package render

import dive.Tuning
import kotlin.math.PI

/**
 * THE BOUNDARY BETWEEN THE SKY AND THE WATER — the wave table it is built from, the band of depth
 * it can occupy, and the one clock in this game that runs on the render loop.
 *
 * Pure Kotlin, no engine imports, exactly like [Framing] and [DepthBlend]: what is worth asserting
 * about a wave is not what it looks like but whether it stays inside the band the renderer
 * reserved for it, and whether its phase can be reproduced. Both are provable without a GL
 * context and neither is visible in a screenshot.
 *
 * ## The boundary is drawn ONCE, and that is the whole design
 *
 * There is a sky ([Sky], on its own surface behind the world) and there is water
 * ([DiveRenderer.drawZoneBands], on `main`), and the obvious way to join them is to have each stop
 * where the other starts. That is two edges that must agree every frame at every aspect ratio, in
 * two different files, one of them multiplied by a light map and the other not — i.e. the exact
 * shape of every drift bug in this project's history.
 *
 * So they do not meet. The SKY IS DRAWN STRAIGHT PAST THE WATERLINE, down to [QUAD_BOTTOM_DEPTH],
 * and the WATER IS DRAWN OVER IT by [WaterRenderer] — one quad, one fragment shader, whose ALPHA
 * is the waterline. Above the wave the shader discards and the sky behind shows through; below it
 * the shader is opaque and hands off to the zone bands at the bottom of its own quad. There is no
 * second edge to keep in sync because there is no second edge: what the player is looking at is
 * one alpha ramp, anti-aliased against whatever happens to be behind it.
 *
 * ## The wave table
 *
 * Three travelling sinusoids, summed. Deep-water waves are dispersive and a real sea is a
 * spectrum; three components at incommensurate wavelengths is the cheapest thing that does not
 * read as a single sine, which is the one thing a wave must not read as. One of them runs the
 * other way so the whole surface does not read as a conveyor belt.
 *
 * The wavelengths are stated in METRES against the play column, not against the screen, so the
 * same ripple appears at the same scale on every panel: the longest is 7.3 m against the 80 m
 * column, i.e. about eleven crests across it. Nothing here is a pixel count and nothing here may
 * become one.
 */
object WaterSurface
{
    /**
     * How long the whole surface takes to return exactly to its starting shape.
     *
     * NOT decoration, and not chosen for the look. THE CABINET RUNS UNATTENDED FOR TWO DAYS. A
     * free-running float clock reaches 172 800 s by the end of day two, where a `Float` has about
     * 0.015 s of resolution — the wave would visibly quantise into steps late on the second
     * afternoon, on a machine nobody is watching and after every test has passed. So the clock
     * wraps, and it wraps at a value that is an exact whole number of cycles for EVERY component
     * ([Component.cyclesPerWrap] is an `Int`), which makes the wrap invisible rather than a jump.
     * At 240 s the clock never leaves a range where a float resolves better than 2e-5 s.
     */
    const val WRAP_SECONDS = 240f

    /**
     * One travelling sinusoid.
     *
     * [cyclesPerWrap] is an INTEGER and is what makes [WRAP_SECONDS] exact — it is the component's
     * speed expressed in the only unit that cannot desynchronise it from the others. Negative
     * means the wave travels the other way. Its speed in metres per second is
     * `wavelengthMetres * cyclesPerWrap / WRAP_SECONDS`, which is 0.88, -0.56 and 0.35 for the
     * three below: a slow travelling ripple.
     */
    class Component(
        val amplitudeMetres: Float,
        val wavelengthMetres: Float,
        val cyclesPerWrap: Int,
        val phaseRadians: Float
    )
    {
        /** Spatial angular frequency, radians per metre. */
        val waveNumber get() = (2.0 * PI / wavelengthMetres).toFloat()

        /** Temporal angular frequency, radians per second. */
        val angularSpeed get() = (2.0 * PI * cyclesPerWrap / WRAP_SECONDS).toFloat()
    }

    /**
     * A RIPPLE, NOT A SWELL — and that is the owner's reference talking, not a taste call.
     *
     * The first version of this table ran 0.70 m at 23 m of wavelength, i.e. a proper travelling
     * swell, and it read as distant hills. The reference the owner then sent (Ori and the Blind
     * Forest, the half-submerged shot) says something quite specific and quite different: the
     * surface there is a nearly FLAT line with a slight ripple on it, and what actually says
     * "this is water" is the bright meniscus sitting on that line and the tint below it — not the
     * geometry. Big waves are the obvious way to draw a sea and they are the wrong one; they make
     * the boundary the subject, when the boundary's job is to be a boundary.
     *
     * So: 0.31 m total over wavelengths of 1.7 to 7.3 m. That is 9 pixels of excursion on the dev
     * window and 6 on a 1080p booth panel — unmistakably alive, and nowhere near enough to read as
     * a shape. `water.frag` spends what it saved on the meniscus.
     *
     * Amplitudes, wavelengths and speeds are DESIGN DECISIONS tuned against captures. What is not
     * free is the amplitude SUM, which [QUAD_TOP_DEPTH]/[QUAD_BOTTOM_DEPTH] are derived from, and
     * the integer cycle counts, which [WRAP_SECONDS] depends on.
     */
    val components = listOf(
        Component(amplitudeMetres = 0.16f, wavelengthMetres = 7.3f, cyclesPerWrap = 29, phaseRadians = 0.0f),
        Component(amplitudeMetres = 0.10f, wavelengthMetres = 3.1f, cyclesPerWrap = -43, phaseRadians = 1.9f),
        Component(amplitudeMetres = 0.05f, wavelengthMetres = 1.7f, cyclesPerWrap = 49, phaseRadians = 4.1f)
    )

    /**
     * The furthest the surface can get from [Tuning.SURFACE_DEPTH] in either direction: the sum of
     * the amplitudes, which is reached only where all three components happen to align.
     *
     * A BOUND, not a typical excursion — the components are incommensurate and only rarely line
     * up, so the sea normally moves well inside it (a full sweep of x and phase reaches 92% of it). It is a bound because the band below is what the renderer reserves,
     * and reserving the typical case would let the rare aligned crest poke out of the top of the
     * quad and get clipped into a flat horizontal line.
     */
    val AMPLITUDE_METRES = components.fold(0f) { sum, c -> sum + c.amplitudeMetres }

    /**
     * How far under the surface the sunlit haze is still visible. The diver spends the whole game
     * below the waterline, so what he mostly sees of the sea's surface is its UNDERSIDE, lit from
     * above — a layer that fades into the water rather than a line.
     *
     * NOT A SECOND DEPTH GRADIENT, and the distinction matters. `DiveRenderer`'s zone bands
     * already darken and shift hue continuously with depth through [DepthBlend], and that IS this
     * game's depth fog — the thing the reference's cyan tint is doing. Building a competing one
     * here would mean two curves to keep in agreement forever. This is a LOCAL term: it exists
     * only in the few metres under the boundary, it is forced to exactly zero where the bands take
     * over (see `water.frag`'s `fade`), and everything deeper than it is the bands' job alone.
     */
    const val UNDERSIDE_METRES = 7f

    /**
     * Slack above the highest crest, so the anti-aliased top of the quad is never the quad's own
     * edge. The shader's alpha ramp is about a pixel wide; this is two orders of magnitude more
     * than that at any framebuffer we can reach, and it costs one strip of transparent fragments.
     */
    const val EDGE_MARGIN_METRES = 0.3f

    /**
     * WHAT THIS DELIBERATELY DOES NOT DO, so the next agent does not go looking for it.
     *
     * The reference also shows a small HORIZONTAL DISPLACEMENT of everything just under the
     * waterline, decaying within a metre or two — the refraction cue. That cannot be done from
     * here: this is a `BatchRenderer`, it draws INTO the world surface and cannot read it, and
     * displacing what it draws itself would displace nothing, because the water it draws is
     * horizontally uniform. It needs the other extension point — a `PostProcessingEffect` that
     * samples `main` after GI has multiplied it — which is a separate piece of work and is also
     * where CAUSTICS would naturally live.
     *
     * WHAT THAT WORK CAN REUSE FROM HERE, WHOLE: [waveUniform] and [phaseSeconds] are the surface's
     * height AND its analytic slope at any world x, single-sourced, bounded, wrap-exact and
     * pinnable. A caustic pattern is a function of that slope, and a refraction offset is a
     * function of the same slope times a decay in depth. Neither needs a second wave table, and
     * neither should have one.
     */

    /** The top of [WaterRenderer]'s quad: above every crest the wave table can produce. */
    val QUAD_TOP_DEPTH = Tuning.SURFACE_DEPTH - AMPLITUDE_METRES - EDGE_MARGIN_METRES

    /**
     * The bottom of [WaterRenderer]'s quad, and therefore the depth at which
     * [DiveRenderer.drawZoneBands] takes over. Below every trough by the whole of the underside
     * fade, so the hand-off happens where the shader has already faded to exactly the band colour.
     */
    val QUAD_BOTTOM_DEPTH = Tuning.SURFACE_DEPTH + AMPLITUDE_METRES + UNDERSIDE_METRES

    // --- THE CLOCK ------------------------------------------------------------------------------
    //
    // ## Why this runs on the RENDER clock, which nothing else in this project does
    //
    // The only clock this codebase had sanctioned is accumulated time on the fixed tick inside
    // `RunLifecycle.simulationAdvances`, and that gate is FALSE in IDLE. A wave animated on it
    // would be frozen solid on the attract screen — which is the screen a queue at a conference
    // booth spends most of its time looking at, and the screen the sea and the sunset exist for.
    // A still sea on the attract loop is worse than no sea: it reads as a painted backdrop, and
    // the whole point of the water is that the world is alive before you have touched anything.
    //
    // The other agent working on the god rays hit the same fork and chose no drift at all rather
    // than animate on the wrong clock. That is the right call for a LIGHT, which is part of the
    // lit scene and whose motion would change the light budget every capture measures. It is the
    // wrong call here: the sea and the sky have no gameplay meaning whatsoever, they are not on
    // the light map (see the class doc — the sky is on its own surface and the water quad's alpha
    // is pure geometry), and nothing about the simulation can observe them.
    //
    // So: `engine.data.deltaTime`, advanced UNCONDITIONALLY from `EnPustTil.onUpdate` — outside
    // the lifecycle gate, deliberately, because a paused game and an attract screen must both
    // still have a moving sea.
    //
    // ## And therefore the phase must be pinnable, because the capture harness depends on it
    //
    // A render-clock animation makes two captures of the same build differ, which is exactly what
    // `HARNESS.md` says destroys a before/after measurement (a same-build control pair that
    // differs as much as the change under test has measured nothing). `EPT_WAVE_PHASE=<seconds>`
    // pins the phase and stops the clock, so a pinned capture reproduces bit for bit. It is read
    // once at startup in `EnPustTil.onCreate`, next to `EPT_SCREENSHOT` and `EPT_DEV`, and at the
    // booth it is unset and costs one getenv and one null check per process.
    //
    // THIS IS THE PRECEDENT FOR AMBIENT MOTION. The floating bubbles (#19) and anything else that
    // is pure presentation should do the same: render clock, unconditional, bounded by an exact
    // wrap, and pinnable. Anything the simulation can observe must stay on the fixed tick.

    private var pinnedSeconds: Float? = null
    private var seconds = 0f

    /** The wave's phase, in seconds. Always in `[0, WRAP_SECONDS)` — see [WRAP_SECONDS]. */
    val phaseSeconds get() = pinnedSeconds ?: seconds

    /**
     * Advance the wave by one rendered frame.
     *
     * Deliberately does NOT check the pin. An earlier version opened with
     * `if (pinnedSeconds != null) return`, which reads like the obvious guard and is dead code:
     * [phaseSeconds] returns the pin when there is one, so a free-running `seconds` underneath it
     * cannot be observed. Mutation-testing it found exactly that — removing the line changed no
     * test and no behaviour — so it is gone rather than left as an untestable line implying the
     * pin lives in two places. The pin lives in [phaseSeconds] and nowhere else.
     *
     * The remaining guard is real. The engine hands `deltaTime` straight from the frame timer, and
     * a stalled frame (a window drag, a display-mode change) has produced absurd values elsewhere
     * in this project; a NaN here makes every fragment's `sin` NaN, and a NaN alpha is a hole in
     * the frame rather than a wobble.
     */
    fun advance(dt: Float)
    {
        if (!(dt > 0f) || !dt.isFinite()) return
        seconds = (seconds + dt) % WRAP_SECONDS
    }

    /** Pin the phase for a reproducible capture — see the clock note above. */
    fun pin(atSeconds: Float)
    {
        pinnedSeconds = wrapped(atSeconds)
    }

    /** Drop a pin. Exists for the tests; nothing in the game unpins. */
    internal fun unpin()
    {
        pinnedSeconds = null
        seconds = 0f
    }

    /** `atSeconds` folded into `[0, WRAP_SECONDS)`, for a negative or enormous pin. */
    internal fun wrapped(atSeconds: Float): Float
    {
        if (!atSeconds.isFinite()) return 0f
        val m = atSeconds % WRAP_SECONDS
        return if (m < 0f) m + WRAP_SECONDS else m
    }

    // --- The wave, as the shader receives it ----------------------------------------------------

    /** Floats per component in the packed uniform: amplitude, wave number, angular speed, phase. */
    const val FLOATS_PER_COMPONENT = 4

    /**
     * The wave table flattened for upload, `FLOATS_PER_COMPONENT` per component.
     *
     * THE TABLE ABOVE IS THE ONLY COPY. `water.frag` sums
     * `amplitude * sin(waveNumber * x + angularSpeed * t + phase)` over exactly these values, so
     * there is no second set of numbers in the GLSL that could drift from these — which is the
     * failure mode a shader-side wave table would have, and it would be invisible until somebody
     * changed one of them. What cannot be proved from here is that the GLSL sums them the way this
     * says it does; `WaterShaderTest` reads the shader's text for the expression, which is the
     * same thing `IridescenceShaderTest` does with its attribute types and for the same reason.
     *
     * Built once at class-init rather than per frame: nothing in it changes, and the render path
     * allocates nothing.
     */
    val waveUniform: FloatArray = FloatArray(components.size * FLOATS_PER_COMPONENT).also { out ->
        components.forEachIndexed { i, c ->
            out[i * FLOATS_PER_COMPONENT] = c.amplitudeMetres
            out[i * FLOATS_PER_COMPONENT + 1] = c.waveNumber
            out[i * FLOATS_PER_COMPONENT + 2] = c.angularSpeed
            out[i * FLOATS_PER_COMPONENT + 3] = c.phaseRadians
        }
    }
}
