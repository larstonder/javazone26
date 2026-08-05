package render

import dive.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The zone-band gradient is `DiveRenderer`'s half of item 1 in the lighting rework
 * (`DiveLighting`'s ambient/intensity curves are the other half, tested in
 * `DiveLightingTest`/`DepthBlendTest`). What's pure here — the per-channel colour curve fed
 * into the strip-drawing loop — is exposed internally for exactly this reason.
 */
class DiveRendererTest
{
    @Test
    fun `zone band colour has no step at a zone boundary`()
    {
        val zones = Zone.entries
        for (i in 1 until zones.size)
        {
            val boundary = zones[i].minDepth
            val aboveR = DiveRenderer.zoneRedAt(boundary - 0.01f)
            val belowR = DiveRenderer.zoneRedAt(boundary + 0.01f)
            assertTrue(
                kotlin.math.abs(aboveR - belowR) < 0.01f,
                "red channel jumped at the ${zones[i]} boundary ($boundary m): $aboveR -> $belowR"
            )

            val aboveG = DiveRenderer.zoneGreenAt(boundary - 0.01f)
            val belowG = DiveRenderer.zoneGreenAt(boundary + 0.01f)
            assertTrue(
                kotlin.math.abs(aboveG - belowG) < 0.01f,
                "green channel jumped at the ${zones[i]} boundary ($boundary m): $aboveG -> $belowG"
            )

            val aboveB = DiveRenderer.zoneBlueAt(boundary - 0.01f)
            val belowB = DiveRenderer.zoneBlueAt(boundary + 0.01f)
            assertTrue(
                kotlin.math.abs(aboveB - belowB) < 0.01f,
                "blue channel jumped at the ${zones[i]} boundary ($boundary m): $aboveB -> $belowB"
            )
        }
    }

    @Test
    fun `every zone still reads as its own colour at its own midpoint`()
    {
        // "Readable as distinct depths, just not hard-edged" — the shallows must not have
        // drifted toward the abyss's near-black by the time you reach the shallows' own
        // characteristic depth.
        //
        // MEASURED AS RELATIVE LUMINANCE, not as a raw r+g+b sum, which this assertion used
        // before the reflectance floor existed. The floor (see DiveRenderer.GI_REFLECTANCE_FLOOR)
        // means no albedo may go below a fixed linear length, so the abyss's raw channel sum
        // is now pinned at roughly 0.18 whatever the zone table says and a sum-based ratio can
        // no longer express "reads far darker" at all. Luminance can, and is what "reads
        // darker" means anyway: the floor is deliberately paid for in blue, which the eye
        // weights at 0.0722 against green's 0.7152, so the abyss still measures 30x darker.
        val shallowsMid = DepthBlend.zoneMidpoint(Zone.SHALLOWS)
        val abyssMid = DepthBlend.zoneMidpoint(Zone.ABYSS)

        val shallowsBrightness = luminanceAt(shallowsMid)
        val abyssBrightness = luminanceAt(abyssMid)

        assertTrue(shallowsBrightness > abyssBrightness * 10f, "shallows ($shallowsBrightness) should read far brighter than the abyss ($abyssBrightness)")
    }

    private fun luminanceAt(depth: Float): Float =
        0.2126f * DiveRenderer.srgbToLinear(DiveRenderer.zoneRedAt(depth)) +
        0.7152f * DiveRenderer.srgbToLinear(DiveRenderer.zoneGreenAt(depth)) +
        0.0722f * DiveRenderer.srgbToLinear(DiveRenderer.zoneBlueAt(depth))

    @Test
    fun `depth colour gets darker (not brighter) as depth increases, overall`()
    {
        var previousBrightness = Float.POSITIVE_INFINITY
        var depth = 0f
        while (depth <= 160f)
        {
            val brightness = DiveRenderer.zoneRedAt(depth) + DiveRenderer.zoneGreenAt(depth) + DiveRenderer.zoneBlueAt(depth)
            assertTrue(brightness <= previousBrightness + 0.0001f, "brightness increased with depth at ${depth}m: $previousBrightness -> $brightness")
            previousBrightness = brightness
            depth += 1f
        }
    }

    /**
     * THE SEAM REGRESSION. `GlobalIlluminationSystem`'s blend discards any albedo whose linear
     * RGB length falls under `minReflectance` and substitutes flat grey — see
     * [DiveRenderer.GI_REFLECTANCE_FLOOR] for the full mechanism and the measurement. A single
     * strip falling through is enough to draw a hairline across the whole play column, so this
     * sweeps the entire reachable depth range at a quarter of a strip's height.
     *
     * Deliberately checks the QUANTIZED colour: `Surface.setDrawColor` truncates each channel
     * to 8 bits after this code has run (`(c * 255).toInt()`), and that truncation is what
     * turned a smooth curve into a hard step in the first place. Testing the un-quantized
     * float would pass while the shipped frame still seams.
     */
    @Test
    fun `every zone band colour clears the GI reflectance floor once quantized`()
    {
        var depth = 0f
        while (depth <= 200f)
        {
            val length = DiveRenderer.reflectanceLength(
                quantize(DiveRenderer.zoneRedAt(depth)),
                quantize(DiveRenderer.zoneGreenAt(depth)),
                quantize(DiveRenderer.zoneBlueAt(depth))
            )
            assertTrue(
                length >= DiveRenderer.GI_REFLECTANCE_FLOOR,
                "band colour at ${depth}m has linear length $length, under the GI reflectance " +
                "floor of ${DiveRenderer.GI_REFLECTANCE_FLOOR} — GI will replace it with flat grey"
            )
            depth += 0.125f
        }
    }

    /**
     * The walls go onto the same GI-lit surface as the water, so they are subject to the same
     * floor — and being warm near-black is exactly the shape of colour that falls through it.
     * Below the floor their hue is thrown away and they become the black bars that read as
     * letterboxing rather than as rock.
     */
    @Test
    fun `the column walls clear the GI reflectance floor once quantized`()
    {
        for ((name, color) in listOf("wall body" to DiveRenderer.wallColor, "wall inner face" to DiveRenderer.wallEdgeColor))
        {
            val length = DiveRenderer.reflectanceLength(quantize(color.red), quantize(color.green), quantize(color.blue))
            assertTrue(
                length >= DiveRenderer.GI_REFLECTANCE_FLOOR,
                "$name has linear length $length, under the GI reflectance floor of ${DiveRenderer.GI_REFLECTANCE_FLOOR}"
            )
        }
    }

    /**
     * The floor may only ever RAISE the blue channel, never lower it — a floor that also
     * capped would repaint the whole gradient onto one value and lose the depth cue the bands
     * exist for.
     *
     * Sampled at 90 m ON PURPOSE. The shallows would prove nothing: there, red and green alone
     * already exceed the floor, so the lift short-circuits before it ever compares anything.
     * 90 m is inside the band where the floor is genuinely in play (red and green have fallen
     * far enough that blue is being asked to carry it) but where the curve's own blue still
     * wins — the only place the "raise, never lower" comparison is actually exercised.
     */
    @Test
    fun `the reflectance floor leaves colours that already clear it untouched`()
    {
        val justAboveTheFloor = 90f
        assertEquals(
            DepthBlend.blend(justAboveTheFloor, floatArrayOf(0.52f, 0.36f, 0.22f, 0.12f, 0.035f)),
            DiveRenderer.zoneBlueAt(justAboveTheFloor),
            0f,
            "90m already clears the floor and must be passed through unchanged"
        )
    }

    /** The engine truncates rather than rounds — see `SurfaceConfigInternal.setDrawColor`. */
    private fun quantize(channel: Float): Float = (channel.coerceIn(0f, 1f) * 255f).toInt() / 255f
}
