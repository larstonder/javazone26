#!/usr/bin/env python3
"""
Bake the column's rock face and the parallax silhouettes into committed textures.

    tools/build_backdrop.py [--rock-height 2048] [--silhouette-max 1600] [--period N]
                            [--luminance-factor 2.0]

SOURCE ART PROVENANCE
    assets/rock/diffuse.png        300x1000 RGBA 8-bit, sRGB (gAMA 0.45455)
    assets/rock/normal.png         300x1000 RGBA 8-bit, sRGB-ENCODED tangent normals
    assets/rock/top_diffuse.png    300x500  RGBA 8-bit, the cliff's summit
    assets/rock/top_normal.png     300x500  RGBA 8-bit, sRGB-ENCODED tangent normals
    assets/silhouettes/1.png       2000x2000 RGBA
    assets/silhouettes/2.png       3000x3000 RGBA
    assets/silhouettes/3.png       3000x1000 RGBA

    assets/ is gitignored (.gitignore) so a clean clone cannot re-bake. That is why the
    OUTPUT is committed, exactly as for the diver's sheets - see
    docs/superpowers/specs/2026-08-07-diver-spritesheet-bake-design.md S7.

THE SEVEN DECISIONS THIS SCRIPT MAKES, EACH OF WHICH FAILS SILENTLY IF GOT WRONG

1. WHETHER THE ROCK IS ALREADY A TILE IS MEASURED, NEVER ASSUMED. The art delivered on
   2026-08-12 wraps at its full 1000 rows and is used as-is. The art before it did not:
   its wrap period was 889 and the remaining 111 rows were an overlap to blend with, and
   butt-joining that file repeated a hard line every tile. `tile.choose_period` tests the
   full-height seam first and only searches for an overlap period if that fails - the
   search has no defence against a source that already tiles and returns a degenerate
   answer for one. See backdrop/tile.py.

2. THE ROCK IS BAKED 2048 ROWS TALL, WHICH IS AN UPSCALE, ON PURPOSE. `vMax` is
   `height / arraySize`, and a LINEAR tap just inside `vMax` reaches half a texel into
   the never-written remainder of the array layer - which for a vertically tiled quad
   is EXACTLY the seam. height == arraySize makes vMax exactly 1.0. See
   backdrop/geometry.py, and note that the upscale costs nothing in VRAM (point 3) and
   nothing in sharpness: the content stays band-limited to its native 1000 rows, so the
   result cannot alias worse than the source could.

3. VRAM IS BUCKETED AND SQUARE. Everything here is sized so that `max(w,h)` lands in
   the 2048 bucket, where the diver's two sheets have ALREADY forced a
   2048x2048x4x15 = 251.7 MB SRGBA8 array and an identical RGBA8 one into existence.
   Five more layers of fifteen cost nothing. The alternatives were measured, not
   guessed: the rock at its native 300x1000 falls into the 1024 bucket and allocates two
   brand-new 209.7 MB arrays (+419.4 MB) AND keeps the contaminated seam; the
   silhouettes at their source 3000x3000 fall into the 4096 bucket and allocate a
   4096x4096x4x10 = 671.1 MB array for three flat masks.

4. THE SILHOUETTES ARE MASKS. Their opaque RGB is 16.7/19.0/22.0 with a standard
   deviation of 0.4 - a single flat colour plus dither, in all three files. Baking that
   colour in would hard-code a look AND drop it under the GI reflectance floor. They
   are baked as white RGB + the source alpha, so `Backdrop`'s per-layer draw colour is
   the whole of their appearance and can be tuned without a re-bake.

5. THE CLIFF TOP IS SIZED FROM ITS WIDTH, SHARES THE WALL'S GAIN, AND IS BAKED
   TWICE. It is drawn edge to edge with the wall at the waterline, so equal baked
   WIDTHS is what makes their texels the same size and the WALL'S OWN gain and ambient
   are what stop a brightness step appearing along the join. The second bake is its
   horizontal mirror, for the other side of the column. See bake_rock_top and
   backdrop/mirror.py.

6. SO IS THE WALL, AND THAT IS NEW. It used to get its right-hand copy from a
   180-degree rotation at the draw site. The crest could not, because 180 degrees is a
   horizontal mirror AND A VERTICAL FLIP and a summit cannot be upside down - so the
   two sides of the column were transformed DIFFERENTLY, and the wall under the
   right-hand crest ran upside down relative to the summit capping it. The owner, on a
   capture: "they should be placed from top to bottom to ensure the seams line up".
   Both now come from the bake, so each side of the column is one consistent horizontal
   mirror of the other, top and side together.

7. THE ROCK THAT REACHES THE FRAME EDGE IS A DIFFERENT TEXTURE FROM THE ROCK THAT
   MAKES THE SILHOUETTE. The wall art is not uniform across a tile - 43% of its width
   is empty or ragged - so tiling it outward from a fixed anchor makes what lands at
   the frame edge a function of `(visibleHalfWidth - anchor) mod TILE_WIDTH_METRES`,
   and it measured EMPTY at 16:9, the likeliest booth panel. `crop_body` cuts the
   wall's opaque interior out of the LIFTED output and mirror-doubles it, so there is
   one texture that is solid at every texel and tiles horizontally by reflection. See
   crop_body, which has the measurements.

Not a decision, a measurement: the rock's albedo is lifted so it clears the GI
reflectance floor. 44.2% of its opaque texels are under it as delivered. See
backdrop/reflectance.py.
"""
import argparse
import hashlib
import pathlib
import sys

import numpy as np
from PIL import Image, PngImagePlugin

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from backdrop import geometry, mirror, reflectance, tile
from spritesheet.colour import linear_to_srgb, resize_plane, srgb_to_linear, to_u8
# The same alpha-weighted resize the diver's bake uses, for the same reason: the rock's
# transparent texels are not uniformly black (they run to RGB 53) and its ragged inner
# edge is where the whole silhouette lives. `tools/tests/test_resample.py` already
# imports it under this name.
from spritesheet.resample import _alpha_weighted_resize, decode_normals

REPO = pathlib.Path(__file__).resolve().parent.parent
ROCK_DIR = REPO / "assets" / "rock"
SILHOUETTE_DIR = REPO / "assets" / "silhouettes"
SANDBANK_DIR = REPO / "assets" / "sandbank"
OUT_DIR = REPO / "src" / "main" / "resources" / "backdrop"

# Both HYPHENATED. The engine's `loadAll` auto-loader (Extensions.kt:446-448) keys on
# the substring `_normal` - an UNDERSCORE - and, when it matches, forces RGBA8 with TEN
# mip levels whatever the caller asked for. `rock-normal.png` does not trip it, so the
# explicit SpriteSheet-style constructor in `RockFace` governs. Same rule, and same
# reason, as `diver-normal.png`. We construct the assets explicitly rather than through
# loadAll, so this is belt and braces - but the belt is one rename away from failing.
# The wall, and its horizontal mirror for the other side of the column - see bake_rock's
# docstring for why the mirror is baked rather than taken as a 180-degree rotation at the
# draw site, which is what shipped and what put the right-hand cliff upside down.
ROCK_OUT = {
    "diffuse": "rock-diffuse.png",
    "normal": "rock-normal.png",
    "mirror_diffuse": "rock-mirror-diffuse.png",
    "mirror_normal": "rock-mirror-normal.png",
}
# The cliff top, and its horizontal mirror for the other side of the column. All eight
# hyphenated for the same reason: `_normal` with an UNDERSCORE trips the auto-loader.
ROCK_TOP_OUT = {
    "diffuse": "rock-top-diffuse.png",
    "normal": "rock-top-normal.png",
    "mirror_diffuse": "rock-top-mirror-diffuse.png",
    "mirror_normal": "rock-top-mirror-normal.png",
}
# The cliff BODY - the opaque-at-every-texel interior of the wall, mirror-doubled so it
# tiles horizontally. ONE pair, not two: it is its own horizontal mirror, so the same
# texture serves both sides of the column. See crop_body. Hyphenated, same rule.
ROCK_BODY_OUT = {
    "diffuse": "rock-body-diffuse.png",
    "normal": "rock-body-normal.png",
}
# The seabed at the foot of the trench. HYPHENATED before `normal`, same rule and same
# reason as every output above: `_normal` with an UNDERSCORE trips the auto-loader
# (Extensions.kt:446-448) into RGBA8 with TEN mip levels whatever the caller asked for.
#
# NO MIRROR PAIR. The sandbank is drawn as ONE quad exactly Framing.VISIBLE_WIDTH_METRES
# wide, centred on x = 0, at uTiling = vTiling = 1 - there is no second side and no
# repeat, so there is nothing to mirror. See the design's S3.2.
SANDBANK_OUT = {
    "diffuse": "sandbank-diffuse.png",
    "normal": "sandbank-normal.png",
}

DEFAULT_ROCK_HEIGHT = 2048
# Anything in (1024, 2048] joins the existing 2048 arrays; the bound is STRICT at the
# bottom (geometry.reuses_array), so 1024 itself would allocate a new 209.7 MB array.
# 1600 leaves the masks visibly smooth at every framebuffer we can reach and keeps the
# committed PNGs small.
DEFAULT_SILHOUETTE_MAX = 1600

# How much of DiveRenderer.wallColor's linear length the rock carries as a floor. The
# minimum output length is at least this fraction of 0.05787, and the floor with the
# project's own margin is 0.022, so 0.5 clears it by 31%. See backdrop/reflectance.py.
AMBIENT_FRACTION = 0.5

# A wrap seam may be no worse than this multiple of the tile's own interior row-to-row
# difference. 1.5 is loose enough not to trip on the blend's own rounding and tight
# enough that a botched period (the next-best candidate scores 5.5x) fails loudly.
MAX_SEAM_RATIO = 1.5

# The rock's mean relative luminance, as a multiple of DiveRenderer.wallColor's 0.034.
#
# 1.0 - "be exactly as bright as the flat slab you replace" - is the obvious anchor and it was
# measured to be wrong. wallColor was tuned in 0f07303 against the lighting of the time, and the
# light reaching the frame EDGE has fallen a long way since (god rays and the pearl-emitter rework
# both moved it): at 12 m, with the wall at that luminance, the rock renders at mean RGB
# (0.08, 0.88, 2.21) out of 255 against water at (1.9, 7.9, 46.5) beside it - 1/20th of its
# neighbour, i.e. the same "hard black bars that read as letterboxing" the earlier commit set out
# to fix, arrived at from the other direction.
#
# 2.0 doubles the albedo and still leaves the mean at 0.068, under lit shallow water's 0.085, so
# the wall is still a subordinate dark border rather than a bright frame - and unlike a flat slab
# it has a distribution, so its darkest texels stay well below the water while its lit faces
# read. Re-measure this if the lighting is reworked again; it is a relationship, not a constant.
LUMINANCE_FACTOR = 2.0


# How many texel columns at u = 0 the bake forces fully transparent. See clear_wrap_border.
BORDER_TEXEL_COLUMNS = 1


def clear_wrap_border(rgba: np.ndarray) -> np.ndarray:
    """
    Force the first [BORDER_TEXEL_COLUMNS] columns fully transparent.

    WHY, AND IT IS NOT COSMETIC. `DiveRenderer` draws the cliff as textured quads and the engine's
    `texture.frag` resolves tiling as `uv = texStart + texSize * fract(texCoord * texTiling)`.
    Where a quad's edge cuts THROUGH a pixel, that pixel's centre lies outside the quad, so the
    interpolated `texCoord.u` extrapolates just past 1.0 - and `fract` of that is ~0, which samples
    the texture's u = 0 column. On this art that column is solid stone, so every quad edge drew one
    partially-covered pixel of opaque rock where the art is fully transparent.

    Measured on the world surface at 3200x1800, in the sky above the waterline: RGBA(0, 0, 0, 64)
    at the left crest's inner edge, (0, 0, 0, 53) at the right's, (0, 0, 0, 167) at the cliff-top
    band's. Against the sunset that is a visible hairline down each cliff edge - which is what the
    owner had been pointing at.

    Proven by elimination rather than assumed. Skipping the crest removed its two lines; moving its
    quad 3 m (exactly 90 px, so the sub-pixel phase was unchanged) moved them 3 m with byte-
    identical alpha; nudging `uTiling` below the integer changed nothing, and so did insetting
    `uMax` by a whole texel - both of which rule out a plain `fract(1.0) = 0` and a tap past the
    texture array's sub-rectangle. Nudging the quad by HALF A PIXEL removed the lines entirely,
    which is what identifies partial pixel coverage as the trigger.

    A half-pixel nudge is not available as a fix: world geometry is in metres and `CameraRig` owns
    the only metre-to-pixel conversion, so any pixel snap would be resolution-dependent and the
    booth's resolution is not known. Making the wrapped-to column transparent fixes it for every
    resolution at once.

    ONLY THE BASE TEXTURES NEED IT. Each mirror's u = 0 is the base's u = 613, which is already
    inside the art's transparent margin - the wall's last 139 columns hold no alpha at all, and so
    do the crest's, whose art ends at the same column 475. The mirrors are produced from these
    outputs, so they inherit the border at u = 613 where it costs nothing.

    (These were 690 / 67 / 177 against the 691-wide pre-2026-08-12 art. RockFace.kt's
    BORDER_TEXEL_COLUMNS and ALPHA_TEXEL_COLUMNS are the authority; the tile is 614 wide now.)

    What it costs on the base: one texel column, 0.0195 m of world at TILE_WIDTH_METRES, i.e. about
    0.6 px on a 1080p panel. On the wall that lands on every horizontal tile join, where the flat
    backing sits behind it by construction; on the crest it lands where the cliff-top band is drawn
    behind it, which is the same rock. Neither can show through as anything but rock.
    """
    out = np.array(rgba, copy=True)
    out[:, :BORDER_TEXEL_COLUMNS, 3] = 0
    return out


def swizzle_yz(vectors: np.ndarray) -> np.ndarray:
    """
    Swap y and z on decoded normal vectors. `assets/sandbank/normal.png` needs it; nothing
    else in this bake does.

    THE DEFECT, AND IT IS INVISIBLE IN EVERY TEST AND IN EVERY STILL FRAME. Mean decoded
    vector after the sRGB->linear decode `decode_normals` already performs, over texels
    with alpha > 16, each vector normalised BEFORE averaging (averaging the raw components
    and normalising afterwards weights the longer vectors more and moves x and z in the
    third decimal):

        assets/rock/normal.png       x +0.161  y +0.058  z +0.755   |v| 0.994   Z-dominant, correct
        assets/rock/top_normal.png   x +0.191  y +0.072  z +0.675   |v| 0.996   Z-dominant, correct
        assets/sandbank/normal.png   x -0.0082 y +0.9004 z +0.3813  |v| 0.9993  Y-DOMINANT - wrong for us

    The sandbank row is stable to four decimals across three masks (alpha > 16,
    alpha > 200, and alpha > 200 restricted to rows below 120), so it is a property of the
    art and not of the crest band's partial coverage. The map is REAL, well-formed normal
    data - unit length once decoded correctly - whose dominant axis is +y rather than +z,
    which is exactly what exporting a seabed rendered TOP-DOWN gives, where a floor's
    surface normal points along world +Y. We view that seabed edge-on, as a vertical
    billboard, and the engine's normal maps are tangent-space with +Z out of the screen.

    Ship it unswizzled and the seabed is lit like a WALL - which looks like a lighting bug
    somewhere else entirely, and nothing downstream complains.

    THE SIGN: NO NEGATION. This CONFIRMS a settled repo convention rather than discovering
    one. `shaders/iridescence.frag:258` states it ("every normal map in this game is baked
    in the opposite, OpenGL convention - green HIGH where a surface faces UP-screen") and
    `render/PearlNormalMap.kt:174-214` settles it with a two-build capture probe at
    EPT_DEPTH=140 (+0.322 as first shipped against +0.416 negated). The trap that makes
    somebody re-derive the opposite: `pulseengine/shaders/lighting/global/
    radiance_cascades.frag` - a JAR resource, in `pulse-engine-0.13.0-QUADFIX.jar` in this
    workspace - dots normal.y straight against a rayDir built with `-sin` at :251, and
    :289-290 has no flip. That inference is wrong, and PearlNormalMap is where it was
    MEASURED wrong rather than argued wrong. Known-correct shipping art is the authority:
    rock-top-normal.png reads mean y +0.547 per column over each column's own topmost 12
    opaque rows, and the diver's crown reads +0.215..+0.237 against his fins' -0.085.

    After the swizzle the sandbank's mean vector is x -0.008, y +0.381, z +0.900:
    Z-dominant with a small upward tilt. KEEP THE TILT - do not project it out. The rock
    headland's own tilt is +0.547 at the crest and +0.381 on the face, the same magnitude,
    in art nobody has complained about; a floor drawn as a vertical billboard SHOULD tilt
    upward so it catches light from above like a floor rather than like a wall.

    Corroboration, with its own limitation: correlating N.L against the diffuse's baked-in
    shading over a sweep of light directions gives r = +0.858 raw and +0.914 swizzled. That
    confirms the swizzle and is BLIND to the sign - all four sign variants tie at 0.914.
    """
    return np.asarray(vectors, dtype=np.float64)[..., [0, 2, 1]]


def pad_top_to_height(rgba: np.ndarray, height: int, fill_rgb: tuple) -> np.ndarray:
    """
    Grow an RGBA image to `height` rows by prepending fully transparent ones.

    ## WHY THE CREST NEEDS THIS, AND IT IS A VRAM DECISION RATHER THAN A VISUAL ONE

    The crest is sized from its WIDTH, to match the wall's texel size at the join (see
    `bake_rock_top`), so its height is whatever the art's proportions give - and NOTHING
    keeps that on the right side of the engine's array-reuse test. `reuses_array` is
    `arraySize >= max_dim and max_dim > arraySize // 2`, and the second half is STRICT, so
    a crest whose largest side lands anywhere in 1..1024 is refused the 2048 arrays the
    diver's sheets have already forced into existence and allocates a 1024x1024x4x50 =
    209.7 MB array of its own instead - twice, once per format, because the normals are a
    different format from the albedo.

    That is not hypothetical. The art delivered on 2026-08-12 tiles at its full 1000 rows
    rather than at 889, which took the wall's baked width from 691 to 614 - and the crest's
    height with it, from 1152 to 1023. Two texels under the bound, for +419.4 MB, reported
    by the bake as one word changing from True to False in a line nobody would reread.

    ## WHY PAD RATHER THAN RESIZE

    Scaling the crest up to clear the bound would change its texel size and put a scale
    step at the join with the wall - the one thing `bake_rock_top`'s first decision exists
    to prevent. Padding leaves every existing texel exactly where it was.

    The padding goes at the TOP because the crest's quad is anchored by its BOTTOM edge, at
    `RockFace.WALL_TOP_DEPTH`. `TOP_HEIGHT_METRES` grows with the padding and
    `CREST_TOP_DEPTH` moves up by exactly the same amount, so every texel of actual art
    keeps the world position it had; what grows is empty sky above the summit.
    `CREST_SHOULDER_DEPTH` is derived from a texel ROW, which the padding shifts by the
    same count, so it too is unmoved. `RockFaceTest` re-derives all of it from the
    committed PNG.

    Padding downward would instead push the summit up out of the sea, and padding
    symmetrically would do half of that.
    """
    rows = rgba.shape[0]
    if height < rows:
        raise ValueError(f"cannot pad {rows} rows down to {height}")
    if height == rows:
        return np.array(rgba, copy=True)
    # Alpha 0 throughout; `fill_rgb` is what sits UNDER it, and it is not free to choose.
    # A LINEAR tap on the boundary row blends the padding's colour into the art's, so the
    # fill has to be the neutral value for the map in question: transparent black for the
    # albedo, and the flat (0, 0, 1) normal - which encodes to (128, 128, 255) - for the
    # normals, matching what `bake_rock_top` already writes for an uncovered texel. A
    # zero-length normal vector must never reach the renderer.
    pad = np.zeros((height - rows,) + rgba.shape[1:], dtype=rgba.dtype)
    for channel, value in enumerate(fill_rgb):
        pad[..., channel] = value
    return np.concatenate([pad, rgba], axis=0)


class SourceError(RuntimeError):
    pass


def load_rgba(path: pathlib.Path) -> np.ndarray:
    if not path.is_file():
        raise SourceError(
            f"source art not present at {path}.\n"
            f"       assets/ is gitignored - see this script's header for provenance."
        )
    return np.asarray(Image.open(path).convert("RGBA"))


def write_png(path: pathlib.Path, rgba: np.ndarray, meta: dict) -> None:
    """Deterministic write - identical inputs must give a byte-identical committed file."""
    info = PngImagePlugin.PngInfo()
    for key in sorted(meta):
        info.add_text(key, str(meta[key]))
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgba, mode="RGBA").save(
        path, format="PNG", optimize=False, compress_level=9, pnginfo=info,
    )


# How far the diffuse's alpha and the normal map's alpha may disagree, per texel.
#
# The check behind it is the one that catches a SLID CROP: the two maps are drawn as one
# rect submitted twice, so if the artist exported them from different framings the
# lighting slides off the rock by however far they moved. That failure moves the alpha
# EDGE, which is a step of up to 255 across hundreds of texels - it cannot hide inside a
# one-LSB tolerance.
#
# What does hide inside one LSB is eight-bit export noise, which is what the cliff top
# actually has: 3 texels of 150 000 differ, every one of them by exactly 1. Demanding
# bit equality there would be demanding the artist's exporter be deterministic across
# two files, which is not a property anyone promised. The wall still passes at 0.
MAX_ALPHA_MISMATCH = 1


def require_matching_alpha(name: str, diffuse: np.ndarray, normal: np.ndarray) -> None:
    """Both maps must be the same crop - see MAX_ALPHA_MISMATCH for what that means exactly."""
    if diffuse.shape != normal.shape:
        raise SourceError(f"{name} diffuse {diffuse.shape} != normal {normal.shape}")
    delta = np.abs(diffuse[..., 3].astype(int) - normal[..., 3].astype(int))
    worst = int(delta.max())
    if worst > MAX_ALPHA_MISMATCH:
        raise SourceError(
            f"{name} diffuse and normal alpha differ by up to {worst} over "
            f"{int((delta > MAX_ALPHA_MISMATCH).sum())} texels; the crop would slide"
        )


def bake_rock(height: int, period_override, luminance_factor: float) -> dict:
    """
    The vertically tiling cliff face, and its horizontal mirror for the other side of the
    column.

    THE MIRROR IS BAKED HERE, AND IT USED NOT TO BE. `DiveRenderer` drew the right-hand
    wall by rotating this texture 180 degrees, which `backdrop/mirror.py` explains is the
    one transform the engine applies to the geometry and the normal VECTORS together
    (`normal_map.vert` builds `normalRotation = rotMatrix(rotation + cameraAngle)`), and
    which is why the mirror was not needed at bake time.

    What that argument missed is that 180 degrees is a horizontal mirror AND A VERTICAL
    FLIP. The crest could never use it - a summit upside down is not a summit - so the
    crest was already baked mirrored while the wall was rotated. The two sides of the
    column were therefore transformed DIFFERENTLY, and on the right-hand side an
    upside-down wall ran up to a right-way-up summit. The owner, looking at a capture:
    "they should be placed from top to bottom to ensure the seams line up."

    So the wall is mirrored the same way the crest is, and `DiveRenderer` draws both
    sides at angle 0. Mirroring costs two more layers in the 2048 texture arrays the
    diver's sheets already allocate (see the filter/wrapping/format note printed by
    `kotlin_snippet`) and nothing else: a horizontal mirror moves whole rows, so the
    vertical wrap-blend this function solves for is preserved exactly.

    WHAT IS GIVEN UP, said plainly because it was a stated benefit of the rotation: 180
    degrees also flipped v, so the right-hand wall showed the tile upside down as well as
    mirrored, which broke up the symmetry of an exact 40 m mirror down both sides of the
    frame for free. An exact mirror is more conspicuous. That is the cost of the seams
    lining up, and the seams win.
    """
    diffuse = load_rgba(ROCK_DIR / "diffuse.png")
    normal = load_rgba(ROCK_DIR / "normal.png")
    require_matching_alpha("rock", diffuse, normal)

    src_h, src_w = diffuse.shape[:2]
    period = period_override or tile.choose_period(diffuse.astype(np.float64), MAX_SEAM_RATIO)
    already_tiles = period == src_h
    print(f"rock     source {src_w}x{src_h}, wrap period {period} "
          + ("(already a tile at full height - no crop, no blend)" if already_tiles else
             f"(overlap error {tile.overlap_error(diffuse, period):.3f}/255 over "
             f"{src_h - period} rows)"))

    diffuse_tile = tile.wrap_blend(diffuse, period)
    normal_tile = tile.wrap_blend(normal, period)
    for name, arr in (("diffuse", diffuse_tile), ("normal", normal_tile)):
        seam, interior = tile.seam_error(arr), tile.interior_error(arr)
        ratio = seam / interior
        print(f"         {name} wrap seam {seam:.3f}/255 vs interior {interior:.3f}/255 "
              f"(ratio {ratio:.2f})")
        if ratio > MAX_SEAM_RATIO:
            raise SourceError(
                f"rock {name} wrap seam is {ratio:.2f}x its interior adjacency "
                f"(limit {MAX_SEAM_RATIO}); the tile does not wrap at period {period}"
            )

    out_w, out_h = geometry.fit_height(src_w, period, height)
    print(f"         output {out_w}x{out_h}, bucket {geometry.bucket_for(max(out_w, out_h))}, "
          f"vMax {out_h / geometry.bucket_for(max(out_w, out_h)):.4f}")

    alpha = diffuse_tile[..., 3] / 255.0

    # --- Albedo -----------------------------------------------------------------------
    linear = srgb_to_linear(diffuse_tile[..., :3] / 255.0)
    resized, resized_alpha, _ = _alpha_weighted_resize(linear, alpha, (out_w, out_h))

    # Clip the ringing before the lift, not after. LANCZOS undershoots, and dividing a small
    # negative premultiplied value by an alpha that is itself near zero magnifies it: without
    # this, twenty texels along the ragged edge come out at exactly RGB 0 with an alpha of 1-3,
    # because `ambient + gain * (a big negative)` lands back on zero. A negative albedo is
    # meaningless in its own right, and a zero one is precisely what the GI reflectance floor
    # exists to catch - so clearing the floor "everywhere" has to mean everywhere.
    resized = np.clip(resized, 0.0, None)

    wall_linear = srgb_to_linear(np.array(reflectance.WALL_COLOR_SRGB))
    ambient = AMBIENT_FRACTION * wall_linear
    opaque = resized_alpha > 0.5
    target_luminance = luminance_factor * float(reflectance.luminance(wall_linear))
    gain = reflectance.solve_gain(
        float(reflectance.luminance(resized[opaque]).mean()),
        float(reflectance.luminance(ambient)),
        target_luminance,
    )
    lifted = reflectance.lift(resized, ambient, gain)
    diffuse_out = np.dstack([to_u8(linear_to_srgb(lifted)), to_u8(resized_alpha)])

    # Measure what was actually WRITTEN, after the 8-bit sRGB round trip - the shader
    # sees the quantised values, not the floats above.
    written = srgb_to_linear(diffuse_out[..., :3] / 255.0)
    lengths = reflectance.linear_length(written[opaque])
    below = int((lengths < reflectance.GI_REFLECTANCE_FLOOR).sum())
    source_lengths = reflectance.linear_length(srgb_to_linear(diffuse_tile[..., :3] / 255.0))
    source_opaque = source_lengths[diffuse_tile[..., 3] > 127]
    print(f"         reflectance: ambient {AMBIENT_FRACTION:.2f}*wallColor "
          f"(|.| {reflectance.linear_length(ambient):.5f}), gain {gain:.3f}, "
          f"target luminance {luminance_factor:.2f}*wallColor")
    print(f"         source  linear length min {source_opaque.min():.5f} "
          f"median {np.median(source_opaque):.5f} max {source_opaque.max():.5f}; "
          f"{(source_opaque < reflectance.GI_REFLECTANCE_FLOOR).sum()} of "
          f"{source_opaque.size} texels ({100 * (source_opaque < reflectance.GI_REFLECTANCE_FLOOR).mean():.2f}%) "
          f"under the {reflectance.GI_REFLECTANCE_FLOOR} floor")
    print(f"         baked   linear length min {lengths.min():.5f} "
          f"median {np.median(lengths):.5f} max {lengths.max():.5f}; "
          f"{below} of {lengths.size} texels under the floor")
    print(f"         baked   mean luminance {reflectance.luminance(written[opaque]).mean():.5f} "
          f"against wallColor's {reflectance.luminance(wall_linear):.5f} "
          f"and lit shallow water's 0.085")
    if below:
        raise SourceError(
            f"{below} baked rock texels are still under the GI reflectance floor; "
            f"raise AMBIENT_FRACTION"
        )

    # --- Normals ----------------------------------------------------------------------
    # sRGB-ENCODED, exactly like the diver's: decoding raw gives mean |v| 1.2121 with
    # 1.2% of vectors within 1% of unit length, linearizing first gives 0.9937 and 67.8%.
    vectors = decode_normals(normal_tile[..., :3])
    resized_n, resized_alpha_n, covered = _alpha_weighted_resize(vectors, alpha, (out_w, out_h))
    length = np.linalg.norm(resized_n, axis=-1, keepdims=True)
    unit = np.where(length > 1e-6, resized_n / np.maximum(length, 1e-6), np.array([0.0, 0.0, 1.0]))
    unit = np.where(covered[..., None], unit, np.array([0.0, 0.0, 1.0]))
    normal_out = np.dstack([to_u8((unit + 1.0) * 0.5), to_u8(resized_alpha_n)])
    # Read back the way the SHADER will: RGBA8 is handed to it unchanged, so the decode
    # is the plain (v+1)/2 inverse and must NOT linearize a second time.
    baked_v = (normal_out[..., :3] / 255.0) * 2.0 - 1.0
    print(f"         normals mean |v| {np.linalg.norm(baked_v[opaque], axis=-1).mean():.4f} "
          f"(RGBA8: stored linearly, the GPU hands these bytes to the shader unchanged)")

    diffuse_out = clear_wrap_border(diffuse_out)
    normal_out = clear_wrap_border(normal_out)

    return {
        "diffuse": diffuse_out,
        "normal": normal_out,
        "mirror_diffuse": mirror.mirror_diffuse(diffuse_out),
        "mirror_normal": mirror.mirror_normal(normal_out),
        "period": period,
        "size": (out_w, out_h),
        "gain": gain,
        "ambient": ambient,
    }


def first_not_solid_column(rgba: np.ndarray) -> int:
    """
    The lowest texel column at or past the wrap border that is NOT alpha 255 in every row.

    Derived from the array rather than hard-coded (it is 264 on the art as delivered - it was 396
    on the pre-2026-08-12 art, which is exactly the staleness this docstring is warning about)
    because
    the whole point of `crop_body` is that its output is opaque at every texel; a number typed
    here would be a second copy of a property of the ART, and a redrawn cliff or a different
    `--rock-height` would leave it stale silently - the crop would simply start including the
    ragged edge, and the frame edge would start showing water again at some window widths.

    Counted from BORDER_TEXEL_COLUMNS, not from 0: the bake forces column 0 fully transparent
    (see clear_wrap_border), so "solid at every row" cannot begin before column 1.
    """
    solid = (rgba[..., 3] == 255).all(axis=0)
    for x in range(BORDER_TEXEL_COLUMNS, rgba.shape[1]):
        if not solid[x]:
            return x
    raise SourceError(
        "no column of the baked wall is transparent anywhere - the cliff has no ragged edge, "
        "so there is nothing to crop the solid body out of"
    )


def crop_body(diffuse_out: np.ndarray, normal_out: np.ndarray) -> dict:
    """
    THE CLIFF BODY: the wall's opaque interior, mirror-doubled, for tiling outward to the
    frame edge at any aspect ratio.

    ## WHAT IT IS FOR

    The wall's own art is not uniform across a tile. Going outward from a tile's inner end it
    is EMPTY for 139 texel columns, RAGGED for the next 211, and only then solid for the
    remaining 264 - 614 in total. Tiling that art outward from a fixed anchor therefore makes
    what lands at the FRAME EDGE a function of `(visibleHalfWidth - anchor) mod
    TILE_WIDTH_METRES` - and 6.836 of those 11.992 m, 57% of the period, is not solid.
    RockFace.kt's class doc carries the same three numbers; it is the authority.

    Measured on the code this replaces, at the frame edge:

        4:3  no rock at all | 16:10 solid | 16:9 EMPTY | 21:9 RAGGED | 32:9 EMPTY

    (Those five samples were taken on the OLD art - a 13.496 m tile, 691 columns split
    67/228/396, where 43% of the period was not solid. The 2026-08-12 re-bake moved every one
    of those numbers and would move which five samples that row reports. It cannot move the
    mechanism, which is the modulus, and that is the only reason the row is still here.)

    16:9 is the likeliest booth panel. Five successive commits moved the anchor or swapped
    the mirror; each of them relocated the hole rather than closing it, because a modulus
    cannot be closed by choosing a phase. The structural fix is two different textures: the
    wall's own art drawn ONCE per side for the ragged silhouette, and THIS - opaque at every
    texel - tiled outward from just inside it, with a whole-tile count rounded UP. Coverage
    then holds by construction of `ceil`, with no modulus left in the derivation.

    ## WHY IT IS MIRROR-DOUBLED

    The art has NO horizontal wrap period to find: `tile.find_wrap_period` scores its best
    candidate at 22.1/255 against an interior adjacency of 1.26/255 - a ratio of 17.5x, where
    the vertical direction (which the wall does tile in) manages 1.5x. So a butt join of the
    crop against itself is a hard vertical line every tile.

    `[C | mirror(C)]` buys four things at once, and each of them is load-bearing:

      1. it tiles horizontally with a seam that is an exact REFLECTION rather than a step -
         the last column of one copy is the mirror of the first column of the next;
      2. it is its own horizontal mirror, so ONE pair serves both sides of the column
         (2 array layers, not 4) - `RockFaceTest` asserts that on the committed file;
      3. its join with the wall's edge art is an exact reflection too, because
         `RockFace.BODY_INNER_HALF_WIDTH` puts the body's inner edge exactly one texel inward
         of the edge tile's outward end, i.e. against the edge art's own column
         BORDER_TEXEL_COLUMNS, which is precisely this texture's outermost column;
      4. it inherits the wall's v phase for free - the crop takes whole columns, all 2048
         rows, so the vertical wrap blend `bake_rock` solved for is untouched.

    ## CROPPED FROM THE BAKE'S OUTPUT, NOT FROM THE SOURCE

    `diffuse_out`/`normal_out` are the LIFTED, resized arrays `bake_rock` is about to write.
    Cropping them costs no second resample (a second LANCZOS pass over an already-resampled
    image is a second set of ringing artefacts) and inherits the wall's gain and ambient
    EXACTLY - so the body and the wall it abuts cannot differ in brightness, which is the
    same class of defect as the flat cliff top being half the rock's luminance (`41849a7`).
    """
    # The bound comes from the ALBEDO and is applied to both, rather than being solved
    # separately for each: the two are drawn as one rect submitted twice, so a crop that
    # differed between them would slide the lighting off the rock. If the normal map is not
    # solid that far - the resize can leave the two alphas a bit apart even though
    # `require_matching_alpha` bounds the SOURCE's disagreement at one LSB - the opacity check
    # below is what catches it, and that is the check worth having: it states the property the
    # body exists for rather than a relationship between two intermediate numbers.
    first = first_not_solid_column(diffuse_out)

    crop_diffuse = diffuse_out[:, BORDER_TEXEL_COLUMNS:first, :]
    crop_normal = normal_out[:, BORDER_TEXEL_COLUMNS:first, :]
    for name, arr in (("diffuse", crop_diffuse), ("normal", crop_normal)):
        holes = int((arr[..., 3] != 255).sum())
        if holes:
            raise SourceError(
                f"{holes} texels of the cropped rock body {name} are not fully opaque; the "
                f"body is what guarantees rock at the frame edge, so a single transparent "
                f"texel in it is a hole in that guarantee"
            )

    body_diffuse = np.ascontiguousarray(
        np.concatenate([crop_diffuse, mirror.mirror_diffuse(crop_diffuse)], axis=1)
    )
    body_normal = np.ascontiguousarray(
        np.concatenate([crop_normal, mirror.mirror_normal(crop_normal)], axis=1)
    )

    h, w = body_diffuse.shape[:2]
    bucket = geometry.bucket_for(max(w, h))
    print(f"rock body columns [{BORDER_TEXEL_COLUMNS}, {first}) of {diffuse_out.shape[1]}, "
          f"mirror-doubled -> {w}x{h}, bucket {bucket}, reuses existing 2048 array: "
          f"{geometry.reuses_array(max(w, h), 2048)}")
    print(f"         every texel opaque: "
          f"{bool((body_diffuse[..., 3] == 255).all() and (body_normal[..., 3] == 255).all())}; "
          f"self-mirroring albedo: {bool((body_diffuse == body_diffuse[:, ::-1, :]).all())}")

    written = srgb_to_linear(body_diffuse[..., :3] / 255.0)
    lengths = reflectance.linear_length(written)
    below = int((lengths < reflectance.GI_REFLECTANCE_FLOOR).sum())
    print(f"         baked   linear length min {lengths.min():.5f} "
          f"median {np.median(lengths):.5f} max {lengths.max():.5f}; "
          f"{below} of {lengths.size} texels under the {reflectance.GI_REFLECTANCE_FLOOR} floor")
    print(f"         baked   mean luminance {reflectance.luminance(written).mean():.5f} "
          f"(the WALL's own gain and ambient, cropped - not solved again)")
    if below:
        raise SourceError(
            f"{below} baked rock-body texels are under the GI reflectance floor; "
            f"raise AMBIENT_FRACTION"
        )

    return {
        "diffuse": body_diffuse,
        "normal": body_normal,
        "size": (w, h),
        "columns": (BORDER_TEXEL_COLUMNS, first),
    }


def bake_rock_top(width: int, height: int, gain: float, ambient: np.ndarray) -> dict:
    """
    The cliff TOP: the summit that caps each wall at the waterline, and its horizontal
    mirror for the other side of the column.

    THREE DECISIONS, EACH FOR A REASON THE WALL'S BAKE DOES NOT SHARE.

    1. IT IS SIZED FROM ITS WIDTH, NOT ITS HEIGHT. The top is drawn exactly as wide as
       the wall tile (`RockFace.TILE_WIDTH_METRES`), so baking it to the wall's own
       output width is what makes their texels the same size and keeps the join
       invisible. Its height then follows from the art's 300x500 proportions and
       DECIDES how tall the cliff is - it is a consequence of the drawing, not a number
       anybody picked.

       The CANVAS is then padded up to the wall's height with transparent rows, which
       does not touch that: the art keeps its size and its world position, and only
       empty sky is added above the summit. That is a VRAM constraint, not a visual
       one - see `pad_top_to_height` for the 419.4 MB it exists to stop.

    2. IT INHERITS THE WALL'S GAIN AND AMBIENT RATHER THAN SOLVING FOR ITS OWN MEAN.
       `bake_rock` solves a gain so the wall's mean luminance lands on a multiple of
       `DiveRenderer.wallColor`'s. Doing that again here would give the top a different
       gain, because it is a different crop of rock with a different mean - and the two
       are drawn edge to edge at the waterline, where a brightness step between them is
       a horizontal line across the cliff. The same affine lift on both is what makes
       the join continuous by construction. The top's own resulting mean is printed, so
       the difference is measured rather than assumed away.

    3. IT IS BAKED TWICE, THE SECOND TIME MIRRORED. See `backdrop/mirror.py`: the wall
       USED TO get its right-hand copy from a 180-degree rotation at the draw site,
       which is a horizontal mirror AND a vertical flip. A summit cannot be flipped
       vertically, and there is no horizontal-only mirror available at the draw site
       that also transforms the normals - so it is done here, where the negation of the
       normal's x component is exact and testable. The wall is baked mirrored too now
       (decision 6 above), precisely because having the two sides transformed
       differently is what misaligned the seams; both sides draw at angle 0.

    NOT baked as a tile and NOT wrap-blended: it is drawn once, at the top of the wall,
    and has no seam to itself. That also means its height need not equal the array size
    the way `TEXELS_TALL` does - `vMax` only matters where `v` wraps, and this `v` runs
    0 to 1 exactly once.
    """
    diffuse = load_rgba(ROCK_DIR / "top_diffuse.png")
    normal = load_rgba(ROCK_DIR / "top_normal.png")
    require_matching_alpha("rock top", diffuse, normal)

    src_h, src_w = diffuse.shape[:2]
    out_w, art_h = geometry.fit_width(src_w, src_h, width)

    # The art's own height is whatever its proportions give, and that is allowed to land on
    # the wrong side of the engine's array-reuse bound - see `pad_top_to_height`, which is
    # the whole reason this is not just `art_h`. Padding to the WALL's height settles it by
    # construction at any source aspect, rather than by a number that happens to work today.
    out_h = max(art_h, height)
    bucket = geometry.bucket_for(max(out_w, out_h))
    reuses = geometry.reuses_array(max(out_w, out_h), 2048)
    print(f"rock top source {src_w}x{src_h} -> {out_w}x{art_h}"
          + (f", padded to {out_w}x{out_h}" if out_h != art_h else "")
          + f", bucket {bucket}, reuses existing 2048 array: {reuses}")
    if not reuses:
        raise SourceError(
            f"the crest at {out_w}x{out_h} does not land in the 2048 texture arrays the "
            f"diver's sheets already allocate, so it would allocate a "
            f"{bucket}x{bucket}x4x{geometry.CAPACITIES[bucket]} array PER FORMAT "
            f"({2 * geometry.array_bytes(bucket) / 1e6:.1f} MB). See pad_top_to_height."
        )

    alpha = diffuse[..., 3] / 255.0

    linear = srgb_to_linear(diffuse[..., :3] / 255.0)
    resized, resized_alpha, _ = _alpha_weighted_resize(linear, alpha, (out_w, art_h))
    resized = np.clip(resized, 0.0, None)   # LANCZOS undershoot, before the lift - see bake_rock
    lifted = reflectance.lift(resized, ambient, gain)
    diffuse_out = np.dstack([to_u8(linear_to_srgb(lifted)), to_u8(resized_alpha)])

    written = srgb_to_linear(diffuse_out[..., :3] / 255.0)
    opaque = resized_alpha > 0.5
    lengths = reflectance.linear_length(written[opaque])
    below = int((lengths < reflectance.GI_REFLECTANCE_FLOOR).sum())
    wall_linear = srgb_to_linear(np.array(reflectance.WALL_COLOR_SRGB))
    print(f"         reflectance: the WALL's gain {gain:.3f} and ambient "
          f"(|.| {reflectance.linear_length(ambient):.5f}), so the join cannot step")
    print(f"         baked   linear length min {lengths.min():.5f} "
          f"median {np.median(lengths):.5f} max {lengths.max():.5f}; "
          f"{below} of {lengths.size} texels under the {reflectance.GI_REFLECTANCE_FLOOR} floor")
    print(f"         baked   mean luminance {reflectance.luminance(written[opaque]).mean():.5f} "
          f"against wallColor's {reflectance.luminance(wall_linear):.5f}")
    if below:
        raise SourceError(
            f"{below} baked rock-top texels are still under the GI reflectance floor; "
            f"raise AMBIENT_FRACTION"
        )

    vectors = decode_normals(normal[..., :3])
    resized_n, resized_alpha_n, covered = _alpha_weighted_resize(vectors, alpha, (out_w, art_h))
    length = np.linalg.norm(resized_n, axis=-1, keepdims=True)
    unit = np.where(length > 1e-6, resized_n / np.maximum(length, 1e-6), np.array([0.0, 0.0, 1.0]))
    unit = np.where(covered[..., None], unit, np.array([0.0, 0.0, 1.0]))
    normal_out = np.dstack([to_u8((unit + 1.0) * 0.5), to_u8(resized_alpha_n)])
    baked_v = (normal_out[..., :3] / 255.0) * 2.0 - 1.0
    print(f"         normals mean |v| {np.linalg.norm(baked_v[opaque], axis=-1).mean():.4f}")

    # After every measurement above, so the printed statistics describe the ART and are not
    # diluted by padding, and before the mirrors, so they inherit it.
    diffuse_out = pad_top_to_height(diffuse_out, out_h, (0, 0, 0))
    normal_out = pad_top_to_height(normal_out, out_h, (128, 128, 255))

    diffuse_out = clear_wrap_border(diffuse_out)
    normal_out = clear_wrap_border(normal_out)

    return {
        "diffuse": diffuse_out,
        "normal": normal_out,
        "mirror_diffuse": mirror.mirror_diffuse(diffuse_out),
        "mirror_normal": mirror.mirror_normal(normal_out),
        "size": (out_w, out_h),
    }


def bake_sandbank(gain: float, ambient: np.ndarray) -> dict:
    """
    The seabed at the foot of the trench: 2000x500, committed at its SOURCE SIZE.

    NO RESAMPLE. `TextureBank.getOrCreateTextureArrayFor` reuses an array only when
    format, filter, wrapping AND maxMipLevels all match and `max(w, h) > arraySize / 2`
    (geometry.reuses_array, whose lower bound is STRICT). max(2000, 500) = 2000, which is
    > 1024 and <= 2048, so both sheets take free layers in the 2048 SRGBA8 and 2048 RGBA8
    arrays the diver's sheets already forced into existence. There is therefore no size to
    fit to, and running a scale-1 Lanczos pass anyway would put a filter between the
    artist's texels and the committed ones for no gain. For the record, from
    geometry.CAPACITIES: a 1024-bucket output would allocate 209.7 MB per format, a
    4096-bucket output 671.1 MB.

    IT INHERITS THE WALL'S GAIN AND AMBIENT, exactly as `bake_rock_top` does and for the
    same reason (that function's decision 2). Solving a second gain against the sandbank's
    own mean would give the seabed a different transfer function from the cliff it butts
    against at BOTH frame edges, and a brightness step along a join between two lit
    surfaces drawn edge to edge is a horizontal line across the picture. Inheriting
    preserves the relative brightness the artist authored between the two renders - sand
    stays brighter than stone, because it is brighter in the source - and introduces no
    new tunable. `crop_body` is NOT a second precedent for this: it takes the wall's
    already-lifted OUTPUT arrays and inherits the lift by construction, never through a
    parameter.

    NO `clear_wrap_border`, AND THAT IS DELIBERATE. The mechanism that function exists for
    is real here too - `texture.frag` resolves `uv = texStart + texSize * fract(texCoord *
    texTiling)`, and where a quad's edge cuts through a pixel the interpolated u
    extrapolates just past 1.0, whose fract is ~0. But the sandbank is the one quad in this
    game whose OUTERMOST COLUMNS ARE SUPPOSED TO BE OPAQUE and land exactly on the frame
    edge (the quad is exactly Framing.VISIBLE_WIDTH_METRES wide, and that width is a
    maximum at every aspect). Clearing them would put an alpha-0 column at the extreme
    pixel of the screen - which is precisely the inverted hairline
    Framing.VISIBLE_WIDTH_METRES records being measured at 16:9, 2.389 and 32:9, and why
    that cap is 2 x BODY_INNER_HALF_WIDTH and not 2 x CREST_OUTER_HALF_WIDTH. The sand's
    own extrapolated column draws sand where sand belongs.
    """
    diffuse = load_rgba(SANDBANK_DIR / "diffuse.png")
    normal = load_rgba(SANDBANK_DIR / "normal.png")
    require_matching_alpha("sandbank", diffuse, normal)

    src_h, src_w = diffuse.shape[:2]
    bucket = geometry.bucket_for(max(src_w, src_h))
    reuses = geometry.reuses_array(max(src_w, src_h), 2048)
    print(f"sandbank source {src_w}x{src_h}, committed at source size (no resample), "
          f"bucket {bucket}, reuses existing 2048 array: {reuses}")
    if not reuses:
        raise SourceError(
            f"the sandbank at {src_w}x{src_h} does not land in the 2048 texture arrays the "
            f"diver's sheets already allocate, so it would allocate a "
            f"{bucket}x{bucket}x4x{geometry.CAPACITIES[bucket]} array PER FORMAT "
            f"({2 * geometry.array_bytes(bucket) / 1e6:.1f} MB). Re-cut the source art."
        )

    # --- Albedo -------------------------------------------------------------------------
    # The alpha channel is carried through as the source's own uint8 bytes rather than
    # round-tripped through a float: there is no resample, so there is nothing to change it.
    alpha = diffuse[..., 3] / 255.0
    opaque = alpha > 0.5

    linear = srgb_to_linear(diffuse[..., :3] / 255.0)
    lifted = reflectance.lift(linear, ambient, gain)
    diffuse_out = np.dstack([to_u8(linear_to_srgb(lifted)), diffuse[..., 3]])

    # Measure what was actually WRITTEN, after the 8-bit sRGB round trip - the shader sees
    # the quantised values, not the floats above.
    written = srgb_to_linear(diffuse_out[..., :3] / 255.0)
    lengths = reflectance.linear_length(written[opaque])
    below = int((lengths < reflectance.GI_REFLECTANCE_FLOOR).sum())
    print(f"         reflectance: the WALL's gain {gain:.3f} and ambient "
          f"(|.| {reflectance.linear_length(ambient):.5f}), so the join cannot step")
    print(f"         baked   linear length min {lengths.min():.5f} "
          f"median {np.median(lengths):.5f} max {lengths.max():.5f}; "
          f"{below} of {lengths.size} texels under the {reflectance.GI_REFLECTANCE_FLOOR} floor")
    print(f"         baked   mean luminance {reflectance.luminance(written[opaque]).mean():.5f}")
    if below:
        raise SourceError(
            f"{below} baked SAND texels are still under the GI reflectance floor. The "
            f"sandbank inherits the wall's lift (see this function's docstring), so the "
            f"only lever is AMBIENT_FRACTION - which raises the cliff, the crest and the "
            f"body too. If that is not acceptable, the inherit decision is what has to be "
            f"reopened, not this threshold."
        )

    # --- Normals ------------------------------------------------------------------------
    # sRGB-decode, THEN swizzle, THEN renormalise. A permutation preserves norm, so the
    # renormalise cannot change anything the swizzle did; it is here because bake_rock's
    # normals block does it, it costs nothing, and it is the one line that guarantees no
    # zero-length vector can reach the renderer whatever a future edit puts above it.
    vectors = swizzle_yz(decode_normals(normal[..., :3]))
    length = np.linalg.norm(vectors, axis=-1, keepdims=True)
    unit = np.where(length > 1e-6, vectors / np.maximum(length, 1e-6), np.array([0.0, 0.0, 1.0]))
    normal_out = np.dstack([to_u8((unit + 1.0) * 0.5), normal[..., 3]])

    # Read back the way the SHADER will: RGBA8 is handed to it unchanged, so the decode is
    # the plain (v+1)/2 inverse and must NOT linearize a second time. Each vector is
    # normalised BEFORE averaging - the same method the docstring's table uses, and the
    # reason its numbers are reproducible.
    baked_v = (normal_out[..., :3] / 255.0) * 2.0 - 1.0
    sampled = baked_v[opaque]
    mean_length = float(np.linalg.norm(sampled, axis=-1).mean())
    mean_unit = (sampled / np.linalg.norm(sampled, axis=-1, keepdims=True)).mean(axis=0)
    print(f"         normals mean |v| {mean_length:.4f}, mean unit vector "
          f"x {mean_unit[0]:+.4f} y {mean_unit[1]:+.4f} z {mean_unit[2]:+.4f} "
          f"(must be Z-DOMINANT with POSITIVE y - see swizzle_yz)")

    return {
        "diffuse": diffuse_out,
        "normal": normal_out,
        "size": (src_w, src_h),
    }


def bake_silhouettes(max_dim: int) -> list:
    out = []
    for index in (1, 2, 3):
        src = load_rgba(SILHOUETTE_DIR / f"{index}.png")
        src_h, src_w = src.shape[:2]
        w, h = geometry.fit_max_dim(src_w, src_h, max_dim)
        alpha = np.clip(resize_plane((src[..., 3] / 255.0).astype(np.float32), (w, h)), 0.0, 1.0)
        rgb = np.full((h, w, 3), 255, dtype=np.uint8)
        bucket = geometry.bucket_for(max(w, h))
        print(f"layer {index}  {src_w}x{src_h} -> {w}x{h}  bucket {bucket}  "
              f"reuses existing 2048 array: {geometry.reuses_array(max(w, h), 2048)}  "
              f"(source bucket {geometry.bucket_for(max(src_w, src_h))} would cost "
              f"{geometry.array_bytes(geometry.bucket_for(max(src_w, src_h))) / 1e6:.1f} MB)")
        out.append((index, np.dstack([rgb, to_u8(alpha)])))
    return out


def kotlin_snippet(rock_w: int, rock_h: int, top_w: int, top_h: int, body_w: int, body_h: int,
                    sand_w: int, sand_h: int) -> str:
    """
    The call site to copy verbatim. Argument order is (format, maxMipLevels) and
    maxMipLevels is 1, never 0 - the same two silent-and-fatal facts the diver's bake
    prints, for the same reason.
    """
    return f"""
Kotlin - copy verbatim:

    Texture("/backdrop/rock-diffuse.png", "rock_diffuse",
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.SRGBA8, maxMipLevels = 1)
    Texture("/backdrop/rock-normal.png", "rock_normal",
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.RGBA8, maxMipLevels = 1)

    Texture("/backdrop/rock-top-diffuse.png", "rock_top_diffuse",       // and -mirror-
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.SRGBA8, maxMipLevels = 1)
    Texture("/backdrop/rock-top-normal.png", "rock_top_normal",         // and -mirror-
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.RGBA8, maxMipLevels = 1)

    Texture("/backdrop/rock-body-diffuse.png", "rock_body_diffuse",     // NO mirror: it is
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,   // its own
        format = TextureFormat.SRGBA8, maxMipLevels = 1)                // horizontal mirror
    Texture("/backdrop/rock-body-normal.png", "rock_body_normal",
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.RGBA8, maxMipLevels = 1)

    Texture("/backdrop/sandbank-diffuse.png", "sandbank_diffuse",   // NO mirror, NO tiling:
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,   // one quad,
        format = TextureFormat.SRGBA8, maxMipLevels = 1)            // uTiling = vTiling = 1
    Texture("/backdrop/sandbank-normal.png", "sandbank_normal",
        filter = TextureFilter.LINEAR, wrapping = TextureWrapping.CLAMP_TO_EDGE,
        format = TextureFormat.RGBA8, maxMipLevels = 1)

    const val ROCK_TEXELS_WIDE = {rock_w}
    const val ROCK_TEXELS_TALL = {rock_h}      // == the 2048 array size, so vMax is exactly 1.0
    const val TOP_TEXELS_WIDE  = {top_w}       // == ROCK_TEXELS_WIDE, so the texels match at the join
    const val TOP_TEXELS_TALL  = {top_h}       // decides how tall the cliff is, in metres
    const val BODY_TEXELS_WIDE = {body_w}      // == 2 * (OPAQUE_TEXEL_COLUMNS - BORDER_TEXEL_COLUMNS)
    const val BODY_TEXELS_TALL = {body_h}      // == ROCK_TEXELS_TALL, so the body keeps the wall's v phase
    const val SANDBANK_TEXELS_WIDE = {sand_w}   // the quad is Framing.VISIBLE_WIDTH_METRES across
    const val SANDBANK_TEXELS_TALL = {sand_h}   // the height follows: WIDTH * TALL / WIDE

FILTER, WRAPPING AND maxMipLevels MUST MATCH THE DIVER'S SHEETS EXACTLY.
TextureBank.getOrCreateTextureArrayFor reuses an array only when format, filter,
wrapping AND maxMipLevels all agree; differ in any one of them and these textures
allocate a second 251.7 MB array of their own instead of taking a free layer in the
diver's.

maxMipLevels is 1, never 0: TextureArray computes min(maxMipLevels, floor(log2(size))+1)
with no coerceAtLeast(1) and passes it to glTexStorage3D as `levels`. levels = 0 is
GL_INVALID_VALUE - no storage allocated, no error logged."""


def bake(rock_height: int, silhouette_max: int, period_override, luminance_factor: float) -> int:
    rock = bake_rock(rock_height, period_override, luminance_factor)
    # The top is sized from the WALL's baked width, not from an argument: equal widths
    # is what makes the two textures' texels the same size when both are drawn
    # TILE_WIDTH_METRES across, and it is not a number anybody should be able to get
    # wrong from the command line.
    top = bake_rock_top(rock["size"][0], rock["size"][1], rock["gain"], rock["ambient"])
    # From the WALL'S OWN OUTPUT arrays, not from the source art: no second resample, and the
    # gain and ambient are inherited exactly rather than solved again. See crop_body.
    body = crop_body(rock["diffuse"], rock["normal"])
    layers = bake_silhouettes(silhouette_max)
    # The WALL's gain and ambient, an explicit parameter pair, exactly as bake_rock_top
    # receives them one line above. See bake_sandbank's docstring for why the seabed does
    # not solve a gain of its own.
    sand = bake_sandbank(rock["gain"], rock["ambient"])

    # The shared fingerprint, over the ROCK and SILHOUETTE sources only. The sandbank is
    # deliberately OUTSIDE it - see sand_meta below - so adding assets/sandbank/*.png here
    # would rewrite all thirteen files below for a metadata change alone.
    fingerprint = hashlib.sha256()
    for path in sorted(ROCK_DIR.glob("*.png")) + sorted(SILHOUETTE_DIR.glob("*.png")):
        fingerprint.update(path.read_bytes())
    meta = {
        "ept:rock_period": rock["period"],
        "ept:rock_size": "x".join(str(n) for n in rock["size"]),
        "ept:rock_top_size": "x".join(str(n) for n in top["size"]),
        "ept:silhouette_max": silhouette_max,
        "ept:source_sha256": fingerprint.hexdigest()[:16],
    }

    # THE SANDBANK GETS ITS OWN DICT, AND ITS OWN FINGERPRINT OVER ITS OWN SOURCE
    # DIRECTORY. It must NOT join the shared one and must not touch the shared one's glob.
    #
    # Read the ALL-THIRTEEN comment below and it is not arguing for one dict in general: it
    # is arguing that the BODY has no claim to a dict of its own, because the body is a
    # crop of the wall and therefore carries the wall's provenance. The sandbank is
    # independent art from a separate source directory that no existing output derives
    # from, so it has the claim the body lacks - and joining the shared glob would change
    # `ept:source_sha256`, which changes the metadata, which rewrites all thirteen
    # committed PNGs, spending the exact "a re-bake is a no-op" property that comment is
    # written to protect in order to cite it. With this, re-baking rewrites two files and
    # only two.
    sand_fingerprint = hashlib.sha256()
    for path in sorted(SANDBANK_DIR.glob("*.png")):
        sand_fingerprint.update(path.read_bytes())
    sand_meta = {
        "ept:sandbank_size": "x".join(str(n) for n in sand["size"]),
        "ept:source_sha256": sand_fingerprint.hexdigest()[:16],
    }

    for key, name in ROCK_OUT.items():
        write_png(OUT_DIR / name, rock[key], meta)
    for key, name in ROCK_TOP_OUT.items():
        write_png(OUT_DIR / name, top[key], meta)
    # The SAME meta as everything else, deliberately: the body is a crop of the wall, so it
    # carries the wall's provenance. One dict is shared by ALL THIRTEEN outputs this script
    # writes (4 wall + 4 crest + 2 body + 3 silhouette), so giving the body a key of its own
    # means either diverging from the other twelve or touching the shared dict and rewriting
    # every one of them - and a metadata-only rewrite still loses the "a re-bake is a no-op"
    # property that makes an unchanged bake produce no git diff.
    #
    # The SANDBANK is deliberately NOT in this set, and has a dict and a fingerprint of its
    # own - see sand_meta above. A future tidy-up that "unifies the metadata" has to argue
    # past that reason rather than merely notice the asymmetry.
    for key, name in ROCK_BODY_OUT.items():
        write_png(OUT_DIR / name, body[key], meta)
    for index, rgba in layers:
        write_png(OUT_DIR / f"silhouette-{index}.png", rgba, meta)
    for key, name in SANDBANK_OUT.items():
        write_png(OUT_DIR / name, sand[key], sand_meta)
    print(f"\nwrote {OUT_DIR}/{{{', '.join(ROCK_OUT.values())}, "
          f"{', '.join(ROCK_TOP_OUT.values())}, {', '.join(ROCK_BODY_OUT.values())}, "
          f"silhouette-1..3.png, {', '.join(SANDBANK_OUT.values())}}}")
    print(kotlin_snippet(*rock["size"], *top["size"], *body["size"], *sand["size"]))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("--rock-height", type=int, default=DEFAULT_ROCK_HEIGHT)
    parser.add_argument("--silhouette-max", type=int, default=DEFAULT_SILHOUETTE_MAX)
    parser.add_argument("--period", type=int, default=None,
                        help="override the searched wrap period")
    parser.add_argument("--luminance-factor", type=float, default=LUMINANCE_FACTOR,
                        help="mean rock luminance as a multiple of wallColor's")
    args = parser.parse_args(argv)
    try:
        return bake(args.rock_height, args.silhouette_max, args.period, args.luminance_factor)
    except SourceError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
