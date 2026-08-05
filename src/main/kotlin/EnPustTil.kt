import dive.DiveInput
import dive.DiveSim
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.PulseEngineGame
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

fun main() = PulseEngine.run<EnPustTil>()

/**
 * Engine shell. Reads input, ticks the pure simulation on the fixed update,
 * and draws it. All game logic lives in the `dive` package.
 *
 * The IDLE / PLAYING / RUN_OVER state machine (attract screen, dwell before restart,
 * idle timeout) lives in [RunLifecycle] — a pure, engine-free, unit-tested class. This
 * file only reacts to it: gates whether [sim] gets ticked, decides which HUD screen to
 * draw, and constructs a fresh [DiveSim] on [RunLifecycle.justStarted].
 */
class EnPustTil : PulseEngineGame()
{
    private var sim = DiveSim(seed = DAILY_SEED)
    private val camera = DiveCamera()
    private val lifecycle = RunLifecycle()

    // Read once at construction, same as MetricViewer's gate below — everything downstream
    // that checks this field (the input overlay in onRender) is then a single boolean read,
    // not a repeated env-var lookup, and is trivially inert (one branch, no allocation, no
    // draw calls) when unset.
    private val devMode = System.getenv("EPT_DEV") != null

    override fun onCreate()
    {
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
        engine.gfx.mainSurface.setBackgroundColor(0.02f, 0.06f, 0.14f, 1f)
        engine.config.fixedTickRate = 60f
        camera.snapTo(sim.depth)

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
        val hudSurface = engine.gfx.createSurface("hud")

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

        lifecycle.update(engine.data.deltaTime, actionPressed, sim.runOver)

        if (lifecycle.justStarted)
        {
            sim = DiveSim(seed = DAILY_SEED)
            camera.snapTo(sim.depth)
            DiveLighting.resetAim()
        }
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
        }

        // Dev-only diagnostic overlay for finding 4 (the arcade encoder risk): completely
        // inert without EPT_DEV — this call is the ONLY thing standing between "shipped
        // build" and "overlay drawn", and it is a single boolean branch before any
        // allocation or draw call happens. See renderGamepadOverlay's doc.
        if (devMode) renderGamepadOverlay(hud, w, h)
    }

    /** Placeholder attract screen: engine default font only, no assets. */
    private fun drawIdleScreen(hud: Surface, w: Float, h: Float)
    {
        hud.setDrawColor(Color.WHITE)
        hud.drawText(
            "ÉN PUST TIL",
            w * 0.5f, h * 0.44f,
            fontSize = h * 0.07f, xOrigin = 0.5f
        )
        hud.drawText(
            "PRESS START",
            w * 0.5f, h * 0.54f,
            fontSize = h * 0.035f, xOrigin = 0.5f
        )
    }

    private fun drawRunOverScreen(hud: Surface, w: Float, h: Float)
    {
        hud.setDrawColor(Color.WHITE)
        hud.drawText(
            "RUN OVER — BANKED ${sim.banked}",
            w * 0.5f, h * 0.5f,
            fontSize = h * 0.04f, xOrigin = 0.5f
        )
        hud.drawText(
            "SPACE / START to play again",
            w * 0.5f, h * 0.5f + h * 0.045f,
            fontSize = h * 0.022f, xOrigin = 0.5f
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
            hud.drawText("!! joystick present but NOT gamepad-mapped — invisible to this game !!", x, y, fontSize = fontSize)
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
