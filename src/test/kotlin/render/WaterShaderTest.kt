package render

import org.lwjgl.opengl.GL11.GL_FLOAT
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of the water shader that can be checked without a GPU — the same set of properties
 * `IridescenceShaderTest` pins for the game's first custom shader, and for the same reasons, which
 * are worth restating because none of them announce themselves at runtime:
 *
 *  - a vertex attribute BOUND as one GL type and DECLARED as another is undefined behaviour with
 *    no GL error and no log line. That is exactly why `Surface.drawQuad` renders nothing at all on
 *    macOS (`render/Draw.kt`), and it took a framebuffer read-back to find.
 *  - a uniform the renderer sets but the shader does not declare produces a WARN, on a booth
 *    cabinet running at `logLevel = WARN` with no console attached; a uniform the shader declares
 *    but nothing sets simply reads as zero, which for `time` is a frozen sea and for `sunStrength`
 *    is no meniscus at all — both entirely plausible-looking frames.
 *  - a `#version` above 330 cannot compile on macOS and silently falls back to the engine's error
 *    program.
 *  - and this shader is OURS, so it must survive the release jar's exclusion of the two files we
 *    deliberately shadow.
 *
 * WHAT IT DELIBERATELY DOES NOT CLAIM: that the sea looks right, or even that the GLSL sums the
 * wave table the way `WaterSurfaceTest` assumes. The closest text can get to the second is the
 * expression check below, and it is included precisely because the wave table is uploaded from
 * Kotlin and evaluated in GLSL — the one place in this design where two files could disagree.
 */
class WaterShaderTest
{
    private fun source(path: String): String =
        WaterShaderTest::class.java.getResource(path)?.readText()
            ?: error("$path is not on the classpath at all — is it under src/main/resources?")

    private val vert = source(WaterRenderer.VERTEX_SHADER)
    private val frag = source(WaterRenderer.FRAGMENT_SHADER)

    private fun declarations(source: String, qualifier: String): Map<String, String> =
        Regex("^\\s*$qualifier\\s+(\\w+)\\s+(\\w+)\\s*;", RegexOption.MULTILINE)
            .findAll(stripComments(source))
            .associate { it.groupValues[2] to it.groupValues[1] }

    /** Uniform names, ARRAYS INCLUDED — `wave[WAVE_COUNT]` is declared as an array and set per element. */
    private fun uniforms(source: String): Set<String> =
        Regex("^\\s*uniform\\s+\\w+\\s+(\\w+)\\s*(\\[[^]]*\\])?\\s*;", RegexOption.MULTILINE)
            .findAll(stripComments(source))
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every attribute the renderer binds is declared in the vertex shader with the matching GLSL type`()
    {
        val declared = declarations(vert, "in")
        val bound = WaterRenderer.instanceLayout().attributes + listOf(dummyQuadAttribute())

        bound.forEach { attribute ->
            val glslType = declared[attribute.name]
            assertTrue(
                glslType != null,
                "the renderer binds a vertex attribute named '${attribute.name}' that water.vert never declares. `glGetAttribLocation` returns -1 and the enable/pointer calls are made against location -1: no GL error, no log"
            )
            assertEquals(
                glslTypeFor(attribute.type, attribute.count), glslType,
                "attribute '${attribute.name}' is BOUND as ${attribute.count} x GL_FLOAT but DECLARED '$glslType'. A count mismatch silently reads neighbouring instance data; a float-versus-integer mismatch is the drawQuad defect"
            )
        }

        val boundNames = bound.map { it.name }.toSet()
        assertEquals(
            emptySet(), declared.keys - boundNames,
            "water.vert declares vertex inputs the renderer never binds — an `in` with no pointer set reads whatever the last VAO left in that slot, which is neither zero nor stable"
        )
    }

    @Test
    fun `the declared instance stride matches the floats the renderer actually writes`()
    {
        val floatsInLayout = WaterRenderer.instanceLayout().attributes.sumOf { it.count }
        assertEquals(
            floatsInLayout, WaterRenderer.FLOATS_PER_INSTANCE,
            "the instance layout is $floatsInLayout floats wide but draw() writes ${WaterRenderer.FLOATS_PER_INSTANCE}"
        )
        assertEquals(
            (floatsInLayout * 4).toLong(), WaterRenderer.instanceLayout().strideInBytes,
            "an attribute type that is not 4 bytes wide would break the float-count arithmetic above"
        )
    }

    @Test
    fun `every uniform the renderer sets exists, and every uniform declared is set`()
    {
        val declared = uniforms(vert) + uniforms(frag)

        assertEquals(
            emptySet(), WaterRenderer.UNIFORMS.toSet() - declared,
            "the renderer sets a uniform the shaders do not declare; ShaderProgram logs a WARN and carries on, and the booth runs at logLevel = WARN with no console"
        )
        assertEquals(
            emptySet(), declared - WaterRenderer.UNIFORMS.toSet(),
            "the shaders declare a uniform nothing uploads — it reads as zero every frame. `time` at zero is a frozen sea and `sunStrength` at zero is no meniscus, and both look entirely deliberate"
        )

        val code = File("src/main/kotlin/render/WaterRenderer.kt").readText()
        WaterRenderer.UNIFORMS.forEach {
            assertTrue(
                code.contains("setUniform(\"$it") ,
                "WaterRenderer.UNIFORMS lists '$it' but nothing calls setUniform for it"
            )
        }
    }

    /**
     * THE WAVE TABLE HAS EXACTLY ONE COPY, AND THE SHADER READS ALL OF IT.
     *
     * `WaterSurface.components` is uploaded verbatim and the GLSL sums it. Nothing in a text file
     * can prove the sum is right, but two things that WOULD be silent can be caught here: a
     * `WAVE_COUNT` that has drifted from the Kotlin table (the shader would then read a component
     * that was never uploaded, i.e. an all-zero vec4 — a component that quietly disappears), and a
     * shader that has grown a hardcoded amplitude or wavelength of its own.
     */
    @Test
    fun `the shader sums exactly the components the renderer uploads`()
    {
        val count = Regex("const\\s+int\\s+WAVE_COUNT\\s*=\\s*(\\d+)").find(stripComments(frag))
            ?: error("water.frag no longer declares WAVE_COUNT")
        assertEquals(
            WaterSurface.components.size, count.groupValues[1].toInt(),
            "water.frag sums ${count.groupValues[1]} components but WaterSurface declares ${WaterSurface.components.size} — the extra one reads as an all-zero vec4 and simply vanishes"
        )

        val body = stripComments(frag)
        assertTrue(
            body.contains("sin(arg)") && body.contains("cos(arg)"),
            "water.frag no longer takes both the sine (the height) and its cosine (the slope) of one shared argument; the slope is what modulates the meniscus"
        )
        // Every term of the sum must come out of the uploaded vec4 and nowhere else.
        listOf("wave[i].x", "wave[i].y", "wave[i].z", "wave[i].w").forEach {
            assertTrue(body.contains(it), "water.frag never reads $it, so part of the uploaded wave table is being ignored")
        }
    }

    @Test
    fun `the two stages agree on their interface`()
    {
        assertEquals(
            declarations(vert, "out"), declarations(frag, "in"),
            "water.vert's outputs and water.frag's inputs must match by name AND type; a mismatch is a LINK error, i.e. a thrown exception at the first bind rather than a build failure here"
        )
    }

    @Test
    fun `both shaders target GLSL 330, which is the highest this game can run`()
    {
        listOf(WaterRenderer.VERTEX_SHADER to vert, WaterRenderer.FRAGMENT_SHADER to frag)
            .forEach { (path, src) ->
                val version = Regex("^\\s*#version\\s+(\\d+)", RegexOption.MULTILINE).find(src)
                assertTrue(version != null, "$path declares no #version at all")
                assertEquals(
                    "330", version.groupValues[1],
                    "$path targets GLSL ${version.groupValues[1]}; macOS gives us 4.10 at most and this project standardises on 330 core"
                )
            }
    }

    @Test
    fun `our shaders live outside the engine's namespace, so they neither shadow nor are excluded`()
    {
        listOf(WaterRenderer.VERTEX_SHADER, WaterRenderer.FRAGMENT_SHADER).forEach {
            assertTrue(
                !it.startsWith("/pulseengine/"),
                "$it sits inside the engine's own resource namespace, where build.gradle.kts's devOnlyShaderOverrides exclusion lives and where a future engine bump could win the classloader race. Ours belong under /shaders/"
            )
            // A CLASSLOADER probe, not a jar listing — the two disagree about duplicates, and a
            // duplicate is precisely what "something is shadowing this" looks like.
            val copies = WaterShaderTest::class.java.classLoader
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

        listOf(WaterRenderer.VERTEX_SHADER, WaterRenderer.FRAGMENT_SHADER).forEach {
            assertTrue(
                !excluded.contains(it.removePrefix("/")),
                "$it is in build.gradle.kts's release-jar exclusion list. That list is for our SHADOWS of engine files; this shader is ours and has to ship"
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
            else -> error("unhandled GL type $glType")
        }

        fun dummyQuadAttribute() = no.njoh.pulseengine.core.graphics.api.VertexAttributeLayout()
            .withAttribute(WaterRenderer.QUAD_ATTRIBUTE, 2, GL_FLOAT)
            .attributes
            .single()
    }
}
