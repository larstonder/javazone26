import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE TWO CALLS THAT MAKE THE VENTS' SHEET DO ANYTHING, AND BOTH FAIL SILENTLY IF ABSENT.
 *
 * `render/OxygenSprite.kt` is a complete, well-tested object that nothing forces anyone to call.
 * Its two lifecycle hooks are the whole of its wiring, and each has a failure mode that produces
 * no error and no log line:
 *
 *  - **`load` missing** — the sheet is never queued, `sheetsReady()` never turns true, and
 *    `DiveRenderer.drawAirPockets` draws the flat fallback square for the whole two days. That is
 *    exactly what a vent looked like before this feature existed, so nobody reviewing a screenshot
 *    has anything to notice. The one WARN `sheetsReady` eventually logs sits at WARN in a booth
 *    log nobody reads until something else has already gone wrong.
 *  - **`advanceLoop` missing** — `loopPhase` sits at 0 forever, `currentFrameFor` returns the same
 *    cell every frame, and every vent is a STILL IMAGE of a perfectly plausible plume. This is the
 *    worse of the two: the art is present, correct, and lit, and the only tell is motion that
 *    isn't there. No unit test of `OxygenSprite` can see it (its own tests drive `advanceLoop`
 *    directly), and no still capture can either.
 *
 * A source scan, in the style of `UpdateGameOrderingTest` and `EnPustTilDepthPinTest`, because
 * `EnPustTil` needs a GL context to instantiate and the thing being asserted is a wiring fact
 * about a file rather than a behaviour of a testable object.
 */
class OxygenSpriteWiringTest
{
    private val source = File(SOURCE).readText()

    @Test
    fun `the vent sheet is queued for upload in createGame`()
    {
        val body = bodyOf("private fun createGame()", "\n    override fun onFixedUpdate()")
        assertTrue(
            body.contains("OxygenSprite.load(engine)"),
            "createGame never calls OxygenSprite.load(engine), so the sheet is never queued and " +
            "OxygenSprite.sheetsReady() can never turn true. Every vent draws the flat fallback " +
            "square for the whole booth — which is what a vent looked like before the sheet " +
            "existed, so there is nothing in a screenshot to notice."
        )
    }

    @Test
    fun `the vent loop is advanced on the fixed tick, in the sprite gate`()
    {
        val body = bodyOf("private fun fixedUpdateGame()", "\n    override fun onUpdate()")

        val advance = body.indexOf("OxygenSprite.advanceLoop(")
        assertTrue(
            advance >= 0,
            "fixedUpdateGame never calls OxygenSprite.advanceLoop. OxygenSprite.loopPhase is the " +
            "ONLY thing that moves the sheet, so every vent is frozen on cell 0 — a still image " +
            "of a plausible plume, with no error, no log line and nothing a screenshot can show."
        )

        // The FIXED tick specifically, which this test can only assert by which function it found
        // the call in. onRender's clock would make the animation depend on frame rate and would
        // stop a screenshot being a pure function of the tick count — CLAUDE.md's rule, and
        // OxygenSprite.loopPhase's own doc, both of which name DiverSprite as the precedent.
        val diver = body.indexOf("DiverSprite.advanceLoop(")
        assertTrue(
            diver >= 0,
            "DiverSprite.advanceLoop is no longer in fixedUpdateGame — the two sprite loops are " +
            "meant to share one clock and one gate. Re-read this test."
        )

        // Inside `lifecycle.spriteAnimates`, the gate that is false only for PAUSED. A vent that
        // kept breathing through a pause reads as a broken pause rather than as a living world,
        // and there is no other state the two loops should disagree about: the diver animates in
        // IDLE for attract mode, and a vent is scenery that was never tied to a run at all.
        val gate = body.lastIndexOf("if (lifecycle.spriteAnimates)", advance)
        assertTrue(
            gate >= 0 && gate < advance,
            "OxygenSprite.advanceLoop is not inside the `if (lifecycle.spriteAnimates)` gate. " +
            "Ungated it keeps the plumes breathing through a pause; behind the wrong gate it " +
            "freezes the scenery whenever the simulation stops."
        )

        // Both loops on the same delta, from the same source. `engine.data.fixedDeltaTime` is the
        // engine's own fixed step; a hand-rolled 1/60 here would drift the instant the config's
        // fixed rate changed, and it would drift only in the animation.
        val call = body.substring(advance, body.indexOf(')', advance) + 1)
        assertTrue(
            call.contains("engine.data.fixedDeltaTime"),
            "OxygenSprite.advanceLoop is not driven by engine.data.fixedDeltaTime — got `$call`"
        )
    }

    /** The text of a function, from its signature up to [until]. */
    private fun bodyOf(signature: String, until: String): String
    {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "no `$signature` in $SOURCE any more — re-read this test")
        val rest = source.substring(start)
        val end = rest.indexOf(until)
        assertTrue(end > 0, "could not find `${until.trim()}` after `$signature` — re-read this test")
        return rest.substring(0, end)
    }

    private companion object
    {
        const val SOURCE = "src/main/kotlin/EnPustTil.kt"
    }
}
