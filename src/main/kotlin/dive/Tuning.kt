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
    /**
     * Was 2.5. Raised to 4 because the pickup circle had stopped agreeing with the drawn diver.
     *
     * ## The disagreement
     *
     * Pickup is a CIRCLE about the diver's centre ([DiveSim.collectPearls]); the sprite is a
     * 2.95 x 9 m RECTANGLE about that same point. Measured off the committed diffuse sheet
     * (alpha > 16, all 41 frames, one texel = 9/384 = 0.0234 m) the silhouette reaches:
     *
     *     crown / snorkel tip     4.45 m above centre   (row 2, identical in all 41 frames)
     *     top of the head         4.31 m                (row 8)
     *     chin                    3.56 m                (row 40; `DiveLighting` measured 3.60)
     *     shoulders begin         3.00 m                (row 64, where the width steps 38 -> 73)
     *     fin tips                4.29 .. 4.45 m below centre
     *     widest half-extent      1.43 m                (61 texels of a 63-texel half-cell)
     *
     * At 2.5 m that left a 1.95 m DEAD BAND at his head and at his fins — and it sits along the
     * swim axis, which is exactly where the player aims. A pearl visibly touching his mask did
     * not collect, which reads as a bug rather than as a rule.
     *
     * ## Why 4 and not 3.5
     *
     * 3.5 was the first suggestion and the measurement disproved it: his CHIN is at 3.56 m, so
     * 3.5 puts the boundary on his neck and leaves the reported case untouched — the pearl still
     * lands on his face and still does not collect. 4 puts the boundary on the face, between
     * chin and crown. What is left is 0.45 m of crown, snorkel and fin tip, the last 5% of the
     * figure.
     *
     * ## Why not 4.5
     *
     * 4.5 would clear the silhouette outright (4.45), and no circle does better than that: a
     * pearl is drawn 1.2 m across, so its box still visually touches the body out to 5.05 m and
     * a radius that chased THAT would be absurd. The 0.5 m it buys over 4 is paid for twice —
     * lateral overreach goes from 2.57 m to 3.07 m past the 1.43 m half-width, and the chance
     * that collecting one pearl also sweeps up a neighbour you did not plan the mass for goes
     * from 22% to 27% (uniform density, 14 pearls per zone, Monte-Carlo). In the seven-seed
     * abyss sweep below it bought exactly one more reachable pearl. Not worth it.
     *
     * ## What it cost the abyss — MEASURED with Autopilot, not argued
     *
     * `Autopilot.Waypoint.reach` defaults to this, so every flown route gets slacker and the
     * survivability numbers move. They move a little:
     *
     *     radius   abyss pearls home (7 seeds, 98 pearls)   max survivable load @130 / @135 / @140
     *     2.5      30                                       24 / 18 /  9
     *     3.5      31                                       25 / 19 / 11
     *     4.0      33                                       26 / 20 / 12
     *     4.5      34                                       27 / 21 / 13
     *
     * The SHAPE of the balance is unchanged, which is the property that matters: an abyss pearl
     * is 16 mass, so 135 m stays carryable (20 >= 16) and 140 m stays a trap (12 < 16) exactly
     * as before. The worst seed still lands 2 pearls, so `AscentTest`'s per-seed floor keeps its
     * old margin rather than being rescued by the change. `the abyss still refuses its deepest
     * water` still refuses it.
     */
    const val PEARL_PICKUP_RADIUS = 4f

    /**
     * Deliberately NOT raised alongside [PEARL_PICKUP_RADIUS], and deliberately left equal to it.
     *
     * It used to be the more forgiving of the two, and the old note here said so. That is no
     * longer true of the RADIUS and the note would now be a lie: at 4 m a vent has the same
     * 0.45 m mismatch against the 4.45 m silhouette that a pearl does, which is the point —
     * after this change the two objects are wrong by the same small amount instead of the pearl
     * being the outlier. A vent stays the easier target because it is DRAWN at
     * `Framing.AIR_POCKET_SIZE_METRES` = 2.4 m against a pearl's 1.2 m, so you can see and line
     * up on it from further away.
     *
     * Growing it is measured to be free and is still wrong. Flying the same sweep at 4.5 and 5.0
     * moved nothing (33, 33, 32 pearls home; identical max loads at 130/135/140) because
     * `Autopilot.ventRoute` scales its own tolerance with this and the two cancel. But a vent is
     * CONSUMED on contact (`AirPocket.usedThisDive`), so extra reach does not only help you find
     * your lifeline — it makes it easier to burn one by brushing past on a full breath. That is
     * the one direction where being generous costs the player something, so it stays at 4.
     */
    const val AIR_POCKET_PICKUP_RADIUS = 4f

    // Anglerfish — the only enemy, lives only in the abyss. Its lure is drawn identically
    // to a pearl; the drift speed is deliberately slow so the tell (motion) is learnable
    // in two runs rather than obvious on sight or invisible entirely. It must never end
    // the run — it only eats a fraction of what you are currently holding.
    const val ANGLERFISH_DRIFT_SPEED = 1.2f    // m/s toward the diver — the tell
    const val ANGLERFISH_BITE_RADIUS = 3f
    const val ANGLERFISH_STEAL_FRACTION = 0.4f
    const val ANGLERFISH_COOLDOWN = 4f
}
