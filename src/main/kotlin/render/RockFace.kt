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

/**
 * The rock that bounds the play column: one vertically tiling cliff face, and the arithmetic
 * that decides how many copies of it go where.
 *
 * The art is a LEFT-hand cliff — solid stone down the first 396 of its 691 baked columns, then a
 * ragged alpha edge running out to column 624. That edge is the whole point: it is the
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
 * anchor that quad's top edge on a fixed WORLD depth — [WALL_TOP_DEPTH], just under the waterline,
 * where [DiveRenderer.drawCrest] caps it. Sizing the quad to the visible rect instead would anchor
 * the tiling phase to the CAMERA, and the rock would scroll at its own rate as the diver
 * descended, which is the one thing rock must never do. See [tileRows] for the history.
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

    /**
     * THE WALL'S HORIZONTAL MIRROR, for the right-hand side of the column — and it is BAKED,
     * where the crest's already was.
     *
     * `DiveRenderer` used to get the right wall by drawing this same texture rotated 180 degrees,
     * on the sound argument that a rotation is the one transform the engine applies to the
     * geometry and to the normal VECTORS together (`normal_map.vert` builds
     * `normalRotation = rotMatrix(rotation + cameraAngle)`), which a uv swap and a negative width
     * both fail to do.
     *
     * What that missed is that 180 degrees is a horizontal mirror AND A VERTICAL FLIP. The crest
     * could never use it — a summit upside down is not a summit — so the crest was baked mirrored
     * while the wall was rotated, and the two sides of the column ended up transformed
     * DIFFERENTLY: on the right, an upside-down wall ran up to a right-way-up summit. The owner,
     * on a capture: *"they should be placed from top to bottom to ensure the seams line up"*.
     *
     * Both now come from the bake, and [DiveRenderer.drawRockWall] draws both sides at angle 0.
     * The cost is two more layers in the 2048 arrays the diver's sheets already allocate — which
     * is why these go through [rockTexture] and inherit exactly the same filter, wrapping and
     * `maxMipLevels` as everything else here (see the class doc: differ in any one of them and
     * this allocates a second 251.7 MB array).
     *
     * A horizontal mirror moves whole rows, so the vertical wrap-blend the bake solves for is
     * preserved exactly — `RockFaceTest` asserts both that and the texel-exact mirror.
     */
    val mirrorDiffuse = rockTexture("rock-mirror-diffuse.png", "rock_mirror_diffuse", TextureFormat.SRGBA8)
    val mirrorNormal = rockTexture("rock-mirror-normal.png", "rock_mirror_normal", TextureFormat.RGBA8)

    // --- THE CREST: what stops the wall at the waterline ---------------------------------------
    //
    // The tile has no top. It repeats down the column for ever and, before this, ran straight off
    // the top of the frame — so above the waterline the cliff was just more cliff, and the owner
    // said so: *"we still don't use the top rock to stop the rock faces at the top"*. The crest
    // sprite is what gives the column an end, and it is the silhouette the sky sits behind.
    //
    // Baked to the SAME WIDTH as the wall (both 691 texels, both drawn [TILE_WIDTH_METRES]
    // across), so the two have identical texel sizes and the join carries no scale change. Its
    // HEIGHT is not chosen — it falls out of the art's 300x500 proportions, and it is therefore
    // how tall the cliff turns out to be. See `tools/build_backdrop.py`'s `bake_rock_top`, which
    // also explains why it shares the wall's gain rather than solving for its own mean.

    /** The crest, in TEXELS. `RockFaceTest` re-derives both from the committed PNG's IHDR. */
    const val TOP_TEXELS_WIDE = 691
    const val TOP_TEXELS_TALL = 1152

    /**
     * How tall the crest is in metres: whatever keeps its texels square against the wall's.
     * 22.49 m at the shipped 691x1152 — which, against the 24 m of sky the camera can show when
     * the diver is at the surface, puts the summit just inside the top of the frame.
     *
     * DERIVED, not declared, for the reason [TILE_WIDTH_METRES] is: two numbers that must agree
     * are one number too many, and a re-bake at a different size would otherwise stretch the crest.
     */
    const val TOP_HEIGHT_METRES = TILE_WIDTH_METRES * TOP_TEXELS_TALL / TOP_TEXELS_WIDE

    /**
     * How far BELOW [Tuning.SURFACE_DEPTH] the crest's bottom edge — and therefore the wall's top
     * edge — sits.
     *
     * A LOOK DECISION, picked by capture. Positive means the join is under water, which is the
     * whole point: the crest and the tile are different crops of rock and their textures do not
     * continue into one another, so the join is a discontinuity that has to be hidden. 1.5 m puts
     * it below the deepest trough the wave can reach ([WaterSurface.AMPLITUDE_METRES] is 0.31 m)
     * and inside the meniscus and the near-surface haze `water.frag` draws, where nothing can be
     * read as a line. The cliff then breaks the surface rather than being cut off by it, which is
     * what the mockup shows.
     *
     * `RockFaceTest` pins the relationship to the wave rather than the number, so retuning the
     * ripple cannot silently expose the join.
     */
    const val CREST_SUBMERGENCE_METRES = 1.5f

    /** The depth the crest's bottom edge and the wall's top edge both sit at. */
    const val WALL_TOP_DEPTH = Tuning.SURFACE_DEPTH + CREST_SUBMERGENCE_METRES

    /** The depth of the crest's top edge — the summit. */
    const val CREST_TOP_DEPTH = WALL_TOP_DEPTH - TOP_HEIGHT_METRES

    /**
     * # THE CREST IS ONE SUMMIT AND IT MUST BE DRAWN EXACTLY ONCE
     *
     * `98bbcb0` drew it with the wall's own argument list, `tileColumns(wallWidth)` copies across.
     * That is right for the wall, whose art is a repeating tile, and wrong for this, whose art is
     * a HEADLAND'S END: solid rock at its outward edge, falling away to a ragged inner edge and
     * then to nothing by texel column 513 of 691. Repeating it produces one identical spire per
     * [TILE_WIDTH_METRES] with open sky between them — a picket fence, not a cliff.
     *
     * MEASURED on a 3456x1218 capture at 0 m, which shows four copies per side. The rock/sky
     * transitions down the left wall land at world x = -79.2, -65.7 and -52.2 m: a pitch of
     * exactly 13.50 m, against [TILE_WIDTH_METRES] = 13.496. Nothing else in the frame has that
     * period. It is worse the wider the display, and the 4:3 dev window hid it almost entirely
     * (the whole wall is off-frame there), which is how it shipped.
     *
     * So the crest is drawn ONCE, [CREST_WIDTH_METRES] across, anchored on the same inner edge the
     * wall's innermost tile uses. Outward of that, the cliff top is filled flat to
     * [CREST_SHOULDER_DEPTH] — see [DiveRenderer.drawColumnWalls]. Flat is honest here rather than
     * lazy: above the waterline the cliff is a SILHOUETTE against the sunset. Measured over the
     * crest's own rock in that capture, mean RGB (2.3, 1.3, 5.8) against a sky of (50, 10, 37) —
     * the texture up there is already invisible, so the fill cannot be told from it.
     */
    const val CREST_WIDTH_METRES = TILE_WIDTH_METRES

    /** Where the crest sprite's outward edge sits, and therefore where the flat cliff top begins. */
    const val CREST_OUTER_HALF_WIDTH = QUAD_INNER_HALF_WIDTH + CREST_WIDTH_METRES

    /**
     * The first texel row of the crest that is opaque at its OUTWARD edge column — 120 of 1152.
     *
     * Column 0 of `rock-top-diffuse.png`, and column 690 of the mirrored copy; `RockFaceTest`
     * re-derives both from the committed PNGs, and that they agree is one more check that the
     * mirror is exact. It is the height the headland stands at where it leaves the sprite, so it
     * is what [CREST_SHOULDER_DEPTH] has to be for the flat top to meet the art without a step.
     */
    const val TOP_SHOULDER_TEXEL_ROW = 120

    /**
     * The depth of the cliff top OUTWARD of the crest sprite: the summit's shoulder, continued off
     * the side of the frame.
     *
     * Derived from [TOP_SHOULDER_TEXEL_ROW] rather than authored, so that a re-bake at a different
     * size or a redrawn summit moves the flat top with the art instead of leaving a step at the
     * join. -18.66 m at the shipped crop, i.e. the headland stands about two diver-heights out of
     * the water where it runs off frame, against the summit's 21 m.
     */
    const val CREST_SHOULDER_DEPTH =
        CREST_TOP_DEPTH + TOP_HEIGHT_METRES * TOP_SHOULDER_TEXEL_ROW / TOP_TEXELS_TALL

    private fun rockTexture(file: String, name: String, format: TextureFormat) = Texture(
        "/backdrop/$file",
        name,
        filter = TextureFilter.LINEAR,
        wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = format,
        maxMipLevels = 1                    // NEVER 0 — see the class doc.
    )

    /**
     * The crest, and its horizontal MIRROR for the other side of the column.
     *
     * Baked mirrored for the same reason [mirrorDiffuse] now is, and this one never had a choice:
     * the only mirror available at the draw site is a 180-degree rotation, which is a horizontal
     * mirror AND a vertical flip, and a vertically flipped summit points downwards. There is no
     * horizontal-only mirror at the draw site either — `NormalMapRenderer.drawNormalMap` takes no uv arguments,
     * so a `uMin`/`uMax` swap would mirror the albedo and leave every bump lit from the wrong
     * side, and a negative width leaves `normalRotation` alone with the same result.
     *
     * So the mirror is BAKED, where the normal map's x component can be negated exactly and
     * checked before it ships. See `tools/backdrop/mirror.py` and `RockFaceTest`, which re-derives
     * the mirror from the committed base PNGs texel by texel.
     */
    val topDiffuse = rockTexture("rock-top-diffuse.png", "rock_top_diffuse", TextureFormat.SRGBA8)
    val topNormal = rockTexture("rock-top-normal.png", "rock_top_normal", TextureFormat.RGBA8)
    val topMirrorDiffuse = rockTexture("rock-top-mirror-diffuse.png", "rock_top_mirror_diffuse", TextureFormat.SRGBA8)
    val topMirrorNormal = rockTexture("rock-top-mirror-normal.png", "rock_top_mirror_normal", TextureFormat.RGBA8)

    private val wallTextures = listOf(diffuse, normal, mirrorDiffuse, mirrorNormal)
    private val topTextures = listOf(topDiffuse, topNormal, topMirrorDiffuse, topMirrorNormal)

    /** Queues every rock texture for upload. Called once, from `EnPustTil.onCreate`. */
    fun load(engine: PulseEngine)
    {
        wallTextures.forEach { engine.asset.load(it) }
        topTextures.forEach { engine.asset.load(it) }
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
        if (wallTextures.all { it.handle != TextureHandle.INVALID } &&
            topTextures.all { it.handle != TextureHandle.INVALID })
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
     * How many whole tiles reach from [WALL_TOP_DEPTH] down past [worldBottom]. At least one, so a
     * degenerate or inverted visible rect still draws something rather than a zero-height quad.
     *
     * ## THE ROCK IS ANCHORED TO THE WATERLINE NOW, NOT TO A LATTICE UNDER THE CAMERA
     *
     * This used to be `tileRows(worldTop, worldBottom, phase)`, paired with a `tileTopDepth` that
     * returned the tile boundary at or above the camera's top edge. That existed to solve one
     * problem — the mapping from world depth to texture v must be a function of the DEPTH alone,
     * or the cliff creeps upward relative to the water as the diver descends (subtle per frame,
     * unmistakable over a dive, invisible in a screenshot) — and it solved it correctly.
     *
     * [WALL_TOP_DEPTH] solves it more simply and more strongly: it is a fixed WORLD constant, so
     * `v` is a function of depth by construction rather than by rounding the camera onto a
     * lattice. And it is the thing the crest needs anyway. The wall has to stop somewhere for the
     * crest to cap it, and "somewhere" has to be the same depth on both sides of the column.
     *
     * ## What was given up: the two walls' vertical PHASE, and it was worth nothing
     *
     * `tileTopDepth` took a `phase` so the right wall could be offset half a tile and stop reading
     * as an exact mirror of the left. That cannot survive a fixed top edge — a phase moves the
     * lattice, and the crest has to sit on it. It turns out to cost nothing, which is worth
     * writing out because it looks like a loss:
     *
     * The right wall is drawn rotated 180 degrees, so its v runs the other way. At depth `d` the
     * left wall samples `fract((d - top)/H)` and the right samples `fract(-(d - top)/H)`, i.e.
     * `1 - fract(...)`. The two coincide wherever `fract(...)` is 0 or 0.5 — every H/2 = 20 m.
     * WITH the old half-tile phase the right wall sampled `fract(0.5 - (d - top)/H)`, which
     * coincides with the left wherever `fract(...)` is 0.25 or 0.75 — also every 20 m. The phase
     * moved the coincidence; it never removed one. What actually decorrelates the two walls is the
     * rotation, which mirrors them in BOTH axes, and that is untouched.
     */
    fun tileRows(worldBottom: Float): Int
    {
        val span = worldBottom - WALL_TOP_DEPTH
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
