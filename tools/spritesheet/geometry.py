"""
Frame sizing and grid selection for the sprite sheet bake.

Pure integer maths, no numpy and no I/O, so every number the bake depends on can be
asserted without touching the 183 MB of source art.
"""
import math
from typing import NamedTuple

# The DIVER's content box, and the default aspect for `frame_width`. Every other set
# passes its own measured aspect; this one is a constant because the diver's numbers
# are quoted in the design spec and asserted by tests that must not need the art.
#
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
