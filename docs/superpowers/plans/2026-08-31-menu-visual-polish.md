# Menu visual polish — implementation plan

**Spec:** `docs/superpowers/specs/2026-08-31-menu-visual-polish-design.md` (read it first; it
carries the measurements and the two CRITICAL defects).
**Baseline:** 941 tests, 89 classes, 0 failures. Any red test after a task is ours.
**Stack:** Kotlin 2.2.20, Pulse Engine 0.13.0, `kotlin.test` on JUnit Platform, Gradle.

## Global constraints — apply to EVERY task

- **Never `drawQuad`/`drawLine`.** `Draw.fillRect`/`fillRectCentred` only. `DrawTest` scans all
  production sources and fails the build.
- **Every semi-transparent value on the `"hud"` surface goes through `Hud.authoredAlphaFor()`.**
  Authored 0.15 displays near 0.02.
- **No per-frame allocation.** Use `Draw.kt:187-206`'s raw-float `drawTextWithOutline` overload
  rather than constructing a `Color`. No `forEach`/`any`/`count` on collections in a draw path.
- **Sizes are fractions of screen HEIGHT.** Prefer `surface.config.width/height` over
  `engine.window.*`. Vertical anchors are the TOP of the text box.
- **Every drawn string goes through `ScreenText` or `ControlHints`, and into its `all()`.** Font
  ceiling U+011F; an out-of-range char contributes no glyph *and no x-advance*, silently.
- **Allman braces, 4 spaces, no wildcard imports.** Comments explain *why*, at length, citing
  evidence — match the surrounding density.
- **A test that cannot fail is worse than no test.** Assert the relationship that would break.
- **Green tests are not evidence the game looks right.** Tasks marked **[CAPTURE]** end with a real
  window grab.

## The capture procedure

`EPT_SCREENSHOT` is NOT this — it is not passive and its output is not the frame. Use the harness
already proven in this work:

```bash
caffeinate -d -u -t 1800 &
(./gradlew run > /tmp/ept-run.log 2>&1 &)
until pgrep -f EnPustTilKt >/dev/null; do sleep 2; done
python3 -c "import time;time.sleep(12)"     # asset upload settles
# keys: System Events is SILENTLY DROPPED by this window. Quartz only.
python3 - <<'PY'
import Quartz, time
CODES={"space":49,"esc":53,"up":126,"down":125,"left":123,"right":124,"q":12}
def tap(n,h=0.06):
    Quartz.CGEventPost(Quartz.kCGHIDEventTap,Quartz.CGEventCreateKeyboardEvent(None,CODES[n],True))
    time.sleep(h)
    Quartz.CGEventPost(Quartz.kCGHIDEventTap,Quartz.CGEventCreateKeyboardEvent(None,CODES[n],False))
tap("down"); time.sleep(0.4); tap("space")
PY
osascript -e 'tell application "System Events" to set frontmost of (first application process whose name is "java") to true'
sleep 0.7 ; screencapture -x -o /tmp/shot.png
pkill -9 -f EnPustTilKt ; pkill -f caffeinate
```

**Validate every capture actually contains the game.** A macOS lock-screen grab looks like a
success. Do not set `EPT_DEV=1` — it draws overlays over the frame.

Reference frames (the "before" set) are in the session scratchpad under `menu-shots/`.

---

## Phase 1 — The shared visual system

The highest-value, lowest-risk work. Everything else sits on it.

### Task 1: MAIN_MENU gets the scrim PAUSED already has **[CAPTURE]**

The root cause. Shots 01/02 and 06 have a byte-identical panel; only the scrim differs.

- [ ] In `EnPustTil.renderGame`'s `RunLifecycleState.MAIN_MENU ->` branch (`EnPustTil.kt:3144`),
      issue a full-screen scrim before `drawMainMenu`, exactly as the PAUSED branch does at
      `:3208-3209`: `hud.setDrawColor(0f, 0f, 0f, Hud.authoredAlphaFor(<alpha>))` then
      `hud.fillRect(0f, 0f, w, h)`.
- [ ] Do **not** change `PanelLayout.ALPHA`. Re-capture first.
- [ ] Comment must state why: the plate is a darkener over the sunset and an additive lift over the
      abyss, so the backdrop must be equalised rather than the plate retuned.
- [ ] **[CAPTURE]** main menu and graphics page. Compare against `01`/`02`. The maroon cast must be
      gone and the diver must no longer read through the panel.
- [ ] Check `PauseMenuWiringTest` still passes — the PAUSED branch is scanned, MAIN_MENU is not.

**Verify:** `./gradlew test` green; the new frames look like `06`'s panel, not `01`'s.

### Task 2: Panels get a border **[CAPTURE]**

Measured on 01: a hard step from (233,99,83) to (179,75,63). No stroke.

- [ ] Add a border to `Hud.renderPanel` (`Hud.kt:786`) — a slightly larger pale slab drawn beneath
      the plate is the cheapest form, and `Hud.tapeShadow` already uses that trick in reverse.
- [ ] Colour is a pre-allocated field beside `panelPlate`, alpha through `authoredAlphaFor`.
- [ ] `renderPanel` is shared by seven screens; a border must look right on all of them. Capture
      the attract board and the briefing as well as the main menu.
- [ ] **[CAPTURE]** main menu, attract, briefing.

### Task 3: Invert the selection bar and add ink hierarchy **[CAPTURE]**

White-on-bar measures **3.46:1** (sunset) and **3.54:1** (deep) — the lowest-contrast text on both
screens. Do **not** raise the bar's brightness; that makes it worse.

- [ ] In `drawMainMenu` (`EnPustTil.kt:3434-3438`), replace the `0.18f` white bar with a dark or
      accent-tinted bar, keeping the selected row's text full white.
- [ ] Unselected rows draw at ~0.72 white via the raw-float overload — **no `Color` construction**.
- [ ] **No leading-edge accent** (measured 160px from the nearest glyph on a centred menu). If a
      mark is wanted, use a symmetric pair at both ends of the bar, or a full-width top/bottom rule.
- [ ] Comment must record the measured ratios, so nobody "tidies" the bar brighter again.
- [ ] **[CAPTURE]** main menu and graphics page; the selected row must be the *most* legible row,
      not the least.

### Task 4: Widen `HIGHLIGHT_HALF_SPAN` and assert it contains the widest row

Real defect: the bar spans x=720..1199 but `OFF-SCREEN RAYS` starts at **x=710**.

- [ ] Widen `MenuLayout.HIGHLIGHT_HALF_SPAN`. Caps: `< 0.533333` (`MenuLayoutTest:112-115`) and
      `< 0.641667` (`PanelLayoutTest:263-288`).
- [ ] **New assertion in `MenuLayoutTest`:** the highlight contains the widest row's rendered width
      at EM = 0.62, for every page. This is the assertion whose absence let the defect through.
- [ ] If the half-span becomes per-page, expect **7 compile breaks** across `MenuLayoutTest:110,129,147`
      and `PanelLayoutTest:79,81,94,95,268`. `PanelLayoutTest:268`'s on-screen map must gain the new
      page's value or that panel edge is unchecked at 4:3.

---

## Phase 2 — Layout

> **Three tasks added 2026-08-31 after inspecting the Phase 1 output captures.** Task 5a closes a
> gap in this plan — spec §2.3 had no task number. Tasks 6a and 7a are defects the Phase 1 frames
> revealed that the before-set did not.

### Task 5a: Page titles (spec §2.3 — MISSING from the original plan)

- [ ] The graphics page is headed `ONE MORE BREATH`, which says nothing about where you are.
      ROOT-no-run → `ScreenText.TITLE`; ROOT-run-held → `ScreenText.PAUSED_TITLE`; GRAPHICS →
      `ScreenText.MENU_GRAPHICS`. All strings exist, so no new atlas risk. Leave room for
      LEADERBOARD (Phase 5).

### Task 6a: The hint legend overlaps the panel border

- [ ] Verified in `after-phase1-graphics.png`: the legend sits ON the bottom border, ~1px clear,
      and reads as attached to the card. **This makes Task 6 more urgent than the 570px-gap framing
      suggested.** The gap must clear the border ring's OUTER edge, on both pages.

### Task 7a: The diver still reads through the 14-row panel

- [ ] Visible behind five rows in `after-phase1-graphics.png`. Phase 1 cut transmission 55% → 31%,
      not enough for a tall panel sitting dead-centre over the diver.
- [ ] **Do NOT raise `PanelLayout.ALPHA`** — shared by seven screens including the unscrimmed
      attract board, ceiling 0.920960.
- [ ] Give `renderPanel` an optional plate-alpha override, used only by the main-menu panel.
      **Its KDoc says the plate is deliberately not per-screen; rewrite that KDoc in the same commit
      and say why the settings list is the exception.** Choose the value from a capture.

### Task 5: `TITLE_Y` conditional, and kill the attract→menu title pop **[CAPTURE]**

The capsule occupies **0.020h..0.100h**; `PAUSED` at 0.10 occupies **0.100h..0.160h** — a
zero-pixel gap. But the attract title's cap-height is **64px** against the menu's **42px**, a 1.52x
pop on one press, and moving `TITLE_Y` unconditionally doubles the drop.

- [ ] ROOT with no run held: match `AttractLayout.TITLE_Y` / `TITLE_FONT` exactly.
- [ ] Run held: `TITLE_Y = 0.135f`. **0.135 is the maximum with no side effect** —
      `minTop = h·(TITLE_Y + TITLE_FONT) = 0.195` equals the padded content top exactly; above it
      the panel clamps into its own padding.
- [ ] `MenuLayoutTest`: `0.135 + 0.06 = 0.195 <= rowY(0) = 0.22` ✓.
- [ ] **[CAPTURE]** attract, then main menu, then pause-during-a-run. The title must not jump
      between the first two, and must clear the capsule in the third.

### Task 6: `HINT_Y` tracks `rowsBottom()`

570px between panel and legend on ROOT; 16px on GRAPHICS. Same constant, two compositions.

- [ ] `HINT_Y` becomes `rowsBottom(n) + a fixed gap`, clamped so `HINT_Y + HINT_FONT < 1f`.
- [ ] Keep `MenuLayoutTest`'s existing fit assertions; they now bind on the computed value.

### Task 7: Graphics page — left-align both columns, group with whitespace **[CAPTURE]**

Measured: label left edges range **710→856** (146px ragged); 13px padding left, **118px dead panel
right**. Widening the panel makes the dead margin bigger, which is why the first draft was wrong.

- [ ] Labels **and** values left-aligned at fixed columns derived from the panel's inner edges, not
      from `centreX ± COLUMN_GAP`.
- [ ] **Override `MenuLayout`'s class doc in the same commit.** It justifies the inward split as
      copied from `BriefingLayout`; that justification does not scale from 3 rows to 14 and the
      replacement must say so. The briefing keeps its inward split (Task 12).
- [ ] `rowY` gains a **gap parameter** — one gap after `QUALITY`, one between the GI block
      (`LIGHT MAP SCALE`…`OFF-SCREEN RAYS`) and the display block (`RESOLUTION`…`SHOW FPS`).
      Keying on `rowCount == 14` is the silent-staleness trap `rowScaleFor`'s KDoc exists to prevent.
- [ ] **`baseHeight` must learn the gaps too.** With them: `rowScaleFor(14) = 0.843216`,
      `rowsBottom(14) = 0.879920 < 0.90` ✓. Without: `0.918857 > 0.90` and the fit assertion fails
      loudly — which is correct.
- [ ] `BACK` stays centred.
- [ ] **[CAPTURE]** graphics page; the label column must have one fixed left edge.

---

## Phase 3 — Score screens

### Task 8: Extract `RunOverLayout` and `InitialsLayout`

- [ ] Two engine-free layout objects in the style of `PauseLayout`/`BriefingLayout`, replacing the
      inline `val`s at `EnPustTil.kt:3709-3712` and `:3964-3970`.
- [ ] Replace `PanelLayoutTest`'s hand-copied `RunOverAnchors` (`:44-50`) and `InitialsAnchors`
      (`:51-59`) with the real objects — the duplication is the point of the task.
- [ ] New `ScoreScreenTest` in the style of `PauseScreenTest`: anchors are fractions, no two
      elements overlap, content fits its panel.

### Task 9: Run-over — scrim, then move, then enlarge **[CAPTURE]**

Order matters. The message currently lands across the diver's fins inside the pearl ring, and
`BANKED 10` is already on screen twice.

- [ ] Scrim as the **first** statement of `drawRunOverScreen` (draw order confirmed at
      `EnPustTil.kt:3214-3226`, so it dims the already-submitted HUD and vent labels).
- [ ] **Pick the weight from a capture.** At 0.55 the HUD underneath is multiplied by 0.2584 — the
      red `0:00` capsule, the best state signal in the game, loses ~74% of its weight. Probably too
      much.
- [ ] Move the block clear of the diver, or panel it.
- [ ] Then enlarge the score numeral; drop the middle-dot separator.
- [ ] Shrink the hint pill: 540px around a 182px string.
- [ ] `PanelLayoutTest:372-381` re-derives `RUN_OVER_HALF_SPAN` against the worst-case string —
      it moves when the string does.
- [ ] **[CAPTURE]** run-over.

### Task 10: Initials — real slot rects **[CAPTURE]**

- [ ] Replace `ScreenText.initialsSlots` with per-slot geometry in `InitialsLayout` plus a per-slot
      accessor. **This is a compile break** at `PanelLayoutTest:388` and in `ScreenText.all()`
      (`EnPustTil.kt:1054-1055`) — replace `:388`'s subject with a per-slot width bound, do not
      delete the assertion.
- [ ] Three slot rects, evenly pitched, active slot carrying Task 3's treatment.
- [ ] Shrink the panel: measured 828px around 216px of content.
- [ ] Scrim as Task 9.
- [ ] **[CAPTURE]** initials.

---

## Phase 4 — Briefing

### Task 11: Make the briefing panel actually visible **[CAPTURE] — BLOCKS Task 12**

Measured inside vs outside at y=760: `(6,48,92)` vs `(4,47,91)`. **The panel does not exist on
screen.**

- [ ] With Tasks 1–2 landed, capture the briefing (`EPT_BRIEFING_HOLD=1`) and confirm the panel is
      visibly a card.
- [ ] **Do not proceed to Task 12 until this capture exists.** Relaxing `BriefingScreenTest`'s halo
      rule in exchange for an invisible panel trades a real constraint for nothing.

### Task 12: Relax the halo constraint to a disjunction

- [ ] Drop the halo term from `drawBriefingScreen`'s `minTop`
      (`EnPustTil.kt:3897-3900`, currently `maxOf(titleBottom, halo)`). **Without this the new
      disjunction's third arm is dead code** — the panel is clamped out of the halo band by
      construction, so nothing inside it can ever be in the band.
- [ ] `BriefingScreenTest:117`: `clearsAbove || clearsBelow || insidePanel`, with the panel extent
      computed from `PanelLayout.bounds` (pure, headless — `PanelLayoutTest:148-179` already does
      exactly this).
- [ ] **`PanelLayoutTest:319-344` must be deleted or inverted** — it asserts the briefing panel top
      never rises into the halo band, which is precisely the constraint being removed. Its own
      comment records that it was added voluntarily, so removing it is legitimate; write the
      reasoning down.

### Task 13: Three facts, a distinct block, and a longer dwell **[CAPTURE]**

- [ ] Three `ScreenText` constants, added to `ScreenText.all()`.
- [ ] **`"CARRIED PEARLS MAKE YOU HEAVIER AND SLOWER"` (42 chars) DOES NOT FIT** —
      `2·0.32/(0.62·0.026) = 39` chars max, giving 0.339h against `BRIEFING_HALF_SPAN = 0.32`.
      Either shorten (`"PEARLS MAKE YOU HEAVY AND SLOW"`, 30 → 0.242h ✓) or raise the half-span to
      0.34 (inside `PanelLayoutTest:263-288`'s cap).
- [ ] Facts are a **visually distinct block** at a smaller font below the controls — not six flat
      rows. Controls keep their **inward** alignment (the opposite of the leaderboard's; copying the
      leaderboard gets it backwards).
- [ ] Vertical: band 0.170..0.760 = 0.590h, content ≈0.293h. **The risk is ~0.30h of empty panel**,
      the exact defect `rowsBottom`'s KDoc records — spread the block or pull `RULE_Y` up.
- [ ] Extend `BRIEFING_SECONDS` from 5f. Nine elements at 5s is 0.55s each.
- [ ] Grow `BriefingScreenTest`'s overlap, width and drawability sweeps to the new rows.
- [ ] **[CAPTURE]** briefing without `EPT_BRIEFING_HOLD` so the countdown row is present — the
      original evidence frame was missing it.

### Task 14: Suppress the in-run legend while a menu is open

Shot 06's top-right reads `STICK swim · B kick · A bleed` while a PLUS/MINUS menu is open.

- [ ] Gate `Hud.renderControlLegend` on the menu not being open.
- [ ] Reconcile the briefing's `bleed pearls` with the legend's `bleed` — two strings, one control.

---

## Phase 5 — The LEADERBOARD page

**Both CRITICAL defects live here. Neither is optional.**

### Task 15: The page

- [ ] `MenuPage.LEADERBOARD`; `MenuItemId.DELETE_BOARD`; rows `[DELETE_BOARD, BACK]`. The board is
      display, not rows.
- [ ] Expect exactly **three compile breaks**, all loud and all correct: `MenuModel.kt:106`
      (`itemsOn`), `EnPustTil.kt:3478` (`menuItemLabel`), `EnPustTil.kt:4422` (`applyMenuAction`).
- [ ] **CRITICAL — `MenuModel.update:242` must become `page != MenuPage.ROOT`.** As written,
      `if (page == MenuPage.GRAPHICS) … else CloseMenu` means back on the leaderboard page emits
      `CloseMenu` → `lifecycle.resumeRun()`, **dropping a paused player into the water**. Assert it.
- [ ] `MenuLayout.rowY` takes the row block's top (default `ROWS_TOP_Y`, so existing sites compile).
- [ ] `MenuModelTest:218` → `MenuPage.entries.flatMap { itemsOn(it) }` so it self-maintains.
- [ ] `MenuLayoutTest:24-25`'s hand-maintained `longestPage`/`shortestPage` need the 2-row page.
      **`:22`'s comment claims `MenuModelTest` has a row-count drift guard; it does not.** Fix the
      comment or add the guard.
- [ ] **Design the empty board.** `drawLeaderboard` returns early when empty, so a freshly-wiped
      page would show a heading and nothing else.
- [ ] Note: `topN` does `ranked.take(n)`, allocating per frame. `drawLeaderboard` already does this
      on every attract frame, so it is precedent, not a new sin — but say so in a comment.

### Task 16: Hold to delete

- [ ] `MenuModel.update` gains `dt: Float = 0f`. Four call sites; three test lines
      (`MenuModelTest:24,26,181`). Feed it `engine.data.deltaTime` — `updateMainMenu` runs on the
      render clock, same as `lifecycle.update` on the next line.
- [ ] **CRITICAL — the hold must require a fresh confirm edge after the page opened.** Confirming
      `LEADERBOARD` sets `selectedIndex = 0`, and `DELETE_BOARD` **is row 0**; `prime` is called
      only on menu entry, never on a page switch. A 5–10 frame human press, or anyone leaning on the
      button, would otherwise clear the threshold and **wipe the board**. Latch `wasConfirm` on the
      page switch, or require a release first.
- [ ] Accumulate only while page is LEADERBOARD, row is `DELETE_BOARD`, confirm held. Reset on
      release, navigation, or page exit. Emit `MenuAction.DeleteBoard` once, then reset.
- [ ] A confirm *edge* on `DELETE_BOARD` does nothing — `MenuModel.kt:236`'s `else -> None` already
      gives this.
- [ ] Progress bar reusing Task 19's track rule.
- [ ] **Tests:** the page-opening press cannot fire it; a tap is inert; the hold resets on
      navigation; it fires exactly once.

### Task 17: Clear-by-seed, with a backup that actually runs

- [ ] `ScoreRepository` gains clear-by-seed (default `todaySeed`): drop matching entries, clear
      `rankedCache` wholesale (as `registerScore` and `loadInto` do), then `saveAndPromote`
      (synchronous, no timers).
- [ ] **The obvious backup call is a no-op.** `maybeRollBackup` early-returns unless 30 minutes have
      passed, and `lastBackupTimeMs` is set **at construction** — so for the first 30 minutes of
      every session it writes nothing. It also uses `saveAsync(…) { }`, whose completion fires
      **only on success**, so failures are silent.
- [ ] Therefore: extract an unconditional `writeBackup(store)` from its body, use
      `ScoreStore.saveSync` (returns `Boolean`), and **refuse to wipe if it returns false**.
- [ ] Only today's seed is cleared — the delete must not be able to destroy day one.
- [ ] Tests, modelled on `ScoreRepositoryTest:285`: today's gone, **another seed's survive**, a
      backup file exists afterwards, and a failed backup aborts the wipe.

### Task 18: Wire the footer legend

- [ ] New `ControlHints.select(...)` — **do not edit `pressStart`**, which is shared with the
      attract screen's sign (`EnPustTil.kt:3550` and `:4198`).
- [ ] Add it to `ControlHints.all()` **and** to `ControlHintsTest:245-259`'s hand-written list.
      Both are manual; a composite missing from either **ships unswept by the font-atlas test** —
      a silent hole, not a red test.
- [ ] `PauseMenuWiringTest:123-128` pins the legend expression verbatim and needs a third branch.
      Change the scan deliberately; never delete it.
- [ ] `ControlHintsTest:277-279`'s pinned literals do **not** move — `goBack` stays byte-identical.

---

## Phase 6 — Cabinet menu (droppable, D6)

### Task 19: Two one-line fixes

- [ ] `drawIdleScreen` gains a flag so the leaderboard is not drawn under the cabinet menu. Touches
      **no** `PauseMenuWiringTest` scanned string.
- [ ] Draw the exit-hold track only while holding. **This reverses a documented decision** —
      `EnPustTil.kt:3832-3836` says the empty track is what tells a technician the control wants
      holding rather than pressing. Engage that argument in the replacement comment; do not
      silently overwrite it. `PauseScreenTest` is unaffected.

---

## Phase 7 — Close out

### Task 19a: ~~The diver's torch beam shines through the score cards~~ — DROPPED BY OWNER

**Owner, 2026-08-31: "I don't care about the things showing through, I just want it finished."**
Not a defect to re-open. Recorded so a future reader does not mistake it for an oversight.

The observation was real: a vertical light streak runs through the `58` on run-over and through the
middle initials slot in `after-phase34-{run-over,initials}.png`. It is a translucency effect, not a
position one — the Phase 3/4 agent established that no y-anchor clears both the surface case (where
`DiveCamera` clamps and the diver renders lower) and the deep case. The fix, if ever wanted, is to
apply Task 7a's `renderPanel` plate-alpha override to those two cards. It is a two-line change.

### Task 19b: ~~`newScore` is inconsistent with run-over~~ — DROPPED BY OWNER

Run-over reads `RUN OVER` / `58` / `PEARLS BANKED` with the middle dot dropped (Task 9); initials
still reads `NEW SCORE  ·  BANKED 58`. Cosmetic inconsistency, deliberately left.

### Task 20: Full-lifecycle capture regression **[CAPTURE]**

- [ ] Re-capture all nine states with the same harness and compare against the before set.
- [ ] Confirm no screen regressed, especially shot 06 (the control) and the attract board.

### Task 21: Record the amendments

- [ ] `§17` row in `2026-08-04-en-pust-til-design.md`: briefing content extended past "controls
      plus one rule" (D3), and why.
- [ ] Note in `2026-08-22-ui-clarity-and-briefing-design.md` that its briefing-content exclusion is
      superseded.
- [ ] Update `CLAUDE.md`'s `render/` paragraph for the new page, and its statement that
      `MenuItemId.LEADERBOARD` drops to attract mode.

---

## Sequencing

Phase 1 first and alone — Tasks 1–3 change how every later capture looks, so doing them last would
invalidate every judgement made before them. Task 11 **blocks** Task 12. Phase 5's two CRITICAL
items are independent of the visual work and can run in parallel with Phases 2–4. Phase 6 is
droppable.

## Deliberately not doing

- The initials 15-second auto-submit countdown (behaviour change; **flagged for the owner** — a
  player pausing to think loses their entry with no warning).
- A back step in initials entry.
- Moving the attract rank column — `AttractScreenTest:210-233` asserts
  `(rankLeftEdge + scoreRightEdge)/2 == centreX` and the constant is shared with `scoreX` and the
  panel width.
- Raising `PanelLayout.ALPHA` (Task 1 removes the reason to).
- The four 2026-08-23 Cut List items, which failed verification.
- `PanelLayoutTest:75`'s pre-existing drift — it re-derives `contentBottom` from
  `HINT_Y + HINT_FONT` while production passes `rowsBottom(items.size)`. Not ours, but it means
  Task 7 is **not** covered there.
