package dive

/**
 * Weight model. Carrying pearls makes the diver sink faster and rise slower.
 * Descent gain is deliberately weaker than ascent loss (see K_DESCENT > K_ASCENT),
 * so greed costs more on the way home than it saves on the way down.
 */
object Buoyancy
{
    fun ascentSpeed(mass: Float) = Tuning.BASE_ASCENT / (1f + mass / Tuning.K_ASCENT)

    fun descentSpeed(mass: Float) = Tuning.BASE_DESCENT * (1f + mass / Tuning.K_DESCENT)

    /**
     * How quickly the diver's velocity converges on its target, per second.
     * Water is thick: you accelerate into a stroke and glide out of it. Carrying more
     * mass lowers this, so a loaded diver is slow to start moving and slow to stop.
     */
    fun responseRate(mass: Float) = Tuning.RESPONSE_RATE / (1f + mass / Tuning.K_RESPONSE_MASS)
}
