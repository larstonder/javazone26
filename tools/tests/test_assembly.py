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


def _indexed_fill(grid, index):
    """A cell filled with a value unique to `index`, for identity checks."""
    w, h = content_box(grid)
    cell = np.zeros((h, w, 4), dtype=np.uint8)
    cell[..., :3] = (17 + index) % 256
    cell[..., 3] = 255
    return cell


def _pattern_cell(grid):
    """
    Content that varies along both axes, unlike `_solid_cell`. A uniform fill
    would round-trip correctly even under a transposed width/height slice or an
    offset write, since every pixel in it is identical; this catches those too.
    """
    w, h = content_box(grid)
    cell = np.zeros((h, w, 4), dtype=np.uint8)
    cell[..., 0] = (np.arange(w) % 256)[None, :]
    cell[..., 1] = (np.arange(h) % 256)[:, None]
    cell[..., 2] = 91
    cell[..., 3] = 255
    return cell


def test_cell_interior_round_trips_the_exact_content_pixels():
    # assemble's whole job is to place content, not just leave a transparent
    # guard around an unwritten cell - a stub that allocates the right-shaped
    # zero sheet and never writes into it would still pass every test above,
    # since a sheet of all zeros is trivially "opaque nowhere" and "transparent
    # in the tail". This pins the actual pixel values, not just their absence.
    grid = _grid()
    box_w, box_h = content_box(grid)
    index = 5
    content = _pattern_cell(grid)
    cells = [_solid_cell(grid, value=0)] * 41
    cells[index] = content
    sheet = assemble(grid, cells)
    x, y = cell_origin(grid, index)
    region = sheet[y + GUARD_PX:y + GUARD_PX + box_h, x + GUARD_PX:x + GUARD_PX + box_w]
    assert np.array_equal(region, content)


def test_distinct_cells_land_at_distinct_row_major_positions():
    """
    14 cols: index 13 is the last cell of row 0, 14 the first cell of row 1, and
    15 the second cell of row 1 - a column-major or transposed index mapping
    would misplace exactly these three. Expected pixel coordinates are computed
    here directly from the row-major formula, NOT via `cell_origin` - assemble
    calls `cell_origin` internally, so if that function itself were the buggy
    one, reading back through it too would just agree with the bug instead of
    catching it.
    """
    grid = _grid()
    box_w, box_h = content_box(grid)
    indices = (13, 14, 15)
    cells = [_solid_cell(grid, value=0)] * 41
    for i in indices:
        cells[i] = _indexed_fill(grid, i)
    sheet = assemble(grid, cells)
    for i in indices:
        expected_x = (i % grid.cols) * grid.frame_w
        expected_y = (i // grid.cols) * grid.frame_h
        region = sheet[
            expected_y + GUARD_PX:expected_y + GUARD_PX + box_h,
            expected_x + GUARD_PX:expected_x + GUARD_PX + box_w,
        ]
        assert np.array_equal(region, _indexed_fill(grid, i))
