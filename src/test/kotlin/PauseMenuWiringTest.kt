import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pause menu's wiring in `EnPustTil.kt` (2026-08-31): pressing Esc mid-run opens the MAIN
 * MENU over the frozen dive, with its first row reading CONTINUE, rather than the separate
 * two-line pause screen it used to open.
 *
 * `RunLifecycle` carries the state half of that and is unit-tested directly (`runHeld`,
 * `resumeRun`, the narrowed pause-edge branch, the inactivity-reset on the auto-resume clock).
 * What CANNOT be reached from a headless JVM is the half that lives in `EnPustTil`: driving
 * `MenuModel` from PAUSED, drawing `drawMainMenu` there instead of `drawPauseScreen`, and
 * routing the menu's own actions back to `RunLifecycle.resumeRun`. Every one of those is a
 * `PulseEngineGame` method needing a GL context, a `Surface` and live input — so this is a
 * source scan, in the established style of [UpdateGameOrderingTest] and
 * `EnPustTilDepthPinTest`.
 *
 * WHAT EACH ASSERTION IS ACTUALLY GUARDING. All three failures are silent and none of them
 * would fail a compile: a gate that dropped `runHeld` leaves a menu on screen that no input
 * moves; a draw that kept `drawPauseScreen` leaves a player looking at "ESC to resume" with no
 * rows at all; and a `CloseMenu`/`StartDive` that stopped calling `resumeRun` strands the run
 * behind a menu whose only remaining exits abandon it or close the game. That last one is the
 * worst, and it is exactly what happens by DEFAULT if this wiring is dropped, because
 * `RunLifecycle` no longer resumes a held run on a pause edge of its own.
 */
class PauseMenuWiringTest
{
    private val source = File("src/main/kotlin/EnPustTil.kt").readText()

    private fun bodyOf(signature: String, endsBefore: String): String
    {
        val after = source.substringAfter(signature)
        val end = after.indexOf(endsBefore)
        assertTrue(end >= 0, "could not bound $signature at $endsBefore — re-read this test")
        return after.substring(0, end)
    }

    @Test
    fun `the menu is driven from a held run as well as from MAIN_MENU`()
    {
        // One gate decides three things at once in updateGame: whether updateMainMenu walks the
        // pads at all, whether applyMenuAction is allowed to act, and whether menuInputActive
        // reaches RunLifecycle to hold off the auto-resume. Drop `runHeld` from it and the pause
        // menu draws perfectly and responds to nothing — no navigation, no CONTINUE, and a run
        // that resumes itself 20 seconds later as if nobody had touched anything.
        val body = bodyOf("private fun updateGame()", "\n    override fun onDestroy")
        assertTrue(
            "val inMainMenuNow = lifecycle.state == RunLifecycleState.MAIN_MENU || lifecycle.runHeld" in body,
            "updateGame's menu gate no longer includes lifecycle.runHeld. The pause menu is the " +
            "SAME MenuModel as the main menu, driven from PAUSED — without runHeld in this gate " +
            "updateMainMenu is never called from a held run, so the menu is drawn but dead: no " +
            "row moves, CONTINUE cannot be confirmed, and menuInputActive never reaches " +
            "RunLifecycle, so PAUSE_IDLE_TIMEOUT_SECONDS resumes the dive under a player who is " +
            "still reading the screen."
        )
    }

    @Test
    fun `a held run draws the main menu and the technician's cabinet menu does not`()
    {
        // Both branches matter. PAUSED-from-a-run must draw the navigable menu; PAUSED-from-
        // attract must keep drawPauseScreen, which is where the exit-hold bar lives and which
        // has no rows to offer a technician in the first place.
        val body = bodyOf("RunLifecycleState.PAUSED ->", "RunLifecycleState.RUN_OVER ->")
        val idleIndex = body.indexOf("if (lifecycle.pausedFromIdle)")
        val pauseScreenIndex = body.indexOf("drawPauseScreen(hud, w, h)")
        val mainMenuIndex = body.indexOf("drawMainMenu(hud, w, h)")

        assertTrue(idleIndex >= 0, "the PAUSED draw no longer branches on pausedFromIdle — re-read this test")
        assertTrue(
            mainMenuIndex >= 0,
            "the PAUSED draw no longer calls drawMainMenu. A run held behind Esc is supposed to " +
            "show the main menu with CONTINUE on its first row; without this call the player " +
            "gets the old two-line pause screen while EnPustTil is still driving MenuModel " +
            "behind it — a selection moving on a screen with nothing on it to move."
        )
        assertTrue(
            pauseScreenIndex in 0 until mainMenuIndex,
            "drawPauseScreen no longer precedes drawMainMenu inside the PAUSED branch. The first " +
            "is the pausedFromIdle (technician's cabinet menu) arm and the second is the held-run " +
            "arm; if the order has flipped, one of the two arms is now drawing the wrong screen."
        )
    }

    @Test
    fun `both of the menu's exits from a held run call resumeRun`()
    {
        // CONTINUE (the START_DIVE row, relabelled) and BACK/Esc on the root page
        // (MenuAction.CloseMenu) are the ONLY two ways a player gets their dive back:
        // RunLifecycle deliberately stopped resuming a held run on its own pause edge, because
        // the shipped default puts the pad's pause and confirm on the same physical button
        // (DEFAULT_PAUSE_BUTTON_ALT and DEFAULT_RESTART_BUTTON are both START) and a pause edge
        // therefore cannot tell "close the menu" apart from "pick this row". Lose either call
        // and the pause menu becomes a screen a run cannot be resumed from at all.
        val body = bodyOf("private fun applyMenuAction(action: MenuAction)", "\n    private fun axis(")
        val startDive = body.indexOf("MenuAction.StartDive ->")
        val closeMenu = body.indexOf("MenuAction.CloseMenu ->")

        assertTrue(startDive >= 0, "applyMenuAction no longer handles MenuAction.StartDive — re-read this test")
        assertTrue(closeMenu >= 0, "applyMenuAction no longer handles MenuAction.CloseMenu — re-read this test")
        for ((name, from) in listOf("StartDive" to startDive, "CloseMenu" to closeMenu))
        {
            val arm = body.substring(from, body.indexOf("MenuAction.", from + 12).let { if (it < 0) body.length else it })
            assertTrue(
                "lifecycle.resumeRun()" in arm,
                "applyMenuAction's MenuAction.$name arm no longer calls lifecycle.resumeRun(). " +
                "CONTINUE (StartDive) and root-page BACK (CloseMenu) are the only two routes out " +
                "of a held run that keep it — RunLifecycle no longer resumes on a pause edge — so " +
                "dropping this leaves a paused dive whose remaining exits all throw it away."
            )
        }
    }

    @Test
    fun `the resume legend is confined to the root page, where the button actually resumes`()
    {
        // Found on a window grab, not in a diff. BACK is consumed by the menu itself on
        // GRAPHICS — MenuModel returns `Back` there and `CloseMenu` only from ROOT, which is
        // what stops Esc dropping a player into the water mid-page — so a legend reading
        // "<button> to resume" on GRAPHICS advertises something that button does not do, on the
        // one line whose entire job is to say which control does what.
        assertTrue(
            "if (runHeld && page == MenuPage.ROOT) hintPauseMenuLegend else hintMenuLegend" in source,
            "drawMainMenu's hint line no longer restricts the resume legend to the ROOT page. On " +
            "GRAPHICS the back button goes back a page rather than resuming, so an unqualified " +
            "`if (runHeld)` prints \"to resume\" over a button that does not resume."
        )
    }

    @Test
    fun `the first row is relabelled rather than replaced by a second row`()
    {
        // A second MenuItemId would have to join MenuModel's ROOT list, which moves every row
        // below it — on the one screen a returning player navigates from muscle memory. The row,
        // its index and its MenuAction all stay put; only the string changes.
        assertTrue(
            "MenuItemId.START_DIVE -> if (lifecycle.runHeld) ScreenText.MENU_CONTINUE else ScreenText.MENU_START_DIVE" in source,
            "menuItemLabel no longer relabels START_DIVE to CONTINUE while a run is held. The row " +
            "resumes the dive in that state (applyMenuAction routes StartDive to resumeRun), so a " +
            "label still reading START DIVE over a frozen run reads as an offer to throw it away."
        )
    }
}
