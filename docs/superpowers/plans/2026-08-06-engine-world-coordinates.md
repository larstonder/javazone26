# Én Pust Til — Migrating to the engine's world-coordinate model

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `engine.gfx.mainCamera` the single authority on where a world point lands on screen, in world units (metres), so that the world, the GI light map and the diver-tracking parts of the HUD cannot disagree — at any framebuffer size or aspect ratio — and so that real art can be authored in metres.

**Scope, after the review (2026-08-06, revised at `231c6a6`).** [`docs/superpowers/reports/2026-08-06-world-coordinates-plan-review.md`](../reports/2026-08-06-world-coordinates-plan-review.md) (commit `0a21992`) approved Stages A–C with required changes and rejected Stage D as written. The owner accepted it. What this plan now ships:

| | Tasks | State |
|---|---|---|
| **Stage A** | 1 | ✅ done, `6ea1f53` |
| | 2–4 | ship as written, with the `MainCameraOwnershipTest` sequencing corrected (Tasks 2, 3, 5) |
| **Stage B** | 5 | ships; one commit, atomic |
| **Stage C** | 6 | **reworked** — the original mechanism was inverted and its fix reintroduced a pixel-count constant (§1.7, Task 6) |
| | 7–9 | ship as written |
| **Stage D** | 10 | **reduced to ~20 lines** — two comment fixes, a conditional `start()`, the `CameraRig` editor gate, the `sim.tick` freeze. No `dive.scn` file, no entities, no `.scn`-ownership question. Its payoff is the one editor capability that demonstrably works today: live `@Prop` editing on the running `GlobalIlluminationSystem`. |
| | 11–14 | **deferred, not deleted** — moved verbatim (with the review's corrections applied) to §9. Revisit when art exists. |

**Architecture, in one line:** `CameraRig` becomes the sole writer of `engine.gfx.mainCamera`; `DiveRenderer` and `DiveLighting` draw the world in **metres**; `Hud` stays in **screen pixels** on its own surface; `Framing` keeps the framing constants and owns no transform. **No scene entities of our own are added** — `dive.scn` stays an empty in-memory scene created by `createEmptyAndSetActive`, and `EntityRendererImpl` + `GlobalIlluminationSystem` stay owned by Kotlin in `DiveLighting.setup`.

**Estimate:** **1.5–2 focused days** for Tasks 2–9 (of which about a third is capture verification at three aspect ratios), plus **~1 hour** for the reduced Task 10. Roughly four weeks remain and the art is not started.

**Currency.** This plan was written at `574d67b` and revised at `231c6a6`. Since it was written: `6b05f07` shadowed the quad/line vertex shaders so `drawQuad`/`drawLine` render on macOS in dev (dev-only; the shaders are excluded from the release jar), `c47a4b0` added the `EPT_EDITOR` hook that launches `SceneEditor`, `231c6a6` reworked the air ring into a clock face, and `0a21992` is the review. Test counts in this plan's step expectations are stale (233 → **238** at `231c6a6`); treat them as "the suite is green", not as literal numbers.

**Spec:** [`docs/superpowers/specs/2026-08-04-en-pust-til-design.md`](../specs/2026-08-04-en-pust-til-design.md) — 🔒 LOCKED
**Predecessor plan:** [`2026-08-04-en-pust-til-gameloop.md`](2026-08-04-en-pust-til-gameloop.md)
**Review:** [`2026-08-06-world-coordinates-plan-review.md`](../reports/2026-08-06-world-coordinates-plan-review.md) — accepted
**Branch:** `feat/en-pust-til-gameloop`

---

## Reference paths used throughout

| Shorthand | Path |
|---|---|
| `ENG/` | `…/scratchpad/pulseengine-src/src/main/kotlin/no/njoh/pulseengine/` |
| `GLSL/` | `…/scratchpad/pulseengine-src/src/main/resources/pulseengine/shaders/` |
| `REF/` | `…/scratchpad/caesars-salads/` |
| `OURS/` | `/Users/larstonder/Documents/capra/javazone26/` |

(`…/scratchpad/` = `/private/tmp/claude-501/-Users-larstonder-Documents-capra-javazone26/319bedb4-3720-4da6-8df1-49699f4d8962/scratchpad/`.)

Every engine claim below was read out of the Kotlin/GLSL source at those paths, not inferred from behaviour.

---

## 1. Findings

### 1.1 The shipped bug has a named mechanism, and it is not the aspect ratio

`DiveLighting.setup` creates an engine `Camera` **scene entity** (`OURS/src/main/kotlin/render/DiveLighting.kt:145-167`) and freezes its viewport at the window size observed during `onCreate`:

```kotlin
camera.viewPortWidth  = engine.window.width.toFloat()
camera.viewPortHeight = engine.window.height.toFloat()
camera.xOrigin = 0f
camera.yOrigin = 0f
```

That entity's `onFixedUpdate` then rewrites the *shared* main camera every fixed tick (`ENG/modules/scene/entities/Camera.kt:85-103`):

```kotlin
val surfaceWidth  = engine.gfx.mainSurface.config.width       // :91
val surfaceHeight = engine.gfx.mainSurface.config.height      // :92
val newScale = min(surfaceWidth / viewPortWidth, surfaceHeight / viewPortHeight) * zoom  // :93
engine.gfx.mainCamera.apply {
    scale.set(newScale)                                       // :96
    origin.x = surfaceWidth * xOrigin                         // :98
    position.x = surfaceWidth * xOrigin - x                   // :100
}
```

With `xOrigin = yOrigin = 0` and the entity at `x = y = 0`, `origin` and `position` are zero, so the resulting view matrix (`ENG/core/graphics/api/Camera.kt:118-134`) collapses to a **pure uniform scale about the screen's top-left corner**, of magnitude

```
s = min(mainSurface.config.width / viewPortWidth, mainSurface.config.height / viewPortHeight)
```

`SurfaceImpl.init` sets `config.width/height` from the *current* window size on every window change (`ENG/core/graphics/surface/SurfaceImpl.kt:46-47`, called from `GraphicsImpl.onWindowChanged:92`), while `viewPortWidth/Height` never change after `onCreate`. So:

> **`s == 1` if and only if the framebuffer is exactly the size it was when `onCreate` ran. Otherwise the world surface is scaled by `s` about `(0, 0)` and the HUD surface — which has its own camera — is not.**

That is precisely the reported symptom. `Hud` and `DiveRenderer` both compute `Viewport.screenX(sim.x, w, h)` from the same `w`/`h` (`OURS/src/main/kotlin/EnPustTil.kt:508-513, 531`), so before the camera they agree exactly; afterwards the world one is multiplied by `s`. A diver whose HUD anchor lands at `0.44·W` and whose world square lands at `0.64·W` implies `s ≈ 1.45` — a pure scale about x = 0, with nothing else able to produce that ratio, because `origin` and `position` are provably zero.

The framebuffer can differ from the `onCreate` value for at least three reasons, all silent:

* ~~`WindowImpl.createWindow` reads the framebuffer size (`ENG/core/window/WindowImpl.kt:94-96`) **before** `glfwShowWindow`, and then installs a framebuffer-size callback (`:107-113`) that fires on the first `glfwPollEvents` — which happens in `beginFrame`, *after* `initGame`/`onCreate` (`ENG/core/PulseEngineImpl.kt:69-73, 159-163, 216-224`). On a HiDPI/Retina backing this is a factor of 2.~~ **MEASURED AND DISPROVEN (2026-08-06, Task 1).** `getFramebufferSize` at `:94-96` runs *after* `glfwCreateWindow` and already returns PHYSICAL pixels, so the callback fires with the same numbers it was seeded with and nothing changes. Instrumented runs logged `window == mainSurface.config` and `scale = 1` from frame 1 in every start-up configuration tried, including a window that landed on a Retina panel (`windowWidth = 2100` → `window = 3456x1800`, i.e. a 2x backing, still `scale = 1`). Start-up is not a trigger.
* The `LEFT_ALT+ENTER` fullscreen toggle shipped in `init.pes` calls `WindowImpl.updateScreenMode` → `createWindow()` → a new framebuffer size (`ENG/core/window/WindowImpl.kt:131-145`). **CONFIRMED, and it is the trigger that reproduces the shipped report.** Booting windowed at 1200x900 and firing that toggle onto a 3440x1440 panel logged `scale=(1.6,1.6) origin=(0,0) position=(0,0)` — `min(3440/1200, 1440/900)` — and put the diver's world square at 0.632 of screen width against its own HUD air ring at 0.395, versus the reported ~0.64 / ~0.44.
* Moving the window between monitors with different content scales.

**Consequence for the plan:** the aspect ratio is a red herring for *this* bug — the trigger is a size change of any kind. But the diagnosis is worth stating precisely, because it decides the sequencing in §5: there is a two-line fix that closes this bug today, and it is not the migration.

### 1.2 `GlobalIlluminationSystem` does **not** require a scene `Camera` entity

`DiveLighting.kt:149-167` says the `Camera` entity "exists purely to satisfy GI's requirement for an active scene camera". That is not true of the engine as shipped.

`GlobalIlluminationSystem` reads `engine.gfx.mainCamera` directly and nothing else. It passes it explicitly to six of its nine surfaces (`ENG/modules/lighting/global/GlobalIlluminationSystem.kt:78, 130, 142, 153, 163, 175`) — the same object `mainSurface` uses (`ENG/core/graphics/GraphicsImpl.kt:47-58`). Grepping the whole `modules/lighting/global/` package for `Camera` returns only `surface.camera` and `engine.gfx.mainCamera`. `modules/scene/entities/Camera` is not imported anywhere in the engine outside its own file.

The only early-out in the system is `getSystemOfType<EntityRenderer>() ?: return` (`:184`), and it only skips *render-pass registration* — every GI surface is already created by that point, so immediate-mode `drawLight` works even with no `EntityRenderer` present at all.

`mainCamera` is created unconditionally at graphics init as `DefaultCamera.createOrthographic(window.width, window.height)` (`GraphicsImpl.kt:47`) with `position = (0,0,0)`, `origin = (0,0,0)`, `scale = (1,1,1)` (`ENG/core/graphics/api/Camera.kt:16-25`) — i.e. **identity**, and its projection is re-issued as `ortho(0, w, h, 0)` on every window change (`GraphicsImpl.kt:95`, `Camera.kt:103-110`). Left alone, it is a correct screen-pixel camera at every framebuffer size, forever.

So the `Camera` entity is not a requirement being satisfied. It is the only thing in the process that ever moves `mainCamera`, and it is the bug.

### 1.3 How the reference does it

`REF/` (Cæsar's Salads, same engine, shipped) is the intended-usage example.

* **Camera.** One `no.njoh.pulseengine.modules.scene.entities.Camera` per level, authored in the `.scn` file and never touched from Kotlin except for the death cinematic. `REF/src/main/resources/scenes/level_1.scn:1` (and `level_2.scn:1`, byte-identical):
  `viewPortWidth: 1920, viewPortHeight: 1080, xorigin: 0.5, yorigin: 0.5, targetEntityId: 1 (the Player), smoothing: 0.025, targetZoom: 0.5`.
  All follow logic is the engine's (`ENG/modules/scene/entities/Camera.kt:105-125`). The only game-side camera code is the round-end zoom/rotate: `REF/src/main/kotlin/gamestate/RoundFinishedState.kt:103-111`.
* **World units.** Not pixels. `scale = min(W/1920, H/1080) · 0.5`, so at 1080p one world unit is half a screen pixel; the Player entity is `200×200` world units and renders ~100 px tall. Level extents run to ~10 000 units. A `Backdrop` is `12595×13440`.
* **Resize / aspect.** No explicit handling anywhere; `grep aspect src/main/kotlin` returns zero hits. The `min(…)` in `Camera.kt:93` is a *contain* fit against the design viewport, recomputed every fixed update, so **a wider window simply reveals more world on the long axis** — no letterbox, no distortion. `engine.window.width/height` is read in exactly six places, all HUD text positioning in raw pixels (`REF/.../LoadLevelState.kt:56-71`, `MainMenuState.kt:42`, `RoundSummaryState.kt:62`). Honest caveat: the reference has **no** resolution-independence story at all — font sizes are hardcoded 1080p pixels (`fontSize = 250f` at `LoadLevelState.kt:73`) and it shipped on a known 1080p booth monitor (`REF/README.md:42`). Our height-fraction convention is strictly better and stays.
* **World rendering.** Scene entities implementing `onRender(engine, surface)`, dispatched by `EntityRendererImpl` (`REF/src/main/kotlin/systems/SceneRenderSystem.kt:18, 40-41`). Level content is 289 entities of `Wall`/`Backdrop`/`Torch`/`Lamp`/`SaladBowl` authored in a 143 KB `.scn`.
* **What `EntityRendererImpl`/`EntityUpdater` actually buy you.** Read `ENG/modules/scene/systems/EntityRenderer.kt:78-144`: a per-surface render pass, a back-to-front z sort (`:132`), a `HIDDEN` flag check, and dispatch to `onRender`. Nothing more — no culling, no batching we do not already get, no camera involvement. Two gotchas: it type-tests only the *first* entity of each type list (`:96`), and an entity that does not implement `Renderable` is silently never drawn. For a fixed cast of a diver, ~N pearls and one fish, generated from a seed, this buys us a z-sort we do not need and costs us the frame-ordering guarantee we fought for. **We are not adopting it** — this matches the earlier review's finding 20/21 (`.superpowers/sdd/2026-08-04-en-pust-til-gameloop/caesars-comparison.md`).
* **Lighting.** GI only; `DirectLightingSystem` appears in neither scene file. `REF/src/main/kotlin/entities/level/Torch.kt:21` is `CommonSceneEntity(), GiLightSource` and overrides `onRenderLightSource` (`:76-92`) to draw a sprite-sheet flame frame.
* **The crux — how non-main surfaces get their camera.** `REF/src/main/kotlin/systems/SceneRenderSystem.kt`:
  * `:23-28` — `createSurface(name = SURFACE_MENU_UI, multisampling = MSAA16, backgroundColor = BLANK, zOrder = -90)` with **no `camera` argument**. `GraphicsImpl.createSurface` then builds a fresh `DefaultCamera.createOrthographic(surfaceW, surfaceH)` (`ENG/core/graphics/GraphicsImpl.kt:186`), i.e. an identity screen-pixel camera that world scrolling can never touch. **This is exactly what our `"hud"` surface already does** (`OURS/src/main/kotlin/EnPustTil.kt:402-407`).
  * `:31-37` — a *second* UI surface, `SURFACE_WORLD_UI`, created with `camera = engine.gfx.mainCamera` explicitly, for in-world tutorial text that must scroll with the level. Same for its debug surface (`PathFindingSystem.kt:30`).

  So the reference's answer to "screen-anchored UI vs world-anchored UI" is **two surfaces, distinguished only by which camera they are handed.** That is one of the two viable answers for our air ring and held count; see §2.4 for which we pick and why.

### 1.4 What the engine `Camera` entity's transform actually is

For a world point `p`, with `o = origin`, `s = scale`, `pos = position` (`ENG/core/graphics/api/Camera.kt:125-131`):

```
viewMatrix = T(o) · S(s) · R · T(pos − o)
screen(p)  = o + s · (p + pos − o)
```

* `origin` is in **screen pixels** — the pivot for scale and rotation, and where the camera's target world point lands. The entity sets it to `(W·xOrigin, H·yOrigin)` (`:98-99`).
* `position` is `origin − worldTarget`, mixing units by the engine's own convention (`:100-101`).
* `scale` is `min(W/viewPortWidth, H/viewPortHeight) · zoom` — a **contain fit**. On a window wider than the design viewport, `H/viewPortHeight` wins and the vertical world extent is exactly `viewPortHeight`, with extra world revealed horizontally. On a narrower window, `W/viewPortWidth` wins and you see *less* vertically. **This matters for us:** `Framing.VISIBLE_DEPTH_METRES = 60` is a gameplay constant — how far ahead you can see is how far ahead you can plan — and it must not shrink on a 4:3 panel. See §2.2.
* `projectionMatrix` is `ortho(0, width, height, 0, near, far)` (`:108`), rebuilt for **every surface camera** on every window change (`GraphicsImpl.kt:95`). So a camera left at defaults is a correct screen-pixel camera at any framebuffer size, and it stays correct across resizes for free.
* `topLeftWorldPosition` / `bottomRightWorldPosition` (`:31-33`) are refreshed once per frame in `GraphicsImpl.initFrame` (`:112`) as `screenPosToWorldPos(0,0)` and `screenPosToWorldPos(mainSurface.config.width, height)` — i.e. **the engine hands you the visible world rect**, aspect-correct, for free.
* `worldPosToScreenPos(x, y)` (`:97-101`) multiplies by the *same* `viewMatrix` the frame will be drawn with. **Hazard:** it returns a single shared `Vector2f` (`:85`) — no allocation, but a second call clobbers the first. Read `.x`/`.y` into locals immediately.
* `isInView(x, y, w, h, padding)` (`:112-116`) is a correct AABB test against the visible world rect, in x as well as y.

### 1.5 Frame ordering — where a one-frame drift can and cannot happen

From `ENG/core/PulseEngineImpl.kt`:

```
beginFrame()  :216-224   gfx.initFrame  -> camera.updateViewMatrix(); camera.updateWorldPositions(...)
tick()        :226-231   update(game)      -> game.onUpdate(); scene.update()
                         fixedUpdate(game) -> per step: gfx.updateCameras() [updateLastState]; game.onFixedUpdate(); scene.fixedUpdate()
                         render(game)      -> game.onRender(); scene.render()
drawFrame()   :235-241   gfx.drawFrame  -> surfaces submit their batches with the matrix built in beginFrame
```

Three consequences, all load-bearing:

1. **The matrix used for frame N was built at frame N's `beginFrame`, before any camera write that frame.** Everything drawn through `mainCamera` is therefore uniformly one frame behind camera motion — world geometry on `main` *and* lights on `gi_local_scene`, because they share the same camera object. Uniform lag is invisible. **Relative drift between a light and the thing it lights becomes structurally impossible once both are submitted in world coordinates** — you could not reproduce the old bug on purpose.
2. **`updateViewMatrix` interpolates `positionLast → position`** by `PulseEngine.INSTANCE.data.interpolation` (`ENG/core/graphics/api/Camera.kt:120-123`, `ENG/core/shared/utils/Extensions.kt:47-55`), and `positionLast` is snapshotted at the *top of each fixed step* (`PulseEngineImpl.kt:279` → `GraphicsImpl.updateCameras:247`). Writing `position` from the render clock therefore pairs a render-clock value with a fixed-step value and makes the interpolator pull the camera backwards by up to `interpolation` — sub-frame judder. `positionLast` is on `CameraInternal` and unreachable from our code, so this cannot be defeated. **The camera must be written from `onFixedUpdate`.** `DiveCamera`'s easing is already `1 − e^(−k·dt)` and therefore frame-rate independent, so nothing is lost by sampling it at 60 Hz; the engine's interpolator then produces *smoother* motion above 60 fps than the current render-clock update does.
3. **The HUD is the new drift risk.** The HUD surface has its own identity camera, so anything on it that must sit on the diver has to be transformed by hand. If that transform reads `DiveCamera.depth` directly (the obvious port), it will use the camera state *after* this frame's fixed steps while the world uses the state from `beginFrame` — the air ring will lead the diver by one frame, exactly the drift `DiveLighting`'s class doc records killing. The fix is `mainCamera.worldPosToScreenPos`, which reads the same `viewMatrix` the world will be drawn with. §2.4.

### 1.6 `GiLightSource` entities vs immediate-mode `GiSceneRenderer.drawLight`

They are **the same data path**. `GiLightSource.onRenderLightSource` (`ENG/modules/lighting/global/GiLightSource.kt:38-58`) calls `surface.getRenderer<GiSceneRenderer>()?.drawLight(...)` with `texture = engine.asset.getOrNull(lightTexture) ?: Texture.BLANK` — the same method, the same instance buffer, the same `config.currentDrawColor`.

* **Sprite-shaped lights come from the `texture` parameter, on both paths.** The texture handle is a per-instance vertex attribute (`GiSceneRenderer.kt:49, 119`) sampled in `GLSL/lighting/global/scene.frag:62-78`, which `discard`s where `texColor.a < 0.5` and emits `vertexColor * texColor`. Immediate mode can pass any `Texture` and additionally exposes `cornerRadius`, `xTiling`, `yTiling`, which the entity path never sets. **Entities buy us nothing here.**
* **The one real asymmetry:** entity lights are collected by *two* passes — `GI_LOCAL_SCENE` and `GI_GLOBAL_SCENE` (`GlobalIlluminationSystem.kt:70, 72`). Immediate mode reaches only the surface whose renderer you fetched. We draw to `GI_LOCAL_SCENE` only, so our lights are absent from the global/world SDF and never contribute off-screen light via `traceWorldRays` (`GLSL/lighting/global/radiance_cascades.frag:82-91`). This is a pre-existing property, unchanged by the migration. If long-range bleed in the Abyss is ever wanted, the fix is a second `drawLight` onto `GI_GLOBAL_SCENE`'s renderer — **not** adopting entities.
* **Verdict: keep immediate mode.** It preserves the frame-ordering guarantee, it can already do everything the entity path can for our case, and it needs no per-frame repositioning step that can fall out of sync.

### 1.7 GI parameters that are defined in terms of camera scale

Today `mainCamera.scale.x == 1`. After the migration it becomes `surfaceHeight / 60` — roughly **30 on a 1800 px framebuffer**. Three shader-side quantities are multiplied by that value (`camScale`, uploaded at `GiSceneRenderer.kt:88` and `GiRadianceCascades.kt:78`):

| Quantity | Where | Effect of `camScale` going 1 → ≈30 |
|---|---|---|
| light `radius` | `radiance_cascades.frag:120-127` — `radius * camScale / dist²`, `dist` in light-texture pixels | **None for us** — we pass `radius = 0f` everywhere, which skips the branch. But its meaning changes, so anyone who later sets it must re-tune. |
| `aoRadius` | `ao.frag:36` — `radius = aoRadius * camScale`; uploaded at **`GiAo.kt:48`** | **Real regression risk, but NOT because the radius scales.** See the unit derivation below. `camScale` cancels; the shader is already scale-invariant in world units. What changes is the *world unit itself* — 1 px becomes 1 m — so the engine default `30f` (`GlobalIlluminationSystem.kt:57`) silently goes from a ~120 px radius to a **120 metre** one, twice the visible column, i.e. AO degenerates into full-screen darkening. Fix is a constant metre value set once — Task 6. |
| minimum light quad size | `scene.vert:86-88` — `max(size, pixelSizeInWorld · 1500 / camScale)` | **None.** `pixelSizeInWorld = 1/(resolution.y)` for an orthographic camera, so the floor is `1500/(resolution.y · camScale)` *world* units, which is a constant number of screen pixels. Scale-invariant by construction. |

> **The `aoRadius` unit derivation, because the obvious reading of `ao.frag:36` is backwards.**
> `ao.frag` marches `ray` in **SDF texels**: `:53-64` divides by `localSdfTexRes`, and the SDF is
> fragCoord-based (`sdf.frag:14, 20`). `camScale` is `mainCamera.scale.x`, i.e. **screen pixels per
> world unit**. Working `radius = aoRadius * camScale` through to a world distance, `camScale`
> cancels and what is left is
>
> ```
> radius_world = aoRadius / localSceneTexScale
> ```
>
> — **no camera scale in it at all.** The multiply at `:36` is a world→texel conversion, not a zoom
> knob. So the AO radius is *already* scale-invariant in world units, and removing that multiply
> would **introduce** zoom-dependence rather than remove it. Do not "fix" the shader; we do not
> modify engine shaders in any case.
>
> The regression is that the **world unit changes meaning**. Today one world unit is one screen
> pixel, so the engine default `aoRadius = 30` with our `localSceneTexScale = 0.25f`
> (`DiveLighting.kt:225`) is `30 / 0.25 = 120` *pixels* — a plausible-looking halo nobody chose.
> After the migration one world unit is one metre, so the same untouched default is **120 metres**:
> twice `VISIBLE_DEPTH_METRES`, i.e. every pixel on screen is inside every occluder's AO radius and
> the effect stops being ambient occlusion and becomes a flat darkening. Task 6 sets a constant
> **metre** value instead.

A **fourth** consumer, harmless today but worth naming so nobody thinks the table is exhaustive:
`GlobalIlluminationSystem.onFixedUpdate` (`:220-231`) copies `mainCamera.position`/`rotation`/`scale`
into `GI_GLOBAL_SCENE`'s camera whenever `traceWorldRays` (default `true`). We draw nothing to that
surface, so it changes nothing for us — but `DiveLighting`'s own existing comment already cites
`:227-229`, and the table did not. Note also that `GI_GLOBAL_SCENE` is the only GI surface created
*without* `camera = mainCamera` (`:90`), which is why §1.2 says "six of its nine surfaces".

One quantity gets **better**: `jitterFix` (`GiSceneRenderer.kt:150-162`) derives a sub-pixel UV offset from `camera.viewMatrix.m30()/m31()`. Today that translation is always zero, so the quarter-resolution light map cannot be jitter-compensated at all and light sampling snaps to the light-texture grid as pearls scroll past a stationary camera. After the migration the camera translates and the compensation actually engages.

`minReflectance` is **not** camera-dependent — it is a colour test in `GLSL/effects/texture_multiply_blend.frag:17-21` against the Euclidean length of the linear albedo, fed from `GlobalIlluminationSystem.kt:56` (default `0.02f`) and re-pushed every frame (`:217`). The migration cannot touch it.

> **Correction to an existing comment.** `DiveRenderer.kt:70-72` says the reflectance floor "cannot be avoided from here (it is the engine's…)". It can: `minReflectance` is a public `@Prop var` and setting `system.minReflectance = 0f` in `DiveLighting.setup` would remove the floor entirely. The current colour-side solution is measured, working and cheaper to trust than a lighting change made in the same pass as a coordinate change, so **we are not changing it** — but the comment should stop claiming impossibility. Task 9.

---

### 1.8 The entity layer — what the editor actually gives you

Every claim in this section was read out of `ENG/modules/editor/` and `ENG/core/scene/`. It is deliberately blunt, because the workflow being promised — live tuning, asset pickers, values that persist — is only *partly* real, and which part is real decides the whole design.

#### 1.8.1 The Outliner reads 0/0 for **two** independent reasons, and only one of them is "no entities"

**(a) There are no entities.** `DiveLighting.setup` calls `engine.scene.createEmptyAndSetActive("dive.scn")` (`OURS/src/main/kotlin/render/DiveLighting.kt:204`). That *creates* an empty scene which happens to be named `dive.scn`; it never reads a file, and there is no `dive.scn` anywhere in `src/main/resources/` (contents: `pulseengine/`, `application.cfg`, `application-dev.cfg`, `init.pes`, `init-dev.pes`).

**(b) The scene is RUNNING, and the editor's whole viewport is gated on STOPPED.** `engine.scene.start()` (`DiveLighting.kt:255`) leaves the scene in `SceneState.RUNNING` for the entire process. `SceneEditor.onUpdate` wraps *all* viewport interaction — entity selection, the move/resize gizmo, rubber-band select and the editor's own camera controller — in

```kotlin
if (enableViewportInteractions && engine.scene.state == SceneState.STOPPED)   // SceneEditor.kt:345
```

and only requests viewport input focus when STOPPED (`SceneEditor.kt:330-334`). **So simply adding entities would still give no gizmo, no viewport selection and no rubber-band — the three things `6b05f07` just made drawable on this Mac.** Anyone who adds entities and stops there will conclude the editor is broken.

**And `start()` turns out not to be required.** `SceneManagerImpl.update()` calls `activeScene.update(engine)` unconditionally (`ENG/core/scene/SceneManagerImpl.kt:206`). `Scene.update` initialises and updates every *enabled* system with **no `SceneState` check at all** (`ENG/core/scene/Scene.kt:80-97`), and `Scene.render` is the same (`:113-117`); `SceneSystem.init` is what calls `onCreate` (`ENG/core/scene/SceneSystem.kt:21-25`). `GlobalIlluminationSystem` overrides exactly `onCreate` (`:74`), `onUpdate` (`:192`), `onFixedUpdate` (`:220`) and `onDestroy` (`:234`) — **there is no `onStart`**. The `MultiplyEffect` that darkens the Abyss is installed from `onUpdate` (`GlobalIlluminationSystem.kt:213`), which runs regardless of state.

> **Correction to an existing comment.** `DiveLighting.kt:141-143` says GI "needs an active scene to live in and a RUNNING one for its `onUpdate` to install the multiply effect … hence `createEmptyAndSetActive` here and `engine.scene.start()` at the end". The first half is right; **the second half is false** — nothing in `Scene.update`/`Scene.render` consults `SceneState`. Only `EntityUpdater` and individual entities self-gate on RUNNING (`ENG/modules/scene/systems/EntityUpdater.kt:33-34, 39-40`), and we have neither. Task 10 fixes the comment and makes the `start()` call conditional.

#### 1.8.2 What `CommonSceneEntity` is, exactly

`ENG/modules/scene/entities/CommonSceneEntity.kt:16` — `abstract class CommonSceneEntity : SceneEntity(), Initiable, Updatable, Renderable, Spatial, Named`.

| From | Property | Type | Default | Line |
|---|---|---|---|---|
| `SceneEntity` | `id`, `parentId`, `childIds`, `flags` | `Long`, `Long`, `LongArray?`, `Int` | — | `ENG/core/scene/SceneEntity.kt:14-24` |
| `Named` | `name` | `String` | `""` | `CommonSceneEntity.kt:18` |
| `Spatial` | `x`, `y`, `z`, `width`, `height`, `rotation` | all `Float` | `0, 0, -0.1, 100, 100, 0` | `CommonSceneEntity.kt:19-24` |

Flags: `DEAD=1`, `POSITION_UPDATED=2`, `ROTATION_UPDATED=4`, `SIZE_UPDATED=8`, `DISCOVERABLE=16`, `SELECTED=32`, `EDITABLE=64`, `HIDDEN=128` (`SceneEntity.kt:50-57`).

**Positions are world units.** `x`/`y`/`width`/`height` are fed straight to `surface.drawTexture` inside the entity's own `onRender`, on `mainSurface`, through `mainCamera`. This is the sequencing argument in one line: **with today's identity camera an entity at `(12, 94)` lands 12 px right and 94 px down from the top-left corner.** Entities are meaningless before Stage B.

Lifecycle, and who dispatches what:

| Hook | Interface | Dispatched by | Runs when |
|---|---|---|---|
| `onCreate()` | `Initiable` | `Scene.insertEntity` itself (`Scene.kt:57-58`) and on deserialization (`Scene.kt:151-157`) | always |
| `onStart(engine)` | `Initiable` | `EntityUpdater` (`EntityUpdater.kt:28`), only from `Scene.start()` | only if `EntityUpdater` is in the scene |
| `onUpdate` / `onFixedUpdate` | `Updatable` | `EntityUpdater` (`:31-41`) | only if `EntityUpdater` is present **and** `state == RUNNING` |
| `onRender(engine, surface)` | `Renderable` | `EntityRendererImpl` (`EntityRenderer.kt:135`) | any state, `enabled && initialized` |
| `onDestroy` | — | **does not exist on entities** | — |

**This is load-bearing for the boundary.** `EntityUpdater` was removed in `6ea1f53`. Without it **no entity in this game can ever receive `onUpdate` or `onFixedUpdate`** — an entity therefore *cannot* evolve state of its own, structurally, not by convention. §2.6 turns that into a review rule and Task 11 into a failable test.

Deletion is `entity.set(DEAD)` (`SceneEditor.kt:547-551` is the canonical form, and it cascades to children by hand); compaction happens at the end of `Scene.update` (`Scene.kt:99-105`), which runs in every state. Note `EntityRendererImpl` checks `HIDDEN` but **not** `DEAD` (`EntityRenderer.kt:100`), so an entity killed in `onFixedUpdate` still renders for one frame.

#### 1.8.3 Per-entity cost, and whether ~100 pearls is reasonable

`EntityRendererImpl.onRender` = `buildRenderQueue` + `drawRenderQueue` (`EntityRenderer.kt:83-87`). Per frame, per render pass:

* `engine.scene.forEachEntityTypeList` walks every type list; **the type test is done on element 0 only** (`:95-96`) and the rest of the list is assumed homogeneous. Safe, because `entityTypeMap` is keyed on the exact `entity::class.java` (`Scene.kt:46-53`).
* Per entity: one `isNot(HIDDEN)` bitmask test, one checked cast, one `ArrayList.add` (`:98-104`).
* **`entities.sortWith(BackToFrontEntityComparator)` (`:132`) — the engine's own source carries the comment `// TODO: This creates alot of garbage internally`.** It is `Arrays.sort(Object[], Comparator)` → TimSort. **Corrected (review claim 5):** that is the *only* steady-state garbage on this path, and it is not unconditional — `Arrays.sort` allocates nothing below n = 32 and is skipped entirely below n = 2. The **render queue itself is pooled**, not rebuilt: `taskPool` (`:76`), `createRenderTask` allocates only when the pool is empty (`:114-121`), `entities.clear()` retains capacity (`:138`), the task goes back to the pool (`:140`) and `forEachFast` is `inline`. So the allocation objection applies to a *large* cast, not to the entity path as such.
* Per entity: one virtual `onRender(engine, surface)` (`:135`; the plan previously cited `:133-137`, which is the enclosing `when`).
* **There is no culling anywhere in this file** — no frustum test, no `SpatialGrid` query, no bounds check. Every non-`HIDDEN` `Renderable` in the scene is sorted and drawn every frame, on screen or not.

Our cast if we converted everything: `PearlColumn.PEARLS_PER_ZONE = 14` × 5 zones = **70 pearls** (`OURS/src/main/kotlin/dive/Pearl.kt:26`, `dive/Zone.kt:13-17`), 3 vents (`dive/AirPocket.kt:34`), 1 anglerfish, 1 diver — **75 entities**, i.e. the ~100 in the question.

Is 75 quads a frame expensive? In isolation, no. But three specific things are worse than they look:

1. **At 75 entities it starts allocating every frame.** `CLAUDE.md`: *"No per-frame allocation in the render path."* TimSort's temp array at `EntityRenderer.kt:132` is allocated only at n ≥ 32 — which 75 pearls clears and today's zero-entity scene does not. **This is an argument against converting pearls, not against entities**; it does not apply at a cast of one.
2. **It loses culling we already have.** `drawPearls` currently rejects off-screen pearls by screen y (`DiveRenderer.kt:237`). With ~60 m visible out of a 160 m column, the entity path submits roughly **2.7× more geometry** than today, and Task 7 (`cam.isInView`) would become unreachable for exactly the objects it was written for. **This is the strongest of the three** and it does not depend on any measurement of the engine's internals.
3. **`BackToFrontEntityComparator` violates the `Comparator` contract, and 70 > 32 — stated at its measured strength, which is lower than the plan originally claimed.** It is `((b.z - a.z) * 10_000f).toInt()` (`EntityRenderer.kt:155-158`). Any two entities whose `z` differs by less than 1e-4 compare equal, so `z = 0.0, 0.00005, 0.0001` gives `a≈b`, `b≈c`, `a≠c` — intransitive, provably. **What the review measured (claim 3):**
   * n ≥ 32 is the correct and *tight* floor for TimSort to be able to throw `IllegalArgumentException: Comparison method violates its general contract!` — exhaustive for n ≤ 15, and 20 M random trials at n = 31 threw zero times.
   * Throwing is **probabilistic, not certain**: ~7 % of arrangements at n = 32, ~33 % at n = 70.
   * It only bites inside a narrow band — a **total z spread of roughly 2e-4 … 1e-3**. Both idiomatic layering schemes measured **0 %** at every N up to 50 000: discrete layers ≥ 1e-4 apart with sub-1e-4 jitter, and plainly separated z.
   * Inside the band, **100 % of the runs that did not throw came out with a strict ordering inversion.** The choice there is crash-or-mis-layered, not crash-or-correct.

   So the original phrasing — "the moment someone jitters pearl `z` for layering, the booth cabinet crashes" — is **not supported** and is withdrawn. The honest statement is: this is a real latent trap with a narrow trigger and a one-in-three failure rate inside it, and it is a reason to be deliberate about `z`, not the load-bearing reason to avoid entities. Reason 2 carries that on its own.

One more ordering fact: `game.onRender()` runs **before** `scene.render()` (`ENG/core/PulseEngineImpl.kt:298-299`), so **entity draws always composite on top of every immediate-mode draw on the same surface.** Fine for actors-over-background; it means you can never put an immediate-mode object in front of an entity one.

#### 1.8.4 Annotations and the Inspector — what is genuinely editable

Declared in `ENG/core/shared/annotations/`:

| Annotation | Where | What it does |
|---|---|---|
| `@Prop(group, i, hidden, editable, min, max, desc)` | `Prop.kt:12-32` | grouping, ordering, hiding, numeric clamp |
| `@AssetRef(type)` | `AssetRef.kt:19-20` | asset picker filtered to `type` |
| `@TexRef` | `AssetRef.kt:28-29` | meta-annotated `@AssetRef(Texture::class)` |
| `@SpriteSheetRef` | `AssetRef.kt:36-37` | meta-annotated `@AssetRef(SpriteSheet::class)` |
| `@SoundRef`, `@FontRef` | `AssetRef.kt:44, 52` | same, other asset types |
| `@Icon(iconName, size, hexColor, textureAssetName, showInViewport)` | `Icon.kt:9-14` | **class-level, not property-level** |

`@TexRef`/`@SpriteSheetRef` resolve because `ReflectionUtil.findPropertyAnnotation` flattens meta-annotations (`ENG/core/shared/utils/ReflectionUtil.kt:97`) and searches **getters**, so on an interface property you must write `@get:TexRef`.

The Inspector is built by `SceneEditor.selectSingleEntity` (`SceneEditor.kt:954-1017`): `entity::class.memberProperties` → drop `@Prop(hidden)` → group → `filterIsInstance<KMutableProperty<*>>()` → `isEditable()` (not private/protected, no `@JsonIgnore` — `EditorUtil.kt:27-30`). Widget dispatch is `UiElementFactory.propertyUiFactories` (`:39-50`) with a fallback at `:705-708`:

| Kotlin type | Widget |
|---|---|
| `String` **with** `@TexRef`/`@AssetRef` | **asset picker** — searchable popup, live texture thumbnails (`UiElementFactory.kt:590-635`, `AssetPicker.kt:194-206`) |
| `String` plain | text field |
| `Float`, `Double` | number field **with a mouse-drag stepper** (`InputField.kt:455-467`) — this is the closest thing to a slider; there is no slider widget |
| `Int`, `Long`, `Char` | integer field + stepper |
| `Boolean` | dropdown (`true`/`false`) |
| any `enum` | dropdown of the constants |
| `Color` | full colour picker, bound to the entity's own `Color` instance and mutated in place (`UiElementFactory.kt:43`, `ColorPicker.kt:178-201`) |
| `FloatArray`/`IntArray`/… | comma-separated text field |
| **anything else** (`Vector2f`, `List`, nested objects) | a text field showing `toString()` that **silently cannot write back** — `EditorUtil.setPrimitiveProperty` has `else -> null` (`EditorUtil.kt:74`) and no log line |

`@Icon` is a class annotation and drives the Outliner row icon and colour (`Outliner.kt:458-464`) and, with `showInViewport = true`, an in-viewport billboard at the entity's world position (`SceneEditor.kt:440-466`) — which is how you find an entity that draws nothing.

**Edits are immediate.** Every valid keystroke calls `property.setter.call(...)` on the live instance (`UiElementFactory.kt:684-692` → `EditorUtil.kt:63-80`); dropdowns at `:76`/`:93`, asset picker at `:632`, colour by aliasing. There is no apply step and no undo.

Three gotchas worth designing around:

* **`@Prop(editable = false)` only works on numeric/text `InputField`s** (`UiElementFactory.kt:673`). Enums, booleans, `Color` and asset refs ignore it — you cannot lock those.
* **`@Prop(min/max)` are only honoured for `FLOAT`/`INTEGER` fields** (`:675-682`).
* **`@Prop(desc = …)` is dead metadata** — declared at `Prop.kt:32`, zero readers in `modules/editor`. No tooltips.

#### 1.8.5 Play mode — the decisive finding

**`PulseEngineGame.onUpdate`/`onFixedUpdate`/`onRender` are never gated by scene state.** `PulseEngineImpl.kt:256, 280, 298` call them every frame unconditionally. "Play" does not switch the game on; the game was already running while you were editing. What `start()`/`stop()` change is `EntityUpdater`'s ticking of entities, and nothing else that matters to us.

Two ways to start from the editor:

* **`Run → Start` / F2** — `stopEditorAndStartGame` (`SceneEditor.kt:514-530`): `stop()`s the editor **Service**, so the entire editor UI stops updating and rendering (`ServiceManagerImpl.kt:35, 43, 51`). Outliner and Inspector are simply gone.
* **F10** — `SceneEditor.kt:358-370`: starts the scene but **leaves the editor UI up**. This is the only mode where "tune while it runs" exists at all.

In F10 mode:

* **Outliner selection and Inspector editing work while RUNNING.** `rootUI.update(engine)` (`SceneEditor.kt:381`) is not state-gated; clicking a row reaches `selectSingleEntity` (`:261-272`) and every subsequent edit hits `setter.call` on the live entity. **This is the real prize, and it is real.**
* **Viewport selection and the gizmo do not** — `:345`.
* **Entities added at runtime do not appear in the Outliner *automatically*.** The only refresh is `outliner?.reloadEntitiesFromActiveScene()` behind `if (engine.scene.activeScene.hashCode() != lastSceneHashCode)` (`SceneEditor.kt:336-343`). `Scene` has no `hashCode` override (`Scene.kt:17-21`), so that is an *identity* hash which changes only when a different `Scene` instance becomes active. `Scene.insertEntity` (`Scene.kt:42-61`) notifies nobody. `Outliner.addEntities` (`Outliner.kt:244-269`) is called from `SceneEditor.createNewEntity` (`:933`), reparenting (`:979`) and Ctrl+D duplicate (`:565`).

  > **Corrected (review claim 1): "never appear" is false and must not be repeated.** Closing the Outliner window removes it from its parent (`UiElementFactory.kt:150`), and reopening it from Windows → Outliner rebuilds it and calls `reloadEntitiesFromActiveScene()` (`SceneEditor.kt:276`). A runtime-spawned entity *is* reachable — by closing and reopening one window. The accurate claim is "no automatic refresh", which is an annoyance, not a wall.
  >
  > **The argument that actually kills pearls-as-entities is simpler and does not depend on any editor implementation detail:** the pearls are **regenerated from `dailySeed` on every run** (design spec §10), so any value tuned on pearl #37 is discarded at the next `lifecycle.justStarted` — and anything that *did* persist would be the §1.8.6 scene-pollution hazard, i.e. the failure mode, not the feature. Seventy pearls buy **none** of the workflow and pay all of the cost in §1.8.3. Use this reason; it is unrefutable and the Outliner one is not.

* **The Inspector never re-reads values from the entity.** `updateEntityPropertiesPanel` (`SceneEditor.kt:1030-1033`) pushes back only `x`/`y`/`rotation`/`width`/`height`, and only when the *gizmo* moved them. Anything game code writes goes stale in the panel immediately. A diver entity whose position is pushed every fixed tick will show a frozen `x` while the diver visibly moves. Not fatal — but say so in the entity's class doc, or the first person to look will file it as a bug.

**STOP discards everything.** `stopGameAndStartEditor` (`:533-543`) and the F10 stop (`:366-369`) both do `engine.scene.stop(); engine.scene.reload()`, and `reload` is `loadAndSetActive(activeScene.fileName)` (`SceneManagerImpl.kt:175-178`) → a full deserialize from disk, `clearAll()`, `System.gc()` (`:113-135`). **A value tuned while RUNNING is lost unless you press Ctrl+S first.**

#### 1.8.6 Persistence — how a tuned value reaches the `.exe`, and the pollution hazard

`engine.scene.save()` → `SceneManagerImpl.save` (`:148-160`) → `activeScene.optimizeCollections()` → Jackson (`ENG/core/data/DataImpl.kt:162-166`, with `enableDefaultTyping()`) → `saveDirectory/fileName` unless `fileName` is absolute (`DataImpl.kt:151-152`). `Scene` is `@JsonAutoDetect(fieldVisibility = ANY)` (`Scene.kt:16`), so **every entity currently in `Scene.entities` is written, including any added at runtime.** The only exclusion mechanism is `@JsonIgnore`; there is no persist/transient flag on entities. `@Prop(hidden = true)` hides from the Inspector but **still serializes**.

Save triggers (`SceneEditor.kt`): `File → Save` (`:192`), `File → Save as…` (`:193`), **Ctrl+S, which works whenever the editor service is up — including while RUNNING (`:163-164`)**, and start-of-play, which saves only `if (state == STOPPED)` (`:526-529`, `:360-363`) — i.e. the authored pre-run state. **Stop never saves.**

So the route to the shipped `.exe` is exactly the reference's: point the save at the source tree, tune, save, rebuild.

> **Hazard, stated plainly.** `engine.config.saveDirectory` is already where `ScoreRepository` writes `scoreboard.json` (`OURS/src/main/kotlin/score/ScoreRepository.kt:184`). Repointing it at `src/main/resources/` to catch `dive.scn` would also drop the scoreboard and its timestamped backups into the source tree. Use an **absolute** scene `fileName` in dev instead — `DataImpl.kt:151-152` takes the absolute path in preference to `saveDirectory` — and leave `saveDirectory` alone.

> **Second hazard.** `enableDefaultTyping()` (`DataImpl.kt:164`) writes fully-qualified class names into the `.scn`, and `FAIL_ON_INVALID_SUBTYPE = false` (`:166`) makes an unresolvable one deserialize to `null` silently. **Renaming or moving an entity class breaks every existing scene with no error.** Pick the package once.

**What stops a runtime-spawned entity from being written into `dive.scn`, and what happens if one is?** At the engine level: *nothing*. `addEntity` pushes into the same list the serializer walks, and Ctrl+S works while running. If it happened, the next load would materialise day one's seeded pearls as **authored** entities *in addition to* day two's procedurally generated ones — silently, inside a single-line 100 KB JSON diff nobody reads — which is precisely the "every attendee faces an identical column" guarantee failing. Our answer (§2.6) is structural rather than procedural: **nothing in production calls `addEntity`, so nothing spawnable exists to be saved**, and Task 11 makes that a failable test plus a purity check on the committed `dive.scn`.

For the record, the reference's answer is weaker and would not survive our constraint: caesars-salads spawns four runtime types (`Enemy.kt:92` `EnemyHitBox`, `Torch.kt:65` `Spark`, `SaladBowl.kt:190` `SaladBowlPart`, `SaladBowl.kt:245` `SaladParticle`), none of which appears in any `.scn`. It relies on (a) `GameMain.kt:71-76` saving only when the editor service is running, (b) every runtime type self-killing on a TTL (`Spark.kt:67-68`, `SaladParticle.kt:59-60`, `SaladBowlPart.kt:64-65`), (c) `setNot(DISCOVERABLE)` in `init{}` (`Spark.kt:47`, `SaladParticle.kt:33`), and (d) the editor's stop→reload. That is four conventions and no test.

#### 1.8.7 The reference confirms the `.scn` is the tuning source of truth

Authored values in `REF/src/main/resources/scenes/level_1.scn` routinely override — and have drifted far from — the Kotlin defaults: `Torch.intensity` `4f` → `7.0`, `Torch.radius` `0f` → `50000.0`, `Player.bodySwaySpeed` `1f` → `0.003`, `Enemy.viewDistance` `1000f` → `1500.0`, `SaladBowl.eatSpeed` `0.025f` → `0.04`. The sharpest proof: `Torch.lightTexture` defaults to `"torch_flame"` (`Torch.kt:35`) but the actual asset is `torche_flame` (from `torche_flame_8x8.png`), so the code default resolves to nothing and `frameCount` would be `0` → a divide by zero in `onFixedUpdate` (`:53`). **The game only works because the editor-authored string in the `.scn` overrides it.** Post-processing is tuned the same way (`ColorGradingSystem {"exposure":2.0,…}`, `BloomSystem {"intensity":0.95,…}`) and `REF/README.md:39-44` explicitly tells you to tune `lightTexScale` *from inside the scene editor*.

Two things follow. First, **the workflow does deliver** — this is what real tuning on this engine looks like. Second, **it delivers via the property panel, not via one entity per object**: 289 authored entities in `level_1.scn` are level *geometry* placed by hand, while everything spawned procedurally (sparks, shards, particles) carries hardcoded Kotlin values and is never authored at all. Our pearls are the second category, not the first.

---

## 2. Target architecture

### 2.1 Coordinate spaces

| Surface | Camera | Space | Contents |
|---|---|---|---|
| `main` | `engine.gfx.mainCamera` | **world metres** | zone bands, column walls, waterline, air vents, pearls, anglerfish, diver |
| `gi_local_scene` | the same `mainCamera` object (`GlobalIlluminationSystem.kt:78`) | **world metres** | `GiSceneRenderer.drawLight` calls |
| `hud` | its own `DefaultCamera` (created by passing `camera = null`) | **screen pixels** | all of `Hud`, the attract/run-over/initials screens, the dev overlay |

World axes are **metres, +x right, +y down**. World `y` *is* `depth` and world `x` *is* `sim.x` — no sign flips, no offset, because the engine's orthographic projection is already y-down (`ortho(0, w, h, 0)`, `Camera.kt:108`) and `dive/` already measures depth downward. Nothing in `dive/` changes; nothing in `dive/` is even read differently.

`AimAngle`'s Y-flip for the GI cone direction is **unchanged**: the flip is between world/screen y-down and the framebuffer's y-up, and a uniform positive scale plus a translation does not alter it. (`radiance_cascades.frag:114-115` also adds the camera's rotation to the cone direction, so if the §12 cash-out ever rotates the camera, the beam compensates itself.)

### 2.2 Who owns the camera

**`engine.gfx.mainCamera`, written by a new `render/CameraRig.kt` from `onFixedUpdate`.** We do **not** use the engine `Camera` scene entity, for two reasons: its `viewPortWidth/Height` are the exact mechanism of the shipped bug (§1.1), and its `min(W/vpW, H/vpH)` contain fit would shrink `VISIBLE_DEPTH_METRES` below 60 on a panel narrower than the design aspect, which is a gameplay change (§1.4).

```kotlin
package render

import no.njoh.pulseengine.core.PulseEngine

/**
 * Applies [DiveCamera]'s world-space camera depth to the engine's shared main camera.
 *
 * The engine's view transform is  screen(p) = origin + scale * (p + position - origin)
 * (ENG/core/graphics/api/Camera.kt:125-131). Pinning the camera's world point (0, depth)
 * to screen (W/2, 0) and choosing scale = H / VISIBLE_DEPTH_METRES reproduces the old
 * Viewport.screenX/screenY EXACTLY -- which is what makes the migration a no-op on screen
 * and any visible change a bug.
 *
 * Deliberately NOT the engine's `Camera` scene entity: its viewport-fit scale
 * (min(W/vpW, H/vpH), Camera.kt:93) would show less than VISIBLE_DEPTH_METRES of water on
 * a panel narrower than the design aspect, and how far ahead you can see is a gameplay
 * constant, not a presentation one.
 *
 * MUST be called from onFixedUpdate. `updateViewMatrix` interpolates position from the
 * value snapshotted at the top of each fixed step (Camera.kt:120-123, PulseEngineImpl.kt:279);
 * writing it from the render clock pairs values that were never consecutive fixed states
 * and makes the interpolator judder. `positionLast` is on CameraInternal and unreachable.
 */
object CameraRig
{
    /** Screen pixels per world metre for a world surface [surfaceHeight] pixels tall. */
    fun pixelsPerMetre(surfaceHeight: Float) = surfaceHeight / Framing.VISIBLE_DEPTH_METRES

    /** Screen x that world x = 0 is pinned to. */
    fun originX(surfaceWidth: Float) = surfaceWidth * 0.5f

    /** Screen y that world y = cameraDepth is pinned to: the top of the frame. */
    const val ORIGIN_Y = 0f

    /** `position` is `origin - worldTarget` in the engine's convention (Camera.kt:100-101). */
    fun positionX(surfaceWidth: Float) = originX(surfaceWidth) - WORLD_X_AT_ORIGIN
    fun positionY(cameraDepth: Float) = ORIGIN_Y - cameraDepth

    fun apply(engine: PulseEngine, cameraDepth: Float)
    {
        val config = engine.gfx.mainSurface.config
        val w = config.width.toFloat()
        val h = config.height.toFloat()
        val s = pixelsPerMetre(h)
        engine.gfx.mainCamera.apply {
            scale.set(s, s, 1f)
            rotation.set(0f, 0f, 0f)
            origin.set(originX(w), ORIGIN_Y, 0f)
            position.set(positionX(w), positionY(cameraDepth), 0f)
        }
    }

    private const val WORLD_X_AT_ORIGIN = 0f
}
```

Sizes come from `mainSurface.config.width/height`, **not** `engine.window.width/height`. They are the same number today, but `config` is what the surface's own projection was built from (`SurfaceImpl.kt:46-47`, `GraphicsImpl.kt:92-95`), so it is the only value that cannot disagree with what is actually being rendered.

### 2.3 What happens to each file

| File | After |
|---|---|
| `render/Viewport.kt` | **Renamed to `render/Framing.kt`, `object Framing`.** Keeps `VISIBLE_DEPTH_METRES`, `DIVER_SCREEN_FRACTION`, `DIVER_MIN/MAX_FRACTION`, `CAMERA_SMOOTHING`, `targetCameraDepth`, `DIVER_SIZE_METRES`, `PEARL_SIZE_METRES`, `AIR_POCKET_SIZE_METRES`. **Loses** `pixelsPerMetre`, `screenX`, `screenY`, `depthAt`, `screenFraction`. Renamed rather than gutted in place so a stale "`Viewport.screenX` exists" mental model cannot survive the compiler. |
| `render/DiveCamera.kt` | Unchanged except `Viewport.` → `Framing.`. Still pure, still the easing model, still unit-tested. It stops being a coordinate authority simply by virtue of `Framing` no longer having a transform to be an authority over — `DiveCamera.depth` was always a world quantity. |
| `render/CameraRig.kt` | **New.** The only place that writes `engine.gfx.mainCamera`. |
| `render/CameraInvariants.kt` | **New.** Pure checks on the numbers the engine reports back, run in dev mode. See §3. |
| `render/DiveRenderer.kt` | Draws in metres. `render(surface, sim, cam: no.njoh…api.Camera)`; the strip loop and the walls walk `cam.topLeftWorldPosition`/`bottomRightWorldPosition`; culling via `cam.isInView`. **All colour code — `zoneRedAt`/`zoneGreenAt`/`zoneBlueAt`/`floorBlueForReflectance`/`srgbToLinear`/`reflectanceLength` and the `GI_REFLECTANCE_FLOOR` doc — is untouched.** |
| `render/Hud.kt` | Stops importing `Viewport` and `DiveCamera` entirely. `render(surface, sim, diverX, diverY, pixelsPerMetre, w, h)` — it is handed a screen position and a pixel-per-metre scale and stays pure screen space. Every pure helper (`airBubblesRemaining`, `airBubbleAngle`, `airBubbleSizeScale`, `authoredAlphaFor`) and the whole alpha-squared story are unchanged, so `HudTest` survives verbatim. |
| `render/DiveLighting.kt` | The `Camera` scene entity and its 19-line comment are **deleted** (§1.2). `drawLight` gets world metres: `x = pearl.x, y = pearl.depth, w = h = PEARL_LIGHT_SIZE_METRES`. `isOnScreen` is replaced by `cam.isInView`. Everything about cone angles, `coneMaskPeak`, `beamIntensity`, the ambient tables and `resetAim` is untouched. Gains one line: `aoRadius` compensation (Task 6). |
| `EnPustTil.kt` | `onFixedUpdate` gains `camera.update(engine.data.fixedDeltaTime, sim.depth)` then `CameraRig.apply(engine, camera.depth)`; `onUpdate` loses `camera.update`. `onRender` derives the diver's HUD anchor from `mainCamera.worldPosToScreenPos` and takes HUD `w`/`h` from the HUD surface's own `config`. `AttractLayout` is entirely screen-height fractions on the HUD surface and does not change. |

### 2.4 The crux: screen-anchored HUD that still tracks the diver

The HUD stays on its own surface with its own identity camera — non-negotiable, because `GlobalIlluminationSystem` multiplies its `targetSurface` (`"main"`, `GlobalIlluminationSystem.kt:64, 210-215`) by the light map, and BANKED in the Abyss was measured at RGB(11,8,1) when it lived there (`EnPustTil.kt:370-379`).

The air ring and the held count get their anchor from the world camera, not from `DiveCamera`:

```kotlin
// In EnPustTil.onRender, before Hud.render.
//
// worldPosToScreenPos multiplies by mainCamera's viewMatrix -- the SAME matrix the world
// surface will be drawn with this frame (built once in gfx.initFrame, GraphicsImpl.kt:111).
// Deriving the anchor any other way -- e.g. from camera.depth directly -- would read camera
// state from a different point in the frame and put the air ring one frame ahead of the
// diver. That is the drift DiveLighting's class doc records killing; this is where it would
// come back.
//
// The returned Vector2f is a SHARED instance (Camera.kt:85). No allocation, but the second
// call clobbers the first, so read both components out before calling again.
val cam = engine.gfx.mainCamera
val anchor = cam.worldPosToScreenPos(sim.x, sim.depth)
val diverX = anchor.x
val diverY = anchor.y
val pixelsPerMetre = cam.worldPosToScreenPos(sim.x + 1f, sim.depth).x - diverX
```

Two calls give an exactly-correct pixels-per-metre even on the single frame a resize is being interpolated through, which `mainCamera.scale.x` would not.

**The alternative, for the record.** The reference's own answer is a *third surface* created with `camera = engine.gfx.mainCamera` (`REF/src/main/kotlin/systems/SceneRenderSystem.kt:31-37`), which tracks the world with zero coordinate maths and sizes everything in metres. It is drift-free for the same reason and it would let the §12 cash-out digit flight be authored in world space. We are not taking it now because it costs a second full-resolution `RGBA16F` MSAA16 surface (meaningful on a 4K booth panel), duplicates the alpha-squared and z-order decisions, and would make the held-count font scale with any future camera zoom. Revisit it when the cash-out spectacle is built.

### 2.5 What is explicitly NOT in scope

* **`src/main/kotlin/dive/` does not change. At all.** This is a rendering migration. Not one file in `dive/` is edited, and no `no.njoh.pulseengine` import may appear there.
* **Pearl, air-vent and anglerfish placement stays procedural**, generated from `dailySeed`. No `.scn` file gains level content. Everyone at the booth faces the identical column and day two is a one-line config change (`application.cfg`, `dailySeed`) — see design spec §10 and its amendment log. The scene stays what it is today: an empty scene that exists so `GlobalIlluminationSystem` has somewhere to live.
* ~~**No scene entities for the diver, pearls, vents or fish.**~~ **AMENDED by Stage D (§2.6).** Still true for **pearls, vents and the fish**, for the reasons in §1.8.3/§1.8.5 — reinforced, not weakened, by the evidence. **Superseded for the diver**, which becomes a single authored appearance-only entity. `EntityRendererImpl` stops being a no-op kept for GI's sake and starts doing real work.
* **No `GiLightSource` migration.** §1.6. **Unchanged by Stage D** — and §4.11 records the new reason it must stay unchanged.
* **No art.** The placeholder squares stay squares; they just become 3 metres instead of 90 pixels. **Stage D does not add art either** — it builds the slot the art drops into.

---

### 2.6 Stage D: the entity layer — appearance only, authored only

**The one-sentence design: a small, fixed, `.scn`-authored cast of entities that own how things *look*, and nothing else. Not one entity per game object.**

#### What becomes an entity, and what does not

| Thing | Today | Stage D | Why |
|---|---|---|---|
| **Diver** | immediate | **`DiverEntity` — a real, positioned, authored `CommonSceneEntity`** | Singleton, so it is *in* the Outliner, selectable, live-editable and persisted. It is the thing that gets iterated on most (texture, size, beam), and `@Icon(showInViewport = true)` plus the gizmo let you place and scale it against the real world in metres. Position pushed from `DiveSim`; see below. **This is the direct answer to "should the player be its own entity?" — yes.** |
| **Pearls (×70)** | immediate, culled | **stay immediate**, but read their appearance from an authored `PearlLook` prototype | Runtime-spawned entities are invisible in the Outliner (§1.8.5) — 70 of them buy *zero* workflow and cost the culling, the per-frame TimSort allocation and the intransitive-comparator crash risk (§1.8.3). A single `PearlLook` gives the identical asset picker, the identical live number field and the identical persistence, for one entity. |
| **Air vents (×3)**, **anglerfish (×1)** | immediate, culled | same: **immediate, driven by an authored `VentLook` / `AnglerfishLook`** | Same argument, and the fish is deliberately drawn *identically* to a pearl (`DiveRenderer.kt:243-247`) — that identity is a design rule, easier to hold with one shared look object than two entities someone can drift apart in the editor. |
| **Zone bands** | 120 immediate strips | **stay immediate, and stay Kotlin constants** | Full-screen background geometry, not objects. 120 entities would be absurd and their z-sort would dominate the frame; they have no position to gizmo; and they must draw *first*, which the entity path cannot guarantee because `game.onRender()` precedes `scene.render()` (§1.8.3). More importantly the zone colour tables are **safety-critical** — every strip colour must clear `GI_REFLECTANCE_FLOOR` or the ~94 m seam returns (risk 4.2), and `DiveRendererTest`'s 0–200 m quantization sweep is what proves it. Making them live-editable would put a foot-gun in a text field. **Deliberately not exposed.** |
| **Column walls, waterline** | immediate | **stay immediate** | Same: framing geometry derived from `Tuning.COLUMN_HALF_WIDTH`, not authorable objects. Their two colours *may* live on the `WaterColumnLook` prototype if wall tuning is ever wanted; they are not on the reflectance-floor path. Low priority. |
| **Lights** | immediate `drawLight` | **stay immediate** | §1.6 stands unchanged and §4.11 strengthens it. |
| **HUD** | `"hud"` surface, screen pixels | **unchanged** | Risk 4.3 is unaffected. |

A "**Look**" here is a `CommonSceneEntity` that carries appearance parameters and draws nothing (`set(HIDDEN)` in `init{}`, empty `onRender`), findable in the viewport via `@Icon(showInViewport = true)`. Its `x`/`y`/`width`/`height` are meaningless and should be documented as such in its class doc — the engine gives no way to hide inherited props from the Inspector without overriding them.

Total: **1 real entity + 4–5 look prototypes ≈ 6 entities**, versus 75. Every workflow benefit, none of the §1.8.3 costs, and the scene file stays small enough to read.

#### State flows in exactly one direction, and here is how a reviewer checks

```
                 DiveSim  (pure, zero engine imports, the source of truth)
                    |
                    |   render/EntityBridge.push(engine, sim)   -- called from
                    |   EnPustTil.onFixedUpdate, AFTER sim.tick, ONCE per fixed step
                    v
              DiverEntity.x / .y / .width / .height        (appearance only)
                    |
                    |   EntityRendererImpl -> DiverEntity.onRender(engine, surface)
                    v
                mainSurface, in metres, through mainCamera

              PearlLook.texture / .sizeMetres / .colour     (authored, read-only at runtime)
                    |
                    |   DiveRenderer.drawPearls reads them once per frame
                    v
                mainSurface, in metres, through mainCamera

  There is NO arrow pointing back up. Nothing in dive/ knows an entity exists.
```

**Six review signals that the boundary has been violated.** Three are source-scannable and become tests in Task 11; three are reading rules.

| # | Violation | How you see it | Enforced |
|---|---|---|---|
| 1 | `src/main/kotlin/dive/` changed at all | `git diff --stat src/main/kotlin/dive/` is non-empty | DoD + existing convention |
| 2 | `EntityUpdater` added back to the scene | `addSystem(EntityUpdater())` in production source | **test** — and it is the *mechanism*: without it, no entity can receive `onUpdate`/`onFixedUpdate` (§1.8.2), so an entity provably cannot evolve state |
| 3 | Anything calls `engine.scene.addEntity(` | source scan | **test** — also the whole defence against scene pollution (§1.8.6) |
| 4 | A write flows entity → sim | `sim.<field> =` or `pearl.<field> =` anywhere under `render/` | **test** (regex scan), plus review |
| 5 | An entity declares a non-appearance field | a `var` named `velocity`/`depth`/`mass`/`air`/`score`, or any `dive.*` type on an entity | review |
| 6 | Game logic reads an entity property | `diverEntity.width` used in a scoring, collision or air decision | review |

If any of 1–6 is true, the pure-sim boundary that let `Buoyancy` be rewritten from scratch on playtest feedback is gone, and the entity layer must be reverted rather than patched.

#### Scene loading, and the editor gate

`createEmptyAndSetActive("dive.scn")` (`DiveLighting.kt:204`) becomes a load with a fallback:

```kotlin
// Authored appearance lives in dive.scn; the WATER COLUMN does not and never will
// (design spec §10 — every attendee faces the identical seeded column). If the file is
// missing or unreadable we fall back to the empty scene and log at WARN, because the game
// must still boot at the booth with placeholder squares rather than not boot at all.
engine.scene.loadAndSetActive("dive.scn", fromClassPath = !devMode)
```

and `engine.scene.start()` becomes conditional: **skipped when the editor is enabled**, so the scene stays `STOPPED` and `SceneEditor.kt:345` lets the viewport, gizmo and rubber-band actually work. GI does not care (§1.8.1). At the booth `EPT_EDITOR` is unset, `start()` runs, and behaviour is bit-identical to today.

Two things must additionally be gated on the editor being open, both of which fight it:

* **`CameraRig.apply`** — after Stage B it writes `mainCamera` 60×/s, and the editor drives the same object through its own `Camera2DController`. Left ungated, you cannot pan or zoom in the editor at all. Skip `CameraRig.apply` while the editor service is running.
* **`sim.tick`** — game callbacks are never state-gated (§1.8.5), so the diver keeps swimming while you try to position it. Freeze the sim while the scene is `STOPPED` and the editor is up.

And the push itself is gated the other way: **push only when `engine.scene.state == RUNNING`.** When STOPPED the gizmo owns `DiverEntity.x/y`; when RUNNING `DiveSim` does. One writer at a time, always, and the rule is one line.

#### How a tuned value reaches the shipped `.exe`

1. `EPT_EDITOR=1 ./gradlew run`, scene loads STOPPED, select `PearlLook` in the Outliner.
2. Tune. Edits hit the live instance on every keystroke (§1.8.4); F10 to watch it in motion.
3. **Ctrl+S.** In dev the scene's `fileName` is an absolute path into `src/main/resources/dive.scn` (§1.8.6 — *not* via `saveDirectory`, which belongs to `ScoreRepository`).
4. `git diff src/main/resources/dive.scn`, review, commit.
5. `./gradlew buildWin64Release` bundles it; the release loads `fromClassPath = true`.

Step 4 is not optional and is why the scene file must stay ~6 entities: a 100 KB single-line JSON blob cannot be reviewed, and §1.8.6's pollution failure would hide inside one.

---

## 3. What replaces `ViewportTest`

`ViewportTest` (104 lines, 9 tests) is dissolved by this plan: three cases move to a new `FramingTest`, two are absorbed by `CameraRigTest`, two lose their subject with `depthAt`, and two are deleted as measured tautologies. **The original version of this section said all nine "could not have failed". That was wrong, and it was wrong in the direction that would have destroyed working tests.** It has been replaced with measurement.

### 3.0 What the mutation run actually found

The claim was tested rather than argued: `render/Viewport.kt` was mutated one edit at a time against `231c6a6` and the surviving `ViewportTest` failures recorded (source verified byte-identical afterwards; full table in `…/scratchpad/glitch/viewport-mutation-results.md`).

| mutation | outcome | tests killed |
|---|---|---|
| `VISIBLE_DEPTH_METRES` 60 → 45 | **SURVIVED** | — |
| `DIVER_SCREEN_FRACTION` 0.4 → 0.6 | **SURVIVED** | — |
| `screenX` uses `screenWidth` instead of `screenHeight` for pixels-per-metre | **SURVIVED** | — |
| `DIVER_SIZE_METRES` 3 → 0.3 | killed | `the diver is large enough to see` |
| `screenX` drops the `screenWidth * 0.5f` centring term | killed | `x is centred and scales with the display` |
| `targetCameraDepth` sign flip | killed | `the diver sits at the same screen fraction on every display`, `camera keeps the diver above the top edge of visible water` |
| `depthAt` multiplies instead of divides | killed | `depthAt inverts screenY`, `depthAt is resolution independent, like screenY` |

**Six of the nine cases die to at least one mutation.** Only three were never killed by anything tried: `a world point maps to the same screen fraction on every display`, `the diver occupies the same screen fraction on every display`, and `only part of the water column is visible so descending scrolls`. Two of those three are the literal `f(x)·h/h == f(x)·h/h` shape — an algebraic identity that holds for any function of that form — which is what the original critique described, correctly, for a *minority* of the file.

### 3.1 The finding that matters most, and it is not about `ViewportTest`'s pass count

> **`screenX` computing pixels-per-metre from `screenWidth` instead of `screenHeight` survives the entire suite.** Nine tests, all green, and the one substitution that would misplace every object on every non-square display goes undetected.

That is precisely the aspect-independence bug class — the same class as the shipped ultrawide misalignment closed in `6ea1f53`, and the exact convention `CLAUDE.md` exists to protect ("Express sizes as a fraction of screen **height**, never width and never a pixel count — the booth display's aspect ratio is not known in advance").

**A suite that cannot distinguish width from height is not evidence of resolution independence, whatever its pass count.** Every replacement below is measured against that sentence: `CameraRigTest`'s assertions must be evaluated at several *aspect ratios*, not several *resolutions*, or they inherit the same blindness; and §3(d)'s capture protocol at 4:3 / 16:9 / 21:9 is not a nice-to-have appended to the unit tests — it is the only part of the verification that can catch this class at all.

Two secondary findings, recorded as decisions rather than left as accidents:

* **`VISIBLE_DEPTH_METRES` and `DIVER_SCREEN_FRACTION` are free to change to any value without a test noticing** (60 → 45 and 0.4 → 0.6 both survived). They are tuning constants, not invariants, and `FramingTest` deliberately pins only their *relationships* (`VISIBLE_DEPTH_METRES < MAX_DEPTH * 0.6`, `diverDepth − targetCameraDepth == VISIBLE_DEPTH_METRES · DIVER_SCREEN_FRACTION`), not their values. Pinning the values would just be a second copy of the source. **Accepted deliberately.**
* One case the review classed as a tautology — `the diver sits at the same screen fraction on every display` — **does** die, to the `targetCameraDepth` sign flip. It is nonetheless redundant once the transform is gone, because `camera keeps the diver above the top edge of visible water` kills the same mutation using only `Framing` constants. It is dropped for redundancy, not for being unfalsifiable.

### 3.2 Case by case: what happens to each of the nine

| # | Case | Killed by | Disposition |
|---|---|---|---|
| 1 | `the diver sits at the same screen fraction on every display` | `targetCameraDepth` sign flip | **Delete.** Calls `screenY`, which is gone. Its only real content — the diver's frame position — is asserted by case 9 from constants alone, which kills the same mutation. |
| 2 | `a world point maps to the same screen fraction on every display` | nothing | **Delete.** Measured tautology, `f(x)·h/h` on both sides. |
| 3 | `the diver occupies the same screen fraction on every display` | nothing | **Delete.** Measured tautology; `DIVER_SIZE_METRES·(h/60)/h` is `DIVER_SIZE_METRES/60` twice. |
| 4 | `the diver is large enough to see` | `DIVER_SIZE_METRES` 3 → 0.3 | **Keep → `FramingTest`.** Re-express without the deleted `pixelsPerMetre`: `DIVER_SIZE_METRES / VISIBLE_DEPTH_METRES > 0.02f`, which is the same number. Same mutation must still kill it. |
| 5 | `only part of the water column is visible so descending scrolls` | nothing tried | **Keep → `FramingTest`, verbatim.** Not a tautology: it is a genuine one-sided bound, `VISIBLE_DEPTH_METRES < MAX_DEPTH * 0.6`. The mutation tried (60 → 45) moved it the safe way; 60 → 100 fails it. Kept because it guards a gameplay property — if the whole column fits on screen, the camera never moves and the sense of descent is gone. |
| 6 | `x is centred and scales with the display` | dropping the `* 0.5f` centring term | **Absorbed by `CameraRigTest`.** It cannot move to `FramingTest` verbatim — it calls `Viewport.screenX`, which this plan deletes. Its content becomes `CameraRigTest`'s `world (0, camDepth) → (W/2, 0)` pin, which kills the same mutation. |
| 7 | `depthAt inverts screenY` | `depthAt` multiply-vs-divide | **Delete with its subject.** `depthAt` is removed in Task 5; nothing in `CameraRig` replaces it. |
| 8 | `depthAt is resolution independent, like screenY` | same | **Delete with its subject.** See below for what covers the inverse mapping afterwards. |
| 9 | `camera keeps the diver above the top edge of visible water` | `targetCameraDepth` sign flip | **Keep → `FramingTest`, verbatim.** Pure `Framing` constants plus `targetCameraDepth`, all of which survive the rename. |

**What replaces the inverse mapping (cases 7 and 8).** After Task 5 nothing of ours converts screen y back to a depth: the strip walk reads `cam.topLeftWorldPosition.y` / `bottomRightWorldPosition.y`, computed by the **engine** in `initFrame` from the actual framebuffer (`GraphicsImpl.kt:112`). There is no pure function left to unit-test. It is checked instead by `CameraInvariants` rule 2 on a real framebuffer — `worldBottom − worldTop ≈ VISIBLE_DEPTH_METRES` — which is a strictly stronger check than a pure inverse, because it exercises the engine's actual matrix rather than our transcription of it. **Stated plainly: this is a net loss of two unit tests and a net gain of one runtime check, and the runtime check only runs under `EPT_DEV`.**

**What replaces it, honestly:**

**(a) `CameraRigTest` — real assertions, real limitation.** `DefaultCamera.updateViewMatrix()` cannot be called from a unit test: it defaults its interpolation factor to `PulseEngine.INSTANCE.data.interpolation` (`Camera.kt:120`, `Extensions.kt:47-55`) and `INSTANCE` is a `lateinit var` with an `internal set` (`ENG/core/PulseEngine.kt:89`). But JOML *is* on our compile classpath — `org/joml/Matrix4f.class` and `org/joml/Vector4f.class` ship inside `pulse-engine-0.13.0.jar` — so the test can build the matrix with the engine's own JOML from a four-line transcription of `Camera.kt:125-131` and assert:

* world `(0, camDepth)` lands at `(W/2, 0)`;
* world `(0, camDepth + 60)` lands at `(W/2, H)` — exactly 60 m of water, at 1200×900, 1920×1080, 2400×1800, 3440×1440 and 3840×2160;
* the new mapping is numerically identical to the old `Viewport.screenX/screenY` for a grid of `(x, depth, camDepth, W, H)` — the migration is defined to be a no-op, and this is the assertion that says so. (Written against a copy of the old formulas inlined in the test, since `Viewport` is gone.)

  **Two assertions the earlier draft of this plan specified are removed, because they cannot fail** (review finding 6, and every test here must have a plausible mutation that makes it red — `CLAUDE.md`: *"A test that cannot fail is worse than no test"*, and commit `4493eeb` deleted several for exactly this):
  * ~~"the visible horizontal extent, `w / CameraRig.pixelsPerMetre(h)`, equals `w/h · 60`"~~ — reduces to `w / (h/60) = 60w/h`, an algebraic identity that holds for **any** value `pixelsPerMetre` returns, including a wrong one. It would not even catch the width-for-height substitution §3.1 is about. Cut. The property it was trying to state — a 21:9 panel shows more water sideways and the same water vertically — is real, and it is checked by `CameraInvariants` rules 2 and 3 on a real framebuffer and by the 21:9 capture in §3(d).
  * ~~"a given world point lands at the same fraction of `h` on all of them"~~ — the same `f(x)·h/h` shape §3.0 measured as unkillable, two paragraphs after condemning it. Cut. **Note it is the `(W/2, 0)` and `(W/2, H)` pins that carry aspect independence here, and only because the display list contains genuinely different aspects (4:3, 16:9, 16:10, 21:9, 16:9-at-4K).** Substituting `w` for `h` inside `pixelsPerMetre` moves the `(W/2, H)` pin on every non-square entry, so `CameraRigTest` does kill the mutation `ViewportTest` could not. If that display list is ever reduced to one aspect, this test rejoins the class of suites §3.1 describes.

  **The limitation, stated plainly:** this tests our camera *parameters* against a transcription of the engine's formula. It would not catch the engine changing that formula in 0.14.0. It is better than `ViewportTest` — it tests agreement with something outside our own file, and it kills a mutation `ViewportTest` demonstrably could not — and it is not a substitute for looking.

**(b) `CameraInvariantsTest` + a live dev-mode check — the part that runs on the real framebuffer.** A pure `object CameraInvariants` takes the numbers the *engine* reports back and returns violations:

```kotlin
fun violations(
    windowWidth: Int, windowHeight: Int,
    surfaceWidth: Int, surfaceHeight: Int,
    worldTop: Float, worldBottom: Float, worldLeft: Float, worldRight: Float
): List<String>
```

with three rules:

1. `surfaceWidth/Height == windowWidth/Height` — the two size sources must never diverge.
2. `worldBottom − worldTop ≈ Framing.VISIBLE_DEPTH_METRES` (±0.5 m) — **exactly 60 m of water is visible, whatever the display is.**
3. `(worldRight − worldLeft) / (worldBottom − worldTop) ≈ surfaceWidth / surfaceHeight` (±1%) — the visible world rect has the screen's aspect, i.e. no distortion.

`CameraInvariantsTest` unit-tests the rules themselves (a violation is detected, a clean set is not, tolerances behave). `EnPustTil` then feeds it `engine.gfx.mainCamera.topLeftWorldPosition` / `bottomRightWorldPosition` — values computed by the engine in `initFrame` from the actual framebuffer (`GraphicsImpl.kt:112`) — once per second under `EPT_DEV`, and `Logger.warn`s any violation. Rule 2 is the single check that would have caught the shipped bug the first time the window changed size, and it is a check no unit test can perform because it needs a real camera and a real surface.

**(c) The `drawQuad` source guard.** A test that scans `src/main/kotlin/**.kt` for `drawQuad(` / `drawLine(` and fails. Cheap, real, and directly aimed at the risk that a rewrite touching every draw call in the codebase reintroduces a call that renders nothing on macOS with no error (see `render/Draw.kt`).

**(d) Capture-based verification — the only thing that reliably catches an aspect bug.** §3.1 is the evidence: nine green unit tests did not notice `screenX` deriving pixels-per-metre from the width. `CameraRigTest`'s multi-aspect pins narrow that gap but do not close it, because they still test our transcription rather than the engine. The migration is only signed off after a human has looked at:

| Window | `application-dev.cfg` | Why |
|---|---|---|
| 4:3 | `1200 × 900` | narrower than the column: walls fall off both edges |
| 16:9 | `1600 × 900` | the booth's likely shape |
| 21:9 | `2100 × 900` | ultrawide: the reported failure |

For each, `EPT_SCREENSHOT=/tmp/ar-<shape>.png ./gradlew run` writes `ar-<shape>-0.png` (world) and `ar-<shape>-hud-0.png` (HUD). Then, mechanically rather than by eye:

```bash
# tools/check-hud-alignment.py — writes nothing to src/, run manually.
# Reports the centroid of the brightest cluster in each capture and their pixel delta.
python3 tools/check-hud-alignment.py /tmp/ar-16x9-0.png /tmp/ar-16x9-hud-0.png
```

The diver square is the brightest thing in the world capture and the air ring is the brightest thing in the HUD capture, and they are concentric by design. **Acceptance: the two centroids agree within a few pixels at all three shapes.** That number, at three aspect ratios, on a real framebuffer, is the actual replacement for `ViewportTest` — everything above it is scaffolding that makes it likely to pass first time.

**(e) Stage D: what can honestly be tested, and what cannot.**

`ViewportTest` was 9 tests that could not fail. The temptation in Stage D is worse, because "does the Inspector work?" is exactly the kind of question you can write a green test about without testing anything. Splitting it honestly:

**Genuinely testable, and each one fails for a real reason:**

1. **The push function.** Extract it as pure: `EntityBridge.pushDiver(entity, sim)` (or `(x, depth, heldMass) -> (x, y, w, h)`), taking and returning primitives/an entity with plain fields, so it needs no GL context. Assert the entity's `x`/`y` equal `sim.x`/`sim.depth`, that `width`/`height` follow `DIVER_SIZE_METRES + heldMass * 0.03f` (the existing rule at `DiveRenderer.kt:262`), and — the mutation-test — that changing `sim.x` changes `entity.x`. This is the one behavioural test in the stage and it is real.
2. **Boundary guards, as source scans.** The project already has this pattern and it is the right one (`MainCameraOwnershipTest`, added in `6ea1f53`, both cases mutation-tested). Four scans over `src/main/kotlin/**.kt`:
   * no `engine.scene.addEntity(` anywhere (§2.6 rules 3, and the pollution defence);
   * no `EntityUpdater` in any `addSystem` call (§2.6 rule 2);
   * no assignment into a `dive` object from `render/` — regex `\b(sim|pearl|pocket|fish)\.\w+\s*=` (§2.6 rule 4);
   * no `no.njoh.pulseengine` import under `src/main/kotlin/dive/` (constraint 1, currently true by convention only).
   Each must be shown to go **red** when you temporarily insert the offending line, exactly as Task 8 Step 2 requires for the `drawQuad` guard. A guard that has never been seen red is a guard nobody has tested.
3. **`dive.scn` purity — the strongest new test.** Parse the committed `src/main/resources/dive.scn` as text and assert: (a) it names **no** `Pearl`-, `AirPocket`- or `Anglerfish`-shaped entity type; (b) the total number of `["<fqcn>",{` entity entries is `<= 8`; (c) it contains only class names from an explicit allow-list. This is the check that catches an accidental Ctrl+S-while-running before it reaches the booth, and it catches it in CI rather than in a 100 KB one-line diff. It fails the moment §1.8.6's hazard actually happens.
4. **Asset references resolve.** For every `@TexRef`-shaped string value in `dive.scn`, assert a matching asset file exists under `src/main/resources/`. This is exactly the `torch_flame` vs `torche_flame` bug the reference shipped with (§1.8.7) and it is free to prevent.
5. **Class-name stability.** `enableDefaultTyping()` writes FQCNs into the scene (§1.8.6). Assert that every FQCN in `dive.scn` resolves via `Class.forName`. A package move then fails the build instead of silently emptying the scene.

**Not testable — you have to look, and the plan says so rather than faking it:**

* That the Outliner lists anything, that the gizmo grabs, that the rubber-band selects.
* That an Inspector edit visibly changes the running frame.
* That the asset picker lists our textures once there are any.
* That `Ctrl+S` writes where we think it writes — verify by `git diff`, by hand, once, and record the result in the task report.
* Anything about how it *looks*: AO, the beam, the seam, the Abyss.

**Explicitly do not write:** a test that constructs a `DiverEntity` and asserts `onRender` "was called" (there is no seam without a GL context, so any such test asserts only that a mock was invoked); a test that asserts `dive.scn` "loads" by calling into `SceneManagerImpl` (needs a live `PulseEngine.INSTANCE`, which is `lateinit … internal set` — `ENG/core/PulseEngine.kt:89`); or a test that asserts the entity count equals the number you just wrote in the same file.

---

## 4. Risks

### 4.1 Lighting alignment — the hard-won one-frame fix

**Status: structurally strengthened on the world side, newly exposed on the HUD side.**

Once light positions and geometry are both world coordinates going through the same `mainCamera.viewProjectionMatrix` (`GiSceneRenderer.kt:85`, `GraphicsImpl.kt:111-113`), a light and the object it lights **cannot** drift apart — the old bug becomes unreproducible. But the drift moves to the HUD boundary, where the air ring and held count are transformed by hand (§2.4). Porting them naively from `DiveCamera.depth` puts them one frame ahead of the diver, and it will look exactly like the original playtest note.

*Verify:* the same capture protocol the lighting rework used (`.superpowers/sdd/…/lighting-rework-report.md`) — capture mid-descent while the camera is easing hard (hold DOWN with a large haul, several depths). In one frame, the pearl halo centre must sit on the pearl square centre, and the air-ring centre must sit on the diver square centre. Anything that only misaligns *while moving* is drift.

*Also:* keep `DiveLighting.render` called from `onRender`. It no longer matters for alignment, but `GiSceneRenderer`'s batch must be filled before `gfx.drawFrame`, and `onRender` is where the rest of the drawing lives.

### 4.2 The GI reflectance floor

**Status: the colour code is untouched; the loop that *feeds* it is rewritten.**

`GI_REFLECTANCE_FLOOR`, `floorBlueForReflectance`, `srgbToLinear` and `DiveRendererTest`'s 0–200 m quantization sweep all survive verbatim, so the *colours* stay proven. What is not proven after the rewrite is that the strip loop still samples them at the same depths: it changes from walking pixels `0..h` in `BAND_STRIP_METRES · ppm` steps to walking world `topLeft.y..bottomRight.y` in `BAND_STRIP_METRES` steps. A subtle change to the strip pitch or to which depth each strip is coloured by would move where the floor bites and could reinstate the ~94 m seam that this constant exists to kill.

*Guard:* extract the walk as a pure function (`DiveRenderer.stripCount(worldTop, worldBottom)` / `stripCentreDepth(worldTop, index)`) and assert in `DiveRendererTest` that centres are `BAND_STRIP_METRES` apart, that the walk covers the whole visible rect with no gap, and that the count is resolution-independent.

*Verify:* capture at 90 m and 100 m. No horizontal hairline anywhere in the column.

*Note:* `minReflectance` is a settable `@Prop` (§1.7) and could remove the problem entirely. Out of scope here — changing lighting and coordinates in the same pass makes any regression unattributable — but the misleading comment gets corrected in Task 9.

### 4.3 The HUD's separate surface

**Status: unchanged and must stay unchanged, but the migration creates pressure to break it.**

Two temptations, both fatal:

* *"Now that the world camera tracks the diver, put the air ring on `main` and it tracks for free."* It would go under GI's multiply and read as RGB(11,8,1) in the Abyss (measured, `EnPustTil.kt:370-379`).
* *"Tidy up the surface creation by passing the camera explicitly."* Today `mainCamera` is (almost) identity so passing it would be nearly invisible. After the migration it would smear the whole HUD by a 30× world transform.

There is a documentation hazard too: the `camera: left null` comment (`EnPustTil.kt:398-401`) justifies the decision by "it must not ride `engine.gfx.mainCamera`, which the GI `Camera` entity above drives". Task 1 deletes that entity, so the *stated reason* evaporates while the decision stays correct for a stronger reason. Rewrite the comment in the same commit or someone will delete the surface.

*Verify:* capture in the Abyss (≥120 m) and read the BANKED glyph pixel values. They must stay in the (190, 215, 255) region, not near-black. Confirm `zOrder = HUD_Z_ORDER` still composites the HUD last.

### 4.4 The `Surface.fillRect` workaround

**Status: unaffected by coordinates, at risk from the rewrite touching every call site.**

`drawQuad`/`drawLine` render nothing on macOS/Apple Silicon, silently, with no GL error (`render/Draw.kt:7-24`). The migration edits every draw call in `DiveRenderer` and `Hud`, and `drawQuad` becomes *more* tempting because a rotation argument looks useful for sprites. It is not needed: `drawTexture(tex, x, y, w, h, angle, xOrigin, yOrigin)` does rotation and is the supported path (the reference uses exactly this — `REF/src/main/kotlin/entities/level/Spark.kt:84`).

*Guard:* the source-scanning test in §3(c). *Verify:* anything that disappears entirely on macOS after the flip is this, not the coordinates.

### 4.5 Camera easing moves from the render clock to the fixed tick

`CLAUDE.md` currently documents render-clock camera easing as deliberate. §1.5(2) shows it is actively wrong once the camera is the engine's: the interpolator pairs `position` with a fixed-step `positionLast` and will judder. The change is safe — `DiveCamera` is already frame-rate independent — but `CLAUDE.md`'s "Frame ordering is load-bearing" section must be updated in the same commit, with the reason.

*Verify:* run uncapped (`targetFps`) and watch the waterline while descending. Judder here is a sub-frame wobble at high frame rates, not a jump.

### 4.6 First-frame and resize behaviour

`topLeftWorldPosition`/`bottomRightWorldPosition` are computed from the matrix built in `beginFrame`, before any of our code runs that frame. `CameraRig.apply` is therefore also called once in `onCreate` (after `camera.snapTo`) so that frame 1's `beginFrame` picks up a sane camera — `onCreate` runs before the first `beginFrame` (`PulseEngineImpl.kt:69-73, 216-224`).

Even so, the strip loop must not trust the world rect blindly: clamp its iteration count, so that a degenerate rect (a camera that has not been applied, a zero-size surface during a resize) draws too few strips rather than hanging the frame.

*Verify:* resize the dev window continuously while the game runs. No hang, no crash, no permanent misalignment.

### 4.7 GI ambient occlusion radius

§1.7. `aoRadius` is multiplied by `camScale`, which goes from 1 to ≈30. This is the one setting that will visibly change and that nobody chose in the first place (it is the engine default).

*Verify and compensate:* Task 6.

### 4.8 Things that are provably unaffected

Listed so nobody spends time on them: the alpha-squared HUD convention and `authoredAlphaFor`; `ScreenText`/`DefaultFont` and the U+011F glyph limit; `ColorGradingEffect`/`BloomEffect`/`ScreenshotEffect` (all screen-space post passes, `order` 15/…/10 000); `AttractLayout` (screen-height fractions on the HUD surface); `RunLifecycle`, `InitialsEntry`, `ScoreRepository`, the gamepad diagnostics; the `scene.vert` minimum-light-size clamp (scale-invariant); the strip count (120 either way, so no performance change); `AimAngle` (the y-flip survives a positive uniform scale). No text is drawn to `mainSurface` today — after the migration any `drawText` there would be sized in metres, which is worth a comment but changes nothing now.

---

## 4b. Risks specific to Stage D (the entity layer)

### 4.9 Scene-file pollution — the constraint-3 failure mode

**Status: structurally prevented by §2.6, and tested.**

Nothing in the engine stops a runtime-spawned entity being serialized: `addEntity` pushes into the same `Scene.entities` list Jackson walks (`Scene.kt:16-20, 42-61`), and Ctrl+S works while the scene is RUNNING (`SceneEditor.kt:163-164`). If seeded pearls were ever written into `dive.scn`, the next load would materialise day one's column as authored entities *alongside* day two's freshly seeded one — silently, inside a single-line JSON diff — and "every attendee faces an identical column" would be quietly false.

Our defence is that **there is nothing to spawn**: §2.6 puts no per-object entity in the scene, so no production code calls `addEntity`. That is enforced by a source-scanning test, and backed by a purity test on the committed `dive.scn` (§3(e) items 2 and 3).

*Verify:* deliberately Ctrl+S while the scene is RUNNING once, during Task 11, and confirm `git diff src/main/resources/dive.scn` is empty. Record the result. If it is not empty, the guard is wrong and the stage stops there.

*Also:* keep the scene file small enough that step 4 of §2.6's tuning loop — reading the diff — is a real review and not a rubber stamp. Eight entities is the ceiling the test enforces.

### 4.10 GI's `onCreate` early return, and `EntityRendererImpl` becoming load-bearing

`GlobalIlluminationSystem.onCreate` ends with `getSystemOfType<EntityRenderer>() ?: return` at `GlobalIlluminationSystem.kt:184`, skipping the five `addRenderPass` calls at `:185-189`. That is why `EntityRendererImpl` was kept when `EntityUpdater` was removed in `6ea1f53`, and the comment at `DiveLighting.kt:214-220` records it.

**After Stage D that system is load-bearing twice**: remove it and (a) GI's five passes never register *and* (b) `DiverEntity` is never drawn — the diver simply vanishes, with no error. The failure mode is silent in both halves. `DiveLighting.kt:214-220`'s comment must be updated from "stays for GI's sake even though it draws nothing" to "draws the diver; also GI's precondition".

Ordering note, verified: systems are initialised in list order inside `Scene.update`'s `forEachFiltered` loop (`Scene.kt:84-97`), and `DiveLighting.setup` adds `EntityRendererImpl` (`:221`) before `GlobalIlluminationSystem` (`:245`), so the lookup at `:184` succeeds. **Do not reorder those two `addSystem` calls.** Worth a comment, because nothing about the code says so.

### 4.11 `GiLightSource` on an entity would change lighting, not just workflow

§1.6 established that `GiLightSource.onRenderLightSource` and immediate-mode `GiSceneRenderer.drawLight` are the same data path, with one asymmetry: **entity lights are collected by two passes**, `GI_LOCAL_SCENE` *and* `GI_GLOBAL_SCENE` (`GlobalIlluminationSystem.kt:70, 72`), while immediate mode reaches only the surface whose renderer you fetched — and we fetch `GI_LOCAL_SCENE` only.

Stage D makes it tempting to give `DiverEntity` a `GiLightSource` implementation "for free", since it is already an entity and the annotations would surface the beam parameters in the Inspector. **That is not free.** It would put the diver's beam into the global SDF and enable far-field bleed through `traceWorldRays` (`GLSL/lighting/global/radiance_cascades.frag:82-91`) that has never existed in this game. It might even look better — but it is a lighting change, and lighting changes made as a side effect of a workflow change are unattributable.

*Decision:* `DiverEntity` implements `Renderable` only. The beam stays an immediate-mode `drawLight` in `DiveLighting`. Beam parameters can still be `@Prop`s on a `DiverLightLook` prototype that `DiveLighting` reads — same Inspector, same persistence, no pass change. If the far-field bleed is wanted later, do it as its own change with its own captures.

### 4.12 The editor and `CameraRig` both write `mainCamera`

After Stage B, `CameraRig.apply` writes `engine.gfx.mainCamera` from `onFixedUpdate` at 60 Hz. The editor drives the *same object* through `Camera2DController` (`SceneEditor.kt:347`). Ungated, the editor's camera is overwritten 60 times a second and panning and zooming are impossible — the viewport will look frozen and misaligned, and it will be read as "the editor is broken" rather than "two writers".

The existing note at `EnPustTil.kt:373-377` already half-predicts this ("expect the world to render wrong while the editor is open"). Stage D must actually fix it, not restate it: **skip `CameraRig.apply` while the editor service `isRunning`.**

Note this is only a `MainCameraOwnershipTest`-style single-writer violation in appearance — the rule is still "exactly one writer at a time", it is just that in editor mode the writer is the editor. Say so where the gate lives, or the next person will delete the gate to satisfy the invariant.

*Verify:* with `EPT_EDITOR=1`, pan and zoom the editor viewport and confirm the world stays where you put it. Without the gate, it snaps back every fixed tick.

### 4.13 The sim keeps running while you edit

`PulseEngineGame.onUpdate`/`onFixedUpdate`/`onRender` are not gated by scene state (`PulseEngineImpl.kt:256, 280, 298`); "play" gates only `EntityUpdater` and entity self-checks. So with the scene STOPPED for editing, `sim.tick` still runs, the diver still swims, air still burns and the run still ends and restarts underneath you.

*Fix:* freeze `sim.tick` while the editor is up **and** the scene is `STOPPED`. RUNNING (F10) still ticks, which is the point of F10.

*Consequence to write down:* the Inspector never re-reads values from the entity (`SceneEditor.kt:1030-1033` pushes back only gizmo-driven `x/y/rotation/width/height`), so during F10 play the diver's `x` field shows a frozen number while the diver visibly moves. That is engine behaviour, not our bug. Put it in `DiverEntity`'s class doc.

### 4.14 Per-frame cost and the intransitive z comparator

At §2.6's scale — one drawn entity plus ~5 hidden prototypes — the cost is a rounding error and none of §1.8.3's three problems bite. **They bite the moment someone converts pearls**, which is exactly the change this stage will look like it invites:

* `EntityRenderer.kt:132`'s TimSort allocates every frame (the engine's own `// TODO: This creates alot of garbage internally`), against `CLAUDE.md`'s "No per-frame allocation in the render path".
* There is no culling in `EntityRendererImpl` at all, so 70 pearls means ~2.7× the geometry we submit today and Task 7 becomes unreachable for them.
* `BackToFrontEntityComparator` (`:155-158`) is `((b.z - a.z) * 10_000f).toInt()`, which is **intransitive for `z` deltas below 1e-4** and will throw `IllegalArgumentException: Comparison method violates its general contract!` from TimSort at n ≥ 32. Seventy pearls with jittered `z` is a crash in front of the queue.

*Guard:* leave this paragraph, verbatim, as a comment on `PearlLook`'s class doc — the object whose existence is the argument against converting pearls. Also keep all Stage D entities on the same `z` so the comparator sees only equal elements.

*Verify:* `EPT_DEV=1`, F3 `MetricViewer`, compare frame time before and after Task 12. A measurable regression from six entities means something else is wrong.

---

## 5. Size, and how to sequence it

**Size.** Roughly **400–500 changed lines across 8 source files and 6 test files**, plus two new source files (~140 lines with docs) and two new test files (~180 lines). `Viewport.kt` (58) is replaced by `Framing.kt` (~35); `ViewportTest.kt` (104) is deleted. `DiveRenderer`'s seven draw methods and `DiveLighting`'s three are rewritten; their colour, intensity and cone code — which is most of both files' 343 and 424 lines — is untouched. Call it **1.5–2 focused days for one person**, of which about a third is the capture verification at three aspect ratios.

**Do it in three stages, and understand that the middle one is atomic.**

* **Stage A — Tasks 1–4. Independently valuable, ships alone.** Task 1 deletes the `Camera` scene entity and closes the reported bug outright, today, with no coordinate change at all. If a booth build is imminent, ship Stage A on its own and stop. Tasks 2–4 add the diagnostics and the pure, tested pieces the flip needs, without changing a pixel.
* **Stage B — Task 5. One commit. It cannot be split.** The moment `mainCamera` stops being identity, *everything* on `main` and `gi_local_scene` must already be in metres and the HUD anchor must already come from `worldPosToScreenPos`, or the frame is garbage. Attempting to land the world and the HUD separately is the single most likely way this migration goes wrong. The de-risking is that `CameraRig` is already proven against the old formulas in Task 3, so the flip's only unknown is whether every call site was converted — which is a compile-and-look problem, not a maths problem.
* **Stage C — Tasks 6–9. Separable cleanups**, each independently revertable: the GI `camScale` compensation, culling via `isInView`, the origin-based draw cleanup that prepares for sprites, and the documentation.

**Recommendation: do the migration, but ship Task 1 first and separately.** The bug is not the reason to migrate — Task 1 fixes the bug in five deleted lines. The reason to migrate is that the placeholder squares are about to become sprites, and every sprite authored in screen pixels is a sprite that has to be re-expressed in metres later, plus a `Viewport` call at every draw site that a designer tweaking a size will have to reason about. Doing this before the art lands is much cheaper than doing it after, and it is the only version of "make real art work easier" that survives contact with a 4K booth panel.

* **Stage D — Tasks 10–14. The entity layer. Ships separately, after Stage B, and can be abandoned without cost.**

**Stage D size.** Roughly **450–550 changed lines**, of which most is new: `render/entities/DiverEntity.kt` (~90 lines with docs), four small look prototypes (~60 each), `render/EntityBridge.kt` (~60), a hand-authored `src/main/resources/dive.scn` (small, reviewable), edits to `DiveLighting.setup` (scene load path, conditional `start()`, two corrected comments), `EnPustTil.kt` (two editor gates), `DiveRenderer` (read look values instead of file-private constants), and ~180 lines of tests across three files. **1.5–2 days for one person**, of which a real fraction is the first hour of actually driving the editor and finding out which of §1.8's claims survive contact.

**Sequencing — Stage D depends on Stage B, and the dependency is hard.** `CommonSceneEntity.x/y/width/height` are world units fed straight to `drawTexture` on `mainSurface` through `mainCamera`. **With today's identity camera, an entity at `(12, 94)` renders 12 pixels right and 94 pixels down from the screen's top-left corner** — the diver would sit in the corner at one-thirtieth of its size, and the gizmo would move it in pixels while the Inspector claimed metres. There is no partial version of this: entities are only meaningful once `mainCamera` is a metre-scaled camera.

| Task | Depends on | Why |
|---|---|---|
| 10 (author `dive.scn`, let the editor reach STOPPED) | **nothing** — could ship before Stage A | It only changes how the scene is created and started. Worth doing early: it is what makes the editor usable at all, and it is the cheapest way to find out whether §1.8's reading of the editor is right. |
| 11 (boundary guards) | Task 10 | Deliberately **before** any entity exists, so every guard is red-tested against a real violation rather than written to match code that already passes. |
| 12 (`DiverEntity`) | **Task 5 (Stage B)** + Tasks 10, 11 | World coordinates, per above. |
| 13 (look prototypes) | Task 12 | Reuses its scene-loading, gating and doc patterns. |
| 14 (documentation) | Task 13 | Last. |

**Recommendation on Stage D: do it, at the size in §2.6, and ship it as its own set of commits after Stage B is verified.** Two reasons and one caveat.

The reasons: the editor workflow **is** real where it matters — §1.8.7's evidence from the reference is unambiguous that on this engine the `.scn` is where tuning lives, and §1.8.5 confirms that Inspector edits on an authored entity hit the live instance on every keystroke. And the alternative is the loop this project has actually been stuck in, which is edit → gradle → relaunch → screenshot for every number.

The caveat, stated plainly because it is the thing most likely to disappoint: **the promised workflow is narrower than "the editor gives you live tuning".** It is *"an authored, selectable entity's numeric and asset-reference properties can be edited from the Outliner while the game runs in F10 mode, and persist if you remember Ctrl+S."* Viewport gizmos only work with the scene STOPPED (`SceneEditor.kt:345`); runtime-spawned entities never appear in the Outliner at all (`SceneEditor.kt:336-343`); the Inspector goes stale the moment game code writes a property (`:1030-1033`); and stop discards everything unsaved (`:540-541`). Anyone expecting Unity will be unhappy. Anyone who wanted to stop rebuilding to change a pearl's size will not be.

**What I would refuse.** Converting pearls, vents or the anglerfish into entities. It is the change the phrase "the player should be its own entity" naturally generalises to, and on this engine it is strictly negative: invisible in the Outliner (§1.8.5), loses culling, adds a per-frame allocation against an explicit project rule, and carries a real n≥32 crash (§1.8.3, §4.14). If the only version of Stage D on offer were one-entity-per-object, the right answer would be to skip the stage entirely and keep the constants in Kotlin.

**If time runs short:** Stage A alone leaves the game correct, with a live invariant check that will shout if it ever stops being correct. That is a defensible place to stop. Stage D is the *first* thing to drop if the booth date gets close — placeholder squares tuned by rebuilding still ship a working cabinet; a half-migrated coordinate system does not.

---

## 6. Task breakdown

### Task 1: Remove the `Camera` scene entity — ✅ DONE, commit `6ea1f53`

Closes the shipped bug. No coordinate change.

**What actually shipped, and how it differs from the plan below:**

* The `Camera` entity and its 19-line comment are gone, replaced by a comment recording the true mechanism with the citations plus the measured 1200x900 → 3440x1440 reproduction.
* **`EntityUpdater` was removed as well** — the plan said to keep it. It is provably dead once the entity is gone: it does nothing but dispatch `onStart`/`onUpdate`/`onFixedUpdate` to `Initiable`/`Updatable` scene entities, and the scene now has none. Its only other effect, syncing `engine.config.fixedTickRate` to its own `tickRate` on start, was already a no-op — `EnPustTil.onCreate` sets `fixedTickRate = 60` *before* calling `DiveLighting.setup`, so it copied 60 out and wrote the same 60 back.
* **`EntityRendererImpl` stays**, per the plan. It draws nothing with an empty scene, but GI's own `onCreate` does `getSystemOfType<EntityRenderer>() ?: return` at `GlobalIlluminationSystem.kt:184` before registering its five render passes, so removing it would change GI's initialisation path for no gain.
* Task 4's `stripCount`/`stripCentreDepth` extraction is untouched and still open.
* `EnPustTil.kt`'s `camera: left null` comment was rewritten in the same commit (risk 4.3): its stated reason ("the GI Camera entity drives `mainCamera`") evaporated with the entity, while the decision stays correct for a stronger reason.
* Added `src/test/kotlin/render/MainCameraOwnershipTest.kt` (2 tests, both mutation-tested): a source-scanning guard asserting no production source constructs a scene `Camera` entity or touches `engine.gfx.mainCamera`. It is a precondition guard, not a behaviour test — stated plainly in its class doc — because the invariant is about a mutable engine-owned object and only becomes visible once two surfaces have been rasterised. Task 5 legitimately deletes it.

**Evidence.** In a configuration where the bug does not manifest (plain fullscreen, framebuffer never changes), the world capture before and after the change is **bit-for-bit identical** — 0 differing pixels over 3440x1440 — so nothing about GI, ambient, zone bands, pearl lights or the diver beam moved. The diver-square-vs-air-ring concentric delta is ≤ 1.7 px at 3440x1440 fullscreen, 3456x1800, 1600x900, 1200x900 and 700x1000, and 0.0 px after an ALT+ENTER toggle from either 1200x900 or 700x1000 — against 886 px for the same toggle before the fix.

**Files:**
- Modify: `src/main/kotlin/render/DiveLighting.kt:13, 145-167`

**Interfaces:**
- Consumes: nothing new
- Produces: `engine.gfx.mainCamera` left at its constructed identity for the whole run

- [x] **Step 1: Record the evidence in the code before deleting anything**

Read `ENG/modules/lighting/global/GlobalIlluminationSystem.kt` end to end and confirm for yourself that no scene `Camera` entity is looked up. `grep -rn "Camera" ENG/modules/lighting/global/` must return only `surface.camera` and `engine.gfx.mainCamera`.

- [x] **Step 2: Delete the entity**

In `DiveLighting.setup`, remove the `import no.njoh.pulseengine.modules.scene.entities.Camera` and the whole block from `val camera = Camera()` through `engine.scene.addEntity(camera)`. Keep `createEmptyAndSetActive`, `EntityUpdater`, `EntityRendererImpl`, the `GlobalIlluminationSystem` setup and `engine.scene.start()` — GI needs an `EntityRenderer` in the scene to register its render passes (`GlobalIlluminationSystem.kt:184-189`) and needs the scene RUNNING for `onUpdate` to install the multiply effect (`:210-215`).

Replace the deleted comment with what is actually true:

```kotlin
// NO SCENE `Camera` ENTITY HERE, DELIBERATELY.
//
// A previous version added one because GI was believed to require an active scene camera.
// It does not: GlobalIlluminationSystem reads engine.gfx.mainCamera directly and passes it
// to its own surfaces (GlobalIlluminationSystem.kt:78, 130, 142, 153, 163, 175). It never
// looks up a scene Camera entity, and the only early-out in the whole system is a missing
// EntityRenderer, which merely skips render-pass registration.
//
// The entity was, however, the ONLY thing in this process that ever wrote mainCamera, and
// it wrote it every fixed tick as
//     scale = min(mainSurface.config.width / viewPortWidth,
//                 mainSurface.config.height / viewPortHeight)
// (Camera.kt:93) against a viewPortWidth/Height frozen at the window size seen during
// onCreate. mainSurface.config tracks the CURRENT framebuffer (SurfaceImpl.kt:46-47), so the
// instant the framebuffer changed size -- a fullscreen toggle, a monitor change, or simply
// the macOS framebuffer-size callback firing after onCreate -- the world surface was scaled
// about the screen's top-left corner by that ratio and the HUD surface (its own camera) was
// not. That is the world-offset-from-HUD bug.
//
// Left alone, mainCamera is DefaultCamera.createOrthographic(...) at position 0, origin 0,
// scale 1 -- the identity -- and its projection is re-issued as ortho(0, w, h, 0) for every
// surface camera on every window change (GraphicsImpl.kt:95). It is therefore a correct
// screen-pixel camera at every framebuffer size, permanently, with no maintenance.
```

- [x] **Step 3: Verify the suite still passes**

Run: `./gradlew test`
Expected: PASS, 233 tests. Nothing here is covered by a test — that is the point of Step 4.

- [x] **Step 4: Verify on a real framebuffer, at a shape you did not start in**

```bash
caffeinate -d -u -t 900 &
EPT_SCREENSHOT=/tmp/task1.png ./gradlew run
# while it runs: LEFT_ALT+ENTER to toggle fullscreen, then let it reach frame 180
pkill -9 -f EnPustTilKt
```

Open `/tmp/task1-0.png` and `/tmp/task1-hud-0.png`. The diver square and the centre of the air-bubble ring must be at the same place. Before this change, after a fullscreen toggle, they are not.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/render/DiveLighting.kt
git commit -m "fix: stop the GI scene camera scaling the world away from the HUD"
```

---

### Task 2: `CameraInvariants` and a dev-mode check that runs on the real framebuffer

The thing that would have caught Task 1's bug the first time the window resized.

> **Sequencing warning — this task turns `MainCameraOwnershipTest` red, and Step 5a is not optional.**
> Its second case, `no production source touches the shared main camera`, fails on **any** production
> source containing the string `mainCamera` after comment-stripping. Step 5 puts
> `engine.gfx.mainCamera.topLeftWorldPosition` into `EnPustTil.kt`, so the guard goes red **in this
> task**, and Task 3 makes it redder still by creating `CameraRig.kt`. Earlier drafts of this plan
> listed neither, and then had Task 4 Step 4 claim `./gradlew test` → PASS, which was unachievable.
> Fixed here.

**Files:**
- Create: `src/main/kotlin/render/CameraInvariants.kt`
- Test: `src/test/kotlin/render/CameraInvariantsTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` (dev-only call in `onRender`)
- **Modify: `src/test/kotlin/render/MainCameraOwnershipTest.kt`** (allow-list the two legitimate touches — Step 5a)

**Interfaces:**
- Consumes: `Framing.VISIBLE_DEPTH_METRES` (`Viewport.VISIBLE_DEPTH_METRES` until Task 5)
- Produces: `CameraInvariants.violations(...): List<String>`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/render/CameraInvariantsTest.kt` with tests that:
- a consistent set (`window == surface`, 60 m of visible depth, world-rect aspect == surface aspect) yields an empty list;
- a surface size that differs from the window size is reported;
- a visible depth of 87 m instead of 60 is reported, and the message names both numbers;
- a world rect whose aspect is 16:9 while the surface is 4:3 is reported;
- the tolerances behave: 60.4 m passes, 60.6 m fails; 1% aspect error passes, 3% fails;
- a degenerate rect (zero or negative height) is reported rather than dividing by zero.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'render.CameraInvariantsTest'`
Expected: FAIL — `Unresolved reference: CameraInvariants`

- [ ] **Step 3: Implement it**

Create `src/main/kotlin/render/CameraInvariants.kt`. Pure Kotlin, no engine imports, so it is testable without a GL context — same pattern as `RunLifecycle` and `DepthBlend`. Document at the top *why* each rule exists and what shipped bug it would have caught.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'render.CameraInvariantsTest'`
Expected: PASS

- [ ] **Step 5: Wire it into the dev overlay**

In `EnPustTil.onRender`, behind the existing `devMode` boolean, at most once per second, feed it `engine.window.width/height`, `engine.gfx.mainSurface.config.width/height` and `engine.gfx.mainCamera.topLeftWorldPosition`/`bottomRightWorldPosition`, and `Logger.warn` each violation. Log at WARN, not DEBUG, for the same reason `logGamepadDiagnostics` does: it must survive the booth's default log level if anyone ever runs a dev build there.

Rules 2 and 3 will fail until Task 5 — with the camera at identity the "world rect" is the pixel rect. Gate the depth/aspect rules behind a flag that Task 5 turns on, or accept a known-failing warning until then; state which in the commit message.

- [ ] **Step 5a: Amend `MainCameraOwnershipTest` in the same commit, and re-red-test it**

Step 5 just made the suite red. Run `./gradlew test --tests 'render.MainCameraOwnershipTest'` **first** and watch it fail on `no production source touches the shared main camera` — see it red before changing it, so you know the amendment is doing work and not papering over something else.

Then narrow the second case from "no production source mentions `mainCamera`" to an **allow-list**:

* `EnPustTil.kt` may **read** `mainCamera.topLeftWorldPosition` / `bottomRightWorldPosition` — reads cannot cause the bug the guard exists for, which is a *write* from a second place.
* `render/CameraRig.kt` may write it (created next, in Task 3). Add the entry now, so Task 3 does not have to touch a test file at all.
* Nothing else, anywhere.

Implement it as "the set of production files containing `mainCamera` equals exactly `{EnPustTil.kt, render/CameraRig.kt}`" rather than as a substring skip, so **adding a third file fails and so does silently losing `CameraRig.kt`.** Update the class doc's "WHEN TO DELETE THIS" paragraph: it is not deleted here, it is narrowed, and Task 5 narrows it again.

Then red-test the amendment the way `CLAUDE.md` requires: temporarily add `engine.gfx.mainCamera.scale.set(2f)` to `render/DiveRenderer.kt`, confirm the test fails and names that file, and remove it. A guard that has never been seen red is not a guard.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/render/CameraInvariants.kt src/test/kotlin/render/CameraInvariantsTest.kt src/main/kotlin/EnPustTil.kt src/test/kotlin/render/MainCameraOwnershipTest.kt
git commit -m "feat: dev-mode camera invariants that check the real framebuffer"
```

---

### Task 3: `CameraRig` — the camera parameters, proven against the old transform

Pure and tested. Not wired in.

> **`MainCameraOwnershipTest` was already amended for this in Task 2 Step 5a**, so creating `CameraRig.kt` does not turn the suite red and this task touches no test file but its own. If it *does* go red here, Task 2 Step 5a was skipped — go back and do it, do not weaken the guard from inside this task.

**Files:**
- Create: `src/main/kotlin/render/CameraRig.kt`
- Test: `src/test/kotlin/render/CameraRigTest.kt`

**Interfaces:**
- Consumes: `Viewport.VISIBLE_DEPTH_METRES` (becomes `Framing` in Task 5)
- Produces: `CameraRig.pixelsPerMetre`, `originX`, `ORIGIN_Y`, `positionX`, `positionY`, `apply(engine, cameraDepth)`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/render/CameraRigTest.kt`. Build the view matrix from `CameraRig`'s parameters with the engine's own JOML, transcribing `ENG/core/graphics/api/Camera.kt:125-131` and citing it in a comment:

```kotlin
private fun screenPos(w: Float, h: Float, camDepth: Float, worldX: Float, worldY: Float): Vector2f
{
    // Transcribed from DefaultCamera.updateViewMatrix (Camera.kt:125-131). Rotation is
    // always zero here, so the rotateXYZ term is omitted. This is a transcription, NOT the
    // engine executing: updateViewMatrix() defaults its interpolation factor to
    // PulseEngine.INSTANCE.data.interpolation, and INSTANCE is `lateinit ... internal set`
    // (PulseEngine.kt:89), so it cannot be called without a live engine.
    val s = CameraRig.pixelsPerMetre(h)
    val ox = CameraRig.originX(w)
    val oy = CameraRig.ORIGIN_Y
    val m = Matrix4f()
        .identity()
        .translate(ox, oy, 0f)
        .scale(s, s, 1f)
        .translate(CameraRig.positionX(w) - ox, CameraRig.positionY(camDepth) - oy, 0f)
    val v = Vector4f(worldX, worldY, 0f, 1f).mul(m)
    return Vector2f(v.x, v.y)
}
```

Then assert, over `listOf(1200f to 900f, 1600f to 900f, 1920f to 1080f, 2400f to 1800f, 3440f to 1440f, 3840f to 2160f)` — **a list whose value is that it contains 4:3, 16:9, 16:10 and 21:9, not that it contains six resolutions** (§3.1):

- `screenPos(w, h, camDepth, 0f, camDepth) == (w/2, 0)`;
- `screenPos(w, h, camDepth, 0f, camDepth + Viewport.VISIBLE_DEPTH_METRES) == (w/2, h)` — **exactly 60 m of water on every display**;
- **parity with the old transform**: for a grid of `x ∈ {-40, -7.5, 0, 12.25, 40}`, `depth ∈ {0, 33, 94.5, 160}` and `camDepth ∈ {-24, 0, 61.75}`, `screenPos(...)` equals `(w/2 + x*h/60, (depth - camDepth)*h/60)` — the old `Viewport.screenX/screenY`, inlined in the test with a comment saying they are the pre-migration formulas and that the migration is defined to be a no-op.

**Three assertions, not five. Two earlier candidates are deliberately absent and must not be added back** (§3(a); review finding 6):

* ~~`w / CameraRig.pixelsPerMetre(h) == w/h * 60`~~ — `w / (h/60) = 60w/h` is an **algebraic identity**. It holds for any value `pixelsPerMetre` returns, including a wrong one, so no mutation of `CameraRig` can make it fail.
* ~~"a given world point lands at the same fraction of `h` on all of them"~~ — the `f(x)·h/h == f(x)·h/h` shape that §3.0 **measured** as unkillable in `ViewportTest`.

Before committing, confirm each of the three surviving assertions has a mutation that reddens it, and write the mutation next to the assertion in a comment:

| assertion | mutation that must kill it |
|---|---|
| `(W/2, 0)` pin | drop `* 0.5f` from `originX` |
| `(W/2, H)` pin | `pixelsPerMetre` divides by `Framing.VISIBLE_DEPTH_METRES * 2` |
| old-transform parity grid | flip the sign of `positionY` |

**And the one this test still cannot catch, stated rather than glossed.** The width-for-height substitution of §3.1 — the bug class that shipped — lives at the *call site*, `val s = pixelsPerMetre(h)` inside `CameraRig.apply`. `apply` takes a live `PulseEngine` and is therefore unreachable from a unit test, and `screenPos` above is a **transcription** that calls `pixelsPerMetre(h)` itself. So writing `pixelsPerMetre(w)` in `apply` would leave `CameraRigTest` entirely green. That substitution is caught by `CameraInvariants` rule 3 on the real framebuffer (the visible world rect would stop having the screen's aspect) and by the 21:9 capture in §3(d) — **by nothing in `./gradlew test`.** Put that sentence in `CameraRigTest`'s class doc; it is the single most useful thing the file can tell the next reader.

If any assertion has no such mutation, it is documentation, not a test — say so in a comment rather than letting it read as verification.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'render.CameraRigTest'`
Expected: FAIL — `Unresolved reference: CameraRig`

- [ ] **Step 3: Implement `CameraRig`**

Create `src/main/kotlin/render/CameraRig.kt` as sketched in §2.2, with the full doc comment — including why it is not the engine `Camera` entity and why `apply` must be called from `onFixedUpdate`.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'render.CameraRigTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/render/CameraRig.kt src/test/kotlin/render/CameraRigTest.kt
git commit -m "feat: CameraRig, proven to reproduce Viewport's transform exactly"
```

---

### Task 4: Make the zone-band strip walk a pure, world-space function

Isolates the one part of `DiveRenderer` where the rewrite could disturb the reflectance floor.

**Files:**
- Modify: `src/main/kotlin/render/DiveRenderer.kt:157-170`
- Test: `src/test/kotlin/render/DiveRendererTest.kt`

**Interfaces:**
- Produces: `DiveRenderer.stripCount(worldTop, worldBottom)`, `DiveRenderer.stripTopDepth(worldTop, index)`, `DiveRenderer.stripCentreDepth(worldTop, worldBottom, index)`

- [ ] **Step 1: Write the failing test**

Add to `DiveRendererTest`:
- consecutive strip centres are exactly `BAND_STRIP_METRES` apart (expose the constant as `internal`);
- the strips tile `[worldTop, worldBottom]` with no gap and no overlap, and the last one is clipped to `worldBottom`;
- the count depends only on `worldBottom - worldTop`, not on resolution — assert the same count for a rect derived from a 900 px screen and from a 2160 px screen;
- the count for the standard 60 m rect is 120, and is clamped to a sane maximum for a degenerate or absurd rect (a camera that has not been applied yet — see risk 4.6);
- every strip centre over `[0, 200]` still produces a colour clearing `GI_REFLECTANCE_FLOOR` once quantized. (The existing sweep test stays; this one asserts the *walk* hits those depths.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'render.DiveRendererTest'`
Expected: FAIL — unresolved references

- [ ] **Step 3: Implement, and rewrite `drawZoneBands` in terms of them**

Keep `drawZoneBands` drawing in screen pixels for now — it takes `worldTop`/`worldBottom` derived from `Viewport.depthAt(0f, cam, h)` and `Viewport.depthAt(h, cam, h)`, which is *numerically identical* to what it does today. Task 5 swaps the source of those two numbers for `cam.topLeftWorldPosition.y`/`bottomRightWorldPosition.y` and the `fillRect` for a world-space one. Doing the extraction now means Task 5's diff in this method is two lines.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: Verify nothing moved**

```bash
EPT_SCREENSHOT=/tmp/task4.png ./gradlew run ; pkill -9 -f EnPustTilKt
```
`/tmp/task4-0.png` must be indistinguishable from a capture taken before this task. This is a pure refactor of the loop bounds; any visible difference is a bug.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/render/DiveRenderer.kt src/test/kotlin/render/DiveRendererTest.kt
git commit -m "refactor: express the zone-band strip walk in world depths"
```

---

### Task 5: The flip — world coordinates everywhere

**One commit. It cannot be split** (§5). Everything drawn through `mainCamera` changes units in the same breath the camera stops being the identity.

**Files:**
- Rename: `src/main/kotlin/render/Viewport.kt` → `src/main/kotlin/render/Framing.kt` (`object Viewport` → `object Framing`)
- Create: `src/test/kotlin/render/FramingTest.kt` (three cases moved out of `ViewportTest`, Step 2)
- Delete: `src/test/kotlin/render/ViewportTest.kt` (the other six, Step 2)
- Modify: `src/test/kotlin/render/MainCameraOwnershipTest.kt` (narrowed to its final guard, Step 2a)
- Modify: `render/DiveCamera.kt`, `render/DiveRenderer.kt`, `render/DiveLighting.kt`, `render/Hud.kt`, `EnPustTil.kt`, `src/test/kotlin/render/DiveCameraTest.kt`, `src/test/kotlin/AttractScreenTest.kt`
- Modify: `src/main/kotlin/render/CameraInvariants.kt` (enable the depth/aspect rules from Task 2)

**Interfaces:**
- Consumes: `CameraRig`, `no.njoh.pulseengine.core.graphics.api.Camera`
- Produces: `DiveRenderer.render(surface, sim, cam)`, `DiveLighting.render(engine, sim, dt)`, `Hud.render(surface, sim, diverX, diverY, pixelsPerMetre, w, h)`

- [ ] **Step 1: Rename `Viewport` to `Framing` and delete its transform functions**

Move the file, rename the object, delete `pixelsPerMetre`, `screenX`, `screenY`, `depthAt` and `screenFraction`. Rewrite the class doc: it is now "how much water is on screen, and where the diver sits in the frame" — framing, not transforms — with a pointer to `CameraRig` for the transform and a one-line note that the HiDPI story it used to tell is now the engine's problem, solved by `ortho(0, w, h, 0)` being reissued on every window change.

The compiler will now list every call site. That is the point of renaming rather than gutting in place.

- [ ] **Step 2: Dissolve `ViewportTest` into `FramingTest` — do not simply delete it**

> **The earlier version of this step was inverted and would have thrown away the working half of the file.** It said to keep "the 'same fraction on every display' ones" and to treat "the rest" as assertions that could not fail. §3.0's mutation run measures the opposite: **two of the three "same fraction" cases are the unkillable ones**, and six of the nine cases die to a real mutation. Follow §3.2's table, not the old sentence.

Create `src/test/kotlin/render/FramingTest.kt` and move three cases into it, keeping their names so `git log --follow` and the mutation record stay meaningful:

* `the diver is large enough to see` — re-expressed as `Framing.DIVER_SIZE_METRES / Framing.VISIBLE_DEPTH_METRES > 0.02f` (numerically the same; `pixelsPerMetre` is gone). Must still die to `DIVER_SIZE_METRES` 3 → 0.3.
* `only part of the water column is visible so descending scrolls` — verbatim, `Framing.` for `Viewport.`. A one-sided bound, not a tautology; it fails at `VISIBLE_DEPTH_METRES` 60 → 100.
* `camera keeps the diver above the top edge of visible water` — verbatim. Must still die to a `targetCameraDepth` sign flip.

Then delete `src/test/kotlin/render/ViewportTest.kt` with the other six, and record in `FramingTest`'s class doc why each went: cases 2 and 3 were **measured** tautologies; case 1 was real but is redundant with case 9 once the transform is gone; case 6 becomes `CameraRigTest`'s `(W/2, 0)` pin; cases 7 and 8 lose their subject with `depthAt`, whose successor is `CameraInvariants` rule 2 on a real framebuffer and nothing in `./gradlew test`.

**Before deleting, re-run the three mutations above and watch the moved cases go red in their new home.** They were killable in `ViewportTest`; the point of moving rather than rewriting them is that they stay killable, and the only way to know is to look.

- [ ] **Step 2a: Replace `MainCameraOwnershipTest`'s guard, and be honest about what is lost**

`CameraRig` is now wired in and writing `mainCamera` 60×/s. Narrow the Task 2 Step 5a allow-list to its final form:

> **No production source outside `render/CameraRig.kt` writes `engine.gfx.mainCamera`.** Reads are allowed and named (`EnPustTil.kt`, for `CameraInvariants` and the HUD anchor).

That is still a real, red-testable guard against a real bug — a second file easing or resetting the shared camera is exactly the fault `6ea1f53` fixed — so **do not delete the file.** Rewrite its class doc for the new invariant and re-red-test it (add a `mainCamera.scale.set(2f)` to `DiveRenderer`, see it fail, remove it).

**What is no longer guaranteed, stated plainly because nothing replaces it.** Since `c47a4b0` the process has a *second* legitimate writer: `SceneEditor`'s `Camera2DController` (`SceneEditor.kt:347`) drives the same object under `EPT_EDITOR=1`. It lives in engine code, so no scan of `src/main/kotlin` can see it and the source guard stays green while the invariant is, at runtime, false.

* **At the booth this does not exist.** `EPT_EDITOR` is unset, `SceneEditor` is never constructed (`EnPustTil.kt:386`), and `CameraRig` is the only writer in the process. The shipped configuration is fully guarded.
* **In editor mode the invariant becomes "exactly one writer *at a time*"**, and the thing that enforces it is not a test — it is Task 10's gate, which skips `CameraRig.apply` while the editor service is running. Its failure mode is loud and immediate (you cannot pan the editor viewport), not silent, which is why a test is not bought for it.
* **No test asserts that gate.** It is verified by hand, once, in Task 10 Step 3. Say so in `MainCameraOwnershipTest`'s class doc so the next reader does not assume the file covers more than it does.

- [ ] **Step 3: Drive the camera from the fixed tick**

In `EnPustTil`:

```kotlin
override fun onFixedUpdate()
{
    if (lifecycle.state != RunLifecycleState.IDLE)
        sim.tick(engine.data.fixedDeltaTime, readInput())

    // Camera easing runs on the FIXED tick, not the render clock. The engine interpolates
    // the camera between the position snapshotted at the top of each fixed step and the
    // current one (Camera.kt:120-123, PulseEngineImpl.kt:279); a render-clock write pairs
    // values that were never consecutive fixed states and makes the interpolator judder.
    // DiveCamera's 1 - e^(-k*dt) easing is already frame-rate independent, so sampling it
    // at 60 Hz costs nothing and the engine's interpolation renders it smoother above 60 fps
    // than the old per-frame update did.
    camera.update(engine.data.fixedDeltaTime, sim.depth)
    CameraRig.apply(engine, camera.depth)
}
```

Remove `camera.update(...)` from `onUpdate`. Add `CameraRig.apply(engine, camera.depth)` in `onCreate` right after `camera.snapTo(sim.depth)`, so frame 1's `beginFrame` builds a real matrix (risk 4.6) — and after `lifecycle.justStarted`'s `camera.snapTo` in `onUpdate` too.

- [ ] **Step 4: Convert `DiveRenderer` to metres**

Signature becomes `render(surface: Surface, sim: DiveSim, cam: no.njoh.pulseengine.core.graphics.api.Camera)`.

- `drawZoneBands`: `worldTop = cam.topLeftWorldPosition.y`, `worldBottom = cam.bottomRightWorldPosition.y`; strips are `BAND_STRIP_METRES` tall and span `cam.topLeftWorldPosition.x` to `cam.bottomRightWorldPosition.x`. The colour calls are unchanged.
- `drawColumnWalls`: `left = cam.topLeftWorldPosition.x`, `right = cam.bottomRightWorldPosition.x`; fill `left .. -COLUMN_HALF_WIDTH` and `+COLUMN_HALF_WIDTH .. right`, with `WALL_EDGE_METRES` faces drawn over them. A non-positive width still draws nothing, so the narrow-aspect case still needs no special handling — keep that comment.
- `drawSurfaceLine`, `drawAirPockets`, `drawPearls`, `drawAnglerfish`, `drawDiver`: positions are `(entity.x, entity.depth)`, sizes are the existing `*_SIZE_METRES` constants with no multiplication.
- Sizes are now metres, so `Viewport.pixelsPerMetre` disappears from this file entirely.
- Update the class doc: it no longer draws in screen pixels and no longer needs to know the resolution.

- [ ] **Step 5: Convert `DiveLighting` to metres**

`render(engine, sim, dt)` — no more `camera`, `w`, `h`. `drawLight(texture = Texture.BLANK, x = pearl.x, y = pearl.depth, w = PEARL_LIGHT_SIZE_METRES, h = PEARL_LIGHT_SIZE_METRES, ...)`. Same for the anglerfish lure and the diver beam.

Add to the class doc, replacing the paragraph about calling from `onRender` for alignment:

```kotlin
// STILL CALLED FROM onRender, but the alignment guarantee is now structural rather than
// procedural. GI's local scene surface is created with `camera = engine.gfx.mainCamera`
// (GlobalIlluminationSystem.kt:78) -- the same object mainSurface uses -- and every matrix
// is built once per frame in gfx.initFrame (GraphicsImpl.kt:111-113) before any game code
// runs. So a light drawn at world (x, depth) and a square drawn at world (x, depth) go
// through the identical viewProjectionMatrix and CANNOT drift, whichever callback issued
// them. What onRender still buys is ordering: GiSceneRenderer's batch must be filled before
// gfx.drawFrame.
//
// The drift risk has moved to the HUD, which is on its own screen-space camera. See the
// diver anchor in EnPustTil.onRender.
```

Delete `isOnScreen`; culling comes in Task 7 (until then, draw them all — there are at most a few dozen and they are cheap).

- [ ] **Step 6: Convert `Hud` to a pure screen-space consumer**

`render(surface: Surface, sim: DiveSim, diverX: Float, diverY: Float, pixelsPerMetre: Float, w: Float, h: Float)`. Remove the `Viewport` and `DiveCamera` imports. Replace every `Viewport.pixelsPerMetre(h)` with the passed-in `pixelsPerMetre`. Everything else — the golden angle, the low-air size gain, the tape, the alpha-squared constants — is untouched.

Update the class doc's "World-anchored elements … positioned at the diver's REAL screen position — computed with the same `DiveCamera` the world renderer used" paragraph to say where the position now comes from and why.

- [ ] **Step 7: Wire the HUD anchor to the world camera**

In `EnPustTil.onRender`, exactly as in §2.4 — including the comment about the shared `Vector2f`. Take the HUD's `w`/`h` from `engine.gfx.getSurfaceOrDefault("hud").config.width/height`, not from `engine.window`. Update the `camera: left null` comment on the `createSurface` call (risk 4.3): the reason is no longer "the GI Camera entity drives mainCamera" but "mainCamera is now a world-space camera scaled by ~30, and the HUD is authored in screen pixels".

- [ ] **Step 8: Fix up the tests that only need renaming**

`DiveCameraTest` and `AttractScreenTest` reference `Viewport.*` constants. Rename only — do not change an assertion.

- [ ] **Step 9: Run the suite**

Run: `./gradlew test`
Expected: PASS. Count should be 238 (at `231c6a6`) − 9 (`ViewportTest`) + 3 (`FramingTest`) + the new `CameraRigTest`/`CameraInvariantsTest`/`DiveRendererTest` cases. Do not treat the number as the check — the check is that every case moved in Step 2 has been seen red under its named mutation.

- [ ] **Step 10: Verify on a real framebuffer, at three aspect ratios**

For each of `1200x900`, `1600x900`, `2100x900` in `application-dev.cfg`:

```bash
caffeinate -d -u -t 900 &
EPT_SCREENSHOT=/tmp/ar-<shape>.png ./gradlew run
pkill -9 -f EnPustTilKt
```

Check, for every shape:
- the diver square in `-0.png` and the air-ring centre in `-hud-0.png` are concentric;
- exactly 60 m of water is visible vertically (the depth tape marker and the world agree);
- the column walls appear on both edges at 16:9 and 21:9, and fall off-frame at 4:3, with no letterbox bars;
- the waterline is horizontal and full-width;
- no horizontal seam anywhere between 90 m and 100 m (risk 4.2);
- BANKED in the Abyss is still bright (risk 4.3);
- `EPT_DEV=1` reports no `CameraInvariants` violations at any shape.

And with the camera easing hard (hold DOWN with a big haul): pearl halos sit on pearl squares, and the air ring sits on the diver (risk 4.1).

- [ ] **Step 11: Commit**

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "refactor: draw the world in metres through the engine camera"
```

---

### Task 6: Retune the GI parameters that are defined in camera scale

`mainCamera.scale` went from 1 to ≈30. `aoRadius` is multiplied by it (§1.7).

**Files:**
- Modify: `src/main/kotlin/render/DiveLighting.kt`

- [ ] **Step 1: Measure**

Capture the Abyss (≥120 m, several pearls in frame) before and after Task 5 at the same seed and depth. Compare the dark halo around each pearl light quad. `ao.frag:36` computes `radius = aoRadius * camScale`, so a ~30× change should be obvious; if it is not, say so and move on rather than tuning a number nobody can see.

- [ ] **Step 2: Compensate, if the measurement says to**

Set `aoRadius` from `CameraRig.apply` (it already knows the surface height and the scale) so the effective screen-space radius is what it was:

```kotlin
// aoRadius is multiplied by the camera scale in ao.frag:36. With the old identity camera
// that scale was 1 and the engine default of 30 was an implicit screen-space value; now the
// scale is pixelsPerMetre, so hold the product constant.
gi?.aoRadius = ENGINE_DEFAULT_AO_RADIUS / CameraRig.pixelsPerMetre(surfaceHeight)
```

If the measurement says the change is invisible or an improvement, leave the default and write down which, with the capture that says so.

- [ ] **Step 3: Confirm the two non-issues, in a comment**

`radius = 0f` on every `drawLight` skips the falloff branch entirely (`radiance_cascades.frag:120-127`), so its `camScale` dependence does not reach us — but leave a note, because the first person to set a non-zero radius will be tuning a number whose meaning depends on the display's height. The `scene.vert:86-88` minimum-size clamp is scale-invariant (`1500/(resolution.y·camScale)` world units is a constant number of screen pixels) and needs nothing.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/render/DiveLighting.kt src/main/kotlin/render/CameraRig.kt
git commit -m "fix: keep GI ambient occlusion the same size now the camera has scale"
```

---

### Task 7: Cull with the engine's own view test

**Files:**
- Modify: `src/main/kotlin/render/DiveRenderer.kt`, `src/main/kotlin/render/DiveLighting.kt`
- Modify: `src/test/kotlin/render/DiveLightingTest.kt` (drop the `isOnScreen` cases)

- [ ] **Step 1: Replace every bespoke bounds check**

`cam.isInView(x - size/2, depth - size/2, size, size, padding)` (`ENG/core/graphics/api/Camera.kt:112-116`). It tests x as well as y, which none of the current checks do — today a pearl far outside the visible half-width is still submitted. Use a padding generous enough for a light's glow (its radius, not its quad).

- [ ] **Step 2: Run the suite and look**

Run: `./gradlew test`, then capture at 21:9 and at 4:3. Nothing may pop in or out at the frame edges; a pearl's glow must not vanish before the pearl leaves the screen.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/render/DiveRenderer.kt src/main/kotlin/render/DiveLighting.kt src/test/kotlin/render/DiveLightingTest.kt
git commit -m "refactor: cull against the engine's view rectangle instead of screen rows"
```

---

### Task 8: Prepare the draw calls for real art

**Files:**
- Modify: `src/main/kotlin/render/Draw.kt`, `src/main/kotlin/render/DiveRenderer.kt`, `src/main/kotlin/render/Hud.kt`
- Test: `src/test/kotlin/render/DrawTest.kt`

- [ ] **Step 1: Write the failing guard test**

Add to `DrawTest`: walk `src/main/kotlin` and fail on any `drawQuad(` or `drawLine(`, naming the file and line. Message: point at `render/Draw.kt`'s explanation and at `drawTexture(..., angle, xOrigin, yOrigin)` as the supported alternative.

- [ ] **Step 2: Run it to verify it passes for the right reason**

Run: `./gradlew test --tests 'render.DrawTest'`
Expected: PASS. Then temporarily add a `drawQuad(` call somewhere and confirm it FAILS. Remove it. A test that cannot fail is worse than no test.

- [ ] **Step 3: Add a centred `fillRect` overload and use it**

`Surface.fillRectCentred(x, y, w, h, angle = 0f)` = `drawTexture(Texture.BLANK, x, y, w, h, angle, xOrigin = 0.5f, yOrigin = 0.5f)`. Replace every `- size * 0.5f` in `DiveRenderer` and `Hud`. This is what the reference does (`REF/src/main/kotlin/entities/level/Spark.kt:84`) and it is the shape every sprite call will take, with rotation available for free.

- [ ] **Step 4: Run the suite and capture**

Run: `./gradlew test`, then capture and confirm nothing moved by half a square.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/render src/test/kotlin/render/DrawTest.kt
git commit -m "refactor: centre-origin draw calls, and a guard against drawQuad returning"
```

---

### Task 9: Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `src/main/kotlin/render/DiveRenderer.kt` (the `minReflectance` correction)
- Modify: `docs/superpowers/specs/2026-08-04-en-pust-til-design.md` (§17 amendment log)

- [ ] **Step 1: `CLAUDE.md`**

- "Architecture / render": `DiveRenderer` draws the world in **metres** to `mainSurface`; `Hud` draws **screen pixels** to `"hud"`; `CameraRig` is the only writer of `engine.gfx.mainCamera`; `Framing` holds framing constants and no transform.
- "Frame ordering is load-bearing": camera easing is now on the **fixed tick**, and why (§1.5). Light/geometry alignment is structural, and the remaining drift risk is the HUD anchor.
- Platform constraints: keep the `engine.window.width/height` physical-pixels note, but say that the world no longer consumes it — sizes there are metres — and that screen-space code should prefer `surface.config.width/height`, which is what the surface's own projection was built from.
- Add the invariant worth remembering: **exactly `Framing.VISIBLE_DEPTH_METRES` of water is visible vertically on every display, and a wider display shows more water sideways, never less water down.**

- [ ] **Step 2: Correct `DiveRenderer`'s reflectance-floor doc**

`GI_REFLECTANCE_FLOOR`'s comment says the floor "cannot be avoided from here (it is the engine's…)". Replace with: `minReflectance` *is* settable (`GlobalIlluminationSystem.kt:56`, a public `@Prop var`, re-pushed every frame at `:217`); we keep the engine default and hold the water above it because the colour-side solution is measured and this is not the pass in which to change lighting. State that, so the next person makes the choice knowingly.

- [ ] **Step 3: Amendment log**

Add to design spec §17: the world is rendered in metres through the engine camera; `Viewport` is gone; content generation stays procedural and seeded (§10's "authoring uses the Pulse Engine scene editor" continues to apply to the diver's visual representation, lights, particles and effects — **not** to pearl, vent or anglerfish placement); the aspect-ratio guarantee is now the engine's.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md src/main/kotlin/render/DiveRenderer.kt docs/superpowers/specs/2026-08-04-en-pust-til-design.md
git commit -m "docs: record the world-coordinate migration and its invariants"
```

---

# Stage D — the entity layer

> Read §1.8, §2.6 and §4.9–4.14 before starting. The three hard constraints are: **`src/main/kotlin/dive/` does not change**; **entities are views, never the source of truth**; **pearl, vent and anglerfish placement stays seeded and procedural and is never authored into `dive.scn`**. Resolution and aspect independence remain non-negotiable — the booth display size is unknown.

### Task 10: Author `dive.scn`, and let the editor actually reach STOPPED

Unblocks the editor. No entities yet, and no dependency on Stage B — this task is worth doing early precisely because it is the cheapest way to test §1.8's reading of the editor against the real thing.

**Files:**
- Create: `src/main/resources/dive.scn` (an empty scene with the two systems, saved from the editor or hand-written)
- Modify: `src/main/kotlin/render/DiveLighting.kt:141-143, 204, 214-220, 255`
- Modify: `src/main/kotlin/EnPustTil.kt` (editor gates)

**Interfaces:**
- Consumes: `devMode`, `System.getenv("EPT_EDITOR")`
- Produces: a scene loaded from a file, `STOPPED` when the editor is up and `RUNNING` otherwise

- [ ] **Step 1: Correct the two false comments before changing behaviour**

`DiveLighting.kt:141-143` claims GI "needs … a RUNNING [scene] for its `onUpdate` to install the multiply effect". Read `ENG/core/scene/SceneManagerImpl.kt:206`, `ENG/core/scene/Scene.kt:80-97` and `:113-117` and confirm for yourself that neither consults `SceneState`, and that `GlobalIlluminationSystem` has no `onStart` (`grep -n "override fun on" GlobalIlluminationSystem.kt` → `74, 192, 220, 234, 257`). Rewrite the comment to say what is actually true and cite it.

Then `DiveLighting.kt:214-220`: `EntityRendererImpl` is about to draw the diver. Update it from "stays even though it draws nothing" to "draws the diver, **and** is GI's precondition at `GlobalIlluminationSystem.kt:184`", and add the ordering note from risk 4.10 — it must be added *before* `GlobalIlluminationSystem`, because systems initialise in list order (`Scene.kt:84-97`).

- [ ] **Step 2: Switch from `createEmptyAndSetActive` to a load with a fallback**

`engine.scene.loadAndSetActive("dive.scn", fromClassPath = !devMode)`, falling back to `createEmptyAndSetActive("dive.scn")` with a `Logger.warn` if the load fails. The booth must boot with placeholder squares rather than not boot. Give the scene an **absolute** `fileName` in dev mode pointing at `src/main/resources/dive.scn`, so a save lands in the source tree without moving `engine.config.saveDirectory`, which `ScoreRepository` owns (`score/ScoreRepository.kt:184`, §1.8.6).

- [ ] **Step 3: Make `engine.scene.start()` conditional, and gate the two things that fight the editor**

- Skip `engine.scene.start()` when the editor is enabled, so the scene stays `STOPPED` and `SceneEditor.kt:345` lets viewport interaction run. Comment the citation.
- Skip `CameraRig.apply` while the editor service `isRunning` (risk 4.12). Note in the comment that the single-writer invariant is preserved — the writer is the editor.
- Freeze `sim.tick` while the editor is up **and** the scene is `STOPPED` (risk 4.13).
- Replace `EnPustTil.kt:373-377`'s "expect the world to render wrong while the editor is open" note: it is now fixed, not tolerated. Also drop the stale "`dive.scn` holds exactly one entity, GI's Camera" — that entity was deleted in `6ea1f53`.

- [ ] **Step 4: Look at it — this is the step the whole stage rests on**

```bash
caffeinate -d -u -t 900 &
EPT_EDITOR=1 EPT_DEV=1 ./gradlew run
```

Confirm, and write down which of these are true, because §1.8 is a reading of source and this is the measurement:
- the editor opens and the Outliner appears (still 0/0 — there are no entities yet);
- the Scene Systems panel lists `EntityRendererImpl` and `GlobalIlluminationSystem`, and editing `aoRadius` or `dithering` changes the frame immediately;
- the viewport grid draws (it uses `drawLine`, fixed for local dev in `6b05f07`);
- panning and zooming the editor camera works and does not snap back;
- the diver is frozen rather than swimming off;
- Ctrl+S writes `src/main/resources/dive.scn` and `git diff` shows a small, readable file.

If any of these is false, stop and re-read the editor source rather than proceeding — Tasks 11–14 all assume this step passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/dive.scn src/main/kotlin/render/DiveLighting.kt src/main/kotlin/EnPustTil.kt
git commit -m "feat: load dive.scn from a file and let the scene editor reach STOPPED"
```

---

### Task 11: The boundary guards, written before there is anything to guard

Deliberately ahead of the first entity, so every guard is red-tested against a real violation instead of written to fit passing code. `MainCameraOwnershipTest` (added in `6ea1f53`) is the pattern; read its class doc first — it is honest about being a precondition guard rather than a behaviour test, and these are the same.

**Files:**
- Test: `src/test/kotlin/render/EntityBoundaryTest.kt`
- Test: `src/test/kotlin/render/SceneFilePurityTest.kt`

**Interfaces:**
- Consumes: `src/main/kotlin/**.kt` as text, `src/main/resources/dive.scn` as text
- Produces: nothing in production

- [ ] **Step 1: Write `EntityBoundaryTest`**

Four source scans over `src/main/kotlin/**.kt`, each failing with the offending file, line and a one-line reason plus a pointer to §2.6:

1. no `engine.scene.addEntity(` — nothing may spawn an entity at runtime (constraint 3, risk 4.9);
2. no `EntityUpdater` in any `addSystem(` call — it is the only thing that can deliver `onUpdate`/`onFixedUpdate` to an entity (`EntityUpdater.kt:31-41`), so its absence is what makes "entities cannot hold evolving state" structural rather than aspirational;
3. no assignment into a sim object from `render/` — regex `\b(sim|pearl|pocket|fish)\.\w+\s*=` — state flows one way only (§2.6 rule 4);
4. no `no.njoh.pulseengine` import under `src/main/kotlin/dive/` — currently true by convention only, and it is the constraint that let `Buoyancy` be rewritten from scratch on playtest feedback.

- [ ] **Step 2: Write `SceneFilePurityTest`**

Over `src/main/resources/dive.scn` as text:

- no entity type name containing `Pearl`, `AirPocket` or `Anglerfish` (constraint 3 — the water column is never authored);
- total entity entries `<= 8` (so the tuning loop's `git diff` review stays real, §2.6);
- every entity FQCN is on an explicit allow-list **and** resolves via `Class.forName` (a package move otherwise silently empties the scene — `FAIL_ON_INVALID_SUBTYPE = false`, `DataImpl.kt:166`);
- every asset-reference-shaped string value names a file that exists under `src/main/resources/` (the reference shipped `"torch_flame"` against an asset called `torche_flame`, §1.8.7).

The last two are no-ops until Task 12 puts something in the file. Say so in the test's class doc rather than leaving a reader to think they are proving something today.

- [ ] **Step 3: Red-test every single guard**

Run: `./gradlew test --tests 'render.EntityBoundaryTest' --tests 'render.SceneFilePurityTest'` → PASS.

Then, one at a time, insert the violation and confirm the specific test goes red with a useful message: an `engine.scene.addEntity(Foo())` line; an `addSystem(EntityUpdater())`; a `sim.x = 0f` in `DiveRenderer`; an `import no.njoh.pulseengine.core.PulseEngine` in `dive/Tuning.kt`; a fake `"dive.Pearl"` string in `dive.scn`. Remove each. **A guard that has never been seen red is not a guard** — this is the same requirement Task 8 Step 2 puts on the `drawQuad` scan, and commit `4493eeb` deleted tests for failing it.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/render/EntityBoundaryTest.kt src/test/kotlin/render/SceneFilePurityTest.kt
git commit -m "test: guard the sim/entity boundary and dive.scn's purity"
```

---

### Task 12: `DiverEntity` — the diver becomes a view

**Depends on Task 5 (Stage B).** `CommonSceneEntity` positions are world units; before the flip an entity at `(12, 94)` lands 12 px from the screen corner (§2.6 sequencing).

**Files:**
- Create: `src/main/kotlin/render/entities/DiverEntity.kt`
- Create: `src/main/kotlin/render/EntityBridge.kt`
- Test: `src/test/kotlin/render/EntityBridgeTest.kt`
- Modify: `src/main/kotlin/render/DiveRenderer.kt` (delete `drawDiver`), `src/main/kotlin/EnPustTil.kt`, `src/main/resources/dive.scn`

**Interfaces:**
- Consumes: `DiveSim.x`, `DiveSim.depth`, `DiveSim.heldMass` (read only), `Framing.DIVER_SIZE_METRES`
- Produces: `EntityBridge.pushDiver(entity, x, depth, heldMass)`, `DiverEntity`

- [ ] **Step 1: Write the failing test**

`EntityBridgeTest`, on a pure function that needs no GL context:
- `x`/`y` on the entity equal the sim's `x`/`depth` exactly;
- `width == height == DIVER_SIZE_METRES + heldMass * 0.03f` — the rule currently at `DiveRenderer.kt:262`, moved not changed;
- **the mutation test**: changing `sim.x` changes `entity.x`. Without this the other two are the tautology §3 warns about.

Run: `./gradlew test --tests 'render.EntityBridgeTest'` → FAIL, unresolved reference.

- [ ] **Step 2: Implement `DiverEntity`**

`class DiverEntity : CommonSceneEntity()`, `@Icon("USER", size = 24f, showInViewport = true)`, `@Name("Diver")`. Properties: `@TexRef var texture = ""`, `var colour: Color`, `@Prop(min = 0.5f, max = 10f) var baseSizeMetres`, `@Prop(min = 0f) var massSizeGain`. `onRender` draws `drawTexture(asset ?: Texture.BLANK, x, y, width, height, rotation, xOrigin = 0.5f, yOrigin = 0.5f)` — the shape Task 8 Step 3 established, and the shape every sprite call will take.

Class doc must state, with citations: that it owns **appearance only** and its position is pushed from `DiveSim` (§2.6); that it implements `Renderable` and **deliberately not `GiLightSource`** (risk 4.11); that the Inspector will show a frozen `x` while the game runs, because `SceneEditor.kt:1030-1033` only pushes gizmo-driven values back; that its `z` must stay equal to every other Stage D entity's (risk 4.14).

- [ ] **Step 3: Implement `EntityBridge` and wire it**

`EntityBridge.push(engine, sim)` called from `EnPustTil.onFixedUpdate`, **after `sim.tick`**, and **only when `engine.scene.state == SceneState.RUNNING`** — when STOPPED the gizmo owns `x`/`y`, so there is exactly one writer at all times. Comment that rule where the gate is; it is the whole reason the editor is usable and the boundary is intact simultaneously.

Look up the entity once and cache it (`engine.scene.getAllEntitiesOfType<DiverEntity>()`), tolerating absence with a single WARN — a missing entity must degrade to "no diver drawn", not a crash at the booth.

- [ ] **Step 4: Delete `DiveRenderer.drawDiver` and author the entity**

Remove `drawDiver` and its call from `render`. Add one `DiverEntity` to `dive.scn` (author it in the editor and Ctrl+S, then hand-check the diff). Confirm `SceneFilePurityTest` still passes and its FQCN/asset rules are now doing real work.

- [ ] **Step 5: Run the suite**

Run: `./gradlew test` → PASS.

- [ ] **Step 6: Look at it, in both modes**

Booth mode (`./gradlew run`, no `EPT_EDITOR`): the diver is where it was, the same size, the load-scaling still visible, the air ring still concentric with it, the beam still on it. Capture and compare against a pre-task capture — **this is a refactor and any visible difference is a bug.**

Editor mode (`EPT_EDITOR=1`): the diver appears in the Outliner; selecting it fills the Inspector; the gizmo moves it with the scene STOPPED; F10 starts the sim and the diver takes over its own position; editing `baseSizeMetres` while running changes the frame immediately. Record which of those are true.

- [ ] **Step 7: Deliberately try to pollute the scene**

With the scene RUNNING under F10, press **Ctrl+S**. Then `git diff src/main/resources/dive.scn`. It must be empty (risk 4.9). Record the result. If it is not empty, something is spawning entities and Task 11's guard is wrong.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/render src/test/kotlin/render src/main/resources/dive.scn src/main/kotlin/EnPustTil.kt
git commit -m "feat: draw the diver from an authored appearance-only scene entity"
```

---

### Task 13: Look prototypes for pearls, vents, the anglerfish and the diver's light

The part that pays for the stage: asset pickers and live numbers for the ~74 objects that are **not** entities.

**Files:**
- Create: `src/main/kotlin/render/entities/Looks.kt` (`PearlLook`, `VentLook`, `AnglerfishLook`, `DiverLightLook`)
- Modify: `src/main/kotlin/render/DiveRenderer.kt`, `src/main/kotlin/render/DiveLighting.kt`, `src/main/resources/dive.scn`

- [ ] **Step 1: Implement the prototypes**

Each is a `CommonSceneEntity` that draws nothing: `set(HIDDEN)` in `init{}`, empty `onRender`, `@Icon(..., showInViewport = true)` so it can still be found. Properties are the constants they replace — `@TexRef var texture`, `var colour: Color`, `@Prop(min…max) var sizeMetres`, and for `DiverLightLook` the beam's intensity/cone parameters currently living in `DiveLighting`.

Class doc on `PearlLook` carries risk 4.14 **verbatim**: it exists precisely so that seventy pearls do not become seventy entities, and it must say why (Outliner blindness at `SceneEditor.kt:336-343`, no culling in `EntityRendererImpl`, the per-frame TimSort at `:132` against `CLAUDE.md`'s no-allocation rule, and the intransitive comparator at `:155-158` crashing TimSort at n ≥ 32). Also document that `x`/`y`/`width`/`height` are inherited and meaningless here — the engine offers no way to hide inherited props from the Inspector.

- [ ] **Step 2: Read them from the immediate-mode draws**

`DiveRenderer.drawPearls` / `drawAirPockets` / `drawAnglerfish` and `DiveLighting`'s three `drawLight` calls take their size/colour/texture from the prototype instead of a file-private constant. Look the prototypes up **once**, not per draw call — no per-frame allocation and no per-frame scene query in the render path.

Keep the anglerfish reading `PearlLook`, not its own size: `DiveRenderer.kt:243-247` records that "in the Abyss you cannot tell treasure from predator by looking" is a design rule, and one shared object is how that rule survives someone tuning in the editor. `AnglerfishLook` therefore carries only what legitimately differs.

**Do NOT expose the zone-band colour tables, `BAND_STRIP_METRES`, or anything else on the reflectance-floor path** (§2.6). They stay Kotlin constants guarded by `DiveRendererTest`'s 0–200 m sweep; the ~94 m seam is not worth a text field.

- [ ] **Step 3: Author them and run**

Add one of each to `dive.scn` (six entities total, under `SceneFilePurityTest`'s ceiling of eight). Run `./gradlew test` → PASS, then capture in booth mode and confirm the frame is unchanged from Task 12's capture — the values authored into the scene must be exactly the constants they replaced, so this is again a refactor with no visible difference.

- [ ] **Step 4: Do one real tuning round, and time it**

Open the editor, change a pearl's `sizeMetres` and colour, F10, watch it, Ctrl+S, `git diff`, rebuild. **Write down how long that took versus edit → gradle → relaunch → screenshot.** That number is the entire justification for the stage; if it is not obviously better, say so in the task report and consider reverting Task 13.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/render src/main/resources/dive.scn
git commit -m "feat: authored look prototypes for pearls, vents, the fish and the beam"
```

---

### Task 14: Document what the entity layer is — and honestly, what it is not

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-08-04-en-pust-til-design.md` (§17 amendment log)

- [ ] **Step 1: `CLAUDE.md`**

- Architecture: a fifth boundary — `render/entities/` holds **appearance-only** scene entities authored in `src/main/resources/dive.scn`. State flows `DiveSim → EntityBridge → entity`, one direction, never back. `EntityUpdater` is deliberately absent, and that absence is what makes it structural.
- The editor: `EPT_EDITOR=1` opens it; the scene stays `STOPPED` so the viewport works; F10 runs it with the panels up; **Ctrl+S is the only thing that persists a tuned value, and stop discards everything else.**
- Platform constraints: add the four editor facts that will otherwise be rediscovered painfully — runtime-spawned entities never appear in the Outliner (`SceneEditor.kt:336-343`); viewport gizmos require `STOPPED` (`:345`); the Inspector never re-reads values game code writes (`:1030-1033`); `@Prop(editable=false)` and `min/max` are honoured only on numeric/text fields (`UiElementFactory.kt:673-682`).
- The rule: **`dive.scn` contains appearance only. Pearl, vent and anglerfish placement is seeded and procedural, and `SceneFilePurityTest` fails the build if that ever stops being true.**

- [ ] **Step 2: Amendment log**

Design spec §17: §10's "authoring uses the Pulse Engine scene editor" now applies concretely — to the diver's appearance, the look prototypes and the lighting parameters, and explicitly **not** to pearl, vent or anglerfish placement, which stays generated from `dailySeed` so every attendee faces an identical column and day two is still a one-line config change.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-08-04-en-pust-til-design.md
git commit -m "docs: record the appearance-only entity layer and the editor's real limits"
```

---

## 7. Definition of done

- [ ] `./gradlew test` passes, and every deleted `ViewportTest` case is either covered by `CameraRigTest` or consciously abandoned as untestable (§3)
- [ ] `render/Viewport.kt` no longer exists, and nothing outside `CameraRig` computes a world-to-screen position
- [ ] `engine.gfx.mainCamera` is written from exactly one place, on the fixed tick
- [ ] No scene `Camera` entity, and no engine-authored level content
- [ ] `src/main/kotlin/dive/` is byte-identical to its state at the start of this plan
- [ ] At 4:3, 16:9 and 21:9 on a real framebuffer: the diver square and the air-ring centre are concentric; exactly 60 m of water is visible; the walls frame the column with no letterbox bars
- [ ] Mid-descent with the camera easing hard: pearl halos sit on pearl squares, and the HUD sits on the diver
- [ ] No horizontal seam between 90 m and 100 m
- [ ] BANKED is still bright in the Abyss
- [ ] `EPT_DEV=1` reports no `CameraInvariants` violations at any of the three shapes, including across a `LEFT_ALT+ENTER` fullscreen toggle
- [ ] Frame rate is unchanged with lighting on

### Stage D additions

- [ ] `src/main/kotlin/dive/` is still byte-identical, and no `no.njoh.pulseengine` import exists under it — asserted by a test, not by memory
- [ ] Nothing in production calls `engine.scene.addEntity`, and `EntityUpdater` is not in the scene — both asserted by tests that have each been seen red
- [ ] `dive.scn` contains **appearance only**: ≤ 8 entities, no `Pearl`/`AirPocket`/`Anglerfish` type, every FQCN resolves, every asset reference exists
- [ ] Ctrl+S while the scene is RUNNING leaves `git diff src/main/resources/dive.scn` empty — measured once, recorded
- [ ] Booth mode (`EPT_EDITOR` unset) captures are indistinguishable from the pre-Stage-D captures — Stage D is a refactor with a workflow payoff, not a visual change
- [ ] With `EPT_EDITOR=1`: the Outliner lists the diver and the look prototypes; the gizmo works with the scene STOPPED; the editor camera pans and zooms without snapping back; the sim is frozen while STOPPED
- [ ] With F10: an Inspector edit visibly changes the running frame, and survives Ctrl+S → rebuild → run
- [ ] One real tuning round has been timed against edit → gradle → relaunch → screenshot, and the number is written down

## 8. Deliberately out of scope

- ~~Scene entities for the diver, pearls, vents or the anglerfish (§1.3, §1.6)~~ **Amended by Stage D.** Still out of scope for **pearls, vents and the anglerfish** — and the evidence in §1.8.3/§1.8.5 makes that a firmer no than it was, not a softer one. The **diver** becomes a single authored appearance-only entity (§2.6, Task 12), alongside four non-drawing look prototypes.
- `GiLightSource` entities (§1.6) and the `GI_GLOBAL_SCENE` far-field light pass — **reaffirmed**, with the new reason at §4.11: adopting it on `DiverEntity` would change the lighting, not just the workflow
- ~~`.scn`-authored level content of any kind~~ **Amended:** `dive.scn` now carries **appearance** (textures, sizes, colours, light parameters). It carries **no level content**: pearl, vent and anglerfish placement stays seeded and procedural from `dailySeed`, per design spec §10 as amended, and `SceneFilePurityTest` fails the build if that changes.
- Runtime entity spawning of any kind (§4.9) — the reason `addEntity` is banned outright rather than used carefully
- Exposing the zone-band colour tables or `BAND_STRIP_METRES` in the Inspector (§2.6) — they are on the `GI_REFLECTANCE_FLOOR` path and stay Kotlin constants guarded by `DiveRendererTest`'s sweep
- Removing the GI reflectance floor by setting `minReflectance = 0` (§1.7 — documented, not done)
- A world-space HUD surface (§2.4 — revisit with the §12 cash-out spectacle)
- Camera zoom, shake or rotation, which the migration makes possible and which the cash-out will want
- `GiOccluder` and normal-mapped lighting on the diver, which the migration also makes possible and which belongs with real art
- Any art at all — Stage D builds the slot the art drops into and puts nothing in it
