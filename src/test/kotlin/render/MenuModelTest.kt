package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Pure navigation for the main menu.
 *
 * EDGE-TRIGGERING IS THE POINT OF MOST OF THESE TESTS. The engine's Gamepad exposes only
 * isPressed/getAxis with no wasClicked (see RunLifecycle's class doc for the incident that made
 * this non-negotiable), so every input this class receives is a LEVEL that reads true on every
 * frame the control is held. A menu that treats a level as a press scrolls its entire list in a
 * single frame and is unusable with a stick — and on a noisy USB encoder, a stuck contact would
 * cycle a setting forever.
 */
class MenuModelTest
{
    private val menu = MenuModel()

    private fun press(up: Boolean = false, down: Boolean = false, left: Boolean = false,
                      right: Boolean = false, confirm: Boolean = false, back: Boolean = false) =
        menu.update(up, down, left, right, confirm, back)

    private fun release() = menu.update(false, false, false, false, false, false)

    @Test
    fun `a held down direction moves the selection exactly once`() {
        val start = menu.selectedIndex
        repeat(60) { press(down = true) }        // one full second at 60 Hz, held
        assertEquals(start + 1, menu.selectedIndex,
            "a held direction must move one row, not 60")
    }

    @Test
    fun `releasing and pressing again moves again`() {
        val start = menu.selectedIndex
        press(down = true); release(); press(down = true)
        assertEquals(start + 2, menu.selectedIndex)
    }

    @Test
    fun `selection wraps from the last row to the first`() {
        val rows = menu.itemsOn(MenuPage.ROOT).size
        repeat(rows) { press(down = true); release() }
        assertEquals(0, menu.selectedIndex, "selection should wrap to the top")
    }

    @Test
    fun `selection wraps backwards from the first row to the last`() {
        val rows = menu.itemsOn(MenuPage.ROOT).size
        press(up = true)
        assertEquals(rows - 1, menu.selectedIndex)
    }

    @Test
    fun `confirming GRAPHICS opens the graphics page and resets the selection`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true)
        assertEquals(MenuPage.GRAPHICS, menu.page)
        assertEquals(0, menu.selectedIndex, "a freshly opened page starts at its first row")
    }

    @Test
    fun `back returns from GRAPHICS to ROOT`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()
        press(back = true)
        assertEquals(MenuPage.ROOT, menu.page)
    }

    @Test
    fun `back on ROOT is a no-op rather than a crash or a quit`() {
        // A player pressing B on the top-level menu must not exit the game by accident.
        val action = press(back = true)
        assertEquals(MenuPage.ROOT, menu.page)
        assertIs<MenuAction.None>(action)
    }

    @Test
    fun `left and right on a setting row emit SettingChanged with a signed delta`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()
        while (menu.selectedItem() != MenuItemId.QUALITY) { press(down = true); release() }

        val right = press(right = true); release()
        assertIs<MenuAction.SettingChanged>(right)
        assertEquals(MenuItemId.QUALITY, right.item)
        assertEquals(1, right.delta)

        val left = press(left = true)
        assertIs<MenuAction.SettingChanged>(left)
        assertEquals(-1, left.delta)
    }

    @Test
    fun `a held right emits exactly one SettingChanged`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()

        var changes = 0
        repeat(60) { if (press(right = true) is MenuAction.SettingChanged) changes++ }
        assertEquals(1, changes, "a held direction must change a setting once, not 60 times")
    }

    @Test
    fun `left and right on an action row do nothing`() {
        // START DIVE has no value to cycle; nudging the stick sideways on it must be inert.
        while (menu.selectedItem() != MenuItemId.START_DIVE) { press(down = true); release() }
        assertIs<MenuAction.None>(press(right = true))
    }

    @Test
    fun `confirming START DIVE and QUIT emit their actions`() {
        while (menu.selectedItem() != MenuItemId.START_DIVE) { press(down = true); release() }
        assertIs<MenuAction.StartDive>(press(confirm = true))

        menu.reset(); release()
        while (menu.selectedItem() != MenuItemId.QUIT) { press(down = true); release() }
        assertIs<MenuAction.Quit>(press(confirm = true))
    }

    @Test
    fun `reset returns to the root page at the first row`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()
        menu.reset()
        assertEquals(MenuPage.ROOT, menu.page)
        assertEquals(0, menu.selectedIndex)
    }

    @Test
    fun `both directions at once cancel rather than picking one`() {
        // A stuck contact on a noisy encoder can hold two directions simultaneously. Picking a
        // winner would let it walk the menu on its own; PadAxis already applies this rule to
        // steering and the menu follows it.
        val start = menu.selectedIndex
        press(up = true, down = true)
        assertEquals(start, menu.selectedIndex)
    }

    @Test
    fun `every item id is reachable from the root by navigation alone`() {
        // Guards against an item declared but never listed on a page - it would be dead code
        // that looks live.
        val reachable = menu.itemsOn(MenuPage.ROOT) + menu.itemsOn(MenuPage.GRAPHICS)
        assertTrue(reachable.containsAll(MenuItemId.entries.toList()),
            "unreachable items: ${MenuItemId.entries - reachable.toSet()}")
    }
}
