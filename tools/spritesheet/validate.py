"""
Gates that run before anything is written.

Every check here corresponds to a failure that would otherwise be silent, and the
tolerances are load-bearing rather than defensive padding - see ALPHA_TOLERANCE.
"""
import pathlib

import numpy as np

from .resample import mean_normal_length

# Pillow has no 16-bit RGBA path: it takes the HIGH BYTE of the 16-bit diffuse rather
# than rounding, so diffuse alpha differs from the normal's by ~6326 texels per
# frame, ALWAYS by exactly 1. Under correct rounding the two are pixel-identical
# (verified by hand-decoding the PNG). So +/-1 is the honest threshold; 0 would abort
# the bake on today's real data, blaming the artist for the loader's behaviour.
ALPHA_TOLERANCE = 1

# A correctly sRGB-decoded normal map has mean |v| within 1% of unit. The raw decode
# measures 1.170 on these files, so this gate has ~17x of margin over the bug it is
# there to catch.
UNIT_LENGTH_TOLERANCE = 0.01


class SourceError(Exception):
    """A source-art problem that must stop the bake."""


def list_frames(directory: pathlib.Path) -> list:
    """Sorted *.png only - assets/sprites/diffuse/.DS_Store already exists."""
    return sorted(directory.glob("*.png"))


def check_pairing(diffuse: list, normals: list) -> None:
    if len(diffuse) != len(normals):
        raise SourceError(
            f"frame count differs: {len(diffuse)} diffuse vs {len(normals)} normal"
        )
    for d, n in zip(diffuse, normals):
        if str(d) != str(n):
            raise SourceError(f"frame names differ: {d} vs {n}")


def check_dimensions(sizes: list) -> None:
    unique = set(sizes)
    if len(unique) > 1:
        raise SourceError(f"source frames have mixed dimensions: {sorted(unique)}")


def check_alpha_agreement(diffuse_alpha, normal_alpha, index: int) -> None:
    delta = np.abs(diffuse_alpha.astype(np.int16) - normal_alpha.astype(np.int16))
    worst = int(delta.max())
    if worst > ALPHA_TOLERANCE:
        count = int((delta > ALPHA_TOLERANCE).sum())
        raise SourceError(
            f"frame {index}: diffuse and normal alpha disagree by up to {worst} "
            f"across {count} texels (tolerance {ALPHA_TOLERANCE}) - the two exports "
            f"have fallen out of sync"
        )


def check_normal_encoding(rgb, alpha, index: int) -> None:
    length = mean_normal_length(rgb, alpha)
    if not np.isfinite(length) or abs(length - 1.0) > UNIT_LENGTH_TOLERANCE:
        raise SourceError(
            f"frame {index}: decoded normals are not unit length (mean {length:.4f}, "
            f"expected 1.0 +/- {UNIT_LENGTH_TOLERANCE}). The source is probably no "
            f"longer sRGB-encoded - re-check resample.decode_normals"
        )


def union_bbox(alphas) -> tuple:
    """Inclusive (x0, y0, x1, y1) covering every non-zero alpha texel in `alphas`."""
    box = None
    for alpha in alphas:
        ys, xs = np.nonzero(alpha)
        if len(xs) == 0:
            continue
        here = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
        box = here if box is None else (
            min(box[0], here[0]), min(box[1], here[1]),
            max(box[2], here[2]), max(box[3], here[3]),
        )
    if box is None:
        raise SourceError("every source frame is fully transparent")
    return box
