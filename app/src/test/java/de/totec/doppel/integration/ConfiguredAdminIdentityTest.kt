package de.totec.doppel.integration

import de.totec.doppel.domain.ChatEventKind
import de.totec.doppel.domain.IncomingEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class ConfiguredAdminIdentityTest {
    @Test
    fun `direct chat phone identity grants configured owner when sender is lid`() {
        val event = event(
            chatJid = "491234567890@s.whatsapp.net",
            senderJid = "123456789012345@lid",
            isGroup = false,
        )

        assertTrue(configuredAdminMatches(event, listOf("491234567890")))
    }

    @Test
    fun `direct chat alias grants configured owner when primary ids are lids`() {
        val event = event(
            chatJid = "123456789012345@lid",
            senderJid = "123456789012345@lid",
            isGroup = false,
            chatAliases = listOf("491234567890@s.whatsapp.net"),
        )

        assertTrue(configuredAdminMatches(event, listOf("491234567890")))
    }

    @Test
    fun `group chat identity never grants a participant owner access`() {
        val event = event(
            chatJid = "491234567890@g.us",
            senderJid = "123456789012345@lid",
            isGroup = true,
        )

        assertFalse(configuredAdminMatches(event, listOf("491234567890")))
    }

    @Test
    fun `persisted pn and lid aliases survive recovery`() {
        val metadata =
            JSONObject()
                .put(
                    "chatAliases",
                    JSONArray(listOf("491234567890@s.whatsapp.net", "123456789012345@lid")),
                )

        assertEquals(
            listOf("491234567890@s.whatsapp.net", "123456789012345@lid"),
            restoredIdentityAliases(metadata, "chatAliases"),
        )
    }

    private fun event(
        chatJid: String,
        senderJid: String,
        isGroup: Boolean,
        chatAliases: List<String> = emptyList(),
    ) = IncomingEvent(
        eventId = "event",
        sequence = 1,
        kind = ChatEventKind.MESSAGE,
        messageId = "message",
        chatJid = chatJid,
        isGroup = isGroup,
        senderJid = senderJid,
        fromMe = false,
        timestampMs = 1,
        text = "hello",
        chatAliases = chatAliases,
    )
}
