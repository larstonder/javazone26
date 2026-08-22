# UI Clarity and Pre-Run Briefing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every on-screen control prompt correct for the hardware actually attached, and add a 5-second explanation screen before each dive from attract mode.

**Architecture:** A new engine-free `render/ControlHints.kt` becomes the single source of every control string, taking button *labels* as Strings so it stays testable without a GL context. `RunLifecycle` gains a sixth state, `BRIEFING`, between `IDLE` and `PLAYING`. A new full-screen `drawBriefingScreen` and a new one-line `Hud.renderControlLegend` consume the hints.

**Tech Stack:** Kotlin 2.2.20, Pulse Engine 0.13.0, `kotlin.test` on JUnit Platform, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-22-ui-clarity-and-briefing-design.md` — read it before starting. This plan argues from it; where they disagree, the spec wins and you should say so rather than guessing.

---

## Global Constraints

Every one of these is a project-wide rule from `CLAUDE.md` or the spec. They apply to **all** tasks.

- **Allman braces, 4 spaces, no wildcard imports.** Match the surrounding file exactly.
- **The default font draws only U+0020..U+011F.** Anything above renders as *nothing at all*, with no glyph and no x-advance, silently. Every drawn string must be plain ASCII. No em dashes, en dashes, curly quotes, ellipses or bullets. Use `ScreenText.SEPARATOR` (a middle dot) where you want an em dash.
- **Never call `drawQuad`/`drawLine`.** They render nothing on macOS, silently. `DrawTest.no production source draws a quad or a line` fails the build if you do. Use `fillRect`/`fillRectCentred` from `render/Draw.kt`.
- **No per-frame allocation in the render path.** HUD numeric text formatting is the one written exemption. Specifically: no `Color` construction per frame, and no `firstOrNull { }` / `forEach` / `count { }` on collections or ranges in `onRender`/`onUpdate` — they allocate an iterator. Use indexed `for (i in xs.indices)` loops, as `unmappedGamepadCount` and `GamepadScan` already do.
- **Semi-transparent colours on the `"hud"` surface must go through `Hud.authoredAlphaFor()`.** That surface stores RGB pre-multiplied by alpha *and* stores alpha squared, so an authored `0.15` displays near `0.02`.
- **Screen-space sizes are fractions of screen HEIGHT**, never width and never a pixel count. The booth panel's aspect is not known in advance.
- **Prefer `surface.config.width/height` over `engine.window.*`** in screen-space code.
- **A test that cannot fail is worse than no test.** Assert the relationship that would actually break.
- **Comments explain *why*, at length, and cite evidence.** Match the surrounding density. When you fix something subtle, leave the same kind of note.
- **Green tests are not evidence the game looks right.** Every task ends with a real window grab (procedure below).

### The window-grab procedure — use this, not `EPT_SCREENSHOT`

```bash
caffeinate -d -u -t 900 &                      # stop the display sleeping mid-capture
./gradlew run > /dev/null 2>&1 &
until pgrep -f EnPustTilKt > /dev/null; do sleep 2; done ; sleep 12
osascript -e 'tell application "System Events" to set frontmost of (first application process whose name is "java") to true'
sleep 2 ; screencapture -x -o /tmp/shot.png    # needs Screen Recording granted to the terminal
pkill -9 -f EnPustTilKt                        # the game has no quit key in booth mode
```

**Do not use `EPT_SCREENSHOT` for appearance.** `ScreenshotEffect.getTexture()` returns `RenderTexture.BLANK`, so any surface it attaches to composites as blank — it changes the frame it claims to observe. It also dumps each surface separately, so a hand-recomposite does not reproduce the engine's frame. Both facts have cost this project a day each.

Prefix the command with env vars where a task says so, e.g. `EPT_BRIEFING_HOLD=1 ./gradlew run`.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/kotlin/render/ControlHints.kt` | **New.** Engine-free. Every control string, for both input devices. Takes button labels as `String`. |
| `src/main/kotlin/render/RunLifecycle.kt` | Gains `BRIEFING`, its two constants, four accessors, and the zero-length rule. Still engine-free. |
| `src/main/kotlin/EnPustTil.kt` | `gamepadButtonLabel`; the hint cache; `BriefingLayout`; `drawBriefingScreen`; the dispatch branch; the pad latch; `EPT_BRIEFING_HOLD`; `ScreenText` additions. |
| `src/main/kotlin/render/Hud.kt` | `renderControlLegend`, `legendInk`, and the legend's layout helpers. |
| `src/test/kotlin/render/ControlHintsTest.kt` | **New.** The sweep of record for composed strings. |
| `src/test/kotlin/render/BriefingScreenTest.kt` | **New.** `BriefingLayout` geometry. |
| `src/test/kotlin/render/PauseScreenTest.kt` | **New.** Closes the gap where `PauseLayout`'s KDoc cites a test that does not exist. |

---

## Task 1: `ControlHints` — the string source

**Files:**
- Create: `src/main/kotlin/render/ControlHints.kt`
- Test: `src/test/kotlin/render/ControlHintsTest.kt`

**Interfaces:**
- Consumes: `ScreenText.SEPARATOR` (the string `"  ·  "`, declared in `EnPustTil.kt:532`). `EnPustTil.kt` has no `package` line, so `ScreenText` is in the root package and is visible from `render` without an import.
- Produces: `ControlHints.swim/kick/bleed/confirm/cycle(...)`, `ControlHints.pressStart/playAgain/initialsHelp/legend(...)`, `ControlHints.all(): List<String>`. Every function's first parameter is `arcade: Boolean`. Tasks 2, 5 and 6 call these.

Nothing depends on this task yet, and it touches no rendering. It is deliberately first.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/render/ControlHintsTest.kt`:

```kotlin
package render

import DefaultFont
import ScreenText
import no.njoh.pulseengine.core.input.GamepadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sweep of record for every string that names a control.
 *
 * `AttractScreenTest` sweeps `ScreenText.all()`, which is a static list — but these strings
 * are COMPOSED at runtime from a config-dependent button label, so they can never appear in
 * it. Adding the fixed fragments there covers the fragments only: not the composition, and
 * not whether the separator survived the join. That is this class's job.
 */
class ControlHintsTest
{
    @Test
    fun `every control hint is inside the default font's baked atlas`()
    {
        for (text in ControlHints.all())
        {
            val missing = DefaultFont.undrawableCodePointsIn(text)
            assertTrue(
                missing.isEmpty(),
                "\"$text\" contains ${missing.map { "U+%04X".format(it) }}, which the default " +
                "font cannot draw - it renders as nothing at all, silently. See DefaultFont."
            )
        }
    }

    @Test
    fun `every gamepad button name produces a drawable, non-empty label`()
    {
        // This is what makes "derive the hint from live config" safe. A technician can bind
        // any of these ~20 names in application.cfg; none of them may vanish into the atlas
        // gap or come out blank.
        for (button in GamepadButton.entries)
        {
            val label = ControlHints.labelFor(button.name)
            assertTrue(label.isNotBlank(), "${button.name} produced a blank label")
            assertTrue(
                DefaultFont.undrawableCodePointsIn(label).isEmpty(),
                "${button.name} -> \"$label\" is not drawable"
            )
        }
    }

    @Test
    fun `the arcade and keyboard forms of every hint differ`()
    {
        // Catches a copy-paste that wires both branches of a `if (arcade)` to the same
        // constant - which would compile, pass a "is it drawable" test, and silently show
        // keyboard keys on the cabinet.
        assertFalse(ControlHints.swim(true) == ControlHints.swim(false), "swim")
        assertFalse(ControlHints.kick(true, "A") == ControlHints.kick(false, "A"), "kick")
        assertFalse(ControlHints.bleed(true, "B") == ControlHints.bleed(false, "B"), "bleed")
        assertFalse(ControlHints.confirm(true, "START") == ControlHints.confirm(false, "START"), "confirm")
        assertFalse(ControlHints.cycle(true) == ControlHints.cycle(false), "cycle")
    }

    @Test
    fun `a rebound button reaches the screen`()
    {
        // The defect this whole file exists for: PRESS_START used to be the literal
        // "PRESS START" while restartButton is config-driven, so a rebind made the screen
        // lie. Every arcade-form hint must carry the label it was handed.
        assertTrue(ControlHints.kick(true, "RIGHT BUMPER").contains("RIGHT BUMPER"))
        assertTrue(ControlHints.bleed(true, "LEFT BUMPER").contains("LEFT BUMPER"))
        assertTrue(ControlHints.pressStart(true, "BACK").contains("BACK"))
        assertTrue(ControlHints.playAgain(true, "BACK").contains("BACK"))
        assertTrue(ControlHints.initialsHelp(true, "BACK").contains("BACK"))
        assertTrue(ControlHints.legend(true, "X", "Y").contains("X"))
        assertTrue(ControlHints.legend(true, "X", "Y").contains("Y"))
    }

    @Test
    fun `the keyboard forms ignore the gamepad label entirely`()
    {
        // Keyboard bindings are hard-coded in readInput (Key.Z, Key.X, the arrows) and in
        // updateGame (Key.SPACE). There is no config key for any of them, so a keyboard hint
        // that varied with a gamepad label would be reporting a binding that does not exist.
        assertEquals(ControlHints.kick(false, "A"), ControlHints.kick(false, "RIGHT BUMPER"))
        assertEquals(ControlHints.bleed(false, "B"), ControlHints.bleed(false, "GUIDE"))
        assertEquals(ControlHints.confirm(false, "START"), ControlHints.confirm(false, "BACK"))
        assertEquals(ControlHints.legend(false, "A", "B"), ControlHints.legend(false, "X", "Y"))
    }

    @Test
    fun `composed hints join with ScreenText SEPARATOR, not a raw run of spaces`()
    {
        // INITIALS_HELP used to separate its two halves with a raw triple space while every
        // other string in the game used SEPARATOR. At arcade viewing distance a run of
        // spaces reads as a gap in the sentence; the dot reads as a deliberate beat.
        for (arcade in listOf(true, false))
        {
            val legend = ControlHints.legend(arcade, "A", "B")
            assertTrue(legend.contains(ScreenText.SEPARATOR), "legend(arcade=$arcade)")
            assertFalse(legend.contains("   "), "legend(arcade=$arcade) has a raw space run")

            val help = ControlHints.initialsHelp(arcade, "START")
            assertTrue(help.contains(ScreenText.SEPARATOR), "initialsHelp(arcade=$arcade)")
            assertFalse(help.contains("   "), "initialsHelp(arcade=$arcade) has a raw space run")
        }
    }

    @Test
    fun `all() covers both device forms of every composed hint`()
    {
        // A guard on the sweep itself: if someone adds a composite and forgets to list it
        // here, the atlas test above silently stops covering it.
        val all = ControlHints.all()
        for (arcade in listOf(true, false))
        {
            assertTrue(all.contains(ControlHints.pressStart(arcade, "START")), "pressStart $arcade")
            assertTrue(all.contains(ControlHints.playAgain(arcade, "START")), "playAgain $arcade")
            assertTrue(all.contains(ControlHints.initialsHelp(arcade, "START")), "initialsHelp $arcade")
            assertTrue(all.contains(ControlHints.legend(arcade, "A", "B")), "legend $arcade")
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "render.ControlHintsTest"
```

Expected: compilation failure — `Unresolved reference: ControlHints`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/render/ControlHints.kt`:

```kotlin
package render

import ScreenText

/**
 * Every string in the game that names a control, for both input devices.
 *
 * WHY THIS EXISTS. Three strings used to hard-code button names as literals — `PRESS START`,
 * `SPACE / START to play again`, `UP/DOWN: change letter   A / START: next`. The gamepad
 * button map is CONFIG-DRIVEN (`kickButton`, `bleedButton`, `restartButton`,
 * `restartButtonAlt` in application.cfg), and the arcade encoder's real button codes are not
 * known until it is plugged in — that is why the map is configurable at all. So those
 * literals were a promise the code could not keep: rebind `restartButton` and the screen
 * lies. Two of them also named a keyboard key AND a gamepad button in one breath, which is
 * half-irrelevant on every machine that will ever run this.
 *
 * PURE AND ENGINE-FREE, like [Framing], [RunLifecycle], [AttractLayout] and [DepthBlend]. It
 * takes button DISPLAY LABELS as plain `String`s, never a `GamepadButton` — that is what
 * keeps it testable with no GL context. The enum-to-label map lives in `EnPustTil.kt` beside
 * `parseGamepadButton`, where the rest of the button-map handling already is.
 *
 * THE ASYMMETRY, which is real and not an oversight worth "cleaning up": gamepad bindings are
 * configurable, keyboard bindings are NOT. `readInput` hard-codes `Key.Z` for kick, `Key.X`
 * for bleed and the arrow keys for the stick; `updateGame` hard-codes `Key.SPACE` for
 * lifecycle actions. There is no config key for any of them. So the keyboard labels below are
 * compile-time constants while the gamepad ones are parameters. Making both parameters would
 * invent four config keys that nothing reads.
 *
 * CASING RULE: control token in caps, verb in lowercase — `STICK swim  ·  A kick`. Calls to
 * action keep full caps (`PRESS START`) because they are the sign, not the legend. Before
 * this, the three literals above disagreed with each other three separate ways, which at
 * arcade viewing distance reads as three unrelated systems.
 *
 * @see ControlHintsTest, which is the sweep of record for these strings — they are composed
 *   at runtime and therefore can never appear in `ScreenText.all()`.
 */
object ControlHints
{
    // The keyboard side. Compiled constants, because the bindings they name are compiled
    // constants — see the asymmetry paragraph above.
    private const val KEY_SWIM = "ARROW KEYS"
    private const val KEY_KICK = "Z"
    private const val KEY_BLEED = "X"
    private const val KEY_CONFIRM = "SPACE"
    private const val KEY_CYCLE = "UP/DOWN"

    private const val PAD_SWIM = "STICK"
    private const val PAD_CYCLE = "STICK UP/DOWN"

    /**
     * A `GamepadButton` name rendered for a screen at arcade viewing distance.
     *
     * ACCEPTED LIMIT: this is the enum's own name, which on a generic USB encoder bears no
     * relation to anything silkscreened on the cabinet. There is no source for the physical
     * legend, and the enum name is at least the same string the technician typed into
     * application.cfg.
     */
    fun labelFor(buttonName: String): String = buttonName.replace('_', ' ')

    fun swim(arcade: Boolean): String = if (arcade) PAD_SWIM else KEY_SWIM

    fun kick(arcade: Boolean, kickLabel: String): String = if (arcade) kickLabel else KEY_KICK

    fun bleed(arcade: Boolean, bleedLabel: String): String = if (arcade) bleedLabel else KEY_BLEED

    fun confirm(arcade: Boolean, startLabel: String): String = if (arcade) startLabel else KEY_CONFIRM

    fun cycle(arcade: Boolean): String = if (arcade) PAD_CYCLE else KEY_CYCLE

    // --- Composites. These allocate, so EnPustTil CACHES them; see its hint cache. --------

    fun pressStart(arcade: Boolean, startLabel: String): String =
        "PRESS ${confirm(arcade, startLabel)}"

    fun playAgain(arcade: Boolean, startLabel: String): String =
        "${confirm(arcade, startLabel)} to play again"

    fun initialsHelp(arcade: Boolean, startLabel: String): String =
        "${cycle(arcade)} change letter${ScreenText.SEPARATOR}${confirm(arcade, startLabel)} next"

    fun legend(arcade: Boolean, kickLabel: String, bleedLabel: String): String =
        "${swim(arcade)} swim${ScreenText.SEPARATOR}" +
        "${kick(arcade, kickLabel)} kick${ScreenText.SEPARATOR}" +
        "${bleed(arcade, bleedLabel)} bleed"

    /**
     * Every string this object can produce, at both device settings and at the widest button
     * labels, for the font-atlas sweep. Test-only; cheap enough not to warrant a flag, same
     * as `ScreenText.all()`.
     */
    fun all(): List<String>
    {
        val out = mutableListOf<String>()
        for (arcade in listOf(true, false))
        {
            out += swim(arcade)
            out += cycle(arcade)
            out += kick(arcade, "RIGHT BUMPER")
            out += bleed(arcade, "LEFT BUMPER")
            out += confirm(arcade, "START")
            out += pressStart(arcade, "START")
            out += playAgain(arcade, "START")
            out += initialsHelp(arcade, "START")
            out += legend(arcade, "A", "B")
            out += legend(arcade, "RIGHT BUMPER", "LEFT BUMPER")
        }
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests "render.ControlHintsTest"
```

Expected: PASS, 7 tests.

If `import ScreenText` fails to resolve: `EnPustTil.kt` has no `package` declaration, so its top-level objects are in the root package. Importing a root-package name from a named package is legal Kotlin. If the compiler disagrees in this project's configuration, move `SEPARATOR` into `ControlHints` as its own constant and have `ScreenText.SEPARATOR` delegate to it — do **not** duplicate the literal, because its double spaces are load-bearing and documented.

- [ ] **Step 5: Run the whole suite**

```bash
./gradlew test
```

Expected: PASS. Nothing consumes `ControlHints` yet, so nothing else can have moved.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/render/ControlHints.kt src/test/kotlin/render/ControlHintsTest.kt
git commit -m "feat: ControlHints, one source for every string that names a control"
```

**Verification gate:** all tests green. No window grab for this task — nothing it produces reaches the screen yet.

---

## Task 2: Wire the hints into the three existing screens

**Files:**
- Modify: `src/main/kotlin/EnPustTil.kt` — `ScreenText` (`:519-602`), `createGame` (near `:1060-1063`), `updateGame` (`:1452+`), `drawIdleScreen` (`:1943-1969`), `drawRunOverScreen` (`:2095-2109`), `drawInitialsEntryScreen` (`:2172-2191`)
- Modify: `src/test/kotlin/AttractScreenTest.kt`

**Interfaces:**
- Consumes: everything Task 1 produced.
- Produces: `arcadeHints: Boolean`, and the cached fields `hintPressStart`, `hintPlayAgain`, `hintInitialsHelp`, `hintLegend` on `EnPustTil`. Tasks 5 and 6 read `hintLegend` and call `ControlHints.confirm(arcadeHints, restartButtonLabel)`.

- [ ] **Step 1: Add the label helper beside `parseGamepadButton`**

In `EnPustTil.kt`, immediately after `parseGamepadButton` (which ends around `:253`), add:

```kotlin
/**
 * A configured button's name as it should appear on screen. Thin, but it is the seam that
 * keeps [render.ControlHints] engine-free: that object takes `String` labels and never a
 * `GamepadButton`, so it needs no pulseengine import and can be tested with no GL context.
 */
fun gamepadButtonLabel(button: GamepadButton): String = ControlHints.labelFor(button.name)
```

Add `import render.ControlHints` to the file's imports if it is not already present.

- [ ] **Step 2: Add the cache fields and the presence read**

Beside the other button fields in `EnPustTil` (near where `kickButton` is declared), add:

```kotlin
/**
 * Whether control prompts should name ARCADE controls rather than keyboard keys.
 *
 * Deliberately NOT `engine.input.gamepads.isNotEmpty()` alone. A generic arcade USB encoder
 * may have no SDL gamepad mapping, in which case it is invisible to that list while working
 * fine at the OS level — a verified platform finding, and the reason [logGamepadDiagnostics]
 * exists. Gating on the mapped list alone would put "PRESS SPACE" on the attract screen of a
 * cabinet that has no keyboard, in front of the queue, as the most visible string in the
 * game. Today that string reads "PRESS START" and is right by accident in exactly that case;
 * a change that made the booth's worst input failure ALSO display the wrong instruction would
 * be a regression. Naming the right control on a broken machine beats naming a control the
 * machine does not have.
 *
 * [unmappedGamepadCount] already walks the raw GLFW joystick list every attract frame for the
 * booth status line, with an indexed loop for the no-allocation rule, so the second half of
 * this costs nothing new.
 */
private var arcadeHints: Boolean = true

// The four composed hints. ControlHints builds Strings, and CLAUDE.md forbids per-frame
// allocation in the render path (the one written exemption is HUD numeric formatting, which
// these are not). The button map is fixed once config is read, so `arcadeHints` is the only
// input that can vary — these are rebuilt only when it flips.
// All four start empty, not at the old ScreenText constants — Step 5 deletes those, and a
// field initialiser referencing a constant this same task removes would not compile. The
// value is meaningless before createGame seeds the cache in Step 3, and
// refreshControlHints' `hintPlayAgain.isNotEmpty()` guard already reads empty as unseeded.
private var hintPressStart: String = ""
private var hintPlayAgain: String = ""
private var hintInitialsHelp: String = ""
private var hintLegend: String = ""
```

Then add the rebuild function near `readInput`:

```kotlin
/**
 * Rebuilds the cached hint strings if — and only if — the connected input hardware changed.
 *
 * Called once per frame from [updateGame]. `gamepads.isNotEmpty()` allocates nothing:
 * `Input.getGamepads()` returns a `java.util.List`, so this resolves to the inline
 * `Collection<T>.isNotEmpty()`. That was checked rather than assumed, because
 * `gamepadIdBuffer` and `GamepadScan` both document the OPPOSITE result for `firstOrNull { }`
 * and `forEach` on the same list.
 */
private fun refreshControlHints()
{
    val arcade = engine.input.gamepads.isNotEmpty() || unmappedGamepadCount() > 0
    if (arcade == arcadeHints && hintPlayAgain.isNotEmpty()) return
    arcadeHints = arcade
    rebuildControlHints()
}

private fun rebuildControlHints()
{
    val startLabel = gamepadButtonLabel(restartButton)
    hintPressStart = ControlHints.pressStart(arcadeHints, startLabel)
    hintPlayAgain = ControlHints.playAgain(arcadeHints, startLabel)
    hintInitialsHelp = ControlHints.initialsHelp(arcadeHints, startLabel)
    hintLegend = ControlHints.legend(arcadeHints, gamepadButtonLabel(kickButton), gamepadButtonLabel(bleedButton))
}
```

- [ ] **Step 3: Seed the cache in `createGame`, not at field-init time**

This ordering is load-bearing. `lifecycle` and `devMode` are *field initialisers*, but `kickButton`/`bleedButton`/`restartButton` are resolved inside `createGame` (`:1056-1063`). A cache built at field-init time would hold labels from before the config was read.

Immediately after the `gamepadButtonCollisionWarnings(...)` block in `createGame` (around `:1090`), add:

```kotlin
// AFTER the button map resolves and BEFORE the first renderGame. A cache seeded at
// field-init time would hold pre-config labels — the fields above are still their
// compiled defaults until the four lines above run.
rebuildControlHints()
```

- [ ] **Step 4: Call the refresh once per frame**

In `updateGame`, immediately before the `lifecycleEdges.begin(...)` call (around `:1498`), add:

```kotlin
// Before the lifecycle reads input, so a pad plugged in this frame is reflected on the
// screen drawn from this frame's state rather than the next one's.
refreshControlHints()
```

- [ ] **Step 5: Retire the three stale literals**

In `object ScreenText`, **delete** `PRESS_START`, `PLAY_AGAIN` and `INITIALS_HELP` and their entries in `all()`. Replace them with a comment where they were:

```kotlin
// PRESS_START, PLAY_AGAIN and INITIALS_HELP used to live here as literals. They named
// buttons that application.cfg can rebind, so the screen could lie; PLAY_AGAIN and
// INITIALS_HELP also named a keyboard key AND a gamepad button in one breath, which is
// half-irrelevant on every machine. They are now composed per-device by
// render/ControlHints.kt and cached on EnPustTil. ControlHintsTest is their font-atlas
// sweep — a composed string can never appear in ScreenText.all().
```

Then update the three call sites to read the cached fields:

- `drawIdleScreen` (`:1963`): `ScreenText.PRESS_START` → `hintPressStart`
- `drawRunOverScreen` (`:2105`): `ScreenText.PLAY_AGAIN` → `hintPlayAgain`
- `drawInitialsEntryScreen` (`:2187`): `ScreenText.INITIALS_HELP` → `hintInitialsHelp`

- [ ] **Step 6: Run the tests to see what breaks**

```bash
./gradlew test
```

Expected: `AttractScreenTest` fails to compile — it references `ScreenText.all()`, which no longer lists the three removed names. Fix by removing those three entries from `ScreenText.all()` (Step 5 already did) and confirming the test file itself does not name them directly. If `AttractScreenTest` names any of the three, delete those references — `ControlHintsTest` covers them now.

- [ ] **Step 7: Run the full suite**

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 8: Window grab — both devices**

Run the grab procedure twice: once with a gamepad connected, once with none.

Confirm on the real screen: with no pad, the attract screen reads **`PRESS SPACE`**; with a pad connected, **`PRESS START`**. Let a run end to see the run-over line, and score above zero to see the initials help line. All three must be legible over the live water and must not have gained or lost a line break.

- [ ] **Step 9: Commit**

```bash
git add -A src/main/kotlin/EnPustTil.kt src/test/kotlin/AttractScreenTest.kt
git commit -m "feat: control prompts name the hardware that is actually attached"
```

**Verification gate:** tests green, and two window grabs proving the string changes with the connected device.

---

## Task 3: `RunLifecycle.BRIEFING` and the pad latch

**Files:**
- Modify: `src/main/kotlin/render/RunLifecycle.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` — the `justStarted` block (`:1600-1638`), the render dispatch (`:1844`)
- Modify: `src/test/kotlin/render/RunLifecycleTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1–2.
- Produces: `RunLifecycleState.BRIEFING`; `RunLifecycle.justEnteredBriefing: Boolean`, `.briefingSkippable: Boolean`, `.briefingCountdownSeconds: Float`, `.briefingAutoStarts: Boolean`; constructor params `briefingSeconds: Float`, `briefingDwellSeconds: Float`; companion constants `BRIEFING_SECONDS = 5f`, `BRIEFING_DWELL_SECONDS = 0.75f`. Task 5 draws from these.

The pad latch lands **with** this task, not after it. It is the difference between a working cabinet and a documented booth failure, and it is meaningless without the state.

> **Expected intermediate state:** after this task the briefing is a 5-second pause showing live water and nothing else. That is correct and Task 5 fills it in. Do not "fix" it here.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/kotlin/render/RunLifecycleTest.kt`. Note `BRIEF` and `BRIEF_DWELL` are new file-level constants; add them beside the existing `DWELL`/`IDLE_TIMEOUT` block at the top.

```kotlin
    // Briefing timings for the tests that actually exercise it. The shared newLifecycle()
    // factory passes briefingSeconds = 0f instead — see `a zero-length briefing...` below.
    private val BRIEF = 3f
    private val BRIEF_DWELL = 0.5f

    private fun briefingLifecycle() = RunLifecycle(
        dwellSeconds = DWELL,
        idleTimeoutSeconds = IDLE_TIMEOUT,
        initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
        pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
        exitHoldSeconds = EXIT_HOLD,
        briefingSeconds = BRIEF,
        briefingDwellSeconds = BRIEF_DWELL
    )

    @Test
    fun `a zero-length briefing goes straight from IDLE to PLAYING`()
    {
        // The rule the whole existing test file rests on. newLifecycle() passes
        // briefingSeconds = 0f, so all 34 tests written before BRIEFING existed keep
        // asserting exactly what they always asserted. A zero-length briefing is not a
        // briefing that closes instantly - it is no briefing at all.
        val lc = newLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "justStarted must still fire on the IDLE transition")
    }

    @Test
    fun `a press in IDLE enters BRIEFING, not PLAYING`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
        assertFalse(lc.justStarted, "the run has not started yet - nothing may build a DiveSim")
        assertTrue(lc.justEnteredBriefing, "EnPustTil latches the pad id on this flag")
    }

    @Test
    fun `justEnteredBriefing is a one-tick event`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertTrue(lc.justEnteredBriefing)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        assertFalse(lc.justEnteredBriefing, "a latched flag would re-latch the pad every frame")
    }

    @Test
    fun `the briefing auto-starts the run when the countdown expires, with no input`()
    {
        // The unattended-recovery guarantee: a player who walks off mid-briefing must not
        // strand the cabinet. The run starts, drowns, and falls through RUN_OVER -> IDLE on
        // the existing timers.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        lc.update(dt = BRIEF + 0.01f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted, "the auto-start is still a run start")
    }

    @Test
    fun `a press before the dwell does not skip the briefing`()
    {
        // The press that OPENS the briefing must not also close it. Edge detection already
        // forces a release-then-press, but a double-tap is ordinary on an arcade button and
        // would otherwise blow straight past the text.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        lc.update(dt = BRIEF_DWELL * 0.5f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }

    @Test
    fun `a press after the dwell skips the briefing`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false)
        lc.update(dt = BRIEF_DWELL + 0.01f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        assertTrue(lc.justStarted)
    }

    @Test
    fun `a held button does not skip the briefing`()
    {
        // A stuck booth encoder button must never be able to skip the explanation for every
        // person in the queue - the same reasoning as the RUN_OVER dwell.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        repeat(10) { lc.update(dt = BRIEF_DWELL * 0.05f, anyInputPressed = true, runOver = false) }
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }

    @Test
    fun `the briefing freezes the simulation but keeps the diver kicking`()
    {
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertFalse(lc.simulationAdvances, "a briefing that burned air would drown the player")
        assertTrue(lc.spriteAnimates, "a frozen diver behind the briefing reads as dead, not idle")
    }

    @Test
    fun `the countdown counts down, never goes negative, and reads zero outside BRIEFING`()
    {
        val lc = briefingLifecycle()
        assertEquals(0f, lc.briefingCountdownSeconds, "IDLE shares timeInState with BRIEFING")
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(BRIEF, lc.briefingCountdownSeconds, 0.001f)
        lc.update(dt = BRIEF * 0.5f, anyInputPressed = false, runOver = false)
        assertEquals(BRIEF * 0.5f, lc.briefingCountdownSeconds, 0.001f)
    }

    @Test
    fun `briefingSkippable is state-blind-proof`()
    {
        // timeInState is shared by the RUN_OVER, PAUSED and ENTER_INITIALS dwells, so an
        // unguarded `timeInState >= briefingDwellSeconds` would read true in IDLE. Harmless
        // today, but it would pin the wrong contract.
        val lc = briefingLifecycle()
        lc.update(dt = BRIEF_DWELL * 5f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.IDLE, lc.state)
        assertFalse(lc.briefingSkippable, "IDLE is not a skippable briefing")

        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertFalse(lc.briefingSkippable, "not yet - the dwell has not elapsed")
        lc.update(dt = BRIEF_DWELL + 0.01f, anyInputPressed = false, runOver = false)
        assertTrue(lc.briefingSkippable)
    }

    @Test
    fun `an infinite briefing never auto-starts but still skips on a press`()
    {
        // The EPT_BRIEFING_HOLD contract: the capture pin must hold the screen open for a
        // screencapture, and must still be dismissable by hand.
        val lc = RunLifecycle(
            dwellSeconds = DWELL,
            idleTimeoutSeconds = IDLE_TIMEOUT,
            initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
            pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
            exitHoldSeconds = EXIT_HOLD,
            briefingSeconds = Float.POSITIVE_INFINITY,
            briefingDwellSeconds = BRIEF_DWELL
        )
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertFalse(lc.briefingAutoStarts, "drawBriefingScreen suppresses the countdown on this")
        lc.update(dt = 10_000f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.BRIEFING, lc.state, "it must hold for the capture")
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
    }

    @Test
    fun `a retry from RUN_OVER does not re-brief`()
    {
        // The owner's decision, pinned so a later "for consistency" change reddens the build.
        // A player who just drowned in eight seconds retries instantly; the briefing is for
        // the person who just walked up.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = BRIEF + 0.01f, anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.PLAYING, lc.state)
        lc.update(dt = 0f, anyInputPressed = false, runOver = true)
        assertEquals(RunLifecycleState.RUN_OVER, lc.state)
        lc.update(dt = DWELL + 0.01f, anyInputPressed = false, runOver = true)
        lc.update(dt = 0f, anyInputPressed = true, runOver = true)
        assertEquals(RunLifecycleState.PLAYING, lc.state, "a retry goes straight back to the water")
    }

    @Test
    fun `Esc during the briefing does not pause`()
    {
        // Consistent with RUN_OVER and ENTER_INITIALS, which also ignore it. A technician
        // waits at most one countdown for the cabinet menu.
        val lc = briefingLifecycle()
        lc.update(dt = 0f, anyInputPressed = true, runOver = false)
        lc.update(dt = 0f, anyInputPressed = false, runOver = false, pausePressed = true)
        assertEquals(RunLifecycleState.BRIEFING, lc.state)
    }
```

- [ ] **Step 2: Change the shared factory — one line**

In `RunLifecycleTest.kt:17`, add one argument to `newLifecycle()`:

```kotlin
    private fun newLifecycle() = RunLifecycle(
        dwellSeconds = DWELL,
        idleTimeoutSeconds = IDLE_TIMEOUT,
        initialsIdleTimeoutSeconds = INITIALS_IDLE_TIMEOUT,
        pauseIdleTimeoutSeconds = PAUSE_IDLE_TIMEOUT,
        exitHoldSeconds = EXIT_HOLD,
        // A zero-length briefing is NO briefing (see `a zero-length briefing...`), so every
        // test written before BRIEFING existed keeps asserting exactly what it always did.
        // Without this, 29 of the 34 would fail: three shared helpers press once and
        // immediately assert the resulting state, and all of them drive with dt = 0f, so a
        // briefing would never expire.
        briefingSeconds = 0f
    )
```

- [ ] **Step 3: Rename the test whose name BRIEFING makes false**

`spriteAnimates agrees with simulationAdvances everywhere except IDLE` → ``spriteAnimates agrees with simulationAdvances everywhere except IDLE and BRIEFING``. **The body does not change** — it only reaches PLAYING and RUN_OVER explicitly. This is a naming fix, and a test whose name is a lie is exactly what this project keeps paying for.

- [ ] **Step 4: Run the tests to verify they fail**

```bash
./gradlew test --tests "render.RunLifecycleTest"
```

Expected: compilation failure — `BRIEFING` is not a member of `RunLifecycleState`, and `RunLifecycle` has no `briefingSeconds` parameter.

- [ ] **Step 5: Implement in `RunLifecycle.kt`**

Add the enum entry:

```kotlin
enum class RunLifecycleState { IDLE, BRIEFING, PLAYING, PAUSED, RUN_OVER, ENTER_INITIALS }
```

Add the two constructor parameters after `exitHoldSeconds`:

```kotlin
    private val briefingSeconds: Float = BRIEFING_SECONDS,
    private val briefingDwellSeconds: Float = BRIEFING_DWELL_SECONDS
```

Add the companion constants beside `DWELL_SECONDS`:

```kotlin
        /**
         * How long the pre-run briefing stays up before the dive starts on its own.
         *
         * This countdown IS the unattended-recovery guarantee for the state: a player who
         * walks away mid-briefing does not strand the cabinet, because the run starts,
         * drowns, and falls through RUN_OVER -> IDLE on the timers above. That is why
         * BRIEFING needs no idle timeout of its own.
         *
         * ZERO OR LESS MEANS NO BRIEFING AT ALL — IDLE goes straight to PLAYING, exactly as
         * it did before this state existed. That is the natural reading of the parameter, it
         * gives a one-constant way to switch the briefing off if it proves too slow in front
         * of a real queue, and it is what lets every test written before BRIEFING keep
         * asserting what it always asserted.
         */
        const val BRIEFING_SECONDS = 5f

        /**
         * How long the briefing ignores input before a press can skip it.
         *
         * The press that OPENS the briefing must not also close it. Edge detection already
         * forces a release-then-press, but a double-tap is ordinary on an arcade button and
         * would blow straight past the text. This is the same guard RUN_OVER uses via
         * [DWELL_SECONDS], at a fifth of the duration — long enough to swallow a double-tap,
         * short enough that a returning player who knows the game is not held up.
         */
        const val BRIEFING_DWELL_SECONDS = 0.75f
```

Add the one-tick flag beside `justStarted`:

```kotlin
    /**
     * True for exactly the [update] call that transitions IDLE -> BRIEFING.
     *
     * EnPustTil latches `lifecycleEdges.firedPadId` on this, and that latch is load-bearing.
     * `firedPadId` is reset to null at the top of EVERY frame, and the briefing's countdown
     * fires [justStarted] on a frame with NO press — so without this flag `activePadId` would
     * be null on the common path, `selectGameplayPad` would fall through to slot 0, and the
     * cabinet would reproduce the exact failure `GamepadScan`'s doc records: a stray HID in
     * slot 0 means "the cabinet's own START press worked and the diver did not move. A
     * startable, unplayable run, silently repeating for every person in the queue."
     */
    var justEnteredBriefing: Boolean = false
        private set
```

Add the three derived accessors near `exitHoldProgress`:

```kotlin
    /**
     * Seconds left before the briefing starts the dive on its own; 0 outside BRIEFING.
     *
     * Guarded on the state because [timeInState] is shared with the RUN_OVER, PAUSED and
     * ENTER_INITIALS dwells — an unguarded form would report a live countdown from the
     * attract screen.
     */
    val briefingCountdownSeconds: Float
        get() = if (state == RunLifecycleState.BRIEFING) (briefingSeconds - timeInState).coerceAtLeast(0f) else 0f

    /**
     * Whether a press would now skip the briefing. The skip hint's visibility is gated on
     * this, so the hint appears at the instant pressing starts working — the affordance and
     * the capability arrive together, and the screen never invites a press that does nothing.
     */
    val briefingSkippable: Boolean
        get() = state == RunLifecycleState.BRIEFING && timeInState >= briefingDwellSeconds

    /**
     * Whether the briefing has a countdown at all. False under `EPT_BRIEFING_HOLD`, which
     * passes an infinite [briefingSeconds] so the screen can be photographed.
     * `drawBriefingScreen` suppresses the countdown line on this: `Float.POSITIVE_INFINITY`
     * converts to `Int.MAX_VALUE`, so the pinned screen would otherwise read
     * "STARTING IN 2147483647" — a defect in the one screen the flag exists to capture.
     */
    val briefingAutoStarts: Boolean get() = briefingSeconds.isFinite()
```

Extend both existing `when`s:

```kotlin
    val simulationAdvances: Boolean get() = when (state)
    {
        RunLifecycleState.IDLE, RunLifecycleState.BRIEFING, RunLifecycleState.PAUSED -> false
        RunLifecycleState.PLAYING, RunLifecycleState.RUN_OVER, RunLifecycleState.ENTER_INITIALS -> true
    }
```

```kotlin
    val spriteAnimates: Boolean get() = when (state)
    {
        RunLifecycleState.PAUSED -> false
        RunLifecycleState.IDLE, RunLifecycleState.BRIEFING, RunLifecycleState.PLAYING,
        RunLifecycleState.RUN_OVER, RunLifecycleState.ENTER_INITIALS -> true
    }
```

**Update `spriteAnimates`' KDoc.** It currently reads *"this is `simulationAdvances` with IDLE added back in, PAUSED excluded"*. Change to *"with IDLE **and BRIEFING** added back in, PAUSED excluded"*, and add: *"BRIEFING joins IDLE for the same reason — a still diver behind the explanation screen reads as a crashed game, not a waiting one."*

Clear the new flag in the `update` prologue, beside the others:

```kotlin
        justEnteredBriefing = false
```

Add the transitions. Replace the IDLE branch and add a BRIEFING branch:

```kotlin
            RunLifecycleState.IDLE ->
                // Esc from attract opens the same screen a paused run gets, so a technician
                // has one way to close the cabinet down and does not have to remember a
                // keyboard shortcut nobody wrote down. Checked AFTER the start press so a
                // player and a technician acting in the same frame gives the player the run.
                if (pressedEdge)
                {
                    // A zero-or-less briefing is NO briefing - straight to PLAYING, exactly
                    // as this did before BRIEFING existed. See BRIEFING_SECONDS.
                    if (briefingSeconds > 0f) enterBriefing()
                    else enter(RunLifecycleState.PLAYING, started = true)
                }
                else if (pauseEdge) enterPause(RunLifecycleState.IDLE)

            RunLifecycleState.BRIEFING ->
                // The dwell first: the press that opened this screen must not also close it.
                // pauseEdge is deliberately NOT handled, exactly as it is not in RUN_OVER or
                // ENTER_INITIALS - a technician waits at most one countdown.
                if (timeInState >= briefingDwellSeconds && pressedEdge)
                    enter(RunLifecycleState.PLAYING, started = true)
                else if (timeInState >= briefingSeconds)
                    enter(RunLifecycleState.PLAYING, started = true)
```

And the private helper, beside `enterPause`:

```kotlin
    private fun enterBriefing()
    {
        enter(RunLifecycleState.BRIEFING)
        justEnteredBriefing = true
    }
```

Note the ordering: `enter` zeroes `timeInState` and would clear a flag set before it, so `justEnteredBriefing` is set **after**.

- [ ] **Step 6: Fix the render dispatch — the fourth compile error**

`EnPustTil.renderGame`'s `when (lifecycle.state)` (`:1844`) now fails to compile. Add the branch:

```kotlin
            // Drawn in Task 5. Until then the briefing is a pause showing live water, which
            // is deliberate and testable: the state machine is what this task delivers.
            RunLifecycleState.BRIEFING -> { }
```

- [ ] **Step 7: The pad latch**

In `updateGame`, immediately **before** the `if (lifecycle.justStarted)` block (`:1600`), add:

```kotlin
        // Latch the pad that opened the briefing. The countdown auto-start fires justStarted
        // on a frame with NO press, and lifecycleEdges.firedPadId is nulled at the top of
        // every frame — so without this the common path binds gameplay to slot 0. See
        // RunLifecycle.justEnteredBriefing, and GamepadScan's "startable, unplayable run".
        if (lifecycle.justEnteredBriefing) activePadId = lifecycleEdges.firedPadId
```

And change the first line **inside** the `justStarted` block from

```kotlin
            activePadId = lifecycleEdges.firedPadId
```

to

```kotlin
            // `?:` keeps the value latched when the briefing opened, for the auto-start frame
            // where no edge fired. Every other route here — a press that skipped the
            // briefing, a RUN_OVER retry, a zero-length briefing — has a real edge this
            // frame, so firedPadId wins and the behaviour is identical to before.
            activePadId = lifecycleEdges.firedPadId ?: activePadId
```

- [ ] **Step 8: Pin the latch with a source scan**

The latch cannot be caught by a runtime test: it takes two well-tested `RunLifecycle` flags plus a *wiring* bug in the file that reacts to them, and it only manifests on a machine with a second gamepad. That is precisely the case `UpdateGameOrderingTest` and `EnPustTilDepthPinTest` already solve by scanning the source, and its own doc says so in as many words.

Create `src/test/kotlin/PadLatchOrderingTest.kt`:

```kotlin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The briefing's auto-start fires `justStarted` on a frame with NO press, and
 * `LifecycleInputEdges.firedPadId` is reset to null at the top of EVERY frame (`begin()`).
 * So on the COMMON path — a player presses once, reads the briefing, and lets the countdown
 * run — `activePadId` would be null, and `selectGameplayPad` would fall through to
 * `padIds.firstOrNull()`, i.e. slot 0.
 *
 * That is verbatim the booth failure `GamepadScan`'s class doc exists to record: with a stray
 * HID in slot 0, "the cabinet's own START press worked and the diver did not move. A
 * startable, unplayable run, silently repeating for every person in the queue."
 *
 * Two things keep it fixed, and BOTH are wiring rather than logic:
 *   1. `activePadId` is latched on `lifecycle.justEnteredBriefing`, and
 *   2. the `justStarted` block falls back to that latch with `?: activePadId` instead of
 *      overwriting it with this frame's null.
 *
 * A runtime test cannot see either — it needs two gamepads and a five-second wait. So this is
 * a source scan, in the style of `UpdateGameOrderingTest`.
 */
class PadLatchOrderingTest
{
    private val body: String
    init
    {
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val afterSignature = source.substringAfter("private fun updateGame()")
        body = afterSignature.substring(0, afterSignature.indexOf("\n    override fun onDestroy"))
    }

    @Test
    fun `the pad that opened the briefing is latched before the run can start`()
    {
        val latchIndex = body.indexOf("if (lifecycle.justEnteredBriefing) activePadId = lifecycleEdges.firedPadId")
        val startedIndex = body.indexOf("if (lifecycle.justStarted)")

        assertTrue(
            latchIndex >= 0,
            "updateGame no longer latches activePadId on lifecycle.justEnteredBriefing. The " +
            "briefing's countdown fires justStarted on a frame with no press, and firedPadId " +
            "is nulled every frame — without this latch the run binds to gamepad slot 0. See " +
            "GamepadScan's \"startable, unplayable run\"."
        )
        assertTrue(startedIndex >= 0, "updateGame no longer checks lifecycle.justStarted — re-read this test")
        assertTrue(
            latchIndex < startedIndex,
            "the justEnteredBriefing latch is at offset $latchIndex, AFTER the justStarted " +
            "block at $startedIndex. The latch must be established before the block that reads it."
        )
    }

    @Test
    fun `the run start falls back to the latched pad instead of overwriting it with null`()
    {
        assertTrue(
            body.contains("activePadId = lifecycleEdges.firedPadId ?: activePadId"),
            "the justStarted block assigns activePadId without the `?: activePadId` fallback. " +
            "On the briefing's auto-start frame no edge fired, so firedPadId is null and this " +
            "would discard the pad latched when the briefing opened — binding gameplay to " +
            "slot 0 for every player who lets the countdown run."
        )
    }
}
```

Run it:

```bash
./gradlew test --tests "PadLatchOrderingTest"
```

Expected: PASS, given Step 7's edits. If it fails, Step 7 was not applied verbatim — the assertions match exact source text on purpose, because a paraphrase of this wiring is how the defect returns.

- [ ] **Step 9: Run the tests**

```bash
./gradlew test
```

Expected: PASS, including all 34 pre-existing `RunLifecycleTest` tests unmodified. **If any of the 34 fail, stop and report it** — the zero-length rule was supposed to make that impossible, and a failure means the rule is wrong rather than the test.

- [ ] **Step 10: Window grab**

Run the standard procedure. Press start, and confirm on the real screen that the game pauses for about five seconds showing live water, then begins the dive on its own. Also confirm a press after the first second starts it early. **The screen is intentionally blank of text at this stage.**

- [ ] **Step 11: Commit**

```bash
git add -A src/main/kotlin/render/RunLifecycle.kt src/main/kotlin/EnPustTil.kt src/test/kotlin/render/RunLifecycleTest.kt src/test/kotlin/PadLatchOrderingTest.kt
git commit -m "feat: BRIEFING lifecycle state, with the pad latch its auto-start requires"
```

**Verification gate:** all tests green including the untouched 34 and the new source scan; window grab showing the five-second hold and the auto-start.

---

## Task 4: The briefing screen

**Files:**
- Modify: `src/main/kotlin/EnPustTil.kt` — `ScreenText`, a new `object BriefingLayout`, `drawBriefingScreen`, the dispatch branch from Task 3, `EPT_BRIEFING_HOLD` in the `lifecycle` field initialiser
- Create: `src/test/kotlin/render/BriefingScreenTest.kt`

**Interfaces:**
- Consumes: `ControlHints` (Task 1), `arcadeHints`/`gamepadButtonLabel` (Task 2), `lifecycle.briefingCountdownSeconds`/`.briefingSkippable`/`.briefingAutoStarts` (Task 3).
- Produces: `BriefingLayout` with the anchors below. Nothing after this consumes it.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/render/BriefingScreenTest.kt`:

```kotlin
package render

import BriefingLayout
import DefaultFont
import ScreenText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Geometry for the pre-run briefing.
 *
 * Modelled on `AttractScreenTest`'s layout half, and deliberately NOT a "does it fit at 4:3,
 * 16:9 and 32:9" vertical test: every anchor here is a fraction of screen HEIGHT, so vertical
 * fit is aspect-invariant BY CONSTRUCTION and such a test could never fail. The genuine
 * aspect-dependent risk is WIDTH at the narrowest panel, which is what is asserted below.
 */
class BriefingScreenTest
{
    /** An upper bound on a glyph's advance in the default font. Same estimate `Hud` uses. */
    private val EM = 0.62f

    private fun widthOf(text: String, fontFraction: Float) = text.length * EM * fontFraction

    private fun boxes(): List<Triple<String, Float, Float>> = listOf(
        Triple("title", BriefingLayout.TITLE_Y, BriefingLayout.TITLE_FONT),
        Triple("row0", BriefingLayout.rowY(0), BriefingLayout.ROW_FONT),
        Triple("row1", BriefingLayout.rowY(1), BriefingLayout.ROW_FONT),
        Triple("row2", BriefingLayout.rowY(2), BriefingLayout.ROW_FONT),
        Triple("rule", BriefingLayout.RULE_Y, BriefingLayout.RULE_FONT),
        Triple("countdown", BriefingLayout.COUNTDOWN_Y, BriefingLayout.COUNTDOWN_FONT),
        Triple("skip", BriefingLayout.SKIP_Y, BriefingLayout.SKIP_FONT)
    )

    @Test
    fun `every anchor is a screen fraction strictly inside the screen`()
    {
        for ((name, y, font) in boxes())
        {
            assertTrue(y > 0f && y < 1f, "$name anchor $y is not a fraction in (0,1)")
            assertTrue(font > 0f && font < 1f, "$name font $font is not a fraction in (0,1)")
            assertTrue(y + font < 1f, "$name runs off the bottom: ${y + font}")
        }
    }

    @Test
    fun `no two elements overlap`()
    {
        // Text grows DOWNWARD from its anchor, so a block occupies y .. y + fontSize - the
        // convention AttractLayout documents.
        val ordered = boxes()
        for (i in 0 until ordered.size - 1)
        {
            val (nameA, yA, fontA) = ordered[i]
            val (nameB, yB, _) = ordered[i + 1]
            assertTrue(
                yA + fontA <= yB,
                "$nameA ends at ${yA + fontA}, which is below $nameB's anchor $yB"
            )
        }
    }

    @Test
    fun `the widest line fits across the narrowest plausible panel`()
    {
        // 4:3 is the narrowest aspect this project tests anywhere (AttractScreenTest checks
        // its leaderboard row against h * 4/3). The risk is horizontal, not vertical.
        val screenWidth = 4f / 3f      // in units of screen height
        val widest = ScreenText.BRIEFING_RULE
        val half = widthOf(widest, BriefingLayout.RULE_FONT) * 0.5f
        assertTrue(
            half < screenWidth * 0.5f,
            "\"$widest\" needs ${half * 2f}h of width; a 4:3 panel has ${screenWidth}h"
        )
    }

    @Test
    fun `the two control columns do not collide at the widest button label`()
    {
        // A rebind can turn "A" into "RIGHT BUMPER". The token column is right-aligned at
        // centre - gap and the verb column left-aligned at centre + gap, so the tokens keep a
        // clean right edge whatever their width - but the pair still has to fit.
        val token = ControlHints.labelFor("RIGHT_BUMPER")
        val verb = ScreenText.BRIEFING_VERB_BLEED
        val total = widthOf(token, BriefingLayout.ROW_FONT) +
                    widthOf(verb, BriefingLayout.ROW_FONT) +
                    BriefingLayout.COLUMN_GAP * 2f
        assertTrue(total < 4f / 3f, "the widest control row needs ${total}h across a 1.333h panel")
    }

    @Test
    fun `every briefing string is drawable`()
    {
        val strings = listOf(
            ScreenText.BRIEFING_TITLE,
            ScreenText.BRIEFING_RULE,
            ScreenText.BRIEFING_SKIP_SUFFIX,
            ScreenText.BRIEFING_VERB_SWIM,
            ScreenText.BRIEFING_VERB_KICK,
            ScreenText.BRIEFING_VERB_BLEED,
            ScreenText.briefingCountdown(5)
        )
        for (text in strings)
            assertTrue(
                DefaultFont.undrawableCodePointsIn(text).isEmpty(),
                "\"$text\" is not inside the default font's atlas"
            )
    }

    // NOTE: "the countdown line is absent under EPT_BRIEFING_HOLD" is NOT asserted here.
    // Suppression happens inside drawBriefingScreen, which needs a GL context. The FLAG that
    // drives it (`briefingAutoStarts`) is pinned in RunLifecycleTest, and the drawn result is
    // checked by the pinned window grab in Step 8 - which is the only evidence that can
    // actually show a string on a screen.

    @Test
    fun `the control rows are evenly spaced and ordered downward`()
    {
        // Guards the rowY arithmetic itself: a sign error or an off-by-one in the index would
        // still satisfy the overlap test if it happened to stay ordered.
        val step = BriefingLayout.rowY(1) - BriefingLayout.rowY(0)
        assertTrue(step > 0f, "rows must descend")
        assertTrue(
            kotlin.math.abs((BriefingLayout.rowY(2) - BriefingLayout.rowY(1)) - step) < 1e-5f,
            "row spacing is not uniform"
        )
        assertTrue(step > BriefingLayout.ROW_FONT, "rows are closer together than they are tall")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "render.BriefingScreenTest"
```

Expected: compilation failure — `Unresolved reference: BriefingLayout`.

- [ ] **Step 3: Add the strings to `ScreenText`**

```kotlin
    // --- The pre-run briefing ----------------------------------------------------------
    // Controls plus the ONE rule the game does not otherwise teach. Air, the anglerfish and
    // the point-of-no-return are deliberately absent: everything on this screen costs reading
    // time in front of a queue, and those three teach themselves by happening.

    const val BRIEFING_TITLE = "HOW TO DIVE"

    /** The one thing a player must know that nothing else on screen ever says. */
    const val BRIEFING_RULE = "SURFACE TO BANK YOUR PEARLS"

    const val BRIEFING_VERB_SWIM = "swim"
    const val BRIEFING_VERB_KICK = "kick"
    const val BRIEFING_VERB_BLEED = "bleed pearls"

    /** Completes "PRESS <button>" — the button half comes from ControlHints.confirm. */
    const val BRIEFING_SKIP_SUFFIX = " TO DIVE NOW"

    fun briefingCountdown(seconds: Int) = "STARTING IN $seconds"
```

Add `BRIEFING_TITLE`, `BRIEFING_RULE`, the three verbs, `BRIEFING_SKIP_SUFFIX` and `briefingCountdown(5)` to `ScreenText.all()`.

- [ ] **Step 4: Add `BriefingLayout`**

Place it beside `AttractLayout` and `PauseLayout` in `EnPustTil.kt`:

```kotlin
/**
 * Anchors for the pre-run briefing. Values only, no logic — the presentation twin of
 * [AttractLayout], and read the same way: every number is a fraction of screen HEIGHT (never
 * width, never a pixel count — the booth panel's aspect is not known in advance), and text
 * grows DOWNWARD from its anchor, so a block occupies `y .. y + fontSize`.
 *
 * WHY THIS SCREEN HAS A SCRIM AND THE ATTRACT SCREEN DOES NOT. [AttractLayout] reserves
 * `DIVER_SCREEN_FRACTION ± DIVER_HALO_HALF_HEIGHT` for the diver and lays its four elements
 * around that band. The briefing has seven and does not fit around it, so it darkens the
 * world instead and uses the full height. The scrim is LIGHTER than [PauseLayout.SCRIM_ALPHA]
 * on purpose: the two screens already differ in heading and content, and differing in weight
 * as well is what stops a player reading "the machine is waiting for me" as "the machine is
 * stopped".
 *
 * Nothing is drawn beneath the scrim but the live world — no attract sign, no leaderboard, no
 * HUD. The briefing is the only thing on screen to read, and a leaderboard competing with it
 * is the one thing that would stop a first-timer reaching the rule line. The cost is that the
 * board is hidden for up to one countdown per play, which is accepted: it is on screen the
 * whole time nobody is playing, which is most of the day.
 *
 * @see BriefingScreenTest, which pins the non-overlap and the width fit.
 */
object BriefingLayout
{
    /**
     * Lighter than [PauseLayout.SCRIM_ALPHA] (0.72). Authored alpha — pass it through
     * [Hud.authoredAlphaFor], because this surface stores alpha squared.
     */
    const val SCRIM_ALPHA = 0.55f

    const val TITLE_Y = 0.16f
    const val TITLE_FONT = 0.055f

    /** Top of the first control row. */
    const val ROWS_TOP_Y = 0.30f
    const val ROW_FONT = 0.030f

    /** Row pitch as a multiple of [ROW_FONT] — 1.9 leaves most of a line of air between rows. */
    const val ROW_SPACING = 1.9f

    /**
     * Half the gutter between the two columns. The control token is drawn `xOrigin = 1f`
     * (RIGHT-aligned) at `centreX - COLUMN_GAP * h` and the verb `xOrigin = 0f` (LEFT-aligned)
     * at `centreX + COLUMN_GAP * h`, so the tokens keep a clean right edge whatever their
     * width — which matters, because a rebind can turn `A` into `RIGHT BUMPER`.
     *
     * NOTE this is the OPPOSITE of [AttractLayout]'s leaderboard, which aligns its columns
     * OUTWARD (`rankX` is `xOrigin = 0` at `centre - halfSpan`). Copying `rankX`/`scoreX` here
     * gets the alignments backwards and loses exactly the property this buys.
     */
    const val COLUMN_GAP = 0.012f

    const val RULE_Y = 0.53f
    const val RULE_FONT = 0.034f

    const val COUNTDOWN_Y = 0.66f
    const val COUNTDOWN_FONT = 0.028f

    const val SKIP_Y = 0.74f
    const val SKIP_FONT = 0.022f

    fun rowY(index: Int): Float = ROWS_TOP_Y + ROW_FONT * ROW_SPACING * index
}
```

- [ ] **Step 5: Write `drawBriefingScreen`**

Add beside `drawPauseScreen` in `EnPustTil`:

```kotlin
    /**
     * The pre-run briefing: what the three controls do, plus the one rule the game never
     * otherwise states. Drawn over a darkened live world; see [BriefingLayout] for why this
     * screen has a scrim and the attract screen does not.
     */
    private fun drawBriefingScreen(hud: Surface, w: Float, h: Float)
    {
        // Authored alpha, not displayed alpha - this surface stores alpha squared, so asking
        // for 0.55 directly would land near 0.3. See Hud's doc for the measurement.
        hud.setDrawColor(0f, 0f, 0f, Hud.authoredAlphaFor(BriefingLayout.SCRIM_ALPHA))
        hud.fillRect(0f, 0f, w, h)

        val centreX = w * 0.5f
        val gap = h * BriefingLayout.COLUMN_GAP

        hud.drawTextWithOutline(
            ScreenText.BRIEFING_TITLE,
            centreX, h * BriefingLayout.TITLE_Y,
            h * BriefingLayout.TITLE_FONT, h, Color.WHITE, xOrigin = 0.5f
        )

        // Token right-aligned, verb left-aligned - INWARD, unlike the leaderboard's outward
        // columns. See BriefingLayout.COLUMN_GAP.
        drawBriefingRow(hud, 0, ControlHints.swim(arcadeHints), ScreenText.BRIEFING_VERB_SWIM, centreX, gap, h)
        drawBriefingRow(hud, 1, ControlHints.kick(arcadeHints, gamepadButtonLabel(kickButton)), ScreenText.BRIEFING_VERB_KICK, centreX, gap, h)
        drawBriefingRow(hud, 2, ControlHints.bleed(arcadeHints, gamepadButtonLabel(bleedButton)), ScreenText.BRIEFING_VERB_BLEED, centreX, gap, h)

        // Amber, not white: this is the one thing a player must know that nothing else on
        // screen ever says. Same literal drawPauseScreen uses for its exit bar - deliberately
        // NOT Hud.noReturnMark, which is public but is a TAPE colour with its own authored
        // alpha and a different RGB.
        hud.drawTextWithOutline(
            ScreenText.BRIEFING_RULE,
            centreX, h * BriefingLayout.RULE_Y,
            h * BriefingLayout.RULE_FONT, h, 1f, 0.85f, 0.3f, 1f, xOrigin = 0.5f
        )

        // Suppressed under EPT_BRIEFING_HOLD: briefingSeconds is infinite there, and
        // Float.POSITIVE_INFINITY.toInt() is Int.MAX_VALUE, so this would read
        // "STARTING IN 2147483647" - on the one screen the flag exists to photograph.
        if (lifecycle.briefingAutoStarts)
        {
            val seconds = ceil(lifecycle.briefingCountdownSeconds).toInt()
            hud.drawTextWithOutline(
                ScreenText.briefingCountdown(seconds),
                centreX, h * BriefingLayout.COUNTDOWN_Y,
                h * BriefingLayout.COUNTDOWN_FONT, h, Color.WHITE, xOrigin = 0.5f
            )
        }

        // Only once a press would actually do something. The hint appearing IS the
        // affordance, so the screen never invites a press that does nothing.
        if (lifecycle.briefingSkippable)
        {
            val skip = "PRESS " + ControlHints.confirm(arcadeHints, gamepadButtonLabel(restartButton)) +
                       ScreenText.BRIEFING_SKIP_SUFFIX
            hud.drawTextWithOutline(
                skip,
                centreX, h * BriefingLayout.SKIP_Y,
                h * BriefingLayout.SKIP_FONT, h, Color.WHITE, xOrigin = 0.5f
            )
        }
    }

    private fun drawBriefingRow(hud: Surface, index: Int, token: String, verb: String, centreX: Float, gap: Float, h: Float)
    {
        val y = h * BriefingLayout.rowY(index)
        val font = h * BriefingLayout.ROW_FONT
        hud.drawTextWithOutline(token, centreX - gap, y, font, h, Color.WHITE, xOrigin = 1f)
        hud.drawTextWithOutline(verb, centreX + gap, y, font, h, Color.WHITE, xOrigin = 0f)
    }
```

Add `import kotlin.math.ceil` if absent.

> The skip line and the countdown allocate a String per frame. That is the HUD text-formatting exemption, and it is bounded at two short strings — the same latitude `Hud.drawClock` and `drawHeld` already take.

- [ ] **Step 6: Wire the dispatch and `EPT_BRIEFING_HOLD`**

Replace Task 3's placeholder branch:

```kotlin
            RunLifecycleState.BRIEFING -> drawBriefingScreen(hud, w, h)
```

And in the `lifecycle` field initialiser, pass the pin:

```kotlin
    /**
     * EPT_BRIEFING_HOLD pins the briefing open so it can be photographed. The documented
     * window-grab route takes about fourteen seconds to reach a screencapture, which is
     * longer than the briefing lasts — without this the one screen that most needs a real
     * frame is the one screen that cannot be captured. One getenv, unset at the booth, same
     * cost as EPT_DEV / EPT_DEPTH / EPT_FAIL_BOOT. Read HERE rather than in RunLifecycle so
     * that class stays a pure object with no environment reads.
     */
    private val lifecycle = RunLifecycle(
        briefingSeconds =
            if (System.getenv("EPT_BRIEFING_HOLD") != null) Float.POSITIVE_INFINITY
            else RunLifecycle.BRIEFING_SECONDS
    )
```

Match the existing style for reading `EPT_*` flags in this file — if the others use a helper, use it here too rather than calling `System.getenv` directly.

- [ ] **Step 7: Run the tests**

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 8: Window grab — the pinned screen, twice**

```bash
EPT_BRIEFING_HOLD=1 ./gradlew run > /dev/null 2>&1 &
```

…then the rest of the grab procedure, pressing start once so the briefing opens.

Check on the real frame:
1. All seven lines are legible over live water, and none overlaps the diver or another line.
2. The countdown line is **absent** (that is the pin working).
3. The skip hint appears after about three quarters of a second.
4. The scrim is visibly lighter than the pause screen's — open the pause screen with Esc from attract and compare.
5. The token column has a clean right edge.

Then grab again **without** the pin to confirm the countdown appears and ticks down.

**Tune `SCRIM_ALPHA` from what you see, not from reasoning.** The value in Step 4 is a starting point.

- [ ] **Step 9: Commit**

```bash
git add -A src/main/kotlin/EnPustTil.kt src/test/kotlin/render/BriefingScreenTest.kt
git commit -m "feat: the pre-run briefing screen, with a capture pin to photograph it"
```

**Verification gate:** tests green; two window grabs (pinned and live) showing all five checks above.

---

## Task 5: The in-run control legend

**Files:**
- Modify: `src/main/kotlin/render/Hud.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` — the dispatch (`:1844-1873`)
- Modify: `src/test/kotlin/render/HudTest.kt`

**Interfaces:**
- Consumes: `hintLegend` (Task 2).
- Produces: `Hud.renderControlLegend(surface, text, w, h)`, `Hud.legendBaselineY(h)`, `Hud.legendRightX(w, h)`, `Hud.LEGEND_FONT_FRACTION`.

- [ ] **Step 1: Write the failing test**

Append to `src/test/kotlin/render/HudTest.kt`:

```kotlin
    // --- The in-run control legend --------------------------------------------------

    /** Same upper-bound em estimate the clock box sizes itself with. */
    private val LEGEND_EM = 0.62f

    @Test
    fun `the legend clears the clock box on the narrowest plausible panel`()
    {
        // THE one legend relationship that can actually fail, and therefore the one asserted
        // numerically. Everything else about the legend's corner is structural (see the two
        // tests below); this is arithmetic and it is tight.
        val h = 1000f
        val w = h * 4f / 3f                      // 4:3, the narrowest aspect tested anywhere here

        // The longest legend a rebind can produce: both action buttons on bumpers.
        val longest = "STICK swim  ·  RIGHT BUMPER kick  ·  LEFT BUMPER bleed"
        val legendWidth = longest.length * LEGEND_EM * h * Hud.LEGEND_FONT_FRACTION
        val legendLeft = Hud.legendRightX(w, h) - legendWidth

        val clockRight = w * 0.5f + Hud.clockBoxWidth(Hud.clockFontSize(h), 4) * 0.5f

        assertTrue(
            legendLeft > clockRight,
            "the legend starts at $legendLeft and the clock box ends at $clockRight - they overlap"
        )
    }

    @Test
    fun `the legend sits above the depth tape and inside the screen`()
    {
        val h = 1000f
        val w = h * 16f / 9f
        val bottom = Hud.legendBaselineY(h) + h * Hud.LEGEND_FONT_FRACTION

        assertTrue(Hud.legendBaselineY(h) > 0f, "the legend runs off the top")
        assertTrue(bottom < h * 0.10f, "the legend collides with the depth tape, which starts at 0.10h")
        assertTrue(Hud.legendRightX(w, h) < w, "the legend runs off the right edge")
    }

    @Test
    fun `the legend can never collide with the HELD numerals`()
    {
        // This is the assertion that moved the legend out of the bottom-left corner, where an
        // earlier design put it. HELD hangs HELD_OFFSET_METRES below the diver, and the diver
        // is bounded ABOVE by DIVER_MIN_FRACTION - so the highest HELD can ever reach is
        // that fraction plus the offset, converted at the LARGEST visible depth (which gives
        // the smallest pixels-per-metre and therefore the smallest offset in screen terms).
        val h = 1000f
        val largestVisibleDepth = Framing.VISIBLE_DEPTH_METRES          // the loosest case
        val heldOffsetFraction = Hud.HELD_OFFSET_METRES / largestVisibleDepth
        val highestHeldTop = h * (Framing.DIVER_MIN_FRACTION + heldOffsetFraction)

        val legendBottom = Hud.legendBaselineY(h) + h * Hud.LEGEND_FONT_FRACTION
        assertTrue(
            legendBottom < highestHeldTop,
            "the legend ends at $legendBottom and HELD can reach up to $highestHeldTop"
        )
    }
```

`HELD_OFFSET_METRES` and `clockFontSize` are currently `private`. Widen them to internal/public with a one-line comment saying the test needs them — that is an established pattern in this file (`clockBoxWidth` is already public for exactly this reason).

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "render.HudTest"
```

Expected: compilation failure — `Unresolved reference: LEGEND_FONT_FRACTION`.

- [ ] **Step 3: Implement in `Hud.kt`**

Add beside the other fraction constants:

```kotlin
    /**
     * The in-run control legend's height fraction. Between the tape's graduation labels
     * (0.014) and its travelling depth readout (0.018): present, and subordinate to both the
     * clock and HELD.
     */
    const val LEGEND_FONT_FRACTION = 0.016f

    private const val LEGEND_MARGIN_FRACTION = 0.02f
```

Add the colour beside `cold`:

```kotlin
    /**
     * The legend's ink. A SEPARATE pre-allocated Color rather than `cold` at a lower alpha:
     * `cold` is a shared mutable singleton (Color has four mutable float fields), so lowering
     * "its" alpha would silently re-tint BANKED, the clock, the tape handle and every
     * graduation label — and building one per frame is forbidden outright.
     */
    private val legendInk = Color(0.75f, 0.85f, 1f, authoredAlphaFor(0.55f))
```

Add the layout helpers beside the other pure ones:

```kotlin
    /** Top of the legend's text box. Same top margin `drawBanked` uses. */
    fun legendBaselineY(h: Float): Float = h * LEGEND_MARGIN_FRACTION

    /** The legend is right-aligned, so this is where its text ENDS. */
    fun legendRightX(w: Float, h: Float): Float = w - h * LEGEND_MARGIN_FRACTION
```

And the draw call:

```kotlin
    /**
     * The one-line "what do I press" legend, top-right, for the whole run.
     *
     * WHY TOP-RIGHT AND NOT BOTTOM-LEFT, which is where a legend conventionally goes: the
     * bottom-left corner is not free. `DiveCamera` clamps the camera's lag at
     * `DIVER_MAX_FRACTION` of the visible depth, so the diver can sit as low as 0.81h, and
     * [HELD_OFFSET_METRES] hangs the held count below him at a font that grows with the haul
     * — worst case the numerals straddle the bottom margin entirely, and they are centred on
     * a diver whose x roams the whole column. Top-right is provably clear instead: HELD hangs
     * BELOW a diver bounded above by `DIVER_MIN_FRACTION`, so it can never reach the top
     * strip at all; the depth tape starts at `TAPE_TOP_FRACTION`; and BANKED is top-left.
     * The only relationship left that can fail is the clock box, which `HudTest` pins
     * numerically at 4:3.
     */
    fun renderControlLegend(surface: Surface, text: String, w: Float, h: Float)
    {
        surface.drawTextWithOutline(
            text,
            legendRightX(w, h), legendBaselineY(h),
            h * LEGEND_FONT_FRACTION, h, legendInk, xOrigin = 1f
        )
    }
```

- [ ] **Step 4: Call it from the dispatch — two branches, not one**

In `renderGame`'s `when`, add the legend to **PLAYING** and to the **paused-from-a-run** case:

```kotlin
            RunLifecycleState.PLAYING ->
            {
                Hud.render(hud, sim, diverX, diverY, pixelsPerMetre, w, h, aimDegrees)
                Hud.renderControlLegend(hud, hintLegend, w, h)
            }
```

and inside the PAUSED branch, where it currently calls `Hud.render(...)` for a paused run:

```kotlin
                else
                {
                    Hud.render(hud, sim, diverX, diverY, pixelsPerMetre, w, h, aimDegrees)
                    // Included deliberately. drawPauseScreen's own comment says a complete HUD
                    // behind the scrim - "a stopped clock and a full ring of bubbles" - is the
                    // clearest statement that the run is being HELD, not ended. A legend that
                    // vanished on pause would contradict that.
                    Hud.renderControlLegend(hud, hintLegend, w, h)
                }
```

**Not** in `RUN_OVER` or `ENTER_INITIALS`: there the play-again and initials hints are the relevant controls, and a swim/kick/bleed legend under them is noise.

Keep both calls at the dispatch rather than threading a nullable parameter into `Hud.render` — that function already takes eight parameters, and this way "these two states, not those two" is visible at the one place that knows the state.

- [ ] **Step 5: Run the tests**

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 6: Window grab — deep, with a big haul**

Run the standard procedure, then play a run: dive past 100 m and collect several pearls so `HELD` is large and the diver is low in the frame. Grab there.

Confirm: the legend is readable but clearly subordinate to the clock; it does not touch the clock box, the depth tape, or the HELD numerals at their largest. Pause with Esc mid-run and confirm the legend is **still** on screen behind the scrim.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/kotlin/render/Hud.kt src/main/kotlin/EnPustTil.kt src/test/kotlin/render/HudTest.kt
git commit -m "feat: permanent in-run control legend, top-right where nothing else reaches"
```

**Verification gate:** tests green; a deep-run grab with a large HELD count showing no collision, and a paused grab showing the legend survives.

---

## Task 6: `PauseScreenTest` — make a stale KDoc claim true

**Files:**
- Create: `src/test/kotlin/render/PauseScreenTest.kt`

`PauseLayout`'s KDoc (`EnPustTil.kt:737`) says its relationships are checked, "see `PauseScreenTest`". **No such file exists**, and `PauseLayout` is referenced by no test at all. The choice is to make the claim true or delete it; the owner chose to make it true.

- [ ] **Step 1: Write the test**

Create `src/test/kotlin/render/PauseScreenTest.kt`:

```kotlin
package render

import PauseLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry for the pause / cabinet-menu screen.
 *
 * WHY THIS EXISTS: `PauseLayout`'s own KDoc said its relationships were checked "see
 * PauseScreenTest", and no such file existed — the layout was referenced by no test at all,
 * unlike the attract screen's ten. A doc citing a test that does not exist is worse than an
 * acknowledged gap, because it stops the next reader looking.
 *
 * Same convention as `AttractScreenTest`'s layout half: text grows DOWNWARD from its anchor,
 * so a block occupies `y .. y + fontSize`.
 */
class PauseScreenTest
{
    @Test
    fun `every anchor is a screen fraction strictly inside the screen`()
    {
        val anchors = listOf(
            "title" to PauseLayout.TITLE_Y,
            "resume" to PauseLayout.RESUME_Y,
            "exit" to PauseLayout.EXIT_Y,
            "bar" to PauseLayout.BAR_Y
        )
        for ((name, y) in anchors)
            assertTrue(y > 0f && y < 1f, "$name anchor $y is not a fraction in (0,1)")

        assertTrue(PauseLayout.SCRIM_ALPHA > 0f && PauseLayout.SCRIM_ALPHA <= 1f)
        assertTrue(PauseLayout.TITLE_FONT > 0f && PauseLayout.HINT_FONT > 0f)
        assertTrue(PauseLayout.BAR_HEIGHT > 0f && PauseLayout.BAR_HALF_SPAN > 0f)
    }

    @Test
    fun `the four elements do not overlap, top to bottom`()
    {
        assertTrue(
            PauseLayout.TITLE_Y + PauseLayout.TITLE_FONT <= PauseLayout.RESUME_Y,
            "the title runs into the resume hint"
        )
        assertTrue(
            PauseLayout.RESUME_Y + PauseLayout.HINT_FONT <= PauseLayout.EXIT_Y,
            "the resume hint runs into the exit hint"
        )
        assertTrue(
            PauseLayout.EXIT_Y + PauseLayout.HINT_FONT <= PauseLayout.BAR_Y,
            "the exit hint runs into the progress bar"
        )
        assertTrue(
            PauseLayout.BAR_Y + PauseLayout.BAR_HEIGHT < 1f,
            "the progress bar runs off the bottom"
        )
    }

    @Test
    fun `the exit bar's fill is bounded by its own track`()
    {
        val h = 1000f
        val track = PauseLayout.barTrackWidth(h)
        assertEquals(0f, PauseLayout.barFillWidth(0f, h), 1e-4f, "an untouched key must show nothing")
        assertEquals(track, PauseLayout.barFillWidth(1f, h), 1e-4f, "a completed hold must fill the track")
        assertTrue(PauseLayout.barFillWidth(0.5f, h) < track, "a half hold must not fill the track")
        assertTrue(PauseLayout.barFillWidth(0.5f, h) > 0f, "a half hold must show something")
    }

    @Test
    fun `the bar is centred on the same axis as the text above it`()
    {
        // The hints are drawn at centreX with xOrigin = 0.5; the bar is drawn from a left
        // edge. If these disagree the screen reads as broken even though every element is
        // individually on screen.
        val h = 1000f
        val centreX = 800f
        val left = PauseLayout.barX(centreX, h)
        val centreOfBar = left + PauseLayout.barTrackWidth(h) * 0.5f
        assertEquals(centreX, centreOfBar, 1e-3f, "the exit bar is not centred under its label")
    }

    @Test
    fun `the exit bar fits across the narrowest plausible panel`()
    {
        // What actually bounds BAR_HALF_SPAN: the bar is sized from screen HEIGHT but drawn
        // across screen WIDTH, so on a narrow panel it is the width that runs out first. 4:3
        // is the narrowest aspect anything in this project is tested against.
        //
        // NOTE: an earlier draft of this plan asserted `barTrackWidth(h) == h * BAR_HALF_SPAN
        // * 2f` here, which is barTrackWidth's own DEFINITION — both sides move together, so
        // no change to the constant could ever fail it. It tested the compiler. Replaced
        // after review; see CLAUDE.md on why a test that cannot fail is worse than no test.
        val h = 1000f
        val narrowestWidth = h * 4f / 3f
        assertTrue(
            PauseLayout.barTrackWidth(h) < narrowestWidth,
            "the exit bar is ${PauseLayout.barTrackWidth(h)} wide on a $narrowestWidth panel - it runs off the screen"
        )
    }

    @Test
    fun `the exit bar's fill grows monotonically with the hold`()
    {
        // The bar is the only feedback a technician gets that holding the key is working, so
        // it has to grow the whole way through rather than jumping or stalling. Pins
        // barFillWidth's contract against a non-linear or inverted refactor - a real risk,
        // since nothing else in the codebase reads this function.
        val h = 1000f
        val quarter = PauseLayout.barFillWidth(0.25f, h)
        val half = PauseLayout.barFillWidth(0.5f, h)
        val threeQuarters = PauseLayout.barFillWidth(0.75f, h)
        assertTrue(quarter < half, "the bar did not grow between a quarter and a half of the hold")
        assertTrue(half < threeQuarters, "the bar did not grow between a half and three quarters of the hold")
    }
}
```

- [ ] **Step 2: Run it**

```bash
./gradlew test --tests "render.PauseScreenTest"
```

Expected: PASS. **If any assertion fails, that is a real layout defect this test just found** — report it rather than loosening the assertion.

- [ ] **Step 3: Run the whole suite**

```bash
./gradlew test
```

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/render/PauseScreenTest.kt
git commit -m "test: PauseScreenTest, which PauseLayout's KDoc has claimed all along"
```

**Verification gate:** tests green. No window grab — this task adds no drawing.

---

## Task 7: Documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-08-04-en-pust-til-design.md` — §5, §17
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-08-11-outstanding-work.md` — §2.5

- [ ] **Step 1: Design spec §5 — document the binding set that actually ships**

§5 currently lists only stick / A / B and has never been updated for the booth-hardening work. Add a second table below the existing one:

```markdown
### The full shipped binding set

§5's table above is the *design*. This is what the code binds, including the keyboard mirrors
that exist so the game can be developed and demonstrated without an encoder.

| Action | Gamepad | Keyboard | Configurable? |
|---|---|---|---|
| Swim | Left stick | Arrow keys | No |
| Kick | `kickButton` (default A) | `Z` | Gamepad only |
| Bleed | `bleedButton` (default B) | `X` | Gamepad only |
| Start / restart / confirm | `restartButton` (START) or `restartButtonAlt` (A), on ANY connected pad | `SPACE` | Gamepad only |
| Cycle initials | Left stick up/down, any pad | `UP`/`DOWN` | No |
| Pause / cabinet menu | *(none — deliberate)* | `ESC` | No |
| Exit the cabinet | *(none — deliberate)* | hold `Q` | No |

The asymmetry is deliberate: gamepad bindings are configurable because the arcade encoder's
real button codes are unknown until it is plugged in; keyboard bindings are not, because they
exist for development rather than for the booth. Pause and exit are keyboard-only so a stuck
encoder button can never close the cabinet.

**Every on-screen prompt names whichever set is actually connected** (`render/ControlHints.kt`),
and presence includes raw GLFW joysticks — an encoder with no SDL mapping is invisible to
`engine.input.gamepads` but is still the only input the cabinet has.
```

- [ ] **Step 2: Design spec §17 — add the amendment row**

Append to the amendment log table:

```markdown
| **A pre-run briefing, and control prompts that name the attached hardware** *(2026-08-22; presentation only, no rule changes)* | Nothing on screen told a player how to play: the PLAYING HUD draws five kinds of text and not one named a control, and the attract screen said only `PRESS START`. Worse, the three strings that *did* name buttons were hard-coded literals while the gamepad map is config-driven, so a rebind made the screen lie — and two of them named a keyboard key *and* a gamepad button in one breath. `RunLifecycle` gains a sixth state, **BRIEFING**, between IDLE and PLAYING: five seconds showing the three controls plus the one rule the game never otherwise states (*surface to bank*), skippable after 0.75 s and auto-starting so an abandoned briefing cannot strand the cabinet. **A retry from RUN_OVER does not re-brief** — the briefing is for the person who just walked up, and a queue is what the dwell protects. A permanent one-line control legend sits top-right for the whole run. Two non-obvious constraints came out of building it, both recorded in the design doc: the briefing's auto-start fires `justStarted` on a frame with *no press*, and `LifecycleInputEdges.firedPadId` is nulled every frame — so without a latch on IDLE → BRIEFING the common path would bind gameplay to slot 0, reproducing the "startable, unplayable run" `GamepadScan` exists to prevent; and the legend cannot go bottom-left, because the camera's lag bound plus `HELD_OFFSET_METRES` lets the held count reach the bottom margin. Content is deliberately *controls plus one rule* — air, the anglerfish and the point-of-no-return teach themselves, and everything on that screen costs reading time in front of a queue. |
```

- [ ] **Step 3: `CLAUDE.md` — three edits**

1. In the `render/` architecture paragraph, change the `RunLifecycle` sentence to name six states: "`RunLifecycle` is the IDLE → BRIEFING → PLAYING → RUN_OVER → ENTER_INITIALS state machine (plus PAUSED)". Add: **"BRIEFING is entered only from IDLE — a retry from RUN_OVER goes straight back to the water."**

2. Add `ControlHints` to the pure-logic-extracted-for-testing list, beside `Framing`, `CameraRig` and `RunLifecycle`.

3. Add beside the other `EPT_*` flags:

```markdown
`EPT_BRIEFING_HOLD=1` pins the pre-run briefing open indefinitely — it then leaves only on a
press. The documented window-grab route takes about fourteen seconds to reach a
`screencapture`, which is longer than the briefing's five-second countdown, so without this
the one screen that most needs a real frame is the one screen that cannot be captured. It
passes `Float.POSITIVE_INFINITY` as `briefingSeconds`, and `drawBriefingScreen` suppresses the
countdown line on `briefingAutoStarts` — `Float.POSITIVE_INFINITY.toInt()` is `Int.MAX_VALUE`,
so the pinned screen would otherwise read `STARTING IN 2147483647`. Unset at the booth.
```

- [ ] **Step 4: outstanding-work §2.5 — retire a to-do that was already built**

Replace §2.5's body with:

```markdown
### 2.5 HUD restyle — DONE

Shipped. Recorded here rather than deleted, because this section still read as outstanding
work long after it was built, and a stale to-do for something already in the codebase is how
the same work gets done twice.

- Pearl icon beside `BANKED`: `Hud.pearlIcon` + `bankedIconDiameter`, drawn through `IridescenceRenderer`.
- Clock in a rounded bordered box: `Hud.clockPlate` + `roundedCornerBands` + `roundedBandHalfWidth`.
- Depth tape with real ticks, labels and a slider handle: `TAPE_GRADUATION_METRES = 25f`, `graduationCount`/`graduationDepth`, and the lozenge handle in `drawDepthTape`.

Its one open question is also **settled**: the mockup's held count was white and ours is amber
heating toward red, a deliberate cue from design spec §12. The flag was raised rather than
changed, and the answer is **keep ours**. `HudTest` covers the geometry of all three.

The two constraints this section recorded are still live and still worth reading before
touching `render/Hud.kt` — `Hud.authoredAlphaFor()` for anything semi-transparent, and the
U+0020..U+011F atlas limit for anything drawn as text.
```

- [ ] **Step 5: Verify the docs build nothing and break nothing**

```bash
./gradlew test
```

Expected: PASS. `AttractScreenTest` sweeps `ScreenText.all()`, so if a doc edit accidentally touched a string constant this catches it.

- [ ] **Step 6: Commit**

```bash
git add -A docs/ CLAUDE.md
git commit -m "docs: record the briefing, the shipped binding set, and one retired to-do"
```

**Verification gate:** tests green; every claim added to a doc is one this plan's tasks actually implemented.

---

## Final verification

- [ ] **Full suite**

```bash
./gradlew build
```

Expected: compile + all tests PASS.

- [ ] **A complete play-through on the real screen**

One grab per state, in one session: attract → briefing → playing → paused → run over → initials. Confirm each screen names controls that match the connected hardware, and that unplugging the pad and returning to attract switches every prompt to keyboard.

- [ ] **The booth path specifically**

Run once with **no gamepad and no joystick at all** and confirm every prompt reads keyboard. Then, if an encoder is available, run with it and confirm every prompt reads arcade — including when the encoder is unmapped, which is the case `arcadeHints` exists for.

---

## Notes for whoever executes this

**If a step's code does not compile against the real file, the plan is wrong, not the codebase.** Line numbers here were read at planning time and drift with every edit; treat them as landmarks, not addresses. Say what you found rather than forcing the plan's version in.

**Two claims in this plan are load-bearing and were verified rather than assumed.** If either turns out false, stop and report:

1. `newLifecycle()` passing `briefingSeconds = 0f` makes all 34 existing `RunLifecycleTest` tests pass **unmodified** (Task 3, Step 8).
2. The legend's top-right corner is clear of HELD, the tape, BANKED and the clock at every aspect from 4:3 up (Task 5, Step 1).

**The spec's §12 lists fifteen things an earlier revision of it got wrong.** That is the standard here: when you find one, fix it and say so in the commit rather than working around it.
