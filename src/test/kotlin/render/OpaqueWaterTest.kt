package render

import dive.Tuning
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of `OpaqueWaterEffect` that can be checked without a GL context: WHERE the gate is, and
 * the conversion that puts it on the screen.
 *
 * What cannot be checked here is whether the black discs are gone — that needed a real window grab
 * and it got one. What CAN be checked is every way the gate could be in the wrong place, and each
 * of those is silent:
 *
 *  - **The uv flip.** `worldPosToScreenPos` returns screen pixels with y DOWN; the full-frame
 *    vertex shader's `uv.y` is 1 at the TOP. Inverted, this effect would force the SKY opaque and
 *    leave the water untouched — which paints the sunset out with black, and would look in a still
 *    frame like the sky had simply stopped being drawn.
 *  - **The clamps.** They are not defensive: "gate off the top of the frame" is the whole of a
 *    dive after the first few seconds, and it must mean "restore everything".
 *  - **The depth.** A gate that a wave trough can reach hardens the meniscus into a hard line, at
 *    one x, at one phase, in one frame out of hundreds. That is not findable by looking.
 *
 * The depth cases below are stated as RELATIONSHIPS against the wave table, `Sky` and
 * `WaterSurface`, never against a transcribed number, so re-tuning an amplitude moves the gate
 * with it or fails the build.
 */
class OpaqueWaterTest
{
    /**
     * The wave's world depth at [x] and [seconds], read out of the packed uniform exactly as
     * `water.frag` reads it — the same helper, and the same reasoning, as `WaterSurfaceTest`'s.
     * Duplicated deliberately rather than shared: this test is asserting something about the gate,
     * and it should be reading the wave the way the SHADER does, not the way another test does.
     */
    private fun waveDepth(x: Float, seconds: Float): Float
    {
        var depth = Tuning.SURFACE_DEPTH
        val w = WaterSurface.waveUniform
        for (i in WaterSurface.components.indices)
        {
            val o = i * WaterSurface.FLOATS_PER_COMPONENT
            depth -= w[o] * sin(w[o + 1] * x + w[o + 2] * seconds + w[o + 3])
        }
        return depth
    }

    // ---- Where the gate sits -------------------------------------------------------------------

    /**
     * NO TROUGH CAN REACH THE GATE — swept over the wave AS THE SHADER READS IT, at 0.25 m and
     * 0.5 s (far finer than the 1.7 m shortest wavelength and the 4.9 s shortest period) and over
     * 240 m of x, wider than any panel this could ship on.
     *
     * This is THE safety property of the whole change. `water.frag`'s alpha is `coverage`, a
     * `smoothstep` one `fwidth` wide, so the boundary pixel — and only the boundary pixel — is
     * legitimately partial. If the gate could ever land on it, this effect would write alpha 1
     * into the anti-aliasing ramp and the waterline would come back as a hard stair-stepped edge.
     *
     * ## WHAT KILLS THIS AND WHAT DOES NOT, because half of it would otherwise be circular
     *
     * `AMPLITUDE_METRES` is a fold over `WaterSurface.components`, and `GATE_DEPTH` is that plus
     * the margin — so the clearance assertion below cannot be broken by RE-TUNING the sea. It is
     * broken by changing how `GATE_DEPTH` is derived (typing it as a literal, dropping the
     * amplitude term), which is the realistic edit and is what the case after this one guards
     * against directly.
     *
     * The first assertion is the non-circular half and it is why this sweep reads
     * [WaterSurface.waveUniform] rather than [WaterSurface.components]: the uniform is the copy the
     * GLSL actually sums, packed four floats at a time by index, and a packing that put the wave
     * number where the amplitude belongs would give the shader a sea an order of magnitude taller
     * than anything Kotlin believes in — with `AMPLITUDE_METRES`, and therefore the gate, still
     * reporting 0.31 m. That is the mutation this case exists for.
     */
    @Test
    fun `no wave trough at any x or any phase comes within the gate's margin of it`()
    {
        var deepestTrough = -Float.MAX_VALUE
        var t = 0f
        while (t < WaterSurface.WRAP_SECONDS)
        {
            var x = -120f
            while (x <= 120f)
            {
                val d = waveDepth(x, t)
                if (d > deepestTrough) deepestTrough = d
                x += 0.25f
            }
            t += 0.5f
        }

        assertTrue(
            deepestTrough <= Tuning.SURFACE_DEPTH + WaterSurface.AMPLITUDE_METRES,
            "the wave the SHADER sums reaches $deepestTrough m, past the " +
            "${Tuning.SURFACE_DEPTH + WaterSurface.AMPLITUDE_METRES} m bound the gate is derived " +
            "from — WaterSurface.waveUniform no longer packs what WaterSurface.components says it does"
        )
        assertTrue(
            OpaqueWaterEffect.GATE_DEPTH - deepestTrough >= OpaqueWaterEffect.GATE_MARGIN_METRES,
            "the deepest trough the wave table can produce is $deepestTrough m and the gate is at " +
            "${OpaqueWaterEffect.GATE_DEPTH} m — a clearance of ${OpaqueWaterEffect.GATE_DEPTH - deepestTrough} m, " +
            "less than the ${OpaqueWaterEffect.GATE_MARGIN_METRES} m the gate is supposed to keep. " +
            "The gate would be written into water.frag's one-pixel alpha ramp and the waterline " +
            "would harden into a stair-stepped edge"
        )
    }

    /**
     * THE GATE IS DERIVED FROM THE WAVE TABLE, not typed. Raising a component's amplitude has to
     * move the gate with it; a hardcoded 1.31 would silently stop clearing the sea.
     */
    @Test
    fun `the gate is the amplitude bound plus the margin, and nothing else`()
    {
        assertEquals(
            Tuning.SURFACE_DEPTH + WaterSurface.AMPLITUDE_METRES + OpaqueWaterEffect.GATE_MARGIN_METRES,
            OpaqueWaterEffect.GATE_DEPTH,
            1e-6f,
            "GATE_DEPTH is no longer the amplitude bound plus the margin — it must stay derived " +
            "from WaterSurface.components so a retuned sea cannot leave a gate the sea can reach"
        )
        assertTrue(
            OpaqueWaterEffect.GATE_MARGIN_METRES > 0f,
            "a zero or negative margin puts the gate on or above the deepest trough"
        )
    }

    /**
     * THERE IS PROVABLY NOTHING BEHIND `main` AT THE GATE, which is what makes forcing alpha to 1
     * a repair rather than an occlusion. [Sky.BOTTOM_DEPTH] is the depth past which `Sky.render`
     * issues no strips at all, and the `"sky"` surface's background is `Color.BLANK` — so below it
     * the only thing an eroded `main` pixel can reveal is the cleared backbuffer.
     *
     * Were the gate ABOVE that line, this effect would be painting over a band of real sunset that
     * the water's alpha ramp is supposed to blend against.
     */
    @Test
    fun `the gate is below the deepest depth the sky is drawn to`()
    {
        assertTrue(
            OpaqueWaterEffect.GATE_DEPTH > Sky.BOTTOM_DEPTH,
            "the gate at ${OpaqueWaterEffect.GATE_DEPTH} m is at or above Sky.BOTTOM_DEPTH " +
            "(${Sky.BOTTOM_DEPTH} m), so forcing alpha to 1 there would hide sky that is actually " +
            "drawn behind the water rather than only closing holes onto the cleared backbuffer"
        )
    }

    /**
     * ...AND IT IS INSIDE THE WATER QUAD, where `water.frag` itself writes `alpha = coverage = 1`.
     *
     * That upgrades "everything below the waterline is opaque" from a belief about the scene to a
     * property of a shader we own. Past [WaterSurface.QUAD_BOTTOM_DEPTH] the opacity comes from
     * `DiveRenderer.drawZoneBands` instead, which is just as opaque — but the quad is the stronger
     * citation, so the gate is kept inside it.
     */
    @Test
    fun `the gate is inside the band where the water shader itself writes alpha 1`()
    {
        assertTrue(
            OpaqueWaterEffect.GATE_DEPTH < WaterSurface.QUAD_BOTTOM_DEPTH,
            "the gate at ${OpaqueWaterEffect.GATE_DEPTH} m has fallen past the water quad's bottom " +
            "edge (${WaterSurface.QUAD_BOTTOM_DEPTH} m), so nothing this project owns guarantees " +
            "the fragments at the gate are opaque"
        )
    }

    // ---- The screen conversion -----------------------------------------------------------------

    /**
     * THE FLIP. Screen y runs DOWN from 0 at the top (the surface's projection is
     * `ortho(0, width, height, 0)`); `uv.y` runs UP from 0 at the bottom (the full-frame vertex
     * shader maps NDC +1 to texCoord 1). The conversion is that one subtraction and getting it
     * backwards forces the SKY opaque instead of the water.
     */
    @Test
    fun `screen y is flipped into uv, top of frame to 1 and bottom to 0`()
    {
        assertEquals(1f, OpaqueWaterEffect.gateUv(0f, 1000f), 1e-6f, "the top of the frame is uv.y 1")
        assertEquals(0f, OpaqueWaterEffect.gateUv(1000f, 1000f), 1e-6f, "the bottom of the frame is uv.y 0")
        assertEquals(0.5f, OpaqueWaterEffect.gateUv(500f, 1000f), 1e-6f, "the middle of the frame is uv.y 0.5")
        assertEquals(0.75f, OpaqueWaterEffect.gateUv(250f, 1000f), 1e-6f, "a quarter down the frame is uv.y 0.75")
    }

    /**
     * IT IS A FRACTION OF THE SURFACE, so the same world gate lands on the same part of the frame
     * on the dev window and on the booth panel. A conversion that leaked a pixel count would put
     * the gate at a different depth on every display, which is the failure mode `CLAUDE.md` spends
     * a section on and which no single screenshot can show.
     */
    @Test
    fun `the conversion is resolution independent`()
    {
        for (height in listOf(900f, 1080f, 1800f, 2160f))
            assertEquals(
                0.4f, OpaqueWaterEffect.gateUv(0.6f * height, height), 1e-5f,
                "the same fraction down a ${height}px surface must give the same uv"
            )
    }

    /**
     * THE CLAMPS ARE THE TWO ENDS OF A DIVE, not defensive padding.
     *
     * Off the TOP is the common case by a wide margin: the waterline leaves the frame within a
     * couple of seconds of the dive starting and does not come back until the ascent, and for all
     * of that time every fragment is below the waterline and must be restored. Off the BOTTOM is
     * the attract screen's opposite extreme, and it has to reach the shader's own no-op — 0, which
     * no `uv.y` is less than.
     */
    @Test
    fun `a gate off either end of the frame clamps to restore everything or nothing`()
    {
        assertEquals(1f, OpaqueWaterEffect.gateUv(-4000f, 1000f), 1e-6f, "a gate far above the frame must restore the whole frame")
        assertEquals(1f, OpaqueWaterEffect.gateUv(-1f, 1000f), 1e-6f, "a gate one pixel above the frame must restore the whole frame")
        assertEquals(0f, OpaqueWaterEffect.gateUv(1001f, 1000f), 1e-6f, "a gate one pixel below the frame must restore nothing")
        assertEquals(0f, OpaqueWaterEffect.gateUv(9000f, 1000f), 1e-6f, "a gate far below the frame must restore nothing")
    }

    /**
     * A DEGENERATE SURFACE OR A NON-FINITE CAMERA FAILS TO "CHANGE NOTHING", which is the same
     * choice `gate`'s 0 default makes. The engine rebuilds surfaces on a window or fullscreen
     * change, and a camera mid-resize has produced absurd values elsewhere in this project; the
     * alternative failure — a NaN uniform, which compares false against everything — happens to be
     * the same no-op, but only by luck, and luck is not what the booth should be running on.
     */
    @Test
    fun `a degenerate surface or a non-finite gate disables the effect rather than guessing`()
    {
        assertEquals(0f, OpaqueWaterEffect.gateUv(500f, 0f), 1e-6f, "a zero-height surface must disable the effect")
        assertEquals(0f, OpaqueWaterEffect.gateUv(500f, -1000f), 1e-6f, "a negative-height surface must disable the effect")
        assertEquals(0f, OpaqueWaterEffect.gateUv(500f, Float.NaN), 1e-6f, "a NaN height must disable the effect")
        assertEquals(0f, OpaqueWaterEffect.gateUv(Float.NaN, 1000f), 1e-6f, "a NaN screen y must disable the effect")
        assertEquals(0f, OpaqueWaterEffect.gateUv(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY), 1e-6f, "an infinite surface must disable the effect")
    }
}
