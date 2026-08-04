package dive

import kotlin.math.max
import kotlin.math.min

/**
 * All run state and the tick function. Contains no engine code by design —
 * everything here is unit-testable pure Kotlin.
 */
class DiveSim(seed: Long)
{
    val pearls: MutableList<Pearl> = PearlColumn.generate(seed)

    var clock = Tuning.RUN_SECONDS;    private set
    var banked = 0;                    private set
    var held = 0;                      private set
    var heldMass = 0f;                 private set
    var depth = 0f;                    private set
    var x = 0f;                        private set
    var air = Tuning.BASE_AIR_SECONDS; private set
    var maxDepthThisDive = 0f;         private set
    var runOver = false;               private set
    var lastBankAmount = 0;            private set
    var blackedOut = false;            private set

    val zone get() = Zone.at(depth)

    fun tick(dt: Float, input: DiveInput)
    {
        if (runOver) return

        clock -= dt
        if (clock <= 0f)
        {
            clock = 0f
            runOver = true
            held = 0          // anything still held at 0:00 is lost
            heldMass = 0f
            return
        }

        updateBleed(dt, input)
        updateMovement(dt, input)
        updateAir(dt, input)
        collectPearls()

        if (depth <= Tuning.SURFACE_DEPTH) surface()
    }

    private fun updateBleed(dt: Float, input: DiveInput)
    {
        if (!input.bleed || heldMass <= 0f) return

        val massBefore = heldMass
        val massDropped = min(Tuning.BLEED_RATE * dt, heldMass)
        val fraction = massDropped / massBefore

        heldMass = max(0f, heldMass - massDropped)
        held = max(0, held - (held * fraction).toInt())

        if (heldMass <= 0.001f) { heldMass = 0f; held = 0 }
    }

    private fun updateMovement(dt: Float, input: DiveInput)
    {
        val boost = if (input.kick) Tuning.KICK_SPEED_MULT else 1f

        x = (x + input.horizontal * Tuning.SWIM_SPEED * boost * dt)
            .coerceIn(-Tuning.COLUMN_HALF_WIDTH, Tuning.COLUMN_HALF_WIDTH)

        // Neutral stick sinks passively — the diver is never truly still.
        // Swimming up is limited by ascentSpeed, which is what makes weight bite.
        val vertical = when
        {
            input.vertical < 0f -> input.vertical * Buoyancy.ascentSpeed(heldMass) * boost
            input.vertical > 0f -> input.vertical * Buoyancy.descentSpeed(heldMass) * boost
            else                -> Buoyancy.descentSpeed(heldMass)
        }

        depth = (depth + vertical * dt).coerceIn(0f, Tuning.MAX_DEPTH)
        maxDepthThisDive = max(maxDepthThisDive, depth)
    }

    private fun updateAir(dt: Float, input: DiveInput)
    {
        val burn = zone.airBurn * (if (input.kick) Tuning.KICK_AIR_MULT else 1f)
        air -= dt * burn
        if (air <= 0f) blackout()
    }

    private fun collectPearls()
    {
        pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            val dx = pearl.x - x
            val dy = pearl.depth - depth
            if (dx * dx + dy * dy <= Tuning.PEARL_PICKUP_RADIUS * Tuning.PEARL_PICKUP_RADIUS)
            {
                pearl.collected = true
                held += pearl.value
                heldMass += pearl.mass
            }
        }
    }

    private fun blackout()
    {
        blackedOut = true
        lastBankAmount = Scoring.blackoutBank(held)
        banked += lastBankAmount
        resetDive()
    }

    private fun surface()
    {
        if (held > 0 || maxDepthThisDive > 0f)
        {
            lastBankAmount = Scoring.bank(held, maxDepthThisDive)
            banked += lastBankAmount
        }
        resetDive()
    }

    private fun resetDive()
    {
        held = 0
        heldMass = 0f
        air = Tuning.BASE_AIR_SECONDS
        maxDepthThisDive = 0f
        depth = 0f
    }

    // --- Test hooks ---------------------------------------------------------

    internal fun debugSetDepth(value: Float) { depth = value; maxDepthThisDive = max(maxDepthThisDive, value) }
    internal fun debugSetHeld(count: Int, mass: Float) { held = count; heldMass = mass }
    internal fun debugMoveTo(newX: Float, newDepth: Float) { x = newX; depth = newDepth }
    internal fun debugSurface() { surface() }
    internal fun debugSetClock(value: Float) { clock = value }
}
