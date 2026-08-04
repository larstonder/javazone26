package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuoyancyTest {
    @Test
    fun `empty diver ascends at base speed`() {
        assertEquals(Tuning.BASE_ASCENT, Buoyancy.ascentSpeed(0f), 0.001f)
    }

    @Test
    fun `mass equal to K_ASCENT halves ascent speed`() {
        val expected = Tuning.BASE_ASCENT / 2f
        assertEquals(expected, Buoyancy.ascentSpeed(Tuning.K_ASCENT), 0.001f)
    }

    @Test
    fun `ascent speed decreases monotonically with mass`() {
        var previous = Buoyancy.ascentSpeed(0f)
        for (mass in 1..200) {
            val current = Buoyancy.ascentSpeed(mass.toFloat())
            assertTrue(current < previous, "ascent must decrease at mass=$mass")
            previous = current
        }
    }

    @Test
    fun `empty diver descends at base speed`() {
        assertEquals(Tuning.BASE_DESCENT, Buoyancy.descentSpeed(0f), 0.001f)
    }

    @Test
    fun `mass increases descent speed`() {
        assertTrue(Buoyancy.descentSpeed(40f) > Buoyancy.descentSpeed(0f))
    }

    @Test
    fun `descent gain is weaker than ascent loss`() {
        val mass = 40f
        val ascentLossRatio = Buoyancy.ascentSpeed(mass) / Tuning.BASE_ASCENT
        val descentGainRatio = Tuning.BASE_DESCENT / Buoyancy.descentSpeed(mass)
        assertTrue(
            ascentLossRatio < descentGainRatio,
            "ascent must be penalised more than descent is rewarded"
        )
    }

    @Test
    fun `ascent speed never reaches zero`() {
        assertTrue(Buoyancy.ascentSpeed(100_000f) > 0f)
    }
}
