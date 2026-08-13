package render

import dive.DiveInput
import dive.DiveSim
import dive.Tuning
import dive.Zone
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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
    /**
     * THE RAMP IS GONE, AND THIS IS THE TEST THAT REPLACED THE THREE THAT GUARDED IT.
     *
     * `pearlIntensityForDepth` ran 0.6 in the Shallows to 4.0 in the Abyss so that a pearl stayed
     * equally legible as the water darkened, and three tests here pinned that: strictly increasing
     * by zone, brightest in the Abyss, continuous across every boundary. The owner removed the
     * rule — *"I'd rather like the diver to have to find them using their flashlight"* — so all
     * three were asserting the opposite of the intent and were replaced rather than relaxed.
     *
     * What is left to protect is the NEW rule, and it is a stronger statement than the old one:
     * not "the ramp is gentle" but "there is no ramp at all, anywhere in the column".
     */
    @Test
    fun `nothing depth-dependent reaches a pearl light's intensity`()
    {
        // ASSERTED ON THE SOURCE, because the strongest form of "there is no ramp" is that there
        // is no depth to ramp over: pearlIntensity() takes no argument, so a value test can only
        // ever compare a constant with itself. What can still regress is the CALL SITE — someone
        // multiplying by a DepthBlend lookup, or passing pearl.depth into a new overload — and
        // that is what this catches.
        val source = File("src/main/kotlin/render/DiveLighting.kt").readText()
        val block = source.substringAfter("private fun drawPearlLights").substringBefore("\n    }")

        assertTrue(
            "intensity = pearlIntensity()," in block,
            "drawPearlLights no longer passes the flat pearlIntensity()"
        )
        assertFalse(
            "DepthBlend" in block,
            "a depth blend has reappeared in the pearl light — pearls brightening with depth is " +
                "exactly what the owner removed, so the torch stops being how you find them"
        )
    }

    /**
     * THE RULE THE OWNER ACTUALLY ASKED FOR, AND THE ONE A FLAT PEARL VALUE ALONE DID NOT BUY.
     *
     * Flattening the pearl ramp to its own shallow anchor (0.6) left every pearl rendering at the
     * same brightness whether the beam was on it or not: a pearl's emitter is inside its own drawn
     * disc and `radius = 0` removes the distance term, so a pearl's body always receives a flat
     * shelf of its own emission, and at 0.6 that shelf swamped anything the torch added. On a
     * capture at 85 m: *"it seems the pearls aren't affected by the light at all. They should be."*
     *
     * So what has to be pinned is the RATIO, not either value on its own — "the torch is what
     * reveals a pearl" is a statement about which of the two dominates. Both constants stay free to
     * be re-tuned; what cannot come back is a pearl that out-shines the thing meant to find it.
     */
    @Test
    fun `the torch overwhelms a pearl's own glow, so the beam is what reveals one`()
    {
        // COMPARED IN THE ONE UNIT, which is the point of this file's 2026-08-13 rework: the two
        // numbers used to be `drawLight` intensities with different cone widths and different
        // emitter sizes behind them, i.e. not the same quantity, and a ratio of two of those is
        // not a ratio of anything. Irradiance at one metre is comparable by construction.
        val pearl = DiveLighting.PEARL_IRRADIANCE_AT_ONE_METRE
        Zone.entries.forEach { zone ->
            val torch = DiveLighting.torchIrradianceForDepth(DepthBlend.zoneMidpoint(zone))
            assertTrue(
                torch > pearl * 10f,
                "in $zone the torch delivers $torch at one metre against a pearl's own $pearl — " +
                    "under 10x, a pearl lights its own body about as hard as the beam does and " +
                    "stops responding to it"
            )
        }
    }

    @Test
    fun `the torch is brightest where there is no daylight left, not dimmest`()
    {
        // The exact inversion of the rule this replaced. `the diver's own light dims only in the
        // abyss, where pearls should read as comparatively brighter` asserted abyss < shallows —
        // correct while pearls were the deep's light source, and backwards the moment the torch
        // became it. Asserted as monotone across every zone rather than just at the ends, because
        // the curve is derived from the ambient table and a re-tune there moves all of it.
        val byZone = Zone.entries.map { DiveLighting.torchIrradianceForDepth(DepthBlend.zoneMidpoint(it)) }
        for (i in 1 until byZone.size)
        {
            assertTrue(
                byZone[i] >= byZone[i - 1],
                "the torch is weaker in ${Zone.entries[i]} (${byZone[i]}) than in " +
                    "${Zone.entries[i - 1]} (${byZone[i - 1]}) — it fades exactly where it becomes " +
                    "the only light"
            )
        }
        assertTrue(
            byZone.last() > byZone.first() * 2f,
            "the Abyss torch (${byZone.last()}) is not meaningfully stronger than the surface's " +
                "(${byZone.first()}), so nothing replaces the daylight that is gone"
        )
    }

    @Test
    fun `the torch at the surface is unchanged, so the shallows were not re-lit`()
    {
        // The deep was the complaint; brightening the Shallows would be a change nobody asked for,
        // and the derivation is built to make the surface a fixed point (1 - daylight = 0 there).
        //
        // 6 IS NOT A NEW NUMBER, and writing it as the product it is rather than as `6f` is what
        // makes that checkable. The torch used to hand `drawLight` a nominal 2.0 corrected by
        // `TORCH_BALANCE_SIZE_METRES / DIVER_LIGHT_SIZE_METRES` for its emitter's size; the same
        // light in the 2026-08-13 unit is that nominal times the balance size, because irradiance
        // at one metre is `intensity x size x coneMaskPeak` and the surface beam's cone peak is 1.
        val nominalBeforeTheUnit = 2.0f
        val emitterSizeTheBalanceWasTunedAt = 3.0f
        assertEquals(
            nominalBeforeTheUnit * emitterSizeTheBalanceWasTunedAt,
            DiveLighting.torchIrradianceForDepth(0f), 1e-4f,
            "the torch no longer delivers what its original surface value did"
        )
    }

    /**
     * THE CONVERSION IS ITS OWN INVERSE, WHICH IS THE WHOLE CLAIM THE UNIT MAKES.
     *
     * `intensityFor` is the only place a design quantity becomes a `drawLight` argument, and the
     * property that makes it a UNIT rather than a fudge factor is that it round-trips: state an
     * irradiance, hand the resulting intensity to a light of any size and cone, and the irradiance
     * that light delivers at one metre is the one you stated. Asserted across the three cone widths
     * and both emitter sizes actually in the game, because the failure this catches — a size or a
     * cone term dropped from one side — is invisible at any single pairing.
     */
    @Test
    fun `stating an irradiance and reading it back gives the same number at every size and cone`()
    {
        for (irradiance in listOf(0.0075f, 0.18f, 6f, 18f))
            for (size in listOf(0.35f, 1.2f, 0.85f))
                for (cone in listOf(50f, 150f, 360f))
                {
                    val intensity = DiveLighting.intensityFor(irradiance, size, cone)
                    assertEquals(
                        irradiance,
                        DiveLighting.irradianceAtOneMetre(intensity, size, cone), irradiance * 1e-4f,
                        "a light of ${size}m at $cone degrees asked for $irradiance at one metre and " +
                        "delivers something else — the two halves of the unit have come apart"
                    )
                }
    }

    /**
     * THE FOUR LIGHTS ARE ONE ANCHOR AND THREE RATIOS, and that is asserted as an identity rather
     * than as four decimals, because four decimals is exactly the arrangement this replaced.
     *
     * `docs/superpowers/plans/2026-08-13-one-world-model.md` §1.4: *"EVERY INTENSITY WAS TUNED
     * AGAINST A DIFFERENT REFERENCE [...] No two of these numbers are in the same unit, so no two
     * can be reasoned about together — which is why each of the last four days' fixes moved one and
     * broke the balance with another."* The defence against that returning is that only the torch
     * is a free number: re-tune it and the other three follow, and adding a fifth light means
     * choosing its fraction rather than choosing its brightness.
     */
    @Test
    fun `every light's brightness is a stated fraction of the torch's`()
    {
        assertEquals(
            DiveLighting.TORCH_ABYSS_IRRADIANCE * DiveLighting.PEARL_FRACTION_OF_TORCH,
            DiveLighting.PEARL_IRRADIANCE_AT_ONE_METRE, 1e-6f,
            "a pearl's brightness has stopped being a fraction of the torch's and become a number " +
            "of its own — which is how the pearls and the beam came to be untunable together"
        )
        assertEquals(
            DiveLighting.PEARL_IRRADIANCE_AT_ONE_METRE * DiveLighting.MOTE_FRACTION_OF_PEARL,
            DiveLighting.MOTE_IRRADIANCE_AT_ONE_METRE, 1e-9f,
            "a mote's brightness has stopped being a fraction of a pearl's"
        )
        // And the ORDER, which is the design requirement the ratios exist to express: the torch is
        // the deep's primary light (spec §17), a pearl is a marker glow, a mote is suspended matter.
        assertTrue(
            DiveLighting.MOTE_IRRADIANCE_AT_ONE_METRE < DiveLighting.PEARL_IRRADIANCE_AT_ONE_METRE,
            "a mote is no longer dimmer than a pearl"
        )
        assertTrue(
            DiveLighting.PEARL_IRRADIANCE_AT_ONE_METRE < DiveLighting.TORCH_ABYSS_IRRADIANCE,
            "a pearl is no longer dimmer than the torch it is meant to be found with"
        )
    }

    /**
     * A MOTE'S CAST NO LONGER DEPENDS ON HOW BIG IT HAPPENS TO BE.
     *
     * Motes run 0.35 m to 0.85 m, and a light's cast is linear in its emitter's size — so the old
     * flat `Motes.GLOW_INTENSITY` made the largest mote cast 2.4x what the smallest did, on top of
     * the alpha spread that is supposed to be the field's only variation. `MotesTest.the glowing
     * subset is decorrelated from how big a mote looks` guards the same intent one level up (glow
     * must not track size); this is the half of it the lighting owns.
     */
    @Test
    fun `every mote delivers the same light whatever size it happens to be`()
    {
        val alpha = Motes.MOTE_ALPHA
        val reference = DiveLighting.irradianceAtOneMetre(
            DiveLighting.moteIntensity(Motes.MIN_SIZE_METRES, alpha), Motes.MIN_SIZE_METRES, 360f
        )
        for (size in listOf(Motes.MIN_SIZE_METRES, 0.5f, 0.7f, Motes.MAX_SIZE_METRES))
        {
            assertEquals(
                reference,
                DiveLighting.irradianceAtOneMetre(DiveLighting.moteIntensity(size, alpha), size, 360f),
                reference * 1e-4f,
                "a ${size}m mote casts a different amount of light than a ${Motes.MIN_SIZE_METRES}m one"
            )
        }
        // And a mote at the field's peak alpha is exactly the stated fraction of a pearl — the
        // normalisation by MOTE_ALPHA is what makes that comparison mean anything.
        assertEquals(
            DiveLighting.MOTE_IRRADIANCE_AT_ONE_METRE, reference, 1e-9f,
            "a mote at full alpha no longer delivers MOTE_IRRADIANCE_AT_ONE_METRE"
        )
        // And it is PROPORTIONAL to the alpha, not merely "less than". A strict inequality here
        // passed against a moteIntensity that ignored alpha altogether, on float rounding alone:
        // `(E/0.35)*0.35` and `(E/0.5)*0.5` are not the same Float, and one of them happened to be
        // the smaller. The relationship is what has to be asserted.
        assertEquals(
            reference * 0.5f,
            DiveLighting.irradianceAtOneMetre(DiveLighting.moteIntensity(0.5f, alpha * 0.5f), 0.5f, 360f),
            reference * 1e-4f,
            "a mote drawn at half alpha no longer emits half as much. The alpha is what stops a " +
            "mote lighting the water from a depth at which the mote itself is invisible"
        )
    }

    @Test
    fun `the anglerfish lure emits exactly what a pearl does`()
    {
        // The design's tell is motion and never light. One function serves both call sites, so
        // this is checking that nothing has introduced a second constant beside it.
        val source = File("src/main/kotlin/render/DiveLighting.kt").readText()
        val lureBlock = source.substringAfter("private fun drawAnglerfishLight")
            .substringBefore("\n    }")
        assertTrue(
            "intensity = pearlIntensity()" in lureBlock,
            "the anglerfish lure no longer takes its intensity from pearlIntensity(), so the lure " +
                "can be told from a pearl by brightness alone"
        )
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
        //
        // READ BACK THROUGH THE UNIT rather than compared with a bare intensity: `base` is an
        // irradiance at one metre since 2026-08-13, and `beamIntensity` now divides by the
        // emitter's size as well as by the cone mask, so `base * 25` is no longer the number the
        // shader is handed. What has to be preserved is the light DELIVERED, which is what
        // irradianceAtOneMetre reads back — and it is unchanged to the last bit.
        assertEquals(50f, DiveLighting.beamConeAngle(1.5f), 1e-4f)
        val focused = base * 25f * DiveLighting.coneMaskPeak(50f)
        listOf(1.5f, 40f).forEach { speed ->
            assertEquals(
                focused,
                DiveLighting.irradianceAtOneMetre(
                    DiveLighting.beamIntensity(base, speed), DiveLighting.DIVER_LIGHT_SIZE_METRES, 50f
                ), 0.01f,
                "the focused beam at ${speed}m/s no longer delivers the 25x-at-50-degrees it did"
            )
        }
    }

    @Test
    fun `hovering is no brighter on-axis than the old omnidirectional light was`()
    {
        // The blob complaint is about total light, not about the diver going dark. Holding the
        // hovering beam's on-axis delivery at the old omnidirectional value (base * 1.0) means
        // nothing anywhere on screen gets brighter than it was — the fix only ever removes light,
        // from the sides and from behind.
        val delivered = DiveLighting.irradianceAtOneMetre(
            DiveLighting.beamIntensity(base, 0f),
            DiveLighting.DIVER_LIGHT_SIZE_METRES,
            DiveLighting.beamConeAngle(0f)
        )
        assertEquals(base, delivered, 0.01f)
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

    /**
     * THE REGRESSION THIS WHOLE EPISODE WAS, HELD AS A BUILD-TIME ASSERTION.
     *
     * A light's emitter quad is a REGION that rasterises into the scene. `fd036f7` tied the
     * torch's quad to `Framing.DIVER_HEIGHT_METRES` — sound reasoning for a lamp centred on a
     * body, wrong for a torch — and the emitter became as tall as the swimmer, which is how the
     * player came to see a hard-edged rectangle around him rather than a light. Rounding it only
     * made the rectangle a circle; the size was the defect.
     *
     * So: the torch's emitter must stay far smaller than the diver, and it must not be expressed
     * as a fraction of him. Half the body height is a generous bound — the real value is a fifth
     * of it — chosen so this fires on the specific mistake (re-tying it to the body) rather than
     * on a legitimate re-tune.
     */
    @Test
    fun `the torch emits from a quad far smaller than the diver, not from one the size of him`()
    {
        assertTrue(
            DiveLighting.DIVER_LIGHT_SIZE_METRES < Framing.DIVER_HEIGHT_METRES * 0.5f,
            "the torch's emitter is ${DiveLighting.DIVER_LIGHT_SIZE_METRES}m against a ${Framing.DIVER_HEIGHT_METRES}m diver — " +
            "at body scale the emitter quad reads as a hard-edged patch of light instead of as a source"
        )
    }

    /**
     * THE SAME REGRESSION, FOR THE PEARLS — the half of it `006512b` did not do.
     *
     * The torch was shrunk and the pearls were left at a 3 m quad around a 1.2 m body, which the
     * owner reported as a faintly visible box of lifted water about 2.5x the pearl's diameter,
     * clearest near the surface where ambient is high enough for its rim to read. It is not a
     * shape problem — `LightEmitter` already makes the emitter round — it is that the inside of an
     * emitter is a FLAT SHELF of irradiance with a hard rim (the local SDF is signed, so a probe
     * inside takes a zero first step and samples the emitter at its own texel), so any part of the
     * quad sticking out past the body is a visible region. Confirmed by capture: the shelf's
     * diameter tracks the constant one for one (12 m asked, 11.7 m measured).
     *
     * ## "FITS INSIDE" WAS TOO WEAK A BOUND, AND THIS IS THE STRONGER ONE
     *
     * This asserted `emitter <= drawn silhouette`, which 1.2 m against 1.354 m satisfies — while
     * covering **79% of the pearl's AREA**, so four fifths of every pearl was the flat shelf and a
     * pearl was its own light from rim to rim whatever the world was doing. That is half of the
     * *"a pearl looks identical at every depth"* review finding.
     *
     * What is required is that the emitter is a CORE: at most half the silhouette's diameter, so
     * at least three quarters of the pearl's area is outside its own full-brightness zone and is
     * therefore lit by the world. Stated against `equalAreaQuad(PEARL_SIZE_METRES)` rather than
     * typed, so re-tuning the pearl's size or the equal-area scale moves the bound with it.
     */
    @Test
    fun `a pearl's emitter is a core inside the pearl and not the whole of it`()
    {
        val drawnDiameter = IridescenceRenderer.equalAreaQuad(Framing.PEARL_SIZE_METRES)
        val areaFraction = (DiveLighting.PEARL_LIGHT_SIZE_METRES / drawnDiameter).let { it * it }

        assertTrue(
            DiveLighting.PEARL_LIGHT_SIZE_METRES <= drawnDiameter * 0.5f,
            "a pearl's emitter is ${DiveLighting.PEARL_LIGHT_SIZE_METRES}m across a pearl drawn " +
            "${drawnDiameter}m across — ${(areaFraction * 100).toInt()}% of its area is the " +
            "emitter's own flat shelf of irradiance, which is the same brightness at every depth " +
            "and in or out of the torch beam. It has to be a core, not the pearl"
        )
    }

    /**
     * THE OTHER HALF OF THE SAME FINDING: **R IS THE RADIUS OF FULL BRIGHTNESS, so a reach larger
     * than the object is a falloff that never happens.**
     *
     * `PEARL_REACH_METRES` was 3 m against a pearl whose drawn radius is 0.677 m, so every pixel
     * of a pearl — and everything within two more pearl-widths of it — sat inside its own
     * `clamp(..., 0, 1)` ceiling. The distance term was present, correctly converted
     * ([DiveLighting.falloffRadius]) and switched on at a distance nothing in the picture reached.
     * Measured consequence: peak luminance 129.4 at 75 m and 130.4 at 140 m while the water around
     * it fell from 1.58 to 0.29.
     *
     * The relationship asserted is the one that makes a pearl read as a core with a halo: its own
     * light must have fallen off SUBSTANTIALLY by the time it crosses its own silhouette. Both
     * radii are derived from the drawn geometry, so this cannot be satisfied by re-typing a
     * number that no longer relates to the pearl.
     */
    @Test
    fun `a pearl's own light falls off across its own body`()
    {
        val bodyRadius = IridescenceRenderer.equalAreaQuad(Framing.PEARL_SIZE_METRES) * 0.5f
        val atTheRim = DiveLighting.attenuationAt(bodyRadius, DiveLighting.PEARL_REACH_METRES)

        assertTrue(
            atTheRim < 0.25f,
            "a pearl's rim still receives $atTheRim of its core's brightness — its reach " +
            "(${DiveLighting.PEARL_REACH_METRES}m) is not short enough against its own ${bodyRadius}m " +
            "body radius for the falloff to happen anywhere a player can see it, so the pearl is a " +
            "flat disc of its own emission again"
        )

        // ...and the core itself must still BE a core: full brightness out to the emitter's own
        // rim, or the bright middle a player picks out at range is not there either.
        assertEquals(
            1f,
            DiveLighting.attenuationAt(DiveLighting.PEARL_LIGHT_SIZE_METRES * 0.5f, DiveLighting.PEARL_REACH_METRES),
            1e-6f,
            "the pearl's own emitter rim is already past its full-brightness radius, so there is no " +
            "bright core left — only a halo"
        )
    }

    /**
     * THE UNIT'S ONE DOCUMENTED EXCEPTION, PINNED SO IT CANNOT DRIFT.
     *
     * [DiveLighting.irradianceAtOneMetre] means "what this light delivers a metre away" for every
     * light whose reach is at least a metre, because both geometric terms are exactly 1 there. A
     * pearl's reach is now 0.25 m — deliberately shorter than the pearl — so a pearl delivers
     * `(0.25)^2 = 1/16` of its stated value at one metre, and its stated value is a statement
     * about the SOURCE rather than about what arrives.
     *
     * That is a real hole in the one-unit model and it is documented on [intensityFor] rather than
     * papered over with a compensating multiplier (which would multiply the pearl's own core
     * radiance by sixteen and undo the fix). What this asserts is that the exception is exactly
     * one light and that its factor is the one written down — so a future reach change either
     * keeps the arithmetic or comes here and restates it.
     */
    @Test
    fun `the one-metre unit is exact for every light except the pearl, whose factor is stated`()
    {
        listOf(
            "torch" to DiveLighting.TORCH_REACH_METRES,
            "mote" to DiveLighting.MOTE_REACH_METRES
        ).forEach { (name, reach) ->
            assertEquals(
                1f, DiveLighting.attenuationAt(1f, reach), 1e-6f,
                "the $name's reach (${reach}m) is under a metre, so `irradianceAtOneMetre` no " +
                "longer means what it says for it — see intensityFor's documented exception"
            )
        }

        assertEquals(
            1f / 16f, DiveLighting.attenuationAt(1f, DiveLighting.PEARL_REACH_METRES), 1e-6f,
            "a pearl delivers a different fraction of its stated one-metre irradiance than the " +
            "sixteenth intensityFor's doc states; the doc is the only place this exception is " +
            "written down, so it has to be true"
        )
    }

    /**
     * HOW FAR A LIGHT REACHES IS NOT HOW BIG ITS EMITTER IS, and the cull margin is the one place
     * that used to confuse them: it read `= PEARL_LIGHT_SIZE_METRES`, which was harmless only
     * while that happened to be 3 m. Shrinking the emitter would have dragged the margin down
     * with it and begun culling pearls that are a metre off screen and still lighting the water
     * that is on it — a pop while panning, invisible in any still.
     *
     * The relationship asserted is that the margin outlives the emitter: it must stay clear of
     * BOTH emitter sizes by a wide factor.
     *
     * WHICH LIGHT THE MARGIN IS FOR HAS CHANGED HANDS, and the constant's own doc records it. It
     * used to be justified by the pearls' 3 m reach and the 8% of a pearl's one-metre value that
     * was measured on the water 3.2 m away. At `PEARL_REACH_METRES` = 0.25 m that is 0.7%, so the
     * margin is now the TORCH's — the one light that reaches across the frame — and this test is
     * left guarding the thing it was always really about: that the number is not re-derived from
     * whichever emitter happens to be handy.
     */
    @Test
    fun `the cull margin is not tied to an emitter's size, because reach and size are independent`()
    {
        listOf(
            "pearl" to DiveLighting.PEARL_LIGHT_SIZE_METRES,
            "torch" to DiveLighting.DIVER_LIGHT_SIZE_METRES
        ).forEach { (name, size) ->
            assertTrue(
                DiveLighting.LIGHT_CULL_MARGIN_METRES >= size * 2f,
                "the cull margin (${DiveLighting.LIGHT_CULL_MARGIN_METRES}m) has followed the " +
                "$name emitter (${size}m) down; a light's reach is set by its intensity too, and " +
                "with radius = 0 there is no falloff to make an off-screen one negligible"
            )
        }
    }

    /**
     * SHRINKING THE EMITTER MUST NOT CHANGE WHAT IT CASTS, and the relationship rather than the
     * resulting number is what is asserted — the failure this exists to catch is someone moving
     * the emitter's size and leaving a hand-typed multiplier behind, which is exactly what
     * `fd036f7` did in the other direction.
     *
     * The physics: a probe's irradiance from a light is the fraction of its rays that hit it,
     * which is proportional to the light's angular size, i.e. to `size / distance`. `size *
     * intensity` is therefore conserved at every distance — which is precisely why the one unit is
     * `intensity x size x coneMaskPeak` and why this now follows from the unit rather than from a
     * `TORCH_SIZE_COMPENSATION` someone has to remember to move.
     */
    @Test
    fun `the torch's cast is conserved when its emitter is resized`()
    {
        val surface = DiveLighting.torchIrradianceForDepth(0f)
        for (size in listOf(0.4f, 1.2f, 3f, 6f, 9f))
        {
            assertEquals(
                surface,
                size * DiveLighting.intensityFor(surface, size, 360f), 1e-3f,
                "an emitter of ${size}m casts a different amount of light than the beam's stated $surface"
            )
        }
    }

    // ---- Distance falloff ------------------------------------------------------------
    //
    // Every drawLight passed `radius = 0f` until 2026-08-13, which in `radiance_cascades.frag` is
    // not "unbounded radius" but "skip the distance term entirely". These cover the conversion that
    // switched it on. See DiveLighting.falloffRadius for the derivation off the shader source.

    /**
     * THE ONE PROPERTY THE WHOLE CONVERSION EXISTS FOR: a light's reach in METRES must not depend
     * on the framebuffer.
     *
     * `radiance_cascades.frag`'s attenuation is `clamp(radius * camScale / dist^2, 0, 1)` with
     * `dist` in FRAMEBUFFER PIXELS, so `radius` has dimension length^2 x pixels/length and a
     * hardcoded one would give the booth panel a vote on how far the torch shines. `CLAUDE.md`
     * forbids that in the strongest terms it uses, and the failure would be invisible on the
     * machine it was tuned on.
     *
     * Asserted by reproducing the shader's own expression at four wildly different framebuffers and
     * requiring the same answer in metres. Not a restatement of `falloffRadius`: this multiplies by
     * `camScale` and divides by `(d * camScale)^2` exactly as the shader does, so a `falloffRadius`
     * that forgot the scale, squared the wrong term, or used a length would fail here.
     */
    @Test
    fun `a light's reach in metres is the same at every framebuffer size`()
    {
        val framebuffers = listOf(
            1600f to 900f,      // the dev window
            3200f to 1800f,     // the same window on a Retina display
            3840f to 2160f,     // a 4K booth panel
            3440f to 1440f      // an ultrawide, which is a different camera REGIME (see CameraRig)
        )
        for (reachMetres in listOf(1f, 3f, 24f))
            for ((w, h) in framebuffers)
            {
                val camScale = CameraRig.pixelsPerMetre(w, h)
                val radius = DiveLighting.falloffRadius(reachMetres, camScale)
                for (distanceMetres in listOf(0.5f, 1f, 5f, 20f, 60f))
                {
                    // radiance_cascades.frag:125-126, verbatim, with dist in framebuffer pixels.
                    val distPixels = distanceMetres * camScale
                    val shader = (radius * camScale / (distPixels * distPixels)).coerceIn(0f, 1f)
                    assertEquals(
                        DiveLighting.attenuationAt(distanceMetres, reachMetres), shader, 1e-4f,
                        "a ${reachMetres}m light attenuates differently at ${w}x$h than the model " +
                        "says — its reach in metres depends on the display, which is the exact " +
                        "failure the conversion exists to prevent"
                    )
                }
            }
    }

    @Test
    fun `a light is at full brightness inside its reach and falls off as the inverse square outside`()
    {
        // Inside: exactly 1, so switching the falloff on cannot brighten anything — it only ever
        // removes light from beyond a light's stated reach. That is what makes this step safe to
        // land before the daylight floor, and it is why the deep is expected to stay dark.
        assertEquals(1f, DiveLighting.attenuationAt(0f, 10f), 1e-6f)
        assertEquals(1f, DiveLighting.attenuationAt(1f, 10f), 1e-6f)
        assertEquals(1f, DiveLighting.attenuationAt(10f, 10f), 1e-6f)

        // Outside: (R/d)^2. Doubling the distance quarters it.
        assertEquals(0.25f, DiveLighting.attenuationAt(20f, 10f), 1e-6f)
        assertEquals(0.0625f, DiveLighting.attenuationAt(40f, 10f), 1e-6f)
    }

    /**
     * `radius` REACHES THE SHADER THROUGH A HALF FLOAT, AND SATURATING IT IS SILENT.
     *
     * `scene.frag`'s last line packs it into `metadata.a`, and `gi_local_scene` is created with
     * `createSurface`'s default `textureFormat`, which is RGBA16F. Above 65504 a half float is
     * +inf, `inf / d^2` clamps to 1, and the light quietly goes back to having no falloff at all —
     * no GL error, no log, and nothing visible except at whatever resolution crosses the bound.
     *
     * The bound is genuinely reachable rather than theoretical: at 8K a 30 m reach is 66 240.
     */
    @Test
    fun `no light's radius can saturate the half float it is packed into`()
    {
        val hugestPlausiblePanel = CameraRig.pixelsPerMetre(7680f, 4320f)
        val reaches = listOf(
            "torch" to DiveLighting.TORCH_REACH_METRES,
            "pearl" to DiveLighting.PEARL_REACH_METRES,
            "mote" to DiveLighting.MOTE_REACH_METRES
        )
        reaches.forEach { (name, reach) ->
            assertTrue(
                reach <= DiveLighting.MAX_REACH_METRES,
                "the $name reach (${reach}m) is above MAX_REACH_METRES (${DiveLighting.MAX_REACH_METRES}m)"
            )
            assertTrue(
                DiveLighting.falloffRadius(reach, hugestPlausiblePanel) < HALF_FLOAT_MAX,
                "the $name light's radius is ${DiveLighting.falloffRadius(reach, hugestPlausiblePanel)} " +
                "at 8K, which a half float rounds to infinity — the falloff silently switches off"
            )
        }
        // And the cap is not slack: 25% more reach than the cap allows really does saturate, so
        // this is a bound someone could cross rather than a comfortable margin.
        assertTrue(
            DiveLighting.falloffRadius(DiveLighting.MAX_REACH_METRES * 1.25f, hugestPlausiblePanel) > HALF_FLOAT_MAX,
            "MAX_REACH_METRES is far below where the half float actually saturates, so it is not " +
            "the bound it claims to be and the comment on it is wrong"
        )
    }

    /**
     * EVERY LIGHT IN THE GAME HAS A REACH, AND THE CONVERSION HAPPENS IN EXACTLY ONE PLACE.
     *
     * Asserted on the source, because what is being checked is a property of the four `drawLight`
     * CALL SITES and there is no way to run one without a GL context — the same instrument, and the
     * same reasoning, as `MainCameraOwnershipTest` and `DrawTest`.
     *
     * Two halves, and both are the thing that actually goes wrong. `radius = 0f` is the state the
     * whole of `2026-08-13-one-world-model.md` §1.1 is about — *"a pearl 50 m away contributes
     * exactly what a pearl 2 m away does"* — and it is one careless copy-paste away at any new
     * light. And a `radius` computed anywhere but [DiveLighting.falloffRadius] is a second place
     * where a metre becomes a pixel count, which is the shape of `6ea1f53`'s shipped bug.
     */
    @Test
    fun `every drawLight states a reach, and only falloffRadius converts one`()
    {
        val source = stripComments(File("src/main/kotlin/render/DiveLighting.kt").readText())

        // Anchored at the start of a line, which is what makes it a named ARGUMENT rather than any
        // assignment: `BloomEffect().apply { ...; radius = 0f; ... }` in `setup` is a different
        // `radius` entirely and an unanchored scan reports it, and the mote loop's hoisted
        // `val radius = ...` is a definition rather than a call site.
        val radiusArguments = Regex("^\\s*radius = (.+)$", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1].trim() }.toList()
        assertEquals(
            4, radiusArguments.size,
            "expected exactly four drawLight radius arguments (torch, pearl, lure, mote), found " +
            "$radiusArguments — a light has been added or removed without this test being read"
        )
        radiusArguments.forEach { argument ->
            assertTrue(
                argument == "radius" || argument.startsWith("falloffRadius("),
                "a drawLight passes `radius = $argument`. Every light states its reach in METRES " +
                "and converts through falloffRadius, which is the only place that knows the engine's " +
                "radius is not a length; `0f` in particular is the no-falloff world this replaced"
            )
        }

        // The local the motes hoist must itself come from the one conversion — otherwise the check
        // above is satisfied by a name and the arithmetic behind it is unguarded.
        assertTrue(
            Regex("val radius = falloffRadius\\(").containsMatchIn(source),
            "drawMoteLights hoists a `radius` local that no longer comes from falloffRadius"
        )
        assertEquals(
            1, Regex("fun falloffRadius\\(").findAll(source).count(),
            "there must be exactly one definition of falloffRadius"
        )
    }

    /**
     * A PEARL MUST NOT LIGHT THE FRAME AND THE TORCH MUST, AND A MOTE MUST NEVER OUT-LIGHT A PEARL.
     *
     * The first two halves are spec §17's ordering — the torch is the deep's light source, the
     * pearls are what it finds — stated as a relationship rather than as two decimals.
     *
     * ## THE MOTE CLAUSE USED TO COMPARE REACHES, AND THAT COMPARISON STOPPED MEANING ANYTHING
     *
     * It read `MOTE_REACH_METRES < PEARL_REACH_METRES`, which was a proxy for *"the mote field is
     * not a second lighting rig"* while the two lights' brightnesses were an order of magnitude
     * apart and only their reaches were in question. `PEARL_REACH_METRES` is now 0.25 m against a
     * mote's 1 m, so that proxy is false while the property it stood for is still comfortably
     * true — a mote delivers a twenty-fourth of a pearl's irradiance and the pearl's shorter reach
     * costs it a sixteenth, so a pearl out-delivers a mote at every distance, by 1.5x at worst.
     *
     * So the proxy is replaced by the quantity itself, swept across the whole visible column
     * rather than sampled: at NO distance may one mote put more light into the water than one
     * pearl. That is what "second lighting rig" actually means, it is the statement that survives
     * a reach change on either side, and it is strictly stronger than the ordering it replaces.
     */
    @Test
    fun `a pearl's reach is its own body, the torch's is the frame, and a mote never outshines a pearl`()
    {
        val across = 20f
        assertTrue(
            DiveLighting.attenuationAt(across, DiveLighting.PEARL_REACH_METRES) < 0.05f,
            "a pearl still delivers " +
            "${DiveLighting.attenuationAt(across, DiveLighting.PEARL_REACH_METRES)} of its full " +
            "brightness ${across}m away — it is lighting the frame it appears in, not its own water"
        )
        assertEquals(
            1f, DiveLighting.attenuationAt(across, DiveLighting.TORCH_REACH_METRES), 1e-6f,
            "the torch has stopped reaching across the frame, so nothing replaces the daylight"
        )

        var distance = 0.1f
        while (distance <= Framing.VISIBLE_DEPTH_METRES)
        {
            val pearl = DiveLighting.PEARL_IRRADIANCE_AT_ONE_METRE *
                DiveLighting.attenuationAt(distance, DiveLighting.PEARL_REACH_METRES)
            val mote = DiveLighting.MOTE_IRRADIANCE_AT_ONE_METRE *
                DiveLighting.attenuationAt(distance, DiveLighting.MOTE_REACH_METRES)
            assertTrue(
                mote < pearl,
                "at ${distance}m one mote delivers $mote against a pearl's $pearl — the mote field " +
                "is a second lighting rig again, which is what it was before 2026-08-13"
            )
            distance += 0.1f
        }
    }

    /**
     * THE TORCH IS CARRIED AT THE SPRITE'S HEAD, AND THIS IS THE ONLY PLACE THAT CHECKS THE TWO
     * AGREE.
     *
     * The owner's report was that the diver glowed from his chest: the emitter sat at
     * `sim.x, sim.depth`, which is the CENTRE-origin position `DiveRenderer.drawDiver` hands
     * `drawTexture`, i.e. the middle of the body. `DiveLighting.torchX`/`torchDepth` now displace
     * it forward along `beamHeadingDegrees` — and `DiverSprite.bodyAngleFor` poses the BODY from
     * that same heading, so "forward" has to come out at the sprite's head at every heading or
     * the light leaves from somewhere he is not.
     *
     * WHAT MAKES THIS NON-TAUTOLOGICAL. It does not restate `(cos h, -sin h)`, which would pass
     * for any sign convention someone happened to type. It pushes the top-centre of the quad —
     * the head — through a transcription of `texture.vert`'s OWN rotation, exactly as
     * `DiverSpriteTest.the body is drawn pointing along the torch's heading` does, and requires
     * the lighting offset to land there. `DiverSpriteTest` establishes that the sprite's head
     * points along the heading; this establishes that the LIGHT does. Neither implies the other,
     * and between them a sign error anywhere in the pair fails the build.
     *
     * The heading list includes [DiverSprite.REST_HEADING_DEGREES] on purpose: that is the
     * attract screen and the first instant of every run, and it is the case a flipped `sin` would
     * turn into a diver whose mask light shines out of his fins.
     */
    @Test
    fun `the torch emits from the sprite's head, not from the middle of the body`()
    {
        val offset = DiveLighting.torchOffsetMetres()

        for (heading in listOf(0f, 45f, 90f, -90f, 180f, -180f, 137.5f, -170f, 359f, DiverSprite.REST_HEADING_DEGREES))
        {
            // texture.vert:68  offset = (vertexPos - origin) * size * rotate(radians(angle))
            // texture.vert:50  rotate(a) = mat2(c, s, -s, c)          (GLSL, column-major)
            // A row-vector product is transpose(M) * p, so local p = (px, py) lands at
            // (c*px + s*py, -s*px + c*py). The head is the quad's top-centre, which in
            // origin-relative local coordinates is (0, -1) because world y runs DOWN.
            val a = Math.toRadians(DiverSprite.bodyAngleFor(heading).toDouble())
            val headX = (cos(a) * 0.0 + sin(a) * -1.0).toFloat()
            val headY = (-sin(a) * 0.0 + cos(a) * -1.0).toFloat()

            // An off-origin diver, so a torch that merely forgot to add the diver's own position
            // cannot pass.
            val diverX = -17.5f
            val diverDepth = 132f

            assertEquals(
                diverX + offset * headX, DiveLighting.torchX(diverX, heading), 1e-3f,
                "at heading $heading the torch is not above the sprite's head horizontally"
            )
            assertEquals(
                diverDepth + offset * headY, DiveLighting.torchDepth(diverDepth, heading), 1e-3f,
                "at heading $heading the torch is not at the sprite's head in depth"
            )
        }
    }

    /**
     * ONE TORCH, TWO SPACES — the property that makes the iridescence shader's light source
     * single-sourced rather than merely consistent-looking.
     *
     * The torch is now needed in world METRES (the emitter itself, and the light position handed
     * to `IridescenceRenderer` on the world surface) and in screen PIXELS (the same light
     * position on the HUD surface, where the air ring's bubbles are drawn). Two spaces, one
     * point. Deriving it twice — a `cos`/`sin` here and another one in `Hud` — is the exact shape
     * of the shipped world-offset-from-HUD bug (`6ea1f53`), and here it would surface as the
     * pearls' interference bands and the ring's shimmer disagreeing about which way the diver
     * faces: subtle, plausible-looking, and invisible in any test that checked only one of them.
     *
     * So [DiveLighting.torchOffsetX]/[DiveLighting.torchOffsetY] take the scale as a PARAMETER,
     * and this asserts the two consequences that buys:
     *
     *  - the offset is exactly LINEAR in that scale, so the HUD's `pixelsPerMetre` cannot
     *    introduce a second convention; and
     *  - `torchX`/`torchDepth` — the world-space pair the emitter and every existing test use —
     *    are those same functions at scale 1, so the world and the HUD provably share one
     *    derivation rather than two that agree today.
     *
     * The headings deliberately include the rest heading and both signs of vertical, because a
     * flipped `sin` in one space and not the other is what this is guarding against; screen y
     * and world y BOTH run downward, so the identical negation must be right in both.
     */
    @Test
    fun `the torch's offset is one derivation, scaled - the world and the HUD cannot disagree`()
    {
        val diverX = -17.5f
        val diverDepth = 132f

        for (heading in listOf(0f, 45f, 90f, -90f, 180f, 137.5f, DiverSprite.REST_HEADING_DEGREES))
        {
            // The world pair is the scaled pair at 1 metre per unit, not a parallel formula.
            assertEquals(
                diverX + DiveLighting.torchOffsetX(heading, 1f),
                DiveLighting.torchX(diverX, heading), 1e-4f,
                "at heading $heading torchX has stopped being torchOffsetX at unit scale"
            )
            assertEquals(
                diverDepth + DiveLighting.torchOffsetY(heading, 1f),
                DiveLighting.torchDepth(diverDepth, heading), 1e-4f,
                "at heading $heading torchDepth has stopped being torchOffsetY at unit scale"
            )

            // ...and the HUD's scale is a pure multiplier. 23.7 stands in for a pixels-per-metre
            // that is deliberately not round and not a power of two: the booth display's
            // resolution is unknown in advance, so nothing may depend on the scale's value.
            val ppm = 23.7f
            assertEquals(
                DiveLighting.torchOffsetX(heading, 1f) * ppm,
                DiveLighting.torchOffsetX(heading, ppm), 1e-3f,
                "at heading $heading the horizontal offset is not linear in the scale"
            )
            assertEquals(
                DiveLighting.torchOffsetY(heading, 1f) * ppm,
                DiveLighting.torchOffsetY(heading, ppm), 1e-3f,
                "at heading $heading the vertical offset is not linear in the scale"
            )
        }

        // A non-degenerate witness: at the rest heading the offset must actually be somewhere,
        // or every assertion above holds trivially for a pair of functions that return zero.
        val restX = DiveLighting.torchOffsetX(DiverSprite.REST_HEADING_DEGREES, 1f)
        val restY = DiveLighting.torchOffsetY(DiverSprite.REST_HEADING_DEGREES, 1f)
        assertEquals(
            DiveLighting.torchOffsetMetres(), hypot(restX, restY), 1e-3f,
            "the offset must have the length torchOffsetMetres promises, in whichever direction"
        )
    }

    /**
     * HOW FAR FORWARD, CHECKED AGAINST THE ART RATHER THAN AGAINST ITSELF.
     *
     * [DiveLighting.TORCH_FORWARD_FRACTION] is a judgement call, so asserting its value would
     * assert nothing. What is NOT a judgement call is the pair of bounds the judgement was made
     * inside, and both are read off the committed sheet here so a re-bake at a different pose or
     * frame height cannot leave them stale:
     *
     *  - **Forward of the SHOULDERS.** Below that the emitter is back on the torso and the
     *    original complaint returns. The shoulder line is the first row from the top where the
     *    silhouette reaches 60% of its widest — the head and neck are 34-48 texels across against
     *    a 91-texel torso, so the jump at the shoulders is unambiguous.
     *  - **Its LEADING EDGE at or behind the CROWN.** The emitter is a quad that rasterises into
     *    the scene, not an abstract point (see [DiveLighting.DIVER_LIGHT_SIZE_METRES]), so a disc
     *    poking out past the top of his head reads as a lamp floating in front of him rather than
     *    one he is wearing — and it would do so at every heading at once. That is why the bound is
     *    on the edge and not on the centre.
     *
     * This is the test that fires if the diver is resized and the offset is left behind, because
     * both sides are fractions of the same height. It says nothing about whether the result LOOKS
     * right, which is not testable and was settled by capture instead.
     */
    @Test
    fun `the torch sits on the head and its emitter stays inside the silhouette`()
    {
        val crown = crownFractionFromSheet()
        val shoulder = shoulderFractionFromSheet()

        assertTrue(
            DiveLighting.TORCH_FORWARD_FRACTION > shoulder,
            "the torch is ${DiveLighting.TORCH_FORWARD_FRACTION} of the way forward, behind the sheet's shoulder line at $shoulder — " +
            "that is back on the torso, which is the 'glowing from the chest' the offset exists to fix"
        )

        val leadingEdge = DiveLighting.TORCH_FORWARD_FRACTION +
            DiveLighting.DIVER_LIGHT_SIZE_METRES / Framing.DIVER_HEIGHT_METRES / 2f
        assertTrue(
            leadingEdge <= crown,
            "the emitter's leading edge is at $leadingEdge of the body height against a crown at $crown — " +
            "the quad would rasterise outside his silhouette and read as a lamp floating in front of him"
        )
    }

    private companion object
    {
        private const val SHEET = "src/main/resources/sprites/diver-diffuse.png"

        /**
         * The largest finite IEEE 754 binary16 value. `radius` reaches the shader through
         * `metadata.a` on an RGBA16F attachment, and anything above this is +inf — which clamps
         * the attenuation to 1 and silently restores the pre-2026-08-13 "no falloff" behaviour.
         */
        private const val HALF_FLOAT_MAX = 65504f

        /**
         * Comments stripped, exactly as `DrawTest` and `MainCameraOwnershipTest` do it: this
         * file's subject is documented at length in prose that names `radius = 0f` several times,
         * and a scan that read the prose would report the explanation as the offence.
         */
        fun stripComments(source: String): String = source
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")

        /**
         * Alpha coverage per row of frame 0 of the committed sheet, top of the cell first. The
         * sheet is authored head-up (`DiverSprite.REST_HEADING_DEGREES`), so row 0 is the leading
         * end. Threshold 16/255 rather than 0 so the anti-aliased fringe does not count as body.
         */
        fun frameZeroRowCoverage(): IntArray
        {
            val sheet = ImageIO.read(File(SHEET))
            val w = DiverSprite.FRAME_TEXELS_WIDE
            val h = DiverSprite.FRAME_TEXELS_TALL
            assertTrue(sheet.width >= w && sheet.height >= h, "$SHEET is smaller than one declared cell")
            return IntArray(h) { y ->
                (0 until w).count { x -> (sheet.getRGB(x, y) ushr 24 and 0xFF) > 16 }
            }
        }

        /** Distance from the body's CENTRE to the top of his head, as a fraction of the height. */
        fun crownFractionFromSheet(): Float
        {
            val rows = frameZeroRowCoverage()
            val crown = rows.indexOfFirst { it > 0 }
            assertTrue(crown >= 0, "no opaque texels at all in frame 0 of $SHEET")
            return (rows.size / 2f - crown) / rows.size
        }

        /** Same, to the shoulder line — the first row reaching 60% of the silhouette's widest. */
        fun shoulderFractionFromSheet(): Float
        {
            val rows = frameZeroRowCoverage()
            val threshold = rows.max() * 0.6f
            val shoulder = rows.indexOfFirst { it >= threshold }
            assertTrue(shoulder > 0, "could not find a shoulder line in frame 0 of $SHEET")
            return (rows.size / 2f - shoulder) / rows.size
        }
    }

    // --- The ambient floor: the water's own glow (DiveLighting.AMBIENT_FLOOR_RED) --------------
    //
    // The whole point of the floor is that it is applied AFTER the daylight blend rather than
    // typed into the tables, so that the two things derived from those tables — the god rays'
    // depth ramp and the torch's — keep reading raw daylight and do not move. Three of the four
    // tests below exist to make that separation break loudly if somebody ever "simplifies" it
    // into the tables, because the symptoms of doing so (god rays in the Abyss; a torch that
    // dims where it is the only light) are both a long way from the line that caused them.

    @Test
    fun `the ambient never falls below the water's own glow, at any depth`()
    {
        var depth = 0f
        while (depth <= Tuning.MAX_DEPTH)
        {
            assertTrue(
                DiveLighting.ambientRedAt(depth) >= DiveLighting.ambientFloor[0],
                "red ambient at ${depth}m is ${DiveLighting.ambientRedAt(depth)}, under the floor"
            )
            assertTrue(
                DiveLighting.ambientGreenAt(depth) >= DiveLighting.ambientFloor[1],
                "green ambient at ${depth}m is ${DiveLighting.ambientGreenAt(depth)}, under the floor"
            )
            assertTrue(
                DiveLighting.ambientBlueAt(depth) >= DiveLighting.ambientFloor[2],
                "blue ambient at ${depth}m is ${DiveLighting.ambientBlueAt(depth)}, under the floor"
            )
            depth += 0.5f
        }
    }

    /**
     * THE SEPARATION, ASSERTED DIRECTLY. The floor lights objects in the deep; the daylight tables
     * must still say there is NO daylight down there, because `shaftDaylightByZone` and
     * `torchIrradianceByZone` are both computed from them.
     *
     * A floor typed into `ambientGreen`'s Abyss entry instead would satisfy the previous test and
     * fail this one, which is exactly the mistake it is here to catch — `LightShaftsTest` would
     * catch the god-ray half of the consequence, and nothing at all would catch the torch half
     * except a player noticing the beam had gone dim at 140 m.
     */
    @Test
    fun `the floor lights the abyss without giving it any daylight`()
    {
        val abyss = DepthBlend.zoneMidpoint(Zone.ABYSS)
        assertEquals(
            0f, DiveLighting.daylightGreenAt(abyss), 0f,
            "the Abyss has daylight — the god rays' ramp and the torch's are both derived from " +
                "this number, so the shafts would reach the Abyss and the torch would dim there"
        )
        assertTrue(
            DiveLighting.ambientGreenAt(abyss) > DiveLighting.daylightGreenAt(abyss),
            "the Abyss ambient is still the raw daylight, so nothing lights the diver down there"
        )
    }

    /**
     * The owner complained about the deep only. The Shallows and the Kelp already read correctly,
     * and brightening them would be a regression rather than a fix — so the floor has to be inert
     * above the Twilight, on all three channels.
     */
    @Test
    fun `the floor is inert in the shallows and the kelp`()
    {
        for (zone in listOf(Zone.SHALLOWS, Zone.KELP))
        {
            val depth = DepthBlend.zoneMidpoint(zone)
            assertEquals(DiveLighting.daylightRedAt(depth), DiveLighting.ambientRedAt(depth), 0f, "$zone red was lifted")
            assertEquals(DiveLighting.daylightGreenAt(depth), DiveLighting.ambientGreenAt(depth), 0f, "$zone green was lifted")
            assertEquals(DiveLighting.daylightBlueAt(depth), DiveLighting.ambientBlueAt(depth), 0f, "$zone blue was lifted")
        }
    }

    /**
     * The glow is WATER, not a lifted black level. Deep water absorbs red first — it is why every
     * other colour in this game shifts toward blue with depth — so a neutral floor would read as
     * grey fog sitting in front of the scene rather than as the water itself.
     */
    @Test
    fun `the water's own glow is bluish rather than neutral`()
    {
        val (red, green, blue) = Triple(DiveLighting.ambientFloor[0], DiveLighting.ambientFloor[1], DiveLighting.ambientFloor[2])
        assertTrue(blue > green, "the floor's blue ($blue) is not above its green ($green) — it will read as grey fog")
        assertTrue(green > red, "the floor's green ($green) is not above its red ($red) — it will read as grey fog")
        assertTrue(blue > red * 2f, "the floor's blue ($blue) is under twice its red ($red), which is not a water hue")
    }
    // --- The colour grade's shadow response (DiveLighting.setup) -------------------------------
    //
    // `2026-08-14-engine-native-lighting.md` §2 read all six of `color_grading.frag`'s curves as
    // x -> 0+ and found we had shipped the harshest one, then bent `contrast` to compensate. The
    // two cases below pin the two halves of the fix. Neither can be asserted against the effect
    // object — `ColorGradingEffect` is an engine class with no accessor a unit test can reach, the
    // same reason `DiveRendererTest.gradedBlue` transcribes its arguments — so the mapper is read
    // off the source and the contrast off the constant it is now constructed with.

    /**
     * ACES's near-black slope is 0.214x, the steepest of the six, and it is what made the deep
     * unreadable: at 140 m it left 85.4% of the frame at or below 2/255 with the rock's p99 at
     * 9.24. UNCHARTED2's is ~2.3x — it LIFTS shadows — and measures 40.0% and 15.53 on the same
     * pinned frame. FILMIC and LOTTES are worse than ACES (a hard clip at 0.004, and a superlinear
     * crush that returns NaN on negative input); NONE and REINHARD are the only other candidates
     * and REINHARD takes the Abyss to 12.4% below 2/255, i.e. it stops being dark at all.
     *
     * So the requirement is a near-black slope of at least 1, and the three that cannot supply one
     * are named rather than the one that can — a future switch to REINHARD or NONE is a judgement
     * about the Abyss, and a switch back to ACES is the bug this fixed.
     */
    @Test
    fun `the colour grade does not use a tone mapper that crushes the deep to black`()
    {
        val source = File("src/main/kotlin/render/DiveLighting.kt").readText()
        val grade = source.substringAfter("ColorGradingEffect(toneMapper = ").substringBefore(",")
        for (crushing in listOf("ACES", "FILMIC", "LOTTES"))
        {
            assertTrue(
                grade.trim() != crushing,
                "the colour grade is back on $crushing, whose near-black slope is below 1 — the " +
                    "Abyss goes back to a hard clamp and the rock stops reading at 140 m"
            )
        }
    }

    /**
     * AND THE SECOND HALF, WHICH IS THE ONE THAT LOOKS SAFE AND IS NOT. `color_grading.frag:124`
     * applies contrast as `(c - 0.5) * (1 + 0.05*(contrast - 1)) + 0.5` BEFORE the tone mapper, so
     * near black any value above 1 is a SUBTRACTION of a constant — 0.00739 at the 1.3 this used to
     * be — and a negative input is negative going into all six curves. No mapper can rescue it.
     *
     * Probed rather than reasoned about: UNCHARTED2 with contrast 1.3 measures a rock p99 of 5.88
     * and an open-water median of exactly 0.000 at 140 m, worse than the ACES control it replaced.
     */
    @Test
    fun `the colour grade's contrast stays at the engine default, which is the only safe value`()
    {
        assertTrue(
            DiveLighting.GRADE_CONTRAST <= 1f,
            "the grade's contrast is ${DiveLighting.GRADE_CONTRAST}; anything above 1 subtracts a " +
                "constant from every channel before the tone mapper and clamps the deep to black"
        )
    }
}
