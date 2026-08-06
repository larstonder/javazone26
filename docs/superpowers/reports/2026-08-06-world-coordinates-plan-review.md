# Review — `2026-08-06-engine-world-coordinates.md`

**Reviewed at:** `c47a4b0`, branch `feat/en-pust-til-gameloop`, 237 tests passing.
**Reviewed against:** engine sources at `…/scratchpad/pulseengine-src/`, the shipped `pulse-engine-0.13.0.jar`
(shaders and `LineNumberTable`s verified byte-identical to the source tree), and `…/scratchpad/caesars-salads/`.

---

## Verdict

**Stages A–C (Tasks 1–9): approve with required changes.** The coordinate migration is well-reasoned,
the diagnosis in §1.1 is correct, and the sequencing argument for Stage B being atomic holds. Three
required changes below, none of which threaten the design.

**Stage D (Tasks 10–14): do not proceed as written.** Its central mechanism does not work, and its
scene-loading step is internally contradictory in a way that would break GI at the booth. This is not
"I'd have done it differently" — it is two engine facts the plan read past.

### The three things that matter most

1. **`set(HIDDEN)` makes a Look prototype unselectable, uninspectable and invisible in the viewport.**
   Every path in `SceneEditor` that reaches the Inspector filters `isNot(HIDDEN)` — Outliner selection
   (`:267`), viewport click (`:597`), rubber-band (`:668`) — and so does the `@Icon(showInViewport)`
   billboard (`:436`, `:454`). The Look prototype is *defined* by `set(HIDDEN)` in `init{}` (§2.6,
   Task 13 Step 1). So "select `PearlLook` in the Outliner and tune it" — the entire payoff of the
   stage, and the thing that justifies six entities instead of seventy-five — **does not work**. The fix
   is one line (drop `HIDDEN`; an empty `onRender` already draws nothing), but an executor following the
   plan literally builds an unusable layer and discovers it at Task 13 Step 4.

2. **Task 10 duplicates the scene systems.** It specifies `dive.scn` as "an empty scene with the two
   systems" *and* leaves `DiveLighting.setup`'s `addSystem(EntityRendererImpl())` /
   `addSystem(GlobalIlluminationSystem())` untouched. `SceneManager.addSystem` is a bare `list.add`
   (`SceneManager.kt:251-252`) — no dedup. Two `GlobalIlluminationSystem`s means two sets of nine
   surfaces, two `MultiplyEffect`s, and `DiveLighting.gi` pointing at whichever instance it constructed
   rather than the one the editor's panel edits. Confirmed against the reference: `level_1.scn` serializes
   `GlobalIlluminationSystem`, `BloomSystem`, `ColorGradingSystem`, `PhysicsSystem` and `EntityUpdater`.
   The plan never decides who owns the systems, and the first Ctrl+S creates the problem even if the
   hand-authored file starts without them.

3. **`MainCameraOwnershipTest` goes red at Task 2, and the plan never says so.** Its second test fails
   on *any* production source containing `mainCamera` after comment-stripping. Task 2 Step 5 adds exactly
   that to `EnPustTil.kt`; Task 3 adds `CameraRig`. Neither task lists the test, and Task 4 Step 4's
   `./gradlew test` → `PASS` is unachievable. Worse, Task 5 deletes the guard outright at the moment a
   *second* legitimate writer exists (the editor's `Camera2DController`, added in `c47a4b0`) — the
   invariant becomes "exactly one writer at a time" with nothing enforcing it.

---

## Claim verification

Verified independently against the engine sources; the plan's citations were not taken on trust.

| # | Claim | Verdict | What I checked |
|---|---|---|---|
| 1 | Runtime-spawned entities never appear in the Outliner | **CONFIRMED with correction** | `SceneEditor.kt:336-343` is as quoted; `Scene` is `open class` with no `hashCode` override (`Scene.kt:17-21`) so it is an identity hash; `insertEntity` (`Scene.kt:42-61`) notifies nobody. **But "never" is false**: the Outliner window's close button removes it from its parent (`UiElementFactory.kt:150`), and reopening it from Windows → Outliner rebuilds it and calls `reloadEntitiesFromActiveScene()` (`SceneEditor.kt:276`). The plan's list of `addEntities` callers also misses `:565` (Ctrl+D duplicate). **The design conclusion survives** — see finding 12 for why it survives for a better reason than the one given. |
| 2 | All viewport interaction is gated on `state == STOPPED` | **CONFIRMED, verbatim** | `SceneEditor.kt:345` is exactly `if (enableViewportInteractions && engine.scene.state == SceneState.STOPPED)`, wrapping `cameraController.update`, `handleEntityTransformation`, `handleEntityCopying`, `handleEntitySelection`, `handleEntityMoving`. Focus request at `:330-334` likewise. Note the icon/gizmo *render* pass (`:431`) is **not** state-gated. |
| 3 | `BackToFrontEntityComparator` intransitivity crashes TimSort at n ≥ 32 | **PARTIALLY CONFIRMED; the causal story is REFUTED** | Comparator and TODO quoted verbatim and correctly (`EntityRenderer.kt:132`, `:155-158`); the sorted collection is a plain `ArrayList` (`:152`) so `sortWith` → `Collections.sort` → `Arrays.sort` → TimSort, confirmed by disassembly. Intransitivity is provable and was demonstrated on the plan's own example. n ≥ 32 is the correct and *tight* floor (exhaustive over all 3ⁿ arrangements for n ≤ 15; 20 M random trials at n = 31 → zero throws). **But throwing is probabilistic, not certain**: at n = 32 ≈ 7 % of arrangements, at n = 70 ≈ 33 %. And it only bites for a *total z spread of roughly 2e-4 … 1e-3*. Both idiomatic layering schemes — discrete layers ≥ 1e-4 apart with sub-1e-4 jitter, and plainly separated z — measured **0 %** at every N up to 50 000. So "the moment someone jitters pearl `z` for layering, the booth cabinet crashes" is not supported. One thing the plan *misses* and should say: inside the danger band, 100 % of the runs that did **not** throw came out with a strict ordering inversion — the choice is crash-or-mis-layered, not crash-or-correct. |
| 4 | `engine.scene.start()` is not needed | **CONFIRMED** | `SceneManagerImpl.update()` ends `activeScene.update(engine)` unconditionally (`:206`); `fixedUpdate` (`:224`) and `render` (`:229`) likewise. `Scene.update` inits (`SceneSystem.init` → `onCreate`) and updates every enabled system with no `SceneState` test (`Scene.kt:78-97`); `Scene.render` the same (`:110-114`). `GlobalIlluminationSystem` overrides only `onCreate`/`onUpdate`/`onFixedUpdate`/`onDestroy` (+`:257`) — **no `onStart`**. So the `DiveLighting.kt:141-143` comment is indeed false in its second half, and Task 10 Step 1 is correct to fix it. |
| 5 | `EntityRendererImpl` has no culling and allocates per frame | **CONFIRMED (no culling) / OVER-STATED (allocation)** | No frustum test, no `SpatialGrid` query, no bounds check anywhere in the 159-line file; `forEachEntityTypeList` is a flat walk. Type test on element 0 only (`:95-96`), `HIDDEN` but not `DEAD` (`:100`), sort at `:132`, dispatch at `:135` (plan says `:133-137` — that is the enclosing `when`). **But the queue is pooled, not rebuilt**: `taskPool` (`:76`), `createRenderTask` allocates only when the pool is empty (`:114-121`), `entities.clear()` retains capacity (`:138`), task returned to pool (`:140`), `forEachFast` is `inline`. The only steady-state garbage is inside `sortWith` — which allocates nothing below n = 32 and is skipped entirely below n = 2. At Stage D's n = 1 the entity path allocates **zero** per frame. |
| 6 | GI's `onCreate` early-returns at `:184` without an `EntityRenderer` | **CONFIRMED — and keeping `EntityRendererImpl` is not necessary** | Verified in source and in the shipped bytecode (`dup / ifnonnull / pop / return` before the five `addRenderPass` `invokevirtual`s). All nine GI surfaces are created at `:76-182`, before the return, and `GiSceneRenderer` is attached at `:86`. The skipped passes only route *scene entities*; immediate-mode `drawLight` bypasses render passes entirely, and `onUpdate`'s `MultiplyEffect` install has no dependency on them. So GI works fine with no `EntityRenderer` at all. Keeping it is defensible conservatism; §4.10's "load-bearing twice" is really load-bearing **once** (it draws `DiverEntity`) — under §4.11's own decision that `DiverEntity` implements `Renderable` only, GI's five passes dispatch to nothing either way. Real hazard the plan gets right: the lookup happens during GI's `onCreate`, so the `addSystem` ordering note in §4.10 is correct and worth keeping. |
| 7 | `aoRadius` × `camScale` is a regression risk once scale goes 1 → ~30 | **CONFIRMED IN EFFECT; mechanism INVERTED** | `ao.frag:36` is exactly `float radius = aoRadius * camScale;` and the default is `30f` (`GlobalIlluminationSystem.kt:57`) — but the upload site is **`GiAo.kt:48`**, cited nowhere in the plan. Working the units out: `ray` is in SDF texels (`ao.frag:53-64` divides by `localSdfTexRes`; the SDF is fragCoord-based, `sdf.frag:14,20`), and `camScale` is `mainCamera.scale.x` = px per world unit. So `radius_world = aoRadius / localSceneTexScale` — **`camScale` cancels, and the AO radius is already scale-invariant in world units.** The regression is real but for the opposite reason: the *world unit* changes from 1 px to 1 m, so the unchanged default becomes `30/0.25 = 120` **metres** — twice the visible column, i.e. AO degenerates into full-screen darkening. See finding 4: the plan's proposed compensation is also wrong for this project. |

Two claims I checked that the brief did not list, both **CONFIRMED**: the `scene.vert:86-88` minimum-quad-size
clamp is scale-invariant (`screenSpacePos.w` is always exactly 1.0 for an affine ortho, so the floor is a
constant number of screen pixels), and `jitterFix` (`GiSceneRenderer.kt:150-162`) is inert today and engages
after the migration. Non-zero light `radius` (`radiance_cascades.frag:120-127`) is **not** scale-invariant —
`radius·camScale/dist²` has dimension 1/length — so §1.7's note that its meaning changes is right and worth
keeping.

---

## Findings, by severity

### Blockers

**B1 — `HIDDEN` kills the Look prototypes.** (Claim table row 1's neighbourhood; §2.6, Task 13 Step 1.)
`isSet(flag) = flags and flag == flag` (`SceneEntity.kt:28`), so `SceneEditor.kt:267`'s
`it.isSet(SELECTED or EDITABLE) && it.isNot(HIDDEN)` requires SELECTED **and** EDITABLE **and** not HIDDEN.
A HIDDEN entity therefore never enters `entitySelection`, so `selectSingleEntity` — the only thing that
builds the Inspector (`:954-1017`) — is never called for it. `:436` and `:454` mean the
`@Icon(showInViewport = true)` fallback the plan proposes for finding it also does nothing. The one
unfiltered path is `createNewEntity` (`:933`), which inspects it once at creation and never again.
*Required change:* Look prototypes must not be `HIDDEN`. An empty `onRender` is sufficient and costs one
`isNot` test plus one virtual call per frame. Then also re-read §1.8.2's "`EntityRendererImpl` checks
`HIDDEN`" — that is true but is no longer the mechanism being used.

**B2 — Task 10 duplicates the scene systems, and never decides who owns them.** Detailed in the verdict.
There is a second half to this that the plan does not touch: `DiveLighting.setup`'s `lightTexScale = 0.25f`
and `dithering = 0.6f` carry ~20 lines of measured justification. Once GI is authored in `dive.scn`, those
values live in a single-line JSON blob and the comments explain settings that no longer apply, or the
authored file silently overrides them. *Required change:* Task 10 must state explicitly whether systems are
Kotlin-owned or scene-owned, and if scene-owned, where the justification comments go.
*Recommended:* keep them Kotlin-owned and keep `createEmptyAndSetActive` — see "What I would cut".

**B3 — `MainCameraOwnershipTest` breaks at Task 2.** Detailed in the verdict. *Required change:* Task 2 must
either amend the guard to allow-list `CameraRig.kt` + the dev-only invariant read, or delete it there with a
note. And Task 5 should not simply delete it: the correct successor is "no production source outside
`CameraRig` writes `mainCamera`", which is still a real, red-testable guard and still guards a real bug.

### Major

**4 — the `aoRadius` compensation reintroduces a pixel-count constant.** Task 6 Step 2 proposes
`aoRadius = 30 / CameraRig.pixelsPerMetre(h)`, which pins the AO radius at a constant **120 screen pixels**.
That is exactly what `CLAUDE.md` forbids ("Express sizes as a fraction of screen **height**, never … a pixel
count"), and it is resolution-dependent in world terms: 4 m of water at h = 1800, 8 m at h = 900. Since the
shader is already scale-invariant in world units, the correct fix is a constant metre value —
`aoRadius = desiredAoMetres * localSceneTexScale` (e.g. `1f` for a 4 m radius) — set **once**, not per frame
from the surface height. Also: Task 6 Step 1 tells the executor `ao.frag:36` "computes `radius = aoRadius *
camScale`, so a ~30× change should be obvious". Someone who then reads the shader and finds the multiply is a
world→texel conversion may conclude the whole task is spurious and skip it, or "fix" it by removing the
multiply — which would *introduce* zoom-dependence. Rewrite the mechanism paragraph.

**5 — §3 over-states its critique of `ViewportTest`, and deletes four tests that can fail.**
"Every assertion in it is of the form … `f(x)·h/h == f(x)·h/h`" is true of tests 1, 2, 3 and 8. It is not true of:
- `the diver is large enough to see` — `fraction > 0.02f`; mutating `DIVER_SIZE_METRES` 3 → 1 gives 0.0167 and fails.
- `only part of the water column is visible so descending scrolls` — `VISIBLE_DEPTH_METRES < MAX_DEPTH * 0.6`; raising 60 → 100 fails.
- `x is centred and scales with the display` — asserts the literal `600f`; removing the `* 0.5f` centring fails.
- `camera keeps the diver above the top edge of visible water` — asserts `VISIBLE_DEPTH · DIVER_SCREEN_FRACTION`; mutating either constant fails.

All four are pure `Framing`-constant assertions that survive the rename verbatim, and **none** is covered by
the proposed `CameraRigTest`. Task 5 Step 2's guidance — "make sure `CameraRigTest` covers the properties
worth keeping (they are the 'same fraction on every display' ones); the rest were assertions that could not
fail" — is precisely inverted. *Required change:* keep those four in `FramingTest`; delete the other five.

**6 — two of `CameraRigTest`'s five assertions cannot fail.** "the visible horizontal extent,
`w / CameraRig.pixelsPerMetre(h)`, equals `w/h * 60`" reduces to `w / (h/60) = 60w/h` — an algebraic identity
that holds for any value `pixelsPerMetre` returns. "a given world point lands at the same fraction of `h` on
all of them" is the same `f(x)·h/h` shape §3 condemns two paragraphs earlier. The two assertions that *are*
real are the `(W/2, 0)` / `(W/2, H)` pins and the parity grid against the old `Viewport` formulas. A section
whose thesis is test honesty should not ship two tautologies; either cut them or label them as documentation.

**7 — the `EntityUpdater` guard has a hole exactly where Stage D moves configuration.** Systems serialize into
the `.scn` — confirmed: `level_1.scn` contains `no.njoh.pulseengine.modules.scene.systems.EntityUpdater`.
Task 11's scan looks for `EntityUpdater` in Kotlin `addSystem(` calls; `SceneFilePurityTest` checks entity type
names only. An `EntityUpdater` added from the editor's Scene Systems panel and Ctrl+S'd into `dive.scn`
bypasses both — and §2.6 rule 2 calls that guard "the *mechanism*" that makes "an entity provably cannot evolve
state" structural rather than aspirational. It is not, as specified. *Required change:* `SceneFilePurityTest`
must allow-list **systems** as well as entities, and reject `EntityUpdater` by name.

**8 — the comparator paragraph is instructed to be frozen into the source verbatim.** Task 13 Step 1 says
`PearlLook`'s class doc must carry risk 4.14 "**verbatim**". Given the claim table row 3 result — throwing is
~33 % at n = 70, not certain, and both idiomatic layering schemes are provably safe — that would write a
demonstrably over-stated engine claim into a comment, which is the exact failure mode this project has been
burned by twice (`Draw.kt`'s `#version`, `DiveLighting.setup`'s GI-camera). Restate it accurately: the
comparator violates the `Comparator` contract for `|Δz| < 1e-4`; TimSort can throw for n ≥ 32 and cannot below;
the danger band is a total z spread of ~2e-4…1e-3; outside it the sort silently mis-orders instead. The
*conclusion* (don't convert pearls) does not depend on the over-statement — the culling loss and the Outliner
argument carry it on their own.

### Moderate

**9 — Task 10's load path does not resolve where the plan thinks.** `DataImpl.getFile` is
`File(filePath).takeIf { it.isAbsolute } ?: File("${getSaveDir()}/$filePath")`, so
`loadAndSetActive("dive.scn", fromClassPath = false)` in dev looks in **`saveDirectory`** — next to
`scoreboard.json` — not the source tree. §1.8.6 gets this right for *saving* and the load snippet in §2.6 and
Task 10 Step 2 does not apply it. Separately, `loadAndSetActive` is `loadObject<Scene>(...)?.let { setActive(it) }`
(`SceneManagerImpl.kt:85-88`) — **it returns `Unit` and has no failure signal**, so the specified "fall back to
`createEmptyAndSetActive` with a `Logger.warn`" has no described mechanism. (`try/catch` will not work; there is
no throw.) The executor would have to check `activeScene.fileName` afterwards, or call `engine.data.loadObject`
directly. Say which.

**10 — guard #3's regex is red out of the box, and matches comparisons.**
`\b(sim|pearl|pocket|fish)\.\w+\s*=` over `src/main/kotlin/**.kt` (Task 11 Step 1's stated scope) matches
`dive/DiveSim.kt:218` `pocket.usedThisDive = true` and `:232` `pearl.collected = true` — legitimate internal sim
writes. §2.6 rule 4 scopes it to `render/`; Task 11 does not. It also matches `==` (`sim.depth == 0f` contains
`sim.depth =`), though not `!=`/`>=`/`<=`. Scope it to `render/` and exclude `==`.

**11 — `EntityBridge` has three signatures and a contradiction about who owns the diver's size.**
§2.6 says `EntityBridge.push(engine, sim)`; §3(e)1 says `EntityBridge.pushDiver(entity, sim)` "or
`(x, depth, heldMass) -> (x, y, w, h)`"; Task 12's Interfaces block says
`EntityBridge.pushDiver(entity, x, depth, heldMass)`; Task 12 Step 3 says `EntityBridge.push(engine, sim)`.
Worse than the naming: Step 1's test asserts `width == height == Framing.DIVER_SIZE_METRES + heldMass * 0.03f`,
while Step 2 gives `DiverEntity` authored `@Prop var baseSizeMetres` and `@Prop var massSizeGain`. If the push
uses the `Framing` constants, editing those props does nothing — and "it is the thing that gets iterated on most"
is the stated reason the diver became an entity at all. Decide: the entity's props are the source of the size and
the bridge pushes only position, or the bridge pushes size and the props are dead. The former is the only version
consistent with §2.6's "entities own how things look".

**12 — the strongest argument against pearls-as-entities is not the one made.** Claim 1 is confirmed for
automatic refresh but "never appear" is refutable by closing and reopening the Outliner window. The argument that
cannot be refuted is simpler and is not in the plan: **the pearls are regenerated from `dailySeed` on every run,
so any value tuned on one is discarded at the next `lifecycle.justStarted`** — and anything that *did* persist
would be the §1.8.6 pollution hazard. State that; it does not depend on any editor implementation detail.

**13 — §1.7's enumeration of `camScale` consumers is incomplete.** `GlobalIlluminationSystem.onFixedUpdate`
(`:220-231`) copies `mainCamera.position`/`rotation`/`scale` into `GI_GLOBAL_SCENE`'s camera whenever
`traceWorldRays` (default `true`). Harmless for us today — nothing is drawn to that surface — but it is a fourth
consumer, and `DiveLighting`'s own existing comment already cites `:227-229` while the plan's table does not.
Note also `GI_GLOBAL_SCENE` is the only GI surface created *without* `camera = mainCamera` (`:90`), so §1.2's
"six of its nine surfaces" is right and worth keeping as stated.

**14 — §4.14's "TimSort allocates every frame, forever" is wrong at Stage D's scale.** Per claim table row 5, the
queue is pooled and `sortWith` allocates nothing below n = 32 and is skipped below n = 2. With one visible entity
the entity path is allocation-free. The rule-violation framing should be scoped to "if pearls were converted",
which is what §4.14 is arguing anyway — but as written it reads as though Stage D itself breaks `CLAUDE.md`'s
no-allocation rule, which would be a reason to reject the stage on its own terms.

**15 — nothing in the plan mentions entity class registration.** Task 12 Step 4 says to author `DiverEntity`
"in the editor", which requires it to be in `SceneEntity.REGISTERED_TYPES` — populated by
`registerSystemsAndEntityClasses` (`SceneManagerImpl.kt:250-267`) from
`gameBasePackage = game::class.java.packageName.substringBefore(".")` (`:41`). `EnPustTil` has no package
declaration, so that is `""`, and `ReflectionUtil.getClassesInPackages` explicitly special-cases the empty
package to walk the whole classpath root (`:38, :55`) — so `render.entities.DiverEntity` **will** be found. It
works, but by accident: moving `EnPustTil` into any package silently drops our entity types from the editor's
create list. Worth one line in Task 12.

**16 — currency.** The plan is current with `6b05f07` (Task 10 Step 4 correctly notes the viewport grid draws
because of it). Against `c47a4b0`, one instruction is stale: Task 10 Step 3 says to "drop the stale `dive.scn`
holds exactly one entity, GI's Camera'" — that text does not exist; `EnPustTil.kt:379-385` already says
"`dive.scn` currently holds NO entities at all". The `EnPustTil.kt:373-377` citation *is* accurate. Test counts
are stale throughout (233 → 237; `ShaderOverrideTest` added four).

### Constraint enforcement — does the plan enforce, or only assert?

| Constraint | Enforced? |
|---|---|
| `src/main/kotlin/dive/` does not change | **Yes.** The `no.njoh.pulseengine` import scan is real and red-testable, and the DoD's "byte-identical" is checkable with `git diff --stat`. A reviewer of the diff would see a violation immediately. |
| Entities are views; state flows one way | **Partly.** The `EntityUpdater` absence is a genuine structural mechanism and the plan is right to lean on it — but the guard has hole 7, and contradiction 11 means the plan has not actually decided what one-way flow means for the diver's size. Signals 5 and 6 are review-only and honestly labelled as such. |
| Placement stays procedural, never authored into `dive.scn` | **Yes, and this is the strongest thing in the plan.** `SceneFilePurityTest` would fail in CI, names the specific types, caps the entity count so the diff stays reviewable, and Task 11 Step 3 requires every guard be seen red. Task 12 Step 7's deliberate Ctrl+S-while-running is the right empirical check. |

### Sequencing

Stage B's atomicity claim is **correct**: the world, the GI light quads and the HUD anchor all go through
`mainCamera`'s single per-frame `viewMatrix` (built in `gfx.initFrame` before any game code), so flipping any
one of them alone produces a garbage frame. §1.5's three frame-ordering consequences check out against
`PulseEngineImpl`.

Stage D's dependency on Stage B is also correct and for the stated reason. **Task 10's claimed independence is
right** — it touches only scene creation and start — but B2 means it is not shippable as written. Two ordering
errors: Task 2 and Task 3 are both blocked by B3, and Task 6 as written should not be attempted before its
mechanism paragraph is corrected (finding 4).

---

## Scope for the actual deadline

Four weeks; art not started; Task 1 already shipped and already closed the reported bug. My reading:

**Do:** Tasks 2–5 (Stage B) plus Tasks 7–9. That is the change that pays for itself, and the argument in §5 for
doing it *before* the art lands is sound — every sprite authored in screen pixels is a sprite that gets
re-expressed later. With findings 5 and 6 applied, the test story is honest. Budget is closer to one day than
two once `CameraRigTest` loses its two tautologies.

**Rework:** Task 6, per finding 4 — a constant metre value set once, not a per-frame pixel count.

**Cut:** Tasks 11–14, and reduce Task 10 to its safe core. Concretely: keep `createEmptyAndSetActive`, keep the
Kotlin `addSystem` calls, and ship only (a) the two corrected comments, (b) the conditional `engine.scene.start()`,
(c) the `CameraRig.apply` gate while the editor service is running, (d) the `sim.tick` freeze while STOPPED. That
is roughly twenty lines, it has no `.scn` ownership question, and it delivers the *one* editor capability that
demonstrably works today: the Scene Systems panel, where `aoRadius`, `dithering` and `lightTexScale` are live
`@Prop`s on the running `GlobalIlluminationSystem`. Given finding 4, being able to drag `aoRadius` and watch the
frame is worth more this month than a `PearlLook` would be.

**Why cut the entity layer rather than fix it:** B1 and B2 are both fixable, but the stage's payoff is "tune a
pearl's size without rebuilding" — and there is no art yet, so nobody knows which numbers need tuning. Spending
1.5–2 days building the slot, plus the rework, buys a workflow for values that are currently four well-commented
Kotlin constants. Revisit it when there is a sprite to tune. The plan itself names Stage D as the first thing to
drop; I agree, and I would drop it now rather than at the deadline.

---

## Preferences, not defects

Brief, and separable from everything above.

- `Viewport` → `Framing` to force the compiler to enumerate call sites is a good move and I would keep the name.
- I would put `CameraInvariants`' live check behind a key rather than a once-per-second timer, so it can be
  fired deliberately right after an ALT+ENTER toggle — which is the moment it exists for.
- §2.4's two-call `worldPosToScreenPos` trick to derive pixels-per-metre is neat and correctly justified; the
  shared-`Vector2f` warning is real (`Camera.kt:85`).
