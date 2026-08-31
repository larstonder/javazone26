package render

/** The two pages the main menu can be on. Confirming GRAPHICS from ROOT opens GRAPHICS; BACK
 * from GRAPHICS returns to ROOT. There is no deeper nesting — a third page would need a page
 * stack, which this deliberately does not have because nothing in the design calls for one. */
enum class MenuPage { ROOT, GRAPHICS }

/**
 * Every row either page can show. Not every id appears on every page — [MenuModel.itemsOn]
 * decides that — but [MenuModel] asserts (via its own test) that every id is reachable from
 * ROOT by navigation alone, so an id declared here and forgotten on a page would fail the
 * build rather than sit as dead code that looks live.
 */
enum class MenuItemId
{
    START_DIVE, GRAPHICS, LEADERBOARD, QUIT,
    QUALITY, RESOLUTION, RENDER_SCALE, FULLSCREEN, FRAME_CAP, VSYNC, SHOW_FPS, BACK
}

/** What confirming or nudging a row produces. [MenuModel.update] returns exactly one of these
 * per call, [None] when nothing happened this frame. */
sealed interface MenuAction
{
    object StartDive : MenuAction
    object ShowLeaderboard : MenuAction
    object Quit : MenuAction
    object Back : MenuAction
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

    /** Fixed row list per page. A page switch always resets [selectedIndex] to 0 — see
     * [update]'s CONFIRM handling and [reset]. */
    fun itemsOn(page: MenuPage): List<MenuItemId> = when (page)
    {
        MenuPage.ROOT -> ROOT_ITEMS
        MenuPage.GRAPHICS -> GRAPHICS_ITEMS
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
     * @return the single [MenuAction] this frame produced, [MenuAction.None] if nothing did.
     */
    fun update(up: Boolean, down: Boolean, left: Boolean, right: Boolean, confirm: Boolean,
               back: Boolean): MenuAction
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

        val items = itemsOn(page)

        if (upEdge)
        {
            selectedIndex = (selectedIndex - 1 + items.size) % items.size
            return MenuAction.None
        }
        if (downEdge)
        {
            selectedIndex = (selectedIndex + 1) % items.size
            return MenuAction.None
        }

        val current = items[selectedIndex]

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
                MenuItemId.LEADERBOARD -> MenuAction.ShowLeaderboard
                MenuItemId.QUIT -> MenuAction.Quit
                MenuItemId.GRAPHICS ->
                {
                    page = MenuPage.GRAPHICS
                    selectedIndex = 0
                    MenuAction.None
                }
                MenuItemId.BACK ->
                {
                    page = MenuPage.ROOT
                    selectedIndex = 0
                    MenuAction.Back
                }
                else -> MenuAction.None
            }
        }

        if (backEdge)
        {
            return if (page == MenuPage.GRAPHICS)
            {
                page = MenuPage.ROOT
                selectedIndex = 0
                MenuAction.Back
            }
            else
            {
                // Back on ROOT must never crash or quit — a player pressing B at the top
                // level is a no-op, not an accidental exit.
                MenuAction.None
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
        private val ROOT_ITEMS = listOf(
            MenuItemId.START_DIVE, MenuItemId.GRAPHICS, MenuItemId.LEADERBOARD, MenuItemId.QUIT
        )

        private val GRAPHICS_ITEMS = listOf(
            MenuItemId.QUALITY, MenuItemId.RESOLUTION, MenuItemId.RENDER_SCALE,
            MenuItemId.FULLSCREEN, MenuItemId.FRAME_CAP, MenuItemId.VSYNC,
            MenuItemId.SHOW_FPS, MenuItemId.BACK
        )

        /** Rows whose left/right nudges a value rather than doing nothing. BACK is
         * deliberately excluded even though it lives on GRAPHICS — it is an action row, not a
         * value row. */
        private val VALUE_ITEMS = setOf(
            MenuItemId.QUALITY, MenuItemId.RESOLUTION, MenuItemId.RENDER_SCALE,
            MenuItemId.FULLSCREEN, MenuItemId.FRAME_CAP, MenuItemId.VSYNC, MenuItemId.SHOW_FPS
        )
    }
}
