package render

import no.njoh.pulseengine.core.graphics.api.Multisampling
import settings.GameSettings
import java.io.File
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
    fun `GameSettings accepts exactly the quality names GraphicsQuality declares, plus CUSTOM`() {
        // GameSettings.KNOWN_QUALITIES is a hand-kept duplicate of GraphicsQuality.entries'
        // names (see that field's doc for why it cannot just import render.GraphicsQuality: it
        // would create a settings <-> render package cycle). A relationship test, not a
        // transcription of both literals side by side: this fails the instant either set gains
        // an entry the other does not, which is exactly the drift a fourth preset would cause -
        // GameSettings.clamped() would silently degrade the new preset to the default with no
        // error anywhere else in the codebase.
        //
        // UPDATED, NOT DELETED (gi-knobs-brief.md, 2026-08-31): "CUSTOM" joined
        // KNOWN_QUALITIES the moment the six GI knobs became independently touchable, but it is
        // deliberately NOT a GraphicsQuality entry (see GraphicsQuality's class doc — it names
        // a real preset with real, measured numbers, and CUSTOM names the absence of one) and
        // must never resolve to one in qualityFor (checked by the next test). So the drift
        // guard's equality now has to add CUSTOM back on the GraphicsQuality side to hold — the
        // instant a FIFTH quality-shaped string appears anywhere without a matching update on
        // the other side, this still catches it.
        val fromQualityEnum = GraphicsQuality.entries.map { it.name }.toSet() + GameSettings.CUSTOM_QUALITY
        assertEquals(fromQualityEnum, GameSettings.KNOWN_QUALITIES,
            "GameSettings.KNOWN_QUALITIES has drifted from GraphicsQuality.entries + CUSTOM - " +
            "update the hand-kept set in settings/GameSettings.kt")
    }

    @Test
    fun `qualityFor never resolves CUSTOM to a real preset`() {
        // The other half of the drift guard above: CUSTOM must degrade through qualityFor's
        // existing catch (GraphicsQuality.valueOf("CUSTOM") throws) to the compiled default,
        // exactly like any other unknown name - never silently become a real preset, which
        // would make "CUSTOM" a lie the moment it round-tripped through this function.
        assertEquals(GraphicsQuality.valueOf(GameSettings.DEFAULT.quality),
            GraphicsApplier.qualityFor(GameSettings.CUSTOM_QUALITY))
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

    // --- aoRadius must never drift from localSceneTexScale (Finding I3) -------------------

    @Test
    fun `apply writes aoRadius wherever it writes localSceneTexScale`() {
        // A SOURCE SCAN, and it replaced a test that could not fail.
        //
        // The previous version asserted `aoRadiusFor(s) / s == aoRadiusFor(sHIGH) / sHIGH` for
        // every preset. Since `aoRadiusFor(s)` IS `AO_RADIUS_METRES * s`, that substitutes to
        // `K == K` — true for any implementation, including one where `apply` never writes
        // `aoRadius` at all, which is the actual defect (finding I3). It is the same identity
        // trap `SandBankTest`'s doc warns about: `(a + b) - b == a` cannot be falsified by any
        // edit anywhere.
        //
        // What can really go wrong is a SEAM, not arithmetic: `apply` sets
        // `localSceneTexScale` and forgets `aoRadius` beside it, so the shader's
        // `radius_world = aoRadius / localSceneTexScale` silently becomes 5.33 m at MEDIUM and
        // 8 m at LOW instead of the intended 4 m. Only a live GI system could observe that at
        // runtime, so this scans the source instead — the house pattern, as in `DrawTest`,
        // `MappedPadsTest`, `MainCameraOwnershipTest` and `UpdateGameOrderingTest`.
        val source = File("src/main/kotlin/render/GraphicsApplier.kt").readText()
        assertTrue(source.contains("localSceneTexScale"),
            "GraphicsApplier no longer writes localSceneTexScale — this test needs rethinking")
        assertTrue(source.contains("aoRadius"),
            "GraphicsApplier writes localSceneTexScale but never aoRadius; the shader derives " +
            "radius_world = aoRadius / localSceneTexScale, so the AO radius drifts with quality")
    }

    @Test
    fun `aoRadiusFor scales linearly, so the world radius it encodes is quality-independent`() {
        // The arithmetic half, kept separate from the seam half above. This CAN fail: an
        // aoRadiusFor that squared, clamped or floored its argument would break the linearity
        // the shader's division depends on, and the ratio would stop being constant.
        val low = DiveLighting.aoRadiusFor(0.2f)
        val high = DiveLighting.aoRadiusFor(0.4f)
        assertEquals(2f, high / low, 0.0001f,
            "aoRadiusFor must be linear in the scale, or radius_world stops being constant")
        assertTrue(low > 0f && high > 0f, "aoRadiusFor must stay positive")
    }
}
