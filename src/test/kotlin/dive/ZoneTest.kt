package dive

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `air burns faster deeper`() {
        assertEquals(1.0f, Zone.SHALLOWS.airBurn)
        assertEquals(2.5f, Zone.ABYSS.airBurn)
    }
}
