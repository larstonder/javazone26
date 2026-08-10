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

    /**
     * The diver's HEIGHT, in metres — renamed from `DIVER_SIZE_METRES` when the white square gave
     * way to the sprite, because the diver is no longer square and "size" no longer names anything.
     * The art is 1:3.05, so the width follows from it ([DiverSprite.widthForHeight]) rather than
     * being a second number that could drift.
     *
     * DOUBLED FROM 3 m BY DESIGN DECISION, not by measurement — the 3 m diver read as too small on
     * screen. 3 m was inherited from the placeholder square and is what the sprite bake assumed
     * (`2026-08-07-diver-spritesheet-bake-design` §3 reasons from ~5% of screen height); 6 m is
     * 10% of it, and `VISIBLE_DEPTH_METRES` is unchanged, so the amount of water a player can see
     * and plan against is exactly as before.
     *
     * The bake does not need redoing, and the change moves the sampling the right way. A 384-texel
     * cell drawn at 5% of an 1800 px framebuffer is a 4.3x minification, and the sheets carry
     * `maxMipLevels = 1` — deliberately, so mip generation cannot average across cell boundaries
     * and smear adjacent frames together — so there is no mip chain to absorb it and the high
     * frequencies in the normal map in particular would alias. At 10% it is 2.1x.
     *
     * THINGS SIT AT A FIXED DISTANCE FROM THE DIVER AND NONE OF THEM SCALES WITH THIS.
     * Changing it means re-checking each: `Hud.AIR_RING_RADIUS_METRES` (the bubble ring orbits
     * him), `Hud.HELD_OFFSET_METRES` (the held count hangs below him), and
     * `Tuning.PEARL_PICKUP_RADIUS` / `AIR_POCKET_PICKUP_RADIUS`, which are GAMEPLAY and must not
     * be touched from here. See `DiverSpriteTest`'s ring-clearance case for the first, and this
     * task's report for the pickup-radius consequence.
     *
     * `DiveLighting.DIVER_LIGHT_SIZE_METRES` USED TO BE TIED TO THIS AND DELIBERATELY IS NOT ANY
     * MORE — do not re-tie it. A light's emitter quad is a region that rasterises into the scene,
     * so a body-sized emitter is seen as a patch of light rather than as a source; that coupling
     * is what put a hard-edged rectangle around the diver. It is a torch head now, at its own
     * 1.2 m, and `DiveLightingTest` fails the build if it ever grows back toward this constant.
     */
    const val DIVER_HEIGHT_METRES = 6f
    const val PEARL_SIZE_METRES = 1.2f
    const val AIR_POCKET_SIZE_METRES = 2.4f

    /** Where the camera would sit if it tracked the diver exactly. */
    fun targetCameraDepth(diverDepth: Float) = diverDepth - VISIBLE_DEPTH_METRES * DIVER_SCREEN_FRACTION
}
