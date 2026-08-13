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
import kotlin.math.sqrt

/**
 * The shape a pearl IS: a unit hemisphere, generated at load time, handed to GI's normal map so
 * the light knows the bead is round.
 *
 * ## The defect this exists to fix
 *
 * `DiveRenderer.drawPearlSurface` submitted nothing at all to `gi_normal_map`, so GI lit a pearl
 * as what it geometrically is on that surface — **a flat quad**. The whole disc faced the camera,
 * every fragment of it gathered light from the same hemisphere of directions, and the result had
 * no lit side and no dark side. Measured on a pinned capture at 140 m, where the torch is the
 * only external light in the frame, over the outer 40% of each pearl's radius (the emitter core
 * excluded), comparing the half facing the torch against the half facing away:
 *
 *     before this file    75 m  -2.7%    140 m  -0.3%      i.e. nothing, to within the noise
 *
 * Two pearls either side of the diver were indistinguishable, and a pearl the beam was ON looked
 * like a pearl the beam was nowhere near. That is most of what makes a bead fail to read as a
 * bead, and no amount of re-tuning the pearl's own emitter can produce it: a light's own emission
 * is radially symmetric about its centre, so it can change how BRIGHT a pearl is and never which
 * SIDE of it is bright.
 *
 * ## Why it is generated and not authored
 *
 * `render/LightEmitter.kt` is the precedent — the round emitter every light in the game shapes
 * itself from is built in code at load rather than shipped as a PNG — and the argument is
 * stronger here. `shaders/iridescence.frag` ALREADY computes this exact normal:
 *
 * ```glsl
 * vec2 p = vUv * 2.0 - 1.0;
 * float r = length(p);
 * float z = sqrt(max(1.0 - r * r, 0.0));
 * vec3 n = vec3(p, z);        // a unit hemisphere over the quad
 * ```
 *
 * — it just cannot SHARE it. A `BatchRenderer` draws to one surface; the iridescence pass is on
 * `main` and GI reads normals from `gi_normal_map`, which is fed by `NormalMapRenderer`, which
 * takes a TEXTURE and not a shader. So the same formula has to exist twice, and the only question
 * is whether the second copy is a PNG somebody drew (which drifts silently, mips across the disc
 * edge, and is exact at exactly one resolution) or [normalAt] below, which `PearlNormalMapTest`
 * asserts IS the shader's line, character for character, out of the shader source itself.
 *
 * ## The two conventions that have to line up, and why they do
 *
 * **The parameterisation.** `iridescence.vert:59` is `vUv = vertexPos` and the engine's
 * `normal_map.vert:50` is `texCoord = vertexPos`, and both then offset the quad by
 * `(vertexPos - origin) * size`. So the albedo pass and the normal pass walk the quad with the
 * SAME 0..1 coordinate, and a texel written at [normalAt]`(nx, ny)` lands on the fragment whose
 * `p` is `(nx, ny)`. Nothing needs flipping — provided the buffer is filled the way [buildPixels]
 * fills it, which is the row order `LightEmitter.buildPixels` established: buffer row 0 is v = 0
 * is `vertexPos.y = 0` is the quad's TOP on screen (world +y is depth, i.e. down).
 *
 * **The silhouette.** `normal_map.frag:57` discards at `alpha < 0.5`, and the fragment then keeps
 * the surface's background — `Color(0.5, 0.5, 1)`, i.e. the flat normal
 * (`GlobalIlluminationSystem.kt:133`). So alpha is coverage, and it carries the iridescence
 * shader's OWN edge ramp ([EDGE_SOFTNESS], copied from `iridescence.frag`) rather than a hard
 * cut: the 0.5 contour then sits at r = 1 - EDGE_SOFTNESS/2 = 0.97, i.e. within one screen pixel
 * of the drawn silhouette at any resolution this game runs at. A pearl's corners are left flat,
 * which is what they are — nothing is drawn there.
 *
 * ## What it does NOT change
 *
 * The pearl's own emitter. That is `DiveLighting.drawPearlLights`, on a different surface, and it
 * is deliberately untouched here — the lure's disguise is that the two emit identically
 * (`AnglerfishDisguiseTest`), and the lure gets this normal map by the same construction it gets
 * the material: both go through `DiveRenderer.drawPearlSurface`, once.
 *
 * ## What it bought, and what CAPS it — measure before re-tuning this
 *
 * Same pinned frames, same pearls, torch-facing half against the far half over the outer 40% of
 * each radius:
 *
 * ```
 *                                            75 m      140 m
 *   no normal map (1.2 m emitter, 3 m reach)  -2.7%     -0.3%
 *   no normal map (0.5 m emitter, 0.25 m)     +4.2%     +8.6%
 *   with this file                            +6.5%    +10.6%
 * ```
 *
 * A pearl now has a lit side and a dark side, and the lit side is the one facing the torch. But
 * [litWeight] says the far side should go to ZERO, and it plainly does not — so the ceiling is
 * worth naming, because it is not this file and re-tuning this file cannot lift it:
 *
 *  - **`final.frag:49` adds `ambientLight` AFTER the normal-weighted gather, unconditionally.**
 *    Below the Kelp that ambient is `DiveLighting`'s flat floor (0.16, 0.30, 0.44) — see
 *    `AMBIENT_FLOOR_RED`, which exists so the diver is not algebraically black at 140 m — and at
 *    140 m it is most of the light a pearl receives. A directionless term that large bounds the
 *    achievable asymmetry at roughly what is measured above.
 *  - Bloom is NOT the cause, which was worth ruling out: the same frame captured with
 *    `BloomEffect.intensity = 0` measures +11.0% against +10.6%, and the pearls' peak is
 *    unchanged to 0.05/255 — they sit under the 1.4 threshold entirely.
 *
 * The other half of the same interaction is the pearl's own emitter, which sits at its CENTRE: an
 * outward rim normal faces away from it, so a rim probe is blind to its own light and is lit only
 * from outside. That is why this was measured AFTER the emitter was shrunk rather than with it —
 * the two changes push the same pixels in opposite directions. [DiveLighting.PEARL_REACH_METRES]
 * and [DiveLighting.PEARL_LIGHT_SIZE_METRES] carry that side of the arithmetic.
 */
object PearlNormalMap
{
    /**
     * The generated texture's side, in TEXELS. 128, for [LightEmitter.TEXELS]'s reason: it is the
     * smallest bucket `TextureBank.DEFAULT_CAPACITIES` declares, so this shares that array rather
     * than rounding a new one up, and a pearl is 44 screen pixels across at the dev framebuffer —
     * this is already three times oversampled.
     */
    const val TEXELS = 128

    /**
     * How far the coverage ramp is softened, as a fraction of the radius. **Copied from
     * `iridescence.frag`'s `EDGE_SOFTNESS`, and `PearlNormalMapTest` reads the shader's own value
     * out of the file and fails the build if the two part company.** The albedo's silhouette and
     * the lit silhouette are the same circle or the pearl has a rim that is drawn and not lit.
     */
    const val EDGE_SOFTNESS = 0.06f

    /**
     * `radiance_cascades.frag:289-290`, transcribed: the fraction of the radiance arriving along
     * ([rayDirX], [rayDirY]) that a fragment at ([nx], [ny]) on the quad keeps.
     *
     * ```glsl
     * vec3 lightDir = normalize(vec3(rayDir, normalMapScale));
     * deltaRadiance.rgb *= clamp(dot(normal, lightDir) * 4, 0.0, 1.0);
     * ```
     *
     * The uniform is `1 / GlobalIlluminationSystem.normalMapScale` and not the property itself —
     * see [DiveLighting.GI_LIGHT_ELEVATION], which is the trap in this expression. At the engine's
     * default that makes the light 0.2425 out of plane, i.e. very nearly sideways, which is why a
     * TRUE hemisphere needs no exaggeration to produce a terminator: the far side of the bead goes
     * to zero from about `r = 0.24` outward, i.e. across 94% of the disc's area. An earlier draft
     * of this file stretched the normals by 4 to compensate for a reciprocal that had already been
     * applied; the numbers below are what settled it.
     *
     * Pure and engine-free so the lit-side property can be asserted without a GL context — the
     * pattern `CLAUDE.md` calls "the relationship between the numbers". [rayDirX], [rayDirY] must
     * be a unit vector; it is the direction FROM the fragment TOWARD where the light is.
     */
    fun litWeight(nx: Float, ny: Float, rayDirX: Float, rayDirY: Float): Float
    {
        val r = hypot(nx, ny)
        val z = sqrt((1f - r * r).coerceAtLeast(0f))
        val len = sqrt(nx * nx + ny * ny + z * z)
        if (len <= 0f) return 0f
        val m = DiveLighting.GI_LIGHT_ELEVATION
        val lightLen = sqrt(1f + m * m)
        val dot = (nx * rayDirX + ny * rayDirY + z * m) / (len * lightLen)
        return (dot * 4f).coerceIn(0f, 1f)
    }

    /**
     * The hemisphere's normal at a point ([nx], [ny]) on the quad, in units where the quad spans
     * -1..1 — i.e. exactly `iridescence.frag`'s `p`. Returns the three components in a caller-
     * supplied order via [out] so this stays allocation-free; it is called once per texel at load
     * and never per frame, but the convention in this codebase is the convention.
     *
     * `z` is `sqrt(1 - r^2)` clamped at 0, so the vector is already unit length inside the disc
     * and flattens to purely in-plane exactly at the rim. Outside the disc it degenerates, which
     * is why [buildPixels] fades the ALPHA out there and the shader discards it.
     */
    /**
     * ## THE GREEN CHANNEL IS NEGATED, AND THAT IS NOT A SIGN ERROR
     *
     * This map is the ONE normal map in the game that is generated rather than baked from art, so
     * it is the one whose convention we chose — and we chose it wrong the first time. It shipped
     * agreeing with `iridescence.frag`'s `n = vec3(p, z)`, which is the natural reading and is
     * correct for THAT shader, whose consumer is its own interference maths in its own uv space.
     *
     * GI's consumer wants the opposite. Every normal map baked from the artist's Blender exports —
     * the diver's sheet and the rock's three — carries the OpenGL convention, green HIGH where a
     * surface faces up-screen: measured on the committed files, `rock-top-normal.png` reads 188 at
     * the top against 98 at the bottom, and the diver's crown 159 against his feet 122. Nothing in
     * the pipeline flips it (`tools/spritesheet/validate.py`'s `check_normal_encoding` asserts unit
     * length and nothing about direction) and nothing in the engine flips it either
     * (`normal_map.frag` decodes, rotates, normalises, re-encodes). So the art's convention IS the
     * engine's, and this map was the odd one out.
     *
     * ## ESTABLISHED BY MEASUREMENT, AFTER THE READING SAID THE OPPOSITE
     *
     * Reading `radiance_cascades.frag:251`'s `rayDir = vec2(cos(a), -sin(a))` as "+y is down"
     * predicts the reverse of all of the above, and that prediction was WRONG. The probe that
     * settled it: capture the pinned attract frame at `EPT_DEPTH=140` twice, once with this
     * negation and once without, and measure each pearl's top-half-minus-bottom-half luminance
     * against whether it sits above or below the diver's torch. A correctly oriented hemisphere is
     * lit from the side the light is on, so pearls BELOW the torch should be brighter on top and
     * pearls ABOVE it brighter underneath. Measured over the same 15 discs in both builds, with the
     * asymmetry normalised by each pearl's own brightness so an overall level change cannot fake it
     * (the negated build is in fact 3.5% DIMMER overall):
     *
     *              above the torch   below the torch   correlation with height
     *     as first shipped   -0.043        +0.033              +0.322
     *     NEGATED (this)     -0.110        +0.083              +0.416
     *
     * Both signs are right; the negated one is **2.5x stronger**. The first version was being
     * partially cancelled by the flat ambient and by its own emitter, which is exactly why a sign
     * error here is invisible in a still frame and why it had to be measured rather than argued.
     *
     * `PearlNormalMapTest` therefore asserts agreement with the shader UP TO THIS NEGATION, and
     * says why — the two normals serve different consumers with different conventions, so an
     * assertion that they are identical was encoding an assumption rather than a requirement.
     */
    fun normalAt(nx: Float, ny: Float, out: FloatArray)
    {
        val r = hypot(nx, ny)
        val z = sqrt((1f - r * r).coerceAtLeast(0f))
        out[0] = nx
        out[1] = -ny
        out[2] = z
    }

    /**
     * The disc's coverage at radius [r] (1.0 at the quad's edge midpoints): the iridescence
     * shader's `edge` term, so the two silhouettes are one circle. See [EDGE_SOFTNESS].
     */
    fun coverageAt(r: Float): Float
    {
        val t = ((r - (1f - EDGE_SOFTNESS)) / EDGE_SOFTNESS).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)   // 1 - smoothstep(1 - EDGE_SOFTNESS, 1, r)
    }

    /**
     * The texture's pixels: RGBA8, RGB the hemisphere normal encoded as `(n + 1) / 2` — the
     * convention `normal_map.frag:61` decodes with — and alpha the disc's coverage.
     *
     * Sampled at TEXEL CENTRES, for `LightEmitter.buildPixels`'s reason: the sampler reads
     * centres, and sampling at `i / TEXELS` would put the hemisphere half a texel off its own
     * quad and tilt every normal by a fixed bias.
     *
     * Direct and native-order because `TextureArray.upload` hands it straight to
     * `glTexSubImage3D`, which cannot take a heap buffer. One allocation, at load, once.
     */
    fun buildPixels(): ByteBuffer
    {
        val buffer = ByteBuffer.allocateDirect(TEXELS * TEXELS * 4).order(ByteOrder.nativeOrder())
        val n = FloatArray(3)
        for (y in 0 until TEXELS)
        {
            val ny = (y + 0.5f) / TEXELS * 2f - 1f
            for (x in 0 until TEXELS)
            {
                val nx = (x + 0.5f) / TEXELS * 2f - 1f
                normalAt(nx, ny, n)
                buffer.put(encode(n[0]))
                buffer.put(encode(n[1]))
                buffer.put(encode(n[2]))
                buffer.put(encode01(coverageAt(hypot(nx, ny))))
            }
        }
        buffer.flip()
        return buffer
    }

    /** A normal component in -1..1 as the byte `normal_map.frag`'s `* 2 - 1` will decode. */
    internal fun encode(component: Float): Byte = encode01(component * 0.5f + 0.5f)

    private fun encode01(value: Float): Byte = (value * 255f).roundToInt().coerceIn(0, 255).toByte()

    /**
     * RGBA8, and here it is NOT documentation the way `LightEmitter`'s is — it is load-bearing.
     * An sRGB format would push every stored component through the ~2.4 decode curve and hand the
     * shader a normal that is not the one written: 0.5 (a flat x) would come back as 0.21, i.e.
     * -0.58, and every pearl would be lit from the same corner regardless of where the torch is.
     *
     * `maxMipLevels = 1` — never 0, which allocates no storage at all (see `DiverSprite`'s class
     * doc), and never more than 1 either: mip generation averages ACROSS the disc's edge, mixing
     * the rim's in-plane normals with the flat background outside it, which is the same defect
     * that keeps the hyphen in `diver-normal.png`.
     *
     * The NAME may contain `_normal` safely. The engine's auto-loader rule that keys on that
     * substring (`Extensions.kt:446`) lives in `pathToAsset`, i.e. it applies to files discovered
     * by `loadAll` — this texture is constructed here with a blank `filePath` and queued directly,
     * exactly as `LightEmitter`'s is, so nothing rewrites its format or its mip count behind us.
     */
    val texture = Texture(
        filePath = "",
        name = "pearl_normal_map",
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

    /** Ten seconds at 60 fps, the same budget as `LightEmitter.WARN_AFTER_FRAMES`. */
    private const val WARN_AFTER_FRAMES = 600

    private var framesWithoutTexture = 0
    private var warnedAboutTexture = false

    /**
     * The texture to hand `drawNormalMap`, or null while the upload is still in flight.
     *
     * NULL rather than a fallback, which is the opposite of [LightEmitter.emitter]'s choice and is
     * right for the opposite reason. `normal_map.frag:43` starts from `vec4(0, 0, 1, 1)` and only
     * samples when `texIndex >= 0`, so a null texture is drawn as a FLAT normal — which is exactly
     * the behaviour this file replaces, i.e. the pearl simply looks the way it did before for the
     * few frames the upload takes. Passing an un-uploaded handle instead would index a texture
     * slice that does not exist.
     *
     * A FUNCTION, not a property, because it counts consecutive misses and logs exactly one WARN
     * once they stop being explicable by the asynchronous upload — `DiverSprite.sheetsReady`'s
     * arrangement, for `DiverSprite.sheetsReady`'s reason.
     */
    fun normals(): Texture?
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
                "The generated pearl normal map was never uploaded — every pearl is being lit as a flat " +
                "quad, with no lit side and no dark side. Check that PearlNormalMap.load is called from onCreate."
            }
        }
        return null
    }
}
