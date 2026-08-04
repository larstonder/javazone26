package dive

/**
 * Real buoyancy. An empty diver is neutrally buoyant — with no pearls and no input,
 * vertical velocity settles at zero, and the diver hovers rather than sinking. Carried
 * mass is what breaks that neutrality: it pulls the diver down ([sinkForce]) and it makes
 * every stroke, in every direction, more sluggish ([dragCoefficient]). Past a certain load
 * the sink force alone exceeds what a swim stroke can push against, and climbing becomes
 * impossible — the only way home is to bleed ballast.
 */
object Buoyancy
{
    /** Downward pull from carried mass. ZERO when empty — an unladen diver hovers. */
    fun sinkForce(mass: Float) = Tuning.SINK_FORCE_PER_MASS * mass

    /** Water resistance. Rises with load, so a laden diver is sluggish in every direction. */
    fun dragCoefficient(mass: Float) = 1f + mass / Tuning.K_DRAG

    /** Terminal vertical speed. verticalInput is -1 up, 0 none, +1 down. Positive result = sinking. */
    fun verticalSpeed(mass: Float, verticalInput: Float, boost: Float) =
        (verticalInput * Tuning.SWIM_THRUST * boost + sinkForce(mass)) / dragCoefficient(mass)

    /** Terminal lateral speed — also dragged down by load. */
    fun lateralSpeed(mass: Float, horizontalInput: Float, boost: Float) =
        horizontalInput * Tuning.LATERAL_THRUST * boost / dragCoefficient(mass)

    /**
     * How quickly the diver's velocity converges on its target, per second.
     * Water is thick: you accelerate into a stroke and glide out of it. Carrying more
     * mass lowers this, so a loaded diver is slow to start moving and slow to stop.
     */
    fun responseRate(mass: Float) = Tuning.RESPONSE_RATE / (1f + mass / Tuning.K_RESPONSE_MASS)
}
