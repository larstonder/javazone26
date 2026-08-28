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
    /**
     * How much of the water column is visible at once, on any display AT OR BELOW the design
     * aspect. Also sets the descent scroll rate. See [VISIBLE_WIDTH_METRES] for what happens
     * above it, and why that had to become a maximum rather than a promise.
     */
    const val VISIBLE_DEPTH_METRES = 60f

    /**
     * The MOST water that may be visible horizontally: the play column plus exactly one cliff at
     * each side, 98.516 m.
     *
     * ## WHY THERE IS A CAP AT ALL
     *
     * [CameraRig] used to scale on HEIGHT alone, so a wider display simply revealed more water
     * sideways. Outside `Tuning.COLUMN_HALF_WIDTH` that water is rock, and the cliff art is one
     * 11.99 m tile — so a wide panel showed the tile repeated. `RockFace.bodyDiffuse` is a
     * mirror-doubled crop, which makes the repeat seamless but does not make it invisible: at the
     * owner's 2.389 the body needs two tiles a side, i.e. FOUR reflections of the same crop on
     * screen, and it reads as wallpaper. His words: *"for each aspect ratio we should only ever
     * see exactly one rock cliff on each end of the screen"*.
     *
     * ## WHY THIS NUMBER — IT IS THE EDGE ART'S OUTWARD END, NOT ITS QUAD'S
     *
     * `2 * RockFace.BODY_INNER_HALF_WIDTH` = 98.516 m — the outward end of the edge art's OPAQUE
     * region, which is one [RockFace.BORDER_TEXEL_COLUMNS] inside its outward end.
     *
     * That last texel matters. It is the transparent wrap border the bake writes to stop
     * `texture.frag`'s `fract` extrapolation sampling solid stone at a quad edge. Capping on
     * `CREST_OUTER_HALF_WIDTH` instead put the frame edge exactly ON it, and the extreme pixel
     * column came back transparent at every aspect — the hairline again, inverted. Measured: alpha
     * 0 at the frame edge at 16:9, 2.389 and 32:9. One texel further in, the frame edge lands on
     * the last opaque column of the art, and the body's span is exactly zero so no body quad is
     * submitted at all.
     *
     * The first version of this cap was `2 * (COLUMN_HALF_WIDTH + TILE_WIDTH_METRES)` — 103.98 m
     * on today's art, 106.99 m on the art it was written against — reasoning that one cliff is one
     * tile wide. That is true of the QUAD and false of the ART: a wall tile's last
     * `RockFace.EDGE_INSET_METRES` (2.715 m, 139 texels) hold no alpha at all — that margin is
     * exactly what lets the ragged silhouette land on `Tuning.COLUMN_HALF_WIDTH`. So the sprite's
     * visible rock ends at [RockFace.CREST_OUTER_HALF_WIDTH] = 49.28 m, and a cap of 51.99 m
     * leaves a 2.715 m strip — nearly a quarter of each cliff — that only the BODY could fill. The
     * owner, on a capture of the capped build: *"still seems like we use at least two sprites for
     * width at each side, can't we use only one?"*
     *
     * Capping on the art's own outward end means one edge sprite covers each side exactly, and
     * the body is reduced to at most a single texel. `FramingTest` asserts both.
     *
     * ## WHAT IT COSTS, SAID PLAINLY
     *
     * The design aspect is `98.516 / 60` = **1.6419**, and 16:9 is 1.7778 — so unlike the first
     * version this one does NOT leave 16:9 alone. A 16:9 panel now shows 55.42 m of water instead
     * of 60, a 7.6% loss, and every wider aspect loses more (42.2 m at 21:9, 41.2 m at the owner's
     * 2.389, 27.7 m at 32:9).
     *
     * That is a gameplay change — how far ahead you can see is how far ahead you can plan — and it
     * was put to the owner as exactly that trade, twice, before it was made. He chose one sprite.
     * `FramingTest` bounds the cost rather than forbidding it, so that a re-bake which made the
     * loss large would fail rather than pass quietly.
     */
    const val VISIBLE_WIDTH_METRES = 2f * RockFace.BODY_INNER_HALF_WIDTH

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
     * 3 m -> 6 m -> 9 m, EACH TIME BY DESIGN DECISION AND NOT BY MEASUREMENT. 3 m was inherited
     * from the placeholder square and is what the sprite bake assumed
     * (`2026-08-07-diver-spritesheet-bake-design` §3 reasons from ~5% of screen height); it read
     * as too small, was doubled to 6 m, and the owner then asked for another 50%. 9 m is 15% of
     * the visible column, which is a lot of diver — it is the deliberate answer to "he reads as
     * too small", not an accident, and `VISIBLE_DEPTH_METRES` is unchanged either way, so the
     * amount of water a player can see and plan against has never moved.
     *
     * The bake does not need redoing, and each step moves the sampling the right way. A 384-texel
     * cell drawn at 5% of an 1800 px framebuffer is a 4.3x minification, and the sheets carry
     * `maxMipLevels = 1` — deliberately, so mip generation cannot average across cell boundaries
     * and smear adjacent frames together — so there is no mip chain to absorb it and the high
     * frequencies in the normal map in particular would alias. At 10% it is 2.1x; at 15% it is
     * 1.4x, which is the closest to native the art has ever been drawn at.
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
    const val DIVER_HEIGHT_METRES = 9f
    const val PEARL_SIZE_METRES = 1.2f

    /**
     * The vent's drawn HEIGHT — its width follows from the art through
     * [OxygenSprite.widthForHeight], because the sheet's cells are 0.843:1 rather than square.
     *
     * SCALED 1.5x FROM 2.4 m on the owner's call, after seeing the animated blob on a real frame.
     * Three things move with it and none of them needed a second edit, which is the point of it
     * being one number: the `gi_normal_map` submission (same rect, copied argument list), the
     * cull square ([DiveRenderer.ventCullSizeFor]), and the `O2` label, whose font size and
     * offset are both fractions of this ([VentLabel.fontSizeFor]) so the label keeps its
     * proportion on the blob instead of needing to be re-tuned alongside it.
     *
     * WHAT DOES NOT MOVE WITH IT IS `Tuning.AIR_POCKET_PICKUP_RADIUS`, and that is deliberate —
     * it is GAMEPLAY, it is measured, and its KDoc records that growing it is free in the sweep
     * and still wrong (a vent is consumed on contact, so extra reach makes it easier to BURN one
     * by brushing past on a full breath). So this widens the gap between "the diver's silhouette
     * is touching the plume" and "the breath is taken": the diver's own half-height is 4.5 m
     * against a 4 m radius, and the vent's half-height goes 1.2 m -> 1.8 m, so the overlap window
     * grows from 1.7 m to 2.3 m of centre distance. `OxygenSpriteTest` still passes because the
     * vent's own corner reach (2.35 m) stays well inside the 4 m radius — the blob is entirely
     * within the zone that picks it up, which is the invariant that actually matters.
     */
    const val AIR_POCKET_SIZE_METRES = 3.6f

    /** Where the camera would sit if it tracked the diver exactly. */
    fun targetCameraDepth(diverDepth: Float) = diverDepth - VISIBLE_DEPTH_METRES * DIVER_SCREEN_FRACTION
}
