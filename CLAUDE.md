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

**`render/` — the engine shell.** `EnPustTil.kt` is the only `PulseEngineGame`: it reads input, gates the sim on lifecycle state, and draws. `RunLifecycle` is the IDLE → PLAYING → RUN_OVER → ENTER_INITIALS state machine — pure and engine-free, so the booth's whole unattended-recovery behaviour is unit-tested. `DiveRenderer` draws the world to `mainSurface`, `DiveLighting` issues immediate-mode `drawLight` calls, `Hud` draws to a separate `"hud"` surface, `DiveCamera` eases toward the diver.

**`score/` — persistence.** `ScoreRepository` is registered as an engine `Service` so it gets `onCreate` (load) / `onDestroy` (final synchronous save) from the engine lifecycle. Writes go to a temp file then `promoteAtomically` (`AtomicFileSwap`), with periodic timestamped backups. `InitialsEntry` is the three-letter arcade entry; `Leaderboard` ranks and decides what's worth recording.

**Presentation state must never leak into `dive/`.** `DiveSim` simulates one run and nothing else; attract mode, dwell timers and initials entry live in `RunLifecycle`.

### The pure-logic-extracted-for-testing pattern

Anything worth asserting is pulled out into an engine-free object so it can be tested without a GL context: `Viewport`, `DepthBlend`, `AimAngle`, `RunLifecycle`, `AttractLayout` and `ScreenText` (both in `EnPustTil.kt`), `parseDailySeed`, `textOutlineOffset`, `DiveRenderer.zone*At`. When you add rendering or layout logic, extract the *relationship between the numbers* the same way rather than leaving it inline in a draw call. `DiveSim` exposes `internal fun debug*` hooks for the same reason.

### Frame ordering is load-bearing

Lights are drawn from `onRender`, in the same call that drives `DiveRenderer`, reading the same `camera.depth`. Moving light positioning back into `onUpdate` reintroduces a one-frame drift between a light and the thing it illuminates (this was a real bug — see `DiveLighting`'s class doc). Camera easing runs on the render clock (presentation); the sim runs on the fixed tick.

Inside `DiveSim.tick`, the order of surfacing / air burn / pickup / anglerfish is deliberate and each ordering constraint is commented where it lives. Don't reorder without reading those comments.

## Platform constraints — verified, not guessed

These were each established empirically (several by decompiling `pulse-engine-0.13.0.jar`) after a bug that produced no error message at all. Violating one fails silently.

- **`Surface.drawQuad()` and `drawLine()` render nothing on macOS/Apple Silicon** — no GL error, no log. The cause is *not* the shader `#version`, which an earlier version of this file claimed: `QuadRenderer.kt:37` and `LineRenderer.kt:34` declare the colour vertex attribute as `GL_FLOAT` (so it binds through `glVertexAttribPointer`) while `quad.vert`/`line.vert` declare it `in uint`. Feeding an integer vertex input through the float path is undefined behaviour; the shader derives alpha as `(rgba & 255u)/255.0`, so the undefined value yields **alpha 0** and the geometry rasterises fully transparent. `TextureRenderer`/`TextRenderer` bind the same attribute as `GL_UNSIGNED_INT` and work. Believed macOS-only — Windows drivers pass the 32 bits through — so it reaches no players. We shadow the two shaders from `src/main/resources/pulseengine/shaders/renderers/` for local development and the scene editor, and the release jar **deliberately keeps the engine's broken copies** (see `build.gradle.kts`). So game code must still use `Surface.fillRect()` from `render/Draw.kt`: folding it into `drawQuad` would look correct on a Mac and fail silently at the booth. Full investigation in `docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md`.
- **`engine.window.width/height` are PHYSICAL framebuffer pixels**, not the logical size in `application.cfg` (2400×1800 on a Retina Mac from a declared 1200×900). Derive every size from the actual surface height. Express sizes as a fraction of screen **height**, never width and never a pixel count — the booth display's aspect ratio is not known in advance. See `render/Viewport.kt`.
- **The default font can only draw U+0020..U+011F.** Anything above that renders as *nothing at all* — no glyph and no x-advance, silently. Em dashes, en dashes, curly quotes, ellipses and bullets all vanish; Norwegian æøå are fine. Every drawn string goes through `ScreenText`, and `AttractScreenTest` asserts all of them are drawable, so a smart-quote substitution fails the build instead of reaching the booth. Use `ScreenText.SEPARATOR` (a middle dot) where you want an em dash.
- **A generic arcade USB encoder may have no SDL gamepad mapping**, in which case it is completely invisible to `engine.input.gamepads` while working fine at the OS level. `logGamepadDiagnostics` goes to the raw GLFW joystick API to tell "nothing plugged in" apart from "plugged in but unmapped", and logs the latter at WARN so it survives the booth's default log level.
- **The engine's `Gamepad` exposes only `isPressed`/`getAxis`** — there is no `wasClicked` for a controller button. Everything consuming lifecycle input takes level readings and does its own previous-frame edge detection (`RunLifecycle`, `InitialsEntry`). A stuck button on a booth encoder must never be able to restart the game repeatedly or blast through the alphabet.
- **Gameplay input reads gamepad 0; lifecycle input scans every connected gamepad** (`anyLifecycleActionPressed`). Index 0 is not guaranteed to be the cabinet's stick, and "any button to start" has to mean any.
- **The HUD lives on its own surface**, not `mainSurface`. `GlobalIlluminationSystem` multiplies `mainSurface` by the light map — which is what makes the Abyss dark, and would also multiply the HUD into near-invisibility. Its `zOrder` is pinned explicitly (`HUD_Z_ORDER`), because the engine otherwise assigns one by surface creation order.

## Config and release

`application.cfg` is the **booth default** and ships in the release `.exe`: fullscreen, no pinned window size (takes the display's native resolution), `logLevel = WARN`. `application-dev.cfg` is loaded automatically on top of it by the engine and restores windowed + DEBUG for local `./gradlew run`; it is excluded from the release by the `exclude("*-dev*")` line in `build.gradle.kts`. `screenMode` and window size **cannot** be changed at runtime — there's no setter that reaches the window after creation — which is why this split exists rather than an env var.

**Day two at the booth:** uncomment and change `dailySeed` in `application.cfg` and restart. That regenerates the water column and, because every `ScoreEntry` stores the seed it was earned under, gives day two a fresh leaderboard while day one's board stays intact in `scoreboard.json`.

## Conventions

- Allman braces, 4 spaces, no wildcard imports — match the surrounding file.
- Comments here explain *why*, often at length, and frequently cite the evidence (a decompiled engine class, a screenshot, a playtest). That density is deliberate: most of these decisions look arbitrary or wrong without it. When you fix something subtle, leave the same kind of note.
- No per-frame allocation in the render path. HUD text formatting is the one explicit exemption; `DepthBlend` and the colour ramps take and return primitive floats specifically to stay allocation-free.
- Simulation is deterministic: seeded RNG, fixed timestep, no `Math.random()` in `dive/`.
- Tests use `kotlin.test` on JUnit Platform, with backticked descriptive names. A test that cannot fail is worse than no test (commit 4493eeb deleted several) — assert the relationship that would actually break.
