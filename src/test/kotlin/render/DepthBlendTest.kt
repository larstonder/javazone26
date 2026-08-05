package render

import dive.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DepthBlend` is what replaces the old hard per-zone lookup ("sharp jump between depth
 * levels" from playtest feedback) with a continuous curve. Every game-facing use blends a
 * strictly increasing or strictly decreasing per-zone table, so these tests exercise it with
 * a representative one rather than duplicating the concrete tables that live in
 * `DiveLighting`/`DiveRenderer`.
 */
class DepthBlendTest
{
    // A monotonically increasing per-zone table, standing in for pearl/diver light intensity.
    private val increasing = floatArrayOf(0.6f, 1.0f, 1.8f, 2.6f, 4.0f)

    @Test
    fun `at a zone's own midpoint, the blended value is exactly that zone's value`() {
        Zone.entries.forEach { zone ->
            val mid = DepthBlend.zoneMidpoint(zone)
            assertEquals(
                increasing[zone.ordinal], DepthBlend.blend(mid, increasing), 0.0001f,
                "zone $zone should read as its own flat value at its own midpoint"
            )
        }
    }

    @Test
    fun `at a zone boundary, the blended value sits between the two neighbouring zones`() {
        // Every Zone in this game spans exactly 30m, so boundary depths sit exactly halfway
        // between the two zones' midpoints and the blend must be an exact 50/50 average.
        val zones = Zone.entries
        for (i in 1 until zones.size) {
            val boundaryDepth = zones[i].minDepth
            val expected = (increasing[i - 1] + increasing[i]) / 2f
            assertEquals(
                expected, DepthBlend.blend(boundaryDepth, increasing), 0.01f,
                "boundary at ${boundaryDepth}m between ${zones[i - 1]} and ${zones[i]} should be a 50/50 blend"
            )
        }
    }

    @Test
    fun `there is no discontinuity at a zone boundary`() {
        // The old bug: values[idx] flips the instant Zone.at() returns a different zone.
        // Sampling a hair either side of every boundary must produce nearly identical values.
        val zones = Zone.entries
        for (i in 1 until zones.size) {
            val boundary = zones[i].minDepth
            val justAbove = DepthBlend.blend(boundary - 0.01f, increasing)
            val justBelow = DepthBlend.blend(boundary + 0.01f, increasing)
            assertTrue(
                kotlin.math.abs(justAbove - justBelow) < 0.01f,
                "boundary at ${boundary}m jumped from $justAbove to $justBelow across a 0.02m step"
            )
        }
    }

    @Test
    fun `the curve is monotonic when the underlying per-zone table is monotonic`() {
        var previous = Float.NEGATIVE_INFINITY
        var depth = 0f
        while (depth <= 160f) {
            val value = DepthBlend.blend(depth, increasing)
            assertTrue(value >= previous - 0.0001f, "value dipped at depth $depth: $previous -> $value")
            previous = value
            depth += 0.5f
        }
    }

    @Test
    fun `depth above the shallowest zone's midpoint clamps rather than extrapolating`() {
        val shallowestMidpoint = DepthBlend.zoneMidpoint(Zone.entries.first())
        assertEquals(
            increasing[0], DepthBlend.blend(shallowestMidpoint - 100f, increasing), 0.0001f,
            "far above the shallowest zone's midpoint must clamp to its flat value"
        )
    }

    @Test
    fun `depth below the deepest zone's midpoint clamps rather than extrapolating`() {
        val deepestMidpoint = DepthBlend.zoneMidpoint(Zone.entries.last())
        assertEquals(
            increasing.last(), DepthBlend.blend(deepestMidpoint + 100f, increasing), 0.0001f,
            "far below the deepest zone's midpoint must clamp to its flat value"
        )
    }
}
