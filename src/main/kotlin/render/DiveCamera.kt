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
    fun snapTo(diverDepth: Float, visibleDepthMetres: Float)
    {
        depth = Framing.targetCameraDepth(diverDepth)
        clampToSandBankFloor(visibleDepthMetres)
        clampSoDiverStaysVisible(diverDepth)
    }

    fun update(dt: Float, diverDepth: Float, visibleDepthMetres: Float)
    {
        val target = Framing.targetCameraDepth(diverDepth)

        // Exponential smoothing. Using 1 - e^(-k*dt) rather than a fixed alpha makes the
        // easing frame-rate independent: the same wall-clock time produces the same motion
        // whether the game is running at 60 or 240 fps.
        val t = 1f - exp(-Framing.CAMERA_SMOOTHING * dt.coerceAtLeast(0f))
        depth += (target - depth) * t

        clampToSandBankFloor(visibleDepthMetres)
        clampSoDiverStaysVisible(diverDepth)
    }

    /**
     * Stop the frame's bottom edge at [Framing.SEA_FLOOR_DEPTH] — the sandbank's bottom edge —
     * so the last stretch of the dive settles onto the seabed instead of scrolling into a void.
     * Without it the frame paints down to 211 m of nothing.
     *
     * ORDER MATTERS AND IS FIXED: this runs BEFORE [clampSoDiverStaysVisible], so that where the
     * two disagree the DIVER'S VISIBILITY WINS. Losing the diver off the bottom of the frame is
     * a broken game; showing a metre of void below the sand is a blemish. They cannot in fact
     * disagree at any aspect this game can be run at — the disagreement needs `V > 69.93 m` and
     * `V <= 60` always, by construction of `CameraRig.pixelsPerMetre`'s `max` — but the ordering
     * is what makes that safe rather than lucky. `DiveCameraTest` constructs the disagreement
     * and asserts it.
     *
     * The guarantee, over the whole domain and with no case split: `coerceIn(lo, hi)` returns at
     * most `max(lo, hi_side_input)`, so `cameraDepth <= max(d - 45, B - V)` and
     * `frameBottom <= max(d - 45 + V, B)`, where `d - 45 + V <= 160 - 45 + 60 = 175 m` against
     * `B` = 184.930 m. Even in the hypothetical where the visibility clamp overrides the floor
     * entirely, the frame stops 9.93 m short of the sand's bottom edge.
     *
     * THE FLOOR DEPTH IS READ FROM [Framing], NOT FROM `SandBank`, and that is deliberate: this
     * class is pure Kotlin with no engine imports, which is what makes the whole camera
     * unit-testable without a GL context. `SandBank` is an object full of engine `Texture`
     * descriptors. See [Framing.SEA_FLOOR_DEPTH].
     */
    private fun clampToSandBankFloor(visibleDepthMetres: Float)
    {
        // A minimized window reports 0x0 through GLFW, and CameraRig.visibleDepthMetres is then
        // 0f / 0f = NaN. `minOf` propagates NaN, `depth` becomes NaN, and every later
        // `depth += (target - depth) * t` keeps it NaN forever: the camera is permanently
        // poisoned with no recovery short of a snapTo. Whether the engine actually ticks a
        // minimized window is UNVERIFIED; the guard is two float comparisons per fixed tick
        // either way. CameraInvariants already bails out of exactly this shape, for exactly
        // this reason, and says so.
        if (!visibleDepthMetres.isFinite()) return
        depth = minOf(depth, Framing.SEA_FLOOR_DEPTH - visibleDepthMetres)
    }

    private fun clampSoDiverStaysVisible(diverDepth: Float)
    {
        val lowest = diverDepth - Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_MAX_FRACTION
        val highest = diverDepth - Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_MIN_FRACTION
        depth = depth.coerceIn(lowest, highest)
    }
}
