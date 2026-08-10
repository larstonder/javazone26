import numpy as np
import pytest

from backdrop.reflectance import (
    GI_REFLECTANCE_FLOOR, LUMA, WALL_COLOR_SRGB, lift, linear_length, luminance,
    solve_gain,
)
from spritesheet.colour import srgb_to_linear


def test_linear_length_is_the_shaders_own_test():
    # texture_multiply_blend.frag: `if (length(c0.rgb) < minReflectance)`.
    assert linear_length(np.array([0.02, 0.0, 0.0])) == pytest.approx(0.02)
    assert linear_length(np.array([0.02, 0.02, 0.02])) == pytest.approx(0.02 * np.sqrt(3))


def test_an_ambient_longer_than_the_floor_lifts_every_texel_over_it():
    # The proof the bake relies on: length(ambient + v) >= length(ambient) for any v in
    # the positive octant, so ONE inequality on the ambient covers all 1.08M texels.
    ambient = 0.5 * srgb_to_linear(np.array(WALL_COLOR_SRGB))
    assert linear_length(ambient) > GI_REFLECTANCE_FLOOR

    rng = np.random.default_rng(7)
    dark = rng.random((5000, 3)) * 0.004      # darker than the rock's darkest texel
    assert (linear_length(dark) < GI_REFLECTANCE_FLOOR).all()
    assert (linear_length(lift(dark, ambient, 1.2)) >= linear_length(ambient)).all()


def test_a_pure_gain_cannot_do_what_the_ambient_does():
    # The rejected alternative, stated as a test so it stays rejected: the gain that
    # holds the tuned wall luminance leaves the darkest texels under the floor.
    wall_linear = srgb_to_linear(np.array(WALL_COLOR_SRGB))
    darkest = np.array([0.0020, 0.0018, 0.0017])          # the rock's floor, measured
    gain = solve_gain(0.0165, 0.0, float(luminance(wall_linear)))
    assert linear_length(lift(darkest, np.zeros(3), gain)) < GI_REFLECTANCE_FLOOR


def test_solve_gain_puts_the_mean_luminance_on_the_target():
    ambient = 0.5 * srgb_to_linear(np.array(WALL_COLOR_SRGB))
    target = float(luminance(srgb_to_linear(np.array(WALL_COLOR_SRGB))))

    rng = np.random.default_rng(11)
    source = rng.random((4000, 3)) * 0.05
    gain = solve_gain(float(luminance(source).mean()), float(luminance(ambient)), target)
    assert luminance(lift(source, ambient, gain)).mean() == pytest.approx(target, rel=1e-9)


def test_solve_gain_never_inverts_the_rock():
    # An ambient already brighter than the target must clamp to zero, not go negative.
    assert solve_gain(0.02, 0.10, 0.034) == 0.0


def test_solve_gain_rejects_a_source_with_no_luminance():
    with pytest.raises(ValueError):
        solve_gain(0.0, 0.017, 0.034)


def test_luminance_uses_rec_709_weights_and_they_sum_to_one():
    assert LUMA.sum() == pytest.approx(1.0)
    assert luminance(np.array([1.0, 1.0, 1.0])) == pytest.approx(1.0)
    # Green dominates - swapping the weights would change the solved gain silently.
    assert LUMA[1] > LUMA[0] > LUMA[2]
