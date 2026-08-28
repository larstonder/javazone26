#!/usr/bin/env python3
"""
Bake a set of animation frames into Pulse Engine sprite sheets.

    tools/build_spritesheet.py [SET] [--frame-height N] [--keep-last-frame] [--list]

SET is a key of SPRITE_SETS below and defaults to `diver`. Adding a sprite set is
adding an entry there; see `spritesheet/sets.py` for what a set is and why the shape
is a list of layers rather than a fixed diffuse/normal pair.

SOURCE ART PROVENANCE
    diver   assets/sprites/diffuse/0001-0042.png   1000x2000 RGBA, 16-bit
            assets/sprites/normals/0001-0042.png   1000x2000 RGBA, 8-bit, sRGB-encoded
            Frames 1-41 are used. Frame 42 is an exported duplicate of frame 1:
            silhouette centroid speed climbs to 5.284 px/frame going into it - the
            maximum of the whole loop - then collapses to 0.083. --keep-last-frame
            includes it anyway.

    oxygen  assets/oxygen_sprites/0001-0096.png    2000x2000 RGBA, 8-bit, sRGB-encoded
            NORMALS ONLY, and that is not an omission: the blob's colour is produced by
            shaders/iridescence.frag at draw time, so a diffuse sheet would occupy a
            layer of a 15-layer texture array to be sampled by nothing. Verified as a
            normal map rather than assumed from its look - it reads as an iridescent
            blob to the eye, but resample.mean_normal_length gives 0.9997 under the
            sRGB-linearised decode against 1.1606 raw (the diver's normals measure
            0.998 / 1.170), mean X is -0.0056 over a bilaterally symmetric shape, and
            0 of 96 frames fail check_normal_encoding individually.
            All 96 frames are used: unlike the diver there is no duplicate tail, mean
            |delta| between frame 96 and frame 1 is 1.75 and the 96->1 centroid step is
            5.07 px against a 5.83 px loop mean.

    assets/ is gitignored (183 MB, .gitignore:130) so a clean clone cannot re-bake
    without obtaining the frames separately. That is why the OUTPUT is committed.

FRAME HEIGHT IS A VRAM DECISION, NOT A SHARPNESS ONE
    TextureBank allocates one SQUARE array per (bucket, format, filter, wrapping,
    maxMipLevels), with every layer eager, and reuses it only when all of those match
    AND max(w, h) > arraySize / 2. Everything this game ships today lands in the 2048
    bucket, so 2048/SRGBA8 (240 MiB) and 2048/RGBA8 (240 MiB) are already paid for and
    a new sheet that lands there is free. A sheet that lands anywhere else is not:
    measured from DEFAULT_CAPACITIES in the 0.13.0 bytecode, the 1024 bucket costs
    +200 MiB, 4096 costs +640 MiB and 8192 costs +1280 MiB, each to hold one texture.
    Smaller is therefore NOT automatically cheaper - oxygen at 96 px would be
    984x768, fail the half-size test against the 2048 array and allocate its own 1024
    array. Re-run the grid search when changing --frame-height rather than assuming.

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
from spritesheet.geometry import Grid, choose_grid, frame_width
from spritesheet.qa import write_qa_bundle
from spritesheet.resample import resample_diffuse, resample_normal
from spritesheet.sets import DIFFUSE, NORMAL, Layer, SpriteSet
from spritesheet.validate import (
    SourceError, check_alpha_agreement, check_dimensions, check_normal_encoding,
    check_pairing, list_frames, union_bbox,
)

REPO = pathlib.Path(__file__).resolve().parent.parent
ASSETS = REPO / "assets"
OUT_DIR = REPO / "src" / "main" / "resources" / "sprites"
QA_DIR = REPO / "build" / "spritesheet-qa"

SPRITE_SETS = {
    "diver": SpriteSet(
        name="diver",
        layers=(
            Layer(DIFFUSE, ASSETS / "sprites" / "diffuse",
                  "diver-diffuse.png", "diver_diffuse"),
            Layer(NORMAL, ASSETS / "sprites" / "normals",
                  "diver-normal.png", "diver_normal"),
        ),
        frame_height=384,
        dropped_tail_frames=1,
        frame_count_constant="DIVER_FRAME_COUNT",
    ),
    "oxygen": SpriteSet(
        name="oxygen",
        layers=(
            # The output keeps the HYPHEN. The engine's loadAll auto-loader keys on the
            # substring "_normal" - an underscore - and forces RGBA8 with 10 mip levels
            # when it matches, and mip generation averages across cell boundaries, which
            # would bleed adjacent frames of the loop into each other under minification.
            Layer(NORMAL, ASSETS / "oxygen_sprites",
                  "oxygen-normal.png", "oxygen_normal"),
        ),
        # 216 is the largest height whose sheet still shares the 2048 array the diver
        # and rock already allocated: 2002x1944 clears max(w,h) > 2048/2, where 224 px
        # tips the grid search to a 6x16 layout 3584 px tall and buys a whole 4096
        # array. See the module docstring.
        frame_height=216,
        dropped_tail_frames=0,
        frame_count_constant="OXYGEN_FRAME_COUNT",
    ),
}

DEFAULT_SET = "diver"


def load_rgba(path: pathlib.Path) -> np.ndarray:
    """
    Load as 8-bit RGBA.

    Pillow truncates the 16-bit diffuse to its high byte here. That is acceptable for
    COLOUR - a +/-1 error 5x above the output's resolution after downscaling - but is
    exactly why the union bbox is taken from a normal layer instead, and why the alpha
    gate tolerates +/-1. See validate.ALPHA_TOLERANCE and sets.SpriteSet.bbox_layer.
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


def kotlin_snippet(grid: Grid, frame_count: int,
                   sprite_set: SpriteSet = None) -> str:
    """
    The Kotlin call site to copy verbatim into the render-wiring branch.

    This is the one artifact in the whole bake that a human copies by hand, so the
    two facts that are silently fatal if wrong are hard-coded as literals rather than
    derived: argument order is (format, maxMipLevels, hCells, vCells) - NOT the field
    order, see the module docstring and design spec S4 - and maxMipLevels is always
    `1`, never `0` (glTexStorage3D with levels=0 is GL_INVALID_VALUE: no storage
    allocated, no error logged). `grid.cols`/`grid.rows` and `frame_count` are the
    only interpolated numbers; the template around them is what needs covering.

    `sprite_set` defaults to the diver so the diver's snippet is unchanged by the
    generalisation, and so the callers that predate it keep working.
    """
    if sprite_set is None:
        sprite_set = SPRITE_SETS[DEFAULT_SET]

    calls = "\n".join(
        f"""    SpriteSheet("/sprites/{layer.output_name}", "{layer.asset_name}",
        TextureFilter.LINEAR, TextureWrapping.CLAMP_TO_EDGE, \
TextureFormat.{layer.texture_format},
        1, {grid.cols}, {grid.rows})"""
        for layer in sprite_set.layers
    )

    # A set whose frame count exactly fills the grid has no unused cells, and warning
    # about "the last 0 cell(s)" would be noise pointing at nothing. The warning is the
    # point of the line, so it only appears when there is something to warn about.
    if grid.unused_cells:
        count_note = (f"   // NOT {grid.cols}*{grid.rows} - the last "
                      f"{grid.unused_cells} cell(s) are unused")
    else:
        count_note = f"   // exactly {grid.cols}*{grid.rows}, no unused cells"

    return f"""
Kotlin - copy verbatim, the argument order is NOT the field order:

{calls}

    const val {sprite_set.frame_count_constant} = {frame_count}{count_note}

maxMipLevels is 1, never 0: TextureArray computes
min(maxMipLevels, floor(log2(size))+1) with no coerceAtLeast(1), and passes it to
glTexStorage3D as `levels`. levels=0 is GL_INVALID_VALUE - no storage allocated, no
error logged."""


def bake(frame_height: int, keep_last: bool, sprite_set: SpriteSet = None) -> int:
    if sprite_set is None:
        sprite_set = SPRITE_SETS[DEFAULT_SET]

    missing = [l.source_dir for l in sprite_set.layers if not l.source_dir.is_dir()]
    if missing:
        print(
            f"error: source frames not present at "
            f"{', '.join(str(p) for p in missing)}.\n"
            f"       assets/ is gitignored - see this script's header for provenance.",
            file=sys.stderr,
        )
        return 2

    paths = {layer: list_frames(layer.source_dir) for layer in sprite_set.layers}

    # Layers share one frame index, so they must agree on names AND count. A single
    # layer is trivially paired with itself and this loop does nothing - which is why
    # the check is over consecutive pairs rather than a required diffuse/normal duo.
    for earlier, later in zip(sprite_set.layers, sprite_set.layers[1:]):
        check_pairing([p.name for p in paths[earlier]],
                      [p.name for p in paths[later]])

    dropped = 0 if keep_last else sprite_set.dropped_tail_frames
    if dropped and len(paths[sprite_set.layers[0]]) > dropped:
        paths = {layer: p[:-dropped] for layer, p in paths.items()}
        print(f"dropping the last {dropped} frame(s) as exported duplicate(s) "
              f"(--keep-last-frame to override)")

    frames = {layer: [load_rgba(p) for p in paths[layer]]
              for layer in sprite_set.layers}
    check_dimensions([(a.shape[1], a.shape[0])
                      for layer in sprite_set.layers for a in frames[layer]])

    frame_count = len(frames[sprite_set.layers[0]])
    if frame_count == 0:
        raise SourceError(f"no source frames found for sprite set '{sprite_set.name}'")

    # Alpha must agree across layers because they are cropped and scaled as one. With a
    # single layer there is nothing to disagree with.
    for earlier, later in zip(sprite_set.layers, sprite_set.layers[1:]):
        for index, (a, b) in enumerate(zip(frames[earlier], frames[later])):
            check_alpha_agreement(a[..., 3], b[..., 3], index)

    for layer in sprite_set.layers_of(NORMAL):
        for index, n in enumerate(frames[layer]):
            check_normal_encoding(n[..., :3], n[..., 3], index)

    x0, y0, x1, y1 = union_bbox([f[..., 3] for f in frames[sprite_set.bbox_layer]])
    content_w, content_h = x1 - x0 + 1, y1 - y0 + 1
    aspect = content_w / content_h

    frame_w = frame_width(frame_height, aspect)
    grid = choose_grid(frame_count, frame_w, frame_height)
    box = content_box(grid)

    print(f"set      {sprite_set.name}  "
          f"({', '.join(l.kind for l in sprite_set.layers)})")
    print(f"content  {content_w}x{content_h}  (x {x0}-{x1}, y {y0}-{y1}, "
          f"aspect {aspect:.6f})  bbox from {sprite_set.bbox_layer.kind}")
    print(f"frame    {grid.frame_w}x{grid.frame_h}   content box {box[0]}x{box[1]}")
    print(f"grid     {grid.cols}x{grid.rows}  ({grid.unused_cells} unused)  "
          f"sheet {grid.sheet_w}x{grid.sheet_h}  bucket {grid.bucket}")

    crop = (slice(y0, y1 + 1), slice(x0, x1 + 1))
    resample = {DIFFUSE: resample_diffuse, NORMAL: resample_normal}
    sheets = {}
    for layer in sprite_set.layers:
        cells = []
        for frame in frames[layer]:
            rgb, alpha = resample[layer.kind](
                frame[crop][..., :3], frame[crop][..., 3], box
            )
            cells.append(np.dstack([rgb, alpha]))
        sheets[layer] = assemble(grid, cells)

    fingerprint = hashlib.sha256()
    for layer in sprite_set.layers:
        for path in paths[layer]:
            fingerprint.update(path.read_bytes())
    meta = {
        "ept:frame_count": frame_count,
        "ept:frame_height": frame_height,
        "ept:grid": f"{grid.cols}x{grid.rows}",
        "ept:source_sha256": fingerprint.hexdigest()[:16],
    }

    print()
    for layer in sprite_set.layers:
        write_png(OUT_DIR / layer.output_name, sheets[layer], meta)
        print(f"wrote {OUT_DIR / layer.output_name}")

    # Per-set subdirectory: a shared QA directory would have the second set silently
    # overwrite the first's contact sheets and loop, which is exactly the kind of
    # quiet wrongness the QA bundle exists to catch.
    qa_dir = QA_DIR / sprite_set.name
    write_qa_bundle(
        qa_dir, grid, frame_count,
        [(layer.kind, layer.asset_name, sheets[layer]) for layer in sprite_set.layers],
    )
    print(f"wrote QA bundle to {qa_dir}")

    print(kotlin_snippet(grid, frame_count, sprite_set))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("sprite_set", nargs="?", default=DEFAULT_SET,
                        choices=sorted(SPRITE_SETS),
                        help=f"which set to bake (default: {DEFAULT_SET})")
    parser.add_argument("--frame-height", type=int, default=None,
                        help="cell height; defaults to the set's own")
    parser.add_argument("--keep-last-frame", action="store_true",
                        help="include the duplicate final frame(s)")
    parser.add_argument("--list", action="store_true",
                        help="list the known sprite sets and exit")
    args = parser.parse_args(argv)

    if args.list:
        for key in sorted(SPRITE_SETS):
            s = SPRITE_SETS[key]
            kinds = ", ".join(l.kind for l in s.layers)
            print(f"{key:<8} {kinds:<18} frame-height {s.frame_height}, "
                  f"drops {s.dropped_tail_frames} tail frame(s)")
        return 0

    sprite_set = SPRITE_SETS[args.sprite_set]
    frame_height = (args.frame_height if args.frame_height is not None
                    else sprite_set.frame_height)
    try:
        return bake(frame_height, args.keep_last_frame, sprite_set)
    except SourceError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
