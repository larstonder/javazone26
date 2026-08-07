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
 * PURE SCREEN SPACE, ON ITS OWN SURFACE. This file has no camera and no coordinate maths: it
 * is HANDED the diver's screen position and a pixels-per-metre scale and draws in pixels. The
 * HUD's surface has its own identity camera and is deliberately NOT the world surface, because
 * `GlobalIlluminationSystem` multiplies the world surface by the light map and would take
 * BANKED in the Abyss to RGB(11,8,1) — measured. See the "hud" createSurface call in
 * `EnPustTil.onCreate`.
 *
 * WHERE THE DIVER'S SCREEN POSITION COMES FROM, and why it is not computed here. The world is
 * drawn in metres through `engine.gfx.mainCamera`; the anchor is `mainCamera
 * .worldPosToScreenPos(sim.x, sim.depth)`, taken in `EnPustTil.onRender` from the SAME matrix
 * the world surface is being drawn with this frame (built once in `gfx.initFrame`,
 * GraphicsImpl.kt:111). Deriving it any other way — most temptingly from `DiveCamera.depth`,
 * which is what this file used to do — reads camera state from a different point in the frame
 * and puts the air ring one frame ahead of the diver while the camera is easing. That is the
 * drift `DiveLighting`'s class doc records killing on the world side; the HUD boundary is where
 * it would come back. [pixelsPerMetre] arrives the same way, as the screen distance between two
 * world points one metre apart, which is exactly right even on a frame a resize is being
 * interpolated through.
 *
 * RESOLUTION INDEPENDENCE: `w`/`h` are the HUD surface's own config size — PHYSICAL framebuffer
 * pixels (2400x1800 on a Retina Mac, not the 1200x900 in application.cfg) — so nothing here is
 * a hardcoded pixel value. Screen-anchored elements (BANKED, the clock, the tape) are sized as
 * a fraction of that height, never its width. World-anchored elements (the air ring, HELD) are
 * sized in metres times [pixelsPerMetre] so they stay the same size relative to the diver.
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

    private val TAU = (PI * 2.0).toFloat()

    /**
     * Where slot 0 sits: straight up from the diver, 12 o'clock.
     *
     * Screen space here runs Y-DOWNWARD (screen y increases with depth, because world y IS
     * depth and the engine's projection is y-down) and
     * [drawAirRing] places a bubble at `(cos a, sin a)`, so `a = 0` is 3 o'clock and
     * `a = +90` degrees is 6 o'clock, NOT 12. Twelve o'clock is therefore minus a quarter
     * turn, and — the part that is easy to get backwards — INCREASING the angle walks
     * 3 -> 6 -> 9 o'clock, which is CLOCKWISE on screen even though it is the
     * counter-clockwise direction in the usual Y-up convention.
     */
    private val RING_TOP_ANGLE = -TAU * 0.25f

    /**
     * TWO EARLIER VERSIONS OF THIS WERE WRONG, IN OPPOSITE DIRECTIONS. Both are worth
     * knowing about before changing anything here.
     *
     * The shipped version drew bubble `i` at `i / AIR_BUBBLE_COUNT * TAU` for
     * `i in 0 until remaining`. Evenly spaced, but the survivors were always the LOWEST
     * indices, so a draining ring collapsed into a wedge anchored at 3 o'clock — three
     * bubbles left occupied 51 degrees and left 86% of the circle empty.
     *
     * The fix for that used golden-angle (137.5 degree) spacing so that every prefix of the
     * sequence stays spread around the circle. It did stop the collapse, and it was worse:
     * at 137.5 degrees apart the bubbles land at irregular angles, so a full ring read as
     * scattered confetti rather than a ring, and they vanished in an order with no visual
     * logic. The player's words were "the bubble UI is misaligned, and they don't pop in a
     * natural clockwise order".
     *
     * So: fourteen FIXED, evenly spaced slots — a clock face — and the ring empties like
     * every countdown dial anyone has ever seen. The gap opens at 12 o'clock and grows
     * CLOCKWISE, which is what [firstOccupiedSlot] encodes by dropping low slots first.
     * An arc is not the enemy; an arc is exactly what a depleting timer looks like. The
     * enemy was irregularity, and a slot's angle depends only on the slot, so no bubble
     * ever moves while another pops.
     */
    private fun slotStep() = TAU / AIR_BUBBLE_COUNT

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
     * The fixed orbital angle, in radians, of ring slot [slot]. Slot 0 is 12 o'clock and
     * rising slot numbers walk CLOCKWISE around the diver (see [RING_TOP_ANGLE] — screen
     * Y runs downward, so that is a rising angle, not a falling one).
     *
     * Depends only on the slot, never on how many bubbles are left, so a bubble never moves
     * while another pops: the ring empties in place instead of re-shuffling.
     */
    fun airBubbleSlotAngle(slot: Int): Float = RING_TOP_ANGLE + slot * slotStep()

    /**
     * The lowest ring slot still holding a bubble when [remaining] are left. Slots
     * `firstOccupiedSlot(remaining) until AIR_BUBBLE_COUNT` are the occupied ones.
     *
     * Dropping the LOW slots is what makes the ring deplete clockwise from 12 o'clock: at
     * 14 bubbles every slot is filled; the first breath takes slot 0 (12 o'clock), the next
     * slot 1 (one o'clock), and so on, so the gap opens at the top and sweeps clockwise
     * exactly like a countdown dial. Keeping the low slots instead would drain it backwards.
     */
    fun firstOccupiedSlot(remaining: Int): Int =
        (AIR_BUBBLE_COUNT - remaining).coerceIn(0, AIR_BUBBLE_COUNT)

    /**
     * Bubble size multiplier for a ring that is down to [remaining] bubbles: 1x until the
     * ring turns red at [AIR_LOW_THRESHOLD], then one more [AIR_LOW_SIZE_GAIN] for every
     * bubble lost after that — 1.6x at three, 2.2x at two, 2.8x at the last one.
     */
    fun airBubbleSizeScale(remaining: Int): Float =
        if (remaining > AIR_LOW_THRESHOLD) 1f
        else 1f + (AIR_LOW_THRESHOLD - remaining + 1) * AIR_LOW_SIZE_GAIN

    /**
     * [diverX]/[diverY] are the diver's position on THIS surface, in pixels, and
     * [pixelsPerMetre] is the scale the world is being drawn at. Both come from the world
     * camera in `EnPustTil.onRender` — see the class doc for why they are arguments rather than
     * something this file works out for itself.
     */
    fun render(
        surface: Surface,
        sim: DiveSim,
        diverX: Float,
        diverY: Float,
        pixelsPerMetre: Float,
        w: Float,
        h: Float
    )
    {
        drawBanked(surface, sim, h)
        drawClock(surface, sim, w, h)
        drawAirRing(surface, sim, diverX, diverY, pixelsPerMetre)
        drawHeld(surface, sim, diverX, diverY, pixelsPerMetre, h)
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
     * The bubbles occupy fourteen fixed, evenly spaced slots ([airBubbleSlotAngle]) and the
     * ring empties clockwise from 12 o'clock ([firstOccupiedSlot]), and the last few are
     * drawn oversized ([airBubbleSizeScale]) so that "almost out of air" is still a shout
     * when there is only one bubble left to shout with. All three were defects in earlier
     * versions; each is explained where its constants are declared.
     */
    private fun drawAirRing(surface: Surface, sim: DiveSim, diverX: Float, diverY: Float, ppm: Float)
    {
        val remaining = airBubblesRemaining(sim.air, Tuning.BASE_AIR_SECONDS)
        if (remaining <= 0) return

        val low = remaining <= AIR_LOW_THRESHOLD
        val pulse = if (low) 1f + sin(sim.clock * HEARTBEAT_HZ) * HEARTBEAT_AMPLITUDE else 1f
        val radius = AIR_RING_RADIUS_METRES * ppm * pulse
        // The heartbeat pulses the bubbles themselves as well as the orbit. A pulsing ORBIT
        // is only legible while there is a ring to see breathing; with one bubble left it is
        // just a dot jittering a few pixels sideways. Pulsing the size makes that last bubble
        // throb in place, which is the actual warning at the moment it matters most.
        val bubbleSize = AIR_BUBBLE_SIZE_METRES * ppm * airBubbleSizeScale(remaining) * pulse

        surface.setDrawColor(if (low) danger else cold)
        for (slot in firstOccupiedSlot(remaining) until AIR_BUBBLE_COUNT)
        {
            val angle = airBubbleSlotAngle(slot)
            val bx = diverX + cos(angle) * radius
            val by = diverY + sin(angle) * radius
            surface.fillRect(bx - bubbleSize * 0.5f, by - bubbleSize * 0.5f, bubbleSize, bubbleSize)
        }
    }

    /** Enormous amber numerals, attached to the diver, hotter and bigger as they grow. */
    private fun drawHeld(surface: Surface, sim: DiveSim, diverX: Float, diverY: Float, ppm: Float, h: Float)
    {
        if (sim.held <= 0) return

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
