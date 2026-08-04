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
| Abyss | 120 m+ | 400 | 16 | ×2.5 | Near-total dark. Pearls are the only light source. One of them is hunting you. |

Zones must differ in **how you move**, not only in value and colour. A zone that changes
only a number is a reskin.

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

---

## 5. Controls

Designed for **joystick + two arcade buttons** on a USB encoder.

| Input | Action |
|---|---|
| Stick | Swim, 8-way with acceleration and water drag |
| **A** (hold) | **Kick** — 3× speed, 3× air burn. Descent tool and escape tool. |
| **B** (hold) | **Bleed ballast** — pearls stream out continuously while held |

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

Free-diver in silhouette. Serious and beautiful rather than comic: shafts of light near
the surface, deep blue falling to near-black, bioluminescence in the dark.

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

### Platform findings that constrain implementation

- **`engine.window.width/height` returns physical framebuffer pixels**, not the logical size in `application.cfg` (2400×1800 on a Retina Mac, not 1200×900). All rendering scale must derive from the actual surface height. See `render/Viewport.kt`.
- **`Surface.drawQuad()` and `drawLine()` render nothing on macOS/Apple Silicon**, silently and with no GL error. Their vertex shaders are `#version 150 core`; the ones that work (`drawText`, `drawTexture`) are `#version 330 core`. Use `Surface.fillRect()` in `render/Draw.kt`, which draws a tinted blank texture. **This has not yet been verified for the `GlobalIlluminationSystem` / `Lamp` path used in the lighting task.**
