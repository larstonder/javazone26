package render

import AttractLayout
import BriefingLayout
import DefaultFont
import ScreenText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Geometry for the pre-run briefing.
 *
 * Modelled on `AttractScreenTest`'s layout half, and deliberately NOT a "does it fit at 4:3,
 * 16:9 and 32:9" vertical test: every anchor here is a fraction of screen HEIGHT, so vertical
 * fit is aspect-invariant BY CONSTRUCTION and such a test could never fail. The genuine
 * aspect-dependent risk is WIDTH at the narrowest panel, which is what is asserted below.
 *
 * NINE ELEMENTS SINCE 2026-08-31, NOT SIX. The screen gained three FACTS (decision D3 —
 * carried pearls make you heavier and slower, deeper pearls are worth more, the bubbles are
 * your air), and every sweep in this file grew to cover them. Two consequences worth naming
 * before reading the tests:
 *
 *  - [boxes] is the single list every geometric assertion walks, so a row added to the screen
 *    and forgotten here is invisible to all of them. It is the one hand-maintained thing in
 *    this file and the one worth checking against `EnPustTil.drawBriefingScreen` by eye.
 *  - `` `every element clears the diver and its glow` `` is a DISJUNCTION now rather than a
 *    two-way clearance test, and its third arm is only reachable because
 *    `drawBriefingScreen`'s `minTop` dropped its halo clamp in the same change. See that test
 *    for the full argument, and `BriefingLayout`'s class doc for why a bordered card over the
 *    diver is a different proposition from loose text over him.
 */
class BriefingScreenTest
{
    /** An upper bound on a glyph's advance in the default font. Same estimate `Hud` uses. */
    private val EM = 0.62f

    private fun widthOf(text: String, fontFraction: Float) = text.length * EM * fontFraction

    private fun boxes(): List<Triple<String, Float, Float>> = listOf(
        Triple("title", BriefingLayout.TITLE_Y, BriefingLayout.TITLE_FONT),
        Triple("row0", BriefingLayout.rowY(0), BriefingLayout.ROW_FONT),
        Triple("row1", BriefingLayout.rowY(1), BriefingLayout.ROW_FONT),
        Triple("row2", BriefingLayout.rowY(2), BriefingLayout.ROW_FONT),
        Triple("fact0", BriefingLayout.factY(0), BriefingLayout.FACT_FONT),
        Triple("fact1", BriefingLayout.factY(1), BriefingLayout.FACT_FONT),
        Triple("fact2", BriefingLayout.factY(2), BriefingLayout.FACT_FONT),
        Triple("rule", BriefingLayout.RULE_Y, BriefingLayout.RULE_FONT),
        Triple("countdown", BriefingLayout.COUNTDOWN_Y, BriefingLayout.COUNTDOWN_FONT),
        Triple("skip", BriefingLayout.SKIP_Y, BriefingLayout.SKIP_FONT)
    )

    /** The three fact strings, paired with the constant name each failure should point at. */
    private fun facts(): List<Pair<String, String>> = listOf(
        "BRIEFING_FACT_WEIGHT" to ScreenText.BRIEFING_FACT_WEIGHT,
        "BRIEFING_FACT_DEPTH" to ScreenText.BRIEFING_FACT_DEPTH,
        "BRIEFING_FACT_AIR" to ScreenText.BRIEFING_FACT_AIR
    )

    @Test
    fun `every anchor is a screen fraction strictly inside the screen`()
    {
        for ((name, y, font) in boxes())
        {
            assertTrue(y > 0f && y < 1f, "$name anchor $y is not a fraction in (0,1)")
            assertTrue(font > 0f && font < 1f, "$name font $font is not a fraction in (0,1)")
            assertTrue(y + font < 1f, "$name runs off the bottom: ${y + font}")
        }
    }

    @Test
    fun `no two elements overlap`()
    {
        // Text grows DOWNWARD from its anchor, so a block occupies y .. y + fontSize - the
        // convention AttractLayout documents.
        val ordered = boxes()
        for (i in 0 until ordered.size - 1)
        {
            val (nameA, yA, fontA) = ordered[i]
            val (nameB, yB, _) = ordered[i + 1]
            assertTrue(
                yA + fontA <= yB,
                "$nameA ends at ${yA + fontA}, which is below $nameB's anchor $yB"
            )
        }
    }

    @Test
    fun `the widest line fits across the narrowest plausible panel`()
    {
        // 4:3 is the narrowest aspect this project tests anywhere (AttractScreenTest checks
        // its leaderboard row against h * 4/3). The risk is horizontal, not vertical.
        val screenWidth = 4f / 3f      // in units of screen height
        val widest = ScreenText.BRIEFING_RULE
        val half = widthOf(widest, BriefingLayout.RULE_FONT) * 0.5f
        assertTrue(
            half < screenWidth * 0.5f,
            "\"$widest\" needs ${half * 2f}h of width; a 4:3 panel has ${screenWidth}h"
        )
    }

    @Test
    fun `every fact fits inside the briefing panel`()
    {
        // THE CONSTRAINT THAT ACTUALLY BINDS THE FACTS, and the reason one of the owner's three
        // sentences had to be reworded rather than merely typed in. There is no glyph-metrics
        // API anywhere in this project (see PanelLayout's class doc), so the panel is sized
        // from EM = 0.62 and a fact has to be short enough for that estimate. At
        // FACT_FONT = 0.026 and BRIEFING_HALF_SPAN = 0.32 the ceiling is
        // `2 * 0.32 / (0.62 * 0.026)` = 39 characters; the owner's phrasing of the weight fact
        // ("carried pearls make you heavier and slower") is 42 and needs 0.339h.
        //
        // Asserted against the PANEL rather than against the screen, unlike the rule line
        // above: a fact that fits on a 4:3 screen but not inside the card is clipped by
        // nothing and simply hangs off the plate, which looks like a bug in the panel rather
        // than in the string.
        for ((name, fact) in facts())
        {
            val needed = widthOf(fact, BriefingLayout.FACT_FONT) * 0.5f
            assertTrue(
                needed <= PanelLayout.BRIEFING_HALF_SPAN,
                "$name (\"$fact\", ${fact.length} chars) needs ${needed}h of half-width but " +
                    "PanelLayout.BRIEFING_HALF_SPAN is ${PanelLayout.BRIEFING_HALF_SPAN}h - " +
                    "shorten the string or widen the panel, and read BRIEFING_HALF_SPAN's doc first"
            )
        }
    }

    @Test
    fun `the two control columns do not collide at the widest button label`()
    {
        // A rebind can turn "A" into "RIGHT BUMPER". The token column is right-aligned at
        // centre - gap and the verb column left-aligned at centre + gap, so the tokens keep a
        // clean right edge whatever their width - but the pair still has to fit.
        val token = ControlHints.labelFor("RIGHT_BUMPER")
        val verb = ScreenText.VERB_BLEED
        val total = widthOf(token, BriefingLayout.ROW_FONT) +
                    widthOf(verb, BriefingLayout.ROW_FONT) +
                    BriefingLayout.COLUMN_GAP * 2f
        assertTrue(total < 4f / 3f, "the widest control row needs ${total}h across a 1.333h panel")
    }

    @Test
    fun `every briefing string is drawable`()
    {
        val strings = listOf(
            ScreenText.BRIEFING_TITLE,
            ScreenText.BRIEFING_RULE,
            ScreenText.BRIEFING_SKIP_SUFFIX,
            ScreenText.VERB_SWIM,
            ScreenText.VERB_KICK,
            ScreenText.VERB_BLEED,
            ScreenText.BRIEFING_FACT_WEIGHT,
            ScreenText.BRIEFING_FACT_DEPTH,
            ScreenText.BRIEFING_FACT_AIR,
            ScreenText.briefingCountdown(5)
        )
        for (text in strings)
            assertTrue(
                DefaultFont.undrawableCodePointsIn(text).isEmpty(),
                "\"$text\" is not inside the default font's atlas"
            )
    }

    // NOTE: "the countdown line is absent under EPT_BRIEFING_HOLD" is NOT asserted here.
    // Suppression happens inside drawBriefingScreen, which needs a GL context. The FLAG that
    // drives it (`briefingAutoStarts`) is pinned in RunLifecycleTest, and the drawn result is
    // checked by the pinned window grab in Step 8 - which is the only evidence that can
    // actually show a string on a screen.

    @Test
    fun `every element clears the diver and its glow, or sits inside the panel`()
    {
        // THE ORIGINAL FINDING, AND THE 2026-08-31 AMENDMENT TO IT.
        //
        // The finding: the diver is drawn at every run start, pinned to
        // Framing.DIVER_SCREEN_FRACTION with AttractLayout.DIVER_HALO_HALF_HEIGHT of glow
        // either side of him - exactly the band AttractScreenTest's "the sign clears the
        // diver and its glow" polices for the attract screen. The scrim this screen draws
        // DARKENS the world; it does not remove the diver from it, so a briefing element laid
        // out loose inside this band visually collides with him. A pinned capture at the first
        // shipped anchors (rows at 0.300/0.357/0.414) showed exactly that - the diver's head
        // sitting between "Z" and "kick". None of that has been retracted.
        //
        // The amendment: this screen now draws a PANEL, with a real pale border ring around it
        // (PanelLayout.BORDER_FRACTION, added in the same pass and measured - before it, the
        // briefing panel's inside-vs-outside was one to two sRGB levels, i.e. the card did not
        // exist on screen). Text inside a bordered card is not loose text: the card is the near
        // layer and the diver becomes backdrop. So the rule is a DISJUNCTION - clear above the
        // band, clear below it, or be inside the card - and the price of the old two-way rule
        // was six elements crammed into the bottom 0.43h while 0.145h..0.54h sat empty.
        //
        // THE THIRD ARM IS ONLY REACHABLE BECAUSE `drawBriefingScreen`'s `minTop` DROPPED ITS
        // HALO TERM. With that clamp in place the panel could not start above the halo's bottom
        // edge, so nothing the panel contained could be in the band, so the arm would be dead
        // code and this test would assert exactly what its predecessor did while appearing to
        // assert something new. The two changes are one change.
        //
        // The panel extent is recomputed here from PanelLayout.bounds rather than hardcoded,
        // the same technique PanelLayoutTest uses, so it tracks the real draw site's geometry.
        val haloTop = Framing.DIVER_SCREEN_FRACTION - AttractLayout.DIVER_HALO_HALF_HEIGHT
        val haloBottom = Framing.DIVER_SCREEN_FRACTION + AttractLayout.DIVER_HALO_HALF_HEIGHT

        // In screen-fraction units (screenHeight = 1), so the result reads as fractions.
        val panel = PanelLayout.bounds(
            contentLeft = -PanelLayout.BRIEFING_HALF_SPAN,
            contentTop = BriefingLayout.ROWS_TOP_Y,
            contentRight = PanelLayout.BRIEFING_HALF_SPAN,
            // The widest case the panel is ever drawn at - the skip hint showing. A narrower
            // case can only make the panel SHORTER, and a shorter panel containing an element
            // is what the per-element check below would catch.
            contentBottom = BriefingLayout.SKIP_Y + BriefingLayout.SKIP_FONT,
            screenHeight = 1f,
            minTop = BriefingLayout.TITLE_Y + BriefingLayout.TITLE_FONT
        )
        val panelTop = panel.centreY - panel.height * 0.5f
        val panelBottom = panel.centreY + panel.height * 0.5f

        var sawOneInTheBand = false
        for ((name, y, font) in boxes())
        {
            val bottom = y + font
            val clearsAbove = bottom <= haloTop
            val clearsBelow = y >= haloBottom
            val insidePanel = y >= panelTop && bottom <= panelBottom
            if (!clearsAbove && !clearsBelow) sawOneInTheBand = true
            assertTrue(
                clearsAbove || clearsBelow || insidePanel,
                "$name occupies ${y}h..${bottom}h, which intrudes into the diver's " +
                "${haloTop}h..${haloBottom}h halo band by " +
                "${minOf(bottom, haloBottom) - maxOf(y, haloTop)}h, and is NOT inside the " +
                "panel (${panelTop}h..${panelBottom}h) either"
            )
        }

        // The guard against this test quietly reverting to its predecessor. If a future layout
        // pushes every element back out of the band, the disjunction's third arm stops being
        // exercised by anything and the `minTop` clamp could be reinstated without a single
        // red test - which is precisely the state this whole change exists to leave behind.
        assertTrue(
            sawOneInTheBand,
            "no briefing element is inside the diver's halo band any more, so the `insidePanel` " +
                "arm of the rule above is no longer exercised by anything. Either the layout " +
                "moved back below the diver (in which case restore drawBriefingScreen's halo " +
                "clamp and delete that arm) or this assertion needs re-deriving."
        )
    }

    @Test
    fun `the control rows are evenly spaced and ordered downward`()
    {
        // Guards the rowY arithmetic itself: a sign error or an off-by-one in the index would
        // still satisfy the overlap test if it happened to stay ordered.
        val step = BriefingLayout.rowY(1) - BriefingLayout.rowY(0)
        assertTrue(step > 0f, "rows must descend")
        assertTrue(
            kotlin.math.abs((BriefingLayout.rowY(2) - BriefingLayout.rowY(1)) - step) < 1e-5f,
            "row spacing is not uniform"
        )
        assertTrue(step > BriefingLayout.ROW_FONT, "rows are closer together than they are tall")
    }

    @Test
    fun `the facts are evenly spaced and ordered downward`()
    {
        val step = BriefingLayout.factY(1) - BriefingLayout.factY(0)
        assertTrue(step > 0f, "facts must descend")
        assertTrue(
            kotlin.math.abs((BriefingLayout.factY(2) - BriefingLayout.factY(1)) - step) < 1e-5f,
            "fact spacing is not uniform"
        )
        assertTrue(step > BriefingLayout.FACT_FONT, "facts are closer together than they are tall")
        assertTrue(
            kotlin.math.abs(BriefingLayout.factsBottom(3) - (BriefingLayout.factY(2) + BriefingLayout.FACT_FONT)) < 1e-6f,
            "factsBottom does not agree with the last fact's own box"
        )
    }

    @Test
    fun `the facts read as a separate block from the control rows`()
    {
        // THE ONE REQUIREMENT ON THIS SCREEN THAT IS ABOUT PERCEPTION RATHER THAN FIT, and the
        // one a fit-only test suite would let slip. Six evenly-pitched lines at one font is six
        // lines a player has to READ before discovering that the first three are a key-to-verb
        // table and the last three are prose (design doc SS5: "not six flat rows"). Three
        // separable levers do the work, and two of them are numbers this test can hold:
        //
        //   1. a smaller font for the facts,
        //   2. a gap between the blocks larger than the pitch inside either of them,
        //   3. centred single strings against the controls' two-column split - which lives in
        //      drawBriefingFact vs drawBriefingRow and cannot be reached from a headless JVM.
        //
        // Lose either of the first two and the blocks merge back into a list, with nothing
        // failing and nothing looking obviously wrong in a diff.
        assertTrue(
            BriefingLayout.FACT_FONT < BriefingLayout.ROW_FONT,
            "the facts are drawn at ${BriefingLayout.FACT_FONT}h against the control rows' " +
                "${BriefingLayout.ROW_FONT}h - at or above the rows' size the two blocks read as one list"
        )

        val rowPitch = BriefingLayout.rowY(1) - BriefingLayout.rowY(0)
        val factPitch = BriefingLayout.factY(1) - BriefingLayout.factY(0)
        val gap = BriefingLayout.FACTS_TOP_Y - (BriefingLayout.rowY(2) + BriefingLayout.ROW_FONT)
        assertTrue(
            gap > rowPitch && gap > factPitch,
            "the gap between the control block and the fact block is ${gap}h, which is not " +
                "larger than both the row pitch (${rowPitch}h) and the fact pitch (${factPitch}h) - " +
                "a separator smaller than the spacing inside either block does not separate them"
        )
    }

    @Test
    fun `the briefing dwell pays for the content on screen`()
    {
        // Not a geometry test, and it is here rather than in RunLifecycleTest because the thing
        // being related is a COUNT OF ELEMENTS ON THIS SCREEN to a duration - and this file is
        // the only place that knows the count. Decision D3 added three facts; the countdown was
        // raised from 5f to 9f in the same change precisely so it was not paid for out of the
        // reading time (design doc SS1: "nine elements at five seconds is 0.55s per element,
        // and nobody reads that").
        //
        // Bounded rather than pinned: 0.8 s per element is a floor nobody would defend going
        // below, not a claim that 1 s exactly is correct. What it catches is the real
        // regression - somebody adding a tenth element, or trimming the countdown back towards
        // its old queue-throughput value, without touching the other.
        val elements = boxes().size
        val perElement = RunLifecycle.BRIEFING_SECONDS / elements
        assertTrue(
            perElement >= 0.8f,
            "the briefing shows $elements elements in ${RunLifecycle.BRIEFING_SECONDS}s, i.e. " +
                "${perElement}s each. Adding content to this screen without extending " +
                "RunLifecycle.BRIEFING_SECONDS is how a teaching screen becomes one everybody skips."
        )
    }
}
