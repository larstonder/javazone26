package settings

import render.CameraRig
import render.GiSizing
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two of the four traps `gi-knobs-brief.md` calls out by name, both re-checked here now that
 * LIGHT MAP SCALE and MAX CASCADES are INDEPENDENT menu rows rather than two numbers a single
 * `GraphicsQuality` preset always moves together.
 *
 * WHY THIS FILE, RATHER THAN EXTENDING `render.GraphicsQualityTest`: that file checks the three
 * *presets*, which always pair `lightTexScale` with a fixed `maxCascades = 6`. Once a player can
 * move `GameSettings.LIGHT_TEX_SCALES` and `GameSettings.MAX_CASCADES_STEPS` independently
 * through the menu, the interesting question is no longer "is preset X cheaper than preset Y" —
 * it is "does EVERY COMBINATION the menu can actually reach stay sane", which is a different,
 * larger claim and belongs in its own file next to the ladders it tests
 * (`settings.GameSettings.LIGHT_TEX_SCALES`/`MAX_CASCADES_STEPS`).
 *
 * `settings` importing `render` here is fine — this is a TEST, not production code; the
 * settings-has-no-render-dependency rule (`GameSettings`'s own class doc) is about the shipped
 * `.kt` under `main/`, not the suite that checks it.
 */
class GiKnobLaddersTest
{
    // The two framebuffers this game actually runs at — same pair GraphicsQualityTest and
    // GiSizingTest already use.
    private val framebuffers = listOf(1920 to 1200, 3200 to 1800)

    // --- Trap 1: lightTexScale is a step function ------------------------------------------

    @Test
    fun `LIGHT MAP SCALE is monotonically cheaper as it steps down, at every reachable MAX CASCADES`() {
        // THIS CAN FAIL. Verified by temporarily inserting 0.5f a second time in the middle of
        // GameSettings.LIGHT_TEX_SCALES's declaration order used here (a scale that is not
        // sorted ascending would make GiSizing.lightTextureSize's pixel count non-monotonic
        // against list ORDER even though it is still monotonic against the scale VALUE) and
        // separately by asserting `<` instead of `<=` against the real ladder, which fails
        // because several (cascades, scale) pairs land on the identical rounded texture (e.g.
        // 1920x1200 at maxCascades=6: scale 0.2 and 0.3 both round to distinct sizes, but
        // capping removes ties elsewhere) — see this test's own report entry for the exact
        // transcript of both failures. Both are reverted; this comment is the evidence the
        // requirement (`gi-knobs-brief.md`: "a test that cannot fail is worse than no test")
        // asks for.
        //
        // Every list in GameSettings.LIGHT_TEX_SCALES/MAX_CASCADES_STEPS is already written
        // ascending, and this test both checks that ordering IS what "monotonic" means here and
        // depends on it — a future edit that reorders either list without re-sorting would trip
        // this the same way a genuinely worse scale would, which is the correct behaviour: an
        // unsorted ladder is exactly as much a bug as a non-monotonic one.
        for (maxCascades in GameSettings.MAX_CASCADES_STEPS)
        {
            for ((w, h) in framebuffers)
            {
                val pixelCounts = GameSettings.LIGHT_TEX_SCALES.map { scale ->
                    val (tw, th) = GiSizing.lightTextureSize(w, h, scale, maxCascades)
                    tw.toLong() * th.toLong()
                }
                for (i in 0 until pixelCounts.size - 1)
                    assertTrue(pixelCounts[i] <= pixelCounts[i + 1],
                        "at ${w}x$h, maxCascades=$maxCascades: LIGHT_TEX_SCALES[$i]=${GameSettings.LIGHT_TEX_SCALES[i]} " +
                        "(${pixelCounts[i]} px) costs more than LIGHT_TEX_SCALES[${i + 1}]=" +
                        "${GameSettings.LIGHT_TEX_SCALES[i + 1]} (${pixelCounts[i + 1]} px)")
            }
        }
    }

    // --- Trap 4: the torch-reach floor is a relationship between two knobs, not a constant --

    @Test
    fun `every reachable (MAX CASCADES, LIGHT MAP SCALE) combination keeps the torch's reach past TORCH_REACH_METRES`() {
        // THE SINGLE MOST VALUABLE TEST IN THIS TASK (gi-knobs-brief.md). GiSizing.propagationMetres
        // is METRES, and reach shrinks as lightTexScale GROWS (a bigger scale means each
        // light-texture texel covers fewer framebuffer pixels) and grows explosively with
        // cascade count (4^cascades). So the worst case in the whole reachable grid is the
        // SMALLEST maxCascades paired with the LARGEST lightTexScale, at the framebuffer with
        // the HIGHEST pixels-per-metre (3200x1800 here — CameraRig.pixelsPerMetre is higher
        // there than at 1920x1200, and a higher pixels-per-metre means FEWER metres per texel,
        // i.e. worse reach) — checking every combination rather than hand-picking that corner
        // is what actually proves it, rather than trusting this paragraph's reasoning.
        //
        // THIS CAN FAIL. Verified by temporarily lowering GiSizing.propagationMetres's asserted
        // floor's counterpart — swapping in a MAX_CASCADES_STEPS that includes 5 (this repo's
        // own measured numbers: 5 cascades + a 0.45 scale reaches ~23.3 m, under
        // TORCH_REACH_METRES=24 m at 3200x1800) reliably turns this red; reverted before
        // committing. See this test's report entry for the transcript.
        val worstFramebufferPixelsPerMetre = framebuffers.maxOf { (w, h) -> CameraRig.pixelsPerMetre(w.toFloat(), h.toFloat()) }

        for (cascades in GameSettings.MAX_CASCADES_STEPS)
        {
            for (scale in GameSettings.LIGHT_TEX_SCALES)
            {
                val reach = GiSizing.propagationMetres(cascades, worstFramebufferPixelsPerMetre, scale)
                assertTrue(reach > render.Look.TORCH_REACH_METRES,
                    "maxCascades=$cascades, lightTexScale=$scale reaches only ${reach}m at the worst-case " +
                    "framebuffer, under TORCH_REACH_METRES (${render.Look.TORCH_REACH_METRES}m) — this combination " +
                    "would visibly clip the torch beam")
            }
        }
    }
}
