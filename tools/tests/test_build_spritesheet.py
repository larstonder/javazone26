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
from spritesheet.sets import DIFFUSE, NORMAL, Layer, SpriteSet
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


def _write_frames(directory, count, make_frame):
    directory.mkdir(parents=True, exist_ok=True)
    for i in range(1, count + 1):
        Image.fromarray(make_frame(), mode="RGBA").save(directory / f"{i:04d}.png")


def _write_source_set(diffuse_dir, normals_dir, count):
    _write_frames(diffuse_dir, count, _diffuse_frame)
    _write_frames(normals_dir, count, _normal_frame)


def _paired_set(tmp_path, dropped_tail_frames=1):
    """A two-layer set under tmp_path, shaped like the diver's."""
    return SpriteSet(
        name="paired",
        layers=(
            Layer(DIFFUSE, tmp_path / "diffuse", "test-diffuse.png", "test_diffuse"),
            Layer(NORMAL, tmp_path / "normals", "test-normal.png", "test_normal"),
        ),
        frame_height=FRAME_H,
        dropped_tail_frames=dropped_tail_frames,
        frame_count_constant="TEST_FRAME_COUNT",
    )


def _normal_only_set(tmp_path, dropped_tail_frames=0):
    """A one-layer set, shaped like oxygen's: normals, no diffuse, no dropped tail."""
    return SpriteSet(
        name="normalonly",
        layers=(
            Layer(NORMAL, tmp_path / "normals", "test-normal.png", "test_normal"),
        ),
        frame_height=FRAME_H,
        dropped_tail_frames=dropped_tail_frames,
        frame_count_constant="TEST_FRAME_COUNT",
    )


def _patch_dirs(monkeypatch, tmp_path):
    out_dir, qa_dir = tmp_path / "out", tmp_path / "qa"
    monkeypatch.setattr(build_spritesheet, "OUT_DIR", out_dir)
    monkeypatch.setattr(build_spritesheet, "QA_DIR", qa_dir)
    return tmp_path / "diffuse", tmp_path / "normals", out_dir, qa_dir


def test_bake_end_to_end_drops_tail_frame_and_produces_expected_geometry(
    tmp_path, monkeypatch
):
    diffuse_dir, normals_dir, out_dir, qa_dir = _patch_dirs(monkeypatch, tmp_path)
    _write_source_set(diffuse_dir, normals_dir, count=4)  # frame 4 is the dupe tail

    frame_height = FRAME_H
    rc = build_spritesheet.bake(
        frame_height, keep_last=False, sprite_set=_paired_set(tmp_path)
    )
    assert rc == 0

    content_w, content_h = X1 - X0 + 1, Y1 - Y0 + 1  # 41 x 14
    aspect = content_w / content_h
    frame_w = frame_width(frame_height, aspect)
    # 4 source frames minus the dropped tail = 3 baked frames.
    expected_grid = choose_grid(3, frame_w, frame_height)

    diffuse_sheet = Image.open(out_dir / "test-diffuse.png")
    assert diffuse_sheet.size == (expected_grid.sheet_w, expected_grid.sheet_h)
    assert diffuse_sheet.text["ept:frame_count"] == "3"
    assert diffuse_sheet.text["ept:grid"] == f"{expected_grid.cols}x{expected_grid.rows}"

    normal_sheet = Image.open(out_dir / "test-normal.png")
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
        build_spritesheet.bake(
            FRAME_H, keep_last=False, sprite_set=_paired_set(tmp_path)
        )

    assert not out_dir.exists() or list(out_dir.glob("*.png")) == []


# --------------------------------------------------------------------------------
# Generality: a set is a LIST of layers, not a fixed diffuse/normal pair. Before this
# section existed the bake could only ever produce the diver's shape, and every gate
# below was unconditional - so a normals-only set aborted on check_pairing rather than
# baking, and a set with no duplicate tail silently lost its last frame.
# --------------------------------------------------------------------------------


def test_bake_of_a_normal_only_set_writes_one_sheet_and_skips_the_paired_gates(
    tmp_path, monkeypatch
):
    _, normals_dir, out_dir, qa_dir = _patch_dirs(monkeypatch, tmp_path)
    _write_frames(normals_dir, 3, _normal_frame)

    rc = build_spritesheet.bake(
        FRAME_H, keep_last=False, sprite_set=_normal_only_set(tmp_path)
    )
    assert rc == 0

    # Exactly one sheet. A diffuse sheet here would occupy a layer of a 15-layer
    # texture array to be sampled by nothing - see the oxygen note in sets.py.
    assert [p.name for p in sorted(out_dir.glob("*.png"))] == ["test-normal.png"]

    # And it is a real bake, not an empty one: check_pairing and
    # check_alpha_agreement must have been SKIPPED rather than passed vacuously,
    # which they cannot be with a single layer.
    assert (np.asarray(Image.open(out_dir / "test-normal.png"))[..., 3] > 0).sum() > 0


def test_bake_keeps_every_frame_when_the_set_drops_no_tail(tmp_path, monkeypatch):
    """
    dropped_tail_frames is per-set data, not a constant.

    The diver's frame 42 re-exports frame 1; the oxygen loop's frame 96 does not. With
    a hard-coded DROPPED_TAIL_FRAMES = 1 this set would bake 3 of its 4 frames and put
    a hitch in a loop that was authored whole.
    """
    _, normals_dir, out_dir, _ = _patch_dirs(monkeypatch, tmp_path)
    _write_frames(normals_dir, 4, _normal_frame)

    rc = build_spritesheet.bake(
        FRAME_H, keep_last=False,
        sprite_set=_normal_only_set(tmp_path, dropped_tail_frames=0),
    )
    assert rc == 0
    assert Image.open(out_dir / "test-normal.png").text["ept:frame_count"] == "4"


def test_bake_writes_qa_into_a_per_set_subdirectory(tmp_path, monkeypatch):
    """
    Two sets baked in sequence must not overwrite each other's QA bundle.

    A shared directory made the second bake silently replace the first's contact
    sheets and loop - exactly the quiet wrongness the QA bundle exists to catch.
    """
    diffuse_dir, normals_dir, _, qa_dir = _patch_dirs(monkeypatch, tmp_path)
    _write_source_set(diffuse_dir, normals_dir, count=3)

    assert build_spritesheet.bake(
        FRAME_H, keep_last=False, sprite_set=_paired_set(tmp_path, 0)) == 0
    assert build_spritesheet.bake(
        FRAME_H, keep_last=False, sprite_set=_normal_only_set(tmp_path)) == 0

    assert (qa_dir / "paired" / "contact-diffuse.png").exists()
    assert (qa_dir / "paired" / "contact-normal.png").exists()
    assert (qa_dir / "normalonly" / "contact-normal.png").exists()
    # The relit GIF follows the NORMAL layers specifically - it is the only check that
    # catches a flipped channel, and it is meaningless on a diffuse sheet.
    assert (qa_dir / "normalonly" / "normal-relit.gif").exists()
    # A normals-only set still gets a loop, animated from its normal layer, because a
    # duplicated tail frame shows up there as a hitch regardless of layer kind.
    assert (qa_dir / "normalonly" / "loop.gif").exists()


def test_kotlin_snippet_for_a_normal_only_set_emits_one_call_and_no_diffuse():
    grid = choose_grid(96, 182, 216)
    assert (grid.cols, grid.rows) == (11, 9)

    snippet = kotlin_snippet(grid, 96, build_spritesheet.SPRITE_SETS["oxygen"])

    assert snippet.count("SpriteSheet(") == 1
    assert "oxygen-normal.png" in snippet
    assert "oxygen-diffuse.png" not in snippet
    # A normal sheet stores (v+1)/2 LINEARLY and must reach the shader unchanged.
    # Claiming SRGBA8 would have the GPU linearise it a second time.
    assert "TextureFormat.RGBA8" in snippet
    assert "TextureFormat.SRGBA8" not in snippet
    # The two facts a human copies by hand survive generalisation.
    assert snippet.count("\n        1, 11, 9)") == 1
    assert "1, 9, 11)" not in snippet
    assert "0, 11, 9)" not in snippet
    assert "OXYGEN_FRAME_COUNT = 96" in snippet
    assert "OXYGEN_FRAME_COUNT = 99" not in snippet  # NOT cols*rows


def test_kotlin_snippet_unused_cell_note_matches_the_grid():
    """
    The note is a warning, and a warning that points at nothing is noise. 41 frames in
    a 14x3 grid leaves one unused cell; 24 in a 12x2 leaves none.
    """
    leaves_one = kotlin_snippet(choose_grid(41, 126, 384), 41)
    assert "the last 1 cell(s) are unused" in leaves_one

    exact = choose_grid(24, 126, 384)
    assert exact.unused_cells == 0
    assert "cell(s) are unused" not in kotlin_snippet(exact, 24)


def test_bbox_layer_prefers_normals_over_diffuse(tmp_path):
    """
    Pillow truncates a 16-bit diffuse to its high byte and zeroes very low alpha,
    giving a content box one pixel narrow (628 vs 629 on the real art). The union box
    must come from a true 8-bit normal layer wherever one exists.
    """
    assert _paired_set(tmp_path).bbox_layer.kind == NORMAL
    assert build_spritesheet.SPRITE_SETS["diver"].bbox_layer.kind == NORMAL
    assert build_spritesheet.SPRITE_SETS["oxygen"].bbox_layer.kind == NORMAL


def test_registered_sets_declare_the_formats_their_kinds_require():
    for name, sprite_set in build_spritesheet.SPRITE_SETS.items():
        assert sprite_set.layers, f"{name} has no layers"
        for layer in sprite_set.layers:
            expected = "SRGBA8" if layer.kind == DIFFUSE else "RGBA8"
            assert layer.texture_format == expected, name
        # The hyphen is load-bearing: the engine's loadAll auto-loader keys on the
        # substring "_normal" and, when it matches, forces 10 mip levels regardless of
        # what the caller asked for. Mips average across cell boundaries and would
        # bleed adjacent frames of the loop together under minification.
        for layer in sprite_set.layers_of(NORMAL):
            assert "_normal" not in layer.output_name, name


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
