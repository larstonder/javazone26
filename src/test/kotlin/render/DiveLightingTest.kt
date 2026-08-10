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
     * THE SAME REGRESSION, FOR THE PEARLS — the half of it `006512b` did not do.
     *
     * The torch was shrunk and the pearls were left at a 3 m quad around a 1.2 m body, which the
     * owner reported as a faintly visible box of lifted water about 2.5x the pearl's diameter,
     * clearest near the surface where ambient is high enough for its rim to read. It is not a
     * shape problem — `LightEmitter` already makes the emitter round — it is that with
     * `radius = 0` the inside of an emitter is a FLAT SHELF of irradiance with a hard rim, so any
     * part of the quad sticking out past the body is a visible region. Confirmed by capture: the
     * shelf's diameter tracks the constant one for one (12 m asked, 11.7 m measured).
     *
     * The bound asserted is therefore the property that makes the shelf invisible: the emitter
     * must fit inside the pearl's own DRAWN silhouette, so that the pearl itself covers it. That
     * silhouette is the iridescence shader's inscribed disc, whose diameter is
     * `equalAreaQuad(PEARL_SIZE_METRES)` — derived here rather than typed, so that re-tuning
     * either the pearl's size or the equal-area scale moves this bound with it.
     */
    @Test
    fun `a pearl's emitter fits inside the pearl, so its shelf of light has nowhere to show`()
    {
        val drawnDiameter = IridescenceRenderer.equalAreaQuad(Framing.PEARL_SIZE_METRES)

        assertTrue(
            DiveLighting.PEARL_LIGHT_SIZE_METRES <= drawnDiameter,
            "a pearl's emitter is ${DiveLighting.PEARL_LIGHT_SIZE_METRES}m across a pearl drawn " +
            "${drawnDiameter}m across — the part that pokes out is a flat shelf of irradiance " +
            "with a hard rim, which is the halo the owner reported around every pearl"
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
     * BOTH emitter sizes by a wide factor. `radius = 0` means there is no distance falloff to
     * make an off-screen light negligible, and the measured contribution profile backs the
     * number — a pearl still puts 8% of its 1 m value on the water 3.2 m away.
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
}
