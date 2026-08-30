package render

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.service.Service
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frame-time instrument. Prints p50/p95/worst once per second to stdout (and therefore into
 * the booth log, via booth/BoothLog's tee).
 *
 * WHY THIS EXISTS AT ALL, given the engine ships a MetricViewer: that overlay has NEVER
 * worked in this project, and the reason is one missing call. `Service.isRunning` defaults to
 * FALSE, and `ServiceManagerImpl.update/fixedUpdate/render` all skip services where
 * `!isRunning()`. `EnPustTil.onCreate` did `engine.service.add(MetricViewer())` with no
 * `.start()`, so it was registered and never ticked. Verified from bytecode AND by driving F3
 * into the running game with Quartz.CGEventPost and screenshotting the result: the gamepad
 * overlay is there, no metrics overlay. Same root cause as the known-dead F1 console — the
 * `bind F3 showMetricViewer` line in init-dev.pes is inert because no .pes script is ever run.
 *
 * SO THIS CLASS MUST CALL start() ON ITSELF. Registering it is not enough. See init below.
 *
 * WHAT IT MEASURES. `engine.data.totalFrameTimeMs` is stamped in DataImpl.update() at the top
 * of beginFrame and read in endFrame AFTER fpsLimiter.sync() — so it is the full frame period
 * INCLUDING any limiter sleep. That is the right number for "what frame rate is the player
 * getting" and the wrong number for "how long did the work take" when the limiter is actually
 * limiting. It never is here: targetFps has never once been reached (FpsLimiter.sync returns
 * immediately whenever a frame has already overrun), so the two coincide today. If a change
 * ever gets the game comfortably under its cap, this distinction wakes up.
 *
 * DO NOT ADD A "GPU TIME" READOUT FROM engine.data. `data.gpuRenderTimeMs` is CPU wall time
 * measured around gfx.drawFrame + swapBuffers, not a GPU timer — the name lies. Real GPU
 * timing is unavailable on this platform: config.gpuProfiling = true produces a well-formed
 * 85-scope tree with t=0ns on every node, because GpuTimeQuery uses GL33.glQueryCounter, which
 * Apple's GL-on-Metal shim does not implement.
 */
class FrameProbe(private val sampleWindowSeconds: Float = 1f) : Service()
{
    // Allocated once. The render path must not allocate, and this runs in it.
    private val samples = FloatArray(MAX_SAMPLES)
    private var count = 0
    private var elapsed = 0f

    override fun onCreate(engine: PulseEngine)
    {
        // Not redundant with engine.service.add(). See the class doc: a Service that does not
        // start itself is silently never ticked, which is exactly how MetricViewer died.
        start()
    }

    override fun onRender(engine: PulseEngine)
    {
        val frameMs = engine.data.totalFrameTimeMs
        if (count < MAX_SAMPLES) samples[count++] = frameMs

        elapsed += engine.data.deltaTime
        if (elapsed < sampleWindowSeconds) return

        if (count > 0)
        {
            println(formatLine(
                p50 = percentile(samples, count, 0.5f),
                p95 = percentile(samples, count, 0.95f),
                worst = percentile(samples, count, 1.0f),
                frames = count
            ))
        }
        elapsed = 0f
        count = 0
    }

    companion object
    {
        /** One second at 1000 fps is far more headroom than this game will ever need. */
        private const val MAX_SAMPLES = 1000

        /**
         * The [fraction]-th ranked sample of the first [count] entries of [samples].
         *
         * Copies before sorting on purpose: [samples] is the live ring the render path is
         * still writing into, and sorting it in place would scramble the next window's data
         * while producing a number that looks perfectly reasonable.
         *
         * This copy allocates, which the render path forbids — it is acceptable HERE because
         * it happens once per second rather than once per frame, and because the probe is a
         * measurement tool that is off at the booth. Do not move this call per-frame.
         */
        fun percentile(samples: FloatArray, count: Int, fraction: Float): Float
        {
            if (count <= 0) return 0f
            val ranked = samples.copyOf(count)
            ranked.sort()
            val index = ((count - 1) * fraction).roundToInt()
            return ranked[min(index, count - 1)]
        }

        /** Milliseconds AND the implied fps, because one of the two is always the one you wanted. */
        fun formatLine(p50: Float, p95: Float, worst: Float, frames: Int): String
        {
            val fps = if (p50 > 0f) 1000f / p50 else 0f
            return "[FRAME] p50=%.2fms (%.1f fps)  p95=%.2fms  worst=%.2fms  n=%d"
                .format(p50, fps, p95, worst, frames)
        }
    }
}
