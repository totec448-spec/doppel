package de.totec.doppel.engine

import de.totec.doppel.ai.HistoryLabelGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryLabelsTest {
    @Test
    fun `plain text and reactions preserve direction`() {
        assertEquals("User sent: hi", ChatHistoryLabels.incomingText(" hi "))
        assertEquals("You sent: hello", ChatHistoryLabels.outgoingText(" hello "))
        assertEquals("User reacted with: ❤️", ChatHistoryLabels.incomingReaction(" ❤️ "))
        assertEquals("You reacted with: 👍", ChatHistoryLabels.outgoingReaction(" 👍 "))
    }

    /**
     * The quoted message used to stand on a line of its own directly above the answer. The model
     * read that as how a message is written here and started sending contacts their own words back.
     */
    @Test
    fun `a quoted reply names its target without repeating it on a line of its own`() {
        val line = ChatHistoryLabels.outgoingText("answer", "earlier")

        assertEquals("You sent (quoting \"earlier\"): answer", line)
        assertFalse(line.contains('\n'))
    }

    @Test
    fun `the quote preview arrives labelled and is unwrapped before it is quoted`() {
        assertEquals(
            "You sent (quoting \"earlier\"): answer",
            ChatHistoryLabels.outgoingText("answer", "User sent: earlier"),
        )
    }

    @Test
    fun `a long quote is cut and an empty one leaves no trace`() {
        val long = "a".repeat(ChatHistoryLabels.MAX_QUOTE_SNIPPET_CHARS + 30)
        val line = ChatHistoryLabels.incomingText("ok", long)

        assertTrue(line.startsWith("User sent (quoting \"${"a".repeat(ChatHistoryLabels.MAX_QUOTE_SNIPPET_CHARS)}…\")"))
        assertEquals("User sent: ok", ChatHistoryLabels.incomingText("ok", "   "))
    }

    /** Whatever ends up in the label has to stay strippable, so it may not close the label early. */
    @Test
    fun `a quote full of colons and quotes cannot break the label back open`() {
        val line = ChatHistoryLabels.outgoingText("answer", """he said: "no" ok""")

        assertEquals("answer", HistoryLabelGuard.stripLeadingLabel(line))
    }

    @Test
    fun `reply context and mutations stay explicit`() {
        assertEquals("In reply to: earlier", ChatHistoryLabels.replyContext("earlier", "m1"))
        assertEquals("User edited a message: corrected", ChatHistoryLabels.incomingEdit("corrected"))
        assertEquals("User deleted a message.", ChatHistoryLabels.incomingDelete())
    }
}
