// The game's own iridescence shader — see iridescence.frag for the optics and
// render/IridescenceRenderer.kt for the batching. NOT a copy of an engine file: this lives
// under /shaders/, deliberately outside /pulseengine/shaders/, so it can never shadow (or be
// shadowed by) anything in pulse-engine-0.13.0.jar. The two files in
// src/main/resources/pulseengine/shaders/renderers/ ARE shadows and are excluded from the
// release jar by build.gradle.kts; this one is ours and must ship.
//
// #version 330 core, not higher: macOS caps OpenGL at 4.1 and has no compute shaders. The
// engine's own lighting/direct/*.frag and error.comp are 430 and simply cannot run here, which
// is why the game uses the global-illumination module rather than the direct one.
//
// Structurally a stripped-down copy of the engine's renderers/texture.vert: one shared unit
// quad plus one instance per object, `viewProjection` taken from whichever surface this
// renderer instance is attached to. That last part is the whole answer to "one shader on two
// surfaces": the world's pearls and the HUD's air bubbles go through the SAME program with
// DIFFERENT matrices, because each of the two IridescenceRenderer instances uploads its own
// surface's camera. Nothing here knows whether a unit is a metre or a pixel, and nothing here
// may learn.

#version 330 core

// Vertex attributes — the shared unit quad, 0..1 in both axes.
in vec2 vertexPos;

// Instance attributes. Kept to five, and every one of them is asserted against this file's
// text by IridescenceShaderTest: a vertex input whose declared type disagrees with the type
// the renderer BINDS is undefined behaviour that raises no GL error and logs nothing — it is
// exactly the defect that makes Surface.drawQuad rasterise at alpha 0 on macOS (render/Draw.kt).
in vec3 surfacePos;   // (x, y) = the object's CENTRE in this surface's own space, z = depth key
in vec2 size;         // full width and height, in this surface's own units
in vec3 filmParams;   // (thickness, amplitude, sheen) — the material, see IridescentMaterial
in vec2 bodyParams;   // (centreOpacity, rimOpacity) — the opaque/translucent split
in uint color;        // packed sRGB RGBA, exactly as SurfaceConfigInternal.setDrawColor writes it

out vec4 vBase;       // the draw colour, converted to LINEAR (the space the surface stores)
out vec2 vUv;         // 0..1 across the quad — the fake normal is built from this
out vec2 vPos;        // this fragment's position in the surface's own space, for the light bearing
out vec3 vFilm;
out vec2 vBody;

uniform mat4 viewProjection;

// Byte-for-byte the engine's own unpackAndConvert (renderers/texture.vert). Copied rather than
// approximated because DiveRenderer.srgbToLinear mirrors this exact transfer function in Kotlin
// to decide which side of the GI reflectance floor a colour lands on, and near-black is where a
// plain pow(c, 2.2) and this curve disagree most.
vec4 unpackAndConvert(uint rgba)
{
    vec4 sRgba = vec4((rgba >> 24u) & 255u, (rgba >> 16u) & 255u, (rgba >> 8u) & 255u, rgba & 255u) / 255.0;
    vec3 lowRange = sRgba.rgb / 12.92;
    vec3 highRange = pow((sRgba.rgb + 0.055) / 1.055, vec3(2.4));
    vec3 linearRgb = mix(highRange, lowRange, lessThanEqual(sRgba.rgb, vec3(0.0031308)));
    return vec4(linearRgb, sRgba.a);
}

void main()
{
    vBase = unpackAndConvert(color);
    vUv   = vertexPos;
    vFilm = filmParams;
    vBody = bodyParams;

    // THE ORIGIN IS THE CENTRE, HARDCODED, and that is deliberate rather than a missing
    // feature. Every object this renderer draws — a pearl, the anglerfish's lure, a ring
    // bubble — is authored as a centre and a size, which is also what scene.vert hardcodes for
    // GI light quads (`(vertexPos - vec2(0.5)) * size`) and what render/Draw.kt's
    // CENTRE_ORIGIN passes for everything else. An origin attribute would be a second way to
    // say the same thing, i.e. a way for the albedo and the light on the same pearl to disagree.
    vec2 offset = (vertexPos - vec2(0.5)) * size;

    vPos = surfacePos.xy + offset;
    gl_Position = viewProjection * vec4(vPos, surfacePos.z, 1.0);
}
