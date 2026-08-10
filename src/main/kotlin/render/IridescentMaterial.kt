package render

/**
 * The parameter set that turns the one shared iridescence shader into a particular SURFACE.
 *
 * There is exactly one shader (`src/main/resources/shaders/iridescence.{vert,frag}`) and
 * exactly two materials, [PEARL] and [BUBBLE]. Every field here is uploaded as a per-instance
 * vertex attribute by [IridescenceRenderer], never as a uniform and never as a branch, so both
 * materials can be drawn in the same batch on the same surface if a future object ever needs
 * that — and so "which material" is a value the render code passes rather than a mode the
 * shader is put into.
 *
 * PURE KOTLIN, NO ENGINE IMPORTS, for the reason CLAUDE.md gives for `Framing`, `DepthBlend`
 * and the rest: what a shader LOOKS like can only be settled by capture, but the RELATIONSHIPS
 * between these numbers — the opaque/translucent split, and the darkest albedo the shader can
 * produce staying above `DiveRenderer.GI_REFLECTANCE_FLOOR` — are arithmetic, and arithmetic is
 * assertable without a GL context. See `IridescentMaterialTest`.
 *
 * WHAT IS NOT HERE: the base colour. That comes from the surface's current draw colour
 * (`setDrawColor`), exactly as it does for every `fillRect` in this codebase, so the HUD's
 * cold-blue-to-danger-red switch on the air ring keeps working untouched and a pearl stays the
 * amber `DiveRenderer.pearlColor` it has always been. The material says how a surface behaves;
 * the draw colour says what colour it is.
 */
data class IridescentMaterial(
    /** For diagnostics and for the tests' failure messages only — never uploaded. */
    val name: String,

    /**
     * Film thickness, in units of the shortest primary's wavelength. This is what sets how many
     * interference bands are crossed as the half-vector swings from dead-on at the centre of
     * the sprite to grazing at its rim — i.e. how tightly the rainbow is wound.
     *
     * Small numbers give one broad colour wash across the whole object (which at a 1.2 m pearl,
     * roughly 24 px tall on a 1200 px-high frame, is closer to a tint than to nacre); large
     * numbers wind the bands finer than a pixel and alias into noise. The pearl sits low and
     * the bubble higher because a ring bubble is drawn several times larger on screen and can
     * afford the extra cycles.
     */
    val filmThickness: Float,

    /**
     * How much of the surface the interference film covers. The shader composes
     * `base * (1 - amplitude) + film * 2 * luma * amplitude`: the body's own colour at reduced
     * weight, plus a layer that can reach the full swing in every channel regardless of what
     * the base colour had in it. See the composition comment in `iridescence.frag` for why the
     * first version — a plain multiply, which preserved the mean exactly — produced no visible
     * iridescence on an amber pearl at all.
     *
     * The number that matters structurally is `1 - amplitude`: with the film at its minimum and
     * the sheen unlit, that is the DARKEST colour this shader can produce. On the world surface
     * it has to clear `DiveRenderer.GI_REFLECTANCE_FLOOR` in linear space, or the GI multiply
     * discards the dark bands and substitutes flat grey — see [darkestReflectanceLength] and
     * `IridescentMaterialTest`.
     */
    val amplitude: Float,

    /**
     * Extra film coverage on the side facing the torch, falling off as `dot(N, L)^3` — a sheen,
     * but made of the same interference rather than a separate white highlight, so the bright
     * side of a pearl is where the colour walk is strongest as it is on a real shell.
     *
     * Kept modest: the world surface runs an ACES tone mapper and a THRESHOLDED bloom
     * (threshold 1.4, `DiveLighting.setup`), and neither is linear in radiance near a light —
     * the same non-linearity that made a nominal +27% emitter compensation measure as +82% on
     * the frame mean (`DiveLighting.pearlIntensityForDepth`).
     */
    val sheen: Float,

    /** Alpha multiplier at the middle of the sprite. 1 is opaque. */
    val centreOpacity: Float,

    /** Alpha multiplier at the silhouette. */
    val rimOpacity: Float
) {
    /**
     * The length, in the linear space GI measures reflectance in, of the darkest colour this
     * material can produce from a base colour of linear length [baseReflectanceLength].
     *
     * The shader scales the ALREADY-LINEAR draw colour (the vertex shader converts sRGB to
     * linear before interpolation, exactly as the engine's `texture.vert` does), so the darkest
     * band is a uniform scale of the base vector and its length scales with it. That is why
     * this is a multiply and not a per-channel re-derivation.
     */
    fun darkestReflectanceLength(baseReflectanceLength: Float) =
        baseReflectanceLength * (1f - amplitude)

    /** True if this material lets anything behind it through. */
    val isTranslucent get() = centreOpacity < 1f || rimOpacity < 1f

    companion object
    {
        /**
         * PEARLS — the game's currency, and the anglerfish's lure, which must be drawn with
         * this same material or the trap stops working (see `DiveRenderer.drawAnglerfish`).
         *
         * Opaque: it sits on the world surface, is relit by GI, and is the brightest albedo in
         * the frame. Nothing may show through it.
         */
        val PEARL = IridescentMaterial(
            name = "pearl",
            filmThickness = 0.55f,
            amplitude = 0.45f,
            sheen = 0.35f,
            centreOpacity = 1f,
            rimOpacity = 1f
        )

        /**
         * AIR RING BUBBLES — translucent, on the HUD surface, in screen pixels.
         *
         * The centre opacity is the one number that has to be defended, because the ring is the
         * game's ONLY air warning (`Hud`'s class doc) and making it fainter is a gameplay
         * regression, not a cosmetic one. It is chosen against the rim's 1.0: the bubble keeps a
         * fully opaque outline at every size, so its SILHOUETTE — which is what carries the
         * count, the clockwise depletion and the low-air heartbeat — is exactly as legible as
         * the flat disc it replaces, and only the interior lets the water through.
         *
         * The film is wound tighter than the pearl's because a ring bubble is drawn much larger
         * on screen (0.9 m at the ring's scale, up to 2.8x that on the last bubble), so it has
         * the pixels to resolve more bands.
         *
         * THESE ARE SHADER ALPHA MULTIPLIERS, NOT DISPLAYED OPACITIES, and on this surface the
         * two are not the same number: the HUD surface stores alpha SQUARED (`Hud.tapeBg` has
         * the measurement — a `Color(1,1,1,0.15)` fill lands as RGBA 38,38,38,6). Everything
         * else on that surface therefore pre-corrects with `Hud.authoredAlphaFor`, and this
         * deliberately does not, because the correction cannot be applied to a per-fragment
         * curve from a call site that only sets a flat draw colour. 0.62 is chosen to be
         * unambiguously translucent under EITHER reading of that measurement — it displays at
         * 0.62 if the squaring is a capture artefact of the compositor and at 0.38 if it is
         * real, and the bubble reads as glass at both. Settled by capture, not by algebra.
         *
         * The rim stays at 1: the OUTLINE of a bubble is the part the air warning is made of.
         */
        val BUBBLE = IridescentMaterial(
            name = "bubble",
            filmThickness = 0.95f,
            amplitude = 0.55f,
            sheen = 0.30f,
            centreOpacity = 0.62f,
            rimOpacity = 1f
        )

        /**
         * The materials drawn onto a surface that `GlobalIlluminationSystem` relights, i.e. the
         * ones the reflectance floor applies to. The HUD is not relit (see the "hud"
         * `createSurface` call in `EnPustTil.onCreate`) so [BUBBLE] is deliberately absent.
         *
         * A list rather than a comment so the floor check is over whatever is actually on the
         * world surface, not over whatever someone remembered to add to a test.
         */
        val WORLD_MATERIALS = listOf(PEARL)
    }
}
