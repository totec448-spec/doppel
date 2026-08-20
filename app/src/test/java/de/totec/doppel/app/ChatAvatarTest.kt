package de.totec.doppel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The avatar is the only thing distinguishing two rows before the number is read, so an initial
 * that is the same for everybody is worse than no avatar at all — which is exactly what the first
 * letter gave: `+49…` for every German contact, `Group …` for every unnamed group.
 *
 * The numbers here are drawn from the German regulator's 15 2 reserved test range, which is never
 * assigned to a subscriber. They still share the country code and prefix that caused the bug.
 */
class ChatAvatarTest {
    @Test
    fun `two german numbers do not share an initial`() {
        assertNotEquals("+4915200000174".initial(), "+4915200000037".initial())
    }

    @Test
    fun `a number is stood for by its last two digits`() {
        assertEquals("74", "+4915200000174".initial())
        assertEquals("37", "4915200000037@s.whatsapp.net".initial())
    }

    @Test
    fun `an unnamed group is stood for by its id, not by the word Group`() {
        assertEquals("10", "Group 704810".initial())
        assertNotEquals("Group 704810".initial(), "Group 167828".initial())
    }

    /** A name that is a name keeps its letter — that is what a person recognises it by. */
    @Test
    fun `a real name still shows its first letter`() {
        assertEquals("A", "Anna".initial())
        assertEquals("F", "Familie Müller".initial())
        assertEquals("W", "WG 3".initial())
    }

    @Test
    fun `a nameless chat falls back to a dot rather than to nothing`() {
        assertEquals("•", "".initial())
        assertEquals("•", "   ".initial())
    }

    /** The colour is identity, so it must not move when the roster reorders. */
    @Test
    fun `the colour of a chat is stable and inside the palette`() {
        val id = "4915200000174@s.whatsapp.net"
        assertEquals(id.accent(), id.accent())
        assertEquals(true, id.accent() in 0 until AVATAR_COLOURS)
    }
}
