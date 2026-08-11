# Én Pust Til — outstanding work

Handoff spec, written 2026-08-11 at commit `98bbcb0`, **393 Kotlin + 79 Python tests passing**, working tree clean.

JavaZone is **2–3 September 2026**, so roughly three weeks remain.

Read `CLAUDE.md` first — it covers the architecture, the coordinate spaces, the draw helpers and the platform constraints. This document covers only what is *left*, plus the traps that would otherwise be rediscovered the expensive way.

---

## 0. THE OPEN QUESTION — settle this before any lighting or albedo work

**Does `GlobalIlluminationSystem` composite `mainSurface` additively or multiplicatively?**

Two measured findings disagree, and several designs rest on the answer. An agent stalled mid-investigation.

**Evidence for ADDITIVE.** `pulseengine/shaders/lighting/global/final.frag` ends `fragColor = vec4(base + light, 1.0)` with `baseTex` bound to `mainSurface` — confirmed by reading the shader text. `GlobalIlluminationSystem` installs `GiAo`, `GiBounce`, `GiFinal`, `GiInterior`, `GiJfa`, `GiJfaSeed`, `GiRadianceCascades`, `GiSdf` — confirmed by disassembly, and **no `MultiplyEffect` among them**.

**Evidence for MULTIPLICATIVE.** The 94.5 m seam (fixed in `0f07303`) was traced to `minReflectance`, and the decisive experiment was setting `minReflectance = 0` with nothing else changed: max row-to-row jump 2.333 → 0.333, seam gone. `minReflectance` appears in exactly one shader in the entire engine — `effects/texture_multiply_blend.frag`, which is `c0.rgb * c1.rgb` with a path-to-white floor. If nothing multiplies `mainSurface`, that experiment should have done nothing.

**How to settle it in one capture:** draw a bright constant colour across the frame at a depth where the light map is near zero — 150 m, no pearls nearby. Bright out means additive; black out means multiplicative. Report the pixel values.

**Why it matters.** `CLAUDE.md` states the multiply as fact. The reverted rim light (`d6faaa5`) rests on it — its whole argument was that an outline cannot work because albedo is multiplied toward nothing at depth. `e45fdbe` (stopping a pearl's albedo down by its own emission) only makes sense under one reading. Whichever is true, **correct `CLAUDE.md` once, from evidence**, and note it in the design spec's §17 amendment log. Do not let each future agent rediscover it.

---

## 1. Decisions only the owner can make

These block or shape work and are cheap for him and expensive for anyone else. **Ask; do not choose.**

### 1.1 Does glowing scenery break the anglerfish's tell?

The anglerfish renders **identically** to a pearl and the only tell is motion — a real pearl never moves, the lure drifts toward you. That is in the design spec, stated at `DiveRenderer.drawAnglerfish`, and now guarded by `AnglerfishDisguiseTest` (which compares the two draws *and* the two `drawLight` argument lists textually).

The owner's mockup has bioluminescent dots across the coral. Fill the scene with glowing points and a suspicious light stops being unusual — the trap stops working.

Options: scenery glows a visibly different colour/shape from pearls (cool cyan-green vs warm amber is cheapest and arguably better art); the anglerfish gets a different tell (expensive, changes a locked design decision); or no glowing scenery in the Abyss specifically.

Blocks task 2.4.

### 1.2 The diver's on-screen size

`Framing.DIVER_HEIGHT_METRES` is 9 m — 15% of the 60 m visible depth. It got there in two owner-requested steps: 3 → 6 → 9.

His latest mockup has the diver at roughly 12% of screen height, and now includes a boat and a waterline that give a real sense of scale the earlier black-water mockup did not.

Most dependent constants are fractions of `DIVER_HEIGHT_METRES` and follow automatically. **`Tuning.PEARL_PICKUP_RADIUS` is not** — it is an absolute 4 m in `dive/`, chosen because the chin sits at 3.56 m and the crown at 4.45 m (`b70e449`), and it moved the abyss balance. If the diver shrinks, re-run the pickup measurement **and** the abyss probe (`AscentTest`, `Autopilot`).

---

## 2. Work, roughly in value order

### 2.1 Mask the flashlight so it does not render on top of the diver

The torch light renders over the diver's body — the flickering pool sits on his chest instead of reading as a beam leaving him. Clearest on the attract screen.

The emitter is 1.2 m at 0.40 × `DIVER_HEIGHT_METRES` forward along the heading (`5c5377d`) — i.e. on his mask, deliberately.

**Depends on §0.** If the composite is multiplicative, this is the same problem `e45fdbe` solved for pearls (stop the albedo down by the light it emits) and the same fix may apply. If additive, the diagnosis is different and needs redoing.

**Do not retry moving or hollowing the emitter.** `LightEmitter`'s class doc records the disproof: with `radius = 0`, `sampleScene` skips the distance term, so irradiance at the centre of a ring equals that inside a disc at *any* hole radius. An annulus was built, measured and reverted.

Worth checking whether GI exposes an occluder concept the diver could use (`GiOccluder` appears in §9 of the world-coordinate plan).

Files: `render/DiveLighting.kt`, `render/DiveRenderer.kt`. Needs the display.

### 2.2 Caustics on the rock faces

The owner raised it with the right instinct: *"really expensive unless we do some kind of texture mapping trick."* True caustics are expensive; the trick is not.

**The elegant route: reuse the shaft field.** `LightShafts.bandSum(x, depth, seconds)` and `bandMask(...)` are pure engine-free functions of (horizontal position, depth, time), and `ShaftRenderer` uploads `bandFrequency`/`bandAmplitude`/`bandPhase` straight out of the object. A caustics shader can upload the same three uniforms and evaluate the same five lines — so a bright shaft and a bright caustic always land together, which is the real physical relationship obtained for free. This was deliberately left as *available*, not abstracted: no interface, no registry.

The depth ramp is deliberately **not** shared — a caustic on rock is dim for a different reason than a shaft in water.

Constraints: fade out with depth (`ambientGreen(d)/ambientGreen(0)` is the derivation used elsewhere); the Abyss must stay at **+0.00%** against a same-build control pair; and check the rock is actually lit where you are putting them (see §3.4).

Files: `render/DiveRenderer.kt`, `render/RockFace.kt`. Needs the display.

### 2.3 Make the air bubbles float

Owner: *"vary in size, and slowly vary their position and scale, making them seem 'floating'."*

Today: 14 fixed slots evenly spaced on a 6 m circle, identical 0.9 m size, plus low-air oversizing (up to 2.8× on the last bubble) and a heartbeat pulse.

Four constraints, all load-bearing:

1. **Deterministic, no RNG.** The harness pins game state so captures are reproducible. Layered sines with a per-slot phase offset gives each bubble its own drift without a random source. No per-frame allocation.
2. **The clock-face reading must survive.** `231c6a6` fixed a real complaint (*"they don't pop in a natural clockwise order"*) by putting bubbles on fourteen fixed slots emptying clockwise from 12 o'clock. Drift big enough to blur *which* slots are gone undoes that. There is a genuine tension between "floating" and "legible dial"; the amplitude is where it resolves.
3. **A build-time assertion will fire.** `a6d6721` asserts the 9 m figure fits inside the ring; clearance is 1.05 m, exact minimum radius 5.85 m. That is the mechanism working. There is already a test sweeping every count/slot/phase asserting no bubble centre lands on the diver — extend it rather than relax it.
4. **Compose with what is there.** The oversizing and heartbeat are the game's only air warning and were tuned deliberately. Worst case is `airBubbleSizeScale` × heartbeat × your variation.

Frame-rate independent; freeze while paused. Note both shader agents converged on the **render clock** with `WRAP_SECONDS = 240`, drift as integer cycles per wrap, and a pinnable phase (`EPT_WAVE_PHASE`, `EPT_SHAFT_PHASE`) — follow that precedent, or say why bubbles differ.

`Hud` already exposes `airBubblesRemaining`, `airBubbleSlotAngle`, `firstOccupiedSlot`, `airBubbleSizeScale` as pure functions so `HudTest` can assert without a GL context. Keep whatever drives drift equally testable.

Files: `render/Hud.kt`.

### 2.4 Decorative scenery — coral, ambient fish, bubble particles

**Blocked on §1.1.** Needs art that does not exist yet.

Wanted: coral/sponges/anemones on the rock faces; 3–4 species of drifting ambient fish; a rising bubble trail from the air vents and possibly the diver; the air vent restyled as the mockup's rounded O₂ bubble (currently a flat cyan square).

What the code needs, none of which exists:
- A prop system with placement seeded from `dailySeed` so every attendee sees the same reef, drawn but never collidable, and **never touching `dive/`**.
- Culling via `Camera.showsSquare` (`render/Draw.kt`) or props cost a submission each across 160 m.
- Frame-rate-independent, pause-respecting animation — `DiverSprite`'s cycle is the pattern.

The deferred entity-layer analysis (§9 of `docs/superpowers/plans/2026-08-06-engine-world-coordinates.md`) is worth reading first: props are the one case where scene entities might pay off, but its conclusion was that they buy little, and it records the trap that a prototype entity setting `HIDDEN` in `init{}` can never be selected in the editor (`SceneEditor.kt:267`, `:597`, `:668`).

### 2.5 HUD restyle

Lowest risk on the list. The HUD is functionally what the mockup shows; it just reads plainer. All on the `"hud"` surface, which is **not** relit by GI, so authored colours are what appear.

From the mockup: a small pearl icon beside `BANKED`; the clock in a rounded bordered box; the depth tape with real ticks and labels at 0/25/50/75/100 m and a slider-style handle.

The mockup's held count is white; ours is amber heating toward red as the haul grows, which is a deliberate cue from spec §12. **Probably keep ours — flag rather than change.**

Two things to respect:
- Semi-transparent colours on this surface come out far fainter than authored: RGB is stored pre-multiplied by alpha **and** alpha is stored squared, so 0.15 alpha displays near 0.02. Use `Hud.authoredAlphaFor()`. This is what made the depth tape invisible until `44a3902`.
- Every drawn string must be inside the default font's baked atlas, **U+0020..U+011F**. Anything above renders as nothing at all *and consumes no width*, silently. Add new strings to `ScreenText`; `AttractScreenTest` asserts they are drawable. This is what made an em dash vanish (`8fb47d2`).

Files: `render/Hud.kt`, `EnPustTil.kt`.

### 2.6 Owner-supplied art still to be wired

He is making these himself: **boat** and **cloud** sprites for the new sunset sky. When they land, bake and wire them the way `97a88aa`/`aac1649` did the rock — `tools/build_backdrop.py` is the working example. The boat is the frame's main scale anchor, so it interacts with §1.2.

---

## 3. Traps — every one of these has already cost this project time

### 3.1 Asset declaration

`SpriteSheet`'s argument order is `(…, format, maxMipLevels, hCells, vCells)`. Passing `(…, cols, rows, 0)` gives `size = 0` and `getTexture(0)` throws. **`maxMipLevels` must never be 0** — `glTexStorage3D(levels = 0)` is `GL_INVALID_VALUE`: no storage allocated, every later upload fails, **no exception and no log**. Documented in the bake spec.

The engine's filename auto-loader keys on `_normal` with an **underscore** (`Extensions.kt:446-448`) and forces `RGBA8` + 10 mips. `diver-normal.png` uses a hyphen deliberately to avoid it.

Mip decisions are per-asset, not a house default: the diver's sheet uses 1 because mip generation smears across cell boundaries; the rock uses 1 for a different reason (`glGenerateMipmap` runs over the whole square layer and contaminates the tile join). Decide and justify.

### 3.2 The GI reflectance floor

Albedo below `0.02` linear length is replaced with flat grey. This produced the 94.5 m seam. **45.3% of the rock's source texels came in under it** and needed an ambient lift in the bake. Measure any new art against `DiveRenderer.GI_REFLECTANCE_FLOOR` and report the distribution.

(Its exact mechanism is entangled with §0 — resolve that first.)

### 3.3 Emitters are visible regions

A `drawLight` emitter quad rasterises into the scene. With `radius = 0` a probe inside it takes a zero first raymarch step and samples its own texel, so an emitter renders as **a flat shelf of irradiance with a hard rim**. That is why a body-sized diver emitter read as a box (`006512b`) and why a 3 m emitter around a 1.2 m pearl showed a square shelf (`0671393`). Proven 1:1 by forcing a 12 m emitter and measuring an 11.7 m disc.

Corollary: **no emitter shape can spare a body at the centre of its own light.** The annulus is disproved; do not rebuild it.

Inverse rule, equally real: a shaft is the one light you look at the *inside* of, so **its falloff lives in RGB, not alpha** — the opposite of the pearl rule. Both are recorded in `LightEmitter` under a HISTORY heading, marked inert now that shafts are a shader.

### 3.4 The rock is barely lit outside the column

Outside `Tuning.COLUMN_HALF_WIDTH` the GI light map is about **1/20** of its value inside, so the cliff only reads where the torch or a pearl is near it. `16dfa99` addressed the water showing through the cliff's alpha; whether the lighting falloff itself was resolved needs checking before building anything that relies on the rock being visible (§2.2).

### 3.5 Shared surface state leaks between frames

`surface.setDrawColor` is shared state. The god-rays agent set a tint with it, and because the strips are drawn last the colour was still set when `DiveRenderer` drew the **next** frame — every world draw that does not set its own colour first was silently at 70% opacity. Prefer uniforms. `DiveRenderer.drawDiver` resets to opaque white for exactly this reason.

### 3.6 `drawQuad` and `drawLine` render nothing on macOS

Not the shader `#version`, as an earlier comment claimed: `QuadRenderer.kt:37`/`LineRenderer.kt:34` bind a `uint` shader attribute as `GL_FLOAT`, which is undefined behaviour yielding alpha 0. We shadow the two vertex shaders for local development, and the release jar **deliberately keeps the engine's broken copies**. So game code must use `Surface.fillRect`/`fillRectCentred` — folding them into `drawQuad` would look right on a Mac and fail silently at the booth. A test guards this.

### 3.7 Custom shaders

Established path: a `BatchRenderer` via `Surface.addRenderer`, with shaders under `src/main/resources/shaders/` — deliberately **outside** `/pulseengine/` so they can neither shadow an engine file nor be caught by `build.gradle.kts`'s `devOnlyShaderOverrides`. Take `SurfaceConfigInternal` so the renderer joins the shared depth/draw-colour scheme; batch renderers run in *add* order, not call order.

Target `#version 330 core` — macOS caps OpenGL at 4.1 with no compute shaders. Verify packaging with a **classloader probe**, not `unzip`: the two disagree about duplicates.

Worked examples: `render/IridescenceRenderer.kt`, `render/ShaftRenderer.kt`.

---

## 4. How to work here

### Testing

`./gradlew test` — 393 Kotlin tests. `tools/` has 79 Python tests.

**Every test must be mutation-tested**: break the production code it covers, confirm the test fails, restore **from a file copy** (not `git checkout --`, which has destroyed real work twice here), verify byte-identical. Report the mutation per test.

**A test with no killing mutation is worse than no test.** This project has shipped 16 that could not fail, found `ViewportTest` cases reducing to `f(x)·h/h == f(x)·h/h`, and had a pause test that silently stopped exercising its own subject. Two agents have correctly **deleted** their own tests when a measurement disproved the constant being asserted — that precedent stands. Where something is only verifiable by looking, say so in a comment instead of asserting it.

`grep -rn "MUTATION" src/ tools/` must be empty before every commit.

### Capturing

Tooling in `scratchpad/glitch/`: `apply_harness.py <repo-root>` (adds an `EPT_SHOT` harness pinning depth/x/held/air/clock), `cap_run.sh <name> <spec> <W> <H> [frame]` (windowed; edits `application-dev.cfg` — **revert before committing**), `composite.py`, `concentric3.py`, `HARNESS.md`.

`JAVA_HOME` must be the Gradle toolchain JDK 23 — the system JDK is 19 and the launcher dies with `UnsupportedClassVersionError`. Run `caffeinate -d -u -t 2400 &` once or the display sleeps and GLFW NPEs. Single bounded sleep then a kill; **no background pollers**. `EPT_DEV=1` enables the camera invariants, which are silent — any warning is a regression you introduced. `EPT_EDITOR=1` opens the scene editor, where GI's parameters are live-editable.

**Two lessons this project paid for, both in `HARNESS.md`:**
1. If a frame looks wrong, `git diff src/main/kotlin/EnPustTil.kt` and read the harness's own hunks **before** concluding anything about the game. A harness anchoring bug once cost a full debugging session chasing a fault that did not exist.
2. **Always take a same-build control pair.** Two runs of the same build differ by 1.6–3.1 M of 5.76 M pixels. A pinned stationary diver has produced bit-identical pairs — that is the trick for measuring anything subtle.

### Committing

Commit **the moment each piece is green**, then refine. Seventeen agents on this project have stalled or been interrupted mid-run; every one that committed in stages kept its work, and the ones that did not lost complete diagnoses.

If more than one agent is working, **stage only your own paths — never `git add -A`.** That has swept another agent's uncommitted work into a commit twice. `assets/` (183 MB) and `release/` (41 MB) are gitignored; keep it that way.

### Boundaries

`src/main/kotlin/dive/` is the pure simulation — zero engine imports, and it must not change for presentation work. It is what allowed the buoyancy model to be rewritten from scratch on playtest feedback. Gameplay changes there need the owner's sign-off (the pickup radius in `b70e449` is the one recent exception, and it moved the abyss balance).

Pearl, vent and anglerfish placement stays procedural from `dailySeed`. Every attendee must face an identical column and a new seed must give day two a fresh one. It must never move into a scene file.

Nothing hardcodes a pixel count or an aspect ratio; the booth display size is unknown. `engine.window.width/height` are physical framebuffer pixels.

---

## 5. The booth risk nobody has touched

**The Windows `.exe` has never been run.** Development is on macOS; the game ships as a Windows executable via `./gradlew buildWin64Release`. This is the largest untested risk in the project and the owner has said he will take it.

When a Windows machine is available, check: it launches fullscreen at native resolution; `drawQuad`/`drawLine` behave (believed fine on Windows, ~85% confidence, untested — the shipped jar deliberately carries the engine's broken shaders); scores persist across a restart; the Esc hold exits cleanly so `onDestroy` runs and the final save happens; the arcade encoder enumerates (`logGamepadDiagnostics` logs at WARN so it survives the booth log level); FPS with GI at booth resolution; and the day-two `dailySeed` change gives a fresh column and an empty board.

See `docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md`.
