package render

import dive.Zone
import kotlin.test.Test
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
        val shallowsMid = DepthBlend.zoneMidpoint(Zone.SHALLOWS)
        val abyssMid = DepthBlend.zoneMidpoint(Zone.ABYSS)

        val shallowsBrightness = DiveRenderer.zoneRedAt(shallowsMid) + DiveRenderer.zoneGreenAt(shallowsMid) + DiveRenderer.zoneBlueAt(shallowsMid)
        val abyssBrightness = DiveRenderer.zoneRedAt(abyssMid) + DiveRenderer.zoneGreenAt(abyssMid) + DiveRenderer.zoneBlueAt(abyssMid)

        assertTrue(shallowsBrightness > abyssBrightness * 10f, "shallows ($shallowsBrightness) should read far brighter than the abyss ($abyssBrightness)")
    }

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
}
