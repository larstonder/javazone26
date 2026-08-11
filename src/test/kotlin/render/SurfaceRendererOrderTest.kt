package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A SOURCE-SCANNING GUARD over one line of `EnPustTil.onCreate`, for a failure that produced no
 * error, no warning and no log line, and that looked exactly like a broken shader.
 *
 * ## The bug, measured
 *
 * Batch renderers are flushed in the order they were ADDED to a surface, not in the order they
 * were called — `IridescenceRenderer`'s own class doc says so, and it is why every primitive
 * participates in `SurfaceConfigInternal`'s shared depth cursor instead of relying on call order.
 * What that doc does not say, because it did not matter until there were three of them, is that
 * every renderer WRITES DEPTH, including for fragments it draws at alpha 0.
 *
 * `DiveLighting.setup` attaches `ShaftRenderer`, whose quads deliberately take a greater
 * `currentDepth` than the world so the god rays land in front of it. `WaterRenderer` was
 * originally attached after that call. The result: the shafts' quads were flushed first and wrote
 * their depth across the whole near-surface band, and every water fragment underneath one then
 * failed `GL_LEQUAL`.
 *
 * Captured at 6 m with `water.frag` forced to a flat opaque magenta: the quad rasterised from
 * -1.5 m to 0.0 m and vanished completely from 0.0 m to 8.2 m — a cut at exactly the depth the
 * shafts start from, and nowhere else. From the outside it was indistinguishable from a shader
 * that discards everything.
 *
 * ## Why a source scan
 *
 * The invariant is about the ORDER of two calls in one method, and its effect only exists once
 * two batch renderers have both rasterised into a real depth buffer. There is nothing pure to
 * assert and no way to reach it without a GL context. What is automatable is the order itself.
 *
 * ## What it says
 *
 *   > `WaterRenderer.addTo` is called before `DiveLighting.setup` in `EnPustTil.onCreate`.
 *
 * Stated as the general rule: THE SEA IS PART OF THE WORLD AND MUST BE FLUSHED WITH THE WORLD.
 * Anything that draws in FRONT of the world — the god rays, and anything like them later —
 * belongs after it.
 *
 * ## THE PEARLS WERE IN EXACTLY THAT POSITION, AND IT WAS NOT DESIGN
 *
 * An earlier version of this doc said: *"`IridescenceRenderer` is attached after
 * `DiveLighting.setup` today and the pearls it draws are therefore behind the shafts' depth in
 * the same way — that is the shafts agent's design, was true before this test existed, and is
 * deliberately not asserted here."*
 *
 * It was the same bug, on the objects the whole game is about. Measured on a pinned 30 m frame at
 * four pearls' own centre pixels, against the same build with the shafts skipped:
 *
 * ```
 *   depth    with god rays          without
 *   15.8 m   RGBA(0, 8, 21, 255)    RGBA(137, 109, 18, 255)
 *   19.8 m   RGBA(0, 9, 23, 255)    RGBA(160, 122, 14, 255)
 *   27.8 m   RGBA(0, 9, 20, 255)    RGBA(196, 160, 28, 255)
 * ```
 *
 * Every pearl above `LightShafts.END_DEPTH_METRES` was erased — not dimmed, replaced by plain
 * water at full alpha. Only its GI light survived, on a different surface with a different depth
 * buffer, so a shallow pearl read as a soft glow with no body. That looks like art, which is why
 * it shipped and why this doc rationalised it.
 *
 * So the rule covers both, and the general form is the one to keep: ANYTHING THAT IS PART OF THE
 * WORLD IS ATTACHED BEFORE `DiveLighting.setup`; only things that draw in FRONT of the world go
 * after it. A fifth renderer added in a different file still is not covered here.
 */
class SurfaceRendererOrderTest
{
    @Test
    fun `the water renderer is attached to the world surface before anything that draws in front of it`()
    {
        val code = File("src/main/kotlin/EnPustTil.kt").readText()

        val water = code.indexOf("WaterRenderer.addTo(engine.gfx.mainSurface)")
        val lighting = code.indexOf("DiveLighting.setup(engine)")

        assertTrue(water >= 0, "EnPustTil.onCreate no longer attaches WaterRenderer to the world surface at all")
        assertTrue(lighting >= 0, "EnPustTil.onCreate no longer calls DiveLighting.setup — re-read this test before deleting it")
        assertTrue(
            water < lighting,
            "WaterRenderer is attached AFTER DiveLighting.setup, which attaches ShaftRenderer. Batch renderers are flushed in the order they were ADDED and every one of them writes depth, so the shafts' quads will already have written theirs and every water fragment under a shaft will fail GL_LEQUAL. Measured: the sea vanishes from 0 m to 8.2 m with no error and no log line. See this test's class doc"
        )
    }

    /**
     * THE PEARLS ARE PART OF THE WORLD TOO — the same rule, and the failure is worse.
     *
     * The sea vanishing is a look bug. The pearls vanishing is the GAME vanishing: every one
     * above `LightShafts.END_DEPTH_METRES` was depth-rejected by the shaft quads, leaving only
     * its GI light — a different surface with a different depth buffer — as a soft glow with no
     * body.
     *
     * Asserted on the WORLD surface's instance specifically. The HUD's instance is on another
     * surface with its own depth buffer and its order there is irrelevant, so matching the
     * argument rather than just the method name is what makes this a real test: attaching only
     * the HUD's copy early would otherwise satisfy it.
     */
    @Test
    fun `the world's iridescence renderer is attached before anything that draws in front of the world`()
    {
        val code = File("src/main/kotlin/EnPustTil.kt").readText()

        val pearls = code.indexOf("IridescenceRenderer.addTo(engine.gfx.mainSurface)")
        val lighting = code.indexOf("DiveLighting.setup(engine)")

        assertTrue(pearls >= 0, "EnPustTil.onCreate no longer attaches IridescenceRenderer to the WORLD surface at all")
        assertTrue(lighting >= 0, "EnPustTil.onCreate no longer calls DiveLighting.setup — re-read this test before deleting it")
        assertTrue(
            pearls < lighting,
            "IridescenceRenderer is attached to mainSurface AFTER DiveLighting.setup, which attaches ShaftRenderer. " +
            "Batch renderers are flushed in the order they were ADDED and every one writes depth, including for its " +
            "transparent fragments, so every pearl above LightShafts.END_DEPTH_METRES fails GL_LEQUAL behind a shaft " +
            "quad. Measured at a pearl's own centre pixel at 19.8 m: RGBA(0, 9, 23, 255) with the god rays against " +
            "RGBA(160, 122, 14, 255) without them — erased, not dimmed, with only its GI light left to look at. " +
            "See this test's class doc"
        )
    }
}
