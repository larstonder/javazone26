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
from spritesheet.qa import write_qa_bundle
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

    write_qa_bundle(QA_DIR, grid, len(diffuse), diffuse_sheet, normal_sheet)
    print(f"wrote QA bundle to {QA_DIR}")

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
