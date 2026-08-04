package render

import no.njoh.pulseengine.core.PulseEngineInternal
import no.njoh.pulseengine.core.graphics.api.RenderTexture
import no.njoh.pulseengine.core.graphics.postprocessing.PostProcessingEffect
import no.njoh.pulseengine.core.shared.utils.Logger
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.stb.STBImageWrite

/**
 * TEMPORARY DEBUG TOOL — not part of the game.
 *
 * Game callbacks run on a thread with no GL context, so glReadPixels there aborts the JVM.
 * Post-processing effects run on the render thread with the context current, and receive the
 * surface's RenderTexture — which is the rendered scene. This reads that texture back and
 * writes a PNG so the output can actually be inspected.
 *
 * RenderTexture.handle is a Kotlin inline value class (TextureHandle) wrapping an Int, so the
 * raw GL name is pulled out by reflection and then validated by querying the bound texture's
 * dimensions before any pixels are read.
 */
class ScreenshotEffect(
    private val outputPath: String,
    private val captureAtFrame: Int = 180
) : PostProcessingEffect
{
    private var frame = 0
    private var done = false

    override val name = "screenshot"
    override val order = 10_000

    override fun init(engine: PulseEngineInternal) { }

    override fun process(engine: PulseEngineInternal, textures: List<RenderTexture>): List<RenderTexture>
    {
        if (done || frame++ < captureAtFrame || textures.isEmpty()) return textures
        done = true

        textures.forEachIndexed { index, tex ->
            runCatching { capture(tex, index) }
                .onFailure { Logger.error { "SCREENSHOT[$index] failed: $it" } }
        }
        return textures
    }

    private fun capture(tex: RenderTexture, index: Int)
    {
        val field = RenderTexture::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val raw = field.getInt(tex)

        GL11.glGetError() // clear any pre-existing error
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, raw)
        val bindErr = GL11.glGetError()

        val texW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
        val texH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)

        Logger.info {
            "SCREENSHOT[$index] name=${tex.name} declared=${tex.width}x${tex.height} " +
            "rawHandle=$raw boundTexture=${texW}x${texH} bindErr=$bindErr format=${tex.format}"
        }

        if (texW <= 0 || texH <= 0)
        {
            Logger.error { "SCREENSHOT[$index] handle $raw is not a readable GL_TEXTURE_2D — skipping" }
            return
        }

        val buffer = BufferUtils.createByteBuffer(texW * texH * 4)
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
        val readErr = GL11.glGetError()

        STBImageWrite.stbi_flip_vertically_on_write(true)
        val path = outputPath.replace(".png", "-$index.png")
        val ok = STBImageWrite.stbi_write_png(path, texW, texH, 4, buffer, texW * 4)
        Logger.info { "SCREENSHOT[$index] written=$ok readErr=$readErr path=$path" }
    }

    override fun getTexture(index: Int): RenderTexture = RenderTexture.BLANK

    override fun destroy() { }
}
