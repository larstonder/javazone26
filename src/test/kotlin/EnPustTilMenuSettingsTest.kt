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

    // --- The six live GI knobs (gi-knobs-brief.md) ---------------------------------------
    // "One source of truth": selecting a named preset writes all six fields at once; touching
    // any one of the six individually sets quality to CUSTOM and leaves the rest alone.

    @Test
    fun `selecting a preset via QUALITY writes all six GI knobs from that preset`() {
        // Starting from CUSTOM values that agree with none of the three presets, so a stray
        // pass-through (the field just happening to already hold the right number) cannot hide
        // a bug in the copy.
        val custom = GameSettings.DEFAULT.copy(
            quality = "CUSTOM",
            lightTexScale = 0.2f, localSceneTexScale = 0.2f, globalSceneTexScale = 0.1f,
            maxCascades = 8, bilinearFix = true, traceWorldRays = true
        )
        // custom.quality = "CUSTOM" is off GraphicsQuality's ladder, so the QUALITY step falls
        // back to DEFAULT's index (HIGH) before applying delta - stepping -1 from there lands
        // one preset down, MEDIUM, exercising a real preset that is neither the start nor the
        // fallback.
        val stepped = stepGameSettings(custom, MenuItemId.QUALITY, -1)
        val medium = GraphicsQuality.MEDIUM.settings()

        assertEquals("MEDIUM", stepped.quality)
        assertEquals(medium.lightTexScale, stepped.lightTexScale)
        assertEquals(medium.localSceneTexScale, stepped.localSceneTexScale)
        assertEquals(medium.globalSceneTexScale, stepped.globalSceneTexScale)
        assertEquals(medium.maxCascades, stepped.maxCascades)
        assertEquals(medium.bilinearFix, stepped.bilinearFix)
        assertEquals(medium.traceWorldRays, stepped.traceWorldRays)
    }

    @Test
    fun `touching a numeric GI knob sets quality to CUSTOM and leaves the other five knobs alone`() {
        val start = GameSettings.DEFAULT.copy(quality = "HIGH")
        val stepped = stepGameSettings(start, MenuItemId.LIGHT_MAP_SCALE, -1)

        assertEquals("CUSTOM", stepped.quality)
        assertEquals(GameSettings.LIGHT_TEX_SCALES[GameSettings.LIGHT_TEX_SCALES.indexOf(start.lightTexScale) - 1],
            stepped.lightTexScale)
        // The other five knobs are untouched by a LIGHT_MAP_SCALE step.
        assertEquals(start.localSceneTexScale, stepped.localSceneTexScale)
        assertEquals(start.globalSceneTexScale, stepped.globalSceneTexScale)
        assertEquals(start.maxCascades, stepped.maxCascades)
        assertEquals(start.bilinearFix, stepped.bilinearFix)
        assertEquals(start.traceWorldRays, stepped.traceWorldRays)
    }

    @Test
    fun `scene scale, global scale and max cascades each clamp at both ends of their own ladder and set CUSTOM`() {
        val atMinScene = GameSettings.DEFAULT.copy(quality = "HIGH", localSceneTexScale = GameSettings.SCENE_TEX_SCALES.first())
        val steppedMinScene = stepGameSettings(atMinScene, MenuItemId.SCENE_SCALE, -1)
        assertEquals(GameSettings.SCENE_TEX_SCALES.first(), steppedMinScene.localSceneTexScale)
        assertEquals("CUSTOM", steppedMinScene.quality)

        val atMaxGlobal = GameSettings.DEFAULT.copy(quality = "HIGH", globalSceneTexScale = GameSettings.GLOBAL_TEX_SCALES.last())
        val steppedMaxGlobal = stepGameSettings(atMaxGlobal, MenuItemId.GLOBAL_SCALE, +1)
        assertEquals(GameSettings.GLOBAL_TEX_SCALES.last(), steppedMaxGlobal.globalSceneTexScale)
        assertEquals("CUSTOM", steppedMaxGlobal.quality)

        val atMaxCascades = GameSettings.DEFAULT.copy(quality = "HIGH", maxCascades = GameSettings.MAX_CASCADES_STEPS.last())
        val steppedMaxCascades = stepGameSettings(atMaxCascades, MenuItemId.MAX_CASCADES, +1)
        assertEquals(GameSettings.MAX_CASCADES_STEPS.last(), steppedMaxCascades.maxCascades)
        assertEquals("CUSTOM", steppedMaxCascades.quality)
    }

    @Test
    fun `ray quality and off-screen rays toggle regardless of delta sign and set CUSTOM`() {
        val start = GameSettings.DEFAULT.copy(quality = "HIGH", bilinearFix = false, traceWorldRays = false)

        val rayOn = stepGameSettings(start, MenuItemId.RAY_QUALITY, +1)
        assertTrue(rayOn.bilinearFix)
        assertEquals("CUSTOM", rayOn.quality)
        val rayOnNegativeDelta = stepGameSettings(start, MenuItemId.RAY_QUALITY, -1)
        assertTrue(rayOnNegativeDelta.bilinearFix)

        val raysOn = stepGameSettings(start, MenuItemId.OFF_SCREEN_RAYS, +1)
        assertTrue(raysOn.traceWorldRays)
        assertEquals("CUSTOM", raysOn.quality)
    }

    // --- reconcileGiKnobsWithPreset: the settings.json migration bug, found by actually looking
    // at the booted game (see that function's own doc for the full empirical trace) --------

    @Test
    fun `a preset-named settings file with zeroed GI knobs — the migration defect — is repaired to the real preset numbers`() {
        // Reproduces EXACTLY what a settings.json written before this feature existed loads as
        // (see reconcileGiKnobsWithPreset's doc): quality is a real, valid preset name, but the
        // six GI fields are all at their JVM zero value rather than that preset's real numbers.
        val migrated = GameSettings.DEFAULT.copy(
            quality = "HIGH",
            lightTexScale = 0f, localSceneTexScale = 0f, globalSceneTexScale = 0f,
            maxCascades = 0, bilinearFix = false, traceWorldRays = false
        )
        val repaired = reconcileGiKnobsWithPreset(migrated)
        val high = GraphicsQuality.HIGH.settings()

        assertEquals("HIGH", repaired.quality)
        assertEquals(high.lightTexScale, repaired.lightTexScale)
        assertEquals(high.localSceneTexScale, repaired.localSceneTexScale)
        assertEquals(high.globalSceneTexScale, repaired.globalSceneTexScale)
        assertEquals(high.maxCascades, repaired.maxCascades)
        assertEquals(high.bilinearFix, repaired.bilinearFix)
        assertEquals(high.traceWorldRays, repaired.traceWorldRays)
    }

    @Test
    fun `a genuine CUSTOM settings file is left untouched`() {
        // A player who deliberately touched a knob saved quality = "CUSTOM" with the six real
        // values they chose - reconcileGiKnobsWithPreset must not overwrite those with any
        // preset's numbers, since "CUSTOM" matches no GraphicsQuality entry.
        val custom = GameSettings.DEFAULT.copy(
            quality = "CUSTOM",
            lightTexScale = 0.5f, localSceneTexScale = 0.2f, globalSceneTexScale = 0.3f,
            maxCascades = 8, bilinearFix = true, traceWorldRays = true
        )
        assertEquals(custom, reconcileGiKnobsWithPreset(custom))
    }

    @Test
    fun `reconciling an already-correct preset file changes nothing`() {
        val correct = GameSettings.DEFAULT.copy(quality = "MEDIUM").let {
            val medium = GraphicsQuality.MEDIUM.settings()
            it.copy(
                lightTexScale = medium.lightTexScale, localSceneTexScale = medium.localSceneTexScale,
                globalSceneTexScale = medium.globalSceneTexScale, maxCascades = medium.maxCascades,
                bilinearFix = medium.bilinearFix, traceWorldRays = medium.traceWorldRays
            )
        }
        assertEquals(correct, reconcileGiKnobsWithPreset(correct))
    }
}
