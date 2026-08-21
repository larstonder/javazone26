package booth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackGuardTest
{
    private class Recorder
    {
        val sites = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val causes = mutableListOf<Throwable>()
        fun sink(site: String, count: Int, cause: Throwable)
        {
            sites += site
            counts += count
            causes += cause
        }
    }

    @Test
    fun `a body that throws does not propagate`()
    {
        // This is the whole point: the engine's own handler opens Notepad over a fullscreen
        // cabinet and shuts the game down. Nothing may reach it.
        val guard = CallbackGuard(onFailure = { _, _, _ -> })

        guard.run("onRender") { throw IllegalStateException("boom") }

        assertEquals(1, guard.totalFailures)
    }

    @Test
    fun `a body that succeeds runs and reports nothing`()
    {
        val recorder = Recorder()
        val guard = CallbackGuard(onFailure = recorder::sink)
        var ran = false

        guard.run("onUpdate") { ran = true }

        assertTrue(ran)
        assertEquals(0, guard.totalFailures)
        assertEquals(emptyList(), recorder.sites)
        assertNull(guard.lastFailureSite)
    }

    @Test
    fun `an Error is caught too, not just an Exception`()
    {
        // The engine's own handler catches Throwable. A StackOverflowError out of a deep
        // draw call must not slip past this guard into it.
        val guard = CallbackGuard(onFailure = { _, _, _ -> })

        guard.run("onRender") { throw StackOverflowError() }

        assertEquals(1, guard.totalFailures)
    }

    @Test
    fun `repeated failures at one site are reported at most maxReportsPerSite times`()
    {
        // onRender runs at 120 Hz. A fault that repeats every frame would write two days of
        // identical stack traces into the log file Task 1 just created and bury everything
        // else in it.
        val recorder = Recorder()
        val guard = CallbackGuard(maxReportsPerSite = 3, onFailure = recorder::sink)

        repeat(100) { guard.run("onRender") { throw IllegalStateException("boom") } }

        // 3 reports, plus exactly one "suppressing further reports" notice.
        assertEquals(4, recorder.sites.size)
        assertEquals(listOf(1, 2, 3, 4), recorder.counts)
        assertEquals(100, guard.failureCount("onRender"))
        assertEquals(100, guard.totalFailures)
    }

    @Test
    fun `each site counts and reports independently`()
    {
        val recorder = Recorder()
        val guard = CallbackGuard(maxReportsPerSite = 1, onFailure = recorder::sink)

        guard.run("onRender") { throw IllegalStateException("render") }
        guard.run("onUpdate") { throw IllegalStateException("update") }
        guard.run("onRender") { throw IllegalStateException("render again") }

        assertEquals(2, guard.failureCount("onRender"))
        assertEquals(1, guard.failureCount("onUpdate"))
        assertEquals("onRender", guard.lastFailureSite)
        // onRender: report + suppression notice. onUpdate: report only.
        assertEquals(listOf("onRender", "onUpdate", "onRender"), recorder.sites)
    }

    @Test
    fun `a throwing failure sink cannot itself take the cabinet down`()
    {
        // The sink writes to a log file that may be on a full disk. Belt and braces.
        val guard = CallbackGuard(onFailure = { _, _, _ -> throw IllegalStateException("log disk full") })

        guard.run("onRender") { throw IllegalStateException("boom") }

        assertEquals(1, guard.totalFailures)
    }

    @Test
    fun `every engine callback in EnPustTil is routed through the guard`()
    {
        // A sixth callback added later without a guard reopens exactly the failure this
        // task closed, and no runtime test would catch it — the engine's handler only fires
        // on a real crash at a real booth.
        val source = java.io.File("src/main/kotlin/EnPustTil.kt").readText()
        val overrides = Regex("""override fun (on[A-Za-z]+)\(\)[^\n]*""").findAll(source).toList()

        assertTrue(overrides.size >= 5, "expected at least the five engine callbacks, found ${overrides.size}")
        overrides.forEach { m ->
            assertTrue(
                m.value.contains("guard.run("),
                "${m.groupValues[1]} does not go through CallbackGuard: ${m.value.trim()}"
            )
        }
    }
}
