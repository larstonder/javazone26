package render

import AttractLayout
import BriefingLayout
import DefaultFont
import InitialsLayout
import PauseLayout
import RunOverLayout
import ScreenText
import score.InitialsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry for the two screens that close a run: RUN_OVER and ENTER_INITIALS.
 *
 * WHY THIS FILE EXISTS. Until 2026-08-31 neither screen had a layout object at all — their
 * anchors were `val`s inside `EnPustTil.drawRunOverScreen` / `drawInitialsEntryScreen`, and the
 * only test that touched them was `PanelLayoutTest`, via a pair of private objects that
 * hand-copied the literals into a second file. That test's own class doc called the duplication
 * "a real risk this test cannot remove, only narrow". [RunOverLayout] and [InitialsLayout]
 * remove it, and this file is what those two objects are for: the same role `PauseScreenTest`
 * plays for [PauseLayout] and `BriefingScreenTest` plays for [BriefingLayout].
 *
 * Same conventions as both of those. Every number is a fraction of screen HEIGHT (never width,
 * never a pixel count — `engine.window.width/height` are PHYSICAL framebuffer pixels and the
 * display's aspect is unknown), and text grows DOWNWARD from its anchor, so a block occupies
 * `y .. y + fontSize`. What is asserted is the RELATIONSHIP between numbers, never the numbers
 * themselves: a test that pins `TITLE_Y == 0.585f` cannot fail from anything a human would call
 * a bug, and this project has deleted several of those (commit 4493eeb).
 */
class ScoreScreenTest
{
    /** An upper bound on a glyph's advance in the default font. Same estimate `Hud` uses. */
    private val EM = 0.62f

    private fun widthOf(text: String, fontFraction: Float) = text.length * EM * fontFraction

    private val HALO_TOP = Framing.DIVER_SCREEN_FRACTION - AttractLayout.DIVER_HALO_HALF_HEIGHT
    private val HALO_BOTTOM = Framing.DIVER_SCREEN_FRACTION + AttractLayout.DIVER_HALO_HALF_HEIGHT

    private fun runOverBoxes(): List<Triple<String, Float, Float>> = listOf(
        Triple("title", RunOverLayout.TITLE_Y, RunOverLayout.TITLE_FONT),
        Triple("score", RunOverLayout.SCORE_Y, RunOverLayout.SCORE_FONT),
        Triple("caption", RunOverLayout.CAPTION_Y, RunOverLayout.CAPTION_FONT),
        Triple("hint", RunOverLayout.HINT_Y, RunOverLayout.HINT_FONT)
    )

    private fun initialsBoxes(): List<Triple<String, Float, Float>> = listOf(
        Triple("title", InitialsLayout.TITLE_Y, InitialsLayout.TITLE_FONT),
        // The slot RECTS, not the letters inside them - the rects are what bound the block.
        Triple("slots", InitialsLayout.SLOTS_TOP_Y, InitialsLayout.SLOT_HEIGHT),
        Triple("help", InitialsLayout.HELP_Y, InitialsLayout.HELP_FONT)
    )

    // --- 1. Both screens are well-formed as layouts ------------------------------------------

    @Test
    fun `every anchor on both screens is a screen fraction strictly inside the screen`()
    {
        for ((screen, boxes) in listOf("run-over" to runOverBoxes(), "initials" to initialsBoxes()))
            for ((name, y, size) in boxes)
            {
                assertTrue(y > 0f && y < 1f, "$screen $name anchor $y is not a fraction in (0,1)")
                assertTrue(size > 0f && size < 1f, "$screen $name size $size is not a fraction in (0,1)")
                assertTrue(y + size < 1f, "$screen $name runs off the bottom: ${y + size}")
            }

        assertTrue(InitialsLayout.LETTER_FONT > 0f && InitialsLayout.LETTER_FONT < 1f)
        assertTrue(InitialsLayout.SLOT_WIDTH > 0f && InitialsLayout.SLOT_GAP > 0f)
    }

    @Test
    fun `no two elements overlap on either screen`()
    {
        for ((screen, boxes) in listOf("run-over" to runOverBoxes(), "initials" to initialsBoxes()))
            for (i in 0 until boxes.size - 1)
            {
                val (nameA, yA, sizeA) = boxes[i]
                val (nameB, yB, _) = boxes[i + 1]
                assertTrue(
                    yA + sizeA <= yB,
                    "$screen: $nameA ends at ${yA + sizeA}, which is below $nameB's anchor $yB"
                )
            }
    }

    // --- 2. Both screens clear the diver ------------------------------------------------------

    @Test
    fun `every element on both screens clears the diver and its glow`()
    {
        // STRICTER THAN THE BRIEFING'S RULE, AND DELIBERATELY SO. `BriefingScreenTest`'s
        // equivalent is a disjunction whose third arm allows an element to sit in the halo band
        // if it is inside the panel — the argument being that a bordered card is the near layer
        // and the diver becomes backdrop (see BriefingLayout's class doc). That argument does
        // not transfer here, for a reason specific to these two screens: their TITLES are drawn
        // OUTSIDE the panel, by the one-panel-per-screen rule, and a title is the largest loose
        // string either screen has. Rather than split the rule per element, both screens simply
        // sit below the band — which they can afford to, having four and three elements
        // respectively against the briefing's ten.
        //
        // The band is derived from the two constants rather than hardcoded as 0.26/0.54, so a
        // change to the diver's screen position or the halo's measured size moves this test
        // with it instead of leaving it silently stale.
        for ((screen, boxes) in listOf("run-over" to runOverBoxes(), "initials" to initialsBoxes()))
            for ((name, y, size) in boxes)
            {
                val bottom = y + size
                assertTrue(
                    bottom <= HALO_TOP || y >= HALO_BOTTOM,
                    "$screen $name occupies ${y}h..${bottom}h, which intrudes into the diver's " +
                        "${HALO_TOP}h..${HALO_BOTTOM}h halo band by " +
                        "${minOf(bottom, HALO_BOTTOM) - maxOf(y, HALO_TOP)}h"
                )
            }
    }

    // --- 3. Run-over: the numeral is the message ----------------------------------------------

    @Test
    fun `the run-over score is the largest thing on its screen by a clear margin`()
    {
        // THE ENTIRE POINT OF THE 2026-08-31 REBUILD, EXPRESSED AS A RELATIONSHIP. `BANKED n`
        // is ALREADY on screen when this screen draws — `Hud.drawBanked` puts it top-left every
        // frame and RUN_OVER draws the full HUD underneath (see renderGame's RUN_OVER branch).
        // So a summary that restates the figure at a comparable size restates nothing, and the
        // whole of design doc §3.5's "then enlarge the numeral" rests on this ratio rather than
        // on any absolute value.
        //
        // 2x against the title rather than a pinned 0.105f: what would actually be a regression
        // is somebody trimming the numeral back towards the 0.04h the old one-line form used,
        // and the ratio catches that whatever the two constants become.
        for ((name, _, size) in runOverBoxes())
        {
            if (name == "score") continue
            assertTrue(
                RunOverLayout.SCORE_FONT >= size * 2f,
                "the run-over score is ${RunOverLayout.SCORE_FONT}h against $name's ${size}h - " +
                    "less than twice as tall, which is not enough to read as the end of a run " +
                    "rather than as one more field of the HUD already showing the same number"
            )
        }
    }

    @Test
    fun `the run-over caption sits directly under the numeral it names`()
    {
        // A number with no noun is a defect; a noun that has drifted away from its number is
        // the same defect wearing a gap. The caption must be nearer the numeral than the hint
        // below it is to the caption, or the three lines read as an evenly-spaced list.
        val scoreToCaption = RunOverLayout.CAPTION_Y - (RunOverLayout.SCORE_Y + RunOverLayout.SCORE_FONT)
        val captionToHint = RunOverLayout.HINT_Y - (RunOverLayout.CAPTION_Y + RunOverLayout.CAPTION_FONT)
        assertTrue(
            scoreToCaption < captionToHint,
            "the numeral-to-caption gap is ${scoreToCaption}h and the caption-to-hint gap is " +
                "${captionToHint}h - the caption is not visibly attached to the number it names"
        )
    }

    // --- 4. Initials: the slot geometry -------------------------------------------------------

    @Test
    fun `there is exactly one slot per letter the entry can produce`()
    {
        // InitialsLayout.SLOT_COUNT is a hand-mirrored copy of InitialsEntry.LETTER_COUNT, in
        // the same position `GameSettings.KNOWN_QUALITIES` is in relative to
        // `GraphicsQuality.entries` (and asserted the same way, by GraphicsApplierTest). It is
        // a copy rather than a read because the layout object is presentation and
        // `score.InitialsEntry` is persistence — but a copy that drifts draws two boxes for
        // three letters, or three for two, with nothing else failing.
        assertEquals(
            InitialsEntry.LETTER_COUNT, InitialsLayout.SLOT_COUNT,
            "InitialsLayout.SLOT_COUNT has drifted from InitialsEntry.LETTER_COUNT - the screen " +
                "would draw a different number of boxes than there are letters to put in them"
        )
    }

    @Test
    fun `the slot block is centred on the screen axis and evenly pitched`()
    {
        // THE PROPERTY THE BRACKETS COULD NOT HAVE. The old `[A]  A   A ` line was one string
        // whose ink shifted sideways as the bracket moved between cells, so the block's optical
        // centre moved with the cursor. Computed rects cannot do that, and this is the
        // assertion that says so: the block's centre is the screen's centre, and it does not
        // depend on which slot is active because nothing here does.
        val h = 1000f
        val centreX = 777f      // deliberately not 0, so a dropped centreX term shows up

        val first = InitialsLayout.slotCentreX(0, centreX, h)
        val last = InitialsLayout.slotCentreX(InitialsLayout.SLOT_COUNT - 1, centreX, h)
        assertEquals(
            centreX, (first + last) * 0.5f, 1e-3f,
            "the slot block is not centred on the screen axis - the title and help line above " +
                "and below it are drawn at centreX with xOrigin = 0.5"
        )

        val pitch = InitialsLayout.slotCentreX(1, centreX, h) - first
        for (slot in 1 until InitialsLayout.SLOT_COUNT)
        {
            val step = InitialsLayout.slotCentreX(slot, centreX, h) -
                       InitialsLayout.slotCentreX(slot - 1, centreX, h)
            assertEquals(pitch, step, 1e-3f, "slot $slot is not on the same pitch as slot 1")
        }
    }

    @Test
    fun `adjacent slots do not touch`()
    {
        // A gap smaller than a hairline turns three boxes into one bar, which is the shape a
        // progress meter has rather than the shape a set of cells has. Derived from the pitch
        // rather than restating SLOT_GAP, so it fails if the pitch and the width ever stop
        // agreeing with each other.
        val h = 1000f
        val centreX = 0f
        val halfSlot = h * InitialsLayout.SLOT_WIDTH * 0.5f
        for (slot in 1 until InitialsLayout.SLOT_COUNT)
        {
            val leftEdge = InitialsLayout.slotCentreX(slot, centreX, h) - halfSlot
            val previousRightEdge = InitialsLayout.slotCentreX(slot - 1, centreX, h) + halfSlot
            assertTrue(
                leftEdge > previousRightEdge,
                "slot $slot starts at $leftEdge but slot ${slot - 1} ends at $previousRightEdge - " +
                    "the boxes touch and read as one bar"
            )
        }
    }

    @Test
    fun `a letter fits inside its own slot, in both axes, and is centred vertically`()
    {
        // The failure this catches is a letter drawn ON its own slot's border, which reads as a
        // rendering fault rather than as a cursor. Horizontally the bound is EM = 0.62 against
        // one glyph; vertically it is the derived letterY() rather than a hand-tuned anchor,
        // which is the whole reason that function exists (see its doc).
        val widest = widthOf("W", InitialsLayout.LETTER_FONT)
        assertTrue(
            widest < InitialsLayout.SLOT_WIDTH,
            "a letter is ${widest}h wide at EM = $EM but the slot is only " +
                "${InitialsLayout.SLOT_WIDTH}h - the glyph overhangs its own box"
        )
        assertTrue(
            InitialsLayout.LETTER_FONT < InitialsLayout.SLOT_HEIGHT,
            "a letter is ${InitialsLayout.LETTER_FONT}h tall in a ${InitialsLayout.SLOT_HEIGHT}h slot"
        )

        val letterTop = InitialsLayout.letterY()
        val letterBottom = letterTop + InitialsLayout.LETTER_FONT
        val slotTop = InitialsLayout.SLOTS_TOP_Y
        val slotBottom = InitialsLayout.slotsBottom()
        assertTrue(letterTop > slotTop, "the letter's box starts above its slot")
        assertTrue(letterBottom < slotBottom, "the letter's box ends below its slot")
        assertEquals(
            letterTop - slotTop, slotBottom - letterBottom, 1e-5f,
            "the letter is not vertically centred in its slot - letterY() is supposed to derive " +
                "that from SLOT_HEIGHT and LETTER_FONT rather than be tuned by hand"
        )
    }

    @Test
    fun `the slot block fills most of its panel and still fits inside it`()
    {
        // BOTH HALVES ARE THE POINT OF DESIGN DOC §3.6, and they pull in opposite directions.
        // The measured defect on shot 08 (1920x1200) was 828 px of card around 216 px of drawn
        // content - the card was four fifths empty - so the fix has to make the content bigger
        // AND the card smaller without letting the first run past the second.
        //
        // THE FIRST DRAFT OF THIS TEST COMPARED AGAINST THE OLD LINE'S *ESTIMATED* WIDTH AND
        // FAILED, WHICH IS WORTH RECORDING BECAUSE THE FAILURE WAS INFORMATIVE. Eleven
        // characters at 0.06h and EM = 0.62 estimates 0.2046h of half-width for `[A]  A   A `,
        // marginally MORE than the slot block's 0.1985h - yet the same line MEASURED 216 px on
        // the real frame, i.e. 0.09h of half-width. EM = 0.62 is a per-glyph upper bound and
        // that line is mostly spaces and brackets, so the estimate overstates it by better than
        // 2x. The lesson generalises: an EM estimate is a safe bound to SIZE A PANEL with (too
        // wide is cosmetic, too narrow clips) and a bad one to COMPARE TWO STRINGS with.
        //
        // So the relationship asserted is the one that was actually measured as wrong - the
        // ratio of drawn content to card - expressed in the only units both sides have: the
        // slot block is real geometry, and the card is PanelLayout.INITIALS_HALF_SPAN plus its
        // padding. Before: 216/828 = 26%. Now: 0.397h of slots in a 0.61h card = 65%.
        val cardHalfWidth = PanelLayout.INITIALS_HALF_SPAN + PanelLayout.PADDING_FRACTION
        val fill = InitialsLayout.slotsHalfSpan() / cardHalfWidth
        assertTrue(
            fill >= 0.5f,
            "the slot block fills only ${fill * 100f}% of the card's width " +
                "(${InitialsLayout.slotsHalfSpan()}h of ${cardHalfWidth}h half-width). The " +
                "defect this screen was rebuilt for was a card four fifths empty; either grow " +
                "the slots or narrow INITIALS_HALF_SPAN - and read that constant's doc first, " +
                "because the help line is what sizes it."
        )
        assertTrue(
            InitialsLayout.slotsHalfSpan() < PanelLayout.INITIALS_HALF_SPAN,
            "the slot block (${InitialsLayout.slotsHalfSpan()}h) is wider than the panel's own " +
                "content half-span (${PanelLayout.INITIALS_HALF_SPAN}h)"
        )
    }

    // --- 5. Scrims ---------------------------------------------------------------------------

    @Test
    fun `both score screens are the lightest scrims in the game`()
    {
        // The ordering IS the signal, and it is the same argument BriefingLayout.SCRIM_ALPHA
        // makes for sitting below PauseLayout's: pause says "stopped", the briefing says "read
        // this", and these two say "your run ended, here is what you got" over a world the
        // player is still meant to recognise as the place they just were. More concretely, this
        // surface's blend leaves `1 - sqrt(alpha)` of what is underneath (see
        // Hud.authoredAlphaFor's measurement), and what is underneath includes the red `0:00`
        // clock capsule - the clearest statement in the game of WHY the run ended.
        for ((name, alpha) in listOf(
            "run-over" to RunOverLayout.SCRIM_ALPHA,
            "initials" to InitialsLayout.SCRIM_ALPHA
        ))
        {
            assertTrue(alpha > 0f && alpha < 1f, "$name scrim $alpha is not a fraction")
            assertTrue(
                alpha < BriefingLayout.SCRIM_ALPHA && alpha < PauseLayout.SCRIM_ALPHA,
                "$name's scrim ($alpha) is not lighter than the briefing's " +
                    "(${BriefingLayout.SCRIM_ALPHA}) and the pause screen's " +
                    "(${PauseLayout.SCRIM_ALPHA}) - see this test's comment for why the order matters"
            )
        }
        assertEquals(
            RunOverLayout.SCRIM_ALPHA, InitialsLayout.SCRIM_ALPHA, 1e-6f,
            "run-over and initials entry are one moment for the player; a step change in dimming " +
                "between them reads as the machine doing something rather than as the same screen"
        )
    }

    // --- 6. Strings --------------------------------------------------------------------------

    @Test
    fun `every string these two screens can draw is inside the font atlas`()
    {
        // ScreenText.all() sweeps the fixed constants, but initialsLetter is a FUNCTION over 26
        // inputs and bankedNumber a function over every score, so neither can be covered by a
        // finite list there — the same position ControlHints' composites are in. All 26 letters
        // are cheap to walk exhaustively, and the digits are covered by a spread of scores.
        for (i in 0 until 26)
        {
            val letters = String(charArrayOf('A' + i, 'A' + i, 'A' + i))
            for (slot in 0 until InitialsLayout.SLOT_COUNT)
            {
                val drawn = ScreenText.initialsLetter(letters, slot)
                assertTrue(
                    DefaultFont.undrawableCodePointsIn(drawn).isEmpty(),
                    "\"$drawn\" is not inside the default font's atlas"
                )
            }
        }
        for (score in listOf(0, 7, 40, 999, 12345, 99999, Int.MAX_VALUE))
            assertTrue(
                DefaultFont.undrawableCodePointsIn(ScreenText.bankedNumber(score)).isEmpty(),
                "the score $score does not draw"
            )
        for (text in listOf(ScreenText.RUN_OVER_TITLE, ScreenText.RUN_OVER_CAPTION))
            assertTrue(
                DefaultFont.undrawableCodePointsIn(text).isEmpty(),
                "\"$text\" is not inside the default font's atlas"
            )
    }

    @Test
    fun `initialsLetter reads the slot it is asked for and never throws`()
    {
        // Two contracts in one test, and the second is the one worth having. The screen it
        // draws is reached by having done WELL, and it is drawn inside a render callback — an
        // index off the end must not become an unhandled throwable there (see
        // booth/CallbackGuard for what the engine does with one: it opens a text editor).
        assertEquals("X", ScreenText.initialsLetter("XYZ", 0))
        assertEquals("Y", ScreenText.initialsLetter("XYZ", 1))
        assertEquals("Z", ScreenText.initialsLetter("XYZ", 2))

        assertEquals("A", ScreenText.initialsLetter("XYZ", 3), "an index past the end must degrade")
        assertEquals("A", ScreenText.initialsLetter("", 0), "an empty entry must degrade")
        assertEquals(
            "A", ScreenText.initialsLetter("Ø..", 0),
            "a character outside A-Z must degrade rather than reach the atlas - the persisted " +
                "scoreboard is a file a human can hand-edit, and isValidInitials is the only " +
                "thing between it and this call"
        )
    }

    @Test
    fun `the letter table is shared, not rebuilt per call`()
    {
        // The reason ScreenText.LETTER_STRINGS exists rather than a Char.toString() at the draw
        // site: three slots drawn every frame of ENTER_INITIALS would be three allocations per
        // frame on the render path, which CLAUDE.md forbids and whose one exemption (HUD text
        // FORMATTING) does not cover a value with 26 possible states. Identity, not equality —
        // `Char.toString()` would satisfy an equality check and allocate every time.
        assertTrue(
            ScreenText.initialsLetter("AAA", 0) === ScreenText.initialsLetter("ABA", 2),
            "initialsLetter is building a new String per call rather than returning a shared one"
        )
    }
}
