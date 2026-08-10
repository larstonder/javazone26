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
 * The shape every light in the game emits from: a round emitter, generated at load time.
 *
 * ## The bug this exists to fix
 *
 * All three of `DiveLighting`'s `GiSceneRenderer.drawLight` calls used to pass
 * `texture = Texture.BLANK`, and `drawLight` takes the emitter's SHAPE from the texture. BLANK
 * has `handle = TextureHandle.NONE`, which `scene.frag` reads as `texIndex == NO_TEXTURE` and
 * short-circuits to `texColor = vec4(1)` — the whole QUAD emits, corners included. So every light
 * in the game was a square:
 *
 *  - the diver's torch drew a hard-edged rectangle around him that ROTATED with his heading,
 *    because the quad is rotated by `angle` (reported twice; see `t9c_rot_right-view.png` and
 *    `b_43_shallow-view.png`);
 *  - every pearl and the anglerfish lure carried a square halo "starting outside of their bodies".
 *
 * The engine's own reference (`Torch.onRenderLightSource`) passes a flame sprite for exactly this
 * reason. We have no art, so the emitter is generated — which also fixes pearls, the anglerfish
 * and the diver at once, and an authored glow PNG can replace it later by matching the alpha
 * convention below.
 *
 * ## WHERE THE FALLOFF HAS TO LIVE, WHICH IS NOT WHERE IT LOOKS LIKE IT SHOULD
 *
 * The obvious reading of "radial falloff texture" is a bright centre fading to black in RGB. That
 * would make the lights very nearly invisible, and the reason is worth stating in full because
 * nothing about the API hints at it. Read off the shaders, not assumed:
 *
 * ```
 * scene.frag:70      if (texColor.a < 0.5) discard;          // shape: a HARD threshold
 * scene.frag:74      sceneColor = vertexColor * texColor;    // what a ray that hits it sees
 * jfa_seed.frag:12   alpha > 0.5 ? seed : -1                 // the SDF is seeded from the same 0.5
 * radiance_cascades.frag:104-129  sampleScene(...) at the RAY'S HIT POSITION
 * ```
 *
 * Radiance is sampled where the ray HITS the emitter — i.e. on its silhouette, its rim — never at
 * its centre. An RGB falloff that reaches zero at the rim therefore scales every escaping ray by
 * zero. The emitter's brightness is set by `intensity` and by the draw colour; the TEXTURE's only
 * job is to say which texels are part of the emitter, and it says that through ALPHA, thresholded
 * at 0.5 in two places that must agree (the fragment discard and the JFA seed that builds the SDF).
 *
 * So: **RGB is flat white and the falloff is entirely in alpha.** [ALPHA_RAMP_INNER] /
 * [ALPHA_RAMP_OUTER] are chosen so the alpha = 0.5 contour — the only contour the engine actually
 * uses — is exactly the circle inscribed in the quad. The ramp either side of it is not decoration:
 * it is what makes the circle come out smooth under the LINEAR sampling `GiSceneRenderer` forces
 * (`onRenderBatch`'s `setUniformSamplerArrays(..., filter = LINEAR)`), and it is the convention an
 * authored glow PNG would have to match.
 *
 * `GI_LOCAL_SCENE` is created with `blendFunction = NONE`, so nothing blends the alpha gradient
 * into the stored RGB behind our backs — the two channels really are independent here.
 *
 * ## What it costs in light: measured at 2.2%, and NOT compensated for
 *
 * A disc is a smaller target than the square it is inscribed in. By Cauchy's formula the mean
 * width of a convex 2D shape is its perimeter / pi, so a square of side d has mean width 4d/pi and
 * the inscribed disc has mean width d: the disc intercepts pi/4 = 78.5% as many rays, and the
 * arithmetic says every light wants brightening by 4/pi to keep this a change of shape only.
 *
 * BUILT THAT WAY, IT WAS WRONG BY A FACTOR OF FIFTY. Captured at 16:9, the +27% nominal
 * compensation took the frame mean from 6.382 to 11.632 — plus 82% — because `DiveLighting.setup`
 * puts an ACES tone mapper and a bloom with `threshold = 1.4` on mainSurface, and neither is
 * linear in radiance anywhere near a light source. The same frame with no compensation reads
 * 6.239, i.e. 2.2% below the square-emitter baseline, against a same-build control pair that
 * agreed to 0.0007/255. So the shape change costs about a fiftieth of what the geometry predicts,
 * and nothing is applied. The number that mattered was never derivable on paper.
 *
 * ## Loading: the same asynchronous path `DiverSprite` established, minus the file
 *
 * `Texture.load()` returns immediately when `filePath` is blank (`Texture.kt:45`), so a texture
 * built with [loadFrom][Texture.loadFrom] survives the asset manager's load pass with its pixel
 * buffer intact and is then handed to `gfx.uploadTexture` by `PulseEngineImpl`'s
 * `setOnAssetLoaded` hook (`PulseEngineImpl.kt:108-111`) like any other. That upload is
 * ASYNCHRONOUS, and an un-uploaded texture is not merely blank: `TextureHandle.INVALID` is
 * texture index 65535, while the shader's NO_TEXTURE sentinel is 65534, so the shader would sample
 * a nonexistent slice rather than take the untextured branch — and `texColor.a < 0.5` would then
 * discard the light ENTIRELY. [emitter] therefore gates on the handle and falls back to
 * `Texture.BLANK`, which is the old square: a few square frames at boot, never a dark game.
 */
object LightEmitter
{
    /**
     * The generated texture's side, in TEXELS. Not a screen size and never a metre — the quad's
     * world size is `DiveLighting`'s business; this only decides how finely the circle is
     * resolved. 128 is the smallest bucket `TextureBank.DEFAULT_CAPACITIES` declares, so it costs
     * one 128px RGBA8 texture array (64 KB) and no bucket is rounded up.
     */
    const val TEXELS = 128

    /**
     * Where the alpha ramp starts and ends, in units of HALF the texture's width — so 1.0 is the
     * midpoint of an edge and sqrt(2) is a corner.
     *
     * They straddle 1.0 symmetrically on purpose. `smoothstep` is 0.5 at the midpoint of its own
     * range, so alpha crosses 0.5 at r = 1.0 exactly, and the emitter the engine actually builds
     * (the alpha > 0.5 region: `scene.frag`'s discard and `jfa_seed.frag`'s seed, which must
     * agree) is precisely the circle inscribed in the quad. Anything narrower would shrink the
     * light relative to the size `DiveLighting` asks for and quietly break the
     * `upscaleSmallSources` and cull-margin reasoning that is written in terms of that size.
     *
     * The width of the ramp (0.8 of a half-width, i.e. 51 texels here) is a look decision with
     * nothing measured behind it: wide enough that LINEAR sampling never shows the texel grid on
     * the circle at any zoom, narrow enough that the corners still reach 0 well before r = sqrt(2)
     * and are discarded rather than sampled.
     */
    const val ALPHA_RAMP_INNER = 0.6f
    const val ALPHA_RAMP_OUTER = 1.4f

    /**
     * The emitter's alpha at a point [nx], [ny] given in units of half the texture's width, with
     * the origin at the centre — so the edge midpoints are at radius 1 and the corners at sqrt(2).
     *
     * Pure, and a function of the RADIUS ALONE. That is the whole fix: the old emitter's extent
     * depended on direction (a square is 1.0 wide across its faces and 1.41 across its diagonals),
     * which is why the diver's light read as a rotating rectangle rather than as a torch.
     */
    fun alphaAt(nx: Float, ny: Float): Float
    {
        val r = hypot(nx, ny)
        val t = ((r - ALPHA_RAMP_INNER) / (ALPHA_RAMP_OUTER - ALPHA_RAMP_INNER)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)   // 1 - smoothstep(inner, outer, r)
    }

    /**
     * The texture's pixels: RGBA8, RGB flat white, alpha from [alphaAt] sampled at TEXEL CENTRES.
     *
     * Centres rather than corners because the sampler reads centres: sampling at `i / TEXELS`
     * would put the circle half a texel off-centre and make it very slightly egg-shaped, which is
     * invisible in a still and exactly the kind of thing this file exists to stop.
     *
     * Allocated direct and in native order because `TextureArray.upload` hands it straight to
     * `glTexSubImage3D`, which cannot take a heap buffer. One allocation, at load, once.
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
     * RGBA8 (linear), not SRGBA8. The colour is a constant 1.0, where the two encodings agree, so
     * the choice is documentation rather than maths: this texture carries a MASK, not colour, and
     * an sRGB format would invite a future editor to author a grey into it and be surprised.
     *
     * `maxMipLevels = 1` — never 0, which allocates no storage at all (see `DiverSprite`'s class
     * doc). One level is also what we want: the emitter is a smooth radial ramp with no detail a
     * mip chain could preserve, and `GiSceneRenderer` samples it with an explicit LINEAR filter.
     */
    val texture = Texture(
        filePath = "",
        name = "gi_light_emitter",
        initWidth = TEXELS,
        initHeight = TEXELS,
        filter = TextureFilter.LINEAR,
        wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.RGBA8,
        maxMipLevels = 1
    )

    /** Fills the texture and queues it for upload. Called once, from `EnPustTil.onCreate`. */
    fun load(engine: PulseEngine)
    {
        texture.loadFrom(buildPixels(), TEXELS, TEXELS)
        engine.asset.load(texture)
    }

    /**
     * Ten seconds at 60 fps before the missing emitter is said out loud — the same budget, and the
     * same reasoning, as `DiverSprite.WARN_AFTER_FRAMES`.
     */
    private const val WARN_AFTER_FRAMES = 600

    private var framesWithoutTexture = 0
    private var warnedAboutTexture = false

    /**
     * The texture to hand `drawLight`, or `Texture.BLANK` while the upload is still in flight.
     *
     * A FUNCTION, not a property, because it has a side effect: it counts consecutive misses and
     * logs exactly one WARN once they stop being explicable by the asynchronous upload — the same
     * arrangement, for the same reason, as `DiverSprite.sheetsReady`. The failure this guards is
     * silent in both directions: falling back gives square lights (the old bug, visible but not
     * fatal), and NOT falling back would hand the shader texture index 65535, which is not its
     * NO_TEXTURE sentinel, so every light would be discarded and the game would simply be black.
     */
    fun emitter(): Texture
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
                "The generated light emitter texture was never uploaded — every light is falling back to " +
                "Texture.BLANK, i.e. to the square emitter. Check that LightEmitter.load is called from onCreate."
            }
        }
        return Texture.BLANK
    }
}
