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
        // BoothLog.install replaces both System.out and System.err, so both must be
        // restored — leaving stderr redirected would leak into every later test in this JVM.
        val originalOut = System.out
        val originalErr = System.err
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
            System.setOut(originalOut)
            System.setErr(originalErr)
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
