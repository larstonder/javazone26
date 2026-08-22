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

    private fun newLifecycle() = RunLifecycle(
        dwellSeconds = DWELL,
        idleTimeoutSeconds = IDLE_TIMEOUT,
        initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
        pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
        exitHoldSeconds = EXIT_HOLD,
        // A zero-length briefing is NO briefing (see `a zero-length briefing...`), so every
        // test written before BRIEFING existed keeps asserting exactly what it always did.
        // Without this, 29 of the 34 would fail: three shared helpers press once and
        // immediately assert the resulting state, and all of them drive with dt = 0f, so a
        // briefing would never expire.
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

    /** Drive a fresh lifecycle from IDLE into a paused run, with every input released. */
    private fun enterPausedRun(lc: RunLifecycle)
    {
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)   // IDLE -> PLAYING
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)  // release
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.PAUSED, lc.state)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = false)
    }

    /** Drive a fresh lifecycle from IDLE, through one full run, into RUN_OVER. */
    private fun enterRunOver(lc: RunLifecycle)
    {
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)   // IDLE -> PLAYING
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)  // release, so later presses are genuine edges
        lc.update(dt = 0f, anyInputPressed = false, runOver = true)   // sim reports over -> RUN_OVER
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
    }

    @Test
    fun `boots into IDLE`() {
        assertEquals(RunLifecycleState.IDLE, newLifecycle().state)
    }

    @Test
    fun `a fresh press leaves IDLE into a fresh run`() {
        val lc = newLifecycle()
        lc.update(dt = 1f / 60f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "caller must construct a new DiveSim on this transition")
    }

    @Test
    fun `IDLE never spontaneously starts without input`() {
        val lc = newLifecycle()
        // Ten seconds of frames at 60fps with nothing pressed. If this class ever ticked
        // toward PLAYING on its own, or the caller's contract ("only tick the sim while
        // PLAYING") were violated, this is what would catch it: IDLE must sit still forever.
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
    fun `no input for the idle timeout returns to IDLE`() {
        val lc = newLifecycle()
        enterRunOver(lc)

        lc.update(dt = IDLE_TIMEOUT - 0.01f, anyInputPressed = false, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state, "must not fall back before the timeout elapses")

        lc.update(dt = 0.02f, anyInputPressed = false, runOver = true)
        assertEquals(RunLifecycleState.IDLE, lc.state)
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
        lc.update(dt = 0f, anyInputPressed = true, runOver = false) // IDLE -> PLAYING
        assertEquals(RunLifecycleState.PLAYING, lc.state)

        repeat(120) { lc.update(dt = 1f / 60f, anyInputPressed = true, runOver = false) }
        assertEquals(RunLifecycleState.PLAYING, lc.state, "kicking/holding buttons mid-run must never restart it")

        lc.update(dt = 0f, anyInputPressed = true, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
    }

    // --- ENTER_INITIALS ---------------------------------------------------------------

    /** Drive a fresh lifecycle from IDLE through a run that banked [score], into RUN_OVER. */
    private fun enterRunOverWithScore(lc: RunLifecycle, score: Int)
    {
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)                          // IDLE -> PLAYING
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)                          // release
        lc.update(dt = 0f, anyInputPressed = false, runOver = true, bankedScore = score)      // -> RUN_OVER
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
    fun `cycling and confirming three letters completes entry and returns to IDLE`() {
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
        assertEquals(RunLifecycleState.IDLE, lc.state)
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

        lc.update(dt = 0f, anyInputPressed = true, runOver = false) // any subsequent update, from IDLE
        assertFalse(lc.initialsJustCompleted, "must clear the next tick, same as justStarted/DiveEnded")
    }

    @Test
    fun `abandoning ENTER_INITIALS for the idle timeout auto-submits rather than losing the score`() {
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
        assertEquals(RunLifecycleState.IDLE, lc.state)
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
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.simulationAdvances)
    }

    @Test
    fun `the sprite keeps animating in IDLE even though the simulation does not`() {
        // spriteAnimates is deliberately NOT the same property as simulationAdvances: DiveSim
        // must stay frozen on an unattended cabinet (it would burn air and "drown" the
        // attract-mode diver otherwise), but the sprite should still kick in place at the
        // surface rather than hold a motionless frame — see DiverSprite.loopPhase's doc.
        val lc = newLifecycle()
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
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)   // IDLE -> PLAYING
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
        // must not find themselves in a phantom run afterwards.
        val lc = newLifecycle()
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
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
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
        // transition.
        val lc = newLifecycle()
        enterRunOver(lc) // pulse: IDLE -> PLAYING -> RUN_OVER, input released throughout

        lc.update(dt = DWELL, anyInputPressed = false, runOver = true) // clear the dwell, still no input
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)

        lc.update(dt = 0.016f, anyInputPressed = true, runOver = true) // a SECOND 1-frame pulse
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "a second pulse must produce a second start")
    }

    @Test
    fun `returning to attract mode is announced exactly once`()
    {
        // EnPustTil rebuilds DiveSim on this flag. Without it the attract screen keeps the
        // last player's final frame - a motionless diver at whatever depth the clock caught
        // him, which after a 120 m timeout is a near-black abyss with a leaderboard in it.
        val lc = newLifecycle()
        enterRunOver(lc)

        // Not worth recording, so RUN_OVER times out straight back to IDLE.
        lc.update(dt = IDLE_TIMEOUT + 0.1f, anyInputPressed = false, runOver = true)

        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertTrue(lc.justReturnedToIdle, "the return to attract must be announced")

        lc.update(dt = 0.016f, anyInputPressed = false, runOver = false)
        assertFalse(lc.justReturnedToIdle, "and must be a one-tick event, not a latched mode")
    }

    @Test
    fun `finishing initials also announces the return to attract mode`()
    {
        // The other way back to IDLE. Both must rebuild the sim, or a player who entered
        // initials leaves their corpse on the attract screen for the next person in the queue.
        val lc = newLifecycle()
        enterRunOver(lc)
        lc.update(dt = DWELL + 0.1f, anyInputPressed = false, runOver = true, bankedScore = 5000)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)

        lc.update(dt = INITIALS_IDLE_TIMEOUT + 0.1f, anyInputPressed = false, runOver = true, bankedScore = 5000)

        assertEquals(RunLifecycleState.IDLE, lc.state)
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
        // would be harmless but the flag must mean one thing only.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.PAUSED, lc.state)

        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)

        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.justReturnedToIdle, "a resume is not a return to attract mode")
    }

    @Test
    fun `a zero-length briefing goes straight from IDLE to PLAYING`()
    {
        // The rule the whole existing test file rests on. newLifecycle() passes
        // briefingSeconds = 0f, so all 34 tests written before BRIEFING existed keep
        // asserting exactly what they always asserted. A zero-length briefing is not a
        // briefing that closes instantly - it is no briefing at all.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "justStarted must still fire on the IDLE transition")
    }

    @Test
    fun `a press in IDLE enters BRIEFING, not PLAYING`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
        assertFalse(lc.justStarted, "the run has not started yet - nothing may build a DiveSim")
        assertTrue(lc.justEnteredBriefing, "EnPustTil latches the pad id on this flag")
    }

    @Test
    fun `justEnteredBriefing is a one-tick event`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertTrue(lc.justEnteredBriefing)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        assertFalse(lc.justEnteredBriefing, "a latched flag would re-latch the pad every frame")
    }

    @Test
    fun `the briefing auto-starts the run when the countdown expires, with no input`()
    {
        // The unattended-recovery guarantee: a player who walks off mid-briefing must not
        // strand the cabinet. The run starts, drowns, and falls through RUN_OVER -> IDLE on
        // the existing timers.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        lc.update(dt = BRIEF + 0.01f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "the auto-start is still a run start")
    }

    @Test
    fun `a press before the dwell does not skip the briefing`()
    {
        // The press that OPENS the briefing must not also close it. Edge detection already
        // forces a release-then-press, but a double-tap is ordinary on an arcade button and
        // would otherwise blow straight past the text.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        lc.update(dt = BRIEF_DWELL * 0.5f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }

    @Test
    fun `a press after the dwell skips the briefing`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
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
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        repeat(10) { lc.update(dt = BRIEF_DWELL * 0.05f, anyInputPressed = true, runOver = false) }
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }

    @Test
    fun `the briefing freezes the simulation but keeps the diver kicking`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertFalse(lc.simulationAdvances, "a briefing that burned air would drown the player")
        assertTrue(lc.spriteAnimates, "a frozen diver behind the briefing reads as dead, not idle")
    }

    @Test
    fun `the countdown counts down, never goes negative, and reads zero outside BRIEFING`()
    {
        val lc = briefingLifecycle()
        assertEquals(0f, lc.briefingCountdownSeconds, "IDLE shares timeInState with BRIEFING")
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(BRIEF, lc.briefingCountdownSeconds, 0.001f)
        lc.update(dt = BRIEF * 0.5f, anyInputPressed = false, runOver = false)
        assertEquals(BRIEF * 0.5f, lc.briefingCountdownSeconds, 0.001f)
    }

    @Test
    fun `briefingSkippable is state-blind-proof`()
    {
        // timeInState is shared by the RUN_OVER, PAUSED and ENTER_INITIALS dwells, so an
        // unguarded `timeInState >= briefingDwellSeconds` would read true in IDLE. Harmless
        // today, but it would pin the wrong contract.
        val lc = briefingLifecycle()
        lc.update(dt = BRIEF_DWELL * 5f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.briefingSkippable, "IDLE is not a skippable briefing")

        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertFalse(lc.briefingSkippable, "not yet - the dwell has not elapsed")
        lc.update(dt = BRIEF_DWELL + 0.01f, anyInputPressed = false, runOver = false)
        assertTrue(lc.briefingSkippable)
    }

    @Test
    fun `an infinite briefing never auto-starts but still skips on a press`()
    {
        // The EPT_BRIEFING_HOLD contract: the capture pin must hold the screen open for a
        // screencapture, and must still be dismissable by hand.
        val lc = RunLifecycle(
            dwellSeconds = DWELL,
            idleTimeoutSeconds = IDLE_TIMEOUT,
            initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
            pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
            exitHoldSeconds = EXIT_HOLD,
            briefingSeconds = Float.POSITIVE_INFINITY,
            briefingDwellSeconds = BRIEF_DWELL
        )
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
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
        // the person who just walked up.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
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
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }
}
