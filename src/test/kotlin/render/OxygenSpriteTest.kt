package render

import dive.Tuning
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT CANNOT BE TESTED HERE, SAID PLAINLY: whether the vent LOOKS like a column of rising air.
 * Whether the normals catch the torch from a plausible direction, whether the plume reads as
 * moving upward rather than downward, whether 24 fps is the right speed for it — all of that
 * needs a real framebuffer and a pair of eyes. What is genuinely provable without one is the
 * ASSET DECLARATION, where the engine's constructor has two documented ways to fail in complete
 * silence, and the FRAME-SELECTION ARITHMETIC, which is pure. This file is `DiverSpriteTest`'s
 * sibling and covers the same two things, plus the one thing [OxygenSprite] has that the diver's
 * sheet does not: the per-vent phase offset.
 *
 * [OxygenSprite]'s `SpriteSheet` field is constructed when the object initialises. That is safe
 * without a GL context and is not an accident of the test: `Texture.<init>` assigns fields and
 * nothing else — no file is opened and no GL call is made until `AssetManager` gets to it.
 */
class OxygenSpriteTest
{
    /**
     * THE ARGUMENT-ORDER AND MIP-LEVEL TRAP, CHECKED ON THE REAL DECLARATION.
     *
     * `SpriteSheet`'s constructor takes `(…, format, maxMipLevels, hCells, vCells)`, which is NOT
     * the order its fields are declared in (`horizontalCells, verticalCells` read first). The
     * plausible-but-wrong call — `(…, HORIZONTAL_CELLS, VERTICAL_CELLS, 1)` — is accepted without
     * complaint and yields `maxMipLevels = HORIZONTAL_CELLS`, so reading `maxMipLevels` back off
     * the constructed asset catches the swap here, before a GL context exists. Left uncaught it
     * surfaces two stages later as an `ArrayIndexOutOfBoundsException` out of `getTexture`, with
     * the call site that got it wrong long off the stack.
     *
     * And it must be exactly 1, never 0: `TextureArray` computes
     * `min(maxMipLevels, floor(log2(size)) + 1)` and passes it to `glTexStorage3D` as `levels`,
     * where 0 is `GL_INVALID_VALUE` — no storage allocated, every later `glTexSubImage3D` failing,
     * no exception and no log line at all.
     *
     * `RGBA8` and not `SRGBA8` is the subtler one, and it is a measurement rather than a
     * preference: the committed sheet's normals decode RAW (`rgb/255*2-1`) to mean length 1.0000,
     * so the bake has already done the sRGB->linear decode and the GPU must not do it again.
     */
    @Test
    fun `the sheet is declared with the argument order the engine actually has`()
    {
        assertEquals(1, OxygenSprite.normal.maxMipLevels, "maxMipLevels must be 1 — 0 allocates no storage at all, and anything else means the arguments are in the wrong order")
        assertEquals(TextureFormat.RGBA8, OxygenSprite.normal.format, "the bake already decoded the normals to linear; SRGBA8 here would linearize them a second time")

        // LINEAR with one mip level, and CLAMP_TO_EDGE rather than REPEAT: with REPEAT, the linear
        // filter's half-texel reach at the sheet's outer border samples the opposite edge of the
        // sheet — i.e. a different frame.
        assertEquals(TextureFilter.LINEAR, OxygenSprite.normal.filter, "must not use a MIPMAP filter with one mip level")
        assertEquals(TextureWrapping.CLAMP_TO_EDGE, OxygenSprite.normal.wrapping, "must clamp — REPEAT wraps a frame's edge onto the far side of the sheet")
    }

    /**
     * The grid constants are measured off the bake, and a re-bake at a different `--frame-height`
     * would change every one of them. Copied numbers going stale is silent — the sheet still
     * loads, and the game shows fragments of the wrong frames — so they are re-derived here from
     * the committed PNG itself, by two independent routes:
     *
     *  - the IHDR, which every PNG puts at a fixed offset, giving the sheet's texel dimensions;
     *  - the bake's own `ept:` `tEXt` chunks, which state `frame_count`, `frame_height` and `grid`
     *    directly.
     *
     * Both, because they can disagree. The IHDR alone cannot tell 11x9 cells of 182x216 from any
     * other factorisation of 2002x1944, and the text chunks alone would still be satisfied if the
     * pixels were re-baked without rewriting them. Read with a plain chunk walk rather than an
     * image library, so the test adds no dependency and decodes no pixels.
     */
    @Test
    fun `the frame grid matches the committed sheet`()
    {
        val bytes = File(SHEET).readBytes()
        val (width, height) = pngSize(bytes)
        val text = pngText(bytes)

        assertEquals(
            OxygenSprite.HORIZONTAL_CELLS * OxygenSprite.FRAME_TEXELS_WIDE, width,
            "the sheet is $width texels wide, which is not ${OxygenSprite.HORIZONTAL_CELLS} cells of ${OxygenSprite.FRAME_TEXELS_WIDE}"
        )
        assertEquals(
            OxygenSprite.VERTICAL_CELLS * OxygenSprite.FRAME_TEXELS_TALL, height,
            "the sheet is $height texels tall, which is not ${OxygenSprite.VERTICAL_CELLS} rows of ${OxygenSprite.FRAME_TEXELS_TALL}"
        )

        // The bake's own contract, stated by the bake rather than inferred from the pixel count.
        assertEquals("${OxygenSprite.HORIZONTAL_CELLS}x${OxygenSprite.VERTICAL_CELLS}", text["ept:grid"], "the bake recorded a different grid than the constants declare")
        assertEquals(OxygenSprite.FRAME_COUNT.toString(), text["ept:frame_count"], "the bake wrote a different number of frames than the constants index")
        assertEquals(OxygenSprite.FRAME_TEXELS_TALL.toString(), text["ept:frame_height"], "the bake used a different --frame-height than the constants declare")

        // THE UNUSED CELLS ARE THE POINT OF THE NEXT TEST, so pin that they exist: 11x9 = 99 cells
        // holding 96 frames. If a re-bake ever filled the grid exactly, the wrap below stops being
        // load-bearing and this line is what should say so.
        val cells = OxygenSprite.HORIZONTAL_CELLS * OxygenSprite.VERTICAL_CELLS
        assertTrue(OxygenSprite.FRAME_COUNT <= cells, "the grid holds $cells cells but ${OxygenSprite.FRAME_COUNT} frames are indexed")
        assertEquals(3, cells - OxygenSprite.FRAME_COUNT, "the committed sheet has three unwritten trailing cells; if that changed, so did the bake")
    }

    /**
     * THE THREE UNWRITTEN CELLS. The grid is 11x9 = 99 and the bake wrote 96 frames; cells 96, 97
     * and 98 were measured at max alpha 0, i.e. fully transparent. Wrapping on 99 instead of 96
     * would select one of them once per cycle — three frames in every 96 with the vent's normals
     * blanked, so the plume flattens to an unlit disc for an eighth of a second, fifteen times a
     * minute. Invisible in a still capture and lost in the motion of a moving one.
     *
     * Swept rather than spot-checked, and deliberately including inputs the running game should
     * never produce: [OxygenSprite.frameIndex] is total, so a phase that somehow went negative or
     * enormous — and [OxygenSprite.phaseOffsetFor] means callers routinely add to a wrapped phase
     * without re-wrapping it — still lands on a frame the bake actually wrote.
     *
     * WHAT THIS ONE CANNOT CATCH, stated so nobody leans on it for the wrong thing: it is written
     * against [OxygenSprite.FRAME_COUNT], so it is vacuous if that constant is itself wrong.
     * Mutating 96 -> 99 leaves this test green (verified). What anchors the constant to the asset
     * is the `ept:frame_count` assertion in `the frame grid matches the committed sheet`, which
     * does fail on that mutation — the two tests are a pair and neither is sufficient alone.
     */
    @Test
    fun `no phase can select an unwritten cell`()
    {
        var phase = -12f
        while (phase < 24f)
        {
            val frame = OxygenSprite.frameIndex(phase)
            assertTrue(
                frame in 0 until OxygenSprite.FRAME_COUNT,
                "phase $phase selected cell $frame, outside 0..${OxygenSprite.FRAME_COUNT - 1}"
            )
            phase += 0.005f
        }

        // Every frame the bake wrote must be reachable, or part of the authored loop is dead art.
        val reached = (0 until 9600).map { OxygenSprite.frameIndex(it * (OxygenSprite.CYCLE_SECONDS / 100f)) }.toSet()
        assertEquals((0 until OxygenSprite.FRAME_COUNT).toSet(), reached, "every baked frame must be reachable")
    }

    /**
     * THE PLAYBACK RATE, AND THE TWO THINGS THAT FOLLOW FROM IT: one cycle lasts exactly 4.0 s
     * (96 frames at 24 fps), and the phase is elapsed SECONDS rather than elapsed frames.
     *
     * That second point is the one worth a test. "Phase" could plausibly mean either, the two
     * differ by a factor of 24, and confusing them yields an animation running 24x too fast or 24x
     * too slow — which looks like a deliberate art choice in a video and is impossible to see in a
     * still.
     *
     * The rate matching `DiverSprite.CYCLE_FPS` is asserted rather than merely commented, because
     * it is the reason the two were made equal: two sheets on screen at once, running at different
     * rates, beat against each other.
     */
    @Test
    fun `one cycle lasts the bake's 96 frames at 24 fps`()
    {
        assertEquals(24f, OxygenSprite.CYCLE_FPS, 1e-6f, "the rate is 24 fps — see the constant's doc")
        assertEquals(DiverSprite.CYCLE_FPS, OxygenSprite.CYCLE_FPS, 1e-6f, "the vent and the diver must run at the same rate or the two loops beat against each other")
        assertEquals(4.0f, OxygenSprite.CYCLE_SECONDS, 1e-6f, "96 frames at 24 fps is exactly 4.0 s")

        // One frame period in, exactly one frame on.
        assertEquals(0, OxygenSprite.frameIndex(0f))
        assertEquals(1, OxygenSprite.frameIndex(1f / OxygenSprite.CYCLE_FPS + 1e-4f), "one 24th of a second must advance exactly one frame")
        assertEquals(
            OxygenSprite.FRAME_COUNT - 1, OxygenSprite.frameIndex(OxygenSprite.CYCLE_SECONDS - 1e-4f),
            "the last instant of the cycle must be its last frame"
        )

        // Advancing by exactly one cycle — and by three — comes back to the beginning.
        assertEquals(0, OxygenSprite.frameIndex(OxygenSprite.advancePhase(0f, OxygenSprite.CYCLE_SECONDS)), "one whole cycle must return to frame 0")
        assertEquals(0, OxygenSprite.frameIndex(OxygenSprite.advancePhase(0f, OxygenSprite.CYCLE_SECONDS * 3f)), "three whole cycles must return to frame 0")
    }

    /**
     * FRAME-RATE INDEPENDENCE AND NO DRIFT, THEN A BOUND THAT HOLDS FOREVER.
     *
     * The booth panel's refresh rate is not known in advance and the dev machine's is not it, so
     * the loop must run on elapsed time and not on frames drawn: two half-steps must land exactly
     * where one whole step does, which "increment a counter per call" does not.
     *
     * The second half is the one a single-frame check would miss. The cabinet runs UNATTENDED FOR
     * TWO DAYS, and this phase — unlike the diver's — is deliberately never reset, because a vent
     * is scenery rather than part of a run. So it is the longest-lived accumulator in the game,
     * and without the per-step wrap it would climb into the `Float` range where one ULP exceeds a
     * frame and the plume would quietly stop moving, hours in, with nothing in the log.
     */
    @Test
    fun `the loop runs on elapsed time and stays bounded however long the cabinet runs`()
    {
        val oneStep = OxygenSprite.advancePhase(0f, 1f / 60f)
        var twoSteps = 0f
        repeat(2) { twoSteps = OxygenSprite.advancePhase(twoSteps, 1f / 120f) }

        assertEquals(oneStep, twoSteps, 1e-5f, "60 fps and 120 fps must reach the same phase after the same wall-clock time")
        assertTrue(oneStep > 0f, "the phase must actually move")

        // A 90-second run (`Tuning.RUN_SECONDS`) accumulated at two very different refresh rates.
        // Compared as a distance ROUND the cycle, since the two could straddle the wrap, and with
        // a tolerance rather than an exact frame match: 1/60 and 1/240 are not exactly
        // representable, so 5400 and 21600 accumulations cannot agree bit-for-bit.
        var at60 = 0f
        repeat(60 * 90) { at60 = OxygenSprite.advancePhase(at60, 1f / 60f) }
        var at240 = 0f
        repeat(240 * 90) { at240 = OxygenSprite.advancePhase(at240, 1f / 240f) }
        val gap = abs(at60 - at240)
        val roundTheCycle = min(gap, OxygenSprite.CYCLE_SECONDS - gap)
        assertTrue(
            roundTheCycle < (1f / OxygenSprite.CYCLE_FPS) * 0.25f,
            "after a whole 90-second run the two refresh rates are ${roundTheCycle}s apart — more than a quarter frame (phases $at60 / $at240)"
        )

        // Twelve simulated minutes through the LIVE loop, which is what actually runs at the booth.
        repeat(60 * 60 * 12) { OxygenSprite.advanceLoop(1f / 60f) }
        val frame = OxygenSprite.currentFrameFor(0)
        assertTrue(frame in 0 until OxygenSprite.FRAME_COUNT, "after twelve simulated minutes the live loop selects cell $frame, outside the baked frames")
    }

    /**
     * THE PER-VENT OFFSET, WHICH IS THE ONE THING THIS SPRITE HAS THAT THE DIVER'S DOES NOT.
     *
     * There is one diver, and there are three vents (`AirPocketField` puts one each in the Kelp,
     * the Twilight and the Trench), any two of which can share a 55 m view. Driving all of them
     * off one phase makes them pulse in unison, which reads as one mechanism blinking rather than
     * as three separate columns of air.
     *
     * SO THE PROPERTY ASSERTED IS THE ONE THAT WOULD ACTUALLY BREAK, and it is not "the offsets
     * differ" — offsets a thousandth of a second apart differ and still select the same cell of
     * the sheet, which is what the eye sees. It is that no two vents are ever on the SAME FRAME.
     * Swept over every count from two to sixteen rather than the three that ship, because the
     * offset has to survive someone adding a fourth vent; and half a second of loop phase is
     * walked underneath it, so the check is not a property of one instant.
     */
    @Test
    fun `no two vents are ever on the same frame of the loop`()
    {
        for (vents in 2..16)
        {
            var phase = 0f
            while (phase < 0.5f)
            {
                val frames = (0 until vents).map { OxygenSprite.frameIndex(phase + OxygenSprite.phaseOffsetFor(it)) }
                assertEquals(
                    vents, frames.toSet().size,
                    "with $vents vents at phase $phase the frames are $frames — two vents on one frame throb in visible lockstep"
                )
                phase += 0.017f
            }
        }
    }

    /**
     * THE SPREAD ITSELF, which is why the offset is an additive recurrence on the golden ratio and
     * not `index / count`: it must depend on the index alone — a vent's animation must not jump
     * the day someone adds a fourth — and must still land the vents near-evenly round the loop for
     * any count.
     *
     * The three-distance theorem says `frac(i / phi)` falls into at most three gap lengths for any
     * N, and phi is the irrational that makes the largest of them smallest. What that buys,
     * computed over N = 2..16: the smallest gap is never below 0.48 of the ideal
     * `CYCLE_SECONDS / N` (worst case N = 14; N = 3, the count that ships, is at 0.71). The bound
     * below is 0.45, just under that — tight enough that swapping the constant for a rational
     * (0.5 would put every even index on top of index 0) fails immediately, loose enough that it
     * is not a restatement of the arithmetic.
     *
     * Also pins the RANGE, because [OxygenSprite.frameIndex] is total and would happily hide an
     * out-of-range offset by wrapping it: a bug there would show as vents that are spread, but not
     * where the caller thinks they are, which is far harder to notice than a crash.
     */
    @Test
    fun `the offsets spread vents round the whole loop for any number of them`()
    {
        for (index in -20..200)
        {
            val offset = OxygenSprite.phaseOffsetFor(index)
            assertTrue(
                offset >= 0f && offset < OxygenSprite.CYCLE_SECONDS,
                "vent $index has offset ${offset}s, outside [0, ${OxygenSprite.CYCLE_SECONDS})"
            )
        }

        assertEquals(0f, OxygenSprite.phaseOffsetFor(0), 1e-6f, "the first vent is the reference point and must not be displaced")

        for (vents in 2..16)
        {
            val sorted = (0 until vents).map { OxygenSprite.phaseOffsetFor(it) }.sorted()
            val ideal = OxygenSprite.CYCLE_SECONDS / vents
            var smallest = OxygenSprite.CYCLE_SECONDS
            for (i in sorted.indices)
            {
                // Round the cycle, so the wrap from the last vent back to the first is a gap too.
                val next = if (i + 1 < sorted.size) sorted[i + 1] else sorted[0] + OxygenSprite.CYCLE_SECONDS
                smallest = min(smallest, next - sorted[i])
            }
            assertTrue(
                smallest > ideal * 0.45f,
                "with $vents vents the closest pair is ${smallest}s apart, below 0.45 of the ideal ${ideal}s — the offsets are clustering instead of spreading (offsets $sorted)"
            )
        }
    }

    /**
     * THE SILHOUETTE, AND THE PICKUP THAT HAS TO COVER IT.
     *
     * The vent was drawn as a `Framing.AIR_POCKET_SIZE_METRES` SQUARE until it took this sheet,
     * and the art is 0.843:1, so adopting it changed the drawn rect's shape (the rect
     * `DiveRenderer.drawAirPockets` now uses is asserted from the renderer's side, in
     * `DiveRendererTest`; this is the sheet's side of the same number). What must not change is that the whole
     * visible plume lies INSIDE `Tuning.AIR_POCKET_PICKUP_RADIUS`: a vent you can visibly swim into
     * without refilling reads as the game cheating you, and at 30 seconds of air with the abyss
     * below, being cheated of a breath ends the run.
     *
     * Checked against the worst point of the rect — a corner, not an edge — because that is the one
     * a player brushes past. It has real headroom today (1.57 m against 4 m), so this is not a
     * tripwire on the current numbers; it is what fires if someone makes the plume a tall dramatic
     * column, which is exactly the change a 96-frame sheet invites.
     */
    @Test
    fun `the whole drawn vent lies inside the pickup radius`()
    {
        val height = Framing.AIR_POCKET_SIZE_METRES
        val width = OxygenSprite.widthForHeight(height)

        assertTrue(width < height, "the cell is taller than it is wide (0.843:1), so the drawn rect must be too — got ${width}m x ${height}m")
        assertTrue(width > height * 0.5f, "the art is 0.843:1, not a sliver; a width under half the height would mean FRAME_ASPECT was inverted")

        val cornerReach = hypot(width * 0.5f, height * 0.5f)
        assertTrue(
            cornerReach < Tuning.AIR_POCKET_PICKUP_RADIUS,
            "the drawn vent's corner reaches ${cornerReach}m from its centre but the pickup radius is " +
            "${Tuning.AIR_POCKET_PICKUP_RADIUS}m — the player can touch the art without getting the breath"
        )

        // Doubling the world height doubles the world width: the aspect is a ratio, not an offset.
        assertEquals(width * 2f, OxygenSprite.widthForHeight(height * 2f), 1e-4f)
    }

    private companion object
    {
        const val SHEET = "src/main/resources/sprites/oxygen-normal.png"

        /**
         * (width, height) from a PNG's IHDR, which every PNG puts at a fixed offset: an 8-byte
         * signature, then a 4-byte chunk length, then the four bytes `IHDR`, then two big-endian
         * 32-bit dimensions.
         */
        fun pngSize(bytes: ByteArray): Pair<Int, Int>
        {
            assertTrue(bytes.size > 24, "$SHEET is not a PNG (only ${bytes.size} bytes)")
            assertEquals("IHDR", String(bytes, 12, 4), "$SHEET does not start with an IHDR chunk")
            return intAt(bytes, 16) to intAt(bytes, 20)
        }

        /**
         * Every `tEXt` chunk in the file, as keyword -> value. A chunk is a 4-byte big-endian
         * length, a 4-byte type, the payload and a 4-byte CRC; a `tEXt` payload is a Latin-1
         * keyword, a single NUL, then a Latin-1 value. Walked by hand so the test needs no image
         * library — the bake's `ept:` keys are the only reason to read them at all.
         */
        fun pngText(bytes: ByteArray): Map<String, String>
        {
            val out = mutableMapOf<String, String>()
            var offset = 8      // past the eight-byte signature
            while (offset + 12 <= bytes.size)
            {
                val length = intAt(bytes, offset)
                val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
                if (type == "tEXt")
                {
                    val payload = String(bytes, offset + 8, length, Charsets.ISO_8859_1)
                    val split = payload.indexOf('\u0000')
                    assertTrue(split > 0, "$SHEET has a tEXt chunk with no NUL separator")
                    out[payload.substring(0, split)] = payload.substring(split + 1)
                }
                if (type == "IEND") break
                offset += 12 + length
            }
            assertTrue(out.isNotEmpty(), "$SHEET carries no tEXt chunks at all — was it re-saved by a tool that strips metadata?")
            return out
        }

        fun intAt(bytes: ByteArray, offset: Int) =
            (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[offset + i].toInt() and 0xFF) }
    }
}
