package render

/**
 * Which button legend is silkscreened on the pad in front of the player.
 *
 * WHY THIS EXISTS. [ControlHints.labelFor] renders a `GamepadButton`'s own enum name, and its
 * KDoc used to record that as an accepted limit because "there is no source for the physical
 * legend". There is one. `InputImpl` filters `glfwJoystickPresent(i) && glfwJoystickIsGamepad(i)`
 * and constructs `Gamepad(i)`, so `Gamepad.id` *is* the raw GLFW joystick index — verified from
 * the 0.13.0 bytecode — which makes `glfwGetGamepadName(pad.id)` directly callable. `EnPustTil`
 * already calls GLFW directly in `logGamepadDiagnostics`, so no new dependency is involved.
 * Measured on the owner's machine: `glfwGetJoystickName(0)` returns exactly
 * `"DualSense Wireless Controller"`, gamepad-mapped at id 0, and that literal is a test case in
 * [ControllerFamilyTest] rather than a plausible-looking invention.
 *
 * THIS IS A LABELLING CHANGE AND NOTHING ELSE. On a DualSense, `GamepadButton.A` and
 * `GamepadButton.CROSS` share code 0 (the engine declares both), so `kickButton = A` already
 * binds the physical Cross button. Nothing was ever misbound; only the printed string was
 * wrong, and only the printed string changes.
 *
 * PURE AND ENGINE-FREE, exactly like [ControlHints], [Framing] and [RunLifecycle]. It takes the
 * device name as a plain `String?`, never a `Gamepad` — the GLFW read happens at the call site
 * in `EnPustTil.refreshControlHints`, which already exists to rebuild the hint cache only when
 * the hardware changed (`glfwGetGamepadName` allocates a `String`, and CLAUDE.md forbids
 * per-frame allocation in the render path).
 *
 * SUBSTRING MATCHING, NOT A TABLE OF EXACT NAMES. The same physical pad reports different
 * strings depending on the OS, the driver and whether it is on USB or Bluetooth — the SDL
 * mapping database carries dozens of variants per device — so an exact-name table would be a
 * list this project could never finish and would silently fall out of date with each new
 * firmware. A handful of tokens that appear in essentially all of them is the version that
 * keeps working.
 *
 * GENERIC IS THE FALLBACK ON PURPOSE, and an unrecognised pad is therefore a NON-EVENT: it gets
 * today's labels, byte for byte. That is what makes this safe to ship to a booth whose USB
 * arcade encoder reports something nobody has ever seen — the failure mode of a missed match is
 * "the screen says what it said last week", not "the screen says the wrong button". The
 * asymmetry is deliberate: a wrong guess is worse than no guess, because a player who is told
 * to press CROSS on an encoder that has no Cross button is stuck, while a player told to press
 * `A` on an unlabelled encoder is exactly where they were before.
 */
enum class ControllerFamily
{
    PLAYSTATION,
    XBOX,
    GENERIC;

    companion object
    {
        /**
         * Lower-case because the match is case-insensitive at the call below, not because the
         * incoming name is: real names arrive as `"DualSense Wireless Controller"`,
         * `"PS4 Controller"`, `"Sony Interactive Entertainment Wireless Controller"`.
         *
         * `ps3`/`ps4`/`ps5` are the bare-substring risk in this list — they would match inside
         * a longer word. No shipping controller name contains them by accident, and the cost of
         * a false positive is one mislabelled screen rather than a mislabelled BINDING (see the
         * class doc), so a word-boundary regex would buy nothing for a per-hardware-change
         * allocation.
         */
        private val PLAYSTATION_TOKENS = arrayOf(
            "dualsense",
            "dualshock",
            "playstation",
            "ps3",
            "ps4",
            "ps5",
            "sony"
        )

        /** `xinput` covers the driver-shim names that never spell the console out. */
        private val XBOX_TOKENS = arrayOf(
            "xbox",
            "xinput"
        )

        /**
         * Case-insensitive substring match on the GLFW device name. `null`, blank and anything
         * unrecognised are [GENERIC] — see the class doc for why that fallback is the safe one.
         *
         * PlayStation is tested before Xbox so the answer is deterministic for a name that
         * somehow contained both; no real device does, but "whichever branch happened to be
         * first" is not a thing to leave to editing order in a file that decides what a queue
         * reads off the cabinet.
         *
         * `contains(ignoreCase = true)` rather than lowercasing the name first: it allocates
         * nothing, and this is called from the hint-cache rebuild where the whole point is that
         * the rebuild is rare.
         */
        fun familyFor(deviceName: String?): ControllerFamily
        {
            if (deviceName.isNullOrBlank()) return GENERIC

            for (token in PLAYSTATION_TOKENS)
            {
                if (deviceName.contains(token, ignoreCase = true)) return PLAYSTATION
            }
            for (token in XBOX_TOKENS)
            {
                if (deviceName.contains(token, ignoreCase = true)) return XBOX
            }
            return GENERIC
        }
    }
}
