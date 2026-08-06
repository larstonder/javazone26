package render

import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color

/**
 * Draw a solid rectangle in the surface's current draw colour.
 *
 * DO NOT replace this with [Surface.drawQuad], even though `drawQuad` now works in dev.
 *
 * On macOS / Apple Silicon, stock `drawQuad` and `drawLine` render nothing at all —
 * silently, with no GL error and no log output. The cause is **not** the shader `#version`,
 * which an earlier version of this comment claimed and which is disproven: `texture.frag`,
 * `glyph.frag`, `surface.vert` and `surface.frag` are all `150 core` and work fine, and the
 * engine was rebuilt with `quad.vert` bumped to `330 core` and nothing else — quads stayed
 * invisible.
 *
 * The real cause is a vertex-attribute *binding type* mismatch. All four renderers pack the
 * RGBA colour into one 32-bit slot (`SurfaceConfig.kt:62` bit-casts it with `intBitsToFloat`)
 * and all four shaders declare that attribute `in uint`, but only two of them bind it as an
 * integer:
 *
 *   drawText     TextRenderer.kt:49     GL_UNSIGNED_INT -> glVertexAttribIPointer   works
 *   drawTexture  TextureRenderer.kt:41  GL_UNSIGNED_INT -> glVertexAttribIPointer   works
 *   drawQuad     QuadRenderer.kt:37     GL_FLOAT        -> glVertexAttribPointer    renders nothing
 *   drawLine     LineRenderer.kt:34     GL_FLOAT        -> glVertexAttribPointer    renders nothing
 *
 * (the dispatch is `ShaderProgram.setVertexAttributeLayout`, `ShaderProgram.kt:105-116`).
 * Feeding an integer-typed vertex input through `glVertexAttribPointer` is undefined
 * behaviour per the OpenGL spec — integer inputs require `glVertexAttribIPointer` — so no GL
 * error is raised and nothing is logged. Apple's GL-over-Metal layer delivers 0; the shader
 * derives alpha as `rgba & 255u`, so every quad and line rasterises at alpha 0. Windows
 * drivers are believed to pass the raw 32 bits through, which is why upstream never noticed.
 *
 * Verified empirically by rendering both in the same frame and reading the framebuffer back:
 * the textured rectangles appear, the quads and lines do not. Full write-up in
 * `docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md`.
 *
 * ## Why `fillRect` stays, now that the shaders are shadowed
 *
 * `src/main/resources/pulseengine/shaders/renderers/{quad,line}.vert` shadow the engine's
 * copies and repair both primitives — but **only on the development classpath**. The Windows
 * release jar deliberately keeps the engine's copies (see the shader-override block in
 * `build.gradle.kts`): the fix is unverified on Windows, and the booth `.exe` must stay the
 * rendering behaviour that was playtested. So on the booth cabinet `drawQuad` is still the
 * broken stock version. It happens not to matter there — but only because nothing in this
 * game calls it.
 *
 * That is the trap: "simplify `fillRect` into `drawQuad`" would look completely fine in dev
 * on this Mac and would then draw *nothing* in the shipped `.exe` if the bug is not in fact
 * macOS-only — a failure discovered in front of a queue, with no error message to go on.
 * `fillRect` also batches through the same texture renderer as every sprite, so it is not
 * even a performance argument. Keep it.
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
