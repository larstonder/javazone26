package render

import booth.CallbackSites
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoothStatusTest
{
    // A healthy call, repeated by every test below so only the ONE field under test differs
    // from the "everything is fine" case.
    private fun healthy(
        seed: Long = 20260903L,
        unmappedPads: Int = 0,
        stuckSources: Int = 0,
        chatterSources: Int = 0,
        callbackFailures: Int = 0,
        lastFailureSite: String? = null,
        bootFailed: Boolean = false
    ) = BoothStatus.line(seed, unmappedPads, stuckSources, chatterSources, callbackFailures, lastFailureSite, bootFailed)

    @Test
    fun `a healthy cabinet shows only the active seed`()
    {
        // Day two's whole verification is "did the number on screen change". Anything else
        // on this line while healthy would train an attendant to ignore it.
        val line = healthy(seed = 20260903L)

        assertEquals("SEED 20260903", line)
    }

    @Test
    fun `an unmapped encoder is named, because the game otherwise looks perfect`()
    {
        val line = healthy(unmappedPads = 1)

        assertTrue(line.contains("1 UNMAPPED"), "got: $line")
    }

    @Test
    fun `stuck sources are counted`()
    {
        val line = healthy(stuckSources = 2)

        assertTrue(line.contains("2 STUCK"), "got: $line")
    }

    @Test
    fun `chattering sources are counted`()
    {
        // Task 4 grew chatterCount alongside stuckCount: a chattering encoder button burns
        // all three InitialsEntry slots in a fraction of a second and auto-submits "AAA",
        // destroying a player's leaderboard entry, and restarts the cabinet before anyone
        // reads their score. It has to be visible or the booth log is the only record of it.
        val line = healthy(chatterSources = 3)

        assertTrue(line.contains("3 CHATTERING"), "got: $line")
    }

    @Test
    fun `a stuck source and a chattering source read as different words, not one merged count`()
    {
        // A stuck button and a chattering one call for different physical fixes
        // (power-cycle the cabinet vs. reseat a connector), so an attendant reading this
        // line must be able to tell which repair to attempt. Merging them into one number
        // would report "something is wrong with input" and nothing more.
        val line = healthy(stuckSources = 1, chatterSources = 1)

        assertTrue(line.contains("1 STUCK"), "got: $line")
        assertTrue(line.contains("1 CHATTERING"), "got: $line")
        assertFalse(line.contains("STUCK CHATTERING"), "the two must not have merged into one phrase: $line")
        assertFalse(line.contains("CHATTERING STUCK"), "the two must not have merged into one phrase: $line")
    }

    @Test
    fun `callback failures name the site that failed`()
    {
        val line = healthy(callbackFailures = 12, lastFailureSite = "onRender")

        assertTrue(line.contains("12"), "got: $line")
        assertTrue(line.contains("onRender"), "got: $line")
    }

    @Test
    fun `a failed boot is named and reads as more severe than an ordinary callback failure`()
    {
        // EnPustTil.bootFailed means createGame threw: sim/scoreRepository are left
        // lateinit-unset, so every later frame throws and is swallowed by CallbackGuard - a
        // black-but-alive cabinet indistinguishable on screen from one that is simply off.
        // This is the worst thing the line can report, so it must be unmistakable, not just
        // another same-shaped segment among the others.
        val line = healthy(bootFailed = true)

        assertTrue(line.contains("BOOT FAILED"), "got: $line")
    }

    @Test
    fun `a healthy cabinet never mentions boot failure`()
    {
        val line = healthy()

        assertFalse(line.contains("BOOT"), "a healthy line must not hint at the worst-case wording: $line")
    }

    @Test
    fun `every fault appears at once rather than the first one winning`()
    {
        val line = BoothStatus.line(
            seed = 7L,
            unmappedPads = 1,
            stuckSources = 1,
            chatterSources = 1,
            callbackFailures = 3,
            lastFailureSite = "onUpdate",
            bootFailed = true
        )

        assertTrue(line.contains("SEED 7"), "got: $line")
        assertTrue(line.contains("BOOT FAILED"), "got: $line")
        assertTrue(line.contains("UNMAPPED"), "got: $line")
        assertTrue(line.contains("STUCK"), "got: $line")
        assertTrue(line.contains("CHATTERING"), "got: $line")
        assertTrue(line.contains("onUpdate"), "got: $line")
    }

    @Test
    fun `the worst-case line is drawable by the default font`()
    {
        // The default font can only draw U+0020..U+011F; anything above renders as NOTHING
        // AT ALL, silently - no glyph and no x-advance. A status line that vanishes is
        // worse than no status line, because it reads as "healthy". The worst case now
        // includes a failed boot, the most severe thing this line can say, on top of every
        // other counter maxed out.
        val line = BoothStatus.line(
            seed = 20260903L,
            unmappedPads = 9,
            stuckSources = 9,
            chatterSources = 9,
            callbackFailures = 99,
            lastFailureSite = "onFixedUpdate",
            bootFailed = true
        )

        line.forEach { c -> assertTrue(c.code in 0x20..0x11F, "undrawable char U+%04X in: %s".format(c.code, line)) }
    }

    @Test
    fun `every real failure site stays drawable, not just the placeholder literal above`()
    {
        // Every literal inside BoothStatus.line is plain ASCII, so lastFailureSite is the
        // only UNCONSTRAINED input - a caller-supplied String, not a compile-time literal.
        // CallbackSites now lives in booth/, alongside its only other consumer
        // (CallbackGuard), which is what lets this pure render-package test loop the REAL
        // site names (an internal declaration is visible module-wide, not just within its
        // package) instead of trusting the one placeholder literal above. AttractScreenTest
        // asserts the identical property from EnPustTil's side; kept in both places
        // deliberately, per the Task 5 review - both are cheap, and this one lives beside
        // the string it guards.
        val sites = listOf(
            CallbackSites.CREATE,
            CallbackSites.FIXED_UPDATE,
            CallbackSites.UPDATE,
            CallbackSites.RENDER,
            CallbackSites.DESTROY,
            null
        )
        for (site in sites)
        {
            val line = BoothStatus.line(
                seed = 20260903L,
                unmappedPads = 9,
                stuckSources = 9,
                chatterSources = 9,
                callbackFailures = 99,
                lastFailureSite = site,
                bootFailed = true
            )
            line.forEach { c ->
                assertTrue(
                    c.code in 0x20..0x11F,
                    "undrawable char U+%04X in (lastFailureSite=$site): %s".format(c.code, line)
                )
            }
        }
    }
}
