package render

import no.njoh.pulseengine.core.graphics.api.DefaultCamera
import no.njoh.pulseengine.core.shared.utils.Extensions.interpolateFrom
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What this file proves, and — more usefully — the one thing it cannot.
 *
 * PROVES: that [CameraRig]'s four camera parameters, pushed through the engine's own view
 * transform, put world (0, cameraDepth) at the top centre of the screen and exactly
 * [Framing.VISIBLE_DEPTH_METRES] of water between the top and bottom edges, on displays of
 * four different aspect ratios; and that the resulting mapping is numerically identical to
 * `Framing.screenX`/`screenY`, the pre-migration transform. The migration is DEFINED to be a
 * no-op on screen, so that last assertion is the one that says so.
 *
 * CANNOT CATCH — and this is the most useful sentence in the file. The bug class that actually
 * shipped is a pixels-per-metre derived from the surface WIDTH instead of its height (see the
 * plan's §3.1: nine green `ViewportTest` cases and that exact substitution survived all of
 * them). It would live at the CALL SITE, in `val s = pixelsPerMetre(h)` inside
 * [CameraRig.apply] — and `apply` takes a live `PulseEngine`, so it is unreachable from a unit
 * test. [screenPos] below is a TRANSCRIPTION that calls `pixelsPerMetre(h)` itself, so writing
 * `pixelsPerMetre(w)` in `apply` would leave every case here green. That substitution is caught
 * by [CameraInvariants] rule 3 on the real framebuffer (the visible world rect would stop
 * having the screen's aspect) and by the 21:9 capture in the plan's §3(d) — by NOTHING in
 * `./gradlew test`.
 *
 * ALSO NOT PROVED: agreement with the engine. This checks our parameters against a
 * transcription of `DefaultCamera.updateViewMatrix`; it would not notice the engine changing
 * that formula in 0.14.0.
 *
 * Two assertions an earlier draft of the plan specified are deliberately ABSENT and must not be
 * added back, because neither can fail (`CLAUDE.md`: a test that cannot fail is worse than no
 * test):
 *   - `w / CameraRig.pixelsPerMetre(h) == w/h * 60` reduces to `w / (h/60) == 60w/h`, an
 *     algebraic identity that holds for whatever `pixelsPerMetre` returns, including a wrong
 *     value.
 *   - "a given world point lands at the same fraction of h on all displays" is the
 *     `f(x)·h/h == f(x)·h/h` shape that was MEASURED as unkillable in `ViewportTest`.
 */
class CameraRigTest
{
    /**
     * Transcribed from `DefaultCamera.updateViewMatrix` (ENG/core/graphics/api/Camera.kt:125-131).
     * Rotation is always zero here, so the `rotateXYZ` term is omitted. This is a transcription,
     * NOT the engine executing: `updateViewMatrix()` defaults its interpolation factor to
     * `PulseEngine.INSTANCE.data.interpolation` (Camera.kt:120, Extensions.kt:47-55), and
     * `INSTANCE` is `lateinit ... internal set` (PulseEngine.kt:89), so it cannot be called
     * without a live engine. JOML itself IS the engine's own — org/joml ships inside
     * pulse-engine-0.13.0.jar — so the matrix arithmetic is not transcribed, only the order of
     * operations is.
     */
    private fun screenPos(w: Float, h: Float, camDepth: Float, worldX: Float, worldY: Float): Vector2f
    {
        val s = CameraRig.pixelsPerMetre(h)
        val ox = CameraRig.originX(w)
        val oy = CameraRig.ORIGIN_Y
        val m = Matrix4f()
            .identity()
            .translate(ox, oy, 0f)
            .scale(s, s, 1f)
            .translate(CameraRig.positionX(w) - ox, CameraRig.positionY(camDepth) - oy, 0f)
        val v = Vector4f(worldX, worldY, 0f, 1f).mul(m)
        return Vector2f(v.x, v.y)
    }

    // Killed by: drop the `* 0.5f` from CameraRig.originX -> the pin lands at w, not w/2.
    @Test
    fun `the camera's own world point lands at the top centre of every display`()
    {
        for ((w, h) in DISPLAYS)
            for (camDepth in CAMERA_DEPTHS)
            {
                val p = screenPos(w, h, camDepth, 0f, camDepth)
                assertEquals(w * 0.5f, p.x, TOLERANCE, "x at ${w}x$h, camera at $camDepth m")
                assertEquals(0f, p.y, TOLERANCE, "y at ${w}x$h, camera at $camDepth m")
            }
    }

    // Killed by: make CameraRig.pixelsPerMetre divide by VISIBLE_DEPTH_METRES * 2 -> 60 m of
    // water lands at h/2 instead of h.
    //
    // This is the case that carries aspect independence, and ONLY because DISPLAYS holds
    // genuinely different shapes: substituting the width for the height inside pixelsPerMetre
    // moves this pin on every non-square entry. Reduce DISPLAYS to a single aspect ratio and
    // this test rejoins the class of suites the class doc's §3.1 reference describes.
    @Test
    fun `exactly the visible depth of water fits between the top and bottom edges`()
    {
        for ((w, h) in DISPLAYS)
            for (camDepth in CAMERA_DEPTHS)
            {
                val p = screenPos(w, h, camDepth, 0f, camDepth + Framing.VISIBLE_DEPTH_METRES)
                assertEquals(w * 0.5f, p.x, TOLERANCE, "x at ${w}x$h, camera at $camDepth m")
                assertEquals(h, p.y, TOLERANCE, "the bottom edge is not ${Framing.VISIBLE_DEPTH_METRES} m " +
                             "below the camera at ${w}x$h, camera at $camDepth m")
            }
    }

    // Killed by: flip the sign of CameraRig.positionY -> every depth maps to the wrong screen row
    // except the one where `depth - camDepth` happens to be zero.
    @Test
    fun `the mapping is numerically identical to the transform it replaces`()
    {
        for ((w, h) in DISPLAYS)
            for (camDepth in CAMERA_DEPTHS)
                for (x in WORLD_XS)
                    for (depth in WORLD_DEPTHS)
                    {
                        val p = screenPos(w, h, camDepth, x, depth)

                        // The PRE-MIGRATION formulas, inlined rather than called: this is what
                        // Framing.screenX/screenY did at 231c6a6, and Task 5 deletes them. The
                        // migration is defined to be a no-op on screen, so any difference here is
                        // a bug in CameraRig and not a change of intent.
                        val pixelsPerMetre = h / Framing.VISIBLE_DEPTH_METRES
                        val oldX = w * 0.5f + x * pixelsPerMetre
                        val oldY = (depth - camDepth) * pixelsPerMetre

                        val where = "(x=$x, depth=$depth) at ${w}x$h, camera at $camDepth m"
                        assertEquals(oldX, p.x, TOLERANCE, "x differs from Framing.screenX for $where")
                        assertEquals(oldY, p.y, TOLERANCE, "y differs from Framing.screenY for $where")
                    }
    }

    /**
     * FRAME 1, WHICH IS THE ONE FRAME NO OTHER TEST HERE MODELS.
     *
     * `updateViewMatrix` does not read `scale`/`origin`/`position`. It reads each of them
     * interpolated from `scaleLast`/`originLast`/`positionLast` by `data.interpolation`
     * (Camera.kt:118-123), and the engine refreshes that snapshot only from `gfx.updateCameras()`
     * INSIDE a fixed step (PulseEngineImpl.kt:279). The very first frame runs no fixed step — the
     * accumulator has not filled — so it is drawn at factor 0, i.e. entirely from a snapshot
     * still holding a freshly-constructed camera's identity.
     *
     * This evaluates the engine's OWN `interpolateFrom` at t = 0 against a real `DefaultCamera`,
     * so it is frame 1 in every respect except that the matrix multiply is written out here
     * (`updateViewMatrix()` itself defaults t to `PulseEngine.INSTANCE.data.interpolation` and
     * `INSTANCE` is `lateinit ... internal set`, so it cannot be called).
     *
     * Killed by: drop `updateLastState()` from [CameraRig.snapTo] — the snapshot stays at the
     * constructed identity and the bottom-edge pin lands at y = 60 instead of y = h, which is
     * exactly the `1800.0 m of water is visible` warning this was found through.
     */
    @Test
    fun `a snapped camera already frames the visible depth at interpolation factor zero`()
    {
        for ((w, h) in DISPLAYS)
            for (camDepth in CAMERA_DEPTHS)
            {
                val camera = DefaultCamera.createOrthographic(w.toInt(), h.toInt())
                CameraRig.snapTo(camera, w, h, camDepth)

                val top = interpolatedScreenPos(camera, 0f, camDepth)
                val bottom = interpolatedScreenPos(camera, 0f, camDepth + Framing.VISIBLE_DEPTH_METRES)

                val where = "at ${w}x$h, camera at $camDepth m"
                assertEquals(w * 0.5f, top.x, TOLERANCE, "the camera's world point is not centred on frame 1 $where")
                assertEquals(0f, top.y, TOLERANCE, "the camera's world point is not at the top edge on frame 1 $where")
                assertEquals(h, bottom.y, TOLERANCE,
                             "frame 1 does not show ${Framing.VISIBLE_DEPTH_METRES} m of water $where — the " +
                             "engine's fixed-step snapshot was left at the constructed identity")
            }
    }

    /**
     * The other half of the same decision, and the reason [CameraRig.snap] is not simply what
     * [CameraRig.apply] does.
     *
     * The snapshot is the engine's memory of where the camera WAS; the interpolation between it
     * and the current value is what renders 60 Hz camera easing smoothly above 60 fps (the plan's
     * §1.5(2), and the reason the rig is driven from `onFixedUpdate` at all). Collapsing it every
     * tick would leave the interpolator nothing to interpolate and silently return the camera to
     * per-tick stepping.
     *
     * Killed by: add `updateLastState()` to [CameraRig.applyTo] — every mid-tick factor then
     * yields the destination instead of a point on the way to it, and 35 m stops being on the
     * top edge half way through.
     */
    @Test
    fun `apply leaves the engine an earlier state to interpolate from`()
    {
        val camera = DefaultCamera.createOrthographic(1600, 900)
        CameraRig.snapTo(camera, 1600f, 900f, 20f)
        CameraRig.applyTo(camera, 1600f, 900f, 50f)

        // Half way through the fixed step the camera must be half way between the two depths,
        // i.e. its world point — the one pinned to the top edge — must be 35 m down.
        val half = interpolatedScreenPos(camera, 0f, 35f, t = 0.5f)
        assertEquals(0f, half.y, TOLERANCE, "35 m is not at the top edge half way between camera depths 20 and 50")

        assertNotEquals(
            half.y,
            interpolatedScreenPos(camera, 0f, 35f, t = 1f).y,
            "the interpolation factor changes nothing — the snapshot and the current state are the same"
        )
    }

    /**
     * Screen position of a world point under the matrix the engine would build for [camera] at
     * interpolation factor [t], using the engine's own `interpolateFrom` and the operation order
     * of `DefaultCamera.updateViewMatrix` (Camera.kt:118-131).
     */
    private fun interpolatedScreenPos(camera: DefaultCamera, worldX: Float, worldY: Float, t: Float = 0f): Vector2f
    {
        val scale = camera.scale.interpolateFrom(camera.scaleLast, Vector3f(), t)
        val origin = camera.origin.interpolateFrom(camera.originLast, Vector3f(), t)
        val position = camera.position.interpolateFrom(camera.positionLast, Vector3f(), t)
        val m = Matrix4f()
            .identity()
            .translate(origin)
            .scale(scale)
            .translate(position.x - origin.x, position.y - origin.y, position.z - origin.z)
        val v = Vector4f(worldX, worldY, 0f, 1f).mul(m)
        return Vector2f(v.x, v.y)
    }

    private companion object
    {
        /**
         * The value of this list is that it holds 4:3, 16:9 and 21:9 — NOT that it holds six
         * resolutions. Six resolutions of one shape would be six copies of the same test.
         * (The plan's prose also claims 16:10; the list it specifies does not contain one, and
         * the list is reproduced here verbatim rather than quietly extended.)
         */
        val DISPLAYS = listOf(
            1200f to 900f,    // 4:3   — narrower than the column
            1600f to 900f,    // 16:9  — the booth's likely shape
            1920f to 1080f,   // 16:9
            2400f to 1800f,   // 4:3   — a Retina framebuffer from a declared 1200x900
            3440f to 1440f,   // 21:9  — the ultrawide the misalignment was reported on
            3840f to 2160f    // 16:9  — 4K
        )

        // Includes a negative depth: the camera sits VISIBLE_DEPTH_METRES * DIVER_SCREEN_FRACTION
        // above the diver (Framing.targetCameraDepth), so it is above the waterline whenever the
        // diver is near the surface — which is where every run starts and where attract mode sits.
        val CAMERA_DEPTHS = listOf(-24f, 0f, 61.75f)

        val WORLD_XS = listOf(-40f, -7.5f, 0f, 12.25f, 40f)
        val WORLD_DEPTHS = listOf(0f, 33f, 94.5f, 160f)

        // Absolute, in screen pixels, against coordinates that run to 3840. Float multiply-add
        // through a 4x4 matrix is not bit-exact against the same arithmetic written out longhand,
        // and the difference this must not mask — a dropped centring term, a doubled scale, a
        // flipped sign — is hundreds of pixels, not hundredths.
        const val TOLERANCE = 0.01f
    }
}
