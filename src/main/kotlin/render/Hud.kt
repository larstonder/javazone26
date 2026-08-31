package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
 * Uses [Surface.fillRect] / [Surface.fillRectCentred] ONLY. `Surface.drawQuad`/`drawLine` render
 * nothing at all on macOS/Apple Silicon — silently, no GL error, and still nothing in the shipped
 * Windows jar. See render/Draw.kt, and `DrawTest` for the guard that fails the build if either
 * returns. Anything whose position means a MIDDLE — a bubble in the ring, a marker on the depth
 * tape — is drawn with [Surface.fillRectCentred] rather than a corner worked out here; anything
 * whose position means a top-left corner (the tape body and its backing) keeps [Surface.fillRect].
 */
object Hud
{
    // --- Bubble ring: air is never a number -----------------------------------------
    const val AIR_BUBBLE_COUNT = 14
    const val AIR_LOW_THRESHOLD = 3
    /**
     * How far from the diver the bubbles orbit, in metres.
     *
     * 5.5 -> 6.0 WHEN THE DIVER WENT FROM 6 m TO 9 m. It was re-checked and deliberately left at
     * 5.5 for the 3 -> 6 change; at 9 m it no longer holds, and
     * `DiverSpriteTest.the diver is a tall figure that stays inside the air ring` said so as a
     * build failure rather than letting it reach the booth. The ring is the game's ONLY air
     * warning, so it losing contrast against the body is a real regression, not a cosmetic one.
     *
     *   full ring, 12 o'clock   bubble inner edge 6.0 - 0.45 = 5.55 m   vs head at 4.5 m
     *
     * 6.0 is very nearly the minimum that keeps a full bubble's worth of gap above the head
     * (5.85 would be exact), because every metre here is expensive: the ring is a 12 m disc in a
     * 60 m view already, i.e. a fifth of the screen height, and it must not become the picture.
     *
     * ## THE LOW-AIR WORST CASE IS REACHABLE AT 9 m, AND IS ACCEPTED RATHER THAN DESIGNED OUT
     *
     * The previous version of this comment argued that the tightest case "cannot happen where it
     * would show", because the ring empties CLOCKWISE FROM 12 O'CLOCK ([firstOccupiedSlot]) so
     * slot 0 — directly above the head — is the first to go. That is true and it is not enough.
     * With one bubble left the survivor is slot 13, at 11 o'clock, 2.8x oversized
     * ([AIR_LOW_SIZE_GAIN]) and pulsing; at the heartbeat's trough its bounding box overlaps the
     * diver's by 0.34 m horizontally and 0.98 m vertically. Its CENTRE stays outside the figure
     * (2.21 m out against a 1.48 m half-width), so what is drawn over the diver is the corner of
     * the bubble and not the bubble, and it is drawn on the HUD surface — on top, unlit, fully
     * opaque red. What is occluded is the diver's shoulder; the warning itself is never the thing
     * that is hidden, which is the requirement that actually matters.
     *
     * Designing it out costs a 6.91 m ring, a 13.8 m disc, 23% of the visible column. That is a
     * worse trade than a red bubble clipping a shoulder in the last 1.5 s of a run.
     */
    internal const val AIR_RING_RADIUS_METRES = 6.0f

    internal const val AIR_BUBBLE_SIZE_METRES = 0.9f
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
    // Below the diver, clear of the air ring. Re-checked at 6 m and again at 9 m, and left alone
    // both times: the numerals are top-anchored at 8 m below the diver's CENTRE, so at 9 m they
    // start 3.5 m below his fins (half-height 4.5 m) and 1.55 m below the bottom of the ring's
    // 6 o'clock bubble (6.0 + 0.45). That slot can only be occupied while at least half the ring
    // remains — firstOccupiedSlot(7) is 7 — and at seven bubbles the size gain has not started, so
    // the bubble there is never the oversized one. Nothing to move.
    // internal (not private): HudTest derives the legend's clearance above HELD's highest
    // possible reach from this same constant, rather than duplicating the number.
    internal const val HELD_OFFSET_METRES = 8f
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
    // internal (not private): HudTest asserts the legend clears this exact boundary rather
    // than a hardcoded copy of it.
    internal const val TAPE_TOP_FRACTION = 0.10f
    private const val TAPE_BOTTOM_FRACTION = 0.08f
    private const val TAPE_RIGHT_MARGIN_FRACTION = 0.035f
    private const val TAPE_LABEL_FONT_FRACTION = 0.018f

    /** Backing rectangle overhang, as a fraction of the tape width. See [tapeShadow]. */
    private const val TAPE_OUTLINE_RATIO = 0.5f

    private const val CLOCK_DANGER_SECONDS = 20f

    /**
     * The in-run control legend's height fraction. Between the tape's graduation labels
     * (0.014) and its travelling depth readout (0.018): present, and subordinate to both the
     * clock and HELD.
     */
    const val LEGEND_FONT_FRACTION = 0.016f

    // Equal to MARGIN_FRACTION above (0.02f) - that is a COINCIDENCE, not shared tuning: this is
    // the legend's own inset from the screen edge, decided independently of the BANKED/clock/tape
    // margin. Do not merge these into one constant - doing so would silently move the legend if
    // MARGIN_FRACTION were ever retuned for the other HUD elements.
    private const val LEGEND_MARGIN_FRACTION = 0.02f

    /** The SHOW FPS readout's own inset from the top-right corner. Equal to
     * [LEGEND_MARGIN_FRACTION]/[MARGIN_FRACTION] for the identical reason those two are equal
     * to each other - a coincidence of the same authored margin, kept as its own constant so a
     * future retune of one never silently moves this. */
    private const val FPS_MARGIN_FRACTION = 0.02f

    /** Small: this is a diagnostic, not a HUD element a player is meant to read mid-dive.
     * Between the legend (0.016) and the tape's graduation labels (0.014). */
    private const val FPS_FONT_FRACTION = 0.015f

    private val cold = Color(0.75f, 0.85f, 1f)
    private val danger = Color(1f, 0.25f, 0.2f)

    /**
     * The legend's ink. A SEPARATE pre-allocated Color rather than `cold` at a lower alpha:
     * `cold` is a shared mutable singleton (Color has four mutable float fields), so lowering
     * "its" alpha would silently re-tint BANKED, the clock, the tape handle and every
     * graduation label — and building one per frame is forbidden outright.
     */
    private val legendInk = Color(0.75f, 0.85f, 1f, authoredAlphaFor(0.55f))

    /**
     * WHERE A LINE OF TEXT ACTUALLY SITS RELATIVE TO THE `y` IT IS DRAWN AT — read out of the
     * engine's bytecode rather than guessed, because every alignment below depends on it and
     * getting it wrong is a half-glyph offset that only a photograph would show.
     *
     * `TextRenderer.draw` (pulse-engine-0.13.0.jar, disassembled) measures `ascent` as the
     * largest `-quad.y` over the glyphs it is about to draw — i.e. how far the tallest glyph
     * rises above the baseline — and then offsets the whole run by
     * `ascent - (ascent + lineShift) * yOrigin`, with `lineShift = 0` for a single line. So:
     *
     *   yOrigin = 0     the TOP of the tallest glyph lands on `y`   (the engine's default)
     *   yOrigin = 0.5   the run is CENTRED on `y`
     *   yOrigin = 1     the BASELINE lands on `y`
     *
     * The middle one is the useful one here and it is used for every string this file draws
     * except HELD. It centres the run on a y WITHOUT this file needing to know the font's cap
     * height — which it cannot know, since `Font.getQuad` is baked data. That is what lets the
     * pearl icon share a centre line with BANKED, and the clock sit centred in its box, with no
     * fudge constant to re-tune if the font is ever swapped.
     */
    private const val TEXT_CENTRED_ON_Y = 0.5f

    // --- BANKED: a pearl, then the number ----------------------------------------------
    /**
     * The pearl icon's diameter as a fraction of screen height, and the gap between its rim
     * and the "BANKED" text as a fraction of that diameter.
     *
     * The icon is a touch smaller than the text's own font size so it reads as a bullet
     * belonging to the row rather than as a second element competing with it: BANKED is the
     * quiet running total (design spec §12 — "small, cold white, top-left"), and the loud
     * number on this screen is HELD, attached to the diver.
     */
    private const val BANKED_ICON_DIAMETER_FRACTION = 0.024f
    private const val BANKED_ICON_GAP_RATIO = 0.45f

    /**
     * The icon's amber, deliberately restating `DiveRenderer.pearlColor` rather than sharing it.
     *
     * They are not the same quantity even though they are the same three numbers today. The
     * world's pearl albedo is fed through `DiveRenderer.exposed(...)` on the way to a surface
     * that GI relights and a thresholded bloom then blows out, so what reaches the eye there is
     * depth-dependent. The HUD is not relit and not bloomed (class doc), so this is a flat
     * authored colour: an icon that says "pearls", legible identically at 5 m and at 150 m.
     * Tying the two together would mean the icon dimming as the diver descends, which is the
     * opposite of what a banked-total readout should do.
     */
    private val pearlIcon = Color(1f, 0.78f, 0.35f)

    // --- The clock's box ----------------------------------------------------------------
    /**
     * The clock box, sized from the string it frames rather than from a fixed width.
     *
     * [CLOCK_BOX_GLYPH_WIDTH_EM] is an UPPER bound on a digit's advance in the default font,
     * in ems — the one number here that can only be confirmed by looking at the screen. It is
     * safe in one direction only: the text is centred in the box ([TEXT_CENTRED_ON_Y] and
     * `xOrigin = 0.5`), so over-estimating merely adds air at both ends, while under-estimating
     * would let a digit sit on the border. Erring high is therefore correct, not lazy.
     *
     * Sizing from `text.length` also means the box does not jump when the clock crosses ten
     * minutes and the string grows from "9:59" to "10:00" — a fixed width would have had to be
     * cut for the longer form and would look loose for the whole run.
     */
    private const val CLOCK_BOX_GLYPH_WIDTH_EM = 0.62f
    private const val CLOCK_BOX_PAD_X_EM = 0.5f

    /**
     * Vertical padding, in ems, ABOVE AND BELOW A FULL EM — not above and below the cap height.
     * The box is therefore taller than the glyphs by at least the padding on each side whatever
     * the font's cap height turns out to be, which is exactly the guarantee this file cannot get
     * any other way (see [TEXT_CENTRED_ON_Y]).
     */
    private const val CLOCK_BOX_PAD_Y_EM = 0.30f

    /** Corner radius and border thickness, both as a fraction of the box's own height. */
    private const val CLOCK_BOX_CORNER_RATIO = 0.38f
    private const val CLOCK_BOX_BORDER_RATIO = 0.085f

    /**
     * The dark plate inside the clock's border.
     *
     * Near-opaque on purpose. The clock sits top-centre, which is exactly where the sunlit
     * Shallows water is brightest, and a bordered box whose interior is the water is a ring
     * around a bright field rather than an instrument. It is also what makes the border legible
     * at both ends of a run: the plate, not the water, is what the border has contrast against.
     * States the opacity it wants to DISPLAY at and runs it through [authoredAlphaFor] like
     * everything else on this surface.
     */
    val clockPlate = Color(0.02f, 0.05f, 0.09f, authoredAlphaFor(0.82f))

    /**
     * The dark, semi-transparent plate behind every lifecycle screen's panel (menus, pause,
     * briefing, run-over, initials entry, the attract screen's leaderboard block) — see
     * [render.PanelLayout] for the shared geometry and [PanelLayout.ALPHA]'s doc for the
     * composed-alpha arithmetic on the two screens that already carry a scrim.
     *
     * Same RGB family as [clockPlate] rather than an invented colour: both are "a dark plate
     * behind HUD text on a surface that ranges from bright Shallows to near-black Abyss", and
     * reusing the triple keeps the two kinds of plate reading as one visual language instead of
     * two. Only the alpha differs, and it is built here — once, at class init, never per frame
     * — exactly the way [clockPlate] itself already is.
     */
    val panelPlate = Color(0.02f, 0.05f, 0.09f, authoredAlphaFor(PanelLayout.ALPHA))

    /**
     * The visible STAIRCASE RISER a rounded corner is allowed, in pixels, and the ceiling on how
     * many bands may be spent buying it. See [roundedCornerBands] and [roundedBandHalfWidth].
     *
     * `Surface.drawQuad` renders nothing at all on macOS and there is no circle primitive, so a
     * rounded rectangle here is a stack of [Surface.fillRectCentred] slabs whose widths follow
     * the corner arc. An earlier version of this constant was a flat SIX bands, on the argument
     * that six puts the largest step "well under a pixel-visible fraction of the radius". That
     * is wrong, and wrong in the direction that shows: a corner arc has a VERTICAL TANGENT where
     * it meets the flat cap, so with bands of equal height the width step grows without bound as
     * the cap is approached — the last of six bands on the clock box's 55 px radius (0.0304 x
     * 1800 px of screen height) steps 14 px sideways over a 9 px rise. That is not a rounded
     * corner, it is a chamfered one.
     *
     * Two things fix it together, and neither works alone. The bands are cut at equal ANGLES
     * around the arc rather than at equal heights, which makes them thin exactly where the arc
     * is shallow and bounds the width step at `radius * bandAngle` everywhere instead of letting
     * it diverge. And the count is then derived from that bound rather than fixed, so the shape
     * that needs it pays for it: the clock's 55 px corner takes ~44 bands, the slider handle's
     * 12 px corner takes ~10, and both land on the same riser on any display.
     *
     * 2 px is chosen against a booth display's pixel, not against a fraction of the shape —
     * "smooth" is a property of the pixel grid. The cap is what stops a future oversized radius
     * turning a HUD element into thousands of draw calls; it only bites above a 5K panel (a
     * 0.0304 x h radius reaches 64 bands at h = 2680), and even there it settles at under 3 px
     * of riser. 89 batched `drawTexture` calls for the clock's box is nothing beside the world's
     * own submissions.
     */
    private const val ROUNDED_CORNER_STEP_PIXELS = 2f
    private const val ROUNDED_CORNER_MAX_BANDS = 64

    // --- The depth tape's graduations and slider handle ----------------------------------
    /**
     * The graduation interval, in metres. Ticks and labels land on multiples of this from 0 to
     * the last one that fits inside `Tuning.MAX_DEPTH` — 0/25/50/75/100 as the owner's mockup
     * asks for, and then 125/150 because the column is 160 m deep and a scale that stops
     * two-thirds of the way down is worse than no scale at all: the Abyss is precisely where a
     * player needs to know how far past the point of no return they are.
     */
    private const val TAPE_GRADUATION_METRES = 25f

    /** Tick length and thickness, as fractions of the tape's width. Ticks extend LEFT only. */
    private const val TAPE_TICK_LENGTH_RATIO = 2.8f
    private const val TAPE_TICK_THICKNESS_RATIO = 0.55f

    /** Gap between the end of a tick and the right edge of its label, in tape widths. */
    private const val TAPE_LABEL_GAP_RATIO = 1.2f

    private const val TAPE_GRADUATION_FONT_FRACTION = 0.014f

    /**
     * How close a graduation's label may come to the travelling depth readout before it is
     * dropped for the frame, as a multiple of the graduation label's own font size.
     *
     * Both are outlined strings right-aligned to the same column, so an overlap is not a near
     * miss — it is two black-rimmed strings on the same pixels, which reads as a smudge and
     * costs the readout its legibility exactly when the diver is at a marked depth. The
     * graduation is the one that yields: it is a fixed scale the player can infer from its
     * neighbours, whereas the readout is the live value.
     */
    private const val TAPE_GRADUATION_HIDE_GAP_EM = 1.3f

    /** The slider handle: a lozenge straddling the tape, sized in tape widths. */
    private const val HANDLE_WIDTH_RATIO = 5.5f
    private const val HANDLE_HEIGHT_RATIO = 2.4f
    private const val HANDLE_CORNER_RATIO = 0.45f
    private const val HANDLE_GRIP_WIDTH_RATIO = 0.5f
    private const val HANDLE_GRIP_HEIGHT_RATIO = 0.16f

    /**
     * Grip bars across the handle's face, and their pitch as a multiple of a bar's own height.
     *
     * Three bars at a pitch of two — bar, gap, bar, gap, bar — is the slider-knob idiom, and it
     * is what tells the eye the lozenge is a THING ON the tape rather than a gap in it. Three at
     * pitch 2 occupies 5 bar-heights = 80% of the handle's height ([HANDLE_GRIP_HEIGHT_RATIO] is
     * 0.16), so the outermost bars stop clear of the rounded ends at any resolution; a fourth
     * bar, or a wider pitch, would run into the corner arc.
     */
    private const val HANDLE_GRIP_COUNT = 3
    private const val HANDLE_GRIP_PITCH_RATIO = 2f

    /**
     * The graduation labels, built once at class-init rather than per frame.
     *
     * The tape's scale is fixed for the whole game — `Tuning.MAX_DEPTH` is a compile-time
     * constant — so there is nothing here to recompute, and this keeps the render path
     * allocation-free (CLAUDE.md: HUD text FORMATTING is the exemption, and a constant string is
     * not formatting). It also means the strings exist before any frame is drawn, so
     * `HudTest` can assert every one of them is inside the default font's baked atlas —
     * U+0020..U+011F, above which a glyph renders as nothing at all and consumes no width,
     * silently. Digits and "m" are far inside it; the test is there so a future "150 m" with a
     * non-breaking space, or a prime mark, fails the build instead of vanishing at the booth.
     */
    val graduationLabels: Array<String> =
        Array(graduationCount()) { "${graduationDepth(it).toInt()}m" }

    /**
     * Ticks and their labels: pale, and a step quieter than the tape they measure. The tape's
     * own body is the instrument; the graduations are the reading aid, and at 25 m intervals
     * there are seven of them, so drawing them at the tape's full weight would turn the right
     * edge into a ladder that competes with the diver.
     */
    val graduationMark = Color(0.9f, 0.94f, 1f, authoredAlphaFor(0.72f))

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

    // --- Layout, extracted from the draw calls so it can be asserted without a GL context ---
    // Same pattern as `Framing`, `AttractLayout` and the bubble-ring functions above: what a
    // thing LOOKS like can only be settled by looking, but where it lands relative to the other
    // things on the row is arithmetic, and arithmetic is testable.

    /**
     * The two font sizes the screen-anchored rows are built from.
     *
     * Functions rather than a `h * FRACTION` written at each call site, because each of them is
     * needed in TWO places that must agree: the draw call that renders the string, and the
     * layout function that decides where its box or its row centre is. Two spellings of the same
     * product is how one of them comes to be edited alone — see `fillRectCentred`'s doc for the
     * version of that mistake this project actually shipped.
     */
    fun bankedFontSize(h: Float): Float = h * BANKED_FONT_FRACTION

    fun clockFontSize(h: Float): Float = h * CLOCK_FONT_FRACTION

    /** The centre line the pearl icon and the BANKED text share. */
    fun bankedRowCentreY(h: Float): Float = h * MARGIN_FRACTION + bankedFontSize(h) * 0.5f

    fun bankedIconDiameter(h: Float): Float = h * BANKED_ICON_DIAMETER_FRACTION

    /** Icon first, its LEFT RIM on the margin — so the row starts where every other margin is. */
    fun bankedIconCentreX(h: Float): Float = h * MARGIN_FRACTION + bankedIconDiameter(h) * 0.5f

    /**
     * Where "BANKED n" starts. Left-aligned (`xOrigin = 0`), so this is the text's left edge and
     * the gap to the icon's rim is exactly [BANKED_ICON_GAP_RATIO] of the icon's diameter — the
     * relationship that keeps the pair reading as one row at any resolution, and the one that
     * breaks silently if either is ever re-anchored to the margin on its own.
     */
    fun bankedTextX(h: Float): Float =
        bankedIconCentreX(h) + bankedIconDiameter(h) * (0.5f + BANKED_ICON_GAP_RATIO)

    /** The clock box's width for a [glyphCount]-character time string. See the constants' doc. */
    fun clockBoxWidth(fontSize: Float, glyphCount: Int): Float =
        fontSize * (glyphCount * CLOCK_BOX_GLYPH_WIDTH_EM + 2f * CLOCK_BOX_PAD_X_EM)

    fun clockBoxHeight(fontSize: Float): Float = fontSize * (1f + 2f * CLOCK_BOX_PAD_Y_EM)

    /** The box hangs from the top margin, and the clock is then centred inside the box. */
    fun clockBoxCentreY(h: Float): Float =
        h * MARGIN_FRACTION + clockBoxHeight(clockFontSize(h)) * 0.5f

    /** Top of the legend's text box. Same top margin `drawBanked` uses. */
    fun legendBaselineY(h: Float): Float = h * LEGEND_MARGIN_FRACTION

    /** The legend is right-aligned, so this is where its text ENDS. */
    fun legendRightX(w: Float, h: Float): Float = w - h * LEGEND_MARGIN_FRACTION

    /**
     * Where the frame-rate readout's text ENDS — right-aligned in the TOP-right corner.
     *
     * Shares [LEGEND_MARGIN_FRACTION] with the legend rather than declaring a margin of its own,
     * so the two read as inset by the same amount from opposite corners; a second, nearly-equal
     * constant is how that alignment silently drifts. Height-derived like everything else here,
     * because `engine.window.width/height` are physical framebuffer pixels and the display's
     * aspect ratio is not known in advance.
     */
    fun fpsRightX(w: Float, h: Float): Float = w - h * LEGEND_MARGIN_FRACTION

    /**
     * Top of the frame-rate readout's text box. Text grows DOWNWARD from its anchor, the same
     * convention every layout object in this project uses, so the block occupies
     * `fpsTopY .. fpsTopY + h * FPS_FONT_FRACTION`.
     *
     * Top-right rather than bottom-right deliberately: the legend already owns the bottom margin,
     * and the depth tape runs down the right-hand side during a run — the top corner is the one
     * area no in-run element claims.
     */
    fun fpsTopY(h: Float): Float = h * LEGEND_MARGIN_FRACTION

    /**
     * Half the width of a rounded rectangle at a row [dy] from its centre — the whole geometry
     * of [fillRoundedRect], pulled out because it is the part that can be wrong.
     *
     * Straight through the middle band, then the corner arc: the arc's centre sits
     * `radius` in from both the side and the end, so at a row `over` past the straight section
     * the half-width is `halfWidth - radius + sqrt(radius^2 - over^2)`. With `radius = 0` this
     * degenerates to a plain rectangle at every row, which is what makes the same function
     * usable for a square-cornered shape without a second code path.
     *
     * [radius] is clamped to the smaller half-extent: a radius larger than that describes no
     * shape (the two corner arcs on a side would overlap), and clamping turns a mis-tuned
     * constant into a stadium instead of a `sqrt` of a negative.
     */
    fun roundedBandHalfWidth(halfWidth: Float, halfHeight: Float, radius: Float, dy: Float): Float
    {
        val r = radius.coerceIn(0f, minOf(halfWidth, halfHeight))
        val straight = halfHeight - r
        val over = (abs(dy) - straight).coerceIn(0f, r)
        return halfWidth - r + sqrt(r * r - over * over)
    }

    /** Where depth [depth] lands on a tape running from [top] down [span] pixels. */
    fun tapeY(depth: Float, top: Float, span: Float): Float =
        top + (depth / Tuning.MAX_DEPTH).coerceIn(0f, 1f) * span

    /** How far a graduation tick reaches LEFT of the tape's left edge, for a [tapeWidth] tape. */
    fun tapeTickLength(tapeWidth: Float): Float = tapeWidth * TAPE_TICK_LENGTH_RATIO

    /**
     * The column both right-aligned strings on the tape share: the graduation labels and the
     * travelling depth readout. [tapeX] is the tape's own left edge.
     *
     * Extracted because it is the number that decides whether the scale is READABLE: it has to
     * clear the slider handle ([handleWidth]), which straddles the tape and is the widest thing
     * on that edge, or a label lands under the handle at the one depth it is describing.
     * `HudTest` asserts that clearance; it cannot be seen in the source, because the two are
     * expressed in different constants (tick length plus gap on one side, handle width on the
     * other) and neither mentions the other.
     */
    fun tapeLabelRightX(tapeX: Float, tapeWidth: Float): Float =
        tapeX - tapeTickLength(tapeWidth) - tapeWidth * TAPE_LABEL_GAP_RATIO

    /** The slider handle's size, in pixels, for a [tapeWidth] tape. It straddles the tape. */
    fun handleWidth(tapeWidth: Float): Float = tapeWidth * HANDLE_WIDTH_RATIO

    fun handleHeight(tapeWidth: Float): Float = tapeWidth * HANDLE_HEIGHT_RATIO

    /**
     * How many graduations the tape carries: every multiple of [TAPE_GRADUATION_METRES] from
     * zero up to and including the last one that fits inside `Tuning.MAX_DEPTH`. Derived rather
     * than listed so that changing either constant cannot leave a tick hanging off the end of
     * the tape or the bottom third unmarked.
     */
    fun graduationCount(): Int = floor(Tuning.MAX_DEPTH / TAPE_GRADUATION_METRES).toInt() + 1

    fun graduationDepth(index: Int): Float = index * TAPE_GRADUATION_METRES

    /**
     * How many bands one corner arc of [radius] pixels is drawn as, so that no staircase riser
     * exceeds [ROUNDED_CORNER_STEP_PIXELS]. See that constant for the whole argument.
     *
     * With the arc cut at equal angles the widest riser is `radius * bandAngle` and the arc
     * spans a quarter turn, so `bands = radius * (TAU / 4) / step` rounded up. Pure, and pinned
     * by `HudTest` in the unit that matters — pixels of riser at a booth-sized radius — because
     * "how many bands is enough" is not a number anyone can read off the source.
     */
    fun roundedCornerBands(radius: Float): Int =
        ceil(radius * TAU * 0.25f / ROUNDED_CORNER_STEP_PIXELS).toInt().coerceIn(1, ROUNDED_CORNER_MAX_BANDS)

    /**
     * Whether a graduation label at [graduationY] is far enough from the travelling depth
     * readout at [handleY] to be drawn this frame. See [TAPE_GRADUATION_HIDE_GAP_EM].
     */
    fun graduationLabelIsClearOfHandle(graduationY: Float, handleY: Float, minGap: Float): Boolean =
        abs(graduationY - handleY) >= minGap

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
        h: Float,
        aimDegrees: Float
    )
    {
        drawBanked(surface, sim, h)
        drawClock(surface, sim, w, h)
        drawAirRing(surface, sim, diverX, diverY, pixelsPerMetre, aimDegrees)
        drawHeld(surface, sim, diverX, diverY, pixelsPerMetre, h)
        drawDepthTape(surface, sim, w, h)
    }

    /**
     * The one-line "what do I press" legend, top-right, for the whole run.
     *
     * WHY TOP-RIGHT AND NOT BOTTOM-LEFT, which is where a legend conventionally goes: the
     * bottom-left corner is not free. `DiveCamera` clamps the camera's lag at
     * `DIVER_MAX_FRACTION` (0.75) of the visible depth, and that fraction is measured against
     * the diver's CENTRE — `sim.depth` is what both `fillRectCentred` draws the sprite around
     * (`DiveRenderer.drawDiver`) and what `diverY` (this file's `render` entry point) is derived
     * from, so 0.75h is the figure the rest of this comment's reasoning is built on. Add the
     * sprite's own half-height on top of that — `Framing.DIVER_HEIGHT_METRES` (9) /
     * `Framing.VISIBLE_DEPTH_METRES` (60) / 2 = 0.075 — and the diver's sprite can reach 0.825h
     * at its lowest, bottom edge included. [HELD_OFFSET_METRES] then hangs the held count a
     * further 8 m (0.133h) below the diver's CENTRE at a font that grows with the haul — worst
     * case the numerals straddle the bottom margin entirely, and they are centred on a diver
     * whose x roams the whole column. Top-right is provably clear instead: HELD hangs BELOW a
     * diver bounded above by `DIVER_MIN_FRACTION`, so it can never reach the top strip at all;
     * the depth tape starts at `TAPE_TOP_FRACTION`; and BANKED is top-left. The only relationship
     * left that can fail is the clock box, which `HudTest` pins numerically at 4:3.
     */
    fun renderControlLegend(surface: Surface, text: String, w: Float, h: Float)
    {
        surface.drawTextWithOutline(
            text,
            legendRightX(w, h), legendBaselineY(h),
            h * LEGEND_FONT_FRACTION, h, legendInk, xOrigin = 1f
        )
    }

    /**
     * Top-right, small — the SHOW FPS readout (`GameSettings.showFps`, drawn from
     * `EnPustTil.renderGame` in every lifecycle state, unlike everything else in this file,
     * which only appears once a run exists). Provably clear of every other element the same
     * way `renderControlLegend`'s own doc argues [legendRightX]/[legendBaselineY] are: HELD
     * hangs BELOW the diver and can never reach the top strip, BANKED and the clock box are
     * top-LEFT, and [FPS_MARGIN_FRACTION] (0.02, same inset as the legend's own margin — a
     * coincidence, not shared tuning, exactly as [LEGEND_MARGIN_FRACTION]'s own comment argues
     * for the identical reason) keeps this well above [TAPE_TOP_FRACTION] (0.10), where the
     * depth tape's right-edge furniture begins.
     *
     * [text] is a CACHED, pre-formatted string — see `EnPustTil.rebuildFpsHint`'s own doc for
     * why the render path must never call `String.format` per frame (CLAUDE.md's no-per-frame-
     * allocation rule; `String.format` parses the format string and boxes every argument). This
     * function only draws whatever it is handed.
     */
    fun renderFpsReadout(surface: Surface, text: String, w: Float, h: Float)
    {
        surface.drawTextWithOutline(
            text,
            fpsRightX(w, h), fpsTopY(h),
            h * FPS_FONT_FRACTION, h, legendInk, xOrigin = 1f
        )
    }

    /**
     * A dark rounded panel CENTRED on ([centreX], [centreY]) — the surface every lifecycle
     * screen's text sits on, per [render.PanelLayout]. Public, unlike [fillRoundedRect] below,
     * because every panel draw site lives in `EnPustTil.kt` (the default package, on the other
     * side of the package boundary this file's own class doc describes): this is the one
     * function that crosses it, so the corner-stepping maths [fillRoundedRect] owns stays
     * declared once rather than being re-taught to the default package.
     *
     * Colour is fixed ([panelPlate], built once — see its own doc) because every panel in this
     * design is the same plate, not a per-screen choice; [width]/[height]/[radius] are what
     * `PanelLayout.bounds`/`PanelLayout.cornerRadius` compute per screen.
     */
    fun renderPanel(surface: Surface, centreX: Float, centreY: Float, width: Float, height: Float, radius: Float)
    {
        surface.setDrawColor(panelPlate)
        surface.fillRoundedRect(centreX, centreY, width, height, radius)
    }

    /**
     * A rounded rectangle CENTRED on ([centreX], [centreY]), drawn as a stack of
     * [Surface.fillRectCentred] slabs: one through the straight middle, then
     * [roundedCornerBands] more at each end whose heights step round the corner arc at equal
     * ANGLES and whose widths come from [roundedBandHalfWidth].
     *
     * There is no circle primitive and no rounded-rect primitive to reach for, and `drawQuad`
     * would not help even if it drew anything on macOS (render/Draw.kt) — it is an axis-aligned
     * rect like every other primitive here. So the shape is rasterised out of rects, and two
     * choices decide whether it looks like a curve. Equal angles rather than equal heights, for
     * the reason [ROUNDED_CORNER_STEP_PIXELS] gives at length. And each band's width sampled at
     * its own MIDPOINT, so the stack straddles the true curve and the error alternates sign
     * along the corner, rather than at an edge, which would make every corner uniformly clipped
     * or uniformly square.
     *
     * A private extension here rather than a `Draw.kt` helper: the clock's box and the depth
     * tape's slider handle are the only two rounded shapes in the game and both live in this
     * file, while `Draw.kt`'s contract is the ORIGIN AND ANGLE convention shared with two engine
     * renderers we do not own. A shape decomposition is a different kind of thing.
     */
    private fun Surface.fillRoundedRect(centreX: Float, centreY: Float, width: Float, height: Float, radius: Float)
    {
        val halfWidth = width * 0.5f
        val halfHeight = height * 0.5f
        val r = radius.coerceIn(0f, minOf(halfWidth, halfHeight))
        val straight = halfHeight - r

        // The middle slab is everything the two corner arcs do not cover, at full width.
        if (straight > 0f) fillRectCentred(centreX, centreY, width, straight * 2f)
        if (r <= 0f) return

        // `over` walks from 0 at the start of the arc to the full radius at the flat cap, in
        // steps of `r * sin`, so the bands are thin where the arc is shallow. Each band's far
        // edge is the next one's near edge, which is what keeps the stack gap-free: the two
        // are the same number, computed once.
        val bands = roundedCornerBands(r)
        val bandAngle = TAU * 0.25f / bands
        var nearOver = 0f
        for (band in 0 until bands)
        {
            val farOver = r * sin((band + 1) * bandAngle)
            val dy = straight + (nearOver + farOver) * 0.5f
            val bandHeight = farOver - nearOver
            val bandWidth = roundedBandHalfWidth(halfWidth, halfHeight, r, dy) * 2f
            fillRectCentred(centreX, centreY - dy, bandWidth, bandHeight)
            fillRectCentred(centreX, centreY + dy, bandWidth, bandHeight)
            nearOver = farOver
        }
    }

    private fun drawBanked(surface: Surface, sim: DiveSim, h: Float)
    {
        val centreY = bankedRowCentreY(h)
        val diameter = bankedIconDiameter(h)

        // THE ICON IS A REAL PEARL, through the very shader the ones in the water are drawn with
        // ([IridescenceRenderer] + [IridescentMaterial.PEARL]). Two things fall out of that: the
        // thing BANKED counts and the thing beside it cannot drift apart into two different
        // ideas of what a pearl looks like, and the HUD gets a ROUND icon on a surface with no
        // circle primitive on it (the alternative, [fillRoundedRect], is thirteen draw calls of
        // stepped slabs where this is one).
        //
        // [bankedIconDiameter] is the icon's SQUARE — the box the layout above reserves for it,
        // and exactly what the fallback below fills — and the quad handed to the shader is that
        // square grown by `equalAreaQuad` so the disc it inscribes covers the same area. That is
        // the house rule (`IridescenceGeometryTest` scans the source for it), and it is the
        // right one here for the reason it is right for a ring bubble: without it the icon would
        // lose a fifth of its ink the moment the renderer became available, i.e. between frame
        // one and frame two. The disc's rim therefore sits about 6% of a diameter proud of the
        // reserved box on each side — a couple of pixels at booth resolution, and well inside
        // the gap [bankedTextX] keeps to the text.
        //
        // Its sheen is measured from wherever this frame's [drawAirRing] put the light — one
        // light source per surface per frame, by design (IridescenceRenderer.setLightSource), so
        // the icon and the ring's bubbles cannot disagree about where the torch is. Draw order
        // does not enter into it: `lightPos` is a per-batch uniform, not per-instance.
        val iridescence = IridescenceRenderer.of(surface)
        surface.setDrawColor(pearlIcon)
        if (iridescence != null)
        {
            val quad = IridescenceRenderer.equalAreaQuad(diameter)
            iridescence.draw(bankedIconCentreX(h), centreY, quad, quad, IridescentMaterial.PEARL)
        }
        // Frame one, before the engine has run the deferred `addRenderer` init: a flat square
        // stands in, so the row keeps its shape instead of the text jumping left for a frame.
        // Same fallback and same reason as [drawAirRing]'s.
        else surface.fillRectCentred(bankedIconCentreX(h), centreY, diameter, diameter)

        // Top-left, same bright-shallows-to-black-abyss background as everything else on
        // this surface (see class doc) — outlined so it stays legible at the surface. Centred
        // on the icon's own centre line ([TEXT_CENTRED_ON_Y]) rather than dropped by a font
        // size, which is the whole of what makes the pair read as one row.
        surface.drawTextWithOutline(
            "BANKED ${sim.banked}", bankedTextX(h), centreY,
            bankedFontSize(h), h, cold, yOrigin = TEXT_CENTRED_ON_Y
        )
    }

    /**
     * The clock, in a rounded bordered box (the owner's mockup).
     *
     * THE BORDER IS THE CLOCK'S OWN COLOUR, so the whole instrument turns red under
     * [CLOCK_DANGER_SECONDS] rather than only the digits. The digits are five glyphs at the top
     * of a busy screen; the box is a shape, and a shape changing colour is visible in peripheral
     * vision, which is where a player watching the diver actually sees the clock from.
     *
     * The border is drawn as the WHOLE outer box and the plate then laid over it, rather than as
     * a ring: a ring would need its own inner-and-outer band decomposition at every row, for a
     * shape that is on screen at one size. The cost of doing it this way is that [clockPlate] is
     * not fully opaque, so the interior carries some fraction of the border's colour — a faint
     * wash that heats with the clock. That is the reason the plate is authored near-opaque
     * instead of at a tasteful half, where the border's colour rather than the plate's would
     * decide what the interior looks like. The exact fraction is a capture question, not a
     * source one: this surface pre-multiplies RGB by alpha AND stores alpha squared (see
     * [tapeBg]), so the two weights are not the same number.
     */
    private fun drawClock(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        val fontSize = clockFontSize(h)
        val minutes = (sim.clock / 60f).toInt()
        val seconds = (sim.clock % 60f).toInt()
        val color = if (sim.clock < CLOCK_DANGER_SECONDS) danger else cold

        // The one per-frame allocation on this path, and an explicitly exempt one (CLAUDE.md:
        // HUD text formatting). It is formatted BEFORE the box is drawn because the box is
        // sized from its length — "9:59" and "10:00" are not the same width.
        val text = "%d:%02d".format(minutes, seconds)

        val centreX = w * 0.5f
        val centreY = clockBoxCentreY(h)
        val boxWidth = clockBoxWidth(fontSize, text.length)
        val boxHeight = clockBoxHeight(fontSize)
        val border = boxHeight * CLOCK_BOX_BORDER_RATIO
        val radius = boxHeight * CLOCK_BOX_CORNER_RATIO

        surface.setDrawColor(color)
        surface.fillRoundedRect(centreX, centreY, boxWidth, boxHeight, radius)

        // The plate is inset by the border thickness on every side and its corner radius is
        // reduced by exactly the same amount, which is what keeps the border a CONSTANT
        // thickness around the corner instead of pinching to nothing at 45 degrees.
        surface.setDrawColor(clockPlate)
        surface.fillRoundedRect(centreX, centreY, boxWidth - border * 2f, boxHeight - border * 2f, radius - border)

        // Top-centre — exactly where the bright Shallows water sits. Flat cold-blue text
        // here is the low-contrast pairing that motivated this outline in the first place;
        // the plate helps, but the box is transparent enough that the outline still earns its
        // second draw call.
        surface.drawTextWithOutline(
            text, centreX, centreY, fontSize, h, color,
            xOrigin = 0.5f, yOrigin = TEXT_CENTRED_ON_Y
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
    private fun drawAirRing(surface: Surface, sim: DiveSim, diverX: Float, diverY: Float, ppm: Float, aimDegrees: Float)
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

        // THE SAME SHADER AS THE PEARLS, ON THIS SURFACE'S OWN CAMERA. `IridescenceRenderer` is
        // a `BatchRenderer` attached per-surface, so the instance found here uploads the HUD's
        // identity camera and everything below stays in screen PIXELS — this file still owns no
        // coordinate maths and still never sees a metre. See IridescenceRenderer's class doc.
        //
        // The torch's position on this surface comes from `DiveLighting.torchOffset*` scaled by
        // `ppm`, which is the SAME derivation the world renderer uses at scale 1 and the same one
        // the light itself is emitted from. Deriving the offset again here with a local cos/sin
        // is the duplicated-derivation shape of the shipped 6ea1f53 bug; it would show up as the
        // ring's shimmer and the pearls' colour bands disagreeing about which way the diver faces.
        val iridescence = IridescenceRenderer.of(surface)
        iridescence?.setLightSource(
            diverX + DiveLighting.torchOffsetX(aimDegrees, ppm),
            diverY + DiveLighting.torchOffsetY(aimDegrees, ppm)
        )

        // The draw colour stays FULLY OPAQUE, unchanged from before this commit. The
        // translucency is the material's ([IridescentMaterial.BUBBLE]), and it is applied
        // per-fragment as a curve from the bubble's centre to its rim — which is the point:
        // authoring it as a flat draw-colour alpha here would fade the SILHOUETTE too, and the
        // silhouette is what carries the count, the clockwise depletion and the low-air throb.
        // See that material's doc for how its numbers were chosen against this surface's alpha
        // behaviour.
        surface.setDrawColor(if (low) danger else cold)

        for (slot in firstOccupiedSlot(remaining) until AIR_BUBBLE_COUNT)
        {
            val angle = airBubbleSlotAngle(slot)
            val bx = diverX + cos(angle) * radius
            val by = diverY + sin(angle) * radius
            // Flat square fallback — identical to what shipped before this commit — for the
            // first frame, before the engine has run the deferred `addRenderer` init. The ring
            // is the game's only air warning and must never be the thing that is missing.
            //
            // The quad is grown so the DISC the shader inscribes in it covers the area the
            // square bubble did ([IridescenceRenderer.EQUAL_AREA_DISC_SCALE]). Here that is a
            // gameplay requirement rather than an exposure one: every bubble losing a fifth of
            // its area is the air warning getting quieter, and the warning getting quieter is
            // precisely the regression AIR_LOW_SIZE_GAIN exists to fight.
            if (iridescence != null)
            {
                val quad = IridescenceRenderer.equalAreaQuad(bubbleSize)
                iridescence.draw(bx, by, quad, quad, IridescentMaterial.BUBBLE)
            }
            else surface.fillRectCentred(bx, by, bubbleSize, bubbleSize)
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

    /**
     * Depth tape down the right edge: a graduated scale, the point-of-no-return marker, and a
     * slider handle carrying the live depth.
     *
     * The tape used to be a bar with two dashes on it, which is a picture of the diver's depth
     * and not a picture of HOW DEEP THAT IS. The graduations are what make the point-of-no-return
     * mark quantitative — "the mercy line is at sixty, I am at ninety" — and the handle is what
     * says the mark is a fixed thing on a scale and the diver is the thing moving along it. Both
     * are the owner's mockup; the reading they buy is why they are worth the draw calls.
     */
    private fun drawDepthTape(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        val tapeWidth = h * TAPE_WIDTH_FRACTION
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

        // Both markers straddle the tape's own centre line, which is what `tapeCentreX` names.
        // Written as a centre and a size ([fillRectCentred]) rather than as a corner: a marker's
        // meaning is "the tape, at THIS depth", and the two corner expressions this replaces
        // ("x - (markWidth - tapeWidth) * 0.5f" and "x - tapeWidth") were two different-looking
        // ways of saying the same thing, which is how one of them comes to be edited alone.
        val tapeCentreX = x + tapeWidth * 0.5f

        // Every y on the tape goes through [tapeY]. The three call sites below used to be three
        // copies of `top + (d / MAX_DEPTH).coerceIn(0f, 1f) * span`, which is the shape of
        // duplicated derivation `fillRectCentred`'s doc records shipping as a real bug: a scale
        // whose graduations and whose handle disagree about where a depth is would be worse than
        // no scale, and it would be invisible in the source.
        val handleY = tapeY(sim.depth, top, span)
        val labelRightX = tapeLabelRightX(x, tapeWidth)

        // --- The graduated scale --------------------------------------------------------
        val tickLength = tapeTickLength(tapeWidth)
        val tickThickness = tapeWidth * TAPE_TICK_THICKNESS_RATIO
        val graduationFont = h * TAPE_GRADUATION_FONT_FRACTION
        val labelHideGap = graduationFont * TAPE_GRADUATION_HIDE_GAP_EM
        for (index in 0 until graduationCount())
        {
            val y = tapeY(graduationDepth(index), top, span)
            // Re-set per iteration because drawTextWithOutline leaves the draw colour on its
            // own text colour — shared surface state, the leak that once put a whole frame of
            // world geometry at 70% opacity (see the shaft-tint note in the handover spec).
            surface.setDrawColor(graduationMark)
            surface.fillRectCentred(x - tickLength * 0.5f, y, tickLength, tickThickness)

            if (graduationLabelIsClearOfHandle(y, handleY, labelHideGap))
            {
                surface.drawTextWithOutline(
                    graduationLabels[index], labelRightX, y,
                    graduationFont, h, graduationMark, xOrigin = 1f, yOrigin = TEXT_CENTRED_ON_Y
                )
            }
        }

        // Point of no return: the mercy that teaches the economy without a word of text. Drawn
        // after the graduations and before the handle — it has to out-read the scale, and the
        // live handle has to out-read it.
        surface.setDrawColor(noReturnMark)
        surface.fillRectCentred(tapeCentreX, tapeY(sim.maxSafeDepth(), top, span), tapeWidth * 6f, tapeWidth)

        // --- The slider handle, carrying the live depth ----------------------------------
        val handleColor = if (sim.canStillReturn()) cold else danger
        val gripWidth = handleWidth(tapeWidth) * HANDLE_GRIP_WIDTH_RATIO
        val gripHeight = handleHeight(tapeWidth) * HANDLE_GRIP_HEIGHT_RATIO

        surface.setDrawColor(handleColor)
        surface.fillRoundedRect(
            tapeCentreX, handleY, handleWidth(tapeWidth), handleHeight(tapeWidth),
            handleHeight(tapeWidth) * HANDLE_CORNER_RATIO
        )

        // The grip bars are the tape's own dark backing colour, so the handle reads as a
        // machined part of the same instrument rather than as a second element with a palette
        // of its own — and black is the only thing guaranteed to have contrast against BOTH
        // handle colours, which is not true of any tint.
        surface.setDrawColor(tapeShadow)
        for (bar in 0 until HANDLE_GRIP_COUNT)
        {
            val dy = (bar - (HANDLE_GRIP_COUNT - 1) * 0.5f) * gripHeight * HANDLE_GRIP_PITCH_RATIO
            surface.fillRectCentred(tapeCentreX, handleY + dy, gripWidth, gripHeight)
        }

        // The readout travels the whole tape as depth increases — the same bright-to-black
        // span everything else on this surface crosses — so this label needs the outline just
        // as much as the clock does. It shares the graduations' right-aligned column, which is
        // what makes [graduationLabelIsClearOfHandle] a real collision test rather than a
        // guess: two outlined strings that overlap there are on the same pixels, not merely
        // near each other. It takes the HANDLE's colour rather than a fixed cold, because the
        // two are one object — a knob with its value written beside it — and the colour is the
        // "you can no longer get back" warning, which is exactly the moment the number matters.
        surface.drawTextWithOutline(
            "%.0fm".format(sim.depth),
            labelRightX, handleY,
            h * TAPE_LABEL_FONT_FRACTION, h, handleColor, xOrigin = 1f, yOrigin = TEXT_CENTRED_ON_Y
        )
    }
}
