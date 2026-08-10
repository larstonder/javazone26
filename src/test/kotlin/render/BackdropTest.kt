package render

import dive.Tuning
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT CANNOT BE TESTED HERE: whether three dark ridges drifting past each other read as distance.
 * That needed captures, and it got them. What is provable is the asset declaration, the layer
 * table's ORDER (a far layer scrolling faster than a near one is exactly backwards, and reads as
 * the mountains swimming through each other) and the parallax arithmetic itself — none of which a
 * single still frame could show, because parallax is by definition a difference between frames.
 */
class BackdropTest
{

    /**
     * Same three silent traps as [RockFace]'s: `maxMipLevels = 0` allocates no storage at all
     * (`glTexStorage3D` with `levels = 0` is `GL_INVALID_VALUE`, no exception, no log), the
     * constructor's own defaults are `LINEAR_MIPMAP` / `REPEAT` / 5 mips, and any disagreement
     * with [DiverSprite]'s parameters means `TextureBank` cannot put these layers in the array
     * the diver's diffuse sheet already opened — three flat masks would then cost a second
     * 2048x2048x4x15 = 251.7 MB array.
     */
    @Test
    fun `the layers are declared with the parameters that keep them in the diver's texture array`()
    {
        Backdrop.layers.forEachIndexed { i, layer ->
            assertEquals(1, layer.texture.maxMipLevels, "layer $i maxMipLevels must be 1 — 0 allocates no storage, and the constructor default is 5")
            assertEquals(TextureFilter.LINEAR, layer.texture.filter, "layer $i must not use a MIPMAP filter with one mip level")
            assertEquals(TextureWrapping.CLAMP_TO_EDGE, layer.texture.wrapping, "layer $i must clamp — these are single shapes, not tiles")
            assertEquals(TextureFormat.SRGBA8, layer.texture.format, "layer $i is an sRGB-encoded mask")
            assertEquals(DiverSprite.diffuse.filter, layer.texture.filter, "a different filter means a second 251.7 MB texture array")
            assertEquals(DiverSprite.diffuse.wrapping, layer.texture.wrapping, "a different wrapping means a second 251.7 MB texture array")
            assertEquals(DiverSprite.diffuse.maxMipLevels, layer.texture.maxMipLevels, "a different mip count means a second 251.7 MB texture array")
        }
    }

    /**
     * `--silhouette-max` would change all six numbers, and a stale one is silent: the layer still
     * draws, at the wrong proportions, so a mountain range comes out squashed. Re-derived from
     * each committed PNG's IHDR.
     *
     * The size also has to stay inside `(1024, 2048]`. `TextureBank` reuses an array only when
     * `max(w, h) > arraySize / 2` and that test is STRICT, so a mask baked at exactly 1024 would
     * open a 1024x1024x4x50 = 209.7 MB array of its own — and at their source 3000 px they would
     * open a 4096 one at 671.1 MB.
     */
    @Test
    fun `each layer's declared size matches its committed PNG and stays in the 2048 bucket`()
    {
        // Keyed off each layer's own file path, NOT off its position in the table: the table runs
        // far to near, which is not the order the sources are numbered in, and pairing by index
        // would have silently compared layer 3's shape against layer 1's PNG.
        Backdrop.layers.forEachIndexed { i, layer ->
            val file = File("src/main/resources" + layer.texture.filePath)
            val (w, h) = pngSize(file)
            assertEquals(layer.texelsWide, w, "${file.name} is $w texels wide, not ${layer.texelsWide}")
            assertEquals(layer.texelsTall, h, "${file.name} is $h texels tall, not ${layer.texelsTall}")

            val largest = maxOf(w, h)
            assertTrue(largest > 1024, "${file.name}'s largest side is $largest; TextureBank's reuse test is strict, so anything at or under 1024 opens a 209.7 MB array of its own")
            assertTrue(largest <= 2048, "${file.name}'s largest side is $largest, past the 2048 bucket the diver's sheet already opened")
        }
    }

    /**
     * FAR TO NEAR, AND THAT IS THE DRAW ORDER TOO. A near layer must scroll faster than the one
     * behind it, or the depth cue inverts and the ridges appear to swim through each other. Both
     * endpoints of the rate are excluded: 0 pins a layer to the frame for the whole dive, 1 nails
     * it to the water and makes it not a parallax layer at all.
     */
    @Test
    fun `the layers run far to near, with rates strictly inside the open interval`()
    {
        assertTrue(Backdrop.layers.size >= 2, "parallax needs at least two layers to be parallax")
        Backdrop.layers.forEachIndexed { i, layer ->
            assertTrue(layer.rate > 0f && layer.rate < 1f, "layer $i has rate ${layer.rate}; 0 pins it to the frame and 1 nails it to the water")
            assertTrue(layer.alpha > 0f && layer.alpha < 1f, "layer $i has alpha ${layer.alpha}; opaque would hide the water it is meant to be seen through")
            if (i > 0)
            {
                assertTrue(layer.rate > Backdrop.layers[i - 1].rate, "layer $i scrolls no faster than the layer behind it, so the depth order is inverted")
                assertTrue(layer.alpha > Backdrop.layers[i - 1].alpha, "layer $i is no stronger than the layer behind it, so the haze order is inverted")
            }
        }
    }

    /**
     * The arithmetic that decides whether the backdrop moves at all, stated at both endpoints and
     * in between. `(1 - rate)` is the whole of it: writing `rate` there instead leaves a layer
     * pinned to the frame when it should be nailed to the water and vice versa, and both look
     * like plausible slow drift in any single capture.
     */
    @Test
    fun `a rate of one nails a layer to the water and a rate of zero nails it to the frame`()
    {
        for (cameraTop in listOf(-24f, 0f, 40f, 136f))
        {
            assertEquals(30f, Backdrop.parallaxTopDepth(cameraTop, 30f, 1f), 1e-3f, "a rate-1 layer must sit at its rest depth whatever the camera does")
            assertEquals(cameraTop + 30f, Backdrop.parallaxTopDepth(cameraTop, 30f, 0f), 1e-3f, "a rate-0 layer must keep the same screen offset whatever the camera does")
        }
    }

    @Test
    fun `a layer climbs the frame by its rate for every metre the camera descends`()
    {
        val rest = 35f
        for (rate in listOf(0.15f, 0.3f, 0.5f))
        {
            for (descent in listOf(1f, 17f, 120f))
            {
                val before = Backdrop.parallaxTopDepth(0f, rest, rate) - 0f
                val after = Backdrop.parallaxTopDepth(descent, rest, rate) - descent
                assertEquals(
                    -rate * descent, after - before, 1e-3f,
                    "a rate-$rate layer moved ${after - before} m up the frame over $descent m of descent"
                )
            }
        }
    }

    /**
     * The layers must not all move at the same speed, which is what "parallax" means and what a
     * single mistyped rate would quietly undo.
     */
    @Test
    fun `the layers separate on screen as the diver descends`()
    {
        val offsets = { cameraTop: Float ->
            Backdrop.layers.map { Backdrop.parallaxTopDepth(cameraTop, it.restTopDepth, it.rate) - cameraTop }
        }
        val start = offsets(-24f)
        val deep = offsets(136f)
        for (i in 1 until Backdrop.layers.size)
        {
            val opened = (deep[i] - deep[i - 1]) - (start[i] - start[i - 1])
            assertTrue(
                kotlin.math.abs(opened) > 1f,
                "layers ${i - 1} and $i stayed within $opened m of the same separation over a whole dive — they are moving together"
            )
        }
    }

    /**
     * The skirt continues each silhouette's solid body to the bottom of the frame, because the
     * quads are finite and climb out of view as the diver descends. It must never overlap the art
     * — the two are drawn at the same colour and alpha, and alpha compositing is not idempotent,
     * so an overlap would darken a band exactly where the join is meant to be invisible — and
     * never leave a gap, which would be the horizontal line it exists to remove.
     */
    @Test
    fun `the skirt begins exactly where the art ends, and never above the frame's bottom`()
    {
        for (top in listOf(-40f, 0f, 33f, 150f))
        {
            for (height in listOf(26.7f, 80f))
            {
                for (bottom in listOf(36f, 96f, 196f))
                {
                    val skirt = Backdrop.skirtDepth(top, height, bottom)
                    assertTrue(skirt <= bottom, "the skirt would start at $skirt, below the frame's bottom $bottom")
                    assertTrue(skirt <= top + height, "the skirt would start at $skirt, overlapping art that runs to ${top + height}")
                    if (top + height < bottom)
                        assertEquals(top + height, skirt, 1e-3f, "the skirt must take over exactly where the quad stops")
                    else
                        assertEquals(bottom, skirt, 1e-3f, "the quad already reaches the bottom of the frame, so there is nothing to skirt")
                }
            }
        }
    }

    /**
     * The one horizontal span in this game that does not depend on the display's aspect ratio.
     * The walls are drawn after the backdrop and are opaque, so nothing outside the column can be
     * seen; sizing to the visible rect instead would stretch the mountains wider on a wider panel,
     * which is a change of art rather than of framing.
     */
    @Test
    fun `the layers are exactly as wide as the play column`()
    {
        assertEquals(2f * Tuning.COLUMN_HALF_WIDTH, Backdrop.widthMetres, 1e-3f, "the backdrop must span the column, no more and no less")
    }

    /** The layer's world height follows its shape, so a re-bake at another size cannot squash it. */
    @Test
    fun `each layer's world height follows its own proportions`()
    {
        Backdrop.layers.forEachIndexed { i, layer ->
            val height = layer.heightMetres(Backdrop.widthMetres)
            assertEquals(
                layer.texelsTall.toFloat() / layer.texelsWide.toFloat(),
                height / Backdrop.widthMetres,
                1e-4f,
                "layer $i is drawn at a different shape from the mask it is drawn with"
            )
        }
    }

    /**
     * All-or-nothing, so a partial upload can never show a near ridge with no far one behind it —
     * and false until the engine has actually uploaded, for the reason `RockFaceTest`'s equivalent
     * gives at length. Called once: [Backdrop.ready] counts misses toward a single WARN.
     */
    @Test
    fun `no layer is ready before the engine has uploaded it`()
    {
        assertTrue(!Backdrop.ready(), "the readiness gate said yes with every handle still INVALID")
    }

    private fun pngSize(file: File): Pair<Int, Int>
    {
        assertTrue(file.exists(), "${file.path} is missing — it is a committed artifact, see tools/build_backdrop.py")
        val bytes = file.readBytes()
        fun int(at: Int) = (bytes[at].toInt() and 0xFF shl 24) or
                           (bytes[at + 1].toInt() and 0xFF shl 16) or
                           (bytes[at + 2].toInt() and 0xFF shl 8) or
                           (bytes[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }
}
