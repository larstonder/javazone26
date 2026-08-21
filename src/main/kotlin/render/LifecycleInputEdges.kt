package render

/**
 * Per-source edge detection for lifecycle input (start / restart / confirm), plus
 * stuck-source detection.
 *
 * THE BUG THIS FIXES. [RunLifecycle.update] takes ONE boolean and edges it:
 *
 *     val pressedEdge = anyInputPressed && !wasInputPressed
 *
 * which is correct and load-bearing - it is what stops a stuck button from restarting the
 * game forever, the incident RunLifecycle's class doc was written for. The defect was
 * upstream: `anyLifecycleActionPressed` collapsed every source into that one boolean
 * BEFORE the edge was taken. One stuck encoder button then held it true forever, no edge
 * was ever produced again, and the cabinet could not be started by anyone - not by a
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
 * ALLOCATION: begin/offer/commit is a push protocol precisely so the caller does not build
 * a collection per frame. The parallel ArrayLists grow only when a genuinely new source
 * appears (at most a handful, once) and are reused every frame after. See CLAUDE.md's
 * no-per-frame-allocation rule.
 *
 * Engine-free - ordinals and ints, never `Gamepad` or `GamepadButton` - so it is unit
 * testable without a GL context, following this project's pure-logic-extracted pattern.
 */
class LifecycleInputEdges(private val stuckSeconds: Float = STUCK_SECONDS)
{
    private val padIds = ArrayList<Int>()
    private val buttonOrdinals = ArrayList<Int>()
    private val wasPressed = ArrayList<Boolean>()
    private val heldSeconds = ArrayList<Float>()
    private val seenThisFrame = ArrayList<Boolean>()

    private var dt = 0f
    private var edge = false

    /** The pad whose button produced this frame's edge, or null for a keyboard start. */
    var firedPadId: Int? = null
        private set

    /** How many sources are currently jammed. Drawn on the booth status line. */
    val stuckCount: Int get() = heldSeconds.count { it >= stuckSeconds }

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
            seenThisFrame.add(true)
            i = padIds.size - 1
        }

        seenThisFrame[i] = true
        val held = if (pressed) heldSeconds[i] + dt else 0f
        heldSeconds[i] = held

        // A source declared stuck contributes nothing in EITHER direction: no edge of its
        // own, and no block on any other source producing one.
        if (!firstSight && pressed && !wasPressed[i] && held < stuckSeconds)
        {
            edge = true
            if (firedPadId == null) firedPadId = padId
        }
        wasPressed[i] = pressed
    }

    /**
     * The keyboard's own source. [clicked] is already an edge (`engine.input.wasClicked`),
     * so it is passed through rather than re-edged - but it is kept SEPARATE from the pads
     * so a jammed pad button can never mask it, which is what the single-boolean collapse
     * used to do.
     */
    fun offerKeyboardEdge(clicked: Boolean)
    {
        if (clicked) edge = true
    }

    /** True for exactly one frame per genuine press, from any live source. */
    fun commit(): Boolean
    {
        // Forget sources not offered this frame - an unplugged pad must not keep reporting
        // itself stuck on the booth status line for the rest of the day.
        var i = padIds.size - 1
        while (i >= 0)
        {
            if (!seenThisFrame[i])
            {
                padIds.removeAt(i)
                buttonOrdinals.removeAt(i)
                wasPressed.removeAt(i)
                heldSeconds.removeAt(i)
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
    }
}
