package settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings file's failure contract.
 *
 * THE ONE RULE: a settings file must never be able to stop the game booting. It is written by
 * a running game that a player can kill at any moment, it lives in a directory a person can
 * open, and it is read before anything is on screen — so every failure path has to end at
 * GameSettings.DEFAULT and a WARN, never at an exception. This mirrors the scoreboard's
 * contract, which survives a corrupt file the same way.
 *
 * FINDING I6 (final review, 2026-08-30): every test below targets [FakeStore], which
 * reimplements the [SettingsStore] contract with its own try/catch and its own `.clamped()`
 * call — so these assertions were only ever proving the FAKE obeys the contract, never that
 * [EngineSettingsStore] does. `EngineSettingsStore` is constructed with a live `PulseEngine`
 * and reads `engine.data`/`engine.window`/`engine.config`, none of which exist in a headless
 * JVM without a GL context — this repo has no precedent anywhere for constructing a real
 * `PulseEngine` in a test (`render.MappedPadsTest` went the other way, splitting a
 * `StateReader` interface out specifically so the GLFW half never has to be constructed for a
 * test to run — see that class's own doc), so `EngineSettingsStore` genuinely cannot be
 * exercised here and stays untested by this file. What CAN be tested, and is the point of
 * keeping [FakeStore] rather than deleting these tests: the shared CONTRACT every
 * [SettingsStore] implementation must honour (never throw, always clamp on load, always
 * degrade to [GameSettings.DEFAULT] rather than propagate) — `EngineSettingsStore`'s own
 * `try`/`catch` blocks are a direct visual match against this fake's, which is the closest
 * this suite can get to that class without an engine.
 */
class SettingsStoreTest
{
    /** In-memory stand-in for the JSON layer, so the contract can be tested without an engine. */
    private class FakeStore(
        var stored: GameSettings? = null,
        var throwOnLoad: Boolean = false,
        var throwOnSave: Boolean = false
    ) : SettingsStore
    {
        var saveCount = 0
        var warnings = 0

        override fun load(): GameSettings =
            try {
                if (throwOnLoad) throw IllegalStateException("corrupt")
                (stored ?: GameSettings.DEFAULT).clamped()
            } catch (e: Exception) { warnings++; GameSettings.DEFAULT }

        override fun save(settings: GameSettings) {
            saveCount++
            try {
                if (throwOnSave) throw IllegalStateException("disk full")
                stored = settings
            } catch (e: Exception) { warnings++ }
        }
    }

    @Test
    fun `a missing file loads the defaults`() {
        assertEquals(GameSettings.DEFAULT, FakeStore(stored = null).load())
    }

    @Test
    fun `a corrupt file loads the defaults instead of throwing`() {
        val store = FakeStore(throwOnLoad = true)
        assertEquals(GameSettings.DEFAULT, store.load())
        assertTrue(store.warnings > 0, "a corrupt settings file should warn, not fail silently")
    }

    @Test
    fun `a failed save does not propagate`() {
        // A player quitting mid-write must not see a crash instead of the game closing.
        //
        // FINDING I6: this used to be `FakeStore(throwOnSave = true).save(GameSettings.DEFAULT)`
        // with no assertion at all — it passed iff the call did not throw, which FakeStore's
        // own try/catch guarantees unconditionally regardless of whether the catch block
        // actually did anything. Asserting on the fake's own bookkeeping (warnings/saveCount)
        // gives the catch branch something to fail: an implementation that swallowed the
        // exception WITHOUT logging, or that returned early and skipped the save attempt
        // bookkeeping entirely, now reddens this test instead of passing it by construction.
        val store = FakeStore(throwOnSave = true)
        store.save(GameSettings.DEFAULT)
        assertEquals(1, store.saveCount, "save must still be attempted exactly once")
        assertTrue(store.warnings > 0, "a failed save should warn, not fail silently")
    }

    @Test
    fun `a loaded file is clamped on the way in`() {
        val store = FakeStore(stored = GameSettings.DEFAULT.copy(renderScale = 99f))
        assertTrue(store.load().renderScale <= GameSettings.RENDER_SCALES.max(),
            "load must clamp - a hand-edited file is the normal case, not the exception")
    }

    @Test
    fun `a round trip preserves every field`() {
        val store = FakeStore()
        val settings = GameSettings.DEFAULT.copy(
            renderScale = GameSettings.RENDER_SCALES.min(),
            frameCap = 0,
            vsync = true,
            showFps = true,
            fullscreen = false
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }
}
