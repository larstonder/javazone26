package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import kotlin.math.min

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

    private val pearlColor = Color(1f, 0.78f, 0.35f)
    private val airPocketColor = Color(0.65f, 0.95f, 1f)
    private val airPocketSpentColor = Color(0.22f, 0.34f, 0.42f)
    private val diverColor = Color(1f, 1f, 1f)
    private val surfaceColor = Color(0.55f, 0.80f, 0.95f)

    fun render(surface: Surface, sim: DiveSim, camera: DiveCamera, screenWidth: Float, screenHeight: Float)
    {
        val cam = camera.depth
        drawZoneBands(surface, cam, screenWidth, screenHeight)
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
    internal fun zoneBlueAt(depth: Float): Float = DepthBlend.blend(depth, zoneBlue)
}
