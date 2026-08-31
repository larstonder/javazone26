package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunLifecycleTest
{
    private val DWELL = 2f
    private val IDLE_TIMEOUT = 5f // measured from RUN_OVER entry, i.e. 3s of true idle after the dwell
    private val INITIALS_IDLE_TIMEOUT = 4f // measured from ENTER_INITIALS entry

    private val PAUSE_IDLE_TIMEOUT = 6f // measured from PAUSED entry
    private val EXIT_HOLD = 1f          // unbroken seconds of the exit input to actually quit

    // Briefing timings for the tests that actually exercise it. The shared newLifecycle()
    // factory passes briefingSeconds = 0f instead — see `a zero-length briefing...` below.
    private val BRIEF = 3f
    private val BRIEF_DWELL = 0.5f

    /**
     * Drive a freshly booted lifecycle (MAIN_MENU) forward to IDLE via the
     * MENU_IDLE_TIMEOUT_SECONDS fallback — the real unattended-recovery path an abandoned
     * menu takes at the booth, not a shortcut invented for tests. `enter()` zeroes
     * timeInState on every transition, so arriving at IDLE this way leaves no residual timer
     * behind, and works regardless of how much timeInState the lifecycle already had
     * accumulated in MAIN_MENU when this is called.
     *
     * AMENDMENT (2026-08-31, task-4-ruling.md): this used to be wired into newLifecycle()
     * and briefingLifecycle() themselves, so EVERY test transparently started at IDLE. That
     * was the wrong call — the spec's own transition table has "start a dive" move from
     * IDLE's press to MAIN_MENU's menuAction, and burying every test's arrival at IDLE inside
     * the shared factories made the whole suite silently keep testing the retired flow. This
     * helper now stays, but is called explicitly only by the handful of tests that are
     * genuinely about IDLE/attract-mode behaviour; everything else starts at the real boot
     * state, MAIN_MENU, and drives a dive with `menuAction` — the path a player now takes.
     */
    private fun warmToIdle(lc: RunLifecycle): RunLifecycle
    {
        lc.update(dt = RunLifecycle.MENU_IDLE_TIMEOUT_SECONDS + 0.1f, anyInputPressed = false, runOver = false)
        check(lc.state == RunLifecycleState.IDLE) { "warmToIdle did not reach IDLE, reached ${lc.state}" }
        return lc
    }

    /**
     * A freshly booted lifecycle — MAIN_MENU, the real state `RunLifecycle()` starts in.
     * Most tests below drive a dive from here with `menuAction`, exactly as a player now
     * does; the few tests that are genuinely about IDLE/attract-mode wrap this in
     * [warmToIdle] instead.
     */
    private fun newLifecycle() = RunLifecycle(
        dwellSeconds = DWELL,
        idleTimeoutSeconds = IDLE_TIMEOUT,
        initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
        pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
        exitHoldSeconds = EXIT_HOLD,
        // A zero-length briefing is NO briefing (see `a zero-length briefing...`), so every
        // test written before BRIEFING existed keeps asserting exactly what it always did.
        briefingSeconds = 0f
    )

    private fun briefingLifecycle() = RunLifecycle(
        dwellSeconds = DWELL,
        idleTimeoutSeconds = IDLE_TIMEOUT,
        initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
        pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
        exitHoldSeconds = EXIT_HOLD,
        briefingSeconds = BRIEF,
        briefingDwellSeconds = BRIEF_DWELL
    )

    /**
     * Drive a fresh (MAIN_MENU) lifecycle into a paused run, with every input released.
     *
     * AMENDMENT (2026-08-31): used to be `warmToIdle` + a press (IDLE -> PLAYING). Rewritten
     * per task-4-ruling.md to exercise the path a player actually takes now: MAIN_MENU's
     * `menuAction`, straight to PLAYING because every caller here uses `newLifecycle()`,
     * whose `briefingSeconds = 0f` means no briefing.
     */
    private fun enterPausedRun(lc: RunLifecycle)
    {
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)   // MAIN_MENU -> PLAYING
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = false)  // release
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.PAUSED, lc.state)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = false)
    }

    /**
     * Drive a fresh (MAIN_MENU) lifecycle, through one full run, into RUN_OVER.
     *
     * AMENDMENT (2026-08-31): same rewrite as [enterPausedRun] — MAIN_MENU's `menuAction`
     * replaces the old IDLE press.
     */
    private fun enterRunOver(lc: RunLifecycle)
    {
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)   // MAIN_MENU -> PLAYING
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = false)  // release, so later presses are genuine edges
        lc.update(dt = 0f, anyInputPressed = false, runOver = true)   // sim reports over -> RUN_OVER
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
    }

    @Test
    fun `the game boots into the main menu, not the attract screen`() {
        // AMENDMENT (2026-08-31): this test used to be named "boots into IDLE" and asserted
        // exactly that. MAIN_MENU is the real boot state now (`state` initialises to it — see
        // RunLifecycle's class doc); renamed and re-pointed rather than left asserting the
        // old default.
        assertEquals(RunLifecycleState.MAIN_MENU, newLifecycle().state)
    }

    @Test
    fun `an abandoned main menu falls back to the attract screen after its own timeout`() {
        // The other half of the boot-state test above: MAIN_MENU is not a dead end if nobody
        // touches it. This is the exact mechanism [warmToIdle] relies on internally, asserted
        // here directly rather than only trusted via that helper's own `check`.
        val lc = newLifecycle()
        lc.update(dt = RunLifecycle.MENU_IDLE_TIMEOUT_SECONDS - 0.1f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.MAIN_MENU, lc.state, "must not fall back before the timeout elapses")

        lc.update(dt = 0.2f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.IDLE, lc.state)
    }

    @Test
    fun `the four booth timeout constants hold their values`() {
        // Pinned so a later "let's round these" edit reddens the build instead of silently
        // changing how long an unattended cabinet waits before recovering. These four are
        // exactly the ones task-4-ruling.md called booth-hardening and off-limits for this
        // task: the three idle timeouts, plus the exit hold.
        assertEquals(17.5f, RunLifecycle.IDLE_TIMEOUT_SECONDS)
        assertEquals(20f, RunLifecycle.PAUSE_IDLE_TIMEOUT_SECONDS)
        assertEquals(15f, RunLifecycle.INITIALS_IDLE_TIMEOUT_SECONDS)
        assertEquals(1.5f, RunLifecycle.EXIT_HOLD_SECONDS)
    }

    @Test
    fun `a fresh press in IDLE opens the main menu, not a fresh run`() {
        // AMENDMENT (2026-08-31): this test used to be `a fresh press leaves IDLE into a
        // fresh run`, asserting IDLE + press -> PLAYING directly. That is exactly the flow
        // this task retired (see RunLifecycle's IDLE amendment and task-4-ruling.md), so
        // keeping it green would mean not implementing the spec. Rewritten, not deleted, to
        // assert the transition that replaced it — this is the "add a test for the new
        // IDLE + press -> MAIN_MENU transition" requirement from the brief.
        val lc = warmToIdle(newLifecycle())
        lc.update(dt = 1f / 60f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.MAIN_MENU, lc.state)
        assertFalse(lc.justStarted, "opening the menu from attract must not itself start a dive")
    }

    @Test
    fun `IDLE never spontaneously starts without input`() {
        val lc = warmToIdle(newLifecycle())
        // Ten seconds of frames at 60fps with nothing pressed. If this class ever ticked
        // toward MAIN_MENU or PLAYING on its own, or the caller's contract ("only tick the
        // sim while PLAYING") were violated, this is what would catch it: IDLE must sit
        // still forever without a press.
        repeat(600) { lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = false) }
        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.justStarted)
    }

    @Test
    fun `a fresh press during the dwell window does NOT restart`() {
        val lc = newLifecycle()
        enterRunOver(lc)

        lc.update(dt = DWELL * 0.25f, anyInputPressed = false, runOver = true)
        lc.update(dt = 0f, anyInputPressed = true, runOver = true) // fresh press, well inside the dwell
        assertEquals(RunLifecycleState.RUN_OVER, lc.state, "the score must stay on screen through the dwell")
        assertFalse(lc.justStarted)
    }

    @Test
    fun `a press after the dwell DOES restart`() {
        val lc = newLifecycle()
        enterRunOver(lc)

        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true) // clear the dwell, no input yet
        lc.update(dt = 0f, anyInputPressed = true, runOver = true)             // fresh press after the dwell
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted)
    }

    @Test
    fun `no input for the idle timeout returns to the menu`() {
        // AMENDMENT (2026-08-31): this test used to be named "...returns to IDLE" and
        // asserted exactly that. RUN_OVER's unattended fallback now lands on MAIN_MENU
        // (RunLifecycle.returnToMenu) — a player who just finished a run gets "play again"
        // one press away, not the screensaver. idleTimeoutSeconds itself (the VALUE under
        // test here) is completely untouched; only the destination moved, and only the final
        // assertion below reflects that.
        val lc = newLifecycle()
        enterRunOver(lc)

        lc.update(dt = IDLE_TIMEOUT - 0.01f, anyInputPressed = false, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state, "must not fall back before the timeout elapses")

        lc.update(dt = 0.02f, anyInputPressed = false, runOver = true)
        assertEquals(RunLifecycleState.MAIN_MENU, lc.state)
    }

    @Test
    fun `a held button does not repeatedly restart`() {
        val lc = newLifecycle()
        enterRunOver(lc)

        // Pressed once, mid-dwell: this is a genuine edge, but the dwell still blocks it.
        lc.update(dt = DWELL * 0.5f, anyInputPressed = true, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)

        // Cross the dwell boundary while STILL holding the same button — no NEW edge occurs,
        // so this must not restart even though the level reads true and enough time has
        // passed. Without edge-tracking (e.g. checking the level instead of a transition)
        // this assertion fails: the game would auto-restart the instant the dwell ends,
        // exactly the stuck-button failure mode this class exists to prevent.
        lc.update(dt = DWELL, anyInputPressed = true, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state, "a held button must not restart once the dwell passes")
        assertFalse(lc.justStarted)
    }

    @Test
    fun `releasing and pressing again after the dwell restarts exactly once`() {
        val lc = newLifecycle()
        enterRunOver(lc)

        lc.update(dt = DWELL * 0.5f, anyInputPressed = true, runOver = true)  // held from before the dwell ends
        lc.update(dt = DWELL, anyInputPressed = false, runOver = true)        // released
        lc.update(dt = 0f, anyInputPressed = true, runOver = true)            // genuine fresh press
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted)
    }

    @Test
    fun `PLAYING ignores input entirely and only reacts to runOver`() {
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true) // MAIN_MENU -> PLAYING
        assertEquals(RunLifecycleState.PLAYING, lc.state)

        repeat(120) { lc.update(dt = 1f / 60f, anyInputPressed = true, runOver = false) }
        assertEquals(RunLifecycleState.PLAYING, lc.state, "kicking/holding buttons mid-run must never restart it")

        lc.update(dt = 0f, anyInputPressed = true, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
    }

    // --- ENTER_INITIALS ---------------------------------------------------------------

    /**
     * Drive a fresh (MAIN_MENU) lifecycle through a run that banked [score], into RUN_OVER.
     *
     * AMENDMENT (2026-08-31): same MAIN_MENU/menuAction rewrite as [enterRunOver].
     */
    private fun enterRunOverWithScore(lc: RunLifecycle, score: Int)
    {
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)    // MAIN_MENU -> PLAYING
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = false)   // release
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, bankedScore = score)   // -> RUN_OVER
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
    }

    @Test
    fun `a zero-point run never offers initials entry - it stays on the plain RUN_OVER screen past the dwell`() {
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 0)

        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true, bankedScore = 0)

        assertEquals(
            RunLifecycleState.RUN_OVER, lc.state,
            "a 0-point run must not block the cabinet with a data-entry prompt"
        )
    }

    @Test
    fun `a worth-recording score auto-enters ENTER_INITIALS after the dwell, with no press required`() {
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 500)

        // Still within the dwell: must not have jumped ahead yet.
        lc.update(dt = DWELL * 0.5f, anyInputPressed = false, runOver = true, bankedScore = 500)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)

        lc.update(dt = DWELL, anyInputPressed = false, runOver = true, bankedScore = 500)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)
    }

    @Test
    fun `ENTER_INITIALS starts fresh at AAA regardless of a previous entry's leftover letters`() {
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 500)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true, bankedScore = 500)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)
        assertEquals("AAA", lc.currentInitials)
        assertEquals(0, lc.currentInitialsSlot)
    }

    @Test
    fun `cycling and confirming three letters completes entry and returns to the menu`() {
        // AMENDMENT (2026-08-31): this test used to be named "...and returns to IDLE" and
        // asserted exactly that. ENTER_INITIALS' completion now lands on MAIN_MENU
        // (RunLifecycle.finishInitials -> returnToMenu) — renamed and re-pointed rather than
        // left asserting the old destination.
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 500)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true, bankedScore = 500)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)

        // Slot 0: cycle up twice (A -> B -> C), confirm.
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, cycleUp = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, cycleUp = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, cycleUp = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, cycleUp = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = true)
        assertFalse(lc.initialsJustCompleted)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = false)

        // Slot 1 and 2: confirm immediately, leaving them at "A".
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = true)

        assertTrue(lc.initialsJustCompleted, "must fire the tick the third slot is confirmed")
        assertEquals("CAA", lc.completedInitials)
        assertEquals(RunLifecycleState.MAIN_MENU, lc.state)
    }

    @Test
    fun `initialsJustCompleted is a one-tick event, cleared the next update`() {
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 500)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true, bankedScore = 500)
        repeat(2) {
            lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = true)
            lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = false)
        }
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, confirmPressed = true) // confirms slot 2 -> complete
        assertTrue(lc.initialsJustCompleted, "must be true on the exact tick entry completes")

        // AMENDMENT (2026-08-31): comment used to say "from IDLE" — entry now completes onto
        // MAIN_MENU (see the test above), but the point of this line is unchanged: any
        // subsequent update must clear the one-tick flag.
        lc.update(dt = 0f, anyInputPressed = true, runOver = false) // any subsequent update, from MAIN_MENU
        assertFalse(lc.initialsJustCompleted, "must clear the next tick, same as justStarted/DiveEnded")
    }

    @Test
    fun `abandoning ENTER_INITIALS for the idle timeout auto-submits rather than losing the score`() {
        // AMENDMENT (2026-08-31): the final assertion used to be IDLE; auto-submit now lands
        // on MAIN_MENU (returnToMenu) — same reasoning as the "cycling and confirming..." test
        // above. Everything about the auto-submit ITSELF (that it fires, that it keeps
        // whatever letters were set) is unchanged and un-weakened, per task-4-ruling.md.
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 500)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true, bankedScore = 500)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)

        // Cycle slot 0 up once, then walk away — never confirm anything.
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, cycleUp = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, cycleUp = false)

        lc.update(dt = INITIALS_IDLE_TIMEOUT, anyInputPressed = false, runOver = true)

        assertTrue(lc.initialsJustCompleted, "the cabinet must recover on its own, not stay stuck forever")
        assertEquals("BAA", lc.completedInitials, "whatever was set at the moment of timeout must be kept")
        assertEquals(RunLifecycleState.MAIN_MENU, lc.state)
    }

    @Test
    fun `holding the cycle-up input in ENTER_INITIALS does not blast through every letter in one frame`() {
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 500)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true, bankedScore = 500)

        repeat(30) { lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = true, cycleUp = true) }

        assertEquals("BAA", lc.currentInitials, "a held stick must cycle exactly once, not once per frame")
    }

    // --- Pause and exit (Esc) ----------------------------------------------------------

    @Test
    fun `a paused run does not advance the simulation`() {
        // The whole point. DiveSim.tick is the only place the clock counts down and air
        // burns, EnPustTil gates that call on simulationAdvances and nothing else, so this
        // property IS the pause. If it were true here, a player could stop and think for
        // free about a dive they are losing.
        val lc = newLifecycle()
        enterPausedRun(lc)
        assertFalse(lc.simulationAdvances, "a paused run must be frozen, not merely covered up")
    }

    @Test
    fun `a run that is merely playing does advance the simulation`() {
        // The other half of the above: without this, freezing everything forever would pass.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true) // MAIN_MENU -> PLAYING
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.simulationAdvances)
    }

    @Test
    fun `the sprite keeps animating in IDLE even though the simulation does not`() {
        // spriteAnimates is deliberately NOT the same property as simulationAdvances: DiveSim
        // must stay frozen on an unattended cabinet (it would burn air and "drown" the
        // attract-mode diver otherwise), but the sprite should still kick in place at the
        // surface rather than hold a motionless frame — see DiverSprite.loopPhase's doc.
        val lc = warmToIdle(newLifecycle())
        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.simulationAdvances, "IDLE must not tick DiveSim")
        assertTrue(lc.spriteAnimates, "IDLE must still animate the sprite")
    }

    @Test
    fun `a paused run does not animate the sprite either`() {
        // Unlike IDLE, PAUSED keeps the diver still: the pause screen's promise is that the
        // run is being HELD, not ended, and a diver swimming behind the scrim would
        // contradict that on sight.
        val lc = newLifecycle()
        enterPausedRun(lc)
        assertFalse(lc.spriteAnimates, "a paused run must freeze the sprite along with the sim")
    }

    @Test
    fun `the sprite also animates during ENTER_INITIALS`() {
        // The fourth and last state spriteAnimates has to get right: a player typing their
        // initials must still see themselves kicking, not a diver frozen behind the entry
        // screen. RunLifecycleTest previously covered IDLE, PAUSED and PLAYING/RUN_OVER here
        // but never this state — an exhaustive `when` with no `else` still lets a WRONG
        // per-branch answer compile, so only a test that actually reaches ENTER_INITIALS and
        // asks catches that.
        val lc = newLifecycle()
        enterRunOverWithScore(lc, score = 5000)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true, bankedScore = 5000)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)
        assertTrue(lc.spriteAnimates, "a frozen diver behind the initials screen reads as dead, not idle")
    }

    @Test
    fun `spriteAnimates agrees with simulationAdvances everywhere except IDLE and BRIEFING`() {
        // Reads BOTH properties and compares them, rather than only asserting spriteAnimates
        // is true — the earlier version of this test did the latter, which cannot fail from a
        // simulationAdvances-side regression: it would have stayed green even if
        // simulationAdvances silently disagreed with spriteAnimates in RUN_OVER, because
        // nothing here ever called simulationAdvances for that state.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)   // MAIN_MENU -> PLAYING
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertEquals(lc.simulationAdvances, lc.spriteAnimates, "PLAYING must not disagree")

        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true)   // -> RUN_OVER
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
        assertEquals(lc.simulationAdvances, lc.spriteAnimates, "RUN_OVER must not disagree")
    }

    @Test
    fun `resuming returns to the run rather than restarting it`() {
        // A resume that set justStarted would have EnPustTil build a fresh DiveSim and throw
        // away the dive — the single worst thing a pause screen could do to a player.
        val lc = newLifecycle()
        enterPausedRun(lc)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertFalse(lc.justStarted, "resume must NOT construct a new DiveSim")
        assertTrue(lc.simulationAdvances)
    }

    @Test
    fun `Esc from attract opens the same screen and returns to attract`() {
        // A technician must be able to shut the cabinet down without a run in progress, and
        // must not find themselves in a phantom run afterwards. Genuinely about IDLE, so this
        // explicitly warms to it rather than starting at the real MAIN_MENU boot state.
        val lc = warmToIdle(newLifecycle())
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.PAUSED, lc.state)
        assertTrue(lc.pausedFromIdle, "the screen must word itself as a cabinet menu, not a paused run")

        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.justStarted, "closing the technician's menu must not start a run")
    }

    @Test
    fun `a paused run reports itself as paused from a run, not from attract`() {
        val lc = newLifecycle()
        enterPausedRun(lc)
        assertFalse(lc.pausedFromIdle)
    }

    @Test
    fun `a held pause key pauses once instead of flickering every frame`() {
        // Same edge-triggering contract as every other lifecycle input: the engine's Gamepad
        // has no wasClicked, so this class does its own edge detection. A held key that
        // toggled per frame would make the pause screen strobe.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)  // MAIN_MENU -> PLAYING
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = false)
        repeat(60) { lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = false, pausePressed = true) }
        assertEquals(RunLifecycleState.PAUSED, lc.state, "a held key must not toggle back out")
    }

    @Test
    fun `exit needs an unbroken hold, so mashing can never accumulate to it`() {
        // The safety property. An attendee who finds Esc mid-run and then leans on a key
        // must not be able to kill the cabinet; a technician holding deliberately must.
        val lc = newLifecycle()
        enterPausedRun(lc)

        // Five bursts of half the required hold, each followed by a release. Total held time
        // is 2.5x EXIT_HOLD, so an implementation that accumulates across releases reaches
        // the exit during burst two.
        //
        // Two things here are load-bearing and were BOTH wrong in the first draft of this
        // test, which passed against a deliberately broken implementation:
        //   - exitRequested is checked on EVERY frame, not once per burst. It is a one-tick
        //     event, so a check after the release frame has already missed it.
        //   - the whole sequence must stay inside PAUSE_IDLE_TIMEOUT. Overrun it and the
        //     pause auto-resumes to PLAYING, where exitHeld is ignored and the progress bar
        //     has been reset — every later assertion then passes for the wrong reason.
        repeat(5) {
            repeat(30) {
                lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = false, exitHeld = true)
                assertFalse(lc.exitRequested, "a broken hold must never reach the exit")
            }
            lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = false, exitHeld = false)
            assertFalse(lc.exitRequested, "a broken hold must never reach the exit")
            assertEquals(0f, lc.exitHoldProgress, 0.0001f, "releasing must reset the progress bar to empty")
        }
        assertEquals(RunLifecycleState.PAUSED, lc.state, "the pause must not have timed out mid-test")
    }

    @Test
    fun `an unbroken hold does exit, exactly once`() {
        val lc = newLifecycle()
        enterPausedRun(lc)

        var fired = 0
        repeat(240) {
            lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = false, exitHeld = true)
            if (lc.exitRequested) fired++
        }
        assertEquals(1, fired, "exitRequested is a one-tick event, not a latched flag")
    }

    @Test
    fun `the exit hold reports its progress so the bar is not a dead key`() {
        val lc = newLifecycle()
        enterPausedRun(lc)
        assertEquals(0f, lc.exitHoldProgress, 0.0001f)
        repeat(30) { lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = false, exitHeld = true) }
        val half = lc.exitHoldProgress
        assertTrue(half > 0.3f && half < 0.7f, "half a hold should read as about half a bar, was $half")
    }

    @Test
    fun `a walked-away pause resumes on its own so it cannot block the queue`() {
        // Every other state in this machine recovers unattended. A pause screen that sat
        // forever would be the one that does not, which at a booth means the next person in
        // the queue finds a dead cabinet.
        val lc = newLifecycle()
        enterPausedRun(lc)
        repeat((PAUSE_IDLE_TIMEOUT * 60).toInt() + 2) {
            lc.update(dt = 1f / 60f, anyInputPressed = false, runOver = false)
        }
        assertEquals(RunLifecycleState.PLAYING, lc.state, "an abandoned pause must let the run continue")
        assertFalse(lc.justStarted, "and must not restart it")
    }

    @Test
    fun `two one-frame input pulses each start a run, back to back`()
    {
        // EnPustTil now passes an EDGE (LifecycleInputEdges.commit()) where it used to
        // pass a level. An earlier version of this test only checked that a SINGLE pulse
        // fires and does not latch `justStarted` on the very next frame — but that second
        // assertion cannot fail regardless: `justStarted = false` is the first statement
        // of update(), and PLAYING already ignores input entirely (see `PLAYING ignores
        // input entirely...` above), so nothing about edge-vs-level was actually being
        // exercised. The property genuinely at risk is a SECOND pulse, after a full run
        // and past the dwell, still working — the pulse equivalent of `releasing and
        // pressing again after the dwell restarts exactly once`, which used a held-then-
        // released LEVEL shape EnPustTil no longer produces. This fails if
        // `wasInputPressed` is ever left latched true across a PLAYING -> RUN_OVER
        // transition. RUN_OVER's own restart-on-press is unaffected by the MAIN_MENU work
        // (it still reads `anyInputPressed` directly, not `menuAction`), so `enterRunOver`'s
        // menuAction-based entry into the first run does not change what this test exercises.
        val lc = newLifecycle()
        enterRunOver(lc) // MAIN_MENU -> PLAYING -> RUN_OVER, input released throughout

        lc.update(dt = DWELL, anyInputPressed = false, runOver = true) // clear the dwell, still no input
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)

        lc.update(dt = 0.016f, anyInputPressed = true, runOver = true) // a SECOND 1-frame pulse
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "a second pulse must produce a second start")
    }

    @Test
    fun `returning to the menu after a run is announced exactly once`()
    {
        // AMENDMENT (2026-08-31): this test used to be named "returning to attract mode is
        // announced exactly once" and asserted a final IDLE state. RUN_OVER's idle-timeout
        // fallback now lands on MAIN_MENU (RunLifecycle.returnToMenu). justReturnedToIdle's
        // CONTRACT (EnPustTil rebuilds DiveSim so the next screen never shows the finished
        // run's last frame) is completely unchanged — only the destination state moved, so
        // this test is renamed and re-pointed rather than left asserting the old destination.
        val lc = newLifecycle()
        enterRunOver(lc)

        // Not worth recording, so RUN_OVER times out straight back to the menu.
        lc.update(dt = IDLE_TIMEOUT + 0.1f, anyInputPressed = false, runOver = true)

        assertEquals(RunLifecycleState.MAIN_MENU, lc.state)
        assertTrue(lc.justReturnedToIdle, "the return to the menu must be announced")

        lc.update(dt = 0.016f, anyInputPressed = false, runOver = false)
        assertFalse(lc.justReturnedToIdle, "and must be a one-tick event, not a latched mode")
    }

    @Test
    fun `finishing initials also announces the return to the menu`()
    {
        // AMENDMENT (2026-08-31): this test used to be named "...return to attract mode" and
        // asserted a final IDLE state. Same rewrite as the test above — ENTER_INITIALS'
        // completion now lands on MAIN_MENU too, via the same returnToMenu().
        val lc = newLifecycle()
        enterRunOver(lc)
        lc.update(dt = DWELL + 0.1f, anyInputPressed = false, runOver = true, bankedScore = 5000)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)

        lc.update(dt = INITIALS_IDLE_TIMEOUT + 0.1f, anyInputPressed = false, runOver = true, bankedScore = 5000)

        assertEquals(RunLifecycleState.MAIN_MENU, lc.state)
        assertTrue(lc.justReturnedToIdle)
        // The Critical this task's own review caught (task-9-report.md) was exactly this
        // co-firing: EnPustTil read `justReturnedToIdle` (rebuilding `sim`) before
        // `initialsJustCompleted` (reading `sim.banked`), silently zeroing every leaderboard
        // entry. Without this line, the test only proved the flag existed — not that it fires
        // on the SAME tick as the score-persist signal, which is the entire premise the fix
        // and UpdateGameOrderingTest depend on.
        assertTrue(lc.initialsJustCompleted, "both flags must fire on the same tick, or the ordering fix has nothing to order")
    }

    @Test
    fun `resuming a run paused from attract mode does not announce a return`()
    {
        // PAUSED -> IDLE is a resume, not a fresh attract screen. Rebuilding the sim there
        // would be harmless but the flag must mean one thing only. Genuinely about IDLE (Esc
        // is only handled in IDLE and PLAYING — see RunLifecycle's PAUSED doc), so this
        // explicitly warms to it.
        val lc = warmToIdle(newLifecycle())
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.PAUSED, lc.state)

        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)

        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.justReturnedToIdle, "a resume is not a return to attract mode")
    }

    @Test
    fun `a zero-length briefing goes straight from MAIN_MENU to PLAYING`()
    {
        // AMENDMENT (2026-08-31): this test used to be named "...from IDLE to PLAYING" and
        // drove `anyInputPressed` from IDLE. Starting a dive moved from IDLE's press to
        // MAIN_MENU's menuAction (see RunLifecycle's IDLE amendment); IDLE's own press now
        // only opens the menu — see `a fresh press in IDLE opens the main menu...` above. The
        // rule under test — a zero-length briefing IS no briefing at all — is itself
        // unchanged, and is what the whole rest of this file rests on via newLifecycle()'s
        // briefingSeconds = 0f.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "justStarted must still fire on the MAIN_MENU transition")
    }

    @Test
    fun `a menu action in MAIN_MENU enters BRIEFING, not PLAYING`()
    {
        // AMENDMENT (2026-08-31): this test used to be named "a press in IDLE enters
        // BRIEFING, not PLAYING" and drove `anyInputPressed` from IDLE. Entering BRIEFING is
        // MAIN_MENU's decision now, via `menuAction` — IDLE's press no longer starts
        // anything, it only opens the menu.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
        assertFalse(lc.justStarted, "the run has not started yet - nothing may build a DiveSim")
        assertTrue(lc.justEnteredBriefing, "EnPustTil latches the pad id on this flag")
    }

    @Test
    fun `justEnteredBriefing is a one-tick event`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        assertTrue(lc.justEnteredBriefing)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        assertFalse(lc.justEnteredBriefing, "a latched flag would re-latch the pad every frame")
    }

    @Test
    fun `the briefing auto-starts the run when the countdown expires, with no input`()
    {
        // The unattended-recovery guarantee: a player who walks off mid-briefing must not
        // strand the cabinet. The run starts, drowns, and falls through RUN_OVER -> MAIN_MENU
        // on the existing timers.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = false)
        lc.update(dt = BRIEF + 0.01f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "the auto-start is still a run start")
    }

    @Test
    fun `a press before the dwell does not skip the briefing`()
    {
        // The press that OPENS the briefing must not also close it. Edge detection already
        // forces a release-then-press, but a double-tap is ordinary on an arcade button and
        // would otherwise blow straight past the text. Entry is via menuAction (see the
        // BRIEFING-entry amendment above); the skip check itself still reads anyInputPressed,
        // unchanged.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = false)
        lc.update(dt = BRIEF_DWELL * 0.5f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }

    @Test
    fun `a press after the dwell skips the briefing`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = false)
        lc.update(dt = BRIEF_DWELL + 0.01f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted)
    }

    @Test
    fun `a held button does not skip the briefing`()
    {
        // A stuck booth encoder button must never be able to skip the explanation for every
        // person in the queue - the same reasoning as the RUN_OVER dwell.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        // 10 * (BRIEF_DWELL * 0.2) = 1.0s: past the 0.5s dwell but short of BRIEF (3s), so this
        // genuinely exercises the gate AFTER the dwell has elapsed - the button is held the whole
        // time (never released), so a correct edge-detected gate stays refused throughout, while a
        // regressed level read would fire the instant timeInState crosses the dwell.
        repeat(10) { lc.update(dt = BRIEF_DWELL * 0.2f, anyInputPressed = true, runOver = false) }
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }

    @Test
    fun `the briefing freezes the simulation but keeps the diver kicking`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        assertFalse(lc.simulationAdvances, "a briefing that burned air would drown the player")
        assertTrue(lc.spriteAnimates, "a frozen diver behind the briefing reads as dead, not idle")
    }

    @Test
    fun `the countdown counts down, never goes negative, and reads zero outside BRIEFING`()
    {
        val lc = briefingLifecycle()
        assertEquals(0f, lc.briefingCountdownSeconds, "MAIN_MENU shares timeInState with BRIEFING")
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        assertEquals(BRIEF, lc.briefingCountdownSeconds, 0.001f)
        lc.update(dt = BRIEF * 0.5f, anyInputPressed = false, runOver = false)
        assertEquals(BRIEF * 0.5f, lc.briefingCountdownSeconds, 0.001f)
    }

    @Test
    fun `briefingSkippable is state-blind-proof`()
    {
        // timeInState is shared by MAIN_MENU, IDLE, RUN_OVER, PAUSED and ENTER_INITIALS, so
        // an unguarded `timeInState >= briefingDwellSeconds` would read true outside BRIEFING
        // too. Checked in both non-BRIEFING states this task actually touches.
        val menuLc = briefingLifecycle()
        menuLc.update(dt = BRIEF_DWELL * 5f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.MAIN_MENU, menuLc.state)
        assertFalse(menuLc.briefingSkippable, "MAIN_MENU is not a skippable briefing")

        val idleLc = warmToIdle(briefingLifecycle())
        idleLc.update(dt = BRIEF_DWELL * 5f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.IDLE, idleLc.state)
        assertFalse(idleLc.briefingSkippable, "IDLE is not a skippable briefing either")

        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true) // -> BRIEFING
        assertFalse(lc.briefingSkippable, "not yet - the dwell has not elapsed")
        lc.update(dt = BRIEF_DWELL + 0.01f, anyInputPressed = false, runOver = false)
        assertTrue(lc.briefingSkippable)
    }

    @Test
    fun `an infinite briefing never auto-starts but still skips on a press`()
    {
        // The EPT_BRIEFING_HOLD contract: the capture pin must hold the screen open for a
        // screencapture, and must still be dismissable by hand. Constructed directly (not via
        // briefingLifecycle()) because this needs its own briefingSeconds, and entered via
        // menuAction like every other route into BRIEFING now.
        val lc = RunLifecycle(
            dwellSeconds = DWELL,
            idleTimeoutSeconds = IDLE_TIMEOUT,
            initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
            pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
            exitHoldSeconds = EXIT_HOLD,
            briefingSeconds = Float.POSITIVE_INFINITY,
            briefingDwellSeconds = BRIEF_DWELL
        )
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        assertFalse(lc.briefingAutoStarts, "drawBriefingScreen suppresses the countdown on this")
        lc.update(dt = 10_000f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.BRIEFING, lc.state, "it must hold for the capture")
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
    }

    @Test
    fun `a retry from RUN_OVER does not re-brief`()
    {
        // The owner's decision, pinned so a later "for consistency" change reddens the build.
        // A player who just drowned in eight seconds retries instantly; the briefing is for
        // the person who just walked up. Protected by task-4-ruling.md: only the entry into
        // the FIRST briefing (menuAction, see above) changed here - the retry itself still
        // reads anyInputPressed directly from RUN_OVER and must still go straight to PLAYING.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        lc.update(dt = BRIEF + 0.01f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true)
        lc.update(dt = 0f, anyInputPressed = true, runOver = true)
        assertEquals(RunLifecycleState.PLAYING, lc.state, "a retry goes straight back to the water")
    }

    @Test
    fun `Esc during the briefing does not pause`()
    {
        // Consistent with RUN_OVER and ENTER_INITIALS, which also ignore it. A technician
        // waits at most one countdown for the cabinet menu.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, menuAction = true)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }
}
