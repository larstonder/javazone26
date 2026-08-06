package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A SOURCE-SCANNING GUARD, not a behaviour test — and deliberately so, because the bug it
 * exists to stop cannot be caught any other way short of a real framebuffer.
 *
 * THE BUG. `DiveLighting.setup` used to add a `no.njoh.pulseengine.modules.scene.entities
 * .Camera` entity, on the (false) belief that `GlobalIlluminationSystem` needed one. That
 * entity's `onFixedUpdate` rewrites the SHARED `engine.gfx.mainCamera` every fixed tick with
 * `scale = min(mainSurface.config.width / viewPortWidth, mainSurface.config.height /
 * viewPortHeight)` (Camera.kt:93), where `viewPortWidth/Height` were frozen at the window
 * size seen during `onCreate` while `config` tracks the CURRENT framebuffer
 * (SurfaceImpl.init:46-47). The moment the framebuffer changed size — the LEFT_ALT+ENTER
 * fullscreen toggle bound in `init.pes`, a monitor change, a content-scale change — the world
 * surface was scaled about the screen's top-left corner and the HUD surface, which has its own
 * camera, was not. Measured: booting at 1200x900 and toggling to 3440x1440 put the diver's
 * world square at 0.63 of screen width while its own HUD-anchored air ring stayed at 0.40.
 *
 * WHY A SOURCE SCAN. `render/Viewport`'s entire screen-pixel coordinate model is correct if
 * and only if `engine.gfx.mainCamera` stays at the identity the engine constructs it with
 * (`DefaultCamera.createOrthographic`, position 0 / origin 0 / scale 1 — GraphicsImpl.init:47).
 * That is a whole-process invariant about a mutable object owned by the engine, so there is
 * nothing pure to assert: `DiveLighting.setup` needs a live `PulseEngine`, and the disagreement
 * only becomes visible once two surfaces with two cameras have both been rasterised. The real
 * verification is a framebuffer capture at several window sizes, and it is not automatable
 * here. What IS automatable is the precondition: no production source touches that camera at
 * all. Reintroducing either half of the fault then fails the build instead of the booth.
 *
 * WHEN TO DELETE THIS. `docs/superpowers/plans/2026-08-06-engine-world-coordinates.md` migrates
 * the world into the engine's own coordinate model, at which point writing `mainCamera` from a
 * `CameraRig` becomes the correct design and this guard is obsolete. Deleting it is then part
 * of that change — which is exactly the point: it makes touching the shared camera a decision
 * someone has to make on purpose.
 */
class MainCameraOwnershipTest
{
    @Test
    fun `no production source constructs an engine scene Camera entity`()
    {
        val offenders = productionSources().filter { (_, code) ->
            code.contains("scene.entities.Camera") || Regex("\\bCamera\\s*\\(\\s*\\)").containsMatchIn(code)
        }

        assertTrue(
            offenders.isEmpty(),
            "A no.njoh.pulseengine.modules.scene.entities.Camera entity drives engine.gfx.mainCamera " +
            "every fixed tick from a viewport frozen at onCreate, which scales the world surface away " +
            "from the HUD surface the instant the framebuffer changes size. See this test's class doc. " +
            "Found in: ${offenders.map { it.first }}"
        )
    }

    @Test
    fun `no production source touches the shared main camera`()
    {
        val offenders = productionSources().filter { (_, code) -> code.contains("mainCamera") }

        assertTrue(
            offenders.isEmpty(),
            "engine.gfx.mainCamera must stay at the identity transform the engine constructs it with — " +
            "render/Viewport's screen-pixel maths, DiveRenderer, DiveLighting's immediate-mode drawLight " +
            "calls and the HUD's own surface camera all depend on it. See this test's class doc. " +
            "Found in: ${offenders.map { it.first }}"
        )
    }

    private companion object
    {
        /**
         * Every production Kotlin source, paired with its code WITH COMMENTS STRIPPED — the
         * whole point of the fix was to leave a long explanation of the mechanism behind, and
         * that explanation necessarily names both the entity class and the shared camera.
         * Stripping is line-based (drop any line whose trimmed form opens a line comment, opens
         * a block comment, or continues a KDoc block with a star; then cut a trailing line
         * comment) rather than a real lexer, which is exact for this codebase's Allman + KDoc
         * style. It would mis-handle a line-comment marker inside a string literal; there is
         * none, and a false positive here is a failing test, not a shipped bug.
         */
        fun productionSources(): List<Pair<String, String>> =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.path to stripComments(it.readText()) }
                .toList()
                .also { assertTrue(it.size > 5, "found only ${it.size} production sources — wrong working directory?") }

        fun stripComments(source: String): String = source
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") }
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }
}
