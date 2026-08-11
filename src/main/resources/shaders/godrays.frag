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
// WHY THIS IS ALBEDO ON `main` AND NOT A LIGHT. Not because the composite is additive — an
// earlier version of this header said so and it is FALSE. `GlobalIlluminationSystem` adds a
// `MultiplyEffect("gi_blend_effect", 15, "gi_light_final", minReflectance)` to mainSurface, so
// what lands on screen is `mainSurface.rgb * lightMap.rgb`. (`lighting/global/final.frag`'s
// `base + light` is real but is the light map ASSEMBLING itself: GiFinal binds its `baseTex`
// from the GI local-scene surface, not from mainSurface.) See LightShafts's class doc.
//
// So these bands ARE multiplied by the light map, which is what the alternative — a drawLight
// emitter — was reaching for anyway. What that buys, and it is not nothing: a band is dim where
// the water around it is dim, so it can never be a bright stripe painted over black. What it
// costs is that the depth ramp is now doing work the multiply already does; see
// LightShafts.tailFade for why the ramp is nonetheless kept (it is the guard rail that makes the
// Abyss exactly zero by geometry, which a multiply cannot promise).

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
uniform vec2 wallFade;        // (|x| where the fade begins, |x| where it reaches zero) in metres

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

    // The same treatment for the column's SIDE edges, which are otherwise a hard vertical line
    // down both sides of the frame — the strips simply stop at +-COLUMN_HALF_WIDTH. Measured at
    // 20 m as a 36/255 single-pixel step at exactly world -40.0 m. Mirrors
    // `LightShafts.wallFade`, which is the testable copy; `1 - smoothstep` rather than a
    // reversed smoothstep so the argument order matches the uniform's (start, end) naming.
    float walls = 1.0 - smoothstep(wallFade.x, wallFade.y, abs(vWorld.x));

    fragColor = vec4(tint.rgb, tint.a * band * entry * walls * vRamp);
}
