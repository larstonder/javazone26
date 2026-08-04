package dive

import kotlin.random.Random

/**
 * A pearl persists where it is until collected. That is what makes the ascent
 * the interesting half of a dive — you deliberately leave pearls on the way
 * down to sweep up on the way home.
 */
class Pearl(val x: Float, val depth: Float, val zone: Zone)
{
    var collected = false

    val value get() = zone.pearlValue
    val mass  get() = zone.pearlMass
}

/**
 * Seeded generator for the hand-authorable water column. Placeholder for now:
 * a fixed count per zone at seeded positions. Replaced later by scene-editor
 * authored layouts, but the seeded version keeps every player on an identical
 * column, which is the score-attack pillar.
 */
object PearlColumn
{
    private const val PEARLS_PER_ZONE = 14

    fun generate(seed: Long): MutableList<Pearl>
    {
        val rng = Random(seed)
        val pearls = ArrayList<Pearl>(Zone.entries.size * PEARLS_PER_ZONE)

        Zone.entries.forEach { zone ->
            val top = zone.minDepth
            val bottom = nextZoneDepth(zone)
            repeat(PEARLS_PER_ZONE) {
                val depth = top + rng.nextFloat() * (bottom - top)
                val x = (rng.nextFloat() * 2f - 1f) * Tuning.COLUMN_HALF_WIDTH
                pearls += Pearl(x, depth, Zone.at(depth))
            }
        }
        return pearls
    }

    private fun nextZoneDepth(zone: Zone): Float
    {
        val next = Zone.entries.getOrNull(zone.ordinal + 1)
        return next?.minDepth ?: Tuning.MAX_DEPTH
    }
}
