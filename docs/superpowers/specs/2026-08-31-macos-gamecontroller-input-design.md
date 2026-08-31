# macOS GameController input bridge

**Status:** approved 2026-08-31. Branch `macos-gamecontroller-input`.

## The problem

On macOS 26 the Nintendo Switch Pro Controller is unusable through GLFW, which is the only
input path Pulse Engine has. macOS claims the pad into `GameController.framework` and keeps the
decoded stream there. What it leaves for generic IOKit HID clients is broken in both transports:

| Transport | HID device GLFW sees | Behaviour |
|---|---|---|
| USB | real (`AppleUserHIDDevice`, `PrimaryUsage=4` Joystick), never handshaken | reports arrive, but they are IMU/status bytes — 227 distinct states in 4 s untouched, axes pinned |
| Bluetooth | **virtual** (`HIDVirtualDevice`, `PrimaryUsage=5` GamePad) | enumerates, `isGamepad=true`, correct GUID, and never delivers a single report |

Measured, not inferred. A `GameController.framework` probe run against the same pad returns
clean input: all face buttons, shoulders, triggers, four D-pad directions and both analog
sticks. The hardware is fine; two separate Pro Controllers behave identically.

A secondary, real defect was found and is NOT worth fixing on its own: GLFW's bundled SDL
mapping for the USB GUID `030000007e0500000920000010020000` references `righty:a4` and
`righttrigger:a5` on a device reporting 4 axes, and GLFW rejects a mapping outright if any
element is out of range — so `glfwJoystickIsGamepad` is false. Installing a fitting mapping
flips it to true instantly, but only makes the garbage reachable.

## Verified constraint: the helper may run in the background

`GameController` input reaches a background child process while the game window is frontmost:
469 events in 20 s with `java` in front. This was tested explicitly because the whole design
rests on it, and an earlier zero-event run had suggested the opposite (it was a missed press).

## Bluetooth only, inherently

The bridge supplies *values* for a pad the engine has already listed; every consumer
(`selectGameplayPad`, `LifecycleInputEdges`, the pause/exit holds) is keyed by GLFW joystick
id. Over Bluetooth the engine lists the pad (`engine.input.gamepads = 1`), so the ids exist and
only the values are dead — which is exactly the gap this fills. Over USB the engine lists
nothing, so there is no id to attach state to. Making USB work would mean bypassing
`engine.input.gamepads` at every input site, and is out of scope.

## Design

A Swift helper reads `GameController.framework` and streams state; a second
`MappedPads.StateReader` serves it. `MappedPads` already takes its reader as a constructor
argument, so nothing downstream changes.

- **`tools/macpad/MacPadBridge.swift`** -> `macpadbridge`. Emits a fixed-size binary record at
  120 Hz: controller count, then 15 button bytes and 6 little-endian floats per controller, in
  SDL code order so it drops straight into `MappedPads`' existing indexing. Fixed rate rather
  than on-change so staleness is detectable.
- **`render/MacPadBridge.kt`** — owns the process and a reader thread; preallocated snapshot
  plus a timestamp. No per-frame allocation; `readMapped` is an `arraycopy`.
- **`render/MacGamepadStateReader.kt`** — implements `StateReader`. Serves the snapshot while
  live, and **delegates to `GlfwGamepadStateReader` otherwise**, so a stale or dead helper
  degrades to today's behaviour rather than to a dead pad. Same booth-safety argument already
  written into `StateReader.readRaw`.
- **Selection** at the one construction site (`EnPustTil.kt:1769`): macOS *and* helper present,
  else the plain GLFW reader. Windows is untouched by construction.
- **Correlation:** the helper knows `GCController`s, not GLFW ids, so they are paired in index
  order. Correct for one pad, which is the target. Documented, not generalised.

## Build and packaging

- A `swiftc` `Exec` task gated to macOS, wired into `run`.
- `buildMacRelease` copies the binary into `Contents/MacOS/`; the runtime resolves the bundle
  path first, then `build/`.
- Signing: when `-PmacSigningIdentity` is passed, the helper needs signing too.

## Testing

The record parser, the staleness rule, the index correlation and the reader-selection logic are
engine-free and unit-tested. Then the game is run and actually played — tests passing is not
evidence here (CLAUDE.md).

## Out of scope

- USB support (see above).
- The `NINTENDO` `ControllerFamily` label fix: on any Nintendo pad the game prints "PRESS A"
  for what is physically the B button, because SDL's `A` is `b0`. Real, separate, small.
