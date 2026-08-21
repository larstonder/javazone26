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
