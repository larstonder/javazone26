package render

import dive.Tuning
import no.njoh.pulseengine.core.PulseEngineInternal
import no.njoh.pulseengine.core.asset.types.FragmentShader
import no.njoh.pulseengine.core.asset.types.VertexShader
import no.njoh.pulseengine.core.graphics.api.RenderTexture
import no.njoh.pulseengine.core.graphics.api.ShaderProgram
import no.njoh.pulseengine.core.graphics.postprocessing.effects.BaseEffect

/**
 * THE BLACK DISCS IN THE WATER — what caused them, why they cannot be fixed where they are made,
 * and what this puts back.
 *
 * ## The defect
 *
 * On the attract screen the water was pocked with small solid-black discs, a dozen or so of them,
 * 12-30 px across, sitting where motes overlapped INSIDE A GOD RAY. They are not drawn by
 * anything: they are the ABSENCE of `main`.
 *
 * `BlendFunction` is a two-field enum (`src`, `dest`) feeding a single `glBlendFunc(src, dest)`,
 * and `NORMAL` is `(770, 771)` = `GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`. OpenGL applies those
 * factors to the ALPHA channel exactly as it does to RGB, so a translucent draw over an opaque
 * destination leaves
 *
 *     dst_a = a*a + dst_a*(1 - a)        instead of the correct     a + dst_a*(1 - a)
 *
 * That stray `a²` is the whole bug. Iterated over overlapping draws it is a contraction toward
 * `a` itself, so a pixel under several translucent draws ends the frame at an alpha of a few
 * percent while its RGB is a perfectly correct "over" composite of everything that was drawn.
 *
 * Correct straight-alpha "over" needs `glBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE,
 * ONE_MINUS_SRC_ALPHA)`. **`glBlendFuncSeparate` appears nowhere in the engine jar** (grepped), so
 * it cannot be expressed at all: not per surface, and certainly not per batch renderer.
 *
 * ## IT IS NOT A MOTE BUG. THE GOD RAYS ARE THE ENABLER AND NEITHER CAUSE IS SUFFICIENT ALONE
 *
 * This matters because the obvious reading — "the marine snow punches holes" — sends the next
 * reader to `Motes` to turn the density down, which does make the discs go away and fixes nothing.
 * Every translucent draw on `main` erodes its alpha; what decides whether the erosion becomes
 * VISIBLE is how much of it landed on the same pixel, and the shafts get there first. `Sky`'s own
 * class doc records `ShaftRenderer` leaving `main`'s alpha at **191/255** inside a shaft against
 * 255 in the water beside it — measured on a pinned 20 m frame, and at the time mis-diagnosed as
 * art, because a warm band across the shafts looks deliberate.
 *
 * The arithmetic of the compounding, at `Motes.MOTE_ALPHA` = 0.25:
 *
 *     over opaque water    one mote   0.0625 + 0.75 * 1.00 = 0.81   (alpha 207)  invisible
 *     over a shaft (0.75)  one mote   0.0625 + 0.75 * 0.75 = 0.63   (alpha 159)  visible
 *     over a shaft         two motes  0.0625 + 0.75 * 0.63 = 0.53   (alpha 135)  a black disc
 *
 * And it is confirmed by where the discs are rather than only by the algebra: of the twelve on the
 * attract capture, the nine mote-sized ones (14-26 px) each sit in water measuring **1.13x to
 * 1.76x** the frame's water median, i.e. inside a shaft column, while the three larger ones sit in
 * water at 0.58-0.65x — a different population entirely, in dark water, and not this mechanism.
 * (Those three are ~27-77 px, and 2.4 m of air vent is ~78 px at this framebuffer, so they are
 * most likely the un-arted air-pocket placeholders. Out of scope here and recorded separately.)
 *
 * So the verification target for this file is not "the discs are gone" but **`main`'s alpha below
 * the waterline is 255 INSIDE the god-ray columns too**, which is the one number that says both
 * contributions were repaired rather than one of them being hidden.
 *
 * ## Why the hole is BLACK rather than merely thin
 *
 * `main`'s alpha survives the whole post chain. GI's composite is `vec4(c0.rgb * c1.rgb, c0.a)`
 * (`texture_multiply_blend.frag`), bloom is `vec4(src.rgb + bloom, src.a)` and colour grading only
 * ever writes `.rgb` — so the eroded alpha reaches the backbuffer composite intact, and
 * `BackBufferBaseState` hardcodes `glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)` once before
 * the loop over surfaces. Below the waterline the `"sky"` surface is `Color.BLANK` and there is
 * nothing else behind `main` at all, so an eroded pixel reveals the cleared backbuffer: black.
 *
 * ## What was considered and rejected: a backdrop behind `main`
 *
 * The obvious alternative is to put something non-black behind the world so a hole reveals water
 * rather than void. It does not work, and the reason is measurable rather than aesthetic: the
 * water on `main` goes through GI's multiply AND [ColorGradingEffect][no.njoh.pulseengine.core
 * .graphics.postprocessing.effects.ColorGradingEffect] (both attached to `mainSurface`), and a
 * backdrop on another surface gets neither — the sunset measures BIT-IDENTICAL under three
 * different tone mappers precisely because it is on `"sky"` and out of reach. A backdrop would
 * replace a black disc with a differently-wrong disc at every depth, and it would have to track
 * the zone bands, the light map and the grade forever.
 *
 * ## What this does instead
 *
 * A full-frame post-processing pass on `mainSurface` that writes alpha 1 below a gate, and passes
 * alpha through untouched above it.
 *
 * **THE GATE IS A POSITION, GATED ON DEPTH, AND NEVER ON THE ALPHA VALUE.** That is the entire
 * justification and it is worth stating precisely, because "restore alpha where it looks wrong" is
 * the version of this that has a threshold in it and would be wrong:
 *
 *   `main`'s alpha is legitimately partial in EXACTLY ONE PLACE — the waterline, where
 *   `water.frag` ramps `coverage` over about a pixel to anti-alias the sea against the sky behind
 *   it (`float aa = max(fwidth(vPos.y), 1e-5); coverage = smoothstep(-aa, aa, below)`), and
 *   discards entirely above it. Below the waterline everything on `main` is opaque by
 *   construction: the water quad writes `alpha = coverage`, which is exactly 1 more than a pixel
 *   under the wave; the zone bands, the rock and the diver are opaque fills and textures.
 *
 * So there is no threshold to tune, no risk of hardening the meniscus, and no way for this to
 * interact with a future translucent object — a genuinely translucent thing drawn on `main` below
 * the waterline would ALREADY be composited against a black void, and the fix for that is a
 * backdrop for it, not a partial alpha in the world surface.
 *
 * ## Ordering does not matter, and that is worth saying rather than leaving to be worried about
 *
 * [ORDER] puts this after the grade and the bloom purely for readability. Every effect on
 * `mainSurface` passes alpha through unchanged — the multiply is `vec4(c0.rgb * c1.rgb, c0.a)`,
 * bloom's final pass is `vec4(srcColor.rgb + bloomColor, srcColor.a)` and `color_grading.frag`
 * only ever assigns `.rgb` — so this pass would produce the identical frame at any position in the
 * chain. It is placed last of the real effects anyway so that `EPT_SCREENSHOT`'s dump of `main`
 * (order 10 000) shows the alpha channel the compositor will actually receive.
 *
 * ## What this does NOT fix, and the second population it leaves standing
 *
 * **The engine defect itself.** Every other surface still erodes its own alpha the same way — the
 * `"hud"` surface most obviously, where overlapping translucent draws thin the overlay against
 * whatever is behind it. Nothing there currently overlaps enough to see, and the same trick is
 * available if it ever does. What cannot be fixed from game code is the blend function.
 *
 * **A SECOND, DIFFERENT SET OF BLACK DISCS SURVIVES THIS, IN THE FIRST METRE OF WATER, AND IT IS
 * NOT AN ALPHA BUG AT ALL.** Four of them on the attract capture, all at 0.58-0.65 m, i.e. above
 * [GATE_DEPTH]. Do not reach for the gate to catch them — lowering it would make them WORSE, and
 * here is why.
 *
 * Measured on an `EPT_SCREENSHOT` dump of `main`, those pixels read `RGBA(0, 1, 2, 0)`: not an
 * eroded alpha over a correct colour, but the CLEARED BACKGROUND, never written by anything. (The
 * `(0, 1, 2)` rather than `(0, 0, 0)` is `MultiplyEffect`'s `minReflectance` path-to-white acting
 * on a black pixel afterwards.) Forcing alpha to 1 there would composite that black at full
 * strength — trading a hole that shows sunset through its top for a solid black disc.
 *
 * They are the motes, confirmed by probe: with `Motes.render` commented out the low-alpha pixel
 * count in that band falls from 325 to 23. And the mechanism is the one `SurfaceRendererOrderTest`
 * documents in full — **batch renderers flush in ADD order, and every renderer writes depth even
 * for fragments it draws at alpha 0.** The motes go through the engine's own `TextureRenderer`,
 * which is attached to every surface at creation and therefore flushes FIRST; they take a greater
 * `currentDepth` than the water; so their quads write depth across the water quad's band before
 * `WaterRenderer` ever runs, and every water fragment underneath one then fails `GL_LEQUAL`. That
 * is character for character the shafts-versus-water bug in that test's doc, arriving on a
 * renderer whose add order we do not control.
 *
 * It only shows in the top [WaterSurface.QUAD_BOTTOM_DEPTH] metres because that is the only place
 * a water QUAD exists to be cut — below it the water is zone bands, drawn through the same
 * `TextureRenderer` as the motes, where the shared depth cursor orders them correctly. The deep
 * has none of these, which is why the frame below 7.31 m comes out clean.
 *
 * BETWEEN THE GATE AND 7.31 m THIS PASS IMPROVES THEM WITHOUT CURING THEM, and it is worth knowing
 * which half it fixed. The cut exposes whatever was drawn BEFORE the water quad — `drawBackdrop` —
 * so the pixel does hold a colour, just a much darker one, and the alpha erosion on top of that
 * took it to black. Measured at three of them on the attract capture, mote-clump against the water
 * beside it, RGB:
 *
 *     depth    before          after           water beside it
 *     1.63 m   ( 2.9,  4.9,  8.7)  (16.6, 25.2, 39.3)  (38.2, 53.4, 79.7)
 *     2.96 m   ( 1.8,  6.0, 12.3)  ( 2.6, 16.0, 43.7)  (18.0, 41.6, 77.9)
 *     3.70 m   ( 1.0,  4.7, 10.5)  ( 2.5, 22.6, 57.7)  (13.4, 40.1, 78.6)
 *
 * i.e. from a black hole to a dim patch of water. Confirmed as the same mechanism by probe: with
 * `Motes.render` commented out those three spots read (1.0, 5.0, 20.0) against water at
 * (1.0, 5.6, 19.9) — indistinguishable from the water, on `main`'s own scale.
 *
 * Recorded here rather than fixed: the fix is somewhere in `Motes`/`WaterRenderer` ordering or in
 * the emitter's alpha test, it is a different defect with a different mechanism, and it is not
 * this pass's to make.
 */
class OpaqueWaterEffect : BaseEffect()
{
    override val name = NAME
    override val order = ORDER

    /**
     * The waterline gate in this surface's uv, `1` at the top of the frame and `0` at the bottom.
     * Fragments BELOW it (i.e. with a smaller `uv.y`) get alpha 1.
     *
     * Written once per frame by `EnPustTil.onRender` from [gateUv], read by [applyEffect] on the
     * render thread at the end of the same frame — the same one-way, once-per-frame handoff
     * `ColorGradingEffect.exposure` and friends already use.
     *
     * **0 is a deliberate no-op default**, not an unset sentinel: no fragment has `uv.y < 0`, so
     * a frame drawn before anything sets this is passed through untouched rather than being
     * flooded opaque. That matters on frame 1, before `CameraRig` has written a scale.
     */
    var gate = 0f

    override fun loadShaderProgram(engine: PulseEngineInternal) = ShaderProgram.create(
        engine.asset.loadNow(VertexShader(VERTEX_SHADER)),
        engine.asset.loadNow(FragmentShader(FRAGMENT_SHADER))
    )

    override fun applyEffect(engine: PulseEngineInternal, inTextures: List<RenderTexture>): List<RenderTexture>
    {
        fbo.bind()
        fbo.clear()
        program.bind()
        program.setUniformSampler("baseTex", inTextures[0])
        program.setUniform("gateUv", gate)
        renderer.draw()
        fbo.release()
        return fbo.getTextures()
    }

    companion object
    {
        /** The effect's name on the surface. Unique — nothing else on `main` is called this. */
        const val NAME = "opaque_water"

        /**
         * After `BloomEffect` (90) and `ColorGradingEffect` (100), before `ScreenshotEffect`
         * (10 000). See the class doc: this is a readability choice and not a correctness one,
         * because every effect on `mainSurface` passes alpha through unchanged.
         */
        const val ORDER = 500

        /**
         * Shader paths, under `/shaders/` and NOT under `/pulseengine/` — the same rule
         * `ShaftRenderer` and `IridescenceRenderer` follow, for the same two reasons: a file
         * there can neither shadow an engine shader on the classpath nor be caught by
         * `build.gradle.kts`'s `devOnlyShaderOverrides` exclusion, which drops our copies of two
         * engine shaders from the release jar. These must ship, and `OpaqueWaterShaderTest`
         * asserts they are on the classpath.
         */
        const val VERTEX_SHADER = "/shaders/opaque_water.vert"
        const val FRAGMENT_SHADER = "/shaders/opaque_water.frag"

        /**
         * How far below the deepest possible wave trough the gate sits, in metres.
         *
         * The trough itself is bounded: [WaterSurface.AMPLITUDE_METRES] is the sum of the three
         * components' amplitudes, reached only where all three align, so the boundary can never be
         * deeper than `SURFACE_DEPTH + AMPLITUDE_METRES` at any x, at any phase. This is the slack
         * on top of that bound, and it exists so that the gate cannot land on the one place
         * `main`'s alpha is legitimately partial — `water.frag`'s anti-aliasing ramp, which is one
         * `fwidth` wide, i.e. ONE PIXEL, wherever it happens to be.
         *
         * 1 m is three times the amplitude bound and about 19 px at the ~19.5 px/m of a 1080p 16:9
         * booth panel (32.5 px/m on the dev window, more on anything larger — the width-capped
         * regime makes pixels-per-metre grow with the panel, so a bigger display only makes this
         * margin bigger in pixels). Nineteen pixels of clearance against a one-pixel ramp.
         *
         * It is not larger because everything between the waterline and the gate keeps the defect,
         * and 1 m is already past the depths where anything erodes alpha: [Motes.surfaceFade] is
         * exactly 0 at the waterline and only 0.11 of full at 1.31 m, and `LightShafts`' own
         * surface fade holds the god rays near zero over the same band.
         */
        const val GATE_MARGIN_METRES = 1f

        /**
         * The world depth at and below which `main` is forced opaque.
         *
         * DERIVED, never typed, from the wave table it has to clear — raising an amplitude in
         * [WaterSurface.components] moves this with it rather than leaving a gate the sea can now
         * reach. `OpaqueWaterTest` asserts the three relationships that make the number safe:
         * it is below the deepest trough by [GATE_MARGIN_METRES], it is below [Sky.BOTTOM_DEPTH]
         * (so there is provably nothing behind `main` at this depth to hide), and it is above
         * [WaterSurface.QUAD_BOTTOM_DEPTH] (so it lies inside the band where `water.frag` itself
         * writes `alpha = 1`, rather than merely where we believe the frame is opaque).
         */
        val GATE_DEPTH = Tuning.SURFACE_DEPTH + WaterSurface.AMPLITUDE_METRES + GATE_MARGIN_METRES

        /**
         * [GATE_DEPTH]'s screen y, converted to the uv the fragment shader compares `uv.y` against.
         *
         * The engine's full-frame vertex shader maps NDC `+1` to `texCoord` 1, and the surface's
         * orthographic projection is `ortho(0, width, height, 0)` — so screen y 0 (the top of the
         * frame) is uv.y **1** and screen y `height` is uv.y **0**. The flip is the whole content
         * of this function and it is exactly the kind of thing that is invisible when wrong in one
         * still frame: inverted, it would force the SKY opaque and leave the water untouched.
         *
         * The clamp is not defensive, it is the behaviour at both ends of a dive:
         *
         *  - gate above the top of the frame (the diver is deep, `screenY < 0`) -> **1**, the whole
         *    frame is below the waterline and every fragment is restored. This is the common case:
         *    the waterline is off screen for all but the first and last seconds of a run.
         *  - gate below the bottom of the frame (the camera is looking at nothing but sky,
         *    `screenY > height`) -> **0**, which is the shader's own no-op.
         *
         * A non-finite or zero [surfaceHeight] returns 0 for the same reason [gate]'s default is 0:
         * the safe failure is "change nothing".
         */
        fun gateUv(gateScreenY: Float, surfaceHeight: Float): Float
        {
            if (!(surfaceHeight > 0f) || !surfaceHeight.isFinite() || !gateScreenY.isFinite()) return 0f
            return (1f - gateScreenY / surfaceHeight).coerceIn(0f, 1f)
        }
    }
}
