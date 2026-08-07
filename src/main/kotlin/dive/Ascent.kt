package dive

/**
 * The air arithmetic of the climb home. Pure, engine-free and zone-aware.
 *
 * WHY THIS EXISTS: [DiveSim.maxSafeDepth] used to price the whole ascent at the CURRENT
 * zone's [Zone.airBurn], with a comment calling that "the honest approximation to make from
 * where the diver is standing right now". It is not honest — it is wrong in one direction,
 * systematically, and it is wrong about the one number the game uses to teach its economy.
 *
 * A climb does not happen at the depth it starts from. It crosses into progressively cheaper
 * water: 2.5 in the Abyss, then 2.0, 1.6, 1.2, and 1.0 at the surface. Pricing all 120 m at
 * 2.5 overstates the cost of a climb from the Abyss by about 70%.
 *
 * MEASURED CONSEQUENCE (see the autopilot probe that motivated this): of 27 sampled
 * combinations of depth, air and carried mass, the point-of-no-return marker said "you cannot
 * get back" in 20 cases where the diver demonstrably could. The marker is a mercy for
 * first-timers — a faint line on the depth tape that teaches the economy without a word of
 * text — and it was telling players to turn around a whole zone early.
 *
 * The model here makes exactly one assumption: the diver climbs at a constant speed, the one
 * [Buoyancy.verticalSpeed] gives for the mass currently carried. That was already the old
 * assumption and it is the right one — it is what a player holding "up" actually gets. What
 * changes is that the air is now integrated across the zones the climb passes through, which
 * is a closed-form sum, not an estimate.
 *
 * Verified against the autopilot rather than derived and trusted: an empty diver at 120 m
 * needs 15.82 s by this model, survives a flown ascent on 20 s, and dies on 15 s. At 140 m it
 * needs 20.37 s, dies on 20 s and survives on 25 s. Both boundaries land where the flown dives
 * put them.
 */
object Ascent
{
    /**
     * The depth at which [zone] ends and the next one down begins — [Tuning.MAX_DEPTH] for
     * the deepest zone, which has no neighbour below it.
     */
    private fun floorOf(zone: Zone): Float =
        Zone.entries.getOrNull(zone.ordinal + 1)?.minDepth ?: Tuning.MAX_DEPTH

    /**
     * Seconds of air a climb from [depth] to the surface costs at [climbSpeed] metres per
     * second, charging each zone the climb passes through at its own [Zone.airBurn].
     *
     * Returns [Float.POSITIVE_INFINITY] for a diver who cannot climb at all — past roughly
     * mass 183, [Buoyancy.sinkForce] exceeds [Tuning.SWIM_THRUST] and no amount of air helps.
     * Infinity rather than a large number so callers cannot accidentally treat it as reachable.
     */
    fun airNeeded(depth: Float, climbSpeed: Float, startupSeconds: Float = 0f): Float
    {
        if (climbSpeed <= 0f) return Float.POSITIVE_INFINITY

        var remainingTop = depth.coerceAtLeast(0f)
        // The climb does not begin at full speed: the diver has to arrest a descent and
        // reverse it, and DiveSim ramps velocity at Buoyancy.responseRate rather than
        // snapping to it. Charged at the burn rate of the zone the diver turns around in,
        // which is where that time is actually spent. See maxDepthReachableOn for why this
        // matters more than its size suggests.
        var air = startupSeconds.coerceAtLeast(0f) * Zone.at(remainingTop).airBurn

        // Deepest zone first: the climb pays the expensive water before the cheap water.
        for (zone in Zone.entries.reversed())
        {
            if (remainingTop <= zone.minDepth) continue
            val segment = remainingTop - zone.minDepth
            air += segment / climbSpeed * zone.airBurn
            remainingTop = zone.minDepth
            if (remainingTop <= 0f) break
        }
        return air
    }

    /**
     * The exact inverse of [airNeeded]: the deepest point from which [air] seconds still buys
     * a climb home at [climbSpeed]. Clamped to [Tuning.MAX_DEPTH], since no part of the game
     * exists below it.
     *
     * Solved by spending the budget zone by zone from the surface downward rather than by
     * searching, so it is exact and allocation-free — [DiveSim.maxSafeDepth] recomputes this
     * every frame to place the HUD marker.
     */
    fun maxDepthReachableOn(air: Float, climbSpeed: Float, startupSeconds: Float = 0f): Float
    {
        if (climbSpeed <= 0f) return 0f

        var budget = air.coerceAtLeast(0f)
        for (zone in Zone.entries)
        {
            val costPerMetre = zone.airBurn / climbSpeed
            val span = floorOf(zone) - zone.minDepth

            // The spin-up is paid once, in whichever zone the diver actually turns around in
            // — so it is deducted when testing whether the answer lies INSIDE this zone, and
            // not from the running budget as the walk passes through.
            //
            // Small, and load-bearing anyway. Without it the model overshoots by under 1% at
            // the extreme, which sounds harmless until you notice which direction it errs in:
            // it told the diver 140 m was survivable on 20 s when a flown dive died there.
            // This marker exists to be trusted by someone who has never played before, so an
            // optimistic edge is the one kind of wrong it must not be.
            val usable = budget - startupSeconds.coerceAtLeast(0f) * zone.airBurn
            val affordable = (usable / costPerMetre).coerceAtLeast(0f)
            if (affordable < span) return (zone.minDepth + affordable).coerceAtMost(Tuning.MAX_DEPTH)

            budget -= span * costPerMetre
        }
        return Tuning.MAX_DEPTH
    }
}
