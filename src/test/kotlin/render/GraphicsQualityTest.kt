package render

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The quality ladder, checked against the engine's real sizing maths rather than against
 * intuition.
 *
 * WHY THIS IS NOT AN OBVIOUS TEST: "Low is cheaper than High" looks like it cannot fail. It
 * absolutely can. lightTexScale feeds a STEP FUNCTION (see GiSizingTest) in which a smaller
 * scale can tip the cascade count and round the light texture UP to a larger size — the shipped
 * 0.5 was more expensive than the engine's 0.4 default for exactly that reason. So a future
 * edit that "turns Low down a bit more" can silently make Low cost more than Medium, and
 * nothing else in this codebase would notice.
 */
class GraphicsQualityTest
{
    // The two framebuffers this game actually runs at: the release fullscreen path and the
    // Retina dev window. A ladder that is monotonic at one and not the other is still broken.
    private val framebuffers = listOf(1920 to 1200, 3200 to 1800)

    @Test
    fun `light texture pixels fall monotonically from high to low`() {
        for ((w, h) in framebuffers) {
            val pixels = listOf(GraphicsQuality.HIGH, GraphicsQuality.MEDIUM, GraphicsQuality.LOW)
                .map { q ->
                    val s = q.settings()
                    val (tw, th) = GiSizing.lightTextureSize(w, h, s.lightTexScale, s.maxCascades)
                    tw.toLong() * th.toLong()
                }
            assertTrue(pixels[0] >= pixels[1],
                "at ${w}x$h MEDIUM light texture (${pixels[1]}) exceeds HIGH (${pixels[0]})")
            assertTrue(pixels[1] >= pixels[2],
                "at ${w}x$h LOW light texture (${pixels[2]}) exceeds MEDIUM (${pixels[1]})")
        }
    }

    @Test
    fun `every preset keeps at least six cascades, the measured-cost configuration`() {
        // NOT a torch-reach floor: GiSizing.propagationMetres, corrected 2026-08-30 to convert
        // light-texture texels through lightTexScale rather than treating them as framebuffer
        // pixels, shows even 5 cascades reaches far past TORCH_REACH_METRES (24 m) — see
        // GiSizingTest and GraphicsQuality's LOW comment for the numbers. Six is kept as the
        // floor because it is the cascade count Task 04 of the rendering-performance plan
        // actually measured (13.6 ms, design spec §6.1); dropping below it would run every
        // preset on an unmeasured configuration, and 6 is also the number that removes the extra
        // cascade PASS the uncapped count (7) would cost at 3200x1800.
        for ((w, h) in framebuffers)
            for (q in GraphicsQuality.entries) {
                val s = q.settings()
                assertTrue(GiSizing.cascadeCount(w, h, s.lightTexScale, s.maxCascades) >= 6,
                    "$q at ${w}x$h drops below 6 cascades, the measured-cost floor")
            }
    }

    @Test
    fun `scene and light scales move together across the ladder`() {
        // DiveLighting's own note: raising only the light map buys a smoother upscale of the
        // same coarse occlusion; raising only the scene marches a finer scene into a map that
        // cannot carry it. A preset that separates them is a mistake.
        for (q in GraphicsQuality.entries) {
            val s = q.settings()
            assertTrue(s.lightTexScale == s.localSceneTexScale,
                "$q separates lightTexScale (${s.lightTexScale}) from localSceneTexScale (${s.localSceneTexScale})")
        }
    }

    @Test
    fun `no preset re-enables work a cheaper preset turned off`() {
        val high = GraphicsQuality.HIGH.settings()
        val medium = GraphicsQuality.MEDIUM.settings()
        val low = GraphicsQuality.LOW.settings()
        assertTrue(!low.bloom || medium.bloom, "LOW enables bloom that MEDIUM disables")
        assertTrue(!low.bilinearFix || medium.bilinearFix, "LOW enables bilinearFix that MEDIUM disables")
        assertTrue(low.globalSceneTexScale <= medium.globalSceneTexScale)
        assertTrue(medium.globalSceneTexScale <= high.globalSceneTexScale)
    }
}
