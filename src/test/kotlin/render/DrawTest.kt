package render

import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.api.DefaultCamera
import no.njoh.pulseengine.core.graphics.api.DefaultCamera.ProjectionType.ORTHOGRAPHIC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `textOutlineOffset` is the one pure piece of [drawTextWithOutline] — the rest issues real
 * `drawText`/`setDrawColor` calls against a `Surface` and needs a live GL context to verify.
 * What matters here is resolution independence: `engine.window.width/height` are PHYSICAL
 * framebuffer pixels (2400x1800 on a Retina Mac, not the 1200x900 in application.cfg), so a
 * fixed-pixel outline offset that looked right at 1200x900 would be sub-pixel — effectively
 * invisible — at 4K. The offset must scale with screen height instead.
 */
class DrawTest
{
    @Test
    fun `offset scales proportionally with screen height`() {
        val at900 = textOutlineOffset(900f)
        val at1800 = textOutlineOffset(1800f)

        // Doubling the physical resolution must double the offset — a fixed pixel count
        // would instead stay constant (and read as vanishing on the larger screen).
        assertEquals(at900 * 2f, at1800, 0.0001f)
    }

    @Test
    fun `offset reproduces the reference's 2px look at 1080p`() {
        // The reference (caesars-salads) hardcodes a 2px offset at its target resolution
        // (1920x1080). Our fraction-of-height approach should reproduce that exact value
        // at that exact height.
        assertEquals(2f, textOutlineOffset(1080f), 0.01f)
    }

    @Test
    fun `offset is zero for zero height and positive for any positive height`() {
        assertEquals(0f, textOutlineOffset(0f), 0.0001f)
        assertTrue(textOutlineOffset(1800f) > 0f)
    }

    // ---- showsSquare: the centre-in / top-left-out culling conversion ---------------------
    //
    // `Camera.isInView` is the engine's, and it is not what is under test here — what is under
    // test is our conversion into it, which is the only part that can be wrong on our side and
    // the only part that is invisible everywhere except at the frame edge.
    //
    // A `DefaultCamera` is a plain JOML object with no GL in it, and `isInView` reads nothing
    // but `topLeftWorldPosition`/`bottomRightWorldPosition` — both public and settable. So the
    // visible world rect can be posed directly, without a view matrix, a surface or a window.
    // A 106 x 60 metre rect is what a 16:9 booth panel actually shows (60 m of depth by
    // CameraRig's scale, ~106 m across at 16:9); the numbers below are that rect, not a
    // resolution.

    private fun cameraShowing(left: Float, top: Float, right: Float, bottom: Float): Camera =
        DefaultCamera(ORTHOGRAPHIC).apply {
            topLeftWorldPosition.set(left, top)
            bottomRightWorldPosition.set(right, bottom)
        }

    @Test
    fun `a square straddling the frame edge is kept, because it is its rect that is tested and not its centre`()
    {
        val cam = cameraShowing(-53f, 100f, 53f, 160f)

        // Centre 1 m BELOW the bottom edge, with a 4 m square: one metre of it is still on
        // screen. The pixel-row checks this replaces compared the centre alone and would have
        // popped it out here — half a square early, every time, at every edge.
        assertTrue(cam.showsSquare(0f, 161f, 4f), "a square overlapping the bottom edge must be drawn")
        assertTrue(cam.showsSquare(0f, 99f, 4f), "a square overlapping the top edge must be drawn")
        assertTrue(cam.showsSquare(-54f, 130f, 4f), "a square overlapping the left edge must be drawn")

        // And once it is genuinely clear of the edge by more than its own half-size, it goes.
        assertFalse(cam.showsSquare(0f, 163f, 4f), "a square fully below the frame must be culled")
    }

    @Test
    fun `a square far outside the visible half-width is culled, which the old row checks never did`()
    {
        val cam = cameraShowing(-53f, 100f, 53f, 160f)

        // Same depth as the middle of the frame — vertically it is dead centre. Only the x test
        // can reject it, and the checks that went away with the pixel coordinates had none.
        assertFalse(cam.showsSquare(-90f, 130f, 4f), "a square off the left of frame must be culled")
        assertFalse(cam.showsSquare(90f, 130f, 4f), "a square off the right of frame must be culled")
        assertTrue(cam.showsSquare(0f, 130f, 4f), "a square in the middle of frame must be drawn")
    }

    @Test
    fun `padding extends the accepted rect outward, which is what keeps an enlarged light quad alive`()
    {
        val cam = cameraShowing(-53f, 100f, 53f, 160f)

        // 3 m light, centre 4 m past the bottom edge: its own rect clears the frame entirely,
        // so with no padding it is culled. GI's shader can still grow that quad to three times
        // its size (scene.vert's upscaleSmallSources), so DiveLighting pads by one full light
        // size — and with that padding it survives. If padding were ignored, the second
        // assertion would read the same as the first.
        assertFalse(cam.showsSquare(0f, 164f, 3f), "unpadded, a light clear of the frame is culled")
        assertTrue(cam.showsSquare(0f, 164f, 3f, padding = 3f), "padded by its own size, it survives")
    }
}
