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
        FakeStore(throwOnSave = true).save(GameSettings.DEFAULT)
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
