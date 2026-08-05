package render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamepadScanTest
{
    @Test
    fun `false when nothing is pressed anywhere`() {
        assertFalse(anyLifecycleActionPressed(keyPressed = false, gamepadActionPressed = emptyList()))
        assertFalse(anyLifecycleActionPressed(keyPressed = false, gamepadActionPressed = listOf(false, false, false)))
    }

    @Test
    fun `true when the keyboard key alone is pressed`() {
        assertTrue(anyLifecycleActionPressed(keyPressed = true, gamepadActionPressed = emptyList()))
    }

    @Test
    fun `true when only the FIRST gamepad is pressed`() {
        assertTrue(anyLifecycleActionPressed(keyPressed = false, gamepadActionPressed = listOf(true, false, false)))
    }

    /**
     * The load-bearing case for finding 3. If this were implemented as
     * `gamepadActionPressed.firstOrNull() ?: false` (i.e. only slot 0 is ever consulted —
     * exactly the bug this function exists to prevent), this list's first element is
     * `false`, so that implementation returns `false` and this test fails. Only scanning
     * ALL gamepads (`.any`) finds the `true` sitting at index 1.
     */
    @Test
    fun `true when a LATER gamepad is pressed and the first is not`() {
        assertTrue(anyLifecycleActionPressed(keyPressed = false, gamepadActionPressed = listOf(false, true)))
    }

    @Test
    fun `true when the last of many gamepads is pressed`() {
        val pads = List(4) { false } + true
        assertTrue(anyLifecycleActionPressed(keyPressed = false, gamepadActionPressed = pads))
    }
}
