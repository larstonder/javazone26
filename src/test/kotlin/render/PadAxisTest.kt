package render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PadAxis] is the D-pad's only route into the game, and the booth failure it was written for
 * — an arcade encoder whose joystick enumerates as a hat, so the cabinet starts and the diver
 * never moves — is invisible in every other test in this project, because nothing else here
 * can hold a `Gamepad`.
 *
 * The cases below are chosen to be ones that can actually FAIL: each pins a relationship that
 * a plausible rewrite of `resolve` would break. In particular, `analog passes through at full
 * magnitude` is the guard against someone "tidying" the stick into the same -1/0/+1 shape the
 * D-pad and keyboard use, which compiles, passes any test that only checks the sign, and
 * silently reduces every analog pad in the world to a hat.
 */
class PadAxisTest
{
    @Test
    fun `a live analog stick wins over both the d-pad and the keyboard`()
    {
        // All three devices live and disagreeing: the stick must be the one that is heard,
        // and the others must not be mixed in.
        assertEquals(
            -0.8f, PadAxis.resolve(-0.8f, negativePressed = false, positivePressed = true, keyboard = 1f), 0f,
            "a deflected stick must not be overridden or diluted by a pressed d-pad or key"
        )
        assertEquals(
            0.6f, PadAxis.resolve(0.6f, negativePressed = true, positivePressed = false, keyboard = -1f), 0f,
            "precedence is first-live-source-wins, not a sum of the three devices"
        )
    }

    @Test
    fun `analog passes through at full magnitude rather than being quantised`()
    {
        // The swim model reads the stick proportionally, so a half-deflected stick must stay
        // half-deflected. Quantising to +/-1 here would turn every analog pad into a d-pad.
        assertEquals(0.4f, PadAxis.resolve(0.4f, false, false, keyboard = 0f), 0f, "0.4 must not become 1.0")
        assertEquals(-0.4f, PadAxis.resolve(-0.4f, false, false, keyboard = 0f), 0f, "-0.4 must not become -1.0")
        assertEquals(0.05f, PadAxis.resolve(0.05f, false, false, keyboard = 0f), 0f, "a small live reading is still its own value")
        assertEquals(1f, PadAxis.resolve(1f, false, false, keyboard = 0f), 0f, "full deflection is unchanged too")
    }

    @Test
    fun `the d-pad wins over the keyboard`()
    {
        assertEquals(
            -1f, PadAxis.resolve(0f, negativePressed = true, positivePressed = false, keyboard = 1f), 0f,
            "a pressed d-pad direction must beat an opposing key"
        )
        assertEquals(
            1f, PadAxis.resolve(0f, negativePressed = false, positivePressed = true, keyboard = -1f), 0f,
            "a pressed d-pad direction must beat an opposing key in the other direction too"
        )
    }

    @Test
    fun `a d-pad direction resolves to full deflection`()
    {
        assertEquals(-1f, PadAxis.resolve(0f, negativePressed = true, positivePressed = false, keyboard = 0f), 0f)
        assertEquals(1f, PadAxis.resolve(0f, negativePressed = false, positivePressed = true, keyboard = 0f), 0f)
    }

    @Test
    fun `both d-pad directions at once cancel to zero rather than one winning by ordering`()
    {
        // A hat cannot report both; a stuck contact on a booth encoder can. Neither -1 nor +1
        // is an acceptable answer here — a diver swimming sideways forever is precisely the
        // unattended-cabinet failure this resolves against.
        assertEquals(
            0f, PadAxis.resolve(0f, negativePressed = true, positivePressed = true, keyboard = 0f), 0f,
            "a stuck contact plus a live press must go dead, not pick a side"
        )
    }

    @Test
    fun `both d-pad directions at once do not fall through to the keyboard either`()
    {
        // The tie is a DEAD axis, not an absent one: falling through would let a key drive the
        // diver while the player is holding a jammed stick, which is worse than not moving.
        assertEquals(
            0f, PadAxis.resolve(0f, negativePressed = true, positivePressed = true, keyboard = 1f), 0f,
            "the both-pressed tie must resolve to 0, not defer to the keyboard"
        )
    }

    @Test
    fun `with no pad input at all the keyboard axis is returned unchanged`()
    {
        assertEquals(-1f, PadAxis.resolve(0f, false, false, keyboard = -1f), 0f)
        assertEquals(1f, PadAxis.resolve(0f, false, false, keyboard = 1f), 0f)
        assertEquals(0f, PadAxis.resolve(0f, false, false, keyboard = 0f), 0f)
    }

    @Test
    fun `a resting analog stick does not shadow a live d-pad`()
    {
        // The regression this whole file exists for: a hat-only encoder reports 0.0 on
        // LEFT_X/LEFT_Y forever. If a zero analog reading were treated as "the stick is the
        // live source", the d-pad would be unreachable and the cabinet would be unplayable
        // with nothing in the log.
        assertEquals(
            -1f, PadAxis.resolve(0f, negativePressed = true, positivePressed = false, keyboard = 0f), 0f,
            "an analog reading of exactly 0 means DEAD, not live-at-zero"
        )
        assertEquals(
            1f, PadAxis.resolve(0f, negativePressed = false, positivePressed = true, keyboard = 0f), 0f,
            "same in the positive direction"
        )
    }

    @Test
    fun `a negative-zero analog reading is treated as dead, not live`()
    {
        // `-0f != 0f` is false in Kotlin for Float comparison via `==`... but `equals` on the
        // boxed type disagrees, and a deadzone that returns `-0f` is easy to write. Pinning
        // this keeps a future rewrite from reaching for `analog.equals(0f)` or `compareTo`,
        // either of which would make -0f "live" and re-open the dead-d-pad bug.
        assertEquals(
            -1f, PadAxis.resolve(-0f, negativePressed = true, positivePressed = false, keyboard = 0f), 0f,
            "-0f must be dead, so the d-pad is still reachable behind it"
        )
    }
}
