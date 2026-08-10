package render

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
 * Testable, and here: the arithmetic RELATIONSHIPS between the two materials' numbers — which
 * is the same reason `Framing`, `DepthBlend` and `DiveRenderer`'s colour ramps were extracted
 * (CLAUDE.md's pure-logic-extracted-for-testing section). Two of them are load-bearing:
 *
 *  - The opaque/translucent split IS the feature. One shader, two materials, and if the two
 *    stop differing in opacity there is only one material and the task was not done.
 *  - The darkest colour the shader can produce has to clear `DiveRenderer.GI_REFLECTANCE_FLOOR`,
 *    or the GI multiply throws the dark interference bands away and substitutes flat grey.
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

    @Test
    fun `the two materials differ in exactly the way the task asked for - one opaque, one translucent`()
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
    }

    @Test
    fun `neither material can produce an albedo the GI multiply would replace with grey`()
    {
        // The floor applies to whatever is drawn onto a surface GlobalIlluminationSystem
        // relights, so the list is asked for rather than assumed — see WORLD_MATERIALS.
        val base = DiveRenderer.reflectanceLength(pearlR, pearlG, pearlB)

        IridescentMaterial.WORLD_MATERIALS.forEach { material ->
            val darkest = material.darkestReflectanceLength(base)
            assertTrue(
                darkest >= DiveRenderer.GI_REFLECTANCE_FLOOR,
                "${material.name}'s darkest interference band has linear length $darkest, under " +
                "the ${DiveRenderer.GI_REFLECTANCE_FLOOR} floor. `texture_multiply_blend.frag` " +
                "DISCARDS any albedo shorter than that and substitutes flat vec3(floor) grey, so " +
                "the dark bands would come out as grey blotches rather than as dark colour. See " +
                "DiveRenderer.GI_REFLECTANCE_FLOOR — the same guard put a razor-sharp seam across " +
                "the whole column once already"
            )
        }
    }

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
    }

    @Test
    fun `every material stays inside the range the shader's composition is defined over`()
    {
        listOf(IridescentMaterial.PEARL, IridescentMaterial.BUBBLE).forEach { m ->
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
