# Kick as a stroke, not a throttle — design

*2026-08-30. Amends the locked design spec §5 (Controls) and the feel described in §4.
Read alongside `docs/superpowers/specs/2026-08-04-en-pust-til-design.md`.*

## The ask

> "Make the kick only give one boost per click. It should not be continuous as it is today
> when holding down the kick button. It should also slightly increase the anim speed of the
> spritesheet during the kick."

Spec §5 currently reads **"A (hold) — Kick — 3× speed, 3× air burn"**. This overrides that.
The button becomes a stroke you throw, not a throttle you lean on.

## What kick is today

Two lines, both reading the raw level `input.kick`:

- `dive/DiveSim.kt:169` — `val boost = if (input.kick) Tuning.KICK_SPEED_MULT else 1f`
- `dive/DiveSim.kt:202` — `val burn = zone.airBurn * (if (input.kick) Tuning.KICK_AIR_MULT else 1f)`

Hold the button and both stay multiplied forever. That is the behaviour being removed.

## The model: a timed burst of the existing multipliers

A rising edge on `input.kick` opens a fixed window. Inside it, today's `KICK_SPEED_MULT` (3×)
and `KICK_AIR_MULT` (3×) apply exactly as they do now; outside it, both are 1×.

**Rejected: a one-shot velocity impulse** (`vx += KICK_IMPULSE`). It is the more literal
reading of "one boost per click" and it is the wrong model here, because it bypasses the drag
and response system the whole feel is built on. `updateMovement` eases velocity toward a
*target* scaled by `Buoyancy.responseRate(heldMass)`, and that is precisely what makes a
laden diver sluggish — design §4's central mechanic. An additive Δv would give a diver
hauling a full load the same instant snap as an empty one, deleting the risk/reward the
economy rests on. It would also need its own air-cost constant and would weaken what
`DiveSimTest` and `NoReturnTest` currently assert.

The burst keeps every one of those properties: the boost still has to fight mass, still
lands slowly on a heavy diver, and the air cost falls out for free — 3× burn for a
fixed-length window **is** a fixed cost per kick, with no new economy constant invented.

## Edge detection lives inside `DiveSim`

`DiveInput.kick` stays a **level** reading. `DiveSim` tracks the previous frame itself and
derives the rising edge.

This is the codebase's one convention, not a preference: the engine's `Gamepad` exposes only
`isPressed`/`getAxis` and has no `wasClicked`, so every consumer of lifecycle input already
does its own edge detection (`RunLifecycle`, `InitialsEntry`, `LifecycleInputEdges`). Doing it
in the sim keeps `dive/` pure and engine-free, keeps the behaviour bit-identical between the
keyboard `Z` and the gamepad, and makes the whole thing unit-testable at a fixed 60 Hz with
no GL context.

The previous-frame flag resets wherever a run resets, so a button already held when a run
starts cannot auto-fire a kick into the first tick.

## Numbers

New `Tuning` constants (`Tuning` holds values and no logic):

| Constant | Value | Why |
|---|---|---|
| burst length | **0.35 s** | Long enough to read as a stroke rather than a twitch. |
| cooldown | **0.45 s**, from the START of a burst | Longer than the burst, so bursts can never overlap and there is a 0.1 s dead gap after each. This is what stops mashing from approximating the old hold-to-fly behaviour, and it is asserted as a duty-cycle bound rather than trusted. |
| animation multiplier | **1.5×** | Visible as a faster stroke without reading as fast-forward. |

## The speed ceiling

The owner asked for a maximum speed. Worth stating precisely, because the honest answer is
that one already exists implicitly and the new constant is a guarantee rather than a change:
`updateMovement` eases velocity *toward* a target (`vx += (targetVx - vx) * response`), so
speed approaches the `Buoyancy` target asymptotically and never passes it. Kicks cannot stack
the way an additive impulse would.

An implicit bound is not a guarantee, so it is written down: a `Tuning` ceiling derived from
the model — the maximum `Buoyancy` target at zero mass, full stick, `boost = KICK_SPEED_MULT`
— clamped in `DiveSim`, with a test asserting no reachable state exceeds it.

**Clamped per axis, not on the combined magnitude.** Clamping `sqrt(vx² + vy²)` would make
diagonal swimming slower than it is today, which is a gameplay change nobody asked for.
Per-axis clamping bounds the diagonal at `sqrt(vxMax² + vyMax²)` and leaves every currently
reachable state untouched — which is the acceptance criterion: **every existing `dive/` test
must pass unmodified.** If one fails, the clamp changed behaviour it should not have.

## The animation

`DiverSprite.advanceLoop(dt)` adds `dt` to a phase, so the speed change is one argument:
`advanceLoop(dt, speedMultiplier)`, defaulted to `1f` so existing callers are unchanged.
`EnPustTil.onFixedUpdate` passes the multiplier when the sim reports a kick is active, inside
the existing `lifecycle.spriteAnimates` gate.

`advancePhase`'s frame-rate independence and `frameIndex`'s wrap guarantee are both load-bearing
and both document real bugs in their KDocs — the multiplier scales `dt` going in and must not
disturb either.

`DiveSim` exposes a read-only `kicking` flag for this. Note the direction of the dependency:
the renderer asks the simulation, and no presentation state moves into `dive/` — CLAUDE.md's
rule that `DiveSim` simulates one run and nothing else.

## Testing

Beyond the clamp and per-axis criteria above:

- one click boosts for exactly the burst length and then stops **even though the button is
  still held** — this is the actual regression, and the one a test written against the old
  semantics would miss;
- holding forever yields exactly one burst;
- re-pressing during the cooldown produces nothing; after it, a second burst;
- mashing every tick cannot exceed the duty cycle the cooldown implies;
- air burn is 3× only inside the burst, so one kick costs a fixed amount of air;
- a kick edge does not survive a run reset.

## Documentation

Design spec §5's table (hold → click) and a §17 amendment-log entry recording that this
supersedes the hold semantics, with the reasoning above for why the burst was chosen over an
impulse.
