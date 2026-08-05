package score

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitialsEntryTest
{
    @Test
    fun `starts at AAA, slot 0, not complete`()
    {
        val e = InitialsEntry()
        assertEquals("AAA", e.initialsString())
        assertEquals(0, e.slot)
        assertFalse(e.complete)
    }

    @Test
    fun `cycling up moves the current slot's letter forward`()
    {
        val e = InitialsEntry()
        e.update(cycleUp = true, cycleDown = false, confirmPressed = false)
        assertEquals("BAA", e.initialsString())
    }

    @Test
    fun `cycling down moves the current slot's letter backward`()
    {
        val e = InitialsEntry()
        e.update(cycleUp = true, cycleDown = false, confirmPressed = false)  // A -> B
        e.update(cycleUp = false, cycleDown = false, confirmPressed = false) // release
        e.update(cycleUp = false, cycleDown = true, confirmPressed = false)  // B -> A
        assertEquals("AAA", e.initialsString())
    }

    @Test
    fun `cycling up from Z wraps to A`()
    {
        val e = InitialsEntry()
        repeat(26) {
            e.update(cycleUp = true, cycleDown = false, confirmPressed = false)
            e.update(cycleUp = false, cycleDown = false, confirmPressed = false) // release each press
        }
        assertEquals("AAA", e.initialsString(), "26 presses from A must land back on A")
    }

    @Test
    fun `cycling down from A wraps to Z`()
    {
        val e = InitialsEntry()
        e.update(cycleUp = false, cycleDown = true, confirmPressed = false)
        assertEquals("ZAA", e.initialsString())
    }

    @Test
    fun `holding the stick up does not repeatedly cycle - edge triggered exactly once`()
    {
        val e = InitialsEntry()
        // Held across many frames without ever releasing.
        repeat(60) { e.update(cycleUp = true, cycleDown = false, confirmPressed = false) }
        assertEquals("BAA", e.initialsString(), "a held stick must cycle exactly once, not once per frame")
    }

    @Test
    fun `confirm advances to the next slot`()
    {
        val e = InitialsEntry()
        e.update(cycleUp = false, cycleDown = false, confirmPressed = true)
        assertEquals(1, e.slot)
        assertFalse(e.complete)
    }

    @Test
    fun `holding the confirm button does not repeatedly advance`()
    {
        val e = InitialsEntry()
        repeat(60) { e.update(cycleUp = false, cycleDown = false, confirmPressed = true) }
        assertEquals(1, e.slot, "a held confirm button must advance exactly once")
    }

    @Test
    fun `confirming all three slots completes entry with the expected initials`()
    {
        val e = InitialsEntry()
        // Slot 0: cycle to 'C' — two edges, A -> B -> C.
        e.update(cycleUp = true, cycleDown = false, confirmPressed = false)
        e.update(cycleUp = false, cycleDown = false, confirmPressed = false)
        e.update(cycleUp = true, cycleDown = false, confirmPressed = false)
        e.update(cycleUp = false, cycleDown = false, confirmPressed = false)
        e.update(cycleUp = false, cycleDown = false, confirmPressed = true) // confirm slot 0 -> slot 1
        assertFalse(e.complete)

        e.update(cycleUp = false, cycleDown = false, confirmPressed = false) // release
        e.update(cycleUp = false, cycleDown = false, confirmPressed = true)  // confirm slot 1 -> slot 2
        assertFalse(e.complete)

        e.update(cycleUp = false, cycleDown = false, confirmPressed = false) // release
        e.update(cycleUp = false, cycleDown = false, confirmPressed = true)  // confirm slot 2 -> done
        assertTrue(e.complete)
        assertEquals("CAA", e.initialsString())
    }

    @Test
    fun `update is a no-op once complete`()
    {
        val e = InitialsEntry()
        repeat(3) {
            e.update(cycleUp = false, cycleDown = false, confirmPressed = true)
            e.update(cycleUp = false, cycleDown = false, confirmPressed = false)
        }
        assertTrue(e.complete)
        val before = e.initialsString()
        e.update(cycleUp = true, cycleDown = false, confirmPressed = true)
        assertEquals(before, e.initialsString(), "letters must not change after completion")
        assertEquals(3, e.slot)
    }

    @Test
    fun `reset returns to the initial state`()
    {
        val e = InitialsEntry()
        e.update(cycleUp = true, cycleDown = false, confirmPressed = true)
        e.reset()
        assertEquals("AAA", e.initialsString())
        assertEquals(0, e.slot)
        assertFalse(e.complete)
    }

    @Test
    fun `reset forgets prior edge state, so a fresh entry after reset starts clean`()
    {
        val e = InitialsEntry()
        e.update(cycleUp = true, cycleDown = false, confirmPressed = false) // A -> B
        e.reset()
        // reset() legitimately forgets "up was already held" along with the letters -
        // otherwise a player who reset mid-hold (impossible in practice, since reset
        // only happens on RunLifecycle re-entering ENTER_INITIALS for a NEW run, but
        // worth pinning down) would have their very first genuine press silently
        // swallowed as a non-edge.
        e.update(cycleUp = true, cycleDown = false, confirmPressed = false)
        assertEquals("BAA", e.initialsString(), "the first press after reset must register as a fresh edge")
    }

    // --- isValidInitials / sanitizeEntries -------------------------------------------

    @Test
    fun `isValidInitials accepts exactly three uppercase letters`()
    {
        assertTrue(isValidInitials("ABC"))
        assertTrue(isValidInitials("ZZZ"))
    }

    @Test
    fun `isValidInitials rejects wrong length`()
    {
        assertFalse(isValidInitials("AB"))
        assertFalse(isValidInitials("ABCD"))
        assertFalse(isValidInitials(""))
    }

    @Test
    fun `isValidInitials rejects non-letter or lowercase content`()
    {
        assertFalse(isValidInitials("A1C"))
        assertFalse(isValidInitials("abc"))
        assertFalse(isValidInitials("A-C"))
    }

    @Test
    fun `sanitizeEntries drops entries with invalid initials or a non-positive score`()
    {
        val good = ScoreEntry("ABC", 100, 1L, 0L)
        val badInitials = ScoreEntry("A1", 100, 1L, 0L)
        val zeroScore = ScoreEntry("XYZ", 0, 1L, 0L)
        val negativeScore = ScoreEntry("XYZ", -50, 1L, 0L)

        val result = sanitizeEntries(listOf(good, badInitials, zeroScore, negativeScore))
        assertEquals(listOf(good), result)
    }

    @Test
    fun `sanitizeEntries treats a null input (total parse failure) as an empty board, not a crash`()
    {
        assertEquals(emptyList(), sanitizeEntries(null))
    }

    @Test
    fun `sanitizeEntries on an empty list is an empty list`()
    {
        assertEquals(emptyList(), sanitizeEntries(emptyList()))
    }
}
