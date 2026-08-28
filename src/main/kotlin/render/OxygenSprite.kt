package render

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.asset.types.SpriteSheet
import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import no.njoh.pulseengine.core.shared.utils.Logger
import kotlin.math.floor

/**
 * The oxygen vent's art: ONE baked sprite sheet of normals, when to show which frame of it, and
 * how big a vent is in the world.
 *
 * [DiverSprite]'s sibling, and deliberately written to read like it — the loading path, the two
 * silent constructor traps, the load-bearing hyphen in the filename and the readiness gate are all
 * the same problems with the same answers, and a second solution to any of them would be a second
 * thing to keep in step. Read that file first; this doc only records where the two DIFFER.
 *
 * ## It is a NORMAL MAP AND NOTHING ELSE — there is no albedo sheet
 *
 * The vent's colour comes from `setDrawColor` at the draw site, exactly as the pearl's does
 * (`DiveRenderer.drawAirPockets` already distinguishes a live vent from a spent one that way, and
 * that distinction is gameplay — one vent per dive, see `dive/AirPocket.kt`). So the sheet
 * contributes SHAPE to the light map and nothing to the picture's colour, which is why there is
 * one `SpriteSheet` here where [DiverSprite] has two and why nothing below is paired.
 *
 * That also removes the diver's cell-for-cell alignment problem: with a single sheet there is no
 * second sheet whose grid could drift out of step with this one.
 *
 * ## The bake, MEASURED on the committed PNG rather than copied from a spec
 *
 * `src/main/resources/sprites/oxygen-normal.png` is 2002x1944, RGBA8, and carries the bake's own
 * contract in PNG `tEXt` chunks: `ept:frame_count=96`, `ept:frame_height=216`, `ept:grid=11x9`,
 * `ept:source_sha256=4e2a4332de15d817`. `OxygenSpriteTest` re-reads those chunks and the IHDR out
 * of the committed file, so every constant below is checked against the asset it describes rather
 * than trusted — a re-bake at a different `--frame-height` cannot leave them stale.
 *
 * What was measured on the pixels, and why each measurement decided something here:
 *
 *  - **The normals decode RAW.** `rgb/255*2-1` gives mean length 1.0000 (per-frame range
 *    1.0000-1.0001) — identical to `diver-normal.png`. The bake has already done the
 *    sRGB->linear decode, so the format below is `RGBA8` and NOT `SRGBA8`; linearizing a second
 *    time would bend every normal toward +Z and flatten the vent under the torch.
 *  - **Green is high at the top in all 96 of 96 frames** (mean 155.4 top against 96.8 bottom),
 *    i.e. +Y points UP: the OpenGL convention, the same one the diver and the rock sheets use.
 *    A DirectX-convention sheet would light the vent from the wrong side and look like an art
 *    problem rather than a code one.
 *  - **The opaque content sits 2 px inside the cell on all four sides.** With
 *    [TextureWrapping.CLAMP_TO_EDGE] and a `LINEAR` filter, that margin is what guarantees the
 *    filter's half-texel reach at a cell border never picks up the neighbouring frame.
 *  - **Cells 96, 97 and 98 are FULLY TRANSPARENT** — max alpha 0, measured. See [FRAME_COUNT].
 *
 * ## The filename's HYPHEN is load-bearing, exactly as the diver's is
 *
 * The engine's auto-loading path (`Extensions.kt:446-448`, reached from `AssetManager.loadAll`)
 * keys on the substring `_normal` — an UNDERSCORE — and, when it matches, forces `RGBA8` with
 * **10** mip levels regardless of what the caller asked for. Our file is `oxygen-normal.png`, with
 * a hyphen, so it does not trip that path and the explicit constructor below governs. Renaming it
 * to `oxygen_normal.png` would silently hand the sheet a mip chain, and mip generation averages
 * across cell boundaries — adjacent frames of the loop would bleed into each other under
 * minification, which a vent (a couple of metres across in a 55 m view) is minified enough to
 * show. Do not rename it.
 */
object OxygenSprite
{
    // --- The bake's contract. Measured off the committed PNG, never retyped from memory. -------

    /**
     * 96, NOT `HORIZONTAL_CELLS * VERTICAL_CELLS`, which is 99. The grid holds three cells the
     * bake never wrote, and they are not merely undefined the way the diver's 42nd cell is —
     * cells 96, 97 and 98 were measured at max alpha 0, i.e. fully transparent.
     *
     * That makes the failure mode a DROPPED frame rather than a garbage one: wrapping on 99
     * instead of 96 would blink the vent's normals out for three frames of every cycle, which
     * under GI is a vent that flattens to an unlit disc for an eighth of a second, eight times a
     * minute. Invisible in a still capture and lost in the motion of a moving one — this
     * project's usual sort of bug. [frameIndex] wraps on this constant for that reason.
     */
    const val FRAME_COUNT = 96

    /**
     * `ept:grid=11x9`. Same reasoning as [DiverSprite.HORIZONTAL_CELLS]: `TextureBank` allocates a
     * SQUARE `TextureArray` bucketed on `max(width, height)`, and 11x9 gives 2002x1944 — max
     * dimension 2002, comfortably inside the 2048 bucket. A squarer-looking 10x10 grid would be
     * 1820x2160, whose max dimension 2160 falls into the 4096 bucket for identical image content.
     */
    const val HORIZONTAL_CELLS = 11
    const val VERTICAL_CELLS = 9

    /**
     * One cell, in TEXELS of the committed sheet: 2002/11 and 1944/9, the second of which is the
     * `ept:frame_height=216` the bake recorded. These are asset dimensions, not screen pixels —
     * nothing here may become a resolution (see `Framing`'s class doc). They exist only to give
     * [widthForHeight] the art's proportions, and `OxygenSpriteTest` re-derives them from the
     * PNG's IHDR so a re-bake cannot leave them wrong.
     */
    const val FRAME_TEXELS_WIDE = 182
    const val FRAME_TEXELS_TALL = 216

    /**
     * Width over height of one frame: 0.843. The cell is TALLER than it is wide, which is the
     * shape of a column of rising air rather than of the square the vent used to be drawn as —
     * `DiveRenderer.drawAirPockets` took `Framing.AIR_POCKET_SIZE_METRES` for both axes until this
     * sheet landed, and now takes it as the HEIGHT and derives the width through [widthForHeight].
     */
    const val FRAME_ASPECT = FRAME_TEXELS_WIDE.toFloat() / FRAME_TEXELS_TALL.toFloat()

    /**
     * Normals, and the only sheet there is. `RGBA8` (linear) because the bake already did the
     * sRGB->linear decode — see the class doc's measurement.
     *
     * THE ARGUMENT ORDER IS `(…, format, maxMipLevels, hCells, vCells)`, which is NOT the field
     * declaration order (`horizontalCells, verticalCells` read first). The plausible-but-wrong
     * call — `(…, HORIZONTAL_CELLS, VERTICAL_CELLS, 1)` — is accepted without complaint, sets
     * `vCells = 1` and `maxMipLevels = HORIZONTAL_CELLS`, and builds a `textures` array that is
     * far too short; the misconfiguration itself raises nothing until `getTexture` throws an
     * `ArrayIndexOutOfBoundsException` two stages later, with the call site that got it wrong long
     * off the stack.
     *
     * And `maxMipLevels` must be exactly `1`, NEVER `0`: `TextureArray` computes
     * `mipLevels = min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and hands
     * it to `glTexStorage3D` as `levels`, where `0` is `GL_INVALID_VALUE` — no storage allocated
     * at all, every later `glTexSubImage3D` failing too, with no exception and no log line. `1`
     * means "one level, no mips", which is also what the art wants (see the class doc on mip bleed
     * across cell boundaries).
     *
     * Both traps are read back off the constructed asset by `OxygenSpriteTest.the sheet is
     * declared with the argument order the engine actually has`, before a GL context exists.
     */
    val normal = SpriteSheet(
        "/sprites/oxygen-normal.png",
        "oxygen_normal",
        TextureFilter.LINEAR,
        TextureWrapping.CLAMP_TO_EDGE,
        TextureFormat.RGBA8,
        1,                  // maxMipLevels — see above. NEVER 0.
        HORIZONTAL_CELLS,
        VERTICAL_CELLS
    )

    /** Queues the sheet for upload. Called once, from `EnPustTil.onCreate`. */
    fun load(engine: PulseEngine)
    {
        engine.asset.load(normal)
    }

    /**
     * How many frames may pass with the sheet still absent before [sheetsReady] says so out loud.
     * Ten seconds at 60 fps — the same budget [DiverSprite] uses, and for the same reason: far
     * longer than decoding a 2002x1944 PNG can take, short enough that a technician watching the
     * log at the booth sees it while still standing there.
     */
    private const val WARN_AFTER_FRAMES = 600

    private var framesWithoutSheet = 0
    private var warnedAboutSheet = false

    /**
     * Is the sheet on the GPU? A FUNCTION and not a property because it has a side effect: it
     * counts consecutive misses and logs exactly one WARN once they stop being explicable by the
     * async load.
     *
     * `AssetManager.load` only appends to a queue; `textures`/`size` are populated in
     * `SpriteSheet.onUploaded`, i.e. on the GL thread some frames later, and `getTexture(i)`
     * before that throws rather than returning null. So this gates on `size >= FRAME_COUNT`, which
     * is `0` before the upload AND `0` if the constructor's argument order was got wrong — the
     * same test that waits out the async load is therefore also the crash guard, and turns what
     * would be an exception in front of a queue into a flat fallback quad plus one WARN line.
     *
     * Zero allocation, one int compare in the ready case.
     */
    fun sheetsReady(): Boolean
    {
        if (normal.size >= FRAME_COUNT)
        {
            framesWithoutSheet = 0
            return true
        }

        framesWithoutSheet++
        if (framesWithoutSheet >= WARN_AFTER_FRAMES && !warnedAboutSheet)
        {
            warnedAboutSheet = true
            Logger.warn {
                "Oxygen vent sprite sheet never uploaded (normal cells=${normal.size}) — drawing the flat " +
                "fallback quad. Check /sprites/oxygen-normal.png is on the classpath and that the SpriteSheet " +
                "argument order is (format, maxMipLevels, hCells, vCells)."
            }
        }
        return false
    }

    /** One frame of the normal sheet. Only valid once [sheetsReady] has returned true. */
    fun normalFrame(index: Int): Texture = normal.getTexture(index)

    // --- Size ---------------------------------------------------------------------------------

    /**
     * A vent's width in world metres for a given world height, keeping the art's proportions.
     *
     * The same choice [DiverSprite.widthForHeight] makes, and made here for the same reason: the
     * vent's world size is one number, `Framing.AIR_POCKET_SIZE_METRES` — which drew a SQUARE
     * before this sheet existed — and the art is 0.843:1, so one of the two axes has to give.
     * HEIGHT is the dimension that means something for a column of
     * rising air — how far up the water the plume reaches is what the player judges it by — so
     * height is what the world size denotes and the width follows from the sheet.
     */
    fun widthForHeight(heightMetres: Float): Float = heightMetres * FRAME_ASPECT

    // --- Which frame to draw ------------------------------------------------------------------

    /**
     * ALL 96 FRAMES ARE ONE CONTINUOUS CYCLE, PLAYED IN ORDER — there is no idle frame, no
     * "spent" frame and no state machine here, exactly as in [DiverSprite]. Whether a vent has
     * been used this dive is a COLOUR at the draw site, not a frame.
     *
     * 24 fps to match [DiverSprite.CYCLE_FPS]. That is not a coincidence to be optimised away:
     * two sheets on screen at once, running at different rates, beat against each other, and the
     * diver's rate is a design decision by the project owner rather than a measurement. Matching
     * it costs nothing and removes the beat.
     *
     * MEASURED CONSEQUENCE, worth stating because it is what makes a constant rate safe here: the
     * loop is genuinely seamless, so nothing has to hide the wrap. The frame 95 -> frame 0 normal
     * delta is **2.324 degrees**, against a mean of 2.315 and a max of 2.846 over the 95 ordinary
     * consecutive steps — the 59th percentile of an ordinary step, i.e. the seam is not
     * distinguishable from any other frame boundary in the cycle.
     */
    const val CYCLE_FPS = 24f

    /** One trip through all [FRAME_COUNT] frames, in seconds. 96 / 24 = exactly 4.0 s. */
    const val CYCLE_SECONDS = FRAME_COUNT / CYCLE_FPS

    /**
     * The loop phase [dt] seconds later, wrapped into `[0, CYCLE_SECONDS)`.
     *
     * Identical in form and in reasoning to [DiverSprite.advancePhase], which carries the full
     * argument. In brief: the phase is accumulated elapsed SECONDS and not a frame counter, so the
     * animation is frame-rate independent and a dropped frame costs the time it took rather than a
     * whole animation frame; and wrapping EVERY step is what bounds the `Float` forever, so an
     * unattended two-day run cannot climb into the range where one ULP exceeds a frame and the
     * vent quietly freezes with nothing in the log.
     *
     * The `floor` form rather than `%` is deliberate: it is also correct for a negative argument,
     * which `%` is not — and [phaseOffsetFor] means callers routinely hand this sums it did not
     * produce itself.
     */
    fun advancePhase(phaseSeconds: Float, dt: Float): Float
    {
        val next = phaseSeconds + dt
        return next - floor(next / CYCLE_SECONDS) * CYCLE_SECONDS
    }

    /**
     * Which cell of the sheet a phase selects. Total: any input at all — negative, enormous, or an
     * offset phase that was never wrapped — lands inside `0 until FRAME_COUNT` and therefore never
     * reaches the grid's three unused cells. See [FRAME_COUNT] for what showing one would look
     * like.
     */
    fun frameIndex(phaseSeconds: Float): Int
    {
        val cell = floor(phaseSeconds * CYCLE_FPS).toInt() % FRAME_COUNT
        return if (cell < 0) cell + FRAME_COUNT else cell
    }

    // --- Why two vents do not throb in lockstep -------------------------------------------------

    /**
     * The conjugate of the golden ratio, `1/phi = phi - 1`. See [phaseOffsetFor].
     *
     * Written out rather than computed from `sqrt(5)` so the constant is `const` and the value is
     * legible at the one place anyone will read it.
     */
    private const val GOLDEN_RATIO_CONJUGATE = 0.618034f

    /**
     * A per-vent phase offset, in seconds, so that vents visible at the same time are at different
     * points of the same loop.
     *
     * THE THING THIS FIXES IS SPECIFIC AND [DiverSprite] CANNOT HAVE IT: there is exactly one
     * diver, and there are three vents (`AirPocketField.ZONES_WITH_VENTS` — Kelp, Twilight,
     * Trench), any two of which can be on screen together on a 55 m view. Driving all of them off
     * one shared phase makes them pulse in perfect unison, which reads as a single mechanism
     * blinking rather than as three separate columns of air, and is the exact tell that the world
     * is a handful of instances of one sprite.
     *
     * ## Why the golden ratio and not `index / count`
     *
     * `index / count` is the obvious even spread and it needs to know `count`, which this
     * signature deliberately does not: the caller is a `forEach` over `sim.airPockets` and has an
     * index, not a total, and a vent's offset must not CHANGE if the number of vents ever does —
     * that would make every vent's animation jump the day someone adds a fourth.
     *
     * An additive recurrence on the golden ratio gives an offset that depends only on the index
     * and is still near-evenly spread for every count, which is the three-distance theorem: for
     * any N the points `frac(i / phi)` fall into at most three distinct gap lengths, and phi is
     * the irrational that makes the largest gap smallest. Computed for N = 2..16, the smallest gap
     * is never below **0.48** of the ideal `CYCLE_SECONDS / N` (worst case N = 14; N = 3, the
     * count that actually ships, is at 0.71) — where a badly chosen irrational, or a rational one,
     * would put two vents on the same frame outright. `OxygenSpriteTest` asserts that bound rather
     * than restating this paragraph.
     *
     * Pure, allocation-free, and total: a negative index wraps like any other through `floor`.
     * `Float` mantissa precision means this stops being well spread somewhere in the thousands of
     * vents, which is several orders of magnitude past three.
     */
    fun phaseOffsetFor(index: Int): Float
    {
        val turns = index * GOLDEN_RATIO_CONJUGATE
        return (turns - floor(turns)) * CYCLE_SECONDS
    }

    // --- The one live loop --------------------------------------------------------------------

    /**
     * The vents' shared animation phase: elapsed seconds into the cycle, wrapped at
     * [CYCLE_SECONDS]. ONE clock for all vents — [phaseOffsetFor] is what separates them, so
     * adding a vent costs no state.
     *
     * PRESENTATION STATE, SO IT LIVES IN `render/` AND NOWHERE ELSE. `dive/AirPocket.kt` holds
     * what a vent IS and whether it has been used this dive; an animation phase in there would be
     * a rendering detail leaking into the model and would have to be serialised into every
     * deterministic-replay argument the simulation makes.
     *
     * Driven from the FIXED TICK via [advanceLoop], never from the render clock — see
     * [DiverSprite.loopPhase]'s doc for the three reasons, of which the load-bearing one is that a
     * fixed-tick phase makes the frame a pure function of the number of ticks and therefore makes
     * a screenshot reproducible.
     *
     * UNLIKE THE DIVER'S, THIS IS NOT RESET PER RUN. A vent is scenery: it is in the world before
     * the player presses start and it is still there after they drown, and restarting its plume at
     * frame 0 the instant a run begins would make the whole column of air twitch at exactly the
     * moment the player's attention is on it. [DiverSprite.restartLoop] exists because the ATTRACT
     * screen needed the diver on an authored frame; nothing here has that requirement, so there is
     * deliberately no `restartLoop` below.
     */
    private var loopPhase = 0f

    /** Which cell of the sheet to draw this frame, for the vent at [index] in `sim.airPockets`. */
    fun currentFrameFor(index: Int): Int = frameIndex(loopPhase + phaseOffsetFor(index))

    /** Called once per fixed tick. See [loopPhase] for why it is the fixed tick and not `onRender`. */
    fun advanceLoop(dt: Float)
    {
        loopPhase = advancePhase(loopPhase, dt)
    }
}
