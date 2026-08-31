package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // --- ResizableWindow.initFrame's ordering (Finding I7) --------------------------------

    @Test
    fun `initFrame calls super BEFORE draining the pending-request queue`() {
        // Source scan, in the house pattern (MainCameraOwnershipTest / DrawTest /
        // MappedPadsTest / UpdateGameOrderingTest) — asserting this against real behaviour
        // would need a live GLFW window, which this suite deliberately never constructs (see
        // this file's class doc). The ordering broke TWICE (95bd110 fixed a version that
        // drained the queue first, applying a resize/swap-interval to a window handle
        // `updateScreenMode` was about to throw away or reset), so it is pinned here rather
        // than trusted to the inline comment alone.
        val source = File("src/main/kotlin/render/ResizableWindow.kt").readText()
        val body = source.substringAfter("override fun initFrame(engineInternal: PulseEngineInternal)")

        val superIndex = body.indexOf("super.initFrame(")
        val takeIndex = body.indexOf("pending.take")

        assertTrue(superIndex >= 0, "initFrame no longer calls super.initFrame( - re-read this test")
        assertTrue(takeIndex >= 0, "initFrame no longer drains pending.take... - re-read this test")
        assertTrue(
            superIndex < takeIndex,
            "pending.take...  is drained at offset $takeIndex, BEFORE super.initFrame( at offset " +
            "$superIndex. WindowImpl.initFrame drains the engine's own onInitFrame list, where " +
            "updateScreenMode parks a window recreation (a new GLFW handle, and a re-run of " +
            "glfwSwapInterval(0)) - draining our queue first applies a resize/swap-interval to a " +
            "handle that recreation is about to throw away or reset, silently. See 95bd110 and " +
            "this file's class doc."
        )
    }
}
