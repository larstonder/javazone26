package render

import score.InitialsEntry
import score.Leaderboard

/** The presentation states a booth run cycles through. */
enum class RunLifecycleState { IDLE, PLAYING, PAUSED, RUN_OVER, ENTER_INITIALS }

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
 *              `runOver`. Start/restart input is otherwise ignored here — kicking/bleeding
 *              never restarts anything. The one thing that DOES act here is `pausePressed`
 *              (Esc), checked only after `runOver`, so a run that has just ended goes to
 *              its RUN_OVER screen rather than becoming pausable in its final frame.
 * - PAUSED   — the deliberate way out, reachable from IDLE and from PLAYING only, and
 *              returning to whichever of those it came from ([resumeState]). See
 *              [simulationAdvances] for the part that makes a mid-run pause airtight
 *              rather than merely visual, and the companion constants for the two
 *              judgement calls (auto-dismiss, exit-by-hold) this state encodes.
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
 * EDGE-TRIGGERING: this class — and the [InitialsEntry] it owns — track the previous
 * frame themselves and only treat a false-to-true transition as "pressed": a button held
 * down across many frames fires exactly once, not once per frame. This is deliberate and
 * load-bearing: a stuck or noisy button on the booth's USB encoder must not be able to
 * auto-restart the game forever, or blast through every letter of the alphabet in one
 * frame (see the incident this class was written to fix).
 *
 * [update]'s `cycleUp`/`cycleDown`/`pausePressed`/`exitHeld` parameters are raw per-frame
 * LEVEL readings (e.g. `Gamepad.isPressed`, which the engine gives no edge-detected
 * alternative for — see EnPustTil's companion doc) and depend entirely on this class's
 * own edging above. `anyInputPressed` and `confirmPressed` are NOT levels from
 * EnPustTil's caller any more: both now carry `LifecycleInputEdges.commit()`'s result,
 * already a one-frame pulse taken per (pad, button) source — see that class's doc for why
 * a single collapsed level could be latched true for two days by one stuck button. This
 * class re-edges them regardless — a pulse re-edged is still a pulse for exactly one
 * frame — so the redundancy is harmless, and other callers remain free to pass a raw
 * level as before.
 */
class RunLifecycle(
    private val dwellSeconds: Float = DWELL_SECONDS,
    private val idleTimeoutSeconds: Float = IDLE_TIMEOUT_SECONDS,
    private val initialsIdleTimeoutSeconds: Float = INITIALS_IDLE_TIMEOUT_SECONDS,
    private val pauseIdleTimeoutSeconds: Float = PAUSE_IDLE_TIMEOUT_SECONDS,
    private val exitHoldSeconds: Float = EXIT_HOLD_SECONDS
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
     * True for exactly one tick on each arrival at [RunLifecycleState.IDLE] from a finished
     * run - the RUN_OVER timeout and the end of initials entry alike, but NOT a resume out
     * of PAUSED, which returns to an attract screen that was never left.
     *
     * EnPustTil rebuilds [dive.DiveSim] on this. Without it the attract screen kept the
     * previous run's final frame: `sim` was reconstructed only on [justStarted], and
     * `simulationAdvances` is false in IDLE, so the diver simply stopped wherever the clock
     * caught him. After a 120 m timeout that left the queue-forming display a near-black
     * abyss with a leaderboard floating in it, until the next person pressed start.
     *
     * A one-tick event rather than a latched flag, for the same reason [justStarted] is.
     */
    var justReturnedToIdle: Boolean = false
        private set

    /**
     * One-tick event: true for exactly the [update] call that finishes initials entry —
     * either the player confirmed the third letter, or entry timed out and was
     * auto-submitted (see the ENTER_INITIALS state doc). The caller reads
     * [completedInitials] THIS SAME TICK and persists the score. Cleared the next tick,
     * same pattern as [justStarted] and `DiveSim.diveEnded`.
     *
     * [justReturnedToIdle] is ALSO true on this exact tick — [finishInitials] sets this flag
     * and then calls `enter(IDLE)` without `resuming` (it defaults to `false`), which sets
     * that one in the same call. The DiveSim that scored the run is
     * only "still intact" for a caller that reads it before acting on [justReturnedToIdle];
     * EnPustTil's ordering of those two blocks in `updateGame` is load-bearing for exactly
     * this reason — see the comment there and `UpdateGameOrderingTest`.
     */
    var initialsJustCompleted: Boolean = false
        private set

    /** Valid only the tick [initialsJustCompleted] is true. */
    var completedInitials: String = ""
        private set

    /**
     * One-tick event: true for exactly the [update] call on which the exit hold completes.
     * The caller shuts the application down on it (`engine.window.close()` in EnPustTil —
     * the same call the engine's own `exit` console command makes, verified by
     * disassembling `CommandRegistry.registerEngineCommands`; it ends the game loop, which
     * then runs `onDestroy` on the game and on every registered Service, so
     * [score.ScoreRepository]'s final synchronous save still happens).
     *
     * A one-tick event rather than a latched flag, for the same reason [justStarted] is:
     * the state machine's job is to say "this happened now", not to hold a mode.
     */
    var exitRequested: Boolean = false
        private set

    /**
     * Which state a resume returns to. PAUSED is entered from IDLE (a technician opening the
     * cabinet menu from attract mode) and from PLAYING (a player pausing a run), and it has
     * to go back to exactly the one it came from: back to PLAYING resumes the run in place,
     * back to IDLE restores the attract screen. Anything else would either resurrect a run
     * that was never started or throw away one that was.
     */
    private var resumeState = RunLifecycleState.IDLE

    /**
     * Whether the pause screen currently on show was opened from attract mode rather than
     * from a run — the only thing the renderer needs to know to word it correctly ("cabinet
     * menu" vs "paused"). Exposed as a boolean rather than as [resumeState] itself so the
     * draw site cannot start branching on lifecycle states it has no business knowing about.
     */
    val pausedFromIdle: Boolean get() = resumeState == RunLifecycleState.IDLE

    /**
     * Whether the caller MUST advance [dive.DiveSim] this frame. EnPustTil's `onFixedUpdate`
     * is gated on exactly this and nothing else, which is what makes a pause airtight
     * instead of cosmetic: `DiveSim.tick` is the single place the clock counts down and air
     * burns (both are `private set` on the sim and are written nowhere else), and it has
     * exactly one call site in the whole game. Skipping that call is therefore the entire
     * pause — there is no second path by which a paused run can lose a tenth of a second or
     * a breath of air, and no way to "think for free" about a dive you are losing.
     *
     * It also cannot accrue a time debt that gets paid out in one lurch on resume: the
     * skipped ticks are dropped, not banked. The engine hands `onFixedUpdate` a CONSTANT
     * `fixedDeltaTime`, so the tick that runs immediately after a resume advances the sim by
     * one frame exactly like every other tick, no matter how long the pause lasted.
     *
     * An exhaustive `when` on purpose, with no `else`: adding a sixth state must be a
     * compile error here, not a silent default that either freezes the game or lets a run
     * tick away behind a screen the player cannot see past. RUN_OVER and ENTER_INITIALS keep
     * ticking exactly as they did before this state existed — `DiveSim.tick` already no-ops
     * once `runOver` is set, so those two are true here to preserve the previous behaviour
     * verbatim rather than because anything still moves.
     */
    val simulationAdvances: Boolean get() = when (state)
    {
        RunLifecycleState.IDLE, RunLifecycleState.PAUSED -> false
        RunLifecycleState.PLAYING, RunLifecycleState.RUN_OVER, RunLifecycleState.ENTER_INITIALS -> true
    }

    /**
     * Whether [render.DiverSprite]'s animation loop should advance this frame — NOT the same
     * condition as [simulationAdvances]. `DiveSim` must stay frozen in IDLE (an attract-mode
     * diver that ran the simulation would burn air and "drown" on an unattended cabinet), but
     * the sprite should still kick in place at the surface rather than hold frame 0 — a
     * motionless diver is indistinguishable from a frozen one, and `AttractLayout` was
     * measured against a surface shot with a diver that reads as alive. So this is
     * [simulationAdvances] with IDLE added back in, PAUSED excluded (a stopped clock and a
     * still diver is what the pause screen's "the run is being held, not ended" promises).
     *
     * Exposed here rather than spelled out at the call site as `simulationAdvances ||
     * state == IDLE` for the same reason `simulationAdvances` itself is a property and not a
     * comparison: that inline form is an exhaustive rule hiding in an `||`, and a sixth state
     * added later would silently inherit "sprite frozen" instead of failing to compile. An
     * exhaustive `when` on purpose, with no `else`.
     */
    val spriteAnimates: Boolean get() = when (state)
    {
        RunLifecycleState.PAUSED -> false
        RunLifecycleState.IDLE, RunLifecycleState.PLAYING, RunLifecycleState.RUN_OVER, RunLifecycleState.ENTER_INITIALS -> true
    }

    /**
     * How far through the exit hold we are, 0..1 — the fill fraction of the progress bar on
     * the pause screen. Feedback is not decoration here: without it a hold-to-confirm reads
     * as a dead key, and the technician lets go and tries something else.
     */
    val exitHoldProgress: Float get() = (exitHeldSeconds / exitHoldSeconds).coerceIn(0f, 1f)

    private val initialsEntry = InitialsEntry()

    /** Current in-progress initials, e.g. "AAA" — for rendering the entry screen. */
    val currentInitials: String get() = initialsEntry.initialsString()

    /** Which of the three slots (0..2) is currently being edited. */
    val currentInitialsSlot: Int get() = initialsEntry.slot

    private var timeInState = 0f
    private var wasInputPressed = false
    private var wasPausePressed = false

    /**
     * How long the exit input has been held CONTINUOUSLY while PAUSED. Reset to zero the
     * moment it is released, and by [enter] on every state change, so a hold is only ever
     * satisfied by one unbroken press — mashing the key can never accumulate its way there.
     */
    private var exitHeldSeconds = 0f

    /** Latch so the one-tick [exitRequested] fires once per pause, not once per frame after. */
    private var exitAlreadyRequested = false

    /**
     * Advance the lifecycle by one frame.
     *
     * @param dt frame delta time in seconds, used for the dwell and idle timers.
     * @param anyInputPressed whether a restart/start-eligible button reads pressed THIS
     *   frame. EnPustTil now hands this an already-edged one-frame pulse
     *   (`LifecycleInputEdges.commit()`); this class re-edges it regardless, so both a raw
     *   level and a pre-edged pulse are safe here — see class doc.
     * @param runOver the underlying sim's `runOver` flag; only consulted while PLAYING.
     * @param bankedScore the sim's banked total; only consulted at/after a RUN_OVER
     *   transition, to decide whether to offer initials entry. Defaults to 0 (never
     *   worth recording) so callers that don't care about initials entry — e.g. existing
     *   tests — get the pre-ENTER_INITIALS behaviour unchanged.
     * @param cycleUp / @param cycleDown initials-entry input, level readings — see
     *   [InitialsEntry.update]. @param confirmPressed is EnPustTil's SAME edge-detected
     *   pulse as [anyInputPressed] ("the button that started your run also advances your
     *   initials" — see EnPustTil's companion doc), not a separate level. Only consulted
     *   in ENTER_INITIALS.
     * @param pausePressed whether the pause/back input (Esc) reads pressed THIS frame — a
     *   level reading, edge-detected here exactly like [anyInputPressed], so a key held
     *   down toggles once and not sixty times a second. Consulted in IDLE and PLAYING (to
     *   open the pause screen) and in PAUSED (to leave it again).
     * @param exitHeld whether the exit input reads pressed this frame. The ONLY lifecycle
     *   input read as a level and not edge-detected, deliberately: what it measures is
     *   duration, and duration is what makes the exit safe (see [EXIT_HOLD_SECONDS]).
     *   Consulted only in PAUSED.
     * @return the state after this update.
     */
    fun update(
        dt: Float,
        anyInputPressed: Boolean,
        runOver: Boolean,
        bankedScore: Int = 0,
        cycleUp: Boolean = false,
        cycleDown: Boolean = false,
        confirmPressed: Boolean = false,
        pausePressed: Boolean = false,
        exitHeld: Boolean = false
    ): RunLifecycleState
    {
        justStarted = false
        justReturnedToIdle = false
        initialsJustCompleted = false
        exitRequested = false
        timeInState += dt

        val pressedEdge = anyInputPressed && !wasInputPressed
        wasInputPressed = anyInputPressed

        val pauseEdge = pausePressed && !wasPausePressed
        wasPausePressed = pausePressed

        when (state)
        {
            RunLifecycleState.IDLE ->
                // Esc from attract opens the same screen a paused run gets, so a technician
                // has one way to close the cabinet down and does not have to remember a
                // keyboard shortcut nobody wrote down. Checked AFTER the start press so a
                // player and a technician acting in the same frame gives the player the run.
                if (pressedEdge) enter(RunLifecycleState.PLAYING, started = true)
                else if (pauseEdge) enterPause(RunLifecycleState.IDLE)

            RunLifecycleState.PLAYING ->
                // runOver first: a run whose clock has just hit zero is finished, and must
                // reach its RUN_OVER screen rather than be frozen one frame short of it.
                if (runOver) enter(RunLifecycleState.RUN_OVER)
                else if (pauseEdge) enterPause(RunLifecycleState.PLAYING)

            RunLifecycleState.PAUSED ->
            {
                // Level, not edge: an unbroken hold is the whole safety mechanism, and it
                // must reset the instant the key comes up.
                exitHeldSeconds = if (exitHeld) exitHeldSeconds + dt else 0f

                if (exitHeld && !exitAlreadyRequested && exitHeldSeconds >= exitHoldSeconds)
                {
                    // Stay in PAUSED. The application is closing; everything should remain
                    // exactly as frozen as it already is until the window actually goes.
                    exitRequested = true
                    exitAlreadyRequested = true
                }
                else if (pauseEdge || timeInState >= pauseIdleTimeoutSeconds)
                {
                    // Both resumes are the same transition on purpose — see
                    // PAUSE_IDLE_TIMEOUT_SECONDS for why the timeout resumes rather than
                    // abandoning. `started` stays false, so the caller does NOT build a
                    // fresh DiveSim: the run is picked up exactly where it was left.
                    enter(resumeState, resuming = true)
                }
            }

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

    private fun enterPause(returnTo: RunLifecycleState)
    {
        resumeState = returnTo
        enter(RunLifecycleState.PAUSED)
    }

    private fun enter(newState: RunLifecycleState, started: Boolean = false, resuming: Boolean = false)
    {
        // Set here rather than at the three call sites so a fourth route back to IDLE added
        // later cannot forget it. The PAUSED resume passes `resuming = true` and is excluded
        // explicitly: that returns to an attract screen that was never left. Keyed on the
        // CALLER'S intent rather than on the old state being PAUSED — a state comparison
        // would also swallow a future "abandon run, return to attract" route added to the
        // pause screen, which goes PAUSED -> IDLE through this same funnel but IS a genuine
        // return to a fresh attract screen, not a resume.
        justReturnedToIdle = newState == RunLifecycleState.IDLE && !resuming
        state = newState
        timeInState = 0f
        justStarted = started
        // Every state change starts the exit hold over. Otherwise a key held down across a
        // pause/resume/pause cycle would carry its accumulated time with it and complete
        // the hold on a press the technician never made in the state it fired in.
        exitHeldSeconds = 0f
        exitAlreadyRequested = false
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

        /**
         * Time on the pause screen, untouched, before it dismisses itself — and it RESUMES
         * rather than exiting or abandoning.
         *
         * JUDGEMENT CALL, and the reasoning is the same one that produced the two timeouts
         * above: every other state in this machine recovers to attract mode on its own so
         * that a player who walks away cannot block the queue. A pause screen that sat there
         * forever would be the single state that does not, and it would be the worst one to
         * have that property — it holds the cabinet mid-run, with the leaderboard hidden and
         * "PRESS START" nowhere on screen, so the queue cannot even tell the machine is
         * still alive.
         *
         * Resuming is the least destructive of the three ways out. Jumping straight to IDLE
         * would silently throw away a run that might have been worth a place on the board;
         * resuming hands the run back to the (absent) player, whose air then drains, which
         * feeds the already-tested RUN_OVER -> ENTER_INITIALS -> auto-submit -> IDLE
         * recovery path — the same path, and the same "a score that exists beats a clean
         * abandonment" reasoning, as ENTER_INITIALS above. Auto-EXITING would of course be
         * absurd: a screen that shuts the cabinet down if nobody touches it for a while.
         *
         * 20s: longer than the 15s and 17.5s above, because a pause is a deliberate "hold
         * on a second" (hand the stick over, take a photo, let a colleague through) and
         * cutting it short makes the feature useless — but still short enough that the
         * cabinet is never held for more than a fifth of a minute by someone who left.
         */
        const val PAUSE_IDLE_TIMEOUT_SECONDS = 20f

        /**
         * How long the exit input must be held CONTINUOUSLY, on the pause screen, to shut
         * the application down.
         *
         * JUDGEMENT CALL: exit needs a confirmation step, but not a confirmation SCREEN.
         * A yes/no prompt is the obvious answer and the wrong one here — it makes the
         * technician navigate a menu on a cabinet with no keyboard-friendly menu convention
         * and no cursor, and it adds a fourth state that itself needs a timeout, an escape
         * and an auto-dismiss rule.
         *
         * A hold gets the same protection out of the input itself. An accidental contact —
         * a knee against the shelf, a hand steadying the cabinet, an attendee mashing keys
         * — produces taps, and taps produce nothing at all here; you have to mean it for a
         * second and a half without letting go. Meanwhile a technician does not "fight the
         * UI": they hold one key and watch a bar fill, with no decision to make and nothing
         * to read. It is also the reason Esc-then-Esc is safe, which is the specific
         * accident worth designing against: an attendee who hits Esc mid-run and hits it
         * again gets their run back, not a dead cabinet, because the second Esc is bound to
         * resume and exit is not on that key at all.
         *
         * 1.5s is the shortest hold that cannot be produced by a bounce or a bump but still
         * feels immediate; it is also long enough for the progress bar to read as a bar
         * rather than a flash.
         */
        const val EXIT_HOLD_SECONDS = 1.5f
    }
}
