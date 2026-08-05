package render

import kotlin.math.atan2
import kotlin.math.exp

/**
 * Pure heading/smoothing maths for the diver's flashlight beam. No engine imports, so it is
 * unit-testable without a GL context.
 *
 * [headingDegrees] is plain, standard `atan2` — 0 along +x, 90 along +y, increasing
 * counter-clockwise in whatever coordinate system its two arguments are already in. It makes
 * no claim about screen space or engine convention; the caller ([DiveLighting]) is
 * responsible for handing it components in whatever axis convention `GiSceneRenderer.drawLight`
 * actually expects for its `angle`/cone-direction parameter.
 *
 * That convention is Y-flipped relative to [Viewport]'s screen-space-Y-down convention. This
 * was originally recorded as an empirical screenshot finding (a straight vertical descent
 * produced a beam pointing straight UP, opposite of travel) with the caveat that it might
 * have been read off an already-broken beam. It is no longer a guess — it now falls out of
 * the engine's own shader source, which is the authority:
 *
 *   - `scene.frag` stores the heading as `metadata.g = fract(sourceAngle / 360.0)`, so
 *     negative angles wrap cleanly and no sign information is lost on the way through.
 *   - `radiance_cascades.frag` decodes it into a direction vector as
 *     `coneDir = vec2(cos(sourceDir + camAngle), sin(sourceDir + camAngle))` — a plain
 *     +sin, i.e. counter-clockwise from +x, in the light texture's own pixel space.
 *   - That pixel space is a GL framebuffer, so its +y runs UP the screen, whereas
 *     `Viewport.screenY` grows DOWNWARD. (The same shader builds its march directions as
 *     `rayDir = vec2(cos(rayAngle), -sin(rayAngle))`, negating sin precisely because ray
 *     angles are quoted in the opposite handedness — the two conventions coexist in one
 *     file, which is exactly how this is easy to get wrong.)
 *
 * So an `angle` of -90 points DOWN the screen, and `DiveLighting.drawDiverBeam` negates
 * `sim.vy` to convert a Y-down world velocity into that Y-up heading. Verified twice over:
 * derived from the shader above, and confirmed in play — the moving beam points where the
 * diver is actually swimming. See the call site in `DiveLighting.drawDiverBeam`.
 */
object AimAngle
{
    /** Standard atan2 heading, in degrees: 0 along +x, 90 along +y. No axis convention implied. */
    fun headingDegrees(vx: Float, vy: Float): Float =
        Math.toDegrees(atan2(vy, vx).toDouble()).toFloat()

    /** Wraps an angle in degrees to the [0, 360) range. */
    fun wrap(degrees: Float): Float
    {
        var a = degrees % 360f
        if (a < 0f) a += 360f
        return a
    }

    /**
     * Shortest signed difference `target - current`, in the range `(-180, 180]`, so a
     * heading that flips from 179 to -179 (crossing the wrap) is read as a 2-degree turn,
     * not a 358-degree one.
     */
    fun shortestDifference(current: Float, target: Float): Float
    {
        var diff = (target - current) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    /**
     * Eases [current] toward [target] using the same `1 - e^(-k*dt)` frame-rate-independent
     * smoothing as [DiveCamera] and `DiveSim.updateMovement`, taking the shortest way around
     * the wrap. Result is always in `[0, 360)`.
     */
    fun smooth(current: Float, target: Float, dt: Float, rate: Float): Float
    {
        val diff = shortestDifference(current, target)
        val t = 1f - exp(-rate * dt.coerceAtLeast(0f))
        return wrap(current + diff * t)
    }
}
