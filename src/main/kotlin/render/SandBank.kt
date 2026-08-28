package render

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureHandle
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import no.njoh.pulseengine.core.shared.utils.Logger

/**
 * The seabed at the foot of the trench: ONE quad, exactly [Framing.VISIBLE_WIDTH_METRES]
 * across, centred on x = 0, drawn at `uTiling = vTiling = 1`.
 *
 * `Tuning.MAX_DEPTH` stops the diver dead at 160 m in open water for the same reason
 * `Tuning.COLUMN_HALF_WIDTH` used to stop him sideways in open water — and the cliff art exists
 * because a bare column boundary did not read as a wall. This is that same fix in the other
 * axis.
 *
 * ## NO TILING, NO MODULUS, NO ASPECT-DEPENDENT COUNT
 *
 * [CameraRig.pixelsPerMetre] is `max(H / 60, W / 98.5156)`, so the visible width is
 * `W / pixelsPerMetre <= W / (W / 98.5156)` = **98.5156 m, always**. At or below the design
 * aspect 1.6419 the height fit is larger and the visible width is `60 * aspect < 98.5156` — the
 * quad overhangs both frame edges and nothing is missing. Above it the width fit binds and the
 * visible width is EXACTLY 98.5156 m — the quad's edges land exactly on the frame's. So a quad
 * of exactly [WIDTH_METRES], centred on `CameraRig.WORLD_X_AT_ORIGIN` (a constant zero — the
 * camera never tracks sideways), reaches both frame edges at every aspect there is. This is the
 * same property `RockFace.coverageOuterHalfWidth` had to be restructured to obtain, got free.
 *
 * That is also why the bake does NOT clear a wrap border on this art, the way it does on every
 * rock texture: the sandbank's outermost columns are SUPPOSED to be opaque at the frame edge.
 * See `tools/build_backdrop.py`'s `bake_sandbank`.
 *
 * ## THE DERIVATION RUNS FROM THE ART OUTWARD, AND THE DIRECTION IS THE WHOLE POINT
 *
 * [QUAD_TOP_DEPTH] is the PLACEMENT — the one number a human chose — and the crest depth is
 * measured DOWN FROM IT through the sheet. Written the other way round
 * (`MEAN_CREST_DEPTH = Tuning.MAX_DEPTH + DiverSprite.FIN_REACH_METRES`) `SandBankTest`'s
 * relationship case substitutes to `(a + b) - b == a`, an identity that no edit anywhere can
 * falsify — a test that cannot fail, in a project whose own convention is that such a test is
 * worse than none. Do not re-invert it.
 */
object SandBank
{
    // --- The bake's contract. Copied from the bake, never retyped from memory; SandBankTest
    // --- re-derives both from the committed PNG's IHDR so a re-bake cannot leave them stale.

    const val TEXELS_WIDE = 2000
    const val TEXELS_TALL = 500

    /**
     * The art's own 4:1 aspect decides the height. Squashing it to some chosen depth was
     * available and was declined: a 2000x500 seabed drawn at any other ratio is a stretched
     * seabed, and the dune band's 37 rows would stretch with it.
     *
     * `WIDTH_METRES * TEXELS_TALL / TEXELS_WIDE` is a legal `const val`:
     * [Framing.VISIBLE_WIDTH_METRES] is itself `const` and `Float`, so the expression is Float
     * from the left and never does integer division.
     */
    const val WIDTH_METRES = Framing.VISIBLE_WIDTH_METRES
    const val HEIGHT_METRES = WIDTH_METRES * TEXELS_TALL / TEXELS_WIDE          // 24.62890625

    /** One texel of the sheet, in world metres: 0.049258 m. */
    const val TEXEL_HEIGHT_METRES = HEIGHT_METRES / TEXELS_TALL

    /**
     * The mean, over the sheet's 2000 columns, of each column's own first row with alpha above
     * 16 — the threshold `DiverSprite`'s own measurements use. Measured at 84.279 and declared
     * to 84.3; `SandBankTest` re-derives it from the committed diffuse.
     *
     * THE THRESHOLD IS NOT A DETAIL. At `alpha > 0` the same file reads min 58, mean 83.684,
     * and row 58 is therefore not empty (its peak alpha is 3). State the threshold or the
     * number is not reproducible.
     *
     * The ragged crest band spans rows 59..95 of 500 — 37 rows, i.e. 1.245 m of dune peak above
     * the mean and 0.528 m of trough below it. That band is the seabed's silhouette, exactly as
     * the cliff's columns 264..475 are the wall's.
     */
    const val MEAN_CREST_TEXEL_ROW = 84.3f
    const val CREST_OFFSET_METRES = HEIGHT_METRES * MEAN_CREST_TEXEL_ROW / TEXELS_TALL  // 4.1524336

    /**
     * THE PLACEMENT. The only chosen number in this file — the single degree of freedom in the
     * whole feature.
     *
     * It was solved once, so that at `Tuning.MAX_DEPTH` the diver's fins land on the MEAN crest:
     * `160 + DiverSprite.FIN_REACH_METRES - CREST_OFFSET_METRES` = `160 + 4.453125 - 4.1524336`
     * = 160.3006914, i.e. 160.30069 to the precision of this literal. That arithmetic is not the
     * direction the code runs — see the class doc, and `SandBankTest`'s relationship case.
     *
     * Anchoring on the MEAN rather than on the deepest trough is deliberate and has a visible
     * consequence at each end: peaks (about 1.245 m above the mean) may cross the diver's
     * silhouette, and in a trough his fins hang about 0.53 m ABOVE the sand — roughly ten screen
     * pixels of open water under him on a 1080p 16:9 panel. Going ONTO the sand is fine; going
     * BENEATH it is not, and that is what the mean buys. If the gap ever reads as floating, this
     * constant alone is the lever and everything else follows from it.
     */
    const val QUAD_TOP_DEPTH = 160.30069f
    const val MEAN_CREST_DEPTH = QUAD_TOP_DEPTH + CREST_OFFSET_METRES           // 164.4531
    const val QUAD_BOTTOM_DEPTH = QUAD_TOP_DEPTH + HEIGHT_METRES                // 184.9296

    /**
     * `SRGBA8` for the albedo because the bake writes sRGB-encoded pixels and the GPU linearizes
     * on sample, which is what the GI multiply expects. `RGBA8` for the normals because the bake
     * already did the sRGB->linear decode and the shader must not decode a second time.
     *
     * Filter, wrapping, format family and `maxMipLevels` MUST equal [DiverSprite]'s,
     * [RockFace]'s and [Backdrop]'s: differ in any one and this allocates a second
     * 2048x2048x4x15 = 251.7 MB array instead of taking a free layer.
     */
    val diffuse = sandTexture("sandbank-diffuse.png", "sandbank_diffuse", TextureFormat.SRGBA8)
    val normal = sandTexture("sandbank-normal.png", "sandbank_normal", TextureFormat.RGBA8)

    private fun sandTexture(file: String, name: String, format: TextureFormat) = Texture(
        "/backdrop/$file",
        name,
        filter = TextureFilter.LINEAR,
        wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = format,
        maxMipLevels = 1                    // NEVER 0 — see the class doc of RockFace.
    )

    private val textures = listOf(diffuse, normal)

    /** Queues both sheets for upload. Called once, from `EnPustTil.onCreate`. */
    fun load(engine: PulseEngine)
    {
        textures.forEach { engine.asset.load(it) }
    }

    /** Ten seconds at 60 fps — see [RockFace.ready], which this mirrors. */
    private const val WARN_AFTER_FRAMES = 600

    private var framesWithoutTextures = 0
    private var warned = false

    /**
     * Are both textures on the GPU? `Texture.<init>` sets `handle` to `TextureHandle.INVALID`
     * and only `onUploaded` replaces it, so this is the one field that distinguishes "queued"
     * from "resident" without a GL call.
     *
     * A FUNCTION, not a property, because it has a side effect: it counts consecutive misses and
     * logs exactly one WARN once they stop being explicable by the asynchronous upload. The
     * fallback — no seabed at all — looks entirely deliberate, so nothing else would ever say
     * the art had failed to load. The camera clamp does not depend on the texture, so the frame
     * still never descends past [QUAD_BOTTOM_DEPTH] either way.
     */
    fun ready(): Boolean
    {
        if (textures.all { it.handle != TextureHandle.INVALID })
        {
            framesWithoutTextures = 0
            return true
        }

        framesWithoutTextures++
        if (framesWithoutTextures >= WARN_AFTER_FRAMES && !warned)
        {
            warned = true
            Logger.warn {
                "Sandbank textures never uploaded — the trench has no visible floor. " +
                "Check /backdrop/sandbank-diffuse.png and /backdrop/sandbank-normal.png are on the classpath."
            }
        }
        return false
    }

    /**
     * The depth at which the art stops and a flat fill has to take over — the bottom of the
     * quad, or the bottom of the frame if the quad reaches past it. Mirrors
     * [Backdrop.skirtDepth].
     *
     * BE HONEST: WITH THE CAMERA CLAMP IN PLACE THIS CAN NEVER RUN. The frame's bottom edge
     * provably never passes [QUAD_BOTTOM_DEPTH] — see [Framing.SEA_FLOOR_DEPTH] and
     * `DiveCameraTest`'s sweep — so `worldBottom > QUAD_BOTTOM_DEPTH` is unreachable and this
     * always returns `worldBottom`. It ships as cheap insurance against a future `Framing`
     * change, a mis-computed visible depth reaching [DiveCamera], or the clamp being removed by
     * someone who did not read the design's §7. It is a `minOf` and one branch that is never
     * taken.
     *
     * AND NOTE WHAT THAT COSTS. If a real window grab ever shows one row of water below the
     * sand at the frame's bottom edge (`texture.frag`'s `fract` extrapolation reaching row 0,
     * which is transparent), "bleed the skirt up by one texel" is NOT on its own a fix — there
     * is nothing to bleed. Making the branch reachable means changing THIS function to
     * `minOf(QUAD_BOTTOM_DEPTH - TEXEL_HEIGHT_METRES, worldBottom)`, which gives up the
     * disjointness above and trades a possible one-pixel water line for a certain one-texel
     * double-composite. That is contingent on a measurement, not adopted here.
     */
    fun skirtDepth(worldBottom: Float): Float = minOf(QUAD_BOTTOM_DEPTH, worldBottom)
}
