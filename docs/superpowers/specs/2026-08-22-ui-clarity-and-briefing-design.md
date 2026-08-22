# Én Pust Til — UI clarity and the pre-run briefing

**Status:** design, approved 2026-08-22. **Revision 3.**

Revision 2 followed an adversarial review that verified every claim in revision 1 against the
code and found two blockers plus seventeen smaller defects. Revision 3 adds four corrections
found by self-check. §12 records all of them, because several of revision 1's justifications
were confidently wrong and this project's history is full of stale reasoning outliving its
facts.

**Review status, stated honestly:** the independent re-review of revision 2 **did not
complete** — both reviewer agents were terminated by an account session limit, one before it
had read the document. Revision 3's corrections are therefore *self-verified against the
code*, not independently confirmed. The arithmetic in §5 and §6 and the 34-test claim in §4.1
are the parts most worth an independent pass before implementation begins.

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
in `application.cfg:73-76`, all four currently commented out so they run on compiled
defaults). The strings that name buttons are hard-coded literals:

| String | Current value | Defect |
|---|---|---|
| `ScreenText.PRESS_START` (`EnPustTil.kt:535`) | `"PRESS START"` | Names `START`, which is `restartButton` and configurable. Rebind it and the screen lies. |
| `ScreenText.PLAY_AGAIN` (`:537`) | `"SPACE / START to play again"` | Same, plus it names a keyboard key **and** a gamepad button in one literal — so it is half-irrelevant on every machine. |
| `ScreenText.INITIALS_HELP` (`:538`) | `"UP/DOWN: change letter   A / START: next"` | Same again, **and** it omits the stick, which is the cabinet's actual control for cycling letters (`readInitialsCycle`). Also uses a raw **triple** space where every other string uses `ScreenText.SEPARATOR`. |

Their casings disagree with each other three ways (`SHOUTING`, `Sentence case`,
`Mixed: label case`), which at arcade viewing distance reads as three unrelated systems.

The arcade encoder's real button codes are **not known until it is plugged in** — that is
why the button map is configurable at all — so "it says A and A is right" is an assumption
this design refuses to keep making.

### Not in scope

**Spec §12's on-screen list is unchanged.** All four of its bullets — `BANKED` cold white
top-left, `HELD` enormous amber attached to the diver, the depth tape down the right edge
with its point-of-no-return marker, and the permanent amber up-arrow — stay exactly as they
are. This design **adds** to the HUD; it restyles nothing.

**The HUD restyle in `2026-08-11-outstanding-work.md` §2.5 is already implemented, and that
document is stale in saying otherwise.** Verified in `render/Hud.kt`: the pearl icon is
`pearlIcon` (`:229`) + `bankedIconDiameter` (`:479`) drawn through `IridescenceRenderer`; the
boxed clock is `clockPlate` (`:270`) + `roundedCornerBands` (`:570`) + `roundedBandHalfWidth`
(`:517`); the tape's graduations are `TAPE_GRADUATION_METRES = 25f` (`:311`) with
`graduationCount`/`graduationDepth` (`:557-559`) and a lozenge handle. Nothing in §2.5's
*work* remains, this design does not reopen it, and §9 records the correction.

§2.5 also carries one **undecided** item, which is not the same thing as unbuilt work: *"The
mockup's held count is white; ours is amber heating toward red as the haul grows, which is a
deliberate cue from spec §12. Probably keep ours — flag rather than change."* The code keeps
amber. §9's correction records that flag as **accepted**, rather than deleting the question
along with the work.

---

## 2. Decisions taken, and by whom

All twelve were taken by the owner on 2026-08-22, before any code was written.

| Decision | Choice | Consequence |
|---|---|---|
| Which controls do prompts depict? | **Whichever is actually connected** | Needs a runtime presence read and two sets of strings. See §3, which widens "connected" after review finding 10. |
| How does the briefing end? | **Countdown auto-start, or a press** | The countdown is the unattended-recovery guarantee; no new idle path is needed. |
| In-run guidance | **Small permanent legend** | A new HUD element, drawn for the whole run. |
| UI scope | **Full pass, propose as found** | §1's defect list is the proposal; it is the whole of it. |
| Button names in hints | **Derived from live config** | A rebind updates the screen. Needs a `GamepadButton` → label map and a font-atlas test over every entry. |
| Which hint set shows | **Gamepad presence wins** | No last-used-device tracking, no flicker, no debounce. |
| Language | **English, as everything else already is** | The only Norwegian string stays the title, `"ÉN PUST TIL"`. |
| Briefing content | **Controls plus the one core rule** | Air, the anglerfish and the point-of-no-return are deliberately NOT taught here. |
| Does a retry re-brief? | **No — attract only** | `IDLE → BRIEFING → PLAYING`; `RUN_OVER → PLAYING` stays direct. |
| Skip guard | **0.75 s dwell** | Mirrors `RUN_OVER`'s proven `DWELL_SECONDS` shape. |
| Countdown | **5 seconds** | |
| Missing pause test | **Write it** | `PauseLayout`'s KDoc (`EnPustTil.kt:737`) cites a `PauseScreenTest` that does not exist; `PauseLayout` is referenced by no test at all. |

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
    // Atoms
    fun swim(arcade: Boolean): String
    fun kick(arcade: Boolean, kickLabel: String): String
    fun bleed(arcade: Boolean, bleedLabel: String): String
    fun confirm(arcade: Boolean, startLabel: String): String
    fun cycle(arcade: Boolean): String

    // Composed — these are the four that are CACHED (see below)
    fun pressStart(arcade: Boolean, startLabel: String): String
    fun playAgain(arcade: Boolean, startLabel: String): String
    fun initialsHelp(arcade: Boolean, startLabel: String): String
    fun legend(arcade: Boolean, kickLabel: String, bleedLabel: String): String

    fun all(): List<String>   // every atom and composite, both arcade values, widest labels
}
```

It takes button **display labels as Strings**, never a `GamepadButton`. That is what keeps
it engine-free. The enum-to-label map (`gamepadButtonLabel`, `name.replace('_', ' ')`) lives
in `EnPustTil.kt` beside `parseGamepadButton`, which is where the rest of the button-map
handling already is.

### The asymmetry that must be commented, not silently absorbed

**Gamepad bindings are configurable; keyboard bindings are not.** `readInput` hard-codes
`Key.Z` for kick, `Key.X` for bleed, `Key.LEFT/RIGHT/UP/DOWN` for the stick, and
`updateGame:1529` hard-codes `Key.SPACE` for lifecycle actions. There is no config key for
any of them. So keyboard labels in `ControlHints` are **compiled constants** and gamepad
labels are **parameters**, and that difference is real rather than an oversight. A comment in
the file says so, because the obvious "clean-up" is to make both parameters, and doing that
would invent four config keys that nothing reads.

### Casing rule

**Control token in caps, verb in lowercase**, joined with the existing
`ScreenText.SEPARATOR` (`"  ·  "`, U+00B7 with load-bearing double spaces — see its KDoc).
Calls to action keep full caps (`PRESS START`) because they are the sign, not the legend.
This rule replaces the three disagreeing casings in §1's table.

### Resulting strings

| Function | `arcade = true` (labels A / B / START) | `arcade = false` |
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

### `arcade` is joystick PRESENCE, not gamepad MAPPING

**This is revision 2's most important change, and revision 1 got it wrong.**

The obvious signal is `engine.input.gamepads.isNotEmpty()`. That is wrong at exactly the
booth failure the project already documents: a generic arcade USB encoder **may have no SDL
gamepad mapping**, in which case it is completely invisible to `engine.input.gamepads` while
working fine at the OS level. Gating on the mapped list would then put **`PRESS SPACE`** on
the attract screen of a cabinet that has no keyboard — in front of the queue, as the single
most visible string in the game. Today that string reads `PRESS START` and is right by
accident in precisely that case. A design that makes the booth's worst input failure *also*
display the wrong instruction is a regression, and "`BoothStatus` already reports the
unmapped count" answers the technician, not the queue.

So:

```
arcade = engine.input.gamepads.isNotEmpty() || rawJoystickPresent()
```

`rawJoystickPresent()` is extracted from the raw GLFW enumeration `logGamepadDiagnostics`
already performs (`GLFW.glfwJoystickPresent`), which exists for exactly this purpose: telling
"nothing plugged in" apart from "plugged in but unmapped". An unmapped encoder therefore
shows **arcade** hints. Those hints name a button that will not respond — but the cabinet has
no keyboard either, so the alternative names a key that physically does not exist. Naming the
right control on a broken machine beats naming a control the machine does not have.

**Cadence.** `engine.input.gamepads.isNotEmpty()` is free — `Input.getGamepads()` returns a
`java.util.List<Gamepad>`, so `isNotEmpty()` resolves to the inline `Collection<T>.isNotEmpty()`
(`!isEmpty()`) and allocates no iterator. This was checked specifically, because
`gamepadIdBuffer` and `GamepadScan` both document the opposite result for `firstOrNull { }`
and `forEach`. The GLFW scan is not free, so it is **re-evaluated once a second**, on the
existing slow cadence — a joystick being plugged in is not a per-frame event.

No last-used-device tracking. No latch. No debounce.

### Per-frame allocation is forbidden here

`pressStart`, `playAgain`, `initialsHelp` and `legend` build Strings. The project's
conventions forbid per-frame allocation in the render path, and the one written exemption is
HUD numeric formatting — which this is not. The five atoms are compile-time constants and
cost nothing.

**All four composites are cached** in a small `ControlHintCache` holding the last `arcade`
value and the four strings; the cache rebuilds only when `arcade` flips. The button labels
are resolved once and never change at runtime, so `arcade` is the only input that can vary.

**Seeding matters and revision 1 omitted it.** `lifecycle` and `devMode` are *field
initialisers* (`EnPustTil.kt:837`, `:933`), while `kickButton`/`bleedButton`/`restartButton`
are resolved inside `createGame` (`:1060-1063`). A cache built at field-init time would hold
labels from before the config was read. **The cache is therefore seeded in `createGame`,
after the button map resolves and before the first `renderGame`.**

### One accepted limit, stated rather than discovered

`ControlHints` takes a single `startLabel`, which is `restartButton` — the **primary**. Both
restart keys are configurable (`restartButton` default `START`, `restartButtonAlt` default
`A`), and a technician who binds only the alt gets a screen naming a button that does
nothing. This is accepted, not overlooked: two button names on one line of a sign is worse at
arcade distance than one, and the primary is the one a technician configures first.

Relatedly, the label shown is the **enum name** (`RIGHT_BUMPER` → `RIGHT BUMPER`), which on a
generic encoder bears no relation to anything silkscreened on the cabinet. Also accepted —
there is no source for the physical legend, and the enum name is at least the same string the
technician typed into `application.cfg`.

---

## 4. `RunLifecycle.BRIEFING` — a sixth state

### Why a state and not a flag

`RunLifecycle` is pure, engine-free and covered by 34 tests, and it is where the booth's
whole unattended-recovery behaviour is asserted. Adding a state is a **compile error at four
sites**, because a non-exhaustive `when` over an enum is an `error`, not a warning:

1. `RunLifecycle.simulationAdvances` (`RunLifecycle.kt:177`)
2. `RunLifecycle.spriteAnimates` (`:199`)
3. `RunLifecycle.update`'s `when (state)` (`:286`) — where the transitions actually go
4. `EnPustTil.renderGame`'s `when (lifecycle.state)` (`EnPustTil.kt:1844`)

*(Revision 1 said two, and omitted both of the sites that carry the actual work.)*

Modelling the briefing as a boolean on IDLE would forfeit all four checks. Rejected
alternatives: a `briefing: Boolean` sub-phase of IDLE; an overlay drawn over the first
seconds of PLAYING with the sim frozen (puts presentation state into the run, and
`justStarted` would fire before the player had read anything).

### Transitions

| From | Condition | To | Side effect |
|---|---|---|---|
| `IDLE` | `pressedEdge && briefingSeconds > 0f` | **`BRIEFING`** | **`justEnteredBriefing = true`** — see §4.2 |
| `IDLE` | `pressedEdge && briefingSeconds <= 0f` | `PLAYING` | `justStarted = true` — see §4.1 |
| `IDLE` | else `pauseEdge` | `PAUSED` | `resumeState = IDLE` (unchanged) |
| **`BRIEFING`** | `timeInState >= briefingDwellSeconds && pressedEdge` | `PLAYING` | `justStarted = true` |
| **`BRIEFING`** | else `timeInState >= briefingSeconds` | `PLAYING` | `justStarted = true` |
| `RUN_OVER` | dwell passed, not worth recording, `pressedEdge` | `PLAYING` | `justStarted = true` — **unchanged, does not re-brief** |

`BRIEFING` does not handle `pauseEdge`, exactly as `RUN_OVER` and `ENTER_INITIALS` do not. A
technician wanting the cabinet menu waits at most 5 seconds. Adding a `resumeState = BRIEFING`
path would give `drawPauseScreen` a third what-is-behind-me branch for a case nobody has.

### 4.1 A zero-length briefing is no briefing — and it is what keeps 29 tests honest

**Review finding 1 (blocker).** Inserting `BRIEFING` between IDLE and PLAYING breaks **29 of
the 34** existing `RunLifecycleTest` tests. Three shared helpers press once and immediately
assert the resulting state — `enterRunOver` (`:36-43`) asserts `PLAYING`, `enterRunOverWithScore`
(`:150-156`) asserts `RUN_OVER`, `enterPausedRun` (`:26-33`) asserts `PAUSED` — and all three
drive with `dt = 0f`, so a briefing would never expire. Revision 1 described that file as
merely "extended". It is the most safety-critical test file in the project: it encodes the
booth's entire unattended-recovery contract.

**The rule: `briefingSeconds <= 0f` means IDLE + press goes straight to PLAYING.** A
zero-length briefing is not a briefing that closes instantly; it is no briefing at all, and
`justStarted` fires on the IDLE transition exactly as it does today.

The whole fix in the test file is then **one line** — `newLifecycle()` (`RunLifecycleTest.kt:17`)
gains `briefingSeconds = 0f` — and all 34 existing tests keep asserting exactly what they
assert now, unmodified. The briefing's own tests construct their own lifecycle with real
values.

This is not a special case bolted on for the tests. It is the natural reading of the
parameter, it gives a one-constant way to disable the briefing if it proves too slow in front
of a real queue, and it gets its own test so the rule is pinned rather than incidental.

### 4.2 `justEnteredBriefing`, and the booth failure the auto-start would otherwise resurrect

**Review finding 2 (blocker), and the most serious defect in revision 1.**

The `justStarted` block opens with `activePadId = lifecycleEdges.firedPadId`
(`EnPustTil.kt:1604`). `firedPadId` is reset to `null` at the top of **every** frame
(`LifecycleInputEdges.begin`, `:145`). The countdown auto-start fires `justStarted` on a frame
with **no press**, so `activePadId` would be `null`, so `selectGameplayPad(padIds, null)`
falls through to `padIds.firstOrNull()` (`GamepadScan.kt:41-42`) — slot 0.

That is verbatim the failure `GamepadScan`'s class doc exists to record: *"If any stray HID
took slot 0 … the cabinet's own START press worked and the diver did not move. A startable,
unplayable run, silently repeating for every person in the queue."* And the auto-start is the
**common** path — every player who presses once and then reads.

Revision 1's claim that "the `justStarted` block is untouched" was wrong, and wrong in the
direction that reintroduces a fixed booth bug.

**The fix.** `RunLifecycle` gains a one-tick `justEnteredBriefing`, fired on IDLE → BRIEFING.
`EnPustTil` latches the pad there:

```kotlin
if (lifecycle.justEnteredBriefing) activePadId = lifecycleEdges.firedPadId
```

and the `justStarted` block becomes:

```kotlin
activePadId = lifecycleEdges.firedPadId ?: activePadId
```

which keeps the latched value when no edge fired this frame (auto-start) and takes the fresh
one when a press caused the skip. Every other path — `RUN_OVER → PLAYING`, and the
zero-length-briefing IDLE → PLAYING — has a real edge on the frame, so `firedPadId` is
non-null and the behaviour is byte-identical to today's.

`activePadId` is still cleared on `justReturnedToIdle` (`:1650-1669`), so a latch cannot
outlive its run.

**Edge cases, walked rather than assumed:**

| Case | Result |
|---|---|
| Pad A opens the briefing, pad B presses to skip | `firedPadId = B` wins. Whoever pressed to *start the dive* gets the dive. Correct. |
| Pad A opens the briefing, nobody presses, countdown fires | `firedPadId = null`, latch holds A. **This is the case the fix exists for.** |
| The latched pad disconnects during the briefing | `selectGameplayPad` finds no match and falls to `firstOrNull()` — identical to today's behaviour when a pad disconnects mid-run. No regression. |
| Zero-length briefing (§4.1), and `RUN_OVER → PLAYING` | Both have a real edge on the frame, so `firedPadId` is non-null and `?: activePadId` never engages. Byte-identical to today. |
| The run is started from the **keyboard** (SPACE) | `offerKeyboardEdge` is its own source and sets no pad, so `firedPadId` is null and `activePadId` stays null → `selectGameplayPad` falls to slot 0. **This is exactly today's behaviour for a keyboard start** and is not a regression; it is noted here so a future reader does not mistake it for one. |

**This gets a dedicated test**, because it is the one defect here that is invisible on a
developer machine with exactly one pad and fatal on a cabinet with a stray HID in slot 0.

### Constants

| Constant | Value | Why |
|---|---|---|
| `BRIEFING_SECONDS` | `5f` | Owner's choice. The countdown is also the unattended-recovery guarantee: a player who walks off mid-briefing does not strand the cabinet, because the run starts, drowns, and falls through `RUN_OVER → IDLE` on the existing timers. No new idle path is required. |
| `BRIEFING_DWELL_SECONDS` | `0.75f` | The press that *opens* the briefing must not also close it. Edge detection already forces a release-then-press, but a double-tap — ordinary on an arcade button — would still skip instantly. Same guard `RUN_OVER` uses via `DWELL_SECONDS`, at a fifth of the duration. |

Both are constructor parameters with the companion constant as default, matching every other
timer in the class.

### New public surface

```kotlin
val justEnteredBriefing: Boolean            // one-tick, IDLE -> BRIEFING
val briefingSkippable: Boolean              // state == BRIEFING && timeInState >= briefingDwellSeconds
val briefingCountdownSeconds: Float         // state == BRIEFING ? (briefingSeconds - timeInState).coerceAtLeast(0f) : 0f
val briefingAutoStarts: Boolean             // briefingSeconds.isFinite()
```

**Both derived accessors are guarded on `state == BRIEFING`.** `timeInState` is shared by the
RUN_OVER, PAUSED and ENTER_INITIALS dwells (`RunLifecycle.kt:220`), so an unguarded
`briefingSkippable` would read `true` in IDLE after 0.75 s. Harmless today, since only the
BRIEFING draw branch reads it — but §8's test would pass against a state-blind implementation
and then pin the wrong contract. *(Review finding 17.)*

`briefingSkippable` gates the skip hint's visibility. **The hint appearing is the
affordance** — it shows up at the instant pressing starts working, so it never invites a
press that does nothing.

`briefingAutoStarts` exists for `EPT_BRIEFING_HOLD`; see §7.

### What deliberately does not change

- **`justStarted` still fires exactly once per run.** Its block's ordering — `activePadId`,
  then `DiveSim(...)`, then `applyDepthPin()` **before** `camera.snapTo`, then `CameraRig.snap`,
  `resetAim`, `restartLoop` (`:1600-1638`) — is unchanged, and so is its position relative to
  the `initialsJustCompleted` block that `UpdateGameOrderingTest` pins. Only the *source* of
  `activePadId` changes, per §4.2.
- **`simulationAdvances = false` for BRIEFING**, joining IDLE and PAUSED.
- **`spriteAnimates = true` for BRIEFING**, joining IDLE. The diver keeps kicking behind the
  briefing, so the world stays alive — which is what makes it read as a game waiting for you
  rather than a dialog box.

**Two things that say "except IDLE" stop being true, and both must be updated.** BRIEFING is
the **second** state where `spriteAnimates` disagrees with `simulationAdvances`:

- `spriteAnimates`' KDoc (`RunLifecycle.kt:183-198`) reads *"this is `simulationAdvances` with
  IDLE added back in, PAUSED excluded"*. It becomes **IDLE and BRIEFING** added back in.
- `RunLifecycleTest`'s test named `spriteAnimates agrees with simulationAdvances everywhere
  except IDLE` must be renamed to `…except IDLE and BRIEFING`. The test body itself passes
  unchanged — it only reaches PLAYING and RUN_OVER explicitly — so this is a **naming** fix,
  not a behavioural one. A test whose name is a lie is exactly the kind of stale claim this
  project keeps paying for.

That KDoc anticipated this case in as many words: *"a sixth state added later would silently
inherit 'sprite frozen' instead of failing to compile. An exhaustive `when` on purpose, with
no `else`."* The compile error is the design working; updating the prose beside it is the
other half.

---

## 5. The briefing screen

`EnPustTil.drawBriefingScreen(hud, w, h)`, with `object BriefingLayout` holding every anchor,
mirroring `AttractLayout` and `PauseLayout`. All anchors are fractions of screen **height** —
never width, never a pixel count — because the booth panel's aspect is not known in advance.

### What is drawn underneath — stated, because revision 1 left it ambiguous

**The BRIEFING branch draws nothing beneath the scrim but the live world.** No attract sign,
no leaderboard, no booth status line, no numeric HUD.

Every other overlay branch in the dispatch draws something first — PAUSED draws
`drawIdleScreen` or `Hud.render` and *then* the scrim (`:1855-1860`). BRIEFING deliberately
does not: the briefing is the only thing on screen to read, and a leaderboard competing with
it is the one thing that would stop a first-timer reading the rule line. The cost is that the
leaderboard is hidden for up to 5 s per play, which is accepted. *(Review finding 9 — two
implementers would otherwise have chosen differently, and one choice hides the board.)*

### The scrim

Drawn **first** in this branch, full-screen, via the top-level `Surface.fillRect` extension
(`render/Draw.kt:91`, called as `hud.fillRect(...)` — there is no `Draw` object) at
`Hud.authoredAlphaFor(...)`. The HUD surface stores RGB pre-multiplied by alpha *and* stores
alpha squared, so an authored 0.15 displays near 0.02 (measured at `Hud.kt:377-387`) and every
semi-transparent colour on this surface must go through that helper.

**Its alpha is lighter than the pause screen's `0.72`** so the two are not mistaken for each
other; they already differ in heading and content, and this makes them differ in weight. The
exact value is chosen from a real screen grab during implementation, not picked here.

### Layout — concrete anchors

Text grows **downward** from its anchor and occupies `y .. y + fontSize` (the convention
`AttractLayout` documents at `EnPustTil.kt:629`). All values are fractions of height.

| Element | Anchor | Font | Occupies |
|---|---|---|---|
| `HOW TO DIVE` | `TITLE_Y = 0.16` | `TITLE_FONT = 0.055` | 0.160 – 0.215 |
| control row 0 | `ROWS_TOP_Y = 0.30` | `ROW_FONT = 0.030` | 0.300 – 0.330 |
| control row 1 | `+ ROW_FONT * ROW_SPACING` | `0.030` | 0.357 – 0.387 |
| control row 2 | `ROW_SPACING = 1.9` | `0.030` | 0.414 – 0.444 |
| `SURFACE TO BANK YOUR PEARLS` | `RULE_Y = 0.53` | `RULE_FONT = 0.034` | 0.530 – 0.564 |
| `STARTING IN n` | `COUNTDOWN_Y = 0.66` | `COUNTDOWN_FONT = 0.028` | 0.660 – 0.688 |
| skip hint | `SKIP_Y = 0.74` | `SKIP_FONT = 0.022` | 0.740 – 0.762 |

Every gap is positive and the block ends at 0.762, clear of the bottom.

The rule line is the one thing a player must know that the game does not otherwise teach, so
it is set apart and drawn amber rather than white, at `(1f, 0.85f, 0.3f)` — the literal
`drawPauseScreen:2161` already uses for its exit-hold bar. It is **not** `Hud.noReturnMark`:
that is public and reachable (`Hud.kt:408`, and `EnPustTil` already reads `Hud.authoredAlphaFor`),
but it is a *tape* colour carrying its own authored alpha and a different RGB — `(1, 0.9, 0.35)`.
This screen declares its own literal, as the pause screen does. *(Revision 1 claimed
`noReturnMark` was private, which was simply false.)*

### The control rows are aligned INWARD

Token `xOrigin = 1f` (right-aligned) at `centreX - COLUMN_GAP * h`; verb `xOrigin = 0f`
(left-aligned) at `centreX + COLUMN_GAP * h`, with `COLUMN_GAP = 0.012`. The tokens form a
clean right edge whatever their width, which is the property this buys — a rebind can turn
`A` into `RIGHT BUMPER`.

**This is the opposite of the leaderboard's alignment**, and revision 1 said to copy it.
`AttractLayout` aligns **outward**: `rankX` is `xOrigin = 0` at `centre - halfSpan`, `scoreX`
is `xOrigin = 1` at `centre + halfSpan` (`:679-729`). An implementer copying `rankX`/`scoreX`
gets the alignments backwards and loses exactly the clean edge. *(Review finding 14.)*

The `|` in any sketch of this layout is a guide, not a drawn glyph.

### Strings

`HOW TO DIVE`, `SURFACE TO BANK YOUR PEARLS`, `TO DIVE NOW`, `STARTING IN %d`, and the three
verbs `swim` / `kick` / `bleed pearls` go into `ScreenText`. The skip hint is
`"PRESS " + ControlHints.confirm(arcade, startLabel) + " TO DIVE NOW"`, so its button name is
device- and config-correct.

`STARTING IN n` counts whole seconds down from `briefingCountdownSeconds` and is the one
per-frame formatted string here, under the HUD numeric-formatting exemption.

**The default font draws only U+0020..U+011F**, and anything above renders as nothing at all
with no x-advance, silently. Every string above is plain ASCII.

---

## 6. The in-run control legend

`Hud.renderControlLegend(surface, text, w, h)` — one line, drawn through
`render.drawTextWithOutline` like every other HUD string.

### It goes TOP-RIGHT, not bottom-left

Revision 1 put it bottom-left and asserted that corner was "genuinely free". **It is not.**
`DiveCamera.kt:43` clamps the camera's lag at `diverDepth - VISIBLE_DEPTH_METRES * DIVER_MAX_FRACTION`
(`Framing.kt:99`, `0.75`), so at 16:9 — where the width regime binds and only 55.42 m is
visible — the diver can sit as low as `45/55.42 = 0.81h`. `drawHeld` puts the numerals at
`diverY + HELD_OFFSET_METRES * ppm` with `HELD_OFFSET_METRES = 8f` (`Hud.kt:158`) = `+0.144h`,
at a font up to `h * (0.045 + 0.05) = 0.095h`. Worst case the HELD glyphs occupy roughly
`0.956h .. 1.05h` — straddling revision 1's proposed baseline at `0.962h`. Horizontally,
`COLUMN_HALF_WIDTH = 40f` against a 98.516 m visible width puts the diver's leftmost screen x
at `0.167h`, and HELD is `xOrigin = 0.5f`, so it reaches further left still. *(Review finding 7.)*

**Top-right is provably clear of HELD**, which is why it wins:

| Property | Value | Why it is safe |
|---|---|---|
| Baseline | `y = h * 0.02f` | Same top margin `drawBanked` uses. |
| Alignment | `xOrigin = 1f` at `x = w - h * 0.02f` | Right edge, symmetric margin. |
| Font | `h * 0.016f` | Between the graduation labels (`TAPE_GRADUATION_FONT_FRACTION = 0.014f`, `Hud.kt:320`) and the travelling depth readout (`TAPE_LABEL_FONT_FRACTION = 0.018f`, `:173`). Present but subordinate. |
| Colour | a new pre-allocated `private val legendInk` beside `cold` | See below. |

- **Clear of HELD, structurally.** `DIVER_MIN_FRACTION = 0.15f` (`Framing.kt:98`) bounds the
  diver's *highest* screen position, and HELD hangs `HELD_OFFSET_METRES = 8f` **below** him.
  Eight metres is `8 / visibleDepth` of screen height, and visible depth is **aspect-dependent**
  (60 m at or below the 1.6419 design aspect, 55.42 m at 16:9, 27.71 m at 32:9), so that offset
  ranges from `0.133h` upward. The **loosest** case is therefore the widest visible depth:
  `0.15h + 0.133h = 0.283h` is the highest the HELD glyphs can ever reach, at any aspect. The
  legend ends at `0.036h`. *(An earlier draft quoted `0.294h`, which is the 16:9 figure stated
  as if it were universal; the conclusion is unchanged but the bound is the 60 m one.)*
- **Clear of the depth tape**, which starts at `TAPE_TOP_FRACTION = 0.10f` (`Hud.kt:170`).
- **Clear of `BANKED`**, which is top-*left*.
- **Clear of the clock box** — the one relationship that can actually fail, and therefore the
  one §8 asserts numerically. The clock is centred with half-width
  `fontSize * (glyphCount * CLOCK_BOX_GLYPH_WIDTH_EM + 2 * CLOCK_BOX_PAD_X_EM) / 2`
  (`Hud.kt:495`, `0.62f` and `0.5f` at `:245-246`), which for a four-glyph `0:30` at
  `fontSize = 0.05h` is `0.087h`. The legend's left edge must stay right of the clock's right
  edge, i.e. `w - 0.02h - legendWidth > w/2 + 0.087h`.

  | Case | Legend width | Requires | Verdict |
  |---|---|---|---|
  | Default labels — `STICK swim · A kick · B bleed`, 33 chars | `0.327h` | `w > 0.868h` | **Safe at every landscape aspect.** |
  | Longest possible rebind — `STICK swim · RIGHT BUMPER kick · LEFT BUMPER bleed`, 53 chars | `0.526h` | `w > 1.266h` | Clears 4:3 (`1.333h`) by `0.034h`. **Fails below about 5:4.** |

  **This is a stated constraint of the design, not merely a test threshold.** The shipped
  configuration is safe on any panel. Only a pathological rebind of *both* action buttons to
  bumper-length names, on a panel narrower than 5:4, collides — and no such display is a
  plausible booth panel. The test asserts the worst-rebind case at 4:3, so a future binding
  that closes that `0.034h` reddens the build instead of overlapping in front of a queue.

### Colour: not `cold`

`Hud.kt:180` `private val cold = Color(0.75f, 0.85f, 1f)` is a **shared, mutable** singleton —
`Color` has four mutable float fields, and `EnPustTil.kt:2003-2008` documents this trap.
"`cold` at reduced alpha", as revision 1 put it, is not implementable: it would mean mutating
the shared instance (silently re-tinting `BANKED`, the clock, the tape handle and every
graduation label), or allocating a `Color` per frame (forbidden). The legend therefore gets
its own **pre-allocated** `private val legendInk = Color(0.75f, 0.85f, 1f, authoredAlphaFor(…))`
beside `cold`. *(Review finding 6.)*

### Which branches draw it — four call sites, not three

`Hud.render` is called from **four** places: `PLAYING` (`:1848`), `PAUSED`-when-not-`pausedFromIdle`
(`:1858`), `RUN_OVER` (`:1864`) and `ENTER_INITIALS` (`:1870`). Revision 1 said three and
missed the paused-mid-run branch. *(Review finding 4.)*

**The legend is drawn in PLAYING and in PAUSED-from-a-run. Not in RUN_OVER, not in
ENTER_INITIALS.** The paused case is included deliberately: `drawPauseScreen`'s own comment
(`:1852-1855`) says a complete HUD behind the scrim — *"a stopped clock and a full ring of
bubbles"* — is the clearest statement that the run is being held, and a legend that vanished
on pause would contradict that. In RUN_OVER and ENTER_INITIALS the play-again and initials
hints are the relevant controls, and a swim/kick/bleed legend under them is noise.

It is called from the **dispatch**, as a second call beside `Hud.render`, not threaded through
`Hud.render` as a nullable parameter — that function already carries eight parameters, and
keeping the gating at the dispatch makes "these two states, not those two" visible at the one
place that knows the state.

---

## 7. `EPT_BRIEFING_HOLD` — a capture pin

The briefing auto-starts after 5 seconds. The verified way to photograph this game is the
shell route (`caffeinate`, `./gradlew run`, wait ~12 s for the window, focus it,
`screencapture`) — longer than the briefing lasts. Without a pin, **the screen being built
cannot be photographed**, and the verification loop this work is required to run becomes
impossible for exactly the task that most needs it.

`EPT_BRIEFING_HOLD=1` passes `briefingSeconds = Float.POSITIVE_INFINITY`, so the countdown
never expires and BRIEFING leaves only on a press. It joins `EPT_DEV`, `EPT_DEPTH`,
`EPT_SCREENSHOT` and `EPT_FAIL_BOOT` — one `getenv` at startup, unset at the booth. It is read
in `EnPustTil` and passed as a constructor argument, so `RunLifecycle` stays a pure object
with no environment reads.

**The countdown line must be suppressed, or the pin breaks the screen it exists to
photograph.** `timeInState >= Float.POSITIVE_INFINITY` is never true, so the transition is
safe — but `briefingCountdownSeconds` is then `Infinity`, and `Infinity.toInt()` (and
`ceil(…).toInt()`) is `Int.MAX_VALUE` in Kotlin. The pinned screen would read
**`STARTING IN 2147483647`**, a ten-digit number that also blows the centred layout width.
*(Review finding 3.)*

So `drawBriefingScreen` omits the countdown line entirely when `!lifecycle.briefingAutoStarts`.
`briefingSkippable` is unaffected, so the pinned screen still shows its skip hint and still
starts on a press. §8 pins both halves.

---

## 8. Testing

Every unit below asserts a **relationship that would actually break**, per the project's
convention that a test which cannot fail is worse than no test (commit `4493eeb` deleted
several that could not).

### `ControlHintsTest` (new) — the sweep of record for composed strings

- Every string in `ControlHints.all()` is inside the font atlas (`DefaultFont.canDraw`), at
  both `arcade` values.
- **Every `GamepadButton.entries` value produces a drawable, non-empty label.** This is what
  makes "derive from live config" safe: a technician can bind any of the ~20 names and none
  can vanish into the atlas gap.
- The `arcade = true` and `arcade = false` forms of each hint **differ** — catches a
  copy-paste that wires both branches to the same constant.
- A rebound button appears in the output: `kick(true, "RIGHT BUMPER")` contains
  `RIGHT BUMPER`. §1's defect, pinned.
- The keyboard forms do **not** vary with the passed gamepad label — they are compiled
  constants and must ignore it.
- Composed strings use `ScreenText.SEPARATOR`, not a raw double or triple space.

**This test, not `AttractScreenTest`, is what covers the composed strings.** They are built
at runtime from a config-dependent label and therefore can never appear in `ScreenText.all()`,
which is a static `List<String>`. Adding the fixed fragments to `ScreenText.all()` covers the
fragments only — not the composition, and not whether `SEPARATOR` survived the join.
*(Review finding 13; revision 1 claimed the existing sweep covered them.)*

### `RunLifecycleTest` (extended — one line changed, the rest added)

`newLifecycle()` gains `briefingSeconds = 0f`. Per §4.1 that makes all 34 existing tests pass
**unmodified**. New tests:

- **A zero-length briefing goes straight from IDLE to PLAYING**, with `justStarted` on that
  tick — the rule §4.1 relies on, pinned rather than incidental.
- A press in IDLE with a real `briefingSeconds` enters `BRIEFING`, **not** `PLAYING`, and
  `justStarted` is false while `justEnteredBriefing` is true.
- `justEnteredBriefing` is one-tick, cleared the next update.
- The briefing auto-starts after `briefingSeconds` with no input; `justStarted` fires exactly
  once.
- A press **before** `briefingDwellSeconds` does not skip; a press **after** it does.
- A **held** button does not skip the briefing (edge, not level).
- `simulationAdvances` is false in `BRIEFING`; `spriteAnimates` is true.
- `briefingCountdownSeconds` reaches 0, does not go negative, and **reads 0 outside BRIEFING**;
  `briefingSkippable` is false at entry, true after the dwell, and **false in IDLE after the
  dwell has notionally elapsed** — the state-blind guard from §4's accessor list.
- `briefingAutoStarts` is false for an infinite `briefingSeconds`, which then never auto-starts
  but still skips on a press — the `EPT_BRIEFING_HOLD` contract.
- **`RUN_OVER → PLAYING` still goes direct** — a retry does not enter `BRIEFING`. The owner's
  decision, pinned so a later "for consistency" change reddens.
- ESC during `BRIEFING` does not pause.

### `EnPustTil` pad-latch test (new, or extended `LifecycleInputEdgesTest`)

**The §4.2 blocker, pinned.** With two pads present and the *second* one pressing start, an
auto-started run must bind gameplay to the pad that pressed — not to slot 0. The assertion is
on the `firedPadId ?: activePadId` composition and `selectGameplayPad`'s result, which are
both pure and reachable without a GL context. This test is the reason the defect cannot come
back; it is invisible on a one-pad developer machine.

### `BriefingScreenTest` (new)

- Every `BriefingLayout` anchor is a screen fraction strictly in `(0, 1)`.
- No two elements' boxes overlap, using the `y .. y + fontSize` convention.
- **A WIDTH test at 4:3**, not a "fits on screen" test at three aspects. Every anchor is a
  fraction of height, so vertical fit is aspect-invariant *by construction* and a three-aspect
  vertical assertion cannot fail. The real risk is horizontal: the rule line, and the two-column
  rows at a long rebound label. The test uses an em-per-glyph estimate and cites
  `Hud.CLOCK_BOX_GLYPH_WIDTH_EM = 0.62f` as its source, the same way `AttractScreenTest:251-264`
  checks a span constant against `h * 4/3`. *(Review finding 8.)*
- The two control columns do not collide at the **widest** `GamepadButton` label.
- The countdown line is absent when `briefingAutoStarts` is false, and present when it is true.
- Every briefing string is drawable.

### `PauseScreenTest` (new)

The gap: `PauseLayout`'s KDoc (`EnPustTil.kt:737`) says its relationships are checked "see
`PauseScreenTest`" and **no such file exists** — `PauseLayout` is referenced by no test at
all. Anchors in `(0, 1)`; title / resume hint / exit hint / progress bar do not overlap; the
bar's fill is bounded by its track; `barFillWidth(0f)` is zero and `barFillWidth(1f)` is the
full track. The KDoc's claim becomes true rather than being deleted.

### `HudTest` (extended)

- **The legend's left edge clears the clock box's right edge at 4:3**, computed from
  `clockBoxWidth` and the longest legend string. This is the one legend relationship that can
  actually fail (§6 shows `0.034h` of clearance at 4:3), so it is the one asserted numerically.
- The legend's right edge is inside the screen and its box is above `TAPE_TOP_FRACTION`.
- **The legend cannot collide with HELD**, asserted against `Framing.DIVER_MIN_FRACTION` and
  `Hud.HELD_OFFSET_METRES` rather than by inspection — the derivation in §6 is exactly what
  revision 1 got wrong by asserting a corner was free.

### `AttractScreenTest` (extended)

`ScreenText.all()` grows to include the new **fixed fragments**. The existing sweep then
covers them with no change to the test's logic. Composed strings are `ControlHintsTest`'s job.

### Verification that is not a unit test

**Green tests are not evidence this game looks right** — a large share of this project's bug
history is invisible-in-tests rendering faults. Every task in the implementation plan ends
with a **real window grab** via the documented shell route, inspected before the task is
called done.

`EPT_SCREENSHOT` is **not** usable for this: `ScreenshotEffect.getTexture()` returns
`RenderTexture.BLANK`, so any surface it attaches to composites as blank, and it dumps each
surface separately so a hand-recomposite does not reproduce the engine's frame. Both facts
have cost this project a day each. The briefing, the legend and the pause screen are all on
the `"hud"` surface, and how they read **over live water** is the entire question, so the real
frame is the only valid evidence.

---

## 9. Files

**New**

- `src/main/kotlin/render/ControlHints.kt`
- `src/test/kotlin/render/ControlHintsTest.kt`
- `src/test/kotlin/render/BriefingScreenTest.kt`
- `src/test/kotlin/render/PauseScreenTest.kt`

**Modified**

- `src/main/kotlin/render/RunLifecycle.kt` — `BRIEFING`; `BRIEFING_SECONDS` and
  `BRIEFING_DWELL_SECONDS`; two constructor params; `justEnteredBriefing`, `briefingSkippable`,
  `briefingCountdownSeconds`, `briefingAutoStarts`; the zero-length rule; **all four**
  exhaustive `when`s (`:177`, `:199`, `:286`, and the dispatch below)
- `src/main/kotlin/EnPustTil.kt` — `ScreenText` additions and the three rewrites;
  `gamepadButtonLabel`; `rawJoystickPresent` extracted from `logGamepadDiagnostics`; the hint
  cache and its seeding in `createGame`; `BriefingLayout`; `drawBriefingScreen`; the dispatch
  branch (`:1844`); the `justEnteredBriefing` pad latch and the `?: activePadId` composition
  (`:1604`); `EPT_BRIEFING_HOLD`
- `src/main/kotlin/render/Hud.kt` — `renderControlLegend`, `legendInk`, layout helpers
- `src/test/kotlin/render/RunLifecycleTest.kt` — one line in `newLifecycle()`, **one test
  renamed** (`…everywhere except IDLE` → `…except IDLE and BRIEFING`, body unchanged), plus
  the new tests in §8
- `src/main/kotlin/render/RunLifecycle.kt` (again) — `spriteAnimates`' KDoc, which currently
  says "with IDLE added back in" and becomes "with IDLE and BRIEFING added back in"
- `src/test/kotlin/render/HudTest.kt`, `src/test/kotlin/AttractScreenTest.kt`
- `docs/superpowers/specs/2026-08-04-en-pust-til-design.md` — §5 gains the full shipped
  binding set (it currently documents only stick / A / B and has never been updated for the
  keyboard mirrors, ESC, Q-hold or the configurable map); §17 gains an amendment row
- `CLAUDE.md` — the sixth lifecycle state, `ControlHints` in the pure-logic list,
  `EPT_BRIEFING_HOLD` beside the other flags
- `docs/superpowers/specs/2026-08-11-outstanding-work.md` — §2.5's *work* marked done, naming
  the symbols that implement it, and its one open question (amber vs white held count)
  recorded as **accepted: keep amber**. A stale to-do for something already built is how the
  same work gets done twice.

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| The briefing delays every walk-up by up to 5 s, slowing the queue. | The skip press is live after 0.75 s, and a retry never re-briefs. If it is too slow at the booth, `BRIEFING_SECONDS` is one constant — and `0f` disables the briefing outright (§4.1). |
| The scrim makes the briefing look like the pause screen. | Different heading, different content, lighter alpha — and the alpha is chosen from a real grab, not from reasoning. |
| Hiding the leaderboard for 5 s per play costs the booth its social proof. | Accepted (§5). The board is on screen the whole time nobody is playing, which is most of the day. |
| The legend is clutter for the 90% of players who do not need it after ten seconds. | The owner chose permanent over fading explicitly. It is small, dim, and in the only corner provably free of every other element. Fading is a later change to one call site. |
| The 4:3 clearance between legend and clock is only `0.034h`. | Asserted numerically in `HudTest` against the longest possible rebound label, so a future binding that closes it reddens the build instead of overlapping at the booth. |
| An unmapped arcade encoder shows the wrong hint set. | Fixed in revision 2: presence includes raw GLFW joysticks, so an unmapped encoder shows **arcade** hints (§3). |
| Deriving labels from config lets a bad rebind put a long name on screen. | `BriefingScreenTest` and `HudTest` check the widest label; `ControlHintsTest` checks every entry is drawable. A name that is not a valid button falls back to the compiled default and warns, via the existing `gamepadButtonConfigWarning`. |
| `restartButtonAlt` is never shown. | Accepted and stated (§3), not overlooked. |

---

## 11. Implementation order

Each task is independently verifiable and ends with both green tests and a real window grab.

1. **`ControlHints` + its test.** Pure, no rendering, no lifecycle change. Nothing depends on
   it yet.
2. **`gamepadButtonLabel`, `rawJoystickPresent`, the hint cache and its seeding.** Rewire the
   three existing strings (`PRESS_START`, `PLAY_AGAIN`, `INITIALS_HELP`) to the cache. Grab:
   the attract, run-over and initials screens, with and without a pad connected.
3. **`RunLifecycle.BRIEFING`** — state, constants, accessors, the zero-length rule, the four
   `when`s, `justEnteredBriefing`. Tests only; nothing draws it yet.
4. **The pad latch** (§4.2) and its test. Small, and it must land with 3, not after.
5. **`BriefingLayout` + `drawBriefingScreen` + the dispatch branch + `EPT_BRIEFING_HOLD`.**
   Grab: the pinned briefing, over live water, at more than one aspect.
6. **The legend** — `legendInk`, `renderControlLegend`, both call sites. Grab: mid-run, deep,
   with a large HELD count, to confirm the geometry in §6 holds in the real frame.
7. **`PauseScreenTest`**, and the KDoc claim becomes true.
8. **Docs** — design spec §5 and §17, `CLAUDE.md`, outstanding-work §2.5.

---

## 12. What changed

### Revision 3 — self-check after the independent re-review was cut short

| Revision 2 said | The code says |
|---|---|
| `RunLifecycleTest` needs one line changed | It also needs the `spriteAnimates agrees … everywhere except IDLE` test **renamed**, because BRIEFING is a second disagreeing state. The body passes unchanged (it only reaches PLAYING and RUN_OVER), so this is a naming fix — but a test whose name is a lie is the exact failure mode this project keeps paying for. |
| Nothing about `spriteAnimates`' KDoc | It reads *"`simulationAdvances` with IDLE added back in"* and becomes *"IDLE and BRIEFING"*. The KDoc predicted this case verbatim. |
| HELD can never appear above `0.294h` | `0.294h` is the **16:9** figure quoted as universal. The offset is `8 / visibleDepth`, and visible depth is aspect-dependent, so the true loosest bound is `0.283h` (at 60 m). Conclusion unchanged; the number was not. |
| The legend/clock clearance is "thin at 4:3" | It is a **hard aspect constraint**: the longest possible rebind needs `w > 1.266h` and fails below about 5:4. The *default* labels need only `w > 0.868h` and are safe everywhere. §6 now states both bounds instead of one. |
| Nothing about the pad latch's edge cases | §4.2 now walks all five, including that a **keyboard** start leaves `firedPadId` null and falls to slot 0 — pre-existing behaviour, recorded so it is not later mistaken for a regression introduced here. |

### Revision 2 — after the adversarial review of revision 1

An adversarial review verified every claim in revision 1 against the code. Two blockers and
seventeen smaller findings. The corrections worth carrying forward, because each was a
confident statement that was simply false:

| Revision 1 said | The code says |
|---|---|
| `RunLifecycleTest` is "extended" | **29 of 34 tests would fail.** Fixed by the zero-length-briefing rule (§4.1), which reduces the change to one line. |
| "The `justStarted` block is untouched" | `firedPadId` is nulled every frame, so the auto-start path would bind gameplay to slot 0 — the exact documented "startable, unplayable run" booth failure (§4.2). |
| Two exhaustive `when`s | **Four**, including the two that carry the actual work. |
| `Hud.render` has three call sites | **Four.** The missed one is paused-mid-run, and the legend belongs in it. |
| `Hud.noReturnMark` is private | **Public**, and a different colour from the one quoted. |
| "`cold` at reduced alpha" | `cold` is a shared mutable singleton; that would re-tint five other HUD elements. |
| Bottom-left "is genuinely free" | HELD can reach `0.956h .. 1.05h` and well left of centre. Legend moved to top-right, which is provably clear. |
| "Fits at 4:3, 16:9 and 32:9" | Aspect-invariant by construction — a test that cannot fail. Replaced by a width test. |
| Presence = `engine.input.gamepads.isNotEmpty()` | Would print `PRESS SPACE` on a keyboardless cabinet with an unmapped encoder. Presence now includes raw joysticks. |
| `EPT_BRIEFING_HOLD` via `POSITIVE_INFINITY` | Safe for the transition, but renders `STARTING IN 2147483647`. Countdown line is now suppressed. |
| "The five composed strings" | The interface has four composites and five atoms. |
| Copy the leaderboard's alignment | The leaderboard aligns **outward**; the briefing needs **inward**. |
| `INITIALS_HELP` has a double space | Triple. |
| "All eight were taken by the owner" | Twelve. |
| `AttractScreenTest`'s sweep covers the new strings | It covers fixed fragments only; composed strings need `ControlHintsTest`. |

One revision-1 claim the review **confirmed** and is worth keeping: `engine.input.gamepads.isNotEmpty()`
allocates nothing, because `getGamepads()` returns a `java.util.List` and `isNotEmpty()`
resolves to the inline `Collection<T>.isNotEmpty()` — unlike the `firstOrNull { }` and
`forEach` cases that `gamepadIdBuffer` and `GamepadScan` document.
