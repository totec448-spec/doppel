package de.totec.doppel.integration

import de.totec.doppel.ai.ChatRole
import de.totec.doppel.engine.StoredTurnMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * P7: the model started opening its replies with WhatsApp-looking timestamp blocks.
 *
 * The cause was not retrospective sending after a reconnect — it was this renderer. Assistant turns
 * are replayed in the assistant role, so a `[2026-08-02T14:03] ` prefix on the bot's *own* past
 * replies reads to the model as the house style, and it copies it.
 */
class TurnHistoryRendererTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    /** 2026-08-02T12:03:00Z, which is 14:03 in Berlin. */
    private val noonish = 1_785_672_180_000L

    @Test
    fun `the models own turns are never prefixed`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(stored(role = "assistant", text = "Klar, mache ich.")),
            isGroup = false,
            timezone = berlin,
            includeTimestamps = true,
        )

        assertEquals(ChatRole.ASSISTANT, rendered.single().role)
        assertEquals("Klar, mache ich.", rendered.single().text)
    }

    @Test
    fun `an assistant turn stays unprefixed in a group too`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(stored(role = "assistant", text = "Bin dabei.", senderName = "Alex")),
            isGroup = true,
            timezone = berlin,
            includeTimestamps = true,
        )

        assertEquals("Bin dabei.", rendered.single().text)
    }

    /** Incoming context is genuinely useful, so the user side keeps its timestamp by default. */
    @Test
    fun `incoming messages keep the timestamp`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(stored(role = "user", text = "Kommst du?")),
            isGroup = false,
            timezone = berlin,
            includeTimestamps = true,
        )

        assertEquals("[2026-08-02T14:03] Kommst du?", rendered.single().text)
    }

    @Test
    fun `the timestamp follows the configured timezone`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(stored(role = "user", text = "Kommst du?")),
            isGroup = false,
            timezone = ZoneId.of("UTC"),
            includeTimestamps = true,
        )

        assertEquals("[2026-08-02T12:03] Kommst du?", rendered.single().text)
    }

    /** The escape hatch for operators whose model imitates the prefix anyway. */
    @Test
    fun `history_timestamps off removes the incoming prefix and keeps the speaker`() {
        val history = listOf(stored(role = "user", text = "Kommst du?", senderName = "Ada"))

        val withTimes = TurnHistoryRenderer.render(history, isGroup = true, timezone = berlin, includeTimestamps = true)
        val without = TurnHistoryRenderer.render(history, isGroup = true, timezone = berlin, includeTimestamps = false)

        assertEquals("[2026-08-02T14:03] Ada: Kommst du?", withTimes.single().text)
        assertEquals("Ada: Kommst du?", without.single().text)
    }

    @Test
    fun `a speaker name only appears in groups`() {
        val history = listOf(stored(role = "user", text = "Hi", senderName = "Ada"))

        val direct = TurnHistoryRenderer.render(history, isGroup = false, timezone = berlin, includeTimestamps = false)
        val group = TurnHistoryRenderer.render(history, isGroup = true, timezone = berlin, includeTimestamps = false)

        assertEquals("Hi", direct.single().text)
        assertEquals("Ada: Hi", group.single().text)
    }

    @Test
    fun `a blank sender name does not produce a bare separator`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(stored(role = "user", text = "Hi", senderName = "   ")),
            isGroup = true,
            timezone = berlin,
            includeTimestamps = false,
        )

        assertEquals("Hi", rendered.single().text)
    }

    @Test
    fun `an overlong sender name is bounded`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(stored(role = "user", text = "Hi", senderName = "N".repeat(400))),
            isGroup = true,
            timezone = berlin,
            includeTimestamps = false,
        )

        assertEquals("${"N".repeat(100)}: Hi", rendered.single().text)
    }

    @Test
    fun `blank and unknown roles are dropped`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(
                stored(role = "user", text = "   "),
                stored(role = "system", text = "internal"),
                stored(role = "tool", text = "{}"),
                stored(role = "USER", text = "zählt"),
            ),
            isGroup = false,
            timezone = berlin,
            includeTimestamps = false,
        )

        assertEquals(listOf("zählt"), rendered.map { it.text })
    }

    @Test
    fun `operator injection keeps its exact position while internal system rows stay hidden`() {
        val rendered =
            TurnHistoryRenderer.render(
                history =
                    listOf(
                        stored(role = "user", text = "before"),
                        stored(
                            role = "system",
                            text = "[in your head]\nAnswer the next question in one sentence.",
                            operatorInjection = true,
                        ),
                        stored(role = "system", text = "internal marker"),
                        stored(role = "assistant", text = "after"),
                    ),
                isGroup = false,
                timezone = berlin,
                includeTimestamps = false,
            )

        assertEquals(
            listOf(
                "before",
                "[in your head]\nAnswer the next question in one sentence.",
                "after",
            ),
            rendered.map { it.text },
        )
        assertEquals(
            listOf(ChatRole.USER, ChatRole.USER, ChatRole.ASSISTANT),
            rendered.map { it.role },
        )
    }

    /**
     * Rows written before the wording changed are still in the database, and a chat that shows the
     * model two different framings of the same mechanism teaches it that the framing is negotiable.
     */
    @Test
    fun `notes stored in an older wording are replayed in the current one`() {
        val legacy =
            listOf(
                "## Injection\nShe just got back from Berlin.",
                "Important context from the operator:\nShe just got back from Berlin.",
            )

        val rendered =
            TurnHistoryRenderer.render(
                history = legacy.map { stored(role = "system", text = it, operatorInjection = true) },
                isGroup = false,
                timezone = berlin,
                includeTimestamps = false,
            )

        assertEquals(
            List(legacy.size) { "[in your head]\nShe just got back from Berlin." },
            rendered.map { it.text },
        )
    }

    /** The whole point, stated as one assertion: nothing the model wrote carries a bracket. */
    @Test
    fun `no assistant line in a mixed history starts with a bracket`() {
        val rendered = TurnHistoryRenderer.render(
            history = (1..10).map {
                stored(
                    role = if (it % 2 == 0) "assistant" else "user",
                    text = "Nachricht $it",
                    senderName = "Ada",
                )
            },
            isGroup = true,
            timezone = berlin,
            includeTimestamps = true,
        )

        val assistant = rendered.filter { it.role == ChatRole.ASSISTANT }
        assertEquals(5, assistant.size)
        assertTrue(assistant.none { it.text.startsWith("[") })
        assertFalse(assistant.any { it.text.contains("Ada:") })
        assertTrue(rendered.filter { it.role == ChatRole.USER }.all { it.text.startsWith("[") })
    }

    /**
     * A reply that carries only its own words reads as a non sequitur once the message it points
     * at has scrolled out of view — which is how "is that your serious?" got answered with "what
     * am I supposed to look at, nothing came in".
     */
    @Test
    fun `an incoming reply carries the quoted words it points at`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(
                stored(
                    role = "user",
                    text = "ist das dein ernst?",
                    quotedText = "text hätt ich schon, aber sag mir erstmal was nettes",
                ),
            ),
            isGroup = false,
            timezone = berlin,
            includeTimestamps = false,
        )

        assertEquals(
            "[replying to: text hätt ich schon, aber sag mir erstmal was nettes] ist das dein ernst?",
            rendered.single().text,
        )
    }

    @Test
    fun `the quote sits behind the timestamp and speaker rather than in front of them`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(stored(role = "user", text = "ja klar", senderName = "Ada", quotedText = "kommst du?")),
            isGroup = true,
            timezone = berlin,
            includeTimestamps = true,
        )

        assertEquals("[2026-08-02T14:03] Ada: [replying to: kommst du?] ja klar", rendered.single().text)
    }

    @Test
    fun `a message without a quote is untouched and a blank quote adds nothing`() {
        val rendered = TurnHistoryRenderer.render(
            history = listOf(
                stored(role = "user", text = "hi"),
                stored(role = "user", text = "hey", quotedText = "   "),
            ),
            isGroup = false,
            timezone = berlin,
            includeTimestamps = false,
        )

        assertEquals(listOf("hi", "hey"), rendered.map { it.text })
    }

    private fun stored(
        role: String,
        text: String,
        senderName: String? = null,
        timestampMs: Long = noonish,
        quotedText: String? = null,
        operatorInjection: Boolean = false,
    ) = StoredTurnMessage(
        id = "m-$text",
        role = role,
        text = text,
        timestampMs = timestampMs,
        senderName = senderName,
        operatorInjection = operatorInjection,
        quotedText = quotedText,
    )
}
