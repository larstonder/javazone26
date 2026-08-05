package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DiveSim.diveEnded] replaced two latching fields flagged in whole-branch review:
 *   - `blackedOut` was set true in blackout() and never reset, not even by resetDive().
 *   - `lastBankAmount` was set on every bank but never cleared, so a HUD reading it
 *     after the dive that set it would show a stale figure forever.
 *
 * These tests pin the actual bug reproduction from the review: a blackout followed by
 * a clean surface must NOT leave the sim looking like it just blacked out.
 */
class DiveEndedTest {
    private val idle = DiveInput.NONE
    private val swimUp = DiveInput(0f, -1f, kick = false, bleed = false)

    @Test
    fun `diveEnded is null before anything has happened`() {
        val sim = DiveSim(seed = 1L)
        assertNull(sim.diveEnded)
    }

    @Test
    fun `blackout fires a BLACKED_OUT event carrying the amount banked`() {
        val sim = DiveSim(seed = 1L)
        sim.pearls.forEach { it.collected = true }
        sim.debugMoveTo(sim.x, 40f)
        sim.debugSetHeld(count = 1000, mass = 0f)
        sim.debugSetAir(0.001f)   // next tick exhausts air

        sim.tick(1f / 60f, idle)

        val event = sim.diveEnded
        assertTrue(event != null, "a blackout must fire a diveEnded event")
        assertEquals(DiveOutcome.BLACKED_OUT, event!!.outcome)
        assertEquals(100, event.banked, "10% of the 1000 held")
        assertTrue(sim.blackedOut)
    }

    @Test
    fun `the event clears itself on the very next tick, whatever that tick does`() {
        val sim = DiveSim(seed = 1L)
        sim.pearls.forEach { it.collected = true }
        sim.debugMoveTo(sim.x, 40f)
        sim.debugSetHeld(count = 1000, mass = 0f)
        sim.debugSetAir(0.001f)

        sim.tick(1f / 60f, idle)              // blacks out
        assertTrue(sim.diveEnded != null)

        sim.tick(1f / 60f, idle)              // an ordinary tick, nothing dive-ending
        assertNull(sim.diveEnded, "a stale event must not survive past the tick it fired on")
        assertFalse(sim.blackedOut, "blackedOut must not latch past the tick it fired on")
    }

    @Test
    fun `a blackout followed by a later clean surface does not still read as blacked out`() {
        // This is the exact reproduction from the review: blackedOut used to latch true
        // forever after the first blackout, so a later CLEAN surface still reported
        // blackedOut = true. It must now report the truth of the most recent dive only.
        val sim = DiveSim(seed = 1L)
        sim.pearls.forEach { it.collected = true }

        // First dive: black out.
        sim.debugMoveTo(sim.x, 40f)
        sim.debugSetHeld(count = 1000, mass = 0f)
        sim.debugSetAir(0.001f)
        sim.tick(1f / 60f, idle)
        assertTrue(sim.blackedOut, "sanity: first dive must actually black out")

        // Second dive: descend a little, then surface cleanly.
        sim.debugSetHeld(count = 500, mass = 0f)
        sim.debugMoveTo(sim.x, 0.01f)
        sim.tick(1f / 60f, swimUp)   // touches the surface -> clean bank

        val event = sim.diveEnded
        assertTrue(event != null, "the clean surface must fire its own event")
        assertEquals(DiveOutcome.SURFACED, event!!.outcome, "must read as SURFACED, not still BLACKED_OUT")
        assertFalse(sim.blackedOut, "blackedOut must not still be true after a later clean surface")
    }

    @Test
    fun `surfacing fires a SURFACED event carrying the amount banked`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(120f)
        sim.debugSetHeld(count = 2400, mass = 0f)

        sim.debugSurface()

        val event = sim.diveEnded
        assertTrue(event != null)
        assertEquals(DiveOutcome.SURFACED, event!!.outcome)
        assertEquals(12000, event.banked)
        assertEquals(120f, event.depth, 0.001f)
    }
}
