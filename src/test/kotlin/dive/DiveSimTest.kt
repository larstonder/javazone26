package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DiveSimTest {
    private val idle = DiveInput(horizontal = 0f, vertical = 0f, kick = false, bleed = false)
    private val swimUp = idle.copy(vertical = -1f)
    private val swimDown = idle.copy(vertical = 1f)

    private fun run(sim: DiveSim, seconds: Float, input: DiveInput = idle) {
        val dt = 1f / 60f
        repeat((seconds / dt).toInt()) { sim.tick(dt, input) }
    }

    @Test
    fun `diver starts at the surface with full air and nothing held`() {
        val sim = DiveSim(seed = 1L)
        assertEquals(0f, sim.depth, 0.001f)
        assertEquals(Tuning.BASE_AIR_SECONDS, sim.air, 0.001f)
        assertEquals(0, sim.held)
        assertEquals(0, sim.banked)
    }

    @Test
    fun `diver sinks passively`() {
        val sim = DiveSim(seed = 1L)
        run(sim, 1f)
        assertTrue(sim.depth > 0f, "diver should sink without input")
    }

    @Test
    fun `kick makes the diver descend faster`() {
        val slow = DiveSim(seed = 1L)
        val fast = DiveSim(seed = 1L)
        run(slow, 1f, swimDown)
        run(fast, 1f, swimDown.copy(kick = true))
        assertTrue(fast.depth > slow.depth)
    }

    @Test
    fun `neutral stick sinks but kick does nothing without a direction`() {
        val drifting = DiveSim(seed = 1L)
        val kicking = DiveSim(seed = 1L)
        run(drifting, 1f, idle)
        run(kicking, 1f, idle.copy(kick = true))
        assertEquals(drifting.depth, kicking.depth, 0.001f)
    }

    @Test
    fun `kick burns air faster`() {
        val slow = DiveSim(seed = 1L)
        val fast = DiveSim(seed = 1L)
        run(slow, 1f, swimDown)
        run(fast, 1f, swimDown.copy(kick = true))
        assertTrue(fast.air < slow.air)
    }

    @Test
    fun `max depth this dive is tracked`() {
        val sim = DiveSim(seed = 1L)
        run(sim, 2f, swimDown.copy(kick = true))
        assertTrue(sim.maxDepthThisDive >= sim.depth)
    }

    @Test
    fun `running out of air blacks out and banks ten percent`() {
        val sim = DiveSim(seed = 1L)
        sim.pearls.forEach { it.collected = true }   // isolate: no pickups en route
        sim.debugSetHeld(count = 2400, mass = 0f)
        run(sim, 40f, swimDown.copy(kick = true))
        assertEquals(0, sim.held, "held must be cleared after blackout")
        assertEquals(240, sim.banked, "blackout banks exactly 10% with no depth bonus")
    }

    @Test
    fun `surfacing banks held times depth bonus and refills air`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(120f)
        sim.debugSetHeld(count = 2400, mass = 0f)
        sim.debugSurface()
        assertEquals(12000, sim.banked)
        assertEquals(0, sim.held)
        assertEquals(Tuning.BASE_AIR_SECONDS, sim.air, 0.001f)
    }

    @Test
    fun `depth bonus resets between dives`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(120f)
        sim.debugSetHeld(count = 100, mass = 0f)
        sim.debugSurface()
        val firstBank = sim.banked

        sim.debugSetDepth(0f)
        sim.debugSetHeld(count = 100, mass = 0f)
        sim.debugSurface()
        val secondBank = sim.banked - firstBank

        assertEquals(100, secondBank, "second dive must not inherit the first dive's bonus")
    }

    @Test
    fun `held pearls are lost when the clock runs out`() {
        val sim = DiveSim(seed = 1L)
        sim.pearls.forEach { it.collected = true }   // isolate from pickups
        sim.debugSetDepth(60f)
        sim.debugSetHeld(count = 5000, mass = 0f)
        sim.debugSetClock(0.5f)                       // half a second left, mid-dive

        run(sim, 1f)                                  // tick past expiry

        assertTrue(sim.runOver)
        assertEquals(0, sim.held, "held pearls are lost, not banked")
        assertEquals(0, sim.banked, "unbanked pearls must not be scored at 0:00")
    }

    @Test
    fun `bleeding reduces held mass and value`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 1000, mass = 80f)
        run(sim, 1f, idle.copy(bleed = true))
        assertTrue(sim.heldMass < 80f, "mass must drop while bleeding")
        assertTrue(sim.held < 1000, "value must drop while bleeding")
        assertTrue(sim.held > 0, "one second must not dump everything")
    }

    @Test
    fun `bleeding cannot go below zero`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 10, mass = 1f)
        run(sim, 5f, idle.copy(bleed = true))
        assertEquals(0, sim.held)
        assertEquals(0f, sim.heldMass, 0.001f)
    }

    @Test
    fun `collecting a pearl adds value and mass`() {
        val sim = DiveSim(seed = 1L)
        val pearl = sim.pearls.first { it.zone == Zone.SHALLOWS }
        sim.debugMoveTo(pearl.x, pearl.depth)
        sim.tick(1f / 60f, idle)

        assertTrue(pearl.collected, "the pearl the diver is sitting on must be collected")

        // Pearls cluster within PEARL_PICKUP_RADIUS, so more than one may be swept
        // up in a single tick. Assert held tracks exactly what was collected.
        val collected = sim.pearls.filter { it.collected }
        assertEquals(collected.sumOf { it.value }, sim.held)
        assertEquals(collected.map { it.mass }.sum(), sim.heldMass, 0.001f)
    }

    @Test
    fun `a collected pearl cannot be collected twice`() {
        val sim = DiveSim(seed = 1L)
        val pearl = sim.pearls.first { it.zone == Zone.SHALLOWS }
        sim.debugMoveTo(pearl.x, pearl.depth)
        sim.tick(1f / 60f, idle)
        val afterFirst = sim.held
        sim.tick(1f / 60f, idle)
        assertEquals(afterFirst, sim.held)
    }

    @Test
    fun `a loaded diver ascends slower than an empty one`() {
        val empty = DiveSim(seed = 1L)
        val loaded = DiveSim(seed = 1L)
        empty.debugSetDepth(50f)
        loaded.debugSetDepth(50f)
        loaded.debugSetHeld(count = 0, mass = 80f)

        run(empty, 1f, swimUp)
        run(loaded, 1f, swimUp)

        assertTrue(loaded.depth > empty.depth, "loaded diver must still be deeper")
    }

    @Test
    fun `swimming up beats passive sink`() {
        val sinking = DiveSim(seed = 1L)
        val rising = DiveSim(seed = 1L)
        sinking.debugSetDepth(50f)
        rising.debugSetDepth(50f)

        run(sinking, 1f, idle)
        run(rising, 1f, swimUp)

        assertTrue(rising.depth < sinking.depth, "swimming up must rise against the sink")
    }

    @Test
    fun `kick boosts whichever direction is held - it is the escape tool too`() {
        val slowUp = DiveSim(seed = 1L)
        val fastUp = DiveSim(seed = 1L)
        slowUp.debugSetDepth(80f)
        fastUp.debugSetDepth(80f)

        run(slowUp, 1f, swimUp)
        run(fastUp, 1f, swimUp.copy(kick = true))

        assertTrue(fastUp.depth < slowUp.depth, "kick must accelerate an ascent, not only a descent")
    }

    @Test
    fun `kicking upward still burns air at the kick rate`() {
        val drift = DiveSim(seed = 1L)
        val kicking = DiveSim(seed = 1L)
        drift.debugSetDepth(80f)
        kicking.debugSetDepth(80f)

        run(drift, 1f, swimUp)
        run(kicking, 1f, swimUp.copy(kick = true))

        assertTrue(kicking.air < drift.air, "escaping must cost air, not be free")
    }

    @Test
    fun `run is not over before the clock expires`() {
        val sim = DiveSim(seed = 1L)
        run(sim, 10f)
        assertFalse(sim.runOver)
    }

    @Test
    fun `a blackout ends the tick - no pickup or bank after losing consciousness`() {
        val sim = DiveSim(seed = 1L)
        // A pearl sits at roughly x=-21.93, depth=2.205 — within pickup radius of depth 0.
        val shallowPearl = sim.pearls.minByOrNull { it.depth }!!
        sim.debugMoveTo(shallowPearl.x, 40f)
        sim.debugSetHeld(count = 1000, mass = 0f)
        sim.debugSetAir(0.001f)          // next tick exhausts air

        sim.tick(1f / 60f, idle)

        assertEquals(100, sim.banked, "blackout banks exactly 10% and nothing else")
        assertEquals(0, sim.held, "nothing may be collected after blacking out")
        assertTrue(!shallowPearl.collected, "a pearl must not be swept up post-blackout")
    }
}
