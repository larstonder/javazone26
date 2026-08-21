package render

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

    private companion object
    {
        // Plain ordinals rather than GamepadButton values, so this test never needs the
        // engine on its classpath - same reasoning as GamepadScan's List<Boolean>.
        const val START = 7
        const val A = 0
    }
}
