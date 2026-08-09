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
