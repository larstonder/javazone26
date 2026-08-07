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
 * WHY A SOURCE SCAN. The invariant is a whole-process property of a mutable object owned by the
 * engine, so there is nothing pure to assert: `DiveLighting.setup` needs a live `PulseEngine`,
 * and the disagreement only becomes visible once two surfaces with two cameras have both been
 * rasterised. The real verification is a framebuffer capture at several window sizes and aspect
 * ratios, and it is not automatable here. What IS automatable is the ownership: exactly one file
 * writes that camera, on purpose. Reintroducing either half of the fault then fails the build
 * instead of the booth.
 *
 * WHAT THIS GUARD SAYS NOW, after the world-coordinate migration:
 *
 *   > No production source outside `render/CameraRig.kt` WRITES `engine.gfx.mainCamera`.
 *   > Reads are allowed and named.
 *
 * It was NOT deleted by that migration, though an early version of this file predicted it would
 * be. That prediction was wrong in the direction that throws away the only thing standing
 * between us and a second silent writer of the shared camera. The invariant was never "nobody
 * writes `mainCamera`" — it was "EXACTLY ONE place writes `mainCamera`". A source scan states
 * that just as well as it states "nobody", and a second file easing, resetting or zooming that
 * camera is precisely the fault `6ea1f53` fixed.
 *
 * So the second case below is an ALLOW-LIST, not a prohibition, and it is exact set equality
 * rather than a substring skip: a third file that mentions the camera fails, and so does
 * silently losing an allow-listed one. Current members and why each is allowed:
 *
 *   - `render/CameraRig.kt` — the ONE legitimate writer. Called from `EnPustTil.onFixedUpdate`,
 *     and from `onCreate`/`justStarted` right after `DiveCamera.snapTo`.
 *   - `EnPustTil.kt` — READS ONLY: `topLeftWorldPosition` / `bottomRightWorldPosition` to feed
 *     [CameraInvariants], and `worldPosToScreenPos` for the HUD's diver anchor. A read cannot
 *     cause the bug above, which is a WRITE from a second place; the read-only clause is
 *     enforced below rather than trusted.
 *
 * WHAT IS NO LONGER GUARANTEED, STATED PLAINLY BECAUSE NOTHING REPLACES IT. Since `c47a4b0` the
 * process has a SECOND legitimate writer under `EPT_EDITOR=1`: `SceneEditor`'s
 * `Camera2DController` (`SceneEditor.kt:347`) drives the same object. It lives in engine code, so
 * no scan of `src/main/kotlin` can see it and this file stays green while the invariant is, at
 * runtime, false.
 *
 *   - AT THE BOOTH IT DOES NOT EXIST. `EPT_EDITOR` is unset, `SceneEditor` is never constructed
 *     (see `EnPustTil.onCreate`), and `CameraRig` is the only writer in the process. The shipped
 *     configuration is fully guarded by what is below.
 *   - IN EDITOR MODE the invariant becomes "exactly one writer AT A TIME", and what enforces it
 *     is not a test — it is Task 10's gate, which skips `CameraRig.apply` while the editor
 *     service is running. Its failure mode is loud and immediate (you cannot pan the editor
 *     viewport), not silent, which is why no test is bought for it.
 *   - NO TEST ASSERTS THAT GATE. It is verified by hand, once. Do not read this file as covering
 *     more than the two bullets above it.
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
    fun `only the allow-listed sources reach the shared main camera by name`()
    {
        val touching = productionSources().filter { (_, code) -> code.contains("mainCamera") }

        assertEquals(
            CAMERA_ALLOW_LIST,
            touching.map { it.first }.toSet(),
            "engine.gfx.mainCamera has exactly one intended writer (render/CameraRig.kt) and no other " +
            "toucher at all — DiveRenderer's world rect, DiveLighting's immediate-mode drawLight calls " +
            "and the HUD's diver anchor all read one camera and would be silently moved apart by a " +
            "second writer. A file MISSING from this set is as much a failure as an extra one: it means " +
            "the intended owner stopped owning it. See this test's class doc."
        )
    }

    /**
     * THE CLAUSE THAT SURVIVED THE MIGRATION, IN A STRONGER FORM.
     *
     * Until the flip, "EnPustTil may only READ the camera" was checked line by line: every
     * `mainCamera` mention in that file had to be followed by one of two named field reads. That
     * proxy stopped working the moment the camera became something legitimately PASSED AROUND —
     * `DiveRenderer.render(surface, sim, engine.gfx.mainCamera)` mentions the camera and reads no
     * named field, and `val cam = engine.gfx.mainCamera` would have satisfied a line check while
     * handing an alias to anything.
     *
     * So the proxy is replaced by the thing it was standing in for: NOBODY OUTSIDE
     * `render/CameraRig.kt` MUTATES A CAMERA'S TRANSFORM. That is strictly stronger. It follows
     * aliases (the write itself is what is scanned for, not the name it is reached through), and
     * it covers files that never say `mainCamera` at all — which now includes `DiveRenderer`,
     * holding the very object as a parameter.
     *
     * WHAT IT DOES NOT COVER, so nobody assumes more: only the four transform fields, and only
     * the `.set(...)` and `.x =` idioms. `nearPlane`/`farPlane` are settable and unscanned; they
     * cannot produce the world-offset-from-HUD bug, which is a scale/translation fault. A camera
     * written through reflection, or a fifth field added by an engine upgrade, would also pass.
     * And the editor's own `Camera2DController` is engine code and invisible to any source scan
     * — see the class doc.
     */
    @Test
    fun `no production source outside CameraRig writes a camera transform`()
    {
        val offenders = productionSources()
            .filterNot { (path, _) -> path == CAMERA_WRITER }
            .flatMap { (path, code) ->
                code.lineSequence()
                    .filter { TRANSFORM_WRITE.containsMatchIn(it) }
                    .map { "$path: ${it.trim()}" }
            }

        assertTrue(
            offenders.isEmpty(),
            "Only $CAMERA_WRITER may write scale/position/origin/rotation on a camera. A second writer " +
            "of engine.gfx.mainCamera is the shipped world-offset-from-HUD bug (see this test's class " +
            "doc), and since the camera is now passed to DiveRenderer as a parameter, a write does not " +
            "have to mention `mainCamera` to be one. Found: $offenders"
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

        /** The one file allowed to write a camera transform. */
        const val CAMERA_WRITER = "src/main/kotlin/render/CameraRig.kt"

        /**
         * A write to one of `Camera`'s four transform fields (Camera.kt:20-23), which are JOML
         * `Vector3f`s: either `field.set(...)`, as CameraRig writes them, or a component
         * assignment like `field.x = `. The `[^=]` tail keeps `==` from matching.
         *
         * Deliberately name-based rather than type-aware — there is no lexer here, same as
         * [stripComments]. Measured against this codebase: the four names appear outside
         * CameraRig only inside comments (DiveLighting's transcription of the deleted entity's
         * arithmetic), which [productionSources] has already stripped. A false positive here is
         * a failing test, not a shipped bug.
         */
        val TRANSFORM_WRITE = Regex("\\b(scale|position|origin|rotation)\\s*(\\.\\s*set\\s*\\(|\\.\\s*[xyz]\\s*=[^=])")

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
