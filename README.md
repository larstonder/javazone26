# Én Pust Til

A free-diving arcade game for the **Capra booth at JavaZone 2026** (2–3 September, NOVA Spektrum
Lillestrøm). Kotlin 2.2.20 on [Pulse Engine](https://github.com/NiklasJohansen/PulseEngine) 0.13.0,
shipped as a Windows `.exe` with a bundled JRE.

> One 90-second breath-hold. Dive as often as you like.
> You only keep what you carry back to the surface.

You are a free-diver. You sink, gather pearls, and return to the surface, where the catch is banked
with a depth bonus. Pearls are **held**, not scored, until you break the surface — and pearls have
**weight**, so a heavy diver sinks faster and climbs slower. Greed is felt through the stick instead
of read off a HUD. That single decision is where the rest of the design comes from.

---

## Read this first — three things that will cost you a day each

**1. `master` is not the game.** It is one commit — the unmodified Pulse Engine project template, 36
files. The entire game is **154 commits on `feat/en-pust-til-gameloop`**, 176 files. Clone and check
out that branch. The branch has never been merged; merging it to `master` is a reasonable first act
of ownership, though at 154 commits it is a real review rather than a formality.

**2. There is uncommitted work in the tree** (as of 2026-08-17). It is coherent and fully green, not
mid-surgery: the god rays were removed at the owner's request, `render/Look.kt` was extracted, and
`render/MoteSprite.kt` was added. See [Where it stands](#where-it-stands). Commit it before you
start anything else.

**3. The Windows `.exe` has never been run.** All development was on macOS. The `.exe` *builds*
fine on a Mac, but nothing in this project has ever been observed executing on the target platform.
This is the single largest risk to the booth and it is described in §5 of
[`docs/superpowers/specs/2026-08-11-outstanding-work.md`](docs/superpowers/specs/2026-08-11-outstanding-work.md).

---

## Quickstart

```bash
git clone <this repo> && cd javazone26
git checkout feat/en-pust-til-gameloop

./gradlew run     # windowed dev mode, 1600x900
./gradlew test    # 494 tests, ~8s

./gradlew test --tests "dive.DiveSimTest"            # one class
./gradlew test --tests "dive.DiveSimTest.*surfac*"   # one test
cd tools && python3 -m pytest                        # the bake scripts, 107 tests
```

**Test names are backticked prose**, so `--tests` matches against the string, not an identifier —
`*surfac*` above, not `surfacing`. There is no formatter or linter in the build (no spotless, ktlint
or detekt), so "Allman braces, 4 spaces" is honour-system; match the file you are in.

**There will be no `assets/` directory, and that is correct.** It is 158 MB of source art frames and
is gitignored, along with `release/`. Everything needed to build, test and run is committed —
including the baked sprite sheets, the rock textures and the 21.8 MB Windows JRE. The one thing a
clean clone cannot do is *re-bake* art; see [`tools/README.md`](tools/README.md).

Nothing needs installing by hand. Gradle 8.13 comes from the wrapper and **JDK 23 is auto-provisioned**
by the foojay toolchain resolver, so your system `java` version does not matter. First build needs
network access to Maven Central, the Gradle plugin portal, and `https://repo.repsy.io/mvn/njoh/public`
(Pulse Engine is not on Maven Central).

On macOS `run` sets `-XstartOnFirstThread` automatically — LWJGL/GLFW must create the window on the
process's first thread.

### Controls

| | Keyboard (dev) | Arcade cabinet |
|---|---|---|
| Swim | Arrow keys | Stick |
| **Kick** — 3x speed, 3x air burn | `Z` (hold) | **A** (hold) |
| **Bleed ballast** — pearls stream out | `X` (hold) | **B** (hold) |
| Start / restart / confirm | `Space` | **Start**, or **A** |
| Initials entry | `Up` / `Down` | Stick up/down |
| Pause | `Esc` | — (technicians only) |
| Quit | `Q` (hold) | — (technicians only) |

Gameplay reads gamepad 0; lifecycle input ("any button to start") scans **every** connected gamepad,
because index 0 is not guaranteed to be the cabinet's stick.

### Dev switches

All are read once at startup and are unset at the booth.

| Variable | Effect |
|---|---|
| `EPT_DEV=1` | Forces `logLevel = DEBUG` (works even against a built release `.exe`), draws the gamepad diagnostic overlay, and runs the camera invariant checks. It also registers `MetricViewer` — but **see the F-key warning below; F3 does not reach it.** |
| `EPT_DEPTH` | Pin the diver at a depth, for reproducible captures of the deep |
| `EPT_WAVE_PHASE`, `EPT_MOTE_PHASE` | Pin animation phase, so two captures are comparable |
| `EPT_SCREENSHOT` | Dump surfaces to PNG. **Read the warning below before trusting its output.** |
| `EPT_EDITOR=1` | Start the engine scene editor. Note it becomes a *second* writer of `mainCamera` and fights `CameraRig`; the failure is loud, not silent. |

### None of the F-keys work, and it is one bug, not four

`init-dev.pes` binds **F2** `showSceneEditor`, **F3** `showMetricViewer`, **F4** `showGpuMonitor`,
**F5** `reloadEntityAndSystemTypes`, and `init.pes` binds **F1** `showConsole`. **All five are
inert**, for a single reason with two halves:

1. **Nothing in pulse-engine 0.13.0 ever runs a `.pes` script.** `console.runScript` is called from
   no class in the jar except the `run {scriptPath}` console command itself, and no class holds the
   string `"init.pes"`. `init.pes:26-33` records this at length — it is the best account in the repo.
2. **Nothing constructs the widget that would read a typed command either.** `showConsole` occurs
   only inside `no/njoh/pulseengine/modules/cli/CommandLine.class`, which no class in the jar or in
   this project references.

So a console command can be *registered* and still be unreachable. Two are: `winner`
(`score/ScoreRepository.kt`) and `showMetricViewer`. `MetricViewer` is registered under `EPT_DEV`
(`EnPustTil.kt:563`) but **never started** — `Service.isRunning` defaults false and only `start()`
flips it. Contrast `EnPustTil.kt:601`, which does `SceneEditor().also { it.start() }`, which is why
`EPT_EDITOR` works and F3 does not.

**Consequence for a new maintainer: there is currently no in-game profiler and no in-game console.**
Adding `.also { it.start() }` to the `MetricViewer()` line is the plausible fix for F3, and
`engine.service.add(CommandLine())` for the console — **both untested here.** Until then, profile
from outside the process.

---

## Seeing it actually run

**Green tests are not evidence the game looks right.** A large share of this project's bug history is
rendering faults that were invisible to the test suite.

**`EPT_SCREENSHOT` is not passive and its output is not the frame.** `ScreenshotEffect.getTexture()`
returns `RenderTexture.BLANK`, so any surface it attaches to *composites as blank* — with it set, the
sky came out black in the running game. It also dumps each surface separately, and recompositing
those by hand does not reproduce the engine's frame. Use it to inspect one surface's raw contents
(its alpha channel is genuinely useful) and take anything about **appearance** from a real screen grab:

```bash
caffeinate -d -u -t 900 &                      # stop the display sleeping mid-capture
./gradlew run > /dev/null 2>&1 &
until pgrep -f EnPustTilKt > /dev/null; do sleep 2; done ; sleep 12
osascript -e 'tell application "System Events" to set frontmost of (first application process whose name is "java") to true'
sleep 2 ; screencapture -x -o /tmp/shot.png    # needs Screen Recording granted to the terminal
pkill -9 -f EnPustTilKt                        # there is no quit key in booth mode
```

The JVM window has no bundle identifier, so screenshot MCPs filter it out entirely. The shell route
above is the one that works.

---

## Repository map

Each package has its own README with the detail. Start with the one you are about to change.

| Path | What it is |
|---|---|
| [`src/main/kotlin/dive/`](src/main/kotlin/dive/README.md) | **The simulation.** Pure Kotlin, zero engine imports, fixed 60 Hz. `DiveSim.tick` is the whole game. `Tuning.kt` holds every tunable number. |
| [`src/main/kotlin/render/`](src/main/kotlin/render/README.md) | **The engine shell.** Everything that touches Pulse Engine. `EnPustTil.kt` is the only `PulseEngineGame`. `Look.kt` holds every number that decides how it *looks*. |
| [`src/main/kotlin/score/`](src/main/kotlin/score/README.md) | **Persistence.** Atomic writes, timestamped backups, the arcade leaderboard and three-letter initials entry. |
| [`tools/`](tools/README.md) | **Asset bake pipeline.** Python; turns source art into the committed sprite sheets and rock textures. |
| [`docs/`](docs/README.md) | **Specs, plans and reports.** Start here for *why*; the design spec is the locked source of truth. |
| `CLAUDE.md` | The agent-facing operational guide. Dense, evidence-backed, and the best single record of the platform traps. |

**`src/main/resources/` is not just config.** It holds the baked art (`sprites/`, `backdrop/`), the
game's GLSL (`shaders/`), the config split (`application.cfg` + `application-dev.cfg`), and two
**deliberate shadows of engine shader files** under `pulseengine/shaders/renderers/`. Read
`build.gradle.kts:46-76` before touching those two — they are dev-only on purpose.

### Scale

43 production Kotlin files against 50 test files, and roughly 13.5k lines of each — near 1:1, which
is the intended ratio: anything worth asserting gets extracted into an engine-free object so it can
be tested without a GL context. (Deliberately rounded. Exact line counts were quoted here at first
and were stale within the hour.)

---

## Where it stands

Audited 2026-08-17, 17 days before the conference.

**Build and tests: green.** `./gradlew clean build` succeeds in ~8s with one compiler warning
(a named-parameter mismatch at `render/ScreenshotEffect.kt:37`). **494 Kotlin tests across 49 classes,
0 failures.** `cd tools && python3 -m pytest` is **107 passed**. There is not a single TODO, FIXME,
HACK or XXX comment in the production source — open work lives in the docs, not the code.

**Uncommitted, coherent, and green** — three intertwined edits made on 2026-08-17:

- **The god rays were removed** at the owner's request (*"Please remove the god rays."*). Deleted:
  `render/LightShafts.kt`, `render/ShaftRenderer.kt`, `shaders/godrays.{frag,vert}`, two test classes,
  and the `EPT_SHAFT_PHASE` pin. Four things were deliberately **kept** because each was attributed to
  the shafts and is not theirs — `DiveLighting.daylightByZone`, `render/OpaqueWater.kt`,
  `Sky.BOTTOM_DEPTH`, and the renderer add-order rule. The design spec's §17 records all of this.
- **`render/Look.kt` was extracted** — 50 presentation constants, the twin of `dive/Tuning.kt`.
- **`render/MoteSprite.kt` was added** — marine snow was being drawn with the light emitter's alpha
  ramp, which forces a flat core and reads as a plate rather than a glow (*"the black circle
  overlapping the diver"*).

Grepping `src/` for the removed names returns only prose comments narrating the removal.

**These six READMEs are uncommitted too**, along with the 2026-08-17 documentation corrections listed
below. **Commit all of it before starting anything new.**

**The packaged release is stale.** `release/win64/en-pust-til-1.0.zip` was built 2026-08-06 — 11 days
and **112 commits** ago. Rebuild before the booth.

### What is left

Owner decisions, from [`docs/superpowers/specs/2026-08-11-outstanding-work.md`](docs/superpowers/specs/2026-08-11-outstanding-work.md):

1. **Does glowing scenery break the anglerfish's tell?** The lure renders identically to a pearl and
   the only tell is motion. Blocks the decorative-scenery work.
2. **The diver's on-screen size** — 9 m against a mockup reading ~12% of visible depth. Note
   `Tuning.PEARL_PICKUP_RADIUS` does *not* scale with him automatically.

Work, in the spec's value order: mask the flashlight so it does not render on top of the diver;
caustics on the rock faces; make the air bubbles float; decorative scenery; a HUD restyle; and wire up
the owner-supplied **boat** and **cloud** sprites for the sunset sky.

**That spec pre-dates the god-ray removal and three of its passages still depend on deleted code.**
§2.2 (caustics) proposes reusing `LightShafts.bandSum`, which no longer exists — that item needs
re-planning, not re-scheduling. §3.7 cites `render/ShaftRenderer.kt` as a worked example, and §0's
last bullet says the godray shader files "are being corrected separately"; they were deleted instead.

`.superpowers/sdd/2026-08-04-en-pust-til-gameloop/progress.md` is a **stale ledger** — it stops at
Task 6 while tasks 7–9 have reports and 154 commits have landed. Treat its open items as unverified.

**A plan is not a description of the code.**
[`docs/superpowers/plans/2026-08-14-engine-native-lighting.md`](docs/superpowers/plans/2026-08-14-engine-native-lighting.md)
proposes moving to GI's own sun/sky model and calling `GiSceneRenderer.drawOccluder` for the rock.
**Neither is implemented** — grepping for `sunColor|skyColor|drawOccluder|bounceAccumulation` finds
only an unrelated `WaterRenderer` uniform. What is actually in the tree is an ambient floor plus four
immediate-mode `drawLight` families with `1/d²` falloff, composited by multiply. Read
[`src/main/kotlin/render/README.md`](src/main/kotlin/render/README.md) for the model that exists.

---

## Documentation defects — corrected 2026-08-17

Writing these READMEs turned up fifteen places where a comment or a doc disagreed with the code. Each
was verified against source and then **fixed in place**, so the list below is a record of what moved,
not a to-do. The build is green after all of it (494 Kotlin, 107 Python).

| Where | What was wrong |
|---|---|
| `dive/Ascent.kt` | Quoted Abyss air burn 2.5 and "140 m needs 20.37 s". `Zone.ABYSS.airBurn` is 2.2; the figure is **19.82 s**. Now names `Zone` instead of listing rates, and flags that the *flown* 140 m boundary has not been re-measured since the amendment. |
| `EnPustTil.kt:1161` | Claimed the marine snow was drawn there "on its own surface". There is no `Motes.render` call in `onRender` at all — they are on `main`, from `DiveRenderer.kt:417`. The comment outlived both the call and the surface. |
| `render/DiveLighting.kt:1548` | Said "1.2 m (`PEARL_LIGHT_SIZE_METRES`)". That constant is 0.5 m; 1.2 m is the torch's. |
| `render/Look.kt:114` vs `DiveRenderer.kt:86` | Same water table, two ratios — 11.8x and 11.98x. **Measured it: 11.98x.** A third figure, "30x", was also sitting in `DiveRendererTest.kt:60` and was never measured against the floored values. |
| `score/ScoreRepository.kt:145` | Cited `ScoreSanitizerTest`, which has never existed. The cases are in `InitialsEntryTest.kt:171-191`. |
| `application-dev.cfg` | "Exactly 60 m of water is visible vertically on every display" — superseded by the 2026-08-12 width cap. This very window is 16:9 and shows **55.42 m**. |
| `CLAUDE.md` | "Four packages" (three, plus `EnPustTil.kt`); "the diver is the only asset with a FILE behind it… two that are generated" (**15 PNGs ship and 3 textures are generated**); and the coordinate-space table omitted the `"sky"` surface, which is a third surface in world metres. |
| `tools/build_backdrop.py` ×4, `backdrop/mirror.py`, `tests/test_body_crop.py` | A cluster of pre-2026-08-12 numbers: a 691-wide tile (it is **614**), `first_not_solid_column` "is 396" (**264**), a 67/228/396 split (**139/211/264**), a "790-wide corner" (**526**). Plus "THE FIVE DECISIONS" listing seven, a usage line missing `--luminance-factor`, and two places still saying the right-hand wall is drawn rotated 180° — the mirror is baked now and **both sides draw at angle 0**. |
| `plans/2026-08-13-deep-water-lighting.md` | Header said "plan only, nothing implemented" while its own body records landed changes. |
| `specs/2026-08-11-outstanding-work.md` | Three passages still assumed the god rays existed. §2.2's entire proposed mechanism was "reuse `LightShafts.bandSum`", which is deleted — that item is now marked as **needing re-planning, not re-scheduling**. |
| Design spec §3, §4, §13 | Annotated rather than rewritten (it is locked): the Abyss row's superseded air burn and light-source cells, §4's entire obsolete buoyancy model, and §13's leaderboard service. |
| These READMEs, second pass | An independent audit of the six found more: this file claimed **50 commits** (it is **154** — see the warning below), `render/README.md` said 30 files (**27**) and omitted `Look.kt` from its own file table, `docs/README.md` attributed a `Viewport.kt` reference to task 6 which contains none, `score/README.md` had six drifted line numbers, and `tools/README.md` carried a defect list that my own fixes had already made obsolete. All corrected. |

> **A trap that cost me the commit count, and will cost you too.** `git log … | wc -l` in this
> environment returns a **summarised** count, not the real one — I got 50 where
> `/usr/bin/git rev-list --count master..HEAD` gives **154**. The same wrapper mangles `grep -n`
> output. When a number comes out of `git` or `grep` and you are going to write it down, get it from
> `/usr/bin/git` or `/usr/bin/grep`.

### Three that are NOT fixed, because they are yours to decide

**1. The end-of-day raffle cannot be run.** `score/ScoreRepository.kt` registers a `winner` command,
and three documents told you to reach it by pressing **F1**. F1 opens nothing: in
`pulse-engine-0.13.0.jar` the string `showConsole` occurs only inside
`no/njoh/pulseengine/modules/cli/CommandLine.class`, nothing in the jar or in this project ever
constructs it, and `init.pes` separately records that the engine runs no `.pes` script — so its
`bind F1 showConsole` is inert too. All three docs now say so. `CommandLine` extends `Service`, so
`engine.service.add(CommandLine())` in `onCreate` is the plausible fix, **but I have not tested it**
and would not ship an untested input path to a booth on my own judgement. The fallback is to read
`scoreboard.json` and draw by hand.

**2. Spec §3's "zones must differ in how you move" was never built.** Kelp fronds and lateral trench
currents do not exist; the five zones differ only in pearl value, mass and air burn — which the spec
itself calls "a reskin". No §17 row defers it and it is not in the §15 cut list. Now flagged in the
spec at the point a reader would meet it.

**3. Spec §13's leaderboard service was never built.** No network code exists anywhere in the
project. Unlike the god rays or the pearl emission, it was never explicitly cut — it simply did not
happen, so nothing recorded it. Also now flagged in place.

The `.superpowers/sdd/` task reports were deliberately left alone: several describe deleted code
(task 2's buoyancy model, task 9's lighting architecture, `render/Viewport.kt`, and the wrong
`#version 150 core` diagnosis of the macOS `drawQuad` bug), but they are dated records of work as it
was done, and `docs/README.md` marks them as history rather than description.

---

## Building the booth release

```bash
./gradlew buildWin64Release    # -> release/win64/en-pust-til-1.0.zip
```

No Windows machine and no Wine required — this cross-builds on macOS. **Scope of that claim:** the
`createExe` half was run during this audit and produced a 32.3 MB `PE32 executable (GUI) … for MS
Windows`. The full `buildWin64Release` was deliberately **not** run, because it would overwrite the
owner's existing `release/win64/en-pust-til-1.0.zip`; the remaining steps are a delete, an unzip and
a zip, so the risk is low, but it is untested here and the packaged zip on disk is still the
2026-08-06 one. Do not read this as "the release is known good" — see risk 3 at the top.

It wraps the fat jar with launch4j, deletes launch4j's redundant `lib/`, unzips the **committed**
`jre/minimal-jre23-win64.zip` (21.8 MB, tracked in git — nothing is downloaded at build time) into
`jre/`, and zips the result.

The jar deliberately excludes `macos/**` and `linux/**` natives, anything matching `*-dev*` (which is
what keeps `application-dev.cfg` and `init-dev.pes` out), and **our** copies of the two shadowed engine
shaders. That last exclusion is intentional and explained at length in `build.gradle.kts:46-76`: the
booth `.exe` ships the *engine's* copies, because the bug they repair is macOS-only with ~85%
confidence, is unverified on Windows, and no game code calls the broken methods. The `.exe` is
unsigned, so expect Windows SmartScreen.

### Booth operation

`application.cfg` is the booth default and ships in the `.exe`: fullscreen at the display's native
resolution, `logLevel = WARN`, no pinned window size. `application-dev.cfg` is loaded automatically on
top of it for local runs and restores windowed + DEBUG. **`screenMode` and window size cannot be
changed at runtime** — no setter reaches the window after creation — which is why this is a two-file
split and not an env var.

**Day two:** uncomment and change `dailySeed` in `application.cfg`, restart the `.exe`. That
regenerates the water column, and because every `ScoreEntry` stores the seed it was earned under, day
two gets a fresh leaderboard while day one's board stays intact in `scoreboard.json`.

The scoreboard lives at `~/EnPustTil/scoreboard.json` (`%USERPROFILE%\EnPustTil\` on Windows).
See [`src/main/kotlin/score/README.md`](src/main/kotlin/score/README.md) for the backup and recovery
procedure.

**If the stick does nothing at the booth**, the first thing to check is the log, not the wiring.
`logGamepadDiagnostics()` runs unconditionally at `EnPustTil.kt:687` and goes to the raw GLFW
joystick API specifically to tell "nothing plugged in" apart from "plugged in but unmapped" — a
generic arcade USB encoder may have no SDL mapping, in which case it is invisible to
`engine.input.gamepads` while working perfectly at the OS level. That case logs at **WARN**, so it
survives the booth's `logLevel = WARN`. The full on-site checklist is in
`.superpowers/sdd/2026-08-04-en-pust-til-gameloop/input-robustness-report.md`.

---

## Conventions

- Allman braces, 4 spaces, no wildcard imports — match the surrounding file.
- **Comments here explain *why*, at length, and cite their evidence** — a decompiled engine class, a
  screenshot, a measured playtest. That density is deliberate: most of these decisions look arbitrary
  or wrong without it. When you fix something subtle, leave the same kind of note.
- No per-frame allocation in the render path. HUD text formatting is the one explicit exemption.
- The simulation is deterministic: seeded RNG, fixed timestep, no `Math.random()` in `dive/`.
- **A test that cannot fail is worse than no test** — assert the relationship that would actually
  break. Commit `4493eeb` deleted several that could not.
- Presentation state must never leak into `dive/`.

## Licence

MIT — see [LICENSE.md](LICENSE.md). **Note this was inherited unchanged from the Pulse Engine project
template and still reads "Copyright (c) 2025 Niklas Johansen".** Nobody has decided what the licence
on Capra's own game code and art should be; worth resolving before the repo goes anywhere public.
