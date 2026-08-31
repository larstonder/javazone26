package score

import no.njoh.pulseengine.core.PulseEngine
import java.io.File

/**
 * Everything [ScoreRepository] needs from the outside world, and nothing else.
 *
 * It exists so the class that owns two days of booth scores can finally be tested. Before
 * this seam, every write path reached through `engine.data` and therefore needed a booted
 * PulseEngine, so `registerScore`, the promote, the corrupt-file path and the backup roll
 * were asserted only in comments - in the ONE class whose stated requirement is "never
 * lose a score".
 *
 * Deliberately narrow. This is not an abstraction over storage; it is the smallest set of
 * calls that lets a test put a real temp directory where `engine.data` normally sits, so
 * the atomic promote is exercised for real rather than mocked away.
 */
interface ScoreStore
{
    fun exists(name: String): Boolean
    fun load(name: String): ScoreboardData?
    fun saveAsync(data: ScoreboardData, name: String, onWritten: () -> Unit)

    /**
     * @return whether the write actually succeeded — the one signal the async path
     *   structurally cannot deliver (see [EngineScoreStore]'s class doc). The caller MUST
     *   check this: a discarded return here is exactly the defect that made a failed
     *   promotion silent before this task.
     */
    fun saveSync(data: ScoreboardData, name: String): Boolean
    fun fileFor(name: String): File
    fun listNames(): List<String>
}

/**
 * The real one, wrapping `engine.data`.
 *
 * VERIFIED, NOT ASSUMED, and against the jar this build actually resolves: `javap -c`
 * against the real `DataImpl.class` inside `pulse-engine-0.13.0.jar` — the exact
 * coordinate `build.gradle.kts` pulls, not the `0.13.0-QUADFIX` one that happened to
 * also be sitting in `~/.m2`. (First pass through this file cross-checked against
 * QUADFIX's *sources* jar instead of the resolved jar's own bytecode — same coordinate
 * family, but a different artifact, and they only happened to agree. Re-verified point
 * by point directly against `pulse-engine-0.13.0.jar`'s bytecode below, including the
 * companion object's static initialiser for the mapper construction.) See
 * task-8-report.md for the raw `javap` output this was checked against.
 *
 * ```kotlin
 * override fun <T> saveObject(data: T, filePath: String, format: FileFormat): Boolean =
 *     runCatching { … file.writeBytes(…) … ; true }
 *         .onFailure { Logger.error { "Failed to save file: $filePath - reason: ${it.message}" } }
 *         .getOrDefault(false)
 *
 * override fun <T> loadObject(filePath: String, type: Class<T>, fromClassPath: Boolean): T? =
 *     runCatching { … }
 *         .onFailure { Logger.error { "Failed to load state …" } }
 *         .getOrNull()
 *
 * override fun <T> saveObjectAsync(data: T, filePath: String, format: FileFormat, onComplete: (T) -> Unit)
 * {
 *     GlobalScope.launch(Dispatchers.IO)
 *     {
 *         saveObject(data, filePath, format).takeIf { it }?.let { onComplete.invoke(data) }
 *     }
 * }
 * ```
 *
 * - `saveObject`/`loadObject` never throw: both wrap their whole body in `runCatching`,
 *   log at ERROR on failure, and return `false` / `null` respectively. The existing code
 *   comment's claim of "verified-safe null-on-failure" for `loadObject` is CONFIRMED. The
 *   exact string, straight from the constant pool: `"Failed to save file: $filePath -
 *   reason: ${it.message}"` — that is the `grep` target a technician wants, and
 *   `score/README.md`'s troubleshooting section now names it.
 * - `saveObjectAsync` launches on `Dispatchers.IO`, coroutines' genuine shared
 *   multi-threaded elastic pool. The brief's premise that two overlapping
 *   `saveObjectAsync` calls can run concurrently on different threads is CONFIRMED.
 * - `saveObjectAsync`'s completion callback fires ONLY IF the internal `saveObject` call
 *   returned `true` — `.takeIf { it }?.let { onComplete.invoke(data) }` skips it entirely
 *   on failure, and the coroutine still completes normally (no exception escapes;
 *   `saveObject` already caught it). **This is why [saveAsync] below is used by nothing at
 *   all any more** — [ScoreRepository] saves synchronously via [saveSync] on every path,
 *   specifically to get the `Boolean` this callback cannot deliver on failure. That was
 *   true of the live score file first; the BACKUP roll followed it, because
 *   `ScoreRepository.clearBoard` has to refuse an irreversible wipe when its safety-net
 *   backup did not land, and a callback that only fires on success cannot tell it so
 *   (`ScoreRepository.writeBackup`'s doc carries the argument). [saveAsync] is kept on the
 *   seam rather than deleted only so the evidence above stays attached to the method it
 *   describes: it is the documented reason this package writes synchronously everywhere,
 *   and re-deriving that from `DataImpl`'s bytecode a third time would be the real cost.
 *   **Do not reach for it for a new write path** — it is the defect, not the tool.
 */
class EngineScoreStore(private val engine: PulseEngine) : ScoreStore
{
    override fun exists(name: String) = engine.data.exists(name)

    override fun load(name: String): ScoreboardData? = engine.data.loadObject<ScoreboardData>(name)

    override fun saveAsync(data: ScoreboardData, name: String, onWritten: () -> Unit)
    {
        engine.data.saveObjectAsync(data, name) { onWritten() }
    }

    override fun saveSync(data: ScoreboardData, name: String): Boolean =
        engine.data.saveObject(data, name)

    override fun fileFor(name: String) = File(engine.config.saveDirectory, name)

    override fun listNames(): List<String> =
        File(engine.config.saveDirectory).listFiles()?.map { it.name } ?: emptyList()
}
