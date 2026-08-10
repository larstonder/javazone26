# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Én Pust Til** — a free-diving arcade game for the Capra booth at JavaZone 2026 (2–3 September, NOVA Spektrum). Kotlin 2.2.20 on [Pulse Engine](https://github.com/NiklasJohansen/PulseEngine) 0.13.0, shipped as a Windows `.exe` with a bundled JRE.

The design is **locked**: `docs/superpowers/specs/2026-08-04-en-pust-til-design.md` is the single source of truth for game rules, economy, controls and presentation. Read the relevant section before changing behaviour; §17 has the amendment log and the platform findings that constrain implementation. `docs/superpowers/plans/2026-08-04-en-pust-til-gameloop.md` is the implementation plan, and `.superpowers/sdd/2026-08-04-en-pust-til-gameloop/` holds the per-task reports with the empirical evidence behind many of the odd-looking decisions in the code.

The target is a **two-day unattended arcade cabinet in front of a queue**. That drives most of the non-obvious engineering: the cabinet must recover to attract mode on its own, must never lose scores, and must not depend on a keyboard or a console being attached.

## Commands

```bash
./gradlew run                 # run the game (windowed dev mode — see config split below)
./gradlew test                # all tests
./gradlew test --tests "dive.DiveSimTest"                    # one test class
./gradlew test --tests "dive.DiveSimTest.*surfac*"           # one test (backtick names: match the string)
./gradlew build               # compile + test
./gradlew buildWin64Release   # Windows .exe + bundled JRE -> release/win64/en-pust-til-1.0.zip
python3 tools/build_spritesheet.py    # re-bake the diver sprite sheets from assets/ (gitignored)
cd tools && python3 -m pytest         # the bake script's tests
```

`run` sets `-XstartOnFirstThread` on macOS automatically (LWJGL/GLFW needs the window on the process's first thread).

### Seeing it actually run

Tests passing is not evidence the game looks right — a lot of the bugs in this project's history were invisible-in-tests rendering faults. To capture a frame:

```bash
caffeinate -d -u -t 900 &                      # macOS: stop the display sleeping mid-capture
EPT_SCREENSHOT=/tmp/shot.png ./gradlew run     # writes /tmp/shot-0.png (world) and /tmp/shot-hud-0.png
pkill -9 -f EnPustTilKt                        # the game has no quit key in booth mode
```

`render/ScreenshotEffect.kt` is a debug tool, not a feature: it captures at frame 180 by default, and it derives its filename via `outputPath.replace(".png", "-$index.png")` — so if `EPT_SCREENSHOT` has no `.png` in it, the file is written with no extension at all.

`EPT_DEV=1` enables the `MetricViewer` overlay (F3), forces `logLevel = DEBUG` (works even against a built release `.exe`), and draws the gamepad diagnostic overlay. F1 opens the engine console; `winner` there draws the end-of-day raffle from today's board.

## Architecture

Four packages, and the boundaries between them are the important part.

**`dive/` — the simulation.** Pure Kotlin, zero engine imports, ticked at a fixed 60 Hz. `DiveSim` holds all run state and its `tick(dt, DiveInput)` is the whole game. `Tuning` holds every tunable constant and no logic. `Zone` is the depth bands (value, mass, air burn). `Buoyancy` is the movement model — an empty diver is *neutrally buoyant and hovers*; carried mass is what makes you sink and what makes you sluggish, which is the design's central mechanic (§4), not a rendering detail.

**`render/` — the engine shell.** `EnPustTil.kt` is the only `PulseEngineGame`: it reads input, gates the sim on lifecycle state, and draws. `RunLifecycle` is the IDLE → PLAYING → RUN_OVER → ENTER_INITIALS state machine — pure and engine-free, so the booth's whole unattended-recovery behaviour is unit-tested. `DiveRenderer` draws the world to `mainSurface`, `DiveLighting` issues immediate-mode `drawLight` calls, `Hud` draws to a separate `"hud"` surface, `DiveCamera` eases toward the diver, and `DiverSprite` owns the diver's art — the two baked sprite sheets, the animation loop and the body's size and rotation.

**The diver is the only loaded asset.** Everything else is still `Texture.BLANK` through `Draw.kt`. `DiverSprite` declares both sheets, `EnPustTil.onCreate` queues them with `engine.asset.load`, and the upload is ASYNCHRONOUS — `SpriteSheet.textures` is populated in `onUploaded`, several frames later, and `getTexture` before that throws rather than returning null. `DiverSprite.sheetsReady()` is the gate, and it doubles as the crash guard: it tests `size >= FRAME_COUNT`, which is 0 before the upload AND 0 if the constructor's argument order was got wrong, so both degrade to a placeholder rectangle plus one WARN instead of an exception in front of a queue.

**One heading, two renderers.** The diver's body is drawn rotated to `DiveLighting.beamHeadingDegrees` — the torch's smoothed aim — so he faces where his light points. That heading is integrated ONCE, in `DiveLighting.updateAim` on the fixed tick, because `DiveRenderer` runs before `DiveLighting` inside `onRender` and a body reading it there would be a frame behind the beam. The two angle conventions are not the same number: `GiSceneRenderer` measures counter-clockwise from +x with +y UP, the sprite's rest orientation is head-up, and the offset between them (`DiverSprite.bodyAngleFor`) was derived from `texture.vert`'s own rotation matrix and then confirmed by capture.

**Two coordinate spaces, and which surface you are on decides which one you are in.**

| Surface | Camera | Space |
|---|---|---|
| `main`, and GI's `gi_local_scene` / `gi_normal_map` | `engine.gfx.mainCamera` | **world metres**, +x right, +y down, world y *is* depth |
| `"hud"` | its own identity `DefaultCamera` (created by passing `camera = null`) | **screen pixels** |

`render/CameraRig.kt` is the **only** writer of `engine.gfx.mainCamera` — `MainCameraOwnershipTest` enforces that by source scan, because a second writer is precisely the bug `6ea1f53` fixed. `render/Framing.kt` (which used to be `Viewport`) holds the framing constants and **owns no transform at all**: there is no `screenX`/`screenY`/`depthAt` any more, and `ViewportTest` was dissolved rather than ported — see `FramingTest`'s class doc for which of its nine cases survived and why the other six did not. `DiveRenderer` and `DiveLighting` therefore contain no coordinate maths; they hand the engine metres and ask the camera (`isInView`, `topLeftWorldPosition`) what is on screen. `Hud` is handed a screen position and a pixels-per-metre scale and stays pure screen space.

**The invariant worth remembering:** exactly `Framing.VISIBLE_DEPTH_METRES` of water is visible vertically on **every** display, and a wider display shows more water *sideways* — never less water *down*. How far ahead you can see is how far ahead you can plan, so that is a gameplay constant, which is why the camera is `CameraRig`'s fixed `H / VISIBLE_DEPTH_METRES` scale and not the engine `Camera` entity's `min(W/vpW, H/vpH)` contain fit. `CameraInvariants` checks it on the real framebuffer once a second under `EPT_DEV` and warns if it ever stops holding.

**Solid rectangles go through `render/Draw.kt`.** `fillRect(x, y, w, h)` for a top-left rect, `fillRectCentred(x, y, w, h, angle)` for anything whose position means a *middle* — every object in the world, and the HUD's bubbles and depth-tape markers. The centred form exists because the art is normal-mapped sprite sheets, and a normal-mapped sprite is the **same world rect submitted to two surfaces** (albedo on `main`, normal on `gi_normal_map`); `drawTexture`, `NormalMapRenderer.drawNormalMap` and `GiSceneRenderer.drawLight` all take the identical `(x, y, w, h, angle)` + centre-origin tuple, so that second draw must be a copied argument list and never a second derivation. `DiveRenderer.drawDiver` is the one place that does it, and the second call's arguments are literally copied from the first. Do **not** reach for the engine's `NormalMapped` interface: `NormalMapped.kt:32` hands the renderer the whole asset, which for a `SpriteSheet` stretches all 42 cells across the quad with no way to name a frame.

**`score/` — persistence.** `ScoreRepository` is registered as an engine `Service` so it gets `onCreate` (load) / `onDestroy` (final synchronous save) from the engine lifecycle. Writes go to a temp file then `promoteAtomically` (`AtomicFileSwap`), with periodic timestamped backups. `InitialsEntry` is the three-letter arcade entry; `Leaderboard` ranks and decides what's worth recording.

**Presentation state must never leak into `dive/`.** `DiveSim` simulates one run and nothing else; attract mode, dwell timers and initials entry live in `RunLifecycle`.

### The pure-logic-extracted-for-testing pattern

Anything worth asserting is pulled out into an engine-free object so it can be tested without a GL context: `Framing`, `CameraRig`, `CameraInvariants`, `DepthBlend`, `AimAngle`, `RunLifecycle`, `AttractLayout` and `ScreenText` (both in `EnPustTil.kt`), `parseDailySeed`, `textOutlineOffset`, `DiveRenderer.zone*At` / `stripCount` / `stripCentreDepth`. When you add rendering or layout logic, extract the *relationship between the numbers* the same way rather than leaving it inline in a draw call. `DiveSim` exposes `internal fun debug*` hooks for the same reason.

### Frame ordering is load-bearing

Lights are drawn from `onRender`, in the same call that drives `DiveRenderer`. Keep them there: `GiSceneRenderer`'s batch has to be filled before `gfx.drawFrame`, and `onRender` is where the rest of the drawing lives.

**Light-versus-geometry alignment is now structural rather than procedural.** GI's local scene surface is created with `camera = engine.gfx.mainCamera` — the same object `mainSurface` uses — and every matrix is built once per frame in `gfx.initFrame` before any game code runs. A light at world `(x, depth)` and a square at world `(x, depth)` therefore go through the identical matrix and *cannot* drift, whichever callback issued them. You could not reproduce the old one-frame bug on purpose.

**The remaining drift risk is the HUD**, which is on its own screen-space camera and so has to transform the diver's position by hand. It must take that anchor from `mainCamera.worldPosToScreenPos` in `onRender` — the same matrix the world is being drawn with this frame — and never from `DiveCamera.depth`, which is camera state from a different point in the frame and would put the air ring one frame ahead of the diver.

**Camera easing runs on the FIXED TICK, not the render clock.** An earlier version of this file described render-clock easing as deliberate; that became actively wrong when `CameraRig` took over `engine.gfx.mainCamera`. `updateViewMatrix` interpolates every camera parameter from a snapshot taken at the top of each fixed step, so writing the camera from the render clock pairs values that were never consecutive fixed states and makes the interpolator judder. Nothing is lost: `DiveCamera`'s easing is `1 − e^(−k·dt)` and already frame-rate independent, and the engine's interpolator then produces *smoother* motion above 60 fps than a render-clock write did. `CameraRig.snap` (not `apply`) is what `DiveCamera.snapTo` pairs with, because that snapshot starts at the identity and frame 1 is otherwise drawn with it — see `CameraRig`'s class doc for the measurement.

Inside `DiveSim.tick`, the order of surfacing / air burn / pickup / anglerfish is deliberate and each ordering constraint is commented where it lives. Don't reorder without reading those comments.

## Platform constraints — verified, not guessed

These were each established empirically (several by decompiling `pulse-engine-0.13.0.jar`) after a bug that produced no error message at all. Violating one fails silently.

- **`Surface.drawQuad()` and `drawLine()` render nothing on macOS/Apple Silicon** — no GL error, no log. The cause is *not* the shader `#version`, which an earlier version of this file claimed: `QuadRenderer.kt:37` and `LineRenderer.kt:34` declare the colour vertex attribute as `GL_FLOAT` (so it binds through `glVertexAttribPointer`) while `quad.vert`/`line.vert` declare it `in uint`. Feeding an integer vertex input through the float path is undefined behaviour; the shader derives alpha as `(rgba & 255u)/255.0`, so the undefined value yields **alpha 0** and the geometry rasterises fully transparent. `TextureRenderer`/`TextRenderer` bind the same attribute as `GL_UNSIGNED_INT` and work. Believed macOS-only — Windows drivers pass the 32 bits through — so it reaches no players. We shadow the two shaders from `src/main/resources/pulseengine/shaders/renderers/` for local development and the scene editor, and the release jar **deliberately keeps the engine's broken copies** (see `build.gradle.kts`). So game code must still use `Surface.fillRect()` / `fillRectCentred()` from `render/Draw.kt`: folding either into `drawQuad` would look correct on a Mac and fail silently at the booth. `DrawTest.no production source draws a quad or a line` fails the build if a call to `drawQuad`/`drawLine` (or their `*Vertex` forms) ever returns, and `fillRectCentred` offers the rotation that is the usual reason to reach for `drawQuad` in the first place. Full investigation in `docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md`.
- **`engine.window.width/height` are PHYSICAL framebuffer pixels**, not the logical size in `application.cfg` (2400×1800 on a Retina Mac from a declared 1200×900). Express sizes as a fraction of screen **height**, never width and never a pixel count — the booth display's aspect ratio is not known in advance. **The world no longer consumes this at all**: sizes there are metres (`render/Framing.kt`), and only `CameraRig` turns a metre into a pixel. Screen-space code — the HUD, the attract screens — should prefer `surface.config.width/height` over `engine.window.*`: they are the same number today, but `config` is what that surface's own projection was built from, so it is the only value that cannot disagree with what is actually being rendered. Reading the window instead is one half of the mechanism that shipped in `6ea1f53`'s bug.
- **The default font can only draw U+0020..U+011F.** Anything above that renders as *nothing at all* — no glyph and no x-advance, silently. Em dashes, en dashes, curly quotes, ellipses and bullets all vanish; Norwegian æøå are fine. Every drawn string goes through `ScreenText`, and `AttractScreenTest` asserts all of them are drawable, so a smart-quote substitution fails the build instead of reaching the booth. Use `ScreenText.SEPARATOR` (a middle dot) where you want an em dash.
- **A generic arcade USB encoder may have no SDL gamepad mapping**, in which case it is completely invisible to `engine.input.gamepads` while working fine at the OS level. `logGamepadDiagnostics` goes to the raw GLFW joystick API to tell "nothing plugged in" apart from "plugged in but unmapped", and logs the latter at WARN so it survives the booth's default log level.
- **The engine's `Gamepad` exposes only `isPressed`/`getAxis`** — there is no `wasClicked` for a controller button. Everything consuming lifecycle input takes level readings and does its own previous-frame edge detection (`RunLifecycle`, `InitialsEntry`). A stuck button on a booth encoder must never be able to restart the game repeatedly or blast through the alphabet.
- **Gameplay input reads gamepad 0; lifecycle input scans every connected gamepad** (`anyLifecycleActionPressed`). Index 0 is not guaranteed to be the cabinet's stick, and "any button to start" has to mean any.
- **The HUD lives on its own surface**, not `mainSurface`. `GlobalIlluminationSystem` multiplies `mainSurface` by the light map — which is what makes the Abyss dark, and would also multiply the HUD into near-invisibility. Its `zOrder` is pinned explicitly (`HUD_Z_ORDER`), because the engine otherwise assigns one by surface creation order.
- **`SpriteSheet`'s constructor takes `(…, format, maxMipLevels, hCells, vCells)`** — the argument order is *not* the field declaration order, which reads `horizontalCells, verticalCells` first. Passing `(…, cols, rows, 0)` sets `hCells = rows` and `vCells = 0`; the constructor accepts that combination without complaint and silently builds a zero-length `Texture[]` — the misconfiguration itself raises nothing. It only surfaces two stages later, as a thrown `ArrayIndexOutOfBoundsException` from `getTexture(0)` the first time a frame is drawn, by which point the call site that actually got it wrong is off the stack. Verified from bytecode: the 6th int is forwarded to `Texture.<init>`'s trailing `maxMipLevels`, and the synthetic defaults constructor defaults that slot to `5`, pairing with the `LINEAR_MIPMAP` filter default.
- **`maxMipLevels = 0` allocates no texture storage at all.** `TextureArray` computes `mipLevels = min(maxMipLevels, floor(log2(size)) + 1)` with no `coerceAtLeast(1)` and hands it to `glTexStorage3D` as `levels`; `levels = 0` is `GL_INVALID_VALUE`, so nothing is allocated and every later `glTexSubImage3D` fails too — no exception, no log. `1` is the value that means "one level, no mips". `tools/build_spritesheet.py` prints the correct constructor call for exactly this reason.

## Config and release

`application.cfg` is the **booth default** and ships in the release `.exe`: fullscreen, no pinned window size (takes the display's native resolution), `logLevel = WARN`. `application-dev.cfg` is loaded automatically on top of it by the engine and restores windowed + DEBUG for local `./gradlew run`; it is excluded from the release by the `exclude("*-dev*")` line in `build.gradle.kts`. `screenMode` and window size **cannot** be changed at runtime — there's no setter that reaches the window after creation — which is why this split exists rather than an env var.

**Day two at the booth:** uncomment and change `dailySeed` in `application.cfg` and restart. That regenerates the water column and, because every `ScoreEntry` stores the seed it was earned under, gives day two a fresh leaderboard while day one's board stays intact in `scoreboard.json`.

**Sprite art.** `assets/` (183 MB of 1000×2000 source frames) and `release/` are gitignored; the *baked* sheets in `src/main/resources/sprites/` are committed and ship in the `.exe`. A clean clone can build and run but cannot re-bake without the source frames. The bake is byte-reproducible, so re-running it with unchanged inputs produces no diff. `docs/superpowers/specs/2026-08-07-diver-spritesheet-bake-design.md` is their specification; `DiverSprite` copies its grid numbers, and `DiverSpriteTest` re-derives them from the committed PNG's IHDR so a re-bake at a different `--frame-height` cannot leave them stale.

**`diver-normal.png` has a HYPHEN and must keep it.** The engine's `loadAll` auto-loader (`Extensions.kt:446-448`) keys on the substring `_normal` — an underscore — and, when it matches, forces `RGBA8` with **10** mip levels regardless of what the caller asked for. Renaming the file to `diver_normal.png` would silently give the sheet a mip chain, and mip generation averages across cell boundaries: adjacent frames of the loop would bleed into each other under minification.

## Conventions

- Allman braces, 4 spaces, no wildcard imports — match the surrounding file.
- Comments here explain *why*, often at length, and frequently cite the evidence (a decompiled engine class, a screenshot, a playtest). That density is deliberate: most of these decisions look arbitrary or wrong without it. When you fix something subtle, leave the same kind of note.
- No per-frame allocation in the render path. HUD text formatting is the one explicit exemption; `DepthBlend` and the colour ramps take and return primitive floats specifically to stay allocation-free.
- Simulation is deterministic: seeded RNG, fixed timestep, no `Math.random()` in `dive/`.
- Tests use `kotlin.test` on JUnit Platform, with backticked descriptive names. A test that cannot fail is worse than no test (commit 4493eeb deleted several) — assert the relationship that would actually break.
