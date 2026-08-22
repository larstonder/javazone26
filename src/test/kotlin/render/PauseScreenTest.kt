package render

import PauseLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry for the pause / cabinet-menu screen.
 *
 * WHY THIS EXISTS: `PauseLayout`'s own KDoc said its relationships were checked "see
 * PauseScreenTest", and no such file existed — the layout was referenced by no test at all,
 * unlike the attract screen's ten. A doc citing a test that does not exist is worse than an
 * acknowledged gap, because it stops the next reader looking.
 *
 * Same convention as `AttractScreenTest`'s layout half: text grows DOWNWARD from its anchor,
 * so a block occupies `y .. y + fontSize`.
 */
class PauseScreenTest
{
    @Test
    fun `every anchor is a screen fraction strictly inside the screen`()
    {
        val anchors = listOf(
            "title" to PauseLayout.TITLE_Y,
            "resume" to PauseLayout.RESUME_Y,
            "exit" to PauseLayout.EXIT_Y,
            "bar" to PauseLayout.BAR_Y
        )
        for ((name, y) in anchors)
            assertTrue(y > 0f && y < 1f, "$name anchor $y is not a fraction in (0,1)")

        assertTrue(PauseLayout.SCRIM_ALPHA > 0f && PauseLayout.SCRIM_ALPHA <= 1f)
        assertTrue(PauseLayout.TITLE_FONT > 0f && PauseLayout.HINT_FONT > 0f)
        assertTrue(PauseLayout.BAR_HEIGHT > 0f && PauseLayout.BAR_HALF_SPAN > 0f)
    }

    @Test
    fun `the four elements do not overlap, top to bottom`()
    {
        assertTrue(
            PauseLayout.TITLE_Y + PauseLayout.TITLE_FONT <= PauseLayout.RESUME_Y,
            "the title runs into the resume hint"
        )
        assertTrue(
            PauseLayout.RESUME_Y + PauseLayout.HINT_FONT <= PauseLayout.EXIT_Y,
            "the resume hint runs into the exit hint"
        )
        assertTrue(
            PauseLayout.EXIT_Y + PauseLayout.HINT_FONT <= PauseLayout.BAR_Y,
            "the exit hint runs into the progress bar"
        )
        assertTrue(
            PauseLayout.BAR_Y + PauseLayout.BAR_HEIGHT < 1f,
            "the progress bar runs off the bottom"
        )
    }

    @Test
    fun `the exit bar's fill is bounded by its own track`()
    {
        val h = 1000f
        val track = PauseLayout.barTrackWidth(h)
        assertEquals(0f, PauseLayout.barFillWidth(0f, h), 1e-4f, "an untouched key must show nothing")
        assertEquals(track, PauseLayout.barFillWidth(1f, h), 1e-4f, "a completed hold must fill the track")
        assertTrue(PauseLayout.barFillWidth(0.5f, h) < track, "a half hold must not fill the track")
        assertTrue(PauseLayout.barFillWidth(0.5f, h) > 0f, "a half hold must show something")
    }

    @Test
    fun `the bar is centred on the same axis as the text above it`()
    {
        // The hints are drawn at centreX with xOrigin = 0.5; the bar is drawn from a left
        // edge. If these disagree the screen reads as broken even though every element is
        // individually on screen.
        val h = 1000f
        val centreX = 800f
        val left = PauseLayout.barX(centreX, h)
        val centreOfBar = left + PauseLayout.barTrackWidth(h) * 0.5f
        assertEquals(centreX, centreOfBar, 1e-3f, "the exit bar is not centred under its label")
    }

    @Test
    fun `the bar track spans twice its half-span`()
    {
        val h = 1000f
        assertEquals(
            h * PauseLayout.BAR_HALF_SPAN * 2f,
            PauseLayout.barTrackWidth(h),
            1e-3f
        )
    }
}
