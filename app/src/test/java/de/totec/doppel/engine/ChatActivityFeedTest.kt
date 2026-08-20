package de.totec.doppel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed is the only thing standing between a token stream and a list the UI has to diff, so the
 * rules about what becomes a new line and what does not are the whole point of it.
 */
class ChatActivityFeedTest {
    @Test
    fun `streamed reasoning grows one line instead of one line per batch`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)

        feed.reasoning(CHAT, "Sie fragt ", nowMs = 1L)
        feed.reasoning(CHAT, "nach dem Wetter.", nowMs = 2L)

        val trace = feed.live.value?.trace.orEmpty()
        assertEquals(1, trace.size)
        assertEquals(TraceKind.REASONING, trace.single().kind)
        assertEquals("Sie fragt nach dem Wetter.", trace.single().text)
    }

    /**
     * A tool call in the middle of a thought is a real boundary: the sentences on either side of it
     * were written for different reasons, and reading them as one paragraph would hide the call.
     */
    @Test
    fun `a tool call ends the paragraph it interrupts`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)

        feed.reasoning(CHAT, "Ich schaue nach.", nowMs = 1L)
        feed.trace(CHAT, TraceKind.TOOL, "search_current_chat {}", nowMs = 2L)
        feed.reasoning(CHAT, "Nichts gefunden.", nowMs = 3L)

        val trace = feed.live.value?.trace.orEmpty()
        assertEquals(3, trace.size)
        assertEquals(listOf(TraceKind.REASONING, TraceKind.TOOL, TraceKind.REASONING), trace.map { it.kind })
        assertEquals("Nichts gefunden.", trace.last().text)
    }

    /** Long enough and it becomes readable again — a single unbounded line is not a trace. */
    @Test
    fun `a long chain of thought is broken into paragraphs`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)

        repeat(40) { feed.reasoning(CHAT, "x".repeat(50), nowMs = it.toLong()) }

        val trace = feed.live.value?.trace.orEmpty()
        assertTrue("Expected several paragraphs, got ${trace.size}", trace.size > 1)
        assertTrue(trace.all { it.text.length <= 600 })
    }

    @Test
    fun `one oversized reasoning callback is split before entering UI state`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)

        feed.reasoning(CHAT, "x".repeat(1_301), nowMs = 1L)

        val trace = feed.live.value?.trace.orEmpty()
        assertEquals(listOf(600, 600, 101), trace.map { it.text.length })
    }

    /** The stream belongs to the turn that opened it; a late chunk for another chat is noise. */
    @Test
    fun `reasoning for a chat that does not hold the engine is dropped`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)

        feed.reasoning("other@s.whatsapp.net", "Nicht hier.", nowMs = 1L)

        assertTrue(feed.live.value?.trace.orEmpty().isEmpty())
    }

    @Test
    fun `finishing a turn takes the trace with it`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)
        feed.reasoning(CHAT, "Kurz gedacht.", nowMs = 1L)

        feed.finish(CHAT)

        assertNull(feed.live.value)
    }

    @Test
    fun `a newly armed message replaces the previous failed live turn`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)
        feed.failed(CHAT, "OpenRouter protocol error: response_too_large", nowMs = 1L)

        feed.armed(CHAT, dueAtMs = 75_000L, nowMs = 2L)

        assertNull(feed.live.value)
        assertEquals(ChatStage.WAITING, feed.chats.value.getValue(CHAT).stage)
        assertEquals(75_000L, feed.chats.value.getValue(CHAT).dueAtMs)
    }

    @Test
    fun `a newly queued message replaces the previous failed live turn`() {
        val feed = ChatActivityFeed()
        feed.begin(CHAT, "turn-1", nowMs = 0L)
        feed.failed(CHAT, "provider failed", nowMs = 1L)

        feed.queued(CHAT, nowMs = 2L)

        assertNull(feed.live.value)
        assertEquals(ChatStage.QUEUED, feed.chats.value.getValue(CHAT).stage)
    }

    private companion object {
        const val CHAT = "4915100000000@s.whatsapp.net"
    }
}
