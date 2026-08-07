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
        // This used to pin `climbSpeed * (air / zone.airBurn)` = 68.75, the single-rate model.
        // That model was wrong — see Ascent — so the number it pinned is gone with it, and
        // the replacement pins the zone-integrated one just as exactly. Spending 10 s of air
        // at a climb speed of 11 m/s, paying each zone's own rate on the way up:
        //   SHALLOWS  0-30m  @1.0 -> 30/11 * 1.0 = 2.727 s, leaving 7.273
        //   KELP     30-60m  @1.2 -> 30/11 * 1.2 = 3.273 s, leaving 4.000
        //   TWILIGHT 60-90m  @1.6 -> the remainder, less one spin-up of 1/3.5 s at 1.6:
        //                            (4.000 - 0.457) / (1.6/11) = 24.36 m into the zone
        //   maxSafeDepth = 60 + 24.36 = 84.36
        //
        // TWILIGHT is still where the answer lands on purpose: at airBurn 1.0 a
        // divide-by-multiply mutation coincides and would slip past unnoticed.
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(60f)
        sim.debugSetAir(10f)
        assertEquals(Zone.TWILIGHT, sim.zone, "sanity: depth 60 must be in TWILIGHT")
        assertEquals(84.36f, sim.maxSafeDepth(), 0.02f)
    }
}
