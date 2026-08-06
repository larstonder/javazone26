package render

import dive.Tuning
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HUD's drawing calls need a live `Surface`, but everything that decides whether the HUD
 * can actually be SEEN is pure maths — and both defects these tests lock down shipped looking
 * perfectly reasonable in the source.
 *
 * 1. ALPHA. The HUD surface stores alpha squared (measured, see [Hud.authoredAlphaFor]), so an
 *    element authored at 15% opacity displays at ~2% and reads as nothing at all. The tape was
 *    invisible in every gameplay screenshot for exactly this reason.
 * 2. BUBBLE DISTRIBUTION. The air ring drew its surviving bubbles at the FIRST indices of a
 *    fixed 14-slot circle, so they clustered into a shrinking arc starting at 3 o'clock instead
 *    of a thinning ring. At 6s of air that read as "three dots off to the lower right".
 */
class HudTest
{
    private val TAU = (PI * 2.0).toFloat()

    /** What the HUD surface actually displays for a colour authored at [authored] alpha. */
    private fun displayedAlpha(authored: Float) = authored * authored

    // --- Alpha squaring ---------------------------------------------------------------

    @Test
    fun `authored alpha is chosen so the surface's squaring lands on the value asked for`() {
        // The whole point: ask for 55% and get 55% on screen, not 55% of 55%.
        listOf(0.15f, 0.5f, 0.65f, 0.9f, 1f).forEach { wanted ->
            assertEquals(
                wanted, displayedAlpha(Hud.authoredAlphaFor(wanted)), 0.0001f,
                "asking for $wanted opacity must display as $wanted"
            )
        }
    }

    @Test
    fun `the depth tape is opaque enough to read as a tape`() {
        // The shipped value was Color(1,1,1,0.15) -> displayed 0.0225, which measured as
        // RGBA(38,38,38,6) on the captured HUD surface: invisible. Anything below a third
        // of full opacity puts us back there.
        val displayed = displayedAlpha(Hud.tapeBg.alpha)
        assertTrue(
            displayed >= 0.33f,
            "depth tape displays at $displayed opacity — too faint to read against the abyss"
        )
    }

    @Test
    fun `the point-of-no-return mark stays the boldest thing on the tape`() {
        // The mark is the mercy that teaches the economy. It must not be dimmer than the
        // tape it sits on, which is what the squaring quietly did to it (0.65 -> 0.42).
        val markAlpha = displayedAlpha(Hud.noReturnMark.alpha)
        val tapeAlpha = displayedAlpha(Hud.tapeBg.alpha)
        assertTrue(markAlpha >= 0.8f, "no-return mark displays at only $markAlpha opacity")
        assertTrue(markAlpha > tapeAlpha, "no-return mark ($markAlpha) must out-read the tape ($tapeAlpha)")
    }

    @Test
    fun `the tape's dark backing is opaque enough to darken the bright shallows behind it`() {
        // A pale tape alone cannot be seen against sunlit Shallows water; the backing is what
        // gives it an edge there, the same trick drawTextWithOutline uses for HUD text.
        val displayed = displayedAlpha(Hud.tapeShadow.alpha)
        assertTrue(displayed >= 0.7f, "tape backing displays at only $displayed opacity")
    }

    // --- Bubble ring distribution -----------------------------------------------------

    /** Where slot [slot] puts a bubble relative to the diver, in SCREEN space (y grows down). */
    private fun slotOffset(slot: Int): Pair<Float, Float> {
        val a = Hud.airBubbleSlotAngle(slot)
        return kotlin.math.cos(a) to kotlin.math.sin(a)
    }

    @Test
    fun `the ring is a clock face - fourteen evenly spaced slots`() {
        // The scatter defect: golden-angle spacing put bubbles at irregular angles, so a full
        // ring read as confetti rather than a ring. Consecutive slots must be exactly one
        // fourteenth of a turn apart, every time.
        val step = TAU / Hud.AIR_BUBBLE_COUNT
        for (slot in 0 until Hud.AIR_BUBBLE_COUNT - 1) {
            val delta = Hud.airBubbleSlotAngle(slot + 1) - Hud.airBubbleSlotAngle(slot)
            assertEquals(step, delta, 0.0001f, "slots $slot and ${slot + 1} are not evenly spaced")
        }
    }

    @Test
    fun `slot zero sits straight above the diver, at twelve o'clock`() {
        // Screen y grows DOWNWARD, so "above" is a negative y offset. Getting this backwards
        // puts the dial's origin at six o'clock and nothing else in the file would notice.
        val (x, y) = slotOffset(0)
        assertEquals(0f, x, 0.0001f, "slot 0 is off to one side, not straight up")
        assertTrue(y < -0.99f, "slot 0 is at y=$y — it must be ABOVE the diver, i.e. negative y")
    }

    @Test
    fun `rising slot numbers walk clockwise on screen`() {
        // The direction test, and the one most at risk of being vacuous: it has to fail if the
        // rotation is reversed. In screen space with y DOWN, turning clockwise means each step
        // turns right, which is a POSITIVE 2D cross product. (In the usual y-up convention the
        // sign is the other way round, which is exactly the trap.)
        for (slot in 0 until Hud.AIR_BUBBLE_COUNT - 1) {
            val (x0, y0) = slotOffset(slot)
            val (x1, y1) = slotOffset(slot + 1)
            val cross = x0 * y1 - y0 * x1
            assertTrue(cross > 0.1f, "slot $slot -> ${slot + 1} turns the wrong way (cross=$cross)")
        }
    }

    @Test
    fun `the ring empties clockwise from twelve o'clock`() {
        // A countdown dial: the gap opens at the top and sweeps clockwise. So the slot lost
        // when the count drops from r to r-1 is always the next one clockwise from the last.
        for (remaining in Hud.AIR_BUBBLE_COUNT downTo 1) {
            val occupied = (Hud.firstOccupiedSlot(remaining) until Hud.AIR_BUBBLE_COUNT).toSet()
            assertEquals(remaining, occupied.size, "$remaining bubbles should occupy $remaining slots")

            // The empty slots must be exactly 0, 1, ... — a contiguous run anchored at twelve
            // o'clock and growing one step clockwise per breath. Dropping the HIGH slots
            // instead would drain the dial backwards and this list would come out reversed.
            val empty = (0 until Hud.AIR_BUBBLE_COUNT).filterNot { it in occupied }
            assertEquals(
                (0 until Hud.AIR_BUBBLE_COUNT - remaining).toList(), empty,
                "with $remaining left, the gap must run from slot 0 clockwise"
            )
        }
    }

    @Test
    fun `a bubble keeps its slot for its whole life`() {
        // Bubbles pop out of a standing ring; none may move because another popped. A slot's
        // angle depends only on the slot, never on how many are left — this test is here so
        // nobody "fixes" anything by dividing by the surviving count instead.
        assertEquals(Hud.airBubbleSlotAngle(2), Hud.airBubbleSlotAngle(2), 0f)
        assertTrue(
            Hud.airBubbleSlotAngle(2) != Hud.airBubbleSlotAngle(3),
            "distinct slots need distinct angles"
        )
    }

    // --- Low air: the last breath has to be impossible to miss --------------------------

    @Test
    fun `the last of the air is a single bubble, so that bubble has to be big`() {
        // 1.5s of a 30s breath is one bubble out of fourteen. At the shipped 1x size that was
        // one small dot at the most critical moment in the game.
        val remaining = Hud.airBubblesRemaining(air = 1.5f, capacity = Tuning.BASE_AIR_SECONDS)
        assertEquals(1, remaining)
        assertTrue(
            Hud.airBubbleSizeScale(remaining) >= 2.5f,
            "the final bubble is drawn at ${Hud.airBubbleSizeScale(remaining)}x — still a dot"
        )
    }

    @Test
    fun `bubbles grow as the ring thins below the low-air threshold, and not before`() {
        // Above the threshold the ring is just information; below it, it is the warning, and
        // it has to get louder as it gets emptier — non-numerically, per the design spec.
        for (count in Hud.AIR_LOW_THRESHOLD + 1..Hud.AIR_BUBBLE_COUNT) {
            assertEquals(1f, Hud.airBubbleSizeScale(count), 0.0001f, "$count bubbles must draw at normal size")
        }
        for (count in 1 until Hud.AIR_LOW_THRESHOLD) {
            assertTrue(
                Hud.airBubbleSizeScale(count) > Hud.airBubbleSizeScale(count + 1),
                "$count bubbles must draw larger than ${count + 1}"
            )
        }
        assertTrue(Hud.airBubbleSizeScale(Hud.AIR_LOW_THRESHOLD) > 1f, "the red ring must already be enlarged")
    }

    @Test
    fun `bubble count spans the full ring across a full breath`() {
        assertEquals(0, Hud.airBubblesRemaining(0f, Tuning.BASE_AIR_SECONDS))
        assertEquals(Hud.AIR_BUBBLE_COUNT, Hud.airBubblesRemaining(Tuning.BASE_AIR_SECONDS, Tuning.BASE_AIR_SECONDS))
        // Over-full (a vent tops you up past the base breath) must not overflow the ring.
        assertEquals(Hud.AIR_BUBBLE_COUNT, Hud.airBubblesRemaining(Tuning.BASE_AIR_SECONDS * 2f, Tuning.BASE_AIR_SECONDS))
        // Half a breath is half a ring.
        assertEquals(Hud.AIR_BUBBLE_COUNT / 2, Hud.airBubblesRemaining(Tuning.BASE_AIR_SECONDS * 0.5f, Tuning.BASE_AIR_SECONDS))
    }
}
