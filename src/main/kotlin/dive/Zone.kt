package dive

/**
 * Depth bands. Each zone must eventually differ in HOW the diver moves,
 * not only in value and colour — a zone that changes only a number is a reskin.
 */
enum class Zone(
    val minDepth: Float,
    val pearlValue: Int,
    val pearlMass: Float,
    val airBurn: Float
) {
    SHALLOWS(0f,   10,  1f,  1.0f),
    KELP    (30f,  25,  2f,  1.2f),
    TWILIGHT(60f,  60,  4f,  1.6f),
    TRENCH  (90f,  150, 8f,  2.0f),
    ABYSS   (120f, 400, 16f, 2.5f);

    companion object
    {
        fun at(depth: Float): Zone = entries.lastOrNull { depth >= it.minDepth } ?: SHALLOWS
    }
}
