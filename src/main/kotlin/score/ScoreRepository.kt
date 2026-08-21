package score

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.console.CommandResult
import no.njoh.pulseengine.core.service.Service
import no.njoh.pulseengine.core.shared.utils.Logger
import kotlin.random.Random

/**
 * Score persistence for a two-day unattended arcade booth. Daily prizes are drawn from
 * this leaderboard, so losing scores is a real failure — see the class-level guarantees
 * documented on each write path below.
 *
 * Registered as an engine [Service] (see `EnPustTil.onCreate` — `engine.service.add`),
 * which gives this class [onCreate]/[onDestroy] hooks driven by the engine's own
 * lifecycle (verified by decompiling `ServiceManagerImpl`: `add()` queues the service,
 * `init()` — called right after `PulseEngineGame.onCreate()` returns — invokes
 * `onCreate` on everything queued so far, and `destroy()` — called during engine
 * shutdown, just after `PulseEngineGame.onDestroy()` — invokes `onDestroy` on every
 * registered service). No name or e-mail is ever collected — design spec §12 picks
 * three-letter initials specifically to avoid personal data, so there is no GDPR
 * consent flow to build here.
 *
 * PERSISTENCE SHAPE: JSON via [ScoreStore] (a seam over `engine.data`, at
 * `engine.config.saveDirectory/scoreboard.json`). Every registered score triggers an
 * async save (never blocks the render thread) plus, at most once every
 * [BACKUP_INTERVAL_MS], a rolling timestamped backup (`scoreboard-backup-<epoch>.json`)
 * — two days of booth scores in one un-backed-up file is a single point of failure. A
 * final, SYNCHRONOUS save happens in [onDestroy] so a clean shutdown cannot lose the
 * tail of scores registered since the last periodic save.
 *
 * THE SEAM: [store] is normally built from the engine in [onCreate] — see
 * [EngineScoreStore] for what was verified (by decompiling `DataImpl`) about its
 * failure behaviour. A test constructs this class with a real [ScoreStore] directly (a
 * temp directory, no engine at all), which is what finally lets `registerScore`, the
 * atomic promote, the corrupt-file path and the stale-temp sweep be asserted for real —
 * see `ScoreRepositoryTest`.
 *
 * DURABILITY: see [promoteAtomically] for exactly what guarantee the write path
 * achieves (a real atomic swap on filesystems that support it — POSIX and NTFS both do
 * — never a torn `scoreboard.json`, but not an `fsync` guarantee against true power
 * loss). A failed promotion is reported through [onSaveFailure] rather than swallowed —
 * see [saveAsync]. Reads go through [sanitizeEntries] on top of [ScoreStore.load]'s own
 * verified null-on-failure behaviour, so a corrupt or missing file starts a fresh board
 * rather than crashing or blocking a run.
 */
class ScoreRepository(
    private val todaySeed: Long,
    private var store: ScoreStore? = null,
    private val onSaveFailure: (String) -> Unit = { Logger.error { it } }
) : Service()
{
    private var entries: MutableList<ScoreEntry> = mutableListOf()
    private var lastBackupTimeMs = System.currentTimeMillis()

    // onCreate builds the real store from the engine when one was not injected. Injection
    // is what ScoreRepositoryTest uses; the booth always takes this branch.
    override fun onCreate(engine: PulseEngine)
    {
        if (store == null) store = EngineScoreStore(engine)
        val s = store!!
        loadInto(s)
        Logger.info { "ScoreRepository loaded ${entries.size} score(s) from ${engine.config.saveDirectory}" }
        registerWinnerCommand(engine)
    }

    /** Top [n] entries for [seed] (defaults to today's), ranked — see [Leaderboard.rank]. */
    fun topN(n: Int, seed: Long = todaySeed): List<ScoreEntry> =
        Leaderboard.topN(entries.filter { it.seed == seed }, n)

    /**
     * Record a completed run's score. No-ops (and never touches disk) for a run not
     * worth recording — see [Leaderboard.isWorthRecording] — so a 0-point run never
     * costs a write.
     */
    fun registerScore(initials: String, score: Int, seed: Long = todaySeed)
    {
        if (!Leaderboard.isWorthRecording(score)) return

        val clean = initials.uppercase().filter { it in 'A'..'Z' }.take(3).padEnd(3, 'A')
        entries.add(ScoreEntry(clean, score, seed, System.currentTimeMillis()))

        val s = store ?: return
        saveAsync(s)
        maybeRollBackup(s)
    }

    /**
     * Async save, promoted atomically once the write finishes — see [promoteAtomically].
     * [ScoreStore.saveAsync] runs the write on `Dispatchers.IO` (verified by decompiling
     * `DataImpl` — see [EngineScoreStore]'s class doc), so this never blocks the render
     * thread, and the completion callback — which does the promotion — runs on that same
     * background thread rather than the game thread.
     *
     * A defensive snapshot ([List], not the live [entries]) is what gets serialised:
     * without it, a score registered while a previous save is still in flight on the IO
     * thread could mutate [entries] out from under Jackson's serialiser mid-write.
     */
    private fun saveAsync(s: ScoreStore)
    {
        val snapshot = ScoreboardData(entries.toList())
        val temp = freshTempName()
        s.saveAsync(snapshot, temp) {
            val promoted = promoteAtomically(
                temp = s.fileFor(temp),
                live = s.fileFor(LIVE_FILE),
                onFailure = { e -> onSaveFailure("Could not promote $temp to $LIVE_FILE: $e") }
            )
            // A lost score is a real failure at a booth whose prizes are drawn from this
            // board. It used to be discarded here with no log at all — and until
            // booth/BoothLog.kt existed, a log would have gone nowhere anyway.
            if (!promoted) onSaveFailure("SCORE NOT SAVED - $LIVE_FILE was not updated from $temp")
        }
    }

    /**
     * A FRESH temp name per write, not a shared constant.
     *
     * `saveObjectAsync` runs on `Dispatchers.IO`, a MULTI-threaded pool (verified — see
     * [EngineScoreStore]'s class doc), and both save paths used to write to the single
     * constant "scoreboard.json.tmp". Two overlapping writes could then have writer A's
     * promote rename a file writer B was midway through rewriting — producing exactly the
     * torn scoreboard.json that [promoteAtomically] exists to prevent. Runs are ~90 s
     * apart at a booth so this was unlikely in practice; it is also free to remove, and
     * the class doc claims the guarantee unconditionally.
     */
    private fun freshTempName(): String = "scoreboard.${java.util.UUID.randomUUID()}.tmp"

    /** At most once every [BACKUP_INTERVAL_MS] — see the class doc for why. */
    private fun maybeRollBackup(s: ScoreStore)
    {
        val now = System.currentTimeMillis()
        if (now - lastBackupTimeMs < BACKUP_INTERVAL_MS) return
        lastBackupTimeMs = now

        val snapshot = ScoreboardData(entries.toList())
        val backupName = "scoreboard-backup-$now.json"
        // Each backup is its own uniquely-named file, never overwritten and never read
        // back by this game — an interrupted backup write only ever damages that one
        // timestamped file, never the live board or an earlier backup, so this does not
        // need the temp+promote dance saveAsync uses for the live file.
        s.saveAsync(snapshot, backupName) { }
    }

    /**
     * Synchronous and blocking, deliberately: this runs during a clean shutdown (see
     * class doc for the verified call order — [PulseEngine]'s own `PulseEngineGame
     * .onDestroy()` runs first, then this), and there is no guarantee the process
     * survives long enough for an async write to finish. A few milliseconds of blocking
     * at shutdown is an acceptable trade the render thread never has to make during
     * actual gameplay, where every other save on this class is async.
     */
    override fun onDestroy(engine: PulseEngine)
    {
        store?.let { finalSave(it) }
        Logger.info { "ScoreRepository saved ${entries.size} score(s) on shutdown" }
    }

    private fun finalSave(s: ScoreStore)
    {
        val snapshot = ScoreboardData(entries.toList())
        val temp = freshTempName()
        s.saveSync(snapshot, temp)
        val promoted = promoteAtomically(
            temp = s.fileFor(temp),
            live = s.fileFor(LIVE_FILE),
            onFailure = { e -> onSaveFailure("Could not promote $temp to $LIVE_FILE on shutdown: $e") }
        )
        if (!promoted) onSaveFailure("SCORE NOT SAVED ON SHUTDOWN - $LIVE_FILE was not updated from $temp")
    }

    /**
     * Loads today's (and yesterday's, and every prior day's — see class doc) entries
     * from [s] into [entries], and sweeps any stale temp file left behind by a crash
     * between "temp written" and "promote called" — see [sweepStaleTemps].
     */
    private fun loadInto(s: ScoreStore)
    {
        sweepStaleTemps(s)
        entries = loadEntries(s).toMutableList()
    }

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

    private fun loadEntries(s: ScoreStore): List<ScoreEntry>
    {
        if (!s.exists(LIVE_FILE)) return emptyList()

        val loaded = try
        {
            // Loaded through a concrete wrapper class, not a bare List<ScoreEntry> —
            // engine.data.loadObject is a reified generic that resolves to
            // loadObject(path, ScoreEntry::class.java, ...) at the call site, and a
            // top-level List<T> would erase to a raw Class<List> at runtime, losing the
            // element type Jackson needs. A wrapper class's FIELD generics are preserved
            // by Jackson's reflection-based introspection, so this round-trips correctly.
            s.load(LIVE_FILE)?.entries
        }
        catch (e: Exception)
        {
            // Belt-and-braces: ScoreStore.load already catches internally — CONFIRMED by
            // decompiling DataImpl.loadObject (see EngineScoreStore's class doc): a
            // runCatching-shaped block that logs and returns null on ANY Throwable,
            // including malformed JSON from a hard power-off mid-write — but this game
            // must never depend on unverified behaviour from a third-party jar for "does
            // not crash on a corrupt save file" — hence this redundant catch, and see the
            // three `sanitizeEntries` cases in InitialsEntryTest (they live there because
            // sanitizeEntries sits alongside isValidInitials, not in a file of its own)
            // for the second, unit-tested layer of defence. This used to cite a
            // `ScoreSanitizerTest`, which has never existed.
            Logger.error(e) { "Failed to load $LIVE_FILE — starting a fresh leaderboard" }
            null
        }

        return sanitizeEntries(loaded)
    }

    private fun registerWinnerCommand(engine: PulseEngine)
    {
        // Draws the on-site raffle prize from today's board — run at end of day.
        //
        // WARNING: THERE IS CURRENTLY NO WAY TO INVOKE THIS. This comment used to read "open
        // console (F1) and type winner", and F1 opens nothing. The command registers fine, but
        // pulse-engine 0.13.0 never constructs the widget that would read it: `showConsole`
        // appears only inside `no/njoh/pulseengine/modules/cli/CommandLine.class`, nothing in the
        // jar or in this project references `cli/CommandLine`, and init.pes's own header records
        // that the engine runs no .pes script, so its `bind F1 showConsole` is inert too.
        // `CommandLine` extends `Service`, so `engine.service.add(CommandLine())` in
        // EnPustTil.onCreate is the likely fix — untested. Verify before relying on it at the
        // booth; the fallback is to read scoreboard.json and draw by hand.
        //
        // Registration is deliberately NOT gated on EPT_DEV. Weighted so a bigger score is more
        // likely to win, but every qualifying entry (score > 0) has a real chance: 10
        // base tickets plus up to 3 more scaled by how close the score is to the day's
        // best. No name or e-mail is ever involved — if a prize needs contact details,
        // collect them on paper at the booth, not in the game (see class doc).
        engine.console.registerCommand(template = "winner")
        {
            val today = topN(n = Int.MAX_VALUE)
            if (today.isEmpty())
                return@registerCommand CommandResult("No scores recorded today — no winner")

            val maxScore = today.maxOf { it.score }.toFloat().coerceAtLeast(1f)
            fun ScoreEntry.tickets() = 10 + (3 * (score / maxScore)).toInt()

            val totalTickets = today.sumOf { it.tickets() }
            val winningTicket = Random.nextInt(totalTickets)
            var sum = 0
            for (entry in today)
            {
                val ticketsToWin = entry.tickets()
                if (winningTicket in sum until sum + ticketsToWin)
                    return@registerCommand CommandResult("Winner: ${entry.initials} — ${entry.score} pts")
                sum += ticketsToWin
            }
            CommandResult("No winner")
        }
    }

    /** See DiveSim's `debug*` hooks — the same pattern, for the same reason: a narrow,
     * clearly-named way for tests to drive the two engine lifecycle hooks without a
     * booted PulseEngine. */
    internal fun onCreateForTest() { store?.let { loadInto(it) } }
    internal fun onDestroyForTest() { store?.let { finalSave(it) } }

    companion object
    {
        internal const val LIVE_FILE = "scoreboard.json"
        private const val BACKUP_INTERVAL_MS = 30 * 60 * 1000L
    }
}

/** On-disk shape — a concrete wrapper so Jackson keeps [ScoreEntry]'s field generics on load. */
data class ScoreboardData(val entries: List<ScoreEntry>)
