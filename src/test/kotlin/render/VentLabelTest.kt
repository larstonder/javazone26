package render

import DefaultFont
import ScreenText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vents' "O2" label, asserted the way every other layout object here is: not "the right
 * pixels came out" — that needs a GL context and a photograph — but the RELATIONSHIPS that
 * would have to hold for the right pixels to be possible.
 *
 * Four things can go wrong with this label, and each of them is silent:
 *
 *  1. **The string leaves the font atlas.** "O₂" is the typographically correct form and it is
 *     the obvious tidy-up. U+2082 is far outside the default font's baked U+0020..U+011F range,
 *     and a code point outside it draws as NOTHING — no glyph, no x-advance, no log line. The
 *     vent would read "O". `AttractScreenTest` sweeps `ScreenText.all()` for exactly this, so
 *     the guard below is that the label is actually IN that list: dropping it from `all()` is
 *     how the sweep would silently stop covering it.
 *  2. **The alpha is authored raw.** The HUD surface stores alpha SQUARED (measured — see
 *     [Hud.tapeBg]), so a label authored at the 0.38 it wants to display at would display at
 *     0.14 and read as nothing. This is the exact failure that made the depth tape invisible in
 *     every gameplay screenshot until somebody measured it.
 *  3. **The label grows off its own blob.** It is sized from the vent rather than from the
 *     screen precisely so it stays proportional at any aspect ratio; a font fraction or a centre
 *     offset that is too large puts "O2" outside the plume it is labelling and turns a marking
 *     into a floating caption. Checked at the real booth-panel aspects, through
 *     [CameraRig.pixelsPerMetre], because the width-bound regime above 1.6419 changes the
 *     pixels-per-metre and is where a screen-derived size would have drifted.
 *  4. **A spent vent stops being findable.** `DiveRenderer.drawAirPockets` dims a used vent
 *     rather than hiding it, on purpose — "knowing where a used vent was is what lets a player
 *     plan the next dive around it". A label that dropped to zero, or that did not dim at all,
 *     would contradict the draw colour immediately beside it.
 *
 * WHAT IS DELIBERATELY NOT ASSERTED: whether 0.38 is the right amount of faint, and whether the
 * upper third is the right place. Those are the owner's calls, made in [VentLabel]'s doc, and a
 * test that pinned them to their current values could only ever fail when somebody deliberately
 * retuned them. The bounds below are bounds — centring the label ([VentLabel
 * .CENTRE_OFFSET_FRACTION] = 0) stays green, and so does making it fainter.
 */
class VentLabelTest
{
    /**
     * A generous UPPER bound on one glyph's advance in the default font, in ems.
     *
     * [Hud]'s clock box uses 0.62 for a DIGIT and documents it as an upper bound that can only
     * be confirmed by looking at the screen. "O" is a letter and a round one, so this allows a
     * further 20% on top. Erring high is the safe direction for a FIT test: it can only make the
     * assertion below stricter than reality, never looser.
     */
    private val emPerGlyph = 0.75f

    /** The vent, at the size `DiveRenderer.drawAirPockets` actually draws it. */
    private val ventHeight = Framing.AIR_POCKET_SIZE_METRES
    private val ventWidth = OxygenSprite.widthForHeight(ventHeight)

    /**
     * Every panel shape the cabinet could plausibly meet, as physical framebuffer pixels.
     * 1.6419 is the design aspect where [CameraRig]'s height fit and width fit are equal, so
     * this straddles both regimes — see `CameraRig`'s class doc.
     */
    private val panels = listOf(
        1200f to 900f,      // 4:3, dev window
        1920f to 1200f,     // 16:10
        1920f to 1080f,     // 16:9, the likely booth panel
        3840f to 2160f,     // 4K 16:9
        2560f to 1080f,     // 2.389 ultrawide
        3840f to 1080f      // 32:9, the extreme
    )

    // --- 1. The string ---------------------------------------------------------------------

    @Test
    fun `the vent label is plain ASCII and inside the default font's baked atlas`()
    {
        val missing = DefaultFont.undrawableCodePointsIn(ScreenText.VENT_OXYGEN)
        assertTrue(
            missing.isEmpty(),
            "\"${ScreenText.VENT_OXYGEN}\" contains ${missing.map { "U+%04X".format(it) }}, which " +
            "the default font cannot draw — it renders as nothing at all, silently, so the vent " +
            "would be labelled with a bare \"O\" or with nothing. U+2082 SUBSCRIPT TWO is the " +
            "trap here: \"O₂\" is the correct typography and is unshippable. See DefaultFont."
        )
    }

    @Test
    fun `the vent label is in ScreenText all, which is what keeps the atlas sweep covering it`()
    {
        assertTrue(
            ScreenText.all().contains(ScreenText.VENT_OXYGEN),
            "ScreenText.VENT_OXYGEN is drawn every frame a vent is on screen but is missing from " +
            "ScreenText.all(), so AttractScreenTest's font-atlas sweep no longer sees it. The " +
            "sweep is the only thing standing between a subscript character and a booth."
        )
    }

    // --- 2. The alpha the HUD surface actually stores -----------------------------------------

    @Test
    fun `the label's authored alpha squares back to the opacity it means to display at`()
    {
        // THE POINT OF THIS TEST: it fails if anyone writes LIVE_DISPLAYED_ALPHA straight into
        // the Color. The surface squares alpha on write, so that mistake ships a label at 14%
        // when 38% was asked for — visible only as "the O2 doesn't seem to be there".
        val cases = listOf(
            "liveInk" to (VentLabel.liveInk to VentLabel.LIVE_DISPLAYED_ALPHA),
            "liveShadow" to (VentLabel.liveShadow to VentLabel.LIVE_DISPLAYED_ALPHA),
            "spentInk" to (VentLabel.spentInk to VentLabel.SPENT_DISPLAYED_ALPHA),
            "spentShadow" to (VentLabel.spentShadow to VentLabel.SPENT_DISPLAYED_ALPHA)
        )

        for ((name, case) in cases)
        {
            val (color, displayed) = case
            assertEquals(
                displayed.toDouble(), (color.alpha * color.alpha).toDouble(), 1e-5,
                "$name is authored at ${color.alpha}, which the HUD surface stores squared and " +
                "therefore displays at ${color.alpha * color.alpha} — not the $displayed it is " +
                "meant to. Run the displayed value through Hud.authoredAlphaFor. See Hud.tapeBg " +
                "for the capture this is derived from."
            )
        }
    }

    // --- 3. The label stays on the blob it labels ---------------------------------------------

    @Test
    fun `the label's box stays inside the vent's drawn rect on every booth panel`()
    {
        for ((w, h) in panels)
        {
            val ppm = CameraRig.pixelsPerMetre(w, h)
            val fontSize = VentLabel.fontSizeFor(ventHeight, ppm)
            val offset = VentLabel.centreOffsetPixels(ventHeight, ppm)

            // The label is drawn centred in both axes (xOrigin = yOrigin = 0.5), so its box is
            // half its width either side of the anchor and half its height above and below.
            val halfTextWidth = ScreenText.VENT_OXYGEN.length * emPerGlyph * fontSize * 0.5f
            val halfTextHeight = fontSize * 0.5f

            val halfVentWidth = ventWidth * ppm * 0.5f
            val halfVentHeight = ventHeight * ppm * 0.5f

            assertTrue(
                halfTextWidth <= halfVentWidth,
                "at ${w.toInt()}x${h.toInt()} the label is ${2 * halfTextWidth} px wide against a " +
                "vent only ${2 * halfVentWidth} px wide — \"O2\" hangs off the sides of the plume " +
                "it is written on. The vent's cell is TALLER than it is wide (0.843), so sizing " +
                "the font off its HEIGHT is what makes this the binding axis."
            )

            assertTrue(
                abs(offset) + halfTextHeight <= halfVentHeight,
                "at ${w.toInt()}x${h.toInt()} the label's box reaches ${abs(offset) + halfTextHeight} px " +
                "from the vent's centre against a half-height of $halfVentHeight px — it is no " +
                "longer overlaid on the blob but floating clear of it, which is the reading of " +
                "\"on top of the bubble\" VentLabel's doc rejects."
            )
        }
    }

    @Test
    fun `the font size is proportional to the vent and to the camera scale, not a pixel count`()
    {
        // Doubling either input doubles the size. This is what makes the label hold its
        // proportion to the blob through a camera ease and across the two CameraRig regimes;
        // a size expressed as a fraction of screen height (or worse, a constant) would not.
        val base = VentLabel.fontSizeFor(ventHeight, 20f)

        assertEquals(
            (2 * base).toDouble(), VentLabel.fontSizeFor(ventHeight * 2f, 20f).toDouble(), 1e-4,
            "a vent drawn twice as tall must carry twice the label"
        )
        assertEquals(
            (2 * base).toDouble(), VentLabel.fontSizeFor(ventHeight, 40f).toDouble(), 1e-4,
            "twice the pixels per metre must give twice the label"
        )
    }

    @Test
    fun `the centre offset scales with the vent exactly as the font size does`()
    {
        // Both are the same fraction-of-vent-height construction, so a change to one that is
        // not matched by the other is a change of KIND — e.g. an offset re-expressed in screen
        // pixels — and would break the fit test above at only some aspect ratios. Asserting the
        // ratio directly says so at every aspect at once.
        for ((w, h) in panels)
        {
            val ppm = CameraRig.pixelsPerMetre(w, h)
            val ratio = VentLabel.centreOffsetPixels(ventHeight, ppm) / VentLabel.fontSizeFor(ventHeight, ppm)
            assertEquals(
                (VentLabel.CENTRE_OFFSET_FRACTION / VentLabel.FONT_FRACTION_OF_VENT_HEIGHT).toDouble(),
                ratio.toDouble(), 1e-4,
                "the offset and the font size stopped being the same fraction-of-the-vent " +
                "construction at ${w.toInt()}x${h.toInt()}"
            )
        }
    }

    @Test
    fun `a degenerate camera scale draws nothing rather than a negative or infinite font`()
    {
        // Frame one before initFrame has run against a written camera, and the frame a resize is
        // interpolated through, can both hand this a non-positive pixels-per-metre. render()
        // returns early on a zero font size; the alternative is asking the engine to rasterise
        // glyphs at a negative or NaN size, which is undefined rather than merely ugly.
        for (bad in listOf(0f, -1f, Float.NaN))
        {
            assertEquals(
                0f, VentLabel.fontSizeFor(ventHeight, bad),
                "fontSizeFor(ppm = $bad) must be 0 so render() can skip the draw"
            )
        }
    }

    // --- 4. A spent vent keeps a dimmer label -------------------------------------------------

    @Test
    fun `a spent vent's label is dimmer than a live one's but never invisible`()
    {
        assertTrue(
            VentLabel.SPENT_DISPLAYED_ALPHA < VentLabel.LIVE_DISPLAYED_ALPHA,
            "a spent vent's label (${VentLabel.SPENT_DISPLAYED_ALPHA}) is not dimmer than a live " +
            "one's (${VentLabel.LIVE_DISPLAYED_ALPHA}) — the label would contradict the draw " +
            "colour beside it, which DiveRenderer.drawAirPockets already dims on use."
        )

        assertTrue(
            VentLabel.SPENT_DISPLAYED_ALPHA > 0f,
            "a spent vent's label vanished. DiveRenderer.drawAirPockets dims a used vent " +
            "\"rather than hidden once spent — knowing where a used vent was is what lets a " +
            "player plan the next dive around it\", and the label is part of knowing where."
        )
    }

    // --- The wiring: the label appears exactly where the numeric HUD does ---------------------

    /**
     * A SOURCE SCAN, in the style of `OxygenSpriteWiringTest`, and for the same reason: `EnPustTil`
     * needs a GL context to instantiate, and what is being asserted is a wiring fact about a file.
     *
     * `Hud.render` is issued from FOUR arms of `onRender`'s `when` — PLAYING, the run half of
     * PAUSED, RUN_OVER and ENTER_INITIALS — and [VentLabel.render] is issued beside each of them
     * because the label is part of the same in-run readout. Four call sites is four chances to
     * drop one, and the arm most likely to be forgotten is also the one nobody looks at twice:
     * miss RUN_OVER and the labels simply blink out at the end of every run, which reads as an
     * intentional fade rather than as a defect.
     *
     * Counting rather than locating, deliberately. Where in each arm the call sits does not
     * matter (the HUD surface has no depth ordering within a frame beyond draw order, and both
     * are text over the same water); that there is one per arm does.
     */
    @Test
    fun `every state that draws the numeric HUD also draws the vent labels`()
    {
        val source = java.io.File("src/main/kotlin/EnPustTil.kt").readText()
        val hudCalls = Regex("Hud\\.render\\(hud,").findAll(source).count()
        val labelCalls = Regex("VentLabel\\.render\\(hud,").findAll(source).count()

        assertTrue(hudCalls > 0, "no `Hud.render(hud,` in EnPustTil.kt any more — re-read this test")
        assertEquals(
            hudCalls, labelCalls,
            "EnPustTil.onRender calls Hud.render $hudCalls times but VentLabel.render $labelCalls " +
            "times. The vents' O2 labels belong to the in-run readout and must be drawn in every " +
            "state that draws it — see VentLabel's class doc for why IDLE and BRIEFING are the " +
            "only states deliberately without them, and why that is expressed by them not calling " +
            "Hud.render either."
        )
    }

    @Test
    fun `the live label is subordinate to every numeric HUD element it shares the surface with`()
    {
        // "Faint" is the whole request. The depth tape's own body is the quietest thing on the
        // HUD at 0.55 displayed; a vent label at or above that would be a second readout
        // competing with the instruments rather than a marking on an object in the water.
        assertTrue(
            VentLabel.LIVE_DISPLAYED_ALPHA < Hud.tapeBg.alpha * Hud.tapeBg.alpha,
            "the vent label displays at ${VentLabel.LIVE_DISPLAYED_ALPHA}, which is not below the " +
            "depth tape's ${Hud.tapeBg.alpha * Hud.tapeBg.alpha} — the tape is the faintest " +
            "instrument on this surface and the label is meant to sit under it."
        )
    }
}
