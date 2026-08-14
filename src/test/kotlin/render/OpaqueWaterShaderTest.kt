package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `opaque_water`'s boundary with Kotlin — the half of a custom shader that is checkable without a
 * GPU. `IridescenceShaderTest` argues the general case and `GodRayShaderTest` repeats it for the
 * second custom shader; only what is different about the third is written out here.
 *
 * Two things are different. This is the project's first `PostProcessingEffect` rather than a
 * `BatchRenderer`, so its vertex attributes are not ours to choose — `FullFrameRenderer` binds
 * exactly `position` and `texCoord`, BY NAME, and `ShaderProgram.setVertexAttributeLayout` on a
 * name the shader does not declare gets location -1 from `glGetAttribLocation` and then makes its
 * enable/pointer calls against it: no GL error, no log, a black screen.
 *
 * And this shader's whole correctness is one comparison. `uv.y < gateUv` is the entire feature;
 * a version that thresholds on the ALPHA VALUE instead would look nearly identical, pass every
 * pixel-count check, and be wrong in the one way this fix must not be — see `OpaqueWaterEffect`'s
 * class doc on why the gate is a POSITION.
 */
class OpaqueWaterShaderTest
{
    private fun source(path: String): String =
        OpaqueWaterShaderTest::class.java.getResource(path)?.readText()
            ?: error("$path is not on the classpath at all — is it under src/main/resources?")

    private val vert = stripComments(source(OpaqueWaterEffect.VERTEX_SHADER))
    private val frag = stripComments(source(OpaqueWaterEffect.FRAGMENT_SHADER))

    private fun uniforms(source: String): Set<String> =
        Regex("^\\s*uniform\\s+\\w+\\s+(\\w+)\\s*;", RegexOption.MULTILINE)
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

    /** The effect's own Kotlin source, read as text — the only way to see what it uploads. */
    private val effectSource = File("src/main/kotlin/render/OpaqueWater.kt").readText()

    // ---- The Kotlin/GLSL boundary --------------------------------------------------------------

    /**
     * EVERY UNIFORM DECLARED IS UPLOADED, AND EVERY UNIFORM UPLOADED IS DECLARED.
     *
     * Both directions are silent failures and they fail differently. A declared-but-never-uploaded
     * uniform reads as ZERO — and `gateUv = 0` is exactly this effect's no-op, so the whole pass
     * would quietly stop doing anything while every test that does not need a GPU still passed. An
     * uploaded-but-undeclared one is a `glGetUniformLocation` of -1, which `ShaderProgram` logs at
     * a level the booth does not print.
     */
    @Test
    fun `every uniform the shader declares is uploaded by the effect, and the reverse`()
    {
        val declared = uniforms(frag) + uniforms(vert)
        val uploaded = Regex("setUniform(?:Sampler)?\\(\"(\\w+)\"")
            .findAll(effectSource)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            declared, uploaded,
            "opaque_water's uniforms and OpaqueWaterEffect.applyEffect's uploads have diverged. " +
            "A declared uniform that is never uploaded reads as 0, and gateUv = 0 is this " +
            "effect's own no-op — it would stop working in complete silence"
        )
    }

    /**
     * THE TWO VERTEX ATTRIBUTES `FullFrameRenderer` BINDS ARE DECLARED, as `vec2`, under exactly
     * those names. We do not get to pick these: `FullFrameRenderer.init` calls
     * `setVertexAttributeLayout("position", 2, GL_FLOAT, ...)` and the same for `"texCoord"`, and
     * `BaseEffect` builds that renderer for us from whatever program `loadShaderProgram` returns.
     */
    @Test
    fun `the vertex shader declares the attributes FullFrameRenderer binds`()
    {
        listOf("position", "texCoord").forEach {
            assertTrue(
                Regex("^\\s*in\\s+vec2\\s+$it\\s*;", RegexOption.MULTILINE).containsMatchIn(vert),
                "opaque_water.vert does not declare `in vec2 $it`. FullFrameRenderer binds that " +
                "name as 2 x GL_FLOAT; glGetAttribLocation returns -1 for a name the shader does " +
                "not have, and the enable/pointer calls are then made against location -1"
            )
        }
        assertTrue(
            Regex("^\\s*out\\s+vec2\\s+uv\\s*;", RegexOption.MULTILINE).containsMatchIn(vert) &&
            Regex("^\\s*in\\s+vec2\\s+uv\\s*;", RegexOption.MULTILINE).containsMatchIn(frag),
            "the varying between the two stages must be `vec2 uv` on both sides — a name mismatch " +
            "links successfully and delivers an undefined value"
        )
    }

    // ---- What the shader must and must not do --------------------------------------------------

    /**
     * THE GATE IS A POSITION. This is the assertion the whole file exists for.
     *
     * `main`'s alpha is legitimately partial in exactly one place — `water.frag`'s one-pixel
     * anti-aliasing ramp at the waterline — and it is partial WRONGLY everywhere below that. Those
     * two are indistinguishable by VALUE (a meniscus fragment and a mote-eroded fragment can hold
     * the same alpha) and trivially distinguishable by POSITION. A threshold on `c.a` would
     * therefore harden the meniscus, and would do it in a way that reads as "the waterline got
     * crisper" rather than as a bug.
     */
    @Test
    fun `the alpha gate is on the fragment's position and never on its alpha value`()
    {
        assertTrue(
            Regex("uv\\.y\\s*<\\s*gateUv").containsMatchIn(frag),
            "opaque_water.frag no longer gates on `uv.y < gateUv`. The gate must be a POSITION: " +
            "see OpaqueWaterEffect's class doc, and OpaqueWaterTest for what the position is"
        )
        assertTrue(
            !Regex("\\.a\\s*[<>]").containsMatchIn(frag),
            "opaque_water.frag compares an alpha VALUE against something. There is no threshold " +
            "to tune here — a meniscus fragment and a mote-eroded fragment hold the same alpha " +
            "and differ only in where they are. Thresholding on alpha hardens the waterline"
        )
    }

    /**
     * RGB IS PASSED THROUGH UNTOUCHED, which is what makes this a repair and not a paint-over.
     * The broken blend got the COLOUR right — `dst_rgb = src_rgb*a + dst_rgb*(1 - a)` is correct
     * straight-alpha "over" — and only the alpha channel carried the stray `a²`. A shader that
     * also wrote rgb would be inventing water where the engine had already composited it
     * correctly.
     */
    @Test
    fun `the colour channels are passed through unmodified`()
    {
        assertTrue(
            Regex("fragColor\\s*=\\s*vec4\\(\\s*c\\.rgb\\s*,").containsMatchIn(frag),
            "opaque_water.frag no longer writes the sampled rgb verbatim. The eroded pixels " +
            "already hold the right colour; only their alpha was wrong"
        )
    }

    /**
     * `#version 330 core`, the ceiling macOS's OpenGL 4.1 leaves the whole project sharing, and
     * the version both other custom shaders target.
     */
    @Test
    fun `both stages target the version the project can compile everywhere`()
    {
        listOf(OpaqueWaterEffect.VERTEX_SHADER to vert, OpaqueWaterEffect.FRAGMENT_SHADER to frag).forEach { (path, src) ->
            assertTrue(
                src.trimStart().startsWith("#version 330 core"),
                "$path must open with `#version 330 core` — macOS caps OpenGL at 4.1"
            )
        }
    }

    // ---- Packaging: these shaders are OURS and must reach the release jar ----------------------

    @Test
    fun `our shaders live outside the engine's namespace, so they neither shadow nor are excluded`()
    {
        listOf(OpaqueWaterEffect.VERTEX_SHADER, OpaqueWaterEffect.FRAGMENT_SHADER).forEach {
            assertTrue(
                !it.startsWith("/pulseengine/"),
                "$it sits inside the engine's own resource namespace, where build.gradle.kts's " +
                "devOnlyShaderOverrides reasoning lives and where a future engine bump could win " +
                "the classloader race. Ours belong under /shaders/"
            )
            val copies = OpaqueWaterShaderTest::class.java.classLoader
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

        listOf(OpaqueWaterEffect.VERTEX_SHADER, OpaqueWaterEffect.FRAGMENT_SHADER).forEach {
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
    }
}
