// COPY OF AN ENGINE FILE — pulse-engine 0.13.0, /pulseengine/shaders/renderers/line.vert.
//
// This file shadows the engine's own copy on the classpath. It is the exact counterpart of
// quad.vert next to it, for Surface.drawLine() instead of Surface.drawQuad(): same bug, same
// fix, same three edits. The full explanation lives in quad.vert's header — read that one.
//
// In brief: LineRenderer.kt:34 binds the packed-colour attribute as
// `.withAttribute("rgbaColor", 1, GL_FLOAT)` (so glVertexAttribPointer) while the engine's
// line.vert declares it `in uint rgbaColor`. An integer vertex input fed through the float
// path is undefined behaviour per the OpenGL spec; Apple's driver delivers 0, alpha comes out
// 0, and every line rasterises fully transparent with no GL error and no log line.
//
// What changed from the engine's file, and nothing else:
//   1. `in uint rgbaColor` -> `in float rgbaColor`, matching the binding the engine performs.
//   2. `unpackAndConvert(rgbaColor)` -> `unpackAndConvert(floatBitsToUint(rgbaColor))`.
//   3. `#version 150 core` -> `#version 330 core`, required by floatBitsToUint (GLSL 3.30).
//      The version was never the cause — see quad.vert.
//
// Pinned to 0.13.0; re-diff against the engine's version if the dependency is bumped.
// Deliberately excluded from the Windows release jar — see build.gradle.kts.
//
// Full analysis: docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md

#version 330 core

in vec3 position;
in float rgbaColor;

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
    vertexColor = unpackAndConvert(floatBitsToUint(rgbaColor));
    gl_Position = viewProjection * vec4(position, 1.0);
}
