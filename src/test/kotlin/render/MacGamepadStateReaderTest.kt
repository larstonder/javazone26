package render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MacGamepadStateReader]'s two contracts: it serves the bridge when the bridge has something,
 * and it DELEGATES rather than returning zeros whenever it does not. The second is the one worth
 * asserting - a reader that quietly answered "nothing pressed" for a missing helper would leave
 * a working DualSense dead on the same machine.
 */
class MacGamepadStateReaderTest
{
    private class RecordingReader : MappedPads.StateReader
    {
        var mappedCalls = 0
        var rawCalls = 0
        var frames = 0
        var closed = false
        var answer = true

        override fun beginFrame() { frames++ }
        override fun readMapped(joystickId: Int, buttons: ByteArray, axes: FloatArray): Boolean
        {
            mappedCalls++
            buttons[0] = 9
            return answer
        }
        override fun readRaw(joystickId: Int, buttons: ByteArray, axes: FloatArray) { rawCalls++ }
        override fun close() { closed = true }
    }

    /** A bridge that is never live - the missing-helper and dead-helper case. */
    private fun deadBridge() = MacPadBridge(java.io.File("/nonexistent/macpadbridge"))

    @Test
    fun `a dead bridge delegates every mapped read to GLFW`()
    {
        val delegate = RecordingReader()
        val reader = MacGamepadStateReader(deadBridge(), delegate)

        val buttons = ByteArray(15)
        assertTrue(reader.readMapped(0, buttons, FloatArray(6)))
        assertEquals(1, delegate.mappedCalls)
        assertEquals(9, buttons[0].toInt(), "the delegate's answer is passed through untouched")
    }

    @Test
    fun `a delegate that also fails is reported as failure, so MappedPads takes its raw fallback`()
    {
        val delegate = RecordingReader().apply { answer = false }
        val reader = MacGamepadStateReader(deadBridge(), delegate)
        assertFalse(reader.readMapped(0, ByteArray(15), FloatArray(6)))
    }

    @Test
    fun `raw reads always go to GLFW - the bridge has no raw form`()
    {
        val delegate = RecordingReader()
        MacGamepadStateReader(deadBridge(), delegate).readRaw(0, ByteArray(15), FloatArray(6))
        assertEquals(1, delegate.rawCalls)
    }

    @Test
    fun `beginFrame reaches the delegate as well, so a wrapped reader is not starved of it`()
    {
        val delegate = RecordingReader()
        MacGamepadStateReader(deadBridge(), delegate).beginFrame()
        assertEquals(1, delegate.frames)
    }

    @Test
    fun `closing closes the delegate too`()
    {
        val delegate = RecordingReader()
        MacGamepadStateReader(deadBridge(), delegate).close()
        assertTrue(delegate.closed)
    }

    /**
     * Slots are paired to joystick ids BY POSITION within a frame, so the counter must reset on
     * every [MappedPads.StateReader.beginFrame]. Without the reset it would climb past
     * [MacPadBridge.MAX_PADS] within a few seconds and the pad would go dead - the exact failure
     * this test exists to catch, since a live bridge is what makes the counter observable.
     */
    @Test
    fun `the slot counter restarts each frame`()
    {
        val delegate = RecordingReader()
        val reader = MacGamepadStateReader(deadBridge(), delegate)

        repeat(3)
        {
            reader.beginFrame()
            reader.readMapped(0, ByteArray(15), FloatArray(6))
            reader.readMapped(1, ByteArray(15), FloatArray(6))
        }
        assertEquals(3, delegate.frames)
        assertEquals(6, delegate.mappedCalls)
    }
}
