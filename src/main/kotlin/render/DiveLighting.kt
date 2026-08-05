package render

import dive.DiveSim
import dive.Zone
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.graphics.postprocessing.effects.BloomEffect
import no.njoh.pulseengine.core.graphics.postprocessing.effects.ColorGradingEffect
import no.njoh.pulseengine.core.graphics.postprocessing.effects.ColorGradingEffect.ToneMapper.ACES
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.modules.lighting.global.GlobalIlluminationSystem
import no.njoh.pulseengine.modules.scene.entities.Camera
import no.njoh.pulseengine.modules.scene.entities.Lamp
import no.njoh.pulseengine.modules.scene.systems.EntityRendererImpl
import no.njoh.pulseengine.modules.scene.systems.EntityUpdater

/**
 * Pearls ARE the light. In the Abyss they are the only light source, which is
 * why lighting is part of the core loop rather than a polish pass — you cannot
 * judge whether the deep feels right without it.
 *
 * Works entirely on untextured entities, so it is fully compatible with
 * placeholder art.
 */
object DiveLighting
{
    private const val PEARL_LAMP_POOL = 24

    /** How far off-screen (px) a lamp may sit before it is culled/parked. */
    private const val CULL_MARGIN = 50f

    private val pearlLight = Color(1f, 0.82f, 0.45f)
    private val diverLight = Color(0.6f, 0.85f, 1f)

    // Ambient falls off zone by zone so the Abyss is the ONLY place where pearls are the
    // sole light source — shallower water still reads without a lamp nearby. Pre-built once
    // (5 fixed Colors), reused by reference every frame; never allocated in sync().
    private val ambientByZone = mapOf(
        Zone.SHALLOWS to Color(0.34f, 0.52f, 0.68f),
        Zone.KELP     to Color(0.16f, 0.30f, 0.44f),
        Zone.TWILIGHT to Color(0.06f, 0.14f, 0.24f),
        Zone.TRENCH   to Color(0.015f, 0.045f, 0.09f),
        Zone.ABYSS    to Color(0.0f, 0.0f, 0.003f)
    )

    private val pearlLamps = ArrayList<Lamp>(PEARL_LAMP_POOL)
    private var diverLamp: Lamp? = null
    private var gi: GlobalIlluminationSystem? = null

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
        camera.xOrigin = 0f
        camera.yOrigin = 0f
        engine.scene.addEntity(camera)

        // Pooled lamps — allocated once, repositioned every frame, never created
        // in the render path.
        repeat(PEARL_LAMP_POOL) {
            val lamp = Lamp()
            lamp.lightColor = pearlLight
            lamp.intensity = 0f
            lamp.width = 6f
            lamp.height = 6f
            lamp.coneAngle = 360f
            engine.scene.addEntity(lamp)
            pearlLamps += lamp
        }

        diverLamp = Lamp().also {
            it.lightColor = diverLight
            it.intensity = 2f
            it.width = 14f
            it.height = 14f
            it.coneAngle = 360f
            engine.scene.addEntity(it)
        }

        val system = GlobalIlluminationSystem()
        system.lightTexScale = 0.25f
        system.localSceneTexScale = 0.25f
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

    /** Repositions the pooled lamps onto the nearest visible pearls. */
    fun sync(engine: PulseEngine, sim: DiveSim, camera: DiveCamera)
    {
        val w = engine.window.width.toFloat()
        val h = engine.window.height.toFloat()
        val centreX = w * 0.5f
        val cameraDepth = camera.depth

        gi?.ambientLight = ambientByZone.getValue(sim.zone)
        val zoneIntensity = zoneIntensityFor(sim.zone)

        var lampIndex = 0
        sim.pearls.forEach { pearl ->
            if (pearl.collected || lampIndex >= pearlLamps.size) return@forEach
            val screenY = Viewport.screenY(pearl.depth, cameraDepth, h)
            if (!isOnScreen(screenY, h)) return@forEach

            pearlLamps[lampIndex].apply {
                x = Viewport.screenX(pearl.x, w, h)
                y = screenY
                intensity = zoneIntensity
            }
            lampIndex++
        }

        // Anglerfish lure reads identically to a pearl by colour — the tell is motion,
        // never light. It borrows a pooled lamp slot exactly like a real pearl.
        sim.anglerfish?.let { fish ->
            if (lampIndex < pearlLamps.size)
            {
                val screenY = Viewport.screenY(fish.depth, cameraDepth, h)
                if (isOnScreen(screenY, h))
                {
                    pearlLamps[lampIndex].apply {
                        x = Viewport.screenX(fish.x, w, h)
                        y = screenY
                        intensity = zoneIntensity
                    }
                    lampIndex++
                }
            }
        }

        // Park unused lamps.
        while (lampIndex < pearlLamps.size) pearlLamps[lampIndex++].intensity = 0f

        diverLamp?.apply {
            x = centreX
            y = Viewport.screenY(sim.depth, cameraDepth, h)
            intensity = if (sim.zone == Zone.ABYSS) 1.2f else 2f
        }
    }

    /**
     * Deeper zones are darker, so pearls must shine harder to stay legible. Pure and
     * unit-tested — everything else here needs a live GL context to verify.
     */
    internal fun zoneIntensityFor(zone: Zone): Float = when (zone)
    {
        Zone.SHALLOWS -> 0.6f
        Zone.KELP     -> 1.0f
        Zone.TWILIGHT -> 1.8f
        Zone.TRENCH   -> 2.6f
        Zone.ABYSS    -> 4.0f
    }

    /** Whether a lamp at [screenY] is close enough to the visible band to bother lighting. */
    internal fun isOnScreen(screenY: Float, screenHeight: Float): Boolean =
        screenY >= -CULL_MARGIN && screenY <= screenHeight + CULL_MARGIN
}
