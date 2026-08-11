package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.modules.lighting.shared.NormalMapRenderer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What is art and what is still a placeholder:
 *   diver     = the baked sprite sheets ([DiverSprite]) — albedo on `main`, normal on GI's
 *               `gi_normal_map`, one rect submitted twice
 *   walls     = [RockFace]'s tiling cliff, the same two-surface arrangement as the diver
 *   backdrop  = [Backdrop]'s parallax silhouettes, albedo only (they are flat masks)
 *   pearl     = the iridescence shader over a small quad
 *   zones     = flat horizontal bands
 *   air vents = untextured quads, still waiting for their own art
 *
 * EVERYTHING HERE IS IN WORLD METRES, +x right, +y down, and world y IS depth. There is no
 * coordinate maths left in this file at all: [CameraRig] writes `engine.gfx.mainCamera` once
 * per fixed tick and the engine's own view matrix turns a metre into a pixel. This file does
 * not know the resolution and must never learn it — a pixel count here is the bug class
 * `CLAUDE.md` and [Framing] both exist to prevent.
 *
 * The visible region is asked of the camera ([Camera.topLeftWorldPosition] /
 * [Camera.bottomRightWorldPosition]), which the engine recomputes each frame in
 * `GraphicsImpl.initFrame` (:112) from the matrix that frame will actually be drawn with. That
 * is stronger than re-deriving our own rect: the strip walk and the walls cannot disagree with
 * what is on screen, because they are reading what is on screen.
 *
 * PER-OBJECT CULLING IS THE CAMERA'S OWN VIEW TEST — see [showsSquare], which is where the
 * reasoning lives. It replaces the pixel-row bounds checks that went away with the coordinates
 * they were written in, and it is strictly better than they were: it tests x as well as y, and
 * it tests the object's whole rect rather than its centre. The saving is small (there are at
 * most a few dozen objects and a fillRect outside the frustum is clipped by the GPU anyway);
 * the point is that "is this on screen" now has exactly one answer in this codebase, and it is
 * the engine's.
 *
 * OBJECTS ARE DRAWN FROM THEIR CENTRES — [fillRectCentred], which is where that reasoning lives.
 * A pearl, a vent, the fish and the diver are each authored as a centre and a size, which is also
 * exactly what the GI light quads on the same objects already were (`scene.vert:102` hardcodes a
 * 0.5 origin) and what the normal-map pass will be when the art lands. Only the things that are
 * genuinely spans — the zone-band strips and the column walls, which are defined by the edges of
 * the visible rect rather than by a middle — still take a corner.
 */
object DiveRenderer
{
    // Per-zone anchor colours for the zone bands, indexed by Zone.ordinal. Blended
    // continuously across depth by DepthBlend rather than drawn as flat per-zone blocks —
    // see drawZoneBands. Kept as parallel FloatArrays (not a Map<Zone, Color>) so blending
    // is zero-allocation: DepthBlend.blend takes primitive floats in, floats out.
    private val zoneRed   = floatArrayOf(0.10f, 0.06f, 0.03f, 0.015f, 0.004f)
    private val zoneGreen = floatArrayOf(0.34f, 0.22f, 0.12f, 0.06f,  0.015f)
    private val zoneBlue  = floatArrayOf(0.52f, 0.36f, 0.22f, 0.12f,  0.035f)

    /** Strip height for the zone-band gradient, in metres (resolution-independent). Small
     *  enough that DepthBlend's smoothstep easing reads as continuous rather than banded.
     *  Internal so [DiveRendererTest] can assert the walk's pitch against it rather than
     *  against a second copy of the number. */
    internal const val BAND_STRIP_METRES = 0.5f

    /**
     * Hard ceiling on how many strips one frame may draw — see [stripCount].
     *
     * A normal frame draws exactly 120 (60 m of water at half a metre a strip). The visible
     * world rect is computed by the engine before any of our code runs that frame, so on the
     * very first frame, or in the middle of a window resize, it can be empty, inverted or
     * enormous. Drawing too few strips is a frame that looks wrong for one sixtieth of a
     * second; drawing an unbounded number is a frame that never ends. Four times the nominal
     * count is far more headroom than any real camera state needs and still bounds the loop.
     */
    internal const val MAX_BAND_STRIPS = (4f * Framing.VISIBLE_DEPTH_METRES / BAND_STRIP_METRES).toInt()

    /**
     * THE REFLECTANCE FLOOR — the single most load-bearing number in this file. Nothing
     * DiveRenderer draws onto the world surface may have a linear-RGB vector length below it.
     *
     * `GlobalIlluminationSystem` (wired up in [DiveLighting.setup]) relights the world surface
     * by adding a `MultiplyEffect(..., minReflectance)` to it. That effect's shader
     * (`shaders/effects/texture_multiply_blend.frag` in the engine source) is NOT a plain
     * multiply — it opens with a hard guard:
     *
     *     if (length(c0.rgb) < minReflectance)   // c0 = this surface's albedo, in LINEAR space
     *         c0.rgb = vec3(minReflectance);     // "creates a path-to-white if albedo is 100% black"
     *
     * so any albedo darker than that length is DISCARDED and replaced by flat, neutral,
     * hue-less grey before the light map is applied. `minReflectance` is left at
     * `GlobalIlluminationSystem`'s own default, which is 0.02.
     *
     * That guard is what produced the razor-sharp horizontal seam this constant exists to
     * kill. [drawZoneBands] samples the depth-colour curve every [BAND_STRIP_METRES], and
     * `Surface.setDrawColor` quantizes each channel to 8 bits (`(c * 255).toInt()`, see
     * `SurfaceConfigInternal.setDrawColor`). Around 94 m the quantized water colour steps from
     * `(4, 19, 38)` — linear length 0.02050, just above the floor — to `(4, 19, 37)` — linear
     * length 0.01967, just below it. One 8-bit step in ONE channel therefore flipped the guard
     * on, and every strip below that depth was drawn as `vec3(0.02)` instead of as water:
     * red rose 0.00152 -> 0.02 (13x), green 0.00651 -> 0.02 (3x), blue barely moved. Hence a
     * hairline at a fixed WORLD depth (it tracks the colour curve, not the screen or the
     * diver), independent of the seed, present in the albedo but not in the light map, with
     * the water below it brighter AND drained of its blue.
     *
     * Confirmed by setting `minReflectance = 0` with nothing else changed: the largest
     * row-to-row median jump across the play column dropped from 2.33/255 to 0.33/255 — i.e.
     * to the frame's ordinary gradient noise. Also explains why swapping the strip loop for a
     * single flat fill appeared to "fix" it: that fill's colour, (0.02, 0.08, 0.15), has
     * linear length 0.02068 — barely above the floor by luck, so the guard never fired.
     *
     * THE FLOOR *CAN* BE REMOVED, AND WE DELIBERATELY DO NOT. An earlier version of this
     * comment said it "cannot be avoided from here (it is the engine's…)". That was simply
     * false, and it mattered, because it presented a choice as a constraint. `minReflectance` is
     * a public `@Prop var` on `GlobalIlluminationSystem` (`:56`, default `0.02f`), re-pushed to
     * the effect every frame at `:217`, so `system.minReflectance = 0f` in [DiveLighting.setup]
     * — one line, in a file we already own — would delete the guard outright.
     *
     * We keep the engine default and hold the water above it instead, for two reasons worth
     * stating so the next person makes this choice knowingly rather than inheriting it:
     * the colour-side solution is measured and working (the numbers above, and
     * `DiveRendererTest`'s 0-200 m quantization sweep), and the floor is what stops a
     * genuinely black albedo becoming an unlit black hole that no amount of light can rescue
     * — which is what the shader's own comment says it is for. Turning it off would also be a
     * lighting change, and lighting changes made in the same pass as anything else are
     * unattributable. So: the water must stay above it — see [floorBlueForReflectance].
     */
    internal const val GI_REFLECTANCE_FLOOR = 0.02f

    /**
     * Headroom over [GI_REFLECTANCE_FLOOR], to absorb the 8-bit TRUNCATION `setDrawColor`
     * applies after this code has run. Truncating each channel can cost up to 1/255, which
     * near these values is worth ~5% of the linear length — sitting exactly on the floor would
     * therefore let quantization drop a strip back under it and reinstate the seam. 1.10 leaves
     * roughly double the worst-case loss: swept over 0-200 m in 0.025 m steps, the smallest
     * length any strip actually reaches AFTER quantization is 0.0210 (see
     * `DiveRendererTest.every zone band colour clears the GI reflectance floor once quantized`).
     */
    private const val REFLECTANCE_FLOOR_MARGIN = 1.10f

    private const val MIN_REFLECTANCE_LENGTH = GI_REFLECTANCE_FLOOR * REFLECTANCE_FLOOR_MARGIN

    private val pearlColor = Color(1f, 0.78f, 0.35f)
    private val airPocketColor = Color(0.65f, 0.95f, 1f)
    private val airPocketSpentColor = Color(0.22f, 0.34f, 0.42f)
    private val diverColor = Color(1f, 1f, 1f)
    private val surfaceColor = Color(0.55f, 0.80f, 0.95f)

    /**
     * What the rock face is drawn ON, and what shows where the rock's own texture has not
     * arrived — see [drawColumnWalls]. Warm, so it reads as stone rather than as more (blue)
     * water or a UI border.
     *
     * IT IS NO LONGER THE WALL, IT IS THE WALL'S BACKING. [RockFace]'s cliff has a ragged alpha
     * edge and covers only [RockFace.TILE_WIDTH_METRES] per tile, so something opaque and
     * stone-coloured has to sit behind it: the transparent notches in the cliff show this, and on
     * a display wide enough to see past the tiles so does the far side of the frame. Keeping the
     * tuned value rather than picking a new one also means the loading frames, and any display
     * where the texture fails to upload, look exactly like the wall that shipped.
     *
     * The previous value, `Color(0.05, 0.045, 0.045)`, did not read as stone at all: its linear
     * length is 0.00599, well under [GI_REFLECTANCE_FLOOR], so the GI blend threw the warmth
     * away and substituted flat `vec3(0.02)` grey — which, multiplied by a light map that is
     * essentially zero that far from the diver and the pearls, measured 0.2-1.6 out of 255.
     * Hard black bars down both sides of a 16:10 or 16:9 frame read as letterboxing, not as
     * rock. Note that merely clearing the floor is not enough on its own: a colour sitting just
     * above it is no brighter than the grey it replaces, so the walls stay black. This value
     * clears the floor with room to spare (linear length 0.0579, per channel 0.0395/0.0331/
     * 0.0262 against the flat 0.02 it used to become), which is what actually lets the ambient
     * in the lit zones — and the diver's beam below them — pick the rock out.
     *
     * Deliberately still DARKER than the water it borders wherever the water is lit: relative
     * luminance 0.034 against the shallows' 0.085, so the wall reads as a subordinate dark
     * border rather than a bright frame competing with the pearls (a pearl's albedo luminance
     * is 0.624, ~18x this). Verified against captures at 25 m, 100 m and 145 m.
     */
    internal val wallColor = Color(0.22f, 0.20f, 0.18f)

    // THE INNER FACE IS GONE, AND IT SHOULD NOT COME BACK. `0f07303` painted a 0.7 m wide
    // lighter strip (`wallEdgeColor`, `WALL_EDGE_METRES`) down the inside of each slab, because a
    // flat slab of one colour still read as a bar wherever the light map fell to nothing — the
    // boundary needed a value of its own to stay legible. That was compensation for the wall
    // having no art, and the compensation is what made it look like UI chrome: a perfectly
    // straight, perfectly uniform vertical line is not a thing rock does. RockFace's ragged alpha
    // edge carries the boundary now, and carries it with a silhouette that varies with depth, so
    // a second painted edge would only fight it. See drawColumnWalls for the cue that edge has to
    // keep carrying, art or no art.

    /**
     * The colour of every [Backdrop] silhouette. The layers themselves are white alpha masks —
     * their sources are one flat colour plus dither, with no internal detail at all — so this
     * and [Backdrop.Layer.alpha] are their entire appearance.
     *
     * Cool and dark: distant terrain seen through a hundred metres of water is the water's own
     * hue, darker. Linear length 0.0311, so even a fully opaque silhouette clears
     * [GI_REFLECTANCE_FLOOR] on its own and the shader never substitutes grey for it — and every
     * layer is drawn at well under full alpha over water that already clears the floor, so the
     * composite cannot fall under it either.
     *
     * Relative luminance 0.0146: under [wallColor]'s 0.034 and far under lit shallow water's
     * 0.085, so a ridge always reads as something BEHIND the water rather than as an object in it.
     */
    internal val silhouetteColor = Color(0.10f, 0.13f, 0.17f)

    /**
     * Thickness of the waterline, in metres. Was `pixelsPerMetre(h) * 0.4f` — i.e. 0.4 m, in a
     * form that had to be multiplied out at the draw site. Named now that a size in this file is
     * simply a size.
     */
    private const val SURFACE_LINE_METRES = 0.4f

    /**
     * [cam] is `engine.gfx.mainCamera` — the camera [surface] is drawn with, and the same object
     * GI's local scene surface uses (`GlobalIlluminationSystem.kt:78`). Passed in rather than
     * reached for so this object keeps no engine handle of its own, and so the visible rect the
     * bands walk is provably the rect the frame is drawn with.
     */
    fun render(surface: Surface, sim: DiveSim, cam: Camera, normalMaps: NormalMapRenderer?, aimDegrees: Float)
    {
        // Read once. These are two DISTINCT Vector2f fields on Camera (Camera.kt:32-33), not
        // the single shared return buffer worldPosToScreenPos hands back, so reading one does
        // not clobber the other — but they are also live references into the camera, so their
        // components are copied out here and the draw methods take plain floats. That keeps the
        // rect a value rather than something a later engine call could move underneath us.
        val topLeft = cam.topLeftWorldPosition
        val bottomRight = cam.bottomRightWorldPosition
        val worldLeft = topLeft.x
        val worldTop = topLeft.y
        val worldRight = bottomRight.x
        val worldBottom = bottomRight.y

        // The iridescence renderer, and the torch that drives it, are resolved ONCE for the
        // whole frame and handed down — the same arrangement as `cam` and `normalMaps` above,
        // and for the stronger of the two reasons: a pearl and the lure disguised as one must
        // provably be lit from the same point, and the light's own emitter (DiveLighting
        // .drawDiverBeam) derives that point from these very functions. Null until the renderer
        // has been attached (frame one) or if its shader failed to compile — see
        // IridescenceRenderer's class doc; both draws below fall back to a flat square.
        val iridescence = IridescenceRenderer.of(surface)
        iridescence?.setLightSource(
            DiveLighting.torchX(sim.x, aimDegrees),
            DiveLighting.torchDepth(sim.depth, aimDegrees)
        )

        // THE WATER NO LONGER STARTS AT THE TOP OF THE FRAME, and that is what makes a sky
        // possible at all. `mainSurface`'s background is transparent and [Sky] is drawn on its own
        // surface BEHIND this one, so everything above [WaterSurface.QUAD_BOTTOM_DEPTH] is left
        // for the water quad's alpha ramp to resolve against the sunset. See [WaterSurface] for
        // why the boundary is one alpha ramp rather than two edges that have to meet.
        //
        // Null only on frame one, or if the shader failed to compile. Then the bands run from the
        // top of the frame exactly as they always did and the flat waterline comes back — i.e. the
        // game degrades to what it looked like before there was a sky, rather than to a hole.
        val water = WaterRenderer.of(surface)
        val bandTop = if (water != null) max(worldTop, WaterSurface.QUAD_BOTTOM_DEPTH) else worldTop

        drawZoneBands(surface, worldLeft, bandTop, worldRight, worldBottom)
        // Between the bands and the walls, and it has to be exactly there. The bands are opaque
        // and cover the whole visible rect, so a backdrop drawn BEFORE them is not behind them,
        // it is invisible; and the walls are opaque too, which is what confines the silhouettes
        // to the column without a single clip test.
        drawBackdrop(surface, cam, worldTop, worldBottom)

        // After the backdrop and before the walls. After, because a silhouette's quad can reach a
        // few tens of centimetres past the waterline near the surface and the sea has to be in
        // front of it; before, because the cliffs stand IN the water and must be in front of the
        // sea.
        if (water != null) drawWaterSurface(surface, water, worldLeft, worldTop, worldRight, worldBottom)
        else drawSurfaceLine(surface, worldLeft, worldRight)

        drawColumnWalls(surface, normalMaps, worldLeft, worldTop, worldRight, worldBottom)
        drawAirPockets(surface, sim, cam)
        drawPearls(surface, sim, cam, iridescence)
        drawAnglerfish(surface, sim, cam, iridescence)
        drawDiver(surface, sim, cam, normalMaps, aimDegrees)
    }

    /**
     * Drawn as many thin horizontal strips, each coloured by [DepthBlend.blend] at the
     * depth its centre corresponds to, rather than one flat [Surface.fillRect] per zone.
     * A flat-per-zone fill is exactly the "sharp jump between depth levels" playtesters
     * flagged: the colour visibly snapped the instant the camera crossed a zone's
     * `minDepth`. Sampling the same continuous curve every [BAND_STRIP_METRES] instead
     * means each zone still reads as its own colour in the middle of its span (DepthBlend
     * holds the value near-flat there) while the crossing itself is a gradient.
     *
     * Every strip colour here must clear [GI_REFLECTANCE_FLOOR] — read that constant's doc
     * before changing the zone tables, [BAND_STRIP_METRES], or anything else in this loop.
     *
     * THE WALK ITSELF IS IN WORLD DEPTHS — [stripCount], [stripTopDepth] and [stripCentreDepth],
     * all pure and asserted in `DiveRendererTest`. It always was, since the extraction in
     * `4aecf7f`; what the migration removed is the pixel conversion that used to wrap it. Which
     * depth each strip is coloured by is the thing that decides where the reflectance floor
     * bites, so it is the one part of this file that must be provable without a GL context, and
     * it is now the ONLY arithmetic here.
     */
    private fun drawZoneBands(surface: Surface, worldLeft: Float, worldTop: Float, worldRight: Float, worldBottom: Float)
    {
        val width = worldRight - worldLeft
        val count = stripCount(worldTop, worldBottom)
        for (i in 0 until count)
        {
            val centreDepth = stripCentreDepth(worldTop, worldBottom, i)
            val top = stripTopDepth(worldTop, i)
            val bottom = min(stripTopDepth(worldTop, i + 1), worldBottom)
            surface.setDrawColor(zoneRedAt(centreDepth), zoneGreenAt(centreDepth), zoneBlueAt(centreDepth), 1f)
            surface.fillRect(worldLeft, top, width, bottom - top)
        }
    }

    // --- The zone-band strip walk, in world depths ---------------------------------------
    //
    // Pure and exposed for testing, for the reason [GI_REFLECTANCE_FLOOR] spells out at
    // length: a single strip taking its colour from the wrong depth is a razor-sharp hairline
    // across the whole play column, and nothing about that is visible in a passing test of the
    // colour curve alone. Expressed in metres rather than in screen rows so that the walk is
    // resolution-independent by construction — the same rect gives the same strips whatever
    // the booth's framebuffer turns out to be.

    /**
     * How many strips cover the visible depth range `[worldTop, worldBottom]`, rounded up so
     * the last one reaches the bottom of the rect (clipped there — see [stripCentreDepth]).
     *
     * Returns 0 for an empty, inverted or NaN rect and never more than [MAX_BAND_STRIPS], so a
     * world rect that has not been computed yet costs a wrong-looking frame rather than a hung
     * one.
     */
    internal fun stripCount(worldTop: Float, worldBottom: Float): Int
    {
        val span = worldBottom - worldTop
        if (!(span > 0f)) return 0 // written this way round so NaN falls out here too
        return ceil(span / BAND_STRIP_METRES).toInt().coerceAtMost(MAX_BAND_STRIPS)
    }

    /** The depth the top edge of strip [index] sits at. */
    internal fun stripTopDepth(worldTop: Float, index: Int) = worldTop + index * BAND_STRIP_METRES

    /**
     * The depth strip [index] takes its colour from: the middle of the strip, not its top
     * edge, so the sampled curve is centred on the band that is actually painted. The last
     * strip is clipped to [worldBottom], which pulls its centre up accordingly.
     */
    internal fun stripCentreDepth(worldTop: Float, worldBottom: Float, index: Int): Float
    {
        val top = stripTopDepth(worldTop, index)
        val bottom = min(stripTopDepth(worldTop, index + 1), worldBottom)
        return (top + bottom) * 0.5f
    }

    /**
     * Solid rock bounding the playable column outside +-[Tuning.COLUMN_HALF_WIDTH]. Without
     * this the boundary is invisible: [CameraRig] derives pixels-per-metre from screen HEIGHT,
     * so on a 16:9 booth screen the visible half-width is ~53m versus the 40m column and the
     * diver stops dead in open water with no visual reason — the stick reads as broken rather
     * than blocked. Drawn onto the world surface (not "hud"), so it is behind
     * [no.njoh.pulseengine.core.PulseEngineGame]'s lighting pass like everything else
     * DiveRenderer draws, and it frames the play area rather than looking like a UI chrome.
     *
     * On a narrower aspect ratio (e.g. the 4:3 dev window) the column may fill the whole
     * screen and these rects fall entirely off both edges — that is fine and needs no special
     * case, since [Surface.fillRect] with a non-positive width simply draws nothing visible.
     * Note this is now stated in metres against the visible rect rather than in pixels against
     * the screen, and it is the same statement: the slab exists only where there is frame left
     * over outside the column.
     *
     * ## THE BACKING IS CUT BACK OFF THE RAGGED EDGE, AND THAT WAS A SHIPPED DEFECT
     *
     * The owner, looking at a capture: *"we should show water behind the alpha of the rocks.
     * Currently it's black."* He was right, and the cause was entirely here rather than in the
     * lighting. `8258eb6` kept the flat slab from `0f07303` as a backing across the WHOLE wall and
     * then drew the cliff over it, on the reasoning that "the walls are opaque, so they confine
     * the backdrop to the column with no clip test". That reasoning held for a flat slab and
     * stopped holding the moment the wall became a texture with an alpha cutout: the slab was
     * still opaque, so it covered the water [drawZoneBands] had already laid down full-width, and
     * what showed through the cliff's notches was [wallColor] — not water.
     *
     * MEASURED, at 12 m and 16:9 (3200x1800), mean over a 40-row band across the boundary:
     *
     *     x = column boundary (water)     RGB (0.00, 3.00, 30.88)
     *     x = 1.3 m outside it (backing)  RGB (0.00, 0.00,  0.00)
     *     x = 4.0 m outside it (backing)  RGB (0.00, 0.00,  0.00)
     *
     * — a hard vertical step from water to literal zero, at exactly the column boundary and
     * nowhere else, which is a giveaway that it is an ALBEDO edge and not a light-map one: the
     * light map knows nothing about `Tuning.COLUMN_HALF_WIDTH`. [wallColor]'s linear blue is
     * 0.0273 against the shallows' 0.212, so the same light map over water instead of over stone
     * is worth ~7.8x in the channel that carries this scene.
     *
     * So the backing now stops at [RockFace.BACKING_HALF_WIDTH] — outward of every texel of the
     * innermost tile that is not opaque in every row. Outward of that it is provably invisible
     * and still does its other two jobs (the joins between tiles on a wide panel, and the frames
     * before the upload lands); inward of it the zone bands show through the alpha, which is what
     * the owner asked for. When the texture is NOT ready the backing covers the whole wall again,
     * unchanged — a flat slab is the right degradation, an empty frame edge is not.
     */
    private fun drawColumnWalls(
        surface: Surface,
        normalMaps: NormalMapRenderer?,
        worldLeft: Float,
        worldTop: Float,
        worldRight: Float,
        worldBottom: Float
    )
    {
        val height = worldBottom - worldTop
        val leftSlab = -Tuning.COLUMN_HALF_WIDTH - worldLeft
        val rightSlab = worldRight - Tuning.COLUMN_HALF_WIDTH
        val ready = RockFace.ready()

        // How far from the axis the opaque backing may begin. Without the art it is the column
        // boundary itself, i.e. exactly the slab `0f07303` shipped.
        val backingHalfWidth = if (ready) RockFace.BACKING_HALF_WIDTH else Tuning.COLUMN_HALF_WIDTH
        val leftBacking = -backingHalfWidth - worldLeft
        val rightBacking = worldRight - backingHalfWidth

        surface.setDrawColor(wallColor)
        if (leftBacking > 0f) surface.fillRect(worldLeft, worldTop, leftBacking, height)
        if (rightBacking > 0f) surface.fillRect(backingHalfWidth, worldTop, rightBacking, height)

        if (!ready) return

        // White and opaque: drawTexture MODULATES by the surface's current draw colour, which is
        // still wallColor from the fills above.
        surface.setDrawColor(1f, 1f, 1f, 1f)
        if (leftSlab > 0f) drawRockWall(surface, normalMaps, leftSlab, worldTop, worldBottom, LEFT_WALL)
        if (rightSlab > 0f) drawRockWall(surface, normalMaps, rightSlab, worldTop, worldBottom, RIGHT_WALL)
    }

    /**
     * The right-hand wall is the same cliff turned through half a turn, and that is the only
     * difference between the two calls.
     *
     * [RockFace]'s art is a LEFT wall: solid stone on its u = 0 side, ragged alpha edge on its
     * u = 1 side. The left wall can use it as it stands, with u = 1 landing on the column
     * boundary. The right wall needs that edge on its own inner side, i.e. mirrored — and 180
     * degrees is the way to get it, not a negative width and not flipped uv arguments:
     *
     *  - `NormalMapRenderer.drawNormalMap` takes no uv arguments at all, so a `uMin`/`uMax` swap
     *    would mirror the albedo and leave the normals unmirrored. The lighting would then be lit
     *    from the wrong side of every bump on one wall only.
     *  - A negative width flips the quad geometrically but leaves `normalRotation` alone, with
     *    the same result, and additionally reverses the triangles' winding.
     *  - `normal_map.vert` builds `normalRotation = rotMatrix(rotation + cameraAngle)`, so a
     *    rotation is the ONE transform the engine applies to the geometry and to the normal
     *    vectors together.
     *
     * Half a turn also flips v, so the right wall shows the cliff upside down as well as
     * mirrored. That is a bonus rather than a cost: an exact mirror of a 40 m tile down both
     * sides of the frame is conspicuous, and this breaks it for free. It does not disturb the
     * tiling, because the quad still spans a whole number of tiles between two world-space tile
     * boundaries, so the world-depth to v mapping stays a function of depth alone.
     */
    private const val LEFT_WALL = 0f
    private const val RIGHT_WALL = 180f

    /**
     * One wall: a whole number of tiles across, a whole number down, anchored on the world's tile
     * lattice and NOT on the visible rect.
     *
     * ## The cue this has to keep carrying
     *
     * The reason a wall is drawn at all has not changed since `0f07303`. [CameraRig] derives
     * pixels-per-metre from screen HEIGHT, so a 16:9 booth panel shows about 53 m of half-width
     * against the column's 40 m, and without a visible boundary the diver stops dead in open
     * water with no visual reason — the stick reads as broken rather than as blocked. What has
     * changed is that the art carries it now instead of a painted strip.
     *
     * SO THE ROCK MUST STAY WHERE THE DIVER STOPS. The walls are symmetric at
     * +-[Tuning.COLUMN_HALF_WIDTH] because that is where the simulation actually halts him, and
     * the two must not drift apart: the cliff's ragged edge is the promise, and `dive/` is what
     * keeps it. Moving the rock inward or outward for looks — or putting a cliff down one side
     * only, as the mockup does — silently breaks the cue, and the failure is a player wrestling
     * with a stick they now believe is faulty.
     *
     * WHICH IS WHY THE QUAD IS ANCHORED ON [RockFace.QUAD_INNER_HALF_WIDTH] AND NOT ON THE
     * BOUNDARY ITSELF. The last 67 of the tile's 691 texel columns hold no alpha at all, so a quad
     * whose u = 1 edge sat on the boundary put the cliff's furthest-reaching texel 1.31 m short of
     * it. That was invisible while an opaque slab filled the gap and becomes a uniform channel of
     * un-enterable water the moment the water shows through — i.e. removing the slab would have
     * WEAKENED the very cue this method exists for. Shifting the quad in by exactly that empty
     * margin restores it and then some: the promontories touch the boundary, the bays between them
     * are water, and because the margin really is empty no rock is ever drawn inside the column.
     *
     * ## Everything else here is width and rounding
     *
     * [wallWidth] is how much frame is left outside the column on this side, which is zero at 4:3
     * (the visible half-width is exactly 40 m there) and grows with the panel's aspect ratio. The
     * quad is a whole number of [RockFace.TILE_WIDTH_METRES] wide, anchored on
     * [RockFace.QUAD_INNER_HALF_WIDTH] and running OUTWARD, so the surplus spills off the side of
     * the frame where nothing can see the join. See [RockFace.tileColumns] for why a fractional
     * count is not an option — and note the count is asked for the slab PLUS the inset, since the
     * quad now starts inside the boundary and has that much further to reach.
     *
     * Vertically it spans [RockFace.tileRows] whole tiles from the tile boundary at or above the
     * visible top, for the reason [RockFace.tileTopDepth] gives: that is what nails the rock to
     * the water instead of to the camera.
     *
     * The two draws are ONE argument list written twice, exactly as [drawDiver]'s are. If you
     * change one of these numbers, change it in both.
     */
    private fun drawRockWall(
        surface: Surface,
        normalMaps: NormalMapRenderer?,
        wallWidth: Float,
        worldTop: Float,
        worldBottom: Float,
        angle: Float
    )
    {
        val phase = if (angle == RIGHT_WALL) RockFace.TILE_HEIGHT_METRES * 0.5f else 0f
        val columns = RockFace.tileColumns(wallWidth + RockFace.EDGE_INSET_METRES)
        val rows = RockFace.tileRows(worldTop, worldBottom, phase)
        val width = columns * RockFace.TILE_WIDTH_METRES
        val height = rows * RockFace.TILE_HEIGHT_METRES

        // Centres, because a rotated quad's (x, y) has to mean its middle for the rotation to be
        // about that middle — the same CENTRE_ORIGIN convention every other object here uses.
        // The quad grows OUTWARD from its inner edge, hence the sign.
        val outward = if (angle == RIGHT_WALL) 1f else -1f
        val centreX = outward * (RockFace.QUAD_INNER_HALF_WIDTH + width * 0.5f)
        val centreY = RockFace.tileTopDepth(worldTop, phase) + height * 0.5f

        surface.drawTexture(
            RockFace.diffuse,
            centreX, centreY, width, height, angle, CENTRE_ORIGIN, CENTRE_ORIGIN,
            0f, 0f, 0f, 1f, 1f, columns.toFloat(), rows.toFloat()
        )

        // The copied argument list. Same rect, same angle, same origin, same tiling.
        normalMaps?.drawNormalMap(
            RockFace.normal,
            centreX, centreY, width, height, angle, CENTRE_ORIGIN, CENTRE_ORIGIN,
            columns.toFloat(), rows.toFloat()
        )
    }

    /**
     * The parallax silhouettes — see [Backdrop], which owns the layer table and the arithmetic.
     *
     * Each layer is one quad the width of the play column, plus a flat skirt continuing its solid
     * body down to the bottom of the frame ([Backdrop.skirtDepth]). Painted far to near, so a
     * near ridge occludes a far one.
     *
     * The skirt is drawn FIRST and the art over it, rather than the other way round: they are the
     * same colour at the same alpha, but alpha compositing is not idempotent, and a skirt painted
     * over the bottom of the silhouette would double the layer's alpha along the overlap and
     * leave a darker band exactly where the join is meant to be invisible. Drawing the skirt
     * strictly below the quad's bottom edge is what keeps them disjoint.
     */
    private fun drawBackdrop(surface: Surface, cam: Camera, worldTop: Float, worldBottom: Float)
    {
        if (!Backdrop.ready()) return

        val width = Backdrop.widthMetres
        Backdrop.layers.forEach { layer ->
            val height = layer.heightMetres(width)
            val top = Backdrop.parallaxTopDepth(worldTop, layer.restTopDepth, layer.rate)
            val centreY = top + height * 0.5f

            // The engine's own view test, padded by nothing: this is a quad on `main`, so its
            // rasterised extent is exactly the rect below. A square of the LARGER side strictly
            // contains the layer, so the test can only ever be conservative.
            val skirtTop = Backdrop.skirtDepth(top, height, worldBottom)
            if (skirtTop < worldBottom)
            {
                surface.setDrawColor(silhouetteColor.red, silhouetteColor.green, silhouetteColor.blue, layer.alpha)
                surface.fillRect(-width * 0.5f, skirtTop, width, worldBottom - skirtTop)
            }

            if (!cam.showsSquare(0f, centreY, max(width, height))) return@forEach

            surface.setDrawColor(silhouetteColor.red, silhouetteColor.green, silhouetteColor.blue, layer.alpha)
            surface.drawTexture(
                layer.texture,
                0f, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN
            )
        }
    }

    /**
     * The sea's surface: ONE quad, spanning the visible width and the band of depth
     * [WaterSurface.QUAD_TOP_DEPTH] to [WaterSurface.QUAD_BOTTOM_DEPTH].
     *
     * Everything interesting is in `shaders/water.frag` — the wave, the anti-aliased waterline,
     * the sun on the crests and the glow under them. What lives here is the two things the shader
     * cannot know: where the quad goes, and what colour the water it has to hand off to is.
     *
     * THE HAND-OFF IS THE PART TO GET RIGHT. The quad's bottom edge is exactly where
     * [drawZoneBands] starts, so the shader is handed [zoneRedAt] and friends sampled at
     * [Tuning.SURFACE_DEPTH] and at [WaterSurface.QUAD_BOTTOM_DEPTH] and interpolates between
     * them. At the bottom edge its output IS the first band's colour — not approximately, exactly
     * — so the two cannot step apart however the zone tables are retuned. This is the same
     * discipline as [drawDiver]'s copied argument list: the number that must agree is passed
     * across rather than derived twice.
     *
     * CULLED AGAINST THE VISIBLE RECT DIRECTLY rather than through [showsSquare], because the band
     * is a wide flat strip and a square test against its LARGER side would be uselessly
     * conservative — it would keep the sea "on screen" for 100 m of the dive. This is the
     * measurement behind "the sky costs nothing where it is not visible": below about 10 m of
     * depth this method issues no draw at all, and [Sky.render] issues none either.
     */
    private fun drawWaterSurface(
        surface: Surface,
        water: WaterRenderer,
        worldLeft: Float,
        worldTop: Float,
        worldRight: Float,
        worldBottom: Float
    )
    {
        if (worldTop > WaterSurface.QUAD_BOTTOM_DEPTH || worldBottom < WaterSurface.QUAD_TOP_DEPTH) return

        water.setWaterColours(
            zoneRedAt(Tuning.SURFACE_DEPTH), zoneGreenAt(Tuning.SURFACE_DEPTH), zoneBlueAt(Tuning.SURFACE_DEPTH),
            zoneRedAt(WaterSurface.QUAD_BOTTOM_DEPTH),
            zoneGreenAt(WaterSurface.QUAD_BOTTOM_DEPTH),
            zoneBlueAt(WaterSurface.QUAD_BOTTOM_DEPTH)
        )

        val height = WaterSurface.QUAD_BOTTOM_DEPTH - WaterSurface.QUAD_TOP_DEPTH
        water.draw(
            (worldLeft + worldRight) * 0.5f,
            WaterSurface.QUAD_TOP_DEPTH + height * 0.5f,
            worldRight - worldLeft,
            height
        )
    }

    /**
     * The waterline, as it was before there was a sea: a flat bar. REACHED ONLY when
     * [WaterRenderer] is absent — frame one, or a shader that would not compile — and kept for
     * exactly that reason. Without it those frames would have no cue at all for where banking
     * happens, which is a gameplay cue and not decoration.
     *
     * Original doc follows.
     *
     * The waterline. Without it there is no visual cue for where banking happens.
     *
     * Spans the visible rect horizontally rather than the column, exactly as before: the water's
     * surface does not stop at the rock.
     *
     * Centred rather than corner-anchored ([fillRectCentred]) because the line's defining
     * quantity is the depth it sits ON — [Tuning.SURFACE_DEPTH] — not the depth its top edge
     * starts at. That is the same reason the objects below are centred, and it leaves no stray
     * half-offset in this file for a later reader to copy.
     */
    private fun drawSurfaceLine(surface: Surface, worldLeft: Float, worldRight: Float)
    {
        surface.setDrawColor(surfaceColor)
        surface.fillRectCentred(
            (worldLeft + worldRight) * 0.5f, Tuning.SURFACE_DEPTH,
            worldRight - worldLeft, SURFACE_LINE_METRES
        )
    }

    /**
     * Air vents. Drawn before pearls so a pearl sitting on top of one stays readable, and
     * dimmed rather than hidden once spent — knowing where a used vent was is what lets a
     * player plan the next dive around it.
     */
    private fun drawAirPockets(surface: Surface, sim: DiveSim, cam: Camera)
    {
        val size = Framing.AIR_POCKET_SIZE_METRES
        sim.airPockets.forEach { pocket ->
            if (!cam.showsSquare(pocket.x, pocket.depth, size)) return@forEach
            surface.setDrawColor(if (pocket.usedThisDive) airPocketSpentColor else airPocketColor)
            surface.fillRectCentred(pocket.x, pocket.depth, size, size)
        }
    }

    private fun drawPearls(surface: Surface, sim: DiveSim, cam: Camera, iridescence: IridescenceRenderer?)
    {
        val size = Framing.PEARL_SIZE_METRES
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            if (!cam.showsSquare(pearl.x, pearl.depth, size)) return@forEach
            drawPearlSurface(surface, iridescence, pearl.x, pearl.depth)
        }
    }

    /**
     * The anglerfish's lure. Drawn IDENTICALLY to a pearl, deliberately — in the Abyss,
     * where pearls are the only light, you cannot tell treasure from predator by looking.
     * The tell is motion: a real pearl never moves, this drifts slowly toward the diver.
     *
     * IT IS THE SAME CALL, NOT THE SAME-LOOKING CALL. This used to be a copy of the pearl's
     * three lines, which was fine while a pearl was a flat amber square and stopped being fine
     * the moment a pearl grew a material of its own: two copies of "amber, this size" can be
     * edited apart, and if the lure keeps the old look for even one commit the trap is over —
     * a player who can tell them apart at a glance never gets eaten, and the abyss's whole
     * risk stops existing. So both go through [drawPearlSurface], and
     * `AnglerfishDisguiseTest` fails the build if this method ever draws anything else.
     */
    private fun drawAnglerfish(surface: Surface, sim: DiveSim, cam: Camera, iridescence: IridescenceRenderer?)
    {
        val fish = sim.anglerfish ?: return
        if (!cam.showsSquare(fish.x, fish.depth, Framing.PEARL_SIZE_METRES)) return
        drawPearlSurface(surface, iridescence, fish.x, fish.depth)
    }

    /**
     * ONE pearl-surfaced object at world ([centreX], [depth]) — a real pearl or the lure, and by
     * construction there is no way to tell which from what is drawn.
     *
     * Iridescent through the game's own shader (`shaders/iridescence.frag`), which also gives it
     * its round silhouette: the shader builds a hemisphere normal across the quad and discards
     * the corners, so what a flat `fillRectCentred` here would have made a square comes out as a
     * small nacreous sphere whose colour bands sweep as the diver's torch passes over it.
     *
     * THE FALLBACK IS THE OLD LOOK, NOT A MISSING PEARL. `IridescenceRenderer.of` is null on the
     * first frame (the engine defers `addRenderer`'s init by a frame) and would be null again if
     * the shader ever failed to load. A pearl is the game's currency and the abyss's only light;
     * it may degrade to the flat amber square it has always been, and it may never be absent.
     *
     * The draw colour is set on every call rather than hoisted out of the pearl loop, because
     * `drawTexture` MODULATES by it and the fallback path shares it with every other primitive
     * on this surface — the same trap `drawDiver` documents in the other direction.
     */
    private fun drawPearlSurface(surface: Surface, iridescence: IridescenceRenderer?, centreX: Float, depth: Float)
    {
        val size = Framing.PEARL_SIZE_METRES
        val exposure = pearlAlbedoExposure(depth)
        surface.setDrawColor(
            exposed(pearlColor.red, exposure),
            exposed(pearlColor.green, exposure),
            exposed(pearlColor.blue, exposure),
            1f
        )
        if (iridescence != null)
        {
            // The shader inscribes a DISC in the quad, so the quad is grown to keep the drawn
            // area the pearl has always had — see [IridescenceRenderer.EQUAL_AREA_DISC_SCALE],
            // which has the measurement of what not doing this did to the abyss's exposure.
            val quad = IridescenceRenderer.equalAreaQuad(size)
            iridescence.draw(centreX, depth, quad, quad, IridescentMaterial.PEARL)
        }
        else surface.fillRectCentred(centreX, depth, size, size)
    }

    /**
     * The one textured thing in the game, and the only place two surfaces are handed the same
     * rect.
     *
     * ## The albedo and the normal are ONE argument list, written once
     *
     * A normal-mapped sprite is not one draw call: it is the same world rect submitted twice —
     * the albedo to `main` through `drawTexture`, the normal to GI's `gi_normal_map` through
     * [NormalMapRenderer.drawNormalMap]. `texture.vert:70` and `normal_map.vert:75` compute the
     * identical `(vertexPos - origin) * size * rotate(radians(angle))`, so the two calls take the
     * identical `(x, y, w, h, angle)` + [CENTRE_ORIGIN] tuple. Below, the second call's arguments
     * are a literal copy of the first's — NOT a second derivation of the same numbers. Deriving
     * them twice is precisely the shape of the shipped world-offset-from-HUD bug `6ea1f53` fixed,
     * and here it would be worse than a visible offset: the lighting would slide off the body by
     * a fraction of a sprite and read as bad art rather than as a bug.
     *
     * ## Why not the `NormalMapped` interface
     *
     * The engine offers one, and it is wrong for a sprite sheet: `NormalMapped.kt:32` passes the
     * whole asset to the renderer, which for a `SpriteSheet` means stretching all 42 cells across
     * the quad with no way to say which frame. `NormalMapRenderer` is a plain public
     * `BatchRenderer` method, callable immediate-mode exactly like `GiSceneRenderer.drawLight`
     * already is (see [DiveLighting]), so the frame's own sub-UV `Texture` goes straight in.
     *
     * ## Culling
     *
     * The figure is taller than it is wide, so a square of its HEIGHT strictly contains it and
     * [showsSquare] against that height can only ever be conservative — it can keep a diver that
     * is a fraction of a metre off frame, never drop one that is on it.
     *
     * [normalMaps] is null when GI has not created its normal-map surface (nothing does that
     * today, but a null renderer must degrade to an unlit-but-present diver rather than to no
     * diver). The albedo does not depend on it.
     */
    private fun drawDiver(surface: Surface, sim: DiveSim, cam: Camera, normalMaps: NormalMapRenderer?, aimDegrees: Float)
    {
        val height = Framing.DIVER_HEIGHT_METRES
        val width = DiverSprite.widthForHeight(height)
        val angle = DiverSprite.bodyAngleFor(aimDegrees)
        if (!cam.showsSquare(sim.x, sim.depth, height)) return

        // Not ready yet (the upload is asynchronous — see DiverSprite.sheetsReady), or missing
        // entirely. Fall back to the placeholder rectangle at the same world footprint so the
        // diver is never simply absent, and let sheetsReady do the complaining.
        if (!DiverSprite.sheetsReady())
        {
            surface.setDrawColor(diverColor)
            surface.fillRectCentred(sim.x, sim.depth, width, height, angle)
            return
        }

        val frame = DiverSprite.currentFrame

        // White, fully opaque: drawTexture MODULATES the sprite by the surface's current draw
        // colour, and the previous call in this frame left it set to the anglerfish's amber.
        // Without this the diver would be tinted by whatever was drawn before him.
        surface.setDrawColor(1f, 1f, 1f, 1f)
        surface.drawTexture(
            DiverSprite.diffuseFrame(frame),
            sim.x, sim.depth, width, height, angle, CENTRE_ORIGIN, CENTRE_ORIGIN
        )

        // The copied argument list. If you change one of these five numbers, change it above.
        normalMaps?.drawNormalMap(
            DiverSprite.normalFrame(frame),
            sim.x, sim.depth, width, height, angle, CENTRE_ORIGIN, CENTRE_ORIGIN
        )
    }

    /**
     * THE PEARL'S ALBEDO EXPOSURE — how far the pearl's own colour is stopped down before the GI
     * multiply, so that its material survives its own light.
     *
     * ## The problem, measured
     *
     * A pearl sits at the centre of its own light source, and `GlobalIlluminationSystem`
     * multiplies `mainSurface` by the light map. Over the brightest 0.05% of pixels in a pinned
     * frame — the pearl cores — the shipped build read:
     *
     *      10 m Shallows   mean RGB (182, 141,  63)   chroma 0.667    0.0% at >= 250
     *      70 m Twilight   mean RGB (234, 210,  85)   chroma 0.638    0.0%
     *     140 m Abyss      mean RGB (246, 241, 187)   chroma 0.240   27.0%
     *
     * The pearl does not merely brighten with depth, it goes WHITE, because
     * [DiveLighting.pearlIntensityForDepth] runs 0.6 in the Shallows to 4.0 in the Abyss and the
     * product leaves the ACES shoulder with no hue left. An iridescent surface authored under
     * those conditions is invisible on exactly the objects it is for.
     *
     * ## Why this and not an emitter shape
     *
     * An annulus emitter was built and measured and does not work AT ANY HOLE RADIUS — see
     * [LightEmitter]'s class doc, which has the numbers and the `radiance_cascades.frag` reading
     * behind them. In short: `radius = 0` means there is no distance term, so a probe inside a
     * ring receives exactly what a probe inside a disc does. No shape can spare the body.
     *
     * ## What this does instead, and why it is depth-STABLE rather than merely darker
     *
     * The light a pearl's own body receives is dominated by its own emitter, whose intensity is
     * an existing, pure, already-tested function of depth. So the albedo is stopped down by the
     * inverse of that intensity, normalised to the deepest zone at which the material was
     * measured to still read (Twilight, 1.8 — 70 m above reads at chroma 0.638 with nothing
     * clipped). `exposure x intensity` is then constant with depth, and the material reads the
     * same in the Kelp as in the Abyss instead of 6.7x differently.
     *
     * Clamped at 1 so it can only ever REMOVE albedo. The Shallows and the Kelp already read;
     * brightening them would be a change nobody asked for, and it would push them toward the same
     * shoulder this exists to get off.
     *
     * ## What it does NOT touch
     *
     * The emitter. `DiveLighting.drawPearlLights` is character-identical to what it was: what a
     * pearl EMITS — and therefore what lights the water, the diver and every neighbouring pearl,
     * and therefore the Abyss's readability that `d343886` and `ad2bc35` established — is
     * unchanged. This is the albedo of the pearl's own 1.2 m disc and nothing else.
     *
     * The anglerfish's lure gets it too, necessarily and by construction: both go through
     * [drawPearlSurface], and its depth is the fish's depth exactly as a pearl's is its own.
     */
    internal fun pearlAlbedoExposure(depth: Float): Float
    {
        val here = DiveLighting.pearlIntensityForDepth(depth)
        if (!(here > 0f)) return 1f // NaN or a degenerate table: draw the pearl unmodified
        val full = (PEARL_EXPOSURE_REFERENCE_INTENSITY / here).coerceAtMost(1f)
        return full.pow(PEARL_EXPOSURE_STRENGTH)
    }

    /**
     * The pearl-light intensity the exposure above is normalised to: the value at the midpoint of
     * the deepest zone whose pearls were measured to keep their hue (Twilight — 70 m reads at
     * chroma 0.638 with nothing clipped, against the Abyss's 0.240 with 27% clipped).
     *
     * Asked of [DepthBlend] and [DiveLighting] rather than typed as 1.8, so that re-tuning the
     * pearl-light table moves this with it instead of silently leaving the reference pointing at
     * an intensity no zone has any more.
     */
    /**
     * How much of the full compensation to apply, as an exponent: 0 is the old behaviour, 1 is
     * "make `exposure x intensity` exactly constant with depth". THIS IS THE ONE KNOB, and it is
     * a trade with a measured curve rather than a value with a right answer.
     *
     * Full compensation makes the material read perfectly and costs too much: the pearls' bloom
     * halos were carrying most of the Abyss's visible light, and removing them takes the water
     * and the diver down with them. Captured at 140 m, 16:9, pinned stationary diver, against a
     * same-build control pair that was BIT-IDENTICAL (0 differing pixels of 5.76 M):
     *
     *     strength   frame mean   pearl core RGB        >= 250    chroma
     *        0.0       9.675      (246, 241, 187)       27.0%      0.240   white, no material
     *        0.5       3.904      (244, 231, 137)        3.3%      0.438   gold, material reads
     *        1.0       1.395      (234, 209,  71)        0.0%      0.696   full material, dark water
     *
     * (chroma = mean `(max - min) / max` over the brightest 0.05% of pixels, i.e. the pearl
     * cores. For scale, an uncompensated pearl at 70 m — which nobody has complained about —
     * reads 0.638, and at 10 m 0.667.)
     *
     * 0.5 is chosen as the point where the material is unambiguously visible and the Abyss still
     * has a glow to read the water and the diver by. It is a judgement, the numbers either side
     * of it are above, and moving it is a one-line change with no other consequence — which is
     * the whole reason it is expressed as a strength rather than baked into the reference.
     */
    internal const val PEARL_EXPOSURE_STRENGTH = 0.5f

    internal val PEARL_EXPOSURE_REFERENCE_INTENSITY =
        DiveLighting.pearlIntensityForDepth(DepthBlend.zoneMidpoint(dive.Zone.TWILIGHT))

    /**
     * One channel of a draw colour, stopped down by [exposure] IN LINEAR SPACE.
     *
     * The round trip through [srgbToLinear]/[linearToSrgb] is not ceremony. `setDrawColor` packs
     * an sRGB byte which `iridescence.vert` (and `texture.vert`) then decode with the ~2.4 power
     * curve, so multiplying the sRGB value by 0.45 would scale the LINEAR value by 0.45^2.4 =
     * 0.147 — three times more light removed than asked for, and wrong by a different factor at
     * every exposure. The multiply has to happen on the side the GI blend measures.
     */
    internal fun exposed(channel: Float, exposure: Float): Float =
        linearToSrgb(srgbToLinear(channel) * exposure)

    // --- Continuous zone-band colour, exposed for testing (see DiveRendererTest) ---------

    internal fun zoneRedAt(depth: Float): Float = DepthBlend.blend(depth, zoneRed)
    internal fun zoneGreenAt(depth: Float): Float = DepthBlend.blend(depth, zoneGreen)

    /**
     * Blue carries the reflectance floor for the whole band colour — see [GI_REFLECTANCE_FLOOR]
     * for what the floor is and what happens when the water falls through it.
     *
     * WHY BLUE ALONE, rather than scaling all three channels together. The floor is a
     * constraint on the LENGTH of the linear RGB vector, so it can be met by any channel; the
     * choice decides what the deep water then looks like. Scaling all three preserves hue
     * exactly but pays for the floor in the two channels the eye weights most, and lands the
     * abyss on a washed-out slate around (19, 26, 37) with a relative luminance of 0.011 —
     * visibly paler than the water two zones above it. Spending the whole deficit on blue
     * instead costs 0.0722 of luminance per unit of length against green's 0.7152, which makes
     * it both the darkest-looking way to satisfy the floor (abyss luminance 0.0029, ~4x
     * darker) and the one that keeps deep water reading as water: red and green go on falling
     * to near-black exactly as the zone table asks, and only blue is held up, so the abyss
     * settles at a saturated navy rather than a grey wash.
     *
     * Continuous by construction: the lift is a `max` against the curve's own value, so at the
     * depth where the floor starts to bite (~93 m) the two agree and there is no step — which
     * is the entire point, since a step here is the bug.
     */
    internal fun zoneBlueAt(depth: Float): Float =
        floorBlueForReflectance(zoneRedAt(depth), zoneGreenAt(depth), DepthBlend.blend(depth, zoneBlue))

    /**
     * The blue channel raised, if required, to the smallest value that puts (r, g, blue) at
     * [MIN_REFLECTANCE_LENGTH] in linear space. Pure and allocation-free: called once per
     * gradient strip, i.e. a couple of hundred times a frame.
     */
    internal fun floorBlueForReflectance(r: Float, g: Float, blue: Float): Float
    {
        val linearRed = srgbToLinear(r)
        val linearGreen = srgbToLinear(g)
        val deficit = MIN_REFLECTANCE_LENGTH * MIN_REFLECTANCE_LENGTH - linearRed * linearRed - linearGreen * linearGreen
        if (deficit <= 0f) return blue // red and green already carry the whole floor on their own
        val neededBlue = sqrt(deficit)
        return if (neededBlue <= srgbToLinear(blue)) blue else linearToSrgb(neededBlue)
    }

    /** Length of a draw colour in the linear space the GI blend measures it in — see [GI_REFLECTANCE_FLOOR]. */
    internal fun reflectanceLength(r: Float, g: Float, b: Float): Float
    {
        val lr = srgbToLinear(r)
        val lg = srgbToLinear(g)
        val lb = srgbToLinear(b)
        return sqrt(lr * lr + lg * lg + lb * lb)
    }

    /**
     * The exact transfer function the engine applies to a draw colour on its way to the
     * surface — see `unpackAndConvert` in `shaders/renderers/texture.vert`. Reproduced rather
     * than approximated with a plain `pow(c, 2.2)`, because [floorBlueForReflectance] has to
     * agree with the shader about which side of the floor a colour lands on, and near-black is
     * exactly where the 0.055 offset in the sRGB curve and a pure power law disagree most.
     *
     * Note the threshold is the engine's: the standard sRGB decode compares 0.04045 against
     * the ENCODED value and reserves 0.0031308 for the linear one. The engine compares
     * 0.0031308 against the encoded value, so its linear segment covers a much smaller range
     * than the standard's. Mirrored here on purpose — matching the engine matters, matching
     * the specification does not.
     */
    internal fun srgbToLinear(c: Float): Float =
        if (c <= SRGB_LINEAR_CUTOFF) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** Inverse of [srgbToLinear], for turning a required linear value back into a draw colour. */
    internal fun linearToSrgb(l: Float): Float =
        if (l <= SRGB_LINEAR_CUTOFF / 12.92f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f

    private const val SRGB_LINEAR_CUTOFF = 0.0031308f
}
