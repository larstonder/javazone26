import no.njoh.pulseengine.core.input.GamepadButton
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The booth may have to remap buttons with Notepad on setup day - see application.cfg. A
 * bad value must always degrade to the compiled default rather than crash or, worse, leave
 * a button silently unbound in front of a queue.
 *
 * Also covers [resolveDeadzone] (the Int/Float coercion gap `stickDeadzone = 0` falls into
 * — same bug family as [resolveDailySeed], see `EnPustTilSeedTest`) and
 * [gamepadButtonConfigWarning] (the caller-side check that tells a typo apart from a
 * deliberate edit, since [parseGamepadButton] itself cannot signal which one produced a
 * fallback).
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

    @Test
    fun `resolveDeadzone reaches zero through the Int shape, which parseDeadzone alone cannot`()
    {
        // THE GAP THIS CLOSES. "0" (the natural way to write "disable the deadzone") is
        // all-digits, so application.cfg's own loader stores it as an Integer, not a Float -
        // engine.config.getFloat("stickDeadzone") returns null for it. A version reading
        // only getFloat/parseDeadzone would silently keep the compiled default here even
        // though "stickDeadzone = 0" is a perfectly legible edit. Empirically verified
        // against the real ConfigurationImpl - see task-6-7-report.md.
        assertEquals(0f, resolveDeadzone(rawInt = 0, rawFloat = null, fallback = 0.2f))
    }

    @Test
    fun `resolveDeadzone still honours a value written with a decimal point`()
    {
        assertEquals(0.35f, resolveDeadzone(rawInt = null, rawFloat = 0.35f, fallback = 0.2f))
    }

    @Test
    fun `resolveDeadzone falls back when neither shape is present`()
    {
        assertEquals(0.2f, resolveDeadzone(rawInt = null, rawFloat = null, fallback = 0.2f))
    }

    @Test
    fun `resolveDeadzone still clamps an out-of-range Int, same as the Float path`()
    {
        assertEquals(0.9f, resolveDeadzone(rawInt = 4, rawFloat = null, fallback = 0.2f))
    }

    @Test
    fun `gamepadButtonConfigWarning is null when the key was never touched`()
    {
        assertEquals(null, gamepadButtonConfigWarning("kickButton", rawString = null, rawInt = null, rawFloat = null, resolved = GamepadButton.A, default = GamepadButton.A))
    }

    @Test
    fun `gamepadButtonConfigWarning is null for a genuinely different, validly-parsed button`()
    {
        // Not a fallback at all - CROSS was recognised and returned as itself, so
        // `resolved != default` short-circuits the warning before the name comparison.
        assertEquals(null, gamepadButtonConfigWarning("kickButton", rawString = "CROSS", rawInt = null, rawFloat = null, resolved = GamepadButton.CROSS, default = GamepadButton.A))
    }

    @Test
    fun `gamepadButtonConfigWarning is null when the technician deliberately wrote the default's own name back`()
    {
        assertEquals(null, gamepadButtonConfigWarning("kickButton", rawString = "  a  ", rawInt = null, rawFloat = null, resolved = GamepadButton.A, default = GamepadButton.A))
    }

    @Test
    fun `gamepadButtonConfigWarning fires for the exact typo this exists to catch`()
    {
        // restartbutton = STRAT: falls back to START (the default), but the raw text was
        // never "START" - this is the case that used to produce no signal anywhere.
        val warning = gamepadButtonConfigWarning("restartButton", rawString = "STRAT", rawInt = null, rawFloat = null, resolved = GamepadButton.START, default = GamepadButton.START)
        assertEquals(true, warning != null && warning.contains("STRAT"))
    }

    @Test
    fun `gamepadButtonConfigWarning fires for a digit typo that coerces to Int - the case a String-only check misses`()
    {
        // kickButton = 0: an earlier version of this function took only a String raw value
        // and saw getString("kickButton") == null (it coerced to Integer instead), which
        // is indistinguishable from the key being entirely absent - silent. GamepadButton
        // has no purely-numeric name, so rawInt present is unconditionally a typo.
        val warning = gamepadButtonConfigWarning("kickButton", rawString = null, rawInt = 0, rawFloat = null, resolved = GamepadButton.A, default = GamepadButton.A)
        assertEquals(true, warning != null && warning.contains("0"))
    }

    @Test
    fun `gamepadButtonConfigWarning fires for a decimal typo that coerces to Float`()
    {
        val warning = gamepadButtonConfigWarning("bleedButton", rawString = null, rawInt = null, rawFloat = 1.5f, resolved = GamepadButton.B, default = GamepadButton.B)
        assertEquals(true, warning != null && warning.contains("1.5"))
    }

    @Test
    fun `no collision warnings for the compiled defaults`()
    {
        // THE CASE THAT NARROWED THIS FUNCTION: the compiled defaults themselves have
        // kickButton == restartButtonAlt (both A), on purpose - see the class doc. A
        // version warning on every pairwise collision would fire this on every untouched
        // booth boot.
        assertEquals(
            emptyList(),
            gamepadButtonCollisionWarnings(GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A)
        )
    }

    @Test
    fun `kickButton and bleedButton on the same button is a collision`()
    {
        val warnings = gamepadButtonCollisionWarnings(GamepadButton.A, GamepadButton.A, GamepadButton.START, GamepadButton.A)
        assertEquals(true, warnings.any { it.contains("kickButton") && it.contains("bleedButton") })
    }

    @Test
    fun `aliases collide even though their names differ - A and CROSS share one physical button`()
    {
        // THE .code FIX. Comparing by name or ordinal would miss this: A and CROSS are
        // different GamepadButton entries (different .name, different .ordinal) but the
        // same physical input (same .code) - verified from the jar's static initialiser.
        val warnings = gamepadButtonCollisionWarnings(GamepadButton.A, GamepadButton.CROSS, GamepadButton.START, GamepadButton.START)
        assertEquals(true, warnings.any { it.contains("kickButton") && it.contains("bleedButton") })
    }

    @Test
    fun `restartButton colliding with restartButtonAlt is NOT reported - it is a supported configuration`()
    {
        // LifecycleInputEdges.offer's duplicate-offer guard exists specifically for this,
        // and it is a reasonable deliberate choice (force both onto a button already known
        // to work) - not a mistake to flag. See the class doc for the RunLifecycle.update
        // source citation backing this.
        val warnings = gamepadButtonCollisionWarnings(GamepadButton.X, GamepadButton.B, GamepadButton.A, GamepadButton.A)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `kickButton colliding with restartButton is NOT reported - the two signals are never live at once`()
    {
        // RunLifecycle.update's PLAYING branch reads only runOver/pauseEdge - the restart
        // signal kickButton would collide with is not consulted while a run is in
        // progress, which is the only time kick matters. Verified against RunLifecycle.kt
        // source, not assumed - see the class doc.
        val warnings = gamepadButtonCollisionWarnings(GamepadButton.START, GamepadButton.B, GamepadButton.START, GamepadButton.A)
        assertEquals(emptyList(), warnings)
    }
}
