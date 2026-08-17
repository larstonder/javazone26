# `tools/` - the asset bakes

↑ [Repo root](../README.md) · [CLAUDE.md](../CLAUDE.md) · [Design spec](../docs/superpowers/specs/2026-08-04-en-pust-til-design.md) · [Docs map](../docs/README.md)

Two Python pipelines that turn source art into the textures the game ships. Neither is part of the
Gradle build and nothing at runtime calls them: you run them by hand and they write PNGs into
`src/main/resources/`.

**Those outputs are in git and the inputs are not.** `.gitignore:130` excludes `assets/` (about
183 MB of source frames plus the rock and silhouette art), so a clean clone builds, tests and runs
the game perfectly well - the baked textures are on the classpath and the `.exe` embeds them - but
**cannot re-bake**. Both scripts detect the missing directory and exit with a message rather than a
traceback (`build_spritesheet.py:109`, `build_backdrop.py:275`).

| Script | Reads | Writes |
|---|---|---|
| `build_spritesheet.py` | `assets/sprites/{diffuse,normals}/*.png` | `src/main/resources/sprites/` (2 files) + `build/spritesheet-qa/` |
| `build_backdrop.py` | `assets/rock/*.png`, `assets/silhouettes/*.png` | `src/main/resources/backdrop/` (13 files) |

## Setup

**There is no `requirements.txt`, no `pyproject.toml` and no `setup.py`** - I looked. The list
below is read off the imports; the versions are what the owner's machine currently has.

| Need | Verified | Used for |
|---|---|---|
| Python 3 | 3.13.2 | everything |
| `numpy` | 2.5.1 | every module |
| `Pillow` (`PIL`) | 12.3.0 | PNG I/O, `Image.LANCZOS`, `PngImagePlugin` tEXt metadata, `ImageDraw` in the QA bundle |
| `pytest` | any recent | the test suite only, not the bakes |

Nothing else; `pip install numpy Pillow pytest` is enough.

```bash
cd tools && python3 -m pytest      # 107 tests across 12 files, all green as of 2026-08-17
```

`pytest.ini` sets `pythonpath = .` and `testpaths = tests`, so the run **must** start from
`tools/` - the tests import `spritesheet.*`, `backdrop.*`, `build_spritesheet` and
`build_backdrop` as top-level names.

The tests never touch `assets/`. Everything is asserted on synthetic arrays or pure integer maths,
deliberately, so the suite is green on a clean clone (`tests/test_body_crop.py:10` says so). The
one test reading a committed artifact, `test_build_spritesheet.py:196`, skips if it is absent.

## The sprite sheet bake

```bash
python3 tools/build_spritesheet.py [--frame-height 384] [--keep-last-frame]
```

**Consumes** 42 diffuse frames (`assets/sprites/diffuse/0001.png` .. `0042.png`, 1000x2000 RGBA
16-bit) and 42 index-matched normal frames (`assets/sprites/normals/`, 1000x2000 RGBA 8-bit,
sRGB-*encoded* tangent normals). **Emits** `src/main/resources/sprites/diver-diffuse.png` and
`diver-normal.png`, both **1764x1152**, plus a QA bundle under `build/spritesheet-qa/`.

| | |
|---|---|
| frames used | 41 of 42 |
| cell | 126 x 384 texels (`spritesheet/geometry.py:35`, rounded up then up to even) |
| grid | 14 x 3 = 42 cells, 1 unused trailing cell |
| sheet | 1764 x 1152, landing in the **2048** texture bucket |
| guard | 2 px of transparency on every side of every cell (`spritesheet/assembly.py:18`) |

The grid is **searched, not chosen** (`spritesheet/geometry.py:52`), ranked by
`(bucket, area, cols)` with bucket first: 7x6 and 14x3 hold the same 42 cells at exactly the same
area, but 7x6's 2304 px edge falls in the 4096 bucket while 14x3's 1764 fits 2048. Unused trailing
cells are free - the game only drives indices `0..FRAME_COUNT-1` - and allowing them is precisely
what buys the smaller bucket. `test_geometry.py:44` pins that ordering.

Frame 42 is dropped as an exported duplicate of frame 1: silhouette centroid speed climbs to
5.284 px/frame going into it and collapses to 0.083 leaving it.

| Flag | Default | Effect |
|---|---|---|
| `--frame-height N` | `384` | Cell height. Width follows from the content aspect and the grid is re-searched, so **this changes every Kotlin constant** - 256 and 512 both give 11x4 where 384 gives 14x3. |
| `--keep-last-frame` | off | Include the duplicate 42nd frame. Changes `FRAME_COUNT` to 42 and re-searches the grid. |

### Validation, and what it rejects

Every gate in `spritesheet/validate.py` runs **before anything is written**
(`test_build_spritesheet.py:163` asserts the abort leaves no output):

- `list_frames` / `check_pairing` / `check_dimensions` - `*.png` only (a naive `listdir` sees 43
  frames, because `assets/sprites/diffuse/.DS_Store` already exists), identical filename lists, all
  84 files the same size, and an empty list is an error too.
- `check_alpha_agreement` - the two exports must agree on alpha to within `ALPHA_TOLERANCE = 1`.
  Zero would be wrong, not stricter: Pillow has no 16-bit RGBA path and takes the **high byte** of
  the 16-bit diffuse, producing ~6326 legitimate off-by-one texels per frame. A real desync moves
  the alpha *edge* - a step of up to 255 - which cannot hide inside one LSB.
- `check_normal_encoding` - mean `|v|` over opaque texels within 1% of unit. A raw
  (non-linearized) decode measures 1.170 on these files, so the gate has ~17x margin. It also
  catches a fully transparent frame, because `mean_normal_length` returns NaN and NaN comparisons
  are always false - hence the explicit `not np.isfinite(...) or` at `validate.py:67`.
- `union_bbox` is taken from the **normals**, not the diffuse, for the same 16-bit reason: the
  truncated diffuse yields a bbox one pixel narrow (628 vs 629).

### Resampling and QA

`spritesheet/resample.py` is where a subtle mistake still produces a plausible image; read its
module docstring first. Three things are load-bearing: resampling in **linear light** (a 5.16x
downscale, and averaging gamma-encoded values darkens); **alpha-premultiplying** before the resize
and dividing back out after (otherwise transparent texels contribute undefined colour to every
partially covered output texel - a dark halo on the albedo, garbage directions on the normals); and
**renormalizing** the resized normals, because averaging unit vectors shortens them. The normal
sheet's RGB is written **linearly**, not sRGB-encoded, because it uploads as `RGBA8` and the GPU
hands the shader the stored bytes unchanged.

`spritesheet/qa.py` writes four artifacts to the gitignored `build/spritesheet-qa/`, meant to be
looked at: `loop.gif` (final-resolution animation on a checkerboard - what settles the frame-42
argument), `contact-{diffuse,normal}.png` (every cell outlined and numbered, green used / red
unused, so an off-by-one grid shows at a glance), and `normal-relit.gif` (frame 0 lit by a key
light orbiting 16 angles; a flipped channel makes the highlight travel the wrong way, obvious in
motion and invisible in a still).

## The backdrop bake

```bash
python3 tools/build_backdrop.py [--rock-height 2048] [--silhouette-max 1600] \
                                [--period N] [--luminance-factor 2.0]
```

**Consumes** `assets/rock/{diffuse,normal}.png` (300x1000), `assets/rock/{top_diffuse,top_normal}.png`
(300x500) and `assets/silhouettes/{1,2,3}.png` (2000x2000, 3000x3000, 3000x1000).

| Output | Size | What it is |
|---|---|---|
| `rock-{diffuse,normal}.png` | 614x2048 | the vertically tiling wall ("edge art") |
| `rock-mirror-{diffuse,normal}.png` | 614x2048 | its horizontal mirror, for the other side of the column |
| `rock-top-{diffuse,normal}.png` | 614x2048 | the crest that caps each wall at the waterline |
| `rock-top-mirror-*.png` | 614x2048 | its horizontal mirror |
| `rock-body-{diffuse,normal}.png` | 526x2048 | the opaque interior, mirror-doubled. **One pair, not two** |
| `silhouette-{1,2,3}.png` | 1600x1600, 1600x1600, 1600x533 | parallax ridges, baked as white RGB + source alpha so the draw colour is their whole appearance |

| Flag | Default | Effect |
|---|---|---|
| `--rock-height N` | `2048` | Wall output rows. An **upscale from 1000, on purpose**: `vMax = height / arraySize`, so `height == arraySize` makes `vMax` exactly 1.0 and stops a LINEAR tap at the tile seam reaching into the never-written remainder of the array layer. |
| `--silhouette-max N` | `1600` | Largest side of each mask. Anything in `(1024, 2048]` joins the arrays the diver's sheets already forced open; the bound is **strict at the bottom**, so 1024 itself allocates a new 209.7 MB array. |
| `--period N` | searched | Override the wrap period. Escape hatch for art the search misjudges. |
| `--luminance-factor F` | `2.0` | Target mean rock luminance as a multiple of `DiveRenderer.wallColor`'s 0.034. A relationship, not a constant. **Missing from the script's own usage line** (`build_backdrop.py:5`). |

### Mirror-doubling

`backdrop/mirror.py` mirrors horizontally: for an albedo that is `rgba[:, ::-1, :]`; for a normal
map it is that **plus negating the vector's x component**, which in 8-bit storage is exactly
`255 - r` with no rounding error. Flip the image and forget the negation and every bump on one
cliff is lit from the wrong side, which reads as bad art rather than as a bug (`test_mirror.py:24`).
The wall used to get its right-hand copy from a 180-degree rotation at the draw site - but that is
a horizontal mirror **and a vertical flip**, which the crest could not use, so the two sides of the
column were transformed differently and the right-hand wall ran upside down under a right-way-up
summit. Both are baked mirrors now and `DiveRenderer` draws every side at angle 0
(`build_backdrop.py:60`).

### The crest / body split

The wall art is **not uniform across a tile**: going outward it is empty, then ragged, then solid.
Tiling it from a fixed anchor makes what lands at the frame edge a function of
`(visibleHalfWidth - anchor) mod TILE_WIDTH_METRES`, and 43% of that period is not solid - it
measured *empty* at 16:9, the likeliest booth panel. Five commits moved the anchor; each relocated
the hole rather than closing it, because a modulus cannot be closed by choosing a phase
(`build_backdrop.py:488`).

So there are two textures. The **edge art** is drawn once per side and supplies the ragged
silhouette on the column boundary. The **body** (`crop_body`) is the wall's opaque interior cut out
of the *already lifted* output - no second LANCZOS pass, and it inherits the wall's gain and
ambient exactly, so the two cannot differ in brightness - concatenated with its own mirror.
`[C | mirror(C)]` buys four things: a seam that is a *reflection* rather than a step;
self-mirroring, so one pair serves both sides (2 array layers, not 4); a reflected join with the
edge art; and the wall's vertical phase preserved, because the crop takes whole columns over all
2048 rows. Its outward bound is **derived, never typed** - `first_not_solid_column` scans for the
first column not alpha 255 in every row (264 on the current art), because a hard-coded number would
be a second copy of a property of the *art* and a redrawn cliff would leave it stale silently.

**`EDGE_INSET_METRES` is a Kotlin constant, not a bake parameter** - `render/RockFace.kt:242`,
derived from `ALPHA_TEXEL_COLUMNS = 475`, one past the last column holding any alpha. The tile's
last 139 columns are completely empty, so anchoring the quad on the column boundary left a 2.715 m
strip of nothing between the rock's furthest reach and where the simulation stops the diver.
Insetting by exactly that margin puts the cliff's furthest-reaching texel on the boundary the
diver actually feels, so the promontories touch the wall and the bays between them are water.

### The transparent wrap border

`clear_wrap_border` (`build_backdrop.py:174`) forces **column 0** of each base texture fully
transparent. Not cosmetic: `texture.frag` tiles as `fract(texCoord * texTiling)`, and where a
quad's edge cuts *through* a pixel that pixel's centre lies outside the quad, so `u` extrapolates
past 1.0 and `fract` of that is ~0 - sampling the `u = 0` column, which on this art was solid
stone. Every quad edge therefore drew one partially-covered pixel of opaque rock where the art is
transparent: RGBA(0,0,0,64), (0,0,0,53) and (0,0,0,167) against the sunset, a visible hairline down
each cliff. Proven by elimination - nudging `uTiling` below the integer and insetting `uMax` by a
whole texel both changed nothing, while a **half-pixel** nudge removed the lines entirely, which is
what identifies partial pixel coverage as the trigger. That nudge is not available as a fix (world
geometry is in metres and the booth's resolution is unknown). Only the base textures need the
border; the mirrors inherit it at `u = 613`, inside the transparent margin.

### Other gates

- `require_matching_alpha` - diffuse and normal must be the same crop to within one LSB. A slid
  crop moves the alpha edge by hundreds of texels and slides the lighting off the rock.
- `tile.choose_period` - **tests the full-height seam first**, searching for an overlap period only
  if that fails. The search has no defence against a source that already tiles: its score falls off
  monotonically with no local minimum, so it returns the range's end, and `wrap_blend` then ships
  the tile with row 0 replaced by the last row - invisible in a capture
  (`backdrop/tile.py:28`, `test_tile.py:131`). The finished seam must also be no worse than
  `MAX_SEAM_RATIO = 1.5` times the tile's own interior row-to-row adjacency.
- **The GI reflectance floor.** `texture_multiply_blend.frag` replaces any albedo with
  `length(rgb) < 0.02` by flat grey, and 44.2% of the source rock's opaque texels are under it. The
  fix is an **ambient term, not a gain**: `length(ambient + v) >= length(ambient)` for `v` in the
  positive octant, so one inequality on the ambient proves the floor is cleared everywhere. The
  gain is then solved so mean luminance lands on `--luminance-factor` times `wallColor`'s. All
  three bakes re-measure after the 8-bit round trip and **raise** if one texel is still under.
- `pad_top_to_height` - the crest is sized from its *width*, to match the wall's texel size at the
  join, so its height is at the mercy of the art's proportions. The current art bakes to 614x1023,
  **two texels** under the engine's strict array-reuse bound, which would allocate a 1024 array per
  format for +419.4 MB. It is padded to the wall's height with transparent rows, on **top**,
  because the crest's quad is anchored by its bottom edge. The albedo pads with black and the
  normals with `(128, 128, 255)`, the flat vector; a zero-length normal must never reach the
  renderer.

## The contract with the Kotlin side

Both scripts print a **Kotlin snippet to copy verbatim** on success (`build_spritesheet.py:77`,
`build_backdrop.py:730`). That exists because the constructor call is the one artifact in the whole
pipeline a human retypes by hand, and two facts in it are silently fatal if got wrong - so they are
hard-coded as literals in the template rather than derived, and `test_build_spritesheet.py:27`
asserts the literal string.

| Baked file | Read by | Constants that must stay in sync | Test that re-derives them |
|---|---|---|---|
| `sprites/diver-{diffuse,normal}.png` | `render/DiverSprite.kt:103,120` | `FRAME_COUNT` 41, `HORIZONTAL_CELLS` 14, `VERTICAL_CELLS` 3, `FRAME_TEXELS_WIDE` 126, `FRAME_TEXELS_TALL` 384 | `DiverSpriteTest.the frame grid matches the committed sheet` (**reads the PNG's IHDR**); `...the argument order the engine actually has` |
| `backdrop/rock-{diffuse,normal}.png` + `rock-mirror-*` | `render/RockFace.kt:269,288,322` | `TEXELS_WIDE` 614, `TEXELS_TALL` 2048, `OPAQUE_TEXEL_COLUMNS` 264, `ALPHA_TEXEL_COLUMNS` 475, `BORDER_TEXEL_COLUMNS` 1 | `RockFaceTest.the tile size matches the committed texture`; `...the alpha profile matches the committed texture`; `...each mirrored rock texture is the exact horizontal mirror of its committed base` |
| `backdrop/rock-body-*.png` | `render/RockFace.kt:551` | `BODY_TEXELS_WIDE = 2 * (OPAQUE_TEXEL_COLUMNS - BORDER_TEXEL_COLUMNS)` = 526, derived not declared | `RockFaceTest.every texel of the committed body is opaque`; `...the body is its own horizontal mirror`; `...the body's outermost column is the wall's first column of art` |
| `backdrop/rock-top-*.png` | `render/RockFace.kt:577` | `TOP_TEXELS_WIDE` 614, `TOP_TEXELS_TALL` 2048, `TOP_SHOULDER_TEXEL_ROW` 1050, `TOP_SUMMIT_TEXEL_ROW` 1043 | `RockFaceTest.the crest is baked to the wall's width, and its height is the art's own proportion` |
| `backdrop/silhouette-1..3.png` | `render/Backdrop.kt:103-105` | per-layer `(wide, tall)`: `(1600,533)`, `(1600,1600)`, `(1600,1600)` | `BackdropTest.each layer's declared size matches its committed PNG and stays in the 2048 bucket` |

**`DiverSpriteTest` re-derives the grid from the committed PNG's IHDR** rather than trusting the
declared constants, and that is the point: a re-bake at a different `--frame-height` re-searches
the grid, so 256 gives 11x4 where 384 gives 14x3. Stale copied numbers are silent - the sheet still
loads and the game shows fragments of the wrong frames. The test reads the IHDR bytes at their
fixed offset directly, with no image library and no pixel decoding.

## The traps

Three engine behaviours that produce **no error message at all** - and the reason the bakes print a
Kotlin snippet instead of a size.

**1. `SpriteSheet`'s constructor takes `(..., format, maxMipLevels, hCells, vCells)`, which is not
the field declaration order.** The fields read `horizontalCells, verticalCells` first, so
`(..., cols, rows, 0)` looks right and sets `hCells = rows`, `vCells = 0`. The constructor accepts
that and silently builds a zero-length `Texture[]`; nothing raises until two stages later, as an
`ArrayIndexOutOfBoundsException` from `getTexture(0)` the first time a frame is drawn, by which
point the call site that got it wrong is off the stack. Verified from bytecode.
`test_build_spritesheet.py:27` asserts the printed template reads `1, 14, 3)` and that neither
`1, 3, 14)` nor `0, 14, 3)` appears anywhere in it.

**2. `maxMipLevels = 0` allocates no texture storage at all.** `TextureArray` computes
`mipLevels = min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and hands it to
`glTexStorage3D` as `levels`. `levels = 0` is `GL_INVALID_VALUE`: nothing is allocated, every later
`glTexSubImage3D` fails too, and there is no exception and no log line. **`1` means "one level, no
mips"** - and `1` is what both snippets print, as a literal.

**3. `diver-normal.png` has a HYPHEN and must keep it.** The engine's `loadAll` auto-loader
(`Extensions.kt:446-448`) keys on the substring `_normal` - an *underscore* - and when it matches
forces `RGBA8` with **10** mip levels regardless of what the caller asked for. Renaming to
`diver_normal.png` would silently give the sheet a mip chain, and mip generation averages across
cell boundaries, so adjacent frames of the loop would bleed into each other under minification.
Every backdrop output is hyphenated for the same reason (`build_backdrop.py:105`), and
`test_body_crop.py:129` asserts `"_normal" not in name` for the body pair. These assets are built
explicitly rather than through `loadAll`, so the rule is belt and braces - but the belt is one
rename away from failing.

A fourth, cheaper trap: **filter, wrapping, format and `maxMipLevels` must match across every
texture that shares an array.** `TextureBank.getOrCreateTextureArrayFor` reuses one only when all
four agree *and* `max(w, h) > arraySize / 2`; differ in any and the backdrop textures allocate a
second 251.7 MB array instead of taking a free layer in the diver's.

## Reproducibility

Both bakes are **byte-reproducible**: same inputs, byte-identical file. `write_png` sorts the tEXt
keys before adding them and pins `optimize=False, compress_level=9` (`build_spritesheet.py:62`,
`build_backdrop.py:284`), and each PNG carries `ept:*` metadata including a sha256 fingerprint of
the concatenated sources, so a committed texture states which art it came from. Re-running with
unchanged inputs must therefore produce **no git diff**:

```bash
python3 tools/build_spritesheet.py
python3 tools/build_backdrop.py
git status --porcelain src/main/resources/sprites src/main/resources/backdrop
# no output = reproducible, and your inputs match what shipped
```

An unexpected diff means one of three things: your `assets/` differ from the owner's, you passed a
different flag, or a Pillow/numpy upgrade changed the encoder output. The `ept:source_sha256` chunk
separates the first case from the other two - read it with
`python3 -c "from PIL import Image; print(Image.open('<path>.png').text)"`. The backdrop bake
deliberately writes the **same** metadata dict to all thirteen files, including the body pair, so
adding a body-specific key cannot rewrite the metadata of the other eleven and destroy the no-op
property (`build_backdrop.py:805`).

## If you need to change the art

1. **Get `assets/`.** It is not in the repo and never will be - ask the owner. Without it neither
   script can run; both exit non-zero naming the missing directory.
2. Edit the source frames, keeping the invariants the validators check: diffuse and normal exports
   the same crop and dimensions, index-matched by filename, and the normal maps still
   **sRGB-encoded** (a linear re-export is rejected by `check_normal_encoding`, which is the point).
3. Re-bake, and read the printed report - the reflectance and seam statistics are there to be
   looked at, and `reuses existing 2048 array: False` is one word away from +419 MB of VRAM.
4. **Copy the printed Kotlin snippet** into `DiverSprite.kt` / `RockFace.kt` / `Backdrop.kt` if any
   dimension changed. Do not retype it.
5. `cd tools && python3 -m pytest`, then `./gradlew test`. The Kotlin side is what actually catches
   a stale constant: `DiverSpriteTest`, `RockFaceTest` and `BackdropTest` re-derive the grid, tile
   size, alpha profile and mirror relationship from the committed PNGs, so they fail on a re-bake
   you forgot to propagate.
6. **Look at it.** Open `build/spritesheet-qa/loop.gif` and `normal-relit.gif`, then run the game
   and grab the real window - see CLAUDE.md's "Seeing it actually run", including the warning that
   `EPT_SCREENSHOT` is not passive and its output is not the frame. Green tests are not evidence
   the art looks right; almost every bug in this project's history was a rendering fault no test
   caught.

## Stale comments — corrected 2026-08-17

This section used to list eight wrong-but-once-correct comments in these scripts. **All eight have
been fixed in place**, so it is now a record rather than a task list: the "rotated 180 degrees"
claims in `backdrop/mirror.py` and `build_backdrop.py` (the mirror is baked; both sides draw at
angle 0), the pre-2026-08-12 texel counts in `build_backdrop.py` and `tests/test_body_crop.py`
(691→**614**, 396→**264**, 67/228/396→**139/211/264**, 790→**526**), the "FIVE DECISIONS" heading
over a list of seven, and the usage line that omitted `--luminance-factor`.

`render/RockFace.kt:117-251` holds the live numbers and is the authority for all of them.

**One caveat about this whole section, worth more than its contents.** A README that inventories
defects in *other* files is stale the moment someone fixes one — and fixing them is the point. If
you find yourself adding to a list like this, prefer fixing the comment, or opening an issue.
The durable version of this knowledge is the contract table above, which describes a *relationship*
that a test re-derives, rather than a snapshot that rots.
