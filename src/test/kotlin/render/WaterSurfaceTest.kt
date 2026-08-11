package render

import dive.Tuning
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT CANNOT BE TESTED HERE, SAID PLAINLY: whether the sea LOOKS like a sea. That needed a
 * framebuffer and a pair of eyes, and it got them — see this task's contact sheet. Nor can
 * anything here prove that `water.frag` evaluates the wave table the way [waveHeight] below does;
 * summing three sinusoids is the one part of this that only a rasteriser can check, and
 * `WaterShaderTest` gets as close as text can by reading the expression out of the GLSL.
 *
 * What IS provable without a GL context is everything that fails SILENTLY:
 *
 *  - the BAND. [WaterSurface.QUAD_TOP_DEPTH]/[WaterSurface.QUAD_BOTTOM_DEPTH] are what
 *    `DiveRenderer` reserves for the shader and where it starts its own zone bands. A wave that
 *    can leave that band is clipped into a dead-straight horizontal line at the quad's edge —
 *    on some frames, at some phases, for some x. Nothing about that shows in a still.
 *  - the WRAP. The cabinet runs unattended for two days and the wave animates on the render
 *    clock. An inexact wrap is a visible jump every four minutes; no wrap at all is a wave that
 *    quantises into steps late on day two, on a machine nobody is watching.
 *  - the CLOCK's guards, because `deltaTime` comes straight off the frame timer and a stalled
 *    frame has produced absurd values in this project before.
 */
class WaterSurfaceTest
{
    @AfterTest
    fun resetClock() = WaterSurface.unpin()

    /** The sum `water.frag` computes, built from the SAME packed uniform the renderer uploads. */
    private fun waveHeight(x: Float, seconds: Float): Float
    {
        var height = Tuning.SURFACE_DEPTH
        val w = WaterSurface.waveUniform
        for (i in WaterSurface.components.indices)
        {
            val o = i * WaterSurface.FLOATS_PER_COMPONENT
            height -= w[o] * sin(w[o + 1] * x + w[o + 2] * seconds + w[o + 3])
        }
        return height
    }

    /**
     * THE BAND HAS TO CONTAIN THE WAVE, at every x on the widest panel and every phase in the
     * cycle. Swept at 0.25 m and 0.5 s, which is far finer than the 1.7 m shortest wavelength and
     * the 4.9 s shortest period.
     *
     * Both margins are checked, and the top one is the one that bites: a crest that leaves the
     * quad is clipped flat against the quad's own edge, which reads as the sea having a ceiling.
     */
    @Test
    fun `the reserved band contains the wave at every x and every phase`()
    {
        // Wider than the widest panel this could ship on: 32:9 shows 106.7 m of half-width.
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        var t = 0f
        while (t < WaterSurface.WRAP_SECONDS)
        {
            var x = -120f
            while (x <= 120f)
            {
                val h = waveHeight(x, t)
                if (h < lowest) lowest = h
                if (h > highest) highest = h
                x += 0.25f
            }
            t += 0.5f
        }

        assertTrue(
            lowest > WaterSurface.QUAD_TOP_DEPTH,
            "a crest reaches $lowest, at or above the quad's top edge ${WaterSurface.QUAD_TOP_DEPTH} — it would be clipped into a straight line"
        )
        assertTrue(
            highest < WaterSurface.QUAD_BOTTOM_DEPTH,
            "a trough reaches $highest, at or below the quad's bottom edge ${WaterSurface.QUAD_BOTTOM_DEPTH} — the sky would show through the water"
        )
        // ...and the band is not absurdly larger than it needs to be either, or the shader is
        // rasterising water nobody can see and `Sky` is painting a gradient behind it.
        assertTrue(
            WaterSurface.QUAD_TOP_DEPTH > Tuning.SURFACE_DEPTH - 2f * WaterSurface.AMPLITUDE_METRES,
            "the top margin is more than the whole wave again"
        )
    }

    /**
     * THE AMPLITUDE BOUND IS REACHABLE, i.e. it is the real bound and not a guess with slack in
     * it. Without this, adding a fourth component and forgetting to widen the band would still
     * pass the test above for a while — until the phases happened to line up in front of a queue.
     *
     * The three components are incommensurate, so they never align exactly; 92% of the bound over
     * a full cycle is what the sweep actually finds, and it is enough to prove the bound is not
     * arbitrary.
     */
    @Test
    fun `the amplitude bound is the sum of the components and is nearly reached`()
    {
        assertEquals(
            WaterSurface.components.fold(0f) { s, c -> s + c.amplitudeMetres },
            WaterSurface.AMPLITUDE_METRES, 1e-5f,
            "the bound the band is derived from is not the sum of the amplitudes"
        )

        var worst = 0f
        var t = 0f
        while (t < WaterSurface.WRAP_SECONDS)
        {
            var x = -120f
            while (x <= 120f)
            {
                worst = maxOf(worst, abs(waveHeight(x, t) - Tuning.SURFACE_DEPTH))
                x += 0.25f
            }
            t += 0.5f
        }
        assertTrue(
            worst > 0.85f * WaterSurface.AMPLITUDE_METRES,
            "the wave only ever reaches $worst of its ${WaterSurface.AMPLITUDE_METRES} bound, so the bound has slack a new component could hide in"
        )
        assertTrue(worst <= WaterSurface.AMPLITUDE_METRES + 1e-4f, "the wave exceeded its own bound")
    }

    /**
     * THE WRAP IS EXACT — the property that makes a two-day unattended run possible.
     *
     * `WaterSurface.advance` folds the clock at [WaterSurface.WRAP_SECONDS] so a `Float` never
     * loses resolution. That fold is invisible only if the surface is in exactly the same shape
     * either side of it, which requires every component to have completed a WHOLE number of
     * cycles — hence `cyclesPerWrap` being an `Int` rather than a speed in metres per second.
     * Get it wrong and the sea jumps every four minutes, forever, on a machine nobody is watching.
     */
    @Test
    fun `the whole surface returns to exactly its starting shape after one wrap`()
    {
        var x = -120f
        while (x <= 120f)
        {
            for (t in listOf(0f, 3.7f, 41f, 199.5f))
                assertEquals(
                    waveHeight(x, t), waveHeight(x, t + WaterSurface.WRAP_SECONDS), 2e-3f,
                    "at x = $x, phase $t and phase ${t + WaterSurface.WRAP_SECONDS} differ — the clock's fold is a visible jump"
                )
            x += 0.37f     // deliberately not a divisor of any wavelength
        }
    }

    /** Every component must actually travel, and no two may share a wavelength. */
    @Test
    fun `the components are distinct and all of them move`()
    {
        val lengths = WaterSurface.components.map { it.wavelengthMetres }
        assertEquals(lengths.size, lengths.toSet().size, "two components share a wavelength, so they are one component with a bigger amplitude")
        WaterSurface.components.forEach {
            assertTrue(it.cyclesPerWrap != 0, "a component with no speed is a standing ripple, not a wave")
            assertTrue(it.amplitudeMetres > 0f, "a component with no amplitude costs a sine and draws nothing")
            assertTrue(it.wavelengthMetres > 0f, "a non-positive wavelength gives an infinite or negative wave number")
        }
        assertTrue(
            WaterSurface.components.any { it.cyclesPerWrap < 0 },
            "every component travels the same way, so the whole sea reads as a conveyor belt"
        )
    }

    /**
     * The wave number and the angular speed are where the 2*pi lives, and both are easy to get
     * wrong in a way that produces a plausible-looking but wrong sea: a missing 2*pi stretches
     * every wavelength by 6.3x, an inverted one shrinks it by the same.
     */
    @Test
    fun `a component's wave number and angular speed are the standard conversions`()
    {
        WaterSurface.components.forEach { c ->
            // One wavelength of travel in x must advance the sine by exactly one turn.
            assertEquals(
                (2.0 * Math.PI).toFloat(), c.waveNumber * c.wavelengthMetres, 1e-4f,
                "${c.wavelengthMetres} m of x does not advance the phase by one turn"
            )
            // One wrap must advance it by exactly `cyclesPerWrap` turns — this is the wrap.
            assertEquals(
                (2.0 * Math.PI * c.cyclesPerWrap).toFloat(),
                c.angularSpeed * WaterSurface.WRAP_SECONDS, 1e-3f,
                "the component does not complete a whole number of cycles per wrap"
            )
        }
    }

    /** The packed uniform is what the renderer actually uploads; a wrong order is a wrong sea. */
    @Test
    fun `the packed uniform carries every component in the order the shader reads it`()
    {
        assertEquals(
            WaterSurface.components.size * WaterSurface.FLOATS_PER_COMPONENT,
            WaterSurface.waveUniform.size,
            "the packed table is not one vec4 per component"
        )
        WaterSurface.components.forEachIndexed { i, c ->
            val o = i * WaterSurface.FLOATS_PER_COMPONENT
            assertEquals(c.amplitudeMetres, WaterSurface.waveUniform[o], 1e-6f, "component $i: amplitude is not first")
            assertEquals(c.waveNumber, WaterSurface.waveUniform[o + 1], 1e-6f, "component $i: wave number is not second")
            assertEquals(c.angularSpeed, WaterSurface.waveUniform[o + 2], 1e-6f, "component $i: angular speed is not third")
            assertEquals(c.phaseRadians, WaterSurface.waveUniform[o + 3], 1e-6f, "component $i: phase is not fourth")
        }
    }

    /** The clock advances, stays inside one wrap, and cannot run away over a two-day session. */
    @Test
    fun `the clock advances and stays bounded`()
    {
        WaterSurface.unpin()
        assertEquals(0f, WaterSurface.phaseSeconds, 1e-6f, "a fresh clock does not start at zero")

        WaterSurface.advance(0.25f)
        assertEquals(0.25f, WaterSurface.phaseSeconds, 1e-6f, "the clock did not advance by dt")

        // Two days at 60 fps, in coarse steps. What matters is only that it never leaves the wrap.
        repeat(2000) { WaterSurface.advance(1f) }
        assertTrue(
            WaterSurface.phaseSeconds >= 0f && WaterSurface.phaseSeconds < WaterSurface.WRAP_SECONDS,
            "the clock reached ${WaterSurface.phaseSeconds}, outside [0, ${WaterSurface.WRAP_SECONDS}) — a float there quantises the sea into steps"
        )
    }

    /**
     * A NaN or a negative frame time must not be able to take the whole sea with it. `deltaTime`
     * comes straight off the frame timer, and a window drag or a display-mode change has produced
     * absurd values elsewhere in this project; NaN in the phase makes every fragment's `sin`
     * NaN, and a NaN alpha is a hole in the frame rather than a wobble.
     */
    @Test
    fun `the clock refuses a non-finite or negative frame time`()
    {
        WaterSurface.unpin()
        WaterSurface.advance(1f)
        val before = WaterSurface.phaseSeconds
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.5f, 0f))
            WaterSurface.advance(bad)
        assertEquals(before, WaterSurface.phaseSeconds, 1e-6f, "a bad frame time moved the clock")
    }

    /**
     * THE PIN, which is what keeps captures reproducible — see `WaterSurface`'s clock note and
     * `HARNESS.md`. It must stop the clock dead, not merely set it: a pinned phase that then
     * drifted would make a before/after pair differ by the render time between the two runs,
     * which is the exact failure that "has measured nothing".
     */
    @Test
    fun `a pinned phase holds against any amount of advancing`()
    {
        WaterSurface.pin(17f)
        assertEquals(17f, WaterSurface.phaseSeconds, 1e-6f, "the pin did not take")
        repeat(100) { WaterSurface.advance(0.5f) }
        assertEquals(17f, WaterSurface.phaseSeconds, 1e-6f, "the pinned phase drifted, so two captures of one build cannot agree")
    }

    /**
     * A pin outside the cycle must land somewhere valid rather than reintroduce the float problem
     * the wrap exists to avoid. `EPT_WAVE_PHASE` is typed by a human at a shell prompt, so a
     * negative or absurd value is not hypothetical.
     *
     * Asserted through [WaterSurface.pin] and [WaterSurface.phaseSeconds] — the PRODUCTION path —
     * rather than through `wrapped` on its own. Mutation-testing the direct form found it vacuous:
     * `pin` could stop calling `wrapped` altogether and the test stayed green.
     */
    @Test
    fun `a pin is folded into the cycle`()
    {
        WaterSurface.pin(-1f)
        assertEquals(WaterSurface.WRAP_SECONDS - 1f, WaterSurface.phaseSeconds, 1e-3f, "a negative pin is not folded forward")

        WaterSurface.pin(WaterSurface.WRAP_SECONDS + 5f)
        assertEquals(5f, WaterSurface.phaseSeconds, 1e-3f, "a pin past one wrap is not folded")

        WaterSurface.pin(Float.NaN)
        assertEquals(0f, WaterSurface.phaseSeconds, 1e-6f, "a NaN pin is not rejected")

        for (raw in listOf(-1000f, 0f, 1e7f, WaterSurface.WRAP_SECONDS))
        {
            WaterSurface.pin(raw)
            val w = WaterSurface.phaseSeconds
            assertTrue(w >= 0f && w < WaterSurface.WRAP_SECONDS, "pin($raw) left the phase at $w, outside the cycle")
        }
    }
}
