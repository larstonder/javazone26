package render

import score.InitialsEntry
import score.Leaderboard

/** The presentation states a booth run cycles through. */
enum class RunLifecycleState { IDLE, PLAYING, RUN_OVER, ENTER_INITIALS }

/**
 * Pure state machine for the run lifecycle — no pulseengine imports, so it is fully
 * unit-testable without booting the engine. `EnPustTil` owns the single instance of this
 * class and is the only place that reacts to its state (drives whether [dive.DiveSim]
 * gets ticked, which HUD screen is drawn, and when a fresh `DiveSim` is constructed).
 * Presentation/lifecycle state must never leak into `dive.DiveSim`, which stays a pure
 * simulation of a single run.
 *
 * States:
 * - IDLE     — attract mode, entered on boot. The sim is NOT ticked (caller's
 *              responsibility — see [RunLifecycleState.IDLE] usage in EnPustTil). A fresh
 *              input press leaves IDLE into a brand new run.
 * - PLAYING  — a run is in progress. Moves to RUN_OVER the instant the sim reports
 *              `runOver`. Input is otherwise ignored here — kicking/bleeding never
 *              restarts anything.
 * - RUN_OVER — the "RUN OVER" screen. For the first [dwellSeconds] no input can restart —
 *              this is what guarantees the final score is actually readable even if a
 *              button is being held or mashed the instant the clock hits zero. After the
 *              dwell: if the run's score is worth recording (see [Leaderboard
 *              .isWorthRecording]), this state moves ON ITS OWN, with no input required,
 *              into ENTER_INITIALS — a qualifying run always gets a chance at the board.
 *              Otherwise a fresh press restarts immediately, or — with no input at all —
 *              the state falls back to IDLE once [idleTimeoutSeconds] have elapsed since
 *              RUN_OVER was entered (so that value already includes the dwell window, not
 *              on top of it).
 * - ENTER_INITIALS — three-letter arcade-initials entry (see [InitialsEntry]), reached
 *              only for a run worth recording. Completes either when the player confirms
 *              the third letter, or — if nobody touches the controls for
 *              [initialsIdleTimeoutSeconds] — by auto-submitting whatever letters were
 *              set (default "AAA" if nothing was changed). AUTO-SUBMIT rather than
 *              discarding is a deliberate choice: a qualifying score that already exists
 *              is worth more to the leaderboard/prize draw than a "clean" abandonment,
 *              and — just as importantly — the cabinet MUST recover to IDLE on its own so
 *              a walked-away player can never block the next person in the queue from
 *              playing.
 *
 * EDGE-TRIGGERING: [update]'s `anyInputPressed` parameter (and, in ENTER_INITIALS,
 * `cycleUp`/`cycleDown`/`confirmPressed`) are per-frame LEVEL readings (e.g.
 * `Gamepad.isPressed`, which the engine gives no edge-detected alternative for — see
 * EnPustTil's companion doc). This class — and the [InitialsEntry] it owns — track the
 * previous frame themselves and only treat a false-to-true transition as "pressed": a
 * button held down across many frames fires exactly once, not once per frame. This is
 * deliberate and load-bearing: a stuck or noisy button on the booth's USB encoder must
 * not be able to auto-restart the game forever, or blast through every letter of the
 * alphabet in one frame (see the incident this class was written to fix).
 */
class RunLifecycle(
    private val dwellSeconds: Float = DWELL_SECONDS,
    private val idleTimeoutSeconds: Float = IDLE_TIMEOUT_SECONDS,
    private val initialsIdleTimeoutSeconds: Float = INITIALS_IDLE_TIMEOUT_SECONDS
)
{
    var state: RunLifecycleState = RunLifecycleState.IDLE
        private set

    /**
     * True for exactly the [update] call that transitions into PLAYING — the caller's
     * signal to construct a brand new `DiveSim` this frame. False every other call.
     */
    var justStarted: Boolean = false
        private set

    /**
     * One-tick event: true for exactly the [update] call that finishes initials entry —
     * either the player confirmed the third letter, or entry timed out and was
     * auto-submitted (see the ENTER_INITIALS state doc). The caller reads
     * [completedInitials] THIS SAME TICK — the sim that scored the run is still intact,
     * since a completed entry moves to IDLE, not PLAYING, so no new `DiveSim` has been
     * constructed yet — and persists the score. Cleared the next tick, same pattern as
     * [justStarted] and `DiveSim.diveEnded`.
     */
    var initialsJustCompleted: Boolean = false
        private set

    /** Valid only the tick [initialsJustCompleted] is true. */
    var completedInitials: String = ""
        private set

    private val initialsEntry = InitialsEntry()

    /** Current in-progress initials, e.g. "AAA" — for rendering the entry screen. */
    val currentInitials: String get() = initialsEntry.initialsString()

    /** Which of the three slots (0..2) is currently being edited. */
    val currentInitialsSlot: Int get() = initialsEntry.slot

    private var timeInState = 0f
    private var wasInputPressed = false

    /**
     * Advance the lifecycle by one frame.
     *
     * @param dt frame delta time in seconds, used for the dwell and idle timers.
     * @param anyInputPressed whether a restart/start-eligible button reads pressed THIS
     *   frame — a level reading, not pre-edge-detected; see class doc.
     * @param runOver the underlying sim's `runOver` flag; only consulted while PLAYING.
     * @param bankedScore the sim's banked total; only consulted at/after a RUN_OVER
     *   transition, to decide whether to offer initials entry. Defaults to 0 (never
     *   worth recording) so callers that don't care about initials entry — e.g. existing
     *   tests — get the pre-ENTER_INITIALS behaviour unchanged.
     * @param cycleUp / @param cycleDown / @param confirmPressed initials-entry input,
     *   level readings — see [InitialsEntry.update]. Only consulted in ENTER_INITIALS.
     * @return the state after this update.
     */
    fun update(
        dt: Float,
        anyInputPressed: Boolean,
        runOver: Boolean,
        bankedScore: Int = 0,
        cycleUp: Boolean = false,
        cycleDown: Boolean = false,
        confirmPressed: Boolean = false
    ): RunLifecycleState
    {
        justStarted = false
        initialsJustCompleted = false
        timeInState += dt

        val pressedEdge = anyInputPressed && !wasInputPressed
        wasInputPressed = anyInputPressed

        when (state)
        {
            RunLifecycleState.IDLE ->
                if (pressedEdge) enter(RunLifecycleState.PLAYING, started = true)

            RunLifecycleState.PLAYING ->
                if (runOver) enter(RunLifecycleState.RUN_OVER)

            RunLifecycleState.RUN_OVER ->
                if (timeInState >= dwellSeconds)
                {
                    if (Leaderboard.isWorthRecording(bankedScore))
                        enter(RunLifecycleState.ENTER_INITIALS)
                    else if (pressedEdge)
                        enter(RunLifecycleState.PLAYING, started = true)
                    else if (timeInState >= idleTimeoutSeconds)
                        enter(RunLifecycleState.IDLE)
                }
                else if (timeInState >= idleTimeoutSeconds)
                    enter(RunLifecycleState.IDLE)

            RunLifecycleState.ENTER_INITIALS ->
            {
                initialsEntry.update(cycleUp, cycleDown, confirmPressed)
                if (initialsEntry.complete || timeInState >= initialsIdleTimeoutSeconds)
                    finishInitials()
            }
        }

        return state
    }

    private fun finishInitials()
    {
        completedInitials = initialsEntry.initialsString()
        initialsJustCompleted = true
        enter(RunLifecycleState.IDLE)
    }

    private fun enter(newState: RunLifecycleState, started: Boolean = false)
    {
        state = newState
        timeInState = 0f
        justStarted = started
        if (newState == RunLifecycleState.ENTER_INITIALS) initialsEntry.reset()
    }

    companion object
    {
        /** Minimum time RUN_OVER stays on screen before any input can restart. */
        const val DWELL_SECONDS = 2.5f

        /**
         * Total time (from entering RUN_OVER, dwell included) before falling back to IDLE
         * with no input at all — 2.5s dwell + 15s of true idle.
         */
        const val IDLE_TIMEOUT_SECONDS = 17.5f

        /**
         * Time in ENTER_INITIALS, with no input at all, before auto-submitting whatever
         * letters were set and returning to IDLE — see the ENTER_INITIALS state doc for
         * why this auto-submits rather than discarding.
         */
        const val INITIALS_IDLE_TIMEOUT_SECONDS = 15f
    }
}
