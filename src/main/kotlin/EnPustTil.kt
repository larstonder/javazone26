import dive.DiveInput
import dive.DiveSim
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.PulseEngineGame
import no.njoh.pulseengine.core.input.GamepadAxis
import no.njoh.pulseengine.core.input.GamepadButton
import no.njoh.pulseengine.core.input.Key
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.modules.metrics.MetricViewer
import render.DiveCamera
import render.DiveRenderer
import render.Hud

fun main() = PulseEngine.run<EnPustTil>()

/**
 * Engine shell. Reads input, ticks the pure simulation on the fixed update,
 * and draws it. All game logic lives in the `dive` package.
 */
class EnPustTil : PulseEngineGame()
{
    private var sim = DiveSim(seed = DAILY_SEED)
    private val camera = DiveCamera()

    override fun onCreate()
    {
        engine.service.add(MetricViewer()) // F3
        engine.gfx.mainSurface.setBackgroundColor(0.02f, 0.06f, 0.14f, 1f)
        engine.config.fixedTickRate = 60f
        camera.snapTo(sim.depth)

        System.getenv("EPT_SCREENSHOT")?.let {
            engine.gfx.mainSurface.addPostProcessingEffect(render.ScreenshotEffect(it))
        }
    }

    override fun onFixedUpdate()
    {
        sim.tick(engine.data.fixedDeltaTime, readInput())
    }

    override fun onUpdate()
    {
        // Camera easing is presentation only, so it runs on the render clock rather than
        // the fixed tick — that keeps it smooth independently of the simulation rate.
        camera.update(engine.data.deltaTime, sim.depth)

        // Restart is only reachable once the run is over — a stray keypress or bumped
        // arcade button must never destroy a leaderboard attempt mid-run. Key.R
        // (unconditional restart) was removed for this reason; see fix-gamepad-report.md.
        val pad = engine.input.gamepads.firstOrNull()
        val padRestart = pad?.let { it.isPressed(RESTART_BUTTON) || it.isPressed(RESTART_BUTTON_ALT) } ?: false
        if (sim.runOver && (engine.input.wasClicked(Key.SPACE) || padRestart))
        {
            sim = DiveSim(seed = DAILY_SEED)
            camera.snapTo(sim.depth)
        }
    }


    override fun onRender()
    {
        val surface = engine.gfx.mainSurface
        val w = engine.window.width.toFloat()
        val h = engine.window.height.toFloat()
        DiveRenderer.render(surface, sim, camera, w, h)
        Hud.render(surface, sim, camera, w, h)

        if (sim.runOver)
        {
            surface.setDrawColor(Color.WHITE)
            surface.drawText(
                "RUN OVER — SPACE to restart",
                w * 0.5f, h * 0.5f,
                fontSize = h * 0.04f, xOrigin = 0.5f
            )
        }
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

    private companion object
    {
        const val DAILY_SEED = 20260902L
        const val STICK_DEADZONE = 0.2f

        // Booth hardware is a joystick plus two arcade buttons on a USB encoder,
        // which enumerates as a gamepad with a standard button layout. Remap here
        // if the encoder wiring puts the buttons on different codes.
        val KICK_BUTTON = GamepadButton.A
        val BLEED_BUTTON = GamepadButton.B
        val RESTART_BUTTON = GamepadButton.A
        val RESTART_BUTTON_ALT = GamepadButton.START
    }
}
