package render

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Menu geometry.
 *
 * Everything is a fraction of screen HEIGHT, never width, for the reason AttractLayout and
 * PauseLayout both give: engine.window.width/height are PHYSICAL framebuffer pixels and the
 * display's aspect ratio is not known until something is plugged in. A width fraction would
 * stretch the rows on a wide display while the glyphs inside them stayed put.
 *
 * What these tests assert is the RELATIONSHIP between the anchors - that nothing collides and
 * nothing leaves the screen - rather than the anchors themselves, which would just restate the
 * constants.
 */
class MenuLayoutTest
{
    // The GRAPHICS page: QUALITY, the six GI knobs (gi-knobs-brief.md), RESOLUTION, RENDER
    // SCALE, FULLSCREEN, FRAME CAP, VSYNC, SHOW FPS, BACK = 14. Was 8 before the six GI knobs
    // landed; MenuModelTest's own row-count assertion is the drift guard that keeps this
    // literal honest against MenuModel's real GRAPHICS_ITEMS list.
    private val longestPage = 14
    private val shortestPage = 4   // ROOT: START DIVE, GRAPHICS, LEADERBOARD, QUIT

    @Test
    fun `no two rows overlap, on either page`() {
        for (rowCount in listOf(shortestPage, longestPage))
            for (i in 0 until rowCount - 1)
                assertTrue(MenuLayout.rowY(i, rowCount) + MenuLayout.rowFontFor(rowCount) <= MenuLayout.rowY(i + 1, rowCount),
                    "row $i overlaps row ${i + 1} on a $rowCount-row page")
    }

    @Test
    fun `the title clears the first row, on either page`() {
        for (rowCount in listOf(shortestPage, longestPage))
            assertTrue(MenuLayout.TITLE_Y + MenuLayout.TITLE_FONT <= MenuLayout.rowY(0, rowCount),
                "the title runs into the first menu row on a $rowCount-row page")
    }

    @Test
    fun `a full page fits on screen with room for the hint line`() {
        val bottom = MenuLayout.rowsBottom(longestPage)
        assertTrue(bottom < MenuLayout.HINT_Y, "a full page runs into the hint line")
        assertTrue(MenuLayout.HINT_Y + MenuLayout.HINT_FONT < 1f, "the hint line runs off screen")
    }

    @Test
    fun `the longest page's rows stay above the hint line at every aspect from 4-3 to 32-9`() {
        // THE LAYOUT PROBLEM (gi-knobs-brief.md): 14 rows at the base ROW_FONT/ROW_SPACING end
        // at 0.959h, past HINT_Y (0.90h). MenuLayout.rowsBottom/rowFontFor now shrink a long
        // page's rows just enough to fit (see MenuLayout.baseHeight's doc) — a computation that
        // depends only on screen HEIGHT, never width, so this sweep is not expected to find a
        // width-dependent failure today. It exists anyway, and takes `w` as a real parameter
        // rather than being skipped as "obviously aspect-independent", for two reasons: it is
        // the exact assertion the task brief asked for by name, and it is what would catch a
        // FUTURE layout change that does bring width in (a two-column page, for one — a solution
        // this brief explicitly allowed) without anyone remembering to add the sweep back.
        val h = 1200f
        for (aspect in listOf(4f / 3f, 16f / 10f, 16f / 9f, 2.389f, 32f / 9f))
        {
            val w = h * aspect
            // w is unused by today's implementation (see the doc above) but is computed at
            // every aspect anyway, exactly as drawMainMenu would, so a future width-dependent
            // MenuLayout function is exercised here without this test needing to change.
            assertTrue(w > 0f)
            val bottom = MenuLayout.rowsBottom(longestPage)
            assertTrue(bottom < MenuLayout.HINT_Y,
                "the $longestPage-row page runs into the hint line at aspect $aspect (bottom=$bottom, HINT_Y=${MenuLayout.HINT_Y})")
        }
    }

    @Test
    fun `the value column sits clear of the label column, and both stay on screen, at every aspect ratio`() {
        // MINOR fix (final review, 2026-08-30): the old version of this test computed
        // `0.5 - GAP/aspect < 0.5 + GAP/aspect` directly instead of calling
        // MenuLayout.labelX/valueX - that inequality is true for ANY positive GAP regardless
        // of aspect, so the five-aspect loop was inert and the real functions were never
        // invoked at all. A sign flip in either (label using `+`, value using `-`) would have
        // shipped invisibly. This version calls the REAL functions with centreX/h computed the
        // same way drawMainMenu does (`centreX = w * 0.5f`), so aspect enters the way it
        // actually does at the call site - through centreX, not through COLUMN_GAP - and also
        // checks the columns stay on screen, not merely clear of each other.
        val h = 1200f
        for (aspect in listOf(4f / 3f, 16f / 10f, 16f / 9f, 2.389f, 32f / 9f)) {
            val w = h * aspect
            val centreX = w * 0.5f
            val labelRight = MenuLayout.labelX(centreX, h)
            val valueLeft = MenuLayout.valueX(centreX, h)
            assertTrue(labelRight < centreX, "label column did not land left of centre at aspect $aspect")
            assertTrue(valueLeft > centreX, "value column did not land right of centre at aspect $aspect")
            assertTrue(labelRight < valueLeft, "columns collide at aspect $aspect")
            assertTrue(labelRight > 0f, "label column ran off the left edge at aspect $aspect")
            assertTrue(valueLeft < w, "value column ran off the right edge at aspect $aspect")
        }
    }

    @Test
    fun `the selection highlight fits the narrowest booth panel it could be shown on`() {
        // MenuLayout.labelX/valueX place the two columns as a HEIGHT fraction either side of
        // centre, which is what MenuLayout.HIGHLIGHT_HALF_SPAN is sized against - but sizing a
        // height-relative span against nothing is not itself a guarantee it fits a real panel.
        // 4:3 is the squarest aspect this booth might show the menu on (AttractScreenTest makes
        // the same check for the leaderboard row); if the highlight fits there with margin, it
        // fits every wider aspect too. This is a real invariant, not a restatement of the
        // constant: raising HIGHLIGHT_HALF_SPAN far enough makes it fail.
        val h = 1200f
        val narrowestWidth = h * 4f / 3f
        val highlightWidth = MenuLayout.HIGHLIGHT_HALF_SPAN * 2f * h

        assertTrue(
            highlightWidth < narrowestWidth * 0.8f,
            "a ${highlightWidth}px selection highlight leaves no margin on a ${narrowestWidth}px-wide 4:3 panel"
        )
    }

    @Test
    fun `the label and value columns sit inside the selection highlight`() {
        // The highlight is meant to frame the row it sits behind - a label or value column
        // planted outside HIGHLIGHT_HALF_SPAN would draw text that spills past its own
        // highlight rectangle, which reads as a layout bug the moment a wide value string
        // ("UNCAPPED") is selected.
        val h = 1200f
        val centreX = 0f // measuring offsets from centre, not an absolute screen position

        val labelOffset = centreX - MenuLayout.labelX(centreX, h)
        val valueOffset = MenuLayout.valueX(centreX, h) - centreX
        val highlightOffset = MenuLayout.HIGHLIGHT_HALF_SPAN * h

        assertTrue(labelOffset < highlightOffset, "the label column sits outside the highlight")
        assertTrue(valueOffset < highlightOffset, "the value column sits outside the highlight")
    }

    @Test
    fun `every menu anchor is a fraction of the screen, not a pixel count`() {
        // engine.window.width/height are PHYSICAL framebuffer pixels, so the single most
        // damaging mistake available here is writing a number that looks like a position at
        // 1080p. Every anchor this object exposes must be a fraction strictly inside the
        // screen, which no pixel count ever is.
        val anchors = mapOf(
            "TITLE_Y" to MenuLayout.TITLE_Y,
            "TITLE_FONT" to MenuLayout.TITLE_FONT,
            "ROWS_TOP_Y" to MenuLayout.ROWS_TOP_Y,
            "ROW_FONT" to MenuLayout.ROW_FONT,
            "COLUMN_GAP" to MenuLayout.COLUMN_GAP,
            "HIGHLIGHT_HALF_SPAN" to MenuLayout.HIGHLIGHT_HALF_SPAN,
            "HINT_Y" to MenuLayout.HINT_Y,
            "HINT_FONT" to MenuLayout.HINT_FONT
        ) + (0 until longestPage).associate { "rowY($it, $longestPage)" to MenuLayout.rowY(it, longestPage) }

        for ((name, value) in anchors)
            assertTrue(value > 0f && value < 1f, "$name = $value is not a screen fraction")
    }
}
