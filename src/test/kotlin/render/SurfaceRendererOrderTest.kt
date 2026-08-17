package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A SOURCE-SCANNING GUARD over the order two renderers are attached in `EnPustTil.onCreate`, for a
 * failure that produces no error, no warning and no log line, and that looks exactly like a broken
 * shader.
 *
 * ## The mechanism, which has outlived the bug that found it
 *
 * Batch renderers are flushed in the order they were ADDED to a surface, not in the order they were
 * called — `IridescenceRenderer`'s own class doc says so, and it is why every primitive participates
 * in `SurfaceConfigInternal`'s shared depth cursor instead of relying on call order. What that doc
 * does not say is that every renderer WRITES DEPTH, including for fragments it draws at alpha 0.
 *
 * So between any two batch renderers on one surface, whichever is ADDED FIRST wins the depth test
 * wherever they overlap, regardless of which the game asked to draw first.
 *
 * ## WHAT THIS USED TO ASSERT, AND WHY IT NOW ASSERTS SOMETHING ELSE
 *
 * It guarded `WaterRenderer.addTo` and `IridescenceRenderer.addTo` against `DiveLighting.setup`,
 * which attached `ShaftRenderer` — the god rays, whose quads deliberately took a greater
 * `currentDepth` than the world so they landed in front of it. Attached after that call, the sea
 * and the pearls were flushed second and lost the depth test to strips that had already written
 * theirs. Both failures were measured:
 *
 *  - the sea, captured at 6 m with `water.frag` forced to a flat opaque magenta: the quad
 *    rasterised from -1.5 m to 0.0 m and vanished completely from 0.0 m to 8.2 m — a cut at exactly
 *    the depth the shafts started from, and nowhere else;
 *  - the pearls, on a pinned 30 m frame at four pearls' own centre pixels, against the same build
 *    with the shafts skipped:
 *
 * ```
 *   depth    with god rays          without
 *   15.8 m   RGBA(0, 8, 21, 255)    RGBA(137, 109, 18, 255)
 *   19.8 m   RGBA(0, 9, 23, 255)    RGBA(160, 122, 14, 255)
 *   27.8 m   RGBA(0, 9, 20, 255)    RGBA(196, 160, 28, 255)
 * ```
 *
 * Every shallow pearl was erased — not dimmed, replaced by plain water at full alpha, with only its
 * GI light surviving on a different surface with a different depth buffer. A pearl read as a soft
 * glow with no body, which looks like art, which is why it shipped.
 *
 * **The god rays were removed on 2026-08-17 at the owner's request**, and `DiveLighting.setup` now
 * attaches no batch renderer to `mainSurface` at all. Asserting against it would be a test that
 * cannot fail for any reason that matters, which this codebase treats as worse than no test
 * (`4493eeb`).
 *
 * The evidence above is kept because the MECHANISM is unchanged and it is the only measurement of
 * it we have. What is asserted below is the ordering constraint that is still live between the two
 * renderers that remain — and if anything is ever attached in `DiveLighting.setup` again, or a
 * third renderer joins `mainSurface` from any file, the rule to apply is the general one:
 * **anything that is part of the world is attached before anything that draws in front of it.**
 */
class SurfaceRendererOrderTest
{
    /**
     * THE SEA IS ATTACHED BEFORE THE PEARLS, and reversing it puts pearl-shaped holes in the water.
     *
     * Both are part of the world and both are on `mainSurface`. The sea is drawn behind the pearls,
     * so it takes a lesser `currentDepth` — but depth only decides the outcome once both have
     * rasterised, and whichever renderer was ADDED first rasterises first. In today's order the sea
     * writes its depth, then the pearls pass `GL_LEQUAL` in front of it, which is correct.
     *
     * Reversed, the pearls rasterise first and write depth across their own quads, and every water
     * fragment behind one then fails `GL_LEQUAL` — so each pearl would punch a hole through the sea
     * to whatever the background is, at full alpha and with no log line. That is the same mechanism
     * as the two failures in the class doc, between the two renderers that are still here.
     */
    @Test
    fun `the sea is attached to the world surface before the pearls`()
    {
        val code = File("src/main/kotlin/EnPustTil.kt").readText()

        val water = code.indexOf("WaterRenderer.addTo(engine.gfx.mainSurface)")
        val pearls = code.indexOf("IridescenceRenderer.addTo(engine.gfx.mainSurface)")

        assertTrue(water >= 0, "EnPustTil.onCreate no longer attaches WaterRenderer to the world surface at all")
        assertTrue(
            pearls >= 0,
            "EnPustTil.onCreate no longer attaches IridescenceRenderer to the WORLD surface. Matching the " +
            "argument and not just the method name is deliberate: the HUD has its own instance on another " +
            "surface with its own depth buffer, and attaching only that one early must not satisfy this"
        )
        assertTrue(
            water < pearls,
            "WaterRenderer is attached AFTER IridescenceRenderer on mainSurface. Batch renderers are flushed " +
            "in the order they were ADDED and every one writes depth, so the pearls will rasterise first and " +
            "every water fragment behind a pearl will fail GL_LEQUAL — each pearl punches a hole through the " +
            "sea, at full alpha, with no error and no log line. See this test's class doc"
        )
    }

    /**
     * BOTH ARE STILL ATTACHED BEFORE `DiveLighting.setup`, which is now a convention rather than a
     * constraint — and that is exactly why it is worth one cheap assertion.
     *
     * `DiveLighting.setup` attaches no batch renderer today, so nothing breaks if this is violated.
     * It is asserted as a **canary**: the next thing attached in there will be attached after the
     * world, which is the position both measured failures in the class doc came from. Failing here
     * costs a reader one look at that doc; not having it cost the sea and then every shallow pearl.
     */
    @Test
    fun `the world's renderers are still attached before DiveLighting sets up`()
    {
        val code = File("src/main/kotlin/EnPustTil.kt").readText()

        val water = code.indexOf("WaterRenderer.addTo(engine.gfx.mainSurface)")
        val pearls = code.indexOf("IridescenceRenderer.addTo(engine.gfx.mainSurface)")
        val lighting = code.indexOf("DiveLighting.setup(engine)")

        assertTrue(lighting >= 0, "EnPustTil.onCreate no longer calls DiveLighting.setup — re-read this test before deleting it")
        assertTrue(
            water < lighting && pearls < lighting,
            "A world renderer is now attached AFTER DiveLighting.setup. That is harmless only while setup " +
            "attaches no batch renderer of its own, which is true today and was NOT true while the god rays " +
            "existed — in that arrangement the sea vanished from 0 m to 8.2 m and every shallow pearl was " +
            "erased. Move it back above, or read this test's class doc and decide deliberately"
        )
    }
}
