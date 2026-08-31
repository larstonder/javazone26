package render

/**
 * The pages the main menu can be on. Confirming GRAPHICS or LEADERBOARD from ROOT opens that
 * page; BACK returns to ROOT. There is still no deeper nesting — every sub-page is exactly one
 * hop from ROOT, so "back" is unambiguous and no page stack is needed.
 *
 * THIS ENUM GREW A THIRD ENTRY ON 2026-08-31, AND THAT IS WHY [MenuModel.update]'S BACK BRANCH
 * IS WRITTEN `page != MenuPage.ROOT` RATHER THAN `page == MenuPage.GRAPHICS`. For as long as
 * there were exactly two pages the two spellings were the same predicate; with three they are
 * not, and the difference is a player being dumped out of a paused dive. See that branch.
 */
enum class MenuPage { ROOT, GRAPHICS, LEADERBOARD }

/**
 * Every row either page can show. Not every id appears on every page — [MenuModel.itemsOn]
 * decides that — but [MenuModel] asserts (via its own test) that every id is reachable from
 * ROOT by navigation alone, so an id declared here and forgotten on a page would fail the
 * build rather than sit as dead code that looks live.
 */
enum class MenuItemId
{
    START_DIVE, GRAPHICS, LEADERBOARD, QUIT,
    QUALITY,
    // The six live GI knobs (gi-knobs-brief.md) — grouped right after QUALITY, the preset they
    // belong to and the row that writes all six of them at once, rather than at the end of the
    // page beside the unrelated window/display rows below. Touching any one of these sets
    // GameSettings.quality to "CUSTOM"; see EnPustTil.stepGameSettings.
    LIGHT_MAP_SCALE, SCENE_SCALE, GLOBAL_SCALE, MAX_CASCADES, RAY_QUALITY, OFF_SCREEN_RAYS,
    RESOLUTION, RENDER_SCALE, FULLSCREEN, FRAME_CAP, VSYNC, SHOW_FPS,
    /** The LEADERBOARD page's only action row. It is HOLD-TO-FIRE, not confirm-to-fire — see
     * [MenuModel.update]'s hold block and [MenuModel.DELETE_HOLD_SECONDS]. */
    DELETE_BOARD,
    BACK
}

/** What confirming or nudging a row produces. [MenuModel.update] returns exactly one of these
 * per call, [None] when nothing happened this frame. */
sealed interface MenuAction
{
    object StartDive : MenuAction

    /**
     * NO LONGER EMITTED BY THIS CLASS as of 2026-08-31, and kept declared on purpose.
     *
     * `MenuItemId.LEADERBOARD` used to produce this, and `EnPustTil.applyMenuAction` routed it
     * to `RunLifecycle.viewLeaderboard()` — which enters IDLE, fires `justReturnedToIdle` and
     * rebuilds `DiveSim`, i.e. it ABANDONED a held run to show the attract screen's board. The
     * row now opens [MenuPage.LEADERBOARD] in place instead (see [MenuModel.update]'s CONFIRM
     * handling), so a paused player can read the board and go back to their dive.
     *
     * It stays here because the ROUTE it names is still live and still correct for its other
     * caller: `RunLifecycle.viewLeaderboard()` is what IDLE/attract uses, and
     * `applyMenuAction`'s branch is the one place that documents why that route abandons a run.
     * Deleting the action would delete that explanation along with it. If a future revision
     * decides the attract board and the menu page are the same screen, this is the seam.
     */
    object ShowLeaderboard : MenuAction

    object Quit : MenuAction
    object Back : MenuAction

    /**
     * The leaderboard for the current daily seed should be wiped. Emitted EXACTLY ONCE per
     * completed hold of `MenuItemId.DELETE_BOARD` — never on a confirm edge, never repeatedly
     * while the button stays down past the threshold. See [MenuModel.update]'s hold block for
     * the two separate rules that guarantee each half of that, and why both are needed.
     *
     * This class does not know what a scoreboard is; the wipe itself (including its backup) is
     * entirely the consumer's, exactly as [SettingChanged] leaves clamping to the consumer.
     */
    object DeleteBoard : MenuAction

    /**
     * BACK pressed on the ROOT page — "close this menu", if the caller has anything to close.
     *
     * ADDED 2026-08-31 with the pause menu. Root-page back used to return [None] and the rule
     * it encoded ("a player pressing B at the top level is a no-op, not an accidental exit")
     * is UNCHANGED: this class still does nothing itself, and a caller that ignores this
     * action behaves exactly as every caller did before it existed. What changed is that the
     * SAME menu is now also what a paused run sits behind (`RunLifecycle.runHeld`), and there
     * root-page back means "give me my run back" — `EnPustTil` maps it to
     * `RunLifecycle.resumeRun()`, which is itself a no-op unless a run is held.
     *
     * It has to be an action rather than a pause edge `RunLifecycle` reads for itself: the
     * shipped default puts the pad's pause and confirm on the same physical button, so a pause
     * edge cannot tell "close the menu" apart from "pick this row". Routing it through the
     * menu means only the ROOT page's own unconsumed back reaches the lifecycle — on GRAPHICS
     * the very same press is [Back], and pages the menu grows later get first refusal too.
     */
    object CloseMenu : MenuAction

    object None : MenuAction

    /** Emitted when left/right nudges a value-carrying row. [delta] is always +1 or -1 —
     * this class never knows the setting's actual range, so clamping/wrapping the value is
     * entirely the consumer's job (see `GameSettings.clamped`). */
    data class SettingChanged(val item: MenuItemId, val delta: Int) : MenuAction
}

/**
 * Pure, engine-free navigation for the main menu — no pulseengine imports, unit-testable in a
 * headless JVM exactly like `RunLifecycle`, `Framing` and `ControlHints`.
 *
 * EDGE-TRIGGERED, for the same reason as [RunLifecycle] and `score.InitialsEntry` (see
 * `RunLifecycle`'s class doc for the incident that made this non-negotiable): the engine's
 * `Gamepad` exposes only `isPressed`/`getAxis`, no `wasClicked`, so every parameter [update]
 * receives is a LEVEL that reads true on every frame the control is held. Treating a level as
 * a press would scroll the whole list in one frame and — on a noisy USB encoder with a stuck
 * contact — cycle a setting forever. This class tracks the previous frame itself (`was*`
 * fields, exactly `InitialsEntry`'s shape) and only acts on a false-to-true transition.
 *
 * Opposing directions (up+down, or left+right) are cancelled to zero BEFORE edge detection,
 * so a stuck contact holding BOTH directions of one axis AT ONCE can never produce a
 * transition and walk the menu on its own — the same rule `render.PadAxis` applies to
 * steering.
 *
 * WHAT THIS DOES NOT COVER (MINOR fix, final review, 2026-08-30 — this doc used to read as if
 * it did): a contact that CHATTERS on ONE side alone — rapidly toggling false/true/false/...
 * with the opposing direction never held — is not cancelled by the paragraph above at all,
 * because `wasUp`/`wasDown`/`wasLeft`/`wasRight` are assigned the CANCELLED level (`upLevel`
 * etc., see [update]), and a chatter with nothing on the opposing side leaves that level
 * exactly as noisy as the raw input. Each low-to-high toggle is then a genuine transition and
 * steps the selection once per chatter cycle — a lesser, accepted risk (one row per contact
 * bounce, not the whole list in one frame the LEVEL-vs-EDGE fix above prevents), not a second
 * guarantee this class does not actually provide.
 */
class MenuModel
{
    var page: MenuPage = MenuPage.ROOT
        private set

    var selectedIndex: Int = 0
        private set

    private var wasUp = false
    private var wasDown = false
    private var wasLeft = false
    private var wasRight = false
    private var wasConfirm = false
    private var wasBack = false

    /** Seconds CONFIRM has been continuously held on the DELETE BOARD row, with the hold
     * armed. Zero whenever any of the conditions in [update]'s hold block is not met. */
    private var deleteHoldSeconds = 0f

    /**
     * Whether a hold on DELETE BOARD is allowed to start accumulating at all.
     *
     * Set FALSE the instant the LEADERBOARD page opens and TRUE again on the first frame
     * CONFIRM is not held. This is the entire CRITICAL C1 fix for this row — see [update]'s
     * hold block, which spells out what goes wrong without it.
     */
    private var deleteHoldArmed = false

    /**
     * How far through the delete hold the player is, 0..1 — for a progress bar, and read-only
     * so a renderer cannot advance or reset the hold by drawing it.
     *
     * 0f whenever no hold is in progress, which includes every frame the hold is unarmed. A bar
     * that sat at some non-zero value while the hold was refusing to accumulate would be
     * actively misleading: it would look like the CRITICAL C1 wipe is under way when it is not.
     */
    val deleteHoldProgress: Float
        get() = (deleteHoldSeconds / DELETE_HOLD_SECONDS).coerceIn(0f, 1f)

    /** Fixed row list per page. A page switch always resets [selectedIndex] to 0 — see
     * [update]'s CONFIRM handling and [reset]. */
    fun itemsOn(page: MenuPage): List<MenuItemId> = when (page)
    {
        MenuPage.ROOT -> ROOT_ITEMS
        MenuPage.GRAPHICS -> GRAPHICS_ITEMS
        MenuPage.LEADERBOARD -> LEADERBOARD_ITEMS
    }

    fun selectedItem(): MenuItemId = itemsOn(page)[selectedIndex]

    /** Back to the root page, first row, all edge state cleared — used both when a run starts
     * (so a stale menu selection never survives into the next visit) and by tests. */
    fun reset()
    {
        page = MenuPage.ROOT
        selectedIndex = 0
        wasUp = false
        wasDown = false
        wasLeft = false
        wasRight = false
        wasConfirm = false
        wasBack = false
        // A menu re-entry must never inherit a part-finished wipe from the last visit, and must
        // never inherit its ARMED flag either: `armed = false` is the conservative side, so a
        // freshly reset model demands a confirm release before any hold can start.
        deleteHoldSeconds = 0f
        deleteHoldArmed = false
    }

    /**
     * Seeds the `was*` edge-tracking fields directly from [up]/[down]/[left]/[right]/[confirm]/
     * [back], WITHOUT touching [page] or [selectedIndex]. Call this on the exact frame [reset]
     * (or the menu's own entry) happens WHILE a control is already held — most importantly the
     * very press that OPENED the menu, still physically down one frame later (a human press is
     * 5-10 frames).
     *
     * CRITICAL C1 (final review, 2026-08-30): [reset] alone zeroes every `was*` field to
     * `false`, so on the very next [update] call a still-held CONFIRM reads as a false-to-true
     * EDGE — the same button that opened the menu re-fires as if freshly pressed, immediately
     * confirming row 0 (START DIVE) and dropping the player straight into a dive one frame
     * after the menu appeared. `EnPustTil` calls [reset] to put the menu back at ROOT/row 0 as
     * before, then calls this to overwrite what [reset] just zeroed with the ACTUAL levels this
     * frame — so a continued hold reads as still-held (no edge) rather than freshly-pressed.
     *
     * Cancels opposing directions exactly like [update] does, for the identical reason: a stuck
     * contact holding both up and down (or left and right) at the moment of entry must not be
     * primed as "was pressed" on one side and produce a spurious edge on release of the other.
     */
    fun prime(up: Boolean, down: Boolean, left: Boolean, right: Boolean, confirm: Boolean, back: Boolean)
    {
        val cancelVertical = up && down
        val cancelHorizontal = left && right
        wasUp = up && !cancelVertical
        wasDown = down && !cancelVertical
        wasLeft = left && !cancelHorizontal
        wasRight = right && !cancelHorizontal
        wasConfirm = confirm
        wasBack = back
    }

    /**
     * Advance the menu by one frame.
     *
     * @param up/down/left/right/confirm/back raw per-frame LEVEL readings — see the class
     *   doc. This class re-edges every one of them itself.
     * @param dt seconds since the previous call, for the DELETE BOARD hold and nothing else.
     *   `EnPustTil.updateMainMenu` feeds it `engine.data.deltaTime` — the RENDER clock, not the
     *   fixed tick, which is correct and consistent: `lifecycle.update` on the very next line
     *   takes the same value, and `RunLifecycle`'s existing exit hold is measured on it too, so
     *   the two holds cannot drift apart in feel. It DEFAULTS TO ZERO so that the 22-of-25
     *   tests in `MenuModelTest` that funnel through its `press`/`release` helpers — and any
     *   caller with no hold to drive — keep compiling and behave exactly as before: a `dt` of
     *   zero accumulates nothing, so a caller that forgets it gets an inert hold rather than a
     *   hold that fires on some unrelated clock.
     * @return the single [MenuAction] this frame produced, [MenuAction.None] if nothing did.
     */
    fun update(up: Boolean, down: Boolean, left: Boolean, right: Boolean, confirm: Boolean,
               back: Boolean, dt: Float = 0f): MenuAction
    {
        // Cancel opposing directions BEFORE edge detection — see the class doc. A frame where
        // both are held looks identical, to the edge tracking below, to a frame where neither
        // is: no transition on either side, regardless of what was held the frame before.
        val cancelVertical = up && down
        val cancelHorizontal = left && right
        val upLevel = up && !cancelVertical
        val downLevel = down && !cancelVertical
        val leftLevel = left && !cancelHorizontal
        val rightLevel = right && !cancelHorizontal

        val upEdge = upLevel && !wasUp
        val downEdge = downLevel && !wasDown
        val leftEdge = leftLevel && !wasLeft
        val rightEdge = rightLevel && !wasRight
        val confirmEdge = confirm && !wasConfirm
        val backEdge = back && !wasBack

        wasUp = upLevel
        wasDown = downLevel
        wasLeft = leftLevel
        wasRight = rightLevel
        wasConfirm = confirm
        wasBack = back

        // ARMING, and it sits HERE — above the navigation early-returns — deliberately, so a
        // frame that only moves the selection still observes a released CONFIRM. A release is
        // the ONLY thing that ever arms the hold; see the hold block below for why.
        if (!confirm) deleteHoldArmed = true

        val items = itemsOn(page)

        if (upEdge)
        {
            selectedIndex = (selectedIndex - 1 + items.size) % items.size
            // "Reset on navigation", made literal rather than left to fall out of the row
            // check below — these branches return before that check runs.
            deleteHoldSeconds = 0f
            return MenuAction.None
        }
        if (downEdge)
        {
            selectedIndex = (selectedIndex + 1) % items.size
            deleteHoldSeconds = 0f
            return MenuAction.None
        }

        val current = items[selectedIndex]

        // --- HOLD TO DELETE THE BOARD --------------------------------------------------
        //
        // CRITICAL C1, IN A NEW PLACE, AND ITS CONSEQUENCE IS A WIPED LEADERBOARD.
        //
        // The obvious rule — "accumulate while CONFIRM is held on this row" — is UNSAFE here,
        // for exactly the reason [prime] exists at the menu's front door. Confirming
        // LEADERBOARD on ROOT switches the page and sets `selectedIndex = 0`, and DELETE BOARD
        // *is* row 0 of the page it just opened. [prime] is called only on menu ENTRY, never on
        // a page switch, so the still-held opening press would land straight on this row as a
        // LEVEL. A human press is 5-10 frames and anyone resting a thumb on the button holds it
        // far longer — so the very press that OPENS the page would clear the threshold and
        // destroy the board, with the player having asked only to LOOK at it.
        //
        // The fix is [deleteHoldArmed]: the page switch below sets it false, and only a frame
        // with CONFIRM NOT HELD sets it true again. A hold therefore requires a fresh press
        // AFTER a release that happened after the page opened. That is a rule about the input's
        // history, which is why it cannot be expressed as an edge on this frame alone.
        //
        // Placed BEFORE the confirm-EDGE branch below so the first frame of a legitimate hold
        // counts toward it, and so a crossing this frame wins over that branch's `else ->
        // MenuAction.None`. Which is the second guarantee, and it is free: a confirm EDGE on
        // DELETE BOARD falls into that `else` and does nothing at all. There is deliberately no
        // tap-to-delete path anywhere in this class.
        if (page == MenuPage.LEADERBOARD && current == MenuItemId.DELETE_BOARD &&
            confirm && deleteHoldArmed)
        {
            deleteHoldSeconds += dt
            if (deleteHoldSeconds >= DELETE_HOLD_SECONDS)
            {
                // EXACTLY ONCE, not every frame past the threshold. Zeroing the timer alone
                // would refire on the very next frame (the button is still down, and `+= dt`
                // would cross again a second and a half later at most); disarming is what makes
                // a second wipe need a second deliberate press.
                deleteHoldSeconds = 0f
                deleteHoldArmed = false
                return MenuAction.DeleteBoard
            }
        }
        else
        {
            // Released, navigated onto another row, or left the page — all three are "the
            // player stopped asking for this", and all three restart the hold from zero.
            deleteHoldSeconds = 0f
        }

        // CONFIRM CHECKED BEFORE BACK — belt-and-braces for CRITICAL C2 (final review,
        // 2026-08-30). `EnPustTil.updateMainMenu` now sources `back` from `pauseButton`,
        // which collides with nothing by default, so the two should never both read true on
        // the same frame in practice. But a technician's config CAN still make them collide
        // (`pauseButton` set to the same button as `restartButton`/`restartButtonAlt`), and
        // when they do, this order is what decides who wins. Checking confirm first means
        // START still works even under a misconfigured collision — the ordering CRITICAL C2
        // actually hit (back silently winning, so START/Options did nothing on ROOT) can no
        // longer recur even if the specific button collision that caused it does.
        if (confirmEdge)
        {
            return when (current)
            {
                MenuItemId.START_DIVE -> MenuAction.StartDive
                MenuItemId.QUIT -> MenuAction.Quit
                MenuItemId.GRAPHICS ->
                {
                    page = MenuPage.GRAPHICS
                    selectedIndex = 0
                    MenuAction.None
                }
                MenuItemId.LEADERBOARD ->
                {
                    // Opens a page in place, exactly like GRAPHICS. It used to emit
                    // [MenuAction.ShowLeaderboard] instead, which reached
                    // `RunLifecycle.viewLeaderboard()` and ABANDONED a held run to reach the
                    // attract screen's board; see that action's doc for why it is still
                    // declared.
                    page = MenuPage.LEADERBOARD
                    selectedIndex = 0
                    // DISARM — CRITICAL C1 in a new place. `selectedIndex = 0` above is
                    // DELETE_BOARD, and the press that got us here is still physically down.
                    // See the hold block for the whole argument; this single line is the fix.
                    deleteHoldArmed = false
                    deleteHoldSeconds = 0f
                    MenuAction.None
                }
                MenuItemId.BACK ->
                {
                    page = MenuPage.ROOT
                    selectedIndex = 0
                    deleteHoldSeconds = 0f
                    MenuAction.Back
                }
                else -> MenuAction.None
            }
        }

        if (backEdge)
        {
            // `page != ROOT`, NOT `page == GRAPHICS`, AND THE DIFFERENCE IS A CRITICAL DEFECT.
            //
            // This read `page == MenuPage.GRAPHICS` for as long as GRAPHICS was the only
            // sub-page, at which point the two spellings were the same predicate. The moment
            // [MenuPage.LEADERBOARD] was added they stopped being: back on the leaderboard page
            // would have fallen into the `else` and emitted [MenuAction.CloseMenu], which
            // `EnPustTil.applyMenuAction` routes straight to `RunLifecycle.resumeRun()` — so
            // Esc on the leaderboard page would DROP A PAUSED PLAYER BACK INTO THE WATER
            // instead of returning them to the row list. That is the same failure the GRAPHICS
            // case was written to prevent (see `MenuModelTest.back on GRAPHICS goes back a page
            // and does NOT report a close`), arriving through a page that did not exist yet.
            //
            // Written this way it is CLOSED against the next page too: a sub-page consumes its
            // own back by default, and only ROOT — the one page with nothing above it — can
            // ever report a close. Any future page is safe without anyone remembering this.
            return if (page != MenuPage.ROOT)
            {
                page = MenuPage.ROOT
                selectedIndex = 0
                deleteHoldSeconds = 0f
                MenuAction.Back
            }
            else
            {
                // Back on ROOT must never crash or quit — a player pressing B at the top
                // level is a no-op, not an accidental exit. It is REPORTED rather than
                // swallowed since 2026-08-31 so a caller with a menu to close can close it;
                // see [MenuAction.CloseMenu], which is inert for every caller that does not.
                MenuAction.CloseMenu
            }
        }

        if (current in VALUE_ITEMS)
        {
            if (leftEdge) return MenuAction.SettingChanged(current, -1)
            if (rightEdge) return MenuAction.SettingChanged(current, +1)
        }

        return MenuAction.None
    }

    companion object
    {
        /**
         * How long CONFIRM must be held on DELETE BOARD before the wipe fires.
         *
         * A READ of [RunLifecycle.EXIT_HOLD_SECONDS] (1.5 s), not a copy of its number, and
         * legal without breaking this class's engine-free purity: `RunLifecycle` is in this
         * same package, imports only `score.InitialsEntry`/`score.Leaderboard`, and has no
         * pulseengine import of its own — it is exactly as headless-testable as this file.
         *
         * Sharing it is the point rather than a convenience. The two holds are the only
         * hold-to-confirm gestures in the game and both guard an irreversible act (shutting the
         * cabinet down; wiping the day's board), so they must feel identical — a player who has
         * learned one has learned the other, and a bar that filled at a different rate would
         * read as a different kind of commitment. That constant's own doc carries the argument
         * for the DURATION (long enough that a bump or a bounce cannot produce it, short enough
         * to still feel immediate); it applies here unchanged.
         *
         * FLAGGED AS A DECISION, NOT AN OVERSIGHT: 1.5 s is on the short side for an
         * irreversible wipe of a booth day's scores, and the spec (§4.2) says so. Consistency
         * won. If it is ever raised, raise it HERE and leave the exit hold alone — a longer
         * wipe than exit is defensible, two arbitrary different numbers are not.
         */
        const val DELETE_HOLD_SECONDS = RunLifecycle.EXIT_HOLD_SECONDS

        private val ROOT_ITEMS = listOf(
            MenuItemId.START_DIVE, MenuItemId.GRAPHICS, MenuItemId.LEADERBOARD, MenuItemId.QUIT
        )

        private val GRAPHICS_ITEMS = listOf(
            MenuItemId.QUALITY,
            MenuItemId.LIGHT_MAP_SCALE, MenuItemId.SCENE_SCALE, MenuItemId.GLOBAL_SCALE,
            MenuItemId.MAX_CASCADES, MenuItemId.RAY_QUALITY, MenuItemId.OFF_SCREEN_RAYS,
            MenuItemId.RESOLUTION, MenuItemId.RENDER_SCALE,
            MenuItemId.FULLSCREEN, MenuItemId.FRAME_CAP, MenuItemId.VSYNC,
            MenuItemId.SHOW_FPS, MenuItemId.BACK
        )

        /**
         * The LEADERBOARD page. Two rows only: the board itself is DISPLAY, not rows — it is
         * not navigable, has no per-entry action, and drawing it is the renderer's business.
         * Listing entries as rows would put an unbounded, run-count-dependent list into a
         * fixed-row navigation model that assumes `items[selectedIndex]` always resolves.
         */
        private val LEADERBOARD_ITEMS = listOf(MenuItemId.DELETE_BOARD, MenuItemId.BACK)

        /** Rows whose left/right nudges a value rather than doing nothing. BACK is
         * deliberately excluded even though it lives on GRAPHICS — it is an action row, not a
         * value row. */
        private val VALUE_ITEMS = setOf(
            MenuItemId.QUALITY,
            MenuItemId.LIGHT_MAP_SCALE, MenuItemId.SCENE_SCALE, MenuItemId.GLOBAL_SCALE,
            MenuItemId.MAX_CASCADES, MenuItemId.RAY_QUALITY, MenuItemId.OFF_SCREEN_RAYS,
            MenuItemId.RESOLUTION, MenuItemId.RENDER_SCALE,
            MenuItemId.FULLSCREEN, MenuItemId.FRAME_CAP, MenuItemId.VSYNC, MenuItemId.SHOW_FPS
        )
    }
}
