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

    // --- BANKED: a pearl icon and a number, reading as one row --------------------------
    //
    // Everything below is layout: where things land relative to each other. What the HUD LOOKS
    // like can only be settled by looking at it, but "the icon does not sit on top of the text"
    // and "the scale reaches the bottom of the column" are arithmetic, and arithmetic is
    // assertable without a GL context — the same split `Framing` and `AttractLayout` are built
    // on. A physical framebuffer height in the booth's range; nothing here may depend on it.
    private val H = 1800f

    /** The margin every screen-anchored element hangs from, read off the one element that is
     *  DEFINED as sitting on it: the pearl icon's left rim (see `Hud.bankedIconCentreX`). */
    private fun margin() = Hud.bankedIconCentreX(H) - Hud.bankedIconDiameter(H) * 0.5f

    @Test
    fun `the pearl icon and BANKED read as one row, with the text clear of the icon's rim`() {
        // The failure this catches is the one its doc names: re-anchoring either element to the
        // margin on its own. Anchor the text at the margin and it starts INSIDE the icon (a
        // negative gap); drop the icon's radius from the text's offset and it touches the rim.
        val diameter = Hud.bankedIconDiameter(H)
        val gap = Hud.bankedTextX(H) - (Hud.bankedIconCentreX(H) + diameter * 0.5f)

        assertTrue(gap > 0f, "BANKED starts $gap px from the icon's rim — it overlaps the icon")
        assertTrue(
            gap < diameter,
            "a $gap px gap on a $diameter px icon is more than an icon's width of air — " +
                "the pair reads as two elements, not one row"
        )
    }

    @Test
    fun `the pearl icon is a bullet beside BANKED, not a second element competing with it`() {
        // Its doc: "a touch smaller than the text's own font size so it reads as a bullet
        // belonging to the row". Bigger than the text and it becomes the loud thing on a screen
        // whose loud thing is HELD; much smaller and it is a speck. It is also what keeps the
        // row's top edge the TEXT's — an icon taller than the font would breach the margin,
        // since both are centred on the same line.
        val diameter = Hud.bankedIconDiameter(H)
        val fontSize = Hud.bankedFontSize(H)
        assertTrue(diameter < fontSize, "the icon ($diameter) is larger than the text it labels ($fontSize)")
        assertTrue(diameter > fontSize * 0.5f, "the icon ($diameter) is a speck beside $fontSize px text")
    }

    @Test
    fun `the BANKED row and the clock box hang from the same top margin`() {
        // Two independent expressions that must agree and mention neither each other nor the
        // constant they share: the row's centre is a margin plus half a font size, the box's is
        // a margin plus half a box height. If either stops meaning "top edge on the margin" —
        // the easy mistake being to add a full height instead of half — the HUD acquires a step
        // across the top of the screen that no test would otherwise see.
        val tolerance = H * 0.0001f
        val bankedTop = Hud.bankedRowCentreY(H) - Hud.bankedFontSize(H) * 0.5f
        val clockTop = Hud.clockBoxCentreY(H) - Hud.clockBoxHeight(Hud.clockFontSize(H)) * 0.5f

        assertEquals(margin(), bankedTop, tolerance, "the BANKED row does not start on the margin")
        assertEquals(margin(), clockTop, tolerance, "the clock box does not hang from the margin")
        assertTrue(margin() > 0f, "the margin is ${margin()} — the row is off the top of the screen")
    }

    // --- The clock's box ----------------------------------------------------------------

    @Test
    fun `the clock box is sized from the string, so 10 00 does not outgrow its border`() {
        // The box is drawn around a string whose length changes once per run, when the clock
        // crosses ten minutes. Sizing it from a fixed width would either clip the longer form
        // or look loose for the whole run, so the width has to be affine in the glyph count:
        // strictly increasing, and by the same amount per glyph.
        val fontSize = Hud.clockFontSize(H)
        val four = Hud.clockBoxWidth(fontSize, 4)   // "9:59"
        val five = Hud.clockBoxWidth(fontSize, 5)   // "10:00"
        val six = Hud.clockBoxWidth(fontSize, 6)

        assertTrue(five > four, "'10:00' ($five) gets no more box than '9:59' ($four)")
        assertEquals(five - four, six - five, fontSize * 0.0001f, "the box does not grow one glyph at a time")
        // And the box is more than the glyphs it frames: what is left after taking four glyph
        // advances out of the four-glyph box is the padding, and a border with no padding
        // inside it is a border with a digit sitting on it.
        val perGlyph = five - four
        assertTrue(four - 4f * perGlyph > 0f, "the box has no horizontal padding at all")
    }

    @Test
    fun `the clock box is taller than a full em, so no glyph can touch its border`() {
        // The padding is stated in ems ABOVE AND BELOW A FULL EM rather than above the cap
        // height, precisely because this file cannot know the font's cap height (Font.getQuad is
        // baked data). That is the guarantee: whatever the cap height turns out to be, the box
        // clears it. Padding measured off the cap height instead would put a descender on the
        // border on a font swap, and only a photograph would show it.
        val fontSize = Hud.clockFontSize(H)
        assertTrue(
            Hud.clockBoxHeight(fontSize) > fontSize,
            "the box (${Hud.clockBoxHeight(fontSize)}) is not taller than the em it frames ($fontSize)"
        )
    }

    // --- The rounded-rect band decomposition --------------------------------------------
    //
    // There is no circle and no rounded-rect primitive on this surface, so the clock's box and
    // the tape's slider handle are stacks of axis-aligned slabs whose widths follow the corner
    // arc. The arc is the part that can be wrong, and it is wrong in a way that is easy to miss
    // on a screenshot: a corner that is a little too square, or a hair clipped.

    @Test
    fun `a zero radius degenerates to a plain rectangle at every row`() {
        // What makes one function serve both the rounded shapes and a square-cornered one
        // without a second code path — and the case where a stray `- radius` term would show up
        // as a rectangle mysteriously narrower than it was asked for.
        for (dy in listOf(0f, 3f, 9.999f, 10f, 40f)) {
            assertEquals(
                20f, Hud.roundedBandHalfWidth(20f, 10f, 0f, dy), 0.0001f,
                "a zero-radius band at dy=$dy is not full width"
            )
        }
    }

    @Test
    fun `the corner really is a circular arc`() {
        // With every extent equal to the radius the shape IS a circle of that radius, so the
        // half-width at row dy must be exactly sqrt(r^2 - dy^2). This is the test that pins the
        // algebra: drop the `- radius` term, or measure the arc from the shape's centre instead
        // of from the arc's own centre, and the numbers stop being a circle at once.
        for (step in 0..10) {
            val dy = step / 10f
            assertEquals(
                kotlin.math.sqrt(1f - dy * dy), Hud.roundedBandHalfWidth(1f, 1f, 1f, dy), 0.0001f,
                "the arc is not a circle at dy=$dy"
            )
        }
    }

    @Test
    fun `the bands narrow monotonically toward the ends, and the end cap is the flat part`() {
        // A band that widened again past the straight section would be a corner bulging outside
        // the shape. And at the very end the half-width must be exactly `halfWidth - radius`:
        // that is the flat cap between the two corner arcs, and it is what makes the border of
        // the clock's box a constant thickness rather than pinching at 45 degrees.
        val halfWidth = 30f
        val halfHeight = 12f
        val radius = 5f
        var previous = Float.MAX_VALUE
        for (step in 0..60) {
            val dy = halfHeight * step / 60f
            val halfBand = Hud.roundedBandHalfWidth(halfWidth, halfHeight, radius, dy)
            assertTrue(halfBand <= previous + 0.0001f, "the band widens again at dy=$dy")
            assertTrue(halfBand <= halfWidth, "the band ($halfBand) is wider than the shape at dy=$dy")
            assertEquals(
                halfBand, Hud.roundedBandHalfWidth(halfWidth, halfHeight, radius, -dy), 0.0001f,
                "the shape is not symmetric about its centre at dy=$dy"
            )
            previous = halfBand
        }
        assertEquals(
            halfWidth - radius, Hud.roundedBandHalfWidth(halfWidth, halfHeight, radius, halfHeight), 0.0001f,
            "the end cap is not `halfWidth - radius` wide"
        )
    }

    @Test
    fun `a rounded corner is banded finely enough that the staircase does not show`() {
        // THE UNIT THAT MATTERS IS PIXELS OF RISER, not bands. Cut at equal angles, a band steps
        // at most `radius * bandAngle` sideways, and the arc spans a quarter turn — so this is
        // the whole claim the constant makes, in the only terms in which "smooth" means
        // anything. A fixed band count (the six this replaced) passes at the slider handle's
        // 12 px corner and fails at the clock box's, which is the trap: a fixed count is a
        // guarantee about the SHAPE, and what shows is a fraction of the SCREEN.
        //
        // The clamp inside the band function caps a radius at the shape's smaller half-extent,
        // so half the clock box's height is an upper bound on any corner this file can ask for
        // at this framebuffer height — and it needs no access to the private corner ratio.
        val largestCorner = Hud.clockBoxHeight(Hud.clockFontSize(H)) * 0.5f
        val quarterTurn = (PI * 0.5).toFloat()
        for (radius in listOf(4f, 12f, largestCorner * 0.5f, largestCorner)) {
            val riser = radius * quarterTurn / Hud.roundedCornerBands(radius)
            assertTrue(riser <= 2.5f, "a $radius px corner steps $riser px sideways — that reads as a chamfer")
        }
        // And it must not spend bands it cannot show: a corner a couple of pixels across is
        // already smooth, and the handle is drawn every frame of every run.
        assertTrue(Hud.roundedCornerBands(1f) <= 2, "a 1 px corner is being drawn as ${Hud.roundedCornerBands(1f)} bands")
    }

    @Test
    fun `a radius larger than the shape is clamped to a stadium rather than a NaN`() {
        // `sqrt` of a negative is silent: it returns NaN, a NaN width draws nothing at all, and
        // the shape simply vanishes with no error anywhere. The clamp is what turns a mis-tuned
        // constant into a stadium instead — so this asserts the clamp, not the absence of a
        // crash there never would have been.
        val overSized = Hud.roundedBandHalfWidth(20f, 10f, 500f, 6f)
        assertTrue(!overSized.isNaN(), "an over-large radius produced NaN — the shape would vanish")
        // Clamped to the smaller half-extent (10), the shape is a stadium: at dy = 6 its
        // half-width is 20 - 10 + sqrt(100 - 36) = 18.
        assertEquals(18f, overSized, 0.0001f, "the radius was not clamped to the smaller half-extent")
    }

    // --- The depth tape: scale, graduations and the slider handle ------------------------

    @Test
    fun `the tape maps the whole column onto its whole length, and clamps at both ends`() {
        val top = 200f
        val span = 1400f
        assertEquals(top, Hud.tapeY(0f, top, span), 0.0001f, "the surface is not at the top of the tape")
        assertEquals(top + span, Hud.tapeY(Tuning.MAX_DEPTH, top, span), 0.0001f, "the sea bed is not at the bottom")
        assertEquals(top + span * 0.5f, Hud.tapeY(Tuning.MAX_DEPTH * 0.5f, top, span), 0.0001f, "the scale is not linear")
        // Both clamps matter at the booth: the diver can be above the surface between runs, and
        // an unclamped tape would put the handle and its readout off the end of the instrument.
        assertEquals(top, Hud.tapeY(-40f, top, span), 0.0001f, "a depth above the surface runs off the top")
        assertEquals(top + span, Hud.tapeY(Tuning.MAX_DEPTH * 2f, top, span), 0.0001f, "an over-deep diver runs off the bottom")
    }

    @Test
    fun `the tape carries the graduations the mockup asks for`() {
        val depths = (0 until Hud.graduationCount()).map { Hud.graduationDepth(it) }
        for (wanted in listOf(0f, 25f, 50f, 75f, 100f)) {
            assertTrue(wanted in depths, "the tape has no ${wanted.toInt()} m graduation — it has $depths")
        }
    }

    @Test
    fun `the graduations reach the bottom of the column without running off the end`() {
        // Derived from Tuning.MAX_DEPTH rather than listed, so this asserts the derivation is
        // MAXIMAL in both directions: one more graduation would hang off the end of the tape,
        // and one fewer would leave the Abyss — precisely where a player needs to know how far
        // past the point of no return they are — unmarked.
        val last = Hud.graduationDepth(Hud.graduationCount() - 1)
        assertTrue(last <= Tuning.MAX_DEPTH, "the deepest graduation ($last m) is off the end of the tape")
        assertTrue(
            Hud.graduationDepth(Hud.graduationCount()) > Tuning.MAX_DEPTH,
            "there is room for another graduation below $last m"
        )
        assertTrue(
            last >= Tuning.MAX_DEPTH * 0.9f,
            "the scale stops at $last m of ${Tuning.MAX_DEPTH} — the deep end of the column is unmarked"
        )
    }

    @Test
    fun `every graduation label names its own depth and is inside the font's baked atlas`() {
        // The atlas range is restated rather than taken from `DefaultFont`, which lives in the
        // default package and so cannot be imported into this one; AttractScreenTest is where
        // that object's own behaviour is pinned. The rule it encodes: a code point above
        // U+011F renders as NOTHING AT ALL and consumes no width, silently. Digits and "m" are
        // far inside it — this is here so that a future "150 m" with a non-breaking space, or a
        // prime mark instead of an m, fails the build instead of vanishing at the booth.
        assertEquals(Hud.graduationCount(), Hud.graduationLabels.size, "a graduation has no label")
        Hud.graduationLabels.forEachIndexed { index, label ->
            assertEquals(
                "${Hud.graduationDepth(index).toInt()}m", label,
                "the label on graduation $index does not name its own depth"
            )
            label.codePoints().forEach { code ->
                assertTrue(code in 0x20..0x11F, "'$label' contains U+%04X, which draws as nothing".format(code))
            }
        }
    }

    @Test
    fun `a graduation label yields to the travelling readout only when it would collide`() {
        // Both are outlined strings right-aligned to the same column, so an overlap is two
        // black-rimmed strings on the same pixels — a smudge, at the exact moment the diver is
        // at a marked depth. The graduation is the one that yields, and it has to yield on BOTH
        // sides of the handle: dropping the abs() leaves half the tape smudging.
        val gap = 30f
        assertTrue(Hud.graduationLabelIsClearOfHandle(500f, 900f, gap), "a label 400 px away was hidden")
        assertTrue(!Hud.graduationLabelIsClearOfHandle(500f, 510f, gap), "a label 10 px below the readout was drawn")
        assertTrue(!Hud.graduationLabelIsClearOfHandle(500f, 490f, gap), "a label 10 px above the readout was drawn")
        assertTrue(Hud.graduationLabelIsClearOfHandle(500f, 530f, gap), "a label exactly one gap away was hidden")
    }

    @Test
    fun `the graduation labels clear the slider handle`() {
        // The handle straddles the tape and is the widest thing on that edge; the labels are
        // right-aligned to a column set from the TICK length and a gap, which mentions the
        // handle nowhere. So nothing in the source relates the two, and widening the handle
        // would silently park it on top of the labels it travels past.
        val tapeX = 1000f
        val tapeWidth = 12f
        val tapeCentreX = tapeX + tapeWidth * 0.5f
        val handleLeft = tapeCentreX - Hud.handleWidth(tapeWidth) * 0.5f

        assertTrue(
            Hud.tapeLabelRightX(tapeX, tapeWidth) <= handleLeft,
            "the label column (${Hud.tapeLabelRightX(tapeX, tapeWidth)}) runs under the handle's left edge ($handleLeft)"
        )
        // The ticks are the scale's own marks and are meant to be seen beside the handle, so
        // they must reach left of the tape at all — and the labels must clear THEM too.
        assertTrue(Hud.tapeTickLength(tapeWidth) > tapeWidth, "the ticks are shorter than the tape is wide")
        assertTrue(
            Hud.tapeLabelRightX(tapeX, tapeWidth) < tapeX - Hud.tapeTickLength(tapeWidth),
            "the labels overlap the ticks"
        )
    }

    @Test
    fun `the slider handle straddles the tape and is wider than it is tall`() {
        // A handle narrower than the tape would read as a notch cut out of it rather than as a
        // knob on it, and one taller than it is wide would read as a segment of the tape — both
        // are the "bare square marker" this replaced. The grip bars are what settle it, and
        // they only fit inside a lozenge of this shape.
        val tapeWidth = 12f
        assertTrue(Hud.handleWidth(tapeWidth) > tapeWidth * 2f, "the handle does not straddle the tape")
        assertTrue(
            Hud.handleWidth(tapeWidth) > Hud.handleHeight(tapeWidth),
            "the handle (${Hud.handleWidth(tapeWidth)} x ${Hud.handleHeight(tapeWidth)}) is not a lozenge"
        )
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

    // --- The in-run control legend --------------------------------------------------

    /** Same upper-bound em estimate the clock box sizes itself with. */
    private val LEGEND_EM = 0.62f

    @Test
    fun `the legend clears the clock box on the narrowest plausible panel`()
    {
        // THE one legend relationship that can actually fail, and therefore the one asserted
        // numerically. Everything else about the legend's corner is structural (see the two
        // tests below); this is arithmetic and it is tight.
        val h = 1000f
        val w = h * 4f / 3f                      // 4:3, the narrowest aspect tested anywhere here

        // The longest legend a rebind can produce: both action buttons on bumpers.
        val longest = "STICK swim  ·  RIGHT BUMPER kick  ·  LEFT BUMPER bleed"
        val legendWidth = longest.length * LEGEND_EM * h * Hud.LEGEND_FONT_FRACTION
        val legendLeft = Hud.legendRightX(w, h) - legendWidth

        // 5 glyphs ("10:00"), not 4 ("9:59") - the file's own test above documents that a run
        // crossing ten minutes widens the box, and the narrowest-panel check should assert the
        // WORST case the clock box can actually reach, not the easier one.
        val clockRight = w * 0.5f + Hud.clockBoxWidth(Hud.clockFontSize(h), 5) * 0.5f

        assertTrue(
            legendLeft > clockRight,
            "the legend starts at $legendLeft and the clock box ends at $clockRight - they overlap"
        )
    }

    @Test
    fun `the legend sits above the depth tape and inside the screen`()
    {
        val h = 1000f
        val w = h * 16f / 9f
        val bottom = Hud.legendBaselineY(h) + h * Hud.LEGEND_FONT_FRACTION

        assertTrue(Hud.legendBaselineY(h) > 0f, "the legend runs off the top")
        assertTrue(
            bottom < h * Hud.TAPE_TOP_FRACTION,
            "the legend collides with the depth tape, which starts at ${Hud.TAPE_TOP_FRACTION}h"
        )
        assertTrue(Hud.legendRightX(w, h) < w, "the legend runs off the right edge")
    }

    @Test
    fun `the legend can never collide with the HELD numerals`()
    {
        // This is the assertion that moved the legend out of the bottom-left corner, where an
        // earlier design put it. HELD hangs HELD_OFFSET_METRES below the diver, and the diver
        // is bounded ABOVE by DIVER_MIN_FRACTION - so the highest HELD can ever reach is
        // that fraction plus the offset, converted at the LARGEST visible depth (which gives
        // the smallest pixels-per-metre and therefore the smallest offset in screen terms).
        val h = 1000f
        val largestVisibleDepth = Framing.VISIBLE_DEPTH_METRES          // the loosest case
        val heldOffsetFraction = Hud.HELD_OFFSET_METRES / largestVisibleDepth
        val highestHeldTop = h * (Framing.DIVER_MIN_FRACTION + heldOffsetFraction)

        val legendBottom = Hud.legendBaselineY(h) + h * Hud.LEGEND_FONT_FRACTION
        assertTrue(
            legendBottom < highestHeldTop,
            "the legend ends at $legendBottom and HELD can reach up to $highestHeldTop"
        )
    }
}
