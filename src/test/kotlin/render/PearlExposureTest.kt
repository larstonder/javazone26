package render

import dive.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A PEARL SITS AT THE CENTRE OF ITS OWN LIGHT, and `GlobalIlluminationSystem` multiplies
 * `mainSurface` by the light map — so the brighter the zone's pearl light, the more of the
 * pearl's own albedo is pushed off the top of the ACES curve. Measured on the shipped build over
 * the brightest 0.05% of pixels in a pinned frame (the pearl cores):
 *
 *      10 m Shallows   mean RGB (182, 141,  63)   chroma 0.667    0.0% at >= 250
 *      70 m Twilight   mean RGB (234, 210,  85)   chroma 0.638    0.0%
 *     140 m Abyss      mean RGB (246, 241, 187)   chroma 0.240   27.0%
 *
 * That is the iridescent material becoming invisible on exactly the objects it is for, and
 * `DiveRenderer.pearlAlbedoExposure` is the answer: stop the albedo down by the light it is about
 * to receive. `LightEmitter`'s class doc records the emitter-shaped fix that was tried first and
 * does not work at any radius.
 *
 * WHAT IS TESTABLE HERE and what is not. Whether the pearl LOOKS right is a capture question and
 * is settled by the numbers above and by this task's contact sheet. What is arithmetic, and
 * therefore assertable without a GL context, is the property the whole idea rests on: that
 * `exposure x intensity` stops depending on depth. Get that wrong and the material reads
 * differently in the Kelp than in the Abyss, which is the failure mode the compensation exists to
 * remove and is invisible in any single screenshot.
 */
class PearlExposureTest
{
    /**
     * THREE TESTS HERE PINNED A DEPTH RAMP THAT NO LONGER EXISTS, AND THIS REPLACED THEM.
     *
     * They asserted that `exposure x intensity` is constant across depth, that the albedo is
     * stopped down below the reference depth and untouched above it, and that exposure never rises
     * with depth. Every one of them was a statement about the pearl light running 0.6 in the
     * Shallows to 4.0 in the Abyss — and the owner removed that ramp so the torch, not the pearl's
     * own glow, is how you find pearls in the deep (`DiveLighting.PEARL_INTENSITY`).
     *
     * The first of the three would have gone GREEN AND MEANINGLESS rather than red: with a flat
     * intensity, "the product is constant across depth" is a tautology. It had a guard against
     * exactly that — `intensity(150) > intensity(100) * 1.3` — which is the assertion that failed
     * and the reason the removal could not pass unnoticed. That guard is the pattern worth keeping,
     * so the replacement below states the new rule in a form that a returning ramp breaks.
     */
    @Test
    fun `the compensation is inert, because there is no longer a depth dependence to cancel`()
    {
        // Swept the full column rather than sampled: the exposure is built from a division, so a
        // reference that drifted off the emission by any amount would show up here as an exposure
        // that is not exactly 1 — and at PEARL_EXPOSURE_STRENGTH 0.5 a 10% drift is only a 5%
        // albedo change, which no capture would catch.
        var depth = 0f
        while (depth <= 200f)
        {
            assertEquals(
                1f, DiveRenderer.pearlAlbedoExposure(), 1e-6f,
                "the pearl albedo is being stopped down at $depth m, but the emission is flat — " +
                "either a depth ramp is back, or the reference no longer tracks the emission"
            )
            depth += 0.5f
        }
    }

    @Test
    fun `the reference is the emission itself rather than a number typed beside it`()
    {
        // This is what makes the compensation follow the emission instead of pointing at a value
        // nothing has any more — the property that carried it through the ramp's removal with no
        // edit to the arithmetic. A hardcoded 1.8 (the old Twilight reference) would have left
        // every pearl in the game stopped down to 0.55 of its albedo for no reason at all.
        assertEquals(
            DiveLighting.pearlIntensity(),
            DiveRenderer.PEARL_EXPOSURE_REFERENCE_INTENSITY,
            1e-6f
        )
        assertTrue(
            DiveRenderer.PEARL_EXPOSURE_REFERENCE_INTENSITY > 0f,
            "a zero reference would make the exposure zero and every pearl black"
        )
    }

    @Test
    fun `the albedo is stopped down in LINEAR space, not in the sRGB byte`()
    {
        // `setDrawColor` packs an sRGB byte which the vertex shader decodes with the ~2.4 power
        // curve, so scaling the sRGB value directly would remove `e^2.4` of the light instead of
        // `e` — at the shipped strength that is 0.45 asked for and 0.147 delivered, and wrong by
        // a different factor at every depth. What must hold is that the LINEAR value scales
        // exactly.
        listOf(0.25f, 0.35f, 0.5f, 0.671f, 0.9f).forEach { exposure ->
            listOf(1f, 0.78f, 0.35f, 0.05f).forEach { channel ->
                val out = DiveRenderer.exposed(channel, exposure)
                assertEquals(
                    DiveRenderer.srgbToLinear(channel) * exposure,
                    DiveRenderer.srgbToLinear(out),
                    1e-4f,
                    "exposing $channel by $exposure gave $out, whose linear value is not the " +
                    "linear input scaled by $exposure"
                )
            }
        }

        // An exposure of 1 must be a true no-op, or every pearl in the Shallows moves by a
        // rounding error that `setDrawColor`'s 8-bit truncation could turn into a whole step.
        assertEquals(0.78f, DiveRenderer.exposed(0.78f, 1f), 1e-4f)
    }

    @Test
    fun `even the most stopped-down pearl clears the GI reflectance floor`()
    {
        // The floor is the other end of the same multiply: an albedo shorter than
        // GI_REFLECTANCE_FLOOR in linear space is DISCARDED by `texture_multiply_blend.frag` and
        // replaced with flat grey. Stopping the albedo down moves it toward that floor, and the
        // iridescence's own darkest band moves it further, so the two have to be checked
        // TOGETHER — neither alone is the worst case.
        val darkestExposure = DiveRenderer.pearlAlbedoExposure()
        val base = DiveRenderer.reflectanceLength(1f, 0.78f, 0.35f)
        val darkest = IridescentMaterial.PEARL.darkestReflectanceLength(base * darkestExposure)

        assertTrue(
            darkest >= DiveRenderer.GI_REFLECTANCE_FLOOR,
            "the deepest, darkest interference band on a pearl has linear length $darkest, under " +
            "the ${DiveRenderer.GI_REFLECTANCE_FLOOR} floor — it would be replaced by flat grey"
        )
    }

}
