package dive

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * All run state and the tick function. Contains no engine code by design —
 * everything here is unit-testable pure Kotlin.
 */
class DiveSim(seed: Long)
{
    val pearls: MutableList<Pearl> = PearlColumn.generate(seed)
    val airPockets: List<AirPocket> = AirPocketField.generate(seed)

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

    /** Current velocity. Water has inertia — these persist between ticks. */
    var vx = 0f;                       private set
    var vy = 0f;                       private set

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
        refillAtAirPockets()
        val justBlackedOut = updateAir(dt, input)
        if (justBlackedOut) return   // blacking out ends the tick — no pickup, no surface bank

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

        if (heldMass <= Tuning.BLEED_EPSILON) { heldMass = 0f; held = 0 }
    }

    private fun updateMovement(dt: Float, input: DiveInput)
    {
        val boost = if (input.kick) Tuning.KICK_SPEED_MULT else 1f

        // Target velocity — what the diver would eventually reach and hold.
        // Neutral stick still sinks: the diver is never truly still.
        // Swimming up is capped by ascentSpeed, which is what makes weight bite.
        val targetVx = input.horizontal * Tuning.SWIM_SPEED * boost
        val targetVy = when
        {
            input.vertical < 0f -> input.vertical * Buoyancy.ascentSpeed(heldMass) * boost
            input.vertical > 0f -> input.vertical * Buoyancy.descentSpeed(heldMass) * boost
            else                -> Buoyancy.descentSpeed(heldMass)
        }

        // Drag: velocity eases toward the target rather than snapping to it, so the diver
        // accelerates into a stroke and glides out of it. Using 1 - e^(-k*dt) keeps this
        // frame-rate independent. Response falls as the diver loads up, so a full haul is
        // sluggish to start and sluggish to stop — the feel of swimming with weight.
        val response = 1f - exp(-Buoyancy.responseRate(heldMass) * dt)
        vx += (targetVx - vx) * response
        vy += (targetVy - vy) * response

        // Integrate, and kill velocity into a wall so the diver does not stick to it.
        val nextX = x + vx * dt
        x = nextX.coerceIn(-Tuning.COLUMN_HALF_WIDTH, Tuning.COLUMN_HALF_WIDTH)
        if (x != nextX) vx = 0f

        val nextDepth = depth + vy * dt
        depth = nextDepth.coerceIn(0f, Tuning.MAX_DEPTH)
        if (depth != nextDepth) vy = 0f

        maxDepthThisDive = max(maxDepthThisDive, depth)
    }

    /** @return true if this call caused a blackout — the caller must end the tick when it does. */
    private fun updateAir(dt: Float, input: DiveInput): Boolean
    {
        val burn = zone.airBurn * (if (input.kick) Tuning.KICK_AIR_MULT else 1f)
        air -= dt * burn
        if (air <= 0f) { blackout(); return true }
        return false
    }

    /** Reaching a vent refills the breath. Once per dive; see [AirPocket]. */
    private fun refillAtAirPockets()
    {
        airPockets.forEach { pocket ->
            if (pocket.usedThisDive) return@forEach
            val dx = pocket.x - x
            val dy = pocket.depth - depth
            val r = Tuning.AIR_POCKET_PICKUP_RADIUS
            if (dx * dx + dy * dy <= r * r)
            {
                pocket.usedThisDive = true
                air = Tuning.BASE_AIR_SECONDS
            }
        }
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
        vx = 0f
        vy = 0f
        airPockets.forEach { it.usedThisDive = false }
    }

    // --- Test hooks ---------------------------------------------------------

    internal fun debugSetDepth(value: Float) { depth = value; maxDepthThisDive = max(maxDepthThisDive, value) }
    internal fun debugSetHeld(count: Int, mass: Float) { held = count; heldMass = mass }
    internal fun debugMoveTo(newX: Float, newDepth: Float) { x = newX; depth = newDepth }
    internal fun debugSurface() { surface() }
    internal fun debugSetClock(value: Float) { clock = value }
    internal fun debugSetAir(value: Float) { air = value }
}
