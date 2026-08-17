package render

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * [MoteSprite] exists because [LightEmitter] is the wrong shape to DRAW with, and the two ways it
 * can silently go wrong again are the two things asserted here: the dab's visible edge drifting off
 * the inscribed circle (so `Motes.sizeMetres` stops naming the drawn diameter), and its corners
 * surviving the alpha discard (so a mote turns square — measured, on a build that lowered the
 * engine's threshold, and reverted).
 */
class MoteSpriteTest
{
    private fun alphaAtRadius(r: Float) = MoteSprite.alphaAt(r, 0f)

    @Test
    fun `the visible edge lands on the inscribed circle`()
    {
        // texture.frag keeps a texel iff its alpha >= the discard threshold, so the outermost
        // drawn texel is where alphaAt crosses it. That must be r = 1, the inscribed circle.
        assertEquals(
            MoteSprite.DISCARD_THRESHOLD.toDouble(), alphaAtRadius(1f).toDouble(), 0.005,
            "The alpha at r = 1 is what decides the dab's drawn radius. If it is not the discard " +
            "threshold, Motes.sizeMetres no longer names the diameter that is actually drawn."
        )
    }

    @Test
    fun `the corners are discarded, so the dab is round and not square`()
    {
        // A quad's corner is at r = sqrt(2). LightEmitter reaches zero only at r = 1.4 against
        // that 1.4142 — no margin — which is why lowering the engine's threshold made motes
        // render as rounded squares.
        val corner = alphaAtRadius(hypot(1f, 1f))
        assertTrue(
            corner < MoteSprite.DISCARD_THRESHOLD,
            "A corner texel has alpha $corner, at or above the ${MoteSprite.DISCARD_THRESHOLD} " +
            "discard — every mote would rasterise as a square."
        )
    }

    @Test
    fun `there is no flat core — every drawn texel is on a slope`()
    {
        // The whole point of the file. LightEmitter is flat at 1.0 out to r = 0.6; a dab with a
        // flat maximum over a third of its area reads as a plate rather than a glow.
        var previous = alphaAtRadius(0f)
        var r = 0.02f
        while (r <= 1f)
        {
            val alpha = alphaAtRadius(r)
            assertTrue(
                alpha < previous,
                "alpha is flat at $alpha between r = ${r - 0.02f} and $r — that is the flat core " +
                "this texture exists to remove."
            )
            previous = alpha
            r += 0.02f
        }
    }

    @Test
    fun `the dab is radially symmetric`()
    {
        // Direction-dependent extent is what made the old square emitter read as a rotating
        // rectangle rather than a round object (see LightEmitter's class doc).
        for (r in listOf(0.25f, 0.5f, 0.75f, 1.0f))
        {
            val onAxis = MoteSprite.alphaAt(r, 0f)
            val diagonal = MoteSprite.alphaAt(r * 0.70710678f, r * 0.70710678f)
            assertEquals(
                onAxis.toDouble(), diagonal.toDouble(), 1e-5,
                "alpha differs along the axis and the diagonal at radius $r."
            )
        }
    }

    @Test
    fun `the built pixels are flat white with the ramp in alpha`()
    {
        // The falloff must be in ALPHA, never RGB: at the rim a colour ramp reaches black, which
        // under src*a + dst*(1-a) is a dark ring on the water rather than a fade to nothing.
        val pixels = MoteSprite.buildPixels()
        val centre = ((MoteSprite.TEXELS / 2) * MoteSprite.TEXELS + MoteSprite.TEXELS / 2) * 4
        assertEquals(255.toByte(), pixels.get(centre), "centre red is not full white")
        assertEquals(255.toByte(), pixels.get(centre + 1), "centre green is not full white")
        assertEquals(255.toByte(), pixels.get(centre + 2), "centre blue is not full white")
        assertEquals(255.toByte(), pixels.get(centre + 3), "centre alpha is not the ramp's peak")

        // A corner texel: still white, but transparent enough to be discarded.
        assertEquals(255.toByte(), pixels.get(0), "corner red is not full white")
        assertTrue(
            (pixels.get(3).toInt() and 0xFF) < (MoteSprite.DISCARD_THRESHOLD * 255).toInt(),
            "corner alpha is ${pixels.get(3).toInt() and 0xFF}, which survives the discard."
        )
    }

    @Test
    fun `it is genuinely a different shape from the emitter it replaced`()
    {
        // Guards against someone "simplifying" this back into LightEmitter. The distinguishing
        // property is the core: LightEmitter is flat at 1.0 well away from the centre.
        assertEquals(
            1f, LightEmitter.alphaAt(0.5f, 0f), 1e-6f,
            "LightEmitter is no longer flat at r = 0.5 — if it has been reshaped, re-read whether " +
            "MoteSprite is still needed AND whether GI's 0.5 contour still lands on the circle."
        )
        assertTrue(
            MoteSprite.alphaAt(0.5f, 0f) < 0.95f,
            "MoteSprite has grown a flat core and is now the shape it exists to avoid."
        )
    }
}
