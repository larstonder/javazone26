package render

import AttractLayout

/**
 * Geometry for the main menu. Modelled directly on the attract screen's and the pause screen's
 * own layout objects (`AttractLayout`, `PauseLayout`, `BriefingLayout` — all in `EnPustTil.kt`,
 * the default package).
 *
 * A NAMED PACKAGE *CAN* IMPORT A DEFAULT-PACKAGE TYPE IN KOTLIN, AND THIS FILE NOW DOES. Every
 * earlier version of this doc (and `PanelLayout`'s, which copied it) asserted the opposite —
 * "a named package cannot import a default-package type, so there is no cross-file reference
 * to draw here" — and used that as the reason the sibling layout objects were only NAMED here
 * rather than linked. That is a Java rule, not a Kotlin one: `import AttractLayout` from
 * `package render` compiles, which is exactly how `PanelLayoutTest` (also `package render`)
 * has been importing `AttractLayout`/`BriefingLayout`/`PauseLayout` all along. Verified by
 * compiling a probe before relying on it. The import is used for ONE thing — [titleY] /
 * [titleFont], where "the front page's title is byte-identical to the attract screen's" has to
 * be structural rather than two copied literals that can drift (see those functions).
 *
 * EVERYTHING IS A FRACTION OF SCREEN HEIGHT, NEVER WIDTH AND NEVER A PIXEL COUNT — the reason
 * every sibling layout object gives: `engine.window.width/height` are PHYSICAL framebuffer
 * pixels (see this repo's CLAUDE.md, "HiDPI"), and the booth display's aspect ratio is not
 * known until something is plugged in. A width fraction would stretch the rows on a wide
 * display while the glyphs drawn inside them — also height-derived — stayed the same size,
 * which is exactly the defect `AttractLayout.ROW_HALF_SPAN`'s doc records for the old
 * leaderboard row.
 *
 * VERTICAL ANCHORS ARE THE TOP OF A TEXT BOX, not the baseline — same convention as
 * `AttractLayout`/`PauseLayout`/`BriefingLayout`. Text grows DOWNWARD from an anchor, so a
 * block occupies `y .. y + fontSize`.
 *
 * THE GRAPHICS PAGE'S TWO COLUMNS ARE BOTH LEFT-ALIGNED, MEASURED FROM THE PANEL'S OWN LEFT
 * INNER EDGE — AND THIS DELIBERATELY OVERRIDES WHAT THIS DOC SAID UNTIL 2026-08-31. It used to
 * read: "THE COLUMN SPLIT ON THE GRAPHICS PAGE IS INWARD, copied from
 * `BriefingLayout.COLUMN_GAP` ... the label is right-aligned at `centreX - COLUMN_GAP * h` and
 * the value left-aligned at `centreX + COLUMN_GAP * h`, so both keep a clean edge against the
 * centre line whatever their own width."
 *
 * That reasoning is sound for the BRIEFING screen and the briefing KEEPS it. It does not
 * survive being scaled from three rows to fourteen, and the capture set says so in pixels: on
 * shot 02 (fullscreen, `after-phase1-graphics.png` at 3440x1440) the fourteen label left edges
 * range over **146 px**, from `RESOLUTION` at x = 856 to `LIGHT MAP SCALE` at x = 710 — because
 * right-aligning a column against the centre line is precisely a promise that its LEFT edge
 * moves with every string's own length. Three rows can afford that; a settings list cannot,
 * because a settings list is not read row by row, it is SCANNED DOWN THE LABEL COLUMN, and a
 * ragged left edge destroys the only fixed edge the eye can track down it. The briefing's three
 * rows are read, not scanned, and its inward split stays (that divergence is deliberate, not an
 * oversight — this is the one page where the two screens differ).
 *
 * So both columns are now left-aligned at fixed offsets from the panel's own inner left edge:
 * [rowTextLeftX] for the label, [valueLeftX] for the value. The offsets are expressed in EMS OF
 * THE ROW'S OWN FONT rather than in fractions of screen height, because the row font itself
 * already shrinks with the row count ([rowFontFor]) — a fixed height fraction would tighten
 * against the glyphs on a long page and loosen on a short one, which is the same class of bug
 * as a pixel count.
 */
object MenuLayout
{
    // --- The title, above the row list -----------------------------------------------------

    /**
     * The title anchor for a menu state that is NOT the game's front page — the paused ROOT
     * page and the GRAPHICS page (see [titleY] for which is which and why).
     *
     * 0.135, RAISED FROM 0.10 ON 2026-08-31, AND IT IS THE LARGEST VALUE WITH NO SIDE EFFECT.
     * The collision it fixes is measured: the in-run timer capsule occupies **0.020h..0.100h**
     * (`Hud.CLOCK_FONT_FRACTION` 0.05, `CLOCK_BOX_PAD_Y_EM` 0.30, `MARGIN_FRACTION` 0.02, so a
     * 0.08h box centred at 0.06h) and `PAUSED` at the old 0.10 occupied **0.100h..0.160h** —
     * both centred on `w * 0.5`, touching at exactly 0.100h with a ZERO-PIXEL gap. At 0.135 the
     * gap is 0.035h, i.e. 50 px at h = 1440.
     *
     * The ceiling: `drawMainMenu` passes `minTop = h * (titleY + titleFont)` to
     * `PanelLayout.bounds`, against a padded content top of
     * `ROWS_TOP_Y - PanelLayout.PADDING_FRACTION = 0.195`. `0.135 + 0.06 = 0.195` EXACTLY, so
     * the panel's top edge is decided by its own padding and not by the clamp. One hundredth
     * higher and the panel starts eating into its own padding instead.
     */
    const val TITLE_Y = 0.135f

    const val TITLE_FONT = 0.06f

    /**
     * Whether this menu state wears the GAME'S OWN TITLE (the front page) or a page header.
     *
     * Only ROOT with no run held is the front page: it is the screen the game boots into and
     * the screen a press from attract mode lands on, so it is the same object the attract
     * screen's sign is — the game, named. Everything else is a page inside the menu.
     */
    fun isFrontPage(page: MenuPage, runHeld: Boolean): Boolean = page == MenuPage.ROOT && !runHeld

    /**
     * Top of the title's text box for [page] / [runHeld].
     *
     * THE FRONT PAGE USES THE ATTRACT SCREEN'S OWN ANCHORS, BY REFERENCE, AND THAT IS THE WHOLE
     * POINT OF THIS FUNCTION. Measured on the 2026-08-31 capture set: the attract title's cap
     * height is **64 px** and the menu title's **42 px** (both at h = 1440), so a single press
     * of SPACE on the attract screen shrank the identical string `ONE MORE BREATH` by **1.52x**
     * and dropped it 0.015h at the same time. That is the first transition any player sees, it
     * is between two screens that are otherwise the same picture, and it reads as a rendering
     * fault rather than as a navigation. Raising [TITLE_Y] to 0.135 unconditionally — which is
     * what the collision above asks for — would have DOUBLED the drop.
     *
     * `AttractLayout.TITLE_Y`/`TITLE_FONT` are read here rather than copied as literals so the
     * two screens cannot drift apart: "the same" is enforced by there being one number, not by
     * a test comparing two. (`MenuLayoutTest` still checks the derived relationships — that
     * both variants clear the first row — because those CAN break from either side.)
     */
    fun titleY(page: MenuPage, runHeld: Boolean): Float =
        if (isFrontPage(page, runHeld)) AttractLayout.TITLE_Y else TITLE_Y

    /** The title's font size for [page] / [runHeld] — see [titleY]. */
    fun titleFont(page: MenuPage, runHeld: Boolean): Float =
        if (isFrontPage(page, runHeld)) AttractLayout.TITLE_FONT else TITLE_FONT

    // --- The row list -----------------------------------------------------------------------
    // ROWS_TOP_Y clears the title with room to spare (see MenuLayoutTest's "the title clears
    // the first row"), and ROW_SPACING > 1 is what keeps consecutive rows from overlapping —
    // both asserted rather than merely intended, because a future font-size change here is
    // exactly the kind of edit that looks harmless and silently overlaps two rows of settings.

    const val ROWS_TOP_Y = 0.22f
    const val ROW_FONT = 0.032f

    /** Row pitch as a multiple of [ROW_FONT]. */
    const val ROW_SPACING = 1.7f

    /**
     * Breathing room kept between the last row's bottom edge and [ROW_BUDGET_BOTTOM] — a page
     * whose rows exactly fill their budget should still read as a separate block from whatever
     * sits below it, not touch it.
     */
    const val ROW_BLOCK_MARGIN = 0.02f

    /**
     * The floor the row block may not grow past, as a fraction of screen height — the budget
     * [rowScaleFor] shrinks a long page against.
     *
     * WAS CALLED `HINT_Y` AND WAS ALSO THE HINT LEGEND'S DRAW POSITION; those two jobs are now
     * separated (see [hintY] for the defect that forced it). Renamed rather than kept, because
     * a constant named for a thing it no longer positions is how the next person mis-reads the
     * budget arithmetic. The VALUE is unchanged at 0.90, so every row anchor on both pages is
     * byte-identical to before the split.
     */
    const val ROW_BUDGET_BOTTOM = 0.90f

    /**
     * Extra vertical space inserted ABOVE a group-opening row, as a multiple of the row pitch —
     * how the GRAPHICS page is grouped.
     *
     * WHITESPACE RATHER THAN HEADING ROWS, deliberately. A heading row would have to be either
     * selectable (changing `MenuModel`'s row list, its reachability sweep, and what `up`/`down`
     * land on) or an unselectable row the index arithmetic has to skip — both are a navigation
     * change dressed as a visual one. A gap is neither: the rows and their indices are
     * untouched, only their `y` moves.
     *
     * 0.4 of a pitch, not a whole one. Verified arithmetic (see [baseHeight]): two gaps give
     * `needed = 13.8 * 0.0544 + 0.032 = 0.782720`, so `rowScaleFor(14, 2) = 0.66 / 0.782720 =
     * 0.843213` and `rowsBottom(14, 2) = 0.880000`, still under [ROW_BUDGET_BOTTOM]. A whole
     * pitch each would cost another 0.0653h of budget and shrink every row further to pay for
     * it — the page is already the smallest type in the game.
     */
    const val GROUP_GAP = 0.4f

    /**
     * The rows a [GROUP_GAP] is inserted ABOVE.
     *
     * The GRAPHICS page is three blocks: the preset (`QUALITY`), the six GI knobs it writes
     * (`LIGHT MAP SCALE` .. `OFF-SCREEN RAYS`), and the window/display rows
     * (`RESOLUTION` .. `SHOW FPS`) plus `BACK`. So a gap opens the GI block and a gap opens the
     * display block. `BACK` gets none: it is one row and the block above it already ends with
     * a value column that visibly stops.
     *
     * A SET OF IDS, NOT A SET OF INDICES AND NOT A ROW-COUNT TEST. `rowScaleFor`'s own doc
     * records why that matters — the six GI knobs turned an 8-row page into a 14-row one and
     * anything keyed on "the long page" silently went stale the moment the count moved. An id
     * moves with its row; an index does not. [groupGapsIn] counts them out of the REAL list
     * `MenuModel.itemsOn` returns, so the count and the positions cannot disagree.
     */
    private val GROUP_OPENERS = setOf(MenuItemId.LIGHT_MAP_SCALE, MenuItemId.RESOLUTION)

    /** Whether [item] opens a new visual group — see [GROUP_OPENERS]. */
    fun opensGroup(item: MenuItemId): Boolean = item in GROUP_OPENERS

    /**
     * How many [GROUP_GAP]s [items] contains — every [GROUP_OPENERS] member except one sitting
     * at index 0, where a gap above the first row would only push the whole block down.
     *
     * An index loop rather than `count { }`: this runs once per frame on the draw path and this
     * project forbids per-frame allocation there (a lambda-taking `count` on a `List` allocates
     * an iterator). Fourteen `Set.contains` calls, no allocation.
     */
    fun groupGapsIn(items: List<MenuItemId>): Int
    {
        var gaps = 0
        for (index in 1 until items.size) if (items[index] in GROUP_OPENERS) gaps++
        return gaps
    }

    /**
     * THE LAYOUT PROBLEM, SOLVED AS ARITHMETIC RATHER THAN A SECOND SET OF CONSTANTS. The
     * GRAPHICS page went from 8 rows to 14 when the six GI knobs landed (gi-knobs-brief.md).
     * At the base [ROW_FONT]/[ROW_SPACING], 14 rows end at `0.22 + 13*0.0544 + 0.032 = 0.959h` —
     * past [ROW_BUDGET_BOTTOM] (0.90h) and off the bottom of the screen; ROOT's 4 rows end at a
     * comfortable 0.42h.
     *
     * Rather than hand-picking a second, smaller font for "the long page" (which silently goes
     * stale the next time a row is added or removed — nothing would fail the build, the page
     * would just quietly start clipping again), this computes how much smaller than [ROW_FONT]
     * a [rowCount]-row page's rows need to be to fit the fixed vertical budget between
     * [ROWS_TOP_Y] and [ROW_BUDGET_BOTTOM] (minus [ROW_BLOCK_MARGIN]), and returns 1f — no
     * shrink at all — whenever the page already fits at the base size. That is why ROOT is
     * untouched: its 4-row `baseHeight` (0.1952h) is far under the 0.66h budget, so
     * [rowScaleFor] returns 1f and every ROOT anchor is byte-identical to before this function
     * existed. GRAPHICS' 14-row, 2-gap `baseHeight` (0.7827h) exceeds the budget, so its rows
     * shrink to `budget / baseHeight` = 0.843213 of [ROW_FONT] — 0.0269828h. [MenuLayoutTest]
     * asserts the general property (every page's rows end above [ROW_BUDGET_BOTTOM]) rather
     * than pinning today's two specific row counts, so a future row added or removed to either
     * page is caught by the same test without anyone updating a literal here.
     *
     * [gapCount] IS NOT OPTIONAL AND MUST NOT BE GIVEN A DEFAULT. The gaps [GROUP_GAP]
     * introduces are part of the block's height, and if `rowY` learns them while this does not,
     * `rowsBottom(14)` comes out at 0.918857 — past [ROW_BUDGET_BOTTOM] and clipping — while
     * every test that only asks "does a row overlap its neighbour" still passes. A default of 0
     * would let exactly that mistake compile at a call site that forgot to pass it, which is
     * the same silent-staleness failure this whole function exists to prevent.
     */
    private fun baseHeight(rowCount: Int, gapCount: Int): Float =
        ((rowCount - 1) + gapCount * GROUP_GAP) * ROW_FONT * ROW_SPACING + ROW_FONT

    /** See [baseHeight]'s doc — 1f (no shrink) for a page that already fits, otherwise the
     * fraction of [ROW_FONT] a [rowCount]-row page with [gapCount] group gaps must shrink to. */
    fun rowScaleFor(rowCount: Int, gapCount: Int): Float
    {
        val budget = ROW_BUDGET_BOTTOM - ROW_BLOCK_MARGIN - ROWS_TOP_Y
        val needed = baseHeight(rowCount, gapCount)
        return if (needed <= budget) 1f else budget / needed
    }

    /** The row font size for a page of [rowCount] rows and [gapCount] group gaps — [ROW_FONT]
     * scaled by [rowScaleFor]. */
    fun rowFontFor(rowCount: Int, gapCount: Int): Float = ROW_FONT * rowScaleFor(rowCount, gapCount)

    /**
     * Top of row [index] (0-based) on a page of [rowCount] rows, as a fraction of screen
     * height.
     *
     * [gapCount] is how many [GROUP_GAP]s the whole page has (it sets the font, via
     * [rowFontFor]); [gapsBefore] is how many of them fall at or above THIS row (it sets where
     * the row lands). The caller keeps a running count down its own draw loop rather than this
     * function re-scanning the list per row — see `EnPustTil.drawMainMenu`.
     *
     * [rowsTop] IS A PARAMETER WITH A DEFAULT RATHER THAN THE [ROWS_TOP_Y] CONSTANT IT USED TO
     * READ DIRECTLY, and the default is what keeps ROOT and GRAPHICS byte-identical to before
     * it existed. The LEADERBOARD page is the one page whose rows do NOT start at the top of
     * the panel: the board itself is drawn above them (see [leaderboardRowsTopY]), and its
     * height depends on how many scores the day has produced — so "where the row block starts"
     * became a value the caller computes rather than a constant this object owns. Every other
     * call site omits it and is unaffected.
     */
    fun rowY(index: Int, rowCount: Int, gapCount: Int, gapsBefore: Int,
             rowsTop: Float = ROWS_TOP_Y): Float =
        rowsTop + rowFontFor(rowCount, gapCount) * ROW_SPACING * (index + gapsBefore * GROUP_GAP)

    /**
     * Where the last of [rowCount] rows ENDS, as a fraction of screen height — the bottom of the
     * block the panel has to contain.
     *
     * Exists because anchoring the panel to the hint line instead, as it was first written, made
     * the panel the same height on both pages by spanning all the way down — leaving ROOT (four
     * rows, ending near 0.42h) with roughly half a screen of empty dark box below its last item.
     * Uniformity between the two pages is not worth that: the panel is there to back the text,
     * so it should end where the text does, and the hint sits outside and below it exactly as
     * the title sits outside and above.
     *
     * Every group gap falls above the LAST row by construction (an opener at index 0 is not
     * counted — see [groupGapsIn]), so `gapsBefore` here is the full [gapCount].
     *
     * [rowsTop] IS FORWARDED TO [rowY] AND MUST BE, which is why it is repeated here rather
     * than left to the default. This function calls [rowY]; a version that did not take the
     * parameter would silently compute the LEADERBOARD panel's bottom edge as though its rows
     * started at [ROWS_TOP_Y] — i.e. it would cut the panel off part-way up its own row list,
     * and the hint legend ([hintY], which reads this) would land inside the panel it is
     * supposed to sit below. Both failures are cosmetic-looking and neither would fail a test
     * that only checks row-versus-row overlap.
     */
    fun rowsBottom(rowCount: Int, gapCount: Int, rowsTop: Float = ROWS_TOP_Y): Float =
        rowY(rowCount - 1, rowCount, gapCount, gapCount, rowsTop) + rowFontFor(rowCount, gapCount)

    // --- The LEADERBOARD page's board, ABOVE the row list ------------------------------------
    //
    // The board is DISPLAY, not rows: `MenuModel.itemsOn(LEADERBOARD)` is `[DELETE_BOARD, BACK]`
    // and nothing here is navigable (that object's own doc argues why an unbounded, run-count-
    // dependent list must not become navigation rows). So it needs vertical anchors of its own,
    // and they are the only thing this page adds — the three COLUMN anchors are reused verbatim
    // from `AttractLayout.rankX`/`initialsX`/`scoreX`, because a second set of column maths for
    // the same three columns is exactly the drift `AttractLayout.ROW_HALF_SPAN`'s own doc
    // records (three independently chosen width fractions that could not stay balanced).

    /** Top of the board's first entry row. The board occupies the top of the panel, so this is
     * [ROWS_TOP_Y] itself — on every other page that is where the rows start. */
    const val BOARD_TOP_Y = ROWS_TOP_Y

    /** The board's font and pitch are the attract screen's own, BY REFERENCE and not as copied
     * literals — this is the same object (a ranked list of initials and scores) drawn in a
     * different frame, and a player who has read one on the attract screen should recognise it
     * instantly here. Reading the constants rather than copying them is what makes "the same"
     * structural; see [titleY], which does it for the title for the same reason. */
    const val BOARD_FONT = AttractLayout.ROW_FONT

    /** See [BOARD_FONT] — baseline-to-baseline pitch as a multiple of it. */
    const val BOARD_LINE_SPACING = AttractLayout.ROW_LINE_SPACING

    /**
     * Clear water between the bottom of the board and the top of the row block, as a fraction
     * of screen height.
     *
     * 0.05 against a board pitch of `BOARD_FONT * BOARD_LINE_SPACING` = 0.042 — visibly MORE
     * than the spacing inside the board, which is the whole job and is asserted rather than
     * eyeballed (`MenuLayoutTest.the leaderboard's rows start below its board, by more than the
     * board's own pitch`; 0.04 was the first value tried and that test rejected it). The two
     * blocks are different kinds of thing — a read-only list, and two things you can press —
     * they share one panel, and the only signal separating them is this gap. At or under the
     * board's own pitch they read as one ten-row list whose last two rows happen to be
     * highlighted.
     */
    const val BOARD_GAP = 0.05f

    /** Top of board entry [index] (0-based), as a fraction of screen height. */
    fun boardRowY(index: Int): Float = BOARD_TOP_Y + BOARD_FONT * BOARD_LINE_SPACING * index

    /**
     * Where the board's lowest pixel lands, as a fraction of screen height.
     *
     * An EMPTY board still occupies one row: [ScreenText.MENU_BOARD_EMPTY] is drawn in its
     * place (a freshly wiped page that showed a heading and nothing at all reads as a broken
     * screen, not as an empty list), so `entryCount = 0` is deliberately floored to one row
     * rather than collapsing the block. That also keeps the panel from changing height by half
     * its own size at the instant the wipe fires.
     */
    fun boardBottom(entryCount: Int): Float = boardRowY(maxOf(entryCount, 1) - 1) + BOARD_FONT

    /** Top of the LEADERBOARD page's row block — pass to [rowY]/[rowsBottom]/[hintY] as their
     * `rowsTop`. */
    fun leaderboardRowsTopY(entryCount: Int): Float = boardBottom(entryCount) + BOARD_GAP

    // --- The DELETE BOARD row's hold progress bar --------------------------------------------

    /**
     * Where the delete-hold bar sits and how thick it is, both in EMS OF THE ROW'S OWN FONT and
     * both measured from the top of the DELETE row's text box — the same convention
     * [ROW_TEXT_INSET_EM] uses, and for the same reason: the row font shrinks with the row
     * count, so a fixed height fraction would collide with the next row on a long page and
     * float free on a short one.
     *
     * THE BAND THESE TWO NUMBERS HAVE TO LIVE IN IS NARROW AND IS WORTH WRITING DOWN. A row's
     * text box is `y .. y + f`; the next row's selection highlight starts at
     * `y + f * (ROW_SPACING - HIGHLIGHT_PAD_FRACTION)` = `y + 1.4f`. So the whole clear band
     * between this row's glyphs and the next row's highlight is 0.4 em tall. The bar takes the
     * middle half of it: top at 1.1 em, height 0.2 em, leaving 0.1 em of air above and below.
     * At the LEADERBOARD page's unshrunk 0.032h font on a 1200-tall screen that is a 7.7 px bar
     * — thin, but it is a meter that fills, and motion is what reads at a glance, not bulk.
     *
     * It is deliberately NOT drawn inside the highlight: the highlight's own vertical padding is
     * 0.3 em, so a 0.2 em bar placed there would overlap the label's baseline.
     */
    const val DELETE_BAR_TOP_EM = 1.1f

    /** See [DELETE_BAR_TOP_EM]. */
    const val DELETE_BAR_HEIGHT_EM = 0.2f

    /** Top of the delete-hold bar in PIXELS, given the DELETE row's own text-box top and font
     * size in pixels. */
    fun deleteBarY(rowTopPx: Float, rowFontPx: Float): Float = rowTopPx + rowFontPx * DELETE_BAR_TOP_EM

    /** Thickness of the delete-hold bar in PIXELS — see [DELETE_BAR_TOP_EM]. */
    fun deleteBarHeight(rowFontPx: Float): Float = rowFontPx * DELETE_BAR_HEIGHT_EM

    // --- The GRAPHICS page's two columns -----------------------------------------------------
    // Both LEFT-aligned, both measured from the panel's own left inner edge, both offset in EMS
    // of the row's own font. See this object's class doc for the 146px-ragged-left-edge
    // measurement that overrode the previous centre-inward split, and why the briefing keeps it.

    /**
     * How far the label column sits inside the selection bar's own left edge, in ems of the row
     * font — and the same inset is reserved on the right, which is what [valueLeftX]'s
     * derivation spends.
     *
     * 0.45 em is 17 px at the GRAPHICS page's 0.0269828h font on a 1440-tall screen. Small on
     * purpose: the bar is the frame, and text that starts a whole em inside it reads as floating
     * rather than as being in a column.
     */
    const val ROW_TEXT_INSET_EM = 0.45f

    /**
     * Where the value column starts, in ems of the row font, measured from the selection bar's
     * left edge.
     *
     * DERIVED, AND THE DERIVATION IS ASSERTED RATHER THAN TRUSTED: [ROW_TEXT_INSET_EM] (0.45)
     * plus the widest label on the page at this project's pessimistic glyph bound
     * (`"OFF-SCREEN RAYS"` / `"LIGHT MAP SCALE"`, 15 chars at EM = 0.62 =
     * `Hud.CLOCK_BOX_GLYPH_WIDTH_EM`, so 9.3 em) plus a 0.75 em gutter = 10.5.
     *
     * `MenuLayoutTest`'s `` `the label column never reaches the value column` `` re-derives that
     * from the REAL row list, so a label longer than fifteen characters fails the build instead
     * of overprinting a value. Real rendered advance measures ~0.452 em/char on the capture set,
     * so the gutter a player actually sees is wider than 0.75 em — that slack is the price of
     * having no text-measurement API anywhere in this project (see `PanelLayout`'s class doc),
     * and it is paid on the side where being wrong is only cosmetic.
     */
    const val VALUE_COLUMN_EM = 10.5f

    /** Left edge of the label column on [page], given the row's own [fontSize] IN PIXELS. Draw
     * with `xOrigin = 0`. */
    fun rowTextLeftX(centreX: Float, screenHeight: Float, page: MenuPage, fontSize: Float): Float =
        centreX - screenHeight * highlightHalfSpanFor(page) + fontSize * ROW_TEXT_INSET_EM

    /** Left edge of the value column on [page], given the row's own [fontSize] IN PIXELS. Draw
     * with `xOrigin = 0`. */
    fun valueLeftX(centreX: Float, screenHeight: Float, page: MenuPage, fontSize: Float): Float =
        centreX - screenHeight * highlightHalfSpanFor(page) + fontSize * VALUE_COLUMN_EM

    /**
     * Half the width of the selection highlight behind the current row, as a fraction of screen
     * HEIGHT — same reasoning as [AttractLayout]'s `ROW_HALF_SPAN`: a row's ink can run wider
     * than any single word in it once a value string is appended, so the highlight is sized to
     * the widest plausible row rather than to one word.
     *
     * PER PAGE SINCE 2026-08-31, AND THE SINGLE SHARED 0.20 IT REPLACES WAS A REAL, SHIPPED
     * DEFECT. Measured on shot 02 of the capture set (fullscreen 1920x1200, so centreX = 960 and
     * h = 1200): the highlight spanned x = 720..1199 while `OFF-SCREEN RAYS` — the widest label
     * on the page it serves — started at x = 710. THE FIRST GLYPH COLUMN OF THE LONGEST ROW FELL
     * OUTSIDE ITS OWN HIGHLIGHT. `MenuLayoutTest` did not catch it because it only ever checked
     * the highlight against the 4:3 panel and against the two column anchors, never against a
     * row's RENDERED WIDTH; the assertion whose absence let this through is now
     * `` `the selection highlight contains the widest row on every page` ``.
     *
     * Sized at the project's own pessimistic glyph bound, EM = 0.62 (`Hud.CLOCK_BOX_GLYPH_WIDTH_EM`
     * — erring high biases a span WIDE, and a too-wide highlight is cosmetic looseness while a
     * too-narrow one clips the thing it is supposed to frame):
     *
     *  - ROOT: 4 rows at the full [ROW_FONT], longest label `"LEADERBOARD"` (11 chars, centred)
     *    = 0.1091h half-width. 0.17 frames it with air either side.
     *  - GRAPHICS: 14 rows and 2 group gaps, so `rowFontFor(14, 2)` = 0.0269828h. The row is now
     *    a left-aligned pair, so its full width is one expression rather than a max of two
     *    sides: `ROW_TEXT_INSET_EM + VALUE_COLUMN_EM's own reach` —
     *    `(10.5 + 9 * 0.62 + 0.45) em = 16.53 em = 0.446026h`, i.e. **0.223013h half-width**.
     *    0.23 clears it by 0.014h.
     *
     * GRAPHICS NARROWED FROM 0.30 TO 0.23 ON 2026-08-31, AND THE 0.30 WAS NOT WRONG WHEN IT WAS
     * WRITTEN — it was derived against the CENTRE-INWARD column split (`0.018 + 15 * 0.62 *
     * 0.0285714 = 0.2837h`), which the class doc above has now overridden. Under left-aligned
     * columns the same content needs 0.223h, and leaving the span at 0.30 would have left
     * 0.154h — 222 px at h = 1440 — of empty plate to the right of every value on the page. That
     * is precisely the "118 px of dead panel right" the design doc measures as a defect, made
     * worse rather than better by the fix for the ragged left edge. Widening the panel is the
     * trap here, not the remedy.
     *
     * ROOT IS DELIBERATELY NARROWER THAN BOTH. The same constant also sets the PANEL's width at
     * both draw sites, so one span sized for a 14-row settings list would have made ROOT's four
     * short words sit in a letterbox. Caps that bound both values: `< 0.533333`
     * (`MenuLayoutTest`'s 4:3 margin check) and `< 0.641667` (`PanelLayoutTest`'s on-screen edge
     * sweep at 4:3).
     */
    const val HIGHLIGHT_HALF_SPAN_ROOT = 0.17f

    /** See [HIGHLIGHT_HALF_SPAN_ROOT] — the 14-row settings list's left-aligned columns need
     * 0.223013h at EM = 0.62. */
    const val HIGHLIGHT_HALF_SPAN_GRAPHICS = 0.23f

    /**
     * See [HIGHLIGHT_HALF_SPAN_ROOT] — sized by the DELETE row's LABEL, not by the board.
     *
     * The board drawn above the rows is the narrower of the two: its rank/initials/score columns
     * span `2 * AttractLayout.ROW_HALF_SPAN` = 0.28h, comfortably inside this. What sets the floor
     * is [ScreenText.MENU_DELETE_BOARD], which at 20 characters needs 0.198h at EM = 0.62 against
     * this page's unshrunk row font (two rows, so `rowScaleFor` returns 1) — wider than ROOT's
     * 0.17 and the reason this page cannot simply borrow it.
     */
    const val HIGHLIGHT_HALF_SPAN_LEADERBOARD = 0.23f

    /** The selection highlight's (and therefore the panel's) half-width on [page]. */
    fun highlightHalfSpanFor(page: MenuPage): Float = when (page)
    {
        MenuPage.ROOT -> HIGHLIGHT_HALF_SPAN_ROOT
        MenuPage.GRAPHICS -> HIGHLIGHT_HALF_SPAN_GRAPHICS
        MenuPage.LEADERBOARD -> HIGHLIGHT_HALF_SPAN_LEADERBOARD
    }

    /** Vertical padding added above and below a row's text box before drawing the selection
     * highlight, as a fraction of [ROW_FONT] — enough that the highlight visibly frames the
     * text rather than clipping its ascenders/descenders. */
    const val HIGHLIGHT_PAD_FRACTION = 0.30f

    /**
     * THE SELECTION BAR IS INVERTED, AND THIS IS THE ONE CHANGE ON THESE SCREENS THAT MUST NOT
     * BE "TIDIED" BACK. The bar used to be white at an authored 0.18, i.e. a pale wash, and
     * measured contrast ratios off the 2026-08-31 capture set say what that cost:
     *
     *                                     shot 01 (sunset)   shot 06 (deep)
     *   highlight bar vs panel                 2.04 : 1          5.56 : 1
     *   white text on the plain panel          7.05 : 1         19.7  : 1
     *   WHITE TEXT ON THE HIGHLIGHT BAR        3.46 : 1          3.54  : 1
     *
     * On BOTH screens the selected row was the LOWEST-CONTRAST TEXT ON THE PAGE. A bar that
     * exists to emphasise one row was degrading it from 7.05:1 to 3.46:1, below large-text AA,
     * and on shot 06 `CONTINUE` is visibly muddier than the unselected `GRAPHICS` under it. The
     * obvious repair — raise the bar's brightness so it reads more strongly — drives white-on-bar
     * DOWN, not up, and was rejected for that reason. Anything that makes this bar paler is the
     * same mistake wearing a different number.
     *
     * So the bar is a saturated MID-DARK BLUE instead. Blue rather than an invented accent
     * because `Hud.PLATE_R/G/B` are already (0.02, 0.05, 0.09) and their own doc gives the rule
     * — one plate family, not two — so this is that same hue amplified rather than a new colour
     * in the design (decision D4: no new visual language). Full white on it lands near 9:1 while
     * the bar still steps clearly off the plate, and with [UNSELECTED_INK] below the selected
     * row is now the brightest ink on the page as well as the one with a shape behind it.
     *
     * NO LEADING-EDGE ACCENT, either, and that is measured too: the bar spans x = 720..1199 on
     * shot 01 while `START DIVE` occupies roughly 878..1046, so a caret at the bar's left edge
     * would sit ~160 px from the nearest glyph and read as panel furniture. (On the GRAPHICS
     * page the rows are now left-aligned and a leading mark WOULD have something to lead — but
     * ROOT's are still centred, and one menu with two focus idioms is worse than neither.)
     */
    const val HIGHLIGHT_R = 0.10f
    const val HIGHLIGHT_G = 0.32f
    const val HIGHLIGHT_B = 0.52f

    /** Displayed alpha of the selection bar — pass through `Hud.authoredAlphaFor`. High, because
     * the bar is now carrying the emphasis the old pale wash failed to carry; see [HIGHLIGHT_R]. */
    const val HIGHLIGHT_ALPHA = 0.85f

    /**
     * White level for a row that is NOT selected — the secondary half of the focus cue, so that
     * the selected row wins on ink as well as on having a bar behind it.
     *
     * A *secondary* cue only: the bar is what says "here", and this must never become the only
     * difference between rows, because a player scanning a settings list still has to read the
     * ones they are not on. 0.72 is a step, not a fade.
     *
     * Consumed through `render.drawTextWithOutline`'s raw-float overload, NEVER by building a
     * `Color`: this is the per-frame draw path and that overload (see its doc) exists precisely
     * so an ink level does not have to allocate.
     */
    const val UNSELECTED_INK = 0.72f

    // --- The confirm/back legend, below the panel ---------------------------------------

    const val HINT_FONT = 0.026f

    /**
     * Gap between the PANEL'S OWN BOTTOM EDGE and the top of the hint legend's text box, as a
     * fraction of screen height. [hintY] adds [PanelLayout.PADDING_FRACTION] to this, because
     * the panel extends that far past the last row.
     */
    const val HINT_GAP = 0.02f

    /**
     * Top of the hint legend's text box for a page of [rowCount] rows and [gapCount] group gaps.
     *
     * A FUNCTION OF THE PANEL, NOT A CONSTANT, SINCE 2026-08-31 — AND THE OLD CONSTANT WAS
     * BROKEN ON BOTH PAGES AT ONCE, IN OPPOSITE DIRECTIONS. `HINT_Y = 0.90f` was one number
     * serving two unrelated compositions, and the capture set measures both failures on the
     * same build (h = 1440):
     *
     *  - ROOT: the panel ends at y = 634 and the legend sat at y = 1296 — **662 px and the whole
     *    ocean between them**. Nothing about that reads as "this line belongs to that menu"; it
     *    reads as two unrelated pieces of screen furniture.
     *  - GRAPHICS: the panel ends at y = 1303 and the legend sat at y = 1296, i.e. **the legend
     *    was drawn ON the panel's bottom border**, about 1 px clear of the ring added in the
     *    same pass. It reads as attached to the card, which is the opposite failure.
     *
     * So it tracks [rowsBottom] plus the panel's own padding plus [HINT_GAP]. On ROOT that puts
     * it at 0.4602h (just under a panel that ends at 0.4402h); on GRAPHICS at 0.9250h (under a
     * panel that ends at 0.9050h). The same 0.02h of clear water on both.
     *
     * THE CLAMP IS REAL BUT UNREACHABLE TODAY, and is written down rather than omitted for that
     * reason: [rowScaleFor] already guarantees `rowsBottom <= ROW_BUDGET_BOTTOM -
     * ROW_BLOCK_MARGIN = 0.88`, so the largest value this can return is
     * `0.88 + 0.025 + 0.02 = 0.925` and `+ HINT_FONT` is 0.951 — comfortably on screen. The
     * clamp exists so that a future change to the budget cannot push the legend off the bottom
     * of the screen SILENTLY; `MenuLayoutTest` asserts the on-screen property against the
     * computed value, so the guarantee is checked rather than merely stated.
     */
    fun hintY(rowCount: Int, gapCount: Int, rowsTop: Float = ROWS_TOP_Y): Float =
        (rowsBottom(rowCount, gapCount, rowsTop) + PanelLayout.PADDING_FRACTION + HINT_GAP)
            .coerceAtMost(1f - HINT_FONT - HINT_GAP)
}
