"""
The cliff BODY crop - the texture that guarantees rock at the frame edge.

What can go wrong here is silent in every way that matters: a crop one column too wide
includes the first ragged texel and the frame edge starts showing water again at some
window widths; a mirror that forgets to negate the normal's x lights every bump on one
half of every tile from the wrong side; a crop that starts at column 0 includes the
transparent wrap border and puts a hairline down the middle of the tiled body.

`build_backdrop.crop_body` cannot be run from a clean clone (assets/ is gitignored), so
these run on synthetic arrays. `RockFaceTest` covers the FILES that actually shipped.
"""
import numpy as np
import pytest

import build_backdrop
from backdrop import mirror
from build_backdrop import BORDER_TEXEL_COLUMNS, SourceError, crop_body, first_not_solid_column


def _wall(width=20, height=8, first_not_solid=12, seed=7):
    """
    A stand-in for the bake's lifted output: an opaque body, a transparent wrap border at
    column 0, and a ragged edge from `first_not_solid` outward. Values are kept well clear
    of the GI reflectance floor so `crop_body`'s own floor check is not what is under test.
    """
    rng = np.random.default_rng(seed)
    rgba = np.empty((height, width, 4), dtype=np.uint8)
    rgba[..., :3] = rng.integers(80, 200, size=(height, width, 3), dtype=np.uint8)
    rgba[..., 3] = 255
    rgba[:, :BORDER_TEXEL_COLUMNS, 3] = 0          # clear_wrap_border
    rgba[0, first_not_solid, 3] = 128              # the first texel of the ragged edge
    rgba[:, first_not_solid + 1:, 3] = 0
    return rgba


def test_first_not_solid_column_skips_the_wrap_border():
    # Column 0 is transparent in every row, so a search that started at 0 would return 0 and
    # the crop would be empty. The border is the bake's own doing (clear_wrap_border), which
    # is exactly why the search may not treat it as art.
    wall = _wall(first_not_solid=12)
    assert wall[:, 0, 3].max() == 0
    assert first_not_solid_column(wall) == 12


def test_first_not_solid_column_finds_a_single_partly_transparent_texel():
    # One texel of 160 at alpha 254 is enough: the body's guarantee is per-TEXEL, so "solid
    # at every row" cannot be a majority vote.
    wall = _wall(first_not_solid=12)
    wall[3, 5, 3] = 254
    assert first_not_solid_column(wall) == 5


def test_first_not_solid_column_rejects_art_with_no_ragged_edge():
    wall = _wall(first_not_solid=12)
    wall[..., 3] = 255
    with pytest.raises(SourceError):
        first_not_solid_column(wall)


def test_crop_body_is_the_solid_columns_mirror_doubled():
    wall = _wall(width=20, first_not_solid=12)
    body = crop_body(wall, wall)

    columns = 12 - BORDER_TEXEL_COLUMNS
    assert body["size"] == (2 * columns, wall.shape[0])
    assert body["columns"] == (BORDER_TEXEL_COLUMNS, 12)
    assert body["diffuse"].shape == (wall.shape[0], 2 * columns, 4)
    # The first half is the crop itself, the second its mirror - so the join between two
    # tiles is a reflection rather than a step, which is the whole reason for the doubling.
    np.testing.assert_array_equal(body["diffuse"][:, :columns, :], wall[:, BORDER_TEXEL_COLUMNS:12, :])
    np.testing.assert_array_equal(
        body["diffuse"][:, columns:, :], mirror.mirror_diffuse(wall[:, BORDER_TEXEL_COLUMNS:12, :])
    )


def test_crop_body_is_opaque_at_every_texel():
    body = crop_body(_wall(), _wall())
    assert (body["diffuse"][..., 3] == 255).all()
    assert (body["normal"][..., 3] == 255).all()


def test_crop_body_is_its_own_horizontal_mirror():
    # This is what lets ONE pair of textures serve both sides of the column: two array
    # layers rather than four. Albedo exactly; the normal map with its x component negated,
    # which in eight bits is exactly 255 - r.
    diffuse = _wall(seed=1)
    normal = _wall(seed=2)
    body = crop_body(diffuse, normal)

    np.testing.assert_array_equal(body["diffuse"], body["diffuse"][:, ::-1, :])
    flipped = body["normal"][:, ::-1, :].copy()
    flipped[..., 0] = 255 - flipped[..., 0]
    np.testing.assert_array_equal(body["normal"], flipped)


def test_crop_bodys_outermost_column_is_the_walls_first_column_of_art():
    # The body is anchored one texel inward of the wall tile's outward end, so THIS column
    # is what the wall's own column BORDER_TEXEL_COLUMNS reflects into. Get it wrong and the
    # join between the edge art and the body is a discontinuity instead of a mirror.
    diffuse = _wall(seed=3)
    normal = _wall(seed=4)
    body = crop_body(diffuse, normal)

    np.testing.assert_array_equal(body["diffuse"][:, 0, :], diffuse[:, BORDER_TEXEL_COLUMNS, :])
    np.testing.assert_array_equal(body["normal"][:, 0, :], normal[:, BORDER_TEXEL_COLUMNS, :])


def test_crop_body_rejects_a_normal_map_that_is_not_solid_over_the_crop():
    # The bound is taken from the albedo and applied to both. A normal map whose alpha runs
    # out earlier would leave a transparent texel in the middle of the body, i.e. a hole in
    # the one guarantee it exists to make.
    diffuse = _wall(first_not_solid=12)
    normal = _wall(first_not_solid=12)
    normal[4, 6, 3] = 0
    with pytest.raises(SourceError, match="not fully opaque"):
        crop_body(diffuse, normal)


def test_crop_body_rejects_texels_under_the_reflectance_floor():
    # The same guard `bake_rock` and `bake_rock_top` carry: an albedo shorter than the floor
    # is replaced by flat grey by the GI multiply, so a patch of cliff stops being cliff.
    dark = _wall()
    dark[..., :3] = 2
    with pytest.raises(SourceError, match="reflectance floor"):
        crop_body(dark, _wall())


def test_the_body_output_names_are_hyphenated():
    # `_normal` with an UNDERSCORE trips the engine's loadAll auto-loader into RGBA8 with ten
    # mip levels regardless of the declaration - and mip generation across a 790-wide corner
    # of a 2048 layer averages in texels nothing ever wrote.
    for name in build_backdrop.ROCK_BODY_OUT.values():
        assert "_normal" not in name
        assert name.startswith("rock-body-")
