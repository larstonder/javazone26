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
    ABYSS   (120f, 400, 16f, 2.2f);

    companion object
    {
        /**
         * The zone table as an array, held once.
         *
         * `entries` is a `kotlin.enums.EnumEntriesList extends AbstractList`, and ANY iterator
         * over it — which `lastOrNull { }`, `firstOrNull { }`, `for (z in entries)` and
         * `reversed()` all take — allocates an `AbstractList$ListIteratorImpl` per call.
         * [at] is called five times per gradient strip across ~111 strips in
         * `DiveRenderer.drawZoneBands`, i.e. 555 allocations per frame at 120 fps, which is
         * the single largest source of garbage in the render path and the only one with a
         * mechanism (young-gen GC) for producing a visible hitch.
         */
        private val ZONES = entries.toTypedArray()

        /**
         * The deepest zone whose [minDepth] this depth has reached, [SHALLOWS] above them all.
         *
         * A descending index loop, NOT `entries.lastOrNull { }` — see [ZONES]. Behaviour is
         * identical; only the garbage is gone.
         */
        fun at(depth: Float): Zone
        {
            for (i in ZONES.indices.reversed())
                if (depth >= ZONES[i].minDepth) return ZONES[i]
            return SHALLOWS
        }
    }
}
