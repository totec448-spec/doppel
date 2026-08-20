package de.totec.doppel.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMemoryPolicyTest {
    @Test
    fun `retained overlap and interval add up to the complete window`() {
        assertEquals(80, ConversationMemoryPolicy.completeWindow(10, 70))
        assertEquals(
            ConversationMemoryPolicy.DEFAULT_COMPLETE_CHAT_WINDOW_MESSAGES,
            ConversationMemoryPolicy.completeWindow(
                ConversationMemoryPolicy.DEFAULT_RETAINED_HISTORY_MESSAGES,
                ConversationMemoryPolicy.DEFAULT_MEMORY_INTERVAL_MESSAGES,
            ),
        )
        assertEquals(275, ConversationMemoryPolicy.completeWindow(75, 200))
    }

    @Test
    fun `the interval alone decides when the next memory is written`() {
        assertEquals(70, ConversationMemoryPolicy.newMessagesAfterReset(70))
        assertEquals(25, ConversationMemoryPolicy.newMessagesAfterReset(25))
    }

    @Test
    fun `configured values stay inside their bounds`() {
        // Unset means "not configured", not "never summarize", so it falls back to the default.
        assertEquals(
            ConversationMemoryPolicy.DEFAULT_MEMORY_INTERVAL_MESSAGES,
            ConversationMemoryPolicy.refreshInterval(0),
        )
        assertEquals(
            ConversationMemoryPolicy.MIN_MEMORY_INTERVAL_MESSAGES,
            ConversationMemoryPolicy.refreshInterval(1),
        )
        assertEquals(
            ConversationMemoryPolicy.MAX_MEMORY_INTERVAL_MESSAGES,
            ConversationMemoryPolicy.refreshInterval(5_000),
        )
        assertEquals(0, ConversationMemoryPolicy.retainedMessages(-5))
        assertEquals(
            ConversationMemoryPolicy.MAX_RETAINED_HISTORY_MESSAGES,
            ConversationMemoryPolicy.retainedMessages(1_000),
        )
        assertEquals(
            ConversationMemoryPolicy.MAX_COMPLETE_CHAT_WINDOW_MESSAGES,
            ConversationMemoryPolicy.completeWindow(1_000, 5_000),
        )
    }

    @Test
    fun `the global to chat ratio is configurable and bounded`() {
        assertEquals(1L, ConversationMemoryPolicy.personaSynthesisInterval(1))
        assertEquals(7L, ConversationMemoryPolicy.personaSynthesisInterval(7))
        // Unset falls back to the default rather than to "never", which a 0 would otherwise mean.
        assertEquals(
            ConversationMemoryPolicy.DEFAULT_PERSONA_MEMORY_EVERY_CHAT_REFRESHES.toLong(),
            ConversationMemoryPolicy.personaSynthesisInterval(0),
        )
        assertEquals(
            ConversationMemoryPolicy.MAX_PERSONA_MEMORY_EVERY_CHAT_REFRESHES.toLong(),
            ConversationMemoryPolicy.personaSynthesisInterval(99),
        )
    }
}
