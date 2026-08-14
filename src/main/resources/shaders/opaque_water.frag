// RESTORE `main`'s ALPHA BELOW THE WATERLINE. Two lines of GLSL; the whole of the reasoning is
// in `render/OpaqueWater.kt`'s class doc, and the short version is:
//
//   `BlendFunction.NORMAL` is a single `glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)`, and
//   OpenGL applies those factors to the ALPHA channel too. So a translucent draw over an opaque
//   destination leaves `dst_a = a*a + dst_a*(1 - a)` instead of the correct `a + dst_a*(1 - a)`.
//   The stray `a^2` is the defect. Correct "over" needs `glBlendFuncSeparate`, which appears
//   nowhere in the engine jar, so it cannot be expressed. `main` then composites over a BLANK sky
//   surface, so an eroded pixel reveals the cleared backbuffer: a black hole in the water.
//
// IT IS NOT MOTE-SPECIFIC. The god rays pre-erode `main`'s alpha to a measured 191/255 inside a
// shaft, and the marine snow on top of that is what pushes it into visibility — which is why every
// disc sits inside a shaft column, and why both disabling the mote draw and halving the mote
// density made them go away without touching the cause. Every translucent draw on `main`
// contributes; this restores all of them at once.
//
// THE GATE IS A POSITION AND NEVER AN ALPHA VALUE. `main`'s alpha is legitimately partial in
// exactly one place — the waterline, where `water.frag` ramps `coverage` to anti-alias itself
// against the sky behind. Everything below that is opaque BY CONSTRUCTION (water, rock, diver),
// so below `gateUv` there is no threshold to tune and nothing to preserve. Thresholding on alpha
// instead would be a guess at which partial alphas were meant, and would harden the meniscus.
//
// `gateUv` is a WORLD DEPTH converted to this surface's uv by `EnPustTil.onRender`, from the same
// `mainCamera` matrix the frame was drawn with. uv.y is 1 at the TOP of the screen (the vertex
// shader maps NDC +1 to texCoord 1) and 0 at the bottom, so `uv.y < gateUv` means "deeper than
// the gate". A gate off the top of the frame clamps to 1 (restore everything, the whole frame is
// underwater) and one off the bottom clamps to 0 (restore nothing, we are looking at sky) — both
// exactly right, and both fall out of the clamp rather than needing a branch.
//
// RGB IS UNTOUCHED, and that is what makes this a repair rather than a paint-over: the broken
// blend got the COLOUR right. `dst_rgb = src_rgb*a + dst_rgb*(1 - a)` is correct straight-alpha
// "over"; only the alpha channel carried the `a^2`. The pixel under a black hole already holds
// the right colour and is simply being composited at the wrong opacity.
#version 330 core

in vec2 uv;

out vec4 fragColor;

uniform sampler2D baseTex;

/** Where the waterline gate sits in this surface's uv. 0 disables the effect entirely. */
uniform float gateUv;

void main()
{
    vec4 c = texture(baseTex, uv);
    fragColor = vec4(c.rgb, uv.y < gateUv ? 1.0 : c.a);
}
