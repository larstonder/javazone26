package render

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The god-ray shader's boundary with Kotlin — the half of a custom shader that can be checked
 * without a GPU. `IridescenceShaderTest` states the case at length and this is the same case for
 * the second custom shader in the game; only what differs is written out here.
 *
 * What differs is that this shader owns NO numbers. The band stack, the convergence apex and the
 * mask's edges all live in `LightShafts`, where `LightShaftsTest` asserts them, and reach the GLSL
 * as uniforms. That split is the only reason any of this effect is testable at all, so the case
 * below that pins every uniform as both declared and uploaded is load-bearing rather than
 * ceremonial: a uniform that stops being uploaded reads as ZERO, and a zero `bandAmplitude` is a
 * shader that draws nothing while logging one WARN at a level the booth does not print.
 */
class GodRayShaderTest
{
    private fun source(path: String): String =
        GodRayShaderTest::class.java.getResource(path)?.readText()
            ?: error("$path is not on the classpath at all — is it under src/main/resources?")

    private val vert = source(ShaftRenderer.VERTEX_SHADER)
    private val frag = source(ShaftRenderer.FRAGMENT_SHADER)

    private fun declarations(source: String, qualifier: String): Map<String, String> =
        Regex("^\\s*$qualifier\\s+(\\w+)\\s+(\\w+)\\s*;", RegexOption.MULTILINE)
            .findAll(stripComments(source))
            .associate { it.groupValues[2] to it.groupValues[1] }

    private fun uniforms(source: String): Set<String> =
        Regex("^\\s*uniform\\s+\\w+\\s+(\\w+)\\s*;", RegexOption.MULTILINE)
            .findAll(stripComments(source))
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every attribute the renderer binds is declared in the vertex shader with the matching GLSL type`()
    {
        val declared = declarations(vert, "in")
        val bound = ShaftRenderer.instanceLayout().attributes + listOf(dummyQuadAttribute())

        bound.forEach { attribute ->
            val glslType = declared[attribute.name]
            assertTrue(
                glslType != null,
                "the renderer binds a vertex attribute named '${attribute.name}' that " +
                "godrays.vert never declares. `glGetAttribLocation` returns -1 for it and the " +
                "enable/pointer calls are then made against location -1: no GL error, no log"
            )
            assertEquals(
                glslTypeFor(attribute.type, attribute.count), glslType,
                "attribute '${attribute.name}' is BOUND as ${glName(attribute.type)} x " +
                "${attribute.count} but DECLARED '$glslType' in godrays.vert. A count mismatch " +
                "silently reads neighbouring instance data; a float-versus-integer mismatch is " +
                "the undefined behaviour that makes drawQuad rasterise at alpha 0 (render/Draw.kt)"
            )
        }

        val boundNames = bound.map { it.name }.toSet()
        assertEquals(
            emptySet(), declared.keys - boundNames,
            "godrays.vert declares vertex inputs the renderer never binds"
        )
    }

    @Test
    fun `the declared instance stride matches the floats the renderer actually writes`()
    {
        val floatsInLayout = ShaftRenderer.instanceLayout().attributes.sumOf { it.count }
        assertEquals(
            floatsInLayout, ShaftRenderer.FLOATS_PER_INSTANCE,
            "the instance layout is $floatsInLayout floats wide but draw() writes " +
            "${ShaftRenderer.FLOATS_PER_INSTANCE} — every strip after the first would then read " +
            "a window sliding through the previous one's data"
        )
        assertEquals(
            (floatsInLayout * 4).toLong(), ShaftRenderer.instanceLayout().strideInBytes,
            "an attribute type that is not 4 bytes wide would break the float-count arithmetic above"
        )
    }

    @Test
    fun `every uniform the renderer sets exists, and every uniform declared is set`()
    {
        val declared = uniforms(vert) + uniforms(frag)

        assertEquals(
            emptySet(), ShaftRenderer.UNIFORMS.toSet() - declared,
            "the renderer sets a uniform the shaders do not declare. ShaderProgram logs a WARN " +
            "and carries on, and the booth runs at logLevel = WARN with no console attached"
        )
        assertEquals(
            emptySet(), declared - ShaftRenderer.UNIFORMS.toSet(),
            "the shaders declare a uniform nothing uploads — it reads as ZERO every frame. This " +
            "shader owns no constants of its own, so a missing upload is not a wrong look, it is " +
            "a missing effect: bandAmplitude at zero draws nothing at all, silently"
        )

        ShaftRenderer.UNIFORMS.forEach {
            assertTrue(
                File("src/main/kotlin/render/ShaftRenderer.kt").readText().contains("setUniform(\"$it\""),
                "ShaftRenderer.UNIFORMS lists '$it' but nothing calls setUniform for it"
            )
        }
    }

    @Test
    fun `the two stages agree on their interface`()
    {
        assertEquals(
            declarations(vert, "out"), declarations(frag, "in"),
            "godrays.vert's outputs and godrays.frag's inputs must match by name AND type; a " +
            "mismatch is a LINK error, i.e. a thrown exception at the first bind rather than a " +
            "build failure here"
        )
    }

    @Test
    fun `both shaders target GLSL 330, which is the highest this game can run`()
    {
        listOf(ShaftRenderer.VERTEX_SHADER to vert, ShaftRenderer.FRAGMENT_SHADER to frag)
            .forEach { (path, src) ->
                val version = Regex("^\\s*#version\\s+(\\d+)", RegexOption.MULTILINE).find(src)
                assertTrue(version != null, "$path declares no #version at all")
                assertEquals(
                    "330", version.groupValues[1],
                    "$path targets GLSL ${version.groupValues[1]}; macOS gives us 4.10 at most"
                )
            }
    }

    /**
     * THE BAND STACK IS FOUR-WIDE ON BOTH SIDES OF THE BOUNDARY.
     *
     * `ShaftRenderer` uploads the frequency, amplitude and phase of every band packed into three
     * `vec4`s, one component per band — which is only correct while `LightShafts` authors exactly
     * four. A fifth band added to the Kotlin table would be silently dropped (the upload names
     * four components by hand) and a band removed would upload a stale value from a table that no
     * longer has that index, which is an array read that throws at the first frame in front of a
     * queue. Neither is visible in either file on its own.
     */
    @Test
    fun `the shader's vec4 band stack is exactly as wide as the Kotlin band table`()
    {
        assertEquals(
            4, LightShafts.bandCount,
            "godrays.frag packs the band stack into vec4s and ShaftRenderer uploads four " +
            "components by hand, so LightShafts must author exactly four bands. It authors " +
            "${LightShafts.bandCount}"
        )
        listOf("bandFrequency", "bandAmplitude", "bandPhase").forEach {
            assertTrue(
                Regex("uniform\\s+vec4\\s+$it\\s*;").containsMatchIn(stripComments(frag)),
                "godrays.frag no longer declares '$it' as a vec4, so it can no longer carry one " +
                "component per band"
            )
        }
    }

    // ---- Packaging: these shaders are OURS and must reach the release jar --------------------

    @Test
    fun `our shaders live outside the engine's namespace, so they neither shadow nor are excluded`()
    {
        listOf(ShaftRenderer.VERTEX_SHADER, ShaftRenderer.FRAGMENT_SHADER).forEach {
            assertTrue(
                !it.startsWith("/pulseengine/"),
                "$it sits inside the engine's own resource namespace, where build.gradle.kts's " +
                "devOnlyShaderOverrides reasoning lives and where a future engine bump could win " +
                "the classloader race. Ours belong under /shaders/"
            )
            val copies = GodRayShaderTest::class.java.classLoader
                .getResources(it.removePrefix("/")).toList()
            assertEquals(1, copies.size, "$it resolves to ${copies.size} classpath entries: $copies")
        }
    }

    @Test
    fun `the release jar's shader exclusion list does not name our shaders`()
    {
        val build = File("build.gradle.kts").readText()
        val excluded = Regex("val devOnlyShaderOverrides = setOf\\(([^)]*)\\)")
            .find(build)
            ?.groupValues?.get(1)
            ?: error("build.gradle.kts no longer declares devOnlyShaderOverrides — re-read this test")

        listOf(ShaftRenderer.VERTEX_SHADER, ShaftRenderer.FRAGMENT_SHADER).forEach {
            assertTrue(
                !excluded.contains(it.removePrefix("/")),
                "$it is in build.gradle.kts's release-jar exclusion list. That list is for our " +
                "SHADOWS of engine files; this shader is ours and has to ship"
            )
            assertTrue(
                File("build/resources/main$it").isFile,
                "$it is missing from build/resources/main, which is what the jar is built from"
            )
        }
    }

    private companion object
    {
        fun stripComments(source: String): String = source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")

        fun glslTypeFor(glType: Int, count: Int): String = when (glType)
        {
            GL_FLOAT -> if (count == 1) "float" else "vec$count"
            GL_UNSIGNED_INT -> if (count == 1) "uint" else "uvec$count"
            else -> error("unhandled GL type $glType")
        }

        fun glName(glType: Int) = if (glType == GL_FLOAT) "GL_FLOAT" else "GL_UNSIGNED_INT"

        /** @see IridescenceShaderTest — the shared unit quad is bound from its own layout. */
        fun dummyQuadAttribute() = no.njoh.pulseengine.core.graphics.api.VertexAttributeLayout()
            .withAttribute(ShaftRenderer.QUAD_ATTRIBUTE, 2, GL_FLOAT)
            .attributes
            .single()
    }
}
