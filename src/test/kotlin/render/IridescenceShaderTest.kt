package render

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of a custom shader that CAN be checked without a GPU: whether the Kotlin side and
 * the GLSL side agree about what is being handed across the boundary.
 *
 * This is not a formality in this codebase. `Surface.drawQuad` renders nothing at all on
 * macOS — silently, no GL error, no log line — for exactly one reason: `QuadRenderer` binds the
 * packed-colour attribute as `GL_FLOAT` while `quad.vert` declares it `in uint`, and feeding an
 * integer-typed vertex input through `glVertexAttribPointer` is undefined behaviour that the
 * driver is under no obligation to complain about (see `render/Draw.kt` and
 * `docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md`). It took a framebuffer
 * read-back to find. The same mistake in `IridescenceRenderer` would produce pearls that are
 * invisible, or the wrong colour, or fine on this Mac and wrong at the booth — and nothing
 * would say so.
 *
 * A missing UNIFORM is only slightly louder: `ShaderProgram.uniformLocationOf` logs a WARN and
 * carries on, and the booth runs at `logLevel = WARN` with no console attached.
 *
 * So: every attribute the renderer binds must be declared in the vertex shader with a matching
 * GLSL type, every uniform it sets must exist, the two stages' interface must line up, and the
 * whole thing must be `#version 330 core` because macOS caps OpenGL at 4.1. All of that is a
 * property of two text files and one Kotlin object, and none of it needs a rasteriser.
 *
 * WHAT THIS DELIBERATELY DOES NOT CLAIM: that the shader looks right. It cannot. See
 * `IridescentMaterialTest`'s class doc and this task's contact sheet.
 */
class IridescenceShaderTest
{
    private fun source(path: String): String =
        IridescenceShaderTest::class.java.getResource(path)?.readText()
            ?: error("$path is not on the classpath at all — is it under src/main/resources?")

    private val vert = source(IridescenceRenderer.VERTEX_SHADER)
    private val frag = source(IridescenceRenderer.FRAGMENT_SHADER)

    /** `in <type> <name>;` and `out <type> <name>;` declarations, ignoring comments. */
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
        val bound = IridescenceRenderer.instanceLayout().attributes +
            listOf(dummyQuadAttribute())

        bound.forEach { attribute ->
            val glslType = declared[attribute.name]
            assertTrue(
                glslType != null,
                "the renderer binds a vertex attribute named '${attribute.name}' that " +
                "iridescence.vert never declares. `glGetAttribLocation` returns -1 for it and " +
                "the enable/pointer calls are then made against location -1: no GL error, no log"
            )
            assertEquals(
                glslTypeFor(attribute.type, attribute.count),
                glslType,
                "attribute '${attribute.name}' is BOUND as ${glName(attribute.type)} x " +
                "${attribute.count} but DECLARED '$glslType' in iridescence.vert. A count " +
                "mismatch silently reads neighbouring instance data; a float-versus-integer " +
                "mismatch is undefined behaviour that Apple's GL-over-Metal layer resolves as " +
                "zero — that is precisely the defect that makes drawQuad rasterise at alpha 0 " +
                "(render/Draw.kt)"
            )
        }

        // ...and nothing declared is left unbound: an `in` with no pointer set reads whatever
        // the last VAO left in that generic attribute slot, which is not zero and is not stable.
        val boundNames = bound.map { it.name }.toSet()
        assertEquals(
            emptySet(), declared.keys - boundNames,
            "iridescence.vert declares vertex inputs the renderer never binds"
        )
    }

    @Test
    fun `the declared instance stride matches the floats the renderer actually writes`()
    {
        // `IridescenceRenderer.draw` writes FLOATS_PER_INSTANCE values into the buffer while the
        // layout tells GL how far apart two instances are. If they disagree, every instance
        // after the first reads a window sliding through the previous one's data — a corruption
        // that gets WORSE the more objects are on screen, so it would look like a load-dependent
        // glitch rather than like a wrong constant.
        val floatsInLayout = IridescenceRenderer.instanceLayout().attributes.sumOf { it.count }
        assertEquals(
            floatsInLayout, IridescenceRenderer.FLOATS_PER_INSTANCE,
            "the instance layout is $floatsInLayout floats wide but draw() writes " +
            "${IridescenceRenderer.FLOATS_PER_INSTANCE}"
        )

        // Every attribute is 4 bytes per component, so the byte stride has to agree too — this
        // catches a type change (e.g. to GL_UNSIGNED_BYTE) that leaves the count alone.
        assertEquals(
            (floatsInLayout * 4).toLong(), IridescenceRenderer.instanceLayout().strideInBytes,
            "an attribute type that is not 4 bytes wide would break the float-count arithmetic above"
        )
    }

    @Test
    fun `every uniform the renderer sets exists, and every uniform declared is set`()
    {
        val declared = uniforms(vert) + uniforms(frag)

        assertEquals(
            emptySet(), IridescenceRenderer.UNIFORMS.toSet() - declared,
            "the renderer sets a uniform the shaders do not declare. ShaderProgram logs a WARN " +
            "and carries on, and the booth runs at logLevel = WARN with no console attached"
        )
        assertEquals(
            emptySet(), declared - IridescenceRenderer.UNIFORMS.toSet(),
            "the shaders declare a uniform nothing uploads — it reads as zero every frame. " +
            "`lightPos` at (0, 0) is a perfectly plausible-looking value and would put the " +
            "torch at the world origin (or the HUD's top-left corner) for the whole run"
        )

        // And the calls are really there. The list above is a declaration of intent; this is
        // what stops it drifting from the code it describes.
        IridescenceRenderer.UNIFORMS.forEach {
            assertTrue(
                File("src/main/kotlin/render/IridescenceRenderer.kt").readText()
                    .contains("setUniform(\"$it\""),
                "IridescenceRenderer.UNIFORMS lists '$it' but nothing calls setUniform for it"
            )
        }
    }

    @Test
    fun `the two stages agree on their interface`()
    {
        // A vertex `out` with no matching fragment `in` (or a type mismatch between them) is a
        // LINK error, which the engine turns into a thrown RuntimeException at the first bind —
        // i.e. a crash in front of a queue rather than a build failure here.
        assertEquals(
            declarations(vert, "out"), declarations(frag, "in"),
            "iridescence.vert's outputs and iridescence.frag's inputs must match by name AND type"
        )
    }

    @Test
    fun `both shaders target GLSL 330, which is the highest this game can run`()
    {
        // macOS caps OpenGL at 4.1 and has no compute shaders at all. The engine ships 430
        // shaders (lighting/direct/*, error.comp) that simply cannot compile here — which is
        // why this game uses the global-illumination module and not the direct one, and why
        // error.comp logs a compile failure on every single boot. A 4.x #version here would do
        // the same thing: fail to compile, fall back to the engine's error program, and draw
        // something wrong with only a DEBUG line to say so.
        listOf(IridescenceRenderer.VERTEX_SHADER to vert, IridescenceRenderer.FRAGMENT_SHADER to frag)
            .forEach { (path, src) ->
                val version = Regex("^\\s*#version\\s+(\\d+)", RegexOption.MULTILINE).find(src)
                assertTrue(version != null, "$path declares no #version at all")
                assertEquals(
                    "330", version.groupValues[1],
                    "$path targets GLSL ${version.groupValues[1]}; macOS gives us 4.10 at most " +
                    "and this project standardises on 330 core"
                )
            }
    }

    // ---- Packaging: this shader is OURS and must reach the release jar ----------------------

    @Test
    fun `our shaders live outside the engine's namespace, so they neither shadow nor are excluded`()
    {
        listOf(IridescenceRenderer.VERTEX_SHADER, IridescenceRenderer.FRAGMENT_SHADER).forEach {
            assertTrue(
                !it.startsWith("/pulseengine/"),
                "$it sits inside the engine's own resource namespace. Two files there " +
                "(renderers/{quad,line}.vert) are deliberate SHADOWS of engine files and are " +
                "excluded from the Windows release jar by build.gradle.kts; a file of ours in " +
                "the same tree risks being caught by that reasoning, and — worse — a future " +
                "engine bump could start shipping a file at the same path and win the " +
                "classloader race. Ours belong under /shaders/"
            )

            // Exactly one copy on the classpath. Two would mean something IS shadowing.
            val copies = IridescenceShaderTest::class.java.classLoader
                .getResources(it.removePrefix("/")).toList()
            assertEquals(1, copies.size, "$it resolves to ${copies.size} classpath entries: $copies")
        }
    }

    @Test
    fun `the release jar's shader exclusion list does not name our shaders`()
    {
        // build.gradle.kts drops `pulseengine/shaders/renderers/{quad,line}.vert` from the
        // release jar by SOURCE DIRECTORY — deliberately, so it cannot be inverted by merge
        // order. That mechanism is the one thing that could silently keep this shader out of the
        // shipped .exe, where the failure would be a booth cabinet drawing flat squares with
        // nothing in the log but a DEBUG line nobody is watching.
        val build = File("build.gradle.kts").readText()
        val excluded = Regex("val devOnlyShaderOverrides = setOf\\(([^)]*)\\)")
            .find(build)
            ?.groupValues?.get(1)
            ?: error("build.gradle.kts no longer declares devOnlyShaderOverrides — re-read this test")

        listOf(IridescenceRenderer.VERTEX_SHADER, IridescenceRenderer.FRAGMENT_SHADER).forEach {
            assertTrue(
                !excluded.contains(it.removePrefix("/")),
                "$it is in build.gradle.kts's release-jar exclusion list. That list is for our " +
                "SHADOWS of engine files; this shader is ours and has to ship"
            )
        }

        // The processed-resources tree is what the jar task copies from, so a shader that is
        // here will be in the jar. (The jar itself is verified by a classloader probe against
        // the built artefact — see this task's report; `./gradlew test` does not build one.)
        listOf(IridescenceRenderer.VERTEX_SHADER, IridescenceRenderer.FRAGMENT_SHADER).forEach {
            assertTrue(
                File("build/resources/main$it").isFile,
                "$it is missing from build/resources/main, which is what the jar is built from"
            )
        }
    }

    private companion object
    {
        /** Kotlin comments are `//`; GLSL's are the same, plus `/* */` blocks. */
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

        /**
         * The shared unit quad's vertex attribute, which is bound from a second, separate layout
         * inside `init` and is therefore not in `instanceLayout()`. Rebuilt here so the `in`
         * declarations in the shader can be checked EXHAUSTIVELY — an unbound `in` is the
         * failure this pairs with, and leaving one attribute out of the comparison would make
         * that check vacuous.
         */
        fun dummyQuadAttribute() = no.njoh.pulseengine.core.graphics.api.VertexAttributeLayout()
            .withAttribute(IridescenceRenderer.QUAD_ATTRIBUTE, 2, GL_FLOAT)
            .attributes
            .single()
    }
}
