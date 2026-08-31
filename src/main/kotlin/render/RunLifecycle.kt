package render

import score.InitialsEntry
import score.Leaderboard

/** The presentation states a booth run cycles through. */
enum class RunLifecycleState { MAIN_MENU, IDLE, BRIEFING, PLAYING, PAUSED, RUN_OVER, ENTER_INITIALS }

/**
 * Pure state machine for the run lifecycle — no pulseengine imports, so it is fully
 * unit-testable without booting the engine. `EnPustTil` owns the single instance of this
 * class and is the only place that reacts to its state (drives whether [dive.DiveSim]
 * gets ticked, which HUD screen is drawn, and when a fresh `DiveSim` is constructed).
 * Presentation/lifecycle state must never leak into `dive.DiveSim`, which stays a pure
 * simulation of a single run.
 *
 * States:
 * - MAIN_MENU — entered on boot (2026-08-31: this used to be IDLE's job — see the amendment
 *              below). The sim is NOT ticked, for the same reason IDLE's isn't: a menu that
 *              ticked DiveSim would burn the diver's air while nobody was playing. A
 *              `menuAction` press is what actually starts a dive from here (straight to
 *              BRIEFING, or PLAYING if [BRIEFING_SECONDS] is zero-or-less — see that
 *              constant); `anyInputPressed` alone does nothing, on purpose, because
 *              EnPustTil hands this class the SAME edge-pulse for menu navigation and for
 *              "start" — see [MENU_IDLE_TIMEOUT_SECONDS] for the unattended-recovery half of
 *              this state, and the IDLE amendment below for why a press FROM attract still
 *              lands here rather than straight into a run.
 * - IDLE     — attract mode, the screensaver an abandoned MAIN_MENU falls back to (see
 *              [MENU_IDLE_TIMEOUT_SECONDS]). The sim is NOT ticked (caller's responsibility —
 *              see [RunLifecycleState.IDLE] usage in EnPustTil).
 *              AMENDMENT (2026-08-31, MAIN_MENU added): a fresh input press used to leave
 *              IDLE straight into a brand new run (BRIEFING, or PLAYING if briefing-less).
 *              It now returns to MAIN_MENU instead — attract is a second way to REACH the
 *              menu, not a second way to start a dive, and this is the one existing
 *              behaviour this task deliberately changes. See that branch's own comment.
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
 *              AMENDMENT (2026-08-31): the two halves of this state now show DIFFERENT
 *              screens and leave by different routes. From IDLE it is the technician's
 *              cabinet menu, unchanged: `drawPauseScreen`, closed by a second pause edge,
 *              exit by hold. From PLAYING it is the MAIN MENU with its first row reading
 *              CONTINUE ([runHeld]) — `EnPustTil` drives `MenuModel` from here exactly as
 *              it does in MAIN_MENU — and it is left by [resumeRun], [viewLeaderboard] or
 *              the menu's QUIT row rather than by a pause edge. See the pause-edge branch
 *              in [update] for why that edge had to stop resuming a held run.
 * - RUN_OVER — the "RUN OVER" screen. For the first [dwellSeconds] no input can restart —
 *              this is what guarantees the final score is actually readable even if a
 *              button is being held or mashed the instant the clock hits zero. After the
 *              dwell: if the run's score is worth recording (see [Leaderboard
 *              .isWorthRecording]), this state moves ON ITS OWN, with no input required,
 *              into ENTER_INITIALS — a qualifying run always gets a chance at the board.
 *              Otherwise a fresh press restarts immediately, or — with no input at all —
 *              the state falls back once [idleTimeoutSeconds] have elapsed since RUN_OVER
 *              was entered (so that value already includes the dwell window, not on top of
 *              it). AMENDMENT (2026-08-31): that fallback now lands on MAIN_MENU rather than
 *              IDLE — a player who just finished a run gets the menu (where "play again" is
 *              one press away), not the screensaver. [idleTimeoutSeconds] itself is
 *              unchanged; only its destination moved. See [returnToMenu].
 * - ENTER_INITIALS — three-letter arcade-initials entry (see [InitialsEntry]), reached
 *              only for a run worth recording. Completes either when the player confirms
 *              the third letter, or — if nobody touches the controls for
 *              [initialsIdleTimeoutSeconds] — by auto-submitting whatever letters were
 *              set (default "AAA" if nothing was changed). AUTO-SUBMIT rather than
 *              discarding is a deliberate choice: a qualifying score that already exists
 *              is worth more to the leaderboard/prize draw than a "clean" abandonment,
 *              and — just as importantly — the cabinet MUST recover on its own so a
 *              walked-away player can never block the next person in the queue from
 *              playing. AMENDMENT (2026-08-31): recovery now lands on MAIN_MENU rather than
 *              IDLE, same reasoning and same [returnToMenu] as RUN_OVER's fallback above.
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
    private val exitHoldSeconds: Float = EXIT_HOLD_SECONDS,
    private val briefingSeconds: Float = BRIEFING_SECONDS,
    private val briefingDwellSeconds: Float = BRIEFING_DWELL_SECONDS
)
{
    var state: RunLifecycleState = RunLifecycleState.MAIN_MENU
        private set

    /**
     * True for exactly the [update] call that transitions into PLAYING — the caller's
     * signal to construct a brand new `DiveSim` this frame. False every other call.
     */
    var justStarted: Boolean = false
        private set

    /**
     * True for exactly the [update] call that transitions into BRIEFING.
     *
     * AMENDMENT (2026-08-31): that transition used to be IDLE -> BRIEFING; it is now
     * MAIN_MENU -> BRIEFING (`menuAction`), since IDLE's own press no longer starts anything
     * — see the class doc's IDLE amendment. The flag's contract is unaffected: it still
     * fires on exactly the update that enters BRIEFING, whichever state that update started
     * from.
     *
     * EnPustTil latches `lifecycleEdges.firedPadId` on this, and that latch is load-bearing.
     * `firedPadId` is reset to null at the top of EVERY frame, and the briefing's countdown
     * fires [justStarted] on a frame with NO press — so without this flag `activePadId` would
     * be null on the common path, `selectGameplayPad` would fall through to slot 0, and the
     * cabinet would reproduce the exact failure `GamepadScan`'s doc records: a stray HID in
     * slot 0 means "the cabinet's own START press worked and the diver did not move. A
     * startable, unplayable run, silently repeating for every person in the queue."
     */
    var justEnteredBriefing: Boolean = false
        private set

    /**
     * True for exactly one tick on the arrival that follows a finished run - the RUN_OVER
     * timeout and the end of initials entry alike, but NOT a resume out of PAUSED, which
     * returns to a screen that was never left.
     *
     * EnPustTil rebuilds [dive.DiveSim] on this. Without it the screen behind a finished run
     * kept the previous run's final frame: `sim` was reconstructed only on [justStarted], and
     * `simulationAdvances` is false wherever this flag fires, so the diver simply stopped
     * wherever the clock caught him. After a 120 m timeout that left the queue-forming
     * display a near-black abyss with a leaderboard floating in it, until the next person
     * pressed start.
     *
     * AMENDMENT (2026-08-31): both of the paths this flag documents (RUN_OVER's fallback,
     * ENTER_INITIALS' completion/auto-submit) now land on MAIN_MENU rather than IDLE - see
     * the class doc. This flag's own name still says "Idle" because IDLE is where it was
     * born and renaming it is out of this task's scope, but its CONTRACT was always "a run
     * just ended, rebuild the sim" and that contract does not depend on which resting screen
     * the run lands on. [enter] deliberately does NOT grow a blanket
     * `newState == IDLE || newState == MAIN_MENU` condition to cover this: that would also
     * fire on IDLE's own ordinary press into MAIN_MENU (opening the menu from attract is not
     * "a run just ended"). Instead [returnToMenu] sets this flag explicitly at the two call
     * sites that really mean it - see that function.
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
     * Whether a RUN is currently being held behind the menu — PAUSED, entered from PLAYING
     * rather than from attract. The exact complement of [pausedFromIdle] within PAUSED, and
     * false in every other state.
     *
     * WHAT THIS IS FOR (2026-08-31). The pause screen a PLAYER sees is now the main menu
     * itself, with its first row reading CONTINUE instead of START DIVE: `EnPustTil` gates
     * `updateMainMenu`/`applyMenuAction`/`drawMainMenu` on `MAIN_MENU || runHeld`, so one
     * navigable screen serves both. The technician's cabinet menu ([pausedFromIdle]) keeps
     * `drawPauseScreen` and its exit-hold bar unchanged.
     *
     * Deliberately derived rather than stored, for the same reason [pausedFromIdle] is: there
     * is exactly one piece of state behind both ([resumeState]), and a second boolean tracking
     * the same fact is a second thing to forget to clear on a transition.
     *
     * Exposed as its own property rather than leaving the call site to write
     * `state == PAUSED && !pausedFromIdle`: that expression is a rule about which screen is on
     * show, and it belongs next to the states it talks about — the same argument
     * [simulationAdvances] and [spriteAnimates] are properties for.
     */
    val runHeld: Boolean get() = state == RunLifecycleState.PAUSED && !pausedFromIdle

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
     *
     * MAIN_MENU (2026-08-31) joins IDLE, BRIEFING and PAUSED at `false`, for the exact
     * reason IDLE is false: a menu that ticked `DiveSim` would burn the diver's air while
     * nobody was playing. It is grouped with IDLE and BRIEFING rather than with PLAYING
     * because, like them, nothing the player does on this screen is a dive in progress.
     */
    val simulationAdvances: Boolean get() = when (state)
    {
        RunLifecycleState.MAIN_MENU, RunLifecycleState.IDLE, RunLifecycleState.BRIEFING, RunLifecycleState.PAUSED -> false
        RunLifecycleState.PLAYING, RunLifecycleState.RUN_OVER, RunLifecycleState.ENTER_INITIALS -> true
    }

    /**
     * Whether [render.DiverSprite]'s animation loop should advance this frame — NOT the same
     * condition as [simulationAdvances]. `DiveSim` must stay frozen in IDLE (an attract-mode
     * diver that ran the simulation would burn air and "drown" on an unattended cabinet), but
     * the sprite should still kick in place at the surface rather than hold frame 0 — a
     * motionless diver is indistinguishable from a frozen one, and `AttractLayout` was
     * measured against a surface shot with a diver that reads as alive. So this is
     * [simulationAdvances] with IDLE and BRIEFING added back in, PAUSED excluded (a stopped
     * clock and a still diver is what the pause screen's "the run is being held, not ended"
     * promises). BRIEFING joins IDLE for the same reason — a still diver behind the
     * explanation screen reads as a crashed game, not a waiting one.
     *
     * Exposed here rather than spelled out at the call site as `simulationAdvances ||
     * state == IDLE` for the same reason `simulationAdvances` itself is a property and not a
     * comparison: that inline form is an exhaustive rule hiding in an `||`, and a sixth state
     * added later would silently inherit "sprite frozen" instead of failing to compile. An
     * exhaustive `when` on purpose, with no `else`.
     *
     * MAIN_MENU (2026-08-31) joins IDLE and the rest at `true`: a motionless diver behind
     * the menu reads as a crashed game exactly as it would behind attract or the briefing,
     * and the menu is the FIRST screen a booth or a desktop build shows — a frozen diver
     * there is the worst possible first impression.
     */
    val spriteAnimates: Boolean get() = when (state)
    {
        RunLifecycleState.PAUSED -> false
        RunLifecycleState.MAIN_MENU, RunLifecycleState.IDLE, RunLifecycleState.BRIEFING, RunLifecycleState.PLAYING,
        RunLifecycleState.RUN_OVER, RunLifecycleState.ENTER_INITIALS -> true
    }

    /**
     * How far through the exit hold we are, 0..1 — the fill fraction of the progress bar on
     * the pause screen. Feedback is not decoration here: without it a hold-to-confirm reads
     * as a dead key, and the technician lets go and tries something else.
     */
    val exitHoldProgress: Float get() = (exitHeldSeconds / exitHoldSeconds).coerceIn(0f, 1f)

    /**
     * Seconds left before the briefing starts the dive on its own; 0 outside BRIEFING.
     *
     * Guarded on the state because [timeInState] is shared with the RUN_OVER, PAUSED and
     * ENTER_INITIALS dwells — an unguarded form would report a live countdown from the
     * attract screen.
     */
    val briefingCountdownSeconds: Float
        get() = if (state == RunLifecycleState.BRIEFING) (briefingSeconds - timeInState).coerceAtLeast(0f) else 0f

    /**
     * Whether a press would now skip the briefing. The skip hint's visibility is gated on
     * this, so the hint appears at the instant pressing starts working — the affordance and
     * the capability arrive together, and the screen never invites a press that does nothing.
     */
    val briefingSkippable: Boolean
        get() = state == RunLifecycleState.BRIEFING && timeInState >= briefingDwellSeconds

    /**
     * Whether the briefing has a countdown at all. False under `EPT_BRIEFING_HOLD`, which
     * passes an infinite [briefingSeconds] so the screen can be photographed.
     * `drawBriefingScreen` suppresses the countdown line on this: `Float.POSITIVE_INFINITY`
     * converts to `Int.MAX_VALUE`, so the pinned screen would otherwise read
     * "STARTING IN 2147483647" — a defect in the one screen the flag exists to capture.
     */
    val briefingAutoStarts: Boolean get() = briefingSeconds.isFinite()

    private val initialsEntry = InitialsEntry()

    /** Current in-progress initials, e.g. "AAA" — for rendering the entry screen. */
    val currentInitials: String get() = initialsEntry.initialsString()

    /** Which of the three slots (0..2) is currently being edited. */
    val currentInitialsSlot: Int get() = initialsEntry.slot

    private var timeInState = 0f
    private var wasInputPressed = false
    private var wasPausePressed = false
    private var wasMenuActionPressed = false

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
     *   open the pause screen) and in PAUSED — where, since 2026-08-31, it leaves only the
     *   technician's cabinet menu; a HELD RUN leaves through [resumeRun]. See that branch.
     * @param exitHeld whether the exit input reads pressed this frame. The ONLY lifecycle
     *   input read as a level and not edge-detected, deliberately: what it measures is
     *   duration, and duration is what makes the exit safe (see [EXIT_HOLD_SECONDS]).
     *   Consulted only in PAUSED.
     * @param menuAction whether the menu's own "confirm/select this item" input reads
     *   pressed THIS frame — a level reading, edge-detected here exactly like
     *   [anyInputPressed]. Consulted only in MAIN_MENU, and deliberately separate from
     *   [anyInputPressed]: MAIN_MENU is where a caller distinguishes "the player is merely
     *   moving the menu cursor" from "the player picked something," and collapsing the two
     *   into one boolean would make every navigation press start a dive. Defaulted to
     *   `false` so every pre-existing caller and test compiles unchanged — none of them
     *   know MAIN_MENU exists yet.
     * @param menuInputActive whether ANY of the menu's six raw input levels (up/down/
     *   left/right/confirm/back — see `EnPustTil.updateMainMenu`) read true THIS frame,
     *   regardless of whether it produced an edge or a [MenuAction]. Consulted only in
     *   MAIN_MENU and — since 2026-08-31, where the same menu is what a held run sits
     *   behind ([runHeld]) — in PAUSED, and deliberately a LEVEL rather than an edge:
     *   [MENU_IDLE_TIMEOUT_SECONDS] (and, in PAUSED, [PAUSE_IDLE_TIMEOUT_SECONDS])
     *   is an INACTIVITY timeout (Finding I4, final review) — a player holding a direction,
     *   or repeatedly nudging FRAME CAP, must keep resetting the clock for exactly as long as
     *   they keep doing it, not just on the frame a hold began. Defaulted to `false` so every
     *   pre-existing caller and test compiles unchanged.
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
        exitHeld: Boolean = false,
        menuAction: Boolean = false,
        menuInputActive: Boolean = false
    ): RunLifecycleState
    {
        justStarted = false
        justEnteredBriefing = false
        justReturnedToIdle = false
        initialsJustCompleted = false
        exitRequested = false
        timeInState += dt

        val pressedEdge = anyInputPressed && !wasInputPressed
        wasInputPressed = anyInputPressed

        val pauseEdge = pausePressed && !wasPausePressed
        wasPausePressed = pausePressed

        val menuActionEdge = menuAction && !wasMenuActionPressed
        wasMenuActionPressed = menuAction

        when (state)
        {
            RunLifecycleState.MAIN_MENU ->
                // The menu's own screen — see the class doc's MAIN_MENU entry. menuAction is
                // deliberately the ONLY thing that can start a dive from here; anyInputPressed
                // is left completely unconsulted in this branch (contrast IDLE below, which
                // still reads pressedEdge) so a stray navigation press can never be mistaken
                // for "start."
                if (menuActionEdge)
                {
                    // A zero-or-less briefing is NO briefing - straight to PLAYING, exactly
                    // as this did before BRIEFING existed, and exactly as IDLE's press used
                    // to do before this task moved "start a dive" here. See BRIEFING_SECONDS.
                    if (briefingSeconds > 0f) enterBriefing()
                    else enter(RunLifecycleState.PLAYING, started = true)
                }
                else
                {
                    // Finding I4 (final review, 2026-08-30): this used to be a state-ENTRY
                    // timer — timeInState is only ever zeroed by enter(), so nothing here
                    // reset it, and the spec's own trigger ("no input for
                    // MENU_IDLE_TIMEOUT_SECONDS") was not what was implemented. The KDoc on
                    // MENU_IDLE_TIMEOUT_SECONDS justifies 45s with "would yank the screen away
                    // from someone halfway through choosing a resolution" — which is exactly
                    // what the old code did the instant 45s had passed since ENTRY, resolution
                    // choosing or not. RULING: the spec wins. menuInputActive resets the clock
                    // on ANY held or repeated menu input (not just a fresh edge — see that
                    // parameter's own doc), so the timeout now measures genuine inactivity.
                    if (menuInputActive) timeInState = 0f
                    if (timeInState >= MENU_IDLE_TIMEOUT_SECONDS)
                    {
                        // Unattended-recovery fallback, same shape as every other state's: a
                        // menu nobody is touching must not hold the screen forever. Longer
                        // than IDLE_TIMEOUT_SECONDS on purpose - see MENU_IDLE_TIMEOUT_SECONDS'
                        // own doc.
                        enter(RunLifecycleState.IDLE)
                    }
                }

            RunLifecycleState.IDLE ->
                // Esc from attract opens the same screen a paused run gets, so a technician
                // has one way to close the cabinet down and does not have to remember a
                // keyboard shortcut nobody wrote down. Checked AFTER the start press so a
                // player and a technician acting in the same frame gives the player the run.
                //
                // AMENDMENT (2026-08-31): a press here used to go straight into BRIEFING/
                // PLAYING (with the zero-briefing shortcut inline). It now returns to
                // MAIN_MENU instead, unconditionally - "start a dive" is MAIN_MENU's decision
                // to make (see that branch above), and IDLE's only job is to be the
                // screensaver a walked-away MAIN_MENU falls back to. This is the one existing
                // behaviour this task deliberately changes.
                if (pressedEdge) enter(RunLifecycleState.MAIN_MENU)
                else if (pauseEdge) enterPause(RunLifecycleState.IDLE)

            RunLifecycleState.BRIEFING ->
                // The dwell first: the press that opened this screen must not also close it.
                // pauseEdge is deliberately NOT handled, exactly as it is not in RUN_OVER or
                // ENTER_INITIALS - a technician waits at most one countdown.
                if (timeInState >= briefingDwellSeconds && pressedEdge)
                    enter(RunLifecycleState.PLAYING, started = true)
                else if (timeInState >= briefingSeconds)
                    enter(RunLifecycleState.PLAYING, started = true)

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

                // A HELD RUN'S PAUSE SCREEN IS THE MAIN MENU (2026-08-31, see [runHeld]), so
                // the auto-resume clock has to measure INACTIVITY here for the identical
                // reason MAIN_MENU's own timeout does (Finding I4): a player halfway through
                // choosing a render scale must not have their run resumed underneath them at
                // [PAUSE_IDLE_TIMEOUT_SECONDS]. Harmless for the technician's cabinet menu,
                // which still draws `drawPauseScreen` and has no menu input to report —
                // `EnPustTil` only ever passes this parameter true while a navigable menu is
                // actually on screen.
                if (menuInputActive) timeInState = 0f

                if (exitHeld && !exitAlreadyRequested && exitHeldSeconds >= exitHoldSeconds)
                {
                    // Stay in PAUSED. The application is closing; everything should remain
                    // exactly as frozen as it already is until the window actually goes.
                    exitRequested = true
                    exitAlreadyRequested = true
                }
                // A PAUSE EDGE NOW CLOSES ONLY THE TECHNICIAN'S CABINET MENU. It used to
                // close both, and that stopped being safe the moment a held run started
                // showing the main menu ([runHeld]): the SHIPPED DEFAULT puts `pauseButtonAlt`
                // and `restartButton` on the same physical button (Options — deliberately, see
                // `EnPustTil.gamepadButtonCollisionWarnings`' own doc), so one Options press on
                // the pause menu raises the menu's CONFIRM and this pause edge in the SAME
                // frame. Resuming here would then fire on every confirm the player made:
                // picking GRAPHICS would drop them back into the water instead of opening the
                // page, and picking QUIT would resume a run on its way out.
                //
                // A held run leaves through [resumeRun] instead, which `EnPustTil` calls for
                // the CONTINUE row and for BACK on the menu's root page. Nothing is lost from
                // the keyboard, where the two are separate keys anyway: Esc arrives at the
                // open menu as `MenuModel`'s own `back`, root-page back returns
                // `MenuAction.CloseMenu`, and that calls [resumeRun] — so Esc-then-Esc still
                // gives a player their run back, exactly as [EXIT_HOLD_SECONDS]' doc promises.
                //
                // `pausePressed` is still fed to this class unsuppressed every frame, and that
                // matters: `wasPausePressed` keeps tracking the real level, so a pause button
                // still held at the instant the run resumes reads as STILL HELD in PLAYING
                // rather than as a fresh press, and cannot immediately re-pause the run it just
                // resumed.
                else if ((pauseEdge && pausedFromIdle) || timeInState >= pauseIdleTimeoutSeconds)
                {
                    // Both resumes are the same transition on purpose — see
                    // PAUSE_IDLE_TIMEOUT_SECONDS for why the timeout resumes rather than
                    // abandoning. `started` stays false, so the caller does NOT build a
                    // fresh DiveSim: the run is picked up exactly where it was left.
                    enter(resumeState, resuming = true)
                }
            }

            RunLifecycleState.RUN_OVER ->
                // AMENDMENT (2026-08-31): both idleTimeoutSeconds fallbacks below used to
                // land on IDLE; they now land on MAIN_MENU (returnToMenu()), so a player who
                // just finished a run gets the menu - "play again" one press away - rather
                // than the attract screen. idleTimeoutSeconds itself is untouched; only the
                // destination moved. The retry branch (pressedEdge, mid-list) is UNCHANGED -
                // a retry still goes straight back to PLAYING, never through MAIN_MENU or
                // BRIEFING (see `a retry from RUN_OVER does not re-brief`).
                if (timeInState >= dwellSeconds)
                {
                    if (Leaderboard.isWorthRecording(bankedScore))
                        enter(RunLifecycleState.ENTER_INITIALS)
                    else if (pressedEdge)
                        enter(RunLifecycleState.PLAYING, started = true)
                    else if (timeInState >= idleTimeoutSeconds)
                        returnToMenu()
                }
                else if (timeInState >= idleTimeoutSeconds)
                    returnToMenu()

            RunLifecycleState.ENTER_INITIALS ->
            {
                initialsEntry.update(cycleUp, cycleDown, confirmPressed)
                if (initialsEntry.complete || timeInState >= initialsIdleTimeoutSeconds)
                    finishInitials()
            }
        }

        return state
    }

    /**
     * The main menu's LEADERBOARD row — leaves MAIN_MENU for IDLE on demand, rather than
     * waiting out [MENU_IDLE_TIMEOUT_SECONDS]. This is deliberately a public method and not a
     * `MenuAction` case [update] itself interprets: `MenuModel` (in `render`, engine-free) has
     * no notion of `RunLifecycleState`, and `EnPustTil` is what already owns both objects and
     * translates one's output into calls on the other (see `EnPustTil.updateMainMenu`'s
     * `when` over `MenuAction`).
     *
     * Reuses [enter]'s existing `newState == IDLE` branch rather than duplicating it — that
     * branch already sets [justReturnedToIdle] (see [enter]'s own comment on why the condition
     * stays a plain `== IDLE` check), which is EXACTLY the signal `EnPustTil` already acts on
     * to rebuild `DiveSim`/snap the camera/restart the diver's loop for a fresh attract screen.
     * That is the same transition MAIN_MENU's own idle-timeout already takes on a clock; this
     * is the identical transition taken by player choice instead.
     *
     * A no-op from any state but MAIN_MENU — nothing in the design calls this from anywhere
     * else, and a defensive no-op is safer here than an assertion on a path a menu-only caller
     * should never be able to reach incorrectly in the first place.
     */
    fun viewLeaderboard()
    {
        if (state == RunLifecycleState.MAIN_MENU || runHeld) enter(RunLifecycleState.IDLE)
    }

    /**
     * Hand a held run back to the player — the pause menu's CONTINUE row, and BACK/Esc on
     * that menu's root page (`MenuAction.CloseMenu`). Both routes are `EnPustTil`'s to call,
     * for the same reason [viewLeaderboard] is: `MenuModel` is engine-free and has no notion
     * of a `RunLifecycleState`, and `EnPustTil` is what already owns both objects and
     * translates one's output into calls on the other.
     *
     * The transition is byte-identical to the one the auto-resume timeout takes —
     * `enter(resumeState, resuming = true)` — so [justStarted] stays false and the caller does
     * NOT build a fresh `DiveSim`. A resume that restarted the run instead is the single worst
     * thing this screen could do to a player, which is why `resuming returns to the run rather
     * than restarting it` asserts it from here.
     *
     * A no-op unless a run is actually held ([runHeld]). The technician's cabinet menu still
     * closes on its own pause edge in [update] and must not be reachable from here, and a
     * defensive no-op is safer on a menu-only path than an assertion — same ruling as
     * [viewLeaderboard]'s.
     */
    fun resumeRun()
    {
        if (runHeld) enter(resumeState, resuming = true)
    }

    private fun finishInitials()
    {
        completedInitials = initialsEntry.initialsString()
        initialsJustCompleted = true
        returnToMenu()
    }

    /**
     * The shared "a run just ended, and nobody is holding a paused one open" landing spot —
     * called from RUN_OVER's unattended fallback and from [finishInitials]. AMENDMENT
     * (2026-08-31): both used to call `enter(IDLE)` directly; they now land on MAIN_MENU, but
     * still need [justReturnedToIdle] to fire, because that flag's actual contract (EnPustTil
     * rebuilds `DiveSim` so the next screen never shows the finished run's last frame) does
     * not care which resting screen the run lands on. [enter]'s own condition for the flag
     * stays `newState == IDLE` — broadening it to also cover MAIN_MENU would make it ALSO
     * fire on IDLE's ordinary press into MAIN_MENU (opening the menu from attract is not "a
     * run just ended," see that branch above) — so this function sets the flag explicitly,
     * after calling [enter], instead. See [justReturnedToIdle]'s own doc for the full
     * reasoning.
     */
    private fun returnToMenu()
    {
        enter(RunLifecycleState.MAIN_MENU)
        justReturnedToIdle = true
    }

    private fun enterPause(returnTo: RunLifecycleState)
    {
        resumeState = returnTo
        enter(RunLifecycleState.PAUSED)
    }

    private fun enterBriefing()
    {
        enter(RunLifecycleState.BRIEFING)
        justEnteredBriefing = true
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
        //
        // Deliberately still just `== IDLE`, not `== IDLE || == MAIN_MENU`, even though
        // MAIN_MENU is now also a valid landing spot for a finished run - see [returnToMenu],
        // which sets the flag explicitly for exactly those two call sites instead of widening
        // this condition (which would incorrectly also fire on IDLE's ordinary press into
        // MAIN_MENU).
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
         * How long the pre-run briefing stays up before the dive starts on its own.
         *
         * This countdown IS the unattended-recovery guarantee for the state: a player who
         * walks away mid-briefing does not strand the cabinet, because the run starts,
         * drowns, and falls through RUN_OVER -> MAIN_MENU (2026-08-31: was IDLE — see
         * [returnToMenu]) on the timers above. That is why BRIEFING needs no idle timeout of
         * its own.
         *
         * ZERO OR LESS MEANS NO BRIEFING AT ALL — MAIN_MENU's `menuAction` goes straight to
         * PLAYING (2026-08-31: this used to be IDLE's press; see the class doc's IDLE
         * amendment — the shortcut moved with it). That is the natural reading of the
         * parameter, it gives a one-constant way to switch the briefing off if it proves too
         * slow in front of a real queue, and it is what lets every test written before
         * BRIEFING keep asserting what it always asserted.
         */
        const val BRIEFING_SECONDS = 5f

        /**
         * How long the briefing ignores input before a press can skip it.
         *
         * The press that OPENS the briefing must not also close it. Edge detection already
         * forces a release-then-press, but a double-tap is ordinary on an arcade button and
         * would blow straight past the text. This is the same guard RUN_OVER uses via
         * [DWELL_SECONDS], at a fifth of the duration — long enough to swallow a double-tap,
         * short enough that a returning player who knows the game is not held up.
         */
        const val BRIEFING_DWELL_SECONDS = 0.75f

        /**
         * Total time (from entering RUN_OVER, dwell included) before falling back with no
         * input at all — 2.5s dwell + 15s of true idle. The VALUE is untouched by this task;
         * only its destination moved from IDLE to MAIN_MENU (2026-08-31 — see
         * [returnToMenu]).
         */
        const val IDLE_TIMEOUT_SECONDS = 17.5f

        /**
         * How long the main menu waits with NO INPUT AT ALL before falling back to the
         * attract screen — an inactivity timeout, not a state-entry one (Finding I4, final
         * review, 2026-08-30: `update`'s MAIN_MENU branch now resets `timeInState` on
         * [update]'s `menuInputActive` parameter every frame it reads true, so only genuine
         * idleness accumulates toward this value).
         *
         * Longer than [IDLE_TIMEOUT_SECONDS] on purpose: attract mode is a screensaver whose
         * job is to recover an abandoned cabinet, while the menu is somewhere a person is
         * actively reading and deciding. Timing out of it as briskly as attract times out
         * would yank the screen away from someone halfway through choosing a resolution — a
         * risk that is now actually guarded against, rather than merely asserted in this
         * comment while the code measured time-since-entry regardless of activity.
         */
        const val MENU_IDLE_TIMEOUT_SECONDS = 45f

        /**
         * Time in ENTER_INITIALS, with no input at all, before auto-submitting whatever
         * letters were set and returning to the menu (2026-08-31: was IDLE — see
         * [returnToMenu]) — see the ENTER_INITIALS state doc for why this auto-submits
         * rather than discarding.
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
