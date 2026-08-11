import numpy as np
import pytest

from backdrop.mirror import decode, mirror_diffuse, mirror_normal


def _normals(h=3, w=5, seed=7):
    rng = np.random.default_rng(seed)
    v = rng.normal(size=(h, w, 3))
    v /= np.linalg.norm(v, axis=-1, keepdims=True)
    rgb = np.round((v + 1.0) * 0.5 * 255.0).astype(np.uint8)
    alpha = rng.integers(0, 256, size=(h, w, 1), dtype=np.uint8)
    return np.dstack([rgb, alpha])


def test_mirror_diffuse_moves_texels_and_changes_no_colour():
    src = np.arange(3 * 5 * 4, dtype=np.uint8).reshape(3, 5, 4)
    out = mirror_diffuse(src)
    assert np.array_equal(out, src[:, ::-1, :])
    # Same multiset of texels, so nothing was recoloured on the way.
    assert np.array_equal(np.sort(out.reshape(-1, 4), axis=0), np.sort(src.reshape(-1, 4), axis=0))


def test_mirror_normal_negates_the_x_component_of_every_vector():
    src = _normals()
    out = mirror_normal(src)
    a, b = decode(src), decode(out)
    w = src.shape[1]
    for y in range(src.shape[0]):
        for x in range(w):
            got, want = b[y, x], a[y, w - 1 - x]
            # THE WHOLE POINT: a bump lit from the left must come out lit from the right.
            assert got[0] == pytest.approx(-want[0], abs=1e-12)
            assert got[1] == pytest.approx(want[1], abs=1e-12)
            assert got[2] == pytest.approx(want[2], abs=1e-12)


def test_mirror_normal_leaves_alpha_alone_apart_from_moving_it():
    src = _normals()
    assert np.array_equal(mirror_normal(src)[..., 3], src[:, ::-1, 3])


def test_mirroring_twice_is_the_identity_for_both_maps():
    # Exactness matters: the negation is done in eight bits precisely so that it costs
    # nothing. A rounding implementation would drift here.
    diffuse = np.arange(4 * 6 * 4, dtype=np.uint8).reshape(4, 6, 4)
    assert np.array_equal(mirror_diffuse(mirror_diffuse(diffuse)), diffuse)
    normal = _normals(4, 6)
    assert np.array_equal(mirror_normal(mirror_normal(normal)), normal)


def test_mirror_normal_preserves_unit_length():
    src = _normals(8, 8)
    before = np.linalg.norm(decode(src), axis=-1)
    after = np.linalg.norm(decode(mirror_normal(src)), axis=-1)
    assert np.allclose(np.sort(before, axis=None), np.sort(after, axis=None))


def test_mirroring_refuses_anything_that_is_not_eight_bit_rgba():
    with pytest.raises(ValueError):
        mirror_normal(np.zeros((3, 3, 3), dtype=np.uint8))
    with pytest.raises(ValueError):
        mirror_normal(np.zeros((3, 3, 4), dtype=np.float32))
