package render

import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color

/**
 * Draw a solid rectangle in the surface's current draw colour.
 *
 * DO NOT replace this with [Surface.drawQuad].
 *
 * On macOS / Apple Silicon, `drawQuad` renders nothing at all — silently, with no GL
 * error and no log output. The cause is the shader version the engine's renderers declare:
 *
 *   drawText     -> glyph.vert    #version 330 core   works
 *   drawTexture  -> texture.vert  #version 330 core   works
 *   drawQuad     -> quad.vert     #version 150 core   renders nothing
 *   drawLine     -> line.vert     #version 150 core   renders nothing
 *
 * Verified empirically by rendering both in the same frame and reading the framebuffer
 * back: the textured rectangles appear, the quads do not. Drawing a blank texture tinted
 * by the current draw colour is the supported cross-platform alternative, and is what the
 * engine's own examples use for sprites.
 */
fun Surface.fillRect(x: Float, y: Float, width: Float, height: Float) =
    drawTexture(Texture.BLANK, x, y, width, height)

/**
 * The reference (`caesars-salads/utils/Extensions.kt:52-58`) offsets its outline by a flat
 * 2 pixels at its target resolution (1920x1080 — see its README). `engine.window.width/height`
 * here are PHYSICAL framebuffer pixels (2400x1800 on a Retina Mac, not the 1200x900 in
 * application.cfg), so a fixed 2px offset that reads fine at 1080p would be sub-pixel — and
 * likely invisible after texture filtering — on a 4K booth display. Expressing the same
 * offset as a fraction of screen height reproduces the reference's look at its own
 * resolution and scales correctly at any other.
 */
private const val TEXT_OUTLINE_OFFSET_FRACTION = 2f / 1080f

/**
 * Pure so it is unit-testable without a `Surface`/GL context — see [drawTextWithOutline].
 */
fun textOutlineOffset(screenHeight: Float): Float = screenHeight * TEXT_OUTLINE_OFFSET_FRACTION

/**
 * Draws [text] with a single black offset pass behind it, then the real colour on top —
 * the same idea as the reference's `drawTextWithOutline`, and deliberately as cheap: one
 * extra `drawText` call, not a multi-directional outline. HUD text spans a background that
 * runs from bright shallows to near-black abyss (see `DiveLighting`'s ambient/zone tables),
 * so a colour tuned to read on one end reads poorly on the other; the outline makes it
 * legible against both.
 *
 * Takes raw `rgba` floats rather than a `Color` so callers that already avoid a per-frame
 * `Color` allocation (see [Hud]'s `drawHeld`, which interpolates amber -> red-amber floats
 * directly) are not forced into one just to get an outline. Fine to call a handful of times
 * per frame (HUD text formatting is explicitly exempt from the no-allocation rule) — not
 * meant for per-entity or per-pixel use.
 */
fun Surface.drawTextWithOutline(
    text: String,
    x: Float,
    y: Float,
    fontSize: Float,
    screenHeight: Float,
    r: Float,
    g: Float,
    b: Float,
    a: Float = 1f,
    xOrigin: Float = 0f,
    yOrigin: Float = 0f
)
{
    val offset = textOutlineOffset(screenHeight)
    setDrawColor(0f, 0f, 0f, 1f)
    drawText(text, x + offset, y + offset, fontSize = fontSize, xOrigin = xOrigin, yOrigin = yOrigin)
    setDrawColor(r, g, b, a)
    drawText(text, x, y, fontSize = fontSize, xOrigin = xOrigin, yOrigin = yOrigin)
}

/** Convenience overload for callers holding a pre-allocated [Color] constant (no allocation). */
fun Surface.drawTextWithOutline(
    text: String,
    x: Float,
    y: Float,
    fontSize: Float,
    screenHeight: Float,
    color: Color,
    xOrigin: Float = 0f,
    yOrigin: Float = 0f
) = drawTextWithOutline(text, x, y, fontSize, screenHeight, color.red, color.green, color.blue, color.alpha, xOrigin, yOrigin)
