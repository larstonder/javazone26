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
import org.lwjgl.opengl.GL11.GL_FLOAT

/**
 * The sea's surface: one quad a frame, drawn onto `mainSurface`, whose fragment shader's alpha IS
 * the waterline. See `shaders/water.frag` for the optics and [WaterSurface] for the wave table and
 * the clock.
 *
 * ## Copied from [IridescenceRenderer] on purpose
 *
 * That class's doc calls itself "the shader PATH as much as the shader itself — the next piece of
 * custom art should be able to copy this file and change three things". This is that copy, and
 * the three things are the shader paths, the instance layout and the uniforms. Everything
 * structural is identical and deliberately so: `Surface.addRenderer` rather than a
 * `PostProcessingEffect` (the sea is a thing in the world, not a filter over the frame), the
 * `SurfaceConfigInternal` constructor argument so this joins the shared depth cursor, the
 * `init`-guard for GL context recreation, and the one-frame-late `addRenderer` that both call
 * sites must tolerate.
 *
 * ## What is different, and why
 *
 * **It takes no draw colour.** [IridescenceRenderer] reads `config.currentDrawColor` so a pearl
 * can be tinted by `setDrawColor` like any other primitive. The sea's colour is not a tint, it is
 * two sampled points on [DiveRenderer]'s own depth curve handed over as uniforms — precisely so
 * that the bottom edge of this quad IS the top of the first zone band and the two cannot step
 * apart. A packed draw colour would be a second, quieter way to say the same thing.
 *
 * **There is one instance per frame, not one per object.** The instanced path is kept anyway
 * (see `water.vert`'s header): it costs nothing, it keeps this file the same shape as the one it
 * was copied from, and it is what makes the quad participate in the surface's depth ordering
 * rather than landing wherever `addRenderer` happened to put this renderer in the batch order.
 *
 * ## Failure mode
 *
 * `addRenderer` defers `init` to the next frame, and a shader that fails to compile leaves the
 * engine's error program in its place. So [of] returns null on frame one and forever after a
 * compile failure — and unlike a pearl, THERE IS NO FALLBACK DRAW here, because there is nothing
 * sensible to fall back to: a flat rectangle across the waterline is not a degraded sea, it is a
 * blue bar. What happens instead is that [DiveRenderer] draws its zone bands from the top of the
 * visible rect rather than from [WaterSurface.QUAD_BOTTOM_DEPTH], i.e. the game falls all the way
 * back to the opaque flat water it had before there was a sky at all, and logs it once. That is
 * the same principle as `DiverSprite.sheetsReady()` — a degraded frame, never a hole.
 */
class WaterRenderer(private val config: SurfaceConfigInternal) : BatchRenderer()
{
    private lateinit var vao: VertexArrayObject
    private lateinit var vertexBuffer: StaticBufferObject
    private lateinit var instanceBuffer: DoubleBufferedFloatObject
    private lateinit var instanceLayout: VertexAttributeLayout
    private lateinit var program: ShaderProgram

    private var waterNearR = 0f; private var waterNearG = 0f; private var waterNearB = 0f
    private var waterFarR = 0f; private var waterFarG = 0f; private var waterFarB = 0f

    /**
     * The water's colour at the top and the bottom of the band, IN LINEAR SPACE.
     *
     * Taken from [DiveRenderer]'s own zone curve by the caller and converted here, in one place,
     * because getting the space wrong is invisible: `setDrawColor` packs sRGB and the engine's
     * vertex shaders decode it, so a shader handed raw sRGB floats via `setUniform` would draw
     * water about 2.4 stops too bright and the mismatch would only show as a step at the hand-off
     * to the bands. See `DiveRenderer.srgbToLinear`, which mirrors the engine's exact transfer
     * function rather than a `pow(c, 2.2)` approximation.
     */
    fun setWaterColours(
        nearR: Float, nearG: Float, nearB: Float,
        farR: Float, farG: Float, farB: Float
    )
    {
        waterNearR = DiveRenderer.srgbToLinear(nearR)
        waterNearG = DiveRenderer.srgbToLinear(nearG)
        waterNearB = DiveRenderer.srgbToLinear(nearB)
        waterFarR = DiveRenderer.srgbToLinear(farR)
        waterFarG = DiveRenderer.srgbToLinear(farG)
        waterFarB = DiveRenderer.srgbToLinear(farB)
    }

    /**
     * Submit the band, CENTRED on ([centreX], [centreY]) — the same convention as `Draw.kt`'s
     * `fillRectCentred`, `GiSceneRenderer.drawLight` and [IridescenceRenderer.draw], and the origin
     * is hardcoded in the vertex shader for the reason stated there.
     */
    fun draw(centreX: Float, centreY: Float, width: Float, height: Float)
    {
        instanceBuffer.fill(FLOATS_PER_INSTANCE)
        {
            put(centreX, centreY, config.currentDepth)
            put(width, height)
        }
        increaseBatchSize()
        config.increaseDepth()
    }

    override fun init(engine: PulseEngineInternal)
    {
        // Guarded exactly as TextureRenderer and IridescenceRenderer guard it: `init` runs again
        // whenever the GL context is recreated, and only the VAO's bindings have to be rebuilt.
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
        // The space comes from the SURFACE, as in IridescenceRenderer: this renderer is on `main`,
        // so this is engine.gfx.mainCamera and every number in the shader is a metre.
        program.setUniform("viewProjection", surface.camera.viewProjectionMatrix)

        // THE WAVE TABLE, UPLOADED VERBATIM. WaterSurface.components is the only copy of these
        // numbers; there is none in the GLSL. Uploaded per frame rather than once because
        // `program` is rebuilt on a context change and a uniform set at init would be lost.
        val wave = WaterSurface.waveUniform
        for (i in 0 until WaterSurface.components.size)
        {
            val o = i * WaterSurface.FLOATS_PER_COMPONENT
            program.setUniform("wave[$i]", wave[o], wave[o + 1], wave[o + 2], wave[o + 3])
        }
        program.setUniform("time", WaterSurface.phaseSeconds)
        program.setUniform("surfaceDepth", dive.Tuning.SURFACE_DEPTH)
        program.setUniform("bottomDepth", WaterSurface.QUAD_BOTTOM_DEPTH)
        program.setUniform("waterNear", waterNearR, waterNearG, waterNearB)
        program.setUniform("waterFar", waterFarR, waterFarG, waterFarB)
        program.setUniform("sunColor", SUN_COLOUR_R, SUN_COLOUR_G, SUN_COLOUR_B)
        program.setUniform("sunStrength", MENISCUS_STRENGTH, MENISCUS_SUN_STRENGTH, UNDERSIDE_STRENGTH)
        program.setUniform("falloffMetres", MENISCUS_FALLOFF_METRES, UNDERSIDE_FALLOFF_METRES)
        program.setUniform("sunBearing", SUN_BEARING)

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
         * [IridescenceRenderer.VERTEX_SHADER] gives at length: the two files under
         * `src/main/resources/pulseengine/shaders/renderers/` shadow engine files and are
         * EXCLUDED from the release jar by `build.gradle.kts`. These shadow nothing, so the
         * exclusion cannot reach them, and they must ship. `WaterShaderTest` asserts both.
         */
        const val VERTEX_SHADER = "/shaders/water.vert"
        const val FRAGMENT_SHADER = "/shaders/water.frag"

        /** The shared unit quad's only vertex attribute. */
        const val QUAD_ATTRIBUTE = "vertexPos"

        /** Floats written per instance by [draw]. Must equal the layout's stride in floats. */
        const val FLOATS_PER_INSTANCE = 5

        /**
         * The low sun's colour, LINEAR — a warm sodium orange, deliberately warmer and far less
         * blue than `DiveLighting`'s `shaftLight` (0.66, 0.86, 1.0).
         *
         * The god rays are the surface light AFTER it has been through several metres of water,
         * which is why they are cold; this is the light before that has happened. Two lights that
         * are the same colour are one light with a gap in it, and the sea's surface has to read as
         * belonging to the sunset above it rather than to the water below.
         */
        const val SUN_COLOUR_R = 1.00f
        const val SUN_COLOUR_G = 0.44f
        const val SUN_COLOUR_B = 0.16f

        /**
         * How bright the meniscus is along the whole waterline, how much MORE it is where a facet
         * faces the sun, and how much of the light is still in the water several metres down.
         * Linear radiance added on top of the water's own albedo, tuned against captures.
         *
         * THESE ARE LARGE NUMBERS BECAUSE THE SURFACE IS BEHIND THE GI MULTIPLY. Everything this
         * shader draws is on `main` and is multiplied by the light map, which near the waterline
         * measured about 0.4, and then run through an ACES tone mapper whose small-signal gain is
         * about 0.21. The first version of this file used 0.85 and 0.10, reasoning about them as
         * if they were output values, and the meniscus was invisible: measured mean red just under
         * the boundary was 1.0 out of 255 against the water's own blue of 38. A 10x sweep put it
         * at 47.7 and washed seven metres of water warm; these land between the two.
         *
         * They are therefore a RELATIONSHIP to the current lighting, not constants — the same
         * caveat `tools/build_backdrop.py`'s LUMINANCE_FACTOR carries. Re-measure them if the god
         * rays or the ambient curve are reworked.
         *
         * The underside term is small because it applies over metres and everywhere; the meniscus
         * terms are large because `falloffMetres.x` confines them to the first few tens of
         * centimetres under the line.
         */
        const val MENISCUS_STRENGTH = 3.0f
        const val MENISCUS_SUN_STRENGTH = 4.0f
        const val UNDERSIDE_STRENGTH = 0.30f

        /**
         * Metres. The meniscus falloff is a few tens of centimetres — it IS the surface, and a
         * value much larger stops reading as a waterline and starts reading as haze. The underside
         * falloff is stated as a fraction of [WaterSurface.UNDERSIDE_METRES] so that the light has
         * decayed to `e^-3`, i.e. 5%, by the depth at which the quad hands off to the zone bands.
         * Anything slower would leave a visible step there.
         */
        const val MENISCUS_FALLOFF_METRES = 0.25f
        const val UNDERSIDE_FALLOFF_METRES = WaterSurface.UNDERSIDE_METRES / 3f

        /**
         * Which way the sun is along the column: `-1` puts it off to the left, so west-facing wave
         * faces catch it. A bearing rather than a position because the sun is on the horizon and
         * the water is effectively at infinity from it — a position would introduce a
         * false perspective that an orthographic camera cannot support anyway.
         */
        const val SUN_BEARING = -1f

        /**
         * The per-instance layout, as a function so `WaterShaderTest` can build it without a GL
         * context and check every name and TYPE against the GLSL text.
         *
         * Both attributes are `GL_FLOAT` and both are declared `vec` in the shader, so there is no
         * integer/float mismatch to make here — but the check is kept anyway, because the absence
         * of that mismatch is the property worth pinning: `setVertexAttributeLayout` routes
         * `GL_UNSIGNED_INT` through `glVertexAttribIPointer` and `GL_FLOAT` through
         * `glVertexAttribPointer`, and feeding one through the other is undefined behaviour with
         * no GL error and no log line. That is why `Surface.drawQuad` draws nothing at all on
         * macOS.
         */
        fun instanceLayout(): VertexAttributeLayout = VertexAttributeLayout()
            .withAttribute("surfacePos", 3, GL_FLOAT, 1)
            .withAttribute("size", 2, GL_FLOAT, 1)

        /** Every uniform the renderer uploads. Checked against the GLSL by the same test. */
        val UNIFORMS = listOf(
            "viewProjection", "wave", "time", "surfaceDepth", "bottomDepth",
            "waterNear", "waterFar", "sunColor", "sunStrength", "falloffMetres", "sunBearing"
        )

        /** Attach a renderer to [surface] and hand it that surface's own config. */
        fun addTo(surface: Surface) =
            surface.addRenderer(WaterRenderer(surface.config as SurfaceConfigInternal))

        /**
         * The renderer on [surface], or null if it has not been attached yet (frame one) or its
         * shader failed to compile. Callers must fall back to opaque water from the top of the
         * frame rather than leave a hole — see the class doc.
         */
        fun of(surface: Surface): WaterRenderer? = surface.getRenderer(WaterRenderer::class.java)
    }
}
