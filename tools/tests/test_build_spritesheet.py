"""
Coverage for the orchestrator itself, tools/build_spritesheet.py.

Before this file existed, a reviewer mutated bake() and kotlin_snippet() twelve ways
- each one a defect the design spec's S10 documents as silently fatal at the booth -
and every single mutant survived the full test suite. These tests exist to close that
gap, in particular for the two facts a human copies out of the printed Kotlin by hand:
argument order (hCells before vCells, not the reverse) and maxMipLevels=1 (never 0).
"""
import pathlib

import numpy as np
import pytest
from PIL import Image

import build_spritesheet
from build_spritesheet import kotlin_snippet
from spritesheet.colour import linear_to_srgb
from spritesheet.geometry import choose_grid, frame_width
from spritesheet.validate import SourceError

# --------------------------------------------------------------------------------
# kotlin_snippet: the hand-typed template, not the numbers that fill it in.
# --------------------------------------------------------------------------------


def test_kotlin_snippet_argument_order_and_mip_levels_are_correct():
    # The real bake's grid: cols=14, rows=3 - deliberately distinct from each other
    # and from maxMipLevels=1, so a swap of any of the three is detectable below.
    grid = choose_grid(41, 126, 384)
    assert (grid.cols, grid.rows) == (14, 3)

    snippet = kotlin_snippet(grid, 41)

    # Both SpriteSheet(...) calls must read "1, 14, 3)" - maxMipLevels=1, THEN cols,
    # THEN rows. A swap to "1, 3, 14)" (rows-before-cols) or "0, 14, 3)"
    # (maxMipLevels=0) must fail this, not silently pass because the numbers appear
    # somewhere else in the string.
    assert snippet.count("\n        1, 14, 3)") == 2
    assert "1, 3, 14)" not in snippet
    assert "0, 14, 3)" not in snippet


def test_kotlin_snippet_formats_are_on_the_correct_texture():
    grid = choose_grid(41, 126, 384)
    snippet = kotlin_snippet(grid, 41)

    diffuse_block, normal_block = snippet.split("diver-normal.png")
    assert "TextureFormat.SRGBA8" in diffuse_block
    assert "TextureFormat.RGBA8" in normal_block
    # "TextureFormat.RGBA8" is not a substring of "TextureFormat.SRGBA8" (the S
    # breaks contiguity), so these are independent checks, not restatements of the
    # ones above: the diffuse call must not ALSO claim linear RGBA8, and the normal
    # call must not ALSO claim sRGB.
    assert "TextureFormat.RGBA8" not in diffuse_block
    assert "TextureFormat.SRGBA8" not in normal_block


def test_kotlin_snippet_frame_count_is_not_cols_times_rows():
    grid = choose_grid(41, 126, 384)
    assert grid.cols * grid.rows == 42  # one unused trailing cell

    snippet = kotlin_snippet(grid, 41)
    assert "DIVER_FRAME_COUNT = 41" in snippet
    assert "DIVER_FRAME_COUNT = 42" not in snippet


# --------------------------------------------------------------------------------
# bake(): end-to-end against synthetic fixtures under tmp_path.
# --------------------------------------------------------------------------------

# Frame dimensions and content bbox are deliberately NON-SQUARE and ASYMMETRIC:
# W=100, H=20, and the content's x-range (50-90) exceeds H=20 entirely. If the crop
# in bake() is ever transposed - (slice(x0,x1+1), slice(y0,y1+1)) instead of
# (slice(y0,y1+1), slice(x0,x1+1)) - the row-slice becomes 50:91 against an array
# with only 20 rows, which numpy silently clamps to an EMPTY (0-row) selection.
# Verified empirically: resampling an empty crop through the real pipeline produces
# an all-fully-transparent output cell (deterministic, not garbage), which the
# alpha-coverage assertion below catches.
FRAME_W, FRAME_H = 100, 20
X0, Y0, X1, Y1 = 50, 2, 90, 15
DIFFUSE_COLOUR = (200, 100, 50)


def _rect_alpha(w, h, x0, y0, x1, y1):
    a = np.zeros((h, w), dtype=np.uint8)
    a[y0:y1 + 1, x0:x1 + 1] = 255
    return a


def _diffuse_frame():
    alpha = _rect_alpha(FRAME_W, FRAME_H, X0, Y0, X1, Y1)
    rgb = np.zeros((FRAME_H, FRAME_W, 3), dtype=np.uint8)
    rgb[Y0:Y1 + 1, X0:X1 + 1] = DIFFUSE_COLOUR
    return np.dstack([rgb, alpha])


def _normal_frame():
    # sRGB-ENCODED flat +Z, i.e. what check_normal_encoding requires - see
    # test_resample.py's _flat_normal_field for the same construction.
    alpha = _rect_alpha(FRAME_W, FRAME_H, X0, Y0, X1, Y1)
    linear = np.zeros((FRAME_H, FRAME_W, 3), dtype=np.float64)
    linear[..., 0] = 0.5
    linear[..., 1] = 0.5
    linear[..., 2] = 1.0
    rgb = np.clip(linear_to_srgb(linear) * 255 + 0.5, 0, 255).astype(np.uint8)
    return np.dstack([rgb, alpha])


def _write_source_set(diffuse_dir, normals_dir, count):
    diffuse_dir.mkdir(parents=True, exist_ok=True)
    normals_dir.mkdir(parents=True, exist_ok=True)
    for i in range(1, count + 1):
        name = f"{i:04d}.png"
        Image.fromarray(_diffuse_frame(), mode="RGBA").save(diffuse_dir / name)
        Image.fromarray(_normal_frame(), mode="RGBA").save(normals_dir / name)


def _patch_dirs(monkeypatch, tmp_path):
    diffuse_dir, normals_dir = tmp_path / "diffuse", tmp_path / "normals"
    out_dir, qa_dir = tmp_path / "out", tmp_path / "qa"
    monkeypatch.setattr(build_spritesheet, "DIFFUSE_DIR", diffuse_dir)
    monkeypatch.setattr(build_spritesheet, "NORMALS_DIR", normals_dir)
    monkeypatch.setattr(build_spritesheet, "OUT_DIR", out_dir)
    monkeypatch.setattr(build_spritesheet, "QA_DIR", qa_dir)
    return diffuse_dir, normals_dir, out_dir, qa_dir


def test_bake_end_to_end_drops_tail_frame_and_produces_expected_geometry(
    tmp_path, monkeypatch
):
    diffuse_dir, normals_dir, out_dir, qa_dir = _patch_dirs(monkeypatch, tmp_path)
    _write_source_set(diffuse_dir, normals_dir, count=4)  # frame 4 is the dupe tail

    frame_height = FRAME_H
    rc = build_spritesheet.bake(frame_height, keep_last=False)
    assert rc == 0

    content_w, content_h = X1 - X0 + 1, Y1 - Y0 + 1  # 41 x 14
    aspect = content_w / content_h
    frame_w = frame_width(frame_height, aspect)
    # 4 source frames minus the dropped tail = 3 baked frames.
    expected_grid = choose_grid(3, frame_w, frame_height)

    diffuse_sheet = Image.open(out_dir / "diver-diffuse.png")
    assert diffuse_sheet.size == (expected_grid.sheet_w, expected_grid.sheet_h)
    assert diffuse_sheet.text["ept:frame_count"] == "3"
    assert diffuse_sheet.text["ept:grid"] == f"{expected_grid.cols}x{expected_grid.rows}"

    normal_sheet = Image.open(out_dir / "diver-normal.png")
    assert normal_sheet.size == (expected_grid.sheet_w, expected_grid.sheet_h)

    # Detects a transposed crop (and, incidentally, a swapped content w/h): with the
    # asymmetric fixture above, a transposed crop silently degenerates to an empty
    # (0-row) selection and every baked cell comes out fully transparent. A real
    # bake must actually carry the opaque rectangle through into the sheet.
    diffuse_px = np.asarray(diffuse_sheet)
    assert (diffuse_px[..., 3] > 0).sum() > 0
    normal_px = np.asarray(normal_sheet)
    assert (normal_px[..., 3] > 0).sum() > 0


def test_bake_aborts_before_writing_when_normal_encoding_fails(tmp_path, monkeypatch):
    diffuse_dir, normals_dir, out_dir, qa_dir = _patch_dirs(monkeypatch, tmp_path)
    diffuse_dir.mkdir(parents=True)
    normals_dir.mkdir(parents=True)

    alpha = _rect_alpha(FRAME_W, FRAME_H, X0, Y0, X1, Y1)
    diffuse = np.dstack(
        [np.full((FRAME_H, FRAME_W, 3), 100, dtype=np.uint8), alpha]
    )
    # NOT sRGB-encoded (raw v=(0,0,1)-ish bytes) - decodes to a strongly non-unit
    # vector and must be rejected by check_normal_encoding before any write happens.
    raw_rgb = np.zeros((FRAME_H, FRAME_W, 3), dtype=np.uint8)
    raw_rgb[..., 0] = 128
    raw_rgb[..., 1] = 128
    raw_rgb[..., 2] = 255
    normal = np.dstack([raw_rgb, alpha])

    Image.fromarray(diffuse, mode="RGBA").save(diffuse_dir / "0001.png")
    Image.fromarray(normal, mode="RGBA").save(normals_dir / "0001.png")

    with pytest.raises(SourceError, match="unit"):
        build_spritesheet.bake(FRAME_H, keep_last=False)

    assert not out_dir.exists() or list(out_dir.glob("*.png")) == []


# --------------------------------------------------------------------------------
# MINOR: the committed sheets' tEXt metadata should still agree with the grid the
# current geometry module would choose. Skips gracefully on a clean checkout that
# hasn't obtained the (gitignored) source frames and re-baked.
# --------------------------------------------------------------------------------


def test_committed_sheets_metadata_matches_current_grid():
    diffuse_path = build_spritesheet.OUT_DIR / "diver-diffuse.png"
    normal_path = build_spritesheet.OUT_DIR / "diver-normal.png"
    if not diffuse_path.exists() or not normal_path.exists():
        pytest.skip("committed sprite sheets are not present in this checkout")

    expected_grid = choose_grid(41, frame_width(384), 384)

    for path in (diffuse_path, normal_path):
        with Image.open(path) as im:
            assert im.text["ept:frame_count"] == "41"
            assert im.text["ept:frame_height"] == "384"
            assert (
                im.text["ept:grid"] == f"{expected_grid.cols}x{expected_grid.rows}"
            )
