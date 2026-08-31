package settings

/**
 * Player-facing graphics/window settings, persisted to disk by `SettingsStore` (Task 3) and
 * applied to the running engine by `GraphicsApplier` (Task 6).
 *
 * `quality` is a `String`, not a `render.GraphicsQuality`, and that is deliberate. This class
 * is deserialised from a JSON file that an older or newer build may have written, that a
 * player could hand-edit, or that a partial write could truncate — a `String` plus a lookup
 * in [clamped] degrades an unknown value to the default, whereas an enum field would make
 * Jackson throw while parsing the whole file and take every OTHER setting down with it too.
 * `GraphicsApplier` is the one place that resolves the name to an actual
 * `render.GraphicsQuality`.
 *
 * WHY CLAMPING LIVES HERE AND NOWHERE ELSE: every consumer of a `GameSettings` instance —
 * `GraphicsApplier` above all — is entitled to assume the instance it was handed is already
 * valid. That means there has to be exactly one place that defends against nonsense arriving
 * from disk, and [clamped] is it. `SettingsStore.load` is required to call it before handing
 * a `GameSettings` to anything else.
 */
data class GameSettings(
    val quality: String,
    val windowWidth: Int,
    val windowHeight: Int,
    val renderScale: Float,
    val fullscreen: Boolean,
    val frameCap: Int,
    val vsync: Boolean,
    val showFps: Boolean,
    // --- The six live GI knobs (2026-08-31, gi-knobs-brief.md) --------------------------
    // These reverse the original "presets, not individual knobs" decision (section 1's
    // decision table, updated alongside this) - the owner asked for the six knobs directly.
    // `GraphicsApplier.apply` reads these SIX FIELDS, never `GraphicsQuality` directly - see
    // that class's doc. Selecting a named QUALITY preset writes all six from
    // `GraphicsQuality.X.settings()`; touching any one of them individually sets `quality` to
    // "CUSTOM" instead (see EnPustTil.stepGameSettings) so the displayed preset name and the
    // applied values can never disagree - the entire reason CUSTOM exists rather than the menu
    // silently keeping the old preset's name after a knob has been nudged away from it.
    val lightTexScale: Float,
    val localSceneTexScale: Float,
    val globalSceneTexScale: Float,
    val maxCascades: Int,
    val bilinearFix: Boolean,
    val traceWorldRays: Boolean
)
{
    /**
     * Returns a copy with every field pulled back into its declared range. Idempotent —
     * clamping an already-valid instance, or a once-clamped instance, changes nothing further.
     */
    fun clamped(): GameSettings
    {
        // Float.NaN.coerceIn(a, b) returns NaN (IEEE 754: every comparison against NaN is
        // false, so coerceIn's own bounds checks silently no-op) - a truncated JSON write can
        // produce NaN, and it must not sail through to setTextureScale. Fall back to the
        // default's render scale rather than an arbitrary bound when the value isn't even a
        // real number.
        val clampedRenderScale =
            if (renderScale.isFinite()) renderScale.coerceIn(RENDER_SCALES.min(), RENDER_SCALES.max())
            else DEFAULT.renderScale

        // Window size clamps to the nearest listed resolution by total pixel distance, rather
        // than clamping width and height independently - independent clamping could produce a
        // pair that isn't itself a listed resolution.
        val clampedResolution =
            if (RESOLUTIONS.contains(windowWidth to windowHeight)) windowWidth to windowHeight
            else RESOLUTIONS.minBy { (w, h) ->
                val dw = (w - windowWidth).toLong()
                val dh = (h - windowHeight).toLong()
                dw * dw + dh * dh
            }

        // 0 means "no limiter" (FpsLimiter.sync returns immediately when fps <= 0) and must
        // survive untouched; any other value snaps to the nearest listed cap.
        val clampedFrameCap =
            if (frameCap == 0) 0
            else if (FRAME_CAPS.contains(frameCap)) frameCap
            else FRAME_CAPS.filter { it != 0 }.minBy { kotlin.math.abs(it - frameCap) }

        val clampedQuality = if (quality in KNOWN_QUALITIES) quality else DEFAULT.quality

        // The six GI knobs snap to the nearest rung on their own ladder, same shape as
        // renderScale/frameCap above - a hand-edited or cross-build settings.json can carry a
        // value that is not one of these lists at all, and clamping degrades it to the nearest
        // real rung rather than letting it sail through to GraphicsApplier.apply, which writes
        // it straight onto the live GlobalIlluminationSystem with no validation of its own.
        val clampedLightTexScale = nearestOnLadder(lightTexScale, LIGHT_TEX_SCALES, DEFAULT.lightTexScale)
        val clampedLocalSceneTexScale = nearestOnLadder(localSceneTexScale, SCENE_TEX_SCALES, DEFAULT.localSceneTexScale)
        val clampedGlobalSceneTexScale = nearestOnLadder(globalSceneTexScale, GLOBAL_TEX_SCALES, DEFAULT.globalSceneTexScale)
        val clampedMaxCascades =
            if (maxCascades in MAX_CASCADES_STEPS) maxCascades
            else MAX_CASCADES_STEPS.minBy { kotlin.math.abs(it - maxCascades) }

        return copy(
            quality = clampedQuality,
            windowWidth = clampedResolution.first,
            windowHeight = clampedResolution.second,
            renderScale = clampedRenderScale,
            frameCap = clampedFrameCap,
            lightTexScale = clampedLightTexScale,
            localSceneTexScale = clampedLocalSceneTexScale,
            globalSceneTexScale = clampedGlobalSceneTexScale,
            maxCascades = clampedMaxCascades
            // bilinearFix/traceWorldRays are Booleans - every value a Boolean field can hold
            // from a JSON boolean is already valid, so there is nothing to clamp (a truncated
            // write that leaves the field absent falls back to Jackson/the loader's own default
            // handling upstream of this function, exactly like showFps/vsync/fullscreen above).
        )
    }

    /**
     * [value] snapped to the nearest entry in [ladder] by absolute distance, or [fallback] (an
     * on-ladder value by construction - every `DEFAULT.*` field this is called with is itself
     * one of its own ladder's entries) when [value] is not a real number at all (NaN/infinite -
     * see the Float.NaN comment on [clampedRenderScale] above; `minBy` on a NaN distance would
     * not throw, but every comparison against NaN is false, so which entry `minBy` returns
     * would be an artifact of iteration order rather than a real "nearest" - so this is checked
     * explicitly rather than trusted to fall out of `minBy` on its own).
     */
    private fun nearestOnLadder(value: Float, ladder: List<Float>, fallback: Float): Float =
        if (!value.isFinite()) fallback
        else ladder.minBy { kotlin.math.abs(it - value) }

    companion object
    {
        /** Common 16:9 and 16:10 window sizes, in logical points (see `engine.window`'s
         * physical-vs-logical distinction in the repo's CLAUDE.md). 1600x900 is today's dev
         * default (`application-dev.cfg`); 1920x1200 is the fullscreen size the 1.74x
         * measurement below was taken at. */
        val RESOLUTIONS: List<Pair<Int, Int>> = listOf(
            1280 to 720,
            1600 to 900,
            1920 to 1080,
            1920 to 1200,
            2560 to 1440,
            3200 to 1800
        )

        /** Render-scale ladder. 1.0 is native; below it trades sharpness for fill-rate. */
        val RENDER_SCALES: List<Float> = listOf(0.5f, 0.6f, 0.75f, 0.85f, 1.0f)

        /** Frame-cap ladder. 0 is uncapped - see [clamped]'s comment on why it survives as a
         * distinct value rather than clamping toward a real cap. */
        val FRAME_CAPS: List<Int> = listOf(0, 30, 60, 120, 144)

        /** Mirrors `render.GraphicsQuality.entries.map { it.name }`, PLUS `"CUSTOM"`, as string
         * literals rather than an import of `render` - this class is meant to have no
         * dependency on the render package at all (see the class doc on why `quality` is a
         * String), so the names are checked against the real enum by hand rather than
         * referenced live. Keep the three preset names in sync with `render/GraphicsQuality.kt`'s
         * `LOW, MEDIUM, HIGH`.
         *
         * `"CUSTOM"` (2026-08-31, gi-knobs-brief.md) is deliberately NOT a `GraphicsQuality`
         * entry and never will be - it is what `quality` becomes the moment any of the six GI
         * knobs is touched individually (see the six-knob fields' doc above), and
         * `GraphicsApplier.qualityFor` must NOT resolve it to a real preset (seeing "CUSTOM"
         * fall through to `qualityFor`'s catch-and-degrade path, which is exactly what happens
         * today since `GraphicsQuality.valueOf("CUSTOM")` throws, is what keeps `apply`'s
         * bloom/hudMultisampling read - the two GI fields that are NOT among the six knobs -
         * on a sane preset rather than crashing).
         *
         * `internal`, not `private`: this hand-kept duplicate can drift silently from the real
         * enum (a fourth preset added to `GraphicsQuality` would make this set start rejecting
         * it as unknown, degrading it to the default with no error anywhere) - `render`'s
         * `GraphicsApplierTest` asserts the two stay equal (GraphicsQuality's names plus
         * "CUSTOM"), which needs to read this set rather than re-derive it, or the check would
         * just restate the literal instead of catching drift. `internal` rather than `public`
         * because the only reader outside this file is a test in the same Gradle module,
         * exactly like `dive.DiveSim`'s `internal fun debug*` hooks. */
        internal val KNOWN_QUALITIES: Set<String> = setOf("LOW", "MEDIUM", "HIGH", CUSTOM_QUALITY)

        /** The one value [KNOWN_QUALITIES] carries that is not a [render.GraphicsQuality] name -
         * see [KNOWN_QUALITIES]'s own doc. A named constant rather than the literal `"CUSTOM"`
         * repeated at every call site (`EnPustTil.stepGameSettings`'s six knob cases) so a
         * rename here cannot happen without every reader failing to compile. */
        const val CUSTOM_QUALITY: String = "CUSTOM"

        // --- The six GI knobs' ladders (2026-08-31, gi-knobs-brief.md) ----------------------
        // Chosen by running every candidate through render.GiSizing.lightTextureSize at both
        // shipped framebuffers (1920x1200, 3200x1800) rather than by area-scaling intuition -
        // see GraphicsQuality's class doc and GiSizing's own doc for why lightTexScale is a
        // STEP FUNCTION where a numerically smaller value can round UP to a bigger, more
        // expensive texture. This class cannot import render.GiSizing (see the class doc on
        // settings having no dependency on render), so the evaluation that justifies these
        // specific numbers lives in the test that checks them
        // (settings.GiKnobLaddersTest/render.GraphicsQualityTest), not here.

        /** LIGHT MAP SCALE ladder. 0.4 is HIGH's existing, measured value (DiveLighting.setup);
         * 0.5 is a deliberate step UP from it for a player who wants more than HIGH offers. */
        val LIGHT_TEX_SCALES: List<Float> = listOf(0.2f, 0.3f, 0.4f, 0.5f)

        /** SCENE SCALE ladder - kept identical in shape to [LIGHT_TEX_SCALES] because
         * DiveLighting's own setup note ("the two must move together") still applies to a
         * QUALITY preset's choice of both; a player moving them independently via the menu is
         * a deliberate escape hatch from that pairing, not evidence the pairing was wrong. */
        val SCENE_TEX_SCALES: List<Float> = listOf(0.2f, 0.3f, 0.4f, 0.5f)

        /** GLOBAL SCALE ladder. 0.15 is HIGH's existing value, held flat across every preset
         * today (see GraphicsQuality's class doc - "never measured independently"). See trap 2
         * in gi-knobs-brief.md: this is the field that actually reclaims the ~19% global-scene
         * jump-flood chain OFF-SCREEN RAYS alone does not. */
        val GLOBAL_TEX_SCALES: List<Float> = listOf(0.1f, 0.15f, 0.2f, 0.3f)

        /** MAX CASCADES ladder. 6 is kept as the FLOOR even though the knob is now independently
         * reachable - not for torch reach (see GiSizing.propagationMetres's KDoc; the reach
         * floor is a relationship between this and [LIGHT_TEX_SCALES], not a constant on either
         * alone), but because it is the cascade count Task 04 of the rendering-performance plan
         * actually measured (GraphicsQuality's class doc). 7 and 8 are deliberate steps UP -
         * a whole extra fill-rate-bound pass each - for a player willing to spend more than
         * HIGH. Every (cascades, scale) pair this ladder crossed with [LIGHT_TEX_SCALES] can
         * reach was checked by hand against GiSizing.propagationMetres before these numbers
         * were chosen; see settings.GiKnobLaddersTest for the assertion that keeps that true. */
        val MAX_CASCADES_STEPS: List<Int> = listOf(6, 7, 8)

        /** Fullscreen at 1920x1200 measured 1.74x faster than windowed at 2048x1152 - the same
         * pixel count - most likely macOS compositor bypass. Matches the shipping
         * application.cfg's screenMode, so a fresh settings file agrees with the booth default.
         *
         * The six GI-knob defaults below are copied from `render.GraphicsQuality.HIGH.settings()`
         * rather than referenced live, for the same "settings has no dependency on render"
         * reason [KNOWN_QUALITIES] gives - `render.GraphicsQualityTest`/`GraphicsApplierTest`
         * are what would catch these going stale if HIGH's own numbers ever move. */
        val DEFAULT = GameSettings(
            quality = "HIGH",
            windowWidth = 1600,
            windowHeight = 900,
            renderScale = 1.0f,
            fullscreen = true,
            frameCap = 0,
            vsync = false,
            showFps = false,
            lightTexScale = 0.4f,
            localSceneTexScale = 0.4f,
            globalSceneTexScale = 0.15f,
            maxCascades = 6,
            bilinearFix = false,
            traceWorldRays = false
        )
    }
}
