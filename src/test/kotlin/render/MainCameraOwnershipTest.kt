package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * WHEN TO NARROW THIS — WHICH IS WHAT ALREADY HAPPENED, TWICE, AND IT IS NOT A DELETION.
 *
 * The original version of this file said the world-coordinate migration would make this guard
 * "obsolete" and that deleting it was part of that change. That was wrong, and wrong in the
 * direction that would have thrown away the only thing standing between us and a second silent
 * writer of the shared camera. The invariant was never "nobody writes `mainCamera`" — it was
 * "EXACTLY ONE place writes `mainCamera`, on purpose". A source scan can state that just as
 * well as it can state "nobody", and it keeps stating it after the migration.
 *
 * So the second case below is an ALLOW-LIST, not a prohibition, and it is exact set equality
 * rather than a substring skip: a third file that mentions the camera fails, and so does
 * silently losing an allow-listed one. Current members and why each is allowed:
 *
 *   - `EnPustTil.kt` — READS ONLY, `topLeftWorldPosition` / `bottomRightWorldPosition`, to feed
 *     [CameraInvariants]. A read cannot cause the bug above, which is a WRITE from a second
 *     place; the read-only clause is enforced below rather than trusted.
 *   - `render/CameraRig.kt` — the ONE legitimate writer. Not yet called by anything; Task 5 of
 *     docs/superpowers/plans/2026-08-06-engine-world-coordinates.md wires it in and narrows this
 *     list again, at which point the read-only clause on `EnPustTil.kt` is the whole guard.
 *
 * The plan asked for CameraRig's entry to be added here in Task 2, before the file existed, so
 * that Task 3 need not touch a test file. It was added in Task 3 instead: exact set equality
 * against a file that does not exist yet means committing Task 2 red, and a red commit is how
 * work gets lost. The cost is one line, and the benefit is that the guard was watched going red
 * on CameraRig.kt specifically before being told to expect it.
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
    fun `only the allow-listed sources touch the shared main camera, and EnPustTil only reads it`()
    {
        val touching = productionSources().filter { (_, code) -> code.contains("mainCamera") }

        assertEquals(
            CAMERA_ALLOW_LIST,
            touching.map { it.first }.toSet(),
            "engine.gfx.mainCamera has exactly one intended writer and no other toucher at all — " +
            "render/Viewport's screen-pixel maths, DiveRenderer, DiveLighting's immediate-mode drawLight " +
            "calls and the HUD's own surface camera all depend on that. A file MISSING from this set is " +
            "as much a failure as an extra one: it means the intended owner stopped owning it. " +
            "See this test's class doc."
        )

        // The allow-list says EnPustTil.kt may READ the camera, so check that it does only that
        // rather than trusting the sentence. Every `mainCamera` mention there must be one of the
        // two world-rect reads CameraInvariants is fed; a write would be indistinguishable from
        // the deleted Camera entity as far as the bug is concerned, and set equality above cannot
        // see it, because the file is allow-listed either way.
        val illegalUses = touching
            .filter { (path, _) -> path.endsWith("EnPustTil.kt") }
            .flatMap { (_, code) -> code.lineSequence().filter { it.contains("mainCamera") } }
            .filterNot { line -> ALLOWED_READS.any { line.contains("mainCamera.$it") } }

        assertTrue(
            illegalUses.isEmpty(),
            "EnPustTil.kt may only READ engine.gfx.mainCamera ($ALLOWED_READS) to feed CameraInvariants. " +
            "Writing it from here would be a second writer of the shared camera, which is the shipped " +
            "bug in this test's class doc. Offending lines: ${illegalUses.map { it.trim() }}"
        )
    }

    private companion object
    {
        /**
         * Every production source permitted to mention `mainCamera` at all, by path. Exact —
         * see the class doc for why this is set equality and not a skip list, and for what
         * each entry is allowed to do with the camera.
         */
        val CAMERA_ALLOW_LIST = setOf(
            "src/main/kotlin/EnPustTil.kt",
            "src/main/kotlin/render/CameraRig.kt"
        )

        /** What EnPustTil.kt is allowed to touch on it: reads of the engine-computed world rect. */
        val ALLOWED_READS = listOf("topLeftWorldPosition", "bottomRightWorldPosition")

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
