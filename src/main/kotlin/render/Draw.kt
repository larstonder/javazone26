package render

import no.njoh.pulseengine.core.asset.types.Texture
import no.njoh.pulseengine.core.graphics.surface.Surface

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
