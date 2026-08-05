package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
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
 * All coordinate maths lives in [Viewport], which is pure and unit-tested — the screen
 * dimensions handed in here are PHYSICAL framebuffer pixels, not the logical size from
 * application.cfg, so nothing may assume a particular resolution.
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
     *  enough that DepthBlend's smoothstep easing reads as continuous rather than banded. */
    private const val BAND_STRIP_METRES = 0.5f

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

    fun render(surface: Surface, sim: DiveSim, camera: DiveCamera, screenWidth: Float, screenHeight: Float)
    {
        val cam = camera.depth
        drawZoneBands(surface, cam, screenWidth, screenHeight)
        drawColumnWalls(surface, screenWidth, screenHeight)
        drawSurfaceLine(surface, cam, screenWidth, screenHeight)
        drawAirPockets(surface, sim, cam, screenWidth, screenHeight)
        drawPearls(surface, sim, cam, screenWidth, screenHeight)
        drawAnglerfish(surface, sim, cam, screenWidth, screenHeight)
        drawDiver(surface, sim, cam, screenWidth, screenHeight)
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
     */
    private fun drawZoneBands(surface: Surface, cam: Float, w: Float, h: Float)
    {
        val ppm = Viewport.pixelsPerMetre(h)
        val stripHeight = BAND_STRIP_METRES * ppm
        var y = 0f
        while (y < h)
        {
            val stripBottom = min(y + stripHeight, h)
            val centreDepth = Viewport.depthAt((y + stripBottom) * 0.5f, cam, h)
            surface.setDrawColor(zoneRedAt(centreDepth), zoneGreenAt(centreDepth), zoneBlueAt(centreDepth), 1f)
            surface.fillRect(0f, y, w, stripBottom - y)
            y = stripBottom
        }
    }

    /**
     * Solid rock bounding the playable column outside +-[Tuning.COLUMN_HALF_WIDTH]. Without
     * this the boundary is invisible: on a 16:9 booth screen (see [Viewport.screenX], which
     * derives pixels-per-metre from screen HEIGHT) the visible half-width is ~53m versus the
     * 40m column, so the diver stops dead in open water with no visual reason — the stick
     * reads as broken rather than blocked. Drawn onto the world surface (not "hud"), so it is
     * behind [no.njoh.pulseengine.core.PulseEngineGame]'s lighting pass like everything else
     * DiveRenderer draws, and it frames the play area rather than looking like a UI chrome.
     *
     * On a narrower aspect ratio (e.g. the 4:3 dev window) the column may fill the whole
     * screen and these rects fall entirely off both edges — that is fine and needs no special
     * case, since [Surface.fillRect] with a non-positive width simply draws nothing visible.
     */
    private fun drawColumnWalls(surface: Surface, w: Float, h: Float)
    {
        val leftEdge = Viewport.screenX(-Tuning.COLUMN_HALF_WIDTH, w, h)
        val rightEdge = Viewport.screenX(Tuning.COLUMN_HALF_WIDTH, w, h)
        val edgeWidth = WALL_EDGE_METRES * Viewport.pixelsPerMetre(h)

        surface.setDrawColor(wallColor)
        if (leftEdge > 0f) surface.fillRect(0f, 0f, leftEdge, h)
        if (rightEdge < w) surface.fillRect(rightEdge, 0f, w - rightEdge, h)

        // The inner faces, drawn over the slabs above rather than beside them, so they can
        // never intrude on the water — and so the narrow-aspect case still needs no special
        // handling: where the slab is off-screen its face is too.
        surface.setDrawColor(wallEdgeColor)
        if (leftEdge > 0f) surface.fillRect(leftEdge - min(edgeWidth, leftEdge), 0f, min(edgeWidth, leftEdge), h)
        if (rightEdge < w) surface.fillRect(rightEdge, 0f, min(edgeWidth, w - rightEdge), h)
    }

    /** The waterline. Without it there is no visual cue for where banking happens. */
    private fun drawSurfaceLine(surface: Surface, cam: Float, w: Float, h: Float)
    {
        val y = Viewport.screenY(Tuning.SURFACE_DEPTH, cam, h)
        if (y < -4f || y > h) return
        val thickness = Viewport.pixelsPerMetre(h) * 0.4f
        surface.setDrawColor(surfaceColor)
        surface.fillRect(0f, y - thickness * 0.5f, w, thickness)
    }

    /**
     * Air vents. Drawn before pearls so a pearl sitting on top of one stays readable, and
     * dimmed rather than hidden once spent — knowing where a used vent was is what lets a
     * player plan the next dive around it.
     */
    private fun drawAirPockets(surface: Surface, sim: DiveSim, cam: Float, w: Float, h: Float)
    {
        val size = Viewport.AIR_POCKET_SIZE_METRES * Viewport.pixelsPerMetre(h)
        sim.airPockets.forEach { pocket ->
            val screenY = Viewport.screenY(pocket.depth, cam, h)
            if (screenY < -size || screenY > h + size) return@forEach
            val screenX = Viewport.screenX(pocket.x, w, h)
            surface.setDrawColor(if (pocket.usedThisDive) airPocketSpentColor else airPocketColor)
            surface.fillRect(screenX - size * 0.5f, screenY - size * 0.5f, size, size)
        }
    }

    private fun drawPearls(surface: Surface, sim: DiveSim, cam: Float, w: Float, h: Float)
    {
        val size = Viewport.PEARL_SIZE_METRES * Viewport.pixelsPerMetre(h)
        surface.setDrawColor(pearlColor)
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            val screenY = Viewport.screenY(pearl.depth, cam, h)
            if (screenY < -size || screenY > h + size) return@forEach
            val screenX = Viewport.screenX(pearl.x, w, h)
            surface.fillRect(screenX - size * 0.5f, screenY - size * 0.5f, size, size)
        }
    }

    /**
     * The anglerfish's lure. Drawn IDENTICALLY to a pearl, deliberately — in the Abyss,
     * where pearls are the only light, you cannot tell treasure from predator by looking.
     * The tell is motion: a real pearl never moves, this drifts slowly toward the diver.
     */
    private fun drawAnglerfish(surface: Surface, sim: DiveSim, cam: Float, w: Float, h: Float)
    {
        val fish = sim.anglerfish ?: return
        val size = Viewport.PEARL_SIZE_METRES * Viewport.pixelsPerMetre(h)
        val screenY = Viewport.screenY(fish.depth, cam, h)
        if (screenY < -size || screenY > h + size) return
        val screenX = Viewport.screenX(fish.x, w, h)
        surface.setDrawColor(pearlColor)
        surface.fillRect(screenX - size * 0.5f, screenY - size * 0.5f, size, size)
    }

    private fun drawDiver(surface: Surface, sim: DiveSim, cam: Float, w: Float, h: Float)
    {
        // Size scales with load so weight is visible as well as felt.
        val metres = Viewport.DIVER_SIZE_METRES + sim.heldMass * 0.03f
        val size = metres * Viewport.pixelsPerMetre(h)
        val screenX = Viewport.screenX(sim.x, w, h)
        val screenY = Viewport.screenY(sim.depth, cam, h)
        surface.setDrawColor(diverColor)
        surface.fillRect(screenX - size * 0.5f, screenY - size * 0.5f, size, size)
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
