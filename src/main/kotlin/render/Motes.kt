package render

import dive.Tuning
import dive.Zone
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.surface.Surface
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * MARINE SNOW — the slow blue motes suspended in the water, on a surface of their own.
 *
 * The owner asked for *"some kind of ambient glow in the sea... a particle system of floating
 * particles that glow blue"*. What follows is that, built as a **stateless lattice** rather than
 * as a particle system: there is no array of particles, nothing is spawned, nothing is retired and
 * nothing is allocated per frame. World space is divided into a fixed grid of
 * [CELL_METRES]-square cells, and each cell's one mote — its offset inside the cell, its size, its
 * brightness, its drift rates and its pulse phase — is DERIVED FROM A HASH of the cell's integer
 * coordinates.
 *
 * Four things fall out of that, and each of them is the reason a real particle system was not
 * built:
 *
 *  - **Culling by construction.** Only the cells overlapping the camera's visible rect are ever
 *    visited, so the cost is a function of the screen and not of the 160 m column. A particle
 *    array would either be sized for the whole column and mostly off screen, or be respawned at
 *    the frame edges — which is the thing that makes particle fields pop when the camera moves.
 *  - **Infinite extent, for free.** The lattice is defined at every integer cell, so it covers the
 *    whole column and anything beyond it without a bound being chosen.
 *  - **Determinism.** The frame is a pure function of the camera rect and the animation clock.
 *    That is what the capture harness in `HARNESS.md` needs (see [PIN_ENV]) and it is what a
 *    spawner with its own RNG cannot offer.
 *  - **Zero allocation.** The render path is a double loop over `Int`s with primitive floats in
 *    and out, which is the rule `CLAUDE.md` states for everything in `render/`.
 *
 * ## THE SURFACE: THEY ARE ON `main`, AND THAT WAS RESOLVED THE HARD WAY
 *
 * `GlobalIlluminationSystem` MULTIPLIES `mainSurface` by the computed light map (`targetSurface`
 * is the literal string `"main"` — the citation is in `CLAUDE.md`'s platform constraints and in
 * [LightShafts]'s class doc). The motes are drawn there, by [DiveRenderer], so the multiply
 * applies to them exactly as it does to the water, the rock and the pearls.
 *
 * **They started on a surface of their own, and it was a mistake worth recording**, because the
 * argument for it is seductive and wrong. It ran: the multiply scales a mote toward black exactly
 * where the feature is supposed to work, the deep, so give it its own surface the way [Sky] does
 * and let it escape. What that actually bought was a field that was EXEMPT from the thing that
 * makes the deep dark — measured at 140 m with `EPT_DEPTH`, the motes were the brightest objects
 * in the Abyss (peak 213.5 against the brightest pearl's 194.2), a starfield laid over a black
 * frame, out-shining the pearls the game is about. The owner: *"put them back on main so GI
 * darkens them too"*.
 *
 * The exemption also had two costs that were being paid without being noticed. Compositing an
 * extra transparent surface put a mote's contribution through the backbuffer blend a second time,
 * so it arrived cubed in alpha while its occlusion of the water was only squared — arithmetic
 * `MOTE_ALPHA`'s doc used to have to reason about and no longer does. And ADDITIVE, the one thing
 * the separate surface genuinely bought (overlapping motes summing rather than occluding), turned
 * out to be unreachable against the world anyway: `BackBufferBaseState` hardcodes
 * `glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)` once, before the loop over surfaces, and
 * `glBlendFuncSeparate` appears nowhere in the jar. So the separate surface cost two composites
 * and bought only intra-field additivity.
 *
 * **What replaces the exemption is better than it was.** In the Abyss an ordinary mote is now
 * black, and the GLOWING quarter ([GLOW_IN]) still reads because each one lights its own body
 * through GI. That is bioluminescence meaning bioluminescence — the specks that make their own
 * light — rather than every speck being turned up by a table. And it gives the torch something to
 * do: a beam sweeping the dark now reveals the unlit motes as dust, which a surface exempt from
 * the light map could never have shown.
 *
 * Being on `main` means sharing its camera by construction, so a mote at world `(x, depth)` goes
 * through the identical matrix as the water it is suspended in — built once per frame in
 * `gfx.initFrame` before any game code runs.
 *
 * ## COLOUR IS A GAME MECHANIC HERE, NOT AN ART PREFERENCE
 *
 * The anglerfish's lure is drawn IDENTICALLY to a pearl — `AnglerfishDisguiseTest` compares the
 * two draw calls and the two `drawLight` argument lists textually — and the only tell is motion.
 * `docs/superpowers/specs/2026-08-11-outstanding-work.md` §1.1 names filling the scene with
 * glowing points as the thing that destroys that trap, and names the cheapest resolution: scenery
 * glows a visibly different colour from the pearls' warm amber. So the motes are COOL BLUE
 * ([MOTE_RED]/[MOTE_GREEN]/[MOTE_BLUE]), they are smaller than a pearl ([MAX_SIZE_METRES] against
 * `Framing.PEARL_SIZE_METRES`), and they are dimmer. `MotesTest` asserts all three against
 * `DiveRenderer`'s own pearl colour rather than against a copied constant, so warming them back
 * toward amber fails the build.
 *
 * ## WHAT IT DELIBERATELY IS NOT
 *
 * A mote is not a `drawLight` and casts nothing. Two hundred GI emitters would be two hundred
 * light sources in a radiance-cascade solve, and — far worse — an emitter is a REGION that
 * rasterises into the scene (`LightEmitter`'s class doc), so each one would also be an occluder
 * of the diver's torch. The motes are a foreground overlay that the water is seen through, which
 * is what marine snow in front of a camera actually is.
 *
 * They are also NOT restricted to the play column, unlike the god rays ([LightShafts.halfWidth]).
 * The shafts stop at `Tuning.COLUMN_HALF_WIDTH` because they are light entering water and the rock
 * is not water, and their continuous band left a measured seam there that had to be faded out.
 * Neither half of that applies to a sparse field of dots: what a lateral cut-off would produce is
 * not a seam but a DENSITY step — a vertical strip of frame with no motes in it, 9 m inside each
 * edge at 16:9 — which is a stronger cue than the edge it would be avoiding. And a mote in front
 * of the cliff is not wrong: it is suspended matter in the water BETWEEN the camera and the wall.
 */
object Motes
{

    // ---- The lattice ---------------------------------------------------------------------------

    /**
     * The grid pitch, in METRES. One mote per cell, so this is the density dial and the only one.
     *
     * Chosen against the mote COUNT rather than against the look, because the count is the thing
     * that has to hold at every aspect ratio the booth panel might turn out to be — a pitch is the
     * right dial for that and a count is not, since a count would have to be re-derived per panel
     * while the DENSITY (motes per square metre of water) is what the eye actually judges.
     *
     * Measured by `MotesTest`, from the same visible rect the camera produces and the same alpha
     * threshold [render] skips on, swept over a whole dive:
     *
     * ```
     *   16:9   1920x1080   98.52 x 55.42 m    180 - 198 motes
     *   4:3    2400x1800   80.00 x 60.00 m    154 - 168
     *   21:9   2560x1080   98.52 x 41.56 m    144 - 162
     *   32:9   3840x1080   98.52 x 27.71 m    105 - 126
     * ```
     *
     * (16:9 and wider are in the width-capped regime, so what shrinks with aspect is the visible
     * DEPTH — see `CLAUDE.md`'s two-regime note.) Sparse enough that the field reads as suspended
     * matter rather than as fog or as snowfall, dense enough that there are always some in frame.
     */
    const val CELL_METRES = 6f

    /**
     * A hard ceiling on cells visited in one frame, and it is a GUARD rather than a budget.
     *
     * The visible rect is computed by the engine by inverting the frame's own matrix, and on frame
     * one — before `CameraRig` has written a scale — or midway through a window resize it can be
     * empty, inverted or enormous. 180 motes is the design case; a rect a hundred times too large
     * would be 18 000 draw calls and a dropped frame in front of a queue. Same trade, and the same
     * reasoning, as [DiveRenderer.stripCount]'s bound: one wrong-looking frame beats a stall.
     */
    internal const val MAX_CELLS = 2000

    /** The cell index containing a world coordinate. Floor, so it is correct for negative x. */
    internal fun cellIndex(worldCoordinate: Float) = floor(worldCoordinate / CELL_METRES).toInt()

    /** The low-coordinate corner of cell [index] along one axis, in world metres. */
    internal fun cellOrigin(index: Int) = index * CELL_METRES

    // ---- The hash ------------------------------------------------------------------------------

    /**
     * The mixing constants. [MIX_A] and [MIX_B] are murmur3's 32-bit finalizer (`0x85ebca6b` and
     * `0xc2b2ae35`), written as the negative `Int` literals they are on the JVM because Kotlin
     * types the positive forms as `Long`.
     *
     * The three per-input primes exist so that `(x, y)` and `(y, x)` are different cells and so
     * that the eight [streams][STREAM_JITTER_X] of one cell are independent of each other. A
     * cheaper hash — `x * 73856093 xor y * 19349663` with no finalizer, the classic spatial-hash
     * pair — was NOT used: without an avalanche step the low bits stay strongly correlated with
     * the input, and the low bits are exactly what a `[0, 1)` conversion keeps. That reads on
     * screen as rows and columns of motes at the same brightness, which is precisely the
     * regularity a lattice has to hide.
     */
    private const val HASH_X = 0x27d4eb2d
    private const val HASH_Y = 0x165667b1
    private const val HASH_S = 0x2545f491
    private const val MIX_A = -0x7a143595
    private const val MIX_B = -0x3d4d51cb

    /**
     * The stream indices. One hash input per independent property, so that changing how one is
     * derived cannot shift any of the others — re-tuning the size range must not move every mote
     * sideways.
     */
    internal const val STREAM_JITTER_X = 0
    internal const val STREAM_JITTER_Y = 1
    internal const val STREAM_SIZE = 2
    internal const val STREAM_BRIGHTNESS = 3
    internal const val STREAM_DRIFT_X = 4
    internal const val STREAM_DRIFT_Y = 5
    internal const val STREAM_PULSE = 6
    internal const val STREAM_RATES = 7
    internal const val STREAM_GLOW = 8

    /** The full 32-bit hash of a cell and a stream. Pure, and the only source of randomness here. */
    internal fun hash(cellX: Int, cellY: Int, stream: Int): Int
    {
        var h = cellX * HASH_X
        h = h xor (cellY * HASH_Y)
        h = h xor (stream * HASH_S)
        h = h xor (h ushr 16)
        h *= MIX_A
        h = h xor (h ushr 13)
        h *= MIX_B
        h = h xor (h ushr 16)
        return h
    }

    /**
     * [hash] as a float in `[0, 1)`.
     *
     * The TOP 24 bits, not the bottom ones, and not a modulo. `ushr 8` keeps the most-mixed end of
     * the word and 24 bits is exactly what a `Float`'s mantissa can represent without rounding, so
     * the conversion is exact and the result can never round up to 1.0 — which a `hash / 2^32 + 0.5`
     * formulation can, and which would put a mote exactly on a cell boundary.
     */
    internal fun unitFloat(cellX: Int, cellY: Int, stream: Int) =
        (hash(cellX, cellY, stream) ushr 8) * UNIT_SCALE

    private const val UNIT_SCALE = 1f / (1 shl 24)

    /** [unitFloat] mapped onto `0 until count` — how a mote picks one of its rate options. */
    internal fun pick(cellX: Int, cellY: Int, stream: Int, count: Int) =
        (unitFloat(cellX, cellY, stream) * count).toInt().coerceIn(0, count - 1)

    // ---- The animation clock -------------------------------------------------------------------

    /**
     * # THE RENDER CLOCK, UNCONDITIONALLY, EXACTLY AS THE SEA AND THE GOD RAYS DO IT
     *
     * `WaterSurface`'s clock note is the precedent and it argues the whole case; this is the third
     * consumer of it and adds nothing new. In short: the only gated clock in this codebase is the
     * fixed tick inside `RunLifecycle.simulationAdvances`, which is FALSE in IDLE — so an
     * ambient effect animated on it would be frozen solid on the attract screen, which is the
     * screen a booth queue spends most of its time looking at. The motes have no gameplay meaning,
     * are not on the light map, and nothing in `dive/` can observe them, so `engine.data.deltaTime`
     * is the right clock and it is advanced outside every lifecycle gate.
     *
     * [WRAP_SECONDS] is `WaterSurface`'s number, shared deliberately: two ambient animations that
     * wrapped at different periods would beat against each other on a cycle neither of them names.
     * Every rate below is authored as a whole number of CYCLES PER WRAP so the wrap is exact — see
     * [DRIFT_X_CYCLES].
     */
    const val PIN_ENV = "EPT_MOTE_PHASE"

    /** @see WaterSurface.WRAP_SECONDS — the same 240 s, for the same booth-reliability reason. */
    const val WRAP_SECONDS = WaterSurface.WRAP_SECONDS

    private var pinnedSeconds: Float? = null
    private var seconds = 0f

    /** The animation clock, in seconds. Always in `[0, WRAP_SECONDS)`. */
    val phaseSeconds get() = pinnedSeconds ?: seconds

    /**
     * Advance by one RENDER frame. Called from `EnPustTil.onUpdate`, beside `WaterSurface.advance`.
     *
     * Deliberately does not check the pin, for the reason `WaterSurface.advance` records: the pin
     * lives in [phaseSeconds] and a free-running `seconds` underneath it cannot be observed, so a
     * guard here would be untestable dead code.
     *
     * The remaining guard is real. The engine hands `deltaTime` straight from the frame timer and
     * a stalled frame (a window drag, a display-mode change) has produced absurd values elsewhere
     * in this project; a NaN here would make every mote's `sin` NaN, and a NaN alpha is a hole in
     * the frame rather than a wobble.
     */
    fun advance(dt: Float)
    {
        if (!(dt > 0f) || !dt.isFinite()) return
        seconds = (seconds + dt) % WRAP_SECONDS
    }

    /** Pin the phase for a reproducible capture. @see PIN_ENV */
    fun pin(atSeconds: Float)
    {
        pinnedSeconds = wrapped(atSeconds)
    }

    /** Drop a pin. Exists for the tests; nothing in the game unpins. */
    internal fun unpin()
    {
        pinnedSeconds = null
        seconds = 0f
    }

    /** [atSeconds] folded into `[0, WRAP_SECONDS)`, for a negative or enormous pin. */
    internal fun wrapped(atSeconds: Float): Float
    {
        if (!atSeconds.isFinite()) return 0f
        val m = atSeconds % WRAP_SECONDS
        return if (m < 0f) m + WRAP_SECONDS else m
    }

    // ---- The motion ----------------------------------------------------------------------------

    /**
     * The drift excursions, in metres, and the per-mote rate options in WHOLE CYCLES PER WRAP.
     *
     * ## Why an oscillation and not a velocity
     *
     * Marine snow is suspended, not falling: it hangs in the water and is nudged about by it. A
     * constant velocity would also break the lattice outright — a mote that travelled would
     * eventually leave its own cell, and [CULL_MARGIN_METRES] could no longer be bounded, so the
     * field would either pop at the frame edge or have to be iterated over the whole column.
     *
     * ## Why three options per axis and not one rate
     *
     * One shared rate makes the whole field move as a single body, which reads as the CAMERA
     * panning rather than as water moving — the same failure `LightShafts.cyclesPerWrap` records
     * for the god rays. Each mote picks its own rate from these, and its own phase, so no two
     * neighbours are in step and there is no shared beat.
     *
     * The two axes draw from DISJOINT sets, which is what makes a mote's path a Lissajous figure
     * rather than a straight line: with the same rate on both axes, any phase offset traces a
     * line segment, and a field of dots all sliding along their own straight lines looks
     * mechanical. There is no ratio here smaller than 4:3.
     *
     * ## Why integers
     *
     * [WRAP_SECONDS] is only invisible if every component is at an exact whole number of cycles
     * when the clock wraps. `WaterSurface.WRAP_SECONDS` has the booth-reliability argument for why
     * the clock wraps at all (a `Float` at 172 800 s resolves to 0.015 s, so a free-running clock
     * would visibly quantise late on day two, on a machine nobody is watching).
     *
     * ## THE AMPLITUDES WERE RAISED, AND THE FIRST SET WAS TOO TIMID
     *
     * They were 0.85 m and 0.35 m, chosen so that a mote crossed about one screen pixel a second —
     * "movement you notice without being able to watch it". On the cabinet that reads as a field of
     * dots that is very nearly STILL. The owner: *"they should float a bit more around"*.
     *
     * 2.2 m and 1.4 m. The speeds these produce: x is 2.2 m of excursion over 240/1..3 s, i.e. a
     * peak of 0.06 to 0.17 m/s; y is 1.4 m over 240/4..7 s, a peak of 0.15 to 0.26 m/s. At the
     * ~19 px/m of a 16:9 booth panel that is 1 to 5 px/s — slow enough still to read as suspended
     * rather than swimming, and fast enough that the field is visibly alive while you watch it.
     *
     * The y amplitude grew proportionally MORE than the x (4x against 2.6x) on purpose: vertical
     * motion is what reads as buoyancy, and the old 0.35 m was under half a mote's own diameter, so
     * the dominant term was horizontal sliding.
     *
     * [CULL_MARGIN_METRES] is DERIVED from these and needs no edit — that is the point of deriving
     * it. `MotesTest` re-checks that no mote's quad can leave its cell by more than that margin, so
     * raising these past what the culling can carry fails the build rather than popping motes in at
     * the frame edge.
     */
    const val DRIFT_X_METRES = 2.2f
    const val DRIFT_Y_METRES = 1.4f
    internal val DRIFT_X_CYCLES = intArrayOf(1, 2, 3)
    internal val DRIFT_Y_CYCLES = intArrayOf(4, 5, 7)

    /**
     * How deep the brightness pulse goes, and how fast.
     *
     * A mote's alpha is multiplied by `1 - PULSE_AMOUNT + PULSE_AMOUNT * (0.5 + 0.5 sin)`, i.e. it
     * breathes between `1 - PULSE_AMOUNT` and 1 of its base. 0.35 is enough that the field is
     * visibly alive and far short of a blink — bioluminescent plankton glow steadily and vary,
     * they do not flash, and a flashing point in this game means something (see the class doc on
     * the anglerfish).
     *
     * The rates are prime-ish and pairwise incommensurate with the drift rates above, so a mote's
     * brightest moment does not land on the same point of its path every cycle.
     */
    const val PULSE_AMOUNT = 0.35f
    internal val PULSE_CYCLES = intArrayOf(7, 11, 13)

    /** Radians per second for a whole-cycle-per-wrap rate. @see DRIFT_X_CYCLES */
    internal fun rateRadiansPerSecond(cyclesPerWrap: Int) =
        (2.0 * PI * cyclesPerWrap / WRAP_SECONDS).toFloat()

    /**
     * A mote's horizontal offset from its cell anchor, in metres, at animation time [atSeconds].
     * In `[-DRIFT_X_METRES, DRIFT_X_METRES]`.
     */
    internal fun driftX(cellX: Int, cellY: Int, atSeconds: Float): Float
    {
        val rate = rateRadiansPerSecond(DRIFT_X_CYCLES[pick(cellX, cellY, STREAM_RATES, DRIFT_X_CYCLES.size)])
        val phase = unitFloat(cellX, cellY, STREAM_DRIFT_X) * TWO_PI
        return DRIFT_X_METRES * sin(rate * atSeconds + phase)
    }

    /** The vertical half of the same. @see driftX */
    internal fun driftY(cellX: Int, cellY: Int, atSeconds: Float): Float
    {
        val rate = rateRadiansPerSecond(DRIFT_Y_CYCLES[pick(cellX, cellY, STREAM_DRIFT_Y, DRIFT_Y_CYCLES.size)])
        val phase = unitFloat(cellX, cellY, STREAM_DRIFT_Y) * TWO_PI + HALF_PI
        return DRIFT_Y_METRES * sin(rate * atSeconds + phase)
    }

    /** A mote's own brightness breath, in `[1 - PULSE_AMOUNT, 1]`. @see PULSE_AMOUNT */
    internal fun pulse(cellX: Int, cellY: Int, atSeconds: Float): Float
    {
        val rate = rateRadiansPerSecond(PULSE_CYCLES[pick(cellX, cellY, STREAM_PULSE, PULSE_CYCLES.size)])
        val phase = unitFloat(cellX, cellY, STREAM_PULSE) * TWO_PI
        return 1f - PULSE_AMOUNT + PULSE_AMOUNT * (0.5f + 0.5f * sin(rate * atSeconds + phase))
    }

    /**
     * `val` rather than `const val` only because Kotlin will not fold a `.toFloat()` call into a
     * compile-time constant. Read once per mote from a field on an object that is already loaded.
     */
    private val TWO_PI = (2.0 * PI).toFloat()
    private val HALF_PI = (PI / 2.0).toFloat()

    /** A mote's world x at [atSeconds] — its cell anchor, its jitter within the cell, its drift. */
    internal fun moteX(cellX: Int, cellY: Int, atSeconds: Float) =
        cellOrigin(cellX) + unitFloat(cellX, cellY, STREAM_JITTER_X) * CELL_METRES + driftX(cellX, cellY, atSeconds)

    /** A mote's world DEPTH at [atSeconds] — world y is depth. @see moteX */
    internal fun moteDepth(cellX: Int, cellY: Int, atSeconds: Float) =
        cellOrigin(cellY) + unitFloat(cellX, cellY, STREAM_JITTER_Y) * CELL_METRES + driftY(cellX, cellY, atSeconds)

    // ---- The look ------------------------------------------------------------------------------

    /**
     * A mote's diameter, in metres.
     *
     * ## THE UPPER BOUND IS PART OF THE ANGLERFISH'S TELL, NOT A LOOK DECISION
     *
     * `Framing.PEARL_SIZE_METRES` is 1.2 m, and a pearl and the anglerfish's lure are the same
     * draw. A glowing point the size of a pearl is a pearl as far as the player is concerned, so
     * the largest mote is deliberately well under it — 0.85 m of QUAD, and the emitter's alpha
     * ramp means the part that reads as solid is the inscribed disc rather than the whole square
     * ([LightEmitter.ALPHA_RAMP_INNER]). `MotesTest` asserts the relationship against
     * `Framing.PEARL_SIZE_METRES` rather than against a copy of the number.
     *
     * The lower bound is a sampling floor rather than a taste one: at the ~19 px/m of a 16:9 booth
     * panel 0.35 m is a 7-pixel quad, and below about that the emitter's ramp has too few texels
     * left to read as round and the mote starts to look like a lone bright pixel.
     */
    const val MIN_SIZE_METRES = 0.35f
    const val MAX_SIZE_METRES = 0.85f

    /**
     * The spread of base brightness, as a multiplier on [MOTE_ALPHA].
     *
     * Not a flat field: a mote field where every dot is the same brightness reads as a texture
     * pasted over the water. The range is asymmetric about 1 on purpose — most motes are dimmer
     * than the peak, so the few bright ones read as the ones that happen to be near.
     */
    const val MIN_BRIGHTNESS = 0.55f
    const val MAX_BRIGHTNESS = 1f

    /** A mote's diameter in metres, from its hash. @see MIN_SIZE_METRES */
    internal fun sizeMetres(cellX: Int, cellY: Int) =
        MIN_SIZE_METRES + (MAX_SIZE_METRES - MIN_SIZE_METRES) * unitFloat(cellX, cellY, STREAM_SIZE)

    /** A mote's base brightness multiplier, from its hash. @see MIN_BRIGHTNESS */
    internal fun brightness(cellX: Int, cellY: Int) =
        MIN_BRIGHTNESS + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * unitFloat(cellX, cellY, STREAM_BRIGHTNESS)

    /**
     * How far outside the visible rect cells are still visited, in metres.
     *
     * A mote's DRAWN position is its cell anchor plus its drift, so a mote belonging to an
     * off-screen cell can be on screen and vice versa. Without this margin those motes would pop
     * in and out along the frame edge as the camera moved — the exact artefact the lattice is
     * supposed to make impossible.
     *
     * It is the worst case and derived rather than typed: the largest drift excursion on either
     * axis ([DRIFT_X_METRES], which is the larger of the two) plus half of the largest mote
     * ([MAX_SIZE_METRES]), so a mote's quad cannot reach the visible rect from a cell further out
     * than this. `MotesTest` re-derives it, so shrinking the margin while growing the drift fails.
     */
    internal const val CULL_MARGIN_METRES = DRIFT_X_METRES + MAX_SIZE_METRES * 0.5f

    /**
     * # THE COLOUR, AND IT IS THE ANGLERFISH'S PROBLEM THAT SETS IT
     *
     * `docs/superpowers/specs/2026-08-11-outstanding-work.md` §1.1: the lure renders IDENTICALLY to
     * a pearl and the only tell is motion, so filling the scene with glowing points stops a
     * suspicious light being unusual and the trap stops working. The resolution named there — and
     * the cheapest one — is that scenery glows a *visibly different colour* from the pearls' warm
     * amber.
     *
     * `DiveRenderer`'s pearl is `(1.00, 0.78, 0.35)`, a hue of about 40 degrees. This is
     * `(0.25, 0.62, 1.00)`, a hue of about 210 — **171 degrees away**, on the far side of the
     * wheel, with the channel ORDER exactly reversed (blue over green over red against red over
     * green over blue). At a glance in the deep, one is gold and the other is ice.
     *
     * `MotesTest` asserts that separation against `DiveRenderer`'s own constant rather than a
     * transcription of it, so warming the motes toward amber, or cooling the pearls, fails the
     * build with §1.1 quoted at whoever did it.
     *
     * It is also the right colour on its own merits: the design's §11 art direction is "deep blue
     * falling to near-black, bioluminescence in the dark", and real marine bioluminescence is
     * overwhelmingly blue-green — 470 to 490 nm — because that is the window seawater absorbs
     * least.
     */
    const val MOTE_RED = 0.25f
    const val MOTE_GREEN = 0.62f
    const val MOTE_BLUE = 1f

    /**
     * How opaque a mote is drawn, before its own brightness and pulse. **ONE NUMBER — the depth
     * response is GI's job now, and it used to be a five-anchor table.**
     *
     * ## WHY THE TABLE WENT
     *
     * It ran 0.18 in the Shallows to 0.85 in the Abyss, on the reasoning that the art direction
     * (§11: "bioluminescence in the dark") wants the motes strongest where the water is blackest.
     * Measured at 140 m with `EPT_DEPTH`, that made them **the brightest thing in the Abyss** —
     * peak 213.5 against the brightest pearl's 194.2, and 19.1% of every lit pixel against the
     * pearls' 7.1%. A field meant to read as suspended matter read as a starfield, and out-shone
     * the object the player is hunting.
     *
     * The table was only half the fault. The other half was the surface: the motes had their own,
     * which meant they escaped the GI multiply that darkens everything else in the deep, so the
     * ramp was fighting a composite it was exempt from. The owner's fix was to remove the
     * exemption — *"put them back on main so GI darkens them too"* — and once the multiply applies,
     * a depth ramp in the ALBEDO is not just redundant but backwards: it brightens the source
     * exactly where the light map is about to multiply it toward zero.
     *
     * ## WHAT THE DEPTH RESPONSE IS INSTEAD
     *
     * The light map. Near the surface it is bright, so a mote reads; in the Abyss it is
     * effectively zero, so an ordinary mote is black — and the GLOWING quarter
     * ([GLOW_IN]) still shows, because each one lights its own body. That is a better version of
     * the same art direction than the table was: bioluminescence in the dark is now literally
     * bioluminescence, the specks that make their own light, rather than every speck being turned
     * up. It also gives the torch something to find — a beam sweeping the dark reveals the dark
     * motes as dust, which the exempt surface could never have done.
     *
     * 0.25 is chosen against the SHALLOWS, which is now the bright end: it is the value at which
     * the field reads as texture rather than as objects on lit water. The deep needs no value
     * chosen for it at all.
     */
    internal const val MOTE_ALPHA = 0.25f

    /** What the brightest possible mote is scaled by. Kept as a name so the bound tests read. */
    internal val PEAK_ALPHA = MOTE_ALPHA

    // --- THE GLOWING SUBSET: motes that are actually light sources ----------------------------
    //
    // ## THIS REVERSES THIS FILE'S OWN "WHAT IT DELIBERATELY IS NOT"
    //
    // The class doc argued that a mote is not a `drawLight` and casts nothing, on two grounds. The
    // owner overruled the conclusion — *"and also be more of a light source"* — so the grounds are
    // worth restating, because ONE OF THEM IS STILL TRUE and is what bounds [GLOW_IN] below.
    //
    // 1. "Two hundred GI emitters would be two hundred light sources in a radiance-cascade solve."
    //    Half right. The cascade march is per-TEXEL and does not care how many lights there are;
    //    what scales with the count is rasterising each emitter quad into `gi_local_scene`, which
    //    for a sub-metre quad is cheap. But it is not free, and it now sits on top of the GI
    //    resolution going from quarter to half res on 2026-08-12 — 4x the texels — so the count
    //    still wants a bound rather than "all of them".
    //
    // 2. "An emitter is a REGION that rasterises into the scene, so each one would also be an
    //    OCCLUDER of the diver's torch." THIS IS THE REAL CONSTRAINT AND IT IS NOT SPECULATIVE.
    //    `CLAUDE.md` records that GI samples a light's colour where a ray HITS it, i.e. on its
    //    rim — rays terminate on light quads. Every glowing mote is therefore a tiny occluder
    //    between the torch and whatever is behind it, and the torch was just made the primary
    //    light source in the deep at the owner's request. A dense field of emitters would chop
    //    that beam into speckle.
    //
    // Which is why only a fraction glow. At 1 in 4 the field carries roughly 25-60 emitters, the
    // same order as the pearls plus vents already on screen rather than ten times them; and the
    // occlusion reads as motes CATCHING the beam — dust in a torchlight, which is the look this
    // whole feature is after — instead of as a screen door in front of it.

    /**
     * One mote in [GLOW_IN] is a light source. 4.
     *
     * Chosen from its own hash stream rather than from size or brightness, so which motes glow is
     * decorrelated from how big they look — a field where every large mote also glows reads as two
     * classes of object rather than as one substance catching the light unevenly.
     */
    internal const val GLOW_IN = 4

    /** Whether this cell's mote is one of the glowing ones. @see GLOW_IN */
    internal fun glows(cellX: Int, cellY: Int) = pick(cellX, cellY, STREAM_GLOW, GLOW_IN) == 0

    /**
     * What a glowing mote emits, before its own alpha scales it.
     *
     * **It must stay far below the torch, and that is checked.** The torch runs 2.0 at the surface
     * to 6.0 in the Abyss (`DiveLighting.diverIntensityByZone`) precisely so that it, and not the
     * scenery, is what reveals a pearl. A field of 25-60 motes at 0.05 each aggregates to something
     * comparable to ONE pearl's 0.12 spread over the whole frame — an ambient wash, which is what
     * was asked for, rather than a second lighting rig competing with the beam.
     *
     * Scaled by the mote's own alpha at the draw site, so the same depth ramp that fades the dots
     * near the surface fades their light with them: a mote cannot glow where it cannot be seen.
     */
    internal const val GLOW_INTENSITY = 0.05f

    /**
     * How many metres below `Tuning.SURFACE_DEPTH` the motes take to fade in, and why there is a
     * fade at all.
     *
     * ABOVE THE WATERLINE THERE MUST BE NOTHING. The motes are suspended in water; the sky is not
     * water, and the camera looks at up to 24 m of sky when the diver is at the surface (see
     * [Sky.SPAN_METRES]). [surfaceFade] is exactly 0 at and above `SURFACE_DEPTH`, so this is a
     * hard guarantee and not a small number — `MotesTest` asserts the zero.
     *
     * The fade below it is what stops that guarantee from being a visible horizontal line of motes
     * starting all at once at the waterline. 6 m is comfortably longer than a mote's vertical
     * drift ([DRIFT_Y_METRES]) so no mote crosses the whole ramp during its own bob, and it is
     * shorter than `WaterSurface.QUAD_BOTTOM_DEPTH`'s 7.31 m — the depth at which the water
     * shader's underside haze has finished handing over to the zone bands — so the motes reach
     * full strength in open water rather than inside the surface quad's own gradient.
     */
    const val SURFACE_FADE_METRES = 6f

    /** `0` at and above the waterline, smoothly reaching `1` at [SURFACE_FADE_METRES] below it. */
    internal fun surfaceFade(depth: Float): Float
    {
        val t = ((depth - Tuning.SURFACE_DEPTH) / SURFACE_FADE_METRES).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The alpha a mote of full brightness at full pulse would be drawn at, at [depth].
     *
     * The two factors say different things and are kept separate for that reason: [surfaceFade] is
     * "is this water at all", and [MOTE_ALPHA] is "how much
     * bioluminescence is there in this water". Either may be re-tuned without the other.
     */
    internal fun depthGain(depth: Float) = surfaceFade(depth) * MOTE_ALPHA

    /**
     * Below this the draw is skipped entirely.
     *
     * `setDrawColor` quantises each channel to 8 bits, so an alpha under 1/255 rasterises as
     * nothing — but it still costs a batch slot, a depth write and a state change. Above the
     * waterline [surfaceFade] already returns an exact 0 and this is what turns that into "no draw
     * call"; it also silently drops the dimmest tail of the Shallows, which is the range the
     * composite makes invisible anyway.
     */
    internal const val MIN_VISIBLE_ALPHA = 1f / 255f

    // ---- The draw ------------------------------------------------------------------------------

    /**
     * Draw one frame of the field onto the motes surface.
     *
     * Walks the cells overlapping the camera's visible rect, expanded by [CULL_MARGIN_METRES], and
     * submits one textured quad per cell whose mote is bright enough to see. No allocation: the
     * loop is over `Int`s, every derived quantity is a primitive `Float`, and the two `Vector2f`s
     * the camera hands back are its own reused instances (read out into locals immediately, as
     * every other consumer of them in this codebase does).
     *
     * ## THE EMITTER IS SHARED WITH EVERY LIGHT IN THE GAME
     *
     * [LightEmitter.emitter] is the round soft-edged disc `DiveLighting` shapes the diver's torch,
     * the pearls and the lure from. It is reused here rather than a second texture being generated,
     * because it is exactly what a mote needs — a radial alpha ramp with flat white RGB, so
     * `drawTexture` modulates it by the draw colour — and one 128px texture is cheaper than two.
     * **A change to [LightEmitter.alphaAt] or [LightEmitter.ALPHA_RAMP_INNER] moves the motes as
     * well as the lights.** That is a real coupling and it is accepted knowingly; if the two ever
     * need to differ, add a second texture there rather than reshaping this one's draw.
     *
     * It is fetched ONCE per frame and held in a local, deliberately. [LightEmitter.emitter] is a
     * function rather than a property because it has a side effect — it counts consecutive misses
     * and logs one WARN after 600 of them — and calling it per mote would burn that budget in
     * three frames and blame the lights for it.
     *
     * The row range is clamped at the waterline so that a camera looking at the sky iterates no
     * cells at all up there. That is an optimisation with a correctness edge to it: without it, a
     * surface-level frame would visit a full screen of cells for [depthGain] to reject one by one.
     */
    /**
     * Visit every mote the camera can see, once, handing each to [visit] as
     * `(x, depth, size, alpha, glows)`.
     *
     * ONE TRAVERSAL, TWO CONSUMERS. [render] draws the dots onto the motes surface and
     * [DiveLighting.drawMoteLights] issues emitters for the glowing subset, and those two must
     * agree about where every mote is to the metre — a light offset from the dot it belongs to is
     * exactly the class of drift `CLAUDE.md` spends a section on. Writing the cell walk twice is
     * how they would drift, so it is written once.
     *
     * `inline` with no captured state, so the lambda is compiled away and this allocates nothing —
     * the render path's standing rule.
     */
    internal inline fun forEachVisible(cam: Camera, visit: (Float, Float, Float, Float, Boolean) -> Unit)
    {
        val topLeft = cam.topLeftWorldPosition
        val worldLeft = topLeft.x
        val worldTop = topLeft.y
        val bottomRight = cam.bottomRightWorldPosition
        val worldRight = bottomRight.x
        val worldBottom = bottomRight.y

        val minCellX = cellIndex(worldLeft - CULL_MARGIN_METRES)
        val maxCellX = cellIndex(worldRight + CULL_MARGIN_METRES)

        // Nothing above the waterline, ever — see SURFACE_FADE_METRES.
        val firstWaterCell = cellIndex(Tuning.SURFACE_DEPTH - CULL_MARGIN_METRES)
        val minCellY = maxOf(cellIndex(worldTop - CULL_MARGIN_METRES), firstWaterCell)
        val maxCellY = cellIndex(worldBottom + CULL_MARGIN_METRES)

        if (maxCellX < minCellX || maxCellY < minCellY) return

        // Long, because a degenerate camera rect can make either span exceed Int on its own.
        val cells = (maxCellX - minCellX + 1L) * (maxCellY - minCellY + 1L)
        if (cells > MAX_CELLS) return

        val atSeconds = phaseSeconds

        for (cellY in minCellY..maxCellY)
        {
            for (cellX in minCellX..maxCellX)
            {
                val depth = moteDepth(cellX, cellY, atSeconds)
                val alpha = depthGain(depth) * brightness(cellX, cellY) * pulse(cellX, cellY, atSeconds)
                if (alpha < MIN_VISIBLE_ALPHA) continue

                visit(moteX(cellX, cellY, atSeconds), depth, sizeMetres(cellX, cellY), alpha, glows(cellX, cellY))
            }
        }
    }

    fun render(surface: Surface, cam: Camera)
    {
        val texture = LightEmitter.emitter()
        forEachVisible(cam) { x, depth, size, alpha, _ ->
            surface.setDrawColor(MOTE_RED, MOTE_GREEN, MOTE_BLUE, alpha)
            surface.drawTexture(texture, x, depth, size, size, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN)
        }
    }
}
