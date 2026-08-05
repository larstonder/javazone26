package render

/**
 * True if the lifecycle action (start/restart) should fire this frame: the keyboard key
 * was clicked, OR any one of the currently connected gamepads reads the action button(s)
 * pressed.
 *
 * Deliberately scans EVERY connected gamepad rather than only the first. At a booth,
 * index 0 in `engine.input.gamepads` is not guaranteed to be the cabinet's own stick — a
 * second device left plugged in from testing, a presenter remote, or any other HID GLFW
 * happens to map as a gamepad can take slot 0. If lifecycle input only looked at
 * `firstOrNull()`, the cabinet's actual START/A press would silently do nothing while that
 * other device sat at index 0. "Any button to start/restart" must mean any gamepad, not
 * just the first one enumerated.
 *
 * This does NOT apply to gameplay (movement/kick/bleed) — see [EnPustTil.readInput], which
 * deliberately keeps `firstOrNull()`: two people fighting over one diver via two different
 * pads is worse than one person plugged into the wrong slot.
 *
 * [gamepadActionPressed] is one boolean per connected gamepad, already evaluated by the
 * caller (typically `pad.isPressed(RESTART_BUTTON) || pad.isPressed(RESTART_BUTTON_ALT)`).
 * Kept as a plain `List<Boolean>` rather than taking `Gamepad`/`GamepadButton` directly so
 * this stays pure and unit-testable without booting the engine.
 */
fun anyLifecycleActionPressed(keyPressed: Boolean, gamepadActionPressed: List<Boolean>): Boolean =
    keyPressed || gamepadActionPressed.any { it }
