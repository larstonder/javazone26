package render

import no.njoh.pulseengine.core.PulseEngineInternal
import no.njoh.pulseengine.core.asset.types.FragmentShader
import no.njoh.pulseengine.core.asset.types.VertexShader
import no.njoh.pulseengine.core.graphics.api.ShaderProgram
import no.njoh.pulseengine.core.graphics.api.VertexAttributeLayout
import no.njoh.pulseengine.core.graphics.api.objects.DoubleBufferedFloatObject
import no.njoh.pulseengine.core.graphics.api.objects.StaticBufferObject
import no.njoh.pulseengine.core.graphics.api.objects.VertexArrayObject
import no.njoh.pulseengine.core.graphics.renderers.BatchRenderer
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.graphics.surface.SurfaceConfigInternal
import no.njoh.pulseengine.core.graphics.util.DrawUtils.drawInstancedQuads
import no.njoh.pulseengine.core.shared.primitives.Color
import org.lwjgl.opengl.GL11.GL_FLOAT
import kotlin.math.pow

/**
 * The god rays' renderer: one horizontal strip of the overlay per instance, one program's worth
 * of GLSL in `shaders/godrays.{vert,frag}`, attached to `mainSurface`.
 *
 * `IridescenceRenderer` is the worked example and this is deliberately the same shape — a shared
 * unit quad, one instance per object, `glDrawArraysInstanced`, `SurfaceConfigInternal` taken in
 * the constructor so this participates in the shared depth cursor and the shared draw colour.
 * Read that file's class doc for the extension-point reasoning; only the differences are here.
 *
 * ## IT IS ATTACHED TO `main`, WHICH IS THE WHOLE DESIGN DECISION
 *
 * The previous two passes drew the shafts as GI lights because CLAUDE.md states that
 * `GlobalIlluminationSystem` MULTIPLIES `mainSurface` by the light map, which would crush albedo
 * to nothing exactly where the water is dark. **The engine's own shader says otherwise:**
 * `pulseengine/shaders/lighting/global/final.frag:57` is `fragColor = vec4(base + light, 1.0)`,
 * with `baseTex` bound to mainSurface's texture by `GiFinal`. The composite is ADDITIVE. So there
 * was nothing to escape from, no second surface is needed, and the shafts live where every other
 * pixel of the world lives — which also gets them the ACES tone mapper and the bloom that
 * `DiveLighting.setup` puts on that surface, both of which a volumetric shaft wants.
 *
 * `LightShafts`'s class doc has the consequence stated honestly: these no longer interact with
 * the light map in either direction.
 *
 * ## DRAW ORDER
 *
 * `DiveLighting.render` runs after `DiveRenderer.render` inside `onRender`, so the strips take a
 * larger `config.currentDepth` than everything in the world and land in FRONT of it. That is
 * correct for this effect rather than incidental: what a god ray is, is scattering in the water
 * BETWEEN the viewer and the scene, so it belongs in front of the diver and the pearls, hazing
 * them very slightly rather than being occluded by them.
 *
 * ## FAILURE MODE
 *
 * `addRenderer` defers `init` to the next frame (`SurfaceImpl.addRenderer` queues it via
 * `runOnInitFrame`), so this renderer does not exist on frame one, and a shader that fails to
 * compile leaves the engine's error program in its place. `DiveLighting.drawLightShafts`
 * therefore draws NOTHING when [of] returns null — unlike the pearls, which fall back to a flat
 * fill, there is no degraded god ray worth drawing and scenery missing for one frame at boot is
 * invisible. Same judgement `LightEmitter.shaftEmitter` used to make for the same reason.
 */
class ShaftRenderer(private val config: SurfaceConfigInternal) : BatchRenderer()
{
    private lateinit var vao: VertexArrayObject
    private lateinit var vertexBuffer: StaticBufferObject
    private lateinit var instanceBuffer: DoubleBufferedFloatObject
    private lateinit var instanceLayout: VertexAttributeLayout
    private lateinit var program: ShaderProgram

    private var tintRed = 1f
    private var tintGreen = 1f
    private var tintBlue = 1f
    private var tintAlpha = 1f

    /**
     * The band colour, in the sRGB [Color] this project authors colours in — converted to linear
     * here and uploaded as a uniform.
     *
     * ## WHY THIS IS NOT `surface.setDrawColor`, WHICH IS WHAT EVERY OTHER PRIMITIVE USES
     *
     * `SurfaceConfigInternal.currentDrawColor` is SHARED STATE on the surface, and nothing resets
     * it between frames. These strips are the last thing drawn to `main`, so the colour they set
     * was still current when `DiveRenderer` drew the FIRST object of the next frame — and at 0.7
     * alpha that silently drew part of the world at 70% opacity.
     *
     * Caught by measurement rather than by reading: a control build with the peak opacity at zero
     * left the draw colour fully TRANSPARENT, and its 40 m frame lost most of its pearls, reading
     * as a 113% "improvement" from the shafts that was really two thirds of the pearls missing
     * from the control. Two variables in one measurement — exactly what `HARNESS.md` warns a
     * control pair is for.
     *
     * A save-and-restore around the draw would work and is what a `fillRect` caller does
     * implicitly by always setting its own colour first. A uniform is better: it cannot be
     * forgotten, it cannot be got wrong by a later edit that adds a second draw, and this
     * renderer draws exactly one colour anyway.
     */
    fun setTint(color: Color)
    {
        tintRed = srgbToLinear(color.red)
        tintGreen = srgbToLinear(color.green)
        tintBlue = srgbToLinear(color.blue)
        tintAlpha = color.alpha
    }

    /**
     * Submit one horizontal strip of the overlay, CENTRED on ([centreX], [centreDepth]) — same
     * convention as `Draw.kt`'s `fillRectCentred` and `IridescenceRenderer.draw`.
     *
     * [rampTop] and [rampBottom] are the depth ramp at the strip's two horizontal edges, which
     * the vertex shader interpolates across it. They are a pair rather than one value because a
     * single value per strip would step at every strip boundary, and fifteen faint horizontal
     * lines across the water is a worse artefact than anything this effect is trying to add. See
     * `LightShafts.STRIP_COUNT` for why the ramp is sampled in Kotlin at all.
     */
    fun draw(centreX: Float, centreDepth: Float, width: Float, height: Float, rampTop: Float, rampBottom: Float)
    {
        instanceBuffer.fill(FLOATS_PER_INSTANCE)
        {
            put(centreX, centreDepth, config.currentDepth)
            put(width, height)
            put(rampTop, rampBottom)
        }
        increaseBatchSize()
        config.increaseDepth()
    }

    override fun init(engine: PulseEngineInternal)
    {
        // Guarded exactly as TextureRenderer and IridescenceRenderer guard it: `init` runs again
        // whenever the GL context is recreated (a window/fullscreen change) and only the VAO's
        // attribute bindings have to be rebuilt.
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
        program.setUniform("viewProjection", surface.camera.viewProjectionMatrix)

        // THE BAND STACK IS UPLOADED, NOT HARDCODED IN THE GLSL, and that is the point of the
        // split: every number below is a Kotlin constant `LightShaftsTest` asserts against, and
        // GLSL cannot be unit-tested at all. The phases already carry the animation clock — the
        // drift is applied in `LightShafts.phase`, on the CPU, so the shader has no clock of its
        // own and a pinned capture (LightShafts.PIN_ENV) is reproducible for free.
        val bands = LightShafts
        program.setUniform("bandFrequency", bands.frequency(0), bands.frequency(1), bands.frequency(2), bands.frequency(3))
        program.setUniform("bandAmplitude", bands.amplitude(0), bands.amplitude(1), bands.amplitude(2), bands.amplitude(3))
        program.setUniform("bandPhase", bands.phase(0), bands.phase(1), bands.phase(2), bands.phase(3))
        program.setUniform("tint", tintRed, tintGreen, tintBlue, tintAlpha)
        program.setUniform("apex", LightShafts.APEX_X, LightShafts.APEX_HEIGHT_METRES)
        program.setUniform("bandEdges", LightShafts.BAND_THRESHOLD, LightShafts.BAND_PEAK)
        program.setUniform("surfaceFade", LightShafts.SURFACE_FADE_METRES)

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
         * OUR shaders, deliberately NOT under `/pulseengine/`, for the reason
         * `IridescenceRenderer.VERTEX_SHADER` states: the two files in
         * `src/main/resources/pulseengine/shaders/renderers/` shadow engine files and are dropped
         * from the release jar by `build.gradle.kts`; these shadow nothing, so they cannot be
         * caught by that exclusion and they must ship. `GodRayShaderTest` asserts both.
         */
        const val VERTEX_SHADER = "/shaders/godrays.vert"
        const val FRAGMENT_SHADER = "/shaders/godrays.frag"

        /** The shared unit quad's only vertex attribute. */
        const val QUAD_ATTRIBUTE = "vertexPos"

        /** Floats written per instance by [draw]. Must equal the layout's stride in floats. */
        const val FLOATS_PER_INSTANCE = 7

        /**
         * The per-instance vertex attribute layout, as a function so `GodRayShaderTest` can build
         * it without a GL context and check every name and TYPE against the GLSL text.
         *
         * The TYPE is the half worth checking. `ShaderProgram.setVertexAttributeLayout` routes
         * `GL_UNSIGNED_INT` through `glVertexAttribIPointer` and `GL_FLOAT` through
         * `glVertexAttribPointer`; feeding an integer-declared shader input through the float
         * path is undefined behaviour that raises no GL error and logs nothing — precisely why
         * `Surface.drawQuad` rasterises at alpha 0 on macOS (see `render/Draw.kt`).
         */
        fun instanceLayout(): VertexAttributeLayout = VertexAttributeLayout()
            .withAttribute("stripPos", 3, GL_FLOAT, 1)
            .withAttribute("size", 2, GL_FLOAT, 1)
            .withAttribute("ramp", 2, GL_FLOAT, 1)

        /** Every uniform the renderer uploads. Checked against the GLSL by the same test. */
        val UNIFORMS = listOf(
            "viewProjection", "bandFrequency", "bandAmplitude", "bandPhase",
            "tint", "apex", "bandEdges", "surfaceFade"
        )

        /**
         * The engine's own sRGB-to-linear transfer function, transcribed from
         * `renderers/texture.vert`'s `unpackAndConvert` — the same curve `DiveRenderer
         * .srgbToLinear` mirrors, and copied for the same reason it is: near-black is where a
         * plain `pow(c, 2.2)` and this curve disagree most, and near-black is most of this water.
         *
         * Needed because the tint is a uniform rather than a packed vertex colour (see [setTint]),
         * so the conversion the vertex shader used to do has to happen somewhere, and Kotlin is
         * where it can be read.
         */
        internal fun srgbToLinear(c: Float): Float =
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

        /**
         * Attach a renderer to [surface] and hand it that surface's own config. The cast is the
         * price of `Surface.config` being typed as the read-only `SurfaceConfig` while the
         * mutable draw colour and depth cursor live on `SurfaceConfigInternal` — see
         * `IridescenceRenderer.addTo`, which pays it for the same reason.
         */
        fun addTo(surface: Surface) =
            surface.addRenderer(ShaftRenderer(surface.config as SurfaceConfigInternal))

        /** The renderer on [surface], or null before `init` has run (frame one). */
        fun of(surface: Surface): ShaftRenderer? = surface.getRenderer(ShaftRenderer::class.java)
    }
}
