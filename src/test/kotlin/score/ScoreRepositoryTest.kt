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
            // null-on-failure, matching engine.data.loadObject's verified behaviour (confirmed
            // by decompiling DataImpl.loadObject: the whole body is wrapped in a
            // try/catch(Throwable) that logs at ERROR and returns null on any failure).
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
