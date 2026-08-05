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
import no.njoh.pulseengine.modules.scene.entities.Camera
import no.njoh.pulseengine.modules.scene.systems.EntityRendererImpl
import no.njoh.pulseengine.modules.scene.systems.EntityUpdater
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

    /** Below this speed (m/s) the direction of travel is noise, not intent — see [drawDiverBeam]. */
    private const val STATIONARY_SPEED_THRESHOLD = 1.5f

    private const val BEAM_CONE_ANGLE = 50f
    private const val WIDE_GLOW_CONE_ANGLE = 360f

    // A directional beam concentrates the same light budget into a much smaller solid angle
    // than the old omnidirectional glow, so it needs a higher nominal intensity to actually
    // read as a bright flashlight rather than a dim smear — matched empirically against
    // screenshots, the same way the old zoneIntensityFor values were tuned.
    private const val BEAM_INTENSITY_MULT = 25f

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
        engine.scene.createEmptyAndSetActive("dive.scn")
        engine.scene.addSystem(EntityUpdater())
        engine.scene.addSystem(EntityRendererImpl())

        val camera = Camera()
        camera.viewPortWidth = engine.window.width.toFloat()
        camera.viewPortHeight = engine.window.height.toFloat()

        // CRITICAL: the scene Camera entity drives engine.gfx.mainCamera every fixed tick
        // (see Camera.onFixedUpdate in the engine source), and its default xOrigin/yOrigin
        // of 0.5 recentres that shared camera on the middle of the screen. DiveRenderer and
        // Hud both draw to the SAME mainSurface using Viewport's top-left-origin pixel maths
        // (screenX/screenY assume (0,0) = top-left). Left at the default, the GI scene camera
        // silently fights that convention and shifts every fillRect/drawText call — verified
        // empirically: without this, the waterline and HUD render far from their expected
        // position. Pinning the origin to top-left keeps the shared camera at the identity
        // transform Viewport already assumes, so this entity exists purely to satisfy GI's
        // requirement for an active scene camera, and touches nothing else.
        //
        // It is also why the immediate-mode drawLight calls below can use the exact same
        // Viewport.screenX/screenY pixel values DiveRenderer uses: GI's "local scene" surface
        // is created with `camera = engine.gfx.mainCamera` (confirmed in the engine source,
        // GlobalIlluminationSystem.onCreate), the SAME camera mainSurface uses. Pin it to
        // identity once here and both surfaces agree on what a pixel coordinate means.
        camera.xOrigin = 0f
        camera.yOrigin = 0f
        engine.scene.addEntity(camera)

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
     * The diver's own light: a flashlight beam in the direction of travel, replacing the old
     * omnidirectional lamp. Two things this must get right (both playtest-driven):
     *
     *   - It must track `sim.x` (the old lamp was hardcoded to screen centre) — done simply
     *     by using the same `Viewport.screenX(sim.x, w, h)` call `DiveRenderer.drawDiver` uses.
     *   - Near-zero velocity gives `atan2` a meaningless direction that would jitter wildly
     *     frame to frame, so below [STATIONARY_SPEED_THRESHOLD] the beam widens into a soft
     *     glow (coneAngle 360) instead of chasing noise, and the last aimed heading is held
     *     rather than re-targeted — so the cone does not snap back to some default the
     *     instant the diver coasts to a stop.
     */
    private fun drawDiverBeam(surface: Surface, renderer: GiSceneRenderer, sim: DiveSim, cam: Float, dt: Float, w: Float, h: Float)
    {
        val ppm = Viewport.pixelsPerMetre(h)
        val screenX = Viewport.screenX(sim.x, w, h)
        val screenY = Viewport.screenY(sim.depth, cam, h)

        val speed = hypot(sim.vx, sim.vy)
        val moving = speed >= STATIONARY_SPEED_THRESHOLD
        if (moving)
        {
            // GiSceneRenderer's cone direction is Y-flipped relative to Viewport's
            // screen-space-Y-down convention — confirmed empirically (a straight vertical
            // descent produced a beam pointing straight UP, opposite of travel, before this
            // negation). See AimAngle's class doc.
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
            intensity = if (moving) baseIntensity * BEAM_INTENSITY_MULT else baseIntensity,
            coneAngle = if (moving) BEAM_CONE_ANGLE else WIDE_GLOW_CONE_ANGLE,
            radius = 0f
        )
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
