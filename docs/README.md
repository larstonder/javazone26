# Documentation map - Én Pust Til

↑ [Repo root](../README.md) · [CLAUDE.md](../CLAUDE.md) · Package docs: [`dive/`](../src/main/kotlin/dive/README.md) · [`render/`](../src/main/kotlin/render/README.md) · [`score/`](../src/main/kotlin/score/README.md) · [`tools/`](../tools/README.md)

Sixty-odd markdown files live under `docs/` and `.superpowers/sdd/`. This page says which of
them binds, which merely records, and which one to open first.

Everything here was written during the build of a free-diving arcade game for the Capra booth at
JavaZone 2026 (2-3 September, NOVA Spektrum), Kotlin on Pulse Engine 0.13.0, shipped as a Windows
`.exe`.

---

## 1. Which document wins

Read this before trusting anything below. The four kinds of document here are not equal, and
several of them contradict each other on purpose.

| Rank | Kind | Authority |
|---|---|---|
| 1 | **The design spec** - [`specs/2026-08-04-en-pust-til-design.md`](superpowers/specs/2026-08-04-en-pust-til-design.md) | **LOCKED.** The single source of truth for rules, economy, controls and presentation. If the code and this document disagree about what the game *should* do, the document is right and the code is a bug. **section 17 is its amendment log** - every change made since the lock is recorded there with the reason, so section 17 overrides the body of the same document wherever the two disagree, and it says so explicitly in three places. |
| 2 | **`CLAUDE.md`** (repo root) | The agent-facing operational guide: architecture, package boundaries, coordinate spaces, draw helpers, and the platform constraints that fail *silently* if violated. It has no authority over game design, and full authority over how to work in this codebase. When it and a plan disagree about the engine, believe `CLAUDE.md` - it is maintained against the current tree. |
| 3 | **Specs other than the design spec** | Scoped and binding within their scope. The bake spec governs asset production; the outstanding-work spec is a handoff document, current as of 2026-08-11. |
| 4 | **Plans** - `docs/superpowers/plans/` | *Intended* implementation, written before the work. Some were executed in full, some in part, one was cut on review. A plan is never evidence of what the code does now. |
| 5 | **Reports** - `docs/superpowers/reports/` and `.superpowers/sdd/*/` | Empirical evidence from work already done: measurements, decompiled engine facts, playtest quotes. Trustworthy about *what was measured on the day*, and frequently describing a state the code has since moved past. Never take a report as a description of the present. |

Two practical consequences:

- **A number in a plan or a report is a historical measurement.** The number in the source file is
  the live one. Where the two disagree, the source wins and the doc is stale.
- **`dive/` is design; `render/` is presentation.** Gameplay changes in `dive/` need the owner's
  sign-off against the spec. Presentation changes do not, but they still get logged in section 17 when
  they move a gameplay constant (visible depth on wide panels is the standing example).

---

## 2. Read this first

Five documents, in this order. Together they are about two hours and they cover the whole project.

| # | Document | Why |
|---|---|---|
| 1 | [`CLAUDE.md`](../CLAUDE.md) | The architecture and the silent-failure list. **Read it first** — not reading it costs a day the first time you hit `drawQuad` or a `SpriteSheet` constructor. |
| 2 | [`specs/2026-08-04-en-pust-til-design.md`](superpowers/specs/2026-08-04-en-pust-til-design.md) | The game. Read sections 1 to 12 for the rules, then **read section 17 in full** - a third of what the body says has been amended. |
| 3 | [`specs/2026-08-11-outstanding-work.md`](superpowers/specs/2026-08-11-outstanding-work.md) | What is left, what only the owner can decide, and seven traps that have each already cost this project time. |
| 4 | [`reports/2026-08-06-drawquad-macos-investigation.md`](superpowers/reports/2026-08-06-drawquad-macos-investigation.md) | The single most load-bearing platform finding, with the controlled experiment behind it. Explains why game code cannot call `drawQuad`. |
| 5 | [`plans/2026-08-13-one-world-model.md`](superpowers/plans/2026-08-13-one-world-model.md) | The current thinking on lighting, and the clearest statement of why the frame looked incoherent. Short. |

If you are only touching the simulation, swap 4 and 5 for the design spec's sections 3, 4 and 6b plus
`dive/Tuning.kt`.

---

## 3. The design spec, section by section

Jump straight to the section that governs the thing you are changing. "Amended" means section 17 has
since changed or superseded it - check there before relying on the body text.

| Section | Subject | Note |
|---|---|---|
| 1 | Why the design exists: pearls have weight, so greed is felt through the stick, not read off a HUD | current |
| 2 | Core loop - one 90 s breath-hold, unlimited dives, pearls are *held* until you surface | current |
| 3 | Economy: `BANKED += HELD x (1 + maxDepth/30)`, plus the five-zone table (value, mass, air burn) | **amended** - Abyss air burn 2.5 -> 2.2; "pearls are the only light source" superseded |
| 4 | Buoyancy and weight - the mass model and its starting constants | **amended** - `baseAir` 20 -> 30 s; momentum and drag added; empty diver now hovers |
| 5 | Controls - stick, A to kick, B to bleed ballast, and why B is a dial not a switch | current |
| 6 | Air is never a number: a ring of bubbles that thins, going red at three | current |
| 6b | **Air vents** - one each in Kelp, Twilight and Trench, none in the Abyss, one use per dive | added by amendment; the deepest vent has since moved into the lower Trench |
| 7 | The anglerfish - lure looks like a pearl, the tell is motion, it never ends your run | **amended** - "the only light in the darkest zone" no longer holds; the tell is still motion |
| 8 | The three ways to lose (blackout / anglerfish / the clock) and the endgame gamble | current |
| 9 | The expert route - dive empty to lock the multiplier, collect on the way up; pearls persist | current |
| 10 | Map and content - one hand-authored column per day, learnable by spectators | **qualified** - placement is generated from `dailySeed`, not authored; section 17 says which half of it survives |
| 11 | Art direction - silhouette diver, deep blue to near-black, bioluminescence, Neptune-Duke as banker | **amended** - "shafts of light near the surface" removed 2026-08-17 |
| 12 | Feedback and presentation - the cash-out, the on-screen layout, the dive-profile leaderboard | **amended** - camera smoothing; point-of-no-return marker made zone-aware |
| 13 | Technical approach - determinism, no physics engine, pooled particles, leaderboard service | partly unbuilt: the leaderboard is a local JSON file (`score/`), not a service with a QR |
| 14 | What to prototype first, in risk order - buoyancy feel is #1 | historical; the feel gate passed (see the gameloop SDD ledger) |
| 15 | Cut list, in order. Do not cut: weight, the bleed button, the cash-out | current |
| 16 | Open questions - pearl density, how much the anglerfish eats, hard stop at 0:00, second screen | still open |
| 17 | **Amendment log** - every post-lock change with its reason and its measurement | the most important section in the file |
| - | *Platform findings that constrain implementation* (after section 17) | the HiDPI framebuffer trap and the `drawQuad` failure, in short form |

---

## 4. Specs

| File | Date | Subject | Status |
|---|---|---|---|
| [`2026-08-04-en-pust-til-design.md`](superpowers/specs/2026-08-04-en-pust-til-design.md) | 2026-08-04, amended through 2026-08-17 | The locked game design | **current** - the source of truth |
| [`2026-08-07-diver-spritesheet-bake-design.md`](superpowers/specs/2026-08-07-diver-spritesheet-bake-design.md) | 2026-08-07 | How 41 diffuse + 41 normal frames become two `SpriteSheet` grids: union bbox, grid search against the engine's texture buckets, sRGB-before-decode normals, byte-reproducible output | **current** for the bake. One stale detail: section 2 quotes `Framing.DIVER_SIZE_METRES` as 3 m; it is now `DIVER_HEIGHT_METRES = 9f` |
| [`2026-08-11-outstanding-work.md`](superpowers/specs/2026-08-11-outstanding-work.md) | 2026-08-11 | Handoff spec: what is left, the two owner decisions, seven traps, how to work here, and the booth risk | **current with stale passages** - see section 7 below |

---

## 5. Plans

| File | Date | Subject | Status |
|---|---|---|---|
| [`2026-08-04-en-pust-til-gameloop.md`](superpowers/plans/2026-08-04-en-pust-til-gameloop.md) | 2026-08-04 | The original nine-task build: test infrastructure, buoyancy, zones and scoring, seeded pearls, `DiveSim`, the playable feel gate, the anglerfish, the HUD, the lighting pass | **historical** - executed in full, but the document carries no status marks at all (76 unticked boxes). Completion evidence is external, in the SDD directory. Its buoyancy and lighting tasks were both **replaced during execution** - see `fix-buoyancy-report.md` and `lighting-rework-report.md` |
| [`2026-08-06-engine-world-coordinates.md`](superpowers/plans/2026-08-06-engine-world-coordinates.md) | 2026-08-06 | Move the world from screen pixels to metres through `engine.gfx.mainCamera`. Stages A-C (tasks 1-9), a reduced Task 10, and a deferred Stage D entity layer in section 9 | **partly executed, and the only plan that annotates itself.** Tasks 1-9 are marked done with commit SHAs in the top matter and in each task heading. Task 10 was reduced to ~20 lines; **Stage D (tasks 11-14) was cut on review** and moved verbatim into section 9. Two of its own predictions are recorded as measured and failed |
| [`2026-08-07-diver-spritesheet-bake.md`](superpowers/plans/2026-08-07-diver-spritesheet-bake.md) | 2026-08-07 | Eight-task implementation of the bake script and its QA bundle | **historical** - all eight tasks complete, sheets committed under `src/main/resources/sprites/`. Its Self-Review was amended after execution with an erratum, and instructs anyone re-running it to take the tests from the repo rather than from the document |
| [`2026-08-13-deep-water-lighting.md`](superpowers/plans/2026-08-13-deep-water-lighting.md) | 2026-08-13 | "It is too dark and the lights fight each other" - two symptoms with different causes, plus a measured baseline in section 2b | **superseded** by `2026-08-13-one-world-model.md`, which keeps its diagnosis and its sections 2b-2d measurements and replaces its section 3 interventions. Its own header still reads "plan only, nothing implemented" - that is now wrong: the depth pin and the mote move landed, and the ambient floor was built, measured as a no-op and reverted. It also carries an in-place correction retracting a model twice presented as validated |
| [`2026-08-13-one-world-model.md`](superpowers/plans/2026-08-13-one-world-model.md) | 2026-08-13 | Five incompatible lighting models replaced by one rule: everything is `albedo x irradiance`, stated in one unit, with real `1/d^2` falloff | **partly executed** - section 4 step 1 is struck through and marked DONE (`5dc5f7a`, distance falloff switched on); steps 2-5 are open. It also strikes through one of its own predictions as disproved by that step. Short, and still the best statement of intent |
| [`2026-08-14-engine-native-lighting.md`](superpowers/plans/2026-08-14-engine-native-lighting.md) | 2026-08-14 | Research: Pulse Engine ships a directional sun-and-sky daylight model that has been on by default and untouched since GI was added, and `drawOccluder` has never been called. Verdicts on all five hand-rolled mechanisms, plus dead ends written down so nobody looks again | **research and proposal, one probe measured and reverted.** Its section 5 migration is unstarted - nothing in `render/DiveLighting.kt` sets the GI sun/sky properties. **Step 3 landed independently** (`e33718a`, tone map with UNCHARTED2 rather than ACES). Its section 1.5 defaults table and its section 2 dead-end list are useful regardless |

---

## 6. Reports

| File | Date | Subject | Status |
|---|---|---|---|
| [`2026-08-06-drawquad-macos-investigation.md`](superpowers/reports/2026-08-06-drawquad-macos-investigation.md) | 2026-08-06 | Root cause of `drawQuad`/`drawLine` drawing nothing on macOS: a `uint` shader attribute bound as `GL_FLOAT`, yielding alpha 0. Not the `#version`, as first believed. Includes the reversed experiment and the fork-free shader-shadow fix | **current** - its conclusion is enforced by `DrawTest` and repeated in `CLAUDE.md`. Windows behaviour is still ~85% confidence, untested |
| [`2026-08-06-world-coordinates-plan-review.md`](superpowers/reports/2026-08-06-world-coordinates-plan-review.md) | 2026-08-06 | Adversarial review of the world-coordinates plan. Approves Stages A-C, rejects Stage D on two engine facts (a `HIDDEN` prototype is unselectable in the editor; the plan duplicates the GI scene systems), and re-verifies seven of the plan's engine claims independently | **historical, and its verdict was taken** - Stage D was cut. Its claim table is still the best single audit of engine behaviour in the repo |
| [`2026-08-14-black-discs.md`](superpowers/reports/2026-08-14-black-discs.md) | 2026-08-14 | "Are there still black discs?" Answer: zero, at eight pinned states, with a probe that turns them back on and off. section 4 proves no *third* cause can exist, from the draw calls themselves | **current as a conclusion**, historical as a recipe - its capture pins include `EPT_SHAFT_PHASE`, which no longer exists. section 6 also corrects `CLAUDE.md`: `EPT_SCREENSHOT`'s RGB is linear, not wrong |
| `2026-08-06-drawquad-macos-evidence.png` | 2026-08-06 | Three-panel screenshot evidence for the report above | supporting asset |

---

## 7. `.superpowers/sdd/` - the per-task record

Two directories, one per plan that was executed with the SDD workflow. The convention is the same
in both:

- **`task-N-brief.md`** - written *before* the task, and closer to an executable script than to a
  description. A **Files** block (create / modify / test), an **Interfaces** block with exact
  signatures, then red-green TDD steps as checkboxes: write the failing test, run it, confirm the
  exact failure message, implement, run, commit. Test and implementation code is given verbatim,
  so the implementer transcribes rather than designs. Spec sections are cited inline.
- **`task-N-report.md`** - written *after*. What was built, verbatim command output, a
  **mutation-testing table** (break the production code, confirm the test goes red, restore,
  confirm green, naming which test caught it), what was verified by *looking*, and a
  **Concerns / not verified** section. Reports routinely overrule their own brief and say so.
  Negative results are kept, not dropped - a surviving mutation is reported as surviving.
- **`progress.md`** - the controller's append-only ledger. One line per event: task complete,
  review verdict, fix round, deferred minor, plus the owner's playtest quotes verbatim.
  **This is where the reasoning behind odd-looking code lives** - the air vents, the feel gate and
  the buoyancy rewrite are all recorded here as they happened, with the arithmetic that forced them.
- **`review-<sha>..<sha>.diff`** - raw diffs, one per review round. Skip these; they are inputs.

**The two ledgers do not carry equal weight.** The bake ledger is complete and ends
`ALL 8 TASKS COMPLETE`. **The gameloop ledger is abandoned mid-Task-6** - tasks 7, 8 and 9 have
briefs and reports but no ledger entries, and none of the named reports below is recorded there
either. Its last line lists four findings as "STILL OPEN"; all four were in fact closed later, by
`task-8-report.md` and `cleanup-report.md`. Read it for the story up to the feel gate, then switch
to the named reports.

### Named reports - the ones worth reading

These are not tied to a numbered task. They are the investigations and reworks that happened
between tasks, and they hold the empirical evidence behind decisions that look arbitrary in the
source.

All eleven are in `.superpowers/sdd/2026-08-04-en-pust-til-gameloop/` except the last.

| Report | Subject, and what it holds |
|---|---|
| [`caesars-comparison.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/caesars-comparison.md) | **The biggest and most consequential.** 2026-08-05 divergence review against a sibling booth game on the same engine (3,546 LOC, zero tests), with every engine claim verified by decompiling the jar. Thirty numbered findings; the ones about the booth (a game-state machine, an unattended watchdog, rolling timestamped backups) set project policy, and its sections 4 and 5 record what we deliberately do *not* do - no scene-entity migration, no networked leaderboard, no occluder pass |
| [`fix-criticals-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/fix-criticals-report.md) | The three criticals in `dive/`: surfacing on the exact tick air expires was scored as a blackout (12,000 became 240); `maxDepthThisDive` - the entire economy - was untested and a mutation passed all 84 tests; and a sweep showing the air vent did *not* make the deep returnable for a laden diver |
| [`fix-buoyancy-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/fix-buoyancy-report.md) | The physics rewrite the owner demanded (*"I do not like constant sink"*). An empty diver is neutrally buoyant and hovers; carried mass is what makes you sink. Holds the speed-versus-mass measurement table and the exact load above which climbing is impossible. **This, not the plan's task 2, is the central mechanic's real implementation** |
| [`fix-gamepad-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/fix-gamepad-report.md) | Gamepad buttons were never read (axes only), so kick and bleed did nothing on the booth encoder and RUN OVER soft-locked without a keyboard. Records the decompiled fact that `Gamepad` has no `wasClicked`, which is why every consumer does its own edge detection. Marked DONE_WITH_CONCERNS and partly superseded |
| [`input-robustness-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/input-robustness-report.md) | Why a generic arcade USB encoder with no SDL mapping is **invisible** to `engine.input.gamepads`, with no error and no log - and the raw-GLFW diagnostic that tells that apart from nothing being plugged in. Ends with a five-step booth setup checklist for the on-site technician. Still accurate |
| [`lighting-rework-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/lighting-rework-report.md) | Replaces the pooled-`Lamp`-scene-entity approach with immediate-mode `drawLight` from `onRender`, against five pieces of playtest feedback. Root-causes the "lights drift when the camera moves" complaint to a one-tick gap, and records the GI cone's Y-flip, found only by screenshot |
| [`visual-polish-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/visual-polish-report.md) | GI dithering, the resolution-scaled HUD text outline, and pinning the `"hud"` surface. The `zOrder` finding is the durable one: when null it is an auto-decrementing counter assigned in surface-creation order, hence the explicit value. Honest that its dithering A/B could not be confirmed visually |
| [`score-persistence-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/score-persistence-report.md) | The `score/` package. The engine's own `saveObject` is **not atomic**, hence the temp-file-then-promote design; the guarantee is "never a torn file", explicitly not fsync-durable. A bare `List<ScoreEntry>` erases through the engine's reified loader, which is why the wrapper class exists |
| [`packaging-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/packaging-report.md) | The Windows release build, the config split, and `dailySeed` for day two. Key engine fact: `application.cfg` is loaded *after* field initialisers but *before* `onCreate`, so anything reading config must be `lateinit` and built in `onCreate` or the override is a silent no-op |
| [`cleanup-report.md`](../.superpowers/sdd/2026-08-04-en-pust-til-gameloop/cleanup-report.md) | Closes the last review findings, including three tests that could not fail (one deleted, one rewritten, one found to have *become* discriminating). Establishes by decompilation that `screenMode` and window size have **no setters** and cannot change at runtime - which is why the two-config split exists |
| [`final-fix-report.md`](../.superpowers/sdd/2026-08-07-diver-spritesheet-bake/final-fix-report.md) | The three findings from the whole-branch review of the bake: the CLI had zero test coverage, and a `np.isfinite` assertion on a `uint8` array that could never fail. One NaN defence is reported as provably redundant rather than a scenario being invented for it |

### The numbered tasks

Briefs and reports for each. **Read the named reports before these** - much of the gameloop set
describes code that has since been replaced (see section 9).

**`2026-08-04-en-pust-til-gameloop/`** - 1 test infrastructure and tuning constants; 2 buoyancy
model; 3 zones and scoring; 4 pearls and the seeded water column; 5 the dive simulation; 6 playable
prototype - the feel gate; 7 the anglerfish; 8 HUD; 9 lighting pass.

**`2026-08-07-diver-spritesheet-bake/`** - 1 geometry and grid search; 2 colour primitives;
3 diffuse and normal resampling; 4 sheet assembly; 5 source loading and validation gates; 6 CLI,
deterministic write, Kotlin emission; 7 QA bundle; 8 documentation and full-suite verification.
This set has held up: the bake still works as specified, and tasks 1-8 describe the modules under
`tools/spritesheet/` as they exist.

---

## 8. Outstanding work and open questions

[`specs/2026-08-11-outstanding-work.md`](superpowers/specs/2026-08-11-outstanding-work.md) is the
handoff document. Its structure:

**Section 0 SETTLED - the GI composite is MULTIPLICATIVE.** Was the open question at the top of the file;
answered by decompiling the jar and cited so it cannot be reopened. Do not re-measure it.

**Section 1 Decisions only the owner can make** - ask, do not choose:

1. 1.1 Does glowing scenery break the anglerfish's tell? (blocks section 2.4)
2. 1.2 The diver's on-screen size

**Section 2 Work, roughly in value order:**

1. 2.1 Mask the flashlight so it does not render on top of the diver
2. 2.2 Caustics on the rock faces
3. 2.3 Make the air bubbles float
4. 2.4 Decorative scenery - coral, ambient fish, bubble particles *(blocked on 1.1)*
5. 2.5 HUD restyle
6. 2.6 Owner-supplied art still to be wired (boat and cloud sprites)

**Section 3 Traps - every one has already cost this project time:** 3.1 asset declaration; 3.2 the GI
reflectance floor; 3.3 emitters are visible regions; 3.4 the rock is barely lit outside the column;
3.5 shared surface state leaks between frames; 3.6 `drawQuad`/`drawLine` on macOS; 3.7 custom
shaders.

**Section 4 How to work here** - mutation-test every test, the capture harness and its two hard-won
lessons, commit in stages, and the `dive/` boundary.

**Section 5 The booth risk nobody has touched** - **the Windows `.exe` has never been run.** Development
is on macOS. Section 5 lists the seven things to check the first time a Windows machine is available.
This is the largest untested risk in the project.

The design spec's own **Section 16** carries four game-design open questions that section 17 has not closed:
pearl density per zone, how much the anglerfish eats, whether the run hard-stops at 0:00, and
whether the booth has a second screen.

---

## 9. Known stale passages

Flagged so they are not rediscovered the expensive way. None of these invalidates its document.

- **The god rays were removed on 2026-08-17** (design spec section 17, last row), deleting
  `render/LightShafts.kt`, `render/ShaftRenderer.kt`, both godray shaders and the `EPT_SHAFT_PHASE`
  capture pin. Three passages still assume they exist:
  - outstanding-work **section 2.2**, whose whole proposed caustics mechanism is "reuse the shaft field"
    via `LightShafts.bandSum` - that function is gone, so the task needs re-planning, not just
    re-scheduling;
  - outstanding-work **section 3.7**, which names `render/ShaftRenderer.kt` as a worked example of a
    custom `BatchRenderer` (`render/IridescenceRenderer.kt`, the other example, is still there);
  - outstanding-work **section 0**'s last bullet, which says `LightShafts.kt` and `shaders/godrays.frag`
    "are being corrected separately" - they were deleted instead.
- **`2026-08-14-black-discs.md` section 7** pins captures with `EPT_SHAFT_PHASE=30`. Only
  `EPT_WAVE_PHASE` and `EPT_MOTE_PHASE` remain.
- **The bake spec section 2** quotes `Framing.DIVER_SIZE_METRES` as 3 m and the diver as ~5% of screen
  height. The constant is now `DIVER_HEIGHT_METRES = 9f`, 15% of the visible depth. The bake's
  geometry is unaffected (it is driven by `--frame-height`), but the sizing argument in section 2 and the
  scope note in section 9 both read against the old figure.
- **`2026-08-13-deep-water-lighting.md`'s own header is wrong about itself.** It says "plan only,
  nothing here has been implemented"; its body records that the `EPT_DEPTH` pin and the mote move
  both landed, and that the ambient floor was built, measured as a no-op and reverted. Its
  interventions were then superseded the same day by `one-world-model.md`. Read the newer one for
  what to do, the older one for the measurements - and note its in-place correction block, which
  retracts a model that had been presented to the owner twice as validated.
- **The gameloop SDD ledger stops mid-Task-6.** Its four "STILL OPEN" items were all closed later
  with no entry recording it. See section 7.
- **`2026-08-14-engine-native-lighting.md`** proposes adopting the engine's sun-and-sky daylight
  model. Nothing in `render/DiveLighting.kt` sets those properties, so the migration in its section 5 is
  unstarted. Treat it as research, not as a description of the lighting.
- **Design spec section 13** specifies the leaderboard as "a small local service the game POSTs to, with
  a QR on the booth wall". What exists is `score/ScoreRepository`, writing `scoreboard.json`
  locally. Not a contradiction the code should fix silently - it is an unbuilt item, and section 15's cut
  list does not cover it.
- **Test counts are stale everywhere.** The outstanding-work spec says 393 Kotlin tests
  (2026-08-11); the black-discs report says 505 (2026-08-14); the tree currently holds 494 `@Test`
  annotations on a working branch. Use the counts to date a document, never to check one.

### The gameloop task reports specifically

Several of the nine numbered gameloop tasks describe subsystems that were rewritten later on the
same branch — tasks 2, 8 and 9 demonstrably, plus anything touching `render/Viewport.kt`. Read the
named reports as the correction; these are listed worst-first. (An earlier version of this line said
"six of the nine" and could only evidence four, so take the bullets below as the claim and the
count as approximate.)

- **`task-9-report.md`'s lighting architecture is superseded twice.** Its pooled `Lamp` entities
  were deleted by `lighting-rework-report.md`, and its central claim - that a `Camera` **scene
  entity** must be added because it hijacks `engine.gfx.mainCamera` - is now inverted:
  `render/CameraRig.kt` is the sole writer, enforced by `MainCameraOwnershipTest`, and a second
  writer is exactly the bug `6ea1f53` fixed.
- **`task-2-brief.md` / `task-2-report.md` describe deleted physics.** `Buoyancy.ascentSpeed` /
  `descentSpeed` and the `K_ASCENT` / `K_DESCENT` constants are gone. `fix-criticals-report.md`'s
  tuning sweep is stale for the same reason - it sweeps a constant that no longer exists.
- **`render/Viewport.kt` no longer exists**, so every report calling `Viewport.screenX` / `screenY`
  as a coordinate transform is stale. Verified by grep, the eight files that name it are:
  `task-8-report.md` (the HUD anchor, which must now come from `mainCamera.worldPosToScreenPos`),
  `task-9-report.md`, `lighting-rework-report.md`, `cleanup-report.md`, `packaging-report.md`,
  `fix-criticals-report.md`, `caesars-comparison.md` and `progress.md`. **Not** task 6, which this
  list claimed for a while and which contains no occurrence at all. `render/Framing.kt` replaced it
  and deliberately owns no transform.
- **The `#version 150 core` explanation for the macOS `drawQuad` failure is wrong**, and it appears
  as established fact in `progress.md` and in `task-9-report.md`. The real cause is the
  `uint`-attribute-bound-as-`GL_FLOAT` mismatch - see the 2026-08-06 investigation report and
  `CLAUDE.md`.
- **`task-1-report.md`'s constants are superseded** (`BASE_AIR_SECONDS` 20 -> 30, `SWIM_SPEED`
  deleted), and **`fix-gamepad-report.md` is doubly superseded** - its `RESTART_BUTTON` was the
  same button as kick, which `caesars-comparison.md` identifies as run-destroying, and its
  lifecycle scan was replaced by `anyLifecycleActionPressed`.
- **`cleanup-report.md`'s flat-`fillRect` column walls** are superseded by `render/RockFace.kt` and
  the width-capped camera; its pinned release resolution was reverted to native by
  `packaging-report.md`.
