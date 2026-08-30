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
 * fed the output of the first — which is why the two bytecode methods agree byte-for-byte on
 * the cascade count and this file only needs to implement the maths once.
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
     * How far light propagates, in METRES, through [cascades] cascades at [pixelsPerMetre].
     *
     * Each cascade's interval is 4x the previous one (`GiRadianceCascades`'s own geometric
     * progression), so total reach in pixels is the geometric sum
     * `intervalLength * (4^cascades - 1) / 3`. `intervalLength` defaults to 1.0f
     * (`GlobalIlluminationSystem`'s `<init>`, verified from bytecode), which is the value in
     * use here — this project never overrides it.
     */
    fun propagationMetres(cascades: Int, pixelsPerMetre: Float, intervalLength: Float = 1f): Float
    {
        val reachPixels = intervalLength * (fourToThe(cascades) - 1f) / 3f
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
