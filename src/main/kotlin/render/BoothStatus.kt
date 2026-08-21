package render

/**
 * The one line on the attract screen an attendant can check at a glance.
 *
 * It exists because real faults are otherwise INVISIBLE at the cabinet — the game keeps
 * looking perfect while something underneath it has already broken:
 *
 *  1. **An unmapped USB encoder.** A generic arcade encoder with no SDL gamepad mapping is
 *     completely absent from `engine.input.gamepads` while working fine at the OS level;
 *     `EnPustTil.readInput`'s `?.` chain degrades to all-zeros, so the stick and both
 *     buttons do nothing and the game looks perfect. The existing red overlay for this is
 *     EPT_DEV gated, so a release build never draws it.
 *  2. **A stuck source** — see [LifecycleInputEdges.stuckCount]. Now handled gracefully,
 *     which means it announces itself even less than it used to.
 *  3. **A chattering source** — see [LifecycleInputEdges.chatterCount]. The mirror image of
 *     a stuck button and, unhandled, the WORSE failure of the two: it burns all three
 *     `InitialsEntry` slots in a fraction of a second and auto-submits "AAA", destroying a
 *     player's leaderboard entry, and restarts the cabinet before anyone reads their score.
 *     It is counted and labelled separately from a stuck source deliberately — a stuck
 *     button and a chattering one call for different physical fixes (power-cycle the
 *     cabinet vs. reseat a connector), and folding both into one number would tell an
 *     attendant something is wrong without telling them which repair to attempt.
 *  4. **A failed boot.** `EnPustTil.bootFailed` is `guard.failureCount(SITE_CREATE) > 0`; if
 *     `createGame` threw, `sim`/`scoreRepository` are left `lateinit`-unset, so every later
 *     frame throws and is swallowed by `CallbackGuard` — a black-but-alive cabinet,
 *     indistinguishable on screen from a machine that is simply off. This is the MOST
 *     SEVERE thing this line can say, so it is worded to be unmistakable rather than terse
 *     like every other segment, and [EnPustTil.renderGame] draws it as the entire frame
 *     (there is no `DiveSim` left to draw a world or a HUD around) rather than as one
 *     ignorable line among many.
 *  5. **A mistyped `dailySeed`.** `parseDailySeed` is `raw?.toLongOrNull() ?: fallback` by
 *     design — a typo must never crash the booth machine — so day two would quietly run day
 *     one's column and append to day one's leaderboard. Showing the ACTIVE seed makes the
 *     day-two switch verifiable without a log: the number either changed or it did not.
 *
 * All of these reach the booth log file (see `booth/BoothLog.kt`), but nobody reads a log
 * with a queue waiting.
 *
 * A pure String function so `AttractScreenTest` (root package — see its `DefaultFont`
 * sweep) can assert every character it can ever produce is inside the default
 * font's U+0020..U+011F range. That is why every segment here is plain ASCII: this is drawn
 * with the engine's default font, which bakes only that range and silently draws NOTHING —
 * no glyph, no x-advance — for anything above it.
 */
object BoothStatus
{
    /**
     * A healthy cabinet returns just `SEED <n>`. Every fault appends its own segment, so
     * two (or more) simultaneous faults are all visible rather than the first one winning —
     * the same reasoning [LifecycleInputEdges] uses for keeping stuck and chatter separate.
     *
     * [bootFailed] leads rather than competing for attention with the seed, because it is
     * the one case where the cabinet cannot recover on its own and needs a human to
     * power-cycle it; every other segment describes a degraded-but-running booth.
     */
    fun line(
        seed: Long,
        unmappedPads: Int,
        stuckSources: Int,
        chatterSources: Int,
        callbackFailures: Int,
        lastFailureSite: String?,
        bootFailed: Boolean
    ): String
    {
        val builder = StringBuilder("SEED ").append(seed)

        if (bootFailed)
            builder.append("  !! BOOT FAILED - RESTART THE CABINET !!")

        if (unmappedPads > 0)
            builder.append("  ").append(unmappedPads).append(" UNMAPPED PAD")

        if (stuckSources > 0)
            builder.append("  ").append(stuckSources).append(" STUCK INPUT")

        if (chatterSources > 0)
            builder.append("  ").append(chatterSources).append(" CHATTERING INPUT")

        if (callbackFailures > 0)
        {
            builder.append("  ").append(callbackFailures).append(" FAULT")
            lastFailureSite?.let { builder.append(" IN ").append(it) }
        }

        return builder.toString()
    }
}
