import no.njoh.pulseengine.core.input.GamepadButton
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The booth may have to remap buttons with Notepad on setup day - see application.cfg. A
 * bad value must always degrade to the compiled default rather than crash or, worse, leave
 * a button silently unbound in front of a queue.
 */
class EnPustTilGamepadConfigTest
{
    @Test
    fun `a valid button name is honoured`()
    {
        assertEquals(GamepadButton.X, parseGamepadButton("X", GamepadButton.A))
    }

    @Test
    fun `case and surrounding whitespace do not matter`()
    {
        // The person editing this file is standing at a booth with a screwdriver.
        assertEquals(GamepadButton.START, parseGamepadButton("  start  ", GamepadButton.A))
    }

    @Test
    fun `an absent key falls back to the compiled default`()
    {
        assertEquals(GamepadButton.A, parseGamepadButton(null, GamepadButton.A))
    }

    @Test
    fun `a name that is not a button falls back rather than throwing`()
    {
        // A typo at the booth must never crash the cabinet, and must never leave kick
        // unbound - the same reasoning as parseDailySeed.
        assertEquals(GamepadButton.A, parseGamepadButton("BUTTON_1", GamepadButton.A))
        assertEquals(GamepadButton.B, parseGamepadButton("", GamepadButton.B))
        // TRIANGLE is deliberately NOT the negative case here - it IS a real entry
        // (verified: javap -p no/njoh/pulseengine/core/input/GamepadButton.class lists the
        // PlayStation aliases CROSS/CIRCLE/SQUARE/TRIANGLE alongside A/B/X/Y).
        assertEquals(GamepadButton.TRIANGLE, parseGamepadButton("triangle", GamepadButton.A))
    }

    @Test
    fun `a deadzone is clamped to something usable`()
    {
        // 1.0 or above would make the stick permanently dead; a negative would make a
        // resting stick read as full deflection and the diver would swim on its own.
        assertEquals(0.35f, parseDeadzone(0.35f, 0.2f))
        assertEquals(0.2f, parseDeadzone(null, 0.2f))
        assertEquals(0.9f, parseDeadzone(4f, 0.2f))
        assertEquals(0f, parseDeadzone(-1f, 0.2f))
    }
}
