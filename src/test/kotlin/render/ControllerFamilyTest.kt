package render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a GLFW device name is allowed to mean.
 *
 * The stakes are asymmetric and that is what these cases are shaped around. A MISSED match
 * costs nothing — the pad falls back to [ControllerFamily.GENERIC], i.e. the labels this game
 * has always printed. A WRONG match tells a player at a booth to press a button their hardware
 * does not have. So the generic cases below are the load-bearing ones: they are the assertion
 * that the arcade cabinet's own encoder cannot be talked into a console legend.
 */
class ControllerFamilyTest
{
    /**
     * The one string in this file that is not an invention. Measured on the owner's machine:
     * `glfwGetJoystickName(0)` returns exactly this, gamepad-mapped at id 0. Everything else
     * here is a plausible variant; this one is ground truth, and it is the pad the feature was
     * asked for.
     */
    private val MEASURED_DUALSENSE = "DualSense Wireless Controller"

    @Test
    fun `the measured DualSense name is a PlayStation pad`()
    {
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor(MEASURED_DUALSENSE))
    }

    @Test
    fun `matching is case-insensitive`()
    {
        // The same device reports different casing across drivers and transports, and nothing
        // downstream normalises it. Asserted on the measured name rather than a made-up one so
        // this cannot pass against a string no hardware produces.
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor(MEASURED_DUALSENSE.uppercase()))
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor(MEASURED_DUALSENSE.lowercase()))
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor("dUaLsEnSe wireless controller"))
        assertEquals(ControllerFamily.XBOX, ControllerFamily.familyFor("XBOX WIRELESS CONTROLLER"))
        assertEquals(ControllerFamily.XBOX, ControllerFamily.familyFor("xbox wireless controller"))
    }

    @Test
    fun `every documented PlayStation token is actually matched`()
    {
        // The token list is the whole feature, so it is re-stated here rather than read back
        // out of the object: this fails if a token is dropped from the production table, which
        // a test that iterated the production table could not do.
        val names = listOf(
            "DualSense Wireless Controller",
            "Wireless Controller (DualShock 4)",
            "PlayStation(R) Controller",
            "PS3 Controller",
            "PS4 Controller",
            "PS5 Controller",
            "Sony Interactive Entertainment Wireless Controller"
        )
        for (name in names)
        {
            assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor(name), name)
        }
    }

    @Test
    fun `every documented Xbox token is actually matched`()
    {
        val names = listOf(
            "Xbox Wireless Controller",
            "Xbox Elite Wireless Controller",
            "Xbox 360 Controller (XInput STANDARD GAMEPAD)",
            "XInput Gamepad"
        )
        for (name in names)
        {
            assertEquals(ControllerFamily.XBOX, ControllerFamily.familyFor(name), name)
        }
    }

    @Test
    fun `the hyphenated Linux xpad name falls back to generic, and that is the accepted limit`()
    {
        // "Microsoft X-Box 360 pad" is the name the Linux xpad driver reports, and the hyphen
        // means the `xbox` token does not match it. Pinned rather than fixed: the game ships as
        // a Windows .exe where the same pad enumerates through XInput as "Xbox 360 Controller
        // (XInput STANDARD GAMEPAD)" and matches on both tokens, and the cost of the miss is a
        // pad that prints A/B/X/Y - which is what an Xbox pad has silkscreened on it anyway.
        // This test exists so the limit is a recorded decision instead of an unnoticed gap.
        assertEquals(ControllerFamily.GENERIC, ControllerFamily.familyFor("Microsoft X-Box 360 pad"))
    }

    @Test
    fun `a token is found wherever it sits in the name`()
    {
        // Substring, not prefix: the device string is manufacturer-formatted and the model word
        // lands at the front, the back and the middle depending on the driver.
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor("DualSense"))
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor("Nacon Revolution 5 Pro for PS5"))
        assertEquals(ControllerFamily.PLAYSTATION, ControllerFamily.familyFor("Sony Corp. Controller"))
        assertEquals(ControllerFamily.XBOX, ControllerFamily.familyFor("8BitDo Ultimate 2C for Xbox"))
    }

    @Test
    fun `null, empty and blank names are generic`()
    {
        // `glfwGetGamepadName` returns null for a joystick index that is present but not
        // gamepad-mapped - the exact case `logGamepadDiagnostics` exists to distinguish - so
        // null is a real value arriving from real hardware, not defensive padding.
        assertEquals(ControllerFamily.GENERIC, ControllerFamily.familyFor(null))
        assertEquals(ControllerFamily.GENERIC, ControllerFamily.familyFor(""))
        assertEquals(ControllerFamily.GENERIC, ControllerFamily.familyFor("   "))
        assertEquals(ControllerFamily.GENERIC, ControllerFamily.familyFor("\t\n"))
    }

    @Test
    fun `an arcade encoder is generic, and this is the case that matters at the booth`()
    {
        // The cabinet ships with a generic USB encoder whose reported name nobody has seen yet.
        // Every one of these must fall through: a console legend on a stick with no console
        // buttons on it is worse than the enum names the game printed before this feature.
        val names = listOf(
            "Generic USB Joystick",
            "USB Encoder",
            "Ultimarc I-PAC",
            "DragonRise Inc. Generic USB Joystick",
            "Arcade Fightstick",
            "2 Axis 8 Button Gamepad",
            "Logitech Dual Action",
            "Nintendo Switch Pro Controller",
            "Wireless Controller"
        )
        for (name in names)
        {
            assertEquals(ControllerFamily.GENERIC, ControllerFamily.familyFor(name), name)
        }
    }

    @Test
    fun `a name carrying both vocabularies resolves to PlayStation deterministically`()
    {
        // No shipping device does this. The assertion is that the ANSWER IS FIXED rather than
        // decided by which branch happens to be written first - a third-party pad advertising
        // compatibility with both consoles must not relabel the cabinet's screens differently
        // between two builds that only reordered a `when`.
        assertEquals(
            ControllerFamily.PLAYSTATION,
            ControllerFamily.familyFor("PowerA Wired Controller for Xbox and PS4")
        )
    }
}
