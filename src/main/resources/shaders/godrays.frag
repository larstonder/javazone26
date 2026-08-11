// The god rays: a stack of four sines across the water, animated, fading with depth.
//
// The owner asked for exactly this — "a more simple shader that animates opacity of the god rays
// based on stacked sinus waves" — after two richer shapes were rejected (five parallel GI light
// quads, then a seven-ray fan from a sun apex). render/LightShafts.kt has the history and owns
// every number below; this file owns none of them. That split is deliberate: the periods, the
// amplitudes and the drift are asserted at build time in Kotlin, and GLSL cannot be unit-tested.
//
// #version 330 core, not higher: macOS caps OpenGL at 4.1.
//
// WHY THIS IS ALBEDO ON `main` AND NOT A LIGHT. GI's composite is ADDITIVE —
// `pulseengine/shaders/lighting/global/final.frag:57` is `fragColor = vec4(base + light, 1.0)`,
// with `base` bound to mainSurface's own texture by GiFinal — so albedo drawn here is NOT
// multiplied toward nothing in the deep, which is what CLAUDE.md's "GI multiplies mainSurface"
// claim would predict and what made the previous two passes use drawLight. See LightShafts's
// class doc for the full correction and for what is given up (these no longer interact with the
// light map at all: no occlusion by rock, no lighting of a pearl that passes through).

#version 330 core

in vec2 vWorld;    // world metres: x, and depth (world y IS depth and runs down)
in float vRamp;    // the depth ramp, sampled in Kotlin at the strip's edges and interpolated

out vec4 fragColor;

// One component per band. Four is what LightShafts authors; see its class doc for why the
// PERIODS are pairwise incommensurate (that, and nothing else, is what makes the bands irregular)
// and why the amplitudes sum to exactly 1 (so `sum` lives in [-1, 1] and bandEdges below is a
// fraction of the largest crest the stack can make).
uniform vec4 bandFrequency;   // radians per metre OF THE SURFACE PLANE
uniform vec4 bandAmplitude;
uniform vec4 bandPhase;       // base phase + drift * the animation clock, advanced on the CPU

uniform vec2 apex;            // (x, height above the surface) — the convergence
uniform vec2 bandEdges;       // (threshold, peak) of the band mask's smoothstep
uniform float surfaceFade;    // metres over which the bands come up to full below the waterline

// The band colour: LINEAR rgb, with alpha the peak opacity. A uniform and NOT the surface's
// packed draw colour — godrays.vert says why, and it is a leak that shipped a wrong frame once.
uniform vec4 tint;

void main()
{
    // THE CONVERGENCE, IN ONE LINE. The stack is evaluated not at this fragment's x but at where
    // the ray from the apex through it crosses the SURFACE plane. Two fragments at the same depth
    // that are d apart therefore span d * H / (H + depth) of the pattern, so the deeper they are
    // the further apart the bands they fall in: the bands splay downward and converge upward,
    // toward a point they never reach because it is 220 m into the sky.
    float u = (vWorld.x - apex.x) * apex.y / (apex.y + vWorld.y);

    vec4 waves = bandAmplitude * sin(bandFrequency * u + bandPhase);
    float sum = waves.x + waves.y + waves.z + waves.w;

    // The threshold is what puts DARK WATER BETWEEN the bands rather than leaving a ripple: only
    // the crests above it are drawn, and their edges fade into each other because the mask is a
    // smoothstep and neighbouring crests overlap before either reaches the threshold.
    float band = smoothstep(bandEdges.x, bandEdges.y, sum);

    // No hard bright cap on the waterline — a band that started at full strength exactly at the
    // surface reads as a spotlight aimed down rather than as light entering the water.
    float entry = smoothstep(0.0, surfaceFade, vWorld.y);

    fragColor = vec4(tint.rgb, tint.a * band * entry * vRamp);
}
