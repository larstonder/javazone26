package dive

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * The point-of-no-return maths that drives the depth-tape marker. See
 * [DiveSim.maxSafeDepth] for why it uses [Buoyancy.verticalSpeed] rather than the old
 * (now-deleted) `Buoyancy.ascentSpeed`.
 */
class NoReturnTest {
    @Test
    fun `an empty diver at the surface can always return`() {
        val sim = DiveSim(seed = 1L)
        assertTrue(sim.canStillReturn())
    }

    @Test
    fun `a heavily loaded deep diver with little air cannot return`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(150f)
        sim.debugSetHeld(count = 5000, mass = 200f)
        sim.debugSetAir(0.5f)
        assertFalse(sim.canStillReturn())
    }

    @Test
    fun `max safe depth shrinks as the diver gets heavier`() {
        val light = DiveSim(seed = 1L)
        val heavy = DiveSim(seed = 1L)
        heavy.debugSetHeld(count = 0, mass = 120f)
        assertTrue(heavy.maxSafeDepth() < light.maxSafeDepth())
    }

    @Test
    fun `max safe depth is never negative`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 0, mass = 5000f)
        sim.debugSetAir(0.1f)
        assertTrue(sim.maxSafeDepth() >= 0f)
    }

    @Test
    fun `past the mass where sink force beats swim thrust, the diver cannot climb at all`() {
        // SWIM_THRUST / SINK_FORCE_PER_MASS = 11 / 0.06 ~= 183.3 — the mass at which
        // even a full swim-up stroke nets zero upward progress. Comfortably past that,
        // no amount of remaining air should produce a nonzero max safe depth.
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 0, mass = 250f)
        sim.debugSetAir(Tuning.BASE_AIR_SECONDS)
        assertEquals(0f, sim.maxSafeDepth(), 0.001f)
    }

    @Test
    fun `a diver who cannot climb but is already at the surface can still return`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 0, mass = 250f)   // past the no-climb mass, but depth is 0
        assertTrue(sim.canStillReturn())
    }

    @Test
    fun `max safe depth shrinks as air runs out`() {
        val fullAir = DiveSim(seed = 1L)
        val lowAir = DiveSim(seed = 1L)
        lowAir.debugSetAir(1f)
        assertTrue(lowAir.maxSafeDepth() < fullAir.maxSafeDepth())
    }

    @Test
    fun `max safe depth pins the exact formula, not just its direction`() {
        // Depth 60 sits in TWILIGHT (airBurn = 1.6), which is deliberately NOT 1.0 — at
        // airBurn 1.0, air/airBurn and air*airBurn coincide, so a divide-by-multiply
        // mutation would slip past a same-zone test unnoticed. This value only comes out
        // right if the formula is climbSpeed * (air / airBurn), computed exactly:
        //   climbSpeed = -verticalSpeed(0, -1, 1) = SWIM_THRUST = 11
        //   maxSafeDepth = 11 * (10 / 1.6) = 68.75
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(60f)
        sim.debugSetAir(10f)
        assertEquals(Zone.TWILIGHT, sim.zone, "sanity: depth 60 must be in TWILIGHT")
        assertEquals(68.75f, sim.maxSafeDepth(), 0.01f)
    }
}
