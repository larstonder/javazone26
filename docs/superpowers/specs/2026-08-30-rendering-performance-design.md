# Rendering performance — reaching 60 fps

**Status:** approved in brainstorming 2026-08-30, not yet implemented.
**Companion:** `2026-08-30-main-menu-and-graphics-options-design.md` builds the menu that exposes
§2's preset ladder. §2 is the source of truth for preset values; that spec imports them.
**Evidence:** five parallel audits, 2026-08-30 — bytecode of `pulse-engine-0.13.0.jar`, measured
frame times, `sample` stack profiles, and GPU utilisation.

---

## 1. The measured situation

### What it runs at today

| Scenario | Framebuffer | p50 frame time | fps |
|---|---|---|---|
| Attract, dev window | 3200×1800 | 34.6 ms | 28.9 |
| In water, 20 m | 3200×1800 | 37.6 ms | 26.6 |
| In water, 140 m | 3200×1800 | 57.7 ms | 17.3 |
| Attract, fullscreen | 1920×1200 | 23.4 ms | 42.8 |
| **140 m, fullscreen** | **1920×1200** | **22.8 ms** | **43.8** |

Fullscreen is the shipping path and the cheap one. `screenMode = FULLSCREEN` on this XDR panel
takes the display's default video mode — **1920×1200**, not the panel's 3456×2234 — so a
1600×900 dev window (a 3200×1800 Retina framebuffer, 2.5× the pixels) is *more* expensive than
the release build. Every dev-window observation overstates the real cost.

### It is GPU-bound, and this is the measurement that proves it

Four consecutive launches at 140 m, alternating framebuffer size, back to back so drift cannot
explain the result:

| Run | Framebuffer | MP | p50 |
|---|---|---|---|
| X1 | 3200×1800 | 5.76 | 73.12 ms |
| X2 | 1600×900 | 1.44 | 30.46 ms |
| X3 | 3200×1800 | 5.76 | 74.28 ms |
| X4 | 1600×900 | 1.44 | 30.30 ms |

**4.00× the pixels → 2.44× the frame time.** Extended across five sizes, frame time fits:

```
frame ≈ 13 ms  +  10.5 ms per megapixel
```

At the dev window that is ~60 ms of 74 ms resolution-dependent. The game's own Kotlin — sim,
camera, HUD, light submission — is **0.3 ms**, under 0.5% of the frame, in every scenario.

### The GPU is saturated, not starved

| State | GPU `Device Utilization %` | CPU |
|---|---|---|
| Idle desktop | 3–5% | — |
| Game, attract, 3200×1800 | **100%** | 37.6% of one core |

**This refutes the obvious reading of the `sample` profile.** That profile shows 73% of
main-thread time inside `IOGPUCommandQueueSubmitCommandBuffers` and 92% in
`GLDContextRec::flushContext`, which looks like driver overhead. It is not: the CPU blocks in
submit because the command queue is **back-pressured by a busy GPU**. An M4 Pro's 20-core GPU is
running flat out and still delivering 29 fps on the attract screen.

**Recorded because it was the first conclusion drawn here and it was wrong.** Do not re-derive
"the GL shim is the bottleneck" from the stack profile alone.

### What the GPU is actually doing

~**144 million pixels shaded per frame at 3200×1800 — 25× the framebuffer** — across **85
profiler scopes**. GI alone creates nine surfaces and runs two complete jump-flood distance-field
chains (`gi_local_sdf`, `gi_global_sdf`; each a seed pass, 11 flood passes and a resolve), then
seven radiance-cascade passes, interior, AO, final, the multiply composite, bloom's 18 draws,
colour grading, opaque water and two back-buffer blits.

The heaviest per-pixel item: with `bilinearFix` on, each of 4 rays makes 4 `getRadiance` calls of
up to 25 march steps — **up to 448 texture fetches per pixel**, and they are dependent,
incoherent raymarch samples, the most cache-hostile pattern a GPU has.

Three reasons Apple Silicon is especially poor at this *shape* of work:

1. **Tile-based deferred rendering.** Every pass costs a tile-memory load and store. 85 passes
   with large RGBA16F attachments means gigabytes per frame of tile traffic against ~273 GB/s of
   unified bandwidth. A discrete desktop GPU shrugs at many-small-passes; a TBDR is punished.
   **This is why pass count matters more here than it would on the Windows booth machine.**
2. **GL 4.1 has no compute shaders.** The whole GI solve is fragment shaders over full-screen
   quads. A compute implementation would need a fraction of the passes and none of the tile
   round-trips. A structural penalty of the deprecated API, not a slow chip.
3. **Apple's GL is a frozen shim over Metal.** Its timer queries are not implemented at all —
   `gpuProfiling = true` yields a well-formed 85-scope tree with `0 ns` on every node.

**The ~13 ms intercept is real GPU work, not driver tax:** the jump-flood chains and the seven
cascade levels are fixed-size passes that do not shrink when the window does. **Work has to go,
not just pixels.** This is why §2's changes are ordered by passes removed, not by pixels saved.

### The goal is 60 at 1920×1200 fullscreen

From 22.8 ms to 16.67 ms is a **27% cut** — and that 22.8 ms was sampled under external CPU
load, so it is pessimistic. This is achievable. 120 fps is not: even a 640×360 framebuffer
measures 15.39 ms, so the fixed floor caps the game near 65 fps before the play area is drawn.
`targetFps = 120` has therefore **never once been honoured** — `FpsLimiter.sync` returns
immediately whenever a frame has already overrun, which is every frame in every scenario
measured.

---

## 2. The changes, in order

Every change is **live at runtime** unless marked otherwise, because
`GlobalIlluminationSystem.onUpdate` re-pushes texture scales every frame and
`SurfaceImpl.setTextureScale` defers a `renderTarget.init` to the next frame. Savings are
estimates derived from pass-count formulas in the bytecode.

> **Implementation rule: land these one at a time and measure each.** The projections are
> arithmetic, not measurements. `FrameProbe` (companion spec §6) must exist before change 01, or
> none of this is verifiable.

### 01 — Drop MSAA16 on the HUD surface  · est. 3–6 ms

`EnPustTil.kt:1380-1386` creates the HUD with `Multisampling.MSAA16` at full framebuffer
resolution, while the actual 3D world uses MSAA4. At 3200×1800 the colour attachment alone is
~737 MB, and `RenderTarget.init` allocates a *second* FBO as a resolve target with
`RenderTarget.end()` doing a `resolveToFBO` blit every frame — confirmed present in the profiler
scope list as `RESOLVE_FBO (MSAA16)`. MSAA16 may also exceed `GL_MAX_SAMPLES` on Apple Silicon
GL 4.1, in which case the driver is silently granting something else anyway.

Sixteen-times coverage sampling on a screen-space text overlay is indefensible. Go to `NONE`,
or MSAA4 to match the world. **Compare a real screen grab before and after** — this is the one
change with a visible-quality risk that tests cannot catch.

### 02 — Collapse the global scene chain  · est. 19% of the pixel budget

13 passes serving off-screen rays only, while `DiveLighting` already culls every light to the
view plus 3 m.

**The trap:** `traceWorldRays = false` does **not** remove it. `gi_global_scene` and
`gi_global_sdf` are created unconditionally in `onCreate` and their post-processing runs
regardless; the flag only gates a shader branch in `radiance_cascades.frag`. To reclaim the
passes you must also drop `globalSceneTexScale` (0.6 → 0.15 or lower), which shrinks the flood
chain's *pass count* as well as its pixels — `GiJfa` runs `ceil(log2(max(w,h)))` passes.

Set both together. With the branch off, `globalSceneTex` is never sampled, so the two are
coherent.

### 03 — Turn off `bilinearFix`  · 75% of the cascade pass's work

16 raymarches per pixel → 4. Best saving-to-risk ratio available, and it is a **uniform** — a
one-frame experiment, not a rebuild. Cost is radiance-cascade ringing at cascade boundaries.

**Try this one first, purely to see how much of the frame it hands back**, before committing to
the ordering above.

### 04 — Fix `lightTexScale`, which is worse than the engine default  · 73% of cascade pixels

Set to 0.5 against a default of 0.4. This is a **step function, not a curve**:
`lightTextureSizeFunc` rounds the scaled framebuffer up to a multiple of `2^cascadeCount`, and
`cascadeCount` derives from the rounded diagonal. At 1080p, 0.5 tips the count 6→7 *and* rounds
540→640: **+122%**. At Retina: +73%.

Any value must be evaluated through the formula. This is what `GraphicsApplierTest` guards.

**`maxCascades` belongs to this change, because it feeds the same size function.** It is 10,
effectively 7. Dropping it to **6** removes a cascade pass *and* the size round-up waste. It is
safe: max light propagation is about `intervalLength·(4^N−1)/3` px, so N=7 reaches ~5461 px and
N=6 ~1365 px ≈ 42 m — still well beyond `TORCH_REACH_METRES = 24 m`. **N=5 (~10.5 m) would
visibly clip the torch beam; do not go below 6.** Unlike the other GI properties this one is read
when the light surface re-initialises rather than as a per-frame uniform, so it takes effect on
the next surface re-init (a resize or a GI toggle), not instantly.

### 05 — Halve two needlessly full-resolution GI surfaces  · est. 8.5%

`gi_light_final` and `gi_normal_map` render at full framebuffer resolution while every input
feeding them is at 0.5 — pure upsampling, no information gained. Neither is reachable through a
GI property, but a direct `setTextureScale(0.5f)` **sticks**, because `onUpdate` does not
overwrite those two. Halving the normal map also halves its 10-level per-frame mip chain
(`CustomMipmapGenerator` does one full-frame draw per level).

Risk: coarser normals on the diver and rock. The cascade shader samples the normal map at
0.5-scale probe centres anyway, so the loss should be small — but confirm on screen.

### 06 — Reconsider bloom  · 18 draws, 7% of budget

10 textures, 18 draws across its down/up chain. Its threshold is 1.4 and this project's own
capture work established the pearls sit under it **entirely** — so it may be costing 7% to do
almost nothing visible.

Verify on screen first. `bloom.intensity = 0f` keeps the passes; `deletePostProcessingEffect("bloom")`
removes them. Only the second is a saving.

### 07 — Set `targetFps` honestly  · no cost, removes a lie

120 has never been reached. Set the shipped default to **60**, and let the menu raise it. The
fixed tick is 60 Hz either way, so simulation is unaffected; what a higher cap buys is smoother
camera interpolation on a 120 Hz panel, which is currently theoretical.

`Configuration.setTargetFps` is public and `endFrame` re-reads it every frame — fully live.

### 08 — Give the Mac build its JVM flags  · 23 ms of boot pauses  · **restart**

`-XX:+UseZGC`, `initialHeapSize`, `maxHeapSize`, `-XX:SoftMaxHeapSize=2g` and
`-XX:+DisableExplicitGC` all live in the `launch4j` block, which only produces the **Windows**
`.exe`. **The tuned configuration has never run on the machine the game now actually runs on.**
The Mac path sets `-XstartOnFirstThread` and nothing else.

Recommended `applicationDefaultJvmArgs` (and the same for the `.app`'s java-options):

```
-XstartOnFirstThread
-Xms512m -Xmx512m          # equal: the heap never grows or shrinks mid-run.
                           # 512 MB is 10x the measured 49 MB peak.
-XX:+AlwaysPreTouch        # pre-fault once at startup so no page fault lands in a frame.
                           # Only cheap because the heap is small - do not pair with -Xms1024m.
-XX:+DisableExplicitGC     # measured: removes two System.gc() full pauses, 10.0 + 12.8 ms.
-Dorg.lwjgl.util.NoChecks=true   # release only. Drops LWJGL per-call validation on every
                                 # GL entry point. Keep OFF in dev so mistakes still throw.
```

**Do not add `-XX:+UseZGC` on the Mac.** G1 measured **one 1.7 ms young pause per ~20 s** with a
~50 MB live set. ZGC's load/store barriers are a per-reference-access tax on the hot path to fix
a pause that happens three times a minute. Also drop `-XX:SoftMaxHeapSize=2g` on Windows — against
a 50 MB live set it does nothing.

Not recommended, with reasons: `-XX:TieredStopAtLevel=1` (kills C2, which the render path needs);
a larger `-Xmx` "for safety" (nothing gets safer, pre-touch gets expensive);
`-XX:+UseLargePages` (no macOS equivalent); GC ergonomic tuning (at ~1 MB/s allocation G1's
defaults are never stressed).

### 09 — Stop allocating in the zone bands  · 555 allocations/frame

Not throughput — **this is the only mechanism in the entire sweep that can produce a visible
hitch** rather than a uniformly slow frame. It is also a direct breach of the "no per-frame
allocation in the render path" rule `CLAUDE.md` states.

`dive/Zone.kt:21` is `entries.lastOrNull { depth >= it.minDepth }`. Verified from bytecode:
`List.lastOrNull(predicate)` compiles to `listIterator`, and `Zone.entries` is an
`EnumEntriesList extends AbstractList`, whose `listIterator(int)` is
`new AbstractList$ListIteratorImpl` — **one allocation per call, unavoidable in that form**.

`DiveRenderer.drawZoneBands:505` calls five blends per strip across 111 strips = **555
allocations/frame**, of which **222 are exact duplicates** because `zoneBlueAt` (`:1655`)
re-derives the red and green it was just handed on the same line.

Two fixes, in value order:
1. Pass `r` and `g` into `floorBlueForReflectance` at the call site. Removes 222 allocations
   *and* 222 blends, two lines.
2. Replace `Zone.at`'s `lastOrNull { }` with a descending index loop over a
   `private val ZONES = entries.toTypedArray()`. Removes the remaining 333 plus the sim-tick
   ones. No behaviour change.

Also: `EnPustTil.kt:2692` allocates a `Color` **every attract frame**, 70 lines below a comment
forbidding exactly that; and `drawLeaderboard` re-sorts with `compareByDescending`/`thenBy`,
boxing an `Integer` and a `Long` per comparison — ~560 boxes/frame, **growing through the day**.
Cache the sorted top-N and invalidate it in `registerScore`.

### Not doing

| | Why |
|---|---|
| GI off entirely | `DiveLighting` draws into surfaces that `onDestroy` deletes; needs verified null paths first, and the abyss reads flat-bright without the multiply. |
| `setTextureCapacity` to reclaim VRAM | 753 MiB of arrays hold ~113 MiB of image data (85% waste), but on 24 GB unified this buys nothing, and a wrong capacity fails **silently** as a missing texture at `logLevel = WARN`. |
| Reducing draw calls | ~623 submissions/frame cost ~30 µs total. `TextureRenderer.draw` is ~17 float stores with no GL call. |
| Culling work | Already good — every iterating system goes through `Camera.showsSquare`. |
| A shader pipeline cache | All ~40 programs compile in 63 ms total; `iridescence.frag`'s 22.5 KB took ~1 ms. |
| `Sky`'s 247 strips, the clock box's 156 rects, `String.format` | Real but ~40–60 µs combined, against heavily-reasoned, well-tested code. |

---

## 3. Measurement

**Nothing in §2 may be claimed without a before/after frame time.**

`FrameProbe` (companion spec §6) is the instrument. Two engine facts it must encode:

- A `Service` must call `start()` on itself — `Service.isRunning` defaults false and
  `ServiceManagerImpl` skips services where `!isRunning()`. **This is why the F3 `MetricViewer`
  overlay has never worked**: `EnPustTil.kt:1508` adds it without `.start()`. Verified by
  bytecode *and* by driving F3 into the running game and screenshotting it. Fix that line and
  correct `CLAUDE.md:88` and `render/README.md:369`.
- `data.gpuRenderTimeMs` is **CPU wall time** around `drawFrame` + `swapBuffers`, not GPU time.
  GL timer queries return `0 ns` on Apple Silicon, so no real GPU time is available.

**Method.** Vary resolution by editing `build/resources/main/application-dev.cfg` — the build
output, gitignored — never the source. Take A/B pairs **back to back** rather than sequentially;
absolute numbers moved ~2× between a quiet and a loaded machine during the sweep, while
back-to-back ratios held. Report p50 and p95, not means. Hold depth with the existing
`EPT_DEPTH` pin so a deep scene survives the sample.

`ioreg -r -d 1 -c AGXAccelerator | grep "Device Utilization %"` gives GPU utilisation without
sudo, and is what distinguishes "saturated" from "starved". Idle reads 3–5%.

**Screen grabs, not tests, are the evidence for anything about appearance.** Changes 01, 05 and
06 all alter what the frame looks like and no unit test can see it. Use the capture recipe in
`CLAUDE.md` — and note `EPT_SCREENSHOT` is not passive: `ScreenshotEffect.getTexture()` returns
`RenderTexture.BLANK`, so any surface it attaches to composites as blank.

---

## 4. Sequencing

1. **`FrameProbe` + the `MetricViewer` `start()` fix** — the instrument, before anything else.
2. **03 (`bilinearFix`)** — one uniform, largest single-line effect, immediate read on how much
   of the frame the cascades own.
3. **01 (HUD MSAA)** — largest fixed-cost win, independent of everything.
4. **02, 04, 05** — the GI scale changes, one at a time, each measured.
5. **07, 08** — config and JVM flags.
6. **06 (bloom)** — after a look at what it contributes.
7. **09 (allocation)** — independent of all the above; do it whenever, but measure frame-time
   *variance* rather than p50, since that is what it affects.
8. **Fold the surviving values into the preset ladder** the menu ships.

Changes 01–06 are exactly the LOW/MEDIUM preset definitions. Implementing them *is* defining the
ladder — the menu spec imports the result rather than duplicating it.

---

## 5. Documentation corrections owed

These are asserted in the repo and were disproved during the sweep. They must be fixed alongside
the work, or they will mislead the next reader:

| Claim | Where | Reality |
|---|---|---|
| `screenMode` cannot change at runtime | `CLAUDE.md:212`, `application.cfg` header, `init.pes` | `Window.updateScreenMode` is public and works |
| Window size cannot change at runtime | same | Window is `GLFW_RESIZABLE`; drag-resize already works end to end |
| `EPT_DEV=1` enables an F3 MetricViewer overlay | `CLAUDE.md:88`, `render/README.md:369` | Never worked — `Service` added without `start()` |
| Startup needs a 12–14 s settle | `CLAUDE.md` capture recipe | Measured 2.3 s from JVM start; `sleep 6` is a safe margin |

Also worth recording, newly discovered:

- Game callbacks run on a **`"game"` thread**, not the GL/main thread — `gameLoopMode` defaults
  to `MULTITHREADED` and nothing overrides it. Nothing in the docs says so, and it constrains
  where GLFW/GL calls may be made.
- Every boot logs an ERROR for `pulseengine/shaders/error/error.comp` — the engine loading a
  compute shader that GL 4.1 on macOS does not have. Harmless, but it is the first ERROR in
  every log and will misdirect anyone triaging a real fault.
