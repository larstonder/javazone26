# Diver Sprite Sheet Bake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `tools/build_spritesheet.py`, which packs 41 diffuse and 41 normal-map diver frames into two Pulse Engine `SpriteSheet`-compatible grids.

**Architecture:** A small Python package `tools/spritesheet/` holding pure, engine-free logic — geometry and grid search, colour conversion, resampling, sheet assembly, source validation — plus a thin CLI at `tools/build_spritesheet.py` that does the file I/O and orchestration. This mirrors the repo's existing "pure-logic-extracted-for-testing" convention: everything worth asserting is a pure function over numpy arrays, testable with synthetic inputs and without the 183 MB of source art.

**Tech Stack:** Python 3, Pillow 12.3, numpy 2.5, pytest 7.4.3. No other imaging library is installed and none may be added — see Global Constraints.

## Global Constraints

Values below are copied verbatim from `docs/superpowers/specs/2026-08-07-diver-spritesheet-bake-design.md`. Every task's requirements implicitly include this section.

- **Dependencies:** Pillow and numpy **only**. `imageio`, `cv2`, `pypng`, `tifffile`, `skimage` and ImageMagick are all absent and must not be introduced.
- **Union alpha bbox:** x 175–803, y 42–1985 **inclusive** → **629 × 1944**, aspect **0.323560**.
- **Bbox is derived from the NORMAL maps only.** They are true 8-bit; Pillow truncates the 16-bit diffuse to its high byte, which zeroes very low alpha and yields a bbox one pixel narrow.
- **Diffuse/normal alpha gate tolerance is ±1**, never 0. Pillow's truncation produces ~6 326 legitimate ±1 differences per frame.
- **Frames used: 1–41.** Frame 42 is an exported duplicate. `--keep-last-frame` restores it.
- **Guard:** 2 px transparent inset per side, applied in destination space after scaling — content fits `(W-4, H-4)`.
- **Frame width:** `W = ceil(H × 0.323560)`, then rounded **up to even**. `H=384 → W=126`.
- **Grid is searched, not passed.** Smallest texture bucket, then smallest sheet area. `41 frames @ 126×384 → 14×3`, sheet `1764×1152`.
- **Texture buckets:** 128, 256, 512, 1024, 2048, 4096, 8192 — chosen by `max(width, height)`.
- **`maxMipLevels` must be `1`, never `0`.** `0` reaches `glTexStorage3D` as `levels=0` → `GL_INVALID_VALUE`, silently allocating nothing.
- **Constructor order:** `SpriteSheet(path, name, filter, wrapping, format, maxMipLevels, hCells, vCells)`. Field declaration order is **not** argument order.
- **Normals are sRGB-encoded.** Pipeline is `sRGB → linear → decode to [-1,1] → alpha-weighted resize → renormalize → encode`. Output is **linear `RGBA8`** — do not re-apply sRGB on the way out.
- **Diffuse output format is `SRGBA8`; normal output format is `RGBA8`.**
- **Zero-alpha footprints fall back to `(0, 0, 1)`** rather than dividing by ~0.
- **Output must be byte-reproducible:** pinned `compress_level`, `optimize=False`, no timestamp metadata.
- **Style:** 4 spaces, no wildcard imports. Comments explain *why*, and cite evidence — match the density of the surrounding repo.

---

## File Structure

| File | Responsibility |
|---|---|
| `tools/spritesheet/__init__.py` | Package marker; exports nothing. |
| `tools/spritesheet/geometry.py` | Frame width derivation, texture-bucket lookup, grid search. Pure math, no numpy, no I/O. |
| `tools/spritesheet/colour.py` | sRGB↔linear conversion and float-array resize helpers. Pure numpy. |
| `tools/spritesheet/resample.py` | `resample_diffuse` and `resample_normal`. Built on `colour`. Pure numpy. |
| `tools/spritesheet/assembly.py` | Places resampled cells into the grid with the guard inset. Pure numpy. |
| `tools/spritesheet/validate.py` | Source-set gates: counts, names, dimensions, alpha agreement, normal unit length. |
| `tools/spritesheet/qa.py` | QA bundle — animated preview, indexed contact sheet, normal visualisation. |
| `tools/build_spritesheet.py` | CLI, file I/O, union bbox, deterministic PNG write, Kotlin emission. |
| `tools/tests/test_geometry.py` | Grid search and frame width. |
| `tools/tests/test_colour.py` | Round-trip and known-value colour tests. |
| `tools/tests/test_resample.py` | Halo suppression, normal unit length, zero-alpha fallback. |
| `tools/tests/test_assembly.py` | Cell placement, guard, unused trailing cell. |
| `tools/tests/test_validate.py` | Each gate fires and each passes on good input. |

---

### Task 1: Geometry and grid search

**Files:**
- Create: `tools/spritesheet/__init__.py`
- Create: `tools/spritesheet/geometry.py`
- Test: `tools/tests/test_geometry.py`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `CONTENT_W = 629`, `CONTENT_H = 1944`, `CONTENT_ASPECT = 0.323560`
  - `BUCKETS: tuple[int, ...]`
  - `frame_width(frame_height: int, aspect: float = CONTENT_ASPECT) -> int`
  - `bucket_for(size: int) -> int`
  - `Grid` — a `NamedTuple` with fields `cols: int`, `rows: int`, `frame_w: int`, `frame_h: int`, `sheet_w: int`, `sheet_h: int`, `bucket: int`, `unused_cells: int`
  - `choose_grid(frame_count: int, frame_w: int, frame_h: int) -> Grid`

- [ ] **Step 1: Write the failing test**

Create `tools/tests/test_geometry.py`:

```python
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


def test_choose_grid_minimises_bucket_before_area():
    # 7x6 has a smaller area (2_032_128 vs 2_128_896 for 11x4) but lands in
    # the 4096 bucket. Bucket must dominate.
    g = choose_grid(41, 126, 384)
    assert g.bucket == 2048
    assert g.cols * g.frame_w == g.sheet_w


def test_choose_grid_always_has_enough_cells():
    for count in (1, 7, 41, 42, 100):
        g = choose_grid(count, 126, 384)
        assert g.cols * g.rows >= count
        assert g.unused_cells == g.cols * g.rows - count


def test_choose_grid_rejects_impossible_counts():
    with pytest.raises(ValueError):
        choose_grid(100_000, 126, 384)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd tools && python3 -m pytest tests/test_geometry.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'spritesheet'`

- [ ] **Step 3: Write minimal implementation**

Create `tools/spritesheet/__init__.py` as an empty file.

Create `tools/spritesheet/geometry.py`:

```python
"""
Frame sizing and grid selection for the diver sprite sheet bake.

Pure integer maths, no numpy and no I/O, so every number the bake depends on can be
asserted without touching the 183 MB of source art.
"""
import math
from typing import NamedTuple

# Measured over all 84 source files, alpha > 0, with the bbox taken from the NORMAL
# maps: they are true 8-bit, while Pillow truncates the 16-bit diffuse to its high
# byte and zeroes very low alpha, which yields a bbox one pixel narrow (628 vs 629).
CONTENT_W = 629
CONTENT_H = 1944
CONTENT_ASPECT = CONTENT_W / CONTENT_H  # 0.323560

# TextureBank.DEFAULT_CAPACITIES sizes, from the engine bytecode. TextureArray.init
# allocates glTexStorage3D(..., textureSize, textureSize, maxCapacity) - a SQUARE
# array with every layer eager - and the bucket is picked by max(width, height).
# So sheet cost is a step function of the larger dimension, not width*height*4.
BUCKETS = (128, 256, 512, 1024, 2048, 4096, 8192)


class Grid(NamedTuple):
    cols: int
    rows: int
    frame_w: int
    frame_h: int
    sheet_w: int
    sheet_h: int
    bucket: int
    unused_cells: int


def frame_width(frame_height: int, aspect: float = CONTENT_ASPECT) -> int:
    """
    Cell width for a given cell height, rounded UP so the content is never cropped,
    then up again to even. Even widths keep the 2 px guard symmetric.
    """
    w = math.ceil(frame_height * aspect)
    return w + (w % 2)


def bucket_for(size: int) -> int:
    """Smallest engine texture bucket that fits `size`."""
    for bucket in BUCKETS:
        if bucket >= size:
            return bucket
    raise ValueError(f"{size} px exceeds the largest texture bucket ({BUCKETS[-1]})")


def choose_grid(frame_count: int, frame_w: int, frame_h: int) -> Grid:
    """
    Search every column count for the cheapest layout.

    Ranked by (bucket, sheet area, cols): bucket first because crossing a boundary
    quadruples the eager square allocation regardless of how much of it is used, and
    area second because within a bucket the only remaining cost is the PNG on disk.
    Unused trailing cells are free - the game drives indices 0..frame_count-1 and
    never samples them - and allowing them is exactly what buys the smaller bucket.
    """
    best = None
    for cols in range(1, frame_count + 1):
        rows = math.ceil(frame_count / cols)
        sheet_w, sheet_h = cols * frame_w, rows * frame_h
        largest = max(sheet_w, sheet_h)
        if largest > BUCKETS[-1]:
            continue
        key = (bucket_for(largest), sheet_w * sheet_h, cols)
        if best is None or key < best[0]:
            best = (key, Grid(
                cols=cols,
                rows=rows,
                frame_w=frame_w,
                frame_h=frame_h,
                sheet_w=sheet_w,
                sheet_h=sheet_h,
                bucket=key[0],
                unused_cells=cols * rows - frame_count,
            ))
    if best is None:
        raise ValueError(
            f"no layout of {frame_count} {frame_w}x{frame_h} cells fits within "
            f"{BUCKETS[-1]}x{BUCKETS[-1]}"
        )
    return best[1]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd tools && python3 -m pytest tests/test_geometry.py -v`
Expected: PASS, 7 tests

- [ ] **Step 5: Add pytest config so `spritesheet` imports resolve**

Create `tools/pytest.ini`:

```ini
[pytest]
pythonpath = .
testpaths = tests
```

Run: `cd tools && python3 -m pytest -v`
Expected: PASS, 7 tests

- [ ] **Step 6: Commit**

```bash
git add tools/spritesheet/__init__.py tools/spritesheet/geometry.py tools/tests/test_geometry.py tools/pytest.ini
git commit -m "feat: bucket-aware grid search for the diver sprite sheet"
```

---

### Task 2: Colour primitives

**Files:**
- Create: `tools/spritesheet/colour.py`
- Test: `tools/tests/test_colour.py`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `srgb_to_linear(c: np.ndarray) -> np.ndarray` — input and output in [0,1]
  - `linear_to_srgb(c: np.ndarray) -> np.ndarray` — input and output in [0,1]
  - `resize_plane(plane: np.ndarray, size: tuple[int, int]) -> np.ndarray` — 2-D float32, `size` is `(width, height)`
  - `resize_channels(arr: np.ndarray, size: tuple[int, int]) -> np.ndarray` — 3-D float32 `HxWxC`
  - `to_u8(arr: np.ndarray) -> np.ndarray` — clip to [0,1] and round to uint8

- [ ] **Step 1: Write the failing test**

Create `tools/tests/test_colour.py`:

```python
import numpy as np
from spritesheet.colour import (
    srgb_to_linear, linear_to_srgb, resize_plane, resize_channels, to_u8,
)


def test_srgb_linear_round_trips():
    c = np.linspace(0.0, 1.0, 256, dtype=np.float64)
    assert np.allclose(linear_to_srgb(srgb_to_linear(c)), c, atol=1e-6)


def test_srgb_to_linear_known_values():
    # Endpoints are exact; mid-grey 0.5 sRGB is the standard 0.2140 linear.
    assert srgb_to_linear(np.array([0.0]))[0] == 0.0
    assert np.isclose(srgb_to_linear(np.array([1.0]))[0], 1.0)
    assert np.isclose(srgb_to_linear(np.array([0.5]))[0], 0.21404, atol=1e-4)


def test_srgb_to_linear_uses_the_linear_segment_near_zero():
    # Below 0.04045 the transfer function is a straight line, not a power curve.
    c = np.array([0.02])
    assert np.isclose(srgb_to_linear(c)[0], 0.02 / 12.92)


def test_resize_plane_preserves_a_constant():
    plane = np.full((100, 50), 0.25, dtype=np.float32)
    out = resize_plane(plane, (10, 20))
    assert out.shape == (20, 10)
    assert np.allclose(out, 0.25, atol=1e-5)


def test_resize_channels_keeps_channel_count_and_order():
    arr = np.zeros((8, 4, 3), dtype=np.float32)
    arr[..., 0] = 1.0
    out = resize_channels(arr, (2, 4))
    assert out.shape == (4, 2, 3)
    assert np.allclose(out[..., 0], 1.0, atol=1e-5)
    assert np.allclose(out[..., 1], 0.0, atol=1e-5)


def test_to_u8_rounds_rather_than_truncates():
    # 0.5/255 must land on 1, not 0 - truncation here is the same class of bug as
    # Pillow's 16-bit high-byte read.
    assert to_u8(np.array([0.9 / 255.0]))[0] == 1
    assert to_u8(np.array([1.0]))[0] == 255
    assert to_u8(np.array([-0.5]))[0] == 0
    assert to_u8(np.array([2.0]))[0] == 255
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd tools && python3 -m pytest tests/test_colour.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'spritesheet.colour'`

- [ ] **Step 3: Write minimal implementation**

Create `tools/spritesheet/colour.py`:

```python
"""
Colour transfer functions and float-array resizing.

Resampling happens in LINEAR light, never in sRGB space: averaging gamma-encoded
values darkens the result, and the diver downscales 5.16x, so the error is not
subtle. Both source sets carry sRGB + gAMA chunks, so both must be linearized
before any arithmetic touches them.
"""
import numpy as np
from PIL import Image


def srgb_to_linear(c: np.ndarray) -> np.ndarray:
    """IEC 61966-2-1 sRGB -> linear. Input and output in [0,1]."""
    c = np.asarray(c, dtype=np.float64)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c: np.ndarray) -> np.ndarray:
    """Inverse of `srgb_to_linear`. Input and output in [0,1]."""
    c = np.clip(np.asarray(c, dtype=np.float64), 0.0, None)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)


def resize_plane(plane: np.ndarray, size: tuple) -> np.ndarray:
    """
    Resize one 2-D float plane. `size` is (width, height), matching Pillow.

    LANCZOS because the bake is a large downscale and box filtering would alias the
    fins; it rings slightly, so every caller clips afterwards.
    """
    img = Image.fromarray(np.asarray(plane, dtype=np.float32), mode="F")
    return np.asarray(img.resize(size, Image.LANCZOS), dtype=np.float32)


def resize_channels(arr: np.ndarray, size: tuple) -> np.ndarray:
    """Resize an HxWxC float array plane by plane."""
    arr = np.asarray(arr, dtype=np.float32)
    return np.stack(
        [resize_plane(arr[..., i], size) for i in range(arr.shape[-1])],
        axis=-1,
    )


def to_u8(arr: np.ndarray) -> np.ndarray:
    """Clip to [0,1] and round-half-up to uint8. Rounds, never truncates."""
    return np.clip(np.asarray(arr, dtype=np.float64) * 255.0 + 0.5, 0, 255).astype(np.uint8)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd tools && python3 -m pytest tests/test_colour.py -v`
Expected: PASS, 6 tests

- [ ] **Step 5: Commit**

```bash
git add tools/spritesheet/colour.py tools/tests/test_colour.py
git commit -m "feat: linear-light colour conversion and resize helpers"
```

---

### Task 3: Diffuse and normal resampling

**Files:**
- Create: `tools/spritesheet/resample.py`
- Test: `tools/tests/test_resample.py`

**Interfaces:**
- Consumes: `spritesheet.colour.{srgb_to_linear, linear_to_srgb, resize_plane, resize_channels, to_u8}`
- Produces:
  - `resample_diffuse(rgb: np.ndarray, alpha: np.ndarray, size: tuple) -> tuple[np.ndarray, np.ndarray]` — takes uint8 `HxWx3` and uint8 `HxW`, returns `(rgb_u8, alpha_u8)` at `size`
  - `resample_normal(rgb: np.ndarray, alpha: np.ndarray, size: tuple) -> tuple[np.ndarray, np.ndarray]` — same shapes; RGB out is **linear-encoded**, not sRGB
  - `decode_normals(rgb: np.ndarray) -> np.ndarray` — uint8 `HxWx3` sRGB → float `HxWx3` in [-1,1]
  - `mean_normal_length(rgb: np.ndarray, alpha: np.ndarray) -> float` — for the §8 unit-length gate

- [ ] **Step 1: Write the failing test**

Create `tools/tests/test_resample.py`:

```python
import numpy as np
from spritesheet.resample import (
    resample_diffuse, resample_normal, decode_normals, mean_normal_length,
)
from spritesheet.colour import srgb_to_linear


def _flat_normal_field(h, w):
    """An sRGB-ENCODED flat +Z normal map, i.e. what the real files contain."""
    linear = np.zeros((h, w, 3), dtype=np.float64)
    linear[..., 0] = 0.5   # x = 0
    linear[..., 1] = 0.5   # y = 0
    linear[..., 2] = 1.0   # z = 1
    # Encode to sRGB so decode_normals has to undo it.
    from spritesheet.colour import linear_to_srgb
    return np.clip(linear_to_srgb(linear) * 255 + 0.5, 0, 255).astype(np.uint8)


def test_decode_normals_linearizes_before_decoding():
    rgb = _flat_normal_field(4, 4)
    v = decode_normals(rgb)
    assert np.allclose(v[..., 0], 0.0, atol=0.01)
    assert np.allclose(v[..., 1], 0.0, atol=0.01)
    assert np.allclose(v[..., 2], 1.0, atol=0.01)


def test_decode_without_linearizing_would_be_wrong():
    # Guards the exact bug the review caught: the raw decode gives a large
    # positive bias where the correct one gives ~0.
    rgb = _flat_normal_field(4, 4)
    raw = rgb / 255.0 * 2.0 - 1.0
    assert raw[..., 0].mean() > 0.3          # wrong, and obviously so
    assert abs(decode_normals(rgb)[..., 0].mean()) < 0.01


def test_mean_normal_length_is_unit_for_a_valid_map():
    rgb = _flat_normal_field(8, 8)
    alpha = np.full((8, 8), 255, dtype=np.uint8)
    assert abs(mean_normal_length(rgb, alpha) - 1.0) < 0.01


def test_mean_normal_length_ignores_transparent_texels():
    rgb = _flat_normal_field(8, 8)
    rgb[4:, :, :] = 0                        # garbage, but fully transparent
    alpha = np.zeros((8, 8), dtype=np.uint8)
    alpha[:4, :] = 255
    assert abs(mean_normal_length(rgb, alpha) - 1.0) < 0.01


def test_resampled_normals_stay_unit_length():
    rgb = _flat_normal_field(64, 64)
    alpha = np.full((64, 64), 255, dtype=np.uint8)
    out_rgb, _ = resample_normal(rgb, alpha, (16, 16))
    v = out_rgb / 255.0 * 2.0 - 1.0          # output is LINEAR, decode directly
    assert np.allclose(np.linalg.norm(v, axis=-1), 1.0, atol=0.02)


def test_normal_zero_alpha_falls_back_to_flat_and_never_nans():
    rgb = _flat_normal_field(16, 16)
    alpha = np.zeros((16, 16), dtype=np.uint8)
    out_rgb, out_a = resample_normal(rgb, alpha, (4, 4))
    assert np.isfinite(out_rgb).all()
    v = out_rgb / 255.0 * 2.0 - 1.0
    assert np.allclose(v[..., 2], 1.0, atol=0.02)
    assert (out_a == 0).all()


def test_diffuse_does_not_halo_from_transparent_black():
    # A white opaque disc on transparent BLACK. Without premultiplication the
    # downscaled edge picks up the black and darkens - this is the halo bug.
    h = w = 64
    yy, xx = np.mgrid[0:h, 0:w]
    inside = (yy - 32) ** 2 + (xx - 32) ** 2 < 20 ** 2
    rgb = np.zeros((h, w, 3), dtype=np.uint8)
    rgb[inside] = 255
    alpha = np.where(inside, 255, 0).astype(np.uint8)

    out_rgb, out_a = resample_diffuse(rgb, alpha, (16, 16))
    lit = out_a > 32
    assert lit.any()
    # Every partially-covered texel must still be white, not grey.
    assert out_rgb[lit].min() > 240


def test_diffuse_preserves_a_flat_opaque_colour():
    rgb = np.full((32, 32, 3), 128, dtype=np.uint8)
    alpha = np.full((32, 32), 255, dtype=np.uint8)
    out_rgb, out_a = resample_diffuse(rgb, alpha, (8, 8))
    assert np.abs(out_rgb.astype(int) - 128).max() <= 1
    assert (out_a == 255).all()


def test_resample_returns_requested_size():
    rgb = np.zeros((100, 40, 3), dtype=np.uint8)
    alpha = np.full((100, 40), 255, dtype=np.uint8)
    for fn in (resample_diffuse, resample_normal):
        out_rgb, out_a = fn(rgb, alpha, (10, 25))
        assert out_rgb.shape == (25, 10, 3)
        assert out_a.shape == (25, 10)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd tools && python3 -m pytest tests/test_resample.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'spritesheet.resample'`

- [ ] **Step 3: Write minimal implementation**

Create `tools/spritesheet/resample.py`:

```python
"""
The two resampling pipelines, and the only place in the bake where getting the
maths subtly wrong still produces a plausible-looking image.

Both source sets are sRGB-encoded (verified: sRGB intent-3 + gAMA 0.45455 chunks in
all 84 files). For the diffuse that is unremarkable. For the NORMALS it is the whole
problem: decoding them raw as rgb/255*2-1 gives a mean X of +0.400 on a bilaterally
symmetric character and leaves 97% of vectors off unit length. Linearizing first
gives mean X +0.001 and |v| 0.998. Renormalizing does not rescue a raw decode - it
projects the wrong direction onto the unit sphere and hides the error.
"""
import numpy as np

from .colour import (
    srgb_to_linear, linear_to_srgb, resize_plane, resize_channels, to_u8,
)

_EPS = 1e-6
_FLAT = np.array([0.0, 0.0, 1.0])


def decode_normals(rgb: np.ndarray) -> np.ndarray:
    """sRGB-encoded uint8 normal map -> float vectors in [-1,1]."""
    return srgb_to_linear(np.asarray(rgb, dtype=np.float64) / 255.0) * 2.0 - 1.0


def mean_normal_length(rgb: np.ndarray, alpha: np.ndarray) -> float:
    """
    Mean |v| over opaque texels. The discriminating test for the decode convention:
    a +Z-dominant mean is consistent with both the right and the wrong decode, but
    only the right one produces unit vectors.
    """
    mask = np.asarray(alpha) > 200
    if not mask.any():
        return float("nan")
    return float(np.linalg.norm(decode_normals(rgb)[mask], axis=-1).mean())


def _alpha_weighted_resize(values: np.ndarray, alpha: np.ndarray, size: tuple):
    """
    Premultiply by alpha, resize, then divide back out.

    Without this, fully transparent texels contribute their (undefined) values to
    every partially-covered output texel: a dark fringe on the diffuse, and garbage
    directions on the normals.
    """
    weighted = resize_channels(values * alpha[..., None], size)
    resized_alpha = np.clip(resize_plane(alpha.astype(np.float32), size), 0.0, 1.0)
    covered = resized_alpha > _EPS
    out = np.where(
        covered[..., None],
        weighted / np.maximum(resized_alpha, _EPS)[..., None],
        0.0,
    )
    return out, resized_alpha, covered


def resample_diffuse(rgb: np.ndarray, alpha: np.ndarray, size: tuple):
    """Downscale the albedo. Returns (rgb_u8, alpha_u8), sRGB-encoded."""
    linear = srgb_to_linear(np.asarray(rgb, dtype=np.float64) / 255.0)
    a = np.asarray(alpha, dtype=np.float64) / 255.0
    resized, resized_alpha, _ = _alpha_weighted_resize(linear, a, size)
    return to_u8(linear_to_srgb(np.clip(resized, 0.0, 1.0))), to_u8(resized_alpha)


def resample_normal(rgb: np.ndarray, alpha: np.ndarray, size: tuple):
    """
    Downscale the normal map. Returns (rgb_u8, alpha_u8).

    The RGB output is LINEAR-encoded, because the sheet is uploaded as RGBA8 rather
    than SRGBA8: the GPU hands the shader the stored bytes unchanged, so the stored
    value must be (v+1)/2 directly. Re-applying sRGB here would reintroduce exactly
    the bias this function exists to remove.
    """
    v = decode_normals(rgb)
    a = np.asarray(alpha, dtype=np.float64) / 255.0
    resized, resized_alpha, covered = _alpha_weighted_resize(v, a, size)

    # Averaging unit vectors shortens them; renormalizing restores the curvature
    # that the fins, mask and shoulders depend on.
    length = np.linalg.norm(resized, axis=-1, keepdims=True)
    unit = np.where(length > _EPS, resized / np.maximum(length, _EPS), _FLAT)

    # Where nothing was covered the direction is undefined and the divide above is
    # meaningless. These texels are never sampled, but a NaN would propagate.
    unit = np.where(covered[..., None], unit, _FLAT)

    return to_u8((unit + 1.0) * 0.5), to_u8(resized_alpha)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd tools && python3 -m pytest tests/test_resample.py -v`
Expected: PASS, 9 tests

- [ ] **Step 5: Commit**

```bash
git add tools/spritesheet/resample.py tools/tests/test_resample.py
git commit -m "feat: linear-light diffuse and sRGB-aware normal resampling"
```

---

### Task 4: Sheet assembly

**Files:**
- Create: `tools/spritesheet/assembly.py`
- Test: `tools/tests/test_assembly.py`

**Interfaces:**
- Consumes: `spritesheet.geometry.Grid`
- Produces:
  - `GUARD_PX = 2`
  - `content_box(grid: Grid) -> tuple[int, int]` — the `(width, height)` a frame's content is scaled into
  - `cell_origin(grid: Grid, index: int) -> tuple[int, int]` — top-left `(x, y)` of cell `index`, row-major
  - `assemble(grid: Grid, cells: list) -> np.ndarray` — `cells` are uint8 `HxWx4` RGBA content tiles sized `content_box`; returns the full `HxWx4` sheet

- [ ] **Step 1: Write the failing test**

Create `tools/tests/test_assembly.py`:

```python
import numpy as np
import pytest
from spritesheet.geometry import choose_grid
from spritesheet.assembly import GUARD_PX, content_box, cell_origin, assemble


def _grid():
    return choose_grid(41, 126, 384)


def _solid_cell(grid, value=255):
    w, h = content_box(grid)
    cell = np.zeros((h, w, 4), dtype=np.uint8)
    cell[..., :] = value
    return cell


def test_content_box_leaves_a_guard_on_every_side():
    grid = _grid()
    w, h = content_box(grid)
    assert w == grid.frame_w - 2 * GUARD_PX
    assert h == grid.frame_h - 2 * GUARD_PX


def test_cell_origin_is_row_major():
    grid = _grid()                       # 14 cols
    assert cell_origin(grid, 0) == (0, 0)
    assert cell_origin(grid, 1) == (126, 0)
    assert cell_origin(grid, 13) == (13 * 126, 0)
    assert cell_origin(grid, 14) == (0, 384)      # wraps to row 1
    assert cell_origin(grid, 40) == ((40 % 14) * 126, (40 // 14) * 384)


def test_cell_origin_rejects_out_of_range():
    grid = _grid()
    with pytest.raises(IndexError):
        cell_origin(grid, grid.cols * grid.rows)


def test_assemble_produces_the_full_sheet():
    grid = _grid()
    sheet = assemble(grid, [_solid_cell(grid) for _ in range(41)])
    assert sheet.shape == (grid.sheet_h, grid.sheet_w, 4)


def test_no_opaque_texel_sits_on_a_cell_boundary():
    # This is the guard's entire purpose: with LINEAR filtering the GPU reaches
    # +/-0.5 texel, so an opaque texel on a seam bleeds into the next frame.
    grid = _grid()
    sheet = assemble(grid, [_solid_cell(grid) for _ in range(41)])
    alpha = sheet[..., 3]
    for col in range(grid.cols):
        x = col * grid.frame_w
        assert alpha[:, x].max() == 0
        assert alpha[:, x + grid.frame_w - 1].max() == 0
    for row in range(grid.rows):
        y = row * grid.frame_h
        assert alpha[y, :].max() == 0
        assert alpha[y + grid.frame_h - 1, :].max() == 0


def test_unused_trailing_cells_are_fully_transparent():
    grid = _grid()
    sheet = assemble(grid, [_solid_cell(grid) for _ in range(41)])
    assert grid.unused_cells == 1
    x, y = cell_origin(grid, 41)
    tail = sheet[y:y + grid.frame_h, x:x + grid.frame_w]
    assert tail.max() == 0


def test_assemble_rejects_too_many_cells():
    grid = _grid()
    with pytest.raises(ValueError):
        assemble(grid, [_solid_cell(grid) for _ in range(grid.cols * grid.rows + 1)])


def test_assemble_rejects_wrongly_sized_cells():
    grid = _grid()
    bad = np.zeros((10, 10, 4), dtype=np.uint8)
    with pytest.raises(ValueError):
        assemble(grid, [bad])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd tools && python3 -m pytest tests/test_assembly.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'spritesheet.assembly'`

- [ ] **Step 3: Write minimal implementation**

Create `tools/spritesheet/assembly.py`:

```python
"""
Placing resampled frames into the uniform grid.

SpriteSheet subdivides UVs exactly evenly - uMin = u0 + x*(1/hCells)*(u1-u0), with no
half-texel inset anywhere in the engine - so the inset has to be baked into the
pixels. Content touches the union bbox on all four sides in the real art (frame 3's
normal reaches x=803, frame 24 the left edge, all 41 the top, frame 35 the bottom),
so without a guard the diver picks up a sliver of its neighbour under LINEAR
filtering.
"""
import numpy as np

from .geometry import Grid

# 2 px in DESTINATION space, applied after scaling. At the bake's 5.16x downscale a
# Lanczos kernel's support is sub-pixel here, and the GPU reaches +/-0.5 texel, so
# 2 px leaves a full clear texel of margin.
GUARD_PX = 2


def content_box(grid: Grid) -> tuple:
    """The (width, height) a frame's content is scaled into, inside its cell."""
    return (grid.frame_w - 2 * GUARD_PX, grid.frame_h - 2 * GUARD_PX)


def cell_origin(grid: Grid, index: int) -> tuple:
    """
    Top-left pixel of cell `index`, row-major.

    Row-major matches the engine: SpriteSheet.onUploaded computes
    x = index % horizontalCells and y = index / horizontalCells, so consecutive
    indices advance along x.
    """
    if not 0 <= index < grid.cols * grid.rows:
        raise IndexError(f"cell {index} outside a {grid.cols}x{grid.rows} grid")
    return ((index % grid.cols) * grid.frame_w, (index // grid.cols) * grid.frame_h)


def assemble(grid: Grid, cells: list) -> np.ndarray:
    """
    Composite content tiles into the full sheet.

    Cells beyond `len(cells)` are left fully transparent. They are never sampled -
    the game drives indices 0..frameCount-1 - and allowing them is what lets the
    grid search reach a smaller texture bucket.
    """
    capacity = grid.cols * grid.rows
    if len(cells) > capacity:
        raise ValueError(f"{len(cells)} cells will not fit in {capacity}")

    box_w, box_h = content_box(grid)
    sheet = np.zeros((grid.sheet_h, grid.sheet_w, 4), dtype=np.uint8)
    for index, cell in enumerate(cells):
        if cell.shape != (box_h, box_w, 4):
            raise ValueError(
                f"cell {index} is {cell.shape}, expected {(box_h, box_w, 4)}"
            )
        x, y = cell_origin(grid, index)
        sheet[y + GUARD_PX:y + GUARD_PX + box_h, x + GUARD_PX:x + GUARD_PX + box_w] = cell
    return sheet
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd tools && python3 -m pytest tests/test_assembly.py -v`
Expected: PASS, 8 tests

- [ ] **Step 5: Commit**

```bash
git add tools/spritesheet/assembly.py tools/tests/test_assembly.py
git commit -m "feat: guarded row-major sheet assembly"
```

---

### Task 5: Source loading and validation gates

**Files:**
- Create: `tools/spritesheet/validate.py`
- Test: `tools/tests/test_validate.py`

**Interfaces:**
- Consumes: `spritesheet.resample.mean_normal_length`
- Produces:
  - `ALPHA_TOLERANCE = 1`
  - `SourceError(Exception)`
  - `list_frames(directory: pathlib.Path) -> list[pathlib.Path]` — sorted, `*.png` only
  - `check_pairing(diffuse: list, normals: list) -> None`
  - `check_dimensions(sizes: list) -> None` — `sizes` is a list of `(w, h)`
  - `check_alpha_agreement(diffuse_alpha: np.ndarray, normal_alpha: np.ndarray, index: int) -> None`
  - `check_normal_encoding(rgb: np.ndarray, alpha: np.ndarray, index: int) -> None`
  - `union_bbox(alphas) -> tuple[int, int, int, int]` — inclusive `(x0, y0, x1, y1)`

- [ ] **Step 1: Write the failing test**

Create `tools/tests/test_validate.py`:

```python
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


def test_union_bbox_is_inclusive_and_spans_every_frame():
    a = np.zeros((10, 10), dtype=np.uint8)
    a[2:5, 3:6] = 255
    b = np.zeros((10, 10), dtype=np.uint8)
    b[6:8, 1:4] = 255
    assert union_bbox([a, b]) == (1, 2, 5, 7)


def test_union_bbox_rejects_an_empty_set():
    with pytest.raises(SourceError):
        union_bbox([np.zeros((4, 4), dtype=np.uint8)])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd tools && python3 -m pytest tests/test_validate.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'spritesheet.validate'`

- [ ] **Step 3: Write minimal implementation**

Create `tools/spritesheet/validate.py`:

```python
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
            f"frame {index}: decoded normals have mean length {length:.4f}, expected "
            f"1.0 +/- {UNIT_LENGTH_TOLERANCE}. The source is probably no longer "
            f"sRGB-encoded - re-check the decode in resample.decode_normals"
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd tools && python3 -m pytest tests/test_validate.py -v`
Expected: PASS, 12 tests

- [ ] **Step 5: Commit**

```bash
git add tools/spritesheet/validate.py tools/tests/test_validate.py
git commit -m "feat: source validation gates with evidence-based tolerances"
```

---

### Task 6: CLI, deterministic write, Kotlin emission

**Files:**
- Create: `tools/build_spritesheet.py`
- Modify: none

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: an executable CLI. No importable API other than `main(argv=None) -> int`.

- [ ] **Step 1: Write the script**

Create `tools/build_spritesheet.py`:

```python
#!/usr/bin/env python3
"""
Bake the diver's animation frames into two Pulse Engine sprite sheets.

    tools/build_spritesheet.py [--frame-height 384] [--keep-last-frame]

SOURCE ART PROVENANCE
    assets/sprites/diffuse/0001-0042.png   1000x2000 RGBA, 16-bit
    assets/sprites/normals/0001-0042.png   1000x2000 RGBA, 8-bit, sRGB-encoded

    assets/ is gitignored (183 MB, .gitignore:130) so a clean clone cannot re-bake
    without obtaining the frames separately. That is why the OUTPUT is committed.

    Frames 1-41 are used. Frame 42 is an exported duplicate of frame 1: silhouette
    centroid speed climbs to 5.284 px/frame going into it - the maximum of the whole
    loop - then collapses to 0.083. Pass --keep-last-frame to include it anyway.

Full rationale, including four defects that each failed silently in review:
docs/superpowers/specs/2026-08-07-diver-spritesheet-bake-design.md
"""
import argparse
import hashlib
import pathlib
import sys

import numpy as np
from PIL import Image, PngImagePlugin

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from spritesheet.assembly import assemble, content_box
from spritesheet.geometry import choose_grid, frame_width
from spritesheet.resample import resample_diffuse, resample_normal
from spritesheet.validate import (
    SourceError, check_alpha_agreement, check_dimensions, check_normal_encoding,
    check_pairing, list_frames, union_bbox,
)

REPO = pathlib.Path(__file__).resolve().parent.parent
DIFFUSE_DIR = REPO / "assets" / "sprites" / "diffuse"
NORMALS_DIR = REPO / "assets" / "sprites" / "normals"
OUT_DIR = REPO / "src" / "main" / "resources" / "sprites"
QA_DIR = REPO / "build" / "spritesheet-qa"

DEFAULT_FRAME_HEIGHT = 384
DROPPED_TAIL_FRAMES = 1


def load_rgba(path: pathlib.Path) -> np.ndarray:
    """
    Load as 8-bit RGBA.

    Pillow truncates the 16-bit diffuse to its high byte here. That is acceptable for
    COLOUR - a +/-1 error 5x above the output's resolution after downscaling - but is
    exactly why the union bbox is taken from the normals instead, and why the alpha
    gate tolerates +/-1. See validate.ALPHA_TOLERANCE.
    """
    return np.asarray(Image.open(path).convert("RGBA"))


def write_png(path: pathlib.Path, rgba: np.ndarray, meta: dict) -> None:
    """
    Write deterministically: same inputs must give a byte-identical file, because
    these are committed artifacts and a spurious 2 MB binary diff per re-bake is
    worse than useless.
    """
    info = PngImagePlugin.PngInfo()
    for key in sorted(meta):
        info.add_text(key, str(meta[key]))
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgba, mode="RGBA").save(
        path, format="PNG", optimize=False, compress_level=9, pnginfo=info,
    )


def bake(frame_height: int, keep_last: bool) -> int:
    if not DIFFUSE_DIR.is_dir() or not NORMALS_DIR.is_dir():
        print(
            f"error: source frames not present at {DIFFUSE_DIR.parent}.\n"
            f"       assets/ is gitignored - see this script's header for provenance.",
            file=sys.stderr,
        )
        return 2

    diffuse_paths = list_frames(DIFFUSE_DIR)
    normal_paths = list_frames(NORMALS_DIR)
    check_pairing([p.name for p in diffuse_paths], [p.name for p in normal_paths])

    if not keep_last and len(diffuse_paths) > DROPPED_TAIL_FRAMES:
        diffuse_paths = diffuse_paths[:-DROPPED_TAIL_FRAMES]
        normal_paths = normal_paths[:-DROPPED_TAIL_FRAMES]
        print(f"dropping the last frame as an exported duplicate "
              f"(--keep-last-frame to override)")

    diffuse = [load_rgba(p) for p in diffuse_paths]
    normals = [load_rgba(p) for p in normal_paths]
    check_dimensions([(a.shape[1], a.shape[0]) for a in diffuse + normals])

    for index, (d, n) in enumerate(zip(diffuse, normals)):
        check_alpha_agreement(d[..., 3], n[..., 3], index)
        check_normal_encoding(n[..., :3], n[..., 3], index)

    # Bbox from the NORMALS only - they are true 8-bit, so Pillow reads them exactly.
    x0, y0, x1, y1 = union_bbox([n[..., 3] for n in normals])
    content_w, content_h = x1 - x0 + 1, y1 - y0 + 1
    aspect = content_w / content_h

    frame_w = frame_width(frame_height, aspect)
    grid = choose_grid(len(diffuse), frame_w, frame_height)
    box = content_box(grid)

    print(f"content  {content_w}x{content_h}  (x {x0}-{x1}, y {y0}-{y1}, "
          f"aspect {aspect:.6f})")
    print(f"frame    {grid.frame_w}x{grid.frame_h}   content box {box[0]}x{box[1]}")
    print(f"grid     {grid.cols}x{grid.rows}  ({grid.unused_cells} unused)  "
          f"sheet {grid.sheet_w}x{grid.sheet_h}  bucket {grid.bucket}")

    diffuse_cells, normal_cells = [], []
    for d, n in zip(diffuse, normals):
        crop = (slice(y0, y1 + 1), slice(x0, x1 + 1))
        dr, da = resample_diffuse(d[crop][..., :3], d[crop][..., 3], box)
        nr, na = resample_normal(n[crop][..., :3], n[crop][..., 3], box)
        diffuse_cells.append(np.dstack([dr, da]))
        normal_cells.append(np.dstack([nr, na]))

    diffuse_sheet = assemble(grid, diffuse_cells)
    normal_sheet = assemble(grid, normal_cells)

    fingerprint = hashlib.sha256()
    for path in diffuse_paths + normal_paths:
        fingerprint.update(path.read_bytes())
    meta = {
        "ept:frame_count": len(diffuse),
        "ept:frame_height": frame_height,
        "ept:grid": f"{grid.cols}x{grid.rows}",
        "ept:source_sha256": fingerprint.hexdigest()[:16],
    }

    write_png(OUT_DIR / "diver-diffuse.png", diffuse_sheet, meta)
    write_png(OUT_DIR / "diver-normal.png", normal_sheet, meta)
    print(f"\nwrote {OUT_DIR / 'diver-diffuse.png'}")
    print(f"wrote {OUT_DIR / 'diver-normal.png'}")

    print(f"""
Kotlin - copy verbatim, the argument order is NOT the field order:

    SpriteSheet("/sprites/diver-diffuse.png", "diver_diffuse",
        TextureFilter.LINEAR, TextureWrapping.CLAMP_TO_EDGE, TextureFormat.SRGBA8,
        1, {grid.cols}, {grid.rows})
    SpriteSheet("/sprites/diver-normal.png", "diver_normal",
        TextureFilter.LINEAR, TextureWrapping.CLAMP_TO_EDGE, TextureFormat.RGBA8,
        1, {grid.cols}, {grid.rows})

    const val DIVER_FRAME_COUNT = {len(diffuse)}   // NOT {grid.cols}*{grid.rows} - \
the last {grid.unused_cells} cell(s) are unused

maxMipLevels is 1, never 0: TextureArray computes
min(maxMipLevels, floor(log2(size))+1) with no coerceAtLeast(1), and passes it to
glTexStorage3D as `levels`. levels=0 is GL_INVALID_VALUE - no storage allocated, no
error logged.""")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("--frame-height", type=int, default=DEFAULT_FRAME_HEIGHT)
    parser.add_argument("--keep-last-frame", action="store_true",
                        help="include the duplicate final frame")
    args = parser.parse_args(argv)
    try:
        return bake(args.frame_height, args.keep_last_frame)
    except SourceError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Make it executable and confirm the no-source path**

```bash
chmod +x tools/build_spritesheet.py
cd tools && python3 -m pytest -v
```

Expected: PASS, 42 tests (nothing regressed).

- [ ] **Step 3: Run the real bake**

Run: `python3 tools/build_spritesheet.py`

Expected output includes:
```
dropping the last frame as an exported duplicate (--keep-last-frame to override)
content  629x1944  (x 175-803, y 42-1985, aspect 0.323560)
frame    126x384   content box 122x380
grid     14x3  (1 unused)  sheet 1764x1152  bucket 2048
```

If the grid, sheet size or bbox differ from these, **stop** — the geometry has drifted from the spec and something in Tasks 1–5 is wrong.

- [ ] **Step 4: Verify determinism**

```bash
shasum -a 256 src/main/resources/sprites/*.png > /tmp/bake-1.txt
python3 tools/build_spritesheet.py >/dev/null
shasum -a 256 src/main/resources/sprites/*.png > /tmp/bake-2.txt
diff /tmp/bake-1.txt /tmp/bake-2.txt && echo "DETERMINISTIC"
```

Expected: `DETERMINISTIC`. If not, an encoder setting is still varying — do not proceed, the artifact is committed.

- [ ] **Step 5: Commit**

```bash
git add tools/build_spritesheet.py src/main/resources/sprites/
git commit -m "feat: bake the diver sprite sheets"
```

---

### Task 7: QA bundle

**Files:**
- Create: `tools/spritesheet/qa.py`
- Modify: `tools/build_spritesheet.py` — import and call `write_qa_bundle`

**Interfaces:**
- Consumes: `spritesheet.geometry.Grid`, `spritesheet.assembly.cell_origin`
- Produces: `write_qa_bundle(out_dir, grid, frame_count, diffuse_sheet, normal_sheet) -> None`

- [ ] **Step 1: Write the module**

Create `tools/spritesheet/qa.py`:

```python
"""
Artifacts a human looks at.

This repo's bug history is almost entirely rendering faults that no test caught, so
the bake ships evidence rather than a claim. In particular the frame-42 decision is
an argument about authored motion, and only the animated preview settles it.
"""
import numpy as np
from PIL import Image, ImageDraw

from .assembly import cell_origin
from .geometry import Grid

CHECKER_LIGHT = (90, 90, 100)
CHECKER_DARK = (60, 60, 70)


def _cells(grid: Grid, sheet: np.ndarray, count: int):
    for index in range(count):
        x, y = cell_origin(grid, index)
        yield sheet[y:y + grid.frame_h, x:x + grid.frame_w]


def _on_checker(cell: np.ndarray) -> Image.Image:
    """Composite over a checkerboard so alpha problems are visible, not guessed."""
    h, w = cell.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    board = np.where(
        (((yy // 8) + (xx // 8)) % 2)[..., None],
        np.array(CHECKER_LIGHT), np.array(CHECKER_DARK),
    ).astype(np.float64)
    alpha = cell[..., 3:4] / 255.0
    return Image.fromarray(
        (cell[..., :3] * alpha + board * (1 - alpha)).astype(np.uint8), mode="RGB"
    )


def write_qa_bundle(out_dir, grid: Grid, frame_count: int,
                    diffuse_sheet: np.ndarray, normal_sheet: np.ndarray) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. The loop, at final resolution. Watch the seam.
    frames = [_on_checker(c) for c in _cells(grid, diffuse_sheet, frame_count)]
    frames[0].save(
        out_dir / "loop.gif", save_all=True, append_images=frames[1:],
        duration=1000 // 30, loop=0, disposal=2,
    )

    # 2. Indexed contact sheet - an off-by-one grid is visible at a glance.
    for name, sheet in (("diffuse", diffuse_sheet), ("normal", normal_sheet)):
        canvas = _on_checker(sheet).convert("RGB")
        draw = ImageDraw.Draw(canvas)
        for index in range(grid.cols * grid.rows):
            x, y = cell_origin(grid, index)
            used = index < frame_count
            draw.rectangle(
                [x, y, x + grid.frame_w - 1, y + grid.frame_h - 1],
                outline=(0, 220, 0) if used else (220, 0, 0),
            )
            draw.text((x + 4, y + 4), str(index) if used else "unused",
                      fill=(255, 255, 0))
        canvas.save(out_dir / f"contact-{name}.png")

    # 3. Normals relit by a moving key light. A flipped or wrongly-decoded channel
    #    makes the highlight travel the WRONG WAY, which is obvious in motion and
    #    nearly invisible in a still.
    lit = []
    for angle_index in range(16):
        theta = angle_index / 16.0 * 2.0 * np.pi
        light = np.array([np.cos(theta), np.sin(theta), 0.6])
        light = light / np.linalg.norm(light)
        cell = next(iter(_cells(grid, normal_sheet, 1)))
        v = cell[..., :3] / 255.0 * 2.0 - 1.0          # sheet is LINEAR-encoded
        shade = np.clip((v * light).sum(axis=-1), 0, 1)
        alpha = cell[..., 3:4] / 255.0
        rgb = (shade[..., None] * 255 * alpha).astype(np.uint8)
        lit.append(Image.fromarray(np.repeat(rgb, 3, axis=-1), mode="RGB"))
    lit[0].save(
        out_dir / "normal-relit.gif", save_all=True, append_images=lit[1:],
        duration=100, loop=0, disposal=2,
    )
```

- [ ] **Step 2: Wire it into the CLI**

In `tools/build_spritesheet.py`, add to the import block:

```python
from spritesheet.qa import write_qa_bundle
```

and insert immediately before `print(f"""` (the Kotlin block) in `bake`:

```python
    write_qa_bundle(QA_DIR, grid, len(diffuse), diffuse_sheet, normal_sheet)
    print(f"wrote QA bundle to {QA_DIR}")
```

- [ ] **Step 3: Run and inspect**

```bash
python3 tools/build_spritesheet.py
ls -la build/spritesheet-qa/
open build/spritesheet-qa/loop.gif build/spritesheet-qa/contact-diffuse.png
```

Expected: `loop.gif`, `contact-diffuse.png`, `contact-normal.png`, `normal-relit.gif`.

**Check by eye, and do not skip this:**
1. `loop.gif` — the diver bobs smoothly with **no hitch at the loop point**. A visible stall means the frame-42 call was wrong; re-run with `--keep-last-frame` and compare.
2. `contact-diffuse.png` — 41 green-outlined cells numbered 0–40, one red "unused", figure centred in every cell with clear margin at the outline.
3. `normal-relit.gif` — the highlight sweeps **around** the body following the light. If it moves opposite to the sweep, a channel is inverted.

- [ ] **Step 4: Confirm determinism still holds**

```bash
shasum -a 256 src/main/resources/sprites/*.png > /tmp/bake-3.txt
python3 tools/build_spritesheet.py >/dev/null
shasum -a 256 src/main/resources/sprites/*.png > /tmp/bake-4.txt
diff /tmp/bake-3.txt /tmp/bake-4.txt && echo "DETERMINISTIC"
```

Expected: `DETERMINISTIC`

- [ ] **Step 5: Commit**

```bash
git add tools/spritesheet/qa.py tools/build_spritesheet.py
git commit -m "feat: QA bundle so the bake ships evidence rather than a claim"
```

---

### Task 8: Documentation and full-suite verification

**Files:**
- Modify: `CLAUDE.md` — add the bake command and the two engine gotchas
- Test: full suite

- [ ] **Step 1: Run everything**

```bash
cd tools && python3 -m pytest -v; cd ..
./gradlew test
```

Expected: 42 Python tests pass; the Kotlin suite passes unchanged (this task adds no Kotlin).

- [ ] **Step 2: Add the command to CLAUDE.md**

In the `## Commands` fenced block, after the `buildWin64Release` line:

```bash
python3 tools/build_spritesheet.py    # re-bake the diver sprite sheets from assets/ (gitignored)
cd tools && python3 -m pytest         # the bake script's tests
```

- [ ] **Step 3: Add the engine gotchas to the platform-constraints list**

These belong in `## Platform constraints — verified, not guessed` because both fail silently, which is that section's stated criterion. Add as two bullets:

```markdown
- **`SpriteSheet`'s constructor takes `(…, format, maxMipLevels, hCells, vCells)`** — the
  argument order is *not* the field declaration order, which reads `horizontalCells,
  verticalCells` first. Passing `(…, cols, rows, 0)` sets `hCells = rows` and `vCells = 0`,
  so `size = rows * 0 = 0`, the backing `Texture[]` is zero-length, and `getTexture(0)`
  throws on the first frame drawn. Verified from bytecode: the 6th int is forwarded to
  `Texture.<init>`'s trailing `maxMipLevels`, and the synthetic defaults constructor
  defaults that slot to `5`, pairing with the `LINEAR_MIPMAP` filter default.
- **`maxMipLevels = 0` allocates no texture storage at all.** `TextureArray` computes
  `mipLevels = min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and
  hands it to `glTexStorage3D` as `levels`; `levels = 0` is `GL_INVALID_VALUE`, so nothing
  is allocated and every later `glTexSubImage3D` fails too — no exception, no log. `1` is
  the value that means "one level, no mips". `tools/build_spritesheet.py` prints the
  correct constructor call for exactly this reason.
```

- [ ] **Step 4: Note the source-art provenance in CLAUDE.md**

Add to the `## Config and release` section:

```markdown
**Sprite art.** `assets/` (183 MB of 1000×2000 source frames) and `release/` are gitignored;
the *baked* sheets in `src/main/resources/sprites/` are committed and ship in the `.exe`.
A clean clone can build and run but cannot re-bake without the source frames. The bake is
byte-reproducible, so re-running it with unchanged inputs produces no diff.
```

- [ ] **Step 5: Final verification and commit**

```bash
cd tools && python3 -m pytest -q; cd ..
./gradlew test
git add CLAUDE.md
git commit -m "docs: record the sprite sheet bake and two silent SpriteSheet traps"
```

---

## Self-Review

**Spec coverage** — every section maps to a task:

| Spec section | Task |
|---|---|
| §1 constructor order | 6 (emission), 8 (documented) |
| §2 source measurements, frame 42 | 5 (bbox), 6 (drop tail) |
| §3 Pillow+numpy only | all — no other import appears |
| §4 CLI contract, `.DS_Store`, mkdir | 5 (`list_frames`), 6 |
| §5 geometry, grid search, guard | 1, 4 |
| §6 resampling, sRGB normals, 16-bit workaround | 2, 3, 5, 6 (`load_rgba`) |
| §7 gitignore, determinism, `tEXt` | 6 (steps 4–5), 8 |
| §8 gates, QA bundle, Kotlin print | 5, 6, 7 |
| §9 out of scope | not implemented, by design |

**Placeholder scan:** no TBDs; every code step carries runnable code; no "similar to Task N".

**Type consistency:** `Grid` fields are used identically in Tasks 1, 4, 7. `content_box` returns `(w, h)` and is passed straight to `resample_*` as `size`, matching Pillow's `(width, height)` convention throughout. `resample_diffuse`/`resample_normal` share one signature, which is what lets Task 3's final test loop over both.

**One gap found and closed:** the plan originally had no consumer for `check_normal_encoding` — it is now called per-frame in Task 6's validation loop.
