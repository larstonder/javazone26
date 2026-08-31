package render

import no.njoh.pulseengine.core.graphics.api.Multisampling
import settings.GameSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mapping from a player's chosen settings to engine state.
 *
 * WHY THE DECISIONS ARE A SEPARATE, PURE FUNCTION: applying them needs a live PulseEngine and a
 * GL context, so a test of `apply` would need the whole engine booted. What can go wrong here is
 * not the calls - it is choosing the wrong values - so the choice is extracted and asserted, and
 * `apply` becomes a thin, obvious forwarding layer.
 */
class GraphicsApplierTest
{
    @Test
    fun `an unknown quality name resolves to the default preset rather than throwing`() {
        assertEquals(GraphicsQuality.valueOf(GameSettings.DEFAULT.quality),
            GraphicsApplier.qualityFor("NOT_A_REAL_PRESET"))
    }

    @Test
    fun `every quality name in the settings list resolves`() {
        for (q in GraphicsQuality.entries)
            assertEquals(q, GraphicsApplier.qualityFor(q.name))
    }

    @Test
    fun `vsync and a frame cap are never both active`() {
        // FpsLimiter and the swap interval are two limiters; leaving both on makes them fight,
        // and the resulting frame pacing is worse than either alone.
        val resolved = GraphicsApplier.frameCapFor(
            GameSettings.DEFAULT.copy(vsync = true, frameCap = 60))
        assertEquals(0, resolved, "with vsync on, the fps limiter must be disabled")
    }

    @Test
    fun `with vsync off the chosen frame cap is passed through`() {
        assertEquals(60, GraphicsApplier.frameCapFor(
            GameSettings.DEFAULT.copy(vsync = false, frameCap = 60)))
    }

    @Test
    fun `no preset the menu can select drops below six cascades at either shipped resolution`() {
        // The torch-clipping floor. Re-asserted here as well as in GraphicsQualityTest because
        // this is the layer that decides which preset a player can actually reach.
        for (q in GraphicsQuality.entries)
            for ((w, h) in listOf(1920 to 1200, 3200 to 1800)) {
                val s = q.settings()
                assertTrue(GiSizing.cascadeCount(w, h, s.lightTexScale, s.maxCascades) >= 6,
                    "$q at ${w}x$h would clip the torch beam")
            }
    }

    // --- GameSettings <-> GraphicsQuality drift guard ------------------------------------

    @Test
    fun `GameSettings accepts exactly the quality names GraphicsQuality declares`() {
        // GameSettings.KNOWN_QUALITIES is a hand-kept duplicate of GraphicsQuality.entries'
        // names (see that field's doc for why it cannot just import render.GraphicsQuality: it
        // would create a settings <-> render package cycle). A relationship test, not a
        // transcription of both literals side by side: this fails the instant either set gains
        // an entry the other does not, which is exactly the drift a fourth preset would cause -
        // GameSettings.clamped() would silently degrade the new preset to the default with no
        // error anywhere else in the codebase.
        val fromQualityEnum = GraphicsQuality.entries.map { it.name }.toSet()
        assertEquals(fromQualityEnum, GameSettings.KNOWN_QUALITIES,
            "GameSettings.KNOWN_QUALITIES has drifted from GraphicsQuality.entries - " +
            "update the hand-kept set in settings/GameSettings.kt")
    }

    // --- HUD multisampling: GiSettings.hudMultisampling (Int) -> Multisampling (enum) ------

    @Test
    fun `every value a GiSettings preset can carry maps to a real Multisampling constant`() {
        for (q in GraphicsQuality.entries)
            assertEquals(
                q.settings().hudMultisampling,
                GraphicsApplier.multisamplingFor(q.settings().hudMultisampling).samples,
                "$q's hudMultisampling does not round-trip through Multisampling"
            )
    }

    @Test
    fun `multisamplingFor maps every named engine constant`() {
        assertEquals(Multisampling.NONE, GraphicsApplier.multisamplingFor(0))
        assertEquals(Multisampling.MSAA4, GraphicsApplier.multisamplingFor(4))
        assertEquals(Multisampling.MSAA8, GraphicsApplier.multisamplingFor(8))
        assertEquals(Multisampling.MSAA16, GraphicsApplier.multisamplingFor(16))
        assertEquals(Multisampling.MSAA32, GraphicsApplier.multisamplingFor(32))
        assertEquals(Multisampling.MSAA_MAX, GraphicsApplier.multisamplingFor(-1))
    }

    @Test
    fun `an unmapped sample count degrades to NONE rather than throwing`() {
        // Same "degrade, do not throw" contract as qualityFor - a malformed or future settings
        // file must never crash the boot over one bad field.
        assertEquals(Multisampling.NONE, GraphicsApplier.multisamplingFor(3))
        assertEquals(Multisampling.NONE, GraphicsApplier.multisamplingFor(-99))
    }
}
