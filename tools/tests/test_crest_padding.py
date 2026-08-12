"""
The crest's transparent top padding - the texture's claim on the 2048 arrays.

WHAT GOES WRONG WITHOUT IT IS NOT VISIBLE AT ALL. The crest is sized from its WIDTH so its
texels match the wall's at the waterline join, which leaves its HEIGHT at the mercy of the
art's proportions - and the engine's array-reuse test, `arraySize >= max_dim and max_dim >
arraySize // 2`, is STRICT at the bottom. The art delivered on 2026-08-12 baked to 614x1023,
two texels under the bound, which would have refused the 2048 arrays the diver's sheets have
already allocated and taken a 1024x1024x4x50 array per format instead: +419.4 MB of VRAM, on
an unknown booth GPU, reported by the bake as one word changing from True to False.

The padding also has a second job it must NOT do: it must not move the art. Every world
position on the cliff is derived from a texel row, so padding that was applied at the bottom -
or symmetrically - would push the summit up out of the sea.
"""
import numpy as np
import pytest

from build_backdrop import pad_top_to_height
from backdrop import geometry


def _art(rows=4, width=3):
    """Opaque art with a recognisable per-row value, so a shift shows up as a wrong row."""
    a = np.zeros((rows, width, 4), dtype=np.uint8)
    for y in range(rows):
        a[y, :, :3] = 10 * (y + 1)
        a[y, :, 3] = 255
    return a


def test_the_padding_goes_on_top_so_the_art_keeps_its_distance_from_the_bottom():
    # The crest's quad is anchored by its BOTTOM edge at RockFace.WALL_TOP_DEPTH, so this is
    # what keeps every texel of rock at the world position it had. Padding the other end
    # would lift the summit out of the sea by the full height of the padding.
    art = _art(rows=4)
    out = pad_top_to_height(art, 10, (0, 0, 0))

    assert out.shape == (10, 3, 4)
    assert np.array_equal(out[6:], art)


def test_the_padding_is_fully_transparent():
    out = pad_top_to_height(_art(rows=4), 10, (0, 0, 0))
    assert (out[:6, :, 3] == 0).all()


def test_the_albedo_pads_with_black_and_the_normals_with_a_flat_vector():
    # A LINEAR tap on the boundary row blends the padding's RGB into the art's, under an
    # alpha that is also blending to zero. Black is the neutral there for an albedo; for a
    # normal map the neutral is the FLAT vector (0, 0, 1) -> (128, 128, 255), matching what
    # bake_rock_top already writes for an uncovered texel. A zero-length normal must never
    # reach the renderer, so (0, 0, 0) would be wrong in the normal map specifically.
    albedo = pad_top_to_height(_art(), 10, (0, 0, 0))
    assert (albedo[:6, :, :3] == 0).all()

    normals = pad_top_to_height(_art(), 10, (128, 128, 255))
    assert (normals[:6, :, 0] == 128).all()
    assert (normals[:6, :, 1] == 128).all()
    assert (normals[:6, :, 2] == 255).all()

    decoded = (normals[:6, :, :3].astype(np.float64) / 255.0) * 2.0 - 1.0
    assert np.allclose(np.linalg.norm(decoded, axis=-1), 1.0, atol=0.01)


def test_padding_to_the_art_s_own_height_is_a_copy_and_not_the_same_array():
    art = _art(rows=4)
    out = pad_top_to_height(art, 4, (0, 0, 0))
    assert np.array_equal(out, art)
    out[0, 0, 0] = 99
    assert art[0, 0, 0] != 99


def test_padding_cannot_be_used_to_crop():
    with pytest.raises(ValueError):
        pad_top_to_height(_art(rows=4), 3, (0, 0, 0))


def test_the_height_the_bake_pads_to_is_one_that_actually_reuses_the_2048_arrays():
    """
    The property the padding exists for, asserted against the engine's own rule.

    614x1023 is what the 2026-08-12 art bakes to unpadded, and it is REFUSED - not by a
    margin, but by two texels. Padding to the wall's 2048 is what settles it, and it settles
    it for any source aspect rather than for this one.
    """
    assert not geometry.reuses_array(max(614, 1023), 2048)
    assert geometry.reuses_array(max(614, 2048), 2048)

    # ...and the refusal is expensive, which is why the bake raises rather than prints.
    refused_bucket = geometry.bucket_for(1023)
    assert refused_bucket == 1024
    assert 2 * geometry.array_bytes(refused_bucket) > 400e6
