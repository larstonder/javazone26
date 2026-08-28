package render

import no.njoh.pulseengine.core.PulseEngineInternal
import no.njoh.pulseengine.core.asset.types.FragmentShader
import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.asset.types.VertexShader
import no.njoh.pulseengine.core.graphics.api.ShaderProgram
import no.njoh.pulseengine.core.graphics.api.TextureHandle
import no.njoh.pulseengine.core.graphics.api.VertexAttributeLayout
import no.njoh.pulseengine.core.graphics.api.objects.DoubleBufferedFloatObject
import no.njoh.pulseengine.core.graphics.api.objects.StaticBufferObject
import no.njoh.pulseengine.core.graphics.api.objects.VertexArrayObject
import no.njoh.pulseengine.core.graphics.renderers.BatchRenderer
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.graphics.surface.SurfaceConfigInternal
import no.njoh.pulseengine.core.graphics.util.DrawUtils.drawInstancedQuads
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT

/**
 * THE GAME'S FIRST CUSTOM SHADER, and therefore the shader PATH as much as the shader itself —
 * the next piece of custom art should be able to copy this file and change three things.
 *
 * ## The extension point, and why this one
 *
 * Pulse Engine offers two: `PostProcessingEffect`/`BaseEffect`, which is a full-screen pass
 * over a surface's texture, and `Surface.addRenderer(BatchRenderer)`, which is a new primitive
 * you draw with. An iridescent pearl is a per-object surface property, not a screen-wide
 * filter, so it is a primitive. `GiSceneRenderer` (which `DiveLighting` already calls
 * immediate-mode) and the engine's own `TextureRenderer` are the worked examples; this is
 * structurally a stripped-down `TextureRenderer` — one shared unit quad, one instance per
 * object, `glDrawArraysInstanced`.
 *
 * ## ONE SHADER, TWO SURFACES, TWO COORDINATE SPACES
 *
 * This is the structural problem the task set, and the answer is that it is not a problem at
 * the shader level at all: a `BatchRenderer` is attached to ONE surface, and `onRenderBatch`
 * is handed that surface. So
 *
 *     program.setUniform("viewProjection", surface.camera.viewProjectionMatrix)
 *
 * uploads `engine.gfx.mainCamera` when this renderer is the one on `main` (world METRES) and
 * the HUD's own identity `DefaultCamera` when it is the one on `"hud"` (screen PIXELS). Two
 * instances of this class, one program's worth of GLSL, no mode flag, and — the part that
 * matters — nothing in the shader that could be right in one space and wrong in the other.
 * That is exactly how the engine's own `TextureRenderer` already serves both surfaces, and it
 * is why `Draw.kt`'s `fillRect` needs no space argument either.
 *
 * The one thing that IS space-dependent, the torch's position, is therefore a uniform that each
 * instance is given in its own units ([setLightSource]). Everything the fragment shader does
 * with it is scale-free by construction — it normalises the in-plane direction before using it —
 * so no pixels-per-metre ever reaches the GLSL. See iridescence.frag.
 *
 * ## TWO WAYS TO GET A SHAPE, and why the same texture supplies BOTH the normal and the silhouette
 *
 * A pearl and a HUD air bubble are round, so `iridescence.frag` derives their normal and their
 * silhouette analytically from the quad's UV — a unit hemisphere inscribed in the quad. An oxygen
 * vent is a 96-frame animated blob and has no closed form, so it brings a baked normal-map sprite
 * sheet instead ([draw]'s `normalTex`). ONE texture covers the whole job because the analytic
 * path's two outputs are the same two numbers a normal map already stores: the shader's `z` — the
 * hemisphere's out-of-screen component, which is what `rimness = 1 - z` reads — IS the normal
 * map's BLUE channel, and the coverage the analytic path gets from `smoothstep` over the radius is
 * the sheet's ALPHA. So there is no second silhouette texture and no second sampler.
 *
 * The default is null and the analytic path is byte-for-byte what it always was, because the
 * pearls and the air ring depend on it and neither has an asset behind it.
 *
 * ## How a texture reaches the shader: a TEXTURE ARRAY BANK and a packed handle
 *
 * Not bindless, and not a `glBindTexture` per draw — that would break batching, which is the only
 * reason this renderer exists. Pulse Engine keeps every uploaded texture in a small set of
 * `GL_TEXTURE_2D_ARRAY`s bucketed by max(width, height) and FORMAT (`TextureBank`, verified from
 * bytecode: `getOrCreateTextureArrayFor` buckets on both), binds all of them at once, and puts
 * *which array* and *which layer* into a single per-instance `uint`:
 *
 *     high 16 bits = the sampler index (which of the bound `sampler2DArray`s)
 *     low  16 bits = the layer within it
 *
 * (`TextureHandle.getSamplerIndex-impl` is `ishr 16; iand 65535`, `getTextureIndex-impl` is
 * `iand 65535`, `create-FfHxNlQ(a, b)` is `(a << 16) | b`.) The 32 bits ride in the FLOAT instance
 * buffer as raw bits — `TextureHandle.toFloat-impl` is `Float.intBitsToFloat` — and arrive intact
 * as a GLSL `uint` only because the attribute is declared `GL_UNSIGNED_INT`, so
 * `setVertexAttributeLayout` routes it through `glVertexAttribIPointer`. Declare it `GL_FLOAT` and
 * the driver normalises/converts the bits and the shader indexes a nonsense sampler, silently —
 * the same class of defect as `Surface.drawQuad` on macOS (`render/Draw.kt`).
 *
 * `TextureHandle.NONE` is `create(0, 65534)`, which is the literal `#define NO_TEXTURE 65534` in
 * the engine's own `texture.frag`; that is the sentinel the fragment shader branches on, so a null
 * `normalTex` and an engine draw with no texture take the same path for the same reason.
 *
 * ## A Texture is a SUB-RECT, and `SpriteSheet` has already done the cell arithmetic
 *
 * `TextureArray.upload` sets `uMax = width/textureSize`, `vMax = height/textureSize` — a texture
 * occupies a corner of its array layer, so its UVs are NOT 0..1. On top of that,
 * `SpriteSheet.onUploaded` composes the page offset AND the per-cell offset into every frame
 * `Texture` it hands out:
 *
 *     uMin_i = uMin + x*(1/hCells)*(uMax - uMin);   uMax_i = uMin_i + (1/hCells)*(uMax - uMin)
 *
 * and every frame carries the SAME handle as the sheet. So `getTexture(i)` is already the final
 * answer: [draw] reads the four floats straight off the `Texture` and computes NOTHING. Deriving a
 * cell rect here would double-apply the one the engine already applied and land the shader on a
 * fraction of the wrong frame.
 *
 * ## Depth, and why `config` is a constructor argument
 *
 * The world surface has a depth attachment, so `BatchRenderBaseState` enables `GL_LEQUAL` depth
 * testing and the engine's renderers order themselves by writing `config.currentDepth` and
 * bumping it a fraction after every draw. Batch renderers run in the order they were ADDED to
 * the surface, not in call order, so without participating in that scheme every pearl would
 * draw on top of the diver regardless of when it was submitted. [draw] therefore takes the
 * current depth and advances it exactly as `TextureRenderer.draw` does.
 *
 * Taking the same `SurfaceConfigInternal` is also what makes `setDrawColor` work on this
 * renderer: the packed draw colour is read from the same field every other primitive reads it
 * from, so a pearl is coloured the way a `fillRect` is coloured and the HUD's cold/danger
 * switch on the air ring keeps working with no new plumbing.
 *
 * ## Failure mode
 *
 * `addRenderer` defers `init` to the next frame (`SurfaceImpl.addRenderer` queues it via
 * `runOnInitFrame`), so this renderer does not exist on frame one, and a shader that fails to
 * compile leaves the engine's error program in its place. Both call sites therefore fall back
 * to a flat `fillRectCentred` when [of] returns null — the exact look the game had before this
 * commit. Same arrangement as `DiverSprite.sheetsReady()`: a degraded frame, never a missing
 * object and never an exception in front of a queue.
 */
class IridescenceRenderer(private val config: SurfaceConfigInternal) : BatchRenderer()
{
    private lateinit var vao: VertexArrayObject
    private lateinit var vertexBuffer: StaticBufferObject
    private lateinit var instanceBuffer: DoubleBufferedFloatObject
    private lateinit var instanceLayout: VertexAttributeLayout
    private lateinit var program: ShaderProgram

    private var lightX = 0f
    private var lightY = 0f

    /**
     * Where the light driving the interference is, IN THIS SURFACE'S OWN SPACE — world metres
     * for the renderer on `main`, screen pixels for the one on `"hud"`.
     *
     * Called once per frame, before the draws it applies to. Deliberately not per-object: the
     * whole point of the light bearing (see iridescence.frag) is that it is one position the
     * whole frame is measured against, so making it per-object would let two pearls disagree
     * about where the torch is.
     */
    fun setLightSource(x: Float, y: Float)
    {
        lightX = x
        lightY = y
    }

    /**
     * Submit one iridescent object, CENTRED on ([centreX], [centreY]) — same convention as
     * `Draw.kt`'s `fillRectCentred` and as `GiSceneRenderer.drawLight`, and the origin is
     * hardcoded in the vertex shader for the reason stated there.
     *
     * No angle. Neither a pearl nor a bubble has a facing, and the shader's own geometry is a
     * radially symmetric hemisphere, so a rotation would be a parameter that provably cannot
     * change a pixel.
     *
     * [normalTex] is the shape, when the object has one that is not a hemisphere: its RGB is the
     * surface normal (`rgb * 2 - 1`) and its ALPHA is the silhouette. See the class doc for why
     * one texture is enough for both, and for what the four UVs and the packed handle are.
     *
     * IT MUST BE A LINEAR-FORMAT TEXTURE — `TextureFormat.RGBA8`, never `SRGBA8`. The GPU
     * linearises an sRGB texture on sample, which would put every component of the normal through
     * a 2.4-power curve and leave a vector that is neither unit-length nor pointing where it was
     * baked to point. Measured: `sprites/oxygen-normal.png` decodes RAW (`rgb/255*2-1`) to mean
     * length 1.0000, exactly as `sprites/diver-normal.png` does. The declaration lives with the
     * asset (`render/OxygenSprite.kt`); this is the consumer stating what it needs. The format is
     * part of the texture-array bucket key, so a normal sheet and a diffuse sheet land in
     * different arrays with different sampler indices — which the packed handle already carries,
     * so nothing here has to care which array it ended up in.
     *
     * Pass `null` (the default) for the analytic hemisphere. Existing call sites — the pearls in
     * `DiveRenderer` and the air ring in `Hud` — are untouched by construction.
     */
    fun draw(
        centreX: Float,
        centreY: Float,
        width: Float,
        height: Float,
        material: IridescentMaterial,
        normalTex: Texture? = null
    )
    {
        // Hoisted out of the fill lambda so the null test happens once and, more importantly, so
        // `TextureHandle` is never boxed: `normalTex?.handle ?: TextureHandle.NONE` would box the
        // inline value class to give the elvis a nullable operand, i.e. one allocation per object
        // per frame in the render path. Both branches here compile to a static `toFloat-impl`.
        val uMin = normalTex?.uMin ?: 0f
        val vMin = normalTex?.vMin ?: 0f
        val uMax = normalTex?.uMax ?: 1f
        val vMax = normalTex?.vMax ?: 1f
        val texHandle = if (normalTex != null) normalTex.handle.toFloat() else TextureHandle.NONE.toFloat()

        instanceBuffer.fill(FLOATS_PER_INSTANCE)
        {
            put(centreX, centreY, config.currentDepth)
            put(width, height)
            put(material.filmThickness, material.amplitude, material.sheen)
            put(material.centreOpacity, material.rimOpacity)
            put(config.currentDrawColor)
            // THIS ORDER IS THE LAYOUT'S ORDER. `instanceLayout()` describes the same bytes to GL
            // by offset alone — no names cross the boundary — so a write here that is not mirrored
            // there feeds every attribute after it the previous one's bits. See the stride test.
            put(uMin, vMin)
            put(uMax, vMax)
            put(texHandle)
        }
        increaseBatchSize()
        config.increaseDepth()
    }

    override fun init(engine: PulseEngineInternal)
    {
        // Guarded exactly as TextureRenderer guards it: `init` is called again whenever the GL
        // context is recreated (a window/fullscreen change), and the program and its buffers
        // survive that — only the VAO's attribute bindings have to be rebuilt.
        if (!this::program.isInitialized)
        {
            vertexBuffer = StaticBufferObject.createQuadVertexArrayBuffer()
            instanceBuffer = DoubleBufferedFloatObject.createArrayBuffer()
            instanceLayout = instanceLayout()
            program = ShaderProgram.create(
                engine.asset.loadNow(VertexShader(VERTEX_SHADER)),
                engine.asset.loadNow(FragmentShader(FRAGMENT_SHADER))
            )
        }

        val vertexLayout = VertexAttributeLayout().withAttribute(QUAD_ATTRIBUTE, 2, GL_FLOAT)

        vao = VertexArrayObject.createAndBind()
        program.bind()
        vertexBuffer.bind()
        program.setVertexAttributeLayout(vertexLayout)
        instanceBuffer.bind()
        program.setVertexAttributeLayout(instanceLayout)
        vao.release()
    }

    override fun onInitFrame()
    {
        instanceBuffer.swapBuffers()
    }

    override fun onRenderBatch(engine: PulseEngineInternal, surface: Surface, startIndex: Int, drawCount: Int)
    {
        if (startIndex == 0)
        {
            instanceBuffer.bind()
            instanceBuffer.submit()
            instanceBuffer.release()
        }

        vao.bind()
        program.bind()
        // THE SPACE COMES FROM THE SURFACE, NOT FROM THIS CLASS — see the class doc. This one
        // line is what makes the same GLSL correct in metres on `main` and in pixels on `hud`.
        program.setUniform("viewProjection", surface.camera.viewProjectionMatrix)
        program.setUniform("lightPos", lightX, lightY)
        // The whole texture bank, bound in one call — this is verbatim the only line
        // `TextureRenderer.onRenderBatch` needs for its textures, and it is what makes an
        // instanced batch able to reference many different textures without a bind per draw.
        // `setUniformSamplerArrays` loops the bank calling glActiveTexture(GL_TEXTURE0 + i) +
        // glBindTexture(GL_TEXTURE_2D_ARRAY, id) + setUniform("textureArrays[i]", i).
        //
        // Unconditional, even on a batch with no textured object in it. It costs a handful of
        // binds on a renderer that issues one draw call per frame, and making it conditional would
        // mean tracking whether any instance in the batch was textured — state that could go stale
        // in exactly the direction that samples an unbound sampler.
        program.setUniformSamplerArrays(engine.gfx.textureBank.getAllTextureArrays())
        drawInstancedQuads(instanceBuffer, instanceLayout, program, drawCount, startIndex)
        vao.release()
    }

    override fun destroy()
    {
        vertexBuffer.destroy()
        instanceBuffer.destroy()
        program.destroy()
        vao.destroy()
    }

    companion object
    {
        /**
         * OUR shaders, deliberately NOT under `/pulseengine/`. Two files in
         * `src/main/resources/pulseengine/shaders/renderers/` shadow engine files and are
         * excluded from the release jar by `build.gradle.kts`; these do not shadow anything, so
         * they cannot be caught by that exclusion and they must ship. `IridescenceShaderTest`
         * asserts both properties.
         *
         * The leading slash is required — `Extensions.loadTextFromDisk` treats a non-absolute
         * path as a classpath resource and prefixes one anyway, but the engine's own shader
         * paths all carry it and a missing file here fails as a shader that will not compile.
         */
        const val VERTEX_SHADER = "/shaders/iridescence.vert"
        const val FRAGMENT_SHADER = "/shaders/iridescence.frag"

        /** The shared unit quad's only vertex attribute. */
        const val QUAD_ATTRIBUTE = "vertexPos"

        /**
         * How much bigger a quad has to be for the DISC this shader inscribes in it to cover the
         * same area as the square that quad used to be filled with: `2 / sqrt(pi)` = 1.1284.
         *
         * ## Why this is not a cosmetic detail
         *
         * `iridescence.frag` discards everything outside the unit circle, so switching a pearl
         * from `fillRectCentred` to this renderer silently removed `1 - pi/4` = 21.5% of its
         * drawn area. Two things depend on that area, and both are load-bearing:
         *
         *  - **The world surface's exposure.** `mainSurface` is multiplied by GI's light map and
         *    then run through a THRESHOLDED bloom (threshold 1.4, `DiveLighting.setup`), which
         *    is violently non-linear: a pearl in the Abyss sits far above the threshold, so the
         *    bloom's energy is roughly `area x (albedo x light - threshold)` and shrinking the
         *    area cuts the halo by more than the area itself. Measured against a same-build
         *    control pair (which agreed to 0.001/255) the round mask ALONE — with the emitters
         *    untouched, and they are untouched — took a 16:9 abyss frame from a mean of 14.60 to
         *    7.41 out of 255. That is the abyss's balance moving, which is exactly what this
         *    task was told not to do.
         *  - **The air ring's visual mass.** The ring is the game's only air warning (`Hud`).
         *    Quietly making every bubble a fifth smaller is a gameplay regression.
         *
         * So the QUAD grows and the drawn area stays put. The alternative — leaving the quad
         * alone and brightening the material to compensate — would have been a change to what a
         * pearl emits in all but name, and it could not have been stated exactly: this factor
         * is exact, `IridescenceGeometryTest` proves it, and it is the same number for every
         * object this shader draws.
         *
         * Note what this does NOT compensate for and could not: the film desaturates the pearl,
         * so its RED specifically is lower than the flat amber's even at equal area, while its
         * blue is much higher. The composition preserves mean LUMINANCE exactly (see that test),
         * not per-channel radiance, and bloom is per-channel. The residual is measured in this
         * task's report rather than hidden.
         */
        val EQUAL_AREA_DISC_SCALE = (2.0 / kotlin.math.sqrt(Math.PI)).toFloat()

        /**
         * The quad size that draws a disc of the same area as a [squareSize] square — the size
         * to pass [draw] wherever this renderer replaces a `fillRectCentred`.
         *
         * A function rather than a per-call-site multiply for the reason `fillRectCentred`'s own
         * doc gives: it is used from two files for two different objects, and a factor written
         * out twice is a factor that gets edited once.
         */
        fun equalAreaQuad(squareSize: Float) = squareSize * EQUAL_AREA_DISC_SCALE

        /** Floats written per instance by [draw]. Must equal the layout's stride in floats. */
        const val FLOATS_PER_INSTANCE = 16

        /**
         * The sampler-array uniform, and its declared size.
         *
         * SIXTEEN IS THE ENGINE'S OWN CAP, not a guess and not merely a convention shared with
         * `texture.frag`/`normal_map.frag`. `TextureBank.MAX_TEXTURE_SLOTS` is a private
         * `const` = 16 (read off the ConstantValue attribute, and it appears in
         * `getOrCreateTextureArrayFor` as a `bipush 16` guard on creating another array), so the
         * list `getAllTextureArrays` returns can never be longer than the array declared here.
         *
         * Were it ever to grow past this, the overflow is survivable rather than fatal:
         * `setUniformSamplerArray` would ask for `textureArrays[16]`, `uniformLocationOf` would
         * fail to find it and cache a -2 sentinel, and one WARN per name per program would be
         * logged — once, not once a frame.
         *
         * Not in [UNIFORMS] because nothing calls `setUniform` for it by name; it is uploaded by
         * `setUniformSamplerArrays`, which derives the indexed names itself. `IridescenceShaderTest`
         * checks it separately for exactly that reason.
         */
        const val SAMPLER_ARRAY_UNIFORM = "textureArrays"
        const val SAMPLER_ARRAY_SIZE = 16

        /**
         * The per-instance vertex attribute layout, as a function so `IridescenceShaderTest` can
         * build it without a GL context and check every name and TYPE against the GLSL text.
         *
         * The type is the half worth checking. `ShaderProgram.setVertexAttributeLayout` routes
         * `GL_UNSIGNED_INT` through `glVertexAttribIPointer` and `GL_FLOAT` through
         * `glVertexAttribPointer`; feeding an integer-declared shader input through the float
         * path is undefined behaviour that raises no GL error and logs nothing. That is not a
         * hypothetical — it is precisely why `Surface.drawQuad` draws nothing at all on macOS
         * (see `render/Draw.kt`), a defect that took a framebuffer read-back to find.
         */
        fun instanceLayout(): VertexAttributeLayout = VertexAttributeLayout()
            .withAttribute("surfacePos", 3, GL_FLOAT, 1)
            .withAttribute("size", 2, GL_FLOAT, 1)
            .withAttribute("filmParams", 3, GL_FLOAT, 1)
            .withAttribute("bodyParams", 2, GL_FLOAT, 1)
            .withAttribute("color", 1, GL_UNSIGNED_INT, 1)
            // The normal-map sheet's frame rect and its bank address. Same names, same types and
            // the same trailing position as `TextureRenderer`'s own layout, so the two can be
            // compared side by side. GL_UNSIGNED_INT on `texHandle` is the load-bearing half —
            // see the class doc and this layout's own note above.
            .withAttribute("uvMin", 2, GL_FLOAT, 1)
            .withAttribute("uvMax", 2, GL_FLOAT, 1)
            .withAttribute("texHandle", 1, GL_UNSIGNED_INT, 1)

        /** Every uniform the renderer uploads. Checked against the GLSL by the same test. */
        val UNIFORMS = listOf("viewProjection", "lightPos")

        /**
         * Attach a renderer to [surface] and hand it that surface's own config.
         *
         * The cast is the price of `Surface.config` being typed as the read-only
         * `SurfaceConfig` interface while the mutable draw-colour and depth cursor every
         * primitive shares live on `SurfaceConfigInternal` — the same public class the engine
         * hands its own `TextureRenderer`. There is exactly one implementation
         * (`SurfaceImpl` constructs it directly), so this cannot fail at runtime; it is written
         * once, here, rather than at each of the two call sites.
         */
        fun addTo(surface: Surface) =
            surface.addRenderer(IridescenceRenderer(surface.config as SurfaceConfigInternal))

        /**
         * The renderer on [surface], or null if it has not been attached yet (frame one — see
         * the class doc). Callers must fall back to a flat fill rather than skip the object.
         */
        fun of(surface: Surface): IridescenceRenderer? = surface.getRenderer(IridescenceRenderer::class.java)
    }
}
