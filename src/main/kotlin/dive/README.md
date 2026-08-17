# `dive/` — the simulation

↑ [Repo root](../../../../README.md) · [CLAUDE.md](../../../../CLAUDE.md) · [Design spec](../../../../docs/superpowers/specs/2026-08-04-en-pust-til-design.md) · [Docs map](../../../../docs/README.md)

This package is the whole game, minus the pictures. It simulates one free-diving run: a 90-second
clock, unlimited dives inside it, and the arithmetic that decides what a dive was worth. It has
**zero engine imports** — no `no.njoh.pulseengine.*`, no GL, no assets, no window — which is what
lets every rule in here be unit-tested without a GL context, on a build server, in milliseconds.
It is ticked at a **fixed 60 Hz**: `EnPustTil.kt:666` sets `engine.config.fixedTickRate = 60f`, and
`EnPustTil.kt:878` is **the only call to `DiveSim.tick` in the game** (grep it) — that is what makes
a pause airtight rather than cosmetic, since gating that one line freezes clock, air, depth and
pickups together with no second path.

The behaviour here is *locked* by `docs/superpowers/specs/2026-08-04-en-pust-til-design.md`. Read the
relevant section before you change anything; §17 is the amendment log and records which of the
spec's own numbers have since been superseded.

## Files

| File | What it owns |
|---|---|
| `DiveSim.kt` | All run state and `tick(dt, DiveInput)` — the tick order, banking, blackout, bleed, pickups, the `diveEnded` event, and `maxSafeDepth()`. |
| `Tuning.kt` | Every tunable constant in the game. Values only, no logic. |
| `Zone.kt` | The five depth bands and what each one is worth: `minDepth`, `pearlValue`, `pearlMass`, `airBurn`, plus `Zone.at(depth)`. |
| `Buoyancy.kt` | The movement model: sink force, drag, terminal vertical/lateral speed, response rate. All pure functions of carried mass. |
| `Ascent.kt` | The air arithmetic of the climb home, integrated across the zones a climb crosses. `airNeeded` and its exact inverse `maxDepthReachableOn`. |
| `AirPocket.kt` | A vent (one-use-per-dive breath refill) and `AirPocketField.generate(seed)`, which places one per zone in Kelp/Twilight/Trench and none in the Abyss. |
| `Anglerfish.kt` | The only enemy. Drift toward the diver, bite radius, bite cooldown. Position and timing only — the theft itself is `DiveSim.bite`. |
| `Pearl.kt` | A pearl (position, zone, collected flag; value and mass delegate to its zone) and `PearlColumn.generate(seed)`, 14 pearls per zone. |
| `Scoring.kt` | Three lines: `depthBonus`, `bank`, `blackoutBank`. |
| `DiveInput.kt` | One frame of player intent: `horizontal`, `vertical`, `kick`, `bleed`. |

Tests live in `src/test/kotlin/dive/`. `Autopilot.kt` is a test-source *tool*, not a test — see
"Changing behaviour safely".

## The model

### A run

`DiveSim(seed)` generates the column (pearls and vents) from the daily seed and starts the diver at
the surface with `RUN_SECONDS` on the clock and `BASE_AIR_SECONDS` of breath. Pearls are **held**,
not scored, until the diver breaks the surface; surfacing banks them, refills air and rearms every
vent, so surfacing costs nothing but time (spec §2). `runOver` latches at 0:00 and `tick` no-ops
after that. Anything still held at 0:00 is **lost** — `DiveSim.kt:79-80` zeroes `held` and
`heldMass` before returning, and no `diveEnded` event fires for the clock (spec §8, "The clock").

### Tick order, and why it is load-bearing

`DiveSim.tick` (`DiveSim.kt:65-119`) runs in this order, and each step's position is defended by a
comment where it lives. `DiveSimTest.tick pipeline order is pinned - vent, then surface, then
blackout, then collect` asserts the *sequence* in one test so a reorder fails loudly.

1. **Clear `diveEnded`** — it is an event valid for exactly one tick. Cleared even when `runOver` is
   about to short-circuit, since a dive cannot end twice.
2. **Clock** — expiry ends the run here, before anything else can bank.
3. **`updateBleed`** — dumping ballast, `BLEED_RATE` mass per second while held. Value drops by the
   same *fraction* as mass, and `BLEED_EPSILON` cleans both to exactly zero at the bottom.
4. **`updateMovement`** — target velocity from `Buoyancy`, eased toward with `1 - e^(-k*dt)` (so it
   is frame-rate independent), integrated, clamped to the column and to `0..MAX_DEPTH`, velocity
   killed into a wall. `maxDepthThisDive` is updated here.
5. **`refillAtAirPockets`** — **before the air burn**, so a vent reached on the dying breath saves
   the diver instead of missing by one tick (spec §6b).
6. **The surface check** — also **before the air burn**, for the same reason: a diver who touches the
   surface on the tick their air would hit zero has visibly made it home and must bank, not black
   out. It only runs the bank-and-reset pipeline when there is a dive to close out (`held > 0 ||
   maxDepthThisDive > 0f`); without that guard an empty diver idling at the surface would re-surface
   every tick and `surface()`'s velocity reset would cancel horizontal swimming before it built up.
7. **`updateAir`** — `zone.airBurn` per second, times `KICK_AIR_MULT` while kicking. Hitting zero
   blacks out and **ends the tick**: no pickup, no bank, no bite.
8. **`collectPearls`** — after the blackout return, so nothing is swept up on a blackout tick.
9. **`updateAnglerfish`** — deliberately last. After `updateMovement` so it chases this tick's
   position, and after the blackout return so a bite can never be resolved on a tick that also
   blacks the diver out (otherwise the 10% blackout bank would be computed on an already-reduced
   `held` — pinned by `AnglerfishTest.a bite does not happen on the same tick the diver blacks out`,
   which checks 200 rather than 120).

### Buoyancy and carried mass — the central mechanic

Spec §4. **An empty diver is neutrally buoyant and hovers**: `Buoyancy.verticalSpeed(0, 0, 1) == 0`.
Carried mass is what breaks that neutrality, in three places at once:

- `sinkForce(mass)` pulls you down, so a laden diver sinks with no input at all;
- `dragCoefficient(mass)` saps speed in **every** direction, so a haul is sluggish sideways too;
- `responseRate(mass)` falls with load, so a full haul is slow to start *and* slow to stop.

Past `SWIM_THRUST / SINK_FORCE_PER_MASS` (11 / 0.06 ~= 183 mass) the sink force beats a full
up-stroke and no climb is possible at all; `maxSafeDepth()` returns 0 there rather than dividing by
zero (`NoReturnTest`, `BuoyancyTest.climbing becomes impossible past the mass where sink force meets
thrust`). This is the risk amplifier the economy rests on: the greedy diver falls into danger more
easily and climbs out of it less easily.

### Zones

`Zone.kt:13-17`. Boundaries are inclusive at the lower edge — depth 30.0 is Kelp, 29.99 is Shallows.

| Zone | From | Pearl value | Pearl mass | Air burn |
|---|---|---|---|---|
| SHALLOWS | 0 m | 10 | 1 | 1.0 |
| KELP | 30 m | 25 | 2 | 1.2 |
| TWILIGHT | 60 m | 60 | 4 | 1.6 |
| TRENCH | 90 m | 150 | 8 | 2.0 |
| ABYSS | 120 m | 400 | 16 | 2.2 |

Air burn is the only thing here that touches physics; value and mass are economy. The Abyss's 2.2
is a *tuning dial* — it came down from the spec's 2.5 to make the zone reachable (§17) — and
`ZoneTest.air burns faster deeper` deliberately does not pin it, asserting strict monotonicity,
`SHALLOWS == 1.0` and `ABYSS > 2` instead.

Spec §3 also asks each zone to differ in **how you move** (kelp fronds, trench currents). That is
not implemented yet; today the zones differ only in the table above plus presentation.

**ADDING OR REMOVING A ZONE IS NOT A `dive/`-ONLY CHANGE.** `Zone.ordinal` indexes six parallel
hand-written arrays over in `render/Look.kt` — `ZONE_RED` / `ZONE_GREEN` / `ZONE_BLUE` and
`AMBIENT_RED` / `AMBIENT_GREEN` / `AMBIENT_BLUE` — read through `render/DepthBlend.blend`, which
takes "one entry per `Zone`, indexed by `Zone.ordinal`" literally: no bounds check, no default. A
sixth zone without a sixth entry in all six arrays throws an `ArrayIndexOutOfBoundsException` from
inside a draw call, on the first frame that paints the new band. This is one of only two places
where `dive/` and `render/` are coupled by anything other than a function call (the other is the
pickup-radius-versus-sprite check in `TuningTest`), and it is invisible from this package.
`DiveRendererTest.every per-zone colour table in Look has exactly one entry per Zone` is the guard.

### Air, vents and the climb home

Air burns at the zone the diver is **in**. A climb, though, crosses progressively cheaper water, so
pricing an ascent at the current zone's rate overstates it badly — by about 70% out of the Abyss.
`Ascent.airNeeded` integrates the climb zone by zone at a constant climb speed, plus one velocity
spin-up charged in the zone the diver turns around in; `Ascent.maxDepthReachableOn` is its exact
inverse, solved by spending the budget downward rather than by searching, and `DiveSim.maxSafeDepth`
calls it every frame to place the HUD's point-of-no-return marker. `AscentTest.maxDepthReachableOn
inverts airNeeded` holds the two together, and `the marker is never optimistic` flies 27 grid cases
to prove the marker never promises a way home that a flown dive cannot deliver. **Do not
"simplify" this back to `air / zone.airBurn`** — that is the bug it replaced.

Vents (`AirPocket.kt`) are the ladder that makes depth a route-planning problem: one each in Kelp,
Twilight and Trench, **none in the Abyss** (that zone is the gamble you take on whatever breath you
arrive with), one use per dive, rearmed on surfacing. The deepest vent sits in its own lower band
(`DEEP_BAND_START = 0.55`) because it is the staging post for the plunge; that placement plus the
2.2 Abyss burn is what makes one 16-mass abyss pearl carryable from 135 m and not from 140 m.

### Scoring

`BANKED += HELD * (1 + maxDepthReached / 30)`. The bonus keys off the deepest point **reached**, not
where pearls were collected, and resets every dive — which is what makes the expert route work: dive
fast and empty to lock the multiplier, then sweep upward through pearls you deliberately left
behind (spec §9; `Pearl`'s persistence is the other half of it). A blackout keeps `BLACKOUT_KEEP`
(10%) of held with **no** depth bonus — devastating, survivable, never a zero.

### The anglerfish

Exists only in the Abyss; created the first tick the diver enters it and destroyed the moment they
leave. It drifts at `ANGLERFISH_DRIFT_SPEED` toward the diver, clamped so a step can never
overshoot. Its lure is drawn identically to a pearl, so **the tell is motion** — real pearls sit
still. A bite steals `ANGLERFISH_STEAL_FRACTION` (40%) of held value and mass and starts a cooldown;
it **must never end the run** (spec §7), and `AnglerfishTest.being bitten steals held pearls but does
not end the run` pins both the exact fraction and `runOver == false`.

### The no-return condition

`canStillReturn()` is `depth <= maxSafeDepth()`. It is advisory — nothing in the sim stops a diver
crossing it — and it is the mercy that teaches the economy without a word of text. The only error
direction that matters is optimism: under-promising costs a player some depth, over-promising kills
a run that trusted it. That asymmetry is why the spin-up term exists in `Ascent`.

## The numbers live in `Tuning.kt`

`Tuning` is values only, no logic, and it is the file you edit to change game feel. Everything that
reads a constant reads it from there — `Buoyancy`, `Ascent`, `DiveSim`, `PearlColumn`,
`AirPocketField`, `Anglerfish`. Several constants carry long comments explaining what was measured
before the value was chosen (`PEARL_PICKUP_RADIUS` is the extreme case); those measurements are the
argument, so read them before overruling them.

A few tests pin a *value* because the design does: `RUN_SECONDS == 90`, `BASE_AIR_SECONDS == 30`,
`BLACKOUT_KEEP == 0.10`. The more interesting ones assert a **relationship**, and if the build fails
there it is a design rule speaking, not a stale expectation:

| Test | The rule |
|---|---|
| `TuningTest.the pearl pickup circle agrees with the diver that is drawn on top of it` | A two-sided squeeze of `PEARL_PICKUP_RADIUS` against `render.Framing.DIVER_HEIGHT_METRES` and `PEARL_SIZE_METRES`. Too small leaves a dead band at head and fins, on the swim axis; too large collects pearls that touch no part of him. **This is the only place `dive/` and `render/` are compared at all** — the coupling exists nowhere in production and has silently drifted twice. |
| `TuningTest.a vent is never harder to hit than a pearl` | `AIR_POCKET_PICKUP_RADIUS >= PEARL_PICKUP_RADIUS`. Missing a vent ends a run; missing a pearl does not. Currently an equality, deliberately. |
| `ZoneTest.air burns faster deeper` | Strictly monotonic burn, `SHALLOWS == 1.0`, `ABYSS > 2`. The whole table cannot be flattened to a constant. |
| `AscentTest.one abyss pearl can be carried home from 135 metres` / `no abyss pearl can be carried home from 140 metres` | The two ends of the sharp edge, flown. Both are written against `Zone.ABYSS.pearlMass`, not a literal 16, so re-pricing the zone re-prices the assertion. Any lever that makes the dive easier trips the second one. |
| `ScoringTest.full and shallow loses badly to deep and loaded` | The two degenerate strategies must lose by arithmetic (spec §3). |

## Changing behaviour safely

1. **Read the spec section first.** The design is locked; §17 tells you which of its own numbers
   have been superseded and why.
2. Change `Tuning` (a feel or balance number) or the object that owns the rule (`Zone`, `Buoyancy`,
   `Ascent`, `Scoring`, `DiveSim`). Keep the reasoning next to the change, in the density the rest
   of the package uses.
3. `./gradlew test --tests "dive.*"` — the whole package runs in seconds and needs no display.
   `./gradlew test` if you touched anything `render/` compares against.

**`Autopilot.kt` is how balance claims get measured instead of argued.** It flies a scripted pilot
through a real `DiveSim` at the real 60 Hz tick and returns a `DiveLog` (survived, banked, max
depth, air at the deepest point, abyss dwell, peak load, seconds past the no-return line). It exists
because every balance number in this project that came from algebra on `Buoyancy` was wrong by a
wide margin — the formulas ignore the velocity ramp, the lateral detour to a vent, and the fact that
burn is sampled at the zone the diver is in. Two things to know before using it:

- The steering is a plain proportional controller, so every survivability number it produces is a
  **lower bound** — the safe direction to be wrong in.
- `Autopilot.clearColumn(sim, keep)` is mandatory for any "can ONE pearl come home?" question. The
  pilot swims a straight line home and `collectPearls` hoovers up whatever it passes, so an
  unfiltered run silently measures a much harder question. That was a real error in the first pass.

`Autopilot.maxSurvivableLoad`, `Waypoint.hold`/`onArrive` and the `bleedToSafety` flag are
investigation instruments — several are not exercised by the committed suite and exist so the next
balance question can be answered by flying it.

## Invariants you must not break

- **No engine imports.** Nothing in `dive/` may import from Pulse Engine, `render/` or `score/`. It
  is the reason the package is testable at all.
- **No presentation state.** `DiveSim` simulates one dive and nothing else. Attract mode, dwell
  timers, pause and initials entry live in `render/RunLifecycle`; camera, aim heading and sprite
  phase live in `render/`. The oriented-capsule hitbox was rejected outright for exactly this reason
  (§17): it needs the diver's heading, which `render/` owns.
- **Determinism.** Seeded RNG only (`Random(seed)` in `PearlColumn`, `Random(seed * 31 + 7)` in
  `AirPocketField`), fixed timestep, and **no `Math.random()` anywhere in `dive/`**. Everyone at the
  booth faces the identical column, and day two is a one-line `dailySeed` change.
- **Frame-rate independence.** Velocity easing is `1 - e^(-k*dt)`; a naive `speed * dt` is only
  acceptable for constant-velocity drift that cannot overshoot (see `Anglerfish.update`'s
  `min(step, dist)` clamp). `DiveSimTest.hydrodynamics are frame-rate independent` compares 60 Hz
  against 240 Hz.
- **No per-frame allocation** in `tick` or anything it calls. `Ascent` returns primitive floats and
  walks `Zone.entries` for this reason.
- **State stays `private set`.** The only writes from outside are the `internal fun debug*` hooks at
  the bottom of `DiveSim.kt`, which exist for tests and for the `EPT_DEPTH` dev pin.
- **`diveEnded` is a one-tick event.** It replaced two fields that latched forever (`blackedOut`,
  `lastBankAmount`); `DiveEndedTest` pins the reproduction. Do not add a field that survives past
  the tick it fired on.
