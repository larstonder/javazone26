package render

import DefaultFont
import ScreenText
import no.njoh.pulseengine.core.input.GamepadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sweep of record for every string that names a control.
 *
 * `AttractScreenTest` sweeps `ScreenText.all()`, which is a static list — but these strings
 * are COMPOSED at runtime from a config-dependent button label, so they can never appear in
 * it. Adding the fixed fragments there covers the fragments only: not the composition, and
 * not whether the separator survived the join. That is this class's job.
 */
class ControlHintsTest
{
    @Test
    fun `every control hint is inside the default font's baked atlas`()
    {
        for (text in ControlHints.all())
        {
            val missing = DefaultFont.undrawableCodePointsIn(text)
            assertTrue(
                missing.isEmpty(),
                "\"$text\" contains ${missing.map { "U+%04X".format(it) }}, which the default " +
                "font cannot draw - it renders as nothing at all, silently. See DefaultFont."
            )
        }
    }

    @Test
    fun `every gamepad button name produces a drawable, non-empty label`()
    {
        // This is what makes "derive the hint from live config" safe. A technician can bind
        // any of these ~20 names in application.cfg; none of them may vanish into the atlas
        // gap or come out blank.
        for (button in GamepadButton.entries)
        {
            val label = ControlHints.labelFor(button.name)
            assertTrue(label.isNotBlank(), "${button.name} produced a blank label")
            assertTrue(
                DefaultFont.undrawableCodePointsIn(label).isEmpty(),
                "${button.name} -> \"$label\" is not drawable"
            )
        }
    }

    @Test
    fun `the arcade and keyboard forms of every hint differ`()
    {
        // Catches a copy-paste that wires both branches of a `if (arcade)` to the same
        // constant - which would compile, pass a "is it drawable" test, and silently show
        // keyboard keys on the cabinet.
        assertFalse(ControlHints.swim(true) == ControlHints.swim(false), "swim")
        assertFalse(ControlHints.kick(true, "A") == ControlHints.kick(false, "A"), "kick")
        assertFalse(ControlHints.bleed(true, "B") == ControlHints.bleed(false, "B"), "bleed")
        assertFalse(ControlHints.confirm(true, "START") == ControlHints.confirm(false, "START"), "confirm")
        assertFalse(ControlHints.cycle(true) == ControlHints.cycle(false), "cycle")
    }

    @Test
    fun `a rebound button reaches the screen`()
    {
        // The defect this whole file exists for: PRESS_START used to be the literal
        // "PRESS START" while restartButton is config-driven, so a rebind made the screen
        // lie. Every arcade-form hint must carry the label it was handed.
        assertTrue(ControlHints.kick(true, "RIGHT BUMPER").contains("RIGHT BUMPER"))
        assertTrue(ControlHints.bleed(true, "LEFT BUMPER").contains("LEFT BUMPER"))
        assertTrue(ControlHints.pressStart(true, "BACK").contains("BACK"))
        assertTrue(ControlHints.playAgain(true, "BACK").contains("BACK"))
        assertTrue(ControlHints.initialsHelp(true, "BACK").contains("BACK"))
        assertTrue(ControlHints.legend(true, "X", "Y").contains("X"))
        assertTrue(ControlHints.legend(true, "X", "Y").contains("Y"))
    }

    @Test
    fun `the keyboard forms ignore the gamepad label entirely`()
    {
        // Keyboard bindings are hard-coded in readInput (Key.Z, Key.X, the arrows) and in
        // updateGame (Key.SPACE). There is no config key for any of them, so a keyboard hint
        // that varied with a gamepad label would be reporting a binding that does not exist.
        assertEquals(ControlHints.kick(false, "A"), ControlHints.kick(false, "RIGHT BUMPER"))
        assertEquals(ControlHints.bleed(false, "B"), ControlHints.bleed(false, "GUIDE"))
        assertEquals(ControlHints.confirm(false, "START"), ControlHints.confirm(false, "BACK"))
        assertEquals(ControlHints.legend(false, "A", "B"), ControlHints.legend(false, "X", "Y"))
    }

    @Test
    fun `composed hints join with ScreenText SEPARATOR, not a raw run of spaces`()
    {
        // INITIALS_HELP used to separate its two halves with a raw triple space while every
        // other string in the game used SEPARATOR. At arcade viewing distance a run of
        // spaces reads as a gap in the sentence; the dot reads as a deliberate beat.
        for (arcade in listOf(true, false))
        {
            val legend = ControlHints.legend(arcade, "A", "B")
            assertTrue(legend.contains(ScreenText.SEPARATOR), "legend(arcade=$arcade)")
            assertFalse(legend.contains("   "), "legend(arcade=$arcade) has a raw space run")

            val help = ControlHints.initialsHelp(arcade, "START")
            assertTrue(help.contains(ScreenText.SEPARATOR), "initialsHelp(arcade=$arcade)")
            assertFalse(help.contains("   "), "initialsHelp(arcade=$arcade) has a raw space run")
        }
    }

    @Test
    fun `all() covers both device forms of every composed hint`()
    {
        // A guard on the sweep itself: if someone adds a composite and forgets to list it
        // here, the atlas test above silently stops covering it.
        val all = ControlHints.all()
        for (arcade in listOf(true, false))
        {
            assertTrue(all.contains(ControlHints.pressStart(arcade, "START")), "pressStart $arcade")
            assertTrue(all.contains(ControlHints.playAgain(arcade, "START")), "playAgain $arcade")
            assertTrue(all.contains(ControlHints.initialsHelp(arcade, "START")), "initialsHelp $arcade")
            assertTrue(all.contains(ControlHints.legend(arcade, "A", "B")), "legend $arcade")
        }
    }
}
