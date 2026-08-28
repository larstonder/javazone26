package render

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHAT IS AND IS NOT TESTABLE ABOUT A SHADER, stated plainly because half this task is not
 * coverable and pretending otherwise would be worse than leaving it uncovered.
 *
 * NOT testable here, and settled only by capture (see the contact sheet in this task's report):
 * whether the interference reads as nacre, whether the band spacing is right at a 1.2 m pearl,
 * whether the bubble looks like glass. There is no GL context in the test JVM, `fwidth` has no
 * meaning outside a rasteriser, and a shader that renders garbage compiles exactly as cleanly as
 * one that renders beautifully.
 *
 * Testable, and here: the arithmetic RELATIONSHIPS between the three materials' numbers — which
 * is the same reason `Framing`, `DepthBlend` and `DiveRenderer`'s colour ramps were extracted
 * (CLAUDE.md's pure-logic-extracted-for-testing section). Three of them are load-bearing:
 *
 *  - The opaque/translucent split IS the feature. One shader, three materials, and if they stop
 *    differing in opacity there is only one material and the task was not done.
 *  - The darkest colour the shader can produce has to clear `DiveRenderer.GI_REFLECTANCE_FLOOR`,
 *    or the GI multiply throws the dark interference bands away and substitutes flat grey. For a
 *    TRANSLUCENT world material that check is on the blend with the water, not on the material.
 *  - Which surface a material is tuned against decides what its numbers mean. `BUBBLE`'s 0.62 is
 *    corrected for the HUD's alpha squaring and `VENT`'s is not, because nothing on the world
 *    surface squares alpha; asserting `VENT.centreOpacity < BUBBLE.centreOpacity` is how that
 *    stays true if either is retuned by capture.
 *
 * `IridescenceShaderTest` covers the other half that a GPU is not needed for: that the uniform
 * and vertex-attribute plumbing the Kotlin side declares actually matches the GLSL text.
 */
class IridescentMaterialTest
{
    /**
     * `DiveRenderer.pearlColor`, which is private. Restated here rather than made internal
     * because what is under test is the FLOOR, not the plumbing: if the pearl's colour ever
     * changes, this test's own number going stale would make it pass against a colour the game
     * no longer draws — so the assertion below is written to hold for the whole family of
     * colours a pearl could plausibly be, and the specific value is only the witness.
     */
    private val pearlR = 1f
    private val pearlG = 0.78f
    private val pearlB = 0.35f

    /**
     * `DiveRenderer.airPocketColor` and `airPocketSpentColor`, both private, restated for the
     * same reason and with the same caveat as the pearl's colour above.
     *
     * THE SPENT COLOUR IS THE DANGEROUS ONE and is why these are two entries rather than one. A
     * used vent is the SAME material drawn at a fifth of the reflectance (linear length 0.180
     * against 1.392), so it is the pair that can fall through the GI floor while the fresh vent
     * clears it by seventy times. A floor test that only checked the colour the object spends
     * most of its life in would pass while the other half of its life came back as grey.
     */
    private val ventR = 0.65f
    private val ventG = 0.95f
    private val ventB = 1f
    private val spentR = 0.22f
    private val spentG = 0.34f
    private val spentB = 0.42f

    /**
     * Which draw colours each world material is actually handed, since the reflectance floor is
     * a property of the PAIR (material, base colour) and not of either alone.
     *
     * Checked for completeness against [IridescentMaterial.WORLD_MATERIALS] below rather than
     * trusted, for the reason that list exists at all: a fourth world material added without a
     * colour here would otherwise be silently skipped by a loop that looks like it covers
     * everything.
     */
    private val worldBaseColours = mapOf(
        IridescentMaterial.PEARL to listOf(Triple(pearlR, pearlG, pearlB)),
        IridescentMaterial.VENT to listOf(Triple(ventR, ventG, ventB), Triple(spentR, spentG, spentB))
    )

    @Test
    fun `the materials differ in exactly the way the task asked for - one opaque, two translucent`()
    {
        assertFalse(
            IridescentMaterial.PEARL.isTranslucent,
            "a pearl is opaque: it is the brightest albedo in the frame and the abyss's only " +
            "light source, and water showing through it would read as a rendering fault"
        )
        assertTrue(
            IridescentMaterial.BUBBLE.isTranslucent,
            "an air bubble is translucent — that is the whole of the second material. If this " +
            "ever stops being true there is one material, not two, and the shared shader has " +
            "nothing to share"
        )

        // The translucency has to live in the BODY and not in the outline. The ring is the
        // game's only air warning (Hud's class doc) and its silhouette is what carries the
        // count, the clockwise depletion and the low-air throb — so the rim must stay as solid
        // as the flat disc it replaced, and only the interior may let the water through.
        assertTrue(
            IridescentMaterial.BUBBLE.centreOpacity < IridescentMaterial.BUBBLE.rimOpacity,
            "the bubble must be more transparent in the middle than at its rim"
        )
        assertTrue(
            IridescentMaterial.BUBBLE.rimOpacity >= 1f,
            "the bubble's outline must stay fully opaque — it is the air warning"
        )

        assertTrue(
            IridescentMaterial.VENT.isTranslucent,
            "an air vent is translucent — it is a pocket of gas in the water and the material's " +
            "whole job is to stop it reading as the opaque bead PEARL already draws"
        )
        assertTrue(
            IridescentMaterial.VENT.centreOpacity < IridescentMaterial.VENT.rimOpacity,
            "a vent keeps the soap-film gradient: denser where you look through more film. That " +
            "is the shape iridescence.frag's mix(vBody.x, vBody.y, rimness) draws, and inverting " +
            "it makes a hollow ring rather than a blob of gas"
        )
        assertTrue(
            IridescentMaterial.VENT.rimOpacity < IridescentMaterial.BUBBLE.rimOpacity,
            "a vent's outline must NOT be pinned solid the way the ring bubble's is. The bubble's " +
            "1.0 buys the air warning's silhouette; a vent carries no count, and an opaque edge " +
            "around a translucent middle reads as a bead"
        )
    }

    /**
     * THE ONE NUMBER THAT WOULD HAVE BEEN INHERITED BY MISTAKE.
     *
     * The bubble's 0.62 is a HUD number: that surface stores alpha SQUARED (`Hud.tapeBg` has the
     * measurement) so the bubble displays somewhere in [0.38, 0.62] depending on whether the
     * squaring is real or a capture artefact, and 0.62 was chosen to read as glass at both ends.
     * Nothing on the world surface squares alpha, so a vent authored at 0.62 would DISPLAY at
     * 0.62 — the solid end of that bracket — beside a ring it is supposed to echo.
     *
     * This asserts the conclusion and not the value: the vent's centre stays under the bubble's
     * authored number, and stays at or above the bottom of the bracket so it does not dissolve
     * into the water instead.
     */
    @Test
    fun `the vent is authored more transparent than the bubble because the world surface does not square alpha`()
    {
        assertTrue(
            IridescentMaterial.VENT.centreOpacity < IridescentMaterial.BUBBLE.centreOpacity,
            "the vent's centre opacity ${IridescentMaterial.VENT.centreOpacity} is not under the " +
            "bubble's ${IridescentMaterial.BUBBLE.centreOpacity}. The bubble's number is " +
            "pre-compensated for nothing on this surface — see IridescentMaterial.VENT's opacity " +
            "section. Copying it across is the mistake this test exists to catch"
        )
        assertTrue(
            IridescentMaterial.VENT.centreOpacity >= IridescentMaterial.BUBBLE.centreOpacity *
                IridescentMaterial.BUBBLE.centreOpacity,
            "the vent's centre has gone below the DISPLAYED opacity the bubble reaches under the " +
            "squaring reading (${IridescentMaterial.BUBBLE.centreOpacity * IridescentMaterial.BUBBLE.centreOpacity}). " +
            "Below that the vent is more transparent than the ring is under any reading, and a " +
            "vent that fades into deep water is an air refill the player cannot find"
        )
    }

    @Test
    fun `no world material can produce an albedo the GI multiply would replace with grey`()
    {
        // The floor applies to whatever is drawn onto a surface GlobalIlluminationSystem
        // relights, so the list is asked for rather than assumed — see WORLD_MATERIALS.
        IridescentMaterial.WORLD_MATERIALS.forEach { material ->
            val colours = worldBaseColours[material]
            assertTrue(
                colours != null,
                "${material.name} is on WORLD_MATERIALS but this test does not know what colour " +
                "it is drawn in, so the loop below would skip it while looking exhaustive"
            )

            colours!!.forEach { (r, g, b) ->
                val base = DiveRenderer.reflectanceLength(r, g, b)
                val darkest = material.darkestReflectanceLength(base)
                assertTrue(
                    darkest >= DiveRenderer.GI_REFLECTANCE_FLOOR,
                    "${material.name}'s darkest interference band over base ($r, $g, $b) has " +
                    "linear length $darkest, under the ${DiveRenderer.GI_REFLECTANCE_FLOOR} " +
                    "floor. `texture_multiply_blend.frag` DISCARDS any albedo shorter than that " +
                    "and substitutes flat vec3(floor) grey, so the dark bands would come out as " +
                    "grey blotches rather than as dark colour. See DiveRenderer." +
                    "GI_REFLECTANCE_FLOOR — the same guard put a razor-sharp seam across the " +
                    "whole column once already"
                )
            }
        }
    }

    /**
     * THE SPENT VENT IS THE CASE THAT DECIDES THE VENT'S AMPLITUDE, so it gets its own test with
     * its own headroom check rather than being one iteration of the loop above.
     *
     * A used vent is dimmed rather than hidden (`DiveRenderer.drawAirPockets` — knowing where a
     * spent vent was is what lets a player plan the next dive), which means the same material is
     * asked to survive a base colour with a fifth of the fresh one's reflectance. It does, with
     * room: the point of the margin assertion is that the room is not quietly spent later. The
     * floor only begins to bite at amplitude 0.889.
     */
    @Test
    fun `a spent vent keeps real headroom over the reflectance floor, not just a pass`()
    {
        val spent = DiveRenderer.reflectanceLength(spentR, spentG, spentB)
        val fresh = DiveRenderer.reflectanceLength(ventR, ventG, ventB)
        assertTrue(
            spent < fresh,
            "the spent colour is supposed to be the DARK one — if it is not, this test is " +
            "guarding the wrong case and the fresh colour is the one that needs checking"
        )

        val darkest = IridescentMaterial.VENT.darkestReflectanceLength(spent)
        assertTrue(
            darkest >= DiveRenderer.GI_REFLECTANCE_FLOOR * 2f,
            "a spent vent's darkest band is $darkest, less than twice the " +
            "${DiveRenderer.GI_REFLECTANCE_FLOOR} floor. It may still technically clear it, but " +
            "setDrawColor's 8-bit truncation is worth ~5% of the linear length near these values " +
            "(DiveRenderer.REFLECTANCE_FLOOR_MARGIN's doc) and merely clearing the floor is known " +
            "not to be enough anyway — a colour sitting just above it is no brighter than the grey " +
            "it replaces, which is what made the old wall colour read as letterboxing"
        )
    }

    /**
     * WHAT THE GI MULTIPLY ACTUALLY READS UNDER A TRANSLUCENT WORLD MATERIAL is not the material.
     * It is the material alpha-blended with whatever was already in the framebuffer — here the
     * zone bands, since `drawAirPockets` runs after `drawZoneBands`. The engine converts a draw
     * colour to linear at the vertex stage (`texture.vert`'s `unpackAndConvert`, mirrored in
     * `DiveRenderer.srgbToLinear`), so the framebuffer holds linear values and the blend is linear.
     *
     * Both terms clear the floor on their own and both are blue-dominant, so no convex mix of
     * them can fall under it — but "roughly collinear, therefore safe" stops being true the day
     * the water's hue is retuned, and the water's hue has been retuned before. Swept instead.
     */
    @Test
    fun `a spent vent blended over the water it sits in still clears the reflectance floor`()
    {
        val alpha = IridescentMaterial.VENT.centreOpacity // the centre is where the least vent is
        val substrate = 1f - IridescentMaterial.VENT.amplitude
        val ventLinR = DiveRenderer.srgbToLinear(spentR) * substrate
        val ventLinG = DiveRenderer.srgbToLinear(spentG) * substrate
        val ventLinB = DiveRenderer.srgbToLinear(spentB) * substrate

        var depth = 0f
        var worst = Float.MAX_VALUE
        var worstDepth = 0f
        while (depth <= 200f)
        {
            val waterR = DiveRenderer.srgbToLinear(DiveRenderer.zoneRedAt(depth))
            val waterG = DiveRenderer.srgbToLinear(DiveRenderer.zoneGreenAt(depth))
            val waterB = DiveRenderer.srgbToLinear(DiveRenderer.zoneBlueAt(depth))

            val r = alpha * ventLinR + (1f - alpha) * waterR
            val g = alpha * ventLinG + (1f - alpha) * waterG
            val b = alpha * ventLinB + (1f - alpha) * waterB
            val length = sqrt(r * r + g * g + b * b)
            if (length < worst)
            {
                worst = length
                worstDepth = depth
            }
            depth += 0.5f
        }

        assertTrue(
            worst >= DiveRenderer.GI_REFLECTANCE_FLOOR,
            "a spent vent's darkest band over the water at $worstDepth m composites to linear " +
            "length $worst, under the ${DiveRenderer.GI_REFLECTANCE_FLOOR} floor. The multiply " +
            "would replace the whole blend with flat grey — a grey rectangle of water AROUND the " +
            "vent as well as on it, since the blend is what is in the framebuffer at those pixels"
        )
    }

    /**
     * THE FILM THICKNESS ORDERING, and the sizing premise half of it rests on, because that
     * premise is the part that turned out to be wrong once already: [IridescentMaterial.BUBBLE]
     * claimed a finer wind on the grounds of being drawn larger than a pearl, and at a full ring
     * it is drawn SMALLER (0.9 m against 1.2 m at the same pixels-per-metre). The vent's claim to
     * sit above the pearl is a size claim, so it is asserted rather than repeated.
     */
    @Test
    fun `the vent's film is wound between the pearl's and the bubble's, on the sizes it claims`()
    {
        assertTrue(
            Framing.AIR_POCKET_SIZE_METRES > Framing.PEARL_SIZE_METRES,
            "the vent's film thickness is argued from it being the bigger object. If a vent is no " +
            "longer bigger than a pearl, that argument is gone and 0.80 is unfounded"
        )
        assertTrue(
            Framing.AIR_POCKET_SIZE_METRES > Hud.AIR_BUBBLE_SIZE_METRES,
            "a vent is drawn at world scale and a ring bubble at the SAME pixels-per-metre " +
            "(Hud.drawAirRing), so this is a direct comparison: the vent is expected to have more " +
            "pixels than a full ring's bubble, not fewer, which is why its wind is NOT argued from " +
            "having more pixels than the bubble"
        )

        assertTrue(
            IridescentMaterial.VENT.filmThickness > IridescentMaterial.PEARL.filmThickness,
            "a vent is twice a pearl's diameter; at the pearl's wind it crosses the same bands " +
            "over twice the distance, which is the broad-wash failure PEARL's own doc names, made " +
            "larger"
        )
        assertTrue(
            IridescentMaterial.VENT.filmThickness < IridescentMaterial.BUBBLE.filmThickness,
            "the vent's bands arrive multiplied by the light map and through an ACES tone mapper " +
            "and the bubble's do not (the HUD is not relit), so the unlit surface keeps the finest " +
            "wind. This is a CONTRAST bound, not a pixel one — see VENT's film-thickness section"
        )
    }

    /**
     * The sheen bound, with the premise it rests on asserted beside it: the film layer is scaled
     * by the base colour's luma (`film * 2.0 * luma * coverage` in iridescence.frag), so the same
     * sheen number buys more absolute swing on a brighter base — on the one surface that runs a
     * tone mapper and a thresholded bloom.
     */
    @Test
    fun `the vent has the least sheen because it has the brightest base colour`()
    {
        val ventLuma = linearLuma(ventR, ventG, ventB)
        val pearlLuma = linearLuma(pearlR, pearlG, pearlB)
        assertTrue(
            ventLuma > pearlLuma,
            "a fresh vent ($ventLuma) is supposed to be the brighter base of the two ($pearlLuma). " +
            "If the vent's colour is dimmed below the pearl's, the reason its sheen is the lowest " +
            "of the three no longer holds and the number should be revisited"
        )
        assertTrue(
            IridescentMaterial.VENT.sheen < IridescentMaterial.PEARL.sheen &&
                IridescentMaterial.VENT.sheen < IridescentMaterial.BUBBLE.sheen,
            "the vent's sheen ${IridescentMaterial.VENT.sheen} is not the lowest of the three, " +
            "despite its base carrying the most luma into the film layer, on the only surface " +
            "where a torch-facing highlight has to survive ACES and a 1.4 bloom threshold"
        )
    }

    /** Rec. 709 luma of a draw colour in the linear space the shader composes in. */
    private fun linearLuma(r: Float, g: Float, b: Float) =
        0.2126f * DiveRenderer.srgbToLinear(r) +
        0.7152f * DiveRenderer.srgbToLinear(g) +
        0.0722f * DiveRenderer.srgbToLinear(b)

    @Test
    fun `the bubble is not on the list of materials the reflectance floor applies to`()
    {
        // The HUD surface is deliberately outside GI's target (see the "hud" createSurface call
        // in EnPustTil.onCreate — putting it inside took "BANKED 7" in the Abyss to RGB(11,8,1)).
        // So the bubble is free to be as dark and as transparent as it likes, and listing it as
        // a world material would be a claim about the wrong surface.
        assertFalse(
            IridescentMaterial.BUBBLE in IridescentMaterial.WORLD_MATERIALS,
            "the air ring is drawn on the HUD surface, which GI does not relight"
        )
        assertTrue(
            IridescentMaterial.PEARL in IridescentMaterial.WORLD_MATERIALS,
            "pearls ARE drawn on the relit world surface, so the floor applies to them"
        )
        assertTrue(
            IridescentMaterial.VENT in IridescentMaterial.WORLD_MATERIALS,
            "air vents are drawn by DiveRenderer.drawAirPockets, onto the relit world surface — " +
            "off this list they would skip the floor check that the SPENT colour actually needs"
        )
    }

    @Test
    fun `every material stays inside the range the shader's composition is defined over`()
    {
        assertTrue(
            IridescentMaterial.WORLD_MATERIALS.all { it in IridescentMaterial.ALL },
            "ALL is supposed to be every material there is, and a world material missing from it " +
            "would skip every check in this test"
        )

        IridescentMaterial.ALL.forEach { m ->
            // `base * (1 - amplitude)` is the substrate's weight in iridescence.frag. Above 1 it
            // goes negative, which the shader does not clamp: the body would flip to a negative
            // colour and the additive film layer would then be all that is left, i.e. the object
            // would lose its own hue entirely rather than gain a film on top of it.
            assertTrue(m.amplitude in 0f..1f, "${m.name}: amplitude ${m.amplitude} must be a fraction")
            assertTrue(m.filmThickness > 0f, "${m.name}: a film with no thickness has no interference")
            assertTrue(m.sheen >= 0f, "${m.name}: sheen is extra film coverage and cannot be negative")
            assertTrue(m.centreOpacity in 0f..1f, "${m.name}: centreOpacity must be an alpha")
            assertTrue(m.rimOpacity in 0f..1f, "${m.name}: rimOpacity must be an alpha")
        }
    }
}
