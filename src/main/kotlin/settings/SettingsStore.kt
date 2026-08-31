package settings

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.shared.utils.Logger
import no.njoh.pulseengine.core.window.ScreenMode

/**
 * Everything the graphics-options menu needs to persist [GameSettings], and nothing else.
 *
 * THE ONE RULE: a settings file must never be able to stop the game booting. It is written by
 * a running game a player can kill at any moment, it lives in a directory a person can open
 * and hand-edit, and it is read before anything is on screen — so every failure path in
 * [EngineSettingsStore] has to end at [GameSettings.DEFAULT] and a WARN log, never at an
 * exception reaching the caller. This mirrors `score.ScoreStore`'s contract for the same
 * reason: both files live beside each other in `engine.config.saveDirectory` and both are
 * read on a path that must not be blockable by a corrupt or hand-edited file.
 *
 * Deliberately narrow, and tested through a fake (see `SettingsStoreTest`) rather than
 * against a booted engine, exactly as `score.ScoreStore` is.
 */
interface SettingsStore
{
    /** Never throws. A missing file, a corrupt file, or any other read failure all resolve to
     * [GameSettings.DEFAULT]. The result is always [GameSettings.clamped] already applied — a
     * hand-edited file is the normal case here, not the exception. */
    fun load(): GameSettings

    /** Never throws. A failed write is logged and dropped — a player quitting mid-save must
     * see the game close, not a crash. */
    fun save(settings: GameSettings)
}

/**
 * The real one, wrapping `engine.data` exactly as `score.EngineScoreStore` does —
 * `saveObject`/`loadObject` are verified (see `score/ScoreStore.kt`'s class doc, checked
 * against `pulse-engine-0.13.0.jar`'s own bytecode) to never throw: both wrap their whole
 * body in `runCatching`, log at ERROR internally on failure, and return `false`/`null`
 * rather than propagating. `engine.data.saveObject`/`loadObject` resolve against
 * `engine.config.saveDirectory` (`~/EnPustTil/` on this machine), which already holds
 * `scoreboard.json`, its backups and `logs/` — this file lands beside them.
 *
 * **Deliberate deviation from the original spec, recorded here because a future reader
 * comparing this file against the scoreboard's will otherwise assume the omission is a bug.**
 * The spec called for routing [save] through `score.AtomicFileSwap.promoteAtomically`, the
 * way the scoreboard writes. This class uses the plain `saveObject` instead. The scoreboard's
 * atomic promote-via-temp-file exists because a lost score is a player's lost run and cannot
 * be recreated; a lost graphics setting costs one re-selection from a menu the next time it
 * is opened. Adding a second consumer to the atomic-swap path would couple settings — a
 * cosmetic, freely-re-enterable value — to the exact machinery that exists to protect
 * something irreplaceable, for no gain in return.
 *
 * Also deliberately NOT a `.cfg`/`Properties` file: `application.cfg`'s own loader
 * (`ConfigurationImpl.loadConfigFile`, see this repo's CLAUDE.md) silently drops a
 * hash-ordered subset of *other* keys the moment one value overflows `Int`, with no error
 * surfaced anywhere. JSON via `engine.data` has no equivalent trap.
 */
class EngineSettingsStore(private val engine: PulseEngine) : SettingsStore
{
    override fun load(): GameSettings =
        try
        {
            (engine.data.loadObject<GameSettings>(FILE_NAME) ?: defaultForThisBoot()).clamped()
        }
        catch (e: Exception)
        {
            Logger.warn { "Could not read $FILE_NAME (${e.message}); using default settings" }
            // .clamped() HERE TOO, and it stopped being optional when defaultForThisBoot() started
            // deriving from live config. It was harmless while this returned GameSettings.DEFAULT,
            // which is valid by construction - but `frameCap` now comes from engine.config
            // .targetFps, and a machine running a value that is not on the FRAME_CAPS ladder (75,
            // say) would hand GraphicsApplier an unclamped record on the corrupt-file path only.
            // GameSettings' own contract is that a loaded value is always clamped; both exits of
            // this function have to honour it, not just the happy one.
            defaultForThisBoot().clamped()
        }

    /**
     * [GameSettings.DEFAULT] with `fullscreen`/`frameCap` overridden from what the engine
     * ACTUALLY booted with, rather than the compiled booth literals baked into `DEFAULT`
     * (`fullscreen = true`, `frameCap = 0`).
     *
     * WHY: on a machine with no `settings.json` yet — every fresh clone, and `./gradlew run`
     * in particular — [load] used to hand back `GameSettings.DEFAULT` untouched, and
     * `GraphicsApplier.apply` pushes every field of whatever it is given onto the running
     * engine unconditionally. That meant `apply` called `updateScreenMode(FULLSCREEN)`,
     * silently overriding `application-dev.cfg`'s `screenMode = WINDOWED` — breaking EVERY
     * capture procedure in this repo's CLAUDE.md, which assumes a windowed dev build — and set
     * `targetFps = 0` (uncapped), overriding `application.cfg`'s deliberate `targetFps = 60`
     * on the very branch whose goal was hitting that cap. Both cfg files are already fully
     * resolved by the time `createGame` runs `settingsStore.load()` (the engine reads them
     * before `onCreate`), so `engine.window.screenMode` and `engine.config.targetFps` are
     * exactly the values `application.cfg`/`application-dev.cfg` chose — reading them back
     * here, instead of typing the booth's own literals a second time, is what keeps a missing
     * settings file inert rather than an accidental override of the cfg split.
     *
     * A REAL saved `settings.json` is untouched by this — it always wins outright, this
     * function only ever backstops the case where there is nothing on disk to read at all
     * (missing file or a corrupt one, the two branches [load] calls it from).
     */
    private fun defaultForThisBoot(): GameSettings = GameSettings.DEFAULT.copy(
        fullscreen = engine.window.screenMode == ScreenMode.FULLSCREEN,
        frameCap = engine.config.targetFps
    )

    override fun save(settings: GameSettings)
    {
        try
        {
            engine.data.saveObject(settings, FILE_NAME)
        }
        catch (e: Exception)
        {
            // saveObject itself never throws (verified against the jar's bytecode - see class
            // doc) - this catch exists purely as a second line of defence against whatever the
            // engine's own internals might do in a future version, so a settings write can
            // never take the boot or the running game down with it.
            Logger.warn { "Could not save $FILE_NAME (${e.message}); setting change was lost" }
        }
    }

    companion object
    {
        private const val FILE_NAME = "settings.json"
    }
}
