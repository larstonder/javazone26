package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `textOutlineOffset` is the one pure piece of [drawTextWithOutline] — the rest issues real
 * `drawText`/`setDrawColor` calls against a `Surface` and needs a live GL context to verify.
 * What matters here is resolution independence: `engine.window.width/height` are PHYSICAL
 * framebuffer pixels (2400x1800 on a Retina Mac, not the 1200x900 in application.cfg), so a
 * fixed-pixel outline offset that looked right at 1200x900 would be sub-pixel — effectively
 * invisible — at 4K. The offset must scale with screen height instead.
 */
class DrawTest
{
    @Test
    fun `offset scales proportionally with screen height`() {
        val at900 = textOutlineOffset(900f)
        val at1800 = textOutlineOffset(1800f)

        // Doubling the physical resolution must double the offset — a fixed pixel count
        // would instead stay constant (and read as vanishing on the larger screen).
        assertEquals(at900 * 2f, at1800, 0.0001f)
    }

    @Test
    fun `offset reproduces the reference's 2px look at 1080p`() {
        // The reference (caesars-salads) hardcodes a 2px offset at its target resolution
        // (1920x1080). Our fraction-of-height approach should reproduce that exact value
        // at that exact height.
        assertEquals(2f, textOutlineOffset(1080f), 0.01f)
    }

    @Test
    fun `offset is zero for zero height and positive for any positive height`() {
        assertEquals(0f, textOutlineOffset(0f), 0.0001f)
        assertTrue(textOutlineOffset(1800f) > 0f)
    }
}
