package render

import dive.Tuning
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureHandle
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import no.njoh.pulseengine.core.shared.utils.Logger
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The rock that bounds the play column: one vertically tiling cliff face, and the arithmetic
 * that decides how many copies of it go where.
 *
 * The art is a LEFT-hand cliff — solid stone down the first 174 of its 300 source columns, then
 * a ragged alpha edge running out to column 269. That edge is the whole point: it is the
 * silhouette the water is seen against, so it must land on the column boundary and the solid
 * body must run outward from there, off the side of the frame. See [DiveRenderer.drawColumnWalls]
 * for how the right-hand wall gets the same edge.
 *
 * ## Tiling is the engine's, not ours
 *
 * `texture.vert` carries a per-instance `tiling` vec2 and `texture.frag` resolves it as
 * `uv = texStart + texSize * fract(texCoord * texTiling)`, computing the derivatives BEFORE the
 * `fract` specifically so a tiled quad picks the right mip level at a tile edge. So one quad
 * covers the whole wall, and this file's job is only to hand it a whole number of tiles and to
 * put that quad's edges on tile boundaries IN WORLD SPACE — see [tileTopDepth]. Sizing the quad
 * to the visible rect instead would anchor the tiling phase to the CAMERA, and the rock would
 * scroll at its own rate as the diver descended, which is the one thing rock must never do.
 *
 * ## The two facts the bake decided, restated here because the numbers are copied
 *
 * **[TEXELS_TALL] is 2048 and that equals the texture array's size.** `TextureArray.upload`
 * writes a `width x height` sub-rectangle into a `textureSize x textureSize` layer and sets
 * `vMax = height / textureSize`; a LINEAR tap just inside `vMax` reaches half a texel past it,
 * into the part of the layer nothing ever writes. On a vertically tiled quad `v -> vMax` is
 * exactly the tile seam, so a shorter texture puts a hairline across the wall every tile. At
 * `height == textureSize` the tap clamps onto the tile's own last row instead, and the tile is
 * wrap-blended so that row is the first row's neighbour anyway.
 *
 * **The filter, wrapping, format-family and mip count must stay identical to [DiverSprite]'s.**
 * `TextureBank.getOrCreateTextureArrayFor` reuses an array only when format, filter, wrapping AND
 * maxMipLevels all match and `max(w, h) > arraySize / 2`. Matching means these two textures take
 * free layers in the 2048 arrays the diver's sheets have already allocated; differing in any one
 * of them allocates a second 2048x2048x4x15 = 251.7 MB array instead.
 *
 * ## Why one mip level, which is NOT the diver's reason
 *
 * The diver's sheets use `maxMipLevels = 1` because mip generation averages across cell
 * boundaries and smears adjacent frames of the loop together. A single tiling texture has no
 * cells, so that argument does not apply here and the decision was made again:
 *
 *  - **There is no minification to absorb.** The game is orthographic and [CameraRig]'s scale is
 *    a fixed `framebufferHeight / VISIBLE_DEPTH_METRES`, so the rock is drawn at a constant
 *    texel-to-pixel ratio on a given display — there is no depth or distance falloff anywhere in
 *    the projection. The texture's real content is band-limited to the 889 source rows it was
 *    baked from, over [TILE_HEIGHT_METRES] of world: 22.2 rows per metre, against 18 px/m on a
 *    1080p panel and 30 px/m at 1800p. Mip level 0 is what a mipped sampler would pick across
 *    that whole range, and below it trilinear would only blur.
 *  - **Mips would create the seam this file exists to avoid.** `TextureArray.upload` calls
 *    `glGenerateMipmap` on the whole square layer, of which this texture is a 691-wide corner.
 *    Every level past 0 averages the content with the never-written remainder, and the
 *    contaminated texels sit at `u = uMax` and `v = vMax` — the ragged inner edge, and the tile
 *    join. Buying clean mips would mean filling the layer, i.e. baking a 2048x2048 square from
 *    300x889 art.
 *  - `maxMipLevels` must be **1** and never **0**. `TextureArray` computes
 *    `min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and hands it to
 *    `glTexStorage3D` as `levels`; `levels = 0` is `GL_INVALID_VALUE`, so no storage is allocated
 *    and every later upload fails too — with no exception and no log line.
 */
object RockFace
{
    // --- The bake's contract. Copied from tools/build_backdrop.py, never retyped from memory. ---

    /**
     * One tile, in TEXELS of the committed texture. Asset dimensions, not screen pixels — nothing
     * here may become a resolution. `RockFaceTest` re-derives both from the committed PNG's IHDR
     * so a re-bake at a different `--rock-height` cannot leave them stale.
     */
    const val TEXELS_WIDE = 691
    const val TEXELS_TALL = 2048

    /**
     * How much depth one copy of the cliff spans. A DESIGN DECISION, not a measurement, and the
     * only number here that is free: 40 m puts four tiles down the 160 m column and a tile and a
     * half on screen at once ([Framing.VISIBLE_DEPTH_METRES] is 60), so the repeat is visible
     * often enough to have to be seamless and rare enough not to read as wallpaper.
     *
     * It also sets the sampling density, which is the reason not to make it much larger: the
     * texture's real content is its 889 source rows, so 40 m gives 22.2 rows per metre against
     * 18-36 screen px/m on the panels this could ship on. See the class doc's mip argument.
     */
    const val TILE_HEIGHT_METRES = 40f

    /**
     * How wide one copy is, in metres. DERIVED from the texture rather than declared, so the
     * cliff's texels stay square whatever the bake's output size turns out to be — the same
     * arrangement as [DiverSprite.widthForHeight], and for the same reason: two numbers that must
     * agree are one number too many.
     *
     * 13.49 m at the shipped 691x2048. That is just wider than the 13.33 m of rock a 16:9 display
     * shows outside the column, so the commonest aspect ratio needs exactly one tile across and
     * has no vertical join on screen at all.
     */
    const val TILE_WIDTH_METRES = TILE_HEIGHT_METRES * TEXELS_WIDE.toFloat() / TEXELS_TALL.toFloat()

    // --- The cliff's ALPHA PROFILE, and what the wall may paint behind it ----------------------
    //
    // These two numbers were not needed while the wall was backed by an opaque slab across its
    // whole width. They became load-bearing the moment that slab was cut back so the water shows
    // through the cliff's notches — see [DiveRenderer.drawColumnWalls], which is where the owner's
    // "it's black behind the rock" defect actually lived. `RockFaceTest` re-derives both from the
    // committed PNG, so a re-bake cannot leave them stale.

    /**
     * The first texel column that is NOT opaque in every single row: 396 of 691, u = 0.573.
     *
     * Everything to the left of it is solid stone at every depth of the tile, so a flat backing
     * drawn under it can never be seen. Everything to the right is the ragged edge, where the
     * backing IS seen — and where it must therefore not be drawn, because what belongs behind a
     * cliff standing in the sea is the sea.
     */
    const val OPAQUE_TEXEL_COLUMNS = 396

    /**
     * One past the last texel column holding any alpha at all: 624 of 691, u = 0.903.
     *
     * The remaining 67 columns of the tile are completely empty. Anchoring the quad's inner edge
     * on the column boundary — which is what the wall did until now — therefore left a 1.31 m
     * strip of nothing between the furthest reach of the rock and the place the simulation stops
     * the diver. That strip was invisible only because the opaque backing filled it in; take the
     * backing away and it becomes a uniform channel of water the diver cannot enter, which is
     * precisely the "stops dead in open water with no visual reason" failure the wall exists to
     * prevent. [QUAD_INNER_HALF_WIDTH] removes it.
     */
    const val ALPHA_TEXEL_COLUMNS = 624

    /**
     * How far the quad's inner edge sits INSIDE [Tuning.COLUMN_HALF_WIDTH], so that the cliff's
     * furthest-reaching texel lands exactly on the boundary the diver is stopped at. 1.31 m.
     *
     * No rock is ever drawn inside the column: [ALPHA_TEXEL_COLUMNS] is one past the last texel
     * with any alpha, so the whole of the shifted overlap is empty. What moves is only the
     * silhouette, and it moves the right way — the promontories now touch the wall the diver
     * feels, and the bays between them are water, which is what a cliff in the sea looks like.
     */
    const val EDGE_INSET_METRES = TILE_WIDTH_METRES * (TEXELS_WIDE - ALPHA_TEXEL_COLUMNS) / TEXELS_WIDE

    /** Where the cliff quad's inner edge sits, as a distance from the column's axis. */
    const val QUAD_INNER_HALF_WIDTH = Tuning.COLUMN_HALF_WIDTH - EDGE_INSET_METRES

    /**
     * How much of the innermost tile is not solid at every depth: 5.76 m, the ragged edge plus the
     * empty margin behind it.
     */
    const val RAGGED_METRES = TILE_WIDTH_METRES * (TEXELS_WIDE - OPAQUE_TEXEL_COLUMNS) / TEXELS_WIDE

    /**
     * The distance from the column's axis at which the flat backing may begin — i.e. outward of
     * every texel of the innermost tile that is not opaque.
     *
     * Outward of this the cliff is solid in every row, so the backing is provably invisible and
     * exists only to cover the joins BETWEEN tiles (a wide panel needs more than one) and the
     * frames before the texture has uploaded. Inward of it, the water drawn by
     * [DiveRenderer.drawZoneBands] is what shows through the alpha.
     */
    const val BACKING_HALF_WIDTH = QUAD_INNER_HALF_WIDTH + RAGGED_METRES

    /**
     * Albedo. `SRGBA8`: the bake writes sRGB-encoded pixels and the GPU linearizes on sample,
     * which is what the GI multiply expects. Its darkest texel clears
     * [DiveRenderer.GI_REFLECTANCE_FLOOR] by construction — see the bake, and `RockFaceTest`.
     */
    val diffuse = Texture(
        "/backdrop/rock-diffuse.png",
        "rock_diffuse",
        filter = TextureFilter.LINEAR,
        wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.SRGBA8,
        maxMipLevels = 1                    // NEVER 0 — see the class doc.
    )

    /**
     * Normals. `RGBA8` (linear) because the bake already did the sRGB->linear decode: the source
     * normals are sRGB-encoded, and decoding them raw gives mean |v| 1.2121 with 1.2% of vectors
     * within 1% of unit length, against 0.9937 and 67.8% decoded properly.
     *
     * The filename has a HYPHEN and must keep it. The engine's `loadAll` auto-loader
     * (`Extensions.kt:446-448`) keys on the substring `_normal` — an UNDERSCORE — and forces
     * `RGBA8` with TEN mip levels when it matches, which would both defeat the mip decision above
     * and move this texture into an array of its own.
     */
    val normal = Texture(
        "/backdrop/rock-normal.png",
        "rock_normal",
        filter = TextureFilter.LINEAR,
        wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.RGBA8,
        maxMipLevels = 1                    // NEVER 0 — see the class doc.
    )

    /** Queues both textures for upload. Called once, from `EnPustTil.onCreate`. */
    fun load(engine: PulseEngine)
    {
        engine.asset.load(diffuse)
        engine.asset.load(normal)
    }

    /** Ten seconds at 60 fps — see [DiverSprite.sheetsReady], which this mirrors. */
    private const val WARN_AFTER_FRAMES = 600

    private var framesWithoutTextures = 0
    private var warned = false

    /**
     * Are both textures on the GPU? `Texture.<init>` sets `handle` to `TextureHandle.INVALID` and
     * only `onUploaded` replaces it, so this is the one field that distinguishes "queued" from
     * "resident" without a GL call.
     *
     * A FUNCTION, not a property, because it has a side effect: it counts consecutive misses and
     * logs exactly one WARN once they stop being explicable by the asynchronous upload. The
     * fallback — [DiveRenderer.wallColor] with no texture over it — is the wall this replaced and
     * looks entirely deliberate, so nothing else would ever say the art had failed to load.
     */
    fun ready(): Boolean
    {
        if (diffuse.handle != TextureHandle.INVALID && normal.handle != TextureHandle.INVALID)
        {
            framesWithoutTextures = 0
            return true
        }

        framesWithoutTextures++
        if (framesWithoutTextures >= WARN_AFTER_FRAMES && !warned)
        {
            warned = true
            Logger.warn {
                "Rock face textures never uploaded — the column walls are drawing as flat slabs. " +
                "Check /backdrop/rock-diffuse.png and /backdrop/rock-normal.png are on the classpath."
            }
        }
        return false
    }

    // --- The tiling arithmetic, in world metres ------------------------------------------------
    //
    // Pure and exposed for testing, for the same reason [DiveRenderer.stripCount] is: what these
    // return decides whether the rock is nailed to the world or slides with the camera, and
    // nothing about the difference shows in a single still frame at one depth.

    /**
     * The depth of the tile boundary at or above [worldTop], offset by [phase].
     *
     * THIS IS WHAT ANCHORS THE ROCK TO THE WORLD. The quad's top edge must sit on a multiple of
     * [TILE_HEIGHT_METRES] so that the mapping from world depth to texture v is a function of the
     * depth alone. Start the quad at the visible rect instead and v becomes a function of the
     * CAMERA, so the cliff creeps upward relative to the water as the diver descends — subtle
     * per frame, unmistakable over a dive, and invisible in a screenshot.
     *
     * [phase] shifts the whole lattice. It exists so the two walls can be given different phases
     * and stop reading as an exact mirror of each other; it changes nothing else.
     */
    fun tileTopDepth(worldTop: Float, phase: Float = 0f): Float =
        floor((worldTop - phase) / TILE_HEIGHT_METRES) * TILE_HEIGHT_METRES + phase

    /**
     * How many whole tiles reach from [tileTopDepth] down past [worldBottom]. At least one, so a
     * degenerate or inverted visible rect still draws something rather than a zero-height quad.
     */
    fun tileRows(worldTop: Float, worldBottom: Float, phase: Float = 0f): Int
    {
        val span = worldBottom - tileTopDepth(worldTop, phase)
        return maxOf(1, ceil(span / TILE_HEIGHT_METRES).toInt())
    }

    /**
     * How many whole tiles are needed to cover [wallWidthMetres] of rock outside the column.
     *
     * WHOLE tiles, never a fraction: `texture.frag` tiles with `fract(texCoord * tiling)`, so a
     * fractional count cuts the cliff off mid-feature at whatever x the quad happens to end at —
     * and that x is the screen edge, which moves with the display's aspect ratio. Rounding up
     * instead spills the surplus off the side of the frame, where nothing can see it.
     *
     * At least one, so the 4:3 case — where the visible half-width is exactly
     * [Tuning.COLUMN_HALF_WIDTH] and there is no wall at all — asks for a well-formed quad that
     * then draws nothing, rather than for zero tiles.
     */
    fun tileColumns(wallWidthMetres: Float): Int =
        maxOf(1, ceil(wallWidthMetres / TILE_WIDTH_METRES).toInt())
}
