"""
Keeping the rock's albedo above the GI reflectance floor without repainting it.

`texture_multiply_blend.frag` is the whole of the problem:

    if (length(c0.rgb) < minReflectance)   // minReflectance = 0.02
        c0.rgb = vec3(minReflectance);
    fragColor = vec4(c0.rgb * c1.rgb, c0.a);

`c0` is the LINEAR main-surface colour, so the test is on the rock's own linear RGB
after the GPU has decoded SRGBA8. Measured on `assets/rock/diffuse.png` over its
227 370 opaque texels: min linear length 0.00334, median 0.02312, and **44.2% of them
sit under 0.02**. Nearly half the rock would be thrown away and replaced with flat
`vec3(0.02)` grey - not darkened, FLATTENED, which is how commit `0f07303` got a
razor-sharp seam out of a colour that merely sat near the boundary.

The fix is an AMBIENT TERM, not a gain. A gain large enough to lift the darkest texel
(6.6x) makes the wall a mid-grey slab; a gain small enough to keep the tuned wall
luminance leaves ~2% of texels under the floor, and one texel under it is a hard-edged
patch of grey. Adding a constant linear vector instead raises every texel by at least
its own length - `length(ambient + v) >= length(ambient)` for `v` in the positive
octant, so ONE inequality on the ambient term proves the floor is cleared everywhere -
and it is also the physically honest correction: the source has render AO baked into
it, and albedo does not go to 0.003.

Both parameters are anchored on values the project already tuned, so the rock inherits
the look `0f07303` measured rather than inventing a new one: the ambient is a fraction
of `DiveRenderer.wallColor` (which is why the rock stays warm rather than neutral), and
the gain is solved so the result's MEAN luminance equals that same wallColor's 0.034 -
still well under lit shallow water's 0.085, so the wall remains a subordinate dark
border rather than a bright frame.
"""
import numpy as np

# DiveRenderer.wallColor, sRGB. The rock's ambient and its target mean luminance are
# both stated relative to this, so a re-tune of the wall moves the bake with it.
WALL_COLOR_SRGB = (0.22, 0.20, 0.18)

# GlobalIlluminationSystem.minReflectance, and DiveRenderer's own margin over it. The
# margin absorbs the 8-bit sRGB quantisation the bake applies after this arithmetic.
GI_REFLECTANCE_FLOOR = 0.02
FLOOR_MARGIN = 1.10

# Rec.709 luminance, the same weighting DiveRenderer's colour notes quote.
LUMA = np.array([0.2126, 0.7152, 0.0722])


def luminance(linear_rgb: np.ndarray) -> np.ndarray:
    """Relative luminance of linear RGB."""
    return np.asarray(linear_rgb, dtype=np.float64) @ LUMA


def linear_length(linear_rgb: np.ndarray) -> np.ndarray:
    """`length(c0.rgb)` exactly as the multiply shader computes it."""
    return np.linalg.norm(np.asarray(linear_rgb, dtype=np.float64), axis=-1)


def solve_gain(source_mean_luminance: float, ambient_luminance: float, target_luminance: float) -> float:
    """
    The gain that puts `ambient + gain * source` at `target_luminance` on average.

    Clamped at zero rather than allowed to go negative: an ambient already brighter
    than the target means the floor and the tuned wall luminance cannot both be had,
    and silently inverting the rock is not the answer - the caller should lower
    `AMBIENT_FRACTION` instead.
    """
    if source_mean_luminance <= 0:
        raise ValueError("source has no luminance to scale")
    return max(0.0, (target_luminance - ambient_luminance) / source_mean_luminance)


def lift(linear_rgb: np.ndarray, ambient: np.ndarray, gain: float) -> np.ndarray:
    """`ambient + gain * source`, clipped into [0,1]. Hue is preserved where it matters."""
    out = np.asarray(ambient, dtype=np.float64) + gain * np.asarray(linear_rgb, dtype=np.float64)
    return np.clip(out, 0.0, 1.0)
