// ONE IRIDESCENT SURFACE, SEVERAL MATERIALS: the opaque pearl, the translucent air bubble and the
// oxygen vent's animated blob. See render/IridescentMaterial.kt for the parameter sets and
// render/IridescenceRenderer.kt for how the same program ends up on two surfaces in two different
// coordinate spaces.
//
// TWO WAYS TO GET A SHAPE, and exactly one place where they differ. A pearl and a bubble are
// round, so their normal and their silhouette are derived analytically from the quad's UV. A vent
// is a 96-frame blob with no closed form, so it brings a baked normal-map SPRITE SHEET and takes
// both from that: RGB decoded as `rgb*2-1` for the normal, ALPHA for the coverage. One texture
// does the whole job because the analytic hemisphere's `z` IS a normal map's blue channel — see
// the block in main(). Everything after that block reads `n` and `edge` and nothing else, so the
// optics below are one implementation and not two.
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
in vec2 vTexStart;
in vec2 vTexSize;
in float vTexIndex;
flat in uint vSamplerIndex;

out vec4 fragColor;

/** The torch, in THIS surface's own space. World metres on `main`, screen pixels on `hud`. */
uniform vec2 lightPos;

/**
 * The whole texture bank, bound in one go by IridescenceRenderer.onRenderBatch. Sixteen is the
 * engine's own cap (TextureBank.MAX_TEXTURE_SLOTS = 16, from the ConstantValue attribute) and is
 * the size both renderers/texture.frag and lighting/normal_map.frag declare.
 */
uniform sampler2DArray textureArrays[16];

/**
 * The sentinel a handle carries when there is no texture: TextureHandle.NONE is create(0, 65534),
 * i.e. layer 65534, which is verbatim the `#define NO_TEXTURE 65534` the engine's texture.frag
 * branches on. A null `normalTex` therefore takes the same path, through the same number, as an
 * engine draw with no texture.
 */
#define NO_TEXTURE 65534.0

// Dynamic indexing of a sampler array needs GLSL 400; this file is 330 core because macOS caps
// OpenGL at 4.1 (see the header). So the index has to be a compile-time constant in every branch,
// which is why the engine ships this switch and why it is copied here VERBATIM rather than
// paraphrased — the two files must sample identically or a vent and the diver beside it would
// disagree about mip selection.
vec4 sampleTextureArrayGrad(int index, vec3 texCoords, vec2 ddx, vec2 ddy)
{
    switch (index)
    {
        case 0:  return textureGrad(textureArrays[0],  texCoords, ddx, ddy);
        case 1:  return textureGrad(textureArrays[1],  texCoords, ddx, ddy);
        case 2:  return textureGrad(textureArrays[2],  texCoords, ddx, ddy);
        case 3:  return textureGrad(textureArrays[3],  texCoords, ddx, ddy);
        case 4:  return textureGrad(textureArrays[4],  texCoords, ddx, ddy);
        case 5:  return textureGrad(textureArrays[5],  texCoords, ddx, ddy);
        case 6:  return textureGrad(textureArrays[6],  texCoords, ddx, ddy);
        case 7:  return textureGrad(textureArrays[7],  texCoords, ddx, ddy);
        case 8:  return textureGrad(textureArrays[8],  texCoords, ddx, ddy);
        case 9:  return textureGrad(textureArrays[9],  texCoords, ddx, ddy);
        case 10: return textureGrad(textureArrays[10], texCoords, ddx, ddy);
        case 11: return textureGrad(textureArrays[11], texCoords, ddx, ddy);
        case 12: return textureGrad(textureArrays[12], texCoords, ddx, ddy);
        case 13: return textureGrad(textureArrays[13], texCoords, ddx, ddy);
        case 14: return textureGrad(textureArrays[14], texCoords, ddx, ddy);
        case 15: return textureGrad(textureArrays[15], texCoords, ddx, ddy);
        default: return vec4(0.0);
    }
}

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

/**
 * Softens the silhouette over this fraction of the radius. Antialiasing, nothing more.
 *
 * ANALYTIC PATH ONLY. A sprite sheet's alpha is already antialiased by the bake, so the textured
 * path uses it as it is — running a second ramp over an already-ramped value is what would make
 * the vent's edge harder than the pearls' rather than matching it.
 */
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
    // ---- The normal and the silhouette -----------------------------------------------------
    //
    // TWO SOURCES, THREE OUTPUTS. Everything below this block reads exactly three things — `n`,
    // the surface normal, `z`, its out-of-screen component, and `edge`, this fragment's coverage —
    // and it does not care which source produced them. That is the whole shape of this change: a
    // normal-mapped vent and an analytic pearl differ HERE and nowhere else, so the interference,
    // the sheen, the band antialiasing and the opaque/translucent split are one implementation
    // serving both.
    //
    // ONE TEXTURE COVERS ALL THREE, and that is not a coincidence. A tangent-space normal map
    // stores the out-of-screen component in BLUE, which is precisely the `z` the analytic path
    // computes; and the coverage the analytic path gets from a smoothstep over the radius is what
    // an antialiased sprite already stores in ALPHA. So `rimness = 1 - z` below is the same
    // quantity in both paths and needs no branch of its own, and there is no second silhouette
    // texture and no second sampler.
    //
    // ---- Analytic: the pearls and the HUD air-ring bubbles ---------------------------------
    // A unit hemisphere over the quad: dead-on at the centre, grazing at the rim. This is the
    // substitute for the geometry a 2D sprite does not have, and it is what gives the
    // interference something to vary ACROSS. It also gives the object its round silhouette —
    // a square iridescent pearl reads as a rendering fault, and the light these same pearls
    // EMIT is already a disc (render/LightEmitter.kt).
    //
    // COMPUTED UNCONDITIONALLY AND THEN OVERRIDDEN, rather than sitting in an `else`. Three
    // reasons, in order of weight:
    //  - It keeps `float z = sqrt(...)` and `vec3 n = vec3(p, z);` as top-level statements in
    //    their original form. `PearlNormalMapTest` parses those two lines OUT OF THIS FILE to
    //    check that the pearl's LIT shape (render/PearlNormalMap.kt, a generated texture on
    //    gi_normal_map) is still the same hemisphere as its DRAWN shape. Two copies of a
    //    hemisphere is the price of a BatchRenderer drawing to one surface; that test is what
    //    stops the copies drifting, and burying these lines in a nested scope would have made it
    //    fail — correctly, since it can no longer tell you which of the two is right.
    //  - The analytic path is what `normalTex = null` means, and null is the default.
    //  - It costs a length, a smoothstep and a sqrt on textured fragments. Against a texture
    //    fetch, nothing.
    vec2 p = vUv * 2.0 - 1.0;
    float r = length(p);

    float edge = 1.0 - smoothstep(1.0 - EDGE_SOFTNESS, 1.0, r);
    float z = sqrt(max(1.0 - r * r, 0.0));
    vec3 n = vec3(p, z);

    if (vTexIndex != NO_TEXTURE)
    {
        // ---- Baked: an oxygen vent's normal-map sprite sheet -------------------------------
        //
        // The branch is uniform across every 2x2 derivative quad — vTexIndex comes from a
        // per-INSTANCE attribute and a fragment's derivative neighbours (helper invocations
        // included) belong to the same primitive, hence the same instance — so calling dFdx in
        // here is well defined. The engine's own texture.frag takes the identical liberty.
        //
        // No fract() and no tiling term: this renderer has no tiling attribute, so the engine's
        // `coord = texCoord * texTiling; tiled = fract(coord)` collapses to `vUv`. What remains
        // is the engine's mapping byte for byte — a Texture is a SUB-RECT of an array layer, so
        // the quad's 0..1 has to be remapped into [vTexStart, vTexStart + vTexSize] and the
        // gradients scaled by vTexSize or the wrong mip level is selected.
        vec2 ddx = dFdx(vUv) * vTexSize;
        vec2 ddy = dFdy(vUv) * vTexSize;
        vec2 uv  = vTexStart + vTexSize * vUv;
        vec4 texel = sampleTextureArrayGrad(int(vSamplerIndex), vec3(uv, floor(vTexIndex)), ddx, ddy);

        // RAW decode, and NO sRGB conversion anywhere. The sheet is uploaded as
        // TextureFormat.RGBA8 (linear) precisely so the sampler hands back the baked bytes: this
        // is a vector, not a colour, and `rgb/255*2-1` on the file's own pixels measures mean
        // length 1.0000 (same as sprites/diver-normal.png). Sampling it as SRGBA8, or applying a
        // transfer function here, would bend every component through a 2.4 power and leave a
        // normal that is neither unit-length nor pointing where it was baked to point.
        vec3 decoded = texel.rgb * 2.0 - 1.0;

        // ---- THE GREEN CHANNEL IS NEGATED, AND THAT IS NOT A SIGN ERROR --------------------
        //
        // This shader's own space has +y pointing DOWN the screen: `vUv` is 0 at the top of the
        // quad and 1 at the bottom (world y IS depth on `main`, and screen y is down on `hud`), so
        // the analytic hemisphere above has n.y = +1 at its BOTTOM edge. Every normal map in this
        // game is baked in the opposite, OpenGL convention — green HIGH where a surface faces
        // UP-screen. Measured on the committed sheets, mean green at the topmost against the
        // bottommost opaque texel of each column:
        //
        //     sprites/oxygen-normal.png    216.4 top / 33.4 bottom   (n = 1917 columns)
        //     sprites/diver-normal.png     195.7 top / 71.9 bottom   (n = 1577 columns)
        //     backdrop/rock-top-normal.png 188   top / 98   bottom   (PearlNormalMap's own probe)
        //
        // The RED channel needs no flip and is measured as the control: 24.9 at the leftmost
        // opaque texel against 221.0 at the rightmost, i.e. +x is right-screen in the bake exactly
        // as it is in `p`.
        //
        // render/PearlNormalMap.kt applies the SAME negation in the other direction and its class
        // doc has the two-build probe behind it — a wrongly signed y there was invisible in a
        // still frame and measured 2.5x weaker once found, so this is precisely the kind of sign
        // that must be measured rather than reasoned about. Getting it wrong here lights the vent
        // from below when the torch is above it.
        decoded.y = -decoded.y;

        // Renormalise, as lighting/normal_map.frag does: bilinear filtering between two texels
        // shortens the interpolated vector, and dot(n, h) below assumes unit length. (The raw
        // decode is already unit to 4 decimal places — mean length 1.0000 over 217715 opaque
        // texels of the vent sheet — which is the same measurement that proves the sheet must be
        // uploaded as a LINEAR format; sampling it as SRGBA8 would bend every component through a
        // 2.4 power and this length would not be 1.)
        //
        // The guard is for the degenerate texel (128, 128, 128), which decodes to exactly
        // (0, 0, 0) and would make the division produce NaN — and a NaN here propagates through
        // the film out to fragColor, where it is a hole in the sprite rather than a subtly wrong
        // shade.
        float len = length(decoded);
        n = len > 1e-4 ? decoded / len : vec3(0.0, 0.0, 1.0);
        z = n.z;

        // THE ALPHA IS USED AS IT IS — deliberately not run through the analytic path's
        // smoothstep. The bake already antialiases the silhouette (2 px of fully transparent
        // margin, ~4% of texels at partial coverage), so a second ramp over an already-ramped
        // value would eat the soft rim it exists to preserve and give the blob a harder edge than
        // a pearl has.
        edge = texel.a;
    }

    if (edge <= 0.0)
        discard; // the quad's corners, or the sheet's transparent margin: no colour and,
                 // importantly, no depth write

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
    // `z` is the normal's out-of-screen component either way — the analytic hemisphere's sqrt or
    // the sheet's decoded BLUE channel — so this line is untouched by the textured path and means
    // the same thing in it: 0 facing the camera, 1 at a silhouette edge.
    float rimness = 1.0 - z;
    float alpha = vBase.a * mix(vBody.x, vBody.y, rimness) * edge;

    fragColor = vec4(rgb, alpha);
}
