package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pending-resize queue.
 *
 * WHY A QUEUE AT ALL: game callbacks run on the "game" thread, not the GL/main thread -
 * gameLoopMode defaults to MULTITHREADED and nothing here overrides it. GLFW window calls must
 * happen on the main thread, and initFrame is the main-thread hook the engine already runs at
 * the top of every frame. So a menu selection records an intent, and initFrame performs it.
 *
 * The queue must COLLAPSE: holding right on the resolution row would otherwise stack a dozen
 * window resizes to be executed one per frame, and the window would visibly walk through every
 * size in the list.
 */
class ResizableWindowTest
{
    @Test
    fun `the latest request wins and earlier ones are discarded`() {
        val q = PendingWindowRequests()
        q.requestSize(1280, 720)
        q.requestSize(1920, 1080)
        assertEquals(1920 to 1080, q.takeSize())
    }

    @Test
    fun `taking a request clears it so it is not re-applied every frame`() {
        val q = PendingWindowRequests()
        q.requestSize(1600, 900)
        q.takeSize()
        assertNull(q.takeSize())
    }

    @Test
    fun `no request means nothing to do`() {
        assertNull(PendingWindowRequests().takeSize())
    }

    @Test
    fun `a swap interval request survives independently of a size request`() {
        val q = PendingWindowRequests()
        q.requestSwapInterval(1)
        q.requestSize(1600, 900)
        assertEquals(1, q.takeSwapInterval())
        assertEquals(1600 to 900, q.takeSize())
    }
}
