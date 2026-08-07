package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The point-of-no-return marker is a mercy for first-timers — a faint line on the depth tape
 * that teaches the economy without a word of text. It is only a mercy if it is true.
 *
 * Everything here is measured against a flown [DiveSim] via [Autopilot] rather than argued
 * from the formulas, because every previous balance number in this project came from algebra
 * on `Buoyancy` and every one of them was wrong.
 */
class AscentTest
{
    private val CLIMB = 11f // an empty diver's climb speed: SWIM_THRUST with no load

    @Test
    fun `a climb is charged at each zone's own burn rate, not the starting zone's`() {
        // From 120 m the climb crosses Trench, Twilight, Kelp and Shallows, whose burn rates
        // are 2.0, 1.6, 1.2 and 1.0. Four 30 m segments at 11 m/s is 2.727 s each:
        //   2.727 * (2.0 + 1.6 + 1.2 + 1.0) = 15.82 s
        // The old model charged all 120 m at the Abyss's 2.5, giving 27.3 s — a 70% overstate,
        // which is what made the marker order players out of the deep a whole zone early.
        assertEquals(15.82f, Ascent.airNeeded(120f, CLIMB), 0.01f)
    }

    @Test
    fun `descending into a more expensive zone costs more than the zone above it`() {
        // 140 m adds 20 m of Abyss on top of the 120 m climb: 20/11 * ABYSS.airBurn.
        val expected = 20f / CLIMB * Zone.ABYSS.airBurn
        val fromAbyss = Ascent.airNeeded(140f, CLIMB)
        val fromCeiling = Ascent.airNeeded(120f, CLIMB)
        assertEquals(expected, fromAbyss - fromCeiling, 0.02f)
    }

    @Test
    fun `maxDepthReachableOn inverts airNeeded`() {
        // The marker's placement and the cost of the climb have to be the same function read
        // in opposite directions, or the line on the tape means nothing.
        listOf(8f, 12f, 16f, 20f, 25f, 30f, 45f).forEach { air ->
            val depth = Ascent.maxDepthReachableOn(air, CLIMB)
            if (depth < Tuning.MAX_DEPTH)
                assertEquals(air, Ascent.airNeeded(depth, CLIMB), 0.05f, "round trip failed at air=$air")
        }
    }

    @Test
    fun `a diver too laden to climb can reach no depth at all`() {
        // Past roughly mass 183 the sink force exceeds SWIM_THRUST. No amount of air helps,
        // and the answer must be zero rather than a negative number or a divide-by-zero.
        assertEquals(0f, Ascent.maxDepthReachableOn(air = 30f, climbSpeed = 0f))
        assertTrue(Ascent.airNeeded(100f, climbSpeed = 0f).isInfinite())
    }

    @Test
    fun `the marker is never optimistic - if it says you can get home, you can`() {
        // THE INVARIANT THIS FILE EXISTS FOR, and the one direction of error that matters:
        // a marker that under-promises costs a player some depth, while one that
        // over-promises kills a run that trusted it.
        //
        // Measured by flying the ascent, not by re-deriving it. Before the zone-aware rework
        // this assertion failed the other way round in 20 of these 27 cases (the marker said
        // "no" where a flown dive said "yes"); the startup term in Ascent then closed the last
        // case, at 140 m on 20 s, where it had said "yes" and the diver died.
        var checked = 0
        for (depth in listOf(60f, 90f, 110f, 120f, 130f, 140f, 150f))
            for (air in listOf(10f, 15f, 20f, 25f, 30f))
                for (mass in listOf(0f, 16f, 32f, 64f))
                {
                    val sim = Autopilot.clearColumn(DiveSim(20260902L))
                    sim.debugSetDepth(depth)
                    sim.debugSetAir(air)
                    sim.debugSetHeld(1, mass)

                    if (!sim.canStillReturn()) continue
                    checked++
                    assertTrue(
                        Autopilot.fly(sim, emptyList()).survived,
                        "marker promised a way home from ${depth}m on ${air}s carrying $mass, and the dive died"
                    )
                }
        assertTrue(checked > 20, "only $checked cases were promised safe — the grid has stopped exercising this")
    }

    @Test
    fun `at least one abyss pearl can be brought home, on every seed`() {
        // "Make the abyss reachable." Before the deeper trench vent and the 2.2 burn, seed 99
        // yielded exactly one reachable abyss pearl and nothing below 121 m; six of fourteen
        // sat permanently out of reach on the day-one seed. The Abyss is meant to be a gamble,
        // not decoration.
        //
        // One pearl at a time down an otherwise empty column: the pilot flies a straight line
        // home and DiveSim.collectPearls hoovers up whatever it passes, so an unfiltered run
        // silently measures "can FIVE pearls come home?" instead — a different, much harder
        // question, and a real error in the first pass of these measurements.
        listOf(20260902L, 20260903L, 777L, 4242L, 31337L, 8L, 99L).forEach { seed ->
            val total = DiveSim(seed).pearls.count { it.zone == Zone.ABYSS }
            var home = 0
            for (i in 0 until total)
            {
                val sim = Autopilot.clearColumn(DiveSim(seed))
                val pearl = sim.pearls.filter { it.zone == Zone.ABYSS }.sortedBy { it.depth }[i]
                pearl.collected = false
                val log = Autopilot.fly(sim, Autopilot.ventRoute(sim) + Waypoint(pearl.x, pearl.depth))
                // banked > 100 rules out surfacing with only the 40-point consolation of a
                // blackout bank: this has to be the abyss pearl, which is worth 400 plus depth.
                if (log.survived && log.banked > 100) home++
            }
            assertTrue(home >= 2, "seed $seed: only $home of its $total abyss pearls can be brought home")
        }
    }

    @Test
    fun `one abyss pearl can be carried home from 135 metres`() {
        // The sharp edge of "reachable", and the assertion that actually pins the balance.
        //
        // Flying the vent ladder puts a diver at 135 m with about 23 s of air. Before this
        // rework the heaviest load that could climb home from there was 14 mass — less than
        // the 16 of a single abyss pearl — so 135 m was a place you could reach, look at a
        // pearl, and leave empty-handed. Everything below 133 m was decoration.
        //
        // Two changes bought the margin and this test is what holds BOTH in place: the
        // deepest vent moved into the lower Trench (more air on arrival, see AirPocketField)
        // and the Abyss burn came down from 2.5 to 2.2 (a cheaper climb out, see Zone).
        // Reverting either one on its own puts the answer back under 16.
        val sim = Autopilot.clearColumn(DiveSim(20260902L))
        val route = Autopilot.ventRoute(sim) +
            Waypoint(0f, 135f, onArrive = { it.debugSetHeld(1, Zone.ABYSS.pearlMass) })

        val log = Autopilot.fly(sim, route)
        assertTrue(
            log.survived,
            "could not carry one ${Zone.ABYSS.pearlMass}-mass pearl home from 135 m " +
            "(arrived with ${"%.1f".format(log.airAtDeepest)} s of air)"
        )
    }

    @Test
    fun `the abyss still refuses its deepest water`() {
        // The other half of the bargain. If everything came home the zone would be a farm
        // rather than a gamble, and the point-of-no-return marker would have nothing to teach.
        val sim = Autopilot.clearColumn(DiveSim(20260902L))
        val deepest = sim.pearls.filter { it.zone == Zone.ABYSS }.maxBy { it.depth }
        deepest.collected = false
        assertTrue(deepest.depth > 150f, "this seed's deepest abyss pearl moved; pick another anchor")

        val log = Autopilot.fly(sim, Autopilot.ventRoute(sim) + Waypoint(deepest.x, deepest.depth))
        assertTrue(!log.survived || log.banked <= 100, "the deepest water must still be a trap")
    }
}
