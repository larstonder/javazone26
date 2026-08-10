package render

import dive.Tuning
import dive.Zone
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The god rays. Three things about them are worth a build-time assertion, and none of them is
 * visible in a screenshot taken at the wrong depth:
 *
 *  - they hang from the SURFACE (this file's first case, which is really a check against
 *    `scene.vert`'s rotation convention);
 *  - they are GONE by the time the water is dark, which is the guard rail the whole feature has
 *    to live under (spec 11 and 6b: the Abyss being dark is the central mechanic);
 *  - their emitter puts its falloff in RGB and its silhouette in ALPHA, which is the exact
 *    opposite of every other light in the game and the one thing about them that looks like a
 *    mistake to a reader who knows `LightEmitter`'s rule and not its exception.
 */
class LightShaftsTest
{
    /**
     * `GiSceneRenderer.drawLight` takes a CENTRE-origin quad and an angle, and a shaft is defined
     * by where it meets the surface — so `LightShafts` has to convert one into the other, and
     * getting the conversion wrong hangs the shafts somewhere plausible-looking but wrong (up out
     * of the water, or sideways) in a way that is only obvious with the surface line on screen.
     *
     * This walks it back the other way, applying `scene.vert`'s own matrix to the quad's local
     * TOP-edge midpoint. The matrix is transcribed here rather than shared with the production
     * code on purpose: the property being asserted is "our derivation agrees with the SHADER",
     * and two callers of one helper would agree with each other while both being wrong.
     *
     * ```
     * scene.vert:101   offset = (vertexPos - vec2(0.5)) * adjustedSize * rotate(radians(angle))
     * scene.vert:50-55 mat2 rotate(a) { return mat2(c, s, -s, c); }
     * ```
     */
    @Test
    fun `every shaft's top edge lands on the water's surface, where it was anchored`()
    {
        for (i in 0 until LightShafts.count)
        {
            // The quad's local top-edge midpoint is (0.5, 0) in vertexPos, i.e. (0, -h/2) once
            // the centre origin is subtracted and the size applied.
            val (dx, dy) = rotateLikeSceneVert(0f, -LightShafts.length(i) * 0.5f, LightShafts.TILT_DEGREES)

            assertEquals(
                Tuning.SURFACE_DEPTH, LightShafts.centreDepth(i) + dy, 1e-3f,
                "shaft $i does not start at the surface — its quad's top edge is at depth " +
                "${LightShafts.centreDepth(i) + dy} m"
            )
            assertEquals(
                LightShafts.topAnchorX(i), LightShafts.centreX(i) + dx, 1e-3f,
                "shaft $i meets the surface at x ${LightShafts.centreX(i) + dx} rather than at " +
                "the ${LightShafts.topAnchorX(i)} it is anchored to — the tilt's sign or axis is wrong"
            )
        }
    }

    /**
     * THE GUARD RAIL, AS ARITHMETIC RATHER THAN AS A REMEMBERED CUTOFF.
     *
     * The Abyss being dark is the design's central mechanic, and a light that spans sixty metres
     * of water is exactly the kind of thing that erodes it a little at a time. The ramp is derived
     * from `DiveLighting`'s own ambient table (`ambient(d) / ambient(0)` — the inverse of what the
     * reverted rim light used), so the shafts reach zero in the same place the daylight does and
     * the two cannot drift apart. That coupling is what this checks: mutating the ambient table's
     * deepest entry away from zero fails the last case here, which is the point.
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
     * A SHAFT'S FALLOFF IS IN RGB AND A PEARL'S IS IN ALPHA, AND THE TWO MUST NOT BE MADE ALIKE.
     *
     * `LightEmitter`'s class doc states the rule at length: radiance is sampled where a ray HITS
     * an emitter, i.e. on its rim, so a colour ramp that reaches zero at the rim scales every
     * escaping ray to zero — shape belongs in alpha. That rule is about a light seen from OUTSIDE.
     * A shaft is the one light here the player looks at the INSIDE of, and a probe inside an
     * emitter samples its own texel, so the interior IS the texture's RGB. Building the shaft the
     * way the pearl is built gives a flat-topped bar with a hard rim; building the pearl the way
     * the shaft is built gives a light that casts nothing.
     *
     * So the two are asserted to be opposites, channel for channel, rather than each being
     * asserted against a shape of its own — which is the only form of this that would still fail
     * if someone "unified" them.
     */
    @Test
    fun `the shaft emitter carries its falloff in RGB while the pearl emitter carries it in alpha`()
    {
        val spine = LightEmitter.shaftRgbAt(0f, 0f)
        val flank = LightEmitter.shaftRgbAt(0.6f, 0f)

        assertTrue(
            flank < spine * 0.5f,
            "the shaft's RGB does not fall away from its spine ($spine at the centre, $flank at " +
            "0.6 of the half-width) — with a flat RGB it is a bar of light, not a shaft"
        )
        assertEquals(
            LightEmitter.alphaAt(0f, 0f), LightEmitter.alphaAt(0.6f, 0f), 0f,
            "this case is written as the OPPOSITE of the pearl emitter, whose RGB is flat white " +
            "and whose falloff is in alpha. If the pearl's alpha has stopped being the flat part " +
            "of the comparison, re-read both — the pearl's alpha varies and its RGB does not"
        )
        assertTrue(
            LightEmitter.alphaAt(0f, 0f) > LightEmitter.alphaAt(0.9f, 0f),
            "the pearl emitter's alpha must still be where ITS falloff lives"
        )
    }

    /**
     * THE ALPHA CEILING IS A CONTRACT WITH `final.frag`, AND BOTH BOUNDS ARE THE ENGINE'S.
     *
     * ```
     * scene.frag:70     if (texColor.a < 0.5) discard;     // and jfa_seed.frag, which must agree
     * final.frag:32     bool isOccluder = scene.a > 0.8;
     * ```
     *
     * Below 0.5 the shaft is not drawn at all. Above 0.8 the engine treats the water inside it as
     * a SURFACE and replaces the lighting there with `light *= sourceIntensity` — which, because
     * the depth ramp drives that intensity below 1, made each shaft darken whatever it contained
     * in proportion to how deep it reached. The window between the two is the whole reason this
     * constant exists, and neither bound is ours to move.
     */
    @Test
    fun `the shaft's alpha stays inside the window between the engine's discard and its occluder test`()
    {
        assertTrue(
            LightEmitter.SHAFT_ALPHA_CEILING > 0.5f,
            "at ${LightEmitter.SHAFT_ALPHA_CEILING} the shaft is below scene.frag's discard and is never drawn"
        )
        assertTrue(
            LightEmitter.SHAFT_ALPHA_CEILING < 0.8f,
            "at ${LightEmitter.SHAFT_ALPHA_CEILING} final.frag treats the water inside a shaft as the " +
            "shaft's own surface and relights it by the shaft's intensity, which the depth ramp " +
            "puts below 1 — so the shaft darkens what it contains"
        )

        // And the texture actually built must honour it: the ceiling is a clamp in shaftAlphaAt,
        // and a profile that peaked above it would put the brightest part of every shaft on the
        // wrong side of the occluder test while the flanks stayed on the right one.
        val peak = (-10..10).maxOf { i -> (-10..10).maxOf { j -> LightEmitter.shaftAlphaAt(i / 10f, j / 10f) } }
        assertTrue(
            peak <= LightEmitter.SHAFT_ALPHA_CEILING,
            "the built shaft texture reaches alpha $peak, above its own ceiling"
        )
    }

    /** GLSL `v * mat2(c, s, -s, c)`: column-major storage, row-vector product. @see the first case */
    private fun rotateLikeSceneVert(vx: Float, vy: Float, degrees: Float): Pair<Float, Float>
    {
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return Pair(vx * c + vy * s, -vx * s + vy * c)
    }
}
