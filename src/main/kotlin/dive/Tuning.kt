package dive

/** Every tunable value in the game. No logic lives here. */
object Tuning
{
    // Run structure
    const val RUN_SECONDS = 90f

    // Was 20f. Raised after the first play session: 20s was not enough air.
    // A trip down to the trench and a laden swim back costs most of a breath, so the
    // deep zones were effectively unreachable-and-returnable and every run ended in a
    // blackout at the old value.
    const val BASE_AIR_SECONDS = 30f

    // Buoyancy — real buoyancy: an empty diver is neutral and hovers. Carried mass is what
    // makes you sink (SINK_FORCE_PER_MASS) and what makes you sluggish in every direction
    // (K_DRAG). See Buoyancy.kt.
    const val SWIM_THRUST = 11f
    const val LATERAL_THRUST = 6f
    const val SINK_FORCE_PER_MASS = 0.06f
    const val K_DRAG = 200f

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
