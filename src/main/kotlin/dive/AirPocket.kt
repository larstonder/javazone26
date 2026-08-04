package dive

import kotlin.random.Random

/**
 * A vent of rising air. Swimming into one refills the diver's breath.
 *
 * WHY THIS EXISTS: without vents the deep zones are unreachable-and-returnable. Descending
 * to the abyss costs ~29 of the 30 second air supply, so you arrive with nothing left for the
 * slower loaded climb home. Kicking does not help — it triples speed and burn together, so
 * air-per-metre is unchanged. Depth was a hard wall rather than a risk.
 *
 * Vents turn that wall into a route-planning problem: knowing where the next one sits is what
 * lets a good player push a zone deeper than a novice.
 *
 * ONE USE PER DIVE. Surfacing resets them, which is what stops a vent being camped or farmed
 * within a single breath. There is no yo-yo exploit either, because surfacing already banks
 * and refills air anyway.
 */
class AirPocket(val x: Float, val depth: Float)
{
    var usedThisDive = false

    val zone get() = Zone.at(depth)
}

object AirPocketField
{
    /**
     * One vent per zone down to the trench, and deliberately NONE in the abyss — the abyss
     * is the gamble you take on whatever breath you arrive with. That keeps the deepest,
     * most valuable water genuinely dangerous while making it possible to get there at all.
     */
    private val ZONES_WITH_VENTS = listOf(Zone.KELP, Zone.TWILIGHT, Zone.TRENCH)

    fun generate(seed: Long): List<AirPocket>
    {
        // Offset the seed so vent placement does not correlate with pearl placement.
        val rng = Random(seed * 31 + 7)
        return ZONES_WITH_VENTS.map { zone ->
            val span = nextZoneDepth(zone) - zone.minDepth
            // Sit the vent in the middle half of its zone, away from the boundaries, so it
            // reads as belonging to that depth band.
            val depth = zone.minDepth + span * (0.25f + rng.nextFloat() * 0.5f)
            val x = (rng.nextFloat() * 2f - 1f) * Tuning.COLUMN_HALF_WIDTH * 0.8f
            AirPocket(x, depth)
        }
    }

    private fun nextZoneDepth(zone: Zone): Float =
        Zone.entries.getOrNull(zone.ordinal + 1)?.minDepth ?: Tuning.MAX_DEPTH
}
