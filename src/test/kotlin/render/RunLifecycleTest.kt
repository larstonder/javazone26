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

    private fun newLifecycle() = RunLifecycle(
        dwellSeconds = DWELL,
        idleTimeoutSeconds = IDLE_TIMEOUT,
        initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT
    )

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
}
