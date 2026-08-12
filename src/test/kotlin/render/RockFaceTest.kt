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
        // The MIRROR pair is in here too. It was added when the right-hand wall stopped being a
        // 180-degree rotation, and it is two more layers in the same arrays — so it has to match
        // on all four parameters or it opens a 251.7 MB array of its own for the sake of one
        // horizontally flipped cliff.
        val declared = listOf(
            "diffuse" to RockFace.diffuse,
            "normal" to RockFace.normal,
            "mirror diffuse" to RockFace.mirrorDiffuse,
            "mirror normal" to RockFace.mirrorNormal
        )
        for ((name, texture) in declared)
        {
            assertEquals(1, texture.maxMipLevels, "rock $name maxMipLevels must be 1 — 0 allocates no storage, and the constructor's default of 5 mips across a partly-written array layer puts a line on the tile seam")
            assertEquals(TextureFilter.LINEAR, texture.filter, "rock $name must not use a MIPMAP filter with one mip level")
            assertEquals(TextureWrapping.CLAMP_TO_EDGE, texture.wrapping, "rock $name must clamp; the tile wraps because the bake blended it, not because the sampler repeats")
        }

        assertEquals(TextureFormat.SRGBA8, RockFace.diffuse.format, "the albedo is sRGB-encoded and the GPU must linearize it on sample")
        assertEquals(TextureFormat.RGBA8, RockFace.normal.format, "the bake already decoded the normals to linear; SRGBA8 would linearize them a second time")
        assertEquals(RockFace.diffuse.format, RockFace.mirrorDiffuse.format, "the mirrored albedo must share the base albedo's format, or it lands in a different array")
        assertEquals(RockFace.normal.format, RockFace.mirrorNormal.format, "the mirrored normals must share the base normals' format, or they land in a different array")

        // The filename rule, on the two files that were added last and are the easiest to get
        // wrong: `Extensions.kt:446-448`'s auto-loader keys on `_normal` with an UNDERSCORE and
        // forces RGBA8 with TEN mip levels when it matches. Every rock file uses a hyphen.
        listOf(RockFace.diffuse, RockFace.normal, RockFace.mirrorDiffuse, RockFace.mirrorNormal).forEach {
            assertTrue(
                !it.filePath.contains("_normal"),
                "${it.filePath} contains `_normal` with an underscore, which trips the engine's auto-loader into RGBA8 with 10 mip levels regardless of this declaration"
            )
        }

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
     * THE ROCK IS ANCHORED TO THE WATERLINE, NOT TO THE CAMERA — the difference between rock that
     * is part of the water column and rock that creeps upward as the diver descends. Sizing the
     * quad to the visible rect is the obvious implementation and is exactly the bug: the texture's
     * v origin would then be the camera's top edge, so the cliff would scroll at its own rate.
     * Nothing about that shows in a still frame; what it shows in is a dive.
     *
     * The property that catches it is that the wall's top edge does not depend on the camera AT
     * ALL — it is a world constant — and that the row count is the only thing the camera moves.
     */
    @Test
    fun `the wall's top edge is a world constant and the camera only changes how far down it runs`()
    {
        val h = RockFace.TILE_HEIGHT_METRES
        // Several of these sit just past a tile boundary MEASURED FROM THE WALL'S TOP rather
        // than from zero — 41 m is two tiles from 1.5 m and one tile from 0 m — because that is
        // the only place the difference between the two shows up at all.
        for (worldBottom in listOf(36f, 41f, 41.4f, 60f, 73.7f, 81.6f, 160f, 196f))
        {
            val rows = RockFace.tileRows(worldBottom)
            val top = RockFace.WALL_TOP_DEPTH
            assertTrue(
                top + rows * h >= worldBottom,
                "$rows rows from $top stop at ${top + rows * h}, short of $worldBottom — a strip of unpainted water under the cliff"
            )
            assertTrue(
                top + (rows - 1) * h < worldBottom,
                "$rows rows is one more than needed to reach $worldBottom"
            )
        }
    }

    /** A degenerate camera rect — frame one, or mid-resize — must still ask for a well-formed quad. */
    @Test
    fun `the tile rows are never zero or negative`()
    {
        assertEquals(1, RockFace.tileRows(RockFace.WALL_TOP_DEPTH), "an empty visible rect must still be one tile")
        assertEquals(1, RockFace.tileRows(-100f), "an inverted visible rect must still be one tile")
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
            if (wall > 0f)
                assertTrue((columns - 1) * w < wall, "$columns tiles is one more than needed for ${wall} m at aspect $aspect")
        }

        assertEquals(1, RockFace.tileColumns(0f), "a zero-width wall must still ask for a well-formed quad")
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

    // --- THE CREST -----------------------------------------------------------------------------

    /** The same three silent traps as the wall's declaration, on four more textures. */
    @Test
    fun `the crest is declared with the parameters that keep it in the diver's texture arrays`()
    {
        val crests = listOf(
            "top diffuse" to RockFace.topDiffuse,
            "top normal" to RockFace.topNormal,
            "top mirror diffuse" to RockFace.topMirrorDiffuse,
            "top mirror normal" to RockFace.topMirrorNormal
        )
        for ((name, texture) in crests)
        {
            assertEquals(1, texture.maxMipLevels, "$name maxMipLevels must be 1 — 0 allocates no storage at all, and the constructor's default of 5 generates mips across the never-written remainder of the array layer")
            assertEquals(RockFace.diffuse.filter, texture.filter, "$name: a different filter means a second 251.7 MB texture array")
            assertEquals(RockFace.diffuse.wrapping, texture.wrapping, "$name: a different wrapping means a second 251.7 MB texture array")
        }
        assertEquals(TextureFormat.SRGBA8, RockFace.topDiffuse.format, "the crest's albedo is sRGB-encoded and the GPU must linearize it on sample")
        assertEquals(TextureFormat.SRGBA8, RockFace.topMirrorDiffuse.format, "the mirrored albedo must share the base's format")
        assertEquals(TextureFormat.RGBA8, RockFace.topNormal.format, "the bake already decoded the crest's normals to linear; SRGBA8 would linearize them twice")
        assertEquals(TextureFormat.RGBA8, RockFace.topMirrorNormal.format, "the mirrored normals must share the base's format")
    }

    /**
     * THE CREST AND THE WALL MUST HAVE THE SAME TEXEL SIZE, or the join at the waterline carries a
     * scale change. Both are drawn [RockFace.TILE_WIDTH_METRES] across, so equal baked WIDTHS is
     * the whole of it — and the crest's height then decides how tall the cliff is.
     */
    @Test
    fun `the crest is baked to the wall's width, and its height is the art's own proportion`()
    {
        val top = pngSize(File("src/main/resources/backdrop/rock-top-diffuse.png"))
        assertEquals(top, pngSize(File("src/main/resources/backdrop/rock-top-normal.png")), "the crest's albedo and normal map must be the same size — they are one rect submitted twice")
        assertEquals(RockFace.TOP_TEXELS_WIDE, top.first, "the committed crest is ${top.first} texels wide, not ${RockFace.TOP_TEXELS_WIDE}")
        assertEquals(RockFace.TOP_TEXELS_TALL, top.second, "the committed crest is ${top.second} texels tall, not ${RockFace.TOP_TEXELS_TALL}")

        assertEquals(
            RockFace.TEXELS_WIDE, RockFace.TOP_TEXELS_WIDE,
            "the crest is not baked to the wall's width, so their texels are different sizes and the join at the waterline carries a scale change"
        )
        assertEquals(
            RockFace.TOP_TEXELS_TALL.toFloat() / RockFace.TOP_TEXELS_WIDE.toFloat(),
            RockFace.TOP_HEIGHT_METRES / RockFace.TILE_WIDTH_METRES,
            1e-5f,
            "the crest's world shape does not match the texture's, so the summit is drawn stretched"
        )
    }

    /**
     * THE CREST IS ONE SUMMIT, DRAWN ONCE, AND THE CLIFF TOP CONTINUES FROM ITS SHOULDER.
     *
     * `98bbcb0` drew it with the wall's argument list, `tileColumns(wallWidth)` copies across —
     * correct for a repeating wall tile, wrong for a headland's end. It put one identical spire
     * every [RockFace.TILE_WIDTH_METRES] with open sky between them; measured on a 3456x1218
     * capture at 0 m, the rock/sky transitions down the left wall landed at world -79.2, -65.7 and
     * -52.2 m, a pitch of 13.50 m against the tile's 13.496.
     *
     * Two things have to hold for the fixed version, and neither is visible in one file alone:
     *
     *  - the crest is drawn at its own art's proportions and is exactly one tile wide, so it
     *    neither repeats nor stretches as the display gets wider;
     *  - the flat cliff top begins exactly where the sprite's outward edge ends, at exactly the
     *    depth the art is opaque to at that edge. Either being off leaves a step in the skyline —
     *    a notch of sky, or a shelf standing proud of the summit.
     *
     * [RockFace.TOP_SHOULDER_TEXEL_ROW] is re-derived from BOTH committed PNGs here rather than
     * trusted, so a re-bake or a redrawn summit fails this instead of quietly leaving that step.
     * That the base and the mirror agree is also one more independent check on the mirror.
     */
    @Test
    fun `the crest is one tile wide and the flat cliff top starts exactly at its shoulder`()
    {
        assertEquals(
            RockFace.TILE_WIDTH_METRES, RockFace.CREST_WIDTH_METRES, 1e-5f,
            "the crest is ${RockFace.CREST_WIDTH_METRES} m across against a tile's " +
            "${RockFace.TILE_WIDTH_METRES} m — it is being stretched or repeated, and repeating a " +
            "summit draws a row of identical spires one tile apart"
        )
        assertEquals(
            RockFace.CREST_WIDTH_METRES, RockFace.CREST_OUTER_HALF_WIDTH - RockFace.QUAD_INNER_HALF_WIDTH, 1e-5f,
            "the flat cliff top does not begin where the crest sprite ends, so the skyline has a " +
            "gap of sky or a doubled shoulder at the join"
        )

        val dir = "src/main/resources/backdrop/"
        val cases = listOf(
            "rock-top-diffuse.png" to 0,                            // outward edge of the LEFT art
            "rock-top-mirror-diffuse.png" to RockFace.TOP_TEXELS_WIDE - 1  // ...and of the mirror
        )
        cases.forEach { (name, column) ->
            val image = ImageIO.read(File(dir + name))
            val firstOpaqueRow = (0 until image.height).firstOrNull { y ->
                (image.getRGB(column, y) ushr 24 and 0xFF) > 128
            }
            assertEquals(
                RockFace.TOP_SHOULDER_TEXEL_ROW, firstOpaqueRow,
                "$name is opaque from row $firstOpaqueRow at its outward edge (column $column), " +
                "not ${RockFace.TOP_SHOULDER_TEXEL_ROW} — RockFace.CREST_SHOULDER_DEPTH is derived " +
                "from that row, so the flat cliff top no longer meets the summit's shoulder"
            )
        }

        assertEquals(
            RockFace.CREST_TOP_DEPTH +
                RockFace.TOP_HEIGHT_METRES * RockFace.TOP_SHOULDER_TEXEL_ROW / RockFace.TOP_TEXELS_TALL,
            RockFace.CREST_SHOULDER_DEPTH, 1e-5f,
            "the shoulder depth is not derived from the art's own profile"
        )
        assertTrue(
            RockFace.CREST_SHOULDER_DEPTH > RockFace.CREST_TOP_DEPTH &&
                RockFace.CREST_SHOULDER_DEPTH < RockFace.WALL_TOP_DEPTH,
            "the cliff top at ${RockFace.CREST_SHOULDER_DEPTH} m is not between the summit " +
            "(${RockFace.CREST_TOP_DEPTH} m) and the waterline join (${RockFace.WALL_TOP_DEPTH} m), " +
            "so it either stands above the peak it is meant to continue or is drowned by it"
        )
    }

    /**
     * THE CLIFF TOP MUST BE AS BRIGHT AS THE CLIFF, OR THE JOIN IS A LINE.
     *
     * Above the waterline, outward of the crest sprite, [DiveRenderer.drawColumnWalls] fills flat.
     * That fill abuts the tiling wall along the whole of [RockFace.WALL_TOP_DEPTH], from the
     * crest's outward edge to the side of the frame — so if the two are not the same brightness,
     * the join is a horizontal step the full width of the fill.
     *
     * They were not. The fill used `wallColor`, and the bake solves the ROCK's mean to
     * `LUMINANCE_FACTOR` times that colour — so the fill was exactly half as bright. Captured at
     * 0 m and 2.84:1, at world -75.3 m: (0, 0, 0) above the join against (1, 3, 6) below it.
     *
     * The factor is READ OUT OF THE BAKE here, not copied: `tools/build_backdrop.py` owns it, and
     * a re-tune there that did not reach `DiveRenderer` would put the seam straight back with
     * every other test still green. Same reason the texel counts are re-derived from the PNG.
     */
    @Test
    fun `the flat cliff top carries the same luminance the bake gives the rock`()
    {
        val bake = File("tools/build_backdrop.py").readText()
        val declared = Regex("""^LUMINANCE_FACTOR\s*=\s*([0-9.]+)""", RegexOption.MULTILINE)
            .find(bake)?.groupValues?.get(1)?.toFloat()

        assertTrue(declared != null, "tools/build_backdrop.py no longer declares LUMINANCE_FACTOR at the top level")
        assertEquals(
            declared, DiveRenderer.ROCK_LUMINANCE_FACTOR, 1e-4f,
            "the bake targets ${declared}x wallColor for the rock's mean luminance but DiveRenderer " +
            "believes ${DiveRenderer.ROCK_LUMINANCE_FACTOR}x — the flat cliff top and the tiling wall " +
            "meet along the whole waterline, so the difference is a horizontal seam there"
        )

        // ...and the colour really carries it, in the LINEAR space the ratio is stated in. An sRGB
        // multiply would overshoot by the ~2.4 power curve, which is the mistake `exposed` exists
        // to prevent and is invisible in a ratio of the packed bytes.
        val linear = { c: Float -> DiveRenderer.srgbToLinear(c) }
        listOf(
            "red" to (DiveRenderer.wallColor.red to DiveRenderer.headlandColor.red),
            "green" to (DiveRenderer.wallColor.green to DiveRenderer.headlandColor.green),
            "blue" to (DiveRenderer.wallColor.blue to DiveRenderer.headlandColor.blue)
        ).forEach { (name, pair) ->
            val (wall, headland) = pair
            assertEquals(
                linear(wall) * DiveRenderer.ROCK_LUMINANCE_FACTOR, linear(headland), 1e-4f,
                "the cliff top's $name is not ${DiveRenderer.ROCK_LUMINANCE_FACTOR}x the wall's in LINEAR space"
            )
        }
        assertTrue(
            DiveRenderer.headlandColor.red > DiveRenderer.wallColor.red,
            "the cliff top is no brighter than wallColor, so it is still half the rock's luminance"
        )
    }

    /**
     * ...AND THE DRAW SITE ACTUALLY USES IT. A SOURCE SCAN, for the reason
     * `SurfaceRendererOrderTest` is one: the invariant is which draw colour is in force when two
     * `fillRect` calls run, and there is no way to observe that without a GL context.
     *
     * This exists because the value test above could not fail on its own. Reverting the draw site
     * to `wallColor` — the exact regression that produced the seam — left `headlandColor` correctly
     * computed and unused, and every other case green. A colour that is right and not used is the
     * same picture as a colour that is wrong.
     */
    @Test
    fun `the headland fill is drawn in the headland colour and not the wall's`()
    {
        val code = File("src/main/kotlin/render/DiveRenderer.kt").readText()

        val setsHeadland = code.indexOf("setDrawColor(headlandColor)")
        val fillsHeadland = code.indexOf("surface.fillRect(RockFace.CREST_OUTER_HALF_WIDTH")

        assertTrue(
            setsHeadland >= 0,
            "DiveRenderer never sets headlandColor as the draw colour, so the flat cliff top is " +
            "drawn in whatever was left in force — wallColor from the backing fills, which is half " +
            "the rock's luminance and puts a seam along the whole waterline join"
        )
        assertTrue(fillsHeadland >= 0, "DiveRenderer no longer fills the headland outward of the crest — re-read this test before deleting it")
        assertTrue(
            setsHeadland < fillsHeadland,
            "headlandColor is set AFTER the headland is filled, so the fill still takes the " +
            "previous draw colour. setDrawColor is shared surface state; the call has to precede " +
            "the fill it is for"
        )

        // Nothing may reset the colour in between. `setDrawColor` is shared state and the backing
        // fills above set wallColor, so a third call slipped between these two would silently
        // restore exactly the bug.
        val between = code.substring(setsHeadland + "setDrawColor(headlandColor)".length, fillsHeadland)
        assertTrue(
            !between.contains("setDrawColor"),
            "another setDrawColor runs between headlandColor being set and the headland being " +
            "filled, so the fill does not get it: ${between.trim().lines().firstOrNull { it.contains("setDrawColor") }}"
        )
    }

    /**
     * THE JOIN IS UNDER WATER AND STAYS THERE. The crest and the tile are different crops of rock
     * whose textures do not continue into one another, so where they meet is a discontinuity —
     * and the only thing hiding it is that it sits below the deepest trough the wave can reach,
     * inside the meniscus and the near-surface haze `water.frag` draws.
     *
     * Pinned as a RELATIONSHIP to the wave rather than as a number, so that retuning the ripple
     * (which is a look decision somebody will make again) cannot silently lift the join into view.
     */
    @Test
    fun `the wall meets the crest below the deepest trough the wave can reach`()
    {
        assertEquals(
            RockFace.WALL_TOP_DEPTH, Tuning.SURFACE_DEPTH + RockFace.CREST_SUBMERGENCE_METRES, 1e-5f,
            "the wall's top edge is no longer the crest's bottom edge, so the two either overlap or leave a gap"
        )
        assertTrue(
            RockFace.WALL_TOP_DEPTH > Tuning.SURFACE_DEPTH + WaterSurface.AMPLITUDE_METRES,
            "the join at ${RockFace.WALL_TOP_DEPTH} m is above the wave's deepest trough at ${Tuning.SURFACE_DEPTH + WaterSurface.AMPLITUDE_METRES} m — a trough would expose it"
        )
        assertTrue(
            RockFace.WALL_TOP_DEPTH < WaterSurface.QUAD_BOTTOM_DEPTH,
            "the join is deeper than the near-surface haze reaches, so nothing is covering it"
        )
        // ...and the summit is above the water, or the cliff does not break the surface at all.
        assertTrue(
            RockFace.CREST_TOP_DEPTH < Tuning.SURFACE_DEPTH - WaterSurface.AMPLITUDE_METRES,
            "the crest's summit at ${RockFace.CREST_TOP_DEPTH} m never clears the highest wave crest, so the cliff never breaks the surface"
        )
        // ...and not so far above it that it can never be seen whole.
        assertTrue(
            RockFace.CREST_TOP_DEPTH > Framing.targetCameraDepth(Tuning.SURFACE_DEPTH),
            "the summit at ${RockFace.CREST_TOP_DEPTH} m is above the highest the camera's top edge ever reaches, so the crest is cut off by the frame at every depth"
        )
    }

    /**
     * THE MIRROR IS A REAL MIRROR, RE-DERIVED FROM THE COMMITTED BASE — the one property of this
     * bake that fails in a way that reads as bad art rather than as a bug.
     *
     * The right-hand crest cannot be got from a rotation (180 degrees flips a summit upside down)
     * and cannot be got from a uv swap (`drawNormalMap` takes no uv arguments, so the albedo would
     * mirror and the normals would not). So it is a second baked texture, and the thing that can
     * silently go wrong in it is the normal map: flip the image and forget to negate the x
     * component and every bump on one whole cliff is lit from the wrong side.
     *
     * Checked texel by texel against the base files, not against `tools/backdrop/mirror.py` — the
     * bake's own tests already cover the function; this covers the FILES that shipped.
     */
    @Test
    fun `each mirrored rock texture is the exact horizontal mirror of its committed base`()
    {
        // BOTH PAIRS. The crest has been baked mirrored since `98bbcb0`; the WALL joined it when
        // the 180-degree rotation was retired, and the wall is the one with something extra to
        // lose — it tiles vertically, and its wrap-blend only survives because a horizontal mirror
        // permutes each row within itself. That follows from the texel-exact check below rather
        // than needing its own case.
        listOf(
            "rock-top-diffuse.png" to "rock-top-mirror-diffuse.png",
            "rock-diffuse.png" to "rock-mirror-diffuse.png"
        ).forEach { (baseAlbedo, mirrorAlbedo) ->
            assertExactMirror(baseAlbedo, mirrorAlbedo)
        }
    }

    /** @see [each mirrored rock texture is the exact horizontal mirror of its committed base] */
    private fun assertExactMirror(baseAlbedo: String, mirrorAlbedo: String)
    {
        val dir = "src/main/resources/backdrop/"
        val baseNormalName = baseAlbedo.replace("-diffuse.png", "-normal.png")
        val mirrorNormalName = mirrorAlbedo.replace("-diffuse.png", "-normal.png")
        val baseDiffuse = ImageIO.read(File(dir + baseAlbedo))
        val mirrorDiffuse = ImageIO.read(File(dir + mirrorAlbedo))
        val baseNormal = ImageIO.read(File(dir + baseNormalName))
        val mirrorNormal = ImageIO.read(File(dir + mirrorNormalName))

        val w = baseDiffuse.width
        val h = baseDiffuse.height
        assertEquals(w to h, mirrorDiffuse.width to mirrorDiffuse.height, "$mirrorAlbedo is a different size from $baseAlbedo")
        assertEquals(w to h, mirrorNormal.width to mirrorNormal.height, "$mirrorNormalName is a different size from $baseNormalName")

        // Sampled on a coprime lattice rather than every texel: 691x1152 is 796 032 texels and
        // four getRGB calls each is slow enough to notice in a test suite. 7 and 11 share no
        // factor with either dimension, so the walk covers the whole image including both edges.
        for (y in 0 until h step 11)
        {
            for (x in 0 until w step 7)
            {
                val mx = w - 1 - x
                assertEquals(
                    baseDiffuse.getRGB(x, y), mirrorDiffuse.getRGB(mx, y),
                    "$mirrorAlbedo differs from $baseAlbedo at ($x, $y) -> ($mx, $y)"
                )

                val a = baseNormal.getRGB(x, y)
                val b = mirrorNormal.getRGB(mx, y)
                assertEquals(
                    255 - ((a shr 16) and 0xFF), (b shr 16) and 0xFF,
                    "$mirrorNormalName's x component at ($mx, $y) is not the negation of $baseNormalName's at ($x, $y) — every bump on the right-hand cliff would be lit from the wrong side"
                )
                assertEquals((a shr 8) and 0xFF, (b shr 8) and 0xFF, "the mirrored normal's y component changed at ($mx, $y); a horizontal mirror leaves the up-down slope alone")
                assertEquals(a and 0xFF, b and 0xFF, "the mirrored normal's z component changed at ($mx, $y); a horizontal mirror leaves the facing alone")
                assertEquals(a ushr 24, b ushr 24, "the mirrored normal's alpha changed at ($mx, $y)")
            }
        }
    }

    /**
     * The same reflectance-floor measurement as the wall's, on the crest — checked SEPARATELY
     * rather than assumed to follow, because it is a different crop of rock with a different
     * distribution and the bake gives it the wall's gain rather than solving for its own.
     */
    @Test
    fun `every visible texel of the committed crest clears the GI reflectance floor`()
    {
        for (name in listOf("rock-top-diffuse.png", "rock-top-mirror-diffuse.png"))
        {
            val image = ImageIO.read(File("src/main/resources/backdrop/$name"))
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
                    if (length < worst) { worst = length; worstAt = "($x, $y)" }
                }
            }
            assertTrue(counted > 0, "$name has no visible texels at all")
            assertTrue(
                worst >= DiveRenderer.GI_REFLECTANCE_FLOOR,
                "the darkest of $counted visible texels in $name, at $worstAt, has linear length $worst — under the GI reflectance floor of ${DiveRenderer.GI_REFLECTANCE_FLOOR}, so the shader will replace that patch of cliff with flat grey"
            )
        }
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
