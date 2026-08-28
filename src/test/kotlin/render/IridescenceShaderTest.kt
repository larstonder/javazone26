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
 * THE TEXTURED PATH ADDS THREE MORE OF THE SAME KIND, each pinned to a failure that is silent:
 *
 *  - `texHandle` (and `color`) must be `GL_UNSIGNED_INT` **absolutely**, not merely consistently
 *    with the GLSL — they carry packed bits, and the two sides can agree on being wrong.
 *  - The sampler array must be declared at the size the engine's bank is capped to, with a
 *    self-consistent `switch` case per slot, and the bank must actually be bound each batch.
 *    Every one of those failures produces a wrong or invisible sprite and no log line.
 *  - The ANALYTIC path must still be there. It is what draws every pearl and every bubble in the
 *    game's only air warning, and neither has a sprite sheet to fall back to — so a refactor that
 *    moved the shape wholly into a texture would compile, link, run, and quietly square off the
 *    pearls.
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

    /**
     * The renderer's own source. Several of the things worth asserting about this boundary are
     * CALLS rather than values — that the texture bank is bound, that the no-texture sentinel is
     * the engine's own — and none of them can be reached without a GL context.
     */
    private val renderer = File("src/main/kotlin/render/IridescenceRenderer.kt").readText()

    /**
     * `in <type> <name>;` and `out <type> <name>;` declarations, ignoring comments.
     *
     * The optional interpolation qualifier is CAPTURED, not skipped, and folded into the reported
     * type. `flat` is part of the two stages' interface: an `out` declared `flat` in one stage and
     * smooth in the other is a LINK error, and dropping the qualifier from the pattern instead
     * would have made the interface check quietly ignore `vSamplerIndex` altogether — a sampler
     * index is the one varying here that MUST be flat, since interpolating an integer is not
     * merely wrong, it fails to compile in GLSL 330.
     */
    private fun declarations(source: String, qualifier: String): Map<String, String> =
        Regex("^\\s*(?:(flat|smooth|noperspective)\\s+)?$qualifier\\s+(\\w+)\\s+(\\w+)\\s*;", RegexOption.MULTILINE)
            .findAll(stripComments(source))
            .associate { match ->
                val interpolation = match.groupValues[1]
                val type = match.groupValues[2]
                match.groupValues[3] to if (interpolation.isEmpty()) type else "$interpolation $type"
            }

    /**
     * Uniform names, INCLUDING array uniforms — `uniform sampler2DArray textureArrays[16];` is a
     * uniform the renderer has to upload, and a pattern that stops at the `;` would miss it and
     * make the "every declared uniform is set" check vacuous for exactly the uniform whose absence
     * would leave every vent sampling an unbound texture unit.
     */
    private fun uniforms(source: String): Set<String> =
        Regex("^\\s*uniform\\s+\\w+\\s+(\\w+)\\s*(?:\\[\\s*\\d+\\s*])?\\s*;", RegexOption.MULTILINE)
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
    fun `the two bit-packed attributes are integers on BOTH sides, not merely on the same side`()
    {
        // The check above compares the layout against the GLSL, so it passes whenever the two
        // AGREE — including when they agree on being wrong. Changing `texHandle` to GL_FLOAT and
        // `in float texHandle` together is a perfectly self-consistent edit and it destroys the
        // value: the handle is 32 packed bits carried through the float buffer by
        // Float.intBitsToFloat, and only glVertexAttribIPointer (which GL_UNSIGNED_INT selects)
        // delivers them unaltered. Through the float path the driver is free to convert, and the
        // shader then shifts a converted value to get a sampler index — which is `color`'s bug in
        // QuadRenderer, i.e. the reason drawQuad draws nothing at all on macOS (render/Draw.kt).
        //
        // So this pins the type absolutely rather than relatively, which is the assertion that
        // could actually have caught that engine defect.
        val byName = IridescenceRenderer.instanceLayout().attributes.associateBy { it.name }

        listOf("color", "texHandle").forEach { name ->
            val attribute = byName[name] ?: error("the instance layout no longer has a '$name' attribute")
            assertEquals(
                GL_UNSIGNED_INT, attribute.type,
                "'$name' carries packed BITS, not a number. It must be bound as GL_UNSIGNED_INT so " +
                "setVertexAttributeLayout routes it through glVertexAttribIPointer"
            )
            assertEquals(
                1, attribute.count,
                "'$name' is a single packed uint"
            )
            assertEquals(
                "uint", declarations(vert, "in")[name],
                "'$name' must be declared `in uint` in iridescence.vert"
            )
        }
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

        // The sampler array is uploaded, but not by name and not by `setUniform` — see below.
        val uploaded = IridescenceRenderer.UNIFORMS.toSet() + IridescenceRenderer.SAMPLER_ARRAY_UNIFORM

        assertEquals(
            emptySet(), uploaded - declared,
            "the renderer sets a uniform the shaders do not declare. ShaderProgram logs a WARN " +
            "and carries on, and the booth runs at logLevel = WARN with no console attached"
        )
        assertEquals(
            emptySet(), declared - uploaded,
            "the shaders declare a uniform nothing uploads — it reads as zero every frame. " +
            "`lightPos` at (0, 0) is a perfectly plausible-looking value and would put the " +
            "torch at the world origin (or the HUD's top-left corner) for the whole run"
        )

        // And the calls are really there. The list above is a declaration of intent; this is
        // what stops it drifting from the code it describes.
        IridescenceRenderer.UNIFORMS.forEach {
            assertTrue(
                renderer.contains("setUniform(\"$it\""),
                "IridescenceRenderer.UNIFORMS lists '$it' but nothing calls setUniform for it"
            )
        }
    }

    @Test
    fun `the texture bank is bound, and the sampler array is declared at the size the engine caps it to`()
    {
        // The sampler array is the one uniform NOT uploaded by name: `setUniformSamplerArrays`
        // walks the bank and derives `textureArrays[i]` itself, one glActiveTexture +
        // glBindTexture + setUniform per array. Drop this call and nothing fails loudly — the
        // samplers keep whatever unit the last program left them pointing at, so a vent samples
        // some other texture (or unit 0, i.e. whatever the engine bound last) and the shape is
        // wrong rather than absent. Hence a check on the call site, not just on the GLSL.
        assertTrue(
            renderer.contains("setUniformSamplerArrays(engine.gfx.textureBank.getAllTextureArrays())"),
            "IridescenceRenderer.onRenderBatch no longer binds the texture bank. Every textured " +
            "instance would then sample whichever texture unit was left bound by the previous " +
            "program — a wrong shape, silently, and only for the objects that use a sheet"
        )

        // The size has to match `TextureBank.MAX_TEXTURE_SLOTS` (16, read off the private const's
        // ConstantValue attribute) and the two engine shaders that declare the same uniform.
        // Declaring FEWER than the bank can hold means `sampleTextureArrayGrad`'s switch has no
        // case for a high sampler index and returns vec4(0) — a fully transparent, zero-length
        // normal, i.e. an invisible vent, and only once enough distinct texture sizes/formats have
        // been loaded to push the sheet into a high slot. That is a booth-day failure mode.
        listOf(IridescenceRenderer.VERTEX_SHADER to vert, IridescenceRenderer.FRAGMENT_SHADER to frag)
            .forEach { (path, src) ->
                val declaration = Regex(
                    "uniform\\s+sampler2DArray\\s+${IridescenceRenderer.SAMPLER_ARRAY_UNIFORM}\\s*\\[\\s*(\\d+)\\s*]"
                ).find(stripComments(src))

                if (path == IridescenceRenderer.FRAGMENT_SHADER)
                {
                    assertEquals(
                        IridescenceRenderer.SAMPLER_ARRAY_SIZE.toString(),
                        declaration?.groupValues?.get(1),
                        "$path must declare `uniform sampler2DArray " +
                        "${IridescenceRenderer.SAMPLER_ARRAY_UNIFORM}[${IridescenceRenderer.SAMPLER_ARRAY_SIZE}]`"
                    )
                }
            }

        // Dynamic indexing of a sampler array needs GLSL 400 and this file is 330, so the switch
        // is not a stylistic copy of the engine's — it is the only legal way to do this here, and
        // it must have a case per slot. A missing tail case is the silent-invisible-vent failure
        // above, so count them rather than trusting the copy.
        val cases = Regex(
            "case\\s+(\\d+)\\s*:\\s*return\\s+textureGrad\\(\\s*${IridescenceRenderer.SAMPLER_ARRAY_UNIFORM}\\[\\s*\\1\\s*]"
        ).findAll(stripComments(frag)).count()
        assertEquals(
            IridescenceRenderer.SAMPLER_ARRAY_SIZE, cases,
            "iridescence.frag's sampleTextureArrayGrad has $cases self-consistent cases but the " +
            "sampler array is declared with ${IridescenceRenderer.SAMPLER_ARRAY_SIZE} slots. A " +
            "case that samples a DIFFERENT index than it matches (case 12 -> textureArrays[13]) " +
            "is the copy-paste error this pattern is shaped to catch"
        )
    }

    @Test
    fun `the analytic hemisphere path survives, because the pearls and the air ring have no asset`()
    {
        // Every pearl in the world and every bubble in the game's ONLY air warning (render/Hud.kt)
        // is drawn with normalTex = null and takes the analytic branch. Nothing else draws them;
        // there is no sprite sheet to fall back on. So the textured path must be an ADDITION.
        //
        // These are the three expressions that ARE that path — the unit-square remap, the
        // smoothstep silhouette and the hemisphere's z. Losing any one of them while the file
        // still compiles is entirely possible (a refactor that moves the shape into the texture
        // for everything), and the result is pearls that are square, aliased, or flat-shaded —
        // none of which a compile or a link would object to.
        listOf(
            "vUv * 2.0 - 1.0",
            "smoothstep(1.0 - EDGE_SOFTNESS, 1.0, r)",
            "sqrt(max(1.0 - r * r, 0.0))"
        ).forEach {
            assertTrue(
                stripComments(frag).contains(it),
                "iridescence.frag no longer contains '$it'. The analytic hemisphere is what draws " +
                "every pearl and every air-ring bubble — neither has a sprite sheet behind it"
            )
        }

        // And the branch really is a branch on the engine's own no-texture sentinel, so a null
        // normalTex (which IridescenceRenderer writes as TextureHandle.NONE = create(0, 65534))
        // lands in the analytic side. A branch on, say, `vTexIndex > 0.0` would send layer 0 —
        // a perfectly ordinary layer — down the wrong path.
        assertTrue(
            stripComments(frag).contains("#define NO_TEXTURE 65534"),
            "iridescence.frag must branch on 65534, which is TextureHandle.NONE's layer and the " +
            "same literal the engine's own texture.frag calls NO_TEXTURE"
        )
        assertTrue(
            renderer.contains("TextureHandle.NONE"),
            "IridescenceRenderer must write TextureHandle.NONE for a null normalTex — anything " +
            "else and the shader's NO_TEXTURE branch is unreachable"
        )
    }

    @Test
    fun `the normal sheet's UV rect is read off the Texture and never recomputed`()
    {
        // SpriteSheet.onUploaded has ALREADY composed the atlas-page offset and the per-cell
        // offset into every frame Texture it hands out (uMin_i = uMin + x*(1/hCells)*(uMax-uMin),
        // and each frame carries the sheet's own handle). Deriving a cell rect here as well would
        // apply it twice and land the shader on a sliver of the wrong frame — which would look
        // like a bad bake, not like a bad renderer, and would be debugged in the wrong file.
        //
        // So: the four floats come straight off the Texture, and nothing here divides by a cell
        // count. This is a shape assertion on the source rather than a behavioural one because
        // the behaviour needs a GL context and an uploaded sheet; it is still the mistake most
        // likely to be made by someone adding a second sheet to this renderer.
        listOf("uMin", "vMin", "uMax", "vMax").forEach {
            assertTrue(
                renderer.contains("normalTex?.$it"),
                "IridescenceRenderer.draw must read $it straight off the frame Texture"
            )
        }
        // Comments stripped: the class doc EXPLAINS the cell arithmetic (it has to, or the next
        // person re-derives it), so a raw text search would fire on its own documentation.
        assertTrue(
            !Regex("hCells|vCells|horizontalCells|verticalCells").containsMatchIn(stripComments(renderer)),
            "IridescenceRenderer CODE mentions a cell count. SpriteSheet.onUploaded has already " +
            "folded the cell rect into each frame Texture's uMin/vMin/uMax/vMax — doing it again " +
            "here double-applies it and lands the shader on a sliver of the wrong frame"
        )
    }

    @Test
    fun `the sampled normal's green channel is negated into the shader's own y-down space`()
    {
        // A SIGN, AND THEREFORE THE ONE THING HERE MOST WORTH PINNING. This shader's space has +y
        // DOWN the screen — `vUv` is 0 at the top of the quad — so the analytic hemisphere has
        // n.y = +1 at its BOTTOM edge. Every normal map in this game is baked in the opposite
        // OpenGL convention, green HIGH facing up-screen. Measured on the committed sheets (mean
        // green at the topmost vs bottommost opaque texel of each column): oxygen-normal.png is
        // 216.4 / 33.4, diver-normal.png 195.7 / 71.9. Red needs no flip and is the control:
        // 24.9 leftmost vs 221.0 rightmost.
        //
        // Drop this line and the vent is lit from below whenever the torch is above it. That is
        // NOT visible in a still — render/PearlNormalMap.kt's class doc records the same sign
        // being wrong in shipped code, invisible in a single frame, and only settled by a
        // two-build probe that measured the correct sign as 2.5x stronger. A comment alone would
        // not have survived; this is the assertion that makes deleting it fail the build.
        assertTrue(
            Regex("""decoded\.y\s*=\s*-decoded\.y\s*;""").containsMatchIn(stripComments(frag)),
            "iridescence.frag no longer negates the sampled normal's y. The baked sheets use the " +
            "OpenGL convention (+green = up-screen) and this shader's own space is y-down, so " +
            "without the flip a vent is shaded from the wrong vertical side — and it will look " +
            "plausible in every screenshot"
        )

        // ...and no flip on x, which would be the natural over-correction. The bake's red channel
        // already agrees with `p.x`.
        assertTrue(
            !Regex("""decoded\.x\s*=\s*-decoded\.x\s*;""").containsMatchIn(stripComments(frag)),
            "iridescence.frag negates the sampled normal's x. Measured, the bake's red channel " +
            "already runs the same way as the quad's p.x (24.9 at the leftmost opaque texel, " +
            "221.0 at the rightmost) — flipping it mirrors the vent's lighting horizontally"
        )
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
