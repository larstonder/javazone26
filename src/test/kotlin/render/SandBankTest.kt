package render

import dive.Tuning
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What is provable without a framebuffer: the ASSET DECLARATION (where the engine has three
 * separate ways to fail in silence), the BAKE'S GEOMETRY (copied into `SandBank` and able to
 * go stale), and the NORMAL MAP'S ENCODING (a swizzle, a sign and an sRGB decode, every one of
 * which is invisible in every other test and in every still frame). Whether the seabed LOOKS
 * like sand needed a real window grab — see the design's §9.6.
 *
 * EVERY ALPHA THRESHOLD IN THIS FILE IS `alpha > 16` EXCEPT the reflectance case, which uses
 * the bake's `alpha > 127`. The two are deliberately different because they answer different
 * questions: what the SILHOUETTE is, versus what the bake's GI gate actually examined.
 *
 * `SandBank`'s `Texture` fields are constructed when the object initialises. That is safe
 * without a GL context: `Texture.<init>` assigns eight fields and opens no file (verified from
 * bytecode, and already relied on by `DiverSpriteTest` and `RockFaceTest`).
 */
class SandBankTest
{
    private val diffusePng = File("src/main/resources/backdrop/sandbank-diffuse.png")
    private val normalPng = File("src/main/resources/backdrop/sandbank-normal.png")

    /** The threshold the rest of this project measures silhouettes with. */
    private val ALPHA_THRESHOLD = 16

    /**
     * THREE SILENT TRAPS IN ONE DECLARATION.
     *
     * `maxMipLevels = 0` allocates no texture storage at all: `TextureArray` computes
     * `min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and hands it to
     * `glTexStorage3D` as `levels`, where 0 is `GL_INVALID_VALUE` — no storage, every later
     * upload failing, no exception and no log line. The constructor's own DEFAULT is worse: 5,
     * paired with a `LINEAR_MIPMAP` filter, so simply not passing it gives the sand a mip chain.
     *
     * And matching [DiverSprite] is not tidiness, it is the VRAM budget:
     * `TextureBank.getOrCreateTextureArrayFor` reuses an array only when format, filter,
     * wrapping AND maxMipLevels all agree. Differ in any one and these two textures allocate a
     * second 2048x2048x4x15 = 251.7 MB array of their own instead of taking free layers.
     */
    @Test
    fun `the sandbank is declared with the parameters that keep it in the diver's texture arrays`()
    {
        listOf("diffuse" to SandBank.diffuse, "normal" to SandBank.normal).forEach { (name, texture) ->
            assertEquals(1, texture.maxMipLevels, "sandbank $name maxMipLevels must be 1 — 0 allocates no storage at all, and the constructor's default of 5 gives the sand a mip chain")
            assertEquals(TextureFilter.LINEAR, texture.filter, "sandbank $name must not use a MIPMAP filter with one mip level")
            assertEquals(TextureWrapping.CLAMP_TO_EDGE, texture.wrapping, "sandbank $name must clamp; the quad draws at uTiling = vTiling = 1 and never repeats")
        }

        assertEquals(TextureFormat.SRGBA8, SandBank.diffuse.format, "the albedo is sRGB-encoded and the GPU must linearize it on sample")
        assertEquals(TextureFormat.RGBA8, SandBank.normal.format, "the bake already decoded the normals to linear; SRGBA8 would linearize them a second time")

        assertEquals(DiverSprite.diffuse.filter, SandBank.diffuse.filter, "a different filter means a second 251.7 MB texture array")
        assertEquals(DiverSprite.diffuse.wrapping, SandBank.diffuse.wrapping, "a different wrapping means a second 251.7 MB texture array")
        assertEquals(DiverSprite.diffuse.maxMipLevels, SandBank.diffuse.maxMipLevels, "a different mip count means a second 251.7 MB texture array")
        assertEquals(DiverSprite.diffuse.format, SandBank.diffuse.format, "the albedo shares the diver's SRGBA8 array")
        assertEquals(DiverSprite.normal.format, SandBank.normal.format, "the normals share the diver's RGBA8 array")
    }

    /**
     * `maxMipLevels` IS 1, NEVER 0 — asserted on its own, separately from the declaration case
     * above, because it is the trap with no symptom at all. `glTexStorage3D` with `levels = 0`
     * is `GL_INVALID_VALUE`: no storage is allocated, every later `glTexSubImage3D` fails too,
     * and nothing raises and nothing logs. `1` is the value that means "one level, no mips".
     */
    @Test
    fun `the sandbank declares exactly one mip level`()
    {
        assertEquals(1, SandBank.diffuse.maxMipLevels, "maxMipLevels = 0 allocates no texture storage at all, silently")
        assertEquals(1, SandBank.normal.maxMipLevels, "maxMipLevels = 0 allocates no texture storage at all, silently")
    }

    /**
     * The filename rule. `Extensions.kt:446-448`'s auto-loader keys on `_normal` with an
     * UNDERSCORE and, when it matches, forces RGBA8 with TEN mip levels regardless of the
     * declaration above — and mip generation would average the dune band into the flat sand.
     */
    @Test
    fun `neither committed filename contains an underscore before normal`()
    {
        listOf(SandBank.diffuse, SandBank.normal).forEach {
            assertTrue(
                !it.filePath.contains("_normal"),
                "${it.filePath} contains `_normal` with an underscore, which trips the engine's auto-loader into RGBA8 with 10 mip levels regardless of this declaration"
            )
        }
    }

    /**
     * The texel counts are the bake's contract and everything geometric is arithmetic over
     * them, so a stale count silently draws the seabed at the wrong height. Read back off the
     * committed PNGs rather than trusted.
     */
    @Test
    fun `the texel counts match the committed textures`()
    {
        val diffuse = ImageIO.read(diffusePng)
        val normal = ImageIO.read(normalPng)

        assertEquals(diffuse.width, normal.width, "the albedo and the normal map must be the same size — they are drawn as one rect submitted twice")
        assertEquals(diffuse.height, normal.height, "the albedo and the normal map must be the same size — they are drawn as one rect submitted twice")
        assertEquals(SandBank.TEXELS_WIDE, diffuse.width, "the committed texture is ${diffuse.width} texels wide, not ${SandBank.TEXELS_WIDE}")
        assertEquals(SandBank.TEXELS_TALL, diffuse.height, "the committed texture is ${diffuse.height} texels tall, not ${SandBank.TEXELS_TALL}")
    }

    /**
     * [SandBank.MEAN_CREST_TEXEL_ROW] is where the dune band's silhouette sits on average, and
     * it is what turns the placement into a crest depth. Re-derived from the committed alpha —
     * the mean over columns of each column's own first row above the threshold.
     */
    @Test
    fun `the mean crest row is re-derived from the committed diffuse's alpha`()
    {
        val image = ImageIO.read(diffusePng)
        val w = image.width
        val h = image.height
        val argb = image.getRGB(0, 0, w, h, null, 0, w)

        var sum = 0.0
        var counted = 0
        for (x in 0 until w)
        {
            for (y in 0 until h)
            {
                if ((argb[y * w + x] ushr 24) > ALPHA_THRESHOLD)
                {
                    sum += y
                    counted++
                    break
                }
            }
        }

        assertEquals(w, counted, "every column must have an opaque texel somewhere; $counted of $w did")
        val mean = (sum / counted).toFloat()
        assertEquals(
            SandBank.MEAN_CREST_TEXEL_ROW, mean, 1f,
            "the committed sandbank's mean first-opaque row is $mean, not ${SandBank.MEAN_CREST_TEXEL_ROW} — " +
            "re-baking at a different crest shape moves where the diver's fins land"
        )
    }

    /**
     * THE PROPERTY THE MISSING WRAP BORDER BUYS. `clear_wrap_border` is deliberately NOT
     * applied to the sandbank (the bake's §4.4): this is the one quad in the game whose
     * outermost columns are supposed to be opaque and land exactly on the frame edge. A stray
     * `clear_wrap_border` would put an alpha-0 column at the extreme pixel of the screen —
     * the same inverted hairline `Framing.VISIBLE_WIDTH_METRES` records being measured at
     * 16:9, 2.389 and 32:9.
     *
     * Worded against EACH COLUMN'S OWN first opaque row, not against a global one: column 0
     * starts at row 87 and column 1999 at row 83, while the sheet's global first opaque row is
     * 59 — so "opaque in every row the art covers" would be false, and false for a reason that
     * has nothing to do with the property being asserted.
     */
    @Test
    fun `both outermost texel columns are opaque from their own first opaque row to the bottom`()
    {
        val image = ImageIO.read(diffusePng)
        val h = image.height
        listOf(0, image.width - 1).forEach { x ->
            val column = image.getRGB(x, 0, 1, h, null, 0, 1)
            val first = column.indexOfFirst { (it ushr 24) > ALPHA_THRESHOLD }
            assertTrue(first >= 0, "column $x has no opaque texel at all")
            for (y in first until h)
            {
                assertTrue(
                    (column[y] ushr 24) > ALPHA_THRESHOLD,
                    "column $x is transparent at row $y, below its own first opaque row $first — " +
                    "the frame edge would show a hairline of water at the bottom of the picture"
                )
            }
        }
    }

    /**
     * THE BAKE'S OWN GATE, RE-RUN AGAINST WHAT ACTUALLY GOT COMMITTED.
     * `texture_multiply_blend.frag` REPLACES a sub-floor albedo with flat `vec3(0.02)` grey —
     * not darkens it, FLATTENS it, which is how `0f07303` got a razor-sharp seam out of a
     * colour that merely sat near the boundary.
     *
     * The mask is `alpha > 127`, NOT the `alpha > 16` every other case here uses, and that is
     * deliberate: the bake's gate is `alpha > 0.5` on the normalised float alpha
     * (`build_backdrop.py:402`), and on an 8-bit PNG 127/255 = 0.498 while 128/255 = 0.502, so
     * `> 127` is exactly the same mask. Written with the equivalence rather than as a number
     * that merely happens to match — two masks that drifted apart would let this pass on texels
     * the bake had already rejected, and the whole value of the case is that it is the bake's
     * gate re-run on the committed file.
     */
    @Test
    fun `every committed sand texel the bake gated clears the GI reflectance floor`()
    {
        val image = ImageIO.read(diffusePng)
        val w = image.width
        val h = image.height
        val argb = image.getRGB(0, 0, w, h, null, 0, w)

        var worst = Float.MAX_VALUE
        var worstIndex = -1
        for (i in argb.indices)
        {
            if ((argb[i] ushr 24) <= 127) continue      // == the bake's `alpha > 0.5` on float alpha
            val r = srgbToLinear(((argb[i] ushr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((argb[i] ushr 8) and 0xFF) / 255f)
            val b = srgbToLinear((argb[i] and 0xFF) / 255f)
            val length = sqrt(r * r + g * g + b * b)
            if (length < worst)
            {
                worst = length
                worstIndex = i
            }
        }

        assertTrue(worstIndex >= 0, "no texel cleared the bake's own alpha gate — the mask is wrong")
        assertTrue(
            worst >= DiveRenderer.GI_REFLECTANCE_FLOOR,
            "texel (${worstIndex % w}, ${worstIndex / w}) of the committed sandbank has linear length " +
            "$worst, under GI_REFLECTANCE_FLOOR ${DiveRenderer.GI_REFLECTANCE_FLOOR} — the multiply " +
            "shader will replace it with flat grey and put a hard seam across the seabed"
        )
    }

    /**
     * THE REGRESSION GUARD FOR THE SOURCE ART'S NORMAL-MAP DEFECT, and it is the only place
     * any of the three failures below can be caught: each is silent in every other test and in
     * every still frame.
     *
     *  - the swizzle dropped   -> the mean vector is Y-DOMINANT, and the seabed is lit like a wall;
     *  - the sign flipped      -> the mean y is NEGATIVE, and light runs the wrong way across every dune;
     *  - the sRGB decode dropped -> mean |v| is about 1.27 rather than 1.
     *
     * Decoded the way the SHADER decodes it: `RGBA8` is handed to it unchanged, so this is the
     * plain `(byte/255)*2 - 1` with NO second sRGB step. Each vector is normalised BEFORE
     * averaging — the design's §2 method; averaging raw components and normalising afterwards
     * weights the longer vectors more and moves the numbers in the third decimal.
     */
    @Test
    fun `the committed normals are unit length, Z-dominant and face up`()
    {
        val albedo = ImageIO.read(diffusePng)
        val image = ImageIO.read(normalPng)
        val w = image.width
        val h = image.height
        val argb = image.getRGB(0, 0, w, h, null, 0, w)
        val alpha = albedo.getRGB(0, 0, w, h, null, 0, w)

        var lengthSum = 0.0
        var xSum = 0.0
        var ySum = 0.0
        var zSum = 0.0
        var counted = 0
        for (i in argb.indices)
        {
            if ((alpha[i] ushr 24) <= ALPHA_THRESHOLD) continue
            val x = ((argb[i] ushr 16) and 0xFF) / 255.0 * 2.0 - 1.0
            val y = ((argb[i] ushr 8) and 0xFF) / 255.0 * 2.0 - 1.0
            val z = (argb[i] and 0xFF) / 255.0 * 2.0 - 1.0
            val length = sqrt(x * x + y * y + z * z)
            if (length < 1e-6) continue
            lengthSum += length
            xSum += x / length
            ySum += y / length
            zSum += z / length
            counted++
        }

        assertTrue(counted > 100_000, "only $counted opaque texels were sampled; the mask is wrong")
        val meanLength = lengthSum / counted
        val mx = xSum / counted
        val my = ySum / counted
        val mz = zSum / counted

        assertTrue(
            abs(meanLength - 1.0) <= 0.01,
            "mean |v| is $meanLength, off unit by more than the bake's own 1% tolerance — the sRGB " +
            "decode was dropped (a raw decode of this art measures 1.2744). See swizzle_yz."
        )
        assertTrue(
            abs(mz) > abs(mx) && abs(mz) > abs(my),
            "the mean normal is ($mx, $my, $mz), which is not Z-dominant — the y<->z swizzle was " +
            "dropped, and the seabed is being lit like a wall. See swizzle_yz."
        )
        assertTrue(
            my > 0.0,
            "the mean normal's y is $my. +y = up/shallower in this project (iridescence.frag:258, " +
            "PearlNormalMap.kt:174-214's capture probe), so a seabed must be positive — the sign " +
            "was flipped, and light runs the wrong way across every dune. See swizzle_yz."
        )
    }

    /** The skirt's colour must be the art's own bottom row, or the join it continues is a step. */
    @Test
    fun `the sand skirt colour matches the committed bottom row and clears the reflectance floor`()
    {
        val image = ImageIO.read(diffusePng)
        val w = image.width
        val row = image.getRGB(0, image.height - 1, w, 1, null, 0, w)

        var r = 0.0
        var g = 0.0
        var b = 0.0
        row.forEach {
            r += ((it ushr 16) and 0xFF) / 255.0
            g += ((it ushr 8) and 0xFF) / 255.0
            b += (it and 0xFF) / 255.0
        }
        r /= w; g /= w; b /= w

        assertEquals(r.toFloat(), DiveRenderer.sandSkirtColor.red, 0.02f, "the skirt must be the art's own bottom row, or the join it continues is a visible step")
        assertEquals(g.toFloat(), DiveRenderer.sandSkirtColor.green, 0.02f, "the skirt must be the art's own bottom row, or the join it continues is a visible step")
        assertEquals(b.toFloat(), DiveRenderer.sandSkirtColor.blue, 0.02f, "the skirt must be the art's own bottom row, or the join it continues is a visible step")

        val lr = srgbToLinear(DiveRenderer.sandSkirtColor.red)
        val lg = srgbToLinear(DiveRenderer.sandSkirtColor.green)
        val lb = srgbToLinear(DiveRenderer.sandSkirtColor.blue)
        assertTrue(
            sqrt(lr * lr + lg * lg + lb * lb) >= DiveRenderer.GI_REFLECTANCE_FLOOR,
            "sandSkirtColor's linear length is under GI_REFLECTANCE_FLOOR, so the multiply shader " +
            "replaces it with flat grey"
        )
    }

    /**
     * The camera reads `Framing.SEA_FLOOR_DEPTH`; the renderer derives
     * [SandBank.QUAD_BOTTOM_DEPTH] from the art. The design's §7.2 is why there are two names —
     * `DiveCamera` must never reference an object holding engine `Texture`s. THIS is the
     * assertion that keeps the camera's floor honest to the art after a re-bake.
     */
    @Test
    fun `the camera's sea floor and the art's quad bottom are the same depth`()
    {
        assertTrue(
            abs(SandBank.QUAD_BOTTOM_DEPTH - Framing.SEA_FLOOR_DEPTH) < 1e-3f,
            "the sandbank's quad now ends at ${SandBank.QUAD_BOTTOM_DEPTH} m while the camera stops " +
            "the frame at ${Framing.SEA_FLOOR_DEPTH} m. A re-bake or a re-placement moved the art; " +
            "update Framing.SEA_FLOOR_DEPTH to match, and read the design's §7.2 first — the two " +
            "constants are separate on purpose."
        )
    }

    /**
     * THE RELATIONSHIP `dive/` CANNOT EXPRESS. The owner asked for `Tuning.MAX_DEPTH` to be
     * derived from where the sand sits. It cannot be done directly — `dive/` may not import
     * `render/`, and that boundary is what makes the simulation deterministic and testable — so
     * `MAX_DEPTH` stays the literal `160f` and the derivation is enforced HERE.
     *
     * This has teeth only because the derivation runs from the ART OUTWARD:
     * `MEAN_CREST_DEPTH = QUAD_TOP_DEPTH + CREST_OFFSET_METRES`. Written the other way round —
     * `MEAN_CREST_DEPTH = Tuning.MAX_DEPTH + FIN_REACH_METRES` — this substitutes to
     * `(a + b) - b == a`, an identity no edit anywhere can falsify. Do not re-invert it.
     *
     * THE TOLERANCE IS THE LITERAL'S PRECISION, NOT SLACK. `QUAD_TOP_DEPTH` is a decimal
     * placement literal, so the chain evaluates to 164.4531236 against `160 + 4.453125` =
     * 164.453125 — a gap of 1.4e-6 m. The smallest REAL breakage is a one-texel shift in the
     * measured crest row, which moves the left-hand side by `HEIGHT_METRES / 500` = 0.04926 m:
     * fifty times this tolerance. Every other breakage moves it by metres.
     */
    @Test
    fun `the sandbank's mean crest and the diver's fins put him on the floor at MAX_DEPTH`()
    {
        assertEquals(
            Tuning.MAX_DEPTH,
            SandBank.MEAN_CREST_DEPTH - DiverSprite.FIN_REACH_METRES,
            1e-3f,
            "the sandbank art and the diver's art no longer put the fins on the mean crest at MAX_DEPTH; " +
            "either re-place SandBank.QUAD_TOP_DEPTH or change Tuning.MAX_DEPTH to match"
        )
    }

    /**
     * The skirt is UNREACHABLE with the camera clamp in place — `worldBottom` provably never
     * passes [SandBank.QUAD_BOTTOM_DEPTH] — so what is asserted is the function's contract, not
     * a behaviour on screen. It ships as insurance against a future `Framing` change, a
     * mis-computed visible depth, or the clamp being removed by someone who did not read §7.
     */
    @Test
    fun `the skirt starts at the quad's bottom edge, or at the frame's, whichever is shallower`()
    {
        assertEquals(150f, SandBank.skirtDepth(150f), 1e-4f, "a frame ending above the art must not start a skirt inside it")
        assertEquals(SandBank.QUAD_BOTTOM_DEPTH, SandBank.skirtDepth(300f), 1e-4f, "a frame reaching past the art must start the skirt at the art's bottom edge")
    }

    /**
     * THE DRAW ORDER, AND BOTH HALVES OF IT ARE LOAD-BEARING.
     *
     * After `drawBackdrop`, because the parallax silhouettes are behind everything in the water.
     * Before `drawColumnWalls`, because the walls are opaque and their ragged silhouette must sit
     * OVER the sand's ends — that is what makes the trench read as sand BETWEEN two cliffs rather
     * than as a strip laid across them. Consequently also before the diver, so he is drawn on top
     * of the seabed, which is what "he rests on the sand" means on screen.
     *
     * Asserted against the source because nothing in a headless JVM can issue a draw call.
     */
    @Test
    fun `the sandbank is drawn after the backdrop and before the walls`()
    {
        val source = File("src/main/kotlin/render/DiveRenderer.kt").readText()
        val backdrop = source.indexOf("drawBackdrop(surface, cam,")
        val sand = source.indexOf("drawSandBank(surface, cam,")
        val walls = source.indexOf("drawColumnWalls(surface, cam,")
        val diver = source.indexOf("drawDiver(surface, sim,")

        assertTrue(backdrop in 0 until sand, "drawSandBank must be called after drawBackdrop — the silhouettes are behind everything in the water")
        assertTrue(sand in 0 until walls, "drawSandBank must be called before drawColumnWalls — the opaque walls' ragged edge has to sit OVER the sand's ends")
        assertTrue(sand < diver, "drawSandBank must be called before drawDiver, or the diver is behind the seabed he is standing on")
    }

    /**
     * A NORMAL-MAPPED SPRITE IS ONE WORLD RECT SUBMITTED TWICE, and the second call's arguments
     * must be a COPIED ARGUMENT LIST rather than a second derivation. Get it wrong and the
     * lighting slides off the sand by however far the two derivations disagree — which reads as
     * bad art rather than as a bug, and which no still frame at one depth reliably shows.
     * `NormalMapRenderer.drawNormalMap` takes no uv arguments at all, so there is no
     * second-derivation route that could even be made to work.
     */
    @Test
    fun `the sandbank's normal map is submitted on the same rect as its albedo`()
    {
        val body = File("src/main/kotlin/render/DiveRenderer.kt").readText()
            .substringAfter("private fun drawSandBank(")
            .substringBefore("\n    }")

        assertTrue(
            body.contains("centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN"),
            "drawSandBank's albedo call must use the plain (centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN) tuple"
        )
        val occurrences = Regex("centreX, centreY, width, height, 0f, CENTRE_ORIGIN, CENTRE_ORIGIN")
            .findAll(body).count()
        assertEquals(
            2, occurrences,
            "the rect must appear EXACTLY twice in drawSandBank — once as albedo on `main` and once " +
            "on gi_normal_map, as a copied argument list. Found $occurrences."
        )
    }

    /** IEC 61966-2-1 sRGB -> linear, the transfer the GPU applies to an SRGBA8 sample. */
    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}
