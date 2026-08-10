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
