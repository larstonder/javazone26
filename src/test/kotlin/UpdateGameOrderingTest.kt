import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `updateGame`'s `initialsJustCompleted` score-persist has to run BEFORE the
 * `justReturnedToIdle` DiveSim rebuild — see Critical 1 of the task-9 review
 * (`.superpowers/sdd/2026-08-21-booth-survival/task-9-report.md`).
 *
 * `RunLifecycle.finishInitials()` sets `initialsJustCompleted = true` and then calls
 * `enter(IDLE)` WITHOUT `resuming` (it defaults to `false`), which sets
 * `justReturnedToIdle = true` in the SAME call — `justReturnedToIdle` is keyed on the
 * caller's resume intent, not on the state being left, so a plain `enter(IDLE)` always sets
 * it. Both flags are therefore true on the exact tick a player finishes their initials. The
 * `justReturnedToIdle` block destroys `sim` (`sim = DiveSim(seed = dailySeed)`), so if that
 * ran first, `scoreRepository.registerScore` would read the FRESH sim's `banked == 0`, and
 * `Leaderboard.isWorthRecording(0)` would discard it with no log and no error — every real
 * score silently lost, for both days of the booth. A runtime test cannot see this: it takes
 * two RunLifecycle flags that are individually well-tested and a wiring bug in the file that
 * reacts to them, so this is a source scan, in the style of `EnPustTilDepthPinTest.the pin
 * is applied before the camera is snapped to the diver`.
 */
class UpdateGameOrderingTest
{
    private val body: String
    init
    {
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val afterSignature = source.substringAfter("private fun updateGame()")
        // Bounded to just this function: the next declaration in the file is onDestroy.
        body = afterSignature.substring(0, afterSignature.indexOf("\n    override fun onDestroy"))
    }

    @Test
    fun `the score is persisted before the returning-to-idle rebuild can destroy the sim that scored it`()
    {
        val initialsIndex = body.indexOf("if (lifecycle.initialsJustCompleted)")
        val returnedIndex = body.indexOf("if (lifecycle.justReturnedToIdle)")

        assertTrue(initialsIndex >= 0, "updateGame no longer checks lifecycle.initialsJustCompleted — re-read this test")
        assertTrue(returnedIndex >= 0, "updateGame no longer checks lifecycle.justReturnedToIdle — re-read this test")
        assertTrue(
            initialsIndex < returnedIndex,
            "lifecycle.justReturnedToIdle is checked at offset $returnedIndex, BEFORE " +
            "lifecycle.initialsJustCompleted at offset $initialsIndex. Both flags are true on the " +
            "same tick a player finishes initials entry (RunLifecycle.finishInitials sets " +
            "initialsJustCompleted then calls enter(IDLE) without resuming, which sets " +
            "justReturnedToIdle in the same call). The justReturnedToIdle block replaces `sim` with a fresh DiveSim " +
            "(banked == 0) — reading sim.banked for the score AFTER that swap registers a zero " +
            "score, which Leaderboard.isWorthRecording silently discards. Every real score would " +
            "be lost, all day, with no log and no error."
        )
    }

    /**
     * CRITICAL C3 (final review, 2026-08-30): `applyMenuAction` (which can call
     * `lifecycle.viewLeaderboard()`, entering IDLE) must run AFTER this frame's own
     * `lifecycle.update(...)` call, not before. `updateMainMenu` used to apply
     * `MenuAction.ShowLeaderboard` itself, before `lifecycle.update` ran — and that
     * `lifecycle.update` call's `anyInputPressed` is the SAME confirm press that produced
     * ShowLeaderboard in the first place. IDLE's own branch reads `anyInputPressed`
     * (`pressedEdge`) and re-enters MAIN_MENU, so the leaderboard bounced back to the menu
     * in the exact frame it opened, with nothing ever drawn. MAIN_MENU's own branch is
     * immune (it deliberately never reads `anyInputPressed`), which is what makes deferring
     * `applyMenuAction` to AFTER `lifecycle.update` safe — see that function's own doc.
     */
    @Test
    fun `the deferred menu action is applied after this frame's own lifecycle update, not before`()
    {
        val updateIndex = body.indexOf("lifecycle.update(")
        val applyIndex = body.indexOf("applyMenuAction(menuAction)")

        assertTrue(updateIndex >= 0, "updateGame no longer calls lifecycle.update( - re-read this test")
        assertTrue(applyIndex >= 0, "updateGame no longer calls applyMenuAction(menuAction) - re-read this test")
        assertTrue(
            updateIndex < applyIndex,
            "applyMenuAction(menuAction) is called at offset $applyIndex, BEFORE lifecycle.update( at " +
            "offset $updateIndex. applyMenuAction can call lifecycle.viewLeaderboard() (MenuAction" +
            ".ShowLeaderboard), which enters IDLE - and THIS SAME FRAME's lifecycle.update call " +
            "reads anyInputPressed = actionPressed, the identical confirm press that produced " +
            "ShowLeaderboard. IDLE's own branch reacts to that press and re-enters MAIN_MENU " +
            "immediately, so the leaderboard would bounce back to the menu in the same frame it " +
            "opened, with nothing ever drawn (CRITICAL C3, final review)."
        )
    }
}
