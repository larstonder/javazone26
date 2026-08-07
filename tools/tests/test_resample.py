import numpy as np
from spritesheet.resample import (
    resample_diffuse, resample_normal, decode_normals, mean_normal_length,
)
from spritesheet.colour import linear_to_srgb


def _flat_normal_field(h, w):
    """An sRGB-ENCODED flat +Z normal map, i.e. what the real files contain."""
    linear = np.zeros((h, w, 3), dtype=np.float64)
    linear[..., 0] = 0.5   # x = 0
    linear[..., 1] = 0.5   # y = 0
    linear[..., 2] = 1.0   # z = 1
    # Encode to sRGB so decode_normals has to undo it.
    return np.clip(linear_to_srgb(linear) * 255 + 0.5, 0, 255).astype(np.uint8)


def test_decode_normals_linearizes_before_decoding():
    rgb = _flat_normal_field(4, 4)
    v = decode_normals(rgb)
    assert np.allclose(v[..., 0], 0.0, atol=0.01)
    assert np.allclose(v[..., 1], 0.0, atol=0.01)
    assert np.allclose(v[..., 2], 1.0, atol=0.01)


def test_decode_without_linearizing_would_be_wrong():
    # Guards the exact bug the review caught: the raw decode gives a large
    # positive bias where the correct one gives ~0.
    rgb = _flat_normal_field(4, 4)
    raw = rgb / 255.0 * 2.0 - 1.0
    assert raw[..., 0].mean() > 0.3          # wrong, and obviously so
    assert abs(decode_normals(rgb)[..., 0].mean()) < 0.01


def test_mean_normal_length_is_unit_for_a_valid_map():
    rgb = _flat_normal_field(8, 8)
    alpha = np.full((8, 8), 255, dtype=np.uint8)
    assert abs(mean_normal_length(rgb, alpha) - 1.0) < 0.01


def test_mean_normal_length_ignores_transparent_texels():
    rgb = _flat_normal_field(8, 8)
    rgb[4:, :, :] = 0                        # garbage, but fully transparent
    alpha = np.zeros((8, 8), dtype=np.uint8)
    alpha[:4, :] = 255
    assert abs(mean_normal_length(rgb, alpha) - 1.0) < 0.01


def test_resampled_normals_stay_unit_length():
    rgb = _flat_normal_field(64, 64)
    alpha = np.full((64, 64), 255, dtype=np.uint8)
    out_rgb, _ = resample_normal(rgb, alpha, (16, 16))
    v = out_rgb / 255.0 * 2.0 - 1.0          # output is LINEAR, decode directly
    assert np.allclose(np.linalg.norm(v, axis=-1), 1.0, atol=0.02)


def test_normal_zero_alpha_falls_back_to_flat_and_never_nans():
    rgb = _flat_normal_field(16, 16)
    alpha = np.zeros((16, 16), dtype=np.uint8)
    out_rgb, out_a = resample_normal(rgb, alpha, (4, 4))
    assert np.isfinite(out_rgb).all()
    v = out_rgb / 255.0 * 2.0 - 1.0
    assert np.allclose(v[..., 2], 1.0, atol=0.02)
    assert (out_a == 0).all()


def test_diffuse_does_not_halo_from_transparent_black():
    # A white opaque disc on transparent BLACK. Without premultiplication the
    # downscaled edge picks up the black and darkens - this is the halo bug.
    h = w = 64
    yy, xx = np.mgrid[0:h, 0:w]
    inside = (yy - 32) ** 2 + (xx - 32) ** 2 < 20 ** 2
    rgb = np.zeros((h, w, 3), dtype=np.uint8)
    rgb[inside] = 255
    alpha = np.where(inside, 255, 0).astype(np.uint8)

    out_rgb, out_a = resample_diffuse(rgb, alpha, (16, 16))
    lit = out_a > 32
    assert lit.any()
    # Every partially-covered texel must still be white, not grey.
    assert out_rgb[lit].min() > 240


def test_diffuse_preserves_a_flat_opaque_colour():
    rgb = np.full((32, 32, 3), 128, dtype=np.uint8)
    alpha = np.full((32, 32), 255, dtype=np.uint8)
    out_rgb, out_a = resample_diffuse(rgb, alpha, (8, 8))
    assert np.abs(out_rgb.astype(int) - 128).max() <= 1
    assert (out_a == 255).all()


def test_resample_returns_requested_size():
    rgb = np.zeros((100, 40, 3), dtype=np.uint8)
    alpha = np.full((100, 40), 255, dtype=np.uint8)
    for fn in (resample_diffuse, resample_normal):
        out_rgb, out_a = fn(rgb, alpha, (10, 25))
        assert out_rgb.shape == (25, 10, 3)
        assert out_a.shape == (25, 10)
