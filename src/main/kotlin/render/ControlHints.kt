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

    private const val PAD_SWIM = "STICK"
    private const val PAD_CYCLE = "STICK UP/DOWN"

    /**
     * The face and system buttons whose printed legend differs from their `GamepadButton`
     * name, per family. A name that is absent falls through to the enum's own name — which is
     * why [ControllerFamily.GENERIC] has no table at all: it IS that fallback, i.e. exactly
     * what this file returned before families existed, so the booth's own USB encoder keeps
     * byte-identical screens.
     *
     * XBOX carries only START and BACK because an Xbox pad's face buttons genuinely ARE
     * A/B/X/Y. Identity rows would be a second copy of the truth to keep in step, with no
     * behaviour behind them.
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
        "BACK" to "CREATE"
    )

    private val XBOX_LABELS = mapOf(
        "START" to "MENU",
        "BACK" to "VIEW"
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
            }
        }
        return out
    }
}
