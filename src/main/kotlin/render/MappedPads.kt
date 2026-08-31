package render

import no.njoh.pulseengine.core.input.Gamepad
import no.njoh.pulseengine.core.input.GamepadAxis
import no.njoh.pulseengine.core.input.GamepadButton
import no.njoh.pulseengine.core.shared.utils.Logger
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWGamepadState
import kotlin.math.min

/**
 * THE ONLY CORRECT WAY TO READ A GAMEPAD IN THIS PROJECT. `Gamepad.isPressed` /
 * `Gamepad.getAxis` are not — they return the device's RAW HID order under SDL-standard
 * names, and on a real pad those two orders disagree.
 *
 * ## THE ENGINE BUG, FROM THE 0.13.0 BYTECODE
 *
 * `no.njoh.pulseengine.core.input.Gamepad.updateState()` is two calls and nothing else:
 *
 * ```
 * GLFW.glfwGetJoystickAxes(id)      -> this.axes
 * GLFW.glfwGetJoystickButtons(id)   -> this.buttons
 * ```
 *
 * Both are the RAW joystick APIs — the device's own HID report order, unmapped. `isPressed`
 * then does `buttons.get(button.getCode()) > 0` and `getAxis` does `axes.get(axis.getCode())`,
 * indexing that raw buffer by the **SDL-standard** code the enum carries (`A`/`CROSS` = 0,
 * `B`/`CIRCLE` = 1, ..., `START` = 7; `LEFT_X` = 0 ... `RIGHT_TRIGGER` = 5 — all read out of
 * `GamepadButton`/`GamepadAxis`'s static initialisers, not assumed).
 *
 * Meanwhile `InputImpl` only constructs a `Gamepad` for a joystick that passes
 * `glfwJoystickPresent(i) && glfwJoystickIsGamepad(i)`, and `Gamepad.id` IS that raw GLFW
 * joystick index. So the engine **filters to SDL-gamepad-mapped devices and then reads them
 * raw anyway**, which makes every name in `GamepadButton` fiction on any pad whose HID order
 * is not the SDL order. Nothing errors and nothing logs; the buttons are simply the wrong ones.
 *
 * ## WHAT IT ACTUALLY DID, ON THE OWNER'S OWN PAD
 *
 * Measured on a DualSense (`glfwGetJoystickName(0)` == `"DualSense Wireless Controller"`,
 * gamepad-mapped, so it was in `engine.input.gamepads` and this whole path applied to it):
 *
 * | application.cfg | code | raw button actually hit | should be |
 * |---|---|---|---|
 * | `kickButton = A`        | 0 | Square | Cross   |
 * | `bleedButton = B`       | 1 | Cross  | Circle  |
 * | `restartButton = START` | 7 | R2     | Options |
 *
 * `restartButton` landing on a trigger is the one that matters most: **OPTIONS DID NOTHING**,
 * i.e. the button the attract screen tells a queue to press was wired to nothing the game read.
 * The left stick worked only by luck — axes 0/1 are LX/LY in both orders — while the EPT_DEV
 * overlay reading `RIGHT_Y=-1.00 LEFT_TRIGGER=-1.00` on an untouched pad was the same defect
 * showing up in the axes.
 *
 * ## THE FIX
 *
 * `GLFW.glfwGetGamepadState(jid, state)` returns the **SDL-mapped** state — 15 buttons and 6
 * axes in exactly the order `GamepadButton.code` / `GamepadAxis.code` already assume. Reading
 * that ourselves is the whole repair: no config change and no label change. `ControlHints` and
 * [ControllerFamily] were already printing `CROSS kick` for `kickButton = A`; that sentence
 * only became TRUE with this file.
 *
 * It is safe precisely because every device in `engine.input.gamepads` has already passed
 * `glfwJoystickIsGamepad`, which is exactly the precondition `glfwGetGamepadState` needs. The
 * fallback below exists anyway — see [StateReader.readRaw].
 *
 * ## ONE READ PER FRAME, CACHED, ALLOCATION-FREE
 *
 * [refresh] is called ONCE per frame and caches everything into primitive arrays keyed by
 * joystick id; every later [isPressed] / [getAxis] is two bounds checks and an array read.
 * That is not only a cost decision, it is a CONSISTENCY one. `readInput` runs on the fixed tick
 * and the lifecycle reads run on the update tick, so without a snapshot a frame with two fixed
 * steps could see a button pressed in one step and released in the next, and a lifecycle edge
 * could be taken against a different sample than gameplay used. The engine's own `Gamepad` has
 * exactly this shape already (`InputImpl.pollEvents` calls `updateState()` once per frame from
 * `beginFrame`, before `onUpdate`), so this changes the cadence of nothing.
 *
 * CLAUDE.md forbids allocation on the per-frame path, so [refresh] is indexed loops over
 * fixed-capacity arrays sized at construction — no `List`, no lambda, no iterator, no
 * `GLFWGamepadState` per call. See `EnPustTil.gamepadIdBuffer`'s doc for the house pattern and
 * for the two `Iterator`s this codebase has already had to hunt down on this exact path.
 *
 * ## PURE-LOGIC-EXTRACTED-FOR-TESTING
 *
 * Both GLFW calls live behind [StateReader], and the core [refresh] takes plain joystick IDS
 * rather than `Gamepad` values, so the cache, the unknown-pad behaviour and the fallback can
 * all be asserted with no GL context and no GLFW init at all — the same reason [Framing],
 * [PadAxis], [ControlHints] and `RunLifecycle` are engine-free. A `Gamepad` is not something a
 * unit test can hold: the class is final and its constructor calls `updateState()`, i.e. GLFW,
 * on the spot. [GlfwGamepadStateReader] is the one implementation that touches GLFW and the
 * one thing here a unit test may not construct.
 */
class MappedPads(private val reader: StateReader)
{
    /**
     * Both reads, injectable so [MappedPads] itself needs neither GLFW nor a window.
     *
     * @see GlfwGamepadStateReader
     */
    interface StateReader
    {
        /**
         * Fills [buttons] (15, indexed by `GamepadButton.code`) and [axes] (6, indexed by
         * `GamepadAxis.code`) with the SDL-MAPPED state of raw GLFW joystick [joystickId].
         *
         * Returns false if no mapped state is available, in which case the arrays' contents
         * are undefined and [MappedPads.refresh] falls back to [readRaw]. An implementation
         * must not throw: a throw here reaches `CallbackGuard` and costs a whole frame of
         * input.
         */
        /**
         * Called once at the top of every [MappedPads.refresh], before any [readMapped].
         *
         * Exists for [MacGamepadStateReader], which pairs GameController slots to GLFW joystick
         * ids BY POSITION and therefore needs to know where a frame begins. Defaulted to nothing
         * so [GlfwGamepadStateReader] - which is stateless across a frame - is unaffected.
         */
        fun beginFrame() {}

        fun readMapped(joystickId: Int, buttons: ByteArray, axes: FloatArray): Boolean

        /**
         * MANDATORY BOOTH SAFETY FALLBACK: the same two arrays, filled with the device's RAW
         * (unmapped) state — the reading the engine's own `Gamepad.isPressed`/`getAxis` would
         * have given.
         *
         * This should be unreachable. `InputImpl` only lists a device that passed
         * `glfwJoystickIsGamepad`, which is precisely what `glfwGetGamepadState` checks before
         * filling its struct. "Should be unreachable" is not a thing to bet a two-day
         * unattended arcade cabinet on: if a pad were dropped between the engine's poll and
         * ours — a chattering connector, which `LifecycleInputEdges.chatterCount` exists
         * because it is a REAL failure mode here — then a `MappedPads` with no fallback would
         * leave the cabinet with a stick and buttons that do nothing at all. **A cabinet with
         * an odd button map is far better than a dead one in front of a queue**, so the pad
         * degrades to exactly the behaviour it had before this file existed instead of going
         * silent.
         *
         * Must fill every element (zeroing what it cannot read) and must not throw.
         */
        fun readRaw(joystickId: Int, buttons: ByteArray, axes: FloatArray)

        /** Releases anything native. Must be idempotent — see [MappedPads.close]. */
        fun close()
    }

    // Everything below is keyed by RAW GLFW JOYSTICK ID (0..15), not by the pad's position in
    // engine.input.gamepads. The id is what every caller already has (LifecycleInputEdges is
    // keyed by pad.id, selectGameplayPad returns an id, the overlay prints one), and it is
    // stable across a frame in which a lower-numbered pad is unplugged — a list index is not.
    // GLFW_JOYSTICK_1..GLFW_JOYSTICK_LAST is 0..15 by definition, so SLOTS is a COMPLETE table
    // and a lookup is a bounds check plus an index rather than a search.

    /** True for each id present in the last [refresh]. Reset every call; that is what makes an unplugged pad go quiet rather than stale. */
    private val live = BooleanArray(SLOTS)

    /** True where [refresh] had to use the raw fallback for that id. Surfaced by [isFallback] for the EPT_DEV overlay and the boot log. */
    private val fallbackUsed = BooleanArray(SLOTS)

    /** One-shot latch per id for the fallback WARN — see [warnFallbackOnce]. */
    private val warnedFallback = BooleanArray(SLOTS)

    /** One-shot latch for the impossible-id WARN in [refresh]. */
    private var warnedOutOfRangeId = false

    private val buttons = Array(SLOTS) { ByteArray(BUTTON_COUNT) }
    private val axes = Array(SLOTS) { FloatArray(AXIS_COUNT) }

    /** Scratch for the [refresh]`(List<Gamepad>)` adapter. Sized once; see that overload. */
    private val idBuffer = IntArray(SLOTS)

    private var closed = false

    /**
     * The engine-facing entry point, and a thin adapter by design: it reads nothing off a
     * `Gamepad` but its `id`, then hands ids to the core overload. That is what keeps the core
     * — the cache, the bounds behaviour and the fallback branch — assertable in a headless
     * JVM, since `Gamepad` is final and its constructor calls GLFW.
     *
     * Indexed loop, no iterator: this is the per-frame path (CLAUDE.md). `pads` is a
     * `java.util.List`, so `pads.indices`/`pads[i]` allocate nothing.
     */
    fun refresh(pads: List<Gamepad>)
    {
        var count = 0
        for (i in pads.indices)
        {
            if (count >= SLOTS) break
            idBuffer[count++] = pads[i].id
        }
        refresh(idBuffer, count)
    }

    /**
     * Samples the first [count] ids of [padIds]. **Call this exactly once per frame, before
     * anything reads it** — `EnPustTil.updateGame`'s first statement, which the engine runs
     * after `pollEvents` and before every `onFixedUpdate` and `onRender` of the same frame
     * (order verified from `PulseEngineImpl.beginFrame`/`tick`).
     */
    fun refresh(padIds: IntArray, count: Int)
    {
        live.fill(false)
        reader.beginFrame()

        val n = min(count, padIds.size)
        for (i in 0 until n)
        {
            val id = padIds[i]

            // Cannot happen — GLFW_JOYSTICK_LAST is 15 — but a table lookup that trusted an id
            // it had not bounded would be an ArrayIndexOutOfBounds inside CallbackGuard, i.e. a
            // whole frame of dead input, rather than a log line. Latched, not one line a frame.
            if (id < 0 || id >= SLOTS)
            {
                if (!warnedOutOfRangeId)
                {
                    warnedOutOfRangeId = true
                    Logger.warn { "GAMEPAD: joystick id $id is outside 0..${SLOTS - 1} - ignored. This should be impossible; report it." }
                }
                continue
            }

            live[id] = true
            val b = buttons[id]
            val a = axes[id]

            // After close() the reader's struct is freed and readMapped returns false, so a
            // read past shutdown routes to the raw fallback rather than touching freed memory.
            if (reader.readMapped(id, b, a))
            {
                fallbackUsed[id] = false
            }
            else
            {
                fallbackUsed[id] = true
                warnFallbackOnce(id)
                reader.readRaw(id, b, a)
            }
        }
    }

    /**
     * Latched per id and never reset, deliberately. A loose connector re-enumerating at 60 Hz
     * is a documented failure mode of the booth's own encoder, and a warning that re-armed on
     * every recovery would fill two days of booth log with one line per frame — burying the
     * things `BoothLog` exists to preserve. One line per pad per process is enough to tell a
     * technician which device took the fallback, and the EPT_DEV overlay flags it live.
     */
    private fun warnFallbackOnce(id: Int)
    {
        if (warnedFallback[id]) return
        warnedFallback[id] = true
        Logger.warn {
            "GAMEPAD: glfwGetGamepadState failed for pad $id although the engine listed it as gamepad-mapped - " +
            "falling back to the RAW (unmapped) read for this pad, so its buttons may not match their names. " +
            "The pad still works. See render/MappedPads.kt."
        }
    }

    /** True if [button] is down on [padId] as of the last [refresh]. Unknown or absent pad, or a code outside the table: false. */
    fun isPressed(padId: Int, button: GamepadButton): Boolean
    {
        if (padId < 0 || padId >= SLOTS || !live[padId]) return false
        val code = button.code
        if (code < 0 || code >= BUTTON_COUNT) return false
        return buttons[padId][code] > 0
    }

    /**
     * [axis]'s value on [padId] as of the last [refresh]. Unknown or absent pad, or a code
     * outside the table: 0 — the rest position, so a missing pad reads as "nobody is touching
     * it" and every caller's existing `?: 0f` behaviour is preserved without a null.
     */
    fun getAxis(padId: Int, axis: GamepadAxis): Float
    {
        if (padId < 0 || padId >= SLOTS || !live[padId]) return 0f
        val code = axis.code
        if (code < 0 || code >= AXIS_COUNT) return 0f
        return axes[padId][code]
    }

    /** True if [padId] was sampled in the last [refresh]. */
    fun isLive(padId: Int): Boolean = padId >= 0 && padId < SLOTS && live[padId]

    /**
     * True if [padId] is live but took the raw fallback. A diagnostic only: the EPT_DEV overlay
     * flags it and [EnPustTil] logs it once at boot, because a technician comparing the overlay
     * against a physical button needs to know when the overlay is reporting unmapped codes.
     */
    fun isFallback(padId: Int): Boolean = padId >= 0 && padId < SLOTS && live[padId] && fallbackUsed[padId]

    /**
     * Frees the reader's native struct. Wired to `EnPustTil.destroyGame`.
     *
     * A LEAKED `GLFWGamepadState` MATTERS LESS THAN A CRASH — which is why the struct is
     * allocated once and held rather than scoped per call, and why nothing here is
     * `AutoCloseable` on a frame scope: it is a few dozen bytes of native memory held for the
     * life of a process that is about to exit either way, so failing to free it costs nothing
     * observable. It is still wrong to leak, and `onDestroy` is a callback the engine genuinely
     * does make on a clean shutdown (see `ScoreRepository`'s class doc for the verification of
     * that ordering), so it is freed there. Idempotent, and every read after it is inert rather
     * than a use-after-free — that is the one way this could turn a tidy shutdown into a JVM
     * crash in front of a queue.
     */
    fun close()
    {
        if (closed) return
        closed = true
        live.fill(false)
        reader.close()
    }

    companion object
    {
        /** `GLFW_JOYSTICK_1..GLFW_JOYSTICK_LAST` is 0..15, so this table is complete by construction. */
        const val SLOTS = 16

        /** `GLFW_GAMEPAD_BUTTON_LAST` is 14; `Gamepad`'s own buffer is `ByteBuffer.allocate(15)` for the same reason. */
        const val BUTTON_COUNT = 15

        /** `GLFW_GAMEPAD_AXIS_LAST` is 5; `Gamepad`'s own buffer is `FloatBuffer.allocate(6)`. */
        const val AXIS_COUNT = 6

        /**
         * One `GamepadButton` per code 0..14, DERIVED rather than hand-listed.
         *
         * `GamepadButton` has twenty entries over fifteen codes — `A`/`CROSS` are both 0,
         * `X`/`SQUARE` both 2, `DPAD_LEFT`/`LAST` both 14, and so on (read out of the enum's
         * static initialiser, where each alias is constructed with the primary's `.code`). A
         * hand-written table of fifteen names would be a second place for that aliasing to be
         * got wrong; first-entry-wins over `entries` cannot be, and it yields the primary names
         * because the aliases are declared last.
         *
         * Built once at class init, so it costs nothing on the frame path. Used by tests and by
         * any reader that needs to go from a code back to a name.
         */
        val BUTTON_BY_CODE: Array<GamepadButton> = run {
            val byCode = arrayOfNulls<GamepadButton>(BUTTON_COUNT)
            for (button in GamepadButton.entries)
            {
                val code = button.code
                if (code in 0 until BUTTON_COUNT && byCode[code] == null) byCode[code] = button
            }
            Array(BUTTON_COUNT) { byCode[it] ?: error("GamepadButton declares no entry for code $it") }
        }

        /** One `GamepadAxis` per code 0..5, derived for the same reason as [BUTTON_BY_CODE] (no aliases today, but the derivation is free). */
        val AXIS_BY_CODE: Array<GamepadAxis> = run {
            val byCode = arrayOfNulls<GamepadAxis>(AXIS_COUNT)
            for (axis in GamepadAxis.entries)
            {
                val code = axis.code
                if (code in 0 until AXIS_COUNT && byCode[code] == null) byCode[code] = axis
            }
            Array(AXIS_COUNT) { byCode[it] ?: error("GamepadAxis declares no entry for code $it") }
        }
    }
}

/**
 * The real [MappedPads.StateReader]: `glfwGetGamepadState` into ONE `GLFWGamepadState`
 * allocated at construction and reused for the life of the process, with the raw joystick APIs
 * as the fallback.
 *
 * ONE STRUCT, NEVER ONE PER CALL. `GLFWGamepadState.calloc()` is a native allocation and this
 * is read once per connected pad per frame, so a per-call `calloc` would be malloc traffic on
 * exactly the path CLAUDE.md forbids allocation on — plus a leak unless every call also freed
 * it. `GLFWGamepadState.malloc(stack)` on LWJGL's `MemoryStack` is the other idiomatic answer,
 * but it needs a `stackPush()`/`pop` pair around every read and buys nothing here: the struct is
 * written and fully consumed inside [readMapped] before the next read is taken, so a single
 * long-lived buffer is both correct and simpler to reason about.
 *
 * Kept out of [MappedPads] itself so a unit test can exercise the cache and the fallback with a
 * fake reader and no GLFW at all — see [MappedPads]' class doc.
 */
class GlfwGamepadStateReader : MappedPads.StateReader
{
    private val state = GLFWGamepadState.calloc()
    private var closed = false

    override fun readMapped(joystickId: Int, buttons: ByteArray, axes: FloatArray): Boolean
    {
        // After close() the struct's memory is freed and reading it would be a use-after-free.
        // False routes the caller to readRaw, which is the correct answer for "this reader can
        // no longer tell you anything".
        if (closed) return false

        if (!GLFW.glfwGetGamepadState(joystickId, state)) return false

        // Indexed over the CALLER's arrays rather than over a constant, so a future change to
        // MappedPads.BUTTON_COUNT/AXIS_COUNT cannot leave this loop writing past a row or
        // short-filling one. GLFWGamepadState is fixed at 15/6 by GLFW itself, so these are the
        // same two numbers seen from the other side.
        for (code in buttons.indices) buttons[code] = state.buttons(code)
        for (code in axes.indices) axes[code] = state.axes(code)
        return true
    }

    /**
     * THE RAW READ, WHICH IS `Gamepad.isPressed`/`getAxis` WITH THE INDIRECTION REMOVED.
     *
     * `Gamepad.updateState()` is literally `glfwGetJoystickAxes(id)` and
     * `glfwGetJoystickButtons(id)` stored to two fields, and `isPressed`/`getAxis` then index
     * those buffers by `.code` (bytecode in [MappedPads]' class doc). Calling the same two GLFW
     * functions here therefore produces the same values by construction, while keeping this
     * class's dependency on a plain joystick id rather than on a `Gamepad` instance the core
     * would otherwise have to carry around purely for a branch that should never run.
     *
     * IT ALSO FIXES A CRASH THE ENGINE'S VERSION HAS. `updateState` REPLACES its buffer with
     * whatever GLFW returns, whose capacity is the DEVICE'S OWN raw count — not 15. A pad
     * reporting twelve raw buttons makes `pad.isPressed(DPAD_LEFT)` (code 14) throw
     * `IndexOutOfBoundsException` out of `ByteBuffer.get(int)`, which on this path would be a
     * dead frame inside `CallbackGuard`. The `min` below clamps instead, and the unread tail
     * stays at the zero written first — an absent button reads as unpressed, which is right.
     *
     * The `ByteBuffer`/`FloatBuffer` wrappers LWJGL returns are an allocation, and this is on
     * the per-frame path. Accepted, narrowly: this branch is the one that should never execute,
     * and while it does the alternative is a cabinet with no input at all. `Gamepad.updateState`
     * already made both of these calls on every frame of every build before this file existed.
     */
    override fun readRaw(joystickId: Int, buttons: ByteArray, axes: FloatArray)
    {
        buttons.fill(0)
        axes.fill(0f)

        val rawButtons = GLFW.glfwGetJoystickButtons(joystickId)
        if (rawButtons != null)
        {
            val n = min(rawButtons.remaining(), buttons.size)
            for (code in 0 until n) buttons[code] = rawButtons.get(code)
        }

        val rawAxes = GLFW.glfwGetJoystickAxes(joystickId)
        if (rawAxes != null)
        {
            val n = min(rawAxes.remaining(), axes.size)
            for (code in 0 until n) axes[code] = rawAxes.get(code)
        }
    }

    override fun close()
    {
        if (closed) return
        closed = true
        state.free()
    }
}
