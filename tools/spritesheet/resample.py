"""
The two resampling pipelines, and the only place in the bake where getting the
maths subtly wrong still produces a plausible-looking image.

Both source sets are sRGB-encoded (verified: sRGB intent-3 + gAMA 0.45455 chunks in
all 84 files). For the diffuse that is unremarkable. For the NORMALS it is the whole
problem: decoding them raw as rgb/255*2-1 gives a mean X of +0.400 on a bilaterally
symmetric character and leaves 97% of vectors off unit length. Linearizing first
gives mean X +0.001 and |v| 0.998. Renormalizing does not rescue a raw decode - it
projects the wrong direction onto the unit sphere and hides the error.
"""
import numpy as np

from .colour import (
    srgb_to_linear, linear_to_srgb, resize_plane, resize_channels, to_u8,
)

_EPS = 1e-6
_FLAT = np.array([0.0, 0.0, 1.0])


def decode_normals(rgb: np.ndarray) -> np.ndarray:
    """sRGB-encoded uint8 normal map -> float vectors in [-1,1]."""
    return srgb_to_linear(np.asarray(rgb, dtype=np.float64) / 255.0) * 2.0 - 1.0


def mean_normal_length(rgb: np.ndarray, alpha: np.ndarray) -> float:
    """
    Mean |v| over opaque texels. The discriminating test for the decode convention:
    a +Z-dominant mean is consistent with both the right and the wrong decode, but
    only the right one produces unit vectors.
    """
    mask = np.asarray(alpha) > 200
    if not mask.any():
        return float("nan")
    return float(np.linalg.norm(decode_normals(rgb)[mask], axis=-1).mean())


def _alpha_weighted_resize(values: np.ndarray, alpha: np.ndarray, size: tuple):
    """
    Premultiply by alpha, resize, then divide back out.

    Without this, fully transparent texels contribute their (undefined) values to
    every partially-covered output texel: a dark fringe on the diffuse, and garbage
    directions on the normals.
    """
    weighted = resize_channels(values * alpha[..., None], size)
    resized_alpha = np.clip(resize_plane(alpha.astype(np.float32), size), 0.0, 1.0)
    covered = resized_alpha > _EPS
    out = np.where(
        covered[..., None],
        weighted / np.maximum(resized_alpha, _EPS)[..., None],
        0.0,
    )
    return out, resized_alpha, covered


def resample_diffuse(rgb: np.ndarray, alpha: np.ndarray, size: tuple):
    """Downscale the albedo. Returns (rgb_u8, alpha_u8), sRGB-encoded."""
    linear = srgb_to_linear(np.asarray(rgb, dtype=np.float64) / 255.0)
    a = np.asarray(alpha, dtype=np.float64) / 255.0
    resized, resized_alpha, _ = _alpha_weighted_resize(linear, a, size)
    return to_u8(linear_to_srgb(np.clip(resized, 0.0, 1.0))), to_u8(resized_alpha)


def resample_normal(rgb: np.ndarray, alpha: np.ndarray, size: tuple):
    """
    Downscale the normal map. Returns (rgb_u8, alpha_u8).

    The RGB output is LINEAR-encoded, because the sheet is uploaded as RGBA8 rather
    than SRGBA8: the GPU hands the shader the stored bytes unchanged, so the stored
    value must be (v+1)/2 directly. Re-applying sRGB here would reintroduce exactly
    the bias this function exists to remove.
    """
    v = decode_normals(rgb)
    a = np.asarray(alpha, dtype=np.float64) / 255.0
    resized, resized_alpha, covered = _alpha_weighted_resize(v, a, size)

    # Averaging unit vectors shortens them; renormalizing restores the curvature
    # that the fins, mask and shoulders depend on.
    length = np.linalg.norm(resized, axis=-1, keepdims=True)
    unit = np.where(length > _EPS, resized / np.maximum(length, _EPS), _FLAT)

    # Where nothing was covered the direction is undefined and the divide above is
    # meaningless. These texels are never sampled, but a NaN would propagate.
    unit = np.where(covered[..., None], unit, _FLAT)

    return to_u8((unit + 1.0) * 0.5), to_u8(resized_alpha)
