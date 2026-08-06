// COPY OF AN ENGINE FILE — pulse-engine 0.13.0, /pulseengine/shaders/renderers/quad.vert.
//
// This file shadows the engine's own copy on the classpath. It exists to repair
// Surface.drawQuad(), which on macOS / Apple Silicon renders nothing at all — silently,
// with no GL error and no log line.
//
// Why: QuadRenderer.kt:37 declares the packed-colour vertex attribute as
// `.withAttribute("color", 1, GL_FLOAT)`, which ShaderProgram.setVertexAttributeLayout
// (ShaderProgram.kt:105-116) routes through glVertexAttribPointer — while the engine's
// quad.vert declares the same attribute `in uint color`. Feeding an integer-typed vertex
// shader input through glVertexAttribPointer is undefined behaviour per the OpenGL spec
// (integer inputs require glVertexAttribIPointer), so no error is raised and nothing is
// logged. Apple's GL-over-Metal layer resolves the undefined value as 0; the shader derives
// alpha as `rgba & 255u`, so every quad rasterises at alpha 0. TextureRenderer.kt:41 and
// TextRenderer.kt:49 bind the same attribute as GL_UNSIGNED_INT and are unaffected, which is
// why drawTexture/drawText work and drawQuad/drawLine do not.
//
// What changed from the engine's file, and nothing else:
//   1. `in uint color` -> `in float color`, matching the GL_FLOAT binding the engine
//      actually performs. SurfaceConfig.kt:62 already bit-casts the packed RGBA integer
//      into a float (`intBitsToFloat`) before it reaches the VBO, so the 32 bits in the
//      buffer are the same either way — only the declared type was lying.
//   2. `unpackAndConvert(color)` -> `unpackAndConvert(floatBitsToUint(color))`, recovering
//      those bits. Verified byte-exact against a drawTexture reference across five swatches
//      chosen to hit the dangerous patterns, including 0xFFFFFFFF (which reinterprets as a
//      NaN) and 0x00000001 (the smallest denormal).
//   3. `#version 150 core` -> `#version 330 core`, required by floatBitsToUint (GLSL 3.30).
//      The version was NOT the bug — the engine was rebuilt with only the version bumped and
//      quads stayed invisible; texture.frag, glyph.frag, surface.vert and surface.frag are
//      all 150 core and work fine.
//
// This is more spec-correct than upstream on every platform, but it is a copy of a file we
// do not own: it is pinned to 0.13.0 and must be re-diffed against the engine's version if
// the dependency is ever bumped. It is deliberately excluded from the Windows release jar —
// see the shader-override block in build.gradle.kts for why.
//
// Full analysis: docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md

#version 330 core

in vec3 position;
in float color;

out vec4 vertexColor;

uniform mat4 viewProjection;

vec4 unpackAndConvert(uint rgba)
{
    // Unpack the rgba color and convert it from sRGB to linear space
    vec4 sRgba = vec4((rgba >> 24u) & 255u, (rgba >> 16u) & 255u, (rgba >> 8u) & 255u, rgba & 255u) / 255.0;
    vec3 lowRange = sRgba.rgb / 12.92;
    vec3 highRange = pow((sRgba.rgb + 0.055) / 1.055, vec3(2.4));
    vec3 linearRgb = mix(highRange, lowRange, lessThanEqual(sRgba.rgb, vec3(0.0031308)));
    return vec4(linearRgb, sRgba.a);
}

void main()
{
    vertexColor = unpackAndConvert(floatBitsToUint(color));
    gl_Position = viewProjection * vec4(position, 1.0);
}
