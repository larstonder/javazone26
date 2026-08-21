package score

/**
 * Pure ranking/selection logic for the leaderboard. No `no.njoh.pulseengine` import —
 * [EngineScoreStore] is the only class in this package that touches `engine.data`
 * directly (`ScoreRepository` itself also imports `PulseEngine`, and `AtomicFileSwap`
 * touches a file directly, just never through the engine); everything here just orders
 * and filters a list already sitting in memory, so it can be unit-tested (and
 * mutation-tested) without booting the engine.
 */
object Leaderboard
{
    /**
     * Highest score first. Ties are broken by whoever set that score FIRST — earlier
     * [ScoreEntry.timestampMs] ranks higher. Chosen over "most recent wins" because a
     * leaderboard at a booth reads naturally as "who do I have to beat", and the player
     * who set the number first is the one a later, equal score failed to beat.
     */
    fun rank(entries: List<ScoreEntry>): List<ScoreEntry> =
        entries.sortedWith(compareByDescending<ScoreEntry> { it.score }.thenBy { it.timestampMs })

    /** Top [n] ranked entries. [n] <= 0 returns an empty list rather than throwing. */
    fun topN(entries: List<ScoreEntry>, n: Int): List<ScoreEntry> =
        if (n <= 0) emptyList() else rank(entries).take(n)

    /**
     * Whether a run's banked total is worth the cost of an initials-entry prompt.
     *
     * CHOSEN RULE: any positive score (`score > 0`). A 0-point run — someone who never
     * left the surface, or blacked out on their very first dive with nothing held — gets
     * no prompt at all: per the task, a zero-value run must never block the cabinet with
     * a data-entry screen while a queue waits behind it. Any positive score, however
     * small, genuinely competes for a leaderboard slot and a shot at the daily prize, so
     * it is always offered. Deliberately NOT gated on "must beat the current Nth place":
     * that would make the prompt's appearance depend on how full the board already is,
     * which is confusing to explain at a glance and would unfairly punish the very first
     * players of the day, before the board has anything on it at all.
     */
    fun isWorthRecording(score: Int): Boolean = score > 0
}
