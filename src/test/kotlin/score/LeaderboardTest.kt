package score

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaderboardTest
{
    private fun entry(initials: String, score: Int, timestampMs: Long = 0L, seed: Long = 1L) =
        ScoreEntry(initials, score, seed, timestampMs)

    @Test
    fun `rank orders highest score first`()
    {
        val entries = listOf(entry("AAA", 100), entry("BBB", 500), entry("CCC", 250))
        val ranked = Leaderboard.rank(entries)
        assertEquals(listOf("BBB", "CCC", "AAA"), ranked.map { it.initials })
    }

    @Test
    fun `a tie is broken by whoever set the score first`()
    {
        val later = entry("LAT", 100, timestampMs = 2000L)
        val earlier = entry("ERL", 100, timestampMs = 1000L)
        val ranked = Leaderboard.rank(listOf(later, earlier))
        assertEquals(listOf("ERL", "LAT"), ranked.map { it.initials }, "earlier timestamp must win a tie")
    }

    @Test
    fun `topN truncates to the requested count`()
    {
        val entries = (1..20).map { entry("A%02d".format(it).take(3), it * 10) }
        val top3 = Leaderboard.topN(entries, 3)
        assertEquals(3, top3.size)
        assertEquals(listOf(200, 190, 180), top3.map { it.score })
    }

    @Test
    fun `topN with n less than or equal to zero is empty, not a crash`()
    {
        val entries = listOf(entry("AAA", 100))
        assertTrue(Leaderboard.topN(entries, 0).isEmpty())
        assertTrue(Leaderboard.topN(entries, -5).isEmpty())
    }

    @Test
    fun `topN never exceeds what rank would return in full`()
    {
        val entries = listOf(entry("AAA", 100), entry("BBB", 200))
        assertEquals(Leaderboard.rank(entries), Leaderboard.topN(entries, 10))
    }

    @Test
    fun `a positive score is worth recording`()
    {
        assertTrue(Leaderboard.isWorthRecording(1))
        assertTrue(Leaderboard.isWorthRecording(12000))
    }

    @Test
    fun `a zero-point run is never worth recording`()
    {
        assertFalse(Leaderboard.isWorthRecording(0), "a 0-point run must not trigger the initials-entry prompt")
    }

    @Test
    fun `a negative score is never worth recording`()
    {
        // Should not be reachable from real gameplay (banked never goes negative), but
        // this is a defensive boundary the rule must still get right.
        assertFalse(Leaderboard.isWorthRecording(-1))
    }
}
