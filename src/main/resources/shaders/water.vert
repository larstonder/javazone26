// The game's own water-surface shader — see water.frag for what it draws and
// render/WaterRenderer.kt for the batching and the uniforms.
//
// NOT a copy of an engine file, and deliberately under /shaders/ rather than
// /pulseengine/shaders/: the two files in src/main/resources/pulseengine/shaders/renderers/ ARE
// shadows of engine files and are excluded from the release jar by build.gradle.kts's
// devOnlyShaderOverrides, so anything that lives there risks being dropped from the .exe. This
// one shadows nothing, cannot be caught by that exclusion, and must ship. WaterShaderTest
// asserts both properties through a CLASSLOADER probe rather than by listing the jar — the two
// disagree about duplicate entries.
//
// #version 330 core, not higher: macOS caps OpenGL at 4.1. Same constraint as iridescence.vert.
//
// Structurally the same stripped-down copy of the engine's renderers/texture.vert that
// iridescence.vert is: one shared unit quad, one instance, the viewProjection of whichever
// surface the renderer is attached to. There is only ever ONE instance per frame here — the
// water's surface is a single band across the visible rect — but it goes through the instanced
// path anyway so that this file and iridescence.vert are the same shape, and so that the depth
// key participates in the surface's shared ordering scheme like every other primitive.

#version 330 core

// The shared unit quad, 0..1 in both axes.
in vec2 vertexPos;

// Instance attributes. Both are asserted against this file's text by WaterShaderTest: a vertex
// input whose declared type disagrees with the type the renderer BINDS is undefined behaviour
// that raises no GL error and logs nothing — the defect that makes Surface.drawQuad rasterise at
// alpha 0 on macOS (render/Draw.kt).
in vec3 surfacePos;   // (x, y) = the band's CENTRE in world metres, z = the surface's depth key
in vec2 size;         // full width and height of the band, in world metres

out vec2 vPos;        // this fragment's position in WORLD METRES — the whole shader runs off it

uniform mat4 viewProjection;

void main()
{
    // THE ORIGIN IS THE CENTRE, hardcoded, exactly as in iridescence.vert and scene.vert. Every
    // quad in this codebase is authored as a centre and a size; an origin attribute would be a
    // second way to say the same thing.
    vec2 offset = (vertexPos - vec2(0.5)) * size;

    // vPos is in metres and the fragment shader's entire job is expressed in metres — the wave
    // table, the underside fade, the crest width. NOTHING here may learn what a pixel is, with
    // the single exception of the anti-aliasing width in water.frag, which asks the rasteriser
    // via fwidth() precisely so that it does not have to be told.
    vPos = surfacePos.xy + offset;
    gl_Position = viewProjection * vec4(vPos, surfacePos.z, 1.0);
}
