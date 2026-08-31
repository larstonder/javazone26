package render

import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.graphics.api.Multisampling
import no.njoh.pulseengine.core.shared.utils.Logger
import no.njoh.pulseengine.core.window.ScreenMode
import no.njoh.pulseengine.modules.lighting.global.GlobalIlluminationSystem
import org.lwjgl.glfw.GLFW
import settings.GameSettings

/**
 * The ONLY file in this game permitted to write engine graphics state — GI scales, the render
 * surface's own texture scale, the target fps, the screen mode, vsync. Everything downstream of
 * a menu selection funnels through [apply]. Read this class doc in full before touching any of
 * the fields it writes; every trap below cost real time to find and is invisible in a diff.
 *
 * ## Why everything can be applied LIVE, with no restart
 *
 * `GlobalIlluminationSystem.onUpdate` runs every frame and unconditionally re-pushes seven
 * texture scales plus `jitterFix`/`globalWorldScale`/`upscaleSmallSources`/`minReflectance`.
 * `SurfaceImpl.setTextureScale` is a no-op if the value is unchanged, and otherwise updates every
 * `TextureDescriptor` and queues `renderTarget.init(...)` through `runOnInitFrame` — executed at
 * the top of the NEXT frame, not this one, but still with no restart and no dropped frame beyond
 * that one-frame lag. Every other GI property (`bilinearFix`, `traceWorldRays`, `maxCascades`'s
 * cost, `dithering`, `aoRadius`) is read fresh inside `applyEffect` each frame and pushed as a
 * plain uniform. **Nothing is latched at engine init.** `Window.updateScreenMode` is a public
 * interface method too (`WindowImpl.updateScreenMode` queues a `runOnInitFrame` lambda that
 * recreates the GL window sharing the OLD context, so surfaces and assets survive) — see this
 * plan's design spec §0 for the two doc claims ("screenMode/window size cannot change at
 * runtime") that this class disproves.
 *
 * ## The four traps
 *
 * 1. **`lightTexScale` is a STEP FUNCTION, not a curve.** `lightTextureSizeFunc` rounds the
 *    scaled framebuffer UP to a multiple of `2^cascadeCount`, and `cascadeCount` is itself
 *    derived from the ROUNDED diagonal — so a numerically SMALLER scale can round UP to a
 *    BIGGER texture than a larger one. This game's own shipped 0.5 was measurably worse than the
 *    engine's 0.4 default for exactly this reason (see `DiveLighting.setup`'s comment on
 *    `lightTexScale`, and `GraphicsQuality`'s class doc). Never choose a value here by assuming
 *    area scaling — evaluate it through [GiSizing], which re-implements the engine's own
 *    formula, at every framebuffer size this game actually ships at.
 *
 * 2. **`traceWorldRays = false` does NOT remove the global scene chain.** `gi_global_scene` and
 *    `gi_global_sdf` — a seed pass, `ceil(log2(max(w,h)))` ping-ponged jump-flood passes, and a
 *    resolve, roughly 13 passes and ~19% of the per-frame pixel budget — are created
 *    UNCONDITIONALLY in `GlobalIlluminationSystem.onCreate`, and their post-processing runs
 *    regardless of the flag; `traceWorldRays` only gates a branch inside
 *    `radiance_cascades.frag`. To actually reclaim those passes you must ALSO drop
 *    `globalSceneTexScale` — set both together or neither, or the "saving" is imaginary and the
 *    frame costs the same as before.
 *
 * 3. **`gi_light_final` and `gi_normal_map` are unreachable through any `GlobalIlluminationSystem`
 *    property**, but a DIRECT `Surface.setTextureScale` call on either one STICKS regardless —
 *    `GlobalIlluminationSystem.onUpdate` only re-pushes the seven scales it owns itself, so a
 *    manual scale on either of these two surfaces is not fought back to 1.0 the next frame the
 *    way a manual edit to one of the seven WOULD be. This is not currently exercised by [apply]
 *    (no preset touches either surface today) — recorded here because the asymmetry is exactly
 *    the kind of thing that looks like a bug in whichever direction you first meet it.
 *
 * 4. **NEVER expose `mergeCascades` on any menu row.** Turning it off collapses lighting to the
 *    single finest interval — visually broken, not merely cheaper. There is deliberately no
 *    [GraphicsQuality] preset and no `GiSettings` field for it; do not add one.
 *
 * ## Two fields this class inherits with weak semantics, handled honestly rather than ignored
 *
 * **`GiSettings.bloom` cannot currently do anything.** The 2026-08-30 performance work removed
 * the `BloomEffect(...)` construction from `DiveLighting.setup` outright (measured
 * sub-perceptual at every depth and on the attract screen, while costing 18 draws across a
 * 10-texture chain — see this repo's CLAUDE.md) rather than leaving it in place and disabled, so
 * there is no longer a call site in this project that builds one. [apply] therefore only ever
 * DELETES a `"bloom"` post-processing effect (a safe, idempotent no-op verified from
 * `SurfaceImpl.deletePostProcessingEffect`'s bytecode: it looks the name up, calls `destroy()`
 * only if found, and the removal loop that follows is a no-op when nothing matches) and logs a
 * warning rather than silently doing nothing if a preset ever asks for `bloom = true` — every
 * shipped [GraphicsQuality] preset sets it `false`, so that branch is unreached today, and it
 * must stay loud rather than quiet if that ever changes.
 *
 * **`GiSettings.hudMultisampling` is an `Int`; `Graphics.createSurface` takes a [Multisampling]
 * enum.** [multisamplingFor] is the mapping (verified from the engine's own bytecode:
 * `NONE(0)`, `MSAA4(4)`, `MSAA8(8)`, `MSAA16(16)`, `MSAA32(32)`, `MSAA_MAX(-1)`), but nothing
 * CONSUMES it today — the HUD surface is created once, in `EnPustTil.onCreate`, before any
 * `GameSettings` has been loaded from disk, and multisampling can only be chosen at
 * `createSurface` time (there is no live "change a surface's multisampling" call, unlike
 * `setTextureScale`). This function exists for the day `onCreate` reads settings before creating
 * the HUD surface; until that wiring lands, changing `hudMultisampling` in a preset has no
 * effect, which is the same "declared but not yet reachable" honesty as `bloom` above.
 *
 * ## VSync — read [reassertVsync]'s own doc; the short version is it must run after every
 * screen-mode change, and nothing else in the engine will do that for you.
 */
class GraphicsApplier(private val engine: PulseEngine)
{
    /**
     * Push [settings] onto the running engine. [gi] is a nullable, caller-supplied reference to
     * the scene's `GlobalIlluminationSystem` rather than something this class looks up itself —
     * finding the right scene/system is the caller's job (today, `EnPustTil` already holds the
     * reference it built in `DiveLighting.setup`), and a scene without the system present (a
     * test scene, a future non-GI mode) is a caller decision this class should not need an
     * opinion on. When [gi] is null, only the non-GI half of [settings] is applied.
     */
    fun apply(settings: GameSettings, gi: GlobalIlluminationSystem?)
    {
        val giSettings = qualityFor(settings.quality).settings()

        if (gi != null)
        {
            // See trap 1 and 2 above before changing any of these five, and trap 4 for the one
            // property that must NEVER be added to this list.
            gi.lightTexScale = giSettings.lightTexScale
            gi.localSceneTexScale = giSettings.localSceneTexScale
            gi.globalSceneTexScale = giSettings.globalSceneTexScale
            gi.maxCascades = giSettings.maxCascades
            gi.bilinearFix = giSettings.bilinearFix
            gi.traceWorldRays = giSettings.traceWorldRays
        }

        engine.gfx.mainSurface.setTextureScale(settings.renderScale)

        applyBloom(giSettings.bloom)

        // vsync and a frame cap must never both be live at once (frameCapFor's own doc) — this
        // is the one call in this function whose ARGUMENT is a decision rather than a value, and
        // that decision is exactly what frameCapFor's own test coverage exists for.
        engine.config.targetFps = frameCapFor(settings)

        engine.window.updateScreenMode(if (settings.fullscreen) ScreenMode.FULLSCREEN else ScreenMode.WINDOWED)

        // MUST run after updateScreenMode, not before — see reassertVsync's own doc for why a
        // screen-mode change is exactly the thing that silently turns vsync back off.
        reassertVsync(settings.vsync)
    }

    /**
     * See the class doc's "`GiSettings.bloom` cannot currently do anything" paragraph. Always
     * deletes the `"bloom"` post-processing effect (safe even if one was never added); only logs
     * — never constructs an effect — when [bloom] asks for it to come back.
     */
    private fun applyBloom(bloom: Boolean)
    {
        engine.gfx.mainSurface.deletePostProcessingEffect(BLOOM_EFFECT_NAME)
        if (bloom)
        {
            Logger.warn {
                "GraphicsApplier: a preset requested bloom=true, but BloomEffect's construction " +
                "was removed from DiveLighting.setup during the 2026-08-30 performance work " +
                "(measured sub-perceptual — see this repo's CLAUDE.md) and this class does not " +
                "reconstruct it. Every shipped GraphicsQuality preset sets bloom=false, so this " +
                "is unreached today; wiring the construction back in belongs here, not silently " +
                "ignored."
            }
        }
    }

    /**
     * There is NO engine API for vsync. `WindowImpl.createWindow` calls
     * `GLFW.glfwSwapInterval(0)` — the ONLY call to that function anywhere in the jar, and it
     * takes no config value — so vsync is unconditionally off the moment any window (including a
     * RECREATED one) exists, and turning it on is only possible by calling GLFW directly, here.
     *
     * **Why this has to be called after EVERY `updateScreenMode`, not just once at startup:**
     * `updateScreenMode` recreates the GL window (`WindowImpl.createWindow` again, sharing the
     * old context) any time the screen mode actually changes, and that recreation runs the exact
     * `glfwSwapInterval(0)` line above a second time. So toggling fullscreen from the menu would
     * SILENTLY turn vsync back off with no error, no log, and no field anywhere recording that it
     * happened — "vsync randomly stops working" is exactly the bug report this would produce if
     * nobody remembered this paragraph. [apply] therefore calls this unconditionally after
     * `updateScreenMode`, every time, rather than only when `settings.vsync` changed.
     *
     * MUST be called from the main thread with the GL context current — `glfwSwapInterval` is a
     * GLFW call like any other GL state change. `apply` calls it from the same thread and the
     * same point in the frame `updateScreenMode` itself must be called from, so no extra
     * threading care is needed at this call site; a caller invoking this OUTSIDE that context
     * (a background thread, a callback with no current GL context) would silently do nothing —
     * the same "no error, no log" failure mode as every GLFW call documented in this repo's
     * CLAUDE.md.
     */
    fun reassertVsync(enabled: Boolean)
    {
        GLFW.glfwSwapInterval(if (enabled) 1 else 0)
    }

    companion object
    {
        private const val BLOOM_EFFECT_NAME = "bloom"

        /**
         * [settings.GameSettings.quality] degraded to a real [GraphicsQuality], falling back to
         * the compiled default rather than throwing — the same "a bad value from disk must never
         * take the whole boot down" contract [settings.GameSettings.clamped] already applies to
         * every other field. `GraphicsQuality.valueOf` throws `IllegalArgumentException` on an
         * unknown name, which is exactly the failure this function exists to absorb.
         */
        fun qualityFor(name: String): GraphicsQuality =
            try { GraphicsQuality.valueOf(name) }
            catch (_: IllegalArgumentException) { GraphicsQuality.valueOf(GameSettings.DEFAULT.quality) }

        /**
         * The fps limiter's target, given [settings]. `0` — the limiter's own "uncapped" value,
         * see `GameSettings.clamped`'s comment — whenever vsync is on, regardless of what
         * `settings.frameCap` says: `FpsLimiter` and `glfwSwapInterval` are two independent
         * frame-pacing mechanisms, and running both at once makes them fight each other rather
         * than agree, which paces WORSE than either alone. Vsync wins the conflict because it is
         * the display's own pacing signal; the limiter is a software approximation of the same
         * idea.
         */
        fun frameCapFor(settings: GameSettings): Int = if (settings.vsync) 0 else settings.frameCap

        /**
         * `GiSettings.hudMultisampling` (an `Int`, chosen for JSON round-tripping through
         * `GameSettings` with no engine import) mapped to the real [Multisampling] the engine's
         * `Graphics.createSurface` actually takes. See this class's doc for why nothing calls
         * this today. An unrecognised sample count degrades to [Multisampling.NONE] — the
         * engine's own cheapest setting — rather than throwing, for the same reason [qualityFor]
         * degrades rather than throws: a malformed value must never take the boot down.
         */
        fun multisamplingFor(samples: Int): Multisampling =
            Multisampling.entries.firstOrNull { it.samples == samples } ?: Multisampling.NONE
    }
}
