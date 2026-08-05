package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    const val AIR_BUBBLE_COUNT = 14
    const val AIR_LOW_THRESHOLD = 3
    private const val AIR_RING_RADIUS_METRES = 5.5f
    private const val AIR_BUBBLE_SIZE_METRES = 0.9f
    private const val HEARTBEAT_HZ = 8f
    private const val HEARTBEAT_AMPLITUDE = 0.15f

    /**
     * Extra bubble size per bubble lost below [AIR_LOW_THRESHOLD]: 1.6x at three bubbles,
     * 2.8x at the last one. The ring cannot warn you by being a ring once there is almost
     * nothing left of it — at 1.5s of a 30s breath the honest bubble count is ONE, and one
     * 0.9 m dot at the far side of the diver was genuinely easy to miss (see lowair-view.png,
     * where it is a single red speck). Rather than lie about the count or fall back on a
     * number — air is NEVER a number, design spec §12 — the fewer bubbles remain, the bigger
     * and heavier each one is drawn, so the warning gets louder exactly as it gets emptier.
     * The heartbeat pulses that size too (see [drawAirRing]), so the last bubble throbs.
     */
    private const val AIR_LOW_SIZE_GAIN = 0.6f

    /**
     * The golden angle, 137.5 degrees — the phyllotaxis spacing sunflowers use to pack seeds.
     *
     * WHY NOT `index / AIR_BUBBLE_COUNT * TAU`: that gives every bubble a slot on an evenly
     * divided circle, but the bubbles that SURVIVE are always the first indices, so a draining
     * ring collapses into an arc that starts at 3 o'clock and sweeps down. Three bubbles left
     * occupied a 51-degree wedge and left 86% of the circle empty (abyss-view.png: three dots
     * off to the diver's lower right, unrecognisable as a ring).
     *
     * The golden angle's defining property is that EVERY prefix of the sequence is spread
     * near-evenly around the circle: 3 bubbles leave at most 38% of it empty, 5 leave 24%,
     * 14 leave 9%. So the ring thins in place — each bubble keeps one fixed angle for its
     * whole life and pops out of a standing ring — instead of re-shuffling the survivors,
     * which is what dividing by the surviving count would do.
     */
    private val GOLDEN_RATIO = (1.0 + sqrt(5.0)) / 2.0
    private val GOLDEN_ANGLE = (PI * 2.0 * (1.0 - 1.0 / GOLDEN_RATIO)).toFloat()

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

    /** Backing rectangle overhang, as a fraction of the tape width. See [tapeShadow]. */
    private const val TAPE_OUTLINE_RATIO = 0.5f

    private const val CLOCK_DANGER_SECONDS = 20f

    private val cold = Color(0.75f, 0.85f, 1f)
    private val danger = Color(1f, 0.25f, 0.2f)

    /**
     * ALPHA ON THE HUD SURFACE IS SQUARED. Do not "tidy" these back down to a tasteful 0.15.
     *
     * MEASURED (trench-hud, 1920x1200 — the raw HUD surface, before compositing): a
     * `Color(1f, 1f, 1f, 0.15f)` fill landed as RGBA(38, 38, 38, 6). 38 = 255 x 0.15, so the
     * engine pre-multiplies the colour by alpha; 6 = 255 x 0.15 x 0.15, so it ALSO stores
     * alpha squared. An element authored at 15% opacity therefore displays at roughly 2% and
     * reads as nothing — which is exactly what became of the depth tape: every gameplay
     * screenshot showed an orphan amber dash and a lone depth marker floating at the right
     * edge with no scale behind them, so the point-of-no-return mercy the tape exists to
     * teach was unreadable. (The same capture shows colour is gamma-encoded on write —
     * `cold` (0.75, 0.85, 1) landed as (133, 175, 255) ~ (0.75, 0.85, 1)^2.2 — which is why
     * the tape body below is a near-white rather than the mid grey it looks like it wants
     * to be.)
     *
     * So every semi-transparent colour here states the opacity it wants to DISPLAY at and
     * runs it through [authoredAlphaFor]. This surface is not relit by global illumination
     * (see the "hud" surface comment in `EnPustTil.onCreate`), so what is authored here is
     * what is shown; there is no later pass to rescue a value that is too faint.
     */
    val tapeBg = Color(0.9f, 0.94f, 1f, authoredAlphaFor(0.55f))

    /**
     * A near-black backing drawn a hair proud of the tape on every side, for the same reason
     * HUD text gets [drawTextWithOutline]: a pale tape has no contrast against sunlit
     * Shallows water and a dark one has none against the near-black Abyss, but a pale bar
     * with a dark edge reads against both ends of a run. Still [fillRect] only —
     * `drawQuad`/`drawLine` render nothing at all on macOS (render/Draw.kt).
     */
    val tapeShadow = Color(0f, 0f, 0f, authoredAlphaFor(0.8f))

    /** The mercy marker: nearly opaque, because it has to out-read the tape it sits on. */
    val noReturnMark = Color(1f, 0.9f, 0.35f, authoredAlphaFor(0.9f))

    /**
     * The authored alpha that lands at [displayedAlpha] on the HUD surface, given that the
     * surface stores alpha squared (see the measurement above [tapeBg]). Ask for 55% opacity
     * and get 55%, not 55% of 55%.
     *
     * Public and pure so it is unit-testable without a `Surface`/GL context — same reason as
     * [textOutlineOffset] in render/Draw.kt.
     */
    fun authoredAlphaFor(displayedAlpha: Float): Float = sqrt(displayedAlpha.coerceIn(0f, 1f))

    /** How many bubbles are left in the ring for [air] seconds out of [capacity]. */
    fun airBubblesRemaining(air: Float, capacity: Float): Int
    {
        val fraction = (air / capacity).coerceIn(0f, 1f)
        return ceil(fraction * AIR_BUBBLE_COUNT).toInt().coerceIn(0, AIR_BUBBLE_COUNT)
    }

    /**
     * The fixed orbital angle, in radians, of bubble [index] — golden-angle spaced so that
     * whatever is left of the ring is the survivors of a ring rather than the front of a
     * queue. See [GOLDEN_ANGLE] for why this is not `index / AIR_BUBBLE_COUNT * TAU`.
     * It depends only on the bubble's own index, never on how many are left, so no bubble
     * ever moves: the ring thins in place instead of re-shuffling every time one pops.
     */
    fun airBubbleAngle(index: Int): Float = index * GOLDEN_ANGLE

    /**
     * Bubble size multiplier for a ring that is down to [remaining] bubbles: 1x until the
     * ring turns red at [AIR_LOW_THRESHOLD], then one more [AIR_LOW_SIZE_GAIN] for every
     * bubble lost after that — 1.6x at three, 2.2x at two, 2.8x at the last one.
     */
    fun airBubbleSizeScale(remaining: Int): Float =
        if (remaining > AIR_LOW_THRESHOLD) 1f
        else 1f + (AIR_LOW_THRESHOLD - remaining + 1) * AIR_LOW_SIZE_GAIN

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
        // Top-left, same bright-shallows-to-black-abyss background as everything else on
        // this surface (see class doc) — outlined so it stays legible at the surface.
        surface.drawTextWithOutline("BANKED ${sim.banked}", margin, margin + fontSize, fontSize, h, cold)
    }

    private fun drawClock(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        val fontSize = h * CLOCK_FONT_FRACTION
        val minutes = (sim.clock / 60f).toInt()
        val seconds = (sim.clock % 60f).toInt()
        val color = if (sim.clock < CLOCK_DANGER_SECONDS) danger else cold
        // Top-centre — exactly where the bright Shallows water sits. Flat cold-blue text
        // here is the low-contrast pairing that motivated this outline in the first place.
        surface.drawTextWithOutline(
            "%d:%02d".format(minutes, seconds),
            w * 0.5f, h * MARGIN_FRACTION + fontSize,
            fontSize, h, color, xOrigin = 0.5f
        )
    }

    /**
     * Air is NEVER a number. A ring of bubbles orbiting the diver, thinning as the
     * breath runs out. At [AIR_LOW_THRESHOLD] bubbles or fewer, the ring turns red and
     * pulses like a heartbeat — the only warning the player gets, no text involved.
     *
     * The bubbles are golden-angle spaced ([airBubbleAngle]) so the ring THINS rather than
     * collapsing into an arc, and the last few are drawn oversized ([airBubbleSizeScale]) so
     * that "almost out of air" is still a shout when there is only one bubble left to shout
     * with. Both were defects in the shipped version; both are explained where the constants
     * are declared.
     */
    private fun drawAirRing(surface: Surface, sim: DiveSim, diverX: Float, diverY: Float, h: Float)
    {
        val remaining = airBubblesRemaining(sim.air, Tuning.BASE_AIR_SECONDS)
        if (remaining <= 0) return

        val low = remaining <= AIR_LOW_THRESHOLD
        val ppm = Viewport.pixelsPerMetre(h)
        val pulse = if (low) 1f + sin(sim.clock * HEARTBEAT_HZ) * HEARTBEAT_AMPLITUDE else 1f
        val radius = AIR_RING_RADIUS_METRES * ppm * pulse
        // The heartbeat pulses the bubbles themselves as well as the orbit. A pulsing ORBIT
        // is only legible while there is a ring to see breathing; with one bubble left it is
        // just a dot jittering a few pixels sideways. Pulsing the size makes that last bubble
        // throb in place, which is the actual warning at the moment it matters most.
        val bubbleSize = AIR_BUBBLE_SIZE_METRES * ppm * airBubbleSizeScale(remaining) * pulse

        surface.setDrawColor(if (low) danger else cold)
        for (i in 0 until remaining)
        {
            val angle = airBubbleAngle(i)
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
        // interpolate the floats directly into the rgba drawTextWithOutline overload.
        // Attached to the diver, so it travels through every zone from bright Shallows to
        // near-black Abyss over a single run — outlined so it reads at both ends.
        surface.drawTextWithOutline(
            "${sim.held}",
            diverX + wobble, diverY + HELD_OFFSET_METRES * ppm,
            fontSize, h,
            1f, 0.72f - heat * 0.47f, 0.25f - heat * 0.15f, 1f,
            xOrigin = 0.5f
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

        // Dark backing first, a hair proud of the tape on every side, then the pale tape on
        // top of it — the rectangle equivalent of drawTextWithOutline, and for the same
        // reason: neither a pale nor a dark bar alone reads across both the sunlit Shallows
        // and the near-black Abyss. The outline width scales with the tape, not with a fixed
        // pixel count, because `h` here is PHYSICAL framebuffer pixels (see the class doc).
        val outline = tapeWidth * TAPE_OUTLINE_RATIO
        surface.setDrawColor(tapeShadow)
        surface.fillRect(x - outline, top - outline, tapeWidth + outline * 2f, span + outline * 2f)

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

        // The marker travels the whole tape as depth increases — the same bright-to-black
        // span everything else on this surface crosses — so this label needs the outline
        // just as much as the clock does.
        surface.drawTextWithOutline(
            "%.0fm".format(sim.depth),
            x - margin * 0.5f, depthY + h * TAPE_LABEL_FONT_FRACTION,
            h * TAPE_LABEL_FONT_FRACTION, h, cold, xOrigin = 1f
        )
    }
}
