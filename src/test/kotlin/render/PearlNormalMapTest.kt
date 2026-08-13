package render

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHETHER A PEARL LOOKS LIKE A BEAD IS A SCREENSHOT QUESTION, and that is said here rather than
 * papered over with assertions that cannot fail. The measurement is in this task's report and in
 * [PearlNormalMap]'s class doc: the torch-facing half of a pearl went from -0.3% to +10.6% of the
 * far half at 140 m.
 *
 * What IS assertable, and is exactly the set of things that would break silently:
 *
 *  - the generated normal is the SAME FORMULA the iridescence shader draws the pearl with, read
 *    out of the shader's own source rather than remembered — two copies of a hemisphere is the
 *    price of a `BatchRenderer` drawing to one surface, and the price of the second copy drifting
 *    is a lit shape that is not the drawn shape;
 *  - the coverage contour the engine actually reads (`normal_map.frag:57`'s `alpha < 0.5`) is the
 *    pearl's own silhouette and not some smaller circle;
 *  - the encoding round-trips through `(n + 1) / 2` — an off-by-one-step bias here tilts every
 *    normal in the game's only round object;
 *  - there IS a terminator: with a real hemisphere and GI's own light elevation, the side facing
 *    away from a light goes dark. A flat normal, or one flattened by a stray scale, would leave
 *    every pearl uniformly lit, which is the defect this file was written to fix and which no
 *    single screenshot distinguishes from "the torch happens to be far away".
 */
class PearlNormalMapTest
{
    /**
     * THE TWO COPIES OF THE HEMISPHERE MUST BE ONE FORMULA.
     *
     * `shaders/iridescence.frag` builds the pearl's drawn shape from `p`, `r` and
     * `z = sqrt(max(1 - r * r, 0))`; [PearlNormalMap.normalAt] builds the LIT shape from the same
     * three lines in Kotlin. They cannot share code — one is a fragment shader on `main`, the
     * other is a texture on `gi_normal_map` — so what is checked is that they still agree
     * numerically, at radii either side of the rim, against the shader source ON DISK. Editing
     * the shader's normal without editing this file (or the reverse) fails the build.
     *
     * The shader text is parsed rather than trusted: if that line is ever rewritten into a form
     * this cannot find, the test fails loudly instead of quietly checking nothing.
     */
    @Test
    fun `the generated normal is the iridescence shader's own hemisphere`()
    {
        val shader = File("src/main/resources/shaders/iridescence.frag").readText()
        assertTrue(
            Regex("""float z = sqrt\(max\(1\.0 - r \* r, 0\.0\)\);""").containsMatchIn(shader),
            "iridescence.frag no longer builds z as sqrt(max(1 - r*r, 0)) — the pearl's drawn " +
            "shape and PearlNormalMap's lit shape have parted company and this test can no " +
            "longer tell you which is right"
        )
        assertTrue(
            Regex("""vec3 n = vec3\(p, z\);""").containsMatchIn(shader),
            "iridescence.frag no longer builds its normal as vec3(p, z)"
        )

        val out = FloatArray(3)
        for (nx in listOf(-1f, -0.7f, -0.25f, 0f, 0.25f, 0.7f, 0.99f))
            for (ny in listOf(-0.9f, -0.4f, 0f, 0.4f, 0.9f))
            {
                PearlNormalMap.normalAt(nx, ny, out)
                val r = hypot(nx, ny)
                val z = sqrt(maxOf(1f - r * r, 0f))
                assertEquals(nx, out[0], 1e-6f, "x at ($nx, $ny)")
                // UP TO A NEGATED Y, which is a requirement and not a discrepancy. The shader's
                // normal feeds its own interference maths in its own uv space; this one feeds GI,
                // whose consumer takes the OpenGL convention that every normal map baked from the
                // artist's exports already uses. Measured, not argued — see PearlNormalMap.normalAt
                // for the two-build probe (the negation makes a pearl's shading track the torch
                // 2.5x more strongly). Asserting equality here encoded an assumption.
                assertEquals(-ny, out[1], 1e-6f, "y at ($nx, $ny) is not the shader's y, negated")
                assertEquals(z, out[2], 1e-6f, "z at ($nx, $ny) is not the shader's sqrt(1 - r^2)")

                // And it is a UNIT vector wherever the disc is, because `normal_map.frag`
                // normalises what it decodes and a shorter one would simply be renormalised into
                // a different direction.
                if (r <= 1f)
                    assertEquals(
                        1f, hypot(hypot(out[0], out[1]), out[2]), 1e-5f,
                        "the normal at ($nx, $ny) is not unit length"
                    )
            }
    }

    /**
     * THE SILHOUETTE THE ENGINE ACTUALLY READS IS THE 0.5 ALPHA CONTOUR, and it must be the
     * pearl's own.
     *
     * `normal_map.frag:57` is `if (normal.a < 0.5) discard;`, and a discarded fragment keeps
     * `gi_normal_map`'s background — `Color(0.5, 0.5, 1)`, the flat normal. So the alpha ramp
     * decides where the bead stops and flat water starts. It carries `iridescence.frag`'s own
     * `EDGE_SOFTNESS`, which puts that contour at `1 - EDGE_SOFTNESS / 2`; anything materially
     * smaller would leave a lit rim that is drawn as pearl and shaded as background.
     */
    @Test
    fun `the coverage contour the shader reads is the pearl's own silhouette`()
    {
        val shader = File("src/main/resources/shaders/iridescence.frag").readText()
        val declared = Regex("""const float EDGE_SOFTNESS = ([0-9.]+);""").find(shader)
            ?.groupValues?.get(1)?.toFloat()
        assertEquals(
            declared, PearlNormalMap.EDGE_SOFTNESS,
            "iridescence.frag softens its silhouette over $declared of the radius and " +
            "PearlNormalMap over ${PearlNormalMap.EDGE_SOFTNESS} — the drawn pearl and the lit " +
            "pearl are different circles"
        )

        assertTrue(PearlNormalMap.coverageAt(0f) > 0.99f, "the middle of the pearl is not covered")
        assertTrue(
            PearlNormalMap.coverageAt(1f - PearlNormalMap.EDGE_SOFTNESS) > 0.99f,
            "the coverage has started falling before the ramp does"
        )
        assertTrue(PearlNormalMap.coverageAt(1f) < 0.01f, "the quad's corners are covered")

        // The contour itself, bracketed rather than solved, so it fails whichever way the ramp is
        // moved or narrowed.
        assertTrue(
            PearlNormalMap.coverageAt(1f - PearlNormalMap.EDGE_SOFTNESS * 0.75f) > 0.5f &&
            PearlNormalMap.coverageAt(1f - PearlNormalMap.EDGE_SOFTNESS * 0.25f) < 0.5f,
            "the alpha = 0.5 contour is not within the edge ramp, so the lit silhouette is a " +
            "different size from the drawn one"
        )
    }

    /**
     * THE ENCODING, WHICH IS THE HALF THAT FAILS SILENTLY AND UNIFORMLY.
     *
     * `normal_map.frag:61` decodes with `normal.xyz * 2.0 - 1.0`, so a texel has to be
     * `(n + 1) / 2` in 0..255. Get it wrong and nothing crashes and nothing is obviously missing:
     * every pearl in the game is simply lit from a direction nobody chose. Checked as a round
     * trip through the byte, at the two ends and at zero, where an unsigned/signed byte confusion
     * and an off-by-a-half-step both show up.
     */
    @Test
    fun `a normal component round-trips through the byte the shader decodes`()
    {
        listOf(-1f to 0, 0f to 128, 1f to 255).forEach { (component, expected) ->
            assertEquals(
                expected, PearlNormalMap.encode(component).toInt() and 0xFF,
                "encoding $component gave the wrong byte — normal_map.frag decodes with * 2 - 1"
            )
        }

        for (step in 0..20)
        {
            val component = -1f + step * 0.1f
            val decoded = (PearlNormalMap.encode(component).toInt() and 0xFF) / 255f * 2f - 1f
            // Half a byte step in the DECODED range, which spans 2.0 — i.e. the tightest bound
            // rounding to a byte allows, plus a float epsilon. A systematic half-step bias (the
            // classic `* 255` versus `* 254` slip) is exactly this size and would fail here.
            assertTrue(
                abs(decoded - component) <= 1f / 255f + 1e-6f,
                "encoding $component and decoding it back gives $decoded"
            )
        }
    }

    /**
     * THE PROPERTY THE WHOLE FILE EXISTS FOR: **a pearl has a lit side and a dark side.**
     *
     * [PearlNormalMap.litWeight] is `radiance_cascades.frag:289-290` transcribed, so this is a
     * statement about what GI will actually do with the texture rather than about the texture.
     * Two halves, and both are the failure that has already happened once:
     *
     *  - the side FACING the light is fully lit and the side facing away is fully dark, across
     *    most of the disc. A flat normal map (the state before this file) gives `dot = z = 1`
     *    everywhere and both sides come out at 1.
     *  - the CENTRE is lit from every direction, because its normal faces the camera. Without
     *    this the fix would have been "a pearl with a bite out of it" rather than a bead.
     *
     * The terminator's radius is asserted as a bound rather than solved: what matters is that a
     * clear majority of the pearl's AREA participates, which is what makes the shading read at 44
     * screen pixels across.
     */
    @Test
    fun `the half of a pearl facing the light is lit and the half facing away is dark`()
    {
        // The light is off to the +x side: a fragment gathers it along rayDir = (1, 0).
        val towards = 1f to 0f

        val centre = PearlNormalMap.litWeight(0f, 0f, towards.first, towards.second)
        assertTrue(
            centre > 0.9f,
            "the middle of a pearl is only $centre lit — its normal faces the camera, so it must " +
            "be lit whichever side the torch is on, or the bead reads as a disc with a bite out of it"
        )

        var darkFrom = Float.MAX_VALUE
        var radius = 0.05f
        while (radius < 1f)
        {
            val near = PearlNormalMap.litWeight(radius, 0f, towards.first, towards.second)
            val far = PearlNormalMap.litWeight(-radius, 0f, towards.first, towards.second)
            assertTrue(
                near >= far,
                "at radius $radius the side facing the light keeps $near of the arriving radiance " +
                "and the side facing away keeps $far — the pearl is lit from the wrong side"
            )
            if (far <= 0f && darkFrom == Float.MAX_VALUE) darkFrom = radius
            radius += 0.01f
        }

        assertTrue(
            darkFrom < 0.5f,
            "the far side of a pearl only goes dark beyond radius $darkFrom, i.e. over " +
            "${((1f - darkFrom * darkFrom) * 100).toInt()}% of its area — the normal map is " +
            "present but nearly inert, which is what a flattened hemisphere or a stray " +
            "normalScale looks like"
        )

        // Vertical too, and in the same sense: a y that was flipped anywhere between the buffer's
        // row order and the shader's decode would light the correct side left-to-right and the
        // wrong side top-to-bottom, which averages into "the effect is weak" rather than into a
        // visible sign error.
        assertTrue(
            PearlNormalMap.litWeight(0f, 0.6f, 0f, 1f) > PearlNormalMap.litWeight(0f, -0.6f, 0f, 1f),
            "a pearl is lit from the wrong side vertically"
        )
    }

    /**
     * THE TEXTURE IS USELESS IF THE DRAW SITE DRIFTS, AND NOTHING ELSE HERE WOULD NOTICE.
     *
     * A normal-mapped object is ONE world rect submitted to TWO surfaces (`CLAUDE.md`), and the
     * second call's arguments must be a COPY of the first's rather than a second derivation — the
     * failure `6ea1f53` shipped. For a pearl the two are `IridescenceRenderer.draw` on `main` and
     * `NormalMapRenderer.drawNormalMap` on `gi_normal_map`, and there are two ways to break it
     * that no other test in this repo sees: delete the normal draw (every pearl silently goes back
     * to being a flat quad, which is precisely the defect this file fixes and which looks like
     * "the torch is far away" in any single frame), or hand it `Framing.PEARL_SIZE_METRES` instead
     * of the `equalAreaQuad` the disc is actually drawn at — a 12% size difference that would put
     * the lit sphere inside the drawn one with a flat-normal ring around it.
     *
     * Asserted on the source, for `AnglerfishDisguiseTest`'s reason: `drawPearlSurface` is private,
     * needs a `Surface`, and what is being compared is two argument lists rather than a result.
     */
    @Test
    fun `the pearl's normal map is the same world rect as the pearl`()
    {
        val body = drawPearlSurfaceBody()
        val albedo = argumentsOf("iridescence.draw(", body)
        val normal = argumentsOf("drawNormalMap(", body)

        // The albedo call is (centreX, depth, quad, quad, material); the normal call is
        // (texture, centreX, depth, quad, quad, angle, origin, origin). So the four numbers that
        // place the rect are the albedo's first four and the normal's second through fifth.
        assertEquals(
            albedo.take(4), normal.drop(1).take(4),
            "DiveRenderer.drawPearlSurface submits a different rect to gi_normal_map than it draws " +
            "the pearl at: albedo $albedo against normal $normal. The lit sphere and the drawn " +
            "sphere have to be the same circle"
        )
        assertTrue(
            normal.first().startsWith("PearlNormalMap."),
            "the pearl's normal draw is not passing PearlNormalMap's generated hemisphere, it is " +
            "passing ${normal.first()}"
        )
    }

    /** The text of `private fun drawPearlSurface`, comments stripped. */
    private fun drawPearlSurfaceBody(): String
    {
        val source = File("src/main/kotlin/render/DiveRenderer.kt").readText()
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")
        val start = source.indexOf("private fun drawPearlSurface")
        assertTrue(start >= 0, "no `private fun drawPearlSurface` any more — re-read this test")
        val rest = source.substring(start + 1)
        val end = Regex("\\n    (private|internal|fun|val|const) ").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * The top-level arguments of the first [call] in [body], split on commas at nesting depth 1 —
     * balanced rather than "up to the next `)`", because the argument lists contain calls of their
     * own (`PearlNormalMap.normals()`) and a naive scan would compare only what precedes them.
     */
    private fun argumentsOf(call: String, body: String): List<String>
    {
        val start = body.indexOf(call)
        assertTrue(start >= 0, "expected a `$call` in drawPearlSurface:\n$body")
        var i = start + call.length
        var depth = 1
        val args = mutableListOf<String>()
        val current = StringBuilder()
        while (i < body.length && depth > 0)
        {
            val c = body[i]
            when
            {
                c == '(' -> { depth++; current.append(c) }
                c == ')' -> { depth--; if (depth > 0) current.append(c) }
                c == ',' && depth == 1 -> { args += current.toString().trim(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        assertTrue(depth == 0, "unterminated `$call` in drawPearlSurface")
        if (current.isNotBlank()) args += current.toString().trim()
        return args.map { it.replace(Regex("\\s+"), " ") }
    }

    /**
     * The buffer is what actually reaches the GPU, so it is checked rather than only the two pure
     * functions it is built from: the right size for an RGBA8 texture of [PearlNormalMap.TEXELS]
     * square, and — the part that decides which way up the bead is lit — the row order.
     *
     * Buffer row 0 is `v = 0` is `vertexPos.y = 0`, which the quad's offset
     * `(vertexPos - origin) * size` puts at the TOP of the pearl on screen (world +y is depth).
     * So the first row must carry the negative y normals. `LightEmitter.buildPixels` established
     * the same order and could not test it — its disc is radially symmetric.
     */
    @Test
    fun `the pixel buffer is filled top row first, which is what decides the lit side vertically`()
    {
        val pixels = PearlNormalMap.buildPixels()
        assertEquals(
            PearlNormalMap.TEXELS * PearlNormalMap.TEXELS * 4, pixels.remaining(),
            "the buffer is not one RGBA8 texel per position"
        )

        fun greenAt(row: Int, column: Int): Int =
            pixels.get((row * PearlNormalMap.TEXELS + column) * 4 + 1).toInt() and 0xFF

        // THE SIGNS HERE WERE THE OTHER WAY ROUND UNTIL THE CONVENTION WAS MEASURED. This test is
        // the one that decides which way a pearl is lit vertically, so it is also the test that was
        // confidently wrong: it asserted green LOW on the first row, matching `iridescence.frag`'s
        // own normal, which is the wrong consumer's convention. GI takes the OpenGL one that every
        // normal map baked from the artist's exports already uses — green HIGH where a surface
        // faces up-screen. See `PearlNormalMap.normalAt` for the two-build probe that settled it.
        val middle = PearlNormalMap.TEXELS / 2
        assertTrue(
            greenAt(0, middle) > 223,
            "the first row of the buffer does not face UP-SCREEN (green high) — a pearl's top half " +
            "will be lit when the light is below it, and the hemisphere reads as a hollow"
        )
        assertTrue(
            greenAt(PearlNormalMap.TEXELS - 1, middle) < 32,
            "the last row of the buffer does not face DOWN-SCREEN (green low)"
        )
        assertTrue(
            abs(greenAt(middle, middle) - 128) <= 2,
            "the middle of the texture is not a flat y (${greenAt(middle, middle)}) — the " +
            "hemisphere is off centre"
        )
    }
}
