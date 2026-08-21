package score

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [promoteAtomically] against a REAL filesystem (a temp directory), not a
 * mock — the whole point of that function is a guarantee about actual file operations,
 * so a test that mocked `java.nio.file.Files` would prove nothing about it.
 */
class AtomicFileSwapTest
{
    private lateinit var dir: File

    @BeforeTest
    fun setUp()
    {
        dir = Files.createTempDirectory("score-repo-test").toFile()
    }

    @AfterTest
    fun tearDown()
    {
        dir.deleteRecursively()
    }

    @Test
    fun `promotes a fully-written temp file into the live filename`()
    {
        val temp = dir.resolve("scoreboard.json.tmp").apply { writeText("""{"entries":[]}""") }
        val live = dir.resolve("scoreboard.json")

        val promoted = promoteAtomically(temp, live)

        assertTrue(promoted)
        assertTrue(live.exists())
        assertEquals("""{"entries":[]}""", live.readText())
        assertFalse(temp.exists(), "the temp file must be consumed by the rename, not left behind")
    }

    @Test
    fun `replaces an existing live file rather than failing`()
    {
        val temp = dir.resolve("scoreboard.json.tmp").apply { writeText("NEW") }
        val live = dir.resolve("scoreboard.json").apply { writeText("OLD") }

        assertTrue(promoteAtomically(temp, live))
        assertEquals("NEW", live.readText())
    }

    @Test
    fun `a missing temp file is reported as a failed promotion, and the live file is left untouched`()
    {
        val temp = dir.resolve("does-not-exist.tmp")
        val live = dir.resolve("scoreboard.json").apply { writeText("ORIGINAL") }

        val promoted = promoteAtomically(temp, live)

        assertFalse(promoted)
        assertEquals("ORIGINAL", live.readText(), "a failed promotion must never touch the live file")
    }

    @Test
    fun `the live file is never left half-written - it is either fully the old content or fully the new`()
    {
        // Not a true crash-mid-write simulation (that needs kill -9, which a JVM test
        // cannot do to itself), but it documents and checks the mechanism that provides
        // the guarantee: the live file is only ever touched by a single atomic rename
        // call, never opened for incremental writing.
        val temp = dir.resolve("scoreboard.json.tmp").apply { writeText("X".repeat(10_000)) }
        val live = dir.resolve("scoreboard.json").apply { writeText("OLD") }

        promoteAtomically(temp, live)

        val content = live.readText()
        assertTrue(content == "X".repeat(10_000), "must be fully the new content, never a partial write")
    }

    @Test
    fun `a promotion that cannot happen reports why`()
    {
        val reported = mutableListOf<Exception>()
        val temp = dir.resolve("swap.tmp").apply { writeText("payload") }
        // A directory where the live file should be: Files.move cannot replace it. Confirmed
        // on this filesystem (APFS, macOS) by running this test — it fails for the expected
        // reason (java.nio.file.FileSystemException: "Is a directory"), not vacuously; the
        // same holds on NTFS per the java.nio.file.Files.move documentation. Built inside
        // `dir` (not a second File.createTempFile pair) so tearDown's deleteRecursively
        // sweeps it along with everything else this class creates.
        //
        // WHICH BRANCH THIS EXERCISES: `temp` and `live` are both on the same filesystem,
        // so ATOMIC_MOVE genuinely is supported here and the FileSystemException above is
        // thrown straight out of the FIRST Files.move call, landing in promoteAtomically's
        // generic `catch (e: Exception)` — NOT the nested try/catch inside the
        // AtomicMoveNotSupportedException fallback branch. That nested catch (added when a
        // review pointed out sibling `catch` clauses don't catch each other) stays
        // deliberately untested: triggering it needs a filesystem that BOTH rejects
        // ATOMIC_MOVE and then fails the plain replace fallback too, which is not
        // reachable from a single real filesystem in a JVM test. Read as covering "a
        // promotion fails", not "every branch that can report a failure".
        val live = dir.resolve("swaplive").apply { mkdirs(); resolve("child").writeText("x") }

        val ok = promoteAtomically(temp, live, onFailure = { reported += it })

        assertFalse(ok)
        assertTrue(reported.isNotEmpty(), "the caller must be told the score was not saved")
    }
}
