package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where the helper binary is looked for, and on which platforms any of this applies. */
class MacPadHelperTest
{
    @Test
    fun `the bundle is searched before the dev build output`()
    {
        val paths = MacPadHelper.candidatePaths(
            javaHome = "/A/One More Breath.app/Contents/runtime/Contents/Home",
            workingDir = "/work",
            override = null
        )
        assertEquals(2, paths.size)
        assertTrue(paths[0].endsWith("/MacOS/macpadbridge"), "the packaged bundle wins")
        assertEquals("/work/build/macpad/macpadbridge", paths[1])
    }

    /**
     * `java.home` inside a jpackage app-image is `<app>.app/Contents/runtime/Contents/Home`, and
     * `buildMacRelease` puts the helper in `<app>.app/Contents/MacOS`. If that relative hop is
     * ever wrong the bundle silently falls back to GLFW and the pad is dead in the shipped build
     * while still working under `./gradlew run` - which is the worst way to find out.
     */
    @Test
    fun `the bundle candidate resolves to Contents MacOS beside the runtime`()
    {
        val paths = MacPadHelper.candidatePaths(
            javaHome = "/A/Game.app/Contents/runtime/Contents/Home",
            workingDir = "/work",
            override = null
        )
        assertEquals("/A/Game.app/Contents/MacOS/macpadbridge", java.io.File(paths[0]).normalize().path)
    }

    @Test
    fun `an override replaces the search entirely`()
    {
        val paths = MacPadHelper.candidatePaths("/jh", "/work", "/tmp/mine")
        assertEquals(listOf("/tmp/mine"), paths)
    }

    @Test
    fun `a blank override is ignored rather than searched for`()
    {
        assertEquals(2, MacPadHelper.candidatePaths("/jh", "/work", "  ").size)
    }

    @Test
    fun `only macOS uses this path`()
    {
        assertTrue(MacPadHelper.isMacOs("Mac OS X"))
        assertTrue(MacPadHelper.isMacOs("Darwin"))
        assertFalse(MacPadHelper.isMacOs("Windows 11"))
        assertFalse(MacPadHelper.isMacOs("Linux"))
    }
}
