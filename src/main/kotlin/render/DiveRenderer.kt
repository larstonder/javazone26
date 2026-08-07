package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Placeholder rendering. Everything is an untextured quad:
 *   diver  = white square, grows with held mass
 *   pearl  = small amber square
 *   zones  = flat horizontal bands
 * Real art replaces this after the loop is locked.
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
     * The floor cannot be avoided from here (it is the engine's, and `DiveLighting` owns the
     * GI system), so the water must simply stay above it — see [floorBlueForReflectance].
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
     * Rock walls bounding the playable column — see [drawColumnWalls]. Warm, so they read as
     * stone rather than as more (blue) water or a UI border.
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
     * The inner face of each wall, lighter than [wallColor]. A flat slab of a single colour
     * still reads as a bar wherever the light map falls to nothing, which in the deep zones is
     * most of it; what says "the column ends HERE" is the boundary itself carrying a value of
     * its own, so the edge stays legible even once the wall body behind it has gone black.
     * Physically it is also the face that catches grazing light, so it being the brighter part
     * is the right way round.
     *
     * Luminance 0.086 — about the same as fully lit shallow water and still ~7x under a pearl,
     * over a strip [WALL_EDGE_METRES] wide at the very edge of frame. Bright enough to define
     * the boundary, nowhere near enough to compete for attention.
     */
    internal val wallEdgeColor = Color(0.36f, 0.32f, 0.28f)

    /** Width of that inner face, in metres so it is resolution-independent like everything else. */
    private const val WALL_EDGE_METRES = 0.7f

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
    fun render(surface: Surface, sim: DiveSim, cam: Camera)
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

        drawZoneBands(surface, worldLeft, worldTop, worldRight, worldBottom)
        drawColumnWalls(surface, worldLeft, worldTop, worldRight, worldBottom)
        drawSurfaceLine(surface, worldLeft, worldRight)
        drawAirPockets(surface, sim, cam)
        drawPearls(surface, sim, cam)
        drawAnglerfish(surface, sim, cam)
        drawDiver(surface, sim, cam)
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
     */
    private fun drawColumnWalls(surface: Surface, worldLeft: Float, worldTop: Float, worldRight: Float, worldBottom: Float)
    {
        val height = worldBottom - worldTop
        val leftSlab = -Tuning.COLUMN_HALF_WIDTH - worldLeft
        val rightSlab = worldRight - Tuning.COLUMN_HALF_WIDTH

        surface.setDrawColor(wallColor)
        if (leftSlab > 0f) surface.fillRect(worldLeft, worldTop, leftSlab, height)
        if (rightSlab > 0f) surface.fillRect(Tuning.COLUMN_HALF_WIDTH, worldTop, rightSlab, height)

        // The inner faces, drawn over the slabs above rather than beside them, so they can
        // never intrude on the water — and so the narrow-aspect case still needs no special
        // handling: where the slab is off-screen its face is too. Clamped to the slab's own
        // width for the same reason, so a face never overhangs a sliver of rock.
        surface.setDrawColor(wallEdgeColor)
        if (leftSlab > 0f)
        {
            val face = min(WALL_EDGE_METRES, leftSlab)
            surface.fillRect(-Tuning.COLUMN_HALF_WIDTH - face, worldTop, face, height)
        }
        if (rightSlab > 0f)
        {
            surface.fillRect(Tuning.COLUMN_HALF_WIDTH, worldTop, min(WALL_EDGE_METRES, rightSlab), height)
        }
    }

    /**
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

    private fun drawPearls(surface: Surface, sim: DiveSim, cam: Camera)
    {
        val size = Framing.PEARL_SIZE_METRES
        surface.setDrawColor(pearlColor)
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            if (!cam.showsSquare(pearl.x, pearl.depth, size)) return@forEach
            surface.fillRectCentred(pearl.x, pearl.depth, size, size)
        }
    }

    /**
     * The anglerfish's lure. Drawn IDENTICALLY to a pearl, deliberately — in the Abyss,
     * where pearls are the only light, you cannot tell treasure from predator by looking.
     * The tell is motion: a real pearl never moves, this drifts slowly toward the diver.
     */
    private fun drawAnglerfish(surface: Surface, sim: DiveSim, cam: Camera)
    {
        val fish = sim.anglerfish ?: return
        val size = Framing.PEARL_SIZE_METRES
        if (!cam.showsSquare(fish.x, fish.depth, size)) return
        surface.setDrawColor(pearlColor)
        surface.fillRectCentred(fish.x, fish.depth, size, size)
    }

    private fun drawDiver(surface: Surface, sim: DiveSim, cam: Camera)
    {
        // Size scales with load so weight is visible as well as felt — and the culled rect has
        // to grow with it, which is the whole reason the size is computed before the test.
        val size = Framing.DIVER_SIZE_METRES + sim.heldMass * 0.03f
        if (!cam.showsSquare(sim.x, sim.depth, size)) return
        surface.setDrawColor(diverColor)
        surface.fillRectCentred(sim.x, sim.depth, size, size)
    }

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
