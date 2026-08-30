# Rendering Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take the game from a measured 43.8 fps to a sustained 60 fps at 1920×1200 fullscreen by removing GPU work — render passes and fill rate — not by optimising Kotlin.

**Architecture:** The game is GPU-bound with the GPU pinned at 100%; its own Kotlin costs 0.3 ms of a 22.8 ms frame. Every change here removes work from the Global Illumination pass chain or the post-processing chain. Each lands as its own commit with a before/after frame-time measurement, because the projected savings are arithmetic from bytecode pass-count formulas, not measurements. The final task extracts the surviving values into a pure preset ladder that the companion menu plan consumes.

**Tech Stack:** Kotlin 2.2.20, Pulse Engine 0.13.0, LWJGL/GLFW, OpenGL 3.3 core on macOS (Apple GL-over-Metal shim), JUnit Platform via `kotlin.test`, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-30-rendering-performance-design.md`

## Global Constraints

- **Allman braces, 4 spaces, no wildcard imports.** Match the surrounding file.
- **Comments explain *why*, at length, and cite evidence** (a decompiled engine class, a screenshot, a measurement). That density is deliberate; most decisions here look arbitrary without it.
- **No per-frame allocation in the render path.** HUD text formatting is the one existing exemption.
- **Never `Surface.drawQuad()` / `drawLine()`** — they render nothing on macOS, silently. Use `render/Draw.kt`'s `fillRect`/`fillRectCentred`. `DrawTest` fails the build if a `drawQuad` call returns.
- **Never read a gamepad outside `render/MappedPads.kt`.** `MappedPadsTest` fails the build if `.isPressed(`/`.getAxis(` appears on any receiver but `mappedPads` or `engine.input`.
- **Every drawn string goes through `ScreenText`.** The default font draws only U+0020..U+011F; anything above renders as nothing at all, silently. `AttractScreenTest` asserts every entry is drawable.
- **A test that cannot fail is worse than no test.** Assert the relationship that would actually break, not a transcription of a constant.
- **Target for "60 fps" in this plan means:** p50 frame time ≤ 16.67 ms at 1920×1200 fullscreen, at 140 m depth.
- **Baseline to beat (measured 2026-08-30, under partial external CPU load, therefore pessimistic):** 22.8 ms p50 at 1920×1200 fullscreen / 140 m; 73.7 ms p50 at 3200×1800 dev window / 140 m.
- **Commit messages:** one short lower-case line, no trailers, no `Co-Authored-By`, matching this repo's history.

## Measurement Protocol

Referenced by every task below as "**run the measurement protocol**". Do not substitute a different method — the numbers will not be comparable.

1. Build and run with the probe enabled: `EPT_PROFILE=1 EPT_DEPTH=140 ./gradlew run > /tmp/run.log 2>&1 &`
2. Wait for the process, then let it settle: `until pgrep -f EnPustTilKt > /dev/null; do sleep 2; done ; sleep 8` — **8 seconds, not 12–14**; startup measures 2.3 s from JVM start and the old figure is roughly 4× too long.
3. Enter the water. `osascript` keystrokes are **silently dropped** by this GLFW window; only `Quartz.CGEventPost` works:

```bash
osascript -e 'tell application "System Events" to set frontmost of (first application process whose name is "java") to true'
sleep 0.5
python3 - <<'PY'
import Quartz, time
def post_key(keycode, down):
    Quartz.CGEventPost(Quartz.kCGHIDEventTap,
                       Quartz.CGEventCreateKeyboardEvent(None, keycode, down))
SPACE = 49
post_key(SPACE, True); time.sleep(0.05); post_key(SPACE, False)
PY
sleep 6      # RunLifecycle.BRIEFING_SECONDS is 5 s, plus margin
```

4. Let it run **at least 25 seconds** of steady state, then read the p50/p95 lines the probe prints to `/tmp/run.log`.
5. `pkill -9 -f EnPustTilKt`
6. Record p50 **and** p95 in the task's commit message. Means hide the hitches that matter.

**Rules that make the numbers trustworthy:**

- **Take A/B pairs back to back**, never on different days. Absolute numbers moved ~2× between a quiet and a loaded machine during the audit; back-to-back ratios held.
- **Vary resolution by editing `build/resources/main/application-dev.cfg`** — the gitignored build output — never the source file. It survives everything except another `processResources`.
- **Confirm the GPU is actually the thing you changed:** `ioreg -r -d 1 -c AGXAccelerator | grep "Device Utilization %"`. Idle desktop reads 3–5%; the game pins it at 100%.
- **`EPT_SCREENSHOT` is not passive and its output is not the frame.** `ScreenshotEffect.getTexture()` returns `RenderTexture.BLANK`, so any surface it attaches to composites as blank, and it dumps each surface separately. For anything about *appearance*, take a real `screencapture` of the window.
- **Validate that a `screencapture` actually contains the game** before drawing conclusions. A capture returning the lock screen looks identical to a successful one unless something checks. A full capture cycle was lost to exactly that on this project.

---

## Task 1: The frame probe, and the reason F3 never worked

Nothing else in this plan can be verified without this. It ships first and stays.

**Files:**
- Create: `src/main/kotlin/render/FrameProbe.kt`
- Modify: `src/main/kotlin/EnPustTil.kt:1505-1506` (the `MetricViewer` registration)
- Modify: `src/main/kotlin/EnPustTil.kt` — register `FrameProbe` in `onCreate`
- Test: `src/test/kotlin/render/FrameProbeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class FrameProbe(private val sampleWindowSeconds: Float = 1f) : Service()`, with pure statics `FrameProbe.percentile(samples: FloatArray, count: Int, fraction: Float): Float` and `FrameProbe.formatLine(p50: Float, p95: Float, worst: Float, frames: Int): String`. Task 12 does not depend on this; Tasks 2–10 all use it via the measurement protocol.

- [ ] **Step 1: Confirm the `Service` API before writing against it**

The plan asserts `Service.isRunning` defaults false and that `ServiceManagerImpl` skips non-running services. Verify rather than trust:

```bash
JAR=$(find ~/.gradle/caches -name "pulse-engine-0.13.0.jar" | head -1)
cd /tmp && rm -rf peek && mkdir peek && cd peek && unzip -q "$JAR"
javap -p no/njoh/pulseengine/core/service/Service.class
javap -p -c no/njoh/pulseengine/core/service/ServiceManagerImpl.class | grep -A4 "isRunning"
```

Expected: `Service` declares a private `isRunning` defaulting false, a public `start()`, and `onCreate`/`onUpdate`/`onFixedUpdate`/`onRender`/`onDestroy` taking `PulseEngine`. Record the exact override signatures — write `FrameProbe` against what you see, not against what this plan guesses.

- [ ] **Step 2: Write the failing test for the pure percentile helper**

The sampling loop needs a GL context; the maths does not. Test the maths.

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `FrameProbe` is the only instrument this project has for frame time — the engine's own F3
 * overlay has never run (see the class doc), and GL timer queries return 0 ns on Apple
 * Silicon. So the arithmetic it reports is worth asserting: a percentile that is quietly
 * wrong would make every optimisation measurement in the performance plan meaningless, and
 * would do it silently, because a plausible-looking millisecond number is indistinguishable
 * from a correct one.
 */
class FrameProbeTest
{
    @Test
    fun `percentile picks the ranked sample, not the mean`() {
        // Deliberately skewed: mean is 20.9, p50 must still be 10.
        val samples = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 119f)
        assertEquals(10f, FrameProbe.percentile(samples, 10, 0.5f), 0.001f)
        assertEquals(119f, FrameProbe.percentile(samples, 10, 1.0f), 0.001f)
    }

    @Test
    fun `percentile reads only the first count entries, not the whole buffer`() {
        // The ring buffer is allocated once and reused, so stale tail entries must not count.
        val samples = FloatArray(100) { 999f }
        samples[0] = 5f
        samples[1] = 7f
        assertEquals(7f, FrameProbe.percentile(samples, 2, 1.0f), 0.001f)
    }

    @Test
    fun `percentile does not mutate the caller's buffer`() {
        // It sorts to rank; sorting in place would scramble the live sample ring.
        val samples = floatArrayOf(30f, 10f, 20f)
        FrameProbe.percentile(samples, 3, 0.5f)
        assertEquals(30f, samples[0], 0.001f)
    }

    @Test
    fun `percentile of an empty window is zero rather than a crash`() {
        assertEquals(0f, FrameProbe.percentile(FloatArray(10), 0, 0.5f), 0.001f)
    }

    @Test
    fun `the reported line carries fps alongside milliseconds`() {
        val line = FrameProbe.formatLine(p50 = 16.67f, p95 = 20f, worst = 33f, frames = 120)
        assertTrue(line.contains("16.67"), "p50 ms missing: $line")
        assertTrue(line.contains("60.0"), "implied fps missing: $line")
        assertTrue(line.contains("120"), "frame count missing: $line")
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests "render.FrameProbeTest"`
Expected: FAIL — `Unresolved reference: FrameProbe`.

- [ ] **Step 4: Write `FrameProbe`**

Create `src/main/kotlin/render/FrameProbe.kt`. Adjust the `override` signatures to match what Step 1 printed.

```kotlin
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
                .format(p50, fps, worst.let { it }, frames)
                .replace("worst=%.2fms".format(worst), "worst=%.2fms".format(worst))
        }
    }
}
```

**Note on `formatLine`:** write it as a single straightforward `"...".format(p50, fps, p95, worst, frames)` with five placeholders in order. The version above is deliberately shown mid-thought so you do not copy a convoluted expression — replace the body with the clean one-liner and keep the test green.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "render.FrameProbeTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Register the probe and fix `MetricViewer`**

In `EnPustTil.kt`, find the `if (devMode)` block at ~1505:

```kotlin
        if (devMode)
        {
            engine.config.logLevel = LogLevel.DEBUG   // belt-and-braces: works even against a built release .exe
            engine.service.add(MetricViewer())        // F3
        }
```

Replace with:

```kotlin
        if (devMode)
        {
            engine.config.logLevel = LogLevel.DEBUG   // belt-and-braces: works even against a built release .exe

            // .start() IS REQUIRED AND WAS MISSING FOR THE WHOLE LIFE OF THIS LINE. Service
            // .isRunning defaults to false and ServiceManagerImpl skips every service where
            // !isRunning(), so `add(MetricViewer())` on its own registered an overlay that was
            // never once ticked. Verified from bytecode and by driving F3 into the running game
            // and screenshotting it. Note it graphs engine.data.getMetrics(), which on this
            // game's path holds only ServiceManagerImpl's three service timers (all ~0.2 ms) —
            // no fps and no frame-time graph. FrameProbe below is the real instrument.
            engine.service.add(MetricViewer().also { it.start() })   // F3
        }

        // Frame-time probe, gated separately from EPT_DEV so it can be run against a release
        // build without turning on DEBUG logging (which is itself a measurable cost).
        if (System.getenv("EPT_PROFILE") == "1")
            engine.service.add(FrameProbe())
```

- [ ] **Step 7: Correct the two documentation claims this task disproves**

In `CLAUDE.md`, the line reading `**`EPT_DEV=1` enables the `MetricViewer` overlay (F3)**...` must say it did **not** work until this change, and that the overlay graphs three service timers rather than frame time. Add one line pointing at `EPT_PROFILE` as the real instrument. Make the same correction in `src/main/kotlin/render/README.md:369`.

Also correct the capture recipe's `sleep 12` / `sleep 14` to `sleep 8`, noting startup measures 2.3 s from JVM start.

- [ ] **Step 8: Verify the probe actually prints, and record the baseline**

Run the measurement protocol at the dev window's default size, then again with `build/resources/main/application-dev.cfg` set to a 1920×1200-equivalent fullscreen run.

Expected: `[FRAME]` lines appear once per second in `/tmp/run.log`. Record both p50/p95 pairs in the commit message — **this is the baseline every later task is judged against.**

If no `[FRAME]` line appears, the `start()` mechanism is wrong; re-read Step 1's output before changing anything else.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/render/FrameProbe.kt src/test/kotlin/render/FrameProbeTest.kt \
        src/main/kotlin/EnPustTil.kt CLAUDE.md src/main/kotlin/render/README.md
git commit -m "a frame-time probe, and the missing start() that kept the F3 overlay dead"
```

---

## Task 2: Turn off `bilinearFix` — the largest single-line effect

Sequenced first among the render changes because it is one uniform and gives an immediate read on how much of the frame the radiance cascades own.

**Files:**
- Modify: `src/main/kotlin/render/DiveLighting.kt` (the `setup` block near line 1274)

**Interfaces:**
- Consumes: `FrameProbe` from Task 1.
- Produces: nothing consumed by later tasks; Task 11 reads the final value.

- [ ] **Step 1: Measure the before, at 1920×1200 fullscreen / 140 m**

Run the measurement protocol. Record p50/p95.

- [ ] **Step 2: Make the change**

In `DiveLighting.setup`, after `system.dithering = 0.6f`, add:

```kotlin
        // BILINEAR FIX OFF — 16 raymarches per pixel become 4.
        //
        // radiance_cascades.frag does 4 rays per pixel; with bilinearFix ON each ray makes
        // FOUR getRadiance calls (one per bilinear tap) instead of one, and each of those
        // marches up to maxSteps = 25 iterations with a textureLod per step plus two
        // scene/metadata fetches. Worst case is about 448 texture fetches PER PIXEL, and they
        // are dependent, incoherent raymarch samples — the most cache-hostile access pattern a
        // GPU has. This was measured as the single heaviest per-pixel item in the frame.
        //
        // What it costs: bilinearFix exists to suppress ringing/banding at cascade boundaries.
        // Turning it off can reintroduce that. It was judged acceptable against the frame-rate
        // win; if banding shows up at a specific depth, that is the thing to re-open, not the
        // frame budget.
        //
        // This is a plain uniform read fresh inside GiRadianceCascades.applyEffect every frame,
        // so it is live — no surface re-init, no restart.
        system.bilinearFix = false
```

- [ ] **Step 3: Measure the after, back to back with Step 1**

Run the measurement protocol again immediately. Record p50/p95.

- [ ] **Step 4: Look at it**

Capture a real window grab at 20 m, 75 m and 140 m using the recipe in `CLAUDE.md` (the `EPT_DEPTH` + Quartz route). Compare against the same depths before the change. You are looking for banding or ringing at cascade boundaries — concentric or stepped artefacts around the torch beam and around pearls.

**Validate each capture actually contains the game**, not the desktop or lock screen.

If the artefacts are unacceptable, revert this task and record the measured saving in the commit message of Task 3 as "available but rejected on looks" — that is a real result, not a failure.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/render/DiveLighting.kt
git commit -m "bilinearFix off: 16 raymarches per pixel become 4"
```

Put the before/after p50 and p95 in the commit body.

---

## Task 3: Drop MSAA16 on the HUD surface

**Files:**
- Modify: `src/main/kotlin/EnPustTil.kt:1380-1385`

**Interfaces:**
- Consumes: `FrameProbe`.
- Produces: nothing.

- [ ] **Step 1: Measure the before**

Run the measurement protocol.

- [ ] **Step 2: Make the change**

At `EnPustTil.kt:1380`:

```kotlin
        val hudSurface = engine.gfx.createSurface(
            name = "hud",
            backgroundColor = Color.BLANK,
            multisampling = Multisampling.MSAA16,
            zOrder = HUD_Z_ORDER
        )
```

becomes:

```kotlin
        // MULTISAMPLING: NONE, DOWN FROM MSAA16, AND MSAA16 HERE WAS A REAL COST.
        //
        // This surface is screen-space text and a handful of rectangles. It was asking for
        // 16x coverage sampling at the FULL framebuffer resolution while the actual 3D world
        // (mainSurface, created by the engine) runs MSAA4. At 3200x1800 the colour attachment
        // alone is ~737 MB — RenderTarget.init allocates a SECOND FBO as a resolve target when
        // hasMultisampling, and RenderTarget.end() does a resolveToFBO blit every single frame.
        // That blit is visible in the GPU profiler's scope list as RESOLVE_FBO (MSAA16).
        //
        // MSAA16 may also exceed GL_MAX_SAMPLES on Apple Silicon's GL 4.1, in which case the
        // driver was silently granting something else anyway and the cost bought nothing at all.
        //
        // NONE rather than MSAA4: the HUD is axis-aligned rectangles and glyphs from a texture
        // atlas, neither of which has the near-horizontal geometry edges MSAA exists to fix.
        // Verified by screen grab before and after — see the commit that introduced this.
        val hudSurface = engine.gfx.createSurface(
            name = "hud",
            backgroundColor = Color.BLANK,
            multisampling = Multisampling.NONE,
            zOrder = HUD_Z_ORDER
        )
```

- [ ] **Step 3: Measure the after, back to back**

Run the measurement protocol.

- [ ] **Step 4: Look at the HUD specifically**

Capture the in-water HUD (depth tape, clock box, air ring) at 140 m before and after. Zoom in on glyph edges and on the rounded corners of the clock box.

If text edges are unacceptably jagged, change `NONE` to `Multisampling.MSAA4` — matching the world — and re-measure. Do not go back to 16.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS. Nothing should depend on the HUD's sample count, and if something does, that is worth knowing.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/EnPustTil.kt
git commit -m "hud surface drops from MSAA16 to NONE: 16x coverage sampling on text"
```

---

## Task 4: Collapse the global scene chain

13 passes serving off-screen rays that `DiveLighting` already culls away.

**Files:**
- Modify: `src/main/kotlin/render/DiveLighting.kt` (the `setup` block)

**Interfaces:**
- Consumes: `FrameProbe`.
- Produces: nothing.

- [ ] **Step 1: Measure the before**

Run the measurement protocol.

- [ ] **Step 2: Make the change — both settings together, never one alone**

```kotlin
        // THE GLOBAL SCENE CHAIN — 13 PASSES SERVING RAYS THIS GAME DOES NOT CAST.
        //
        // gi_global_scene and gi_global_sdf each carry a jump-flood + SDF chain: a seed pass,
        // ceil(log2(max(w,h))) ping-ponged flood passes (11 at Retina), and a resolve. That is
        // ~19% of the whole per-frame pixel budget, and it exists to let cascades trace rays
        // against geometry that is OFF SCREEN.
        //
        // We do not need it: every drawLight call in this file is already culled to the visible
        // rect plus LIGHT_CULL_MARGIN_METRES (3 m), so there is no off-screen emitter for an
        // off-screen ray to find.
        //
        // THE TRAP, AND IT IS THE WHOLE REASON THESE TWO LINES ARE ADJACENT: setting
        // traceWorldRays = false does NOT remove the chain. Those surfaces are created
        // unconditionally in GlobalIlluminationSystem.onCreate and their post-processing runs
        // regardless of the flag — traceWorldRays only gates a branch inside
        // radiance_cascades.frag. To actually reclaim the passes you must ALSO shrink the
        // surfaces, because GiJfa's pass count is ceil(log2(max(w,h))) and therefore falls with
        // the texture size. Set both or neither; setting only the flag costs the same and looks
        // like it worked.
        //
        // Coherent as a pair: with the branch off, globalSceneTex is never sampled at all
        // (sampleScene only reaches it when status == GLOBAL), so shrinking it cannot show.
        system.traceWorldRays = false
        system.globalSceneTexScale = 0.15f
```

- [ ] **Step 3: Measure the after, back to back**

Run the measurement protocol. Expect the largest single saving in this plan.

- [ ] **Step 4: Look for light leaking at the frame edges**

Capture at 20 m and 140 m. The specific risk is light that used to arrive from just outside the view no longer arriving — look at the rock walls at the left and right frame edges, where off-screen geometry was previously contributing.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/render/DiveLighting.kt
git commit -m "collapse the global scene chain: 13 passes for rays we never cast"
```

---

## Task 5: Fix `lightTexScale`, `localSceneTexScale` and `maxCascades`

`lightTexScale = 0.5` is **worse than the engine's own 0.4 default**, and not by a little.

**Files:**
- Modify: `src/main/kotlin/render/DiveLighting.kt:1274-1275`
- Create: `src/main/kotlin/render/GiSizing.kt`
- Test: `src/test/kotlin/render/GiSizingTest.kt`

**Interfaces:**
- Consumes: `FrameProbe`.
- Produces: `GiSizing.lightTextureSize(framebufferWidth: Int, framebufferHeight: Int, scale: Float, maxCascades: Int): Pair<Int, Int>` and `GiSizing.cascadeCount(framebufferWidth: Int, framebufferHeight: Int, scale: Float, maxCascades: Int): Int`. **Task 11 depends on both.**

- [ ] **Step 1: Write the failing test for the engine's size formula**

This is the test that earns its keep. `lightTexScale` is a **step function**, not a curve, and no amount of staring at the value reveals that.

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GiSizing` re-implements GlobalIlluminationSystem.lightTextureSizeFunc and the cascade-count
 * formula from GiRadianceCascades.applyEffect, both read out of pulse-engine-0.13.0.jar.
 *
 * WHY A COPY OF ENGINE MATHS IS WORTH HAVING: lightTexScale is a STEP FUNCTION, not a curve.
 * The light texture is rounded UP to a multiple of 2^cascadeCount, and cascadeCount is itself
 * derived from the rounded diagonal — so a "small" change to the scale can cost or save 70-120%
 * of the single most expensive pass in the game. The shipped 0.5 was WORSE than the engine's
 * own 0.4 default for exactly this reason, and nobody could see it by reading the number.
 *
 * These tests pin the mechanism so that a future quality preset cannot silently tip the cascade
 * count and cost more at "Low" than at "Medium".
 */
class GiSizingTest
{
    @Test
    fun `the shipped 0_5 was worse than the engine default 0_4 at 1080p`() {
        // The measured defect: at 1080p, 0.5 tips cascadeCount 6 -> 7 AND rounds 540 -> 640.
        val at40 = GiSizing.lightTextureSize(1920, 1080, 0.4f, maxCascades = 10)
        val at50 = GiSizing.lightTextureSize(1920, 1080, 0.5f, maxCascades = 10)

        assertEquals(6, GiSizing.cascadeCount(1920, 1080, 0.4f, 10))
        assertEquals(7, GiSizing.cascadeCount(1920, 1080, 0.5f, 10))

        val pixels40 = at40.first * at40.second
        val pixels50 = at50.first * at50.second
        assertTrue(pixels50 > pixels40 * 2,
            "0.5 should cost more than double 0.4 at 1080p, got $pixels40 vs $pixels50")
    }

    @Test
    fun `the light texture is rounded up to a multiple of two to the cascade count`() {
        val cascades = GiSizing.cascadeCount(1920, 1080, 0.4f, 10)
        val (w, h) = GiSizing.lightTextureSize(1920, 1080, 0.4f, 10)
        val factor = 1 shl cascades
        assertEquals(0, w % factor, "width $w is not a multiple of $factor")
        assertEquals(0, h % factor, "height $h is not a multiple of $factor")
    }

    @Test
    fun `capping maxCascades caps the count and shrinks the rounding`() {
        assertEquals(6, GiSizing.cascadeCount(3200, 1800, 0.4f, maxCascades = 6))
        val capped = GiSizing.lightTextureSize(3200, 1800, 0.4f, maxCascades = 6)
        val uncapped = GiSizing.lightTextureSize(3200, 1800, 0.4f, maxCascades = 10)
        assertTrue(capped.first * capped.second <= uncapped.first * uncapped.second,
            "capping cascades must not increase the texture")
    }

    @Test
    fun `six cascades still reach past the torch`() {
        // Light propagation is about intervalLength * (4^N - 1) / 3 pixels. At N=6 that is
        // ~1365 px; at ~32.5 px/m that is ~42 m, comfortably past TORCH_REACH_METRES = 24.
        // N=5 would be ~10.5 m and would visibly clip the beam - this is the floor.
        assertTrue(GiSizing.propagationMetres(cascades = 6, pixelsPerMetre = 32.48f) > 24f)
        assertTrue(GiSizing.propagationMetres(cascades = 5, pixelsPerMetre = 32.48f) < 24f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "render.GiSizingTest"`
Expected: FAIL — `Unresolved reference: GiSizing`.

- [ ] **Step 3: Re-read the engine formula before implementing**

Do not implement from this plan's prose. Read it:

```bash
cd /tmp/peek
javap -p -c no/njoh/pulseengine/modules/lighting/global/GlobalIlluminationSystem.class \
  | grep -A40 "lightTextureSizeFunc"
javap -p -c no/njoh/pulseengine/modules/lighting/global/effects/GiRadianceCascades.class \
  | grep -B5 -A30 "applyEffect"
```

Expected shape:

```
tw, th       = ceil(W * scale), ceil(H * scale)
diag         = sqrt(tw^2 + th^2)
cascadeCount = min(ceil(log2(diag) / log2(4)) + 1, maxCascades)
f            = 2^cascadeCount
outW, outH   = ceil(tw / f) * f, ceil(th / f) * f
```

**If what you read differs, the bytecode wins.** Write `GiSizing` to match it and adjust the test's expected numbers to the real formula's output — but keep the *relationships* the tests assert (0.5 worse than 0.4 at 1080p; rounding to a power of two; capping never increases).

- [ ] **Step 4: Implement `GiSizing`**

Create `src/main/kotlin/render/GiSizing.kt` as a pure object with no engine imports, so it stays testable in a headless JVM. Implement `cascadeCount`, `lightTextureSize` and `propagationMetres` per the formula from Step 3, with a class doc citing the two bytecode methods.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "render.GiSizingTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Measure the before**

Run the measurement protocol.

- [ ] **Step 7: Change the three values**

At `DiveLighting.kt:1274-1275`, replace the two assignments (keep the long existing comment above them, and append to it):

```kotlin
        // CORRECTED 2026-08-30, DOWN FROM 0.5. The comment above is preserved because its
        // REASONING about emitter silhouettes is still right; its arithmetic assumption was not.
        //
        // 0.5 was not "a bit more than the 0.4 default" — it was WORSE THAN THE DEFAULT by a
        // step function. GlobalIlluminationSystem.lightTextureSizeFunc rounds the scaled
        // framebuffer UP to a multiple of 2^cascadeCount, and cascadeCount derives from the
        // ROUNDED diagonal. At 1080p, 0.5 tips the count 6 -> 7 and rounds 540 -> 640: +122%.
        // At this Mac's 3200x1800 dev framebuffer it is +73%. render/GiSizing.kt re-implements
        // that formula and GiSizingTest pins it, so the next person to touch this number finds
        // out what it costs instead of guessing.
        system.lightTexScale = 0.4f
        system.localSceneTexScale = 0.4f

        // maxCascades 10 (effective 7) -> 6. Removes a whole cascade pass AND shrinks the
        // size round-up above, because both derive from this number.
        //
        // SAFE, AND 6 IS THE FLOOR. Max light propagation is about
        // intervalLength * (4^N - 1) / 3 pixels: N=7 reaches ~5461 px, N=6 ~1365 px which is
        // ~42 m at this scale — still well past TORCH_REACH_METRES (24 m). N=5 would be ~10.5 m
        // and would visibly clip the torch beam. GiSizingTest asserts exactly that boundary.
        //
        // UNLIKE the scales above, this one is NOT a per-frame uniform: it feeds
        // lightTextureSizeFunc, which is only re-evaluated when the light surface re-initialises.
        // Set here at setup, it is in place before the first frame.
        system.maxCascades = 6
```

- [ ] **Step 8: Measure the after, back to back**

Run the measurement protocol.

- [ ] **Step 9: Look at the light map's resolution**

Capture at 140 m with the torch sweeping. `lightTexScale` down means a blockier light map (LINEAR-upsampled in `final.frag`) and `localSceneTexScale` down means a coarser occluder grid — softer shadow edges and more light leak through thin geometry. Check the torch beam's edge against the rock wall.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/render/GiSizing.kt src/test/kotlin/render/GiSizingTest.kt \
        src/main/kotlin/render/DiveLighting.kt
git commit -m "lightTexScale 0.5 was worse than the 0.4 default: a step function, now pinned by a test"
```

---

## Task 6: Halve two needlessly full-resolution GI surfaces

**Files:**
- Modify: `src/main/kotlin/render/DiveLighting.kt` (in `setup`, after `gi = system`)

**Interfaces:**
- Consumes: `FrameProbe`.
- Produces: nothing.

- [ ] **Step 1: Measure the before**

Run the measurement protocol.

- [ ] **Step 2: Add the two direct surface writes**

These cannot go through a GI property — none reaches them. Add after `gi = system` (~line 1384):

```kotlin
        // TWO GI SURFACES RENDER AT FULL FRAMEBUFFER RESOLUTION WHILE EVERY INPUT THEY CONSUME
        // IS AT HALF. That is pure upsampling: it costs full-res pixels and carries no
        // information the half-res inputs did not already have.
        //
        // Neither is reachable through a GlobalIlluminationSystem property — onCreate builds
        // them at scale 1.0 and no field exposes them. A direct setTextureScale STICKS, though,
        // because onUpdate re-pushes only the seven scales it owns (light_exterior,
        // light_interior, local_sdf, local_scene, global_sdf, global_scene, ao) and never
        // touches these two. Verified against onUpdate's bytecode.
        //
        // gi_light_final hosts GiFinal, whose inputs are all half-scale.
        // gi_normal_map additionally runs a 10-level CustomMipmapGenerator chain EVERY FRAME —
        // one full-frame draw per level — so halving it halves the mip chain too. The cascade
        // shader samples it at 0.5-scale probe centres regardless.
        //
        // Deferred, not immediate: SurfaceImpl.setTextureScale queues renderTarget.init through
        // runOnInitFrame, executed at the top of the next frame.
        engine.gfx.getSurface("gi_light_final")?.setTextureScale(0.5f)
        engine.gfx.getSurface("gi_normal_map")?.setTextureScale(0.5f)
```

**Use the engine's own name constants if they exist** — `GlobalIlluminationSystem.GI_NORMAL_MAP` is already referenced elsewhere in this codebase (`EnPustTil.kt:2288`). Prefer the constant over a string literal wherever one is exposed.

- [ ] **Step 3: Measure the after, back to back**

Run the measurement protocol.

- [ ] **Step 4: Look at normal-mapped surfaces closely**

The risk is coarser normals on the diver and the rock. Capture at 75 m and 140 m with the torch across the rock wall, and compare the wall's surface detail and the diver's lit side.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/render/DiveLighting.kt
git commit -m "gi_light_final and gi_normal_map at half scale: they were upsampling half-res inputs"
```

---

## Task 7: Honest `targetFps`, and the JVM flags the Mac never got

**Files:**
- Modify: `src/main/resources/application.cfg`
- Modify: `build.gradle.kts:6-15` (`applicationDefaultJvmArgs`)
- Modify: `build.gradle.kts` (the macOS `jpackage` task's `--java-options`)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Set `targetFps` to 60**

In `application.cfg`, change `targetFps = 120` to:

```
# 60, DOWN FROM 120 — and 120 was never once reached.
#
# FpsLimiter.sync returns immediately whenever a frame has already overrun its budget, which
# was every frame in every scenario measured (p50 15.4-73.7 ms against a 8.33 ms ask). So the
# old value was not a cap that was being honoured, it was a number with no effect.
#
# The fixed tick is 60 Hz either way (EnPustTil sets fixedTickRate = 60), so simulation is
# identical. What a higher cap buys is smoother camera interpolation on a 120 Hz panel, which
# is worth having ONCE the frame budget can actually deliver it. The graphics menu exposes
# this, so a player on a fast machine can raise it; 0 disables the limiter entirely.
#
# Configuration.setTargetFps is public and PulseEngineImpl.endFrame re-reads config.targetFps
# every single frame, so this is fully live at runtime — no restart to change it.
targetFps = 60
```

- [ ] **Step 2: Give the Mac path its JVM flags**

`build.gradle.kts`'s `application { }` block currently sets only `-XstartOnFirstThread` on macOS. The tuned flags (`-XX:+UseZGC`, heap sizing, `-XX:+DisableExplicitGC`) live in the `launch4j` block, **which only produces the Windows `.exe`** — so they have never run on the machine this game now actually targets.

```kotlin
application {
    mainClass.set("EnPustTilKt")
    // LWJGL/GLFW requires the window to be created on the process's first thread.
    // The Gradle `run` task launches the JVM on a worker thread by default, so on
    // macOS this must be forced explicitly. No-op on other platforms; the Windows
    // release build below uses launch4j's own jvmOptions instead.
    //
    // THE REST OF THIS LIST HAD NEVER RUN ON A MAC. -XX:+UseZGC, the heap sizes and
    // -XX:+DisableExplicitGC were all set in the launch4j block, which builds the WINDOWS exe
    // only — so the tuned GC configuration applied to no machine anybody was playing on once
    // the target was corrected to "a Mac with a controller".
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX)
        applicationDefaultJvmArgs = listOf(
            "-XstartOnFirstThread",

            // Equal min and max: the heap never grows or shrinks, so no resize pause can land
            // inside a frame. 512 MB is ~10x the MEASURED 49 MB peak live set — this game
            // allocates about 1 MB/s and holds 10-30 MB.
            "-Xms512m", "-Xmx512m",

            // Pre-fault all 512 MB once at startup so no page fault happens mid-frame. Only
            // cheap BECAUSE the heap is small; do not pair this with a multi-gigabyte -Xms.
            "-XX:+AlwaysPreTouch",

            // Measured win: removes two System.gc() full pauses (10.0 ms and 12.8 ms) that
            // fire during engine init. PulseEngineImpl.postGameInit calls System.gc() and the
            // engine expects that collection to happen — it does not need to.
            "-XX:+DisableExplicitGC"
        )
}
```

**Deliberately NOT added, with reasons** — record these in the commit body so they are not "fixed" later:
- `-XX:+UseZGC` on macOS. G1 measured **one 1.7 ms young pause per ~20 s**. ZGC's load/store barriers are a per-reference-access tax on the hot path to remove a pause that happens three times a minute. Wrong trade.
- `-Dorg.lwjgl.util.NoChecks=true` in `applicationDefaultJvmArgs`. It drops LWJGL's per-call parameter validation on every GL entry point — a real per-frame saving, but it must be **release-only**, so it goes on the `jpackage` task (Step 3) and never on `./gradlew run`, where mistakes should still throw.
- `-XX:TieredStopAtLevel=1` (kills C2, which the render path needs), a larger `-Xmx` "for safety" (nothing gets safer and pre-touch gets expensive), `-XX:+UseLargePages` (no macOS equivalent), GC ergonomic tuning (at ~1 MB/s allocation G1's defaults are never stressed).

- [ ] **Step 3: Add the release-only flags to the macOS bundle**

Find the `jpackage` invocation's `"--java-options", "-XstartOnFirstThread"` argument in the macOS release task and extend it with the same heap/GC flags **plus** `-Dorg.lwjgl.util.NoChecks=true`. Keep `-XstartOnFirstThread` — the `.app` cannot open a window without it.

- [ ] **Step 4: Verify the release bundle still launches**

```bash
./gradlew buildMacRelease
grep -A12 "JavaOptions" "release/macos/One More Breath.app/Contents/app/One More Breath.cfg"
"release/macos/One More Breath.app/Contents/MacOS/One More Breath" > /tmp/macapp.log 2>&1 &
sleep 12 && pgrep -f "One More Breath" && screencapture -x -o /tmp/macapp.png
pkill -f "One More Breath"
```

Expected: every flag present in the `.cfg`; the process alive; `/tmp/macapp.png` **actually showing the attract screen** — open it and look, do not assume.

- [ ] **Step 5: Measure, and check the boot pauses are gone**

Run: `EPT_PROFILE=1 ./gradlew run 2>&1 | grep -i "Pause Full"` with `-Xlog:gc` temporarily added.
Expected: no `Pause Full (System.gc())` lines.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.cfg build.gradle.kts
git commit -m "targetFps 60, and the jvm flags that only ever reached the windows exe"
```

---

## Task 8: Decide bloom on the evidence

Bloom owns 10 textures and 18 draws — ~7% of the budget — and the project's own capture work established the pearls sit under its 1.4 threshold entirely. It may be costing 7% to do nothing visible.

**Files:**
- Modify: `src/main/kotlin/EnPustTil.kt` (in `onCreate`, after the surfaces exist) — **only if the evidence says remove it**

**Interfaces:**
- Consumes: `FrameProbe`.
- Produces: nothing.

- [ ] **Step 1: Find out what bloom is actually contributing**

Temporarily set `intensity = 0f` on the effect rather than deleting it, so the passes still run and only the visual contribution changes:

```kotlin
        engine.gfx.mainSurface.getPostProcessingEffect("bloom")?.let { /* set intensity 0 */ }
```

Capture at 20 m, 75 m and 140 m, and on the attract screen (which has a bright sunset sky — the most likely place bloom earns its keep). Compare against the same captures with bloom on.

- [ ] **Step 2: Decide, and record the decision either way**

- **If bloom contributes visibly** (most likely on the sky and the water surface), keep it. Revert the temporary change, commit nothing but a note in the spec recording the measurement, and move to Task 9.
- **If it does not**, remove the passes — `intensity = 0` keeps them and saves nothing:

```kotlin
        // BLOOM REMOVED. Not disabled — deleted, because BloomEffect's cost is its 18 draws
        // across a 10-texture down/up chain, and intensity = 0 pays all of that to multiply by
        // zero. ~7% of the per-frame pixel budget.
        //
        // Justified by capture, not by reasoning: its threshold is 1.4 and this project's own
        // pearl measurements put the pearls under it entirely, so the only candidates were the
        // sunset sky and the water surface. Compared at 20/75/140 m and on the attract screen
        // with intensity 0 vs default; see the commit body for what the captures showed.
        engine.gfx.mainSurface.deletePostProcessingEffect("bloom")
```

- [ ] **Step 3: Measure whichever path you took**

Run the measurement protocol. If bloom was kept, this confirms no regression; if removed, it records the saving.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/EnPustTil.kt
git commit -m "bloom: <removed for 7% of the budget | kept, it carries the sunset>"
```

Put the capture comparison in the commit body. **The decision is only defensible with the images behind it.**

---

## Task 9: Stop allocating in the zone bands

The only mechanism in the whole audit that produces a *visible hitch* rather than a uniformly slow frame — and a direct breach of the "no per-frame allocation in the render path" rule.

**Files:**
- Modify: `src/main/kotlin/dive/Zone.kt:21`
- Modify: `src/main/kotlin/render/DiveRenderer.kt:505` and the `zoneBlueAt` declaration at `:1655`
- Test: `src/test/kotlin/dive/ZoneTest.kt` (extend)

**Interfaces:**
- Consumes: nothing.
- Produces: `DiveRenderer.zoneColourAt(depth: Float, out: FloatArray)` writing r,g,b into `out[0..2]`. Nothing later depends on it.

- [ ] **Step 1: Write the failing test for allocation-free zone lookup**

```kotlin
    @Test
    fun `at returns the deepest zone whose minDepth the depth has reached`() {
        assertEquals(Zone.SHALLOWS, Zone.at(0f))
        assertEquals(Zone.SHALLOWS, Zone.at(29.99f))
        assertEquals(Zone.KELP, Zone.at(30f))          // boundary is inclusive
        assertEquals(Zone.ABYSS, Zone.at(120f))
        assertEquals(Zone.ABYSS, Zone.at(1000f))       // past the last band
    }

    @Test
    fun `at handles a negative depth as the shallows rather than falling off the front`() {
        // The diver is above the waterline in attract mode, so this is reachable, not theoretical.
        assertEquals(Zone.SHALLOWS, Zone.at(-5f))
    }

    @Test
    fun `at does not allocate`() {
        // The old form was `entries.lastOrNull { ... }`, which compiles to
        // EnumEntriesList.listIterator() -> new AbstractList$ListIteratorImpl: ONE allocation
        // per call. DiveRenderer.drawZoneBands calls it five times per strip across ~111
        // strips, i.e. 555 allocations every frame. Verified from bytecode.
        //
        // Measured by allocation count rather than by reading the source, because the source
        // form that allocates and the form that does not look almost identical.
        val before = allocatedBytes()
        var sink = Zone.SHALLOWS
        repeat(100_000) { sink = Zone.at(it % 200f) }
        val after = allocatedBytes()
        assertTrue(sink == Zone.ABYSS || sink != Zone.ABYSS)  // keep `sink` live
        assertTrue(after - before < 100_000,
            "Zone.at allocated ${after - before} bytes over 100k calls — it should allocate none")
    }

    private fun allocatedBytes(): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
                as com.sun.management.ThreadMXBean
        return bean.getThreadAllocatedBytes(Thread.currentThread().id)
    }
```

**If `com.sun.management.ThreadMXBean` is unavailable in this toolchain**, replace the allocation assertion with a bytecode check instead — assert that the compiled `Zone$Companion.class` contains no `listIterator` reference:

```kotlin
    @Test
    fun `at compiles without an iterator`() {
        val bytes = java.io.File("build/classes/kotlin/main/dive/Zone\$Companion.class").readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(!text.contains("listIterator"),
            "Zone.at still compiles to a listIterator call, i.e. it allocates once per call")
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "dive.ZoneTest"`
Expected: FAIL on the allocation test.

- [ ] **Step 3: Rewrite `Zone.at`**

```kotlin
    companion object
    {
        /**
         * The zone table as an array, held once.
         *
         * `entries` is a `kotlin.enums.EnumEntriesList extends AbstractList`, and ANY iterator
         * over it — which `lastOrNull { }`, `firstOrNull { }`, `for (z in entries)` and
         * `reversed()` all take — allocates an `AbstractList$ListIteratorImpl` per call.
         * [at] is called five times per gradient strip across ~111 strips in
         * `DiveRenderer.drawZoneBands`, i.e. 555 allocations per frame at 120 fps, which is
         * the single largest source of garbage in the render path and the only one with a
         * mechanism (young-gen GC) for producing a visible hitch.
         */
        private val ZONES = entries.toTypedArray()

        /**
         * The deepest zone whose [minDepth] this depth has reached, [SHALLOWS] above them all.
         *
         * A descending index loop, NOT `entries.lastOrNull { }` — see [ZONES]. Behaviour is
         * identical; only the garbage is gone.
         */
        fun at(depth: Float): Zone
        {
            for (i in ZONES.indices.reversed())
                if (depth >= ZONES[i].minDepth) return ZONES[i]
            return SHALLOWS
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "dive.ZoneTest"`
Expected: PASS.

- [ ] **Step 5: Stop `drawZoneBands` computing five blends where three suffice**

`DiveRenderer.kt:505` currently reads:

```kotlin
            surface.setDrawColor(zoneRedAt(centreDepth), zoneGreenAt(centreDepth), zoneBlueAt(centreDepth), 1f)
```

and `zoneBlueAt` (`:1655`) internally re-calls `zoneRedAt(depth)` and `zoneGreenAt(depth)` — the two values just computed on the same line. That is 222 duplicated blends per frame.

Add beside `zoneBlueAt`, keeping `zoneBlueAt` itself for its existing test callers:

```kotlin
    /**
     * The band colour at [depth], written into [out] as r, g, b.
     *
     * Exists because the obvious call —
     * `setDrawColor(zoneRedAt(d), zoneGreenAt(d), zoneBlueAt(d), 1f)` — computes red and green
     * TWICE: [zoneBlueAt] re-derives both to feed [floorBlueForReflectance]. Across ~111 strips
     * that was 222 redundant blends and 222 redundant `srgbToLinear` pow calls every frame.
     *
     * Takes an output array rather than returning one because the render path must not
     * allocate; the caller holds a single reusable FloatArray(3) in a field.
     */
    internal fun zoneColourAt(depth: Float, out: FloatArray)
    {
        val r = zoneRedAt(depth)
        val g = zoneGreenAt(depth)
        out[0] = r
        out[1] = g
        out[2] = floorBlueForReflectance(r, g, rawZoneBlueAt(depth))
    }
```

Add a `private val bandColour = FloatArray(3)` field to `DiveRenderer` and change the draw line to:

```kotlin
            zoneColourAt(centreDepth, bandColour)
            surface.setDrawColor(bandColour[0], bandColour[1], bandColour[2], 1f)
```

- [ ] **Step 6: Add a test that the two paths agree**

```kotlin
    @Test
    fun `zoneColourAt agrees with the three single-channel functions it replaces`() {
        // The point of zoneColourAt is to compute the SAME colour with less work. If it ever
        // disagrees, the zone bands change appearance and no other test would notice.
        val out = FloatArray(3)
        for (depth in intArrayOf(0, 15, 30, 59, 60, 95, 120, 160)) {
            val d = depth.toFloat()
            renderer.zoneColourAt(d, out)
            assertEquals(renderer.zoneRedAt(d), out[0], 1e-6f, "red at $d")
            assertEquals(renderer.zoneGreenAt(d), out[1], 1e-6f, "green at $d")
            assertEquals(renderer.zoneBlueAt(d), out[2], 1e-6f, "blue at $d")
        }
    }
```

Match the existing `DiveRendererTest`'s way of obtaining a renderer instance; if it tests these as top-level/internal functions rather than through an instance, follow that.

- [ ] **Step 7: Fix the attract screen's two per-frame allocations**

At `EnPustTil.kt:2692`, `val cold = Color(0.75f, 0.85f, 1f)` allocates **every attract frame**, 70 lines below a comment forbidding exactly that. Hoist it to a `private val` on the companion or the class.

`drawLeaderboard` calls `ScoreRepository.topN` → `Leaderboard.rank`, which does `entries.filter { }` then `sortedWith(compareByDescending<ScoreEntry> { it.score }.thenBy { it.timestampMs })` — two `Comparator` objects, several intermediate collections, and **one boxed `Integer` plus one boxed `Long` per comparison**, growing as scores accumulate through the day. Cache the ranked list in `ScoreRepository` and invalidate it in `registerScore`.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew test`
Expected: PASS. `DiveRendererTest`'s existing zone-colour assertions are the guard that this changed nothing visible.

- [ ] **Step 9: Measure frame-time VARIANCE, not p50**

Run the measurement protocol. This task barely moves p50 — **compare p95 and worst**, which is where allocation hitches live.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/dive/Zone.kt src/main/kotlin/render/DiveRenderer.kt \
        src/main/kotlin/EnPustTil.kt src/main/kotlin/score/ScoreRepository.kt \
        src/test/kotlin/dive/ZoneTest.kt src/test/kotlin/render/DiveRendererTest.kt
git commit -m "555 allocations a frame in the zone bands, and a Color built every attract frame"
```

---

## Task 10: Confirm 60 fps, honestly

**Files:** none — this is a measurement task with a written result.

- [ ] **Step 1: Full measurement sweep**

Run the measurement protocol at each of: attract fullscreen; 20 m fullscreen; 140 m fullscreen; 140 m dev window. Record p50/p95/worst for each.

- [ ] **Step 2: Compare against the recorded baseline**

Baseline from Task 1, Step 8. State plainly:
- Did 1920×1200 fullscreen / 140 m reach **p50 ≤ 16.67 ms**?
- What is p95? A p50 at 60 fps with a p95 at 40 fps is not 60 fps.

- [ ] **Step 3: Verify the GPU is no longer pinned**

`ioreg -r -d 1 -c AGXAccelerator | grep "Device Utilization %"` while running. If it still reads 100%, the game is still GPU-saturated and the remaining headroom is in further pass removal, not elsewhere.

- [ ] **Step 4: If 60 was not reached, say so and name what is left**

Do not claim success on a p50 that only just crosses under load. The honest options if short:
- Lower `renderScale` below 1.0 (the menu plan's knob) — attacks the 10.5 ms/megapixel term directly.
- Revisit bloom if it was kept.
- `GI off` remains explicitly out of scope; do not reach for it here.

- [ ] **Step 5: Write the result into the spec and commit**

Append a "Measured result" section to `docs/superpowers/specs/2026-08-30-rendering-performance-design.md` with the before/after table and the verdict.

```bash
git add docs/superpowers/specs/2026-08-30-rendering-performance-design.md
git commit -m "record the measured result of the performance work"
```

---

## Task 11: Extract the surviving values into a preset ladder

The menu plan consumes this. Do this **last**, when the values are settled and measured, not before.

**Files:**
- Create: `src/main/kotlin/render/GraphicsQuality.kt`
- Modify: `src/main/kotlin/render/DiveLighting.kt` (`setup` reads the HIGH preset instead of literals)
- Test: `src/test/kotlin/render/GraphicsQualityTest.kt`

**Interfaces:**
- Consumes: `GiSizing.lightTextureSize` and `GiSizing.cascadeCount` from Task 5.
- Produces: `enum class GraphicsQuality { LOW, MEDIUM, HIGH }` and `data class GiSettings(val lightTexScale: Float, val localSceneTexScale: Float, val globalSceneTexScale: Float, val maxCascades: Int, val bilinearFix: Boolean, val traceWorldRays: Boolean, val bloom: Boolean, val hudMultisampling: Int)`, plus `GraphicsQuality.settings(): GiSettings`. **The menu plan's `GraphicsApplier` depends on all of these.**

- [ ] **Step 1: Write the failing test — the ladder must be monotonic through the engine's own formula**

This is the test the whole task exists for.

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The quality ladder, checked against the engine's real sizing maths rather than against
 * intuition.
 *
 * WHY THIS IS NOT AN OBVIOUS TEST: "Low is cheaper than High" looks like it cannot fail. It
 * absolutely can. lightTexScale feeds a STEP FUNCTION (see GiSizingTest) in which a smaller
 * scale can tip the cascade count and round the light texture UP to a larger size — the shipped
 * 0.5 was more expensive than the engine's 0.4 default for exactly that reason. So a future
 * edit that "turns Low down a bit more" can silently make Low cost more than Medium, and
 * nothing else in this codebase would notice.
 */
class GraphicsQualityTest
{
    // The two framebuffers this game actually runs at: the release fullscreen path and the
    // Retina dev window. A ladder that is monotonic at one and not the other is still broken.
    private val framebuffers = listOf(1920 to 1200, 3200 to 1800)

    @Test
    fun `light texture pixels fall monotonically from high to low`() {
        for ((w, h) in framebuffers) {
            val pixels = listOf(GraphicsQuality.HIGH, GraphicsQuality.MEDIUM, GraphicsQuality.LOW)
                .map { q ->
                    val s = q.settings()
                    val (tw, th) = GiSizing.lightTextureSize(w, h, s.lightTexScale, s.maxCascades)
                    tw.toLong() * th.toLong()
                }
            assertTrue(pixels[0] >= pixels[1],
                "at ${w}x$h MEDIUM light texture (${pixels[1]}) exceeds HIGH (${pixels[0]})")
            assertTrue(pixels[1] >= pixels[2],
                "at ${w}x$h LOW light texture (${pixels[2]}) exceeds MEDIUM (${pixels[1]})")
        }
    }

    @Test
    fun `every preset keeps at least six cascades so the torch beam is not clipped`() {
        for ((w, h) in framebuffers)
            for (q in GraphicsQuality.entries) {
                val s = q.settings()
                assertTrue(GiSizing.cascadeCount(w, h, s.lightTexScale, s.maxCascades) >= 6,
                    "$q at ${w}x$h drops below 6 cascades, which visibly clips the torch")
            }
    }

    @Test
    fun `scene and light scales move together across the ladder`() {
        // DiveLighting's own note: raising only the light map buys a smoother upscale of the
        // same coarse occlusion; raising only the scene marches a finer scene into a map that
        // cannot carry it. A preset that separates them is a mistake.
        for (q in GraphicsQuality.entries) {
            val s = q.settings()
            assertTrue(s.lightTexScale == s.localSceneTexScale,
                "$q separates lightTexScale (${s.lightTexScale}) from localSceneTexScale (${s.localSceneTexScale})")
        }
    }

    @Test
    fun `no preset re-enables work a cheaper preset turned off`() {
        val high = GraphicsQuality.HIGH.settings()
        val medium = GraphicsQuality.MEDIUM.settings()
        val low = GraphicsQuality.LOW.settings()
        assertTrue(!low.bloom || medium.bloom, "LOW enables bloom that MEDIUM disables")
        assertTrue(!low.bilinearFix || medium.bilinearFix, "LOW enables bilinearFix that MEDIUM disables")
        assertTrue(low.globalSceneTexScale <= medium.globalSceneTexScale)
        assertTrue(medium.globalSceneTexScale <= high.globalSceneTexScale)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "render.GraphicsQualityTest"`
Expected: FAIL — `Unresolved reference: GraphicsQuality`.

- [ ] **Step 3: Write `GraphicsQuality`**

Create `src/main/kotlin/render/GraphicsQuality.kt`, engine-free. `HIGH` carries **the values this plan actually landed on** (i.e. what Tasks 2–8 left in `DiveLighting.setup` — not the original pre-optimisation values, which nobody should be able to select). `MEDIUM` and `LOW` step down from there.

Give the file a class doc that states: HIGH is the measured shipping configuration; the ladder's values were each measured individually in Tasks 2–8; and any change must keep `GraphicsQualityTest` green because the sizing maths is not monotonic in the obvious way.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "render.GraphicsQualityTest"`
Expected: PASS, 4 tests. **If the monotonicity test fails, the preset values are wrong — fix the values, not the test.**

- [ ] **Step 5: Make `DiveLighting.setup` read the preset**

Replace the literal assignments in `setup` with reads from `GraphicsQuality.HIGH.settings()`, keeping every explanatory comment in place — the comments are the evidence and must not be lost in the refactor.

- [ ] **Step 6: Measure that nothing changed**

Run the measurement protocol. This task is a pure refactor: **p50 must match Task 10's recorded figure.** If it moved, a value was transcribed wrong.

- [ ] **Step 7: Run the full suite and commit**

```bash
./gradlew test
git add src/main/kotlin/render/GraphicsQuality.kt src/test/kotlin/render/GraphicsQualityTest.kt \
        src/main/kotlin/render/DiveLighting.kt
git commit -m "extract the measured settings into a quality ladder the menu can select"
```

---

## Task 12: The documentation corrections this work owes

Four claims in the repo were disproved during the audit. Leaving them standing guarantees the next reader re-derives the wrong conclusion.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `src/main/resources/application.cfg`
- Modify: `src/main/resources/init.pes`

- [ ] **Step 1: Correct the runtime-mutability claims**

`CLAUDE.md:212`, `application.cfg`'s booth header, and `init.pes` all state that `screenMode` and window size cannot be changed at runtime. Replace with the verified position:

- `Window.updateScreenMode(ScreenMode)` is a **public interface method**. `WindowImpl` defers it through `runOnInitFrame`; the lambda calls `createWindow()` passing the previous handle as GLFW's **share** parameter, so the new window inherits the GL context's objects, then fires `resizeCallBack(w, h, windowRecreated = true)` → `gfx.onWindowChanged`, re-initialising every surface and re-projecting every camera. `init.pes` names this mechanism one paragraph before denying it.
- Window size: there is genuinely **no `glfwSetWindowSize` in the jar**, but the window is created `GLFW_RESIZABLE`, the framebuffer-size callback is wired, and `SurfaceImpl.init` reallocates every render texture on resize — **dragging the window edge already works end to end today**.

Keep the `application.cfg` / `application-dev.cfg` split explanation: it is still the right mechanism for the *initial* size, and still correct that `ConfigurationImpl.setWindowWidth/Height` have no effect after startup.

- [ ] **Step 2: Record the newly discovered engine facts**

Add to `CLAUDE.md`'s platform-constraints list:

- **Game callbacks run on a separate `"game"` thread, not the GL/main thread.** `gameLoopMode` defaults to `MULTITHREADED` and nothing here overrides it; `gfx.drawFrame` and `swapBuffers` run on the main thread. This constrains where a direct GLFW/GL call may be made.
- **GL timer queries are dead on Apple Silicon.** `config.gpuProfiling = true` yields a well-formed 85-scope tree with `t=0ns` on every node, because `GpuTimeQuery` uses `GL33.glQueryCounter`. The pass **names** remain useful; the times do not exist.
- **`data.gpuRenderTimeMs` is CPU wall time**, measured around `drawFrame` + `swapBuffers`.
- **Every boot logs 31 ERROR lines and all are benign** — two for `pulseengine/shaders/error/error.comp` (a compute shader GL 4.1 on macOS does not have) and 29 `Config property … not found` for the deliberately commented-out `dailySeed`, button-map and `stickDeadzone` keys, whose fallbacks fire correctly on the next line. Worth writing down precisely because 31 ERRORs on a clean boot is exactly the noise that hides a real fault.

- [ ] **Step 3: Record the performance shape**

Add a short section to `CLAUDE.md` capturing what the next person needs before touching rendering:

- The game is **GPU-bound with the GPU pinned at 100%**; its own Kotlin is 0.3 ms of the frame.
- Frame time fits **`13 ms + 10.5 ms per megapixel`**; the fixed term is real GPU work (fixed-size GI passes), not driver overhead.
- **Fullscreen is the cheap path** — `screenMode = FULLSCREEN` takes the display's default video mode (1920×1200 here), while a 1600×900 dev window is a 3200×1800 Retina framebuffer, 2.5× the pixels. Dev-window observations overstate the real cost.
- **Pass count matters more here than on the Windows booth machine**, because Apple GPUs are tile-based deferred renderers that pay a tile load/store per pass.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md src/main/resources/application.cfg src/main/resources/init.pes
git commit -m "correct four documented claims the performance audit disproved"
```

---

## Self-Review

**Spec coverage:** §1 measured situation → Task 1 baseline + Task 10 verification. §2 change 01 → Task 3. 02 → Task 4. 03 → Task 2. 04 (incl. `maxCascades`) → Task 5. 05 → Task 6. 06 → Task 8. 07 → Task 7. 08 → Task 7. 09 → Task 9. §2 "Not doing" → respected; `GI off` explicitly excluded in Task 10 Step 4. §3 measurement → the Measurement Protocol, referenced by every task. §4 sequencing → task order matches exactly. §5 documentation corrections → Task 12, plus the `MetricViewer`/startup corrections in Task 1 Step 7.

**Placeholder scan:** Task 8 deliberately branches on a capture result rather than prescribing an outcome — that is a decision the evidence must make, and both branches are fully specified. Task 1 Step 4's `formatLine` carries an explicit instruction to replace the shown body with a clean five-placeholder `format` call; that is a warning against copying, not a placeholder. Task 5 Step 3 and Task 11 Step 3 direct the implementer to the bytecode/measured values rather than hard-coding numbers this plan cannot know yet — intentional, and each says exactly where to get them.

**Type consistency:** `FrameProbe.percentile(FloatArray, Int, Float)` and `formatLine(Float, Float, Float, Int)` are used identically in the test and the implementation. `GiSizing.lightTextureSize(Int, Int, Float, Int): Pair<Int,Int>`, `cascadeCount(Int, Int, Float, Int): Int` and `propagationMetres(Int, Float): Float` are declared in Task 5 and consumed with matching signatures in Task 11. `GiSettings`' eight fields are named consistently between Task 11's Interfaces block, its test, and the menu plan's `GraphicsApplier`. `DiveRenderer.zoneColourAt(Float, FloatArray)` is declared and called consistently in Task 9.
