package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `FrameProbe` is the only instrument this project has for frame time — the engine's own F3
 * overlay has never run (see the class doc), and GL timer queries return 0 ns on Apple
 * Silicon. So the arithmetic it reports is worth asserting: a percentile that is quietly
 * wrong would make every optimisation measurement in the performance plan meaningless, and
 * would do it silently, because a plausible-looking millisecond number is indistinguishable
 * from a correct one.
 */
class FrameProbeTest
{
    @Test
    fun `percentile picks the ranked sample, not the mean`() {
        // Deliberately skewed: mean is 20.9, p50 must still be 10.
        val samples = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 119f)
        assertEquals(10f, FrameProbe.percentile(samples, 10, 0.5f), 0.001f)
        assertEquals(119f, FrameProbe.percentile(samples, 10, 1.0f), 0.001f)
    }

    @Test
    fun `percentile reads only the first count entries, not the whole buffer`() {
        // The ring buffer is allocated once and reused, so stale tail entries must not count.
        val samples = FloatArray(100) { 999f }
        samples[0] = 5f
        samples[1] = 7f
        assertEquals(7f, FrameProbe.percentile(samples, 2, 1.0f), 0.001f)
    }

    @Test
    fun `percentile does not mutate the caller's buffer`() {
        // It sorts to rank; sorting in place would scramble the live sample ring.
        val samples = floatArrayOf(30f, 10f, 20f)
        FrameProbe.percentile(samples, 3, 0.5f)
        assertEquals(30f, samples[0], 0.001f)
    }

    @Test
    fun `percentile of an empty window is zero rather than a crash`() {
        assertEquals(0f, FrameProbe.percentile(FloatArray(10), 0, 0.5f), 0.001f)
    }

    @Test
    fun `the reported line carries fps alongside milliseconds`() {
        val line = FrameProbe.formatLine(p50 = 16.67f, p95 = 20f, worst = 33f, frames = 120)
        assertTrue(line.contains("16.67"), "p50 ms missing: $line")
        assertTrue(line.contains("60.0"), "implied fps missing: $line")
        assertTrue(line.contains("120"), "frame count missing: $line")
    }
}
