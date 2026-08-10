package render

import dive.DiveInput
import dive.DiveSim
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
     * SHRINKING THE EMITTER MUST NOT CHANGE WHAT IT CASTS, and the relationship rather than the
     * resulting number is what is asserted — the failure this exists to catch is someone moving
     * the emitter's size and leaving a hand-typed multiplier behind, which is exactly what
     * `fd036f7` did in the other direction.
     *
     * The physics: `radius = 0` disables `radiance_cascades.frag`'s distance term, so a probe's
     * irradiance from a light is the fraction of its rays that hit it, which is proportional to
     * the light's angular size, i.e. to `size / distance`. `size * intensity` is therefore the
     * conserved quantity at every distance, and it must come out at the size the beam's balance
     * was signed off against no matter what emitter size is chosen.
     */
    @Test
    fun `the torch's cast is conserved when its emitter is resized`()
    {
        for (size in listOf(0.4f, 1.2f, 3f, 6f, 9f))
        {
            assertEquals(
                DiveLighting.TORCH_BALANCE_SIZE_METRES,
                size * DiveLighting.torchIntensityFor(size), 1e-3f,
                "an emitter of ${size}m casts a different amount of light than the ${DiveLighting.TORCH_BALANCE_SIZE_METRES}m one the beam was balanced at"
            )
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

    // --- The rim ------------------------------------------------------------------------------
    //
    // What is testable here is the GEOMETRY and the BUDGET, both of which are relationships
    // between numbers. What the rim LOOKS like is not testable at all and was settled by capture
    // (see the task report): whether 5 is the right cast, whether the blue is the right blue, and
    // whether the halo at his knees is acceptable are all judgements a still had to answer.

    /**
     * THE RIM IS BEHIND HIM, AND "BEHIND" IS CHECKED AGAINST THE SPRITE'S OWN ROTATION RATHER
     * THAN AGAINST THE FORMULA THAT PRODUCED IT.
     *
     * Same construction as `the torch emits from the sprite's head`, mirrored: it pushes the
     * quad's BOTTOM-centre — the fins end, local `(0, +1)` because world y runs DOWN — through a
     * transcription of `texture.vert`'s own rotation matrix and requires the rim to land there.
     * Restating `(-cos h, +sin h)` would pass for any sign convention someone happened to type;
     * this cannot, because the matrix comes from the shader and the same transcription is what
     * `DiverSpriteTest` poses the BODY from.
     *
     * The second assertion is the one that catches a sign slip specifically in the rim: the
     * torch's displacement and the rim's must point in OPPOSITE directions. A rim that landed on
     * the same side as the torch would be a backlight shining from his mask, which is exactly
     * what a lost minus sign in `alongHeadingX` would produce, and which is invisible in a still
     * of a diver swimming straight down where both offsets are vertical.
     */
    @Test
    fun `the rim emits from behind the sprite's fins, on the opposite side from the torch`()
    {
        val offset = DiveLighting.rimOffsetMetres()

        for (heading in listOf(0f, 45f, 90f, -90f, 180f, -180f, 137.5f, -170f, 359f, DiverSprite.REST_HEADING_DEGREES))
        {
            // texture.vert:68  offset = (vertexPos - origin) * size * rotate(radians(angle))
            // texture.vert:50  rotate(a) = mat2(c, s, -s, c)          (GLSL, column-major)
            // A row-vector product is transpose(M) * p, so local p = (px, py) lands at
            // (c*px + s*py, -s*px + c*py). The fins are the quad's bottom-centre, (0, +1).
            val a = Math.toRadians(DiverSprite.bodyAngleFor(heading).toDouble())
            val finX = (cos(a) * 0.0 + sin(a) * 1.0).toFloat()
            val finY = (-sin(a) * 0.0 + cos(a) * 1.0).toFloat()

            val diverX = -17.5f
            val diverDepth = 132f

            assertEquals(
                diverX + offset * finX, DiveLighting.rimX(diverX, heading), 1e-3f,
                "at heading $heading the rim is not behind the sprite's fins horizontally"
            )
            assertEquals(
                diverDepth + offset * finY, DiveLighting.rimDepth(diverDepth, heading), 1e-3f,
                "at heading $heading the rim is not behind the sprite's fins in depth"
            )

            val torchDx = DiveLighting.torchX(diverX, heading) - diverX
            val torchDy = DiveLighting.torchDepth(diverDepth, heading) - diverDepth
            val rimDx = DiveLighting.rimX(diverX, heading) - diverX
            val rimDy = DiveLighting.rimDepth(diverDepth, heading) - diverDepth
            assertTrue(
                torchDx * rimDx + torchDy * rimDy < 0f,
                "at heading $heading the rim and the torch are displaced in the same direction " +
                "(torch $torchDx,$torchDy against rim $rimDx,$rimDy) — the rim would be backlighting him from his own mask"
            )
        }
    }

    /**
     * WHERE BEHIND HIM, CHECKED AGAINST THE ART. [DiveLighting.RIM_BACK_FRACTION] is chosen to
     * hide as much of the emitter quad inside the silhouette as the trailing half of this diver
     * allows, and that optimum is a property of the committed sheet, so it is re-derived here
     * rather than restated. Three things, all in fractions of the body height so a fourth resize
     * of `Framing.DIVER_HEIGHT_METRES` cannot invalidate them:
     *
     *  - the quad lies WHOLLY BEHIND his centre, or it is not a backlight at all;
     *  - its trailing edge is at or inside the fins, or it rasterises past his outline into open
     *    water and reads as a lamp trailing him;
     *  - it sits on the LOCAL MAXIMUM of silhouette coverage — better hidden than positions a
     *    twelfth of a body either side of it, which are the calf gap and the fin fork. That is the
     *    assertion that fires if the constant is nudged without re-measuring, and the one that
     *    moves on its own if the diver is ever re-baked in a different pose.
     *
     * It says nothing about whether the visible halo is acceptable, which is not testable and was
     * answered by capture.
     */
    @Test
    fun `the rim's emitter is behind him, inside his outline, and as hidden as the art allows`()
    {
        val back = DiveLighting.RIM_BACK_FRACTION
        val half = DiveLighting.RIM_SIZE_FRACTION / 2f

        assertTrue(back - half > 0f, "the rim's emitter overlaps the diver's centre at $back +- $half — it is not behind him")

        val fin = finFractionFromSheet()
        assertTrue(
            back + half <= fin,
            "the rim's emitter reaches $${back + half} of the body behind his centre against fins that end at $fin — " +
            "the quad would rasterise outside his outline and read as a lamp trailing him"
        )

        val here = emitterCoverageFromSheet(back)
        val step = 1f / 12f
        for (other in listOf(back - step, back + step))
        {
            assertTrue(
                here > emitterCoverageFromSheet(other),
                "the emitter is better hidden at $other (${emitterCoverageFromSheet(other)}) than at $back ($here) — " +
                "RIM_BACK_FRACTION is no longer the local maximum it was measured to be"
            )
        }
    }

    /**
     * THE RIM'S BUDGET IS A RATIO OF THE TORCH'S, AND THE RATIO IS WHAT IS ASSERTED.
     *
     * `size * intensity` is the conserved quantity — `radius = 0` disables the distance term, so
     * a probe's irradiance from a light goes as its angular size, i.e. as `size / distance`. The
     * same physics `the torch's cast is conserved when its emitter is resized` rests on. What
     * this fixes in place is that the rim's cast is exactly
     * [DiveLighting.rimCastFractionForDepth] of the TORCH's, at every depth: the failure it
     * exists to catch is someone re-tuning `RIM_SIZE_FRACTION` and leaving the intensity behind,
     * which would change how much light is in the frame while every constant still read as
     * before.
     */
    @Test
    fun `the rim's cast is a stated fraction of the torch's, whatever the emitter's size`()
    {
        for (depth in listOf(0f, 15f, 45f, 75f, 105f, 135f, 160f, 400f))
        {
            val torchCast = DiveLighting.DIVER_LIGHT_SIZE_METRES * DiveLighting.diverIntensityForDepth(depth)
            val rimCast = DiveLighting.rimSizeMetres() * DiveLighting.rimIntensityForDepth(depth)
            assertEquals(
                torchCast * DiveLighting.rimCastFractionForDepth(depth), rimCast, 1e-3f,
                "at $depth m the rim casts $rimCast against a torch's $torchCast — that is not " +
                "${DiveLighting.rimCastFractionForDepth(depth)} of it, so the emitter's size and its intensity have come apart"
            )
        }
    }

    /**
     * THE CAST FRACTION IS A PEAK RADIANCE, AND IT IS ONLY A PEAK RADIANCE BECAUSE THE CONE IS
     * EXACTLY A HEMISPHERE.
     *
     * [DiveLighting.RIM_CAST_FRACTION] is documented as being in the same unit as
     * [DiveLighting.beamIntensity]'s two ends, which holds only where [DiveLighting.coneMaskPeak]
     * is 1 — see its "landmark" note. Narrow the rim's cone without dividing the mask peak back
     * out and the rim silently dims by up to 10x while the constant still reads 5. Nothing else
     * in the file would notice.
     */
    @Test
    fun `the rim's cone is the exact hemisphere its budget is stated against`()
    {
        assertEquals(
            1f, DiveLighting.coneMaskPeak(DiveLighting.RIM_CONE_ANGLE), 1e-6f,
            "a ${DiveLighting.RIM_CONE_ANGLE}-degree rim cone peaks at ${DiveLighting.coneMaskPeak(DiveLighting.RIM_CONE_ANGLE)} of nominal, " +
            "so RIM_CAST_FRACTION is no longer the peak radiance it is documented as"
        )
    }

    /**
     * THE RIM REPLACES LOST DAYLIGHT, SO IT MUST BE ABSENT WHERE NONE HAS BEEN LOST.
     *
     * The Shallows case is the one that matters and it is an EXACT zero, not a small number: the
     * rim's cost is paid in the water it also lights, and at 10 m submitting it at full strength
     * moved the frame mean by 0.238/255 and the water immediately around the diver by 5.4/255
     * (measured — see [DiveLighting.RIM_CAST_FRACTION] and the ramp's own doc). Zero is also what
     * lets `drawDiverRim` skip the submission entirely, which matters for a second reason: a
     * light quad occludes even when it emits nothing, and left in it measurably DARKENED the
     * diver in the shallows.
     *
     * Monotonic and continuous are asserted over a fine sweep rather than at the anchors, because
     * a diver descending crosses every value in between and a step anywhere in there is the
     * "sharp jump between depth levels" this whole file blends to avoid.
     */
    @Test
    fun `the rim is absent in the shallows and rises smoothly to full strength in the abyss`()
    {
        assertEquals(
            0f, DiveLighting.rimCastFractionForDepth(0f), 0f,
            "the rim is not exactly off at the surface, so it lights water that the sun is already lighting"
        )
        assertEquals(
            DiveLighting.RIM_CAST_FRACTION, DiveLighting.rimCastFractionForDepth(200f), 1e-6f,
            "the rim does not reach full strength in the abyss, which is the depth it exists for"
        )

        var previous = -1f
        var biggestStep = 0f
        var depth = 0f
        while (depth <= 200f)
        {
            val here = DiveLighting.rimCastFractionForDepth(depth)
            assertTrue(here >= previous, "the rim gets weaker between ${depth - 0.5f} m and $depth m")
            if (previous >= 0f) biggestStep = maxOf(biggestStep, here - previous)
            previous = here
            depth += 0.5f
        }
        assertTrue(
            biggestStep < DiveLighting.RIM_CAST_FRACTION * 0.05f,
            "the rim jumps by $biggestStep in half a metre of descent — that is a visible step, not a ramp"
        )
    }

    private companion object
    {
        private const val SHEET = "src/main/resources/sprites/diver-diffuse.png"

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

        /** Distance from the body's CENTRE to the trailing tip of his fins, as a fraction of the height. */
        fun finFractionFromSheet(): Float
        {
            val rows = frameZeroRowCoverage()
            val fin = rows.indexOfLast { it > 0 }
            assertTrue(fin >= 0, "no opaque texels at all in frame 0 of $SHEET")
            return (fin - rows.size / 2f) / rows.size
        }

        /**
         * What fraction of the rim's emitter disc the silhouette covers, with the disc centred
         * [backFraction] of the body height behind his centre.
         *
         * A DISC and not a row, because the emitter is round ([LightEmitter]) and the thing being
         * measured is how much of the quad rasterises into open water. In cell texels a metre is
         * the same number of texels in both axes — the cell's 126x384 has exactly the sprite's
         * aspect — so the disc is a circle here and needs no anisotropic correction.
         */
        fun emitterCoverageFromSheet(backFraction: Float): Float
        {
            val sheet = ImageIO.read(File(SHEET))
            val w = DiverSprite.FRAME_TEXELS_WIDE
            val h = DiverSprite.FRAME_TEXELS_TALL
            val radius = DiveLighting.RIM_SIZE_FRACTION * h / 2f
            val centreY = (0.5f + backFraction) * h
            val centreX = w / 2f
            var inside = 0
            var covered = 0
            for (y in 0 until h)
            {
                for (x in 0 until w)
                {
                    val dx = x - centreX
                    val dy = y - centreY
                    if (dx * dx + dy * dy > radius * radius) continue
                    inside++
                    if ((sheet.getRGB(x, y) ushr 24 and 0xFF) > 16) covered++
                }
            }
            assertTrue(inside > 0, "the rim's emitter disc falls entirely outside the cell at $backFraction")
            return covered / inside.toFloat()
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
}
