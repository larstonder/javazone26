package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT SURVIVED OF `ViewportTest`, AND WHY THE OTHER SIX DID NOT.
 *
 * `ViewportTest` had nine cases. `render/Viewport.kt` was mutated one edit at a time and the
 * surviving failures recorded (the table is in the plan's §3.0; the source was verified
 * byte-identical afterwards). Six of the nine died to at least one mutation, so the file was
 * NOT the write-off an earlier reading called it — but three of the six lost their subject when
 * the transform moved into [CameraRig], and the three that could not be killed by anything are
 * gone for that reason instead. Case by case:
 *
 *   1. `the diver sits at the same screen fraction on every display` — real (a `targetCameraDepth`
 *      sign flip killed it), but it called `screenY`, which no longer exists, and case 9 below
 *      kills the same mutation using only constants. Dropped for REDUNDANCY, not for being
 *      unfalsifiable.
 *   2. `a world point maps to the same screen fraction on every display` — MEASURED tautology:
 *      `f(x)·h/h == f(x)·h/h` on both sides, true for any `f`, including a wrong one.
 *   3. `the diver occupies the same screen fraction on every display` — MEASURED tautology:
 *      `DIVER_HEIGHT_METRES·(h/60)/h` is `DIVER_HEIGHT_METRES/60` written out twice.
 *   4. `the diver is large enough to see` — KEPT, below, re-expressed without `pixelsPerMetre`.
 *   5. `only part of the water column is visible so descending scrolls` — KEPT, verbatim.
 *   6. `x is centred and scales with the display` — real (dropping the `* 0.5f` centring term
 *      killed it) and absorbed by `CameraRigTest`'s `world (0, camDepth) -> (W/2, 0)` pin, which
 *      kills the same mutation. It could not move here: it called `Viewport.screenX`.
 *   7/8. the two `depthAt` cases — deleted WITH THEIR SUBJECT. Nothing of ours converts a screen
 *      row back to a depth any more: `DiveRenderer` reads `cam.topLeftWorldPosition.y`, which the
 *      ENGINE computes in `initFrame` from the real framebuffer. There is no pure function left
 *      to test. Its successor is [CameraInvariants] rule 2 on a running game, which is strictly
 *      stronger (it exercises the engine's actual matrix rather than our transcription of it) and
 *      which runs only under `EPT_DEV`. Stated plainly: a net loss of two unit tests for a
 *      runtime check that is not in `./gradlew test`.
 *   9. `camera keeps the diver above the top edge of visible water` — KEPT, verbatim.
 *
 * WHAT IS DELIBERATELY NOT ASSERTED. `VISIBLE_DEPTH_METRES` and `DIVER_SCREEN_FRACTION` can be
 * changed to any value without a test here noticing (60 -> 45 and 0.4 -> 0.6 both survived the
 * whole nine-case suite). They are tuning constants, not invariants; what is pinned below is
 * their RELATIONSHIPS. Pinning the values would just be a second copy of the source.
 *
 * AND THE SENTENCE THIS FILE EXISTS UNDER: no unit test here can catch an aspect-ratio bug.
 * `screenX` deriving pixels-per-metre from the screen WIDTH survived all nine of the cases above.
 * That class is caught by [CameraInvariants] rule 3 on a real framebuffer, and by looking at
 * captures at three aspect ratios — by nothing in `./gradlew test`.
 */
class FramingTest
{
    // Killed by: DIVER_HEIGHT_METRES 3 -> 0.3. Same mutation that killed it in ViewportTest,
    // verified red in this file before ViewportTest was deleted.
    @Test
    fun `the diver is large enough to see`() {
        // The bug shipped a 14px diver into an 1800px-tall framebuffer: 0.8% of screen height.
        // Stated as a fraction of the visible column rather than of a screen height, which is
        // numerically the same number and no longer needs a resolution to say it:
        // DIVER_HEIGHT_METRES * (h / VISIBLE_DEPTH_METRES) / h is DIVER_HEIGHT_METRES /
        // VISIBLE_DEPTH_METRES for every h.
        val fraction = Framing.DIVER_HEIGHT_METRES / Framing.VISIBLE_DEPTH_METRES
        assertTrue(fraction > 0.02f, "diver covers only ${fraction * 100}% of screen height")
    }

    // Killed by: VISIBLE_DEPTH_METRES 60 -> 100. A genuine one-sided bound, not a tautology —
    // the mutation the plan's §3.0 run tried (60 -> 45) simply moved it the safe way.
    @Test
    fun `only part of the water column is visible so descending scrolls`() {
        // MAX_DEPTH is 160m. If the whole column fits on screen the camera never moves
        // and the sense of descent is lost entirely.
        assertTrue(
            Framing.VISIBLE_DEPTH_METRES < dive.Tuning.MAX_DEPTH * 0.6f,
            "visible depth ${Framing.VISIBLE_DEPTH_METRES}m is too much of the ${dive.Tuning.MAX_DEPTH}m column"
        )
    }

    // Killed by: flip the sign of targetCameraDepth's second term. This is what makes case 1
    // above redundant — it kills the same mutation from constants alone.
    @Test
    fun `camera keeps the diver above the top edge of visible water`() {
        val diverDepth = 100f
        val top = Framing.targetCameraDepth(diverDepth)
        assertTrue(top < diverDepth, "camera top must be shallower than the diver")
        assertEquals(Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_SCREEN_FRACTION, diverDepth - top, 0.001f)
    }

    /**
     * THE CAP IS EXACTLY ONE CLIFF SPRITE AT EACH END — THE ART'S EXTENT, NOT ITS QUAD'S.
     *
     * The owner, after five failed attempts at the symptom: *"for each aspect ratio we should only
     * ever see exactly one rock cliff on each end of the screen"*, and then, on the first capped
     * build: *"still seems like we use at least two sprites for width at each side"*.
     *
     * He was right both times. The first cap was `COLUMN_HALF_WIDTH + TILE_WIDTH_METRES`, which is
     * one tile of QUAD; but a wall tile's last [RockFace.EDGE_INSET_METRES] hold no alpha — that
     * margin is what puts the ragged silhouette on the column boundary — so the visible rock ends
     * at [RockFace.CREST_OUTER_HALF_WIDTH], 1.31 m short, and the body had to fill the rest.
     *
     * Written because widening the cap survived every other case: the frame dutifully showed the
     * wider cap, the arithmetic stayed self-consistent, and the surplus came straight back as
     * repeated art. A test that only checks "the frame shows the constant" cannot notice the
     * constant being wrong.
     */
    @Test
    fun `the visible width is exactly one cliff sprite at each end`()
    {
        assertEquals(
            2f * RockFace.BODY_INNER_HALF_WIDTH, Framing.VISIBLE_WIDTH_METRES, 1e-4f,
            "the frame is capped at ${Framing.VISIBLE_WIDTH_METRES} m against " +
            "${2f * RockFace.BODY_INNER_HALF_WIDTH} m for one cliff sprite a side. Capping on the " +
            "QUAD's width leaves EDGE_INSET_METRES of empty margin that only the body can fill; " +
            "capping on CREST_OUTER_HALF_WIDTH puts the frame edge on the transparent wrap border " +
            "and the extreme pixel column comes back empty"
        )

        // ...and therefore NO body quad is submitted at all: its span at the cap is exactly zero.
        // This is the "only one sprite" requirement stated as a consequence rather than a number.
        val bodyVisible = Framing.VISIBLE_WIDTH_METRES * 0.5f - RockFace.BODY_INNER_HALF_WIDTH
        assertEquals(
            0f, bodyVisible, 1e-4f,
            "the body is $bodyVisible m wide at the cap — a second sprite's worth of rock beside " +
            "the edge art, which is exactly what this cap exists to remove"
        )
        assertEquals(
            0, RockFace.bodyColumns(bodyVisible),
            "a body quad is still submitted at the cap, for ${bodyVisible} m of screen"
        )
    }

    /**
     * THE COST OF THAT CAP IS BOUNDED, AND DELIBERATE.
     *
     * The design aspect is 1.7396 and 16:9 is 1.7778, so a 16:9 panel IS scaled by width and does
     * lose depth. That is intentional — the owner chose one sprite over the last 1.3 m of water,
     * and was shown this exact table before choosing.
     *
     * An earlier version of this file asserted the opposite (`designAspect >= 16f/9f`, "16:9 must
     * never lose depth"). That assertion was correct for the cap of the time and is now wrong; it
     * is replaced rather than deleted so the reversal is visible. What is asserted instead is that
     * the loss stays SMALL — a re-bake that made the cliff art much narrower would shrink the cap,
     * cost real depth, and fail here rather than pass quietly.
     */
    @Test
    fun `capping the width costs a bounded amount of visible depth`()
    {
        val designAspect = Framing.VISIBLE_WIDTH_METRES / Framing.VISIBLE_DEPTH_METRES
        val atSixteenNine = Framing.VISIBLE_WIDTH_METRES / (16f / 9f)

        assertTrue(
            designAspect < 16f / 9f,
            "the design aspect is $designAspect, at or above 16:9 — then 16:9 is not capped at all " +
            "and the body fills the surplus, which is the two-sprite state this cap replaced"
        )
        assertTrue(
            atSixteenNine > Framing.VISIBLE_DEPTH_METRES * 0.95f,
            "a 16:9 panel shows $atSixteenNine m of water against ${Framing.VISIBLE_DEPTH_METRES} m — " +
            "more than 5% of the player's planning distance has been traded away for the framing, " +
            "which is past what was agreed"
        )
    }
}
