import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `createGame`'s "hud" surface creation has to be the FIRST thing that can succeed or fail
 * in that function — see the comment at that `createSurface` call and Critical 1 of the
 * Task 5 review. `engine.gfx.getSurfaceOrDefault("hud")` is a silent `?: mainSurface`
 * fallback (no log, no warning), and `mainSurface`'s camera is a WORLD camera scaled by
 * pixels-per-metre. So if ANYTHING between "hud" being created and the end of `createGame`
 * fails first — `sim`/`scoreRepository` construction, `DiverSprite.load`, `DiveLighting.setup`,
 * every asset load in between — `drawBootFailedScreen`'s screen-pixel coordinates would be
 * silently fed through the wrong camera and land hundreds of world-metres off screen. A
 * human noticing that regression requires remembering to re-run the EPT_FAIL_BOOT capture;
 * this test catches it at build time instead, the same way `EnPustTilDepthPinTest` catches
 * a `DiveSim(` construction that forgot `applyDepthPin()`.
 *
 * The "natural tidying instinct" this guards against: grouping the surface-creation calls
 * together (hud, sky) would put "hud" back after the world/asset setup it currently
 * precedes, since that is where `sky` still lives and where "hud" itself used to live
 * before the Task 5 review's fix round.
 */
class CreateGameOrderingTest
{
    private val body: String
    init
    {
        val source = File("src/main/kotlin/EnPustTil.kt").readText()
        val afterSignature = source.substringAfter("private fun createGame()")
        // Bounded to just this function: the next declaration in the file is onFixedUpdate.
        body = afterSignature.substring(0, afterSignature.indexOf("\n    override fun onFixedUpdate"))
    }

    // Comments explain WHY the ordering matters using the very identifiers being searched
    // for ("...this call no longer sits next to DiveLighting.setup()..."), so they must be
    // stripped before searching for the real statements — the same trap
    // RenderGameBootGuardTest's class doc calls out for renderGame's comments.
    private fun stripComments(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `the hud surface is created before sim, DiverSprite, or DiveLighting can fail`()
    {
        val code = stripComments(body)
        val hudIndex = code.indexOf("name = \"hud\"")
        val simIndex = code.indexOf("sim = DiveSim(")
        val diverSpriteIndex = code.indexOf("DiverSprite.load(")
        val diveLightingIndex = code.indexOf("DiveLighting.setup(")

        assertTrue(hudIndex >= 0, "createGame no longer creates a surface named \"hud\" — re-read this test")
        assertTrue(simIndex >= 0, "createGame no longer constructs sim — re-read this test")
        assertTrue(diverSpriteIndex >= 0, "createGame no longer loads DiverSprite — re-read this test")
        assertTrue(diveLightingIndex >= 0, "createGame no longer calls DiveLighting.setup — re-read this test")

        val firstRiskyStatement = minOf(simIndex, diverSpriteIndex, diveLightingIndex)
        assertTrue(
            hudIndex < firstRiskyStatement,
            "the \"hud\" surface is created at offset $hudIndex, AFTER a statement at offset " +
            "$firstRiskyStatement that can throw (sim/DiverSprite/DiveLighting). " +
            "engine.gfx.getSurfaceOrDefault(\"hud\") silently falls back to mainSurface — a WORLD " +
            "camera scaled by pixels-per-metre — whenever \"hud\" was never created, so " +
            "drawBootFailedScreen's screen-pixel coordinates would land hundreds of world-metres " +
            "off screen for every failure in this window. \"hud\" must be created before anything " +
            "in createGame that can fail, not just before CameraRig.snap."
        )
    }
}
