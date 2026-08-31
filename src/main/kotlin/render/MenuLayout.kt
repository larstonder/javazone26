package render

/**
 * Geometry for the main menu. Modelled directly on the attract screen's and the pause screen's
 * own layout objects (`AttractLayout`, `PauseLayout`, `BriefingLayout` — all in `EnPustTil.kt`,
 * the default package, which is why this doc names rather than links them: a named package
 * cannot import a default-package type, so there is no cross-file reference to draw here even
 * though the pattern is copied wholesale).
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
 * THE COLUMN SPLIT ON THE GRAPHICS PAGE IS INWARD, copied from `BriefingLayout.COLUMN_GAP`
 * rather than from `AttractLayout`'s outward leaderboard columns: the label is right-aligned at
 * `centreX - COLUMN_GAP * h` and the value left-aligned at `centreX + COLUMN_GAP * h`, so both
 * keep a clean edge against the centre line whatever their own width — which matters here for
 * the same reason it does on the briefing screen, because a value string's width varies
 * (`"1920x1200"` versus `"UNCAPPED"`).
 */
object MenuLayout
{
    // --- The title, above the row list -----------------------------------------------------

    const val TITLE_Y = 0.10f
    const val TITLE_FONT = 0.06f

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
     * Breathing room kept between the last row's bottom edge and [HINT_Y] — a page whose rows
     * exactly fill their budget should still read as a separate block from the hint legend
     * below it, not touch it.
     */
    const val ROW_BLOCK_MARGIN = 0.02f

    /**
     * THE LAYOUT PROBLEM, SOLVED AS ARITHMETIC RATHER THAN A SECOND SET OF CONSTANTS. The
     * GRAPHICS page went from 8 rows to 14 when the six GI knobs landed (gi-knobs-brief.md).
     * At the base [ROW_FONT]/[ROW_SPACING], 14 rows end at `0.22 + 13*0.0544 + 0.032 = 0.959h` —
     * past [HINT_Y] (0.90h) and off the bottom of the screen; ROOT's 4 rows end at a comfortable
     * 0.42h.
     *
     * Rather than hand-picking a second, smaller font for "the long page" (which silently goes
     * stale the next time a row is added or removed — nothing would fail the build, the page
     * would just quietly start clipping again), this computes how much smaller than [ROW_FONT]
     * a [rowCount]-row page's rows need to be to fit the fixed vertical budget between
     * [ROWS_TOP_Y] and [HINT_Y] (minus [ROW_BLOCK_MARGIN]), and returns 1f — no shrink at all —
     * whenever the page already fits at the base size. That is why ROOT is untouched: its
     * 4-row `baseHeight` (0.1952h) is far under the 0.66h budget, so [rowScaleFor] returns 1f
     * and every ROOT anchor is byte-identical to before this function existed. GRAPHICS' 14-row
     * `baseHeight` (0.7392h) exceeds the budget, so its rows shrink to `budget / baseHeight` ≈
     * 0.893 of [ROW_FONT] — about 0.0286h, roughly 10% smaller, chosen by the arithmetic rather
     * than by eye. [MenuLayoutTest] asserts the general property (every page's rows end above
     * [HINT_Y]) rather than pinning today's two specific row counts, so a future row added or
     * removed to either page is caught by the same test without anyone updating a literal here.
     */
    private fun baseHeight(rowCount: Int): Float = (rowCount - 1) * ROW_FONT * ROW_SPACING + ROW_FONT

    /** See [baseHeight]'s doc — 1f (no shrink) for a page that already fits, otherwise the
     * fraction of [ROW_FONT] a [rowCount]-row page's rows must shrink to. */
    fun rowScaleFor(rowCount: Int): Float
    {
        val budget = HINT_Y - ROW_BLOCK_MARGIN - ROWS_TOP_Y
        val needed = baseHeight(rowCount)
        return if (needed <= budget) 1f else budget / needed
    }

    /** The row font size for a page of [rowCount] rows — [ROW_FONT] scaled by [rowScaleFor]. */
    fun rowFontFor(rowCount: Int): Float = ROW_FONT * rowScaleFor(rowCount)

    /** Top of row [index] (0-based) on a page of [rowCount] rows, as a fraction of screen
     * height — both are needed because the row's own font size (and therefore its pitch) is a
     * function of how many rows share the page, see [rowFontFor]. */
    fun rowY(index: Int, rowCount: Int): Float = ROWS_TOP_Y + rowFontFor(rowCount) * ROW_SPACING * index

    /**
     * Where the last of [rowCount] rows ENDS, as a fraction of screen height — the bottom of the
     * block the panel has to contain.
     *
     * Exists because anchoring the panel to [HINT_Y] instead, as it was first written, made the
     * panel the same height on both pages by spanning all the way to the hint line — leaving ROOT
     * (four rows, ending near 0.42h) with roughly half a screen of empty dark box below its last
     * item. Uniformity between the two pages is not worth that: the panel is there to back the
     * text, so it should end where the text does, and the hint sits outside and below it exactly
     * as the title sits outside and above.
     */
    fun rowsBottom(rowCount: Int): Float = rowY(rowCount - 1, rowCount) + rowFontFor(rowCount)

    // --- The GRAPHICS page's two columns -----------------------------------------------------
    // Half the gutter between label and value, exactly BriefingLayout.COLUMN_GAP's shape (see
    // this object's class doc) rather than AttractLayout's outward span: the row reads
    // "QUALITY .......... HIGH" with a predictable gap at the centre line, not two blocks
    // straddling the edges of a fixed-width row.

    const val COLUMN_GAP = 0.018f

    /** Right edge of the label column. Draw with `xOrigin = 1`. */
    fun labelX(centreX: Float, screenHeight: Float): Float = centreX - screenHeight * COLUMN_GAP

    /** Left edge of the value column. Draw with `xOrigin = 0`. */
    fun valueX(centreX: Float, screenHeight: Float): Float = centreX + screenHeight * COLUMN_GAP

    /**
     * Half the width of the selection highlight behind the current row, as a fraction of screen
     * HEIGHT — same reasoning as [AttractLayout]'s `ROW_HALF_SPAN` (unlinkable here for the
     * package reason above, but it is the same idea): a row's ink can run wider than any single
     * word in it once a value string is appended, so the highlight is sized to the widest
     * plausible row rather than to one word. Checked against the narrowest booth panel this
     * game could be shown on (4:3) in `MenuLayoutTest`.
     */
    const val HIGHLIGHT_HALF_SPAN = 0.20f

    /** Vertical padding added above and below a row's text box before drawing the selection
     * highlight, as a fraction of [ROW_FONT] — enough that the highlight visibly frames the
     * text rather than clipping its ascenders/descenders. */
    const val HIGHLIGHT_PAD_FRACTION = 0.30f

    // --- The confirm/back legend, bottom of screen ---------------------------------------

    const val HINT_Y = 0.90f
    const val HINT_FONT = 0.026f
}
