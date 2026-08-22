# `score/` - persistence and the arcade leaderboard

↑ [Repo root](../../../../README.md) · [CLAUDE.md](../../../../CLAUDE.md) · [Design spec](../../../../docs/superpowers/specs/2026-08-04-en-pust-til-design.md) · [Docs map](../../../../docs/README.md)

## Why this package looks paranoid

The target is a **two-day unattended arcade cabinet** at the Capra booth, in front of a
queue, with nobody watching the machine. Daily prizes are drawn from this leaderboard, so
a lost score is a real failure with a person attached to it. Every decision below -
temp-file-then-rename, unique temp names, reported (not swallowed) failures, a stale-temp
sweep, timestamped backups, a defensive filter on load, a synchronous save on every
registration and again at shutdown, auto-submitting abandoned initials - is that one
requirement, spelled out.

The second constraint is queue throughput. Design spec section 12: **three-letter initials, never
a form field**. That is also why no name and no e-mail is ever collected - there is no
personal data here and therefore no consent flow to build.

`EngineScoreStore` (`ScoreStore.kt`) is the only class in this package that touches
`engine.data` directly - though not the only place that touches the engine or a file at
all: `ScoreRepository` itself imports `PulseEngine`, and `AtomicFileSwap` touches the
filesystem directly (deliberately - it is a `Files.move`, not a data read/write, so it
does not go through `engine.data`). The `EngineScoreStore` seam is what lets
`ScoreRepository` itself - not just the ranking, the validation and the initials state
machine around it - be unit-tested without a GL context. `ScoreRepositoryTest`
constructs a real temp-directory `ScoreStore` and drives `registerScore` for real, atomic
promote included.

## Files

| File | What it owns |
|---|---|
| `ScoreEntry.kt` | The record: `initials`, `score`, `seed`, `timestampMs`. Four fields, no logic. |
| `Leaderboard.kt` | Pure ranking and selection - `rank`, `topN`, `isWorthRecording`. |
| `InitialsEntry.kt` | The three-letter entry state machine, plus `isValidInitials` and `sanitizeEntries` (the load-time filter, which lives here because it is the same shape rule). |
| `AtomicFileSwap.kt` | `promoteAtomically(temp, live, onFailure)` - one function, two `Files.move` calls (a primary atomic rename, and a fallback plain replace if the filesystem cannot do it atomically), with a failure channel so a caller can report rather than silently lose a promotion. |
| `ScoreStore.kt` | The seam: `ScoreStore` (`exists`/`load`/`saveAsync`/`saveSync`/`fileFor`/`listNames`) and `EngineScoreStore`, the only class in this package that touches `engine.data` directly. Its class doc carries the decompiled evidence for every claim this file makes about `DataImpl`'s failure behaviour. |
| `ScoreRepository.kt` | An engine `Service` that loads (and sweeps stale temp files) on create, saves synchronously on every registration and on destroy, rolls backups asynchronously, and registers the `winner` raffle command. |

## The write path

`engine.data.saveObject`/`saveObjectAsync` write **directly to the target path** - verified
by decompiling `DataImpl`: Jackson to a byte array, then `FilesKt.writeBytes(file, bytes)`,
which truncates and overwrites in place. There is no temp file inside the engine. So this
package builds one on top:

1. `ScoreRepository.saveAndPromote` (`score/ScoreRepository.kt`) serialises a **snapshot**
   (`entries.toList()`, not the live list) to a freshly-named `scoreboard.<uuid>.tmp` via
   `ScoreStore.saveSync` - **synchronously, on the calling thread**, not async. That is a
   deliberate reversal of the first version of this write path: `saveObjectAsync`'s
   completion callback only fires when the write succeeds (verified against `DataImpl`'s
   bytecode - see `ScoreStore.kt`'s class doc), so a write *failure* on that path could
   never reach this class's own failure reporting at all. `saveSync` returns the `Boolean`
   the async path could not deliver, and this fires once per completed run - gameplay is
   already over and the screen is a static leaderboard - so the render-thread cost async
   saving elsewhere in this project exists to avoid does not apply here.
2. If the write itself failed, `onSaveFailure` reports it immediately and nothing is
   promoted. Otherwise `promoteAtomically` (`score/AtomicFileSwap.kt`) runs - `Files.move
   (ATOMIC_MOVE, REPLACE_EXISTING)` with a caught fallback to a plain replace if the
   filesystem cannot do it atomically - and **a failed promotion is reported through the
   same `onSaveFailure` channel** rather than swallowed, which is what a `catch (e:
   Exception) { false }` with no log and no rethrow used to do.

What each step actually defends against:

| Failure | Outcome | Why |
|---|---|---|
| The temp write itself fails (disk full, permission denied, …) | Reported via `onSaveFailure`, live file untouched | `saveSync`'s `Boolean` is checked before anything is promoted. |
| Crash or power cut **during the temp write** | Live file untouched | `scoreboard.json` is never opened for writing. The half-written `.tmp` is simply overwritten next time and never promoted. |
| Crash **between the temp write and the rename** | Equivalent to the write never happening | The safe outcome: the previous complete board survives. Any `.tmp` left behind by this is swept on the next `onCreate` - see below. |
| Crash **during the rename**, or the rename fails (e.g. an indexer/AV holds `scoreboard.json` open on Windows) | Fully-old or fully-new, never torn - and the failure is reported, not silent | A rename is a directory-entry swap, not a content write; `promoteAtomically`'s `onFailure` parameter is what makes the failure visible. |
| True hardware power loss | **Not covered** | Nothing calls `fsync`/`force`. The guarantee is "no torn file", not "every acknowledged write has reached the platter". Honest limit of what `engine.data`'s API allows without reimplementing serialisation. |
| A corrupt or hand-mangled `scoreboard.json` | Fresh empty board, no crash | `loadEntries` catches, then `sanitizeEntries` (`score/InitialsEntry.kt:119`) drops anything that is not three uppercase letters with a positive score. |
| A stale `scoreboard.<uuid>.tmp` left by a crash between write and promote | Deleted on the next `onCreate` | `ScoreRepository.sweepStaleTemps`, run from `loadInto` before entries are loaded - otherwise these accumulate forever across two days of watchdog restarts, in the same folder a technician copies to a USB stick. |

**A score in the leaderboard on screen but missing from `scoreboard.json` after the run
ended means the save failed.** `grep` the booth log (`booth/BoothLog.kt` tees
`System.out`/`System.err`, so `Logger.error` calls land there) for either of two strings:
`Failed to save file:` (the engine's own `DataImpl.saveObject` log line - the exact string,
from the constant pool - meaning the temp write itself failed) or `SCORE NOT SAVED` (this
package's own report, from `ScoreRepository.onSaveFailure`, meaning either the write or the
promotion failed). Both fire at `ERROR`, which clears the booth's `logLevel = WARN` gate.

**Backups.** `maybeRollBackup` writes `scoreboard-backup-<epochMs>.json` at most once every
`BACKUP_INTERVAL_MS` (30 minutes). This is the one write in the package still asynchronous
(`ScoreStore.saveAsync`) - deliberately: it is off the score-loss critical path, so a
callback that silently never fires on failure costs at most one missing backup file, never
a lost score. Each backup is a uniquely named file, never overwritten and never read back
by the game, so an interrupted backup write can only ever damage that one file - which is
why it deliberately skips the temp+promote dance. Note that the timer only advances when a
score is registered: a cabinet nobody plays for an hour writes no backups, and the first
backup of a session cannot happen until 30 minutes after process start (`lastBackupTimeMs`
is initialised at construction).

**Where on disk.** `EngineScoreStore.fileFor` (`score/ScoreStore.kt`) is
`File(engine.config.saveDirectory, name)`. `saveDirectory` defaults to
`File(System.getProperty("user.home"), gameName).absolutePath` (from `ConfigurationImpl`'s
constructor, re-derived whenever `gameName` changes), and `gameName = EnPustTil` in
`application.cfg`. So:

- Windows booth: `C:\Users\<user>\EnPustTil\scoreboard.json`
- macOS/Linux dev: `~/EnPustTil/scoreboard.json`

`saveDirectory` is itself a config key and can be overridden in `application.cfg` if the
booth machine needs the board somewhere else.

## Lifecycle hooks

`ScoreRepository` extends the engine's `Service` and is registered with
`engine.service.add(scoreRepository)` in `EnPustTil.kt:1303`. Verified call order (from
decompiling `ServiceManagerImpl`): `add()` queues the service; `init()` runs right after
`PulseEngineGame.onCreate()` returns and calls `onCreate` on everything queued; `destroy()`
runs during shutdown, just after `PulseEngineGame.onDestroy()`, and calls `onDestroy` on
every registered service.

- `onCreate` loads the board (via `loadInto`, which sweeps stale `.tmp` files first - see
  the write-path table above) and registers the `winner` command.
- `onDestroy` runs the same synchronous save-and-promote every `registerScore` uses (see
  the write path above for why that switched from async to synchronous). Deliberate on
  both call sites now, not just here: there is no guarantee the process survives long
  enough for an async write to finish, and the cost - a few milliseconds of blocking, once
  per completed run, on a screen that is already a static leaderboard - is nothing like the
  render-thread cost that rule exists to protect during actual gameplay.

**If the cabinet is killed without `onDestroy` running** - `pkill -9`, power cut, a hung
process force-quit - nothing that was already registered is lost. Every `registerScore`
already ran its own synchronous save-and-promote before returning (`EnPustTil.kt:1698` is
the only caller, fired on `RunLifecycle.initialsJustCompleted`). There is no window between
"registered" and "on disk" any more - the two happen on the same call stack. A run that was
still on screen was never registered in the first place. The `onDestroy` save exists for
belt-and-braces, not because there is a race it alone closes.

This is why `RunLifecycle`'s exit path calls `engine.window.close()` and never
`exitProcess`: closing the window ends the game loop, which runs `destroy()`, which runs
this hook. `exitProcess` would skip all of it.

## On-disk format

JSON through `engine.data`'s Jackson mapper, wrapped in `ScoreboardData`
(`score/ScoreRepository.kt`) - a concrete class rather than a bare `List<ScoreEntry>`,
because the engine's reified `loadObject<T>` resolves to a raw `Class` at the call site
and a top-level generic list would lose its element type. A wrapper class's *field*
generics survive Jackson's reflective introspection.

The engine's mapper has default typing enabled - `ObjectMapper().registerModule
(KotlinModule.Builder().build()).enableDefaultTyping().configure(FAIL_ON_UNKNOWN_PROPERTIES,
false).configure(FAIL_ON_INVALID_SUBTYPE, false)`, verified by disassembling
`DataImpl.class`'s companion-object static initialiser inside the exact
`pulse-engine-0.13.0.jar` this build resolves - so the list is written as a two-element
`[className, elements]` pair. Real file, from `~/EnPustTil/scoreboard.json`:

```json
{"entries":["java.util.ArrayList",[
  {"initials":"AAA","score":1989,"seed":20260902,"timestampMs":1785935323251},
  {"initials":"AEE","score":229,"seed":20260902,"timestampMs":1786459993180}
]]}
```

**This is a measured claim, not an assumed one, and it was wrong once already.**
`ScoreRepositoryTest`'s fake `ScoreStore` originally used a bare `jacksonObjectMapper()` -
same library as the engine, but not the same mapper configuration - which cannot parse
this file at all: it throws `MismatchedInputException`, which the load path already
catches and turns into `null`, so the mismatch was invisible and every test read back an
empty board instead of failing. `an engine-written scoreboard round-trips through this
store's mapper` (`ScoreRepositoryTest`) now pastes this exact file as a literal and
asserts it loads to two entries - the property this package actually depends on (day two
must be able to read day one's file) is now the one under test, not merely the schema.

(The real file is one line; the concrete class name in slot 0 varies with whatever list
implementation Jackson saw. **If you hand-edit this file, keep that wrapper** - dropping it
makes the file unreadable, which degrades to an empty board rather than a crash, but the
day's scores are then gone from the display.)

**Every entry stores the seed it was earned under.** That is the whole day-two mechanism:
`ScoreRepository.topN` (`score/ScoreRepository.kt:69`) filters to `todaySeed`,
`registerScore` (`score/ScoreRepository.kt:77`) stamps `todaySeed`, and
`todaySeed` comes from `application.cfg`'s `dailySeed` key (`EnPustTil.kt:1084-1086`, falling
back to the compile-time `DAILY_SEED = 20260902L` at `EnPustTil.kt:2484`). Change the seed
for day two and you get a fresh water column *and* a fresh leaderboard, while day one's
rows stay in the same file under the old seed - preserved, just no longer displayed.
`resolveDailySeed` (the live resolution path - it checks for a numeric `dailySeed` key
first, then falls back to `parseDailySeed` for a string one) returns the fallback for both
an absent key and an unparseable one, so a technician's typo reuses day one's seed rather
than crashing the booth.

## Initials entry

`InitialsEntry` is a pure state machine owned by `render/RunLifecycle.kt` (field at `:212`),
not driven in parallel from `EnPustTil` - so the lifecycle's existing edge-tracking and
idle-timeout machinery covers initials entry too.

- Three slots, all starting at `'A'`. Up/down cycles the letter in the current slot; the
  confirm button locks it and advances. After the third, `complete` is true.
- **Character set: `A`-`Z` only, wrapping in both directions** (`cycleLetter`,
  `score/InitialsEntry.kt:93`). No digits, no punctuation, no space. `isValidInitials`
  (`score/InitialsEntry.kt:108`) is the same rule, applied defensively on load;
  `registerScore` (`score/ScoreRepository.kt:85`) applies it again on write -
  `uppercase().filter { it in 'A'..'Z' }.take(3).padEnd(3, 'A')`.
- **Input is LEVEL readings, and this class does its own edge detection** (`update`,
  `score/InitialsEntry.kt:63`). The engine's `Gamepad` exposes only `isPressed`/`getAxis` -
  there is no `wasClicked` for a controller button - so a held stick or a stuck
  arcade-encoder button reads true every single frame. Tracking the previous frame here is
  what stops a stuck button blasting through the alphabet at 60 letters a second. There is
  deliberately **no auto-repeat** while held: simpler and fully deterministic.
- If both up and down edge on the same frame, up wins, deterministically
  (`score/InitialsEntry.kt:75`).

Wiring: `readInitialsCycle` (`EnPustTil.kt:2308`) reads stick Y past `stickDeadzone` (a
booth-configurable field, resolved from application.cfg - see `parseDeadzone`) on *any*
connected gamepad, or the UP/DOWN keys. Confirm reuses the same `actionPressed`
signal as the restart button (`EnPustTil.kt:1567-1580` - gamepad `START` or `A`, or
`SPACE`), so the cabinet needs no third physical input. On-screen help text is no longer a
fixed constant: it is composed per-device by `render/ControlHints.kt`'s
`initialsHelp(arcade, startLabel)` and cached on `EnPustTil` as `hintInitialsHelp`, rebuilt
only when a gamepad's presence flips - so the line names whichever device (arcade buttons
or keyboard keys) is actually connected, rather than a string that could name a rebound
button wrongly.

**When entry is offered, and when it ends.** `RUN_OVER` moves into `ENTER_INITIALS` on its
own after the dwell, with no press required, whenever `Leaderboard.isWorthRecording(banked)`
- i.e. `score > 0` (`render/RunLifecycle.kt:328`). A 0-point run never sees the prompt, so
it cannot block the queue with a data-entry screen. If nobody touches the controls for
`INITIALS_IDLE_TIMEOUT_SECONDS` (15 s, `render/RunLifecycle.kt:399`) the entry
**auto-submits** whatever letters were set - "AAA" if untouched - rather than discarding
it. A qualifying score that exists is worth more to the prize draw than a clean
abandonment, and the cabinet must recover to IDLE on its own regardless.

## Booth operations

**Where the board lives:** `C:\Users\<user>\EnPustTil\` on the booth machine
(`~/EnPustTil/` on a dev Mac). That directory holds `scoreboard.json`, transient
`scoreboard.<uuid>.tmp` files (uniquely named per write, not a single shared
`scoreboard.json.tmp` any more - swept automatically on the next launch if one is ever
left behind by a crash), and the `scoreboard-backup-<epochMs>.json` backups.

**Back it up:** copy the whole `EnPustTil` folder to a USB stick at the end of each day.
Do it after a clean shutdown (see below) so the last save has landed. The game never reads
the backup files, so copying or deleting them cannot affect a running cabinet.

**Shut the cabinet down cleanly:** on the pause screen (Esc, keyboard only), hold Q. That
routes through `engine.window.close()` and the final synchronous save runs. Do not kill the
process if you can avoid it - though per the lifecycle section above, a kill loses at most
the run being entered right now.

**Day two:** open `application.cfg` next to the `.exe`, uncomment the `dailySeed` line and
give it a different number, then restart. Fresh column, fresh board. Day one's rows stay in
`scoreboard.json` and can be recovered later by pointing `topN` at the old seed.

**Draw the raffle winner:** the `winner` command (`score/ScoreRepository.kt:264`) is registered unconditionally, not
behind `EPT_DEV`. It takes every entry for today's seed, gives each `10 + (3 * score/best)`
tickets - so 10 to 13, weighted toward big scores but with every qualifying entry in real
contention - and picks one. **Caveat, verified against the jar: there is currently no way
to type it.** `init.pes` binds F1 to `showConsole`, but nothing in pulse-engine 0.13.0 ever
runs a `.pes` script, and the `CommandLine` widget that registers `showConsole` is
constructed by no class in the jar and by no class of ours. Until someone adds
`engine.service.add(CommandLine())` in `EnPustTil.onCreate`, draw the winner by hand from
`scoreboard.json` - the entries for today's seed are all there, and the weighting is three
lines of arithmetic.

**If the file is corrupt:** the game will start with an empty board rather than crashing,
which is the safe failure but is also silent. To recover, stop the game, rename the newest
`scoreboard-backup-<epochMs>.json` to `scoreboard.json`, and restart. Check the log for
`ScoreRepository loaded N score(s) from ...` to confirm what came back.

## Testing

```bash
./gradlew test --tests "score.*"                       # this package
./gradlew test --tests "score.AtomicFileSwapTest"      # one class
./gradlew test --tests "render.RunLifecycleTest"       # the ENTER_INITIALS transitions
```

| Test | Covers |
|---|---|
| `LeaderboardTest` | Rank order, the earlier-timestamp tie-break, `topN` truncation and the `n <= 0` boundary, and `isWorthRecording` at 0 / positive / negative. |
| `InitialsEntryTest` | Cycling, A-Z wrap in both directions, edge-triggering (60 held frames cycle once), slot advance, no-op after completion, `reset` clearing prior edge state, plus `isValidInitials` and `sanitizeEntries`. |
| `AtomicFileSwapTest` | `promoteAtomically` against a **real** temp directory, not a mock - promotion, replacing an existing live file, a missing temp reported as failure with the live file untouched, a 10 KB payload landing whole, and a promotion that cannot happen at all (a non-empty directory where the live file should be) reporting why through `onFailure`. |
| `ScoreRepositoryTest` | `registerScore` through to the live file (not just the temp file), no temp file left behind after a success, two sequential saves never sharing a temp name, a failed promotion reported through `onSaveFailure` (exactly two messages, pinned), a corrupt `scoreboard.json` starting a fresh board, a stale `.tmp` swept on load, a zero-score run never touching disk, the shutdown save promoting the tail of scores, yesterday's scores staying on disk but off today's filtered board, and - the one that actually matters most - a **real, byte-for-byte engine-written `scoreboard.json`** round-tripping through the store's mapper. |

The seam is `ScoreStore` (`score/ScoreStore.kt`): the real one wraps `engine.data`, the
test's `FakeStore` is a real temp directory with no engine at all, so every write path
above - including the atomic promote - is exercised for real rather than mocked away.

**One thing worth remembering if you touch `FakeStore`'s mapper again:** matching the
engine's Jackson *module* is not the same claim as matching its *configuration*. The
engine's mapper has `enableDefaultTyping()` switched on, which changes the wire format
(see "On-disk format" above); a mapper built without it silently fails to read a
real engine-written file and, because the load path already catches and nulls out any
read failure, that mismatch does not show up as a test failure on its own - it shows up as
every entry quietly vanishing. `an engine-written scoreboard round-trips through this
store's mapper` pastes a real file as a literal specifically so this can regress loudly.

One mutation from the original `AtomicFileSwapTest` mutation run still survives: dropping
`REPLACE_EXISTING` from the `ATOMIC_MOVE` call. On APFS, `ATOMIC_MOVE` already implies
replace-on-exists, so no test on a Mac can distinguish it. It is kept for NTFS, which is
the booth's actual filesystem.
