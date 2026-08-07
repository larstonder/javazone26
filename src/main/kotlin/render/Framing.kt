package render

/**
 * How much water is on screen, and where the diver sits in the frame. Pure Kotlin, no engine
 * imports, so it is unit-testable.
 *
 * FRAMING, NOT TRANSFORMS. This object used to be `Viewport` and used to own the world -> screen
 * transform as well (`screenX`, `screenY`, `depthAt`, `pixelsPerMetre`, `screenFraction`). It no
 * longer does: the world is drawn in METRES through `engine.gfx.mainCamera`, and the only place
 * that decides how a metre becomes a pixel is [CameraRig]. What is left here is the set of
 * numbers that say how much of the column a player can see and where in the frame they are kept
 * — gameplay quantities, none of which mention a pixel.
 *
 * It was RENAMED rather than gutted in place so that a stale "`Viewport.screenX` exists"
 * mental model could not survive the compiler: every call site had to be visited.
 *
 * WHAT HAPPENED TO THE HiDPI STORY THIS FILE USED TO TELL. `engine.window.height` returns
 * PHYSICAL framebuffer pixels — 1800 on a Retina Mac from the 900 declared in application.cfg —
 * and the original renderer's hardcoded `PIXELS_PER_METRE = 5` therefore drew everything at half
 * the intended relative scale. That is now the engine's problem rather than ours: it re-issues
 * `ortho(0, w, h, 0)` for every surface camera on every window change (GraphicsImpl.kt:95), and
 * [CameraRig] derives its scale from `mainSurface.config.height` each fixed tick. Sizes below are
 * in METRES, so nothing here can shrink on a HiDPI panel — but the convention that produced the
 * bug still binds everything else in this codebase: express a size as a fraction of screen
 * HEIGHT, never width and never a pixel count. The booth display's aspect ratio is not known in
 * advance.
 */
object Framing
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
    const val AIR_POCKET_SIZE_METRES = 2.4f

    /** Where the camera would sit if it tracked the diver exactly. */
    fun targetCameraDepth(diverDepth: Float) = diverDepth - VISIBLE_DEPTH_METRES * DIVER_SCREEN_FRACTION
}
