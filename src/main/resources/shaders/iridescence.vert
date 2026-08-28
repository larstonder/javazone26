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

// Instance attributes. Every one of them is asserted against this file's text by
// IridescenceShaderTest: a vertex input whose declared type disagrees with the type the renderer
// BINDS is undefined behaviour that raises no GL error and logs nothing — it is exactly the
// defect that makes Surface.drawQuad rasterise at alpha 0 on macOS (render/Draw.kt).
in vec3 surfacePos;   // (x, y) = the object's CENTRE in this surface's own space, z = depth key
in vec2 size;         // full width and height, in this surface's own units
in vec3 filmParams;   // (thickness, amplitude, sheen) — the material, see IridescentMaterial
in vec2 bodyParams;   // (centreOpacity, rimOpacity) — the opaque/translucent split
in uint color;        // packed sRGB RGBA, exactly as SurfaceConfigInternal.setDrawColor writes it

// The optional normal-map sheet: where its frame sits in the texture bank, and which slice.
// Read STRAIGHT OFF the frame Texture by IridescenceRenderer.draw — SpriteSheet.onUploaded has
// already folded both the atlas-page offset and the per-cell offset into these four floats, so
// recomputing a cell rect here would apply it twice. See that file's class doc.
in vec2 uvMin;
in vec2 uvMax;
in uint texHandle;    // GL_UNSIGNED_INT, so the packed bits survive glVertexAttribIPointer intact

out vec4 vBase;       // the draw colour, converted to LINEAR (the space the surface stores)
out vec2 vUv;         // 0..1 across the quad — the fake normal is built from this
out vec2 vPos;        // this fragment's position in the surface's own space, for the light bearing
out vec3 vFilm;
out vec2 vBody;

// The texture-bank address, split exactly as the engine's texture.vert splits it.
out vec2 vTexStart;
out vec2 vTexSize;
out float vTexIndex;          // the array LAYER, or 65534 (= TextureHandle.NONE) for "no texture"
flat out uint vSamplerIndex;  // FLAT: an integer index has no meaningful interpolation, and
                              // interpolating one is a compile error in GLSL 330 anyway

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

// Byte-for-byte the engine's own two helpers (renderers/texture.vert). Copied rather than
// rewritten because they are the decode half of a packing that lives in Kotlin bytecode:
// TextureHandle.create-FfHxNlQ(a, b) is `(a << 16) | b`, getSamplerIndex-impl is `ishr 16;
// iand 65535` and getTextureIndex-impl is `iand 65535`. High 16 bits pick WHICH bound
// sampler2DArray, low 16 bits pick the LAYER inside it.
uint getSamplerIndex(uint textureHandle)
{
    return (textureHandle >> uint(16)) & ((uint(1) << uint(16)) - uint(1));
}

float getTexIndex(uint textureHandle)
{
    return float(textureHandle & ((uint(1) << uint(16)) - uint(1)));
}

void main()
{
    vBase = unpackAndConvert(color);
    vUv   = vertexPos;
    vFilm = filmParams;
    vBody = bodyParams;

    // texStart/texSize rather than min/max, because that is the form the fragment shader wants
    // and the form the engine's own texture.frag and normal_map.frag both consume.
    vTexStart     = uvMin;
    vTexSize      = uvMax - uvMin;
    vSamplerIndex = getSamplerIndex(texHandle);
    vTexIndex     = getTexIndex(texHandle);

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
