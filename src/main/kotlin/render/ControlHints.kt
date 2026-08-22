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
     * A `GamepadButton` name rendered for a screen at arcade viewing distance.
     *
     * ACCEPTED LIMIT: this is the enum's own name, which on a generic USB encoder bears no
     * relation to anything silkscreened on the cabinet. There is no source for the physical
     * legend, and the enum name is at least the same string the technician typed into
     * application.cfg.
     */
    fun labelFor(buttonName: String): String = buttonName.replace('_', ' ')

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
     * Every string this object can produce, at both device settings and at the widest button
     * labels, for the font-atlas sweep. Test-only; cheap enough not to warrant a flag, same
     * as `ScreenText.all()`.
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
        return out
    }
}
