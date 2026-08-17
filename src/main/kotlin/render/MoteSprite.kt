package render

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.api.TextureFilter
import no.njoh.pulseengine.core.graphics.api.TextureFormat
import no.njoh.pulseengine.core.graphics.api.TextureHandle
import no.njoh.pulseengine.core.graphics.api.TextureWrapping
import no.njoh.pulseengine.core.shared.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What a mote is DRAWN with: a round dab with no flat middle, generated at load time.
 *
 * ## WHY THIS IS NOT [LightEmitter], WHICH IT OTHERWISE LOOKS EXACTLY LIKE
 *
 * `Motes.render` used to draw `LightEmitter.emitter()`, and that texture is built for a job it is
 * very good at and this is not: it is the shape a light EMITS from. Its ramp
 * (`1 - smoothstep(0.6, 1.4, r)`) is chosen so the **alpha = 0.5 contour is the circle inscribed
 * in the quad**, because 0.5 is the only contour GI reads — `scene.frag`'s discard and
 * `jfa_seed.frag`'s seed both threshold there, and they must agree. Everything inside that
 * contour is emitting geometry, so the profile is deliberately **flat at 1.0 out to r = 0.6**.
 *
 * Drawn rather than emitted from, that flat middle is the whole defect. `texture.frag` cuts the
 * dab at texel alpha 0.4 (see [Motes.render]), which for that ramp is r = 1.052 — so a mote is
 * **alpha 1.0 across the inner 32% of its visible area** and then a short shoulder to the cut. A
 * disc with a flat maximum over a third of itself does not read as a glow; it reads as a plate,
 * which is exactly how the owner described one that landed on the diver: *"the black circle
 * overlapping the diver"*, *"I swam into it, I didn't appear on top"*.
 *
 * ## WHAT THIS DOES AND, JUST AS IMPORTANTLY, WHAT IT CANNOT
 *
 * It removes the flat middle: [alphaAt] peaks at 1.0 at the centre ONLY and falls continuously
 * from there, so every texel of the visible dab is on a slope.
 *
 * MEASURED, on two window grabs pinned to the same frame (`EPT_DEPTH=10` plus all three phase
 * pins, same 1600x928 window), over the twelve motes the frame contains — "flat core" being the
 * share of a dab's visible pixels sitting at or above 90% of its own peak:
 *
 * | | flat core |
 * |---|---|
 * | LightEmitter | 48% |
 * | MoteSprite | **14%** |
 *
 * A 70% reduction, in the same direction on every one of the twelve, and the peak amplitude is
 * unchanged to within 1 of 255 on all of them — so this costs no brightness. What it buys is that
 * a mote now shades from its centre outward instead of presenting a disc of flat colour.
 *
 * It does **not** soften the rim, and no texture can. `texture.frag` discards below a threshold on
 * the TEXTURE's alpha, so whatever shape is authored, the outermost surviving texel is drawn at
 * `threshold * drawColorAlpha` and the step into the water is that number. Measured across three
 * builds on pinned frames, the discard is real and it is not the lever: at 0.9 motes are 31%
 * narrower (mean visible width 14.5 px against 20.9 at the default), and at 1/255 they render as
 * rounded SQUARES, because `LightEmitter`'s alpha only reaches 0 at r = 1.4 while a quad's corners
 * are at r = 1.414 — drop the threshold and the corners stop being discarded. **The engine's 0.4
 * default is load-bearing for a round mote and must not be changed**; that was tried, measured,
 * and reverted. The rim step is a function of `Motes.MOTE_ALPHA` alone.
 *
 * @see LightEmitter for the emitter this deliberately diverges from, and for the alpha convention
 *   both textures share.
 */
object MoteSprite
{
    /**
     * Texels on a side. Matches [LightEmitter.TEXELS] for the same reason it does: a mote is at
     * most `Motes.MAX_SIZE_METRES` across, which is a couple of dozen pixels on a booth panel, so
     * this is heavily minified and the size only has to be enough that LINEAR sampling never shows
     * the texel grid on the circle.
     */
    const val TEXELS = 128

    /**
     * Where the alpha ramp ends, in units of HALF the texture's width — so 1.0 is the midpoint of
     * an edge and sqrt(2) is a corner. The ramp STARTS at 0, which is the point of this file.
     *
     * ## The two constraints this single number has to satisfy
     *
     * 1. **The visible edge must land on the inscribed circle**, r = 1.0, so that
     *    `Motes.sizeMetres` means the diameter that is actually drawn. `texture.frag` cuts at
     *    alpha 0.4, and `1 - smoothstep(0, OUTER, 1.0) = 0.4` requires `smoothstep = 0.6`, whose
     *    parameter is t = 0.5665 — hence `OUTER = 1 / 0.5665`. (`LightEmitter` puts its own cut at
     *    r = 1.052, marginally outside the inscribed circle, so a mote's apparent diameter shrinks
     *    by 5% here. That is below the size quantisation of a 14-px dab and is not compensated
     *    for; compensating would mean `sizeMetres` no longer naming the drawn size, which is the
     *    property this constant exists to buy.)
     *
     * 2. **The corners must still be discarded.** At r = 1.4142 this ramp gives alpha 0.103, which
     *    is below 0.4 and therefore thrown away — with a margin of 4x rather than
     *    `LightEmitter`'s 1.4-versus-1.414, i.e. none at all. `MoteSpriteTest` asserts both the
     *    contour and the corner, because a change to either turns the dab square.
     */
    const val ALPHA_RAMP_OUTER = 1.7653f

    /** The alpha `texture.frag` discards below — the engine's `TextureRenderer` default. */
    const val DISCARD_THRESHOLD = 0.4f

    /**
     * The dab's alpha at [nx], [ny], given in units of half the texture's width with the origin at
     * the centre.
     *
     * A function of the RADIUS ALONE, for the reason [LightEmitter.alphaAt] gives: anything that
     * depends on direction reads as a rotating rectangle rather than a round object. The
     * difference from that one is only the ramp's START — 0 here, 0.6 there — and that difference
     * is this whole file.
     */
    fun alphaAt(nx: Float, ny: Float): Float
    {
        val r = hypot(nx, ny)
        val t = (r / ALPHA_RAMP_OUTER).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)   // 1 - smoothstep(0, ALPHA_RAMP_OUTER, r)
    }

    /**
     * The texture's pixels: RGBA8, RGB flat white, alpha from [alphaAt] sampled at TEXEL CENTRES.
     *
     * Centres rather than corners for the reason [LightEmitter.buildPixels] records — sampling at
     * `i / TEXELS` puts the circle half a texel off centre and makes it faintly egg-shaped.
     *
     * RGB is flat white because the colour comes from `setDrawColor`: `Motes.render` tints every
     * dab with `MOTE_RED/GREEN/BLUE`, and `fragColor = vertexColor * textureColor`. Putting the
     * falloff in RGB instead of alpha would make the rim BLACK rather than transparent, which
     * under `src*a + dst*(1-a)` is a dark ring on the water — the inverse of what is wanted, and
     * the same trap `LightEmitter`'s doc spells out for a wide, soft emitter.
     */
    fun buildPixels(): ByteBuffer
    {
        val buffer = ByteBuffer.allocateDirect(TEXELS * TEXELS * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until TEXELS)
        {
            val ny = (y + 0.5f) / TEXELS * 2f - 1f
            for (x in 0 until TEXELS)
            {
                val nx = (x + 0.5f) / TEXELS * 2f - 1f
                buffer.put(255.toByte())
                buffer.put(255.toByte())
                buffer.put(255.toByte())
                buffer.put((alphaAt(nx, ny) * 255f).roundToInt().coerceIn(0, 255).toByte())
            }
        }
        buffer.flip()
        return buffer
    }

    /**
     * RGBA8 (linear) and `maxMipLevels = 1`, both for the reasons [LightEmitter.texture] states:
     * this carries a MASK rather than colour, and 1 is the value that means "one level, no mips" —
     * 0 allocates no storage at all (see `DiverSprite`'s class doc).
     */
    val texture = Texture(
        filePath = "",
        name = "mote_sprite",
        initWidth = TEXELS,
        initHeight = TEXELS,
        filter = TextureFilter.LINEAR,
        wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.RGBA8,
        maxMipLevels = 1
    )

    /**
     * Fills the texture and queues it for upload. Called once, from `EnPustTil.onCreate`, beside
     * [LightEmitter.load] — FILLED before it is queued, which is the order `loadFrom` +
     * `engine.asset.load` requires.
     */
    fun load(engine: PulseEngine)
    {
        texture.loadFrom(buildPixels(), TEXELS, TEXELS)
        engine.asset.load(texture)
    }

    /** How many consecutive frames of a missing texture stop being explicable by the upload. */
    private const val WARN_AFTER_FRAMES = 120

    private var framesWithoutTexture = 0
    private var warnedAboutTexture = false

    /**
     * The texture to draw a mote with, or `Texture.BLANK` while the upload is still in flight.
     *
     * A FUNCTION rather than a property, and for the same reason [LightEmitter.emitter] is one: it
     * counts consecutive misses and logs exactly one WARN once they stop being explicable by the
     * asynchronous upload (`engine.asset.load` populates the handle several frames later).
     *
     * The fallback is deliberately survivable rather than correct. `Texture.BLANK` has
     * `handle = TextureHandle.NONE`, which `texture.frag` reads as `texIndex == NO_TEXTURE` and
     * short-circuits to `textureColor = vec4(1)` — so a mote drawn during that window is a SQUARE
     * at full alpha, which is ugly for a few frames and cannot crash. That is the right trade for
     * a booth cabinet in front of a queue; the WARN is what makes it diagnosable afterwards.
     *
     * @see Motes.render
     */
    fun sprite(): Texture
    {
        if (texture.handle != TextureHandle.INVALID)
        {
            framesWithoutTexture = 0
            return texture
        }

        framesWithoutTexture++
        if (framesWithoutTexture >= WARN_AFTER_FRAMES && !warnedAboutTexture)
        {
            warnedAboutTexture = true
            Logger.warn {
                "The generated mote sprite was never uploaded — every mote is falling back to Texture.BLANK, " +
                "i.e. to a solid square. Check that MoteSprite.load is called from onCreate."
            }
        }
        return Texture.BLANK
    }
}
