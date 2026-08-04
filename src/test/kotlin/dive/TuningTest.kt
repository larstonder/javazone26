package dive

import kotlin.test.Test
import kotlin.test.assertEquals

class TuningTest {
    @Test
    fun `run length is 90 seconds`() {
        assertEquals(90f, Tuning.RUN_SECONDS)
    }

    @Test
    fun `base air is 20 seconds`() {
        assertEquals(20f, Tuning.BASE_AIR_SECONDS)
    }

    @Test
    fun `blackout keeps ten percent`() {
        assertEquals(0.10f, Tuning.BLACKOUT_KEEP)
    }
}
