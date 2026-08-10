// ONE IRIDESCENT SURFACE, TWO MATERIALS: the opaque pearl and the translucent air bubble.
// See render/IridescentMaterial.kt for the two parameter sets and render/IridescenceRenderer.kt
// for how the same program ends up on two surfaces in two different coordinate spaces.
//
// #version 330 core — macOS caps OpenGL at 4.1. See iridescence.vert's header.
//
// ============================================================================================
// WHAT DRIVES THE HUE SHIFT, AND WHY IT IS NOT THE VIEW ANGLE
// ============================================================================================
//
// Real nacre and real soap film are thin-film interference: two reflections, one off the front
// of a thin layer and one off the back, arriving out of phase by an optical path difference
// (OPD) that depends on how obliquely you are looking through the film. Tilt the shell, the
// path lengthens, the wavelength that cancels changes, the colour walks around the spectrum.
//
// THERE IS NO VIEW ANGLE IN THIS GAME. The camera is orthographic and every sprite is a flat
// quad facing it square-on, so the view vector is the constant (0, 0, 1) for every fragment of
// every object, always. A thin-film shader ported from a 3D renderer computes `dot(N, V)`,
// gets the same number everywhere, and produces a flat tint — an expensive way to draw an
// amber square. The variation has to come from somewhere else or it does not exist.
//
// SO THE LIGHT VECTOR TAKES THE VIEW VECTOR'S PLACE, and the light in question is the diver's
// torch. The OPD is evaluated against the half-vector of (L, V) — the standard microfacet
// stand-in for "the direction the film is being probed from" — so the physics is kept intact
// and only the term that has no variation is swapped for one that has plenty:
//
//   * ACROSS each object, because N is a faked hemisphere normal (below), so the interference
//     bands sweep across the curve instead of flooding it. This is what makes a pearl read as
//     a small round SOLID rather than as a tinted dot.
//   * OVER TIME, because L is the bearing to the player's torch. A pearl changes colour as the
//     beam passes over it; the air ring's shimmer rotates as the diver turns.
//
// THE ALTERNATIVES WERE CONSIDERED AND ARE WEAKER, briefly, so nobody re-litigates this:
//   depth   — already the zone bands' job, and it would make every pearl at a given depth
//             identical, which is the opposite of what nacre does.
//   a clock — decorative. Every object in the frame shimmers in lockstep for reasons the
//             player cannot influence, and it makes two captures of the same build differ.
//   GI's own light direction — not readable from a forward pass; the light map is computed
//             from this surface, one full pipeline stage later.
// The torch bearing is the only candidate that ties the effect to the game's own mechanic:
// the thing that makes a pearl light up is the thing the player is aiming.
//
// It is also deterministic and stateless — one vec2 uniform per surface per frame, no clock, no
// per-object memory — so a pinned capture of the same build reproduces exactly, which is what
// makes the light-budget measurement against a control pair meaningful at all.

#version 330 core

in vec4 vBase;
in vec2 vUv;
in vec2 vPos;
in vec3 vFilm;
in vec2 vBody;

out vec4 fragColor;

/** The torch, in THIS surface's own space. World metres on `main`, screen pixels on `hud`. */
uniform vec2 lightPos;

/**
 * The camera's view direction. Constant for every fragment in this game — that is the whole
 * problem this shader is written around, so it is stated as a constant rather than hidden in
 * an unused uniform that would imply it might one day vary.
 */
const vec3 VIEW_DIR = vec3(0.0, 0.0, 1.0);

/**
 * How far "out of the screen" the torch is taken to sit, in units of its own in-plane
 * direction. Without it L is purely in-plane, `dot(N, L)` is zero all along the object's
 * centre line, and the interference collapses into one hard seam through the middle of every
 * pearl. It is applied AFTER the in-plane direction is normalised, so it is a pure ratio and
 * carries no length — which is what lets the same number serve a surface measured in metres
 * and a surface measured in pixels.
 */
const float LIGHT_ELEVATION = 0.55;

/**
 * Relative wavelengths of the three primaries, normalised to green (~650 / 550 / 450 nm).
 *
 * Interference intensity at wavelength L is cos^2(2*pi*OPD/L), so dividing the phase by these
 * three numbers puts the channels out of step with each other at the rate a real film does.
 * That ratio IS the effect: a single scalar band driving a hand-picked three-colour gradient
 * produces evenly spaced stripes, whereas out-of-phase channels produce the uneven
 * green-magenta-gold-cyan walk that reads as nacre.
 */
const vec3 WAVELENGTHS = vec3(1.18, 1.0, 0.82);

const float TAU = 6.28318530718;

/**
 * Rec. 709 luma weights. The interference LAYER is scaled by the body's luma rather than tinted
 * by the body's colour — see the composition below for why that is the difference between a
 * pearl and an amber dot.
 */
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

/**
 * How hard to fade the bands where they get finer than a pixel. `fwidth(opd)` is how much the
 * optical path difference changes between neighbouring fragments; once that approaches a whole
 * cycle the cos() is being point-sampled far below Nyquist and turns into coloured static —
 * which is exactly what the first capture of the air ring showed along its upper rim, where the
 * half-vector grazes and the path length diverges. Fading toward the film's own mean (0.5)
 * there is the standard analytic-antialiasing remedy: the object keeps its shape and its
 * brightness and simply stops claiming a colour it cannot resolve.
 */
const float BAND_AA = 2.2;

/** Softens the silhouette over this fraction of the radius. Antialiasing, nothing more. */
const float EDGE_SOFTNESS = 0.06;

/**
 * Floor on cos(theta) in the OPD. As the half-vector approaches the horizon the path length
 * diverges and the bands become finer than a pixel, which aliases into noise. Clamping the
 * grazing case is the standard thin-film mitigation and costs a little band compression right
 * at the silhouette, where the alpha ramp is already fading the fragment out.
 */
const float MIN_COS_THETA = 0.12;

void main()
{
    // ---- The faked normal --------------------------------------------------------------
    // A unit hemisphere over the quad: dead-on at the centre, grazing at the rim. This is the
    // substitute for the geometry a 2D sprite does not have, and it is what gives the
    // interference something to vary ACROSS. It also gives the object its round silhouette —
    // a square iridescent pearl reads as a rendering fault, and the light these same pearls
    // EMIT is already a disc (render/LightEmitter.kt).
    vec2 p = vUv * 2.0 - 1.0;
    float r = length(p);

    float edge = 1.0 - smoothstep(1.0 - EDGE_SOFTNESS, 1.0, r);
    if (edge <= 0.0)
        discard; // the quad's corners: no colour and, importantly, no depth write

    float z = sqrt(max(1.0 - r * r, 0.0));
    vec3 n = vec3(p, z);

    // ---- The light bearing ---------------------------------------------------------------
    // Normalised IN-PLANE first, then elevated, so nothing below depends on the distance to
    // the torch or on the units it is measured in. A pearl 40 m away and a bubble 300 px away
    // are treated identically, which is what makes one shader serve both surfaces.
    vec2 toLight = lightPos - vPos;
    float dist = length(toLight);
    vec2 dir = dist > 1e-6 ? toLight / dist : vec2(0.0, -1.0);
    vec3 l = normalize(vec3(dir, LIGHT_ELEVATION));

    // ---- Thin-film interference ----------------------------------------------------------
    vec3 h = normalize(l + VIEW_DIR);
    float cosTheta = max(dot(n, h), MIN_COS_THETA);
    float opd = vFilm.x / cosTheta;
    vec3 film = 0.5 + 0.5 * cos(TAU * opd / WAVELENGTHS);

    // Fade to the film's own mean wherever a cycle no longer spans a pixel. See BAND_AA.
    film = mix(vec3(0.5), film, 1.0 / (1.0 + BAND_AA * fwidth(opd)));

    // ---- Composition: a SUBSTRATE with a FILM ON IT, not a tinted substrate ----------------
    //
    // THE FIRST VERSION MULTIPLIED THE DRAW COLOUR BY THE FILM AND IT DID NOT WORK. That form
    // has an attractive property — `film` averages 0.5, so the multiplier averages exactly 1
    // and the object's mean albedo is untouched — and one fatal one: a channel can only vary in
    // proportion to what the base colour already had in it. A pearl's amber (1, 0.78, 0.35) is
    // (1.0, 0.565, 0.101) in linear, so its blue could swing by at most +-0.05 and the whole
    // interference collapsed into "warmer amber / cooler amber". Captured at 100 m it was
    // indistinguishable from the flat square it replaced. The bubble, whose base is a near-white
    // blue, looked superb in the same frame — which is the tell: the shader was fine, the
    // composition was throwing the colour away.
    //
    // So the film is a LAYER, which is what it physically is: light reflected off a thin
    // coating sitting on top of a diffuse body. The body keeps its own hue at reduced weight,
    // and the layer contributes a spectrum of its own, scaled by the body's LUMA so a dark
    // object gets a dim film and a bright one a bright film. Every channel can now reach the
    // full swing regardless of the base colour, which is why an amber pearl can be nacreous at
    // all.
    //
    // The mean is no longer preserved — the layer adds `amplitude * luma` on average, spread
    // evenly across the channels, so an amber pearl comes out paler and slightly brighter in
    // blue. That is a real change to the world surface's albedo and therefore to what the GI
    // multiply has to work with; it is measured against a same-build control pair rather than
    // asserted, and the pearls' EMITTERS (DiveLighting.drawPearlLights) are untouched, so the
    // light budget itself is unchanged.
    //
    // What IS still exact is the darkest value this shader can produce: with `film` at 0 and
    // the sheen unlit, it is `base * (1 - amplitude)`. That is the number IridescentMaterialTest
    // checks against DiveRenderer.GI_REFLECTANCE_FLOOR — any albedo shorter than that in linear
    // space is discarded by the GI blend and replaced with flat grey, which on a banded surface
    // would be grey blotches rather than dark bands.
    float amplitude = vFilm.y;

    // The sheen is not a separate highlight, it is simply MORE FILM on the side facing the
    // torch — as on a real shell, where the bright side is where the colour walk is strongest.
    // Kept modest: the world surface runs an ACES tone mapper and a THRESHOLDED bloom
    // (threshold 1.4, DiveLighting.setup), neither of which is linear in radiance near a light.
    float lambert = max(dot(n, l), 0.0);
    float coverage = amplitude + vFilm.z * lambert * lambert * lambert;

    float luma = dot(vBase.rgb, LUMA);
    vec3 rgb = vBase.rgb * (1.0 - amplitude) + film * (2.0 * luma * coverage);

    // ---- The opaque / translucent split ----------------------------------------------------
    // The ONE thing that differs materially between a pearl and a bubble, and it is two numbers
    // rather than a branch: how much of the fragment survives at the centre of the sprite and
    // how much at its silhouette. A pearl passes (1, 1) and is simply opaque. A bubble passes a
    // low centre and a full rim, which is what a soap film actually does — you see through the
    // middle of it and along the edge you are looking through much more film.
    //
    // The ring is the game's ONLY air warning (render/Hud.kt), so this deliberately keeps a
    // solid translucent BODY rather than hollowing the bubble out into an outline.
    float rimness = 1.0 - z;
    float alpha = vBase.a * mix(vBody.x, vBody.y, rimness) * edge;

    fragColor = vec4(rgb, alpha);
}
