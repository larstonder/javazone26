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
 * SO THIS CLASS MUST CALL start() ON ITSELF. Registering it is not enough. See onCreate below.
 *
 * AND IT ONLY WORKS FOR A SERVICE REGISTERED DURING game.onCreate. ServiceManagerImpl.add only
 * appends to a list; Service.onCreate is invoked exclusively from ServiceManagerInternal.init,
 * which PulseEngineImpl.postGameInit calls ONCE, right after game.onCreate. A service added any
 * later never receives onCreate, never calls start(), and stays isRunning = false forever - the
 * same silent death this class was written to escape.
 *
 * WHAT IT MEASURES. `engine.data.totalFrameTimeMs` is stamped in DataImpl.update() at the top
 * of beginFrame and read in endFrame AFTER fpsLimiter.sync() — so it is the full frame period
 * INCLUDING any limiter sleep. That is the right number for "what frame rate is the player
 * getting" and the wrong number for "how long did the work take" when the limiter is actually
 * limiting. THAT CAVEAT HAS NOW FIRED - it was written when targetFps was 120 and had never once
 * been reached, so the two numbers coincided. The 2026-08-30 performance work changed that: the
 * game now holds a 60 fps cap comfortably, so a capped reading here measures THE LIMITER, not the
 * renderer. To see real headroom you must set targetFps = 0 - FpsLimiter.sync returns immediately
 * when fps <= 0 - and the spec's section 6.1 reports capped and uncapped rows separately for
 * exactly this reason.
 *
 * DO NOT ADD A "GPU TIME" READOUT FROM engine.data. `data.gpuRenderTimeMs` is CPU wall time
 * measured around gfx.drawFrame + swapBuffers, not a GPU timer — the name lies. Real GPU
 * timing is unavailable on this platform: config.gpuProfiling = true produces a well-formed
 * 85-scope tree with t=0ns on every node, because GpuTimeQuery uses GL33.glQueryCounter, which
 * Apple's GL-on-Metal shim does not implement.
 *
 * BASELINE (as of ec6f70e, this machine, `EPT_PROFILE=1` alone — no `EPT_DEV`, since that adds
 * `MetricViewer`'s own always-on per-frame draw cost and would inflate every number below).
 * Both runs `WINDOWED` (`application-dev.cfg` forces `screenMode = WINDOWED` on every
 * `./gradlew run`; there is no runtime setter that reaches the window after creation, so a
 * pinned-size fullscreen run cannot be expressed this way — see CLAUDE.md's config-and-release
 * section) at `EPT_DEPTH=20`, first `[FRAME]` line of each run discarded (it carries first-use
 * shader-compile stalls, not steady-state cost):
 *
 * - 1600x900 window:  p50 ~37.5-38.0 ms (~26.5 fps), p95 ~38-43 ms
 * - 1920x1200 window: p50 ~42.1-42.8 ms (~23.5 fps), p95 ~44.5-46.2 ms
 *
 * **These are NOT comparable to the design spec's 22.8 ms fullscreen figure**
 * (`docs/superpowers/specs/2026-08-30-rendering-performance-design.md`: 1920x1200 fullscreen,
 * 140 m depth). Same pixel count, ~1.85x slower here — the spec's own audit separately measured
 * fullscreen at roughly 1.74x faster than windowed at equal resolution (likely a macOS
 * compositor bypass fullscreen gets and a windowed surface does not), which accounts for most of
 * that gap; the rest is plausibly the shallower `EPT_DEPTH=20` used here against the spec's 140 m
 * (fewer zone bands, less water column drawn). A/B measurements taken in the same mode (windowed
 * vs windowed, or fullscreen vs fullscreen) stay valid regardless — only a windowed-to-fullscreen
 * absolute comparison, or a "did we reach 60 fps" verdict, needs a true fullscreen run to trust.
 */
class FrameProbe(private val sampleWindowSeconds: Float = 1f) : Service()
{
    // Allocated once. The render path must not allocate, and this runs in it.
    private val samples = FloatArray(MAX_SAMPLES)
    private var count = 0
    private var elapsed = 0f

    /**
     * The most recently completed sample window's p50 frame time, in milliseconds — 0f until
     * the first full window closes. Set inside [onRender]'s existing once-a-second block, from
     * the SAME `percentile(samples, count, 0.5f)` call the `[FRAME]` log line's own p50 comes
     * from, so the on-screen SHOW FPS readout (`EnPustTil.renderGame`) and the log line it is
     * meant to be checked against (task-8-brief.md's own verification step) can never disagree
     * about the same window. Deliberately NOT recomputed every frame — it only changes once a
     * window closes, exactly as often as the log line does — so a reader should expect this to
     * hold still for up to [sampleWindowSeconds] at a time, not update smoothly.
     */
    var currentP50Ms: Float = 0f
        private set

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
            val p50 = percentile(samples, count, 0.5f)
            currentP50Ms = p50
            println(formatLine(
                p50 = p50,
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
