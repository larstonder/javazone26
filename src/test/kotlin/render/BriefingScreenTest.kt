package render

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
        Triple("rule", BriefingLayout.RULE_Y, BriefingLayout.RULE_FONT),
        Triple("countdown", BriefingLayout.COUNTDOWN_Y, BriefingLayout.COUNTDOWN_FONT),
        Triple("skip", BriefingLayout.SKIP_Y, BriefingLayout.SKIP_FONT)
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
    fun `the two control columns do not collide at the widest button label`()
    {
        // A rebind can turn "A" into "RIGHT BUMPER". The token column is right-aligned at
        // centre - gap and the verb column left-aligned at centre + gap, so the tokens keep a
        // clean right edge whatever their width - but the pair still has to fit.
        val token = ControlHints.labelFor("RIGHT_BUMPER")
        val verb = ScreenText.BRIEFING_VERB_BLEED
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
            ScreenText.BRIEFING_VERB_SWIM,
            ScreenText.BRIEFING_VERB_KICK,
            ScreenText.BRIEFING_VERB_BLEED,
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
}
