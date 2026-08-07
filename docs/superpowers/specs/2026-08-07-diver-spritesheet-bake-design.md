# Diver sprite sheet bake — design

**Status:** approved 2026-08-07. Revised 2026-08-07 after adversarial review; see §10.
**Date:** 2026-08-07
**Scope:** asset production only. Drawing the animated diver in `render/` is separate — §9.

> 41 diffuse frames and 41 normal-map frames become two sprite sheets that Pulse Engine's
> `SpriteSheet` asset can index directly, with nothing left for a human to type twice.

---

## 1. What problem this solves

`assets/sprites/` holds a hand-authored idle/float loop for the diver: 42 `0001.png`–`0042.png`
diffuse frames and 42 index-matched normal maps, each 1000×2000 RGBA, 183 MB in total. Pulse
Engine cannot animate from a directory of files. Its `SpriteSheet` asset takes **one texture
divided into a uniform grid**, and the argument order is not what the field order suggests:

```
SpriteSheet(filePath, name, filter, wrapping, format, maxMipLevels, horizontalCells, verticalCells)
```

Verified from bytecode, because getting this wrong is silent and fatal — see §4.

Doing the pack by hand is a trap. Every failure mode here is silent: a per-frame crop makes the
diver jitter, a naive resize halos the silhouette, a naive normal decode inverts the lighting,
a wrong argument order yields a zero-length texture array, and a mistyped cell count shows the
wrong frame at the booth. This spec defines a script that makes each of those impossible or loud.

---

## 2. Source material — measured, not assumed

Measured 2026-08-07 from the files themselves. Where a number was initially measured wrong, §10
records what changed and why.

| Property | Value |
|---|---|
| Files | 42 diffuse + 42 normal, filenames identical between the directories |
| Size | 1000×2000 RGBA, all 84 |
| **Diffuse bit depth** | **16** (colour type 6) |
| **Normal bit depth** | **8** (colour type 6) |
| Colour chunks | both carry `sRGB` (intent 3), `gAMA` 0.45455, `cHRM`; no ICC profile |
| Union alpha bbox, all 84 | x 175–803, y 42–1985 inclusive → **629 × 1944**, aspect 0.323560 |
| Diffuse vs normal alpha | **pixel-identical**, 0 differing texels, under correct 16→8 rounding |
| Normal encoding | tangent-space **and sRGB-encoded** — see §6 |
| Frames used | **41.** Frame 42 is an exported duplicate — see below |

### Frame 42 is a duplicate, and magnitude is the wrong test

Frame 42 differs from frame 1 in 31 977 px against frame 1→2's 206 572, which invites the
reading that it is a real frame in a loop that decelerates into the seam. It is not. The
discriminating measure is silhouette-centroid speed, px/frame:

```
… 0.212  1.972  3.679  4.804  5.284 │ 0.083
                                42→1 ┘
```

Velocity climbs to **5.284 — the maximum of the entire loop — immediately before frame 42**, then
collapses to **0.083**, about 1/31 of the mean 2.612. Motion does not decelerate into the seam;
it is cut off by a duplicate end-frame. Keeping frame 42 puts a one-frame dead stop (~1/60 s) in
every loop.

**The bake uses frames 1–41.** 41 is prime, which does not constrain the grid: §5 allows unused
trailing cells, since the game drives indices `0 … frameCount-1` and never samples them.

### The source is ~5× oversized

`Framing.VISIBLE_DEPTH_METRES` is 60 and `Framing.DIVER_SIZE_METRES` is 3, so the diver is about
5% of screen height — near 90 px on an 1800 px framebuffer. Fitted into the default 384 px cell
the content downscales **5.16×** (width-limited: 122/629). The bake is a large, deliberate
downscale and its quality is the main thing the script must get right.

---

## 3. Approach

A single purpose-built script, `tools/build_spritesheet.py`, run by hand and re-run when the
frame size or the source art changes.

Rejected alternatives:

- **A config-driven pipeline** over a sprite-set manifest. Right once a second sprite set exists;
  today there is one, and the schema would be invented against imagined requirements. The
  internals are structured so this is a small refactor later.
- **A Gradle task baking at build time.** Ruled out by §7: `assets/` is gitignored, so a clean
  clone has no source frames and the build would fail. The sheets are committed artifacts.

**Python with Pillow and numpy only.** No ImageMagick, and none of `imageio`, `cv2`, `pypng`,
`tifffile` or `skimage` is installed. That constraint has teeth — see §6.

---

## 4. Contract

```bash
tools/build_spritesheet.py [--frame-height 384] [--keep-last-frame]
```

**Reads** `assets/sprites/diffuse/*.png` and `assets/sprites/normals/*.png` (glob `*.png`
explicitly — `assets/sprites/diffuse/.DS_Store` already exists and a naive `listdir` sees 43 vs 42).

**Writes** `src/main/resources/sprites/diver-diffuse.png` and `diver-normal.png`, creating the
directory (it does not exist yet). Both are one uniform row-major grid, aligned cell-for-cell.

The grid is **chosen, not passed** — see §5 — so `--cols` is deliberately absent.

```kotlin
// printed by the script with the numbers filled in
SpriteSheet("/sprites/diver-diffuse.png", "diver_diffuse", LINEAR, CLAMP_TO_EDGE, SRGBA8, 1, 14, 3)
SpriteSheet("/sprites/diver-normal.png",  "diver_normal",  LINEAR, CLAMP_TO_EDGE, RGBA8,  1, 14, 3)
const val DIVER_FRAME_COUNT = 41   // NOT 14*3 — the last cell is unused
```

Two things in that call are load-bearing and both were wrong in the first draft:

**Argument order is `(…, format, maxMipLevels, hCells, vCells)`.** The constructor passes its
6th int straight to `Texture.<init>`'s trailing `maxMipLevels`, then assigns slots 7 and 8 to
`horizontalCells` / `verticalCells`. The synthetic defaults constructor confirms it: bit 32
defaults slot 6 to `5`, pairing with the `LINEAR_MIPMAP` filter default. Passing
`(…, cols, rows, 0)` sets `hCells=rows, vCells=0`, so `size = rows*0 = 0`, `textures` is
zero-length, and `getTexture(0)` throws.

**`maxMipLevels` must be `1`, never `0`.** `TextureArray` computes
`mipLevels = min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)`, and passes it
to `glTexStorage3D` as `levels`. `levels = 0` is `GL_INVALID_VALUE`: no storage is allocated and
every later `glTexSubImage3D` fails too — no exception, no log. `1` means one level, no mips,
which is what we want: mip generation averages across cell boundaries and would smear adjacent
frames together under minification.

`--frame-height` is parameterised because the diver's on-screen size is still a live design
question. `--keep-last-frame` restores frame 42 if the QA preview argues for it.

---

## 5. Geometry and grid selection

```
union alpha bbox, all 84 files       629 × 1944   aspect 0.323560
frame height H                       384          (--frame-height)
frame width  W = ceil(H × aspect)    → 126        (rounded up to even)
content scaled to fit (W-4, H-4), centred         → 2 px transparent guard
grid   14 × 3 = 42 cells, 41 used, 1 unused
sheet  1764 × 1152                   → 2048 texture bucket
```

### The grid is searched, not chosen by hand

Sheet VRAM is **not** `w × h × 4`. `TextureBank.getOrCreateTextureArrayFor` buckets by
`Math.max(width, height)` against `DEFAULT_CAPACITIES` (`TextureCapacitySpec(texSize, capacity,
format)`, 28 entries, sizes 128…8192), and `TextureArray.init` calls
`glTexStorage3D(…, textureSize, textureSize, maxCapacity)` — a **square** array with **every
layer allocated eagerly**. Crossing a bucket boundary is therefore a step function, not a slope.

So the script enumerates every `cols` from 1 to `frameCount`, and picks the grid with the
**smallest bucket, then the smallest sheet area**. At the default that is 14×3 (max dim 1764 →
2048 bucket, 2.03 Mpx) over 11×4 (max 1536 → same 2048 bucket, 2.13 Mpx).

| `--frame-height` | Frame | Best grid | Sheet | Max dim | Bucket |
|---|---|---|---|---|---|
| 256 | 84×256 | 11×4 | 924×1024 | 1024 | 1024 |
| **384** | **126×384** | **14×3** | **1764×1152** | **1764** | **2048** |
| 512 | 166×512 | 11×4 | 1826×2048 | 2048 | 2048 |

A hand-picked 7×6 would give 882×2304 — max dim 2304, into the **4096** bucket, for identical
image content. Allowing unused trailing cells is what buys the smaller bucket.

### Three load-bearing details

**One union bbox for all 84 files, never per-frame.** Per-frame cropping re-centres the figure
every frame and the diver jitters in place; diffuse and normal must additionally share the *same*
crop or the lighting slides off the body. Every frame gets a byte-identical crop→scale→placement
transform, so authored motion is preserved exactly. The only cost is resolution — each frame's
content is smaller than the union — which is not jitter.

**The 2 px transparent guard, applied in destination space after scaling.** `SpriteSheet`
subdivides UVs exactly evenly (`uMin = u0 + x*(1/hCells)*(u1-u0)`) with no half-texel inset
anywhere, and content touches the union bbox on all four sides (frame 3's normal reaches x=803,
frame 24 the left edge, all 41 the top, frame 35 the bottom). With `LINEAR` filtering the GPU
reaches ±0.5 texel, so an opaque texel on a cell boundary bleeds into its neighbour. A 2 px inset
leaves a full clear texel of margin at the stated 5.16× downscale.

**Cell size divides exactly.** The engine computes per-cell size as `(int)(width * (1f/cols))`;
at 1764/14 and 1152/3 that is exactly 126×384 with no off-by-one.

---

## 6. Resampling and colour

This is where the first draft was most wrong, and every error here looks fine in a thumbnail.

### The normals are sRGB-encoded, so they must be linearized before decoding

The maxim "never gamma-correct a normal map" is a statement about *storage convention*, not
about these files. These carry `sRGB` and `gAMA` chunks and the pixel data matches. Measured over
opaque texels:

| Decode | mean \|v\| | within 1% of unit | mean X |
|---|---|---|---|
| raw `v = rgb/255*2-1` | 1.170 | 3.3% | **+0.400** |
| sRGB→linear, then decode | 0.998 | 80.8% | **+0.001** |

A mean X of +0.40 on a bilaterally symmetric character is not physical; +0.001 is. The first
draft's validation — "mean (178, 187, 241), valid +Z-dominant" — could not catch this, because a
+Z-dominant mean is consistent with both. **Unit length is the discriminating test**, and the
script asserts it: mean \|v\| must be within 1% of 1.0 after decode, which fails loudly if a
future re-export changes the convention.

Renormalizing does **not** rescue a wrong decode — it projects the wrong direction onto the unit
sphere and hides the error. Order matters:

```
sRGB → linear  →  decode to [-1,1]  →  alpha-weighted resize  →  renormalize  →  encode
```

Alpha-weighted averaging is correct (it stops undefined normals in transparent texels polluting
the edge), but needs an explicit guard: where a footprint's total alpha is 0 the renormalize
divides by ~0, so those texels fall back to `(0, 0, 1)`. They are never sampled, but NaNs would
propagate through the encode.

The normal sheet is written as linear `RGBA8`; the diffuse as `SRGBA8`. The consumer path is
`gi_normal_map` via `NormalMapRenderer.drawNormalMap` (see `render/Draw.kt` and CLAUDE.md).

### Diffuse: premultiply → resize → un-premultiply

Pillow does not premultiply on resize, so fully-transparent texels' RGB — typically black —
averages into the silhouette edge and produces a dark fringe. Un-premultiplying must skip texels
with near-zero alpha rather than dividing by them.

### Pillow truncates the 16-bit diffuse, and the script must work around it

Pillow has no 16-bit RGBA path: it takes the **high byte**, so `round(a/257)` is not what you
get. Consequences, all measured:

- Diffuse alpha loaded through Pillow differs from the normal's by **6 326 texels/frame**, always
  ±1. Under correct rounding the two are **exactly identical**.
- Truncation zeroes very low alpha (`0x00FF` → `0`), so a bbox taken from the Pillow-loaded
  diffuse is **one pixel narrow** — this is exactly how the first draft measured 628 instead of 629.

No 16-bit-capable library is installed (§3), and a pure-Python PNG decode is far too slow for 84
files. The workaround needs neither:

- **Derive the union bbox from the normal maps only.** They are genuine 8-bit, so Pillow reads
  them exactly, and the alpha masks are provably identical to the diffuse under correct rounding.
- **Gate diffuse/normal alpha agreement at ±1**, not at 0. Zero tolerance would abort on today's
  real data — a false alarm caused by the script's own loader.
- **Accept ±1 truncation on diffuse RGB.** After a 5.16× downscale into an 8-bit output it is far
  below one output LSB.

---

## 7. Repository handling

`assets/` is 183 MB and git-lfs is not installed; committing it would cost every future clone
that, permanently.

- `assets/` and `release/` are gitignored (`.gitignore:130-131`, done in `32f7e65`).
- The two sheets (~1–2 MB) are committed under `src/main/resources/sprites/`, which ships inside
  the release `.exe`.
- The script header records the source frames' provenance and the re-bake command.

Because the sheets are committed artifacts, the bake must be **byte-reproducible**: pin Pillow's
`compress_level` and `optimize`, and strip timestamp metadata, so re-running with unchanged
inputs produces an identical file rather than a spurious 2 MB binary diff.

The bake parameters (`frame-height`, grid, frame count, source-frame hash) are written into a
PNG `tEXt` chunk, so a sheet that no longer matches the current settings is detectable rather
than silently stale.

Tradeoff, stated plainly: a clean clone can build and run the game but cannot re-bake without
obtaining the source frames separately. `assets/` being absent is the *normal* state, so the
script exits with a clear "source frames not present, see header for provenance" rather than a
`FileNotFoundError`.

---

## 8. Verification

This project's history is mostly rendering faults no test caught, so the script checks itself in
both directions.

**Fails loudly, before writing anything:**

- file counts differ between the directories (globbing `*.png`, per §4)
- filenames do not match one-for-one
- any file's dimensions differ from the rest
- diffuse/normal alpha disagree by **more than ±1** (§6 — the tolerance is the point)
- decoded normals' mean length is not within 1% of unit (§6)
- the chosen grid has fewer cells than the frame count

**Writes a QA bundle** to `build/spritesheet-qa/` (confirmed gitignored by `.gitignore:120:/build/`):

- the baked loop as an animated preview at final resolution, so the seam can be *watched* — this
  is what settles whether dropping frame 42 was right
- a contact sheet with grid lines and cell indices, so an off-by-one grid or bad crop is visible
  at a glance
- the decoded normal map rendered as a lit sphere-normal comparison, so an inverted or
  wrongly-decoded channel is obvious rather than subtle

**Prints the Kotlin** of §4 with grid, frame count and formats filled in, so those numbers are
copied from the bake rather than retyped.

---

## 9. Explicitly out of scope

- Wiring the sheets into `render/DiveRenderer` and replacing the white square diver.
- Frame timing and how the loop is driven.
- The diver's final on-screen size. Note this is *not* fully free: §5's table shows 512 still fits
  the 2048 bucket but 7×6-style layouts do not, so a size change should be re-run through the grid
  search rather than assumed cheap.
- Any other sprite set — pearls, air pockets and the anglerfish are still primitives.

Not out of scope any more: **how the normal map reaches the lighting pass is known.** CLAUDE.md
and `render/Draw.kt` document it as the same world rect submitted twice — albedo to `main` via
`drawTexture`, normal to `gi_normal_map` via `NormalMapRenderer.drawNormalMap` — with an identical
`(x, y, w, h, angle)` + centre-origin tuple. That is why §4 pins the two `TextureFormat`s.

---

## 10. Revision log

Adversarial review on 2026-08-07 found four errors that would each have failed silently, plus a
reversed conclusion. All are corrected above; recorded here because the wrong versions are all
plausible and would otherwise be reintroduced.

| Was | Is | Why it mattered |
|---|---|---|
| `SpriteSheet(…, hCells, vCells, maxMipLevels)` | `(…, maxMipLevels, hCells, vCells)` | Field order ≠ argument order. The wrong call gives `vCells = 0`, a zero-length array, and an AIOOBE on frame 1. |
| `maxMipLevels = 0` for "no mips" | `1` | `0` reaches `glTexStorage3D` as `levels=0` → `GL_INVALID_VALUE`, no storage allocated, no error reported. |
| "Normals must never be gamma-corrected" | sRGB→linear **before** decode | These files are sRGB-encoded. Raw decode gives mean X +0.40 and 17% non-unit vectors; renormalizing hides it. |
| Alpha mismatch threshold 0 | ±1, bbox from the normals | Pillow truncates the 16-bit diffuse; the strict gate aborts on real data and the bbox came out 628 instead of 629. |
| "Frame 42 is not a duplicate" | It is; bake 41 frames | Judged by magnitude, which is the wrong test. Centroid velocity peaks at 5.284 into the seam then drops to 0.083. |
| Hand-picked 7×6 grid, VRAM `w×h×4` | Searched grid, bucket-aware | Textures are allocated as square eager `TextureArray` buckets; 7×6 lands in 4096 where 14×3 lands in 2048. |

Claims that were checked and held: row-major `getTexture` (`x = index % hCells`, `y = index /
hCells`); exactly-even UV subdivision with no inset; the 2 px guard's sufficiency and ordering;
one-union-bbox genuinely preventing jitter; alpha-weighted normal averaging; and the frame
42→1 / 1→2 pixel counts (31 977 and 206 572, exact).
