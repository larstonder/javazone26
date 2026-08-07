# Diver sprite sheet bake — design

**Status:** approved 2026-08-07
**Date:** 2026-08-07
**Scope:** asset production only. Turning the baked sheets into a drawn, animated diver in
`render/` is a separate piece of work — see [Explicitly out of scope](#9-explicitly-out-of-scope).

> 42 diffuse frames and 42 normal-map frames become two sprite sheets that Pulse Engine's
> `SpriteSheet` asset can index directly, with nothing left for a human to type twice.

---

## 1. What problem this solves

`assets/sprites/` holds a hand-authored idle/float loop for the diver: 42 `0001.png`–`0042.png`
diffuse frames and 42 index-matched normal maps, each 1000×2000 RGBA, 183 MB in total. Pulse
Engine cannot animate from a directory of files. Its `SpriteSheet` asset takes **one texture
divided into a uniform grid**:

```
SpriteSheet(filePath, name, filter, wrapping, format, horizontalCells, verticalCells, maxMipLevels)
```

and walks it row-major via `getTexture(index)`. So the frames must be packed into a single
rigid grid per map, at a resolution the game actually uses, with the two maps aligned
cell-for-cell.

Doing that by hand is a trap. The failure modes are all silent: a per-frame crop makes the
diver jitter, a naive resize halos the silhouette, a naive normal-map resize flattens the
lighting, and a mis-typed cell count shows the wrong frame at the booth. This spec defines a
script that makes each of those either impossible or loud.

---

## 2. Source material — measured, not assumed

Every number here was measured from the files on 2026-08-07, not inferred from the export
settings.

| Property | Value |
|---|---|
| Frame count | 42 diffuse, 42 normal, filenames identical between the two directories |
| Source size | 1000×2000 RGBA, all 84 files |
| Union alpha bbox, all 84 files | x 175–803, y 42–1986 → **628×1944**, aspect 0.3230 |
| Diffuse vs normal alpha | **pixel-identical** on all 42 pairs (0 differing texels of 2 000 000) |
| Normal encoding | tangent-space, mean (178, 187, 241) over opaque texels — valid +Z-dominant |
| Loop | seamless; frame 42→1 differs in 31 977 px against frame 1→2's 206 572 |

Two consequences worth stating outright, because both contradict a reasonable first guess:

**Frame 42 is not a redundant duplicate.** It differs from frame 1 by 6.5× less than frame 2
does, which reads at a glance like the usual exported-loop duplicate that should be dropped. It
is not one — it is a real frame in a loop whose velocity approaches zero at the seam. All 42
frames are kept. Dropping it would put a small speed discontinuity in the loop.

**The source is roughly 20× oversized for its on-screen use.** `Framing.VISIBLE_DEPTH_METRES`
is 60, so one metre is about 30 px on an 1800 px framebuffer, and the current
`Framing.DIVER_SIZE_METRES = 3f` puts the diver near 90 px tall. A 2000 px source frame is far
past what any booth display can resolve. The bake is therefore a large, deliberate downscale,
and downscale quality is the main thing the script has to get right.

---

## 3. Approach

A single purpose-built script, `tools/build_spritesheet.py`, run by hand and re-run whenever
the frame size or the source art changes.

Two alternatives were considered and rejected:

- **A config-driven pipeline** over a manifest of sprite sets. The right shape once there is a
  second sprite set; today there is exactly one, and the manifest schema would be invented
  against imagined requirements. The script's internals are structured so this is a small
  refactor when pearls or the anglerfish get art.
- **A Gradle task baking at build time.** Ruled out by the asset decision in §7: `assets/` is
  gitignored, so a clean clone has no source frames and a build-time bake would fail. The
  sheets are committed artifacts, not build outputs.

Python with Pillow and numpy. Neither ImageMagick nor `magick`/`convert` is installed on this
machine; Pillow 12.3 and numpy 2.5 are.

---

## 4. Contract

```bash
tools/build_spritesheet.py [--frame-height 384] [--cols 7]
```

**Reads** `assets/sprites/diffuse/*.png` and `assets/sprites/normals/*.png`.

**Writes** `src/main/resources/sprites/diver-diffuse.png` and
`src/main/resources/sprites/diver-normal.png` — one uniform row-major grid each, aligned
cell-for-cell, both loadable as:

```kotlin
SpriteSheet("/sprites/diver-diffuse.png", "diver_diffuse", …, cols, rows, 0)
```

`--frame-height` is parameterised rather than fixed because the diver's final on-screen size is
still a live design question (§2). The default of 384 gives roughly 4× supersampling headroom
over the current 90 px diver, which covers growing it to ~6 m world height at 4K without a
re-bake. Re-running with a different value is cheap; the grid shape does not change.

---

## 5. Geometry

Derived once from the union bbox and shared by both maps, so the two cannot drift apart:

```
union alpha bbox over all 84 files      628 × 1944   (aspect 0.3230)
frame height H                          384          (--frame-height)
frame width  W = ceil(H × 0.3230)       → 126        (rounded up to even)
content scaled to fit (W-4, H-4), centred            → 2 px transparent guard
sheet = cols × W  ×  rows × H           882 × 2304   (7 × 6, no empty cells)
```

For reference at the other candidate sizes:

| `--frame-height` | Frame | Sheet (7×6) | VRAM per map |
|---|---|---|---|
| 256 | 84×256 | 588×1536 | 3.6 MB |
| **384** | **126×384** | **882×2304** | **8.1 MB** |
| 512 | 166×512 | 1162×3072 | 14.3 MB |

`rows` is derived as `count / cols`, never rounded up — see §8. `cols = 7` gives `rows = 6`,
which divides 42 exactly, leaves no dead cells, and keeps the sheet's aspect as close to square
as tall cells allow.

Three details in that block are load-bearing rather than cosmetic:

**One union bbox for all 84 files, never per-frame.** Trimming each frame to its own content
re-centres the figure every frame, and the diver would visibly jitter in place. The diffuse and
normal maps must additionally share the *same* crop, or the lighting slides off the body. Since
the two maps' alpha masks are pixel-identical (§2), a single bbox computed over all 84 files is
both correct and self-checking.

**The 2 px transparent guard.** `SpriteSheet` divides UVs evenly across the grid with no padding
of any kind. Several frames' content touches the union bbox edge exactly — frames 2 and 3 both
end at x=803, which is the union's right edge. With `LINEAR` filtering, an opaque texel sitting
on a cell boundary is sampled into the neighbouring cell, and the diver picks up a sliver of the
next frame down one side. Insetting the content guarantees no opaque texel lands on a seam.

**`maxMipLevels = 0`.** Mip generation averages across cell boundaries, which smears adjacent
frames into each other whenever the sprite is minified. The bake already does the downscaling
offline at the resolution the game draws at, so runtime mips buy nothing and cost correctness.

---

## 6. Resampling

This is the part that is silently wrong in most versions of this script, and both errors look
fine in a thumbnail.

**Diffuse: premultiply → resize → un-premultiply.** Pillow does not premultiply alpha on
resize, so the RGB of fully transparent texels — typically black — is averaged into the edge of
the silhouette. The result is a dark fringe around the diver that reads as a bad matte,
strongest exactly where the downscale ratio is largest, which here is 15×.

**Normal: decode to [-1,1] → alpha-weighted resize → renormalize → re-encode.** Averaging
encoded normals produces vectors shorter than unit length; the shading then goes flat precisely
at high-curvature areas — the fins, the mask, the shoulders — which are the features the normal
map exists to bring out. Renormalizing after the resample restores them. Normals must never be
gamma-corrected; they are a vector field, not a colour.

The normal sheet must also be loaded with a linear, non-sRGB `TextureFormat` on the Kotlin side.
That is a consumer-side concern, but it is recorded here because getting it wrong produces a
subtly wrong lighting response with no error.

---

## 7. Repository handling

`assets/` is 183 MB and git-lfs is not installed. Adding it to history would cost every future
clone that 183 MB permanently, with no way back short of a history rewrite.

- `.gitignore` gains `assets/` and `release/`.
- The two baked sheets — roughly 1–2 MB together — are committed under
  `src/main/resources/sprites/`, which is what ships inside the release `.exe`.
- The script's header comment records where the source frames come from and how to re-bake, so
  the provenance survives even though the inputs are not in the repo.

The tradeoff, stated plainly: a clean clone can build and run the game but cannot re-bake the
sheets without obtaining the source frames separately.

---

## 8. Verification

This project's history is mostly rendering faults that no test caught, so the script verifies
itself in two directions.

**Fails loudly, before writing anything**, on any of:

- differing file counts between the two directories
- filenames that do not match one-for-one
- any file whose dimensions differ from the rest
- **any** diffuse/normal alpha mismatch. The measured value today is exactly 0 differing
  texels across all 42 pairs (§2), so the threshold is 0 and any nonzero value means the two
  exports have fallen out of sync — which would misalign the lighting from the body.
- `--cols` not dividing the frame count evenly. `rows` is derived as `count / cols`, and a
  remainder would leave empty cells that `getTexture(index)` still happily returns, showing a
  blank diver for part of the loop. 42 admits 1, 2, 3, 6, 7, 14, 21 and 42.

**Writes a QA bundle** to `build/spritesheet-qa/`:

- the baked loop as an animated preview at final resolution, so the animation can be watched
  rather than inferred
- a contact sheet with grid lines and cell indices drawn on, so an off-by-one grid or a bad crop
  is visible at a glance

**Prints the Kotlin constructor line** with `cols`, `rows` and the paths filled in, so the
numbers in the game source are copied from the bake instead of retyped from memory. A hand-typed
`7, 6` that disagrees with the sheet is the single most likely way this breaks later.

---

## 9. Explicitly out of scope

- Wiring the sheets into `render/DiveRenderer` and replacing the white square diver.
- Frame timing and how the loop is driven from the sim or render clock.
- Whether `GlobalIlluminationSystem` will consume the normal map at all. The engine's
  `NormalMapped` interface is scene-entity-shaped — `getNormalMapTexture(): String` — while this
  game draws immediate-mode, so there is real work to determine how a normal map reaches the
  lighting pass. That question is worth answering before the normal sheet has a consumer, but it
  does not change what the bake produces.
- Any other sprite set. Pearls, air pockets and the anglerfish are still primitives.
