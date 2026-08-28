package render

import dive.Tuning
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiveCameraTest
{
    /** The clamp lands exactly on the boundary, so allow for float rounding. */
    private val EPS = 1e-5f

    /**
     * Where in the frame the diver sits, 0 at the top edge and 1 at the bottom.
     *
     * This was `Viewport.screenFraction(depth, cameraDepth)`, which went with the rest of that
     * object's transform when the world moved into metres — [Framing] deliberately owns no
     * mapping at all now, not even a resolution-free one, so that nothing can grow back into a
     * second coordinate authority beside [CameraRig]. Inlined here rather than reinstated: it is
     * one subtraction, only this file wants it, and every assertion below is unchanged.
     */
    private fun fractionOf(cam: DiveCamera, diverDepth: Float) =
        (diverDepth - cam.depth) / Framing.VISIBLE_DEPTH_METRES

    /**
     * NEW, AND PICKING THE WRONG ONE OF THE TWO MAKES A TEST GO RED ON CORRECT CODE.
     *
     * [fractionOf] above divides by the CONSTANT [Framing.VISIBLE_DEPTH_METRES], and every case
     * asserting membership in `[DIVER_MIN_FRACTION, DIVER_MAX_FRACTION]` must keep using it,
     * because those two constants are themselves defined against the constant 60 — `DiveCamera
     * .clampSoDiverStaysVisible` and `Framing.targetCameraDepth` both multiply the constant,
     * never the actual frame.
     *
     * THIS one divides by the ACTUAL visible depth: where in the frame a VIEWER sees the diver.
     * Only for assertions about what is on screen. At 16:9, `(160 - 129.514) / 55.415` = 0.550
     * while `(160 - 129.514) / 60` = 0.508 — so writing the settle case with the other helper
     * makes it fail on correct code.
     */
    private fun fractionOfFrame(cam: DiveCamera, diverDepth: Float, visibleDepthMetres: Float) =
        (diverDepth - cam.depth) / visibleDepthMetres

    @Test
    fun `snapTo puts the diver exactly at the target screen fraction`() {
        val cam = DiveCamera()
        cam.snapTo(50f, Framing.VISIBLE_DEPTH_METRES)
        assertEquals(Framing.DIVER_SCREEN_FRACTION, fractionOf(cam, 50f), 0.0001f)
    }

    @Test
    fun `the camera lags rather than tracking exactly`() {
        val cam = DiveCamera()
        cam.snapTo(0f, Framing.VISIBLE_DEPTH_METRES)
        val before = cam.depth

        cam.update(dt = 1f / 60f, diverDepth = 20f, visibleDepthMetres = Framing.VISIBLE_DEPTH_METRES)

        assertTrue(cam.depth > before, "camera must move toward the diver")
        assertTrue(
            cam.depth < Framing.targetCameraDepth(20f),
            "camera must NOT arrive instantly — that is the whole point of smoothing"
        )
    }

    @Test
    fun `the camera converges on the target when the diver stops`() {
        val cam = DiveCamera()
        cam.snapTo(0f, Framing.VISIBLE_DEPTH_METRES)
        repeat(300) { cam.update(1f / 60f, diverDepth = 40f, visibleDepthMetres = Framing.VISIBLE_DEPTH_METRES) }
        assertEquals(Framing.targetCameraDepth(40f), cam.depth, 0.01f)
    }

    @Test
    fun `easing is frame-rate independent`() {
        // Start inside the clamp band so the comparison measures easing, not clamping.
        val coarse = DiveCamera().apply { snapTo(50f, Framing.VISIBLE_DEPTH_METRES) }
        val fine = DiveCamera().apply { snapTo(50f, Framing.VISIBLE_DEPTH_METRES) }

        // Same wall-clock duration, very different step sizes.
        repeat(6) { coarse.update(1f / 60f, diverDepth = 55f, visibleDepthMetres = Framing.VISIBLE_DEPTH_METRES) }
        repeat(24) { fine.update(1f / 240f, diverDepth = 55f, visibleDepthMetres = Framing.VISIBLE_DEPTH_METRES) }

        assertEquals(coarse.depth, fine.depth, 0.05f,
            "60fps and 240fps must produce the same camera motion over the same time")
    }

    @Test
    fun `a diver descending fast drifts below the target line`() {
        val cam = DiveCamera()
        cam.snapTo(0f, Framing.VISIBLE_DEPTH_METRES)

        // Kick descent: roughly 18 m/s for half a second.
        var diverDepth = 0f
        repeat(30) {
            diverDepth += 18f * (1f / 60f)
            cam.update(1f / 60f, diverDepth, Framing.VISIBLE_DEPTH_METRES)
        }

        assertTrue(
            fractionOf(cam, diverDepth) > Framing.DIVER_SCREEN_FRACTION,
            "outrunning the camera should push the diver down the screen"
        )
    }

    @Test
    fun `the diver can never be pushed off screen by camera lag`() {
        val cam = DiveCamera()
        cam.snapTo(0f, Framing.VISIBLE_DEPTH_METRES)

        // Absurd velocity, far beyond anything the game can produce.
        var diverDepth = 0f
        repeat(120) {
            diverDepth += 200f * (1f / 60f)
            cam.update(1f / 60f, diverDepth, Framing.VISIBLE_DEPTH_METRES)
            val f = fractionOf(cam, diverDepth)
            assertTrue(
                f in (Framing.DIVER_MIN_FRACTION - EPS)..(Framing.DIVER_MAX_FRACTION + EPS),
                "diver at screen fraction $f escaped the visible band"
            )
        }
    }

    @Test
    fun `a fast ascent also keeps the diver on screen`() {
        val cam = DiveCamera()
        // Clamps to 124.930 m at V = 60 (Framing.SEA_FLOOR_DEPTH - VISIBLE_DEPTH_METRES), so the
        // starting fraction moves from the nominal 0.400 to 0.418 — still a band-membership
        // assertion below, so this is not a regression.
        cam.snapTo(150f, Framing.VISIBLE_DEPTH_METRES)

        var diverDepth = 150f
        repeat(120) {
            diverDepth -= 200f * (1f / 60f)
            cam.update(1f / 60f, diverDepth, Framing.VISIBLE_DEPTH_METRES)
            val f = fractionOf(cam, diverDepth)
            assertTrue(
                f in (Framing.DIVER_MIN_FRACTION - EPS)..(Framing.DIVER_MAX_FRACTION + EPS),
                "diver at screen fraction $f escaped the visible band while ascending"
            )
        }
    }

    @Test
    fun `a zero or negative timestep does not move or corrupt the camera`() {
        val cam = DiveCamera()

        // Settle the camera off-target for a diver at 30f (still inside the visible clamp
        // band, so the clamp itself is a no-op here and cannot mask what dt <= 0 does).
        // snapTo(30f) would put depth exactly on target, making (target - depth) zero and
        // the test blind to any t no matter how corrupt — see the mutation this replaced.
        cam.snapTo(10f, Framing.VISIBLE_DEPTH_METRES)
        val before = cam.depth

        cam.update(0f, 30f, Framing.VISIBLE_DEPTH_METRES)
        cam.update(-1f, 30f, Framing.VISIBLE_DEPTH_METRES)

        assertTrue(abs(cam.depth - before) < 0.0001f, "camera must be stable at dt <= 0")
    }

    /**
     * THE FRAME NEVER SHOWS PAST THE SANDBANK'S BOTTOM EDGE — SWEPT, AND NON-OPTIONAL.
     *
     * This is the most valuable case in this file. The design's §7.3 lists FOUR inputs that
     * break the camera guarantee — `Tuning.MAX_DEPTH` above 169.93 m, `VISIBLE_DEPTH_METRES`
     * above 99.7 m, `DIVER_MAX_FRACTION` below 0.5845 (headroom of only 0.166, and tightening
     * the lag band is an ordinary tuning change), and a shorter re-bake of the art — and
     * `SandBankTest`'s relationship assertion catches NONE of them: it ties the art to the
     * diver, which is a different property, and the re-bake case trips it once and then invites
     * the edit that silences it.
     *
     * This sweep catches all four, because it asserts the property DIRECTLY over its whole
     * domain instead of asserting a relationship between the constants that happen to produce
     * it today.
     *
     * SWEPT, NOT SPOT-CHECKED. The failure mode is aspect-dependent, and three sample aspects
     * is exactly what let five versions of the rock's frame-edge bug through — see
     * `RockFace.coverageOuterHalfWidth`'s doc. `V` sweeps the CONSTANT, never the literal 60,
     * so that raising `VISIBLE_DEPTH_METRES` moves the test with it.
     */
    @Test
    fun `the frame never paints past the sandbank's bottom edge, at any visible depth or diver depth`()
    {
        var v = 1f
        while (v <= Framing.VISIBLE_DEPTH_METRES + 1e-4f)
        {
            var d = 0f
            while (d <= Tuning.MAX_DEPTH + 1e-4f)
            {
                val eased = DiveCamera()
                eased.snapTo(0f, v)
                repeat(600) { eased.update(1f / 60f, d, v) }
                assertTrue(
                    eased.depth + v <= SandBank.QUAD_BOTTOM_DEPTH + EPS,
                    "eased to ${eased.depth} m with V = $v m at diver depth $d m: the frame bottom " +
                    "reaches ${eased.depth + v} m, past the sandbank's ${SandBank.QUAD_BOTTOM_DEPTH} m"
                )

                val snapped = DiveCamera()
                snapped.snapTo(d, v)
                assertTrue(
                    snapped.depth + v <= SandBank.QUAD_BOTTOM_DEPTH + EPS,
                    "snapped to ${snapped.depth} m with V = $v m at diver depth $d m: the frame bottom " +
                    "reaches ${snapped.depth + v} m, past the sandbank's ${SandBank.QUAD_BOTTOM_DEPTH} m"
                )
                d += 1f
            }
            v += 1f
        }
    }

    /**
     * THE ORDERING, NOT THE BOUND. `clampToSandBankFloor` runs BEFORE
     * `clampSoDiverStaysVisible`, so where the two disagree the diver's visibility wins: losing
     * the diver off the bottom of the frame is a broken game, showing a metre of void below the
     * sand is a blemish.
     *
     * They cannot in fact disagree at any aspect this game can be run at — the disagreement
     * threshold is `V > 69.93 m` at `d = 160`, and `V <= 60` always — which is why this case has
     * to CONSTRUCT the situation. The ordering is what makes the guarantee safe rather than
     * lucky.
     *
     * Uses [fractionOf], not [fractionOfFrame]: the band constants are defined against the
     * constant 60.
     */
    @Test
    fun `the floor clamp yields to the visibility clamp when they disagree`()
    {
        val impossibleV = 90f       // past the 69.93 m threshold; the real game cannot reach it
        val cam = DiveCamera()
        cam.snapTo(Tuning.MAX_DEPTH, impossibleV)
        repeat(600) { cam.update(1f / 60f, Tuning.MAX_DEPTH, impossibleV) }

        val f = fractionOf(cam, Tuning.MAX_DEPTH)
        assertTrue(
            f in (Framing.DIVER_MIN_FRACTION - EPS)..(Framing.DIVER_MAX_FRACTION + EPS),
            "diver at screen fraction $f escaped the visible band — the floor clamp ran after the " +
            "visibility clamp and overrode it"
        )
    }

    /**
     * `snapTo` clamps too. It did not before, because `targetCameraDepth` alone could not put the
     * camera anywhere illegal; it can now.
     */
    @Test
    fun `snapTo clamps to the sea floor as well`()
    {
        val cam = DiveCamera()
        cam.snapTo(Tuning.MAX_DEPTH, Framing.VISIBLE_DEPTH_METRES)

        assertEquals(
            Framing.SEA_FLOOR_DEPTH - Framing.VISIBLE_DEPTH_METRES, cam.depth, 0.01f,
            "snapTo at MAX_DEPTH must land on the floor limit, not on targetCameraDepth(160) = " +
            "${Framing.targetCameraDepth(Tuning.MAX_DEPTH)}"
        )
    }

    /**
     * THE BEHAVIOURAL CONSEQUENCE, PINNED at 16:9's V = 55.415 m. The camera stops descending
     * once the diver passes 153.51 m, and over the last 6.5 m the view holds still and the diver
     * settles down the frame from the nominal 40% of the 60 m design frame (43.3% of the actual
     * 55.4 m one) to 55.0%, standing on the sand.
     *
     * This is the assertion that would notice if the sand were moved without anyone looking at a
     * frame. It uses [fractionOfFrame] — the actual frame — because that is what a viewer sees.
     */
    @Test
    fun `the camera settles onto the seabed over the last stretch of the dive`()
    {
        val v = 55.415f     // 16:9 at 1920x1080

        val early = DiveCamera()
        early.snapTo(0f, v)
        repeat(600) { early.update(1f / 60f, 153.51f, v) }

        val late = DiveCamera()
        late.snapTo(0f, v)
        repeat(600) { late.update(1f / 60f, Tuning.MAX_DEPTH, v) }

        assertEquals(
            early.depth, late.depth, 0.05f,
            "the camera must stop descending past 153.51 m — it moved from ${early.depth} to ${late.depth}"
        )
        assertEquals(
            0.550f, fractionOfFrame(late, Tuning.MAX_DEPTH, v), 0.005f,
            "on the floor at 16:9 the diver must sit 55.0% down the frame; the sand has moved"
        )
    }

    /**
     * A DEGENERATE VISIBLE DEPTH MUST NOT POISON THE CAMERA. GLFW reports a minimized window as
     * 0x0, and `CameraRig.visibleDepthMetres(0f, 0f)` is `0f / 0f` = NaN. `minOf` propagates NaN,
     * `depth` becomes NaN, and every later `depth += (target - depth) * t` keeps it NaN forever:
     * the camera is permanently poisoned with no recovery short of a `snapTo`.
     */
    @Test
    fun `a NaN visible depth does not poison the camera`()
    {
        val guarded = DiveCamera()
        guarded.snapTo(10f, Framing.VISIBLE_DEPTH_METRES)
        val control = DiveCamera()
        control.snapTo(10f, Framing.VISIBLE_DEPTH_METRES)

        guarded.update(1f / 60f, 30f, Float.NaN)
        control.update(1f / 60f, 30f, Framing.VISIBLE_DEPTH_METRES)

        assertTrue(guarded.depth.isFinite(), "a NaN visible depth left the camera at ${guarded.depth}")
        assertEquals(control.depth, guarded.depth, 1e-4f, "a NaN visible depth must skip the floor clamp, not change the ease")

        // And the camera still works afterwards.
        repeat(600) { guarded.update(1f / 60f, 30f, Framing.VISIBLE_DEPTH_METRES) }
        assertEquals(Framing.targetCameraDepth(30f), guarded.depth, 0.01f, "the camera never recovered from the NaN frame")
    }
}
