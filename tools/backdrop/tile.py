"""
Turning the rock face into a tile that actually wraps.

THE SOURCE ART COMES IN TWO KINDS AND THE SCRIPT MUST NOT ASSUME EITHER. `choose_period`
is the one entry point; it decides which kind it has been handed.

ALREADY A TILE. The rock delivered on 2026-08-12 wraps at its full 1000 rows: row 999
against row 0 differs by 0.72/255 mean, against an interior row-to-row difference of
1.08/255 - i.e. the join is a SMALLER step than the average adjacency inside the image,
so it is simply one more adjacency. Nothing to crop and nothing to blend; the period is
the height and `wrap_blend` is the identity.

ONE PERIOD PLUS AN OVERLAP. The art this replaced was 300x1000 and did NOT wrap: row 999
against row 0 differed by 16.1/255 against the same ~1.2/255 interior, so butt-joining it
repeated a hard horizontal line every tile. Searching every period 820..999 for the offset
that best explains the image against itself (`find_wrap_period`) picked 889 by a wide
margin - mean |a[:111] - a[889:]| was 1.17/255, i.e. the last 111 rows were a re-render of
the first 111 to within the render's own noise, while the next best candidate (888) was
2.09 and the median candidate ~14. The artist had rendered 1.125 periods and left us the
overlap to blend with.

`wrap_blend` uses it: the output's first B rows are cross-faded from src[P+y] to
src[y]. At y=0 the output IS src[P], whose natural predecessor is src[P-1] - which is
the output's own last row. The wrap seam therefore becomes an interior adjacency by
construction rather than by luck, and because the two blended images are the same
content to within 1.17/255 the cross-fade ghosts nothing.

## WHY THE SEARCH CANNOT BE TRUSTED TO NOTICE AN ALREADY-TILING SOURCE BY ITSELF

`find_wrap_period` scores a period by the mean |difference| over the overlap it implies,
which is deliberately a MEAN so that a long overlap is not penalised for having more rows
to disagree over. That defence does not run the other way. On a source that already tiles
there is no correct answer in 820..999 at all, and the score then falls off monotonically
towards the top of the range purely because a shorter overlap averages fewer genuinely
different rows: measured on the new art, 999 scored 0.719, 998 0.996, 997 1.268, ... with
NO local minimum anywhere. The search duly returned 999 - a single overlap row - and
`wrap_blend` at band 1 sets out[0] = src[999], so the "tile" shipped as rows
999, 1, 2, ... 998 with row 0 dropped altogether. A one-row skip is not visible in a
capture; it is exactly the class of silent wrongness this module exists to prevent.

So the full-height case is tested FIRST and by a different measurement - the finished
seam against the interior adjacency, which has a real scale to it - and the search is
only consulted once that has been ruled out. A genuine period announces itself as a local
minimum against its neighbours; the absence of one is not evidence for the range's end.
"""
import numpy as np

# Where to look for the period. Below ~820 the overlap is long enough that the search
# starts preferring short periods for trivial reasons (fewer rows to disagree over);
# 999 is the last offset with any overlap at all.
SEARCH_LO = 820
SEARCH_HI = 999


def find_wrap_period(a: np.ndarray, lo: int = SEARCH_LO, hi: int = SEARCH_HI) -> int:
    """
    The row offset P for which `a[:H-P]` best matches `a[P:]`.

    Mean absolute difference, not sum: a longer overlap must not be penalised for
    having more rows to disagree over, and a shorter one must not win by having
    almost none.
    """
    a = np.asarray(a, dtype=np.float64)
    height = a.shape[0]
    if hi >= height:
        hi = height - 1
    if lo < 1 or lo > hi:
        raise ValueError(f"bad period search range {lo}..{hi} for {height} rows")
    scores = [(float(np.abs(a[: height - p] - a[p:]).mean()), p) for p in range(lo, hi + 1)]
    return min(scores)[1]


def choose_period(a: np.ndarray, max_seam_ratio: float, lo: int = SEARCH_LO, hi: int = SEARCH_HI) -> int:
    """
    The period to build the tile at: the full height when the source already wraps there,
    otherwise the best overlap offset in `lo..hi`.

    The full-height test is the SAME measurement the bake validates its finished tile with
    - the wrap seam against the interior adjacency, under `max_seam_ratio`. That is
    deliberate. A source that passes it is already a tile by the only definition the bake
    has; cropping and blending it could then only make it worse, and on the 2026-08-12 art
    it did (see the module doc). It also means the two are impossible to drift apart: a
    stricter acceptance threshold automatically becomes a stricter detection threshold.

    In the full-height branch the bake's later check is therefore a tautology - it re-runs
    this exact comparison on an unchanged array. It still earns its place, because it is
    the only thing checking the SEARCH branch, where the array really did change.
    """
    a = np.asarray(a, dtype=np.float64)
    if seam_error(a) <= max_seam_ratio * interior_error(a):
        return a.shape[0]
    return find_wrap_period(a, lo, hi)


def overlap_error(a: np.ndarray, period: int) -> float:
    """Mean |difference| over the overlap the blend is about to average together."""
    a = np.asarray(a, dtype=np.float64)
    return float(np.abs(a[: a.shape[0] - period] - a[period:]).mean())


def wrap_blend(a: np.ndarray, period: int) -> np.ndarray:
    """
    Crop to `period` rows, cross-fading the head with the overlap so the tile wraps.

    out[y] = (1-w)*src[y] + w*src[y+P]  with  w = 1 - y/B  over the first B = H-P rows,
    and out[y] = src[y] after that. w(0) = 1 makes out[0] exactly src[P]; out[P-1] is
    src[P-1] untouched; and src[P-1] -> src[P] is an interior step of the source.

    `period == height` is the ALREADY-A-TILE case and is the identity: there is no overlap
    to fade and, crucially, nothing to crop. It used to raise. Returning `a` unchanged is
    what lets `choose_period` express "this source needs no work" in the same units as
    every other answer, instead of the caller having to special-case it.
    """
    a = np.asarray(a, dtype=np.float64)
    height = a.shape[0]
    if not 1 <= period <= height:
        raise ValueError(f"period {period} outside 1..{height}")
    if period == height:
        return a.copy()
    band = height - period
    out = a[:period].copy()
    w = 1.0 - np.arange(band, dtype=np.float64) / band
    shape = (band,) + (1,) * (a.ndim - 1)
    out[:band] = (1.0 - w.reshape(shape)) * a[:band] + w.reshape(shape) * a[period:]
    return out


def seam_error(a: np.ndarray) -> float:
    """Mean |difference| across the wrap join of a finished tile: last row vs first."""
    a = np.asarray(a, dtype=np.float64)
    return float(np.abs(a[-1] - a[0]).mean())


def interior_error(a: np.ndarray) -> float:
    """Mean |difference| between adjacent interior rows - what a seam must look like."""
    a = np.asarray(a, dtype=np.float64)
    return float(np.abs(a[1:] - a[:-1]).mean())
