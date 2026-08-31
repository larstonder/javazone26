package render

import no.njoh.pulseengine.core.shared.utils.Logger
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * The Kotlin half of the macOS GameController bridge: it owns the `macpadbridge` helper process
 * and keeps the most recent controller state it sent.
 *
 * ## WHY THERE IS A HELPER PROCESS AT ALL
 *
 * On macOS 26 the Switch Pro Controller cannot be read through GLFW, which is the only input
 * path Pulse Engine has. macOS claims the pad into `GameController.framework`; the generic HID
 * device it leaves behind for IOKit clients is un-handshaken garbage over USB (227 distinct
 * states in 4 s with nobody touching it, axes pinned) and, over Bluetooth, a virtual device that
 * enumerates perfectly - `isGamepad = true`, correct GUID, `SDL-mapped` - and never delivers a
 * single report. Both measured. See
 * `docs/superpowers/specs/2026-08-31-macos-gamecontroller-input-design.md`.
 *
 * ## BLUETOOTH ONLY, AND THAT IS INHERENT
 *
 * This supplies VALUES for a pad the engine has already listed. Every consumer in this project -
 * [selectGameplayPad], [LifecycleInputEdges], the pause and exit holds - is keyed by GLFW
 * joystick id, and over Bluetooth the engine does list the pad (`engine.input.gamepads = 1`), so
 * the ids exist and only the values are dead. That is exactly the gap this fills. Over USB the
 * engine lists nothing, there is no id to attach state to, and this cannot help; making USB work
 * would mean bypassing `engine.input.gamepads` at every input site.
 *
 * ## THE FRAME, AND WHY IT IS FIXED-RATE AND FIXED-SIZE
 *
 * `tools/macpad/MacPadBridge.swift` emits [FRAME_BYTES] bytes at 120 Hz:
 *
 * ```
 *   byte 0   0xA5 sync marker
 *   byte 1   controller count, 0..MAX_PADS
 *   then MAX_PADS slots, always all of them:
 *     15 bytes buttons indexed by GamepadButton.code
 *     6 floats axes (little-endian) indexed by GamepadAxis.code
 * ```
 *
 * FIXED RATE rather than on-change is what makes staleness detectable: a helper that has died
 * and a controller nobody is touching produce identical CONTENT, and only the arrival of frames
 * separates them. [isLive] is that test, and it is why a dead helper degrades to the GLFW read
 * instead of to a pad that silently does nothing.
 *
 * FIXED SIZE keeps the reader a blocking `readFully` into one preallocated array - no framing
 * logic, no growth, nothing allocated per frame. The sync marker is not redundant with that: if
 * the stream ever desynchronises (a partial write, a helper restarted mid-frame) a reader
 * without a marker would silently reinterpret axis bytes as buttons forever, which presents as
 * a possessed controller rather than as an error.
 *
 * ## THREADING
 *
 * The helper's pipe is drained by one daemon thread, which parks in `readFully` and never
 * touches the game loop. It publishes into [snapshot] under [lock]; [copyInto] takes the same
 * lock and `arraycopy`s out. The lock is uncontended in practice (one writer at 120 Hz, one
 * reader at 60 Hz, both holding it for a few hundred nanoseconds) and CLAUDE.md's
 * no-per-frame-allocation rule is respected: nothing here allocates after construction.
 */
class MacPadBridge(private val helper: File)
{
    private val lock = Any()

    /** Latest frame, written by the reader thread and read by the game thread. Never reallocated. */
    private val snapshot = ByteArray(FRAME_BYTES)

    /** `System.nanoTime()` of the last complete frame, or 0 before the first. Guarded by [lock]. */
    private var lastFrameNanos = 0L

    private var process: Process? = null
    private var thread: Thread? = null

    @Volatile private var closed = false
    private var warnedDesync = false

    /**
     * Starts the helper. Returns false if it could not be launched, in which case this bridge
     * stays permanently not-[isLive] and [MacGamepadStateReader] falls back to GLFW - a missing
     * or unrunnable helper must degrade, never throw, because this runs during `createGame`.
     */
    fun start(): Boolean
    {
        if (!helper.canExecute())
        {
            Logger.warn { "MACPAD: helper '$helper' is missing or not executable - falling back to the GLFW gamepad read." }
            return false
        }
        return try
        {
            val p = ProcessBuilder(helper.absolutePath)
                .redirectErrorStream(false)
                // The helper's stderr is inherited so anything it complains about lands in the
                // same log BoothLog is already teeing, rather than filling a pipe nobody drains
                // and blocking the helper forever.
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            process = p
            thread = Thread({ pump(p.inputStream) }, "macpad-bridge").apply {
                isDaemon = true
                start()
            }
            Logger.warn { "MACPAD: GameController bridge started from '$helper'." }
            true
        }
        catch (e: Exception)
        {
            Logger.warn { "MACPAD: could not start the helper '$helper' (${e.message}) - falling back to the GLFW gamepad read." }
            false
        }
    }

    private fun pump(stream: InputStream)
    {
        val input = DataInputStream(stream.buffered(FRAME_BYTES * 4))
        val buffer = ByteArray(FRAME_BYTES)
        try
        {
            while (!closed)
            {
                input.readFully(buffer)
                if (buffer[0] != SYNC)
                {
                    if (!warnedDesync)
                    {
                        warnedDesync = true
                        Logger.warn { "MACPAD: frame desynchronised (expected 0xA5, got ${buffer[0]}) - resynchronising." }
                    }
                    // Resynchronise by consuming single bytes until the marker turns up again,
                    // rather than reinterpreting the stream at the wrong offset for the rest of
                    // the process.
                    while (!closed && input.readByte() != SYNC) { /* skip */ }
                    input.readFully(buffer, 1, FRAME_BYTES - 1)
                    buffer[0] = SYNC
                }
                synchronized(lock)
                {
                    System.arraycopy(buffer, 0, snapshot, 0, FRAME_BYTES)
                    lastFrameNanos = System.nanoTime()
                }
            }
        }
        catch (e: Exception)
        {
            // EOF is the normal shutdown path (close() destroys the process), so it is only
            // worth a line when we did not ask for it.
            if (!closed) Logger.warn { "MACPAD: bridge stream ended (${e.message}) - falling back to the GLFW gamepad read." }
        }
    }

    /** True if a frame arrived recently enough to trust. See the class doc on fixed-rate framing. */
    fun isLive(): Boolean
    {
        if (closed) return false
        synchronized(lock)
        {
            if (lastFrameNanos == 0L) return false
            return System.nanoTime() - lastFrameNanos < STALE_AFTER_NANOS
        }
    }

    /** Controllers the helper reported in the last frame, or 0 if none has arrived. */
    fun controllerCount(): Int
    {
        synchronized(lock)
        {
            if (lastFrameNanos == 0L) return 0
            return snapshot[1].toInt().coerceIn(0, MAX_PADS)
        }
    }

    /**
     * Copies slot [slot]'s state into [buttons] (15) and [axes] (6). Returns false if the slot
     * is beyond what the helper reported, leaving both arrays untouched.
     */
    fun copyInto(slot: Int, buttons: ByteArray, axes: FloatArray): Boolean
    {
        if (slot < 0 || slot >= MAX_PADS) return false
        synchronized(lock)
        {
            if (lastFrameNanos == 0L) return false
            if (slot >= snapshot[1].toInt()) return false
            decodeSlot(snapshot, slot, buttons, axes)
            return true
        }
    }

    /** Stops the helper. Idempotent, and every read afterwards is inert rather than a hang. */
    fun close()
    {
        if (closed) return
        closed = true
        process?.destroy()
        process = null
        thread = null
    }

    companion object
    {
        const val MAX_PADS = 4
        const val BUTTON_COUNT = 15
        const val AXIS_COUNT = 6

        /** One slot: 15 button bytes then 6 little-endian floats. */
        const val SLOT_BYTES = BUTTON_COUNT + AXIS_COUNT * 4

        /** Sync marker + count + every slot. Mirrored in `tools/macpad/MacPadBridge.swift`. */
        const val FRAME_BYTES = 2 + MAX_PADS * SLOT_BYTES

        const val SYNC: Byte = 0xA5.toByte()

        /**
         * The helper emits at 120 Hz, so ~60 missed frames. Long enough that a scheduling hiccup
         * or a GC pause cannot flap the pad off, short enough that a player notices nothing worse
         * than half a second of a control scheme they were not using anyway before the GLFW
         * fallback takes over.
         */
        const val STALE_AFTER_NANOS = 500_000_000L

        /**
         * Decodes one slot out of a raw frame. Split out as a pure function over plain arrays so
         * the wire format can be asserted with no process, no thread and no macOS - the
         * pure-logic-extracted-for-testing convention in CLAUDE.md.
         */
        fun decodeSlot(frame: ByteArray, slot: Int, buttons: ByteArray, axes: FloatArray)
        {
            val base = 2 + slot * SLOT_BYTES
            for (code in 0 until BUTTON_COUNT) buttons[code] = frame[base + code]
            val axisBase = base + BUTTON_COUNT
            for (code in 0 until AXIS_COUNT)
            {
                val o = axisBase + code * 4
                val bits = (frame[o].toInt() and 0xFF) or
                           ((frame[o + 1].toInt() and 0xFF) shl 8) or
                           ((frame[o + 2].toInt() and 0xFF) shl 16) or
                           ((frame[o + 3].toInt() and 0xFF) shl 24)
                axes[code] = Float.fromBits(bits)
            }
        }
    }
}
