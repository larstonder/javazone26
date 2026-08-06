import dive.DiveInput
import dive.DiveSim
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.PulseEngineGame
import no.njoh.pulseengine.core.asset.types.Font
import no.njoh.pulseengine.core.graphics.api.Multisampling
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.input.GamepadAxis
import no.njoh.pulseengine.core.input.GamepadButton
import no.njoh.pulseengine.core.input.Key
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.core.shared.utils.LogLevel
import no.njoh.pulseengine.core.shared.utils.Logger
import no.njoh.pulseengine.modules.metrics.MetricViewer
import org.lwjgl.glfw.GLFW
import render.DiveCamera
import render.DiveLighting
import render.DiveRenderer
import render.Hud
import render.RunLifecycle
import render.RunLifecycleState
import render.anyLifecycleActionPressed
import render.drawTextWithOutline
import score.ScoreRepository

fun main() = PulseEngine.run<EnPustTil>()

/**
 * Parses the "dailySeed" override read from application.cfg (see [EnPustTil.onCreate]).
 * Pure and engine-free specifically so it is unit testable without standing up a
 * PulseEngine instance — see EnPustTilSeedTest.
 *
 * Returns [fallback] (the compile-time [EnPustTil.DAILY_SEED]) for both cases a
 * technician's text-file edit can produce: [raw] is null (key absent — day one, before
 * anyone has touched the file) or [raw] is present but not a valid Long (a typo must
 * never crash the booth machine; it just silently reuses day one's seed instead).
 */
fun parseDailySeed(raw: String?, fallback: Long): Long = raw?.toLongOrNull() ?: fallback

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
    const val PRESS_START = "PRESS START"
    const val LEADERBOARD_HEADING = "TODAY'S DIVERS"
    const val PLAY_AGAIN = "SPACE / START to play again"
    const val INITIALS_HELP = "UP/DOWN: change letter   A / START: next"

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

    /**
     * Every drawable string, with the interpolated ones instantiated at values that
     * exercise their widest form. Used only by the test; cheap enough not to warrant
     * hiding behind a flag.
     */
    fun all(): List<String> = listOf(
        SEPARATOR,
        TITLE,
        PRESS_START,
        LEADERBOARD_HEADING,
        PLAY_AGAIN,
        INITIALS_HELP,
        UNMAPPED_JOYSTICK_WARNING,
        runOver(0),
        runOver(99999),
        newScore(12345),
        initialsSlots("AAA", 0),
        initialsSlots("ØYA", 2)
    )
}

/**
 * Pure, engine-free layout for the attract screen — extracted for the same reason
 * [render.Viewport] and [render.DepthBlend] are: the interesting property is a RELATIONSHIP
 * between numbers ("the title clears the diver", "the leaderboard is centred under its own
 * heading"), and a relationship can be asserted without a GL context.
 *
 * WHY THIS EXISTS: the world keeps rendering behind the attract screen on purpose (see
 * [EnPustTil.drawIdleScreen]) — the queue watches live water, not a static image. That is a
 * good decision that had a bad consequence: the title was drawn at 0.44 of screen height,
 * and the diver is pinned by [render.Viewport.DIVER_SCREEN_FRACTION] to 0.40 with a large
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
 * [render.Viewport]'s doc for the HiDPI bug this convention exists to prevent).
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
     * [render.Viewport.DIVER_SCREEN_FRACTION]. The diver itself is only
     * `DIVER_SIZE_METRES / VISIBLE_DEPTH_METRES` = 0.05 of screen height, so this is
     * almost entirely the light: measured off `idle-view.png`, the blue halo is still
     * clearly reading 0.14h above and below the diver before it fades into the ambient
     * gradient. Attract text must stay outside this band, which is the whole point of the
     * anchors below.
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
 * Engine shell. Reads input, ticks the pure simulation on the fixed update,
 * and draws it. All game logic lives in the `dive` package.
 *
 * The IDLE / PLAYING / RUN_OVER / ENTER_INITIALS state machine (attract screen +
 * leaderboard, dwell before restart, initials entry for a qualifying score, idle
 * timeout) lives in [RunLifecycle] — a pure, engine-free, unit-tested class. This file
 * only reacts to it: gates whether [sim] gets ticked, decides which HUD screen to draw,
 * constructs a fresh [DiveSim] on [RunLifecycle.justStarted], and persists a completed
 * initials entry via [scoreRepository] on [RunLifecycle.initialsJustCompleted].
 */
class EnPustTil : PulseEngineGame()
{
    private lateinit var sim: DiveSim
    private val camera = DiveCamera()
    private val lifecycle = RunLifecycle()

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

    // Read once at construction, same as MetricViewer's gate below — everything downstream
    // that checks this field (the input overlay in onRender) is then a single boolean read,
    // not a repeated env-var lookup, and is trivially inert (one branch, no allocation, no
    // draw calls) when unset.
    private val devMode = System.getenv("EPT_DEV") != null

    override fun onCreate()
    {
        // Resolved FIRST: sim/scoreRepository below are constructed from this value, and
        // application.cfg (loaded by the engine before onCreate runs — see dailySeed's
        // doc) is the only source for a technician's day-two override. See
        // application.cfg for the exact commented-out line to uncomment/edit on-site.
        dailySeed = parseDailySeed(engine.config.getString("dailySeed"), DAILY_SEED)
        sim = DiveSim(seed = dailySeed)
        scoreRepository = ScoreRepository(todaySeed = dailySeed)
        Logger.info { "Daily seed: $dailySeed" }

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
        // its own Camera2DController, so panning or zooming in the editor moves the world out
        // from under the HUD — Viewport's screen-space maths assumes mainCamera is at identity
        // (see 6ea1f53, where a second writer of that same camera was the shipped
        // misalignment bug).
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

        engine.gfx.mainSurface.setBackgroundColor(0.02f, 0.06f, 0.14f, 1f)
        engine.config.fixedTickRate = 60f
        camera.snapTo(sim.depth)

        // Registering as a Service (rather than calling its methods directly) gives
        // ScoreRepository its own onCreate (load scores from disk) and onDestroy (final
        // save) hooks, driven by the engine's own lifecycle — see its class doc for the
        // verified call order.
        engine.service.add(scoreRepository)

        logGamepadDiagnostics()

        DiveLighting.setup(engine)

        // The HUD is drawn to its OWN transparent surface, composited on top of mainSurface
        // at the backbuffer stage, rather than onto mainSurface itself. GlobalIlluminationSystem
        // (wired up by DiveLighting) adds a multiply post-processing effect that relights
        // whatever mainSurface holds by the computed light map — that is exactly what makes
        // the Abyss go dark, but it would ALSO multiply the HUD into near-invisibility, since
        // BANKED/the clock/depth tape sit far from any lamp. Verified empirically: with the
        // HUD on mainSurface, "BANKED 7" in the Abyss reads as RGB(11,8,1) — practically
        // black. A separate surface outside GI's target ("main") keeps the HUD fully lit
        // regardless of world darkness, which is what "the HUD remains readable over the
        // darkened scene" requires.
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
        //     (`lastZOrder--`) in creation order — so simply reordering DiveLighting.setup()
        //     and this call would silently change the HUD's layering. HUD_Z_ORDER pins it
        //     explicitly instead, matching the reference's SURFACE_MENU_UI (-90): smaller
        //     zOrder sorts later in GraphicsImpl's composite pass (sorted by -zOrder
        //     ascending, confirmed in the decompiled comparator), i.e. drawn ON TOP —
        //     comfortably past anything GlobalIlluminationSystem creates, which derives its
        //     own surfaces' zOrder relative to mainSurface's rather than through this
        //     counter, so it can never collide with this value.
        //   - camera: left null (default) deliberately — the engine default IS "create a
        //     fresh orthographic camera" (GraphicsImpl.createSurface builds its own
        //     DefaultCamera.createOrthographic when none is passed), which is exactly what a
        //     screen-space HUD needs. It must NOT be handed the shared main camera. That used
        //     to be justified by "the GI Camera entity drives it"; that entity is gone (see
        //     DiveLighting.setup — it WAS the world-offset-from-HUD bug), so the shared camera
        //     now sits at the identity and passing it would look harmless today. It is not:
        //     the HUD's independence from the world camera is the whole reason Hud and
        //     AttractLayout can be pure screen space, and the world-coordinate migration in
        //     docs/superpowers/plans/2026-08-06-engine-world-coordinates.md would make that
        //     camera scale by ~30 and smear the entire HUD off screen. Leave it null.
        val hudSurface = engine.gfx.createSurface(
            name = "hud",
            backgroundColor = Color.BLANK,
            multisampling = Multisampling.MSAA16,
            zOrder = HUD_Z_ORDER
        )

        System.getenv("EPT_SCREENSHOT")?.let {
            engine.gfx.mainSurface.addPostProcessingEffect(render.ScreenshotEffect(it))
            hudSurface.addPostProcessingEffect(render.ScreenshotEffect(it.replace(".png", "") + "-hud"))
        }
    }

    override fun onFixedUpdate()
    {
        // IDLE = attract mode: the clock must not run, and nothing should be reachable
        // by a bumped button while the machine sits unattended between players. Ticking
        // is otherwise unconditional — DiveSim.tick already no-ops once runOver is true,
        // so RUN_OVER need not be special-cased here.
        if (lifecycle.state != RunLifecycleState.IDLE)
            sim.tick(engine.data.fixedDeltaTime, readInput())
    }

    override fun onUpdate()
    {
        // Ambient is a continuous function of depth only — no camera/screen dependence — so
        // unlike the positional light draws in onRender, timing here doesn't matter.
        DiveLighting.updateAmbient(sim)

        // Camera easing is presentation only, so it runs on the render clock rather than
        // the fixed tick — that keeps it smooth independently of the simulation rate.
        camera.update(engine.data.deltaTime, sim.depth)

        // Start/restart is a LEVEL reading here — deliberately. The engine's Gamepad only
        // exposes isPressed/getAxis (confirmed against the engine jar: no gamepad
        // wasClicked), so there is no engine-provided edge detection for a controller
        // button. RunLifecycle does its own previous-frame edge-tracking internally (see
        // its class doc) specifically so a held or stuck button cannot fire this every
        // frame — feeding it a level reading is exactly what it is built to consume.
        // Key.SPACE's wasClicked is already an edge; OR-ing it in here is harmless since
        // RunLifecycle re-edges the combined signal anyway.
        //
        // Movement/kick/bleed are deliberately excluded — a stray keypress or bumped
        // arcade button must never destroy a leaderboard attempt mid-run, nor spuriously
        // wake the attract screen. Key.R (unconditional restart) was removed for the same
        // reason; see fix-gamepad-report.md.
        //
        // Unlike readInput()'s gameplay reads (gamepads.firstOrNull() — deliberately kept,
        // see that method's doc), lifecycle input scans EVERY connected gamepad. Index 0 is
        // not guaranteed to be the cabinet's stick at a booth; see anyLifecycleActionPressed's
        // doc (render/GamepadScan.kt) for why "any button to start" must mean any gamepad.
        val gamepadActionPressed = engine.input.gamepads.map {
            it.isPressed(RESTART_BUTTON) || it.isPressed(RESTART_BUTTON_ALT)
        }
        val actionPressed = anyLifecycleActionPressed(engine.input.wasClicked(Key.SPACE), gamepadActionPressed)

        // Initials entry (ENTER_INITIALS only — harmless to compute unconditionally
        // otherwise, RunLifecycle simply ignores these outside that state). Reuses the
        // SAME actionPressed signal as the restart/confirm button — "the button that
        // started your run also advances your initials" — rather than introducing a
        // third physical input the cabinet does not have. See readInitialsCycle's doc
        // for the up/down source.
        val (cycleUp, cycleDown) = readInitialsCycle()

        lifecycle.update(
            dt = engine.data.deltaTime,
            anyInputPressed = actionPressed,
            runOver = sim.runOver,
            bankedScore = sim.banked,
            cycleUp = cycleUp,
            cycleDown = cycleDown,
            confirmPressed = actionPressed
        )

        if (lifecycle.justStarted)
        {
            sim = DiveSim(seed = dailySeed)
            camera.snapTo(sim.depth)
            DiveLighting.resetAim()
        }

        // The tick initials entry finishes (confirmed or auto-submitted on timeout —
        // see RunLifecycle's ENTER_INITIALS doc), persist the score. `sim` is still the
        // DiveSim that scored this run: a completed entry moves to IDLE, not PLAYING, so
        // no new DiveSim has been constructed yet this frame (justStarted is false here).
        if (lifecycle.initialsJustCompleted)
            scoreRepository.registerScore(engine, lifecycle.completedInitials, sim.banked)
    }

    /**
     * The actual score-saving guarantee on a clean shutdown comes from
     * [ScoreRepository.onDestroy] — it is registered as a [no.njoh.pulseengine.core
     * .service.Service] (see [onCreate]) and the engine calls every service's
     * `onDestroy` automatically (verified by decompiling `ServiceManagerImpl`, right
     * after this method returns — see [ScoreRepository]'s class doc for the exact
     * order). This method exists to satisfy that explicit requirement in its own right
     * and to leave a clean, on-site-diagnosable log line distinguishing a graceful
     * shutdown from a crash/power-cut, which this line never gets the chance to log.
     */
    override fun onDestroy()
    {
        Logger.info { "Én Pust Til shutting down cleanly" }
    }

    override fun onRender()
    {
        val w = engine.window.width.toFloat()
        val h = engine.window.height.toFloat()

        // World: lit by GlobalIlluminationSystem, which multiplies mainSurface by the
        // computed light map — this is what makes the Abyss genuinely dark.
        DiveRenderer.render(engine.gfx.mainSurface, sim, camera, w, h)

        // Lights: immediate-mode drawLight calls issued HERE, in onRender, reading the SAME
        // camera.depth DiveRenderer just used above — not repositioned earlier in onUpdate,
        // which is what let the light and the object it illuminates drift apart by a frame
        // whenever the camera was still easing. See DiveLighting's class doc.
        DiveLighting.render(engine, sim, camera, engine.data.deltaTime, w, h)

        // HUD: its own surface, composited on top unaffected by GI — see the comment in
        // onCreate for why it cannot share mainSurface. What it shows depends on the
        // lifecycle state: the numeric HUD (BANKED/clock/air/depth tape) only makes sense
        // once a run actually exists, so IDLE gets its own simple attract text instead.
        val hud = engine.gfx.getSurfaceOrDefault("hud")

        when (lifecycle.state)
        {
            RunLifecycleState.IDLE -> drawIdleScreen(hud, w, h)

            RunLifecycleState.PLAYING -> Hud.render(hud, sim, camera, w, h)

            RunLifecycleState.RUN_OVER ->
            {
                Hud.render(hud, sim, camera, w, h)
                drawRunOverScreen(hud, w, h)
            }

            RunLifecycleState.ENTER_INITIALS ->
            {
                Hud.render(hud, sim, camera, w, h)
                drawInitialsEntryScreen(hud, w, h)
            }
        }

        // Dev-only diagnostic overlay for finding 4 (the arcade encoder risk): completely
        // inert without EPT_DEV — this call is the ONLY thing standing between "shipped
        // build" and "overlay drawn", and it is a single boolean branch before any
        // allocation or draw call happens. See renderGamepadOverlay's doc.
        if (devMode) renderGamepadOverlay(hud, w, h)
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
            ScreenText.PRESS_START,
            w * 0.5f, h * AttractLayout.PRESS_START_Y,
            h * AttractLayout.PRESS_START_FONT, h, Color.WHITE, xOrigin = 0.5f
        )
        drawLeaderboard(hud, w, h)
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
            ScreenText.PLAY_AGAIN,
            w * 0.5f, h * 0.5f + h * 0.045f,
            h * 0.022f, h, Color.WHITE, xOrigin = 0.5f
        )
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
            ScreenText.INITIALS_HELP,
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
        val pad = engine.input.gamepads.firstOrNull()
        val padX = pad?.getAxis(GamepadAxis.LEFT_X)?.deadzone() ?: 0f
        val padY = pad?.getAxis(GamepadAxis.LEFT_Y)?.deadzone() ?: 0f

        val keyX = axis(Key.LEFT, Key.RIGHT)
        val keyY = axis(Key.UP, Key.DOWN)

        return DiveInput(
            horizontal = if (padX != 0f) padX else keyX,
            vertical   = if (padY != 0f) padY else keyY,
            kick       = (pad?.isPressed(KICK_BUTTON) ?: false) || engine.input.isPressed(Key.Z),
            bleed      = (pad?.isPressed(BLEED_BUTTON) ?: false) || engine.input.isPressed(Key.X)
        )
    }

    /**
     * Initials-entry cycling input: stick up/down (any connected gamepad — same "any
     * button" reasoning as [anyLifecycleActionPressed], since this is menu navigation,
     * not gameplay) OR the UP/DOWN keys, so keyboard development keeps working. Level
     * readings, same as [readInput] — [score.InitialsEntry] does its own edge-tracking.
     */
    private fun readInitialsCycle(): Pair<Boolean, Boolean>
    {
        val padUp = engine.input.gamepads.any { it.getAxis(GamepadAxis.LEFT_Y) < -STICK_DEADZONE }
        val padDown = engine.input.gamepads.any { it.getAxis(GamepadAxis.LEFT_Y) > STICK_DEADZONE }
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

    private fun Float.deadzone() = if (kotlin.math.abs(this) < STICK_DEADZONE) 0f else this

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
     * The "present but unmapped" case is logged at WARN specifically so it survives the
     * booth-default WARN log level (application.cfg) without needing EPT_DEV — it is exactly
     * the failure this diagnostic exists to catch, and a technician should see it even
     * against a plain release .exe. The summary line is INFO, so it is silent by default in
     * the release build and visible whenever logLevel is DEBUG (application-dev.cfg locally,
     * or EPT_DEV forcing it against a release .exe).
     */
    private fun logGamepadDiagnostics()
    {
        val recognised = engine.input.gamepads
        Logger.info {
            "GAMEPAD DIAGNOSTIC: engine.input.gamepads = ${recognised.size} " +
            "(ids=${recognised.map { it.id }})"
        }

        var rawPresent = 0
        var rawUnmapped = 0
        for (i in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST)
        {
            if (!GLFW.glfwJoystickPresent(i)) continue
            rawPresent++
            if (GLFW.glfwJoystickIsGamepad(i))
            {
                Logger.info { "GAMEPAD DIAGNOSTIC: raw joystick $i ('${GLFW.glfwGetJoystickName(i)}') is gamepad-mapped" }
            }
            else
            {
                rawUnmapped++
                Logger.warn {
                    "GAMEPAD DIAGNOSTIC: raw joystick $i ('${GLFW.glfwGetJoystickName(i)}') is PRESENT but has NO " +
                    "SDL gamepad mapping — invisible to engine.input.gamepads. If this is the booth encoder, the " +
                    "stick and buttons will silently do nothing. See input-robustness-report.md."
                }
            }
        }

        if (rawPresent == 0)
            Logger.info { "GAMEPAD DIAGNOSTIC: no raw joysticks detected at all (nothing plugged in, or OS hasn't enumerated it yet)" }
        else if (rawUnmapped > 0)
            Logger.warn { "GAMEPAD DIAGNOSTIC: $rawUnmapped of $rawPresent raw joystick(s) are NOT gamepad-mapped" }
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
        var rawPresent = 0
        var rawUnmapped = 0
        for (i in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST)
        {
            if (!GLFW.glfwJoystickPresent(i)) continue
            rawPresent++
            if (!GLFW.glfwJoystickIsGamepad(i)) rawUnmapped++
        }

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
        const val STICK_DEADZONE = 0.2f

        /** See the comment at the "hud" createSurface call for why this value and sign. */
        const val HUD_Z_ORDER = -90

        // Booth hardware is a joystick plus two arcade buttons on a USB encoder,
        // which enumerates as a gamepad with a standard button layout. Remap here
        // if the encoder wiring puts the buttons on different codes.
        val KICK_BUTTON = GamepadButton.A
        val BLEED_BUTTON = GamepadButton.B

        // Restart/start gets its OWN button (START), separate from KICK_BUTTON, so
        // holding A to kick toward the surface at 0:00 can never itself restart the run
        // (see RunLifecycle's class doc for the incident this fixes). A is kept as a
        // secondary in case the encoder wiring leaves START unmapped — it is safe to
        // double up because RunLifecycle edge-triggers this signal internally regardless
        // of which physical button produced it, so a held A during actual play has no
        // effect (PLAYING ignores input entirely) and a held A after the run ends cannot
        // repeatedly restart (no NEW edge without a release-then-press).
        val RESTART_BUTTON = GamepadButton.START
        val RESTART_BUTTON_ALT = GamepadButton.A
    }
}
