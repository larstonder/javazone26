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

    /** Largest empty arc, as a fraction of the circle, for the first [count] bubbles. */
    private fun largestGapFraction(count: Int): Float {
        val angles = (0 until count).map { AimAngle.wrap(Math.toDegrees(Hud.airBubbleAngle(it).toDouble()).toFloat()) }
            .sorted()
        var largest = 0f
        for (i in angles.indices) {
            val next = if (i == angles.lastIndex) angles[0] + 360f else angles[i + 1]
            largest = maxOf(largest, next - angles[i])
        }
        return largest / 360f
    }

    @Test
    fun `bubbles stay spread around the whole circle as the ring thins`() {
        // The defect: with angle = index / AIR_BUBBLE_COUNT, three surviving bubbles occupy a
        // 51-degree wedge and leave 86% of the circle empty — an arc, not a ring.
        for (count in 3..Hud.AIR_BUBBLE_COUNT) {
            val gap = largestGapFraction(count)
            assertTrue(
                gap <= 0.4f,
                "$count bubbles leave ${(gap * 100).toInt()}% of the circle empty — that reads as an arc"
            )
        }
    }

    @Test
    fun `two bubbles sit on opposite sides rather than next to each other`() {
        // Two is the last count where "ring" still means anything; they should straddle the
        // diver, not huddle. Neither gap may be smaller than a quarter of the circle.
        val gap = largestGapFraction(2)
        assertTrue(gap <= 0.75f, "two bubbles left a ${(gap * 100).toInt()}% gap — they are huddled together")
    }

    @Test
    fun `a full ring spreads its bubbles without stacking two in the same place`() {
        // Cheap sanity check on the angle sequence itself: 14 bubbles, 14 distinct positions.
        val positions = (0 until Hud.AIR_BUBBLE_COUNT)
            .map { AimAngle.wrap(Math.toDegrees(Hud.airBubbleAngle(it).toDouble()).toFloat()) }
        positions.forEach { a ->
            assertTrue(positions.count { kotlin.math.abs(it - a) < 5f } == 1, "two bubbles overlap near $a degrees")
        }
    }

    @Test
    fun `a bubble keeps the same angle for its whole life`() {
        // Bubbles must pop out of a standing ring, not re-shuffle every time one goes. The
        // angle depends only on the bubble's index, never on how many are left — this test
        // is here so nobody "fixes" the spread by dividing by the surviving count instead.
        val angleOfThirdBubble = Hud.airBubbleAngle(2)
        assertEquals(angleOfThirdBubble, Hud.airBubbleAngle(2), 0f)
        assertTrue(Hud.airBubbleAngle(2) != Hud.airBubbleAngle(3), "distinct bubbles need distinct angles")
        // And the sequence is a prefix-stable one: index 0 is where it always was, 3 o'clock.
        assertEquals(0f, Hud.airBubbleAngle(0), 0.0001f)
        assertTrue(Hud.airBubbleAngle(1) in 0f..TAU, "angles stay inside one turn")
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
