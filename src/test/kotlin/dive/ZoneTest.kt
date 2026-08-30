package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoneTest {
    @Test
    fun `surface is shallows`() = assertEquals(Zone.SHALLOWS, Zone.at(0f))

    @Test
    fun `above surface clamps to shallows`() = assertEquals(Zone.SHALLOWS, Zone.at(-5f))

    @Test
    fun `boundaries are inclusive at the lower edge`() {
        assertEquals(Zone.KELP, Zone.at(30f))
        assertEquals(Zone.TWILIGHT, Zone.at(60f))
        assertEquals(Zone.TRENCH, Zone.at(90f))
        assertEquals(Zone.ABYSS, Zone.at(120f))
    }

    @Test
    fun `just above a boundary stays in the shallower zone`() {
        assertEquals(Zone.SHALLOWS, Zone.at(29.99f))
        assertEquals(Zone.TRENCH, Zone.at(119.99f))
    }

    @Test
    fun `very deep is abyss`() = assertEquals(Zone.ABYSS, Zone.at(500f))

    @Test
    fun `pearl value increases with depth`() {
        assertEquals(10, Zone.SHALLOWS.pearlValue)
        assertEquals(400, Zone.ABYSS.pearlValue)
    }

    @Test
    fun `at does not allocate`() {
        // The old form was `entries.lastOrNull { ... }`, which compiles to
        // EnumEntriesList.listIterator() -> new AbstractList$ListIteratorImpl: ONE allocation
        // per call. DiveRenderer.drawZoneBands calls it five times per strip across ~111
        // strips, i.e. 555 allocations every frame. Verified from bytecode.
        //
        // Measured by allocation count rather than by reading the source, because the source
        // form that allocates and the form that does not look almost identical.
        val before = allocatedBytes()
        var sink = 0
        repeat(100_000) { sink += Zone.at((it % 200).toFloat()).pearlValue }
        val after = allocatedBytes()
        // Assert something real about `sink` so the loop cannot be optimised away: every zone
        // has a positive pearlValue, so 100k lookups must sum to a positive total.
        assertTrue(sink > 0, "the lookup loop was optimised away; the measurement is meaningless")
        assertTrue(after - before < 100_000,
            "Zone.at allocated ${after - before} bytes over 100k calls — it should allocate none")
    }

    private fun allocatedBytes(): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
                as com.sun.management.ThreadMXBean
        return bean.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    @Test
    fun `air burns faster deeper`() {
        // Strict monotonicity is the property; the endpoints are pinned so the whole table
        // cannot be flattened to a constant and still pass. The Abyss's own value is a tuning
        // dial and is deliberately NOT pinned here — it moved from 2.5 to 2.2 when the zone
        // was made reachable (see Ascent, AscentTest and the design spec's §17 log), and a
        // test that has to be edited to permit a balance change is a test that will be edited
        // without thought.
        assertEquals(1.0f, Zone.SHALLOWS.airBurn, "the surface must be the cheapest water")
        Zone.entries.zipWithNext().forEach { (shallower, deeper) ->
            assertTrue(
                deeper.airBurn > shallower.airBurn,
                "$deeper must burn air faster than $shallower"
            )
        }
        assertTrue(Zone.ABYSS.airBurn > 2f, "the deep must stay meaningfully expensive")
    }
}
