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
 * The diver's art: two baked sprite sheets, when to show which frame of them, and how big the
 * figure is in the world.
 *
 * This is the FIRST loaded asset in the project — until it landed, `Texture.BLANK` was the only
 * texture the game ever named — so it establishes the loading path as well as using it. The
 * sheets themselves are produced by `tools/build_spritesheet.py` and specified in
 * `docs/superpowers/specs/2026-08-07-diver-spritesheet-bake-design.md`; every number below is
 * copied from that spec's §4/§5 rather than measured again here, and
 * `DiverSpriteTest.the frame grid matches the committed sheet` re-derives them from the
 * committed PNG so a re-bake at a different `--frame-height` cannot leave them stale.
 *
 * ## Two silent traps in the constructor, both of which this file is arranged to make loud
 *
 * **The argument order is `(…, format, maxMipLevels, hCells, vCells)`, which is NOT the field
 * declaration order** (`horizontalCells, verticalCells` read first). Passing `(…, cols, rows, 0)`
 * — the order the field list suggests — sets `hCells = rows`, `vCells = 0`, so `size = 0`, the
 * `textures` array is zero-length, and the misconfiguration raises nothing at all until
 * `getTexture(0)` throws an `ArrayIndexOutOfBoundsException` two stages later, with the call
 * site that got it wrong long off the stack.
 *
 * **`maxMipLevels` must be `1`, never `0`.** `TextureArray` computes
 * `mipLevels = min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and hands it
 * to `glTexStorage3D` as `levels`; `levels = 0` is `GL_INVALID_VALUE`, so NO storage is allocated
 * and every later `glTexSubImage3D` fails as well — with no exception and no log line. `1` is the
 * value that means "one level, no mips", which is also what we want: mip generation averages
 * across cell boundaries and would smear adjacent frames of the loop into each other.
 *
 * Both traps are checked by `DiverSpriteTest.the sheets are declared with the argument order the
 * engine actually has`, which reads `maxMipLevels` back off the constructed asset. That is a real
 * guard rather than a restatement: with the arguments in the plausible-but-wrong order the sheet's
 * `maxMipLevels` comes out as [HORIZONTAL_CELLS], not 1, so the test fails on the declaration
 * itself — before a GL context exists, and long before the booth.
 *
 * ## The filename is load-bearing in a way that is easy to undo
 *
 * The engine has an auto-loading path (`Extensions.kt:446-448`, reached from
 * `AssetManager.loadAll`) that keys on the substring `_normal` — an UNDERSCORE — and, when it
 * matches, forces `RGBA8` with **10** mip levels regardless of what the caller wanted. Our file is
 * `diver-normal.png`, with a HYPHEN, so it does not trip that path and the explicit constructor
 * below governs. Renaming it to `diver_normal.png` would silently hand the sheet ten mip levels
 * (frame bleed under minification) if it were ever loaded through `loadAll`. Do not rename it.
 *
 * ## Loading is asynchronous, and the readiness test is also the crash guard
 *
 * `AssetManager.load` only appends to a queue; `textures`/`size` are populated in
 * `SpriteSheet.onUploaded`, i.e. on the GL thread some frames later. `SpriteSheet.textures` is a
 * `lateinit`-style field, so `getTexture(i)` before the upload throws rather than returning null.
 * [sheetsReady] therefore gates every access on `size >= FRAME_COUNT`, which is `0` before the
 * upload and `HORIZONTAL_CELLS * VERTICAL_CELLS` after it — so the same test that waits out the
 * async load also catches a sheet that was declared with a broken cell count, and turns what
 * would be an exception in front of a queue into a fallback rectangle plus one WARN line.
 */
object DiverSprite
{
    // --- The bake's contract (spec §4/§5). Copied from the bake, never retyped from memory. ---

    /**
     * 41, NOT `HORIZONTAL_CELLS * VERTICAL_CELLS`. The grid holds 42 cells and the last one is
     * unused: source frame 42 is an exported duplicate of frame 1 (silhouette-centroid velocity
     * peaks at 5.284 px/frame into the seam then collapses to 0.083, ~1/31 of the mean), so
     * keeping it would put a one-frame dead stop in every loop. Allowing an unused trailing cell
     * is also what buys the smaller texture bucket — see [HORIZONTAL_CELLS].
     */
    const val FRAME_COUNT = 41

    /**
     * The grid is 14x3 rather than anything squarer because `TextureBank` allocates a SQUARE
     * `TextureArray` bucketed on `max(width, height)`: 14x3 gives a 1764x1152 sheet (max dim 1764,
     * 2048 bucket) where a hand-picked 7x6 would give 882x2304 — max dim 2304, into the 4096
     * bucket — for identical image content.
     */
    const val HORIZONTAL_CELLS = 14
    const val VERTICAL_CELLS = 3

    /**
     * One cell, in TEXELS of the committed sheet. These are asset dimensions, not screen pixels:
     * nothing here may become a resolution (see `Framing`'s class doc). They exist only to give
     * [widthForHeight] the art's proportions, and `DiverSpriteTest` re-derives them from the PNG's
     * IHDR so a re-bake cannot leave them wrong.
     */
    const val FRAME_TEXELS_WIDE = 126
    const val FRAME_TEXELS_TALL = 384

    /** Width over height of one frame: 0.328, i.e. a figure a little over three times as tall as it is wide. */
    const val FRAME_ASPECT = FRAME_TEXELS_WIDE.toFloat() / FRAME_TEXELS_TALL.toFloat()

    /**
     * Albedo. `SRGBA8` because the source frames carry `sRGB`/`gAMA` chunks and the bake preserves
     * that encoding — the GPU linearizes on sample, which is what the GI multiply expects.
     */
    val diffuse = SpriteSheet(
        "/sprites/diver-diffuse.png",
        "diver_diffuse",
        TextureFilter.LINEAR,
        TextureWrapping.CLAMP_TO_EDGE,
        TextureFormat.SRGBA8,
        1,                  // maxMipLevels — see the class doc. NEVER 0.
        HORIZONTAL_CELLS,
        VERTICAL_CELLS
    )

    /**
     * Normals. `RGBA8` (linear) because the bake already did the sRGB->linear decode: the source
     * normals were sRGB-encoded, and decoding them raw gives a mean X of +0.40 on a bilaterally
     * symmetric character. Measured on the committed sheet, mean |v| is 1.0000 and mean X is
     * -0.0017, so the sheet holds genuine unit normals and must not be linearized a second time.
     */
    val normal = SpriteSheet(
        "/sprites/diver-normal.png",
        "diver_normal",
        TextureFilter.LINEAR,
        TextureWrapping.CLAMP_TO_EDGE,
        TextureFormat.RGBA8,
        1,                  // maxMipLevels — see the class doc. NEVER 0.
        HORIZONTAL_CELLS,
        VERTICAL_CELLS
    )

    /** Queues both sheets for upload. Called once, from `EnPustTil.onCreate`. */
    fun load(engine: PulseEngine)
    {
        engine.asset.load(diffuse)
        engine.asset.load(normal)
    }

    /**
     * How many frames may pass with the sheets still absent before [sheetsReady] says so out loud.
     * Ten seconds at 60 fps: far longer than decoding two 1764x1152 PNGs can take, short enough
     * that a technician watching the log at the booth sees it while still standing there.
     */
    private const val WARN_AFTER_FRAMES = 600

    private var framesWithoutSheets = 0
    private var warnedAboutSheets = false

    /**
     * Are both sheets on the GPU? A FUNCTION and not a property because it has a side effect: it
     * counts consecutive misses and logs exactly one WARN once they stop being explicable by the
     * async load. A missing sprite is otherwise completely silent — the fallback rectangle looks
     * like a deliberate placeholder — and "silent" is this project's recurring failure mode.
     *
     * Zero allocation, one int compare in the ready case.
     */
    fun sheetsReady(): Boolean
    {
        if (diffuse.size >= FRAME_COUNT && normal.size >= FRAME_COUNT)
        {
            framesWithoutSheets = 0
            return true
        }

        framesWithoutSheets++
        if (framesWithoutSheets >= WARN_AFTER_FRAMES && !warnedAboutSheets)
        {
            warnedAboutSheets = true
            Logger.warn {
                "Diver sprite sheets never uploaded (diffuse cells=${diffuse.size}, normal cells=${normal.size}) " +
                "— drawing the fallback rectangle. Check /sprites/diver-diffuse.png and /sprites/diver-normal.png " +
                "are on the classpath and that the SpriteSheet argument order is (format, maxMipLevels, hCells, vCells)."
            }
        }
        return false
    }

    /** One frame of the albedo sheet. Only valid once [sheetsReady] has returned true. */
    fun diffuseFrame(index: Int): Texture = diffuse.getTexture(index)

    /** The index-matched frame of the normal sheet. Only valid once [sheetsReady] has returned true. */
    fun normalFrame(index: Int): Texture = normal.getTexture(index)

    // --- Size ---------------------------------------------------------------------------------

    /**
     * The diver's width in world metres for a given world height, keeping the art's proportions.
     *
     * THE PLACEHOLDER WAS A SQUARE AND THE ART IS NOT, so one of the two had to give. What the
     * diver's world size means is now its HEIGHT ([Framing.DIVER_HEIGHT_METRES], still 3 m): that
     * keeps the figure at the `3 / 60` = 5% of screen height the bake was sized against (spec §3),
     * on every display and at every aspect ratio, and it is the dimension the eye actually judges
     * a swimmer by. The width follows from the sheet at 0.328 of that — about one metre — which is
     * a free-diver seen side-on, and matches the design's "free-diver in silhouette" (§11).
     *
     * Keeping the square instead would have meant a 3 m WIDE diver, i.e. stretching a 1:3 figure
     * to 1:1 — three times too fat, and wrong in a way no lighting could rescue.
     */
    fun widthForHeight(heightMetres: Float): Float = heightMetres * FRAME_ASPECT

    // --- Which way the body points ------------------------------------------------------------

    /**
     * THE SHEET'S REST ORIENTATION, stated here because nobody can infer it from the code and the
     * whole of [bodyAngleFor] depends on it. **In an unrotated frame the diver is drawn UPRIGHT,
     * facing the camera: head at the top of the cell, fins at the bottom, arms at his sides.**
     * Read off the baked sheet (`t9b_43_shallow-diver.png`), not assumed.
     *
     * So the body's long axis points SCREEN-UP at `angle = 0`, and that is the offset below.
     */
    const val REST_HEADING_DEGREES = 90f

    /**
     * The `angle` to hand `drawTexture`/`drawNormalMap` so the diver's HEAD points along
     * [beamHeading] — the smoothed torch heading `DiveLighting` owns.
     *
     * ## Two angle conventions, and they are not the same number
     *
     * [beamHeading] is in `GiSceneRenderer`'s convention: degrees counter-clockwise from +x in a
     * frame whose **+y runs UP the screen** (see `AimAngle`'s class doc, and the `-sim.vy` at the
     * call site that converts the y-DOWN world velocity into it). So 0 is right, +90 is up, -90 is
     * down.
     *
     * `drawTexture` goes through a different shader and there was no reason to assume it agreed.
     * Derived from `texture.vert` and then CONFIRMED BY CAPTURE, the way the beam's own Y-flip
     * was:
     *
     * ```
     * texture.vert:50   mat2 rotate(a) { return mat2(c, s, -s, c); }        // column-major
     * texture.vert:68   offset = (vertexPos - origin) * size * rotate(...)  // ROW-vector * matrix
     * ```
     *
     * A row-vector product is `transpose(M) * v`, so a local point `p` lands at
     * `(c*px + s*py, -s*px + c*py)` in WORLD axes, where y runs down. Take the top of the quad,
     * `p = (0, -1)`: it lands at `(-sin a, -cos a)`. At `a = 0` that is straight up (rest
     * orientation, as above); at `a = +90` it is world -x, i.e. screen LEFT. **Positive `angle`
     * therefore turns the sprite ANTI-CLOCKWISE on screen — the same sense as the beam's heading,
     * which is the piece of luck that makes this a subtraction rather than a negation.**
     *
     * Writing the head's direction as a screen-up-positive heading gives `beamHeading = a + 90`,
     * hence `a = beamHeading - 90`. `DiverSpriteTest` re-derives that from a transcription of the
     * shader's own matrix rather than restating the formula, so the two cannot agree by accident.
     *
     * ## What it inherits by taking the beam's heading rather than `sim.vx/vy`
     *
     * The easing (`AimAngle.smooth` at `AIM_SMOOTHING_RATE`) and — the one that shows — the
     * stationary HOLD: below `DiveLighting.STATIONARY_SPEED_THRESHOLD` the heading is not updated
     * at all, so a diver who coasts to a stop keeps the pose he was swimming in instead of flicking
     * back to upright every time the player lets go of the stick. Re-deriving any of that here
     * would be a second copy of playtested behaviour, and the two would visibly part company
     * mid-turn.
     */
    fun bodyAngleFor(beamHeading: Float): Float = beamHeading - REST_HEADING_DEGREES

    // --- Which frame to draw ------------------------------------------------------------------

    /**
     * ALL 41 FRAMES ARE ONE CONTINUOUS CYCLE, PLAYED IN ORDER. They are not a set of poses to
     * choose between: there is no idle pose, no kick pose and no state machine here, and adding
     * one would fight the art rather than use it. The only question this section answers is how
     * fast the one loop plays, and the answer is a constant.
     *
     * A DESIGN DECISION, NOT A MEASUREMENT — recorded as such because this codebase's constants
     * are usually the other way round and the next person should not go hunting for empirical
     * justification that does not exist. 24 fps was chosen by the project owner; nothing was
     * timed, fitted or playtested to arrive at it.
     *
     * What it implies is worth a sanity check rather than a derivation: 41 frames at 24 fps is
     * [CYCLE_SECONDS] = 1.71 s per cycle, which is a plausible length for one relaxed free-diving
     * kick. If the diver ever reads as hurried or as slow-motion on a booth panel, this is the one
     * number to change and nothing else depends on its value.
     *
     * NOT MODULATED by speed or by load. An earlier draft ramped the rate with effort; that is
     * explicitly not wanted, and a constant additionally removes any possibility of the
     * threshold-stutter that `DiveLighting.drawDiverBeam`'s hard 50/360-degree cone branch used to
     * produce before it was ramped.
     */
    const val CYCLE_FPS = 24f

    /** One trip through all [FRAME_COUNT] frames, in seconds. 41 / 24 = 1.71 s. */
    const val CYCLE_SECONDS = FRAME_COUNT / CYCLE_FPS

    /**
     * The loop phase [dt] seconds later, wrapped into `[0, CYCLE_SECONDS)`.
     *
     * THE PHASE IS ACCUMULATED ELAPSED SECONDS, NOT A FRAME COUNTER. That is what makes it
     * frame-rate independent: the same wall-clock time gives the same phase whether the game runs
     * at 60 fps or 240, and a dropped frame costs exactly the time it took rather than a whole
     * animation frame. The obvious-looking alternative — increment an integer per call — ties the
     * animation's speed to the refresh rate of whatever panel the cabinet ends up in front of, and
     * no still capture could ever show it.
     *
     * WRAPPING EVERY STEP IS WHAT KEEPS IT EXACT, and matters more than it looks. `Float` has ~7
     * significant digits; an unwrapped accumulator over an unattended two-day run would reach the
     * range where one ULP exceeds a frame's worth of time and the animation would quietly freeze,
     * hours in, with nothing in the log. Wrapped, the accumulator never exceeds 1.71, where an ULP
     * is ~1.2e-7 s against a 1/60 s step — six orders of magnitude of headroom, so a 90-second run
     * accumulates no measurable drift at all.
     *
     * The `floor` form rather than `%` is deliberate: it is also correct for a negative argument,
     * which `%` is not.
     */
    fun advancePhase(phaseSeconds: Float, dt: Float): Float
    {
        val next = phaseSeconds + dt
        return next - floor(next / CYCLE_SECONDS) * CYCLE_SECONDS
    }

    /**
     * Which cell of the sheet a phase selects. Total: any input at all — negative, enormous, NaN —
     * lands inside `0 until FRAME_COUNT` and therefore never reaches the grid's unused 42nd cell,
     * whose contents are undefined and which the bake never wrote. Wrapping on the 42-cell GRID
     * instead of on the 41 used frames would show that cell once per cycle: a blank flicker every
     * 1.71 s, invisible in a still capture and lost in the motion of a moving one.
     */
    fun frameIndex(phaseSeconds: Float): Int
    {
        val cell = floor(phaseSeconds * CYCLE_FPS).toInt() % FRAME_COUNT
        return if (cell < 0) cell + FRAME_COUNT else cell
    }

    // --- The one live loop --------------------------------------------------------------------

    /**
     * The diver's animation phase: elapsed seconds into the cycle, wrapped at [CYCLE_SECONDS].
     *
     * PRESENTATION STATE, SO IT LIVES IN `render/` AND NOWHERE ELSE. `DiveSim` simulates one run
     * and nothing else; an animation phase in there would be a rendering detail leaking into the
     * model, and would also have to be serialised into every deterministic-replay argument the
     * simulation makes.
     *
     * ## It is driven from the FIXED TICK, not the render clock
     *
     * [advanceLoop] is called from `EnPustTil.onFixedUpdate`, gated on
     * `RunLifecycle.spriteAnimates` — NOT the same gate as the single `DiveSim.tick` call,
     * which uses `RunLifecycle.simulationAdvances`. The two gates agree everywhere except
     * IDLE: `spriteAnimates` is true there so the attract-mode diver keeps kicking, while
     * `simulationAdvances` stays false so `DiveSim` never ticks (an attract-mode diver that
     * ran the simulation would burn air and "drown" on an unattended cabinet). This split
     * used to not exist — the loop held frame 0 in IDLE, see below — but both properties are
     * still fixed-tick reads for the same three reasons, and the second is the load-bearing
     * one:
     *
     *  - **The pause is airtight by construction rather than by a second copy of the condition.**
     *    A pause that stops the clock but leaves the diver swimming behind the scrim contradicts
     *    exactly what the pause screen says (`RunLifecycleState.PAUSED` deliberately keeps a
     *    stopped clock and a full ring of bubbles, because "the run is being held, not ended").
     *    Gating this from `onRender` instead would mean writing that condition out a second time,
     *    where it could drift from the one the simulation uses.
     *  - **The phase becomes a pure function of the number of fixed ticks.** Two runs that
     *    have advanced the same number of fixed steps show the same frame, whatever the refresh
     *    rate did in between — which is what makes a screenshot reproducible. This project's
     *    capture harness has already paid, twice, for state pinned on one clock and consumed on
     *    another (see `HARNESS.md`), and a render-clock phase would add a third instance.
     *  - Nothing is lost by sampling at 60 Hz: [frameIndex] quantises to [CYCLE_FPS] = 24 anyway,
     *    so a 240 fps render clock could not show an intermediate frame even if it had one. The
     *    phase itself is elapsed SECONDS, so the choice of clock changes only how finely it is
     *    sampled, never how fast the loop plays.
     *
     * **IDLE (attract mode) animates.** An earlier version of this file held frame 0 there —
     * "not worth a second, ungated animation clock" — but that trade was reversed once
     * `RunLifecycle.justReturnedToIdle` existed: without it, the attract screen showed
     * whichever frame the previous player's run happened to end on, motionless, which after a
     * 120 m timeout read as a near-dead diver hanging in a near-black abyss with a leaderboard
     * floating in it (task-9,
     * `.superpowers/sdd/2026-08-21-booth-survival/task-9-brief.md`). `spriteAnimates` is still
     * one gate, not a second ungated clock — it is `simulationAdvances` with IDLE folded back
     * in, both exhaustive `when`s in `RunLifecycle` so a sixth state cannot silently pick a
     * default.
     *
     * Reset to 0 on `RunLifecycle.justStarted` AND on `RunLifecycle.justReturnedToIdle` (see
     * [restartLoop]) so every run, and every fresh arrival at the attract screen, opens on the
     * same authored frame instead of wherever the previous player left it.
     */
    private var loopPhase = 0f

    /** Which cell of the sheet to draw this frame. */
    val currentFrame: Int get() = frameIndex(loopPhase)

    /**
     * Called on `RunLifecycle.justStarted` AND on `RunLifecycle.justReturnedToIdle`: every run,
     * and every fresh arrival at the attract screen, opens on the loop's authored first frame.
     */
    fun restartLoop()
    {
        loopPhase = 0f
    }

    /**
     * Called once per fixed tick, from `RunLifecycle.spriteAnimates` — deliberately NOT the
     * same gate that ticks the simulation (`simulationAdvances`): the sprite must also animate
     * in IDLE, where the sim stays frozen. See [loopPhase]'s doc for why these are two gates
     * and not a second, ungated clock.
     */
    fun advanceLoop(dt: Float)
    {
        loopPhase = advancePhase(loopPhase, dt)
    }
}
