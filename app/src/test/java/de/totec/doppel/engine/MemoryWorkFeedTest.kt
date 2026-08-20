package de.totec.doppel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWorkFeedTest {
    @Test
    fun `final memory prose streams without storing reasoning`() {
        val feed = MemoryWorkFeed()
        val work =
            MemoryWork(
                scope = MemoryWorkScope.CHAT,
                key = "chat#persona",
                personaKey = "persona",
                chatJid = "chat@s.whatsapp.net",
            )

        feed.started(work)
        assertEquals(MemoryWorkPhase.REASONING, feed.inFlight.value.single().phase)
        assertTrue(feed.inFlight.value.single().draft.isEmpty())

        feed.appendDraft(MemoryWorkScope.CHAT, work.key, "First ")
        feed.appendDraft(MemoryWorkScope.CHAT, work.key, "memory line")

        assertEquals(MemoryWorkPhase.WRITING, feed.inFlight.value.single().phase)
        assertEquals("First memory line", feed.inFlight.value.single().draft)
        feed.finished(MemoryWorkScope.CHAT, work.key)
        assertTrue(feed.inFlight.value.isEmpty())
    }
}
