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
 *   air vents = the same iridescence shader, but shaped by [OxygenSprite]'s 96-frame baked
 *               normal sheet instead of the analytic hemisphere — and that sheet goes to GI's
 *               `gi_normal_map` as well, so a vent is the third object here submitting one
 *               world rect to two surfaces
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
    /**
     * Per-zone anchor colours for the zone bands, indexed by Zone.ordinal. Blended continuously
     * across depth by [DepthBlend] rather than drawn as flat per-zone blocks — see
     * [drawZoneBands]. Kept as parallel FloatArrays (not a `Map<Zone, Color>`) so blending is
     * zero-allocation: `DepthBlend.blend` takes primitive floats in, floats out.
     *
     * # THE DEEP END WAS LIFTED — a 2.9x ramp in blue where it used to be 15x
     *
     * Blue was `(0.52, 0.36, 0.22, 0.12, 0.035)`, and red and green fell with it. That was the
     * ALBEDO half of a double count: the frame is `albedo x irradiance`, the composite is a
     * MULTIPLY, and [DiveLighting]'s ambient was ALSO ramping to near-zero (0.68 -> 0.003), so
     * the deep water fell off QUADRATICALLY — four orders of magnitude, with 98.9% of a measured
     * 140 m frame at or below 2/255 and the open water at a flat 0.000.
     *
     * The fix is a SPLIT, and each half does a different job (`DiveLighting.AMBIENT_FLOOR_RED`
     * carries the other half and the reasoning behind the split):
     *
     *  - these colours keep ramping, because a strip is coloured at ITS OWN depth and this table
     *    is therefore the ONLY thing producing the water's visible vertical gradient. Flatten it
     *    and the water becomes one colour from the surface to the floor;
     *  - the global ambient gets a FLOOR, because it is set once per frame from the DIVER's
     *    depth and is what makes objects exist at all.
     *
     * ## THE VALUES
     *
     * The Shallows and the Kelp are UNCHANGED, to the digit — verified as byte-identical water in
     * a 20 m capture before and after. The owner's complaint was about the deep only, and
     * brightening water that already reads correctly is a regression, not a fix.
     *
     * The bottom three rise as far as the DEPTH CUE allows and no further, which is what fixes the
     * value rather than taste. `every zone still reads as its own colour at its own midpoint`
     * requires the Abyss to stay at least 10x darker than the Shallows in relative luminance; this
     * table measures **11.98x**, so a blue much above 0.18 would fail it. That test and this table
     * are two ends of the same argument: the deep must be dimmer, and it must not be a void.
     *
     * RED AND GREEN ARE SCALED TO HOLD THE HUE, NOT RE-TUNED BY EYE. Each zone's red:green:blue
     * ratio is carried over exactly — Trench 1 : 4 : 8 and Abyss 1 : 3.75 : 8.75 are the ratios
     * the old table had, and the Twilight's 1 : 4 : 7.335 matches its old 1 : 4 : 7.333. So the
     * water keeps shifting toward blue with depth at precisely the rate it did; only the level
     * moved. `DiveRendererTest` asserts the ratios' ORDERING rather than the values, so a re-tune
     * that quietly drains the hue fails.
     *
     * ## THE COLOUR GRADE'S MODEL, CORRECTED — the deep water is clamped, not dimmed
     *
     * `2026-08-13-deep-water-lighting.md` §2d multiplies the DRAW values together and grades that.
     * It omits the sRGB round trip, and the round trip is the whole of the deep: `setDrawColor`
     * packs an sRGB byte that `texture.vert` decodes with the ~2.4 power curve, so the multiply is
     * in LINEAR space and a draw blue of 0.18 is a linear 0.027. The corrected chain, validated
     * against three real captures spanning 0 to 87/255 (model 95.6, 0.00, 3.3 against measured 87,
     * 0, 2):
     *
     *     linear = srgbToLinear(albedo) * srgbToLinear(ambient)
     *     linear *= 2^exposure - 1                              // 1.1  -> x1.1435
     *     linear  = (linear - 0.5) * (1 + 0.05*(contrast-1)) + 0.5   // 1.3 -> SUBTRACTS 0.00739
     *     out     = srgbEncode(ACES(linear))                    // negative clamps to pure black
     *
     * The contrast term is a SUBTRACTION of a constant near black, not a scaling, which is why the
     * deep water is a hard clamp and not a ramp. At these values the Twilight's water clears it
     * (+0.0063 -> 6.6/255, measured 7) and the Trench just does (+0.0014 -> 1.1/255), while the
     * ABYSS WATER IS STILL EXACTLY BLACK (-0.0024). Clearing it there would need a draw blue near
     * 0.31 — the Kelp's 0.36 — i.e. the gradient deleted. The lever that would move it is
     * `contrast`, which is the one-world-model plan's LAST step and not this one.
     *
     * ## THE REFLECTANCE FLOOR NO LONGER BITES, AND THAT IS THE POINT
     *
     * Re-checked rather than assumed, and it explains why the previous ambient floor measured as a
     * no-op. THE OLD TABLE'S DEEP END WAS NEVER WHAT WAS DRAWN: its Abyss anchor had a linear
     * length of 0.00325, six times under [GI_REFLECTANCE_FLOOR], so [floorBlueForReflectance]
     * lifted blue 0.035 -> 0.1598 and the deepest water's albedo was the FLOOR'S choice rather
     * than the table's. Any ambient floor was multiplying an albedo that was already as high as it
     * was going to get.
     *
     * This table's minimum raw linear length over the whole reachable column is 0.02811 (at
     * 135 m), above the 0.022 target, so the lift is now inert everywhere and the table says what
     * the water looks like. `every zone band colour clears the GI reflectance floor once quantized`
     * still sweeps the column and is what proves it — [floorBlueForReflectance] stays as the guard
     * it was written to be, and `the reflectance floor leaves colours that already clear it
     * untouched` still exercises its comparison branch at 90 m.
     */
    private val zoneRed   = Look.ZONE_RED
    private val zoneGreen = Look.ZONE_GREEN
    private val zoneBlue  = Look.ZONE_BLUE

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

    /**
     * THE PEARL'S WARM AMBER, and it is `internal` rather than `private` for one reason: `MotesTest`
     * asserts the marine snow's cool blue is a long way from it on the colour wheel. That is the
     * §1.1 constraint in `docs/superpowers/specs/2026-08-11-outstanding-work.md` — the anglerfish's
     * lure renders identically to a pearl and the only tell is motion, so ambient glowing points
     * must not be mistakable for one. A test that compares against a TRANSCRIPTION of this number
     * would pass forever after somebody changed this line, which is the failure it exists to catch.
     */
    internal val pearlColor = Color(Look.PEARL_RED, Look.PEARL_GREEN, Look.PEARL_BLUE)
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

    /**
     * How much brighter the BAKED ROCK is than [wallColor] — 2.0, and it is the bake's number, not
     * one of ours. `tools/build_backdrop.py`'s `LUMINANCE_FACTOR` is the target the rock's gain is
     * solved for: "target luminance 2.00*wallColor", and the run prints the result it reaches
     * (`baked mean luminance 0.06815 against wallColor's 0.03408`).
     *
     * `RockFaceTest` re-reads it out of `build_backdrop.py` rather than trusting this copy, the
     * same way the texel counts are re-derived from the committed PNG's IHDR. Two numbers that
     * must agree are one number too many.
     */
    internal const val ROCK_LUMINANCE_FACTOR = 2.0f

    /**
     * The flat stand-in for rock ABOVE the waterline, outward of the crest sprite — and the whole
     * of it is that it must not be [wallColor].
     *
     * ## A ONE-PIXEL SEAM, MEASURED
     *
     * The headland fill (see [drawColumnWalls]) abuts the tiling wall along the whole of
     * [RockFace.WALL_TOP_DEPTH], from the crest's outward edge to the side of the frame. Drawn in
     * `wallColor` it was HALF the brightness of the rock underneath it, because the bake solves
     * the rock's mean to [ROCK_LUMINANCE_FACTOR] times that colour — so the join was a horizontal
     * step running the full width of the fill. The owner: *"there is still like a 1 pixel seam
     * between the sides of the screen and the tops"*. Captured at 0 m and 2.84:1, at world
     * -75.3 m: `(0, 0, 0)` above the join against `(1, 3, 6)` below it.
     *
     * `wallColor` is right where it is used BELOW the waterline — there it shows through the
     * cliff's alpha notches and stands in for the unlit gap between tiles, not for rock.
     *
     * ## WHY THE EXPOSURE GOES THROUGH [exposed]
     *
     * The factor is a ratio of LINEAR luminances, and `setDrawColor` packs an sRGB byte that
     * `texture.vert` decodes with the ~2.4 power curve. Multiplying the sRGB value by 2 would
     * scale the linear value by far more than 2. [exposed] does the round trip, and is already
     * the answer to this exact mistake for the pearls.
     */
    internal val headlandColor = Color(
        exposed(wallColor.red, ROCK_LUMINANCE_FACTOR),
        exposed(wallColor.green, ROCK_LUMINANCE_FACTOR),
        exposed(wallColor.blue, ROCK_LUMINANCE_FACTOR)
    )

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

        drawColumnWalls(surface, cam, normalMaps, worldLeft, worldTop, worldRight, worldBottom)
        drawAirPockets(surface, sim, cam, iridescence, normalMaps)
        drawPearls(surface, sim, cam, iridescence, normalMaps)
        drawAnglerfish(surface, sim, cam, iridescence, normalMaps)
        drawDiver(surface, sim, cam, normalMaps, aimDegrees)

        // THE MARINE SNOW, LAST, AND ON THIS SURFACE ON PURPOSE.
        //
        // It had a surface of its own until the owner said *"put them back on main so GI darkens
        // them too"* — see `Motes`' class doc for the measurement that prompted it (a field
        // exempt from the light map was the brightest thing in the Abyss). Being here is the whole
        // point: the multiply that darkens the water darkens the motes with it, and only the
        // glowing quarter, which lights its own body, survives the deep.
        //
        // LAST, so a mote is in front of the rock, the pearls and the diver — it is suspended
        // matter between the camera and the world, which is what marine snow in front of a lens
        // actually is. It is drawn AFTER the diver rather than before for that reason and not by
        // accident; at MOTE_ALPHA 0.25 and a sub-metre quad, a speck crossing his silhouette is
        // the effect rather than a defect.
        Motes.render(surface, cam)
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
     * this the boundary is invisible: [CameraRig] fits the frame to the screen's larger dimension,
     * so on a 16:9 booth screen the visible half-width is ~49m versus the 40m column and the
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
     * MEASURED, at 12 m and 16:9 (3200x1800), mean over a 40-row band across the boundary — on
     * the art as it was then, whose empty margin was 1.31 m rather than today's 2.715 m:
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
     *
     * ## THE EDGE AND THE BODY ARE TWO DIFFERENT TEXTURES, AND THE FRAME EDGE IS THE BODY'S JOB
     *
     * The owner: *"The rocks should ALWAYS be exactly starting at the edge of the screen.
     * ALWAYS."* [RockFace]'s class doc has the measurement, the five failed attempts and the
     * reason no anchor on the wall's own art could ever satisfy it — in one line, the art is not
     * uniform across a tile, so tiling it makes the frame edge a function of a MODULUS.
     *
     * The order below is the whole structure, and each step is where it is for a reason:
     *
     *  1. the flat [wallColor] backing, below the waterline, exactly as before;
     *  2. the BODY ([drawRockBody]) — opaque at every texel, tiled outward from
     *     [RockFace.BODY_INNER_HALF_WIDTH] with a whole-tile count rounded UP, in TWO calls: one
     *     for the band above the waterline and one for the wall below it. Not one tall quad: the
     *     v lattice is anchored on [RockFace.WALL_TOP_DEPTH], so a single quad covering both would
     *     have to start 40 m above that line and would paint 17 m of opaque rock across the sky;
     *  3. the EDGE ([drawRockEdge]) — the wall's own art, ONE tile, never tiled horizontally,
     *     carrying the ragged silhouette onto ±[Tuning.COLUMN_HALF_WIDTH]. AFTER the body, so
     *     what shows through its alpha notches is the water and the sky, not the body;
     *  4. the CREST ([drawCrest]), last, so the summit draws over everything it caps.
     *
     * The width gates that used to gate each of these are gone. The body's own `columns` is zero
     * when there is no frame left over, and at 4:3 — where the visible half-width IS
     * [Tuning.COLUMN_HALF_WIDTH] — every texel of the edge and the crest that would be on screen
     * is transparent by construction (`RockFaceTest.the inward shift draws no rock inside the play
     * column`), so drawing them costs two quads and shows nothing.
     */
    private fun drawColumnWalls(
        surface: Surface,
        cam: Camera,
        normalMaps: NormalMapRenderer?,
        worldLeft: Float,
        worldTop: Float,
        worldRight: Float,
        worldBottom: Float
    )
    {
        // Per SIDE, not one half-width. [CameraRig] fixes the camera's world x at 0, so these are
        // the same number today; taking them separately costs a negation and means that if a
        // horizontal camera target is ever added it cannot leave one side of the frame short of
        // rock while the tests, which know nothing about the camera, stay green.
        val leftHalfWidth = -worldLeft
        val rightHalfWidth = worldRight

        if (!RockFace.ready())
        {
            drawFlatWalls(surface, worldLeft, worldTop, worldRight, worldBottom)
            return
        }

        // 1. THE BACKING, below the waterline, from BACKING_HALF_WIDTH outward — see this
        // method's doc for the "it's black behind the rock" defect that cut it back to there.
        val backingTop = maxOf(worldTop, RockFace.WALL_TOP_DEPTH)
        val backingHeight = worldBottom - backingTop
        if (backingHeight > 0f)
        {
            surface.setDrawColor(wallColor)
            val leftBacking = leftHalfWidth - RockFace.BACKING_HALF_WIDTH
            val rightBacking = rightHalfWidth - RockFace.BACKING_HALF_WIDTH
            if (leftBacking > 0f)
                surface.fillRect(worldLeft, backingTop, leftBacking, backingHeight)
            if (rightBacking > 0f)
                surface.fillRect(RockFace.BACKING_HALF_WIDTH, backingTop, rightBacking, backingHeight)
        }

        // 2. THE BODY. White and opaque first: drawTexture MODULATES by the surface's current
        // draw colour, which is still wallColor from the backing above.
        surface.setDrawColor(1f, 1f, 1f, 1f)

        // Above the waterline: the summit's shoulder down to the join, clipped to the frame so the
        // quad shrinks as the camera drops rather than being drawn and clipped by the GPU.
        val aboveTop = maxOf(worldTop, RockFace.CREST_SHOULDER_DEPTH)
        drawRockBody(surface, normalMaps, leftHalfWidth, aboveTop, RockFace.WALL_TOP_DEPTH, LEFT_SIDE)
        drawRockBody(surface, normalMaps, rightHalfWidth, aboveTop, RockFace.WALL_TOP_DEPTH, RIGHT_SIDE)

        // Below it: the same whole number of whole tiles the edge runs, so the two share a v
        // lattice and their horizontal joins cannot fall at different depths.
        val wallBottom = RockFace.WALL_TOP_DEPTH +
            RockFace.tileRows(worldBottom) * RockFace.TILE_HEIGHT_METRES
        drawRockBody(surface, normalMaps, leftHalfWidth, RockFace.WALL_TOP_DEPTH, wallBottom, LEFT_SIDE)
        drawRockBody(surface, normalMaps, rightHalfWidth, RockFace.WALL_TOP_DEPTH, wallBottom, RIGHT_SIDE)

        // 3. THE EDGE, over the body, so the water shows through its notches.
        drawRockEdge(surface, normalMaps, worldBottom, side = LEFT_SIDE)
        drawRockEdge(surface, normalMaps, worldBottom, side = RIGHT_SIDE)

        // 4. The crest, LAST, so it draws over the top of the wall it caps rather than under it.
        drawCrest(surface, cam, normalMaps, side = LEFT_SIDE)
        drawCrest(surface, cam, normalMaps, side = RIGHT_SIDE)
    }

    /**
     * THE DEGRADATION: what the column looks like when the rock textures have not uploaded —
     * frame one, and any display where the files are missing from the classpath. Flat slabs from
     * ±[Tuning.COLUMN_HALF_WIDTH] to the frame edge, which is exactly the wall `0f07303` shipped
     * and reads as entirely deliberate. ([RockFace.ready] is what logs that it happened.)
     *
     * TWO COLOURS, SPLIT AT THE WATERLINE, and that split is the whole reason this is not one
     * fill. Below [RockFace.WALL_TOP_DEPTH] the slab stands in for unlit rock seen through water,
     * which is what [wallColor] was tuned against. Above it the slab stands in for the cliff top
     * against the sunset, where [headlandColor] is the rock's own baked mean luminance — the bake
     * solves the rock to [ROCK_LUMINANCE_FACTOR] times [wallColor], so filling the whole height in
     * `wallColor` would make the above-water half half as bright as the rock it replaces. That was
     * a measured defect in its own right when the live path filled flat (`41849a7`, the owner:
     * *"there is still like a 1 pixel seam between the sides of the screen and the tops"*), and
     * there is no reason to reintroduce it in the fallback.
     */
    private fun drawFlatWalls(
        surface: Surface,
        worldLeft: Float,
        worldTop: Float,
        worldRight: Float,
        worldBottom: Float
    )
    {
        val leftSlab = -Tuning.COLUMN_HALF_WIDTH - worldLeft
        val rightSlab = worldRight - Tuning.COLUMN_HALF_WIDTH
        if (leftSlab <= 0f && rightSlab <= 0f) return

        val skyHeight = RockFace.WALL_TOP_DEPTH - worldTop
        if (skyHeight > 0f)
        {
            surface.setDrawColor(headlandColor)
            if (leftSlab > 0f) surface.fillRect(worldLeft, worldTop, leftSlab, skyHeight)
            if (rightSlab > 0f) surface.fillRect(Tuning.COLUMN_HALF_WIDTH, worldTop, rightSlab, skyHeight)
        }

        val seaTop = maxOf(worldTop, RockFace.WALL_TOP_DEPTH)
        val seaHeight = worldBottom - seaTop
        if (seaHeight > 0f)
        {
            surface.setDrawColor(wallColor)
            if (leftSlab > 0f) surface.fillRect(worldLeft, seaTop, leftSlab, seaHeight)
            if (rightSlab > 0f) surface.fillRect(Tuning.COLUMN_HALF_WIDTH, seaTop, rightSlab, seaHeight)
        }
    }

    /**
     * THE CLIFF'S CREST — what stops the tiling wall at the waterline, and the silhouette the sky
     * is seen behind.
     *
     * The owner: *"we still don't use the top rock to stop the rock faces at the top"*. Before
     * this the tile ran off the top of the frame, so above the waterline the cliff was simply more
     * cliff — which read as a column of rock with no end rather than as a headland standing in the
     * sea. The crest sprite gives the column a top; [RockFace.WALL_TOP_DEPTH] is where the wall
     * now stops and where this starts.
     *
     * ## The right-hand one is a DIFFERENT TEXTURE, not a different angle
     *
     * The wall used to get its right-hand copy from a half turn, which is a horizontal
     * mirror and a vertical flip together. A wall tile survives being flipped vertically; a summit
     * does not. So both crests are drawn with NO rotation at all — as both walls now are — and the right one
     * uses a texture that was mirrored IN THE BAKE, where the normal map's x component could be
     * negated exactly (`tools/backdrop/mirror.py`, and `RockFaceTest` re-derives it from the
     * committed base PNGs). Mirroring at the draw site is not available: `drawNormalMap` takes no
     * uv arguments, so a uv swap would mirror the albedo and leave every bump lit from the wrong
     * side.
     *
     * ## Culling
     *
     * [showsSquare] against the larger side, exactly as [drawDiver] does — the codebase has one
     * answer to "is this on screen" and it is the engine's. It is conservative here, and more so
     * than it used to be: the larger side is now the quad's full [RockFace.TOP_HEIGHT_METRES],
     * whose top half is the transparent padding the crest is baked with, so the square tested is
     * far taller than the summit it contains. Conservative is the safe direction — it can keep a
     * crest that is metres off frame, never drop one that is on it — and it costs one quad that
     * draws nothing for a while after the summit has left the frame.
     *
     * The two draws are ONE argument list written twice, exactly as [drawDiver]'s and
     * [drawRockEdge]'s are.
     */
    private fun drawCrest(
        surface: Surface,
        cam: Camera,
        normalMaps: NormalMapRenderer?,
        side: Float
    )
    {
        val width = RockFace.CREST_WIDTH_METRES
        val height = RockFace.TOP_HEIGHT_METRES

        val centreX = side * (RockFace.QUAD_INNER_HALF_WIDTH + width * 0.5f)
        val centreY = RockFace.WALL_TOP_DEPTH - height * 0.5f

        if (!cam.showsSquare(centreX, centreY, max(width, height))) return

        val diffuse = if (side == RIGHT_SIDE) RockFace.topMirrorDiffuse else RockFace.topDiffuse
        val normal = if (side == RIGHT_SIDE) RockFace.topMirrorNormal else RockFace.topNormal

        // TILING IS 1, AND THAT IS THE WHOLE OF THIS SPRITE'S CONTRACT — see
        // RockFace.CREST_WIDTH_METRES. The wall's identical-looking call passes `columns` here
        // because a wall tile repeats; a summit does not, and repeating it drew a picket fence of
        // spires one TILE_WIDTH_METRES apart. Do not "fix" a bare frame edge by putting a count
        // back: what continues the cliff outward is the flat top in [drawColumnWalls].
        surface.drawTexture(
            diffuse,
            centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN,
            0f, 0f, 0f, 1f, 1f, 1f, 1f
        )

        // The copied argument list. Same rect, same angle, same origin, same tiling.
        normalMaps?.drawNormalMap(
            normal,
            centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN,
            1f, 1f
        )
    }

    /**
     * The right-hand wall is the same cliff MIRRORED IN THE BAKE, and that is the only difference
     * between the two calls. Both are drawn at angle 0.
     *
     * [RockFace]'s art is a LEFT wall: solid stone on its u = 0 side, ragged alpha edge on its
     * u = 1 side. The left wall can use it as it stands, with u = 1 landing on the column
     * boundary. The right wall needs that edge on its own inner side, i.e. mirrored.
     *
     * ## IT USED TO BE A HALF TURN, AND THAT PUT ONE WALL UPSIDE DOWN
     *
     * The original argument was sound as far as it went, and is worth keeping because it rules
     * out the two obvious alternatives:
     *
     *  - `NormalMapRenderer.drawNormalMap` takes no uv arguments at all, so a `uMin`/`uMax` swap
     *    would mirror the albedo and leave the normals unmirrored. The lighting would then be lit
     *    from the wrong side of every bump on one wall only.
     *  - A negative width flips the quad geometrically but leaves `normalRotation` alone, with
     *    the same result, and additionally reverses the triangles' winding.
     *  - `normal_map.vert` builds `normalRotation = rotMatrix(rotation + cameraAngle)`, so a
     *    rotation is the ONE transform the engine applies to the geometry and to the normal
     *    vectors together — at the DRAW SITE.
     *
     * The conclusion it reached does not follow, because there is a third place to mirror: the
     * BAKE. `98bbcb0` had already gone there for the crest, which cannot survive a half turn (a
     * summit upside down is not a summit). So the two sides of the column were being transformed
     * differently — the crest by a horizontal mirror, the wall by a mirror AND a vertical flip —
     * and on the right-hand side an upside-down wall ran up to a right-way-up summit. The owner,
     * on a capture: *"they should be placed from top to bottom to ensure the seams line up"*.
     *
     * Both are baked mirrors now (`tools/backdrop/mirror.py`, `RockFace.mirrorDiffuse`), so each
     * side of the column is one consistent horizontal mirror of the other, top and side together.
     *
     * WHAT THAT COSTS, since the old note called it a bonus: the half turn also flipped v, so the
     * right wall showed the tile upside down as well as mirrored, which broke up the symmetry of
     * an exact 40 m mirror down both sides of the frame for free. An exact mirror is more
     * conspicuous. That is the price of the seams lining up, and the seams win — the alternative
     * is a join that cannot be made continuous at all.
     */
    private const val LEFT_SIDE = -1f
    private const val RIGHT_SIDE = 1f

    /**
     * THE EDGE: the wall's own art, ONE tile wide, drawn EXACTLY ONCE per side and NEVER tiled
     * horizontally. It carries the silhouette; it does not carry the frame edge.
     *
     * ## The cue this has to keep carrying
     *
     * The reason a wall is drawn at all has not changed since `0f07303`. [CameraRig] fits the
     * frame to the screen's larger dimension, so a 16:9 booth panel shows about 49 m of half-width
     * against the column's 40 m, and without a visible boundary the diver stops dead in open
     * water with no visual reason — the stick reads as broken rather than as blocked.
     *
     * SO THE ROCK MUST STAY WHERE THE DIVER STOPS. The walls are symmetric at
     * +-[Tuning.COLUMN_HALF_WIDTH] because that is where the simulation actually halts him, and
     * the two must not drift apart: the cliff's ragged edge is the promise, and `dive/` is what
     * keeps it. Moving the rock inward or outward for looks — or putting a cliff down one side
     * only, as the mockup does — silently breaks the cue, and the failure is a player wrestling
     * with a stick they now believe is faulty.
     *
     * WHICH IS WHY THE QUAD IS ANCHORED ON [RockFace.QUAD_INNER_HALF_WIDTH] AND NOT ON THE
     * BOUNDARY ITSELF. The last 139 of the tile's 614 texel columns hold no alpha at all, so a quad
     * whose u = 1 edge sat on the boundary put the cliff's furthest-reaching texel 2.715 m short of
     * it. That was invisible while an opaque slab filled the gap and becomes a uniform channel of
     * un-enterable water the moment the water shows through. Shifting the quad in by exactly that
     * empty margin restores it and then some: the promontories touch the boundary, the bays
     * between them are water, and because the margin really is empty no rock is ever drawn inside
     * the column.
     *
     * ## `uTiling` IS 1, AND THE FRAME EDGE IS SOMEBODY ELSE'S JOB
     *
     * It used to be `tileColumns(wallWidth + EDGE_INSET_METRES)` — as many tiles as it took to
     * reach the side of the frame. That is what made the frame edge a function of
     * `(visibleHalfWidth − anchor) mod TILE_WIDTH_METRES`, and the art is EMPTY over 2.715 m of
     * that period and RAGGED over another 4.121 m, so at 16:9 the outermost pixel column of the
     * screen was empty. See [drawColumnWalls] and [RockFace]'s class doc. Drawn once, this quad's
     * non-solid region is one FIXED world interval — `[37.285, 44.121]` — at every aspect ratio,
     * which is what makes it a silhouette rather than a lottery. [drawRockBody] covers everything
     * outward of it.
     *
     * Vertically it starts at [RockFace.WALL_TOP_DEPTH] — a fixed WORLD depth, just under the
     * waterline, where [drawCrest] caps it — and runs [RockFace.tileRows] whole tiles past the
     * bottom of the frame. A fixed top edge is what nails the rock to the water rather than to the
     * camera. See [RockFace.tileRows] for what that cost (a vertical phase that provably bought
     * nothing) and why.
     *
     * The two draws are ONE argument list written twice, exactly as [drawDiver]'s are. If you
     * change one of these numbers, change it in both.
     */
    private fun drawRockEdge(
        surface: Surface,
        normalMaps: NormalMapRenderer?,
        worldBottom: Float,
        side: Float
    )
    {
        val rows = RockFace.tileRows(worldBottom)
        val width = RockFace.TILE_WIDTH_METRES
        val height = rows * RockFace.TILE_HEIGHT_METRES

        // Centres, because the CENTRE_ORIGIN convention every other object here uses is what the
        // normal-map draw has to be handed identically. The quad grows OUTWARD from its inner
        // edge, which is the whole of what [side] means.
        val centreX = side * (RockFace.QUAD_INNER_HALF_WIDTH + width * 0.5f)
        val centreY = RockFace.WALL_TOP_DEPTH + height * 0.5f

        // The mirrored PAIR on the right — never one of them. Taking the mirrored albedo with the
        // base normals would light every bump on that wall from the wrong side, which is exactly
        // the failure a uv swap would have caused and the reason the mirror is baked at all.
        val diffuse = if (side == RIGHT_SIDE) RockFace.mirrorDiffuse else RockFace.diffuse
        val normal = if (side == RIGHT_SIDE) RockFace.mirrorNormal else RockFace.normal

        surface.drawTexture(
            diffuse,
            centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN,
            0f, 0f, 0f, 1f, 1f, 1f, rows.toFloat()
        )

        // The copied argument list. Same rect, same angle, same origin, same tiling.
        normalMaps?.drawNormalMap(
            normal,
            centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN,
            1f, rows.toFloat()
        )
    }

    /**
     * How many TILES tall a body quad is — its height in metres over
     * [RockFace.TILE_HEIGHT_METRES], and therefore the `vTiling` it must be drawn with.
     *
     * Pure and extracted because it is the one number in [drawRockBody] that can be wrong without
     * looking wrong at a glance. The above-water band's height shrinks as the camera drops (it is
     * clipped to the visible rect), so a fixed `1f` would squash a whole 40 m tile into whatever
     * sliver was on screen and the rock's texels would stretch as the diver descended — a
     * distortion that only shows up as motion, which no single still frame can catch.
     *
     * It is the WALL's tile height, not the body texture's own, and that is deliberate: the body
     * is a horizontal crop of the wall, all 2048 rows of it, so it shares the wall's v mapping
     * exactly and the two are still the same rock at the same scale where they meet.
     */
    internal fun bodyVerticalTiles(heightMetres: Float) = heightMetres / RockFace.TILE_HEIGHT_METRES

    /**
     * THE BODY: opaque rock from [RockFace.BODY_INNER_HALF_WIDTH] out past the side of the frame,
     * however wide the frame is. This is what makes the owner's *"the rocks should ALWAYS be
     * exactly starting at the edge of the screen"* true by construction.
     *
     * [halfWidth] is this side's visible half-width, and the tile count is
     * `ceil((halfWidth − BODY_INNER_HALF_WIDTH) / BODY_TILE_WIDTH_METRES)` — see
     * [RockFace.bodyColumns], and [RockFace.coverageOuterHalfWidth] for the arithmetic that
     * `RockFaceTest` sweeps. Zero columns when the frame stops short of the anchor (16:10 and
     * anything narrower), and then nothing is drawn at all: the body is opaque, so a floored-to-one
     * tile would paint solid rock across the crest's silhouette.
     *
     * ## THE SAME TEXTURE ON BOTH SIDES, AND THE LIGHTING IS STILL MIRRORED
     *
     * Every other rock texture here comes as a base/mirror pair. This one does not need to,
     * because the bake emits `[C | mirror(C)]`: the texture IS its own horizontal mirror. Work it
     * through — the quad grows outward from the anchor, so at a distance `d` outward the RIGHT
     * side samples `u = d/W` and the LEFT samples `u = 1 − d/W`. Self-mirroring means the albedo
     * at those two u's is the same texel, so the two cliffs are exact mirror images of each other;
     * and the mirror negated the normal's x, so the left's normal x at `d` is the negation of the
     * right's. Which is precisely what a mirrored cliff must have. Two array layers, not four, and
     * no way to hand one side the wrong half of a pair.
     *
     * ## NO UV SLICING, EVER
     *
     * `vTiling` is [bodyVerticalTiles] and `uTiling` is the column count, so both maps take the
     * identical natural mapping. `NormalMapRenderer.drawNormalMap` takes no uv arguments at all
     * (verified against the jar), so slicing the albedo's v to continue a lattice exactly would
     * leave the normals unsliced and light every facet from the wrong place — the same trap that
     * made the right-hand wall a baked mirror rather than a uv swap.
     *
     * The two draws are ONE argument list written twice, exactly as [drawDiver]'s are.
     */
    private fun drawRockBody(
        surface: Surface,
        normalMaps: NormalMapRenderer?,
        halfWidth: Float,
        topDepth: Float,
        bottomDepth: Float,
        side: Float
    )
    {
        val columns = RockFace.bodyColumns(halfWidth - RockFace.BODY_INNER_HALF_WIDTH)
        if (columns == 0) return

        val height = bottomDepth - topDepth
        if (height <= 0f) return

        val width = columns * RockFace.BODY_TILE_WIDTH_METRES
        val centreX = side * (RockFace.BODY_INNER_HALF_WIDTH + width * 0.5f)
        val centreY = topDepth + height * 0.5f
        val rows = bodyVerticalTiles(height)

        surface.drawTexture(
            RockFace.bodyDiffuse,
            centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN,
            0f, 0f, 0f, 1f, 1f, columns.toFloat(), rows
        )

        // The copied argument list. Same rect, same angle, same origin, same tiling.
        normalMaps?.drawNormalMap(
            RockFace.bodyNormal,
            centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN,
            columns.toFloat(), rows
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
     * A VENT'S DRAWN WIDTH, from the height the world gives it.
     *
     * Extracted for the reason [bodyVerticalTiles] is: it is the one number in [drawAirPockets]
     * that can be wrong without looking wrong. [OxygenSprite]'s cell is 182x216 — 0.843:1, TALLER
     * than wide — so a vent that adopts the art is NARROWER than the 2.4 m square it replaced. A
     * call site that handed [Framing.AIR_POCKET_SIZE_METRES] to both axes, which is exactly what
     * this method did before the sheet existed, would stretch every frame of the plume sideways
     * by 19%. Nobody spots that on an animated blob they have never seen undistorted, and no
     * still frame shows it at all.
     *
     * It DELEGATES to [OxygenSprite.widthForHeight] rather than restating `height * FRAME_ASPECT`.
     * The aspect is measured off the committed PNG in one place (`OxygenSpriteTest` re-derives it
     * from the IHDR), and a second derivation here would be a second thing to keep in step with a
     * re-bake — the same rule the copied argument lists in this file are written under.
     */
    internal fun ventWidthFor(heightMetres: Float) = OxygenSprite.widthForHeight(heightMetres)

    /**
     * The square a vent is culled against: the LARGER of its two sides, exactly as [drawCrest]
     * does and for the same reason — [showsSquare] takes one size, and the only safe direction to
     * be wrong in is to keep an object that is off frame.
     *
     * `max` rather than the bare height [drawDiver] passes, even though the two are the same
     * number today. The diver's height is larger than his width by the geometry of a human figure;
     * a vent's is larger by a ratio that lives in a PNG, and a re-bake at a wider frame would
     * silently start clipping vents at the left and right edges of the screen. `max` costs one
     * compare and cannot be invalidated by an art change.
     *
     * It also has to cover BOTH shapes this method draws: the textured rect above, and the
     * `heightMetres` square the fallback keeps. It does, exactly — the fallback square IS the
     * larger side on both axes.
     */
    internal fun ventCullSizeFor(heightMetres: Float) = max(heightMetres, ventWidthFor(heightMetres))

    /**
     * Air vents. Drawn before pearls so a pearl sitting on top of one stays readable, and
     * dimmed rather than hidden once spent — knowing where a used vent was is what lets a
     * player plan the next dive around it.
     *
     * ## The live/spent distinction is still a DRAW COLOUR, and that is why [IridescentMaterial]
     * carries none
     *
     * `setDrawColor` is unchanged from when this was a flat quad: `IridescenceRenderer.draw`
     * packs the surface's current draw colour into the instance and the shader modulates by it,
     * so [IridescentMaterial.VENT] describes a film and a substrate weight and never a hue. One
     * vent per dive is gameplay (`dive/AirPocket.kt`), and a player has to be able to see at a
     * glance which ones they have already spent.
     *
     * ## NO [IridescenceRenderer.equalAreaQuad] HERE, AND ITS ABSENCE IS DELIBERATE
     *
     * [drawPearlSurface] and `Hud.drawAirRing` both grow their quad by that factor because the
     * ANALYTIC path inscribes a disc in the quad and loses `1 - pi/4` of its area. A vent does not
     * take that path: its silhouette comes from the sheet's ALPHA, which already fills its cell to
     * within the 2 px transparent margin the bake leaves on each side (measured — see
     * [OxygenSprite]'s class doc, where that margin exists to stop the LINEAR filter reaching into
     * the neighbouring frame). Applying the compensation to a shape that is already full would
     * oversize the plume by about 12% in each axis and, worse, put the vent's drawn size out of
     * step with `Tuning.AIR_POCKET_PICKUP_RADIUS` — art you can visibly swim into without getting
     * the breath.
     *
     * ## The fallback is today's square, never a missing vent
     *
     * Two independent things can be absent, and both are ordinary rather than exceptional. The
     * renderer is null on frame one (the engine defers `addRenderer`'s init by a frame) and would
     * be null again if the shader failed to compile; the sheet is absent for the several frames
     * the asynchronous upload takes, and [OxygenSprite.normalFrame] before that THROWS rather than
     * returning null. Either way the vent degrades to the flat 2.4 m square it has always been —
     * the same doctrine [drawDiver] and [drawPearlSurface] follow, because an exception in front
     * of a queue is the one outcome the cabinet cannot have.
     */
    private fun drawAirPockets(
        surface: Surface,
        sim: DiveSim,
        cam: Camera,
        iridescence: IridescenceRenderer?,
        normalMaps: NormalMapRenderer?
    )
    {
        val height = Framing.AIR_POCKET_SIZE_METRES
        val width = ventWidthFor(height)
        val cullSize = ventCullSizeFor(height)

        // Resolved ONCE for the whole frame, and sheetsReady() is called unconditionally rather
        // than short-circuited behind the null test. It is not a pure predicate: it counts
        // consecutive misses and logs exactly one WARN after ten seconds of them, so asking it
        // only on the frames where the renderer happens to exist — or once per vent instead of
        // once per frame — would make that budget depend on something it is not measuring.
        //
        // Kept as a nullable local rather than a Boolean so the branch below smart-casts; a
        // Boolean flag would leave `iridescence` nullable at the call and buy nothing.
        val sheetReady = OxygenSprite.sheetsReady()
        val textured = if (sheetReady) iridescence else null

        sim.airPockets.forEachIndexed { index, pocket ->
            if (!cam.showsSquare(pocket.x, pocket.depth, cullSize)) return@forEachIndexed

            surface.setDrawColor(if (pocket.usedThisDive) airPocketSpentColor else airPocketColor)

            if (textured == null)
            {
                surface.fillRectCentred(pocket.x, pocket.depth, height, height)
                return@forEachIndexed
            }

            // THE INDEX IS WHY TWO VENTS DO NOT THROB IN LOCKSTEP. One shared phase drives every
            // vent and [OxygenSprite.phaseOffsetFor] spreads them by the golden ratio, so the
            // Kelp's vent and the Twilight's — which can be on screen together on a 55 m view —
            // are at different points of the same four-second loop. Driving them off the bare
            // phase would read as one mechanism blinking rather than three columns of air.
            val frame = OxygenSprite.currentFrameFor(index)
            val normal = OxygenSprite.normalFrame(frame)

            textured.draw(pocket.x, pocket.depth, width, height, IridescentMaterial.VENT, normal)

            // The copied argument list. Same texture, same rect, same (absent) angle, same
            // origin — see [drawDiver], whose doc has why deriving these twice is the shape of a
            // shipped bug. The shader takes the vent's SHAPE from this texture's alpha, so a
            // normal map that disagreed with it by even a fraction of a metre would light a
            // silhouette that is not where the picture's is.
            normalMaps?.drawNormalMap(
                normal,
                pocket.x, pocket.depth, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN
            )
        }
    }

    private fun drawPearls(
        surface: Surface,
        sim: DiveSim,
        cam: Camera,
        iridescence: IridescenceRenderer?,
        normalMaps: NormalMapRenderer?
    )
    {
        val size = Framing.PEARL_SIZE_METRES
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            if (!cam.showsSquare(pearl.x, pearl.depth, size)) return@forEach
            drawPearlSurface(surface, iridescence, normalMaps, pearl.x, pearl.depth)
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
    private fun drawAnglerfish(
        surface: Surface,
        sim: DiveSim,
        cam: Camera,
        iridescence: IridescenceRenderer?,
        normalMaps: NormalMapRenderer?
    )
    {
        val fish = sim.anglerfish ?: return
        if (!cam.showsSquare(fish.x, fish.depth, Framing.PEARL_SIZE_METRES)) return
        drawPearlSurface(surface, iridescence, normalMaps, fish.x, fish.depth)
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
     *
     * ## THE NORMAL IS PART OF THE SAME DESCRIPTION, and this is the second place in the game
     * where one world rect goes to two surfaces
     *
     * A pearl used to submit nothing at all to `gi_normal_map`, so GI lit it as what it
     * geometrically is on that surface — a flat quad — and it had no lit side and no dark side
     * (measured at -0.3% at 140 m; [PearlNormalMap]'s class doc has the numbers). The albedo pass
     * has the right normal and cannot share it: a `BatchRenderer` draws to one surface, and GI
     * reads normals from a different one through a TEXTURE. So the hemisphere is generated at load
     * and submitted here, on the SAME world rect, exactly as [drawDiver] submits the diver's.
     *
     * The second call's arguments are a literal COPY of the first's and must stay one — see
     * [drawDiver], whose doc has why deriving them twice is the shape of a shipped bug.
     *
     * It is inside the iridescence branch on purpose. The fallback is a flat `fillRectCentred`
     * square, and a flat square's true normal is the flat one GI already assumes; giving a square
     * a hemisphere would light a shape that is not there. So the normal follows the disc, and the
     * two are one description with one `if`.
     */
    private fun drawPearlSurface(
        surface: Surface,
        iridescence: IridescenceRenderer?,
        normalMaps: NormalMapRenderer?,
        centreX: Float,
        depth: Float
    )
    {
        val size = Framing.PEARL_SIZE_METRES
        val exposure = pearlAlbedoExposure()
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

            // The copied argument list. If you change one of these four numbers, change it above.
            // No angle, for `IridescenceRenderer.draw`'s reason: a hemisphere is radially
            // symmetric, so a rotation provably cannot change a texel.
            normalMaps?.drawNormalMap(
                PearlNormalMap.normals(),
                centreX, depth, quad, quad, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN
            )
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
     * The pearl did not merely brighten with depth, it went WHITE, because the pearl light then
     * ran 0.6 in the Shallows to 4.0 in the Abyss and the product left the ACES shoulder with no
     * hue left. An iridescent surface authored under those conditions is invisible on exactly the
     * objects it is for.
     *
     * ## THIS IS NOW INERT, AND THAT IS THE CORRECT OUTCOME RATHER THAN A REGRESSION
     *
     * The depth ramp above no longer exists: the owner removed it so that the torch, not the
     * pearl's own glow, is how you find pearls in the deep — see
     * [DiveLighting.PEARL_FRACTION_OF_TORCH], which is what the flat emission is now stated as.
     * With emission flat at the Shallows' own 0.6, `here` and the reference are the same number,
     * so this returns exactly 1 and removes no albedo at any depth.
     *
     * It is kept rather than deleted because it is a CONSEQUENCE of the emission curve, not a
     * setting: it is written as `reference / here`, so if any depth response is ever reintroduced
     * the compensation comes back with it automatically and correctly. Deleting it would leave the
     * next person to try a ramp rediscovering the white-out above from scratch. The measurements
     * are retained for the same reason — they are the evidence for what 4.0 in the Abyss costs,
     * and they are why 0.6 flat is known to be safe (the Shallows row clips nothing at all).
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
     * an existing, pure, already-tested property of the pearl. So the albedo is stopped down by
     * the inverse of that intensity, normalised to a reference the material was measured to still
     * read at. `exposure x intensity` is then constant with depth, and the material reads the same
     * in the Kelp as in the Abyss instead of 6.7x differently.
     *
     * The reference used to be the Twilight zone's 1.8, picked as the deepest zone whose pearls
     * kept their hue. With the ramp gone there is only one intensity to be the reference, so it is
     * simply that — see [PEARL_EXPOSURE_REFERENCE_INTENSITY].
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
    internal fun pearlAlbedoExposure(): Float
    {
        val here = DiveLighting.pearlIntensity()
        if (!(here > 0f)) return 1f // NaN or a degenerate constant: draw the pearl unmodified
        val full = (PEARL_EXPOSURE_REFERENCE_INTENSITY / here).coerceAtMost(1f)
        return full.pow(PEARL_EXPOSURE_STRENGTH)
    }

    /**
     * The pearl-light intensity the exposure above is normalised to.
     *
     * It was the value at the midpoint of the deepest zone whose pearls were measured to keep
     * their hue — Twilight, 1.8, where 70 m reads at chroma 0.638 with nothing clipped, against
     * the Abyss's 0.240 with 27% clipped. Now that the emission is flat there is exactly one
     * intensity in the game, so the reference is that intensity and the exposure is 1.
     *
     * Asked of [DiveLighting] rather than typed as a number, which is what makes the whole
     * compensation follow the emission automatically instead of pointing at a value nothing has
     * any more. That property is precisely what carried it through the ramp's removal without an
     * edit to the arithmetic.
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

    internal val PEARL_EXPOSURE_REFERENCE_INTENSITY = DiveLighting.pearlIntensity()

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
        floorBlueForReflectance(zoneRedAt(depth), zoneGreenAt(depth), rawZoneBlueAt(depth))

    /**
     * The band's blue BEFORE the reflectance floor — what [zoneBlue] asks for on its own.
     *
     * Exposed so `DiveRendererTest` can assert "the floor raises and never lowers" against the
     * curve itself instead of against a TRANSCRIPTION of the table, which is what it used to do:
     * a copy of `floatArrayOf(0.52f, ...)` in the test file passes forever after somebody edits
     * the real one, which is exactly the change this comment is attached to.
     */
    internal fun rawZoneBlueAt(depth: Float): Float = DepthBlend.blend(depth, zoneBlue)

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
