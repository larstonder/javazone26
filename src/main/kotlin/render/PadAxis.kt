package render

/**
 * One axis of steering, resolved across the three devices that can drive it: the analog
 * stick, the D-pad, and the keyboard. Pure Kotlin, no engine imports.
 *
 * ## WHY THIS EXISTS AT ALL
 *
 * The D-pad was not read anywhere in this project. `readInput` took `GamepadAxis.LEFT_X` /
 * `LEFT_Y` and nothing else, and `GamepadButton.DPAD_UP` / `DOWN` / `LEFT` / `RIGHT` — all
 * four of which the engine declares — appeared in no source file. On a console pad that is a
 * missing convenience. At the booth it is a cabinet-killer: a generic USB arcade encoder's
 * joystick commonly enumerates as a HAT rather than as analog axes, and in that case the
 * cabinet comes up perfectly — `restartButton` is a button, and buttons work — while the
 * diver never moves. That is the same "startable, unplayable run" class of failure
 * `selectGameplayPad` exists to prevent, arriving through a different door, and it would
 * present at the stand as *the game is broken* with nothing at all in the log.
 *
 * ## WHY IT IS A SEPARATE OBJECT RATHER THAN THREE LINES IN `readInput`
 *
 * The project's pure-logic-extracted-for-testing convention (CLAUDE.md): the RELATIONSHIP
 * between the numbers is pulled out into an engine-free object so it can be asserted with no
 * GL context, exactly as [Framing], [DepthBlend], [AimAngle], [ControlHints] and
 * `RunLifecycle` are. Precedence and the both-directions tie-break below are relationships
 * worth asserting; a `Gamepad` is not something a unit test can hold.
 *
 * ## THE CONTRACT
 *
 * Precedence is analog -> D-pad -> keyboard, and it is FIRST LIVE SOURCE WINS rather than a
 * sum: two devices are never mixed, so a resting keyboard cannot dilute a deflected stick and
 * a leaning stick cannot be nudged by an arrow key.
 *
 * `analog` arrives ALREADY DEADZONED — `readInput` applies its own `Float.deadzone()`, which
 * is where `stickDeadzone` from application.cfg is honoured — so `analog != 0f` is the whole
 * test for "the stick is live". Nothing here re-derives a threshold, because a second copy of
 * that number is a second thing to keep in step with the config key.
 *
 * A live `analog` passes through AT FULL MAGNITUDE and is deliberately NOT normalised to
 * +/-1: the stick is proportional and `DiveSim`'s swim model reads it that way, so a
 * half-deflected stick must stay a half-deflected stick. Quantising it here would silently
 * turn every pad in the world into a D-pad.
 *
 * Allocation-free and branch-only. This is called twice per frame from `readInput`, which is
 * on the per-frame path this project forbids allocation on.
 */
object PadAxis
{
    /**
     * The live source's own value for one axis.
     *
     * @param analog the stick reading, ALREADY deadzoned by the caller; non-zero means live.
     * @param negativePressed the D-pad direction that steers toward negative (left, or up in
     *   the +y-down world space this game draws in — the caller decides which button that is,
     *   the same way `EnPustTil.axis(negative, positive)` already does for the keyboard).
     * @param positivePressed the opposing D-pad direction.
     * @param keyboard the keyboard axis, already resolved to -1/0/+1 by the caller.
     */
    fun resolve(analog: Float, negativePressed: Boolean, positivePressed: Boolean, keyboard: Float): Float
    {
        // The stick, unchanged and unquantised, whenever it is off its rest position.
        if (analog != 0f) return analog

        // BOTH D-PAD DIRECTIONS AT ONCE RESOLVES TO ZERO, DETERMINISTICALLY. A hat cannot
        // physically report left and right together, so this looks like an impossible case
        // — but a stuck contact on a booth USB encoder can, and then the OTHER direction is
        // still a live, sensible input the player is pressing. Resolving it by the order the
        // branches happen to be written in would hand the win to whichever side the compiler
        // sees first and leave the diver swimming sideways for the rest of the day. That is
        // exactly the unattended-cabinet failure this codebase keeps designing against, so
        // the tie is cancelled on purpose and the axis simply goes dead until the contact
        // clears. It is checked BEFORE either single-direction branch for that reason.
        if (negativePressed && positivePressed) return 0f
        if (negativePressed) return -1f
        if (positivePressed) return 1f

        return keyboard
    }
}
