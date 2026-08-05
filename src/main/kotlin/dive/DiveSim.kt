package dive

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** How a dive closed out — see [DiveSim.diveEnded]. */
enum class DiveOutcome { SURFACED, BLACKED_OUT }

/**
 * Fired for exactly the tick a dive ends. A HUD (or anything else) that wants to react
 * to a cash-out — sag the music, flash the screen, fly digits into the banked total —
 * reads this on the tick it is non-null. It is cleared at the start of the next tick,
 * so nothing downstream can read stale state from a dive that ended long ago.
 *
 * [banked] is the amount THIS dive contributed, not the running total ([DiveSim.banked]
 * is that). [depth] is the deepest point this dive reached (`maxDepthThisDive` at the
 * moment it closed), which is also what the depth-bonus multiplier was computed from.
 */
data class DiveEnded(val outcome: DiveOutcome, val banked: Int, val depth: Float)

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

    /**
     * Non-null for exactly the tick a dive ended; cleared at the start of every [tick].
     * See [DiveEnded] for why this replaced the old `lastBankAmount` (never cleared —
     * stale forever) and `blackedOut` (latched true forever after the first blackout,
     * never reset even by [resetDive]) fields.
     */
    var diveEnded: DiveEnded? = null;  private set

    /**
     * Convenience view onto [diveEnded]: true only for the tick a blackout just closed
     * out a dive, false again the tick after. Unlike the field this replaced, it cannot
     * latch — it is derived fresh from [diveEnded] every read.
     */
    val blackedOut: Boolean get() = diveEnded?.outcome == DiveOutcome.BLACKED_OUT

    /** Non-null only while the diver is in the Abyss. See [updateAnglerfish]. */
    var anglerfish: Anglerfish? = null; private set

    /** Current velocity. Water has inertia — these persist between ticks. */
    var vx = 0f;                       private set
    var vy = 0f;                       private set

    val zone get() = Zone.at(depth)

    fun tick(dt: Float, input: DiveInput)
    {
        // diveEnded is an event, valid for exactly one tick — clear it before this tick
        // has a chance to decide whether a new one fires. Cleared even when runOver is
        // about to short-circuit the rest of this tick, since a dive cannot end twice.
        diveEnded = null

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

        // Surfacing is checked before the air burn: a diver who touches the surface on the
        // same tick their air would hit zero has visibly made it home and must bank, not
        // black out. This mirrors why air-pocket refills are already checked before the burn.
        //
        // An empty diver now hovers, so resting exactly at depth 0 with no vertical intent
        // is a stable state, not a one-tick fly-through like it was under constant sink.
        // Only run the bank-and-reset pipeline when there is an actual dive to close out
        // (something held, or the diver went below the surface this dive) — otherwise a
        // diver idling at the surface would re-surface every single tick, and surface()'s
        // reset of vx/vy would cancel any horizontal swimming before it could build up speed.
        if (depth <= Tuning.SURFACE_DEPTH)
        {
            collectPearls()
            if (held > 0 || maxDepthThisDive > 0f) surface()
            return
        }

        val justBlackedOut = updateAir(dt, input)
        if (justBlackedOut) return   // blacking out ends the tick — no pickup, no surface bank, no bite

        collectPearls()

        // The anglerfish is updated last, deliberately after every early-return above:
        //   - after updateMovement, so it chases the diver's position AS OF THIS TICK,
        //     not the position left over from last tick.
        //   - after the blackout check's early return, so a diver who blacks out this
        //     tick cannot also be bitten this same tick (bite-then-blackout would let a
        //     10% blackout bank be computed on an already-fish-reduced `held`, which is
        //     the same class of nonsense interaction the vent/surface ordering above
        //     exists to prevent).
        updateAnglerfish(dt)
    }

    /**
     * The anglerfish exists ONLY in the Abyss — it is what stops players camping the
     * highest-value water. It is created the first tick the diver enters the Abyss and
     * destroyed the moment the diver leaves it (torn down here rather than left to drift
     * off pointlessly).
     */
    private fun updateAnglerfish(dt: Float)
    {
        if (zone != Zone.ABYSS)
        {
            anglerfish = null
            return
        }

        val fish = anglerfish ?: Anglerfish(x = -Tuning.COLUMN_HALF_WIDTH, depth = Tuning.MAX_DEPTH)
            .also { anglerfish = it }

        fish.update(dt, x, depth)

        if (fish.canBite(x, depth)) bite(fish)
    }

    /** A bite scatters a fraction of what is currently held. It must NEVER end the run. */
    private fun bite(fish: Anglerfish)
    {
        val stolenValue = (held * Tuning.ANGLERFISH_STEAL_FRACTION).toInt()
        val stolenMass = heldMass * Tuning.ANGLERFISH_STEAL_FRACTION
        held = max(0, held - stolenValue)
        heldMass = max(0f, heldMass - stolenMass)
        fish.onBite()
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

        // Target velocity — what the diver would eventually reach and hold. An empty diver
        // is neutrally buoyant: neutral stick with no mass targets zero, so it hovers rather
        // than sinking. Carried mass adds a sink force the stroke has to fight, and adds
        // drag that saps every direction, so a laden diver is sluggish and, past enough
        // mass, cannot out-swim its own sink force at all.
        val targetVx = Buoyancy.lateralSpeed(heldMass, input.horizontal, boost)
        val targetVy = Buoyancy.verticalSpeed(heldMass, input.vertical, boost)

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
        val amount = Scoring.blackoutBank(held)
        banked += amount
        diveEnded = DiveEnded(DiveOutcome.BLACKED_OUT, amount, maxDepthThisDive)
        resetDive()
    }

    private fun surface()
    {
        if (held > 0 || maxDepthThisDive > 0f)
        {
            val amount = Scoring.bank(held, maxDepthThisDive)
            banked += amount
            diveEnded = DiveEnded(DiveOutcome.SURFACED, amount, maxDepthThisDive)
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

    /**
     * How deep the diver could still be and expect to reach the surface on the air
     * remaining, given the mass currently carried. Drives the faint point-of-no-return
     * marker on the depth tape — a mercy for first-timers that teaches the economy
     * without a word of text.
     *
     * Uses the CURRENT zone's air burn as the climb rate for the whole ascent. That is
     * an approximation — a real climb crosses zones with different burn rates — but it
     * is the honest one to make from where the diver is standing right now, and it is
     * cheap enough to recompute every frame.
     *
     * Past roughly mass 183 (the point where [Buoyancy.sinkForce] exceeds
     * [Tuning.SWIM_THRUST]), the diver cannot climb against its own sink force at all —
     * no amount of remaining air helps. maxSafeDepth is 0 in that case, not a
     * divide-by-zero or a negative number.
     */
    fun maxSafeDepth(): Float
    {
        // verticalSpeed is positive when sinking; swimming up (-1) against the sink
        // force gives a NEGATIVE result while the diver can still climb at all. Negate
        // it to get a climb speed that is positive exactly when climbing is possible.
        val climbSpeed = -Buoyancy.verticalSpeed(heldMass, verticalInput = -1f, boost = 1f)
        if (climbSpeed <= 0f) return 0f

        val reachable = climbSpeed * (air / zone.airBurn)
        return reachable.coerceIn(0f, Tuning.MAX_DEPTH)
    }

    /** Whether the diver, right now, could still make it back to the surface. */
    fun canStillReturn(): Boolean = depth <= maxSafeDepth()

    // --- Test hooks ---------------------------------------------------------

    internal fun debugSetDepth(value: Float) { depth = value; maxDepthThisDive = max(maxDepthThisDive, value) }
    internal fun debugSetHeld(count: Int, mass: Float) { held = count; heldMass = mass }
    internal fun debugMoveTo(newX: Float, newDepth: Float) { x = newX; depth = newDepth }
    internal fun debugSurface() { surface() }
    internal fun debugSetClock(value: Float) { clock = value }
    internal fun debugSetAir(value: Float) { air = value }
    internal fun debugForceBite() { bite(anglerfish ?: Anglerfish(x, depth).also { anglerfish = it }) }
    internal fun debugSpawnAnglerfish(fishX: Float, fishDepth: Float) { anglerfish = Anglerfish(fishX, fishDepth) }
}
