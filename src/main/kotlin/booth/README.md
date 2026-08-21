# `booth/` — surviving two unattended days

↑ [Repo root](../../../../README.md) · [CLAUDE.md](../../../../CLAUDE.md) · [Design spec](../../../../docs/superpowers/specs/2026-08-04-en-pust-til-design.md) · [Docs map](../../../../docs/README.md)

## Why this package exists

The target is a **two-day unattended arcade cabinet** at the Capra booth: a windowed GUI `.exe`,
no console, no attendant babysitting the JVM. Three files, three separate ways that machine could
go dark with nobody watching:

- **Nobody can read stdout.** The release binary is `PE32 executable (GUI)`, which Windows starts
  with no attached console — so every `Logger.warn`/`Logger.error` this project relies on to
  survive `application.cfg`'s booth-default `logLevel = WARN` was being printed into a void.
  `BoothLog` gives it somewhere to land.
- **The engine's own crash handler is worse than the crash.** An uncaught throwable at the booth
  does not just end the run — it opens a text editor, full-screen, in front of a queue.
  `CallbackGuard` exists so the engine's handler never fires at all.
- **The five callback names needed one home, not five call sites each inventing their own
  string.** `CallbackSites` is that home.

## Files

| File | What it owns |
|---|---|
| `BoothLog.kt` | `install`/`logDirectory`/`pruneOldLogs` and the `TeeOutputStream` that makes `System.out`/`System.err` write to a file as well as wherever they already went. |
| `CallbackGuard.kt` | `run(site, body)` — catches everything a wrapped call throws, counts it per site, and reports it (rate-limited) through an injected `onFailure`. |
| `CallbackSites.kt` | The five site-name constants `CallbackGuard` is keyed by: `CREATE`, `FIXED_UPDATE`, `UPDATE`, `RENDER`, `DESTROY`. |

---

## `BoothLog` — a log file the engine cannot write

`LogTarget` is a Kotlin enum with exactly two entries, `STDOUT` and `CONSOLE` — verified with
`javap -p no/njoh/pulseengine/core/shared/utils/LogTarget.class` against the real
`pulse-engine-0.13.0.jar` (`BoothLog.kt:11-13`) — so `engine.config.logTarget` has no `FILE` option
and no third value can be added from outside the jar. Combined with the GUI binary having no
console (`BoothLog.kt:14-17`), every `WARN` this project counts on to reach a technician was going
nowhere.

The fix does not touch the engine at all: `Logger.logToStandardOut` ends in
`System.out.println` (verified from bytecode — `getstatic java/lang/System.out` then
`invokevirtual PrintStream.println`, `BoothLog.kt:19-21`), so replacing `System.out`/`System.err`
with a tee (`TeeOutputStream`, `BoothLog.kt:97-121`) captures every line the engine and the game
log — and anything a library prints or a stack trace dumps besides — with no dependency on which
`LogTarget` is configured. `install` (`BoothLog.kt:71-90`) builds that tee and **never throws**: a
read-only profile directory or a full disk costs the booth its log file, deliberately, rather than
the window that has not opened yet.

It runs from `main`, before `PulseEngine.run<EnPustTil>()` — `EnPustTil.kt:59-65` — specifically
because a failure during engine start-up happens inside `run()` and would otherwise be logged to
the same unreadable stdout. `logDirectory` (`BoothLog.kt:47`) puts the log beside the scoreboard,
not in a temp folder — one folder copied to a USB stick at end of day carries both. `MAX_RETAINED_LOGS`
(`BoothLog.kt:36`) and `pruneOldLogs` (`BoothLog.kt:50-57`) exist because `tools/booth/start-booth.bat`
relaunches the game on every exit and each launch opens a new file, so a crash-restart loop would
otherwise fill the disk over two days.

## `CallbackGuard` — the engine's own handler opens Notepad

Disassembled from `pulse-engine-0.13.0.jar`: `PulseEngineImpl.runGameLoop` wraps the whole loop
body in one `Exception table` entry catching `Throwable`, and its handler logs
`"Fatal error in game loop - shutting down"` at `ERROR`, calls
`Logger.writeAndOpenCrashReport`, and shuts down (`CallbackGuard.kt:8-15`).
`writeAndOpenCrashReport` calls `CrashReportBuilder.buildAndOpen`, which writes a
`crash-report-<epoch>.txt` and then calls `FileUtilsKt.openFile(absolutePath)` — on Windows, the
registered handler for `.txt` (`CallbackGuard.kt:16-21`). `application.cfg` picks `FULLSCREEN`
specifically so no title bar or close button is reachable; this handler puts **Notepad** on the
display anyway, for an attendee to explore, and ends the process besides.

`run` (`CallbackGuard.kt:53-79`) catches `Throwable`, not `Exception` — narrower would let an
`Error` through to the engine's own handler and open Notepad after all (`CallbackGuard.kt:59-62`).
Failures are counted per site (`failureCount`, `CallbackGuard.kt:51`) and reported through the
injected `onFailure`, rate-limited to `MAX_REPORTS_PER_SITE` (5, `CallbackGuard.kt:84`) plus one
more call that announces the suppression — `onRender` runs at up to 120 Hz, and an unthrottled
report there would write two days of identical stack traces into the booth log and bury everything
else. `totalFailures` and `lastFailureSite` (`CallbackGuard.kt:44-49`) are what
`render.BoothStatus`'s attract-screen line reads to say a boot failed at all.

Wired in `EnPustTil.kt:822-824`: `guard` is built once with an `onFailure` that logs through
`Logger.error` — "the frame is lost, the cabinet continues". Every engine callback is a one-line
delegation, `override fun on…() = guard.run(CallbackSites.X, xBody)` —
`onCreate`/`EnPustTil.kt:950`, `onFixedUpdate`/`:1387`, `onUpdate`/`:1449`,
`onDestroy`/`:1672`, `onRender`/`:1691` — and `CallbackGuardTest` source-scans for exactly that
shape, so none of the five may grow a second statement. The real bodies (`createGame`,
`fixedUpdateGame`, `updateGame`, `renderGame`, `destroyGame`) are held as `val …Body: () -> Unit`
fields (`EnPustTil.kt:829-833`) rather than written inline, because a capturing lambda allocates
per call and four of these run every frame — see `CallbackGuard`'s own ALLOCATION note.

## `CallbackSites` — one name, agreed everywhere it is used

Five constants, `CREATE`/`FIXED_UPDATE`/`UPDATE`/`RENDER`/`DESTROY` (`CallbackSites.kt:23-27`),
rather than a literal string at each `guard.run(...)` call site — a typo in a literal would
silently create a sixth counter that never merges with the site it meant to name.

It lives beside `CallbackGuard`, in `booth/`, rather than in `EnPustTil`'s own companion object,
for one reason: `CallbackGuard` is these five names' only real consumer (`EnPustTil` just supplies
the literal strings back to it), and putting them in the package that owns the concept lets that
companion object stay `private` — it does not have to widen `EnPustTil`'s other, unrelated
constants (`DAILY_SEED`, `DEFAULT_STICK_DEADZONE`, `HUD_Z_ORDER`, …) just to make these five
visible to a test in a different package (`CallbackSites.kt:10-14`).

`internal`, not `private` (`CallbackSites.kt:21`): `render.BoothStatusTest` and the root-package
`AttractScreenTest` both loop every real site name through `BoothStatus.line`'s drawability check,
so a future rename — an en dash slipped into a call-site name, say — cannot silently vanish from
the attract screen with no test failing (`CallbackSites.kt:15-19`). `render/BoothStatus.kt:24`
reads `guard.failureCount(CallbackSites.CREATE)` to decide whether the boot itself failed — that
is the one place outside this package and `EnPustTil` that names a site directly.
