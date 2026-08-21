package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GamepadScanTest
{
    @Test
    fun `gameplay follows the pad that started the run, not slot zero`()
    {
        // THE BOOTH FAILURE: a stray HID in slot 0 gave a startable, unplayable run - START
        // worked (lifecycle scans every pad) and the diver did not move (gameplay read
        // firstOrNull). Repeating until someone noticed.
        assertEquals(3, selectGameplayPad(padIds = listOf(0, 3), preferred = 3))
    }

    @Test
    fun `with no run started yet, gameplay falls back to the first pad`()
    {
        // Attract mode, and the frame a keyboard start begins a run: there is no preferred
        // pad, and the old behaviour is the right default.
        assertEquals(0, selectGameplayPad(padIds = listOf(0, 3), preferred = null))
    }

    @Test
    fun `a preferred pad that has been unplugged falls back to the first pad`()
    {
        // Mid-run unplug must not freeze the diver for the rest of the run.
        assertEquals(0, selectGameplayPad(padIds = listOf(0, 3), preferred = 7))
    }

    @Test
    fun `no pads at all selects nothing`()
    {
        // Keyboard-only development, and an unmapped encoder at the booth.
        assertNull(selectGameplayPad(padIds = emptyList(), preferred = 3))
    }
}
