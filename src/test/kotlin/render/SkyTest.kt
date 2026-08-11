package render

import dive.Tuning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT CANNOT BE TESTED HERE: whether the sunset is beautiful. That needed a framebuffer and the
 * owner's eye, and it got both — see this task's contact sheet.
 *
 * What IS provable is the set of things that would be wrong in a way nobody notices until the
 * booth:
 *
 *  - the gradient is a function of HEIGHT ABOVE THE WATER, so the horizon stays on the horizon as
 *    the diver descends. Anchoring it to the visible rect instead makes the whole sunset scroll
 *    with the camera, which looks fine in any single screenshot and wrong in every dive.
 *  - it CLAMPS at both ends. Below the waterline the sky is behind opaque water and its colour is
 *    irrelevant — but `blend` is still evaluated there (the strips run past the waterline on
 *    purpose, see `Sky.render`), and an unclamped ramp there would run off the end of the stop
 *    table.
 *  - the strip walk is bounded and refuses a degenerate camera rect, exactly as
 *    `DiveRenderer.stripCount` is and for the same reason: the visible rect is computed by the
 *    engine before any of our code runs and can be empty, inverted or enormous on frame one.
 *  - the sky is on ITS OWN SURFACE, BEHIND the world. That is the decision the whole design rests
 *    on and the one that would be silently undone by an edit that looks like tidying.
 */
class SkyTest
{
    /**
     * THE HORIZON IS NAILED TO THE WATER. `heightAt` is the whole of the anchoring: it measures
     * from [Tuning.SURFACE_DEPTH], not from the top of the frame, so a camera at 0 m and a camera
     * at 5 m see the same colour at the same world depth.
     */
    @Test
    fun `the gradient is a function of world depth and not of where the camera is`()
    {
        for (depth in listOf(-24f, -12f, -3f, 0f, 4f))
        {
            val h = Sky.heightAt(depth)
            assertEquals(Tuning.SURFACE_DEPTH - depth, h, 1e-5f, "the height above water at depth $depth is not measured from the waterline")
        }
        // The colour at a given world depth is the same number whatever else is going on.
        assertEquals(Sky.redAt(Sky.heightAt(-10f)), Sky.redAt(10f), 1e-6f, "the ramp is not a pure function of height above the water")
    }

    /**
     * THE WARM END IS AT THE BOTTOM AND THE COLD END AT THE TOP, and the ramp gets there without
     * a flat stretch. A sunset that is uniformly orange-to-purple over the whole sky reads as a
     * colour ramp; a low sun squeezes the warm end into the first few metres.
     *
     * Stated as a monotonic property rather than as a list of expected colours, so retuning the
     * stops is free and inverting them is not.
     */
    @Test
    fun `the sky runs warm at the horizon and cold at the zenith, monotonically`()
    {
        val heights = generateSequence(0f) { it + 0.25f }.takeWhile { it <= Sky.SPAN_METRES }.toList()
        var lastRed = Float.MAX_VALUE
        heights.forEach { h ->
            val red = Sky.redAt(h)
            assertTrue(red <= lastRed + 1e-5f, "red rises again at $h m; the sunset must fade upward, not pulse")
            lastRed = red
        }

        assertTrue(Sky.redAt(0f) > Sky.greenAt(0f) && Sky.greenAt(0f) > Sky.blueAt(0f), "the horizon is not warm")
        assertTrue(Sky.blueAt(Sky.SPAN_METRES) > Sky.redAt(Sky.SPAN_METRES), "the zenith is not cool")
        assertTrue(
            Sky.redAt(0f) - Sky.redAt(Sky.SPAN_METRES) > 0.5f,
            "there is less than half a unit of red between the horizon and the zenith — that is a tint, not a sunset"
        )
    }

    /** Below the waterline and above the authored span, the ramp must hold rather than run off its table. */
    @Test
    fun `the ramp clamps at both ends and survives a NaN`()
    {
        listOf(Sky::redAt, Sky::greenAt, Sky::blueAt).forEach { channel ->
            assertEquals(channel(0f), channel(-50f), 1e-6f, "the ramp does not clamp below the waterline, where Sky.render still evaluates it")
            assertEquals(channel(Sky.SPAN_METRES), channel(Sky.SPAN_METRES + 100f), 1e-6f, "the ramp does not clamp above its last stop")
            assertEquals(channel(0f), channel(Float.NaN), 1e-6f, "a NaN height does not clamp to the horizon")
            for (h in listOf(0f, 1f, 5f, 12f, 25f, 26f))
            {
                val v = channel(h)
                assertTrue(v in 0f..1f, "the ramp leaves [0,1] at $h m, giving $v — setDrawColor would wrap it")
            }
        }
    }

    /**
     * The sky is authored over slightly MORE than the camera can ever show above the water, so its
     * last stop lands just off the top of the frame. A gradient whose final stop sits exactly on
     * the frame edge reads as a band across the top.
     */
    @Test
    fun `the authored span covers more sky than the camera can ever show`()
    {
        val mostVisible = Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_SCREEN_FRACTION
        assertTrue(
            Sky.SPAN_METRES > mostVisible,
            "the gradient is authored over ${Sky.SPAN_METRES} m but the camera can show $mostVisible m of sky, so the zenith colour is never reached"
        )
        assertTrue(
            Sky.SPAN_METRES < mostVisible * 1.5f,
            "the gradient is authored over far more sky than can be seen, so most of it is unreachable"
        )
    }

    /** Same bounds, and the same reason, as `DiveRenderer.stripCount`'s. */
    @Test
    fun `the strip walk covers the band, is bounded, and refuses a degenerate rect`()
    {
        assertEquals(0, Sky.stripCount(0f, 0f), "an empty rect must draw no strips")
        assertEquals(0, Sky.stripCount(10f, 0f), "an inverted rect must draw no strips")
        assertEquals(0, Sky.stripCount(Float.NaN, 10f), "a NaN rect must draw no strips")
        assertTrue(Sky.stripCount(-1e9f, 1e9f) <= Sky.MAX_STRIPS, "an enormous rect must still bound the loop")

        // A normal frame at the surface: from the camera's top down to the hand-off depth.
        val top = -Framing.VISIBLE_DEPTH_METRES * Framing.DIVER_SCREEN_FRACTION
        val count = Sky.stripCount(top, WaterSurface.QUAD_BOTTOM_DEPTH)
        assertTrue(
            top + count * Sky.STRIP_METRES >= WaterSurface.QUAD_BOTTOM_DEPTH,
            "$count strips from $top stop short of the water quad's bottom, leaving an unpainted gap the sea's alpha ramp would blend against nothing"
        )
        assertTrue(
            top + (count - 1) * Sky.STRIP_METRES < WaterSurface.QUAD_BOTTOM_DEPTH,
            "$count strips is one more than needed"
        )
    }

    /**
     * IT COSTS NOTHING WHERE IT IS NOT VISIBLE. Once the camera's top edge is below the water
     * quad's bottom — about 32 m of diver depth — the sky issues no draws at all. That is the
     * claim the deep captures were taken to check, stated here so it cannot quietly stop being
     * true.
     */
    @Test
    fun `the sky draws nothing once the surface is out of frame`()
    {
        val topAtDepth = { d: Float -> Framing.targetCameraDepth(d) }
        assertEquals(0, Sky.stripCount(topAtDepth(70f), WaterSurface.QUAD_BOTTOM_DEPTH), "the sky is still walking strips at 70 m")
        assertEquals(0, Sky.stripCount(topAtDepth(150f), WaterSurface.QUAD_BOTTOM_DEPTH), "the sky is still walking strips in the Abyss")
        assertTrue(Sky.stripCount(topAtDepth(0f), WaterSurface.QUAD_BOTTOM_DEPTH) > 0, "the sky draws nothing at the surface either")
    }

    /**
     * THE SKY MUST BE BEHIND THE WORLD, AND THE WORLD MUST BE TRANSPARENT — a source scan, in the
     * spirit of `MainCameraOwnershipTest` and `DrawTest`, because neither half can be asserted
     * without a framebuffer and both are one tidy-looking edit away from being undone.
     *
     * If the sky's zOrder stopped exceeding `main`'s it would composite ON TOP of the world and
     * paint out the cliffs and the diver; if `mainSurface`'s background stopped being transparent
     * a flat navy field would sit between the sky and the sea. The first would be obvious; the
     * second reads as "the sky is not working" with no clue why.
     */
    @Test
    fun `the sky surface is created behind a transparent world surface`()
    {
        val code = File("src/main/kotlin/EnPustTil.kt").readText()

        assertTrue(
            code.contains("mainSurface.config.zOrder + Sky.Z_ORDER_OFFSET"),
            "the sky's zOrder is no longer main's plus Sky.Z_ORDER_OFFSET. GraphicsImpl composites sorted by -zOrder ascending, so a LARGER zOrder is drawn EARLIER, i.e. behind — the opposite sense to HUD_Z_ORDER"
        )
        assertTrue(
            code.contains("mainSurface.setBackgroundColor(Color.BLANK)"),
            "mainSurface's background is no longer transparent, so the sky cannot show through where DiveRenderer draws no water"
        )
        assertTrue(
            Sky.Z_ORDER_OFFSET > 9,
            "GlobalIlluminationSystem takes mainZOrder + 1 .. + 9 for its own surfaces; the sky must clear them"
        )
    }
}
