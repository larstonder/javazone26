// The macOS GameController -> GLFW-shaped input bridge. See
// docs/superpowers/specs/2026-08-31-macos-gamecontroller-input-design.md.
//
// WHY THIS PROCESS EXISTS. On macOS 26 the Switch Pro Controller is unreadable through GLFW,
// which is Pulse Engine's only input path: macOS claims the pad into GameController.framework
// and what it leaves on the generic HID device is either un-handshaken garbage (USB) or a
// virtual device that enumerates perfectly and never reports (Bluetooth). Measured both ways.
//
// It is a SEPARATE PROCESS rather than a library because GameController.framework wants an
// Objective-C run loop, and the game's callbacks run on Pulse Engine's "game" thread while GLFW
// owns the process's first thread. Verified that a background child process still receives
// controller input while the game window is frontmost: 469 events in 20 s with the game in
// front. That measurement is the whole reason this design is viable, so do not assume it.
//
// PROTOCOL: a fixed-size binary frame, emitted at a FIXED RATE rather than on change so the
// reader can tell "nothing is being pressed" from "the helper has died".
//
//   byte 0      0xA5 sync marker (lets the reader resynchronise rather than drift silently)
//   byte 1      controller count, 0..MAX_PADS
//   then MAX_PADS slots, ALWAYS all of them so the frame size is constant:
//     15 bytes  buttons, indexed by GamepadButton.code (SDL order, read out of the 0.13.0
//               bytecode: A=0 B=1 X=2 Y=3 LEFT_BUMPER=4 RIGHT_BUMPER=5 BACK=6 START=7 GUIDE=8
//               LEFT_THUMB=9 RIGHT_THUMB=10 DPAD_UP=11 DPAD_RIGHT=12 DPAD_DOWN=13 DPAD_LEFT=14)
//     6 floats  axes, little-endian, indexed by GamepadAxis.code
//
// Frame size is therefore 2 + MAX_PADS * (15 + 24) = 158 bytes. render/MacPadBridge.kt holds
// the same three numbers and MacPadBridgeTest asserts they agree with this comment.

import Foundation
import GameController

let MAX_PADS = 4
let BUTTON_COUNT = 15
let AXIS_COUNT = 6
let SLOT_BYTES = BUTTON_COUNT + AXIS_COUNT * 4
let FRAME_BYTES = 2 + MAX_PADS * SLOT_BYTES

var frame = [UInt8](repeating: 0, count: FRAME_BYTES)
frame[0] = 0xA5

@inline(__always)
func putFloat(_ value: Float, _ offset: Int) {
    let bits = value.bitPattern.littleEndian
    frame[offset + 0] = UInt8(truncatingIfNeeded: bits)
    frame[offset + 1] = UInt8(truncatingIfNeeded: bits >> 8)
    frame[offset + 2] = UInt8(truncatingIfNeeded: bits >> 16)
    frame[offset + 3] = UInt8(truncatingIfNeeded: bits >> 24)
}

func sample() {
    let pads = GCController.controllers()
    let n = min(pads.count, MAX_PADS)
    frame[1] = UInt8(n)

    // Every slot is rewritten each frame, including the empty ones: a pad that disconnects must
    // go quiet rather than leave its last state latched in the buffer forever.
    for i in 2 ..< FRAME_BYTES { frame[i] = 0 }

    for i in 0 ..< n {
        guard let pad = pads[i].extendedGamepad else { continue }
        let base = 2 + i * SLOT_BYTES

        @inline(__always) func put(_ code: Int, _ pressed: Bool) {
            frame[base + code] = pressed ? 1 : 0
        }

        // THE FACE BUTTONS ARE SWAPPED ON NINTENDO PADS, AND ONLY ON NINTENDO PADS.
        //
        // GameController names a Nintendo pad's buttons by their PRINTED LABEL, while SDL - and
        // therefore GLFW, GamepadButton.code, application.cfg and every hint this game draws -
        // names them by POSITION, where `A` is the BOTTOM button. On a Switch pad the bottom
        // button is printed B, so the two conventions disagree on all four.
        //
        // Measured, not assumed: pressing ONLY the physical B (bottom) button reported
        // `buttons=[B]` 81 times out of 81 through GCExtendedGamepad. Positional naming would
        // have reported `A`. GLFW's own bundled Switch mapping agrees on the destination
        // convention - `a:b0` where b0 is the bottom button.
        //
        // The swap must NOT be unconditional. Apple reports a DualSense positionally already
        // (its bottom button, Cross, IS buttonA), so swapping every pad would break the one
        // controller this project has previously measured and shipped against.
        let category = pads[i].productCategory
        let nintendo = category.contains("Switch") || category.contains("Joy-Con") || category.contains("Nintendo")

        put(0, (nintendo ? pad.buttonB : pad.buttonA).isPressed)  // SDL A: bottom
        put(1, (nintendo ? pad.buttonA : pad.buttonB).isPressed)  // SDL B: right
        put(2, (nintendo ? pad.buttonY : pad.buttonX).isPressed)  // SDL X: left
        put(3, (nintendo ? pad.buttonX : pad.buttonY).isPressed)  // SDL Y: top
        put(4, pad.leftShoulder.isPressed)
        put(5, pad.rightShoulder.isPressed)
        // BACK/START are Minus/Plus on a Switch pad. GCExtendedGamepad calls them
        // buttonOptions/buttonMenu; both are optionals on the profile, so an older pad that
        // lacks one reads as unpressed rather than crashing the helper.
        put(6, pad.buttonOptions?.isPressed ?? false)
        put(7, pad.buttonMenu.isPressed)
        put(8, pad.buttonHome?.isPressed ?? false)
        put(9, pad.leftThumbstickButton?.isPressed ?? false)
        put(10, pad.rightThumbstickButton?.isPressed ?? false)
        put(11, pad.dpad.up.isPressed)
        put(12, pad.dpad.right.isPressed)
        put(13, pad.dpad.down.isPressed)
        put(14, pad.dpad.left.isPressed)

        let axes = base + BUTTON_COUNT
        // Y IS NEGATED. GameController reports +1 as stick-UP; GLFW (and therefore every
        // consumer in this game, whose world is +y DOWN) reports -1 as stick-up. PadAxis'
        // contract names DPAD_UP as the negative direction, so getting this backwards would
        // invert the dive without failing a single test.
        putFloat(pad.leftThumbstick.xAxis.value, axes + 0)
        putFloat(-pad.leftThumbstick.yAxis.value, axes + 4)
        putFloat(pad.rightThumbstick.xAxis.value, axes + 8)
        putFloat(-pad.rightThumbstick.yAxis.value, axes + 12)
        // TRIGGERS ARE RESCALED. GameController reports 0..1; GLFW reports -1 released to +1
        // fully pressed, which is what GamepadAxis.LEFT_TRIGGER's consumers assume.
        putFloat(pad.leftTrigger.value * 2 - 1, axes + 16)
        putFloat(pad.rightTrigger.value * 2 - 1, axes + 20)
    }
}

func emit() {
    sample()
    let written = frame.withUnsafeBufferPointer { fwrite($0.baseAddress, 1, FRAME_BYTES, stdout) }
    // A short write means the pipe is gone, i.e. the game exited. Leave rather than spin: the
    // helper must never outlive its parent and become an orphan holding the controller.
    if written < FRAME_BYTES { exit(0) }
    fflush(stdout)
}

// SIGPIPE is left at its default (terminate). When the game dies its end of the pipe closes and
// this process goes with it, which is the backstop for the parent being SIGKILLed and never
// getting to tidy up.
GCController.startWirelessControllerDiscovery {}
let timer = Timer(timeInterval: 1.0 / 120.0, repeats: true) { _ in emit() }
RunLoop.current.add(timer, forMode: .common)
RunLoop.current.run()
