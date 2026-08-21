import booth.CallbackSites
import render.BoothStatus
import render.Framing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the two defects visible in `idle-view.png` / `runover-view.png` at
 * commit 44a3902.
 *
 * 1. The em dash in "RUN OVER — BANKED 0" rendered as nothing at all — no glyph, no width,
 *    no warning — because the engine's default font bakes only code points U+0020..U+011F
 *    into its atlas. See [DefaultFont]'s doc for how that was established from the engine
 *    jar. Nothing in the game could have told us; only a photograph of the screen could.
 *
 * 2. The attract screen's text collided with the live world rendering behind it: the title
 *    sat inside the diver's glow at 0.44h with the diver pinned to 0.40h, and the
 *    leaderboard row was visually left of the heading it sat under. See [AttractLayout]'s
 *    doc.
 *
 * Both are drawing defects, and drawing needs a GL context — so the testable part was
 * extracted into pure objects first, exactly as [render.Framing], [render.DepthBlend] and
 * [render.AimAngle] already are. What is asserted here is not "the right pixels came out"
 * but the relationships that made the pixels wrong: a character is outside the atlas, a
 * text block overlaps the diver's band, a row is off-centre from its own heading.
 */
class AttractScreenTest
{
    // --- Defect 1: the font's glyph coverage ----------------------------------------

    @Test
    fun `every string the game draws is inside the default font's baked atlas`()
    {
        for (text in ScreenText.all())
        {
            val missing = DefaultFont.undrawableCodePointsIn(text)
            assertTrue(
                missing.isEmpty(),
                "\"$text\" contains ${missing.map { "U+%04X".format(it) }}, which the engine's " +
                "default font cannot draw — it will render as nothing at all, silently. " +
                "See DefaultFont's doc."
            )
        }
    }

    @Test
    fun `the booth status line's worst case is inside the default font's baked atlas too`()
    {
        // BoothStatus.line is not one of ScreenText's constants — it is built at draw time
        // in EnPustTil (drawBoothStatusLine / drawBootFailedScreen) — so the sweep above
        // never sees it. Its worst case includes a failed boot, the most severe thing the
        // line can say, on top of every counter maxed out — the exact case a vanished
        // string would be most dangerous for, since it is drawn as the ENTIRE frame.
        //
        // Every literal inside BoothStatus.line is plain ASCII, so the only UNCONSTRAINED
        // input is lastFailureSite — a caller-supplied String, not a compile-time literal.
        // Looping the real booth.CallbackSites constants through here (rather than one
        // hardcoded stand-in like "onFixedUpdate") is what would catch a future site
        // renamed to include an en dash or a smart quote: exactly defect 1 in this class's
        // own doc, and exactly the kind of change that compiles, passes every other test,
        // and vanishes from the screen with no warning.
        val sites = listOf(
            CallbackSites.CREATE,
            CallbackSites.FIXED_UPDATE,
            CallbackSites.UPDATE,
            CallbackSites.RENDER,
            CallbackSites.DESTROY,
            null
        )
        for (site in sites)
        {
            val text = BoothStatus.line(
                seed = 20260903L,
                unmappedPads = 9,
                stuckSources = 9,
                chatterSources = 9,
                callbackFailures = 99,
                lastFailureSite = site,
                bootFailed = true
            )
            val missing = DefaultFont.undrawableCodePointsIn(text)
            assertTrue(
                missing.isEmpty(),
                "\"$text\" (lastFailureSite=$site) contains ${missing.map { "U+%04X".format(it) }}, which " +
                "the engine's default font cannot draw — it will render as nothing at all, silently."
            )
        }
    }

    @Test
    fun `the em dash that caused the defect is still rejected`()
    {
        // Guards the guard: if canDraw ever became `true` for everything, the sweep above
        // would pass vacuously and we would be back where we started.
        assertEquals(
            listOf(0x2014),
            DefaultFont.undrawableCodePointsIn("RUN OVER — BANKED 0"),
            "U+2014 EM DASH is the character that silently vanished on the booth screen"
        )
    }

    @Test
    fun `the other punctuation an editor substitutes in is rejected too`()
    {
        // The realistic way this comes back is not someone typing U+2014 deliberately; it
        // is smart quotes, an en dash or an ellipsis arriving via copy-paste.
        for (c in listOf('–', '‘', '’', '“', '”', '…', '•'))
            assertTrue(
                DefaultFont.undrawableCodePointsIn("A${c}B").isNotEmpty(),
                "U+%04X should be reported as undrawable".format(c.code)
            )
    }

    @Test
    fun `Norwegian and the separator are drawable, so the fix is not just ASCII-only`()
    {
        // The distinction that made the original bug confusing: É rendered fine, the em
        // dash did not. Both are non-ASCII; only one is inside the atlas. A fix that
        // retreated to pure ASCII would have thrown away the game's own title.
        for (text in listOf("ÉN PUST TIL", "ÆØÅ æøå", ScreenText.SEPARATOR))
            assertTrue(
                DefaultFont.undrawableCodePointsIn(text).isEmpty(),
                "\"$text\" is inside U+0020..U+011F and must stay drawable"
            )
    }

    @Test
    fun `the atlas bounds match the engine's own constants`()
    {
        // The last code point stb bakes, and the first one it does not.
        assertTrue(DefaultFont.canDraw(0x20), "space is the first baked code point")
        assertTrue(DefaultFont.canDraw(0x11F), "U+011F is the last baked code point")
        assertFalse(DefaultFont.canDraw(0x1F), "U+001F is below the atlas")
        assertFalse(DefaultFont.canDraw(0x120), "U+0120 is above the atlas")
        assertTrue(DefaultFont.canDraw('\n'.code), "TextRenderer handles newline before the range check")
    }

    // --- Defect 2: the attract screen versus the live world behind it -----------------

    @Test
    fun `the sign clears the diver and its glow`()
    {
        // THE REPORTED DEFECT. The title was drawn at 0.44h with the diver pinned to
        // Framing.DIVER_SCREEN_FRACTION = 0.40h — text straight through a bright white
        // square and its halo, with a pearl inside the letter U.
        val haloTop = Framing.DIVER_SCREEN_FRACTION - AttractLayout.DIVER_HALO_HALF_HEIGHT

        assertTrue(
            AttractLayout.TITLE_Y + AttractLayout.TITLE_FONT < haloTop,
            "the title ends at ${AttractLayout.TITLE_Y + AttractLayout.TITLE_FONT}h but the " +
            "diver's halo starts at ${haloTop}h"
        )
        assertTrue(
            AttractLayout.PRESS_START_Y + AttractLayout.PRESS_START_FONT < haloTop,
            "PRESS START ends at ${AttractLayout.PRESS_START_Y + AttractLayout.PRESS_START_FONT}h " +
            "but the diver's halo starts at ${haloTop}h"
        )
    }

    @Test
    fun `the leaderboard starts below the diver and its glow`()
    {
        val haloBottom = Framing.DIVER_SCREEN_FRACTION + AttractLayout.DIVER_HALO_HALF_HEIGHT
        assertTrue(
            AttractLayout.HEADING_Y > haloBottom,
            "the leaderboard heading is at ${AttractLayout.HEADING_Y}h but the diver's halo " +
            "runs to ${haloBottom}h"
        )
    }

    @Test
    fun `the sign does not overlap itself`()
    {
        assertTrue(
            AttractLayout.TITLE_Y + AttractLayout.TITLE_FONT < AttractLayout.PRESS_START_Y,
            "the title's own text box runs into PRESS START"
        )
    }

    @Test
    fun `a full leaderboard fits on screen`()
    {
        // Eight rows is the whole social hook; a board that runs off the bottom of a booth
        // panel loses the names at the bottom, which are the ones people came back to see.
        val bottom = AttractLayout.bottomOfBoard(AttractLayout.LEADERBOARD_SIZE)
        assertTrue(bottom < 1f, "the last of ${AttractLayout.LEADERBOARD_SIZE} rows ends at ${bottom}h, off screen")
        assertTrue(
            AttractLayout.rowY(0) > AttractLayout.HEADING_Y + AttractLayout.ROW_FONT,
            "the first row overlaps the heading"
        )
    }

    @Test
    fun `leaderboard rows do not overlap each other`()
    {
        assertTrue(
            AttractLayout.rowY(1) - AttractLayout.rowY(0) > AttractLayout.ROW_FONT,
            "row spacing ${AttractLayout.rowY(1) - AttractLayout.rowY(0)}h is smaller than the " +
            "${AttractLayout.ROW_FONT}h text it has to hold"
        )
    }

    @Test
    fun `a leaderboard row is centred on the same x as its heading`()
    {
        // THE REPORTED DEFECT. Rank right-aligned at 0.42w, initials left-aligned at 0.46w
        // and score right-aligned at 0.58w put the row's ink centre near 0.49w while the
        // heading was centred at 0.50w. The row now pins its outer edges symmetrically
        // about the centre, so this holds without knowing any glyph width.
        val w = 1920f
        val h = 1200f
        val centreX = w * 0.5f

        val rankLeftEdge = AttractLayout.rankX(centreX, h)      // drawn with xOrigin = 0
        val scoreRightEdge = AttractLayout.scoreX(centreX, h)   // drawn with xOrigin = 1

        assertEquals(
            centreX, (rankLeftEdge + scoreRightEdge) * 0.5f, 0.001f,
            "the row's outer edges must straddle the heading's centre line"
        )
        assertEquals(
            centreX, AttractLayout.initialsX(centreX), 0.001f,
            "the initials sit on the centre line, so the row reads as one block"
        )
        assertTrue(rankLeftEdge < centreX && scoreRightEdge > centreX, "the columns must not have swapped sides")
    }

    @Test
    fun `the row does not stretch when the panel gets wider`()
    {
        // The distinct failure the old layout had, separate from being off-centre: its
        // columns were fractions of WIDTH while the text between them was a fraction of
        // HEIGHT, so on a wider panel the columns pulled apart and left a hole while the
        // glyphs stayed the same size. Same height, three panel widths, one row width.
        val h = 1200f
        val widths = listOf(1600f, 1920f, 3840f).map { w ->
            AttractLayout.scoreX(w * 0.5f, h) - AttractLayout.rankX(w * 0.5f, h)
        }

        for (rowWidth in widths)
            assertEquals(
                widths.first(), rowWidth, 0.001f,
                "the row is ${widths.first()}px on one panel and ${rowWidth}px on another of the " +
                "same height — its columns must be derived from screen height, not width"
            )
    }

    @Test
    fun `the row fits the narrowest booth panel it could be shown on`()
    {
        // The row's width is a fraction of screen HEIGHT (see ROW_HALF_SPAN's doc), which
        // is what keeps it proportional to the text inside it — but it does then have to be
        // checked against the WIDTH of the squarest panel the booth might have. 4:3 is the
        // worst case; if it fits there it fits 16:10 and 16:9.
        val h = 1200f
        val narrowestWidth = h * 4f / 3f
        val rowWidth = AttractLayout.rowWidth() * h

        assertTrue(
            rowWidth < narrowestWidth * 0.8f,
            "a ${rowWidth}px row leaves no margin on a ${narrowestWidth}px-wide 4:3 panel"
        )
    }

    @Test
    fun `the booth status line clears the bottom of a full leaderboard`()
    {
        // Passes TODAY (board bottom is well above 0.98h), which is exactly why it needs an
        // assertion rather than an eyeball: nobody notices a leaderboard grown by a future
        // LEADERBOARD_SIZE change until the two collide on a real booth panel.
        assertTrue(
            AttractLayout.STATUS_Y - AttractLayout.STATUS_FONT > AttractLayout.bottomOfBoard(AttractLayout.LEADERBOARD_SIZE),
            "the status line's top edge (${AttractLayout.STATUS_Y - AttractLayout.STATUS_FONT}h) overlaps a full " +
            "leaderboard, whose last row ends at ${AttractLayout.bottomOfBoard(AttractLayout.LEADERBOARD_SIZE)}h"
        )
    }

    @Test
    fun `every attract anchor is a fraction of the screen, not a pixel count`()
    {
        // engine.window.width/height are PHYSICAL framebuffer pixels (see render/Framing), so
        // the single most damaging mistake available here is writing a number that looks
        // like a position at 1080p. Every anchor this object exposes must be a fraction
        // strictly inside the screen — which no pixel count ever is.
        val anchors = mapOf(
            "TITLE_Y" to AttractLayout.TITLE_Y,
            "TITLE_FONT" to AttractLayout.TITLE_FONT,
            "PRESS_START_Y" to AttractLayout.PRESS_START_Y,
            "PRESS_START_FONT" to AttractLayout.PRESS_START_FONT,
            "HEADING_Y" to AttractLayout.HEADING_Y,
            "ROW_FONT" to AttractLayout.ROW_FONT,
            "ROW_HALF_SPAN" to AttractLayout.ROW_HALF_SPAN,
            "DIVER_HALO_HALF_HEIGHT" to AttractLayout.DIVER_HALO_HALF_HEIGHT,
            "STATUS_FONT" to AttractLayout.STATUS_FONT,
            "STATUS_Y" to AttractLayout.STATUS_Y
        ) + (0 until AttractLayout.LEADERBOARD_SIZE).associate { "rowY($it)" to AttractLayout.rowY(it) }

        for ((name, value) in anchors)
            assertTrue(value > 0f && value < 1f, "$name = $value is not a screen fraction")
    }
}
