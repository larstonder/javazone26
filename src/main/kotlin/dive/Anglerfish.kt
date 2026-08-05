package dive

import kotlin.math.hypot
import kotlin.math.min

/**
 * The only enemy in the game, and it lives only in the Abyss — where the only
 * real money is. Its lure is drawn identically to a pearl (see [render.DiveRenderer]).
 *
 * THE TELL: real pearls sit still, this drifts toward the diver. It is hunting.
 * That is what makes it fair — learnable in two runs, unfair in none.
 *
 * It must never end the run. A bite only scatters a fraction of what is currently
 * held; see [DiveSim.bite].
 */
class Anglerfish(var x: Float, var depth: Float)
{
    var biteCooldown = 0f; private set

    /**
     * Constant-velocity drift toward the diver's current position, capped so a single
     * step can never overshoot the target — the frame-rate-independence rule allows a
     * naive `speed * dt` approach for constant-velocity drift as long as it cannot
     * overshoot, which the `min(step, dist)` clamp below guarantees.
     */
    fun update(dt: Float, diverX: Float, diverDepth: Float)
    {
        if (biteCooldown > 0f) biteCooldown = maxOf(0f, biteCooldown - dt)

        val dx = diverX - x
        val dy = diverDepth - depth
        val dist = hypot(dx, dy)
        if (dist < 0.001f) return

        val step = min(Tuning.ANGLERFISH_DRIFT_SPEED * dt, dist)
        x += dx / dist * step
        depth += dy / dist * step
    }

    fun canBite(diverX: Float, diverDepth: Float): Boolean
    {
        if (biteCooldown > 0f) return false
        return hypot(diverX - x, diverDepth - depth) <= Tuning.ANGLERFISH_BITE_RADIUS
    }

    fun onBite() { biteCooldown = Tuning.ANGLERFISH_COOLDOWN }
}
