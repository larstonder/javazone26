package render

import dive.Tuning
import dive.Zone
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // weights at 0.0722 against green's 0.7152, so the abyss still measures far darker —
        // 11.98x on the current table, against the 10x this test demands. (It said 30x for a
        // while; that was never measured against the floored values. Look.kt's water-table
        // comment carries the same figure, and it is the one to keep in step with this test.)
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
        for ((name, color) in listOf("wall backing" to DiveRenderer.wallColor, "backdrop silhouette" to DiveRenderer.silhouetteColor))
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
            DiveRenderer.rawZoneBlueAt(justAboveTheFloor),
            DiveRenderer.zoneBlueAt(justAboveTheFloor),
            0f,
            "90m already clears the floor and must be passed through unchanged"
        )
    }

    /**
     * THE LIFT OF 2026-08-13, AND THE HALF OF IT THAT MUST NOT BE UNDONE BY EYE.
     *
     * The deep end of the band table was raised (blue 0.035 -> 0.14 in the Abyss) because the
     * frame is `albedo x irradiance` and BOTH halves were ramping to near-zero, so the deep fell
     * off quadratically — 98.9% of a measured 140 m frame at or below 2/255. See [zoneRed]'s doc.
     *
     * What that lift must not cost is the water's HUE. Deep water absorbs red first, and the old
     * table encoded that as a red:green:blue ratio that grows steadily bluer with depth; a lift
     * applied to blue alone, or eyeballed per channel, would have flattened it and left the deep
     * a washed slate. So this asserts the RELATIONSHIP rather than the numbers: every zone's
     * water is bluer than the zone above it, on both ratios.
     *
     * Sampled at zone MIDPOINTS because that is where [DepthBlend] anchors each zone's own value
     * (`smoothstep` has zero derivative there), so a midpoint reading is that zone's entry.
     */
    @Test
    fun `the water shifts further toward blue at every zone in turn`()
    {
        var previousBlueOverRed = 0f
        var previousBlueOverGreen = 0f
        for (zone in Zone.entries)
        {
            val depth = DepthBlend.zoneMidpoint(zone)
            // The RAW blue: zoneBlueAt adds the reflectance floor, which is a constraint on the
            // linear vector length and has nothing to say about the water's intended hue.
            val blueOverRed = DiveRenderer.rawZoneBlueAt(depth) / DiveRenderer.zoneRedAt(depth)
            val blueOverGreen = DiveRenderer.rawZoneBlueAt(depth) / DiveRenderer.zoneGreenAt(depth)
            assertTrue(
                blueOverRed > previousBlueOverRed,
                "$zone water is not bluer than the zone above it: blue/red $previousBlueOverRed -> $blueOverRed"
            )
            assertTrue(
                blueOverGreen > previousBlueOverGreen,
                "$zone water is not bluer than the zone above it: blue/green $previousBlueOverGreen -> $blueOverGreen"
            )
            previousBlueOverRed = blueOverRed
            previousBlueOverGreen = blueOverGreen
        }
    }

    /**
     * THE TWO ASSERTIONS THAT SPAN BOTH HALVES OF THE 2026-08-13 SPLIT, and the only place the
     * quadratic collapse can be expressed at all.
     *
     * Neither table can state it alone. `every zone still reads as its own colour at its own
     * midpoint` measures the ALBEDO, and the albedo alone never looked that bad — the reflectance
     * floor was quietly holding the old Abyss blue at 0.160 against a table asking for 0.035, so
     * the albedo ratio was 30x and not four orders of magnitude. What collapsed was the PRODUCT of
     * the band colour and the ambient, because the composite is a multiply and BOTH were ramping.
     * So the product is what has to be asserted, and it has to be run through the grade, because
     * near black the grade is a hard clamp rather than a curve.
     *
     * ## WHAT EACH ONE CATCHES
     *
     *  - **The water.** The Twilight is the deepest water the grade lets through at all (see
     *    [zoneRed]'s corrected model: the Trench is +0.0014 and the Abyss is still negative), so
     *    it is the shallowest place the collapse can be caught. At the old values it was -0.0053
     *    and measured 0.000/255 in a real 70 m capture; at these it is +0.0063 and measures 7.
     *  - **The objects.** This is the owner's actual complaint — *"you can't see the diver"* —
     *    and the ambient floor is what fixes it. A mid-grey object at the Abyss was -0.0074, i.e.
     *    pure black at ANY albedo, because the ambient was 0.003. It is now +0.033. The diver's
     *    suit is roughly this albedo and his torso measured 0.000 -> 10.3/255 at 140 m.
     *
     * BLUE, because blue is what carries deep water and is the channel the zone table lets fall
     * the least. Red and green are further under the clamp at every depth.
     */
    @Test
    fun `the deepest readable water clears the colour grade's black clamp`()
    {
        val twilight = DepthBlend.zoneMidpoint(Zone.TWILIGHT)
        val graded = gradedBlue(DiveRenderer.zoneBlueAt(twilight), DiveLighting.ambientBlueAt(twilight))
        assertTrue(
            graded > 0f,
            "the Twilight's water grades to $graded, which ACES clamps to pure black — albedo and " +
            "ambient are both ramping again and the water below the Kelp has stopped existing"
        )
    }

    @Test
    fun `an object in the abyss clears the colour grade's black clamp`()
    {
        // A plain mid-grey, not a transcription of the diver's suit: the sheet's texels are not
        // reachable from here, and the point is that NO albedo could survive an ambient of 0.003.
        val graded = gradedBlue(0.5f, DiveLighting.ambientBlueAt(DepthBlend.zoneMidpoint(Zone.ABYSS)))
        assertTrue(
            graded > 0f,
            "a mid-grey object in the Abyss grades to $graded, which ACES clamps to pure black — " +
            "the diver is algebraically invisible down there whatever his albedo is"
        )
    }

    /**
     * The value entering ACES for one channel, given a draw albedo and a draw ambient. Negative
     * means the colour grade clamps that channel to pure black.
     *
     * Reproduces `shaders/effects/color_grading.frag` and the linear-space composite exactly as
     * [DiveRenderer.zoneRed]'s doc sets it out, including the two things the deep-water plan's §2d
     * model got wrong: the multiply happens on the LINEAR side of `texture.vert`'s decode, and
     * `contrast` enters the shader scaled by 0.05, which near black makes it a SUBTRACTION of a
     * constant rather than a scaling. Validated against three captures spanning 0 to 87/255.
     *
     * The exposure and contrast values are the ones `DiveLighting.setup` constructs the
     * `ColorGradingEffect` with. They are transcribed because the effect is an engine class with
     * no accessor reachable from a unit test; if they move there and not here, this test goes
     * quietly wrong, so it is worth a grep when either is touched.
     */
    private fun gradedBlue(albedoDraw: Float, ambientDraw: Float): Float
    {
        val linear = DiveRenderer.srgbToLinear(albedoDraw) * DiveRenderer.srgbToLinear(ambientDraw)
        val exposed = linear * (Math.pow(2.0, GRADE_EXPOSURE.toDouble()).toFloat() - 1f)
        return (exposed - 0.5f) * (1f + 0.05f * (GRADE_CONTRAST - 1f)) + 0.5f
    }

    private val GRADE_EXPOSURE = 1.1f
    private val GRADE_CONTRAST = 1.3f

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
        val worldBottom = worldTop + Framing.VISIBLE_DEPTH_METRES
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
        for (span in listOf(Framing.VISIBLE_DEPTH_METRES, 61.3f))
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
        // Both rects are derived from a screen height the way the PRE-MIGRATION drawZoneBands
        // got them ([oldDepthAt]) — the loop now reads cam.topLeftWorldPosition instead, which
        // no unit test can produce. NOTE, so nobody reads more into this than is there: once
        // stripCount takes only world depths, "the same count at 900 px and at 2160 px" is true
        // by the signature and cannot fail — the 900/2160 pair documents the intent, and the
        // assertion that can actually fail is the count itself (120 = 60 m / 0.5 m).
        for (screenHeight in listOf(900f, 2160f))
        {
            val worldTop = oldDepthAt(0f, 0f, screenHeight)
            val worldBottom = oldDepthAt(screenHeight, 0f, screenHeight)
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
                val ppm = oldPixelsPerMetre(screenHeight)
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
                    oldCentres += oldDepthAt((y + stripBottom) * 0.5f, cam, screenHeight)
                    y = stripBottom
                }

                val worldTop = oldDepthAt(0f, cam, screenHeight)
                val worldBottom = oldDepthAt(screenHeight, cam, screenHeight)
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
     * bound still in place, it went red immediately: the camera's lag clamp let it sit 9 m off
     * the sea floor, so the bottom of the frame reached 211 m and eleven metres of painted
     * water had never been checked by anything. The floor clamp has since cut that back to
     * 184.930 m — see [deepestPaintedDepth], which both tests derive.
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
            val worldBottom = cam + Framing.VISIBLE_DEPTH_METRES
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

    // --- The PRE-MIGRATION screen transform, kept alive here and nowhere else ----------------
    //
    // These two were `Viewport.pixelsPerMetre` and `Viewport.depthAt` before the world moved
    // into metres, and they are the transform the strip walk used to be expressed in. They are
    // copied here, rather than the tests above being rewritten to state the new formula, for the
    // same reason CameraRigTest inlines the old screenX/screenY: the point of a
    // behaviour-preservation pin is agreement with WHAT SHIPPED, and a test that re-derives the
    // new code's own arithmetic pins nothing. Nothing in `src/main` calls anything like them any
    // more — `DiveRenderer` reads `cam.topLeftWorldPosition`, computed by the engine from the
    // real framebuffer, which is why `depthAt`'s own two ViewportTest cases could not be moved
    // to FramingTest and were deleted with their subject.

    private fun oldPixelsPerMetre(screenHeight: Float) = screenHeight / Framing.VISIBLE_DEPTH_METRES

    private fun oldDepthAt(screenY: Float, cameraDepth: Float, screenHeight: Float) =
        cameraDepth + screenY / oldPixelsPerMetre(screenHeight)

    // --- How deep the frame can actually reach ------------------------------------------
    //
    // Derived rather than picked, because picking is how the sweep above ended up bounded at a
    // round 200 m while the strip walk could paint 211 m. TWO clamps decide it now, and the
    // FLOOR CLAMP IS THE ONE THAT BINDS.
    //
    // `DiveCamera.clampSoDiverStaysVisible` lets the camera sit anywhere from
    // diverDepth - VISIBLE_DEPTH_METRES * DIVER_MAX_FRACTION to
    // diverDepth - VISIBLE_DEPTH_METRES * DIVER_MIN_FRACTION, and the diver's own depth is
    // clamped to [0, MAX_DEPTH] by DiveSim — which on its own would put the camera 9 m above
    // MAX_DEPTH, at 151 m, and the bottom of the frame at 211 m (the value this file used to
    // sweep to, back when that clamp was the whole story). `clampToSandBankFloor` runs FIRST
    // and cuts that back: the frame's bottom edge may not pass Framing.SEA_FLOOR_DEPTH
    // (184.930 m, the sandbank's own bottom edge), so at VISIBLE_DEPTH_METRES = 60 the camera
    // stops at 124.930 m and the deepest painted depth is 184.930 m.
    //
    // THE SWEEP GETS SMALLER, AND THAT IS CORRECT RATHER THAN A LOSS OF COVERAGE: no strip
    // below 184.930 m can be painted any more, so those depths' colours are unreachable.
    // 184.930 m is also not a coincidence of this file picking V = 60 — it is the GLOBAL
    // maximum of deepestPaintedDepth over every visible depth the camera could ever run at, and
    // the reason is an ASYMMETRY between the two clamp terms that is easy to get backwards.
    // `clampSoDiverStaysVisible` multiplies the CONSTANT `Framing.VISIBLE_DEPTH_METRES` (60),
    // never the real visible depth its own caller was handed for the current aspect ratio —
    // `DiveCameraTest`'s own `fractionOf` comment states the same fact for a different
    // assertion: "clampSoDiverStaysVisible and Framing.targetCameraDepth both multiply the
    // constant, never the actual frame." So the visibility term is pinned at
    // MAX_DEPTH - 60 * DIVER_MIN_FRACTION = 151 m at every aspect, while the floor term,
    // SEA_FLOOR_DEPTH - V, tracks the REAL V `clampToSandBankFloor` is actually passed (55.42 m
    // at 16:9 down to 27.71 m at 32:9 — see CameraRig). The floor term is the one that shrinks
    // as V grows, and it crosses the pinned 151 m at V = SEA_FLOOR_DEPTH - 151 = 33.9296 m:
    // below that the floor term is still the larger of the two and the visibility clamp binds
    // instead; at or above it the floor term is smaller and clampToSandBankFloor binds. This
    // file's V = 60 sits well past that crossover, so the floor clamp is the one that binds
    // here, and in that regime deepestPaintedDepth = deepestCameraDepth + V =
    // (SEA_FLOOR_DEPTH - V) + V = SEA_FLOOR_DEPTH exactly — the V cancels — so 184.930 m
    // (equivalently SandBank.QUAD_BOTTOM_DEPTH, which SandBankTest holds to within 1e-3 m of
    // Framing.SEA_FLOOR_DEPTH) is the true ceiling at EVERY aspect this game can run at past
    // 33.93 m, not just this file's V = 60.
    //
    // Nothing below 135 m (the abyss's midpoint) has a colour of its own — DepthBlend clamps
    // there — so extending the sweep further proves nothing new about the curve. It is derived
    // anyway so that raising MAX_DEPTH, widening the camera's lag bounds, or moving the sea
    // floor moves the sweep with them instead of silently leaving painted depths unchecked. The
    // `minOf` below is the third thing that can move it.

    @Test
    fun `every per-zone colour table in Look has exactly one entry per Zone`()
    {
        // THIS IS THE ONLY THING STANDING BETWEEN A SIXTH ZONE AND A CRASH IN A DRAW CALL.
        //
        // DepthBlend.blend takes "one entry per Zone, indexed by Zone.ordinal" and does exactly
        // that — no bounds check, no default. Six tables in Look.kt are keyed that way, and every
        // one of them is a hand-written floatArrayOf whose length is a fact about the source and
        // not about Zone. Add a zone to dive/Zone.kt without extending all six and the failure is
        // an ArrayIndexOutOfBoundsException from inside DiveRenderer.drawZoneBands, on the first
        // frame that paints the new band — with the whole rest of the suite green, because
        // nothing else in the project reads these arrays' length at all.
        //
        // Look.kt's own header calls itself "values only, tweak to taste". For these six that is
        // true of the NUMBERS and false of the COUNT, which is why the guard lives here rather
        // than being left to the reader to notice.
        val expected = Zone.entries.size
        mapOf(
            "ZONE_RED" to Look.ZONE_RED,
            "ZONE_GREEN" to Look.ZONE_GREEN,
            "ZONE_BLUE" to Look.ZONE_BLUE,
            "AMBIENT_RED" to Look.AMBIENT_RED,
            "AMBIENT_GREEN" to Look.AMBIENT_GREEN,
            "AMBIENT_BLUE" to Look.AMBIENT_BLUE
        ).forEach { (name, table) ->
            assertEquals(
                expected,
                table.size,
                "Look.$name has ${table.size} entries for $expected zones — DepthBlend.blend " +
                    "indexes it by Zone.ordinal and will throw inside a draw call"
            )
        }
    }

    // ---- The air vent's drawn rect -------------------------------------------------------------
    //
    // A vent stopped being a square when it took [OxygenSprite]'s sheet: the cell is 182x216, so
    // the drawn rect is TALLER than it is wide and the width is not a number anyone can check by
    // looking at an animated blob. These four tests pin the two halves of that — the rect itself,
    // and the two traps the wiring had to walk past.

    @Test
    fun `a vent is drawn taller than it is wide, and the width follows the sheet`()
    {
        val height = Framing.AIR_POCKET_SIZE_METRES
        val width = DiveRenderer.ventWidthFor(height)

        // The direction of the inequality is the whole point. A vent used to be drawn
        // `AIR_POCKET_SIZE_METRES` on both axes, so the failure this catches is someone restoring
        // that — or inverting the aspect, which would stretch the plume sideways by 19%.
        assertTrue(
            width < height,
            "the vent's cell is 182x216 (0.843:1), so the drawn rect must be narrower than it is " +
                "tall — got ${width}m x ${height}m"
        )
        assertTrue(
            width > height * 0.5f,
            "the art is 0.843:1, not a sliver — a width under half the height means the aspect " +
                "was inverted somewhere (got ${width}m for a ${height}m vent)"
        )

        // Not a restatement of `height * FRAME_ASPECT`. The aspect is measured off the committed
        // PNG in exactly one place and `OxygenSpriteTest` re-derives it from that file's IHDR, so
        // this asserts the renderer still ASKS rather than remembering — an inlined 0.843f here
        // would survive a re-bake at a different frame height and silently disagree with the art.
        assertEquals(
            OxygenSprite.widthForHeight(height),
            width,
            0f,
            "DiveRenderer.ventWidthFor must delegate to OxygenSprite.widthForHeight, not re-derive it"
        )

        // A ratio, not an offset: doubling the world height doubles the world width.
        assertEquals(width * 2f, DiveRenderer.ventWidthFor(height * 2f), 1e-4f)

        // The player has to be able to reach every pixel of it. `OxygenSpriteTest` asserts this
        // against the sheet's own arithmetic; here it is asserted against the number the renderer
        // actually draws with, which is the one a change to this file would move.
        val cornerReach = hypot(width * 0.5f, height * 0.5f)
        assertTrue(
            cornerReach < Tuning.AIR_POCKET_PICKUP_RADIUS,
            "the drawn vent's corner reaches ${cornerReach}m from its centre but the pickup radius " +
                "is ${Tuning.AIR_POCKET_PICKUP_RADIUS}m — the player can touch the art without " +
                "getting the breath"
        )
    }

    @Test
    fun `a vent's cull square contains both of the shapes it can draw`()
    {
        // `showsSquare` takes ONE size, and this method draws two different rects: the textured
        // one (narrow) and, when the shader or the sheet is missing, the flat square it always
        // was. Culling against anything smaller than the larger side pops an object off at the
        // edge of the screen — the safe direction is to keep something that is off frame.
        //
        // Swept rather than checked at the shipping height, because the whole reason this is `max`
        // and not the bare height `drawDiver` passes is that a re-bake could make the cell WIDER
        // than it is tall, at which point the bare height starts clipping vents at the sides.
        for (height in listOf(0.5f, 1f, Framing.AIR_POCKET_SIZE_METRES, 5f, 40f))
        {
            val width = DiveRenderer.ventWidthFor(height)
            val cull = DiveRenderer.ventCullSizeFor(height)

            assertTrue(
                cull >= width && cull >= height,
                "a ${width}m x ${height}m vent is culled against a ${cull}m square — that is " +
                    "smaller than the object on at least one axis, so it will vanish before it " +
                    "leaves the frame"
            )
        }

        // And the fallback square specifically: it is drawn `AIR_POCKET_SIZE_METRES` on both axes,
        // so the cull square must be at least that or the degraded frame culls differently from
        // the textured one — a bug that only appears on the handful of frames before the sheet
        // uploads, which is exactly where nobody looks.
        assertEquals(
            Framing.AIR_POCKET_SIZE_METRES,
            DiveRenderer.ventCullSizeFor(Framing.AIR_POCKET_SIZE_METRES),
            0f,
            "the fallback square is AIR_POCKET_SIZE_METRES on both axes and must be fully covered " +
                "by the cull square"
        )
    }

    @Test
    fun `a vent is not grown by the equal-area disc compensation`()
    {
        // THE TRAP. `drawPearlSurface` and `Hud.drawAirRing` both pass their size through
        // `IridescenceRenderer.equalAreaQuad`, because the ANALYTIC path inscribes a disc in the
        // quad and loses 1 - pi/4 of its area. A vent does not take that path: its silhouette is
        // the sheet's ALPHA, which fills the cell to within the 2 px margin the bake leaves. So
        // the compensation would be applied to a shape that is already full, oversizing the plume
        // by ~12% on each axis and pushing its corners toward the pickup radius.
        //
        // Asserted by source scan because there is no seam to observe it at: both forms compile,
        // both draw a plausible blob, and the difference is 12% on an object nobody has seen at
        // the correct size.
        val body = drawAirPocketsBody()
        assertFalse(
            body.contains("equalAreaQuad"),
            "drawAirPockets applies IridescenceRenderer.equalAreaQuad. That factor compensates " +
                "for the analytic hemisphere being inscribed in its quad; a textured vent's " +
                "silhouette already fills the cell, so this oversizes it. See the method's KDoc."
        )
    }

    @Test
    fun `a vent's albedo and its normal map are one argument list`()
    {
        // The rule CLAUDE.md states for every normal-mapped sprite here and `drawDiver` is the
        // worked example of: the second call's arguments are COPIED, never derived a second time.
        // For a vent it is stronger than usual — the iridescence shader takes the vent's SHAPE
        // from this same texture's alpha, so a normal map on a different rect would light a
        // silhouette that is not where the drawn one is.
        val body = drawAirPocketsBody()

        val iridescenceArgs = argumentsOf(body, ".draw(")
        val normalMapArgs = argumentsOf(body, "drawNormalMap(")

        val rect = iridescenceArgs.split(", ").take(4).joinToString(", ")
        assertTrue(
            rect.split(", ").size == 4 && normalMapArgs.contains(rect),
            "the normal-map draw does not take the iridescence draw's rect verbatim.\n" +
                "  iridescence: $iridescenceArgs\n  normal map:  $normalMapArgs"
        )

        // THE RECT IS STILL THE SHEET'S SHAPE AT THE CALL SITE, not just in `ventWidthFor`. The
        // two size arguments must be different expressions: passing the height to both is the
        // pre-sheet square, which compiles, draws, and is the exact regression the extraction
        // above exists to make visible. Swapping them rotates every plume 90 degrees, which is a
        // shape a viewer has no way to know is wrong.
        val (w, h) = rect.split(", ").drop(2)
        assertTrue(
            w != h,
            "the vent's iridescence quad is drawn `$w` by `$h` — the same expression on both " +
                "axes is the square the sheet replaced"
        )
        assertTrue(
            body.contains("val $w = ventWidthFor("),
            "the vent's quad width `$w` does not come from DiveRenderer.ventWidthFor, so the " +
                "rect the tests above pin is not the rect being drawn"
        )
        assertTrue(
            body.contains("val $h = Framing.AIR_POCKET_SIZE_METRES"),
            "the vent's quad height `$h` is no longer Framing.AIR_POCKET_SIZE_METRES — the world " +
                "size denotes the HEIGHT and the width follows the art, not the other way round"
        )

        // The same TEXTURE, from one lookup. Two calls to `OxygenSprite.normalFrame(frame)` would
        // still be correct today and would be two places to get the frame index wrong tomorrow.
        assertEquals(
            iridescenceArgs.split(", ").last().trim(),
            normalMapArgs.split(", ").first().trim(),
            "the two draws pass different textures — they must share one local"
        )

        // No angle, and the centre origin on both. A vent has no facing (the sheet is authored
        // upright and the analytic path takes no angle either), so this is what keeps the normal
        // map's quad on top of the albedo's rather than a parameter anyone is meant to tune.
        assertTrue(
            normalMapArgs.contains("0f, CENTRE_ORIGIN, CENTRE_ORIGIN"),
            "the normal-map draw must pass the same zero angle and centre origin as the " +
                "iridescence quad — got: $normalMapArgs"
        )

        assertTrue(
            body.contains("IridescentMaterial.VENT"),
            "drawAirPockets no longer draws with IridescentMaterial.VENT — re-read this test"
        )
    }

    /**
     * `drawAirPockets`' source, comments stripped and whitespace collapsed.
     *
     * Comments first, exactly as `DrawTest`, `AnglerfishDisguiseTest` and `MainCameraOwnershipTest`
     * do it: that method's KDoc explains at length why `equalAreaQuad` is absent, and a scan that
     * read the prose would report the explanation as the offence.
     */
    private fun drawAirPocketsBody(): String
    {
        val stripped = File(RENDERER_SOURCE).readText()
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")

        val start = stripped.indexOf("private fun drawAirPockets")
        assertTrue(start >= 0, "no `private fun drawAirPockets` in $RENDERER_SOURCE any more — re-read this test")

        val rest = stripped.substring(start + 1)
        val end = Regex("\\n    (private|internal|fun|val|const) ").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
            .replace(Regex("\\s+"), " ")
            .replace("( ", "(")
            .replace(" )", ")")
    }

    /** The text between the parentheses of the first call whose text begins with [marker]. */
    private fun argumentsOf(body: String, marker: String): String
    {
        val at = body.indexOf(marker)
        assertTrue(at >= 0, "no `$marker` call in drawAirPockets any more — re-read this test")

        var depth = 0
        val open = at + marker.length - 1
        for (i in open until body.length)
        {
            if (body[i] == '(') depth++
            if (body[i] == ')')
            {
                depth--
                if (depth == 0) return body.substring(open + 1, i)
            }
        }
        throw AssertionError("unbalanced parentheses after `$marker` in drawAirPockets")
    }

    private val shallowestCameraDepth = 0f - Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_MAX_FRACTION

    // Mirrors DiveCamera's own two clamps — clampToSandBankFloor then clampSoDiverStaysVisible
    // — rather than restating the old pre-floor number. See the "How deep the frame can
    // actually reach" comment block above for the full derivation and the crossover math; the
    // short version is that the floor term is the one that binds at this file's V = 60, so
    // `minOf` picks it. Both terms are still expressed so that a future MAX_DEPTH or
    // DIVER_MIN_FRACTION change that swung the crossover back the other way would move this
    // value with it instead of leaving a stale winner hard-coded.
    private val deepestCameraDepth = minOf(
        Tuning.MAX_DEPTH - Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_MIN_FRACTION,  // 151.0
        Framing.SEA_FLOOR_DEPTH - Framing.VISIBLE_DEPTH_METRES                          // 124.930
    )

    private val deepestPaintedDepth = deepestCameraDepth + Framing.VISIBLE_DEPTH_METRES  // 184.930

    private companion object
    {
        const val RENDERER_SOURCE = "src/main/kotlin/render/DiveRenderer.kt"
    }
}
