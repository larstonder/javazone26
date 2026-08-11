package render

import dive.Tuning
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT CANNOT BE TESTED HERE, SAID PLAINLY: whether the cliff LOOKS like rock. That needed a
 * framebuffer and a pair of eyes, and it got them — see this task's report and its contact sheet.
 * What is provable without one is the ASSET DECLARATION, where the engine has three separate ways
 * to fail in silence, the BAKE'S GEOMETRY, which is copied into this file and can go stale, and
 * the TILING ARITHMETIC, which decides whether the rock is nailed to the water or slides with the
 * camera — a difference no single still frame at one depth can show.
 *
 * [RockFace]'s `Texture` fields are constructed when the object initialises. That is safe without
 * a GL context: `Texture.<init>` assigns eight fields and opens no file (verified from bytecode,
 * and already relied on by `DiverSpriteTest`).
 */
class RockFaceTest
{
    private val diffusePng = File("src/main/resources/backdrop/rock-diffuse.png")
    private val normalPng = File("src/main/resources/backdrop/rock-normal.png")

    /**
     * THREE SILENT TRAPS IN ONE DECLARATION.
     *
     * `maxMipLevels = 0` allocates no texture storage at all: `TextureArray` computes
     * `min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and hands it to
     * `glTexStorage3D` as `levels`, where 0 is `GL_INVALID_VALUE` — no storage, every later
     * upload failing, no exception and no log line. The `Texture` constructor's own DEFAULT is
     * worse than either: it is `5`, paired with a `LINEAR_MIPMAP` filter and `REPEAT` wrapping,
     * so simply not passing these arguments gives the rock a mip chain generated across the
     * never-written remainder of its array layer — contaminating the texels at `v = vMax`, which
     * is exactly the tile seam.
     *
     * And matching [DiverSprite] is not tidiness, it is the VRAM budget:
     * `TextureBank.getOrCreateTextureArrayFor` reuses an array only when format, filter, wrapping
     * AND maxMipLevels all agree. Differ in any one and these two textures allocate a second
     * 2048x2048x4x15 = 251.7 MB array of their own instead of taking free layers in the ones the
     * diver's sheets have already opened.
     */
    @Test
    fun `the rock is declared with the parameters that keep it in the diver's texture arrays`()
    {
        for ((name, texture) in listOf("diffuse" to RockFace.diffuse, "normal" to RockFace.normal))
        {
            assertEquals(1, texture.maxMipLevels, "rock $name maxMipLevels must be 1 — 0 allocates no storage, and the constructor's default of 5 mips across a partly-written array layer puts a line on the tile seam")
            assertEquals(TextureFilter.LINEAR, texture.filter, "rock $name must not use a MIPMAP filter with one mip level")
            assertEquals(TextureWrapping.CLAMP_TO_EDGE, texture.wrapping, "rock $name must clamp; the tile wraps because the bake blended it, not because the sampler repeats")
        }

        assertEquals(TextureFormat.SRGBA8, RockFace.diffuse.format, "the albedo is sRGB-encoded and the GPU must linearize it on sample")
        assertEquals(TextureFormat.RGBA8, RockFace.normal.format, "the bake already decoded the normals to linear; SRGBA8 would linearize them a second time")

        // The array-sharing requirement, stated against the assets it has to share WITH rather
        // than against a second copy of the constants.
        assertEquals(DiverSprite.diffuse.filter, RockFace.diffuse.filter, "a different filter means a second 251.7 MB texture array")
        assertEquals(DiverSprite.diffuse.wrapping, RockFace.diffuse.wrapping, "a different wrapping means a second 251.7 MB texture array")
        assertEquals(DiverSprite.diffuse.maxMipLevels, RockFace.diffuse.maxMipLevels, "a different mip count means a second 251.7 MB texture array")
        assertEquals(DiverSprite.diffuse.format, RockFace.diffuse.format, "the albedo shares the diver's SRGBA8 array")
        assertEquals(DiverSprite.normal.format, RockFace.normal.format, "the normals share the diver's RGBA8 array")
    }

    /**
     * The texel constants are copied from `tools/build_backdrop.py`, and `--rock-height` would
     * change both of them. Copied numbers going stale is silent — the texture still loads, and
     * [RockFace.TILE_WIDTH_METRES] simply comes out wrong, so the cliff is drawn stretched. Read
     * back off the committed PNG's IHDR, which lives at a fixed offset in every PNG, so this test
     * decodes nothing and depends on nothing.
     */
    @Test
    fun `the tile size matches the committed texture`()
    {
        val diffuse = pngSize(diffusePng)
        assertEquals(diffuse, pngSize(normalPng), "the albedo and the normal map must be the same size — they are drawn as one rect submitted twice")
        assertEquals(RockFace.TEXELS_WIDE, diffuse.first, "the committed texture is ${diffuse.first} texels wide, not ${RockFace.TEXELS_WIDE}")
        assertEquals(RockFace.TEXELS_TALL, diffuse.second, "the committed texture is ${diffuse.second} texels tall, not ${RockFace.TEXELS_TALL}")
    }

    /**
     * THE ONE NUMBER THE SEAM DEPENDS ON. `TextureArray.upload` writes a `width x height`
     * sub-rectangle into a `textureSize x textureSize` layer and sets `vMax = height/textureSize`;
     * a LINEAR tap just inside `vMax` reaches half a texel past it into the part of the layer
     * nothing ever writes, and on a vertically tiled quad `v -> vMax` IS the tile join. Only
     * `height == textureSize` makes `vMax` exactly 1.0.
     *
     * `textureSize` is the smallest engine bucket that fits `max(w, h)`, so this is the assertion
     * that the height is a bucket size AND is the larger side. Both halves matter: a 691x1024
     * bake would satisfy neither (1024 is a bucket, but it also fails the `> arraySize/2` reuse
     * test against the 2048 array and would open a 209.7 MB one of its own).
     */
    @Test
    fun `the tile is exactly as tall as the texture array it lands in`()
    {
        val buckets = listOf(128, 256, 512, 1024, 2048, 4096, 8192)
        val largest = maxOf(RockFace.TEXELS_WIDE, RockFace.TEXELS_TALL)
        val arraySize = buckets.first { it >= largest }

        assertEquals(RockFace.TEXELS_TALL, largest, "the height must be the larger side, or the bucket is chosen by the width")
        assertEquals(
            arraySize, RockFace.TEXELS_TALL,
            "vMax is height/arraySize = ${RockFace.TEXELS_TALL}/$arraySize, not 1.0 — the linear tap at the tile join reaches into the unwritten part of the layer"
        )
        assertTrue(
            largest > arraySize / 2,
            "TextureBank only reuses an array when max(w,h) > arraySize/2, so this would allocate a new one"
        )
    }

    /**
     * THE REFLECTANCE-FLOOR MEASUREMENT, ON THE COMMITTED FILE.
     *
     * `texture_multiply_blend.frag` replaces any linear albedo shorter than
     * [DiveRenderer.GI_REFLECTANCE_FLOOR] with flat `vec3(0.02)` grey before the light map is
     * applied. As DELIVERED, 45.3% of the rock's opaque texels are under it — the bake lifts them
     * with an ambient term, and this is what proves the lift survived the 8-bit sRGB round trip
     * into the file the game actually loads. The bake asserts the same thing, but the bake is not
     * run by `./gradlew test` and cannot be run at all from a clean clone.
     *
     * Every texel with any alpha at all, not just the opaque ones: a partly transparent edge texel
     * still contributes its colour to the blend.
     */
    @Test
    fun `every visible texel of the committed albedo clears the GI reflectance floor`()
    {
        val image = ImageIO.read(diffusePng)
        var worst = Float.MAX_VALUE
        var worstAt = ""
        var counted = 0
        for (y in 0 until image.height)
        {
            for (x in 0 until image.width)
            {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24) == 0) continue
                counted++
                val length = DiveRenderer.reflectanceLength(
                    ((argb shr 16) and 0xFF) / 255f,
                    ((argb shr 8) and 0xFF) / 255f,
                    (argb and 0xFF) / 255f
                )
                if (length < worst)
                {
                    worst = length
                    worstAt = "($x, $y)"
                }
            }
        }

        assertTrue(counted > 0, "the committed albedo has no visible texels at all")
        assertTrue(
            worst >= DiveRenderer.GI_REFLECTANCE_FLOOR,
            "the darkest of $counted visible rock texels, at $worstAt, has linear length $worst — under the GI reflectance floor of ${DiveRenderer.GI_REFLECTANCE_FLOOR}, so the shader will replace that patch of cliff with flat grey"
        )
    }

    /**
     * THE ALPHA PROFILE, READ OFF THE COMMITTED FILE.
     *
     * [RockFace.OPAQUE_TEXEL_COLUMNS] and [RockFace.ALPHA_TEXEL_COLUMNS] are what decide where the
     * flat backing may start and where the quad's inner edge goes, i.e. they are the whole of the
     * fix for the owner's *"we should show water behind the alpha of the rocks. Currently it's
     * black."* Both are properties of the ART, so a re-bake — a different `--rock-height`, or the
     * owner redrawing the cliff with a longer ragged edge — moves them, and nothing about the
     * resulting wall says so: it just quietly goes back to painting stone where the sea should be,
     * or starts drawing rock inside the play column.
     */
    @Test
    fun `the alpha profile matches the committed texture`()
    {
        val image = ImageIO.read(diffusePng)
        val alpha = Array(image.width) { x ->
            var min = 255
            var max = 0
            for (y in 0 until image.height)
            {
                val a = image.getRGB(x, y) ushr 24
                if (a < min) min = a
                if (a > max) max = a
            }
            min to max
        }

        val firstNotSolid = (0 until image.width).first { alpha[it].first < 255 }
        val pastLastVisible = (0 until image.width).last { alpha[it].second > 0 } + 1

        assertEquals(
            firstNotSolid, RockFace.OPAQUE_TEXEL_COLUMNS,
            "column $firstNotSolid is the first that is not opaque at every depth of the tile — the backing may not reach past it, or it shows through the cliff's notches instead of the water"
        )
        assertEquals(
            pastLastVisible, RockFace.ALPHA_TEXEL_COLUMNS,
            "the last texel with any alpha is in column ${pastLastVisible - 1}, so ${image.width - pastLastVisible} columns of the tile are empty"
        )
        assertTrue(
            RockFace.OPAQUE_TEXEL_COLUMNS < RockFace.ALPHA_TEXEL_COLUMNS,
            "the solid body must end before the ragged edge does"
        )
    }

    /**
     * WHERE THE ROCK LOOKS LIKE IT IS, IS WHERE THE DIVER ACTUALLY STOPS — the property
     * `drawColumnWalls` was written for, expressed against the art rather than against a comment.
     *
     * The cliff's furthest-reaching texel must land ON [Tuning.COLUMN_HALF_WIDTH], neither short
     * of it (a channel of water the stick will not enter — the failure the wall exists to prevent,
     * and what removing the opaque backing would have reintroduced) nor past it (rock drawn over
     * water the diver can swim through).
     *
     * Both halves are killed by mutation: anchoring the quad on the boundary itself, as the wall
     * did before the backing was cut back, leaves the reach 1.31 m short.
     */
    @Test
    fun `the cliff's furthest reach lands exactly where the simulation stops the diver`()
    {
        val reach = RockFace.QUAD_INNER_HALF_WIDTH +
            RockFace.TILE_WIDTH_METRES * (RockFace.TEXELS_WIDE - RockFace.ALPHA_TEXEL_COLUMNS) / RockFace.TEXELS_WIDE

        assertEquals(
            Tuning.COLUMN_HALF_WIDTH, reach, 1e-3f,
            "the cliff reaches to |x| = $reach but the diver is stopped at ${Tuning.COLUMN_HALF_WIDTH}"
        )
    }

    /**
     * NO ROCK INSIDE THE PLAY COLUMN. The quad is shifted inward by the empty margin behind the
     * ragged edge, so the shift is only safe for as long as that margin really is empty — read off
     * the committed file, not asserted from the constant that was derived from it.
     *
     * A texel here would be rock drawn over water the diver can occupy, which is the same class of
     * lie as the channel above, pointing the other way.
     */
    @Test
    fun `the inward shift draws no rock inside the play column`()
    {
        val image = ImageIO.read(diffusePng)
        val perTexel = RockFace.TILE_WIDTH_METRES / RockFace.TEXELS_WIDE
        for (x in 0 until image.width)
        {
            // The innermost tile: u = (x + 1) / width is the texel's far edge, and the quad runs
            // from QUAD_INNER_HALF_WIDTH outward, so |worldX| falls as u rises.
            val worldX = RockFace.QUAD_INNER_HALF_WIDTH + (image.width - x - 1) * perTexel
            if (worldX >= Tuning.COLUMN_HALF_WIDTH) continue
            for (y in 0 until image.height)
                assertEquals(
                    0, image.getRGB(x, y) ushr 24,
                    "texel ($x, $y) lands at |x| = $worldX, inside the ${Tuning.COLUMN_HALF_WIDTH} m column, and is not transparent"
                )
        }
    }

    /**
     * THE OWNER'S DEFECT, AS A TEST. *"We should show water behind the alpha of the rocks.
     * Currently it's black."*
     *
     * [DiveRenderer.drawColumnWalls] lays an opaque [DiveRenderer.wallColor] backing down before
     * the cliff. That backing is what the player saw through every notch — measured at 12 m as a
     * hard step from RGB (0, 3, 30.9) inside the boundary to (0, 0, 0) outside it — so it may not
     * reach any texel that is not opaque at EVERY depth of the tile. Beyond that column it is
     * provably invisible and still covers the joins between tiles and the pre-upload frames.
     *
     * Read off the file rather than off [RockFace.OPAQUE_TEXEL_COLUMNS], so that this stays a
     * statement about the art even if that constant is edited.
     */
    @Test
    fun `the flat backing never reaches a texel the water should show through`()
    {
        val image = ImageIO.read(diffusePng)
        val perTexel = RockFace.TILE_WIDTH_METRES / RockFace.TEXELS_WIDE

        // The lowest-numbered column that is not opaque at every depth is also the FURTHEST from
        // the axis, because u rises inward — so it is the only one the backing has to clear.
        val firstNotSolid = (0 until image.width).first { x ->
            (0 until image.height).any { (image.getRGB(x, it) ushr 24) != 255 }
        }
        val outerEdge = RockFace.QUAD_INNER_HALF_WIDTH + (image.width - firstNotSolid) * perTexel

        // A tenth of a millimetre of slack, because the constant and this line divide by 691 in a
        // different order and may differ in the last bit. Nothing at that scale can be seen.
        assertTrue(
            RockFace.BACKING_HALF_WIDTH >= outerEdge - 1e-4f,
            "column $firstNotSolid is not solid at every depth and reaches out to |x| = $outerEdge, but the backing starts at ${RockFace.BACKING_HALF_WIDTH} — stone would show through the cliff there instead of water"
        )
    }

    /**
     * THE TILE LATTICE IS THE WORLD'S, NOT THE CAMERA'S — the difference between rock that is
     * part of the water column and rock that creeps upward as the diver descends. Sizing the quad
     * to the visible rect is the obvious implementation and is exactly the bug: the texture's v
     * origin would then be the camera's top edge, so the cliff would scroll at its own rate.
     * Nothing about that shows in a still frame; what it shows in is a dive.
     *
     * The property that catches it is that the lattice is invariant under a whole-tile shift and
     * SHIFTS BY THE SAME AMOUNT under anything else.
     */
    @Test
    fun `the tile lattice is anchored to the world and not to the camera`()
    {
        val h = RockFace.TILE_HEIGHT_METRES
        for (worldTop in listOf(-24f, 0f, 7.3f, 40f, 99.9f, 136f))
        {
            val top = RockFace.tileTopDepth(worldTop)
            assertTrue(top <= worldTop, "the quad must start at or above the visible top, got $top for $worldTop")
            assertTrue(worldTop - top < h, "the quad must not start more than one tile above the visible top, got $top for $worldTop")
            assertTrue(abs(top / h - floor(top / h)) < 1e-4f, "$top is not on the tile lattice")

            // A whole tile of camera movement changes nothing but the offset, so the phase of the
            // rock at any given world depth is the same before and after.
            assertEquals(top + h, RockFace.tileTopDepth(worldTop + h), 1e-3f, "one tile of descent must move the lattice by exactly one tile")
            // Half a tile of camera movement moves the lattice by zero or by a whole tile, never
            // by half — which is what "the phase is a function of world depth" means.
            val half = RockFace.tileTopDepth(worldTop + h * 0.5f)
            assertTrue(abs(half - top) < 1e-3f || abs(half - top - h) < 1e-3f, "the lattice moved by ${half - top}, which is not a whole number of tiles")
        }
    }

    /** The phase argument shifts the whole lattice and does nothing else — it is what stops the two walls being an exact mirror. */
    @Test
    fun `a phase offsets the lattice by exactly that much`()
    {
        val h = RockFace.TILE_HEIGHT_METRES
        for (worldTop in listOf(-24f, 12f, 70f, 145f))
        {
            val phase = h * 0.5f
            val shifted = RockFace.tileTopDepth(worldTop, phase)
            assertTrue(abs((shifted - phase) / h - floor((shifted - phase) / h)) < 1e-4f, "$shifted is not on the phase-shifted lattice")
            assertTrue(shifted != RockFace.tileTopDepth(worldTop, 0f) || worldTop == 0f, "a half-tile phase must actually move the lattice at $worldTop")
        }
    }

    /**
     * The quad has to reach past the bottom of the frame, and not by more than it must. Too few
     * rows leaves a strip of unpainted water below the cliff; the round-up is what makes the
     * count whole, which is what `texture.frag`'s `fract(texCoord * tiling)` needs to avoid
     * cutting the last tile off mid-feature.
     */
    @Test
    fun `the tile rows cover the visible rect and stop just past it`()
    {
        val h = RockFace.TILE_HEIGHT_METRES
        for ((top, bottom) in listOf(-24f to 36f, 0f to 60f, 13.7f to 73.7f, 100f to 160f))
        {
            val rows = RockFace.tileRows(top, bottom)
            val quadTop = RockFace.tileTopDepth(top)
            assertTrue(quadTop + rows * h >= bottom, "$rows rows from $quadTop stop at ${quadTop + rows * h}, short of $bottom")
            assertTrue(quadTop + (rows - 1) * h < bottom, "$rows rows is one more than needed to reach $bottom")
        }
    }

    /** A degenerate camera rect — frame one, or mid-resize — must still ask for a well-formed quad. */
    @Test
    fun `the tile rows are never zero or negative`()
    {
        assertEquals(1, RockFace.tileRows(0f, 0f), "an empty visible rect must still be one tile")
        assertEquals(1, RockFace.tileRows(60f, 0f), "an inverted visible rect must still be one tile")
    }

    /**
     * How many tiles reach across the rock outside the column, at the aspect ratios this could
     * ship on. The visible half-width is `VISIBLE_DEPTH_METRES * aspect / 2` — [CameraRig] scales
     * on HEIGHT — so at 4:3 it is exactly [Tuning.COLUMN_HALF_WIDTH] and there is no wall at all.
     *
     * That case is the one worth pinning: it must ask for one tile rather than zero, and
     * `drawColumnWalls` must then draw nothing because the slab width is not positive. Zero tiles
     * would be a zero-width quad; a fractional count would cut the cliff off at whatever world x
     * the screen edge happens to be, which moves with the panel.
     */
    @Test
    fun `the tile columns cover the rock outside the column at every aspect ratio`()
    {
        val w = RockFace.TILE_WIDTH_METRES
        for (aspect in listOf(4f / 3f, 16f / 10f, 16f / 9f, 21f / 9f, 32f / 9f))
        {
            // The slab PLUS the inward shift, which is what `drawRockWall` actually asks for: the
            // quad starts inside the boundary now, so it has that much further to reach.
            val wall = Framing.VISIBLE_DEPTH_METRES * aspect * 0.5f - Tuning.COLUMN_HALF_WIDTH +
                RockFace.EDGE_INSET_METRES
            val columns = RockFace.tileColumns(wall)
            assertTrue(columns >= 1, "aspect $aspect asked for $columns tiles")
            assertTrue(columns * w >= wall, "$columns tiles span ${columns * w} m, short of the ${wall} m of rock at aspect $aspect")
            // Minimality only bites where there is rock to cover; at 4:3 there is none, and the
            // floor of one tile is deliberate — see the assertions below.
            if (wall > 0f)
                assertTrue((columns - 1) * w < wall, "$columns tiles is one more than needed for ${wall} m at aspect $aspect")
        }

        assertEquals(1, RockFace.tileColumns(0f), "4:3 shows no rock at all and must still ask for a well-formed quad")
        assertEquals(1, RockFace.tileColumns(-5f), "an inverted rect must not ask for a negative number of tiles")
    }

    /**
     * The tile's world shape follows the texture's, so the rock's texels stay square. Two numbers
     * that must agree are one number too many — the width is derived, and this is the guard that
     * it stays derived rather than becoming a second constant that drifts at the next re-bake.
     */
    @Test
    fun `the tile keeps the art's proportions`()
    {
        assertEquals(
            RockFace.TEXELS_WIDE.toFloat() / RockFace.TEXELS_TALL.toFloat(),
            RockFace.TILE_WIDTH_METRES / RockFace.TILE_HEIGHT_METRES,
            1e-5f,
            "the tile's world shape does not match the texture's, so the cliff is drawn stretched"
        )
    }

    /**
     * The gate that stands between an unfinished asynchronous upload and a frame in front of a
     * queue. `AssetManager.load` only appends to a queue; the handle is replaced in `onUploaded`
     * on the GL thread some frames later. A gate that answered yes early would hand the renderer
     * an INVALID handle, which `texture.frag` reads as `NO_TEXTURE` and draws as a plain
     * untextured quad — indistinguishable from the wall having no art, which is the whole failure
     * mode this project keeps rediscovering.
     *
     * Called once, not in a loop: [RockFace.ready] counts consecutive misses toward a single WARN.
     */
    @Test
    fun `the rock is not ready before the engine has uploaded it`()
    {
        assertTrue(!RockFace.ready(), "the readiness gate said yes with both handles still INVALID")
    }

    /** Width and height from the IHDR chunk, at its fixed offset. No image library, no pixels. */
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
