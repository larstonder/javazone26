# Doing the lighting the way Pulse Engine intends

**Status:** research + proposal. One measured probe, reverted; the tree is clean at `c8c051b`.

**The owner's brief, verbatim:** *"I like that the deep gets darker, but things should still be
legible. I think you rely too much on numbers and hacks. Try to adhere to how it's supposed to be
done using the Pulse engine's own capabilities."*

He is right, and the specific way he is right has a name.

---

## The headline

**Pulse Engine 0.13.0 ships a directional sun-and-sky daylight model. It is on by default. It has
been running in this game since the day GI was added, and we have never touched any of its seven
properties — we built a flat `ambientLight` uniform and a baked albedo ramp instead.**

`radiance_cascades.frag:277-284`:

```glsl
// Mix in the sun and sky radiance if we're in the outermost cascade
if (deltaRadiance.a == 0.0 && cascadeIndex == cascadeCount - 1.0)
{
    float angleToSun = mod(rayAngle - (sunAngle - camAngle), TAU);
    float sunIntensity = pow(max(0.0, cos(angleToSun)), 4.0 / sunDistance);
    vec3 sunAndSkyRandiance = mix(sunColor.rgb * sunIntensity, skyColor.rgb, 0.3);
    deltaRadiance.rgb = max(sunAndSkyRandiance, deltaRadiance.rgb);
}
```

And the second half, which is the same fault seen from the other side:

**The engine's model expects the world's SOLID GEOMETRY to be in the light solver, and ours is
not.** `GiSceneRenderer.drawOccluder` exists and we have never called it. The rock walls — 100% of
the large opaque surfaces in the game — are drawn only as albedo on `main`. Consequently
`bounceAccumulation` (default 0.7) has nothing to bounce off, `interiorLightTex` and
`ambientInteriorLight` have no interiors to light, `aoRadius`/`aoStrength` have nothing to occlude,
and the sun casts no shadows. Five of the engine's mechanisms are switched off *by the absence of
one draw call*, and every one of them is a legibility mechanism.

That is the whole of "you rely too much on numbers and hacks". The numbers are not the disease.

---

## 0. Read this first

`docs/superpowers/plans/2026-08-13-one-world-model.md` and `2026-08-13-deep-water-lighting.md` §0
are still correct and are not re-derived here. Two corrections to the record, both load-bearing:

**(a) `render/ShaftRenderer.kt`'s class doc is wrong and should be fixed.** It says:

> *"The engine's own shader says otherwise: `final.frag:57` is `fragColor = vec4(base + light, 1.0)`
> … The composite is ADDITIVE. So there was nothing to escape from"*

It is not. `CLAUDE.md` already settles this and the bytecode confirms it again:
`GlobalIlluminationSystem.onUpdate` @383-405 constructs
`MultiplyEffect("gi_blend_effect", 15, "gi_light_final", minReflectance)` and calls
`addPostProcessingEffect` on `gfx.getSurface(targetSurface)`, where `targetSurface` is initialised
to the literal `"main"` in `<init>` @214. `final.frag:59`'s `base + light` is the **light map
assembling itself** on the `gi_light_final` surface — `GiFinal`'s `baseTex` is that surface's own
(empty) texture. The composite onto `main` is `texture_multiply_blend.frag:21`:

```glsl
fragColor = vec4(c0.rgb * c1.rgb, c0.a);
```

So the god rays **are** multiplied by the light map and **are** crushed in the deep. Their placement
on `main` may still be the right call, but it is not free the way that doc claims, and the reasoning
needs replacing before anyone builds on it.

**(b) `sunAngle`'s screen direction is NOT verified.** It is the one property never assigned in
`GlobalIlluminationSystem.<init>`, so it defaults to `0f` — light along +X in the shader's frame.
The probe below used `sunAngle = 90f` and it looked right. Which screen direction that actually is
has to be established the way `DiverSprite.bodyAngleFor` was — by capture, against the shader's own
convention (`rayDir = vec2(cos(rayAngle), -sin(rayAngle))`, `radiance_cascades.frag:251`, and the
`fneg` on upload at `GiRadianceCascades` @731) — not by assertion. This game has two angle
conventions already and got burned once; it will not get a third for free.

---

## 1. What the engine's intended model is

Four surfaces and a strict division of labour. Every citation is from the extracted
`pulse-engine-0.13.0.jar`.

### 1.1 The scene the light solver sees is a SEPARATE scene

`gi_local_scene` is its own surface (main camera, `BlendFunction.NONE`, `TextureFilter.NEAREST`, two
colour attachments: colour and metadata). Two things go into it, and the engine has one entry point
for each:

| | Entry point | Instance data written |
|---|---|---|
| Emitters | `drawLight(texture, x, y, w, h, angle, intensity, coneAngle, radius, cornerRadius = 0f, xTiling = 1f, yTiling = 1f)` | `intensity > 0`, `coneAngle`, `radius` |
| **Occluders** | **`drawOccluder(texture, x, y, w, h, angle, edgeLight, cornerRadius = 0f, xTiling = 1f, yTiling = 1f)`** | `intensity = 0`, `coneAngle = 360`, `radius = edgeLight` |

**They fill the identical 18-float instance layout.** `drawOccluder` is `drawLight` with `intensity`
pinned to `0f` and `coneAngle` pinned to the literal `360f`, with its `edgeLight` argument written
into the `radius` slot — which `scene.frag:78` packs as `metadata.a` and `scene.frag:11` labels
`sourceRadius; // light radius / occluder edge light`.

**There is no type flag anywhere.** Classification is entirely by pixel value:

- **Occluder-ness is the DRAW COLOUR'S ALPHA.** `final.frag:33` `scene.a > 0.8`,
  `bounce.frag:24` `> 0.5`, `jfa_seed.frag:12` `> 0.5`. `scene.frag:73-74`'s
  `if (texColor.a < 0.5) discard;` gates it further on the texture's own alpha. All four thresholds
  are already correctly documented in `render/LightEmitter.kt` — including the deliberate 0.75 cap
  on shaft alpha, which now reads as "above `jfa_seed`'s 0.5 so it seeds the SDF, below
  `final.frag`'s 0.8 so it is not swapped onto the interior branch". That is the right instinct and
  it generalises.
- **Emitter-ness is `metadata.b > 0`**, i.e. `intensity > 0`.

So one draw is routinely both, which is `2026-08-13-deep-water-lighting.md` §0's "every emitter is
also an occluder" restated from the other end — and it means **`drawLight(intensity = 0f)` is a pure
occluder**, a second route to step 2 if `drawOccluder`'s fixed `coneAngle = 360` ever gets in the way.

Two further details of `drawLight` worth having written down, since neither is obvious from the call
site: **`angle` does double duty** — it rotates the quad (`scene.vert:102`) *and* aims the cone
(`scene.vert:79` → `metadata.g`); and **`coneAngle` becomes a HALF-angle in the shader**
(`radiance_cascades.frag:108` `metadata.r * PI` = `coneAngleDeg × π/360`).

The scene entity route (`GiLightSource`, `GiOccluder`, dispatched by the five `RenderPass` fields in
`GlobalIlluminationSystem.<init>`) reaches **the same two methods** — their `DefaultImpls` bodies
call `drawLight`/`drawOccluder` on the same renderer, passing literal masks `3584`/`896` to take all
three trailing defaults. So immediate mode is not a shortcut around the engine; it is the engine's
own back end, and it is **strictly more expressive** (the interfaces cannot reach `cornerRadius` or
tiling at all, and `GiLightSource`'s default body early-outs on `intensity == 0f`, so the scene route
cannot express a pure occluder through it). We lose nothing by staying immediate. What the entity
route *does* buy is automatic dispatch into **both** `gi_local_scene` and `gi_global_scene` — which
matters only if §5 step 5a is pursued.

The engine ships worked examples of both: `modules/scene/entities/Lamp` (`DirectLightSource`,
`GiLightSource`) and `Wall` (`DirectLightOccluder`, `GiOccluder`, `NormalMapped`). `Wall` is the
reference for what step 2 should look like — an opaque, normal-mapped, shadow-casting surface —
and `GiOccluder`'s four properties (`occluderTexture`, `bounceColor`, `castShadows`, `edgeLight`)
are a spec for what an occluder is supposed to carry.

### 1.2 A pixel's light is decided by WHAT IT IS, not by one global constant

`final.frag:32-49`, in full:

```glsl
float sceneSourceIntensity = sceneMeta.b;
bool isOccluder = scene.a > 0.8;
bool isLightSource = sceneSourceIntensity > 0.0;

if (isOccluder)
{
    if (isLightSource)  light  = light * sourceIntensity;                                    // :40
    else                light  = texture(interiorLightTex, offsetUv).rgb + ambientInteriorLight.rgb; // :44
}

light += ambientLight.rgb;   // :49  — unconditional, every pixel
```

Three classes, three light sources:

- **Open space** — gets `exteriorLightTex`, the radiance-cascade solve. Directional, occluded,
  normal-modulated.
- **A lamp's own body** — gets the same, scaled by the global `sourceIntensity` (default `1.0`).
  This is the dial for "the pearl's disc does not blow out", and we do not use it.
- **Solid geometry's interior** — gets `interiorLightTex + ambientInteriorLight`, and note it
  **replaces** rather than adds to the exterior sample.

`ambientLight` (line 49) is the only term applied to all three, unconditionally, with no direction,
no occlusion and no normal response. **That is the one term we use, and it is the least expressive
one in the shader.**

### 1.3 `interior.frag` is the engine's answer to "dark but legible"

This is the mechanism the reverted rim light (`d6faaa5`) was hand-building as albedo.
`interior.frag:28-70`: for a pixel *inside* an occluder, march 16 rays outward through the
**negated** SDF until they exit the solid, then sample the exterior light at the exit point:

```glsl
float edgeLightStrengt = sceneMetaData.a * 0.01;                       // :39  the occluder's edgeLight
vec3 normal = normalize(texture(normalMapTex, uv).xyz * 2.0 - 1.0);    // :40
…
vec3 lightDir = normalize(vec3(dir, normalMapScale));                  // :60
float falloff = clamp(dot(normal, lightDir) * 4, 0.0, 1.0);            // :61
falloff *= edgeLightStrengt / (1.0 + (0.05 * travelDist / scale));     // :62
light += texture(exteriorLightTex, samplePos * …).rgb * falloff;       // :65
```

Per-occluder edge light, normal-weighted, distance-attenuated, temporally accumulated (`:70`
`mix(light / rayCount, lastLight.rgb, 0.75)`). It gives a solid object a rim that reads its shape
without lighting the water around it. **We have no occluders, so this shader currently does nothing
in our game at all.**

`ambientInteriorLight` is its floor — a minimum brightness **on solid objects only**. That is
precisely the quantity `DiveLighting.AMBIENT_FLOOR_RED/GREEN/BLUE` was invented to provide, except
the engine's version does not also lift the open water.

### 1.4 The daylight

Sun and sky are injected on rays that escape the scene, in the outermost cascade only
(`radiance_cascades.frag:277-284`, quoted above). Resolved:

```
radiance = 0.7 · sunColor · pow(max(0, cos(angleToSun)), 4/sunDistance)  +  0.3 · skyColor
```

- **The sun term is directional.** `sunAngle` aims it; `sunDistance` sets the lobe width via the
  exponent `4/sunDistance` — at the default `sunDistance = 10` the exponent is **0.4**, an extremely
  broad lobe. That is not a "sun"; it is a hemisphere of daylight, which is exactly what light
  entering through a water surface is.
- **The sky term is isotropic.** Flat `0.3 × skyColor` for every ray angle. There is no
  zenith/horizon gradient and no up-vector. **A ray going up and a ray going down get identical sky.**
  Up/down asymmetry is available only from the sun's lobe.
- Both are **occluded**: the injection is gated on `deltaRadiance.a == 0.0`, and a ray that hit
  geometry carries that geometry's alpha. Daylight therefore casts real shadows — once there is
  geometry to cast them.
- Both are **normal-modulated by the time they reach the light map**, and this point is worth being
  careful about because it is easy to read the shader the other way. `:287` gates the Lambert term
  on `cascadeIndex < 2.0`, and the sun/sky is injected only in the *outermost* cascade — so it looks
  as though daylight escapes normal shading. It does not: cascades render outermost-first, `merge`
  (`:181-188`) pulls the upper cascade's value down into cascades 1 and 0, and `:286-291` then
  applies `clamp(dot(normal, lightDir) * 4, 0, 1)` to that already-merged value. The light map is
  cascade `drawCascade` (= 0), so the daylight in it **has** been through the normal term.
  **Confirmed empirically:** the probe in §4 changed nothing but sun and sky, and the rock's relief
  appeared. Nothing else in that frame could have produced it.

  Note the `* 4` and the `clamp`: normals within roughly ±76° of a ray get full radiance, so this is
  a saturating term that darkens only steeply-facing-away surfaces. It is a shading cue, not a
  physical Lambert.

Wiring, from `GiRadianceCascades.applyEffect`: `skyColor_uniform = system.skyColor ×
(skyLight ? skyIntensity : 0)` (@349-378), `sunColor_uniform = system.sunColor ×
(skyLight ? sunIntensity : 0)` (@379-408), `sunAngle_uniform = -toRadians(system.sunAngle)`
(@714-732, note the `fneg`), `sunDistance` raw (@735-746).

**One trap.** With `bilinearFix = true` (the default, and ours), `deltaRadiance` is a bilinear blend
of four probe samples (`:267`). A single `TERMINATED` ray among the four carries `a = -1` and pulls
the blended alpha off exactly `0.0`, which **suppresses the sun/sky test at `:278` for that ray**.
So a dense field of small occluders — the motes — can locally delete the daylight. That is a sharper
version of candidate A3 in the deep-water plan, and it is now a mechanism rather than a suspicion.

### 1.5 The defaults, complete

Read out of `GlobalIlluminationSystem.<init>` bytecode. **We touch five of thirty-three.**

| Property | Default | Ours | Property | Default | Ours |
|---|---|---|---|---|---|
| `ambientLight` | `(0,0,0,1)` | **written per tick** | `bounceAccumulation` | `0.7` | — |
| `ambientInteriorLight` | `(0,0,0,1)` | — | `bounceRadius` | `0.0` | — |
| **`skyLight`** | **`true`** | — | `bounceEdgeFade` | `0.2` | — |
| **`skyColor`** | `(0.02,0.08,0.2)` | — | `normalMapScale` | `4.0` | — |
| **`sunColor`** | `(0.95,0.95,0.9)` | — | `sourceIntensity` | `1.0` | — |
| **`skyIntensity`** | `0.1` | — | `minReflectance` | `0.02` | — |
| **`sunIntensity`** | `0.01` | — | `aoRadius` | `30.0` | **4 m × scale** |
| **`sunDistance`** | `10.0` | — | `aoStrength` | `1.5` | — |
| **`sunAngle`** | `0.0` | — | `traceWorldRays` | `true` | — |
| `dithering` | `0.2` | **`0.6`** | `mergeCascades` | `true` | — |
| `lightTexFilter` | `LINEAR` | — | `bilinearFix` | `true` | — |
| `lightTexScale` | `0.4` | **`0.5`** | `jitterFix` | `true` | — |
| `localSceneTexScale` | `0.4` | **`0.5`** | `upscaleSmaleSources` | `true` | — |
| `globalSceneTexScale` | `0.6` | — | `targetSurface` | `"main"` | — |
| `globalWorldScale` | `4.0` | — | `drawCascade` | `0` | — |
| `maxCascades` | `10` | — | `maxSteps` | `25` | — |
| `intervalLength` | `1.0` | — | | | |

The seven daylight properties are bold. So is `skyLight = true` — meaning the sky has been
contributing `0.3 × (0.02, 0.08, 0.2) × 0.1 = (0.0006, 0.0024, 0.006)` to every open-water pixel all
along, against our `AMBIENT_FLOOR_BLUE` of `0.44`. **Our flat ambient is seventy times the engine's
daylight.** That single ratio is the shape of the problem.

`traceWorldRays = true` and `globalWorldScale = 4` are also live and unexploited: rays leaving the
screen (in cascades > 1) continue into `gi_global_scene`, a 4× zoomed-out copy of the same scene
(`onFixedUpdate` @0-192 syncs its camera). **Nothing is drawn into it.** It is the only route by
which something off-screen — the water surface 140 m above — could light the frame.

---

## 2. Dead ends, written down so nobody looks again

**There is no fog system. There is no participating medium. There is no distance attenuation of the
framebuffer.** Exhaustive greps over all 54 shader sources and every class under
`no/njoh/pulseengine/**` for `fog|medium|attenuat|absorb|scatter|extinction|beer|transmittance|haze|
volumetric|mist|density`:

- `fog` appears in **two class files** (`DirectLightingSystem`, `DirectLightBlendEffect`) and **one
  shader** (`lighting/direct/lighting_blend.frag`). It is a 2D animated fBm noise haze, **purely
  additive** (`:121` `fragColor = vec4(baseColor.rgb * lighting + fogColor, alpha)`), never mixing
  toward a fog colour with distance, no `exp(-d)`, no Beer–Lambert. It lives in the **direct**
  lighting system, not GI, and defaults to `fogIntensity = 0`.
- `absorption`, `scatter`, `extinction`, `transmittance`, `beer`, `haze`, `medium`, `volumetric`,
  `density` — **zero hits**, shaders and classes both.
- No class anywhere named `*Fog*`, `*Medium*`, `*Attenuation*`, `*Depth*`, `*Volum*`.

**There is no auto-exposure or adaptation.** Zero hits for `autoExposure|adaptation|eyeAdapt|
averageLuminance|histogram`. The only luminance dot-products are fixed thresholds in
`brightness_threshold.frag:14` and `bloom_downsample.frag:16`, never fed back.

**There is no per-draw depth a game can write.** `SurfaceConfigInternal.currentDepth` is an
auto-incrementing `1e-6` painter's tiebreaker owned by the engine; a `DEPTH_TEXTURE` attachment
exists but would contain that counter, not scene distance. There are exactly four `BlendFunction`s
(`NONE`, `NORMAL`, `ADDITIVE`, `SCREEN`) and no custom factors. Per-surface integer `zOrder` is the
only game-facing layering primitive.

**The closest thing to transported-light attenuation is hard-coded**: `interior.frag:62`'s
`1/(1 + 0.05·travelDist/scale)`. The constant is not exposed.

So: **a physically-correct participating medium for the water is not available and cannot be
configured. Building one means a custom `BaseEffect`** — subclass, override `loadShaderProgram`,
`applyEffect` binds `engine.gfx.getSurface(name).getTexture()` as `tex1` exactly the way
`MultiplyEffect` does. That is a real and supported extension point, and it is the honest
alternative if §5's route proves insufficient. It is not something the engine hands us.

**Post-processing effects, complete:** `BloomEffect`, `BlurEffect`, `ColorGradingEffect`,
`FrostedGlassEffect`, `MultiplyEffect`, `ThresholdEffect`. **None expresses depth, distance or
exposure adaptation.** The only one with a layering concept is `FrostedGlassEffect`, via a
`zThreshold` on surface `zOrder`.

**But `ColorGradingEffect` does have a real, un-tried lever for shadow response — the tone mapper**,
and it is a far better one than `contrast`. From `color_grading.frag`, behaviour as `x → 0⁺`:

| `ToneMapper` | near-black slope | note |
|---|---|---|
| `ACES` (**ours**) | **≈ 0.214×** | steepest black crush of the six; `clamp(…,0,1)` at `:26` |
| `LOTTES` | `x^1.6 / c` | superlinear crush; **NaN on negative input** |
| `FILMIC` | hard clip | `:31` `max(0, x - 0.004)` — everything ≤ 0.004 becomes exactly 0 |
| `NONE` | 1.0 | passthrough, nothing clamped afterwards |
| `REINHARD` | **1.0** | `x/(1+x)`; the only mapper faithful to near-black |
| `UNCHARTED2` | **≈ 2.3×** | *lifts* shadows |

And `exposure` is not a stop: `:121` is `color.rgb *= pow(2.0, exposure) - 1.0`, so `exposure = 1`
is identity, `exposure = 0` makes the frame **pure black**, and negative values produce negative RGB.
It is a pure multiply and **can never lift a true black pixel**. `contrast` (`:124`) pivots on a
hard-coded `0.5` with a `0.05`-per-unit slope, so our documented "1.3 subtracts 0.00739" is right —
and for `contrast < 1` it *lifts* blacks toward milk. Neither dial is the right instrument.

**We chose the single harshest shadow response available and then fought it with a contrast
constant.** That is hand-rolled item #4, diagnosed.

### 2.1 The other lighting system, and why we should not switch

`modules/lighting/direct/` is a **completely separate** system — `DirectLightingSystem`,
`DirectLightRenderer`, its own surfaces (`light_surface`, `light_normal_map`, `light_occluder_map`)
and its own blend effect. It has things GI does not: `DirectLightType.LINEAR` line/capsule lights;
analytic penumbra soft shadows from a 128-bit occlusion mask marching an SSBO of edges
(`light.frag:51-56, 221-255`); per-light `spill` through occluders; a **real z coordinate**
(`light.vert:75`, `light.frag:368`) giving a true 3D Lambert with no `* 4` fudge and no cascade
restriction; and the fog above.

**It has no sun and no ambient beyond a positionless `ambientColor` (`light.frag:30`), no bounce, no
interior lighting, no AO, no textured emitters and no off-screen lighting.** For this game that
trade is the wrong way round: the four things GI has and direct lacks are exactly the four
legibility mechanisms of §6.

Both *can* run at once — disjoint surfaces, disjoint effect names, and the shipped `Lamp`/`Wall`
entities implement both interfaces deliberately. But `DirectLightingSystem.targetSurfaces` and
`GlobalIlluminationSystem.targetSurface` both default to `"main"`, so both blend effects would stack
on the same surface and double-light it. **Recorded so nobody has to look: direct lighting is a real
option, it is not obviously better, and running both is a trap.**

---

## 3. Verdict on each of the five hand-rolled mechanisms

### 1. Depth darkening baked into the water's ALBEDO — **PARTLY OURS, MOSTLY REPLACED**

`DiveRenderer.drawZoneBands` per-strip `setDrawColor` from `zoneRed/Green/Blue` is doing two jobs at
once, and only one of them is legitimately ours:

- **Legitimate:** the water's *intrinsic colour* shifting toward blue with depth (each zone's
  1 : 4 : 8-ish ratio). Water really does absorb red first. No engine mechanism expresses this and
  none should — it is a material property.
- **Not ours:** the *level* falling 0.52 → 0.18 to encode how much light reaches that depth. That is
  irradiance living in a material, and it is why the frame falls off quadratically. The engine's
  place for it is the daylight solve.

The catch, stated honestly: **the engine's daylight is uniform over the frame**, so it cannot on its
own produce an in-frame vertical gradient. §5 step 5 has the two candidate routes and neither is
free. Until one lands, some residual level ramp in the albedo is the pragmatic answer — but a much
gentler one, because it stops being the *only* thing making the deep dark.

### 2. One `ambientLight` colour per frame from the diver's depth, with a `max()` floor — **REPLACED**

Replace the *floor* with `skyColor`/`skyIntensity` + `sunColor`/`sunIntensity`, and the
object-legibility half of its job with `ambientInteriorLight`. `AMBIENT_FLOOR_RED`'s own doc argues
the floor is *"the water's own faint glow — scatter, bioluminescence, the light the diver's own lamp
bounces off the column"*. That is a correct piece of physics and the engine has a term for it: it is
the **sky**, and `bounceAccumulation` is literally the lamp bouncing off the column. The floor was
the right idea implemented on the only surface we had noticed.

The depth-driven *ramp* (`ambientRed/Green/Blue`) stays — it just moves from `ambientLight` to
`skyIntensity`/`sunIntensity`. Same one write per tick, same cost, but now the light is directional,
occluded and normal-modulated instead of a flat add. Note that `shaftDaylightByZone` and
`torchIrradianceByZone` are derived from these tables and must keep reading the same numbers.

### 3. `GI_REFLECTANCE_FLOOR` / `floorBlueForReflectance` — **OURS, KEEP, PROBABLY REDUNDANT**

`minReflectance` is a genuine engine constraint (`texture_multiply_blend.frag:18-19`), it is a public
`@Prop var` defaulting to `0.02`, and `DiveRenderer.zoneRed`'s doc already records that the current
table clears it everywhere (min linear length 0.02811 at 135 m) so the lift is inert. Keep it as the
guard it was written to be. The cleaner alternative — `system.minReflectance = 0f` — is already
documented at `DiveRenderer.kt:191-193` as available; it is a one-liner and would let the water's
albedo say what the water looks like without a second opinion. Low priority either way.

### 4. `contrast` 1.3 → 1.0 — **REPLACED, by the tone mapper**

See §2. The dial that actually governs shadow response is `toneMapper`, and we are on the most
crushing of the six. `contrast` should go back to its default `1.0` and stay there.

### 5. Four intensities and four reaches in "irradiance at one metre" — **OURS, KEEP**

This is the one that is genuinely ours and genuinely right. The engine gives `drawLight` an
`intensity` and a `radius` and no unit for either; `radius = R² × camScale`
(`radiance_cascades.frag:126`) is a raw shader quantity, not a physical one. A project-level unit
that makes four lights comparable is exactly the kind of thing a game should own, and
`2026-08-13-one-world-model.md` §4.1 already paid for it. Keep it, and **express the daylight in the
same unit** so there are five comparable numbers instead of four plus an ambient.

Two engine dials that belong in the same conversation and are currently unset: `sourceIntensity`
(how bright an emitter's own body renders — the pearl-exemption dial, step 3 of the one-world plan)
and `bounceRadius` (default `0`, so bounce light currently has no falloff even if bounce were live).

---

## 4. The probe, and what it measured

**One change, four captures, reverted.** `EPT_DEPTH=140 EPT_WAVE_PHASE=12 EPT_SHAFT_PHASE=30
EPT_MOTE_PHASE=45 EPT_DEV=1`, real `screencapture` of the game window, never `EPT_SCREENSHOT`.
The frames are at `/tmp/engine-native-{ctrl,sky-only,sky-mid,sky}-140.png` — look at them; the
numbers below understate the difference.
Luminance is Rec.709 out of 255 over the game region; "rock p99" is the 99th percentile of the left
cliff column and is the legibility proxy; "diver" is the torso patch.

| | control (`c8c051b`) | sky 1.6 / sun 0.6 | **sky 2.8 / sun 1.0** | sky 5 / sun 2 |
|---|---|---|---|---|
| flat `ambientLight` floor | **0.16 / 0.30 / 0.44** | 0 | **0** | 0.16 / 0.30 / 0.44 |
| frame mean | 3.042 | 2.503 | **4.261** | 15.370 |
| % below 2/255 | 86.2 | 89.0 | **73.3** | 0.9 |
| open water mean | 1.674 | 1.398 | **2.393** | 9.064 |
| **rock p99** | **9.382** | 8.353 | **30.778** | 108.773 |
| diver torso | 15.347 | 10.682 | **15.577** | 40.238 |

The third column is the result worth having. **With the flat ambient floor set to exactly zero and
the daylight expressed through the engine's sun and sky instead, the diver reads identically
(15.58 vs 15.35) and the rock is 3.3× more legible (30.8 vs 9.4) — at a frame that is still 73%
below 2/255.**

By eye, and this is the part the numbers understate: in the control the cliff is a flat murky-blue
smear and the pearls are flat discs, because everything in frame received the same additive lift.
In the probe the cliff reads as *rock* — its normal map is doing work, the relief is visible, the
faces catch light differently — the pearls read as lit spheres, and the water between them is
blacker than the control's. That is "dark AND legible", and it came from turning on a feature, not
from a constant.

`sunColor` (0.95, 0.95, 0.9) × `sunIntensity` at weight 0.7 dominates `skyColor` (0.02, 0.08, 0.2)
× `skyIntensity` at weight 0.3 by more than an order of magnitude in the probe, so most of that
result is **the directional sun**, i.e. the normal-modulated term. The sky is the isotropic fill.
Those are exactly the two terms this game's water wants, and they arrive already separated.

**What the probe does NOT show, stated plainly:**

- Nothing was drawn as an occluder, so the sun casts no shadows in any of these frames. The rock's
  gain is normal-map response alone. §5 step 2 is expected to be a *larger* effect than this and is
  entirely unmeasured.
- The three numbers are not a recommendation. They bracket a range; the owner picks the value.
- Only 140 m was captured. 20 m and 75 m are unmeasured and the shallow end must not move.
- No fps measurement. Bounce, AO and interior all go live the moment occluders exist, and none has
  been profiled on the booth machine.

---

## 5. The migration, ordered, with risk

One step at a time, each against a bit-identical control pair at 20 / 75 / 140 m with all three
phase pins. The order is chosen so the largest structural gain comes before any re-tuning.

**Step 1 — Turn the daylight into sun + sky. LOW RISK, HIGH VALUE, MEASURED.**
Drive `skyIntensity` and `sunIntensity` from the existing `ambientRed/Green/Blue` tables in
`updateAmbient` instead of writing `ambientLight`; set `ambientLight` to black; delete
`AMBIENT_FLOOR_*`. Set `sunAngle` upward (verify by capture — see §0b) and leave `sunDistance` at
`10` so the lobe is a hemisphere, not a disc. *Measure:* the §4 table at all three depths; the
Shallows must be unchanged to the digit, which is the same constraint `AMBIENT_FLOOR_RED` was chosen
under. *Risk:* the sky term is isotropic, so the deep loses the flat lift the water currently gets —
which is the point, but it must not take the diver with it (the probe says it does not).
*Reversible:* entirely, it is one function.

**Step 2 — Draw the rock as an occluder. MEDIUM RISK, THE LARGEST EXPECTED GAIN.**
`RockFace`'s quads already have diffuse + normal textures with real alpha edges. Add a
`drawOccluder` call per wall quad into `gi_local_scene`, with the **argument list copied** from the
`drawTexture` call the way `DiveRenderer.drawDiver` copies its normal-map call — same
`(x, y, w, h, angle)` centre-origin tuple, same texture, so the two cannot derive independently.
Give it a non-zero `edgeLight` so `interior.frag` runs. This switches on, in one change: real
shadows from the sun, `bounceAccumulation`'s 0.7 (the torch bouncing off stone), `ambientInteriorLight`,
`aoRadius`/`aoStrength` (which `DiveLighting.setup`'s own comment says *"goes live the moment
something is drawn as an OCCLUDER — the rock walls are the obvious candidate"*), and
`interiorLightTex`'s rim. *Measure:* fps first (F3 `MetricViewer`) — four dormant shaders start
doing work simultaneously; then rock p99 and the beam's shape against a wall. *Risk:* the alpha ≥ 0.5
discard means the ragged silhouette edge becomes the occlusion boundary, which is probably a feature
but is unverified; and the walls will start blocking the torch, which is a gameplay change.
*Reversible:* yes, it is additive draw calls.

**Step 3 — Change the tone mapper. LOW RISK, ONE TOKEN.**
`ACES` → `UNCHARTED2` (near-black ×2.3) or `REINHARD` (×1.0). *Measure:* the highlights, not the
shadows — the pearls and the torch core are where a different shoulder will show first, and
`REINHARD` has no shoulder to speak of. Do **not** use `LOTTES` (NaN on negative input, and
`contrast > 1` produces negatives). Set `contrast` back to `1.0` and leave it. *Reversible:* trivially.

**Step 4 — Re-check the emitters against the new world. LOW RISK.**
With daylight directional and stone bouncing, revisit `sourceIntensity` (the pearl-exemption dial),
`bounceRadius` (currently `0`, so bounce has no falloff), and the four project-unit intensities. The
mote field is the specific thing to re-examine: §1.4's `bilinearFix` interaction means a dense
occluder field **suppresses the daylight** on the rays it touches, which is a sharper mechanism than
the deep-water plan's A3 and predicts that motes now cost more than they did.

**Step 5 — The in-frame vertical gradient. HIGH RISK, DO LAST, MAY NOT BE WORTH IT.**
Only after 1–4 is it clear how much residual albedo ramp is still needed. Two engine-native routes,
both unproven:
- *(a) The water surface as an emitter in `gi_global_scene`.* `traceWorldRays` and
  `globalWorldScale = 4` are already on; a large emitter at depth 0 drawn into the global scene
  would light the column from above with real falloff. **Known blocker:** the one-world plan
  measured that a reach beyond ~24 m saturates `metadata.a` in the RGBA16F metadata buffer to `+inf`,
  which silently restores no-falloff. A 140 m reach cannot be expressed this way. It might still
  work as a *local* gradient over the ~58 m window.
- *(b) A custom `BaseEffect`* implementing Beer–Lambert against depth, sampling `main` and applying
  `exp(-k·depth)`. This is the honest way to build the medium the engine lacks (§2), it is a
  supported extension point, and it is a real GLSL shader to own and debug. It is also the option
  most likely to be the right one, precisely because the thing it models does not exist in the
  engine.

Do not start step 5 before steps 1–4 are measured. It is entirely possible that with directional
daylight, real occlusion, bounce, and a tone mapper that does not crush blacks, the gentle albedo
ramp we already have is sufficient and step 5 is not needed at all.

---

## 6. How the deep stays dark AND legible

The owner's actual requirement, answered directly.

**The reason today's deep is illegible is not that it is dark. It is that every pixel receives the
same light.** `final.frag:49` adds `ambientLight` to water, rock, diver and pearl alike. Raising it
brightens the void as fast as it brightens the diver, so the contrast between "thing" and "not
thing" never improves — which is why the ambient-floor attempt of 2026-08-13 was measured as a no-op
and why §2d of the deep-water plan concluded, correctly, that no value works.

The engine's model separates them, and every one of these is a mechanism we are not using:

| The engine's term | Applies to | Effect in the deep |
|---|---|---|
| `sunColor` / `sunAngle` / `sunDistance` | open space, **directionally**, occluded | daylight from above; a surface facing up is lit, a surface facing down is not |
| normal-map integration (`radiance_cascades.frag:286-291`) | everything with a normal map | the rock's relief and the diver's body read as **form**, not as a flat lifted patch |
| `interiorLightTex` + `edgeLight` (`interior.frag`) | **solid objects only** | a rim that states an object's silhouette without lighting the water around it |
| `ambientInteriorLight` | **solid objects only** | a legibility floor on things, with the water left at zero |
| `bounceAccumulation` (0.7, dormant) | occluders near light | the torch bouncing off stone — the deep's own fill light |
| `sourceIntensity` | emitters' own bodies | stops a pearl's disc being the only thing on screen |
| `skyColor` | everything, isotropic | the last resort, and the smallest term |

**So: the water gets almost nothing, and the things in it get four separate sources of light.** The
deep stays dark because the *medium* is dark. Things stay legible because *things* are lit — by
direction, by normal, by rim, by bounce. That is the design the engine is built around, and it is a
strictly better answer than any single number, because "dark" and "legible" stop being the same
dial.

The probe demonstrates the first two rows of that table and nothing else, and it already buys a 3.3×
gain in rock legibility at equal darkness. Rows three to six are step 2, and are unmeasured.

**Where the engine's model runs out, and it does:** it has no participating medium, so it cannot
make daylight weaken *continuously with depth within one frame*. That vertical gradient is
legitimately ours to own — either as a much gentler albedo ramp than today's, or as a custom
`BaseEffect` (§5 step 5b). Saying that plainly is the point: **the gradient is ours; the darkness
and the legibility are the engine's, and we were doing both of its jobs by hand.**

---

## 7. What must not regress

- **The Shallows and the Kelp are bit-identical.** Every step is verified at 20 m first. Brightening
  water that already reads right is a regression, not a fix.
- **The torch is the deep's primary light source** (spec §17, 2026-08-12). Daylight from above must
  not out-light it in the Abyss.
- **The Abyss stays frightening** (spec §11). "Bright enough to read" is the target; "lit" is not.
- **No god rays in the Abyss** (`LightShaftsTest`). `shaftDaylightByZone` and
  `torchIrradianceByZone` are derived from `ambientRed/Green/Blue`; those tables keep their meaning
  and their values through step 1, which is the whole reason the floor was a `max()` and not a
  re-typed table entry.
- **The anglerfish's trap** — motes cool blue, pearls amber, the lure pixel-identical to a pearl in
  both draw and light (`AnglerfishDisguiseTest` compares the `drawLight` argument list as text).
- **60 fps on the cabinet.** Step 2 wakes four dormant shader stages at once. Measure with F3 under
  `EPT_DEV` on the booth machine, in the same pass.
- Determinism, no per-frame allocation, `dive/` untouched. 488 tests green.
- **Every step measured at 140 m with `EPT_DEPTH` against a bit-identical control pair.** Every
  number in this document came from that rig.
