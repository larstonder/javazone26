package render

/**
 * # EVERY NUMBER THAT DECIDES HOW THE GAME LOOKS, IN ONE PLACE.
 *
 * The presentation twin of `dive.Tuning`, and it follows the same rule that file does: **values
 * and no logic**. Nothing here computes anything, nothing here draws anything, and no code outside
 * `render/` reads it. Change a number, run `./gradlew run`, look at it.
 *
 * ## HOW TO USE IT
 *
 * The groups below are ordered the way you see them on screen — sky first, then the sea's surface,
 * then the water you swim through, then what lights it, then the things in it, and finally the
 * grade that sits over the whole frame. Start at the group that names what is bothering you.
 *
 * If you only want the frame *lighter or darker overall*, that is one number: [GRADE_EXPOSURE].
 * It is a straight gain over everything underwater and changes no relationship inside the frame.
 * Everything else here changes how one thing relates to another.
 *
 * ## WHERE THE REASONING IS, AND WHY IT IS NOT HERE
 *
 * Most of these numbers were measured rather than chosen, several of them twice, and the argument
 * is often long — the ambient tables alone carry a page about why the floor is applied *after* the
 * blend and not typed into them. That reasoning stays next to the code that consumes it, where a
 * reader who is about to change behaviour will actually meet it. Each entry below carries a
 * one-line summary and a `@see` to the full account.
 *
 * **So: skim the summary, and read the `@see` before you move something a long way.** Small
 * adjustments are safe to make on taste alone. Large ones are how the deep went black in the first
 * place.
 *
 * ## IF THE BUILD FAILS AFTER YOU EDIT THIS FILE, READ THE MESSAGE — IT IS NOT NOISE
 *
 * A dozen of these numbers are constrained against each other rather than absolutely, and those
 * constraints are asserted. A test failing here is a design rule telling you what it protects, in
 * its own words. The four you are most likely to meet:
 *
 *  - the water is pinned brighter in the Shallows than in the Abyss by more than 10x
 *    (`DiveRendererTest`) — that is §11's "deep blue falling to near-black";
 *  - the ambient floor must stay blue-dominant (`DiveLightingTest`), or it reads as grey fog
 *    sitting in front of the scene rather than as water;
 *  - the motes must stay far from the pearls' hue (`MotesTest`), or the anglerfish's lure stops
 *    being a trap — the outstanding-work spec §1.1;
 *  - every water band must clear the GI reflectance floor once quantised (`DiveRendererTest`), or
 *    GI replaces the band with flat grey and the column gets a hairline seam across it.
 *
 * ## WHAT IS DELIBERATELY NOT HERE
 *
 * Anything that is a *correctness* constraint rather than a look decision, because putting it on a
 * page headed "tweak to taste" is an invitation. `DiveRenderer.GI_REFLECTANCE_FLOOR` is the engine's
 * own threshold and is not ours to pick; `Motes.FIELD_TOP_DEPTH` and `OpaqueWater.GATE_DEPTH` are
 * depth-buffer and alpha-blend guards; `MoteSprite.ALPHA_RAMP_OUTER` is solved from the engine's
 * alpha discard. All of them live with their derivations and their warnings.
 *
 * Shader-side numbers are also absent — `water.frag` and `iridescence.frag` carry their own, and
 * they cannot be reached from Kotlin without a uniform to carry them.
 */
object Look
{
    // =============================================================================================
    // 1. THE SKY — everything above the waterline.
    // =============================================================================================
    //
    // A vertical gradient sampled at five stops, in METRES above the water rather than in pixels,
    // so the sunset keeps its proportions on any panel. Stop 0 is the horizon.
    //
    // The sky is on its OWN surface and escapes both the GI multiply and the colour grade — so it
    // is the one thing here that [GRADE_EXPOSURE] does not move. Brighten the water a long way and
    // the sunset will start to look dim beside it; this is the pair to re-check.
    //
    // @see Sky for the surface split and why the sky must escape the light map.

    /** Height of each gradient stop above the waterline, in metres. Must ascend. */
    val SKY_STOP_HEIGHTS = floatArrayOf(0f, 2.5f, 7f, 15f, 26f)

    /** Sunset red, horizon first. @see SKY_STOP_HEIGHTS */
    val SKY_STOP_RED = floatArrayOf(1.00f, 0.98f, 0.78f, 0.34f, 0.13f)

    /** Sunset green, horizon first. @see SKY_STOP_HEIGHTS */
    val SKY_STOP_GREEN = floatArrayOf(0.62f, 0.44f, 0.28f, 0.19f, 0.12f)

    /** Sunset blue, horizon first. Note the RISE at stop 2 — that is dusk overhead. */
    val SKY_STOP_BLUE = floatArrayOf(0.36f, 0.30f, 0.38f, 0.44f, 0.34f)

    // =============================================================================================
    // 2. THE SEA'S SURFACE — the lit waterline, seen from below.
    // =============================================================================================

    /**
     * The low sun's colour on the water's surface, LINEAR — a warm sodium orange. This is the
     * sunlight BEFORE any water has taken the red out of it, which is why it is so much warmer
     * than anything in group 3. @see WaterRenderer.SUN_COLOUR_R
     */
    const val SUN_COLOUR_R = 1.00f

    /** @see SUN_COLOUR_R */
    const val SUN_COLOUR_G = 0.44f

    /** @see SUN_COLOUR_R */
    const val SUN_COLOUR_B = 0.16f

    /** Which way the sun sits, -1 for the left. @see WaterRenderer.SUN_BEARING */
    const val SUN_BEARING = -1f

    // =============================================================================================
    // 3. THE WATER'S OWN COLOUR — the albedo of the water you swim through.
    // =============================================================================================
    //
    // Five anchors, one per Zone: Shallows, Kelp, Twilight, Trench, Abyss. THIS IS WHERE THE DEPTH
    // DARKENING ACTUALLY LIVES — the light map barely ramps below the Kelp, so what makes 140 m
    // darker than 40 m is these three tables and nothing else.
    //
    // They must DESCEND, and the Shallows must stay more than 10x the Abyss in luminance
    // (measured 11.98x — linear-light, at the zone midpoints, with the reflectance floor applied,
    // which is what `DiveRendererTest` asserts). That ratio is §11's art direction written down;
    // lifting the deep end is the one change here that needs a design amendment rather than a
    // tweak. Do not restate the number anywhere else: this comment read 11.8x while
    // DiveRenderer's read 11.98x for the same table, and only one of them was ever measured.
    //
    // @see DiveRenderer.zoneRedAt for the reflectance-floor correction applied to blue.

    /** Water albedo, red, Shallows to Abyss. @see DiveRenderer */
    val ZONE_RED = floatArrayOf(0.10f, 0.06f, 0.0409f, 0.030f, 0.0206f)

    /** Water albedo, green — the channel that decides whether the deep reads as lit at all. */
    val ZONE_GREEN = floatArrayOf(0.34f, 0.22f, 0.1636f, 0.120f, 0.0771f)

    /** Water albedo, blue. Kept highest because deep water absorbs red first. */
    val ZONE_BLUE = floatArrayOf(0.52f, 0.36f, 0.30f, 0.24f, 0.18f)

    // =============================================================================================
    // 4. THE DAYLIGHT — how much light reaches each depth, and the water's own glow.
    // =============================================================================================
    //
    // THESE TABLES MEAN ONE THING: HOW MUCH DAYLIGHT IS LEFT HERE. They are not "the ambient
    // light" — the floor below is added afterwards, on purpose, so that the two quantities DERIVED
    // from these tables keep reading raw daylight.
    //
    // What is derived: the torch's depth ramp ([TORCH_SURFACE_IRRADIANCE] and
    // [TORCH_DARKNESS_GAIN] scale on `1 - daylight(d)/daylight(0)`). The Abyss entry being exactly
    // 0 is a guard rail — raise it and the beam DIMS at 140 m, precisely where the design makes it
    // the only light. `DiveLightingTest` is the only thing that catches that now.
    //
    // @see DiveLighting.daylightByZone

    /** Daylight reaching each zone, red. Shallows to Abyss. Abyss must stay 0. */
    val AMBIENT_RED = floatArrayOf(0.34f, 0.16f, 0.06f, 0.015f, 0.0f)

    /** Daylight, green — Rec.709 weights this 0.7152, so it is what the eye measures. Abyss must stay 0. */
    val AMBIENT_GREEN = floatArrayOf(0.52f, 0.30f, 0.14f, 0.045f, 0.0f)

    /** Daylight, blue. @see AMBIENT_RED */
    val AMBIENT_BLUE = floatArrayOf(0.68f, 0.44f, 0.24f, 0.09f, 0.003f)

    /**
     * THE WATER'S OWN GLOW — a floor applied with `max()` AFTER the blend above, so it lights
     * objects in the deep without giving the deep any daylight.
     *
     * Must stay blue > green > red, and blue above twice red, or it reads as grey fog in front of
     * the scene instead of as water (`DiveLightingTest`).
     *
     * Raising this lifts the UNLIT water more than the torch-lit water, so it flattens the beam's
     * contrast. If you want "brighter overall" without that side effect, use [GRADE_EXPOSURE].
     *
     * @see DiveLighting.ambientRedAt
     */
    const val AMBIENT_FLOOR_RED = 0.16f

    /** @see AMBIENT_FLOOR_RED */
    const val AMBIENT_FLOOR_GREEN = 0.30f

    /** @see AMBIENT_FLOOR_RED */
    const val AMBIENT_FLOOR_BLUE = 0.44f

    // =============================================================================================
    // 5. THE TORCH — the diver's light, and the deep's primary light source by design.
    // =============================================================================================

    /** Torch colour, a cold lamp-blue. Must stay clearly cooler than the pearls' warm amber. @see DiveLighting.diverLight */
    const val TORCH_RED = 0.6f

    /** @see TORCH_RED */
    const val TORCH_GREEN = 0.85f

    /** @see TORCH_RED */
    const val TORCH_BLUE = 1f

    /** Irradiance at one metre at the SURFACE, where the torch matters least. @see DiveLighting.torchIrradianceByZone */
    const val TORCH_SURFACE_IRRADIANCE = 6f

    /** How much stronger the torch gets where the daylight has gone: `x(1 + this)` at zero daylight. */
    const val TORCH_DARKNESS_GAIN = 2f

    /** How far the beam is at FULL brightness before `1/d²` takes over, in metres. @see DiveLighting.TORCH_REACH_METRES */
    const val TORCH_REACH_METRES = 24f

    /**
     * The emitter quad's size — what the source LOOKS like, as opposed to how far it reaches.
     *
     * **THIS IS THE ONLY DIAL THAT HIDES THE SOURCE, and it costs nothing.** GI draws a light's own
     * body: the emitter is a quad rasterised into `gi_local_scene`, and a probe inside it hits on
     * the first march step and gathers its full radiance, so you see the quad. At 1.2 m that was a
     * disc floating in front of the diver's mask — the owner: *"it is currently drawn as a circle
     * in front of the diver. I only want the actual lightbeam."* At 0.45 m it is a bright point at
     * the mask and the cone is all that is left.
     *
     * The beam does not change, and that is structural rather than lucky:
     * `DiveLighting.intensityFor` derives the intensity from an irradiance and DIVIDES by the
     * size, so shrinking the quad raises its radiance by exactly the factor that keeps the
     * delivered light identical. Captured as a matched pair at `EPT_DEPTH=60` with all three phase
     * pins, same window position: the disc is gone and the whole-frame mean moves 30.12 -> 30.37
     * out of 255.
     *
     * THE FLOOR IS 0.142 m on a 1080p booth panel — `scene.vert:86-88` enlarges any quad below
     * `1500 / (lightTexRes.y * camScale)`, and shrinking past it stops shrinking the disc while
     * still raising the radiance. 0.45 m keeps three times that margin.
     *
     * THE ONE THING THAT DOES *NOT* WORK, so nobody spends the afternoon on it again:
     * `GlobalIlluminationSystem.sourceIntensity` is the uniform that literally scales a light's own
     * body (`final.frag:35-42`, `light *= sourceIntensity` where a fragment is both occluder and
     * light source; the engine default is 1). Setting it to 0 does remove the disc — and replaces
     * it with a hard-edged BLACK one, because inside the emitter the fragment then gets only the
     * ambient while every pixel around it in the beam keeps its gathered light. Captured, and the
     * hole is worse than the disc. It is also global, so the same run turned every pearl's warm
     * core into a grey ring.
     *
     * Nor does the other half of that branch help. `LightEmitter`'s §2 records the trick of capping
     * an emitter's ALPHA at 0.75 — drawn and seeded (both thresholds are 0.5) but under
     * `isOccluder`'s 0.8 — which is how the god rays escaped this same code path. It goes the wrong
     * way here: skipping the branch leaves the gathered light UNSCALED, and inside a source that is
     * the source's own radiance, so the disc gets brighter rather than dimmer.
     *
     * @see DiveLighting.DIVER_LIGHT_SIZE_METRES
     * @see LightEmitter for the same three shader lines, read for the opposite purpose
     */
    const val TORCH_SIZE_METRES = 0.45f

    /**
     * How far along the diver's body the torch sits, as a fraction of his height.
     *
     * **It is bounded against [TORCH_SIZE_METRES] and moves with it.** The emitter must clear the
     * mask — a small emitter is a bright one ([DiveLighting.intensityFor] divides by the size), and
     * one overlapping the sprite blows his face out — while staying nearer his head than his head
     * is long, or the light stops reading as his. At 0.45 m that window is 0.5198 .. 0.6734.
     *
     * This was 0.40 with a 1.2 m emitter, on the opposite rule. @see DiveLighting.TORCH_FORWARD_FRACTION
     */
    const val TORCH_FORWARD_FRACTION = 0.55f

    // =============================================================================================
    // 6. THE PEARLS — what the game is about, and the anglerfish's lure copies them exactly.
    // =============================================================================================

    /** A pearl's drawn body colour, a warm gold. @see DiveRenderer.pearlColor */
    const val PEARL_RED = 1f

    /** @see PEARL_RED */
    const val PEARL_GREEN = 0.78f

    /** @see PEARL_RED */
    const val PEARL_BLUE = 0.35f

    /** The colour a pearl EMITS, slightly warmer than its body. @see DiveLighting.pearlLight */
    const val PEARL_LIGHT_RED = 1f

    /** @see PEARL_LIGHT_RED */
    const val PEARL_LIGHT_GREEN = 0.82f

    /** @see PEARL_LIGHT_RED */
    const val PEARL_LIGHT_BLUE = 0.45f

    /**
     * How hard a pearl glows, as a fraction of the torch. NO DEPTH TERM, deliberately — the owner
     * removed it so the diver has to find pearls with the flashlight (spec §17, 2026-08-12).
     * Raising this back toward 1 undoes that decision. @see DiveLighting.PEARL_FRACTION_OF_TORCH
     */
    const val PEARL_FRACTION_OF_TORCH = 0.01f

    /** The pearl emitter's quad size. Small on purpose: a light cannot avoid lighting its own body. @see DiveLighting.PEARL_LIGHT_SIZE_METRES */
    const val PEARL_LIGHT_SIZE_METRES = 0.5f

    /** A pearl's full-brightness radius — its own emitter's radius, so its light falls off from its own surface. */
    const val PEARL_REACH_METRES = 0.25f

    // =============================================================================================
    // 7. THE MARINE SNOW — the drifting motes.
    // =============================================================================================
    //
    // The hue must stay far from the pearls' gold. That is not taste: the anglerfish's lure renders
    // identically to a pearl and the only tell is motion, so a field of glowing points that looked
    // like pearls would stop the trap working (outstanding-work §1.1). `MotesTest` asserts the
    // separation against the pearl constants above rather than against a copy of them.

    /** Mote colour, an ice blue — about 171 degrees of hue from the pearls. @see Motes.MOTE_RED */
    const val MOTE_RED = 0.25f

    /** @see MOTE_RED */
    const val MOTE_GREEN = 0.62f

    /** @see MOTE_RED */
    const val MOTE_BLUE = 1f

    /** How opaque a mote is drawn. Also sets the hard rim step left by the engine's alpha discard. @see MoteSprite */
    const val MOTE_ALPHA = 0.25f

    /** Grid pitch in metres — the density dial. Larger is sparser. @see Motes.CELL_METRES */
    const val MOTE_CELL_METRES = 6f

    /** Smallest mote diameter, metres. @see Motes.MIN_SIZE_METRES */
    const val MOTE_MIN_SIZE_METRES = 0.35f

    /** Largest mote diameter, metres. Kept under a pearl's so the two never confuse. */
    const val MOTE_MAX_SIZE_METRES = 0.85f

    // A mote is not a light. `MOTE_GLOW_IN`, `MOTE_FRACTION_OF_PEARL` and `MOTE_REACH_METRES` were
    // here between 2026-08-13 and 2026-08-17, while a quarter of the field were GI emitters; the
    // owner removed them on sight of what an emitting mote looks like beside a plain one. There is
    // no dial to bring them back with — see `Motes`' "THE GLOWING SUBSET".

    /** How far a mote drifts sideways from its cell anchor, metres. @see Motes.DRIFT_X_METRES */
    const val MOTE_DRIFT_X_METRES = 2.2f

    /** How far a mote bobs vertically, metres. @see Motes.DRIFT_Y_METRES */
    const val MOTE_DRIFT_Y_METRES = 1.4f

    /** Metres below the waterline the field takes to fade in. @see Motes.SURFACE_FADE_METRES */
    const val MOTE_SURFACE_FADE_METRES = 6f

    // =============================================================================================
    // 8. THE GRADE — one pass over the whole underwater frame, after everything else.
    // =============================================================================================

    /**
     * **THE OVERALL BRIGHTNESS DIAL. If the game is simply too dark or too bright, this is the
     * one to move, and it is the only one that changes no relationship inside the frame.**
     *
     * A linear gain applied before the tone mapper. Measured: at 1.1 a pinned 140 m frame had
     * 16.7% of its pixels at or under 2/255 — under a booth panel's black point; at 2.6 that is
     * 0.3%, and the clipped share is unchanged, because UNCHARTED2's shoulder absorbs the gain.
     *
     * Does NOT touch the sky, which is on its own surface — see group 1.
     *
     * @see DiveLighting.GRADE_EXPOSURE for the full before/after table at three depths.
     */
    const val GRADE_EXPOSURE = 2.6f

    /**
     * MUST NOT GO ABOVE 1, and this is the one number in this file that is a trap rather than a
     * taste. `color_grading.frag` applies contrast BEFORE the tone mapper, and near black anything
     * above 1 is a SUBTRACTION of a constant — so the deep clamps to true black whichever curve
     * follows. Probed at 1.3: open water measured exactly 0.000 at 140 m.
     *
     * @see DiveLighting.GRADE_CONTRAST
     */
    const val GRADE_CONTRAST = 1.0f

    /** How much the frame darkens toward its corners. Purely taste. @see DiveLighting.setup */
    const val GRADE_VIGNETTE = 0.25f
}
