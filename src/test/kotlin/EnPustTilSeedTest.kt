import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [parseDailySeed] is what lets a technician change the water column between the two
 * conference days by editing one line in application.cfg, without a rebuild — see its
 * doc and EnPustTil.onCreate. Covers both ways a text file can fail to give a usable
 * value: the key absent entirely (day one, before anyone has touched the file) and the
 * key present but not a valid Long (a typo, which must fall back rather than crash the
 * booth machine).
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
}
