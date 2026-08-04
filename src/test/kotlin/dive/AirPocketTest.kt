package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AirPocketTest
{
    private val idle = DiveInput.NONE

    @Test
    fun `same seed produces the same vents`() {
        val a = AirPocketField.generate(1L)
        val b = AirPocketField.generate(1L)
        assertEquals(a.size, b.size)
        a.indices.forEach {
            assertEquals(a[it].x, b[it].x, 0.0001f)
            assertEquals(a[it].depth, b[it].depth, 0.0001f)
        }
    }

    @Test
    fun `there is a vent in kelp twilight and trench`() {
        val zones = AirPocketField.generate(1L).map { it.zone }.toSet()
        assertEquals(setOf(Zone.KELP, Zone.TWILIGHT, Zone.TRENCH), zones)
    }

    @Test
    fun `there is deliberately no vent in the abyss`() {
        assertTrue(
            AirPocketField.generate(1L).none { it.zone == Zone.ABYSS },
            "the abyss must stay a gamble on the breath you arrive with"
        )
    }

    @Test
    fun `vents sit within the column bounds`() {
        AirPocketField.generate(3L).forEach {
            assertTrue(it.x in -Tuning.COLUMN_HALF_WIDTH..Tuning.COLUMN_HALF_WIDTH)
            assertTrue(it.depth in 0f..Tuning.MAX_DEPTH)
        }
    }

    @Test
    fun `vent placement does not correlate with pearl placement`() {
        // Both are seeded from the same run seed; they must not land on top of each other.
        val vents = AirPocketField.generate(1L)
        val pearls = PearlColumn.generate(1L)
        assertTrue(vents.none { v -> pearls.any { p -> p.x == v.x && p.depth == v.depth } })
    }

    @Test
    fun `swimming into a vent refills the breath`() {
        val sim = DiveSim(seed = 1L)
        val vent = sim.airPockets.first()
        sim.debugMoveTo(vent.x, vent.depth)
        sim.debugSetAir(2f)

        sim.tick(1f / 60f, idle)

        assertTrue(sim.air > 25f, "air should be refilled, was ${sim.air}")
        assertTrue(vent.usedThisDive)
    }

    @Test
    fun `a vent only works once per dive`() {
        val sim = DiveSim(seed = 1L)
        val vent = sim.airPockets.first()
        sim.debugMoveTo(vent.x, vent.depth)
        sim.debugSetAir(2f)
        sim.tick(1f / 60f, idle)

        // Sit on the spent vent and let air drain.
        sim.debugMoveTo(vent.x, vent.depth)
        sim.debugSetAir(2f)
        repeat(30) { sim.tick(1f / 60f, idle) }

        assertTrue(sim.air < 2f, "a spent vent must not keep topping you up — that is camping")
    }

    @Test
    fun `surfacing rearms every vent for the next dive`() {
        val sim = DiveSim(seed = 1L)
        val vent = sim.airPockets.first()
        sim.debugMoveTo(vent.x, vent.depth)
        sim.debugSetAir(2f)
        sim.tick(1f / 60f, idle)
        assertTrue(vent.usedThisDive)

        sim.debugSurface()

        assertFalse(vent.usedThisDive, "a new dive must get the vents back")
    }

    @Test
    fun `a vent reached on the dying breath still saves the diver`() {
        val sim = DiveSim(seed = 1L)
        val vent = sim.airPockets.first()
        sim.debugMoveTo(vent.x, vent.depth)
        sim.debugSetHeld(count = 900, mass = 0f)
        sim.debugSetAir(0.001f)   // would black out this very tick

        sim.tick(1f / 60f, idle)

        assertEquals(900, sim.held, "the vent must be checked before air burns, not after")
        assertEquals(0, sim.banked, "no blackout bank should have happened")
    }

    @Test
    fun `the trench is now reachable and returnable`() {
        // The whole point of the mechanic. Descend to the trench vent, refill, come back.
        val sim = DiveSim(seed = 1L)
        sim.pearls.forEach { it.collected = true }
        val trenchVent = sim.airPockets.first { it.zone == Zone.TRENCH }

        sim.debugMoveTo(trenchVent.x, trenchVent.depth)
        sim.debugSetAir(1f)
        sim.tick(1f / 60f, idle)          // refill at the vent

        // Now swim all the way up on that breath.
        val up = DiveInput(0f, -1f, kick = false, bleed = false)
        var ticks = 0
        while (sim.depth > 0.5f && ticks < 60 * 60) { sim.tick(1f / 60f, up); ticks++ }

        assertTrue(sim.depth <= 0.5f, "diver failed to reach the surface from the trench vent")
        assertFalse(sim.blackedOut, "the ascent from a refilled trench vent must be survivable")
    }
}
