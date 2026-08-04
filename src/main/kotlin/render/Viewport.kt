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

    /** The diver is pinned this far down the screen; the camera follows. */
    const val DIVER_SCREEN_FRACTION = 0.4f

    const val DIVER_SIZE_METRES = 3f
    const val PEARL_SIZE_METRES = 1.2f

    fun pixelsPerMetre(screenHeight: Float) = screenHeight / VISIBLE_DEPTH_METRES

    /** Depth at the top edge of the screen, given where the diver is. */
    fun cameraDepth(diverDepth: Float) = diverDepth - VISIBLE_DEPTH_METRES * DIVER_SCREEN_FRACTION

    fun screenY(depth: Float, diverDepth: Float, screenHeight: Float) =
        (depth - cameraDepth(diverDepth)) * pixelsPerMetre(screenHeight)

    fun screenX(x: Float, screenWidth: Float, screenHeight: Float) =
        screenWidth * 0.5f + x * pixelsPerMetre(screenHeight)
}
