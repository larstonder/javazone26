package render

import AttractLayout
import BriefingLayout
import PauseLayout
import ScreenText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Geometry for the dark panels behind every lifecycle screen's text (owner request,
 * 2026-08-31 — see `.superpowers/sdd/2026-08-30-main-menu-and-graphics-options/panel-report.md`
 * for the full derivation and what is still UNVERIFIED).
 *
 * Modelled directly on `MenuLayoutTest`/`AttractScreenTest`/`PauseScreenTest`/
 * `BriefingScreenTest`: what is asserted is the RELATIONSHIP between a screen's content box and
 * the panel `PanelLayout.bounds` draws around it, not the constants themselves. The three risks
 * the task brief names explicitly are containment, staying on screen, and the attract screen's
 * halo clearance; a fourth is added here because it was found while deriving the geometry rather
 * than assumed away — the run-over screen's title-clearance case (see
 * `` `the run-over panel does not run into its own title` `` below).
 *
 * `run-over` and `initials entry` have no dedicated layout object in `EnPustTil.kt` (their
 * anchors are locals inside the two draw functions) — the constants restated here are copies of
 * those locals. That duplication is a real risk this test cannot remove, only narrow: if either
 * draw site's literals move, these tests silently stop describing the real screen. Flagged
 * rather than hidden.
 */
class PanelLayoutTest
{
    // An upper bound on a glyph's advance in the default font, as a multiple of font height —
    // the same estimate `Hud.CLOCK_BOX_GLYPH_WIDTH_EM` and `BriefingScreenTest` already use.
    // Erring high is correct here too, for the same reason: it biases a half-span estimate wide
    // rather than narrow, and a too-wide panel is a cosmetic looseness while a too-narrow one
    // clips text.
    private val EM = 0.62f

    private fun halfWidthOf(text: String, fontFraction: Float) = text.length * EM * fontFraction * 0.5f

    // --- Restated anchors for the two screens with no dedicated layout object ----------------
    // Mirrors EnPustTil.kt's drawRunOverScreen/drawInitialsEntryScreen locals exactly - see
    // this class's own doc for the duplication risk that comes with that.
    private object RunOverAnchors
    {
        const val TITLE_Y = 0.5f
        const val TITLE_FONT = 0.04f
        const val HINT_Y = 0.5f + 0.045f
        const val HINT_FONT = 0.022f
    }

    private object InitialsAnchors
    {
        const val TITLE_Y = 0.46f
        const val TITLE_FONT = 0.032f
        const val SLOTS_Y = 0.54f
        const val SLOTS_FONT = 0.06f
        const val HELP_Y = 0.6f
        const val HELP_FONT = 0.02f
    }

    private val HALO_BOTTOM = Framing.DIVER_SCREEN_FRACTION + AttractLayout.DIVER_HALO_HALF_HEIGHT

    // --- 1. Containment: the panel encloses its content box, with the declared padding --------
    // Deliberately NOT aspect-swept for the vertical relationships: every anchor PanelLayout.bounds
    // consumes is a fraction of screen HEIGHT (never width), so containment on the vertical axis
    // is aspect-invariant by construction - the same reasoning BriefingScreenTest's own class doc
    // gives for skipping a vertical aspect sweep. What DOES vary with aspect is whether the
    // panel's horizontal edges stay on screen, checked separately below per the task brief.

    @Test
    fun `the main menu panel contains the row list and the hint legend, not the title`()
    {
        val h = 1200f
        val contentTop = h * MenuLayout.ROWS_TOP_Y
        val contentBottom = h * (MenuLayout.HINT_Y + MenuLayout.HINT_FONT)
        val titleBottom = h * (MenuLayout.TITLE_Y + MenuLayout.TITLE_FONT)

        val panel = PanelLayout.bounds(
            contentLeft = -h * MenuLayout.HIGHLIGHT_HALF_SPAN,
            contentTop = contentTop,
            contentRight = h * MenuLayout.HIGHLIGHT_HALF_SPAN,
            contentBottom = contentBottom,
            screenHeight = h,
            minTop = titleBottom
        )

        val panelTop = panel.centreY - panel.height * 0.5f
        val panelBottom = panel.centreY + panel.height * 0.5f
        val panelLeft = panel.centreX - panel.width * 0.5f
        val panelRight = panel.centreX + panel.width * 0.5f

        assertTrue(panelTop <= contentTop, "panel top ${panelTop} does not contain content top $contentTop")
        assertTrue(panelBottom >= contentBottom, "panel bottom $panelBottom does not contain content bottom $contentBottom")
        assertTrue(panelLeft <= -h * MenuLayout.HIGHLIGHT_HALF_SPAN, "panel does not contain the row list's left edge")
        assertTrue(panelRight >= h * MenuLayout.HIGHLIGHT_HALF_SPAN, "panel does not contain the row list's right edge")
        assertTrue(panelTop >= titleBottom, "the menu panel overlaps its own title")
    }

    @Test
    fun `the leaderboard panel contains the heading and every row, for one row and for a full board`()
    {
        val h = 1200f
        for (rowCount in listOf(1, 4, AttractLayout.LEADERBOARD_SIZE))
        {
            val contentTop = h * AttractLayout.HEADING_Y
            val contentBottom = h * AttractLayout.bottomOfBoard(rowCount)

            val panel = PanelLayout.bounds(
                contentLeft = -h * AttractLayout.ROW_HALF_SPAN,
                contentTop = contentTop,
                contentRight = h * AttractLayout.ROW_HALF_SPAN,
                contentBottom = contentBottom,
                screenHeight = h,
                minTop = h * HALO_BOTTOM
            )
            val panelTop = panel.centreY - panel.height * 0.5f
            val panelBottom = panel.centreY + panel.height * 0.5f

            assertTrue(panelTop <= contentTop, "rowCount=$rowCount: panel top does not contain the heading")
            assertTrue(panelBottom >= contentBottom, "rowCount=$rowCount: panel bottom does not contain the last row")
        }
    }

    @Test
    fun `the pause panel contains the resume hint, the exit hint and the bar, not the title`()
    {
        val h = 1200f
        val contentTop = h * PauseLayout.RESUME_Y
        val contentBottom = h * (PauseLayout.BAR_Y + PauseLayout.BAR_HEIGHT)
        val titleBottom = h * (PauseLayout.TITLE_Y + PauseLayout.TITLE_FONT)

        val panel = PanelLayout.bounds(
            contentLeft = -h * PanelLayout.PAUSE_HALF_SPAN,
            contentTop = contentTop,
            contentRight = h * PanelLayout.PAUSE_HALF_SPAN,
            contentBottom = contentBottom,
            screenHeight = h,
            minTop = titleBottom
        )
        val panelTop = panel.centreY - panel.height * 0.5f
        val panelBottom = panel.centreY + panel.height * 0.5f

        assertTrue(panelTop <= contentTop, "panel top does not contain the resume hint")
        assertTrue(panelBottom >= contentBottom, "panel bottom does not contain the exit bar")
        assertTrue(panelTop >= titleBottom, "the pause panel overlaps its own title")
    }

    @Test
    fun `the briefing panel contains the rows and rule, and grows to cover the countdown and skip lines`()
    {
        val h = 1200f
        val titleBottom = h * (BriefingLayout.TITLE_Y + BriefingLayout.TITLE_FONT)
        val minTop = maxOf(titleBottom, h * HALO_BOTTOM)

        val cases = listOf(
            "rule only" to (BriefingLayout.RULE_Y + BriefingLayout.RULE_FONT),
            "with countdown" to (BriefingLayout.COUNTDOWN_Y + BriefingLayout.COUNTDOWN_FONT),
            "with skip" to (BriefingLayout.SKIP_Y + BriefingLayout.SKIP_FONT)
        )
        for ((label, bottomY) in cases)
        {
            val contentTop = h * BriefingLayout.ROWS_TOP_Y
            val contentBottom = h * bottomY

            val panel = PanelLayout.bounds(
                contentLeft = -h * PanelLayout.BRIEFING_HALF_SPAN,
                contentTop = contentTop,
                contentRight = h * PanelLayout.BRIEFING_HALF_SPAN,
                contentBottom = contentBottom,
                screenHeight = h,
                minTop = minTop
            )
            val panelTop = panel.centreY - panel.height * 0.5f
            val panelBottom = panel.centreY + panel.height * 0.5f

            assertTrue(panelTop <= contentTop, "$label: panel top does not contain the first row")
            assertTrue(panelBottom >= contentBottom, "$label: panel bottom does not contain its own content")
        }
    }

    @Test
    fun `the run-over panel contains only the retry hint, not the title`()
    {
        val h = 1200f
        val contentTop = h * RunOverAnchors.HINT_Y
        val contentBottom = h * (RunOverAnchors.HINT_Y + RunOverAnchors.HINT_FONT)
        val titleBottom = h * (RunOverAnchors.TITLE_Y + RunOverAnchors.TITLE_FONT)

        val panel = PanelLayout.bounds(
            contentLeft = -h * PanelLayout.RUN_OVER_HALF_SPAN,
            contentTop = contentTop,
            contentRight = h * PanelLayout.RUN_OVER_HALF_SPAN,
            contentBottom = contentBottom,
            screenHeight = h,
            minTop = titleBottom
        )
        val panelTop = panel.centreY - panel.height * 0.5f
        val panelBottom = panel.centreY + panel.height * 0.5f

        assertTrue(panelBottom >= contentBottom, "panel bottom does not contain the retry hint")
        assertTrue(panelTop >= titleBottom, "the run-over panel overlaps its own title")
    }

    @Test
    fun `the run-over panel does not run into its own title`()
    {
        // THE DEFECT NAIVE PADDING WOULD HAVE SHIPPED. The title's own text box
        // (RunOverAnchors.TITLE_Y .. +TITLE_FONT) ends at 0.54h; the retry hint starts at
        // 0.545h - a 0.005h gap far smaller than PanelLayout.PADDING_FRACTION (0.025h). Padding
        // the hint's top edge naively would put the panel's top at 0.52h, inside the title's own
        // box. This is exactly the fault the task brief says "only an eye catches" - caught here
        // by the numbers instead, because nobody could look at a real frame. See
        // panel-report.md's "found by arithmetic" section.
        val h = 1200f
        val titleBottom = h * (RunOverAnchors.TITLE_Y + RunOverAnchors.TITLE_FONT)

        val panel = PanelLayout.bounds(
            contentLeft = -h * PanelLayout.RUN_OVER_HALF_SPAN,
            contentTop = h * RunOverAnchors.HINT_Y,
            contentRight = h * PanelLayout.RUN_OVER_HALF_SPAN,
            contentBottom = h * (RunOverAnchors.HINT_Y + RunOverAnchors.HINT_FONT),
            screenHeight = h,
            minTop = titleBottom
        )
        val panelTop = panel.centreY - panel.height * 0.5f

        assertTrue(
            panelTop >= titleBottom - 1e-4f,
            "the run-over panel's top ($panelTop) rises above the title's own bottom edge " +
                "($titleBottom) - naive padding would draw the panel through the title text"
        )
    }

    @Test
    fun `the initials panel contains the slot line and the help line, not the title`()
    {
        val h = 1200f
        val contentTop = h * InitialsAnchors.SLOTS_Y
        val contentBottom = h * (InitialsAnchors.HELP_Y + InitialsAnchors.HELP_FONT)
        val titleBottom = h * (InitialsAnchors.TITLE_Y + InitialsAnchors.TITLE_FONT)

        val panel = PanelLayout.bounds(
            contentLeft = -h * PanelLayout.INITIALS_HALF_SPAN,
            contentTop = contentTop,
            contentRight = h * PanelLayout.INITIALS_HALF_SPAN,
            contentBottom = contentBottom,
            screenHeight = h,
            minTop = titleBottom
        )
        val panelTop = panel.centreY - panel.height * 0.5f
        val panelBottom = panel.centreY + panel.height * 0.5f

        assertTrue(panelTop <= contentTop, "panel top does not contain the slot line")
        assertTrue(panelBottom >= contentBottom, "panel bottom does not contain the help line")
        assertTrue(panelTop >= titleBottom, "the initials panel overlaps its own title")
    }

    // --- 2. On screen: no panel's edge leaves 0f..1f, at every aspect from 4:3 to 32:9 --------
    // Same technique MenuLayoutTest/PauseScreenTest already use: a half-span is a fraction of
    // HEIGHT but drawn across WIDTH, so it is checked against width by dividing by aspect.
    // PADDING_FRACTION is folded in since it is what the panel actually draws at.

    @Test
    fun `no panel's horizontal edge leaves the screen, at any aspect from 4-3 to 32-9`()
    {
        val aspects = listOf(4f / 3f, 16f / 10f, 16f / 9f, 2.389f, 32f / 9f)
        val halfSpans = mapOf(
            "menu" to MenuLayout.HIGHLIGHT_HALF_SPAN,
            "leaderboard" to AttractLayout.ROW_HALF_SPAN,
            "pause" to PanelLayout.PAUSE_HALF_SPAN,
            "briefing" to PanelLayout.BRIEFING_HALF_SPAN,
            "run-over" to PanelLayout.RUN_OVER_HALF_SPAN,
            "initials" to PanelLayout.INITIALS_HALF_SPAN
        )
        for ((name, halfSpan) in halfSpans)
        {
            val padded = halfSpan + PanelLayout.PADDING_FRACTION
            for (aspect in aspects)
            {
                val edgeFraction = padded / aspect
                assertTrue(
                    edgeFraction < 0.5f,
                    "$name panel's edge at aspect $aspect is ${0.5f + edgeFraction} " +
                        "(or ${0.5f - edgeFraction}), outside 0f..1f"
                )
            }
        }
    }

    // --- 3. The attract screen's panel clears the diver halo -----------------------------
    // Not "the leaderboard panel starts below the halo" restated - that would just restate
    // AttractLayout.HEADING_Y > haloBottom, already covered by AttractScreenTest. This is the
    // PANEL's own top edge, post-padding and post-clamp, which is the thing that could actually
    // intrude even when the unpadded content does not.

    @Test
    fun `the leaderboard panel's top edge never rises into the diver halo band`()
    {
        val h = 1200f
        for (rowCount in listOf(1, AttractLayout.LEADERBOARD_SIZE))
        {
            val panel = PanelLayout.bounds(
                contentLeft = -h * AttractLayout.ROW_HALF_SPAN,
                contentTop = h * AttractLayout.HEADING_Y,
                contentRight = h * AttractLayout.ROW_HALF_SPAN,
                contentBottom = h * AttractLayout.bottomOfBoard(rowCount),
                screenHeight = h,
                minTop = h * HALO_BOTTOM
            )
            val panelTop = panel.centreY - panel.height * 0.5f
            assertTrue(
                panelTop >= h * HALO_BOTTOM - 1e-3f,
                "rowCount=$rowCount: the leaderboard panel's top ($panelTop) intrudes into the " +
                    "diver halo band (halo bottom = ${h * HALO_BOTTOM})"
            )
        }
    }

    @Test
    fun `the briefing panel's top edge never rises into the diver halo band either`()
    {
        // Not required by the task brief, which names only the attract screen - added because
        // BriefingLayout's own class doc places ROWS_TOP_Y just 0.03h below the halo's bottom
        // edge, thinner than PanelLayout.PADDING_FRACTION (0.025h), so the same intrusion risk
        // exists here even though nobody asked for it to be checked.
        val h = 1200f
        val titleBottom = h * (BriefingLayout.TITLE_Y + BriefingLayout.TITLE_FONT)
        val minTop = maxOf(titleBottom, h * HALO_BOTTOM)

        val panel = PanelLayout.bounds(
            contentLeft = -h * PanelLayout.BRIEFING_HALF_SPAN,
            contentTop = h * BriefingLayout.ROWS_TOP_Y,
            contentRight = h * PanelLayout.BRIEFING_HALF_SPAN,
            contentBottom = h * (BriefingLayout.RULE_Y + BriefingLayout.RULE_FONT),
            screenHeight = h,
            minTop = minTop
        )
        val panelTop = panel.centreY - panel.height * 0.5f
        assertTrue(
            panelTop >= h * HALO_BOTTOM - 1e-3f,
            "the briefing panel's top ($panelTop) intrudes into the diver halo band " +
                "(halo bottom = ${h * HALO_BOTTOM})"
        )
    }

    // --- 4. Width constants actually cover the worst-case string they claim to ---------------
    // Turns the arithmetic in PanelLayout's own KDoc into an assertion instead of a comment that
    // can silently go stale - the class doc there names the exact worst-case string for each
    // constant; this re-derives the same numbers and checks the constant against them.

    @Test
    fun `PAUSE_HALF_SPAN covers the widest exit-hold hint`()
    {
        val worst = ControlHints.exitHold(true, "LEFT BUMPER", "RIGHT BUMPER")
        val needed = halfWidthOf(worst, PauseLayout.HINT_FONT)
        assertTrue(
            PanelLayout.PAUSE_HALF_SPAN >= needed,
            "\"$worst\" needs ${needed}h half-width but PAUSE_HALF_SPAN is only ${PanelLayout.PAUSE_HALF_SPAN}h"
        )
    }

    @Test
    fun `BRIEFING_HALF_SPAN covers the fixed rule line`()
    {
        val needed = halfWidthOf(ScreenText.BRIEFING_RULE, BriefingLayout.RULE_FONT)
        assertTrue(
            PanelLayout.BRIEFING_HALF_SPAN >= needed,
            "\"${ScreenText.BRIEFING_RULE}\" needs ${needed}h half-width but BRIEFING_HALF_SPAN is only ${PanelLayout.BRIEFING_HALF_SPAN}h"
        )
    }

    @Test
    fun `RUN_OVER_HALF_SPAN covers the widest play-again hint`()
    {
        val worst = ControlHints.playAgain(true, "LEFT BUMPER")
        val needed = halfWidthOf(worst, RunOverAnchors.HINT_FONT)
        assertTrue(
            PanelLayout.RUN_OVER_HALF_SPAN >= needed,
            "\"$worst\" needs ${needed}h half-width but RUN_OVER_HALF_SPAN is only ${PanelLayout.RUN_OVER_HALF_SPAN}h"
        )
    }

    @Test
    fun `INITIALS_HALF_SPAN covers the widest initials-help hint and the slot line`()
    {
        val worstHelp = ControlHints.initialsHelp(true, "LEFT BUMPER")
        val neededHelp = halfWidthOf(worstHelp, InitialsAnchors.HELP_FONT)
        val neededSlots = halfWidthOf(ScreenText.initialsSlots("AAA", 0), InitialsAnchors.SLOTS_FONT)

        assertTrue(
            PanelLayout.INITIALS_HALF_SPAN >= neededHelp,
            "\"$worstHelp\" needs ${neededHelp}h half-width but INITIALS_HALF_SPAN is only ${PanelLayout.INITIALS_HALF_SPAN}h"
        )
        assertTrue(
            PanelLayout.INITIALS_HALF_SPAN >= neededSlots,
            "the initials slot line needs ${neededSlots}h half-width but INITIALS_HALF_SPAN is only ${PanelLayout.INITIALS_HALF_SPAN}h"
        )
    }

    // --- 5. Shared constants are sane fractions -----------------------------------------------

    @Test
    fun `every shared panel constant is a screen fraction strictly inside the screen`()
    {
        val values = mapOf(
            "ALPHA" to PanelLayout.ALPHA,
            "PADDING_FRACTION" to PanelLayout.PADDING_FRACTION,
            "CORNER_RADIUS_FRACTION" to PanelLayout.CORNER_RADIUS_FRACTION,
            "PAUSE_HALF_SPAN" to PanelLayout.PAUSE_HALF_SPAN,
            "BRIEFING_HALF_SPAN" to PanelLayout.BRIEFING_HALF_SPAN,
            "RUN_OVER_HALF_SPAN" to PanelLayout.RUN_OVER_HALF_SPAN,
            "INITIALS_HALF_SPAN" to PanelLayout.INITIALS_HALF_SPAN
        )
        for ((name, value) in values)
            assertTrue(value > 0f && value < 1f, "$name = $value is not a screen fraction")
    }

    @Test
    fun `the composed pause-screen alpha is dark but short of opaque`()
    {
        // The one arithmetic figure the task brief explicitly asks to be reported (both here
        // and in panel-report.md): the pause screen already draws a full-screen scrim at
        // PauseLayout.SCRIM_ALPHA before the panel goes on top of it, and the two must not
        // compose to near-opacity - that would destroy the "run is held, not thrown away"
        // reading the scrim exists to give. Standard alpha-over-alpha compositing for two
        // layers of the same (black) colour: combined = 1 - (1-a1)*(1-a2).
        val composed = 1f - (1f - PauseLayout.SCRIM_ALPHA) * (1f - PanelLayout.ALPHA)
        assertTrue(composed < 0.95f, "composed pause alpha $composed reads as opaque, not held")
        assertTrue(composed > PauseLayout.SCRIM_ALPHA, "the panel should darken the pause screen further, not lighten it")
    }
}
