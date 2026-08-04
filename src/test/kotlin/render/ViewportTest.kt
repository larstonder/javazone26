package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the HiDPI bug: on a Retina display `engine.window.height` reports
 * 1800 rather than the 900 declared in application.cfg, which halved the apparent scale of
 * everything and shrank the diver to an invisible dot.
 */
class ViewportTest
{
    private val logical = 900f    // what application.cfg declares
    private val retina = 1800f    // what engine.window.height actually reports on a Retina Mac

    @Test
    fun `the diver sits at the same screen fraction on every display`() {
        val diverDepth = 42f

        val yLogical = Viewport.screenY(diverDepth, diverDepth, logical)
        val yRetina = Viewport.screenY(diverDepth, diverDepth, retina)

        assertEquals(Viewport.DIVER_SCREEN_FRACTION, yLogical / logical, 0.001f)
        assertEquals(Viewport.DIVER_SCREEN_FRACTION, yRetina / retina, 0.001f)
    }

    @Test
    fun `a world point maps to the same screen fraction on every display`() {
        val diverDepth = 30f
        val pearlDepth = 55f

        val fractionLogical = Viewport.screenY(pearlDepth, diverDepth, logical) / logical
        val fractionRetina = Viewport.screenY(pearlDepth, diverDepth, retina) / retina

        assertEquals(fractionLogical, fractionRetina, 0.0001f,
            "doubling the framebuffer must not move content relative to the screen")
    }

    @Test
    fun `the diver occupies the same screen fraction on every display`() {
        val sizeLogical = Viewport.DIVER_SIZE_METRES * Viewport.pixelsPerMetre(logical)
        val sizeRetina = Viewport.DIVER_SIZE_METRES * Viewport.pixelsPerMetre(retina)

        assertEquals(sizeLogical / logical, sizeRetina / retina, 0.0001f,
            "the diver must not shrink on a HiDPI display — this is the reported bug")
    }

    @Test
    fun `the diver is large enough to see`() {
        // The bug shipped a 14px diver into an 1800px-tall framebuffer: 0.8% of screen height.
        val fraction = Viewport.DIVER_SIZE_METRES * Viewport.pixelsPerMetre(retina) / retina
        assertTrue(fraction > 0.02f, "diver covers only ${fraction * 100}% of screen height")
    }

    @Test
    fun `only part of the water column is visible so descending scrolls`() {
        // MAX_DEPTH is 160m. If the whole column fits on screen the camera never moves
        // and the sense of descent is lost entirely.
        assertTrue(
            Viewport.VISIBLE_DEPTH_METRES < dive.Tuning.MAX_DEPTH * 0.6f,
            "visible depth ${Viewport.VISIBLE_DEPTH_METRES}m is too much of the ${dive.Tuning.MAX_DEPTH}m column"
        )
    }

    @Test
    fun `x is centred and scales with the display`() {
        assertEquals(600f, Viewport.screenX(0f, 1200f, logical), 0.001f)
        assertEquals(1200f, Viewport.screenX(0f, 2400f, retina), 0.001f)

        val offsetLogical = Viewport.screenX(10f, 1200f, logical) - 600f
        val offsetRetina = Viewport.screenX(10f, 2400f, retina) - 1200f
        assertEquals(offsetLogical * 2f, offsetRetina, 0.001f)
    }

    @Test
    fun `camera keeps the diver above the top edge of visible water`() {
        val diverDepth = 100f
        val top = Viewport.cameraDepth(diverDepth)
        assertTrue(top < diverDepth, "camera top must be shallower than the diver")
        assertEquals(Viewport.VISIBLE_DEPTH_METRES * Viewport.DIVER_SCREEN_FRACTION, diverDepth - top, 0.001f)
    }
}
