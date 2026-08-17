package score

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.console.CommandResult
import no.njoh.pulseengine.core.service.Service
import no.njoh.pulseengine.core.shared.utils.Logger
import java.io.File
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
 * PERSISTENCE SHAPE: JSON via `engine.data`, at `engine.config.saveDirectory/
 * scoreboard.json`. Every registered score triggers an async save (never blocks the
 * render thread) plus, at most once every [BACKUP_INTERVAL_MS], a rolling timestamped
 * backup (`scoreboard-backup-<epoch>.json`) — two days of booth scores in one
 * un-backed-up file is a single point of failure. A final, SYNCHRONOUS save happens in
 * [onDestroy] so a clean shutdown cannot lose the tail of scores registered since the
 * last periodic save.
 *
 * DURABILITY: see [promoteAtomically] for exactly what guarantee the write path
 * achieves (a real atomic swap on filesystems that support it — POSIX and NTFS both do
 * — never a torn `scoreboard.json`, but not an `fsync` guarantee against true power
 * loss). Reads go through [sanitizeEntries] on top of `engine.data.loadObject`'s own
 * verified-safe null-on-failure behaviour, so a corrupt or missing file starts a fresh
 * board rather than crashing or blocking a run.
 */
class ScoreRepository(private val todaySeed: Long) : Service()
{
    private var entries: MutableList<ScoreEntry> = mutableListOf()
    private var lastBackupTimeMs = System.currentTimeMillis()

    override fun onCreate(engine: PulseEngine)
    {
        entries = loadEntries(engine).toMutableList()
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
    fun registerScore(engine: PulseEngine, initials: String, score: Int, seed: Long = todaySeed)
    {
        if (!Leaderboard.isWorthRecording(score)) return

        val clean = initials.uppercase().filter { it in 'A'..'Z' }.take(3).padEnd(3, 'A')
        entries.add(ScoreEntry(clean, score, seed, System.currentTimeMillis()))

        saveAsync(engine)
        maybeRollBackup(engine)
    }

    /**
     * Async save, promoted atomically once the write finishes — see [promoteAtomically].
     * `engine.data.saveObjectAsync` runs the write on `Dispatchers.IO` (verified by
     * decompiling `DataImpl`), so this never blocks the render thread, and the
     * completion callback — which does the promotion — runs on that same background
     * thread rather than the game thread.
     *
     * A defensive snapshot ([List], not the live [entries]) is what gets serialised:
     * without it, a score registered while a previous save is still in flight on the IO
     * thread could mutate [entries] out from under Jackson's serialiser mid-write.
     */
    private fun saveAsync(engine: PulseEngine)
    {
        val snapshot = ScoreboardData(entries.toList())
        engine.data.saveObjectAsync(snapshot, TEMP_FILE) {
            promoteAtomically(scoreFile(engine, TEMP_FILE), scoreFile(engine, LIVE_FILE))
        }
    }

    /** At most once every [BACKUP_INTERVAL_MS] — see the class doc for why. */
    private fun maybeRollBackup(engine: PulseEngine)
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
        engine.data.saveObjectAsync(snapshot, backupName) { }
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
        val snapshot = ScoreboardData(entries.toList())
        engine.data.saveObject(snapshot, TEMP_FILE)
        promoteAtomically(scoreFile(engine, TEMP_FILE), scoreFile(engine, LIVE_FILE))
        Logger.info { "ScoreRepository saved ${entries.size} score(s) on shutdown" }
    }

    private fun loadEntries(engine: PulseEngine): List<ScoreEntry>
    {
        if (!engine.data.exists(LIVE_FILE)) return emptyList()

        val loaded = try
        {
            // Loaded through a concrete wrapper class, not a bare List<ScoreEntry> —
            // engine.data.loadObject is a reified generic that resolves to
            // loadObject(path, ScoreEntry::class.java, ...) at the call site, and a
            // top-level List<T> would erase to a raw Class<List> at runtime, losing the
            // element type Jackson needs. A wrapper class's FIELD generics are preserved
            // by Jackson's reflection-based introspection, so this round-trips correctly.
            engine.data.loadObject<ScoreboardData>(LIVE_FILE)?.entries
        }
        catch (e: Exception)
        {
            // Belt-and-braces: engine.data.loadObject already catches internally
            // (verified by decompiling DataImpl.loadObject — a runCatching-shaped block
            // that logs and returns null on ANY Throwable, including malformed JSON from
            // a hard power-off mid-write), but this game must never depend on unverified
            // behaviour from a third-party jar for "does not crash on a corrupt save
            // file" — hence this redundant catch, and see the three `sanitizeEntries` cases in
            // InitialsEntryTest (they live there because sanitizeEntries sits alongside
            // isValidInitials, not in a file of its own) for the second, unit-tested layer of
            // defence. This used to cite a `ScoreSanitizerTest`, which has never existed.
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

    private fun scoreFile(engine: PulseEngine, name: String) = File(engine.config.saveDirectory, name)

    companion object
    {
        private const val LIVE_FILE = "scoreboard.json"
        private const val TEMP_FILE = "scoreboard.json.tmp"
        private const val BACKUP_INTERVAL_MS = 30 * 60 * 1000L
    }
}

/** On-disk shape — a concrete wrapper so Jackson keeps [ScoreEntry]'s field generics on load. */
data class ScoreboardData(val entries: List<ScoreEntry>)
