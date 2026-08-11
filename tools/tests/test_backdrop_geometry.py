import pytest

from backdrop.geometry import (
    array_bytes, bucket_for, fit_height, fit_max_dim, fit_width, reuses_array,
    texels_per_metre, tiles_across,
)


def test_bucket_for_picks_the_smallest_bucket_that_fits():
    assert bucket_for(889) == 1024
    assert bucket_for(1024) == 1024
    assert bucket_for(1025) == 2048
    assert bucket_for(2048) == 2048
    assert bucket_for(3000) == 4096


def test_bucket_for_refuses_a_texture_larger_than_the_largest_bucket():
    with pytest.raises(ValueError):
        bucket_for(8193)


def test_reuses_array_is_strict_at_half_the_array_size():
    # The engine's own half-size test. A texture whose largest side is EXACTLY 1024
    # does not squat in the 2048 array - it allocates a 209.7 MB 1024 one of its own.
    assert not reuses_array(1024, 2048)
    assert reuses_array(1025, 2048)
    assert reuses_array(2048, 2048)
    assert not reuses_array(2049, 2048)


def test_the_shipped_sizes_all_land_in_the_array_the_diver_already_forced_open():
    for largest in (2048, 1600):          # rock height, silhouette max dimension
        assert reuses_array(largest, 2048)
    # ...and the sizes that were rejected do not.
    assert not reuses_array(889, 2048)    # the rock at its native height
    assert bucket_for(3000) == 4096       # the silhouettes at source resolution


def test_array_bytes_is_square_and_eager_not_width_times_height():
    assert array_bytes(2048) == 2048 * 2048 * 4 * 15
    assert array_bytes(4096) == 4096 * 4096 * 4 * 10
    # Crossing one bucket boundary costs more than twice the array below it.
    assert array_bytes(4096) > 2 * array_bytes(2048)


def test_fit_height_pins_the_height_and_follows_the_source_aspect():
    assert fit_height(300, 889, 2048) == (691, 2048)
    assert fit_height(889, 300, 2048) == (6069, 2048)   # aspect is not symmetric


def test_fit_max_dim_pins_the_largest_side_whichever_it_is():
    assert fit_max_dim(2000, 2000, 1600) == (1600, 1600)
    assert fit_max_dim(3000, 1000, 1600) == (1600, 533)
    assert fit_max_dim(1000, 3000, 1600) == (533, 1600)


def test_fit_rejects_degenerate_input():
    with pytest.raises(ValueError):
        fit_height(0, 889, 2048)
    with pytest.raises(ValueError):
        fit_max_dim(300, 889, 0)


def test_tiles_across_covers_the_span_with_whole_tiles():
    assert tiles_across(13.3, 13.49) == 1        # 16:9 wall, one tile, no join on screen
    assert tiles_across(30.0, 13.49) == 3        # 21:9 wall
    assert tiles_across(13.49, 13.49) == 1       # an exact fit is not two tiles
    assert tiles_across(0.0, 13.49) == 1         # 4:3: no wall, but never zero tiles
    assert tiles_across(-5.0, 13.49) == 1


def test_texels_per_metre_is_the_sampling_density_the_seam_argument_rests_on():
    assert texels_per_metre(2048, 40.0) == pytest.approx(51.2)
    with pytest.raises(ValueError):
        texels_per_metre(2048, 0.0)


def test_fit_width_hits_the_width_exactly_and_derives_the_height():
    # The cliff top's actual case: it must come out exactly as wide as the wall's baked
    # 691, because both are drawn TILE_WIDTH_METRES across and unequal widths would mean
    # unequal texel sizes on the two halves of one cliff.
    assert fit_width(300, 500, 691) == (691, 1152)
    assert fit_width(300, 889, 691) == (691, 2048)   # the wall, the other way round
    assert fit_width(4, 2, 10) == (10, 5)



def test_fit_width_refuses_a_degenerate_request():
    for args in ((0, 500, 691), (300, 0, 691), (300, 500, 0)):
        with pytest.raises(ValueError):
            fit_width(*args)
