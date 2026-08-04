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
 */
object DiveRenderer
{
    private const val PIXELS_PER_METRE = 5f
    private const val DIVER_BASE_SIZE = 14f
    private const val PEARL_SIZE = 6f

    private val zoneColors = mapOf(
        Zone.SHALLOWS to Color(0.20f, 0.55f, 0.80f),
        Zone.KELP     to Color(0.14f, 0.42f, 0.62f),
        Zone.TWILIGHT to Color(0.09f, 0.28f, 0.46f),
        Zone.TRENCH   to Color(0.05f, 0.16f, 0.30f),
        Zone.ABYSS    to Color(0.02f, 0.06f, 0.14f)
    )

    private val pearlColor = Color(1f, 0.78f, 0.35f)
    private val diverColor = Color(1f, 1f, 1f)

    fun render(surface: Surface, sim: DiveSim, screenWidth: Float, screenHeight: Float)
    {
        val centreX = screenWidth * 0.5f
        // Camera follows the diver vertically, keeping them at 40% screen height.
        val cameraDepth = sim.depth - (screenHeight * 0.4f) / PIXELS_PER_METRE

        drawZoneBands(surface, cameraDepth, screenWidth, screenHeight)
        drawPearls(surface, sim, centreX, cameraDepth, screenHeight)
        drawDiver(surface, sim, centreX, cameraDepth)
    }

    private fun drawZoneBands(surface: Surface, cameraDepth: Float, w: Float, h: Float)
    {
        Zone.entries.forEach { zone ->
            val top = (zone.minDepth - cameraDepth) * PIXELS_PER_METRE
            val bottom = nextZoneTop(zone, cameraDepth)
            if (bottom < 0f || top > h) return@forEach
            surface.setDrawColor(zoneColors.getValue(zone))
            surface.drawQuad(0f, top, w, bottom - top)
        }
    }

    private fun nextZoneTop(zone: Zone, cameraDepth: Float): Float
    {
        val next = Zone.entries.getOrNull(zone.ordinal + 1)
        val depth = next?.minDepth ?: Tuning.MAX_DEPTH
        return (depth - cameraDepth) * PIXELS_PER_METRE
    }

    private fun drawPearls(surface: Surface, sim: DiveSim, centreX: Float, cameraDepth: Float, h: Float)
    {
        surface.setDrawColor(pearlColor)
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            val screenY = (pearl.depth - cameraDepth) * PIXELS_PER_METRE
            if (screenY < -PEARL_SIZE || screenY > h + PEARL_SIZE) return@forEach
            val screenX = centreX + pearl.x * PIXELS_PER_METRE
            surface.drawQuad(screenX - PEARL_SIZE * 0.5f, screenY - PEARL_SIZE * 0.5f, PEARL_SIZE, PEARL_SIZE)
        }
    }

    private fun drawDiver(surface: Surface, sim: DiveSim, centreX: Float, cameraDepth: Float)
    {
        // Size scales with load so weight is visible as well as felt.
        val size = DIVER_BASE_SIZE + sim.heldMass * 0.15f
        val screenX = centreX + sim.x * PIXELS_PER_METRE
        val screenY = (sim.depth - cameraDepth) * PIXELS_PER_METRE
        surface.setDrawColor(diverColor)
        surface.drawQuad(screenX - size * 0.5f, screenY - size * 0.5f, size, size)
    }
}
