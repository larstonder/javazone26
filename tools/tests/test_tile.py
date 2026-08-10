import numpy as np
import pytest

from backdrop.tile import (
    find_wrap_period, interior_error, overlap_error, seam_error, wrap_blend,
)

PERIOD = 40
BAND = 7


def periodic(period=PERIOD, band=BAND, width=3, slip=0.0):
    """
    A synthetic stand-in for the delivered art: one period of a triangle wave followed
    by a `band`-row re-render of its head, so the file is 1 + band/period periods long.

    A TRIANGLE and not a sine, because every row-to-row step of a triangle has the same
    magnitude. That makes "the seam is an interior step" an equality a test can assert
    rather than an inequality that a sine's varying slope would let through.

    `slip` makes the re-render inexact, the way the real one is - the rock's overlap
    matches its head to 1.17/255 rather than exactly. With slip = 0 the blend is a
    no-op, so the tests that have to distinguish a correct cross-fade from a reversed
    one pass a non-zero slip.
    """
    y = np.arange(period + band)
    phase = (y % period) / period
    signal = 2.0 * np.abs(2.0 * (phase - np.floor(phase + 0.5)))
    signal = signal + np.where(y >= period, slip, 0.0)
    return signal[:, None] * np.ones((1, width))


def test_find_wrap_period_recovers_the_offset_the_overlap_was_built_with():
    assert find_wrap_period(periodic(), lo=20, hi=PERIOD + BAND - 1) == PERIOD


def test_find_wrap_period_prefers_the_offset_that_actually_explains_the_image():
    # The true period scores near zero; every neighbouring offset scores far worse, so
    # the search is discriminating rather than merely returning something.
    a = periodic()
    assert overlap_error(a, PERIOD) < 1e-12
    assert min(overlap_error(a, p) for p in (38, 39, 41, 42)) > 0.05


def test_find_wrap_period_survives_an_inexact_repeat():
    assert find_wrap_period(periodic(slip=0.002), lo=20, hi=PERIOD + BAND - 1) == PERIOD


def test_taking_the_file_as_delivered_is_the_hard_join_this_exists_to_remove():
    # The naive reading - "it is 47 rows, so tile it every 47 rows" - joins row 46,
    # which is a re-render of row 6, straight onto row 0.
    a = periodic()
    assert seam_error(a) > 5 * interior_error(a)


def test_wrap_blend_makes_the_seam_an_interior_step_of_the_source():
    a = periodic(slip=0.002)
    blended = wrap_blend(a, PERIOD)
    assert blended.shape == (PERIOD, 3)
    assert seam_error(blended) == pytest.approx(float(np.abs(a[PERIOD] - a[PERIOD - 1]).mean()))
    assert seam_error(blended) <= interior_error(blended)


def test_wrap_blend_starts_at_the_overlaps_first_row_so_the_join_is_a_real_successor():
    # out[0] == src[period], whose predecessor src[period-1] is out's own last row. A
    # cross-fade running the other way would leave out[0] == src[0] and put the file's
    # original hard join straight back.
    a = periodic(slip=0.002)
    blended = wrap_blend(a, PERIOD)
    assert np.allclose(blended[0], a[PERIOD])
    assert not np.allclose(blended[0], a[0])
    assert np.allclose(blended[-1], a[PERIOD - 1])


def test_wrap_blend_fades_monotonically_out_of_the_overlap():
    a = periodic(slip=0.002)
    blended = wrap_blend(a, PERIOD)
    # The overlap sits `slip` above the head, scaled by the fade weight, so the
    # excess over the untouched source must fall to zero across the band and stay there.
    excess = (blended - a[:PERIOD])[:, 0]
    assert excess[0] == pytest.approx(0.002)
    assert np.all(np.diff(excess[:BAND]) < 0)
    assert np.allclose(excess[BAND:], 0.0)


def test_wrap_blend_leaves_the_tail_of_the_tile_untouched():
    a = periodic(slip=0.002)
    assert np.allclose(wrap_blend(a, PERIOD)[BAND:], a[BAND:PERIOD])


def test_wrap_blend_rejects_a_period_that_leaves_no_overlap():
    a = periodic()
    with pytest.raises(ValueError):
        wrap_blend(a, a.shape[0])
    with pytest.raises(ValueError):
        wrap_blend(a, 0)
