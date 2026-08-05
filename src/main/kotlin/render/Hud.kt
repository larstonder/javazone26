package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * The real HUD, replacing the temporary numeric readout. Per the design spec (§12):
 *
 *   BANKED — small, cold white, top-left.
 *   HELD   — enormous amber numerals attached to the diver, wobbling, hotter as it grows.
 *   AIR    — NEVER a number. A ring of bubbles orbiting the diver that visibly thins as
 *            you breathe; at three bubbles left they turn red and pulse with a heartbeat.
 *   Depth tape down the right edge, with the point-of-no-return marker.
 *   The clock.
 *
 * RESOLUTION INDEPENDENCE: `engine.window.width/height` are PHYSICAL framebuffer pixels
 * (2400x1800 on a Retina Mac, not the 1200x900 in application.cfg), so nothing here is a
 * hardcoded pixel value. Screen-anchored elements (BANKED, the clock, the tape) are sized
 * as a fraction of the actual surface height. World-anchored elements (the air ring,
 * HELD) are sized in metres via [Viewport.pixelsPerMetre] and positioned at the diver's
 * REAL screen position — computed with the same [DiveCamera] the world renderer used, not
 * a fixed screen fraction — so they track the diver correctly even while the camera is
 * lagging behind a hard descent (see [DiveCamera]).
 *
 * Uses [Surface.fillRect] ONLY. `Surface.drawQuad`/`drawLine` render nothing at all on
 * macOS/Apple Silicon — silently, no GL error. See render/Draw.kt.
 */
object Hud
{
    // --- Bubble ring: air is never a number -----------------------------------------
    private const val AIR_BUBBLE_COUNT = 14
    private const val AIR_LOW_THRESHOLD = 3
    private const val AIR_RING_RADIUS_METRES = 5.5f
    private const val AIR_BUBBLE_SIZE_METRES = 0.9f
    private const val HEARTBEAT_HZ = 8f
    private const val HEARTBEAT_AMPLITUDE = 0.15f

    // --- HELD: enormous, amber, attached to the diver --------------------------------
    private const val HELD_OFFSET_METRES = 8f        // below the diver, clear of the air ring
    private const val HELD_HEAT_SCALE = 3000f         // held value at which colour/size maxes out
    private const val HELD_MIN_FONT_FRACTION = 0.045f
    private const val HELD_MAX_FONT_BONUS_FRACTION = 0.05f
    private const val HELD_WOBBLE_HZ = 2.4f
    private const val HELD_WOBBLE_METRES = 0.4f

    // --- Screen-anchored: BANKED, clock, depth tape -----------------------------------
    private const val BANKED_FONT_FRACTION = 0.028f
    private const val CLOCK_FONT_FRACTION = 0.05f
    private const val MARGIN_FRACTION = 0.02f
    private const val TAPE_WIDTH_FRACTION = 0.006f
    private const val TAPE_TOP_FRACTION = 0.10f
    private const val TAPE_BOTTOM_FRACTION = 0.08f
    private const val TAPE_RIGHT_MARGIN_FRACTION = 0.035f
    private const val TAPE_LABEL_FONT_FRACTION = 0.018f

    private const val CLOCK_DANGER_SECONDS = 20f

    private val TAU = (PI * 2.0).toFloat()

    private val cold = Color(0.75f, 0.85f, 1f)
    private val danger = Color(1f, 0.25f, 0.2f)
    private val tapeBg = Color(1f, 1f, 1f, 0.15f)
    private val noReturnMark = Color(1f, 0.9f, 0.35f, 0.65f)

    fun render(surface: Surface, sim: DiveSim, camera: DiveCamera, w: Float, h: Float)
    {
        val diverX = Viewport.screenX(sim.x, w, h)
        val diverY = Viewport.screenY(sim.depth, camera.depth, h)

        drawBanked(surface, sim, h)
        drawClock(surface, sim, w, h)
        drawAirRing(surface, sim, diverX, diverY, h)
        drawHeld(surface, sim, diverX, diverY, h)
        drawDepthTape(surface, sim, w, h)
    }

    private fun drawBanked(surface: Surface, sim: DiveSim, h: Float)
    {
        val margin = h * MARGIN_FRACTION
        val fontSize = h * BANKED_FONT_FRACTION
        surface.setDrawColor(cold)
        surface.drawText("BANKED ${sim.banked}", margin, margin + fontSize, fontSize = fontSize)
    }

    private fun drawClock(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        val fontSize = h * CLOCK_FONT_FRACTION
        val minutes = (sim.clock / 60f).toInt()
        val seconds = (sim.clock % 60f).toInt()
        surface.setDrawColor(if (sim.clock < CLOCK_DANGER_SECONDS) danger else cold)
        surface.drawText(
            "%d:%02d".format(minutes, seconds),
            w * 0.5f, h * MARGIN_FRACTION + fontSize,
            fontSize = fontSize, xOrigin = 0.5f
        )
    }

    /**
     * Air is NEVER a number. A ring of bubbles orbiting the diver, thinning as the
     * breath runs out. At [AIR_LOW_THRESHOLD] bubbles or fewer, the ring turns red and
     * pulses like a heartbeat — the only warning the player gets, no text involved.
     */
    private fun drawAirRing(surface: Surface, sim: DiveSim, diverX: Float, diverY: Float, h: Float)
    {
        val fraction = (sim.air / Tuning.BASE_AIR_SECONDS).coerceIn(0f, 1f)
        val remaining = ceil(fraction * AIR_BUBBLE_COUNT).toInt().coerceIn(0, AIR_BUBBLE_COUNT)
        if (remaining <= 0) return

        val low = remaining <= AIR_LOW_THRESHOLD
        val ppm = Viewport.pixelsPerMetre(h)
        val pulse = if (low) 1f + sin(sim.clock * HEARTBEAT_HZ) * HEARTBEAT_AMPLITUDE else 1f
        val radius = AIR_RING_RADIUS_METRES * ppm * pulse
        val bubbleSize = AIR_BUBBLE_SIZE_METRES * ppm

        surface.setDrawColor(if (low) danger else cold)
        for (i in 0 until remaining)
        {
            val angle = (i.toFloat() / AIR_BUBBLE_COUNT) * TAU
            val bx = diverX + cos(angle) * radius
            val by = diverY + sin(angle) * radius
            surface.fillRect(bx - bubbleSize * 0.5f, by - bubbleSize * 0.5f, bubbleSize, bubbleSize)
        }
    }

    /** Enormous amber numerals, attached to the diver, hotter and bigger as they grow. */
    private fun drawHeld(surface: Surface, sim: DiveSim, diverX: Float, diverY: Float, h: Float)
    {
        if (sim.held <= 0) return

        val ppm = Viewport.pixelsPerMetre(h)
        val heat = (sim.held / HELD_HEAT_SCALE).coerceIn(0f, 1f)
        val fontSize = h * (HELD_MIN_FONT_FRACTION + heat * HELD_MAX_FONT_BONUS_FRACTION)
        val wobble = sin(sim.clock * HELD_WOBBLE_HZ) * HELD_WOBBLE_METRES * ppm

        // Cold amber -> hot red-amber as the haul grows. No Color allocation per frame —
        // interpolate the floats directly into the 4-float setDrawColor overload.
        surface.setDrawColor(1f, 0.72f - heat * 0.47f, 0.25f - heat * 0.15f, 1f)
        surface.drawText(
            "${sim.held}",
            diverX + wobble, diverY + HELD_OFFSET_METRES * ppm,
            fontSize = fontSize, xOrigin = 0.5f
        )
    }

    /** Depth tape down the right edge, with the point-of-no-return marker. */
    private fun drawDepthTape(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        val tapeWidth = h * TAPE_WIDTH_FRACTION
        val margin = h * MARGIN_FRACTION
        val x = w - h * TAPE_RIGHT_MARGIN_FRACTION
        val top = h * TAPE_TOP_FRACTION
        val bottom = h - h * TAPE_BOTTOM_FRACTION
        val span = bottom - top

        surface.setDrawColor(tapeBg)
        surface.fillRect(x, top, tapeWidth, span)

        // Point of no return: the mercy that teaches the economy without a word of text.
        val safeFraction = (sim.maxSafeDepth() / Tuning.MAX_DEPTH).coerceIn(0f, 1f)
        val safeY = top + safeFraction * span
        val markWidth = tapeWidth * 6f
        surface.setDrawColor(noReturnMark)
        surface.fillRect(x - (markWidth - tapeWidth) * 0.5f, safeY - tapeWidth * 0.5f, markWidth, tapeWidth)

        // Current depth marker.
        val depthFraction = (sim.depth / Tuning.MAX_DEPTH).coerceIn(0f, 1f)
        val depthY = top + depthFraction * span
        val markerHeight = tapeWidth * 3f
        surface.setDrawColor(if (sim.canStillReturn()) cold else danger)
        surface.fillRect(x - tapeWidth, depthY - markerHeight * 0.5f, tapeWidth * 3f, markerHeight)

        surface.setDrawColor(cold)
        surface.drawText(
            "%.0fm".format(sim.depth),
            x - margin * 0.5f, depthY + h * TAPE_LABEL_FONT_FRACTION,
            fontSize = h * TAPE_LABEL_FONT_FRACTION, xOrigin = 1f
        )
    }
}
