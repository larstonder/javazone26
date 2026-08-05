package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnglerfishTest {

    @Test
    fun `lure drifts toward the diver - this is the tell`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        val startDistance = distance(fish, diverX = 20f, diverDepth = 130f)
        fish.update(dt = 1f, diverX = 20f, diverDepth = 130f)
        val endDistance = distance(fish, diverX = 20f, diverDepth = 130f)
        assertTrue(endDistance < startDistance, "lure must approach the diver")
    }

    @Test
    fun `lure does not overshoot the diver`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        repeat(100) { fish.update(dt = 1f, diverX = 1f, diverDepth = 130f) }
        assertEquals(1f, fish.x, 0.5f)
    }

    @Test
    fun `bite detection triggers within the bite radius`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        assertTrue(fish.canBite(diverX = 0f, diverDepth = 131f))
    }

    @Test
    fun `bite detection does not trigger far away`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        assertTrue(!fish.canBite(diverX = 50f, diverDepth = 130f))
    }

    @Test
    fun `a bite cannot fire again until the cooldown expires`() {
        // Without a cooldown a stationary diver sitting inside the bite radius would be
        // bitten every single tick, which is not "a chunk scatters into the dark" — it is
        // a death spiral. onBite() must actually gate canBite() until it elapses.
        val fish = Anglerfish(x = 0f, depth = 130f)
        assertTrue(fish.canBite(diverX = 0f, diverDepth = 130f), "sanity: starts in range")
        fish.onBite()
        assertFalse(fish.canBite(diverX = 0f, diverDepth = 130f), "must not re-bite immediately")

        fish.update(dt = Tuning.ANGLERFISH_COOLDOWN, diverX = 0f, diverDepth = 130f)
        assertTrue(fish.canBite(diverX = 0f, diverDepth = 130f), "cooldown must expire")
    }

    @Test
    fun `anglerfish only exists in the abyss`() {
        // The brief's version of this test only ever observed a null anglerfish that was
        // never populated in the first place, so it could not distinguish "correctly gated
        // to the abyss" from "anglerfish is always null" — it would pass even with the
        // feature entirely deleted. This version forces a real create-then-destroy cycle so
        // it can actually fail if the zone gate is removed or broken.
        val sim = DiveSim(seed = 1L)

        sim.debugSetDepth(130f)                     // Abyss
        sim.tick(1f / 60f, DiveInput.NONE)
        assertTrue(sim.anglerfish != null, "the anglerfish must exist once the diver is in the abyss")

        sim.debugSetDepth(10f)                       // Shallows
        sim.tick(1f / 60f, DiveInput.NONE)
        assertTrue(sim.anglerfish == null, "no anglerfish outside the abyss")
    }

    @Test
    fun `being bitten steals held pearls but does not end the run`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(130f)
        sim.debugSetHeld(count = 2000, mass = 40f)
        sim.debugForceBite()

        // Exact values, not just "some was taken" — pins the steal fraction itself so a
        // wrong fraction (e.g. stealing everything, or stealing nothing) is caught.
        assertEquals(1200, sim.held, "bite must steal exactly ANGLERFISH_STEAL_FRACTION of held value")
        assertEquals(24f, sim.heldMass, 0.001f, "bite must steal exactly ANGLERFISH_STEAL_FRACTION of held mass")
        assertFalse(sim.runOver, "bite must never end the run")
    }

    @Test
    fun `a bite does not happen on the same tick the diver blacks out`() {
        // This is the placement guarantee from DiveSim.tick: updateAnglerfish runs after
        // the air-burn/blackout early return, so a bite can never be resolved on a tick
        // that also blacks the diver out. If the fish updated BEFORE that early return
        // instead, held would be reduced by the bite first, and the blackout bank (10% of
        // held) would be computed on the already-diminished amount - 120, not 200.
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(130f)                              // Abyss
        sim.debugSetHeld(count = 2000, mass = 0f)
        sim.debugSpawnAnglerfish(sim.x, sim.depth)            // sitting exactly on the diver
        sim.debugSetAir(0.001f)                               // this tick blacks out

        sim.tick(1f / 60f, DiveInput.NONE)

        assertTrue(sim.blackedOut, "sanity: this tick must actually black out")
        assertEquals(200, sim.banked, "blackout bank must be computed on the pre-bite held amount")
    }

    private fun distance(fish: Anglerfish, diverX: Float, diverDepth: Float): Float {
        val dx = fish.x - diverX
        val dy = fish.depth - diverDepth
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
