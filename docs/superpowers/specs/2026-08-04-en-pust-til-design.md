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
| **Point-of-no-return marker made zone-aware** (§12) | The marker priced the whole climb at the CURRENT zone's air burn, so from the Abyss it charged all 120 m at 2.5 instead of the 2.0/1.6/1.2/1.0 the climb actually crosses — a ~70% overstate. Measured against flown dives: of 27 sampled (depth, air, mass) combinations it said "you cannot get home" in **20 cases where the diver demonstrably could**, ordering players out of the deep a whole zone early. Now integrated across zones in `dive/Ascent.kt`, plus one velocity-ramp spin-up so it can never err optimistic. All 27 cases now agree. |
| **Deepest vent moved into the lower Trench** (§6b) | The Abyss was decoration. Flying the vent ladder arrived at 135 m with 21.6 s of air, and the heaviest load that could climb home from there was 14 mass — less than the 16 of a single abyss pearl. Six of fourteen abyss pearls were permanently unreachable, and on some seeds only one could be brought home at all. The last vent before the drop is now a staging post, not just another rung. **No vent in the Abyss** — that rule is unchanged. |
| **The world is rendered in METRES through the engine camera** (§12, presentation only) | Not a design change — recorded here because it changes what §10's "authoring uses the Pulse Engine scene editor" can mean, and because it makes one presentation guarantee explicit that the design always assumed. `render/Viewport.kt` is gone; it is `render/Framing.kt` and owns no transform, and `render/CameraRig.kt` is the sole writer of `engine.gfx.mainCamera`. **The aspect-ratio guarantee is now the engine's**: exactly 60 m of water is visible vertically on every display, and a wider panel shows more water sideways — never less water down. How far ahead you can see is how far ahead you can plan, so that is a gameplay constant and it is now checked on the real framebuffer (`CameraInvariants`, under `EPT_DEV`) rather than assumed. **Content generation stays procedural and seeded**: §10's scene-editor sentence continues to apply to the diver's visual representation, lights, particles and effects, and **not** to pearl, vent or anglerfish placement, which is generated from `dailySeed` so that everyone at the booth faces the identical column and day two remains a one-line config change. |
| **`PEARL_PICKUP_RADIUS` 2.5 → 4** | The hitbox had stopped agreeing with the diver you can see. Pickup is a circle about his centre; the sprite is a 2.95 × 9 m rectangle about the same point, so at 2.5 m there was a **1.95 m dead band at his head and at his fins** — and it sat on the swim axis, exactly where the player aims. A pearl visibly touching his mask did not collect, which reads as a bug rather than as a rule. Measured off the committed sheet (alpha > 16, all 41 frames): crown at 4.45 m, chin 3.56 m, widest half-extent 1.43 m. **3.5 was tried first and disproved** — it does not reach the chin, so the reported case survives it untouched. 4 puts the boundary on the head and leaves 0.45 m of crown and snorkel. **4.5 was rejected**: it clears the silhouette outright but takes lateral overreach from 2.57 m to 3.07 m past his flank and the accidental sweep-up of a neighbouring pearl from 22% to 27%, and bought exactly one more reachable abyss pearl. An oriented capsule matching the sprite was rejected outright — it needs the diver's *heading*, which is presentation state owned by `render/`, and `dive/` stays pure. **Balance cost, flown with `Autopilot` rather than argued:** abyss pearls brought home across seven seeds 30 → 33 of 98; max survivable load 24 → 26 at 130 m, 18 → 20 at 135 m, 9 → 12 at 140 m. The *shape* is unchanged, which is the property that matters — an abyss pearl is 16 mass, so 135 m stays carryable and 140 m stays a trap, and the worst seed still lands 2 pearls rather than being rescued by the change. `AscentTest` gained `no abyss pearl can be carried home from 140 metres`, which pins the NO that nothing had pinned. |
| **`AIR_POCKET_PICKUP_RADIUS` left at 4** | Considered and deliberately not moved, so the two radii are now equal. The vent has the same 0.45 m mismatch a pearl now has — after the change both objects are wrong by the same small amount instead of the pearl being the outlier — and raising it is *measured* to be free of balance effect (4.5 and 5.0 give 33/32 pearls home and identical max loads, because `Autopilot.ventRoute` scales its own tolerance with it and the two cancel). It stays at 4 because a vent is **consumed on contact**, so extra reach makes it easier to burn your lifeline brushing past on a full breath. "A vent is more forgiving than a pearl" is now true of the *target* — a vent is drawn 2.4 m across against a pearl's 1.2 m — rather than of the radius, and is asserted as `≥` so the pearl can never overtake it. |
| **The GI composite onto `mainSurface` is MULTIPLICATIVE** *(settled 2026-08-11; presentation only, no rule changes)* | Not a design change — logged here because two agents read the same jar and reached opposite conclusions, because §16-style open questions are expensive when each new agent rediscovers them, and because at least one reverted design (`d6faaa5`'s rim light) rests on the answer. Settled by decompiling `pulse-engine-0.13.0.jar`. **The multiply:** `GlobalIlluminationSystem.onUpdate` — *not* `onCreate`, which is why disassembling the surface-setup path alone misses it — constructs `MultiplyEffect("gi_blend_effect", 15, "gi_light_final", minReflectance)` (descriptor `(Ljava/lang/String;ILjava/lang/String;F)V`) and passes it to `Surface.addPostProcessingEffect` on `gfx.getSurface(targetSurface)`, with `targetSurface` initialised to the literal `"main"` in the system's constructor. `MultiplyEffect.applyEffect` binds `tex0` from `textures.get(0)` — its own input, i.e. `mainSurface` — and `tex1` from the `gi_light_final` surface, and `shaders/effects/texture_multiply_blend.frag` is `fragColor = vec4(c0.rgb * c1.rgb, c0.a)` under `if (length(c0.rgb) < minReflectance) c0.rgb = vec3(minReflectance);`, so the reflectance floor bites on **our albedo**. **The contrary evidence, explained rather than dismissed:** `lighting/global/final.frag:59` really is `fragColor = vec4(base + light, 1.0)`, but that is the light map assembling itself, not the composite — `GiFinal` is a post-processing effect on the `gi_light_final` surface, its `baseTex` is that surface's own texture (the `gi_local_scene` name it is constructed with goes to a different uniform, `localSceneTex`), and no other class in the jar even names `gi_light_final`. Both findings were true of different stages. **Consequences:** albedo really is scaled toward nothing at depth, so an outline cannot be drawn as albedo and `d6faaa5`'s reversal stands; stopping a pearl's albedo down by its own emission (`e45fdbe`) is the right shape of fix; and `DiveRenderer.GI_REFLECTANCE_FLOOR` with `floorBlueForReflectance` is load-bearing rather than defensive. |
| **Abyss `airBurn` 2.5 → 2.2** | The other half of the same fix. Together with the vent it takes the max survivable load at 135 m from 14 to 18, past one pearl. Across seven seeds the abyss now yields 2–7 reachable pearls (was 1–5), with the deepest reachable rising from 121 m to 134 m on the worst seed. The Abyss remains the fastest-burning zone by a wide margin, and its deepest water is still a trap by design. Verified byte-identical Shallows and Kelp sweeps, so the early game is untouched. |
| **Visible depth is capped by the cliff art on wide panels** *(2026-08-12; presentation, but it changes a gameplay constant)* | **This supersedes the "exactly 60 m of water is visible vertically on every display" sentence in the metres-through-the-engine-camera row above, which is no longer true.** The surplus width outside `Tuning.COLUMN_HALF_WIDTH` is *rock*, and the cliff is a single tile, so an uncapped wide panel showed it repeating — four reflections a side at 2.389. The owner, after five failed attempts at the symptom: *"for each aspect ratio we should only ever see exactly one rock cliff on each end of the screen"*, and then, on the first capped build, *"still seems like we use at least two sprites for width at each side"*. `CameraRig.pixelsPerMetre` is now the LARGER of a height fit and a width fit, capped at `Framing.VISIBLE_WIDTH_METRES` = `2 × RockFace.BODY_INNER_HALF_WIDTH`, so exactly one cliff sprite reaches each frame edge and **no body quad is submitted at all**. The cost is paid in DEPTH above the design aspect, which is now **1.6419** — below 16:9. Measured on real framebuffers: 4:3 and 16:10 keep the full 60 m; **16:9 shows 55.42 m**, 2.389 shows 41.24 m, 32:9 shows 27.71 m. How far ahead you can see is still how far ahead you can plan, so this is a real change to the game on a wide panel and the owner took it twice — once when the cliff art made it cost 1.3 m, and again when the art was redrawn on 2026-08-12 and the same rule cost 4.6 m. The alternative offered and declined was drawing the rock 44% larger (`TILE_HEIGHT_METRES` 40 → 57.6 m) to hold 60 m. `FramingTest` pins the cost at under 10%, so a re-bake that halved the cliff's reach reddens rather than quietly taking another 12 m of sight-line. |
| **Pearls no longer brighten with depth; the torch is the deep's light source** *(2026-08-12)* | **This supersedes §7's "in the darkest zone, where pearls are the only light" and the §11 zone table's "Pearls are the only light source" for the Abyss.** Pearl emission ran a five-anchor ramp, 0.6 in the Shallows to 4.0 in the Abyss, so a pearl brightened at exactly the rate the water darkened and was therefore *always* equally visible — which made the torch scenery. The owner: *"remove the gradual increase of pearl brightness by depth. I'd rather like the diver to have to find them using their flashlight."* Flattening it to the Shallows' own 0.6 was **not enough, for a structural reason worth recording**: a pearl's emitter sits inside its own drawn disc and `radius = 0` removes the distance term, so a pearl's body always receives a flat shelf of its own emission — at 0.6 that shelf swamped anything the beam added, and on a capture at 85 m *"the pearls aren't affected by the light at all. They should be."* Emission is now a flat **0.12**, a marker glow rather than a light source. The torch moved the other way: it used to *dim* 40% in the Abyss (2.0 → 1.2) specifically so pearls would read as comparatively brighter, and it now RISES with depth, derived from `1 − ambientGreen(d)/ambientGreen(0)` so that it replaces exactly the daylight that is gone (2.0 at the surface, unchanged, to 6.0 in the Abyss). **Abyss torch-to-pearl ratio: 0.3× before, 50× after.** The anglerfish is unaffected — the lure reads the same `pearlIntensity()` a pearl does, so it is still indistinguishable by light and the tell is still motion — but this makes §1.1 of the outstanding-work spec live: with the deep now genuinely dark, any glowing decorative scenery must be a visibly different colour from the pearls or the trap stops working. |

### Platform findings that constrain implementation

- **`engine.window.width/height` returns physical framebuffer pixels**, not the logical size in `application.cfg` (2400×1800 on a Retina Mac, not 1200×900). All rendering scale must derive from the actual surface **height**, never its width and never a pixel count. The world no longer consumes it at all — world sizes are metres (`render/Framing.kt`) and only `render/CameraRig.kt` turns a metre into a pixel; screen-space code should read `surface.config.width/height`, which is what that surface's own projection was built from.
- **`Surface.drawQuad()` and `drawLine()` render nothing on macOS/Apple Silicon**, silently and with no GL error. The cause is NOT the shader `#version`, as first believed: `QuadRenderer`/`LineRenderer` bind an `in uint` shader attribute as `GL_FLOAT`, which is undefined behaviour and yields alpha 0. See `docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md`. Use `Surface.fillRect()` in `render/Draw.kt`, which draws a tinted blank texture. The `GlobalIlluminationSystem` path is unaffected — it draws through `GiSceneRenderer`, which works.
