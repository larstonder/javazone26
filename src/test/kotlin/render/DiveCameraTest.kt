package render

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiveCameraTest
{
    /** The clamp lands exactly on the boundary, so allow for float rounding. */
    private val EPS = 1e-5f

    /**
     * Where in the frame the diver sits, 0 at the top edge and 1 at the bottom.
     *
     * This was `Viewport.screenFraction(depth, cameraDepth)`, which went with the rest of that
     * object's transform when the world moved into metres — [Framing] deliberately owns no
     * mapping at all now, not even a resolution-free one, so that nothing can grow back into a
     * second coordinate authority beside [CameraRig]. Inlined here rather than reinstated: it is
     * one subtraction, only this file wants it, and every assertion below is unchanged.
     */
    private fun fractionOf(cam: DiveCamera, diverDepth: Float) =
        (diverDepth - cam.depth) / Framing.VISIBLE_DEPTH_METRES

    @Test
    fun `snapTo puts the diver exactly at the target screen fraction`() {
        val cam = DiveCamera()
        cam.snapTo(50f)
        assertEquals(Framing.DIVER_SCREEN_FRACTION, fractionOf(cam, 50f), 0.0001f)
    }

    @Test
    fun `the camera lags rather than tracking exactly`() {
        val cam = DiveCamera()
        cam.snapTo(0f)
        val before = cam.depth

        cam.update(dt = 1f / 60f, diverDepth = 20f)

        assertTrue(cam.depth > before, "camera must move toward the diver")
        assertTrue(
            cam.depth < Framing.targetCameraDepth(20f),
            "camera must NOT arrive instantly — that is the whole point of smoothing"
        )
    }

    @Test
    fun `the camera converges on the target when the diver stops`() {
        val cam = DiveCamera()
        cam.snapTo(0f)
        repeat(300) { cam.update(1f / 60f, diverDepth = 40f) }
        assertEquals(Framing.targetCameraDepth(40f), cam.depth, 0.01f)
    }

    @Test
    fun `easing is frame-rate independent`() {
        // Start inside the clamp band so the comparison measures easing, not clamping.
        val coarse = DiveCamera().apply { snapTo(50f) }
        val fine = DiveCamera().apply { snapTo(50f) }

        // Same wall-clock duration, very different step sizes.
        repeat(6) { coarse.update(1f / 60f, diverDepth = 55f) }
        repeat(24) { fine.update(1f / 240f, diverDepth = 55f) }

        assertEquals(coarse.depth, fine.depth, 0.05f,
            "60fps and 240fps must produce the same camera motion over the same time")
    }

    @Test
    fun `a diver descending fast drifts below the target line`() {
        val cam = DiveCamera()
        cam.snapTo(0f)

        // Kick descent: roughly 18 m/s for half a second.
        var diverDepth = 0f
        repeat(30) {
            diverDepth += 18f * (1f / 60f)
            cam.update(1f / 60f, diverDepth)
        }

        assertTrue(
            fractionOf(cam, diverDepth) > Framing.DIVER_SCREEN_FRACTION,
            "outrunning the camera should push the diver down the screen"
        )
    }

    @Test
    fun `the diver can never be pushed off screen by camera lag`() {
        val cam = DiveCamera()
        cam.snapTo(0f)

        // Absurd velocity, far beyond anything the game can produce.
        var diverDepth = 0f
        repeat(120) {
            diverDepth += 200f * (1f / 60f)
            cam.update(1f / 60f, diverDepth)
            val f = fractionOf(cam, diverDepth)
            assertTrue(
                f in (Framing.DIVER_MIN_FRACTION - EPS)..(Framing.DIVER_MAX_FRACTION + EPS),
                "diver at screen fraction $f escaped the visible band"
            )
        }
    }

    @Test
    fun `a fast ascent also keeps the diver on screen`() {
        val cam = DiveCamera()
        cam.snapTo(150f)

        var diverDepth = 150f
        repeat(120) {
            diverDepth -= 200f * (1f / 60f)
            cam.update(1f / 60f, diverDepth)
            val f = fractionOf(cam, diverDepth)
            assertTrue(
                f in (Framing.DIVER_MIN_FRACTION - EPS)..(Framing.DIVER_MAX_FRACTION + EPS),
                "diver at screen fraction $f escaped the visible band while ascending"
            )
        }
    }

    @Test
    fun `a zero or negative timestep does not move or corrupt the camera`() {
        val cam = DiveCamera()

        // Settle the camera off-target for a diver at 30f (still inside the visible clamp
        // band, so the clamp itself is a no-op here and cannot mask what dt <= 0 does).
        // snapTo(30f) would put depth exactly on target, making (target - depth) zero and
        // the test blind to any t no matter how corrupt — see the mutation this replaced.
        cam.snapTo(10f)
        val before = cam.depth

        cam.update(0f, 30f)
        cam.update(-1f, 30f)

        assertTrue(abs(cam.depth - before) < 0.0001f, "camera must be stable at dt <= 0")
    }
}
