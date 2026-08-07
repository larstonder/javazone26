package render

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.graphics.api.Camera
import no.njoh.pulseengine.core.graphics.api.CameraInternal

/**
 * Applies [DiveCamera]'s world-space camera depth to the engine's shared main camera.
 *
 * The engine's view transform is  screen(p) = origin + scale * (p + position - origin)
 * (ENG/core/graphics/api/Camera.kt:125-131). Pinning the camera's world point (0, depth)
 * to screen (W/2, 0) and choosing scale = H / VISIBLE_DEPTH_METRES reproduces the pre-migration
 * Viewport.screenX/screenY EXACTLY -- which is what made the migration a no-op on screen
 * and any visible change a bug. CameraRigTest asserts that equality over a grid of world
 * points at six display shapes, against a copy of those formulas inlined in the test, since
 * `Viewport` itself is gone (it is [Framing] now, and owns no transform).
 *
 * Deliberately NOT the engine's `Camera` scene entity, for two independent reasons:
 *
 *  1. Its viewport-fit scale, min(W/viewPortWidth, H/viewPortHeight) (Camera.kt:93), would
 *     show LESS than VISIBLE_DEPTH_METRES of water on a panel narrower than the design
 *     aspect. How far ahead you can see is how far ahead you can plan: that is a gameplay
 *     constant, not a presentation one, and the booth display's aspect is not known in
 *     advance.
 *  2. That entity's frozen viewPortWidth/Height IS the shipped world-offset-from-HUD bug,
 *     removed in 6ea1f53 -- see MainCameraOwnershipTest's class doc for the mechanism. It
 *     recomputes its scale from mainSurface.config every fixed tick against a viewport
 *     captured once during onCreate, so the world surface silently rescales the instant the
 *     framebuffer changes size and the HUD surface, which has its own camera, does not.
 *
 * [apply] MUST be called from onFixedUpdate. `updateViewMatrix` interpolates EVERY parameter --
 * position, rotation, origin AND scale -- from the value snapshotted at the top of each fixed
 * step (Camera.kt:118-123, PulseEngineImpl.kt:279); writing them from the render clock pairs
 * values that were never consecutive fixed states and makes the interpolator pull the camera
 * backwards by up to one interpolation factor -- sub-frame judder. Nothing is lost by sampling
 * DiveCamera at 60 Hz: its easing is 1 - e^(-k*dt) and therefore already frame-rate independent,
 * and the engine's interpolator then produces SMOOTHER motion above 60 fps than a render-clock
 * write does.
 *
 * [snap] EXISTS BECAUSE THAT INTERPOLATION HAS A STARTING VALUE, AND IT IS THE IDENTITY. The
 * snapshot lives in `CameraInternal.positionLast`/`scaleLast`/`originLast`, which the engine
 * only ever refreshes from `gfx.updateCameras()` INSIDE a fixed step (PulseEngineImpl.kt:279).
 * A `Camera` is constructed with `scale = (1,1,1)` and everything else zero (Camera.kt:16-25),
 * and the first frame runs no fixed step at all -- the accumulator has not filled yet -- so at
 * that frame's `initFrame` the interpolation factor is 0 and `updateViewMatrix` reads ONLY the
 * un-refreshed snapshot. Calling [apply] alone from `onCreate` therefore does NOT make frame 1
 * correct, however plausible that looks: measured at 2400x1800, frame 1 was drawn with
 * `viewMatrix = identity` -- one screen pixel per metre, about the top-left corner -- while
 * `scale` already read 30. That frame put the whole 1800 px column into the top 60 m of water,
 * and it made [CameraInvariants] rule 2 warn `1800.0 m of water is visible` once on every single
 * run, which reads exactly like a catastrophic scale fault and is really one transient frame.
 *
 * [snap] collapses the snapshot onto the applied values, so the very next matrix is built from
 * them at any interpolation factor. It belongs at every point where DiveCamera TELEPORTS
 * ([DiveCamera.snapTo] -- start-up and the first frame of a fresh run) and nowhere else:
 * interpolating a teleport is wrong in its own right, and using it on the fixed tick would
 * defeat the interpolation the paragraph above exists to preserve.
 *
 * The plan this migration follows states that `positionLast` is "on CameraInternal and
 * unreachable from our code". THAT IS FALSE, and it is why the first frame was left broken:
 * `CameraInternal` is public API in `core.graphics.api`, and `updateLastState()` is a public
 * abstract method on it (verified with `javap` against pulse-engine-0.13.0.jar, not inferred
 * from the sources). It is reached through a safe cast, so a future engine whose main camera is
 * not a `CameraInternal` degrades to today's one-bad-frame behaviour rather than crashing.
 *
 * THE ONE THING NO UNIT TEST HERE CAN CATCH: [apply] derives pixels-per-metre from the
 * surface HEIGHT. Deriving it from the width instead would misplace every object on every
 * non-square display, and it would leave CameraRigTest entirely green, because that test
 * transcribes the transform and calls [pixelsPerMetre] itself. It is caught by
 * [CameraInvariants] rule 3 on a real framebuffer and by nothing in `./gradlew test`. Do not
 * "simplify" the two lines below into one that reads `w`.
 *
 * WIRED IN FROM EnPustTil.onFixedUpdate, and from onCreate/justStarted right after
 * DiveCamera.snapTo so that frame 1 (and the first frame of a fresh run) is drawn with a real
 * matrix rather than whatever the previous run left behind: `topLeftWorldPosition` is computed
 * in GraphicsImpl.initFrame before any of our code runs that frame.
 */
object CameraRig
{
    /**
     * Screen pixels per world metre for a world surface [surfaceHeight] pixels tall.
     *
     * HEIGHT, never width and never a pixel count -- the booth display's aspect ratio is not
     * known in advance, so a fraction of the height is the only size that means the same
     * thing on every panel. A wider display then simply reveals more water sideways, which
     * is a presentation difference; a taller one would reveal more DEPTH, which would be a
     * gameplay difference, and is exactly what this constant scale prevents.
     */
    fun pixelsPerMetre(surfaceHeight: Float) = surfaceHeight / Framing.VISIBLE_DEPTH_METRES

    /** Screen x that world x = 0 is pinned to: the horizontal centre of the frame. */
    fun originX(surfaceWidth: Float) = surfaceWidth * 0.5f

    /** Screen y that world y = cameraDepth is pinned to: the top of the frame. */
    const val ORIGIN_Y = 0f

    /** `position` is `origin - worldTarget` in the engine's convention (Camera.kt:100-101). */
    fun positionX(surfaceWidth: Float) = originX(surfaceWidth) - WORLD_X_AT_ORIGIN

    fun positionY(cameraDepth: Float) = ORIGIN_Y - cameraDepth

    /**
     * Writes all four camera parameters, every fixed tick, from scratch.
     *
     * Sizes come from `mainSurface.config`, NOT `engine.window`. They are the same number
     * today and [CameraInvariants] rule 1 exists to notice if they ever stop being -- but
     * `config` is what the surface's own projection was built from (SurfaceImpl.init:46-47,
     * re-run from GraphicsImpl.onWindowChanged:92), so it is the only value that cannot
     * disagree with what is actually being rendered. Reading the window instead is one half
     * of the mechanism that shipped.
     *
     * `rotation` is written even though it is always zero, and `scale`'s z is written to 1.
     * This is a shared, mutable, engine-owned object: writing every field it uses means the
     * camera's state is a function of this call alone, and cannot be left holding something
     * the scene editor's Camera2DController (see EnPustTil.onCreate) or a future effect put
     * there. It costs four float stores per fixed tick.
     */
    fun apply(engine: PulseEngine, cameraDepth: Float)
    {
        val config = engine.gfx.mainSurface.config
        applyTo(engine.gfx.mainCamera, config.width.toFloat(), config.height.toFloat(), cameraDepth)
    }

    /**
     * [apply], plus a collapse of the engine's fixed-step interpolation onto the values just
     * written, so the NEXT matrix built from this camera is those values whatever the
     * interpolation factor is. See the class doc for the frame this exists to fix and for the
     * measurement behind it.
     *
     * Call this only where [DiveCamera] teleports rather than eases. On the fixed tick it would
     * be actively wrong: `updateLastState` is how the engine remembers where the camera was, and
     * overwriting that every tick leaves the interpolator nothing to interpolate.
     */
    fun snap(engine: PulseEngine, cameraDepth: Float)
    {
        val config = engine.gfx.mainSurface.config
        snapTo(engine.gfx.mainCamera, config.width.toFloat(), config.height.toFloat(), cameraDepth)
    }

    /**
     * The camera-level form of [apply], taking its target and its size explicitly so it can be
     * exercised against a real `DefaultCamera` in `CameraRigTest` without a live engine. The
     * two-argument size is what keeps `CameraRigTest` honest about aspect ratios; it is also
     * the one line in this file that must go on reading the HEIGHT (see [pixelsPerMetre]).
     */
    fun applyTo(camera: Camera, surfaceWidth: Float, surfaceHeight: Float, cameraDepth: Float)
    {
        val s = pixelsPerMetre(surfaceHeight)
        camera.scale.set(s, s, 1f)
        camera.rotation.set(0f, 0f, 0f)
        camera.origin.set(originX(surfaceWidth), ORIGIN_Y, 0f)
        camera.position.set(positionX(surfaceWidth), positionY(cameraDepth), 0f)
    }

    /** The camera-level form of [snap] -- see [applyTo]. */
    fun snapTo(camera: Camera, surfaceWidth: Float, surfaceHeight: Float, cameraDepth: Float)
    {
        applyTo(camera, surfaceWidth, surfaceHeight, cameraDepth)

        // Safe cast, not a hard one: every camera the engine builds is a DefaultCamera and so a
        // CameraInternal (GraphicsImpl.kt:47, :186), but this is engine-owned state reached
        // across an abstract boundary, and the cost of being wrong should be the one stale frame
        // we have today rather than a crash at the booth.
        (camera as? CameraInternal)?.updateLastState()
    }

    /**
     * The world x that [originX] is the screen position of. Zero, i.e. the column is centred:
     * the water column is generated symmetrically about x = 0 in `dive/`, so there is nothing
     * for a horizontal camera target to follow and a moving one would only add sway. Named
     * rather than dropped so that `position = origin - worldTarget` stays legible as the
     * engine's own convention instead of looking like an unexplained copy of `origin`.
     */
    private const val WORLD_X_AT_ORIGIN = 0f
}
