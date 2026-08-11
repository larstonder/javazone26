package render

import dive.Zone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The god rays, as the owner asked for them: soft overlapping bands whose width and spacing fall
 * out of a stack of sines, drifting slowly, dimming with depth.
 *
 * How they LOOK is not testable and this file does not pretend otherwise (see the contact sheet
 * in this task's report). What is testable is every property the look is built out of, and each
 * case below is written against the property rather than against the number that currently
 * produces it:
 *
 *  - the periods are pairwise incommensurate, which is the ONLY reason the bands are irregular —
 *    make any two harmonic and the frame gets a repeating pattern back;
 *  - the amplitudes sum to 1, so the mask's threshold means a fraction of the largest crest;
 *  - the drift rates differ and are signed, so the pattern breathes instead of sliding;
 *  - the bands are a minority of the water, i.e. there is dark between them;
 *  - the bands converge upward and splay downward;
 *  - the ramp is `ambientGreen(d)/ambientGreen(0)` and is EXACTLY zero in the Abyss, which is the
 *    guard rail this whole feature lives under (spec 11 and 6b);
 *  - the overlay's geometry stops before the Abyss can see it, which is the other half of that
 *    guard rail and the half that is not arithmetic;
 *  - the shafts are warm and the torch is not.
 */
class LightShaftsTest
{
    /**
     * THE IRREGULARITY IS THE PERIODS' AND NOTHING ELSE'S.
     *
     * `f2f2eaa` shipped five hand-placed shafts and the owner called them "ruler-drawn stripes";
     * the answer he asked for was a sum of sines, where nobody places anything. That only works
     * while the periods share no small common multiple: two sines at 2:1 sum to a shape that
     * repeats every long period, and a repeating shape is a pattern even when the crests inside
     * the repeat are unevenly spaced. Any small integer ratio does the same thing, more weakly.
     *
     * So the ratio of every pair is asserted to sit well away from an integer, and separately the
     * stack is required not to repeat within the width of the water column — which is the form of
     * the requirement a player could actually see.
     */
    @Test
    fun `no two bands are harmonically related, so the stack never repeats in the column`()
    {
        for (i in 0 until LightShafts.bandCount)
        {
            for (j in i + 1 until LightShafts.bandCount)
            {
                val ratio = maxOf(LightShafts.period(i), LightShafts.period(j)) /
                    minOf(LightShafts.period(i), LightShafts.period(j))
                assertTrue(
                    abs(ratio - ratio.roundToInt()) > 0.15f,
                    "bands $i and $j have periods ${LightShafts.period(i)} m and " +
                    "${LightShafts.period(j)} m, a ratio of $ratio — near enough an integer that " +
                    "their crests line up regularly and the sum reads as a repeating pattern"
                )
            }
        }

        // And the emergent property: sample the mask across the column and require that no shift
        // by a plausible band spacing reproduces it. A stack with a short common period would
        // match itself here almost exactly.
        val samples = (0..800).map { LightShafts.bandMask(-40f + it * 0.1f, 0f, 0f) }
        for (shiftMetres in 5..40)
        {
            val shift = shiftMetres * 10
            val overlap = samples.indices.drop(shift)
            val error = overlap.sumOf { abs(samples[it] - samples[it - shift]).toDouble() } / overlap.size
            assertTrue(
                error > 0.02,
                "shifting the band pattern by $shiftMetres m reproduces it to within $error — " +
                "the stack repeats at that spacing, which is what it was chosen not to do"
            )
        }
    }

    /**
     * THE AMPLITUDES SUM TO 1, WHICH IS WHAT MAKES THE THRESHOLD A FRACTION AND NOT A GUESS.
     *
     * `bandSum` is bounded by the sum of the amplitudes, so at 1 the sum lives in [-1, 1] and
     * `BAND_THRESHOLD` / `BAND_PEAK` are read directly as "how far up the largest possible crest".
     * Re-tuning one amplitude for a look without re-normalising moves the coverage — how much of
     * the water is inside a band — which is the LEVEL and is the thing measured against the deep.
     */
    @Test
    fun `the band amplitudes sum to exactly one`()
    {
        val total = (0 until LightShafts.bandCount).map { LightShafts.amplitude(it) }.sum()
        assertEquals(
            1f, total, 1e-4f,
            "the band amplitudes sum to $total, so bandSum no longer spans [-1, 1] and " +
            "BAND_THRESHOLD (${LightShafts.BAND_THRESHOLD}) is no longer the fraction of a crest " +
            "it is documented as"
        )

        // The bound has to actually hold, not merely be arithmetic on the table.
        val peak = (0..400).maxOf { LightShafts.bandSum(-40f + it * 0.2f, 0f, 0f) }
        assertTrue(peak <= 1f, "bandSum reaches $peak across the column, above its own bound")
    }

    /**
     * THE DRIFT MAKES THE PATTERN BREATHE RATHER THAN SLIDE.
     *
     * The owner asked for it to animate, and the cheap version of that — one phase advanced on
     * every band — translates the whole stack sideways at a constant speed, which on a static
     * camera reads as the camera panning rather than as light moving in water. Different rates
     * (with mixed signs) make crests widen, narrow, merge and separate in place.
     *
     * Also asserted: nothing is fast enough to loop inside a booth session, and nothing is zero,
     * which would silently pin one band while the rest moved around it.
     */
    @Test
    fun `the bands drift at different rates and in both directions`()
    {
        val rates = (0 until LightShafts.bandCount).map { LightShafts.driftRate(it) }

        assertEquals(
            rates.size, rates.distinct().size,
            "two bands drift at the same rate ($rates) — to that extent the pattern translates " +
            "rigidly instead of evolving"
        )
        assertTrue(rates.any { it > 0f } && rates.any { it < 0f }, "every band drifts the same way: $rates")
        assertTrue(rates.none { it == 0f }, "a band with zero drift is pinned while the others move: $rates")

        val slowestCycleSeconds = 2 * Math.PI / rates.minOf { abs(it) }
        assertTrue(
            slowestCycleSeconds > 90.0,
            "the slowest band cycles in $slowestCycleSeconds s, inside the time a player stands " +
            "at the cabinet — the animation would be seen to loop"
        )

        // And it really does change the shape, not just its position: the same point must read
        // differently a minute apart, and the pattern must not be a pure translation of itself.
        val early = (0..400).map { LightShafts.bandMask(-40f + it * 0.2f, 0f, 0f) }
        val later = (0..400).map { LightShafts.bandMask(-40f + it * 0.2f, 0f, 60f) }
        assertTrue(
            early.indices.sumOf { abs(early[it] - later[it]).toDouble() } / early.size > 0.02,
            "the band pattern is the same 60 s later — nothing is animating"
        )
    }

    /**
     * THERE IS DARK WATER BETWEEN THE BANDS.
     *
     * "Soft overlapping bands" is not "a sine ripple across the whole frame". The threshold is
     * what separates the crests, and with it at 0 exactly half the water would be inside a band
     * and the effect would read as a texture on the water rather than as light coming through it.
     * Too high and the shafts become rare needles.
     *
     * Measured as coverage — the fraction of a horizontal line that is inside a band at all —
     * because that is the property, and it is what a threshold change or an amplitude change both
     * move. Sampled at three animation times so it cannot pass on one lucky phase.
     */
    @Test
    fun `the bands are a minority of the water at every phase, with gaps between them`()
    {
        for (seconds in listOf(0f, 37f, 91f))
        {
            val mask = (0..800).map { LightShafts.bandMask(-40f + it * 0.1f, 0f, seconds) }
            val coverage = mask.count { it > 0.5f } / mask.size.toFloat()
            assertTrue(
                coverage in 0.1f..0.45f,
                "at t = $seconds s the bands cover $coverage of the water. Below 0.1 they are " +
                "needles; above 0.45 there is no dark left between them and the effect is a " +
                "ripple across the whole frame rather than shafts"
            )
            assertTrue(
                mask.any { it < 0.01f },
                "at t = $seconds s no part of the water is outside a band at all"
            )
        }
    }

    /**
     * THE BANDS CONVERGE UPWARD AND SPLAY DOWNWARD, WHICH IS ONE LINE OF THE SHADER.
     *
     * The owner's reference has them "converging very slightly". The mechanism is that the stack
     * is evaluated at where the ray from the apex through a fragment crosses the SURFACE plane, so
     * a fixed world distance spans less of the pattern the deeper it is — bands get wider and
     * further apart with depth. Asserted through [LightShafts.surfacePlaneX], which is what both
     * the Kotlin model and `godrays.frag` compute, and at the extremes: the apex must be ABOVE the
     * water (a negative or shallow height would put the vanishing point in the water and turn the
     * splay into a pinch), and the splay must be slight rather than a starburst.
     */
    @Test
    fun `the bands splay with depth, from an apex above the water`()
    {
        assertTrue(
            LightShafts.APEX_HEIGHT_METRES > LightShafts.END_DEPTH_METRES,
            "the apex is ${LightShafts.APEX_HEIGHT_METRES} m above the surface but the overlay " +
            "reaches ${LightShafts.END_DEPTH_METRES} m down; the projection's denominator " +
            "(H + depth) must never approach zero inside the drawn band"
        )

        val spanAtSurface = LightShafts.surfacePlaneX(10f, 0f) - LightShafts.surfacePlaneX(0f, 0f)
        var previous = spanAtSurface
        for (depth in listOf(0.25f, 0.5f, 0.75f, 1f).map { it * LightShafts.END_DEPTH_METRES })
        {
            val span = LightShafts.surfacePlaneX(10f, depth) - LightShafts.surfacePlaneX(0f, depth)
            assertTrue(
                span < previous,
                "10 m of water spans $span of the band pattern at $depth m against $previous " +
                "just above it — the bands are not splaying"
            )
            previous = span
        }

        val splay = spanAtSurface / previous
        assertTrue(
            splay in 1.1f..2f,
            "a band is ${splay}x wider at the bottom of the overlay than at the surface. Below " +
            "1.1 the convergence is invisible; above 2 it is a starburst rather than the 'very " +
            "slight' convergence the reference shows"
        )
    }

    /**
     * THE GUARD RAIL, AS ARITHMETIC RATHER THAN AS A REMEMBERED CUTOFF.
     *
     * The Abyss being dark is the design's central mechanic, and an overlay that spans ninety
     * metres of water is exactly the kind of thing that erodes it a little at a time. The ramp is
     * derived from `DiveLighting`'s own ambient table (`ambient(d) / ambient(0)` — the inverse of
     * what the reverted rim light used), so the shafts reach zero in the same place the daylight
     * does and the two cannot drift apart. That coupling is what this checks: mutating the ambient
     * table's deepest entry away from zero fails the Abyss case here, which is the point.
     *
     * It matters MORE now than it did when these were lights. GI's composite is additive
     * (`final.frag:57`), so albedo drawn to `mainSurface` is not attenuated by the light map at
     * all — this ramp is the only thing standing between a band and the deep.
     */
    @Test
    fun `the shafts are full strength at the surface and exactly nothing in the abyss`()
    {
        val byZone = Zone.entries.map { DiveLighting.shaftDaylightForDepth(DepthBlend.zoneMidpoint(it)) }

        assertEquals(1f, byZone[Zone.SHALLOWS.ordinal], 1e-4f, "the shafts must be undimmed where the daylight is")
        assertEquals(0f, byZone[Zone.ABYSS.ordinal], 0f, "a shaft that reaches the Abyss must contribute EXACTLY nothing")
        assertTrue(
            byZone[Zone.TRENCH.ordinal] <= 0.1f,
            "the brief is 'gone by the Trench'; it is still at ${byZone[Zone.TRENCH.ordinal]} of full there"
        )
        for (i in 1 until byZone.size)
        {
            assertTrue(
                byZone[i] < byZone[i - 1],
                "the ramp is not monotone: ${Zone.entries[i]} (${byZone[i]}) is not below " +
                "${Zone.entries[i - 1]} (${byZone[i - 1]})"
            )
        }
    }

    /**
     * THE OTHER HALF OF THE GUARD RAIL, WHICH IS GEOMETRY AND NOT ARITHMETIC.
     *
     * The ramp above only reaches exactly zero at the Abyss's own midpoint, 135 m. Between 90 and
     * 135 it is small but not nothing, and "small times a warm colour on near-black water" is
     * still a lift a frame mean can measure. `f2f2eaa` got its Abyss +0.00% because its shafts
     * were not on screen at 140 m, not because of its ramp — an accident of five hand-placed
     * lengths. This makes it deliberate: the strips stop at `END_DEPTH_METRES`, and the camera at
     * 140 m starts at 116 m, so nothing is submitted at all.
     *
     * The tail is asserted with it, because a geometric cut with no fade is a hard horizontal line
     * across the water, which is a worse artefact than anything this effect adds.
     */
    @Test
    fun `the overlay has stopped being drawn before the abyss can see it`()
    {
        val abyssTop = Zone.ABYSS.minDepth
        assertTrue(
            LightShafts.END_DEPTH_METRES < abyssTop,
            "the overlay reaches ${LightShafts.END_DEPTH_METRES} m, into the Abyss at $abyssTop m"
        )

        // The deepest strip must not reach past the declared end either — the strip table and the
        // end depth are two numbers that have to agree, and only one of them is in the doc.
        val deepest = LightShafts.stripTopDepth(LightShafts.STRIP_COUNT - 1) + LightShafts.stripHeight()
        assertEquals(
            LightShafts.END_DEPTH_METRES, deepest, 1e-3f,
            "the strips end at $deepest m rather than at the declared ${LightShafts.END_DEPTH_METRES} m"
        )

        assertEquals(
            0f, LightShafts.tailFade(LightShafts.END_DEPTH_METRES), 0f,
            "the overlay is still at ${LightShafts.tailFade(LightShafts.END_DEPTH_METRES)} of full " +
            "opacity where its geometry stops — that edge is a hard horizontal line across the water"
        )
        assertEquals(1f, LightShafts.tailFade(0f), 1e-4f, "the tail must not dim the shafts at the surface")
        assertTrue(
            LightShafts.tailFade(LightShafts.END_DEPTH_METRES - LightShafts.TAIL_METRES * 0.5f) < 0.9f,
            "the tail does not actually fade — it steps at the very end"
        )

        // And the combined ramp really is zero everywhere the Abyss can be seen from.
        for (depth in listOf(LightShafts.END_DEPTH_METRES, 110f, 120f, 140f, 180f))
        {
            assertEquals(
                0f, DiveLighting.shaftRampForDepth(depth), 0f,
                "the shafts still contribute at $depth m"
            )
        }
    }

    /**
     * THE CLOCK CAN BE PINNED, WHICH IS WHAT MAKES A CAPTURE COMPARABLE.
     *
     * The owner asked for an animation, so this runs on the RENDER clock — the only one alive on
     * the attract screen, where `f2f2eaa`'s no-drift decision hurt most. The price is that a
     * captured frame stops being a function of the frame number, and the whole screenshot harness
     * (`HARNESS.md`) is built on it being one: a before/after pair would otherwise differ by
     * however much wall time each run took to reach frame 240.
     *
     * `PIN_ENV` is the escape hatch and this is the case that keeps it working. It cannot test the
     * environment variable itself without setting one for the JVM, so it tests the two properties
     * that make the hatch possible: the clock only ever moves through [LightShafts.advance], and
     * `advance` is a no-op while pinned. A phase read straight off `engine.data.deltaTime` in the
     * renderer would pass neither.
     */
    @Test
    fun `the animation clock only advances through advance, and not at all when pinned`()
    {
        val before = LightShafts.animationSeconds()
        LightShafts.advance(0.5f)
        val after = LightShafts.animationSeconds()

        if (LightShafts.isPinned())
        {
            assertEquals(
                before, after, 0f,
                "${LightShafts.PIN_ENV} is set but the clock advanced anyway, so a pinned capture " +
                "is not reproducible after all"
            )
        }
        else
        {
            assertEquals(before + 0.5f, after, 1e-4f, "advance() did not advance the clock")
            // Every band's phase has to move with it, or the pin would freeze the clock while the
            // shader kept animating from somewhere else.
            assertTrue(
                (0 until LightShafts.bandCount).all {
                    abs(LightShafts.phase(it) - (LightShafts.driftRate(it) * after)) < 10f
                },
                "a band's phase is not derived from the animation clock"
            )
            LightShafts.advance(-0.5f)
        }
    }

    /**
     * THE SHAFTS ARE WARM AND THE DIVER'S TORCH IS NOT, AND THE ORDERING IS THE REQUIREMENT.
     *
     * The sky above this water is a sunset, and `f2f2eaa`'s shafts were `(0.66, 0.86, 1)` — a cold
     * daylight blue, which under a warm sky reads as two unrelated pictures. Only the ordering is
     * pinned, in both directions: the shafts must be warm-biased and the torch must stay
     * cold-biased, because the second half is the older constraint (two lights the same colour are
     * one light with a gap in it) and a later warming of the torch would quietly undo this one
     * without touching it.
     *
     * The alpha is asserted with the colour because it now carries the effect's LEVEL — it is the
     * peak opacity a band reaches, and `mainSurface` composites with normal alpha blending, so at
     * 1 the bands would replace the water outright rather than haze it.
     */
    @Test
    fun `the god rays are warm, translucent, and not the colour of the torch`()
    {
        assertTrue(
            DiveLighting.shaftLight.red > DiveLighting.shaftLight.blue * 1.5f,
            "the god rays are ${DiveLighting.shaftLight.red} red against " +
            "${DiveLighting.shaftLight.blue} blue — under a sunset sky a shaft is the light that " +
            "has travelled through the least water, so it is the warmest thing in the frame"
        )
        assertTrue(
            DiveLighting.diverLight.blue > DiveLighting.diverLight.red * 1.5f,
            "the torch has stopped being cold, so it is no longer distinguishable from the shafts"
        )
        assertTrue(
            DiveLighting.shaftLight.alpha in 0.02f..0.75f,
            "the bands peak at ${DiveLighting.shaftLight.alpha} opacity. This is albedo under " +
            "NORMAL alpha blending, not a light, so the peak is literally how much of what is " +
            "behind a crest gets replaced: above 0.75 a pearl or the diver passing through one is " +
            "more than three-quarters erased, and the pearls are the game. Below 0.02 the water " +
            "at the surface — measured at 7.5 out of 255 against a same-build control pair — is " +
            "too dark for anything to show against"
        )
    }
}
