package render

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `src/main/resources/pulseengine/shaders/renderers/{quad,line}.vert` are COPIES of engine
 * files, shadowing pulse-engine 0.13.0's own on the classpath so that `Surface.drawQuad` and
 * `drawLine` render at all on macOS (see the headers in those files, and `Draw.kt`).
 *
 * Copying a file you do not own has exactly one maintenance hazard: the original changes
 * underneath you and nobody notices, because a stale shader still compiles and still draws
 * something. Nothing else in this repo can catch that — there is no GL context in the test
 * JVM, so what a shader actually *renders* is only ever verifiable by framebuffer read-back
 * (which is how the fix itself was verified; see the investigation report).
 *
 * So this asserts the one thing that is checkable without a GPU: that our copies differ from
 * the engine's *only* in the three documented ways. Everything else — the sRGB-to-linear
 * conversion, the attribute names, the `viewProjection` transform — must still be
 * character-for-character the engine's, so a bump of the pulse-engine dependency that touches
 * these shaders fails the build instead of silently leaving a 0.13.0 shader in a 0.14 game.
 *
 * Both copies are on the test classpath: ours as a plain file (build/resources/main), the
 * engine's inside the dependency jar. That is the same shadowing that makes the override work
 * at runtime, so this also fails if the override stops being on the classpath at all.
 */
class ShaderOverrideTest
{
    private fun copies(path: String): List<URL> =
        ShaderOverrideTest::class.java.classLoader.getResources(path).toList()

    private fun ourCopy(path: String) = copies(path)
        .firstOrNull { it.protocol == "file" }
        ?.readText()
        ?: error("Our override of $path is not on the classpath — src/main/resources copy missing?")

    private fun enginesCopy(path: String) = copies(path)
        .firstOrNull { it.protocol == "jar" }
        ?.readText()
        ?: error("The engine's own $path is not on the classpath")

    /** Drops our header comment block; GLSL allows comments before `#version`. */
    private fun body(src: String) =
        src.lineSequence().dropWhile { !it.startsWith("#version") }.joinToString("\n").trim()

    /** Reverses the three documented edits, so what is left must be the engine's file verbatim. */
    private fun undoOurEdits(ourBody: String, attribute: String) = ourBody
        .replace("#version 330 core", "#version 150 core")
        .replace("in float $attribute;", "in uint $attribute;")
        .replace("unpackAndConvert(floatBitsToUint($attribute))", "unpackAndConvert($attribute)")

    private fun assertShadowsEngineFile(path: String, attribute: String)
    {
        val ours = body(ourCopy(path))
        val engines = body(enginesCopy(path))

        // The fix itself must still be there. Reverting the shader to the engine's text would
        // otherwise pass the diff check below trivially.
        assertTrue(
            ours.contains("in float $attribute;"),
            "$path must declare `in float $attribute` to match the GL_FLOAT binding the engine performs"
        )
        assertTrue(
            ours.contains("unpackAndConvert(floatBitsToUint($attribute))"),
            "$path must recover the packed RGBA bits with floatBitsToUint"
        )

        // And the engine's copy must still have the shape the fix is written against. If
        // upstream ever declares this attribute correctly, the override is obsolete and should
        // be deleted rather than carried forward.
        assertTrue(
            engines.contains("in uint $attribute;"),
            "The engine's $path no longer declares `in uint $attribute` — upstream may have fixed " +
            "the binding mismatch, in which case delete our override and the build.gradle.kts block"
        )

        assertEquals(
            engines,
            undoOurEdits(ours, attribute),
            "Our $path has drifted from the engine's beyond the three documented edits — re-diff " +
            "it against the pulse-engine jar"
        )
    }

    @Test
    fun `our quad_vert differs from the engine's only by the attribute-type fix`() =
        assertShadowsEngineFile("pulseengine/shaders/renderers/quad.vert", "color")

    @Test
    fun `our line_vert differs from the engine's only by the attribute-type fix`() =
        assertShadowsEngineFile("pulseengine/shaders/renderers/line.vert", "rgbaColor")
}
