package de.totec.doppel.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BridgeRequestIdTest {
    @Test
    fun `normalization cannot alias different durable operations`() {
        assertNotEquals("turn:chat/a:text:0".toBridgeRequestId(), "turn:chat?a:text:0".toBridgeRequestId())
        assertNotEquals("turn:chat/a:text:0".toBridgeRequestId(), "turn:chat_a:text:0".toBridgeRequestId())
        assertEquals("turn:chat-a:text:0", "turn:chat-a:text:0".toBridgeRequestId())
        assertEquals("turn:chat/a:text:0".toBridgeRequestId(), "turn:chat/a:text:0".toBridgeRequestId())
    }
}
