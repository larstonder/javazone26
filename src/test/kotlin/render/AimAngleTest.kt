package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `AimAngle` drives the diver's flashlight beam heading. The wraparound maths is exactly the
 * kind of thing that looks right for 359 of every 360 degrees and then visibly snaps the
 * beam all the way around once — so it gets tested at the wrap, not just in the interior.
 */
class AimAngleTest
{
    @Test
    fun `heading points along the velocity — right, down, left, up`() {
        assertEquals(0f, AimAngle.headingDegrees(1f, 0f), 0.01f)
        assertEquals(90f, AimAngle.headingDegrees(0f, 1f), 0.01f)
        assertEquals(180f, AimAngle.headingDegrees(-1f, 0f), 0.01f)
        assertEquals(-90f, AimAngle.headingDegrees(0f, -1f), 0.01f)
    }

    @Test
    fun `wrap folds any angle into 0 until 360`() {
        assertEquals(350f, AimAngle.wrap(-10f), 0.01f)
        assertEquals(10f, AimAngle.wrap(370f), 0.01f)
        assertEquals(0f, AimAngle.wrap(360f), 0.01f)
        assertEquals(180f, AimAngle.wrap(180f), 0.01f)
    }

    @Test
    fun `shortest difference takes the short way across the wrap, not the long way around`() {
        // 179 -> -179 is a 2-degree turn across the wrap, not a 358-degree turn the other way.
        val diff = AimAngle.shortestDifference(current = 179f, target = -179f)
        assertEquals(2f, diff, 0.01f)
    }

    @Test
    fun `shortest difference is symmetric in sign for the reverse turn`() {
        val diff = AimAngle.shortestDifference(current = -179f, target = 179f)
        assertEquals(-2f, diff, 0.01f)
    }

    @Test
    fun `smoothing near the wrap moves the short way, not the long way around`() {
        // Without wrap-aware smoothing, easing from 179 toward -179 would crawl backwards
        // through 90, 0, -90 instead of stepping straight across 180/-180.
        val next = AimAngle.smooth(current = 179f, target = -179f, dt = 1f / 60f, rate = 6f)

        // Moving the short way means the result is just past 179 (wrapped), not near 90.
        assertTrue(next > 179f || next < -170f, "expected a small step across the wrap, got $next")
    }

    @Test
    fun `smoothing converges on the target when given enough time`() {
        var angle = 0f
        repeat(300) { angle = AimAngle.smooth(angle, target = 200f, dt = 1f / 60f, rate = 6f) }
        assertEquals(200f, angle, 0.5f)
    }

    @Test
    fun `smoothing does not arrive instantly — that is the whole point of easing`() {
        val next = AimAngle.smooth(current = 0f, target = 90f, dt = 1f / 60f, rate = 6f)
        assertTrue(next > 0f, "must move toward the target")
        assertTrue(next < 90f, "must not snap straight to the target in one frame")
    }

    @Test
    fun `smoothing is frame-rate independent, like DiveCamera's easing`() {
        var coarse = 0f
        var fine = 0f
        repeat(6) { coarse = AimAngle.smooth(coarse, target = 45f, dt = 1f / 60f, rate = 6f) }
        repeat(24) { fine = AimAngle.smooth(fine, target = 45f, dt = 1f / 240f, rate = 6f) }
        assertEquals(coarse, fine, 0.1f)
    }

    @Test
    fun `a zero timestep does not move the angle`() {
        val next = AimAngle.smooth(current = 30f, target = 90f, dt = 0f, rate = 6f)
        assertEquals(30f, next, 0.0001f)
    }
}
