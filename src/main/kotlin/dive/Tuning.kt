package dive

/** Every tunable value in the game. No logic lives here. */
object Tuning
{
    // Run structure
    const val RUN_SECONDS = 90f
    const val BASE_AIR_SECONDS = 20f

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

    // World
    const val COLUMN_HALF_WIDTH = 40f
    const val MAX_DEPTH = 160f
    const val SURFACE_DEPTH = 0f
    const val PEARL_PICKUP_RADIUS = 2.5f
}
