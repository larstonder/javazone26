package render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire format between `tools/macpad/MacPadBridge.swift` and [MacPadBridge].
 *
 * Everything here runs on plain arrays with no process, no thread and no macOS - the
 * pure-logic-extracted-for-testing convention in CLAUDE.md. The one thing a unit test genuinely
 * cannot hold is the helper process itself, so the format is asserted from BOTH sides: these
 * cases encode a frame by hand and decode it, and the source scan at the bottom checks the Swift
 * that produces it still agrees about the three numbers that decide the layout.
 */
class MacPadBridgeTest
{
    private fun encode(slot: Int, buttons: ByteArray, axes: FloatArray, count: Int = 1): ByteArray
    {
        val frame = ByteArray(MacPadBridge.FRAME_BYTES)
        frame[0] = MacPadBridge.SYNC
        frame[1] = count.toByte()
        val base = 2 + slot * MacPadBridge.SLOT_BYTES
        for (i in buttons.indices) frame[base + i] = buttons[i]
        val axisBase = base + MacPadBridge.BUTTON_COUNT
        for (i in axes.indices)
        {
            val bits = axes[i].toRawBits()
            frame[axisBase + i * 4 + 0] = (bits and 0xFF).toByte()
            frame[axisBase + i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            frame[axisBase + i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            frame[axisBase + i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return frame
    }

    @Test
    fun `a frame is the sync marker, the count and one slot per pad`()
    {
        assertEquals(39, MacPadBridge.SLOT_BYTES, "15 button bytes plus 6 little-endian floats")
        assertEquals(2 + 4 * 39, MacPadBridge.FRAME_BYTES)
        assertEquals(158, MacPadBridge.FRAME_BYTES)
    }

    @Test
    fun `decodeSlot round-trips every button and every axis`()
    {
        val buttons = ByteArray(MacPadBridge.BUTTON_COUNT) { if (it % 2 == 0) 1 else 0 }
        val axes = floatArrayOf(-1f, 1f, 0.5f, -0.25f, -1f, 0.75f)

        val outButtons = ByteArray(MacPadBridge.BUTTON_COUNT)
        val outAxes = FloatArray(MacPadBridge.AXIS_COUNT)
        MacPadBridge.decodeSlot(encode(0, buttons, axes), 0, outButtons, outAxes)

        assertTrue(buttons.contentEquals(outButtons))
        assertTrue(axes.contentEquals(outAxes))
    }

    @Test
    fun `each slot decodes independently, so one pad cannot read another pad's state`()
    {
        val buttons = ByteArray(MacPadBridge.BUTTON_COUNT).also { it[7] = 1 }
        val axes = FloatArray(MacPadBridge.AXIS_COUNT).also { it[0] = 0.5f }
        val frame = encode(slot = 2, buttons = buttons, axes = axes, count = 3)

        val outButtons = ByteArray(MacPadBridge.BUTTON_COUNT)
        val outAxes = FloatArray(MacPadBridge.AXIS_COUNT)

        MacPadBridge.decodeSlot(frame, 2, outButtons, outAxes)
        assertEquals(1, outButtons[7].toInt(), "slot 2 carries the press")
        assertEquals(0.5f, outAxes[0])

        MacPadBridge.decodeSlot(frame, 0, outButtons, outAxes)
        assertEquals(0, outButtons[7].toInt(), "slot 0 is untouched by slot 2")
        assertEquals(0f, outAxes[0])
    }

    /**
     * The axis codes this format is indexed by are the engine's, and the SIGNS are the thing a
     * still frame would never catch: the Swift negates GameController's Y (it reports +1 as
     * stick-UP, GLFW and this game's +y-down world report -1) and rescales the triggers from
     * 0..1 to -1..+1. Getting either backwards inverts the dive or pins a trigger on.
     */
    @Test
    fun `the swift helper negates stick Y and rescales the triggers`()
    {
        val swift = File("tools/macpad/MacPadBridge.swift").readText()
        assertTrue(
            swift.contains("putFloat(-pad.leftThumbstick.yAxis.value"),
            "left stick Y must be negated - GameController is +y up, GLFW and this game are +y down"
        )
        assertTrue(
            swift.contains("putFloat(pad.leftTrigger.value * 2 - 1"),
            "triggers must be rescaled from GameController's 0..1 to GLFW's -1..+1"
        )
    }

    /**
     * The Nintendo face-button swap, which is the one piece of this bridge that silently
     * mis-binds gameplay if it is wrong or if it is applied too widely. Measured: pressing ONLY
     * the physical B (bottom) button reported `buttons=[B]` through GCExtendedGamepad, i.e.
     * Apple names a Nintendo pad by its PRINTED LABEL while SDL names by POSITION.
     */
    @Test
    fun `the face-button swap is gated on the controller being a Nintendo pad`()
    {
        val swift = File("tools/macpad/MacPadBridge.swift").readText()
        assertTrue(swift.contains("let nintendo ="), "the swap must be per-controller, not global")
        assertTrue(
            swift.contains("put(0, (nintendo ? pad.buttonB : pad.buttonA).isPressed)"),
            "SDL A is the BOTTOM button: physical B on a Nintendo pad, buttonA on everything else"
        )
        assertTrue(
            swift.contains("put(1, (nintendo ? pad.buttonA : pad.buttonB).isPressed)"),
            "SDL B is the RIGHT button: physical A on a Nintendo pad"
        )
    }

    /** The three numbers that decide the layout must agree across the language boundary. */
    @Test
    fun `the swift helper and the kotlin reader agree on the frame layout`()
    {
        val swift = File("tools/macpad/MacPadBridge.swift").readText()
        assertTrue(swift.contains("let MAX_PADS = ${MacPadBridge.MAX_PADS}"))
        assertTrue(swift.contains("let BUTTON_COUNT = ${MacPadBridge.BUTTON_COUNT}"))
        assertTrue(swift.contains("let AXIS_COUNT = ${MacPadBridge.AXIS_COUNT}"))
        assertTrue(swift.contains("frame[0] = 0xA5"), "sync marker must match MacPadBridge.SYNC")
    }

    @Test
    fun `a bridge that has never received a frame is not live and reports no controllers`()
    {
        val bridge = MacPadBridge(File("/nonexistent/macpadbridge"))
        assertFalse(bridge.isLive())
        assertEquals(0, bridge.controllerCount())
        assertFalse(bridge.copyInto(0, ByteArray(15), FloatArray(6)))
    }

    @Test
    fun `starting a missing helper fails rather than throwing, so boot degrades to GLFW`()
    {
        assertFalse(MacPadBridge(File("/nonexistent/macpadbridge")).start())
    }
}
