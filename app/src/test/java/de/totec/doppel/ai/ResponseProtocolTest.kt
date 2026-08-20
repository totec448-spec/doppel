package de.totec.doppel.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseProtocolTest {
    private val parser = ResponseProtocolParser()

    @Test
    fun `tagged and high confidence leading reasoning never become chat text`() {
        val tagged = parser.parse("Hallo<think>private chain</think>Welt")
        assertEquals(listOf("Hallo Welt"), tagged.bubbles)
        assertTrue(tagged.strippedReasoning)
        assertTrue(tagged.strippedReasoningCharacters > 0)

        val plain = parser.parse("We need to answer the user in German. Klar, mache ich.")
        assertEquals(listOf("Klar, mache ich."), plain.bubbles)
        assertTrue(plain.strippedReasoning)
    }

    @Test
    fun `ordinary first person text is not mistaken for reasoning`() {
        val result = parser.parse("I need to sleep. Gute Nacht.")

        assertEquals(listOf("I need to sleep. Gute Nacht."), result.bubbles)
        assertFalse(result.strippedReasoning)
    }

    @Test
    fun `no reply is accepted only as the exact trimmed response`() {
        assertTrue(parser.parse(" \n[no reply]\n").noReply)
        assertTrue(
            parser.parse(
                "[no reply]",
                OutputSettings(allowNoReply = false),
            ).noReply,
        )

        val embedded = parser.parse("Vielleicht [no reply]")
        assertFalse(embedded.noReply)
        assertEquals(listOf("Vielleicht [no reply]"), embedded.bubbles)
    }

    @Test
    fun `reaction quote and bubbles are extracted without leaking markers`() {
        val result = parser.parse(
            """
            [reply:"die \"rote\" Tür"]Klar 🙂 [react:👍]

            Machen wir morgen.

            Dritter Teil.

            Überschuss.
            """.trimIndent(),
            OutputSettings(maxBubbles = 3),
        )

        assertEquals("👍", result.reaction)
        assertEquals("die \"rote\" Tür", result.quote?.sourceSnippet)
        assertEquals(3, result.bubbles.size)
        assertEquals("Dritter Teil.", result.bubbles.last())
        assertTrue(result.bubbles.none { '[' in it || ']' in it })
    }

    @Test
    fun `two replies plus four plain lines stay six aligned bubbles`() {
        val result =
            parser.parse(
                """
                [reply:"erste obere Nachricht"]Erste Reply-Antwort
                [reply:"Text aus der Sprachnachricht"]Zweite Reply-Antwort
                Nachricht eins
                Nachricht zwei
                Nachricht drei
                Nachricht vier
                """.trimIndent(),
                OutputSettings(maxBubbles = 8),
            )

        assertEquals(
            listOf(
                "Erste Reply-Antwort",
                "Zweite Reply-Antwort",
                "Nachricht eins",
                "Nachricht zwei",
                "Nachricht drei",
                "Nachricht vier",
            ),
            result.bubbles,
        )
        assertEquals(
            listOf(
                "erste obere Nachricht",
                "Text aus der Sprachnachricht",
                null,
                null,
                null,
                null,
            ),
            result.bubbleReplies.map { it?.sourceSnippet },
        )
    }

    @Test
    fun `marker on its own line quotes the following bubble instead of vanishing`() {
        val result =
            parser.parse(
                """
                [reply:"deine alte Nachricht"]
                Da bin ich anderer Meinung.
                Und das hier ist normal.
                """.trimIndent(),
            )

        assertEquals(
            listOf("Da bin ich anderer Meinung.", "Und das hier ist normal."),
            result.bubbles,
        )
        assertEquals(
            listOf("deine alte Nachricht", null),
            result.bubbleReplies.map { it?.sourceSnippet },
        )
    }

    @Test
    fun `spacing quoting and wording variants all resolve to a real quote`() {
        val variants =
            mapOf(
                """[reply: "mit Leerzeichen"]Text""" to "mit Leerzeichen",
                "[reply:ohne Anführungszeichen]Text" to "ohne Anführungszeichen",
                """[Reply to "englische Schreibweise"]Text""" to "englische Schreibweise",
                """[antwort auf "deutsche Schreibweise"]Text""" to "deutsche Schreibweise",
                "[quote:'einfache Quotes']Text" to "einfache Quotes",
            )

        variants.forEach { (raw, expected) ->
            val result = parser.parse(raw)
            assertEquals(raw, listOf("Text"), result.bubbles)
            assertEquals(raw, expected, result.quote?.sourceSnippet)
        }
    }

    @Test
    fun `bare reply marker keeps the bubble and targets the newest message`() {
        val result = parser.parse("[reply]Passt.")

        assertEquals(listOf("Passt."), result.bubbles)
        assertNull(result.quote?.sourceSnippet)
        assertEquals(1, result.bubbleReplies.count { it != null })
    }

    @Test
    fun `invalid reaction is stripped but never emitted`() {
        val result = parser.parse("Text [react:not an emoji]")
        assertNull(result.reaction)
        assertEquals(listOf("Text"), result.bubbles)
    }

    @Test
    fun `native-style JSON tool fallbacks support object arrays and wrappers`() {
        val single = parser.parse(
            """{"tool":"send_voice_note","arguments":{"text":"Bin gleich da"}}""",
        )
        assertEquals("send_voice_note", single.fallbackToolCalls.single().name)
        assertEquals(
            "Bin gleich da",
            JSONObject(single.fallbackToolCalls.single().argumentsJson).getString("text"),
        )
        assertTrue(single.bubbles.isEmpty())

        val wrapped = parser.parse(
            """{"tools":[{"name":"list_chats","args":{}},{"tool":"unknown","args":{}}]}""",
        )
        assertEquals(listOf("list_chats"), wrapped.fallbackToolCalls.map(AiToolCall::name))

        val array = parser.parse(
            """[{"tool":"request_chat_memory_refresh","arguments":{}}]""",
        )
        assertEquals(1, array.fallbackToolCalls.size)
    }

    @Test
    fun `bracket fallbacks are conservative and malformed actions remain text`() {
        val result = parser.parse(
            """
            [voice:"Sprachnachricht"]
            Danach normal.
            [search_chat:irgendwas]
            [tool:send_image:{"asset_id":"img-7"}]
            """.trimIndent(),
        )

        assertEquals(
            listOf("send_voice_note", "send_image"),
            result.fallbackToolCalls.map(AiToolCall::name),
        )
        assertTrue(result.bubbles.any { it.contains("[search_chat:irgendwas]") })

        val malformed = parser.parse("""{"tool":"send_image","arguments":"not-json"}""")
        assertTrue(malformed.fallbackToolCalls.isEmpty())
        assertEquals(1, malformed.bubbles.size)
    }

    @Test
    fun `disabled marker features stay inert and never leak while multi-bubble stays active`() {
        val result = parser.parse(
            "[reply]Eins [react:🙂]\n\nZwei",
            OutputSettings(
                allowNoReply = false,
                allowReactions = false,
                allowQuoteReply = false,
            ),
        )

        assertNull(result.reaction)
        assertNull(result.quote)
        assertEquals(listOf("Eins", "Zwei"), result.bubbles)
    }

    @Test
    fun `single line break creates separate WhatsApp bubbles`() {
        val result = parser.parse(
            "ok.\nhier, deine zwei nachrichten. zufrieden?",
            OutputSettings(maxBubbles = 3),
        )

        assertEquals(
            listOf("ok.", "hier, deine zwei nachrichten. zufrieden?"),
            result.bubbles,
        )
    }
}
