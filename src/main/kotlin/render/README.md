# `render/` - the engine shell

↑ [Repo root](../../../../README.md) · [CLAUDE.md](../../../../CLAUDE.md) · [Design spec](../../../../docs/superpowers/specs/2026-08-04-en-pust-til-design.md) · [Docs map](../../../../docs/README.md)

Everything in this game that touches Pulse Engine lives here or in `../EnPustTil.kt`. Nothing in
`dive/` imports the engine; nothing here decides a game rule. `EnPustTil.kt` is the only
`PulseEngineGame` - it reads input, gates the pure simulation on a lifecycle state, and draws.
The rest of this package is the drawing.

The other half of the boundary is the **pure-logic-extracted-for-testing** pattern, and it is
the reason this package has 27 files rather than three. Anything worth asserting is pulled out
into an engine-free object so it can be tested without a GL context: `Framing`, `CameraRig`,
`CameraInvariants`, `DepthBlend`, `AimAngle`, `RunLifecycle`, `WaterSurface`,
`IridescentMaterial`, `AttractLayout` / `PauseLayout` / `ScreenText` (in `EnPustTil.kt`), and
the strip walk inside `DiveRenderer` (`stripCount` / `stripTopDepth` / `stripCentreDepth`).
When you add rendering or layout logic, extract the *relationship between the numbers* the same
way rather than leaving it inline in a draw call.

`../../../../CLAUDE.md` is the evidence store. This file is the map. Where a claim below is
short, the long version with the decompiled class, the shader line or the measurement is either
in CLAUDE.md or in the class doc of the file named - those class docs are extensive and are the
best source in the repo.

---

## 1. A frame, start to finish

### `onCreate` - the order here is load-bearing, not stylistic

| # | Call | Why it is where it is |
|---|---|---|
| 1 | `createSurface("hud", ..., zOrder = HUD_Z_ORDER)` (`EnPustTil.kt:1027-1032`) | Created **first**, before anything else in `createGame` that can throw. `engine.gfx.getSurfaceOrDefault` falls back to `mainSurface` with no log if `"hud"` was never created (decompiled: `surfaceMap[name] ?: mainSurface`) - and `mainSurface`'s camera is a *world* camera in metres, so a boot-failure screen drawn through that fallback would land hundreds of world-metres off screen. Moving the HUD surface first means it always exists by the time anything downstream (asset loads, `DiveLighting.setup`) could fail. |
| 2 | `parseDailySeed`/`resolveDailySeed` (`:1043-1045`) and `parseDepthPin` (`:1102`) | `application.cfg` is only loaded into `engine.config` by the engine's `initEngine()`, which runs *after* this object is constructed and *before* `onCreate`. Reading it at field-init time would silently see an empty config. |
| 3 | `mainSurface.setBackgroundColor(Color.BLANK)` (`:1188`) | The world surface is **transparent where nothing is drawn**. That one line is what lets a sky exist behind it. |
| 4 | `WaterRenderer.addTo(mainSurface)` then `IridescenceRenderer.addTo(mainSurface)` (`:1235`, `:1237`) | Batch renderers flush in the order they were **added**, not called, and every one writes depth - including for fragments at alpha 0. Reverse these two and each pearl punches a hole through the sea. `SurfaceRendererOrderTest` fails the build on both halves. |
| 5 | `engine.config.fixedTickRate = 60f`, `camera.snapTo`, `CameraRig.snap` (`:1238-1251`) | **`snap`, not `apply`.** Frame 1 runs no fixed step, so the engine's interpolator reads only its un-refreshed snapshot - which is the identity camera. Measured at 2400x1800: frame 1 drawn with `viewMatrix = identity` while `scale` already read 30. |
| 6 | `DiverSprite.load` / `RockFace.load` / `Backdrop.load` / `SandBank.load` (four consecutive calls in `createGame`) | File-backed assets. `AssetManager.load` only queues; the GL upload lands several frames later. `SandBank` is queued HERE rather than with the draw code, so `ready()`'s one WARN is never spent on a self-inflicted false alarm. |
| 7 | `LightEmitter.load` / `MoteSprite.load` / `PearlNormalMap.load` (`:1291-1293`) | Generated textures. No file behind them, so all three must be **filled before they are queued**. |
| 8 | `DiveLighting.setup(engine)` (`:1295`) | Creates the empty scene, adds `EntityRendererImpl` + `GlobalIlluminationSystem`, sets GI's resolution and AO radius, and attaches `ColorGradingEffect` (UNCHARTED2) and `BloomEffect` to `mainSurface`. Must come after the world's renderers (step 4). |
| 9 | `IridescenceRenderer.addTo(hudSurface)` (`:1313`) | The second instance of the same program. One shader, two surfaces, two coordinate spaces. |
| 10 | `createSurface(Sky.SURFACE_NAME, camera = engine.gfx.mainCamera, zOrder = main + Sky.Z_ORDER_OFFSET)` (`:1339-1344`) | Deliberately the **shared world camera**, the exact opposite of the HUD's `null` (step 1). This is a *read* of `mainCamera`; `MainCameraOwnershipTest` allows it and separately forbids writing a transform outside `CameraRig`. |
| 11 | `OpaqueWaterEffect` on `mainSurface` (`:1360`) | Repairs `main`'s alpha below the waterline. See `OpaqueWater.kt` - it survives the god rays' removal on purpose. |
| 12 | The `EPT_*` pins and `EPT_SCREENSHOT` (`:1369-1384`) | One `getenv` each, inert at the booth. |

### `onFixedUpdate` - 60 Hz, `EnPustTil.kt:1387`

1. `if (lifecycle.simulationAdvances)` - the gate. `RunLifecycle.simulationAdvances` is an
   exhaustive `when` with no `else`, so a sixth state is a compile error there rather than a
   silent default. False for `IDLE` and `PAUSED`.
2. `sim.tick(fixedDeltaTime, readInput())` - **the only call to `DiveSim.tick` in the game.**
   Gating this one line freezes the whole simulation; there is no second path by which a paused
   run can lose time, air, depth or a pearl.
3. `DiveLighting.updateAim(sim, dt)` - inside the same gate as `sim.tick`, right after it. The
   torch heading is integrated **once**, here. It used to be integrated inside the beam draw,
   which broke the moment the diver's *body* had to be drawn to the same heading: `DiveRenderer`
   runs before `DiveLighting` in `onRender`, so the body would have used the previous frame's aim.
4. `if (lifecycle.spriteAnimates) DiverSprite.advanceLoop(dt)` - its **own** gate, deliberately
   NOT `simulationAdvances`: also true in `IDLE`, so the diver's kick animates on the attract
   screen while the sim itself stays frozen (task 9 - a motionless diver behind a fresh attract
   arrival reads as dead, not idle).
5. `camera.update(dt, sim.depth, visibleDepthMetres())` then `CameraRig.apply(engine, camera.depth)` -
   **outside** both gates above, because a paused or attract frame still has to be drawn with a valid
   camera. `visibleDepthMetres()` is what `DiveCamera`'s sandbank floor clamp needs; it reads
   `mainSurface.config`, not `engine.window`, for the same reason screen-space code does (§2).

**Camera easing runs on the fixed tick, not the render clock.** `updateViewMatrix` interpolates
every camera parameter from a snapshot taken at the top of each fixed step, so a render-clock
write pairs values that were never consecutive fixed states and the interpolator judders.
Nothing is lost: `DiveCamera`'s easing is `1 - e^(-k*dt)` and already frame-rate independent.

### `onUpdate` - render clock, `EnPustTil.kt:1449`

1. `DiveLighting.updateAmbient(sim)` - ambient is a function of depth only, no camera
   dependence, so timing does not matter.
2. `WaterSurface.advance(deltaTime)` and `Motes.advance(deltaTime)` - the sea and the marine
   snow move on the **render clock and outside every lifecycle gate**, deliberately. The fixed
   tick is gated on `simulationAdvances`, which is false in `IDLE`, so a correctly-gated sea
   would be frozen solid on the one screen a booth queue spends its time looking at. Both are
   pinnable for reproducible captures.
3. Input scan: gameplay follows `activePadId` (the pad that started the run - see
   `selectGameplayPad`); lifecycle input scans **every** gamepad, per-source, via
   `LifecycleInputEdges`. Pause (Esc) and exit (hold Q) are keyboard-only.
4. `lifecycle.update(...)`, then react: `exitRequested` -> `engine.window.close()`;
   `justStarted` -> fresh `DiveSim`, `applyDepthPin`, `camera.snapTo`, `CameraRig.snap`,
   `DiveLighting.resetAim`, `DiverSprite.restartLoop`; `initialsJustCompleted` ->
   `scoreRepository.registerScore`.

### `onRender` - `EnPustTil.kt:1691`

```
val worldCamera = engine.gfx.mainCamera            // read once, handed to everything
val normalMaps  = getSurface(GI_NORMAL_MAP)?.getRenderer<NormalMapRenderer>()
val aimDegrees  = DiveLighting.beamHeadingDegrees  // ONE heading, two renderers
opaqueWater.gate = gateUv(worldCamera.worldPosToScreenPos(0, GATE_DEPTH).y, main.config.height)

Sky.render(getSurfaceOrDefault("sky"), worldCamera)                       // own surface, behind
DiveRenderer.render(mainSurface, sim, worldCamera, normalMaps, aimDegrees) // the world
DiveLighting.render(engine, sim, worldCamera)                             // drawLight -> GI
// HUD:
val hud = getSurfaceOrDefault("hud"); val w = hud.config.width; val h = hud.config.height
val anchor = worldCamera.worldPosToScreenPos(sim.x, sim.depth)   // the ONE hand transform
when (lifecycle.state) { IDLE -> attract; PLAYING -> Hud.render(...); ... }
if (devMode) { renderGamepadOverlay(...); checkCameraInvariants() }
```

Inside `DiveRenderer.render` (`DiveRenderer.kt:345`), on `mainSurface`, back to front:
zone bands -> backdrop silhouettes -> the sea's surface quad -> column walls -> air pockets ->
pearls -> anglerfish -> diver -> **motes last** (`:417`). Each of those boundaries is argued in
a comment at the call site; the backdrop in particular *must* sit between the opaque bands and
the opaque walls, which is what confines the silhouettes to the column with no clip test.

Inside `DiveLighting.render`, immediate-mode onto GI's local scene surface: pearl lights,
anglerfish lure, diver beam. Order is presentational only - `GiSceneRenderer` accumulates a
batch solved once - but it is sorted by how much each light matters. A fourth pass, `mote
lights`, was issued first between 2026-08-13 and 2026-08-17; **the motes are not lights**, and
`Motes`' "THE GLOWING SUBSET" section is the argument.

### Why the order is load-bearing

- **Lights must be issued from `onRender`.** `GiSceneRenderer`'s batch has to be filled before
  `gfx.drawFrame`, and `onRender` is where the rest of the drawing lives. Do not move it to
  `onUpdate`.
- **Light-versus-geometry alignment is structural, not procedural.** GI's local scene surface is
  created with `camera = engine.gfx.mainCamera` - the same object `mainSurface` uses - and every
  matrix is built once per frame in `gfx.initFrame` before any game code runs. A light at world
  `(x, depth)` and a square at world `(x, depth)` go through the identical matrix and *cannot*
  drift, whichever callback issued them.
- **The remaining drift risk is the HUD.** It is on its own screen-space camera, so it has to
  transform the diver's position by hand. That anchor must come from
  `mainCamera.worldPosToScreenPos` in `onRender` - the same matrix, this frame - and never from
  `DiveCamera.depth`, which is camera state from a different point in the frame.
- **`DiveRenderer` runs before `DiveLighting`**, which is why the aim heading is integrated on
  the fixed tick and merely *read* here.

---

## 2. Surfaces, z-order and coordinate spaces

```
  drawn EARLIEST / furthest back
  +-----------------------------------------------------------+
  | "sky"        zOrder = main + Sky.Z_ORDER_OFFSET (10)       |  world metres, mainCamera
  |              escapes the GI multiply AND stays behind      |  no tone map, no bloom
  +-----------------------------------------------------------+
  | GI internals  main+1 .. main+9  (gi_local_scene,           |  world metres, mainCamera
  |               gi_normal_map, gi_light_final, ...)          |  (the offset above clears these)
  +-----------------------------------------------------------+
  | "main"        the lit world: water, rock, backdrop,        |  world metres, mainCamera
  |               pearls, diver, motes                         |  MULTIPLIED by the light map,
  |                                                            |  then colour-graded + bloomed
  +-----------------------------------------------------------+
  | "hud"         zOrder = HUD_Z_ORDER = -90                   |  SCREEN PIXELS, own identity
  |               BANKED, clock, air ring, depth tape,         |  DefaultCamera (camera = null)
  |               attract / pause / initials screens, MSAA16   |  outside GI entirely
  +-----------------------------------------------------------+
  drawn LAST / on top
```

`GraphicsImpl` composites sorted by `-zOrder` ascending, so a **larger** `zOrder` is drawn
**earlier**, i.e. further back. The sky's `+10` and the HUD's `-90` are opposite senses of the
same rule.

| Surface | Camera | Space |
|---|---|---|
| `main`, `"sky"`, and GI's `gi_local_scene` / `gi_normal_map` | `engine.gfx.mainCamera` | **world metres**, +x right, +y down, world y *is* depth |
| `"hud"` | its own identity `DefaultCamera` (created by passing `camera = null`) | **screen pixels** |

**`CameraRig` is the only writer of `engine.gfx.mainCamera`.** `MainCameraOwnershipTest` enforces
that by source scan with an exact-set allow-list, because a second writer is precisely the bug
`6ea1f53` fixed. `DiveRenderer` and `DiveLighting` therefore contain no coordinate maths at all;
they hand the engine metres and ask the camera (`isInView` via `showsSquare`,
`topLeftWorldPosition`) what is on screen.

**Screen-space code reads `surface.config.width/height`, never `engine.window.*`.** They are the
same number today - `CameraInvariants` rule 1 exists to notice if they ever stop being - but
`config` is what that surface's own projection was built from, so it is the only value that
cannot disagree with what is actually being rasterised. Reading the window instead is one half
of the mechanism that shipped in `6ea1f53`'s bug. And `engine.window.width/height` are
**physical framebuffer pixels** (2400x1800 on a Retina Mac from a declared 1200x900), so express
a screen size as a fraction of **height**, never width and never a pixel count.

**The camera invariant has two regimes.** `CameraRig.pixelsPerMetre` is
`max(H / VISIBLE_DEPTH_METRES, W / VISIBLE_WIDTH_METRES)` - the *larger* of a height fit and a
width fit. The design aspect where they are equal is 1.6419, below 16:9, so a 16:9 panel is in
the width-bound regime and shows 55.42 m of depth instead of 60. That is a deliberate gameplay
trade, made to keep exactly one cliff sprite at each end of the frame; `Framing.kt:30-89` has the
whole argument and `FramingTest` bounds the cost. It is `max`, not `min` - `min` is the engine
`Camera` entity's contain fit, which is what `CameraRig` exists to avoid.

---

## 3. File by file

### Tuning

| File | Owns |
|---|---|
| `Look.kt` | **Every number that decides how the game looks** - sky gradient, sun, the water's colour ramp, the daylight tables and their floor, the torch, the pearls, the motes, the colour grade. Values only, no logic; the reasoning lives at the consumer. This is where a tuning task starts, so it is listed first. See §4 for what is deliberately *not* in it. |

### Lifecycle and entry

| File | Owns |
|---|---|
| `../EnPustTil.kt` | The only `PulseEngineGame`. Surfaces, asset queueing, the four callbacks, input reading, the attract / pause / run-over / initials screens. Also `DefaultFont`, `ScreenText`, `AttractLayout`, `PauseLayout` - all pure and unit-tested. |
| `RunLifecycle.kt` | `IDLE -> PLAYING -> PAUSED / RUN_OVER -> ENTER_INITIALS`. Pure, engine-free, so the booth's whole unattended-recovery behaviour is unit-tested. Does its own previous-frame edge detection, because the engine's `Gamepad` has no `wasClicked`. |
| `GamepadScan.kt` | `selectGameplayPad` - gameplay follows the pad whose button started the run, not slot 0. The "any button to start" rule now lives in `LifecycleInputEdges`' class doc. |

### Camera and framing

| File | Owns |
|---|---|
| `Framing.kt` | How much water is visible and where the diver sits in the frame. **Owns no transform** (it used to be `Viewport`; `screenX`/`screenY`/`depthAt` are gone). Metres only. |
| `CameraRig.kt` | The *only* writer of `engine.gfx.mainCamera`. `apply` on the fixed tick, `snap` where `DiveCamera` teleports. |
| `DiveCamera.kt` | The smoothed vertical camera depth. Pure, `1 - e^(-k*dt)`, clamped so lag can never lose the diver. |
| `CameraInvariants.kt` | Three rules checked against a **real** framebuffer once a second under `EPT_DEV`: surface size vs window size, visible depth, and world-rect aspect. Rule 3 is the only automated check anywhere that can catch a pixels-per-metre derived from width instead of height. |
| `AimAngle.kt` | Heading maths and shortest-way-round smoothing. Documents the Y-flip between the world's y-down and `GiSceneRenderer`'s y-up cone convention, derived from the engine's shader source. |

### World rendering

| File | Owns |
|---|---|
| `DiveRenderer.kt` | The world on `mainSurface`: zone bands, backdrop, sea quad, sandbank, walls, air pockets, pearls, anglerfish, diver, motes. Holds `GI_REFLECTANCE_FLOOR` (`:205`) and the zone colour ramps. |
| `Draw.kt` | `fillRect`, `fillRectCentred`, `showsSquare`, `drawTextWithOutline`, `textOutlineOffset`. Every solid rectangle in the game goes through here. |
| `DepthBlend.kt` | Smooth per-zone interpolation anchored on zone **midpoints**, so nothing steps at a boundary. Zero allocation. |
| `RockFace.kt` | The cliff: one edge tile per side (never tiled horizontally), a mirrored body behind it, and a crest above the waterline. The geometry that makes "exactly one cliff sprite at each end" structural. |
| `Backdrop.kt` | Three parallax silhouette ridges, alpha masks tinted by `DiveRenderer.silhouetteColor`. Vertical parallax only, because there is no horizontal camera. |
| `SandBank.kt` | The seabed at the foot of the trench: one quad, exactly `Framing.VISIBLE_WIDTH_METRES` across, never tiled. `QUAD_TOP_DEPTH` is the single chosen placement and everything geometric is derived from the art outward - do not re-invert that, or `SandBankTest`'s relationship assertion becomes an identity. Its `skirtDepth` branch is unreachable with the camera clamp in place, on purpose. |
| `Motes.kt` | Marine snow as a **stateless hashed lattice**, not a particle system: culled by construction, infinite, deterministic, zero-allocation. Drawn on `main` so GI darkens it, and it emits nothing - a mote is an overlay the water is seen through, and only the torch makes one legible in the deep. |

### Lighting

| File | Owns |
|---|---|
| `DiveLighting.kt` | GI setup (scene, `GlobalIlluminationSystem`, resolution, AO radius, tone mapping, bloom), the ambient colour, the aim heading, and the four immediate-mode `drawLight` families. |
| `LightEmitter.kt` | The generated round emitter every light shapes itself from. Its alpha = 0.5 contour is the inscribed circle, because 0.5 is the only contour GI reads. |
| `PearlNormalMap.kt` | The generated hemisphere normal submitted to `gi_normal_map` on the pearl's own world rect, so a bead has a lit side. `PearlNormalMapTest` asserts it against `iridescence.frag`'s source on disk. |

### Water and sky

| File | Owns |
|---|---|
| `Sky.kt` | The sunset gradient, on its own surface behind the world so it escapes the GI multiply *and* stays behind the cliff tops. Horizontal strips, same technique as the zone bands. |
| `WaterSurface.kt` | Pure: the three-component wave table, the band of depth the quad may occupy, and the wrapping render-clock. Wraps at 240 s so a two-day unattended run never quantises. |
| `WaterRenderer.kt` | The `BatchRenderer` that draws one sea quad a frame with `shaders/water.{vert,frag}`. Its fragment alpha *is* the waterline - one edge, not two that must agree. |
| `OpaqueWater.kt` | The post-processing pass that restores `main`'s alpha below the waterline. The engine's `BlendFunction.NORMAL` erodes destination alpha (`glBlendFuncSeparate` appears nowhere in the jar), which underwater revealed the cleared backbuffer as black discs. |

### Assets and sprites

| File | Owns |
|---|---|
| `DiverSprite.kt` | The two baked sheets, the 41-frame loop, the figure's size, `bodyAngleFor`, and `sheetsReady()` - the gate that turns an async-upload race *and* a wrong constructor argument order into a placeholder rectangle plus one WARN. |
| `MoteSprite.kt` | The generated dab a mote is **drawn** with. Separate from `LightEmitter` on purpose: an emitter's profile is flat at 1.0 out to r = 0.6, which reads as a plate when drawn rather than emitted from. |
| `IridescenceRenderer.kt` | The custom-shader path: `Surface.addRenderer(BatchRenderer)`, one instance per surface, `viewProjection` from `surface.camera`. Copy this file for the next custom shader. |
| `IridescentMaterial.kt` | The two parameter sets, `PEARL` and `BUBBLE`, uploaded as per-instance attributes - never a uniform and never a shader branch. Pure Kotlin. |

### HUD

| File | Owns |
|---|---|
| `Hud.kt` | BANKED, HELD, the air bubble ring, the depth tape, the clock. Pure screen space: it is *handed* the diver's screen position and a pixels-per-metre scale and owns no camera. |
| `VentLabel.kt` | The faint `O2` written across each oxygen vent. On the **HUD** surface (text, and GI must not decide how faint it is) but anchored to **world** positions through `worldPosToScreenPos`, so unlike `Hud.kt` it is handed the world camera. Its font size comes from the vent's own on-screen height, not from the screen's - so the label holds its proportion to the blob at every aspect and through every camera ease. |

### Dev tools

| File | Owns |
|---|---|
| `ScreenshotEffect.kt` | Debug only. Captures at frame 180 by default. See section 6 for why its output is not the frame. |

---

## 4. `Look.kt` versus the consumer files

`Look.kt` is the presentation twin of `dive/Tuning.kt`: **every number that decides how the game
looks, values only, no logic.** Sky gradient, sun colour, the water's own colour ramp, the
daylight tables and their floor, the torch, the pearls, the motes, and the colour grade - each
declared there and referenced from the file that uses it, so the owner has one page to tune from.
Groups are ordered the way you see them on screen. If you only want the frame lighter or darker
overall, that is one number: `Look.GRADE_EXPOSURE`.

**The reasoning deliberately stays at the consumer** (`DiveLighting`, `DiveRenderer`, `Sky`,
`Motes`), because that is where someone about to change behaviour will meet it. Each `Look` entry
carries a one-line summary and a `@see`.

**"Values only" is true of the NUMBERS and false of the COUNT, for six of these tables.**
`ZONE_RED` / `ZONE_GREEN` / `ZONE_BLUE` and `AMBIENT_RED` / `AMBIENT_GREEN` / `AMBIENT_BLUE` are
hand-written `floatArrayOf`s with **one entry per `dive.Zone`, indexed by `Zone.ordinal`** through
`DepthBlend.blend` - which does exactly that, with no bounds check and no default. So **adding or
removing a zone in `dive/Zone.kt` is not a `dive/`-only change**: miss one of the six and the
failure is an `ArrayIndexOutOfBoundsException` thrown from inside a draw call, on the first frame
that paints the new band. `DiveRendererTest.every per-zone colour table in Look has exactly one
entry per Zone` is the guard; it was added on 2026-08-17 because nothing had been checking, and
494 otherwise-green tests would not have caught it.

**Correctness constraints are deliberately NOT in `Look.kt`**, because a page headed "tweak to
taste" is the wrong home for a value that was solved rather than chosen:

- `DiveRenderer.GI_REFLECTANCE_FLOOR` - the engine's own threshold, not ours to pick.
- `Motes.FIELD_TOP_DEPTH` - a depth-buffer guard against submitting a mote into the water quad's
  band.
- `OpaqueWater.GATE_DEPTH` - an alpha-blend guard, derived from the wave's amplitude bound.
- `MoteSprite.ALPHA_RAMP_OUTER` - solved against `texture.frag`'s alpha discard.

Shader-side numbers are also absent: `water.frag` and `iridescence.frag` carry their own and
cannot be reached from Kotlin without a uniform to carry them.

**If the build fails after an edit in `Look.kt`, the test message is a design rule speaking.** A
dozen of those numbers are constrained against each other rather than absolutely: the >10x
Shallows-to-Abyss water ratio (`DiveRendererTest`), the ambient floor's blue dominance
(`DiveLightingTest`), the mote-versus-pearl hue separation (`MotesTest`, which keeps the
anglerfish's lure a trap), and every water band clearing the reflectance floor once quantised.

---

## 5. The traps

Each of these fails **silently** - no error, no log - and each was established empirically, most
of them by decompiling `pulse-engine-0.13.0.jar` after a bug that produced no message at all.

| Trap | What happens if you get it wrong | Guard |
|---|---|---|
| **`drawQuad` / `drawLine` on macOS** | Renders nothing at all. `QuadRenderer.kt:37` binds the colour attribute as `GL_FLOAT` while `quad.vert` declares it `in uint`; the undefined value yields alpha 0. Use `Draw.kt`'s `fillRect` / `fillRectCentred`. | `DrawTest.no production source draws a quad or a line`; CLAUDE.md platform constraints |
| **The font's U+011F ceiling** | Em dashes, en dashes, curly quotes, ellipses and bullets render as *nothing* - no glyph and no x-advance. Norwegian ae/oe/aa are fine. Use `ScreenText`, and `ScreenText.SEPARATOR` where you want an em dash. | `AttractScreenTest` asserts every string in `ScreenText.all()` is drawable |
| **`SpriteSheet` constructor argument order** | It is `(..., format, maxMipLevels, hCells, vCells)`, *not* the field declaration order. Passing `(..., cols, rows, 0)` builds a zero-length texture array; it only surfaces two stages later as an AIOOBE from `getTexture(0)`. | `DiverSpriteTest.the sheets are declared with the argument order the engine actually has` |
| **`maxMipLevels = 0`** | `glTexStorage3D` with `levels = 0` is `GL_INVALID_VALUE`, so no storage is allocated and every later upload fails too. `1` means "one level, no mips". | same test, which reads `maxMipLevels` back off the asset |
| **Asynchronous sprite upload** | `SpriteSheet.textures` is populated in `onUploaded`, several frames after `engine.asset.load`; `getTexture` before that *throws*. Everything drawing an asset must gate: `DiverSprite.sheetsReady()`, `RockFace`/`Backdrop`'s ready flags, `LightEmitter.emitter()` / `MoteSprite.sprite()` falling back to `Texture.BLANK`, `PearlNormalMap.normals()` returning null. | `DiverSpriteTest`, `BackdropTest.no layer is ready before the engine has uploaded it`, `LightEmitterTest` |
| **`diver-normal.png` / `oxygen-normal.png` / `sandbank-normal.png` must keep their hyphen** | The engine's `loadAll` auto-loader keys on the substring `_normal` and, when it matches, forces `RGBA8` with 10 mip levels. Mip generation averages across cell boundaries, so adjacent frames of the loop would bleed into each other. | `DiverSprite.kt:51`; the same note is repeated in `PearlNormalMap.kt:280`; `SandBankTest.neither committed filename contains an underscore before normal` |
| **A normal map baked in the wrong axis** | `assets/sandbank/normal.png` is world-space Y-up (a seabed rendered top-down), so shipping it unswizzled lights the floor like a wall. Silent in every test and in every still frame; nothing downstream complains. `tools/build_backdrop.py`'s `swizzle_yz` does the y-z swap, with no sign flip - `+y = up` is settled by `iridescence.frag:258` and `PearlNormalMap.kt`'s capture probe. | `SandBankTest.the committed normals are unit length, Z-dominant and face up`, which separates all three failures (swizzle dropped / sign flipped / sRGB decode dropped) |
| **A light's falloff must live in ALPHA, not colour** | `drawLight` takes the emitter's shape from the texture's alpha, and `Texture.BLANK` means "no texture" - so the whole quad emits, corners included. But GI samples a light's *colour* where a ray **hits** it, i.e. on its rim, so an RGB ramp reaching zero at the rim scales all escaping light to zero. | `LightEmitterTest.the generated colour is flat, because GI samples a light's colour on its rim` |
| **A light emitter's SIZE, not just its shape** | A light cannot avoid illuminating its own body, and the emitter quad is a region that rasterises into the scene. At body scale you see the emitter instead of the light. `DIVER_LIGHT_SIZE_METRES` is deliberately decoupled from the diver's height and must not be re-tied. | `DiveLightingTest.the torch emits from a quad far smaller than the diver, not from one the size of him` |
| **Renderer attach order on one surface** | Batch renderers flush in **add** order and all of them write depth, including at alpha 0. Whichever was added first wins the depth test wherever two overlap. | `SurfaceRendererOrderTest` |
| **The GI composite is a MULTIPLY** | Settled - do not reopen it. `GlobalIlluminationSystem.onUpdate` (not `onCreate`) builds a `MultiplyEffect` on `getSurface("main")`. The reflectance floor bites on **our albedo**, which is why an outline drawn as albedo cannot survive the deep. `final.frag:59`'s `base + light` is the *light map assembling itself*, on `gi_light_final`, not the composite. | CLAUDE.md platform constraints (full citation) |
| **A second writer of `mainCamera`** | The shipped world-offset-from-HUD bug. A scene `Camera` entity rewrites the shared camera every fixed tick from a viewport frozen at `onCreate`. | `MainCameraOwnershipTest` (exact set equality), `CameraInvariants` at runtime |
| **A pixel count in world code** | Sizes in `render/` are metres; only `CameraRig` turns a metre into a pixel. Screen-space code uses fractions of surface **height**. | `CameraInvariants` rule 3 is the only thing that catches a width-derived scale |

---

## 6. Seeing it actually run

Tests passing is not evidence the game looks right - a lot of this project's bugs were
invisible-in-tests rendering faults. The full procedure is in CLAUDE.md under *Seeing it actually
run*; the two-line warning that costs the most time when ignored:

> **`EPT_SCREENSHOT` is not passive and its output is not the frame.** `ScreenshotEffect
> .getTexture()` returns `RenderTexture.BLANK`, so **any surface it is attached to composites as
> blank** - with it set, the sky surface came out black in the running game. And it dumps each
> surface *separately* (`-0` world, `-hud`, `-sky`); recompositing those by hand does not
> reproduce the engine's frame.

Use it only to inspect one surface's raw contents (its alpha channel is genuinely useful - that
is how the quad-edge slivers were found), and take anything about *appearance* from a real screen
grab. The JVM window has no bundle identifier, so the computer-use MCP filters it out of its
screenshots entirely; the `screencapture` shell route in CLAUDE.md is the one that works.

### Dev environment variables

Every one is read once at startup and is unset at the booth, so each costs one `getenv`.

| Variable | Read at | Effect |
|---|---|---|
| `EPT_DEV` | `EnPustTil.kt:933` | Forces `logLevel = DEBUG` (works even against a built release `.exe`), adds `MetricViewer` (F3), draws the gamepad diagnostic overlay, and runs `CameraInvariants` once a second. |
| `EPT_DEPTH` | `EnPustTil.kt:1102` | Pins the diver at a depth via `DiveSim.debugSetDepth`, turning the attract screen into a deep-water capture rig. Coerced into `0..MAX_DEPTH`; a typo is inert. It moves the diver, it does not change the rules. |
| `EPT_EDITOR` | `EnPustTil.kt:1172` | Registers and starts the engine's `SceneEditor`. Note it drives `mainCamera` through its own `Camera2DController` and therefore fights `CameraRig` every fixed tick - loudly (you cannot pan), never at the booth. |
| `EPT_WAVE_PHASE` | `EnPustTil.kt:1369` | Pins `WaterSurface`'s render-clock phase for reproducible captures. |
| `EPT_MOTE_PHASE` | `EnPustTil.kt:1374` (`Motes.PIN_ENV`) | Pins the mote field's phase, same reason. |
| `EPT_SCREENSHOT` | `EnPustTil.kt:1376` | Attaches `ScreenshotEffect` to `main`, `"hud"` and `"sky"`. Filenames derive via `outputPath.replace(".png", "-$index.png")`, so a value with no `.png` in it writes a file with no extension. Read the warning above first. |

(`EPT_SHAFT_PHASE` pinned the god rays and went with them on 2026-08-17.)

---

## 7. Shaders

### `src/main/resources/shaders/` - the game's own

| Pair | Drawn by | What it does |
|---|---|---|
| `iridescence.{vert,frag}` | `IridescenceRenderer` | The pearl, the anglerfish lure, and on the HUD the air-ring bubbles and the BANKED pearl icon. One program on two surfaces in two coordinate spaces; the only space-dependent input is the torch position, handed in per instance in that surface's own units. It also computes the pearl's hemisphere normal, which `PearlNormalMap` regenerates because a `BatchRenderer` draws to one surface and GI reads normals from a *texture*. |
| `water.{vert,frag}` | `WaterRenderer` | The sea's surface seen from below. Its **alpha is the waterline** - above the wave it discards and the sky shows through, below it it is opaque and hands off to the zone bands. One edge, so there is no second edge to keep in sync. |
| `opaque_water.{vert,frag}` | `OpaqueWaterEffect` | A post pass that forces `main`'s alpha to 1 below a gate depth. The gate is on the fragment's **position**, never on its alpha value. |

All three target `#version 330 core` - macOS caps OpenGL at 4.1 - and all three live outside the
engine's `pulseengine/` namespace so they neither shadow an engine file nor get caught by the
release jar's exclusion list.

### `src/main/resources/pulseengine/shaders/renderers/{quad,line}.vert` - the two shadowed files

These are copies of engine files, pinned to 0.13.0, that repair `Surface.drawQuad` / `drawLine`
on macOS. Three changes and nothing else: `in uint color` -> `in float color` (matching the
`GL_FLOAT` binding the engine actually performs), `unpackAndConvert(color)` ->
`unpackAndConvert(floatBitsToUint(color))`, and `#version 150 core` -> `330 core` (required by
`floatBitsToUint`; the version was *not* the bug).

**The release jar deliberately keeps the engine's broken copies.** `build.gradle.kts` has an
explicit `devOnlyShaderOverrides` exclusion, dropping our copies by their **source directory**
rather than by merge position, because with `duplicatesStrategy = INCLUDE` the outcome would
otherwise be decided by the order of the `from(...)` lines. The reasons: the booth `.exe` must
stay bit-for-bit the rendering behaviour that was playtested, and the bug is unverified on
Windows with no Windows machine here to check it on. Since the game never calls `drawQuad`,
shipping the override buys a player nothing. **So on the cabinet `drawQuad` is still the broken
stock version** - which is exactly why "simplify `fillRect` into `drawQuad`" is a trap that would
look fine in dev and fail in front of a queue.

### Tests that assert shader source against Kotlin constants

These read the `.vert`/`.frag` files off disk and compare them to what the Kotlin side binds and
uploads, which is the only way to catch a silent interface drift:

- `IridescenceShaderTest` and `WaterShaderTest` - every bound attribute is declared with the
  matching GLSL type, the declared instance stride matches the floats actually written, every
  uniform set exists and every uniform declared is set, the two stages agree on their interface,
  both target GLSL 330, and the release jar's exclusion list does not name our shaders.
- `OpaqueWaterShaderTest` - the same uniform round trip, plus that the alpha gate is on position
  and that the colour channels pass through unmodified.
- `ShaderOverrideTest` - our `quad.vert` / `line.vert` differ from the engine's **only** by the
  attribute-type fix.
- `PearlNormalMapTest.the generated normal is the iridescence shader's own hemisphere` - the
  generated texture agrees with `iridescence.frag`'s formula, read from source.
