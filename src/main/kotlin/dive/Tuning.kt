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

    // Kick — ONE BOOST PER CLICK, not a hold. The two multipliers below are unchanged and are
    // still applied exactly as they always were; what changed is WHEN. A rising edge on
    // DiveInput.kick opens a burst window of KICK_BURST_SECONDS during which both apply, and
    // when it closes both revert to 1f even if the button is still down. See DiveSim.updateKick.
    const val KICK_SPEED_MULT = 3f
    const val KICK_AIR_MULT = 3f

    /**
     * How long one click's boost lasts. A stroke, not a jet.
     *
     * 0.35 s is a little over one time constant of the velocity ramp at zero mass
     * (`1 / Buoyancy.responseRate(0f)` = 0.286 s), so a single kick reaches ~71% of the boosted
     * target and then decays back toward the unboosted one — the diver visibly surges and glides
     * rather than snapping between two speeds. Shorter and the ramp eats the whole burst so the
     * boost is barely felt; much longer and holding the button again approximates the old
     * continuous kick, which is exactly what this change removes.
     *
     * It is deliberately shorter than [KICK_COOLDOWN_SECONDS], so two bursts can never overlap
     * and there is always a dead gap between them — see that constant.
     */
    const val KICK_BURST_SECONDS = 0.35f

    /**
     * Minimum time between the START of one burst and the start of the next — NOT time measured
     * from the end of a burst. Measuring from the start is what makes the duty cycle a fixed,
     * checkable fraction (KICK_BURST_SECONDS / KICK_COOLDOWN_SECONDS = 0.778) no matter how the
     * player mashes: the burst is 0.35 s of the 0.45 s window and the remaining 0.1 s is a dead
     * gap in which no boost is possible at all.
     *
     * That gap is the whole point. Kick used to be continuous while held, and the risk in making
     * it a burst is that a player simply mashes the button back to the old behaviour. With this
     * ceiling they cannot: mashing every single tick still cannot exceed 78% duty, so the kick
     * stays a decision with an air price rather than a flight mode. `KickBurstTest.mashing the kick
     * button every tick cannot approximate the old hold-to-fly behaviour` asserts it.
     */
    const val KICK_COOLDOWN_SECONDS = 0.45f

    /**
     * Hard ceilings on each velocity axis, in m/s. **These change nothing that is reachable
     * today** — they are a guarantee and a regression guard, not a behaviour change.
     *
     * The movement model already bounds speed implicitly: [DiveSim.updateMovement] eases velocity
     * TOWARD a `Buoyancy` target by a factor in (0, 1), so velocity always stays between its
     * previous value and that target and can never overshoot it. Both targets are largest at zero
     * mass with a full stick and the kick boost applied — carried mass only ever shrinks them,
     * because the drag divisor grows faster than the sink term
     * (`d/dm[(33 + 0.06m)/(1 + m/200)] < 0` at every mass) — so the numbers below ARE
     * `Buoyancy.verticalSpeed(0f, 1f, KICK_SPEED_MULT)` = 33 and
     * `Buoyancy.lateralSpeed(0f, 1f, KICK_SPEED_MULT)` = 18.
     *
     * They are written as products of this file's own constants rather than as literals so that
     * raising [SWIM_THRUST] or [KICK_SPEED_MULT] carries the ceiling with it; `dive/Buoyancy.kt`
     * owns the actual formula, and `KickBurstTest.the speed ceilings are the buoyancy model's own
     * maxima` re-derives both from it (and sweeps mass) so the two cannot silently part company.
     *
     * **The axes are clamped INDEPENDENTLY, never as a combined magnitude.** A magnitude clamp at
     * 33 would still be a change: swimming diagonally today gives vx = 18 and vy = 33 at once, a
     * magnitude of 37.6, and capping that would make diagonal swimming slower than it is now —
     * a gameplay change nobody asked for.
     */
    const val MAX_VERTICAL_SPEED = SWIM_THRUST * KICK_SPEED_MULT
    const val MAX_LATERAL_SPEED = LATERAL_THRUST * KICK_SPEED_MULT

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
     * `Framing.AIR_POCKET_SIZE_METRES` = 3.6 m against a pearl's 1.2 m, so you can see and line
     * up on it from further away. (That was 2.4 m until the vent took its animated sheet and was
     * scaled 1.5x; the gap this paragraph is about therefore got WIDER, not narrower, and the
     * argument for leaving this radius alone is unchanged by it — see that constant's KDoc.)
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
