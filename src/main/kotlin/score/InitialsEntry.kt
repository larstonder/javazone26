package score

/**
 * Pure, engine-free state machine for entering three-letter arcade initials on a
 * joystick plus one button — the only input the booth cabinet has (design spec §5,
 * §12). Owned by `render.RunLifecycle` rather than driven in parallel by `EnPustTil`,
 * so the lifecycle's existing edge-tracking and idle-timeout machinery cover initials
 * entry too, instead of a second copy of that machinery living alongside it.
 *
 * Up/down cycles the letter in the CURRENT slot (A<->Z wraps); the advance button
 * confirms that slot and moves to the next. After the third slot is confirmed,
 * [complete] becomes true and [initialsString] holds the final value.
 *
 * EDGE-TRIGGERED, for the same reason as `RunLifecycle` (see its class doc for the
 * incident that made this non-negotiable): [update]'s three parameters are LEVEL
 * readings — a held gamepad button or a stick pushed to one side reads true every
 * single frame, and the engine's `Gamepad` gives no edge-detected alternative. This
 * class tracks the previous frame itself, so a held stick or a stuck button cycles or
 * advances exactly once per press, not once per frame. Deliberately no auto-repeat while
 * held — simpler, fully deterministic, and consistent with how the rest of this booth's
 * input handling behaves.
 */
class InitialsEntry
{
    private var letters = charArrayOf('A', 'A', 'A')

    /** Which slot (0..2) is currently being edited. Meaningless once [complete]. */
    var slot = 0; private set

    /** True once all three slots have been confirmed. [update] is then a no-op. */
    var complete = false; private set

    private var wasCycleUp = false
    private var wasCycleDown = false
    private var wasConfirmPressed = false

    fun initialsString(): String = String(letters)

    /** Reusable across runs — clears back to "AAA", slot 0, not complete. */
    fun reset()
    {
        letters = charArrayOf('A', 'A', 'A')
        slot = 0
        complete = false
        wasCycleUp = false
        wasCycleDown = false
        wasConfirmPressed = false
    }

    /**
     * Advance the entry by one frame.
     *
     * @param cycleUp level reading of "cycle the current letter forward" (stick pushed
     *   up, or the keyboard UP key held) — see [render.EnPustTil]'s reader for the
     *   combined gamepad/keyboard source.
     * @param cycleDown level reading of "cycle the current letter backward".
     * @param confirmPressed level reading of the advance button.
     */
    fun update(cycleUp: Boolean, cycleDown: Boolean, confirmPressed: Boolean)
    {
        if (complete) return

        val upEdge = cycleUp && !wasCycleUp
        val downEdge = cycleDown && !wasCycleDown
        val confirmEdge = confirmPressed && !wasConfirmPressed
        wasCycleUp = cycleUp
        wasCycleDown = cycleDown
        wasConfirmPressed = confirmPressed

        // Only one of up/down acts per frame — if a diagonal or an unlucky same-frame
        // release-and-repress somehow sets both edges true, up wins deterministically
        // rather than the two cancelling or racing.
        if (upEdge) letters[slot] = cycleLetter(letters[slot], +1)
        else if (downEdge) letters[slot] = cycleLetter(letters[slot], -1)

        if (confirmEdge)
        {
            slot++
            if (slot >= LETTER_COUNT) complete = true
        }
    }

    companion object
    {
        const val LETTER_COUNT = 3
        private const val ALPHABET_SIZE = 26

        /** A<->Z wraps in both directions. [direction] is +1 or -1. */
        internal fun cycleLetter(c: Char, direction: Int): Char
        {
            val base = 'A'.code
            val index = (((c.code - base) + direction) % ALPHABET_SIZE + ALPHABET_SIZE) % ALPHABET_SIZE
            return (base + index).toChar()
        }
    }
}

/**
 * Three uppercase A-Z letters, nothing else — the only shape [InitialsEntry] can ever
 * produce. Used defensively when loading persisted scores: even a file that parsed as
 * valid JSON might contain data a human introduced by hand-editing it, or data left
 * over from a future format this code doesn't know about.
 */
fun isValidInitials(s: String): Boolean = s.length == InitialsEntry.LETTER_COUNT && s.all { it in 'A'..'Z' }

/**
 * Defensive filter applied to whatever comes back from disk, on top of
 * `engine.data.loadObject`'s own null-on-failure behaviour (verified by decompiling
 * `DataImpl.loadObject`: it wraps the read+parse in a `runCatching`-style block and
 * returns null, logging the exception, rather than throwing — so a totally unparsable
 * file already cannot crash the game). This is the second layer: entries with invalid
 * initials or a non-positive score are dropped rather than displayed or entered into the
 * `winner` raffle draw.
 */
fun sanitizeEntries(raw: List<ScoreEntry>?): List<ScoreEntry> =
    raw?.filter { isValidInitials(it.initials) && it.score > 0 } ?: emptyList()
