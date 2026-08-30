package render

import ScreenText

/**
 * Every string in the game that names a control, for both input devices.
 *
 * WHY THIS EXISTS. Three strings used to hard-code button names as literals — `PRESS START`,
 * `SPACE / START to play again`, `UP/DOWN: change letter   A / START: next`. The gamepad
 * button map is CONFIG-DRIVEN (`kickButton`, `bleedButton`, `restartButton`,
 * `restartButtonAlt` in application.cfg), and the arcade encoder's real button codes are not
 * known until it is plugged in — that is why the map is configurable at all. So those
 * literals were a promise the code could not keep: rebind `restartButton` and the screen
 * lies. Two of them also named a keyboard key AND a gamepad button in one breath, which is
 * half-irrelevant on every machine that will ever run this.
 *
 * PURE AND ENGINE-FREE, like [Framing], [RunLifecycle], [AttractLayout] and [DepthBlend]. It
 * takes button DISPLAY LABELS as plain `String`s, never a `GamepadButton` — that is what
 * keeps it testable with no GL context. The enum-to-label map lives in `EnPustTil.kt` beside
 * `parseGamepadButton`, where the rest of the button-map handling already is.
 *
 * THE ASYMMETRY, which is real and not an oversight worth "cleaning up": gamepad bindings are
 * configurable, keyboard bindings are NOT. `readInput` hard-codes `Key.Z` for kick, `Key.X`
 * for bleed and the arrow keys for the stick; `updateGame` hard-codes `Key.SPACE` for
 * lifecycle actions. There is no config key for any of them. So the keyboard labels below are
 * compile-time constants while the gamepad ones are parameters. Making both parameters would
 * invent four config keys that nothing reads.
 *
 * CASING RULE: control token in caps, verb in lowercase — `STICK swim  ·  A kick`. Calls to
 * action keep full caps (`PRESS START`) because they are the sign, not the legend. Before
 * this, the three literals above disagreed with each other three separate ways, which at
 * arcade viewing distance reads as three unrelated systems.
 *
 * @see ControlHintsTest, which is the sweep of record for these strings — they are composed
 *   at runtime and therefore can never appear in `ScreenText.all()`.
 */
object ControlHints
{
    // The keyboard side. Compiled constants, because the bindings they name are compiled
    // constants — see the asymmetry paragraph above.
    private const val KEY_SWIM = "ARROW KEYS"
    private const val KEY_KICK = "Z"
    private const val KEY_BLEED = "X"
    private const val KEY_CONFIRM = "SPACE"
    private const val KEY_CYCLE = "UP/DOWN"

    /**
     * The pause screen's own two keys. `Esc` opens AND closes it (`pauseEdges`' keyboard
     * source in `EnPustTil.updateGame`), and `Q` held is the exit — see
     * [RunLifecycle.EXIT_HOLD_SECONDS]. Both are compiled constants for the same reason the
     * four above are: nothing in application.cfg rebinds a KEY.
     */
    private const val KEY_RESUME = "ESC"
    private const val KEY_EXIT = "Q"

    private const val PAD_SWIM = "STICK"
    private const val PAD_CYCLE = "STICK UP/DOWN"

    /**
     * The face and system buttons whose printed legend differs from their `GamepadButton`
     * name, per family. A name that is absent falls through to the enum's own name — which is
     * why [ControllerFamily.GENERIC] has no table at all: it IS that fallback, i.e. exactly
     * what this file returned before families existed, so the booth's own USB encoder keeps
     * byte-identical screens.
     *
     * XBOX carries no FACE row because an Xbox pad's face buttons genuinely ARE A/B/X/Y.
     * Identity rows would be a second copy of the truth to keep in step, with no behaviour
     * behind them. It does carry the shoulders and the stick clicks, because those are NOT
     * identities: an Xbox pad's silkscreen reads LB/RB, and its stick clicks are LS/RS.
     *
     * THE SHOULDER AND THUMB ROWS ARRIVED WITH THE PAUSE SCREEN'S OWN HINTS. `exitButtonA`
     * and `exitButtonB` default to the two bumpers, and the exit hint now PRINTS them
     * ([exitHold]) rather than naming a keyboard key the player may not have — so
     * `HOLD LEFT BUMPER + RIGHT BUMPER to exit` is what a DualSense owner would otherwise
     * read off a pad whose shoulders say L1 and R1. The thumb rows are here for the same
     * reason one step ahead: `exitButtonA = LEFT_THUMB` is a documented, tested remap
     * (application.cfg's BUTTON MAP names both thumbs), so it can reach this exact string.
     *
     * ASCII WORDS, NOT THE REAL GLYPHS, AND THIS IS NOT A STYLE CHOICE. The engine's default
     * font bakes only U+0020..U+011F into its atlas, and anything above renders as *nothing at
     * all* — no glyph and no x-advance, silently (see `DefaultFont`). The PlayStation face
     * symbols are U+2715 / U+25CB / U+25A1 / U+25B3, so a "correct" `CROSS kick` would ship as
     * ` kick` with nothing anywhere saying so. [all] carries every string below for exactly
     * that reason: a later tidy-up to the real symbols fails the build instead of emptying the
     * cabinet's legend.
     *
     * KNOWN LIMIT, deliberate. The engine's `GamepadButton` also declares CROSS/CIRCLE/SQUARE/
     * TRIANGLE as aliases sharing codes with A/B/X/Y, so a technician who writes
     * `kickButton = CROSS` gets "CROSS" on an Xbox pad. Mapping those back would mean this
     * table changed a name the technician typed into application.cfg into its opposite, which
     * is a worse surprise than an unusual spelling of a correctly bound button.
     */
    private val PLAYSTATION_LABELS = mapOf(
        "A" to "CROSS",
        "B" to "CIRCLE",
        "X" to "SQUARE",
        "Y" to "TRIANGLE",
        "START" to "OPTIONS",
        "BACK" to "CREATE",
        "LEFT_BUMPER" to "L1",
        "RIGHT_BUMPER" to "R1",
        "LEFT_THUMB" to "L3",
        "RIGHT_THUMB" to "R3"
    )

    private val XBOX_LABELS = mapOf(
        "START" to "MENU",
        "BACK" to "VIEW",
        "LEFT_BUMPER" to "LB",
        "RIGHT_BUMPER" to "RB",
        "LEFT_THUMB" to "LS",
        "RIGHT_THUMB" to "RS"
    )

    /**
     * Every enum name any family renames, derived from the tables rather than listed again, so
     * adding a row extends [all]'s font sweep automatically. A second hand-kept list is how a
     * new label ships uncovered.
     */
    private val FAMILY_LABELLED_BUTTONS: List<String> =
        (PLAYSTATION_LABELS.keys + XBOX_LABELS.keys).toList()

    /**
     * A `GamepadButton` name rendered for a screen at arcade viewing distance.
     *
     * WHAT THIS PARAGRAPH USED TO SAY. It recorded an ACCEPTED LIMIT — the enum's own name,
     * because "there is no source for the physical legend". There is one now, and leaving the
     * old text standing would be a doc comment that contradicts the code: `Gamepad.id` is the
     * raw GLFW joystick index, so `glfwGetGamepadName` names the hardware, and
     * [ControllerFamily] turns that name into a family. See its class doc for the evidence and
     * for why an unrecognised pad is a non-event.
     *
     * The default is [ControllerFamily.GENERIC], which is that old behaviour unchanged: the
     * enum name with underscores opened out. Every existing caller is therefore byte-identical,
     * and so is the booth's generic USB encoder, which is the point rather than a convenience.
     *
     * ENGINE-FREE, like the rest of this object: a `String` name and a [ControllerFamily],
     * never a `GamepadButton`. `EnPustTil.gamepadButtonLabel` is the adapter.
     */
    fun labelFor(buttonName: String, family: ControllerFamily = ControllerFamily.GENERIC): String
    {
        val legend = when (family)
        {
            ControllerFamily.PLAYSTATION -> PLAYSTATION_LABELS[buttonName]
            ControllerFamily.XBOX -> XBOX_LABELS[buttonName]
            ControllerFamily.GENERIC -> null
        }
        return legend ?: buttonName.replace('_', ' ')
    }

    fun swim(arcade: Boolean): String = if (arcade) PAD_SWIM else KEY_SWIM

    fun kick(arcade: Boolean, kickLabel: String): String = if (arcade) kickLabel else KEY_KICK

    fun bleed(arcade: Boolean, bleedLabel: String): String = if (arcade) bleedLabel else KEY_BLEED

    fun confirm(arcade: Boolean, startLabel: String): String = if (arcade) startLabel else KEY_CONFIRM

    fun cycle(arcade: Boolean): String = if (arcade) PAD_CYCLE else KEY_CYCLE

    // --- Composites. These allocate, so EnPustTil CACHES them; see its hint cache. --------

    fun pressStart(arcade: Boolean, startLabel: String): String =
        "PRESS ${confirm(arcade, startLabel)}"

    fun playAgain(arcade: Boolean, startLabel: String): String =
        "${confirm(arcade, startLabel)} to play again"

    fun initialsHelp(arcade: Boolean, startLabel: String): String =
        "${cycle(arcade)} change letter${ScreenText.SEPARATOR}${confirm(arcade, startLabel)} next"

    fun legend(arcade: Boolean, kickLabel: String, bleedLabel: String): String =
        "${swim(arcade)} swim${ScreenText.SEPARATOR}" +
        "${kick(arcade, kickLabel)} kick${ScreenText.SEPARATOR}" +
        "${bleed(arcade, bleedLabel)} bleed"

    // --- The pause / cabinet-menu screen. -------------------------------------------------
    //
    // These three replaced `ScreenText.PAUSE_RESUME_HINT` / `MENU_RESUME_HINT` / `EXIT_HINT`,
    // which were fixed keyboard literals — `ESC to resume`, `ESC to go back`, `HOLD Q to
    // exit`. Pause and exit reached the PAD on 2026-08-30, so on a controller all three named
    // keys the player may not have in front of them, on the one screen whose entire job is to
    // say which control does what. Same defect the four composites above already exist for:
    // a string that promises a binding the machine does not have.
    //
    // WHICH PAD BUTTON THE RESUME LINE ADVERTISES IS A CHOICE, and the caller makes it. There
    // are two buttons that open and close this screen — `pauseButton` (Create/View) and
    // `pauseButtonAlt` (Options/Menu) — and only one fits on the line. `EnPustTil` passes the
    // ALT's label: Options is the button a console player reaches for to pause anything, and
    // `pauseButton`'s default (Create) is the technician's, not the player's. Both still work;
    // only one is printed.

    fun resume(arcade: Boolean, pauseLabel: String): String =
        "${if (arcade) pauseLabel else KEY_RESUME} to resume"

    fun goBack(arcade: Boolean, pauseLabel: String): String =
        "${if (arcade) pauseLabel else KEY_RESUME} to go back"

    /**
     * The exit affordance. On a pad it is TWO buttons held together — see the exit-hold
     * comment in `EnPustTil.updateGame` for why one stuck contact must not be able to close
     * the cabinet — so this is the one hint that names two controls, joined with a literal
     * `+` rather than [ScreenText.SEPARATOR]. The dot is this game's "and also"; a plus is
     * the only punctuation that reads as "at the same time", and that simultaneity IS the
     * instruction. `+` is U+002B, well inside the default font's atlas.
     */
    fun exitHold(arcade: Boolean, exitALabel: String, exitBLabel: String): String =
        if (arcade) "HOLD $exitALabel + $exitBLabel to exit"
        else "HOLD $KEY_EXIT to exit"

    /**
     * Every string this object can produce, at both device settings, at every controller
     * family and at the widest button labels, for the font-atlas sweep. Test-only; cheap
     * enough not to warrant a flag, same as `ScreenText.all()`.
     */
    fun all(): List<String>
    {
        val out = mutableListOf<String>()
        for (arcade in listOf(true, false))
        {
            out += swim(arcade)
            out += cycle(arcade)
            out += kick(arcade, "RIGHT BUMPER")
            out += bleed(arcade, "LEFT BUMPER")
            out += confirm(arcade, "START")
            out += pressStart(arcade, "START")
            out += playAgain(arcade, "START")
            out += initialsHelp(arcade, "START")
            out += legend(arcade, "A", "B")
            out += legend(arcade, "RIGHT BUMPER", "LEFT BUMPER")
            out += resume(arcade, "START")
            out += goBack(arcade, "START")
            // Both the widest generic labels and the shortest console ones, because the exit
            // hint is the only string in this object that joins TWO variable labels and the
            // join is where a stray character would land.
            out += exitHold(arcade, "LEFT BUMPER", "RIGHT BUMPER")
            out += exitHold(arcade, "L1", "R1")
        }

        // Every family legend, and every composite that can carry one — a family label reaches
        // the screen only through these, so listing the bare word alone would leave the joins
        // unswept. Only the arcade forms: a keyboard hint ignores the gamepad label entirely
        // (see the asymmetry paragraph in this object's doc), so a family cannot reach it.
        //
        // This loop is the enforcement half of labelFor's font paragraph. CROSS/CIRCLE/SQUARE/
        // TRIANGLE are ASCII stand-ins for symbols the default font cannot draw at all, and
        // this is what makes a later edit to the real glyphs a failed build rather than a
        // legend that silently vanishes off a cabinet in front of a queue.
        for (family in ControllerFamily.entries)
        {
            for (buttonName in FAMILY_LABELLED_BUTTONS)
            {
                val label = labelFor(buttonName, family)
                out += label
                out += kick(true, label)
                out += bleed(true, label)
                out += confirm(true, label)
                out += pressStart(true, label)
                out += playAgain(true, label)
                out += initialsHelp(true, label)
                out += legend(true, label, label)
                out += resume(true, label)
                out += goBack(true, label)
                out += exitHold(true, label, label)
            }
        }
        return out
    }
}
