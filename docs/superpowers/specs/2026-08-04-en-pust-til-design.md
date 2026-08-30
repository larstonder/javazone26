# Én Pust Til — design

**Status:** 🔒 **LOCKED** — this is the game. Design approved 2026-08-04.
**Date:** 2026-08-04
**Target:** Capra booth, JavaZone 2026, NOVA Spektrum Lillestrøm, 2–3 September 2026
**Stack:** Kotlin 2.2.20 + Pulse Engine 0.13.0 (this repo), shipped as a Windows `.exe`
with bundled JRE via `buildWin64Release`

> One 90-second breath-hold. Dive as often as you like.
> You only keep what you carry back to the surface.

Selected from 32 candidate concepts drafted and scored during design exploration, where
it ranked 4th of 32 overall and 1st on both replay pull and leaderboard integrity. The
alternatives are no longer tracked — **this document is the single source of truth for
the game.**

---

## 1. What problem this design solves

The concept scored 9s across the board except **instant read (6)**. The original brief
admitted the weakness plainly: *"a stranger might not realise they have to come back."*
Held-versus-banked is a two-layer idea, and the brief's proposed fix was UI — an amber
arrow and a number stapled to the diver.

**This design replaces that UI fix with a physical one: pearls have weight.**

Mass slows your ascent, so greed is felt through the stick instead of read off a HUD.
Nobody needs to be told that heavy things are hard to lift. Everything else in this
document follows from that decision.

---

## 2. Core loop

You are a free-diver — a silhouette, no tank, no helmet, because "one breath" is the
whole premise. You sink, gather pearls, and return to the surface, where Neptune takes
the catch and the number lands.

Pearls are **held**, not scored, until you break the surface. Air refills instantly on
surfacing, so **surfacing costs nothing but time** — and time is the only real resource.

One 90-second clock. Unlimited dives within it. The player chooses the shape of the run:
five quick shallow raids, or two deep gambles.

---

## 3. Economy

```
BANKED += HELD × DEPTH_BONUS
DEPTH_BONUS = 1.0 + (maxDepthReachedThisDive / 30)
```

The bonus keys off the deepest point **reached**, not where pearls were collected, and
**resets every dive** — no carrying a multiplier forward.

This structure makes both degenerate strategies lose by arithmetic rather than by
playtesting:

| Strategy | Maths | Result |
|---|---|---|
| Dive deep, grab nothing | `0 × 5.0` | **0** |
| Sweep the shallows safely | `300 × 2.0` | **600** |
| Touch 120m, sweep up loaded | `2400 × 5.0` | **12,000** |

You have to do both things. That is the game.

### Zones

| Zone | Depth | Pearl value | Pearl mass | Air burn | How it plays |
|---|---|---|---|---|---|
| Shallows | 0–30 m | 10 | 1 | ×1.0 | Dense, cheap, forgiving. The tutorial nobody notices is a tutorial. |
| Kelp | 30–60 m | 25 | 2 | ×1.2 | Fronds slow you. Cheap to enter, expensive to leave. |
| Twilight | 60–90 m | 60 | 4 | ×1.6 | Pearls on ledges and outcrops. Precision over speed. |
| Trench | 90–120 m | 150 | 8 | ×2.0 | Lateral currents. You drift to pearls rather than swim to them. |
| Abyss | 120 m+ | 400 | 16 | ×2.5 ⚠ | Near-total dark. ~~Pearls are the only light source.~~ ⚠ One of them is hunting you. |

> ⚠ **Two cells in the Abyss row are superseded by §17 and this table was not annotated for a
> while.** The air burn is **×2.2**, not ×2.5 (amendment *"Abyss `airBurn` 2.5 → 2.2"*), and pearls
> are **not** the deep's light source — the torch is (amendment *"Pearls no longer brighten with
> depth"*). `dive/Zone.kt` is the authority for the numbers in this table.

Zones must differ in **how you move**, not only in value and colour. A zone that changes
only a number is a reskin.

> **UNBUILT, as of 2026-08-17.** The paragraph above is locked design that was never implemented:
> the Kelp's "fronds slow you" and the Trench's "lateral currents" do not exist in `dive/`, and the
> five zones currently differ only in pearl value, pearl mass and air burn — which is precisely the
> "reskin" this paragraph forbids. There is no §17 row deferring or cutting it, and it is not in
> §15's cut list either. Flagged here rather than silently left, because a reader of §3 has no
> other way to find out. The decision is the owner's.

---

## 4. Buoyancy and weight

The diver is a single point mass. No rigid-body physics anywhere.

```
mass          = baseMass + Σ(heldPearlMass)
ascentSpeed   = baseAscent  / (1 + mass / K_ASCENT)
descentSpeed  = baseDescent × (1 + mass / K_DESCENT)
```

Loading up makes you **sink faster and rise slower**. That is real physics and it is a
free risk amplifier: the greedy diver falls into danger more easily and climbs out of it
less easily.

**Starting values to tune from** (all subject to feel testing):

| Constant | Start | Note |
|---|---|---|
| `baseAir` | 20 s | at ×1.0 burn |
| `baseAscent` | 8 m/s | empty |
| `baseDescent` | 6 m/s | empty, passive sink |
| `K_ASCENT` | 40 | 40 mass units ≈ halves ascent speed |
| `K_DESCENT` | 120 | descent gain is deliberately weaker than ascent loss |
| kick multiplier | 3× speed, 3× air burn | both, always |
| ballast bleed | 8 mass units/sec | while B held |

> ⚠ **THIS WHOLE SECTION DESCRIBES A MODEL THE CODE NO LONGER USES, and §17 has no amendment row
> for it.** §17 logs *"Momentum and drag (§4)"*, but the change that actually invalidated the
> formulae above — **the diver became neutrally buoyant** — is unlogged, and it is the single
> biggest change to the movement model. Recorded here on 2026-08-17 by someone who did not witness
> the decision, so this note states *what* diverged and deliberately does **not** invent *why*.
>
> - **"baseDescent 6 m/s, empty, passive sink" is now false.** An empty diver **hovers**. Carried
>   mass is the only thing that makes you sink. Pinned by `BuoyancyTest.empty diver hovers` and
>   `DiveSimTest.an empty diver does not sink passively - it hovers`.
> - **None of `baseAscent`, `baseDescent`, `K_ASCENT` or `K_DESCENT` exist.** The model is
>   `SWIM_THRUST` (11), `SINK_FORCE_PER_MASS` (0.06), `K_DRAG` (200), plus `RESPONSE_RATE` (3.5)
>   and `K_RESPONSE_MASS` (60) for the momentum amendment. See `dive/Buoyancy.kt` and
>   `dive/Tuning.kt`; `NoReturnTest` still refers to the "old (now-deleted) `Buoyancy.ascentSpeed`".
> - **`baseAir` is 30 s, not 20** — that one §17 does log.
>
> What survives unchanged is the *design intent* the section opens with, and it is the part that
> matters: load makes you sink faster and rise slower, so greed is a risk amplifier. The
> arithmetic below it is superseded; `dive/Tuning.kt` is the authority.

---

## 5. Controls

Designed for **joystick + two arcade buttons** on a USB encoder.

| Input | Action |
|---|---|
| Stick | Swim, 8-way with acceleration and water drag |
| **A** (click) | **Kick** — one 0.35 s burst per press at 3× speed and 3× air burn, then a 0.45 s cooldown measured from the burst's start. Descent tool and escape tool. Holding the button yields exactly one burst; see the 2026-08-30 amendment. |
| **B** (hold) | **Bleed ballast** — pearls stream out continuously while held |

### The full shipped binding set

§5's table above is the *design*. This is what the code binds, including the keyboard mirrors
that exist so the game can be developed and demonstrated without an encoder.

| Action | Gamepad | Keyboard | Configurable? |
|---|---|---|---|
| Swim | Left stick **or D-pad** (analog wins while it is off its rest position, then the D-pad) | Arrow keys | No |
| Kick | `kickButton` (default A) — **one burst per click**, not a hold | `Z` | Gamepad only |
| Bleed | `bleedButton` (default B), held | `X` | Gamepad only |
| Start / restart / confirm | `restartButton` (START) or `restartButtonAlt` (A), on ANY connected pad | `SPACE` | Gamepad only |
| Cycle initials | Left stick up/down **or D-pad up/down**, any pad | `UP`/`DOWN` | No |
| Pause / cabinet menu | `pauseButton` (default BACK), on ANY connected pad | `ESC` | Gamepad only |
| Exit the cabinet | `exitButtonA` + `exitButtonB` (defaults LEFT_BUMPER + RIGHT_BUMPER) held TOGETHER on the SAME pad for 1.5 s, and only from the pause screen | hold `Q` | Gamepad only |

The asymmetry is deliberate: gamepad bindings are configurable because the arcade encoder's
real button codes are unknown until it is plugged in; keyboard bindings are not, because they
exist for development rather than for the booth.

**Pause and exit were keyboard-only until 2026-08-30, and the sentence that stood here said so
as a safety property: "so a stuck encoder button can never close the cabinet."** That is no
longer how the cabinet is protected, and the risk it named has not gone away — see the
controller-parity row in §17 for why the owner took the trade. What replaces it, in the shipped
code:

- **Exit is an AND across two buttons on one pad.** A single pad button would mean one stuck
  contact ends the day, which is exactly the old sentence's fear. Releasing either button
  resets the hold to zero, so a phantom exit now needs a phantom *pause* edge to reach the
  pause screen at all, then two simultaneous stuck contacts on the same pad, held together for
  the full 1.5 s. `exitButtonA == exitButtonB` collapses that AND back to one button and is
  therefore logged at WARN at startup.
- **Pause is edge-detected per source, never a level.** It goes through its own
  `LifecycleInputEdges` instance, fed `pauseButton` from every connected pad plus `ESC`'s own
  `wasClicked` — so a jammed pause button emits no edges at all, cannot mask the keyboard's
  edge, and is counted on the booth status line as stuck rather than silently holding a boolean
  true forever.
- **The map itself is guarded, on `.code` so an alias cannot evade it.** `pauseButton` landing
  on `kickButton` or `bleedButton` is logged at WARN — a player's kick would otherwise open the
  pause screen mid-run — and so is `exitButtonA == exitButtonB`. Neither exit button may be
  `pauseButton` either; that one is not code-checked because it is a mechanism rather than a
  preference: while PAUSED a pause edge means *resume*, so an exit combination containing it
  would leave the pause screen before the hold ever accumulated and could never fire. Nothing
  else in the map is warned about, deliberately — a false alarm on the booth status line is
  worse than no alarm, and the remaining pairs are read in disjoint states.

**Every on-screen prompt names whichever set is actually connected** (`render/ControlHints.kt`),
and presence includes raw GLFW joysticks — an encoder with no SDL mapping is invisible to
`engine.input.gamepads` but is still the only input the cabinet has.

**On-screen button labels follow the detected controller family** (`render/ControllerFamily.kt`),
so the same binding prints `CROSS` on a DualSense, `A` on a generic arcade encoder, and `MENU`
rather than `START` on an Xbox pad. This is a *labelling* change only — no binding moves, because
`GamepadButton.A` and `CROSS` share code 0, so `kickButton = A` already bound the physical Cross
button and only the printed string was wrong. An unrecognised device is a non-event: it falls
back to GENERIC, which is the enum's own name, i.e. exactly the screens the booth encoder printed
before families existed. The two lines on the pause screen itself (`ESC to resume`,
`HOLD Q to exit`) are deliberately still keyboard-only text — they are fixed `ScreenText`
constants for a technician, not hints composed per device.

### Why B is a dial, not a switch

Holding to bleed lets a player dump exactly as much as they need, turning a panic button
into an instrument operated under pressure. It is also the most spectator-legible thing
in the game: a desperate diver clawing upward with a glittering trail pouring out behind
them tells the whole story — greed, mistake, cost — to someone thirty metres away who has
never seen the game before.

---

## 6. Air

**Air is never a number.** It is a ring of bubbles orbiting the diver that visibly thins
as you breathe. At three bubbles remaining they turn red and pulse with a heartbeat that
rises in the mix.

---

## 6b. Air vents *(added after playtesting — see amendment log)*

**Swimming into a vent refills the breath.**

Without them the deep zones are not reachable-and-returnable at all. Descending to
the abyss costs roughly 29 of the 30 seconds of air, so the diver arrives with
nothing left for the slower loaded climb home — and kicking does not help, because
it triples speed and burn together, leaving air-per-metre unchanged. Depth was a
hard wall rather than a risk.

| Rule | Why |
|---|---|
| One vent each in **Kelp, Twilight and Trench** | A ladder: each vent lets you push one zone deeper. Knowing where the next sits is what separates a good run from a novice one. |
| **No vent in the Abyss** | The deepest, most valuable water stays a gamble on whatever breath you arrive with. The risk at the top end must survive. |
| **One use per dive** | Stops a vent being camped or farmed within a single breath. |
| **Rearmed on surfacing** | Not an exploit — surfacing already banks and refills air anyway. |
| **Checked before air burns** | A vent reached on the dying breath saves you, rather than missing by one tick. |
| **Spent vents dim, they do not vanish** | Knowing where a used vent was is what lets you plan the next dive around it. |

Vent placement is seeded independently of pearl placement so the two do not correlate.

---

## 7. The anglerfish

The only enemy in the game, and it lives only in the Abyss — where the only real money
is. Its purpose is to stop players camping the highest-value water.

- **Its lure looks like a pearl.** In the darkest zone, where pearls are the only light,
  you cannot tell treasure from predator at a glance.
- **The tell is motion.** Real pearls sit still. The lure drifts *very slightly toward
  you* — it is hunting. Learnable in two runs, unfair in none.
- **It eats your held pearls** and scatters a chunk of them into the dark. It does
  **not** end your run.

That last rule is load-bearing. Losing 1,800 held points to a fish is a story people tell
at the next booth. Dying to one is a reason to walk away from a queue.

---

## 8. The three ways to lose

| | What happens | Why |
|---|---|---|
| **Blackout** | You drift up limp and keep **10% of held, no depth bonus**. 2,400 becomes 240 while the music sags. | Devastating, survivable, funny. Never a zero. |
| **Anglerfish** | A chunk of held pearls scatters into the dark. | Punishes the greedy deep player specifically. |
| **The clock** | At 0:00 you stop where you are. **Anything still held is lost.** | This is the endgame. |

### The endgame

Around 0:25, every player faces the same question: *is there time for one more?*

A faint **point-of-no-return marker** on the depth tape shows how deep they can still
afford to be given the clock. It is a mercy for first-timers that teaches the economy
without a word of text — and ignoring it becomes the signature move of someone going for
the leaderboard.

Because each player reaches that moment at a slightly different time, the crowd never
knows exactly when to expect the final gamble. That is better than an imposed timer.

---

## 9. The expert route

Not designed — emergent, from weight plus max-depth bonus plus persistent pearls:

**Dive fast and empty to lock the multiplier, then collect on the way up.**

An empty diver descends quickly. Touching 120 m banks the ×5.0 into that dive regardless
of what happens next. Then you sweep upward through pearls you deliberately left on the
descent, getting heavier and slower exactly as you approach safety — the moment when
being heavy is most affordable.

**Consequence for content design: pearls must persist where they are.** A pearl passed on
the descent stays there. This is what makes the ascent the interesting half of every dive
rather than eight seconds of travel.

---

## 10. Map and content

**One hand-authored water column, identical for every player, all day.** A second column
ships for day two — fresh leaderboard, returning players, no extra code.

Fixed and hand-authored rather than generated, for three reasons:

1. Route planning is the skill; the layout has to be learnable.
2. **Spectators learn the map by watching**, which is how a queue gets invested.
3. It is the score-attack pillar — everyone runs the identical course, so the number is
   pure execution.

Authoring uses the Pulse Engine scene editor (F2), already in this repo.

---

## 11. Art direction

Free-diver in silhouette. Serious and beautiful rather than comic: deep blue falling to
near-black, bioluminescence in the dark. *(This read "shafts of light near the surface,
deep blue falling to near-black, bioluminescence in the dark" until 2026-08-17 — see §17.)*

This is also the most *readable* option for a booth screen — bright points of light on
near-black, high-contrast silhouettes, a glittering bleed trail against dark water. It
carries across a bright hall better than detailed art would.

**Neptune-Duke is the banker**, on the surface boat, taking the catch. That uses the
JavaZone mascot as a character rather than as set dressing.

The tone is quiet; the **audio** is not. The heartbeat and the cash-out carry the noise.

---

## 12. Feedback and presentation

### The cash-out
The single loudest thing in the game and the reason to build it. On surfacing:

1. Camera slams upward with the diver
2. Screen whites out in foam
3. Held number **flies into the banked total digit by digit**
4. **A rising chromatic chime — one note per 1,000 points**, getting higher and faster
5. Bass hit, and Neptune stamps the trident

A 12,000-point bank is a twelve-note ascending run lasting about a second and a half.
**You can hear how good a stranger's dive was from the far side of the hall without
looking at the screen.** That is how a queue forms.

### On-screen
- `BANKED` — small, cold white, top-left
- `HELD` — **enormous amber numerals attached to the diver**, wobbling, growing hotter as it grows
- Depth tape down the right edge, with the point-of-no-return marker
- A permanent amber up-arrow

### Leaderboard
Each run shown as its **dive profile** — the depth trace over time, which is what divers
actually chart. A cautious run is three shallow bumps; a great run is a shallow probe
followed by one enormous spike. It shows *how* someone scored, not just what they scored,
and it looks excellent on a second screen.

Three-letter arcade initials. **Never a form field** — a name form kills queue throughput.

---

## 13. Technical approach

- **Determinism throughout.** Fixed 60 Hz timestep, seeded integer RNG, no
  `Math.random()` in simulation. Chaotic outcomes read as *"the game cheated me"* in
  front of a queue.
- **No physics engine.** The diver is one point mass with drag; pearls are static until
  collected. Collision is circle-circle against a spatial hash.
- **Pooled particles** for bubbles, foam and the bleed trail. No per-frame allocation —
  ZGC is configured in `build.gradle.kts` but should not be leaned on.
- **Leaderboard** as a small local service the game POSTs to, with a QR on the booth wall
  so attendees can check standings from inside a talk.

> ⚠ **The leaderboard service was never built, and this is not recorded anywhere else.** There is no
> network code in the project at all: `score/ScoreRepository` writes a local `scoreboard.json` next
> to the game and the attract screen reads it back. No service, no POST, no QR. It is absent from
> §15's cut list and has no §17 amendment row, so — unlike the god rays or the pearl emission — it
> was never explicitly cut; it simply did not happen. Flagged 2026-08-17. Whether it still matters
> is the owner's call, and with the conference 17 days out it is worth deciding deliberately rather
> than by default. §12's **dive-profile** leaderboard is likewise unbuilt (the board draws plain
> rank / initials / score rows), but that one *is* covered by cut list item 4.

---

## 14. What to prototype first

In priority order. Each answers a question that could kill or reshape the design.

1. **Buoyancy feel** *(highest risk)* — an empty diver and a loaded one, sinking and
   rising, nothing else on screen. Does weight feel *satisfying* rather than *annoying*?
   If loading up feels like punishment rather than tension, the central mechanic is wrong
   and the design needs rework. **One evening.**
2. **The bleed trail** — hold B, watch pearls stream out. Is the trade-off legible in the
   moment, and does it read from across a room?
3. **The cash-out** — foam, digit flight, chime run. It is the marketing, so prove it
   early rather than treating it as polish.
4. **Instant read test** — put a colleague who knows nothing in front of it with no
   explanation. **Do they surface without being told?** If not, the amber arrow and the
   attract loop need rework before any content is authored.

---

## 15. Cut list

If time runs short, cut in this order. Everything above the line still leaves a complete
game.

1. The anglerfish (Abyss becomes purely an air gamble)
2. Zone-specific movement rules — keep value and air burn differences only
3. Trench currents
4. The dive-profile leaderboard (fall back to a plain number)
5. The second day-two water column

**Do not cut:** weight, the bleed button, or the cash-out. Those are the design.

---

## 16. Open questions

- **Pearl count and density per zone** — needs a first pass in the scene editor, then
  tuning against real 90-second runs.
- **How much the anglerfish eats** — a flat proportion, or scaled to what you are
  carrying? Flat is simpler; scaled punishes greed more precisely.
- **Whether the run hard-stops at 0:00 or allows an in-progress ascent to finish.** This
  design specifies a hard stop, because it is what creates the endgame gamble. Worth
  re-testing if first-timers find it harsh.
- **Second screen** — is one available at the booth for the leaderboard and dive
  profiles, or does it share the game screen between runs?

---

## 17. Amendment log

The design was locked on 2026-08-04 and then playtested. Changes made since, with
the reason each was needed:

| Change | Reason |
|---|---|
| `BASE_AIR_SECONDS` 20 → 30 | 20s was not enough air. The descent to the trench alone cost ~15s, leaving nothing for gathering or the loaded ascent. |
| **Smoothed camera** (§12) | The camera was locked exactly to the diver, which makes fast movement feel static — you never move relative to the frame however hard you kick. It now eases, so a hard descent visibly outruns it. Bounded to 15–75% of screen height so lag can never lose the diver. |
| **Momentum and drag** (§4) | Movement was direct position control: velocity snapped to target and stopped dead. Now velocity is persistent state that eases toward the target, and response falls as the diver loads up — so a full haul is sluggish to start *and* to stop. Terminal speeds are unchanged, so the buoyancy tuning still holds. |
| **Air vents** (§6b) | The deep zones were unreachable-and-returnable. See §6b. |
| **Point-of-no-return marker made zone-aware** (§12) | The marker priced the whole climb at the CURRENT zone's air burn, so from the Abyss it charged all 120 m at 2.5 instead of the 2.0/1.6/1.2/1.0 the climb actually crosses — a ~70% overstate. Measured against flown dives: of 27 sampled (depth, air, mass) combinations it said "you cannot get home" in **20 cases where the diver demonstrably could**, ordering players out of the deep a whole zone early. Now integrated across zones in `dive/Ascent.kt`, plus one velocity-ramp spin-up so it can never err optimistic. All 27 cases now agree. |
| **Deepest vent moved into the lower Trench** (§6b) | The Abyss was decoration. Flying the vent ladder arrived at 135 m with 21.6 s of air, and the heaviest load that could climb home from there was 14 mass — less than the 16 of a single abyss pearl. Six of fourteen abyss pearls were permanently unreachable, and on some seeds only one could be brought home at all. The last vent before the drop is now a staging post, not just another rung. **No vent in the Abyss** — that rule is unchanged. |
| **The world is rendered in METRES through the engine camera** (§12, presentation only) | Not a design change — recorded here because it changes what §10's "authoring uses the Pulse Engine scene editor" can mean, and because it makes one presentation guarantee explicit that the design always assumed. `render/Viewport.kt` is gone; it is `render/Framing.kt` and owns no transform, and `render/CameraRig.kt` is the sole writer of `engine.gfx.mainCamera`. **The aspect-ratio guarantee is now the engine's**: exactly 60 m of water is visible vertically on every display, and a wider panel shows more water sideways — never less water down. How far ahead you can see is how far ahead you can plan, so that is a gameplay constant and it is now checked on the real framebuffer (`CameraInvariants`, under `EPT_DEV`) rather than assumed. **Content generation stays procedural and seeded**: §10's scene-editor sentence continues to apply to the diver's visual representation, lights, particles and effects, and **not** to pearl, vent or anglerfish placement, which is generated from `dailySeed` so that everyone at the booth faces the identical column and day two remains a one-line config change. |
| **`PEARL_PICKUP_RADIUS` 2.5 → 4** | The hitbox had stopped agreeing with the diver you can see. Pickup is a circle about his centre; the sprite is a 2.95 × 9 m rectangle about the same point, so at 2.5 m there was a **1.95 m dead band at his head and at his fins** — and it sat on the swim axis, exactly where the player aims. A pearl visibly touching his mask did not collect, which reads as a bug rather than as a rule. Measured off the committed sheet (alpha > 16, all 41 frames): crown at 4.45 m, chin 3.56 m, widest half-extent 1.43 m. **3.5 was tried first and disproved** — it does not reach the chin, so the reported case survives it untouched. 4 puts the boundary on the head and leaves 0.45 m of crown and snorkel. **4.5 was rejected**: it clears the silhouette outright but takes lateral overreach from 2.57 m to 3.07 m past his flank and the accidental sweep-up of a neighbouring pearl from 22% to 27%, and bought exactly one more reachable abyss pearl. An oriented capsule matching the sprite was rejected outright — it needs the diver's *heading*, which is presentation state owned by `render/`, and `dive/` stays pure. **Balance cost, flown with `Autopilot` rather than argued:** abyss pearls brought home across seven seeds 30 → 33 of 98; max survivable load 24 → 26 at 130 m, 18 → 20 at 135 m, 9 → 12 at 140 m. The *shape* is unchanged, which is the property that matters — an abyss pearl is 16 mass, so 135 m stays carryable and 140 m stays a trap, and the worst seed still lands 2 pearls rather than being rescued by the change. `AscentTest` gained `no abyss pearl can be carried home from 140 metres`, which pins the NO that nothing had pinned. |
| **`AIR_POCKET_PICKUP_RADIUS` left at 4** | Considered and deliberately not moved, so the two radii are now equal. The vent has the same 0.45 m mismatch a pearl now has — after the change both objects are wrong by the same small amount instead of the pearl being the outlier — and raising it is *measured* to be free of balance effect (4.5 and 5.0 give 33/32 pearls home and identical max loads, because `Autopilot.ventRoute` scales its own tolerance with it and the two cancel). It stays at 4 because a vent is **consumed on contact**, so extra reach makes it easier to burn your lifeline brushing past on a full breath. "A vent is more forgiving than a pearl" is now true of the *target* — a vent is drawn 2.4 m across against a pearl's 1.2 m — rather than of the radius, and is asserted as `≥` so the pearl can never overtake it. |
| **The GI composite onto `mainSurface` is MULTIPLICATIVE** *(settled 2026-08-11; presentation only, no rule changes)* | Not a design change — logged here because two agents read the same jar and reached opposite conclusions, because §16-style open questions are expensive when each new agent rediscovers them, and because at least one reverted design (`d6faaa5`'s rim light) rests on the answer. Settled by decompiling `pulse-engine-0.13.0.jar`. **The multiply:** `GlobalIlluminationSystem.onUpdate` — *not* `onCreate`, which is why disassembling the surface-setup path alone misses it — constructs `MultiplyEffect("gi_blend_effect", 15, "gi_light_final", minReflectance)` (descriptor `(Ljava/lang/String;ILjava/lang/String;F)V`) and passes it to `Surface.addPostProcessingEffect` on `gfx.getSurface(targetSurface)`, with `targetSurface` initialised to the literal `"main"` in the system's constructor. `MultiplyEffect.applyEffect` binds `tex0` from `textures.get(0)` — its own input, i.e. `mainSurface` — and `tex1` from the `gi_light_final` surface, and `shaders/effects/texture_multiply_blend.frag` is `fragColor = vec4(c0.rgb * c1.rgb, c0.a)` under `if (length(c0.rgb) < minReflectance) c0.rgb = vec3(minReflectance);`, so the reflectance floor bites on **our albedo**. **The contrary evidence, explained rather than dismissed:** `lighting/global/final.frag:59` really is `fragColor = vec4(base + light, 1.0)`, but that is the light map assembling itself, not the composite — `GiFinal` is a post-processing effect on the `gi_light_final` surface, its `baseTex` is that surface's own texture (the `gi_local_scene` name it is constructed with goes to a different uniform, `localSceneTex`), and no other class in the jar even names `gi_light_final`. Both findings were true of different stages. **Consequences:** albedo really is scaled toward nothing at depth, so an outline cannot be drawn as albedo and `d6faaa5`'s reversal stands; stopping a pearl's albedo down by its own emission (`e45fdbe`) is the right shape of fix; and `DiveRenderer.GI_REFLECTANCE_FLOOR` with `floorBlueForReflectance` is load-bearing rather than defensive. |
| **Abyss `airBurn` 2.5 → 2.2** | The other half of the same fix. Together with the vent it takes the max survivable load at 135 m from 14 to 18, past one pearl. Across seven seeds the abyss now yields 2–7 reachable pearls (was 1–5), with the deepest reachable rising from 121 m to 134 m on the worst seed. The Abyss remains the fastest-burning zone by a wide margin, and its deepest water is still a trap by design. Verified byte-identical Shallows and Kelp sweeps, so the early game is untouched. |
| **Visible depth is capped by the cliff art on wide panels** *(2026-08-12; presentation, but it changes a gameplay constant)* | **This supersedes the "exactly 60 m of water is visible vertically on every display" sentence in the metres-through-the-engine-camera row above, which is no longer true.** The surplus width outside `Tuning.COLUMN_HALF_WIDTH` is *rock*, and the cliff is a single tile, so an uncapped wide panel showed it repeating — four reflections a side at 2.389. The owner, after five failed attempts at the symptom: *"for each aspect ratio we should only ever see exactly one rock cliff on each end of the screen"*, and then, on the first capped build, *"still seems like we use at least two sprites for width at each side"*. `CameraRig.pixelsPerMetre` is now the LARGER of a height fit and a width fit, capped at `Framing.VISIBLE_WIDTH_METRES` = `2 × RockFace.BODY_INNER_HALF_WIDTH`, so exactly one cliff sprite reaches each frame edge and **no body quad is submitted at all**. The cost is paid in DEPTH above the design aspect, which is now **1.6419** — below 16:9. Measured on real framebuffers: 4:3 and 16:10 keep the full 60 m; **16:9 shows 55.42 m**, 2.389 shows 41.24 m, 32:9 shows 27.71 m. How far ahead you can see is still how far ahead you can plan, so this is a real change to the game on a wide panel and the owner took it twice — once when the cliff art made it cost 1.3 m, and again when the art was redrawn on 2026-08-12 and the same rule cost 4.6 m. The alternative offered and declined was drawing the rock 44% larger (`TILE_HEIGHT_METRES` 40 → 57.6 m) to hold 60 m. `FramingTest` pins the cost at under 10%, so a re-bake that halved the cliff's reach reddens rather than quietly taking another 12 m of sight-line. |
| **Pearls no longer brighten with depth; the torch is the deep's light source** *(2026-08-12)* | **This supersedes §7's "in the darkest zone, where pearls are the only light" and the §11 zone table's "Pearls are the only light source" for the Abyss.** Pearl emission ran a five-anchor ramp, 0.6 in the Shallows to 4.0 in the Abyss, so a pearl brightened at exactly the rate the water darkened and was therefore *always* equally visible — which made the torch scenery. The owner: *"remove the gradual increase of pearl brightness by depth. I'd rather like the diver to have to find them using their flashlight."* Flattening it to the Shallows' own 0.6 was **not enough, for a structural reason worth recording**: a pearl's emitter sits inside its own drawn disc and `radius = 0` removes the distance term, so a pearl's body always receives a flat shelf of its own emission — at 0.6 that shelf swamped anything the beam added, and on a capture at 85 m *"the pearls aren't affected by the light at all. They should be."* Emission is now a flat **0.12**, a marker glow rather than a light source. The torch moved the other way: it used to *dim* 40% in the Abyss (2.0 → 1.2) specifically so pearls would read as comparatively brighter, and it now RISES with depth, derived from `1 − ambientGreen(d)/ambientGreen(0)` so that it replaces exactly the daylight that is gone (2.0 at the surface, unchanged, to 6.0 in the Abyss). **Abyss torch-to-pearl ratio: 0.3× before, 50× after.** The anglerfish is unaffected — the lure reads the same `pearlIntensity()` a pearl does, so it is still indistinguishable by light and the tell is still motion — but this makes §1.1 of the outstanding-work spec live: with the deep now genuinely dark, any glowing decorative scenery must be a visibly different colour from the pearls or the trap stops working. |
| **The god rays are removed** *(2026-08-17; presentation only, no rule changes)* | **This supersedes §11's "shafts of light near the surface".** The owner, looking at a build: *"Please remove the god rays."* No rule, economy or control changes — the shafts were albedo strips on `mainSurface`, never lights and never occluders, and nothing in `dive/` ever knew about them. Deleted: `render/LightShafts.kt`, `render/ShaftRenderer.kt`, `shaders/godrays.frag`, `shaders/godrays.vert`, their two test classes, and the `EPT_SHAFT_PHASE` capture pin. **What deliberately did NOT go with them, because each was attributed to the shafts and is not theirs:** (a) `DiveLighting.daylightByZone`, the `ambientGreen(d)/ambientGreen(0)` identity — it was the shafts' depth ramp *and* is the torch's, so the "no daylight in the Abyss" guard rail is unchanged and `DiveLightingTest` is now its only enforcement rather than its second; (b) `render/OpaqueWater.kt` — the alpha erosion it repairs is the ENGINE's blend function (`glBlendFuncSeparate` appears nowhere in the jar), and the rays were only the arrangement in which it became *visible*; (c) `Sky.BOTTOM_DEPTH` — deepening it again would put opaque sunset back behind eroded alpha; (d) the renderer add-order rule the shafts taught twice, at the cost of the sea vanishing from 0–8.2 m and every pearl above 50 m being erased — `SurfaceRendererOrderTest` now asserts it between the sea and the pearls, which is the pair that remains. The general form stands: **anything that is part of the world is attached before anything that draws in front of it.** |
| **A pre-run briefing, and control prompts that name the attached hardware** *(2026-08-22; presentation only, no rule changes)* | Nothing on screen told a player how to play: the PLAYING HUD draws five kinds of text and not one named a control, and the attract screen said only `PRESS START`. Worse, the three strings that *did* name buttons were hard-coded literals while the gamepad map is config-driven, so a rebind made the screen lie — and two of them named a keyboard key *and* a gamepad button in one breath. `RunLifecycle` gains a sixth state, **BRIEFING**, between IDLE and PLAYING: five seconds showing the three controls plus the one rule the game never otherwise states (*surface to bank*), skippable after 0.75 s and auto-starting so an abandoned briefing cannot strand the cabinet. **A retry from RUN_OVER does not re-brief** — the briefing is for the person who just walked up, and a queue is what the dwell protects. A permanent one-line control legend sits top-right for the whole run. Two non-obvious constraints came out of building it, both recorded in the design doc: the briefing's auto-start fires `justStarted` on a frame with *no press*, and `LifecycleInputEdges.firedPadId` is nulled every frame — so without a latch on IDLE → BRIEFING the common path would bind gameplay to slot 0, reproducing the "startable, unplayable run" `GamepadScan` exists to prevent; and the legend cannot go bottom-left, because the camera's lag bound plus `HELD_OFFSET_METRES` lets the held count reach the bottom margin. Content is deliberately *controls plus one rule* — air, the anglerfish and the point-of-no-return teach themselves, and everything on that screen costs reading time in front of a queue. |
| **The game is playable entirely on a controller, and the printed legend names the pad in front of the player** *(2026-08-30)* | **This supersedes §5's "Pause and exit are keyboard-only so a stuck encoder button can never close the cabinet."** **DEPLOYMENT TARGET CORRECTION, added the same day this entry was written:** the owner then clarified that this build does **not** need to run on an arcade cabinet — it runs on a standard Mac with a controller attached. This entry was drafted before that was known and argues several points primarily from arcade-encoder failure modes. Those arguments are left standing because they are true and because the booth framing is the rest of this document's premise, but the WEIGHTING below is wrong in two specific places, flagged inline: the D-pad's real justification is simply that a controller's D-pad must work, and the two-button exit hold is belt-and-braces rather than a necessity. Nothing built is invalidated by the correction; only the reasoning is over-weighted. The owner asked for the game to be playable entirely on a controller, plus a second question that turned out to be the same subject from the other end — *a PS5 pad is connected and the screen still says A*. Tracing the whole input surface first was worth it: swim, kick, bleed, start, restart, confirm and initials entry were **already** fully pad-reachable, so only two of the nine keyboard read sites had no gamepad equivalent — and the gap that actually mattered was a third one that does not appear in that list at all. **The D-pad was read nowhere in this project.** `readInput` took `GamepadAxis.LEFT_X`/`LEFT_Y` and nothing else; `readInitialsCycle` took `LEFT_Y`; `GamepadButton.DPAD_UP`/`DOWN`/`LEFT`/`RIGHT` all exist in the engine and appeared in no source file. **On a console pad — the actual deployment target — that is a plain functional gap: half the directional hardware in the player's hands did nothing, and on a Mac with a DualSense that alone is reason enough to fix it.** The arcade case below is now SECONDARY (see the deployment-target correction at the head of this entry), though it is what made the gap look urgent while it was still believed to be the target: **a generic USB arcade encoder's joystick commonly enumerates as a hat rather than as analog axes**, and in that case the cabinet comes up perfectly — `restartButton` is a button, and buttons work — while the diver never moves. That is the same "startable, unplayable run" class of failure `selectGameplayPad` exists to prevent, arriving through a different door, and it would present at the stand as *the game is broken* with nothing at all in the log. Both readers now resolve through a pure `render/PadAxis.kt`, precedence analog → D-pad → keyboard, first live source wins rather than a sum, with a live stick passing through **at full magnitude** (the stick is proportional and the swim model reads it that way; quantising it would silently turn every pad in the world into a D-pad) and **both directions at once cancelling to zero deterministically** — a hat cannot physically report left and right together, but a stuck contact on an encoder can, and a diver swimming sideways for the rest of the day is the unattended-cabinet failure this project keeps designing against. **Pause and exit on the pad OVERRIDE the original §5 reasoning at the owner's direction; they do not refute it.** All three of that reasoning's arguments were re-checked rather than discarded: *"there is no third button to spend"* is true of the arcade encoder and false of a console pad (the new keys default to BACK and the two bumpers, four inputs the cabinet's own encoder is unlikely even to have, so the booth configuration is unchanged in practice); *"pause is the one lifecycle action a player benefits from abusing"* is still true and is **accepted, not solved** — pause sits on a system button away from kick and bleed, the collision check refuses to let it be configured onto either, and the rest is a staffed booth; *"a phantom press must never reach an action this final"* **is** solved, by making exit an AND across `exitButtonA` and `exitButtonB` held together **on the same pad** for `EXIT_HOLD_SECONDS` (1.5 s), so one stuck contact can no longer end the day and `RunLifecycle` needed no change at all — **on the corrected target this is belt-and-braces rather than load-bearing** (a mis-fired exit on a Mac costs a relaunch, not a day of the stand), and it was kept because it is already built, already tested, and costs the player nothing — its `exitHeldSeconds = if (exitHeld) exitHeldSeconds + dt else 0f` already resets the hold the moment either button is released. Pause takes the same protection from the other side: it runs through a **second `LifecycleInputEdges` instance**, fed `pauseButton` from every connected pad plus `ESC`'s own `wasClicked`, so a jammed button emits no edges, cannot mask the keyboard's perfectly good edge behind an OR that is already true, and is counted on the booth status line as stuck. **The constraint that shaped the exit combination, and it is a mechanism rather than a preference: it cannot include `pauseButton`.** Pause is edge-detected, and while PAUSED a pause edge means *resume* — so pressing it to begin an exit hold would leave the pause screen before the hold ever accumulated. The obvious design does not function. `exitButtonA == exitButtonB` is the one new collision that silently *removes* a protection (the AND collapses to `x && x` with the config file still looking like it names two buttons) and is logged at WARN, compared on `.code` like every other check here so an alias cannot evade it; `pauseButton` on `kickButton`/`bleedButton` is logged for the same live-at-the-same-time reason, and nothing else is, because a false alarm on the booth status line is worse than no alarm. **The legend is family-aware; the BINDINGS ARE NOT CHANGED.** `ControlHints.labelFor` used to record "there is no source for the physical legend" as an accepted limit — there is one: `InputImpl` filters `glfwJoystickPresent(i) && glfwJoystickIsGamepad(i)` and constructs `Gamepad(i)`, so `Gamepad.id` *is* the raw GLFW joystick index (verified from the 0.13.0 bytecode), which makes `glfwGetGamepadName(pad.id)` directly callable; measured on the owner's machine it returns exactly `"DualSense Wireless Controller"`, and that literal is a test case rather than a plausible-looking invention. Nothing was ever misbound: on a DualSense `GamepadButton.A` and `CROSS` **share code 0**, so `kickButton = A` already bound the physical Cross button and only the printed string was wrong. Substring matching on the device name, not a table of exact names (the same pad reports different strings per OS, driver and transport), with GENERIC as the fallback — an unrecognised booth encoder gets today's screens byte for byte, because a wrong guess is worse than no guess: a player told to press CROSS on an encoder that has no Cross button is stuck, while a player told to press `A` is where they were last week. **The font constraint decides the vocabulary.** The real PlayStation face symbols are U+2715, U+25CB, U+25A1 and U+25B3, all above the default font's baked U+0020..U+011F, and a code point outside that atlas draws as *nothing at all* — no glyph, no x-advance, silently — so a "correct" `CROSS kick` would have shipped as ` kick` with nothing anywhere saying so. The labels are ASCII words, and `ControlHintsTest.every family legend is an ASCII word, never a PlayStation glyph` names those four code points explicitly, so a later tidy-up to the real symbols is a failed build rather than an empty legend on a cabinet in front of a queue. |
| **Kick is a stroke, not a throttle** *(2026-08-30)* | **This supersedes §5's "A (hold) — Kick — 3× speed, 3× air burn."** The owner: *"Make the kick only give one boost per click. It should not be continuous as it is today when holding down the kick button. It should also slightly increase the anim speed of the spritesheet during the kick."* A rising edge on the button now opens a fixed window during which today's `KICK_SPEED_MULT` and `KICK_AIR_MULT` (both 3×) apply exactly as they always did, and when it closes both revert to 1× **even if the button is still down**. **A one-shot velocity impulse was rejected, and the reason is §4.** `vx += KICK_IMPULSE` is the more literal reading of "one boost per click" and it is the wrong model here: it bypasses the drag and response system the whole feel is built on. `updateMovement` eases velocity toward a *target* at a rate set by `Buoyancy.responseRate(heldMass)`, and that is precisely what makes a laden diver sluggish — the design's central mechanic. An additive Δv would give a diver hauling a full load the same instant snap as an empty one, deleting the risk/reward the economy rests on. Boosting the target keeps every one of those properties: the kick still has to fight mass, still lands slowly on a heavy diver, and **the air cost falls out for free** — 3× burn over a fixed-length window *is* a fixed cost per kick, with no new economy constant invented, and both consumers read one timer advanced once at the top of the tick so a kick can never cost air on a tick it did not propel. **The edge is detected inside `DiveSim`, and `DiveInput.kick` stays a level reading.** That is this codebase's one convention for buttons rather than a preference — the engine's `Gamepad` exposes only `isPressed`/`getAxis` and has no `wasClicked`, so `RunLifecycle`, `InitialsEntry` and `LifecycleInputEdges` all already edge their own input — and doing it in the sim keeps `dive/` engine-free and makes the behaviour bit-identical for keyboard `Z` and for the cabinet's encoder. **Numbers: a 0.35 s burst** (a little over one time constant of the velocity ramp at zero mass, `1 / Buoyancy.responseRate(0f)` = 0.286 s, so a single kick reaches ~71% of the boosted target and then decays — the diver surges and glides rather than snapping between two speeds), **a 0.45 s cooldown measured from the burst's START rather than from its end**, and a **1.5× animation multiplier** on the fin loop. Measuring the cooldown from the start is what makes the duty cycle a fixed, checkable fraction (0.35 / 0.45 = 78%) however the player mashes, and **the cooldown is the whole defence against mashing approximating the old hold-to-fly behaviour**: 0.1 s of every window is a dead gap in which no boost is possible at all. **An explicit per-axis speed ceiling** was added at the owner's request and clamps nothing reachable today — `response` is in (0, 1), so each axis stays between its previous value and its target and can never overshoot, and both targets peak at zero mass with a full stick and the boost applied, which is exactly what the ceilings are. It is a guarantee against a future edit, not a change. **Per axis, never on the combined magnitude**, and that distinction is load-bearing: a boosted diagonal is (18, 33), magnitude 37.6, so a magnitude clamp at 33 would make diagonal swimming slower than it is today — an unrequested gameplay change, when the ceiling is meant to be invisible. The acceptance criterion was that every existing `dive/` test passes unmodified, and **exactly one legitimately changed**: `DiveSimTest.hitting the column edge kills horizontal momentum` had its time budget raised from 6 s to 10 s because it had implicitly encoded hold semantics — 6 s of continuously boosted lateral swimming covered the 40 m to the wall, whereas one burst plus unboosted cruising at 6 m/s covers about 38.5 m and stops just short of the fixture it exists to hit. Its assertions are untouched. |
| **The player-facing title is English: "ONE MORE BREATH"** *(2026-08-30; presentation only, no rule changes)* | The booth audience is international and the game should not greet them in Norwegian, so `ScreenText.TITLE` is now `"ONE MORE BREATH"` where it read `"ÉN PUST TIL"`. **PLAYER-FACING ONLY, and deliberately so.** Every Kotlin identifier, the main class `EnPustTilKt`, the compiled `GAME_NAME` and `application.cfg`'s `gameName` still say EnPustTil, because CLAUDE.md's booth capture, in-water screenshot and recovery procedures are keyed on `pgrep -f EnPustTilKt` and the event is days away — a rename that reaches the class name silently breaks every one of those procedures at the moment they are most needed. **The trap for whoever finishes the rename later, which is not obvious from either file:** `GAME_NAME` and `application.cfg`'s `gameName` are compared against each other at startup by `EnPustTil.configFileHealthWarning` — that comparison is the cheap check that the config file loaded completely — so the two must change in **lockstep**, or the cabinet logs a config-corruption warning at every boot with nothing whatsoever wrong. (`AttractScreenTest` still sweeps the literal `"ÉN PUST TIL"`; it is a font-range probe for the accented Norwegian range that the practical rule in `EnPustTil.kt`'s font doc depends on, not the game's name any more.) |

### Platform findings that constrain implementation

- **`engine.window.width/height` returns physical framebuffer pixels**, not the logical size in `application.cfg` (2400×1800 on a Retina Mac, not 1200×900). All rendering scale must derive from the actual surface **height**, never its width and never a pixel count. The world no longer consumes it at all — world sizes are metres (`render/Framing.kt`) and only `render/CameraRig.kt` turns a metre into a pixel; screen-space code should read `surface.config.width/height`, which is what that surface's own projection was built from.
- **`Surface.drawQuad()` and `drawLine()` render nothing on macOS/Apple Silicon**, silently and with no GL error. The cause is NOT the shader `#version`, as first believed: `QuadRenderer`/`LineRenderer` bind an `in uint` shader attribute as `GL_FLOAT`, which is undefined behaviour and yields alpha 0. See `docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md`. Use `Surface.fillRect()` in `render/Draw.kt`, which draws a tinted blank texture. The `GlobalIlluminationSystem` path is unaffected — it draws through `GiSceneRenderer`, which works.
