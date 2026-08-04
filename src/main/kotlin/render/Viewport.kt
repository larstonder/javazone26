package render

/**
 * Pure, resolution-independent viewport maths. No engine imports, so it is unit-testable.
 *
 * WHY THIS EXISTS: `engine.window.height` returns PHYSICAL framebuffer pixels, which on a
 * Retina display is 2x the logical size declared in application.cfg (1200x900 -> 2400x1800).
 * The original renderer hardcoded `PIXELS_PER_METRE = 5` and a 14px diver, which assumed a
 * 900px-tall screen — so on a HiDPI display everything rendered at half the intended relative
 * scale and the diver became a 7-point dot.
 *
 * Deriving pixels-per-metre from the actual surface height keeps the same amount of water on
 * screen on every display, and expressing sizes in metres keeps the diver the same visual size.
 */
object Viewport
{
    /** How much of the water column is visible at once. Also sets the descent scroll rate. */
    const val VISIBLE_DEPTH_METRES = 60f

    /** Where the camera tries to keep the diver vertically. */
    const val DIVER_SCREEN_FRACTION = 0.4f

    /**
     * Hard bounds on where the diver may sit once camera lag is taken into account.
     * The easing is allowed to fall behind, but never far enough to lose the diver.
     */
    const val DIVER_MIN_FRACTION = 0.15f
    const val DIVER_MAX_FRACTION = 0.75f

    /**
     * Exponential easing rate, per second. Higher is snappier.
     * 4.0 gives a time constant of 0.25s — the camera covers ~63% of the remaining
     * distance every quarter second, which reads as smooth without feeling floaty.
     */
    const val CAMERA_SMOOTHING = 4f

    const val DIVER_SIZE_METRES = 3f
    const val PEARL_SIZE_METRES = 1.2f

    fun pixelsPerMetre(screenHeight: Float) = screenHeight / VISIBLE_DEPTH_METRES

    /** Where the camera would sit if it tracked the diver exactly. */
    fun targetCameraDepth(diverDepth: Float) = diverDepth - VISIBLE_DEPTH_METRES * DIVER_SCREEN_FRACTION

    /** Screen fraction (0 = top, 1 = bottom) a given depth occupies for a given camera. */
    fun screenFraction(depth: Float, cameraDepth: Float) = (depth - cameraDepth) / VISIBLE_DEPTH_METRES

    fun screenY(depth: Float, cameraDepth: Float, screenHeight: Float) =
        (depth - cameraDepth) * pixelsPerMetre(screenHeight)

    fun screenX(x: Float, screenWidth: Float, screenHeight: Float) =
        screenWidth * 0.5f + x * pixelsPerMetre(screenHeight)
}
