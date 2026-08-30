package render

import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT CANNOT BE TESTED HERE, SAID PLAINLY: whether the diver LOOKS right. Whether the sprite is
 * the right size on a booth panel, whether the normal map lights it from a plausible direction,
 * whether the loop reads as a float rather than a twitch — all of that needs a real framebuffer
 * and a pair of eyes, and this file does not pretend otherwise. It covers the two things that are
 * genuinely provable without one: the ASSET DECLARATION, where the engine's constructor has two
 * documented ways to fail in complete silence, and the FRAME-SELECTION ARITHMETIC, which is pure.
 *
 * [DiverSprite]'s `SpriteSheet` fields are constructed when this object initialises. That is safe
 * without a GL context and is not an accident of the test: `Texture.<init>` (verified from
 * bytecode) assigns eight fields and nothing else — no file is opened and no GL call is made until
 * `AssetManager` gets to it.
 */
class DiverSpriteTest
{
    /**
     * THE ARGUMENT-ORDER AND MIP-LEVEL TRAP, CHECKED ON THE REAL DECLARATION.
     *
     * `SpriteSheet`'s constructor takes `(…, format, maxMipLevels, hCells, vCells)`, which is NOT
     * the order its fields are declared in (`horizontalCells, verticalCells` read first). The
     * plausible-but-wrong call — `(…, HORIZONTAL_CELLS, VERTICAL_CELLS, 1)` — is accepted without
     * complaint and yields `vCells = 1`... and, crucially, `maxMipLevels = HORIZONTAL_CELLS`. So
     * reading `maxMipLevels` back off the constructed asset catches the swap, here, before a GL
     * context exists. Left uncaught it surfaces two stages later as an
     * `ArrayIndexOutOfBoundsException` out of `getTexture`, with the call site that got it wrong
     * long off the stack.
     *
     * And `maxMipLevels` must be exactly 1, never 0: `TextureArray` computes
     * `min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and passes it to
     * `glTexStorage3D` as `levels`, where 0 is `GL_INVALID_VALUE` — no storage allocated, every
     * later `glTexSubImage3D` failing, no exception and no log line at all.
     *
     * The formats are here for a subtler reason: the diffuse sheet holds sRGB-encoded pixels and
     * the normal sheet holds linear ones, and swapping them looks like an art problem rather than
     * a code one — a washed-out diver, or a normal map linearized twice and lit from the wrong
     * side.
     */
    @Test
    fun `the sheets are declared with the argument order the engine actually has`()
    {
        assertEquals(1, DiverSprite.diffuse.maxMipLevels, "diffuse maxMipLevels must be 1 — 0 allocates no storage at all, and anything else means the arguments are in the wrong order")
        assertEquals(1, DiverSprite.normal.maxMipLevels, "normal maxMipLevels must be 1 — see the diffuse message")

        assertEquals(TextureFormat.SRGBA8, DiverSprite.diffuse.format, "the albedo is sRGB-encoded and the GPU must linearize it on sample")
        assertEquals(TextureFormat.RGBA8, DiverSprite.normal.format, "the bake already decoded the normals to linear; SRGBA8 here would linearize them a second time")

        // CLAMP_TO_EDGE, not REPEAT: with REPEAT, the linear filter's half-texel reach at the
        // sheet's outer border samples the opposite edge of the sheet — i.e. a different frame.
        for ((name, sheet) in listOf("diffuse" to DiverSprite.diffuse, "normal" to DiverSprite.normal))
        {
            assertEquals(TextureFilter.LINEAR, sheet.filter, "$name must not use a MIPMAP filter with one mip level")
            assertEquals(TextureWrapping.CLAMP_TO_EDGE, sheet.wrapping, "$name must clamp — REPEAT wraps a frame's edge onto the far side of the sheet")
        }
    }

    /**
     * The grid constants are copied from the bake, and a re-bake at a different `--frame-height`
     * would change every one of them: `tools/build_spritesheet.py` SEARCHES for the grid that
     * minimises the texture bucket, so 256 gives 11x4 and 512 gives 11x4 again where 384 gives
     * 14x3. Copied numbers going stale is silent — the sheet still loads, and the game shows
     * fragments of the wrong frames — so they are re-derived here from the committed PNG itself.
     *
     * Reads the IHDR chunk directly rather than through an image library: the dimensions live at
     * a fixed offset in every PNG, and this way the test has no dependency and decodes no pixels.
     */
    @Test
    fun `the frame grid matches the committed sheet`()
    {
        val diffuse = pngSize("src/main/resources/sprites/diver-diffuse.png")
        val normal = pngSize("src/main/resources/sprites/diver-normal.png")

        assertEquals(diffuse, normal, "the two sheets must be cell-for-cell aligned, so they must be the same size")

        assertEquals(
            DiverSprite.HORIZONTAL_CELLS * DiverSprite.FRAME_TEXELS_WIDE, diffuse.first,
            "the sheet is ${diffuse.first} texels wide, which is not ${DiverSprite.HORIZONTAL_CELLS} cells of ${DiverSprite.FRAME_TEXELS_WIDE}"
        )
        assertEquals(
            DiverSprite.VERTICAL_CELLS * DiverSprite.FRAME_TEXELS_TALL, diffuse.second,
            "the sheet is ${diffuse.second} texels tall, which is not ${DiverSprite.VERTICAL_CELLS} rows of ${DiverSprite.FRAME_TEXELS_TALL}"
        )

        // 41 used of 42, and the grid must hold at least that. The engine indexes row-major, so
        // one unused TRAILING cell is fine and is what buys the smaller texture bucket.
        assertTrue(
            DiverSprite.FRAME_COUNT <= DiverSprite.HORIZONTAL_CELLS * DiverSprite.VERTICAL_CELLS,
            "the grid holds ${DiverSprite.HORIZONTAL_CELLS * DiverSprite.VERTICAL_CELLS} cells but ${DiverSprite.FRAME_COUNT} frames are indexed"
        )
    }

    /**
     * FRAME-RATE INDEPENDENCE, AND NO DRIFT OVER A WHOLE RUN. The booth panel's refresh rate is
     * not known in advance and the dev machine's is not it, so the loop must run on elapsed time
     * and not on frames drawn: two half-steps must land exactly where one whole step does, which
     * the obvious-looking "increment a counter per call" does not.
     *
     * The second half is the one a single-frame check would miss. A run lasts 90 seconds
     * (`Tuning.RUN_SECONDS`), which is 5400 accumulations at 60 Hz and 21600 at 240 Hz; if the
     * phase accumulated rounding error, the two would have visibly parted by the end. They must
     * still be on the same frame.
     */
    @Test
    fun `the loop advances with elapsed time and does not drift over a whole run`()
    {
        val oneStep = DiverSprite.advancePhase(0f, 1f / 60f)

        var twoSteps = 0f
        repeat(2) { twoSteps = DiverSprite.advancePhase(twoSteps, 1f / 120f) }

        assertEquals(oneStep, twoSteps, 1e-5f, "60 fps and 120 fps must reach the same phase after the same wall-clock time")
        assertTrue(oneStep > 0f, "the phase must actually move")

        // A full 90-second run, accumulated at two very different refresh rates.
        var at60 = 0f
        repeat(60 * 90) { at60 = DiverSprite.advancePhase(at60, 1f / 60f) }
        var at240 = 0f
        repeat(240 * 90) { at240 = DiverSprite.advancePhase(at240, 1f / 240f) }

        // Compared as a distance ROUND the cycle, since the two could straddle the wrap, and with
        // a tolerance rather than an exact frame match: `1f/60f` and `1f/240f` are not exactly
        // representable, so 5400 and 21600 accumulations cannot agree bit-for-bit and a
        // frame-index comparison would be a coin toss whenever the run happens to end near a frame
        // boundary. What must hold is that the disagreement stays far below one frame — a quarter
        // of one here, where a per-frame counter would be out by whole cycles.
        val gap = abs(at60 - at240)
        val roundTheCycle = min(gap, DiverSprite.CYCLE_SECONDS - gap)
        assertTrue(
            roundTheCycle < (1f / DiverSprite.CYCLE_FPS) * 0.25f,
            "after a whole 90-second run the two refresh rates are ${roundTheCycle}s apart — more than a quarter frame (phases $at60 / $at240)"
        )
    }

    /**
     * THE UNUSED 42nd CELL. The grid is 14x3 = 42 and the bake writes 41 frames: source frame 42
     * is an exported duplicate of frame 1 and dropping it is what removes a one-frame dead stop
     * from every loop. So cell 41 was never written, and wrapping on 42 instead of 41 would show
     * whatever is in it once per cycle — a blank flicker three times a second that a still capture
     * cannot show and a moving one hides in the motion.
     *
     * Swept rather than spot-checked, and deliberately including inputs the running game should
     * never produce: [DiverSprite.frameIndex] is total, so a phase that somehow went negative or
     * enormous still lands on a real frame rather than off the end of the array.
     */
    @Test
    fun `no phase can select the grid's unused last cell`()
    {
        var phase = -5f
        while (phase < 20f)
        {
            val frame = DiverSprite.frameIndex(phase)
            assertTrue(
                frame in 0 until DiverSprite.FRAME_COUNT,
                "phase $phase selected cell $frame, outside 0..${DiverSprite.FRAME_COUNT - 1}"
            )
            phase += 0.005f
        }

        // Every frame the bake wrote must be reachable, or part of the authored loop is dead art.
        val reached = (0 until 4100).map { DiverSprite.frameIndex(it * (DiverSprite.CYCLE_SECONDS / 100f)) }.toSet()
        assertEquals((0 until DiverSprite.FRAME_COUNT).toSet(), reached, "every baked frame must be reachable")
    }

    /**
     * THE PLAYBACK RATE IS A DESIGN DECISION, and this pins the two things that follow from it:
     * one cycle lasts [DiverSprite.CYCLE_SECONDS] = 41/24 = 1.71 s, and the phase is elapsed
     * SECONDS rather than elapsed frames.
     *
     * That second point is the one worth a test. "Phase" could plausibly mean either, the two
     * differ by a factor of 24, and confusing them yields an animation running 24x too fast or
     * 24x too slow — which is exactly the kind of thing that looks like a deliberate art choice
     * in a video and is impossible to see in a still.
     */
    @Test
    fun `one cycle lasts the design's 41 frames at 24 fps`()
    {
        assertEquals(24f, DiverSprite.CYCLE_FPS, 1e-6f, "the rate is 24 fps by design decision — see the constant's doc")
        assertEquals(DiverSprite.FRAME_COUNT / 24f, DiverSprite.CYCLE_SECONDS, 1e-6f)

        // One frame period in, exactly one frame on.
        assertEquals(0, DiverSprite.frameIndex(0f))
        assertEquals(1, DiverSprite.frameIndex(1f / DiverSprite.CYCLE_FPS + 1e-4f), "one 24th of a second must advance exactly one frame")
        assertEquals(
            DiverSprite.FRAME_COUNT - 1, DiverSprite.frameIndex(DiverSprite.CYCLE_SECONDS - 1e-4f),
            "the last instant of the cycle must be its last frame"
        )

        // Advancing by exactly one cycle — and by three — comes back to the beginning.
        assertEquals(0, DiverSprite.frameIndex(DiverSprite.advancePhase(0f, DiverSprite.CYCLE_SECONDS)), "one whole cycle must return to frame 0")
        assertEquals(0, DiverSprite.frameIndex(DiverSprite.advancePhase(0f, DiverSprite.CYCLE_SECONDS * 3f)), "three whole cycles must return to frame 0")
    }

    /**
     * The cabinet runs unattended for two days. `loopPhase` is a `Float` accumulated for the whole
     * session, so without the wrap it would climb into the range where one ULP exceeds a frame and
     * the animation would quietly stop moving — after hours, at the booth, in front of a queue,
     * with nothing in the log. Wrapping every step bounds it forever.
     */
    @Test
    fun `the phase stays bounded however long the cabinet runs`()
    {
        var phase = 0f
        repeat(60 * 60 * 12) { phase = DiverSprite.advancePhase(phase, 1f / 60f) }

        assertTrue(
            phase >= 0f && phase < DiverSprite.CYCLE_SECONDS,
            "after twelve simulated minutes the phase is $phase, outside [0, ${DiverSprite.CYCLE_SECONDS})"
        )
    }

    // --- The kick's animation speed -------------------------------------------------------------

    /**
     * [DiverSprite.advanceLoop] gained a speed multiplier so the swim loop can run faster for the
     * 0.35 s a kick burst lasts. **The default must be bit-for-bit the old behaviour**, because
     * every other caller — attract mode, and the paused/idle path — still calls the single-argument
     * form and none of them should have to know the parameter exists.
     *
     * `dt * 1f == dt` exactly in IEEE-754, so this is a real equality rather than a tolerance.
     */
    @Test
    fun `advanceLoop's default multiplier is exactly the old single-argument behaviour`()
    {
        DiverSprite.restartLoop()
        repeat(97) { DiverSprite.advanceLoop(1f / 60f) }
        val implicit = DiverSprite.currentFrame

        DiverSprite.restartLoop()
        repeat(97) { DiverSprite.advanceLoop(1f / 60f, 1f) }
        val explicit = DiverSprite.currentFrame

        assertEquals(explicit, implicit, "advanceLoop(dt) and advanceLoop(dt, 1f) must be the same call")
        DiverSprite.restartLoop()
    }

    /**
     * The multiplier scales the RATE, and it does so by scaling `dt` rather than the phase — which
     * is what keeps [DiverSprite.advancePhase]'s frame-rate independence and its wrap intact.
     *
     * The two step sizes are chosen to be exactly representable so this can assert an exact frame
     * rather than a fuzzy one: 0.25 s at 24 fps is frame 6 on the nose, and 0.25 s at 1.5x is
     * 0.375 s of loop, i.e. frame 9. A multiplier applied to the phase instead of to `dt` would
     * give the same answer here for one step and then diverge — so the twelve-minute wrap case
     * below is the other half of the assertion.
     */
    @Test
    fun `the speed multiplier advances the loop proportionally faster`()
    {
        DiverSprite.restartLoop()
        DiverSprite.advanceLoop(0.25f)
        assertEquals(6, DiverSprite.currentFrame, "0.25 s at 24 fps is frame 6")

        DiverSprite.restartLoop()
        DiverSprite.advanceLoop(0.25f, 1.5f)
        assertEquals(9, DiverSprite.currentFrame, "0.25 s played at 1.5x is 0.375 s of loop, i.e. frame 9")

        DiverSprite.restartLoop()
    }

    /**
     * The kick's multiplier must not be able to break the two guarantees the loop already had:
     * the phase stays inside `[0, CYCLE_SECONDS)` for the length of an unattended booth day, and
     * [DiverSprite.frameIndex] never reaches the grid's unused 42nd cell (whose contents the bake
     * never wrote — it would flash as a blank frame).
     *
     * Run at the kick rate for twelve simulated minutes, which is far longer than any real burst,
     * precisely because the failure this guards is an accumulator that grows without bound.
     */
    @Test
    fun `the kick multiplier cannot push the loop out of its frame range`()
    {
        DiverSprite.restartLoop()
        repeat(60 * 60 * 12) {
            DiverSprite.advanceLoop(1f / 60f, DiverSprite.KICK_CYCLE_MULTIPLIER)
            val frame = DiverSprite.currentFrame
            assertTrue(
                frame in 0 until DiverSprite.FRAME_COUNT,
                "the loop reached frame $frame, outside 0 until ${DiverSprite.FRAME_COUNT}"
            )
        }
        DiverSprite.restartLoop()
    }

    /**
     * The one thing the constant's VALUE has to satisfy: it must speed the loop up. A multiplier
     * of 1 would make the kick invisible in the art, and anything below 1 would have the diver
     * slow down at the exact moment he is working hardest — the opposite of what was asked for.
     * The 1.5 itself is a taste decision (see the constant's doc) and is pinned here only so a
     * casual edit has to be a deliberate one.
     */
    @Test
    fun `the kick plays the swim loop faster, not slower`()
    {
        assertTrue(
            DiverSprite.KICK_CYCLE_MULTIPLIER > 1f,
            "a kick must speed the loop up; ${DiverSprite.KICK_CYCLE_MULTIPLIER} would slow it down or freeze it"
        )
        assertEquals(1.5f, DiverSprite.KICK_CYCLE_MULTIPLIER, 1e-6f, "1.5x by design decision — see the constant's doc")
    }

    /** A fresh run opens on the loop's authored first frame, not wherever the last player left it. */
    @Test
    fun `a new run restarts the loop at its first frame`()
    {
        repeat(40) { DiverSprite.advanceLoop(1f / 60f) }
        assertTrue(DiverSprite.currentFrame != 0, "the fixture is meaningless unless the loop actually moved off frame 0")

        DiverSprite.restartLoop()

        assertEquals(0, DiverSprite.currentFrame, "RunLifecycle.justStarted must put the diver back on frame 0")
    }

    /**
     * THE SILHOUETTE, AND THE HUD ANCHORED TO IT.
     *
     * The placeholder was a square whose side grew with the haul, to 9 m under a full load —
     * half-extent 4.5 m against an air ring orbiting at 5.5 m, i.e. the bubbles very nearly inside
     * the diver. The sprite is a 1:3 figure at a FIXED height, so both halves of that improve at
     * once: the silhouette is a tall swimmer rather than a block, and it no longer grows into the
     * HUD at all.
     *
     * `Hud.render` centres both diver-anchored elements — the air ring and the HELD numerals — on
     * the diver's world position, which is the sprite's centre. The ring is the game's ONLY air
     * warning, so what has to hold is not merely that the two do not overlap but that the bubbles
     * clear the body with room to read: a full ring's 12 o'clock bubble is the one directly above
     * the head, and it must sit clear of it. Asserted rather than eyeballed because the diver's
     * height is now a number someone may well raise again — see `Framing.DIVER_HEIGHT_METRES`,
     * where the rest of this reasoning lives.
     *
     * IT HAS EARNED ITS KEEP TWICE. It passed the 3 -> 6 m change untouched and FAILED the 6 -> 9
     * one, at 0.55 m of headroom against a 0.9 m bubble, which is what took `Hud
     * .AIR_RING_RADIUS_METRES` from 5.5 to 6.0.
     */
    @Test
    fun `the diver is a tall figure that stays inside the air ring`()
    {
        val height = Framing.DIVER_HEIGHT_METRES
        val width = DiverSprite.widthForHeight(height)

        assertTrue(width < height * 0.5f, "the art is 1:3, so drawing it needs a rect much taller than it is wide (got ${width}m x ${height}m)")
        assertTrue(width > 0f, "the diver must have a width")

        // The 12 o'clock bubble of a full ring, against the top of the diver's head.
        val headroom = Hud.AIR_RING_RADIUS_METRES - Hud.AIR_BUBBLE_SIZE_METRES * 0.5f - height * 0.5f
        assertTrue(
            headroom > Hud.AIR_BUBBLE_SIZE_METRES,
            "the air ring clears the diver's head by only ${headroom}m — less than one bubble, so the game's only air warning is drawn on top of the art"
        )

        // Doubling the world height doubles the world width: the aspect is a ratio, not an offset.
        assertEquals(width * 2f, DiverSprite.widthForHeight(height * 2f), 1e-4f)
    }

    /**
     * THE END OF THE RUN, WHICH IS THE ONE MOMENT THE RING HAS TO WIN.
     *
     * The case above covers a FULL ring. The tight one is the empty ring: below
     * `Hud.AIR_LOW_THRESHOLD` each surviving bubble is drawn up to 2.8x oversized
     * (`Hud.airBubbleSizeScale`) and pulsed by the heartbeat, so the last bubble is a big red
     * throbbing dot at 1.5 s of air remaining.
     *
     * At 9 m that bubble's bounding box genuinely does clip the diver's — by 0.34 m — and the
     * ring radius that would prevent it is 6.91 m, a 13.8 m disc in a 60 m view. So the accepted
     * property is the weaker and more meaningful one: **no bubble is ever CENTRED on the diver.**
     * A bubble whose centre lands inside the figure is drawn over the middle of him and reads as
     * part of the art; one whose corner overlaps reads as a bubble in front of a shoulder, which
     * is what the HUD surface is for. The distinction is the whole of why the ring works at all
     * once it is nearly empty.
     *
     * Swept over every state that can actually occur — every low-air count, every slot
     * `Hud.firstOccupiedSlot` leaves occupied in it, and both ends of the heartbeat — rather than
     * against a hand-picked worst case, because which slot is worst depends on the ring radius
     * and would silently stop being the one someone checked.
     */
    @Test
    fun `no air bubble is ever drawn centred on the diver, however empty the ring gets`()
    {
        val halfHeight = Framing.DIVER_HEIGHT_METRES * 0.5f
        val halfWidth = DiverSprite.widthForHeight(Framing.DIVER_HEIGHT_METRES) * 0.5f

        for (remaining in 1..Hud.AIR_LOW_THRESHOLD)
        {
            for (slot in Hud.firstOccupiedSlot(remaining) until Hud.AIR_BUBBLE_COUNT)
            {
                val angle = Hud.airBubbleSlotAngle(slot)
                // The heartbeat scales the orbit and the bubble together; its trough pulls the
                // bubble furthest in, which is the direction that matters here.
                for (pulse in listOf(0.85f, 1f, 1.15f))
                {
                    val radius = Hud.AIR_RING_RADIUS_METRES * pulse
                    val bx = cos(angle) * radius
                    val by = sin(angle) * radius
                    assertTrue(
                        abs(bx) > halfWidth || abs(by) > halfHeight,
                        "with $remaining bubbles left, slot $slot at pulse $pulse puts a bubble's CENTRE at " +
                        "(${bx}, ${by}) — inside the diver's ${halfWidth * 2}m x ${halfHeight * 2}m rect, so the " +
                        "game's only air warning is drawn on top of the figure instead of beside it"
                    )
                }
            }
        }
    }

    /**
     * THE ROTATION CONVENTION, DERIVED FROM THE SHADER RATHER THAN RESTATED.
     *
     * `DiverSprite.bodyAngleFor` turns the torch's heading — which is in `GiSceneRenderer`'s
     * convention, counter-clockwise from +x with **+y UP** — into the `angle` `drawTexture` and
     * `drawNormalMap` want, and there was no reason to assume the two shaders agree about sign.
     * `AimAngle`'s class doc records how much care the lighting one alone took to pin down.
     *
     * So this does not restate `heading - 90`, which would pass whatever the constant was. It
     * transcribes `texture.vert`'s own rotation — `mat2(c, s, -s, c)` applied as a ROW-vector
     * product, i.e. `p * M` = `transpose(M) * p` — pushes the top-centre of the quad through it,
     * and asserts the resulting WORLD-space direction (x right, y DOWN) is the direction the beam
     * is pointing. Getting the offset wrong by a sign or a quarter turn moves the head somewhere
     * else and this fails; only the correct convention makes the two independent derivations agree.
     *
     * A sprite rotating the wrong way looks almost right in a still and is obviously wrong in
     * motion, which is exactly the class of thing worth a test rather than a screenshot.
     */
    @Test
    fun `the body is drawn pointing along the torch's heading`()
    {
        for (heading in listOf(0f, 45f, 90f, -90f, 180f, -180f, 137.5f, -170f, 359f))
        {
            val a = Math.toRadians(DiverSprite.bodyAngleFor(heading).toDouble())
            val c = cos(a)
            val sn = sin(a)

            // texture.vert:68  offset = (vertexPos - origin) * size * rotate(radians(angle))
            // texture.vert:50  rotate(a) = mat2(c, s, -s, c)          (GLSL, column-major)
            // A row-vector product is transpose(M) * p, so p = (px, py) lands at
            // (c*px + s*py, -s*px + c*py). The diver's head is the top-centre of the quad, which
            // in origin-relative local coordinates is (0, -1) — world y runs DOWN.
            val headX = (c * 0.0 + sn * -1.0).toFloat()
            val headY = (-sn * 0.0 + c * -1.0).toFloat()

            // The beam heading is CCW from +x in a Y-UP frame, so as a world (y-down) direction it
            // is (cos h, -sin h).
            val h = Math.toRadians(heading.toDouble())
            val beamX = cos(h).toFloat()
            val beamY = (-sin(h)).toFloat()

            assertEquals(beamX, headX, 1e-4f, "at heading $heading the head points x=$headX, the torch points x=$beamX")
            assertEquals(beamY, headY, 1e-4f, "at heading $heading the head points y=$headY, the torch points y=$beamY")
        }
    }

    /**
     * THE REST ORIENTATION, ASSERTED INDEPENDENTLY OF THE CONSTANT THAT ENCODES IT.
     *
     * The first version of this test asserted `bodyAngleFor(REST_HEADING_DEGREES) == 0`, which is
     * true for EVERY value of the constant — `h - h` — and duly survived mutating it to 0. It is
     * kept in that shape below only as the last line; what makes the test real is the two before
     * it, which say what the constant has to MEAN: the sheet is authored head-up, so the heading
     * at which the sprite needs no rotation must be the one pointing straight UP the screen. In
     * `GiSceneRenderer`'s convention (counter-clockwise from +x, +y UP) that direction is
     * `(cos h, sin h) = (0, 1)`, and only 90 satisfies it.
     *
     * `DiveLighting` sets its resting `beamAngleDeg` from this same constant — the heading a diver
     * who has never moved is drawn at — so a wrong value here puts the diver on his head on the
     * attract screen and points his torch at the sea floor.
     */
    @Test
    fun `an unrotated frame is the diver standing upright`()
    {
        val h = Math.toRadians(DiverSprite.REST_HEADING_DEGREES.toDouble())
        assertEquals(0f, cos(h).toFloat(), 1e-4f, "the rest heading must have no sideways component")
        assertEquals(1f, sin(h).toFloat(), 1e-4f, "the rest heading must point straight UP the screen, which is how the sheet is drawn")
        assertEquals(0f, DiverSprite.bodyAngleFor(DiverSprite.REST_HEADING_DEGREES), 1e-4f)
    }

    /**
     * PART 3 OF THE RING THAT REPLACES "derive MAX_DEPTH from the art".
     *
     * `dive/` may not import `render/`, so `Tuning.MAX_DEPTH` cannot be an expression over
     * [SandBank.MEAN_CREST_DEPTH]. What is enforced instead is a three-part ring:
     * `SandBankTest` asserts the crest and the fins agree with `Tuning.MAX_DEPTH` given the
     * placement, and re-derives the crest row from the committed sandbank; THIS case
     * re-derives the fin reach from the committed diver sheet. Take any one away and the
     * ring opens — the first would pass against two stale constants.
     *
     * Measured over all 41 frames at the same `alpha > 16` threshold the rest of the
     * project uses: lowest opaque row min 374, mean 378.76, MAX 381. The max, not the
     * mean, because the sand must clear the DEEPEST fin the loop ever draws.
     *
     * The method validates against a figure derived independently, months earlier:
     * `Tuning.PEARL_PICKUP_RADIUS`'s KDoc measured this same sheet for an unrelated purpose
     * and records the crown at row 2, identical in all 41 frames — `9 * (192 - 2) / 384` =
     * 4.453125 m, the same number to the digit from the other end of the sprite.
     */
    @Test
    fun `the fin reach is the deepest opaque row of any frame, re-derived from the committed sheet`()
    {
        val image = ImageIO.read(File("src/main/resources/sprites/diver-diffuse.png"))
        var lowest = -1
        for (frame in 0 until DiverSprite.FRAME_COUNT)
        {
            val cellX = (frame % DiverSprite.HORIZONTAL_CELLS) * DiverSprite.FRAME_TEXELS_WIDE
            val cellY = (frame / DiverSprite.HORIZONTAL_CELLS) * DiverSprite.FRAME_TEXELS_TALL
            val argb = image.getRGB(
                cellX, cellY, DiverSprite.FRAME_TEXELS_WIDE, DiverSprite.FRAME_TEXELS_TALL,
                null, 0, DiverSprite.FRAME_TEXELS_WIDE
            )
            for (row in DiverSprite.FRAME_TEXELS_TALL - 1 downTo 0)
            {
                val opaque = (0 until DiverSprite.FRAME_TEXELS_WIDE).any { x ->
                    (argb[row * DiverSprite.FRAME_TEXELS_WIDE + x] ushr 24) > 16
                }
                if (opaque)
                {
                    if (row > lowest) lowest = row
                    break
                }
            }
        }

        assertEquals(
            DiverSprite.LOWEST_OPAQUE_TEXEL_ROW, lowest,
            "the committed diver sheet's deepest opaque row over all ${DiverSprite.FRAME_COUNT} " +
            "frames is $lowest, not ${DiverSprite.LOWEST_OPAQUE_TEXEL_ROW} — re-bake the diver and " +
            "the sandbank's placement (SandBank.QUAD_TOP_DEPTH) no longer puts his fins on the crest"
        )
        assertEquals(4.453125f, DiverSprite.FIN_REACH_METRES, 1e-6f)
    }

    private companion object
    {
        /**
         * (width, height) from a PNG's IHDR, which every PNG puts at a fixed offset: an 8-byte
         * signature, then a 4-byte chunk length, then the four bytes `IHDR`, then two big-endian
         * 32-bit dimensions.
         */
        fun pngSize(path: String): Pair<Int, Int>
        {
            val bytes = File(path).readBytes()
            assertTrue(bytes.size > 24, "$path is not a PNG (only ${bytes.size} bytes)")
            assertEquals("IHDR", String(bytes, 12, 4), "$path does not start with an IHDR chunk")

            fun intAt(offset: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[offset + i].toInt() and 0xFF) }
            return intAt(16) to intAt(20)
        }
    }
}
