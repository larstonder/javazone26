# Main Menu and Graphics Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot the game into a real main menu with graphics options — quality preset, window resolution, render scale, fullscreen, frame cap, vsync and an FPS readout — every one of which applies live, with no restart.

**Architecture:** A new `MAIN_MENU` lifecycle state, following the existing `ENTER_INITIALS`/`InitialsEntry` precedent: the *state* lives in the tested pure `RunLifecycle`, while the menu's *contents* live in a separate pure `MenuModel` that does its own edge detection. Settings are a pure data class persisted as JSON beside the scoreboard. A single `GraphicsApplier` is the only file allowed to touch engine graphics state, and it holds every trap the performance audit found.

**Tech Stack:** Kotlin 2.2.20, Pulse Engine 0.13.0, LWJGL/GLFW, JUnit Platform via `kotlin.test`, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-30-main-menu-and-graphics-options-design.md`

**Depends on:** `docs/superpowers/plans/2026-08-30-rendering-performance.md` — Tasks 5 and 11 of that plan produce `GiSizing` and `GraphicsQuality`/`GiSettings`, which Task 6 here consumes. **Do not start Task 6 before that plan's Task 11 is committed.** Tasks 1–5 and 7–9 here have no such dependency and can proceed in parallel.

## Global Constraints

- **Allman braces, 4 spaces, no wildcard imports.** Match the surrounding file.
- **Comments explain *why*, at length, and cite evidence.** That density is deliberate.
- **No per-frame allocation in the render path.** The menu is drawn every frame it is open, and it is open for as long as somebody leaves it open. Cache composed strings in fields, as `hintPauseResume`/`hintMenuResume`/`hintExitHold` already are.
- **Never `Surface.drawQuad()` / `drawLine()`** — they render nothing on macOS, silently. Use `render/Draw.kt`'s `fillRect`/`fillRectCentred`. `DrawTest` fails the build if a `drawQuad` call returns.
- **Never read a gamepad outside `render/MappedPads.kt`.** `MappedPadsTest` fails the build if `.isPressed(`/`.getAxis(` appears on any receiver but `mappedPads` or `engine.input`. Every name in `GamepadButton` is fiction on a real pad unless read through the SDL-mapped path.
- **Every drawn string goes through `ScreenText`.** The default font draws only **U+0020..U+011F**; anything above renders as *nothing at all* — no glyph, no x-advance, silently. Em dashes, en dashes, curly quotes, ellipses and bullets all vanish; Norwegian æøå are fine. Use `ScreenText.SEPARATOR` (a middle dot) where an em dash is wanted.
- **All layout constants are fractions of screen HEIGHT**, never width and never pixel counts — `engine.window.width/height` are physical framebuffer pixels and the display's aspect ratio is unknown. Vertical anchors are the **top** of a text box; text grows downward.
- **Prefer `surface.config.width/height` over `engine.window.*`** — the same number today, but `config` is what that surface's projection was built from, so it cannot disagree with what is being rendered.
- **Edge-trigger every input.** The engine's `Gamepad` exposes only `isPressed`/`getAxis` — there is **no `wasClicked`**. A held stick reads true every frame.
- **A test that cannot fail is worse than no test.** Assert relationships, not transcriptions.
- **Commit messages:** one short lower-case line, no trailers, matching this repo's history.

---

## Task 1: `MenuModel` — pure navigation

**Files:**
- Create: `src/main/kotlin/render/MenuModel.kt`
- Test: `src/test/kotlin/render/MenuModelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class MenuPage { ROOT, GRAPHICS }`
  - `sealed interface MenuAction` with `object StartDive`, `object ShowLeaderboard`, `object Quit`, `object None`, and `data class SettingChanged(val item: MenuItemId, val delta: Int) : MenuAction`
  - `enum class MenuItemId { START_DIVE, GRAPHICS, LEADERBOARD, QUIT, QUALITY, RESOLUTION, RENDER_SCALE, FULLSCREEN, FRAME_CAP, VSYNC, SHOW_FPS, BACK }`
  - `class MenuModel` with `val page: MenuPage`, `val selectedIndex: Int`, `fun itemsOn(page: MenuPage): List<MenuItemId>`, `fun selectedItem(): MenuItemId`, `fun reset()`, and `fun update(up: Boolean, down: Boolean, left: Boolean, right: Boolean, confirm: Boolean, back: Boolean): MenuAction`
  - **Tasks 3, 4 and 7 all depend on these exact names.**

- [ ] **Step 1: Write the failing test**

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Pure navigation for the main menu.
 *
 * EDGE-TRIGGERING IS THE POINT OF MOST OF THESE TESTS. The engine's Gamepad exposes only
 * isPressed/getAxis with no wasClicked (see RunLifecycle's class doc for the incident that made
 * this non-negotiable), so every input this class receives is a LEVEL that reads true on every
 * frame the control is held. A menu that treats a level as a press scrolls its entire list in a
 * single frame and is unusable with a stick — and on a noisy USB encoder, a stuck contact would
 * cycle a setting forever.
 */
class MenuModelTest
{
    private val menu = MenuModel()

    private fun press(up: Boolean = false, down: Boolean = false, left: Boolean = false,
                      right: Boolean = false, confirm: Boolean = false, back: Boolean = false) =
        menu.update(up, down, left, right, confirm, back)

    private fun release() = menu.update(false, false, false, false, false, false)

    @Test
    fun `a held down direction moves the selection exactly once`() {
        val start = menu.selectedIndex
        repeat(60) { press(down = true) }        // one full second at 60 Hz, held
        assertEquals(start + 1, menu.selectedIndex,
            "a held direction must move one row, not 60")
    }

    @Test
    fun `releasing and pressing again moves again`() {
        val start = menu.selectedIndex
        press(down = true); release(); press(down = true)
        assertEquals(start + 2, menu.selectedIndex)
    }

    @Test
    fun `selection wraps from the last row to the first`() {
        val rows = menu.itemsOn(MenuPage.ROOT).size
        repeat(rows) { press(down = true); release() }
        assertEquals(0, menu.selectedIndex, "selection should wrap to the top")
    }

    @Test
    fun `selection wraps backwards from the first row to the last`() {
        val rows = menu.itemsOn(MenuPage.ROOT).size
        press(up = true)
        assertEquals(rows - 1, menu.selectedIndex)
    }

    @Test
    fun `confirming GRAPHICS opens the graphics page and resets the selection`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true)
        assertEquals(MenuPage.GRAPHICS, menu.page)
        assertEquals(0, menu.selectedIndex, "a freshly opened page starts at its first row")
    }

    @Test
    fun `back returns from GRAPHICS to ROOT`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()
        press(back = true)
        assertEquals(MenuPage.ROOT, menu.page)
    }

    @Test
    fun `back on ROOT is a no-op rather than a crash or a quit`() {
        // A player pressing B on the top-level menu must not exit the game by accident.
        val action = press(back = true)
        assertEquals(MenuPage.ROOT, menu.page)
        assertIs<MenuAction.None>(action)
    }

    @Test
    fun `left and right on a setting row emit SettingChanged with a signed delta`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()
        while (menu.selectedItem() != MenuItemId.QUALITY) { press(down = true); release() }

        val right = press(right = true); release()
        assertIs<MenuAction.SettingChanged>(right)
        assertEquals(MenuItemId.QUALITY, right.item)
        assertEquals(1, right.delta)

        val left = press(left = true)
        assertIs<MenuAction.SettingChanged>(left)
        assertEquals(-1, left.delta)
    }

    @Test
    fun `a held right emits exactly one SettingChanged`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()

        var changes = 0
        repeat(60) { if (press(right = true) is MenuAction.SettingChanged) changes++ }
        assertEquals(1, changes, "a held direction must change a setting once, not 60 times")
    }

    @Test
    fun `left and right on an action row do nothing`() {
        // START DIVE has no value to cycle; nudging the stick sideways on it must be inert.
        while (menu.selectedItem() != MenuItemId.START_DIVE) { press(down = true); release() }
        assertIs<MenuAction.None>(press(right = true))
    }

    @Test
    fun `confirming START DIVE and QUIT emit their actions`() {
        while (menu.selectedItem() != MenuItemId.START_DIVE) { press(down = true); release() }
        assertIs<MenuAction.StartDive>(press(confirm = true))

        menu.reset(); release()
        while (menu.selectedItem() != MenuItemId.QUIT) { press(down = true); release() }
        assertIs<MenuAction.Quit>(press(confirm = true))
    }

    @Test
    fun `reset returns to the root page at the first row`() {
        while (menu.selectedItem() != MenuItemId.GRAPHICS) { press(down = true); release() }
        press(confirm = true); release()
        menu.reset()
        assertEquals(MenuPage.ROOT, menu.page)
        assertEquals(0, menu.selectedIndex)
    }

    @Test
    fun `both directions at once cancel rather than picking one`() {
        // A stuck contact on a noisy encoder can hold two directions simultaneously. Picking a
        // winner would let it walk the menu on its own; PadAxis already applies this rule to
        // steering and the menu follows it.
        val start = menu.selectedIndex
        press(up = true, down = true)
        assertEquals(start, menu.selectedIndex)
    }

    @Test
    fun `every item id is reachable from the root by navigation alone`() {
        // Guards against an item declared but never listed on a page - it would be dead code
        // that looks live.
        val reachable = menu.itemsOn(MenuPage.ROOT) + menu.itemsOn(MenuPage.GRAPHICS)
        assertTrue(reachable.containsAll(MenuItemId.entries.toList()),
            "unreachable items: ${MenuItemId.entries - reachable.toSet()}")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "render.MenuModelTest"`
Expected: FAIL — `Unresolved reference: MenuModel`.

- [ ] **Step 3: Write `MenuModel`**

Create `src/main/kotlin/render/MenuModel.kt` with **no engine imports** — it must be testable in a headless JVM, like `RunLifecycle`, `Framing` and `ControlHints`.

Implementation notes that the tests pin:
- Hold `wasUp`, `wasDown`, `wasLeft`, `wasRight`, `wasConfirm`, `wasBack` as private fields; a direction acts only on a `false → true` transition, exactly as `InitialsEntry` does.
- Cancel opposing directions to zero **before** edge detection, so a stuck pair never produces a transition.
- `itemsOn` returns a fixed list per page; row selection wraps modulo the list size.
- `update` returns `MenuAction.None` when nothing happened. Only rows that carry a value emit `SettingChanged`; keep the set of value-carrying rows as a private `Set<MenuItemId>` so `left`/`right` on `START_DIVE` is inert.
- Opening a page resets `selectedIndex` to 0.

Give the class a doc explaining the edge-triggering requirement and pointing at `RunLifecycle`'s class doc for the incident behind it.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "render.MenuModelTest"`
Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/render/MenuModel.kt src/test/kotlin/render/MenuModelTest.kt
git commit -m "a pure, edge-triggered model for the main menu"
```

---

## Task 2: `GameSettings` — the data, clamped

**Files:**
- Create: `src/main/kotlin/settings/GameSettings.kt`
- Test: `src/test/kotlin/settings/GameSettingsTest.kt`

**Interfaces:**
- Consumes: nothing. (`quality` is a `String` here, deliberately — see Step 3 — so this task does not block on the performance plan.)
- Produces: `data class GameSettings(val quality: String, val windowWidth: Int, val windowHeight: Int, val renderScale: Float, val fullscreen: Boolean, val frameCap: Int, val vsync: Boolean, val showFps: Boolean)` with `companion object { val DEFAULT: GameSettings; val RESOLUTIONS: List<Pair<Int, Int>>; val RENDER_SCALES: List<Float>; val FRAME_CAPS: List<Int> }` and `fun clamped(): GameSettings`. **Tasks 3, 6 and 7 depend on these.**

- [ ] **Step 1: Write the failing test**

```kotlin
package settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings record.
 *
 * WHY CLAMPING LIVES HERE AND NOWHERE ELSE: this value is read from a JSON file on disk that a
 * player can edit, that a partial write can truncate, and that an older build may have written
 * with a different set of fields. Every consumer — GraphicsApplier above all — is entitled to
 * assume a GameSettings instance is already valid, so there is exactly one place that has to
 * defend against nonsense, and it is here.
 */
class GameSettingsTest
{
    @Test
    fun `defaults are fullscreen, because fullscreen measured 1_74x faster than windowed`() {
        // Not a cosmetic default: fullscreen at 1920x1200 beat windowed at 2048x1152 - the same
        // pixel count - by 1.74x, most likely macOS compositor bypass. It is also the shipping
        // application.cfg's screenMode.
        assertTrue(GameSettings.DEFAULT.fullscreen)
    }

    @Test
    fun `render scale clamps into its declared range`() {
        assertEquals(GameSettings.RENDER_SCALES.min(),
            GameSettings.DEFAULT.copy(renderScale = 0.01f).clamped().renderScale)
        assertEquals(GameSettings.RENDER_SCALES.max(),
            GameSettings.DEFAULT.copy(renderScale = 9f).clamped().renderScale)
    }

    @Test
    fun `a negative or absurd window size clamps to a listed resolution`() {
        val fixed = GameSettings.DEFAULT.copy(windowWidth = -1, windowHeight = 0).clamped()
        assertTrue(GameSettings.RESOLUTIONS.contains(fixed.windowWidth to fixed.windowHeight),
            "clamped window size ${fixed.windowWidth}x${fixed.windowHeight} is not a listed resolution")
    }

    @Test
    fun `frame cap clamps to a listed value and zero survives as uncapped`() {
        assertTrue(GameSettings.FRAME_CAPS.contains(
            GameSettings.DEFAULT.copy(frameCap = 7).clamped().frameCap))
        // 0 means "no limiter" - FpsLimiter.sync returns immediately when fps <= 0 - and must
        // not be clamped away to a real cap.
        assertTrue(GameSettings.FRAME_CAPS.contains(0))
        assertEquals(0, GameSettings.DEFAULT.copy(frameCap = 0).clamped().frameCap)
    }

    @Test
    fun `an unknown quality name falls back to the default rather than throwing`() {
        // An older or newer build may have written a name this one does not know.
        val fixed = GameSettings.DEFAULT.copy(quality = "ULTRA_NIGHTMARE").clamped()
        assertEquals(GameSettings.DEFAULT.quality, fixed.quality)
    }

    @Test
    fun `clamping an already valid value changes nothing`() {
        val valid = GameSettings.DEFAULT
        assertEquals(valid, valid.clamped())
    }

    @Test
    fun `clamping is idempotent`() {
        val once = GameSettings.DEFAULT.copy(renderScale = 99f, frameCap = -5).clamped()
        assertEquals(once, once.clamped())
    }

    @Test
    fun `NaN render scale does not survive clamping`() {
        // A truncated JSON write can produce NaN, and NaN fails every comparison silently -
        // it would sail through a naive coerceIn and then blow up setTextureScale.
        val fixed = GameSettings.DEFAULT.copy(renderScale = Float.NaN).clamped()
        assertTrue(fixed.renderScale.isFinite(), "NaN render scale survived clamping")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "settings.GameSettingsTest"`
Expected: FAIL — `Unresolved reference: GameSettings`.

- [ ] **Step 3: Write `GameSettings`**

Create `src/main/kotlin/settings/GameSettings.kt`, engine-free.

**`quality` is a `String`, not an enum, and that is deliberate.** This class is deserialised from a file that an older or newer build may have written; a `String` plus a lookup in `clamped()` degrades an unknown value to the default, whereas an enum field would make Jackson throw during load and take the whole settings file down with it. `GraphicsApplier` (Task 6) resolves the name to `GraphicsQuality`.

`RESOLUTIONS` should list common 16:9 and 16:10 sizes in logical points, including 1600×900 (today's dev default) and 1920×1200. `RENDER_SCALES` should be a short list such as `0.5f, 0.6f, 0.75f, 0.85f, 1.0f`. `FRAME_CAPS` must include `0` (uncapped) alongside 30, 60, 120 and 144.

`clamped()` must handle NaN explicitly — `Float.NaN.coerceIn(a, b)` returns NaN, so test `isFinite()` first.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "settings.GameSettingsTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/settings/GameSettings.kt src/test/kotlin/settings/GameSettingsTest.kt
git commit -m "the settings record, and the one place that defends against a bad settings file"
```

---

## Task 3: `SettingsStore` — persistence that cannot stop the game booting

**Files:**
- Create: `src/main/kotlin/settings/SettingsStore.kt`
- Test: `src/test/kotlin/settings/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: `GameSettings` from Task 2.
- Produces: `interface SettingsStore { fun load(): GameSettings; fun save(settings: GameSettings) }` and `class EngineSettingsStore(private val engine: PulseEngine) : SettingsStore`. **Task 7 depends on both.**

- [ ] **Step 1: Write the failing test against a fake store**

The engine-backed implementation needs a `PulseEngine`; the *contract* does not. Test the contract through a fake, exactly as the score tests do.

```kotlin
package settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings file's failure contract.
 *
 * THE ONE RULE: a settings file must never be able to stop the game booting. It is written by
 * a running game that a player can kill at any moment, it lives in a directory a person can
 * open, and it is read before anything is on screen — so every failure path has to end at
 * GameSettings.DEFAULT and a WARN, never at an exception. This mirrors the scoreboard's
 * contract, which survives a corrupt file the same way.
 */
class SettingsStoreTest
{
    /** In-memory stand-in for the JSON layer, so the contract can be tested without an engine. */
    private class FakeStore(
        var stored: GameSettings? = null,
        var throwOnLoad: Boolean = false,
        var throwOnSave: Boolean = false
    ) : SettingsStore
    {
        var saveCount = 0
        var warnings = 0

        override fun load(): GameSettings =
            try {
                if (throwOnLoad) throw IllegalStateException("corrupt")
                (stored ?: GameSettings.DEFAULT).clamped()
            } catch (e: Exception) { warnings++; GameSettings.DEFAULT }

        override fun save(settings: GameSettings) {
            saveCount++
            try {
                if (throwOnSave) throw IllegalStateException("disk full")
                stored = settings
            } catch (e: Exception) { warnings++ }
        }
    }

    @Test
    fun `a missing file loads the defaults`() {
        assertEquals(GameSettings.DEFAULT, FakeStore(stored = null).load())
    }

    @Test
    fun `a corrupt file loads the defaults instead of throwing`() {
        val store = FakeStore(throwOnLoad = true)
        assertEquals(GameSettings.DEFAULT, store.load())
        assertTrue(store.warnings > 0, "a corrupt settings file should warn, not fail silently")
    }

    @Test
    fun `a failed save does not propagate`() {
        // A player quitting mid-write must not see a crash instead of the game closing.
        FakeStore(throwOnSave = true).save(GameSettings.DEFAULT)
    }

    @Test
    fun `a loaded file is clamped on the way in`() {
        val store = FakeStore(stored = GameSettings.DEFAULT.copy(renderScale = 99f))
        assertTrue(store.load().renderScale <= GameSettings.RENDER_SCALES.max(),
            "load must clamp - a hand-edited file is the normal case, not the exception")
    }

    @Test
    fun `a round trip preserves every field`() {
        val store = FakeStore()
        val settings = GameSettings.DEFAULT.copy(
            renderScale = GameSettings.RENDER_SCALES.min(),
            frameCap = 0,
            vsync = true,
            showFps = true,
            fullscreen = false
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "settings.SettingsStoreTest"`
Expected: FAIL — `Unresolved reference: SettingsStore`.

- [ ] **Step 3: Write `SettingsStore` and `EngineSettingsStore`**

Create `src/main/kotlin/settings/SettingsStore.kt`.

**Use the engine's own JSON path**, exactly as `EngineScoreStore` does (`score/ScoreStore.kt:88-106`):

```kotlin
    override fun load(): GameSettings =
        try {
            (engine.data.loadObject<GameSettings>(FILE_NAME) ?: GameSettings.DEFAULT).clamped()
        } catch (e: Exception) {
            Logger.warn { "Could not read $FILE_NAME (${e.message}); using default settings" }
            GameSettings.DEFAULT
        }
```

`engine.data.saveObject` / `loadObject` resolve against `engine.config.saveDirectory` — `~/EnPustTil/` on macOS — which already holds `scoreboard.json`, its backups and `logs/`.

**Deliberate deviation from the spec, record it in the class doc:** the spec said to route this through `AtomicFileSwap.promoteAtomically`, as the scoreboard does. Use the plain `saveObject` instead. The scoreboard's atomic swap exists because a lost score is a player's lost run and cannot be recreated; a lost graphics setting costs one re-selection from a menu. Adding a second consumer to the atomic-swap path buys nothing and couples settings to the score-loss critical path. **Do not write a `.cfg`** — a `Properties` file would inherit `application.cfg`'s type-coercion trap, where a value above `Int.MAX_VALUE` throws mid-parse and silently drops a hash-ordered subset of *other* keys.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "settings.SettingsStoreTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/settings/SettingsStore.kt src/test/kotlin/settings/SettingsStoreTest.kt
git commit -m "settings persist beside the scoreboard, and a bad file never blocks a boot"
```

---

## Task 4: `MAIN_MENU` in the lifecycle

Adding a value to `RunLifecycleState` **will break compilation in four places**, and that is the design working: those `when` blocks are exhaustive with no `else` **on purpose**, so a new state cannot silently inherit another's behaviour. The compiler is handing you the list of decisions to make.

**Files:**
- Modify: `src/main/kotlin/render/RunLifecycle.kt:7` (the enum), `:193` (`simulationAdvances`), `:217` (`spriteAnimates`), `:333` (the transition `when`), and the companion object
- Modify: `src/main/kotlin/EnPustTil.kt:2398` (the draw dispatch `when`)
- Test: `src/test/kotlin/render/RunLifecycleTest.kt` (extend)

**Interfaces:**
- Consumes: nothing.
- Produces: `RunLifecycleState.MAIN_MENU`; `RunLifecycle.MENU_IDLE_TIMEOUT_SECONDS`; a `menuAction: Boolean` parameter on `update`. **Task 7 depends on all three.**

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `the game boots into the main menu, not the attract screen`() {
        assertEquals(RunLifecycleState.MAIN_MENU, RunLifecycle().state)
    }

    @Test
    fun `the main menu falls back to attract after the idle timeout`() {
        val lifecycle = RunLifecycle()
        lifecycle.update(dt = RunLifecycle.MENU_IDLE_TIMEOUT_SECONDS + 0.1f,
                         anyInputPressed = false, runOver = false)
        assertEquals(RunLifecycleState.IDLE, lifecycle.state)
    }

    @Test
    fun `any press on the attract screen returns to the main menu`() {
        val lifecycle = RunLifecycle()
        lifecycle.update(RunLifecycle.MENU_IDLE_TIMEOUT_SECONDS + 0.1f, false, false)
        assertEquals(RunLifecycleState.IDLE, lifecycle.state)

        lifecycle.update(0.016f, anyInputPressed = true, runOver = false)
        assertEquals(RunLifecycleState.MAIN_MENU, lifecycle.state,
            "attract should hand back to the menu, not start a run directly")
    }

    @Test
    fun `the simulation does not advance and the sprite still animates in the menu`() {
        val lifecycle = RunLifecycle()
        assertFalse(lifecycle.simulationAdvances,
            "a menu that ticked DiveSim would burn the diver's air while nobody was playing")
        assertTrue(lifecycle.spriteAnimates,
            "a motionless diver behind the menu reads as a crashed game")
    }

    @Test
    fun `a run that is not worth recording returns to the menu, not to attract`() {
        val lifecycle = RunLifecycle()
        // Drive to RUN_OVER with a score below the board's threshold, wait out the dwell.
        // (Follow the existing tests' helper for reaching RUN_OVER.)
        // ... then:
        assertEquals(RunLifecycleState.MAIN_MENU, lifecycle.state)
    }

    @Test
    fun `BRIEFING is still reached only from a cold start, never from a retry`() {
        // Pre-existing rule, re-asserted because this change adds a new route into PLAYING and
        // must not accidentally send retries through the briefing.
        // Follow the existing briefing tests' setup.
    }
```

Also add a guard that the booth machinery is untouched:

```kotlin
    @Test
    fun `the booth hardening constants are still in force`() {
        // "Relax for desktop, keep the machinery" was an explicit decision. These values staying
        // put is what lets a future booth build re-enable the old behaviour by choosing a
        // different boot state rather than rewriting a state machine. If a change to the menu
        // breaks one of these, that is a design violation, not a test to update.
        assertEquals(1.5f, RunLifecycle.EXIT_HOLD_SECONDS)
        assertEquals(17.5f, RunLifecycle.IDLE_TIMEOUT_SECONDS)
        assertEquals(15f, RunLifecycle.INITIALS_IDLE_TIMEOUT_SECONDS)
        assertEquals(20f, RunLifecycle.PAUSE_IDLE_TIMEOUT_SECONDS)
    }
```

**Fill in the three elided setups** by copying the existing tests' helpers from `RunLifecycleTest` — do not leave them as comments.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "render.RunLifecycleTest"`
Expected: FAIL — `Unresolved reference: MAIN_MENU`.

- [ ] **Step 3: Add the state and let the compiler find every decision**

```kotlin
enum class RunLifecycleState { MAIN_MENU, IDLE, BRIEFING, PLAYING, PAUSED, RUN_OVER, ENTER_INITIALS }
```

Run `./gradlew compileKotlin` and **write down every error**. Expect four: `RunLifecycle.kt:193`, `:217`, `:333`, and `EnPustTil.kt:2398`.

- [ ] **Step 4: Resolve each `when`, deliberately**

`simulationAdvances` (`:193`) — `MAIN_MENU -> false`. Group it with `IDLE, BRIEFING, PAUSED`, and extend the existing doc: a menu that ticked `DiveSim` would burn air while nobody was playing, which is the same reason IDLE is false.

`spriteAnimates` (`:217`) — `MAIN_MENU -> true`. Group it with `IDLE, BRIEFING, PLAYING, RUN_OVER, ENTER_INITIALS`, and extend the doc: the diver kicking in place behind the menu is what makes the screen read as alive rather than frozen, exactly as for IDLE.

The transition `when` (`:333`) — add the `MAIN_MENU` branch:
- `menuAction` requesting a dive → `BRIEFING` (set `justEnteredBriefing`).
- `timeInState >= MENU_IDLE_TIMEOUT_SECONDS` with no input → `IDLE`.
- Otherwise stay. **`anyInputPressed` alone must NOT start a run from the menu** — that is IDLE's behaviour, and inheriting it here would make every navigation press launch a dive.

`IDLE`'s existing branch — its `pressedEdge` destination changes from `BRIEFING` to `MAIN_MENU`. **This is the one existing behaviour this task deliberately changes**; note it in the branch's comment.

`RUN_OVER` — the fallback that currently returns to `IDLE` on `idleTimeoutSeconds` now returns to `MAIN_MENU`. Leave `IDLE_TIMEOUT_SECONDS` itself alone.

`ENTER_INITIALS` — on completion or auto-submit, go to `MAIN_MENU`.

`EnPustTil.kt:2398` — add `RunLifecycleState.MAIN_MENU -> drawMainMenu(hud, w, h)`. Task 5 writes that function; for now, a stub that draws nothing keeps compilation green.

- [ ] **Step 5: Add the constant and the parameter**

In the companion object, beside the existing timeouts:

```kotlin
        /**
         * How long the main menu waits, untouched, before falling back to the attract screen.
         *
         * Longer than [IDLE_TIMEOUT_SECONDS] on purpose: attract mode is a screensaver whose job
         * is to recover an abandoned cabinet, while the menu is somewhere a person is actively
         * reading and deciding. Timing out of it as briskly as attract times out would yank the
         * screen away from someone halfway through choosing a resolution.
         */
        const val MENU_IDLE_TIMEOUT_SECONDS = 45f
```

Add `menuAction: Boolean = false` to `update`'s parameter list, defaulted so every existing caller and test compiles unchanged.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests "render.RunLifecycleTest"`
Expected: PASS — **including every pre-existing test unchanged.** If a booth-hardening test now fails, do not update it; the transition logic is wrong.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`
Expected: PASS. `CreateGameOrderingTest`, `UpdateGameOrderingTest` and `RenderGameBootGuardTest` all touch lifecycle wiring.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/render/RunLifecycle.kt src/main/kotlin/EnPustTil.kt \
        src/test/kotlin/render/RunLifecycleTest.kt
git commit -m "boot into a main menu, with attract as the idle fallback"
```

---

## Task 5: `MenuLayout` and drawing the menu

**Files:**
- Create: `src/main/kotlin/render/MenuLayout.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` — `ScreenText` entries and a `drawMainMenu` function
- Test: `src/test/kotlin/render/MenuLayoutTest.kt`
- Test: `src/test/kotlin/AttractScreenTest.kt` (extend)

**Interfaces:**
- Consumes: `MenuModel`, `MenuItemId`, `MenuPage` from Task 1.
- Produces: `MenuLayout` constants and `MenuLayout.rowY(index: Int): Float`; `ScreenText.MENU_*` string constants. Task 7 calls `drawMainMenu`.

- [ ] **Step 1: Write the failing layout test**

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Menu geometry.
 *
 * Everything is a fraction of screen HEIGHT, never width, for the reason AttractLayout and
 * PauseLayout both give: engine.window.width/height are PHYSICAL framebuffer pixels and the
 * display's aspect ratio is not known until something is plugged in. A width fraction would
 * stretch the rows on a wide display while the glyphs inside them stayed put.
 *
 * What these tests assert is the RELATIONSHIP between the anchors - that nothing collides and
 * nothing leaves the screen - rather than the anchors themselves, which would just restate the
 * constants.
 */
class MenuLayoutTest
{
    private val longestPage = 8   // the GRAPHICS page: 7 settings plus BACK

    @Test
    fun `no two rows overlap`() {
        for (i in 0 until longestPage - 1)
            assertTrue(MenuLayout.rowY(i) + MenuLayout.ROW_FONT <= MenuLayout.rowY(i + 1),
                "row $i overlaps row ${i + 1}")
    }

    @Test
    fun `the title clears the first row`() {
        assertTrue(MenuLayout.TITLE_Y + MenuLayout.TITLE_FONT <= MenuLayout.rowY(0),
            "the title runs into the first menu row")
    }

    @Test
    fun `a full page fits on screen with room for the hint line`() {
        val bottom = MenuLayout.rowY(longestPage - 1) + MenuLayout.ROW_FONT
        assertTrue(bottom < MenuLayout.HINT_Y, "a full page runs into the hint line")
        assertTrue(MenuLayout.HINT_Y + MenuLayout.HINT_FONT < 1f, "the hint line runs off screen")
    }

    @Test
    fun `the value column sits clear of the label column at every aspect ratio`() {
        // Aspect matters because both columns are placed as height fractions either side of the
        // screen centre; at 4:3 the screen is narrowest in height-relative terms and is where
        // they would collide first.
        for (aspect in listOf(4f / 3f, 16f / 10f, 16f / 9f, 2.389f, 32f / 9f)) {
            val labelRight = 0.5f - MenuLayout.COLUMN_GAP / aspect
            val valueLeft = 0.5f + MenuLayout.COLUMN_GAP / aspect
            assertTrue(labelRight < valueLeft, "columns collide at aspect $aspect")
        }
    }
}
```

Adapt the last test to however you actually place the two columns — the assertion that matters is *label and value never overlap at any aspect this game can be shown at*.

- [ ] **Step 2: Run to verify it fails, then write `MenuLayout`**

Run: `./gradlew test --tests "render.MenuLayoutTest"` → FAIL.

Create `src/main/kotlin/render/MenuLayout.kt` as a pure object of `const val` fractions plus `rowY`, modelled directly on `AttractLayout` (`EnPustTil.kt:776`). Document each anchor's reason, not just its value.

- [ ] **Step 3: Add the menu's strings to `ScreenText`**

Every string the menu draws goes in `ScreenText`. **The default font draws only U+0020..U+011F** — an em dash, en dash, curly quote, ellipsis or bullet renders as *nothing at all*, silently.

```kotlin
    const val MENU_START_DIVE = "START DIVE"
    const val MENU_GRAPHICS = "GRAPHICS"
    const val MENU_LEADERBOARD = "LEADERBOARD"
    const val MENU_QUIT = "QUIT"
    const val MENU_QUALITY = "QUALITY"
    const val MENU_RESOLUTION = "RESOLUTION"
    const val MENU_RENDER_SCALE = "RENDER SCALE"
    const val MENU_FULLSCREEN = "FULLSCREEN"
    const val MENU_FRAME_CAP = "FRAME CAP"
    const val MENU_VSYNC = "VSYNC"
    const val MENU_SHOW_FPS = "SHOW FPS"
    const val MENU_BACK = "BACK"
    const val MENU_ON = "ON"
    const val MENU_OFF = "OFF"
    const val MENU_UNCAPPED = "UNCAPPED"
```

- [ ] **Step 4: Extend `AttractScreenTest` to cover them**

`AttractScreenTest` already asserts every `ScreenText` entry is drawable by the default font. Confirm it enumerates the object reflectively or by an explicit list — **if explicit, add every new constant**, or the guard silently does not cover them.

- [ ] **Step 5: Write `drawMainMenu`**

In `EnPustTil.kt`, replace Task 4's stub. Draw to the **`hud` surface** — never `mainSurface`, which `GlobalIlluminationSystem` multiplies by the light map, making the menu near-invisible in the abyss.

- Solid shapes via `render/Draw.kt`'s `fillRect`/`fillRectCentred`. **Never `drawQuad`** — it renders nothing on macOS, silently, and `DrawTest` fails the build.
- Highlight the selected row (a filled rect behind it, or a colour change).
- On the GRAPHICS page, draw each row as label + current value.
- **No per-frame allocation.** Compose the value strings only when a setting changes, into cached fields, following the existing `hintPauseResume`/`hintMenuResume`/`hintExitHold` pattern. In particular do not build a `Color` per frame — `EnPustTil.kt:2692` already does that and it is a bug being fixed in the performance plan.
- Print the pad's own button legend for confirm/back via `ControlHints`/`ControllerFamily`, which already say `CROSS` on a DualSense and `A` on a generic pad.

- [ ] **Step 6: Run the suite and commit**

```bash
./gradlew test
git add src/main/kotlin/render/MenuLayout.kt src/test/kotlin/render/MenuLayoutTest.kt \
        src/main/kotlin/EnPustTil.kt src/test/kotlin/AttractScreenTest.kt
git commit -m "menu geometry and drawing, on the hud surface where the light map cannot dim it"
```

---

## Task 6: `GraphicsApplier` — the only file that touches graphics state

**Blocked on the performance plan's Task 11** (`GraphicsQuality`, `GiSettings`) and Task 5 (`GiSizing`).

**Files:**
- Create: `src/main/kotlin/render/GraphicsApplier.kt`
- Test: `src/test/kotlin/render/GraphicsApplierTest.kt`

**Interfaces:**
- Consumes: `GameSettings` (Task 2); `GraphicsQuality`, `GiSettings`, `GiSizing` (performance plan).
- Produces: `class GraphicsApplier(private val engine: PulseEngine)` with `fun apply(settings: GameSettings, gi: GlobalIlluminationSystem?)` and `fun reassertVsync(enabled: Boolean)`. Task 7 depends on both.

- [ ] **Step 1: Write the failing test for the pure decision layer**

Applying settings needs an engine; deciding *what* to apply does not. Split the decision out and test it.

```kotlin
package render

import settings.GameSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mapping from a player's chosen settings to engine state.
 *
 * WHY THE DECISIONS ARE A SEPARATE, PURE FUNCTION: applying them needs a live PulseEngine and a
 * GL context, so a test of `apply` would need the whole engine booted. What can go wrong here is
 * not the calls - it is choosing the wrong values - so the choice is extracted and asserted, and
 * `apply` becomes a thin, obvious forwarding layer.
 */
class GraphicsApplierTest
{
    @Test
    fun `an unknown quality name resolves to the default preset rather than throwing`() {
        assertEquals(GraphicsQuality.valueOf(GameSettings.DEFAULT.quality),
            GraphicsApplier.qualityFor("NOT_A_REAL_PRESET"))
    }

    @Test
    fun `every quality name in the settings list resolves`() {
        for (q in GraphicsQuality.entries)
            assertEquals(q, GraphicsApplier.qualityFor(q.name))
    }

    @Test
    fun `vsync and a frame cap are never both active`() {
        // FpsLimiter and the swap interval are two limiters; leaving both on makes them fight,
        // and the resulting frame pacing is worse than either alone.
        val resolved = GraphicsApplier.frameCapFor(
            GameSettings.DEFAULT.copy(vsync = true, frameCap = 60))
        assertEquals(0, resolved, "with vsync on, the fps limiter must be disabled")
    }

    @Test
    fun `with vsync off the chosen frame cap is passed through`() {
        assertEquals(60, GraphicsApplier.frameCapFor(
            GameSettings.DEFAULT.copy(vsync = false, frameCap = 60)))
    }

    @Test
    fun `no preset the menu can select drops below six cascades at either shipped resolution`() {
        // The torch-clipping floor. Re-asserted here as well as in GraphicsQualityTest because
        // this is the layer that decides which preset a player can actually reach.
        for (q in GraphicsQuality.entries)
            for ((w, h) in listOf(1920 to 1200, 3200 to 1800)) {
                val s = q.settings()
                assertTrue(GiSizing.cascadeCount(w, h, s.lightTexScale, s.maxCascades) >= 6,
                    "$q at ${w}x$h would clip the torch beam")
            }
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew test --tests "render.GraphicsApplierTest"` → FAIL.

Create `src/main/kotlin/render/GraphicsApplier.kt`. Put `qualityFor`, `frameCapFor` and any other pure decisions in a `companion object`; keep `apply` a thin forwarding layer.

**The class doc must carry all four traps**, because this is the one file anyone will read before changing GI state:

1. **`lightTexScale` is a step function**, not a curve — the light texture rounds up to `2^cascadeCount` and the count derives from the rounded diagonal. The shipped 0.5 was worse than the engine's 0.4 default. Evaluate through `GiSizing`, never by assuming area scaling.
2. **`traceWorldRays = false` does not remove the global chain** — those surfaces are created unconditionally in `onCreate` and their post-processing runs regardless; the flag only gates a shader branch. Drop `globalSceneTexScale` too, or the saving is imaginary.
3. **`gi_light_final` and `gi_normal_map` are unreachable through any GI property**, but a direct `setTextureScale` sticks, because `onUpdate` re-pushes only the seven scales it owns.
4. **Never expose `mergeCascades`** — turning it off collapses lighting to the finest interval.

Why everything can be applied live, also in the doc: `GlobalIlluminationSystem.onUpdate` re-pushes the scales every frame; `SurfaceImpl.setTextureScale` is a no-op if unchanged and otherwise queues `renderTarget.init` through `runOnInitFrame` for the next frame; every other GI property is a fresh per-frame uniform. **Nothing is latched at init.**

`apply` should:
- resolve the preset and push `lightTexScale`, `localSceneTexScale`, `globalSceneTexScale`, `maxCascades`, `bilinearFix`, `traceWorldRays` onto the `GlobalIlluminationSystem`
- `engine.gfx.mainSurface.setTextureScale(settings.renderScale)`
- add or delete the `"bloom"` post-processing effect to match the preset
- `engine.config.targetFps = frameCapFor(settings)`
- `engine.window.updateScreenMode(if (settings.fullscreen) ScreenMode.FULLSCREEN else ScreenMode.WINDOWED)`
- call `reassertVsync(settings.vsync)`

- [ ] **Step 3: Implement `reassertVsync`, and document why it exists**

There is **no engine API for vsync**. `WindowImpl.createWindow` calls `GLFW.glfwSwapInterval(0)` — the only such call in the jar, taking no config value — and **re-applies it every time the window is recreated**, which `updateScreenMode` does.

So toggling fullscreen silently turns vsync off. `reassertVsync` must be called after every screen-mode change, from the main thread with the context current (Task 7's `ResizableWindow.initFrame` hook). Say all of that in the function's doc; it is a bug that will otherwise be rediscovered as "vsync randomly stops working".

- [ ] **Step 4: Run the test and commit**

```bash
./gradlew test --tests "render.GraphicsApplierTest"
git add src/main/kotlin/render/GraphicsApplier.kt src/test/kotlin/render/GraphicsApplierTest.kt
git commit -m "one choke point for graphics state, carrying the four traps that cost a day each"
```

---

## Task 7: Wire it up — window resizing, and the menu on screen

**Files:**
- Create: `src/main/kotlin/render/ResizableWindow.kt`
- Modify: `src/main/kotlin/EnPustTil.kt` — `main()`, `onCreate`, `updateGame`, the draw dispatch
- Test: `src/test/kotlin/render/ResizableWindowTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: `class ResizableWindow : WindowImpl()` with `fun requestSize(width: Int, height: Int)` and `fun requestSwapInterval(interval: Int)`.

- [ ] **Step 1: Confirm `WindowImpl` really is subclassable**

```bash
cd /tmp/peek
javap -p no/njoh/pulseengine/core/window/WindowImpl.class | head -30
javap -p no/njoh/pulseengine/core/PulseEngineImpl.class | grep "PulseEngineImpl("
```

Expected: `WindowImpl` is a non-final `public class` with a non-final `public void initFrame(...)`, and `PulseEngineImpl`'s constructor is public. **If either is final, stop** — drop the resolution row from the menu, keep render scale (which needs no subclass), and record why in the spec.

- [ ] **Step 2: Write the failing test for the pending-request queue**

The GLFW call needs a window; the queueing discipline does not.

```kotlin
package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pending-resize queue.
 *
 * WHY A QUEUE AT ALL: game callbacks run on the "game" thread, not the GL/main thread -
 * gameLoopMode defaults to MULTITHREADED and nothing here overrides it. GLFW window calls must
 * happen on the main thread, and initFrame is the main-thread hook the engine already runs at
 * the top of every frame. So a menu selection records an intent, and initFrame performs it.
 *
 * The queue must COLLAPSE: holding right on the resolution row would otherwise stack a dozen
 * window resizes to be executed one per frame, and the window would visibly walk through every
 * size in the list.
 */
class ResizableWindowTest
{
    @Test
    fun `the latest request wins and earlier ones are discarded`() {
        val q = PendingWindowRequests()
        q.requestSize(1280, 720)
        q.requestSize(1920, 1080)
        assertEquals(1920 to 1080, q.takeSize())
    }

    @Test
    fun `taking a request clears it so it is not re-applied every frame`() {
        val q = PendingWindowRequests()
        q.requestSize(1600, 900)
        q.takeSize()
        assertNull(q.takeSize())
    }

    @Test
    fun `no request means nothing to do`() {
        assertNull(PendingWindowRequests().takeSize())
    }

    @Test
    fun `a swap interval request survives independently of a size request`() {
        val q = PendingWindowRequests()
        q.requestSwapInterval(1)
        q.requestSize(1600, 900)
        assertEquals(1, q.takeSwapInterval())
        assertEquals(1600 to 900, q.takeSize())
    }
}
```

- [ ] **Step 3: Run to verify it fails, then implement both classes**

Run: `./gradlew test --tests "render.ResizableWindowTest"` → FAIL.

Put `PendingWindowRequests` (pure, engine-free, holding nullable latest-wins slots) in `ResizableWindow.kt` alongside:

```kotlin
class ResizableWindow : WindowImpl()
{
    private val pending = PendingWindowRequests()

    fun requestSize(width: Int, height: Int) = pending.requestSize(width, height)
    fun requestSwapInterval(interval: Int) = pending.requestSwapInterval(interval)

    override fun initFrame(engine: PulseEngineInternal)
    {
        pending.takeSize()?.let { (w, h) -> GLFW.glfwSetWindowSize(windowHandle, w, h) }
        pending.takeSwapInterval()?.let { GLFW.glfwSwapInterval(it) }
        super.initFrame(engine)
    }
}
```

Match `initFrame`'s real signature from Step 1. Document that there is **no `glfwSetWindowSize` anywhere in the engine jar** — this is the gap this class fills — but that the window *is* created `GLFW_RESIZABLE` with its framebuffer-size callback wired to `gfx.onWindowChanged`, which reallocates every render texture and re-projects every camera. **Dragging the window edge already works today; this only adds programmatic control.**

- [ ] **Step 4: Construct the engine explicitly**

Replace the inline `PulseEngine.run<EnPustTil>()` helper in `main()`:

```kotlin
fun main()
{
    PulseEngineImpl(window = ResizableWindow()).run(EnPustTil())
}
```

`PulseEngine.Companion.run` is an inline reified helper that does exactly this; going explicit is what lets a custom `Window` in.

- [ ] **Step 5: Load settings and apply them in `onCreate`**

After the surfaces exist and after `DiveLighting.setup` has captured the GI system:

```kotlin
        settingsStore = EngineSettingsStore(engine)
        settings = settingsStore.load()
        graphicsApplier = GraphicsApplier(engine)
        graphicsApplier.apply(settings, lighting.giSystem())
```

**Take the simpler of the spec's two startup paths:** let the window open at `application.cfg`'s size and apply the saved size here, through the same pending-resize hook the menu uses. One code path instead of two, at the cost of a brief resize on the first frame. Only add the `UserConfig : ConfigurationImpl` route if that flash proves objectionable in practice.

`DiveLighting` will need a small accessor exposing its private `gi` field.

- [ ] **Step 6: Drive the menu from `updateGame`**

Where lifecycle input is already read:
- Build the six menu inputs from `PadAxis` (analog → D-pad → keyboard, first live source wins) and `MappedPads`. **Never `pad.isPressed`** — `MappedPadsTest` fails the build.
- **Scan every connected gamepad**, as `anyLifecycleActionPressed` already does; index 0 is not guaranteed to be the player's pad.
- Call `menuModel.update(...)` only while `lifecycle.state == MAIN_MENU`.
- Act on the returned `MenuAction`:
  - `StartDive` → pass `menuAction = true` into `lifecycle.update`
  - `Quit` → `engine.window.close()`. Clean shutdown already runs `service.destroy`, which is where `ScoreRepository.onDestroy` saves — **scores are safe across it.**
  - `SettingChanged` → step the field in `settings`, `clamped()`, then `graphicsApplier.apply(...)`
  - `ShowLeaderboard` → leave the menu for `IDLE`
- **Debounce the save.** Write on leaving the GRAPHICS page and in `onDestroy`, **not on every cycler step** — holding right on FRAME CAP must not produce a burst of file writes.

- [ ] **Step 7: Verify end to end, on screen**

Green tests are not evidence the menu works. Launch it and drive it:

```bash
caffeinate -d -u -t 300 &
./gradlew run > /tmp/menu.log 2>&1 &
until pgrep -f EnPustTilKt > /dev/null; do sleep 2; done ; sleep 8
osascript -e 'tell application "System Events" to set frontmost of (first application process whose name is "java") to true'
sleep 0.5 ; screencapture -x -o /tmp/menu.png
```

`osascript` keystrokes are **silently dropped** by this window — use the `Quartz.CGEventPost` recipe from `CLAUDE.md` to drive navigation. **Open every capture and confirm it shows the game**, not the desktop or the lock screen; a full capture cycle was once lost to exactly that.

Confirm, each with a capture:
- The menu is the first thing on screen at boot.
- Down/up moves one row per press, not many.
- GRAPHICS opens; left/right changes a value **and the change is visible immediately**.
- **Toggling fullscreen does not lose the diver's sprite sheets.** `updateScreenMode` recreates the window (sharing the GL context); `DiverSprite.sheetsReady()` degrading to a placeholder rectangle is the visible symptom if assets were lost.
- **Toggling fullscreen does not silently disable vsync** — `createWindow` re-applies `glfwSwapInterval(0)`, which is what `reassertVsync` exists to undo.
- Changing resolution mid-session leaves the camera correct — check with `CameraInvariants` under `EPT_DEV`.
- QUIT closes the game and `scoreboard.json` is intact afterwards.
- Settings survive a restart.

- [ ] **Step 8: Run the full suite and commit**

```bash
./gradlew test
git add src/main/kotlin/render/ResizableWindow.kt src/test/kotlin/render/ResizableWindowTest.kt \
        src/main/kotlin/EnPustTil.kt src/main/kotlin/render/DiveLighting.kt
git commit -m "wire the menu to live settings, with programmatic window resizing"
```

---

## Task 8: The FPS readout

**Files:**
- Modify: `src/main/kotlin/render/FrameProbe.kt` (from the performance plan) — expose the current p50
- Modify: `src/main/kotlin/EnPustTil.kt` — draw it when `settings.showFps`

**Interfaces:**
- Consumes: `FrameProbe`, `GameSettings`.
- Produces: `FrameProbe.currentP50Ms: Float`.

- [ ] **Step 1: Expose the rolling figure**

Add a `var currentP50Ms: Float = 0f; private set` to `FrameProbe`, updated in the same once-per-second block that prints. Register the probe unconditionally when `settings.showFps` is on — not only under `EPT_PROFILE`.

- [ ] **Step 2: Draw it on the `hud` surface**

Top-right corner, small. **On the `hud` surface**, never `mainSurface` — the GI multiply would make it near-invisible in the abyss.

Format the string **only when the value changes** (once per second), into a cached field. Do not `String.format` per frame: it parses the format string and boxes every argument, and the render path forbids per-frame allocation.

**Do not label anything "GPU".** `data.gpuRenderTimeMs` is CPU wall time around `drawFrame` + `swapBuffers`, and real GPU timing does not exist on this platform — `gpuProfiling` returns `0 ns` on all 85 scopes because Apple's shim does not implement `glQueryCounter`.

- [ ] **Step 3: Verify it reads plausibly**

Run the game, turn SHOW FPS on, and compare the on-screen number against the `[FRAME]` lines in the log. They must agree.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/render/FrameProbe.kt src/main/kotlin/EnPustTil.kt
git commit -m "an fps readout the menu can turn on"
```

---

## Task 9: Correct the documentation this work disproves

**Files:**
- Modify: `CLAUDE.md`
- Modify: `src/main/kotlin/render/README.md`

> If the performance plan's Task 12 has already landed, the runtime-mutability corrections are done — **check first and do not duplicate them.** This task then covers only the menu-specific additions below.

- [ ] **Step 1: Document the menu's architecture**

Add to `CLAUDE.md`'s architecture section:
- `RunLifecycleState.MAIN_MENU` is the boot state; IDLE is now the idle *fallback*, reached on `MENU_IDLE_TIMEOUT_SECONDS` and returning to the menu on any press.
- `MenuModel` is pure and edge-triggered, following `InitialsEntry`'s precedent.
- **`GraphicsApplier` is the only file permitted to touch engine graphics state**, and it carries the four GI traps.
- Settings live in `~/EnPustTil/settings.json` beside the scoreboard, via `engine.data.saveObject`. A corrupt file degrades to defaults and can never block a boot.
- **The booth machinery is intact and deliberately so** — `EXIT_HOLD_SECONDS`, the idle timeouts and the initials auto-submit are all still in force and still tested. A future booth build changes the boot state and hides QUIT; it does not rewrite the state machine.

- [ ] **Step 2: Record the vsync trap where someone will hit it**

Vsync has no engine API, is hardcoded off at `WindowImpl.createWindow`, and is **re-applied on every window recreation** — so any screen-mode change silently disables it. `GraphicsApplier.reassertVsync` is the remedy.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md src/main/kotlin/render/README.md
git commit -m "document the menu, the settings file and the vsync trap"
```

---

## Self-Review

**Spec coverage:** §0 runtime mutability → Tasks 6, 7, 9. §1 decisions (boot destination, attract fallback, booth relaxation, presets-plus-knobs, FPS readout) → Tasks 4, 5, 6, 8. §2 architecture and file list → Tasks 1–3, 6, 7 (all six new files covered). §3 `MenuModel` → Task 1. §4 `GameSettings`, both resolution knobs, persistence, startup ordering → Tasks 2, 3, 7. §5 `GraphicsApplier`, the four traps, presets, vsync → Task 6. §6 FPS readout and the `Service.start()` fact → Task 8 (the `MetricViewer` fix itself is the performance plan's Task 1). §7 lifecycle transitions → Task 4. §8 drawing → Task 5. §9 input → Task 7 Step 6. §10 testing → every task's test step; the `GraphicsApplierTest` monotonicity guard lives in the performance plan's `GraphicsQualityTest` and is re-asserted at the menu-reachable layer in Task 6. §11 risks → Task 7 Step 7 verifies each on screen. §12 out of scope → nothing here implements audio, remapping, localisation or GI-off.

**Placeholder scan:** Task 4 Step 1 leaves three test bodies to be filled from `RunLifecycleTest`'s existing helpers rather than inventing a setup that may not match — the step says so explicitly and names the source. Task 5 Step 1's last test says to adapt the column assertion to the actual placement; the invariant to assert is stated. Task 6's `apply` is specified as a bulleted call list rather than a code block because its exact signatures depend on the performance plan's `GiSettings`, which is declared in that plan's Task 11 Interfaces block.

**Type consistency:** `MenuItemId`, `MenuPage`, `MenuAction` and `MenuModel.update`'s six-boolean signature are declared in Task 1 and used identically in Tasks 5, 6 and 7. `GameSettings`' eight fields and its three companion lists are declared in Task 2 and consumed unchanged in Tasks 3, 6 and 7. `SettingsStore.load/save` match between the fake in Task 3's test and `EngineSettingsStore`. `GraphicsApplier.qualityFor`/`frameCapFor`/`apply`/`reassertVsync` are consistent between Task 6's test, its implementation and Task 7's call sites. `PendingWindowRequests.requestSize/takeSize/requestSwapInterval/takeSwapInterval` match between Task 7's test and implementation. `GiSizing.cascadeCount` and `GraphicsQuality.settings()` match the performance plan's declared signatures.

**One cross-plan risk, flagged rather than hidden:** Task 6 consumes `GraphicsQuality` and `GiSettings` from the performance plan's Task 11, which is that plan's *last* task. If the menu is wanted before the performance work finishes, Task 6 can ship against a temporary `GraphicsQuality` carrying today's values as `HIGH` and no other presets — the menu would then have a QUALITY row with one option. That is a working, honest intermediate state; a fabricated ladder is not.
