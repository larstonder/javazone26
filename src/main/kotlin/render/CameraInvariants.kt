package render

import kotlin.math.abs

/**
 * The three things that must be true of the world camera on a REAL framebuffer, expressed as
 * pure arithmetic over numbers the engine hands back.
 *
 * WHY THIS EXISTS. The shipped world-offset-from-HUD bug (closed in `6ea1f53`) was invisible to
 * the entire test suite and to every screenshot taken at the size the window booted at. Its
 * mechanism: a `no.njoh.pulseengine.modules.scene.entities.Camera` entity rewrote the shared
 * `engine.gfx.mainCamera` every fixed tick with
 *     scale = min(mainSurface.config.width / viewPortWidth, mainSurface.config.height / viewPortHeight)
 * (Camera.kt:93), where `viewPortWidth/Height` were frozen at the window size seen during
 * `onCreate` while `config` tracks the CURRENT framebuffer (SurfaceImpl.init:46-47). The instant
 * the framebuffer changed size the world surface was scaled about the screen's top-left corner
 * and the HUD surface, which has its own camera, was not. Measured: booting at 1200x900 and
 * toggling to 3440x1440 put the diver's world square at 0.63 of screen width against its own
 * HUD-anchored air ring at 0.40.
 *
 * Nothing pure could have caught that, because the disagreement is a property of two mutable
 * engine-owned objects and only becomes visible once two surfaces have been rasterised.
 * [MainCameraOwnershipTest] guards the PRECONDITION (nobody writes that camera but us). This
 * object guards the CONSEQUENCE, on the actual framebuffer, at runtime: it is fed
 * `mainCamera.topLeftWorldPosition` / `bottomRightWorldPosition` — which the engine recomputes
 * every frame in `GraphicsImpl.initFrame` (:112) from the very matrix the frame will be drawn
 * with — and says whether the world they describe is the world we asked for.
 *
 * Rule 2 is the one that matters. It is a statement about GAMEPLAY, not presentation: how far
 * ahead you can see is how far ahead you can plan, so exactly [Framing.VISIBLE_DEPTH_METRES]
 * of water must be on screen whatever the display is. It would have fired on the very first
 * frame after that fullscreen toggle.
 *
 * Rule 3 earns its place for a different reason, and it is worth stating because it is not
 * obvious: it is the ONLY automated check anywhere in this project that can catch a
 * pixels-per-metre derived from the surface WIDTH rather than its height. The dissolved
 * `ViewportTest` had nine green cases and that exact substitution survived all of them (see the
 * plan's §3.1) — which is the same aspect-blindness class as the shipped bug. A width-derived
 * scale leaves the visible world rect with a square aspect on every display, so rule 3 reddens
 * and nothing else does.
 *
 * RULES 2 AND 3 ONLY BECAME MEANINGFUL WHEN THE CAMERA WAS FLIPPED TO METRES. They were gated
 * off behind a `worldRectIsInMetres` flag while `mainCamera` was still the identity the engine
 * constructs it with, because the "world rect" it reported back was then the PIXEL rect — 1800
 * "metres" of visible depth on a Retina panel, which was correct behaviour and must not be
 * warned about every second. The flag and its last caller went with the flip.
 *
 * NO ENGINE IMPORTS, deliberately — same pattern as [RunLifecycle] and [DepthBlend]. The rules
 * are testable without a GL context; feeding them the right eight numbers is [EnPustTil]'s job
 * and is verified by looking at a running game.
 *
 * NOT free of allocation, and that is fine: it returns a list, and the call site runs it at most
 * once per second behind `EPT_DEV`. It must never be called from the per-frame draw path.
 */
object CameraInvariants
{
    /**
     * Rule 2's slack, in metres. Half a metre out of 60 is under 1% of the frame, i.e. below
     * anything a player could perceive, while still being far tighter than any real fault: the
     * failure mode this catches is a whole-number scale factor (1.6x in the measured
     * reproduction), not a rounding error.
     */
    const val VISIBLE_DEPTH_TOLERANCE_METRES = 0.5f

    /**
     * Rule 3's slack, as a fraction of the screen's own aspect.
     *
     * The plan wrote this as "±1%", but a threshold of exactly 1% cannot admit a 1% error, and
     * the two cases pinned in [CameraInvariantsTest] are "1% passes, 3% fails". 2% is the
     * midpoint. The distortion this is looking for is not subtle — a width-for-height
     * substitution makes the visible world rect square, i.e. off by the display's whole aspect
     * ratio (33% on a 4:3 panel, 139% on a 21:9 one).
     */
    const val ASPECT_TOLERANCE_FRACTION = 0.02f

    /**
     * Returns one string per violated rule, empty when all is well.
     *
     * [worldTop]/[worldBottom]/[worldLeft]/[worldRight] are the visible world rect, i.e.
     * `mainCamera.topLeftWorldPosition` and `bottomRightWorldPosition`, IN METRES — which is
     * what they are now that [CameraRig] owns that camera. See the class doc for the flag that
     * used to gate rules 2 and 3 while they were not yet true.
     */
    fun violations(
        windowWidth: Int,
        windowHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        worldTop: Float,
        worldBottom: Float,
        worldLeft: Float,
        worldRight: Float
    ): List<String>
    {
        val found = ArrayList<String>(3)

        // RULE 1 — the two size sources must never diverge.
        //
        // `mainSurface.config` is what the surface's own projection was built from
        // (SurfaceImpl.init:46-47, re-run from GraphicsImpl.onWindowChanged:92); `window` is
        // what GLFW reports. They are the same number today and the engine keeps them so, but
        // "they are the same number" is precisely the assumption the shipped bug's frozen
        // viewport violated, one size source against another. If these ever disagree, every
        // other rule here is measuring the wrong surface and the world is already wrong.
        if (surfaceWidth != windowWidth || surfaceHeight != windowHeight)
        {
            found += "rule 1: main surface is ${surfaceWidth}x$surfaceHeight but the window is " +
                     "${windowWidth}x$windowHeight — the surface's projection and the framebuffer disagree"
        }

        val visibleDepth = worldBottom - worldTop
        val visibleWidth = worldRight - worldLeft

        // A camera that has not been applied yet, or a surface caught mid-resize at zero size,
        // reports a degenerate rect. Bail out BEFORE rule 3 divides by it: `0f / 0f` is NaN,
        // every comparison against NaN is false, and rule 3 would silently report nothing at
        // all — a clean-looking frame in exactly the situation worth looking at.
        if (visibleDepth <= 0f || visibleWidth <= 0f || surfaceWidth <= 0 || surfaceHeight <= 0)
        {
            found += "rule 2/3: degenerate visible world rect ($worldLeft, $worldTop)-($worldRight, $worldBottom) " +
                     "on a ${surfaceWidth}x$surfaceHeight surface — camera not applied, or a zero-size framebuffer"
            return found
        }

        // RULE 2 — exactly VISIBLE_DEPTH_METRES of water is visible, whatever the display is.
        if (abs(visibleDepth - Framing.VISIBLE_DEPTH_METRES) > VISIBLE_DEPTH_TOLERANCE_METRES)
        {
            found += "rule 2: $visibleDepth m of water is visible, expected " +
                     "${Framing.VISIBLE_DEPTH_METRES} m — how far ahead the player can see is a gameplay " +
                     "constant, so the world camera's scale is wrong"
        }

        // RULE 3 — the visible world rect has the screen's aspect, i.e. nothing is stretched.
        val worldAspect = visibleWidth / visibleDepth
        val screenAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        if (abs(worldAspect - screenAspect) > screenAspect * ASPECT_TOLERANCE_FRACTION)
        {
            found += "rule 3: the visible world rect's aspect is $worldAspect but the surface's is " +
                     "$screenAspect — the world is being stretched, or pixels-per-metre was derived from " +
                     "the surface width instead of its height"
        }

        return found
    }
}
