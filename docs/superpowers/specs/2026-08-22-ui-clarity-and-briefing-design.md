# Én Pust Til — UI clarity and the pre-run briefing

**Status:** design, approved 2026-08-22.
**Supersedes nothing.** Extends `2026-08-04-en-pust-til-design.md` §5 (Controls) and §12
(Feedback and presentation); an amendment row goes in §17 when this lands.

---

## 1. What problem this design solves

Two problems, found by reading every input binding and every drawn string in the game.

**Nothing on screen tells a player how to play.** The PLAYING HUD draws exactly five kinds
of text — `BANKED n`, the clock, the `HELD` integer, the depth-tape graduation labels and
the travelling depth readout — and not one of them names a control. The attract screen says
`PRESS START` and nothing else. A stranger at the booth learns swim, kick and bleed by
guessing, and learns *surface to bank* by losing a haul without being told why.

**Three of the strings that do name a control are wrong, or can become wrong.** The gamepad
button map is config-driven (`kickButton`, `bleedButton`, `restartButton`, `restartButtonAlt`
in `application.cfg`, currently commented out so all four run on compiled defaults). The
strings that name buttons are hard-coded literals:

| String | Current value | Defect |
|---|---|---|
| `ScreenText.PRESS_START` | `"PRESS START"` | Names `START`, which is `restartButton` and configurable. Rebind it and the screen lies. |
| `ScreenText.PLAY_AGAIN` | `"SPACE / START to play again"` | Same, plus it names a keyboard key **and** a gamepad button in one literal — so it is half-irrelevant on every machine. |
| `ScreenText.INITIALS_HELP` | `"UP/DOWN: change letter   A / START: next"` | Same again, **and** it omits the stick, which is the cabinet's actual control for cycling letters. Also uses a raw double-space where every other string uses `ScreenText.SEPARATOR`. |

Their casings disagree with each other three ways (`SHOUTING`, `Sentence case`,
`Mixed: label case`), which at arcade viewing distance reads as three unrelated systems.

The arcade encoder's real button codes are **not known until it is plugged in** — that is
why the button map is configurable at all — so "it says A and A is right" is an assumption
this design refuses to keep making.

### Not in scope

The HUD restyle in `2026-08-11-outstanding-work.md` §2.5 (pearl icon, boxed clock, depth-tape
ticks) is **already implemented**, and that document is stale in saying otherwise. Verified in
`render/Hud.kt`: the pearl icon is `pearlIcon` + `bankedIconDiameter` drawn through
`IridescenceRenderer`; the boxed clock is `clockPlate` + `roundedCornerBands` +
`roundedBandHalfWidth`; the tape's graduations are `TAPE_GRADUATION_METRES = 25f` with
`graduationCount`/`graduationDepth` and a lozenge handle. Nothing in §2.5 remains to do, this
design does not reopen it, and §9 records the one-line correction to that document so the next
reader is not sent to build it a second time.

Spec §12's on-screen list (`BANKED` cold white top-left, `HELD` amber attached to the diver,
depth tape down the right edge) is unchanged. This design **adds** to the HUD; it restyles
nothing.

---

## 2. Decisions taken, and by whom

All eight were taken by the owner on 2026-08-22, before any code was written.

| Decision | Choice | Consequence |
|---|---|---|
| Which controls do prompts depict? | **Whichever is actually connected** | Needs a runtime gamepad-presence read and two sets of strings. |
| How does the briefing end? | **Countdown auto-start, or a press** | The countdown is the unattended-recovery guarantee; no new idle path is needed. |
| In-run guidance | **Small permanent legend** | A new HUD element, drawn for the whole run. |
| UI scope | **Full pass, propose as found** | §1's defect list is the proposal; it is the whole of it. |
| Button names in hints | **Derived from live config** | A rebind updates the screen. Needs a `GamepadButton` → label map and a font-atlas test over every entry. |
| Which hint set shows | **Gamepad presence wins** | `engine.input.gamepads.isNotEmpty()`. No last-used-device tracking, no flicker, no debounce. |
| Language | **English, as everything else already is** | The only Norwegian string stays the title, `"ÉN PUST TIL"`. |
| Briefing content | **Controls plus the one core rule** | Air, the anglerfish and the point-of-no-return are deliberately NOT taught here. |
| Does a retry re-brief? | **No — attract only** | `IDLE → BRIEFING → PLAYING`; `RUN_OVER → PLAYING` stays direct. |
| Skip guard | **0.75 s dwell** | Mirrors `RUN_OVER`'s proven `DWELL_SECONDS` shape. |
| Countdown | **5 seconds** | |
| Missing pause test | **Write it** | `PauseLayout`'s KDoc cites a `PauseScreenTest` that does not exist. |

---

## 3. `render/ControlHints.kt` — the single source of every control string

New file. **Pure Kotlin, zero engine imports**, in the same family as `Framing`,
`RunLifecycle`, `AttractLayout`, `ScreenText` and `DepthBlend`: the relationship between the
numbers (here, between bindings and words) is extracted so it can be asserted without a GL
context.

### Interface

```kotlin
object ControlHints
{
    fun swim(padConnected: Boolean): String
    fun kick(padConnected: Boolean, kickLabel: String): String
    fun bleed(padConnected: Boolean, bleedLabel: String): String
    fun confirm(padConnected: Boolean, startLabel: String): String
    fun cycle(padConnected: Boolean): String

    fun pressStart(padConnected: Boolean, startLabel: String): String
    fun playAgain(padConnected: Boolean, startLabel: String): String
    fun initialsHelp(padConnected: Boolean, startLabel: String): String
    fun legend(padConnected: Boolean, kickLabel: String, bleedLabel: String): String

    fun all(): List<String>
}
```

It takes button **display labels as Strings**, never a `GamepadButton`. That is what keeps
it engine-free. The enum-to-label map lives in `EnPustTil.kt` beside `parseGamepadButton`,
which is where the rest of the button-map handling already is.

### The asymmetry that must be commented, not silently absorbed

**Gamepad bindings are configurable; keyboard bindings are not.** `readInput` hard-codes
`Key.Z` for kick, `Key.X` for bleed, `Key.LEFT/RIGHT/UP/DOWN` for the stick, and
`updateGame` hard-codes `Key.SPACE` for lifecycle actions. There is no config key for any
of them. So keyboard labels in `ControlHints` are **compiled constants** and gamepad labels
are **parameters**, and that difference is real rather than an oversight. A comment in the
file says so, because the obvious "clean-up" is to make both parameters, and doing that
would invent four config keys that nothing reads.

### Casing rule

**Control token in caps, verb in lowercase**, joined with the existing
`ScreenText.SEPARATOR` (`"  ·  "`, U+00B7 with load-bearing double spaces — see its KDoc).

```
STICK swim  ·  A kick  ·  B bleed
ARROW KEYS swim  ·  Z kick  ·  X bleed
```

Calls to action keep full caps (`PRESS START`, `PRESS SPACE`) because they are the sign,
not the legend. This rule replaces the three disagreeing casings in §1's table.

### Resulting strings

| Function | `padConnected = true` (labels A / B / START) | `padConnected = false` |
|---|---|---|
| `swim` | `STICK` | `ARROW KEYS` |
| `kick` | `A` | `Z` |
| `bleed` | `B` | `X` |
| `confirm` | `START` | `SPACE` |
| `cycle` | `STICK UP/DOWN` | `UP/DOWN` |
| `pressStart` | `PRESS START` | `PRESS SPACE` |
| `playAgain` | `START to play again` | `SPACE to play again` |
| `initialsHelp` | `STICK UP/DOWN change letter  ·  START next` | `UP/DOWN change letter  ·  SPACE next` |
| `legend` | `STICK swim  ·  A kick  ·  B bleed` | `ARROW KEYS swim  ·  Z kick  ·  X bleed` |

`pressStart` on a booth cabinet therefore reads exactly as it does today. That is the point:
the string is unchanged in the default case and correct in every other one.

### Per-frame allocation is forbidden here

`legend`, `playAgain` and `initialsHelp` build Strings. The project's conventions forbid
per-frame allocation in the render path, and the one written exemption is HUD numeric
formatting — which this is not.

**The hints are cached and rebuilt only when gamepad presence flips.** `EnPustTil` holds a
small `ControlHintCache` (or equivalent private fields) storing the last `padConnected` value
and the five composed strings; `updateGame` compares presence once per frame and rebuilds
only on change. The button labels are resolved once in `createGame` and never change at
runtime, so presence is the only input that can vary.

This is a design element, not an optimisation, and it is why `ControlHints` returns plain
`String` rather than writing into a builder.

### Gamepad presence

`engine.input.gamepads.isNotEmpty()`, read once per frame in `updateGame`.

A joystick that is present at the OS level but has **no SDL mapping** is invisible to
`engine.input.gamepads` (a verified platform finding — `logGamepadDiagnostics` exists
precisely to tell that case apart from "nothing plugged in"). Such a pad will therefore show
**keyboard** hints. That is deliberate and correct: an unmapped pad cannot produce input
either, so naming its buttons would promise a control that does nothing. The unmapped count
already reaches the attract screen through `BoothStatus.line`, which is where a technician
should learn about it.

No last-used-device tracking. No latch. No debounce. Presence is a stable signal and a
stick drifting inside its deadzone cannot thrash it.

---

## 4. `RunLifecycle.BRIEFING` — a sixth state

### Why a state and not a flag

`RunLifecycle` is pure, engine-free and covered by 34 tests, and it is where the booth's
whole unattended-recovery behaviour is asserted. Its two exhaustive `when`s
(`simulationAdvances`, `spriteAnimates`) carry **no `else` branch**, deliberately, so that
adding a state is a compile error at every site that must decide about it. Modelling the
briefing as a boolean on IDLE would forfeit exactly that.

Rejected alternatives: a `briefing: Boolean` sub-phase of IDLE (loses the compile-time
check, and `simulationAdvances`/`spriteAnimates` would need to consult two fields); an
overlay drawn over the first seconds of PLAYING with the sim frozen (puts presentation state
into the run, and `justStarted` would fire before the player had read anything, rebuilding
the sim at the wrong moment).

### Transitions

| From | Condition | To | Side effect |
|---|---|---|---|
| `IDLE` | `pressedEdge` | **`BRIEFING`** | none — `justStarted` is **not** set here |
| `IDLE` | else `pauseEdge` | `PAUSED` | `resumeState = IDLE` (unchanged) |
| **`BRIEFING`** | `timeInState >= briefingDwellSeconds && pressedEdge` | `PLAYING` | `justStarted = true` |
| **`BRIEFING`** | else `timeInState >= briefingSeconds` | `PLAYING` | `justStarted = true` |
| `RUN_OVER` | dwell passed, not worth recording, `pressedEdge` | `PLAYING` | `justStarted = true` — **unchanged, does not re-brief** |

Everything else in `update` is untouched.

### Constants

| Constant | Value | Why |
|---|---|---|
| `BRIEFING_SECONDS` | `5f` | Owner's choice. The countdown is also the unattended-recovery guarantee: a player who walks off mid-briefing does not strand the cabinet, because the run starts, drowns, and falls through `RUN_OVER → IDLE` on the existing timers. No new idle path is required. |
| `BRIEFING_DWELL_SECONDS` | `0.75f` | The press that *opens* the briefing must not also close it. Edge detection already forces a release-then-press, but a double-tap — ordinary on an arcade button — would still skip instantly. This is the same guard `RUN_OVER` uses via `DWELL_SECONDS`, at a fifth of the duration. |

Both are constructor parameters with the companion constant as default, matching every other
timer in the class, so tests can inject short values.

### New public surface

```kotlin
val briefingCountdownSeconds: Float   // (briefingSeconds - timeInState).coerceAtLeast(0f)
val briefingSkippable: Boolean        // timeInState >= briefingDwellSeconds
```

`briefingSkippable` gates the skip hint's visibility. **The hint appearing is the
affordance** — it shows up at the instant pressing starts working, so it never invites a
press that does nothing.

### What deliberately does not change

- **`justStarted` still fires exactly once per run**, now on `BRIEFING → PLAYING` instead of
  `IDLE → PLAYING`. The `updateGame` block it drives — capture `activePadId`, construct the
  new `DiveSim`, `applyDepthPin()` **before** `camera.snapTo`, `CameraRig.snap`,
  `DiveLighting.resetAim()`, `DiverSprite.restartLoop()` — is untouched, and so is its
  ordering relative to the `initialsJustCompleted` block that `UpdateGameOrderingTest` pins.
- **`simulationAdvances = false` for BRIEFING**, joining IDLE and PAUSED. The diver does not
  sink while the text is up.
- **`spriteAnimates = true` for BRIEFING**, joining IDLE. The diver keeps kicking behind the
  briefing, so the world stays alive — which is what makes it read as a game waiting for you
  rather than a dialog box.
- **ESC is ignored in BRIEFING.** `pauseEdge` is not handled there, exactly as it is not
  handled in `RUN_OVER` or `ENTER_INITIALS`. A technician wanting the cabinet menu waits at
  most 5 seconds. Adding a `resumeState = BRIEFING` path would mean `drawPauseScreen` needed
  a third what-is-behind-me branch, for a case nobody has.

---

## 5. The briefing screen

`EnPustTil.drawBriefingScreen(hud, w, h)`, with `object BriefingLayout` holding every
anchor, mirroring `AttractLayout` and `PauseLayout`. All anchors are fractions of screen
**height** — never width, never a pixel count — because the booth panel's aspect is not
known in advance.

### The scrim, and why it is not optional

`AttractLayout` reserves `0.4 ± 0.14` for the diver halo and lays text out around it, which
is why the attract screen fits only four elements. The briefing has seven and does not fit
around it.

The screen therefore draws a **full-screen scrim first**, as `drawPauseScreen` does, through
`Draw.fillRect` at `Hud.authoredAlphaFor(...)` — the HUD surface stores RGB pre-multiplied by
alpha *and* stores alpha squared, so an authored 0.15 displays near 0.02 and every
semi-transparent colour on this surface must go through that helper. The scrim frees the full
screen height and reads as "the game is waiting".

**Its alpha is lighter than the pause screen's `0.72`** so the two screens are not mistaken
for each other at a glance; they already differ in heading and content, and this makes them
differ in weight too. The exact value is chosen from a real screen grab during implementation,
not picked here.

### Layout

```
              HOW TO DIVE

         STICK  |  swim
             A  |  kick
             B  |  bleed pearls

    SURFACE TO BANK YOUR PEARLS

           STARTING IN 5
      PRESS START TO DIVE NOW        <- only while briefingSkippable
```

The three control rows are **two columns**: the control token right-aligned at
`centreX - gap`, the verb left-aligned at `centreX + gap`. This is the same right/centre/left
trick the leaderboard already uses for `rankX` / `initialsX` / `scoreX`, so the tokens form a
clean right edge whatever their width — which matters, because a rebind can turn `A` into
`RIGHT BUMPER`.

The rule line is the one thing a player must know that the game does not otherwise teach, so
it is set apart and drawn amber rather than white. The value is `(1f, 0.85f, 0.3f)` — the
literal `drawPauseScreen` already uses for its exit-hold bar. It is **not** a reference to
`Hud.noReturnMark`, which is `private` to `Hud` and unreachable from `EnPustTil`; this screen
is drawn in `EnPustTil` and declares its own colour, as the pause screen does.

`STARTING IN n` counts whole seconds down from `briefingCountdownSeconds`. It is the one
per-frame formatted string on this screen and falls under the HUD numeric-formatting
exemption.

### Strings

`HOW TO DIVE`, `SURFACE TO BANK YOUR PEARLS`, `PRESS START TO DIVE NOW`, `STARTING IN %d`,
and the three verbs `swim` / `kick` / `bleed pearls` all go into `ScreenText` and therefore
into `ScreenText.all()`, so `AttractScreenTest`'s font-atlas sweep covers them. The skip
hint's button name comes from `ControlHints.confirm`, so it is device- and config-correct.

**The default font draws only U+0020..U+011F**, and anything above renders as nothing at all
with no x-advance, silently. Every string above is plain ASCII. No em dashes, no ellipses, no
curly quotes.

---

## 6. The in-run control legend

`Hud.renderControlLegend(surface, text, w, h)` — one line, drawn through
`render.drawTextWithOutline` like every other HUD string.

| Property | Value | Why |
|---|---|---|
| Position | bottom-left, `x = h * 0.02f` | The same left margin `drawBanked` uses, so the HUD has one left edge rather than two. |
| Baseline | `y = h - h * 0.02f - fontSize` | Symmetric bottom margin. |
| Font | `h * 0.018f` | Between the graduation labels (`0.014`) and the travelling depth readout (`0.018`) — present but subordinate. |
| Colour | `cold` at reduced authored alpha | It must be readable and must not compete with `HELD`. |

Bottom-left is genuinely free during PLAYING: the depth tape is at `x = w - h*0.035` on the
right, `HELD` is diver-attached and centred, the clock is top-centre, `BANKED` is top-left,
and `drawBoothStatusLine` — the only other thing that uses the bottom-left corner — is called
from `drawIdleScreen` and therefore never coexists with the legend.

### It is called from the dispatch, not from inside `Hud.render`

`Hud.render` is called by **three** branches — `PLAYING`, `RUN_OVER` and `ENTER_INITIALS`.
The legend belongs to `PLAYING` only; during `RUN_OVER` the play-again hint is the relevant
control and a swim/kick/bleed legend under it would be noise.

So `renderGame`'s `PLAYING` branch calls `Hud.render(...)` and then
`Hud.renderControlLegend(...)` as a second, separate call. Threading a nullable
`controlLegend: String?` through `Hud.render` would hide that gating inside a function whose
signature already carries eight parameters; keeping it at the dispatch site makes "this is
PLAYING-only" visible at the one place that knows the state.

---

## 7. `EPT_BRIEFING_HOLD` — a capture pin

The briefing auto-starts after 5 seconds. The verified way to photograph this game is the
shell route (`caffeinate`, `./gradlew run`, wait ~12 s for the window, focus it,
`screencapture`) — which takes longer than the briefing lasts. Without a pin, **the screen
being built cannot be photographed**, and the verification loop this work is required to run
becomes impossible for exactly the task that most needs it.

`EPT_BRIEFING_HOLD=1` makes the briefing's countdown never expire: `BRIEFING` then leaves
only on a press. It joins `EPT_DEV`, `EPT_DEPTH`, `EPT_SCREENSHOT` and `EPT_FAIL_BOOT` — one
`getenv` at startup, the same cost as every other flag in that family, unset at the booth.

It is read in `EnPustTil` and passed as the `briefingSeconds` constructor argument
(`Float.POSITIVE_INFINITY` when set), so `RunLifecycle` itself stays free of environment
reads and remains a pure object. `briefingSkippable` is unaffected, so the pinned screen
still shows its skip hint and still starts on a press.

---

## 8. Testing

Every unit below asserts a **relationship that would actually break**, per the project's
convention that a test which cannot fail is worse than no test.

### `ControlHintsTest` (new)

- Every string in `ControlHints.all()` is inside the font atlas (`DefaultFont.canDraw`), at
  both presence values.
- **Every `GamepadButton.entries` value produces a drawable, non-empty label.** This is the
  test that makes "derive from live config" safe: a technician can bind any of the ~20 names
  and none of them can vanish into the atlas gap.
- The connected and disconnected forms of each hint **differ** — catches a copy-paste that
  wires both branches to the same constant.
- A rebound button appears in the output: `kick(true, "RIGHT BUMPER")` contains
  `RIGHT BUMPER`. This is the defect from §1, pinned.
- The keyboard forms do **not** vary with the passed gamepad label — they are compiled
  constants and must ignore it.
- Composed strings use `ScreenText.SEPARATOR`, not a raw double space.

### `RunLifecycleTest` (extended)

- A press in IDLE enters `BRIEFING`, **not** `PLAYING`, and `justStarted` is false.
- The briefing auto-starts after `briefingSeconds` with no input, and `justStarted` fires
  exactly once.
- A press **before** `briefingDwellSeconds` does not skip.
- A press **after** it does, and `justStarted` fires exactly once.
- A **held** button does not skip the briefing (edge, not level).
- `simulationAdvances` is false in `BRIEFING`; `spriteAnimates` is true.
- `briefingCountdownSeconds` reaches 0 and does not go negative.
- `briefingSkippable` is false at entry and true after the dwell.
- **`RUN_OVER → PLAYING` still goes direct** — a retry does not enter `BRIEFING`. This is the
  owner's decision, pinned so a later "for consistency" change reddens.
- ESC during `BRIEFING` does not pause.
- An infinite `briefingSeconds` never auto-starts but still skips on a press (the
  `EPT_BRIEFING_HOLD` contract).

### `BriefingScreenTest` (new)

Modelled on `AttractScreenTest`'s layout half:

- Every `BriefingLayout` anchor is a screen fraction strictly in `(0, 1)`.
- No two elements' boxes overlap vertically (text grows **downward** from its anchor, so a
  block occupies `y .. y + fontSize` — the same convention `AttractLayout` documents).
- The whole block fits on screen at 4:3, 16:9 and 32:9.
- The two control columns do not collide at the **widest** button label.
- Every briefing string is drawable.

### `PauseScreenTest` (new)

The gap: `PauseLayout`'s KDoc says its relationships are checked "see `PauseScreenTest`" and
**no such file exists** — `PauseLayout` is referenced by no test at all. Same shape as above:
anchors in `(0, 1)`, title / resume hint / exit hint / progress bar do not overlap, the bar's
fill is bounded by its track, `barFillWidth(0f)` is zero and `barFillWidth(1f)` is the full
track. The KDoc's claim becomes true rather than being deleted.

### `HudTest` (extended)

- The legend's right edge clears the depth tape's left edge at the widest tested aspect.
- The legend's bottom clears the bottom of the screen.
- The legend sits below the `HELD` numerals' widest excursion, or is otherwise proven not to
  collide with them.

### `AttractScreenTest` (extended)

`ScreenText.all()` grows to include the new strings and both device forms of the three
rewritten ones. The existing sweep then covers them with no change to the test's logic.

### Verification that is not a unit test

**Green tests are not evidence this game looks right** — a large share of this project's bug
history is invisible-in-tests rendering faults. Every task in the implementation plan ends
with a **real window grab** via the documented shell route, inspected before the task is
called done.

`EPT_SCREENSHOT` is **not** usable for this: `ScreenshotEffect.getTexture()` returns
`RenderTexture.BLANK`, so any surface it attaches to composites as blank, and it dumps each
surface separately so a hand-recomposite does not reproduce the engine's frame. Both facts
have cost this project a day each. The briefing, the legend and the pause screen are all on
the `"hud"` surface, and how they read **over live water** is the entire question, so the
real frame is the only valid evidence.

---

## 9. Files

**New**

- `src/main/kotlin/render/ControlHints.kt`
- `src/test/kotlin/render/ControlHintsTest.kt`
- `src/test/kotlin/render/BriefingScreenTest.kt`
- `src/test/kotlin/render/PauseScreenTest.kt`

**Modified**

- `src/main/kotlin/render/RunLifecycle.kt` — `BRIEFING`, two constants, two constructor
  params, two accessors, two `when`s
- `src/main/kotlin/EnPustTil.kt` — `ScreenText` additions and the three rewrites,
  `gamepadButtonLabel`, the hint cache, `BriefingLayout`, `drawBriefingScreen`, the dispatch
  branch, `EPT_BRIEFING_HOLD`
- `src/main/kotlin/render/Hud.kt` — `renderControlLegend` and its layout helpers
- `src/test/kotlin/render/RunLifecycleTest.kt`, `src/test/kotlin/render/HudTest.kt`,
  `src/test/kotlin/AttractScreenTest.kt`
- `docs/superpowers/specs/2026-08-04-en-pust-til-design.md` — §5 gains the full shipped
  binding set (it currently documents only stick / A / B and has never been updated for the
  keyboard mirrors, ESC, Q-hold or the configurable map); §17 gains an amendment row
- `CLAUDE.md` — the sixth lifecycle state, `ControlHints` in the pure-logic list,
  `EPT_BRIEFING_HOLD` beside the other flags
- `docs/superpowers/specs/2026-08-11-outstanding-work.md` — §2.5 is marked done rather than
  left as outstanding work, with the symbols that implement it named (see §1, "Not in scope").
  A stale to-do that has already been built is how the same work gets done twice.

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| The briefing delays every walk-up by up to 5 s, slowing the queue. | The skip press is live after 0.75 s, and a returning player's retry never re-briefs at all. If it proves too slow at the booth, `BRIEFING_SECONDS` is one constant. |
| The scrim makes the briefing look like the pause screen. | Different heading, different content, lighter alpha — and the alpha is chosen from a real grab, not from reasoning. |
| The legend is clutter for the 90% of players who do not need it after ten seconds. | The owner chose permanent over fading explicitly. It is small, dim and in a corner nothing else uses. Fading is a later change to one call site. |
| An unmapped arcade encoder shows keyboard hints on a cabinet with no keyboard. | Correct behaviour — an unmapped pad cannot play either. `BoothStatus` already surfaces the unmapped count on the attract screen, which is the actionable signal. |
| Deriving labels from config lets a bad rebind put a long name on screen. | `BriefingScreenTest` checks the widest label; `ControlHintsTest` checks every entry is drawable. A name that is not a valid button falls back to the compiled default and warns, via the existing `gamepadButtonConfigWarning`. |
