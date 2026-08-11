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
 * ## AN ANNULUS EMITTER WAS BUILT AND MEASURED, AND IT DOES NOT WORK. DO NOT REBUILD IT.
 *
 * The problem it was meant to solve is real and is still open: a pearl's own light saturates the
 * pearl's own body, so the iridescent material on it cannot be seen. Measured over the brightest
 * 0.05% of pixels in a pinned frame — which in every one of these frames are the pearl cores:
 *
 *      10 m Shallows   mean RGB (182, 141,  63)   chroma 0.667    0.0% at >= 250
 *      70 m Twilight   mean RGB (234, 210,  85)   chroma 0.638    0.0%
 *     140 m Abyss      mean RGB (246, 241, 187)   chroma 0.240   27.0%
 *
 * The obvious fix is to move the emitter's peak off the body: keep the outer silhouette (so the
 * cast is unchanged — external rays hit the same rim in the same place) and punch the middle out,
 * leaving a ring outside the 1.2 m body. Built, at two hole radii, captured at 140 m in the Abyss
 * against the shipped disc:
 *
 *     disc                          frame mean 9.675   chroma 0.240   27.0% at >= 250
 *     annulus, hole 0.62 half-widths   "     8.860     "     0.260   22.1%
 *     annulus, hole 0.86 half-widths   "     8.876     "     0.249   26.7%
 *
 * The second one has almost the entire emitter removed — a ring 0.14 half-widths thick, its inner
 * edge nearly three times the body's radius — and the pearl is STILL a blown-out white disc. The
 * effect is not small; it is absent.
 *
 * THE REASON IS IN `radiance_cascades.frag`, and it is a property of the whole approach rather
 * than of the radii. A probe inside the hole is SURROUNDED by the ring, so every ray it casts
 * hits emitting geometry; and we pass `radius = 0` on every `drawLight`, which is not "unbounded
 * radius" but "skip the distance term entirely" (`sampleScene`'s `if (radius > 0.0)` branch), so
 * what a ray brings back does not depend on how far it travelled. The irradiance at the centre of
 * an annulus is therefore the same as the irradiance inside a disc, exactly, at any hole radius.
 * The disc's own case is the same statement one step further along: the local SDF is signed and
 * `raymarch` steps by `max(0.0, sdf)`, so inside an emitter the first step is zero, `stepSize <=
 * MIN_STEP` fires immediately, and the probe samples the scene at its own texel.
 *
 * So no emitter SHAPE can spare the body at the centre of its own light. What would work is a
 * non-zero `radius` (which turns the distance term back on and would make the hole's width mean
 * something) — but that changes the falloff of every light in the game and would be a lighting
 * task of its own. The alternative that does not touch the lighting at all is to stop asking the
 * ALBEDO to survive an unbounded multiply: see `DiveRenderer.pearlAlbedoExposure`.
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

    /**
     * Fills the texture and queues it for upload. Called once, from `EnPustTil.onCreate`.
     *
     * ONE texture again. `f2f2eaa` added a second one here for the god rays; they are no longer
     * lights and no longer have an emitter — see the history section below, which `EnPustTil`'s
     * own comment at that call site has not been updated for (it is not this task's file).
     */
    fun load(engine: PulseEngine)
    {
        texture.loadFrom(buildPixels(), TEXELS, TEXELS)
        engine.asset.load(texture)
    }

    // --- HISTORY: the god rays used to emit from a second texture here --------------------------

    /**
     * # THE SHAFT EMITTER IS GONE, AND THESE TWO FINDINGS ARE WHY IT MUST NOT BE REBUILT BLIND
     *
     * `f2f2eaa` drew the god rays as real GI lights emitting from a second generated texture that
     * lived here. They are now albedo strips on `mainSurface` through `shaders/godrays.frag`;
     * `LightShafts` has the owner's brief for that move.
     *
     * THE JUSTIFICATION THIS COMMENT USED TO CARRY FOR THAT MOVE WAS WRONG, and is corrected here
     * rather than deleted because it is exactly the sort of thing that gets re-derived. It claimed
     * CLAUDE.md's "GI multiplies `mainSurface`" was false, on the evidence that `final.frag` ends
     * `base + light`. Settled on 2026-08-11 by decompiling `pulse-engine-0.13.0.jar`: **the
     * composite is a MULTIPLY**. `GlobalIlluminationSystem.onUpdate` — not `onCreate`, which is why
     * reading only the surface-setup path misses it — installs
     * `MultiplyEffect("gi_blend_effect", 15, "gi_light_final", minReflectance)` on
     * `gfx.getSurface(targetSurface)`, and `targetSurface` is initialised to `"main"`;
     * `MultiplyEffect` binds `tex0` from its own input (that surface, i.e. `mainSurface`) and `tex1`
     * from the `gi_light_final` surface. The `base + light` line is real — it is line 59, not 57 —
     * but it is the light map ASSEMBLING ITSELF: `GiFinal` is a post-processing effect on the
     * `gi_light_final` surface and its `baseTex` is that surface's own texture, not `mainSurface`.
     * Two true statements about two different stages. CLAUDE.md's platform constraints carry the
     * full citation. **Neither of the two findings below depends on which it is** — both are about
     * what a `drawLight` emitter looks like from the inside, upstream of any composite.
     *
     * So nothing below is live code any more. It is recorded because both findings were expensive,
     * neither is discoverable from the API, and anyone who ever makes a shaft a `drawLight` again
     * will otherwise re-derive them from a frame that merely looks slightly wrong.
     *
     * ## 1. A SHAFT'S FALLOFF LIVES IN RGB — THE EXACT INVERSE OF [alphaAt]'S RULE
     *
     * The rule above is: a pearl's light is only ever seen FROM OUTSIDE, radiance is sampled where
     * a ray HITS the emitter (i.e. on its rim), so a colour ramp that reaches zero at the rim
     * scales every escaping ray to zero. Shape must therefore live in alpha.
     *
     * A shaft is the one light in this game the player is meant to look at the INSIDE of, and the
     * inside of an emitter is not sampled on its rim: the local SDF is signed, `raymarch` steps by
     * `max(0.0, sdf)`, so a probe INSIDE an emitter takes a zero first step, trips
     * `stepSize <= MIN_STEP` immediately and samples the scene AT ITS OWN TEXEL
     * (`radiance_cascades.frag`). What a shaft looks like from the inside is therefore, texel for
     * texel, the texture's RGB times `intensity` — the same property that made the pearls' 3 m
     * emitter a visible flat shelf with a hard rim ([DiveLighting.PEARL_LIGHT_SIZE_METRES] has
     * that measurement) is, for a shaft, the entire mechanism the effect is built on.
     *
     * The profile also had to be a TRANSVERSE one times a LONGITUDINAL one and never a radial one.
     * A radial falloff — the obvious thing, and what it was built as first — makes the shaft an
     * ELLIPSE: brightest halfway down its own length and tapering at both ends. Captured, five of
     * those read as blue almond-shaped slabs hanging in the water rather than as light from above.
     * A shaft is strongest where it enters and weakest where it runs out, which is a property of
     * one axis and not of a radius.
     *
     * ## 2. THE ALPHA HAD TO BE CAPPED AT 0.75, AND BOTH BOUNDS ARE THE ENGINE'S
     *
     * ```
     * scene.frag:70      if (texColor.a < 0.5) discard;      // and jfa_seed.frag, which must agree
     * final.frag:32-46   bool isOccluder = scene.a > 0.8;
     *                    if (isOccluder) { if (isLightSource) light *= sourceIntensity; ... }
     * ```
     *
     * The occluder branch is right for every other light in this game: a pearl's emitter sits ON
     * the pearl and the torch's ON the diver, so the texels it covers really are a surface, and
     * lighting a surface by its own source's intensity is what makes a light source look lit.
     *
     * A shaft covers OPEN WATER. Left at full alpha, `light *= sourceIntensity` scaled everything
     * inside a shaft by that shaft's own depth-ramped intensity, which is below 1 — so a shaft
     * DARKENED whatever it contained, in proportion to how deep it reached. 0.75 is the window:
     * fully drawn and fully seeded into the SDF (both thresholds are 0.5), while `final.frag`
     * leaves the water inside the shaft alone.
     *
     * The related number was the silhouette contour, i.e. where the hard discard is placed as a
     * fraction of peak radiance. It shipped at 0.10 rather than the 0.02 it was built with,
     * because the silhouette is BOTH what is drawn and what blocks every other light: at 0.02 it
     * reached out into the flank where the shaft contributed nothing while still occluding at full
     * strength, and a same-build on/off pair had **30% of the frame DARKER with the shafts in**.
     * At 0.10 that was 1.0%. Everything between the two contours was pure cost.
     */

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
