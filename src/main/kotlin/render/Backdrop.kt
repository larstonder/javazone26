package render

import dive.Tuning
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureHandle
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import no.njoh.pulseengine.core.shared.utils.Logger

/**
 * The parallax silhouettes behind the water: three distant ridges that scroll past at their own
 * rates as the diver descends, so the column reads as a place rather than as a scrolling texture.
 *
 * ## What the art is, and what that decides
 *
 * All three sources are FLAT SHAPES. Their opaque RGB is 16.7/19.0/22.0 with a standard deviation
 * of 0.4 — one colour plus dither, in every one of the three files — and there is no internal
 * detail at all. So the bake throws the colour away and commits a white RGB + the source alpha,
 * and the layer's entire appearance is [DiveRenderer.silhouetteColor] times [Layer.alpha] here.
 * That is not a shortcut: baking a near-black colour in would have dropped every opaque texel far
 * under [DiveRenderer.GI_REFLECTANCE_FLOOR], and the multiply shader would have replaced the
 * mountains with flat grey.
 *
 * Each shape is also SOLID BELOW ITS RIDGE, and the quad holding it is finite, so its bottom edge
 * comes into view once the camera has descended far enough. [skirtDepth] is why that does not show
 * as a horizontal line across the frame — see its doc.
 *
 * ## Only the vertical parallax exists, because only the vertical camera does
 *
 * `CameraRig.WORLD_X_AT_ORIGIN` is a constant zero: the column is generated symmetrically about
 * x = 0 and the camera never tracks the diver sideways. A horizontal parallax term would
 * therefore be multiplying a number that is always zero.
 *
 * ## VRAM
 *
 * All three are baked to 1600 on their long side. That is not a quality judgement — they are flat
 * masks and 1600 is far more than they need — it is the bucket: `TextureBank` reuses an existing
 * array when `max(w, h) > arraySize / 2`, so 1600 takes a free layer in the 2048 SRGBA8 array the
 * diver's diffuse sheet already allocated, while their source 3000x3000 would have opened a
 * 4096x4096x4x10 = 671.1 MB array and 1024 (being exactly half of 2048, and the test being
 * strict) would have opened a 209.7 MB one.
 */
object Backdrop
{
    /**
     * One parallax layer.
     *
     * [restTopDepth] is where the layer's TOP edge sits when the camera is at the waterline, and
     * [rate] is how fast it scrolls relative to the world: 1 is nailed to the water, 0 is nailed
     * to the frame. See [parallaxTopDepth].
     *
     * [texelsWide]/[texelsTall] come from the bake and are re-derived from the committed PNGs by
     * `BackdropTest`. They exist only to give [heightMetres] the shape's proportions — the same
     * arrangement as [DiverSprite.FRAME_ASPECT] and [RockFace.TILE_WIDTH_METRES], so that a
     * re-bake at a different `--silhouette-max` cannot silently squash a mountain range.
     */
    class Layer(
        val texture: Texture,
        val texelsWide: Int,
        val texelsTall: Int,
        val rate: Float,
        val restTopDepth: Float,
        val alpha: Float
    )
    {
        /** The layer's world height for a given world width, keeping the shape's proportions. */
        fun heightMetres(widthMetres: Float): Float =
            widthMetres * texelsTall.toFloat() / texelsWide.toFloat()
    }

    private fun silhouette(index: Int, wide: Int, tall: Int, rate: Float, restTop: Float, alpha: Float) =
        Layer(
            Texture(
                "/backdrop/silhouette-$index.png",
                "backdrop_silhouette_$index",
                // Identical to the diver's sheets and to RockFace's, and that is load-bearing
                // rather than tidy: TextureBank reuses an array only when format, filter,
                // wrapping AND maxMipLevels all match. maxMipLevels is 1 and NEVER 0 —
                // glTexStorage3D with levels = 0 is GL_INVALID_VALUE, allocates nothing, and
                // logs nothing.
                filter = TextureFilter.LINEAR,
                wrapping = TextureWrapping.CLAMP_TO_EDGE,
                format = TextureFormat.SRGBA8,
                maxMipLevels = 1
            ),
            wide, tall, rate, restTop, alpha
        )

    /**
     * Far to near, which is also the order they are drawn in — each layer paints over the one
     * behind it. `BackdropTest` asserts the rates increase down this list, because a far layer
     * that scrolled faster than a near one is exactly backwards and reads as the mountains
     * swimming past each other.
     *
     * The rates, rest depths and alphas are DESIGN DECISIONS, tuned against captures, not
     * measurements of anything. What is not free is their ORDER and their range: a rate outside
     * (0, 1) either pins a layer to the frame forever or drives it the wrong way, and an alpha
     * heavy enough to compete with the water would put the backdrop in front of the gameplay.
     */
    val layers = listOf(
        silhouette(3, 1600, 533, rate = 0.15f, restTop = 20f, alpha = 0.10f),
        silhouette(2, 1600, 1600, rate = 0.30f, restTop = 35f, alpha = 0.16f),
        silhouette(1, 1600, 1600, rate = 0.50f, restTop = 60f, alpha = 0.24f)
    )

    /**
     * How wide a layer is drawn: the whole visible frame, exactly as [SandBank.WIDTH_METRES] is.
     *
     * THIS READ `2f * Tuning.COLUMN_HALF_WIDTH` UNTIL 2026-08-30, and the note justifying it said
     * the column walls "are drawn after the backdrop and are opaque, so nothing outside
     * +-[Tuning.COLUMN_HALF_WIDTH] could be seen even if it were drawn." That is false, and the
     * error is worth keeping visible because it is an easy one to make twice: the wall's inner
     * edge is RAGGED ALPHA, not coverage. Measured on the committed `rock-diffuse.png`, the rock
     * is **0% opaque at exactly 40 m** — where this constant used to stop the art — and only
     * 64.7% opaque across 40..44.12 m, so better than a third of that band was the silhouette's
     * own rectangular cut showing against open water. [RockFace]'s own texel constants say the
     * same thing: `ALPHA_TEXEL_COLUMNS = 475` falls at 39.98 m and `OPAQUE_TEXEL_COLUMNS = 264`
     * at 44.11 m, so solid rock begins only at [RockFace.BACKING_HALF_WIDTH] — 4.12 m OUTSIDE the
     * old edge. The wall covers the backdrop's cut nowhere near where the note assumed.
     *
     * The old note's real objection — that sizing to the visible rect "would stretch the mountains
     * wider on a wider panel, which is a change of art" — does not apply, and [Layer.heightMetres]
     * is why: height is DERIVED from width, so widening scales a layer and cannot stretch it. The
     * shape is preserved exactly, and `each layer's world height follows its own proportions`
     * asserts it. What does change is scale: every layer is 23% larger than it was, its top edge
     * still pinned at [Layer.restTopDepth] and the extra height hanging below.
     *
     * It is no longer aspect-independent, and that is the point rather than a cost: the frame's
     * width is capped at [Framing.VISIBLE_WIDTH_METRES] at every aspect (see [CameraRig]), so one
     * quad this wide reaches both edges on every panel without tiling.
     */
    val widthMetres = Framing.VISIBLE_WIDTH_METRES

    /** Queues all three layers for upload. Called once, from `EnPustTil.onCreate`. */
    fun load(engine: PulseEngine)
    {
        layers.forEach { engine.asset.load(it.texture) }
    }

    private const val WARN_AFTER_FRAMES = 600
    private var framesWithoutTextures = 0
    private var warned = false

    /**
     * Are all three layers resident? `Texture.<init>` sets `handle` to `TextureHandle.INVALID`
     * and only `onUploaded` replaces it. All-or-nothing rather than per-layer, so a partial
     * upload can never show a near ridge with no far one behind it.
     *
     * Side-effecting for the same reason [RockFace.ready] is: the fallback is simply no backdrop,
     * which looks exactly like a deliberate design choice, so nothing else would ever say so.
     */
    fun ready(): Boolean
    {
        if (layers.all { it.texture.handle != TextureHandle.INVALID })
        {
            framesWithoutTextures = 0
            return true
        }

        framesWithoutTextures++
        if (framesWithoutTextures >= WARN_AFTER_FRAMES && !warned)
        {
            warned = true
            Logger.warn {
                "Backdrop silhouettes never uploaded — the parallax layers are absent. " +
                "Check /backdrop/silhouette-1..3.png are on the classpath."
            }
        }
        return false
    }

    // --- The parallax arithmetic, in world metres ----------------------------------------------

    /**
     * Where a layer's top edge is drawn, given where the top of the frame is.
     *
     *     drawnTop = restTopDepth + (1 - rate) * cameraTopDepth
     *
     * so `drawnTop - cameraTopDepth`, the layer's position ON SCREEN, is
     * `restTopDepth - rate * cameraTopDepth`: it climbs the frame by `rate` metres for every
     * metre the camera descends. `rate = 1` leaves `drawnTop` at `restTopDepth` for every camera
     * position — nailed to the water. `rate = 0` leaves the screen offset at `restTopDepth` —
     * nailed to the frame. Neither endpoint is used; both are what the interior means.
     *
     * Stated in terms of a REST DEPTH rather than an anchor the layer converges on, because the
     * rest depth is a number a human can see in a screenshot ("the far ridge starts 20 m down
     * when the camera is at the waterline") while the convergence point of a 0.15-rate layer is
     * out past 160 m and means nothing.
     */
    fun parallaxTopDepth(cameraTopDepth: Float, restTopDepth: Float, rate: Float): Float =
        restTopDepth + (1f - rate) * cameraTopDepth

    /**
     * The depth the layer's own art stops at and a flat fill has to take over — the bottom of the
     * quad, or the bottom of the frame if the quad reaches past it.
     *
     * WHY THERE IS A FILL AT ALL. Each silhouette is solid below its ridge, but its quad is a
     * finite number of metres tall, and because `rate < 1` the quad climbs the frame as the diver
     * descends. Past about 130 m every layer's bottom edge is inside the visible rect, and without
     * this the mountain would simply stop in mid-water along a dead-straight horizontal line. The
     * fill continues it in the same colour at the same alpha, so the join is invisible wherever
     * the art's own bottom row is opaque — which it is for all of layer 2 and layer 3, and for
     * 97.8% of layer 1, whose diagonal runs out through the bottom-right corner.
     */
    fun skirtDepth(layerTopDepth: Float, layerHeightMetres: Float, worldBottom: Float): Float =
        minOf(layerTopDepth + layerHeightMetres, worldBottom)
}
