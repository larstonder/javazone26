package render

import kotlin.math.exp

/**
 * Smoothed vertical camera. Pure Kotlin, no engine imports, so it is unit-testable.
 *
 * The camera eases toward the diver rather than locking to them. A hard-locked camera makes
 * fast movement feel static, because the diver never moves relative to the frame no matter
 * how hard you kick. With easing, a hard descent visibly outruns the camera and the diver
 * drifts down the screen, then settles back when you stop — which is where the sense of
 * speed and weight comes from.
 *
 * The lag is bounded: [Framing.DIVER_MIN_FRACTION] and [Framing.DIVER_MAX_FRACTION] cap how
 * far the diver may drift, so no amount of velocity can push them off-screen.
 */
class DiveCamera
{
    var depth = 0f
        private set

    /** Jump straight to the ideal position — used at the start of a run and after a reset. */
    fun snapTo(diverDepth: Float)
    {
        depth = Framing.targetCameraDepth(diverDepth)
    }

    fun update(dt: Float, diverDepth: Float)
    {
        val target = Framing.targetCameraDepth(diverDepth)

        // Exponential smoothing. Using 1 - e^(-k*dt) rather than a fixed alpha makes the
        // easing frame-rate independent: the same wall-clock time produces the same motion
        // whether the game is running at 60 or 240 fps.
        val t = 1f - exp(-Framing.CAMERA_SMOOTHING * dt.coerceAtLeast(0f))
        depth += (target - depth) * t

        clampSoDiverStaysVisible(diverDepth)
    }

    private fun clampSoDiverStaysVisible(diverDepth: Float)
    {
        val lowest = diverDepth - Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_MAX_FRACTION
        val highest = diverDepth - Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_MIN_FRACTION
        depth = depth.coerceIn(lowest, highest)
    }
}
