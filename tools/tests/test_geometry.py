import math
import pytest
from spritesheet.geometry import (
    CONTENT_ASPECT, BUCKETS, frame_width, bucket_for, choose_grid,
)


def test_frame_width_is_even_and_covers_the_aspect():
    # 384 * 0.323560 = 124.25 -> ceil 125 -> even 126
    assert frame_width(384) == 126
    assert frame_width(256) == 84
    assert frame_width(512) == 166


def test_frame_width_never_crops_the_content():
    for h in range(64, 1025):
        assert frame_width(h) >= h * CONTENT_ASPECT
        assert frame_width(h) % 2 == 0


def test_bucket_for_rounds_up_to_the_next_bucket():
    assert bucket_for(1) == 128
    assert bucket_for(128) == 128
    assert bucket_for(129) == 256
    assert bucket_for(1764) == 2048
    assert bucket_for(2049) == 4096


def test_bucket_for_rejects_oversized():
    with pytest.raises(ValueError):
        bucket_for(BUCKETS[-1] + 1)


def test_choose_grid_picks_14x3_for_the_real_bake():
    # The spec's headline result: 14x3 beats 11x4 on area at the same bucket,
    # and beats the naive 7x6 by a whole bucket (2048 vs 4096).
    g = choose_grid(41, 126, 384)
    assert (g.cols, g.rows) == (14, 3)
    assert (g.sheet_w, g.sheet_h) == (1764, 1152)
    assert g.bucket == 2048
    assert g.unused_cells == 1


def test_choose_grid_prefers_a_smaller_bucket_over_an_equal_area_layout():
    # 7x6 and 14x3 both hold 42 cells and have IDENTICAL area (2_032_128) - same
    # cell count, same cell size. But 7x6's 2304 px edge lands in the 4096 bucket
    # while 14x3's 1764 fits 2048. Only the bucket term separates them, so this
    # fails if the ranking key is ever reordered to (area, bucket).
    assert (7 * 126) * (6 * 384) == (14 * 126) * (3 * 384)
    assert bucket_for(max(7 * 126, 6 * 384)) == 4096
    assert bucket_for(max(14 * 126, 3 * 384)) == 2048
    g = choose_grid(41, 126, 384)
    assert (g.cols, g.rows) == (14, 3)


def test_choose_grid_always_has_enough_cells():
    for count in (1, 7, 41, 42, 100):
        g = choose_grid(count, 126, 384)
        assert g.cols * g.rows >= count
        assert g.unused_cells == g.cols * g.rows - count


def test_choose_grid_rejects_impossible_counts():
    with pytest.raises(ValueError):
        choose_grid(100_000, 126, 384)
