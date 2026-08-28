package render

/**
 * The parameter set that turns the one shared iridescence shader into a particular SURFACE.
 *
 * There is exactly one shader (`src/main/resources/shaders/iridescence.{vert,frag}`) and
 * exactly three materials, [PEARL], [BUBBLE] and [VENT]. Every field here is uploaded as a
 * per-instance vertex attribute by [IridescenceRenderer], never as a uniform and never as a
 * branch, so any of them can be drawn in the same batch on the same surface if a future object
 * ever needs that — and so "which material" is a value the render code passes rather than a
 * mode the shader is put into.
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
     * numbers wind the bands finer than a pixel and alias into noise. This used to add "the
     * pearl sits low and the bubble higher because a ring bubble is drawn several times larger
     * on screen"; a ring bubble is in fact SMALLER than a pearl until the ring starts oversizing
     * its last few, so that is not why. The worked sizes are in [VENT], and each material's own
     * doc now says which of its bound is a pixel argument and which is not.
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
         * The film is wound tighter than the pearl's. THE REASON GIVEN HERE USED TO BE SIZE —
         * "a ring bubble is drawn much larger on screen (0.9 m at the ring's scale, up to 2.8x
         * that on the last bubble), so it has the pixels to resolve more bands" — and that does
         * not survive the arithmetic, which was only done when [VENT] needed the same argument
         * (see its film-thickness section for the worked sizes). `Hud.drawAirRing` scales the
         * bubble by the world's OWN `pixelsPerMetre`, so at a full ring 0.9 m is 17.5 px on a
         * 1080p 16:9 panel against a 1.2 m pearl's 23.4 px: a bubble is SMALLER than a pearl,
         * not larger, until [Hud.airBubbleSizeScale] starts oversizing the last few. So 0.95 is
         * not explained by pixels. It is one of the values this file's header says can only be
         * settled by capture, it was, and the capture stands; the explanation was wrong and the
         * number is not. Left corrected rather than deleted because the same false size argument
         * would otherwise be re-derived by the next material that wants a finer wind.
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
         * AIR VENTS — the oxygen refills (`dive/AirPocket.kt`), drawn on the WORLD surface by
         * `DiveRenderer.drawAirPockets`, in world metres, at [Framing.AIR_POCKET_SIZE_METRES].
         *
         * A vent is meant to read as a big cousin of the ring bubbles, so the obvious move is to
         * pass [BUBBLE] and be done. IT CANNOT. [BUBBLE] is tuned against the "hud" surface,
         * which `GlobalIlluminationSystem` does not relight and whose alpha does not behave like
         * the world's. Every number below is [BUBBLE]'s number moved for one of those two
         * reasons, and the reason is given rather than the value defended — see the closing
         * section for which half of this is a contract and which half is a starting point.
         *
         * ## OPACITY: 0.62 IS A HUD NUMBER AND DOES NOT TRANSFER
         *
         * [BUBBLE]'s centre is 0.62 because the HUD surface stores alpha SQUARED (`Hud.tapeBg`
         * has the measurement) and 0.62 reads as glass under EITHER reading of that: it displays
         * at 0.62 if the squaring is a capture artefact of the compositor, and at 0.38 if it is
         * real. That correction is a property of THAT surface. Nothing measured on the world
         * surface squares alpha, so here the authored number IS the displayed number — and
         * copying 0.62 across would silently park the vent at the SOLID end of a bracket the
         * bubble is only known to be somewhere inside, i.e. a vent noticeably more opaque than
         * the ring it is supposed to echo. 0.45 is inside `[0.38, 0.62]` at both ends, so the
         * vent lands in the same material family as the ring whichever way that measurement
         * eventually resolves.
         *
         * The rim is 0.90 and deliberately NOT [BUBBLE]'s 1.0. A ring bubble's outline is pinned
         * solid because the ring is the game's only air warning and its SILHOUETTE carries the
         * count, the clockwise depletion and the low-air throb (`Hud`'s class doc). A vent
         * carries no count. What its rim has to do is stay findable by torchlight against
         * near-black deep water, which 0.90 does while still letting the water through — an
         * opaque edge around a translucent middle is a BEAD, and a bead is the one thing this
         * material must not look like, since the opaque bead is already taken by [PEARL] and by
         * the anglerfish's lure. The gradient direction is kept: denser at the rim than at the
         * centre is what a soap film does and is the shape `iridescence.frag`'s
         * `mix(vBody.x, vBody.y, rimness)` exists to draw.
         *
         * ## FILM THICKNESS: 0.80, BETWEEN THE OTHER TWO, AND ONLY ONE BOUND IS ABOUT PIXELS
         *
         * The sizes, since two materials have now claimed a wind on size grounds and only one of
         * them was entitled to. At 1080p and 16:9 the width binds, so `pixelsPerMetre` is
         * `1920 / Framing.VISIBLE_WIDTH_METRES` = 19.49 (see CLAUDE.md's two-regime invariant),
         * and the same scale is handed to `Hud.drawAirRing`. A vent is therefore **46.8 px**, a
         * pearl **23.4 px**, and a ring bubble at a full ring **17.5 px** — doubling on a 4K
         * panel, and all three in the same ratio at every resolution. A vent is the LARGEST thing
         * this shader draws, except against the last one or two oversized bubbles of a dying air
         * ring (2.8x, 49 px).
         *
         * ABOVE [PEARL]'s 0.55, and this bound is about pixels. Band count scales with thickness,
         * so the pearl's wind across twice the diameter is half the bands per unit of silhouette
         * — which is precisely the failure [filmThickness]'s own doc names at pearl size ("closer
         * to a tint than to nacre"), made twice as large.
         *
         * BELOW [BUBBLE]'s 0.95, and this bound is NOT about pixels — the vent has more of them,
         * and the size argument that used to sit in [BUBBLE]'s doc is corrected there. It is
         * about CONTRAST, which is the world surface's difference from the HUD's. A ring bubble's
         * bands composite as authored. A vent's are multiplied by the light map (the GI composite
         * is a MULTIPLY — CLAUDE.md states this is settled), then run through an ACES tone mapper,
         * and below the Kelp the only thing lighting one is a grazing torch beam. Bands that
         * arrive with less contrast should be fewer and wider or they average into a flat wash
         * anyway. Straight-line scaling with diameter off the pearl would ask for 1.10; 0.80 takes
         * about half of that and leaves the unlit surface holding the finest wind in the game.
         *
         * ## SHEEN: 0.25, THE LOWEST OF THE THREE, BECAUSE THE VENT'S BASE IS THE BRIGHTEST
         *
         * The film LAYER is scaled by the base colour's luma (`film * 2.0 * luma * coverage`),
         * and a fresh vent is handed the brightest base this shader ever gets: `airPocketColor`
         * (0.65, 0.95, 1) is Rec. 709 luma **0.790** in linear, against `pearlColor`'s 0.628 and
         * the ring's cold blue (0.75, 0.85, 1) at 0.678. The same sheen number therefore buys ~26%
         * more absolute swing on a vent than on a pearl, on the one surface that runs the ACES
         * tone mapper and the THRESHOLDED bloom (threshold 1.4, `DiveLighting.setup`) — the two
         * stages [sheen]'s own doc already says are not linear in radiance. So the number that
         * doc calls "modest" is smaller here for the same reason it exists at all.
         *
         * ## AMPLITUDE: 0.55, THE SAME AS THE BUBBLE'S, AND THE GI FLOOR IS NOT CLOSE
         *
         * [amplitude] is the "how much film, how little body" knob (the substrate's weight is
         * `1 - amplitude`), and on that axis a vent simply IS a bubble — it is the one number
         * that needs no world-surface correction, provided the reflectance floor allows it.
         *
         * It does, and the case that decides it is the SPENT vent, not the fresh one. A used vent
         * is drawn in `DiveRenderer.airPocketSpentColor` (0.22, 0.34, 0.42) — linear length
         * **0.180** against the fresh 1.392, a fifth of the reflectance from the same material,
         * so it is the one that could fall through `DiveRenderer.GI_REFLECTANCE_FLOOR` and come
         * back as flat grey. At 0.55 its darkest band is [darkestReflectanceLength] = **0.0808**,
         * **4.0x** the 0.02 floor; the floor would only begin to bite at amplitude 0.889. There
         * was no trade to make. `IridescentMaterialTest` asserts both colours rather than this
         * paragraph being believed.
         *
         * Translucency does add one wrinkle the other two world materials do not have: what the
         * GI multiply reads is not the material, it is the material ALPHA-BLENDED with the water
         * behind it. Both are blue-dominant and both clear the floor on their own, so no mix of
         * them can fall under it — asserted over the whole reachable column rather than argued,
         * because "roughly collinear so the convex combination is safe" is the kind of reasoning
         * that is right until the water's hue is retuned.
         *
         * ## WHAT IS A CONTRACT HERE AND WHAT IS NOT
         *
         * These five values are a STARTING POINT to be settled by capture. This project settles
         * appearance by screenshot and never by algebra (CLAUDE.md, "Seeing it actually run"),
         * and nothing above claims otherwise: whether a 47 px blob of this reads as gas or as
         * jelly is not knowable from these numbers. What the test pins is the RELATIONSHIPS —
         * the thickness ordering against the other two, the centre opacity being under
         * [BUBBLE]'s, the translucency, and the floor — so a capture session is free to move any
         * of the values and will be told the moment it moves one out of its bracket.
         */
        val VENT = IridescentMaterial(
            name = "vent",
            filmThickness = 0.80f,
            amplitude = 0.55f,
            sheen = 0.25f,
            centreOpacity = 0.45f,
            rimOpacity = 0.90f
        )

        /**
         * The materials drawn onto a surface that `GlobalIlluminationSystem` relights, i.e. the
         * ones the reflectance floor applies to. The HUD is not relit (see the "hud"
         * `createSurface` call in `EnPustTil.onCreate`) so [BUBBLE] is deliberately absent.
         *
         * A list rather than a comment so the floor check is over whatever is actually on the
         * world surface, not over whatever someone remembered to add to a test.
         */
        val WORLD_MATERIALS = listOf(PEARL, VENT)

        /**
         * Every material, for the checks that are about the SHADER's domain rather than about a
         * surface — the ranges `iridescence.frag`'s composition is defined over.
         *
         * Same argument as [WORLD_MATERIALS]: a fourth material must arrive already covered by
         * those checks, rather than covered only if whoever added it also remembered a test.
         */
        val ALL = listOf(PEARL, BUBBLE, VENT)
    }
}
