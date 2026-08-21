package booth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The watchdog cannot be exercised from a JVM test on macOS, so what is asserted here is
 * that it EXISTS, that it loops, that it waits for the right process, that its logging
 * actually reaches disk, and that the release actually ships it. Those are the ways it has
 * historically gone missing or lied about working; the behaviour itself is verified on
 * hardware in `docs/booth/windows-verification.md` step 6.
 *
 * Round 1 review (2026-08-21) found two Criticals that ALL FIVE original tests missed
 * because they only checked for a substring anywhere in the file, comments included:
 * `start` with no `/wait` is a fork bomb, and `%TIME%>>` truncates the log about one line
 * in ten. `executableLines` exists so a delay or a log path mentioned only in a REM cannot
 * satisfy an assertion that is supposed to be checking runnable code.
 */
class BoothLauncherTest
{
    private val script = File("tools/booth/start-booth.bat")

    /** Non-blank lines that are not REM comments - i.e. lines cmd would actually run. */
    private fun executableLines(text: String): List<String> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("rem", ignoreCase = true) }

    @Test
    fun `the watchdog script exists`()
    {
        assertTrue(script.isFile, "tools/booth/start-booth.bat is missing - the booth has no restart cover")
    }

    @Test
    fun `the watchdog relaunches in a loop rather than starting the game once`()
    {
        val text = script.readText().lowercase()
        val lines = text.lines().map { it.trim() }

        // A bare contains(":relaunch") cannot fail unless contains("goto :relaunch") also
        // fails, because the latter string contains the former - that made this assertion
        // unable to catch a missing label on its own. Requiring a line that IS the label
        // closes that gap.
        assertTrue(lines.any { it == ":relaunch" }, "no loop label on its own line - this would start the game once and exit")
        assertTrue(text.contains("goto :relaunch"), "the loop never repeats")
        assertTrue(text.contains("en-pust-til.exe"), "the watchdog does not name the game binary")
    }

    @Test
    fun `the watchdog waits for the game process itself, not just for the launcher to return`()
    {
        // launch4j's gui-header wrapper returns as soon as it has spawned javaw unless the
        // launcher itself is told to stay alive (see the paired build.gradle.kts test below).
        // `start` with no `/wait` - or `start /wait` on a launcher that returns immediately -
        // is satisfied within about a second, so the watchdog would relaunch the game every
        // few seconds forever, starting on the very first boot at the venue.
        val lines = executableLines(script.readText().lowercase())

        assertTrue(
            lines.any { it.contains("start") && it.contains("/wait") && it.contains("en-pust-til.exe") },
            "no executable line waits for en-pust-til.exe with /wait - the watchdog would relaunch the game every few seconds"
        )
    }

    @Test
    fun `the watchdog delays between restarts on an executable line, not merely in a comment`()
    {
        // A game that fails during onCreate would otherwise be relaunched thousands of
        // times a minute, filling the disk with logs and pinning a CPU core. A REM
        // mentioning "timeout" does nothing at runtime - only a line cmd actually executes
        // does, which is why this scans executableLines rather than the whole file.
        val lines = executableLines(script.readText().lowercase())

        assertTrue(
            lines.any { it.contains("timeout") || it.contains("ping ") },
            "no delay between restarts runs as code - a boot-crash loop would spin at full speed"
        )
    }

    @Test
    fun `the watchdog records its restart history to a file, on an executable line`()
    {
        // A cabinet has nowhere to show a cmd window, so echoes that only reach the console
        // are echoes nobody reads. This is NOT the booth log - BoothLog tees the JVM's
        // System.out, and these lines are the shell's. Scanning executableLines means a
        // REM that merely mentions the filename cannot satisfy this on its own.
        val lines = executableLines(script.readText().lowercase())

        assertTrue(
            lines.any { it.contains("watchdog.log") },
            "the restart history never reaches disk as code - the file path must appear on a line cmd runs"
        )
    }

    @Test
    fun `no echo line redirects immediately after a percent-variable close, which cmd would misread as a handle`()
    {
        // cmd expands percent variables BEFORE it scans for redirection operators, and a
        // single digit immediately before ">>" names a handle rather than starting a
        // redirect. %TIME% is HH-mm-ss-ff on every locale, so its last character is always
        // a digit: "...%TIME%>>" silently truncates the echoed line and redirects only a
        // trailing fragment. Leading redirection (">>\"file\" echo ...") never has this
        // problem because nothing precedes the operator.
        //
        // Scanned over executableLines, not the raw file: a REM explaining this exact bug
        // has to be able to quote the buggy syntax it is warning about without tripping
        // the very check meant to catch the syntax in CODE.
        val lines = executableLines(script.readText())
        val badRedirect = Regex("%[A-Za-z_]+%>>")

        assertTrue(
            lines.none { badRedirect.containsMatchIn(it) },
            "a %VAR%>> with no space lets cmd read the variable's last character as a redirection handle, " +
                "silently truncating the logged line"
        )
    }

    @Test
    fun `the watchdog changes into its own folder before launching the exe`()
    {
        // Without this, a shortcut whose "Start in" folder differs from the script's own
        // folder (the default when you drag a .bat into shell:startup) cannot find
        // en-pust-til.exe by its relative name.
        val text = script.readText()

        assertTrue(
            text.contains("cd /d \"%~dp0\""),
            "no cd /d %~dp0 - the exe would not be found when Start in differs from the script's folder"
        )
    }

    @Test
    fun `the script has CRLF line endings throughout`()
    {
        // There is no .gitattributes pinning this, so a clone with core.autocrlf=input
        // would silently strip every CR on checkout. Assert the raw bytes rather than
        // trusting the working tree to preserve what was committed.
        val bytes = script.readBytes()

        for (i in bytes.indices)
        {
            if (bytes[i] == '\n'.code.toByte())
            {
                assertTrue(
                    i > 0 && bytes[i - 1] == '\r'.code.toByte(),
                    "bare LF at byte offset $i - this file must be CRLF throughout"
                )
            }
        }
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

    @Test
    fun `the release exe is configured to stay alive so start wait waits for the JVM, not the launcher`()
    {
        // launch4j's headerType/stayAlive conventions are "gui" / false when unset - verified
        // from the plugin's own bytecode and from the unpacked 3.50 head objects. A gui-header
        // launcher with stayAlive = false calls execute(wait = FALSE): it CreateProcess()es
        // javaw and returns within about a second, while the game is still starting up. cmd's
        // `start /wait` is then satisfied against the WRONG process, and the watchdog launches
        // a new game every few seconds - on the first boot at the venue, not only after a crash.
        val build = File("build.gradle.kts").readText()

        assertTrue(
            Regex("""stayAlive\s*=\s*true""").containsMatchIn(build),
            "launch4j { stayAlive = true } is missing - guihead returns as soon as it spawns javaw, " +
                "so start /wait in start-booth.bat waits for the wrong process"
        )
    }
}
