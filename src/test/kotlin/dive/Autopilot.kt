package dive

/**
 * A scripted pilot that flies a real [DiveSim] through a route, so balance claims can be
 * MEASURED rather than argued from the formulas.
 *
 * This exists because every previous balance number in this project came from algebra on
 * `Buoyancy` and every one of them was wrong by a wide margin — the formulas ignore the
 * velocity ramp (`RESPONSE_RATE`), the lateral detour to a vent, and the fact that the
 * air burn is sampled at the zone the diver is IN, not averaged over the climb. A dive
 * that actually surfaces alive is the only proof that a dive can surface alive.
 *
 * The steering is deliberately a plain proportional controller rather than anything
 * clever: it approximates a competent human, not a solver. Full thrust until within
 * [STEER_SCALE] metres of the waypoint, then ease off so the diver settles instead of
 * oscillating. A perfect player would beat it by a little; that makes every survivability
 * number it produces a LOWER bound, which is the safe direction to be wrong in.
 */
data class Waypoint(
    val x: Float,
    val depth: Float,
    /** How close counts as arrived. Defaults to the pearl pickup radius. */
    val reach: Float = Tuning.PEARL_PICKUP_RADIUS,
    val kick: Boolean = false,
    /**
     * Seconds to loiter on arrival, holding station with neutral stick. This is how
     * "how long can you afford to be down there?" gets measured: the search time a real
     * player spends hunting a pearl in the dark is loiter, and it is the only thing that
     * makes the anglerfish's 1.2 m/s drift able to close any distance at all.
     */
    val hold: Float = 0f,
    /**
     * Runs once, on the tick the diver arrives. Used to inject a load at depth
     * ("what if I were holding 32 mass right here?") without needing a pearl to exist there.
     */
    val onArrive: (DiveSim) -> Unit = {}
)

/** Everything about a flown dive that a balance assertion might want to look at. */
data class DiveLog(
    val outcome: DiveOutcome?,
    val banked: Int,
    val maxDepth: Float,
    /** Seconds of sim time from the first tick to the tick the dive closed out. */
    val elapsed: Float,
    /** Air remaining at the deepest point of the dive — the margin the ascent had to spend. */
    val airAtDeepest: Float,
    /** Seconds spent at or below [Zone.ABYSS]'s ceiling. Drives the anglerfish reachability check. */
    val abyssDwell: Float,
    /** Lowest air the diver ever saw. Zero means a blackout. */
    val minAir: Float,
    /** Heaviest load carried at any point — how much the column added on top of the target pearl. */
    val peakHeldMass: Float,
    /** Seconds the sim claimed the diver was past the point of no return. */
    val secondsPastNoReturn: Float,
    /** True if the route ran out of waypoints and the pilot never reached the surface. */
    val timedOut: Boolean
)
{
    val survived get() = outcome == DiveOutcome.SURFACED
}

object Autopilot
{
    /** The sim's fixed tick, matching what `EnPustTil` drives it at. */
    const val DT = 1f / 60f

    /** Distance at which the pilot starts easing off full thrust, in metres. */
    private const val STEER_SCALE = 2f

    /**
     * Flies [waypoints] in order and then swims for the surface. Returns as soon as the
     * dive closes out (surfaced or blacked out), or when [maxSeconds] of sim time elapse.
     */
    fun fly(
        sim: DiveSim,
        waypoints: List<Waypoint>,
        maxSeconds: Float = Tuning.RUN_SECONDS,
        kickHome: Boolean = false,
        /**
         * Play the designed safety valve: dump ballast whenever the sim itself says the
         * diver is past the point of no return. This is exactly what the HUD marker tells
         * a player to do, so a pilot that obeys it measures whether the game's own advice
         * actually saves you.
         */
        bleedToSafety: Boolean = false
    ): DiveLog
    {
        var index = 0
        var elapsed = 0f
        var abyssDwell = 0f
        var minAir = sim.air
        var airAtDeepest = sim.air
        var deepestSeen = sim.depth
        var holdLeft = 0f
        var peakHeldMass = 0f
        var pastNoReturn = 0f

        while (elapsed < maxSeconds)
        {
            val heading = waypoints.getOrNull(index)
            val targetX = heading?.x ?: 0f
            val targetDepth = heading?.depth ?: 0f
            val kick = heading?.kick ?: kickHome

            val bleed = bleedToSafety && !sim.canStillReturn()

            // Loitering holds station with a neutral stick, which is what a player hunting
            // for a pearl in the dark actually does. An empty diver hovers there; a laden
            // one sinks, and that is a real part of the cost of stopping to look.
            val input = if (holdLeft > 0f) DiveInput(0f, 0f, kick = false, bleed = bleed) else DiveInput(
                horizontal = ((targetX - sim.x) / STEER_SCALE).coerceIn(-1f, 1f),
                vertical = ((targetDepth - sim.depth) / STEER_SCALE).coerceIn(-1f, 1f),
                kick = kick,
                bleed = bleed
            )

            sim.tick(DT, input)
            elapsed += DT

            if (sim.depth > deepestSeen) { deepestSeen = sim.depth; airAtDeepest = sim.air }
            if (sim.air < minAir) minAir = sim.air
            if (sim.heldMass > peakHeldMass) peakHeldMass = sim.heldMass
            if (sim.depth >= Zone.ABYSS.minDepth) abyssDwell += DT
            if (!sim.canStillReturn()) pastNoReturn += DT

            sim.diveEnded?.let { ended ->
                return DiveLog(
                    ended.outcome, ended.banked, ended.depth, elapsed,
                    airAtDeepest, abyssDwell, minAir, peakHeldMass, pastNoReturn, timedOut = false
                )
            }

            if (holdLeft > 0f)
            {
                holdLeft -= DT
                if (holdLeft <= 0f) index++
            }
            else if (heading != null)
            {
                val dx = heading.x - sim.x
                val dy = heading.depth - sim.depth
                if (dx * dx + dy * dy <= heading.reach * heading.reach)
                {
                    heading.onArrive(sim)
                    if (heading.hold > 0f) holdLeft = heading.hold else index++
                }
            }
        }

        return DiveLog(
            null, 0, deepestSeen, elapsed,
            airAtDeepest, abyssDwell, minAir, peakHeldMass, pastNoReturn, timedOut = true
        )
    }

    /**
     * The vent ladder for a seed, deepest-last. The expert route threads all three on the
     * way down; §6b calls this "a ladder: each vent lets you push one zone deeper".
     */
    fun ventRoute(sim: DiveSim): List<Waypoint> =
        sim.airPockets
            .sortedBy { it.depth }
            .map { Waypoint(it.x, it.depth, reach = Tuning.AIR_POCKET_PICKUP_RADIUS * 0.6f) }

    /**
     * Marks every pearl in [sim] collected except [keep], and returns the sim.
     *
     * Any measurement of "can ONE pearl come home?" is meaningless without this. The pilot
     * flies a straight line to the surface and [DiveSim.collectPearls] hoovers up anything
     * within the pickup radius on the way, so an unfiltered run silently measures "can five
     * pearls come home?" instead — which is a different and much harder question. This was
     * a real error in the first pass of these measurements.
     */
    fun clearColumn(sim: DiveSim, keep: Pearl? = null): DiveSim
    {
        sim.pearls.forEach { it.collected = it !== keep }
        return sim
    }

    /**
     * The heaviest load a diver can pick up at [depth] and still surface alive, in mass
     * units, flying the full vent ladder down an otherwise empty column. Returns -1 if even
     * an empty diver cannot get home from there.
     *
     * Searched linearly rather than by bisection because survivability is not guaranteed
     * to be monotonic in mass — more mass sinks you faster on the descent, which can
     * change how tightly the pilot clips a vent — and a wrong answer here is exactly the
     * class of bug this whole exercise exists to fix.
     */
    fun maxSurvivableLoad(seed: Long, depth: Float, ceiling: Int = 90): Int
    {
        var best = -1
        for (mass in 0..ceiling)
        {
            val sim = clearColumn(DiveSim(seed))
            val route = ventRoute(sim) + Waypoint(0f, depth, onArrive = { it.debugSetHeld(1, mass.toFloat()) })
            if (fly(sim, route).survived) best = mass
        }
        return best
    }
}
