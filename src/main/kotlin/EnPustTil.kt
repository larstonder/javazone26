import booth.BoothLog
import booth.CallbackGuard
import booth.CallbackSites
import dive.DiveInput
import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.PulseEngineGame
import no.njoh.pulseengine.core.asset.types.Font
import no.njoh.pulseengine.core.graphics.api.Multisampling
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.input.Gamepad
import no.njoh.pulseengine.core.input.GamepadAxis
import no.njoh.pulseengine.core.input.GamepadButton
import no.njoh.pulseengine.core.input.Key
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.core.shared.utils.LogLevel
import no.njoh.pulseengine.core.shared.utils.Logger
import no.njoh.pulseengine.modules.lighting.global.GlobalIlluminationSystem
import no.njoh.pulseengine.modules.lighting.shared.NormalMapRenderer
import no.njoh.pulseengine.modules.metrics.MetricViewer
import org.lwjgl.glfw.GLFW
import render.Backdrop
import render.BoothStatus
import render.CameraInvariants
import render.CameraRig
import render.ControlHints
import render.DiveCamera
import render.DiveLighting
import render.DiveRenderer
import render.DiverSprite
import render.Hud
import render.IridescenceRenderer
import render.LifecycleInputEdges
import render.LightEmitter
import render.MoteSprite
import render.Motes
import render.OpaqueWaterEffect
import render.OxygenSprite
import render.PearlNormalMap
import render.RockFace
import render.RunLifecycle
import render.RunLifecycleState
import render.SandBank
import render.Sky
import render.VentLabel
import render.WaterRenderer
import render.WaterSurface
import render.drawTextWithOutline
import render.fillRect
import render.selectGameplayPad
import score.ScoreRepository
import kotlin.math.ceil

fun main()
{
    // BEFORE PulseEngine.run, deliberately: a failure during engine start-up — a missing
    // asset, a GL context the booth GPU will not give us — happens inside run() and would
    // otherwise be logged to a stdout nobody can read. See BoothLog's class doc for why the
    // engine cannot write this file itself (LogTarget has no FILE entry and cannot get one).
    //
    // GAME_NAME is duplicated from application.cfg rather than read from it: the config is
    // parsed by the engine, inside run(), which is after this point.
    val log = BoothLog.install(
        dir = BoothLog.logDirectory(System.getProperty("user.home") ?: ".", GAME_NAME),
        startedAtMillis = System.currentTimeMillis()
    )
    println(if (log != null) "Booth log: ${log.absolutePath}" else "Booth log: unavailable (continuing without one)")

    PulseEngine.run<EnPustTil>()
}

/** Must match `gameName` in application.cfg — see [main] for why it cannot be read from there. */
const val GAME_NAME = "EnPustTil"

/**
 * Parses a "dailySeed" value already known to be a `String` — i.e. application.cfg's
 * loader did NOT type-coerce it into an `Int` or a `Float` (see [resolveDailySeed], the
 * actual call site, for why a String is only ONE of three shapes this value can arrive
 * in). Pure and engine-free specifically so it is unit testable without standing up a
 * PulseEngine instance — see EnPustTilSeedTest.
 *
 * Returns [fallback] (the compile-time default, `EnPustTil`'s private `DAILY_SEED` —
 * not linked, that companion is `private` and a KDoc link to it cannot resolve from a
 * top-level function) for both cases a technician's text-file edit can produce: [raw] is
 * null (key absent — day one, before anyone has touched the file) or [raw] is present but
 * not a valid Long (a typo must never crash the booth machine; it just silently reuses
 * day one's seed instead).
 */
fun parseDailySeed(raw: String?, fallback: Long): Long = raw?.toLongOrNull() ?: fallback

/**
 * Resolves "dailySeed" across every shape `ConfigurationImpl`'s own loader can store ONE
 * text value as. Reading only [no.njoh.pulseengine.core.config.Configuration.getString]
 * — which this code did until this function existed — is why the day-two procedure never
 * actually worked: `ConfigurationImpl.loadConfigFile` type-coerces every property value
 * BEFORE storing it (decompiled: all-digits parses as `Integer`, digits-with-one-dot as
 * `Float`, anything else stays `String`), and `getString` returns null for anything not
 * literally stored as a `String`.
 *
 * EMPIRICALLY VERIFIED against the real `ConfigurationImpl` (not reasoned from the
 * decompiled loader alone — see task-6-7-report.md for the harness and full output, now
 * updated twice: a first empirical pass got the ABORT mechanism half right, and a second
 * review measurement corrected it — read that report's "round 2" section, not just its
 * first pass, before changing this doc again):
 *
 *  - **Every real seed used at this booth so far (`20260902`, `20260903` — all digits)
 *    stores as `Integer`.** `getString` returns null for it; this is the entire bug this
 *    function exists to fix. [rawInt] is checked FIRST for exactly this reason: it is the
 *    shape a legitimate seed actually arrives in, not an edge case.
 *  - **A seed above `2147483647` (`Int.MAX_VALUE`) does NOT arrive as a `String`.**
 *    `Integer.parseInt` throws inside `ConfigurationImpl.loadConfigFile`. The throw is
 *    SILENT, not logged: `ConfigurationInternal.init()` (which the engine actually calls
 *    for `application.cfg`, decompiled) wraps the call in `runCatching { ... }` and stores
 *    the `Result` to a local that is NEVER READ — bytecode: `astore_2`, nothing after it.
 *    (`ConfigurationImpl.load(path)`, a DIFFERENT public method, DOES log "Failed to load
 *    configuration" at ERROR — but the engine never calls that one for this file, only
 *    `init()`'s silent `runCatching`, so that log line is not a safety net here.)
 *  - **The throw aborts parsing partway through, and WHICH keys survive is HASH ORDER,
 *    NOT FILE ORDER, and therefore unpredictable.** `loadConfigFile` iterates
 *    `Properties.entrySet()`, a `Hashtable`; every entry already reached before the throw
 *    is already committed to `this.properties`, and everything the iteration had not yet
 *    reached is lost — regardless of where either sits in the FILE. Measured against the
 *    real shipping `application.cfg` with `dailySeed = 99999999999`: `gameName`,
 *    `targetFps`, `screenMode`, `logLevel`, `kickButton`, `bleedButton` and `restartButton`
 *    ALL SURVIVE (the cabinet does NOT come up windowed, contrary to an earlier version of
 *    this doc) — only `dailySeed`, `restartButtonAlt` and `stickDeadzone` are lost. Making
 *    `targetFps` the bad value instead loses a different subset. DO NOT name which keys
 *    survive an over-range value as a general rule: it depends on the exact key set in the
 *    file at the time and changes if a key is added or removed. The one thing this
 *    function's caller CAN do generically is notice `gameName` came back wrong — see
 *    [configFileHealthWarning] — but that check is a SPECULATIVE bonus signal, not a
 *    reliable one: in BOTH measurements above, `gameName` survived, so
 *    [configFileHealthWarning] would have stayed silent for the exact failures this list
 *    documents. Its doc says so explicitly rather than letting its silence be trusted more
 *    than it deserves.
 *  - **The boundary is EXACTLY `2147483647`, verified both sides**: `dailySeed =
 *    2147483647` loads fine (an ordinary `Integer`); `dailySeed = 2147483648` throws. "Ten
 *    digits or fewer" (an earlier version of this doc and of application.cfg) is
 *    ACTIONABLY WRONG on the boundary — `2147483648` is ten digits and throws, and
 *    `2147483647` is also ten digits and is fine — the only correct statement is the
 *    numeric ceiling itself.
 *  - **Text containing a `.` (a stray decimal point) stores as `Float`.** There is no
 *    meaningful integer to recover from a fractional value (every real seed here is a
 *    bare integer, so a decimal point is definitionally a typo), and rounding or
 *    truncating it would silently substitute a seed nobody chose. It degrades exactly
 *    like a non-numeric typo: [fallback], not a guess.
 *  - **Any other non-numeric typo stores as `String`** and is handled by [parseDailySeed]
 *    exactly as before.
 *  - **An absent key** returns null from every getter and falls back exactly as before.
 *
 * See [dailySeedConfigWarning] for making the decimal-point and non-numeric-typo cases
 * VISIBLE — this function only degrades safely, it does not warn.
 *
 * [rawInt] and [rawString] rather than a `Configuration` reference, so this stays pure
 * and testable without booting the engine — same reasoning as [parseGamepadButton].
 */
fun resolveDailySeed(rawInt: Int?, rawString: String?, fallback: Long): Long =
    rawInt?.toLong() ?: parseDailySeed(rawString, fallback)

/**
 * A warning message if `dailySeed` was present in application.cfg, in ANY of the three
 * shapes its loader can store it as, but did not resolve to a usable value — or null if
 * the key is either absent or genuinely valid.
 *
 * GENERALISES what an earlier version of this fix only caught for ONE of two failure
 * shapes: it warned on a stray-decimal `Float` (`dailySeed = 2026.0903`) but said nothing
 * for a non-numeric typo stored as `String` (`dailySeed = 2O260903`, a letter for a
 * digit) — which is the MORE LIKELY typo of the two, and produced no signal at all. Both
 * are now covered by the same check: present, and [resolveDailySeed] could not use it.
 * [rawInt] present is never a failure — an `Int` from this loader is always a valid seed —
 * so it is not part of this function's signature at all; the caller simply does not call
 * this when `rawInt != null`.
 */
fun dailySeedConfigWarning(rawString: String?, rawFloat: Float?): String?
{
    if (rawString != null && rawString.toLongOrNull() == null)
        return "application.cfg: dailySeed = \"$rawString\" is not a whole number - using the active seed instead. See CLAUDE.md's day-two paragraph."
    if (rawFloat != null)
        return "application.cfg: dailySeed = $rawFloat has a decimal point - seeds must be a whole number - using the active seed instead."
    return null
}

/**
 * A WARN-worthy message if application.cfg's load looks like it silently aborted partway
 * through — see [resolveDailySeed]'s doc for the mechanism this detects the SYMPTOM of,
 * not the cause: an over-range numeric value (`dailySeed` above `2147483647`, or any other
 * key with the same shape) throws while `ConfigurationImpl.loadConfigFile` iterates
 * `Properties.entrySet()` in HASH order, and the throw is swallowed by
 * `ConfigurationInternal.init()`'s `runCatching` with its `Result` never inspected — so
 * nothing else in the process ever finds out on its own.
 *
 * [gameName] should be `engine.config.getString("gameName")`. It is the one key in
 * application.cfg that is (a) always present and uncommented in the shipped file, (b)
 * NEVER subject to the numeric coercion that causes the abort — a game name is never
 * all-digits, so it can never itself be the value that throws — and (c) has one, known,
 * unchanging correct value ([GAME_NAME]) rather than a technician-editable one. If a load
 * silently aborted partway through, `gameName`'s hash bucket has a real (if not
 * guaranteed — hash order, not "last") chance of being one of the entries never reached,
 * and reading anything other than [GAME_NAME] back is a signal a technician would
 * otherwise never get: WHICH other keys were lost is unpredictable and this function does
 * not attempt to say — see [resolveDailySeed]'s doc for why naming them would immediately
 * go stale.
 *
 * THIS GUARD'S DETECTION IS SPECULATIVE, NOT RELIABLE — SAY SO RATHER THAN LET ITS SILENCE
 * BE TRUSTED MORE THAN IT DESERVES. It did NOT fire for either failure this task actually
 * measured: `resolveDailySeed`'s own table shows `dailySeed = 99999999999` and
 * `targetFps = 99999999999` BOTH leaving `gameName` intact (`gameName`'s hash bucket
 * happened to be reached before the throw in both runs). This function only helps on the
 * runs where `gameName`'s bucket falls AFTER the abort, which has never been observed
 * with this exact key set — so its silence means "gameName survived," never "the file
 * loaded completely," and must not be read as the latter. A reliable version would need
 * to compare against a known-complete key list, which drifts every time a key is added —
 * not a trade worth making for a guard this cheap.
 *
 * IT ALSO HAS A FALSE-POSITIVE PATH THAT ISN'T THE FAILURE IT EXISTS FOR: a technician
 * editing or deleting the `gameName` line itself — for any reason, including a harmless
 * one — makes this fire with nothing actually truncated. See the `# do not edit` note
 * application.cfg carries beside `gameName` for exactly this reason.
 *
 * Cheaper than a documented rule a technician has to remember and re-derive by hand: this
 * is three lines, runs once at startup, and turns "read CLAUDE.md's day-two paragraph
 * correctly" into "read one WARN line in the booth log" — ON THE RUNS WHERE IT FIRES AT
 * ALL. It is a bonus signal on top of the documented ceiling, not a replacement for it.
 */
fun configFileHealthWarning(gameName: String?): String? =
    if (gameName != GAME_NAME)
        "application.cfg: gameName read back as \"$gameName\", expected \"$GAME_NAME\" - the config file may have failed to load completely (a numeric value above 2147483647 anywhere in it throws mid-parse and silently drops an unpredictable subset of the file's OTHER keys - see resolveDailySeed's doc). Check every key in application.cfg, not just the one you edited."
    else null

/**
 * Parses a `GamepadButton` name read from application.cfg (kickButton / bleedButton /
 * restartButton / restartButtonAlt).
 *
 * WHY THIS IS A CONFIG KEY AT ALL. The booth hardware is a generic USB arcade encoder that
 * has never been enumerated on Windows, and its button codes are a guess. Before this, a
 * wrong guess meant rebuilding the game on a machine with the Kotlin toolchain, at the
 * venue, on setup day. application.cfg ships inside the .exe and opens in Notepad.
 *
 * Falls back to [fallback] for every failure a text-file edit can produce - key absent,
 * empty, misspelled, a button name from a different controller vocabulary - for exactly
 * the reason [parseDailySeed] does: a typo must never crash the booth machine, and must
 * never leave kick unbound in front of a queue. Pure and engine-free apart from the enum
 * itself, so it is unit testable without standing up a PulseEngine.
 *
 * `LAST` is a real entry (verified from the jar) and is accepted here like any other name
 * — it is not special-cased out. It is a GLFW alias for `DPAD_LEFT` (the two share the same
 * underlying code, same as `GLFW_GAMEPAD_BUTTON_LAST`), so setting a key to `LAST` would
 * bind it to whatever DPAD_LEFT reads: a working, if confusingly-named, choice. Excluding
 * it would need a special case for a value nobody at a booth is realistically going to
 * type, so application.cfg's documented list simply does not mention it — the parser stays
 * generic over every enum entry rather than growing a carve-out for one alias.
 */
fun parseGamepadButton(raw: String?, fallback: GamepadButton): GamepadButton
{
    val name = raw?.trim()?.uppercase() ?: return fallback
    return GamepadButton.entries.firstOrNull { it.name == name } ?: fallback
}

/**
 * A configured button's name as it should appear on screen. Thin, but it is the seam that
 * keeps [render.ControlHints] engine-free: that object takes `String` labels and never a
 * `GamepadButton`, so it needs no pulseengine import and can be tested with no GL context.
 */
fun gamepadButtonLabel(button: GamepadButton): String = ControlHints.labelFor(button.name)

/**
 * A warning message for `createGame` to log if the key was present in application.cfg, in
 * ANY of the three shapes its loader can store a value as, but did not resolve to a
 * genuinely recognised button — or null if the key is either absent entirely or a
 * genuinely recognised name.
 *
 * WHY THIS EXISTS. [parseGamepadButton] falls back to [default] for every unparseable
 * [rawString] — correctly, a typo must never crash the booth machine — but that means a
 * typo and a deliberate "use the compiled default" edit are otherwise
 * INDISTINGUISHABLE: both produce [resolved] == [default] and, before this, nothing
 * printed anywhere a technician would see it. A typo is left silently unbound in front of
 * a queue with the reasonable conclusion "the config file doesn't work" — the precise
 * failure Task 7 exists to prevent, arriving through a different door.
 *
 * GENERALISED beyond the `String` shape: a version of this taking only [rawString]
 * checked nothing for `kickButton = 0` — an all-digit value coerces to `Integer`, so
 * `parseGamepadButton` was handed `getString(key) == null` and fell back with the
 * genuinely-absent case, silent. [rawInt]/[rawFloat] present is unconditionally a typo
 * (no legitimate `GamepadButton` name is purely numeric or contains a decimal point), so
 * their presence alone — with no name-equality escape hatch, since there is no name to
 * compare — is enough to warn.
 *
 * The [rawString] heuristic: present, resolved to [default], AND [rawString] (trimmed,
 * case-insensitively) is NOT [default]'s own name — i.e. a technician who explicitly
 * writes the default's name back gets no warning (a real, deliberate edit, not a
 * silently-swallowed typo), but anything else that fell back does. Not perfect — it
 * cannot tell "typed the default on purpose" apart from "typo that happens to coincide
 * with the default's name" — but it is the caller-side comparison this project's
 * `parseDailySeed`-family functions already use in place of threading a result type
 * through every parser.
 */
fun gamepadButtonConfigWarning(key: String, rawString: String?, rawInt: Int?, rawFloat: Float?, resolved: GamepadButton, default: GamepadButton): String?
{
    if (rawInt != null) return "application.cfg: $key = $rawInt is not a recognised GamepadButton name - using the compiled default ($default). See application.cfg's BUTTON MAP comment for valid names."
    if (rawFloat != null) return "application.cfg: $key = $rawFloat is not a recognised GamepadButton name - using the compiled default ($default). See application.cfg's BUTTON MAP comment for valid names."
    if (rawString == null || resolved != default) return null
    if (rawString.trim().equals(default.name, ignoreCase = true)) return null
    return "application.cfg: $key = \"$rawString\" is not a recognised GamepadButton name - using the compiled default ($default). See application.cfg's BUTTON MAP comment for valid names."
}

/**
 * Warnings for configured-button collisions that are ALWAYS a mistake, regardless of
 * `RunLifecycle`'s state — currently just `kickButton == bleedButton` (by
 * [GamepadButton.code]). One warning string per problem, ready to log; empty when nothing
 * collides.
 *
 * WHY ONLY THIS ONE PAIR, when application.cfg configures four buttons and there are six
 * possible pairs among them. This function used to warn on ALL SIX — "any two of the four
 * collide" — until that was checked against the compiled defaults themselves and found to
 * be self-contradicting: `EnPustTil`'s `DEFAULT_KICK_BUTTON` and
 * `DEFAULT_RESTART_BUTTON_ALT` are BOTH `A`, on purpose (see the doc above those
 * constants — A is deliberately kept as a secondary restart button in case START is
 * unmapped; not linked here — `EnPustTil`'s companion object is `private`, so a KDoc link
 * to a member of it cannot resolve from a top-level function outside the class). A
 * version that warned on every pairwise collision would log a WARNING ON EVERY SINGLE
 * UNTOUCHED BOOTH BOOT, for a "collision" that is the shipped, tested, documented design —
 * exactly the kind of cried-wolf noise that makes a technician start ignoring the booth
 * log.
 *
 * The other five pairs are safe for reasons specific to THIS codebase, verified against
 * source rather than assumed:
 *  - `kickButton`/`bleedButton` colliding with `restartButton`/`restartButtonAlt`, in
 *    EITHER direction, is harmless: `RunLifecycle.update`'s `PLAYING` branch (`:252-256`)
 *    consults only `runOver` and `pauseEdge` — `pressedEdge` (the restart/confirm signal
 *    kick/bleed would collide with) is not read AT ALL while a run is in progress, and
 *    `readInput`'s kick/bleed reads only matter while `DiveSim` is ticking, i.e. while
 *    `PLAYING`. The two signals are only ever "live" in disjoint states, so one button
 *    driving both never produces a conflicting read.
 *  - `restartButton == restartButtonAlt` is exactly the case
 *    `LifecycleInputEdges.offer`'s duplicate-offer guard exists for (see its KDoc), and a
 *    reasonable technician choice in its own right (force both onto a button already
 *    known to work). Warning about it would be warning about a supported configuration.
 *
 * `kickButton == bleedButton` has no such escape: both buttons are read on the SAME state
 * (`PLAYING`) for two DIFFERENT, simultaneously-meaningful actions (kick propels toward
 * the surface, bleed releases air) — see `dive/Tuning.kt` and the design spec §4 for why
 * they are not interchangeable. Colliding them is never safe and is exactly the plausible
 * booth copy-paste (four adjacent lines, one mis-edited) this function exists to catch.
 *
 * Compared on [GamepadButton.code], not name or `.ordinal`, so an alias does not evade
 * the check: `GamepadButton` has entries that name-compare as different buttons but are
 * the SAME physical input on this hardware (`A`/`CROSS`, `X`/`SQUARE`, `DPAD_LEFT`/`LAST`,
 * ... — verified from the jar's static initialiser, each alias constructed with the
 * original's `code`). `kickButton = A` / `bleedButton = CROSS` is exactly as broken as
 * `kickButton = A` / `bleedButton = A`, and only the `.code` comparison catches both.
 *
 * Pure, so it is unit-testable without an engine. Called ONCE at startup (`createGame`),
 * not per frame, so the small `List`/string-building cost here is unlike the constraints
 * on `readInput`'s allocation-free path.
 */
fun gamepadButtonCollisionWarnings(
    kickButton: GamepadButton,
    bleedButton: GamepadButton,
    restartButton: GamepadButton,
    restartButtonAlt: GamepadButton
): List<String>
{
    // restartButton/restartButtonAlt are read but not compared — see the class doc for
    // why every collision involving them is safe by this codebase's own design and would
    // be a false alarm.
    if (kickButton.code == bleedButton.code)
        return listOf("application.cfg: kickButton and bleedButton both resolve to the same physical button ($kickButton, code ${kickButton.code}) - kick and bleed would always fire together. Check for a copy-paste.")
    return emptyList()
}

/**
 * Parses the `stickDeadzone` override ALREADY KNOWN TO BE a `Float` — see
 * [resolveDeadzone] for why a `Float` is only one of two shapes this value can arrive in.
 * Clamped to 0..0.9: 1.0 or above makes the stick permanently dead (the diver could never
 * be steered), and a negative value makes a resting stick read as full deflection (the
 * diver swims on its own, forever, on the attract screen). Both are worse than any
 * legitimate value.
 */
fun parseDeadzone(raw: Float?, fallback: Float): Float = (raw ?: fallback).coerceIn(0f, 0.9f)

/**
 * Resolves `stickDeadzone` across both shapes application.cfg's loader can store it as —
 * the SAME coercion mechanism [resolveDailySeed] documents at length, hitting a second
 * key in this exact `createGame` block. A technician disabling the deadzone writes `0`
 * (the natural way to write "off"), which is all-digits and therefore stores as `Integer`,
 * not `Float` — so a version of this reading only [no.njoh.pulseengine.core.config
 * .Configuration.getFloat] would silently ignore `stickDeadzone = 0` and keep
 * `EnPustTil`'s compiled `DEFAULT_STICK_DEADZONE` (not linked — that companion is
 * `private`, so a KDoc link to it cannot resolve from a top-level function). [rawInt] is
 * checked first so that value is reachable; a value written WITH a decimal point (`0.2`,
 * `0.35`, ...) still arrives as `Float` and is unaffected.
 */
fun resolveDeadzone(rawInt: Int?, rawFloat: Float?, fallback: Float): Float =
    parseDeadzone(rawInt?.toFloat() ?: rawFloat, fallback)

/**
 * Parses `EPT_DEPTH` — the DEV-ONLY depth pin that puts the diver at a fixed depth so the
 * attract screen becomes a deep-water test rig.
 *
 * ## WHY IT EXISTS, WHICH IS A PROCESS PROBLEM AND NOT A FEATURE
 *
 * There is no way to put the diver at 140 m without playing the cabinet, and over 2026-08-12
 * and -13 that blocked EVERY visual claim about the deep: the pearls' light response, the
 * torch's reach, the mote field's glow. Three checks in a row had to be handed back to the
 * owner because the only instrument for them was a human at a joystick. The lighting work
 * planned in `docs/superpowers/plans/2026-08-13-deep-water-lighting.md` is a sequence of
 * one-dial changes each decided by a capture, and it is unrunnable without this.
 *
 * `EPT_WAVE_PHASE` and `EPT_MOTE_PHASE` are the precedent — one `getenv` at startup, unset and
 * therefore inert at the booth. (There was a third, `EPT_SHAFT_PHASE`, pinning the god rays; it
 * went with them on 2026-08-17.)
 *
 * ## IT MOVES THE DIVER, IT DOES NOT CHANGE THE RULES
 *
 * Applied through [DiveSim.debugSetDepth], the same `internal` hook the simulation tests use.
 * Nothing in `dive/` learns that it exists: no rule is suspended, no timer is stopped, air
 * burns normally the moment a run starts. In IDLE — the attract screen — the sim is not
 * ticked at all (`RunLifecycle.simulationAdvances` is false), so a diver placed there simply
 * stays, which is what makes it a stable rig rather than a brief glimpse.
 *
 * ## PARSING
 *
 * Returns null for absent, blank or unparseable, so a typo is inert rather than a crash or a
 * silent 0. A valid number is COERCED into `0..MAX_DEPTH` rather than rejected: the useful
 * values are at the bottom of the column and "140" and "160" and "200" are all obviously
 * asking for the same thing, while a depth outside the column would put the camera somewhere
 * the game cannot otherwise reach and produce measurements of nothing.
 */
const val DEPTH_PIN_ENV = "EPT_DEPTH"

/**
 * A deliberate boot-failure rehearsal switch, in the same family as EPT_DEV/EPT_DEPTH/
 * EPT_SCREENSHOT/EPT_EDITOR (see CLAUDE.md for all five). Named here, top-level beside
 * [DEPTH_PIN_ENV] and for the identical reason ([EnPustTil]'s companion is private), rather
 * than left as a bare literal at its one call site — see [EnPustTil.createGame] for where
 * it is read and why exactly that point in the function.
 */
const val FAIL_BOOT_ENV = "EPT_FAIL_BOOT"

/** @see parseDepthPin — top-level beside it, because [EnPustTil]'s companion is private. */
fun parseDepthPin(raw: String?, maxDepth: Float): Float?
{
    val value = raw?.trim()?.toFloatOrNull() ?: return null
    if (!value.isFinite()) return null
    return value.coerceIn(0f, maxDepth)
}

/**
 * What the engine's default font can actually draw — which is a good deal narrower than
 * "anything you can type into a string literal", and fails silently when you exceed it.
 *
 * ESTABLISHED BY DECOMPILING pulse-engine-0.13.0.jar, not by guessing:
 *
 *   Font's `<clinit>`  DEFAULT = Font("/pulseengine/assets/FiraSans-Regular.ttf", "default_font", 80f)
 *   Font.load()        stbtt_BakeFontBitmap(ttf, 80f, bitmap, 1024, 1024, FIRST_CHAR_CODE,
 *                                           STBTTBakedChar.malloc(MAX_CHAR_COUNT))
 *   Font's constants   FIRST_CHAR_CODE = 32 (private), MAX_CHAR_COUNT = 256 (public)
 *
 * `stbtt_BakeFontBitmap` bakes a CONTIGUOUS run of code points, so the atlas holds exactly
 * U+0020..U+011F: printable ASCII, the whole Latin-1 Supplement, and the first half of
 * Latin Extended-A. The TTF itself has thousands more glyphs — FiraSans certainly contains
 * an em dash — but they are never baked into the atlas, so nothing downstream can reach
 * them. The limit is the atlas, not the typeface.
 *
 * `TextRenderer`'s glyph loop then does, per code point (bytecode offsets 133..143 of its
 * text pass):
 *
 *     val i = codePoint - 32
 *     if (i < 0 || i >= 256) continue     // no glyph, NO ADVANCE, no exception, no log
 *
 * That `continue` skips the x-advance as well as the quad, which is exactly what
 * `runover-view.png` shows: `"RUN OVER — BANKED 0"` came out as `RUN OVER  BANKED 0`, the
 * two spaces that flanked the dash and nothing between them. The dash contributed literally
 * zero width. Nothing anywhere reports it — on a booth machine with no console attached,
 * the first anyone would know is a photograph of the cabinet.
 *
 * `É` (U+00C9 = 201) sits comfortably inside the range, which is why `"ÉN PUST TIL"` was
 * fine and made this look like a mystery rather than a bounds check.
 *
 * PRACTICAL RULE FOR EVERY DRAWN STRING IN THIS FILE:
 *   - Norwegian is safe. Æ Ø Å æ ø å are U+00C5..U+00F8, inside the atlas.
 *   - General punctuation is NOT. Em dash U+2014, en dash U+2013, curly quotes
 *     U+2018/2019/201C/201D, ellipsis U+2026 and bullet U+2022 all live above U+2000 and
 *     will vanish. These are precisely the characters a word processor, a chat client or
 *     an editor's smart-quotes feature substitutes in for you.
 * [ScreenText.all] enumerates every string this file draws and AttractScreenTest asserts
 * every one of them is drawable, so reintroducing an em dash fails the build rather than
 * shipping to the booth.
 */
object DefaultFont
{
    /** `Font.FIRST_CHAR_CODE`. Private on the engine class, so it has to be restated here. */
    const val FIRST_CODE_POINT = 32

    /**
     * `Font.MAX_CHAR_COUNT`. Referenced rather than hardcoded so that an engine upgrade
     * which enlarges the atlas relaxes this rule automatically, instead of leaving us
     * pessimistic against a limit that no longer exists.
     */
    const val CODE_POINT_COUNT = Font.MAX_CHAR_COUNT

    /**
     * Newline is consumed by `TextRenderer` BEFORE the range check (it records a line
     * break and skips the glyph path entirely), so it is drawable in the only sense that
     * matters here: it does not silently disappear.
     */
    private const val NEWLINE = 10

    /** Whether the default font's baked atlas has a glyph slot for [codePoint]. */
    fun canDraw(codePoint: Int) = codePoint == NEWLINE ||
        (codePoint >= FIRST_CODE_POINT && codePoint < FIRST_CODE_POINT + CODE_POINT_COUNT)

    /**
     * The code points of [text] that would render as nothing at all. Empty means the
     * string is safe to draw. Walks code points, not chars, because `TextRenderer` does
     * (`Font.CodePoint.of` recombines surrogate pairs first) — so an emoji is reported
     * once, as the supplementary code point that actually gets rejected.
     */
    fun undrawableCodePointsIn(text: String): List<Int> = text.codePoints().toArray().filterNot(::canDraw)
}

/**
 * Every string this file draws, in one place, so [DefaultFont] can be asserted against all
 * of them at once (AttractScreenTest) rather than trusting a reviewer to spot a character
 * that renders as nothing.
 *
 * Engine-free and pure for the same reason [parseDailySeed] is: it can be tested without
 * standing up a PulseEngine.
 */
object ScreenText
{
    /**
     * Replaces the em dash that silently vanished (see [DefaultFont]). A middle dot
     * (U+00B7 = 183) is inside the baked atlas, and unlike a hyphen it is unmistakably a
     * separator rather than a subtraction sign or a stray mark on a word.
     *
     * The double spaces are load-bearing, not sloppiness: at arcade viewing distance a
     * tight `A·B` reads as one smudged word, whereas a dot given a full space of air on
     * each side reads as a deliberate beat — which is the job the em dash was doing. The
     * dot is drawn through [render.drawTextWithOutline] like everything else on this
     * surface, so it keeps its black rim and does not disappear into bright Shallows water.
     */
    const val SEPARATOR = "  ·  "

    const val TITLE = "ÉN PUST TIL"
    const val LEADERBOARD_HEADING = "TODAY'S DIVERS"

    /**
     * The label written faintly across every oxygen vent — see [render.VentLabel] for where it
     * is drawn and why it is on the HUD surface rather than in the world.
     *
     * "O2", WITH AN ASCII DIGIT, AND NOT "O₂". U+2082 SUBSCRIPT TWO is far outside the default
     * font's baked U+0020..U+011F atlas, and a code point outside that atlas draws as nothing
     * at all — no glyph and no x-advance, silently (see [DefaultFont]). The typographically
     * correct form would therefore have shipped a vent labelled "O", with nothing anywhere
     * saying so. It is in [all] below so `AttractScreenTest` sweeps it, which is what turns a
     * later tidy-up to the subscript form into a failed build rather than a booth defect.
     */
    const val VENT_OXYGEN = "O2"

    // PRESS_START, PLAY_AGAIN and INITIALS_HELP used to live here as literals. They named
    // buttons that application.cfg can rebind, so the screen could lie; PLAY_AGAIN and
    // INITIALS_HELP also named a keyboard key AND a gamepad button in one breath, which is
    // half-irrelevant on every machine. They are now composed per-device by
    // render/ControlHints.kt and cached on EnPustTil. ControlHintsTest is their font-atlas
    // sweep — a composed string can never appear in ScreenText.all().

    // --- The pause / exit screen (Esc) -------------------------------------------------
    // Deliberately plain ASCII. Not because anything here would break the font atlas (see
    // [DefaultFont] — these are all well inside it, and AttractScreenTest proves it), but
    // because these lines name physical keys, and a key legend is the one place a
    // typographic flourish costs legibility for nothing.

    /** Heading when the screen was opened mid-run. */
    const val PAUSED_TITLE = "PAUSED"

    /**
     * Heading when the screen was opened from attract mode. Named for what it is — the
     * technician's way to close the cabinet — rather than "PAUSED", which would be a lie:
     * there is no run to pause, and a queue reading it over someone's shoulder would think
     * the machine had stopped working.
     */
    const val MENU_TITLE = "CABINET MENU"

    const val PAUSE_RESUME_HINT = "ESC to resume"
    const val MENU_RESUME_HINT = "ESC to go back"

    /** The exit affordance, on both variants. See RunLifecycle.EXIT_HOLD_SECONDS. */
    const val EXIT_HINT = "HOLD Q to exit"

    /** Dev overlay (EPT_DEV only) — see [EnPustTil.renderGamepadOverlay]. Still drawn text. */
    const val UNMAPPED_JOYSTICK_WARNING = "!! joystick present but NOT gamepad-mapped${SEPARATOR}invisible to this game !!"

    fun runOver(banked: Int) = "RUN OVER${SEPARATOR}BANKED $banked"

    fun newScore(banked: Int) = "NEW SCORE${SEPARATOR}BANKED $banked"

    /**
     * The three initials slots, with the one being edited bracketed so the cursor reads
     * without a caret asset. The un-edited slots are padded to the same width so the
     * letters do not shuffle sideways as the bracket moves between them.
     */
    fun initialsSlots(letters: String, slot: Int) =
        letters.mapIndexed { i, c -> if (i == slot) "[$c]" else " $c " }.joinToString(" ")

    // --- The pre-run briefing ----------------------------------------------------------
    // Controls plus the ONE rule the game does not otherwise teach. Air, the anglerfish and
    // the point-of-no-return are deliberately absent: everything on this screen costs reading
    // time in front of a queue, and those three teach themselves by happening.

    const val BRIEFING_TITLE = "HOW TO DIVE"

    /** The one thing a player must know that nothing else on screen ever says. */
    const val BRIEFING_RULE = "SURFACE TO BANK YOUR PEARLS"

    const val BRIEFING_VERB_SWIM = "swim"
    const val BRIEFING_VERB_KICK = "kick"
    const val BRIEFING_VERB_BLEED = "bleed pearls"

    /** Completes "PRESS <button>" — the button half comes from ControlHints.confirm. */
    const val BRIEFING_SKIP_SUFFIX = " TO DIVE NOW"

    fun briefingCountdown(seconds: Int) = "STARTING IN $seconds"

    /**
     * Every drawable string, with the interpolated ones instantiated at values that
     * exercise their widest form. Used only by the test; cheap enough not to warrant
     * hiding behind a flag.
     */
    fun all(): List<String> = listOf(
        SEPARATOR,
        TITLE,
        LEADERBOARD_HEADING,
        VENT_OXYGEN,
        UNMAPPED_JOYSTICK_WARNING,
        PAUSED_TITLE,
        MENU_TITLE,
        PAUSE_RESUME_HINT,
        MENU_RESUME_HINT,
        EXIT_HINT,
        runOver(0),
        runOver(99999),
        newScore(12345),
        initialsSlots("AAA", 0),
        initialsSlots("ØYA", 2),
        BRIEFING_TITLE,
        BRIEFING_RULE,
        BRIEFING_VERB_SWIM,
        BRIEFING_VERB_KICK,
        BRIEFING_VERB_BLEED,
        BRIEFING_SKIP_SUFFIX,
        briefingCountdown(5)
    )
}

/**
 * Pure, engine-free layout for the attract screen — extracted for the same reason
 * [render.Framing] and [render.DepthBlend] are: the interesting property is a RELATIONSHIP
 * between numbers ("the title clears the diver", "the leaderboard is centred under its own
 * heading"), and a relationship can be asserted without a GL context.
 *
 * WHY THIS EXISTS: the world keeps rendering behind the attract screen on purpose (see
 * [EnPustTil.drawIdleScreen]) — the queue watches live water, not a static image. That is a
 * good decision that had a bad consequence: the title was drawn at 0.44 of screen height,
 * and the diver is pinned by [render.Framing.DIVER_SCREEN_FRACTION] to 0.40 with a large
 * additive glow around it. `idle-view.png` shows the result — "ÉN PUST TIL" landed inside
 * the diver's halo with a pearl sitting in the bowl of the U, and the waterline (which is
 * also at 0.40 whenever the diver is at the surface) ran immediately above it, so the title
 * read as a caption pinned to a horizontal rule. It looked like a rendering fault, not like
 * layered art.
 *
 * The fix is compositional rather than cosmetic: leave the middle third of the screen to the
 * diver and the waterline, put the sign (title + call to action) in the dark water above
 * them, and put the leaderboard below. Everything stays a fraction of screen HEIGHT — never
 * a pixel count and never a fraction of width — because `engine.window.width/height` are
 * PHYSICAL framebuffer pixels and the booth display may be 16:9, 16:10 or 4K (see
 * [render.Framing]'s doc for the HiDPI bug this convention exists to prevent).
 *
 * VERTICAL ANCHORS ARE THE TOP OF THE TEXT BOX, not the baseline. Measured off the captures
 * at commit 44a3902: the clock is drawn at `y = h*0.02 + h*0.05` and its glyph tops land at
 * 0.0675h on a 1200px capture; the old title at `y = h*0.44` had its cap-tops at 0.451h.
 * Text grows DOWNWARD from these values, so a block occupies `y .. y + fontSize`.
 */
object AttractLayout
{
    /**
     * Half-height of the screen band the diver and its glow occupy, centred on
     * [render.Framing.DIVER_SCREEN_FRACTION]. The diver itself is only
     * `DIVER_HEIGHT_METRES / VISIBLE_DEPTH_METRES` = 0.10 of screen height (0.05 when this
     * was measured, before the diver was doubled), so this is mostly the light: measured off
     * `idle-view.png`, the blue halo is still clearly reading 0.14h above and below the diver
     * before it fades into the ambient gradient. Attract text must stay outside this band,
     * which is the whole point of the anchors below. Note the diver's own half-height is now
     * 0.05h against this 0.14h band, so the band is still the binding constraint — but by a
     * smaller margin than it was, and a third doubling would invert them.
     */
    const val DIVER_HALO_HALF_HEIGHT = 0.14f

    // --- The sign: title + call to action, in the dark water above the waterline --------
    // Sized up as well as moved. The old title was h*0.07 competing with a bright diver
    // directly behind it; with the halo out of the way it can afford to be a title.
    const val TITLE_Y = 0.085f
    const val TITLE_FONT = 0.09f

    const val PRESS_START_Y = 0.20f
    const val PRESS_START_FONT = 0.038f

    // --- The leaderboard: below the diver, the last thing on screen ---------------------
    const val HEADING_Y = 0.56f
    const val ROW_FONT = 0.028f

    /** Baseline-to-baseline spacing, as a multiple of [ROW_FONT]. */
    const val ROW_LINE_SPACING = 1.5f

    /**
     * Half the width of a leaderboard row, as a fraction of screen HEIGHT — height, so the
     * row keeps the same proportions relative to its own text (which is also height-derived)
     * on a 4:3 booth panel and on a 16:9 one alike. A width fraction would stretch the row
     * on a wide display while the glyphs inside it stayed the same size.
     *
     * The row is laid out symmetrically about the screen centre by construction: rank
     * LEFT-aligned at `centre - halfSpan`, initials CENTRED on `centre`, score RIGHT-aligned
     * at `centre + halfSpan`. So the block's outer edges are exactly `halfSpan` either side
     * of the same x the "TODAY'S DIVERS" heading is centred on, and no font metric is needed
     * to know that.
     *
     * That is what was wrong before: rank right-aligned at 0.42w, initials left-aligned at
     * 0.46w and score right-aligned at 0.58w put the row's ink between roughly 0.40w and
     * 0.58w — visual centre 0.49w, left of the heading's 0.50w — and left a 0.09w hole
     * between the initials and the score. Three independently chosen width fractions cannot
     * be balanced except by accident, and cannot stay balanced across aspect ratios at all.
     *
     * 0.14 is about 5 em at [ROW_FONT], which clears a five-digit score on the right and a
     * two-character rank on the left without the columns drifting apart.
     */
    const val ROW_HALF_SPAN = 0.14f

    /**
     * Rows shown on the attract-screen leaderboard. Lives here rather than in
     * [EnPustTil]'s private companion because it is half a layout decision — the board has
     * to end above the bottom of the screen, and only this object knows where its rows land.
     */
    const val LEADERBOARD_SIZE = 8

    /** Top of leaderboard row [index] (0-based), as a fraction of screen height. */
    fun rowY(index: Int) = HEADING_Y + ROW_FONT * ROW_LINE_SPACING * (index + 1)

    /** Where the lowest pixel of a full board lands — must stay on screen. */
    fun bottomOfBoard(rowCount: Int) = rowY(rowCount - 1) + ROW_FONT

    /** Total width of a leaderboard row, as a fraction of screen height. */
    fun rowWidth() = ROW_HALF_SPAN * 2f

    // --- The booth status line: bottom-left, small, findable but ignorable --------------
    // See render/BoothStatus.kt for what it says and why. Geometry lives here rather than
    // as inline magic numbers at the draw site, same as everything else on this screen —
    // this object's own doc says "WHERE things go is AttractLayout's problem".
    const val STATUS_FONT = 0.014f

    /**
     * Where the line sits, as a fraction of screen height. NOT flush with the bottom edge
     * (`1f`): `drawText`'s default `yOrigin` was not confirmed against a capture with a
     * non-null failure site, whose descenders (`onFixedUpdate`, `onUpdate`) would be the
     * first thing clipped by a hard bottom edge. The `* 1.5f` margin below buys half a
     * font-height of slack rather than one exactly, so a descender has somewhere to go.
     */
    const val STATUS_Y = 1f - STATUS_FONT * 1.5f

    // The three column anchors, in pixels, given the screen centre and height. Returned
    // from here rather than computed at the draw site so the balance they exist to
    // guarantee is assertable (AttractScreenTest) rather than merely intended: the rank's
    // LEFT edge and the score's RIGHT edge must be equidistant from the centre the heading
    // is drawn on. Each anchor states the xOrigin it must be drawn with, because the
    // symmetry is a property of the pair (anchor, alignment), not of the anchor alone.

    /** Left edge of the rank column. Draw with `xOrigin = 0`. */
    fun rankX(centreX: Float, screenHeight: Float) = centreX - screenHeight * ROW_HALF_SPAN

    /** Centre of the initials column. Draw with `xOrigin = 0.5`. */
    fun initialsX(centreX: Float) = centreX

    /** Right edge of the score column. Draw with `xOrigin = 1`. */
    fun scoreX(centreX: Float, screenHeight: Float) = centreX + screenHeight * ROW_HALF_SPAN
}

/**
 * Pure, engine-free layout for the pause / exit screen, extracted for the same reason
 * [AttractLayout] is: the properties worth asserting are RELATIONSHIPS between numbers, and a
 * relationship can be checked without a GL context (see PauseScreenTest).
 *
 * Unlike the attract screen this one does NOT have to dodge the diver and the waterline,
 * because it is drawn over a full-screen scrim (see [EnPustTil.drawPauseScreen]) which takes
 * the live world down to a dim backdrop. What it does have to guarantee is that its four
 * elements — heading, resume line, exit line, exit progress bar — never run into each other
 * at any screen size, and that the bar still fits across the squarest booth panel we might be
 * given. Everything is a fraction of screen HEIGHT for the usual reason: `engine.window
 * .width/height` are PHYSICAL framebuffer pixels and the booth display's resolution and
 * aspect ratio are both unknown until we plug it in (see [render.Framing]).
 *
 * As with [AttractLayout], vertical anchors are the TOP of the text box and text grows
 * downward, so a block occupies `y .. y + fontSize`.
 */
object PauseLayout
{
    /**
     * Displayed alpha of the full-screen scrim. Dark enough that the frozen world stops
     * competing with the text and the screen reads unmistakably as "the game is not running
     * right now" — but transparent enough that the diver, the pearls and the (stopped) HUD
     * clock are all still visible behind it, which is what tells a player their run is being
     * held rather than thrown away.
     *
     * Handed through `Hud.authoredAlphaFor` at the draw site, not here: this is the alpha we
     * want to SEE, and the HUD surface stores alpha squared (see Hud's measurement), so
     * authoring 0.72 directly would come out at about half that.
     */
    const val SCRIM_ALPHA = 0.72f

    const val TITLE_Y = 0.38f
    const val TITLE_FONT = 0.07f

    const val RESUME_Y = 0.50f
    const val EXIT_Y = 0.56f
    const val HINT_FONT = 0.03f

    /** Top of the exit-hold progress bar, and its thickness. */
    const val BAR_Y = 0.62f
    const val BAR_HEIGHT = 0.012f

    /**
     * Half the bar's length, as a fraction of screen HEIGHT — height, so the bar keeps the
     * same proportion to the "HOLD Q to exit" line above it on any aspect ratio, exactly as
     * [AttractLayout.ROW_HALF_SPAN] does for a leaderboard row.
     */
    const val BAR_HALF_SPAN = 0.15f

    /** Left edge of the bar, given the screen centre. Draw with the default `xOrigin = 0`. */
    fun barX(centreX: Float, screenHeight: Float) = centreX - screenHeight * BAR_HALF_SPAN

    /** Full length of the bar's track. */
    fun barTrackWidth(screenHeight: Float) = screenHeight * BAR_HALF_SPAN * 2f

    /**
     * Length of the filled part of the bar at [progress] (0..1).
     *
     * Clamps rather than trusting its input. `RunLifecycle.exitHoldProgress` already clamps,
     * so this is belt and braces — but an unclamped multiply is exactly how a rectangle ends
     * up drawn off the side of the screen, and on a booth cabinet with no console attached
     * that is a bug nobody can diagnose from a photograph.
     */
    fun barFillWidth(progress: Float, screenHeight: Float) =
        barTrackWidth(screenHeight) * progress.coerceIn(0f, 1f)
}

/**
 * Anchors for the pre-run briefing. Values only, no logic — the presentation twin of
 * [AttractLayout], and read the same way: every number is a fraction of screen HEIGHT (never
 * width, never a pixel count — the booth panel's aspect is not known in advance), and text
 * grows DOWNWARD from its anchor, so a block occupies `y .. y + fontSize`.
 *
 * WHY THIS SCREEN HAS A SCRIM AND THE ATTRACT SCREEN DOES NOT. [AttractLayout] reserves
 * `DIVER_SCREEN_FRACTION ± DIVER_HALO_HALF_HEIGHT` for the diver and lays its four elements
 * around that band. The briefing has seven and does not fit around it, so it darkens the
 * world instead of trying to. The scrim is LIGHTER than [PauseLayout.SCRIM_ALPHA] on purpose:
 * the two screens already differ in heading and content, and differing in weight as well is
 * what stops a player reading "the machine is waiting for me" as "the machine is stopped".
 *
 * THE SCRIM DOES NOT MOVE THE DIVER. An earlier version of this doc argued that darkening the
 * world "frees the full screen height" for text, as if a scrim were a substitute for the halo
 * carve-out [AttractLayout] does. It is not: a scrim dims what is drawn, it does not stop the
 * diver being drawn, and at every run start he sits exactly where he always does, lit and
 * moving, at [render.Framing.DIVER_SCREEN_FRACTION] under the same
 * [AttractLayout.DIVER_HALO_HALF_HEIGHT] glow band the attract screen dodges. A pinned
 * screenshot of the first shipped layout (rows at 0.300 / 0.357 / 0.414, inside the
 * 0.26–0.54 band) showed exactly this: the diver's head sitting visibly between "Z" and
 * "kick", darkened by the scrim but not erased by it. So this screen observes the identical
 * `DIVER_SCREEN_FRACTION ± DIVER_HALO_HALF_HEIGHT` band the attract screen does, and — like
 * [AttractLayout] — puts its content below the diver rather than across him: the sign goes
 * above the band (title only, it fits), everything else below it. The scrim's job is
 * legibility against the moving water and light, not clearance from the diver; clearance is
 * the anchors' job, exactly as it is for [AttractLayout].
 *
 * Nothing is drawn beneath the scrim but the live world — no attract sign, no leaderboard, no
 * HUD. The briefing is the only thing on screen to read, and a leaderboard competing with it
 * is the one thing that would stop a first-timer reaching the rule line. The cost is that the
 * board is hidden for up to one countdown per play, which is accepted: it is on screen the
 * whole time nobody is playing, which is most of the day.
 *
 * @see BriefingScreenTest, which pins the non-overlap, the width fit and the diver clearance.
 */
object BriefingLayout
{
    /**
     * Lighter than [PauseLayout.SCRIM_ALPHA] (0.72). Authored alpha — pass it through
     * [Hud.authoredAlphaFor], because this surface stores alpha squared.
     */
    const val SCRIM_ALPHA = 0.55f

    const val TITLE_Y = 0.09f
    const val TITLE_FONT = 0.055f

    /**
     * Top of the first control row. Below [AttractLayout.DIVER_HALO_HALF_HEIGHT]'s band
     * (0.26–0.54 with today's constants) rather than inside it — see this object's class
     * doc for the pinned screenshot that showed the diver's head sitting between two rows
     * when this was 0.30.
     */
    const val ROWS_TOP_Y = 0.57f
    const val ROW_FONT = 0.030f

    /** Row pitch as a multiple of [ROW_FONT] — 1.9 leaves most of a line of air between rows. */
    const val ROW_SPACING = 1.9f

    /**
     * Half the gutter between the two columns. The control token is drawn `xOrigin = 1f`
     * (RIGHT-aligned) at `centreX - COLUMN_GAP * h` and the verb `xOrigin = 0f` (LEFT-aligned)
     * at `centreX + COLUMN_GAP * h`, so the tokens keep a clean right edge whatever their
     * width — which matters, because a rebind can turn `A` into `RIGHT BUMPER`.
     *
     * NOTE this is the OPPOSITE of [AttractLayout]'s leaderboard, which aligns its columns
     * OUTWARD (`rankX` is `xOrigin = 0` at `centre - halfSpan`). Copying `rankX`/`scoreX` here
     * gets the alignments backwards and loses exactly the property this buys.
     */
    const val COLUMN_GAP = 0.012f

    const val RULE_Y = 0.76f
    const val RULE_FONT = 0.034f

    const val COUNTDOWN_Y = 0.845f
    const val COUNTDOWN_FONT = 0.028f

    const val SKIP_Y = 0.905f
    const val SKIP_FONT = 0.022f

    fun rowY(index: Int): Float = ROWS_TOP_Y + ROW_FONT * ROW_SPACING * index
}

/**
 * Engine shell. Reads input, ticks the pure simulation on the fixed update,
 * and draws it. All game logic lives in the `dive` package.
 *
 * The IDLE / PLAYING / RUN_OVER / ENTER_INITIALS state machine (attract screen +
 * leaderboard, dwell before restart, initials entry for a qualifying score, idle
 * timeout) lives in [RunLifecycle] — a pure, engine-free, unit-tested class. This file
 * only reacts to it: gates whether [sim] gets ticked, decides which HUD screen to draw,
 * constructs a fresh [DiveSim] on [RunLifecycle.justStarted] and again on
 * [RunLifecycle.justReturnedToIdle], and persists a completed initials entry via
 * [scoreRepository] on [RunLifecycle.initialsJustCompleted] — which must run BEFORE the
 * justReturnedToIdle rebuild in `updateGame`, since both flags are true on the same tick
 * when a player finishes initials entry (see the comments at those two blocks).
 */
class EnPustTil : PulseEngineGame()
{
    /**
     * Nothing thrown by this game may reach the engine's game-loop handler — it opens a text
     * editor over the fullscreen cabinet. See CallbackGuard's class doc for the bytecode.
     */
    private val guard = CallbackGuard { site, count, cause ->
        Logger.error(cause) { "[$site] failed (occurrence $count) - the frame is lost, the cabinet continues" }
    }

    // Held as fields, built once, rather than written inline at the call site: a capturing
    // lambda allocates per call, and four of these run every frame. See CallbackGuard's
    // ALLOCATION note and CLAUDE.md's no-per-frame-allocation rule.
    private val createBody: () -> Unit = { createGame() }
    private val fixedUpdateBody: () -> Unit = { fixedUpdateGame() }
    private val updateBody: () -> Unit = { updateGame() }
    private val renderBody: () -> Unit = { renderGame() }
    private val destroyBody: () -> Unit = { destroyGame() }

    private lateinit var sim: DiveSim
    private val camera = DiveCamera()

    /**
     * EPT_BRIEFING_HOLD pins the briefing open so it can be photographed. The documented
     * window-grab route takes about fourteen seconds to reach a screencapture, which is
     * longer than the briefing lasts — without this the one screen that most needs a real
     * frame is the one screen that cannot be captured. One getenv, unset at the booth, same
     * cost as EPT_DEV / EPT_DEPTH / EPT_FAIL_BOOT. Read HERE rather than in RunLifecycle so
     * that class stays a pure object with no environment reads.
     */
    private val lifecycle = RunLifecycle(
        briefingSeconds =
            if (System.getenv("EPT_BRIEFING_HOLD") != null) Float.POSITIVE_INFINITY
            else RunLifecycle.BRIEFING_SECONDS
    )

    /**
     * Per-source lifecycle edges. See LifecycleInputEdges' class doc for the stuck-button
     * lockout this replaced — the single OR that used to live here could be held true for
     * two days by one jammed encoder button, with no sign of it on screen.
     */
    private val lifecycleEdges = LifecycleInputEdges()

    // Score persistence — registered as an engine Service in onCreate below, which
    // gives it onCreate (load from disk)/onDestroy (final save) hooks driven by the
    // engine's own lifecycle. See ScoreRepository's class doc for the verified call
    // order and the durability guarantees actually achieved.
    private lateinit var scoreRepository: ScoreRepository

    // The seed driving both today's water column ([DiveSim]) and which leaderboard rows
    // count as "today's" (ScoreRepository.topN filters entries by seed — see
    // drawLeaderboard). Resolved in onCreate from application.cfg's "dailySeed" key
    // (see [parseDailySeed]'s doc), NOT at field-init time like [sim]/[scoreRepository]
    // used to be constructed: application.cfg is not loaded into engine.config until
    // PulseEngineImpl's own initEngine() step, which runs after this object already
    // exists but before onCreate is called (verified by decompiling PulseEngineImpl.run:
    // initEngine() then initGame()/onCreate()) — reading engine.config any earlier would
    // silently see an empty config and always fall back to DAILY_SEED regardless of what
    // a technician wrote in the file.
    private var dailySeed = DAILY_SEED

    // Resolved from application.cfg in createGame, beside dailySeed and for the same
    // reason — see parseGamepadButton's doc for why the button map has to be editable
    // without a compiler. Held as `var`s initialised to the compiled defaults so a config
    // with no keys at all (day one, before anyone has touched the file) behaves exactly
    // as the old hardcoded constants did.
    private var kickButton = DEFAULT_KICK_BUTTON
    private var bleedButton = DEFAULT_BLEED_BUTTON
    private var restartButton = DEFAULT_RESTART_BUTTON
    private var restartButtonAlt = DEFAULT_RESTART_BUTTON_ALT
    private var stickDeadzone = DEFAULT_STICK_DEADZONE

    /**
     * Whether control prompts should name ARCADE controls rather than keyboard keys.
     *
     * Deliberately NOT `engine.input.gamepads.isNotEmpty()` alone. A generic arcade USB encoder
     * may have no SDL gamepad mapping, in which case it is invisible to that list while working
     * fine at the OS level — a verified platform finding, and the reason [logGamepadDiagnostics]
     * exists. Gating on the mapped list alone would put "PRESS SPACE" on the attract screen of a
     * cabinet that has no keyboard, in front of the queue, as the most visible string in the
     * game. Today that string reads "PRESS START" and is right by accident in exactly that case;
     * a change that made the booth's worst input failure ALSO display the wrong instruction would
     * be a regression. Naming the right control on a broken machine beats naming a control the
     * machine does not have.
     *
     * [unmappedGamepadCount] already walks the raw GLFW joystick list every attract frame for the
     * booth status line, with an indexed loop for the no-allocation rule, so the second half of
     * this costs nothing new.
     */
    private var arcadeHints: Boolean = true

    // ONE-WAY LATCH backing arcadeHints' real-hardware reading - see refreshControlHints' doc
    // for why this is a separate field from arcadeHints rather than folded into it. Starts
    // false; once a real detection sets it true, it never goes false again this process.
    private var arcadeEverDetected: Boolean = false

    // The five composed hints. ControlHints builds Strings, and CLAUDE.md forbids per-frame
    // allocation in the render path (the one written exemption is HUD numeric formatting, which
    // these are not). The button map is fixed once config is read, so `arcadeHints` is the only
    // input that can vary — these are rebuilt only when it flips.
    // All five start empty, not at the old ScreenText constants — Step 5 deletes those, and a
    // field initialiser referencing a constant this same task removes would not compile. The
    // value is meaningless before createGame seeds the cache in Step 3, and
    // refreshControlHints' `hintPlayAgain.isNotEmpty()` guard already reads empty as unseeded.
    private var hintPressStart: String = ""
    private var hintPlayAgain: String = ""
    private var hintInitialsHelp: String = ""
    private var hintLegend: String = ""
    // "PRESS <button> TO DIVE NOW" for the briefing skip prompt. Built from hintPressStart
    // rather than re-deriving ControlHints.confirm(...) a second time — see the review finding
    // that put this field here, and drawBriefingScreen for the per-frame allocation it replaced.
    private var hintBriefingSkip: String = ""

    /**
     * The dev-only depth pin, or null at the booth. Resolved in [onCreate] from
     * [DEPTH_PIN_ENV] — see [parseDepthPin] for what it is for and why it exists.
     *
     * A `var` set once rather than a `val` initialised here, for exactly [dailySeed]'s reason
     * in reverse: this one COULD be read at construction (it is an env var, not config), but
     * keeping both pins resolved in the same place in `onCreate` is worth more than saving a
     * line, since the failure mode for both is "read too early and silently see nothing".
     */
    private var depthPin: Float? = null

    /**
     * The pad whose button started the current run, or null for a keyboard start / attract
     * mode. See [selectGameplayPad] for the slot-0 failure this removed.
     */
    private var activePadId: Int? = null

    /**
     * Reused every frame by [readInput] rather than rebuilt with `pads.map { it.id }` — this
     * project forbids per-frame allocation on the update path (CLAUDE.md; a `Color(...)`
     * allocation in this exact file was a review finding one task ago). Cleared and refilled
     * each call rather than sized once: the booth normally has one pad, but a second HID
     * plugged in mid-session must still be seen without this buffer needing to grow past a
     * size chosen for the common case.
     *
     * The boxing an earlier version of this doc apologised for is NOT a real cost: every
     * GLFW joystick id is 0..15 (`GLFW_JOYSTICK_1`..`GLFW_JOYSTICK_LAST`), and the JVM's
     * `Integer` cache covers -128..127 by spec — `ArrayList<Int>.add` autoboxes into an
     * already-existing `Integer`, not a new one, for every id this hardware can ever
     * produce. That was wrong in the PESSIMISTIC direction: it named a free operation as a
     * cost and left the real one unnamed. The real one was the `Iterator` `pads
     * .firstOrNull { it.id == chosenId }` allocated resolving [selectGameplayPad]'s answer
     * back to a `Gamepad` — fixed alongside this doc with an indexed loop; see [readInput].
     * [selectGameplayPad] itself is allocation-free for the same reason — see its doc.
     */
    private val gamepadIdBuffer = ArrayList<Int>(4)

    /**
     * Puts the diver at [depthPin], if there is one. Called after EVERY `DiveSim` construction.
     *
     * Both call sites matter and a new one must call it too, which is why
     * `EnPustTilDepthPinTest` scans this file for `DiveSim(` and requires each occurrence to be
     * followed by this call: a third construction site that forgot would produce a rig that
     * silently works on the attract screen and not after a restart.
     *
     * `debugSetDepth` and not `debugMoveTo`, so the diver keeps the horizontal position the
     * seed gave him — the pin is a depth, and moving him sideways as well would change which
     * pearls and which cliff he is next to, i.e. change the thing being photographed.
     */
    private fun applyDepthPin()
    {
        depthPin?.let { sim.debugSetDepth(it) }
    }

    // Read once at construction, same as MetricViewer's gate below — everything downstream
    // that checks this field (the input overlay in onRender) is then a single boolean read,
    // not a repeated env-var lookup, and is trivially inert (one branch, no allocation, no
    // draw calls) when unset.
    private val devMode = System.getenv("EPT_DEV") != null

    // Seconds since [CameraInvariants] was last consulted. Only ever advanced behind `devMode`
    // (see onRender), so at the booth it stays at zero and costs one float compare per frame.
    private var secondsSinceCameraCheck = 0f

    // One-shot latch for the missing-normal-map-renderer warning in onRender. Presentation-only
    // and deliberately not reset: the point is one log line per process, not one per frame.
    private var warnedAboutNormalMaps = false

    // The post-processing pass that repairs `main`'s alpha below the waterline — see
    // `render/OpaqueWater.kt` for the whole diagnosis. Held rather than re-fetched because its one
    // uniform is written every frame from onRender, and `getPostProcessingEffect(name)` is a
    // linear scan of the surface's effect list. Constructed in onCreate; null only in the window
    // before that, which nothing renders in.
    private var opaqueWater: OpaqueWaterEffect? = null

    override fun onCreate() = guard.run(CallbackSites.CREATE, createBody)

    /**
     * True if [createGame] threw ANYWHERE inside it — including late, fully recoverable
     * asset-load failures (DiverSprite/RockFace/Backdrop/LightEmitter/MoteSprite/
     * PearlNormalMap/DiveLighting.setup all have their own documented fallback and leave
     * `sim`/`scoreRepository` set regardless). Feeds [drawBoothStatusLine]'s `bootFailed`
     * segment on the ordinary, still-playable attract screen. Deliberately NOT what
     * [renderGame] uses to decide whether the world itself is drawable — see
     * [worldUnusable] for that narrower, more severe condition, and [drawBootFailedScreen]
     * for the full-frame screen it alone routes to.
     */
    private val bootFailed: Boolean get() = guard.failureCount(CallbackSites.CREATE) > 0

    /**
     * True only when `sim` or `scoreRepository` never got constructed at all — the one
     * `createGame` failure with no world and no HUD left to draw around it. A strict subset
     * of [bootFailed]: every `worldUnusable` frame is also a `bootFailed` one (the
     * construction lines are inside `createGame`, so failing them counts at
     * [CallbackSites.CREATE] too), but most `bootFailed` frames (an asset failed to load, the
     * lighting rig failed to set up) are NOT `worldUnusable` — the run is still playable,
     * just missing a texture or an effect. [renderGame] checks this, not [bootFailed], for
     * exactly that reason.
     */
    private val worldUnusable: Boolean get() = !::sim.isInitialized || !::scoreRepository.isInitialized

    /** The real body. See [guard] for why nothing here may throw past this class. */
    private fun createGame()
    {
        // THE HUD SURFACE IS CREATED FIRST, BEFORE ANYTHING ELSE IN THIS FUNCTION THAT CAN
        // THROW — moved here from much further down (it used to sit right before
        // IridescenceRenderer.addTo(hudSurface), after asset loading and DiveLighting.setup)
        // specifically so [renderGame]'s bootFailed branch always has a real, correctly
        // scaled surface to draw the boot-failed message on.
        //
        // THE BUG THIS FIXES: engine.gfx.getSurfaceOrDefault("hud") — decompiled,
        // `GraphicsImpl.getSurfaceOrDefault` is `surfaceMap[name] ?: mainSurface`, a SILENT
        // fallback with no log and no warning. If a failure anywhere between the old
        // creation point and the end of createGame (DiverSprite/RockFace/Backdrop/
        // LightEmitter/MoteSprite/PearlNormalMap.load, DiveLighting.setup, ...) left "hud"
        // never created, drawBootFailedScreen's `getSurfaceOrDefault("hud")` would silently
        // hand back mainSurface — whose camera is engine.gfx.mainCamera, a WORLD camera in
        // METRES scaled by pixels-per-metre (see the camera-null comment a few lines below,
        // preserved where the rest of this surface's setup still lives). Screen-pixel
        // coordinates fed through that camera land hundreds of world-metres off screen —
        // exactly the "black, silent cabinet" this whole mechanism exists to end, and for
        // precisely the failures most likely to occur (the asset-loading half of this
        // function, all GL calls). Creating "hud" before any of that runs means it always
        // exists by the time anything downstream could fail — this call depends on nothing
        // but `engine`, so there is no cost to moving it first.
        //
        // Explicit rather than all-default (verified against the decompiled
        // Graphics/GraphicsImpl interface in the engine jar, not assumed):
        //   - backgroundColor: engine default IS already Color.BLANK (transparent) —
        //     confirmed from Graphics.createSurface$default's bytecode. Named here so that
        //     stays true on purpose rather than by accident.
        //   - multisampling: engine default is Multisampling.NONE. MSAA16 smooths the
        //     bubble-ring/depth-tape edges and outlined text at negligible cost for a
        //     screen-space overlay this small (see the reference's SceneRenderSystem,
        //     which uses MSAA16 for both of its overlay surfaces).
        //   - zOrder: left null, the engine assigns an auto-decrementing counter
        //     (`lastZOrder--`) in creation order. HUD_Z_ORDER pins it explicitly instead —
        //     now doubly load-bearing, since this call no longer sits next to
        //     DiveLighting.setup() at all, and an implicit zOrder would have made THIS move
        //     silently change the HUD's layering relative to GI's own surfaces. Matches the
        //     reference's SURFACE_MENU_UI (-90): smaller zOrder sorts later in GraphicsImpl's
        //     composite pass (sorted by -zOrder ascending, confirmed in the decompiled
        //     comparator), i.e. drawn ON TOP.
        //   - camera: left null (default) deliberately — the engine default IS "create a
        //     fresh orthographic camera" (GraphicsImpl.createSurface builds its own
        //     DefaultCamera.createOrthographic when none is passed), which is exactly what a
        //     screen-space HUD needs. It must NOT be handed the shared main camera: passing
        //     it would multiply every HUD coordinate by pixels-per-metre and smear the whole
        //     overlay off screen (this is the exact defect the getSurfaceOrDefault fallback
        //     above would have reintroduced). The HUD is authored in screen pixels and stays
        //     that way; what makes its diver-anchored elements track the world is the single
        //     worldPosToScreenPos call in onRender, not a shared camera. Leave it null.
        val hudSurface = engine.gfx.createSurface(
            name = "hud",
            backgroundColor = Color.BLANK,
            multisampling = Multisampling.MSAA16,
            zOrder = HUD_Z_ORDER
        )

        // Resolved FIRST: sim/scoreRepository below are constructed from this value, and
        // application.cfg (loaded by the engine before onCreate runs — see dailySeed's
        // doc) is the only source for a technician's day-two override. See
        // application.cfg for the exact commented-out line to uncomment/edit on-site.
        //
        // getInt checked FIRST, not getString — see resolveDailySeed's doc. A plain
        // all-digit seed (every real one used at this booth) is stored as an Integer by
        // application.cfg's own loader, and getString returns null for it; reading only
        // getString is why the day-two procedure never actually worked before this fix.
        val dailySeedRawInt = engine.config.getInt("dailySeed")
        val dailySeedRawString = engine.config.getString("dailySeed")
        dailySeed = resolveDailySeed(dailySeedRawInt, dailySeedRawString, DAILY_SEED)
        // WARN whenever the key was present, in EITHER of the two failure shapes, but
        // could not be used — not just the decimal-point Float case. A rawInt present
        // never needs this: an Int from this loader is always a valid seed.
        if (dailySeedRawInt == null)
            dailySeedConfigWarning(dailySeedRawString, engine.config.getFloat("dailySeed"))?.let { Logger.warn { it } }
        // "Daily seed: $dailySeed" is logged below, once sim/scoreRepository are actually
        // built from it — see that line for why it sits there rather than here.

        // Beside dailySeed, and for the same reason: application.cfg is the only thing a
        // technician can edit at the booth without a toolchain.
        val kickButtonRawString = engine.config.getString("kickButton")
        val bleedButtonRawString = engine.config.getString("bleedButton")
        val restartButtonRawString = engine.config.getString("restartButton")
        val restartButtonAltRawString = engine.config.getString("restartButtonAlt")
        kickButton = parseGamepadButton(kickButtonRawString, DEFAULT_KICK_BUTTON)
        bleedButton = parseGamepadButton(bleedButtonRawString, DEFAULT_BLEED_BUTTON)
        restartButton = parseGamepadButton(restartButtonRawString, DEFAULT_RESTART_BUTTON)
        restartButtonAlt = parseGamepadButton(restartButtonAltRawString, DEFAULT_RESTART_BUTTON_ALT)
        // stickDeadzone = 0 (the natural way to write "disable the deadzone") is all-digit
        // and therefore an Integer under the same coercion, not a Float — see
        // resolveDeadzone's doc. getInt checked first for the same reason dailySeed's is.
        stickDeadzone = resolveDeadzone(engine.config.getInt("stickDeadzone"), engine.config.getFloat("stickDeadzone"), DEFAULT_STICK_DEADZONE)

        // WARN, not INFO (this line used to be INFO, which application.cfg's own
        // logLevel = WARN booth default never reaches — see BoothLog). Without this line
        // at a level the booth log actually keeps, a mistyped `restartbutton = STRAT`
        // falls back to the compiled default with NO signal anywhere, and the reasonable
        // conclusion for a technician is "the config file doesn't work" — precisely the
        // failure Task 7 exists to prevent.
        Logger.warn { "Buttons: kick=$kickButton bleed=$bleedButton restart=$restartButton/$restartButtonAlt deadzone=$stickDeadzone" }

        // Per-key, and now checked against ALL THREE coercion shapes (see
        // gamepadButtonConfigWarning's GENERALISED paragraph) — the summary line above
        // says WHAT was resolved, not whether a key was actually present and malformed
        // versus simply absent, and a numeric typo like `kickButton = 0` used to produce
        // no warning at all because only the String shape was ever checked.
        gamepadButtonConfigWarning("kickButton", kickButtonRawString, engine.config.getInt("kickButton"), engine.config.getFloat("kickButton"), kickButton, DEFAULT_KICK_BUTTON)?.let { Logger.warn { it } }
        gamepadButtonConfigWarning("bleedButton", bleedButtonRawString, engine.config.getInt("bleedButton"), engine.config.getFloat("bleedButton"), bleedButton, DEFAULT_BLEED_BUTTON)?.let { Logger.warn { it } }
        gamepadButtonConfigWarning("restartButton", restartButtonRawString, engine.config.getInt("restartButton"), engine.config.getFloat("restartButton"), restartButton, DEFAULT_RESTART_BUTTON)?.let { Logger.warn { it } }
        gamepadButtonConfigWarning("restartButtonAlt", restartButtonAltRawString, engine.config.getInt("restartButtonAlt"), engine.config.getFloat("restartButtonAlt"), restartButtonAlt, DEFAULT_RESTART_BUTTON_ALT)?.let { Logger.warn { it } }

        // A plausible booth copy-paste (kickButton = bleedButton, or any two of these four
        // landing on the same physical button) makes the game partly or fully unplayable
        // with no signal anywhere else — see gamepadButtonCollisionWarnings's doc.
        gamepadButtonCollisionWarnings(kickButton, bleedButton, restartButton, restartButtonAlt)
            .forEach { Logger.warn { it } }

        // AFTER the button map resolves and BEFORE the first renderGame. A cache seeded at
        // field-init time would hold pre-config labels — the fields above are still their
        // compiled defaults until the four lines above run.
        rebuildControlHints()

        // A cheap, general symptom-check for "application.cfg silently failed to load
        // completely" (see resolveDailySeed's doc for the mechanism) — a startup guard
        // rather than trusting every technician to remember and correctly re-derive a
        // documented numeric ceiling. See configFileHealthWarning's doc for why gameName
        // specifically is the sentinel.
        configFileHealthWarning(engine.config.getString("gameName"))?.let { Logger.warn { it } }

        // Read ONCE, here, beside the other capture pins — see parseDepthPin. Null at the
        // booth, where EPT_DEPTH is not set, so applyDepthPin below is a null check per run.
        depthPin = parseDepthPin(System.getenv(DEPTH_PIN_ENV), Tuning.MAX_DEPTH)
        depthPin?.let { Logger.warn { "[$DEPTH_PIN_ENV] the diver is PINNED at $it m — this is a capture rig, not a playable build" } }

        // FAIL_BOOT_ENV (EPT_FAIL_BOOT): placed here — after "hud" already exists (so
        // drawBootFailedScreen has a real surface to draw on) and before `sim`/
        // `scoreRepository` are constructed (so `worldUnusable` below is true, which is what
        // actually routes renderGame to the full-frame screen) — it reproduces the exact
        // unrecoverable case this task exists to make visible, on demand, without editing
        // source. See FAIL_BOOT_ENV's own doc for why it is a named constant rather than a
        // literal, and CLAUDE.md for it alongside the other four EPT_* flags. Unset at the
        // booth: one getenv at startup, same cost as every other EPT_* flag here.
        if (System.getenv(FAIL_BOOT_ENV) != null)
            error(FAIL_BOOT_ENV)

        sim = DiveSim(seed = dailySeed)
        applyDepthPin()
        scoreRepository = ScoreRepository(todaySeed = dailySeed)
        // WARN, not INFO — the same defect fixed 30-odd lines above for the button-map
        // summary, on the exact value this whole round's critical fix is about: INFO never
        // reaches application.cfg's booth-default logLevel = WARN, so this line would
        // never have reached the booth log at all.
        Logger.warn { "Daily seed: $dailySeed" }

        // Booth mode is the default (see application.cfg: FULLSCREEN, quiet logging, no
        // title bar an attendee could drag or close). screenMode and window size cannot be
        // changed here — the window is already built from application.cfg before onCreate
        // ever runs, and neither has a runtime setter (confirmed against the engine's
        // Configuration API) — so that split lives in application.cfg/application-dev.cfg,
        // not here. What CAN be gated at runtime is gated behind this one env var, mirroring
        // how EPT_SCREENSHOT already gates ScreenshotEffect below.
        if (devMode)
        {
            engine.config.logLevel = LogLevel.DEBUG   // belt-and-braces: works even against a built release .exe
            engine.service.add(MetricViewer())        // F3
        }

        // The engine's scene editor (EPT_EDITOR=1). Registered from here because nothing in
        // the engine ever constructs SceneEditor — verified by scanning every class in
        // pulse-engine-0.13.0.jar outside the editor package for a reference, and finding
        // none. So there is no config key and no flag that can reach it; only this call can.
        // The `showSceneEditor` console command is registered BY SceneEditor.onCreate, which
        // is why F1 cannot get you there either until this line has run.
        //
        // Do NOT try to replace `start()` with `openEditorOnStart = true` in
        // application-dev.cfg. SceneEditor.onCreate loads its own bundled
        // /pulseengine/config/editor_default.cfg (which sets that key false) AFTER our config
        // is already loaded, and ConfigurationImpl.loadConfigFile does an unconditional
        // Map.put — so the engine's default silently overwrites ours. Verified empirically:
        // with the key set true in application-dev.cfg, the game logged
        // `openEditorOnStart=false editor.isRunning=false`.
        //
        // Inert at the booth: EPT_EDITOR is unset there, so this is one getenv at startup and
        // SceneEditor is never constructed. Note the editor drives engine.gfx.mainCamera via
        // its own Camera2DController (SceneEditor.kt:347), which makes it a SECOND writer of
        // the camera CameraRig owns — the exact shape of the bug 6ea1f53 fixed. Under
        // EPT_EDITOR the two fight every fixed tick: panning moves the world out from under
        // the HUD, which still anchors itself through mainCamera.worldPosToScreenPos in
        // onRender, and CameraRig snaps it back. MainCameraOwnershipTest cannot see this,
        // because Camera2DController lives in engine code; Task 10 of
        // docs/superpowers/plans/2026-08-06-engine-world-coordinates.md is the gate that skips
        // CameraRig.apply while the editor is running. Its failure mode is loud (you cannot pan
        // the viewport), not silent, and it cannot reach the booth.
        //
        // dive.scn currently holds NO entities at all: it exists only so
        // GlobalIlluminationSystem, which is a scene SYSTEM, has a scene to be added to. That
        // is why the editor's Outliner reads 0/0 and its Inspector is empty — there is nothing
        // authored to select. Stage D of docs/superpowers/plans/2026-08-06-engine-world-coordinates.md
        // plans the appearance-only entities that would populate it, and records two engine
        // blockers found while planning it: runtime-spawned entities never appear in the
        // Outliner at all, and viewport interaction is gated on the scene being STOPPED.
        if (System.getenv("EPT_EDITOR") != null)
            engine.service.add(no.njoh.pulseengine.modules.editor.SceneEditor().also { it.start() })

        // THE WORLD SURFACE IS NOW TRANSPARENT WHERE NOTHING IS DRAWN, and that one line is what
        // lets a sky exist. It used to be an opaque dark blue, which was fine while `DiveRenderer`
        // painted zone bands across the whole visible rect — the clear colour was never seen. It
        // is not fine now: the bands start at `WaterSurface.QUAD_BOTTOM_DEPTH`, everything above
        // that is the sea's own alpha ramp over the sunset, and an opaque clear colour would put a
        // flat navy field between the two.
        //
        // Transparency survives the whole post chain, all four steps read off the engine jar
        // rather than assumed: GI's multiply writes `vec4(c0.rgb * c1.rgb, c0.a)`, the bloom's
        // final pass writes `vec4(src.rgb + bloom, src.a)`, colour grading only ever touches
        // `.rgb`, and `BackBufferBaseState` composites with `glBlendFunc(GL_SRC_ALPHA,
        // GL_ONE_MINUS_SRC_ALPHA)` — STRAIGHT alpha, so a transparent world pixel contributes
        // nothing at all rather than the premultiplied haze it would under `GL_ONE`.
        engine.gfx.mainSurface.setBackgroundColor(Color.BLANK)

        // ...and the thing that fills the gap that leaves: the sea's surface, on `main` because it
        // is part of the LIT world and must be multiplied by the light map, unlike the sky.
        //
        // ATTACHED BEFORE THE PEARLS, AND THE ORDER IS LOAD-BEARING. Batch renderers are flushed
        // in the order they were ADDED to the surface, not in the order they were called — and
        // every one of them writes depth, including for fragments it draws at alpha 0. So between
        // any two renderers on one surface, whichever was ADDED first rasterises first and wins
        // the depth test wherever they overlap, whatever `currentDepth` either was given.
        //
        // Reverse these two and the pearls rasterise first, write depth across their own quads,
        // and every water fragment behind one fails `GL_LEQUAL` — each pearl punches a hole
        // through the sea at full alpha, with no error and no log line.
        //
        // THE RULE, STATED ONCE: THE SEA AND THE PEARLS ARE PART OF THE WORLD AND MUST BE FLUSHED
        // WITH THE WORLD. Anything that draws in FRONT of the world belongs after them.
        // `SurfaceRendererOrderTest` fails the build on both halves of that.
        //
        // ## THE GOD RAYS ARE WHERE THIS WAS LEARNED, TWICE, AND THEY ARE GONE
        //
        // `DiveLighting.setup` used to attach `ShaftRenderer`, whose quads deliberately took a
        // greater `currentDepth` than the world so the rays landed in front of it. Both of these
        // renderers were once attached BELOW that call, and both were silently destroyed by it:
        //
        //   - the sea, captured at 6 m with the fragment shader forced to a flat opaque magenta:
        //     the quad rasterised for depth -1.5 to 0.0 and vanished completely from 0.0 to 8.2 —
        //     a cut at exactly the depth the shafts started from, and nowhere else;
        //   - the pearls, on a pinned 30 m frame at four pearls' own centre pixels, against the
        //     same build with the shafts skipped:
        //
        //         depth    with god rays        without
        //         15.8 m   RGBA(0, 8, 21, 255)  RGBA(137, 109, 18, 255)
        //         16.3 m   RGBA(1, 11, 24, 251) RGBA(155, 119, 15, 255)
        //         19.8 m   RGBA(0, 9, 23, 255)  RGBA(160, 122, 14, 255)
        //         27.8 m   RGBA(0, 9, 20, 255)  RGBA(196, 160, 28, 255)
        //
        //     Not dimmed — GONE, replaced by plain water at full alpha. What survived is the
        //     pearl's GI LIGHT, on a different surface with a different depth buffer, so a shallow
        //     pearl read as a soft blurry glow with no body. That looks like art, and is how it
        //     shipped; an earlier version of `SurfaceRendererOrderTest`'s doc even rationalised it
        //     as "the shafts agent's design" and declined to assert it. It was not design.
        //
        // The god rays were REMOVED on 2026-08-17 at the owner's request, so nothing attached in
        // `DiveLighting.setup` draws in front of the world any more and neither failure can recur
        // as such. The ordering above is kept and still asserted because the MECHANISM is
        // unchanged: it is what will bite the next renderer added to this surface.
        WaterRenderer.addTo(engine.gfx.mainSurface)

        IridescenceRenderer.addTo(engine.gfx.mainSurface)
        engine.config.fixedTickRate = 60f
        camera.snapTo(sim.depth)

        // SNAP, not apply, and the difference is the whole of frame 1. `topLeftWorldPosition`
        // and the view matrix behind it are computed in GraphicsImpl.initFrame at the top of
        // each frame, before any of our callbacks run (PulseEngineImpl.kt:69-73, 216-224) — so
        // without a write here the first frame would be drawn, and DiveRenderer's strip walk
        // would read its visible rect, from the identity camera the engine constructs.
        //
        // But a plain `apply` does not fix that, which is not obvious and was measured the hard
        // way: `updateViewMatrix` interpolates every parameter from a snapshot the engine only
        // refreshes inside a fixed step, frame 1 runs no fixed step, and the snapshot's initial
        // value IS that identity. `snap` collapses the two. See CameraRig's class doc.
        CameraRig.snap(engine, camera.depth)

        // Registering as a Service (rather than calling its methods directly) gives
        // ScoreRepository its own onCreate (load scores from disk) and onDestroy (final
        // save) hooks, driven by the engine's own lifecycle — see its class doc for the
        // verified call order.
        engine.service.add(scoreRepository)

        logGamepadDiagnostics()

        // The diver's art. Queued here rather than at field-init time for the same
        // reason `sim` is: `engine` is not usable before onCreate. `AssetManager.load` only
        // appends to a queue — the GL upload happens some frames later — so DiveRenderer draws a
        // fallback rectangle until DiverSprite.sheetsReady() turns true, and complains in the log
        // if it never does.
        DiverSprite.load(engine)

        // The oxygen vents' plume: one normal-map-only sheet, no diffuse, because a vent's colour
        // comes from `setDrawColor` at the draw site (live vents and spent ones differ by nothing
        // else). Same queue, same asynchronous upload, same degradation — DiveRenderer draws the
        // flat 2.4 m square the vents shipped as until OxygenSprite.sheetsReady() turns true.
        OxygenSprite.load(engine)

        // The column's own art: the tiling cliff face on both walls, and the parallax
        // silhouettes behind the water. Same queue and the same asynchronous upload, so both
        // degrade to what shipped before them — a flat stone slab, and no backdrop at all —
        // until their textures land, and both complain in the log if they never do.
        RockFace.load(engine)
        Backdrop.load(engine)

        // The seabed at the foot of the trench. Queued HERE, beside the rock and the backdrop,
        // and not after the draw code lands: `drawSandBank` running against textures nobody
        // queued would leave `SandBank.ready()` false forever and, after 600 frames, spend its
        // one WARN on a self-inflicted false alarm — which is how a real one gets ignored later.
        // Loading art nothing draws yet is harmless; drawing art nothing loaded is not.
        SandBank.load(engine)

        // The GENERATED assets: the round emitter every point light in DiveLighting shapes its
        // light from, the dab a mote is DRAWN with, and the hemisphere normal that tells GI a
        // pearl is a bead and not a flat quad. Same queue and the same asynchronous upload as
        // every file-backed asset above, and the same gate-and-warn arrangement — though the
        // gates differ on purpose (LightEmitter.emitter and MoteSprite.sprite fall back to
        // Texture.BLANK, i.e. to a square, while PearlNormalMap.normals returns null, which
        // normal_map.frag reads as the flat normal the pearls had before it existed; see them for
        // why a fallback is right for two and not the third). None has a file behind it, so all
        // three must be FILLED before they are queued — which is what their `load` does and is the
        // whole reason they are not in loadAll above.
        //
        // MoteSprite is separate from LightEmitter although the two textures look alike, and the
        // difference is which SIDE of the pipeline each is for: LightEmitter's ramp is shaped so
        // the alpha = 0.5 contour is the inscribed circle, because that is the only contour GI
        // reads, which forces a flat 1.0 core that reads as a plate when it is DRAWN. See
        // MoteSprite's class doc for the measurements.
        LightEmitter.load(engine)
        MoteSprite.load(engine)
        PearlNormalMap.load(engine)

        DiveLighting.setup(engine)

        // THE GAME'S OWN SHADER, on both surfaces — one program, two coordinate spaces.
        //
        // `IridescenceRenderer` is a `BatchRenderer`, so it belongs to a SURFACE and is handed
        // that surface when it draws. Attaching one instance here and one below is what lets a
        // world-space pearl in metres and a screen-space air bubble in pixels come out of the
        // same GLSL: each instance uploads its own surface's `viewProjection`, exactly as the
        // engine's `TextureRenderer` already does for `fillRect` on both. See that class's doc
        // — this is the extension path for every custom shader that follows, and it is
        // deliberately NOT a `PostProcessingEffect`, which would be a whole-screen filter
        // rather than a per-object surface property.
        //
        // Only the HUD's instance is attached here. THE WORLD'S IS ATTACHED WITH THE WORLD, next
        // to WaterRenderer and BEFORE DiveLighting.setup — see there for why, and
        // `SurfaceRendererOrderTest` for the rule. The engine defers the actual `init` to the top
        // of the next frame either way (`SurfaceImpl.addRenderer` queues it), which is why both
        // call sites tolerate a null renderer for one frame.
        IridescenceRenderer.addTo(hudSurface)

        // THE SKY, ON A SURFACE OF ITS OWN AND BEHIND THE WORLD — see `render/Sky.kt`, which has
        // the whole argument and the light-map measurements behind it. In short: GI multiplies
        // `mainSurface` by the light map, so a sunset drawn there comes out with the light map
        // printed across it; `"hud"` escapes the multiply but is composited ON TOP, which would
        // paint out the cliff tops that rise into the sky. A third surface escapes the multiply
        // AND stays behind the world.
        //
        //   - camera: `engine.gfx.mainCamera`, DELIBERATELY the shared world camera and the exact
        //     opposite of the HUD's `null` two calls up. The sky's only interesting edge is the
        //     waterline, which is a world DEPTH; sharing the camera puts the sky through the same
        //     matrix as the water, built once per frame in `gfx.initFrame`, so the two cannot
        //     drift for the same structural reason a light cannot drift from what it lights. This
        //     is a READ of the camera — `MainCameraOwnershipTest` allows this file to read it and
        //     separately forbids anything outside `CameraRig` from writing a transform.
        //   - zOrder: `main`'s plus `Sky.Z_ORDER_OFFSET`. `GraphicsImpl` composites sorted by
        //     `-zOrder` ascending, so LARGER is drawn EARLIER, i.e. further back — the opposite
        //     sense to `HUD_Z_ORDER`'s -90. The offset clears GI's nine internal surfaces, which
        //     take `main + 1 .. + 9`.
        //   - backgroundColor: transparent, so that once the diver is below the waterline this
        //     surface contributes nothing at all rather than a colour behind an opaque world.
        //   - multisampling: left at the engine's NONE. Every edge on this surface is a
        //     horizontal strip boundary inside a smooth gradient; there is nothing to anti-alias.
        //     The one edge that needs it is the WATERLINE, and that is the water shader's alpha
        //     ramp on `main`, which anti-aliases itself against whatever is behind it.
        val skySurface = engine.gfx.createSurface(
            name = Sky.SURFACE_NAME,
            camera = engine.gfx.mainCamera,
            backgroundColor = Color.BLANK,
            zOrder = engine.gfx.mainSurface.config.zOrder + Sky.Z_ORDER_OFFSET
        )

        // ...AND THE PASS THAT STOPS THAT TRANSPARENCY LEAKING BELOW THE WATERLINE. Attached here,
        // immediately after the surface whose blankness is what made the defect visible, because
        // the two facts only make sense together: `main` is deliberately transparent above the
        // waterline so the sunset behind shows through, and `"sky"` is deliberately BLANK below it
        // so an opaque world has nothing wasted behind it. ANY translucent draw on `main` — a
        // mote, and formerly a god ray — erodes `main`'s alpha, because `glBlendFunc(GL_SRC_ALPHA,
        // GL_ONE_MINUS_SRC_ALPHA)` applies to the alpha channel too and the engine never calls
        // `glBlendFuncSeparate`. Underwater that eroded alpha revealed the cleared backbuffer: a
        // solid black disc wherever motes overlapped INSIDE A SHAFT, which was the only place the
        // two erosions compounded far enough to see. Not a mote bug and not a shaft bug — an
        // engine one, so REMOVING THE GOD RAYS (2026-08-17) DID NOT REMOVE THE NEED FOR THIS PASS:
        // it only removed the one place the erosion happened to be visible, which is the worst
        // reason to delete a guard. `render/OpaqueWater.kt` has the full derivation, the
        // measurement and what was rejected.
        opaqueWater = OpaqueWaterEffect().also { engine.gfx.mainSurface.addPostProcessingEffect(it) }

        // THE WAVE'S PHASE, PINNED FOR REPRODUCIBLE CAPTURES. The sea animates on the RENDER
        // clock — see `WaterSurface`'s clock note for why that is the right call for something
        // with no gameplay meaning that has to keep moving on the attract screen — and a
        // render-clock animation is exactly what makes two captures of the same build differ.
        // `HARNESS.md` is emphatic that a control pair which differs as much as the change under
        // test has measured nothing, so the phase can be nailed down. Unset at the booth: one
        // getenv at startup.
        System.getenv("EPT_WAVE_PHASE")?.toFloatOrNull()?.let { WaterSurface.pin(it) }

        // The marine snow's phase, for the same reason and read in the same place. These are the
        // two ambient clocks left: the god rays had a third, `EPT_SHAFT_PHASE`, which `LightShafts`
        // read itself, and both went when the rays were removed on 2026-08-17.
        System.getenv(Motes.PIN_ENV)?.toFloatOrNull()?.let { Motes.pin(it) }

        System.getenv("EPT_SCREENSHOT")?.let {
            engine.gfx.mainSurface.addPostProcessingEffect(render.ScreenshotEffect(it))
            hudSurface.addPostProcessingEffect(render.ScreenshotEffect(it.replace(".png", "") + "-hud"))
            // The sky is a third surface now, so a capture of `main` alone is no longer the
            // frame: above the waterline `main` is transparent and the sunset lives here. Written
            // with the same no-extension quirk as the HUD's (`ScreenshotEffect` substitutes on
            // ".png" and there is none left after the replace).
            skySurface.addPostProcessingEffect(render.ScreenshotEffect(it.replace(".png", "") + "-sky"))
        }
    }

    override fun onFixedUpdate() = guard.run(CallbackSites.FIXED_UPDATE, fixedUpdateBody)

    /** The real body. See [guard] for why nothing here may throw past this class. */
    private fun fixedUpdateGame()
    {
        // THE ONLY CALL TO DiveSim.tick IN THE GAME (grep it), and therefore the only place
        // the clock counts down or air burns — both are `private set` on the sim and written
        // nowhere else. That is what lets a pause be airtight rather than cosmetic: gating
        // this one line freezes the entire simulation, with no second path by which a paused
        // run can lose time, air, depth or a pearl.
        //
        // The condition is asked of RunLifecycle rather than spelled out here as a state
        // comparison (it used to read `state != IDLE`) so that the rule lives in the pure,
        // unit-tested state machine next to the states it talks about, and so that adding a
        // state cannot silently pick a default: RunLifecycle.simulationAdvances is an
        // exhaustive `when` with no `else`, so a seventh state is a compile error there. It is
        // false for IDLE (attract mode must not run a clock while the machine sits
        // unattended) and for PAUSED, and true for the rest — RUN_OVER and ENTER_INITIALS
        // included, unchanged, since DiveSim.tick already no-ops once runOver is set.
        if (lifecycle.simulationAdvances)
        {
            sim.tick(engine.data.fixedDeltaTime, readInput())

            // The diver's aim, integrated here for the same reason and in the same gate. It used
            // to be integrated inside DiveLighting's beam DRAW, from onRender's delta time — which
            // stopped working the moment the diver's BODY had to be drawn to the same heading:
            // DiveRenderer runs before DiveLighting in onRender, so the body would have been
            // rotated to the previous frame's aim while the beam used this one. One write on the
            // fixed tick, strictly before both reads. See DiveLighting.updateAim.
            DiveLighting.updateAim(sim, engine.data.fixedDeltaTime)
        }

        // The sprite loop advances in IDLE too, but DiveSim does NOT tick: an attract-mode
        // diver that ran the simulation would burn air and "drown" on the attract screen.
        // What is wanted is a diver kicking in place at the surface, which is the animation
        // without the simulation. Asked of RunLifecycle.spriteAnimates rather than spelled
        // out as `simulationAdvances || state == IDLE` here, for the same reason the gate
        // above reads `lifecycle.simulationAdvances` rather than `state != IDLE`: the rule
        // lives in the pure, exhaustive `when` next to the states it talks about, so a seventh
        // state is a compile error there instead of silently getting "sprite frozen" here.
        if (lifecycle.spriteAnimates)
        {
            DiverSprite.advanceLoop(engine.data.fixedDeltaTime)

            // The vents' plume, on the same clock and in the same gate. THE FIXED TICK IS THE
            // POINT: OxygenSprite.loopPhase is the only thing that moves the sheet, so a frame
            // where nobody calls this is a frame where all three vents are a still image — the
            // failure is a silent one, since a normal-map-only sheet that never advances still
            // draws a perfectly plausible blob.
            //
            // Same gate, but for a different reason than the diver's. The diver animates in IDLE
            // because attract mode wants a diver kicking in place without DiveSim burning his
            // air; a vent is scenery and simply has no run state to be out of step with (see
            // OxygenSprite.loopPhase — it is deliberately NOT reset per run, unlike the diver's).
            // What the two do share is PAUSED, the one state spriteAnimates is false for, and a
            // paused game whose plumes kept breathing would read as a broken pause rather than a
            // living world.
            OxygenSprite.advanceLoop(engine.data.fixedDeltaTime)
        }

        // CAMERA EASING RUNS ON THE FIXED TICK, NOT THE RENDER CLOCK. It used to be the other
        // way round, and CLAUDE.md used to describe that as deliberate presentation-side
        // smoothing. It stopped being right the moment CameraRig started writing the ENGINE's
        // camera: `updateViewMatrix` interpolates `position` between the value snapshotted at
        // the top of each fixed step and the current one (Camera.kt:120-123,
        // PulseEngineImpl.kt:279), so a render-clock write hands it two values that were never
        // consecutive fixed states and the interpolator judders sub-frame.
        //
        // Nothing is lost. DiveCamera's 1 - e^(-k*dt) easing is already frame-rate independent,
        // so sampling it at 60 Hz produces the same motion, and the engine's interpolation then
        // renders it SMOOTHER above 60 fps than a per-frame update did.
        //
        // Note this runs unconditionally, outside the `simulationAdvances` gate above: a paused
        // or attract-mode frame still has to be drawn with a valid camera, and easing toward a
        // sim depth that is not changing is a no-op that costs four float stores.
        camera.update(engine.data.fixedDeltaTime, sim.depth)
        CameraRig.apply(engine, camera.depth)
    }

    override fun onUpdate() = guard.run(CallbackSites.UPDATE, updateBody)

    /** The real body. See [guard] for why nothing here may throw past this class. */
    private fun updateGame()
    {
        // Ambient is a continuous function of depth only — no camera/screen dependence — so
        // unlike the positional light draws in onRender, timing here doesn't matter.
        DiveLighting.updateAmbient(sim)

        // THE SEA MOVES ON THE RENDER CLOCK, AND OUTSIDE EVERY LIFECYCLE GATE. Both halves are
        // deliberate and `WaterSurface`'s clock note argues them at length; the short version is
        // that the fixed tick is gated on `RunLifecycle.simulationAdvances`, which is FALSE in
        // IDLE, so a correctly-gated sea would be frozen solid on precisely the screen a booth
        // queue spends its time looking at. The water and the sky have no gameplay meaning, are
        // not on the light map, and nothing in `dive/` can observe them — so the presentation
        // clock is the right one, and it is pinnable (`EPT_WAVE_PHASE`) so captures stay
        // reproducible. This is the precedent for the floating bubbles and any later ambient
        // motion; anything the simulation CAN observe still belongs on the fixed tick.
        WaterSurface.advance(engine.data.deltaTime)

        // The marine snow drifts on the same clock, outside the same gates and for the same
        // reasons — see `Motes.advance`. It is the third consumer of the precedent above.
        Motes.advance(engine.data.deltaTime)

        // Start/restart reads gamepad LEVELS here — the engine's Gamepad only exposes
        // isPressed/getAxis (confirmed against the engine jar: no gamepad wasClicked), so
        // there is no engine-provided edge detection for a controller button. The edge is
        // taken per (pad, button) SOURCE, in LifecycleInputEdges, and never over the
        // collapsed signal: this block used to OR every source into one boolean and let
        // RunLifecycle edge that, which one stuck encoder button could hold true for two
        // days — no edge, no start, from any pad or from SPACE, with the attract screen
        // looking healthy throughout. RunLifecycle still re-edges what it is handed (see
        // its class doc); that is now a redundant second guard rather than the only one.
        // Key.SPACE's wasClicked is already an edge and goes in as its OWN source — OR-ing
        // it into the pad levels is exactly what used to mask it behind a jammed button.
        //
        // Movement/kick/bleed are deliberately excluded — a stray keypress or bumped
        // arcade button must never destroy a leaderboard attempt mid-run, nor spuriously
        // wake the attract screen. Key.R (unconditional restart) was removed for the same
        // reason; see fix-gamepad-report.md.
        //
        // Unlike readInput()'s gameplay reads (which follow activePadId — the pad that
        // STARTED the run, see selectGameplayPad's doc), lifecycle input scans EVERY
        // connected gamepad. Index 0 is not guaranteed to be the cabinet's stick at a booth;
        // see LifecycleInputEdges' class doc for why "any button to start" must mean any
        // gamepad, now enforced per-source rather than by the collapsed OR this replaced.
        // Every (pad, button) pair is its own source now, edged independently — see
        // LifecycleInputEdges. This used to OR the LEVELS together and let RunLifecycle
        // edge the result, which one stuck button could hold true forever.
        //
        // Before the lifecycle reads input, so a pad plugged in this frame is reflected on the
        // screen drawn from this frame's state rather than the next one's.
        refreshControlHints()
        lifecycleEdges.begin(engine.data.deltaTime)
        // Indexed, not `.forEach { pad -> ... }` — `Iterable<T>.forEach` on a `List`
        // allocates one `Iterator` per call, and this runs every update frame. Same
        // no-per-frame-allocation reasoning as readInput's pad lookup (see
        // gamepadIdBuffer's doc) and selectGameplayPad — this was the one Iterator this
        // round's own diff left standing after fixing the other two.
        val lifecyclePads = engine.input.gamepads
        for (i in lifecyclePads.indices)
        {
            val pad = lifecyclePads[i]
            // Keyed on .code, not .ordinal: GamepadButton has aliases sharing one physical
            // button's code (A/CROSS both 0, X/SQUARE both 2, DPAD_LEFT/LAST both 14, ...).
            // Two DIFFERENT GamepadButton values with the same .code are the same physical
            // input, and .ordinal would give them two different source keys — which would
            // report "2 STUCK" for one jammed button, or "2 CHATTERING" for one bad
            // contact, on the exact status line BoothStatus/chatterCount's doc says must
            // tell an attendant which repair to attempt. .code collapses aliases correctly
            // by construction; the existing tests are unaffected (START and A have
            // ordinal == code).
            lifecycleEdges.offer(pad.id, restartButton.code, pad.isPressed(restartButton))
            // restartButton/restartButtonAlt are BOTH config now (Task 7), and a
            // technician who finds START unmapped could reasonably set both keys to the
            // same button (or two aliases of it). This guard is an OPTIMISATION, not the
            // safety net — LifecycleInputEdges.offer defends itself against the identical
            // (padId, code) source being offered twice in one frame (see its KDoc for why
            // that bookkeeping corruption, not the edge itself, was the actual hazard).
            // Skipping the call entirely when the codes already match just avoids paying
            // for a call whose result is thrown away.
            if (restartButtonAlt.code != restartButton.code)
                lifecycleEdges.offer(pad.id, restartButtonAlt.code, pad.isPressed(restartButtonAlt))
        }
        lifecycleEdges.offerKeyboardEdge(engine.input.wasClicked(Key.SPACE))
        val actionPressed = lifecycleEdges.commit()

        // Initials entry (ENTER_INITIALS only — harmless to compute unconditionally
        // otherwise, RunLifecycle simply ignores these outside that state). Reuses the
        // SAME actionPressed signal as the restart/confirm button — "the button that
        // started your run also advances your initials" — rather than introducing a
        // third physical input the cabinet does not have. See readInitialsCycle's doc
        // for the up/down source.
        val (cycleUp, cycleDown) = readInitialsCycle()

        // PAUSE AND EXIT ARE KEYBOARD-ONLY, AND THAT IS THE WHOLE POINT.
        //
        // No gamepad button reaches either of these, unlike every other lifecycle input in
        // this file (which deliberately scans EVERY connected gamepad — see
        // LifecycleInputEdges' class doc). Three reasons, in increasing order of severity:
        //
        //  1. The cabinet has a joystick and two buttons, and both buttons are already
        //     spoken for twice over — A/B are kick and bleed during a run, and START/A are
        //     start-and-confirm outside one. There is no third button to spend, and
        //     overloading one of the two would mean a player's kick could open a menu.
        //  2. Pause is the one lifecycle action a player benefits from ABUSING. A pause
        //     reachable from the stick is a free think about a dive you are losing, on a
        //     leaderboard the whole queue can see.
        //  3. Exit lives on this screen. Putting a "shut the cabinet down" path behind a
        //     booth encoder button is precisely the class of failure RunLifecycle was
        //     written to fix — a held or bumped button destroying a run — except the blast
        //     radius is the whole day rather than one run. The encoder may also be unmapped
        //     and noisy (see logGamepadDiagnostics); a phantom press must never be able to
        //     reach an action this final.
        //
        // A keyboard is present at the booth for technicians only, which is exactly the
        // population this screen is for, and Esc/Q are inert on the cabinet's own controls.
        //
        // Levels, not edges, for both — matching every other lifecycle input in this file
        // (Key.wasClicked exists, Gamepad has no equivalent, so this codebase has exactly
        // one convention and RunLifecycle does the edge detection). For the exit key the
        // level is not merely conventional but required: RunLifecycle measures how long it
        // has been held, and an edge carries no duration.
        val pausePressed = engine.input.isPressed(Key.ESCAPE)
        val exitHeld = engine.input.isPressed(Key.Q)

        lifecycle.update(
            dt = engine.data.deltaTime,
            anyInputPressed = actionPressed,
            runOver = sim.runOver,
            bankedScore = sim.banked,
            cycleUp = cycleUp,
            cycleDown = cycleDown,
            confirmPressed = actionPressed,
            pausePressed = pausePressed,
            exitHeld = exitHeld
        )

        // The deliberate way out of the cabinet, replacing the accidental one that the
        // ALT+ENTER fullscreen binding used to be (see src/main/resources/init.pes).
        //
        // window.close() is the engine's OWN shutdown path, not a shortcut around it: the
        // `exit` console command does exactly this one call and nothing else (verified by
        // disassembling CommandRegistry.registerEngineCommands in pulse-engine-0.13.0.jar).
        // It asks GLFW to close the window, which ends PulseEngineImpl's game loop, which
        // then runs destroy() — onDestroy on this game, and onDestroy on every registered
        // Service. ScoreRepository is registered as a Service precisely so that hook fires,
        // so the leaderboard is saved synchronously on the way out. Deliberately NOT
        // exitProcess(): that would skip all of it and lose the day's scores.
        if (lifecycle.exitRequested)
        {
            Logger.info { "Exit requested from the pause screen" }
            engine.window.close()
        }

        // Latch the pad that opened the briefing. The countdown auto-start fires justStarted
        // on a frame with NO press, and lifecycleEdges.firedPadId is nulled at the top of
        // every frame — so without this the common path binds gameplay to slot 0. See
        // RunLifecycle.justEnteredBriefing, and GamepadScan's "startable, unplayable run".
        if (lifecycle.justEnteredBriefing) activePadId = lifecycleEdges.firedPadId

        if (lifecycle.justStarted)
        {
            // `?:` keeps the value latched when the briefing opened, for the auto-start frame
            // where no edge fired. Every other route here — a press that skipped the
            // briefing, a RUN_OVER retry, a zero-length briefing — has a real edge this
            // frame, so firedPadId wins and the behaviour is identical to before.
            activePadId = lifecycleEdges.firedPadId ?: activePadId
            sim = DiveSim(seed = dailySeed)
            // BEFORE the snap below, not after: snapTo teleports the camera to sim.depth, and
            // a pin applied afterwards would leave the camera at the surface easing 140 m down
            // through the first second of the run. `EnPustTilDepthPinTest` asserts this order.
            applyDepthPin()
            camera.snapTo(sim.depth)
            // Pushed through immediately, for the same reason as in onCreate: this runs on the
            // render clock, so without it the frame drawn right after a restart would use the
            // camera the PREVIOUS run ended at — a full-frame jump from the abyss back to the
            // surface, one frame late. CameraRig.snap is idempotent, so the fixed tick simply
            // writes the same four values again.
            //
            // SNAP rather than apply because this is a teleport: DiveCamera.snapTo just moved
            // the camera from wherever the last run ended to the surface, and the engine would
            // otherwise interpolate across that jump from a snapshot taken in the abyss —
            // smearing the first frame of a fresh run through 150 m of water. It only bites on a
            // frame that ran no fixed step, which is exactly the kind of intermittent that never
            // reproduces on demand. See CameraRig.snap.
            CameraRig.snap(engine, camera.depth)
            DiveLighting.resetAim()

            // Every run opens on the loop's authored first frame rather than wherever the last
            // player left it, which is the same argument as resetAim above and as the fresh
            // DiveSim: nothing about a new run should depend on the previous one. Reset from HERE
            // and not from onFixedUpdate even though the phase is ADVANCED there — `justStarted`
            // is a one-tick flag cleared at the top of the next `lifecycle.update`, i.e. on the
            // render clock, and a frame that happens to run no fixed step would miss it.
            DiverSprite.restartLoop()
        }

        // THIS MUST RUN BEFORE THE justReturnedToIdle BLOCK BELOW — the two flags are not
        // mutually exclusive. RunLifecycle.finishInitials() sets initialsJustCompleted = true
        // and then calls enter(IDLE) WITHOUT resuming (resuming defaults to false), which sets
        // justReturnedToIdle = true in the same call — so on the tick a player finishes their
        // initials, BOTH flags are true
        // at once. `sim` is still the DiveSim that scored this run only as long as this read
        // happens first: reading it after the block below would read a freshly-constructed
        // sim with `banked == 0`, and Leaderboard.isWorthRecording(0) discards the score with
        // no log and no error — every real score silently lost, all day. (This is exactly
        // what shipped in the first cut of this feature; the review that caught it is task-9
        // in .superpowers/sdd/2026-08-21-booth-survival/, and UpdateGameOrderingTest pins the
        // ordering so it cannot regress silently again.)
        if (lifecycle.initialsJustCompleted)
            scoreRepository.registerScore(lifecycle.completedInitials, sim.banked)

        if (lifecycle.justReturnedToIdle)
        {
            // A fresh diver at the surface, not the last player's corpse at 120 m. Same
            // construction and camera-snap order as justStarted above — see that block's
            // comments for why the pin is applied BEFORE the snap, and why this snaps
            // rather than eases (a teleport read as a smear across the water otherwise).
            //
            // MUST RUN AFTER THE initialsJustCompleted BLOCK ABOVE. Finishing initials sets
            // both flags on the same tick (see the comment above), and this block destroys
            // `sim` — reordering it back above the score read would zero every score before
            // it is ever persisted. See UpdateGameOrderingTest.
            sim = DiveSim(seed = dailySeed)
            applyDepthPin()
            camera.snapTo(sim.depth)
            CameraRig.snap(engine, camera.depth)
            DiveLighting.resetAim()
            DiverSprite.restartLoop()
            // Task 6: the next run picks its own pad.
            activePadId = null
        }
    }

    override fun onDestroy() = guard.run(CallbackSites.DESTROY, destroyBody)

    /**
     * The real body. See [guard] for why nothing here may throw past this class.
     *
     * The actual score-saving guarantee on a clean shutdown comes from
     * [ScoreRepository.onDestroy] — it is registered as a [no.njoh.pulseengine.core
     * .service.Service] (see [createGame]) and the engine calls every service's
     * `onDestroy` automatically (verified by decompiling `ServiceManagerImpl`, right
     * after this method returns — see [ScoreRepository]'s class doc for the exact
     * order). This method exists to satisfy that explicit requirement in its own right
     * and to leave a clean, on-site-diagnosable log line distinguishing a graceful
     * shutdown from a crash/power-cut, which this line never gets the chance to log.
     */
    private fun destroyGame()
    {
        Logger.info { "Én Pust Til shutting down cleanly" }
    }

    override fun onRender() = guard.run(CallbackSites.RENDER, renderBody)

    /** The real body. See [guard] for why nothing here may throw past this class. */
    private fun renderGame()
    {
        // NOT `bootFailed` — that is "createGame threw SOMEWHERE", true even for a late,
        // fully recoverable asset-load failure (DiverSprite/RockFace/.../DiveLighting.setup
        // all degrade to a documented fallback and leave `sim`/`scoreRepository` set). The
        // condition that actually matters here is narrower and checked directly:
        // `worldUnusable` is true only when one of those two lateinit fields never got
        // constructed at all, which is the ONE case with no world or HUD left to draw.
        // Reading `sim`/`scoreRepository` any other way below — DiveRenderer.render hands
        // `sim` straight to the GPU a few lines down — would throw EVERY frame from here on,
        // be swallowed by CallbackGuard, and draw NOTHING: a black-but-alive cabinet,
        // indistinguishable on screen from a machine that is simply off. Checked FIRST,
        // before any lateinit access, and returns rather than falling through.
        //
        // A late, recoverable createGame failure instead falls through to the ordinary
        // drawIdleScreen path below, where drawBoothStatusLine passes the live `bootFailed`
        // value into BoothStatus.line — so it is still named on screen, just on a cabinet
        // that is still playable, which is the brief's original design for this line.
        if (worldUnusable)
        {
            drawBootFailedScreen(engine.gfx.getSurfaceOrDefault("hud"))
            return
        }

        // World: lit by GlobalIlluminationSystem, which multiplies mainSurface by the
        // computed light map — this is what makes the Abyss genuinely dark. Drawn in METRES
        // through engine.gfx.mainCamera, which CameraRig wrote on the last fixed tick; the
        // renderer takes that camera so the rect it walks is the rect the frame is drawn with.
        val worldCamera = engine.gfx.mainCamera

        // The diver's normal map goes to GI's own normal-map surface, so its renderer is fetched
        // here and handed down rather than reached for inside DiveRenderer — which deliberately
        // holds no engine handle of its own (see its class doc), exactly as the world camera and
        // DiveLighting's surfaces already work.
        //
        // NormalMapRenderer is attached to that surface by GlobalIlluminationSystem itself
        // (:128-138), BEFORE the `EntityRenderer` early-out at :184 — so it exists even though
        // this game authors no scene entities at all. Null-safe anyway: a diver drawn flat is a
        // far better failure than no diver.
        val normalMaps = engine.gfx
            .getSurface(GlobalIlluminationSystem.GI_NORMAL_MAP)
            ?.getRenderer<NormalMapRenderer>()

        // A diver drawn flat is a perfectly good frame and an entirely silent one, which is this
        // project's recurring failure mode — so say it once if the renderer is ever missing.
        // Looked up per frame rather than cached in onCreate because the engine rebuilds surfaces
        // on a window change, and a cached renderer would then be a handle to a dead surface.
        if (normalMaps == null && !warnedAboutNormalMaps)
        {
            warnedAboutNormalMaps = true
            Logger.warn { "No ${GlobalIlluminationSystem.GI_NORMAL_MAP} NormalMapRenderer — the diver will be drawn without normals" }
        }

        // ONE heading, read once, handed to both draws. DiveLighting owns it (it is the torch's
        // smoothed aim, holding its last value when the diver coasts to a stop); the body is drawn
        // rotated to the same number so the diver faces where his light points. Deriving it twice
        // from sim.vx/vy is the shape of the bug 6ea1f53 fixed — see DiveLighting.beamHeadingDegrees.
        val aimDegrees = DiveLighting.beamHeadingDegrees

        // THE WATERLINE, HANDED TO THE POST PASS THAT REPAIRS `main`'s ALPHA BELOW IT.
        //
        // Taken from THIS frame's matrix, the same way and for the same reason the HUD's diver
        // anchor is below: `worldPosToScreenPos` multiplies by `mainCamera`'s viewMatrix, built
        // once in `gfx.initFrame` before any of our code ran, and the effect runs at the end of
        // this same frame — so the gate cannot be a frame off from the water it is gating. Any
        // other derivation (from `DiveCamera.depth`, say) reads camera state from a different
        // point in the frame, which is the drift `CLAUDE.md` names as the remaining risk here.
        //
        // The x is 0 because the gate is a horizontal line: the camera has no roll, so every world
        // x at `GATE_DEPTH` has the same screen y. The returned Vector2f is the camera's shared
        // instance (Camera.kt:85), clobbered by the next call — `.y` is read out immediately.
        //
        // `mainSurface.config.height`, not `engine.window.height`: `config` is what this surface's
        // own projection was built from, so it is the only value that cannot disagree with what is
        // being rasterised. See `CLAUDE.md`'s platform constraints and `6ea1f53`.
        opaqueWater?.let { effect ->
            val gateScreenY = worldCamera.worldPosToScreenPos(0f, OpaqueWaterEffect.GATE_DEPTH).y
            effect.gate = OpaqueWaterEffect.gateUv(gateScreenY, engine.gfx.mainSurface.config.height.toFloat())
        }

        // THE SKY, FIRST AND ON ITS OWN SURFACE. Order within this method does not decide what
        // ends up in front of what — that is the surfaces' zOrder, and the sky's puts it behind
        // the world — but it is drawn first anyway so the file reads back to front like the
        // frame does. It is in world metres on the shared camera, so it needs nothing from here
        // but the camera itself, and it issues no draws at all once the diver is below about
        // 10 m. See `render/Sky.kt`.
        Sky.render(engine.gfx.getSurfaceOrDefault(Sky.SURFACE_NAME), worldCamera)

        DiveRenderer.render(engine.gfx.mainSurface, sim, worldCamera, normalMaps, aimDegrees)

        // Lights: immediate-mode drawLight calls, also in metres, onto GI's local scene
        // surface — which is created with `camera = engine.gfx.mainCamera`, the SAME object,
        // so a light and the thing it lights go through one matrix and cannot drift. Still
        // issued from onRender: GiSceneRenderer's batch must be filled before gfx.drawFrame.
        // See DiveLighting's class doc for what changed and what did not.
        //
        // Handed the SAME camera reference DiveRenderer just got, deliberately, rather than
        // letting DiveLighting fetch it: a light and the square it sits on are then culled
        // against one visible rect, and only this file has to name engine.gfx.mainCamera at all
        // (see MainCameraOwnershipTest, whose allow-list is exact set equality).
        DiveLighting.render(engine, sim, worldCamera)

        // THE MARINE SNOW IS NOT DRAWN HERE. It is on `main`, issued last inside
        // DiveRenderer.render (see the Motes.render call there), so GI's multiply darkens a mote
        // with the water, the rock and the pearls rather than leaving it glowing in the Abyss.
        //
        // It DID have a surface of its own, and this comment used to describe that draw. Both the
        // call and the surface are gone; the comment outlived them, which is exactly the failure
        // mode Motes' own class doc ("THE SURFACE: THEY ARE ON `main`, AND THAT WAS RESOLVED THE
        // HARD WAY") was written to prevent. MotesTest pins it: `the motes are drawn onto the
        // world surface, so GI darkens them with everything else`. Do not re-add a draw here.

        // HUD: its own surface, its own screen-pixel camera, composited on top unaffected by
        // GI — see the comment in onCreate for why it cannot share mainSurface. What it shows
        // depends on the lifecycle state: the numeric HUD (BANKED/clock/air/depth tape) only
        // makes sense once a run actually exists, so IDLE gets its own simple attract text.
        val hud = engine.gfx.getSurfaceOrDefault("hud")

        // The HUD's own size, from the surface it is drawn on rather than from engine.window.
        // The two are the same number and CameraInvariants rule 1 exists to notice if they
        // ever stop being — but `config` is what this surface's projection was actually built
        // from (SurfaceImpl.init:46-47), so it is the only value that cannot disagree with
        // what is being rasterised. Reading the window instead was one half of the mechanism
        // that shipped.
        val w = hud.config.width.toFloat()
        val h = hud.config.height.toFloat()

        // THE DIVER'S ANCHOR ON THE HUD SURFACE — the crux of the world-coordinate migration,
        // and the exact spot the shipped ultrawide bug would come back.
        //
        // worldPosToScreenPos multiplies by mainCamera's viewMatrix: the SAME matrix the world
        // surface is being drawn with this frame, built once in gfx.initFrame
        // (GraphicsImpl.kt:111) before any of our code ran. Deriving this any other way — most
        // temptingly from `camera.depth`, which is what Hud used to do — reads camera state
        // from a different point in the frame and puts the air ring one frame ahead of the
        // diver whenever the camera is easing. That is the drift DiveLighting's class doc
        // records killing on the world side.
        //
        // The returned Vector2f is a SHARED instance (Camera.kt:85), reused by the next call.
        // No allocation, but the second call clobbers the first — hence both components are
        // read out into locals BEFORE asking again.
        val anchor = worldCamera.worldPosToScreenPos(sim.x, sim.depth)
        val diverX = anchor.x
        val diverY = anchor.y

        // Pixels per metre as the screen distance between two world points one metre apart,
        // rather than as `mainCamera.scale.x`. Identical today, and exactly right on the one
        // frame a resize is being interpolated through, which the raw scale would not be.
        val pixelsPerMetre = worldCamera.worldPosToScreenPos(sim.x + 1f, sim.depth).x - diverX

        when (lifecycle.state)
        {
            RunLifecycleState.IDLE -> drawIdleScreen(hud, w, h)

            RunLifecycleState.BRIEFING -> drawBriefingScreen(hud, w, h)

            RunLifecycleState.PLAYING ->
            {
                Hud.render(hud, sim, diverX, diverY, pixelsPerMetre, w, h, aimDegrees)

                // THE VENTS' "O2" LABELS, AND WHY THEY ARE ISSUED FROM HERE.
                //
                // They are text, so they are on the HUD surface (GI multiplies `main`, and a
                // "faint" label at the mercy of the torch is not a chosen value) — but they are
                // pinned to WORLD positions, so they need the camera and the pixels-per-metre
                // that were taken from THIS frame's matrix a few lines above. This method is the
                // only place both of those exist at once. See VentLabel's class doc.
                //
                // Repeated per state alongside Hud.render rather than hoisted above the `when`,
                // and for the same reason Hud.render is: IDLE and BRIEFING draw an attract screen
                // over live water, and a scatter of labels behind the title, the call to action
                // and the leaderboard is noise competing with the one thing those screens exist
                // to say. The label is part of the in-run readout, so it appears exactly where
                // the in-run readout does.
                VentLabel.render(hud, sim, worldCamera, pixelsPerMetre, h)

                Hud.renderControlLegend(hud, hintLegend, w, h)
            }

            // The screen underneath is drawn FIRST and in full, then dimmed by the pause
            // screen's own scrim. A paused run keeps its HUD — a stopped clock and a full
            // ring of bubbles behind the scrim is the clearest possible statement that the
            // run is being held, not ended — and the attract variant keeps its leaderboard,
            // so the queue can still read the board while a technician has the menu open.
            RunLifecycleState.PAUSED ->
            {
                if (lifecycle.pausedFromIdle) drawIdleScreen(hud, w, h)
                else
                {
                    Hud.render(hud, sim, diverX, diverY, pixelsPerMetre, w, h, aimDegrees)
                    VentLabel.render(hud, sim, worldCamera, pixelsPerMetre, h)
                    // Included deliberately. drawPauseScreen's own comment says a complete HUD
                    // behind the scrim - "a stopped clock and a full ring of bubbles" - is the
                    // clearest statement that the run is being HELD, not ended. A legend that
                    // vanished on pause would contradict that.
                    Hud.renderControlLegend(hud, hintLegend, w, h)
                }
                drawPauseScreen(hud, w, h)
            }

            RunLifecycleState.RUN_OVER ->
            {
                Hud.render(hud, sim, diverX, diverY, pixelsPerMetre, w, h, aimDegrees)
                VentLabel.render(hud, sim, worldCamera, pixelsPerMetre, h)
                drawRunOverScreen(hud, w, h)
            }

            RunLifecycleState.ENTER_INITIALS ->
            {
                Hud.render(hud, sim, diverX, diverY, pixelsPerMetre, w, h, aimDegrees)
                VentLabel.render(hud, sim, worldCamera, pixelsPerMetre, h)
                drawInitialsEntryScreen(hud, w, h)
            }
        }

        // Dev-only diagnostic overlay for finding 4 (the arcade encoder risk): completely
        // inert without EPT_DEV — this call is the ONLY thing standing between "shipped
        // build" and "overlay drawn", and it is a single boolean branch before any
        // allocation or draw call happens. See renderGamepadOverlay's doc.
        if (devMode)
        {
            renderGamepadOverlay(hud, w, h)
            checkCameraInvariants()
        }
    }

    /**
     * The runtime half of the world-offset-from-HUD guard — the half that runs on a real
     * framebuffer. See [CameraInvariants] for the mechanism and for what each rule catches.
     *
     * WHY IT LIVES IN onRender rather than onUpdate: the numbers it reads are recomputed once
     * per frame in `GraphicsImpl.initFrame` (:112, `camera.updateWorldPositions`), which runs at
     * the top of the frame, before any of our code. Asking here means asking about the matrix
     * this frame is actually being drawn with, which is the whole point of consulting the engine
     * rather than recomputing our own transform and comparing it to itself.
     *
     * ONCE PER SECOND, not per frame, because a violation is a persistent structural fault (a
     * camera scaled by the wrong factor stays wrong until something resizes again) and 60
     * identical WARN lines a second would bury the log it is trying to be found in.
     *
     * WARN, not DEBUG, for the same reason [logGamepadDiagnostics] warns: application.cfg sets
     * `logLevel = WARN` at the booth, and if anyone ever runs a dev build on the cabinet this
     * has to survive that level to be worth having.
     *
     * `topLeftWorldPosition` / `bottomRightWorldPosition` are two DISTINCT `Vector2f` fields on
     * `Camera` (Camera.kt:32-33), not the single shared return buffer `worldPosToScreenPos`
     * hands back (:85) — so unlike that method, reading one does not clobber the other. Their
     * components are copied into locals anyway, which is free and removes the question.
     */
    private fun checkCameraInvariants()
    {
        secondsSinceCameraCheck += engine.data.deltaTime
        if (secondsSinceCameraCheck < 1f)
            return
        secondsSinceCameraCheck = 0f

        val topLeft = engine.gfx.mainCamera.topLeftWorldPosition
        val worldTop = topLeft.y
        val worldLeft = topLeft.x
        val bottomRight = engine.gfx.mainCamera.bottomRightWorldPosition
        val worldBottom = bottomRight.y
        val worldRight = bottomRight.x

        val config = engine.gfx.mainSurface.config
        CameraInvariants.violations(
            windowWidth = engine.window.width,
            windowHeight = engine.window.height,
            surfaceWidth = config.width,
            surfaceHeight = config.height,
            worldTop = worldTop,
            worldBottom = worldBottom,
            worldLeft = worldLeft,
            worldRight = worldRight
        ).forEach { Logger.warn { "camera invariant violated — $it" } }
    }

    /**
     * Placeholder attract screen: engine default font only, no assets. Also the booth's
     * whole social hook — the leaderboard the queue can see before they play — so it
     * draws today's top scores under the title. Scoped to TODAY's seed only (see
     * ScoreRepository.topN's default), which is what gives day two a fresh, empty board
     * for free.
     */
    private fun drawIdleScreen(hud: Surface, w: Float, h: Float)
    {
        // The world (DiveRenderer) still renders behind this surface while IDLE — the
        // attract screen shows the live shallows, not a static image — so this text sits
        // over the same bright-to-dark gradient as everything else. Outlined for the same
        // reason as the rest of the HUD (see render/Hud.kt, render/Draw.kt).
        //
        // WHERE things go is [AttractLayout]'s problem, not this method's, and it is not a
        // free choice: the diver is pinned to 0.40 of screen height with a large glow, and
        // the waterline sits on top of it whenever the diver is at the surface — which is
        // exactly where a fresh attract screen starts. See AttractLayout's doc for the
        // collision this arrangement fixes, and AttractScreenTest for the assertion that
        // stops it coming back. The sign goes in the dark water above the diver; the
        // leaderboard goes below it.
        hud.drawTextWithOutline(
            ScreenText.TITLE,
            w * 0.5f, h * AttractLayout.TITLE_Y,
            h * AttractLayout.TITLE_FONT, h, Color.WHITE, xOrigin = 0.5f
        )
        hud.drawTextWithOutline(
            hintPressStart,
            w * 0.5f, h * AttractLayout.PRESS_START_Y,
            h * AttractLayout.PRESS_START_FONT, h, Color.WHITE, xOrigin = 0.5f
        )
        drawLeaderboard(hud, w, h)
        drawBoothStatusLine(hud, w, h)
    }

    /**
     * Bottom-left, small and dim: findable by an attendant looking for it, ignorable by a
     * player who is not. NOT EPT_DEV gated, unlike the red unmapped-joystick overlay — a
     * fault nobody at the booth can see is the exact problem this line exists for. See
     * [BoothStatus] for what each segment means and why.
     *
     * `bootFailed` here is [EnPustTil.bootFailed] — "createGame threw somewhere" — NOT the
     * narrower [worldUnusable] that routes [renderGame] to [drawBootFailedScreen] instead of
     * here. This is therefore the path a LATE, recoverable createGame failure is reported
     * through: the run is fully playable (an asset fell back to a placeholder, say), but the
     * fault still deserves the loudest wording this line has, because a technician needs to
     * know one thing broke even if nothing visible did.
     */
    private fun drawBoothStatusLine(hud: Surface, w: Float, h: Float)
    {
        // A fraction of screen HEIGHT, not a pixel count and not width — engine.window.*
        // returns physical framebuffer pixels, and the booth display's aspect ratio is not
        // known in advance (CLAUDE.md, platform constraints). Geometry lives in
        // AttractLayout, not as inline numbers here — see that object's STATUS_FONT/STATUS_Y
        // doc for the descender-clipping margin baked into STATUS_Y.
        val fontSize = h * AttractLayout.STATUS_FONT

        // Explicit colour, always — this used to be the only attract-screen text with no
        // setDrawColor of its own, so it silently inherited whatever the PREVIOUS draw call
        // left set: `cold` from drawLeaderboard's last row on a day with scores, or
        // Color.WHITE from PRESS_START on an empty board. Same line, two different colours
        // depending on how many people had played that morning. A dim, neutral grey reads as
        // "diagnostic text", distinct from both the warm leaderboard rows and the alarming
        // red of drawBootFailedScreen.
        //
        // The FLOAT overload, not `Color(0.6f, 0.6f, 0.65f)` — `Color` is a plain class with
        // four mutable float fields (decompiled: not a Kotlin value class), so constructing
        // one heap-allocates, and this call runs every attract-screen frame for however many
        // hours the cabinet idles between plays. CLAUDE.md's no-per-frame-allocation
        // exemption is HUD TEXT FORMATTING (BoothStatus.line's StringBuilder) — it does not
        // cover a colour object. Every other setDrawColor call in this file either reuses a
        // singleton (Color.RED, Color.GREEN) or uses this same float overload.
        hud.setDrawColor(0.6f, 0.6f, 0.65f, 1f)
        hud.drawText(
            BoothStatus.line(
                seed = dailySeed,
                unmappedPads = unmappedGamepadCount(),
                stuckSources = lifecycleEdges.stuckCount,
                chatterSources = lifecycleEdges.chatterCount,
                callbackFailures = guard.totalFailures,
                lastFailureSite = guard.lastFailureSite,
                bootFailed = bootFailed
            ),
            x = fontSize,
            y = h * AttractLayout.STATUS_Y,
            fontSize = fontSize
        )
    }

    /**
     * The ENTIRE frame whenever [worldUnusable] is true. There is no `DiveSim` to draw a
     * world or a numeric HUD around — see [renderGame]'s early return, which is what routes
     * here instead of falling through into code that would throw on `sim`/`scoreRepository`
     * every frame. Deliberately the only thing on screen, not one line among many: this is
     * the single case a technician cannot walk away from, and everything else this status
     * line reports is a degraded-but-still-running booth.
     */
    private fun drawBootFailedScreen(hud: Surface)
    {
        val w = hud.config.width.toFloat()
        val h = hud.config.height.toFloat()
        val fontSize = h * 0.03f

        hud.setDrawColor(Color.RED)
        hud.drawText(
            BoothStatus.line(
                seed = dailySeed,
                unmappedPads = unmappedGamepadCount(),
                stuckSources = lifecycleEdges.stuckCount,
                chatterSources = lifecycleEdges.chatterCount,
                // Suppressed, not passed through from `guard`: with `sim` unset,
                // onFixedUpdate/onUpdate throw on EVERY subsequent frame too (both read
                // `sim` the moment lifecycle logic touches it), so guard.totalFailures
                // climbs at 60-120 Hz and guard.lastFailureSite reads "onUpdate" or
                // "onFixedUpdate" — not "onCreate", where the fault actually happened. Left
                // live, the FAULT segment would flicker a different, ever-growing digit
                // count every frame and misattribute the site, right next to the one segment
                // that already says everything an attendant needs ("BOOT FAILED"). 0/null
                // omits it entirely — see BoothStatus.line's callbackFailures > 0 gate.
                callbackFailures = 0,
                lastFailureSite = null,
                bootFailed = true
            ),
            x = w * 0.05f,
            y = h * 0.5f,
            fontSize = fontSize
        )
    }

    private fun drawLeaderboard(hud: Surface, w: Float, h: Float)
    {
        val top = scoreRepository.topN(AttractLayout.LEADERBOARD_SIZE)
        if (top.isEmpty()) return

        val fontSize = h * AttractLayout.ROW_FONT
        val centreX = w * 0.5f
        val cold = Color(0.75f, 0.85f, 1f)

        hud.drawTextWithOutline(
            ScreenText.LEADERBOARD_HEADING,
            centreX, h * AttractLayout.HEADING_Y,
            fontSize, h, cold, xOrigin = 0.5f
        )

        // Rank hard against the left edge of the row, initials on the centre line, score
        // hard against the right edge — so the block is symmetric about the same centreX
        // the heading above is centred on, without needing to know how wide any glyph is.
        // See AttractLayout.ROW_HALF_SPAN for what the three unrelated width fractions this
        // replaces were doing wrong. Left-aligning the rank and right-aligning the score
        // also keeps both columns aligned down the board — "8." under "1.", units under
        // units — which three-digit and five-digit scores in the same list otherwise lose.
        top.forEachIndexed { i, entry ->
            val y = h * AttractLayout.rowY(i)
            hud.drawTextWithOutline("${i + 1}.", AttractLayout.rankX(centreX, h), y, fontSize, h, cold, xOrigin = 0f)
            hud.drawTextWithOutline(entry.initials, AttractLayout.initialsX(centreX), y, fontSize, h, cold, xOrigin = 0.5f)
            hud.drawTextWithOutline("${entry.score}", AttractLayout.scoreX(centreX, h), y, fontSize, h, cold, xOrigin = 1f)
        }
    }

    private fun drawRunOverScreen(hud: Surface, w: Float, h: Float)
    {
        // A run can end at any depth, so this can land anywhere from bright shallows to
        // near-black abyss — outlined for the same reason as the rest of the HUD.
        hud.drawTextWithOutline(
            ScreenText.runOver(sim.banked),
            w * 0.5f, h * 0.5f,
            h * 0.04f, h, Color.WHITE, xOrigin = 0.5f
        )
        hud.drawTextWithOutline(
            hintPlayAgain,
            w * 0.5f, h * 0.5f + h * 0.045f,
            h * 0.022f, h, Color.WHITE, xOrigin = 0.5f
        )
    }

    /**
     * The pause / exit screen (Esc). Wording and geometry are [ScreenText]'s and
     * [PauseLayout]'s problems; this method only issues the draw calls.
     *
     * Every solid rectangle here goes through [render.fillRect] and never `drawQuad` —
     * `drawQuad` renders nothing at all on macOS and, more to the point, still renders
     * nothing in the shipped Windows `.exe`, which keeps the engine's stock shaders (see
     * render/Draw.kt and the shader-override block in build.gradle.kts). A pause screen whose
     * scrim and progress bar silently failed to draw would be discovered in front of a queue.
     */
    private fun drawPauseScreen(hud: Surface, w: Float, h: Float)
    {
        // Full-screen scrim. Authored alpha, not displayed alpha: the HUD surface stores
        // alpha squared, so asking for 0.72 directly would land near 0.5 (Hud's doc has the
        // measurement). This surface is not relit by global illumination, so what is authored
        // is what is shown — there is no later pass to rescue a scrim that came out too weak.
        hud.setDrawColor(0f, 0f, 0f, Hud.authoredAlphaFor(PauseLayout.SCRIM_ALPHA))
        hud.fillRect(0f, 0f, w, h)

        val centreX = w * 0.5f
        val fromIdle = lifecycle.pausedFromIdle

        // Outlined like the rest of the HUD: the scrim darkens the world but does not
        // flatten it, and this text can land over a bright Shallows waterline.
        hud.drawTextWithOutline(
            if (fromIdle) ScreenText.MENU_TITLE else ScreenText.PAUSED_TITLE,
            centreX, h * PauseLayout.TITLE_Y,
            h * PauseLayout.TITLE_FONT, h, Color.WHITE, xOrigin = 0.5f
        )
        hud.drawTextWithOutline(
            if (fromIdle) ScreenText.MENU_RESUME_HINT else ScreenText.PAUSE_RESUME_HINT,
            centreX, h * PauseLayout.RESUME_Y,
            h * PauseLayout.HINT_FONT, h, Color.WHITE, xOrigin = 0.5f
        )
        hud.drawTextWithOutline(
            ScreenText.EXIT_HINT,
            centreX, h * PauseLayout.EXIT_Y,
            h * PauseLayout.HINT_FONT, h, Color.WHITE, xOrigin = 0.5f
        )

        // The exit-hold bar: an empty track always, plus a fill that grows while the key is
        // down. Drawn unconditionally rather than only while held, so the affordance is
        // visible before anyone touches anything — an empty track under "HOLD Q to exit" is
        // what tells a technician the key wants holding rather than pressing.
        val barX = PauseLayout.barX(centreX, h)
        val barY = h * PauseLayout.BAR_Y
        val barHeight = h * PauseLayout.BAR_HEIGHT

        hud.setDrawColor(1f, 1f, 1f, Hud.authoredAlphaFor(0.25f))
        hud.fillRect(barX, barY, PauseLayout.barTrackWidth(h), barHeight)

        hud.setDrawColor(1f, 0.85f, 0.3f, Hud.authoredAlphaFor(0.95f))
        hud.fillRect(barX, barY, PauseLayout.barFillWidth(lifecycle.exitHoldProgress, h), barHeight)
    }

    /**
     * The pre-run briefing: what the three controls do, plus the one rule the game never
     * otherwise states. Drawn over a darkened live world; see [BriefingLayout] for why this
     * screen has a scrim and the attract screen does not.
     */
    private fun drawBriefingScreen(hud: Surface, w: Float, h: Float)
    {
        // Authored alpha, not displayed alpha - this surface stores alpha squared, so asking
        // for 0.55 directly would land near 0.3. See Hud's doc for the measurement.
        hud.setDrawColor(0f, 0f, 0f, Hud.authoredAlphaFor(BriefingLayout.SCRIM_ALPHA))
        hud.fillRect(0f, 0f, w, h)

        val centreX = w * 0.5f
        val gap = h * BriefingLayout.COLUMN_GAP

        hud.drawTextWithOutline(
            ScreenText.BRIEFING_TITLE,
            centreX, h * BriefingLayout.TITLE_Y,
            h * BriefingLayout.TITLE_FONT, h, Color.WHITE, xOrigin = 0.5f
        )

        // Token right-aligned, verb left-aligned - INWARD, unlike the leaderboard's outward
        // columns. See BriefingLayout.COLUMN_GAP.
        drawBriefingRow(hud, 0, ControlHints.swim(arcadeHints), ScreenText.BRIEFING_VERB_SWIM, centreX, gap, h)
        drawBriefingRow(hud, 1, ControlHints.kick(arcadeHints, gamepadButtonLabel(kickButton)), ScreenText.BRIEFING_VERB_KICK, centreX, gap, h)
        drawBriefingRow(hud, 2, ControlHints.bleed(arcadeHints, gamepadButtonLabel(bleedButton)), ScreenText.BRIEFING_VERB_BLEED, centreX, gap, h)

        // Amber, not white: this is the one thing a player must know that nothing else on
        // screen ever says. Same literal drawPauseScreen uses for its exit bar - deliberately
        // NOT Hud.noReturnMark, which is public but is a TAPE colour with its own authored
        // alpha and a different RGB.
        hud.drawTextWithOutline(
            ScreenText.BRIEFING_RULE,
            centreX, h * BriefingLayout.RULE_Y,
            h * BriefingLayout.RULE_FONT, h, 1f, 0.85f, 0.3f, 1f, xOrigin = 0.5f
        )

        // Suppressed under EPT_BRIEFING_HOLD: briefingSeconds is infinite there, and
        // Float.POSITIVE_INFINITY.toInt() is Int.MAX_VALUE, so this would read
        // "STARTING IN 2147483647" - on the one screen the flag exists to photograph.
        if (lifecycle.briefingAutoStarts)
        {
            val seconds = ceil(lifecycle.briefingCountdownSeconds).toInt()
            hud.drawTextWithOutline(
                ScreenText.briefingCountdown(seconds),
                centreX, h * BriefingLayout.COUNTDOWN_Y,
                h * BriefingLayout.COUNTDOWN_FONT, h, Color.WHITE, xOrigin = 0.5f
            )
        }

        // Only once a press would actually do something. The hint appearing IS the
        // affordance, so the screen never invites a press that does nothing.
        if (lifecycle.briefingSkippable)
        {
            hud.drawTextWithOutline(
                hintBriefingSkip,
                centreX, h * BriefingLayout.SKIP_Y,
                h * BriefingLayout.SKIP_FONT, h, Color.WHITE, xOrigin = 0.5f
            )
        }
    }

    private fun drawBriefingRow(hud: Surface, index: Int, token: String, verb: String, centreX: Float, gap: Float, h: Float)
    {
        val y = h * BriefingLayout.rowY(index)
        val font = h * BriefingLayout.ROW_FONT
        hud.drawTextWithOutline(token, centreX - gap, y, font, h, Color.WHITE, xOrigin = 1f)
        hud.drawTextWithOutline(verb, centreX + gap, y, font, h, Color.WHITE, xOrigin = 0f)
    }

    /**
     * Three-letter arcade initials entry — see design spec §12 ("never a form field")
     * and RunLifecycle's ENTER_INITIALS doc for when this is offered. The current slot
     * is bracketed so it reads clearly even with the engine's default font and no
     * cursor/caret asset.
     */
    private fun drawInitialsEntryScreen(hud: Surface, w: Float, h: Float)
    {
        hud.drawTextWithOutline(
            ScreenText.newScore(sim.banked),
            w * 0.5f, h * 0.46f,
            h * 0.032f, h, Color.WHITE, xOrigin = 0.5f
        )

        hud.drawTextWithOutline(
            ScreenText.initialsSlots(lifecycle.currentInitials, lifecycle.currentInitialsSlot),
            w * 0.5f, h * 0.54f,
            h * 0.06f, h, Color.WHITE, xOrigin = 0.5f
        )

        hud.drawTextWithOutline(
            hintInitialsHelp,
            w * 0.5f, h * 0.6f,
            h * 0.02f, h, Color.WHITE, xOrigin = 0.5f
        )
    }

    /**
     * Stick is a full 2D swim direction; A boosts whichever way you point.
     * Deadzone is applied so a drifting analogue stick does not stop the
     * passive sink, which is the game's baseline state.
     */
    private fun readInput(): DiveInput
    {
        // Gameplay follows the pad that STARTED the run (activePadId, captured on
        // lifecycle.justStarted), not slot 0 — see selectGameplayPad's doc for the booth
        // failure ("startable, unplayable run") a stray HID in slot 0 used to cause.
        val pads = engine.input.gamepads
        gamepadIdBuffer.clear()
        for (i in pads.indices) gamepadIdBuffer.add(pads[i].id)
        val chosenId = selectGameplayPad(gamepadIdBuffer, activePadId)
        // Indexed, not `pads.firstOrNull { it.id == chosenId }` — that resolves through
        // the Iterable<T> extension and allocates one Iterator per call. See
        // gamepadIdBuffer's doc for the matching fix on the id list this reads.
        var pad: Gamepad? = null
        for (i in pads.indices) if (pads[i].id == chosenId) { pad = pads[i]; break }
        val padX = pad?.getAxis(GamepadAxis.LEFT_X)?.deadzone() ?: 0f
        val padY = pad?.getAxis(GamepadAxis.LEFT_Y)?.deadzone() ?: 0f

        val keyX = axis(Key.LEFT, Key.RIGHT)
        val keyY = axis(Key.UP, Key.DOWN)

        return DiveInput(
            horizontal = if (padX != 0f) padX else keyX,
            vertical   = if (padY != 0f) padY else keyY,
            kick       = (pad?.isPressed(kickButton) ?: false) || engine.input.isPressed(Key.Z),
            bleed      = (pad?.isPressed(bleedButton) ?: false) || engine.input.isPressed(Key.X)
        )
    }

    /**
     * Rebuilds the cached hint strings if — and only if — the connected input hardware changed.
     *
     * Called once per frame from [updateGame]. `gamepads.isNotEmpty()` allocates nothing:
     * `Input.getGamepads()` returns a `java.util.List`, so this resolves to the inline
     * `Collection<T>.isNotEmpty()`. That was checked rather than assumed, because
     * `gamepadIdBuffer` and `GamepadScan` both document the OPPOSITE result for `firstOrNull { }`
     * and `forEach` on the same list.
     *
     * ONE-WAY LATCH ([arcadeEverDetected]): once real hardware detection has found an arcade
     * controller, it stays "found" for the rest of the process, even if the reading that
     * produced it later flips back to false. A generic USB arcade encoder is a realistic booth
     * failure mode for a LOOSE CONNECTOR, not just an absent one — see
     * `LifecycleInputEdges.chatterCount`, which exists for exactly this — and without the latch
     * a chattering connection would make `engine.input.gamepads.isNotEmpty()` flip every frame,
     * rebuilding the hint cache at 60 Hz and strobing the attract screen between `PRESS START`
     * and `PRESS SPACE` in front of the queue. A booth has no keyboard, so once a pad has
     * genuinely been seen, staying on arcade labels is strictly correct. This is a SEPARATE
     * field from [arcadeHints] deliberately: [arcadeHints] defaults to `true` at construction
     * (a one-frame bias — see its own doc) and gets corrected by the first real reading here, so
     * folding the latch into [arcadeHints] itself would make that correction never happen and
     * pin every dev machine that has never owned a pad to permanent arcade labels too.
     * [arcadeEverDetected] starts `false` and is unaffected by that bias.
     */
    private fun refreshControlHints()
    {
        arcadeEverDetected = arcadeEverDetected ||
            engine.input.gamepads.isNotEmpty() || unmappedGamepadCount() > 0
        val arcade = arcadeEverDetected
        if (arcade == arcadeHints && hintPlayAgain.isNotEmpty()) return
        arcadeHints = arcade
        rebuildControlHints()
    }

    private fun rebuildControlHints()
    {
        val startLabel = gamepadButtonLabel(restartButton)
        hintPressStart = ControlHints.pressStart(arcadeHints, startLabel)
        hintPlayAgain = ControlHints.playAgain(arcadeHints, startLabel)
        hintInitialsHelp = ControlHints.initialsHelp(arcadeHints, startLabel)
        hintLegend = ControlHints.legend(arcadeHints, gamepadButtonLabel(kickButton), gamepadButtonLabel(bleedButton))
        hintBriefingSkip = hintPressStart + ScreenText.BRIEFING_SKIP_SUFFIX
    }

    /**
     * Initials-entry cycling input: stick up/down (any connected gamepad — same "any
     * button" reasoning as [LifecycleInputEdges], since this is menu navigation, not
     * gameplay) OR the UP/DOWN keys, so keyboard development keeps working. Level
     * readings, same as [readInput] — [score.InitialsEntry] does its own edge-tracking.
     */
    private fun readInitialsCycle(): Pair<Boolean, Boolean>
    {
        val padUp = engine.input.gamepads.any { it.getAxis(GamepadAxis.LEFT_Y) < -stickDeadzone }
        val padDown = engine.input.gamepads.any { it.getAxis(GamepadAxis.LEFT_Y) > stickDeadzone }
        val up = padUp || engine.input.isPressed(Key.UP)
        val down = padDown || engine.input.isPressed(Key.DOWN)
        return up to down
    }

    private fun axis(negative: Key, positive: Key) = when
    {
        engine.input.isPressed(negative) -> -1f
        engine.input.isPressed(positive) ->  1f
        else -> 0f
    }

    private fun Float.deadzone() = if (kotlin.math.abs(this) < stickDeadzone) 0f else this

    /**
     * Finding 4 (highest-risk unknown in the project): the engine only lists a device in
     * `engine.input.gamepads` if GLFW considers it a "gamepad" — i.e. it has an
     * SDL_GameControllerDB mapping (verified: the engine's `InputImpl.pollEvents` bytecode
     * gates entry on `glfwJoystickPresent(i) && glfwJoystickIsGamepad(i)`). A generic
     * "zero-delay" arcade USB encoder very often has NO such mapping — it shows up fine at
     * the OS level, but is completely invisible to `engine.input.gamepads`, silently. If
     * that turns out to be the booth's encoder, the stick and both buttons do nothing and
     * there is no error anywhere in this game's own code, because as far as this game can
     * see, no gamepad exists.
     *
     * This logs once at boot, going straight to LWJGL's raw GLFW joystick API — which is
     * reachable because pulse-engine ships as a fat jar with `org.lwjgl.glfw.GLFW` on our
     * compile classpath (already used elsewhere in this codebase, see ScreenshotEffect) —
     * rather than only through the engine's already-filtered `gamepads` list. That is the
     * one thing that can tell "nothing plugged in" apart from "plugged in but unmapped":
     *   - engine.input.gamepads.size  -> devices the game can actually read
     *   - raw joysticks present        -> devices GLFW/the OS sees at all
     *   - (raw present) - (raw gamepad-mapped) -> devices invisible to this game RIGHT NOW
     *
     * WARN, not INFO — the same defect fixed twice already in this file (the resolved
     * button map, the daily-seed line): application.cfg's booth default is
     * `logLevel = WARN`, which filters INFO out entirely. A correctly-mapped encoder is the
     * *success* path here, and it used to log nothing at all, which meant this diagnostic's
     * one useful case for an unattended-booth log — "what did the encoder enumerate as,
     * once, at boot" — was invisible unless something had already gone wrong. This is once
     * per boot, so there is no per-frame noise cost to raising it.
     */
    private fun logGamepadDiagnostics()
    {
        val recognised = engine.input.gamepads
        Logger.warn {
            "GAMEPAD DIAGNOSTIC: engine.input.gamepads = ${recognised.size} " +
            "(ids=${recognised.map { it.id }})"
        }

        // This loop still walks the raw GLFW range itself, rather than calling
        // unmappedGamepadCount(), because it does something that function deliberately does
        // not: it logs EACH joystick individually (mapped or not) so a technician reading
        // the log can tell which physical device is the problem, not just how many. The
        // rawUnmapped COUNT below is taken from unmappedGamepadCount() rather than
        // accumulated here a second time, so the number in this log line and the number on
        // the booth status line (drawBoothStatusLine / drawBootFailedScreen) cannot drift
        // apart from each other.
        var rawPresent = 0
        for (i in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST)
        {
            if (!GLFW.glfwJoystickPresent(i)) continue
            rawPresent++
            if (GLFW.glfwJoystickIsGamepad(i))
            {
                Logger.warn { "GAMEPAD DIAGNOSTIC: raw joystick $i ('${GLFW.glfwGetJoystickName(i)}') is gamepad-mapped" }
            }
            else
            {
                Logger.warn {
                    "GAMEPAD DIAGNOSTIC: raw joystick $i ('${GLFW.glfwGetJoystickName(i)}') is PRESENT but has NO " +
                    "SDL gamepad mapping — invisible to engine.input.gamepads. If this is the booth encoder, the " +
                    "stick and buttons will silently do nothing. See input-robustness-report.md."
                }
            }
        }

        val rawUnmapped = unmappedGamepadCount()
        if (rawPresent == 0)
            Logger.warn { "GAMEPAD DIAGNOSTIC: no raw joysticks detected at all (nothing plugged in, or OS hasn't enumerated it yet)" }
        else if (rawUnmapped > 0)
            Logger.warn { "GAMEPAD DIAGNOSTIC: $rawUnmapped of $rawPresent raw joystick(s) are NOT gamepad-mapped" }
    }

    /**
     * Joysticks GLFW can see that SDL has no gamepad mapping for — the exact state in which
     * the cabinet's encoder is invisible to `engine.input.gamepads` while working fine at
     * the OS level. Extracted out of [logGamepadDiagnostics] rather than duplicated so the
     * booth status line ([drawBoothStatusLine], [drawBootFailedScreen]), the log, AND the
     * EPT_DEV overlay ([renderGamepadOverlay]) — three call sites now, not two — cannot
     * report three different numbers for one machine. That function's own comment covers
     * why it still walks the raw range itself as well, for per-joystick logging this count
     * alone cannot provide.
     *
     * A plain indexed loop, not `(a..b).count { }` — this now runs on the RENDER path
     * (drawBoothStatusLine, every attract-screen frame), where CLAUDE.md's no-per-frame-
     * allocation rule applies and `.count { }` on a range allocates both the `IntRange` and
     * its `IntIterator`. `render/LifecycleInputEdges.kt`'s `stuckCount`/`chatterCount` made
     * the identical call for the identical reason — see their doc comments.
     */
    private fun unmappedGamepadCount(): Int
    {
        var count = 0
        for (i in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST)
            if (GLFW.glfwJoystickPresent(i) && !GLFW.glfwJoystickIsGamepad(i)) count++
        return count
    }

    /**
     * Dev-only input diagnostic overlay (EPT_DEV). Turns "does the cabinet's stick/buttons
     * actually work?" into a glance instead of an afternoon of guessing: how many gamepads
     * the engine sees, each one's live axis values and currently-pressed buttons, and the
     * raw-joystick-vs-mapped-gamepad counts that distinguish "nothing plugged in" from
     * "plugged in but unmapped" (see [logGamepadDiagnostics]'s doc for why that distinction
     * is the whole diagnostic value here).
     *
     * Only ever called from behind `if (devMode)` in [onRender] — this function itself does
     * no gating, so it must never be called unconditionally.
     *
     * Drawn to the "hud" surface (never mainSurface — see onCreate's comment on why GI would
     * relight it into near-invisibility), low on screen so it does not collide with the real
     * HUD's BANKED/clock/depth-tape, which all live near the top.
     */
    private fun renderGamepadOverlay(hud: Surface, w: Float, h: Float)
    {
        // rawPresent still needs its own walk (this loop's only remaining job) — but
        // rawUnmapped now comes from the shared unmappedGamepadCount() rather than a second
        // local accumulator, so this overlay's number and the booth status line's number
        // cannot read differently for the same physical encoder at the same moment, which
        // is exactly the scenario a technician standing at the cabinet with EPT_DEV=1 would
        // be comparing them for.
        var rawPresent = 0
        for (i in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST)
            if (GLFW.glfwJoystickPresent(i)) rawPresent++
        val rawUnmapped = unmappedGamepadCount()

        val pads = engine.input.gamepads
        val fontSize = h * 0.016f
        val lineHeight = fontSize * 1.35f
        val x = w * 0.015f
        var y = h * 0.80f

        hud.setDrawColor(Color.GREEN)
        hud.drawText(
            "[EPT_DEV] gamepads: ${pads.size} recognised / $rawPresent raw present / $rawUnmapped raw unmapped",
            x, y, fontSize = fontSize
        )
        y += lineHeight

        if (pads.isEmpty() && rawUnmapped > 0)
        {
            hud.setDrawColor(Color.RED)
            hud.drawText(ScreenText.UNMAPPED_JOYSTICK_WARNING, x, y, fontSize = fontSize)
            y += lineHeight
            hud.setDrawColor(Color.GREEN)
        }

        for (pad in pads)
        {
            val axes = GamepadAxis.entries.joinToString(" ") { "%s=%.2f".format(it.name, pad.getAxis(it)) }
            hud.drawText("pad#${pad.id} axes: $axes", x, y, fontSize = fontSize)
            y += lineHeight

            val pressed = GamepadButton.entries.filter { pad.isPressed(it) }
            val pressedText = if (pressed.isEmpty()) "(none)" else pressed.joinToString(",") { it.name }
            hud.drawText("pad#${pad.id} pressed: $pressedText", x, y, fontSize = fontSize)
            y += lineHeight
        }
    }

    private companion object
    {
        const val DAILY_SEED = 20260902L

        /** See the comment at the "hud" createSurface call for why this value and sign. */
        const val HUD_Z_ORDER = -90

        // COMPILED DEFAULTS ONLY. The values actually used are the fields on the class,
        // resolved from application.cfg in onCreate - see parseGamepadButton for why the
        // booth needs to remap these without a compiler. Keep these as the best guess at
        // the cabinet's encoder so a config with no keys behaves exactly as before.
        val DEFAULT_KICK_BUTTON = GamepadButton.A
        val DEFAULT_BLEED_BUTTON = GamepadButton.B
        const val DEFAULT_STICK_DEADZONE = 0.2f

        // Restart/start gets its OWN button (START), separate from DEFAULT_KICK_BUTTON, so
        // holding A to kick toward the surface at 0:00 can never itself restart the run
        // (see RunLifecycle's class doc for the incident this fixes). A is kept as a
        // secondary in case the encoder wiring leaves START unmapped — it is safe to
        // double up because RunLifecycle edge-triggers this signal internally regardless
        // of which physical button produced it, so a held A during actual play has no
        // effect (PLAYING ignores input entirely) and a held A after the run ends cannot
        // repeatedly restart (no NEW edge without a release-then-press).
        //
        // restartButton/restartButtonAlt are now config (Task 7), which opens a hazard
        // these compiled defaults do not hit but a booth technician's edit could: setting
        // BOTH keys to the same button would offer the identical (padId, ordinal) source
        // to LifecycleInputEdges twice in one frame. See the guard beside the offer calls
        // in updateGame for why that is skipped rather than merely tolerated.
        val DEFAULT_RESTART_BUTTON = GamepadButton.START
        val DEFAULT_RESTART_BUTTON_ALT = GamepadButton.A
    }
}
