package score

/**
 * One recorded run: three-letter arcade initials (never a name or e-mail — see design
 * spec §12, which picks initials precisely to avoid collecting personal data), the
 * banked score, the day's seed (so day-one and day-two boards are distinguishable — see
 * `DAILY_SEED` in `EnPustTil`), and when it was recorded.
 *
 * Deliberately engine-free — no `no.njoh.pulseengine` import anywhere in this package
 * except [ScoreRepository] itself — so ranking, validation and the initials-entry state
 * machine built around this type are unit-testable without booting PulseEngine.
 */
data class ScoreEntry(
    val initials: String,
    val score: Int,
    val seed: Long,
    val timestampMs: Long
)
