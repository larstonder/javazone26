import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import render.GraphicsQuality
import render.MenuItemId
import settings.GameSettings

/**
 * [stepGameSettings] is the GRAPHICS page's whole cycler contract: one
 * [render.MenuAction.SettingChanged] -> one new [GameSettings]. Covers the two things that
 * would otherwise fail silently in the menu itself — a step walking off either end of a
 * ladder, and a step taken from a value that is not on the ladder at all (a hand-edited or
 * cross-build settings.json).
 */
class EnPustTilMenuSettingsTest
{
    @Test
    fun `quality steps up the ladder and clamps at HIGH`() {
        val lowest = GameSettings.DEFAULT.copy(quality = "LOW")
        val stepped = stepGameSettings(lowest, MenuItemId.QUALITY, +1)
        assertEquals("MEDIUM", stepped.quality)

        val highest = GameSettings.DEFAULT.copy(quality = "HIGH")
        assertEquals("HIGH", stepGameSettings(highest, MenuItemId.QUALITY, +1).quality,
            "clamps rather than wrapping past the top of the ladder")
    }

    @Test
    fun `quality steps down the ladder and clamps at LOW`() {
        val lowest = GameSettings.DEFAULT.copy(quality = "LOW")
        assertEquals("LOW", stepGameSettings(lowest, MenuItemId.QUALITY, -1).quality,
            "clamps rather than wrapping past the bottom of the ladder")
    }

    @Test
    fun `every GraphicsQuality preset is reachable by stepping quality`() {
        // Drift guard, same shape as GameSettings.KNOWN_QUALITIES' own — a fourth preset
        // added to GraphicsQuality must be reachable from the menu, not just accepted by
        // GameSettings.clamped.
        var current = GameSettings.DEFAULT.copy(quality = GraphicsQuality.entries.first().name)
        for (expected in GraphicsQuality.entries.drop(1))
        {
            current = stepGameSettings(current, MenuItemId.QUALITY, +1)
            assertEquals(expected.name, current.quality)
        }
    }

    @Test
    fun `resolution steps through the ladder by index, not by arithmetic on width or height`() {
        val first = GameSettings.RESOLUTIONS.first()
        val second = GameSettings.RESOLUTIONS[1]
        val settings = GameSettings.DEFAULT.copy(windowWidth = first.first, windowHeight = first.second)
        val stepped = stepGameSettings(settings, MenuItemId.RESOLUTION, +1)
        assertEquals(second.first, stepped.windowWidth)
        assertEquals(second.second, stepped.windowHeight)
    }

    @Test
    fun `resolution clamps at the top of the ladder rather than wrapping to the smallest`() {
        val (w, h) = GameSettings.RESOLUTIONS.last()
        val settings = GameSettings.DEFAULT.copy(windowWidth = w, windowHeight = h)
        val stepped = stepGameSettings(settings, MenuItemId.RESOLUTION, +1)
        assertEquals(w, stepped.windowWidth)
        assertEquals(h, stepped.windowHeight)
    }

    @Test
    fun `render scale clamps at both ends of its ladder`() {
        val atMin = GameSettings.DEFAULT.copy(renderScale = GameSettings.RENDER_SCALES.first())
        assertEquals(GameSettings.RENDER_SCALES.first(), stepGameSettings(atMin, MenuItemId.RENDER_SCALE, -1).renderScale)

        val atMax = GameSettings.DEFAULT.copy(renderScale = GameSettings.RENDER_SCALES.last())
        assertEquals(GameSettings.RENDER_SCALES.last(), stepGameSettings(atMax, MenuItemId.RENDER_SCALE, +1).renderScale)
    }

    @Test
    fun `frame cap clamps at both ends of its ladder`() {
        val atMin = GameSettings.DEFAULT.copy(frameCap = GameSettings.FRAME_CAPS.first())
        assertEquals(GameSettings.FRAME_CAPS.first(), stepGameSettings(atMin, MenuItemId.FRAME_CAP, -1).frameCap)

        val atMax = GameSettings.DEFAULT.copy(frameCap = GameSettings.FRAME_CAPS.last())
        assertEquals(GameSettings.FRAME_CAPS.last(), stepGameSettings(atMax, MenuItemId.FRAME_CAP, +1).frameCap)
    }

    @Test
    fun `fullscreen, vsync and show-fps toggle regardless of delta sign`() {
        val settings = GameSettings.DEFAULT.copy(fullscreen = false, vsync = false, showFps = false)

        assertTrue(stepGameSettings(settings, MenuItemId.FULLSCREEN, +1).fullscreen)
        assertTrue(stepGameSettings(settings, MenuItemId.FULLSCREEN, -1).fullscreen)
        assertTrue(stepGameSettings(settings, MenuItemId.VSYNC, +1).vsync)
        assertTrue(stepGameSettings(settings, MenuItemId.SHOW_FPS, +1).showFps)
    }

    @Test
    fun `stepping from a value that is not on the ladder falls back to the default's position`() {
        // A hand-edited or cross-build settings.json can carry a renderScale that is not one
        // of GameSettings.RENDER_SCALES at all. clamped() would fix that on load, but a step
        // taken against the RAW value (which is what the menu shows before the caller's own
        // clamped() call) must not silently indexOf(-1) and clamp to index 0, i.e. the
        // smallest option, regardless of which direction the player pressed.
        val offLadder = GameSettings.DEFAULT.copy(renderScale = 0.42f)
        val stepped = stepGameSettings(offLadder, MenuItemId.RENDER_SCALE, +1)
        val defaultIndex = GameSettings.RENDER_SCALES.indexOf(GameSettings.DEFAULT.renderScale)
        assertEquals(GameSettings.RENDER_SCALES[(defaultIndex + 1).coerceIn(0, GameSettings.RENDER_SCALES.lastIndex)], stepped.renderScale)
    }

    @Test
    fun `an action row never reaches stepGameSettings but is handled harmlessly if it does`() {
        val settings = GameSettings.DEFAULT
        assertEquals(settings, stepGameSettings(settings, MenuItemId.BACK, +1))
    }
}
