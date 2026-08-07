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

    /**
     * Where in its zone a vent sits, as a fraction of the zone's span: start of the band,
     * and how wide the band is. Away from the boundaries either way, so a vent reads as
     * belonging to one depth band rather than straddling two.
     */
    private const val BAND_START = 0.25f
    private const val BAND_WIDTH = 0.5f

    /**
     * The DEEPEST vent gets its own, lower band — it is the staging post for the plunge, not
     * just another rung.
     *
     * MEASURED, not chosen for flavour. With the trench vent in the ordinary middle band a
     * flown dive arrives at 135 m with 21.6 s of air, and the heaviest load that can climb
     * home from there is 8 mass — less than the 16 of a single abyss pearl. Only 4 of the 14
     * abyss pearls could be brought home at all, and the whole zone below 133 m was
     * decoration. Pushing this one vent into the lower half of the Trench buys roughly 3
     * seconds of arrival air, which is what turns the shallow two-thirds of the Abyss from
     * unwinnable into a genuine gamble.
     *
     * Deliberately does NOT put a vent in the Abyss — see [ZONES_WITH_VENTS]. The last breath
     * you get is still taken above the drop, and everything below it is still spent on what
     * you carried down.
     */
    private const val DEEP_BAND_START = 0.55f
    private const val DEEP_BAND_WIDTH = 0.35f

    fun generate(seed: Long): List<AirPocket>
    {
        // Offset the seed so vent placement does not correlate with pearl placement.
        val rng = Random(seed * 31 + 7)
        val deepest = ZONES_WITH_VENTS.last()
        return ZONES_WITH_VENTS.map { zone ->
            val span = nextZoneDepth(zone) - zone.minDepth
            val start = if (zone == deepest) DEEP_BAND_START else BAND_START
            val width = if (zone == deepest) DEEP_BAND_WIDTH else BAND_WIDTH
            val depth = zone.minDepth + span * (start + rng.nextFloat() * width)
            val x = (rng.nextFloat() * 2f - 1f) * Tuning.COLUMN_HALF_WIDTH * 0.8f
            AirPocket(x, depth)
        }
    }

    private fun nextZoneDepth(zone: Zone): Float =
        Zone.entries.getOrNull(zone.ordinal + 1)?.minDepth ?: Tuning.MAX_DEPTH
}
