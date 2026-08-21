import dive.DiveSim
import dive.Tuning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `EPT_DEPTH`, the dev-only depth pin.
 *
 * ## WHAT IS WORTH TESTING HERE, AND WHAT IS NOT
 *
 * The pin is a debug hook, so the temptation is to leave it untested. Two things make that
 * wrong. It is about to be the ONLY instrument for the deep-water lighting work
 * (`docs/superpowers/plans/2026-08-13-deep-water-lighting.md`), so a rig that lies — puts the
 * diver at 141 m and reports 140, or silently does nothing after a restart — corrupts every
 * measurement taken with it and does so invisibly. And it reaches into `dive/` through a
 * `debug*` hook, which is exactly the seam that must not start carrying rules.
 *
 * What is NOT tested: that the camera follows, that the frame looks right. Neither is
 * reachable without a GL context, and the second is the thing the pin exists to let a HUMAN
 * check.
 */
class EnPustTilDepthPinTest
{
    @Test
    fun `an absent or unusable pin is inert`()
    {
        // A booth machine has none of these set, and a typo on a dev machine must be inert
        // rather than a crash or a silent zero — a pin that quietly means "the surface" would
        // be indistinguishable from the feature not working.
        assertNull(parseDepthPin(null, Tuning.MAX_DEPTH), "unset")
        assertNull(parseDepthPin("", Tuning.MAX_DEPTH), "empty")
        assertNull(parseDepthPin("   ", Tuning.MAX_DEPTH), "blank")
        assertNull(parseDepthPin("deep", Tuning.MAX_DEPTH), "not a number")
        assertNull(parseDepthPin("14o", Tuning.MAX_DEPTH), "typo'd digit")
        assertNull(parseDepthPin("NaN", Tuning.MAX_DEPTH), "NaN parses as a Float and must not survive")
        assertNull(parseDepthPin("Infinity", Tuning.MAX_DEPTH), "infinite depth is not a depth")
    }

    @Test
    fun `a usable pin is the depth asked for`()
    {
        assertEquals(140f, parseDepthPin("140", Tuning.MAX_DEPTH))
        assertEquals(140f, parseDepthPin(" 140 ", Tuning.MAX_DEPTH), "surrounding whitespace")
        assertEquals(85.5f, parseDepthPin("85.5", Tuning.MAX_DEPTH))
        assertEquals(0f, parseDepthPin("0", Tuning.MAX_DEPTH), "the surface is a legitimate pin")
    }

    @Test
    fun `a pin outside the column is clamped into it rather than rejected`()
    {
        // "200" and "160" are obviously asking for the same thing — the bottom — and rejecting
        // the first would be a rig that silently does nothing at the moment it is most wanted.
        // Clamping DOWN also matters for the measurement: a camera below the column would be
        // looking at water the game cannot otherwise reach, i.e. photographing nothing.
        assertEquals(Tuning.MAX_DEPTH, parseDepthPin("200", Tuning.MAX_DEPTH))
        assertEquals(Tuning.MAX_DEPTH, parseDepthPin("99999", Tuning.MAX_DEPTH))
        assertEquals(0f, parseDepthPin("-40", Tuning.MAX_DEPTH), "above the waterline clamps to it")
    }

    @Test
    fun `the pin moves the diver without touching the rules`()
    {
        // The hook the pin uses, exercised the way the pin uses it. What must hold is that a
        // pinned diver is a NORMAL diver who happens to be deep: air, clock and held pearls are
        // whatever a fresh run has, so a rig capture measures the game and not a special mode.
        val fresh = DiveSim(seed = 1L)
        val pinned = DiveSim(seed = 1L)
        pinned.debugSetDepth(140f)

        assertEquals(140f, pinned.depth)
        assertEquals(fresh.air, pinned.air, "the pin burned air")
        assertEquals(fresh.clock, pinned.clock, "the pin moved the clock")
        assertEquals(fresh.held, pinned.held, "the pin changed the haul")
        assertEquals(fresh.x, pinned.x, "the pin moved the diver sideways, changing what he is next to")
    }

    @Test
    fun `every sim construction applies the pin`()
    {
        // THE REGRESSION THIS EXISTS FOR. There are three `DiveSim(` sites in EnPustTil — onCreate,
        // the justStarted branch, and the justReturnedToIdle branch added by task 9 (see
        // `.superpowers/sdd/2026-08-21-booth-survival/task-9-report.md`) — and a pin applied at
        // only some of them produces a rig that works on the attract screen and silently stops
        // working the moment anyone presses start, or the moment a run times out back to it.
        // That is the kind of fault that is discovered halfway through a measurement session.
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val constructions = Regex("""sim = DiveSim\(""").findAll(source).toList()

        assertTrue(constructions.isNotEmpty(), "no DiveSim construction found — re-read this test")
        constructions.forEach { match ->
            val after = source.substring(match.range.last, minOf(source.length, match.range.last + 600))
            assertTrue(
                "applyDepthPin()" in after,
                "a DiveSim is constructed at offset ${match.range.first} without a following " +
                    "applyDepthPin() — EPT_DEPTH would silently stop working from that path"
            )
        }
    }

    @Test
    fun `the pin is applied before the camera is snapped to the diver`()
    {
        // snapTo TELEPORTS the camera to sim.depth. Applied after it, the pin would leave the
        // camera at the surface easing 140 m down through the first second of the run — which
        // does not look like a bug, it looks like a slow start, so nothing would flag it.
        //
        // This used to narrow to only the `justStarted` block (substringAfter/substringBefore
        // around its own `resetAim()` call), which left the `justReturnedToIdle` block added by
        // task 9 completely unguarded by this test — correct today only because it happens to
        // copy the same order, not because anything enforced it. Widened to check EVERY
        // `sim = DiveSim(` site the same way `every sim construction applies the pin` does:
        // for each one, look at the text up to the NEXT construction (or end of file) and
        // require applyDepthPin() to precede camera.snapTo( within that window.
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val constructions = Regex("""sim = DiveSim\(""").findAll(source).map { it.range.first }.toList()
        assertTrue(constructions.isNotEmpty(), "no DiveSim construction found — re-read this test")

        constructions.forEachIndexed { i, start ->
            val end = constructions.getOrElse(i + 1) { source.length }
            val window = source.substring(start, end)

            val pin = window.indexOf("applyDepthPin()")
            val snap = window.indexOf("camera.snapTo(")
            // Not every construction site snaps the camera on the same statement (onCreate's
            // does, much further down, inside createGame's broader setup) — only assert the
            // ordering where this window actually contains both.
            if (pin >= 0 && snap >= 0)
                assertTrue(
                    pin < snap,
                    "at DiveSim construction offset $start, the depth pin is applied after " +
                    "camera.snapTo — a pinned run/attract-mode diver would open with the camera " +
                    "140 m adrift"
                )
        }
    }

    @Test
    fun `the pin is read from the environment once, and announced`()
    {
        val source = File("src/main/kotlin/EnPustTil.kt").readText()

        assertEquals("EPT_DEPTH", DEPTH_PIN_ENV, "the pin's variable was renamed; HARNESS notes and this test say EPT_DEPTH")
        assertTrue(
            "System.getenv(DEPTH_PIN_ENV)" in source,
            "the pin is no longer read from $DEPTH_PIN_ENV"
        )
        assertEquals(1, Regex("""System\.getenv\(DEPTH_PIN_ENV\)""").findAll(source).count(),
            "the pin is read from the environment more than once; it is resolved in onCreate and stored")
        // Announced at WARN, which survives the booth's default log level (application.cfg sets
        // WARN). A pinned build is not a playable one and must never be mistaken for one.
        assertTrue(
            "Logger.warn" in source.substringAfter("parseDepthPin(System.getenv(DEPTH_PIN_ENV)").take(300),
            "a pinned build no longer says so at WARN — it would be indistinguishable from a broken game"
        )
    }
}
