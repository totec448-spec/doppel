package de.totec.doppel.integration

import de.totec.doppel.engine.ChatHistoryLabels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciler rebuilds a chat-log line from the outbox row that delivered it. A recovered
 * message has to be indistinguishable from one recorded the moment it went out — the model reads
 * both the same way.
 */
class OutboxHistoryReconcilerTest {
    @Test
    fun `a recovered text reads exactly like the line the turn writes`() {
        val payload = JSONObject().put("chatId", "49170@s.whatsapp.net").put("text", "  hey  ")

        assertEquals(
            ChatHistoryLabels.outgoingText("hey"),
            outboxHistoryLine("send_text", payload),
        )
    }

    @Test
    fun `a quoted reply keeps the reference the bubble carried`() {
        val payload =
            JSONObject()
                .put("text", "yes")
                .put("replyTo", JSONObject().put("id", "ABC").put("preview", "are you there"))

        assertEquals(
            ChatHistoryLabels.outgoingText("yes", "are you there"),
            outboxHistoryLine("send_text", payload),
        )
    }

    @Test
    fun `a voice note is a voice note, not the audio file it was uploaded as`() {
        val payload = JSONObject().put("kind", "audio").put("ptt", true)

        assertEquals("You sent a voice note", outboxHistoryLine("send_media", payload))
    }

    @Test
    fun `a picture keeps its caption`() {
        val payload = JSONObject().put("kind", "image").put("caption", "look")

        assertEquals("You sent an image: look", outboxHistoryLine("send_media", payload))
    }

    @Test
    fun `an empty send leaves nothing behind to recover`() {
        assertNull(outboxHistoryLine("send_text", JSONObject().put("text", "   ")))
        assertNull(outboxHistoryLine("send_reaction", JSONObject().put("emoji", "👍")))
    }

    @Test
    fun `only sends are recoverable`() {
        assertTrue("send_text" in RECOVERABLE_OPERATIONS)
        assertTrue("send_media" in RECOVERABLE_OPERATIONS)
        assertFalse("edit_message" in RECOVERABLE_OPERATIONS)
        assertFalse("send_reaction" in RECOVERABLE_OPERATIONS)
        assertFalse("block" in RECOVERABLE_OPERATIONS)
    }

    /** Command answers never entered the AI history, so recovery must not put them there either. */
    @Test
    fun `command answers stay out of the chat log`() {
        assertTrue(isAdminOutboxKey("admin:3EB0C767D1:0".toBridgeRequestId()))
        assertTrue(isAdminOutboxKey("admin-image:req-1234".toBridgeRequestId()))
        assertFalse(isAdminOutboxKey("turn:3EB0C767D1:abcd:text:0".toBridgeRequestId()))
    }

    /**
     * A sweep reaches back a month, so the persona the chat is assigned *today* is the wrong
     * answer for exactly the rows this class repairs.
     */
    @Test
    fun `a recovered message belongs to the persona the chat was logging then`() {
        assertEquals(
            "alice",
            personaKeyFromLoggedConversations(
                listOf("49170@s.whatsapp.net#alice", "49170@s.whatsapp.net#bob"),
            ),
        )
    }

    @Test
    fun `rows that name no persona are skipped, not answered with`() {
        assertEquals(
            "bob",
            personaKeyFromLoggedConversations(
                // A legacy row without the column, then one whose key carries no persona at all.
                listOf(null, "49170@s.whatsapp.net", "49170@s.whatsapp.net#bob"),
            ),
        )
    }

    /** Nothing logged that far back is the one case where the current assignment is right. */
    @Test
    fun `a chat with no usable history falls through to the caller's default`() {
        assertNull(personaKeyFromLoggedConversations(emptyList()))
        assertNull(personaKeyFromLoggedConversations(listOf(null, "49170@s.whatsapp.net#")))
    }

    /**
     * Deleting a chat leaves the outbox ledger standing, and the sweep read every one of those rows
     * as a message the history had lost. The contact's side stayed deleted, the bot's side came
     * back — into whatever persona was selected by then.
     */
    @Test
    fun `a deleted chat does not get the bot's old messages back`() {
        val reopenedAt = 2_000L

        assertFalse(belongsToCurrentChat(sentAt = 1_999L, chatCreatedAt = reopenedAt))
        // The message that reopened the chat is the chat: same millisecond, still recoverable.
        assertTrue(belongsToCurrentChat(sentAt = reopenedAt, chatCreatedAt = reopenedAt))
        assertTrue(belongsToCurrentChat(sentAt = 2_001L, chatCreatedAt = reopenedAt))
    }

    @Test
    fun `a row without a server message ID is not recoverable`() {
        assertNull(outboxTransportMessageId(null))
        assertNull(outboxTransportMessageId(""))
        assertNull(outboxTransportMessageId("{}"))
        assertNull(outboxTransportMessageId("""{"message":{"id":""}}"""))
        assertEquals("3EB0", outboxTransportMessageId("""{"message":{"id":"3EB0"}}"""))
    }
}
