package de.totec.doppel.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeControlTokenTest {
    @Test
    fun acceptsHexAndBase64UrlWithAtLeast256Bits() {
        assertTrue(BridgeControlToken.isValid("01".repeat(32)))
        assertTrue(BridgeControlToken.isValid("A".repeat(43)))
    }

    @Test
    fun rejectsPasswordsMalformedAndUndersizedValues() {
        assertFalse(BridgeControlToken.isValid("this-is-a-long-but-human-password-value"))
        assertFalse(BridgeControlToken.isValid("01".repeat(31)))
        assertFalse(BridgeControlToken.isValid("A".repeat(42)))
        assertFalse(BridgeControlToken.isValid("A".repeat(43) + "="))
        assertFalse(BridgeControlToken.isValid(null))
    }
}
