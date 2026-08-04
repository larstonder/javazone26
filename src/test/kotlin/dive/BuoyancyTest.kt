package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuoyancyTest {
    @Test
    fun `empty diver hovers - zero vertical speed with no input`() {
        // The headline property of the rework: an unladen diver is neutrally buoyant.
        assertEquals(0f, Buoyancy.verticalSpeed(0f, 0f, 1f), 0.001f)
    }

    @Test
    fun `empty diver swims up briskly`() {
        val speed = Buoyancy.verticalSpeed(0f, -1f, 1f)
        assertTrue(speed < 0f, "negative is up")
        assertTrue(-speed in 8f..12f, "an unladen diver should be nimble, got ${-speed}")
    }

    @Test
    fun `mass makes an idle diver sink, and more mass sinks faster`() {
        var previous = Buoyancy.verticalSpeed(0f, 0f, 1f)
        assertEquals(0f, previous, 0.001f)
        for (mass in listOf(16f, 32f, 64f, 96f, 128f)) {
            val current = Buoyancy.verticalSpeed(mass, 0f, 1f)
            assertTrue(current > 0f, "mass=$mass must sink on its own (positive = sinking)")
            assertTrue(current > previous, "more mass must sink faster: mass=$mass gave $current, previous was $previous")
            previous = current
        }
    }

    @Test
    fun `mass 64 sinks idle in the 2 to 3 meters per second band`() {
        assertTrue(Buoyancy.verticalSpeed(64f, 0f, 1f) in 2f..3f)
    }

    @Test
    fun `mass slows the climb`() {
        // Band widened from the spec's "roughly 4-5" to "roughly 4-6": SWIM_THRUST was
        // raised from 10 to 11 during tuning specifically to give the mass-64 return from
        // the trench vent (~102m, one 30s breath) a real survival margin — see the tuning
        // report. That is the higher-priority target; this band reflects the value it forced.
        val emptyClimb = -Buoyancy.verticalSpeed(0f, -1f, 1f)
        val ladenClimb = -Buoyancy.verticalSpeed(64f, -1f, 1f)
        assertTrue(ladenClimb < emptyClimb, "carrying mass must slow the climb")
        assertTrue(ladenClimb in 4f..6f, "mass 64 should still climb, roughly 4-6 m/s, got $ladenClimb")
    }

    @Test
    fun `mass slows lateral movement too - a laden diver is sluggish in every direction`() {
        val emptyLateral = Buoyancy.lateralSpeed(0f, 1f, 1f)
        val ladenLateral = Buoyancy.lateralSpeed(64f, 1f, 1f)
        assertTrue(ladenLateral < emptyLateral, "mass must also drag down sideways speed")
        assertTrue(ladenLateral > 0f, "should still be able to move sideways, just slower")
    }

    @Test
    fun `climbing becomes impossible past the mass where sink force meets thrust`() {
        // At this mass, sinkForce(mass) == SWIM_THRUST exactly: the swim stroke and the
        // sink force cancel, so a full up-stroke nets zero vertical progress.
        val thresholdMass = Tuning.SWIM_THRUST / Tuning.SINK_FORCE_PER_MASS

        val justBelow = Buoyancy.verticalSpeed(thresholdMass - 5f, -1f, 1f)
        val justAbove = Buoyancy.verticalSpeed(thresholdMass + 5f, -1f, 1f)

        assertTrue(justBelow < 0f, "just below threshold, a full up-stroke should still climb")
        assertTrue(justAbove >= 0f, "past threshold, a full up-stroke must not net upward progress")
    }

    @Test
    fun `sink force exceeds thrust well past the threshold - no climb is possible at all`() {
        assertTrue(Buoyancy.verticalSpeed(300f, -1f, 1f) > 0f, "even a max effort up-stroke must lose to sink force")
    }

    @Test
    fun `speeds never blow up or go NaN at extreme mass`() {
        for (mass in listOf(0f, 1_000f, 100_000f, 10_000_000f)) {
            val vertical = Buoyancy.verticalSpeed(mass, -1f, 1f)
            val lateral = Buoyancy.lateralSpeed(mass, 1f, 1f)
            val rest = Buoyancy.verticalSpeed(mass, 0f, 1f)
            assertFalse(vertical.isNaN() || vertical.isInfinite(), "vertical blew up at mass=$mass: $vertical")
            assertFalse(lateral.isNaN() || lateral.isInfinite(), "lateral blew up at mass=$mass: $lateral")
            assertFalse(rest.isNaN() || rest.isInfinite(), "rest blew up at mass=$mass: $rest")
        }
    }
}
