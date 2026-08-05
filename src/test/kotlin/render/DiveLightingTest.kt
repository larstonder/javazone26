package render

import dive.Zone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DiveLighting` is mostly unmockable — it drives a live GlobalIlluminationSystem and Lamp
 * scene entities, which need a real GL context (see the empirically-verified camera-origin
 * bug documented in DiveLighting.setup, found only by rendering and reading the framebuffer
 * back). What IS pure — zone intensity and the on-screen culling check — is extracted and
 * tested here.
 */
class DiveLightingTest
{
    @Test
    fun `pearls shine harder the deeper the zone, so they stay legible against the darker ambient`()
    {
        val intensities = Zone.entries.map { DiveLighting.zoneIntensityFor(it) }

        // Strictly increasing depth-by-depth — this is the whole point of the lookup: if the
        // Abyss were not the brightest lamp, pearls would wash out into the darkest ambient.
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
    fun `the abyss lamp intensity is the brightest, matching pearls being the only light there`()
    {
        val abyss = DiveLighting.zoneIntensityFor(Zone.ABYSS)
        val others = Zone.entries.filter { it != Zone.ABYSS }.map { DiveLighting.zoneIntensityFor(it) }
        assertTrue(others.all { it < abyss })
    }

    @Test
    fun `a lamp well within the visible band is kept on screen`()
    {
        assertTrue(DiveLighting.isOnScreen(screenY = 900f, screenHeight = 1800f))
    }

    @Test
    fun `a lamp far above the top edge is culled`()
    {
        assertFalse(DiveLighting.isOnScreen(screenY = -500f, screenHeight = 1800f))
    }

    @Test
    fun `a lamp far below the bottom edge is culled`()
    {
        assertFalse(DiveLighting.isOnScreen(screenY = 2500f, screenHeight = 1800f))
    }

    @Test
    fun `a lamp just past the edge within the cull margin is still kept, avoiding pop-in at the border`()
    {
        // Pearls near the very top/bottom of the screen must not visibly snap their light off
        // one pixel before the pearl itself scrolls out of view.
        assertTrue(DiveLighting.isOnScreen(screenY = -10f, screenHeight = 1800f))
        assertTrue(DiveLighting.isOnScreen(screenY = 1810f, screenHeight = 1800f))
    }
}
