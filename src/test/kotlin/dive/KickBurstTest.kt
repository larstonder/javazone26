package dive

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KICK IS ONE BOOST PER CLICK, NOT A HOLD — and the regression this file exists to prevent is the
 * old behaviour coming back by accident.
 *
 * Until this change `DiveSim` read `input.kick` raw in two places, so the button was a throttle:
 * hold it and you flew, at a flat 3x air cost for as long as you held it. It is now a timed burst
 * opened by a RISING EDGE, using the identical [Tuning.KICK_SPEED_MULT] / [Tuning.KICK_AIR_MULT]
 * multipliers for exactly [Tuning.KICK_BURST_SECONDS], with a [Tuning.KICK_COOLDOWN_SECONDS]
 * minimum between one burst's start and the next.
 *
 * There are two ways to lose that, and both are silent:
 *
 *  - **reverting to the level read**, which no existing test would catch: every "kick makes the
 *    diver descend faster" style assertion in `DiveSimTest` passes under BOTH semantics, because a
 *    burst is still faster than no kick at all. So the assertions here are about DURATION and
 *    about what happens on the ticks AFTER the burst, which is the only place the two differ.
 *  - **dropping the cooldown**, which is subtler still: with a burst but no cooldown, a player
 *    mashing the button every other tick re-opens the window continuously and has simply
 *    rediscovered hold-to-fly with a sorer thumb. `mashing the kick button every tick cannot
 *    approximate the old hold-to-fly behaviour` is the assertion that fails in that case, and it
 *    is the reason the cooldown is measured from a burst's START rather than from its end.
 *
 * Every fixture below collects all the pearls first. Pearls add mass, mass changes both the
 * `Buoyancy` target and `responseRate`, and a diver who sweeps up a cluster mid-measurement is
 * measuring pickup luck rather than kick timing.
 */
class KickBurstTest
{
    private val dt = 1f / 60f
    private val idle = DiveInput(horizontal = 0f, vertical = 0f, kick = false, bleed = false)
    private val kickHeld = idle.copy(kick = true)
    private val swimDown = idle.copy(vertical = 1f)

    /** Ticks per burst at the fixed 60 Hz step: 0.35 / (1/60) = 21. */
    private val burstTicks = (Tuning.KICK_BURST_SECONDS / dt).toInt()

    private fun isolatedSim(): DiveSim = DiveSim(seed = 1L).also { sim ->
        sim.pearls.forEach { it.collected = true }
        sim.airPockets.forEach { it.usedThisDive = true }
    }

    /**
     * Runs [ticks] ticks, feeding the input [press] decides for each tick index, and returns the
     * tick indices on which a boost was actually active.
     *
     * `sim.kicking` is sampled AFTER each tick on purpose: `updateKick` runs at the very top of
     * `tick`, before either consumer reads it, so the value visible afterwards is exactly the one
     * the speed and air multipliers used during that tick. Sampling before would be off by one.
     */
    private fun boostedTicks(sim: DiveSim, ticks: Int, base: DiveInput = idle, press: (Int) -> Boolean): List<Int>
    {
        val boosted = mutableListOf<Int>()
        repeat(ticks) { i ->
            sim.tick(dt, base.copy(kick = press(i)))
            if (sim.kicking) boosted += i
        }
        return boosted
    }

    // --- One click, one burst ------------------------------------------------

    /**
     * THE ACTUAL REGRESSION. The button is held down for three full seconds — under the old
     * semantics that is three seconds of boost — and the boost must stop after
     * [Tuning.KICK_BURST_SECONDS] anyway.
     *
     * Two separate things are asserted because two separate things could break: that the boost
     * lasts the right LENGTH, and that the boosted ticks are a contiguous run starting at the
     * first one (a burst, not a stutter, and not a burst that re-opens later while the button is
     * still down).
     */
    @Test
    fun `one click boosts for exactly the burst length and then stops, even with the button still held`()
    {
        val sim = isolatedSim()
        val boosted = boostedTicks(sim, ticks = 180, base = swimDown) { true }

        assertEquals(
            Tuning.KICK_BURST_SECONDS, boosted.size * dt, dt * 1.5f,
            "a held button boosted for ${boosted.size * dt}s; one click must boost for exactly " +
            "${Tuning.KICK_BURST_SECONDS}s and then stop, however long the button stays down"
        )
        assertEquals(0, boosted.first(), "the burst must open on the tick the button goes down, not later")
        assertEquals(
            boosted.indices.toList(), boosted.map { it - boosted.first() },
            "the boosted ticks must be one contiguous burst — $boosted has a gap in it, so the " +
            "window is re-opening rather than running down"
        )
        assertFalse(sim.kicking, "three seconds into a held button the boost must be long over")
    }

    /** The same property stated as a count of bursts rather than a length: holding is not repeating. */
    @Test
    fun `holding the kick button forever yields exactly one burst`()
    {
        val sim = isolatedSim()
        var previous = false
        var starts = 0

        repeat(600) {                       // ten seconds — twenty-two cooldowns' worth
            sim.tick(dt, swimDown.copy(kick = true))
            if (sim.kicking && !previous) starts++
            previous = sim.kicking
        }

        assertEquals(1, starts, "a button held for ten seconds must produce ONE burst, not $starts")
    }

    // --- The cooldown --------------------------------------------------------

    /**
     * Pressing again inside the cooldown is refused outright — it is not queued, and it does not
     * fire late once the window opens. The second press here lands at 0.40 s, after the first
     * burst has already ended (0.35 s) but before the 0.45 s cooldown has, which is exactly the
     * 0.1 s dead gap the two constants are chosen to leave.
     */
    @Test
    fun `releasing and re-pressing during the cooldown produces no second burst`()
    {
        val sim = isolatedSim()
        val secondPress = 24                        // 0.400 s — inside the 0.45 s cooldown

        val boosted = boostedTicks(sim, ticks = 120, base = swimDown) { i -> i == 0 || i == secondPress }

        assertTrue(boosted.isNotEmpty(), "sanity: the first click must have boosted something")
        assertTrue(
            boosted.all { it < burstTicks + 2 },
            "a press at ${secondPress * dt}s is inside the ${Tuning.KICK_COOLDOWN_SECONDS}s cooldown " +
            "and must be refused outright — boosted ticks were $boosted"
        )
    }

    /**
     * ...and the complement, so the test above cannot pass by kick simply being broken. The same
     * fixture with the second press moved past the cooldown must produce a second burst.
     */
    @Test
    fun `releasing and re-pressing after the cooldown produces a second burst`()
    {
        val sim = isolatedSim()
        val secondPress = 30                        // 0.500 s — clear of the 0.45 s cooldown

        var previous = false
        var starts = 0
        repeat(120) { i ->
            sim.tick(dt, swimDown.copy(kick = i == 0 || i == secondPress))
            if (sim.kicking && !previous) starts++
            previous = sim.kicking
        }

        assertEquals(2, starts, "a press ${secondPress * dt}s after the first is past the cooldown and must boost again")
    }

    /**
     * THE ANTI-MASHING ASSERTION, and the reason the cooldown is measured from a burst's START.
     *
     * If it were measured from a burst's END, or absent entirely, a player alternating the button
     * every single tick would re-open the window as fast as it closed and would have the old
     * continuous kick back. Measured from the start, the boost can occupy at most
     * `KICK_BURST_SECONDS / KICK_COOLDOWN_SECONDS` = 78% of any stretch of time no matter how the
     * button is played, so mashing is strictly worse than a hypothetical hold-to-fly and the kick
     * stays a decision with an air price.
     *
     * The slack is one whole burst spread over the measured window — a burst already in flight
     * when the count starts, which cannot recur.
     */
    @Test
    fun `mashing the kick button every tick cannot approximate the old hold-to-fly behaviour`()
    {
        val sim = isolatedSim()
        val ticks = 600                             // ten seconds
        val boosted = boostedTicks(sim, ticks, base = swimDown) { i -> i % 2 == 0 }

        val duty = boosted.size.toFloat() / ticks
        val ceiling = Tuning.KICK_BURST_SECONDS / Tuning.KICK_COOLDOWN_SECONDS
        val slack = Tuning.KICK_BURST_SECONDS / (ticks * dt)

        assertTrue(
            duty <= ceiling + slack,
            "mashing every tick reached a duty cycle of $duty, above the $ceiling the cooldown is " +
            "supposed to impose — the cooldown is either gone or is being measured from the end of " +
            "a burst instead of its start, and hold-to-fly is back"
        )
        assertTrue(duty > 0.5f, "sanity: mashing must still be worth doing — measured $duty")
    }

    // --- Air ------------------------------------------------------------------

    /**
     * ONE KICK COSTS A FIXED AMOUNT OF AIR. That is the economic point of the change: under the
     * old semantics the price of a kick was however long you leaned on the button, which made the
     * air gauge a function of thumb stamina rather than of decisions.
     *
     * Measured with the diver PINNED IN THE KELP by giving him no direction to swim in. Kick
     * multiplies thrust, so with a neutral stick an empty (neutrally buoyant) diver does not move
     * at all and stays in one zone — which matters, because `zone.airBurn` steps from 1.0 to 2.2
     * across the column and a drifting diver would be measuring the zone boundary he crossed
     * rather than the burst.
     */
    @Test
    fun `one kick burns a fixed extra amount of air, however long the button is then held`()
    {
        fun airBurnedOver(seconds: Float, press: (Int) -> Boolean): Float
        {
            val sim = isolatedSim()
            sim.debugSetDepth(40f)                  // Kelp: airBurn 1.2, and far from either boundary
            val before = sim.air
            repeat((seconds / dt).toInt()) { i -> sim.tick(dt, idle.copy(kick = press(i))) }
            assertEquals(40f, sim.depth, 0.001f, "the fixture is void unless the diver stayed in one zone")
            return before - sim.air
        }

        val drifting = airBurnedOver(3f) { false }
        val oneClick = airBurnedOver(3f) { i -> i == 0 }
        val heldDown = airBurnedOver(3f) { true }

        assertEquals(
            oneClick, heldDown, 0.01f,
            "holding the button burned $heldDown against a single click's $oneClick — a kick's air " +
            "cost must not depend on how long the button is held"
        )
        assertEquals(
            Zone.KELP.airBurn * (Tuning.KICK_AIR_MULT - 1f) * Tuning.KICK_BURST_SECONDS,
            oneClick - drifting,
            Zone.KELP.airBurn * (Tuning.KICK_AIR_MULT - 1f) * dt * 1.5f,
            "one kick must cost exactly one burst of extra burn at the zone's own rate"
        )
    }

    /** The burn multiplier and the speed multiplier must be live on the SAME ticks, never one without the other. */
    @Test
    fun `air is burned at the kick rate on exactly the ticks the boost is applied`()
    {
        val sim = isolatedSim()
        sim.debugSetDepth(40f)

        var boostedTickCount = 0
        var extraAirBurned = 0f
        repeat(180) { i ->
            val before = sim.air
            sim.tick(dt, idle.copy(kick = i == 0))
            val burned = before - sim.air
            if (sim.kicking)
            {
                boostedTickCount++
                extraAirBurned += burned - Zone.KELP.airBurn * dt
            }
            else assertEquals(
                Zone.KELP.airBurn * dt, burned, 1e-4f,
                "tick $i was not boosted, so it must burn air at the plain zone rate"
            )
        }

        assertTrue(boostedTickCount > 0, "sanity: something must have been boosted")
        assertEquals(
            Zone.KELP.airBurn * (Tuning.KICK_AIR_MULT - 1f) * boostedTickCount * dt,
            extraAirBurned, 1e-3f,
            "every boosted tick must also have burned at KICK_AIR_MULT — the two multipliers " +
            "must come from one timer, not from two independent reads of the button"
        )
    }

    // --- A run reset ----------------------------------------------------------

    /**
     * A BUTTON HELD ACROSS A RESET MUST NOT AUTO-FIRE. `resetDive` clears the burst and the
     * cooldown (a new dive starts from rest, as vx/vy do) but deliberately does NOT clear
     * `kickWasPressed`, because that field mirrors the player's thumb rather than the run: zeroing
     * it would manufacture a rising edge on the very next tick and hand a free kick to anyone who
     * happened to be holding the button as they surfaced.
     *
     * The diver here surfaces with something held while leaning on kick the whole time, which is
     * not a contrived input at all — kicking for the surface with a full haul is the normal way a
     * dive ends.
     */
    @Test
    fun `a kick held across a run reset does not auto-fire a new burst`()
    {
        val sim = isolatedSim()
        sim.debugSetDepth(2f)
        sim.debugSetHeld(count = 100, mass = 0f)

        var reset = false
        var previous = false
        var burstsAfterReset = 0

        repeat(300) {
            sim.tick(dt, DiveInput(0f, -1f, kick = true, bleed = false))
            if (reset && sim.kicking && !previous) burstsAfterReset++
            previous = sim.kicking
            if (sim.diveEnded != null) reset = true
        }

        assertTrue(reset, "sanity: the dive must actually have closed out for this to test anything")
        assertEquals(0, burstsAfterReset, "a button already down when the dive reset must not read as a new click")
    }

    /** ...and the complement: letting go and pressing again after the reset works normally. */
    @Test
    fun `a fresh click after a run reset boosts again`()
    {
        val sim = isolatedSim()
        sim.debugSetDepth(2f)
        sim.debugSetHeld(count = 100, mass = 0f)

        var ticksSinceReset = -1
        var previous = false
        var burstsAfterReset = 0

        repeat(300) {
            // Lean on kick into the surface; after the reset let go for two ticks, then press again.
            val kick = if (ticksSinceReset < 0) true else ticksSinceReset >= 2
            sim.tick(dt, DiveInput(0f, -1f, kick = kick, bleed = false))
            if (ticksSinceReset >= 0 && sim.kicking && !previous) burstsAfterReset++
            previous = sim.kicking
            if (ticksSinceReset >= 0) ticksSinceReset++
            else if (sim.diveEnded != null) ticksSinceReset = 0
        }

        assertTrue(ticksSinceReset >= 0, "sanity: the dive must actually have closed out")
        assertEquals(
            1, burstsAfterReset,
            "letting go and clicking again after a reset must boost — the reset clears the burst " +
            "and the cooldown, so a genuine new click is never refused"
        )
    }

    // --- The speed ceiling ----------------------------------------------------

    /**
     * The ceilings are not literals — they must stay the buoyancy model's OWN maxima, or the clamp
     * silently becomes a behaviour change the next time a thrust or a multiplier moves.
     *
     * `dive/Tuning.kt` may hold no logic, so the constants there are written as products of that
     * file's constants; this is the ring test that ties them back to `Buoyancy`, which owns the
     * actual formula. The mass sweep is the other half: the claim is that zero mass with a full
     * stick and the kick boost is the LARGEST target either axis has, and the sweep is what would
     * catch that ceasing to be true (it turns on the drag divisor growing faster than the sink
     * term, which is a relationship between four constants and not an obvious one).
     */
    @Test
    fun `the speed ceilings are the buoyancy model's own maxima`()
    {
        assertEquals(
            Buoyancy.verticalSpeed(0f, 1f, Tuning.KICK_SPEED_MULT), Tuning.MAX_VERTICAL_SPEED, 1e-4f,
            "MAX_VERTICAL_SPEED must be the fastest target Buoyancy can produce, not an invented number"
        )
        assertEquals(
            Buoyancy.lateralSpeed(0f, 1f, Tuning.KICK_SPEED_MULT), Tuning.MAX_LATERAL_SPEED, 1e-4f,
            "MAX_LATERAL_SPEED must be the fastest target Buoyancy can produce, not an invented number"
        )

        var mass = 0f
        while (mass <= 400f)
        {
            for (stick in listOf(-1f, 0f, 1f))
            {
                assertTrue(
                    abs(Buoyancy.verticalSpeed(mass, stick, Tuning.KICK_SPEED_MULT)) <= Tuning.MAX_VERTICAL_SPEED + 1e-3f,
                    "mass $mass, stick $stick targets a vertical speed above the declared ceiling"
                )
                assertTrue(
                    abs(Buoyancy.lateralSpeed(mass, stick, Tuning.KICK_SPEED_MULT)) <= Tuning.MAX_LATERAL_SPEED + 1e-3f,
                    "mass $mass, stick $stick targets a lateral speed above the declared ceiling"
                )
            }
            mass += 1f
        }
    }

    /**
     * THE CLAMP MUST BE INERT, and this asserts that rather than merely asserting the bound.
     *
     * `assertTrue(|v| <= ceiling)` would pass even if the clamp were doing all the work, which
     * would mean the ceiling had quietly become a gameplay change. So the sweep below asserts a
     * STRICT inequality with room to spare: across masses, stick directions, mashing patterns and
     * two different fixed timesteps, no reachable velocity ever gets near either ceiling, so the
     * clamp never binds and the diver moves exactly as he did before it existed.
     */
    @Test
    fun `no reachable state comes near the speed clamp, so the clamp changes nothing`()
    {
        var worstVertical = 0f
        var worstLateral = 0f

        for (mass in listOf(0f, 16f, 64f, 160f, 320f))
            for (stick in listOf(-1f, 0f, 1f))
                for (step in listOf(1f / 60f, 1f / 20f))
                    for (pattern in 0 until 3)
                    {
                        val sim = isolatedSim()
                        sim.debugSetDepth(60f)
                        sim.debugSetHeld(count = 0, mass = mass)
                        repeat((6f / step).toInt()) { i ->
                            val kick = when (pattern)
                            {
                                0 -> true                 // held
                                1 -> i % 2 == 0           // mashed
                                else -> i % 40 == 0       // clicked on the cooldown
                            }
                            sim.tick(step, DiveInput(horizontal = stick, vertical = stick, kick = kick, bleed = false))
                            worstVertical = maxOf(worstVertical, abs(sim.vy))
                            worstLateral = maxOf(worstLateral, abs(sim.vx))
                        }
                    }

        assertTrue(worstVertical > 1f && worstLateral > 1f, "sanity: the sweep must have actually moved the diver")
        assertTrue(
            worstVertical < Tuning.MAX_VERTICAL_SPEED,
            "the fastest reachable vertical speed was $worstVertical against a ceiling of " +
            "${Tuning.MAX_VERTICAL_SPEED} — if these meet, the clamp is shaping movement rather than guarding it"
        )
        assertTrue(
            worstLateral < Tuning.MAX_LATERAL_SPEED,
            "the fastest reachable lateral speed was $worstLateral against a ceiling of " +
            "${Tuning.MAX_LATERAL_SPEED} — if these meet, the clamp is shaping movement rather than guarding it"
        )
    }

    /**
     * The two constants have to stay on the right side of each other or the whole shape of the
     * mechanic changes: a cooldown no longer than the burst means back-to-back bursts with no gap,
     * which is hold-to-fly by another name.
     */
    @Test
    fun `the cooldown outlasts the burst, so there is always a gap between kicks`()
    {
        assertTrue(
            Tuning.KICK_COOLDOWN_SECONDS > Tuning.KICK_BURST_SECONDS,
            "the cooldown (${Tuning.KICK_COOLDOWN_SECONDS}s) must outlast the burst " +
            "(${Tuning.KICK_BURST_SECONDS}s), or bursts run back to back and the kick is a throttle again"
        )
        assertTrue(
            Tuning.KICK_BURST_SECONDS >= dt * 4f,
            "a burst shorter than a handful of fixed ticks would be lost in the velocity ramp entirely"
        )
    }

    /** [DiveSim.kicking] is what the renderer reads to speed the swim loop up; it must not latch. */
    @Test
    fun `kicking is false before the first click and false again once the burst ends`()
    {
        val sim = isolatedSim()
        assertFalse(sim.kicking, "a fresh sim must not think it is mid-kick")

        sim.tick(dt, kickHeld)
        assertTrue(sim.kicking, "the tick the button goes down is boosted")

        repeat(120) { sim.tick(dt, kickHeld) }
        assertFalse(sim.kicking, "kicking must fall back to false on its own, not latch")
    }
}
