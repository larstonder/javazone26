import dive.DiveInput
import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.PulseEngineGame
import no.njoh.pulseengine.core.input.GamepadAxis
import no.njoh.pulseengine.core.input.GamepadButton
import no.njoh.pulseengine.core.input.Key
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.modules.metrics.MetricViewer
import render.DiveCamera
import render.DiveRenderer

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
        DiveRenderer.render(surface, sim, camera, engine.window.width.toFloat(), engine.window.height.toFloat())
        drawDebugReadout()
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

    /** Temporary numeric readout. Replaced by the real HUD in Task 8. */
    private fun drawDebugReadout()
    {
        val s = engine.gfx.mainSurface
        s.setDrawColor(Color.WHITE)
        s.drawText("CLOCK  %.1f".format(sim.clock), 20f, 30f, fontSize = 24f)
        s.drawText("DEPTH  %.1f m".format(sim.depth), 20f, 60f, fontSize = 24f)
        s.drawText("AIR    %.1f".format(sim.air), 20f, 90f, fontSize = 24f)
        s.drawText("HELD   ${sim.held}  (mass %.1f)".format(sim.heldMass), 20f, 120f, fontSize = 24f)
        s.drawText("BANKED ${sim.banked}", 20f, 150f, fontSize = 24f)
        s.drawText("ZONE   ${sim.zone}", 20f, 180f, fontSize = 24f)
        if (sim.runOver) s.drawText("RUN OVER — press A or SPACE", 20f, 220f, fontSize = 32f)
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
        val RESTART_BUTTON = GamepadButton.A
        val RESTART_BUTTON_ALT = GamepadButton.START
    }
}
