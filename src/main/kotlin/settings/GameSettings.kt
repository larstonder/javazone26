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
    val showFps: Boolean
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

        return copy(
            quality = clampedQuality,
            windowWidth = clampedResolution.first,
            windowHeight = clampedResolution.second,
            renderScale = clampedRenderScale,
            frameCap = clampedFrameCap
        )
    }

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

        /** Mirrors `render.GraphicsQuality.entries.map { it.name }` as string literals rather
         * than an import of `render` - this class is meant to have no dependency on the render
         * package at all (see the class doc on why `quality` is a String), so the names are
         * checked against the real enum by hand rather than referenced live. Keep in sync with
         * `render/GraphicsQuality.kt`'s `LOW, MEDIUM, HIGH`. */
        private val KNOWN_QUALITIES: Set<String> = setOf("LOW", "MEDIUM", "HIGH")

        /** Fullscreen at 1920x1200 measured 1.74x faster than windowed at 2048x1152 - the same
         * pixel count - most likely macOS compositor bypass. Matches the shipping
         * application.cfg's screenMode, so a fresh settings file agrees with the booth default. */
        val DEFAULT = GameSettings(
            quality = "HIGH",
            windowWidth = 1600,
            windowHeight = 900,
            renderScale = 1.0f,
            fullscreen = true,
            frameCap = 0,
            vsync = false,
            showFps = false
        )
    }
}
