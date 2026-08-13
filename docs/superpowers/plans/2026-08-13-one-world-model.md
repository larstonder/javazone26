# One world model for the lighting

**Status:** proposal. Supersedes the interventions in
`2026-08-13-deep-water-lighting.md`, whose diagnosis stands and whose §2b–2d measurements are
the evidence for this.

**The owner, on a capture at 137 m:** *"you can't see the diver, you can barely see the
flashlight, but the pearls are very visible… I feel like we should have one consistent 'world
model' that drives everything."* And, separately: *"I like the water to have presence, but I
think we overdid the darkness."*

That is the correct diagnosis and it is a better one than "too dark". Below is why the frame
is incoherent, stated as five specific faults, and the single rule that replaces them.

---

## 1. There is no world model. There are five, and no two share a unit.

**1. NOTHING IN THE GAME HAS DISTANCE FALLOFF.** Every `drawLight` call in `DiveLighting`
passes `radius = 0f`, and `radiance_cascades.frag` applies its distance term only
`if (radius > 0.0)`. So a pearl 50 m away contributes exactly what a pearl 2 m away does.
There is no law anywhere relating how bright a thing looks to how far away it is — which is,
on its own, most of what "inconsistent" means. The engine implements inverse-square and we
have never switched it on.

**2. A PEARL CARRIES ITS OWN LIGHT, SO IT IS EXEMPT FROM THE WORLD.** Its emitter sits inside
its own drawn disc, and with `radius = 0` the disc's interior receives a flat shelf of its own
emission. A pearl therefore looks the same in the beam or out of it, at 10 m or at 140 m,
lit or unlit. Measured at 140 m: **pearls average 53/255 against open water's 0.009 — a factor
of 5 600.** That is the whole of the owner's "the pearls are very visible".

**3. THE DIVER HAS NO EMISSION AND THEREFORE NO EXISTENCE.** He is `albedo x daylight(depth)`,
and daylight at 140 m is 0.003, so he is algebraically black. The only thing visible at his
position is his torch's own emitter core. He is not lit by his own lamp, because with
`radius = 0` a light does not illuminate its neighbourhood as a function of distance — it
illuminates whatever its quad happens to cover.

**4. EVERY INTENSITY WAS TUNED AGAINST A DIFFERENT REFERENCE.** Pearls 0.12 ("a marker glow");
torch 2.0 to 6.0 (derived from `1 - daylight`); motes 0.05 x alpha ("an ambient wash"); the
god rays are not lights at all but albedo strips on `main`; daylight 0.68 to 0.003. No two of
these numbers are in the same unit, so no two can be reasoned about together — which is why
each of the last four days' fixes moved one and broke the balance with another.

**5. TWO OF THE FIVE ESCAPE THE COMPOSITE, IN DIFFERENT WAYS.** The god rays are drawn as
albedo, so the multiply treats them as surfaces rather than as light. The motes had their own
surface until 2026-08-13 and escaped it entirely. Anything exempt from the multiply is by
definition outside whatever model the multiply expresses.

---

## 2. The rule that replaces them

> **Everything visible is `albedo x irradiance`. Irradiance has exactly two sources, and both
> state their falloff.**
>
>  - **DAYLIGHT** — a function of DEPTH alone. Falls off with depth because water absorbs it.
>    This is what `ambientRed/Green/Blue` already is, and it is the only depth-dependent term
>    in the game.
>  - **LOCAL LIGHTS** — every emitter, each with a real `radius`, so irradiance falls as
>    `1/d²`. This is the term that does not currently exist.
>
> Nothing else. No object is self-lit, nothing is exempt from the multiply, and every intensity
> is expressed in ONE unit: the irradiance it delivers at one metre.

Three of the five faults die immediately as consequences rather than as separate fixes:

- ~~**The diver becomes visible** because his torch is 1.2 m from his body and `1/d²` lights
  him.~~ **THIS WAS WRONG, ON BOTH HALVES, AND STEP 1 DISPROVED IT.** Struck rather than
  deleted because it is the reason step 1 was expected to produce a visible headline and did
  not.

  The torch is not 1.2 m from his body — 1.2 m is the EMITTER's size; it is carried
  `TORCH_FORWARD_FRACTION` (0.40) × `DIVER_HEIGHT_METRES` (9 m) = **3.6 m ahead** along the
  heading. And distance is not what stops it lighting him: `radiance_cascades.frag:114-117` is
  `dotK = max(dot(coneDir, -rayDir), 0); color *= clamp(dotK - cos(coneAngle), 0, 1)`, so a
  probe BEHIND a directional emitter gets `clamp(0 - cos(halfAngle), 0, 1)` = **exactly zero at
  every distance**. The diver is always behind his own beam, hovering or swimming.

  Falloff could never have fixed this, for a reason that should have been obvious before the
  work rather than after it: the distance term CLAMPS AT 1, so it can only ever REMOVE light.
  It cannot create illumination that was not there. Measured: the diver's torso is 0.000/255 at
  140 m both before and after.

  What will actually light him is step 2 (at the proposed Abyss daylight of 0.15 his suit puts
  him near 10/255), or a fifth light — a co-located, omnidirectional, much dimmer "lantern" at
  the torch, which is `2026-08-13-deep-water-lighting.md` §3.2 and is a NEW light rather than a
  property of an existing one.
- **Pearls stop being exempt.** With a radius their emission still lights their own body — but
  it also lights the water around them, falls off, and is comparable with the torch in the same
  unit. A pearl in the beam gets brighter, which is what "affected by the light" means.
- **The torch's reach becomes a physical quantity** instead of an artefact of its quad size.
  `DIVER_LIGHT_SIZE_METRES` goes back to being about what the emitter LOOKS like, which is what
  its doc has always wanted it to be about.

---

## 3. The one number the owner sets

Everything above is structure. Exactly one thing is taste, and it is the thing the owner has
now twice given an opinion on:

> **How much daylight reaches the bottom of the column?**

Today it is 0.003 blue and effectively zero, and the measured consequence is that 96% of the
Abyss frame is below 2/255. The owner: *"I like the water to have presence, but I think we
overdid the darkness."*

`2026-08-13-deep-water-lighting.md` §2d has the arithmetic for what any given answer produces,
validated against real captures. For the deep water to read at all, the product
`albedo x daylight` must clear **0.0074** or the colour grade's contrast step clamps it to
pure black; to read at ~12/255 it must reach about 0.056.

A concrete starting proposal, keeping both ramps monotone and lifting only the bottom two zones:

```
zone        albedo   daylight   product   out/255      (today)
Shallows     0.520      0.680     0.354     138.6        138.6
Kelp         0.360      0.440     0.158      66.5         66.5
Twilight     0.220      0.240     0.053      12.7         12.7
Trench       0.180      0.190     0.034       5.5          0.4
Abyss        0.150      0.150     0.023       3.2          0.0
```

That is "the deep is very dark but it is water, not a void" — the Abyss still an order of
magnitude below the Twilight, and the silhouette of the rock readable against it. It is a
**design amendment** either way (§11's "near-total dark" and the §11 zone table's "Near-total
dark. Pearls are the only light source" both move), so it wants recording in §17 whichever
values are chosen.

---

## 4. Order of work

1. ~~**Switch on distance falloff.**~~ **DONE.** Every `drawLight` states a reach in metres and
   every intensity is stated as irradiance at one metre (`E1 = intensity × sizeMetres ×
   coneMaskPeak`), which subsumed the four separate size-correction constants that each existed
   to answer the same question against a different reference size. `radius = R² × camScale`,
   established both from the bytecode and by measuring a pearl's halo in METRES at two
   framebuffer sizes (mean disagreement 0.0019, against 0.0117 predicted if the radius were
   hardcoded).

   **It changed almost nothing visually, which is the correct outcome and was not the
   prediction.** The distance term clamps at 1, so it only removes light; the frame at 140 m
   went from 0.225 to 0.245 mean. What it bought is structural — one unit, so the four
   intensities can be reasoned about together — plus two latent faults found on the way: a
   reach above ~24 m saturates the RGBA16F `metadata.a` to `+inf` and silently restores
   no-falloff, and motes of different sizes were casting different amounts of light for the
   same nominal intensity.
2. **Set the daylight floor** from §3, once the owner picks.
3. **Re-check the pearls' exemption.** With falloff on, a pearl's own shelf may still dominate
   its body; if so, the fix is the emitter's SIZE relative to its silhouette, which
   `PEARL_LIGHT_SIZE_METRES` already reasons about at length.
4. **Re-check the torch.** Its intensity ramp was derived from `1 - daylight` to compensate for
   a reach problem that falloff makes explicit; it may simplify to a constant.
5. **The god rays last.** They are albedo pretending to be light, and once everything else is
   in one unit it will be obvious whether that still earns its place.

## 5. What must not regress

- The anglerfish's trap: motes stay cool blue, pearls amber, and the lure stays pixel-identical
  to a pearl in both draw and light (`AnglerfishDisguiseTest`).
- No god rays in the Abyss (`LightShaftsTest`, spec §11/§6b).
- Determinism, no per-frame allocation, `dive/` untouched.
- 60 fps: `radius > 0` adds a per-sample distance term in the cascade march, on top of the
  half-res GI bump of 2026-08-12. Neither has been profiled on the booth machine.
- **Every step measured at 140 m with `EPT_DEPTH`, against a bit-identical control pair.** The
  three phase pins exist for exactly this and every number in this document came from them.
