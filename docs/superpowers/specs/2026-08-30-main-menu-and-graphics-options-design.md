# Main menu and graphics options — design

**Status:** approved in brainstorming 2026-08-30, not yet implemented.
**Companion:** `2026-08-30-rendering-performance-design.md` supplies the measured cost of every
knob this menu exposes, and the preset ladder below is *defined* there. Read that spec's §2
before changing a preset value here.

---

## 0. Why this exists, and what changed to allow it

The game boots straight into IDLE (attract mode) because it was built for a **two-day unattended
arcade cabinet**. That target was corrected on 2026-08-30: this build runs on a **standard Mac
with a controller**. A desktop game gets a menu.

Two beliefs recorded in `CLAUDE.md` said a settings menu could not really work, and **both are
wrong**. They were checked against `pulse-engine-0.13.0.jar`'s bytecode during the performance
sweep:

1. `CLAUDE.md:212`, `application.cfg`'s header and `init.pes` all say *"`screenMode` and window
   size cannot be changed at runtime — there's no setter that reaches the window after
   creation"*. **`Window.updateScreenMode(ScreenMode)` is a public interface method.**
   `WindowImpl.updateScreenMode` early-returns on no change, else queues a lambda through
   `runOnInitFrame`; that lambda calls `createWindow()` passing the *previous* handle as GLFW's
   **share** parameter — so the new window inherits the old GL context's objects — then fires
   `resizeCallBack(w, h, windowRecreated = true)`, which reaches `gfx.onWindowChanged` and
   re-inits every surface and re-projects every camera. `init.pes` names this exact mechanism
   one paragraph before denying it exists.

2. Window size: "no setter" is *literally* true — there is no `glfwSetWindowSize` anywhere in
   the jar — but "cannot be changed at runtime" is misleading. The window is created
   `GLFW_RESIZABLE`, the framebuffer-size callback is wired, and `SurfaceImpl.init` reallocates
   every render texture on resize. **A player can already drag the window edge today and the
   whole pipeline follows correctly**, `CameraRig` included.

Consequence: **no setting in this menu needs a restart.** No "apply and restart" screen, no
self-relaunch, no watchdog dependency. That is the single most important fact shaping this
design, and it is why the menu is worth building rather than being a config file with extra
steps.

### Fix the doc lies as part of this work

Those three sentences must be corrected in the same change that relies on them being false,
or the next person will read them and re-derive the wrong conclusion. Also correct
`CLAUDE.md:88` and `render/README.md:369`, which claim `EPT_DEV=1` enables an F3 MetricViewer
overlay — see §6.

---

## 1. Decisions taken in brainstorming

| Question | Decision |
|---|---|
| Boot destination | **A real main menu**, not the attract screen |
| Attract screen | Becomes the *idle fallback*; any press returns to the menu |
| Booth hardening | **Relaxed for desktop, machinery kept.** Plain QUIT item; RUN_OVER returns to the menu. The exit-hold, idle timeouts and initials auto-submit stay in the code and stay tested. |
| Options depth | ~~Presets plus a few key knobs. GI internals sit behind the preset.~~ **REVERSED 2026-08-31** (`.superpowers/sdd/2026-08-30-main-menu-and-graphics-options/gi-knobs-brief.md`): the owner asked to expose the six live GI knobs directly (LIGHT MAP SCALE, SCENE SCALE, GLOBAL SCALE, MAX CASCADES, RAY QUALITY, OFF-SCREEN RAYS). Touching any one of them sets `quality = "CUSTOM"`; selecting a named preset still writes all six at once. `mergeCascades` stays hidden — turning it off breaks lighting outright, not merely cheaply — and GI on/off is still excluded (it deletes surfaces `DiveLighting` draws into). |
| FPS readout | **Yes** — a built-in counter, toggleable from the menu |

### What "keep the machinery" means concretely

`RunLifecycle`'s `EXIT_HOLD_SECONDS` two-button hold, `IDLE_TIMEOUT_SECONDS`,
`PAUSE_IDLE_TIMEOUT_SECONDS`, `INITIALS_IDLE_TIMEOUT_SECONDS` and the initials auto-submit are
**not deleted and not weakened**. Their tests stay green. They simply stop being the only way
out, because the menu now has QUIT. A future booth build re-enables the old behaviour by
choosing a different boot state and hiding one menu item — not by rewriting a state machine.

---

## 2. Architecture

Three ways to fit a menu into a lifecycle that is pure, engine-free and heavily tested:

- **A. New `MAIN_MENU` state in `RunLifecycle`; menu *contents* in a separate pure model.**
- B. A parallel `MenuFlow` machine outside `RunLifecycle`, with `EnPustTil` gating between them.
- C. Extend the existing PAUSED-from-IDLE "CABINET MENU".

**A is chosen.** The codebase already has the precedent and it is exact: `ENTER_INITIALS` is a
lifecycle *state*, while the letter-cycling inside it lives in `InitialsEntry` — a separate,
pure, engine-free class that does its own edge detection. A menu page with a selection index is
the same shape. B invents a second state machine and creates a standing question about which one
owns input this frame. C is the smallest diff but does not boot into a menu, which is the
requirement.

### New files

| File | Purpose | Engine imports |
|---|---|---|
| `render/MenuModel.kt` | Pages, items, selection, edge-triggered navigation | **none** |
| `render/MenuLayout.kt` | Layout constants, fractions of screen height | **none** |
| `settings/GameSettings.kt` | The settings data class, defaults, clamping | **none** |
| `settings/SettingsStore.kt` | Load/save `settings.json` | file IO only |
| `render/GraphicsApplier.kt` | Maps `GameSettings` → engine calls | **yes — the only one** |
| `render/FrameProbe.kt` | FPS/frame-time sampling `Service` | yes |

The engine-free/engine-facing split is the same one the project already enforces: anything worth
asserting is pulled into a pure object so it can be tested without a GL context (`Framing`,
`CameraRig`, `RunLifecycle`, `ControlHints`, `AttractLayout`, `ScreenText`).

**`GraphicsApplier` is deliberately the single choke point for graphics state.** Every trap the
performance sweep found lives there with its evidence, rather than being scattered across call
sites. If a future change needs to touch a GI property, it goes through this file.

---

## 3. `MenuModel` — pure navigation

### Shape

```
enum class MenuPage { ROOT, GRAPHICS }

class MenuModel
{
    var page: MenuPage           private set
    var selectedIndex: Int       private set

    fun itemsOn(page: MenuPage): List<MenuItem>
    fun update(up: Boolean, down: Boolean, left: Boolean, right: Boolean,
               confirm: Boolean, back: Boolean): MenuAction
}
```

`update` takes **level readings** and does its own previous-frame edge detection, exactly as
`InitialsEntry` and `RunLifecycle` do. This is not optional and the reason is recorded in
`RunLifecycle`'s class doc: the engine's `Gamepad` exposes only `isPressed`/`getAxis` with **no
`wasClicked`**, so a held stick reads true every frame. A menu without edge detection scrolls
the entire list in one frame.

`update` returns a `MenuAction` — a sealed result the caller acts on
(`StartDive`, `Quit`, `Back`, `SettingChanged(field, newValue)`, `None`) — rather than mutating
game state itself. That keeps the model pure and makes every transition assertable.

### Items

`ROOT`: `START DIVE`, `GRAPHICS`, `LEADERBOARD`, `QUIT`.
`GRAPHICS` (14 rows since the "Options depth" reversal above): `QUALITY`, `LIGHT MAP SCALE`,
`SCENE SCALE`, `GLOBAL SCALE`, `MAX CASCADES`, `RAY QUALITY`, `OFF-SCREEN RAYS`, `RESOLUTION`,
`RENDER SCALE`, `FULLSCREEN`, `FRAME CAP`, `VSYNC`, `SHOW FPS`, `BACK`. The six GI knobs sit
right after `QUALITY` — the preset row they belong to and the one that writes all six at once —
rather than at the end beside the unrelated window/display rows.

A `MenuItem` is either an **action** (confirm fires it) or a **cycler** (left/right steps through
a fixed list of values). No free text, no sliders — a cycler over a declared value list is what a
D-pad and a stick can both drive, and it makes every reachable state enumerable in a test.

### Wrapping

Selection wraps top-to-bottom (an arcade convention the project already uses: `InitialsEntry`
wraps A↔Z). Value cyclers **clamp** rather than wrap, so holding right cannot silently take
RESOLUTION from the largest value back to the smallest.

---

## 4. Settings

### `GameSettings`

```
data class GameSettings(
    val quality: Quality,            // LOW, MEDIUM, HIGH
    val windowWidth: Int,            // logical points
    val windowHeight: Int,
    val renderScale: Float,          // 0.5 .. 1.0, main surface texture scale
    val fullscreen: Boolean,
    val frameCap: Int,               // 0 = uncapped, else 30/60/120/144
    val vsync: Boolean,
    val showFps: Boolean
)
```

Every field is clamped on construction *and* on load. `GameSettings` owns its own validity —
`SettingsStore` never hands out an out-of-range value, and `GraphicsApplier` never has to
defend against one.

### Two resolution knobs, on purpose

`RESOLUTION` changes the **window size in points**. `RENDER SCALE` changes
`mainSurface.setTextureScale`, i.e. how many pixels are actually shaded before being presented
at window size.

Render scale is the higher-value knob and belongs in the menu even though it was not asked for,
because of the measured result in the companion spec: **frame time fits `13 ms + 10.5 ms per
megapixel`**, and this Mac's 1600×900 window is a **3200×1800** framebuffer. Render scale
attacks the dominant term directly and needs no engine plumbing at all. Resolution is offered
because it was asked for, and because a smaller window is the one thing that reduces both terms.

**Fullscreen is not merely cosmetic here.** Measured: fullscreen at 1920×1200 ran **1.74×
faster** than windowed at 2048×1152 — essentially the same pixel count. The likely cause is
macOS compositor bypass. The menu should default `fullscreen = true`.

### Persistence

The engine's `Configuration` has **no `save()`** — verified across the whole jar; it declares
`load`, the typed getters, and nothing that writes. And `application.cfg` ships *inside* the
jar/`.app`, where it cannot be written.

Settings therefore go to **`~/EnPustTil/settings.json`**, beside `scoreboard.json`, written
through the **existing** `AtomicFileSwap.promoteAtomically` path that `ScoreStore` already uses.
That directory is `engine.config.saveDirectory`, it already exists, and its write path already
survives a power cut. Do not invent a second I/O route and do not write a `.cfg` — a
`Properties` file would inherit `application.cfg`'s type-coercion trap, where a value above
`Int.MAX_VALUE` throws mid-parse and silently drops a hash-ordered subset of *other* keys.

**Save policy:** debounce. Write on leaving the GRAPHICS page and on `onDestroy`, not on every
cycler step — holding right on FRAME CAP must not produce a burst of file writes.

**Corrupt or missing file → defaults, plus one WARN.** Same contract as the scoreboard. A
settings file must never be able to stop the game booting.

### Startup ordering

`ConfigurationInternal.init()` runs in `initEngine` **before** `window.init(config)` reads
`windowWidth`/`windowHeight`/`screenMode`. So the saved window size can be applied at launch
with no restart dance, by constructing the engine explicitly rather than through the inline
`PulseEngine.run<T>()` helper:

```kotlin
PulseEngineImpl(config = UserConfig(), window = ResizableWindow()).run(EnPustTil())
```

`PulseEngineImpl`'s constructor is public with defaults, and `WindowImpl` is verified `open`
with an `open initFrame`. `UserConfig` overrides `init()` to call `super.init()` and then
`load(File(saveDirectory, "settings.cfg").absolutePath)` — **absolute**, because
`Extensions.loadTextFromDisk` only reads a real file when `it.isFile() && it.isAbsolute()` and
otherwise silently falls back to the classpath.

**Simpler alternative, and the one to try first:** skip `UserConfig` entirely, let the window
open at `application.cfg`'s size, and apply the saved size in `onCreate` via the same pending-
resize hook the menu uses. One code path instead of two, at the cost of a visible resize on the
first frame. Take the two-path version only if that flash is objectionable.

---

## 5. `GraphicsApplier` — the only file that touches graphics state

### Why everything can be applied live

`GlobalIlluminationSystem.onUpdate` runs **every frame** and unconditionally re-pushes seven
texture scales, plus `jitterFix`, `globalWorldScale`, `upscaleSmallSources` and `minReflectance`.
`SurfaceImpl.setTextureScale` is a no-op if unchanged, else updates every `TextureDescriptor` and
queues `renderTarget.init(...)` through `runOnInitFrame`, executed at the top of the next frame.
Every other GI property is read fresh inside `applyEffect` each frame and pushed as a uniform.
**Nothing is latched at init.**

### The traps, which is why this file exists

1. **`lightTexScale` is a step function, not a curve.** `lightTextureSizeFunc` rounds the scaled
   framebuffer *up* to a multiple of `2^cascadeCount`, and `cascadeCount` derives from the
   rounded diagonal. The current 0.5 is *worse than the engine's 0.4 default*: at 1080p it tips
   the count 6→7 **and** rounds 540→640, costing +122%. Any value chosen here must be evaluated
   through the formula, never by assuming area scaling.
2. **`traceWorldRays = false` does not remove the global chain.** `gi_global_scene` and
   `gi_global_sdf` and their 13 jump-flood/SDF passes are created unconditionally in `onCreate`
   and their post-processing runs regardless; the flag only gates a shader branch. To reclaim
   those passes you must also drop `globalSceneTexScale`. Set both together or neither.
3. **`gi_light_final` and `gi_normal_map` are not reachable via any GI property**, but a direct
   `setTextureScale(0.5f)` on them *sticks*, because `onUpdate` does not overwrite those two.
4. **`mergeCascades` must never be exposed.** Turning it off collapses lighting to the finest
   interval.

### Presets

Defined in the companion spec §2 and imported here as named constants. Summary:

| Preset | Change from today |
|---|---|
| HIGH | today's values |
| MEDIUM | `globalSceneTexScale` down, `traceWorldRays` off, `maxCascades` 6, `gi_light_final`/`gi_normal_map` at 0.5, HUD multisampling down |
| LOW | MEDIUM plus `lightTexScale` 0.4, `localSceneTexScale` 0.4, `bilinearFix` off, bloom removed |

The exact values are settled by the companion spec's per-change verification, not here — several
of them (HUD multisampling, the halved GI surfaces, bloom) change what the frame *looks* like and
are only confirmed by a screen grab. Treat this table as the shape of the ladder; import the
numbers.

**`GI off` is deliberately NOT a preset.** `DiveLighting` draws through
`gfx.getSurface(GI_LOCAL_SCENE)?.getRenderer(GiSceneRenderer)`, and disabling the system
`deleteSurface`s all nine GI surfaces — so every call site needs a verified null path first.
Separately, losing the multiply composite makes the abyss read flat-bright, which may be
unacceptable regardless of frame rate. Revisit only after LOW is shipped and measured.

### VSync

There is no engine API. `WindowImpl.createWindow` calls `GLFW.glfwSwapInterval(0)` — the only
such call in the jar, taking no config value. Toggling vsync means calling
`GLFW.glfwSwapInterval(n)` yourself **on the main thread with the context current**, which the
`ResizableWindow.initFrame` hook already provides.

**It must be re-applied after every `updateScreenMode`**, because `createWindow()` resets it to
0. That is a real bug waiting to happen: toggle fullscreen and vsync silently turns itself off.
`GraphicsApplier` owns re-asserting it.

Pair vsync with `frameCap = 0`, or the limiter and the swap interval fight each other.

---

## 6. FPS readout

`engine.data` already exposes `currentFps`, `totalFrameTimeMs`, `cpuUpdateTimeMs`,
`cpuFixedUpdateTimeMs`, `cpuRenderTimeMs`. `FrameProbe` is a small `Service` that samples
`totalFrameTimeMs` in `onRender` and keeps a rolling p50/p95.

Two engine facts to encode rather than rediscover:

- **A `Service` must call `start()` on itself or it never runs.** `Service.isRunning` defaults
  false and `ServiceManagerImpl.update/fixedUpdate/render` all skip services where
  `!isRunning()`. This is exactly why the existing F3 overlay has never worked:
  `EnPustTil.kt:1508` does `engine.service.add(MetricViewer())` with no `.start()`. Fix that in
  the same change, and correct `CLAUDE.md:88` and `render/README.md:369`, which both claim the
  overlay works.
- **`data.gpuRenderTimeMs` is CPU wall time**, measured around `gfx.drawFrame` +
  `swapBuffers` — not a GPU timer. Do not label it "GPU" on screen. GL timer queries return
  `0 ns` for every scope on Apple Silicon, so there is no real GPU time available to show.

Draw the readout on the **`hud` surface** (screen space, its own camera). It must not go on
`mainSurface`, which `GlobalIlluminationSystem` multiplies by the light map — the counter would
be invisible in the abyss.

---

## 7. Lifecycle changes

`RunLifecycleState` gains `MAIN_MENU`.

| Transition | Trigger |
|---|---|
| boot → `MAIN_MENU` | initial state |
| `MAIN_MENU` → `BRIEFING` | `StartDive` |
| `MAIN_MENU` → `IDLE` | no input for `MENU_IDLE_TIMEOUT_SECONDS` |
| `IDLE` → `MAIN_MENU` | any press |
| `RUN_OVER` → `MAIN_MENU` | after dwell, when the score is not worth recording |
| `ENTER_INITIALS` → `MAIN_MENU` | on completion or auto-submit |

**BRIEFING is still entered only from a cold start, never from a retry** — that rule exists
today (a retry from RUN_OVER goes straight back to the water) and this change must not
accidentally route retries through the menu.

The GRAPHICS page is a page of `MenuModel`, **not** a lifecycle state. Nothing about the
lifecycle should know that graphics settings exist.

---

## 8. Drawing

`MenuLayout` follows `AttractLayout`/`PauseLayout` exactly: constants are **fractions of screen
height**, never width and never pixels, because `engine.window.width/height` are physical
framebuffer pixels and the display's aspect ratio is unknown. Vertical anchors are the **top** of
a text box and text grows downward.

Prefer `surface.config.width/height` over `engine.window.*` — same number today, but `config` is
what that surface's projection was built from and so cannot disagree with what is being rendered.

**Every drawn string goes through `ScreenText`.** The default font can only draw
U+0020..U+011F, and anything above renders as *nothing at all* — no glyph, no x-advance,
silently. `AttractScreenTest` already asserts every `ScreenText` entry is drawable; adding the
menu's strings there is what stops an em dash or a curly quote reaching the screen. Use
`ScreenText.SEPARATOR` (a middle dot) where an em dash is wanted.

Solid rectangles go through `render/Draw.kt` (`fillRect`/`fillRectCentred`), never `drawQuad` —
which renders nothing at all on macOS, silently. `DrawTest` fails the build if a `drawQuad` call
returns.

No per-frame allocation on the draw path. The menu is drawn every frame it is open, and it is
open for as long as somebody leaves it open — the same reasoning that already forbids
constructing a `Color` in `drawLeaderboard`. Cache composed hint strings in fields, as
`hintPauseResume`/`hintMenuResume`/`hintExitHold` already are.

---

## 9. Input

Reuse what exists rather than adding a second input path:

- Steering/navigation through **`render/PadAxis.kt`** — analog → D-pad → keyboard, first live
  source wins, both-directions-at-once cancelled to zero so a stuck contact cannot pin the
  selection.
- Gamepad reads through **`render/MappedPads.kt`** and nothing else. `MappedPadsTest` fails the
  build if `.isPressed(`/`.getAxis(` appears on any receiver but `mappedPads` or
  `engine.input`. Never `pad.isPressed` — the engine reads the device's raw HID order and
  indexes it by SDL codes, so every name in `GamepadButton` is fiction on a real pad.
- **Lifecycle input scans every connected gamepad** (`anyLifecycleActionPressed`), because index
  0 is not guaranteed to be the player's pad. Menu navigation follows the same rule.
- Confirm/back reuse the existing configured buttons; the menu prints the pad's own legend via
  `ControlHints`/`ControllerFamily`, which already say `CROSS` on a DualSense and `A` on a
  generic pad.

---

## 10. Testing

Tests assert **relationships**, not restatements of constants — a test that cannot fail is worse
than no test, and commit `4493eeb` deleted several for that reason.

| Test | Asserts |
|---|---|
| `MenuModelTest` | Wrapping; **a held direction moves the selection exactly once**; cyclers clamp at both ends; every `MenuAction` is reachable; `back` from ROOT is a no-op, not a crash |
| `GameSettingsTest` | Clamping at both bounds; JSON round-trip is identity |
| `SettingsStoreTest` | Corrupt file → defaults + no throw; missing file → defaults; write is atomic (no partial file observable) |
| `GraphicsApplierTest` | **The preset ladder is monotonically cheaper through the engine's own `lightTextureSizeFunc`** — not through assumed area scaling. This is the test that earns its keep: it is the only thing stopping a future "Low" preset from tipping the cascade count and costing more than Medium. |
| `MenuLayoutTest` | No two elements overlap at any aspect ratio from 4:3 to 32:9; the longest item label still fits |
| `AttractScreenTest` (extend) | Every new `ScreenText` entry is drawable by the default font |
| `RunLifecycleTest` (extend) | Boot state is `MAIN_MENU`; `RUN_OVER` → `MAIN_MENU`; **the existing exit-hold, idle-timeout and auto-submit cases still pass unchanged** |

The last row is the regression guard for "keep the machinery": if relaxing for desktop breaks a
booth-hardening test, that is a design violation, not a test to update.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| `updateScreenMode` recreates the GL context's window; assets or surfaces could be lost | It shares the context (`prevWindowHandle` as GLFW's share param) and the engine re-inits every surface. **Verify by toggling fullscreen mid-run and confirming the diver's sprite sheets still draw** — `DiverSprite.sheetsReady()` degrading to a placeholder rectangle would be the visible symptom. |
| VSync silently lost on fullscreen toggle | `GraphicsApplier` re-asserts swap interval after every mode change; test it explicitly |
| A resolution change mid-run disturbs the camera or the sim | `CameraRig` reads `mainSurface.config.width/height` every fixed tick and `Framing` is resolution-independent by construction. Confirm with `CameraInvariants` under `EPT_DEV`. |
| Menu strings silently invisible | `ScreenText` + `AttractScreenTest` |
| Settings file blocks boot | Defaults on any failure; never throw out of `SettingsStore.load` |
| `MSAA16 → NONE` on the HUD changes text appearance | Compare a real screen grab before/after; the world keeps MSAA4 |

---

## 12. Out of scope

- Audio options — the project ships no audio.
- Control remapping in the menu — `application.cfg`'s button map already does this, and a
  remapping UI is a separate piece of work.
- Localisation. The title is English (`ScreenText.TITLE` is `"ONE MORE BREATH"`) but every
  identifier and the config's `gameName` remain `EnPustTil`, deliberately, so every capture and
  recovery procedure keeps working. **`GAME_NAME` and `application.cfg`'s `gameName` must move
  in lockstep if ever renamed** — `configFileHealthWarning` compares them at startup.
- Turning GI off (§5).
