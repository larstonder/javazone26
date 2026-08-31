package render

/**
 * Geometry for the dark panels drawn behind every lifecycle screen's menu/status text — the
 * owner's request (2026-08-31): "the menus and UI elements ... should be contained in dark
 * transparent boxes for better viewability", scoped to ONE PANEL PER SCREEN with the screen's
 * own title kept OUTSIDE it. Not the in-run HUD (see CLAUDE.md and the task brief at
 * `.superpowers/sdd/2026-08-30-main-menu-and-graphics-options/panel-task-brief.md`).
 *
 * Modelled on `AttractLayout`/`PauseLayout`/`BriefingLayout` (all in `EnPustTil.kt`, the
 * default package — unlinkable here for the reason `MenuLayout`'s own doc gives: a named
 * package cannot import a default-package type — WHICH IS FALSE IN KOTLIN, see `MenuLayout`'s
 * corrected class doc and the `import AttractLayout` it now carries; these three stay unlinked
 * here only because nothing in this file needs their values) and on `MenuLayout` itself, which
 * lives beside this file.
 *
 * EVERYTHING IS A FRACTION OF SCREEN HEIGHT, NEVER WIDTH AND NEVER A PIXEL COUNT —
 * `engine.window.width/height` are PHYSICAL framebuffer pixels and the display's aspect ratio
 * is unknown until something is plugged in.
 *
 * THIS FILE OWNS THE SHARED SHAPE OF A PANEL — [ALPHA], [PADDING_FRACTION],
 * [CORNER_RADIUS_FRACTION] and the [bounds] helper that turns a screen's own content box into a
 * padded, halo/title-clamped rectangle — so every panel is identical BY CONSTRUCTION rather
 * than by seven independent copies of the same padding-and-radius arithmetic. What each draw
 * site still owns is its OWN content box: which text sits inside the panel (never the screen's
 * title) and how wide that content plausibly gets. Four of the seven screens already have a
 * layout object with a usable half-width for this (`MenuLayout.highlightHalfSpanFor`,
 * `AttractLayout.ROW_HALF_SPAN`) and reuse it directly at the call site; the other three (pause,
 * briefing, initials entry) and run-over have NO existing width bound because their text is
 * free-standing device-dependent hint strings, so this file adds one each below — see their
 * docs for the derivation and its uncertainty.
 *
 * NO TEXT-MEASUREMENT API EXISTS ANYWHERE IN THIS PROJECT (checked: no `textWidth`/
 * `measureText` on `Surface`, nothing in `Draw.kt`), so every half-width constant here, like
 * every sibling in `AttractLayout`/`MenuLayout`/`PauseLayout`, is a hand-picked fraction of
 * screen height rather than a measured one — UNVERIFIED until someone looks at a real frame.
 * See the four `*_HALF_SPAN` docs below for how each was arrived at.
 *
 * @see PanelLayoutTest for the containment, on-screen and halo-clearance relationships this
 *   object exists to make assertable rather than merely intended.
 */
object PanelLayout
{
    /**
     * Displayed alpha of a panel's dark plate — the DEFAULT, taken by six of the seven panels
     * (see [MENU_PLATE_ALPHA] for the one that does not, and [Hud.renderPanel]'s doc for the
     * rule this exception replaced). Handed through [Hud.authoredAlphaFor] at the draw site —
     * this surface stores alpha SQUARED, so authoring 0.45 directly would come out looking like
     * roughly half that (see that function's own doc for the measurement this trap is named
     * after).
     *
     * CHOSEN, NOT MEASURED — nobody can look at this build (the machine is unattended; see
     * CLAUDE.md's screenshot warnings and the task brief). 0.45 sits below
     * `PauseLayout.SCRIM_ALPHA` (0.72) and `BriefingLayout.SCRIM_ALPHA` (0.55): a panel is a
     * SMALL box behind one block of text, not a full-screen dimmer, so it does not need to
     * carry as much weight as either scrim to read as "a dark box" against a bright Shallows
     * background. On the two screens that already have a scrim (pause, briefing) this alpha
     * composites ON TOP of the existing one rather than replacing it — see each draw site for
     * the composed figure, and PAUSE in particular: `1 - (1 - 0.72) * (1 - 0.45) = 0.846`,
     * reported in panel-report.md per the task brief's explicit request. That is dark but not
     * opaque; a human still needs to confirm it reads as "held" rather than "opaque" on a real
     * screen.
     */
    const val ALPHA = 0.45f

    /**
     * Displayed alpha of the MAIN MENU's plate specifically — the one per-screen override
     * [Hud.renderPanel] accepts. Read that function's doc first: it carries the argument for why
     * a per-screen plate exists at all and why the other six panels must keep [ALPHA].
     *
     * CHOSEN FROM CAPTURES AT TWO CANDIDATE VALUES, NOT FROM ALGEBRA — and the algebra is why.
     * The `hud` surface stores alpha squared, is drawn over by a 0.72 scrim before the panel goes
     * on it, and is then composited over an already-graded world, so what a plate alpha does to a
     * frame is a three-stage question nobody should answer on paper. Two real window grabs of the
     * GRAPHICS page (3440x1440 fullscreen, 2026-08-31) at 0.80 and at 0.90, measured as the
     * MEDIAN of the diver's body showing through the panel against the blank plate immediately
     * right of it, in Rec.709 luminance:
     *
     *                     diver            plate beside it     |dL|
     *   a = 0.45 (before)  (25, 62, 102) L 57.0   (149,68,48) L 83.8   26.8
     *   a = 0.80           (15, 41,  68) L 37.4   ( 98,43,35) L 54.1   16.7   (-38%)
     *   a = 0.90 (this)    (11, 31,  52) L 28.3   ( 73,33,30) L 41.3   13.0   (-51%)
     *
     * The composed-transmission model (`composed = a + SCRIM_ALPHA * (1 - sqrt(a))`, so
     * `1 - composed` is the world's surviving fraction: 31.3% at 0.45, 12.4% at 0.80, 6.3% at
     * 0.90) predicts the ratios correctly in LINEAR light and is worth keeping for that — it
     * predicted (103, 34, 52) for the scrimmed sunset against a measured (104, 34, 52) — but it
     * badly over-predicts the apparent change, because sRGB encoding compresses the bottom of the
     * range. Halving the transmitted light is a 51% drop in |dL|, not a 51% drop in visibility.
     * A value picked off that arithmetic alone would have been chosen believing 0.80 was enough.
     *
     * NOT PUSHED TO THE CEILING, AND THE ABYSS CASE IS NOW MEASURED RATHER THAN REASONED. The
     * panel is also drawn over a HELD RUN — mid-run Esc opens this same menu, over the abyss,
     * where `dst ~= 0` and the plate stops being a darkener and becomes an additive LIFT. The
     * design doc measures that case at 0.85 and calls the result "a pale slab on a near-black
     * world", i.e. the control frame destroyed to rescue the others.
     *
     * THIS DOC SAID "THAT FRAME WAS NOT CAPTURED AT THIS VALUE" AND NAMED 0.80 AS THE FALLBACK.
     * It has now been captured, and 0.90 survives it — the fallback was not needed. Reaching
     * PAUSED-from-a-run had defeated three separate attempts, and the cause turned out not to be
     * the window at all: `RunLifecycle.BRIEFING_SECONDS` went from 5f to 9f in the same pass, so
     * every recipe carrying the old `sleep 6` was delivering its Esc INSIDE the briefing, where
     * a press starts the dive instead of pausing it. `space`, `sleep 10.5`, `esc` reaches it on
     * the first try.
     *
     * Measured on that frame (`menu-shots/after-final-pause-in-run.png`, 3440x1440 fullscreen,
     * `EPT_DEPTH=160`, 2026-08-31), median over blank plate against the water either side of the
     * panel:
     *
     *   plate interior   sRGB (5, 12, 24)   L = 11.4
     *   water beside it  sRGB (0,  2, 20)   L =  2.9
     *
     * A dark navy card, not a pale slab. The prediction in the paragraph above — "the interior
     * converges toward the plate's own colour, sRGB (42, 63, 84)" — was WRONG BY A FACTOR OF
     * EIGHT in luminance, and wrong in the safe direction: `authoredAlphaFor` squares the alpha,
     * so the plate never reaches its own nominal colour on this surface. The border ring is
     * indeed what carries the card's edge there, exactly as predicted. If a future change makes
     * this frame look like a grey slab, THIS is still the constant to lower and 0.80 is still a
     * measured working value — but do not lower it on the design doc's 0.85 concern, because
     * that concern was checked and did not materialise.
     *
     * The hard ceiling is `< 0.920960`, from `PanelLayoutTest`'s composed-alpha bound
     * (`a + SCRIM_ALPHA * (1 - sqrt(a)) < 0.95`, solved). That test now sweeps this constant too,
     * so a future nudge past it fails the build rather than shipping an opaque menu.
     */
    const val MENU_PLATE_ALPHA = 0.90f

    /** Padding added on every side of a screen's content box, as a fraction of screen height. */
    const val PADDING_FRACTION = 0.025f

    /**
     * Corner radius, as a fraction of screen height — NOT of the panel's own size, unlike
     * `Hud`'s clock box (`CLOCK_BOX_CORNER_RATIO`, a fraction of that box's height): every
     * panel here shares one radius regardless of how tall its content block turns out to be,
     * which is what makes "the panels are identical by construction" true of the corner too.
     * `Hud.fillRoundedRect` clamps a radius larger than a shape's own half-extent, so a panel
     * shorter than this radius degrades to a stadium rather than throwing.
     */
    const val CORNER_RADIUS_FRACTION = 0.02f

    // --- Half-widths for the four screens with no existing content-width constant -----------
    // The other three (main menu + graphics page, the attract screen's leaderboard block) reuse
    // MenuLayout.highlightHalfSpanFor / AttractLayout.ROW_HALF_SPAN directly at their draw sites
    // — both already sized "to the widest plausible row" by their own docs, and both already
    // exercised against a 4:3 booth panel by MenuLayoutTest / AttractScreenTest. Not duplicated
    // here as PanelLayout constants of their own: EnPustTil.kt already has both in scope as
    // same-file/same-import objects, so restating them here would be a second copy of a number
    // that already has one home.
    //
    // SIZING METHOD for the four below, since none of these screens has a prior width bound to
    // reuse: EM = 0.62 is this project's own existing UPPER BOUND on a glyph's advance width in
    // the default font, as a multiple of font height — `Hud.CLOCK_BOX_GLYPH_WIDTH_EM`, whose own
    // doc explains why erring high is correct rather than lazy (the clock box centres its text,
    // so an over-estimate only adds air; an under-estimate lets a digit sit on the border), and
    // `BriefingScreenTest` already reuses the identical constant for exactly this kind of
    // width-fit check. Every half-span below is `charCount * EM * fontFraction`, halved for a
    // centred string, rounded UP to the nearest 0.02 for additional margin: a too-narrow panel
    // clips text (a real containment bug), a too-wide one is only a cosmetic looseness — nobody
    // can compare the two by eye on this build. Worst-case strings are the ones this codebase's
    // own tests already sweep for, not invented ones — `ControlHints.all()`'s own comment
    // identifies `"LEFT BUMPER"` / `"RIGHT BUMPER"` (the GENERIC-family fallback label, an enum
    // name with its underscore opened to a space) as the widest button labels it tests against.
    // `PanelLayoutTest` re-derives EM = 0.62 itself (same value, same source) and asserts each
    // constant below against the worst-case string it is sized for, rather than trusting this
    // comment's arithmetic to stay correct.

    /**
     * Pause / cabinet-menu screen. Content is the resume hint, the exit hint and the exit-hold
     * bar (title excluded). Driving string: `ControlHints.exitHold` on a GENERIC pad with both
     * exit buttons remapped to the bumpers — `"HOLD LEFT BUMPER + RIGHT BUMPER to exit"`, 39
     * characters at `PauseLayout.HINT_FONT` (0.03h). `39 * EM * 0.03 ~= 0.73h` full width
     * (centred, `xOrigin = 0.5f`), i.e. ~0.36h half-width from centre; rounded up to 0.40 for
     * margin. `PauseLayout.BAR_HALF_SPAN` (0.15) sits comfortably inside this.
     */
    const val PAUSE_HALF_SPAN = 0.40f

    /**
     * Pre-run briefing screen. Content is the three control rows and the rule line (title
     * excluded; the countdown/skip lines below them are narrower). Driving string:
     * `ScreenText.BRIEFING_RULE`, a FIXED compile-time string — `"SURFACE TO BANK YOUR
     * PEARLS"`, 28 characters at `BriefingLayout.RULE_FONT` (0.034h). `28 * EM * 0.034 ~=
     * 0.59h` full width, i.e. ~0.30h half-width, centred (the rule is drawn at `xOrigin =
     * 0.5f`); rounded up to 0.32. A control row with a remapped bumper token
     * (`"LEFT BUMPER"` right-aligned against a 4-6 character verb, inward-aligned per
     * `BriefingLayout.COLUMN_GAP`) reaches at most ~0.22h from centre on its wider side — inside
     * this bound.
     *
     * UNCHANGED AT 0.32 WHEN THE THREE FACTS LANDED (2026-08-31, decision D3), AND THAT WAS A
     * DECISION RATHER THAN AN OVERSIGHT. The facts are drawn at `BriefingLayout.FACT_FONT`
     * (0.026h), so this bound caps a fact at `2 * 0.32 / (0.62 * 0.026)` = 39 characters — and
     * the owner's own phrasing of the weight fact, "carried pearls make you heavier and
     * slower", is 42 and needs 0.339h. The alternative was to widen this constant to 0.34
     * (which fits: `PanelLayoutTest`'s 4:3 on-screen sweep allows up to 0.641667). The STRING
     * was shortened instead, because widening the card widens it on every briefing forever to
     * accommodate one sentence, while the sentence had a 38-character form that loses nothing
     * — see `ScreenText.BRIEFING_FACT_WEIGHT`. `BriefingScreenTest` asserts all three fit.
     */
    const val BRIEFING_HALF_SPAN = 0.32f

    /**
     * Run-over screen. Content is the big score numeral, its caption and the retry hint (the
     * "RUN OVER" title stays outside the panel — see the run-over draw site and
     * panel-report.md's "found by arithmetic" section for why that distinction matters here
     * more than it does anywhere else).
     *
     * The bound is unchanged at 0.20 and the reason it is unchanged is worth stating, because
     * the plan for this work asked for the panel to be SHRUNK: measured on shot 07, it was
     * 540 px of card around a 182 px string. Two of the three candidate contents are now
     * wider than they were, and the widest is still the hint:
     *
     *  - `ControlHints.playAgain` at its GENERIC widest, `"LEFT BUMPER to play again"` — 25
     *    characters at `RunOverLayout.HINT_FONT` (0.022h) = 0.171h half-width. THE DRIVER.
     *  - The numeral, `"99999"` at `RunOverLayout.SCORE_FONT` (0.105h) = 0.163h.
     *  - `ScreenText.RUN_OVER_CAPTION` at `CAPTION_FONT` (0.022h) = 0.089h.
     *
     * So the 540 px was never spare width — it was width the hint's worst case genuinely
     * needs, wrapped around a card that happened to hold only that hint's TYPICAL case. The
     * defect was the content-to-card ratio, and it is fixed by giving the card content (a
     * numeral at 0.105h that fills 95% of the width the hint reserves) rather than by
     * narrowing it under a string that would then clip on a rebind. `ScoreScreenTest` asserts
     * all three against this constant so the driver cannot change without saying so.
     */
    const val RUN_OVER_HALF_SPAN = 0.20f

    /**
     * Initials-entry screen. Content is the three slot rectangles and the help line (the "NEW
     * SCORE (dot) BANKED N" line is this screen's title, excluded the same way run-over's is).
     *
     * NARROWED FROM 0.32 ON 2026-08-31. Measured on shot 08: 828 px of card (43% of a 1920
     * screen) around 216 px of content (11%). Two things changed to close that:
     *
     *  - The slot block is real geometry now, not a string. `InitialsLayout.slotsHalfSpan()`
     *    is `(3 * 0.115 + 2 * 0.026) / 2` = 0.1985h — nearly double the ~0.10h the old
     *    `"[A]  A   A "` line occupied at its 0.06h font.
     *  - The help line, which is and always was the constant's real driver, is drawn smaller.
     *    `ControlHints.initialsHelp` at its GENERIC/arcade widest is
     *    `"STICK UP/DOWN change letter (dot) LEFT BUMPER next"` — 48 characters (this doc said
     *    46 and was two short; SEPARATOR is five characters wide, not three). At
     *    `InitialsLayout.HELP_FONT`, now 0.018h rather than 0.02h, that is
     *    `48 * EM * 0.018 / 2` = 0.268h, down from 0.298h.
     *
     * 0.28 is the smaller figure rounded up. The card is now 0.61h wide instead of 0.69h and
     * holds 0.40h of slots instead of 0.20h of text.
     */
    const val INITIALS_HALF_SPAN = 0.28f

    /** The centre, size and (pre-clamp) padded extent of a panel — everything [Hud.renderPanel]
     * needs to draw it. Pixel-space, matching every sibling layout object's convention of
     * returning positions already multiplied by screen height/handed a centre in pixels. */
    data class Bounds(val centreX: Float, val centreY: Float, val width: Float, val height: Float)

    /**
     * The panel enclosing a content block from ([contentLeft], [contentTop]) to ([contentRight],
     * [contentBottom]) — all already in the SAME pixel space the caller draws text in (typically
     * `screenHeight * someLayoutObject.SOME_Y` for the vertical edges and `centreX +/-
     * screenHeight * someLayoutObject.SOME_HALF_SPAN` for the horizontal ones) — padded by
     * [PADDING_FRACTION] `* screenHeight` on every side.
     *
     * [minTop] IS REQUIRED, NOT DEFAULTED, ON PURPOSE. Padding alone is not safe: this codebase
     * has at least one screen (run-over — see panel-report.md) where the gap between a title's
     * own bottom edge and the panel's content is SMALLER than [PADDING_FRACTION], so naive
     * padding draws the panel straight through the title text it is supposed to stay clear of.
     * The fix generalises rather than special-cases run-over: every call site must state the
     * floor its panel's top edge may not rise above — its own title's bottom edge for every
     * screen, and ADDITIONALLY the diver halo's bottom edge
     * (`Framing.DIVER_SCREEN_FRACTION + AttractLayout.DIVER_HALO_HALF_HEIGHT`, both in pixel
     * space) for the attract screen's leaderboard panel, per the task brief's explicit
     * requirement that that one must not intrude into the halo band. A call site with nothing
     * above its content to protect against passes `Float.NEGATIVE_INFINITY`, which the `maxOf`
     * below then never selects — a real floor rather than an magic sentinel string, and visible
     * at the call site as "there is no floor" rather than a silently-omitted parameter.
     */
    fun bounds(
        contentLeft: Float,
        contentTop: Float,
        contentRight: Float,
        contentBottom: Float,
        screenHeight: Float,
        minTop: Float
    ): Bounds
    {
        val pad = screenHeight * PADDING_FRACTION
        val top = maxOf(contentTop - pad, minTop)
        val left = contentLeft - pad
        val right = contentRight + pad
        val bottom = contentBottom + pad

        return Bounds(
            centreX = (left + right) * 0.5f,
            centreY = (top + bottom) * 0.5f,
            width = right - left,
            height = bottom - top
        )
    }

    /** [CORNER_RADIUS_FRACTION] of [screenHeight], in pixels — what [Hud.renderPanel] wants. */
    fun cornerRadius(screenHeight: Float): Float = screenHeight * CORNER_RADIUS_FRACTION

    /**
     * Thickness of the pale hairline stroked around every panel, as a fraction of screen height.
     *
     * WHY A BORDER AT ALL. The edge scan across shot 01 of the 2026-08-31 capture set steps
     * straight from (233, 99, 83) outside the panel to (179, 75, 63) inside it — no stroke, no
     * inner light, just a change of wash. A wash is the one cue that a bright backdrop can beat;
     * an EDGE is not, because the eye locks onto a line regardless of what either side of it is
     * doing. That is why this is the cheapest single win available on these screens and why it
     * has to survive at [ALPHA] rather than being traded for a heavier plate.
     *
     * 0.002 is [render.textOutlineOffset]'s own 2/1080 rounded to a round number, and for the
     * same reason that constant is a fraction: `engine.window.width/height` are PHYSICAL
     * framebuffer pixels (2400x1800 on a Retina Mac from a declared 1200x900), so a flat "2 px"
     * would be a hairline at 1080p and sub-pixel — likely filtered away entirely — on a 4K
     * panel. 2.4 px at h = 1200, 3.6 px at h = 1800.
     */
    const val BORDER_FRACTION = 0.002f

    /** [BORDER_FRACTION] of [screenHeight], in pixels — what [Hud.renderPanel] wants. */
    fun borderWidth(screenHeight: Float): Float = screenHeight * BORDER_FRACTION
}
