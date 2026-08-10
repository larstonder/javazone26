package render

import no.njoh.pulseengine.core.asset.types.Texture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MOST OF THE ROUND-EMITTER CHANGE IS ONLY VERIFIABLE BY LOOKING, and that is said here rather
 * than papered over with assertions that cannot fail. Whether the diver's light reads as a torch
 * instead of a rotating rectangle is a screenshot question; see this task's report and contact
 * sheet.
 *
 * What IS assertable is the part that made the old emitter wrong:
 *
 *  - the emitter's extent is a function of RADIUS and nothing else (a square's is not — that is
 *    precisely the "rotated square that turns with the flashlight heading" report);
 *  - the alpha = 0.5 contour, which is the ONLY contour `scene.frag`'s discard and
 *    `jfa_seed.frag`'s SDF seed actually read, is the circle inscribed in the quad `DiveLighting`
 *    asked for — not some smaller circle that would silently shrink every light;
 *  - RGB is flat, because GI samples a light's colour where a RAY HITS IT, i.e. on the rim, so a
 *    colour falloff would scale escaping light to zero (the shape of the mistake this whole file
 *    exists to avoid);
 *  - an un-uploaded emitter degrades to BLANK rather than to a handle the shader would discard.
 *
 * What is deliberately NOT asserted is a light-budget compensation. The geometry says a disc
 * intercepts pi/4 of the rays its bounding square does; the capture says the frame mean moves by
 * 2.2%, because the tone mapper and the thresholded bloom sit between radiance and pixels. That
 * number belongs in a measurement, not in a test.
 */
class LightEmitterTest
{
    /**
     * THE BUG, STATED AS AN ASSERTION. A square emitter is 1.0 half-widths across its faces and
     * 1.41 across its diagonals, so how far it throws depends on which way it is pointing — and
     * `DiveLighting` rotates the diver's light quad by his heading, so that dependence turned
     * into a visible rectangle wheeling around him as he swam.
     *
     * Rotational symmetry is therefore not a nicety, it is the whole fix. Checked at radii either
     * side of the ramp and at angles that include the diagonals, where a square is furthest from
     * a circle. The tolerance is 1e-5 rather than exact because `hypot` and the smoothstep are
     * float arithmetic, not because any real asymmetry is being allowed for.
     */
    @Test
    fun `the emitter's extent depends on radius alone, so it cannot turn with the beam`()
    {
        for (radius in listOf(0.2f, 0.5f, 0.8f, 0.95f, 1.0f, 1.1f, 1.35f))
        {
            val onAxis = LightEmitter.alphaAt(radius, 0f)
            for (degrees in 0 until 360 step 15)
            {
                val a = degrees * PI.toFloat() / 180f
                val alpha = LightEmitter.alphaAt(radius * cos(a), radius * sin(a))
                assertEquals(
                    onAxis, alpha, 1e-5f,
                    "the emitter is not rotationally symmetric: at radius $radius it is $onAxis along +x " +
                    "but $alpha at $degrees degrees, so the light's reach would depend on the beam's heading"
                )
            }
        }
    }

    /**
     * WHICH CIRCLE, AND WHY 0.5 IS THE ONLY NUMBER THAT MATTERS.
     *
     * `scene.frag` discards a light's fragment when `texColor.a < 0.5`, and `jfa_seed.frag` seeds
     * the signed distance field GI raymarches against from `alpha > 0.5` on the same surface. So
     * the emitter the engine builds is exactly the alpha > 0.5 region, and every alpha value away
     * from that contour is invisible to it. That contour has to sit at the quad's inscribed
     * circle: `DiveLighting` reasons about `PEARL_LIGHT_SIZE_METRES` and
     * `DIVER_LIGHT_SIZE_METRES` as the light's real size — its cull margins and its
     * `upscaleSmallSources` headroom are both written in terms of them — and a contour at, say,
     * 0.7 of that would shrink every light by a third with nothing to say so.
     *
     * Asserted as a bracket rather than as a value at r = 1, so that it fails whichever way the
     * ramp is moved or narrowed, and on both sides.
     */
    @Test
    fun `the emitter is the disc inscribed in the quad, measured at the only contour GI reads`()
    {
        assertEquals(1f, LightEmitter.alphaAt(0f, 0f), 1e-6f, "the centre of the emitter must be fully opaque")

        for (degrees in 0 until 360 step 15)
        {
            val a = degrees * PI.toFloat() / 180f
            val inside = LightEmitter.alphaAt(0.97f * cos(a), 0.97f * sin(a))
            val outside = LightEmitter.alphaAt(1.03f * cos(a), 1.03f * sin(a))

            assertTrue(inside > 0.5f, "at $degrees degrees the emitter already ends at 0.97 of its own half-width (alpha $inside), so the light is smaller than the size DiveLighting asked for")
            assertTrue(outside < 0.5f, "at $degrees degrees the emitter still reaches past its own half-width at 1.03 (alpha $outside), so it is not inscribed in its quad")
        }

        // The corners are what made it a square. They must be gone entirely, not merely dim:
        // anything at or above 0.5 there is emitting geometry as far as the SDF is concerned.
        assertEquals(0f, LightEmitter.alphaAt(1f, 1f), 1e-6f, "the quad's corners must be discarded, or the emitter is still a square")
    }

    /**
     * THE MISTAKE THIS PINS DOWN. "Radial falloff" reads as a bright centre fading to black, and
     * putting that in RGB would have made the lights very nearly invisible: `radiance_cascades
     * .frag`'s `sampleScene` reads the emitter's colour AT THE RAY'S HIT POSITION, which for any
     * probe outside the light is on its rim. A colour that falls to zero at the rim scales every
     * escaping ray by zero.
     *
     * So the buffer's colour must be flat, and the falloff must be in alpha alone. Reads the
     * generated bytes rather than the generator's source, so it also catches the RGB being built
     * from `alphaAt` by a later "tidy-up".
     */
    @Test
    fun `the generated colour is flat, because GI samples a light's colour on its rim`()
    {
        val pixels = LightEmitter.buildPixels()
        assertEquals(LightEmitter.TEXELS * LightEmitter.TEXELS * 4, pixels.remaining(), "the buffer must hold RGBA for every texel")

        var alphaSeen = 0
        for (i in 0 until LightEmitter.TEXELS * LightEmitter.TEXELS)
        {
            val r = pixels.get(i * 4).toInt() and 0xFF
            val g = pixels.get(i * 4 + 1).toInt() and 0xFF
            val b = pixels.get(i * 4 + 2).toInt() and 0xFF
            assertEquals(255, r, "texel $i is not full-white in red — a colour falloff would dim every ray that escapes the light")
            assertEquals(255, g, "texel $i is not full-white in green")
            assertEquals(255, b, "texel $i is not full-white in blue")
            if ((pixels.get(i * 4 + 3).toInt() and 0xFF) >= 128) alphaSeen++
        }

        // And the alpha really does carry the shape: the emitting region is the inscribed disc,
        // so it covers pi/4 of the square buffer. A generator that filled alpha with a constant
        // would pass every colour assertion above and fail here.
        val coverage = alphaSeen.toFloat() / (LightEmitter.TEXELS * LightEmitter.TEXELS)
        assertEquals(
            (PI / 4.0).toFloat(), coverage, 0.01f,
            "the emitting texels cover $coverage of the texture, not the pi/4 an inscribed disc covers"
        )
    }

    /**
     * THE FALLBACK, WHICH IS NOT COSMETIC.
     *
     * `AssetManager.load` only queues; the GL upload lands some frames later, and until it does
     * `Texture.handle` is `TextureHandle.INVALID` — texture index 65535. The shader's "no texture"
     * sentinel is 65534, so handing it the un-uploaded texture would NOT take the untextured
     * branch: it would sample a slice that does not exist, and `texColor.a < 0.5` would then
     * discard every light in the game. A black screen at the booth, with nothing in the log.
     *
     * Falling back to `Texture.BLANK` degrades to the old square emitter instead, which is
     * visible and harmless. No GL context exists in a unit test, so the texture here is
     * permanently un-uploaded, which is exactly the state this guards.
     */
    @Test
    fun `an un-uploaded emitter falls back to BLANK rather than to a handle the shader would discard`()
    {
        assertEquals(
            Texture.BLANK, LightEmitter.emitter(),
            "before the upload lands, drawLight must be given BLANK — an INVALID handle is not the shader's " +
            "NO_TEXTURE sentinel and would discard every light instead of drawing a square one"
        )
    }

    /**
     * A sanity check on the sampling grid rather than on the profile: texel CENTRES, so the disc
     * is centred on the texture instead of half a texel off. Compares the four texels that
     * surround the centre, which are equidistant from it only if the sampling is centre-based.
     */
    @Test
    fun `the profile is sampled at texel centres, so the disc is centred on the texture`()
    {
        val n = LightEmitter.TEXELS
        val pixels = LightEmitter.buildPixels()
        fun alphaOf(x: Int, y: Int) = pixels.get((y * n + x) * 4 + 3).toInt() and 0xFF

        // The emitting disc must be symmetric about both axes of the texture.
        for (y in 0 until n)
            for (x in 0 until n)
                assertEquals(
                    alphaOf(x, y), alphaOf(n - 1 - x, n - 1 - y),
                    "texel ($x,$y) and its mirror disagree, so the disc is off-centre by half a texel"
                )

        // And it must actually be a disc rather than a filled square: the emitting region reaches
        // the middle of every edge but not the corners, which is the entire difference between
        // the two shapes.
        assertEquals(255, alphaOf(n / 2, n / 2), "the centre texel must be full")
        assertTrue(alphaOf(n / 2, 0) >= 128, "the emitter does not reach the middle of its own top edge, so it is smaller than the quad DiveLighting asked for")
        assertTrue(alphaOf(0, n / 2) >= 128, "the emitter does not reach the middle of its own left edge")
        assertTrue(alphaOf(0, 0) < 128, "the corner texel emits (alpha ${alphaOf(0, 0)}), so the emitter is still a square")
        assertTrue(alphaOf(n - 1, 0) < 128, "the opposite corner emits too")
    }
}
