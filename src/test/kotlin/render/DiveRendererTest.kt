package render

import dive.Tuning
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
     * sweeps the entire reachable depth range at a quarter of a strip's height. "Reachable" is
     * [deepestPaintedDepth], derived from the camera's own lag clamp — it used to be a
     * hand-picked 200 m, which was 11 m short of the depth the bottom row of the frame can
     * actually reach.
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
        while (depth <= deepestPaintedDepth)
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

    // --- The strip walk (DiveRenderer.stripCount / stripTopDepth / stripCentreDepth) --------
    //
    // The walk decides WHICH depths the colour curve above is sampled at, so it is the other
    // half of the seam story: the curve can clear GI_REFLECTANCE_FLOOR at every depth and the
    // frame will still seam if the loop paints a strip with a colour taken from somewhere else.
    // Expressed in world depths (not screen rows) so it can be asserted at all.

    @Test
    fun `strip centres start half a strip below the top and step one strip at a time`()
    {
        val worldTop = 37.5f
        val worldBottom = worldTop + Viewport.VISIBLE_DEPTH_METRES
        val count = DiveRenderer.stripCount(worldTop, worldBottom)

        assertEquals(
            worldTop + DiveRenderer.BAND_STRIP_METRES * 0.5f,
            DiveRenderer.stripCentreDepth(worldTop, worldBottom, 0),
            1e-3f,
            "the first strip must be coloured by the depth at its middle, not at its top edge"
        )

        // 60 m is an exact multiple of the strip pitch, so no strip here is clipped and the
        // spacing must be uniform all the way down. A pitch the loop does not agree with, or a
        // centre that is not the midpoint of its own strip, breaks this.
        for (i in 1 until count)
        {
            val previous = DiveRenderer.stripCentreDepth(worldTop, worldBottom, i - 1)
            val centre = DiveRenderer.stripCentreDepth(worldTop, worldBottom, i)
            assertEquals(
                DiveRenderer.BAND_STRIP_METRES,
                centre - previous,
                1e-3f,
                "strip $i's centre ($centre m) is not one strip below strip ${i - 1}'s ($previous m)"
            )
        }
    }

    @Test
    fun `the strips tile the visible rect with no gaps and the last one is clipped to it`()
    {
        // 61.3 m is deliberately NOT a multiple of the strip pitch. Today's rect is always
        // exactly VISIBLE_DEPTH_METRES tall, but once the rect comes from the engine's own
        // camera it is whatever the framebuffer makes it, so the clip has to be real.
        for (span in listOf(Viewport.VISIBLE_DEPTH_METRES, 61.3f))
        {
            val worldTop = -24f
            val worldBottom = worldTop + span
            val count = DiveRenderer.stripCount(worldTop, worldBottom)

            assertEquals(worldTop, DiveRenderer.stripTopDepth(worldTop, 0), 0f, "the first strip must start at the top of the rect")

            for (i in 0 until count)
            {
                // Recovered from the strip's own centre rather than re-derived from the pitch,
                // so this asserts the bottom edge the loop actually paints to.
                val bottom = 2f * DiveRenderer.stripCentreDepth(worldTop, worldBottom, i) - DiveRenderer.stripTopDepth(worldTop, i)
                val expected = if (i == count - 1) worldBottom else DiveRenderer.stripTopDepth(worldTop, i + 1)
                assertEquals(
                    expected,
                    bottom,
                    1e-3f,
                    "span $span m: strip $i ends at $bottom m, leaving a gap or an overlap against $expected m"
                )
            }
        }
    }

    @Test
    fun `the standard visible rect is 120 strips`()
    {
        // Both rects are derived through Viewport from a screen height, which is how
        // drawZoneBands gets them. NOTE, so nobody reads more into this than is there: once
        // stripCount takes only world depths, "the same count at 900 px and at 2160 px" is true
        // by the signature and cannot fail — the 900/2160 pair documents the intent, and the
        // assertion that can actually fail is the count itself (120 = 60 m / 0.5 m).
        for (screenHeight in listOf(900f, 2160f))
        {
            val worldTop = Viewport.depthAt(0f, 0f, screenHeight)
            val worldBottom = Viewport.depthAt(screenHeight, 0f, screenHeight)
            assertEquals(120, DiveRenderer.stripCount(worldTop, worldBottom), "strip count for a ${screenHeight}px screen")
        }
    }

    @Test
    fun `a degenerate or absurd world rect is clamped instead of hanging the frame`()
    {
        // Risk 4.6: the world rect is computed by the engine before any of our code runs, so on
        // the first frame — or mid-resize — it can be empty, inverted or enormous. Too few
        // strips is a frame that looks wrong; an unbounded count is a frame that never ends.
        assertEquals(0, DiveRenderer.stripCount(50f, 50f), "an empty rect draws nothing")
        assertEquals(0, DiveRenderer.stripCount(50f, 10f), "an inverted rect draws nothing")
        assertEquals(0, DiveRenderer.stripCount(Float.NaN, Float.NaN), "a NaN rect draws nothing")
        assertEquals(
            DiveRenderer.MAX_BAND_STRIPS,
            DiveRenderer.stripCount(0f, 1_000_000f),
            "an absurd rect must be clamped"
        )
        assertTrue(
            DiveRenderer.MAX_BAND_STRIPS > 120,
            "the clamp must sit well above the 120 strips a normal frame draws, or it would bite in play"
        )
    }

    /**
     * THE BEHAVIOUR-PRESERVATION PIN. The walk was extracted from a loop that stepped screen
     * rows and converted each row back to a depth; this asserts the world-depth version paints
     * the same rows with the same colours, on real framebuffer heights and over the camera's
     * whole reachable range. Written against a copy of the old loop inlined below, since the
     * original is gone — the point is agreement with what shipped, not with a second statement
     * of the new formula.
     *
     * THE ONE MEASURED DIFFERENCE, and why it is not a pixel. The old loop accumulated `y` by
     * repeated float addition, so on a screen whose height is not a whole multiple of the strip
     * pitch in pixels (1000 px, camera at 37.5 m, measured) it undershot the bottom edge by
     * ~6e-5 px and ran one extra iteration — a 121st strip with no height. The new walk rounds
     * the span up once instead and produces 120. Hence `<= 1` rather than an equality, plus the
     * explicit bound below on how much of the frame that surplus strip could ever have covered:
     * a hundredth of a pixel is two orders of magnitude under anything that can rasterise.
     * On 900/1080/1440/1800/2160 px, where pixels-per-metre divides exactly, the two walks agree
     * strip for strip.
     */
    @Test
    fun `the world-depth walk reproduces the pixel loop it replaced`()
    {
        // Camera depths spanning the reachable range: -24 m (floating at the surface, so the
        // camera sits above it) to 136 m (the deepest the camera eases to at MAX_DEPTH). The odd
        // heights are in the list on purpose — the booth display's resolution is not known.
        for (screenHeight in listOf(900f, 1000f, 1051f, 1080f, 1440f, 1800f, 2160f))
        {
            for (cam in listOf(-24f, 0f, 37.5f, 94.5f, 136f))
            {
                val ppm = Viewport.pixelsPerMetre(screenHeight)
                val stripHeight = DiveRenderer.BAND_STRIP_METRES * ppm

                val oldTops = ArrayList<Float>()
                val oldBottoms = ArrayList<Float>()
                val oldCentres = ArrayList<Float>()
                var y = 0f
                while (y < screenHeight)
                {
                    val stripBottom = minOf(y + stripHeight, screenHeight)
                    oldTops += y
                    oldBottoms += stripBottom
                    oldCentres += Viewport.depthAt((y + stripBottom) * 0.5f, cam, screenHeight)
                    y = stripBottom
                }

                val worldTop = Viewport.depthAt(0f, cam, screenHeight)
                val worldBottom = Viewport.depthAt(screenHeight, cam, screenHeight)
                val count = DiveRenderer.stripCount(worldTop, worldBottom)
                val where = "${screenHeight}px, camera at ${cam}m"

                assertTrue(
                    kotlin.math.abs(count - oldTops.size) <= 1,
                    "strip count at $where: $count against the old loop's ${oldTops.size}"
                )
                for (i in 0 until minOf(count, oldTops.size))
                {
                    val top = (DiveRenderer.stripTopDepth(worldTop, i) - worldTop) * ppm
                    val bottom = (minOf(DiveRenderer.stripTopDepth(worldTop, i + 1), worldBottom) - worldTop) * ppm
                    assertEquals(oldTops[i], top, 0.01f, "strip $i top at $where")
                    assertEquals(oldBottoms[i], bottom, 0.01f, "strip $i bottom at $where")
                    assertEquals(
                        oldCentres[i],
                        DiveRenderer.stripCentreDepth(worldTop, worldBottom, i),
                        1e-3f,
                        "strip $i colour depth at $where"
                    )
                }

                // Whichever walk has the extra strip, it can only be covering the sliver
                // between the other's last bottom edge and the bottom of the screen. Both
                // walks must reach the bottom, so that sliver has no height worth drawing.
                val lastBottom = (minOf(DiveRenderer.stripTopDepth(worldTop, count), worldBottom) - worldTop) * ppm
                assertEquals(screenHeight, lastBottom, 0.01f, "the walk must reach the bottom of the screen at $where")
            }
        }
    }

    /**
     * WHAT THIS ADDS OVER THE SWEEP ABOVE, because it is less than it looks and saying so is
     * the point. Measured, not assumed: mutating the floor out of `zoneBlueAt` kills this test
     * and the sweep together, and nothing kills this one alone through the colour code. It
     * cannot, because `floorBlueForReflectance` holds the floor at EVERY depth by construction
     * — so a walk that sampled the wrong depths entirely would still paint colours that clear
     * it, and the floor assertion below can only ever restate the sweep. Depths above the
     * waterline add nothing either: `DepthBlend` clamps everything shallower than the
     * shallowest zone midpoint to that zone's flat value, so a strip at -24 m is coloured
     * identically to one at 0 m, which the sweep already checks.
     *
     * What this test is for is therefore the COUPLING: that the depth a strip is coloured by
     * lies inside that strip, and that no strip the camera can ever reach is coloured from
     * outside the range the sweep covers. That is what lets the sweep's bounded loop stand for
     * the whole frame — and it is not decoration. Written with the sweep's hand-picked 200 m
     * bound still in place, it went red immediately: the camera's lag clamp lets it sit 9 m off
     * the sea floor, so the bottom of the frame reaches 211 m and eleven metres of painted
     * water had never been checked by anything. See [deepestPaintedDepth], which both tests now
     * derive.
     *
     * The floor assertion below is kept as the direct statement of risk 4.2's property and is,
     * measured, redundant with the sweep. No single-edit mutation of today's production code
     * kills this test alone; the assertions that only it makes are guards against a future
     * widening of MAX_DEPTH, the camera's lag bounds or VISIBLE_DEPTH_METRES outrunning the
     * swept range. Recorded plainly rather than dressed up as verification.
     */
    @Test
    fun `every strip is coloured from inside itself, by a depth the floor sweep covers`()
    {
        var cam = shallowestCameraDepth
        while (cam <= deepestCameraDepth)
        {
            val worldTop = cam
            val worldBottom = cam + Viewport.VISIBLE_DEPTH_METRES
            val count = DiveRenderer.stripCount(worldTop, worldBottom)
            for (i in 0 until count)
            {
                val depth = DiveRenderer.stripCentreDepth(worldTop, worldBottom, i)
                val stripTop = DiveRenderer.stripTopDepth(worldTop, i)
                assertTrue(
                    depth >= stripTop && depth <= minOf(stripTop + DiveRenderer.BAND_STRIP_METRES, worldBottom),
                    "with the camera at ${cam}m, strip $i covers ${stripTop}m onward but is coloured by ${depth}m"
                )
                assertTrue(
                    depth <= deepestPaintedDepth,
                    "with the camera at ${cam}m, strip $i is coloured by ${depth}m — past the " +
                    "${deepestPaintedDepth}m the reflectance-floor sweep covers, so nothing has checked that colour"
                )

                val length = DiveRenderer.reflectanceLength(
                    quantize(DiveRenderer.zoneRedAt(depth)),
                    quantize(DiveRenderer.zoneGreenAt(depth)),
                    quantize(DiveRenderer.zoneBlueAt(depth))
                )
                assertTrue(
                    length >= DiveRenderer.GI_REFLECTANCE_FLOOR,
                    "with the camera at ${cam}m, strip $i is coloured by ${depth}m, whose linear " +
                    "length $length is under the GI reflectance floor of ${DiveRenderer.GI_REFLECTANCE_FLOOR}"
                )
            }
            cam += 0.25f
        }
    }

    /** The engine truncates rather than rounds — see `SurfaceConfigInternal.setDrawColor`. */
    private fun quantize(channel: Float): Float = (channel.coerceIn(0f, 1f) * 255f).toInt() / 255f

    // --- How deep the frame can actually reach ------------------------------------------
    //
    // Derived rather than picked, because picking is how the sweep above ended up bounded at a
    // round 200 m while the strip walk could paint 211 m. What decides it is DiveCamera's own
    // clamp, not targetCameraDepth: `clampSoDiverStaysVisible` lets the camera sit anywhere
    // from diverDepth - VISIBLE_DEPTH_METRES * DIVER_MAX_FRACTION to
    // diverDepth - VISIBLE_DEPTH_METRES * DIVER_MIN_FRACTION, and the diver's own depth is
    // clamped to [0, MAX_DEPTH] by DiveSim. So the deepest the camera can ever be is 9 m above
    // the sea floor, and the bottom of the frame is a further VISIBLE_DEPTH_METRES below that.
    //
    // Nothing below 135 m (the abyss's midpoint) has a colour of its own — DepthBlend clamps
    // there — so extending the sweep past 200 m proves nothing new about the curve. It is
    // derived anyway so that raising MAX_DEPTH or widening the camera's lag bounds moves the
    // sweep with them instead of silently leaving painted depths unchecked.

    private val shallowestCameraDepth = 0f - Viewport.VISIBLE_DEPTH_METRES * Viewport.DIVER_MAX_FRACTION

    private val deepestCameraDepth = Tuning.MAX_DEPTH - Viewport.VISIBLE_DEPTH_METRES * Viewport.DIVER_MIN_FRACTION

    private val deepestPaintedDepth = deepestCameraDepth + Viewport.VISIBLE_DEPTH_METRES
}
