package render

import dive.Tuning
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.surface.Surface
import kotlin.math.ceil
import kotlin.math.min

/**
 * THE SUNSET, AND THE ONE DECISION THAT DECIDES WHETHER IT SURVIVES AT ALL: which surface it is
 * drawn on.
 *
 * ## Not `mainSurface`, and this is not a preference
 *
 * `GlobalIlluminationSystem` relights the world surface by multiplying it by the computed light
 * map (`texture_multiply_blend.frag`, wired up in [DiveLighting.setup]). That is what makes the
 * Abyss dark and it is indiscriminate: a bright sky drawn as albedo on `main` comes out as
 * `sky * whatever light happens to reach the top of the frame`, which near the surface is the
 * god rays' own pattern and 13 m outside the play column is about a tenth of that. Measured with
 * a flat 0.5 grey world at 12 m and 16:9, mean blue out of 255:
 *
 *     column centre                  27.42
 *     just inside the boundary       20.93
 *     0-5 m outside the boundary     10.04
 *     5-13 m outside the boundary     2.11
 *
 * A sunset sky multiplied by that is not a dimmer sunset, it is a sunset with the light map
 * printed across it — brighter over the middle of the column, guttering out over the cliffs, and
 * flickering as the diver's torch swings. This is the same trap that reverted the rim light
 * (`d6faaa5`) and blew out the pearls (`e45fdbe`), arriving from the third direction.
 *
 * ## Not the `"hud"` surface either
 *
 * `"hud"` escapes the multiply, which is exactly why the HUD lives there — but it is composited
 * ON TOP of everything and it is in screen PIXELS. On top is fatal: the cliff tops rise out of
 * the water into the sky, and a sky drawn over them paints them out. Screen pixels is merely
 * expensive: the sky's only interesting edge is the waterline, which is a WORLD depth, so every
 * frame would have to transform it by hand — the one thing `CLAUDE.md` names as the remaining
 * drift risk in this codebase.
 *
 * ## So: a surface of its own, BEHIND the world, on the world's own camera
 *
 * `EnPustTil.onCreate` creates `"sky"` with `camera = engine.gfx.mainCamera` — the same object
 * `mainSurface` and GI's local scene already share, so the sky is in world METRES and goes
 * through the identical matrix, built once per frame in `gfx.initFrame` before any game code
 * runs. It cannot drift from the water for the same structural reason a light cannot drift from
 * the square it lights.
 *
 * Its `zOrder` is `mainSurface`'s plus [Z_ORDER_OFFSET]. `GraphicsImpl` composites surfaces
 * sorted by `-zOrder` ascending (verified in the decompiled comparator), so a LARGER zOrder is
 * drawn EARLIER, i.e. further back — the opposite convention to the HUD's -90. Behind is what
 * lets the cliff tops, the rock and everything else on `main` occlude the sky by simply being
 * opaque, with no clipping and no draw-order coupling between the two files.
 *
 * For that to work `mainSurface`'s background is [no.njoh.pulseengine.core.shared.primitives
 * .Color.BLANK] and its water starts at [WaterSurface.QUAD_BOTTOM_DEPTH]. The transparency
 * survives the whole post chain: the GI multiply writes `vec4(c0.rgb * c1.rgb, c0.a)`, bloom
 * writes `vec4(src.rgb + bloom, src.a)` and colour grading only ever touches `.rgb` — and the
 * backbuffer composites with `glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)`, i.e. STRAIGHT
 * alpha, so a transparent world pixel contributes nothing at all rather than a premultiplied
 * haze. All four read off the engine jar rather than assumed.
 *
 * ## What the sky is made of
 *
 * Horizontal strips, [Surface.fillRect], sampled from [colourAt] — deliberately the same
 * technique as [DiveRenderer.drawZoneBands] rather than a second custom shader. The zone bands
 * are the proof it reads as a continuous gradient, the pitch is a world metre count so it is
 * resolution-independent, and there is nothing about a vertical ramp that a fragment shader would
 * do better. The one edge that genuinely needs a shader — the waterline — is the water's, not the
 * sky's, and it has one.
 *
 * THE GRADIENT IS ANCHORED TO THE WATER, NOT TO THE FRAME. [colourAt] takes a height above
 * [Tuning.SURFACE_DEPTH] in metres, so the horizon stays on the horizon and the sky slides up out
 * of frame as the diver descends, which is what a sky does. Anchoring it to the visible rect
 * instead would make the whole sunset scroll with the camera.
 */
object Sky
{
    /**
     * How far above [mainSurface]'s own zOrder the sky sits, and therefore how far BEHIND it is
     * composited.
     *
     * 10 rather than 1 because `GlobalIlluminationSystem` takes `mainZOrder + 1 .. + 9` for its
     * nine internal surfaces (read off its bytecode: `gi_light_final` is +1, `gi_local_scene` is
     * +9). Those are all created with `isVisible = false` so they are skipped in the composite
     * pass and could not actually collide — but sharing a zOrder with one of them would leave the
     * sky's position in the sort decided by insertion order, which is not a thing to depend on.
     */
    const val Z_ORDER_OFFSET = 10

    /** The surface's name, in one place, because two files have to agree on it. */
    const val SURFACE_NAME = "sky"

    /**
     * How much sky the gradient is authored over, in metres above the waterline.
     *
     * The camera cannot show more than `VISIBLE_DEPTH_METRES * DIVER_SCREEN_FRACTION` = 24 m of
     * sky, because that is where the top of the frame sits when the diver is at the surface and
     * he cannot go higher. 26 m is that plus slack, so the zenith colour is reached just off the
     * top of the frame rather than exactly at it — a gradient that lands its last stop precisely
     * on the frame edge reads as a band.
     *
     * Derived from [Framing] rather than typed, so that changing how much water is on screen
     * cannot silently leave the sky authored for a frame that no longer exists.
     */
    val SPAN_METRES = Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_SCREEN_FRACTION + 2f

    /**
     * Strip pitch, in metres. Finer than [DiveRenderer.BAND_STRIP_METRES]'s 0.5 because this
     * gradient is far brighter and covers far more colour: `setDrawColor` quantises each channel
     * to 8 bits, and at 0.1 m a channel moving 200 levels over [SPAN_METRES] changes about every
     * third strip, so the quantisation is what limits the smoothness rather than the pitch. 260
     * strips at most, against the bands' 120.
     */
    const val STRIP_METRES = 0.1f

    /** The same bound, and the same reason, as [DiveRenderer.MAX_BAND_STRIPS]. */
    internal val MAX_STRIPS = (4f * (SPAN_METRES + WaterSurface.QUAD_BOTTOM_DEPTH) / STRIP_METRES).toInt()

    /**
     * The gradient's stops, from the horizon upward, at the heights in [stopHeights].
     *
     * A LOW SUN, which is what decides the shape: the warm end is squeezed into the first few
     * metres above the water and the cold end occupies most of the sky. Evenly spaced stops give
     * a uniform orange-to-purple wash that reads as a colour ramp rather than as evening.
     *
     * Kept as parallel `FloatArray`s and blended with primitive floats in and floats out, for the
     * same reason [DiveRenderer]'s zone tables are: this is called a few hundred times a frame and
     * the render path allocates nothing.
     */
    private val stopHeights = floatArrayOf(0f, 2.5f, 7f, 15f, 26f)
    private val stopRed = floatArrayOf(1.00f, 0.98f, 0.78f, 0.34f, 0.13f)
    private val stopGreen = floatArrayOf(0.62f, 0.44f, 0.28f, 0.19f, 0.12f)
    private val stopBlue = floatArrayOf(0.36f, 0.30f, 0.38f, 0.44f, 0.34f)

    /**
     * The sky's colour at [heightMetres] above [Tuning.SURFACE_DEPTH], as an sRGB draw colour.
     *
     * Piecewise linear between the stops, clamped at both ends. Linear rather than smoothstepped
     * ([DepthBlend]'s easing) on purpose: [DepthBlend] holds a zone's colour near-flat through the
     * middle of its band so each zone reads as its own colour, which is right for five named
     * depth zones and wrong for a sky, where every flat stretch is a visible band.
     *
     * Pure and exposed so `SkyTest` can sweep it — the gradient's monotonicity and its clamping
     * are the parts a wrong number breaks silently, and neither is visible in a still frame taken
     * at one camera depth.
     */
    internal fun redAt(heightMetres: Float) = blend(heightMetres, stopRed)
    internal fun greenAt(heightMetres: Float) = blend(heightMetres, stopGreen)
    internal fun blueAt(heightMetres: Float) = blend(heightMetres, stopBlue)

    internal fun blend(heightMetres: Float, stops: FloatArray): Float
    {
        if (!(heightMetres > stopHeights[0])) return stops[0]      // this way round, so NaN clamps low
        for (i in 1 until stopHeights.size)
        {
            if (heightMetres >= stopHeights[i]) continue
            val span = stopHeights[i] - stopHeights[i - 1]
            val t = (heightMetres - stopHeights[i - 1]) / span
            return stops[i - 1] + (stops[i] - stops[i - 1]) * t
        }
        return stops[stops.size - 1]
    }

    /** Height above the waterline, in metres, for a world depth. Zero at and below the surface. */
    internal fun heightAt(depth: Float) = Tuning.SURFACE_DEPTH - depth

    /**
     * How many strips cover `[worldTop, bottom]`. Same shape, and the same defensive bounds, as
     * [DiveRenderer.stripCount] — the visible rect is computed by the engine before any of our
     * code runs and can be empty, inverted or enormous on the first frame or mid-resize.
     */
    internal fun stripCount(worldTop: Float, bottom: Float): Int
    {
        val span = bottom - worldTop
        if (!(span > 0f)) return 0
        return ceil(span / STRIP_METRES).toInt().coerceAtMost(MAX_STRIPS)
    }

    /**
     * How deep the sky is painted: past the lowest trough the wave table can produce, and no
     * further. See [render].
     */
    val BOTTOM_DEPTH = Tuning.SURFACE_DEPTH + WaterSurface.AMPLITUDE_METRES + WaterSurface.EDGE_MARGIN_METRES

    /**
     * Draw the sky onto its own surface.
     *
     * It stops at [BOTTOM_DEPTH] — past the lowest trough by [WaterSurface.EDGE_MARGIN_METRES] and
     * nothing more. The sky has to exist behind the pixels [WaterRenderer]'s alpha ramp runs
     * through, because that is what the ramp ramps AGAINST; those pixels are the ones the wavy
     * boundary itself crosses, so they live within [WaterSurface.AMPLITUDE_METRES] of the
     * waterline. Everywhere below that, `water.frag` writes alpha 1 — "this quad is either water
     * or it is not, and the only place it is partly water is the pixel the boundary runs through"
     * — so `main` is opaque and a sky behind it cannot be seen.
     *
     * ## IT USED TO STOP AT [WaterSurface.QUAD_BOTTOM_DEPTH], 7.31 m, AND THAT WAS VISIBLE
     *
     * The old reasoning took the whole of the water quad as needing a backdrop. Only the alpha
     * ramp does; the other 7 m of that quad is the UNDERSIDE COLOUR fade, which is opaque and
     * needs nothing behind it. Those 7 m of unnecessary opaque sunset were harmless right up until
     * something else stopped `main` being opaque underwater — and `8fcaa98`'s god rays do exactly
     * that, because the surface blends with straight alpha (`glBlendFunc(GL_SRC_ALPHA,
     * GL_ONE_MINUS_SRC_ALPHA)` applies to the alpha channel too), so a strip drawn at src alpha
     * `a` leaves `a^2 + dst*(1 - a)` behind it. Measured on a pinned 20 m frame: `main`'s alpha
     * inside a shaft is **191**, against 255 in the water beside it.
     *
     * The sunset then showed THROUGH the water in every band, down to exactly 7.31 m, where it
     * stopped dead — a hard horizontal line cutting every god ray at a fixed world depth,
     * measured at **91/255** at x = -6.25 m. It reads as the shafts being warm near the surface,
     * which is why it survived review: it looks like art.
     *
     * Two defects meet here and this fixes the one that can be fixed safely. The other — that a
     * batch renderer on `main` can silently punch holes in the world's alpha — is a shared-state
     * hazard of the same family as `ShaftRenderer.setTint`'s, and it is NOT fixed: `BlendFunction`
     * is per-surface and batch renderers flush at frame end in add order, so a shaft-only
     * `ADDITIVE` (which would preserve destination alpha) cannot currently be scoped to them.
     * What this change removes is anything for those holes to reveal: below [BOTTOM_DEPTH] there
     * is no sky, and the shafts' own `surfaceFade` holds their alpha near zero above it
     * (`smoothstep(0, 10, 0.61)` = 0.01), so the two windows do not overlap.
     *
     * Costs nothing once the diver is deeper than that: the whole band is above the visible rect,
     * [stripCount] returns 0 and this method issues no draws at all. That is the common case — the
     * sky is on screen for the first few seconds of a dive and the last few of an ascent.
     */
    fun render(surface: Surface, cam: Camera)
    {
        val topLeft = cam.topLeftWorldPosition
        val bottomRight = cam.bottomRightWorldPosition
        val worldLeft = topLeft.x
        val worldTop = topLeft.y
        val width = bottomRight.x - worldLeft
        val bottom = min(bottomRight.y, BOTTOM_DEPTH)

        val count = stripCount(worldTop, bottom)
        for (i in 0 until count)
        {
            val top = worldTop + i * STRIP_METRES
            val stripBottom = min(top + STRIP_METRES, bottom)
            val height = heightAt((top + stripBottom) * 0.5f)
            surface.setDrawColor(redAt(height), greenAt(height), blueAt(height), 1f)
            surface.fillRect(worldLeft, top, width, stripBottom - top)
        }
    }
}
