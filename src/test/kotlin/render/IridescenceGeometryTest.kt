package render

import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE SHADER DRAWS A DISC IN A SQUARE QUAD, AND THAT SILENTLY REMOVED A FIFTH OF EVERY OBJECT.
 *
 * `iridescence.frag` discards everything outside the unit circle, so a pearl that used to be a
 * `fillRectCentred` square lost `1 - pi/4` = 21.5% of its drawn area the moment it moved to this
 * renderer. Two things depended on that area and neither is cosmetic:
 *
 *  - The world surface is multiplied by GI's light map and then run through a THRESHOLDED bloom.
 *    Measured against a same-build control pair that agreed to 0.001/255, the round mask took a
 *    16:9 abyss frame's mean from 14.60 to 7.41 out of 255 — with the pearls' EMITTERS
 *    completely untouched. "Changing how a pearl looks must not change what it emits" was
 *    satisfied and the abyss got twice as dark anyway, because bloom is not linear in area.
 *  - Every air-ring bubble shrinking by a fifth is the game's only air warning getting quieter.
 *
 * The compensation is a single exact factor, `2 / sqrt(pi)`, and this file is what stops it
 * being a magic number: the relationship it encodes ("the drawn area is unchanged") is
 * arithmetic and therefore provable without a GL context, which is more than can be said for
 * anything else about a shader.
 */
class IridescenceGeometryTest
{
    @Test
    fun `the grown quad draws a disc of exactly the area the square it replaced had`()
    {
        // Sizes chosen to span everything this renderer actually draws: a 1.2 m pearl, a 0.9 m
        // ring bubble, and that bubble at the 2.8x low-air gain. Nothing here is a pixel count.
        listOf(Framing.PEARL_SIZE_METRES, Hud.AIR_BUBBLE_SIZE_METRES, Hud.AIR_BUBBLE_SIZE_METRES * 2.8f)
            .forEach { square ->
                val quad = IridescenceRenderer.equalAreaQuad(square)
                val discArea = PI.toFloat() * (quad * 0.5f) * (quad * 0.5f)
                assertEquals(
                    square * square, discArea, square * square * 1e-5f,
                    "a $square m object drawn as a disc in a $quad m quad covers $discArea m^2, " +
                    "not the ${square * square} m^2 the square it replaced covered"
                )
            }
    }

    @Test
    fun `the scale grows the quad rather than shrinking it, and is the factor it claims to be`()
    {
        // The direction is the half that a sign or an inverted division would get wrong, and it
        // is the half a pure area equality above cannot catch on its own: dividing by 2/sqrt(pi)
        // instead of multiplying gives a disc SMALLER than the square, which is the regression
        // rather than the fix.
        assertTrue(
            IridescenceRenderer.EQUAL_AREA_DISC_SCALE > 1f,
            "the quad must GROW: an inscribed disc is smaller than its square, so matching the " +
            "area needs a bigger quad, not a smaller one"
        )
        assertEquals(1.1284f, IridescenceRenderer.EQUAL_AREA_DISC_SCALE, 1e-4f)
    }

    @Test
    fun `every object this shader draws is submitted through the equal-area quad`()
    {
        // A source scan, same instrument as DrawTest and AnglerfishDisguiseTest, because what
        // is under test is that no call site was MISSED — and a missed one is a fifth of an
        // object quietly gone, in a frame nobody would think to compare. Every `.draw(` on an
        // IridescenceRenderer must be handed a size that came from equalAreaQuad.
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                stripComments(file.readText())
                    .lineSequence()
                    .filter { Regex("\\biridescence\\.draw\\s*\\(").containsMatchIn(it) }
                    .map { "${file.path}: ${it.trim()}" }
                    .filterNot { it.contains("quad, quad") }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "these iridescence draws do not pass an equalAreaQuad size, so they draw a disc a " +
            "fifth smaller than the square they replaced: $offenders"
        )
    }

    /**
     * THE SECOND HALF OF THE EXPOSURE ARGUMENT, and it lives in the GLSL, so it is asserted
     * against the GLSL.
     *
     * Restoring the drawn AREA (above) is only sufficient if the shader also leaves the object's
     * mean albedo LUMINANCE alone. `iridescence.frag` composes
     * `base * (1 - amplitude) + film * 2 * luma * coverage`, where `film` averages 0.5 and the
     * layer is a NEUTRAL value scaled by the body's own luma. The mean then works out at
     * `luma * (1 - amplitude) + luma * amplitude` = `luma`, exactly the body's, at ANY
     * amplitude — which is what lets the amplitude stay a free art decision instead of needing
     * a light-budget re-measurement every time it is nudged.
     *
     * That derivation rests on exactly two things in the shader text, and an earlier version of
     * this test checked neither: it restated the formula in Kotlin and asserted the algebra,
     * which is an identity that cannot fail whatever the shader does. Deleted and replaced with
     * the two facts that are actually load-bearing:
     *
     *  1. The layer is scaled by `luma`, the body's own luminance. Drop that factor and the
     *     layer becomes an absolute brightness — every pearl, every bubble and anything drawn
     *     with this shader later would be lifted toward the same value regardless of its colour.
     *  2. `LUMA`'s three weights sum to 1. They are Rec. 709's, which do — but a set that does
     *     not (the common 0.299/0.587/0.114 written from memory sums to 1.000, while an
     *     unnormalised or truncated set does not) silently scales the layer, and with it the
     *     whole relit surface's exposure, by that sum.
     */
    @Test
    fun `the film layer is scaled by the body's own luminance, which is what preserves exposure`()
    {
        val frag = stripComments(
            IridescenceGeometryTest::class.java.getResource(IridescenceRenderer.FRAGMENT_SHADER)!!.readText()
        )

        assertTrue(
            Regex("rgb\\s*=\\s*vBase\\.rgb\\s*\\*\\s*\\(1\\.0\\s*-\\s*amplitude\\)\\s*\\+\\s*film\\s*\\*\\s*\\(\\s*2\\.0\\s*\\*\\s*luma\\s*\\*").containsMatchIn(frag),
            "iridescence.frag's composition must be `base * (1 - amplitude) + film * (2 * luma * " +
            "coverage)`. Without the `luma` factor the interference layer stops being " +
            "proportional to the body it sits on and becomes an absolute brightness added to " +
            "every object alike — an exposure change to the whole GI-relit world surface. Found:\n" +
            frag.lineSequence().filter { "rgb =" in it }.joinToString("\n")
        )

        assertTrue(
            Regex("float\\s+luma\\s*=\\s*dot\\(\\s*vBase\\.rgb\\s*,\\s*LUMA\\s*\\)").containsMatchIn(frag),
            "`luma` must be the draw colour's own luminance"
        )

        val weights = Regex("const\\s+vec3\\s+LUMA\\s*=\\s*vec3\\(([^)]*)\\)")
            .find(frag)
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().toFloat() }
            ?: error("iridescence.frag no longer declares a LUMA constant")

        assertEquals(
            1f, weights.sum(), 1e-4f,
            "LUMA's weights sum to ${weights.sum()}, not 1. The mean-luminance-preserving " +
            "cancellation is `luma * (1 - a) + a * luma * sum(LUMA)`, so any other sum scales " +
            "every iridescent object's mean brightness by it — silently, and worst exactly where " +
            "the GI multiply and the thresholded bloom are most non-linear"
        )
    }

    private companion object
    {
        fun stripComments(source: String): String = source
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }
}
