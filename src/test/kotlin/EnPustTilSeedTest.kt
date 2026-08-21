import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [parseDailySeed] is what lets a technician change the water column between the two
 * conference days by editing one line in application.cfg, without a rebuild — see its
 * doc and EnPustTil.onCreate. Covers both ways a text file can fail to give a usable
 * value: the key absent entirely (day one, before anyone has touched the file) and the
 * key present but not a valid Long (a typo, which must fall back rather than crash the
 * booth machine).
 *
 * [resolveDailySeed] is the ACTUAL call site (`createGame`) uses — see its doc for why a
 * `String` is only one of three shapes application.cfg's own loader can store one text
 * value as, and why reading only [parseDailySeed] against `getString` (which is all this
 * code did before that function existed) meant the day-two procedure never worked at all:
 * every real seed used at this booth is all-digits and stores as `Int`, not `String`.
 */
class EnPustTilSeedTest
{
    @Test
    fun `returns fallback when raw is null (key absent from application-cfg)`()
    {
        assertEquals(20260902L, parseDailySeed(raw = null, fallback = 20260902L))
    }

    @Test
    fun `parses a present, valid override instead of the fallback`()
    {
        assertEquals(20260903L, parseDailySeed(raw = "20260903", fallback = 20260902L))
    }

    @Test
    fun `falls back on a non-numeric value rather than crashing`()
    {
        assertEquals(20260902L, parseDailySeed(raw = "not-a-number", fallback = 20260902L))
    }

    @Test
    fun `falls back on an empty string`()
    {
        assertEquals(20260902L, parseDailySeed(raw = "", fallback = 20260902L))
    }

    @Test
    fun `resolveDailySeed prefers the Int shape - the one every real seed here arrives in`()
    {
        // THE BUG THIS FUNCTION FIXES. "20260903" is all-digits, so application.cfg's own
        // loader stores it as an Integer, not a String - engine.config.getString("dailySeed")
        // returns null for it. Empirically verified against the real ConfigurationImpl; see
        // task-6-7-report.md for the harness. A version reading only getString/parseDailySeed
        // would return the fallback here even though a perfectly valid override is present.
        assertEquals(20260903L, resolveDailySeed(rawInt = 20260903, rawString = null, fallback = 20260902L))
    }

    @Test
    fun `resolveDailySeed falls back to the String path when there is no Int`()
    {
        // The shape a non-numeric typo actually arrives in.
        assertEquals(20260902L, resolveDailySeed(rawInt = null, rawString = "not-a-number", fallback = 20260902L))
    }

    @Test
    fun `resolveDailySeed falls back when both are absent`()
    {
        assertEquals(20260902L, resolveDailySeed(rawInt = null, rawString = null, fallback = 20260902L))
    }

    @Test
    fun `resolveDailySeed prefers Int even when a stale String also happens to be present`()
    {
        // Should not occur in practice (ConfigurationImpl stores one value under one key,
        // never both) - asserted anyway because rawInt is checked FIRST by construction,
        // not by accident, and this is the test that would catch the check being reordered.
        assertEquals(1L, resolveDailySeed(rawInt = 1, rawString = "2", fallback = 3L))
    }

    // ---- dailySeedConfigWarning: making BOTH typo shapes visible, not just the decimal one ----

    @Test
    fun `dailySeedConfigWarning is null when the key is absent entirely`()
    {
        assertEquals(null, dailySeedConfigWarning(rawString = null, rawFloat = null))
    }

    @Test
    fun `dailySeedConfigWarning fires for a letter-for-digit typo - the more likely typo, and the one an earlier version missed`()
    {
        // "2O260903" (letter O for digit 0) stores as a String, not a Float - a version of
        // this warning that only checked the Float/decimal-point shape said nothing here.
        val warning = dailySeedConfigWarning(rawString = "2O260903", rawFloat = null)
        assertEquals(true, warning != null && warning.contains("2O260903"))
    }

    @Test
    fun `dailySeedConfigWarning fires for a stray decimal point`()
    {
        val warning = dailySeedConfigWarning(rawString = null, rawFloat = 2026.0903f)
        assertEquals(true, warning != null && warning.contains("2026.0903"))
    }

    @Test
    fun `dailySeedConfigWarning is null for a String that actually parses - the caller never gets here in that case, but the function must not warn regardless`()
    {
        assertEquals(null, dailySeedConfigWarning(rawString = "20260903", rawFloat = null))
    }

    // ---- configFileHealthWarning: a cheap general symptom-check for a partial load ----

    @Test
    fun `configFileHealthWarning is null when gameName reads back correctly`()
    {
        assertEquals(null, configFileHealthWarning(GAME_NAME))
    }

    @Test
    fun `configFileHealthWarning fires when gameName is missing`()
    {
        // The shape a silently-aborted load produces: gameName's hash bucket was one of
        // the entries the loader never reached before the throw.
        val warning = configFileHealthWarning(null)
        assertEquals(true, warning != null && warning.contains(GAME_NAME))
    }

    @Test
    fun `configFileHealthWarning fires when gameName reads back as something else entirely`()
    {
        val warning = configFileHealthWarning("NotEnPustTil")
        assertEquals(true, warning != null && warning.contains("NotEnPustTil"))
    }
}
