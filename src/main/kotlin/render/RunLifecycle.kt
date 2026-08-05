package render

/** The three presentation states a booth run cycles through. */
enum class RunLifecycleState { IDLE, PLAYING, RUN_OVER }

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
 *              dwell, a fresh press restarts immediately. With no input at all, the state
 *              falls back to IDLE once [idleTimeoutSeconds] have elapsed since RUN_OVER was
 *              entered (so that value already includes the dwell window, not on top of it).
 *
 * EDGE-TRIGGERING: [update]'s `anyInputPressed` parameter is a per-frame LEVEL reading
 * (e.g. `Gamepad.isPressed`, which the engine gives no edge-detected alternative for — see
 * EnPustTil's companion doc). This class tracks the previous frame's level itself and only
 * treats a false-to-true transition as "pressed" — a button held down across many frames
 * fires exactly once, not once per frame. This is deliberate and load-bearing: a stuck or
 * noisy button on the booth's USB encoder must not be able to auto-restart the game forever
 * (see the incident this class was written to fix).
 */
class RunLifecycle(
    private val dwellSeconds: Float = DWELL_SECONDS,
    private val idleTimeoutSeconds: Float = IDLE_TIMEOUT_SECONDS
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

    private var timeInState = 0f
    private var wasInputPressed = false

    /**
     * Advance the lifecycle by one frame.
     *
     * @param dt frame delta time in seconds, used for the dwell and idle timers.
     * @param anyInputPressed whether a restart/start-eligible button reads pressed THIS
     *   frame — a level reading, not pre-edge-detected; see class doc.
     * @param runOver the underlying sim's `runOver` flag; only consulted while PLAYING.
     * @return the state after this update.
     */
    fun update(dt: Float, anyInputPressed: Boolean, runOver: Boolean): RunLifecycleState
    {
        justStarted = false
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
                if (timeInState >= dwellSeconds && pressedEdge)
                    enter(RunLifecycleState.PLAYING, started = true)
                else if (timeInState >= idleTimeoutSeconds)
                    enter(RunLifecycleState.IDLE)
        }

        return state
    }

    private fun enter(newState: RunLifecycleState, started: Boolean = false)
    {
        state = newState
        timeInState = 0f
        justStarted = started
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
    }
}
