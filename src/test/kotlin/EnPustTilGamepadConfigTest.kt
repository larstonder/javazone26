import no.njoh.pulseengine.core.input.GamepadButton
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The booth may have to remap buttons with Notepad on setup day - see application.cfg. A
 * bad value must always degrade to the compiled default rather than crash or, worse, leave
 * a button silently unbound in front of a queue.
 *
 * Also covers [resolveDeadzone] (the Int/Float coercion gap `stickDeadzone = 0` falls into
 * — same bug family as [resolveDailySeed], see `EnPustTilSeedTest`) and
 * [gamepadButtonConfigWarning] (the caller-side check that tells a typo apart from a
 * deliberate edit, since [parseGamepadButton] itself cannot signal which one produced a
 * fallback).
 */
class EnPustTilGamepadConfigTest
{
    @Test
    fun `a valid button name is honoured`()
    {
        assertEquals(GamepadButton.X, parseGamepadButton("X", GamepadButton.A))
    }

    @Test
    fun `case and surrounding whitespace do not matter`()
    {
        // The person editing this file is standing at a booth with a screwdriver.
        assertEquals(GamepadButton.START, parseGamepadButton("  start  ", GamepadButton.A))
    }

    @Test
    fun `an absent key falls back to the compiled default`()
    {
        assertEquals(GamepadButton.A, parseGamepadButton(null, GamepadButton.A))
    }

    @Test
    fun `a name that is not a button falls back rather than throwing`()
    {
        // A typo at the booth must never crash the cabinet, and must never leave kick
        // unbound - the same reasoning as parseDailySeed.
        assertEquals(GamepadButton.A, parseGamepadButton("BUTTON_1", GamepadButton.A))
        assertEquals(GamepadButton.B, parseGamepadButton("", GamepadButton.B))
        // TRIANGLE is deliberately NOT the negative case here - it IS a real entry
        // (verified: javap -p no/njoh/pulseengine/core/input/GamepadButton.class lists the
        // PlayStation aliases CROSS/CIRCLE/SQUARE/TRIANGLE alongside A/B/X/Y).
        assertEquals(GamepadButton.TRIANGLE, parseGamepadButton("triangle", GamepadButton.A))
    }

    @Test
    fun `a deadzone is clamped to something usable`()
    {
        // 1.0 or above would make the stick permanently dead; a negative would make a
        // resting stick read as full deflection and the diver would swim on its own.
        assertEquals(0.35f, parseDeadzone(0.35f, 0.2f))
        assertEquals(0.2f, parseDeadzone(null, 0.2f))
        assertEquals(0.9f, parseDeadzone(4f, 0.2f))
        assertEquals(0f, parseDeadzone(-1f, 0.2f))
    }

    @Test
    fun `resolveDeadzone reaches zero through the Int shape, which parseDeadzone alone cannot`()
    {
        // THE GAP THIS CLOSES. "0" (the natural way to write "disable the deadzone") is
        // all-digits, so application.cfg's own loader stores it as an Integer, not a Float -
        // engine.config.getFloat("stickDeadzone") returns null for it. A version reading
        // only getFloat/parseDeadzone would silently keep the compiled default here even
        // though "stickDeadzone = 0" is a perfectly legible edit. Empirically verified
        // against the real ConfigurationImpl - see task-6-7-report.md.
        assertEquals(0f, resolveDeadzone(rawInt = 0, rawFloat = null, fallback = 0.2f))
    }

    @Test
    fun `resolveDeadzone still honours a value written with a decimal point`()
    {
        assertEquals(0.35f, resolveDeadzone(rawInt = null, rawFloat = 0.35f, fallback = 0.2f))
    }

    @Test
    fun `resolveDeadzone falls back when neither shape is present`()
    {
        assertEquals(0.2f, resolveDeadzone(rawInt = null, rawFloat = null, fallback = 0.2f))
    }

    @Test
    fun `resolveDeadzone still clamps an out-of-range Int, same as the Float path`()
    {
        assertEquals(0.9f, resolveDeadzone(rawInt = 4, rawFloat = null, fallback = 0.2f))
    }

    @Test
    fun `gamepadButtonConfigWarning is null when the key was never touched`()
    {
        assertEquals(null, gamepadButtonConfigWarning("kickButton", rawString = null, rawInt = null, rawFloat = null, resolved = GamepadButton.A, default = GamepadButton.A))
    }

    @Test
    fun `gamepadButtonConfigWarning is null for a genuinely different, validly-parsed button`()
    {
        // Not a fallback at all - CROSS was recognised and returned as itself, so
        // `resolved != default` short-circuits the warning before the name comparison.
        assertEquals(null, gamepadButtonConfigWarning("kickButton", rawString = "CROSS", rawInt = null, rawFloat = null, resolved = GamepadButton.CROSS, default = GamepadButton.A))
    }

    @Test
    fun `gamepadButtonConfigWarning is null when the technician deliberately wrote the default's own name back`()
    {
        assertEquals(null, gamepadButtonConfigWarning("kickButton", rawString = "  a  ", rawInt = null, rawFloat = null, resolved = GamepadButton.A, default = GamepadButton.A))
    }

    @Test
    fun `gamepadButtonConfigWarning fires for the exact typo this exists to catch`()
    {
        // restartbutton = STRAT: falls back to START (the default), but the raw text was
        // never "START" - this is the case that used to produce no signal anywhere.
        val warning = gamepadButtonConfigWarning("restartButton", rawString = "STRAT", rawInt = null, rawFloat = null, resolved = GamepadButton.START, default = GamepadButton.START)
        assertEquals(true, warning != null && warning.contains("STRAT"))
    }

    @Test
    fun `gamepadButtonConfigWarning fires for a digit typo that coerces to Int - the case a String-only check misses`()
    {
        // kickButton = 0: an earlier version of this function took only a String raw value
        // and saw getString("kickButton") == null (it coerced to Integer instead), which
        // is indistinguishable from the key being entirely absent - silent. GamepadButton
        // has no purely-numeric name, so rawInt present is unconditionally a typo.
        val warning = gamepadButtonConfigWarning("kickButton", rawString = null, rawInt = 0, rawFloat = null, resolved = GamepadButton.A, default = GamepadButton.A)
        assertEquals(true, warning != null && warning.contains("0"))
    }

    @Test
    fun `gamepadButtonConfigWarning fires for a decimal typo that coerces to Float`()
    {
        val warning = gamepadButtonConfigWarning("bleedButton", rawString = null, rawInt = null, rawFloat = 1.5f, resolved = GamepadButton.B, default = GamepadButton.B)
        assertEquals(true, warning != null && warning.contains("1.5"))
    }

    @Test
    fun `no collision warnings for the compiled defaults`()
    {
        // THE CASE THAT NARROWED THIS FUNCTION: the compiled defaults themselves have
        // kickButton == restartButtonAlt (both A), on purpose - see the class doc. A
        // version warning on every pairwise collision would fire this on every untouched
        // booth boot.
        assertEquals(
            emptyList(),
            gamepadButtonCollisionWarnings(
                GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
                GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
            )
        )
    }

    @Test
    fun `kickButton and bleedButton on the same button is a collision`()
    {
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.A, GamepadButton.START, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, warnings.any { it.contains("kickButton") && it.contains("bleedButton") })
    }

    @Test
    fun `aliases collide even though their names differ - A and CROSS share one physical button`()
    {
        // THE .code FIX. Comparing by name or ordinal would miss this: A and CROSS are
        // different GamepadButton entries (different .name, different .ordinal) but the
        // same physical input (same .code) - verified from the jar's static initialiser.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.CROSS, GamepadButton.START, GamepadButton.START,
            GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, warnings.any { it.contains("kickButton") && it.contains("bleedButton") })
    }

    @Test
    fun `restartButton colliding with restartButtonAlt is NOT reported - it is a supported configuration`()
    {
        // LifecycleInputEdges.offer's duplicate-offer guard exists specifically for this,
        // and it is a reasonable deliberate choice (force both onto a button already known
        // to work) - not a mistake to flag. See the class doc for the RunLifecycle.update
        // source citation backing this.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.X, GamepadButton.B, GamepadButton.A, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `kickButton colliding with restartButton is NOT reported - the two signals are never live at once`()
    {
        // RunLifecycle.update's PLAYING branch reads only runOver/pauseEdge - the restart
        // signal kickButton would collide with is not consulted while a run is in
        // progress, which is the only time kick matters. Verified against RunLifecycle.kt
        // source, not assumed - see the class doc.
        //
        // pauseButtonAlt is Y here rather than its own default START, deliberately: this case
        // points kickButton at START, and pauseButtonAlt on kickButton IS a reported collision
        // (a kick would open the pause screen mid-run). Leaving the default in would have this
        // test fail for a reason that has nothing to do with what it is about.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.START, GamepadButton.B, GamepadButton.START, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.Y, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(emptyList(), warnings)
    }

    // --- pauseButton / exitButtonA / exitButtonB (2026-08-30, controller parity) ---------
    //
    // The three keys that put pause and exit on the pad. Their compiled defaults live in
    // EnPustTil's PRIVATE companion, so they cannot be referenced from here - the literals
    // below are those values written out, and `the compiled defaults for the new keys are
    // real GamepadButton names` is what stops this file and application.cfg's own BUTTON
    // MAP comment from documenting a name the enum does not have.

    @Test
    fun `the compiled defaults for the new keys are real GamepadButton names`()
    {
        // application.cfg tells a technician to write these three words. If any of them
        // were not an entry, parseGamepadButton would silently hand back the fallback and
        // the documented "# pauseButton = BACK" line would be a lie that never logged.
        assertEquals(GamepadButton.BACK, parseGamepadButton("BACK", GamepadButton.A))
        assertEquals(GamepadButton.LEFT_BUMPER, parseGamepadButton("LEFT_BUMPER", GamepadButton.A))
        assertEquals(GamepadButton.RIGHT_BUMPER, parseGamepadButton("RIGHT_BUMPER", GamepadButton.A))
    }

    @Test
    fun `a remapped pause or exit button is honoured, in the technician's casing`()
    {
        // Same "standing at a booth with a screwdriver" allowance the restart keys get.
        assertEquals(GamepadButton.START, parseGamepadButton(" start ", GamepadButton.BACK))
        assertEquals(GamepadButton.LEFT_THUMB, parseGamepadButton("left_thumb", GamepadButton.LEFT_BUMPER))
        assertEquals(GamepadButton.RIGHT_THUMB, parseGamepadButton("Right_Thumb", GamepadButton.RIGHT_BUMPER))
    }

    @Test
    fun `a typo in pauseButton falls back to BACK rather than leaving pause unreachable`()
    {
        assertEquals(GamepadButton.BACK, parseGamepadButton("BAKC", GamepadButton.BACK))
    }

    @Test
    fun `a typo in either exit key falls back rather than leaving the cabinet unclosable`()
    {
        assertEquals(GamepadButton.LEFT_BUMPER, parseGamepadButton("LEFT_BUMBER", GamepadButton.LEFT_BUMPER))
        assertEquals(GamepadButton.RIGHT_BUMPER, parseGamepadButton("", GamepadButton.RIGHT_BUMPER))
    }

    @Test
    fun `gamepadButtonConfigWarning names the new keys so the right line can be found`()
    {
        // The warning is read off a booth log with the file open in Notepad; naming the key
        // is the whole reason it is passed in rather than composed from the button alone.
        val pause = gamepadButtonConfigWarning("pauseButton", rawString = "BAKC", rawInt = null, rawFloat = null, resolved = GamepadButton.BACK, default = GamepadButton.BACK)
        assertEquals(true, pause != null && pause.contains("pauseButton") && pause.contains("BAKC"))

        val exitA = gamepadButtonConfigWarning("exitButtonA", rawString = "L1", rawInt = null, rawFloat = null, resolved = GamepadButton.LEFT_BUMPER, default = GamepadButton.LEFT_BUMPER)
        assertEquals(true, exitA != null && exitA.contains("exitButtonA") && exitA.contains("L1"))

        // The Int shape: `exitButtonB = 5` is a plausible edit for someone reading raw
        // codes off the EPT_DEV overlay, and it coerces to Integer before getString ever
        // sees it - the exact gap the rawInt parameter closes.
        val exitB = gamepadButtonConfigWarning("exitButtonB", rawString = null, rawInt = 5, rawFloat = null, resolved = GamepadButton.RIGHT_BUMPER, default = GamepadButton.RIGHT_BUMPER)
        assertEquals(true, exitB != null && exitB.contains("exitButtonB") && exitB.contains("5"))
    }

    @Test
    fun `gamepadButtonConfigWarning stays silent when the new keys are simply absent`()
    {
        // Day one, nobody has touched the file. Three more untouched keys must not add
        // three more lines to a log that is meant to be read.
        assertEquals(null, gamepadButtonConfigWarning("pauseButton", null, null, null, GamepadButton.BACK, GamepadButton.BACK))
        assertEquals(null, gamepadButtonConfigWarning("exitButtonA", null, null, null, GamepadButton.LEFT_BUMPER, GamepadButton.LEFT_BUMPER))
        assertEquals(null, gamepadButtonConfigWarning("exitButtonB", null, null, null, GamepadButton.RIGHT_BUMPER, GamepadButton.RIGHT_BUMPER))
    }

    @Test
    fun `both exit buttons on one physical button is a collision - the AND would collapse`()
    {
        // THE PROTECTION THIS PROTECTS. Exit is two buttons held together specifically so
        // one stuck contact cannot shut the cabinet down for the day; pointed at one
        // button the expression becomes `x && x` and that is gone, with the file still
        // looking like it names two buttons.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.LEFT_BUMPER
        )
        assertEquals(true, warnings.any { it.contains("exitButtonA") && it.contains("exitButtonB") })
    }

    @Test
    fun `two exit aliases of one button collide too - X and SQUARE are one contact`()
    {
        // The .code comparison, not name or ordinal: X and SQUARE are different entries
        // with the same physical code, so `exitButtonA = X` / `exitButtonB = SQUARE` is
        // exactly as collapsed as writing X twice, and only .code sees it.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.START, GamepadButton.X, GamepadButton.SQUARE
        )
        assertEquals(true, warnings.any { it.contains("exitButtonA") && it.contains("exitButtonB") })
    }

    @Test
    fun `pauseButton on kickButton is a collision - a kick would open the pause screen`()
    {
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
            GamepadButton.A, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, warnings.any { it.contains("pauseButton") && it.contains("kickButton") })
    }

    @Test
    fun `pauseButton on bleedButton is a collision, aliases included`()
    {
        // CIRCLE is B's alias. Both halves of this test matter: that the bleed pair is
        // checked at all (an earlier draft checked only kick), and that it is checked by
        // .code so `bleedButton = B` / `pauseButton = CIRCLE` cannot slip past.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
            GamepadButton.CIRCLE, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, warnings.any { it.contains("pauseButton") && it.contains("bleedButton") })
    }

    @Test
    fun `pauseButton on either restart key IS now reported - CRITICAL C2, final review`()
    {
        // AMENDMENT (2026-08-31): this test used to be named "...is NOT reported - the
        // signals are never live at once" and asserted emptyList(). That was true only
        // while MAIN_MENU read `padBack` from `pauseButtonAlt`; it now reads `pauseButton`
        // (see `EnPustTil.updateMainMenu`'s own doc), so a `pauseButton`/restart-key
        // collision means MAIN_MENU reads confirm and back from the SAME physical button in
        // the SAME state, on the SAME frame - exactly the "live at once" shape this
        // function's doc says is never safe, and no longer the disjoint-states case this
        // test's old name claimed. gamepadButtonCollisionWarnings now flags it.
        val vsRestart = gamepadButtonCollisionWarnings(
            GamepadButton.X, GamepadButton.B, GamepadButton.START, GamepadButton.Y,
            GamepadButton.START, GamepadButton.LEFT_THUMB, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, vsRestart.any { it.contains("pauseButton") && it.contains("restartButton") && !it.contains("restartButtonAlt") })

        val vsRestartAlt = gamepadButtonCollisionWarnings(
            GamepadButton.X, GamepadButton.B, GamepadButton.START, GamepadButton.Y,
            GamepadButton.Y, GamepadButton.LEFT_THUMB, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, vsRestartAlt.any { it.contains("pauseButton") && it.contains("restartButtonAlt") })
    }

    @Test
    fun `pauseButtonAlt on either restart key is STILL not reported - the two pause buttons diverged`()
    {
        // pauseButtonAlt is no longer read by updateMainMenu at all (only pauseButton is,
        // since CRITICAL C2) - it is still read while PLAYING/PAUSED, where a restart edge
        // is genuinely never live, so the original disjoint-states reasoning still holds for
        // THIS button. This is also exactly the compiled shipped default
        // (DEFAULT_PAUSE_BUTTON_ALT == DEFAULT_RESTART_BUTTON == START, see the class doc),
        // which `the compiled defaults produce zero warnings` above already exercises
        // implicitly - this test isolates the claim on its own rather than relying on that.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.X, GamepadButton.B, GamepadButton.START, GamepadButton.Y,
            GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `an exit button sharing a gameplay button is NOT reported - exit is only read while PAUSED`()
    {
        // Neither kick nor bleed is read in PAUSED, and exit is read nowhere else, so an
        // encoder with few buttons may legitimately double these up. Only exitButtonA
        // against exitButtonB is a mistake.
        assertEquals(
            emptyList(),
            gamepadButtonCollisionWarnings(
                GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
                GamepadButton.BACK, GamepadButton.START, GamepadButton.A, GamepadButton.B
            )
        )
    }

    @Test
    fun `two problems in one file produce two warnings, not just the first`()
    {
        // The function used to `return listOf(...)` at its single check. A technician who
        // copy-pasted one block of the button map has plausibly broken more than one line
        // of it, and these warnings are read off a booth log after the fact - there is no
        // second run to surface the rest.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.A, GamepadButton.START, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.LEFT_BUMPER
        )
        assertEquals(2, warnings.size, warnings.toString())
        assertEquals(true, warnings.any { it.contains("kickButton") && it.contains("bleedButton") })
        assertEquals(true, warnings.any { it.contains("exitButtonA") && it.contains("exitButtonB") })
    }

    // --- pauseButtonAlt (a second pause button, mirroring restartButtonAlt) ---------------
    //
    // Its compiled default is START, which is ALSO DEFAULT_RESTART_BUTTON. That coincidence is
    // the feature - Options on a console pad starts a run from attract and pauses one in
    // progress - so the negative case below is as load-bearing as any of the positives.

    @Test
    fun `the compiled default for pauseButtonAlt is a real GamepadButton name`()
    {
        // application.cfg documents "# pauseButtonAlt = START". If START were not an entry,
        // parseGamepadButton would hand back the fallback and that line would be a lie that
        // never logged - the same guarantee the other new keys get one test above.
        assertEquals(GamepadButton.START, parseGamepadButton("START", GamepadButton.A))
    }

    @Test
    fun `a remapped pauseButtonAlt is honoured, in the technician's casing`()
    {
        assertEquals(GamepadButton.GUIDE, parseGamepadButton(" guide ", GamepadButton.START))
        assertEquals(GamepadButton.BACK, parseGamepadButton("Back", GamepadButton.START))
    }

    @Test
    fun `a typo in pauseButtonAlt falls back to START rather than leaving the second pause button dead`()
    {
        // The player-facing one of the two: the pause screen prints THIS button's label, so a
        // typo that silently unbound it would leave the screen naming a control that does
        // nothing. Falling back to START keeps Options working and logs the typo.
        assertEquals(GamepadButton.START, parseGamepadButton("SATRT", GamepadButton.START))
        assertEquals(GamepadButton.START, parseGamepadButton(null, GamepadButton.START))
    }

    @Test
    fun `gamepadButtonConfigWarning names pauseButtonAlt in all three coercion shapes`()
    {
        val typo = gamepadButtonConfigWarning("pauseButtonAlt", rawString = "OPTIONS", rawInt = null, rawFloat = null, resolved = GamepadButton.START, default = GamepadButton.START)
        assertEquals(true, typo != null && typo.contains("pauseButtonAlt") && typo.contains("OPTIONS"))

        // "pauseButtonAlt = 7" is the plausible edit for someone copying a raw code off the
        // EPT_DEV overlay; it coerces to Integer and getString never sees it.
        val int = gamepadButtonConfigWarning("pauseButtonAlt", rawString = null, rawInt = 7, rawFloat = null, resolved = GamepadButton.START, default = GamepadButton.START)
        assertEquals(true, int != null && int.contains("pauseButtonAlt") && int.contains("7"))

        val float = gamepadButtonConfigWarning("pauseButtonAlt", rawString = null, rawInt = null, rawFloat = 7.5f, resolved = GamepadButton.START, default = GamepadButton.START)
        assertEquals(true, float != null && float.contains("pauseButtonAlt") && float.contains("7.5"))
    }

    @Test
    fun `gamepadButtonConfigWarning stays silent when pauseButtonAlt is absent or deliberately written out`()
    {
        assertEquals(null, gamepadButtonConfigWarning("pauseButtonAlt", null, null, null, GamepadButton.START, GamepadButton.START))
        assertEquals(null, gamepadButtonConfigWarning("pauseButtonAlt", "start", null, null, GamepadButton.START, GamepadButton.START))
    }

    @Test
    fun `pauseButtonAlt on kickButton is a collision - a kick would open the pause screen`()
    {
        // Both pause keys are read on the same states, so which one is pointed at kick makes
        // no difference to the defect. An earlier version of this function checked only
        // pauseButton, which would have let the SECOND pause key through unwarned.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.A, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, warnings.any { it.contains("pauseButtonAlt") && it.contains("kickButton") })
    }

    @Test
    fun `pauseButtonAlt on bleedButton is a collision, aliases included`()
    {
        // .code, not name: bleedButton = B and pauseButtonAlt = CIRCLE are one contact.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
            GamepadButton.BACK, GamepadButton.CIRCLE, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(true, warnings.any { it.contains("pauseButtonAlt") && it.contains("bleedButton") })
    }

    @Test
    fun `pauseButtonAlt on restartButton is NOT reported - that collision is the shipped default`()
    {
        // THE NEGATIVE CASE THIS FEATURE STANDS ON. DEFAULT_PAUSE_BUTTON_ALT and
        // DEFAULT_RESTART_BUTTON are both START on purpose: one Options press raises
        // pressedEdge and pauseEdge in the same frame, and RunLifecycle.update's own
        // precedence resolves it correctly in every state (IDLE gives the start the run,
        // PLAYING pauses, PAUSED resumes, the rest ignore pauseEdge). A warning here would
        // fire on EVERY untouched boot for the shipped, tested design - which is exactly the
        // cried-wolf noise that narrowed this function in the first place.
        //
        // These are the compiled defaults written out (EnPustTil's companion is private).
        assertEquals(
            emptyList(),
            gamepadButtonCollisionWarnings(
                GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.A,
                GamepadButton.BACK, GamepadButton.START, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
            )
        )

        // And against restartButtonAlt too, by alias, for the same reason.
        assertEquals(
            emptyList(),
            gamepadButtonCollisionWarnings(
                GamepadButton.X, GamepadButton.B, GamepadButton.START, GamepadButton.Y,
                GamepadButton.BACK, GamepadButton.Y, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
            )
        )
    }

    @Test
    fun `both pause keys pointed at one gameplay button produce two warnings, not one`()
    {
        // The two checks are independent, and a technician who mis-edited the pause block has
        // plausibly mis-edited both lines of it. Reporting only one would send them back to
        // the cabinet twice - these are read off a booth log, not an interactive prompt.
        //
        // restartButtonAlt is Y here, not its old A: with pauseButton = A, A also matching
        // restartButtonAlt would add a THIRD, unrelated warning from CRITICAL C2's new
        // pauseButton/restart check (see the test below) - this test is about the kick pair
        // specifically and must isolate that from a coincidental second collision.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.START, GamepadButton.Y,
            GamepadButton.A, GamepadButton.CROSS, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(2, warnings.size, warnings.toString())
        assertEquals(true, warnings.any { it.contains("pauseButton ") && it.contains("kickButton") })
        assertEquals(true, warnings.any { it.contains("pauseButtonAlt") && it.contains("kickButton") })
    }

    @Test
    fun `pauseButton colliding with a restart key AND a gameplay button produces two independent warnings`()
    {
        // The two pauseButton checks (restart-key, gameplay-button) are independent of each
        // other too, mirroring the test above for pauseButton vs pauseButtonAlt.
        val warnings = gamepadButtonCollisionWarnings(
            GamepadButton.A, GamepadButton.B, GamepadButton.A, GamepadButton.Y,
            GamepadButton.A, GamepadButton.LEFT_THUMB, GamepadButton.LEFT_BUMPER, GamepadButton.RIGHT_BUMPER
        )
        assertEquals(2, warnings.size, warnings.toString())
        assertEquals(true, warnings.any { it.contains("pauseButton ") && it.contains("kickButton") })
        assertEquals(true, warnings.any { it.contains("pauseButton ") && it.contains("restartButton") && !it.contains("restartButtonAlt") })
    }
}
