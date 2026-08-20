package de.totec.doppel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppChatImportParserTest {
    @Test
    fun `parses Android export and assigns the selected sender to the bot`() {
        val parsed =
            WhatsAppChatImportParser.parse(
                source =
                    """
                    08.08.26, 20:01 - Alice: First line
                    continuation
                    08.08.26, 20:02 - Me: My reply
                    """.trimIndent(),
                botSender = "me",
            )

        assertEquals(2, parsed.size)
        assertEquals("First line\ncontinuation", parsed[0].body)
        assertFalse(parsed[0].sentByBot)
        assertTrue(parsed[1].sentByBot)
    }

    @Test
    fun `parses bracketed iOS export and produces deterministic ids`() {
        val source =
            """
            [08.08.26, 20:01:02] Alice: Hi
            [08.08.26, 20:02:03] Me: Hey
            """.trimIndent()

        val first = WhatsAppChatImportParser.parse(source, "Me")
        val second = WhatsAppChatImportParser.parse(source, "Me")

        assertEquals(first.map { it.providerMessageId }, second.map { it.providerMessageId })
        assertEquals("08.08.26, 20:01:02", first.first().exportedTimestamp)
    }

    @Test
    fun `does not append timestamped system events to the previous message`() {
        val parsed =
            WhatsAppChatImportParser.parse(
                source =
                    """
                    08.08.26, 20:01 - Alice: Hi
                    08.08.26, 20:01 - Messages and calls are end-to-end encrypted.
                    08.08.26, 20:02 - Me: Hey
                    """.trimIndent(),
                botSender = "Me",
            )

        assertEquals(2, parsed.size)
        assertEquals("Hi", parsed.first().body)
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects a sender that is absent from the export`() {
        WhatsAppChatImportParser.parse("08.08.26, 20:01 - Alice: Hi", "Nobody")
    }

    @Test
    fun `resolves the bot side from the connected account without a user prompt`() {
        val parsed =
            WhatsAppChatImportParser.parseAutomatically(
                source =
                    """
                    6/20/26, 18:23 - Alice BAUER: Helly
                    6/20/26, 18:23 - Lina: Oh hey how are you
                    """.trimIndent(),
                botAccountCandidates = listOf("Lina"),
                remoteParticipantCandidates = listOf("Alice BAUER"),
            )

        assertFalse(parsed.first().sentByBot)
        assertTrue(parsed.last().sentByBot)
    }

    @Test
    fun `resolves the bot as the other side of a direct export`() {
        val parsed =
            WhatsAppChatImportParser.parseAutomatically(
                source =
                    """
                    6/20/26, 18:23 - Alice BAUER: Helly
                    6/20/26, 18:23 - Local account: Hey
                    """.trimIndent(),
                botAccountCandidates = emptyList(),
                remoteParticipantCandidates = listOf("Alice BAUER"),
            )

        assertEquals(listOf(false, true), parsed.map { it.sentByBot })
    }

    @Test
    fun `keeps the newest characters instead of rejecting an oversized export`() {
        val source =
            "x".repeat(WhatsAppChatImportParser.MAX_SOURCE_CHARACTERS) +
                "\n08.08.26, 20:01 - Alice: newest inbound" +
                "\n08.08.26, 20:02 - Me: newest outbound"

        val parsed = WhatsAppChatImportParser.parse(source, "Me")

        assertEquals(2, parsed.size)
        assertEquals("newest inbound", parsed.first().body)
        assertTrue(parsed.last().sentByBot)
    }

    @Test
    fun `keeps the latest five thousand parsed messages`() {
        val source =
            (0..WhatsAppChatImportParser.MAX_MESSAGES).joinToString("\n") { index ->
                "08.08.26, 20:01 - ${if (index % 2 == 0) "Me" else "Alice"}: $index"
            }

        val parsed = WhatsAppChatImportParser.parse(source, "Me")

        assertEquals(WhatsAppChatImportParser.MAX_MESSAGES, parsed.size)
        assertEquals("1", parsed.first().body)
        assertEquals(WhatsAppChatImportParser.MAX_MESSAGES.toString(), parsed.last().body)
    }
}
