package render

import AttractLayout
import BriefingLayout
import InitialsLayout
import PauseLayout
import RunOverLayout
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
 * THE DUPLICATION THIS CLASS USED TO CARRY IS GONE (2026-08-31). The doc here read: "`run-over`
 * and `initials entry` have no dedicated layout object in `EnPustTil.kt` (their anchors are
 * locals inside the two draw functions) — the constants restated here are copies of those
 * locals. That duplication is a real risk this test cannot remove, only narrow: if either draw
 * site's literals move, these tests silently stop describing the real screen. Flagged rather
 * than hidden." Both screens now have a real layout object — [RunOverLayout] and
 * [InitialsLayout], siblings of [PauseLayout]/[BriefingLayout] — and this file IMPORTS them, so
 * the risk is removed rather than narrowed: there is one copy of each number and the test reads
 * it. The private `RunOverAnchors`/`InitialsAnchors` objects that stood in for them are deleted.
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

    private val HALO_BOTTOM = Framing.DIVER_SCREEN_FRACTION + AttractLayout.DIVER_HALO_HALF_HEIGHT

    // --- 1. Containment: the panel encloses its content box, with the declared padding --------
    // Deliberately NOT aspect-swept for the vertical relationships: every anchor PanelLayout.bounds
    // consumes is a fraction of screen HEIGHT (never width), so containment on the vertical axis
    // is aspect-invariant by construction - the same reasoning BriefingScreenTest's own class doc
    // gives for skipping a vertical aspect sweep. What DOES vary with aspect is whether the
    // panel's horizontal edges stay on screen, checked separately below per the task brief.

    @Test
    fun `the main menu panel contains the row list, and neither its title nor its hint legend`()
    {
        val h = 1200f
        val contentTop = h * MenuLayout.ROWS_TOP_Y
        val model = MenuModel()

        // Swept over both pages since 2026-08-31: the highlight half-span - which is also the
        // panel's own width at the draw site - became per-page when the shared 0.20 turned out
        // to be too narrow for GRAPHICS' widest row and too wide for ROOT's four short words
        // (see MenuLayout.HIGHLIGHT_HALF_SPAN_ROOT). Checking one page would leave the other's
        // panel edge unasserted.
        //
        // AND OVER BOTH TITLE VARIANTS, and against the REAL contentBottom. This case used to
        // pass `contentBottom = h * (HINT_Y + HINT_FONT)` while production passed
        // `rowsBottom(items.size)` - a pre-existing drift the menu-polish plan lists under
        // "deliberately not doing", which meant Task 7's grouped row block was not covered here
        // at all. Both are now read the way drawMainMenu reads them, so this test describes the
        // panel that is actually drawn.
        for (page in MenuPage.entries)
        {
            val items = model.itemsOn(page)
            val gaps = MenuLayout.groupGapsIn(items)
            val halfSpan = h * MenuLayout.highlightHalfSpanFor(page)
            val contentBottom = h * MenuLayout.rowsBottom(items.size, gaps)

            for (runHeld in listOf(false, true))
            {
                val titleBottom = h * (MenuLayout.titleY(page, runHeld) + MenuLayout.titleFont(page, runHeld))

                val panel = PanelLayout.bounds(
                    contentLeft = -halfSpan,
                    contentTop = contentTop,
                    contentRight = halfSpan,
                    contentBottom = contentBottom,
                    screenHeight = h,
                    minTop = titleBottom
                )

                val panelTop = panel.centreY - panel.height * 0.5f
                val panelBottom = panel.centreY + panel.height * 0.5f
                val panelLeft = panel.centreX - panel.width * 0.5f
                val panelRight = panel.centreX + panel.width * 0.5f
                val label = "$page (runHeld=$runHeld)"

                assertTrue(panelTop <= contentTop, "$label panel top $panelTop does not contain content top $contentTop")
                assertTrue(panelBottom >= contentBottom, "$label panel bottom $panelBottom does not contain content bottom $contentBottom")
                assertTrue(panelLeft <= -halfSpan, "$label panel does not contain the row list's left edge")
                assertTrue(panelRight >= halfSpan, "$label panel does not contain the row list's right edge")
                // 1e-3 px of slack, and it is load-bearing rather than defensive. The
                // page-header title is deliberately sized so `titleY + titleFont` EQUALS the
                // padded content top (see MenuLayout.TITLE_Y - 0.135 + 0.06 = 0.195 = 0.22 -
                // 0.025), so `maxOf` is picking between two numbers that are equal in decimal
                // and differ by ~1.8e-8 in float: 0.135f + 0.06f rounds up, 0.22f - 0.025f
                // rounds down. Without the epsilon this case fails on the exact configuration
                // it is meant to certify as correct.
                assertTrue(panelTop >= titleBottom - 1e-3f, "the $label menu panel overlaps its own title")

                // The legend is OUTSIDE this panel now, which is the Task 6 change: it tracks
                // the panel's bottom edge instead of sitting at a fixed 0.90h, so a panel that
                // grew to contain it would mean the two had collided.
                assertTrue(
                    h * MenuLayout.hintY(items.size, gaps) > panelBottom,
                    "$label panel bottom $panelBottom has swallowed the hint legend"
                )
            }
        }
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
    fun `the briefing panel contains the rows, facts and rule, and grows to cover the countdown and skip lines`()
    {
        val h = 1200f
        // THE TITLE'S BOTTOM EDGE IS NOW THE WHOLE FLOOR. This was
        // `maxOf(titleBottom, h * HALO_BOTTOM)`, mirroring a draw site that clamped the
        // briefing panel out of the diver's halo band; that clamp was removed on 2026-08-31
        // (Task 12) and this line has to follow it, or the test is checking containment for a
        // panel the game does not draw. It was not merely stale — it FAILED, because the panel's
        // real top (0.175h) is above the halo's bottom (0.54h) and the clamped one was not, so
        // the clamped panel did not contain its own first row at 0.200h.
        val minTop = h * (BriefingLayout.TITLE_Y + BriefingLayout.TITLE_FONT)

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
    fun `the run-over panel contains the score block and the retry hint, not the title`()
    {
        // Was `contains only the retry hint`, and the "only" was true until 2026-08-31: the
        // panel's content box now starts at the big score numeral (design doc SS3.5's "then
        // enlarge"), so the title-clearance relationship below is being checked against a
        // DIFFERENT and much taller content box than the one it was written for.
        val h = 1200f
        val contentTop = h * RunOverLayout.SCORE_Y
        val contentBottom = h * (RunOverLayout.HINT_Y + RunOverLayout.HINT_FONT)
        val titleBottom = h * (RunOverLayout.TITLE_Y + RunOverLayout.TITLE_FONT)

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

        assertTrue(panelTop <= contentTop, "panel top does not contain the score numeral")
        assertTrue(panelBottom >= contentBottom, "panel bottom does not contain the retry hint")
        assertTrue(panelTop >= titleBottom, "the run-over panel overlaps its own title")
    }

    @Test
    fun `the run-over panel does not run into its own title`()
    {
        // THE DEFECT NAIVE PADDING WOULD HAVE SHIPPED, AND IT SURVIVED THE 2026-08-31 REBUILD
        // OF THIS SCREEN RATHER THAN BEING DESIGNED OUT — which is why this test is still here
        // and its numbers have moved. The title's own text box
        // (RunOverLayout.TITLE_Y .. +TITLE_FONT) ends at 0.621h and the panel's content now
        // starts at the score numeral, RunOverLayout.SCORE_Y = 0.645h. That is a 0.024h gap,
        // still narrower than PanelLayout.PADDING_FRACTION (0.025h), so padding the numeral's
        // top edge naively would once again put the panel's top edge inside "RUN OVER". (The
        // pre-rebuild figures were 0.54h, 0.545h and a 0.005h gap.) This is exactly the fault
        // the task brief says "only an eye catches" — caught here by the numbers instead. See
        // panel-report.md's "found by arithmetic" section.
        val h = 1200f
        val titleBottom = h * (RunOverLayout.TITLE_Y + RunOverLayout.TITLE_FONT)

        val panel = PanelLayout.bounds(
            contentLeft = -h * PanelLayout.RUN_OVER_HALF_SPAN,
            contentTop = h * RunOverLayout.SCORE_Y,
            contentRight = h * PanelLayout.RUN_OVER_HALF_SPAN,
            contentBottom = h * (RunOverLayout.HINT_Y + RunOverLayout.HINT_FONT),
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
    fun `the initials panel contains the slot rects and the help line, not the title`()
    {
        // "slot line" until 2026-08-31, when the single bracketed string became three rects
        // (design doc SS3.6) - the content box's top edge is now the top of a RECTANGLE rather
        // than the top of a text box, which is why InitialsLayout.SLOTS_TOP_Y replaced a
        // SLOTS_Y that meant something subtly different.
        val h = 1200f
        val contentTop = h * InitialsLayout.SLOTS_TOP_Y
        val contentBottom = h * (InitialsLayout.HELP_Y + InitialsLayout.HELP_FONT)
        val titleBottom = h * (InitialsLayout.TITLE_Y + InitialsLayout.TITLE_FONT)

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

        assertTrue(panelTop <= contentTop, "panel top does not contain the slot rects")
        assertTrue(panelBottom >= contentBottom, "panel bottom does not contain the help line")
        assertTrue(panelTop >= titleBottom, "the initials panel overlaps its own title")

        // The slot BLOCK, which the content box above only bounds vertically. Horizontally the
        // panel is sized by PanelLayout.INITIALS_HALF_SPAN and the slots are laid out from
        // their own pitch, so the two could disagree without any other assertion here noticing
        // - and the failure mode is a slot rect drawn straddling or outside the card's edge.
        val panelLeft = panel.centreX - panel.width * 0.5f
        val panelRight = panel.centreX + panel.width * 0.5f
        val centreX = 0f
        for (slot in 0 until InitialsLayout.SLOT_COUNT)
        {
            val slotCentre = InitialsLayout.slotCentreX(slot, centreX, h)
            val halfSlot = h * InitialsLayout.SLOT_WIDTH * 0.5f
            assertTrue(
                slotCentre - halfSlot >= panelLeft && slotCentre + halfSlot <= panelRight,
                "initials slot $slot spans ${slotCentre - halfSlot}..${slotCentre + halfSlot}, " +
                    "outside the panel's $panelLeft..$panelRight"
            )
        }
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
            // Both menu pages by name, not one entry for "menu": the span became per-page on
            // 2026-08-31 and the wider of the two (GRAPHICS) is the one that could actually run
            // off a 4:3 panel, so listing only ROOT would leave the risky one unchecked.
            "menu ROOT" to MenuLayout.HIGHLIGHT_HALF_SPAN_ROOT,
            "menu GRAPHICS" to MenuLayout.HIGHLIGHT_HALF_SPAN_GRAPHICS,
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

    // `the briefing panel's top edge never rises into the diver halo band either` STOOD HERE
    // AND IS DELETED, NOT PORTED (2026-08-31, Task 12). It asserted exactly the constraint that
    // has been removed — that `drawBriefingScreen`'s `minTop` clamps the briefing panel out of
    // the diver's halo band — and there is no inverted form of it worth writing, because
    // "the panel's top MAY be in the band" is a permission, not a property.
    //
    // Removing it is legitimate rather than convenient, and its own comment is the evidence: it
    // opened "Not required by the task brief, which names only the attract screen - added
    // because BriefingLayout's own class doc places ROWS_TOP_Y just 0.03h below the halo's
    // bottom edge ... so the same intrusion risk exists even though nobody asked for it to be
    // checked". It was a voluntary guard on a numeric coincidence between two constants, and
    // BOTH of those constants have since moved: ROWS_TOP_Y is 0.200 and the clamp is gone.
    //
    // What replaces it is not nothing. `BriefingScreenTest`'s halo rule became a disjunction —
    // clears above, clears below, OR IS INSIDE THE PANEL — so every briefing element is still
    // held to a relationship with the diver, and the panel extent that third arm is measured
    // against is computed with `PanelLayout.bounds` there, exactly as the tests above do here.
    // The attract screen's own halo test, four lines up, is untouched: nothing about that
    // screen changed and its panel has no card-over-the-diver argument to make.

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
    fun `RUN_OVER_HALF_SPAN covers the widest play-again hint and the widest score`()
    {
        // Two subjects since 2026-08-31: the numeral was added to this panel's content and at
        // RunOverLayout.SCORE_FONT (0.105h) a five-digit score is within 5% of the hint's own
        // requirement, so which of the two actually drives the constant is no longer obvious
        // from reading it. Both are checked, and PanelLayout.RUN_OVER_HALF_SPAN's doc names
        // the hint as the driver — if that ever stops being true, this test says so.
        val worst = ControlHints.playAgain(true, "LEFT BUMPER")
        val neededHint = halfWidthOf(worst, RunOverLayout.HINT_FONT)
        // The widest score the game can show. Scoring is unbounded in principle, so this is the
        // same worst case ScreenText.all() sweeps rather than a derived maximum.
        val neededScore = halfWidthOf(ScreenText.bankedNumber(99999), RunOverLayout.SCORE_FONT)
        val neededCaption = halfWidthOf(ScreenText.RUN_OVER_CAPTION, RunOverLayout.CAPTION_FONT)

        assertTrue(
            PanelLayout.RUN_OVER_HALF_SPAN >= neededHint,
            "\"$worst\" needs ${neededHint}h half-width but RUN_OVER_HALF_SPAN is only ${PanelLayout.RUN_OVER_HALF_SPAN}h"
        )
        assertTrue(
            PanelLayout.RUN_OVER_HALF_SPAN >= neededScore,
            "a five-digit score at SCORE_FONT needs ${neededScore}h half-width but " +
                "RUN_OVER_HALF_SPAN is only ${PanelLayout.RUN_OVER_HALF_SPAN}h"
        )
        assertTrue(
            PanelLayout.RUN_OVER_HALF_SPAN >= neededCaption,
            "\"${ScreenText.RUN_OVER_CAPTION}\" needs ${neededCaption}h half-width but " +
                "RUN_OVER_HALF_SPAN is only ${PanelLayout.RUN_OVER_HALF_SPAN}h"
        )
    }

    @Test
    fun `INITIALS_HALF_SPAN covers the widest initials-help hint and the slot block`()
    {
        // THE SECOND SUBJECT CHANGED, IT WAS NOT DROPPED. This used to measure
        // `ScreenText.initialsSlots("AAA", 0)` — a composed 11-character string — against a
        // 0.06h font. That function no longer exists (design doc SS3.6: the bracketed line
        // became three rects), so the width bound it stood for is re-expressed as the thing
        // that is actually drawn: the slot BLOCK's own half-span, which InitialsLayout derives
        // from SLOT_WIDTH, SLOT_GAP and SLOT_COUNT rather than from any glyph estimate. Same
        // relationship, a subject that can now be measured exactly instead of guessed at EM.
        val worstHelp = ControlHints.initialsHelp(true, "LEFT BUMPER")
        val neededHelp = halfWidthOf(worstHelp, InitialsLayout.HELP_FONT)
        val neededSlots = InitialsLayout.slotsHalfSpan()

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
            "MENU_PLATE_ALPHA" to PanelLayout.MENU_PLATE_ALPHA,
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
        // reading the scrim exists to give.
        //
        // FINDING I8 (final review, 2026-08-31): this used to compute
        // `1 - (1-a1)*(1-a2)` (0.846) - standard source-over compositing on DISPLAYED
        // alphas, and a test that CANNOT fail from the real bug the arithmetic was meant to
        // catch, because it pins the wrong blend model rather than the surface's actual one.
        // The HUD surface SQUARES alpha (Hud.authoredAlphaFor's own doc, measured: a single
        // draw at authored alpha `a` onto a blank surface stores `a^2`), so re-derived from
        // that same measurement for two stacked draws - `new = src^2 + dst*(1-src)`, where
        // `src` is the RAW (sqrt) value passed to setDrawColor, not the displayed one:
        //
        // SWEPT OVER BOTH PLATE ALPHAS since 2026-08-31. Hud.renderPanel gained a per-screen
        // plate alpha and the main menu passes PanelLayout.MENU_PLATE_ALPHA, which is far closer
        // to the ceiling this bound describes than the shared default ever was - and the main
        // menu is drawn over PauseLayout.SCRIM_ALPHA on BOTH of its branches (the resting menu
        // gained the same scrim in the same pass), so this is the composition it actually meets.
        // Checking only ALPHA would leave the one value that could realistically cross the bound
        // unchecked.
        for ((name, alpha) in listOf("ALPHA" to PanelLayout.ALPHA, "MENU_PLATE_ALPHA" to PanelLayout.MENU_PLATE_ALPHA))
        {
            val src = kotlin.math.sqrt(alpha)
            val over = alpha + PauseLayout.SCRIM_ALPHA * (1f - src)
            assertTrue(over < 0.95f, "composed pause alpha $over from $name reads as opaque, not held")
        }

        val panelSrc = kotlin.math.sqrt(PanelLayout.ALPHA)
        val composed = PanelLayout.ALPHA + PauseLayout.SCRIM_ALPHA * (1f - panelSrc)
        assertTrue(composed < 0.95f, "composed pause alpha $composed reads as opaque, not held")

        // NOT `composed > PauseLayout.SCRIM_ALPHA`, which the old (wrong-model) version of
        // this test asserted: under the real squared-alpha blend, a LIGHTER panel (0.45)
        // drawn over a HEAVIER scrim (0.72) actually composes to marginally BELOW the
        // scrim's own alpha (~0.687 < 0.72) - a real, measured property of this blend, not
        // a regression. Bounded generously rather than re-pinned exactly, so a future change
        // to either alpha does not need this comment re-derived by hand; the reviewer's own
        // ruling (Finding I8) is that this is harmless regardless, since the panel's bounds
        // sit entirely inside the area the plain scrim already covers - see this class's own
        // doc for the diver/pearls/clock that stay untouched by it.
        assertTrue(composed > PauseLayout.SCRIM_ALPHA * 0.9f,
            "composed pause alpha $composed is unexpectedly far below the scrim alone - re-check the blend model")
    }
}
