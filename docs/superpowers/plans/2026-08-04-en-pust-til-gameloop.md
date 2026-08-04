# Én Pust Til — Game Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the complete, playable game loop for Én Pust Til using only placeholder shapes, so the feel can be locked before any real art is made.

**Architecture:** A pure Kotlin simulation (`DiveSim`) with zero PulseEngine dependencies holds all game state and maths, unit-tested in isolation. A thin engine shell (`EnPustTil`) reads input, ticks the sim on the fixed update, and renders it with untextured quads. Lighting is applied to those same placeholder shapes via `GlobalIlluminationSystem`, because the deep zones are unjudgeable without it.

**Tech Stack:** Kotlin 2.2.20 · Pulse Engine 0.13.0 · JUnit 5 via `kotlin("test")` · Gradle

**Spec:** [`docs/superpowers/specs/2026-08-04-en-pust-til-design.md`](../specs/2026-08-04-en-pust-til-design.md) — 🔒 LOCKED

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Placeholder assets only.** Untextured `drawQuad` calls and `Lamp` entities. No PNG, no sprite, no font asset. Real art comes after this plan is complete.
- **The simulation must not import `no.njoh.pulseengine`.** Everything in `src/main/kotlin/dive/` is pure Kotlin. This is what makes it testable and it is not negotiable.
- **Determinism.** Fixed 60 Hz tick via `onFixedUpdate` and `engine.data.fixedDeltaTime`. Seeded RNG only — no `Math.random()`, no `kotlin.random.Random.Default`.
- **No physics engine.** The diver is one point mass with drag. Collision is circle-circle distance checks.
- **No per-frame allocation in the pearl, lamp and particle iteration paths.** Pool and reuse. ZGC is configured in `build.gradle.kts:44-48` but must not be leaned on. **HUD text formatting is explicitly exempt** — a handful of strings per frame is noise, and caching values that change every frame is pointless.
- **Controls fit a joystick and two buttons.** Stick + A + B. Keyboard maps to arrows + Z + X for development.
- **Tuning values live in `Tuning.kt` and nowhere else.** No magic numbers in logic or rendering.
- **Run length is 90 seconds. Base air is 20 seconds at ×1.0 burn.**
- **No zero scores.** A first-timer must always bank something.

### Tuning constants (verbatim from spec §4)

| Constant | Value | Note |
|---|---|---|
| `RUN_SECONDS` | 90f | total clock |
| `BASE_AIR_SECONDS` | 20f | at ×1.0 burn |
| `BASE_ASCENT` | 8f | m/s, empty |
| `BASE_DESCENT` | 6f | m/s, empty, passive sink |
| `K_ASCENT` | 40f | 40 mass units ≈ halves ascent speed |
| `K_DESCENT` | 120f | descent gain deliberately weaker than ascent loss |
| `KICK_SPEED_MULT` | 3f | |
| `KICK_AIR_MULT` | 3f | |
| `BLEED_RATE` | 8f | mass units per second while B held |
| `BLACKOUT_KEEP` | 0.10f | fraction of held retained on blackout |
| `DEPTH_BONUS_DIVISOR` | 30f | `bonus = 1 + maxDepth/30` |

### Zone table (verbatim from spec §3)

| Zone | Min depth | Pearl value | Pearl mass | Air burn |
|---|---|---|---|---|
| SHALLOWS | 0 m | 10 | 1 | ×1.0 |
| KELP | 30 m | 25 | 2 | ×1.2 |
| TWILIGHT | 60 m | 60 | 4 | ×1.6 |
| TRENCH | 90 m | 150 | 8 | ×2.0 |
| ABYSS | 120 m | 400 | 16 | ×2.5 |

---

## File Structure

**Created by this plan:**

| File | Responsibility |
|---|---|
| `src/main/kotlin/dive/Tuning.kt` | Every tunable constant. No logic. |
| `src/main/kotlin/dive/Buoyancy.kt` | Mass → ascent/descent speed. Pure functions. |
| `src/main/kotlin/dive/Zone.kt` | Depth zone table and depth→zone lookup. |
| `src/main/kotlin/dive/Scoring.kt` | Depth bonus, bank, blackout bank. Pure functions. |
| `src/main/kotlin/dive/Pearl.kt` | Pearl data + the seeded column generator. |
| `src/main/kotlin/dive/DiveInput.kt` | Input snapshot passed into the sim. |
| `src/main/kotlin/dive/DiveSim.kt` | All mutable run state and the tick function. |
| `src/main/kotlin/dive/Anglerfish.kt` | Lure behaviour and the "drifts toward you" tell. |
| `src/main/kotlin/EnPustTil.kt` | Engine shell: input → sim → render. Entry point. |
| `src/main/kotlin/render/DiveRenderer.kt` | Draws sim state as placeholder quads. |
| `src/main/kotlin/render/Hud.kt` | Held/banked, air ring, depth tape, no-return marker. |
| `src/main/kotlin/render/DiveLighting.kt` | GI system setup and pearl→Lamp syncing. |
| `src/test/kotlin/dive/*Test.kt` | Unit tests for every pure module above. |

**Modified:** `build.gradle.kts` (test deps), `src/main/resources/application.cfg` (window/fps).

`GameTemplate.kt` and `examples/` are left untouched as reference.

---

### Task 1: Test infrastructure and tuning constants

**Files:**
- Modify: `build.gradle.kts:16-22`
- Create: `src/main/kotlin/dive/Tuning.kt`
- Test: `src/test/kotlin/dive/TuningTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `object Tuning` with the constants listed in Global Constraints, all `Float` except `BLACKOUT_KEEP` (also `Float`)

- [ ] **Step 1: Add the test dependency**

In `build.gradle.kts`, replace the `dependencies` block and add a `test` task config after the `kotlin` block:

```kotlin
dependencies {
    implementation("no.njoh:pulse-engine:0.13.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/dive/TuningTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertEquals

class TuningTest {
    @Test
    fun `run length is 90 seconds`() {
        assertEquals(90f, Tuning.RUN_SECONDS)
    }

    @Test
    fun `base air is 20 seconds`() {
        assertEquals(20f, Tuning.BASE_AIR_SECONDS)
    }

    @Test
    fun `blackout keeps ten percent`() {
        assertEquals(0.10f, Tuning.BLACKOUT_KEEP)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests 'dive.TuningTest'`
Expected: FAIL — `Unresolved reference: Tuning`

- [ ] **Step 4: Create the tuning object**

Create `src/main/kotlin/dive/Tuning.kt`:

```kotlin
package dive

/** Every tunable value in the game. No logic lives here. */
object Tuning
{
    // Run structure
    const val RUN_SECONDS = 90f
    const val BASE_AIR_SECONDS = 20f

    // Buoyancy
    const val BASE_ASCENT = 8f
    const val BASE_DESCENT = 6f
    const val K_ASCENT = 40f
    const val K_DESCENT = 120f

    // Kick
    const val KICK_SPEED_MULT = 3f
    const val KICK_AIR_MULT = 3f

    // Ballast
    const val BLEED_RATE = 8f

    // Scoring
    const val BLACKOUT_KEEP = 0.10f
    const val DEPTH_BONUS_DIVISOR = 30f

    // Horizontal movement
    const val SWIM_SPEED = 5f

    // World
    const val COLUMN_HALF_WIDTH = 40f
    const val MAX_DEPTH = 160f
    const val SURFACE_DEPTH = 0f
    const val PEARL_PICKUP_RADIUS = 2.5f
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'dive.TuningTest'`
Expected: PASS, 3 tests

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/kotlin/dive/Tuning.kt src/test/kotlin/dive/TuningTest.kt
git commit -m "test: add JUnit5 and tuning constants"
```

---

### Task 2: Buoyancy model

The riskiest maths in the design. Weight must slow ascent and speed descent, with descent gain deliberately weaker.

**Files:**
- Create: `src/main/kotlin/dive/Buoyancy.kt`
- Test: `src/test/kotlin/dive/BuoyancyTest.kt`

**Interfaces:**
- Consumes: `Tuning` (Task 1)
- Produces: `Buoyancy.ascentSpeed(mass: Float): Float`, `Buoyancy.descentSpeed(mass: Float): Float`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dive/BuoyancyTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuoyancyTest {
    @Test
    fun `empty diver ascends at base speed`() {
        assertEquals(Tuning.BASE_ASCENT, Buoyancy.ascentSpeed(0f), 0.001f)
    }

    @Test
    fun `mass equal to K_ASCENT halves ascent speed`() {
        val expected = Tuning.BASE_ASCENT / 2f
        assertEquals(expected, Buoyancy.ascentSpeed(Tuning.K_ASCENT), 0.001f)
    }

    @Test
    fun `ascent speed decreases monotonically with mass`() {
        var previous = Buoyancy.ascentSpeed(0f)
        for (mass in 1..200) {
            val current = Buoyancy.ascentSpeed(mass.toFloat())
            assertTrue(current < previous, "ascent must decrease at mass=$mass")
            previous = current
        }
    }

    @Test
    fun `empty diver descends at base speed`() {
        assertEquals(Tuning.BASE_DESCENT, Buoyancy.descentSpeed(0f), 0.001f)
    }

    @Test
    fun `mass increases descent speed`() {
        assertTrue(Buoyancy.descentSpeed(40f) > Buoyancy.descentSpeed(0f))
    }

    @Test
    fun `descent gain is weaker than ascent loss`() {
        val mass = 40f
        val ascentLossRatio = Buoyancy.ascentSpeed(mass) / Tuning.BASE_ASCENT
        val descentGainRatio = Tuning.BASE_DESCENT / Buoyancy.descentSpeed(mass)
        assertTrue(
            ascentLossRatio < descentGainRatio,
            "ascent must be penalised more than descent is rewarded"
        )
    }

    @Test
    fun `ascent speed never reaches zero`() {
        assertTrue(Buoyancy.ascentSpeed(100_000f) > 0f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'dive.BuoyancyTest'`
Expected: FAIL — `Unresolved reference: Buoyancy`

- [ ] **Step 3: Implement the buoyancy model**

Create `src/main/kotlin/dive/Buoyancy.kt`:

```kotlin
package dive

/**
 * Weight model. Carrying pearls makes the diver sink faster and rise slower.
 * Descent gain is deliberately weaker than ascent loss (see K_DESCENT > K_ASCENT),
 * so greed costs more on the way home than it saves on the way down.
 */
object Buoyancy
{
    fun ascentSpeed(mass: Float) = Tuning.BASE_ASCENT / (1f + mass / Tuning.K_ASCENT)

    fun descentSpeed(mass: Float) = Tuning.BASE_DESCENT * (1f + mass / Tuning.K_DESCENT)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'dive.BuoyancyTest'`
Expected: PASS, 7 tests

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dive/Buoyancy.kt src/test/kotlin/dive/BuoyancyTest.kt
git commit -m "feat: buoyancy model where mass slows ascent"
```

---

### Task 3: Zones and scoring

**Files:**
- Create: `src/main/kotlin/dive/Zone.kt`, `src/main/kotlin/dive/Scoring.kt`
- Test: `src/test/kotlin/dive/ZoneTest.kt`, `src/test/kotlin/dive/ScoringTest.kt`

**Interfaces:**
- Consumes: `Tuning` (Task 1)
- Produces:
  - `enum class Zone(minDepth: Float, pearlValue: Int, pearlMass: Float, airBurn: Float)` with `Zone.at(depth: Float): Zone`
  - `Scoring.depthBonus(maxDepth: Float): Float`
  - `Scoring.bank(held: Int, maxDepth: Float): Int`
  - `Scoring.blackoutBank(held: Int): Int`

- [ ] **Step 1: Write the failing zone test**

Create `src/test/kotlin/dive/ZoneTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertEquals

class ZoneTest {
    @Test
    fun `surface is shallows`() = assertEquals(Zone.SHALLOWS, Zone.at(0f))

    @Test
    fun `above surface clamps to shallows`() = assertEquals(Zone.SHALLOWS, Zone.at(-5f))

    @Test
    fun `boundaries are inclusive at the lower edge`() {
        assertEquals(Zone.KELP, Zone.at(30f))
        assertEquals(Zone.TWILIGHT, Zone.at(60f))
        assertEquals(Zone.TRENCH, Zone.at(90f))
        assertEquals(Zone.ABYSS, Zone.at(120f))
    }

    @Test
    fun `just above a boundary stays in the shallower zone`() {
        assertEquals(Zone.SHALLOWS, Zone.at(29.99f))
        assertEquals(Zone.TRENCH, Zone.at(119.99f))
    }

    @Test
    fun `very deep is abyss`() = assertEquals(Zone.ABYSS, Zone.at(500f))

    @Test
    fun `pearl value increases with depth`() {
        assertEquals(10, Zone.SHALLOWS.pearlValue)
        assertEquals(400, Zone.ABYSS.pearlValue)
    }

    @Test
    fun `air burns faster deeper`() {
        assertEquals(1.0f, Zone.SHALLOWS.airBurn)
        assertEquals(2.5f, Zone.ABYSS.airBurn)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dive.ZoneTest'`
Expected: FAIL — `Unresolved reference: Zone`

- [ ] **Step 3: Implement Zone**

Create `src/main/kotlin/dive/Zone.kt`:

```kotlin
package dive

/**
 * Depth bands. Each zone must eventually differ in HOW the diver moves,
 * not only in value and colour — a zone that changes only a number is a reskin.
 */
enum class Zone(
    val minDepth: Float,
    val pearlValue: Int,
    val pearlMass: Float,
    val airBurn: Float
) {
    SHALLOWS(0f,   10,  1f,  1.0f),
    KELP    (30f,  25,  2f,  1.2f),
    TWILIGHT(60f,  60,  4f,  1.6f),
    TRENCH  (90f,  150, 8f,  2.0f),
    ABYSS   (120f, 400, 16f, 2.5f);

    companion object
    {
        fun at(depth: Float): Zone = entries.lastOrNull { depth >= it.minDepth } ?: SHALLOWS
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'dive.ZoneTest'`
Expected: PASS, 7 tests

- [ ] **Step 5: Write the failing scoring test**

Create `src/test/kotlin/dive/ScoringTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    @Test
    fun `surface dive has bonus of one`() {
        assertEquals(1f, Scoring.depthBonus(0f), 0.001f)
    }

    @Test
    fun `thirty metres gives double`() {
        assertEquals(2f, Scoring.depthBonus(30f), 0.001f)
    }

    @Test
    fun `one hundred and twenty metres gives five times`() {
        assertEquals(5f, Scoring.depthBonus(120f), 0.001f)
    }

    /** Spec section 3: the two degenerate strategies must lose by arithmetic. */
    @Test
    fun `deep and empty scores nothing`() {
        assertEquals(0, Scoring.bank(held = 0, maxDepth = 120f))
    }

    @Test
    fun `full and shallow loses badly to deep and loaded`() {
        val shallowSweep = Scoring.bank(held = 300, maxDepth = 30f)
        val deepLoaded = Scoring.bank(held = 2400, maxDepth = 120f)
        assertEquals(600, shallowSweep)
        assertEquals(12000, deepLoaded)
        assertTrue(deepLoaded > shallowSweep * 10)
    }

    @Test
    fun `blackout keeps ten percent with no depth bonus`() {
        assertEquals(240, Scoring.blackoutBank(2400))
    }

    @Test
    fun `blackout on a tiny haul still rounds down to zero without crashing`() {
        assertEquals(0, Scoring.blackoutBank(5))
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew test --tests 'dive.ScoringTest'`
Expected: FAIL — `Unresolved reference: Scoring`

- [ ] **Step 7: Implement Scoring**

Create `src/main/kotlin/dive/Scoring.kt`:

```kotlin
package dive

/**
 * BANKED += HELD * (1 + maxDepthReached / 30)
 *
 * The bonus keys off the deepest point REACHED, not where pearls were collected.
 * That is what makes the expert route work: dive fast and empty to lock the
 * multiplier, then collect on the way up.
 */
object Scoring
{
    fun depthBonus(maxDepth: Float) = 1f + maxDepth / Tuning.DEPTH_BONUS_DIVISOR

    fun bank(held: Int, maxDepth: Float) = (held * depthBonus(maxDepth)).toInt()

    fun blackoutBank(held: Int) = (held * Tuning.BLACKOUT_KEEP).toInt()
}
```

- [ ] **Step 8: Run it to verify it passes**

Run: `./gradlew test --tests 'dive.ScoringTest'`
Expected: PASS, 7 tests

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/dive/Zone.kt src/main/kotlin/dive/Scoring.kt src/test/kotlin/dive/ZoneTest.kt src/test/kotlin/dive/ScoringTest.kt
git commit -m "feat: depth zones and bank formula"
```

---

### Task 4: Pearls and the seeded water column

**Files:**
- Create: `src/main/kotlin/dive/Pearl.kt`
- Test: `src/test/kotlin/dive/PearlTest.kt`

**Interfaces:**
- Consumes: `Tuning`, `Zone`
- Produces:
  - `class Pearl(val x: Float, val depth: Float, val zone: Zone) { var collected = false }`
  - `object PearlColumn { fun generate(seed: Long): MutableList<Pearl> }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dive/PearlTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PearlTest {
    @Test
    fun `same seed produces identical columns`() {
        val a = PearlColumn.generate(seed = 42L)
        val b = PearlColumn.generate(seed = 42L)
        assertEquals(a.size, b.size)
        a.indices.forEach { i ->
            assertEquals(a[i].x, b[i].x, 0.0001f)
            assertEquals(a[i].depth, b[i].depth, 0.0001f)
        }
    }

    @Test
    fun `different seeds produce different columns`() {
        val a = PearlColumn.generate(seed = 1L)
        val b = PearlColumn.generate(seed = 2L)
        val same = a.indices.count { a[it].depth == b[it].depth }
        assertTrue(same < a.size, "seeds must differ")
    }

    @Test
    fun `every zone has pearls`() {
        val pearls = PearlColumn.generate(seed = 7L)
        Zone.entries.forEach { zone ->
            assertTrue(pearls.any { it.zone == zone }, "no pearls in $zone")
        }
    }

    @Test
    fun `pearls sit within the column bounds`() {
        PearlColumn.generate(seed = 7L).forEach {
            assertTrue(it.x >= -Tuning.COLUMN_HALF_WIDTH && it.x <= Tuning.COLUMN_HALF_WIDTH)
            assertTrue(it.depth >= 0f && it.depth <= Tuning.MAX_DEPTH)
        }
    }

    @Test
    fun `pearl zone matches its depth`() {
        PearlColumn.generate(seed = 7L).forEach {
            assertEquals(Zone.at(it.depth), it.zone)
        }
    }

    @Test
    fun `pearls start uncollected`() {
        assertTrue(PearlColumn.generate(seed = 7L).none { it.collected })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dive.PearlTest'`
Expected: FAIL — `Unresolved reference: PearlColumn`

- [ ] **Step 3: Implement Pearl and the generator**

Create `src/main/kotlin/dive/Pearl.kt`:

```kotlin
package dive

import kotlin.random.Random

/**
 * A pearl persists where it is until collected. That is what makes the ascent
 * the interesting half of a dive — you deliberately leave pearls on the way
 * down to sweep up on the way home.
 */
class Pearl(val x: Float, val depth: Float, val zone: Zone)
{
    var collected = false

    val value get() = zone.pearlValue
    val mass  get() = zone.pearlMass
}

/**
 * Seeded generator for the hand-authorable water column. Placeholder for now:
 * a fixed count per zone at seeded positions. Replaced later by scene-editor
 * authored layouts, but the seeded version keeps every player on an identical
 * column, which is the score-attack pillar.
 */
object PearlColumn
{
    private const val PEARLS_PER_ZONE = 14

    fun generate(seed: Long): MutableList<Pearl>
    {
        val rng = Random(seed)
        val pearls = ArrayList<Pearl>(Zone.entries.size * PEARLS_PER_ZONE)

        Zone.entries.forEach { zone ->
            val top = zone.minDepth
            val bottom = nextZoneDepth(zone)
            repeat(PEARLS_PER_ZONE) {
                val depth = top + rng.nextFloat() * (bottom - top)
                val x = (rng.nextFloat() * 2f - 1f) * Tuning.COLUMN_HALF_WIDTH
                pearls += Pearl(x, depth, Zone.at(depth))
            }
        }
        return pearls
    }

    private fun nextZoneDepth(zone: Zone): Float
    {
        val next = Zone.entries.getOrNull(zone.ordinal + 1)
        return next?.minDepth ?: Tuning.MAX_DEPTH
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'dive.PearlTest'`
Expected: PASS, 6 tests

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dive/Pearl.kt src/test/kotlin/dive/PearlTest.kt
git commit -m "feat: seeded pearl column"
```

---

### Task 5: The dive simulation

The heart of the game. All mutable run state and one tick function.

**Files:**
- Create: `src/main/kotlin/dive/DiveInput.kt`, `src/main/kotlin/dive/DiveSim.kt`
- Test: `src/test/kotlin/dive/DiveSimTest.kt`

**Interfaces:**
- Consumes: `Tuning`, `Buoyancy`, `Zone`, `Scoring`, `Pearl`, `PearlColumn`
- Produces:
  - `data class DiveInput(val horizontal: Float, val vertical: Float, val kick: Boolean, val bleed: Boolean)` — `vertical` is -1 up / +1 down / 0 neutral
  - `class DiveSim(seed: Long)` with public read state `clock`, `banked`, `held`, `heldMass`, `depth`, `x`, `air`, `maxDepthThisDive`, `pearls`, `runOver`, `lastBankAmount`, `zone`, and `fun tick(dt: Float, input: DiveInput)`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dive/DiveSimTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DiveSimTest {
    private val idle = DiveInput(horizontal = 0f, vertical = 0f, kick = false, bleed = false)
    private val swimUp = idle.copy(vertical = -1f)
    private val swimDown = idle.copy(vertical = 1f)

    private fun run(sim: DiveSim, seconds: Float, input: DiveInput = idle) {
        val dt = 1f / 60f
        repeat((seconds / dt).toInt()) { sim.tick(dt, input) }
    }

    @Test
    fun `diver starts at the surface with full air and nothing held`() {
        val sim = DiveSim(seed = 1L)
        assertEquals(0f, sim.depth, 0.001f)
        assertEquals(Tuning.BASE_AIR_SECONDS, sim.air, 0.001f)
        assertEquals(0, sim.held)
        assertEquals(0, sim.banked)
    }

    @Test
    fun `diver sinks passively`() {
        val sim = DiveSim(seed = 1L)
        run(sim, 1f)
        assertTrue(sim.depth > 0f, "diver should sink without input")
    }

    @Test
    fun `kick makes the diver descend faster`() {
        val slow = DiveSim(seed = 1L)
        val fast = DiveSim(seed = 1L)
        run(slow, 1f, swimDown)
        run(fast, 1f, swimDown.copy(kick = true))
        assertTrue(fast.depth > slow.depth)
    }

    @Test
    fun `neutral stick sinks but kick does nothing without a direction`() {
        val drifting = DiveSim(seed = 1L)
        val kicking = DiveSim(seed = 1L)
        run(drifting, 1f, idle)
        run(kicking, 1f, idle.copy(kick = true))
        assertEquals(drifting.depth, kicking.depth, 0.001f)
    }

    @Test
    fun `kick burns air faster`() {
        val slow = DiveSim(seed = 1L)
        val fast = DiveSim(seed = 1L)
        run(slow, 1f, swimDown)
        run(fast, 1f, swimDown.copy(kick = true))
        assertTrue(fast.air < slow.air)
    }

    @Test
    fun `max depth this dive is tracked`() {
        val sim = DiveSim(seed = 1L)
        run(sim, 2f, swimDown.copy(kick = true))
        assertTrue(sim.maxDepthThisDive >= sim.depth)
    }

    @Test
    fun `running out of air blacks out and banks ten percent`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 2400, mass = 0f)
        run(sim, 40f, swimDown.copy(kick = true))
        assertEquals(0, sim.held, "held must be cleared after blackout")
        assertTrue(sim.banked in 200..260, "expected ~240, got ${sim.banked}")
    }

    @Test
    fun `surfacing banks held times depth bonus and refills air`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(120f)
        sim.debugSetHeld(count = 2400, mass = 0f)
        sim.debugSurface()
        assertEquals(12000, sim.banked)
        assertEquals(0, sim.held)
        assertEquals(Tuning.BASE_AIR_SECONDS, sim.air, 0.001f)
    }

    @Test
    fun `depth bonus resets between dives`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(120f)
        sim.debugSetHeld(count = 100, mass = 0f)
        sim.debugSurface()
        val firstBank = sim.banked

        sim.debugSetDepth(0f)
        sim.debugSetHeld(count = 100, mass = 0f)
        sim.debugSurface()
        val secondBank = sim.banked - firstBank

        assertEquals(100, secondBank, "second dive must not inherit the first dive's bonus")
    }

    @Test
    fun `held pearls are lost when the clock runs out`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 5000, mass = 0f)
        run(sim, Tuning.RUN_SECONDS + 1f)
        assertTrue(sim.runOver)
        assertEquals(0, sim.banked, "unbanked pearls must not be scored at 0:00")
    }

    @Test
    fun `bleeding reduces held mass and value`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 1000, mass = 80f)
        run(sim, 1f, idle.copy(bleed = true))
        assertTrue(sim.heldMass < 80f, "mass must drop while bleeding")
        assertTrue(sim.held < 1000, "value must drop while bleeding")
        assertTrue(sim.held > 0, "one second must not dump everything")
    }

    @Test
    fun `bleeding cannot go below zero`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 10, mass = 1f)
        run(sim, 5f, idle.copy(bleed = true))
        assertEquals(0, sim.held)
        assertEquals(0f, sim.heldMass, 0.001f)
    }

    @Test
    fun `collecting a pearl adds value and mass`() {
        val sim = DiveSim(seed = 1L)
        val pearl = sim.pearls.first { it.zone == Zone.SHALLOWS }
        sim.debugMoveTo(pearl.x, pearl.depth)
        sim.tick(1f / 60f, idle)
        assertTrue(pearl.collected)
        assertEquals(pearl.value, sim.held)
        assertEquals(pearl.mass, sim.heldMass, 0.001f)
    }

    @Test
    fun `a collected pearl cannot be collected twice`() {
        val sim = DiveSim(seed = 1L)
        val pearl = sim.pearls.first { it.zone == Zone.SHALLOWS }
        sim.debugMoveTo(pearl.x, pearl.depth)
        sim.tick(1f / 60f, idle)
        val afterFirst = sim.held
        sim.tick(1f / 60f, idle)
        assertEquals(afterFirst, sim.held)
    }

    @Test
    fun `a loaded diver ascends slower than an empty one`() {
        val empty = DiveSim(seed = 1L)
        val loaded = DiveSim(seed = 1L)
        empty.debugSetDepth(50f)
        loaded.debugSetDepth(50f)
        loaded.debugSetHeld(count = 0, mass = 80f)

        run(empty, 1f, swimUp)
        run(loaded, 1f, swimUp)

        assertTrue(loaded.depth > empty.depth, "loaded diver must still be deeper")
    }

    @Test
    fun `swimming up beats passive sink`() {
        val sinking = DiveSim(seed = 1L)
        val rising = DiveSim(seed = 1L)
        sinking.debugSetDepth(50f)
        rising.debugSetDepth(50f)

        run(sinking, 1f, idle)
        run(rising, 1f, swimUp)

        assertTrue(rising.depth < sinking.depth, "swimming up must rise against the sink")
    }

    @Test
    fun `kick boosts whichever direction is held - it is the escape tool too`() {
        val slowUp = DiveSim(seed = 1L)
        val fastUp = DiveSim(seed = 1L)
        slowUp.debugSetDepth(80f)
        fastUp.debugSetDepth(80f)

        run(slowUp, 1f, swimUp)
        run(fastUp, 1f, swimUp.copy(kick = true))

        assertTrue(fastUp.depth < slowUp.depth, "kick must accelerate an ascent, not only a descent")
    }

    @Test
    fun `kicking upward still burns air at the kick rate`() {
        val drift = DiveSim(seed = 1L)
        val kicking = DiveSim(seed = 1L)
        drift.debugSetDepth(80f)
        kicking.debugSetDepth(80f)

        run(drift, 1f, swimUp)
        run(kicking, 1f, swimUp.copy(kick = true))

        assertTrue(kicking.air < drift.air, "escaping must cost air, not be free")
    }

    @Test
    fun `run is not over before the clock expires`() {
        val sim = DiveSim(seed = 1L)
        run(sim, 10f)
        assertFalse(sim.runOver)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dive.DiveSimTest'`
Expected: FAIL — `Unresolved reference: DiveInput`

- [ ] **Step 3: Create the input snapshot**

Create `src/main/kotlin/dive/DiveInput.kt`:

```kotlin
package dive

/**
 * One frame of player intent.
 *
 * The stick is a full 2D swim direction. [kick] multiplies speed in whichever
 * direction you are heading, which is why the spec calls it both the descent
 * tool and the escape tool — and why escaping costs just as much air as diving.
 *
 * @param vertical -1 = swim up, +1 = swim down, 0 = neutral (passive sink)
 */
data class DiveInput(
    val horizontal: Float,
    val vertical: Float,
    val kick: Boolean,
    val bleed: Boolean
)
{
    companion object { val NONE = DiveInput(0f, 0f, kick = false, bleed = false) }
}
```

- [ ] **Step 4: Implement the simulation**

Create `src/main/kotlin/dive/DiveSim.kt`:

```kotlin
package dive

import kotlin.math.max
import kotlin.math.min

/**
 * All run state and the tick function. Contains no engine code by design —
 * everything here is unit-testable pure Kotlin.
 */
class DiveSim(seed: Long)
{
    val pearls: MutableList<Pearl> = PearlColumn.generate(seed)

    var clock = Tuning.RUN_SECONDS;    private set
    var banked = 0;                    private set
    var held = 0;                      private set
    var heldMass = 0f;                 private set
    var depth = 0f;                    private set
    var x = 0f;                        private set
    var air = Tuning.BASE_AIR_SECONDS; private set
    var maxDepthThisDive = 0f;         private set
    var runOver = false;               private set
    var lastBankAmount = 0;            private set
    var blackedOut = false;            private set

    val zone get() = Zone.at(depth)

    fun tick(dt: Float, input: DiveInput)
    {
        if (runOver) return

        clock -= dt
        if (clock <= 0f)
        {
            clock = 0f
            runOver = true
            held = 0          // anything still held at 0:00 is lost
            heldMass = 0f
            return
        }

        updateBleed(dt, input)
        updateMovement(dt, input)
        updateAir(dt, input)
        collectPearls()

        if (depth <= Tuning.SURFACE_DEPTH) surface()
    }

    private fun updateBleed(dt: Float, input: DiveInput)
    {
        if (!input.bleed || heldMass <= 0f) return

        val massBefore = heldMass
        val massDropped = min(Tuning.BLEED_RATE * dt, heldMass)
        val fraction = massDropped / massBefore

        heldMass = max(0f, heldMass - massDropped)
        held = max(0, held - (held * fraction).toInt())

        if (heldMass <= 0.001f) { heldMass = 0f; held = 0 }
    }

    private fun updateMovement(dt: Float, input: DiveInput)
    {
        val boost = if (input.kick) Tuning.KICK_SPEED_MULT else 1f

        x = (x + input.horizontal * Tuning.SWIM_SPEED * boost * dt)
            .coerceIn(-Tuning.COLUMN_HALF_WIDTH, Tuning.COLUMN_HALF_WIDTH)

        // Neutral stick sinks passively — the diver is never truly still.
        // Swimming up is limited by ascentSpeed, which is what makes weight bite.
        val vertical = when
        {
            input.vertical < 0f -> input.vertical * Buoyancy.ascentSpeed(heldMass) * boost
            input.vertical > 0f -> input.vertical * Buoyancy.descentSpeed(heldMass) * boost
            else                -> Buoyancy.descentSpeed(heldMass)
        }

        depth = (depth + vertical * dt).coerceIn(0f, Tuning.MAX_DEPTH)
        maxDepthThisDive = max(maxDepthThisDive, depth)
    }

    private fun updateAir(dt: Float, input: DiveInput)
    {
        val burn = zone.airBurn * (if (input.kick) Tuning.KICK_AIR_MULT else 1f)
        air -= dt * burn
        if (air <= 0f) blackout()
    }

    private fun collectPearls()
    {
        pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            val dx = pearl.x - x
            val dy = pearl.depth - depth
            if (dx * dx + dy * dy <= Tuning.PEARL_PICKUP_RADIUS * Tuning.PEARL_PICKUP_RADIUS)
            {
                pearl.collected = true
                held += pearl.value
                heldMass += pearl.mass
            }
        }
    }

    private fun blackout()
    {
        blackedOut = true
        lastBankAmount = Scoring.blackoutBank(held)
        banked += lastBankAmount
        resetDive()
    }

    private fun surface()
    {
        if (held > 0 || maxDepthThisDive > 0f)
        {
            lastBankAmount = Scoring.bank(held, maxDepthThisDive)
            banked += lastBankAmount
        }
        resetDive()
    }

    private fun resetDive()
    {
        held = 0
        heldMass = 0f
        air = Tuning.BASE_AIR_SECONDS
        maxDepthThisDive = 0f
        depth = 0f
    }

    // --- Test hooks ---------------------------------------------------------

    internal fun debugSetDepth(value: Float) { depth = value; maxDepthThisDive = max(maxDepthThisDive, value) }
    internal fun debugSetHeld(count: Int, mass: Float) { held = count; heldMass = mass }
    internal fun debugMoveTo(newX: Float, newDepth: Float) { x = newX; depth = newDepth }
    internal fun debugSurface() { surface() }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests 'dive.DiveSimTest'`
Expected: PASS, 15 tests

If `blackout banks ten percent` fails because the diver surfaces before the air runs out, check that `debugSetHeld` mass is `0f` so the diver keeps sinking rather than floating up.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew test`
Expected: PASS, all tests green

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dive/DiveInput.kt src/main/kotlin/dive/DiveSim.kt src/test/kotlin/dive/DiveSimTest.kt
git commit -m "feat: dive simulation with banking, blackout and bleed"
```

---

### Task 6: Playable prototype — THE FEEL GATE

**This is the task the whole design rests on.** Everything renders as untextured
quads. No lighting, no HUD polish, no zones visually distinguished. The single
question being answered: **does weight feel like tension or like punishment?**

**Files:**
- Create: `src/main/kotlin/EnPustTil.kt`, `src/main/kotlin/render/DiveRenderer.kt`
- Modify: `src/main/resources/application.cfg`

**Interfaces:**
- Consumes: `DiveSim`, `DiveInput` (Task 5), `Zone`, `Tuning`
- Produces: `class EnPustTil : PulseEngineGame()`, `object DiveRenderer { fun render(engine, surface, sim) }`

- [ ] **Step 1: Configure the window**

Replace `src/main/resources/application.cfg`:

```
gameName = EnPustTil
targetFps = 120
windowWidth = 1200
windowHeight = 900
screenMode = WINDOWED
logLevel = DEBUG
```

- [ ] **Step 2: Write the renderer**

Create `src/main/kotlin/render/DiveRenderer.kt`:

```kotlin
package render

import dive.DiveSim
import dive.Tuning
import dive.Zone
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color

/**
 * Placeholder rendering. Everything is an untextured quad:
 *   diver  = white square, grows with held mass
 *   pearl  = small amber square
 *   zones  = flat horizontal bands
 * Real art replaces this after the loop is locked.
 */
object DiveRenderer
{
    private const val PIXELS_PER_METRE = 5f
    private const val DIVER_BASE_SIZE = 14f
    private const val PEARL_SIZE = 6f

    private val zoneColors = mapOf(
        Zone.SHALLOWS to Color(0.20f, 0.55f, 0.80f),
        Zone.KELP     to Color(0.14f, 0.42f, 0.62f),
        Zone.TWILIGHT to Color(0.09f, 0.28f, 0.46f),
        Zone.TRENCH   to Color(0.05f, 0.16f, 0.30f),
        Zone.ABYSS    to Color(0.02f, 0.06f, 0.14f)
    )

    private val pearlColor = Color(1f, 0.78f, 0.35f)
    private val diverColor = Color(1f, 1f, 1f)

    fun render(surface: Surface, sim: DiveSim, screenWidth: Float, screenHeight: Float)
    {
        val centreX = screenWidth * 0.5f
        // Camera follows the diver vertically, keeping them at 40% screen height.
        val cameraDepth = sim.depth - (screenHeight * 0.4f) / PIXELS_PER_METRE

        drawZoneBands(surface, cameraDepth, screenWidth, screenHeight)
        drawPearls(surface, sim, centreX, cameraDepth, screenHeight)
        drawDiver(surface, sim, centreX, cameraDepth)
    }

    private fun drawZoneBands(surface: Surface, cameraDepth: Float, w: Float, h: Float)
    {
        Zone.entries.forEach { zone ->
            val top = (zone.minDepth - cameraDepth) * PIXELS_PER_METRE
            val bottom = nextZoneTop(zone, cameraDepth)
            if (bottom < 0f || top > h) return@forEach
            surface.setDrawColor(zoneColors.getValue(zone))
            surface.drawQuad(0f, top, w, bottom - top)
        }
    }

    private fun nextZoneTop(zone: Zone, cameraDepth: Float): Float
    {
        val next = Zone.entries.getOrNull(zone.ordinal + 1)
        val depth = next?.minDepth ?: Tuning.MAX_DEPTH
        return (depth - cameraDepth) * PIXELS_PER_METRE
    }

    private fun drawPearls(surface: Surface, sim: DiveSim, centreX: Float, cameraDepth: Float, h: Float)
    {
        surface.setDrawColor(pearlColor)
        sim.pearls.forEach { pearl ->
            if (pearl.collected) return@forEach
            val screenY = (pearl.depth - cameraDepth) * PIXELS_PER_METRE
            if (screenY < -PEARL_SIZE || screenY > h + PEARL_SIZE) return@forEach
            val screenX = centreX + pearl.x * PIXELS_PER_METRE
            surface.drawQuad(screenX - PEARL_SIZE * 0.5f, screenY - PEARL_SIZE * 0.5f, PEARL_SIZE, PEARL_SIZE)
        }
    }

    private fun drawDiver(surface: Surface, sim: DiveSim, centreX: Float, cameraDepth: Float)
    {
        // Size scales with load so weight is visible as well as felt.
        val size = DIVER_BASE_SIZE + sim.heldMass * 0.15f
        val screenX = centreX + sim.x * PIXELS_PER_METRE
        val screenY = (sim.depth - cameraDepth) * PIXELS_PER_METRE
        surface.setDrawColor(diverColor)
        surface.drawQuad(screenX - size * 0.5f, screenY - size * 0.5f, size, size)
    }
}
```

- [ ] **Step 3: Write the engine shell**

Create `src/main/kotlin/EnPustTil.kt`:

```kotlin
import dive.DiveInput
import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.PulseEngineGame
import no.njoh.pulseengine.core.input.GamepadAxis
import no.njoh.pulseengine.core.input.Key
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.modules.metrics.MetricViewer
import render.DiveRenderer

fun main() = PulseEngine.run<EnPustTil>()

/**
 * Engine shell. Reads input, ticks the pure simulation on the fixed update,
 * and draws it. All game logic lives in the `dive` package.
 */
class EnPustTil : PulseEngineGame()
{
    private var sim = DiveSim(seed = DAILY_SEED)

    override fun onCreate()
    {
        engine.service.add(MetricViewer()) // F3
        engine.gfx.mainSurface.setBackgroundColor(0.02f, 0.06f, 0.14f, 1f)
        engine.config.fixedTickRate = 60f
    }

    override fun onFixedUpdate()
    {
        sim.tick(engine.data.fixedDeltaTime, readInput())
    }

    override fun onUpdate()
    {
        if (engine.input.wasClicked(Key.R) || (sim.runOver && engine.input.wasClicked(Key.SPACE)))
            sim = DiveSim(seed = DAILY_SEED)
    }

    override fun onRender()
    {
        val surface = engine.gfx.mainSurface
        DiveRenderer.render(surface, sim, engine.window.width.toFloat(), engine.window.height.toFloat())
        drawDebugReadout()
    }

    /**
     * Stick is a full 2D swim direction; A boosts whichever way you point.
     * Deadzone is applied so a drifting analogue stick does not stop the
     * passive sink, which is the game's baseline state.
     */
    private fun readInput(): DiveInput
    {
        val pad = engine.input.gamepads.firstOrNull()
        val padX = pad?.getAxis(GamepadAxis.LEFT_X)?.deadzone() ?: 0f
        val padY = pad?.getAxis(GamepadAxis.LEFT_Y)?.deadzone() ?: 0f

        val keyX = axis(Key.LEFT, Key.RIGHT)
        val keyY = axis(Key.UP, Key.DOWN)

        return DiveInput(
            horizontal = if (padX != 0f) padX else keyX,
            vertical   = if (padY != 0f) padY else keyY,
            kick       = engine.input.isPressed(Key.Z),
            bleed      = engine.input.isPressed(Key.X)
        )
    }

    private fun axis(negative: Key, positive: Key) = when
    {
        engine.input.isPressed(negative) -> -1f
        engine.input.isPressed(positive) ->  1f
        else -> 0f
    }

    private fun Float.deadzone() = if (kotlin.math.abs(this) < STICK_DEADZONE) 0f else this

    /** Temporary numeric readout. Replaced by the real HUD in Task 8. */
    private fun drawDebugReadout()
    {
        val s = engine.gfx.mainSurface
        s.setDrawColor(Color.WHITE)
        s.drawText("CLOCK  %.1f".format(sim.clock), 20f, 30f, fontSize = 24f)
        s.drawText("DEPTH  %.1f m".format(sim.depth), 20f, 60f, fontSize = 24f)
        s.drawText("AIR    %.1f".format(sim.air), 20f, 90f, fontSize = 24f)
        s.drawText("HELD   ${sim.held}  (mass %.1f)".format(sim.heldMass), 20f, 120f, fontSize = 24f)
        s.drawText("BANKED ${sim.banked}", 20f, 150f, fontSize = 24f)
        s.drawText("ZONE   ${sim.zone}", 20f, 180f, fontSize = 24f)
        if (sim.runOver) s.drawText("RUN OVER - SPACE to restart", 20f, 220f, fontSize = 32f)
    }

    private companion object
    {
        const val DAILY_SEED = 20260902L
        const val STICK_DEADZONE = 0.2f
    }
}
```

- [ ] **Step 4: Build and run it**

Add the `application` plugin so the game can be launched from Gradle. In
`build.gradle.kts`, add to the `plugins` block and append the `application` block:

```kotlin
plugins {
    kotlin("jvm") version "2.2.20"
    id("edu.sc.seis.launch4j") version "4.0.0"
    application
}

application {
    mainClass.set("EnPustTilKt")
}
```

Also update the launch4j `mainClass` value near the bottom of the file so the
Windows release builds the game rather than the template:

```kotlin
val mainClass = "EnPustTilKt"
```

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

Run: `./gradlew run`
Expected: the game window opens

- [ ] **Step 5: THE FEEL GATE — play it for ten minutes**

Arrow keys swim in any direction. `Z` boosts whichever way you are pointing —
including upward, because it is the escape tool as well as the descent tool.
Release everything and you sink slowly. `X` bleeds ballast. Collect pearls by
touching them, and watch the diver square grow as it loads up.

Answer these, honestly:

1. **Does being loaded feel like tension, or like the controls broke?** This is
   the make-or-break question. Tension is good. Sluggish-and-annoying is fatal.
2. Is `K_ASCENT = 40f` right? If a full load feels *unplayably* slow, raise it.
   If weight is barely noticeable, lower it.
3. Is `BASE_DESCENT = 6f` fast enough that reaching the Abyss feels achievable
   inside a 20-second breath?
4. Does the 90-second run feel long, short, or right?

**If weight feels like punishment rather than tension, stop here and revisit the
spec.** That is the one outcome that invalidates the design, and finding it now
costs one evening instead of four weeks.

- [ ] **Step 6: Record the tuning outcome**

Update the values in `src/main/kotlin/dive/Tuning.kt` to whatever felt right, and
add a comment above each changed constant recording the old value and why it moved.

- [ ] **Step 7: Re-run the tests**

Run: `./gradlew test`
Expected: PASS — the buoyancy tests are written as relationships, not absolute
values, so retuning must not break them. If a test fails on a value change, the
test was over-specified; fix the test to assert the relationship instead.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/EnPustTil.kt src/main/kotlin/render/DiveRenderer.kt src/main/resources/application.cfg src/main/kotlin/dive/Tuning.kt
git commit -m "feat: playable buoyancy prototype with placeholder shapes"
```

---

### Task 7: The anglerfish

**Files:**
- Create: `src/main/kotlin/dive/Anglerfish.kt`
- Modify: `src/main/kotlin/dive/DiveSim.kt`, `src/main/kotlin/render/DiveRenderer.kt`
- Test: `src/test/kotlin/dive/AnglerfishTest.kt`

**Interfaces:**
- Consumes: `Tuning`, `Zone`
- Produces: `class Anglerfish(var x: Float, var depth: Float)` with `fun update(dt: Float, diverX: Float, diverDepth: Float)` and `val lureX: Float`, `val lureDepth: Float`; `DiveSim.anglerfish: Anglerfish?`; `Tuning.ANGLERFISH_*` constants

- [ ] **Step 1: Add anglerfish tuning constants**

Append to `src/main/kotlin/dive/Tuning.kt` inside the object:

```kotlin
    // Anglerfish
    const val ANGLERFISH_DRIFT_SPEED = 1.2f    // m/s toward the diver — the tell
    const val ANGLERFISH_BITE_RADIUS = 3f
    const val ANGLERFISH_STEAL_FRACTION = 0.4f
    const val ANGLERFISH_COOLDOWN = 4f
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/dive/AnglerfishTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnglerfishTest {
    @Test
    fun `lure drifts toward the diver - this is the tell`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        val startDistance = distance(fish, diverX = 20f, diverDepth = 130f)
        fish.update(dt = 1f, diverX = 20f, diverDepth = 130f)
        val endDistance = distance(fish, diverX = 20f, diverDepth = 130f)
        assertTrue(endDistance < startDistance, "lure must approach the diver")
    }

    @Test
    fun `lure does not overshoot the diver`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        repeat(100) { fish.update(dt = 1f, diverX = 1f, diverDepth = 130f) }
        assertEquals(1f, fish.x, 0.5f)
    }

    @Test
    fun `bite detection triggers within the bite radius`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        assertTrue(fish.canBite(diverX = 0f, diverDepth = 131f))
    }

    @Test
    fun `bite detection does not trigger far away`() {
        val fish = Anglerfish(x = 0f, depth = 130f)
        assertTrue(!fish.canBite(diverX = 50f, diverDepth = 130f))
    }

    @Test
    fun `anglerfish only exists in the abyss`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(10f)
        sim.tick(1f / 60f, DiveInput.NONE)
        assertTrue(sim.anglerfish == null, "no anglerfish outside the abyss")
    }

    @Test
    fun `being bitten steals held pearls but does not end the run`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(130f)
        sim.debugSetHeld(count = 2000, mass = 40f)
        sim.debugForceBite()
        assertTrue(sim.held < 2000, "bite must cost held pearls")
        assertTrue(sim.held > 0, "bite must not take everything")
        assertTrue(!sim.runOver, "bite must never end the run")
    }

    private fun distance(fish: Anglerfish, diverX: Float, diverDepth: Float): Float {
        val dx = fish.x - diverX
        val dy = fish.depth - diverDepth
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests 'dive.AnglerfishTest'`
Expected: FAIL — `Unresolved reference: Anglerfish`

- [ ] **Step 4: Implement the anglerfish**

Create `src/main/kotlin/dive/Anglerfish.kt`:

```kotlin
package dive

import kotlin.math.hypot
import kotlin.math.min

/**
 * The only enemy in the game, and it lives only in the Abyss — where the only
 * real money is. Its lure is drawn identically to a pearl.
 *
 * THE TELL: real pearls sit still, this drifts toward the diver. It is hunting.
 * That is what makes it fair — learnable in two runs, unfair in none.
 */
class Anglerfish(var x: Float, var depth: Float)
{
    var biteCooldown = 0f; private set

    fun update(dt: Float, diverX: Float, diverDepth: Float)
    {
        if (biteCooldown > 0f) biteCooldown = maxOf(0f, biteCooldown - dt)

        val dx = diverX - x
        val dy = diverDepth - depth
        val dist = hypot(dx, dy)
        if (dist < 0.001f) return

        // Never overshoot: step is capped at the remaining distance.
        val step = min(Tuning.ANGLERFISH_DRIFT_SPEED * dt, dist)
        x += dx / dist * step
        depth += dy / dist * step
    }

    fun canBite(diverX: Float, diverDepth: Float): Boolean
    {
        if (biteCooldown > 0f) return false
        return hypot(diverX - x, diverDepth - depth) <= Tuning.ANGLERFISH_BITE_RADIUS
    }

    fun onBite() { biteCooldown = Tuning.ANGLERFISH_COOLDOWN }
}
```

- [ ] **Step 5: Wire it into the simulation**

In `src/main/kotlin/dive/DiveSim.kt`, add the field next to the other state:

```kotlin
    var anglerfish: Anglerfish? = null; private set
```

Add these two methods to the class:

```kotlin
    private fun updateAnglerfish(dt: Float)
    {
        if (zone != Zone.ABYSS)
        {
            anglerfish = null
            return
        }

        val fish = anglerfish ?: Anglerfish(x = -Tuning.COLUMN_HALF_WIDTH, depth = Tuning.MAX_DEPTH)
            .also { anglerfish = it }

        fish.update(dt, x, depth)

        if (fish.canBite(x, depth)) bite(fish)
    }

    private fun bite(fish: Anglerfish)
    {
        val stolenValue = (held * Tuning.ANGLERFISH_STEAL_FRACTION).toInt()
        val stolenMass = heldMass * Tuning.ANGLERFISH_STEAL_FRACTION
        held = max(0, held - stolenValue)
        heldMass = max(0f, heldMass - stolenMass)
        fish.onBite()
    }

    internal fun debugForceBite() { bite(anglerfish ?: Anglerfish(x, depth).also { anglerfish = it }) }
```

Then call `updateAnglerfish(dt)` from `tick`, immediately after `collectPearls()`:

```kotlin
        collectPearls()
        updateAnglerfish(dt)
```

- [ ] **Step 6: Run it to verify it passes**

Run: `./gradlew test --tests 'dive.AnglerfishTest'`
Expected: PASS, 6 tests

- [ ] **Step 7: Draw the lure identically to a pearl**

In `src/main/kotlin/render/DiveRenderer.kt`, add this method and call it from
`render` immediately after `drawPearls`:

```kotlin
    private fun drawAnglerfish(surface: Surface, sim: DiveSim, centreX: Float, cameraDepth: Float)
    {
        val fish = sim.anglerfish ?: return
        // Deliberately identical to a pearl. The only difference is that it moves.
        surface.setDrawColor(pearlColor)
        val screenX = centreX + fish.x * PIXELS_PER_METRE
        val screenY = (fish.depth - cameraDepth) * PIXELS_PER_METRE
        surface.drawQuad(screenX - PEARL_SIZE * 0.5f, screenY - PEARL_SIZE * 0.5f, PEARL_SIZE, PEARL_SIZE)
    }
```

Update the `render` body:

```kotlin
        drawZoneBands(surface, cameraDepth, screenWidth, screenHeight)
        drawPearls(surface, sim, centreX, cameraDepth, screenHeight)
        drawAnglerfish(surface, sim, centreX, cameraDepth)
        drawDiver(surface, sim, centreX, cameraDepth)
```

- [ ] **Step 8: Run the whole suite and play it**

Run: `./gradlew test`
Expected: PASS

Then dive to 120m+ and confirm: can you spot the lure by its motion before it
reaches you? If it is impossible to distinguish, lower `ANGLERFISH_DRIFT_SPEED`
so it approaches more slowly and is easier to read; if it is trivially obvious,
raise it.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/dive/Anglerfish.kt src/main/kotlin/dive/DiveSim.kt src/main/kotlin/dive/Tuning.kt src/main/kotlin/render/DiveRenderer.kt src/test/kotlin/dive/AnglerfishTest.kt
git commit -m "feat: anglerfish whose lure looks like a pearl but drifts toward you"
```

---

### Task 8: HUD

Held/banked, the air ring, the depth tape and the point-of-no-return marker.
Still placeholder — quads and engine default text only.

**Files:**
- Create: `src/main/kotlin/render/Hud.kt`
- Modify: `src/main/kotlin/EnPustTil.kt`
- Test: `src/test/kotlin/dive/NoReturnTest.kt`
- Modify: `src/main/kotlin/dive/DiveSim.kt`

**Interfaces:**
- Consumes: `DiveSim`
- Produces: `DiveSim.canStillReturn(): Boolean`, `DiveSim.maxSafeDepth(): Float`, `object Hud { fun render(surface, sim, w, h) }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dive/NoReturnTest.kt`:

```kotlin
package dive

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class NoReturnTest {
    @Test
    fun `an empty diver at the surface can always return`() {
        val sim = DiveSim(seed = 1L)
        assertTrue(sim.canStillReturn())
    }

    @Test
    fun `a heavily loaded deep diver with little air cannot return`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetDepth(150f)
        sim.debugSetHeld(count = 5000, mass = 200f)
        sim.debugSetAir(0.5f)
        assertFalse(sim.canStillReturn())
    }

    @Test
    fun `max safe depth shrinks as the diver gets heavier`() {
        val light = DiveSim(seed = 1L)
        val heavy = DiveSim(seed = 1L)
        heavy.debugSetHeld(count = 0, mass = 120f)
        assertTrue(heavy.maxSafeDepth() < light.maxSafeDepth())
    }

    @Test
    fun `max safe depth is never negative`() {
        val sim = DiveSim(seed = 1L)
        sim.debugSetHeld(count = 0, mass = 5000f)
        sim.debugSetAir(0.1f)
        assertTrue(sim.maxSafeDepth() >= 0f)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dive.NoReturnTest'`
Expected: FAIL — `Unresolved reference: canStillReturn`

- [ ] **Step 3: Implement the point-of-no-return maths**

Add to `src/main/kotlin/dive/DiveSim.kt`:

```kotlin
    /**
     * How deep the diver could still be and expect to reach the surface on the
     * air remaining. Drives the faint depth-tape marker — a mercy for
     * first-timers that teaches the economy without a word of text.
     */
    fun maxSafeDepth(): Float
    {
        val ascent = Buoyancy.ascentSpeed(heldMass)
        val burn = zone.airBurn
        val reachable = ascent * (air / burn)
        return max(0f, min(reachable, Tuning.MAX_DEPTH))
    }

    fun canStillReturn(): Boolean = depth <= maxSafeDepth()

    internal fun debugSetAir(value: Float) { air = value }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests 'dive.NoReturnTest'`
Expected: PASS, 4 tests

- [ ] **Step 5: Write the HUD**

Create `src/main/kotlin/render/Hud.kt`:

```kotlin
package render

import dive.DiveSim
import dive.Tuning
import no.njoh.pulseengine.core.graphics.surface.Surface
import no.njoh.pulseengine.core.shared.primitives.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * Placeholder HUD. Per the spec:
 *   BANKED - small, cold white, top-left
 *   HELD   - enormous amber numerals attached to the diver
 *   AIR    - a RING OF BUBBLES, never a number
 *   Depth tape down the right edge with the point-of-no-return marker
 */
object Hud
{
    private const val AIR_BUBBLE_COUNT = 12
    private const val AIR_RING_RADIUS = 34f
    private const val BUBBLE_SIZE = 7f
    private const val TAPE_WIDTH = 6f
    private const val TAPE_MARGIN = 40f

    private val cold = Color(0.75f, 0.85f, 1f)
    private val amber = Color(1f, 0.72f, 0.25f)
    private val danger = Color(1f, 0.25f, 0.2f)
    private val tapeBg = Color(1f, 1f, 1f, 0.12f)
    private val noReturn = Color(1f, 0.9f, 0.4f, 0.5f)

    fun render(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        drawBanked(surface, sim)
        drawClock(surface, sim, w)
        drawAirRing(surface, sim, w, h)
        drawHeld(surface, sim, w, h)
        drawDepthTape(surface, sim, w, h)
    }

    private fun drawBanked(surface: Surface, sim: DiveSim)
    {
        surface.setDrawColor(cold)
        surface.drawText("BANKED ${sim.banked}", 20f, 34f, fontSize = 26f)
    }

    private fun drawClock(surface: Surface, sim: DiveSim, w: Float)
    {
        surface.setDrawColor(if (sim.clock < 20f) danger else cold)
        surface.drawText("%.0f".format(sim.clock), w * 0.5f, 44f, fontSize = 40f, xOrigin = 0.5f)
    }

    /** Air is NEVER a number. It is a ring of bubbles that thins as you breathe. */
    private fun drawAirRing(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        val fraction = (sim.air / Tuning.BASE_AIR_SECONDS).coerceIn(0f, 1f)
        val remaining = (fraction * AIR_BUBBLE_COUNT).toInt()
        val low = remaining <= 3

        val cx = w * 0.5f
        val cy = h * 0.4f

        surface.setDrawColor(if (low) danger else cold)
        for (i in 0 until remaining)
        {
            val angle = (i.toFloat() / AIR_BUBBLE_COUNT) * Math.PI.toFloat() * 2f
            // Heartbeat pulse when low: ring breathes in and out.
            val pulse = if (low) 1f + sin(sim.clock * 8f) * 0.12f else 1f
            val bx = cx + cos(angle) * AIR_RING_RADIUS * pulse
            val by = cy + sin(angle) * AIR_RING_RADIUS * pulse
            surface.drawQuad(bx - BUBBLE_SIZE * 0.5f, by - BUBBLE_SIZE * 0.5f, BUBBLE_SIZE, BUBBLE_SIZE)
        }
    }

    /** Enormous amber numerals, attached to the diver, hotter as they grow. */
    private fun drawHeld(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        if (sim.held <= 0) return
        val heat = (sim.held / 3000f).coerceIn(0f, 1f)
        surface.setDrawColor(Color(1f, 0.72f - heat * 0.3f, 0.25f - heat * 0.2f))
        surface.drawText("${sim.held}", w * 0.5f, h * 0.4f + 70f, fontSize = 44f + heat * 28f, xOrigin = 0.5f)
    }

    private fun drawDepthTape(surface: Surface, sim: DiveSim, w: Float, h: Float)
    {
        val x = w - TAPE_MARGIN
        val top = 80f
        val bottom = h - 60f
        val span = bottom - top

        surface.setDrawColor(tapeBg)
        surface.drawQuad(x, top, TAPE_WIDTH, span)

        // Point of no return.
        val safeY = top + (sim.maxSafeDepth() / Tuning.MAX_DEPTH).coerceIn(0f, 1f) * span
        surface.setDrawColor(noReturn)
        surface.drawQuad(x - 14f, safeY - 1.5f, TAPE_WIDTH + 28f, 3f)

        // Current depth.
        val depthY = top + (sim.depth / Tuning.MAX_DEPTH).coerceIn(0f, 1f) * span
        surface.setDrawColor(if (sim.canStillReturn()) cold else danger)
        surface.drawQuad(x - 8f, depthY - 3f, TAPE_WIDTH + 16f, 6f)

        surface.drawText("%.0f m".format(sim.depth), x - 20f, depthY - 14f, fontSize = 20f, xOrigin = 1f)
    }
}
```

- [ ] **Step 6: Swap the debug readout for the HUD**

In `src/main/kotlin/EnPustTil.kt`, replace the body of `onRender`:

```kotlin
    override fun onRender()
    {
        val surface = engine.gfx.mainSurface
        val w = engine.window.width.toFloat()
        val h = engine.window.height.toFloat()
        DiveRenderer.render(surface, sim, w, h)
        Hud.render(surface, sim, w, h)
        if (sim.runOver)
        {
            surface.setDrawColor(Color.WHITE)
            surface.drawText("RUN OVER — SPACE to restart", w * 0.5f, h * 0.5f, fontSize = 40f, xOrigin = 0.5f)
        }
    }
```

Add the import `import render.Hud` and delete the now-unused `drawDebugReadout` method.

- [ ] **Step 7: Run the suite and play it**

Run: `./gradlew test`
Expected: PASS

Play and check: **do you ever look at a number to know you are in trouble, or
does the thinning bubble ring tell you?** If you find yourself reading text, the
ring is not doing its job.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/render/Hud.kt src/main/kotlin/EnPustTil.kt src/main/kotlin/dive/DiveSim.kt src/test/kotlin/dive/NoReturnTest.kt
git commit -m "feat: HUD with bubble-ring air and point-of-no-return marker"
```

---

### Task 9: Lighting pass

Global illumination applied to the placeholder shapes. **Not polish** — the
Abyss's entire identity is "pearls are the only light source", so the deep zones
cannot be judged without it.

**Files:**
- Create: `src/main/kotlin/render/DiveLighting.kt`
- Modify: `src/main/kotlin/EnPustTil.kt`

**Interfaces:**
- Consumes: `DiveSim`, engine graphics API
- Produces: `object DiveLighting { fun setup(engine: PulseEngine); fun sync(engine: PulseEngine, sim: DiveSim) }`

- [ ] **Step 1: Write the lighting setup**

Create `src/main/kotlin/render/DiveLighting.kt`:

```kotlin
package render

import dive.DiveSim
import dive.Tuning
import dive.Zone
import no.njoh.pulseengine.core.PulseEngine
import no.njoh.pulseengine.core.graphics.postprocessing.effects.BloomEffect
import no.njoh.pulseengine.core.graphics.postprocessing.effects.ColorGradingEffect
import no.njoh.pulseengine.core.graphics.postprocessing.effects.ColorGradingEffect.ToneMapper.ACES
import no.njoh.pulseengine.core.shared.primitives.Color
import no.njoh.pulseengine.modules.lighting.global.GlobalIlluminationSystem
import no.njoh.pulseengine.modules.scene.entities.Camera
import no.njoh.pulseengine.modules.scene.entities.Lamp
import no.njoh.pulseengine.modules.scene.systems.EntityRendererImpl
import no.njoh.pulseengine.modules.scene.systems.EntityUpdater

/**
 * Pearls ARE the light. In the Abyss they are the only light source, which is
 * why lighting is part of the core loop rather than a polish pass — you cannot
 * judge whether the deep feels right without it.
 *
 * Works entirely on untextured entities, so it is fully compatible with
 * placeholder art.
 */
object DiveLighting
{
    private const val PEARL_LAMP_POOL = 48
    private const val PIXELS_PER_METRE = 5f

    private val pearlLight = Color(1f, 0.82f, 0.45f)
    private val diverLight = Color(0.6f, 0.85f, 1f)

    private val pearlLamps = ArrayList<Lamp>(PEARL_LAMP_POOL)
    private var diverLamp: Lamp? = null

    fun setup(engine: PulseEngine)
    {
        engine.scene.createEmptyAndSetActive("dive.scn")
        engine.scene.addSystem(EntityUpdater())
        engine.scene.addSystem(EntityRendererImpl())

        val camera = Camera()
        camera.viewPortWidth = engine.window.width.toFloat()
        camera.viewPortHeight = engine.window.height.toFloat()
        engine.scene.addEntity(camera)

        // Pooled lamps — allocated once, repositioned every frame, never created
        // in the render path.
        repeat(PEARL_LAMP_POOL) {
            val lamp = Lamp()
            lamp.lightColor = pearlLight
            lamp.intensity = 0f
            lamp.width = 6f
            lamp.height = 6f
            lamp.coneAngle = 360f
            engine.scene.addEntity(lamp)
            pearlLamps += lamp
        }

        diverLamp = Lamp().also {
            it.lightColor = diverLight
            it.intensity = 2f
            it.width = 14f
            it.height = 14f
            it.coneAngle = 360f
            engine.scene.addEntity(it)
        }

        val gi = GlobalIlluminationSystem()
        gi.lightTexScale = 0.8f
        gi.localSceneTexScale = 0.8f
        engine.scene.addSystem(gi)

        engine.gfx.mainSurface.addPostProcessingEffect(
            ColorGradingEffect(toneMapper = ACES, vignette = 0.25f, exposure = 1.1f, contrast = 1.3f)
        )
        engine.gfx.mainSurface.addPostProcessingEffect(
            BloomEffect().apply { intensity = 1.2f; radius = 0f; threshold = 1.4f }
        )

        engine.scene.start()
    }

    /** Repositions the pooled lamps onto the nearest visible pearls. */
    fun sync(engine: PulseEngine, sim: DiveSim)
    {
        val centreX = engine.window.width * 0.5f
        val cameraDepth = sim.depth - (engine.window.height * 0.4f) / PIXELS_PER_METRE

        // Deeper zones are darker, so pearls must shine harder to stay legible.
        val zoneIntensity = when (sim.zone)
        {
            Zone.SHALLOWS -> 0.6f
            Zone.KELP     -> 1.0f
            Zone.TWILIGHT -> 1.8f
            Zone.TRENCH   -> 2.6f
            Zone.ABYSS    -> 4.0f
        }

        var lampIndex = 0
        sim.pearls.forEach { pearl ->
            if (pearl.collected || lampIndex >= pearlLamps.size) return@forEach
            val screenY = (pearl.depth - cameraDepth) * PIXELS_PER_METRE
            if (screenY < -50f || screenY > engine.window.height + 50f) return@forEach

            pearlLamps[lampIndex].apply {
                x = centreX + pearl.x * PIXELS_PER_METRE
                y = screenY
                intensity = zoneIntensity
            }
            lampIndex++
        }

        // Park unused lamps.
        while (lampIndex < pearlLamps.size) pearlLamps[lampIndex++].intensity = 0f

        diverLamp?.apply {
            x = centreX + sim.x * PIXELS_PER_METRE
            y = (sim.depth - cameraDepth) * PIXELS_PER_METRE
            intensity = if (sim.zone == Zone.ABYSS) 1.2f else 2f
        }
    }
}
```

- [ ] **Step 2: Wire lighting into the shell**

In `src/main/kotlin/EnPustTil.kt`:

Add `import render.DiveLighting`, then at the end of `onCreate`:

```kotlin
        DiveLighting.setup(engine)
```

And at the start of `onUpdate`:

```kotlin
        DiveLighting.sync(engine, sim)
```

- [ ] **Step 3: Darken the zone bands so the lighting has something to do**

In `src/main/kotlin/render/DiveRenderer.kt`, replace the `zoneColors` map:

```kotlin
    private val zoneColors = mapOf(
        Zone.SHALLOWS to Color(0.10f, 0.34f, 0.52f),
        Zone.KELP     to Color(0.06f, 0.22f, 0.36f),
        Zone.TWILIGHT to Color(0.03f, 0.12f, 0.22f),
        Zone.TRENCH   to Color(0.015f, 0.06f, 0.12f),
        Zone.ABYSS    to Color(0.004f, 0.015f, 0.035f)
    )
```

- [ ] **Step 4: Build, run and check performance**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

Run the game and press **F3** to open the `MetricViewer`. Dive to the Abyss.

Check: **is the frame rate stable at the target with 48 lamps active?** If not,
lower `PEARL_LAMP_POOL` to 24, or drop `gi.lightTexScale` to `0.6f` — the example
at `src/main/kotlin/examples/GlobaltLightingExample.kt:93` notes that decreasing
this increases performance.

- [ ] **Step 5: Judge the Abyss**

Dive past 120m with the lights on. Answer:

1. Is the Abyss genuinely dark enough that pearls are the only thing you can see?
2. **Can you still tell the anglerfish lure from a pearl by its motion?** If the
   glow makes them indistinguishable even when moving, give the lure a very
   slightly different `intensity` — but never a different colour.
3. Does bloom on a cluster of pearls read as treasure, or as noise?

- [ ] **Step 6: Run the suite**

Run: `./gradlew test`
Expected: PASS — lighting touches no simulation code, so all tests must still be green.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/render/DiveLighting.kt src/main/kotlin/render/DiveRenderer.kt src/main/kotlin/EnPustTil.kt
git commit -m "feat: global illumination with pearls as the light source"
```

---

## Definition of done

The game loop is locked when all of these are true:

- [ ] `./gradlew test` passes — all pure simulation logic is covered
- [ ] A full 90-second run is playable end to end with placeholder shapes
- [ ] Weight feels like **tension**, not punishment (Task 6, Step 5)
- [ ] The bubble ring, not a number, tells you when you are in trouble
- [ ] Banking on surfacing works, and the depth bonus resets between dives
- [ ] Blacking out costs 90% and never ends the run
- [ ] The clock expiring while submerged loses everything held
- [ ] Bleeding ballast visibly trades score for speed
- [ ] The anglerfish is distinguishable by motion, and only bites in the Abyss
- [ ] The Abyss is dark enough that pearls are the only light
- [ ] Frame rate is stable with lighting on

**Then, and only then:** real art replaces the placeholder quads.

---

## Deliberately out of scope

These are in the spec but are **not** part of locking the game loop. Each becomes
its own plan once the feel is proven.

- Zone-specific movement (kelp drag, trench currents, twilight ledges) — spec §3
- The cash-out spectacle: foam, camera slam, digit flight, chime run — spec §12
- The dive-profile leaderboard and three-letter initials — spec §12
- Scene-editor authored pearl layouts replacing the seeded generator — spec §10
- Audio of any kind, including the heartbeat
- Neptune, the surface boat, and all real art
- The local leaderboard service and QR code
