package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE LURE MUST BE INDISTINGUISHABLE FROM A PEARL, and that is a GAMEPLAY requirement, not a
 * cosmetic one. The design spec is explicit: in the Abyss, where pearls are the only light, you
 * cannot tell treasure from predator by looking — the tell is MOTION, never light. A player who
 * can spot the lure at a glance never gets eaten, and the abyss's entire risk stops existing.
 *
 * This did not need a test while both were three lines of "amber square, PEARL_SIZE_METRES":
 * two copies of a literal are hard to get wrong. It needs one now. A pearl has a MATERIAL, and a
 * material is a thing someone will tune — and if the lure keeps the previous parameters for even
 * one commit, or gains its own, the trap is over. Worse, nothing about that failure is visible
 * from a screenshot of a frame with no anglerfish in it, and the fish only spawns in the deep.
 *
 * ## Why a source scan
 *
 * The property is "these two draws are the SAME draw", and sameness of a draw call cannot be
 * asserted by running one: `DiveRenderer.drawAnglerfish` is private, needs a `DiveSim` with a
 * spawned fish, a `Camera` and a `Surface`, and the thing being compared is a rasterised
 * appearance. What CAN be asserted, exactly, is that both code paths funnel through one
 * function. Same instrument and same reasoning as `MainCameraOwnershipTest` and
 * `DrawTest.no production source draws a quad or a line`, both of which guard structural
 * properties whose violation is invisible without a real framebuffer.
 */
class AnglerfishDisguiseTest
{
    private val source = File(RENDERER).readText()
    private val lightingSource = File(LIGHTING).readText()

    @Test
    fun `the lure and a real pearl are drawn by the same function`()
    {
        val lure = bodyOf("drawAnglerfish")
        val pearls = bodyOf("drawPearls")

        assertTrue(
            SHARED_DRAW in lure,
            "DiveRenderer.drawAnglerfish must draw through $SHARED_DRAW — the same call a real " +
            "pearl goes through. Anything else is a second description of 'looks like a pearl', " +
            "and two descriptions can be edited apart. Found:\n$lure"
        )
        assertTrue(
            SHARED_DRAW in pearls,
            "DiveRenderer.drawPearls must draw through $SHARED_DRAW. Found:\n$pearls"
        )
    }

    @Test
    fun `the lure issues no drawing of its own`()
    {
        val lure = bodyOf("drawAnglerfish")

        // Any other draw verb in that method is either a second appearance for the fish or a
        // divergent copy of the pearl's. Both end the trap.
        val strays = DRAW_VERBS.findAll(lure)
            .map { it.value }
            .filterNot { it.startsWith(SHARED_DRAW) }
            .toList()

        assertEquals(
            emptyList(), strays,
            "DiveRenderer.drawAnglerfish draws something other than a pearl surface: $strays"
        )

        // And it must not name a material at all — choosing one here is how the lure acquires a
        // look of its own. The material belongs to the shared function, once.
        assertTrue(
            "IridescentMaterial" !in lure,
            "DiveRenderer.drawAnglerfish names a material. Whichever material a pearl uses is " +
            "the lure's material by construction, and the way to keep that true is for this " +
            "method not to have an opinion"
        )
    }

    @Test
    fun `the shared function is the only place a pearl surface is described`()
    {
        // Exactly one definition, and exactly two callers. A third caller is fine in principle
        // (something else may legitimately want to look like a pearl) but a SECOND definition is
        // the failure this whole file exists to prevent, so the count is pinned rather than
        // bounded — a deliberate change has to come here and say so.
        val definitions = Regex("private fun $SHARED_DRAW\\b").findAll(source).count()
        assertEquals(1, definitions, "there must be exactly one definition of $SHARED_DRAW")

        val calls = Regex("(?<!fun )\\b$SHARED_DRAW\\(").findAll(source).count()
        assertEquals(
            2, calls,
            "$SHARED_DRAW should be called twice — once for real pearls, once for the lure"
        )
    }

    /**
     * THE LURE IS ALSO A LIGHT, AND THE LIGHT IS THE HALF THAT MATTERS IN THE ABYSS.
     *
     * Everything above guards the pearl's SURFACE. In the Abyss the surface is barely the point:
     * ambient is effectively zero and what a player actually sees of either object is the glow it
     * casts, which comes from `DiveLighting`, not `DiveRenderer`. `drawPearlLights` and
     * `drawAnglerfishLight` cannot funnel through one function the way the two surface draws do —
     * one loops over `sim.pearls` and the other takes the single `sim.anglerfish` — so what is
     * pinned instead is that their `drawLight` ARGUMENT LISTS are the same text once the subject
     * is renamed. Emitter size, colour, cone and intensity curve are all in there.
     *
     * This gap was live and unguarded until the pearl's emitter was resized: the size and the
     * intensity are written out at both call sites, and changing one and not the other would have
     * given the lure a glow of its own with nothing to say so.
     */
    @Test
    fun `the lure emits exactly the light a pearl of the same depth emits`()
    {
        val lure = drawLightArgumentsIn(bodyOf("drawAnglerfishLight", lightingSource)).replace("fish.", "SUBJECT.")
        val pearl = drawLightArgumentsIn(bodyOf("drawPearlLights", lightingSource)).replace("pearl.", "SUBJECT.")

        assertEquals(
            pearl, lure,
            "DiveLighting.drawAnglerfishLight passes drawLight different arguments than " +
            "drawPearlLights does. In the Abyss the glow IS the disguise, so any difference — " +
            "emitter size, intensity, cone, colour — is a tell the player can learn"
        )
    }

    /**
     * The text between `drawLight(` and its MATCHING `)`, whitespace-collapsed. Balanced rather
     * than "up to the next `)`" because the argument list contains calls of its own
     * (`pearlIntensityForDepth(pearl.depth)`), and a naive scan would compare only the arguments
     * before the first of them — silently passing on a divergent `coneAngle` or `radius`.
     *
     * One normalisation, and it is spelled out because a normalisation is a hole in the check:
     * the pearl loop hoists `LightEmitter.emitter()` above the loop and the lure calls it inline,
     * which is a difference in where the texture is FETCHED and not in which texture is passed.
     */
    private fun drawLightArgumentsIn(body: String): String
    {
        val start = body.indexOf("drawLight(")
        assertTrue(start >= 0, "expected a drawLight( call:\n$body")
        var depth = 0
        var i = start + "drawLight(".length - 1
        val open = i + 1
        while (i < body.length)
        {
            if (body[i] == '(') depth++
            if (body[i] == ')' && --depth == 0) break
            i++
        }
        assertTrue(i < body.length, "unterminated drawLight( in:\n$body")
        return body.substring(open, i)
            .replace("LightEmitter.emitter()", "emitter")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * The text of `private fun <name>` up to the next member declaration. Comments are stripped
     * first, exactly as `DrawTest` and `MainCameraOwnershipTest` do: this file's prose talks at
     * length about pearls and lures and materials, and a scan that read it would report the
     * explanation as the offence.
     */
    private fun bodyOf(name: String, from: String = source): String
    {
        val stripped = stripComments(from)
        val start = stripped.indexOf("private fun $name")
        assertTrue(start >= 0, "no `private fun $name` any more — re-read this test")
        val rest = stripped.substring(start + 1)
        val end = Regex("\\n    (private|internal|fun|val|const) ").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object
    {
        const val RENDERER = "src/main/kotlin/render/DiveRenderer.kt"
        const val LIGHTING = "src/main/kotlin/render/DiveLighting.kt"

        /** The one function that knows what a pearl-surfaced object looks like. */
        const val SHARED_DRAW = "drawPearlSurface"

        /** Anything that puts pixels on a surface or into the iridescence batch. */
        val DRAW_VERBS = Regex("\\b(fillRect|fillRectCentred|drawTexture|drawText|drawPearlSurface)\\s*\\(")

        fun stripComments(source: String): String = source
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }
}
