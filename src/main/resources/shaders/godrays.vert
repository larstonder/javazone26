// The game's god rays — see godrays.frag for the band stack and render/ShaftRenderer.kt for the
// batching. NOT a copy of an engine file: this lives under /shaders/, deliberately outside
// /pulseengine/shaders/, so it can never shadow (or be shadowed by) anything in
// pulse-engine-0.13.0.jar, and so build.gradle.kts's devOnlyShaderOverrides exclusion — which
// drops our two DELIBERATE shadows from the release jar — cannot catch it. It must ship.
//
// #version 330 core, not higher: macOS caps OpenGL at 4.1.
//
// Structurally the same stripped-down texture.vert that shaders/iridescence.vert is, and it
// takes `viewProjection` from whichever surface its renderer is attached to for the same reason.
// This one is only ever attached to `main`, so its units are always world METRES, and the
// fragment shader depends on that: the band periods are metres.

#version 330 core

// The shared unit quad, 0..1 in both axes.
in vec2 vertexPos;

// Per-instance: one horizontal STRIP of the overlay. See LightShafts.STRIP_COUNT for why the
// overlay is submitted as strips rather than as one quad — the short version is that the depth
// ramp is a DepthBlend smoothstep chain that exists only in Kotlin and must not be reimplemented
// here, so it is sampled at each strip's edges and interpolated across it by `vRamp` below.
in vec3 stripPos;     // (x, depth) = the strip's CENTRE in world metres, z = the depth key
in vec2 size;         // full width and height, in metres
in vec2 ramp;         // the depth ramp at the strip's (top, bottom) edge

out vec2 vWorld;      // this fragment's world position in metres — x, and depth
out float vRamp;      // the depth ramp here

uniform mat4 viewProjection;

// NO PACKED `in uint color` HERE, unlike shaders/iridescence.vert, and that is a fix rather than
// a simplification. The packed colour comes from `SurfaceConfigInternal.currentDrawColor`, which
// is SHARED STATE on the surface: a renderer that sets it has changed it for whatever draws next.
// These strips are the last thing drawn to `main` in a frame, so the colour they set was still
// there when DiveRenderer drew the NEXT frame's world — and with the shafts at 0.7 alpha, every
// world draw that does not set its own colour first was silently drawn at 70% opacity. It was
// caught by a control build with the opacity at 0, which left the draw colour fully transparent
// and made two thirds of the pearls disappear. See ShaftRenderer's `tint` uniform.

void main()
{
    // vertexPos.y runs 0 at the strip's TOP edge to 1 at its bottom, which is the same direction
    // world y runs (world y IS depth and runs DOWN — see CLAUDE.md's coordinate table). So
    // ramp.x is the shallow end and ramp.y the deep one, and no flip belongs here.
    vRamp = mix(ramp.x, ramp.y, vertexPos.y);

    // THE ORIGIN IS THE CENTRE, hardcoded, as in shaders/iridescence.vert, scene.vert and
    // render/Draw.kt's CENTRE_ORIGIN. One convention for every quad in this game.
    vec2 offset = (vertexPos - vec2(0.5)) * size;

    vWorld = stripPos.xy + offset;
    gl_Position = viewProjection * vec4(vWorld, stripPos.z, 1.0);
}
