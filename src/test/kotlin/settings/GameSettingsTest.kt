package settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings record.
 *
 * WHY CLAMPING LIVES HERE AND NOWHERE ELSE: this value is read from a JSON file on disk that a
 * player can edit, that a partial write can truncate, and that an older build may have written
 * with a different set of fields. Every consumer — GraphicsApplier above all — is entitled to
 * assume a GameSettings instance is already valid, so there is exactly one place that has to
 * defend against nonsense, and it is here.
 */
class GameSettingsTest
{
    @Test
    fun `defaults are fullscreen, because fullscreen measured 1_74x faster than windowed`() {
        // Not a cosmetic default: fullscreen at 1920x1200 beat windowed at 2048x1152 - the same
        // pixel count - by 1.74x, most likely macOS compositor bypass. It is also the shipping
        // application.cfg's screenMode.
        assertTrue(GameSettings.DEFAULT.fullscreen)
    }

    @Test
    fun `render scale clamps into its declared range`() {
        assertEquals(GameSettings.RENDER_SCALES.min(),
            GameSettings.DEFAULT.copy(renderScale = 0.01f).clamped().renderScale)
        assertEquals(GameSettings.RENDER_SCALES.max(),
            GameSettings.DEFAULT.copy(renderScale = 9f).clamped().renderScale)
    }

    @Test
    fun `a negative or absurd window size clamps to a listed resolution`() {
        val fixed = GameSettings.DEFAULT.copy(windowWidth = -1, windowHeight = 0).clamped()
        assertTrue(GameSettings.RESOLUTIONS.contains(fixed.windowWidth to fixed.windowHeight),
            "clamped window size ${fixed.windowWidth}x${fixed.windowHeight} is not a listed resolution")
    }

    @Test
    fun `frame cap clamps to a listed value and zero survives as uncapped`() {
        assertTrue(GameSettings.FRAME_CAPS.contains(
            GameSettings.DEFAULT.copy(frameCap = 7).clamped().frameCap))
        // 0 means "no limiter" - FpsLimiter.sync returns immediately when fps <= 0 - and must
        // not be clamped away to a real cap.
        assertTrue(GameSettings.FRAME_CAPS.contains(0))
        assertEquals(0, GameSettings.DEFAULT.copy(frameCap = 0).clamped().frameCap)
    }

    @Test
    fun `an unknown quality name falls back to the default rather than throwing`() {
        // An older or newer build may have written a name this one does not know.
        val fixed = GameSettings.DEFAULT.copy(quality = "ULTRA_NIGHTMARE").clamped()
        assertEquals(GameSettings.DEFAULT.quality, fixed.quality)
    }

    @Test
    fun `clamping an already valid value changes nothing`() {
        val valid = GameSettings.DEFAULT
        assertEquals(valid, valid.clamped())
    }

    @Test
    fun `clamping is idempotent`() {
        val once = GameSettings.DEFAULT.copy(renderScale = 99f, frameCap = -5).clamped()
        assertEquals(once, once.clamped())
    }

    @Test
    fun `NaN render scale does not survive clamping`() {
        // A truncated JSON write can produce NaN, and NaN fails every comparison silently -
        // it would sail through a naive coerceIn and then blow up setTextureScale.
        val fixed = GameSettings.DEFAULT.copy(renderScale = Float.NaN).clamped()
        assertTrue(fixed.renderScale.isFinite(), "NaN render scale survived clamping")
    }
}
