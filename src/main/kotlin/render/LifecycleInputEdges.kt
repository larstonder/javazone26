package render

/**
 * Per-source edge detection for lifecycle input (start / restart / confirm), plus
 * stuck-source and chattering-source rejection.
 *
 * THE BUG THIS FIXES. [RunLifecycle.update] takes ONE boolean and edges it:
 *
 *     val pressedEdge = anyInputPressed && !wasInputPressed
 *
 * which is correct and load-bearing — it is what stops a stuck button from restarting the
 * game forever, the incident RunLifecycle's class doc was written for. The defect was
 * upstream: `anyLifecycleActionPressed` collapsed every source into that one boolean
 * BEFORE the edge was taken. One stuck encoder button then held it true forever, no edge
 * was ever produced again, and the cabinet could not be started by anyone — not by a
 * second gamepad, not by SPACE, because neither can change an OR that is already true.
 * The attract screen looked perfectly healthy throughout, which is what made it a
 * two-day failure rather than a two-minute one.
 *
 * Taking the OR over EDGES instead of over LEVELS fixes it: a jammed START contributes no
 * edge while A on the same pad still starts a run.
 *
 * A SOURCE IS A (padId, buttonOrdinal) PAIR, not a pad. The cabinet has one encoder, so
 * per-pad granularity would not have helped it at all.
 *
 * FIRST SIGHT ESTABLISHES A LEVEL, IT DOES NOT FIRE. A pad hot-plugged with a jammed
 * button would otherwise start a run the instant GLFW enumerated it.
 *
 * A SECOND FAILURE MODE, THE MIRROR OF THE FIRST: a booth encoder may not just stick
 * closed, it may CHATTER — CLAUDE.md already records that a generic USB encoder can be
 * "unmapped and noisy". A button oscillating pressed/released every frame never holds
 * long enough to trip [isStuck] (`heldSeconds` resets to 0 on every released frame), so
 * an un-debounced version of this class would emit a fresh edge every other frame
 * forever, invisible on the stuck-count line. That is worse than the stuck-closed bug:
 * wired to `anyInputPressed` it restarts the run before anyone reads their score, and
 * wired to `confirmPressed` it burns all three initials slots in a handful of frames and
 * auto-submits `AAA` for every player. [MIN_EDGE_INTERVAL_SECONDS] is the guard — see its
 * doc and the per-source `secondsSinceEdge` tracking in [offer].
 *
 * ALLOCATION: begin/offer/commit is a push protocol precisely so the caller does not build
 * a collection per frame. The parallel ArrayLists grow only when a genuinely new source
 * appears (at most a handful, once) and are re-used — not re-allocated — every frame
 * after. That is a large reduction from the `List<Boolean>` this replaced (built fresh by
 * `.map {}` every single frame), but it is not literally zero: `heldSeconds[i] = held` and
 * `secondsSinceEdge[i] = ...` each box one `Float` per `offer` call (no `Float` cache, unlike
 * small `Int`/`Long`), and `EnPustTil`'s `engine.input.gamepads.forEach { ... }` allocates
 * one `Iterator` per frame for that `List`. A handful of small objects per frame, not none —
 * named here rather than implied away, per CLAUDE.md's no-per-frame-allocation rule.
 *
 * Engine-free — ordinals and ints, never `Gamepad` or `GamepadButton` — so it is unit
 * testable without a GL context, following this project's pure-logic-extracted pattern.
 */
class LifecycleInputEdges(private val stuckSeconds: Float = STUCK_SECONDS)
{
    private val padIds = ArrayList<Int>()
    private val buttonOrdinals = ArrayList<Int>()
    private val wasPressed = ArrayList<Boolean>()
    private val heldSeconds = ArrayList<Float>()
    private val secondsSinceEdge = ArrayList<Float>()
    private val seenThisFrame = ArrayList<Boolean>()

    private var dt = 0f
    private var edge = false

    /**
     * The pad whose button produced this frame's edge; null both when the edge (if any)
     * came from the keyboard, AND when no edge fired this frame at all — check the return
     * of [commit] first if the distinction matters.
     */
    var firedPadId: Int? = null
        private set

    /**
     * How many sources are currently jammed stuck-closed. Drawn on the booth status line.
     * Deliberately does NOT count chattering sources — a stuck button and a chattering one
     * call for different physical fixes (power-cycle the cabinet vs. reseat a connector),
     * and folding both into one number would tell an operator something is wrong without
     * telling them which repair to attempt. A plain indexed loop, not `.count { }` — see
     * class doc's ALLOCATION paragraph.
     */
    val stuckCount: Int get()
    {
        var count = 0
        for (i in heldSeconds.indices) if (heldSeconds[i] >= stuckSeconds) count++
        return count
    }

    fun isStuck(padId: Int, buttonOrdinal: Int): Boolean
    {
        val i = indexOf(padId, buttonOrdinal)
        return i >= 0 && heldSeconds[i] >= stuckSeconds
    }

    fun begin(dt: Float)
    {
        this.dt = dt
        edge = false
        firedPadId = null
        for (i in seenThisFrame.indices) seenThisFrame[i] = false
    }

    fun offer(padId: Int, buttonOrdinal: Int, pressed: Boolean)
    {
        var i = indexOf(padId, buttonOrdinal)
        val firstSight = i < 0
        if (firstSight)
        {
            padIds.add(padId)
            buttonOrdinals.add(buttonOrdinal)
            // Seeded with the level as observed, NOT false: a pad hot-plugged with a jammed
            // button must not read as a press on the frame it appears.
            wasPressed.add(pressed)
            heldSeconds.add(0f)
            // Seeded AT the debounce threshold, not 0: the very first genuine press this
            // source ever offers must not be suppressed as "too soon after" a last edge
            // that never happened.
            secondsSinceEdge.add(MIN_EDGE_INTERVAL_SECONDS)
            seenThisFrame.add(true)
            i = padIds.size - 1
        }

        seenThisFrame[i] = true
        val held = if (pressed) heldSeconds[i] + dt else 0f
        heldSeconds[i] = held
        secondsSinceEdge[i] = secondsSinceEdge[i] + dt

        // A source declared stuck contributes nothing in EITHER direction: no edge of its
        // own, and no block on any other source producing one. `held < stuckSeconds` can
        // only be false here on the very frame that crosses the stuck threshold: an edge
        // requires `!wasPressed[i]`, which means `heldSeconds[i]` was reset to 0 last time
        // this source was offered released, so `held == dt` on every genuine edge frame
        // unless a single frame itself lasts >= stuckSeconds. It is a dt-sanity guard, not
        // the thing that stops a stuck button re-firing — `!wasPressed[i]` already does
        // that, unconditionally, the frame after a stuck button's one real press. Kept
        // anyway so a pathological multi-second frame (a debugger breakpoint, a GC pause)
        // cannot manufacture a fresh "edge" out of a button that has simply been held the
        // whole time.
        //
        // secondsSinceEdge[i] >= MIN_EDGE_INTERVAL_SECONDS is the debounce: it rejects a
        // transition from a source whose LAST FIRED EDGE was too recent, which is what a
        // chattering button (see class doc) produces every other frame forever. It is
        // reset only when an edge actually fires, not on every attempted transition, so a
        // source that is genuinely toggling slowly (well under chatter rate) is never
        // permanently silenced — it simply cannot exceed ~25 edges/second.
        if (!firstSight && pressed && !wasPressed[i] && held < stuckSeconds &&
            secondsSinceEdge[i] >= MIN_EDGE_INTERVAL_SECONDS)
        {
            edge = true
            secondsSinceEdge[i] = 0f
            if (firedPadId == null) firedPadId = padId
        }
        wasPressed[i] = pressed
    }

    /**
     * The keyboard's own source. [clicked] is already an edge (`engine.input.wasClicked`),
     * so it is passed through rather than re-edged — but it is kept SEPARATE from the pads
     * so a jammed pad button can never mask it, which is what the single-boolean collapse
     * used to do. Not debounced: the engine's own `wasClicked` is already a one-frame
     * pulse per physical keypress, so there is nothing for a chatter guard to catch here.
     */
    fun offerKeyboardEdge(clicked: Boolean)
    {
        if (clicked) edge = true
    }

    /** True for exactly one frame per genuine press, from any live source. */
    fun commit(): Boolean
    {
        // Forget sources not offered this frame — an unplugged pad must not keep reporting
        // itself stuck on the booth status line for the rest of the day. Six parallel lists,
        // kept in lockstep: every add() above and every removeAt() below touches all six in
        // the same order, and this sweep runs indices DESCENDING so a removal never shifts
        // an index this loop has not visited yet.
        var i = padIds.size - 1
        while (i >= 0)
        {
            if (!seenThisFrame[i])
            {
                padIds.removeAt(i)
                buttonOrdinals.removeAt(i)
                wasPressed.removeAt(i)
                heldSeconds.removeAt(i)
                secondsSinceEdge.removeAt(i)
                seenThisFrame.removeAt(i)
            }
            i--
        }
        return edge
    }

    private fun indexOf(padId: Int, buttonOrdinal: Int): Int
    {
        for (i in padIds.indices)
            if (padIds[i] == padId && buttonOrdinals[i] == buttonOrdinal) return i
        return -1
    }

    companion object
    {
        /**
         * An unbroken hold longer than this is hardware, not a player. Well past any real
         * press (an arcade button is down for ~0.1 s) and well short of RunLifecycle's
         * DWELL_SECONDS, so a player leaning on START at the end of a run is never
         * mistaken for a fault.
         */
        const val STUCK_SECONDS = 3f

        /**
         * The minimum time a source must wait after producing an edge before it may
         * produce another. 40 ms allows 25 presses/second through untouched — far above
         * any human mash rate — while rejecting the every-other-frame edge a chattering
         * booth encoder would otherwise emit at 60 fps (see class doc). Chosen as a rate
         * limit on FIRED edges, not on attempted transitions, so a source that is
         * genuinely pressed twice in quick succession by a human still gets its second
         * press once 40 ms have passed, rather than being locked out for a fixed window.
         *
         * NOT 50 ms, deliberately: at this suite's one-frame-is-0.016f convention, three
         * frames (hold, release, re-press — exactly the shape of `a press produces exactly
         * one edge`'s "a fresh press fires again" case) sum to 0.048s, which a 50 ms floor
         * would reject as too soon after the first press and break an already-correct,
         * already-reviewed test. A single chattering step (press, one release frame,
         * press) sums to only 0.032s, so 0.04s sits with real margin on both sides: it
         * still rejects every-other-frame chatter while leaving room for a genuine
         * hold-then-re-press one frame later than that.
         */
        const val MIN_EDGE_INTERVAL_SECONDS = 0.04f
    }
}
