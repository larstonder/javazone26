# Fixing the deep: it is too dark, and the lights fight each other

**Status:** PARTLY EXECUTED — this header said "plan only, nothing here has been implemented" long
after that stopped being true. The body itself records the landed changes and one reverted
experiment; read it for which is which, and treat the code as the authority, not this document.
**Reported by the owner, 2026-08-13:** *"It's currently WAY too dark at the darkest levels, and it seems the lights are fighting each other."*

Both halves of that are real and they have DIFFERENT causes, which is why this is a plan
rather than a tuning pass. One of them is a hard engine constraint that no choice of
constants can escape, and one of them was made worse by work landed the same day.

---

## 0. What is established, with citations

Everything in this section was read out of `pulse-engine-0.13.0.jar` or measured. Nothing
here is inference, and none of it should be re-derived by the next person.

**The composite is a MULTIPLY.** Settled 2026-08-11, recorded in the design spec's §17 and
in `CLAUDE.md`. `mainSurface` is multiplied by the light map. So a pixel's brightness is
`albedo x light`, and where the light map is zero the frame is black no matter what the
albedo is.

**Every emitter is also an OCCLUDER, and this is not avoidable.**
`shaders/lighting/global/jfa_seed.frag` is four lines long and decisive:

```glsl
float alpha = texture(sceneTex, uv).a;
ivec2 externalSeed = (alpha > 0.5) ? ivec2(gl_FragCoord.xy) : ivec2(-1);
```

Anything in `gi_local_scene` with **alpha > 0.5** seeds the jump-flood, and therefore the
SDF. `radiance_cascades.frag`'s `raymarch` marches that SDF and returns
`HitResult(rayPos * invSdfRes, space)` the moment `stepSize <= MIN_STEP`; `sampleScene`
then returns THAT surface's colour. A ray that meets a mote stops at the mote and never
reaches the pearl or the torch behind it.

`drawLight` draws `LightEmitter`'s disc, whose alpha runs ~1 at the centre to 0 at the rim.
The inner region where alpha > 0.5 is an occluder. **So a GI light in this engine is a
lamp with an opaque core, and the two cannot be separated:** an emitter whose alpha never
exceeded 0.5 would not seed the SDF, no ray would ever hit it, and it would emit nothing at
all. Being visible to the solver IS being an occluder.

**A light's reach is linear in its QUAD SIZE and independent of its intensity.**
`sampleScene` applies the distance term only `if (radius > 0.0)`, and every `drawLight`
call in `DiveLighting` passes `radius = 0f`. So intensity scales how bright a light is
where it is seen, and the size of its quad is what decides how much of the frame can see
it. This is already documented at `DiveLighting.torchIntensityFor`, and it is the reason
the torch feeling short-ranged was not fixed by making it brighter.

**The Abyss ambient is zero.** `DiveLighting.ambientRed/Green/Blue` end
`(0.0, 0.0, 0.003)`. Below the Trench there is no light in the scene except what the torch,
the pearls and the motes emit.

**The colour grade sits AFTER the multiply.**
`ColorGradingEffect(toneMapper = ACES, vignette = 0.25f, exposure = 1.1f, contrast = 1.3f)`.
A contrast of 1.3 applied to an already near-black frame pushes more of it to true black,
and the vignette darkens the frame edges by a further quarter.

**Three things landed on 2026-08-12/13 that all pushed the same direction**, and the
complaint arrived after them:
- pearl emission cut from a 0.6-to-4.0 depth ramp to a flat 0.12 (a 33x cut in the Abyss);
- GI resolution raised from quarter to half res, which sharpens the SDF and therefore makes
  occluders block *more precisely* rather than smearing;
- 20-90 mote emitters added to the water, each with an opaque core.

---

## 1. The two symptoms, and the ranked candidates for each

Each candidate is written so it can be **falsified by one measurement**. Do not fix
anything before the measurement that identifies it; the history of this file's subject
matter is four consecutive fixes that each relocated a symptom.

### Symptom A — "the lights are fighting each other"

**A1. Emitter cores intercept every other light. VERIFIED MECHANISM, UNVERIFIED MAGNITUDE.**
This is §0's engine constraint. The torch's rays terminate on the first mote or pearl core
they meet, so a field of emitters between the diver and anything else eats the beam. The
mote field added on 2026-08-13 put 20-90 new occluders into exactly the water the torch has
to light, which is the strongest single suspect for the symptom appearing *now*.
*Falsify by:* set `Motes.GLOW_IN` high enough that nothing glows, capture the same pinned
frame, and compare frame mean and the beam's reach against a control pair.

**A2. The pearls are occluders too, and always were.** 14 of them, cores ~1.2 m. Same
mechanism, smaller count, and present long before the complaint — so this is a contributor,
not the trigger.
*Falsify by:* the same capture with `drawPearlLights` skipped.

**A3. Cascade merging across a dense occluder field.** `raymarch` returns
`TERMINATED` (`a = -1`) when it runs out of steps, and the shader comments say that value
exists "to prevent merging and sun/sky radiance for terminated rays". A field of small
occluders raises the number of terminated rays, and terminated rays contribute nothing at
all rather than contributing the light behind them.
*Falsify by:* whether A1's measurement scales with the mote COUNT rather than with their
total emitted intensity. If halving the count helps more than halving the intensity, this
is A3 rather than plain A1.

### Symptom B — "WAY too dark at the darkest levels"

**B1. There is nothing to multiply. HIGHEST PRIOR.** Abyss ambient is `(0, 0, 0.003)` and
the composite is a multiply, so every pixel outside a light's reach is black by
construction, not by tuning. The Abyss *should* be dark — but "dark" and "no signal at
all" are different, and the design asks for silhouettes against near-black, which requires
a floor above zero.

**B2. The torch cannot reach far, and its intensity is the wrong dial.** Per §0, reach is
linear in quad size with `radius = 0`. The torch head is `DIVER_LIGHT_SIZE_METRES` = 1.2 m,
deliberately small because at body scale you see the emitter instead of the light. Raising
the intensity to 6.0 in the Abyss (2026-08-12) made the lit region brighter without making
it bigger, which is consistent with the owner's report that it is still too dark.

**B3. The colour grade is tuned for a frame that no longer exists.** Contrast 1.3 and
vignette 0.25 predate every change above.
*Falsify by:* capture the same frame with contrast 1.0 and vignette 0.

**B4. `minReflectance` floors the ALBEDO, not the light.** Already understood
(`DiveRenderer.GI_REFLECTANCE_FLOOR`), and it means the floor cannot rescue a black light
map. Listed so nobody re-investigates it as a lever: it is not one.

---

## 2. Step 0, and it blocks everything else

**Add a dev-only depth pin.** There is no way to put the diver at 140 m without playing the
cabinet, and *every* visual claim about the deep in the last two days has been blocked on
exactly this — including three the assistant could not make and had to hand back to the
owner. `EPT_WAVE_PHASE`, `EPT_SHAFT_PHASE` and `EPT_MOTE_PHASE` are the precedent: one
`getenv` at startup, inert at the booth.

Suggested: `EPT_DEPTH=140` places the diver there in IDLE and holds him, so the attract
screen becomes a fixed deep-water test rig. It must not be reachable without the env var,
and it must not touch `dive/`'s rules — `RunLifecycle` and the sim stay honest.

This is the highest-value item on the page. It is worth doing before any lighting change,
because without it every step below is unmeasurable.

---

## 2b. THE BASELINE, MEASURED — step 0 is done and it changed the ranking

`EPT_DEPTH` landed, and the first capture it made possible settles several of §1's
candidates immediately. Frame: 140 m, `EPT_DEPTH=140 EPT_WAVE_PHASE=12 EPT_SHAFT_PHASE=30
EPT_MOTE_PHASE=45`, 3190x1845 of game window.

```
mean luminance          3.86 / 255
pixels below  2/255        95.2%
pixels below 10/255        95.3%
lit pixels (>25)            4.50%
   of which blue (motes)   19.1%
   of which amber (pearls)  7.1%
brightest mote            213.5
brightest pearl           194.2
```

**B1 is confirmed and is the headline: 95% of the Abyss is at or below 2/255.** That is not
"dark", it is black — three quarters of a single 8-bit level. The owner's "WAY too dark" is
if anything an understatement, and no contrast or vignette change can recover a signal that
is not there. B3 drops down the list accordingly: the grade is not what is destroying the
image, it is being handed nothing.

**A NEW FINDING, AND IT INVERTS A DESIGN INTENT: the motes are the brightest thing in the
Abyss.** Brightest mote 213.5 against the brightest pearl's 194.2, and motes are 19.1% of all
lit pixels against the pearls' 7.1%. The field reads as a starfield rather than as suspended
matter, and it out-shines the object the player is actually hunting.

**This also exposes a hole in `MotesTest`.** `a glowing mote is dimmer than a pearl, which is
dimmer than the torch` passes — and is worthless for this — because it compares EMITTED
INTENSITY, while what reaches the screen is composited very differently for the two: a mote
is drawn on its own surface and escapes the GI multiply entirely, whereas a pearl's albedo is
multiplied by a light map that is ~0 down here. The two quantities are not comparable and the
test silently assumed they were. Any fix must add an assertion about the COMPOSITED result,
which means it needs a capture and cannot live in the unit suite — or the mote surface has to
stop being exempt from the thing that darkens everything else.

**Revised order of work:**
1. ~~Bring the motes down~~ **DONE** — see §2c;
2. then B1, the ambient floor, which is what makes the water exist at all;
3. then A1, the emitter-occlusion measurement, which is now a *comparison* against this
   baseline rather than an open question;
4. reach (B2) and the grade (B3) last.

### A correction to the numbers above

The "brightest mote 213.5" and the mote/pearl pixel shares in the baseline block **counted the
attract screen's HUD text as motes** — 7026 pale-blue pixels, at byte-identical positions in
every capture, on a surface neither change touches. The conclusion (the motes dominated the
Abyss) survives and is if anything understated, but any figure derived from a naive
`blue > red` test on these captures is contaminated. Use a SATURATION test: the motes are
`(0.25, 0.62, 1.0)`, so `b > r * 2.5` holds for them and fails for the HUD's `(169, 209, 221)`.

---

## 2c. Step 1 is done: the motes are on `main`

The exemption is removed. The per-zone alpha table went with it — it ramped 0.18 to 0.85 so the
motes were strongest where the water is blackest, which was coherent only while they escaped
the multiply. Applied to an albedo GI is about to scale toward zero it is backwards, so there
is one flat `MOTE_ALPHA` and the depth response is the light map's.

Same pinned frame, same seed, same phases, saturation-tested:

```
                    own surface     on main
mote pixels               47811         710      (-99%)
pearl pixels              20296       21785
brightest mote            181.3       181.3      (now below the pearls' 194.2)
mean luminance             3.86        3.46
pixels below 2/255        95.2%       96.1%
```

The starfield is gone and the pearls are unambiguously the dominant objects again. The
brightest mote is unchanged because the ones that remain are the glowing quarter, which light
their own bodies through GI — which is the intended behaviour, not a leftover.

**And it did nothing for the darkness, which was expected and is worth stating plainly:** the
frame went from 95.2% to 96.1% below 2/255. Removing something that was too bright cannot make
a black frame legible. B1 is next and is now unambiguously the main event.

---

## 2d. Step 2 was ATTEMPTED AND REVERTED: the ambient floor cannot work

An ambient floor was built (applied after the blend, so `ambientGreen` kept its single meaning
and neither `shaftDaylightByZone`'s Abyss zero nor the torch ramp moved) and it changed the
frame by **nothing measurable**: mean 3.46 both ways, 96.1% below 2/255 both ways. Raising it
8x — an Abyss ambient blue of 0.72, eight times the Trench's — moved 7% of the frame off pure
black, to 2.6/255 at p90. It was reverted rather than shipped, because a documented no-op is
worse than an absent feature.

**Why, and this is the arithmetic the whole problem turns on.** The frame is
`albedo x ambient`, and BOTH are ramps that fall to near-zero, so the deep falls off
QUADRATICALLY. Then the colour grade, read out of `shaders/effects/color_grading.frag`:

```glsl
color.rgb *= pow(2.0, exposure) - 1.0;                                    // 1.1 -> x1.1435
color.rgb = ((color.rgb - 0.5) * max(1.0 + 0.05*(contrast - 1), 0)) + 0.5; // 1.3 -> x1.015
```

**`contrast` is scaled by 0.05 inside the shader**, so our 1.3 is a multiplier of 1.015 and not
1.3 — but that is still enough to push anything below **0.00739** negative, where ACES clamps
it to zero. A hard black clamp, not a curve.

> **CORRECTION (2026-08-13, after step 2).** The model below is WRONG, and it was presented
> here — and to the owner, twice — as "validated against the captures". It multiplies the DRAW
> values and grades that, omitting the sRGB round trip: `setDrawColor` packs an sRGB byte which
> `texture.vert` decodes with the ~2.4 power curve, so the multiply happens in LINEAR space and
> a draw blue of 0.18 is a linear 0.027. It agreed with the Shallows capture by coincidence of
> range, which is exactly how a wrong model survives a single check.
>
> The corrected chain is documented in `DiveRenderer.zoneRed`'s doc and is validated against
> three captures spanning 0 to 87/255. Under it, the grade's `contrast` term near black is a
> SUBTRACTION OF A CONSTANT 0.00739 rather than a ramp, so the deep water is a hard clamp.
>
> **The conclusion below survives its own broken arithmetic** — no ambient makes the Abyss water
> read without deleting the gradient — but the numbers in the table are not to be reused.

Modelled end to end and validated against the captures (the Shallows figure below is what a
correctly-lit surface frame measures):

```
zone        albedo  ambient   product   out/255
Shallows     0.520    0.680   0.35360    138.55
Kelp         0.360    0.440   0.15840     66.50
Twilight     0.220    0.240   0.05280     12.66
Trench       0.120    0.090   0.01080      0.38
Abyss        0.035    0.003   0.00011      0.00
```

The Trench is ALREADY black (0.38/255) and the Abyss is four orders of magnitude below the
Shallows. For the Abyss water to read at even 8/255 the product must reach 0.041 — which at the
current albedo needs an ambient of 1.46, sixteen times the Trench's. Keeping both ramps
monotone and lifting the bottom as far as is defensible (albedo 0.11, ambient 0.10) still only
reaches 0.41/255.

**CONCLUSION: the water cannot be what makes the deep legible.** There is no assignment of
ambient that makes the Abyss visible and still leaves it looking deeper than the Twilight. Any
value large enough to work makes the Abyss look like the Twilight, which is the design's
"near-total dark" (§11, and the §11 zone table) deleted rather than tuned.

So B1 is dead as stated, and what remains is the correct answer anyway: **the deep should be
legible because the TORCH lights things in it** — the rock, the diver, the pearls — not because
the water glows. That is B2, and it is now the main event.

**If the owner does want the water itself to have presence in the deep, that is a DESIGN
AMENDMENT and not a tuning pass** — it means the Abyss stops being near-total dark — and the
numbers it requires are the table above: an Abyss ambient around 0.2 with an albedo around 0.2,
i.e. the Abyss rendered about as bright as the Twilight is today.

---

## 3. Interventions, cheapest and most reversible first

Each is one dial, with the measurement that decides whether to keep it. Do them **one at a
time**, against a bit-identical control pair with all three phase pins set.

1. **Stop the motes emitting** (`Motes.GLOW_IN` -> none, or revert `drawMoteLights`).
   **DONE, 2026-08-17** — and not for the reason predicted here. The owner did not report the
   deep as "fought"; he looked at a shallow frame and saw that a glowing mote and a plain one
   are two visibly different objects — the emitter wears a halo and a dark occlusion surround
   that the plain dab does not. *"I only want those not affected by GI."* `GLOW_IN`,
   `drawMoteLights`, `moteIntensity`, `MOTE_REACH_METRES` and `MOTE_FRACTION_OF_PEARL` are all
   gone; `Motes`' "THE GLOWING SUBSET" section is the standing record and
   `MotesTest.the lighting pass does not touch the mote field` is the guard. The trade this
   paragraph flagged — the motes stop being a light source, which the owner had asked for — was
   put back to him and he took it.
   Reverses today's change and tests A1 directly. If the deep brightens materially, the
   owner's "fighting" is confirmed as emitter occlusion and the motes go back to being a
   pure overlay — which is what `Motes`' class doc originally argued for, on precisely this
   ground. Cost: the motes stop being a light source, which the owner asked for; so if this
   is the fix, it is a trade to put back to him, not a decision to take here.

2. **Give the torch reach instead of brightness.** Three options, in increasing risk:
   - a co-located SECOND emitter, much larger and much dimmer — a "lantern" that lights the
     surrounding water while the existing 1.2 m head stays the visible beam source;
   - `radius > 0` on the torch only, which switches on `sampleScene`'s inverse-square term
     and changes the falloff's shape, not just its scale;
   - enlarging `DIVER_LIGHT_SIZE_METRES`, which is what `006512b` shrank to kill a
     hard-edged rectangle around the diver — **read `LightEmitter`'s class doc before
     touching this one**; it is the option most likely to reintroduce a solved bug.

3. **Lift the Abyss ambient off zero.** A small non-zero blue floor gives the multiply
   something to work with everywhere, so the water reads as water rather than as void.
   Directly addresses B1 and is a single number. The risk is flattening the darkness the
   design wants, so it should be the smallest value that restores legibility, chosen by
   capture and not by argument. Note `shaftDaylightByZone` and `diverIntensityByZone` are
   BOTH derived from `ambientGreen`, so changing the ambient moves the god rays and the
   torch ramp with it — intended, but it means this dial is not local.

4. **Re-tune the colour grade last.** Contrast and vignette are the cheapest way to make a
   dark frame look better and the easiest way to hide a real problem, so they come after the
   causes are addressed, not before.

---

## 4. What must not regress

- **The torch is the deep's primary light source.** Design spec §17, 2026-08-12. Any fix
  that works by making the pearls or the motes brighter undoes the change that entry records.
- **Pearls are a marker glow, not a light source** (`PEARL_INTENSITY` 0.12).
- **The anglerfish's trap.** Motes stay cool blue, pearls stay amber, and the mote field
  stays slower than a third of `Tuning.ANGLERFISH_DRIFT_SPEED`. `MotesTest` and
  `AnglerfishDisguiseTest` both fail if not.
- **The Abyss stays frightening.** The complaint is about legibility, not about atmosphere.
  "Bright enough to read" is the target; "lit" is not.
- No per-frame allocation, determinism preserved, `dive/` untouched.
- **60 fps on the cabinet.** Half-res GI landed on 2026-08-12 and has never been profiled on
  the booth machine. If step 3.1 removes the mote emitters it also removes their cost, so
  measure fps in the same pass rather than separately.

---

## 5. The honest summary

The engine gives a light an opaque core and gives the deep no ambient, and those two facts
together mean the Abyss is black except inside a small radius around each lamp — with each
lamp shadowing the others. That is the shape of the problem. The most likely single cause
of the owner noticing it *today* is the mote emitters added today, and the first step is to
measure that rather than to argue about it.
