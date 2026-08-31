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
    private val longestPage = 8   // the GRAPHICS page: 7 settings plus BACK

    @Test
    fun `no two rows overlap`() {
        for (i in 0 until longestPage - 1)
            assertTrue(MenuLayout.rowY(i) + MenuLayout.ROW_FONT <= MenuLayout.rowY(i + 1),
                "row $i overlaps row ${i + 1}")
    }

    @Test
    fun `the title clears the first row`() {
        assertTrue(MenuLayout.TITLE_Y + MenuLayout.TITLE_FONT <= MenuLayout.rowY(0),
            "the title runs into the first menu row")
    }

    @Test
    fun `a full page fits on screen with room for the hint line`() {
        val bottom = MenuLayout.rowY(longestPage - 1) + MenuLayout.ROW_FONT
        assertTrue(bottom < MenuLayout.HINT_Y, "a full page runs into the hint line")
        assertTrue(MenuLayout.HINT_Y + MenuLayout.HINT_FONT < 1f, "the hint line runs off screen")
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
        ) + (0 until longestPage).associate { "rowY($it)" to MenuLayout.rowY(it) }

        for ((name, value) in anchors)
            assertTrue(value > 0f && value < 1f, "$name = $value is not a screen fraction")
    }
}
