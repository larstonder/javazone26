package render

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A pure re-implementation of two formulas read out of `pulse-engine-0.13.0.jar` by decompiling
 * `GlobalIlluminationSystem.lightTextureSizeFunc` and `GiRadianceCascades.applyEffect`:
 *
 * ```
 * tw, th       = ceil(W * scale), ceil(H * scale)
 * diag         = sqrt(tw^2 + th^2)
 * cascadeCount = min(ceil(log2(diag) / log2(4)) + 1, maxCascades)
 * f            = 2^cascadeCount
 * outW, outH   = ceil(tw / f) * f, ceil(th / f) * f
 * ```
 *
 * `lightTextureSizeFunc` computes this once, when the light surface (re-)initialises, from the
 * raw framebuffer size and `lightTexScale`. `GiRadianceCascades.applyEffect` re-derives
 * `cascadeCount` EVERY FRAME from the ALREADY-ROUNDED texture it was handed — same formula,
 * fed the output of the first. The two bytecode methods therefore agree at every value this
 * project actually uses, and this file only needs to implement the maths once — but they take
 * different inputs (raw framebuffer size versus the rounded texture), so "agree" holds only
 * because the round-trip through rounding is idempotent for values this cap ever binds on, not
 * because the two formulas are the same computation.
 *
 * WHY A COPY OF ENGINE MATHS IS WORTH HAVING: `lightTexScale` is a STEP FUNCTION, not a curve.
 * The light texture is rounded UP to a multiple of `2^cascadeCount`, and `cascadeCount` is
 * itself derived from the ROUNDED diagonal — so a "small" change to the scale can cost or save
 * 70-120% of the single most expensive pass in the game, and nobody can see that by reading the
 * number. `GiSizingTest` pins the mechanism so a future quality preset cannot silently tip the
 * cascade count and cost more at "Low" than at "Medium".
 *
 * No engine imports on purpose — this stays testable in a headless JVM.
 */
object GiSizing
{
    /**
     * The rounded (width, height) of the GI light texture for a [framebufferWidth] x
     * [framebufferHeight] framebuffer at [scale], capped at [maxCascades] cascades.
     */
    fun lightTextureSize(framebufferWidth: Int, framebufferHeight: Int, scale: Float, maxCascades: Int): Pair<Int, Int>
    {
        val tw = ceil(framebufferWidth * scale).toInt()
        val th = ceil(framebufferHeight * scale).toInt()
        val cascades = cascadeCount(framebufferWidth, framebufferHeight, scale, maxCascades)
        val f = 1 shl cascades
        val outW = ceilToMultiple(tw, f)
        val outH = ceilToMultiple(th, f)
        return outW to outH
    }

    /**
     * The number of radiance cascades the engine will run for a [framebufferWidth] x
     * [framebufferHeight] framebuffer at [scale], capped at [maxCascades].
     *
     * `diag` is measured off the SCALED-BUT-NOT-YET-ROUNDED (`tw`, `th`) here, matching
     * `lightTextureSizeFunc` exactly — `GiRadianceCascades.applyEffect` gets the same answer at
     * runtime only because it measures `diag` off the texture that formula already produced.
     *
     * This omits the engine's own `coerceAtLeast(1)` on `maxCascades` — the two only diverge for
     * `maxCascades <= 0`, which no preset in this project ever passes, so it is left out rather
     * than reproduced for a case nothing here reaches.
     */
    fun cascadeCount(framebufferWidth: Int, framebufferHeight: Int, scale: Float, maxCascades: Int): Int
    {
        val tw = ceil(framebufferWidth * scale)
        val th = ceil(framebufferHeight * scale)
        val diag = sqrt(tw * tw + th * th)
        val raw = ceil(log2(diag) / log2(4f)).toInt() + 1
        return min(raw, maxCascades)
    }

    /**
     * How far light propagates, in METRES, through [cascades] cascades at [pixelsPerMetre] and
     * [lightTexScale].
     *
     * CASCADES MARCH IN LIGHT-TEXTURE TEXELS, NOT FRAMEBUFFER PIXELS — an earlier version of
     * this function treated `intervalLength` as framebuffer pixels directly and every caller's
     * reach number was wrong as a result (whole-branch review, 2026-08-30; corrected rather than
     * moved once caught, because it had also been used to justify `maxCascades = 6` as a torch-
     * reach floor when it is not one — see [GiSizingTest] and `GraphicsQuality`'s LOW comment).
     * Re-derived from `radiance_cascades.frag`:
     * - `:204` `screenPos = floor(uv * lightTexRes)` puts `probeCenterPos` in LIGHT-TEXTURE
     *   TEXELS, and `:200-201` builds `intervalStart`/`intervalEnd` by adding
     *   `intervalLength * ...` straight onto that — so `intervalLength`, and therefore the
     *   geometric sum below, is in light-texture texels, not framebuffer pixels.
     * - `:122-126` is the engine's OWN conversion from texels to framebuffer pixels:
     *   `dist = distance(hitPos, originPos) / lightTexScale`. One light-texture texel is
     *   `1 / lightTexScale` framebuffer pixels.
     *
     * So total reach in light-texture texels is the geometric sum
     * `intervalLength * (4^cascades - 1) / 3` (each cascade's interval is 4x the previous one,
     * `GiRadianceCascades`'s own progression), which becomes framebuffer pixels by dividing by
     * `lightTexScale`, and then metres by dividing by `pixelsPerMetre`. `intervalLength` defaults
     * to 1.0f (`GlobalIlluminationSystem`'s `<init>`, verified from bytecode), which is the value
     * in use here — this project never overrides it.
     */
    fun propagationMetres(cascades: Int, pixelsPerMetre: Float, lightTexScale: Float, intervalLength: Float = 1f): Float
    {
        val reachTexels = intervalLength * (fourToThe(cascades) - 1f) / 3f
        val reachPixels = reachTexels / lightTexScale
        return reachPixels / pixelsPerMetre
    }

    private fun ceilToMultiple(value: Int, factor: Int): Int
    {
        val quotient = ceil(value.toFloat() / factor.toFloat()).toInt()
        return quotient * factor
    }

    private fun fourToThe(n: Int): Float
    {
        var result = 1f
        repeat(n) { result *= 4f }
        return result
    }
}
