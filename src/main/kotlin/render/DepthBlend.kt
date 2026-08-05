package render

import dive.Zone

/**
 * Smoothly interpolates a per-zone scalar (a colour channel, a light intensity, ...) across
 * depth so nothing built on it steps hard at a zone boundary.
 *
 * WHY MIDPOINTS, NOT BOUNDARIES: each zone's own value is anchored at that zone's MIDPOINT,
 * not its `minDepth`. [smoothstep] has zero derivative at both ends of its 0..1 range, so
 * the blended value barely moves near a midpoint (a zone still reads as its own distinct
 * depth if you sample it there) and moves fastest exactly halfway between two midpoints —
 * which, because every [Zone] in this game spans exactly 30m, lands almost exactly on the
 * old hard boundary. The jump the playtester saw becomes the point of fastest — but still
 * continuous — change, instead of a discontinuity.
 *
 * Pure floats in, pure float out, zero allocation: safe to call for every gradient strip in
 * [DiveRenderer]'s zone bands (hundreds of times per frame) as well as once per pearl in
 * [DiveLighting]'s light-culling loop.
 */
object DepthBlend
{
    /**
     * The midpoint of [zone]'s depth span. The deepest zone (no upper neighbour) is given
     * the same span width as the zone above it, so it still has a stable anchor rather than
     * an unbounded one.
     */
    fun zoneMidpoint(zone: Zone): Float
    {
        val zones = Zone.entries
        val next = zones.getOrNull(zone.ordinal + 1)
        val upperBound = if (next != null) next.minDepth else
        {
            val prev = zones.getOrNull(zone.ordinal - 1)
            val span = zone.minDepth - (prev?.minDepth ?: 0f)
            zone.minDepth + span
        }
        return (zone.minDepth + upperBound) * 0.5f
    }

    /**
     * Blends [values] (one entry per [Zone], indexed by [Zone.ordinal]) across [depth].
     * Depths shallower than the shallowest zone's midpoint, or deeper than the deepest
     * zone's midpoint, clamp to that zone's flat value rather than extrapolating.
     */
    fun blend(depth: Float, values: FloatArray): Float
    {
        val zones = Zone.entries
        val zone = Zone.at(depth)
        val idx = zone.ordinal
        val midpoint = zoneMidpoint(zone)

        return if (depth < midpoint)
        {
            val prevIdx = idx - 1
            if (prevIdx < 0) values[idx] else
            {
                val prevMid = zoneMidpoint(zones[prevIdx])
                val t = smoothstep(((depth - prevMid) / (midpoint - prevMid)).coerceIn(0f, 1f))
                lerp(values[prevIdx], values[idx], t)
            }
        }
        else
        {
            val nextIdx = idx + 1
            if (nextIdx >= zones.size) values[idx] else
            {
                val nextMid = zoneMidpoint(zones[nextIdx])
                val t = smoothstep(((depth - midpoint) / (nextMid - midpoint)).coerceIn(0f, 1f))
                lerp(values[idx], values[nextIdx], t)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smoothstep(t: Float): Float
    {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}
