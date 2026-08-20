package de.totec.doppel.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The privacy switch is one boolean, but what it promises is decided entirely by this function:
 * enough of a number to stay oriented, never enough to identify anybody — and never a name put in
 * the place a number just left.
 */
class SensitiveNumbersTest {
    @Test
    fun `a phone number keeps its dialling prefix and loses the subscriber`() {
        assertEquals("+49151…", maskNumber("+4915123456789"))
        assertEquals("49151…", maskNumber("4915123456789"))
        assertEquals("+31612…", maskNumber("+31612345678"))
    }

    @Test
    fun `a jid is masked without its domain`() {
        assertEquals("49151…", maskNumber("4915123456789@s.whatsapp.net"))
        assertEquals("21793…", maskNumber("217935482930174@lid"))
        assertEquals("12036…", maskNumber("120363041234567890@g.us"))
    }

    @Test
    fun `formatting inside a number does not survive it`() {
        assertEquals("+49151…", maskNumber("+49 151 234 567 89"))
        assertEquals("+49151…", maskNumber("  +49-151-23456789  "))
    }

    /** A name is not a number; masking one would only leave the other to identify the person. */
    @Test
    fun `a name is left alone`() {
        assertEquals("Anna", maskNumber("Anna"))
        assertEquals("Group 704810", maskNumber("Group 704810"))
        assertEquals("Uni WG 2024", maskNumber("Uni WG 2024"))
        assertEquals("", maskNumber(""))
    }

    /**
     * Nothing is gained by replacing four digits with an ellipsis, and a short code is not a
     * number that reaches a person.
     */
    @Test
    fun `a value too short to identify anybody is not shortened further`() {
        assertEquals("49151", maskNumber("49151"))
        assertEquals("+4915", maskNumber("+4915"))
    }
}
