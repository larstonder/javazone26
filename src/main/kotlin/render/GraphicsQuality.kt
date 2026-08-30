package render

/**
 * The three-preset quality ladder the graphics-options menu selects from
 * (`docs/superpowers/plans/2026-08-30-main-menu-and-graphics-options.md`).
 *
 * `HIGH` is not a design choice made here — it is a **transcription** of the values Tasks 2-8 of
 * `docs/superpowers/specs/2026-08-30-rendering-performance-design.md` landed in
 * `DiveLighting.setup`, each measured individually and recorded in that spec's "Measured result"
 * section. Read `DiveLighting.setup`'s own comments for the evidence behind every HIGH number;
 * they are not repeated here so they cannot drift into a second, unmaintained copy. `MEDIUM` and
 * `LOW` step `lightTexScale`/`localSceneTexScale` down from HIGH's 0.4 and were NOT independently
 * measured — they exist so the ladder has three rungs, and [GraphicsQualityTest] is what keeps
 * them honest.
 *
 * WHY THIS IS NOT AS SIMPLE AS "LOWER NUMBER, LOWER COST": `lightTexScale` feeds a STEP FUNCTION
 * (`GiSizing`, read out of the engine's `lightTextureSizeFunc`/`GiRadianceCascades.applyEffect`
 * bytecode) — the light texture is rounded UP to a multiple of `2^cascadeCount`, and
 * `cascadeCount` is itself derived from the rounded diagonal, so a "smaller" scale can round UP
 * to a bigger texture than a "larger" one. The shipped 0.5 was measurably worse than the engine's
 * own 0.4 default for exactly this reason (see `DiveLighting.setup`'s comment on
 * `lightTexScale`). `GraphicsQualityTest` checks the ladder through `GiSizing`'s real formula, at
 * both framebuffers this game actually runs at, rather than trusting that a smaller float means
 * a smaller texture. **If that test ever fails after an edit here, the fix is to change the
 * preset's numbers, not the test** — the test is asserting the engine's own arithmetic, not a
 * preference.
 *
 * `bloom`, `hudMultisampling` and `traceWorldRays`/`bilinearFix` are held flat across all three
 * presets on purpose: bloom was DELETED from this branch rather than disabled (`DiveLighting`'s
 * "BLOOM REMOVED" comment — there is nothing left to toggle back on), the HUD surface's
 * multisampling was already turned off at the engine default for a reason unrelated to graphics
 * quality (axis-aligned HUD geometry has no edges MSAA helps), and `bilinearFix`/`traceWorldRays`
 * are already at their cheapest setting at HIGH — a "step down" ladder has nothing further to
 * turn off there without re-enabling something HIGH deliberately removed. `globalSceneTexScale`
 * is likewise held flat at HIGH's measured 0.15: shrinking it further per-preset was never
 * measured, and this file does not guess at a number nothing in Tasks 2-8 established.
 */
enum class GraphicsQuality
{
    LOW,
    MEDIUM,
    HIGH;

    fun settings(): GiSettings = when (this)
    {
        HIGH -> GiSettings(
            // Every value below is copied from DiveLighting.setup as it stands after Tasks 2-8 —
            // see that function's comments for the measurement behind each one.
            lightTexScale = 0.4f,
            localSceneTexScale = 0.4f,
            globalSceneTexScale = 0.15f,
            maxCascades = 6,
            bilinearFix = false,
            traceWorldRays = false,
            bloom = false,
            hudMultisampling = 0
        )
        MEDIUM -> GiSettings(
            // 0.3 vs HIGH's 0.4: confirmed by GraphicsQualityTest to round to a SMALLER light
            // texture than HIGH at both 1920x1200 and 3200x1800 (not obvious on its own — see
            // this file's class doc on the step function). Not independently measured for frame
            // time; that is the parity gap this preset ladder still owes the plan.
            lightTexScale = 0.3f,
            localSceneTexScale = 0.3f,
            globalSceneTexScale = 0.15f,
            maxCascades = 6,
            bilinearFix = false,
            traceWorldRays = false,
            bloom = false,
            hudMultisampling = 0
        )
        LOW -> GiSettings(
            // 0.2: stays at 6 cascades (the floor — see maxCascades below) at both framebuffers
            // with comfortable margin. 0.1 was tried and rejected here: it drops to 5 cascades at
            // 1920x1200, which GraphicsQualityTest's "at least six cascades" case exists to catch,
            // because 5 cascades visibly clips the torch beam (GiSizing's class doc / propagationMetres).
            lightTexScale = 0.2f,
            localSceneTexScale = 0.2f,
            globalSceneTexScale = 0.15f,
            // maxCascades is already at the floor set by TORCH_REACH_METRES at HIGH (see
            // DiveLighting.setup's comment on this constant) — there is no cheaper safe value to
            // step down to, so every preset shares it.
            maxCascades = 6,
            bilinearFix = false,
            traceWorldRays = false,
            bloom = false,
            hudMultisampling = 0
        )
    }
}

/**
 * The GI/HUD knobs one [GraphicsQuality] preset selects. Field names and types are load-bearing:
 * the menu plan's `GraphicsApplier` (`docs/superpowers/plans/2026-08-30-main-menu-and-graphics-options.md`)
 * consumes exactly these eight, by name.
 */
data class GiSettings(
    val lightTexScale: Float,
    val localSceneTexScale: Float,
    val globalSceneTexScale: Float,
    val maxCascades: Int,
    val bilinearFix: Boolean,
    val traceWorldRays: Boolean,
    val bloom: Boolean,
    val hudMultisampling: Int
)
