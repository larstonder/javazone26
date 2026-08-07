package render

import no.njoh.pulseengine.core.asset.types.Font
import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.api.BlendFunction
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.api.DefaultCamera
import no.njoh.pulseengine.core.graphics.api.DefaultCamera.ProjectionType.ORTHOGRAPHIC
import no.njoh.pulseengine.core.graphics.api.Multisampling
import no.njoh.pulseengine.core.graphics.api.RenderState
import no.njoh.pulseengine.core.graphics.api.RenderTexture
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.postprocessing.PostProcessingEffect
import no.njoh.pulseengine.core.graphics.renderers.BatchRenderer
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.graphics.surface.SurfaceConfig
import no.njoh.pulseengine.core.shared.primitives.Color
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `textOutlineOffset` is the one pure piece of [drawTextWithOutline] — the rest issues real
 * `drawText`/`setDrawColor` calls against a `Surface` and needs a live GL context to verify.
 * What matters here is resolution independence: `engine.window.width/height` are PHYSICAL
 * framebuffer pixels (2400x1800 on a Retina Mac, not the 1200x900 in application.cfg), so a
 * fixed-pixel outline offset that looked right at 1200x900 would be sub-pixel — effectively
 * invisible — at 4K. The offset must scale with screen height instead.
 */
class DrawTest
{
    @Test
    fun `offset scales proportionally with screen height`() {
        val at900 = textOutlineOffset(900f)
        val at1800 = textOutlineOffset(1800f)

        // Doubling the physical resolution must double the offset — a fixed pixel count
        // would instead stay constant (and read as vanishing on the larger screen).
        assertEquals(at900 * 2f, at1800, 0.0001f)
    }

    @Test
    fun `offset reproduces the reference's 2px look at 1080p`() {
        // The reference (caesars-salads) hardcodes a 2px offset at its target resolution
        // (1920x1080). Our fraction-of-height approach should reproduce that exact value
        // at that exact height.
        assertEquals(2f, textOutlineOffset(1080f), 0.01f)
    }

    @Test
    fun `offset is zero for zero height and positive for any positive height`() {
        assertEquals(0f, textOutlineOffset(0f), 0.0001f)
        assertTrue(textOutlineOffset(1800f) > 0f)
    }

    // ---- showsSquare: the centre-in / top-left-out culling conversion ---------------------
    //
    // `Camera.isInView` is the engine's, and it is not what is under test here — what is under
    // test is our conversion into it, which is the only part that can be wrong on our side and
    // the only part that is invisible everywhere except at the frame edge.
    //
    // A `DefaultCamera` is a plain JOML object with no GL in it, and `isInView` reads nothing
    // but `topLeftWorldPosition`/`bottomRightWorldPosition` — both public and settable. So the
    // visible world rect can be posed directly, without a view matrix, a surface or a window.
    // A 106 x 60 metre rect is what a 16:9 booth panel actually shows (60 m of depth by
    // CameraRig's scale, ~106 m across at 16:9); the numbers below are that rect, not a
    // resolution.

    private fun cameraShowing(left: Float, top: Float, right: Float, bottom: Float): Camera =
        DefaultCamera(ORTHOGRAPHIC).apply {
            topLeftWorldPosition.set(left, top)
            bottomRightWorldPosition.set(right, bottom)
        }

    @Test
    fun `a square straddling the frame edge is kept, because it is its rect that is tested and not its centre`()
    {
        val cam = cameraShowing(-53f, 100f, 53f, 160f)

        // Centre 1 m BELOW the bottom edge, with a 4 m square: one metre of it is still on
        // screen. The pixel-row checks this replaces compared the centre alone and would have
        // popped it out here — half a square early, every time, at every edge.
        assertTrue(cam.showsSquare(0f, 161f, 4f), "a square overlapping the bottom edge must be drawn")
        assertTrue(cam.showsSquare(0f, 99f, 4f), "a square overlapping the top edge must be drawn")
        assertTrue(cam.showsSquare(-54f, 130f, 4f), "a square overlapping the left edge must be drawn")

        // And once it is genuinely clear of the edge by more than its own half-size, it goes.
        assertFalse(cam.showsSquare(0f, 163f, 4f), "a square fully below the frame must be culled")
    }

    @Test
    fun `a square far outside the visible half-width is culled, which the old row checks never did`()
    {
        val cam = cameraShowing(-53f, 100f, 53f, 160f)

        // Same depth as the middle of the frame — vertically it is dead centre. Only the x test
        // can reject it, and the checks that went away with the pixel coordinates had none.
        assertFalse(cam.showsSquare(-90f, 130f, 4f), "a square off the left of frame must be culled")
        assertFalse(cam.showsSquare(90f, 130f, 4f), "a square off the right of frame must be culled")
        assertTrue(cam.showsSquare(0f, 130f, 4f), "a square in the middle of frame must be drawn")
    }

    @Test
    fun `padding extends the accepted rect outward, which is what keeps an enlarged light quad alive`()
    {
        val cam = cameraShowing(-53f, 100f, 53f, 160f)

        // 3 m light, centre 4 m past the bottom edge: its own rect clears the frame entirely,
        // so with no padding it is culled. GI's shader can still grow that quad to three times
        // its size (scene.vert's upscaleSmallSources), so DiveLighting pads by one full light
        // size — and with that padding it survives. If padding were ignored, the second
        // assertion would read the same as the first.
        assertFalse(cam.showsSquare(0f, 164f, 3f), "unpadded, a light clear of the frame is culled")
        assertTrue(cam.showsSquare(0f, 164f, 3f, padding = 3f), "padded by its own size, it survives")
    }

    // ---- fillRectCentred: the origin and angle convention the art depends on ---------------
    //
    // What is under test is NOT that the engine draws a centred quad — that is the engine's
    // business and it is verified by looking at a frame. What is under test is the argument
    // list we hand it, because that same argument list has to be handed a SECOND time, to
    // `NormalMapRenderer.drawNormalMap`, when the normal-mapped sprite art lands (see
    // fillRectCentred's doc). If our origin ever stops being the centre, the albedo and the
    // normal are derived apart and the lighting slides off the sprite — a fault that is
    // invisible in every existing test and, at half a sprite, easy to mistake for art.
    //
    // A `Surface` is an abstract class with no GL in its API, so it can simply be implemented.

    @Test
    fun `fillRectCentred hands the engine the centre itself, and the origin that makes it the centre`()
    {
        val surface = RecordingSurface()
        surface.fillRectCentred(12f, -3.5f, 4f, 6f)

        val call = assertNotNull(surface.lastTexture, "fillRectCentred must reach a drawTexture overload")

        // The centre goes through UNCHANGED — no half-size subtracted on the way. This is the
        // half that a `- w * 0.5f` at the call site would duplicate.
        assertEquals(12f, call.x, 0.0001f, "x must be the centre, not a corner")
        assertEquals(-3.5f, call.y, 0.0001f, "y must be the centre, not a corner")
        assertEquals(4f, call.width, 0.0001f)
        assertEquals(6f, call.height, 0.0001f)

        // ...and the origin is what MAKES it the centre. texture.vert:70, normal_map.vert:75 and
        // scene.vert:102 all compute `(vertexPos - origin) * size`, with vertexPos in 0..1, so
        // only 0.5 puts the quad's middle on the position passed. scene.vert has that 0.5
        // hardcoded, which is why DiveLighting's drawLight calls already pass centres.
        assertEquals(0.5f, call.xOrigin, 0.0001f, "xOrigin must be the middle of the quad")
        assertEquals(0.5f, call.yOrigin, 0.0001f, "yOrigin must be the middle of the quad")
        assertEquals(CENTRE_ORIGIN, call.xOrigin, 0.0001f)
    }

    @Test
    fun `fillRectCentred passes an angle through, and defaults it to none`()
    {
        // The angle matters here and not in fillRect because it is the other half of the tuple
        // drawNormalMap takes: a rotated sprite whose normal map is drawn unrotated is lit as if
        // it had never turned. It is degrees in both (texture.vert and normal_map.vert each call
        // radians() on it themselves) and it turns the quad about the same centre above.
        val rotated = RecordingSurface()
        rotated.fillRectCentred(0f, 0f, 1f, 1f, angle = 37f)
        assertEquals(37f, assertNotNull(rotated.lastTexture).angle, 0.0001f, "the angle must reach the engine")

        val unrotated = RecordingSurface()
        unrotated.fillRectCentred(0f, 0f, 1f, 1f)
        assertEquals(0f, assertNotNull(unrotated.lastTexture).angle, 0.0001f, "an unrotated draw must ask for no rotation")
    }

    // ---- The drawQuad / drawLine source guard ----------------------------------------------

    /**
     * `drawQuad` and `drawLine` render NOTHING on macOS/Apple Silicon and — the part that
     * actually reaches players — nothing in the shipped Windows `.exe` either, which
     * deliberately keeps the engine's stock shaders (`build.gradle.kts`, and `render/Draw.kt`
     * for the vertex-attribute binding-type mechanism). No GL error, no log line: a rectangle
     * that simply is not there.
     *
     * The shader shadow in `src/main/resources/pulseengine/shaders/renderers/` repairs both on
     * the DEVELOPMENT classpath only, which is what makes this worth a build failure rather
     * than a comment: from a Mac, a `drawQuad` introduced here looks perfect and ships blank.
     * `fillRect` / `fillRectCentred` go through the texture renderer, which binds that
     * attribute correctly, and `fillRectCentred` additionally offers the rotation that is the
     * usual reason someone reaches for `drawQuad` in the first place.
     *
     * A source scan and not a behaviour test, for the same reason as `MainCameraOwnershipTest`:
     * the failure needs a real framebuffer on a real platform to observe.
     */
    @Test
    fun `no production source draws a quad or a line`()
    {
        val offenders = productionSources().flatMap { (path, code) ->
            code.lineSequence()
                .filter { BROKEN_PRIMITIVE.containsMatchIn(it) }
                .map { "$path: ${it.trim()}" }
        }

        assertTrue(
            offenders.isEmpty(),
            "Surface.drawQuad/drawLine (and their *Vertex forms) rasterise at alpha 0 — silently, " +
            "with no GL error — on macOS AND in the shipped Windows jar, which keeps the engine's " +
            "stock shaders. Use render/Draw.kt's fillRect, or fillRectCentred(x, y, w, h, angle) if " +
            "what you wanted was the rotation. See this test's doc. Found: $offenders"
        )
    }

    private companion object
    {
        /**
         * A CALL to one of the four broken primitives — the trailing `(` is what distinguishes it
         * from the many prose mentions of `drawQuad` in this codebase's comments, and
         * [stripComments] removes those anyway. `drawQuadVertex`/`drawLineVertex` are included
         * because they feed the identical renderers and fail identically.
         */
        val BROKEN_PRIMITIVE = Regex("\\bdraw(Quad|Line)(Vertex)?\\s*\\(")

        /**
         * Every production Kotlin source with its comments stripped. Lifted deliberately from
         * `MainCameraOwnershipTest`, which needs the same thing for the same reason: the fix is
         * documented at length in the very files being scanned, so a scan that read comments
         * would report the explanation as the offence. Line-based rather than a real lexer,
         * which is exact for this codebase's Allman + KDoc style; a false positive is a failing
         * test, not a shipped bug.
         */
        fun productionSources(): List<Pair<String, String>> =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.path to stripComments(it.readText()) }
                .toList()
                .also { assertTrue(it.size > 5, "found only ${it.size} production sources — wrong working directory?") }

        fun stripComments(source: String): String = source
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }
}

/** The argument list a draw call actually reached the engine with. */
private data class TextureCall(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
    val xOrigin: Float,
    val yOrigin: Float
)

/**
 * A [Surface] that records the last textured-quad call instead of rasterising it.
 *
 * `Surface` is an ordinary abstract class whose drawing API is plain floats (no GL handles, no
 * context), so implementing it is cheaper than any mocking framework and it cannot go stale
 * against the engine: an added abstract member breaks the build here rather than being silently
 * unrecorded.
 *
 * All three `drawTexture` overloads record, on purpose. Kotlin resolves
 * `drawTexture(Texture.BLANK, x, y, w, h, angle, xOrigin, yOrigin)` to the shortest applicable
 * signature today, but which overload is picked is not the property under test — the argument
 * values are — and this way the test keeps testing them if that ever changes. Everything else
 * throws: nothing in [fillRectCentred] should be reaching it, and a silent no-op would let a
 * future rewrite pass by drawing through a path this file never checks.
 */
private class RecordingSurface : Surface()
{
    var lastTexture: TextureCall? = null

    override val camera: Camera = DefaultCamera(ORTHOGRAPHIC)
    override val config: SurfaceConfig get() = TODO("no test needs the surface config")

    override fun drawTexture(texture: Texture, x: Float, y: Float, width: Float, height: Float, angle: Float, xOrigin: Float, yOrigin: Float, cornerRadius: Float)
    {
        lastTexture = TextureCall(x, y, width, height, angle, xOrigin, yOrigin)
    }

    override fun drawTexture(texture: Texture, x: Float, y: Float, width: Float, height: Float, angle: Float, xOrigin: Float, yOrigin: Float, cornerRadius: Float, uMin: Float, vMin: Float, uMax: Float, vMax: Float, xTiling: Float, yTiling: Float)
    {
        lastTexture = TextureCall(x, y, width, height, angle, xOrigin, yOrigin)
    }

    override fun drawTexture(texture: RenderTexture, x: Float, y: Float, width: Float, height: Float, angle: Float, xOrigin: Float, yOrigin: Float, cornerRadius: Float, uMin: Float, vMin: Float, uMax: Float, vMax: Float)
    {
        lastTexture = TextureCall(x, y, width, height, angle, xOrigin, yOrigin)
    }

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float) = TODO("nothing here may draw a line")
    override fun drawLineVertex(x: Float, y: Float) = TODO("nothing here may draw a line")
    override fun drawQuad(x: Float, y: Float, width: Float, height: Float) = TODO("nothing here may draw a quad")
    override fun drawQuadVertex(x: Float, y: Float) = TODO("nothing here may draw a quad")
    override fun drawText(text: CharSequence, x: Float, y: Float, font: Font?, fontSize: Float, angle: Float, xOrigin: Float, yOrigin: Float, wrapNewLines: Boolean, newLineSpacing: Float) = TODO("no test draws text")

    override fun setDrawColor(red: Float, green: Float, blue: Float, alpha: Float): Surface = this
    override fun setDrawColor(color: Color): Surface = this
    override fun setBackgroundColor(red: Float, green: Float, blue: Float, alpha: Float): Surface = this
    override fun setBackgroundColor(color: Color): Surface = this
    override fun setBlendFunction(func: BlendFunction): Surface = this
    override fun setMultisampling(multisampling: Multisampling): Surface = this
    override fun setIsVisible(isVisible: Boolean): Surface = this
    override fun setTextureFormat(format: TextureFormat): Surface = this
    override fun setTextureFilter(filter: TextureFilter): Surface = this
    override fun setTextureScale(scale: Float): Surface = this

    override fun getTexture(index: Int, final: Boolean): RenderTexture = TODO("not used")
    override fun getTextures(): List<RenderTexture> = emptyList()
    override fun addPostProcessingEffect(effect: PostProcessingEffect) = TODO("not used")
    override fun getPostProcessingEffects(): List<PostProcessingEffect> = emptyList()
    override fun getPostProcessingEffect(name: String): PostProcessingEffect? = null
    override fun <T : PostProcessingEffect> getPostProcessingEffect(type: Class<T>): T? = null
    override fun deletePostProcessingEffect(name: String) = TODO("not used")
    override fun applyRenderState(state: RenderState) = TODO("not used")
    override fun addRenderer(renderer: BatchRenderer) = TODO("not used")
    override fun getRenderers(): List<BatchRenderer> = emptyList()
    override fun <T : BatchRenderer> getRenderer(type: Class<T>): T? = null
}
