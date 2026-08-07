package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the RULES. They cannot test the thing the rules are for.
 *
 * [CameraInvariants] is checked against numbers the ENGINE reports back from a real
 * framebuffer — `mainCamera.topLeftWorldPosition` / `bottomRightWorldPosition`, computed in
 * `GraphicsImpl.initFrame` from the matrix the frame will actually be drawn with. That is the
 * whole point of it: it is the one check in this codebase that can see a camera/surface
 * disagreement, and no unit test can perform it, because it needs a live GL context.
 *
 * So what is tested here is only that the rules fire when they should and stay quiet when
 * they should. The rules themselves are pure arithmetic over eight numbers; feeding them the
 * RIGHT eight numbers is [EnPustTil]'s job and is verified by looking at a running game.
 */
class CameraInvariantsTest
{
    // Numbers that satisfy all three rules: window == surface, exactly VISIBLE_DEPTH_METRES of
    // water visible, and a visible world rect with the surface's own 4:3 aspect.
    private val depth = Viewport.VISIBLE_DEPTH_METRES
    private val consistentWidth = depth * (1200f / 900f)

    private fun violations(
        windowWidth: Int = 1200,
        windowHeight: Int = 900,
        surfaceWidth: Int = 1200,
        surfaceHeight: Int = 900,
        worldTop: Float = 12f,
        worldBottom: Float = 12f + depth,
        worldLeft: Float = -consistentWidth * 0.5f,
        worldRight: Float = consistentWidth * 0.5f
    ) = CameraInvariants.violations(
        windowWidth, windowHeight, surfaceWidth, surfaceHeight,
        worldTop, worldBottom, worldLeft, worldRight
    )

    // Killed by: negate rule 1's comparison (`==` for `!=`) -> this reports a violation and fails.
    @Test
    fun `a consistent set of numbers is not a violation of anything`()
    {
        assertEquals(emptyList(), violations())
    }

    // Killed by: delete rule 1 -> no violation is reported and this fails. Rule 1 is the one that
    // would have caught the shipped bug's PRECONDITION (the surface resized under a frozen camera).
    @Test
    fun `a main surface that is not the window size is reported`()
    {
        val found = violations(surfaceWidth = 2400, surfaceHeight = 1800)

        assertEquals(1, found.size, "expected exactly the surface-size rule to fire, got: $found")
        assertTrue(found[0].contains("2400") && found[0].contains("1800"), "message omits the surface size: ${found[0]}")
        assertTrue(found[0].contains("1200") && found[0].contains("900"), "message omits the window size: ${found[0]}")
    }

    // Killed by: delete rule 2 -> nothing fires and this fails. This is THE rule: 60 m of water is
    // visible whatever the display is, and it is the check that would have caught the world
    // surface being silently scaled about (0, 0) the first time the framebuffer changed size.
    @Test
    fun `showing 87 metres of water instead of 60 is reported, naming both numbers`()
    {
        val found = violations(worldTop = 0f, worldBottom = 87f, worldLeft = -58f, worldRight = 58f)

        // The aspect rule fires too on these numbers (116 / 87 is not 4:3), which is correct and
        // not what this case is about — it asserts only that the depth rule is among them.
        val depthViolation = found.singleOrNull { it.contains("87") }
        assertTrue(depthViolation != null, "no violation names the visible depth 87: $found")
        assertTrue(depthViolation.contains("60"), "message omits the expected depth: $depthViolation")
    }

    // Killed by: delete rule 3 -> nothing fires and this fails. Rule 3 is the only automated check
    // anywhere that can catch CameraRig.apply deriving pixels-per-metre from the surface WIDTH
    // instead of its height — the aspect-blindness class that shipped. See CameraRigTest's doc.
    @Test
    fun `a visible world rect whose aspect is not the screen's is reported`()
    {
        // Surface is 4:3; the world rect handed back is 16:9. Depth is still exactly 60 m, so
        // rule 2 stays quiet and this isolates rule 3.
        val wide = depth * (16f / 9f)
        val found = violations(worldLeft = -wide * 0.5f, worldRight = wide * 0.5f)

        assertEquals(1, found.size, "expected exactly the aspect rule to fire, got: $found")
        assertTrue(found[0].contains("aspect"), "message does not say what is wrong: ${found[0]}")
    }

    // Killed by: widen VISIBLE_DEPTH_TOLERANCE_METRES to 1f -> 60.6 stops firing and this fails.
    // Killed by: narrow it to 0.1f -> 60.4 starts firing and this fails. Both directions pinned,
    // which is the only way a tolerance is actually tested rather than merely present.
    @Test
    fun `the visible-depth tolerance admits 60 point 4 metres and rejects 60 point 6`()
    {
        assertEquals(emptyList(), violations(worldTop = 0f, worldBottom = 60.4f, worldLeft = -40.4f, worldRight = 40.4f))
        assertTrue(
            violations(worldTop = 0f, worldBottom = 60.6f, worldLeft = -40.4f, worldRight = 40.4f).isNotEmpty(),
            "60.6 m of visible water is 0.6 m off and must be reported"
        )
    }

    // Killed by: widen ASPECT_TOLERANCE_FRACTION to 0.05f -> the 3% case stops firing and this
    // fails. Killed by: narrow it to 0.005f -> the 1% case starts firing and this fails.
    @Test
    fun `the aspect tolerance admits one percent of error and rejects three`()
    {
        val exact = depth * (1200f / 900f)

        assertEquals(
            emptyList(),
            violations(worldLeft = -exact * 1.01f * 0.5f, worldRight = exact * 1.01f * 0.5f),
            "1% of aspect error is measurement noise on a real framebuffer, not a bug"
        )
        assertTrue(
            violations(worldLeft = -exact * 1.03f * 0.5f, worldRight = exact * 1.03f * 0.5f).isNotEmpty(),
            "3% of aspect error means the world is being stretched and must be reported"
        )
    }

    // Killed by: remove the degenerate-rect guard -> the aspect rule divides by zero, produces
    // NaN, `NaN > tolerance` is false, and NOTHING is reported. That silent nothing is the
    // failure mode this case exists for: a frame mid-resize would look clean.
    @Test
    fun `a degenerate world rect is reported rather than divided by`()
    {
        val zeroHeight = violations(worldTop = 40f, worldBottom = 40f)
        assertTrue(zeroHeight.isNotEmpty(), "a zero-height world rect must be reported, not silently divided by")
        assertTrue(zeroHeight.any { it.contains("degenerate") }, "expected a degenerate-rect message, got: $zeroHeight")

        val inverted = violations(worldTop = 40f, worldBottom = -10f, worldLeft = 10f, worldRight = -10f)
        assertTrue(inverted.isNotEmpty(), "an inverted world rect must be reported")
        assertTrue(inverted.all { it.contains("degenerate") || it.contains("window") }, "unexpected messages: $inverted")
    }

    // Killed by: make the world-rect gate unconditional (drop the `if (!worldRectIsInMetres) return`)
    // -> the pre-Task-5 call site starts reporting the 1800 m "depth" of an identity camera every
    // second and this fails. The gate exists because EnPustTil wires this check in one task BEFORE
    // the camera is flipped to world coordinates; see CameraInvariants' doc.
    @Test
    fun `with the world rect still in screen pixels only the size rule is checked`()
    {
        val found = CameraInvariants.violations(
            windowWidth = 1200, windowHeight = 900,
            surfaceWidth = 1200, surfaceHeight = 900,
            // What an identity camera reports: the world rect IS the pixel rect.
            worldTop = 0f, worldBottom = 900f, worldLeft = 0f, worldRight = 1200f,
            worldRectIsInMetres = false
        )

        assertEquals(emptyList(), found)

        // ...and rule 1 still is, because it is true of the numbers regardless of what space the
        // world rect is in. Without this half, deleting rule 1 would also pass this case.
        assertTrue(
            CameraInvariants.violations(
                windowWidth = 1200, windowHeight = 900,
                surfaceWidth = 2400, surfaceHeight = 1800,
                worldTop = 0f, worldBottom = 1800f, worldLeft = 0f, worldRight = 2400f,
                worldRectIsInMetres = false
            ).isNotEmpty(),
            "the surface-size rule does not depend on the world rect and must still be checked"
        )
    }
}
