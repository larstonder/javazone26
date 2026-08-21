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
