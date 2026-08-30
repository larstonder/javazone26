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
    fun `every gamepad button name produces a drawable, non-empty label on every controller`()
    {
        // This is what makes "derive the hint from live config" safe. A technician can bind
        // any of these ~20 names in application.cfg; none of them may vanish into the atlas
        // gap or come out blank. The family loop is the same guarantee for the legend a
        // console pad swaps in: a label is chosen by hardware the build never sees, so every
        // reachable combination has to be drawable, not just the ones someone thought of.
        for (family in ControllerFamily.entries)
        {
            for (button in GamepadButton.entries)
            {
                val label = ControlHints.labelFor(button.name, family)
                assertTrue(label.isNotBlank(), "$family ${button.name} produced a blank label")
                assertTrue(
                    DefaultFont.undrawableCodePointsIn(label).isEmpty(),
                    "$family ${button.name} -> \"$label\" is not drawable"
                )
            }
        }
    }

    @Test
    fun `the family label table renders each console's own legend`()
    {
        // The table from the controller-parity design, asserted whole. The PlayStation column
        // is the reason the feature exists (a PS5 pad on the owner's desk was told to press
        // A); the XBOX column is nearly the identity and is asserted anyway, because "nearly"
        // is exactly the kind of table someone later fills in with symbols.
        val expected = listOf(
            //          enum      GENERIC    PLAYSTATION  XBOX
            Triple("A", "A", listOf("CROSS", "A")),
            Triple("B", "B", listOf("CIRCLE", "B")),
            Triple("X", "X", listOf("SQUARE", "X")),
            Triple("Y", "Y", listOf("TRIANGLE", "Y")),
            Triple("START", "START", listOf("OPTIONS", "MENU")),
            Triple("BACK", "BACK", listOf("CREATE", "VIEW"))
        )
        for ((name, generic, console) in expected)
        {
            assertEquals(generic, ControlHints.labelFor(name, ControllerFamily.GENERIC), "GENERIC $name")
            assertEquals(console[0], ControlHints.labelFor(name, ControllerFamily.PLAYSTATION), "PLAYSTATION $name")
            assertEquals(console[1], ControlHints.labelFor(name, ControllerFamily.XBOX), "XBOX $name")
        }
    }

    @Test
    fun `a button no family renames is unchanged everywhere`()
    {
        // The other half of the table's last row. A shoulder button, a stick click and a d-pad
        // direction have no console-specific legend worth printing, and the underscore-to-space
        // rendering must survive the family branch rather than being reimplemented inside it.
        for (family in ControllerFamily.entries)
        {
            assertEquals("RIGHT BUMPER", ControlHints.labelFor("RIGHT_BUMPER", family), "$family")
            assertEquals("LEFT THUMB", ControlHints.labelFor("LEFT_THUMB", family), "$family")
            assertEquals("DPAD UP", ControlHints.labelFor("DPAD_UP", family), "$family")
            assertEquals("GUIDE", ControlHints.labelFor("GUIDE", family), "$family")
        }
    }

    @Test
    fun `GENERIC is byte-identical to the label this game printed before families existed`()
    {
        // The booth's own USB encoder is GENERIC, and its screens must not move by one
        // character for a feature that exists for consoles. The old implementation is written
        // out longhand here on purpose: comparing against ControlHints' own constant would
        // pass no matter what that constant became.
        for (button in GamepadButton.entries)
        {
            val before = button.name.replace('_', ' ')
            assertEquals(before, ControlHints.labelFor(button.name, ControllerFamily.GENERIC), button.name)
            assertEquals(before, ControlHints.labelFor(button.name), "${button.name} (default family)")
        }
    }

    @Test
    fun `every family legend is an ASCII word, never a PlayStation glyph`()
    {
        // CROSS/CIRCLE/SQUARE/TRIANGLE are stand-ins, not a naming preference. The real symbols
        // are U+2715 / U+25CB / U+25A1 / U+25B3, far above the default font's U+011F ceiling,
        // and would render as nothing at all - no glyph, no x-advance, no warning. The atlas
        // sweep above already catches that; this states the RULE, so the failure message names
        // the reason instead of leaving the next person to rediscover it from a blank screen.
        for (family in ControllerFamily.entries)
        {
            for (button in GamepadButton.entries)
            {
                val label = ControlHints.labelFor(button.name, family)
                assertTrue(
                    label.all { it == ' ' || it in 'A'..'Z' || it in '0'..'9' },
                    "$family ${button.name} -> \"$label\" is not an ASCII word - the default " +
                    "font draws only U+0020..U+011F, so a symbol here vanishes silently"
                )
            }
        }
    }

    @Test
    fun `all() covers every family legend and the composites that carry it`()
    {
        // ControlHints.all() is the sweep of record for these strings; a legend that never
        // reaches it is a string the font check never sees. Every label a console pad can put
        // on screen has to be in there ON ITS OWN and inside each hint that embeds it, because
        // the join is where a separator or a stray character would appear.
        val all = ControlHints.all()
        for (family in ControllerFamily.entries)
        {
            for (name in listOf("A", "B", "X", "Y", "START", "BACK"))
            {
                val label = ControlHints.labelFor(name, family)
                assertTrue(all.contains(label), "$family $name -> \"$label\" missing from all()")
                assertTrue(all.contains(ControlHints.pressStart(true, label)), "pressStart $label")
                assertTrue(all.contains(ControlHints.playAgain(true, label)), "playAgain $label")
                assertTrue(all.contains(ControlHints.initialsHelp(true, label)), "initialsHelp $label")
                assertTrue(all.contains(ControlHints.legend(true, label, label)), "legend $label")
            }
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
