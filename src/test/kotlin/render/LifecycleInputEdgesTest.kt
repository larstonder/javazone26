package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifecycleInputEdgesTest
{
    private val STUCK = 2f

    private fun newEdges() = LifecycleInputEdges(stuckSeconds = STUCK)

    /** One frame in which [padId] offers START at [start] and A at [a]. */
    private fun LifecycleInputEdges.frame(dt: Float, start: Boolean, a: Boolean, padId: Int = 0): Boolean
    {
        begin(dt)
        offer(padId, START, start)
        offer(padId, A, a)
        return commit()
    }

    @Test
    fun `a press produces exactly one edge, not one per frame held`()
    {
        val edges = newEdges()

        assertFalse(edges.frame(dt = 0f, start = false, a = false), "no press, no edge")
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "the press frame fires")
        assertFalse(edges.frame(dt = 0.016f, start = true, a = false), "holding must not re-fire")
        assertFalse(edges.frame(dt = 0.016f, start = false, a = false), "release is not an edge")

        // The gap between the fired edge above and the re-press below is held open for
        // ~10 frames (~0.16s) DELIBERATELY - comfortably above MIN_EDGE_INTERVAL_SECONDS
        // (0.08s), not incidentally straddling it. A single extra release frame here used
        // to sit at 0.048s, under a since-raised debounce threshold, and made this test
        // collide with the anti-chatter guard; widen this gap again rather than shrinking
        // the constant back down if it ever collides a second time.
        repeat(8) { assertFalse(edges.frame(dt = 0.016f, start = false, a = false), "still released") }
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "a fresh press fires again")
    }

    @Test
    fun `a stuck START does not stop A on the same pad from starting a run`()
    {
        // THE BOOTH FAILURE THIS CLASS EXISTS FOR. Before per-source edges, one stuck
        // button made the whole cabinet unstartable and the attract screen looked fine.
        // The cabinet has ONE encoder, so per-pad granularity would not have helped it -
        // START and A on that single device have to fail independently.
        val edges = newEdges()

        assertFalse(edges.frame(dt = 0f, start = false, a = false))
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "the first press is a real edge")
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = false) }
        assertTrue(edges.isStuck(0, START), "held for 3.2s must be declared stuck")

        assertTrue(edges.frame(dt = 0.016f, start = true, a = true), "A must still start the run")
    }

    @Test
    fun `a stuck button on one pad does not stop a different pad from starting a run`()
    {
        val edges = newEdges()

        // Pad 0's START goes down on frame two and never comes up; pad 1 is the cabinet
        // encoder and is idle.
        edges.begin(0f); edges.offer(0, START, false); edges.offer(1, START, false); edges.commit()
        repeat(200)
        {
            edges.begin(0.016f)
            edges.offer(0, START, true)
            edges.offer(1, START, false)
            edges.commit()
        }
        assertTrue(edges.isStuck(0, START))

        edges.begin(0.016f)
        edges.offer(0, START, true)
        edges.offer(1, START, true)
        assertTrue(edges.commit(), "the working pad must still produce an edge")
        assertEquals(1, edges.firedPadId, "and the run must be attributed to the pad that fired it")
    }

    @Test
    fun `releasing a stuck button restores it to a working source`()
    {
        val edges = newEdges()
        edges.frame(dt = 0f, start = false, a = false)
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = false) }
        assertTrue(edges.isStuck(0, START))

        edges.frame(dt = 0.016f, start = false, a = false)
        assertFalse(edges.isStuck(0, START), "releasing clears the stuck flag")
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "a real press after the fault works again")
    }

    @Test
    fun `a pad that appears already holding a button produces no edge`()
    {
        // Hot-plugging a pad with a jammed button must not start a run by itself. The
        // first reading of a source establishes its level; only a CHANGE from it fires.
        val edges = newEdges()

        assertFalse(edges.frame(dt = 0f, start = true, a = false), "first sight of a held button is not a press")
        assertFalse(edges.frame(dt = 0.016f, start = true, a = false))
        edges.frame(dt = 0.016f, start = false, a = false)
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "but a genuine press afterwards does")
    }

    @Test
    fun `the keyboard is its own source and a stuck pad cannot mask it`()
    {
        val edges = newEdges()
        edges.begin(0f); edges.offer(0, START, false); edges.offerKeyboardEdge(false); edges.commit()
        repeat(200) { edges.begin(0.016f); edges.offer(0, START, true); edges.offerKeyboardEdge(false); edges.commit() }

        edges.begin(0.016f)
        edges.offer(0, START, true)
        edges.offerKeyboardEdge(true)
        assertTrue(edges.commit(), "SPACE must still start a run when a pad button is jammed")
        assertNull(edges.firedPadId, "a keyboard start is attributed to no pad")
    }

    @Test
    fun `unplugging a pad forgets its state rather than leaving it stuck forever`()
    {
        val edges = newEdges()
        edges.frame(dt = 0f, start = false, a = false)
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = false) }
        assertEquals(1, edges.stuckCount)

        // Pad 0 is gone: it is offered nothing at all this frame.
        edges.begin(0.016f)
        edges.commit()

        assertEquals(0, edges.stuckCount, "an unplugged pad must not keep reporting itself stuck")
    }

    @Test
    fun `stuckCount counts every jammed source`()
    {
        val edges = newEdges()
        edges.frame(dt = 0f, start = false, a = false)
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = true) }

        assertEquals(2, edges.stuckCount)
    }

    @Test
    fun `unplugging one pad does not desync a still-connected pad's state`()
    {
        // The mirror of "forgets its state": that test removes EVERY source at once, which
        // would still pass against a commit() that just cleared all six lists. This one
        // removes ONE of two pads and checks the survivor's identity is intact — the class
        // desyncing on a partial removal (indices shifting under a still-live pad) is
        // exactly what descending removeAt() in commit() exists to prevent.
        val edges = newEdges()

        // pad 0 (indices 0, 1: START, A) and pad 1 (index 2: START) are both present.
        edges.begin(0f)
        edges.offer(0, START, false)
        edges.offer(0, A, false)
        edges.offer(1, START, true)
        edges.commit()

        // Pad 0 unplugs (offered nothing this frame); pad 1's START is still held.
        edges.begin(0.016f)
        edges.offer(1, START, true)
        assertFalse(edges.commit(), "pad 1's already-held button is not a fresh transition")

        // Pad 1 releases and presses again — evaluated against PAD 1's own history, not a
        // slot that used to belong to pad 0.
        edges.begin(0.016f); edges.offer(1, START, false); edges.commit()
        edges.begin(0.016f)
        edges.offer(1, START, true)
        assertTrue(edges.commit(), "pad 1 must still produce a genuine edge after pad 0 is gone")
        assertEquals(1, edges.firedPadId, "attributed to pad 1, not a desynced slot")
    }

    @Test
    fun `firedPadId is null when no edge fires at all`()
    {
        // The doc on firedPadId says null covers two cases — a keyboard start, AND no
        // edge at all — but only the keyboard case had a test.
        val edges = newEdges()
        assertFalse(edges.frame(dt = 0f, start = false, a = false))
        assertNull(edges.firedPadId)
        assertFalse(edges.frame(dt = 0.016f, start = false, a = false))
        assertNull(edges.firedPadId, "no press this frame means no fired pad, not a stale one from earlier")
    }

    @Test
    fun `a pad that reappears after being unplugged is first-sight again`()
    {
        // A pad that flickers out for one enumeration frame while its button is still
        // physically held must not fire the instant it reappears — correct and
        // load-bearing (the alternative is a false start from a USB hiccup), but nothing
        // pinned it before this test.
        val edges = newEdges()
        assertFalse(edges.frame(dt = 0f, start = false, a = false))
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "genuine press")

        // Pad vanishes for one frame while still (physically) held.
        edges.begin(0.016f); edges.commit()

        // Pad reappears, still reading pressed. Must not fire — it is first-sight again.
        edges.begin(0.016f)
        edges.offer(0, START, true)
        assertFalse(edges.commit(), "reappearing already-held must not fire")
    }

    @Test
    fun `a chattering button produces far fewer edges than the frames it toggles across`()
    {
        // THE MIRROR FAILURE. A button oscillating every frame never holds long enough to
        // trip isStuck (heldSeconds resets on every released frame), so without a
        // debounce this would emit a fresh edge every other frame forever — invisible on
        // the stuck-count line, and wired to confirmPressed it would burn all three
        // initials slots and auto-submit AAA in a handful of frames.
        val edges = newEdges()
        assertFalse(edges.frame(dt = 0f, start = false, a = false))

        var edgeCount = 0
        var pressed = true
        repeat(240)
        {
            if (edges.frame(dt = 0.016f, start = pressed, a = false)) edgeCount++
            pressed = !pressed
        }

        // 240 frames toggling every frame is 120 up-transition attempts. An un-debounced
        // version fires on every one of them; this must fire on far fewer.
        assertTrue(edgeCount in 1..60, "expected far fewer than 120 edges from 120 attempts, got $edgeCount")
    }

    @Test
    fun `a legitimate press about 200ms after a previous one still fires`()
    {
        val edges = newEdges()
        assertFalse(edges.frame(dt = 0f, start = false, a = false))
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "first press fires")
        assertFalse(edges.frame(dt = 0.016f, start = false, a = false), "release, not an edge")

        // ~190ms further of release — comfortably past MIN_EDGE_INTERVAL_SECONDS (40ms)
        // and nowhere near stuckSeconds.
        repeat(12) { edges.frame(dt = 0.016f, start = false, a = false) }

        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "a genuine press ~200ms later must still fire")
    }

    @Test
    fun `the debounce is per-source, so a chattering START does not suppress a genuine A`()
    {
        val edges = newEdges()
        edges.begin(0f); edges.offer(0, START, false); edges.offer(0, A, false); edges.commit()

        // START chatters every frame; A stays low the whole time, untouched.
        var startPressed = true
        repeat(12)
        {
            edges.begin(0.016f)
            edges.offer(0, START, startPressed)
            edges.offer(0, A, false)
            edges.commit()
            startPressed = !startPressed
        }

        edges.begin(0.016f)
        edges.offer(0, START, startPressed)
        edges.offer(0, A, true)
        assertTrue(edges.commit(), "A must still fire despite START chattering on the same pad")
    }

    @Test
    fun `a chattering source counts toward chatterCount`()
    {
        val edges = newEdges()
        assertFalse(edges.frame(dt = 0f, start = false, a = false))

        var pressed = true
        repeat(20) { edges.frame(dt = 0.016f, start = pressed, a = false); pressed = !pressed }

        assertTrue(edges.chatterCount >= 1, "sustained oscillation must be counted as chattering")
    }

    @Test
    fun `a single fast double-tap is not counted as chattering`()
    {
        // THE DISTINCTION chatterCount exists to draw: a person who presses the same
        // button twice quickly produces exactly ONE blocked attempt, then stops. That
        // must read as a normal fast re-press, not a hardware fault.
        val edges = newEdges()
        assertFalse(edges.frame(dt = 0f, start = false, a = false))
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "first press fires")
        assertFalse(edges.frame(dt = 0.016f, start = false, a = false), "release")
        assertFalse(edges.frame(dt = 0.016f, start = true, a = false), "quick re-press is blocked by the debounce")

        assertEquals(0, edges.chatterCount, "one blocked attempt is not chattering - a human produces exactly this")
    }

    @Test
    fun `chatterCount clears when the source settles or is unplugged`()
    {
        // Settling: the source stops oscillating (holds one level steady) for longer than
        // CHATTER_SETTLE_SECONDS, with no unplug involved at all.
        val settled = newEdges()
        assertFalse(settled.frame(dt = 0f, start = false, a = false))
        var pressed = true
        repeat(20) { settled.frame(dt = 0.016f, start = pressed, a = false); pressed = !pressed }
        assertTrue(settled.chatterCount >= 1, "setup: must be chattering before it can settle")

        // Holds steady at the level the toggle loop left it on - no further transitions at
        // all - for well past CHATTER_SETTLE_SECONDS (0.3s).
        repeat(25) { settled.frame(dt = 0.016f, start = false, a = false) }
        assertEquals(0, settled.chatterCount, "holding steady must let the chatter flag settle")

        // Unplugging: the source disappears entirely rather than going quiet.
        val unplugged = newEdges()
        assertFalse(unplugged.frame(dt = 0f, start = false, a = false))
        pressed = true
        repeat(20) { unplugged.frame(dt = 0.016f, start = pressed, a = false); pressed = !pressed }
        assertTrue(unplugged.chatterCount >= 1, "setup: must be chattering before it can unplug")

        unplugged.begin(0.016f) // the pad is offered nothing at all this frame - gone
        unplugged.commit()
        assertEquals(0, unplugged.chatterCount, "an unplugged pad must not keep reporting itself chattering")
    }

    /**
     * THE HAZARD this guards, unchanged from the first version of this test:
     * `EnPustTil.restartButton`/`restartButtonAlt` are both read from application.cfg
     * (Task 7), and a technician who finds START unmapped could reasonably set BOTH keys
     * to the same physical button (or two aliases of it - see the `.code` fix in
     * `EnPustTil`'s offer wiring). That produces two calls to [offer] in one frame
     * carrying the IDENTICAL (padId, buttonOrdinal) source key.
     *
     * WHAT CHANGED FROM THE FIRST VERSION OF THIS TEST, AND WHY. The first version
     * asserted `isStuck` was TRUE after a double-offer sequence that should not have
     * tripped it — a characterization test of the defect: it passed by demonstrating the
     * bug, not by demonstrating correctness, so hardening [offer] against the hazard
     * (THE DUPLICATE-OFFER GUARD in [offer]'s KDoc) made this test FAIL and read as a
     * regression. This version asserts the intended behaviour instead: a duplicate offer
     * in the same frame changes NOTHING about the bookkeeping a single offer would have
     * produced.
     */
    @Test
    fun `a duplicate offer in one frame changes nothing`()
    {
        val framesElapsed = 70 // well under STUCK_SECONDS / dt at a single offer per frame

        val single = newEdges() // STUCK = 2f
        single.begin(0f); single.offer(0, START, false); single.commit()
        repeat(framesElapsed) { single.begin(0.016f); single.offer(0, START, true); single.commit() }
        assertFalse(single.isStuck(0, START), "70 * 0.016s = 1.12s, well under the 2s stuck threshold")

        val doubled = newEdges()
        doubled.begin(0f); doubled.offer(0, START, false); doubled.commit()
        var edgeCount = 0
        repeat(framesElapsed) {
            doubled.begin(0.016f)
            doubled.offer(0, START, true) // first offer this frame
            doubled.offer(0, START, true) // the exact hazard: same source, same frame, again
            if (doubled.commit()) edgeCount++
        }
        assertFalse(doubled.isStuck(0, START), "a duplicate offer must not accelerate stuck detection")
        assertEquals(1, edgeCount, "the press produces exactly one edge, not one per offer() call")
    }

    /**
     * A SOURCE-SCANNING GUARD for the call-site half of THE DUPLICATE-OFFER GUARD
     * documented in [LifecycleInputEdges.offer]'s KDoc. [offer] now defends its own
     * bookkeeping against a duplicate call (see `a duplicate offer in one frame changes
     * nothing`, above), so `EnPustTil`'s `if (restartButtonAlt.code != restartButton.code)`
     * beside the offer calls is only an OPTIMISATION now — but nothing else in this
     * codebase would notice if someone deleted that `if` and reintroduced a pointless
     * second call on every frame the two buttons collide. A line-based scan, not a real
     * lexer — same reasoning as `DrawTest`/`MainCameraOwnershipTest`'s `productionSources`.
     */
    @Test
    fun `EnPustTil still skips the second restart-button offer when the codes match`()
    {
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val guarded = Regex(
            """if\s*\(\s*restartButtonAlt\.code\s*!=\s*restartButton\.code\s*\)\s*\R\s*lifecycleEdges\.offer\(pad\.id,\s*restartButtonAlt\.code"""
        )

        assertTrue(
            guarded.containsMatchIn(source),
            "expected the restartButtonAlt offer call in EnPustTil's updateGame to be guarded by " +
            "`if (restartButtonAlt.code != restartButton.code)` immediately above it. See " +
            "LifecycleInputEdges.offer's KDoc for why LifecycleInputEdges itself now tolerates a " +
            "duplicate offer, and why the call site should still avoid making a pointless one."
        )
    }

    private companion object
    {
        // Plain ordinals rather than GamepadButton values, so this test never needs the
        // engine on its classpath - same reasoning as GamepadScan's List<Boolean>.
        const val START = 7
        const val A = 0
    }
}
