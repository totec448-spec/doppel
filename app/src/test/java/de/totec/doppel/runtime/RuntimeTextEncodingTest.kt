package de.totec.doppel.runtime

import de.totec.doppel.security.privacySafeErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTextEncodingTest {
    @Test
    fun runtimeLabelsCompileToExpectedText() {
        assertEquals("Doppel is running", RuntimeText.TITLE_RUNNING)
        assertEquals("Doppel needs attention", RuntimeText.TITLE_ATTENTION)
        assertEquals("Connection is degraded", RuntimeText.CONNECTION_DEGRADED)
        assertEquals("The bot engine is unavailable", RuntimeText.ENGINE_UNAVAILABLE)

        val labels = listOf(
            RuntimeText.TITLE_RUNNING,
            RuntimeText.TITLE_ATTENTION,
            RuntimeText.CONNECTION_DEGRADED,
            RuntimeText.ENGINE_UNAVAILABLE,
        )
        // These reach the user through a notification, so a mis-decoded build must fail here
        // rather than on someone's status bar.
        assertFalse(
            labels.any { value ->
                value.contains('Ã') ||
                    value.contains('Â') ||
                    value.contains('�')
            },
        )
    }

    @Test
    fun persistedErrorTypeNeverIncludesTheExceptionMessage() {
        val sensitiveMessage = "private-message-and-token"
        val result = privacySafeErrorType(IllegalStateException(sensitiveMessage))

        assertEquals("IllegalStateException", result)
        assertFalse(result.contains(sensitiveMessage))
        assertTrue(result.length <= 120)
    }
}
