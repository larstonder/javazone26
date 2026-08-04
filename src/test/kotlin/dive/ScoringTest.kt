package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    @Test
    fun `surface dive has bonus of one`() {
        assertEquals(1f, Scoring.depthBonus(0f), 0.001f)
    }

    @Test
    fun `thirty metres gives double`() {
        assertEquals(2f, Scoring.depthBonus(30f), 0.001f)
    }

    @Test
    fun `one hundred and twenty metres gives five times`() {
        assertEquals(5f, Scoring.depthBonus(120f), 0.001f)
    }

    /** Spec section 3: the two degenerate strategies must lose by arithmetic. */
    @Test
    fun `deep and empty scores nothing`() {
        assertEquals(0, Scoring.bank(held = 0, maxDepth = 120f))
    }

    @Test
    fun `full and shallow loses badly to deep and loaded`() {
        val shallowSweep = Scoring.bank(held = 300, maxDepth = 30f)
        val deepLoaded = Scoring.bank(held = 2400, maxDepth = 120f)
        assertEquals(600, shallowSweep)
        assertEquals(12000, deepLoaded)
        assertTrue(deepLoaded > shallowSweep * 10)
    }

    @Test
    fun `blackout keeps ten percent with no depth bonus`() {
        assertEquals(240, Scoring.blackoutBank(2400))
    }

    @Test
    fun `blackout on a tiny haul still rounds down to zero without crashing`() {
        assertEquals(0, Scoring.blackoutBank(5))
    }
}
