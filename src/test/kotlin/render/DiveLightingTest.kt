package render

import dive.Zone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DiveLighting` is mostly unmockable — `render()` drives a live `GlobalIlluminationSystem`
 * and issues immediate-mode `drawLight` calls through a real GL context. What IS pure — the
 * continuous pearl/diver light-intensity curves and the on-screen culling check — is
 * extracted and tested here. The zone-boundary continuity itself is `DepthBlend`'s
 * responsibility and is covered in `DepthBlendTest`; this file checks the concrete tables
 * `DiveLighting` feeds into it.
 */
class DiveLightingTest
{
    @Test
    fun `pearls shine harder the deeper the zone, so they stay legible against the darker ambient`()
    {
        val intensities = Zone.entries.map { DiveLighting.pearlIntensityForDepth(DepthBlend.zoneMidpoint(it)) }

        // Strictly increasing zone-midpoint by zone-midpoint — this is the whole point of the
        // curve: if the Abyss were not the brightest, pearls would wash out into the darkest
        // ambient. Sampled at each zone's own midpoint so this exercises the concrete tables,
        // not DepthBlend's boundary maths (see DepthBlendTest for that).
        for (i in 1 until intensities.size)
        {
            assertTrue(
                intensities[i] > intensities[i - 1],
                "zone ${Zone.entries[i]} (${intensities[i]}) must shine harder than " +
                    "${Zone.entries[i - 1]} (${intensities[i - 1]})"
            )
        }
    }

    @Test
    fun `the abyss pearl intensity is the brightest, matching pearls being the only light there`()
    {
        val abyss = DiveLighting.pearlIntensityForDepth(DepthBlend.zoneMidpoint(Zone.ABYSS))
        val others = Zone.entries.filter { it != Zone.ABYSS }
            .map { DiveLighting.pearlIntensityForDepth(DepthBlend.zoneMidpoint(it)) }
        assertTrue(others.all { it < abyss })
    }

    @Test
    fun `pearl intensity rises continuously with depth, with no step at a zone boundary`()
    {
        // The playtest complaint this whole rework answers: a hard switch exactly at a zone's
        // minDepth. Sample a hair either side of every boundary and require near-equality.
        val zones = Zone.entries
        for (i in 1 until zones.size)
        {
            val boundary = zones[i].minDepth
            val justAbove = DiveLighting.pearlIntensityForDepth(boundary - 0.01f)
            val justBelow = DiveLighting.pearlIntensityForDepth(boundary + 0.01f)
            assertTrue(
                kotlin.math.abs(justAbove - justBelow) < 0.01f,
                "pearl intensity jumped at the ${zones[i]} boundary: $justAbove -> $justBelow"
            )
        }
    }

    @Test
    fun `the diver's own light dims only in the abyss, where pearls should read as comparatively brighter`()
    {
        val abyss = DiveLighting.diverIntensityForDepth(DepthBlend.zoneMidpoint(Zone.ABYSS))
        val shallows = DiveLighting.diverIntensityForDepth(DepthBlend.zoneMidpoint(Zone.SHALLOWS))
        assertTrue(abyss < shallows, "diver light should be dimmer in the abyss than in the shallows")
    }

    @Test
    fun `a light well within the visible band is kept on screen`()
    {
        assertTrue(DiveLighting.isOnScreen(screenY = 900f, screenHeight = 1800f))
    }

    @Test
    fun `a light far above the top edge is culled`()
    {
        assertFalse(DiveLighting.isOnScreen(screenY = -500f, screenHeight = 1800f))
    }

    @Test
    fun `a light far below the bottom edge is culled`()
    {
        assertFalse(DiveLighting.isOnScreen(screenY = 2500f, screenHeight = 1800f))
    }

    @Test
    fun `a light just past the edge within the cull margin is still kept, avoiding pop-in at the border`()
    {
        // Pearls near the very top/bottom of the screen must not visibly snap their light off
        // one pixel before the pearl itself scrolls out of view.
        assertTrue(DiveLighting.isOnScreen(screenY = -10f, screenHeight = 1800f))
        assertTrue(DiveLighting.isOnScreen(screenY = 1810f, screenHeight = 1800f))
    }
}
