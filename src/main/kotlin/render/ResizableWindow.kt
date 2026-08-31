package render

import no.njoh.pulseengine.core.PulseEngineInternal
import no.njoh.pulseengine.core.window.WindowImpl
import org.lwjgl.glfw.GLFW

/**
 * Latest-wins, pure, engine-free queue behind [ResizableWindow]. Split out from that class
 * specifically so the collapsing discipline can be asserted in a headless JVM (see
 * `ResizableWindowTest`) without a GL context — the same pure-logic-extracted-for-testing
 * pattern as `Framing`, `CameraRig` and `RunLifecycle` (CLAUDE.md).
 *
 * WHY A QUEUE AT ALL, RATHER THAN A DIRECT `GLFW.glfwSetWindowSize` CALL FROM THE MENU: game
 * callbacks (`onCreate`, `updateGame`, `onFixedUpdate`, `onRender`) run on the engine's "game"
 * thread — `gameLoopMode` defaults to `MULTITHREADED` and nothing in `application.cfg` or here
 * overrides it — while every GLFW window call must happen on the main/GL thread. `initFrame` is
 * the main-thread hook the engine already runs at the top of every frame
 * (`WindowInternal.initFrame(PulseEngineInternal)`), so a menu selection on the game thread
 * records an INTENT here, and [ResizableWindow.initFrame], running on the correct thread, is
 * what actually performs it.
 *
 * MUST COLLAPSE: a player holding right on the RESOLUTION row fires `SettingChanged` on every
 * edge-triggered frame that produces one — if each call queued independently, [initFrame] would
 * drain one resize per frame and the window would visibly walk through every size on the ladder
 * before landing on the held-to value. `requestSize`/`requestSwapInterval` overwrite their own
 * slot rather than appending, and [takeSize]/[takeSwapInterval] clear what they return, so only
 * the latest request the menu produced before the next `initFrame` survives, and it fires
 * exactly once.
 */
class PendingWindowRequests
{
    private var pendingWidth: Int? = null
    private var pendingHeight: Int? = null
    private var pendingSwapInterval: Int? = null

    /** Overwrites any earlier unfulfilled size request. */
    fun requestSize(width: Int, height: Int)
    {
        pendingWidth = width
        pendingHeight = height
    }

    /** Overwrites any earlier unfulfilled swap-interval request. */
    fun requestSwapInterval(interval: Int)
    {
        pendingSwapInterval = interval
    }

    /** Returns the latest queued size, or null if none is pending, and clears it either way —
     * a taken request is never re-applied on the next frame. */
    fun takeSize(): Pair<Int, Int>?
    {
        val w = pendingWidth ?: return null
        val h = pendingHeight ?: return null
        pendingWidth = null
        pendingHeight = null
        return w to h
    }

    /** Returns the latest queued swap interval, or null if none is pending, and clears it. */
    fun takeSwapInterval(): Int?
    {
        val interval = pendingSwapInterval
        pendingSwapInterval = null
        return interval
    }
}

/**
 * Adds PROGRAMMATIC window resizing to the engine's own [WindowImpl] — there is no
 * `glfwSetWindowSize` call anywhere in `pulse-engine-0.13.0.jar` (confirmed by unzipping and
 * grepping the jar's classes; `WindowImpl.updateScreenMode` recreates the window for a
 * fullscreen/windowed toggle but never resizes an existing windowed one), so the RESOLUTION row
 * on the GRAPHICS page has nothing to call without this class.
 *
 * Dragging the window's own edge already works today — GLFW's `GLFW_RESIZABLE` window hint is
 * set at creation and the framebuffer-size callback is already wired to `gfx.onWindowChanged`,
 * which reallocates every render texture and re-projects every camera (see `CameraRig`'s class
 * doc). This class only adds a second, programmatic way to trigger that same, already-correct
 * path — it does not touch resize HANDLING at all, only resize REQUESTING.
 *
 * `WindowImpl` is a non-final `public class` with a non-final `initFrame`, confirmed against the
 * shipped jar before writing this class:
 * ```
 * javap -p no/njoh/pulseengine/core/window/WindowImpl.class | head -30
 * ```
 * shows `public class ... WindowImpl` (no `final`) and `public void initFrame(...)` (no
 * `final`), and `PulseEngineImpl`'s own constructor is public with every parameter defaulted —
 * so `PulseEngineImpl(window = ResizableWindow())` in `main()` is valid Kotlin.
 */
class ResizableWindow : WindowImpl()
{
    private val pending = PendingWindowRequests()

    fun requestSize(width: Int, height: Int) = pending.requestSize(width, height)
    fun requestSwapInterval(interval: Int) = pending.requestSwapInterval(interval)

    /**
     * Runs `super.initFrame` FIRST, then drains this class's own pending-request queue — see
     * the inline comment on the call below for the full reasoning. (FINDING I7, final review,
     * 2026-08-30: this KDoc used to say the queue is drained BEFORE `super.initFrame`, which
     * is the exact defect `95bd110` fixed — a stale doc presenting a reverted bug as the
     * design, in a repo where the comments ARE the evidence. Corrected here rather than left
     * to disagree with the inline comment two lines below it.)
     */
    override fun initFrame(engineInternal: PulseEngineInternal)
    {
        // SUPER FIRST, THEN OUR QUEUE - and the order is the whole correctness of this class.
        //
        // `WindowImpl.initFrame` drains the ENGINE's own `onInitFrame` list, and that list is
        // where `updateScreenMode` parks its work. That lambda calls `createWindow()`, which
        // (a) produces a NEW GLFW window handle and (b) re-runs `glfwSwapInterval(0)` - the only
        // call to that function anywhere in the jar.
        //
        // So draining ours first, as this did originally, is wrong twice over in the same frame
        // a player toggles fullscreen: the swap interval we set is immediately reset to 0 by the
        // recreation, and the size we set is applied to a handle that is then thrown away. Both
        // failures are silent - no error, no log - and would surface as "vsync randomly stops
        // working" and "the resolution setting sometimes does nothing".
        //
        // Running super first means `windowHandle` below is the CURRENT handle and our swap
        // interval is the last one applied in the frame.
        super.initFrame(engineInternal)

        pending.takeSize()?.let { (w, h) -> GLFW.glfwSetWindowSize(windowHandle, w, h) }
        pending.takeSwapInterval()?.let { GLFW.glfwSwapInterval(it) }
    }
}
