import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `renderGame`'s `worldUnusable` early return (the fix for the Task 5 review's Critical 2)
 * has to run BEFORE the very first read of `sim` in that function, or it does not fix
 * anything: `sim`/`scoreRepository` are `lateinit`, and DiveRenderer.render hands `sim`
 * straight to the GPU a few lines into the method. A future edit that slips a `sim`/
 * `scoreRepository` read above the guard — moving the camera setup earlier, say, or adding
 * a new line at the top "just to log the depth" — would reopen the exact black-but-alive
 * cabinet this task exists to close, and no runtime test would catch it, because it only
 * happens when `createGame` has already failed.
 *
 * Following `EnPustTilDepthPinTest`'s / `MainCameraOwnershipTest`'s pattern: a source scan,
 * because the property being protected is about ORDER OF STATEMENTS in one function, which
 * is not observable through `renderGame`'s return type or any public API.
 */
class RenderGameBootGuardTest
{
    private val body: String
    init
    {
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val afterSignature = source.substringAfter("private fun renderGame()")
        // Bounded to just this function: the next `private fun` declaration in the file.
        body = afterSignature.substring(0, afterSignature.indexOf("\n    private fun"))
    }

    // Comments routinely explain the guard by NAME ("the worldUnusable check...", "not
    // bootFailed...") and mention `sim`/`scoreRepository` in prose well before the real
    // code does anything with them. Both must be stripped before searching for the actual
    // statements, or this test would pass by finding its own commentary.
    private fun stripComments(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `renderGame still has a worldUnusable guard`()
    {
        assertTrue("worldUnusable" in stripComments(body), "renderGame no longer checks worldUnusable at all")
    }

    @Test
    fun `the worldUnusable guard runs before the first read of sim`()
    {
        val code = stripComments(body)
        val guardIndex = code.indexOf("if (worldUnusable)")
        val firstSimIndex = Regex("""\bsim\b""").find(code)?.range?.first

        assertTrue(guardIndex >= 0, "renderGame's `if (worldUnusable)` early return is missing")
        assertTrue(firstSimIndex != null, "renderGame no longer reads `sim` at all — re-read this test")
        assertTrue(
            guardIndex < firstSimIndex!!,
            "a `sim` read at offset $firstSimIndex now comes before the worldUnusable guard at " +
            "offset $guardIndex — a failed createGame would throw on it every frame, exactly the " +
            "black-but-alive cabinet this guard exists to prevent"
        )
    }

    @Test
    fun `the guard returns rather than falling through`()
    {
        val code = stripComments(body)
        val guardBlock = code.substringAfter("if (worldUnusable)").substringBefore("\n\n")
        assertTrue("drawBootFailedScreen" in guardBlock, "the guard no longer routes to drawBootFailedScreen")
        assertTrue("return" in guardBlock, "the guard no longer returns — it would fall through into the lateinit reads below it")
    }

    @Test
    fun `worldUnusable checks scoreRepository as well as sim, not just sim`()
    {
        // worldUnusable guards TWO lateinit fields. sim's half is exercised directly above
        // (DiveRenderer.render hands `sim` to the GPU inside renderGame itself); scoreRepository
        // has no equivalent direct read in renderGame — it is first read one function over, in
        // drawLeaderboard (scoreRepository.topN(...)), reached from renderGame's `when` branch
        // for IDLE/PAUSED. A regression that dropped scoreRepository from the OR — say, "sim is
        // the only one that matters, DiveRenderer proves it" — would leave a booth that boots
        // with sim built but scoreRepository's construction failed (a plausible split failure:
        // they are two separate statements) drawing the WORLD, then throwing on
        // scoreRepository.topN every frame the instant lifecycle.state is IDLE — the exact
        // black-but-alive cabinet this guard exists to prevent, just for the other field.
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val definition = stripComments(source.substringAfter("private val worldUnusable").substringBefore("\n\n"))

        assertTrue("::sim.isInitialized" in definition, "worldUnusable no longer checks sim — re-read this test")
        assertTrue(
            "::scoreRepository.isInitialized" in definition,
            "worldUnusable no longer checks scoreRepository — a createGame failure that leaves " +
            "sim built but scoreRepository unconstructed would pass this guard, reach " +
            "drawLeaderboard, and throw on scoreRepository.topN every IDLE-state frame"
        )
    }

    @Test
    fun `the worldUnusable guard would also catch a scoreRepository read added directly to renderGame`()
    {
        // Unlike sim, renderGame has no CURRENT direct scoreRepository token to check the
        // ordering of (see the class doc: it is one function over, in drawLeaderboard) — so
        // this cannot assert today's code the way the sim ordering test does. What it CAN
        // assert is that IF one were ever added directly to renderGame's own body — a "quick
        // debug line" above the guard, say — it would have to come after the guard to pass,
        // which is exactly the property worth protecting even though nothing exercises it yet.
        val code = stripComments(body)
        val guardIndex = code.indexOf("if (worldUnusable)")
        val scoreRepositoryIndex = Regex("""\bscoreRepository\b""").find(code)?.range?.first

        assertTrue(guardIndex >= 0, "renderGame's `if (worldUnusable)` early return is missing")
        if (scoreRepositoryIndex != null)
            assertTrue(
                guardIndex < scoreRepositoryIndex,
                "a scoreRepository read at offset $scoreRepositoryIndex now comes before the " +
                "worldUnusable guard at offset $guardIndex — scoreRepository is lateinit and unset " +
                "in exactly the case this guard exists for"
            )
    }
}
