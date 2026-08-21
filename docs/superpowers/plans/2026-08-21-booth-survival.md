# Booth Survival Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the cabinet survive two unattended days in front of a queue — it must not die on one exception, must not lock itself out on one stuck button, must not lose a score, must leave a readable log, and must be reconfigurable at the venue without a compiler.

**Architecture:** Every fix is a defensive layer around code that already works. Three of them are new engine-free classes in the established pure-logic-extracted-for-testing pattern (`BoothLog`, `CallbackGuard`, `LifecycleInputEdges`), one is a seam that finally makes `ScoreRepository` testable (`ScoreStore`), and the rest are small edits at existing call sites. No gameplay behaviour changes. Nothing in `dive/` is touched.

**Tech Stack:** Kotlin 2.2.20, Pulse Engine 0.13.0, `kotlin.test` on JUnit Platform, Gradle (`./gradlew`), launch4j for the Windows `.exe`.

**Spec:** `docs/superpowers/specs/2026-08-04-en-pust-til-design.md` (§12 the booth, §17 amendments and platform findings) and `docs/superpowers/specs/2026-08-11-outstanding-work.md` §5 (the untested Windows binary). The booth-readiness audit that motivated this plan is summarised in the Context section below.

## Context: why these ten tasks and not others

A readiness audit on 2026-08-21 (13 days before JavaZone) found the simulation finished and the cabinet-survival layer absent. `./gradlew build` is green (491 tests, 0 failures, 0 skipped) and there is not one `TODO`/`FIXME`/`HACK` in production source — but the worst problems are all in code paths no test can reach.

**Deliberately out of scope for this plan**, and each needs its own:

- **Audio** (spec §11, §12). There is no `Sound` asset, no `engine.audio` call, no audio file anywhere. The implementation plan deferred it on purpose (`2026-08-04-en-pust-til-gameloop.md:2113-2121`). It is the game's stated queue-forming mechanism and needs a design pass, not a task list.
- **The cash-out** (spec §12, on §15's do-not-cut list). `DiveSim` fires `diveEnded` correctly and nothing in `render/` consumes it.
- **The bleed trail visual** (spec §5, also do-not-cut). The mechanic is wired; there is no trail on screen.
- **Zone-specific movement** (spec §3 — kelp fronds, trench currents). An owner decision, not a defect.

**Windows access is uncertain**, which is why Tasks 1, 2, 3 and 7 come first: they are the ones that make an *untested* binary survivable. Task 10 is the protocol to run the moment a machine is available.

## Global Constraints

Copied verbatim from `CLAUDE.md`; every task's requirements implicitly include this section.

- **Allman braces, 4 spaces, no wildcard imports** — match the surrounding file.
- **Comments explain *why*, at length, citing evidence** (a decompiled engine class, a screenshot, a measurement). When you fix something subtle, leave the same kind of note.
- **No per-frame allocation in the render path.** HUD text formatting is the one explicit exemption.
- **Never call `Surface.drawQuad()`/`drawLine()`** (or their `*Vertex` forms) — they rasterise fully transparent on macOS/Apple Silicon with no error. Use `fillRect`/`fillRectCentred` from `render/Draw.kt`. `DrawTest.no production source draws a quad or a line` fails the build if you do.
- **The default font can only draw U+0020..U+011F.** Anything above renders as nothing at all — no glyph, no x-advance, silently. Every drawn string goes through `ScreenText`; `AttractScreenTest` asserts they are all drawable. Use `ScreenText.SEPARATOR` (a middle dot) where you want an em dash.
- **`dive/` imports nothing from the engine and holds no presentation state.** Nothing in this plan changes a file under `dive/`.
- **Screen-space code prefers `surface.config.width/height` over `engine.window.*`** — `engine.window.width/height` are physical framebuffer pixels, not the logical config size.
- **`render/CameraRig.kt` is the only writer of `engine.gfx.mainCamera`** — `MainCameraOwnershipTest` enforces it by source scan.
- **The engine's `Gamepad` exposes only `isPressed`/`getAxis`** — no `wasClicked`. Consumers do their own previous-frame edge detection.
- **Tests use `kotlin.test` on JUnit Platform with backticked descriptive names.** A test that cannot fail is worse than no test — assert the relationship that would actually break.
- **Commit after every task.** Run `./gradlew build` before each commit; it must be green.

## File Structure

**New production files**

| File | Responsibility |
|---|---|
| `src/main/kotlin/booth/BoothLog.kt` | Tee `System.out` to a dated log file; derive the log directory; prune old logs. Engine-free. |
| `src/main/kotlin/booth/CallbackGuard.kt` | Catch a `Throwable` out of one named call site, count it, rate-limit the report. Engine-free (sink is injected). |
| `src/main/kotlin/render/LifecycleInputEdges.kt` | Per-source edge detection and stuck-source detection for lifecycle input. Engine-free, allocation-free after warm-up. |
| `src/main/kotlin/render/BoothStatus.kt` | Compose the one-line booth status string (stuck inputs, unmapped pads, active seed). Pure. |
| `src/main/kotlin/score/ScoreStore.kt` | The persistence seam: interface + `EngineScoreStore` wrapping `engine.data`. |
| `tools/booth/start-booth.bat` | The Windows watchdog that relaunches the `.exe` if it ever exits. |
| `docs/booth/windows-verification.md` | The on-hardware protocol for Task 10. |

**Modified production files**

| File | Change |
|---|---|
| `src/main/kotlin/EnPustTil.kt` | Install `BoothLog` in `main`; route all five engine callbacks through `CallbackGuard`; feed `LifecycleInputEdges`; select the gameplay pad; read the button map from config; rebuild `sim` on the return to IDLE; draw the booth status line. |
| `src/main/kotlin/render/RunLifecycle.kt` | Add the `justReturnedToIdle` one-tick flag. |
| `src/main/kotlin/render/GamepadScan.kt` | Add `selectGameplayPad`. |
| `src/main/kotlin/score/ScoreRepository.kt` | Depend on `ScoreStore`; unique temp filenames; log a failed promotion; sweep stale temps on load. |
| `src/main/kotlin/score/AtomicFileSwap.kt` | Report the swallowed exception through an injected callback. |
| `src/main/resources/application.cfg` | Document the new booth keys (button map, deadzone, log retention). |
| `build.gradle.kts` | Ship `start-booth.bat` in the release zip. |

**New test files:** `BoothLogTest`, `CallbackGuardTest`, `LifecycleInputEdgesTest`, `BoothStatusTest`, `GamepadConfigTest`, `ScoreRepositoryTest`, `BoothLauncherTest`, plus additions to `RunLifecycleTest`, `GamepadScanTest`, `AtomicFileSwapTest` and `AttractScreenTest`.

---

### Task 1: A log file that survives the booth

**Why first:** every diagnostic in every later task is worthless without this. `LogTarget` is a Kotlin enum with exactly two entries — `STDOUT` and `CONSOLE`, verified with `javap -p no/njoh/pulseengine/core/shared/utils/LogTarget.class`. **There is no FILE target and one cannot be added.** The release binary is `PE32 executable (GUI)`, which on Windows gets no attached console, so every log line the game writes is discarded. That silently defeats `logGamepadDiagnostics`' WARN (written specifically to survive the booth log level) and the recovery procedure in `score/README.md` that says "check the log".

The mechanism: `Logger.logToStandardOut` ends in `System.out.println` (verified at bytecode offset 104: `getstatic java/lang/System.out`, 108: `invokevirtual PrintStream.println`). So replacing `System.out` with a tee captures every engine and game log line, without touching the engine.

**Files:**
- Create: `src/main/kotlin/booth/BoothLog.kt`
- Create: `src/test/kotlin/booth/BoothLogTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt:44` (the `main` function)

**Interfaces:**
- Consumes: nothing.
- Produces: `booth.BoothLog.logDirectory(home: String, gameName: String): java.io.File`, `booth.BoothLog.install(dir: java.io.File, startedAtMillis: Long, keep: Int = MAX_RETAINED_LOGS): java.io.File?`, `booth.BoothLog.pruneOldLogs(dir: java.io.File, keep: Int)`, `booth.TeeOutputStream(primary: java.io.OutputStream, secondary: java.io.OutputStream)`, `booth.BoothLog.MAX_RETAINED_LOGS: Int`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/booth/BoothLogTest.kt`:

```kotlin
package booth

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoothLogTest
{
    private fun tempDir(): File = File.createTempFile("boothlog", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Test
    fun `the tee writes every byte to both streams`()
    {
        val a = ByteArrayOutputStream()
        val b = ByteArrayOutputStream()
        PrintStream(TeeOutputStream(a, b), true).use { it.print("hello booth") }

        assertEquals("hello booth", a.toString())
        assertEquals("hello booth", b.toString())
    }

    @Test
    fun `closing the tee does not close the primary stream`()
    {
        // System.out is the primary. Closing it would silence the process for good, and
        // the JVM does not give it back.
        var primaryClosed = false
        val primary = object : ByteArrayOutputStream() { override fun close() { primaryClosed = true } }
        val secondary = ByteArrayOutputStream()

        TeeOutputStream(primary, secondary).close()

        assertTrue(!primaryClosed, "the tee must never close the stream it is teeing from")
    }

    @Test
    fun `the log directory hangs off the save directory the engine would use`()
    {
        // ConfigurationImpl's default saveDirectory is <homeDir>/<gameName> — verified by
        // disassembling its constructor. The log sits beside the scoreboard so one folder
        // copied to a USB stick at end of day carries both.
        val dir = BoothLog.logDirectory("/home/booth", "EnPustTil")

        assertEquals(File("/home/booth/EnPustTil/logs"), dir)
    }

    @Test
    fun `install writes a log file and everything printed afterwards lands in it`()
    {
        val dir = tempDir()
        val original = System.out
        try
        {
            val file = BoothLog.install(dir, startedAtMillis = 1_700_000_000_000L)
            assertNotNull(file)
            println("a line that must reach the file")
            System.out.flush()

            assertTrue(file.readText().contains("a line that must reach the file"))
        }
        finally
        {
            System.setOut(original)
        }
    }

    @Test
    fun `pruning keeps the newest logs and deletes the rest`()
    {
        // Two days unattended with a watchdog that may restart the process repeatedly must
        // not fill the disk with logs.
        val dir = tempDir()
        val names = (1L..5L).map { File(dir, "booth-$it.log").apply { writeText("x") } }

        BoothLog.pruneOldLogs(dir, keep = 2)

        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("booth-4.log", "booth-5.log"), left)
        assertTrue(names.first().exists().not())
    }

    @Test
    fun `install never throws when the directory cannot be created`()
    {
        // A read-only or missing profile directory must degrade to "no log file", never to
        // a crash before the window has even opened.
        val blocker = File.createTempFile("notadir", ".txt")
        val impossible = File(blocker, "logs")

        assertEquals(null, BoothLog.install(impossible, startedAtMillis = 1L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "booth.BoothLogTest"`
Expected: FAIL — `Unresolved reference: BoothLog` / `Unresolved reference: TeeOutputStream`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/booth/BoothLog.kt`:

```kotlin
package booth

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * A log file for a cabinet with no console attached.
 *
 * THE ENGINE CANNOT WRITE ONE. `LogTarget` is a Kotlin enum with exactly two entries —
 * STDOUT and CONSOLE (verified: `javap -p no/njoh/pulseengine/core/shared/utils/
 * LogTarget.class`) — so `engine.config.logTarget` has no file option and no third value
 * can be added from outside the jar. The release binary is `PE32 executable (GUI)`, which
 * Windows starts with no attached console, so STDOUT goes nowhere at the booth. Every
 * WARN this project writes to survive the booth log level — `logGamepadDiagnostics`'
 * unmapped-encoder warning above all — was being discarded.
 *
 * WHAT WORKS INSTEAD: `Logger.logToStandardOut` ends in `System.out.println` (verified
 * from bytecode — offset 104 `getstatic java/lang/System.out`, offset 108
 * `invokevirtual java/io/PrintStream.println`). Replacing `System.out` with a tee
 * therefore captures every line the engine and the game log, with no engine change and no
 * dependency on which LogTarget is configured. It also catches anything a library prints
 * or a stack trace dumps, which a custom logger would have missed.
 *
 * Engine-free (`java.io` only) so it is unit-testable and so it can run in `main` BEFORE
 * `PulseEngine.run` — a crash during engine start-up is exactly the one this has to catch.
 */
object BoothLog
{
    /**
     * How many log files to keep. The watchdog (`tools/booth/start-booth.bat`) relaunches
     * the game on every exit, and each launch opens a new file, so an unattended cabinet
     * in a restart loop would otherwise fill the disk over two days.
     */
    const val MAX_RETAINED_LOGS = 10

    private const val PREFIX = "booth-"
    private const val SUFFIX = ".log"

    /**
     * Beside the scoreboard, not in a temp folder: `ConfigurationImpl`'s default
     * `saveDirectory` is `<homeDir>/<gameName>` (verified by disassembling its
     * constructor), so one folder copied to a USB stick at end of day carries the day's
     * scores AND the log that explains anything odd about them.
     */
    fun logDirectory(home: String, gameName: String): File = File(File(home, gameName), "logs")

    /** Newest [keep] files kept, the rest deleted. Sorted by the epoch stamp in the name. */
    fun pruneOldLogs(dir: File, keep: Int = MAX_RETAINED_LOGS)
    {
        val logs = dir.listFiles { f: File -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?: return
        logs.sortedBy { stampOf(it.name) }
            .dropLast(keep.coerceAtLeast(0))
            .forEach { runCatching { it.delete() } }
    }

    private fun stampOf(name: String): Long =
        name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: 0L

    /**
     * Redirects [System.out] through a tee into a fresh log file and returns it, or null
     * if no file could be opened.
     *
     * NEVER THROWS, deliberately. A read-only profile directory, a full disk or a
     * roaming-profile hiccup must cost the booth its log file and nothing else — failing
     * here would kill the cabinet before the window has even opened, which is strictly
     * worse than the problem this class exists to solve.
     */
    fun install(dir: File, startedAtMillis: Long, keep: Int = MAX_RETAINED_LOGS): File? =
        try
        {
            dir.mkdirs()
            val file = File(dir, "$PREFIX$startedAtMillis$SUFFIX")
            val out = FileOutputStream(file, true)
            // autoFlush: the interesting lines are the ones written immediately before a
            // hard kill (the watchdog, a power cut, a technician holding the power button),
            // and a buffered stream loses exactly those.
            System.setOut(PrintStream(TeeOutputStream(System.out, out), true))
            System.setErr(PrintStream(TeeOutputStream(System.err, out), true))
            pruneOldLogs(dir, keep)
            file
        }
        catch (e: Exception)
        {
            // Cannot use Logger here — this runs before the engine exists, and the whole
            // point is that stdout may go nowhere anyway.
            null
        }
}

/**
 * Writes every byte to both streams. [close] closes only [secondary]: [primary] is
 * `System.out`, and the JVM does not hand it back once closed.
 */
class TeeOutputStream(private val primary: OutputStream, private val secondary: OutputStream) : OutputStream()
{
    override fun write(value: Int)
    {
        primary.write(value)
        secondary.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int)
    {
        primary.write(bytes, offset, length)
        secondary.write(bytes, offset, length)
    }

    override fun flush()
    {
        primary.flush()
        secondary.flush()
    }

    override fun close()
    {
        secondary.close()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "booth.BoothLogTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Install it in `main`**

Modify `src/main/kotlin/EnPustTil.kt`. Replace line 44:

```kotlin
fun main() = PulseEngine.run<EnPustTil>()
```

with:

```kotlin
fun main()
{
    // BEFORE PulseEngine.run, deliberately: a failure during engine start-up — a missing
    // asset, a GL context the booth GPU will not give us — happens inside run() and would
    // otherwise be logged to a stdout nobody can read. See BoothLog's class doc for why the
    // engine cannot write this file itself (LogTarget has no FILE entry and cannot get one).
    //
    // GAME_NAME is duplicated from application.cfg rather than read from it: the config is
    // parsed by the engine, inside run(), which is after this point.
    val log = BoothLog.install(
        dir = BoothLog.logDirectory(System.getProperty("user.home") ?: ".", GAME_NAME),
        startedAtMillis = System.currentTimeMillis()
    )
    println(if (log != null) "Booth log: ${log.absolutePath}" else "Booth log: unavailable (continuing without one)")

    PulseEngine.run<EnPustTil>()
}

/** Must match `gameName` in application.cfg — see [main] for why it cannot be read from there. */
const val GAME_NAME = "EnPustTil"
```

Add `import booth.BoothLog` to the import block (alphabetically, before `import dive.DiveInput`).

- [ ] **Step 6: Verify the whole build is green**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 497 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/booth/BoothLog.kt src/test/kotlin/booth/BoothLogTest.kt src/main/kotlin/EnPustTil.kt
git commit -m "feat: the booth writes a log file the engine cannot"
```

---

### Task 2: One exception must not end the day, or open Notepad on the cabinet

**Why:** there is not one `try`, `catch` or `runCatching` in all 1656 lines of `EnPustTil.kt`. Disassembled from the jar, `PulseEngineImpl.runGameLoop` has a single `Exception table: from 21 to 110 target 110 Class java/lang/Throwable` around the whole loop, and its handler does three things: logs `"Fatal error in game loop - shutting down"`, calls `Logger.writeAndOpenCrashReport()`, and calls `shutdown()`. `writeAndOpenCrashReport` delegates to `CrashReportBuilder.buildAndOpen`, which writes a report **and then calls `FileUtilsKt.openFile(absolutePath)`** — on Windows, the OS handler for `.txt`.

So the failure mode is not a black screen. The fullscreen game dies and **Notepad appears on a cabinet configured `screenMode = FULLSCREEN`** precisely so no OS chrome is reachable, with an attendee free to click around in it.

Catching inside our own callbacks means the exception never reaches that handler at all.

**Files:**
- Create: `src/main/kotlin/booth/CallbackGuard.kt`
- Create: `src/test/kotlin/booth/CallbackGuardTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` (`onCreate` ~535, `onFixedUpdate` ~860, `onUpdate` ~914, `onRender` ~1081, `onDestroy`)

**Interfaces:**
- Consumes: nothing from Task 1 (independent), though its reports only reach disk because Task 1 shipped.
- Produces: `booth.CallbackGuard(maxReportsPerSite: Int = MAX_REPORTS_PER_SITE, onFailure: (site: String, count: Int, cause: Throwable) -> Unit)`, with `fun run(site: String, body: () -> Unit)`, `fun failureCount(site: String): Int`, `val totalFailures: Int`, `val lastFailureSite: String?`, and `booth.CallbackGuard.MAX_REPORTS_PER_SITE: Int`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/booth/CallbackGuardTest.kt`:

```kotlin
package booth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackGuardTest
{
    private class Recorder
    {
        val sites = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val causes = mutableListOf<Throwable>()
        fun sink(site: String, count: Int, cause: Throwable)
        {
            sites += site
            counts += count
            causes += cause
        }
    }

    @Test
    fun `a body that throws does not propagate`()
    {
        // This is the whole point: the engine's own handler opens Notepad over a fullscreen
        // cabinet and shuts the game down. Nothing may reach it.
        val guard = CallbackGuard(onFailure = { _, _, _ -> })

        guard.run("onRender") { throw IllegalStateException("boom") }

        assertEquals(1, guard.totalFailures)
    }

    @Test
    fun `a body that succeeds runs and reports nothing`()
    {
        val recorder = Recorder()
        val guard = CallbackGuard(onFailure = recorder::sink)
        var ran = false

        guard.run("onUpdate") { ran = true }

        assertTrue(ran)
        assertEquals(0, guard.totalFailures)
        assertEquals(emptyList(), recorder.sites)
        assertNull(guard.lastFailureSite)
    }

    @Test
    fun `an Error is caught too, not just an Exception`()
    {
        // The engine's own handler catches Throwable. A StackOverflowError out of a deep
        // draw call must not slip past this guard into it.
        val guard = CallbackGuard(onFailure = { _, _, _ -> })

        guard.run("onRender") { throw StackOverflowError() }

        assertEquals(1, guard.totalFailures)
    }

    @Test
    fun `repeated failures at one site are reported at most maxReportsPerSite times`()
    {
        // onRender runs at 120 Hz. A fault that repeats every frame would write two days of
        // identical stack traces into the log file Task 1 just created and bury everything
        // else in it.
        val recorder = Recorder()
        val guard = CallbackGuard(maxReportsPerSite = 3, onFailure = recorder::sink)

        repeat(100) { guard.run("onRender") { throw IllegalStateException("boom") } }

        // 3 reports, plus exactly one "suppressing further reports" notice.
        assertEquals(4, recorder.sites.size)
        assertEquals(listOf(1, 2, 3, 4), recorder.counts)
        assertEquals(100, guard.failureCount("onRender"))
        assertEquals(100, guard.totalFailures)
    }

    @Test
    fun `each site counts and reports independently`()
    {
        val recorder = Recorder()
        val guard = CallbackGuard(maxReportsPerSite = 1, onFailure = recorder::sink)

        guard.run("onRender") { throw IllegalStateException("render") }
        guard.run("onUpdate") { throw IllegalStateException("update") }
        guard.run("onRender") { throw IllegalStateException("render again") }

        assertEquals(2, guard.failureCount("onRender"))
        assertEquals(1, guard.failureCount("onUpdate"))
        assertEquals("onRender", guard.lastFailureSite)
        // onRender: report + suppression notice. onUpdate: report only.
        assertEquals(listOf("onRender", "onUpdate", "onRender"), recorder.sites)
    }

    @Test
    fun `a throwing failure sink cannot itself take the cabinet down`()
    {
        // The sink writes to a log file that may be on a full disk. Belt and braces.
        val guard = CallbackGuard(onFailure = { _, _, _ -> throw IllegalStateException("log disk full") })

        guard.run("onRender") { throw IllegalStateException("boom") }

        assertEquals(1, guard.totalFailures)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "booth.CallbackGuardTest"`
Expected: FAIL — `Unresolved reference: CallbackGuard`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/booth/CallbackGuard.kt`:

```kotlin
package booth

/**
 * Catches everything thrown out of one named call site, so nothing reaches the engine's
 * own handler.
 *
 * WHAT THE ENGINE DOES INSTEAD, and why that is unacceptable at a booth. Disassembled from
 * `pulse-engine-0.13.0.jar`, `PulseEngineImpl.runGameLoop` carries exactly one
 * `Exception table: from 21 to 110 target 110 Class java/lang/Throwable` around the whole
 * loop body, and its handler does three things and returns:
 *
 *     149: ldc_w  "Fatal error in game loop - shutting down"   (LogLevel.ERROR)
 *     171: invokevirtual Logger.writeAndOpenCrashReport:()V
 *     175: invokespecial shutdown:()V
 *
 * `writeAndOpenCrashReport` calls `CrashReportBuilder.buildAndOpen`, which writes
 * `crash-report-<epoch>.txt` and then calls **`FileUtilsKt.openFile(absolutePath)`** — on
 * Windows, the registered handler for `.txt`. So a single uncaught throwable at the booth
 * does not merely end the run: it kills a FULLSCREEN cabinet (application.cfg picked
 * FULLSCREEN specifically so no title bar or close button is reachable) and leaves
 * **Notepad on the display**, in front of a queue, for an attendee to explore.
 *
 * Catching here means the engine's handler never fires. The frame is lost, the cabinet
 * lives. That is the right trade for two unattended days: a diver that stutters once is a
 * far smaller failure than a dead booth, and `tools/booth/start-booth.bat` covers the case
 * where the process dies anyway.
 *
 * Engine-free — the report sink is injected — so it is unit-testable without a GL context,
 * following this project's pure-logic-extracted-for-testing pattern.
 *
 * ALLOCATION: `run` takes a `() -> Unit`, and a capturing lambda allocates per call. The
 * call sites in `EnPustTil` therefore hold their bodies in `val` fields built once in
 * construction rather than writing the lambda inline at 120 Hz — see the fields named
 * `*Body` there.
 */
class CallbackGuard(
    private val maxReportsPerSite: Int = MAX_REPORTS_PER_SITE,
    private val onFailure: (site: String, count: Int, cause: Throwable) -> Unit
)
{
    private val failures = HashMap<String, Int>()

    /** Every failure ever caught, across all sites. Drawn on the booth status line. */
    var totalFailures: Int = 0
        private set

    /** The most recent failing site, for the booth status line. Null until one fails. */
    var lastFailureSite: String? = null
        private set

    fun failureCount(site: String): Int = failures[site] ?: 0

    fun run(site: String, body: () -> Unit)
    {
        try
        {
            body()
        }
        catch (cause: Throwable)
        {
            // Throwable, not Exception: the engine's handler catches Throwable, so anything
            // narrower here would let an Error through to it and open Notepad after all.
            val count = failureCount(site) + 1
            failures[site] = count
            totalFailures++
            lastFailureSite = site

            // Rate-limited: onRender runs at 120 Hz, and a fault that repeats every frame
            // would write two days of identical stack traces into the booth log and bury
            // everything else. One extra call past the cap announces the suppression, so a
            // reader of the log knows the silence is deliberate.
            if (count <= maxReportsPerSite || count == maxReportsPerSite + 1)
            {
                // The sink writes to a file that may be on a full disk. A guard that can be
                // taken down by its own reporting is not a guard.
                try { onFailure(site, count, cause) } catch (_: Throwable) { }
            }
        }
    }

    companion object
    {
        /** Enough to see whether a fault is a one-off or a pattern; few enough to stay readable. */
        const val MAX_REPORTS_PER_SITE = 5
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "booth.CallbackGuardTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Route every engine callback through it**

Modify `src/main/kotlin/EnPustTil.kt`. Add `import booth.CallbackGuard` to the imports.

Inside the `EnPustTil` class, add the guard and the pre-allocated bodies as fields:

```kotlin
/**
 * Nothing thrown by this game may reach the engine's game-loop handler — it opens a text
 * editor over the fullscreen cabinet. See CallbackGuard's class doc for the bytecode.
 */
private val guard = CallbackGuard { site, count, cause ->
    Logger.error(cause) { "[$site] failed (occurrence $count) - the frame is lost, the cabinet continues" }
}

// Held as fields, built once, rather than written inline at the call site: a capturing
// lambda allocates per call, and four of these run every frame. See CallbackGuard's
// ALLOCATION note and CLAUDE.md's no-per-frame-allocation rule.
private val createBody: () -> Unit = { createGame() }
private val fixedUpdateBody: () -> Unit = { fixedUpdateGame() }
private val updateBody: () -> Unit = { updateGame() }
private val renderBody: () -> Unit = { renderGame() }
private val destroyBody: () -> Unit = { destroyGame() }
```

Then rename each existing override's body to a private method and make the override a one-line delegation. For each of the five, apply this shape (shown for `onRender`; do the same for `onCreate`/`createGame`, `onFixedUpdate`/`fixedUpdateGame`, `onUpdate`/`updateGame`, `onDestroy`/`destroyGame`):

```kotlin
override fun onRender() = guard.run(SITE_RENDER, renderBody)

/** The real body. See [guard] for why nothing here may throw past this class. */
private fun renderGame()
{
    // ... the existing onRender body, unchanged ...
}
```

Add the site names to the private companion object, beside `DAILY_SEED`:

```kotlin
// Site names for CallbackGuard. Constants rather than literals so the booth status line
// and the log agree, and so a typo cannot silently create a sixth counter.
const val SITE_CREATE = "onCreate"
const val SITE_FIXED_UPDATE = "onFixedUpdate"
const val SITE_UPDATE = "onUpdate"
const val SITE_RENDER = "onRender"
const val SITE_DESTROY = "onDestroy"
```

**One special case — `onCreate`.** If construction fails the game is unusable, but it still must not open Notepad. After the guarded call, record it so the status line (Task 5) can say so:

```kotlin
override fun onCreate() = guard.run(SITE_CREATE, createBody)

/** True if [createGame] threw. The cabinet stays up and says so rather than dying silently. */
private val bootFailed: Boolean get() = guard.failureCount(SITE_CREATE) > 0
```

- [ ] **Step 6: Add the source-scan test that keeps it true**

This codebase enforces structural rules by scanning source (`MainCameraOwnershipTest`, `DrawTest`, `ShaderOverrideTest`). Add the same for the guard. Append to `src/test/kotlin/booth/CallbackGuardTest.kt`:

```kotlin
    @Test
    fun `every engine callback in EnPustTil is routed through the guard`()
    {
        // A sixth callback added later without a guard reopens exactly the failure this
        // task closed, and no runtime test would catch it — the engine's handler only fires
        // on a real crash at a real booth.
        val source = java.io.File("src/main/kotlin/EnPustTil.kt").readText()
        val overrides = Regex("""override fun (on[A-Za-z]+)\(\)[^\n]*""").findAll(source).toList()

        assertTrue(overrides.size >= 5, "expected at least the five engine callbacks, found ${overrides.size}")
        overrides.forEach { m ->
            assertTrue(
                m.value.contains("guard.run("),
                "${m.groupValues[1]} does not go through CallbackGuard: ${m.value.trim()}"
            )
        }
    }
```

- [ ] **Step 7: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. If the source scan fails, a callback was missed — fix the callback, not the test.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/booth/CallbackGuard.kt src/test/kotlin/booth/CallbackGuardTest.kt src/main/kotlin/EnPustTil.kt
git commit -m "fix: a thrown frame no longer opens Notepad over the cabinet"
```

---

### Task 3: A watchdog that restarts the cabinet

**Why:** Task 2 stops our own code killing the process, but not everything can be caught in a callback — a GL driver fault, an OOM the JVM cannot recover from, an attendee finding the power switch. There is **no watchdog and no restart wrapper anywhere in the repo**: no `.bat`, nothing in `build.gradle.kts`'s launch4j block (lines 96-108). Once the process is gone, the booth is dead until a human notices.

**Files:**
- Create: `tools/booth/start-booth.bat`
- Create: `src/test/kotlin/booth/BoothLauncherTest.kt`
- Modify: `build.gradle.kts` (the `buildWin64Release` Zip task, ~line 108)

**Interfaces:**
- Consumes: nothing in code. NOTE: the watchdog's `echo` lines do **not** reach the booth log — `BoothLog` tees the JVM's `System.out`, and a `.bat` echo goes to the cmd console, which a cabinet has nowhere to show. The script therefore appends its own restart history to `%USERPROFILE%\EnPustTil\logs\watchdog.log`, beside it.
- Produces: `en-pust-til-1.0.zip` gains `start-booth.bat` at its root, beside `en-pust-til.exe`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/booth/BoothLauncherTest.kt`:

```kotlin
package booth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The watchdog cannot be exercised from a JVM test on macOS, so what is asserted here is
 * that it EXISTS, that it loops, and that the release actually ships it. Those are the
 * three ways it has historically gone missing; the behaviour itself is verified on
 * hardware in `docs/booth/windows-verification.md` step 6.
 */
class BoothLauncherTest
{
    private val script = File("tools/booth/start-booth.bat")

    @Test
    fun `the watchdog script exists`()
    {
        assertTrue(script.isFile, "tools/booth/start-booth.bat is missing - the booth has no restart cover")
    }

    @Test
    fun `the watchdog relaunches in a loop rather than starting the game once`()
    {
        val text = script.readText().lowercase()

        assertTrue(text.contains(":relaunch"), "no loop label - this would start the game once and exit")
        assertTrue(text.contains("goto :relaunch"), "the loop never repeats")
        assertTrue(text.contains("en-pust-til.exe"), "the watchdog does not name the game binary")
    }

    @Test
    fun `the watchdog waits between restarts so a boot-crash loop cannot spin`()
    {
        // A game that fails during onCreate would otherwise be relaunched thousands of
        // times a minute, filling the disk with logs and pinning a CPU core.
        assertTrue(script.readText().lowercase().contains("timeout"), "no delay between restarts")
    }

    @Test
    fun `the watchdog records its restart history to a file`()
    {
        // A cabinet has nowhere to show a cmd window, so echoes that only reach the console
        // are echoes nobody reads. This is NOT the booth log - BoothLog tees the JVM's
        // System.out, and these lines are the shell's.
        val text = script.readText().lowercase()

        assertTrue(text.contains("watchdog.log"), "the restart history never reaches disk")
    }

    @Test
    fun `the release zip ships the watchdog beside the exe`()
    {
        val build = File("build.gradle.kts").readText()

        assertTrue(
            build.contains("tools/booth/start-booth.bat"),
            "buildWin64Release does not include the watchdog - the booth would get an exe with no restart cover"
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "booth.BoothLauncherTest"`
Expected: FAIL — 4 failures, starting with "tools/booth/start-booth.bat is missing".

- [ ] **Step 3: Write the watchdog**

Create `tools/booth/start-booth.bat` (CRLF line endings — it is a Windows batch file):

```bat
@echo off
REM ---------------------------------------------------------------------------
REM  En Pust Til - booth watchdog.
REM
REM  START THE CABINET WITH THIS, NOT WITH en-pust-til.exe DIRECTLY.
REM  Put a shortcut to this file in shell:startup so the cabinet comes back on
REM  its own after a power cut.
REM
REM  Why it exists: render/CallbackGuard stops OUR code from ending the process,
REM  but nothing in the JVM catches a GL driver fault, an unrecoverable OOM, or
REM  an attendee finding the power switch. Without this, the booth is dead from
REM  that moment until a human notices - which, in a hall, is a long time.
REM
REM  The delay is the important part: a game that fails during onCreate would
REM  otherwise be relaunched thousands of times a minute, filling the disk with
REM  logs (see booth/BoothLog.kt) and pinning a core.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

REM  Its own file, NOT the booth log: BoothLog tees the JVM's System.out, and these
REM  echoes are the shell's, not the JVM's. A cabinet has nowhere to show a cmd window
REM  anyway, so the restart history has to reach disk to be worth writing.
set "WDLOG=%USERPROFILE%\EnPustTil\logs\watchdog.log"
if not exist "%USERPROFILE%\EnPustTil\logs" mkdir "%USERPROFILE%\EnPustTil\logs"

:relaunch
echo [watchdog] starting en-pust-til.exe at %DATE% %TIME%>>"%WDLOG%"
start /wait "" "en-pust-til.exe"
echo [watchdog] exited with code %ERRORLEVEL% at %DATE% %TIME%>>"%WDLOG%"
echo [watchdog] restarting in 5 seconds - close this window to stop the cabinet
timeout /t 5 /nobreak >nul
goto :relaunch
```

- [ ] **Step 4: Ship it in the release**

Modify `build.gradle.kts`. In the `tasks.register<Zip>("buildWin64Release")` block, after the existing `from(releaseBuildDir)` line, add:

```kotlin
    // The booth is started by this, not by the exe - see the script's own header, and
    // BoothLauncherTest, which fails the build if this line is ever dropped.
    from(file("tools/booth/start-booth.bat"))
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "booth.BoothLauncherTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Verify the zip actually contains it**

Run:
```bash
./gradlew buildWin64Release && unzip -l release/win64/en-pust-til-1.0.zip | grep -E "start-booth|en-pust-til.exe"
```
Expected: both `start-booth.bat` and `en-pust-til.exe` listed at the archive root.

- [ ] **Step 7: Commit**

```bash
git add tools/booth/start-booth.bat src/test/kotlin/booth/BoothLauncherTest.kt build.gradle.kts
git commit -m "feat: a watchdog brings the cabinet back when the process dies"
```

---

### Task 4: A stuck button must not lock the cabinet out of starting a run

**Why:** `render/RunLifecycle.kt:223-224` reads

```kotlin
val pressedEdge = anyInputPressed && !wasInputPressed
wasInputPressed = anyInputPressed
```

and the signal it is handed is a single OR across every source (`EnPustTil.kt:953-956` builds `gamepadActionPressed` per pad, then `anyLifecycleActionPressed` collapses it with the keyboard into one boolean). If **one** encoder button sticks closed — or a spurious HID reports a permanently-pressed button — `anyInputPressed` is true forever, no edge is ever produced again, and **nobody can start a run**. A working second pad cannot rescue it, because its press changes nothing in an OR that is already true. `Key.SPACE` cannot either: `wasClicked` is already an edge but it is OR-ed into the same latched signal. The attract screen looks perfectly healthy the whole time.

Edge-triggering is correct and load-bearing — it is what stops a stuck button *restarting* the game forever, the incident `RunLifecycle`'s class doc was written for. The defect is the *collapse to one signal* before the edge is taken.

The fix is per-source edge detection: each `(gamepad, button)` pair and the keyboard get their own previous-frame state, and the OR is taken over the *edges*, not the levels. A stuck START then contributes no edge while A on the same pad still starts the game.

**Files:**
- Create: `src/main/kotlin/render/LifecycleInputEdges.kt`
- Create: `src/test/kotlin/render/LifecycleInputEdgesTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt:953-956`
- Modify: `src/test/kotlin/render/RunLifecycleTest.kt` (one added test)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `render.LifecycleInputEdges(stuckSeconds: Float = STUCK_SECONDS)` with `fun begin(dt: Float)`, `fun offer(padId: Int, buttonOrdinal: Int, pressed: Boolean)`, `fun offerKeyboardEdge(clicked: Boolean)`, `fun commit(): Boolean`, `val firedPadId: Int?`, `val stuckCount: Int`, `fun isStuck(padId: Int, buttonOrdinal: Int): Boolean`, and `render.LifecycleInputEdges.STUCK_SECONDS: Float`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/render/LifecycleInputEdgesTest.kt`:

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifecycleInputEdgesTest
{
    private val STUCK = 2f

    private fun newEdges() = LifecycleInputEdges(stuckSeconds = STUCK)

    /** One frame in which [padId] offers START at [start] and A at [a]. */
    private fun LifecycleInputEdges.frame(dt: Float, start: Boolean, a: Boolean, padId: Int = 0): Boolean
    {
        begin(dt)
        offer(padId, START, start)
        offer(padId, A, a)
        return commit()
    }

    @Test
    fun `a press produces exactly one edge, not one per frame held`()
    {
        val edges = newEdges()

        assertFalse(edges.frame(dt = 0f, start = false, a = false), "no press, no edge")
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "the press frame fires")
        assertFalse(edges.frame(dt = 0.016f, start = true, a = false), "holding must not re-fire")
        assertFalse(edges.frame(dt = 0.016f, start = false, a = false), "release is not an edge")
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "a fresh press fires again")
    }

    @Test
    fun `a stuck START does not stop A on the same pad from starting a run`()
    {
        // THE BOOTH FAILURE THIS CLASS EXISTS FOR. Before per-source edges, one stuck
        // button made the whole cabinet unstartable and the attract screen looked fine.
        // The cabinet has ONE encoder, so per-pad granularity would not have helped it -
        // START and A on that single device have to fail independently.
        val edges = newEdges()

        assertFalse(edges.frame(dt = 0f, start = false, a = false))
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "the first press is a real edge")
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = false) }
        assertTrue(edges.isStuck(0, START), "held for 3.2s must be declared stuck")

        assertTrue(edges.frame(dt = 0.016f, start = true, a = true), "A must still start the run")
    }

    @Test
    fun `a stuck button on one pad does not stop a different pad from starting a run`()
    {
        val edges = newEdges()

        // Pad 0's START goes down on frame two and never comes up; pad 1 is the cabinet
        // encoder and is idle.
        edges.begin(0f); edges.offer(0, START, false); edges.offer(1, START, false); edges.commit()
        repeat(200)
        {
            edges.begin(0.016f)
            edges.offer(0, START, true)
            edges.offer(1, START, false)
            edges.commit()
        }
        assertTrue(edges.isStuck(0, START))

        edges.begin(0.016f)
        edges.offer(0, START, true)
        edges.offer(1, START, true)
        assertTrue(edges.commit(), "the working pad must still produce an edge")
        assertEquals(1, edges.firedPadId, "and the run must be attributed to the pad that fired it")
    }

    @Test
    fun `releasing a stuck button restores it to a working source`()
    {
        val edges = newEdges()
        edges.frame(dt = 0f, start = false, a = false)
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = false) }
        assertTrue(edges.isStuck(0, START))

        edges.frame(dt = 0.016f, start = false, a = false)
        assertFalse(edges.isStuck(0, START), "releasing clears the stuck flag")
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "a real press after the fault works again")
    }

    @Test
    fun `a pad that appears already holding a button produces no edge`()
    {
        // Hot-plugging a pad with a jammed button must not start a run by itself. The
        // first reading of a source establishes its level; only a CHANGE from it fires.
        val edges = newEdges()

        assertFalse(edges.frame(dt = 0f, start = true, a = false), "first sight of a held button is not a press")
        assertFalse(edges.frame(dt = 0.016f, start = true, a = false))
        edges.frame(dt = 0.016f, start = false, a = false)
        assertTrue(edges.frame(dt = 0.016f, start = true, a = false), "but a genuine press afterwards does")
    }

    @Test
    fun `the keyboard is its own source and a stuck pad cannot mask it`()
    {
        val edges = newEdges()
        edges.begin(0f); edges.offer(0, START, false); edges.offerKeyboardEdge(false); edges.commit()
        repeat(200) { edges.begin(0.016f); edges.offer(0, START, true); edges.offerKeyboardEdge(false); edges.commit() }

        edges.begin(0.016f)
        edges.offer(0, START, true)
        edges.offerKeyboardEdge(true)
        assertTrue(edges.commit(), "SPACE must still start a run when a pad button is jammed")
        assertNull(edges.firedPadId, "a keyboard start is attributed to no pad")
    }

    @Test
    fun `unplugging a pad forgets its state rather than leaving it stuck forever`()
    {
        val edges = newEdges()
        edges.frame(dt = 0f, start = false, a = false)
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = false) }
        assertEquals(1, edges.stuckCount)

        // Pad 0 is gone: it is offered nothing at all this frame.
        edges.begin(0.016f)
        edges.commit()

        assertEquals(0, edges.stuckCount, "an unplugged pad must not keep reporting itself stuck")
    }

    @Test
    fun `stuckCount counts every jammed source`()
    {
        val edges = newEdges()
        edges.frame(dt = 0f, start = false, a = false)
        repeat(200) { edges.frame(dt = 0.016f, start = true, a = true) }

        assertEquals(2, edges.stuckCount)
    }

    private companion object
    {
        // Plain ordinals rather than GamepadButton values, so this test never needs the
        // engine on its classpath - same reasoning as GamepadScan's List<Boolean>.
        const val START = 7
        const val A = 0
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "render.LifecycleInputEdgesTest"`
Expected: FAIL — `Unresolved reference: LifecycleInputEdges`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/render/LifecycleInputEdges.kt`:

```kotlin
package render

/**
 * Per-source edge detection for lifecycle input (start / restart / confirm), plus
 * stuck-source detection.
 *
 * THE BUG THIS FIXES. [RunLifecycle.update] takes ONE boolean and edges it:
 *
 *     val pressedEdge = anyInputPressed && !wasInputPressed
 *
 * which is correct and load-bearing - it is what stops a stuck button from restarting the
 * game forever, the incident RunLifecycle's class doc was written for. The defect was
 * upstream: `anyLifecycleActionPressed` collapsed every source into that one boolean
 * BEFORE the edge was taken. One stuck encoder button then held it true forever, no edge
 * was ever produced again, and the cabinet could not be started by anyone - not by a
 * second gamepad, not by SPACE, because neither can change an OR that is already true.
 * The attract screen looked perfectly healthy throughout, which is what made it a
 * two-day failure rather than a two-minute one.
 *
 * Taking the OR over EDGES instead of over LEVELS fixes it: a jammed START contributes no
 * edge while A on the same pad still starts a run.
 *
 * A SOURCE IS A (padId, buttonOrdinal) PAIR, not a pad. The cabinet has one encoder, so
 * per-pad granularity would not have helped it at all.
 *
 * FIRST SIGHT ESTABLISHES A LEVEL, IT DOES NOT FIRE. A pad hot-plugged with a jammed
 * button would otherwise start a run the instant GLFW enumerated it.
 *
 * ALLOCATION: begin/offer/commit is a push protocol precisely so the caller does not build
 * a collection per frame. The parallel ArrayLists grow only when a genuinely new source
 * appears (at most a handful, once) and are reused every frame after. See CLAUDE.md's
 * no-per-frame-allocation rule.
 *
 * Engine-free - ordinals and ints, never `Gamepad` or `GamepadButton` - so it is unit
 * testable without a GL context, following this project's pure-logic-extracted pattern.
 */
class LifecycleInputEdges(private val stuckSeconds: Float = STUCK_SECONDS)
{
    private val padIds = ArrayList<Int>()
    private val buttonOrdinals = ArrayList<Int>()
    private val wasPressed = ArrayList<Boolean>()
    private val heldSeconds = ArrayList<Float>()
    private val seenThisFrame = ArrayList<Boolean>()

    private var dt = 0f
    private var edge = false

    /** The pad whose button produced this frame's edge, or null for a keyboard start. */
    var firedPadId: Int? = null
        private set

    /** How many sources are currently jammed. Drawn on the booth status line. */
    val stuckCount: Int get() = heldSeconds.count { it >= stuckSeconds }

    fun isStuck(padId: Int, buttonOrdinal: Int): Boolean
    {
        val i = indexOf(padId, buttonOrdinal)
        return i >= 0 && heldSeconds[i] >= stuckSeconds
    }

    fun begin(dt: Float)
    {
        this.dt = dt
        edge = false
        firedPadId = null
        for (i in seenThisFrame.indices) seenThisFrame[i] = false
    }

    fun offer(padId: Int, buttonOrdinal: Int, pressed: Boolean)
    {
        var i = indexOf(padId, buttonOrdinal)
        val firstSight = i < 0
        if (firstSight)
        {
            padIds.add(padId)
            buttonOrdinals.add(buttonOrdinal)
            // Seeded with the level as observed, NOT false: a pad hot-plugged with a jammed
            // button must not read as a press on the frame it appears.
            wasPressed.add(pressed)
            heldSeconds.add(0f)
            seenThisFrame.add(true)
            i = padIds.size - 1
        }

        seenThisFrame[i] = true
        val held = if (pressed) heldSeconds[i] + dt else 0f
        heldSeconds[i] = held

        // A source declared stuck contributes nothing in EITHER direction: no edge of its
        // own, and no block on any other source producing one.
        if (!firstSight && pressed && !wasPressed[i] && held < stuckSeconds)
        {
            edge = true
            if (firedPadId == null) firedPadId = padId
        }
        wasPressed[i] = pressed
    }

    /**
     * The keyboard's own source. [clicked] is already an edge (`engine.input.wasClicked`),
     * so it is passed through rather than re-edged - but it is kept SEPARATE from the pads
     * so a jammed pad button can never mask it, which is what the single-boolean collapse
     * used to do.
     */
    fun offerKeyboardEdge(clicked: Boolean)
    {
        if (clicked) edge = true
    }

    /** True for exactly one frame per genuine press, from any live source. */
    fun commit(): Boolean
    {
        // Forget sources not offered this frame - an unplugged pad must not keep reporting
        // itself stuck on the booth status line for the rest of the day.
        var i = padIds.size - 1
        while (i >= 0)
        {
            if (!seenThisFrame[i])
            {
                padIds.removeAt(i)
                buttonOrdinals.removeAt(i)
                wasPressed.removeAt(i)
                heldSeconds.removeAt(i)
                seenThisFrame.removeAt(i)
            }
            i--
        }
        return edge
    }

    private fun indexOf(padId: Int, buttonOrdinal: Int): Int
    {
        for (i in padIds.indices)
            if (padIds[i] == padId && buttonOrdinals[i] == buttonOrdinal) return i
        return -1
    }

    companion object
    {
        /**
         * An unbroken hold longer than this is hardware, not a player. Well past any real
         * press (an arcade button is down for ~0.1 s) and well short of RunLifecycle's
         * DWELL_SECONDS, so a player leaning on START at the end of a run is never
         * mistaken for a fault.
         */
        const val STUCK_SECONDS = 3f
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "render.LifecycleInputEdgesTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Wire it into `EnPustTil`**

Modify `src/main/kotlin/EnPustTil.kt`. Add the field:

```kotlin
/**
 * Per-source lifecycle edges. See LifecycleInputEdges' class doc for the stuck-button
 * lockout this replaced - the single OR that used to live here could be held true for two
 * days by one jammed encoder button, with no sign of it on screen.
 */
private val lifecycleEdges = LifecycleInputEdges()
```

Replace lines 953-956 (the `gamepadActionPressed` / `actionPressed` block, keeping the long comment above it and adding to it) with:

```kotlin
        // Every (pad, button) pair is its own source now, edged independently - see
        // LifecycleInputEdges. This used to OR the LEVELS together and let RunLifecycle
        // edge the result, which one stuck button could hold true forever.
        lifecycleEdges.begin(engine.data.deltaTime)
        engine.input.gamepads.forEach { pad ->
            lifecycleEdges.offer(pad.id, RESTART_BUTTON.ordinal, pad.isPressed(RESTART_BUTTON))
            lifecycleEdges.offer(pad.id, RESTART_BUTTON_ALT.ordinal, pad.isPressed(RESTART_BUTTON_ALT))
        }
        lifecycleEdges.offerKeyboardEdge(engine.input.wasClicked(Key.SPACE))
        val actionPressed = lifecycleEdges.commit()
```

`RESTART_BUTTON`/`RESTART_BUTTON_ALT` become the config-backed `restartButton`/`restartButtonAlt` fields in Task 7; leave the constants here for now.

Leave `anyLifecycleActionPressed` and `GamepadScanTest` in place — the function is unused by `EnPustTil` from this point, but Task 6 edits the same file and Task 6 step 6 removes it, so deleting it here would churn the diff twice.

- [ ] **Step 6: Assert `RunLifecycle` still fires on a one-frame pulse**

`actionPressed` is now a 1-frame pulse rather than a level, and `RunLifecycle` re-edges whatever it is given. Add to `src/test/kotlin/render/RunLifecycleTest.kt`:

```kotlin
    @Test
    fun `a one-frame input pulse starts a run and does not latch`()
    {
        // EnPustTil now passes an EDGE (LifecycleInputEdges.commit) where it used to pass a
        // level. RunLifecycle re-edges its input, so a 1-frame pulse must still fire - and
        // must not leave justStarted set on the frame after.
        val lc = newLifecycle()

        lc.update(dt = 0.016f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted)

        lc.update(dt = 0.016f, anyInputPressed = false, runOver = false)
        assertFalse(lc.justStarted, "the pulse must not latch")
    }
```

- [ ] **Step 7: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/render/LifecycleInputEdges.kt src/test/kotlin/render/LifecycleInputEdgesTest.kt src/main/kotlin/EnPustTil.kt src/test/kotlin/render/RunLifecycleTest.kt
git commit -m "fix: one stuck button no longer locks the cabinet out of every run"
```

---

### Task 5: A booth status line, so a silent failure stops being silent

**Why:** three real faults are currently invisible to anyone standing at the cabinet.

1. **An unmapped USB encoder.** A generic arcade encoder with no SDL gamepad mapping is completely absent from `engine.input.gamepads` while working fine at the OS level. `readInput`'s `?.` chain (`EnPustTil.kt:1470-1481`) degrades to all-zeros — no crash, no message — so the stick and both buttons do nothing and the game looks perfect. The red `UNMAPPED_JOYSTICK_WARNING` overlay (`EnPustTil.kt:228`, drawn ~1610-1616) is `EPT_DEV`-gated, so a release build never draws it.
2. **A stuck source** (Task 4). Now handled gracefully, which means it announces itself even less than before.
3. **A mistyped `dailySeed`.** `parseDailySeed` (`EnPustTil.kt:56`) is `raw?.toLongOrNull() ?: fallback` by design — a typo must never crash the booth machine — so day two would quietly run day one's column and append to day one's leaderboard.

All three reach the log file now (Task 1), but nobody reads a log with a queue waiting.

**Files:**
- Create: `src/main/kotlin/render/BoothStatus.kt`
- Create: `src/test/kotlin/render/BoothStatusTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` (the attract-screen draw; `logGamepadDiagnostics` ~1536-1569)
- Modify: `src/test/kotlin/render/AttractScreenTest.kt`

**Interfaces:**
- Consumes: `render.LifecycleInputEdges.stuckCount` (Task 4); `booth.CallbackGuard.totalFailures` and `.lastFailureSite` (Task 2).
- Produces: `render.BoothStatus.line(seed: Long, unmappedPads: Int, stuckSources: Int, callbackFailures: Int, lastFailureSite: String?): String`; `EnPustTil.unmappedGamepadCount(): Int`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/render/BoothStatusTest.kt`:

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoothStatusTest
{
    @Test
    fun `a healthy cabinet shows only the active seed`()
    {
        // Day two's whole verification is "did the number on screen change". Anything else
        // on this line while healthy would train an attendant to ignore it.
        val line = BoothStatus.line(seed = 20260903L, unmappedPads = 0, stuckSources = 0, callbackFailures = 0, lastFailureSite = null)

        assertEquals("SEED 20260903", line)
    }

    @Test
    fun `an unmapped encoder is named, because the game otherwise looks perfect`()
    {
        val line = BoothStatus.line(seed = 1L, unmappedPads = 1, stuckSources = 0, callbackFailures = 0, lastFailureSite = null)

        assertTrue(line.contains("1 UNMAPPED"), "got: $line")
    }

    @Test
    fun `stuck sources are counted`()
    {
        val line = BoothStatus.line(seed = 1L, unmappedPads = 0, stuckSources = 2, callbackFailures = 0, lastFailureSite = null)

        assertTrue(line.contains("2 STUCK"), "got: $line")
    }

    @Test
    fun `callback failures name the site that failed`()
    {
        val line = BoothStatus.line(seed = 1L, unmappedPads = 0, stuckSources = 0, callbackFailures = 12, lastFailureSite = "onRender")

        assertTrue(line.contains("12"), "got: $line")
        assertTrue(line.contains("onRender"), "got: $line")
    }

    @Test
    fun `every fault appears at once rather than the first one winning`()
    {
        val line = BoothStatus.line(seed = 7L, unmappedPads = 1, stuckSources = 1, callbackFailures = 3, lastFailureSite = "onUpdate")

        assertTrue(line.contains("SEED 7"), "got: $line")
        assertTrue(line.contains("UNMAPPED"), "got: $line")
        assertTrue(line.contains("STUCK"), "got: $line")
        assertTrue(line.contains("onUpdate"), "got: $line")
    }

    @Test
    fun `the worst-case line is drawable by the default font`()
    {
        // The default font can only draw U+0020..U+011F; anything above renders as NOTHING
        // AT ALL, silently - no glyph and no x-advance. A status line that vanishes is
        // worse than no status line, because it reads as "healthy".
        val line = BoothStatus.line(seed = 20260903L, unmappedPads = 9, stuckSources = 9, callbackFailures = 99, lastFailureSite = "onFixedUpdate")

        line.forEach { c -> assertTrue(c.code in 0x20..0x11F, "undrawable char U+%04X in: %s".format(c.code, line)) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "render.BoothStatusTest"`
Expected: FAIL — `Unresolved reference: BoothStatus`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/render/BoothStatus.kt`:

```kotlin
package render

/**
 * The one line on the attract screen an attendant can check at a glance.
 *
 * It exists because three real faults are otherwise INVISIBLE at the cabinet:
 *
 *  1. An unmapped USB encoder. A generic arcade encoder with no SDL gamepad mapping is
 *     completely absent from `engine.input.gamepads` while working fine at the OS level;
 *     EnPustTil.readInput's `?.` chain degrades to all-zeros, so the stick and both
 *     buttons do nothing and the game looks perfect. The existing red overlay for this is
 *     EPT_DEV gated, so a release build never draws it.
 *  2. A stuck source - see [LifecycleInputEdges]. Now handled gracefully, which means it
 *     announces itself even less than it used to.
 *  3. A mistyped `dailySeed`. `parseDailySeed` falls back to day one's seed deliberately
 *     (a typo must never crash the booth machine), so day two would quietly run day one's
 *     column and append to day one's leaderboard. Showing the ACTIVE seed makes the
 *     day-two switch verifiable without a log: the number either changed or it did not.
 *
 * All three reach the booth log file now (see booth/BoothLog.kt), but nobody reads a log
 * with a queue waiting.
 *
 * A pure String function so [AttractScreenTest] can assert every character it can ever
 * produce is inside the default font's U+0020..U+011F range - see ScreenText.
 */
object BoothStatus
{
    /**
     * A healthy cabinet returns just `SEED <n>`. Every fault appends its own segment, so
     * two simultaneous faults are both visible rather than the first one winning.
     */
    fun line(
        seed: Long,
        unmappedPads: Int,
        stuckSources: Int,
        callbackFailures: Int,
        lastFailureSite: String?
    ): String
    {
        val builder = StringBuilder("SEED ").append(seed)
        if (unmappedPads > 0) builder.append("  ").append(unmappedPads).append(" UNMAPPED PAD")
        if (stuckSources > 0) builder.append("  ").append(stuckSources).append(" STUCK INPUT")
        if (callbackFailures > 0)
        {
            builder.append("  ").append(callbackFailures).append(" FAULT")
            lastFailureSite?.let { builder.append(" IN ").append(it) }
        }
        return builder.toString()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "render.BoothStatusTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Extract the unmapped count and draw the line**

Modify `src/main/kotlin/EnPustTil.kt`. `logGamepadDiagnostics` (~1536-1569) already walks the raw GLFW joystick API to tell "nothing plugged in" from "plugged in but unmapped". Pull the count out of it so the draw site and the log site cannot disagree:

```kotlin
    /**
     * Joysticks GLFW can see that SDL has no gamepad mapping for - the exact state in
     * which the cabinet's encoder is invisible to `engine.input.gamepads` while working
     * fine at the OS level. Extracted out of [logGamepadDiagnostics] rather than
     * duplicated so the number on the booth status line and the number in the log cannot
     * drift apart.
     */
    private fun unmappedGamepadCount(): Int =
        (GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST).count {
            GLFW.glfwJoystickPresent(it) && !GLFW.glfwJoystickIsGamepad(it)
        }
```

and have `logGamepadDiagnostics` call it for the count it reports.

Then, in the attract-screen draw, after the leaderboard:

```kotlin
        // Bottom-left, small and dim: findable by an attendant looking for it, ignorable by
        // a player who is not. NOT EPT_DEV gated, unlike the red unmapped-joystick overlay -
        // a fault nobody at the booth can see is the exact problem this line exists for.
        // surface.config.height rather than engine.window.height: config is what this
        // surface's own projection was built from, so it cannot disagree with what is
        // actually being rendered (CLAUDE.md, platform constraints).
        val statusSize = hudSurface.config.height * 0.014f
        hud.drawText(
            BoothStatus.line(
                seed = dailySeed,
                unmappedPads = unmappedGamepadCount(),
                stuckSources = lifecycleEdges.stuckCount,
                callbackFailures = guard.totalFailures,
                lastFailureSite = guard.lastFailureSite
            ),
            x = statusSize,
            y = hudSurface.config.height - statusSize,
            fontSize = statusSize
        )
```

Match the surrounding call site's actual `hud.drawText` signature and its surface handle name; the names above follow `drawGamepadDiagnostics` at ~1610-1626.

- [ ] **Step 6: Assert the string stays drawable from the attract-screen test too**

Add the worst case to the existing every-drawn-string-is-drawable list in `src/test/kotlin/render/AttractScreenTest.kt`:

```kotlin
        BoothStatus.line(seed = 20260903L, unmappedPads = 9, stuckSources = 9, callbackFailures = 99, lastFailureSite = "onFixedUpdate"),
```

- [ ] **Step 7: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/render/BoothStatus.kt src/test/kotlin/render/BoothStatusTest.kt src/main/kotlin/EnPustTil.kt src/test/kotlin/render/AttractScreenTest.kt
git commit -m "feat: the attract screen says when the cabinet is quietly broken"
```

---

### Task 6: Gameplay follows the pad that started the run

**Why:** lifecycle input scans every gamepad (`anyLifecycleActionPressed`, `render/GamepadScan.kt`) but gameplay reads `engine.input.gamepads.firstOrNull()` (`EnPustTil.kt:1470`). That asymmetry is deliberate and documented — two people fighting over one diver via two pads is worse than one person plugged into the wrong slot — but it produces a specific booth failure: **if any stray HID takes slot 0, START works from the cabinet stick and the diver does not move.** A startable, unplayable run, repeating until a human notices.

`LifecycleInputEdges.firedPadId` (Task 4) already knows which pad started the run. Reading gameplay from that pad keeps the one-diver-one-player guarantee and removes the slot-0 dependency entirely.

**Files:**
- Modify: `src/main/kotlin/render/GamepadScan.kt`
- Modify: `src/test/kotlin/render/GamepadScanTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt:1470` (`readInput`) and the `justStarted` block (~1026)

**Interfaces:**
- Consumes: `render.LifecycleInputEdges.firedPadId` (Task 4).
- Produces: `render.selectGameplayPad(padIds: List<Int>, preferred: Int?): Int?`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/render/GamepadScanTest.kt`:

```kotlin
    @Test
    fun `gameplay follows the pad that started the run, not slot zero`()
    {
        // THE BOOTH FAILURE: a stray HID in slot 0 gave a startable, unplayable run - START
        // worked (lifecycle scans every pad) and the diver did not move (gameplay read
        // firstOrNull). Repeating until someone noticed.
        assertEquals(3, selectGameplayPad(padIds = listOf(0, 3), preferred = 3))
    }

    @Test
    fun `with no run started yet, gameplay falls back to the first pad`()
    {
        // Attract mode, and the frame a keyboard start begins a run: there is no preferred
        // pad, and the old behaviour is the right default.
        assertEquals(0, selectGameplayPad(padIds = listOf(0, 3), preferred = null))
    }

    @Test
    fun `a preferred pad that has been unplugged falls back to the first pad`()
    {
        // Mid-run unplug must not freeze the diver for the rest of the run.
        assertEquals(0, selectGameplayPad(padIds = listOf(0, 3), preferred = 7))
    }

    @Test
    fun `no pads at all selects nothing`()
    {
        // Keyboard-only development, and an unmapped encoder at the booth.
        assertNull(selectGameplayPad(padIds = emptyList(), preferred = 3))
    }
```

Add `import kotlin.test.assertNull` if it is not already imported.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "render.GamepadScanTest"`
Expected: FAIL — `Unresolved reference: selectGameplayPad`.

- [ ] **Step 3: Write the implementation**

Add to `src/main/kotlin/render/GamepadScan.kt`:

```kotlin
/**
 * Which connected gamepad gameplay (movement / kick / bleed) should read this frame:
 * [preferred] if it is still connected, otherwise the first one, otherwise none.
 *
 * [preferred] is the pad whose button actually started the run - see
 * [LifecycleInputEdges.firedPadId], captured by EnPustTil on `RunLifecycle.justStarted`.
 *
 * WHAT THIS REPLACED, and why it was a booth failure. Gameplay used to read
 * `engine.input.gamepads.firstOrNull()` while lifecycle input scanned every pad (see
 * [anyLifecycleActionPressed]). If any stray HID took slot 0 - a second device left
 * plugged in from testing, a presenter remote, anything GLFW happens to map as a gamepad -
 * the cabinet's own START press worked and the diver did not move. A startable, unplayable
 * run, silently repeating for every person in the queue.
 *
 * The asymmetry it was defending is still intact: exactly ONE pad drives the diver, so two
 * people on two pads still cannot fight over one run. It is now the RIGHT one.
 *
 * Falling back to the first pad when [preferred] has vanished is deliberate: a mid-run
 * unplug-and-replug (which changes the id) must not freeze the diver for the rest of the
 * run.
 *
 * Plain `Int` ids rather than `Gamepad` values so this stays pure and unit-testable
 * without booting the engine - same reasoning as [anyLifecycleActionPressed]'s
 * `List<Boolean>`. `Gamepad.id` is an Int (verified: `javap -p
 * no/njoh/pulseengine/core/input/Gamepad.class`).
 */
fun selectGameplayPad(padIds: List<Int>, preferred: Int?): Int? =
    padIds.firstOrNull { it == preferred } ?: padIds.firstOrNull()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "render.GamepadScanTest"`
Expected: PASS.

- [ ] **Step 5: Wire it into `EnPustTil`**

Add the field:

```kotlin
/**
 * The pad whose button started the current run, or null for a keyboard start / attract
 * mode. See [selectGameplayPad] for the slot-0 failure this removed.
 */
private var activePadId: Int? = null
```

In the `if (lifecycle.justStarted)` block (~1026), before `sim = DiveSim(...)`:

```kotlin
            // Captured on the frame the run starts, not read per frame: gameplay must stay
            // with whoever pressed the button even if a second device is plugged in mid-run.
            activePadId = lifecycleEdges.firedPadId
```

And in `readInput` (~1470), replace `val pad = engine.input.gamepads.firstOrNull()` with:

```kotlin
        val pads = engine.input.gamepads
        val chosenId = selectGameplayPad(pads.map { it.id }, activePadId)
        val pad = pads.firstOrNull { it.id == chosenId }
```

`pads.map { it.id }` allocates a list per frame. `readInput` is on the update path, not inside a draw loop, and the list is at most a handful of ints — but if the allocation budget matters, hoist a reusable `IntArray`-backed buffer. Note the choice in a comment either way.

- [ ] **Step 6: Remove the now-unused helper**

`anyLifecycleActionPressed` has had no caller since Task 4. Delete it from `render/GamepadScan.kt` and delete its tests from `GamepadScanTest`, moving the "any button to start must mean any gamepad" reasoning from its doc into `LifecycleInputEdges`' class doc (it is still the rule — it is just enforced per-source now). Leaving a documented helper with no caller invites someone to "restore" the collapse Task 4 removed.

- [ ] **Step 7: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/render/GamepadScan.kt src/test/kotlin/render/GamepadScanTest.kt src/main/kotlin/EnPustTil.kt
git commit -m "fix: the diver follows the pad that started the run, not slot zero"
```

---

### Task 7: The button map moves into the config file

**Why:** `KICK_BUTTON = GamepadButton.A`, `BLEED_BUTTON = GamepadButton.B`, `RESTART_BUTTON = GamepadButton.START`, `RESTART_BUTTON_ALT = GamepadButton.A` and `STICK_DEADZONE = 0.2f` are compile-time constants (`EnPustTil.kt:1634-1654`), with a comment that already admits the problem: *"Remap here if the encoder wiring puts the buttons on different codes."* `dailySeed` got a config key; these did not.

**Windows access is uncertain**, and the encoder has never been enumerated. If its codes differ from expectation on setup day, the current fix is a rebuild — on a machine with the Kotlin toolchain, at the venue. `engine.config.getString/getFloat` already works for custom keys (that is how `dailySeed` is read), and `application.cfg` ships in the `.exe` and is editable with Notepad.

**Files:**
- Modify: `src/main/kotlin/EnPustTil.kt` (`onCreate` ~535, the companion ~1631-1655, `readInput`, `readInitialsCycle`)
- Create: `src/test/kotlin/EnPustTilGamepadConfigTest.kt`
- Modify: `src/main/resources/application.cfg`

**Interfaces:**
- Consumes: nothing.
- Produces: top-level `parseGamepadButton(raw: String?, fallback: no.njoh.pulseengine.core.input.GamepadButton): GamepadButton` and `parseDeadzone(raw: Float?, fallback: Float): Float` in `EnPustTil.kt`, beside `parseDailySeed`; instance fields `kickButton`, `bleedButton`, `restartButton`, `restartButtonAlt`, `stickDeadzone`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/EnPustTilGamepadConfigTest.kt`:

```kotlin
import no.njoh.pulseengine.core.input.GamepadButton
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The booth may have to remap buttons with Notepad on setup day - see application.cfg. A
 * bad value must always degrade to the compiled default rather than crash or, worse, leave
 * a button silently unbound in front of a queue.
 */
class EnPustTilGamepadConfigTest
{
    @Test
    fun `a valid button name is honoured`()
    {
        assertEquals(GamepadButton.X, parseGamepadButton("X", GamepadButton.A))
    }

    @Test
    fun `case and surrounding whitespace do not matter`()
    {
        // The person editing this file is standing at a booth with a screwdriver.
        assertEquals(GamepadButton.START, parseGamepadButton("  start  ", GamepadButton.A))
    }

    @Test
    fun `an absent key falls back to the compiled default`()
    {
        assertEquals(GamepadButton.A, parseGamepadButton(null, GamepadButton.A))
    }

    @Test
    fun `a name that is not a button falls back rather than throwing`()
    {
        // A typo at the booth must never crash the cabinet, and must never leave kick
        // unbound - the same reasoning as parseDailySeed.
        assertEquals(GamepadButton.A, parseGamepadButton("BUTTON_1", GamepadButton.A))
        assertEquals(GamepadButton.B, parseGamepadButton("", GamepadButton.B))
        // TRIANGLE is deliberately NOT the negative case here - it IS a real entry
        // (verified: javap -p no/njoh/pulseengine/core/input/GamepadButton.class lists the
        // PlayStation aliases CROSS/CIRCLE/SQUARE/TRIANGLE alongside A/B/X/Y).
        assertEquals(GamepadButton.TRIANGLE, parseGamepadButton("triangle", GamepadButton.A))
    }

    @Test
    fun `a deadzone is clamped to something usable`()
    {
        // 1.0 or above would make the stick permanently dead; a negative would make a
        // resting stick read as full deflection and the diver would swim on its own.
        assertEquals(0.35f, parseDeadzone(0.35f, 0.2f))
        assertEquals(0.2f, parseDeadzone(null, 0.2f))
        assertEquals(0.9f, parseDeadzone(4f, 0.2f))
        assertEquals(0f, parseDeadzone(-1f, 0.2f))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "EnPustTilGamepadConfigTest"`
Expected: FAIL — `Unresolved reference: parseGamepadButton`.

- [ ] **Step 3: Write the implementation**

Add to `src/main/kotlin/EnPustTil.kt`, beside `parseDailySeed` (~line 56):

```kotlin
/**
 * Parses a `GamepadButton` name read from application.cfg (kickButton / bleedButton /
 * restartButton / restartButtonAlt).
 *
 * WHY THIS IS A CONFIG KEY AT ALL. The booth hardware is a generic USB arcade encoder that
 * has never been enumerated on Windows, and its button codes are a guess. Before this, a
 * wrong guess meant rebuilding the game on a machine with the Kotlin toolchain, at the
 * venue, on setup day. application.cfg ships inside the .exe and opens in Notepad.
 *
 * Falls back to [fallback] for every failure a text-file edit can produce - key absent,
 * empty, misspelled, a button name from a different controller vocabulary - for exactly
 * the reason [parseDailySeed] does: a typo must never crash the booth machine, and must
 * never leave kick unbound in front of a queue. Pure and engine-free apart from the enum
 * itself, so it is unit testable without standing up a PulseEngine.
 */
fun parseGamepadButton(raw: String?, fallback: GamepadButton): GamepadButton
{
    val name = raw?.trim()?.uppercase() ?: return fallback
    return GamepadButton.entries.firstOrNull { it.name == name } ?: fallback
}

/**
 * Parses the `stickDeadzone` override. Clamped to 0..0.9: 1.0 or above makes the stick
 * permanently dead (the diver could never be steered), and a negative value makes a
 * resting stick read as full deflection (the diver swims on its own, forever, on the
 * attract screen). Both are worse than any legitimate value.
 */
fun parseDeadzone(raw: Float?, fallback: Float): Float = (raw ?: fallback).coerceIn(0f, 0.9f)
```

In the companion object, keep the existing constants but rename them to make their new role explicit:

```kotlin
        // COMPILED DEFAULTS ONLY. The values actually used are the fields on the class,
        // resolved from application.cfg in onCreate - see parseGamepadButton for why the
        // booth needs to remap these without a compiler. Keep these as the best guess at
        // the cabinet's encoder so a config with no keys behaves exactly as before.
        val DEFAULT_KICK_BUTTON = GamepadButton.A
        val DEFAULT_BLEED_BUTTON = GamepadButton.B
        val DEFAULT_RESTART_BUTTON = GamepadButton.START
        val DEFAULT_RESTART_BUTTON_ALT = GamepadButton.A
        const val DEFAULT_STICK_DEADZONE = 0.2f
```

Add the resolved fields to the class:

```kotlin
    private var kickButton = DEFAULT_KICK_BUTTON
    private var bleedButton = DEFAULT_BLEED_BUTTON
    private var restartButton = DEFAULT_RESTART_BUTTON
    private var restartButtonAlt = DEFAULT_RESTART_BUTTON_ALT
    private var stickDeadzone = DEFAULT_STICK_DEADZONE
```

And resolve them in `createGame` (the guarded `onCreate` body from Task 2), immediately after the `dailySeed` line:

```kotlin
        // Beside dailySeed, and for the same reason: application.cfg is the only thing a
        // technician can edit at the booth without a toolchain.
        kickButton = parseGamepadButton(engine.config.getString("kickButton"), DEFAULT_KICK_BUTTON)
        bleedButton = parseGamepadButton(engine.config.getString("bleedButton"), DEFAULT_BLEED_BUTTON)
        restartButton = parseGamepadButton(engine.config.getString("restartButton"), DEFAULT_RESTART_BUTTON)
        restartButtonAlt = parseGamepadButton(engine.config.getString("restartButtonAlt"), DEFAULT_RESTART_BUTTON_ALT)
        stickDeadzone = parseDeadzone(engine.config.getFloat("stickDeadzone"), DEFAULT_STICK_DEADZONE)
        Logger.info { "Buttons: kick=$kickButton bleed=$bleedButton restart=$restartButton/$restartButtonAlt deadzone=$stickDeadzone" }
```

Then replace every use of the old constants with the fields: `readInput` (`KICK_BUTTON`, `BLEED_BUTTON`), `readInitialsCycle` and `deadzone()` (`STICK_DEADZONE`), and the `lifecycleEdges.offer` block from Task 4 step 5 (`RESTART_BUTTON`, `RESTART_BUTTON_ALT`). Grep to confirm none are left: `grep -n "KICK_BUTTON\|BLEED_BUTTON\|RESTART_BUTTON\|STICK_DEADZONE" src/main/kotlin/EnPustTil.kt` should show only the `DEFAULT_*` declarations and their five uses in `createGame`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "EnPustTilGamepadConfigTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Document the keys in `application.cfg`**

Append to `src/main/resources/application.cfg`:

```
# BUTTON MAP. The booth encoder is a generic USB arcade board and its button codes are a
# guess until one is enumerated on Windows. If kick, bleed or start land on the wrong
# physical button on setup day, change them here and restart the .exe - no rebuild, no
# toolchain. Valid names are GamepadButton's entries, verified against the jar: A, B, X, Y,
# LEFT_BUMPER, RIGHT_BUMPER, BACK, START, GUIDE, LEFT_THUMB, RIGHT_THUMB, DPAD_UP,
# DPAD_RIGHT, DPAD_DOWN, DPAD_LEFT, and the PlayStation aliases CROSS, CIRCLE, SQUARE,
# TRIANGLE. An unknown
# or misspelled name falls back to the compiled default rather than leaving the button
# unbound - see parseGamepadButton. Run with EPT_DEV=1 to see which codes the encoder
# actually reports (the gamepad diagnostic overlay), or read the booth log written at
# start-up (see booth/BoothLog.kt).
# kickButton = A
# bleedButton = B
# restartButton = START
# restartButtonAlt = A
# stickDeadzone = 0.2
```

- [ ] **Step 6: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/EnPustTil.kt src/test/kotlin/EnPustTilGamepadConfigTest.kt src/main/resources/application.cfg
git commit -m "feat: the button map is editable at the booth without a compiler"
```

---

### Task 8: `ScoreRepository` gets a seam, tests, and three fixes

**Why:** `grep -rln "ScoreRepository\|registerScore\|maybeRollBackup" src/test/` returns **nothing**. `AtomicFileSwapTest`, `LeaderboardTest` and `InitialsEntryTest` cover the pure helpers around it; the class that owns two days of booth scores — `registerScore`, `saveAsync`, `loadEntries`'s corrupt-file path, `maybeRollBackup` — is asserted only in comments. It is the one class in the project whose stated requirement is "never lose a score".

It has no tests because it takes a `PulseEngine` and reaches through `engine.data`. A narrow seam fixes that and fixes three real defects at the same time:

1. **A failed atomic promotion is silent.** `score/AtomicFileSwap.kt:56-59` ends `catch (e: Exception) { false }` — no log, no rethrow — and `saveAsync`'s completion callback (`ScoreRepository.kt:86-88`) discards the return value. On Windows, `Files.move(…, REPLACE_EXISTING)` can fail with `AccessDeniedException` when an indexer or AV holds the target open. The score is then lost with no trace anywhere.
2. **Concurrent saves share one temp filename.** Both `saveAsync` and `onDestroy` write to the constant `TEMP_FILE = "scoreboard.json.tmp"` (`ScoreRepository.kt:200`), and `engine.data.saveObjectAsync` runs on `Dispatchers.IO` — a multi-threaded pool. Two overlapping writes can have writer A's promotion rename a temp file writer B is midway through rewriting, producing exactly the torn `scoreboard.json` that `AtomicFileSwap` exists to prevent. Low probability at the booth (runs are ≥90 s apart); the mechanism is real and the class doc claims the guarantee unconditionally.
3. **Stale temp files are never swept.** A crash between write and promote leaves one behind forever.

**Files:**
- Create: `src/main/kotlin/score/ScoreStore.kt`
- Create: `src/test/kotlin/score/ScoreRepositoryTest.kt`
- Modify: `src/main/kotlin/score/ScoreRepository.kt`
- Modify: `src/main/kotlin/score/AtomicFileSwap.kt`
- Modify: `src/test/kotlin/score/AtomicFileSwapTest.kt`

**Interfaces:**
- Consumes: `booth.BoothLog` (Task 1) is what makes the new failure logs readable at the booth.
- Produces: `score.ScoreStore` with `fun exists(name: String): Boolean`, `fun load(name: String): ScoreboardData?`, `fun saveAsync(data: ScoreboardData, name: String, onWritten: () -> Unit)`, `fun saveSync(data: ScoreboardData, name: String)`, `fun fileFor(name: String): java.io.File`, `fun listNames(): List<String>`; `score.EngineScoreStore(engine: PulseEngine) : ScoreStore`; `ScoreRepository.storeFor` seam. `promoteAtomically` gains `onFailure: (Exception) -> Unit = {}`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/score/ScoreRepositoryTest.kt`:

```kotlin
package score

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first tests this class has ever had. It owns two days of booth scores and its stated
 * requirement is "never lose a score" - which was asserted only in comments until now.
 *
 * The seam is [ScoreStore]: the real one wraps `engine.data`, this one is a real temp
 * directory with no engine at all, so every write path is exercised for real (the atomic
 * promote included) without a GL context.
 */
class ScoreRepositoryTest
{
    // jacksonObjectMapper is already on the classpath via the engine, and DataImpl uses the
    // same library - so the fake exercises real serialisation rather than mocking it away.
    private class FakeStore(val dir: File) : ScoreStore
    {
        val writes = mutableListOf<String>()

        override fun exists(name: String) = File(dir, name).exists()
        override fun fileFor(name: String) = File(dir, name)
        override fun listNames(): List<String> = dir.listFiles()?.map { it.name } ?: emptyList()

        // jacksonObjectMapper is already on the classpath via the engine, and DataImpl uses
        // the same library - so the fake exercises real serialisation rather than mocking it away.
        private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

        override fun load(name: String): ScoreboardData?
        {
            val f = File(dir, name)
            if (!f.exists()) return null
            // null-on-failure, matching engine.data.loadObject's verified behaviour.
            return runCatching { mapper.readValue(f.readText(), ScoreboardData::class.java) }.getOrNull()
        }

        override fun saveSync(data: ScoreboardData, name: String)
        {
            writes += name
            File(dir, name).writeText(mapper.writeValueAsString(data))
        }

        override fun saveAsync(data: ScoreboardData, name: String, onWritten: () -> Unit)
        {
            // Synchronous here on purpose: the ORDER of write-then-promote is what is under
            // test, not the threading. The threading defect (a shared temp filename across
            // two IO-pool writers) is covered by `two overlapping saves ...` below.
            saveSync(data, name)
            onWritten()
        }
    }

    /**
     * A store whose live file cannot be replaced, so promoteAtomically genuinely throws.
     * A non-empty DIRECTORY where scoreboard.json should be: Files.move refuses to replace
     * it on both APFS and NTFS. This is the closest reachable stand-in for the booth's real
     * failure - an indexer or AV on Windows holding scoreboard.json open.
     *
     * NOT an `inner` class, deliberately: Kotlin cannot reference an inner class constructor
     * in a supertype delegation expression, so `ScoreStore by FakeStore(dir)` only compiles
     * with both classes top-level-private in this file.
     */
    private class UnpromotableStore(dir: File) : ScoreStore by FakeStore(dir)
    {
        private val blocked = File(dir, ScoreRepository.LIVE_FILE).apply {
            mkdirs()
            File(this, "child").writeText("x")
        }

        override fun fileFor(name: String) =
            if (name == ScoreRepository.LIVE_FILE) blocked else File(blocked.parentFile, name)
    }

    private fun tempDir(): File = File.createTempFile("scorerepo", "").let { it.delete(); it.mkdirs(); it }

    private fun repo(store: ScoreStore, seed: Long = 1L) = ScoreRepository(todaySeed = seed, store = store)

    @Test
    fun `a registered score reaches the live file, not just the temp file`()
    {
        val store = FakeStore(tempDir())
        val r = repo(store)

        r.registerScore(initials = "LTO", score = 500)

        assertTrue(store.exists(ScoreRepository.LIVE_FILE), "scoreboard.json was never promoted")
        assertEquals(1, store.load(ScoreRepository.LIVE_FILE)!!.entries.size)
    }

    @Test
    fun `no temp file is left behind after a successful save`()
    {
        val store = FakeStore(tempDir())

        repo(store).registerScore(initials = "LTO", score = 500)

        assertTrue(store.listNames().none { it.endsWith(".tmp") }, "left: ${store.listNames()}")
    }

    @Test
    fun `two overlapping saves do not share a temp filename`()
    {
        // THE RACE: saveObjectAsync runs on Dispatchers.IO, a MULTI-threaded pool, and both
        // save paths used the one constant "scoreboard.json.tmp". Writer A's promote could
        // rename a file writer B was midway through rewriting - exactly the torn
        // scoreboard.json promoteAtomically exists to prevent.
        val store = FakeStore(tempDir())
        val r = repo(store)

        r.registerScore(initials = "AAA", score = 100)
        r.registerScore(initials = "BBB", score = 200)

        val temps = store.writes.filter { it.endsWith(".tmp") }
        assertEquals(temps.size, temps.toSet().size, "temp filenames repeated: $temps")
    }

    @Test
    fun `a failed promotion is reported rather than swallowed`()
    {
        // On Windows an indexer or AV holding scoreboard.json open makes Files.move throw
        // AccessDeniedException. That used to lose the score with no trace anywhere - and
        // with no log at the booth at all, before booth/BoothLog.kt.
        val reported = mutableListOf<String>()
        val r = ScoreRepository(todaySeed = 1L, store = UnpromotableStore(tempDir()), onSaveFailure = { reported += it })

        r.registerScore(initials = "LTO", score = 500)

        assertTrue(reported.isNotEmpty(), "a lost score must be reported")
    }

    @Test
    fun `a corrupt scoreboard starts a fresh board instead of crashing a run`()
    {
        val store = FakeStore(tempDir())
        store.fileFor(ScoreRepository.LIVE_FILE).writeText("{ this is not json")

        val r = repo(store)
        r.onCreateForTest()

        assertEquals(emptyList(), r.topN(10))
    }

    @Test
    fun `a stale temp file from a crash is swept on load`()
    {
        // A crash between "temp written" and "promote called" leaves one behind forever.
        val store = FakeStore(tempDir())
        store.fileFor("scoreboard.deadbeef.tmp").writeText("{}")

        repo(store).onCreateForTest()

        assertTrue(store.listNames().none { it.endsWith(".tmp") }, "left: ${store.listNames()}")
    }

    @Test
    fun `a score not worth recording never touches disk`()
    {
        val store = FakeStore(tempDir())

        repo(store).registerScore(initials = "LTO", score = 0)

        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `the shutdown save promotes the tail of scores registered since the last one`()
    {
        val store = FakeStore(tempDir())
        val r = repo(store)
        r.registerScore(initials = "AAA", score = 100)
        r.registerScore(initials = "BBB", score = 200)

        r.onDestroyForTest()

        assertEquals(2, store.load(ScoreRepository.LIVE_FILE)!!.entries.size)
    }

    @Test
    fun `yesterday's scores stay on disk but off today's board`()
    {
        // The day-two seed switch (application.cfg) depends on exactly this.
        val store = FakeStore(tempDir())
        val r = repo(store, seed = 20260903L)
        r.registerScore(initials = "OLD", score = 900, seed = 20260902L)
        r.registerScore(initials = "NEW", score = 100, seed = 20260903L)

        assertEquals(listOf("NEW"), r.topN(10).map { it.initials })
        assertEquals(2, store.load(ScoreRepository.LIVE_FILE)!!.entries.size)
    }
}
```

The helpers `serialise` / `jacksonRoundTrip` in `FakeStore` should use the same Jackson mapper the engine's `DataImpl` uses, so the fake exercises real serialisation. If pulling that in is awkward, implement them with `com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()` — it is already on the classpath via the engine — and say so in a comment.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "score.ScoreRepositoryTest"`
Expected: FAIL — `Unresolved reference: ScoreStore`, and `ScoreRepository` has no `store` parameter.

- [ ] **Step 3: Write the seam**

Create `src/main/kotlin/score/ScoreStore.kt`:

```kotlin
package score

import no.njoh.pulseengine.core.PulseEngine
import java.io.File

/**
 * Everything [ScoreRepository] needs from the outside world, and nothing else.
 *
 * It exists so the class that owns two days of booth scores can finally be tested. Before
 * this seam, every write path reached through `engine.data` and therefore needed a booted
 * PulseEngine, so `registerScore`, the promote, the corrupt-file path and the backup roll
 * were asserted only in comments - in the ONE class whose stated requirement is "never
 * lose a score".
 *
 * Deliberately narrow. This is not an abstraction over storage; it is the smallest set of
 * calls that lets a test put a real temp directory where `engine.data` normally sits, so
 * the atomic promote is exercised for real rather than mocked away.
 */
interface ScoreStore
{
    fun exists(name: String): Boolean
    fun load(name: String): ScoreboardData?
    fun saveAsync(data: ScoreboardData, name: String, onWritten: () -> Unit)
    fun saveSync(data: ScoreboardData, name: String)
    fun fileFor(name: String): File
    fun listNames(): List<String>
}

/**
 * The real one. `saveObjectAsync` runs the write on `Dispatchers.IO` (verified by
 * decompiling `DataImpl`), so it never blocks the render thread, and [onWritten] runs on
 * that background thread rather than the game thread.
 */
class EngineScoreStore(private val engine: PulseEngine) : ScoreStore
{
    override fun exists(name: String) = engine.data.exists(name)

    override fun load(name: String): ScoreboardData? = engine.data.loadObject<ScoreboardData>(name)

    override fun saveAsync(data: ScoreboardData, name: String, onWritten: () -> Unit)
    {
        engine.data.saveObjectAsync(data, name) { onWritten() }
    }

    override fun saveSync(data: ScoreboardData, name: String)
    {
        engine.data.saveObject(data, name)
    }

    override fun fileFor(name: String) = File(engine.config.saveDirectory, name)

    override fun listNames(): List<String> =
        File(engine.config.saveDirectory).listFiles()?.map { it.name } ?: emptyList()
}
```

- [ ] **Step 4: Rework `ScoreRepository` onto the seam and fix the three defects**

Modify `src/main/kotlin/score/ScoreRepository.kt`:

```kotlin
class ScoreRepository(
    private val todaySeed: Long,
    private var store: ScoreStore? = null,
    private val onSaveFailure: (String) -> Unit = { Logger.error { it } }
) : Service()
{
    // onCreate builds the real store from the engine when one was not injected. Injection
    // is what ScoreRepositoryTest uses; the booth always takes this branch.
    override fun onCreate(engine: PulseEngine)
    {
        if (store == null) store = EngineScoreStore(engine)
        ...
    }
```

Then, the three fixes:

**(a) Unique temp filenames.** Replace the `TEMP_FILE` constant's use at both save sites with a fresh name per write:

```kotlin
    /**
     * A FRESH temp name per write, not a shared constant.
     *
     * `saveObjectAsync` runs on `Dispatchers.IO`, a MULTI-threaded pool, and both save
     * paths used to write to the single constant "scoreboard.json.tmp". Two overlapping
     * writes could then have writer A's promote rename a file writer B was midway through
     * rewriting - producing exactly the torn scoreboard.json that promoteAtomically exists
     * to prevent. Runs are ~90 s apart at a booth so this is unlikely; it is also free to
     * remove, and the class doc claims the guarantee unconditionally.
     */
    private fun freshTempName(): String = "scoreboard.${java.util.UUID.randomUUID()}.tmp"
```

**(b) Report a failed promotion:**

```kotlin
    private fun saveAsync()
    {
        val s = store ?: return
        val snapshot = ScoreboardData(entries.toList())
        val temp = freshTempName()
        s.saveAsync(snapshot, temp) {
            val promoted = promoteAtomically(
                temp = s.fileFor(temp),
                live = s.fileFor(LIVE_FILE),
                onFailure = { e -> onSaveFailure("Could not promote $temp to $LIVE_FILE: ${e}") }
            )
            // A lost score is a real failure at a booth whose prizes are drawn from this
            // board. It used to be discarded here with no log at all - and until
            // booth/BoothLog.kt, a log would have gone nowhere anyway.
            if (!promoted) onSaveFailure("SCORE NOT SAVED - $LIVE_FILE was not updated from $temp")
        }
    }
```

**(c) Sweep stale temps on load**, at the end of the load path:

```kotlin
    /**
     * A crash between "temp written" and "promote called" leaves a temp file behind
     * forever. Harmless individually; over two days of watchdog restarts they accumulate
     * in the folder a technician copies to a USB stick at end of day.
     */
    private fun sweepStaleTemps(s: ScoreStore)
    {
        s.listNames()
            .filter { it.startsWith("scoreboard.") && it.endsWith(".tmp") }
            .forEach { runCatching { s.fileFor(it).delete() } }
    }
```

Make `LIVE_FILE` internal-visible for the test (it is referenced as `ScoreRepository.LIVE_FILE`), and add the two `*ForTest` hooks in the style `DiveSim` already uses for `debug*`:

```kotlin
    /** See DiveSim's `debug*` hooks - the same pattern, for the same reason. */
    internal fun onCreateForTest() { store?.let { loadInto(it) } }
    internal fun onDestroyForTest() { store?.let { finalSave(it) } }
```

Finally, `registerScore` no longer needs a `PulseEngine` parameter — it uses `store`. Update its one call site in `EnPustTil.kt` (the `initialsJustCompleted` block).

- [ ] **Step 5: Give `promoteAtomically` a failure channel**

Modify `src/main/kotlin/score/AtomicFileSwap.kt`:

```kotlin
fun promoteAtomically(temp: File, live: File, onFailure: (Exception) -> Unit = {}): Boolean
{
    if (!temp.exists()) return false
    return try
    {
        Files.move(temp.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    }
    catch (e: AtomicMoveNotSupportedException)
    {
        Files.move(temp.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }
    catch (e: Exception)
    {
        // This used to be a bare `false`. On Windows, Files.move can fail with
        // AccessDeniedException when an indexer or AV holds the target open, and the
        // caller discarded the return value - so a booth score vanished with no trace in
        // any log, on a board the day's prizes are drawn from.
        onFailure(e)
        false
    }
}
```

Add to `src/test/kotlin/score/AtomicFileSwapTest.kt`:

```kotlin
    @Test
    fun `a promotion that cannot happen reports why`()
    {
        val reported = mutableListOf<Exception>()
        val temp = File.createTempFile("swap", ".tmp").apply { writeText("payload") }
        // A directory where the live file should be: Files.move cannot replace it.
        val live = File.createTempFile("swaplive", "").apply { delete(); mkdirs(); File(this, "child").writeText("x") }

        val ok = promoteAtomically(temp, live, onFailure = { reported += it })

        assertFalse(ok)
        assertTrue(reported.isNotEmpty(), "the caller must be told the score was not saved")
    }
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests "score.*"`
Expected: PASS. If `a promotion that cannot happen reports why` passes vacuously on macOS (a non-empty directory does reliably refuse replacement on both APFS and NTFS, but confirm), swap the trigger for one that genuinely throws on this filesystem and note which in a comment.

- [ ] **Step 7: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/score/ src/test/kotlin/score/ src/main/kotlin/EnPustTil.kt
git commit -m "fix: a lost score is no longer silent, and the repository finally has tests"
```

---

### Task 9: Attract mode stops showing the last player's dead diver

**Why:** `sim` is reconstructed **only** on `lifecycle.justStarted` (`EnPustTil.kt:1026-1028`). Nothing rebuilds it on the return to IDLE. `camera.update(...)` runs unconditionally, outside the `simulationAdvances` gate — its own comment at `EnPustTil.kt:906-910` says so — and `simulationAdvances` is false in IDLE (`RunLifecycle.kt:150`), so `DiveSim.tick` and `DiverSprite.advanceLoop` are both skipped.

Net effect: after the first run of the day, the attract screen is the previous run's final frame — a motionless diver wherever the clock caught him, camera eased there. **If someone times out at 120 m, the queue-forming display is a near-black abyss with a leaderboard floating in it**, until the next person presses start. `AttractLayout` (`EnPustTil.kt:268-295`) was designed and measured against a surface shot.

**Files:**
- Modify: `src/main/kotlin/render/RunLifecycle.kt`
- Modify: `src/test/kotlin/render/RunLifecycleTest.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` (the `justStarted` block ~1026, and the sprite advance in the fixed update ~876-884)

**Interfaces:**
- Consumes: nothing.
- Produces: `RunLifecycle.justReturnedToIdle: Boolean` (one-tick, same shape as `justStarted`).

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/render/RunLifecycleTest.kt`:

```kotlin
    @Test
    fun `returning to attract mode is announced exactly once`()
    {
        // EnPustTil rebuilds DiveSim on this flag. Without it the attract screen keeps the
        // last player's final frame - a motionless diver at whatever depth the clock caught
        // him, which after a 120 m timeout is a near-black abyss with a leaderboard in it.
        val lc = newLifecycle()
        enterRunOver(lc)

        // Not worth recording, so RUN_OVER times out straight back to IDLE.
        lc.update(dt = IDLE_TIMEOUT + 0.1f, anyInputPressed = false, runOver = true)

        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertTrue(lc.justReturnedToIdle, "the return to attract must be announced")

        lc.update(dt = 0.016f, anyInputPressed = false, runOver = false)
        assertFalse(lc.justReturnedToIdle, "and must be a one-tick event, not a latched mode")
    }

    @Test
    fun `finishing initials also announces the return to attract mode`()
    {
        // The other way back to IDLE. Both must rebuild the sim, or a player who entered
        // initials leaves their corpse on the attract screen for the next person in the queue.
        val lc = newLifecycle()
        enterRunOver(lc)
        lc.update(dt = DWELL + 0.1f, anyInputPressed = false, runOver = true, bankedScore = 5000)
        assertEquals(RunLifecycleState.ENTER_INITIALS, lc.state)

        lc.update(dt = INITIALS_IDLE_TIMEOUT + 0.1f, anyInputPressed = false, runOver = true, bankedScore = 5000)

        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertTrue(lc.justReturnedToIdle)
    }

    @Test
    fun `resuming a run paused from attract mode does not announce a return`()
    {
        // PAUSED -> IDLE is a resume, not a fresh attract screen. Rebuilding the sim there
        // would be harmless but the flag must mean one thing only.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.PAUSED, lc.state)

        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)

        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.justReturnedToIdle, "a resume is not a return to attract mode")
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "render.RunLifecycleTest"`
Expected: FAIL — `Unresolved reference: justReturnedToIdle`.

- [ ] **Step 3: Add the flag**

Modify `src/main/kotlin/render/RunLifecycle.kt`. Beside `justStarted`:

```kotlin
    /**
     * True for exactly one tick on each arrival at [RunLifecycleState.IDLE] from a finished
     * run - the RUN_OVER timeout and the end of initials entry alike, but NOT a resume out
     * of PAUSED, which returns to an attract screen that was never left.
     *
     * EnPustTil rebuilds [dive.DiveSim] on this. Without it the attract screen kept the
     * previous run's final frame: `sim` was reconstructed only on [justStarted], and
     * `simulationAdvances` is false in IDLE, so the diver simply stopped wherever the clock
     * caught him. After a 120 m timeout that left the queue-forming display a near-black
     * abyss with a leaderboard floating in it, until the next person pressed start.
     *
     * A one-tick event rather than a latched flag, for the same reason [justStarted] is.
     */
    var justReturnedToIdle: Boolean = false
        private set
```

Clear it beside the other one-tick resets at the top of `update`:

```kotlin
        justStarted = false
        justReturnedToIdle = false
        initialsJustCompleted = false
```

And set it in `enter`, which is the single funnel every transition goes through:

```kotlin
    private fun enter(next: RunLifecycleState, started: Boolean = false)
    {
        // Set here rather than at the three call sites so a fourth route back to IDLE added
        // later cannot forget it. The PAUSED resume goes through enter(resumeState) too, so
        // it is excluded explicitly: that returns to an attract screen that was never left.
        justReturnedToIdle = next == RunLifecycleState.IDLE && state != RunLifecycleState.PAUSED
        ...
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "render.RunLifecycleTest"`
Expected: PASS.

- [ ] **Step 5: Rebuild the sim and keep the attract diver moving**

Modify `src/main/kotlin/EnPustTil.kt`. Beside the existing `if (lifecycle.justStarted)` block:

```kotlin
        if (lifecycle.justReturnedToIdle)
        {
            // A fresh diver at the surface, not the last player's corpse at 120 m. Same
            // construction and camera-snap order as justStarted - see that block's comment
            // for why the pin is applied BEFORE the snap, and why this snaps rather than
            // eases (a teleport read as a full-frame jump otherwise).
            sim = DiveSim(seed = dailySeed)
            applyDepthPin()
            camera.snapTo(sim.depth)
            CameraRig.snap(engine, camera.depth)
            DiveLighting.resetAim()
            DiverSprite.restartLoop()
            activePadId = null   // Task 6: the next run picks its own pad
        }
```

Then let the diver animate on the attract screen. In the fixed update, the sprite advance is currently inside the `simulationAdvances` gate (~876-884). Move the sprite's own loop out of it:

```kotlin
        // The sprite loop advances in IDLE too, but DiveSim does NOT tick: an attract-mode
        // diver that ran the simulation would burn air and "drown" on the attract screen.
        // What is wanted is a diver kicking in place at the surface, which is the animation
        // without the simulation.
        if (lifecycle.simulationAdvances || lifecycle.state == RunLifecycleState.IDLE)
            diverSprite.advanceLoop(engine.data.fixedDeltaTime)
```

- [ ] **Step 6: LOOK AT IT — tests cannot see this one**

This is a rendering change and green tests are not evidence it is right. Grab the real window (`CLAUDE.md`, "Seeing it actually run"):

```bash
caffeinate -d -u -t 900 &
./gradlew run > /dev/null 2>&1 &
until pgrep -f EnPustTilKt > /dev/null; do sleep 2; done ; sleep 12
osascript -e 'tell application "System Events" to set frontmost of (first application process whose name is "java") to true'
sleep 2 ; screencapture -x -o /tmp/attract-before-run.png
# press SPACE, let a run start, then let it time out back to attract, and capture again
sleep 2 ; screencapture -x -o /tmp/attract-after-run.png
pkill -9 -f EnPustTilKt
```

Expected: the two captures show the same surface-level attract screen with a moving diver. Do **not** use `EPT_SCREENSHOT` for this — `ScreenshotEffect.getTexture()` returns `RenderTexture.BLANK`, so any surface it is attached to composites as blank, and it dumps each surface separately; neither tells you what the frame looks like.

- [ ] **Step 7: Run the whole build and commit**

```bash
./gradlew build
git add src/main/kotlin/render/RunLifecycle.kt src/test/kotlin/render/RunLifecycleTest.kt src/main/kotlin/EnPustTil.kt
git commit -m "fix: attract mode shows a live diver, not the last player's corpse"
```

---

### Task 10: The Windows verification protocol

**Why:** the `.exe` has never been run — by anyone, ever. `docs/superpowers/specs/2026-08-11-outstanding-work.md` §5 and `README.md` risk 3 both say so. The build half works (`./gradlew buildWin64Release` exits 0 and produces a 52.5 MB zip containing `en-pust-til.exe` — `PE32 executable (GUI) Intel 80386` — plus a bundled `jre/bin/`, with `application-dev.cfg` correctly excluded and exactly one `quad.vert`, the engine's deliberately-unfixed copy). Nothing past "it builds" is known.

Tasks 1-9 exist to make an untested binary *survivable*. This one is how you stop it being untested. **It cannot be completed from macOS** — it is the owner's to run, and every earlier task should be finished before it so one Windows session covers everything.

**Files:**
- Create: `docs/booth/windows-verification.md`

**Interfaces:**
- Consumes: everything. This is the acceptance pass for the whole plan.
- Produces: a filled-in checklist committed back to the repo, so the next person knows what was actually observed rather than what was hoped.

- [ ] **Step 1: Write the protocol**

Create `docs/booth/windows-verification.md` with these checks, each stating what to do, what to expect, and what it means if it fails:

1. **It starts at all.** Unzip, run `start-booth.bat`. Expect fullscreen at the panel's native resolution, no title bar, the attract screen. *If it fails:* read the booth log (check 2) — this is the check every other one depends on.
2. **The booth log exists and has content.** `%USERPROFILE%\EnPustTil\logs\booth-<epoch>.log`. Expect the start-up banner, `Daily seed: 20260902`, `Buttons: kick=A …`, and the gamepad diagnostics. *If empty:* Task 1 did not install, and every remaining check is blind.
3. **`drawQuad`/`drawLine` on Windows drivers.** The release ships the engine's unfixed shaders deliberately (`build.gradle.kts:53-58`, ~85% confidence they work on Windows). Nothing in the game calls them, so this is about the scene editor only — but confirm the *game* renders correctly: water bands, rock, pearls, HUD, diver. *If the world is missing:* the shadowing exclusion is wrong and the release needs our copies after all.
4. **Frame rate with GI on.** `targetFps = 120` (`application.cfg:2`), GI is on, and no one has ever measured it. The in-game profiler is unreachable (the F1 console does not exist — see below), so measure from outside the process. *If it is below 60:* the panel resolution is the lever, and `CameraRig` is resolution-independent by construction.
5. **The arcade encoder.** Plug it in. Does it appear in `engine.input.gamepads` at all, or only as an unmapped joystick? Read the booth status line (Task 5) and the log. Which physical button reports which code? *If the codes are wrong:* edit `application.cfg` (Task 7) — no rebuild.
6. **The watchdog.** Kill `en-pust-til.exe` from Task Manager. Expect the console window to report the exit code and relaunch after 5 seconds. *If it does not:* the booth has no restart cover.
7. **The crash path.** With `EPT_DEV=1`, force a fault if you can reach one. Expect the cabinet to survive with the booth status line showing `1 FAULT IN onRender` and **no Notepad**. *If Notepad appears:* a callback escaped Task 2's guard.
8. **The stuck button.** Wedge the START button closed (tape). Expect: the status line shows `1 STUCK INPUT` within ~3 s, and pressing A still starts a run. *If it does not:* Task 4 did not take.
9. **Scores across a restart.** Play a run worth recording, enter initials, kill the process, let the watchdog restart it. Expect the score on the attract leaderboard. Then check `%USERPROFILE%\EnPustTil\scoreboard.json` and confirm no `.tmp` files are left.
10. **Hard power cut.** Play a scoring run, then pull the power (not a shutdown). On restart, expect the board intact — `promoteAtomically` guarantees no torn file, but **not** an fsync against true power loss, so the last score may legitimately be missing. Record which.
11. **The day-two seed.** Uncomment and change `dailySeed` in `application.cfg`, restart. Expect the attract status line to show the new number and the leaderboard to be **empty**. *If the number did not change:* the edit did not take (Task 5 exists to make this visible).
12. **The raffle.** Confirmed still broken, exactly as `CLAUDE.md` states: `ScoreRepository.kt:169` registers `winner` correctly and ungated, but nothing constructs `CommandLine`, the engine never runs a `.pes` script, and F1 is inert. `engine.service.add(CommandLine())` in `onCreate` is the plausible one-line fix and is **UNTESTED**. Either test it in this session or plan to compute the draw by hand from `scoreboard.json` — the weighting is `10 + 3*(score/best)` tickets, three lines of arithmetic. **Decide before the booth, not at it.**
13. **Eight hours unattended.** Leave it running on the attract screen overnight with a few runs played first. Expect it alive in the morning, the status line clean, memory not climbing. This is the only check that tests what the booth actually asks of it.

- [ ] **Step 2: Run it on hardware and record what happened**

Fill in each check with the observed result — not "OK", but what was seen. A checklist of ticks is worth nothing next year.

- [ ] **Step 3: Commit the filled-in protocol**

```bash
git add docs/booth/windows-verification.md
git commit -m "docs: what the exe actually did the first time anyone ran it"
```

---

## Execution notes

**Order matters in two places and nowhere else.** Task 1 first (every later diagnostic writes to the file it creates) and Task 10 last (it is the acceptance pass). Task 5 consumes Task 2's and Task 4's counters, Task 6 consumes Task 4's `firedPadId`, and Task 7 rewrites the button constants Task 4 touched — so 2 and 4 before 5, 4 before 6, and 4 before 7. Tasks 8 and 9 are independent of all of it and can be done any time after 1.

**What this plan does not make true.** After all ten tasks the cabinet survives its own faults, recovers from process death, tolerates a jammed button, reports what is wrong, keeps its scores and can be remapped with Notepad. It is still **silent**, it still has **no cash-out**, and the **bleed trail is still invisible** — the three things spec §12 says are how a queue forms. Those need their own plan, and 13 days is not much. Decide early whether they are in.

**Two audit items are deliberately left out, and both are judgement calls rather than oversights.** `drawRunOverScreen` (`EnPustTil.kt:1365-1379`) and `drawInitialsEntryScreen` (`:1442-1461`) use raw layout literals (`h * 0.5f`, `h * 0.46f`, `h * 0.032f`) with no test that they clear the HUD — unlike `AttractLayout` and `PauseLayout`, which are extracted and asserted. They are the two screens every player sees at the end of every run, so the risk is real, but they *work today* and extracting them is a refactor with no failing symptom to guide it. If there is time after Task 10, extract them the way `AttractLayout` was. Spec §3's zone-specific movement (kelp fronds, trench currents) is out for the reason given in the Context section: it is an owner decision about scope, not a defect to fix.

**Merge `master`.** Not a task here because it is a review decision, not an implementation: `master` is still the empty Pulse Engine template (36 files vs HEAD's 178, 158 commits behind). Anyone cloning the default branch gets the sample, not the game. For a booth with a bus factor of one, that is worth closing before the conference.
