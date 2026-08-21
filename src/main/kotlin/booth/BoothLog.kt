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
