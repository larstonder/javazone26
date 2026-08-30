package render

import no.njoh.pulseengine.core.input.GamepadAxis
import no.njoh.pulseengine.core.input.GamepadButton
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MappedPads] exists because the engine reads a gamepad RAW and indexes it by SDL codes — see
 * its class doc for the bytecode and for the DualSense measurements (`kickButton = A` hitting
 * Square, `restartButton = START` hitting R2, i.e. Options doing nothing).
 *
 * WHAT CAN BE TESTED HERE, AND WHAT CANNOT. `glfwGetGamepadState` needs a live GLFW, and
 * `Gamepad` is a final class whose constructor calls GLFW on the spot — neither is reachable
 * from a headless JVM. So the GLFW half is behind [MappedPads.StateReader] and the core
 * [MappedPads.refresh] takes plain joystick ids, which leaves everything that can actually be
 * WRONG in our code testable: that a read returns what the last refresh put there and not what
 * the one before it did, that a pad that vanished goes quiet instead of stale, that an unknown
 * id is inert rather than an exception, and — the one that matters at a booth — that a pad the
 * mapped read refuses still reports its buttons through the raw fallback rather than going
 * dead in front of a queue.
 *
 * The fake below is the whole GLFW dependency, and it also records how often each entry point
 * was called, because "the fallback ran" and "the fallback ran EVERY frame" are different
 * findings and only one of them is a bug.
 */
class MappedPadsTest
{
    /**
     * A [MappedPads.StateReader] with no GLFW in it. `mapped[id]` is the SDL-mapped state to
     * serve for that id, or null to refuse it (which is what drives the fallback); `raw[id]` is
     * what the raw read then produces.
     */
    private class FakeReader : MappedPads.StateReader
    {
        val mapped = HashMap<Int, Pair<ByteArray, FloatArray>>()
        val raw = HashMap<Int, Pair<ByteArray, FloatArray>>()
        var mappedCalls = 0
        var rawCalls = 0
        var closes = 0

        override fun readMapped(joystickId: Int, buttons: ByteArray, axes: FloatArray): Boolean
        {
            mappedCalls++
            val state = mapped[joystickId] ?: return false
            state.first.copyInto(buttons)
            state.second.copyInto(axes)
            return true
        }

        override fun readRaw(joystickId: Int, buttons: ByteArray, axes: FloatArray)
        {
            rawCalls++
            buttons.fill(0)
            axes.fill(0f)
            val state = raw[joystickId] ?: return
            state.first.copyInto(buttons)
            state.second.copyInto(axes)
        }

        override fun close() { closes++ }
    }

    private fun state(vararg pressed: GamepadButton): Pair<ByteArray, FloatArray>
    {
        val buttons = ByteArray(MappedPads.BUTTON_COUNT)
        for (button in pressed) buttons[button.code] = 1
        return buttons to FloatArray(MappedPads.AXIS_COUNT)
    }

    private fun stateWithAxis(axis: GamepadAxis, value: Float): Pair<ByteArray, FloatArray>
    {
        val axes = FloatArray(MappedPads.AXIS_COUNT)
        axes[axis.code] = value
        return ByteArray(MappedPads.BUTTON_COUNT) to axes
    }

    // ---- the code tables the whole read is indexed by ------------------------------------

    @Test
    fun `every button code from 0 to 14 has a name, and aliases collapse onto one entry`()
    {
        // The cache is a flat array indexed by GamepadButton.code, so a code with no entry in
        // the table would be a hole nothing could ever read or write. Derived rather than
        // hand-listed precisely so this cannot rot — see BUTTON_BY_CODE's doc.
        assertEquals(MappedPads.BUTTON_COUNT, MappedPads.BUTTON_BY_CODE.size)
        for (code in 0 until MappedPads.BUTTON_COUNT)
            assertEquals(code, MappedPads.BUTTON_BY_CODE[code].code, "table entry $code names a different code")

        // A/CROSS, X/SQUARE and DPAD_LEFT/LAST are the same physical button under two names.
        // If the table ever grew one row per NAME instead of one per CODE, this is the
        // assertion that would catch it before a jammed button reported itself twice.
        assertEquals(GamepadButton.A.code, GamepadButton.CROSS.code)
        assertEquals(GamepadButton.X.code, GamepadButton.SQUARE.code)
        assertEquals(GamepadButton.DPAD_LEFT.code, GamepadButton.LAST.code)
        assertEquals(MappedPads.BUTTON_BY_CODE[GamepadButton.A.code], MappedPads.BUTTON_BY_CODE[GamepadButton.CROSS.code])
    }

    @Test
    fun `every axis code from 0 to 5 has a name`()
    {
        assertEquals(MappedPads.AXIS_COUNT, MappedPads.AXIS_BY_CODE.size)
        for (code in 0 until MappedPads.AXIS_COUNT)
            assertEquals(code, MappedPads.AXIS_BY_CODE[code].code, "table entry $code names a different code")
    }

    // ---- the cache ------------------------------------------------------------------------

    @Test
    fun `a read returns the mapped state of the pad with that id, and not another pad's`()
    {
        // The bug this guards is an off-by-one between "slot in engine.input.gamepads" and
        // "joystick id" — the two are equal only when pad 0 is present, which is exactly the
        // configuration a developer tests on and a booth with a second HID does not have.
        val reader = FakeReader()
        reader.mapped[1] = state(GamepadButton.START)
        reader.mapped[3] = state(GamepadButton.A)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(1, 3), 2)

        assertTrue(pads.isPressed(1, GamepadButton.START))
        assertFalse(pads.isPressed(1, GamepadButton.A))
        assertTrue(pads.isPressed(3, GamepadButton.A))
        assertFalse(pads.isPressed(3, GamepadButton.START))
    }

    @Test
    fun `an alias reads the same physical button as its primary name`()
    {
        // The point of the whole change: `kickButton = A` and `kickButton = CROSS` must name
        // one contact, and after the mapped read they finally name the RIGHT one.
        val reader = FakeReader()
        reader.mapped[0] = state(GamepadButton.A)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0), 1)

        assertTrue(pads.isPressed(0, GamepadButton.A))
        assertTrue(pads.isPressed(0, GamepadButton.CROSS))
    }

    @Test
    fun `axes come back by axis code, so the stick is not read as a trigger`()
    {
        // The overlay's `RIGHT_Y=-1.00 LEFT_TRIGGER=-1.00` on an untouched pad was this exact
        // relationship being wrong in the engine: an axis read at the wrong index.
        val reader = FakeReader()
        reader.mapped[0] = stateWithAxis(GamepadAxis.LEFT_Y, -0.75f)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0), 1)

        assertEquals(-0.75f, pads.getAxis(0, GamepadAxis.LEFT_Y), 0.0001f)
        assertEquals(0f, pads.getAxis(0, GamepadAxis.LEFT_TRIGGER), 0.0001f)
        assertEquals(0f, pads.getAxis(0, GamepadAxis.RIGHT_Y), 0.0001f)
    }

    @Test
    fun `a refresh replaces the previous frame's reading rather than accumulating it`()
    {
        // A cache that OR-ed frames together would look fine for a press and would never
        // release — the same class of failure LifecycleInputEdges exists to catch, arriving
        // one layer lower down where no edge detector could see it.
        val reader = FakeReader()
        val pads = MappedPads(reader)

        reader.mapped[0] = state(GamepadButton.START)
        pads.refresh(intArrayOf(0), 1)
        assertTrue(pads.isPressed(0, GamepadButton.START))

        reader.mapped[0] = state()
        pads.refresh(intArrayOf(0), 1)
        assertFalse(pads.isPressed(0, GamepadButton.START), "the button stayed pressed after it was released")
    }

    @Test
    fun `only the first count ids are sampled`()
    {
        // refresh(IntArray, Int) is fed a REUSED buffer whose tail is last frame's ids (see
        // MappedPads.idBuffer), so honouring `count` is what stops an unplugged pad from
        // reappearing out of stale scratch space.
        val reader = FakeReader()
        reader.mapped[0] = state(GamepadButton.A)
        reader.mapped[2] = state(GamepadButton.A)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0, 2), 1)

        assertTrue(pads.isLive(0))
        assertFalse(pads.isLive(2), "an id past `count` was sampled anyway")
        assertFalse(pads.isPressed(2, GamepadButton.A))
    }

    // ---- pads that are not there ----------------------------------------------------------

    @Test
    fun `an unknown pad id reads as unpressed and centred rather than throwing`()
    {
        // readInput passes -1 when selectGameplayPad found no pad at all, which is the
        // everyday case on the attract screen with nothing plugged in. It has to be inert.
        val pads = MappedPads(FakeReader())
        pads.refresh(intArrayOf(), 0)

        for (id in intArrayOf(-1, 0, 7, MappedPads.SLOTS, MappedPads.SLOTS + 100, Int.MIN_VALUE, Int.MAX_VALUE))
        {
            assertFalse(pads.isPressed(id, GamepadButton.START), "pad $id reported a press")
            assertEquals(0f, pads.getAxis(id, GamepadAxis.LEFT_X), 0.0001f, "pad $id reported an axis")
            assertFalse(pads.isLive(id))
            assertFalse(pads.isFallback(id))
        }
    }

    @Test
    fun `a pad that disappears stops reporting the buttons it was holding`()
    {
        // A mid-run unplug (or a chattering connector) must not leave the diver kicking
        // forever off a cached press — the cabinet would look possessed and nothing would
        // clear it. `live` is reset at the top of every refresh for exactly this.
        val reader = FakeReader()
        reader.mapped[0] = state(GamepadButton.A)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0), 1)
        assertTrue(pads.isPressed(0, GamepadButton.A))

        pads.refresh(intArrayOf(), 0)
        assertFalse(pads.isPressed(0, GamepadButton.A), "an unplugged pad kept reporting a held button")
        assertFalse(pads.isLive(0))
    }

    @Test
    fun `an id outside the table is skipped without disturbing the pads beside it`()
    {
        val reader = FakeReader()
        reader.mapped[0] = state(GamepadButton.A)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0, MappedPads.SLOTS, -4), 3)

        assertTrue(pads.isPressed(0, GamepadButton.A))
        assertFalse(pads.isLive(MappedPads.SLOTS))
        assertFalse(pads.isLive(-4))
    }

    // ---- the booth safety fallback ---------------------------------------------------------

    @Test
    fun `a pad the mapped read refuses still reports its buttons through the raw fallback`()
    {
        // THE POINT OF THE FALLBACK. A cabinet with an odd button map is far better than one
        // that stops responding in front of a queue, so a refused pad must degrade to the
        // engine's old raw behaviour rather than going silent.
        val reader = FakeReader()
        reader.raw[0] = state(GamepadButton.START)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0), 1)

        assertTrue(pads.isLive(0))
        assertTrue(pads.isFallback(0))
        assertTrue(pads.isPressed(0, GamepadButton.START), "a refused pad went dead instead of falling back")
        assertEquals(1, reader.rawCalls)
    }

    @Test
    fun `one refused pad does not push the pads beside it onto the fallback`()
    {
        // fallbackUsed is per id, not a single flag. A shared flag would quietly demote a
        // perfectly good cabinet stick because some unrelated HID misbehaved.
        val reader = FakeReader()
        reader.mapped[0] = state(GamepadButton.A)
        reader.raw[1] = state(GamepadButton.B)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0, 1), 2)

        assertFalse(pads.isFallback(0))
        assertTrue(pads.isFallback(1))
        assertTrue(pads.isPressed(0, GamepadButton.A))
        assertTrue(pads.isPressed(1, GamepadButton.B))
    }

    @Test
    fun `a pad that recovers stops being reported as a fallback`()
    {
        // isFallback drives an EPT_DEV overlay tag and a boot log line; a latch that never
        // cleared would tell a technician the mapped read is broken long after it started
        // working, which is the sort of stale diagnostic that costs an afternoon.
        val reader = FakeReader()
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0), 1)
        assertTrue(pads.isFallback(0))

        reader.mapped[0] = state(GamepadButton.A)
        pads.refresh(intArrayOf(0), 1)
        assertFalse(pads.isFallback(0))
        assertTrue(pads.isPressed(0, GamepadButton.A))
    }

    @Test
    fun `the raw fallback is consulted every frame it is needed, not latched on one reading`()
    {
        // The WARN is latched (once per pad per process, so a chattering connector cannot fill
        // the booth log); the READ must not be. A fallback that sampled once and then served
        // that sample forever would be a frozen pad wearing a working pad's clothes.
        val reader = FakeReader()
        reader.raw[0] = state(GamepadButton.START)
        val pads = MappedPads(reader)

        pads.refresh(intArrayOf(0), 1)
        assertTrue(pads.isPressed(0, GamepadButton.START))

        reader.raw[0] = state()
        pads.refresh(intArrayOf(0), 1)
        assertFalse(pads.isPressed(0, GamepadButton.START))
        assertEquals(2, reader.rawCalls)
    }

    @Test
    fun `close is idempotent and leaves every read inert`()
    {
        // close() frees a native GLFWGamepadState. A second free is a JVM crash, and a read
        // that reached the freed struct is a use-after-free — either one turns a tidy shutdown
        // into a crash dialog at the booth.
        val reader = FakeReader()
        reader.mapped[0] = state(GamepadButton.A)
        val pads = MappedPads(reader)
        pads.refresh(intArrayOf(0), 1)

        pads.close()
        pads.close()

        assertEquals(1, reader.closes, "the underlying reader was closed more than once")
        assertFalse(pads.isPressed(0, GamepadButton.A))
        assertFalse(pads.isLive(0))
    }

    // ---- the guard that keeps the fix from being undone ------------------------------------

    @Test
    fun `no production source reads a gamepad outside MappedPads`()
    {
        // In the spirit of DrawTest's `no production source draws a quad or a line`: the engine
        // API that LOOKS right is the broken one, so the only durable defence is a scan.
        // `Gamepad.isPressed`/`getAxis` index the device's RAW HID buffer by an SDL-standard
        // code (bytecode citation in MappedPads' class doc), which is why `restartButton =
        // START` hit R2 on a DualSense and the Options button did nothing at all. Every
        // gameplay and lifecycle read must go through MappedPads.
        //
        // Comments are stripped first, exactly as DrawTest and MainCameraOwnershipTest do it,
        // because this fix is documented at length in the very files being scanned and a scan
        // that read prose would report the explanation as the offence.
        val offenders = productionSources().flatMap { (path, code) ->
            code.lineSequence()
                .filter { line ->
                    ANY_PAD_READ.findAll(line).count() > ALLOWED_PAD_READ.findAll(line).count()
                }
                .map { "$path: ${it.trim()}" }
        }

        assertTrue(
            offenders.isEmpty(),
            "A gamepad was read through something other than MappedPads. `Gamepad.isPressed`/`getAxis` " +
            "return the device's RAW HID order indexed by SDL codes — on a DualSense that made " +
            "`kickButton = A` hit Square and left the Options button doing nothing, silently. Read " +
            "through `mappedPads` (EnPustTil) instead; keyboard reads through `engine.input` are fine. " +
            "See render/MappedPads.kt. Found: $offenders"
        )
    }

    private companion object
    {
        /**
         * Any call to either accessor. The leading `\.` is what keeps `fun isPressed(` in
         * MappedPads' own declarations out of the count — this scan covers that file too, on
         * purpose, so the fallback cannot quietly move back onto a `Gamepad` there either.
         */
        val ANY_PAD_READ = Regex("\\.(isPressed|getAxis)\\s*\\(")

        /**
         * The two receivers that are allowed to answer. `engine.input.isPressed(Key.X)` is the
         * KEYBOARD and has nothing to do with this bug; `mappedPads` is the fix. Counted rather
         * than matched line-by-line so a line holding one of each (`mappedPads.isPressed(...)
         * || engine.input.isPressed(Key.Z)` — readInput has exactly that) is not a false
         * positive, while a line that adds a third, unapproved receiver still is.
         */
        val ALLOWED_PAD_READ = Regex("(engine\\.input|mappedPads)\\.(isPressed|getAxis)\\s*\\(")

        /** Lifted from `DrawTest`, which needs the identical thing for the identical reason. */
        fun productionSources(): List<Pair<String, String>> =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.path to stripComments(it.readText()) }
                .toList()
                .also { assertTrue(it.size > 5, "found only ${it.size} production sources — wrong working directory?") }

        fun stripComments(source: String): String = source
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }
}
