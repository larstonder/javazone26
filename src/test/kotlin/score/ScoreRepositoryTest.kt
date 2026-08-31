package score

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import kotlin.test.AfterTest
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

        /**
         * MUST match `DataImpl`'s own mapper construction, not merely use "the same
         * library" - those are different claims, and the first cut of this file asserted
         * the weaker one without measuring it. `DataImpl`'s companion object builds its
         * `jsonMapper` as `ObjectMapper().registerModule(KotlinModule.Builder().build())
         * .enableDefaultTyping().configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
         * .configure(FAIL_ON_INVALID_SUBTYPE, false)` — verified by disassembling
         * `DataImpl.class`'s static initialiser in the exact `pulse-engine-0.13.0.jar`
         * this build resolves (see task-8-report.md for the bytecode). `enableDefaultTyping()`
         * is the part that matters: it changes the WIRE FORMAT, wrapping every `List` as a
         * `[className, elements]` pair (`score/README.md`'s "On-disk format" section shows
         * a real file). A bare `jacksonObjectMapper()` - what this fake used before this
         * was measured - cannot read a file written by the real engine at all: it throws
         * `MismatchedInputException`, which [load] below already catches and turns into
         * `null`, so the failure was invisible - it just looked like an empty board. See
         * `an engine-written scoreboard round-trips through this store's mapper` below,
         * which is what caught it.
         */
        private val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .enableDefaultTyping()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)

        override fun load(name: String): ScoreboardData?
        {
            val f = File(dir, name)
            if (!f.exists()) return null
            // null-on-failure, matching engine.data.loadObject's verified behaviour (confirmed
            // by decompiling DataImpl.loadObject: the whole body is wrapped in a
            // try/catch(Throwable) that logs at ERROR and returns null on any failure).
            return runCatching { mapper.readValue(f.readText(), ScoreboardData::class.java) }.getOrNull()
        }

        override fun saveSync(data: ScoreboardData, name: String): Boolean
        {
            writes += name
            File(dir, name).writeText(mapper.writeValueAsString(data))
            return true
        }

        override fun saveAsync(data: ScoreboardData, name: String, onWritten: () -> Unit)
        {
            // Synchronous here on purpose: the ORDER of write-then-promote is what is under
            // test, not the threading. registerScore itself no longer goes through this
            // path at all (a code review switched the live-file save to synchronous - see
            // ScoreRepository.saveAndPromote's doc) - this remains exercised only via
            // maybeRollBackup's backup roll.
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

    /**
     * A store whose BACKUP writes fail and whose every other write succeeds — the seam
     * `ScoreRepository.clearBoard`'s refusal is driven through.
     *
     * Matched on the `scoreboard-backup-` prefix rather than by failing `saveSync`
     * wholesale, because failing every write would abort the wipe for the wrong reason:
     * the live-file save would fail too, and the test would pass with the refusal deleted.
     *
     * Takes its delegate as a constructor PARAMETER rather than constructing one in the
     * delegation expression, which also sidesteps the restriction [UnpromotableStore]'s
     * doc records (Kotlin cannot name an inner class's constructor there) — and it is
     * what lets a test reach the underlying [FakeStore] to inspect the directory
     * afterwards.
     */
    private class NoBackupStore(val inner: FakeStore) : ScoreStore by inner
    {
        override fun saveSync(data: ScoreboardData, name: String): Boolean =
            if (name.startsWith(BACKUP_PREFIX)) false else inner.saveSync(data, name)
    }

    // Every test in this class creates at least one temp directory via tempDir() and none
    // of them cleaned up after themselves - nine leaked per run. Tracked here and swept
    // in tearDown, the same shape AtomicFileSwapTest already uses for its one shared dir.
    private val createdDirs = mutableListOf<File>()

    private fun tempDir(): File =
        File.createTempFile("scorerepo", "").let { it.delete(); it.mkdirs(); it }.also { createdDirs += it }

    @AfterTest
    fun tearDown()
    {
        createdDirs.forEach { it.deleteRecursively() }
    }

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
        // ORIGINALLY THE RACE: saveObjectAsync ran on Dispatchers.IO, a MULTI-threaded
        // pool, and both save paths used the one constant "scoreboard.json.tmp". Writer A's
        // promote could rename a file writer B was midway through rewriting - exactly the
        // torn scoreboard.json promoteAtomically exists to prevent. A later review switched
        // the live-file save to synchronous (ScoreRepository.saveAndPromote), which removes
        // the concurrency this raced on - but freshTempName() stays belt-and-braces (see its
        // doc), and this test still pins that two sequential registerScore calls never
        // reuse a temp name, regardless of how the save happens to be scheduled.
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
        //
        // Exactly two reports, pinned: promoteAtomically's own onFailure callback (the
        // exception itself, "Could not promote...") AND ScoreRepository.saveAndPromote's
        // own check of the returned Boolean ("SCORE NOT SAVED..."). Asserting only
        // isNotEmpty() would still pass with either half deleted - both are load-bearing,
        // since a technician greps the booth log for one specific string
        // ("SCORE NOT SAVED") and the exception detail is what explains why.
        val reported = mutableListOf<String>()
        val r = ScoreRepository(todaySeed = 1L, store = UnpromotableStore(tempDir()), onSaveFailure = { reported += it })

        r.registerScore(initials = "LTO", score = 500)

        assertEquals(2, reported.size, "expected the promote failure AND the not-saved report: $reported")
    }

    @Test
    fun `an engine-written scoreboard round-trips through this store's mapper`()
    {
        // A REAL file, byte for byte, from a dev machine's ~/EnPustTil/scoreboard.json -
        // the same file score/README.md's "On-disk format" section quotes. DataImpl's
        // mapper has enableDefaultTyping() switched on (verified against the resolved
        // pulse-engine-0.13.0.jar's bytecode - see task-8-report.md), which wraps every
        // List as a ["className", elements] pair. Before FakeStore's mapper was corrected
        // to match, this file failed to parse at all (MismatchedInputException, caught by
        // load() and turned into null) and silently read back as an empty board - the
        // exact failure this test exists to catch.
        val store = FakeStore(tempDir())
        store.fileFor(ScoreRepository.LIVE_FILE).writeText(
            """{"entries":["java.util.ArrayList",[""" +
                """{"initials":"AAA","score":1989,"seed":20260902,"timestampMs":1785935323251},""" +
                """{"initials":"AEE","score":229,"seed":20260902,"timestampMs":1786459993180}""" +
                """]]}"""
        )

        val r = repo(store, seed = 20260902L)
        r.onCreateForTest()

        val top = r.topN(10)
        assertEquals(2, top.size, "the real engine-written file must round-trip, not silently read as empty")
        assertEquals(listOf("AAA", "AEE"), top.map { it.initials })
        // Pin the score too, not just initials/order: AAA-before-AEE is also what the
        // timestamp tie-break in Leaderboard.rank would produce even if both scores were
        // equal, so without this the test would not actually catch a dropped/garbled
        // `score` field on the round trip.
        assertEquals(1989, top[0].score)
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
    fun `registering a score with no store reports the loss and never appears on the board`()
    {
        // Unreachable in the real booth's call order - onCreate always builds a store
        // before updateGame can call registerScore - but the guard exists so a null
        // store cannot silently accept a score into memory that is never written and
        // never reported. Both halves matter: reverting the guard to run AFTER
        // entries.add (instead of before) leaves this exact scoring run present on the
        // in-memory board while still failing to persist it - the worse of the two
        // failures, since the booth's own HUD would then show a score that vanishes the
        // moment the process restarts. All 594 tests before this one passed with that
        // ordering; this is the one that would have caught it.
        val reported = mutableListOf<String>()
        val r = ScoreRepository(todaySeed = 1L, store = null, onSaveFailure = { reported += it })

        r.registerScore(initials = "LTO", score = 500)

        assertEquals(1, reported.size, "a score with nowhere to go must be reported exactly once")
        assertTrue(r.topN(10).isEmpty(), "an unpersisted score must not appear on the board either")
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

    @Test
    fun `clearing the board removes today's scores from the live file, not just from memory`()
    {
        // Both halves are load-bearing. Clearing only `entries` looks correct on screen
        // and comes straight back on the next launch; promoting without clearing the
        // in-memory list wipes the file under a board that still shows the rows.
        val store = FakeStore(tempDir())
        val r = repo(store, seed = 20260903L)
        r.registerScore(initials = "AAA", score = 100)
        r.registerScore(initials = "BBB", score = 200)

        assertTrue(r.clearBoard(), "the wipe should have gone ahead")

        assertEquals(emptyList(), r.topN(10))
        assertEquals(emptyList(), store.load(ScoreRepository.LIVE_FILE)!!.entries)
    }

    @Test
    fun `clearing one seed leaves another seed's scores untouched`()
    {
        // THE ONE THAT MATTERS MOST. Day one's rows live in the same file as day two's,
        // separated only by the seed each entry carries (see `yesterday's scores stay on
        // disk but off today's board` above, which is the read side of the same
        // mechanism). A delete implemented as `entries.clear()` passes every other test
        // in this block and destroys day one while day two is running.
        val store = FakeStore(tempDir())
        val r = repo(store, seed = 20260903L)
        r.registerScore(initials = "OLD", score = 900, seed = 20260902L)
        r.registerScore(initials = "NEW", score = 100, seed = 20260903L)

        assertTrue(r.clearBoard(), "the wipe should have gone ahead")

        assertEquals(emptyList(), r.topN(10), "today's board should be empty")
        assertEquals(listOf("OLD"), r.topN(10, seed = 20260902L).map { it.initials })
        assertEquals(
            listOf("OLD"),
            store.load(ScoreRepository.LIVE_FILE)!!.entries.map { it.initials },
            "day one must survive on disk, not merely in memory"
        )
    }

    @Test
    fun `clearing the board leaves a backup holding the scores it destroyed`()
    {
        // The safety net, asserted by its CONTENTS and not merely by its existence: a
        // backup written AFTER the wipe would be an empty file with a plausible name, and
        // an existence-only assertion would happily accept it.
        //
        // Note what this also pins about the trap in ScoreRepository.clearBoard's doc:
        // maybeRollBackup cannot produce this file. Its 30-minute gate is measured from
        // construction, so within a test - and within the first half hour of any real
        // session - it writes nothing at all, and this assertion is exactly what fails if
        // someone "simplifies" clearBoard into calling it.
        val store = FakeStore(tempDir())
        val r = repo(store)
        r.registerScore(initials = "AAA", score = 100)
        r.registerScore(initials = "BBB", score = 200)

        r.clearBoard()

        val backups = store.listNames().filter { it.startsWith(BACKUP_PREFIX) }
        assertEquals(1, backups.size, "expected exactly one pre-delete backup: ${store.listNames()}")
        assertEquals(
            listOf("AAA", "BBB"),
            store.load(backups.single())!!.entries.map { it.initials },
            "the backup must hold the PRE-wipe board, not the empty one that replaced it"
        )
    }

    @Test
    fun `a failed backup aborts the wipe and leaves the board intact`()
    {
        // A destructive, irreversible operation must not proceed when its safety net
        // failed. Every other outcome here - wiping anyway, or wiping and reporting -
        // loses a booth day's prize draw to a full disk.
        val reported = mutableListOf<String>()
        val inner = FakeStore(tempDir())
        val r = ScoreRepository(
            todaySeed = 1L,
            store = NoBackupStore(inner),
            onSaveFailure = { reported += it }
        )
        r.registerScore(initials = "AAA", score = 100)

        assertEquals(false, r.clearBoard(), "a wipe with no backup must refuse")

        assertEquals(listOf("AAA"), r.topN(10).map { it.initials }, "the in-memory board must survive")
        assertEquals(
            listOf("AAA"),
            inner.load(ScoreRepository.LIVE_FILE)!!.entries.map { it.initials },
            "the live file must survive"
        )
        assertEquals(1, reported.size, "the refusal must be reported exactly once: $reported")
        assertTrue(reported.single().startsWith("BOARD NOT CLEARED"), reported.single())
    }

    @Test
    fun `the ranked cache does not survive a clear`()
    {
        // topN serves a cached FULL ranking per seed (see rankedCache's doc) and the cache
        // is only ever invalidated wholesale. A clear that forgets to do so keeps serving
        // the deleted rows for the rest of the session - on the attract screen, every
        // frame - while the file on disk is already empty. The first topN call here is not
        // decoration: it is what puts the entry in the cache that the second call would
        // otherwise be served from.
        val store = FakeStore(tempDir())
        val r = repo(store)
        r.registerScore(initials = "AAA", score = 100)
        assertEquals(listOf("AAA"), r.topN(10).map { it.initials }, "precondition: the cache is warm")

        r.clearBoard()

        assertEquals(emptyList(), r.topN(10), "topN served a ranking computed before the wipe")
    }

    private companion object
    {
        /** `ScoreRepository.writeBackup`'s filename shape: `scoreboard-backup-<epochMs>.json`. */
        const val BACKUP_PREFIX = "scoreboard-backup-"
    }
}
