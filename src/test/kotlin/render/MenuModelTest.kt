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

    // dt defaults to 0f here for the same reason MenuModel.update's own parameter does: most of
    // these tests are about EDGE detection and have no hold to drive, and a zero dt accumulates
    // nothing. The hold tests at the bottom pass it explicitly, one 60 Hz frame at a time.
    private fun press(up: Boolean = false, down: Boolean = false, left: Boolean = false,
                      right: Boolean = false, confirm: Boolean = false, back: Boolean = false,
                      dt: Float = 0f) =
        menu.update(up, down, left, right, confirm, back, dt)

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
    fun `back on ROOT closes the menu rather than quitting or changing page`() {
        // A player pressing B on the top-level menu must not exit the game by accident, and
        // must not leave the page they are on.
        //
        // AMENDMENT (2026-08-31, the pause menu): this used to assert MenuAction.None. The
        // action is now CloseMenu, which is a REPORT and not a decision — this class still does
        // nothing itself, and a caller that ignores it behaves exactly as every caller did
        // before the case existed. What the assertion protects is unchanged and is the whole
        // reason the test is here: root-page back must never be Quit, and must never move the
        // page. Asserting the exact type rather than "not Quit" is what would catch a future
        // edit that routed this row somewhere new.
        val action = press(back = true)
        assertEquals(MenuPage.ROOT, menu.page)
        assertIs<MenuAction.CloseMenu>(action)
    }

    @Test
    fun `back on GRAPHICS goes back a page and does NOT report a close`() {
        // The other side of CloseMenu, and the property that makes it safe for EnPustTil to
        // wire straight to RunLifecycle.resumeRun(): a sub-page consumes back itself, so the
        // same Esc that resumes a held run from the root page cannot resume it from GRAPHICS —
        // it leaves the page instead. Without this, a player who opened GRAPHICS mid-dive and
        // pressed Esc would be dropped back into the water rather than back to the row list.
        press(down = true)                        // ROOT: START_DIVE -> GRAPHICS
        assertIs<MenuAction.None>(press(confirm = true))
        assertEquals(MenuPage.GRAPHICS, menu.page)

        val action = press(back = true)
        assertEquals(MenuPage.ROOT, menu.page)
        assertIs<MenuAction.Back>(action)
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

    // --- CRITICAL C1: priming on entry so an already-held button is not a fresh edge -------

    @Test
    fun `a level already held when the menu opens must NOT produce an edge`() {
        // CRITICAL C1 (final review, 2026-08-30): reset() alone zeroes every was* field, so
        // the very press that OPENED the menu — still physically down one frame later, since
        // a human press is 5-10 frames — would read as a false-to-true CONFIRM edge on the
        // very next update() and immediately confirm row 0 (START DIVE), dropping the player
        // straight into a dive the instant the menu appears. prime() is EnPustTil's fix:
        // seed was* from the actual levels this frame instead of leaving reset()'s zeroes.
        menu.reset()
        menu.prime(up = false, down = false, left = false, right = false, confirm = true, back = false)

        val action = menu.update(up = false, down = false, left = false, right = false, confirm = true, back = false)
        assertIs<MenuAction.None>(action, "a level primed as already-held must not fire as a fresh edge")

        // And releasing, then pressing again, must still work normally afterwards - priming
        // must not permanently disable confirm.
        release()
        assertIs<MenuAction.StartDive>(press(confirm = true))
    }

    @Test
    fun `prime does not touch the page or selected row`() {
        // prime() is deliberately narrower than reset(): EnPustTil calls reset() first (to
        // land back on ROOT/row 0) and prime() second (to fix up was*) - prime() reaching
        // into page/selectedIndex too would make that second call redundant with the first
        // in a way that invites deleting one of them later.
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        val indexBefore = menu.selectedIndex
        val pageBefore = menu.page
        menu.prime(up = true, down = false, left = false, right = false, confirm = false, back = false)
        assertEquals(pageBefore, menu.page)
        assertEquals(indexBefore, menu.selectedIndex)
    }

    // --- CRITICAL C2: confirm wins the tie-break if confirm and back ever collide ----------

    @Test
    fun `confirm wins if confirm and back are somehow both held at once`() {
        // Belt-and-braces for a misconfigured pauseButton/restartButton collision (see
        // EnPustTil's gamepadButtonCollisionWarnings) - even if that collision recurs, START
        // must still work rather than BACK silently winning, which was the whole shape of
        // CRITICAL C2 (Options doing nothing on the root menu).
        while (menu.selectedItem() != MenuItemId.START_DIVE) { press(down = true); release() }
        val action = press(confirm = true, back = true)
        assertIs<MenuAction.StartDive>(action, "confirm must win a same-frame collision with back")
    }

    @Test
    fun `every item id is reachable from the root by navigation alone`() {
        // Guards against an item declared but never listed on a page - it would be dead code
        // that looks live.
        //
        // SWEEPS MenuPage.entries RATHER THAN NAMING THE PAGES. It listed ROOT and GRAPHICS by
        // hand until 2026-08-31, which made it silently incomplete the moment a third page was
        // added: a page whose rows were all unreachable would still have passed, because the
        // test would not have looked at it. Enumerating the enum makes it self-maintaining.
        val reachable = MenuPage.entries.flatMap { menu.itemsOn(it) }
        assertTrue(reachable.containsAll(MenuItemId.entries.toList()),
            "unreachable items: ${MenuItemId.entries - reachable.toSet()}")
    }

    // --- The LEADERBOARD page: CRITICAL back handling, and CRITICAL C1 in a new place -------

    /** Walk ROOT to the LEADERBOARD row and confirm it, leaving CONFIRM STILL HELD - which is
     * the physical truth one frame after any human press, and the whole premise of the hold
     * tests below. Returns with the model on MenuPage.LEADERBOARD, row 0 = DELETE_BOARD. */
    private fun openLeaderboardWithConfirmStillHeld() {
        while (menu.selectedItem() != MenuItemId.LEADERBOARD) { press(down = true); release() }
        press(confirm = true)
        assertEquals(MenuPage.LEADERBOARD, menu.page)
        assertEquals(MenuItemId.DELETE_BOARD, menu.selectedItem(),
            "these tests are only meaningful if DELETE BOARD really is the row the page opens on")
    }

    @Test
    fun `back on the LEADERBOARD page returns to ROOT and does NOT report a close`() {
        // CRITICAL. MenuModel.update's back branch read `page == MenuPage.GRAPHICS` for as long
        // as GRAPHICS was the only sub-page. On a third page that falls into the else and emits
        // CloseMenu, which EnPustTil.applyMenuAction routes to lifecycle.resumeRun() - so Esc
        // on the leaderboard page would DROP A PAUSED PLAYER BACK INTO THE WATER instead of
        // returning them to the row list. The branch is `page != MenuPage.ROOT`; this is what
        // fails if anyone narrows it back to a single named page.
        openLeaderboardWithConfirmStillHeld()
        release()

        val action = press(back = true)
        assertEquals(MenuPage.ROOT, menu.page)
        assertIs<MenuAction.Back>(action,
            "back on a sub-page must be consumed by the menu, never reported as CloseMenu")
    }

    @Test
    fun `the press that OPENS the leaderboard page cannot delete the board`() {
        // CRITICAL C1, IN A NEW PLACE, AND ITS CONSEQUENCE IS A WIPED LEADERBOARD.
        //
        // Confirming LEADERBOARD switches the page and sets selectedIndex = 0, and DELETE BOARD
        // IS row 0. prime() is called only on menu ENTRY, never on a page switch. So the
        // opening press - still physically down, since a human press is 5-10 frames and anyone
        // resting a thumb on the button holds it far longer - lands on the delete row as a
        // LEVEL. A naive "accumulate while confirm is held" rule wipes the board of a player
        // who asked only to LOOK at it.
        //
        // Ten seconds of continuous hold here, nearly seven times the threshold: nothing.
        openLeaderboardWithConfirmStillHeld()

        repeat(600) {
            assertIs<MenuAction.None>(press(confirm = true, dt = 1f / 60f),
                "the still-held opening press must never reach the delete threshold")
        }
        assertEquals(0f, menu.deleteHoldProgress,
            "an unarmed hold must read as zero progress, not as a bar creeping toward a wipe")

        // And the page is still usable afterwards - the guard must not permanently disable the
        // row, only require the player to let go and mean it.
        release()
        repeat(600) { press(confirm = true, dt = 1f / 60f) }
        assertEquals(MenuPage.LEADERBOARD, menu.page)
    }

    @Test
    fun `a fresh press after releasing does delete the board, exactly once`() {
        // The other half of the test above: the guard is "release first", not "never". Without
        // this pair, deleting the whole hold implementation would still pass the C1 test.
        openLeaderboardWithConfirmStillHeld()
        release()

        var deletes = 0
        // Five seconds of continuous hold - more than three times the threshold. A rule that
        // zeroed the timer without disarming would fire again roughly every 1.5 s.
        repeat(300) { if (press(confirm = true, dt = 1f / 60f) is MenuAction.DeleteBoard) deletes++ }
        assertEquals(1, deletes,
            "an irreversible wipe must fire once per deliberate hold, not on every frame past it")
    }

    @Test
    fun `a confirm tap on DELETE BOARD does nothing at all`() {
        // There is deliberately no press-to-delete path. MenuModel.update's confirm-edge `when`
        // has no DELETE_BOARD arm, so it falls to `else -> MenuAction.None`; this asserts that
        // rather than re-implementing it, because an arm added there later would be the exact
        // regression - one stray tap wiping a booth day's scores.
        openLeaderboardWithConfirmStillHeld()
        release()

        repeat(20) {
            assertIs<MenuAction.None>(press(confirm = true, dt = 1f / 60f))
            release()
        }
    }

    @Test
    fun `navigating off the delete row and back restarts the hold from zero`() {
        // "Resets on navigation." Without it, a player could bank most of a hold on DELETE
        // BOARD, move down to BACK to reconsider, move back up, and have the wipe fire almost
        // immediately - a hold whose accumulated time survives the player changing their mind
        // is not a deliberate gesture any more.
        openLeaderboardWithConfirmStillHeld()
        release()

        // 1.4 s: just short of the 1.5 s threshold, so nothing has fired yet.
        repeat(84) { assertIs<MenuAction.None>(press(confirm = true, dt = 1f / 60f)) }
        assertTrue(menu.deleteHoldProgress > 0.8f, "the hold should be nearly complete by now")

        press(down = true, confirm = true)                    // DELETE_BOARD -> BACK
        assertEquals(MenuItemId.BACK, menu.selectedItem())
        assertEquals(0f, menu.deleteHoldProgress, "navigation must zero the hold")

        press(up = true, confirm = true)                      // BACK -> DELETE_BOARD
        assertEquals(MenuItemId.DELETE_BOARD, menu.selectedItem())

        // A single frame at the old near-threshold value must not tip it over.
        assertIs<MenuAction.None>(press(confirm = true, dt = 1f / 60f))
    }

    @Test
    fun `releasing part-way through the hold restarts it from zero`() {
        openLeaderboardWithConfirmStillHeld()
        release()

        repeat(84) { press(confirm = true, dt = 1f / 60f) }   // 1.4 s, just short
        assertTrue(menu.deleteHoldProgress > 0.8f)

        release()
        assertEquals(0f, menu.deleteHoldProgress, "releasing must zero the hold")

        // Re-pressing starts a whole fresh 1.5 s, so 1.4 s of it still fires nothing.
        repeat(84) { assertIs<MenuAction.None>(press(confirm = true, dt = 1f / 60f)) }
    }

    @Test
    fun `leaving the leaderboard page abandons a hold in progress`() {
        openLeaderboardWithConfirmStillHeld()
        release()

        repeat(84) { press(confirm = true, dt = 1f / 60f) }   // 1.4 s, just short
        press(back = true)                                     // confirm released, back pressed
        assertEquals(MenuPage.ROOT, menu.page)
        assertEquals(0f, menu.deleteHoldProgress, "leaving the page must zero the hold")
    }

    @Test
    fun `the delete hold and the exit hold are the same duration`() {
        // The game's only two hold-to-confirm gestures, both guarding an irreversible act. A
        // player who has learned one has learned the other; two different fill rates would read
        // as two different kinds of commitment. This is what fails if someone edits one number.
        assertEquals(RunLifecycle.EXIT_HOLD_SECONDS, MenuModel.DELETE_HOLD_SECONDS)
    }

    @Test
    fun `confirming LEADERBOARD opens a page rather than abandoning a held run`() {
        // It used to emit ShowLeaderboard, which EnPustTil routes to viewLeaderboard() -> IDLE
        // -> justReturnedToIdle -> a fresh DiveSim, i.e. it threw a paused player's dive away
        // to show them the board. As a page it just opens, like GRAPHICS.
        while (menu.selectedItem() != MenuItemId.LEADERBOARD) { press(down = true); release() }
        val action = press(confirm = true)
        assertIs<MenuAction.None>(action, "the row opens a page; it must not report an action")
        assertEquals(MenuPage.LEADERBOARD, menu.page)
        assertEquals(0, menu.selectedIndex, "a freshly opened page starts at its first row")
    }
}
