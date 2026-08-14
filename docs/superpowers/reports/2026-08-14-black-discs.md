# Are there still black discs? No. Count, method, and the proof

**Date:** 2026-08-14. **Tree state:** clean at `5110a19` throughout; the one probe was reverted with
`cp` from a copy and `git status --porcelain` was verified empty before this file was written.
`./gradlew test --rerun-tasks` → **505 tests, 0 failures, 0 errors**.

**Answer: zero.** There are no black discs in the game's frame at the attract screen, and none at any
of `EPT_DEPTH` 3, 6, 10, 20, 30, 75 or 140, nor in two frames of a live run. The two causes already
found (`becec3b` alpha erosion, `5110a19` motes depth-rejecting the water) between them account for
every disc that ever existed. There is no third cause, and the two open tasks it would have belonged
to can be retired.

The count of "2 survivors" recorded in `5110a19`'s message was an artefact of counting on
uncontrolled full-screen grabs. It does not survive a controlled measurement.

---

## 1. Establishing the count beyond doubt

### 1.1 Game pixels, not desktop pixels

Every number below is taken from an image that is **exactly the game's framebuffer and nothing
else**. The route:

```
X Y W H  <- kCGWindowBounds of the window named "EnPustTil", read at capture time
screencapture -x -R${X},$((Y+28)),${W},$((H-28))    # content rect: bounds minus the 28pt title bar
```

The output is the window's content area to the pixel — no title bar, no shadow, no wallpaper, no
menu bar. Three independent checks that it is the right rectangle and the right window:

1. **It is deterministic.** Two grabs two seconds apart, same run: max channel difference **6/255**
   at the attract screen and at 140 m, 22/255 over 0.036% of pixels at 20 m, 34/255 over 0.039% at
   75 m. The residual is GI's own temporal accumulator (`bounceAccumulation` 0.7), which never fully
   settles. Nothing at that amplitude can hide or invent a black disc.
2. **The frame's own geometry predicts a measured row.** `OpaqueWaterEffect.GATE_DEPTH` is
   `SURFACE_DEPTH + AMPLITUDE_METRES + 1` = `0 + 0.31 + 1` = **1.31 m**. On a 3456×1956 framebuffer
   the camera is width-bound, so `pixelsPerMetre = 3456 / 98.516 = 35.081` and the frame's top is at
   `diverDepth − 60 × 0.4`. Predicted gate row at the attract screen (diver at 0 m):
   `(1.31 + 24) × 35.081 = 887.9`. **Measured on the `EPT_SCREENSHOT` dump: the deepest row holding
   any alpha < 255 is 887, and row 888 is the first that is opaque everywhere.** At `EPT_DEPTH=20`:
   predicted `(1.31 + 4) × 35.081 = 186.3`, measured last partial row **185**, first opaque **186**.
   Two different states, both to the row. The crop, the camera model and the gate all agree.
3. **A positive control lights up in it** — §3.

### 1.2 What counts as a black disc

Defined once, in `blackdisc.py`, and used for every number here. A black disc is a 4-connected
component of the composite that is

* near-zero in absolute terms — mean luma **< 8/255**, and
* **< 0.25 ×** a local background (a ⅛-scale block median, so a small blob cannot pull down its own
  reference), and
* compact and near-circular — area ≥ 40 px, bbox aspect ≤ 1.8, fill ≥ 0.5, and
* inside the play column (`|world x| < Tuning.COLUMN_HALF_WIDTH`; the rock walls are legitimately
  near-black stone and are the largest dark components in every frame), and
* not HUD — `ScreenText` draws a **black outline** behind every string, so the counters of `R`, `4`,
  `D`, `O` are black *by design*. Every one of the "discs" an absolute-threshold scan finds in a
  clean frame is a letter's counter; three of them sit at pixel-identical coordinates in the probe
  build and the shipped build, which is how they were identified.

### 1.3 The count

| state | framebuffer | control-pair max Δ | **black discs** |
|---|---|---|---|
| attract (IDLE, diver at 0 m) | 3456×1956 | 6 | **0** |
| `EPT_DEPTH=3` | 3200×1800 | 6 | **0** |
| `EPT_DEPTH=6` | 3456×1956 | 11 | **0** |
| `EPT_DEPTH=10` | 3456×1956 | 7 | **0** |
| `EPT_DEPTH=20` | 3456×1956 | 22 | **0** |
| `EPT_DEPTH=30` | 3456×1956 | 8 | **0** |
| `EPT_DEPTH=75` | 3200×1800 | 34 | **0** |
| `EPT_DEPTH=140` | 3200×1800 | 6 | **0** |
| PLAYING, two frames (accidental — see §5) | 3456×1956 | — | **0** |
| **probe: `Motes.FIELD_TOP_DEPTH` reverted** | 3456×1956 | 12 | **4** (7 at loose settings) |

The shallow depths are not padding. 3/6/10/30 m are the states where the diver, the walls and the
air vents are drawn **inside the water-surface quad's band** (0 → `QUAD_BOTTOM_DEPTH` = 7.31 m),
which is the only place in the game where the depth-rejection mechanism of `5110a19` can produce a
pixel nothing ever wrote. If a fourth object had the motes' defect, it would show there.

Two backstops behind the detector, because a detector can only find what it was told to look for:

* **A loose sweep with the absolute floor removed entirely** (`< 0.55 ×` local background, same shape
  filters) over all eight frames returns **five** candidates. Every one was cropped and looked at:
  two are water beside a bright HUD glyph, one is water beside another glyph, one is water above an
  air vent, one is a pearl's rim beside the white `U` of "PUST" — in each case the *background*
  estimate was inflated by an adjacent bright object, not the pixel darkened. The same sweep returns
  **7** on the probe build. There is no population of "not-quite-black" discs hiding under the floor.
* **The eye, at γ = 0.28.** Every frame was rendered with a 0.28 gamma lift, which turns a hole into
  an unmissable black blot on pale water. All eight are clean; the probe build's discs are obvious
  in a thumbnail.

---

## 2. The mechanism check, on the surface dumps

`EPT_SCREENSHOT` involves no window, so this half of the evidence is immune to everything in §5.
Both known causes have exact signatures in `main`'s alpha channel.

| dump | alpha < 255 | alpha == 0 below the waterline |
|---|---|---|
| attract | rows 0–887 only (the sky, plus the meniscus band above the gate) | **none** |
| `EPT_DEPTH=20` | rows 0–185 only | **none** |
| `EPT_DEPTH=75` | **none at all — `min(alpha) == 255` over the whole surface** | none |
| `EPT_DEPTH=140` | **none at all — `min(alpha) == 255`** | none |

So `OpaqueWaterEffect` is doing exactly and only what it claims: below the gate row there is not one
partial-alpha pixel, at any of the four states.

Above the gate, where the erosion is untouched by design, there are 16 partial-alpha components
≥ 30 px strictly below the water's own edge at the attract screen. **None of them is disc-shaped.**
They are the rock's ragged transparent margin (two 140-px-wide columns), the god-ray columns crossing
the meniscus (four bands 148–219 px wide, all ending dead on the gate row), and six slivers around
the diver. Run the same analysis with the mote floor reverted and **three perfect circles appear** —
23×23, 17×17, 17×17, bbox aspect **1.00**, fill 0.79–0.82. That is the shape signature of the defect,
and the shipped build has none of it.

The `"sky"` surface was checked for the same failure, since it is what a hole in `main` reveals:
**alpha is 255 at every lit pixel** at attract and at 20 m. There is no hole behind the hole.

---

## 3. The decisive probe: turning the discs back on and off

`Motes.FIELD_TOP_DEPTH` reverted from `WaterSurface.QUAD_BOTTOM_DEPTH + MAX_SIZE_METRES * 0.5f` to
`Tuning.SURFACE_DEPTH` — one line, which restores both consumers (`surfaceFade` and the cell walk's
`firstWaterCell`) at once. Rebuild, capture the identically-pinned attract frame, restore, rebuild.

| | shipped | probe | back to shipped |
|---|---|---|---|
| black discs, strict | 0 | **4** | 0 |
| dark blobs, loose sweep | 0 | **7** | 0 |
| disc-shaped alpha holes on `main` | 0 | **3** | 0 |

The seven blobs sit at rows 869–960 of 1956 — the water-surface quad's band — and at x from 685 to
2694, i.e. spread across the frame, exactly where marine snow is. The four strictest have inner luma
**0.13, 0.18, 0.30 and 6.62** against local water at 43–55. They are plainly visible in a thumbnail.

**The instrument sees black discs when black discs are there, and reports zero when they are not.**

---

## 4. Why nothing else can punch a hole — the fact that closes this out

This is the part that makes "none remain" a statement about the game rather than about eight
screenshots, and it was the last thing missing from the record.

A hole in the water needs three things at once: a draw that goes through the engine's own
`TextureRenderer` (attached at surface creation, therefore **flushed first**), issued **after** the
water quad in call order (therefore holding a nearer depth), that **writes depth at a fragment it
does not paint**. The first two are true of the walls, the air vents, the diver and the motes alike.
The third is where they part, and the reason is in `renderers/texture.frag`:

```glsl
if (textureColor.a < alphaDiscardThreshold)
    discard;
```

`alphaDiscardThreshold` **defaults to 0.4** — `TextureRenderer`'s synthetic-defaults constructor,
`ldc float 0.4f` on the masked `iload_3 / iconst_2 / iand` branch. And the test is on
`textureColor.a`, the **texture's** alpha; `vertexColor.a`, the draw colour's, is applied only in the
final `fragColor = vertexColor * textureColor` and never gates the discard. So:

* **The diver, the cliff tiles and the backdrop silhouettes cannot punch holes.** Their transparent
  texels have texture alpha 0, are discarded, and write no depth. This is why the diver's **9 m** quad
  — which straddles the water quad's band at every depth from 0 to about 11 m — leaves no rectangle
  in the sea. Measured: at `EPT_DEPTH` 3, 6 and 10 the water around him is intact.
* **`Draw.fillRect` always writes depth over its whole quad.** `Texture.BLANK` is uploaded with
  `TextureHandle.NONE`, and `TextureHandle.NONE = create(sampler = 0, texIndex = 65534)` = the
  shader's `NO_TEXTURE`, for which `textureColor` is `vec4(1.0)`. Alpha 1 ≥ 0.4, never discarded,
  whatever the draw colour. The air vents do exactly this — but they are drawn with an **opaque**
  colour (`Color(0.65f, 0.95f, 1f)`), so they fill their own hole with themselves and read as the
  pale square they are. They are not, and never were, black discs.
* **The motes were the one draw that had both halves.** They use `LightEmitter.emitter()` — a radial
  alpha ramp with flat white RGB — modulated by a draw colour at `MOTE_ALPHA` 0.25 × fade. The
  texture's alpha is 1 at the centre and crosses 0.4 partway out, so a **disc** of fragments survives
  the discard and writes depth while painting almost nothing. That is the round hole, and that
  combination now exists nowhere else in the game.

That is a complete enumeration of what is drawn on `main` after the water quad. The absence of a
third cause is not an inference from eight clean frames; it is a property of the draw calls.

---

## 5. Wrong turns, and the traps that are still armed

**The window's SIZE changes between otherwise identical launches.** Same script, same command line,
minutes apart: `1600×928 @ (64,80)` and `1728×1006 @ (0,33)`. Two of the `EPT_SCREENSHOT` runs came
out at a **1600×900** framebuffer rather than 3200×1800, i.e. the window had opened on the
non-Retina external display. **Never assume a framebuffer size; read `kCGWindowBounds` at capture
time and derive the crop from it.** Every table above records the size it was measured at.

**`osascript … set frontmost` STARTS THE GAME.** Two captures taken with the `CLAUDE.md` recipe's
activation step came back with the run timer at 1:26, the air ring drawn and "BANKED 10" in the
corner — the attract screen was gone. It was only caught because the HUD was visibly wrong. The
window does not need raising for a `-R` grab launched fresh; do not use it. (The two frames were
kept as a bonus PLAYING data point: 0 discs.)

**`wincap.py` is dead on this machine.** `CGWindowListCreateImage` returns `None` — deprecated out
from under us. It cannot be used, including through a screen lock.

**`screencapture -l <id>` cannot be cropped by assumption.** For the *same* 1600×928 window it
returned images of 3200×1856, 3336×1992, 3424×2080 and 3456×2012, the difference being a variable
drop shadow. Worse, the desktop behind that shadow is a flat neutral dark grey, and my title-bar
detector matched it — so I concluded for several minutes that the window was 1728 px wide and
maximised. It was not; the crop was off by 112 px in x and ~25 px in y, and the resulting frame
compared against a correctly-cropped one showed a "moved waterline" that did not exist. This is
precisely the class of error the brief warned about, arriving through a different door.

**My own scanner bit me too.** `discs.py` auto-stripped a title bar whenever the image height ended
in 56 — which the legitimate 1956-row content grabs do. It silently removed the top 56 rows for
several runs. Caught only because two detectors disagreed about a y coordinate by exactly 56.

---

## 6. Two incidental findings

**`main`'s dump is LINEAR; the composite is sRGB-encoded. This is the real reason for `CLAUDE.md`'s
"the rock came out a pure black silhouette in the dumps and lit, textured stone on screen".** It is
not a recompositing error and the dump is not lying — the same pixels simply measure ~6.5× darker
because the render texture holds linear values that the backbuffer encodes on output. Measured on
the attract frame, dump against the `-R` composite of the same state, with the sRGB→linear
prediction beside it:

| region | `main` dump | composite | predicted from composite |
|---|---|---|---|
| water, rows 1000–1100 | 5.09 | 33.59 | 4.0 |
| rock, left wall | 1.79 | 16.80 | 1.4 |
| deep water, rows 1700–1900 | 2.22 | 21.78 | 2.0 |

All three land within the error you would expect from taking a median through a nonlinear transform.
So `EPT_SCREENSHOT`'s **RGB is usable after all**, provided it is converted; it is the *composite* of
several dumps that is not reproducible by hand, and the alpha channel that is directly reliable.
`CLAUDE.md`'s warning is right about what not to do and wrong about why, and the "why" is what sent
this investigation to the composite for every colour reading.

**One untested corollary of the same ordering, flagged not fixed.** `IridescenceRenderer` is attached
after `TextureRenderer`, and the motes are drawn after the pearls in call order — so a mote's core
should depth-reject the pearl behind it exactly as it once rejected the water. It cannot make a black
disc (the zone band behind is opaque and was drawn earlier through the same renderer, so the pixel
holds water, not void), which is why it is out of scope here. It predicts a mote-sized bite out of a
pearl's body. I did not measure it.

**On the pearl annulus (task #13): this work does not explain it.** The loose sweep did flag the ring
beside one pearl at 75 m, but the local background there was inflated by an adjacent air vent, so
that flag is an artefact of my background estimator and is evidence of nothing either way.

---

## 7. Reproducing this

Scripts and captures are under `/tmp/bd` (`cap5.sh` — composite grabs; `dump.sh` — surface dumps;
`blackdisc.py` — the detector, with its definition in the module docstring). Pins used everywhere:
`EPT_WAVE_PHASE=12 EPT_SHAFT_PHASE=30 EPT_MOTE_PHASE=45`, plus `EPT_DEPTH` where stated. The only
source edit made at any point was the one-line `Motes.FIELD_TOP_DEPTH` probe of §3, restored from a
`cp` backup and verified with `git status --porcelain` before this file was written.
