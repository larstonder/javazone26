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
    private val bodyDiffusePng = File("src/main/resources/backdrop/rock-body-diffuse.png")
    private val bodyNormalPng = File("src/main/resources/backdrop/rock-body-normal.png")

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

        // FROM THE BORDER, not from column 0: the bake forces the first
        // RockFace.BORDER_TEXEL_COLUMNS transparent so a quad edge's extrapolated `fract`
        // wrap lands on nothing. See that constant.
        val firstNotSolid = (RockFace.BORDER_TEXEL_COLUMNS until image.width).first { alpha[it].first < 255 }
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
        val firstNotSolid = (RockFace.BORDER_TEXEL_COLUMNS until image.width).first { x ->
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
     * # THE PRIMARY TEST OF THIS WHOLE STRUCTURE, AND IT IS A SWEEP RATHER THAN A SPOT CHECK
     *
     * The owner: *"The rocks should ALWAYS be exactly starting at the edge of the screen.
     * ALWAYS."* This is that sentence, as arithmetic.
     *
     * FIVE COMMITS IN A ROW SHIPPED A VERSION THAT PASSED A FIVE-ASPECT-RATIO TEST AND STILL LEFT
     * A HOLE. The reason is that the wall art is not uniform across a tile, so with the old
     * structure what landed at the frame edge was a function of
     * `(visibleHalfWidth − anchor) mod TILE_WIDTH_METRES` — and 43% of that period is empty or
     * ragged. Measured on that code, at the extreme frame-edge column:
     *
     *     4:3  no rock at all | 16:10 solid | 16:9 EMPTY | 21:9 RAGGED | 32:9 EMPTY
     *
     * Naming five aspect ratios samples that modulus five times. Moving the anchor by any amount
     * moves which five samples you get, so every attempt could be "verified" and every attempt was
     * wrong somewhere else. A 1 cm sweep from the innermost half-width the body has to cover out
     * to 200 m samples it 15 558 times, which is what makes it a proof rather than a coincidence:
     * it would have failed on every one of those five commits.
     *
     * The named ratios are kept as well — they are the shapes a booth panel might actually be, and
     * naming them keeps the failure message legible — plus 1.594 and 1.732, which the owner hit by
     * dragging a window and which are the two the previous versions were retuned against.
     */
    @Test
    fun `solid rock reaches the frame edge at every visible half-width`()
    {
        // From the edge art's own solid run outward: inward of BACKING_HALF_WIDTH the art is
        // ragged ON PURPOSE — that is the silhouette — and the body does not reach there.
        var halfWidth = RockFace.BACKING_HALF_WIDTH
        var steps = 0
        while (halfWidth <= 200f)
        {
            val coverage = RockFace.coverageOuterHalfWidth(halfWidth)
            assertTrue(
                coverage >= halfWidth,
                "a display showing $halfWidth m of half-width gets solid rock only out to " +
                "$coverage m, so its outermost ${halfWidth - coverage} m is water or sky"
            )
            halfWidth += 0.01f
            steps++
        }
        assertTrue(steps > 15_000, "the sweep only took $steps steps — it is not sweeping anything")

        // ...and the same statement at the shapes a panel is actually sold in. VISIBLE_DEPTH_METRES
        // * aspect / 2 is the visible half-width, because CameraRig scales on HEIGHT.
        listOf(4f / 3f, 16f / 10f, 1.594f, 1.732f, 16f / 9f, 21f / 9f, 32f / 9f).forEach { aspect ->
            val h = Framing.VISIBLE_DEPTH_METRES * aspect * 0.5f
            val coverage = RockFace.coverageOuterHalfWidth(h)
            assertTrue(
                coverage >= h,
                "at aspect $aspect the frame edge is at $h m and solid rock stops at $coverage m"
            )
        }
    }

    /**
     * THE 4:3 DEGENERACY, STATED RATHER THAN LEFT TO FALL OUT.
     *
     * At 4:3 the visible half-width is EXACTLY [Tuning.COLUMN_HALF_WIDTH]: the frame edge and the
     * place the simulation stops the diver are the same world line, so there is no room for rock
     * at all and no arrangement of textures can put any there. Every capture of the dev window
     * before it moved to 16:9 showed that, and it was read as "the walls are broken" more than
     * once — see `application-dev.cfg`, which moved the window for exactly this reason.
     *
     * Pinned as an equality between the two constants so that changing either one surfaces it. If
     * [Framing.VISIBLE_DEPTH_METRES] or [Tuning.COLUMN_HALF_WIDTH] moves, 4:3 stops being the
     * degenerate case and some other ratio becomes it, and whoever makes that change should be
     * told rather than left to find out at a booth.
     */
    @Test
    fun `at 4 by 3 the frame edge is exactly where the diver is stopped`()
    {
        assertEquals(
            Tuning.COLUMN_HALF_WIDTH,
            Framing.VISIBLE_DEPTH_METRES * (4f / 3f) * 0.5f,
            1e-4f,
            "4:3 is no longer the aspect ratio at which the play column exactly fills the frame, " +
            "so the degenerate case — where no rock can be drawn outside the column because there " +
            "is no frame outside the column — has moved to some other ratio"
        )
    }

    /**
     * How many BODY tiles reach across the frame outward of the anchor. Whole tiles, rounded UP:
     * `texture.frag` tiles with `fract(texCoord * tiling)`, so a fractional count would cut the
     * texture off mid-feature at whatever world x the screen edge happens to be — which moves with
     * the panel. Rounding up spills the surplus off the side, where nothing can see it.
     *
     * ZERO, NOT ONE, when there is no frame left over. The body is opaque at every texel, so a
     * count floored at one would paint a solid rectangle across the crest's silhouette and into
     * the play column at 16:10 and anything narrower — which is the defect `e7a638e` fixed, and
     * the reason this differs from the `tileColumns` it replaced.
     */
    @Test
    fun `the body asks for whole tiles, and for none at all when the frame stops short`()
    {
        val w = RockFace.BODY_TILE_WIDTH_METRES
        listOf(0.01f, 1f, w - 0.01f, w, w + 0.01f, 17.83f, 54.5f, 130f).forEach { span ->
            val columns = RockFace.bodyColumns(span)
            assertTrue(columns * w >= span, "$columns tiles span ${columns * w} m, short of $span m")
            assertTrue((columns - 1) * w < span, "$columns tiles is one more than needed for $span m")
        }

        assertEquals(0, RockFace.bodyColumns(0f), "a frame that stops exactly on the anchor needs no body at all")
        assertEquals(0, RockFace.bodyColumns(-4.17f), "16:10 stops 4.17 m short of the anchor and must draw no body")
        assertEquals(0, RockFace.bodyColumns(Float.NaN), "a NaN visible rect must not ask for a quad")
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

    // --- THE BODY: the rock that actually reaches the frame edge --------------------------------

    /**
     * WHERE THE BODY STARTS, AND WHY IT IS THE ONLY PLACE IT CAN START.
     *
     * Two conditions bracket it, and they are both structural rather than aesthetic:
     *
     *  - it may not start INWARD of [RockFace.BACKING_HALF_WIDTH], because inward of there the
     *    edge art is ragged and the body is opaque — an opaque quad over the silhouette is the
     *    silhouette gone;
     *  - it may not start OUTWARD of [RockFace.CREST_OUTER_HALF_WIDTH], because outward of there
     *    the edge art has stopped and a gap between the two is a strip of water at a fixed world
     *    position down both sides of the frame.
     *
     * The body's own inner edge is where those two must meet, and one texel inward of the edge
     * tile's outward end is the unique choice that makes the meeting an exact reflection — see
     * [RockFace.BODY_INNER_HALF_WIDTH], which has the derivation.
     */
    @Test
    fun `the body's inner edge lies inside the edge art's solid run`()
    {
        assertTrue(
            RockFace.BODY_INNER_HALF_WIDTH >= RockFace.BACKING_HALF_WIDTH,
            "the body starts at ${RockFace.BODY_INNER_HALF_WIDTH} m, inward of the edge art's " +
            "solid run at ${RockFace.BACKING_HALF_WIDTH} m — opaque rock would be drawn over the " +
            "cliff's ragged silhouette, which is the one thing the edge art is for"
        )
        assertTrue(
            RockFace.BODY_INNER_HALF_WIDTH <= RockFace.CREST_OUTER_HALF_WIDTH,
            "the body starts at ${RockFace.BODY_INNER_HALF_WIDTH} m, outward of the edge tile's " +
            "outward end at ${RockFace.CREST_OUTER_HALF_WIDTH} m — the gap between them is a " +
            "strip of water down both sides of every frame"
        )
    }

    /**
     * ONE TEXEL, EXACTLY — the anchor that makes the join a reflection rather than a step.
     *
     * The edge tile's outward-most texel column is 0, which the bake forces transparent
     * ([RockFace.BORDER_TEXEL_COLUMNS]); the first column carrying art is column 1. The body is
     * its own horizontal mirror and its outermost column IS the wall's column 1, so anchoring one
     * texel in puts column 1 immediately inward of the line and its mirror immediately outward.
     *
     * Half a metre inward (the `CLIFF_TOP_OVERLAP_METRES` fudge this replaced) would put 25 texels
     * of opaque body over the edge art instead, and at the waterline that overlap is over the
     * CREST — i.e. an opaque rectangle behind the summit's notches, which is `e7a638e`'s defect.
     */
    @Test
    fun `the body's inner edge is exactly one texel inward of the edge tile's outward end`()
    {
        assertEquals(
            RockFace.TEXEL_WIDTH_METRES,
            RockFace.CREST_OUTER_HALF_WIDTH - RockFace.BODY_INNER_HALF_WIDTH,
            1e-6f,
            "the body overlaps the edge tile by " +
            "${RockFace.CREST_OUTER_HALF_WIDTH - RockFace.BODY_INNER_HALF_WIDTH} m against one " +
            "texel's ${RockFace.TEXEL_WIDTH_METRES} m — anything more is opaque rock over the " +
            "crest's silhouette, anything less is a transparent seam at a fixed world position"
        )
        assertEquals(
            RockFace.TILE_WIDTH_METRES / RockFace.TEXELS_WIDE, RockFace.TEXEL_WIDTH_METRES, 1e-9f,
            "TEXEL_WIDTH_METRES is not the wall's own texel any more"
        )
    }

    /**
     * THE BODY IS A CROP OF THE WALL AND MUST KEEP ITS TEXEL SIZE. The two are drawn edge to edge
     * along one world line, so a scale change there is a visible discontinuity in a surface that
     * is otherwise continuous rock — the same failure the crest's equal-width bake avoids at the
     * waterline.
     *
     * Both the declared width and the committed file are checked, because the constant is derived
     * from the wall's alpha profile and the file is produced by `crop_body`: they agree today, and
     * a re-bake that changed one without the other would leave the body stretched with every other
     * test green.
     */
    @Test
    fun `the body keeps the wall's texel size`()
    {
        val body = pngSize(bodyDiffusePng)
        assertEquals(body, pngSize(bodyNormalPng), "the body's albedo and normal map must be the same size — they are one rect submitted twice")
        assertEquals(RockFace.BODY_TEXELS_WIDE, body.first, "the committed body is ${body.first} texels wide, not ${RockFace.BODY_TEXELS_WIDE}")
        assertEquals(RockFace.TEXELS_TALL, body.second, "the body is ${body.second} rows tall against the wall's ${RockFace.TEXELS_TALL} — it no longer shares the wall's v mapping, so the two stop being the same rock at the join")

        assertEquals(
            2 * (RockFace.OPAQUE_TEXEL_COLUMNS - RockFace.BORDER_TEXEL_COLUMNS), RockFace.BODY_TEXELS_WIDE,
            "the body is not the wall's opaque run mirror-doubled"
        )
        assertEquals(
            RockFace.TILE_WIDTH_METRES / RockFace.TEXELS_WIDE,
            RockFace.BODY_TILE_WIDTH_METRES / RockFace.BODY_TEXELS_WIDE,
            1e-9f,
            "one body texel is ${RockFace.BODY_TILE_WIDTH_METRES / RockFace.BODY_TEXELS_WIDE} m " +
            "against one wall texel's ${RockFace.TILE_WIDTH_METRES / RockFace.TEXELS_WIDE} m, so " +
            "the rock changes scale where the two meet"
        )
    }

    /**
     * EVERY TEXEL OF THE BODY IS ALPHA 255. This is the half of the guarantee a pure sweep cannot
     * make: [RockFace.coverageOuterHalfWidth] proves the body's QUAD reaches the frame edge, and
     * this proves that what the quad rasterises there is rock rather than a hole.
     *
     * It is read off the committed file rather than trusted to the bake, for the reason every
     * other file-reading case here exists: the bake is not run by `./gradlew test` and cannot be
     * run at all from a clean clone, so the file is the only artifact both the game and this suite
     * actually share.
     */
    @Test
    fun `every texel of the committed body is opaque`()
    {
        listOf(bodyDiffusePng, bodyNormalPng).forEach { file ->
            val image = ImageIO.read(file)
            val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val hole = argb.indexOfFirst { (it ushr 24) != 255 }
            assertEquals(
                -1, hole,
                "texel (${hole % image.width}, ${hole / image.width}) of ${file.name} has alpha " +
                "${argb.getOrElse(hole) { 0 } ushr 24}, so the rock the frame edge is guaranteed " +
                "to be made of has a transparent texel in it"
            )
        }
    }

    /**
     * THE BODY IS ITS OWN HORIZONTAL MIRROR, WHICH IS WHAT LETS ONE PAIR SERVE BOTH SIDES.
     *
     * `crop_body` emits `[C | mirror(C)]`. Two things follow and both are load-bearing:
     *
     *  - the two cliffs are exact mirror images of one another even though they are drawn from the
     *    same texture, because the quad grows outward on both sides so the left samples `1 − u`
     *    where the right samples `u`;
     *  - the tile join within one side is a REFLECTION rather than a step, which is the only way
     *    to join this art to itself: it has no horizontal wrap period (the bake measures the best
     *    candidate at 22.1/255 against an interior adjacency of 1.26/255, a ratio of 17.5x).
     *
     * The normal map's x component is negated by the mirror, which in eight bits is exactly
     * `255 − r`. Forget that and every bump on one half of every tile is lit from the wrong side —
     * which reads as bad art rather than as a bug, and is why this is checked on the file that
     * shipped and not only in the bake's own tests.
     */
    @Test
    fun `the body is its own horizontal mirror`()
    {
        val albedo = ImageIO.read(bodyDiffusePng)
        val normal = ImageIO.read(bodyNormalPng)
        val w = albedo.width
        val h = albedo.height
        val a = albedo.getRGB(0, 0, w, h, null, 0, w)
        val n = normal.getRGB(0, 0, w, h, null, 0, w)

        for (y in 0 until h step 7)
        {
            for (x in 0 until w)
            {
                val mx = w - 1 - x
                assertEquals(
                    a[y * w + x], a[y * w + mx],
                    "the body's albedo at ($x, $y) is not its own mirror at ($mx, $y), so one " +
                    "texture cannot serve both sides of the column and its tile join is a step"
                )

                val here = n[y * w + x]
                val there = n[y * w + mx]
                assertEquals(
                    255 - ((here shr 16) and 0xFF), (there shr 16) and 0xFF,
                    "the body's normal x at ($mx, $y) is not the negation of its own at ($x, $y) — every bump on one half of every tile is lit from the wrong side"
                )
                assertEquals((here shr 8) and 0xFF, (there shr 8) and 0xFF, "the mirrored normal's y component differs at ($mx, $y); a horizontal mirror leaves the up-down slope alone")
                assertEquals(here and 0xFF, there and 0xFF, "the mirrored normal's z component differs at ($mx, $y); a horizontal mirror leaves the facing alone")
            }
        }
    }

    /**
     * THE JOIN WITH THE EDGE ART IS A REFLECTION, TEXEL FOR TEXEL.
     *
     * The body is anchored one texel inward of the edge tile's outward end, so the texel landing
     * against that world line is the body's OUTERMOST column — and this asserts that column is a
     * copy of the wall's column [RockFace.BORDER_TEXEL_COLUMNS], which is what sits immediately
     * inward of the same line. A crop that started anywhere else (at column 0, or at
     * [RockFace.BACKING_HALF_WIDTH]'s column 396) would put unrelated art on the two sides of that
     * line, and the art has no horizontal wrap period to make that continuous.
     *
     * Checked on the base texture, which is the LEFT wall; the right is its baked mirror and the
     * body is its own mirror, so the same statement holds there by the two mirror tests.
     */
    @Test
    fun `the body's outermost column is the wall's first column of art`()
    {
        val wallAlbedo = ImageIO.read(diffusePng)
        val wallNormal = ImageIO.read(normalPng)
        val bodyAlbedo = ImageIO.read(bodyDiffusePng)
        val bodyNormal = ImageIO.read(bodyNormalPng)

        assertEquals(wallAlbedo.height, bodyAlbedo.height, "the body is a different height from the wall it is cropped from")
        for (y in 0 until wallAlbedo.height)
        {
            assertEquals(
                wallAlbedo.getRGB(RockFace.BORDER_TEXEL_COLUMNS, y), bodyAlbedo.getRGB(0, y),
                "the body's outermost albedo column differs from the wall's column " +
                "${RockFace.BORDER_TEXEL_COLUMNS} at row $y, so the join between the edge art and " +
                "the body is a discontinuity instead of a reflection"
            )
            assertEquals(
                wallNormal.getRGB(RockFace.BORDER_TEXEL_COLUMNS, y), bodyNormal.getRGB(0, y),
                "the body's outermost normal column differs from the wall's at row $y"
            )
        }
    }

    /** The same three silent traps as the wall's declaration, on the two body textures. */
    @Test
    fun `the body is declared with the parameters that keep it in the diver's texture arrays`()
    {
        listOf("body diffuse" to RockFace.bodyDiffuse, "body normal" to RockFace.bodyNormal)
            .forEach { (name, texture) ->
                assertEquals(1, texture.maxMipLevels, "$name maxMipLevels must be 1 — 0 allocates no storage at all, and the constructor's default of 5 generates mips across the never-written remainder of the array layer")
                assertEquals(RockFace.diffuse.filter, texture.filter, "$name: a different filter means a second 251.7 MB texture array")
                assertEquals(RockFace.diffuse.wrapping, texture.wrapping, "$name: a different wrapping means a second 251.7 MB texture array")
                assertTrue(
                    !texture.filePath.contains("_normal"),
                    "${texture.filePath} contains `_normal` with an underscore, which trips the engine's auto-loader into RGBA8 with 10 mip levels regardless of this declaration"
                )
            }
        assertEquals(TextureFormat.SRGBA8, RockFace.bodyDiffuse.format, "the body's albedo is sRGB-encoded and the GPU must linearize it on sample")
        assertEquals(TextureFormat.RGBA8, RockFace.bodyNormal.format, "the bake already decoded the body's normals to linear; SRGBA8 would linearize them twice")

        // ...and it lands in the 2048 bucket, which is the one the diver's sheets already forced
        // into existence. TextureBank reuses an array only when max(w, h) > arraySize / 2, so a
        // 790-wide texture is only free because it is 2048 TALL.
        assertTrue(
            maxOf(RockFace.BODY_TEXELS_WIDE, RockFace.TEXELS_TALL) > 2048 / 2,
            "the body's largest side is ${maxOf(RockFace.BODY_TEXELS_WIDE, RockFace.TEXELS_TALL)}, " +
            "which fails TextureBank's `> arraySize / 2` reuse test against the 2048 array and " +
            "allocates a 251.7 MB array of its own"
        )
    }

    /**
     * The same reflectance-floor measurement as the wall's and the crest's — checked on the body
     * SEPARATELY because it is what the frame edge is made of. A patch of body under the floor is
     * replaced by flat `vec3(0.02)` grey by `texture_multiply_blend.frag` before the light map is
     * applied, so the edge of the screen would stop being rock in the only way that matters:
     * visually.
     */
    @Test
    fun `every texel of the committed body clears the GI reflectance floor`()
    {
        val image = ImageIO.read(bodyDiffusePng)
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        var worst = Float.MAX_VALUE
        var worstAt = -1
        argb.forEachIndexed { index, value ->
            val length = DiveRenderer.reflectanceLength(
                ((value shr 16) and 0xFF) / 255f,
                ((value shr 8) and 0xFF) / 255f,
                (value and 0xFF) / 255f
            )
            if (length < worst) { worst = length; worstAt = index }
        }
        assertTrue(
            worst >= DiveRenderer.GI_REFLECTANCE_FLOOR,
            "the darkest of ${argb.size} body texels, at (${worstAt % image.width}, ${worstAt / image.width}), " +
            "has linear length $worst — under the GI reflectance floor of ${DiveRenderer.GI_REFLECTANCE_FLOOR}, " +
            "so the shader replaces that patch of the frame edge with flat grey"
        )
    }

    /**
     * THE BODY CARRIES THE WALL'S OWN LIFT, NOT A SECOND ONE SOLVED FOR ITS OWN MEAN.
     *
     * `bake_rock` solves a gain so the wall's mean luminance lands on
     * [DiveRenderer.ROCK_LUMINANCE_FACTOR] times `wallColor`'s. `crop_body` takes its columns out
     * of THAT array rather than re-running the lift on the source, so the body's mean is exactly
     * the mean of the wall columns it was cut from. If a re-bake ever solved the body's own gain,
     * the two would differ — and they are drawn edge to edge along one world line, so the
     * difference would be a vertical brightness step down both sides of every frame. That is the
     * same class of defect as the flat cliff top being half the rock's luminance (`41849a7`).
     *
     * Stated against the WALL'S OWN CROP rather than against the factor, because the crop is the
     * solid interior and is legitimately a little darker than the whole tile (0.0644 against
     * 0.0682) — asserting the factor would be asserting something that is not true of the body and
     * would have to be given a tolerance wide enough to hide a real re-gain.
     */
    @Test
    fun `the body carries the wall's baked mean luminance`()
    {
        val wall = ImageIO.read(diffusePng)
        val wallArgb = wall.getRGB(0, 0, wall.width, wall.height, null, 0, wall.width)
        var wallSum = 0.0
        var wallCount = 0
        for (y in 0 until wall.height)
        {
            for (x in RockFace.BORDER_TEXEL_COLUMNS until RockFace.OPAQUE_TEXEL_COLUMNS)
            {
                wallSum += relativeLuminance(wallArgb[y * wall.width + x])
                wallCount++
            }
        }

        val body = ImageIO.read(bodyDiffusePng)
        val bodyArgb = body.getRGB(0, 0, body.width, body.height, null, 0, body.width)
        val bodyMean = bodyArgb.sumOf { relativeLuminance(it) } / bodyArgb.size

        assertEquals(
            wallSum / wallCount, bodyMean, 1e-9,
            "the body's mean luminance is $bodyMean against ${wallSum / wallCount} over the wall " +
            "columns it is cropped from — it has been given a gain of its own, so there is a " +
            "brightness step where the two meet"
        )
    }

    /** The relative luminance of a packed ARGB texel, in the LINEAR space the GI blend measures. */
    private fun relativeLuminance(argb: Int): Double
    {
        val r = DiveRenderer.srgbToLinear(((argb shr 16) and 0xFF) / 255f)
        val g = DiveRenderer.srgbToLinear(((argb shr 8) and 0xFF) / 255f)
        val b = DiveRenderer.srgbToLinear((argb and 0xFF) / 255f)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
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
     *  - the cliff BODY begins exactly one texel inward of where the sprite's outward edge ends,
     *    at exactly the depth the art is opaque to at that edge. Either being off leaves a step in
     *    the skyline — a notch of sky, or a shelf standing proud of the summit.
     *
     * [RockFace.TOP_SHOULDER_TEXEL_ROW] is re-derived from BOTH committed PNGs here rather than
     * trusted, so a re-bake or a redrawn summit fails this instead of quietly leaving that step.
     * That the base and the mirror agree is also one more independent check on the mirror.
     */
    @Test
    fun `the crest is one tile wide and the cliff body starts exactly at its shoulder`()
    {
        assertEquals(
            RockFace.TILE_WIDTH_METRES, RockFace.CREST_WIDTH_METRES, 1e-5f,
            "the crest is ${RockFace.CREST_WIDTH_METRES} m across against a tile's " +
            "${RockFace.TILE_WIDTH_METRES} m — it is being stretched or repeated, and repeating a " +
            "summit draws a row of identical spires one tile apart"
        )
        assertEquals(
            RockFace.CREST_WIDTH_METRES, RockFace.CREST_OUTER_HALF_WIDTH - RockFace.QUAD_INNER_HALF_WIDTH, 1e-5f,
            "the cliff body's anchor is not one tile outward of the edge art's, so the skyline " +
            "has a gap of sky or a doubled shoulder at the join"
        )

        val dir = "src/main/resources/backdrop/"
        // JUST INSIDE THE WRAP BORDER on each side. The bake clears column 0 of the base (and
        // therefore column 690 of the mirror), so the outward-most column that still carries the
        // summit's profile is one texel in. See RockFace.BORDER_TEXEL_COLUMNS.
        val cases = listOf(
            "rock-top-diffuse.png" to RockFace.BORDER_TEXEL_COLUMNS,
            "rock-top-mirror-diffuse.png" to RockFace.TOP_TEXELS_WIDE - 1 - RockFace.BORDER_TEXEL_COLUMNS
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
     * A FLAT SLAB STANDING IN FOR ROCK MUST BE AS BRIGHT AS THE ROCK, OR THE JOIN IS A LINE.
     *
     * [DiveRenderer.drawFlatWalls] is the degradation when the textures have not uploaded, and it
     * fills the two slabs in two colours split at [RockFace.WALL_TOP_DEPTH]. The above-waterline
     * half stands in for the cliff top against the sunset, so if it is not the brightness the bake
     * gives the rock, the join at the waterline is a horizontal step the full width of the slab.
     *
     * They were not. The fill used `wallColor`, and the bake solves the ROCK's mean to
     * `LUMINANCE_FACTOR` times that colour — so the fill was exactly half as bright. Captured at
     * 0 m and 2.84:1, at world -75.3 m: (0, 0, 0) above the join against (1, 3, 6) below it. That
     * was measured on the LIVE path, which is now textured rock at every texel; the constant and
     * this test survive because the fallback makes exactly the same claim.
     *
     * The factor is READ OUT OF THE BAKE here, not copied: `tools/build_backdrop.py` owns it, and
     * a re-tune there that did not reach `DiveRenderer` would put the seam straight back with
     * every other test still green. Same reason the texel counts are re-derived from the PNG.
     */
    @Test
    fun `the flat slab carries the same luminance the bake gives the rock`()
    {
        val bake = File("tools/build_backdrop.py").readText()
        val declared = Regex("""^LUMINANCE_FACTOR\s*=\s*([0-9.]+)""", RegexOption.MULTILINE)
            .find(bake)?.groupValues?.get(1)?.toFloat()

        assertTrue(declared != null, "tools/build_backdrop.py no longer declares LUMINANCE_FACTOR at the top level")
        assertEquals(
            declared, DiveRenderer.ROCK_LUMINANCE_FACTOR, 1e-4f,
            "the bake targets ${declared}x wallColor for the rock's mean luminance but DiveRenderer " +
            "believes ${DiveRenderer.ROCK_LUMINANCE_FACTOR}x — the two flat slabs meet along the " +
            "whole waterline, so the difference is a horizontal seam there"
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
                "the above-waterline slab's $name is not ${DiveRenderer.ROCK_LUMINANCE_FACTOR}x the wall's in LINEAR space"
            )
        }
        assertTrue(
            DiveRenderer.headlandColor.red > DiveRenderer.wallColor.red,
            "the above-waterline slab is no brighter than wallColor, so it is still half the rock's luminance"
        )
    }

    /**
     * ...AND THE DEGRADED WALL ACTUALLY USES IT. A SOURCE SCAN, for the reason
     * `SurfaceRendererOrderTest` is one: the invariant is which draw colour is in force when two
     * `fillRect` calls run, and there is no way to observe that without a GL context.
     *
     * This exists because the value test above could not fail on its own. Reverting the draw site
     * to `wallColor` — the exact regression that produced the seam — left `headlandColor` correctly
     * computed and unused, and every other case green. A colour that is right and not used is the
     * same picture as a colour that is wrong.
     *
     * THE DRAW SITE MOVED. `headlandColor` used to back the textured cliff-top band on the live
     * path; the body texture is opaque at every texel, so there is nothing left for it to fill in
     * up there and the live fill is gone. It survives in [DiveRenderer.drawFlatWalls] — the
     * degradation when the textures have not uploaded — where the same argument applies unchanged:
     * the above-waterline slab stands in for rock the bake makes twice as bright as `wallColor`,
     * so filling the whole height in `wallColor` puts a horizontal step at the waterline.
     *
     * Scoped to `drawFlatWalls`'s OWN body. An `indexOf` over the whole file would find the
     * backing's `setDrawColor(wallColor)` instead and pass on a gutted fallback.
     */
    @Test
    fun `the degraded wall fills above the waterline in the headland colour and not the wall's`()
    {
        val body = functionBody("private fun drawFlatWalls")

        val setsHeadland = body.indexOf("setDrawColor(headlandColor)")
        val fillsHeadland = body.indexOf("surface.fillRect(worldLeft, worldTop")

        assertTrue(
            setsHeadland >= 0,
            "drawFlatWalls never sets headlandColor as the draw colour, so the above-waterline " +
            "slab is drawn in whatever was left in force — and wallColor is half the luminance " +
            "the bake gives the rock, which is a horizontal step at the waterline"
        )
        assertTrue(fillsHeadland >= 0, "drawFlatWalls no longer fills above the waterline — re-read this test before deleting it")
        assertTrue(
            setsHeadland < fillsHeadland,
            "headlandColor is set AFTER the above-waterline slab is filled, so the fill still " +
            "takes the previous draw colour. setDrawColor is shared surface state; the call has " +
            "to precede the fill it is for"
        )

        // Nothing may reset the colour in between: a third call slipped between these two would
        // silently restore exactly the bug.
        val between = body.substring(setsHeadland + "setDrawColor(headlandColor)".length, fillsHeadland)
        assertTrue(
            !between.contains("setDrawColor"),
            "another setDrawColor runs between headlandColor being set and the slab being " +
            "filled, so the fill does not get it: ${between.trim().lines().firstOrNull { it.contains("setDrawColor") }}"
        )

        // ...and the slab BELOW the waterline still takes wallColor, which is what it was tuned
        // against. One colour for the whole height is the defect, whichever of the two it is.
        assertTrue(
            body.indexOf("setDrawColor(wallColor)") > setsHeadland,
            "drawFlatWalls no longer fills below the waterline in wallColor"
        )
    }

    /**
     * THE CLIFF TOP IS ROCK, NOT A COLOUR — and the band keeps the rock's proportions as the
     * camera drops.
     *
     * `d7921a6` filled above the waterline flat, and `41849a7` then matched that flat colour to
     * the rock's mean luminance. Neither could work: what makes the cliff read as rock up there is
     * the NORMAL MAP's relief catching the light, and flat geometry has none to catch it with. The
     * owner, on a crop of the right-hand cliff: *"look at the sky seeping through, and us filling
     * with a dark color after"*. Measured over the cliff-top band, the flat fill's per-channel
     * standard deviation was (0.00, 0.37, 0.48) — zero variation in red, because it was one
     * colour — against (0.26, 0.84, 1.89) for the wall texture now drawn there.
     *
     * The band is CLIPPED to the visible rect, so its height changes every time the camera moves.
     * `vTiling` therefore has to be that height in tiles: a fixed `1f` squashes a whole 40 m tile
     * into whatever sliver is on screen, and the rock stretches as the diver descends. That is a
     * distortion in MOTION, which no still frame catches — hence a test rather than a capture.
     *
     * [DiveRenderer.headlandColor] survives as the backing behind the texture, filling the
     * transparent slit at every tile join exactly as the backing does below the waterline, which
     * is why the tests above still stand.
     */
    @Test
    fun `the cliff is drawn as rock on both sides, and its tiling keeps the texels square at any band height`()
    {
        val walls = functionBody("private fun drawColumnWalls")

        // BOTH SIDES, each named with the half-width it is passed. Dropping one side leaves the
        // other, and a whole-file `contains` would not notice — which it did not, the first time
        // this was written. The property survived the restructure because it is a property of the
        // COLUMN having two sides, not of what is drawn on them.
        //
        // Four calls, because the body is drawn as two quads and not one tall one: a single quad
        // on the v lattice would have to start at WALL_TOP_DEPTH - 40 m and would paint 17 m of
        // opaque rock across the sky. See drawColumnWalls.
        listOf(
            "leftHalfWidth, aboveTop, RockFace.WALL_TOP_DEPTH, LEFT_SIDE",
            "rightHalfWidth, aboveTop, RockFace.WALL_TOP_DEPTH, RIGHT_SIDE",
            "leftHalfWidth, RockFace.WALL_TOP_DEPTH, wallBottom, LEFT_SIDE",
            "rightHalfWidth, RockFace.WALL_TOP_DEPTH, wallBottom, RIGHT_SIDE"
        ).forEach {
            assertTrue(
                walls.contains("drawRockBody(surface, normalMaps, $it)"),
                "drawRockBody is not called with ($it), so that band of that side's cliff has no " +
                "rock reaching the frame edge at all"
            )
        }
        listOf("side = LEFT_SIDE", "side = RIGHT_SIDE").forEach {
            assertTrue(
                walls.contains("drawRockEdge(surface, normalMaps, worldBottom, $it)"),
                "drawRockEdge is not called with ($it), so that side of the column has no ragged " +
                "silhouette where the diver is stopped"
            )
        }

        // AND IN THAT ORDER. The edge art has alpha notches, and what shows through them must be
        // the water and the sky rather than the body — so the body goes down first. The crest is
        // last because it caps the wall it is drawn over.
        assertTrue(
            walls.indexOf("drawRockBody(") < walls.indexOf("drawRockEdge(") &&
                walls.indexOf("drawRockEdge(") < walls.indexOf("drawCrest("),
            "the wall is not drawn body, then edge, then crest — the cliff's notches would show " +
            "opaque body instead of water, or the crest would be drawn under the wall it caps"
        )

        val body = functionBody("private fun drawRockBody")
        assertTrue(
            body.contains("drawNormalMap"),
            "drawRockBody submits no normal map, so the rock at the frame edge is lit flat — which " +
            "is the whole of what made the flat fill wrong"
        )
        assertTrue(
            body.contains("bodyVerticalTiles("),
            "drawRockBody no longer derives its vTiling from the band's height, so the rock " +
            "stretches as the camera moves"
        )

        // uTiling IS THE TILE COUNT, on BOTH maps. Pinning it to 1 leaves the quad the right
        // width — so the frame edge is still covered and the arithmetic sweep still passes — and
        // stretches one 15.43 m tile across up to 60 m of frame instead. That is invisible to
        // every pure test here, which is exactly why it is asserted at the draw site; and it has
        // to be the same on both maps, because drawNormalMap takes no uv arguments at all and a
        // normal map tiled differently from its albedo lights every facet from the wrong place.
        assertEquals(
            2, Regex("""columns\.toFloat\(\), rows""").findAll(body).count(),
            "drawRockBody does not pass uTiling = columns to BOTH the albedo and the normal map, " +
            "so the body's tiles are stretched or its lighting does not match its art"
        )

        // The above-waterline band's height varies with the camera, so the tiling has to vary with
        // it. Every band must show exactly its own height of tile, at the tile's own scale.
        listOf(2f, 7.5f, RockFace.TILE_HEIGHT_METRES, 20.16f, 39.9f).forEach { height ->
            assertEquals(
                height,
                DiveRenderer.bodyVerticalTiles(height) * RockFace.TILE_HEIGHT_METRES,
                1e-3f,
                "a ${height} m band of cliff is drawn showing " +
                "${DiveRenderer.bodyVerticalTiles(height)} tiles, i.e. " +
                "${DiveRenderer.bodyVerticalTiles(height) * RockFace.TILE_HEIGHT_METRES} m of rock — " +
                "the texels are stretched, and they stretch differently at every camera depth"
            )
        }
    }

    /**
     * THE EDGE IS DRAWN ONCE ACROSS, AND THAT IS THE FIX. A SOURCE SCAN, because the number that
     * matters is a `uTiling` argument and no pure function can observe it.
     *
     * `drawRockEdge` used to ask for `tileColumns(wallWidth + EDGE_INSET_METRES)` tiles — as many
     * as it took to reach the frame edge. That is what made the frame edge a function of
     * `(visibleHalfWidth − anchor) mod TILE_WIDTH_METRES`, and 43% of that period is empty or
     * ragged, so at 16:9 the outermost pixel column of the screen had no rock in it at all.
     *
     * Putting a horizontal count back here is the single most likely way for this defect to
     * return, because it looks like the obvious way to make the cliff reach further. It is not:
     * [DiveRenderer.drawRockBody] is what reaches, and this must stay exactly one tile wide so its
     * non-solid region is one fixed world interval at every aspect ratio.
     */
    @Test
    fun `the edge art is one tile across and is never tiled horizontally`()
    {
        val edge = functionBody("private fun drawRockEdge")

        assertTrue(
            edge.contains("val width = RockFace.TILE_WIDTH_METRES"),
            "drawRockEdge's quad is no longer exactly one tile wide, so which part of the art " +
            "lands at the frame edge depends on the display's aspect ratio again"
        )
        assertTrue(
            !edge.contains("olumns"),
            "drawRockEdge counts tiles across again — that is the modulus this whole structure " +
            "exists to remove: ${edge.lines().firstOrNull { it.contains("olumns") }?.trim()}"
        )
        // uTiling is the second-to-last argument of both draws, and it is the literal 1f.
        assertEquals(
            2, Regex("""1f, rows\.toFloat\(\)""").findAll(edge).count(),
            "drawRockEdge does not pass uTiling = 1 to BOTH the albedo and the normal map — a " +
            "normal map tiled differently from the albedo lights every facet from the wrong place"
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

    /**
     * The source of ONE function of `DiveRenderer`, from its declaration to the next one.
     *
     * Every source scan here is scoped through this rather than run over the whole file, because
     * an unscoped `indexOf` finds another function's identical lines and passes on a gutted
     * target — which has happened twice in this file's history: a whole-file `contains` for the
     * cliff-top draw matched only one of the two sides, and a scan for `drawNormalMap` matched the
     * wall's copy of the call while the band's had been deleted.
     */
    private fun functionBody(declaration: String): String
    {
        val code = File("src/main/kotlin/render/DiveRenderer.kt").readText()
        val start = code.indexOf(declaration)
        assertTrue(start >= 0, "DiveRenderer no longer declares `$declaration` — re-read the test that asked for it before deleting it")
        // Its own closing brace, which is the first one at FOUR spaces of indent: every block
        // inside a method closes at eight or more. Ending at "the next `private fun` instead"
        // swept up the following method's KDoc, and a doc that mentions a call is not a call.
        val end = code.indexOf("\n    }\n", start + declaration.length)
        assertTrue(end > start, "`$declaration`'s body has no closing brace at four spaces of indent; this helper cannot find its end")
        return code.substring(start, end)
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
