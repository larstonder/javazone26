package dive

/**
 * BANKED += HELD * (1 + maxDepthReached / 30)
 *
 * The bonus keys off the deepest point REACHED, not where pearls were collected.
 * That is what makes the expert route work: dive fast and empty to lock the
 * multiplier, then collect on the way up.
 */
object Scoring
{
    fun depthBonus(maxDepth: Float) = 1f + maxDepth / Tuning.DEPTH_BONUS_DIVISOR

    fun bank(held: Int, maxDepth: Float) = (held * depthBonus(maxDepth)).toInt()

    fun blackoutBank(held: Int) = (held * Tuning.BLACKOUT_KEEP).toInt()
}
