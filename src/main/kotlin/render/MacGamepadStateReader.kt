package render

import java.io.File

/**
 * The [MappedPads.StateReader] that serves [MacPadBridge]'s snapshot, falling back to
 * [GlfwGamepadStateReader] whenever the bridge has nothing to say.
 *
 * ## THE FALLBACK IS THE POINT, NOT AN AFTERTHOUGHT
 *
 * This is the same argument already written into [MappedPads.StateReader.readRaw]: a helper that
 * is missing, unrunnable, killed, or simply slow must leave the game with the input it had
 * BEFORE this class existed, never with a dead pad. So every path that cannot answer delegates
 * rather than returning zeros - a DualSense on the same machine keeps working through GLFW
 * exactly as it did, and so does every pad on Windows, where this reader is never constructed at
 * all.
 *
 * ## SLOT CORRELATION, AND ITS HONEST LIMIT
 *
 * The helper knows `GCController`s; [MappedPads] is keyed by GLFW joystick id. Nothing connects
 * the two identities - GameController exposes no HID id and GLFW exposes no GameController
 * handle - so they are paired BY POSITION: the first pad [MappedPads.refresh] asks about gets
 * slot 0, the second slot 1, and so on. `refresh` walks `engine.input.gamepads`, which
 * `InputImpl` builds by scanning joystick slots 0..15 in order, so this is ascending id order.
 *
 * That is correct for one controller, which is the target (see [MacPadBridge]'s class doc). With
 * two pads of DIFFERENT kinds it can pair them the wrong way round. It is documented rather than
 * defended, because the alternative - matching on names that the two frameworks spell
 * differently - would be a guess wearing a disguise.
 *
 * [MappedPads.StateReader.beginFrame] is what makes the counting possible: it resets [nextSlot]
 * once per refresh, so the count cannot drift as pads come and go.
 */
class MacGamepadStateReader(
    private val bridge: MacPadBridge,
    private val delegate: MappedPads.StateReader
) : MappedPads.StateReader
{
    private var nextSlot = 0

    override fun beginFrame()
    {
        nextSlot = 0
        delegate.beginFrame()
    }

    override fun readMapped(joystickId: Int, buttons: ByteArray, axes: FloatArray): Boolean
    {
        val slot = nextSlot++
        if (bridge.isLive() && bridge.copyInto(slot, buttons, axes)) return true
        return delegate.readMapped(joystickId, buttons, axes)
    }

    override fun readRaw(joystickId: Int, buttons: ByteArray, axes: FloatArray) =
        delegate.readRaw(joystickId, buttons, axes)

    override fun close()
    {
        bridge.close()
        delegate.close()
    }
}

/**
 * Where the `macpadbridge` binary might be, in the order worth trying.
 *
 * Split out as a pure function over plain strings so the search order can be asserted without a
 * filesystem, a bundle or a Mac - the pure-logic-extracted-for-testing convention in CLAUDE.md.
 */
object MacPadHelper
{
    const val BINARY_NAME = "macpadbridge"

    /** Overrides the search entirely. Useful for a technician with a hand-built helper. */
    const val ENV_OVERRIDE = "EPT_MACPAD"

    /**
     * @param javaHome `System.getProperty("java.home")`. Inside a jpackage app-image this is
     *   `<app>.app/Contents/runtime/Contents/Home`, so the bundle's own `Contents/MacOS` - where
     *   `buildMacRelease` puts the helper - is four levels up and across.
     * @param workingDir `user.dir`, which for `./gradlew run` is the project root.
     * @param override the value of [ENV_OVERRIDE], or null.
     */
    fun candidatePaths(javaHome: String, workingDir: String, override: String?): List<String>
    {
        if (!override.isNullOrBlank()) return listOf(override)
        return listOf(
            // The packaged bundle: .../Contents/runtime/Contents/Home -> .../Contents/MacOS
            "$javaHome/../../../MacOS/$BINARY_NAME",
            // ./gradlew run, and anything else launched from the project root.
            "$workingDir/build/macpad/$BINARY_NAME"
        )
    }

    /** The first candidate that exists and is executable, or null. */
    fun locate(
        javaHome: String = System.getProperty("java.home") ?: "",
        workingDir: String = System.getProperty("user.dir") ?: "",
        override: String? = System.getenv(ENV_OVERRIDE)
    ): File?
    {
        for (path in candidatePaths(javaHome, workingDir, override))
        {
            val file = File(path).let { if (it.exists()) it.canonicalFile else it }
            if (file.canExecute()) return file
        }
        return null
    }

    /** True on macOS, where this whole path applies. Windows constructs none of it. */
    fun isMacOs(osName: String = System.getProperty("os.name") ?: ""): Boolean =
        osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true)
}
