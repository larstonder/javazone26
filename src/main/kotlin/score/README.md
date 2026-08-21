# `score/` - persistence and the arcade leaderboard

↑ [Repo root](../../../../README.md) · [CLAUDE.md](../../../../CLAUDE.md) · [Design spec](../../../../docs/superpowers/specs/2026-08-04-en-pust-til-design.md) · [Docs map](../../../../docs/README.md)

## Why this package looks paranoid

The target is a **two-day unattended arcade cabinet** at the Capra booth, in front of a
queue, with nobody watching the machine. Daily prizes are drawn from this leaderboard, so
a lost score is a real failure with a person attached to it. Every decision below -
temp-file-then-rename, timestamped backups, a defensive filter on load, a synchronous save
at shutdown, auto-submitting abandoned initials - is that one requirement, spelled out.

The second constraint is queue throughput. Design spec section 12: **three-letter initials, never
a form field**. That is also why no name and no e-mail is ever collected - there is no
personal data here and therefore no consent flow to build.

Four of the five files import nothing from `no.njoh.pulseengine`. Only `ScoreRepository`
touches the engine or a file, which is what lets the ranking, the validation and the
initials state machine be unit-tested without a GL context.

## Files

| File | What it owns |
|---|---|
| `ScoreEntry.kt` | The record: `initials`, `score`, `seed`, `timestampMs`. Four fields, no logic. |
| `Leaderboard.kt` | Pure ranking and selection - `rank`, `topN`, `isWorthRecording`. |
| `InitialsEntry.kt` | The three-letter entry state machine, plus `isValidInitials` and `sanitizeEntries` (the load-time filter, which lives here because it is the same shape rule). |
| `AtomicFileSwap.kt` | `promoteAtomically(temp, live)` - one function, one `Files.move`. |
| `ScoreRepository.kt` | The only engine-aware class: an engine `Service` that loads on create, saves on every registration, rolls backups, saves synchronously on destroy, and registers the `winner` raffle command. |

## The write path

`engine.data.saveObject`/`saveObjectAsync` write **directly to the target path** - verified
by decompiling `DataImpl`: Jackson to a byte array, then `FilesKt.writeBytes(file, bytes)`,
which truncates and overwrites in place. There is no temp file inside the engine. So this
package builds one on top:

1. `ScoreRepository.saveAsync` (`score/ScoreRepository.kt:83`) serialises a **snapshot**
   (`entries.toList()`, not the live list) to `scoreboard.json.tmp`. The snapshot matters:
   `saveObjectAsync` runs on `Dispatchers.IO`, so without it a score registered mid-write
   could mutate the list out from under Jackson.
2. The completion callback calls `promoteAtomically` (`score/AtomicFileSwap.kt:43`), which
   is `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` with a caught fallback to a plain replace
   if the filesystem cannot do it atomically.

What each step actually defends against:

| Failure | Outcome | Why |
|---|---|---|
| Crash or power cut **during the temp write** | Live file untouched | `scoreboard.json` is never opened for writing. The half-written `.tmp` is simply overwritten next time and never promoted. |
| Crash **between the temp write and the rename** | Equivalent to the write never happening | The safe outcome: the previous complete board survives. |
| Crash **during the rename** | Fully-old or fully-new, never torn | A rename is a directory-entry swap, not a content write. |
| True hardware power loss | **Not covered** | Nothing calls `fsync`/`force`. The guarantee is "no torn file", not "every acknowledged write has reached the platter". Honest limit of what `engine.data`'s API allows without reimplementing serialisation. |
| A corrupt or hand-mangled `scoreboard.json` | Fresh empty board, no crash | `loadEntries` (`:123`) catches, then `sanitizeEntries` (`score/InitialsEntry.kt:115`) drops anything that is not three uppercase letters with a positive score. |

**Backups.** `maybeRollBackup` (`:92`) writes `scoreboard-backup-<epochMs>.json` at most
once every `BACKUP_INTERVAL_MS` (30 minutes, `:203`). Each is a uniquely named file, never
overwritten and never read back by the game, so an interrupted backup write can only ever
damage that one file - which is why it deliberately skips the temp+promote dance. Note
that the timer only advances when a score is registered: a cabinet nobody plays for an hour
writes no backups, and the first backup of a session cannot happen until 30 minutes after
process start (`lastBackupTimeMs` is initialised at construction, `:43`).

**Where on disk.** `scoreFile` (`:197`) is `File(engine.config.saveDirectory, name)`.
`saveDirectory` defaults to `File(System.getProperty("user.home"), gameName).absolutePath`
(from `ConfigurationImpl`'s constructor, re-derived whenever `gameName` changes), and
`gameName = EnPustTil` in `application.cfg`. So:

- Windows booth: `C:\Users\<user>\EnPustTil\scoreboard.json`
- macOS/Linux dev: `~/EnPustTil/scoreboard.json`

`saveDirectory` is itself a config key and can be overridden in `application.cfg` if the
booth machine needs the board somewhere else.

## Lifecycle hooks

`ScoreRepository` extends the engine's `Service` and is registered with
`engine.service.add(scoreRepository)` in `EnPustTil.kt:685`. Verified call order (from
decompiling `ServiceManagerImpl`): `add()` queues the service; `init()` runs right after
`PulseEngineGame.onCreate()` returns and calls `onCreate` on everything queued; `destroy()`
runs during shutdown, just after `PulseEngineGame.onDestroy()`, and calls `onDestroy` on
every registered service.

- `onCreate` (`:45`) loads the board and registers the `winner` command.
- `onDestroy` (`:115`) does a **synchronous** save. Deliberate: there is no guarantee the
  process survives long enough for an async write to finish, and a few milliseconds of
  blocking at shutdown is a cost the render thread never pays during play.

**If the cabinet is killed without `onDestroy` running** - `pkill -9`, power cut, a hung
process force-quit - nothing that was already registered is lost. Every `registerScore`
already triggered its own async save plus promotion (`EnPustTil.kt:1063` is the only
caller, fired on `RunLifecycle.initialsJustCompleted`). The only window is the few
milliseconds between a registration and its promotion; a run that was still on screen was
never registered in the first place. The `onDestroy` save is belt and braces, not the
mechanism.

This is why `RunLifecycle`'s exit path calls `engine.window.close()` and never
`exitProcess` (`EnPustTil.kt:1020-1024`): closing the window ends the game loop, which runs
`destroy()`, which runs this hook. `exitProcess` would skip all of it.

## On-disk format

JSON through `engine.data`'s Jackson mapper, wrapped in `ScoreboardData` (`:208`) - a
concrete class rather than a bare `List<ScoreEntry>`, because the engine's reified
`loadObject<T>` resolves to a raw `Class` at the call site and a top-level generic list
would lose its element type. A wrapper class's *field* generics survive Jackson's
reflective introspection.

The engine's mapper has default typing enabled, so the list is written as a two-element
`[className, elements]` pair. Real file, from `~/EnPustTil/scoreboard.json`:

```json
{"entries":["java.util.ArrayList",[
  {"initials":"AAA","score":1989,"seed":20260902,"timestampMs":1785935323251},
  {"initials":"AEE","score":229,"seed":20260902,"timestampMs":1786459993180}
]]}
```

(The real file is one line; the concrete class name in slot 0 varies with whatever list
implementation Jackson saw. **If you hand-edit this file, keep that wrapper** - dropping it
makes the file unreadable, which degrades to an empty board rather than a crash, but the
day's scores are then gone from the display.)

**Every entry stores the seed it was earned under.** That is the whole day-two mechanism:
`topN` (`:53`) filters to `todaySeed`, `registerScore` (`:61`) stamps `todaySeed`, and
`todaySeed` comes from `application.cfg`'s `dailySeed` key (`EnPustTil.kt:541`, falling
back to the compile-time `DAILY_SEED = 20260902L` at `EnPustTil.kt:1633`). Change the seed
for day two and you get a fresh water column *and* a fresh leaderboard, while day one's
rows stay in the same file under the old seed - preserved, just no longer displayed.
`parseDailySeed` returns the fallback for both an absent key and an unparseable one, so a
technician's typo reuses day one's seed rather than crashing the booth.

## Initials entry

`InitialsEntry` is a pure state machine owned by `render/RunLifecycle.kt` (field at `:161`),
not driven in parallel from `EnPustTil` - so the lifecycle's existing edge-tracking and
idle-timeout machinery covers initials entry too.

- Three slots, all starting at `'A'`. Up/down cycles the letter in the current slot; the
  confirm button locks it and advances. After the third, `complete` is true.
- **Character set: `A`-`Z` only, wrapping in both directions** (`cycleLetter`, `:89`). No
  digits, no punctuation, no space. `isValidInitials` (`:104`) is the same rule, applied
  defensively on load; `registerScore` (`:65`) applies it again on write -
  `uppercase().filter { it in 'A'..'Z' }.take(3).padEnd(3, 'A')`.
- **Input is LEVEL readings, and this class does its own edge detection** (`update`, `:59`).
  The engine's `Gamepad` exposes only `isPressed`/`getAxis` - there is no `wasClicked` for a
  controller button - so a held stick or a stuck arcade-encoder button reads true every
  single frame. Tracking the previous frame here is what stops a stuck button blasting
  through the alphabet at 60 letters a second. There is deliberately **no auto-repeat**
  while held: simpler and fully deterministic.
- If both up and down edge on the same frame, up wins, deterministically (`:73`).

Wiring: `readInitialsCycle` (`EnPustTil.kt:1491`) reads stick Y past `stickDeadzone` (a
booth-configurable field, resolved from application.cfg - see `parseDeadzone`) on *any*
connected gamepad, or the UP/DOWN keys. Confirm reuses the same `actionPressed`
signal as the restart button (`EnPustTil.kt:956` - gamepad `START` or `A`, or `SPACE`), so
the cabinet needs no third physical input. On-screen help text is
`ScreenText.INITIALS_HELP` (`EnPustTil.kt:202`).

**When entry is offered, and when it ends.** `RUN_OVER` moves into `ENTER_INITIALS` on its
own after the dwell, with no press required, whenever `Leaderboard.isWorthRecording(banked)`
- i.e. `score > 0` (`render/RunLifecycle.kt:271`). A 0-point run never sees the prompt, so
it cannot block the queue with a data-entry screen. If nobody touches the controls for
`INITIALS_IDLE_TIMEOUT_SECONDS` (15 s, `render/RunLifecycle.kt:334`) the entry
**auto-submits** whatever letters were set - "AAA" if untouched - rather than discarding
it. A qualifying score that exists is worth more to the prize draw than a clean
abandonment, and the cabinet must recover to IDLE on its own regardless.

## Booth operations

**Where the board lives:** `C:\Users\<user>\EnPustTil\` on the booth machine
(`~/EnPustTil/` on a dev Mac). That directory holds `scoreboard.json`, a transient
`scoreboard.json.tmp`, and the `scoreboard-backup-<epochMs>.json` backups.

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

**Draw the raffle winner:** the `winner` command (`:174`) is registered unconditionally, not
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
| `AtomicFileSwapTest` | `promoteAtomically` against a **real** temp directory, not a mock - promotion, replacing an existing live file, a missing temp reported as failure with the live file untouched, and a 10 KB payload landing whole. |

Two gaps worth knowing about. There is **no `ScoreRepositoryTest`**: load, save, backup
rolling and the `winner` draw are exercised only by running the game (the round-trip was
verified by hand across two separate processes - see
`.superpowers/sdd/2026-08-04-en-pust-til-gameloop/score-persistence-report.md` section 5). And the
one mutation that survived the original mutation run was dropping `REPLACE_EXISTING` from
the `ATOMIC_MOVE` call: on APFS, `ATOMIC_MOVE` already implies replace-on-exists, so no test
on a Mac can distinguish it. It is kept for NTFS, which is the booth's actual filesystem.
