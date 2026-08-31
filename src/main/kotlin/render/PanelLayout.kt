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
 * package cannot import a default-package type) and on `MenuLayout` itself, which lives beside
 * this file. EVERYTHING IS A FRACTION OF SCREEN HEIGHT, NEVER WIDTH AND NEVER A PIXEL COUNT —
 * `engine.window.width/height` are PHYSICAL framebuffer pixels and the display's aspect ratio
 * is unknown until something is plugged in.
 *
 * THIS FILE OWNS THE SHARED SHAPE OF A PANEL — [ALPHA], [PADDING_FRACTION],
 * [CORNER_RADIUS_FRACTION] and the [bounds] helper that turns a screen's own content box into a
 * padded, halo/title-clamped rectangle — so every panel is identical BY CONSTRUCTION rather
 * than by seven independent copies of the same padding-and-radius arithmetic. What each draw
 * site still owns is its OWN content box: which text sits inside the panel (never the screen's
 * title) and how wide that content plausibly gets. Four of the seven screens already have a
 * layout object with a usable half-width for this (`MenuLayout.HIGHLIGHT_HALF_SPAN`,
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
     * Displayed alpha of a panel's dark plate. Handed through [Hud.authoredAlphaFor] at the
     * point a `Color` is built (see `Hud.panelPlate`) — this surface stores alpha SQUARED, so
     * authoring 0.45 directly would come out looking like roughly half that (see that
     * function's own doc for the measurement this trap is named after).
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
    // MenuLayout.HIGHLIGHT_HALF_SPAN / AttractLayout.ROW_HALF_SPAN directly at their draw sites
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
     */
    const val BRIEFING_HALF_SPAN = 0.32f

    /**
     * Run-over screen. Content is only the retry hint (the "RUN OVER (dot) BANKED N" line is
     * this screen's title and stays outside the panel — see the run-over draw site and
     * panel-report.md's "found by arithmetic" section for why that distinction matters here
     * more than it does anywhere else). Driving string: `ControlHints.playAgain` with a
     * GENERIC-family confirm label — `"LEFT BUMPER to play again"`, 26 characters at the
     * draw site's hint font (0.022h). `26 * EM * 0.022 ~= 0.35h` full width, i.e. ~0.18h
     * half-width; rounded up to 0.20.
     */
    const val RUN_OVER_HALF_SPAN = 0.20f

    /**
     * Initials-entry screen. Content is the initials-slot line and the help line (the "NEW
     * SCORE (dot) BANKED N" line is this screen's title, excluded the same way run-over's is).
     * The slot line itself is a FIXED 11 characters regardless of the letters chosen
     * (`ScreenText.initialsSlots` joins three 3-character cells with two 1-character gaps —
     * `"[A]"`/`" A "` are both 3 wide — `3*3 + 2*1 = 11`), which at the draw site's 0.06h font
     * is only ~0.20h wide (~0.10h half-width). The wider driver is the help line:
     * `ControlHints.initialsHelp` at its GENERIC/arcade widest —
     * `"STICK UP/DOWN change letter (dot) LEFT BUMPER next"`, 46 characters at the draw site's
     * 0.02h font. `46 * EM * 0.02 ~= 0.57h` full width, i.e. ~0.29h half-width; rounded up to
     * 0.32.
     */
    const val INITIALS_HALF_SPAN = 0.32f

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
}
