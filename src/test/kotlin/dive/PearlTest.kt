package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PearlTest {
    @Test
    fun `same seed produces identical columns`() {
        val a = PearlColumn.generate(seed = 42L)
        val b = PearlColumn.generate(seed = 42L)
        assertEquals(a.size, b.size)
        a.indices.forEach { i ->
            assertEquals(a[i].x, b[i].x, 0.0001f)
            assertEquals(a[i].depth, b[i].depth, 0.0001f)
        }
    }

    @Test
    fun `different seeds produce different columns`() {
        val a = PearlColumn.generate(seed = 1L)
        val b = PearlColumn.generate(seed = 2L)
        val same = a.indices.count { a[it].depth == b[it].depth }
        assertTrue(same < a.size, "seeds must differ")
    }

    @Test
    fun `every zone has pearls`() {
        val pearls = PearlColumn.generate(seed = 7L)
        Zone.entries.forEach { zone ->
            assertTrue(pearls.any { it.zone == zone }, "no pearls in $zone")
        }
    }

    @Test
    fun `pearls sit within the column bounds`() {
        PearlColumn.generate(seed = 7L).forEach {
            assertTrue(it.x >= -Tuning.COLUMN_HALF_WIDTH && it.x <= Tuning.COLUMN_HALF_WIDTH)
            assertTrue(it.depth >= 0f && it.depth <= Tuning.MAX_DEPTH)
        }
    }

    @Test
    fun `pearl zone matches its depth`() {
        PearlColumn.generate(seed = 7L).forEach {
            assertEquals(Zone.at(it.depth), it.zone)
        }
    }

    @Test
    fun `pearls start uncollected`() {
        assertTrue(PearlColumn.generate(seed = 7L).none { it.collected })
    }
}
