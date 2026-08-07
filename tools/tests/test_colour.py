import numpy as np
from spritesheet.colour import (
    srgb_to_linear, linear_to_srgb, resize_plane, resize_channels, to_u8,
)


def test_srgb_linear_round_trips():
    c = np.linspace(0.0, 1.0, 256, dtype=np.float64)
    assert np.allclose(linear_to_srgb(srgb_to_linear(c)), c, atol=1e-6)


def test_srgb_to_linear_known_values():
    # Endpoints are exact; mid-grey 0.5 sRGB is the standard 0.2140 linear.
    assert srgb_to_linear(np.array([0.0]))[0] == 0.0
    assert np.isclose(srgb_to_linear(np.array([1.0]))[0], 1.0)
    assert np.isclose(srgb_to_linear(np.array([0.5]))[0], 0.21404, atol=1e-4)


def test_srgb_to_linear_uses_the_linear_segment_near_zero():
    # Below 0.04045 the transfer function is a straight line, not a power curve.
    c = np.array([0.02])
    assert np.isclose(srgb_to_linear(c)[0], 0.02 / 12.92)


def test_resize_plane_preserves_a_constant():
    plane = np.full((100, 50), 0.25, dtype=np.float32)
    out = resize_plane(plane, (10, 20))
    assert out.shape == (20, 10)
    assert np.allclose(out, 0.25, atol=1e-5)


def test_resize_channels_keeps_channel_count_and_order():
    arr = np.zeros((8, 4, 3), dtype=np.float32)
    arr[..., 0] = 1.0
    out = resize_channels(arr, (2, 4))
    assert out.shape == (4, 2, 3)
    assert np.allclose(out[..., 0], 1.0, atol=1e-5)
    assert np.allclose(out[..., 1], 0.0, atol=1e-5)


def test_to_u8_rounds_rather_than_truncates():
    # 0.5/255 must land on 1, not 0 - truncation here is the same class of bug as
    # Pillow's 16-bit high-byte read.
    assert to_u8(np.array([0.9 / 255.0]))[0] == 1
    assert to_u8(np.array([1.0]))[0] == 255
    assert to_u8(np.array([-0.5]))[0] == 0
    assert to_u8(np.array([2.0]))[0] == 255
