package dive

import render.Framing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuningTest {
    @Test
    fun `run length is 90 seconds`() {
        assertEquals(90f, Tuning.RUN_SECONDS)
    }

    @Test
    fun `base air is 30 seconds`() {
        // Raised from 20f after the first play session — 20s was too little air.
        assertEquals(30f, Tuning.BASE_AIR_SECONDS)
    }

    @Test
    fun `blackout keeps ten percent`() {
        assertEquals(0.10f, Tuning.BLACKOUT_KEEP)
    }

    /**
     * THE PICKUP CIRCLE AND THE DRAWN DIVER HAVE TO AGREE, and this is the only place the two
     * can be compared: `dive/` is pure simulation and must not import `render/`, so the coupling
     * exists nowhere in production and would drift silently. It has drifted twice already —
     * `Framing.DIVER_HEIGHT_METRES` went 3 -> 6 -> 9 while [Tuning.PEARL_PICKUP_RADIUS] sat at
     * 2.5, which is how a pearl came to be able to touch the diver's mask without collecting.
     * `Framing`'s own doc asks in prose for this radius to be re-checked whenever the height
     * moves; this is that request written as a failing build.
     *
     * Deliberately a TWO-SIDED squeeze, because both directions are real faults:
     *
     *  - too small and there is a DEAD BAND at his head and his fins, on the swim axis, exactly
     *    where the player aims. That is the bad one: it reads as a bug rather than as a rule.
     *  - too large and the circle collects pearls in open water off his flank, and aiming stops
     *    mattering.
     *
     * The bounds are the drawn figure itself, so neither is a taste judgement:
     *
     *  - the dead band left over must be shorter than the PEARL'S OWN RADIUS. A pearl is
     *    `Framing.PEARL_SIZE_METRES` across, so this says every pearl the game still refuses is
     *    one whose centre is past the end of him AND which is more than half outside his body.
     *    You cannot do better than "half outside" with a circle at all: the pearl's box still
     *    overlaps him out to 5.05 m, and a radius chasing that would reach 3.6 m clear of his
     *    flank sideways.
     *  - the circle must never reach past the figure in ANY direction, which is what bounds the
     *    lateral overreach: his half-length is the largest extent he has, so a radius inside it
     *    can only ever be generous sideways by less than the length of his own body.
     *
     * Together they pin 4 to within 3.9..4.5, and the balance measurement in [Tuning] is what
     * chose 4 out of that window rather than 4.5.
     */
    @Test
    fun `the pearl pickup circle agrees with the diver that is drawn on top of it`() {
        // The sprite is a rect centred on sim.x/sim.depth — the same point pickup measures from.
        val halfLength = Framing.DIVER_HEIGHT_METRES * 0.5f
        val deadBand = halfLength - Tuning.PEARL_PICKUP_RADIUS

        assertTrue(
            deadBand < Framing.PEARL_SIZE_METRES * 0.5f,
            "the pickup circle stops ${deadBand}m short of the diver's own ends, which is more " +
            "than a pearl's ${Framing.PEARL_SIZE_METRES * 0.5f}m radius — so a pearl can sit " +
            "mostly inside his head or his fins and be refused, on the swim axis, where the " +
            "player is aiming"
        )

        assertTrue(
            Tuning.PEARL_PICKUP_RADIUS <= halfLength,
            "the pickup circle reaches ${Tuning.PEARL_PICKUP_RADIUS}m from a diver only " +
            "${halfLength}m long, so it collects pearls that touch no part of him in any " +
            "direction and aiming has stopped mattering"
        )
    }

    /**
     * A vent must be at least as forgiving as a pearl. It is the lifeline that makes the Abyss
     * reachable at all, and missing one is a dead run rather than a lost pearl.
     *
     * This used to be strictly true — 4 against 2.5 — and is now an equality, which is a
     * deliberate choice and not a drift: see [Tuning.AIR_POCKET_PICKUP_RADIUS] for why the vent
     * was left where it is. What must not happen is the pearl overtaking it, so the relationship
     * is asserted rather than either number.
     */
    @Test
    fun `a vent is never harder to hit than a pearl`() {
        assertTrue(
            Tuning.AIR_POCKET_PICKUP_RADIUS >= Tuning.PEARL_PICKUP_RADIUS,
            "a vent (${Tuning.AIR_POCKET_PICKUP_RADIUS}m) is now harder to hit than a pearl " +
            "(${Tuning.PEARL_PICKUP_RADIUS}m) — missing a vent ends the run, missing a pearl does not"
        )
    }
}
