package render

import dive.Tuning

/**
 * The god rays, as a **stack of sine waves** rather than as a set of objects. Pure Kotlin, no
 * engine imports, so every number here is unit-testable — `ShaftRenderer` uploads them as
 * uniforms and `shaders/godrays.frag` is the ten lines that evaluate them.
 *
 * ## THE OWNER ASKED FOR THIS SHAPE, AND IT REPLACES TWO EARLIER ONES
 *
 * *"could we use a more simple shader that animates opacity of the god rays based on stacked
 * sinus waves?"* — with a reference of soft overlapping bands, varying in width, converging very
 * slightly, edges fading into each other, dimming with depth.
 *
 * That is a different KIND of thing from what came before, and the difference is the point:
 *
 *  - `f2f2eaa` drew five surface-anchored GI lights sharing one tilt. The owner's verdict was
 *    "five straight ruler-drawn stripes of uniform width".
 *  - The next pass (never shipped) made them a fan of seven from a sun apex, each with its own
 *    angle, reach, width and brightness — four hand-authored tables to keep in step.
 *  - This has NO per-shaft table at all. **The width and spacing variation falls out of the
 *    sum.** Four sines whose periods share no small common multiple never repeat inside the
 *    column, so every crest is a different width and every gap a different size, and there is
 *    nothing to author unevenly because nothing is even to begin with.
 *
 * ## WHY IT IS NOT A LIGHT ANY MORE — AND A CORRECTION, BECAUSE THE FIRST ANSWER WAS WRONG
 *
 * `f2f2eaa` made the shafts GI lights on the argument, repeated in CLAUDE.md, that
 * `GlobalIlluminationSystem` MULTIPLIES `mainSurface` by the light map. `8fcaa98` then made them
 * albedo strips on the argument that CLAUDE.md was wrong and the composite is ADDITIVE, citing:
 *
 * ```
 * pulseengine/shaders/lighting/global/final.frag:59   fragColor = vec4(base + light, 1.0);
 * ```
 *
 * **That correction was itself wrong, and this paragraph is the retraction.** The composite is
 * MULTIPLICATIVE; CLAUDE.md was right all along. Settled 2026-08-11 by disassembling
 * `pulse-engine-0.13.0.jar`, and the two readings turn out to be true of different stages:
 *
 *  - `GlobalIlluminationSystem.onUpdate` — NOT its constructor, which is why disassembling only
 *    the setup path misses it — builds `MultiplyEffect("gi_blend_effect", 15, "gi_light_final",
 *    minReflectance)` and passes it to `Surface.addPostProcessingEffect` on
 *    `gfx.getSurface(targetSurface)`, where `targetSurface` is the literal string `"main"`.
 *  - `MultiplyEffect` binds `tex0` from its own input `textures[0]` (mainSurface's render
 *    texture) and `tex1` from the named texture. `effects/texture_multiply_blend.frag` is
 *    `fragColor = vec4(c0.rgb * c1.rgb, c0.a)`, with the `minReflectance` floor applied to `c0`
 *    — i.e. to mainSurface's ALBEDO. That is `DiveRenderer.GI_REFLECTANCE_FLOOR`, which has
 *    documented this correctly in this same source tree the whole time.
 *  - `final.frag`'s `base + light` is real, but `GiFinal` is a post-processing effect on the
 *    **`gi_light_final` surface**, so its `baseTex` — bound, like every such effect, from
 *    `textures[0]` — is that surface's own texture and never mainSurface. (The `gi_local_scene`
 *    name `GiFinal` is constructed with goes to a *different* uniform, `localSceneTex`.) Those
 *    lines assemble the light map; the `MultiplyEffect` above then multiplies it into
 *    mainSurface. Reading `baseTex` as mainSurface is the whole of the error.
 *
 * Confirmed against a capture as well as against the jar: at 105 m, open water on mainSurface
 * post-composite reads `(2,0,0)` / `(4,1,0)` / `(8,2,0)` — **zero blue** — while
 * `DiveRenderer.zoneBlueAt` explicitly holds the authored water blue above the reflectance floor
 * at every depth. Under `base + light` that authored navy would be a floor visible everywhere.
 *
 * ## WHAT THAT MEANS FOR THIS FILE, NOW THAT THE PREMISE IS THE OTHER WAY ROUND
 *
 * The DECISION still stands — these are albedo strips, not lights — but for a different and
 * better reason than the one that was written down. The bands are multiplied by the light map,
 * so a band is dim exactly where the water around it is dim and can never be a bright stripe
 * painted over black. That is the coupling `f2f2eaa` wanted from `drawLight`, obtained without
 * paying `LightEmitter`'s emitter-is-a-visible-region problem.
 *
 * What is genuinely given up is narrower than the retracted paragraph claimed: the shafts still
 * do not OCCLUDE, do not cast on the diver, and do not light a pearl that passes through, because
 * they are not in the light map — they are lit BY it. Nothing in the game depended on any of that.
 *
 * And the depth ramp is not, as was claimed, "the only thing keeping the deep dark" — the multiply
 * darkens them too, so the two now compound. The ramp is kept anyway and unchanged
 * (`DiveLighting.shaftRampForDepth` is still `ambientGreen(d) / ambientGreen(0)`, times the
 * geometric tail below) because it is the guard rail that makes the Abyss exactly zero by
 * GEOMETRY — no strip is submitted at all — which is a promise a multiply cannot make.
 * `LightShaftsTest` fails if the Abyss stops being exactly zero (spec 11, 6b).
 *
 * ## THIS IS A FIELD, NOT A SET OF SHAFTS, AND OTHER EFFECTS CAN SAMPLE IT
 *
 * The owner's water reference (`daothienphu/InteractiveWaterSystem`) has one transferable idea and
 * it is architectural rather than visual: build the wave field ONCE and let the surface
 * displacement, the underwater distortion and the caustics all sample the same field, so they
 * cannot disagree. A bright shaft, a surface crest and a bright caustic then land together because
 * they are the same arithmetic, not because three effects were tuned until they looked aligned.
 *
 * This object is that field for the light. [bandSum] and [bandMask] are pure functions of
 * `(horizontal position, depth, time)` with no engine imports and no renderer behind them —
 * anything that wants to agree with the god rays can call them, and a caustic on the rock face
 * (task 21) would want exactly `bandMask(x, rockDepth, animationSeconds())`. The GLSL side is
 * shareable the same way: `ShaftRenderer.onRenderBatch` uploads `bandFrequency`, `bandAmplitude`
 * and `bandPhase` straight out of this object, so a second shader can upload the identical three
 * uniforms and evaluate the identical five lines rather than inventing its own noise.
 *
 * It cost nothing to leave it in this shape — the numbers had to live somewhere testable anyway,
 * and GLSL cannot be unit-tested — so no abstraction has been built for a caller that does not
 * exist yet. There is no interface, no registry and no "field provider": there is an object with
 * two pure functions on it, which is the version that stays available.
 *
 * What it does NOT share is the depth ramp. That belongs to the shafts alone (see
 * `DiveLighting.shaftRampForDepth`) — a caustic on rock at 40 m is not dimmer because the shafts
 * are, it is dimmer because the light reaching that rock is, which is a different question with a
 * different answer.
 *
 * ## WHAT WAS LEARNED WHILE THEY WERE LIGHTS, WHICH NO LONGER APPLIES BUT MUST NOT BE RE-DERIVED
 *
 * Two findings from `f2f2eaa` were expensive and are now inert. They are recorded in
 * `LightEmitter`'s class doc, under the heading that says they are historical: the RGB-versus-
 * alpha inversion for an emitter you look at the INSIDE of, and the `SHAFT_ALPHA_CEILING = 0.75`
 * window between `scene.frag`'s 0.5 discard and `final.frag`'s 0.8 occluder test. Anyone who ever
 * makes a shaft a `drawLight` again needs both before they start.
 */
object LightShafts
{
    /**
     * The four sines, as `(period in metres, amplitude, drift in radians per second, phase at
     * t = 0)` read down the columns.
     *
     * ## THE PERIODS ARE CHOSEN TO SHARE NO SMALL COMMON MULTIPLE
     *
     * That is the entire mechanism by which this is irregular. Two sines whose periods are in a
     * ratio of 2:1 (or 3:1, or 3:2) sum to a shape that repeats every long period, and a
     * repeating shape is what "ruler-drawn stripes" means even when the stripes are unevenly
     * spaced inside the repeat. 13, 19, 29 and 8 metres are pairwise well away from any small
     * integer ratio, so the sum does not repeat within the 80 m column and no two bands in the
     * frame are the same width. `LightShaftsTest` asserts the ratios rather than the values,
     * because the values are taste and the incommensurability is the requirement.
     *
     * ## THE AMPLITUDES SUM TO EXACTLY 1
     *
     * So the sum lives in [-1, 1] and [BAND_THRESHOLD] / [BAND_PEAK] mean what they say — a
     * threshold expressed as a fraction of the largest crest the stack can produce. Re-tuning an
     * amplitude without re-normalising would move the coverage, i.e. how much of the water is
     * inside a band, which is the level and not the look.
     *
     * ## THE DRIFT IS AUTHORED IN WHOLE CYCLES PER WRAP, WHICH IS NOT A UNIT CHOICE
     *
     * All four rates equal would slide the whole pattern sideways at a constant speed, which reads
     * as the camera panning rather than as light moving. Different rates, with mixed signs, make
     * the crests breathe — bands widen, narrow, merge and separate in place.
     *
     * They are INTEGERS because the animation clock wraps. `WaterSurface.WRAP_SECONDS` establishes
     * why, and it is a booth-reliability finding rather than a look one: the cabinet runs
     * unattended for two days, a free-running float clock reaches 172 800 s by the end of day two,
     * and a `Float` has about 0.015 s of resolution there — the animation would visibly quantise
     * into steps late on the second afternoon, on a machine nobody is watching, after every test
     * has passed. So the clock wraps at [WRAP_SECONDS], and a wrap is only invisible if every
     * component is at an exact whole number of cycles when it happens. That is what an integer
     * here buys, and it is why the rate is derived rather than authored.
     *
     * [WRAP_SECONDS] is the sea's number, deliberately: two ambient animations that wrap at
     * different periods would beat against each other on a cycle neither of them names.
     */
    private val periodMetres = floatArrayOf(13f, 19f, 29f, 8f)
    private val amplitude = floatArrayOf(0.38f, 0.27f, 0.2f, 0.15f)
    private val cyclesPerWrap = intArrayOf(4, -3, 2, -6)
    private val phaseAtZero = floatArrayOf(0f, 1.7f, 3.9f, 5.2f)

    val bandCount: Int get() = periodMetres.size

    /** Angular frequency of band [index], in radians per metre of the SURFACE plane. @see periodMetres */
    fun frequency(index: Int) = (2.0 * Math.PI / periodMetres[index]).toFloat()

    fun amplitude(index: Int) = amplitude[index]

    fun period(index: Int) = periodMetres[index]

    /**
     * How fast band [index] drifts, in radians per second — derived from its whole-cycle count so
     * the wrap at [WRAP_SECONDS] cannot be anything but exact. @see cyclesPerWrap
     */
    fun driftRate(index: Int) = (2.0 * Math.PI * cyclesPerWrap[index] / WRAP_SECONDS).toFloat()

    /** Band [index]'s phase now — its base phase plus its own drift times the animation clock. */
    fun phase(index: Int) = phaseAtZero[index] + driftRate(index) * seconds

    /**
     * # THE CONVERGENCE, WHICH IS ONE LINE OF THE SHADER
     *
     * The bands are evaluated not at a fragment's world x but at where the ray through it meets
     * the SURFACE plane, `(x - APEX_X) * H / (H + depth)`. That single projection is what makes
     * them converge upward and splay downward: two fragments at the same depth that are `d` apart
     * map to `d * H / (H + depth)` apart on the surface plane, so the deeper they are the more of
     * the pattern they span, i.e. the wider apart the bands they fall in.
     *
     * "Converging very slightly" is what the reference shows, and [APEX_HEIGHT_METRES] is the
     * dial: it is the height of the point every band would meet at. At 130 m a band is about 1.7x
     * wider where the overlay gives out than where it enters the water, and a band 30 m off the
     * apex leans about 15 degrees — present at a glance, and nowhere near the starburst a low
     * apex gives (which is what the unshipped seven-ray fan pass looked like). Captured at 220 m
     * first: the splay was there in the arithmetic and invisible in the frame, and the bands read
     * as vertical curtains.
     *
     * [APEX_X] is a lean and not a sun position. Refraction gathers every above-water direction
     * into Snell's window, a cone centred on the ZENITH, so underwater shafts come from overhead
     * whatever the sun's azimuth is — a sunset sky low on one side does not tip them. This is why
     * nothing here reads anything out of `DiveRenderer`, and why a sky agent and a shaft agent
     * cannot get out of step.
     */
    const val APEX_X = 4f
    const val APEX_HEIGHT_METRES = 130f

    /**
     * The band mask is `smoothstep(BAND_THRESHOLD, BAND_PEAK, sum)`, with the sum in [-1, 1].
     *
     * The threshold is what puts DARK WATER BETWEEN THE BANDS: at 0 exactly half the water would
     * be inside a band and the effect would be a ripple rather than a set of shafts. Above it,
     * the crests separate. `BAND_PEAK` below 1 is what stops the brightest band being a needle —
     * the stack only reaches 1 where all four crests coincide, which happens roughly nowhere.
     *
     * The pair is asserted for coverage rather than for value: `LightShaftsTest` measures what
     * fraction of the surface line is inside a band and requires it to be a minority but not a
     * rarity, which is the property "soft overlapping bands" actually names.
     */
    const val BAND_THRESHOLD = 0.05f
    const val BAND_PEAK = 0.8f

    /**
     * How far below the surface the bands take to reach full strength, in metres.
     *
     * It exists for one reason: without it every band ends in a hard bright cap sitting exactly on
     * the waterline, which reads as a row of spotlights aimed downward rather than as light
     * entering the water. Captured at 4 m the cap was still a visible straight edge across the
     * frame — the first metre of water is where the bands are strongest, so the fade has to be
     * long enough to cover the whole of the ramp's own peak, not just to round the corner.
     *
     * It is still short against the 90 m the overlay spans, which is what keeps the shafts
     * brightest near the surface where they belong.
     */
    const val SURFACE_FADE_METRES = 10f

    /**
     * Where the overlay stops being drawn at all, and over how many metres it fades out first.
     *
     * ## THIS IS THE GUARD RAIL'S GEOMETRY HALF, AND IT IS WHAT MAKES THE ABYSS EXACTLY ZERO
     *
     * The depth ramp alone is not enough. `ambientGreen(d)/ambientGreen(0)` is small at 120 m but
     * it is not 0 until the Abyss's own midpoint at 135 m, and "small times a warm colour on
     * near-black water" is still a lift a frame mean can see. `f2f2eaa` got its +0.00% from the
     * shafts simply not being ON SCREEN at 140 m, not from its ramp, and that property has to be
     * reproduced deliberately now that the overlay is a band of geometry rather than five objects.
     *
     * So the strips stop at [END_DEPTH_METRES] and the camera culls them: at 140 m the top of the
     * frame is at 116 m, no strip is submitted, and the Abyss delta is zero by construction and
     * not by arithmetic. [TAIL_METRES] fades the opacity to nothing before that edge so the cut
     * is never visible — a hard horizontal line across the water would be far worse than the
     * shafts themselves.
     *
     * ## AND IT IS ALSO WHERE THE LEVEL AT DEPTH IS SET, WHICH THE RAMP ALONE CANNOT DO
     *
     * The ramp is `ambientGreen(d)/ambientGreen(0)` and is 0.63 at 40 m. The WATER at 40 m is not
     * 63% as bright as at the surface — measured against a same-build control pair it is 14%
     * (frame means 1.03 against 7.51 out of 255), because what a frame's brightness is made of
     * down there is `DiveRenderer`'s zone-band albedo, which falls far faster than the ambient
     * table does. So a band held at 63% is six times too strong relative to the water it sits in,
     * and the first build measured **Kelp +121%** against the control — where the shipped light
     * version measured +12%.
     *
     * That difference cannot be fixed in the ramp, which is the guard rail and is derived rather
     * than authored. It is fixed here: at 50 m with a 45 m tail the combined ramp is 0.08 at 40 m,
     * which lands Kelp back beside the number the owner already accepted. The tail is doing look
     * work as well as safety work, and saying so is better than hiding it inside the ramp — the
     * ramp's identity with the ambient table is the thing that must stay re-derivable.
     */
    const val END_DEPTH_METRES = 50f
    const val TAIL_METRES = 45f

    /**
     * `1` above the tail, falling to `0` at [END_DEPTH_METRES]. Multiplied onto the depth ramp by
     * `DiveLighting.shaftRampForDepth` — the two are separate because they say different things:
     * the ramp is how much daylight is left, this is where we stop drawing.
     */
    fun tailFade(depth: Float): Float
    {
        val t = ((END_DEPTH_METRES - depth) / TAIL_METRES).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * How many horizontal strips the overlay is submitted as, over `SURFACE_DEPTH` to
     * [END_DEPTH_METRES].
     *
     * ## WHY STRIPS AND NOT ONE QUAD
     *
     * The depth ramp is `DepthBlend` over five zone anchors — a smoothstep chain that only exists
     * in Kotlin, and the single source of truth for how dark the deep is. Reimplementing it in
     * GLSL would be a second copy of the one number this whole feature is constrained by. So the
     * ramp is evaluated in Kotlin at each strip BOUNDARY and handed to the shader as a per-strip
     * `(top, bottom)` pair, which the vertex shader interpolates linearly across the strip. Six
     * metres of a smoothstep is indistinguishable from a straight line, and the pattern is the one
     * `DiveRenderer`'s zone bands already use for exactly the same reason.
     *
     * It is also what makes the culling free: a strip is a rect, so the camera rejects the ones
     * that are off screen and the Abyss draws literally nothing.
     */
    const val STRIP_COUNT = 15

    /** The depth of strip [index]'s upper edge. */
    fun stripTopDepth(index: Int) = Tuning.SURFACE_DEPTH + stripHeight() * index

    /** Every strip is the same height; the overlay is a band, not a perspective. */
    fun stripHeight() = (END_DEPTH_METRES - Tuning.SURFACE_DEPTH) / STRIP_COUNT

    /** How wide the overlay is: the water column and nothing else — the rock walls are not water. */
    fun halfWidth() = Tuning.COLUMN_HALF_WIDTH

    /**
     * How many metres before the column's edge the bands start fading out, so that [halfWidth]'s
     * boundary is not a visible straight line down the water.
     *
     * ## THIS FIXED A SEAM THAT WAS MEASURED, NOT GUESSED
     *
     * `98bbcb0` shipped the overlay with no lateral fade at all: the strips simply stopped at
     * +-[halfWidth]. That reads as a hard vertical edge down both sides of the frame, and the
     * play column ends up looking like a lit rectangle pasted over darker water. It is invisible
     * in the 1200x900 dev window because [Framing.VISIBLE_DEPTH_METRES] is 60 m, so at 4:3 the
     * visible width is exactly 60 * 4/3 = 80 m = the full column and both edges sit precisely on
     * the screen border. At 16:9 — which is what a booth display almost certainly is — there are
     * 9 m of water outside the column on each side (the frame is capped at
     * [Framing.VISIBLE_WIDTH_METRES] there) and the seam is one of the first things the eye lands
     * on.
     *
     * Measured on a 3200x1800 capture at 20 m, scanning for the largest single-pixel step along
     * each row (`wide20-0.png`, mainSurface post-composite):
     *
     * ```
     *   depth ~ 9 m   step 15 at x=399->400   (0,3,25) -> (7,9,23)
     *   depth ~16 m   step 36 at x=399->400   (0,3,26) -> (19,17,23)
     *   depth ~26 m   step 16 at x=399->400   (0,2,20) -> (8,9,19)
     *   depth ~36 m   step  5                  (no longer at the boundary)
     *   depth ~46 m   step  3                  (gone)
     * ```
     *
     * x=399.5 of 3200 is world -40.0 m, i.e. exactly `Tuning.COLUMN_HALF_WIDTH`, and the step
     * decays with depth in step with [tailFade] and dies with it — which is what identifies the
     * shafts as the cause rather than the light map. The same scan on a 105 m capture finds no
     * step at the boundary at all. The step is warm-positive INSIDE the column (red and green
     * gain, blue does not), which is this overlay's tint and nothing else's.
     *
     * ## WHY A FADE AND NOT A WIDER QUAD
     *
     * The other way to remove the edge is to draw the bands across the whole visible width. That
     * is rejected on [halfWidth]'s own grounds — the rock walls are not water — and it would put
     * bright bands over the cliff face, where the thing being lit is stone and the shaft would be
     * scattering in front of it rather than in it. Fading instead says something true: the cliff
     * shades the water beside it, so there is less light in the last few metres before the wall.
     *
     * ## WHY 10 m, AND WHY IT IS ITS OWN CONSTANT
     *
     * Long enough that no band is cut mid-crest: the narrowest of the four periods is 8 m (see
     * [period]), so a fade shorter than that could still end a crest abruptly. Short enough to
     * leave 60 of the column's 80 m at full strength. It is deliberately NOT [SURFACE_FADE_METRES]
     * reused, even though both are 10: they answer different questions (how light enters the water
     * versus how the wall shades it) and either may be re-tuned without the other.
     */
    const val WALL_FADE_METRES = 10f

    /**
     * `1` across the middle of the column, falling to `0` at +-[halfWidth]. **The same arithmetic
     * `godrays.frag` performs** on `abs(vWorld.x)`, and duplicated for the reason [bandSum]'s doc
     * gives: GLSL cannot be unit-tested, and the property that actually matters here — that this
     * reaches exactly zero at the boundary, so there is nothing left to make an edge out of — is
     * a property of this function.
     *
     * Same smoothstep shape as [tailFade] and [bandMask].
     */
    fun wallFade(x: Float): Float
    {
        val t = ((halfWidth() - kotlin.math.abs(x)) / (halfWidth() - wallFadeStart())).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The `|x|` at which [wallFade] leaves 1 and starts falling — the shader's `wallFade.x`.
     *
     * Exists so that this subtraction happens exactly ONCE. `ShaftRenderer` uploads it and
     * [wallFade] divides by it, and those are the GLSL and Kotlin halves of the same curve; a
     * second derivation of `halfWidth() - WALL_FADE_METRES` at the upload site is the shape of
     * the bug `6ea1f53` shipped, where two places computed the same thing and one of them drifted.
     */
    fun wallFadeStart() = halfWidth() - WALL_FADE_METRES

    // ---- The animation clock ------------------------------------------------------------------

    /**
     * # THE CLOCK IS THE RENDER CLOCK, AND THAT IS THE OWNER'S ANSWER RATHER THAN A CHOICE
     *
     * `f2f2eaa` shipped no drift, and the reasoning was sound for what it was: the only clock in
     * this codebase a moving shaft could legitimately use was the fixed tick inside
     * `RunLifecycle.simulationAdvances`, which is FALSE in IDLE so that an unattended cabinet is
     * not running a clock all night — and IDLE, the attract screen, is exactly where these shafts
     * do their work. A correctly-gated drift would have been frozen precisely where it was wanted.
     *
     * The owner then asked for a shader that *animates*. A render clock has none of that problem:
     * it runs in IDLE, it is not simulation state, and nothing in `dive/` can see it. What it
     * costs is that a captured frame is no longer a function of the frame number alone, which the
     * screenshot harness in `HARNESS.md` depends on completely — a before/after pair would differ
     * by however much wall time the two runs took to reach frame 240.
     *
     * [PIN_ENV] is the answer to that, and it is the same kind of debug hook `EPT_SCREENSHOT` and
     * `EPT_DEV` already are: set it to a number of seconds and the animation clock is frozen
     * there for the whole run, so two builds captured at the same pin are comparable frame for
     * frame. Unset — which is every booth boot — it costs one getenv at class load and one null
     * check per frame.
     *
     * THE SEA LANDED IN THE SAME PLACE INDEPENDENTLY. `WaterSurface`'s clock note reaches this
     * conclusion for the waves — render clock, advanced unconditionally, exact wrap, env-pinned —
     * and states it as the precedent for all ambient motion. It also records that the god-ray
     * agent "chose no drift at all"; that was true of `f2f2eaa` and is no longer, and the two
     * effects now differ only in where the environment variable is read (see [pinnedSeconds]).
     */
    const val PIN_ENV = "EPT_SHAFT_PHASE"

    /**
     * How long the whole band stack takes to return exactly to its starting shape — the sea's
     * number, shared on purpose. @see cyclesPerWrap for why it exists at all.
     */
    const val WRAP_SECONDS = 240f

    /**
     * Read here rather than in `EnPustTil.onCreate`, which is where `EPT_SCREENSHOT`, `EPT_DEV`
     * and the sea's own `EPT_WAVE_PHASE` are read — only because that file is another agent's and
     * this task may not touch it. If the two are ever unified, this is the one that should move.
     */
    private val pinnedSeconds: Float? = System.getenv(PIN_ENV)?.toFloatOrNull()?.let { wrapped(it) }

    private var seconds = pinnedSeconds ?: 0f

    /**
     * Advance the animation clock by one RENDER frame's [deltaSeconds] — called from
     * `DiveLighting.render`, which is the only place in this file's reach with a render clock.
     *
     * A no-op while [PIN_ENV] is set, which is what makes a pinned capture reproducible; the
     * branch is on a value resolved once at class load, not on an environment read per frame.
     *
     * Guards against a non-finite or negative `dt` rather than trusting it, exactly as
     * `WaterSurface.advance` does and for the same reason: the engine hands `deltaTime` straight
     * from the frame timer, and a stalled frame (a window drag, a display mode change) has
     * produced enormous values elsewhere in this project. A bad `dt` here would put NaN in the
     * phase and every band with it.
     */
    fun advance(deltaSeconds: Float)
    {
        if (pinnedSeconds != null) return
        if (!(deltaSeconds > 0f) || !deltaSeconds.isFinite()) return
        seconds = (seconds + deltaSeconds) % WRAP_SECONDS
    }

    /** [atSeconds] folded into `[0, WRAP_SECONDS)`, for a negative or enormous pin. */
    internal fun wrapped(atSeconds: Float): Float
    {
        if (!atSeconds.isFinite()) return 0f
        val m = atSeconds % WRAP_SECONDS
        return if (m < 0f) m + WRAP_SECONDS else m
    }

    /** The animation clock, in seconds. @see advance */
    fun animationSeconds() = seconds

    /** True when [PIN_ENV] has frozen the clock — the harness's own switch. */
    fun isPinned() = pinnedSeconds != null

    // ---- The model, evaluated ------------------------------------------------------------------

    /**
     * Where world x maps to on the surface plane, at [depth] — the convergence, in Kotlin.
     * @see APEX_HEIGHT_METRES
     */
    fun surfacePlaneX(x: Float, depth: Float) =
        (x - APEX_X) * APEX_HEIGHT_METRES / (APEX_HEIGHT_METRES + depth)

    /**
     * The stacked sum at [x], [depth] and animation time [atSeconds], in [-1, 1] — **the same
     * arithmetic `godrays.frag` performs**, and the reason it exists twice.
     *
     * The GLSL is the production path; this is what lets the band structure be asserted at build
     * time, because "are the bands of varying width" and "is there dark water between them" are
     * properties of this sum and of nothing else. `GodRayShaderTest` pins the shader's text
     * against the uniforms `ShaftRenderer` uploads so the two cannot silently diverge into
     * different arithmetic; what a duplicated *expression* could still drift on is the shape, and
     * that is what the shader test's structural cases cover.
     */
    fun bandSum(x: Float, depth: Float, atSeconds: Float): Float
    {
        val u = surfacePlaneX(x, depth)
        var sum = 0f
        for (i in 0 until bandCount)
        {
            val phase = phaseAtZero[i] + driftRate(i) * atSeconds
            sum += amplitude[i] * kotlin.math.sin(frequency(i) * u + phase)
        }
        return sum
    }

    /** The band mask at a point: [bandSum] through the threshold. In 0..1. @see BAND_THRESHOLD */
    fun bandMask(x: Float, depth: Float, atSeconds: Float): Float
    {
        val t = ((bandSum(x, depth, atSeconds) - BAND_THRESHOLD) / (BAND_PEAK - BAND_THRESHOLD))
            .coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
