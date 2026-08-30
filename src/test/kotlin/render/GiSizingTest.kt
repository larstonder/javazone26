package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GiSizing` re-implements GlobalIlluminationSystem.lightTextureSizeFunc and the cascade-count
 * formula from GiRadianceCascades.applyEffect, both read out of pulse-engine-0.13.0.jar.
 *
 * WHY A COPY OF ENGINE MATHS IS WORTH HAVING: lightTexScale is a STEP FUNCTION, not a curve.
 * The light texture is rounded UP to a multiple of 2^cascadeCount, and cascadeCount is itself
 * derived from the rounded diagonal — so a "small" change to the scale can cost or save 70-120%
 * of the single most expensive pass in the game. The shipped 0.5 was WORSE than the engine's
 * own 0.4 default for exactly this reason, and nobody could see it by reading the number.
 *
 * These tests pin the mechanism so that a future quality preset cannot silently tip the cascade
 * count and cost more at "Low" than at "Medium".
 */
class GiSizingTest
{
    @Test
    fun `the shipped 0_5 was worse than the engine default 0_4 at 1080p`() {
        // The measured defect: at 1080p, 0.5 tips cascadeCount 6 -> 7 AND rounds the unrounded
        // 540 up to 640. The brief that spawned this test guessed "+122%" for the resulting
        // area increase; re-derived from the real bytecode formula (see GiSizing's class doc)
        // it is 344064 -> 655360 px, i.e. 1.905x, +90% -- still a lot for "0.1 higher", just not
        // literally double. The 3200x1800 dev-framebuffer case below it in DiveLighting.kt
        // (+73%) was independently re-derived here too and DID match the brief exactly, which
        // is why only this number was corrected rather than the formula being distrusted.
        val at40 = GiSizing.lightTextureSize(1920, 1080, 0.4f, maxCascades = 10)
        val at50 = GiSizing.lightTextureSize(1920, 1080, 0.5f, maxCascades = 10)

        assertEquals(6, GiSizing.cascadeCount(1920, 1080, 0.4f, 10))
        assertEquals(7, GiSizing.cascadeCount(1920, 1080, 0.5f, 10))

        val pixels40 = at40.first * at40.second
        val pixels50 = at50.first * at50.second
        assertTrue(pixels50 > pixels40 * 1.8f,
            "0.5 should cost substantially more than 0.4 at 1080p (measured 1.905x), got $pixels40 vs $pixels50")
    }

    @Test
    fun `the light texture is rounded up to a multiple of two to the cascade count`() {
        val cascades = GiSizing.cascadeCount(1920, 1080, 0.4f, 10)
        val (w, h) = GiSizing.lightTextureSize(1920, 1080, 0.4f, 10)
        val factor = 1 shl cascades
        assertEquals(0, w % factor, "width $w is not a multiple of $factor")
        assertEquals(0, h % factor, "height $h is not a multiple of $factor")
    }

    @Test
    fun `capping maxCascades removes a pass but need not shrink the rounded texture`() {
        // At 3200x1800 uncapped cascadeCount is 7; capped it is 6 — a whole cascade PASS removed.
        // The rounded SIZE does not move here: 2^6 = 64 and 2^7 = 128 both divide the already-
        // rounded 1280x768 evenly, so capped and uncapped land on the identical texture. The
        // `<=` below still holds (equality is not a shrink), which is the point — this test used
        // to be named as if capping always shrinks the texture, and at both framebuffers this
        // game actually runs it does not; see DiveLighting.setup's comment on `maxCascades`.
        assertEquals(6, GiSizing.cascadeCount(3200, 1800, 0.4f, maxCascades = 6))
        assertEquals(7, GiSizing.cascadeCount(3200, 1800, 0.4f, maxCascades = 10))
        val capped = GiSizing.lightTextureSize(3200, 1800, 0.4f, maxCascades = 6)
        val uncapped = GiSizing.lightTextureSize(3200, 1800, 0.4f, maxCascades = 10)
        assertEquals(capped, uncapped, "expected the two to round to the same texture here")
        assertTrue(capped.first * capped.second <= uncapped.first * uncapped.second,
            "capping cascades must not increase the texture")
    }

    @Test
    fun `six cascades reach past the torch, with a wide margin`() {
        // Light propagation is about intervalLength * (4^N - 1) / 3 LIGHT-TEXTURE TEXELS, which
        // the engine's own dist = distance(...) / lightTexScale (radiance_cascades.frag:122-126)
        // converts to framebuffer pixels — see propagationMetres's KDoc for the full derivation.
        // At HIGH's lightTexScale = 0.4 and ~32.5 px/m, N=6 reaches ~105 m, not the ~42 m an
        // earlier version of this test (and of propagationMetres itself) computed by treating
        // intervalLength as framebuffer pixels directly.
        //
        // There used to be a second assertion here, `propagationMetres(5, ...) < 24f`, offered
        // as proof that N=5 would clip TORCH_REACH_METRES = 24 m and that 6 is therefore a
        // torch-reach floor. It passed only because the function was wrong: N=5 actually reaches
        // ~26 m, comfortably past the torch too. Deleted rather than corrected to a passing
        // number — a test that can't fail is worse than none, and this one was never testing a
        // real boundary. `maxCascades = 6` is kept as a floor for a different, real reason: cost
        // (GraphicsQualityTest's "at least six cascades" case), not torch reach.
        assertTrue(GiSizing.propagationMetres(cascades = 6, pixelsPerMetre = 32.48f, lightTexScale = 0.4f) > 24f)
    }
}
