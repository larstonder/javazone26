package render

import dive.DiveSim
import dive.Tuning
import dive.Zone
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color

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
    private val zoneColors = mapOf(
        Zone.SHALLOWS to Color(0.20f, 0.55f, 0.80f),
        Zone.KELP     to Color(0.14f, 0.42f, 0.62f),
        Zone.TWILIGHT to Color(0.09f, 0.28f, 0.46f),
        Zone.TRENCH   to Color(0.05f, 0.16f, 0.30f),
        Zone.ABYSS    to Color(0.02f, 0.06f, 0.14f)
    )

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

    private fun drawZoneBands(surface: Surface, cam: Float, w: Float, h: Float)
    {
        Zone.entries.forEach { zone ->
            val top = Viewport.screenY(zone.minDepth, cam, h)
            val bottom = Viewport.screenY(nextZoneDepth(zone), cam, h)
            if (bottom < 0f || top > h) return@forEach
            surface.setDrawColor(zoneColors.getValue(zone))
            surface.fillRect(0f, top, w, bottom - top)
        }
    }

    private fun nextZoneDepth(zone: Zone): Float =
        Zone.entries.getOrNull(zone.ordinal + 1)?.minDepth ?: Tuning.MAX_DEPTH

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
}
