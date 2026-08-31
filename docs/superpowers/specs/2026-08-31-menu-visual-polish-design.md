# Menu visual polish, briefing content and leaderboard management — design

**Date:** 2026-08-31
**Status:** verified — revised after three independent verification passes
**Baseline:** `./gradlew test --rerun-tasks` → 941 tests, 89 classes, 0 failures.

> **Revision note.** The first draft of this document named panel opacity as the root cause of the
> menus looking bad. **That was wrong**, and it is corrected in §2.1. Pixel measurements off the
> capture set showed the panel is byte-identical on the best- and worst-looking screens; the
> variable is a scrim that one branch draws and another does not. The first draft also proposed a
> fix that would have *damaged* the frame it named as its own control. Both verification passes
> that caught this are cited inline. Two critical safety defects found during verification are in
> §4.1 and §4.2 — either would have shipped.

---

## 0. Why this exists

Nine real window grabs of every non-gameplay state were captured on 2026-08-31 (fullscreen,
1920x1200; `settings.json` had `FULLSCREEN ON`, so `application-dev.cfg`'s windowed override did
not apply). They are the evidence for everything below.

The structure of the menus is **not** in question. The owner likes it. This is a presentation
pass, plus two content additions the owner asked for.

**One capture is not trustworthy as evidence.** Shot 03 was taken under `EPT_BRIEFING_HOLD=1`,
which suppresses the countdown line. The real briefing has one more row than that frame shows —
nine elements, not eight. Any layout reasoning must use nine.

---

## 1. Decisions taken

| # | Decision | Taken by |
|---|---|---|
| D1 | `LEADERBOARD` becomes a real `MenuPage`, showing the board plus `DELETE BOARD` and `BACK`. | Owner |
| D2 | Deleting the board is **hold-to-confirm**. A tap does nothing. | Owner |
| D3 | The briefing gains three facts: carried pearls make you heavier and slower; deeper pearls are worth more; the bubbles are your air. | Owner |
| D4 | Visual scope is *fix the fundamentals* — no new visual language, no adoption of the HUD's capsule/ink idiom. | Owner |
| D5 | **The anglerfish is not mentioned anywhere**, because it is being removed. | Owner |
| D6 | The cabinet menu is barely used; it is lowest priority and droppable. | Owner |

### Three things the owner should know, which do not change the decisions

**The two-day problem D1 addresses is already solved.** `ScoreRepository.topN` filters
`entries.filter { it.seed == seed }` against `todaySeed`, and every `ScoreEntry` carries its seed
(`score/ScoreEntry.kt:16`). Changing `dailySeed` and restarting already yields a clean day-two
board with day one intact. The delete button is a **convenience** — it removes the need to edit a
config file at the stand — not a correctness fix.

**D3 amends a locked decision.** `2026-08-22-ui-clarity-and-briefing-design.md` §2 locks briefing
content at "controls plus the one core rule" and names air as the **first** of three deliberate
exclusions, because everything on that screen costs reading time in front of a queue. The owner
has overridden this. The queue no longer exists (CLAUDE.md, 2026-08-30), so the reasoning no
longer applies with the same force. A §17 amendment row records it.

**Nine elements at five seconds is 0.55s per element, and nobody reads that.** The design critique
recommended cutting the weight fact. The owner chose all three, so all three stay — but the
briefing dwell is extended (§5), because adding content without paying for the time is how the
screen becomes unreadable. `BRIEFING_SECONDS = 5f` was a queue-throughput number from the cabinet
era and is no longer paid for by anything.

---

## 2. The shared visual system

### 2.1 The root cause is a missing scrim, not the panel's opacity

**Measured, inside vs outside the panel:**

| Frame | inside | outside | effect |
|---|---|---|---|
| 01 main menu (sunset) | (192, 86, 58) | (250, 113, 76) | a 45% darkener over a very bright field |
| 06 pause during a run (160 m) | (3, 10, 29) | (0, 1, 14) | the panel is **brighter** than its surround |

The plate is doing two opposite jobs. Over the sunset it is a scrim leaving 55% of a saturated
orange showing. Over the abyss `dst ≈ 0`, so the plate's own RGB `(0.02, 0.05, 0.09)` *is* the
panel — it works as an additive **lift**. One alpha constant cannot serve both, and the first
draft's "choose it from captures at both extremes" was an unsatisfiable constraint on a single
number, not a mitigation.

**The actual variable is the backdrop.** Sampling `(400,300)` across the set, every screen that
looks bad has sky at `(171,65,100)` and **no scrim**; every screen that looks good has a scrim.
`MAIN_MENU -> drawMainMenu(hud, w, h)` (`EnPustTil.kt:3144`) draws no scrim, while the PAUSED
branch draws `PauseLayout.SCRIM_ALPHA` first (`:3208`). **That one line is the entire difference
between the frame this document calls the best-looking menu in the game and the two it calls the
worst.** The panel is identical in both.

**So, in order:**

1. **Give MAIN_MENU the scrim PAUSED already has.** One `fillRect` in the `MAIN_MENU ->` branch.
   It equalises the backdrop so one plate value genuinely works everywhere, and it introduces no
   new visual language (D4) — the scrim already exists on two other screens.
2. **Leave `PanelLayout.ALPHA` near 0.45–0.55** and, if anything, darken the plate's RGB toward
   `(0.01, 0.02, 0.04)`. Re-capture before touching alpha at all. At 0.85 the deep panel goes from
   `(3,10,29)` to roughly `(39,59,78)` — a pale slab on a near-black world, i.e. the control case
   destroyed to fix the others.
3. **Give every panel a border.** The edge scan on shot 01 shows a hard step from `(233,99,83)` to
   `(179,75,63)` — no stroke, no inner light. A card with a thin light border reads as a card at
   *any* fill opacity, because the eye locks onto the edge rather than the wash. This is the
   cheapest single win available and the first draft did not contain it.

**Facts that bound any alpha change:**

- **The literal is `PanelLayout.ALPHA` at `render/PanelLayout.kt:58`**, not `Hud.kt:344`. The first
  draft quoted the wrong file. `Hud.kt:344` reads `Color(0.02f, 0.05f, 0.09f, authoredAlphaFor(PanelLayout.ALPHA))`.
- **The plate is shared by seven screens by design.** `Hud.renderPanel` (`Hud.kt:786-790`)
  hard-codes `setDrawColor(panelPlate)`, and its KDoc says so deliberately: *"every panel in this
  design is the same plate, not a per-screen choice."* Six call sites cover main menu (ROOT and
  GRAPHICS), the attract leaderboard, run over, pause/cabinet, briefing and initials. Any
  per-screen plate means parameterising `renderPanel` **and rewriting that KDoc in the same
  commit**.
- **There is a hard ceiling at `ALPHA < 0.920960`.** `PanelLayoutTest:418-450` asserts
  `ALPHA + SCRIM_ALPHA·(1 − √ALPHA) < 0.95`. Solving gives `√a < 0.959667`. The first draft's
  "start at 0.85 and adjust" pointed straight at a wall two hundredths above its starting point.
- **The scrims crush harder than their displayed alpha suggests.** Under `new = src² + dst·(1−src)`
  with the raw src, the pause scrim multiplies what is under it by **0.1515** and the briefing
  scrim by **0.2584**.

### 2.2 The selection bar is currently harming the row it highlights

**Measured contrast ratios:**

| | 01 (sunset) | 06 (deep) |
|---|---|---|
| highlight bar vs panel | 2.04 : 1 | 5.56 : 1 |
| white text on plain panel | 7.05 : 1 | 19.7 : 1 |
| **white text on the highlight bar** | **3.46 : 1** | **3.54 : 1** |

On both screens the selected row is **the lowest-contrast text on the page**. The bar that exists
to emphasise a row degrades it from 7.05:1 to 3.46:1. In shot 06, `CONTINUE` is visibly muddier
than the unselected `GRAPHICS` below it. The first draft's "raise the bar's contrast" would have
driven this below 3.0:1 and failed large-text AA; **that item is dropped.**

Instead:

1. **Invert the bar.** A dark or accent-tinted bar with full-white text, which over a scrimmed
   backdrop reaches 10:1 and keeps the bar as a solid mark. This is also what the existing black
   text outline is tuned for.
2. **No leading-edge accent.** In shot 01 the bar spans x=720..1199 while `START DIVE` occupies
   roughly 878..1046 — a mark at x=720 sits ~160px from the nearest glyph and reads as panel
   furniture. A leading-edge caret works when the *text* is left-aligned to it. For a centred menu,
   use a symmetric pair of end marks or a full-width top-and-bottom rule.
3. **Ink hierarchy stays**, unselected rows at ~0.72 white, as a *secondary* cue only. It needs no
   pre-allocated `Color`: `Draw.kt:187-206` is a primitive overload taking raw `r,g,b,a` floats and
   its KDoc says it exists precisely for this. Zero allocation, no new field.

**A real defect neither the first draft nor `MenuLayoutTest` caught:** the bar spans x=720..1199,
but on the graphics page `OFF-SCREEN RAYS` starts at **x=710** — its first glyph column falls
*outside* the highlight. `HIGHLIGHT_HALF_SPAN = 0.20f` is too narrow for the longest row on the
page it serves. `MenuLayoutTest` misses it because it checks the highlight against the 4:3 panel,
never against the widest row's rendered width. Widen it, and **add an assertion that the highlight
contains the widest row at EM = 0.62**.

Cap: `HIGHLIGHT_HALF_SPAN < 0.533333` (`MenuLayoutTest:112-115`). A second, looser cap of
`< 0.641667` comes from `PanelLayoutTest:263-288`, which the first draft did not mention.

### 2.3 Page titles name the page

| Page | Title |
|---|---|
| ROOT, no run held | `ScreenText.TITLE` |
| ROOT, run held | `ScreenText.PAUSED_TITLE` |
| GRAPHICS | `ScreenText.MENU_GRAPHICS` |
| LEADERBOARD | `ScreenText.LEADERBOARD_HEADING` |

All four strings already exist. No new atlas risk.

### 2.4 The footer legend says what the buttons do

`PRESS PLUS  ·  MINUS to go back` mixes a call-to-action register with a legend register in one
line, and on the boot root menu the back half advertises a no-op.

New legend: **`PLUS to select  ·  MINUS to go back`**, with the back half suppressed on ROOT when
no run is held.

**This requires a NEW `ControlHints` composite, not an edit.** `ControlHints.pressStart` is
**shared with the attract screen's sign** — drawn at `EnPustTil.kt:3550` *and* concatenated into
`hintMenuLegend` at `:4198`. Editing it would change the attract sign, which §2.4 promises not to
touch. So add `ControlHints.select(...)`.

Two consequences the first draft got wrong:

- **`ControlHintsTest`'s pinned literals do not move.** They are at `:277-279`
  (`"ESC to resume"`, `"ESC to go back"`, `"HOLD Q to exit"`); the new legend keeps `goBack`
  byte-identical. The first draft cited `:271` and claimed the pin moves. It does not.
- **A new composite is NOT automatically swept.** `ControlHintsTest:245-259` enumerates the
  composites **by hand**, as does `ControlHints.all()`. A new function added without a line in both
  ships **uncovered by the font-atlas test** — a silent coverage hole, which is a worse failure
  mode than a red test.
- **`PauseMenuWiringTest:123-128` breaks here**, not at §3.4. It pins the verbatim source string
  `"if (runHeld && page == MenuPage.ROOT) hintPauseMenuLegend else hintMenuLegend"`, and both the
  ROOT suppression and the third page need a new branch there.

---

## 3. Per-screen changes

### 3.1 Main menu (ROOT)

- Scrim, border, focus inversion, ink hierarchy (§2.1–2.2).
- **`TITLE_Y` moves to `0.135f` ONLY when a run is held.** The collision is real and measured: the
  run-timer capsule occupies **0.020h..0.100h** (`Hud.kt` `CLOCK_FONT_FRACTION = 0.05`,
  `CLOCK_BOX_PAD_Y_EM = 0.30`, `MARGIN_FRACTION = 0.02`, giving `clockBoxHeight = 0.08h` centred at
  `0.06h`) and `PAUSED` at `TITLE_Y = 0.10` occupies **0.100h..0.160h**, both centred on `w·0.5`.
  **They touch at exactly 0.100h — a zero-pixel gap.** At 0.135 the gap is 0.035h (42px at
  h=1200).
  **But applying it unconditionally creates a worse bug.** The attract title's cap-height measures
  **64px** and the menu title's **42px** — a 1.52x shrink plus a y-drop on a single button press,
  which is the first animation any player sees and reads as a fault. Moving `TITLE_Y` to 0.135
  doubles that drop. So: ROOT-with-no-run matches `AttractLayout`'s title size and Y exactly; the
  0.135 offset applies only on the run-held variant, where the collision it fixes actually occurs.
  **0.135 is also the largest value with no side effect**: `drawMainMenu` passes
  `minTop = h·(TITLE_Y + TITLE_FONT)` (`EnPustTil.kt:3411`) against a padded content top of
  `0.22 − 0.025 = 0.195`, and `0.135 + 0.06 = 0.195` exactly. Above it the panel's top clamps into
  its own padding.
- **`HINT_Y` tracks `rowsBottom()` with a fixed gap** instead of being pinned at 0.90. On the
  4-row root menu the panel ends at y≈523 and the legend sits at y=1091 — **570px and the whole
  ocean between them**, so it does not read as belonging to the menu. On the 14-row graphics page
  the same constant puts it 16px below the panel. One constant, two unrelated compositions.
- **`QUIT` keeps normal weight.** The first draft proposed permanently muted ink; that is dropped.
  Muted ink already means "unselected", so a permanently muted `QUIT` would read at the same weight
  as an unselected `START DIVE` when it *is* selected — ambiguous with the focus system being built
  alongside it. Its position last in the list is the affordance, and D4 rules out a warning colour.

### 3.2 Graphics page

**Measured:** label left edges range **710 → 856** (a 146px ragged left margin); value left edges
are all at 981; content right edge maxes at 1111 while the panel runs to 1229 — **13px of padding
on the left and 118px of dead panel on the right**.

- **Left-align labels AND values at fixed columns** derived from the panel's inner edges. The
  first draft proposed keeping the centre-inward split and widening the panel; that is wrong twice
  over — widening *enlarges* the dead right margin, and the ragged left edge is the actual problem.
  A settings list is scanned down the label column, and right-aligning labels destroys the only
  fixed edge the eye can track. The inward split is inherited from `BriefingLayout`, which has
  three rows and does not scale to fourteen. **`MenuLayout`'s class doc explicitly justifies the
  inward split; that justification must be overridden in writing in the same commit.**
- **Group with whitespace, not headings.** Heading rows would change the selectable set and
  `MenuModelTest`'s reachability sweep. `rowY` gains a gap term: one gap after `QUALITY`, one
  between the GI block and the display block.
  **Verified arithmetic:** two gaps of 0.4 pitch give `needed = 13.8·0.0544 + 0.032 = 0.78272`, so
  `rowScaleFor(14) = 0.66/0.78272 = 0.843216` and `rowsBottom(14) = 0.879920 < 0.90` ✓ — at all
  five swept aspects, since `rowsBottom` reads only height fractions.
  **This only holds if `baseHeight` learns the gaps too.** If `rowY` gains them and `baseHeight`
  does not, `rowsBottom(14) = 0.918857 > 0.90` and the fit assertion fails — which is the correct,
  loud failure.
  The gaps fall after *specific indices*, so `rowY` must take a gap parameter. Keying on
  `rowCount == 14` is the exact silent-staleness trap `rowScaleFor`'s KDoc exists to prevent.
- `BACK` stays centred. It is an action, not a setting.

### 3.3 Attract screen

- Border (§2.1). The panel is already the most legible in the game because it sits over dark water.
- **The rank column is NOT moved.** The first draft proposed pulling it in toward the initials.
  `AttractLayout.rankX` is `centreX − screenHeight·ROW_HALF_SPAN` — the *same* constant as `scoreX`
  and as the panel's own width — and `AttractScreenTest:210-233` asserts
  `(rankLeftEdge + scoreRightEdge)/2 == centreX` to 0.001. Moving the rank alone breaks that
  symmetry outright. Moving both together is a larger change than the defect justifies. **Dropped.**
- **The empty board needs a state.** Rows 5–8 already read `AAA 10` and look like placeholder data;
  the moment D2's hold-to-delete fires, an *empty* board is the normal state and nothing is
  designed for it. `drawLeaderboard` currently returns early on an empty board, so the page would
  show a heading and nothing else.

### 3.4 Cabinet menu — LOWEST PRIORITY, droppable (D6)

Vestigial: the target moved to a desktop Mac and the main menu carries QUIT. Still reachable, so
not left visibly broken, but it gets no design effort. Scope is two one-line fixes, sequenced
**last**:

- **Do not draw the leaderboard under it.** `drawIdleScreen` gains a flag. This touches **no**
  `PauseMenuWiringTest` scanned string — the scan pins `drawPauseScreen(hud, w, h)` and
  `drawMainMenu(hud, w, h)`, and the flag changes `drawIdleScreen(hud, w, h)`. The first draft
  attributed a `PauseMenuWiringTest` break to this change; that was wrong (see §2.4).
- **Draw the exit-hold track only while holding.** **This reverses a documented decision and must
  answer it, not overwrite it.** `EnPustTil.kt:3832-3836` states the track is drawn unconditionally
  on purpose: *"an empty track under the 'HOLD … to exit' line is what tells a technician the
  control wants holding rather than pressing."* The counter-argument — that the label already
  carries the affordance, and that at rest the track reads as a rendering artefact — is probably
  right, but the existing comment must be engaged with in the replacement comment. `PauseScreenTest`
  is unaffected either way; it tests `barFillWidth`/`barX`, never whether the track is issued.

Everything else about the screen is deliberately left alone.

### 3.5 Run over

**The worst thing is not the score's size.** `RUN OVER · BANKED 10` is drawn at y≈615 **across the
diver's fins and inside the pearl ring**, on the busiest 300px of the frame — while the top-left
HUD still shows `BANKED 10` and the tape still shows the deepest mark. The number the first draft
wanted to enlarge **is already on screen twice**. Enlarging it without moving the block first just
makes a bigger thing collide with the diver.

In order:

1. **Scrim first.** Issued as the first statement of `drawRunOverScreen`, which paints over the
   already-submitted `Hud.render` and `VentLabel.render` (draw order confirmed at
   `EnPustTil.kt:3214-3226`). Pick the weight from a capture: at 0.55 the HUD underneath is
   multiplied by 0.2584, so the red `0:00` capsule — the best state signal in the game — loses
   ~74% of its weight, which is probably too much.
2. **Move the block clear of the diver**, or panel it.
3. **Then enlarge the numeral** and drop the middle-dot separator, which makes an end-of-run
   summary read like a status bar.
4. **Shrink the hint pill**: 540px wide around a 182px string.
5. **Extract `RunOverLayout`.** The anchors are inline `val`s (`titleY = 0.5f`, `titleFont = 0.04f`,
   `hintY = 0.545f`, `hintFont = 0.022f`) while `PanelLayoutTest` hand-copies them in a second file.

### 3.6 Initials entry

- **Real slot rects.** `[A] A A` marks the active letter with literal brackets, which steal
  horizontal space, shift the three letters off-centre, and read as a placeholder. Slot rects also
  give the panel a reason to be as wide as it is.
  **This is a compile break, not a layout change**: `ScreenText.initialsSlots` is referenced from
  `PanelLayoutTest:388` and from `ScreenText.all()` (`EnPustTil.kt:1054-1055`). A per-slot width
  bound must *replace* that assertion's subject, not delete it.
- **Shrink the panel**: measured **828px (43% of screen) around 216px of content (11%)**.
- Scrim, as §3.5.

### 3.7 Briefing

- Border and the panel actually becoming visible (§2.1). **Measured, the briefing panel does not
  currently exist on screen**: inside vs outside at y=760 is `(6,48,92)` vs `(4,47,91)` — one to
  two sRGB levels. The 0.55 scrim plus a 0.45 plate over a scrimmed sunset produces no visible
  card at all.
- **Do not relax `BriefingScreenTest` until a capture shows the panel visible.** Otherwise a real
  constraint is traded for nothing.
- The amber rule keeps its own literal `(1f, 0.85f, 0.3f)`, per the 2026-08-22 spec.
- Control rows keep their **inward** alignment. This is the opposite of the leaderboard's outward
  alignment and copying the leaderboard gets it backwards. (Note this is the one place the inward
  split stays — §3.2 overrides it only for the 14-row settings list.)
- **Suppress the in-run control legend while a menu is open.** Shot 06's top-right still reads
  `STICK swim · B kick · A bleed` while a menu whose controls are PLUS/MINUS is open, contradicting
  its own footer.
- **The briefing verbs disagree with the in-run legend**: the briefing says `A bleed pearls`, the
  legend says `A bleed`. Two strings for one control; one should move.

---

## 4. The LEADERBOARD page

### 4.1 Navigation — contains a CRITICAL defect the first draft missed

`MenuPage` gains `LEADERBOARD`; `MenuItemId` gains `DELETE_BOARD`. The row list is
`[DELETE_BOARD, BACK]`. The board itself is **display, not rows**.

**CRITICAL — back on the new page strands the player in the water.** `MenuModel.update:242` reads:

```kotlin
return if (page == MenuPage.GRAPHICS) { page = MenuPage.ROOT; …; MenuAction.Back }
       else { … MenuAction.CloseMenu }
```

On a `LEADERBOARD` page, back falls into the `else` and emits `CloseMenu`, which
`applyMenuAction` routes to `lifecycle.resumeRun()` — **so Esc on the leaderboard page would drop
a paused player straight back into the water instead of returning to ROOT.** The condition must
become `page != MenuPage.ROOT`.

`MenuItemId.LEADERBOARD` currently emits `ShowLeaderboard` → `viewLeaderboard()` → IDLE, which
**abandons a held run** (IDLE fires `justReturnedToIdle`, building a fresh `DiveSim`). As a page it
just opens, like GRAPHICS. That is a behaviour change and a side benefit, but
`RunLifecycleTest:622` and `:877` document the old path and become stale — they still compile and
pass because they call `viewLeaderboard()` directly, but `:880`'s comment becomes false.

`MenuLayout.rowY` takes the row block's top as a parameter (default `ROWS_TOP_Y`, so existing
call sites compile unchanged).

**Note:** `ScoreRepository.topN` does `ranked.take(n)`, allocating a list every frame the page is
open. `drawLeaderboard` already does this on every attract frame, so there is precedent — but it is
an allocation on the draw path and should be noted rather than discovered.

### 4.2 Hold to delete — contains a second CRITICAL defect

`MenuModel.update` gains `dt: Float = 0f`. **Four call sites total**, three in tests, and only
three lines change because 22 of 25 tests funnel through two helpers.

`updateMainMenu` runs on the **render clock** (`updateGame` ← `onUpdate`), not the fixed tick.
That is correct and consistent: `lifecycle.update` on the very next line already takes
`engine.data.deltaTime`, and `RunLifecycle`'s existing exit hold uses the same clock.

**CRITICAL — the specified rule lets the opening press wipe the board.** The first draft said the
hold accumulates while confirm is *held* (level, not edge). But confirming `LEADERBOARD` switches
the page and sets `selectedIndex = 0` — and `DELETE_BOARD` **is row 0**. `MenuModel.prime` is
called only on *menu entry*, never on a page switch. A human press lasts 5–10 frames, and anyone
leaning on the button clears `EXIT_HOLD_SECONDS` easily. **This is CRITICAL C1 in a new place, and
its consequence is a wiped leaderboard.**

So the rules are:

- The hold requires a **fresh confirm edge after the page opened** — latch `wasConfirm` on the page
  switch, or require a release first. Never a bare level.
- It accumulates only while page is LEADERBOARD, the selected row is `DELETE_BOARD`, and confirm is
  held.
- Release, navigation, or leaving the page resets it to zero.
- Crossing the threshold emits `MenuAction.DeleteBoard` **once**, then resets.
- A confirm **edge** on `DELETE_BOARD` does nothing. `MenuModel.kt:236`'s existing
  `else -> MenuAction.None` already gives this for free, which is exactly right.

**`RunLifecycle.EXIT_HOLD_SECONDS = 1.5f` is short for an irreversible wipe.** Reusing it keeps the
two holds feeling identical, which is the argument for it. Flagged as a decision, not a bug.

### 4.3 Clearing the scores — the specified backup silently does nothing

`ScoreRepository` gains a clear-by-seed method: remove entries matching a seed (default
`todaySeed`), clear `rankedCache` wholesale (as `registerScore` and `loadInto` already do), then
`saveAndPromote`, which is **synchronous, not debounced** — no timers, nothing special for the swap.

**The backup as first specified would not have run.** `maybeRollBackup` early-returns unless 30
minutes have elapsed, and `lastBackupTimeMs` is initialised **at construction** — so for the first
30 minutes of every session it writes nothing at all, and a delete inside that window would be
backed by no file. It also uses `saveAsync(…) { }` with an empty callback, and that completion
**fires only on success**, so a failed backup is silent.

Therefore: **extract an unconditional `writeBackup(store)` from its body, call `ScoreStore.saveSync`
(which returns `Boolean`), and refuse to wipe if it returns false.** Only today's seed is cleared,
so the delete cannot destroy day one while day two is running.

---

## 5. Briefing content (D3)

Three facts join the three control rows and the rule. No anglerfish (D5). Not the
point-of-no-return — the depth tape already marks it, and surfacing it needs a margin query out of
`dive/Ascent.kt` that does not exist.

| Fact | Why it earns screen space |
|---|---|
| Carried pearls make you heavier and slower | Spec §4's central mechanic — an empty diver hovers, carried mass is what makes you sink. Taught on **no screen at all** today. |
| Deeper pearls are worth more | The whole risk curve. The briefing never motivates going deep. |
| The bubbles are your air | Air is deliberately never a number, so the bubble ring is the only indicator, and nothing says so. |

**Vertical fits with room to spare.** With the halo clamp gone the panel top can descend to the
title's bottom (0.145), so content starts at 0.170 and the rule stays at 0.760 — a band of
**0.590h**. Three control rows at pitch 0.057 plus a 0.040 gap plus three facts at 0.026/pitch
0.0416 consumes **0.293h**. The risk is the opposite of crowding: ~0.30h of empty panel, which is
the exact defect `rowsBottom`'s KDoc records. Spread the block or pull `RULE_Y` up.

**Horizontal is what actually bites.** `PanelLayout.BRIEFING_HALF_SPAN = 0.32`, so at EM 0.62 and a
0.026 font a fact may be at most **39 characters**:

- `"DEEPER PEARLS ARE WORTH MORE"` (28) → 0.226h ✓
- `"THE BUBBLES ARE YOUR AIR"` (24) → 0.193h ✓
- `"CARRIED PEARLS MAKE YOU HEAVIER AND SLOWER"` (42) → **0.339h > 0.32 ✗**

Shorten it (e.g. `"PEARLS MAKE YOU HEAVY AND SLOW"`, 30 → 0.242h) or raise `BRIEFING_HALF_SPAN` to
0.34, which stays inside `PanelLayoutTest:263-288`'s cap.

**Form.** Not six flat rows. Controls stay a token/verb table; facts are a visually distinct block
at a smaller font, so the eye parses "three controls, then a short note".

**`BRIEFING_SECONDS` extends from 5f** to pay for the added reading time (§1).

Every new string is a `ScreenText` constant, added to `ScreenText.all()`, swept by
`AttractScreenTest`. The font ceiling is U+011F and an out-of-range character contributes **no
glyph and no x-advance**, silently.

---

## 6. Constraints that bound every change

Each fails **silently** when violated.

- **`drawQuad`/`drawLine` render nothing on macOS.** Use `Draw.fillRect`/`fillRectCentred`.
  `DrawTest`'s regex `\bdraw(Quad|Line)(Vertex)?\s*\(` scans every production source. This
  constrains four new pieces of drawing: the §2.2 focus marks, the §3.6 slot rects, the §4.2
  progress bar and the §2.1 border.
- **Alpha on the `"hud"` surface is squared and colour is premultiplied.** Everything
  semi-transparent goes through `Hud.authoredAlphaFor()`. Authored 0.15 displays near 0.02.
- **The default font ceiling is U+011F.**
- **Sizes are fractions of screen HEIGHT.** Prefer `surface.config.width/height`.
- **Vertical anchors are the TOP of the text box**, `yOrigin` defaults to 0.
- **No per-frame allocation on the draw path.** Use `Draw.kt:187-206`'s float overload rather than
  constructing a `Color`.
- **Gamepad reads only through `MappedPads`.** `MappedPadsTest`'s regex
  `\.(isPressed|getAxis)\s*\(` allows only `engine.input` and `mappedPads` receivers — so §4.2's
  level read of confirm must come through existing `updateMainMenu` plumbing.
- **Allman braces, 4 spaces, no wildcard imports.** Comments explain *why*, citing evidence.
- **Green tests are not evidence.** Every visual task ends with a real window grab.

---

## 7. Test impact

**Three hard compile breaks** from the enum/sealed additions, all of them loud and all of them the
good outcome:

| file:line | why |
|---|---|
| `MenuModel.kt:106` `itemsOn` | exhaustive `when (page)`, no `else` |
| `EnPustTil.kt:3478` `menuItemLabel` | exhaustive `when` over 18 ids, no `else` |
| `EnPustTil.kt:4422` `applyMenuAction` | exhaustive `when` over the sealed `MenuAction`, no `else` |

Safe: `menuValueFor` (`else -> ""`), `stepGameSettings` (`else -> current`), `MenuModel.kt:236`
(`else -> MenuAction.None`).

**Signature blast radius:** `MenuModel.update` +`dt` → 4 sites (3 test lines).
`MenuLayout.rowY` +`rowsTop`/gaps → 5 sites, plus `rowsBottom` needs the same → 3 more.
`drawIdleScreen` +flag → 2 sites, 0 tests. `HIGHLIGHT_HALF_SPAN` per-page → **7 sites across
`MenuLayoutTest` and `PanelLayoutTest`**.

| Test | Change | Note |
|---|---|---|
| `MenuModelTest:218` reachability | use `MenuPage.entries.flatMap { itemsOn(it) }` so it self-maintains | plus new tests: confirm-edge on `DELETE_BOARD` is inert; hold resets on navigation; **hold cannot fire from the page-opening press** |
| `MenuLayoutTest` | ~11 compile breaks then value re-checks | `:24-25`'s `longestPage`/`shortestPage` are hand-maintained and need the 2-row page. **`:22`'s comment claims `MenuModelTest` has a row-count drift guard — no such assertion exists.** Fix the comment or add the guard. |
| `PanelLayoutTest:418-450` | composed-alpha bound | ceiling `ALPHA < 0.920960` |
| `PanelLayoutTest:319-344` | **must be deleted or inverted** | it asserts the briefing panel top never rises into the halo band — exactly the constraint §3.7 removes. `drawBriefingScreen`'s `minTop` (`EnPustTil.kt:3897-3900`) must drop its halo term, **or the new disjunction's third arm is dead code**. |
| `PanelLayoutTest:75` | pre-existing drift | it re-derives `contentBottom` from `HINT_Y + HINT_FONT` while production passes `rowsBottom(items.size)`. **Not caused by this work, but it means the group-gap change is not covered there.** |
| `PanelLayoutTest:388` | `initialsSlots` subject disappears | replace with a per-slot width bound |
| `BriefingScreenTest:117` | halo → disjunction | assertable headlessly; `PanelLayout.bounds` is pure |
| `AttractScreenTest:210-233` | **blocks the §3.3 rank move** | which is why §3.3 drops it |
| `ControlHintsTest:245-259` + `ControlHints.all()` | add the new composite **by hand in both** | otherwise a silent coverage hole |
| `PauseMenuWiringTest:123-128` | new branch in the pinned legend expression | caused by §2.4/§4.1, **not** §3.4 |
| `ScoreRepositoryTest` | clear-by-seed | model on `:285` (`yesterday's scores stay on disk but off today's board`). Assert: today gone, **other seed survives**, backup file exists, refuses on backup failure |
| `RunLifecycleTest:622,877` | stale, not broken | `:880`'s comment becomes false |
| `EnPustTilMenuSettingsTest:109` | `DELETE_BOARD` joins `BACK` as an inert action row | low |

New: `ScoreScreenTest` for `RunOverLayout`/`InitialsLayout`, in the style of `PauseScreenTest`.

---

## 8. Out of scope

- Any new visual language (D4); the anglerfish (D5).
- **The initials 15-second auto-submit countdown.** The critique argues this is worse than the
  bracket — a player pausing to think loses their entry with no warning — and it is a fair point.
  It is a behaviour change, not a visual one, and needs its own decision. **Flagged for the owner.**
- A back step in initials entry (`InitialsEntry` has `slot++` with no decrement).
- Moving the attract rank column (§3.3).
- **The four 2026-08-23 Cut List items**, which failed verification: a cause on the run-over screen
  (the run ends exactly one way), unifying three scrim weights (there are two), tinting the bubble
  rim (already done), and two findings that review retracted.
- A unit on the leaderboard's scores; a tagline on the attract screen.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Raising `PanelLayout.ALPHA` damages the deep frame | Don't. Add the MAIN_MENU scrim instead and re-capture (§2.1). Ceiling is 0.9209 regardless. |
| Group gaps overflow the 14-row budget | `baseHeight` must learn the gaps; otherwise `rowsBottom(14) = 0.9189 > 0.90` fails loudly. |
| The briefing halo relaxation hides a real overlap | The replacement asserts a disjunction. Do not relax it until a capture shows the panel visible. |
| The delete fires from the page-opening press | §4.2: require a fresh confirm edge after the page switch. Assert it. |
| Back on the leaderboard page resumes the run | §4.1: `page != MenuPage.ROOT`. Assert it. |
| The pre-delete backup silently no-ops | §4.3: unconditional `writeBackup` + `saveSync` + refuse on false. |
| A new `ControlHints` composite ships unswept | Add it to `ControlHints.all()` **and** `ControlHintsTest`'s hand-written list. |
| `PauseMenuWiringTest`'s verbatim scan blocks a refactor | Change the scanned string deliberately, same commit. Never delete the scan. |
