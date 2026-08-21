package render

/**
 * Which connected gamepad gameplay (movement / kick / bleed) should read this frame:
 * [preferred] if it is still connected, otherwise the first one, otherwise none.
 *
 * [preferred] is the pad whose button actually started the run - see
 * [LifecycleInputEdges.firedPadId], captured by EnPustTil on `RunLifecycle.justStarted`.
 *
 * WHAT THIS REPLACED, and why it was a booth failure. Gameplay used to read
 * `engine.input.gamepads.firstOrNull()` while lifecycle input scanned every pad (see
 * [LifecycleInputEdges]'s class doc). If any stray HID took slot 0 - a second device left
 * plugged in from testing, a presenter remote, anything GLFW happens to map as a gamepad -
 * the cabinet's own START press worked and the diver did not move. A startable, unplayable
 * run, silently repeating for every person in the queue.
 *
 * The asymmetry it was defending is still intact: exactly ONE pad drives the diver, so two
 * people on two pads still cannot fight over one run. It is now the RIGHT one.
 *
 * Falling back to the first pad when [preferred] has vanished is deliberate: a mid-run
 * unplug-and-replug (which changes the id) must not freeze the diver for the rest of the
 * run.
 *
 * Plain `Int` ids rather than `Gamepad` values so this stays pure and unit-testable
 * without booting the engine - same reasoning as [LifecycleInputEdges]'s ordinals-and-ints
 * shape. `Gamepad.id` is an Int (verified: `javap -p
 * no/njoh/pulseengine/core/input/Gamepad.class`).
 *
 * Called on the FIXED-tick update path (`EnPustTil.readInput`), so CLAUDE.md's
 * no-per-frame-allocation rule applies. `padIds.firstOrNull { it == preferred }` would be
 * inlined (no lambda object), but a `List<Int>` still resolves that call through the
 * `Iterable<T>` extension, which allocates one `Iterator` per call — an indexed loop over
 * `padIds.indices` does the identical search with none. The trailing `padIds.firstOrNull()`
 * (no predicate) is the one call left as a stdlib call: Kotlin overloads it specifically
 * for `List<T>` as `if (isEmpty()) null else this[0]`, which is already indexed and
 * allocates nothing — see `EnPustTil.gamepadIdBuffer`'s doc for the equivalent fix on the
 * call site that builds [padIds] in the first place.
 */
fun selectGameplayPad(padIds: List<Int>, preferred: Int?): Int?
{
    for (i in padIds.indices) if (padIds[i] == preferred) return padIds[i]
    return padIds.firstOrNull()
}
