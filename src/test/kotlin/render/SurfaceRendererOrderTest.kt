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
 * WHAT IT DOES NOT COVER, so nobody assumes more: it says nothing about the ORDER OF THE OTHER
 * renderers relative to each other, and nothing about a fourth one added in a different file.
 * `IridescenceRenderer` is attached after `DiveLighting.setup` today and the pearls it draws are
 * therefore behind the shafts' depth in the same way — that is the shafts agent's design, was
 * true before this test existed, and is deliberately not asserted here.
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
}
