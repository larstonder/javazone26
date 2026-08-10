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
    private fun intensity(depth: Float) = DiveLighting.pearlIntensityForDepth(depth)

    @Test
    fun `the compensation cancels the depth dependence it is compensating for`()
    {
        // At full strength `exposure x intensity` is constant everywhere the clamp is not
        // binding. Asserted as a RATIO across depths rather than against a hardcoded product, so
        // the pearl-light table stays free to be re-tuned.
        val deep = listOf(100f, 110f, 120f, 130f, 140f, 150f, 190f)
        val products = deep.map { fullStrengthExposure(it) * intensity(it) }
        val first = products.first()

        products.zip(deep).forEach { (p, d) ->
            assertEquals(
                first, p, first * 1e-4f,
                "at $d m the compensated pearl albedo receives $p, not $first. The whole point is " +
                "that the material reads the same in the Trench as in the Abyss — a pearl light " +
                "table running 0.6 to 4.0 is a 6.7x swing in how washed out the body is"
            )
        }

        // And it really is cancelling something: the raw intensities must differ across that
        // range, or the test above holds trivially.
        assertTrue(
            intensity(150f) > intensity(100f) * 1.3f,
            "the pearl light must actually get stronger with depth for this to be compensating anything"
        )
    }

    @Test
    fun `the compensation only ever removes albedo, and never above the reference depth`()
    {
        // Clamped at 1. The Shallows and the Kelp already read (0.667 and 0.638 chroma, nothing
        // clipped) and brightening them would push them toward the very shoulder this exists to
        // get off — as well as being a change to two zones nobody complained about.
        listOf(0f, 5f, 15f, 30f, 45f, 60f, 75f).forEach {
            assertEquals(
                1f, DiveRenderer.pearlAlbedoExposure(it), 1e-5f,
                "at $it m the pearl light is at or below the reference, so the albedo must be untouched"
            )
        }

        listOf(100f, 120f, 140f, 200f).forEach {
            val e = DiveRenderer.pearlAlbedoExposure(it)
            assertTrue(e < 1f, "at $it m the albedo must be stopped down, got $e")
            assertTrue(e > 0f, "at $it m the exposure must stay positive, got $e")
        }
    }

    @Test
    fun `exposure never increases with depth, so a pearl cannot brighten as the water darkens`()
    {
        // Monotonicity is what makes the transition invisible. `DepthBlend` is smooth, so a
        // non-monotone exposure would show as a pearl that dims and then brightens again as the
        // diver descends past it — a moving artefact, and the hardest kind to attribute.
        var previous = DiveRenderer.pearlAlbedoExposure(0f)
        var depth = 0f
        while (depth <= 200f)
        {
            val e = DiveRenderer.pearlAlbedoExposure(depth)
            assertTrue(e <= previous + 1e-5f, "exposure rose from $previous to $e at $depth m")
            previous = e
            depth += 0.5f
        }
    }

    @Test
    fun `the reference is taken from the zone table rather than typed as a number`()
    {
        // The reference is "the deepest zone whose pearls were measured to keep their hue", which
        // is Twilight. Written as a lookup so re-tuning `pearlIntensityByZone` moves it too; a
        // hardcoded 1.8 would silently start pointing at an intensity no zone has any more.
        assertEquals(
            intensity(DepthBlend.zoneMidpoint(Zone.TWILIGHT)),
            DiveRenderer.PEARL_EXPOSURE_REFERENCE_INTENSITY,
            1e-5f
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
        val darkestExposure = DiveRenderer.pearlAlbedoExposure(200f)
        val base = DiveRenderer.reflectanceLength(1f, 0.78f, 0.35f)
        val darkest = IridescentMaterial.PEARL.darkestReflectanceLength(base * darkestExposure)

        assertTrue(
            darkest >= DiveRenderer.GI_REFLECTANCE_FLOOR,
            "the deepest, darkest interference band on a pearl has linear length $darkest, under " +
            "the ${DiveRenderer.GI_REFLECTANCE_FLOOR} floor — it would be replaced by flat grey"
        )
    }

    /** The compensation with the strength knob wound fully in, for the cancellation test. */
    private fun fullStrengthExposure(depth: Float) =
        (DiveRenderer.PEARL_EXPOSURE_REFERENCE_INTENSITY / intensity(depth)).coerceAtMost(1f)
}
