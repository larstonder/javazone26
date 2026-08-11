// THE SURFACE OF THE SEA, SEEN FROM UNDER IT — and the boundary between the sky and the water,
// which this shader's ALPHA is.
//
// #version 330 core — macOS caps OpenGL at 4.1. See water.vert's header.
//
// =============================================================================================
// WHY THE WATERLINE IS AN ALPHA RAMP AND NOT TWO EDGES THAT MEET
// =============================================================================================
//
// The sky (render/Sky.kt) is drawn on its own surface BEHIND the world, and it is drawn straight
// past the waterline, down to WaterSurface.QUAD_BOTTOM_DEPTH. This shader is drawn ON the world
// surface, over the top of it, across exactly the same band. Above the wave it discards, so the
// sky shows; below the wave it is opaque water; across the wave it ramps.
//
// So there is ONE edge in the whole arrangement and it is this one. Nothing has to agree with
// anything: the sky is simply what is behind the ramp, at whatever aspect ratio, whatever
// framebuffer, whatever camera depth. The alternative — sky stops here, water starts there — is
// two edges in two files in two coordinate spaces that have to land on the same sub-pixel every
// frame, which is the shape of every drift bug in this project's history.
//
// It also means the ramp is anti-aliased for free and correctly: it blends against the actual
// sky pixel behind it, not against a guess at what the sky would have been.
//
// =============================================================================================
// WHAT IS LIT AND WHAT IS NOT
// =============================================================================================
//
// This shader is on `main`, so everything it draws IS multiplied by GI's light map. That is
// correct and deliberate: the water is part of the lit world, the god rays come through this
// surface from above, and a sea that ignored the light map would sit in front of the scene
// rather than in it. The SKY is the thing that must escape the multiply, and it does, by being
// on another surface entirely.
//
// The one thing that follows from it: every colour this shader can produce must clear
// DiveRenderer.GI_REFLECTANCE_FLOOR, or texture_multiply_blend.frag replaces it with flat
// vec3(0.02) grey. That is free here rather than a constraint — the two water colours are
// sampled from DiveRenderer's own zone curve, which already clears the floor by construction,
// and everything else this shader does is ADDITIVE. There is no path through it that can make a
// fragment darker than the water it started from.
//
// =============================================================================================
// THE MENISCUS DOES THE WORK, NOT THE WAVE
// =============================================================================================
//
// The owner's reference is the half-submerged shot from Ori and the Blind Forest, and the useful
// thing about it is what it does NOT have: no large waves, no displaced mesh, no separately drawn
// underwater scenery. The surface there is a nearly flat line, and what makes it read as water is
// (a) a bright meniscus band sitting on that line and (b) the tint below it. The geometry is
// almost incidental. WaterSurface's table was retuned down to a 0.31 m ripple on the strength of
// that, and the budget went here.
//
// So: two exponentials in metres below the local surface height, no texture and no noise.
//
//   * a TIGHT one, a couple of tens of centimetres — the meniscus. It runs the WHOLE length of
//     the line at `sunStrength.x`, with `sunStrength.y` added only where a facet is tilted toward
//     the sun. The first version had no `sunStrength.x` at all and put everything behind the
//     slope test, which left most of the waterline a hard dark cut with occasional bright patches.
//   * a BROAD one, a few metres — the light that got through and is still in the water. NOT
//     modulated by slope: light that has refracted and scattered through several metres has
//     forgotten which facet it came through.
//
// The slope is the analytic derivative of the same wave table, so it costs one cosine per
// component whose argument the sine already needed.
//
// THE BROAD TERM IS NOT A DEPTH FOG AND MUST NOT BECOME ONE. DiveRenderer's zone bands already
// carry this game's depth gradient, continuously, through DepthBlend — that is what the
// reference's cyan tint corresponds to. This is a LOCAL term confined to the metres under the
// boundary and forced to exactly zero where the bands take over (`fade`, below), precisely so
// there are not two depth curves in this codebase to keep in agreement.
//
// WHAT IS DELIBERATELY MISSING: the reference also displaces everything just under the waterline
// horizontally, which is the refraction cue. That cannot be done from a BatchRenderer — it draws
// into the world surface and cannot read it, and the water it draws is horizontally uniform so
// displacing that would displace nothing. It needs a PostProcessingEffect sampling `main` after
// the GI multiply, which is also where caustics belong. See WaterSurface's note for what such a
// pass can reuse from here whole.

#version 330 core

in vec2 vPos;         // world metres — x along the column, y IS depth, positive downward

out vec4 fragColor;

/** How many sinusoids are summed. Must equal WaterSurface.components.size. */
const int WAVE_COUNT = 3;

/**
 * The wave table, one vec4 per component: (amplitude m, waveNumber rad/m, angularSpeed rad/s,
 * phase rad). Uploaded verbatim from `WaterSurface.components` — there is no second copy of these
 * numbers anywhere, and in particular none in this file.
 */
uniform vec4 wave[WAVE_COUNT];

/** The wave's phase in seconds. See WaterSurface's clock note for why this exists and is pinnable. */
uniform float time;

/** Tuning.SURFACE_DEPTH — the depth the sea would sit at with no waves on it. */
uniform float surfaceDepth;

/** WaterSurface.QUAD_BOTTOM_DEPTH — where this quad hands off to DiveRenderer's zone bands. */
uniform float bottomDepth;

/**
 * The water's own colour at `surfaceDepth` and at `bottomDepth`, IN LINEAR SPACE, sampled from
 * DiveRenderer's zone curve on the CPU.
 *
 * Two uniforms rather than a curve in the shader for the reason the wave table is one uniform
 * rather than three constants: DiveRenderer.zoneBlueAt already carries the reflectance floor and
 * the whole depth-blend easing, and a second implementation of it here could disagree with the
 * band it is supposed to hand off to. `waterFar` is sampled at exactly `bottomDepth`, so at the
 * bottom edge of this quad the shader's output IS the first zone band's colour and the join
 * cannot step.
 */
uniform vec3 waterNear;
uniform vec3 waterFar;

/** The low sun's colour, linear. */
uniform vec3 sunColor;

/**
 * (meniscus everywhere, extra meniscus where a facet faces the sun, underside haze).
 *
 * THE FIRST OF THE THREE IS THE ONE THAT MATTERS, and it was not there in the first version. That
 * version put the whole surface term behind the slope test, so the bright edge appeared only on
 * the facets tilted at the sun and the rest of the waterline was a hard dark cut. The owner's
 * reference (Ori and the Blind Forest, half-submerged) shows the opposite emphasis: a CONTINUOUS
 * bright meniscus running the whole length of the line, with the sun's variation as a modulation
 * ON it rather than as its cause. That band, not the geometry, is what says "surface".
 */
uniform vec3 sunStrength;

/** Metres: the tight meniscus exponential's falloff, then the broad underside one's. */
uniform vec2 falloffMetres;

/**
 * Which way the sun is, as a unit x. The surface's slope is dotted with it, so +1 lights the
 * faces tilted one way and -1 the other. A scalar rather than a vec2 because the sun is on the
 * horizon: there is no vertical component to a bearing that lies in the plane of the water.
 */
uniform float sunBearing;

void main()
{
    // ---- The surface's height and slope at this x, from the one wave table -------------------
    //
    // Depth grows DOWNWARD, so a crest is a SMALLER depth and the sum is subtracted.
    float height = surfaceDepth;
    float slope = 0.0;
    for (int i = 0; i < WAVE_COUNT; i++)
    {
        float arg = wave[i].y * vPos.x + wave[i].z * time + wave[i].w;
        height -= wave[i].x * sin(arg);
        // d(height)/dx, analytic. The same argument, so the compiler shares it.
        slope -= wave[i].x * wave[i].y * cos(arg);
    }

    // How far under the surface this fragment is. Negative above it.
    float below = vPos.y - height;

    // ---- The one edge ------------------------------------------------------------------------
    //
    // fwidth() is how this stays resolution-independent without knowing the resolution: it is
    // the rasteriser's own estimate of how much vPos.y changes across one pixel, in METRES, so
    // the ramp is one pixel wide on a 900-row dev window and one pixel wide on a 4K booth panel
    // and this file never learns which it is on. Floored at a tiny epsilon because a degenerate
    // or off-screen derivative would otherwise make the edge infinitely sharp or NaN.
    float aa = max(fwidth(vPos.y), 1e-5);
    float coverage = smoothstep(-aa, aa, below);
    if (coverage <= 0.0)
        discard;   // above the wave: leave `main` transparent so the sky behind shows through

    // ---- The water body ----------------------------------------------------------------------
    //
    // Linear in depth between the two sampled zone-curve colours. Over the ~9 m this quad spans,
    // DiveRenderer's own curve is close enough to linear that the difference is under a quantised
    // step; what matters is that the value AT bottomDepth is exact, which it is by construction.
    float t = clamp((vPos.y - surfaceDepth) / max(bottomDepth - surfaceDepth, 1e-5), 0.0, 1.0);
    vec3 rgb = mix(waterNear, waterFar, t);

    // ---- The sun on the surface, and under it ------------------------------------------------
    float under = max(below, 0.0);

    // The meniscus: a bright band hugging the underside of the boundary, everywhere along it.
    float meniscus = exp(-under / falloffMetres.x);

    // ...and the sun's variation ON that band. A face tilted toward the sun catches more of it.
    // Squared rather than clamped-linear so the lit facets are genuinely picked out instead of the
    // whole line being lifted uniformly, which is what a linear term does.
    float facing = clamp(slope * sunBearing, 0.0, 1.0);

    // The light that got through, which has forgotten the facet it came through.
    float glow = exp(-under / falloffMetres.y);

    // BOTH SUN TERMS ARE FORCED TO EXACTLY ZERO AT THE BOTTOM OF THE QUAD, and that is what makes
    // the hand-off to DiveRenderer's zone bands seamless rather than merely close. An exponential
    // is never zero: at `bottomDepth` the underside glow is still e^-3, i.e. 5% of its peak, and
    // 5% of a term this size measured as a 2.5/255 step across the whole frame at the hand-off
    // depth — a hard horizontal line at a fixed WORLD depth, which is exactly the artefact
    // DiveRenderer.GI_REFLECTANCE_FLOOR's note describes and exactly as easy to mistake for a
    // lighting bug. `1 - t` is zero there by construction, so the shader's output at the bottom
    // edge is the mixed water colour and nothing else — and `waterFar` is sampled at that same
    // depth, so it IS the first band's colour.
    float fade = 1.0 - t;

    rgb += sunColor * fade * (
        meniscus * (sunStrength.x + sunStrength.y * facing * facing) +
        glow * sunStrength.z
    );

    // Alpha is coverage and nothing else: this quad is either water or it is not, and the only
    // place it is partly water is the pixel the boundary runs through. In particular the
    // underside glow does NOT fade the water out — the water below the surface is opaque water,
    // and the sky must not be visible through it.
    fragColor = vec4(rgb, coverage);
}
