package dive

/** Every tunable value in the game. No logic lives here. */
object Tuning
{
    // Run structure
    const val RUN_SECONDS = 90f

    // Was 20f. Raised after the first play session: 20s was not enough air.
    // At BASE_DESCENT the trip down to the trench alone costs ~15s, leaving nothing
    // for gathering or the slower loaded ascent, so the deep zones were effectively
    // unreachable-and-returnable and every run ended in a blackout.
    const val BASE_AIR_SECONDS = 30f

    // Buoyancy
    const val BASE_ASCENT = 8f
    const val BASE_DESCENT = 6f
    const val K_ASCENT = 40f
    const val K_DESCENT = 120f

    // Kick
    const val KICK_SPEED_MULT = 3f
    const val KICK_AIR_MULT = 3f

    // Ballast
    const val BLEED_RATE = 8f

    // Floating-point cleanliness guard for the ballast bleed
    const val BLEED_EPSILON = 0.001f

    // Scoring
    const val BLACKOUT_KEEP = 0.10f
    const val DEPTH_BONUS_DIVISOR = 30f

    // Horizontal movement
    const val SWIM_SPEED = 5f

    // Hydrodynamics — how fast the diver reaches target speed. Velocity eases toward the
    // target instead of snapping to it, so the diver glides on after you let go and takes
    // a moment to get moving. Lower RESPONSE_RATE = more inertia = heavier water.
    const val RESPONSE_RATE = 3.5f

    // Mass at which responsiveness halves. A loaded diver is sluggish to start AND to stop,
    // which is what makes a full haul feel like one rather than just a slower number.
    const val K_RESPONSE_MASS = 60f

    // World
    const val COLUMN_HALF_WIDTH = 40f
    const val MAX_DEPTH = 160f
    const val SURFACE_DEPTH = 0f
    const val PEARL_PICKUP_RADIUS = 2.5f

    // Vents are a lifeline, so they are more forgiving to hit than a pearl.
    const val AIR_POCKET_PICKUP_RADIUS = 4f
}
