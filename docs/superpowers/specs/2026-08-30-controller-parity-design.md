# Controller parity — design

*2026-08-30. Amends the locked design spec §5 (Controls). Read alongside
`docs/superpowers/specs/2026-08-04-en-pust-til-design.md`.*

## The ask

"Make the game playable entirely on a controller", plus a second question that turned out to
be the same subject from the other end: *a PS5 pad is connected and the screen still says
`A` / `X`.*

## What was already true

Tracing the whole input surface first, because most of it turned out not to need changing.
The complete keyboard read set is nine call sites:

| Site | Key | Gamepad equivalent today |
|---|---|---|
| `EnPustTil.kt:1758` | `SPACE` | `restartButton` / `restartButtonAlt`, any pad |
| `EnPustTil.kt:1797` | `ESCAPE` (pause) | **none** |
| `EnPustTil.kt:1798` | `Q` (exit hold) | **none** |
| `EnPustTil.kt:2573-2574` | arrow keys | `LEFT_X` / `LEFT_Y` |
| `EnPustTil.kt:2579-2580` | `Z` / `X` | `kickButton` / `bleedButton` |
| `EnPustTil.kt:2638-2639` | `UP` / `DOWN` | `LEFT_Y`, any pad |

So swim, kick, bleed, start, restart, confirm and initials entry were already fully
controller-reachable. The gaps are the two "none" rows, plus one that does not show up in
that table at all.

## The four changes

### 1. The D-pad is dead, and that is the load-bearing gap

`readInput` reads only `GamepadAxis.LEFT_X` / `LEFT_Y`; `readInitialsCycle` reads only
`LEFT_Y`. `GamepadButton.DPAD_UP` / `DOWN` / `LEFT` / `RIGHT` exist in the engine (verified
against `pulse-engine-0.13.0.jar`: `GamepadButton` declares all four) and are referenced
**nowhere** in this project.

On a console pad that is a missing convenience. At the booth it is a cabinet-killer: a
generic USB arcade encoder's joystick commonly enumerates as a hat rather than as analog
axes, and in that case the cabinet starts fine — `restartButton` is a button, and buttons
work — while the diver never moves. That is the same "startable, unplayable run" class of
failure `selectGameplayPad` exists to prevent, arriving through a different door, and it
would present at the booth as *the game is broken* with nothing in the log.

**Change.** Both readers gain the D-pad, with precedence analog → D-pad → keyboard. The
resolution is extracted into a pure `render/PadAxis.kt` per this project's
extract-the-relationship convention, so it is testable with no GL context and stays
allocation-free on `readInput`'s per-frame path.

```kotlin
object PadAxis
{
    /** The live source's own value: analog past its deadzone wins and passes through
     *  at FULL MAGNITUDE (it is proportional, and the swim model reads it that way),
     *  then the D-pad pair as -1/+1, then the keyboard axis. */
    fun resolve(analog: Float, negativePressed: Boolean, positivePressed: Boolean, keyboard: Float): Float
}
```

Both directions pressed at once resolves to `0f` — deterministically, rather than letting
one win by accident of ordering. A hat physically cannot report left and right together,
but a stuck contact on an encoder can, and a diver who swims sideways forever because of it
is exactly the booth failure this codebase keeps designing against.

### 2. Pause on the pad

New config key `pauseButton`, default `BACK` (Create on a DualSense, View on an Xbox pad).
Read on **every** connected gamepad, matching the "any button to start" rule
`LifecycleInputEdges`' class doc argues for.

**Change.** A second `LifecycleInputEdges` instance rather than a hand-rolled edge. That
reuses the machinery that already solves the two hard parts — per-`(padId, code)` sourcing
so one jammed button cannot mask a live one, and the stuck/chatter counters
`render/BoothStatus.kt` already puts on the status line. `ESCAPE` enters it through
`offerKeyboardEdge(engine.input.wasClicked(Key.ESCAPE))`, exactly as `SPACE` does, and
**not** by OR-ing a level into the pad levels: that collapse is the specific bug the comment
at `EnPustTil.kt:1698` was written about.

`RunLifecycle.update(pausePressed = ...)` re-edges whatever it is handed, so feeding it a
one-frame pulse instead of a level is safe — the same shape `InitialsEntry`'s `confirmPressed`
already receives.

### 3. Exit on the pad — a two-button hold

Exit stays reachable only from PAUSED and keeps `EXIT_HOLD_SECONDS` (1.5 s) of continuous
hold. On a pad it additionally requires **two buttons held together on the same pad**:
`exitButtonA` (default `LEFT_BUMPER`) and `exitButtonB` (default `RIGHT_BUMPER`), both new
config keys, neither bound to anything else in the game.

Two constraints produced this shape rather than a single button.

**The combination cannot include `pauseButton`.** Pause is edge-detected, and while PAUSED a
pause edge means *resume* — so pressing it to begin an exit hold would leave PAUSED before
the hold ever accumulated. This is not a preference; the obvious design does not function.

**One button would mean one stuck contact ends the day.** The original keyboard-only
decision (design spec §5, and the comment at `EnPustTil.kt:1771-1795`) rests on exactly that
risk, and the owner has accepted it in order to get pad parity. A two-button AND buys most
of the protection back for almost nothing: a phantom exit now needs a phantom pause edge,
*then* two simultaneous stuck contacts on one pad, held for a second and a half.

**`RunLifecycle` needs no change at all.** `RunLifecycle.kt:368` is
`exitHeldSeconds = if (exitHeld) exitHeldSeconds + dt else 0f`, so releasing either button
already resets the hold to zero. The AND composes at the call site:

```kotlin
val exitHeld = engine.input.isPressed(Key.Q) ||
               engine.input.gamepads.any { it.isPressed(exitButtonA) && it.isPressed(exitButtonB) }
```

The `any {}` here allocates an iterator, unlike `readInput`'s hot path. It is on the
per-frame update, so it is written as an indexed loop for the same reason
`gamepadIdBuffer`'s doc gives.

### 4. Controller-aware button labels

`ControlHints.labelFor` is `buttonName.replace('_', ' ')` on the enum name, and its KDoc
records this as an ACCEPTED LIMIT on the grounds that there is no source for the physical
legend. **There is one.** `InputImpl` filters `glfwJoystickPresent(i) && glfwJoystickIsGamepad(i)`
and constructs `Gamepad(i)`, so `Gamepad.id` *is* the raw GLFW joystick index (verified from
bytecode) and `glfwGetGamepadName(pad.id)` is directly callable — this file already calls
GLFW directly in `logGamepadDiagnostics`.

Measured on the owner's machine: `glfwGetJoystickName(0)` returns
`'DualSense Wireless Controller'`, gamepad-mapped at id 0.

Note what this is and is not. On a DualSense, `GamepadButton.A` and `CROSS` share code 0, so
`kickButton = A` **already binds the physical Cross button**. Nothing is misbound; only the
printed string is wrong. This is a labelling change end to end.

**Change.** A pure, engine-free `render/ControllerFamily.kt`:

```kotlin
enum class ControllerFamily { PLAYSTATION, XBOX, GENERIC }

/** Case-insensitive substring match on the device name; GENERIC for anything unrecognised. */
fun familyFor(deviceName: String?): ControllerFamily
```

matching `dualsense`, `dualshock`, `playstation`, `ps3`, `ps4`, `ps5`, `sony` →
`PLAYSTATION`; `xbox`, `xinput` → `XBOX`; everything else → `GENERIC`. `ControlHints` then
renders per family:

| Enum name | GENERIC | PLAYSTATION | XBOX |
|---|---|---|---|
| `A` | `A` | `CROSS` | `A` |
| `B` | `B` | `CIRCLE` | `B` |
| `X` | `X` | `SQUARE` | `X` |
| `Y` | `Y` | `TRIANGLE` | `Y` |
| `START` | `START` | `OPTIONS` | `MENU` |
| `BACK` | `BACK` | `CREATE` | `VIEW` |
| everything else | unchanged | unchanged | unchanged |

**The font constraint decides the vocabulary.** The default font draws only
U+0020..U+011F, and anything above renders as *nothing at all* — no glyph, no x-advance,
silently. The actual PlayStation glyphs ✕ ○ □ △ would therefore vanish. Labels stay ASCII
words. `ControlHints.all()` grows to cover the new strings so the font sweep enforces this
rather than trusting it.

*Corrected during implementation:* an earlier draft of this section said `AttractScreenTest`
performs that sweep. It does not — `AttractScreenTest` sweeps `ScreenText.all()`, and the
font check over control hints is `ControlHintsTest.every control hint is inside the default
font's baked atlas`. Extending `all()` is still exactly what makes the constraint enforced;
the enforcing test is simply the other one. The distinction matters to anyone who later adds
a label and goes looking for the test that should have caught it.

**A generic encoder is deliberately untouched.** `GENERIC` keeps today's behaviour exactly,
so the booth cabinet's screens do not change.

**Where the name is read.** Inside `refreshControlHints` only — `glfwGetGamepadName`
allocates a `String`, and that function already exists to rebuild the hint cache only when
the hardware changed. The family is cached alongside `arcadeHints` and re-derived when the
detected identity changes. Which pad decides: the active gameplay pad (`activePadId`) when
one is latched, otherwise the lowest-id connected pad.

## Config surface added

Three keys, all following `parseGamepadButton` / `gamepadButtonConfigWarning`'s existing
shape (unknown name → compiled default, logged at WARN so a typo is never silent):

```
# pauseButton   = BACK
# exitButtonA   = LEFT_BUMPER
# exitButtonB   = RIGHT_BUMPER
```

`gamepadButtonCollisionWarnings` grows two checks, and only two — its class doc is explicit
that most collisions among the existing four are safe or deliberate, and a false alarm on
the booth status line is worse than no alarm:

- `exitButtonA.code == exitButtonB.code` — the AND collapses to a single button, silently
  undoing the whole protection in change 3.
- `pauseButton.code` equal to `kickButton.code` or `bleedButton.code` — a player's kick
  would open the pause screen mid-run.

## Testing

- `PadAxisTest` — source precedence, deadzone, opposing-directions-cancel.
- `ControllerFamilyTest` — including the literal measured string
  `"DualSense Wireless Controller"`, case-insensitivity, and unknown → `GENERIC`.
- `ControlHintsTest` — the per-family label table, and every new string reachable from
  `all()` for the font sweep.
- `EnPustTilGamepadConfigTest` — parse, warning and collision cases for the three new keys.
- `RunLifecycleTest` — unchanged behaviour is the assertion here: a pad exit is just
  `exitHeld`, and release-resets-the-hold is already covered.
- Source-scan tests in the style of `MainCameraOwnershipTest` are **not** added; there is no
  ownership invariant here worth pinning.

Green tests are not the finish line on this project. Verification ends with a real window
grab through the documented in-water sequence in `CLAUDE.md`, with the DualSense connected,
confirming the D-pad moves the diver and the legend reads `CROSS kick`.

## Documentation

- Design spec §5 binding table, and a §17 amendment-log entry recording that pause and exit
  on the pad **override** the original reasoning, with the two-button hold as what replaces
  the protection that reasoning provided.
- `application.cfg` BUTTON MAP comment: the three new keys.
- `CLAUDE.md` only if the platform-constraints list gains something; the D-pad finding is a
  candidate.

## Explicitly not in scope

- Rebinding anything. Change 4 is labels only.
- Glyph rendering for PlayStation face buttons — blocked by the font range, above.
- A pause *menu*. Pause remains the existing hold screen; only its input source widens.
