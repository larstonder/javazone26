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

    private companion object
    {
        // Plain ordinals rather than GamepadButton values, so this test never needs the
        // engine on its classpath - same reasoning as GamepadScan's List<Boolean>.
        const val START = 7
        const val A = 0
    }
}
