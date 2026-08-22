import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The briefing's auto-start fires `justStarted` on a frame with NO press, and
 * `LifecycleInputEdges.firedPadId` is reset to null at the top of EVERY frame (`begin()`).
 * So on the COMMON path — a player presses once, reads the briefing, and lets the countdown
 * run — `activePadId` would be null, and `selectGameplayPad` would fall through to
 * `padIds.firstOrNull()`, i.e. slot 0.
 *
 * That is verbatim the booth failure `GamepadScan`'s class doc exists to record: with a stray
 * HID in slot 0, "the cabinet's own START press worked and the diver did not move. A
 * startable, unplayable run, silently repeating for every person in the queue."
 *
 * Two things keep it fixed, and BOTH are wiring rather than logic:
 *   1. `activePadId` is latched on `lifecycle.justEnteredBriefing`, and
 *   2. the `justStarted` block falls back to that latch with `?: activePadId` instead of
 *      overwriting it with this frame's null.
 *
 * A runtime test cannot see either — it needs two gamepads and a five-second wait. So this is
 * a source scan, in the style of `UpdateGameOrderingTest`.
 */
class PadLatchOrderingTest
{
    private val body: String
    init
    {
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val afterSignature = source.substringAfter("private fun updateGame()")
        body = afterSignature.substring(0, afterSignature.indexOf("\n    override fun onDestroy"))
    }

    @Test
    fun `the pad that opened the briefing is latched before the run can start`()
    {
        val latchIndex = body.indexOf("if (lifecycle.justEnteredBriefing) activePadId = lifecycleEdges.firedPadId")
        val startedIndex = body.indexOf("if (lifecycle.justStarted)")

        assertTrue(
            latchIndex >= 0,
            "updateGame no longer latches activePadId on lifecycle.justEnteredBriefing. The " +
            "briefing's countdown fires justStarted on a frame with no press, and firedPadId " +
            "is nulled every frame — without this latch the run binds to gamepad slot 0. See " +
            "GamepadScan's \"startable, unplayable run\"."
        )
        assertTrue(startedIndex >= 0, "updateGame no longer checks lifecycle.justStarted — re-read this test")
        assertTrue(
            latchIndex < startedIndex,
            "the justEnteredBriefing latch is at offset $latchIndex, AFTER the justStarted " +
            "block at $startedIndex. The latch must be established before the block that reads it."
        )
    }

    @Test
    fun `the run start falls back to the latched pad instead of overwriting it with null`()
    {
        assertTrue(
            body.contains("activePadId = lifecycleEdges.firedPadId ?: activePadId"),
            "the justStarted block assigns activePadId without the `?: activePadId` fallback. " +
            "On the briefing's auto-start frame no edge fired, so firedPadId is null and this " +
            "would discard the pad latched when the briefing opened — binding gameplay to " +
            "slot 0 for every player who lets the countdown run."
        )
    }
}
