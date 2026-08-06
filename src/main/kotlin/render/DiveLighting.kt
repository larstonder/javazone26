package render

import dive.DiveSim
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.asset.types.Texture
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
 * `Torch.onRenderLightSource`, which does exactly this). So [render] is now called from
 * `EnPustTil.onRender()` — the SAME call that drives `DiveRenderer`, reading the SAME
 * `camera.depth` on the SAME frame — which is what makes the light and the object it
 * illuminates agree pixel-for-pixel. There is nothing left to pool: no entities, no
 * per-frame entity-system update pass, no repositioning step that can fall out of sync.
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
 */
object DiveLighting
{
    /** How far off-screen (px) a light may sit before it is culled. */
    private const val CULL_MARGIN = 50f

    private val pearlLight = Color(1f, 0.82f, 0.45f)
    private val diverLight = Color(0.6f, 0.85f, 1f)

    private const val PEARL_LIGHT_SIZE_METRES = 3f
    private const val DIVER_LIGHT_SIZE_METRES = 3f

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

    // -90 degrees is "facing down" in GiSceneRenderer's Y-flipped cone-direction convention
    // (see AimAngle's class doc) — a sensible default before the diver first moves.
    private var beamAngleDeg = -90f
    private var beamInitialized = false

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
        // through the SAME Viewport.screenX — stayed at 0.395. That 0.40-vs-0.63 split is
        // exactly the shipped "world offset from the HUD" report. The diver square also came
        // out 115 px instead of 72, the same 1.6x.
        //
        // Left alone, mainCamera is DefaultCamera.createOrthographic(window.width,
        // window.height) at position 0, origin 0, scale 1 (GraphicsImpl.init:47,
        // Camera.kt:16-25) — the identity — and its projection is re-issued as
        // ortho(0, w, h, 0) for EVERY surface camera on every window change
        // (GraphicsImpl.onWindowChanged:95). It is therefore a correct screen-pixel camera at
        // every framebuffer size, forever, with no maintenance. That identity is precisely
        // what render/Viewport's top-left-origin pixel maths already assumes, and it is what
        // lets the immediate-mode drawLight calls below use the exact same
        // Viewport.screenX/screenY pixel values DiveRenderer uses: GI's "local scene" surface
        // is created with `camera = engine.gfx.mainCamera` (GlobalIlluminationSystem.kt:78),
        // the SAME camera mainSurface uses. Nothing in this process writes it now, so both
        // surfaces agree on what a pixel coordinate means at any size.
        //
        // Nothing may reintroduce a scene Camera entity here without also moving every draw
        // in DiveRenderer, DiveLighting and Hud into the same coordinate space — which is the
        // (deliberately deferred) migration in
        // docs/superpowers/plans/2026-08-06-engine-world-coordinates.md.
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
        beamAngleDeg = -90f
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
     * Immediate-mode light draws. MUST be called from the same place, on the same frame, and
     * with the same [camera] read `DiveRenderer.render` uses — see the class doc for why.
     */
    fun render(engine: PulseEngine, sim: DiveSim, camera: DiveCamera, dt: Float, w: Float, h: Float)
    {
        val surface = engine.gfx.getSurface(GlobalIlluminationSystem.GI_LOCAL_SCENE) ?: return
        val renderer = surface.getRenderer<GiSceneRenderer>() ?: return
        val cam = camera.depth

        drawPearlLights(surface, renderer, sim, cam, w, h)
        drawAnglerfishLight(surface, renderer, sim, cam, w, h)
        drawDiverBeam(surface, renderer, sim, cam, dt, w, h)
    }

    private fun drawPearlLights(surface: Surface, renderer: GiSceneRenderer, sim: DiveSim, cam: Float, w: Float, h: Float)
    {
        val ppm = Viewport.pixelsPerMetre(h)
        val size = PEARL_LIGHT_SIZE_METRES * ppm
        surface.setDrawColor(pearlLight)
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            val screenY = Viewport.screenY(pearl.depth, cam, h)
            if (!isOnScreen(screenY, h)) return@forEach
            val screenX = Viewport.screenX(pearl.x, w, h)
            renderer.drawLight(
                texture = Texture.BLANK,
                x = screenX, y = screenY, w = size, h = size,
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
     */
    private fun drawAnglerfishLight(surface: Surface, renderer: GiSceneRenderer, sim: DiveSim, cam: Float, w: Float, h: Float)
    {
        val fish = sim.anglerfish ?: return
        val screenY = Viewport.screenY(fish.depth, cam, h)
        if (!isOnScreen(screenY, h)) return
        val ppm = Viewport.pixelsPerMetre(h)
        val size = PEARL_LIGHT_SIZE_METRES * ppm
        val screenX = Viewport.screenX(fish.x, w, h)
        surface.setDrawColor(pearlLight)
        renderer.drawLight(
            texture = Texture.BLANK,
            x = screenX, y = screenY, w = size, h = size,
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
     *   - It must track `sim.x` (the old lamp was hardcoded to screen centre) — done simply
     *     by using the same `Viewport.screenX(sim.x, w, h)` call `DiveRenderer.drawDiver` uses.
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
    private fun drawDiverBeam(surface: Surface, renderer: GiSceneRenderer, sim: DiveSim, cam: Float, dt: Float, w: Float, h: Float)
    {
        val ppm = Viewport.pixelsPerMetre(h)
        val screenX = Viewport.screenX(sim.x, w, h)
        val screenY = Viewport.screenY(sim.depth, cam, h)

        val speed = hypot(sim.vx, sim.vy)
        if (speed >= STATIONARY_SPEED_THRESHOLD)
        {
            // GiSceneRenderer's cone direction is Y-flipped relative to Viewport's
            // screen-space-Y-down convention. Originally found empirically; now confirmed
            // from the shader source — scene.frag builds the cone direction as
            // `vec2(cos(a), sin(a))` in a framebuffer whose +y runs UP the screen, while
            // Viewport's screenY runs DOWN. See AimAngle's class doc.
            val target = AimAngle.headingDegrees(sim.vx, -sim.vy)
            beamAngleDeg = if (beamInitialized) AimAngle.smooth(beamAngleDeg, target, dt, AIM_SMOOTHING_RATE) else target
            beamInitialized = true
        }

        val size = DIVER_LIGHT_SIZE_METRES * ppm
        val baseIntensity = diverIntensityForDepth(sim.depth)
        surface.setDrawColor(diverLight)
        renderer.drawLight(
            texture = Texture.BLANK,
            x = screenX, y = screenY, w = size, h = size,
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
     */
    internal fun pearlIntensityForDepth(depth: Float): Float = DepthBlend.blend(depth, pearlIntensityByZone)

    /** Same continuity treatment as [pearlIntensityForDepth], for the diver's own light. */
    internal fun diverIntensityForDepth(depth: Float): Float = DepthBlend.blend(depth, diverIntensityByZone)

    /** Whether a light at [screenY] is close enough to the visible band to bother drawing. */
    internal fun isOnScreen(screenY: Float, screenHeight: Float): Boolean =
        screenY >= -CULL_MARGIN && screenY <= screenHeight + CULL_MARGIN
}
