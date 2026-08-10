package render

import dive.DiveInput
import dive.DiveSim
import dive.Zone
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DiveLighting` is mostly unmockable — `render()` drives a live `GlobalIlluminationSystem`
 * and issues immediate-mode `drawLight` calls through a real GL context. What IS pure — the
 * continuous pearl/diver light-intensity curves and the on-screen culling check — is
 * extracted and tested here. The zone-boundary continuity itself is `DepthBlend`'s
 * responsibility and is covered in `DepthBlendTest`; this file checks the concrete tables
 * `DiveLighting` feeds into it.
 */
class DiveLightingTest
{
    @Test
    fun `pearls shine harder the deeper the zone, so they stay legible against the darker ambient`()
    {
        val intensities = Zone.entries.map { DiveLighting.pearlIntensityForDepth(DepthBlend.zoneMidpoint(it)) }

        // Strictly increasing zone-midpoint by zone-midpoint — this is the whole point of the
        // curve: if the Abyss were not the brightest, pearls would wash out into the darkest
        // ambient. Sampled at each zone's own midpoint so this exercises the concrete tables,
        // not DepthBlend's boundary maths (see DepthBlendTest for that).
        for (i in 1 until intensities.size)
        {
            assertTrue(
                intensities[i] > intensities[i - 1],
                "zone ${Zone.entries[i]} (${intensities[i]}) must shine harder than " +
                    "${Zone.entries[i - 1]} (${intensities[i - 1]})"
            )
        }
    }

    @Test
    fun `the abyss pearl intensity is the brightest, matching pearls being the only light there`()
    {
        val abyss = DiveLighting.pearlIntensityForDepth(DepthBlend.zoneMidpoint(Zone.ABYSS))
        val others = Zone.entries.filter { it != Zone.ABYSS }
            .map { DiveLighting.pearlIntensityForDepth(DepthBlend.zoneMidpoint(it)) }
        assertTrue(others.all { it < abyss })
    }

    @Test
    fun `pearl intensity rises continuously with depth, with no step at a zone boundary`()
    {
        // The playtest complaint this whole rework answers: a hard switch exactly at a zone's
        // minDepth. Sample a hair either side of every boundary and require near-equality.
        val zones = Zone.entries
        for (i in 1 until zones.size)
        {
            val boundary = zones[i].minDepth
            val justAbove = DiveLighting.pearlIntensityForDepth(boundary - 0.01f)
            val justBelow = DiveLighting.pearlIntensityForDepth(boundary + 0.01f)
            assertTrue(
                kotlin.math.abs(justAbove - justBelow) < 0.01f,
                "pearl intensity jumped at the ${zones[i]} boundary: $justAbove -> $justBelow"
            )
        }
    }

    @Test
    fun `the diver's own light dims only in the abyss, where pearls should read as comparatively brighter`()
    {
        val abyss = DiveLighting.diverIntensityForDepth(DepthBlend.zoneMidpoint(Zone.ABYSS))
        val shallows = DiveLighting.diverIntensityForDepth(DepthBlend.zoneMidpoint(Zone.SHALLOWS))
        assertTrue(abyss < shallows, "diver light should be dimmer in the abyss than in the shallows")
    }

    // FOUR `isOnScreen` CASES LIVED HERE and were deleted with their subject. `isOnScreen` was a
    // screen-ROW bounds check with a 50-PIXEL margin, and there are no screen rows in
    // DiveLighting any more — the lights are drawn in world metres through the engine's camera.
    // Nothing culls in the meantime, deliberately: at most a few dozen lights, and a quad outside
    // the frustum is clipped by the GPU. Task 7 of
    // docs/superpowers/plans/2026-08-06-engine-world-coordinates.md brings culling back as
    // `cam.isInView(...)`, which needs a live camera and so will be covered by looking at a
    // capture rather than by a case here.

    // ---- Flashlight beam -------------------------------------------------------------
    //
    // These cover the cone maths described at length in DiveLighting.coneMaskPeak. The
    // numbers below are not arbitrary: they are what GI's own scene.frag computes, so a
    // failure here means the beam no longer matches the shader that draws it.

    /** A representative diver base intensity — the table value for every zone but the abyss. */
    private val base = 2f

    @Test
    fun `a 360 degree cone is GI's unattenuated omnidirectional case`()
    {
        // scene.frag guards its cone attenuation with `if (coneAngle < PI)`, and 360 degrees
        // is exactly PI after the metadata.r * PI decode — so the whole block is skipped and
        // every direction gets the full, unreduced radiance. That is what made the hovering
        // diver a symmetric bloom blob.
        assertEquals(1f, DiveLighting.coneMaskPeak(360f), 1e-6f)
    }

    @Test
    fun `a 180 degree cone is the exact forward hemisphere, needing no intensity correction`()
    {
        // Half-angle 90 degrees, so cos(halfAngle) is 0 and the mask collapses to plain
        // max(cos(theta), 0) — a Lambertian hemisphere whose on-axis peak is already 1.
        // This is the landmark that separates "lights something behind the diver" from
        // "does not", and it is why the hovering cone is kept at or below it.
        assertEquals(1f, DiveLighting.coneMaskPeak(180f), 1e-6f)
    }

    @Test
    fun `a narrow cone throttles its own peak, which is why a narrow beam needs more nominal intensity`()
    {
        // The counter-intuitive core of the whole defect: scene.frag's mask is
        // clamp(dot - cos(halfAngle), 0, 1), which is NOT normalised. At 50 degrees the very
        // brightest the cone can ever be is 1 - cos(25 degrees) = 0.0937 of nominal — so
        // narrowing a cone makes it DIMMER unless the intensity is scaled back up.
        assertTrue(
            DiveLighting.coneMaskPeak(50f) < 0.1f,
            "a 50-degree cone should peak near 0.094, was ${DiveLighting.coneMaskPeak(50f)}"
        )
        assertTrue(DiveLighting.coneMaskPeak(50f) < DiveLighting.coneMaskPeak(150f))
    }

    @Test
    fun `a hovering diver still casts a directional cone rather than an omnidirectional blob`()
    {
        // THE regression test for the reported defect. Standing still used to widen the cone
        // all the way to 360, throwing away the held heading and lighting the diver equally
        // in every direction.
        assertTrue(
            DiveLighting.beamConeAngle(0f) < 360f,
            "a hovering diver must still point the torch somewhere"
        )
    }

    @Test
    fun `the hovering cone lights nothing behind the diver, so it reads as a torch and not a halo`()
    {
        // At most the forward hemisphere (see the 180-degree test above). Anything wider
        // starts spilling light behind the diver, which is the symmetric-halo look again.
        assertTrue(
            DiveLighting.beamConeAngle(0f) <= 180f,
            "hovering cone was ${DiveLighting.beamConeAngle(0f)} degrees, which lights the diver's back"
        )
    }

    @Test
    fun `swimming focuses the beam - a moving diver's cone is narrower than a hovering diver's`()
    {
        assertTrue(DiveLighting.beamConeAngle(5f) < DiveLighting.beamConeAngle(0f))
    }

    @Test
    fun `at full focus the beam is exactly the 50-degree, 25x beam that was signed off in playtest`()
    {
        // The moving beam is known-good and must not regress. 25x at 50 degrees is what it
        // has always sent to drawLight; the normalisation below has to reproduce it exactly.
        assertEquals(50f, DiveLighting.beamConeAngle(1.5f), 1e-4f)
        assertEquals(base * 25f, DiveLighting.beamIntensity(base, 1.5f), 0.01f)
        assertEquals(base * 25f, DiveLighting.beamIntensity(base, 40f), 0.01f)
    }

    @Test
    fun `hovering is no brighter on-axis than the old omnidirectional light was`()
    {
        // The blob complaint is about total light, not about the diver going dark. Peak
        // on-axis radiance is intensity * coneMaskPeak, and holding that at the old omni
        // value (base * 1.0) means nothing anywhere on screen gets brighter than it is
        // today — the fix only ever removes light, from the sides and from behind.
        val peakRadiance = DiveLighting.beamIntensity(base, 0f) * DiveLighting.coneMaskPeak(DiveLighting.beamConeAngle(0f))
        assertEquals(base, peakRadiance, 0.01f)
    }

    @Test
    fun `cone and intensity are continuous across the stationary threshold, so the beam does not pop`()
    {
        // The old code branched hard at 1.5 m/s, so crossing it teleported the cone between
        // 50 and 360 degrees. Sample a hair either side and require near-equality.
        val justBelow = 1.5f - 0.001f
        val justAbove = 1.5f + 0.001f
        assertEquals(DiveLighting.beamConeAngle(justBelow), DiveLighting.beamConeAngle(justAbove), 0.5f)
        assertEquals(DiveLighting.beamIntensity(base, justBelow), DiveLighting.beamIntensity(base, justAbove), 0.5f)
    }

    /**
     * THE STATIONARY HOLD, WHICH IS NOW ALSO THE DIVER'S POSE.
     *
     * `updateAim` stops tracking below `STATIONARY_SPEED_THRESHOLD`, because `atan2` on a
     * near-zero velocity is noise rather than intent. That was already true of the torch; since
     * the sprite art landed, the diver's BODY is drawn rotated to the same heading
     * (`DiverSprite.bodyAngleFor`), so the hold is what stops him snapping to a new pose every
     * time the player lets go of the stick and the residual drift decides where he "faces".
     *
     * The fixture makes that observable rather than trivially true: the diver swims RIGHT while
     * carrying 20 units of ballast, then releases. The lateral velocity decays to nothing but the
     * load keeps him sinking at ~1.1 m/s forever — under the threshold, so a heading that kept
     * tracking would swing round from "right" to "straight down" and sit there. Held, it does not
     * move at all.
     */
    @Test
    fun `a diver who coasts to a stop keeps the heading he was last swimming at`()
    {
        val dt = 1f / 60f
        val sim = DiveSim(seed = 7L)
        sim.debugSetHeld(4, 20f)
        DiveLighting.resetAim()

        // Swim right until the aim has locked on.
        val right = DiveInput(horizontal = 1f, vertical = 0f, kick = false, bleed = false)
        repeat(180) { sim.tick(dt, right); DiveLighting.updateAim(sim, dt) }
        assertTrue(hypot(sim.vx, sim.vy) > 1.5f, "fixture: the diver must actually be swimming")

        // Let go, and keep going until the residual speed is under the threshold.
        var held = DiveLighting.beamHeadingDegrees
        repeat(600)
        {
            sim.tick(dt, DiveInput.NONE)
            DiveLighting.updateAim(sim, dt)
            if (hypot(sim.vx, sim.vy) >= 1.5f) held = DiveLighting.beamHeadingDegrees
        }

        assertTrue(hypot(sim.vx, sim.vy) < 1.5f, "fixture: the diver must have dropped under the threshold")
        assertTrue(sim.vy > 0.5f, "fixture: the ballast must still be pulling him DOWN, so a tracking heading would swing to -90")
        assertTrue(
            AimAngle.wrap(AimAngle.shortestDifference(-90f, held) + 360f) > 20f,
            "fixture: the held heading ($held) must be meaningfully different from straight down"
        )

        assertEquals(
            held, DiveLighting.beamHeadingDegrees, 1e-3f,
            "the aim must freeze once the diver is below the stationary threshold, not creep round toward his drift"
        )

        DiveLighting.resetAim()
    }
}
