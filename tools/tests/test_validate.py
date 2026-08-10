import numpy as np
import pytest
from PIL import Image
from spritesheet.validate import (
    ALPHA_TOLERANCE, SourceError, list_frames, check_pairing, check_dimensions,
    check_alpha_agreement, check_normal_encoding, union_bbox,
)
from spritesheet.colour import linear_to_srgb


def _write_png(path, rgba):
    Image.fromarray(rgba, mode="RGBA").save(path)


def test_list_frames_ignores_non_png(tmp_path):
    # .DS_Store already exists in assets/sprites/diffuse - a naive listdir sees 43.
    for name in ("0002.png", "0001.png"):
        _write_png(tmp_path / name, np.zeros((4, 4, 4), dtype=np.uint8))
    (tmp_path / ".DS_Store").write_bytes(b"junk")
    (tmp_path / "notes.txt").write_text("hi")
    assert [p.name for p in list_frames(tmp_path)] == ["0001.png", "0002.png"]


def test_check_pairing_accepts_matching_names():
    check_pairing(["0001.png", "0002.png"], ["0001.png", "0002.png"])


def test_check_pairing_rejects_count_mismatch():
    with pytest.raises(SourceError, match="count"):
        check_pairing(["0001.png"], ["0001.png", "0002.png"])


def test_check_pairing_rejects_name_mismatch():
    with pytest.raises(SourceError, match="0003"):
        check_pairing(["0001.png", "0003.png"], ["0001.png", "0002.png"])


def test_check_dimensions_accepts_uniform():
    check_dimensions([(1000, 2000)] * 5)


def test_check_dimensions_rejects_odd_one_out():
    with pytest.raises(SourceError, match="dimension"):
        check_dimensions([(1000, 2000), (1000, 2000), (999, 2000)])


def test_check_dimensions_rejects_empty_list():
    # An empty list has no "mixed" dimensions to disagree on, so the len(unique) > 1
    # check silently passes it - but zero frames is not a valid bake input either.
    with pytest.raises(SourceError):
        check_dimensions([])


def test_alpha_agreement_tolerates_pillows_truncation():
    # Pillow reads the 16-bit diffuse as its HIGH BYTE, producing ~6326 legitimate
    # off-by-one differences per frame. A zero-tolerance gate aborts on real data.
    a = np.full((100, 100), 200, dtype=np.uint8)
    b = a.copy()
    b[:50, :] -= 1
    check_alpha_agreement(a, b, 0)


def test_alpha_agreement_rejects_a_real_desync():
    a = np.full((100, 100), 200, dtype=np.uint8)
    b = a.copy()
    b[:50, :] = 100
    with pytest.raises(SourceError, match="alpha"):
        check_alpha_agreement(a, b, 7)


def test_check_normal_encoding_accepts_srgb_encoded_flat():
    linear = np.zeros((8, 8, 3))
    linear[..., 0] = 0.5
    linear[..., 1] = 0.5
    linear[..., 2] = 1.0
    rgb = np.clip(linear_to_srgb(linear) * 255 + 0.5, 0, 255).astype(np.uint8)
    check_normal_encoding(rgb, np.full((8, 8), 255, dtype=np.uint8), 0)


def test_check_normal_encoding_rejects_a_raw_linear_map():
    # A map that is NOT sRGB-encoded decodes to non-unit vectors and must fail,
    # which is what protects against a future re-export changing convention.
    rgb = np.zeros((8, 8, 3), dtype=np.uint8)
    rgb[..., 0] = 128
    rgb[..., 1] = 128
    rgb[..., 2] = 255
    with pytest.raises(SourceError, match="unit"):
        check_normal_encoding(rgb, np.full((8, 8), 255, dtype=np.uint8), 3)


def test_check_normal_encoding_rejects_an_all_transparent_frame():
    # mean_normal_length returns nan when no texel is opaque (mask.any() is False).
    # nan comparisons are always False, so `abs(length - 1.0) > TOLERANCE` alone
    # would silently PASS this frame; the `not np.isfinite(length) or` guard is what
    # actually catches it.
    rgb = np.zeros((8, 8, 3), dtype=np.uint8)
    alpha = np.zeros((8, 8), dtype=np.uint8)
    with pytest.raises(SourceError, match="unit"):
        check_normal_encoding(rgb, alpha, 0)


def test_union_bbox_is_inclusive_and_spans_every_frame():
    a = np.zeros((10, 10), dtype=np.uint8)
    a[2:5, 3:6] = 255
    b = np.zeros((10, 10), dtype=np.uint8)
    b[6:8, 1:4] = 255
    assert union_bbox([a, b]) == (1, 2, 5, 7)


def test_union_bbox_rejects_an_empty_set():
    with pytest.raises(SourceError):
        union_bbox([np.zeros((4, 4), dtype=np.uint8)])
