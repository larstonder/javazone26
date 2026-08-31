package render

import AttractLayout
import ScreenText
import kotlin.test.Test
import kotlin.test.assertEquals
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
 *
 * PAGES, NOT ROW COUNTS, since 2026-08-31. This class used to carry two hand-maintained
 * literals (`longestPage = 14`, `shortestPage = 4`) with a comment claiming MenuModelTest's own
 * row-count assertion kept them honest. It does not - MenuModelTest has no such guard - and the
 * literals went stale once before, when the six GI knobs took the graphics page from 8 rows to
 * 14. Every case below now sweeps `MenuPage.entries` and reads the REAL row list out of
 * MenuModel, so a page added or a row moved cannot leave a case describing a page that no
 * longer exists.
 */
class MenuLayoutTest
{
    private val model = MenuModel()

    private fun rowsOn(page: MenuPage) = model.itemsOn(page)

    /** Exactly what `EnPustTil.drawMainMenu` computes for the page before drawing a row. */
    private fun gapsOn(page: MenuPage) = MenuLayout.groupGapsIn(rowsOn(page))

    /**
     * Where the page's row block starts - the `rowsTop` argument `EnPustTil.drawMainMenu` hands
     * rowY/rowsBottom/hintY, mirrored here.
     *
     * ONLY THE LEADERBOARD PAGE DIFFERS, and this helper exists precisely so the cases below do
     * not quietly check a geometry production never draws. Its board sits above its rows, so
     * its row block starts most of a screen lower than everywhere else; a sweep that passed the
     * default would be asserting the panel and the legend of a page that does not exist. The
     * FULL board (AttractLayout.LEADERBOARD_SIZE) is the worst case - the page's rows are at
     * their lowest with a full board, so every fit assertion binds there.
     */
    private fun rowsTopOn(page: MenuPage) = when (page)
    {
        MenuPage.LEADERBOARD -> MenuLayout.leaderboardRowsTopY(AttractLayout.LEADERBOARD_SIZE)
        else -> MenuLayout.ROWS_TOP_Y
    }

    // An upper bound on a glyph's advance in the default font, as a multiple of font height -
    // the same estimate Hud.CLOCK_BOX_GLYPH_WIDTH_EM, PanelLayoutTest and BriefingScreenTest all
    // use, and erring high for the same reason. See the widest-row test for what that buys.
    private val EM = 0.62f

    /**
     * The row labels EnPustTil.menuItemLabel draws, hand-copied because that function is a
     * PRIVATE method of the engine shell - default package, on an object that cannot be
     * constructed without a GL context - so this test cannot call it. Same duplication
     * PanelLayoutTest already accepts for its RunOverAnchors/InitialsAnchors, and narrowed the
     * same way: `this test's copy of the row labels covers every menu row` asserts it complete
     * against MenuItemId.entries, so a new row cannot silently escape the width check.
     *
     * START_DIVE carries the LONGER of its two labels: the row reads CONTINUE while a run is
     * held behind the menu and START DIVE otherwise, and the highlight has to frame both.
     */
    private val rowLabels: Map<MenuItemId, String> = mapOf(
        MenuItemId.START_DIVE to ScreenText.MENU_START_DIVE,   // longer than MENU_CONTINUE
        MenuItemId.GRAPHICS to ScreenText.MENU_GRAPHICS,
        MenuItemId.LEADERBOARD to ScreenText.MENU_LEADERBOARD,
        MenuItemId.QUIT to ScreenText.MENU_QUIT,
        MenuItemId.QUALITY to ScreenText.MENU_QUALITY,
        MenuItemId.LIGHT_MAP_SCALE to ScreenText.MENU_LIGHT_MAP_SCALE,
        MenuItemId.SCENE_SCALE to ScreenText.MENU_SCENE_SCALE,
        MenuItemId.GLOBAL_SCALE to ScreenText.MENU_GLOBAL_SCALE,
        MenuItemId.MAX_CASCADES to ScreenText.MENU_MAX_CASCADES,
        MenuItemId.RAY_QUALITY to ScreenText.MENU_RAY_QUALITY,
        MenuItemId.OFF_SCREEN_RAYS to ScreenText.MENU_OFF_SCREEN_RAYS,
        MenuItemId.RESOLUTION to ScreenText.MENU_RESOLUTION,
        MenuItemId.RENDER_SCALE to ScreenText.MENU_RENDER_SCALE,
        MenuItemId.FULLSCREEN to ScreenText.MENU_FULLSCREEN,
        MenuItemId.FRAME_CAP to ScreenText.MENU_FRAME_CAP,
        MenuItemId.VSYNC to ScreenText.MENU_VSYNC,
        MenuItemId.SHOW_FPS to ScreenText.MENU_SHOW_FPS,
        MenuItemId.BACK to ScreenText.MENU_BACK,
        MenuItemId.DELETE_BOARD to ScreenText.MENU_DELETE_BOARD
    )

    /**
     * The widest string the GRAPHICS page's VALUE column can hold, as EnPustTil.
     * rebuildMenuValueHints composes them. A resolution is `"${w}x${h}"`, nine characters at
     * every mode this game will meet (1920x1200, 2560x1440, 3840x2160); the rest are shorter -
     * ScreenText.MENU_UNCAPPED and the longest quality preset are eight, a GI knob's "%.2f" is
     * four, a render scale's percentage is four, ON/OFF are three or fewer.
     *
     * A literal rather than a read of the real strings because they are RUNTIME-formatted from
     * live GameSettings and there is nothing compile-time to sweep. Deliberately the pessimistic
     * end of that list, same principle as EM above.
     */
    private val WIDEST_VALUE = "1920x1200"

    @Test
    fun `no two rows overlap, on either page`() {
        for (page in MenuPage.entries)
        {
            val n = rowsOn(page).size
            val gaps = gapsOn(page)
            // Both rows at the SAME gapsBefore, which is the tightest possible pair: a group
            // gap between them only ever pushes the lower one FURTHER down (asserted separately
            // below), so checking the ungapped pitch is the pessimistic case and the one that
            // would actually collide.
            for (i in 0 until n - 1)
            {
                val top = MenuLayout.rowY(i, n, gaps, 0)
                val next = MenuLayout.rowY(i + 1, n, gaps, 0)
                assertTrue(top + MenuLayout.rowFontFor(n, gaps) <= next,
                    "row $i overlaps row ${i + 1} on $page")
            }
        }
    }

    @Test
    fun `a group gap pushes the rows below it down and never up`() {
        // The gap is the whole point of the grouping (MenuLayout.GROUP_OPENERS): if `gapsBefore`
        // did not move a row, the two blocks on the GRAPHICS page would be indistinguishable and
        // nothing else here would notice - every other case would still pass. It also pins the
        // SIGN, which is the one thing a fat-fingered edit to rowY could invert while leaving
        // the overlap sweep above happy (a negative gap pulls rows UP, which still does not
        // overlap the row above it until it passes clean through).
        val page = MenuPage.GRAPHICS
        val n = rowsOn(page).size
        val gaps = gapsOn(page)
        assertTrue(gaps > 0, "the GRAPHICS page has no group gaps at all - grouping is not wired")

        for (i in 0 until n)
            assertTrue(
                MenuLayout.rowY(i, n, gaps, 1) > MenuLayout.rowY(i, n, gaps, 0),
                "a group gap above row $i did not move it down"
            )
    }

    @Test
    fun `the row block's height accounts for the group gaps, so a grouped page still fits`() {
        // THE TRAP THIS EXISTS FOR, stated in MenuLayout.baseHeight's own doc: if `rowY` learns
        // the gaps and `baseHeight` does not, the font is computed for an ungapped page and the
        // rows are drawn for a gapped one. rowsBottom(14) then lands at 0.918857 - past
        // ROW_BUDGET_BOTTOM and clipping - while every overlap check still passes, because each
        // row is still exactly one pitch below the last.
        for (page in MenuPage.entries)
        {
            val n = rowsOn(page).size
            val gaps = gapsOn(page)
            // rowsTopOn, not the default: the LEADERBOARD page's rows start below its board and
            // this is the one case where that has to bind, because a board tall enough to push
            // its two rows past the budget is exactly the failure this test names.
            val bottom = MenuLayout.rowsBottom(n, gaps, rowsTopOn(page))
            assertTrue(
                bottom <= MenuLayout.ROW_BUDGET_BOTTOM - MenuLayout.ROW_BLOCK_MARGIN + 1e-4f,
                "$page's rows end at $bottom, past the budget " +
                    "(${MenuLayout.ROW_BUDGET_BOTTOM} - ${MenuLayout.ROW_BLOCK_MARGIN})"
            )
            // And the gaps are actually paid for, not merely survived: a page WITH gaps must
            // have a smaller font than the same page pretending it has none.
            if (gaps > 0)
                assertTrue(
                    MenuLayout.rowFontFor(n, gaps) < MenuLayout.rowFontFor(n, 0),
                    "$page's font did not shrink to pay for its $gaps group gaps"
                )
        }
    }

    @Test
    fun `the group-gap count agrees with the openers actually on the page`() {
        // groupGapsIn and the draw loop's own running counter are two separate walks of the same
        // list (see EnPustTil.drawMainMenu), and they must agree or the font is computed for one
        // layout while the rows are placed by another. Re-derived here the third way.
        for (page in MenuPage.entries)
        {
            val items = rowsOn(page)
            var expected = 0
            for (i in 1 until items.size) if (MenuLayout.opensGroup(items[i])) expected++
            assertEquals(expected, MenuLayout.groupGapsIn(items), "$page's gap count disagrees")
        }
    }

    @Test
    fun `an opener at the very top of a page contributes no gap`() {
        // A gap ABOVE the first row is not a separator, it is the whole block moved down - and
        // the draw loop excludes index 0 for that reason. If groupGapsIn counted it and the draw
        // loop did not, the font would shrink to pay for a gap nothing ever draws.
        val opener = MenuPage.entries
            .flatMap { rowsOn(it) }
            .first { MenuLayout.opensGroup(it) }

        assertEquals(0, MenuLayout.groupGapsIn(listOf(opener, MenuItemId.BACK)))
        assertEquals(1, MenuLayout.groupGapsIn(listOf(MenuItemId.BACK, opener)))
    }

    @Test
    fun `both title variants clear the first row, on every page`() {
        // Two variants since 2026-08-31: the front page (ROOT, no run held) wears the ATTRACT
        // screen's own title anchors so the string does not shrink 1.52x and drop on the single
        // press that opens the menu; every other state wears the smaller page header at the
        // raised MenuLayout.TITLE_Y that clears the in-run timer capsule. Both have to clear the
        // row list, and they clear it by different margins.
        for (page in MenuPage.entries)
            for (runHeld in listOf(false, true))
            {
                val n = rowsOn(page).size
                val bottom = MenuLayout.titleY(page, runHeld) + MenuLayout.titleFont(page, runHeld)
                assertTrue(
                    bottom <= MenuLayout.rowY(0, n, gapsOn(page), 0),
                    "the title runs into the first row on $page (runHeld=$runHeld): " +
                        "title ends at $bottom"
                )
            }
    }

    @Test
    fun `the front page's title is the attract screen's title, not a copy of its numbers`() {
        // THE POP THIS KILLS, measured on the 2026-08-31 capture set at h = 1440: the attract
        // title's cap height is 64px and the menu title's 42px, so pressing SPACE shrank the
        // identical string ONE MORE BREATH by 1.52x and dropped it, on the first transition any
        // player sees. This asserts the anchors are the SAME OBJECT rather than two literals
        // that happen to agree today - MenuLayout reads AttractLayout directly, so the only way
        // this can fail is if someone reintroduces a copy.
        assertEquals(AttractLayout.TITLE_Y, MenuLayout.titleY(MenuPage.ROOT, runHeld = false))
        assertEquals(AttractLayout.TITLE_FONT, MenuLayout.titleFont(MenuPage.ROOT, runHeld = false))

        // ...and it is ONLY the front page. A run held behind the menu must NOT use the attract
        // anchors: at 0.085 + 0.09 the title would sit across the in-run timer capsule, which
        // occupies 0.020h..0.100h on the same centre line.
        assertTrue(
            MenuLayout.titleY(MenuPage.ROOT, runHeld = true) > MenuLayout.titleY(MenuPage.ROOT, runHeld = false),
            "the paused title did not drop clear of the run-timer capsule"
        )
        assertTrue(
            MenuLayout.titleY(MenuPage.GRAPHICS, runHeld = false) > AttractLayout.TITLE_Y,
            "the graphics page is not the game's front page and must not wear its title"
        )
    }

    @Test
    fun `the page-header title clears the in-run timer capsule`() {
        // The capsule is Hud's clock box: CLOCK_FONT_FRACTION 0.05 with CLOCK_BOX_PAD_Y_EM 0.30
        // above and below a full em gives a 0.08h box, and MARGIN_FRACTION 0.02 puts its top
        // there - so it occupies 0.020h..0.100h, centred on w*0.5 exactly as the title is. At
        // the old TITLE_Y of 0.10 the two touched with a zero-pixel gap.
        val capsuleBottom = 0.02f + 0.05f * (1f + 2f * 0.30f)
        assertTrue(
            MenuLayout.TITLE_Y > capsuleBottom,
            "the page-header title (${MenuLayout.TITLE_Y}) starts inside the run-timer capsule " +
                "(which ends at $capsuleBottom)"
        )
    }

    @Test
    fun `the page-header title is the largest anchor the panel's own padding will absorb`() {
        // MenuLayout.TITLE_Y's doc claims 0.135 is the LARGEST value with no side effect,
        // because drawMainMenu passes `minTop = h * (titleY + titleFont)` and the panel's padded
        // content top is ROWS_TOP_Y - PADDING_FRACTION. Equal is fine (the clamp picks the
        // padding); above it, the panel starts eating its own padding and the claim is false.
        val paddedContentTop = MenuLayout.ROWS_TOP_Y - PanelLayout.PADDING_FRACTION
        assertTrue(
            MenuLayout.TITLE_Y + MenuLayout.TITLE_FONT <= paddedContentTop + 1e-5f,
            "the page-header title ends at ${MenuLayout.TITLE_Y + MenuLayout.TITLE_FONT}, below " +
                "the panel's padded content top ($paddedContentTop) - the panel now clamps into " +
                "its own padding"
        )
    }

    @Test
    fun `the hint legend clears the panel below it, and stays on screen, on every page`() {
        // ADDITION B / Task 6. One constant (HINT_Y = 0.90f) used to serve two unrelated
        // compositions and got both wrong on the same build, measured at h = 1440: 662px of
        // open ocean under ROOT's four-row panel, and - on GRAPHICS - the legend drawn ON the
        // panel's bottom border, about a pixel clear of the ring. The panel's own bottom is
        // rowsBottom + PADDING_FRACTION (the border ring is stroked INSIDE that extent, so it
        // is the outer edge), which is what the legend has to clear.
        for (page in MenuPage.entries)
        {
            val n = rowsOn(page).size
            val gaps = gapsOn(page)
            val rowsTop = rowsTopOn(page)
            val panelBottom = MenuLayout.rowsBottom(n, gaps, rowsTop) + PanelLayout.PADDING_FRACTION
            val hintTop = MenuLayout.hintY(n, gaps, rowsTop)

            assertTrue(
                hintTop > panelBottom,
                "$page's hint legend ($hintTop) is drawn on or above its panel's bottom edge " +
                    "($panelBottom)"
            )
            assertTrue(
                hintTop + MenuLayout.HINT_FONT < 1f,
                "$page's hint legend ends at ${hintTop + MenuLayout.HINT_FONT}, off the bottom " +
                    "of the screen"
            )
        }
    }

    @Test
    fun `the hint legend follows its own panel rather than sitting at one fixed height`() {
        // The relationship, not the values: a four-row page's legend must sit HIGHER than a
        // fourteen-row page's, because it is tracking a panel that ends higher. A future edit
        // that quietly pins hintY back to a constant passes every other case in this file and
        // fails exactly here.
        val short = MenuPage.ROOT
        val long = MenuPage.GRAPHICS
        val shortHint = MenuLayout.hintY(rowsOn(short).size, gapsOn(short))
        val longHint = MenuLayout.hintY(rowsOn(long).size, gapsOn(long))

        assertTrue(
            shortHint < longHint,
            "the 4-row page's legend ($shortHint) does not sit above the 14-row page's " +
                "($longHint) - hintY is not tracking the panel"
        )
    }

    @Test
    fun `the two column anchors are left-aligned, in order, and inside the highlight`() {
        // TASK 7's core change, and the assertion that pins its DIRECTION. Both columns are now
        // left-aligned at fixed offsets from the panel's own left inner edge - the label column
        // no longer moves with the label's own length, which is what produced the measured 146px
        // of ragged left margin across the fourteen graphics rows. Swept across aspects the way
        // the old inward-split test was, because aspect enters through centreX exactly as it
        // does at the draw site.
        val h = 1200f
        val page = MenuPage.GRAPHICS
        val fontSize = h * MenuLayout.rowFontFor(rowsOn(page).size, gapsOn(page))

        for (aspect in listOf(4f / 3f, 16f / 10f, 16f / 9f, 2.389f, 32f / 9f))
        {
            val w = h * aspect
            val centreX = w * 0.5f
            val highlightLeft = centreX - h * MenuLayout.highlightHalfSpanFor(page)
            val highlightRight = centreX + h * MenuLayout.highlightHalfSpanFor(page)
            val labelLeft = MenuLayout.rowTextLeftX(centreX, h, page, fontSize)
            val valueLeft = MenuLayout.valueLeftX(centreX, h, page, fontSize)

            assertTrue(labelLeft > highlightLeft, "the label column starts outside its own highlight at aspect $aspect")
            assertTrue(labelLeft < valueLeft, "the value column is not right of the label column at aspect $aspect")
            assertTrue(valueLeft < highlightRight, "the value column starts outside the highlight at aspect $aspect")
            assertTrue(labelLeft > 0f, "the label column ran off the left edge at aspect $aspect")
            assertTrue(valueLeft < w, "the value column ran off the right edge at aspect $aspect")
        }
    }

    @Test
    fun `the label column never reaches the value column`() {
        // MenuLayout.VALUE_COLUMN_EM is DERIVED - the label inset plus the widest label at
        // EM = 0.62 plus a gutter - and a derivation written into a doc comment is exactly the
        // thing that goes stale when a seventeen-character label is added. Re-derived here from
        // the REAL row list, so the build fails instead of the value overprinting the label.
        val page = MenuPage.GRAPHICS
        for (item in rowsOn(page))
        {
            if (item == MenuItemId.BACK) continue     // centred action row, no columns
            val label = rowLabels.getValue(item)
            val reach = MenuLayout.ROW_TEXT_INSET_EM + label.length * EM
            assertTrue(
                reach <= MenuLayout.VALUE_COLUMN_EM,
                "\"$label\" reaches ${reach}em from the panel's left edge but the value column " +
                    "starts at ${MenuLayout.VALUE_COLUMN_EM}em - the two would overprint"
            )
        }
    }

    @Test
    fun `the selection highlight fits the narrowest booth panel it could be shown on`() {
        // MenuLayout's column anchors place both columns as offsets from the panel's own edge,
        // which is what the highlight half-spans are sized against - but sizing a
        // height-relative span against nothing is not itself a guarantee it fits a real panel.
        // 4:3 is the squarest aspect this booth might show the menu on (AttractScreenTest makes
        // the same check for the leaderboard row); if the highlight fits there with margin, it
        // fits every wider aspect too. This is a real invariant, not a restatement of the
        // constant: raising either half-span far enough makes it fail.
        val h = 1200f
        val narrowestWidth = h * 4f / 3f

        for (page in MenuPage.entries) {
            val highlightWidth = MenuLayout.highlightHalfSpanFor(page) * 2f * h
            assertTrue(
                highlightWidth < narrowestWidth * 0.8f,
                "$page's ${highlightWidth}px selection highlight leaves no margin on a ${narrowestWidth}px-wide 4:3 panel"
            )
        }
    }

    @Test
    fun `the selection highlight contains the widest row on every page`() {
        // THE ASSERTION WHOSE ABSENCE SHIPPED A DEFECT. Measured on shot 02 of the 2026-08-31
        // capture set (fullscreen 1920x1200): the highlight spanned x = 720..1199 while the
        // GRAPHICS page's widest label, "OFF-SCREEN RAYS", started at x = 710 - its first glyph
        // column drawn OUTSIDE the bar meant to frame it. Nothing here caught that, because the
        // two existing highlight tests check it against the 4:3 panel and against the column
        // anchors, and neither of those knows how wide a ROW actually renders.
        //
        // EM = 0.62 is this project's own pessimistic upper bound on a glyph's advance
        // (Hud.CLOCK_BOX_GLYPH_WIDTH_EM; PanelLayoutTest and BriefingScreenTest re-derive the
        // same value for the same kind of check). Erring high is right here for the usual
        // reason: it biases the span WIDE, and a too-wide highlight is cosmetic looseness while
        // a too-narrow one clips the row it exists to frame. Real rendered widths are narrower -
        // "OFF-SCREEN RAYS" measured ~0.452 em/char on the capture - so passing this leaves real
        // margin on top of the margin.
        //
        // REWRITTEN FOR LEFT-ALIGNED COLUMNS (Task 7). A graphics row's ink is no longer two
        // blocks measured outward from the centre line; it is one run starting at the label
        // column, so what has to fit is its RIGHT edge against the highlight's right edge.
        val h = 1200f

        for (page in MenuPage.entries) {
            val items = rowsOn(page)
            val gaps = gapsOn(page)
            val fontSize = MenuLayout.rowFontFor(items.size, gaps) * h
            val halfSpan = MenuLayout.highlightHalfSpanFor(page) * h
            val centreX = 0f    // measuring offsets from centre, not an absolute screen position

            for (item in items) {
                val label = rowLabels.getValue(item)
                // Exactly drawMainMenu's own branch: a GRAPHICS setting row is a label and a
                // value both LEFT-aligned at their own columns, so the row's right edge is the
                // value column plus the widest value; every other row is one centred string,
                // reaching half its own width either way.
                val right = if (page == MenuPage.GRAPHICS && item != MenuItemId.BACK)
                    maxOf(
                        MenuLayout.rowTextLeftX(centreX, h, page, fontSize) + label.length * EM * fontSize,
                        MenuLayout.valueLeftX(centreX, h, page, fontSize) + WIDEST_VALUE.length * EM * fontSize
                    )
                else
                    label.length * EM * fontSize * 0.5f

                assertTrue(
                    right <= halfSpan,
                    "\"$label\" on $page reaches ${right}px from centre but the highlight only " +
                        "spans ${halfSpan}px - its outer glyphs fall outside their own selection bar"
                )
            }
        }
    }

    // --- The LEADERBOARD page's board -------------------------------------------------------

    @Test
    fun `the leaderboard's rows start below its board, by more than the board's own pitch`() {
        // THE WHOLE REASON rowY GAINED A rowsTop PARAMETER. If the rows did not move down, the
        // two of them would be drawn ON the board's first two entries - and nothing else in
        // this file would notice, because every other case is about rows-versus-rows.
        //
        // The margin is asserted, not just the order: the board and the rows share one panel
        // and the ONLY thing separating a read-only list from two things you can press is the
        // gap between them. At the board's own pitch they would read as one ten-row list whose
        // last two rows happen to be highlighted, which is the failure MenuLayout.BOARD_GAP's
        // doc describes.
        val boardPitch = MenuLayout.BOARD_FONT * MenuLayout.BOARD_LINE_SPACING

        for (entries in 0..AttractLayout.LEADERBOARD_SIZE)
        {
            val boardBottom = MenuLayout.boardBottom(entries)
            val rowsTop = MenuLayout.leaderboardRowsTopY(entries)
            assertTrue(
                rowsTop - boardBottom > boardPitch,
                "with $entries entries the row block starts $rowsTop, only ${rowsTop - boardBottom} " +
                    "below the board's last pixel ($boardBottom) - closer than the board's own " +
                    "$boardPitch pitch, so the two blocks read as one list"
            )
        }
    }

    @Test
    fun `an empty board still reserves a row, so the page does not change height as it is wiped`() {
        // ScreenText.MENU_BOARD_EMPTY is drawn where the entries would be, so boardBottom(0)
        // has to reserve exactly the one row it occupies rather than collapsing to nothing. If
        // it collapsed, the panel would jump upward by a whole row at the instant the wipe
        // fires - on the one screen where the player is watching for the wipe to have worked.
        assertEquals(MenuLayout.boardBottom(1), MenuLayout.boardBottom(0),
            "an empty board does not reserve the row MENU_BOARD_EMPTY is drawn in")
    }

    @Test
    fun `no board entry overlaps the next, and the board never reaches the rows`() {
        val fullBoard = AttractLayout.LEADERBOARD_SIZE
        for (i in 0 until fullBoard - 1)
            assertTrue(
                MenuLayout.boardRowY(i) + MenuLayout.BOARD_FONT <= MenuLayout.boardRowY(i + 1),
                "board entry $i overlaps entry ${i + 1}"
            )

        assertEquals(
            MenuLayout.boardRowY(fullBoard - 1) + MenuLayout.BOARD_FONT,
            MenuLayout.boardBottom(fullBoard),
            "boardBottom does not agree with the last entry it is derived from"
        )
    }

    @Test
    fun `the board's columns and its empty-state line both fit inside the leaderboard panel`() {
        // The board reuses AttractLayout's three column anchors verbatim (see
        // EnPustTil.drawMenuBoard), and those are sized for the ATTRACT screen's panel, not
        // this one. So the containment has to be checked here rather than assumed: the rank
        // column's left edge and the score column's right edge are +/- ROW_HALF_SPAN from the
        // centre, and MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD is what the menu panel is
        // built from. Its own doc claims the board fits "comfortably inside"; this is the
        // assertion behind that claim.
        assertTrue(
            AttractLayout.ROW_HALF_SPAN < MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD,
            "the board's columns (+/-${AttractLayout.ROW_HALF_SPAN}h) run outside the leaderboard " +
                "panel (+/-${MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD}h)"
        )

        // And the empty-state line, which is a centred string rather than three columns, so its
        // width is the one thing that could exceed the panel without the columns doing so.
        val emptyHalfWidth = ScreenText.MENU_BOARD_EMPTY.length * EM * MenuLayout.BOARD_FONT * 0.5f
        assertTrue(
            emptyHalfWidth < MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD,
            "\"${ScreenText.MENU_BOARD_EMPTY}\" needs ${emptyHalfWidth}h either side of centre, " +
                "wider than the ${MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD}h panel it is drawn on"
        )
    }

    @Test
    fun `the delete-hold bar sits between its own row and the next, touching neither`() {
        // The bar is drawn under the DELETE BOARD row while a hold is running
        // (EnPustTil.drawMainMenu). The band it has to live in is narrow and is entirely
        // determined by three constants that have nothing to do with it - ROW_SPACING,
        // HIGHLIGHT_PAD_FRACTION and the row font - so any of them moving can put the bar
        // through the label above it or under the BACK row's highlight, invisibly in a diff and
        // invisibly in a still frame at rest (the bar is only drawn mid-hold).
        //
        // Worked in ems of the row font, which is the unit the bar is expressed in.
        val page = MenuPage.LEADERBOARD
        val n = rowsOn(page).size
        val gaps = gapsOn(page)
        val font = MenuLayout.rowFontFor(n, gaps)

        val rowTop = MenuLayout.rowY(0, n, gaps, 0, rowsTopOn(page))
        val rowTextBottom = rowTop + font
        // The BACK row's selection highlight starts HIGHLIGHT_PAD_FRACTION above its text.
        val nextHighlightTop = MenuLayout.rowY(1, n, gaps, 0, rowsTopOn(page)) -
            font * MenuLayout.HIGHLIGHT_PAD_FRACTION

        val barTop = MenuLayout.deleteBarY(rowTop, font)
        val barBottom = barTop + MenuLayout.deleteBarHeight(font)

        assertTrue(barTop > rowTextBottom,
            "the delete bar starts at $barTop, inside the DELETE row's own text box (ends $rowTextBottom)")
        assertTrue(barBottom < nextHighlightTop,
            "the delete bar ends at $barBottom, under the BACK row's highlight (starts $nextHighlightTop)")
        assertTrue(MenuLayout.deleteBarHeight(font) > 0f, "the delete bar has no thickness")
    }

    @Test
    fun `the delete-hold bar is narrower than the row it marks`() {
        // It reuses PauseLayout.barX/barTrackWidth so the two hold gestures in this game are the
        // same object at the same width (see the draw site). That is only legible while the bar
        // is INSIDE the selection highlight it sits under - a bar wider than its own row would
        // read as page furniture rather than as this row's meter.
        assertTrue(
            PauseLayout.BAR_HALF_SPAN < MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD,
            "the hold bar (+/-${PauseLayout.BAR_HALF_SPAN}h) is wider than the row it marks " +
                "(+/-${MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD}h)"
        )
    }

    @Test
    fun `the page header clears the board, not just the rows`() {
        // `both title variants clear the first row` checks rowY(0), which on the LEADERBOARD
        // page is most of a screen BELOW the first thing actually drawn in the panel. The board
        // is what the title has to clear there.
        for (runHeld in listOf(false, true))
        {
            val page = MenuPage.LEADERBOARD
            val bottom = MenuLayout.titleY(page, runHeld) + MenuLayout.titleFont(page, runHeld)
            assertTrue(
                bottom <= MenuLayout.BOARD_TOP_Y,
                "the title (ends $bottom, runHeld=$runHeld) runs into the board at " +
                    "${MenuLayout.BOARD_TOP_Y}"
            )
        }
    }

    @Test
    fun `this test's copy of the row labels covers every menu row`() {
        // rowLabels is a hand-copy of EnPustTil.menuItemLabel (see its own comment for why it
        // has to be). That duplication is only safe while it is COMPLETE: a row added to
        // MenuItemId and given a label there but not here would silently drop out of the width
        // check above rather than failing it, which is the worse of the two outcomes.
        for (item in MenuItemId.entries)
            assertTrue(rowLabels.containsKey(item), "$item has no label in MenuLayoutTest.rowLabels")
    }

    @Test
    fun `every menu anchor is a fraction of the screen, not a pixel count`() {
        // engine.window.width/height are PHYSICAL framebuffer pixels, so the single most
        // damaging mistake available here is writing a number that looks like a position at
        // 1080p. Every anchor this object exposes must be a fraction strictly inside the
        // screen, which no pixel count ever is. (ROW_TEXT_INSET_EM and VALUE_COLUMN_EM are
        // deliberately NOT in this list - they are ems of the row's own font, not screen
        // fractions, and VALUE_COLUMN_EM is legitimately greater than 1.)
        val anchors = mutableMapOf(
            "TITLE_Y" to MenuLayout.TITLE_Y,
            "TITLE_FONT" to MenuLayout.TITLE_FONT,
            "ROWS_TOP_Y" to MenuLayout.ROWS_TOP_Y,
            "ROW_FONT" to MenuLayout.ROW_FONT,
            "ROW_BUDGET_BOTTOM" to MenuLayout.ROW_BUDGET_BOTTOM,
            "HIGHLIGHT_HALF_SPAN_ROOT" to MenuLayout.HIGHLIGHT_HALF_SPAN_ROOT,
            "HIGHLIGHT_HALF_SPAN_GRAPHICS" to MenuLayout.HIGHLIGHT_HALF_SPAN_GRAPHICS,
            "HINT_GAP" to MenuLayout.HINT_GAP,
            "HINT_FONT" to MenuLayout.HINT_FONT
        )
        for (page in MenuPage.entries)
        {
            val n = rowsOn(page).size
            val gaps = gapsOn(page)
            val rowsTop = rowsTopOn(page)
            anchors["hintY($page)"] = MenuLayout.hintY(n, gaps, rowsTop)
            for (i in 0 until n) anchors["rowY($i, $page)"] = MenuLayout.rowY(i, n, gaps, gaps, rowsTop)
        }
        // The board's own anchors, on the one page that has them.
        anchors["BOARD_TOP_Y"] = MenuLayout.BOARD_TOP_Y
        anchors["BOARD_FONT"] = MenuLayout.BOARD_FONT
        anchors["BOARD_GAP"] = MenuLayout.BOARD_GAP
        anchors["HIGHLIGHT_HALF_SPAN_LEADERBOARD"] = MenuLayout.HIGHLIGHT_HALF_SPAN_LEADERBOARD
        for (i in 0 until AttractLayout.LEADERBOARD_SIZE)
            anchors["boardRowY($i)"] = MenuLayout.boardRowY(i)

        for ((name, value) in anchors)
            assertTrue(value > 0f && value < 1f, "$name = $value is not a screen fraction")
    }
}
