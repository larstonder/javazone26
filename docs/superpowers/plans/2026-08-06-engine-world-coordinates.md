# Én Pust Til — Migrating to the engine's world-coordinate model

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `engine.gfx.mainCamera` the single authority on where a world point lands on screen, in world units (metres), so that the world, the GI light map and the diver-tracking parts of the HUD cannot disagree — at any framebuffer size or aspect ratio — and so that real art can be authored in metres.

**Spec:** [`docs/superpowers/specs/2026-08-04-en-pust-til-design.md`](../specs/2026-08-04-en-pust-til-design.md) — 🔒 LOCKED
**Predecessor plan:** [`2026-08-04-en-pust-til-gameloop.md`](2026-08-04-en-pust-til-gameloop.md)
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
| `aoRadius` | `ao.frag:36` — `radius = aoRadius * camScale` | **Real regression risk.** Left at the engine default `30f` (`GlobalIlluminationSystem.kt:57`), the effective AO radius grows ~30×. Our light quads are drawn with `Color`'s default alpha 1 and therefore also seed the SDF (`jfa_seed.frag:12-13`, `final.frag:33`), so we *do* have AO. Must be measured and compensated — see Task 6. |
| minimum light quad size | `scene.vert:86-88` — `max(size, pixelSizeInWorld · 1500 / camScale)` | **None.** `pixelSizeInWorld = 1/(resolution.y)` for an orthographic camera, so the floor is `1500/(resolution.y · camScale)` *world* units, which is a constant number of screen pixels. Scale-invariant by construction. |

One quantity gets **better**: `jitterFix` (`GiSceneRenderer.kt:150-162`) derives a sub-pixel UV offset from `camera.viewMatrix.m30()/m31()`. Today that translation is always zero, so the quarter-resolution light map cannot be jitter-compensated at all and light sampling snaps to the light-texture grid as pearls scroll past a stationary camera. After the migration the camera translates and the compensation actually engages.

`minReflectance` is **not** camera-dependent — it is a colour test in `GLSL/effects/texture_multiply_blend.frag:17-21` against the Euclidean length of the linear albedo, fed from `GlobalIlluminationSystem.kt:56` (default `0.02f`) and re-pushed every frame (`:217`). The migration cannot touch it.

> **Correction to an existing comment.** `DiveRenderer.kt:70-72` says the reflectance floor "cannot be avoided from here (it is the engine's…)". It can: `minReflectance` is a public `@Prop var` and setting `system.minReflectance = 0f` in `DiveLighting.setup` would remove the floor entirely. The current colour-side solution is measured, working and cheaper to trust than a lighting change made in the same pass as a coordinate change, so **we are not changing it** — but the comment should stop claiming impossibility. Task 9.

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
* **No scene entities for the diver, pearls, vents or fish.** §1.3, §1.6, and the earlier `caesars-comparison.md` findings 20/21/23.
* **No `GiLightSource` migration.** §1.6.
* **No art.** The placeholder squares stay squares; they just become 3 metres instead of 90 pixels.

---

## 3. What replaces `ViewportTest`

`ViewportTest` (104 lines, 9 tests) is deleted by this plan. It has to be, and it is worth being blunt about what it was and was not doing.

**What it was.** A test that `Viewport`'s own arithmetic is internally consistent. Every assertion in it is of the form "this expression, divided by `h`, equals that expression, divided by `h`" — which is true for any function of the shape `f(x)·h`, by algebra, at every aspect ratio and every resolution. It could not have failed. It passed throughout the entire life of the shipped bug, because the bug was never in `Viewport`; it was in the disagreement between `Viewport` and a transform `Viewport` had no knowledge of.

**What replaces it, honestly:**

**(a) `CameraRigTest` — real assertions, real limitation.** `DefaultCamera.updateViewMatrix()` cannot be called from a unit test: it defaults its interpolation factor to `PulseEngine.INSTANCE.data.interpolation` (`Camera.kt:120`, `Extensions.kt:47-55`) and `INSTANCE` is a `lateinit var` with an `internal set` (`ENG/core/PulseEngine.kt:89`). But JOML *is* on our compile classpath — `org/joml/Matrix4f.class` and `org/joml/Vector4f.class` ship inside `pulse-engine-0.13.0.jar` — so the test can build the matrix with the engine's own JOML from a four-line transcription of `Camera.kt:125-131` and assert:

* world `(0, camDepth)` lands at `(W/2, 0)`;
* world `(0, camDepth + 60)` lands at `(W/2, H)` — exactly 60 m of water, at 1200×900, 1920×1080, 2400×1800, 3440×1440 and 3840×2160;
* the same world point lands at the same *fraction of screen height* at all five;
* the horizontal visible extent in metres is `W/H · 60` — so a 21:9 panel shows more water sideways and the same water vertically;
* the new mapping is numerically identical to the old `Viewport.screenX/screenY` for a grid of `(x, depth, camDepth, W, H)` — the migration is defined to be a no-op, and this is the assertion that says so. (Written against a copy of the old formulas inlined in the test, since `Viewport` is gone.)

  **The limitation, stated plainly:** this tests our camera *parameters* against a transcription of the engine's formula. It would not catch the engine changing that formula in 0.14.0. It is strictly better than `ViewportTest` — it tests agreement with something outside our own file — and it is not a substitute for looking.

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

**(d) Capture-based verification — the only thing that catches an aspect bug.** No test in `./gradlew test` can. The migration is only signed off after a human has looked at:

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

## 5. Size, and how to sequence it

**Size.** Roughly **400–500 changed lines across 8 source files and 6 test files**, plus two new source files (~140 lines with docs) and two new test files (~180 lines). `Viewport.kt` (58) is replaced by `Framing.kt` (~35); `ViewportTest.kt` (104) is deleted. `DiveRenderer`'s seven draw methods and `DiveLighting`'s three are rewritten; their colour, intensity and cone code — which is most of both files' 343 and 424 lines — is untouched. Call it **1.5–2 focused days for one person**, of which about a third is the capture verification at three aspect ratios.

**Do it in three stages, and understand that the middle one is atomic.**

* **Stage A — Tasks 1–4. Independently valuable, ships alone.** Task 1 deletes the `Camera` scene entity and closes the reported bug outright, today, with no coordinate change at all. If a booth build is imminent, ship Stage A on its own and stop. Tasks 2–4 add the diagnostics and the pure, tested pieces the flip needs, without changing a pixel.
* **Stage B — Task 5. One commit. It cannot be split.** The moment `mainCamera` stops being identity, *everything* on `main` and `gi_local_scene` must already be in metres and the HUD anchor must already come from `worldPosToScreenPos`, or the frame is garbage. Attempting to land the world and the HUD separately is the single most likely way this migration goes wrong. The de-risking is that `CameraRig` is already proven against the old formulas in Task 3, so the flip's only unknown is whether every call site was converted — which is a compile-and-look problem, not a maths problem.
* **Stage C — Tasks 6–9. Separable cleanups**, each independently revertable: the GI `camScale` compensation, culling via `isInView`, the origin-based draw cleanup that prepares for sprites, and the documentation.

**Recommendation: do the migration, but ship Task 1 first and separately.** The bug is not the reason to migrate — Task 1 fixes the bug in five deleted lines. The reason to migrate is that the placeholder squares are about to become sprites, and every sprite authored in screen pixels is a sprite that has to be re-expressed in metres later, plus a `Viewport` call at every draw site that a designer tweaking a size will have to reason about. Doing this before the art lands is much cheaper than doing it after, and it is the only version of "make real art work easier" that survives contact with a 4K booth panel.

**If time runs short:** Stage A alone leaves the game correct, with a live invariant check that will shout if it ever stops being correct. That is a defensible place to stop.

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

**Files:**
- Create: `src/main/kotlin/render/CameraInvariants.kt`
- Test: `src/test/kotlin/render/CameraInvariantsTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` (dev-only call in `onRender`)

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

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/render/CameraInvariants.kt src/test/kotlin/render/CameraInvariantsTest.kt src/main/kotlin/EnPustTil.kt
git commit -m "feat: dev-mode camera invariants that check the real framebuffer"
```

---

### Task 3: `CameraRig` — the camera parameters, proven against the old transform

Pure and tested. Not wired in.

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

Then assert, over `listOf(1200f to 900f, 1600f to 900f, 1920f to 1080f, 2400f to 1800f, 3440f to 1440f, 3840f to 2160f)`:

- `screenPos(w, h, camDepth, 0f, camDepth) == (w/2, 0)`;
- `screenPos(w, h, camDepth, 0f, camDepth + Viewport.VISIBLE_DEPTH_METRES) == (w/2, h)` — **exactly 60 m of water on every display**;
- a given world point lands at the same fraction of `h` on all of them;
- the visible horizontal extent, `w / CameraRig.pixelsPerMetre(h)`, equals `w/h * 60` — so 21:9 shows more water sideways and the same water vertically;
- **parity with the old transform**: for a grid of `x ∈ {-40, -7.5, 0, 12.25, 40}`, `depth ∈ {0, 33, 94.5, 160}` and `camDepth ∈ {-24, 0, 61.75}`, `screenPos(...)` equals `(w/2 + x*h/60, (depth - camDepth)*h/60)` — the old `Viewport.screenX/screenY`, inlined in the test with a comment saying they are the pre-migration formulas and that the migration is defined to be a no-op.

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
- Delete: `src/test/kotlin/render/ViewportTest.kt`
- Modify: `render/DiveCamera.kt`, `render/DiveRenderer.kt`, `render/DiveLighting.kt`, `render/Hud.kt`, `EnPustTil.kt`, `src/test/kotlin/render/DiveCameraTest.kt`, `src/test/kotlin/AttractScreenTest.kt`
- Modify: `src/main/kotlin/render/CameraInvariants.kt` (enable the depth/aspect rules from Task 2)

**Interfaces:**
- Consumes: `CameraRig`, `no.njoh.pulseengine.core.graphics.api.Camera`
- Produces: `DiveRenderer.render(surface, sim, cam)`, `DiveLighting.render(engine, sim, dt)`, `Hud.render(surface, sim, diverX, diverY, pixelsPerMetre, w, h)`

- [ ] **Step 1: Rename `Viewport` to `Framing` and delete its transform functions**

Move the file, rename the object, delete `pixelsPerMetre`, `screenX`, `screenY`, `depthAt` and `screenFraction`. Rewrite the class doc: it is now "how much water is on screen, and where the diver sits in the frame" — framing, not transforms — with a pointer to `CameraRig` for the transform and a one-line note that the HiDPI story it used to tell is now the engine's problem, solved by `ortho(0, w, h, 0)` being reissued on every window change.

The compiler will now list every call site. That is the point of renaming rather than gutting in place.

- [ ] **Step 2: Delete `ViewportTest`**

Delete `src/test/kotlin/render/ViewportTest.kt`. Read §3 of this plan first and make sure `CameraRigTest` covers the properties worth keeping (they are the "same fraction on every display" ones); the rest were assertions that could not fail.

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
Expected: PASS. Count should be 233 − 9 (`ViewportTest`) + the new `CameraRigTest`/`CameraInvariantsTest`/`DiveRendererTest` cases.

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

## 8. Deliberately out of scope

- Scene entities for the diver, pearls, vents or the anglerfish (§1.3, §1.6)
- `GiLightSource` entities (§1.6) and the `GI_GLOBAL_SCENE` far-field light pass
- `.scn`-authored level content of any kind (design spec §10 as amended; pearl placement stays seeded and procedural)
- Removing the GI reflectance floor by setting `minReflectance = 0` (§1.7 — documented, not done)
- A world-space HUD surface (§2.4 — revisit with the §12 cash-out spectacle)
- Camera zoom, shake or rotation, which the migration makes possible and which the cash-out will want
- `GiOccluder` and normal-mapped lighting on the diver, which the migration also makes possible and which belongs with real art
- Any art at all
