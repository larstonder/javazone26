package render

import dive.DiveSim
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.postprocessing.effects.BloomEffect
import no.njoh.pulseengine.core.graphics.postprocessing.effects.ColorGradingEffect
import no.njoh.pulseengine.core.graphics.postprocessing.effects.ColorGradingEffect.ToneMapper.ACES
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.modules.lighting.global.GiSceneRenderer
import no.njoh.pulseengine.modules.lighting.global.GlobalIlluminationSystem
import no.njoh.pulseengine.modules.scene.systems.EntityRendererImpl
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pearls ARE the light. In the Abyss they are the only light source, which is why lighting
 * is part of the core loop rather than a polish pass — you cannot judge whether the deep
 * feels right without it.
 *
 * REWORKED AWAY FROM POOLED `Lamp` SCENE ENTITIES. The previous version repositioned a pool
 * of `Lamp` entities onto pearls every frame from `sync()`, called during `onUpdate()` —
 * BEFORE `DiveCamera.update()` ran that same tick. `DiveRenderer` (called from `onRender()`,
 * AFTER the camera had already advanced) then drew everything using the NEW camera depth.
 * That one-tick gap between when a lamp's position was computed and when the matching
 * pixels were drawn is exactly the "slight drift... when the camera moves" playtest note —
 * worse the faster the camera was easing. It also hardcoded the diver lamp's x to screen
 * centre, ignoring `sim.x` entirely, which is why the light never followed the diver
 * horizontally.
 *
 * `GiSceneRenderer.drawLight()` is immediate-mode: callable directly on the GI "local scene"
 * surface from anywhere, with no scene entity required (confirmed against the engine's own
 * `Torch.onRenderLightSource`, which does exactly this). There is nothing left to pool: no
 * entities, no per-frame entity-system update pass, no repositioning step that can fall out of
 * sync.
 *
 * STILL CALLED FROM onRender, but the alignment guarantee is now STRUCTURAL rather than
 * procedural. GI's local scene surface is created with `camera = engine.gfx.mainCamera`
 * (GlobalIlluminationSystem.kt:78) — the same object mainSurface uses — and every matrix is
 * built once per frame in gfx.initFrame (GraphicsImpl.kt:111-113) before any game code runs. So
 * a light drawn at world (x, depth) and a square drawn at world (x, depth) go through the
 * identical viewProjectionMatrix and CANNOT drift, whichever callback issued them. What
 * onRender still buys is ordering: GiSceneRenderer's batch must be filled before gfx.drawFrame,
 * and onRender is where the rest of the drawing lives. Do not move this back to onUpdate.
 *
 * The drift risk has MOVED TO THE HUD, which is on its own screen-space camera and must
 * therefore transform the diver's position by hand. See the diver anchor in
 * `EnPustTil.onRender`, which takes it from `mainCamera.worldPosToScreenPos` — the same matrix,
 * on the same frame — rather than from `DiveCamera.depth`.
 *
 * ONE CONE, RAMPED BY SPEED. The diver's light used to switch between a 50-degree beam while
 * moving and `coneAngle = 360` while stopped. The second of those is not "a very wide torch":
 * 360 trips a guard in GI's own shader that disables cone attenuation altogether, so a
 * hovering diver emitted full radiance in every direction and read as an intense symmetric
 * bloom blob rather than as someone holding a flashlight. [drawDiverBeam] now keeps the beam
 * directional at all speeds and ramps its width and brightness instead of branching. The
 * per-parameter semantics of `drawLight`, read off the shader source rather than assumed, are
 * documented on [coneMaskPeak] — they are unintuitive enough that the old 25x-versus-1x
 * pairing looked reasonable while being roughly a factor of five out.
 *
 * SMALL, ROUND EMITTERS — and the SMALL is the load-bearing half. A light's emitter quad is a
 * REGION that rasterises into the scene, not an abstract point: whatever its shape, at body size
 * you see the emitter instead of the light. The diver's torch was drawn from a quad the height of
 * the diver, and read as a hard-edged rectangle wheeling around him; making it round only turned
 * it into a hard-edged circle. It is now 1.2 m — see [DIVER_LIGHT_SIZE_METRES], which has the
 * whole diagnosis, and [TORCH_SIZE_COMPENSATION], which is how the cast survives the shrink.
 *
 * All three `drawLight` calls below then pass [LightEmitter.emitter] rather than `Texture.BLANK`,
 * because `drawLight` takes the emitter's shape from the texture and BLANK means "no texture",
 * i.e. the WHOLE QUAD emits, corners included. That is what put a square box around every pearl.
 * `LightEmitter`'s class doc has the shader reading behind it, why the falloff has to live in
 * ALPHA rather than in colour, and what the shape change measured at.
 */
object DiveLighting
{
    private val pearlLight = Color(1f, 0.82f, 0.45f)
    private val diverLight = Color(0.6f, 0.85f, 1f)

    private const val PEARL_LIGHT_SIZE_METRES = 3f

    /**
     * The diver's torch: the size of the QUAD that emits it, in metres.
     *
     * ## A TORCH HEAD, NOT A BODY — and the history matters, because this has now been wrong in
     * both directions
     *
     * It was a literal `3f`, which happened to equal the old `Framing.DIVER_SIZE_METRES`. When the
     * diver doubled to 6 m for the sprite art the two silently parted company, and the fix was to
     * express this AS the diver's height so a future resize could not separate them again. That
     * reasoning is right for a lamp centred on a body and wrong for a torch, and the resize it
     * introduced is what made the defect visible: **a light's emitter quad is a REGION that
     * rasterises into the scene**, and at body size that region is far too big to be mistaken for a
     * source. The player's words were that the diver had "a hard-edged rectangle" around him;
     * making it round moved the defect rather than fixing it — a hard-edged circle instead. The
     * shape was never the problem. The SIZE was.
     *
     * The diagnostic sat in the same frame the whole time: pearls emit through this same
     * `drawLight` call and read as clean glows, because their emitter is 3 m for a 1.2 m body
     * rather than 6 m for a 6 m one. The engine's own reference does the same thing — `Torch`
     * passes its FLAME sprite, sized like a flame, and casts light across a room.
     *
     * So 1.2 m: a torch head, decoupled from the swimmer holding it and stated as its own number
     * because it is not a fraction of anything. It is above the floor `scene.vert:87` puts on a
     * light quad (`pixelSizeInWorld * 1500 / camScale`, which works out at ~0.11 m for our camera
     * at any resolution), and 1.2 m is 9 texels across in the quarter-scale local SDF — coarse, but
     * enough for the JFA to build a shape from.
     *
     * ## Where the reach comes from now: [TORCH_SIZE_COMPENSATION]
     *
     * A CORRECTION TO WHAT THIS COMMENT USED TO CLAIM: `upscaleSmallSources` does NOT apply here.
     * `GlobalIlluminationSystem` sets it on the GI_GLOBAL_SCENE renderer only
     * (GlobalIlluminationSystem.kt:203-207); the GI_LOCAL_SCENE renderer, which is what feeds the
     * SDF the near-field cascades march against, is left at the field default of `false`. Nothing
     * enlarges a small light for us, and nothing divides its intensity either.
     */
    internal const val DIVER_LIGHT_SIZE_METRES = 1.2f

    /**
     * The emitter size the beam's balance was tuned at, in metres. `d92c723` set
     * [BEAM_INTENSITY_MULT] and [STATIONARY_PEAK_RADIANCE] against a 3 m quad; `fd036f7` then
     * doubled the quad to 6 m as a side effect of resizing the diver, which doubled the beam's
     * reach without anyone choosing to. This is the number that balance belongs to.
     */
    internal const val TORCH_BALANCE_SIZE_METRES = 3f

    /**
     * The nominal intensity multiplier that makes an emitter of [emitterSizeMetres] cast exactly
     * what the [TORCH_BALANCE_SIZE_METRES] one did. At our 1.2 m that is 2.5.
     *
     * IT IS A RATIO OF SIZES BECAUSE THE FALLOFF IS ANGULAR, NOT RADIAL. We pass `radius = 0`,
     * which in `radiance_cascades.frag` is not "unbounded radius" but "skip the distance term
     * altogether" — so what a probe receives from a light is its radiance times the FRACTION OF
     * ITS RAYS THAT HIT IT, and that fraction is proportional to the light's angular size, i.e.
     * to `size / distance`. Irradiance is therefore linear in the quad's size at every distance,
     * and dividing the quad by 2.5 while multiplying the intensity by 2.5 leaves the cast
     * identical everywhere while shrinking the visible source to a fifth of its area.
     *
     * A FUNCTION rather than a bare constant so that the RELATIONSHIP is what is written down and
     * what is tested: the failure mode this is guarding against is precisely someone changing the
     * emitter's size and leaving a hand-typed multiplier behind, which is how `fd036f7` came to
     * double the beam without anyone noticing.
     *
     * It also restores the `d92c723` balance exactly rather than approximately, and the
     * hovering-versus-moving ramp survives it untouched by construction: [beamIntensity] is
     * expressed entirely in MULTIPLES of the base intensity it is handed, so scaling that base
     * moves both ends of the ramp together and cannot change their ratio. `DiveLightingTest`'s
     * ramp cases assert against `base` for that reason and are unaffected.
     */
    internal fun torchIntensityFor(emitterSizeMetres: Float): Float =
        TORCH_BALANCE_SIZE_METRES / emitterSizeMetres

    private val TORCH_SIZE_COMPENSATION = torchIntensityFor(DIVER_LIGHT_SIZE_METRES)

    /**
     * How far ahead of the diver's CENTRE the torch is carried, as a fraction of
     * [Framing.DIVER_HEIGHT_METRES]. At the current 9 m diver that is 3.6 m.
     *
     * ## Why there is an offset at all
     *
     * `sim.x, sim.depth` is the MIDDLE of the body — it is the centre-origin position
     * `DiveRenderer.drawDiver` hands `drawTexture` — and the torch used to emit from exactly
     * that point. What you saw was a diver glowing from the chest while his head, mask and hands
     * were at the leading end of the sprite: a man lit from inside rather than a man carrying a
     * light. Owner's words on a capture of a downward swim: "shouldn't the light be emitted from
     * in front of the character?"
     *
     * ## Why a FRACTION and not 3.6 m
     *
     * `Framing.DIVER_HEIGHT_METRES` has been changed twice already (3 -> 6 -> 9) and a hand-typed
     * metre value would silently stop meaning "his head" at the next change. That is not a
     * hypothetical: it is exactly how [DIVER_LIGHT_SIZE_METRES] came to be half the body in
     * `fd036f7`. The offset is a place ON the diver, so it is written as one.
     *
     * ## Why 0.40 specifically
     *
     * Measured off the committed sheet rather than reasoned about. A cell is 384 texels tall for
     * [Framing.DIVER_HEIGHT_METRES] of world height, so one texel is 0.0234 m, and (rows where
     * alpha > 16, frame 0):
     *
     *     snorkel tip / crown   row 2      0.495 of the height above centre
     *     mask                  rows 8-25  0.44
     *     chin                  row 40     0.40
     *     shoulders             row 55     0.36
     *     HANDS                 rows 150-195   0.06 — i.e. essentially AT the centre
     *
     * So "the hands" is not available as an anchor: this diver swims with his arms at his sides,
     * and his hands are at his hips. The head is the only leading end there is, and 0.40 puts the
     * emitter's centre on the chin/mask — a mask light, which is what a free-diver would actually
     * be wearing.
     *
     * 0.40 is also the largest round value that keeps the emitter WHOLLY INSIDE the silhouette:
     * the quad is [DIVER_LIGHT_SIZE_METRES] across, i.e. 0.133 of the height, so its leading edge
     * sits at 0.40 + 0.067 = 0.467 against a crown at 0.495. That is the property worth
     * preserving if anyone re-tunes this — a disc that pokes out past the head reads as a lamp
     * floating in front of him rather than as one he is wearing, and it would do so at every
     * heading at once.
     *
     * ## The hovering case is safe BY CONSTRUCTION, not by luck
     *
     * The offset direction is [beamAngleDeg], the same single heading `DiverSprite.bodyAngleFor`
     * poses the BODY from, so the emitter lands on the sprite's head at every heading — including
     * the held heading below [STATIONARY_SPEED_THRESHOLD], where the diver keeps the pose he
     * coasted to a stop in. There is no state in which the body faces one way and the light
     * leaves from another, so the light cannot come loose from him. Deriving the offset from
     * `sim.vx/vy` instead would break exactly that: a hovering diver has no velocity to derive a
     * direction from, and the offset would collapse to zero (or to `atan2` noise) while his body
     * stayed posed.
     *
     * ## It applies to the TORCH ONLY
     *
     * Pearls and the anglerfish's lure are omnidirectional and have no facing — they pass
     * `coneAngle = 360` and `angle = 0` — so there is no "in front" for them to be offset along.
     * They stay on their own centres.
     */
    internal const val TORCH_FORWARD_FRACTION = 0.40f

    /** The torch's forward offset from the diver's centre, in metres. */
    internal fun torchOffsetMetres(): Float = Framing.DIVER_HEIGHT_METRES * TORCH_FORWARD_FRACTION

    /**
     * Where the torch emits from, given the diver's centre and the smoothed heading.
     *
     * TWO FUNCTIONS RATHER THAN ONE RETURNING A PAIR, because a `Pair<Float, Float>` in the
     * render path is a per-frame allocation and this project does not do those (see CLAUDE.md).
     * Two `cos`/`sin` calls a frame is not a cost worth a boxed tuple.
     *
     * THE Y TERM IS NEGATED, and that is the same flip [updateAim] applies in the other
     * direction. [beamAngleDeg] lives in `GiSceneRenderer`'s convention — counter-clockwise from
     * +x with +y running UP the screen (see `AimAngle`'s class doc) — while world y IS depth and
     * runs DOWN. `updateAim` converts a world velocity into that convention by negating `sim.vy`;
     * this converts a heading in that convention back into a world displacement by negating the
     * `sin`. The two negations are inverses of each other, so a diver swimming straight down
     * (`vy > 0`, heading -90) gets `depth + offset`, i.e. the torch DEEPER than his centre, which
     * is the direction he is going.
     */
    internal fun torchX(diverX: Float, headingDegrees: Float): Float =
        diverX + torchOffsetX(headingDegrees, unitsPerMetre = 1f)

    /** @see torchX — world y is depth and runs DOWN, hence the negated `sin`. */
    internal fun torchDepth(diverDepth: Float, headingDegrees: Float): Float =
        diverDepth + torchOffsetY(headingDegrees, unitsPerMetre = 1f)

    /**
     * The torch's displacement from the diver's centre, expressed in whatever unit
     * [unitsPerMetre] converts a metre into — 1 for world metres, `pixelsPerMetre` for the HUD's
     * screen pixels.
     *
     * ## Why the scale is a parameter rather than two derivations
     *
     * The torch is now needed in TWO spaces. `DiveRenderer` needs it in world metres, both to
     * emit the light ([drawDiverBeam]) and to tell the iridescence shader where the light is;
     * `Hud` needs the same point in screen pixels, because the air ring's bubbles are drawn on
     * the HUD surface and are lit by the same torch (see `shaders/iridescence.frag` for why the
     * bearing to the torch is what drives the effect at all).
     *
     * Two spaces, one point. Deriving it twice — `sim.x + offset * cos(...)` on one side and
     * `diverScreenX + offset * ppm * cos(...)` on the other — is exactly the duplicated
     * derivation that produced the shipped world-offset-from-HUD bug (`6ea1f53`), and here it
     * would show as the pearls' colour bands and the ring's shimmer disagreeing about which way
     * the diver is facing. So the offset is computed once and scaled, and both `torchX`/
     * `torchDepth` above are now thin wrappers over the same two functions the HUD calls with a
     * different scale. There is one trigonometric expression per axis in this file.
     *
     * THE Y NEGATION LIVES HERE, once. [beamAngleDeg] is in `GiSceneRenderer`'s convention
     * (counter-clockwise from +x with +y UP), while world y IS depth and runs DOWN — and screen
     * y on the HUD surface runs down too, for the same reason. So the identical negation is
     * correct in both spaces, which is the second half of why one function can serve them.
     */
    internal fun torchOffsetX(headingDegrees: Float, unitsPerMetre: Float): Float =
        torchOffsetMetres() * unitsPerMetre * cos(Math.toRadians(headingDegrees.toDouble())).toFloat()

    /** @see torchOffsetX — y runs DOWN in both spaces, hence the negated `sin`. */
    internal fun torchOffsetY(headingDegrees: Float, unitsPerMetre: Float): Float =
        -torchOffsetMetres() * unitsPerMetre * sin(Math.toRadians(headingDegrees.toDouble())).toFloat()

    /**
     * How many metres of water around an occluder GI's ambient occlusion darkens. A plain
     * artistic quantity in world units — see [setup], where it is converted into the engine's
     * `aoRadius`, why it is a metre value and must never become a pixel count, and what it was
     * measured to actually do (which, at this value, is nothing: the scene has no occluders).
     */
    private const val AO_RADIUS_METRES = 4f

    // Continuous ambient (see DepthBlend) replaces the old flat per-zone Color lookup — a
    // hard-edged mapOf(Zone, Color) is exactly the "sharp jump" the zone bands also had.
    // Same anchor values as before (SHALLOWS reads without a lamp nearby, ABYSS ambient is
    // effectively zero), indexed by Zone.ordinal, blended by DepthBlend.blend.
    private val ambientRed   = floatArrayOf(0.34f, 0.16f, 0.06f, 0.015f, 0.0f)
    private val ambientGreen = floatArrayOf(0.52f, 0.30f, 0.14f, 0.045f, 0.0f)
    private val ambientBlue  = floatArrayOf(0.68f, 0.44f, 0.24f, 0.09f,  0.003f)

    // Deeper zones are darker, so pearls must shine harder to stay legible — same anchor
    // values the old zoneIntensityFor used, now blended continuously instead of switching
    // the instant a zone boundary is crossed.
    private val pearlIntensityByZone = floatArrayOf(0.6f, 1.0f, 1.8f, 2.6f, 4.0f)
    private val diverIntensityByZone = floatArrayOf(2.0f, 2.0f, 2.0f, 2.0f, 1.2f)

    /**
     * Speed (m/s) at which the beam reaches full focus. Also the cutoff below which the
     * direction of travel is `atan2` noise rather than intent, so the aim stops tracking —
     * see [drawDiverBeam].
     *
     * Sanity-checked against the movement model: an unladen diver with no input settles at
     * exactly zero (Buoyancy.verticalSpeed is neutral when mass is zero), so "hovering" is a
     * real, common state and not a rounding artefact. Any held direction converges on at
     * least `LATERAL_THRUST / dragCoefficient` — 6 m/s empty, still 3 m/s under a 200-mass
     * haul — so 1.5 m/s is crossed almost the instant the player commits to a direction. The
     * wide end of the ramp is therefore the "hovering, deciding where to go" look, and the
     * narrow end is essentially all of actual swimming.
     */
    private const val STATIONARY_SPEED_THRESHOLD = 1.5f

    private const val FULL_CIRCLE_DEGREES = 360f
    private const val WIDE_GLOW_CONE_ANGLE = FULL_CIRCLE_DEGREES

    // Cone width at the two ends of the focus ramp. Swimming narrows the beam to a torch;
    // hovering opens it to a pool. STATIONARY_CONE_ANGLE is deliberately at-or-below the
    // 180-degree hemisphere landmark documented on coneMaskPeak, so that even at its widest
    // the light never reaches behind the diver — "no light behind you" is precisely what
    // stops this reading as the symmetric halo it used to be.
    private const val BEAM_CONE_ANGLE = 50f
    private const val STATIONARY_CONE_ANGLE = 150f

    // Nominal intensity of the focused beam, unchanged from the version that was signed off
    // in playtest. It is only ever used via BEAM_PEAK_RADIANCE below; see coneMaskPeak for
    // why a bare multiplier like this cannot be compared across two different cone widths.
    private const val BEAM_INTENSITY_MULT = 25f

    // Guard so a degenerate cone width can never divide by zero in beamIntensity.
    private const val MIN_CONE_MASK_PEAK = 1e-4f

    // Target PEAK ON-AXIS RADIANCE at each end of the ramp, in multiples of the diver's base
    // intensity. This is the quantity that actually reaches the screen — see coneMaskPeak —
    // and expressing the two ends in the same unit is what makes them comparable at all.
    //
    // The focused end is derived from the old constants rather than retyped, so the moving
    // beam is reproduced exactly (25 * 0.0937 = 2.342 x base) by construction rather than by
    // a hand-copied decimal that could drift.
    private val BEAM_PEAK_RADIANCE = BEAM_INTENSITY_MULT * coneMaskPeak(BEAM_CONE_ANGLE)

    // 1.0 is exactly what the old 360-degree fallback delivered: coneAngle 360 skips the
    // shader's attenuation entirely, so its radiance was base * 1.0 in every direction.
    // Holding the hovering peak there means the fix cannot make anything on screen brighter
    // than it already is — it only ever removes light, from the sides and from behind. That
    // is the conservative reading of a "too intense" complaint, and it keeps the diver
    // exactly as legible straight ahead as players are used to.
    private const val STATIONARY_PEAK_RADIANCE = 1f

    /** Aim-angle smoothing rate, per second — same `1 - e^(-k*dt)` family as DiveCamera. */
    private const val AIM_SMOOTHING_RATE = 6f

    private var gi: GlobalIlluminationSystem? = null
    private val ambientColor = Color(0f, 0f, 0f, 1f)

    /**
     * THE RESTING HEADING, CHANGED FROM -90 WHEN THE DIVER'S BODY STARTED SHARING IT.
     *
     * It used to be -90 — "facing down" in GiSceneRenderer's Y-flipped cone-direction convention
     * (see AimAngle's class doc) — chosen as a sensible default for a TORCH before the diver first
     * moves. That stopped being a free choice the moment `DiverSprite.bodyAngleFor` started posing
     * the sprite from the same number: -90 draws the diver UPSIDE DOWN, and the state it shows in
     * is the attract screen and the first instant of a run, i.e. a diver hovering at the surface
     * standing on his head. [DiverSprite.REST_HEADING_DEGREES] is by definition the heading at
     * which the sheet's own art is upright, so it is the only value that can be right here.
     *
     * The cost is that an untouched torch points UP rather than down. It is paid only until the
     * first stick input, which snaps rather than eases (`beamInitialized` below), and at depth 0
     * in the lit Shallows where the beam contributes least. A diver standing on his head is a much
     * louder wrong than a torch shining at the sky.
     */
    private var beamAngleDeg = DiverSprite.REST_HEADING_DEGREES
    private var beamInitialized = false

    /**
     * WHERE THE DIVER IS POINTING — one value, owned here, read by two renderers.
     *
     * The diver's BODY is drawn rotated to this heading as well ([DiverSprite.bodyAngleFor], via
     * `DiveRenderer.drawDiver`), so a diver swimming down-right is drawn facing down-right instead
     * of staying bolt upright while only his torch turns. Exposed rather than re-derived because
     * two renderers computing `atan2(sim.vy, sim.vx)` independently is exactly the shape of the
     * shipped world-offset-from-HUD bug (`6ea1f53`), and here the two would part company in every
     * frame the smoothing is mid-turn — the body would lag or lead the beam by a visible amount.
     *
     * There is nothing to keep in step, because there is only one number: [updateAim] integrates
     * it once per fixed tick and both draws read it in the same frame.
     *
     * It is also why the smoothing and the stationary hold below are inherited rather than
     * re-implemented: the body eases at the same [AIM_SMOOTHING_RATE], and it holds its last
     * heading below [STATIONARY_SPEED_THRESHOLD] instead of flicking back to a neutral pose every
     * time the player lets go of the stick.
     */
    val beamHeadingDegrees: Float get() = beamAngleDeg

    /**
     * Integrate the aim. Called once per fixed tick from `EnPustTil.onFixedUpdate`, inside the
     * same `RunLifecycle.simulationAdvances` gate as `DiveSim.tick`.
     *
     * MOVED OUT OF [drawDiverBeam], WHICH IS WHAT MAKES ONE HEADING POSSIBLE. It used to be
     * integrated inside the beam's own draw, from `onRender`'s delta time — and `DiveRenderer`
     * runs BEFORE `DiveLighting` in `onRender`, so a body reading the heading there would have
     * been reading the PREVIOUS frame's value while the beam used this one. Integrating on the
     * fixed tick puts the single write strictly before both reads, every frame, by construction.
     *
     * Nothing else changes: the easing is `1 - e^(-k*dt)`, which is frame-rate independent, so
     * sampling it at 60 Hz produces the same motion the render clock did — the same argument
     * `CameraRig` records for camera easing. And it still runs regardless of culling: the aim is
     * smoothed STATE, not a per-frame derivation, so freezing it while the diver is off screen
     * would snap the heading the frame he came back.
     */
    fun updateAim(sim: DiveSim, dt: Float)
    {
        val speed = hypot(sim.vx, sim.vy)
        if (speed < STATIONARY_SPEED_THRESHOLD) return  // hold the last heading — see drawDiverBeam

        // GiSceneRenderer's cone direction is Y-flipped relative to the world's y-DOWN
        // convention. Originally found empirically; now confirmed from the shader source —
        // scene.frag builds the cone direction as `vec2(cos(a), sin(a))` in a framebuffer
        // whose +y runs UP the screen, while world y (which IS depth) runs DOWN. Unchanged
        // by the migration to metres: the flip is between world-y-down and the framebuffer's
        // y-up, and a uniform positive scale plus a translation cannot alter it. See
        // AimAngle's class doc.
        val target = AimAngle.headingDegrees(sim.vx, -sim.vy)
        beamAngleDeg = if (beamInitialized) AimAngle.smooth(beamAngleDeg, target, dt, AIM_SMOOTHING_RATE) else target
        beamInitialized = true
    }

    fun setup(engine: PulseEngine)
    {
        // An EMPTY scene, deliberately and permanently. GlobalIlluminationSystem is a scene
        // SYSTEM, so it needs an active scene to live in and a RUNNING one for its onUpdate
        // to install the multiply effect on mainSurface (GlobalIlluminationSystem.kt:210-215)
        // — hence createEmptyAndSetActive here and engine.scene.start() at the end. It does
        // not need, and we do not have, a single scene ENTITY: pearls, vents, the anglerfish
        // and the diver are all generated from the daily seed and drawn immediately (see
        // DiveRenderer), and the lights are immediate-mode drawLight calls (see [render]).
        //
        // NO SCENE `Camera` ENTITY HERE, DELIBERATELY. THIS IS A FIXED BUG, NOT AN OVERSIGHT.
        //
        // A previous version of this method added a no.njoh.pulseengine.modules.scene
        // .entities.Camera with a comment asserting that GI "requires an active scene
        // camera". That assertion was simply false. GlobalIlluminationSystem reads
        // engine.gfx.mainCamera directly and hands it to its own surfaces
        // (GlobalIlluminationSystem.kt:78, 130, 142, 153, 163, 175, and again at :227-229 for
        // the world-ray pass); it never looks up a scene Camera entity. Grepping the whole
        // engine for `entities.Camera` returns nothing outside that entity's own file, and
        // the only early-out in the entire system is a missing EntityRenderer at :184, which
        // merely skips render-pass registration — every GI surface is already built by then,
        // so immediate-mode drawLight works regardless.
        //
        // What the entity DID do was rewrite the shared mainCamera every fixed tick
        // (Camera.onFixedUpdate, Camera.kt:85-103):
        //
        //     scale = min(mainSurface.config.width  / viewPortWidth,
        //                 mainSurface.config.height / viewPortHeight) * zoom     // :93
        //     origin.x   = surfaceWidth * xOrigin                                // :98
        //     position.x = surfaceWidth * xOrigin - x                            // :100
        //
        // with xOrigin/yOrigin pinned to 0, so origin and position were both zero and the
        // whole view matrix collapsed to a PURE UNIFORM SCALE ABOUT THE SCREEN'S TOP-LEFT
        // CORNER. viewPortWidth/Height were frozen at the window size seen during onCreate,
        // while mainSurface.config tracks the CURRENT framebuffer (SurfaceImpl.init:46-47,
        // called from GraphicsImpl.onWindowChanged). So that scale was 1 if and only if the
        // framebuffer was still exactly the size it was at onCreate, and anything else
        // multiplied the world surface — and NOT the HUD surface, which has its own camera —
        // by the ratio.
        //
        // MEASURED, not reasoned about: booting windowed at 1200x900 and then firing the
        // fullscreen toggle that init.pes binds to LEFT_ALT+ENTER (WindowImpl.updateScreenMode
        // -> createWindow -> a new framebuffer of 3440x1440) logged
        //     scale=(1.6,1.6) origin=(0,0) position=(0,0)
        // i.e. min(3440/1200, 1440/900) = 1.6, and put the diver's world square at 0.632 of
        // screen width while its own HUD-anchored air ring — computed from the SAME sim.x
        // through the SAME (then screen-space) transform — stayed at 0.395. That 0.40-vs-0.63
        // split is exactly the shipped "world offset from the HUD" report. The diver square
        // also came out 115 px instead of 72, the same 1.6x.
        //
        // WHO WRITES mainCamera NOW: [CameraRig], from EnPustTil.onFixedUpdate, and nothing
        // else — MainCameraOwnershipTest enforces that as exact set equality over the sources.
        // It writes all four parameters from scratch every tick against mainSurface.config, so
        // there is no frozen viewport left to go stale. World coordinates are METRES, and GI's
        // "local scene" surface is created with `camera = engine.gfx.mainCamera`
        // (GlobalIlluminationSystem.kt:78) — the SAME object mainSurface uses — so the
        // immediate-mode drawLight calls below and DiveRenderer's squares go through one
        // matrix, built once per frame in gfx.initFrame before any of our code runs. They
        // cannot disagree about where a metre is at any framebuffer size.
        //
        // Nothing may reintroduce a scene Camera entity here. It would be a SECOND writer of
        // that shared camera, which is the mechanism above, and it would fight CameraRig every
        // fixed tick.
        engine.scene.createEmptyAndSetActive("dive.scn")

        // EntityUpdater is GONE with the Camera entity, because it existed only to tick it.
        // All it ever does is dispatch onStart/onUpdate/onFixedUpdate to Initiable/Updatable
        // scene entities (EntityUpdater.kt in full), and this scene has none — the one entity
        // that ever existed was the Camera above. Its only other effect is syncing
        // engine.config.fixedTickRate to its own tickRate on start, which was already a no-op
        // here: EnPustTil.onCreate sets fixedTickRate = 60 BEFORE calling this method, so
        // EntityUpdater.onCreate copied 60 out and onStart wrote the same 60 back.
        //
        // EntityRendererImpl STAYS, even though it too draws nothing while the scene is empty
        // (buildRenderQueue finds no entity type lists, so every task goes straight back to
        // the pool — EntityRenderer.kt:88-105). It stays because GI's own onCreate does
        // `getSystemOfType<EntityRenderer>() ?: return` at GlobalIlluminationSystem.kt:184
        // before registering its five render passes: removing this system would silently
        // change GI's initialisation path for no gain beyond one no-op pass per frame, and it
        // is the seam any future GiLightSource or occluder entity would have to plug into.
        engine.scene.addSystem(EntityRendererImpl())

        val system = GlobalIlluminationSystem()
        system.lightTexScale = 0.25f
        system.localSceneTexScale = 0.25f
        // Default dithering (0.2, verified by decompiling GlobalIlluminationSystem's
        // <init>) is tuned for a light map close to native resolution. Ours is upscaled
        // from a quarter-res source (lightTexScale/localSceneTexScale above) onto an
        // enormous smooth vertical gradient (DiveRenderer.drawZoneBands / updateAmbient
        // below) — close to the worst case for visible banding, and worse the larger the
        // display. The reference (caesars-salads) sets 0.6 for the same reason.
        //
        // Tried to A/B this visually (0.2 vs 0.6, captured at several depths on this
        // Retina display) and could NOT confirm a difference by eye in the captures —
        // honestly reported rather than claimed: the observable background gradient in
        // the 8-bit PNG screenshots was already crushed to raw values of 0-3 out of 255
        // at every reachable depth, below where 8-bit quantization itself dominates
        // whatever dithering noise is or isn't doing (see visual-polish-report.md for
        // the actual pixel dumps). That is a limitation of judging this from a
        // screenshot, not evidence the setting does nothing on the real HDR framebuffer.
        // Kept 0.6, matching the reference: it costs nothing at runtime, and it is
        // GI's own documented remedy for exactly this "upscaled low-res light map over a
        // smooth gradient" scenario, tuned by the reference for the same lighting system.
        system.dithering = 0.6f

        // AO RADIUS, IN METRES. Set AFTER localSceneTexScale above, because it is expressed
        // against it and the two must not be able to silently disagree.
        //
        // `ao.frag:36` is `radius = aoRadius * camScale`, which reads like a zoom knob and is
        // not one. `ray` marches in SDF TEXELS (ao.frag:53-64 divides the step by
        // localSdfTexRes, and the SDF is fragCoord-based — sdf.frag:14,20), while camScale is
        // screen pixels per world unit. Texels per world unit is camScale * localSceneTexScale,
        // so camScale cancels and what is left is
        //
        //     radius_world = aoRadius / localSceneTexScale
        //
        // — no camera scale in it at all. The AO radius is therefore ALREADY scale-invariant in
        // world units. Do NOT "fix" that multiply, and do NOT divide by the camera scale here:
        // that would pin AO to a constant number of SCREEN PIXELS, which CLAUDE.md forbids
        // outright, and which would make the world radius depend on the booth panel's height
        // (4 m at h=1800, 8 m at h=900) for a display whose size we do not know in advance.
        //
        // What the world-coordinate migration changed is what a world unit MEANS: one pixel
        // before, one metre after. Left at the engine's default of 30
        // (GlobalIlluminationSystem.kt:57) the radius would have gone from 30/0.25 = 120 pixels
        // — a halo nobody chose — to 120 METRES, twice Framing.VISIBLE_DEPTH_METRES. So the
        // value is stated in metres and converted here, once.
        //
        // WHAT THIS MEASURABLY DOES, WHICH IS ALMOST NOTHING, AND WHY THAT IS THE RIGHT
        // OUTCOME RATHER THAN A REASON TO SKIP IT. Captured at 16:9 (3200x1800 framebuffer) in
        // the abyss with pearls in frame, four runs of the same pinned scene:
        //
        //     aoRadius default (120 m)   frame mean 14.866 / 14.864 over two runs
        //     AO_RADIUS_METRES = 4 m     frame mean 14.913
        //     aoRadius 0 (AO disabled)   frame mean 14.913
        //
        // Two runs of the SAME build differ by a mean |delta| of 0.012/255 (the run-to-run
        // floor: GI accumulates temporally and ao.frag jitters its ray directions by `time`).
        // The 4 m build differs from AO-DISABLED by 0.005/255 — BELOW that floor, i.e. at this
        // radius ambient occlusion contributes nothing to our frame at all. The 120 m default
        // differed from both by 0.088/255, peaking at 57/255, and every one of those pixels sat
        // in the glow around a single pearl near the left wall. Side-by-side crops of that pearl
        // are indistinguishable by eye. NO VISUAL JUSTIFICATION IS BEING CLAIMED FOR 4 m; the
        // justification is the unit, and the measurement is recorded so nobody re-tunes this
        // hunting for an effect that is not there yet.
        //
        // The reason it is inert is in ao.frag: the loop only accumulates occlusion where a ray
        // hits SDF geometry that is NOT a light source (`hitLightSource` breaks without
        // occluding, ao.frag:57-60). GI_LOCAL_SCENE is fed by exactly two things — GiOccluder
        // entities through GlobalIlluminationSystem's localOccluderPass, and our three
        // immediate-mode drawLight calls in [render]. We have no scene entities at all (see the
        // comment above engine.scene.createEmptyAndSetActive), so the only geometry in the local
        // SDF is the light quads themselves. What the 120 m default was producing, then, was a
        // faint darkening around the lights — an artefact of a radius twice the height of the
        // visible column, over geometry that is not supposed to occlude anything.
        //
        // It goes live the moment something is drawn as an OCCLUDER — the rock walls are the
        // obvious candidate when the art lands. That is when to tune AO_RADIUS_METRES by eye,
        // anywhere between roughly 1 m and 10 m, and it is exactly then that a wrong unit would
        // have been expensive to find. Do not reintroduce a screen-relative expression while
        // tuning: if a value looks right at one resolution and wrong at another, something else
        // is wrong and a pixel count will hide it rather than fix it.
        system.aoRadius = AO_RADIUS_METRES * system.localSceneTexScale

        // THE THREE NEIGHBOURING KNOBS THAT LOOK LIKE THEY NEED THE SAME TREATMENT AND DO NOT.
        //
        // 1. A light's `radius` (radiance_cascades.frag:120-127, `radius * camScale / dist^2`)
        //    genuinely is NOT scale-invariant — it has dimension 1/length — but we pass
        //    `radius = 0f` on every drawLight in [render], which skips the falloff branch
        //    entirely, so it does not reach us. Anyone who later sets a non-zero radius is
        //    tuning a number whose meaning depends on the display's height, and will have to
        //    derive it the way this one is derived.
        // 2. The minimum light quad size (scene.vert:86-88,
        //    `max(size, pixelSizeInWorld * 1500 / camScale)`) IS scale-invariant already:
        //    screenSpacePos.w is exactly 1.0 for an affine orthographic camera, so the floor is
        //    1500/(resolution.y * camScale) world units, i.e. a constant number of screen
        //    pixels. Nothing to do.
        // 3. `normalMapScale` (GlobalIlluminationSystem.kt:54, default 4f, uploaded as its
        //    RECIPROCAL at GiRadianceCascades.kt:88 and GiInterior.kt:52) is the out-of-plane
        //    component of the ray direction — A UNITLESS RATIO, NOT A LENGTH. It does not
        //    change meaning when a world unit goes from a pixel to a metre and MUST NOT be
        //    compensated the way aoRadius is above. It is inert today because nothing is drawn
        //    to GI_NORMAL_MAP; it goes live when the normal-mapped sprite art lands. Left at
        //    its default deliberately, not by omission.

        engine.scene.addSystem(system)
        gi = system

        engine.gfx.mainSurface.addPostProcessingEffect(
            ColorGradingEffect(toneMapper = ACES, vignette = 0.25f, exposure = 1.1f, contrast = 1.3f)
        )
        engine.gfx.mainSurface.addPostProcessingEffect(
            BloomEffect().apply { intensity = 1.2f; radius = 0f; threshold = 1.4f }
        )

        engine.scene.start()
    }

    /** Call once per restart so a stale beam heading from the previous run does not carry over. */
    fun resetAim()
    {
        beamAngleDeg = DiverSprite.REST_HEADING_DEGREES
        beamInitialized = false
    }

    /**
     * Ambient light as a continuous function of depth — see [DepthBlend]. Positional
     * independent of the camera, so unlike [render] it is fine to call from `onUpdate()`.
     */
    fun updateAmbient(sim: DiveSim)
    {
        val depth = sim.depth
        ambientColor.setFromRgba(
            DepthBlend.blend(depth, ambientRed),
            DepthBlend.blend(depth, ambientGreen),
            DepthBlend.blend(depth, ambientBlue),
            1f
        )
        gi?.ambientLight = ambientColor
    }

    /**
     * Immediate-mode light draws, IN WORLD METRES — the same coordinates `DiveRenderer` draws
     * the objects these lights sit on. Must still be called from `onRender`; see the class doc
     * for what that buys now that it is no longer alignment.
     *
     * [cam] is passed in, exactly as `DiveRenderer.render` takes it, and NOT fetched from
     * `engine.gfx.mainCamera` here even though this method already holds the engine. Two
     * reasons, and the second is the load-bearing one:
     *
     *  - It is provably the same camera `DiveRenderer` walked its visible rect against on this
     *    frame, because `EnPustTil.onRender` reads the field once and hands the same reference to
     *    both. GI's local scene surface is created with `camera = engine.gfx.mainCamera`
     *    (GlobalIlluminationSystem.kt:78) — the same object — so a light and the square it sits
     *    on are culled against the same rect and drawn through the same matrix.
     *  - `MainCameraOwnershipTest` holds an exact-set allow-list of the files that may so much as
     *    NAME `engine.gfx.mainCamera`, and this file is deliberately not on it. Reaching for the
     *    field here would have to widen that list, which is the guard against a second writer of
     *    the shared camera — the fault `6ea1f53` fixed — being loosened for a mere read.
     */
    fun render(engine: PulseEngine, sim: DiveSim, cam: Camera)
    {
        val surface = engine.gfx.getSurface(GlobalIlluminationSystem.GI_LOCAL_SCENE) ?: return
        val renderer = surface.getRenderer<GiSceneRenderer>() ?: return

        drawPearlLights(surface, renderer, sim, cam)
        drawAnglerfishLight(surface, renderer, sim, cam)
        drawDiverBeam(surface, renderer, sim, cam)
    }

    /**
     * How far outside the visible rect a light quad still counts as on screen, in metres.
     *
     * NOT a safety fudge, and deliberately not a copy of the 50-PIXEL `CULL_MARGIN` the deleted
     * `isOnScreen` carried — that number existed because the old check compared a light's CENTRE
     * against the screen's rows and so needed slack for the quad's own half-size, which
     * [showsSquare] now accounts for exactly.
     *
     * WHAT IT USED TO SAY, AND WHY THAT WAS WRONG. It claimed to cover `scene.vert:88-99`'s
     * `upscaleSmallSources`, which enlarges a light quad by up to 3x while dividing its
     * intensity by the same factor. That branch never runs on our lights:
     * `GlobalIlluminationSystem` only sets `upscaleSmallSources` on the GI_GLOBAL_SCENE
     * renderer (GlobalIlluminationSystem.kt:203-207), and every `drawLight` below goes to
     * GI_LOCAL_SCENE, whose renderer keeps the field default of `false`. So the quad
     * rasterised is exactly the quad asked for.
     *
     * The margin stays, at one pearl-light size, for what it now genuinely buys: a light just
     * off the visible rect still contributes to on-screen probes, because `radius = 0` means
     * there is no distance falloff to make its contribution negligible at the boundary. Three
     * metres, in metres, at any resolution.
     */
    private const val LIGHT_CULL_MARGIN_METRES = PEARL_LIGHT_SIZE_METRES

    private fun drawPearlLights(surface: Surface, renderer: GiSceneRenderer, sim: DiveSim, cam: Camera)
    {
        val emitter = LightEmitter.emitter()
        surface.setDrawColor(pearlLight)
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            if (!cam.showsSquare(pearl.x, pearl.depth, PEARL_LIGHT_SIZE_METRES, LIGHT_CULL_MARGIN_METRES))
                return@forEach
            renderer.drawLight(
                texture = emitter,
                x = pearl.x, y = pearl.depth, w = PEARL_LIGHT_SIZE_METRES, h = PEARL_LIGHT_SIZE_METRES,
                angle = 0f,
                intensity = pearlIntensityForDepth(pearl.depth),
                coneAngle = WIDE_GLOW_CONE_ANGLE,
                radius = 0f
            )
        }
    }

    /**
     * The anglerfish lure. Same colour AND same intensity curve as a real pearl, deliberately
     * — the tell is motion, never light (see DiveRenderer.drawAnglerfish).
     *
     * NOT OFFSET the way the torch is ([TORCH_FORWARD_FRACTION]), and neither are the pearls. The
     * offset exists because the diver has a facing and carries his light at one end of himself;
     * these two emit `coneAngle = 360` from a body that has no front, so there is no direction to
     * offset them ALONG. Giving the lure one would also be a second heading to keep in step with
     * the fish's drawn sprite, which is the shape of bug this file already carries two comments
     * about.
     */
    private fun drawAnglerfishLight(surface: Surface, renderer: GiSceneRenderer, sim: DiveSim, cam: Camera)
    {
        val fish = sim.anglerfish ?: return
        if (!cam.showsSquare(fish.x, fish.depth, PEARL_LIGHT_SIZE_METRES, LIGHT_CULL_MARGIN_METRES)) return
        surface.setDrawColor(pearlLight)
        renderer.drawLight(
            texture = LightEmitter.emitter(),
            x = fish.x, y = fish.depth, w = PEARL_LIGHT_SIZE_METRES, h = PEARL_LIGHT_SIZE_METRES,
            angle = 0f,
            intensity = pearlIntensityForDepth(fish.depth),
            coneAngle = WIDE_GLOW_CONE_ANGLE,
            radius = 0f
        )
    }

    /**
     * The diver's own light: a flashlight beam, always pointed somewhere. Three things this
     * must get right (all playtest-driven):
     *
     *   - It must track `sim.x` (the old lamp was hardcoded to screen centre) — which is now
     *     nothing more than passing `sim.x`, the same number `DiveRenderer.drawDiver` passes.
     *   - Near-zero velocity gives `atan2` a meaningless direction that would jitter wildly
     *     frame to frame, so below [STATIONARY_SPEED_THRESHOLD] the aim STOPS TRACKING and
     *     the last heading is held. That gate is genuinely a threshold and stays one.
     *   - The beam must stay directional while the player holds still. It previously widened
     *     to coneAngle 360 when stopped, which does not mean "a very wide torch" — it trips
     *     the `coneAngle < PI` guard in GI's scene.frag and disables cone attenuation
     *     outright, emitting full radiance in all 360 degrees. Combined with `radius = 0`
     *     (no distance falloff at all, see below) that is a large, perfectly symmetric,
     *     over-bloom-threshold disc centred on the diver — the "intense bloom blob" report.
     *     A held torch still points where you last pointed it, so the heading [beamAngleDeg]
     *     was already being preserved for is now actually used.
     *
     * Cone width and intensity are RAMPED by speed rather than branched on it. Two reasons:
     * the old hard branch teleported the cone between 50 and 360 degrees the moment the
     * diver drifted across 1.5 m/s, which pops visibly; and once both ends are expressed as
     * a peak radiance ([beamIntensity]) there is no longer anything to branch on — focusing
     * is a continuous property of how hard you are swimming. At or above the threshold the
     * ramp is saturated, so everything a moving diver sees is bit-identical to before.
     *
     * `radius` stays 0 throughout. In scene.frag that is not "unbounded radius" so much as
     * "skip the falloff term": `radius > 0` enables an inverse-square `radius*camScale/d^2`
     * attenuation, and 0 disables it. Bounding the glow that way is a real option for
     * tightening this further, but it would change the focused beam too, and the focused
     * beam is known-good.
     */
    private fun drawDiverBeam(surface: Surface, renderer: GiSceneRenderer, sim: DiveSim, cam: Camera)
    {
        val speed = hypot(sim.vx, sim.vy)

        // THE TORCH IS CARRIED IN FRONT OF HIM, not at his middle. `sim.x, sim.depth` is the
        // CENTRE of the body — see [TORCH_FORWARD_FRACTION] for the measurement off the sheet and
        // for why the direction is [beamAngleDeg] rather than a second heading derived here.
        val torchX = torchX(sim.x, beamAngleDeg)
        val torchDepth = torchDepth(sim.depth, beamAngleDeg)

        // THE HEADING IS NO LONGER INTEGRATED HERE — see [updateAim], which runs on the fixed tick
        // so that the diver's BODY can be drawn to the same number in the same frame. Everything
        // left in this method is a per-frame derivation from state, so culling it costs nothing
        // and can lose nothing.
        //
        // CULLED ON THE EMITTER'S OWN POSITION, not the diver's. The two are now up to
        // [torchOffsetMetres] apart — 3.6 m against a 3 m margin — so culling on `sim.x` would
        // drop a torch that is still in frame, or keep one that is not, by more than the margin
        // covers. The quad that is drawn is the quad to test.
        //
        // Padded by LIGHT_CULL_MARGIN_METRES like the other two, not by this light's own size: the
        // torch quad is now 1.2 m, and a margin that small would cut the beam of a diver a metre
        // outside the rect, whose light still reaches into it (radius = 0, so nothing attenuates
        // with distance). The margin's own doc records why the `upscaleSmallSources` argument that
        // used to justify a per-light margin does not apply to us at all.
        if (!cam.showsSquare(torchX, torchDepth, DIVER_LIGHT_SIZE_METRES, LIGHT_CULL_MARGIN_METRES)) return

        // Intensity still keys off the DIVER's depth, not the torch's. The offset moves where the
        // light comes from; it must not also nudge the zone blend, or the beam would brighten
        // slightly whenever he happened to point downward. Same reason the light budget is
        // unchanged by this commit: `size x intensity` is untouched (`006512b`).
        val baseIntensity = diverIntensityForDepth(sim.depth)
        surface.setDrawColor(diverLight)
        renderer.drawLight(
            texture = LightEmitter.emitter(),
            x = torchX, y = torchDepth, w = DIVER_LIGHT_SIZE_METRES, h = DIVER_LIGHT_SIZE_METRES,
            angle = beamAngleDeg,
            intensity = beamIntensity(baseIntensity, speed),
            coneAngle = beamConeAngle(speed),
            radius = 0f
        )
    }

    /**
     * How focused the beam is, 0 while hovering to 1 at [STATIONARY_SPEED_THRESHOLD] and above.
     */
    private fun beamFocus(speed: Float): Float = (speed / STATIONARY_SPEED_THRESHOLD).coerceIn(0f, 1f)

    /**
     * Peak value of GI's cone mask for a cone of [coneAngleDegrees] FULL width, i.e. the
     * largest fraction of nominal intensity that ever reaches the screen from this light.
     *
     * Read straight off `pulseengine/shaders/lighting/global/scene.frag`, which is the only
     * place the semantics actually live. `GiSceneRenderer.drawLight` passes `coneAngle`
     * through untouched into a vertex attribute; scene.frag packs it as
     * `metadata.r = coneAngle / 360`; and radiance_cascades.frag decodes and applies it:
     *
     *     float coneAngle = metadata.r * PI;              // == radians(coneAngleDegrees / 2)
     *     if (coneAngle < PI)                             // 360 degrees skips this entirely
     *     {
     *         float dotK = max(dot(coneDir, -rayDir), 0.0);
     *         color.rgb *= clamp((dotK - cos(coneAngle)), 0, 1);
     *     }
     *
     * So: the parameter is DEGREES, and it is the FULL cone width — the shader halves it
     * itself. 360 is not merely the widest cone, it is a distinct omnidirectional case that
     * bypasses the mask.
     *
     * The trap, and the reason this function exists at all, is that the mask is NOT
     * normalised. `dotK` is at most 1 (a ray dead on axis), so the mask can never exceed
     * `1 - cos(halfAngle)`. Narrowing a cone therefore makes it DIMMER, not more
     * concentrated: a 50-degree cone peaks at 1 - cos(25 degrees) = 0.094, throwing away
     * more than 90% of its nominal intensity even along its own axis, while a 360-degree
     * one keeps all of it. That single factor is why the old code needed an unexplained 25x
     * on the moving branch just to compete with an un-multiplied stationary glow, and why
     * the two branches could not be reasoned about side by side.
     *
     * Landmark worth knowing: at 180 degrees the half-angle is 90, `cos` is 0, and the mask
     * degenerates to plain `max(cos(theta), 0)` — a Lambertian forward hemisphere with a
     * peak of exactly 1. At or below 180 a cone puts no light behind its own origin.
     */
    internal fun coneMaskPeak(coneAngleDegrees: Float): Float
    {
        if (coneAngleDegrees >= FULL_CIRCLE_DEGREES) return 1f
        val halfAngle = Math.toRadians((coneAngleDegrees / 2f).toDouble())
        return (1.0 - cos(halfAngle)).toFloat().coerceIn(0f, 1f)
    }

    /** Cone width in degrees for a diver moving at [speed] m/s — wide hovering, narrow swimming. */
    internal fun beamConeAngle(speed: Float): Float =
        STATIONARY_CONE_ANGLE + (BEAM_CONE_ANGLE - STATIONARY_CONE_ANGLE) * beamFocus(speed)

    /**
     * Nominal intensity to hand `drawLight`, such that the light's PEAK ON-AXIS RADIANCE is
     * the ramped target regardless of how wide the cone currently is. Dividing out
     * [coneMaskPeak] is what makes "hovering" and "swimming" comparable: without it, widening
     * the cone silently brightens the light by up to 10x and narrowing it silently dims it,
     * which is exactly how the original 25x-versus-1x pairing came to be so far off.
     */
    internal fun beamIntensity(baseIntensity: Float, speed: Float): Float
    {
        val peak = STATIONARY_PEAK_RADIANCE + (BEAM_PEAK_RADIANCE - STATIONARY_PEAK_RADIANCE) * beamFocus(speed)
        return baseIntensity * peak / coneMaskPeak(beamConeAngle(speed)).coerceAtLeast(MIN_CONE_MASK_PEAK)
    }

    /**
     * Deeper zones are darker, so pearls must shine harder to stay legible. Continuous in
     * depth (see [DepthBlend]) rather than switching at a zone boundary. Pure and
     * unit-tested — everything else here needs a live GL context to verify.
     *
     * NOT COMPENSATED FOR THE ROUND EMITTER, AND THAT IS A MEASUREMENT RATHER THAN AN OMISSION.
     * A disc intercepts pi/4 as many of GI's rays as the square it is inscribed in (Cauchy: mean
     * width is perimeter / pi), so the arithmetic says every light should be brightened by 4/pi
     * to keep the change a change of shape only. Built that way and captured, it was wildly
     * wrong: the 16:9 frame mean went 6.382 -> 11.632, i.e. +82% for a nominal +27%, because
     * `setup` puts an ACES tone mapper and a THRESHOLDED bloom (`threshold = 1.4`) on
     * mainSurface and neither is linear in radiance near a light. The same capture with the
     * compensation removed reads 6.239 — 2.2% below the square-emitter baseline, against a
     * same-build control pair that agreed to 0.0007/255. So the honest correction for the shape
     * change is roughly a fiftieth of the analytic one, which is inside the noise of anything
     * anyone could judge by eye, and applying nothing is closer to right than applying 4/pi.
     * Do not re-derive this on paper; the post chain is what decides it.
     */
    internal fun pearlIntensityForDepth(depth: Float): Float = DepthBlend.blend(depth, pearlIntensityByZone)

    /**
     * Same continuity treatment as [pearlIntensityForDepth], for the diver's own light, times
     * [TORCH_SIZE_COMPENSATION] — the torch emits from a quad a fifth of the pearls' area, and
     * with `radius = 0` a light's reach is linear in its quad's size. See that constant.
     */
    internal fun diverIntensityForDepth(depth: Float): Float =
        DepthBlend.blend(depth, diverIntensityByZone) * TORCH_SIZE_COMPENSATION

    // `isOnScreen(screenY, screenHeight)` USED TO LIVE HERE and went with the coordinates it was
    // written in: it was a screen-row bounds check with a 50-PIXEL margin, and there are no
    // screen rows in this file any more. Nothing culls in the meantime, which is fine — there
    // are at most a few dozen lights and a quad outside the frustum is clipped by the GPU. Task
    // 7 of docs/superpowers/plans/2026-08-06-engine-world-coordinates.md restores it as
    // `cam.isInView(...)` with a padding derived from the glow's reach, which also tests x —
    // something the old check never did, so a pearl far outside the visible half-width was
    // submitted every frame.
}
