# Windows verification protocol

↑ [Repo root](../../README.md) · [CLAUDE.md](../../CLAUDE.md)

**Nobody on this project has a Windows machine.** `en-pust-til.exe` has been built (`./gradlew
buildWin64Release` exits 0, produces a 52.5 MB zip, `PE32 executable (GUI) Intel 80386`, bundled
`jre/bin/`) but never *run*, by anyone, ever. Tasks 1-9 of this plan exist to make an untested
binary survivable at an unattended booth. This document is how it stops being untested — the
protocol the owner runs the first time the `.exe` sees real hardware, and the record of what
actually happened.

**A checklist of ticks is worth nothing next year.** Every check below has a blank for the
*observed* result — a number, a log line, a screen you actually looked at — not "OK" or "PASS".
Fill in what was seen, including anything odd that didn't rise to a failure. Someone reading this
in 2027 trying to figure out why the cabinet behaves a certain way should be able to find the
answer here.

**Legend — what hardware each check needs**, because the owner may get the booth panel and the
arcade encoder on separate days, or only one of them before travelling:

- **[ANY WIN]** — any Windows machine. Laptop is fine.
- **[PANEL]** — must be the actual booth display, because the claim is about resolution or
  aspect (frame rate, `CameraRig`'s pixel math).
- **[ENCODER]** — must be the actual arcade USB encoder. Doesn't need to be mounted in the
  cabinet yet, just plugged in.
- **[BOOTH]** — needs the full assembled cabinet (panel + encoder + venue power), because the
  check is about the assembly, not any one part.

---

## Decide before you travel

These are not hardware checks. Get them settled on a laptop, before the one on-site Windows
session has to also cover everything else.

### D1. The venue build is from commit `89312c3` or later

Earlier commits have `stayAlive = false` in `build.gradle.kts`'s `launch4j {}` block. With that,
launch4j's gui-header launcher returns as soon as it has spawned `javaw.exe` — about a second
after start — so `tools/booth/start-booth.bat`'s `start /wait "" "en-pust-til.exe"` is satisfied
against *that* return, not against the game exiting. The watchdog then relaunches a new game
every ~5 seconds forever, starting on the very first boot at the venue: a fork bomb, not a crash
recovery mechanism. `stayAlive = true` was added in `89312c3` ("the watchdog now waits for the
JVM and its log lines actually land") and is asserted by `BoothLauncherTest`.

Before building the release zip that ships to the venue: `git log -1 --format=%H` on the
checked-out commit and confirm `89312c3` is an ancestor (`git merge-base --is-ancestor 89312c3
HEAD`). Do this on the build machine, not the booth — by the time the watchdog is looping every
5 seconds in front of a queue, the fix is a re-build and a re-flash, not a config edit.

Built from commit: **______________**  Confirmed `stayAlive = true` present: **______________**

### D2. The raffle fallback — decide the mechanism now, not at the booth

F1 opens no console (see check 12 below) — `winner` cannot be typed at the booth under any
config. Decide now which of these two you're doing on raffle day:

1. Test `engine.service.add(CommandLine())` in `EnPustTil.onCreate` ahead of time, confirm it
   actually opens a console and that `winner` prints a result, and ship that build. **This is the
   plausible one-line fix and is UNTESTED** as of this writing — nobody has tried it.
2. Or: plan to compute the draw by hand from `scoreboard.json`, using the exact weighting in
   `ScoreRepository.kt`'s `winner` command (`score/ScoreRepository.kt:245-285`, `tickets()` at
   `:271`): every entry with
   `score > 0` gets `10 + floor(3 * score / bestScoreToday)` tickets — 10 base, up to 3 more
   scaled linearly by how close the score is to the best score recorded that day — then draw one
   ticket uniformly at random across the pool. Three lines of arithmetic over the day's
   `ScoreEntry` list (filter by `seed == todaySeed`, since day two's board coexists with day one's
   in the same file).

Decision: **______________**  If option 1: did `CommandLine()` work? **______________**

---

## The checks

Ordered so an early failure stops you before it wastes a later check's setup — per the plan's own
execution notes, checks 1-2 gate everything else.

### 1. It starts at all — [ANY WIN], ideally [PANEL]

Unzip the release, run `start-booth.bat` (not `en-pust-til.exe` directly — the .bat is the
watchdog; running the exe alone means no restart cover, ever). Expect fullscreen at the display's
native resolution, no title bar, the attract screen.

**If it fails:** go straight to check 2 — the booth log is the only diagnostic that exists before
the engine has drawn a frame. Every check below assumes this one passed.

Observed: **______________**

### 2. The booth log exists and has content — [ANY WIN]

Path (from `booth/BoothLog.kt`'s `logDirectory`, which is `<homeDir>/<gameName>/logs`):

```
%USERPROFILE%\EnPustTil\logs\booth-<epoch>.log
```

Expect the start-up banner (`main()`'s `Booth log: <path>` line, `EnPustTil.kt:63` — teed into the
file, so it should be the first line), `Daily seed: 20260902` (or whatever `dailySeed` resolved to
— `EnPustTil.kt:1120`), `Buttons: kick=A bleed=B restart=START/A deadzone=0.2`
(`EnPustTil.kt:1072`, values reflecting whatever `application.cfg` set), and the gamepad
diagnostics (`logGamepadDiagnostics`) — `GAMEPAD DIAGNOSTIC: engine.input.gamepads = N ...` plus
one `... is gamepad-mapped` line per recognised device. All of these are now at `Logger.warn`
specifically so a **healthy** encoder still writes something: an earlier version of
`logGamepadDiagnostics` logged its success-path lines at `Logger.info`, which `application.cfg`'s
`logLevel = WARN` booth default filters out entirely, so a correctly-mapped pad would leave zero
gamepad lines in this log and a technician could misread that silence as the tee having failed.
Fixed alongside this document — see `EnPustTil.kt` around `logGamepadDiagnostics`'s doc comment.

**If empty or missing:** the log tee (`BoothLog.install`) never ran, or the profile directory is
unwritable. This means Task 1 did not take, and every remaining check that says "check the log"
is blind for the rest of this session — the log is the only channel a fullscreen cabinet with no
console has.

Observed: **______________**

### 3. The world actually renders — [PANEL]

The release ships the engine's own unfixed `quad.vert`/`line.vert` deliberately
(`build.gradle.kts:45-58`) — the macOS `drawQuad`/`drawLine` bug is believed Windows-safe at
**~85% confidence, and this is the first time it has ever been checked**. Nothing in game code
calls `drawQuad`/`drawLine` directly (`DrawTest` enforces that by source scan), so this check is
about the *game*, not the scene editor: water bands, rock face, pearls, HUD, diver all visible
and textured, not black or missing.

**If the world is missing, or textures are silhouettes:** the shadowing exclusion in
`build.gradle.kts` is wrong for this platform after all and the release needs our repaired
shaders. This would be a first-of-its-kind Windows-specific rendering defect — flag it loudly,
don't just patch around it blind.

**Do NOT use `EPT_SCREENSHOT` to judge this.** `ScreenshotEffect.getTexture()` returns
`RenderTexture.BLANK`, so any surface it's attached to composites as **blank** — the sky came out
solid black under a capture that looked fine on the real screen. Look at the actual monitor.

Observed: **______________**

### 4. Frame rate with GI on — [PANEL]

`targetFps = 120` (`application.cfg`), the global-illumination composite runs every frame, and no
one has ever measured the achieved rate. The in-game profiler is unreachable — F1 opens no
console (check 12) — so measure from outside the process: Task Manager's GPU graph, an overlay
tool, or simply eyeballing motion smoothness against a phone's slow-motion camera if nothing else
is available.

**If it's below 60:** the lever is panel resolution, not code — `CameraRig` derives world scale
from the actual framebuffer height and is resolution-independent by construction (see
`render/CameraRig.kt`), so a lower-resolution panel is a legitimate mitigation if the booth
display is swappable. If it's not swappable, this is a scope conversation, not a bug report.

Observed FPS: **______________**  Panel resolution: **______________**

### 5. The watchdog kill-test — [ANY WIN]

**Kill the right process.** With `stayAlive = true` (confirmed in D1), `en-pust-til.exe` is a
*resident parent* of `javaw.exe`, not a launcher that exits. **Everything below this line about
what Task Manager and the screen actually show is reasoned from source, not observed** — nobody
on this project has a Windows machine, so this predicts from `launch4j`'s and `start-booth.bat`'s
documented behaviour rather than reporting something anyone has watched happen. In particular,
whether launch4j's native launcher stub wraps its child in a Windows Job Object that cascade-kills
`javaw.exe` when `en-pust-til.exe` dies is exactly the kind of detail source-reading cannot
settle — if it does, the "orphaned `javaw.exe`" prediction below is simply wrong, and the whole
two-process picture may not hold the way this section describes.

The recommended action does not depend on that being right, so do it anyway: **kill `javaw.exe`**
(the actual JVM/game process), not `en-pust-til.exe`. This is the correct crash simulation on
either theory — launch4j propagates its exit code back through the still-running
`en-pust-til.exe`, `start /wait` in `start-booth.bat` returns, the watchdog logs the exit code and
relaunches after ~5 seconds.

Predicted (not yet observed) if you deliberately kill `en-pust-til.exe` instead, to see what the
wrong process looks like: Task Manager shows two entries for one running game
(`en-pust-til.exe` and `javaw.exe`) before the kill; killing the parent leaves `javaw.exe` running
underneath it, orphaned, with the game window still on screen, while the watchdog's `start /wait`
— which was waiting on the process you just killed — also sees an exit and relaunches a second
`en-pust-til.exe`/`javaw.exe` pair on top of the orphaned first one, giving three-plus processes
and two live game windows/instances fighting over the same fullscreen display. **If a Job Object
is in play, none of that may happen** — killing the parent could cascade-kill `javaw.exe` cleanly
instead, in which case Task Manager will show only one pair, no orphan, and the watchdog's
relaunch will look identical to the correct-kill case. Whichever it is, this is not what check 6
or check 7 are testing — **record what actually happens here**, since whoever runs this check is
the first person ever to find out.

Confirm: kill `javaw.exe`, watch `watchdog.log` (`tools/booth/start-booth.bat`'s own `%WDLOG%` —
**separate from the booth log**, written by the batch script's own `echo` redirects, path:

```
%USERPROFILE%\EnPustTil\logs\watchdog.log
```

) for `[watchdog] exited with code <n>` followed by `[watchdog] starting en-pust-til.exe` ~5
seconds later, and the attract screen returns.

**If it does not relaunch:** the booth has no restart cover — this is the single most important
check in this document, since it's the backstop for every other failure mode below it.

Observed (killing `javaw.exe`): **______________**
Observed (killing `en-pust-til.exe`, if you also tried the wrong one deliberately): **______________**

### 6. The arcade encoder — [ENCODER]

Plug it in before launching (or restart after plugging in). Two questions:

1. Does it show up in `engine.input.gamepads` at all, or only as an unmapped raw joystick? The
   booth status line (bottom of the attract screen, `render/BoothStatus.kt`) appends `N UNMAPPED
   PAD` if SDL has no mapping for it; the log's `logGamepadDiagnostics` output says the same
   thing at start-up.
2. Which physical button reports which code? Run with `EPT_DEV=1` (forces `logLevel = DEBUG`,
   works even against a built release `.exe`) to see the gamepad diagnostic overlay — it prints
   recognised/raw-present/raw-unmapped counts live.

**A healthy status line reads just `SEED <n>`** with nothing appended — everything past the seed
number is a fault segment appended by `BoothStatus.line`.

**If the codes are wrong** (kick fires on the wrong button, etc.): edit `application.cfg`'s
button-map block, no rebuild — see check 8 for the exact syntax.

**If the whole pad is invisible/unmapped:** this needs an SDL gamepad mapping added for this
specific encoder's VID/PID, which is a toolchain job, not a config edit. Flag it early — it's the
one failure in this section that can't be fixed on setup day with a text editor.

Observed (recognised as gamepad / raw-only): **______________**
Observed (button → physical mapping, one line per button): **______________**

### 7. The crash path (`EPT_FAIL_BOOT`) — [ANY WIN]

Two separate things to confirm here — a boot-time fault and a running-time fault.

**Boot-time:** run with `EPT_FAIL_BOOT=1`. `EnPustTil.onCreate` throws deliberately, before `sim`/
`scoreRepository` exist, which routes every later frame to the full-frame boot-failed screen
(there's no `DiveSim` left to draw a world around). Expect a **red `!! BOOT FAILED - RESTART THE
CABINET !!`** screen, not Notepad and not a frozen black window. This is the rehearsal for the
"createGame threw" case that `render/BoothStatus.kt`'s class doc calls "the MOST SEVERE thing
this line can say" — confirming it actually renders on the real panel, not just in a test, is the
whole point of this check.

**Running-time (if you can reach a fault without `EPT_FAIL_BOOT`):** with `EPT_DEV=1`, force
whatever fault you can reach mid-run. Expect the cabinet to keep running with the status line
showing `1 FAULT IN onRender` (or whichever call site) and, critically, **no Notepad** — the
engine's default handler opens a crash-report `.txt` via the OS file association
(`CallbackGuard`'s class doc has the disassembly), which on a fullscreen cabinet means Notepad
appearing in front of the queue.

**If Notepad appears:** a callback escaped `CallbackGuard` — find which call site in
`EnPustTil.kt` isn't wrapped and wrap it.

Observed (`EPT_FAIL_BOOT=1` screen): **______________**
Observed (running-time fault, if reached): **______________**

### 8. The stuck button — [ENCODER] or [BOOTH]

Wedge the START button closed (tape, a rubber band, whatever holds contact). `LifecycleInputEdges
.STUCK_SECONDS = 3f` (`render/LifecycleInputEdges.kt`), so expect the status line to show `1
STUCK INPUT` within about 3 seconds, and — the actual point of the fix — pressing A (or whatever
`kickButton` maps to) still starts a run despite START being held.

**If it does not recover:** the debounce/stuck-source handling regressed; a single wedged button
would otherwise lock the cabinet out of every run for the rest of the show.

**Button-map edits, if any button needs remapping** (no rebuild — `application.cfg`, uncomment
and edit):

```
kickButton = A
bleedButton = B
restartButton = START
restartButtonAlt = A
```

Valid names (verified against the jar, `GamepadButton`'s entries): `A, B, X, Y, LEFT_BUMPER,
RIGHT_BUMPER, BACK, START, GUIDE, LEFT_THUMB, RIGHT_THUMB, DPAD_UP, DPAD_RIGHT, DPAD_DOWN,
DPAD_LEFT`, plus PlayStation aliases `CROSS, CIRCLE, SQUARE, TRIANGLE`. (`LAST` also parses, as an
alias for `DPAD_LEFT` — write `DPAD_LEFT` instead, nobody standing at the cabinet will guess what
`LAST` means.) A misspelled name silently falls back to the compiled default and logs a WARN — it
does not crash and does not leave the button unbound to nothing.

`stickDeadzone` is **safe to write either way now** — `0` (bare integer, "disable it entirely")
or `0.2`/`0.35` (decimal) both load correctly. This used to be a trap (a version of this reader
that only called `Configuration.getFloat` would silently ignore an all-digits `stickDeadzone = 0`
and keep the compiled default instead, since `application.cfg`'s own loader stores all-digit
values as `Integer`, not `Float`), but `resolveDeadzone` (`EnPustTil.kt:379-382`) now checks the
`Int` reading first and falls back to the `Float` reading, so both shapes resolve. Confirm via
the log's `Buttons: ... deadzone=<n>` line that the value you wrote is the value that loaded,
regardless of which shape you used — that line is the general check for "did this edit take" and
costs nothing extra.

Observed (time to `1 STUCK INPUT`): **______________**
Observed (A still starts a run while START is stuck): **______________**
Any remap needed: **______________**

### 9. Scores across a restart — [ANY WIN]

Play a run worth recording (see `Leaderboard.isWorthRecording`), enter initials, kill `javaw.exe`
(the correct kill — see check 5), let the watchdog restart it. Expect the score on the attract
leaderboard after restart.

Then check `%USERPROFILE%\EnPustTil\scoreboard.json` directly and confirm the score is actually
in the file, and that no `scoreboard.<uuid>.tmp` files are left beside it (`ScoreRepository
.sweepStaleTemps` should have cleared any from the kill itself, but confirm).

**If the score is on screen but not in the file:** the save failed. Grep the booth log for
`Failed to save file:` (the engine's own `DataImpl.saveObject` line, verified from the constant
pool — `score/README.md`) or `SCORE NOT SAVED` (this project's own report from
`ScoreRepository.onSaveFailure`). Both log at ERROR, which clears the booth's `logLevel = WARN`
gate, so they will be there if this happened.

Observed: **______________**
`.tmp` files left behind: **______________**

### 10. Hard power cut — [BOOTH]

Play a scoring run, then pull the power at the wall — not a shutdown, not Alt-F4. On restart,
expect `scoreboard.json` intact.

`promoteAtomically` guarantees no *torn* file (rename is atomic, so you get the fully-old or
fully-new file, never a half-written one) but there is **no `fsync`** anywhere in this path — see
`score/README.md`'s failure table — so the very last score, if the power cut landed between the
temp write and the OS actually flushing it to the platter, may legitimately be missing even
though the promote logic is correct. Record which happened; don't treat a missing last score as a
bug unless the file is torn or corrupted.

Observed (board intact / last score present / last score missing / file corrupted): **______________**

### 11. The day-two seed — [ANY WIN]

Uncomment and change `dailySeed` in `application.cfg`, restart.

**THE VALUE MUST BE ≤ 2147483647.** Above that, the config loader throws *silently* partway
through parsing the file and drops an *unpredictable, hash-order-dependent* subset of every other
key in the file too (see the giant comment above `dailySeed` in `application.cfg` and
`render/BoothStatus.kt`'s doc item 5) — not a clean rejection, a partial, undiagnosable file load.
Use a sane date-shaped number like `20260903`, not a typo with an extra digit.

**Never trust the edit — verify it took.** Read the attract screen's status line: a healthy line
after the change reads `SEED 20260903` (whatever you set). If the log's `Daily seed:` line or the
on-screen `SEED <n>` still shows the old number, the edit did not take — re-check the file for a
stray decimal point (degrades safely, falls back to the old seed) versus a value over the ceiling
above (does not degrade safely).

Expect the leaderboard to show **empty** for the new seed (every `ScoreEntry` stores the seed it
was earned under, and the attract leaderboard is scoped to `todaySeed` — day one's scores are
still in `scoreboard.json`, just not displayed until you switch back).

Observed (`SEED` line before / after): **______________**
Observed (leaderboard empty on the new seed): **______________**

### 12. The raffle — [ANY WIN]

Confirmed still broken by inspection, and this check just re-confirms it rather than discovering
it fresh: `ScoreRepository.kt` registers the `winner` console command correctly and ungated
(`registerWinnerCommand`, called from `onCreate` unconditionally), but nothing in this project or
the engine ever constructs the `CommandLine` service that reads console input, `init.pes` is
never executed by the engine at all, and F1 is consequently inert — there is no way to type
`winner` at the booth as shipped.

If D2 above decided to test the `CommandLine()` fix, this is where you confirm it: add
`engine.service.add(CommandLine())` to `onCreate`, rebuild, confirm F1 actually opens a console
and `winner` prints something. If D2 decided on the hand-computed fallback instead, this check is
just "confirm F1 does nothing" (expected) and move on — the decision is already made, this isn't
the place to make it under time pressure.

Observed: **______________**

### 13. Eight hours unattended — [BOOTH]

Leave it running on the attract screen overnight, with a few runs played first so the leaderboard
and save path have been exercised. This is the only check that actually tests what two days at
the booth demands of the cabinet — everything above it is a component test.

Expect: alive in the morning, status line clean (just `SEED <n>`, no accumulated fault segments),
memory not visibly climbing (Task Manager's working-set column, a screenshot at the start and one
at the end is enough — this doesn't need a profiler).

Observed (alive / status line / memory start → end): **______________**

---

## Outstanding from the Mac side — not a Windows check, record it anyway

Task 9 (`7dde8ce`, "attract mode shows a live diver, not the last player's corpse") fixed
`RunLifecycle` so a return to `IDLE` after a finished run rebuilds `DiveSim`, specifically so the
attract screen shows a fresh diver at the surface rather than the previous player's frame frozen
wherever the RUN_OVER timeout caught them (previously: a near-black abyss with a leaderboard
floating in it after a 120 m run). **This was never seen on screen** — the capture session that
would have confirmed it hit a screen lock partway through, so the fix is verified by
`RunLifecycleTest` (`justReturnedToIdle` fires exactly once on the IDLE transition, not on a
PAUSED resume) but not by a human looking at the monitor.

While the booth panel is set up for the checks above, let a run finish (or let RUN_OVER time out
without entering initials) and watch the transition back to attract mode. Confirm a fresh diver
at the surface appears, not the previous run's final pose held on screen.

Observed: **______________**
