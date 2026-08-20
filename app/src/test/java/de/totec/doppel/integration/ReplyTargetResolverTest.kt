package de.totec.doppel.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyTargetResolverTest {
    @Test
    fun `two reply snippets resolve independently including persisted voice transcript`() {
        val candidates =
            listOf(
                ReplyTargetCandidate(
                    messageId = "text-message-id",
                    searchableText = "Nee, natürlich nicht, bin sehr stolz auf dich",
                ),
                ReplyTargetCandidate(
                    messageId = "voice-message-id",
                    searchableText =
                        "[Voice note] Sorry, I'm so fucking sorry dafür, aber keine Ahnung, Alter",
                ),
            )

        assertEquals(
            "text-message-id",
            resolveReplyTargetId(candidates, "bin sehr stolz auf dich"),
        )
        assertEquals(
            "voice-message-id",
            resolveReplyTargetId(candidates, "I'm so fucking sorry dafür"),
        )
    }

    @Test
    fun `a reply that names no message is dropped instead of guessing one`() {
        val candidates =
            listOf(
                ReplyTargetCandidate("older", "erste Nachricht"),
                ReplyTargetCandidate("newest", "zweite Nachricht"),
            )

        assertNull(resolveReplyTargetId(candidates, null))
        assertNull(resolveReplyTargetId(candidates, "   "))
    }

    @Test
    fun `a snippet that matches nothing is dropped instead of quoting the newest message`() {
        val candidates =
            listOf(
                ReplyTargetCandidate("older", "erste Nachricht"),
                ReplyTargetCandidate("newest", "zweite Nachricht"),
            )

        assertNull(resolveReplyTargetId(candidates, "worüber wir gestern beim Kaffee sprachen"))
    }

    @Test
    fun `the quoted words are recognised the way a model reproduces them`() {
        val candidates =
            listOf(
                ReplyTargetCandidate("older", "Guten Morgen, wie hast du geschlafen?"),
                ReplyTargetCandidate(
                    "target",
                    "Kommst du morgen mit zum Training, oder hast du schon was vor? 😅",
                ),
                ReplyTargetCandidate("newest", "ach egal"),
            )

        // Different case and punctuation.
        assertEquals("target", resolveReplyTargetId(candidates, "kommst du morgen mit zum training"))
        // Emoji dropped, wording shortened.
        assertEquals("target", resolveReplyTargetId(candidates, "hast du schon was vor"))
        // A word remembered wrong still leaves enough overlap.
        assertEquals(
            "target",
            resolveReplyTargetId(candidates, "kommst du morgen mit zum sport oder hast du was vor"),
        )
    }

    @Test
    fun `a short message cannot swallow a longer quotation`() {
        val candidates =
            listOf(
                ReplyTargetCandidate("real", "ich hol dich um acht ab, pass auf dich auf"),
                ReplyTargetCandidate("short", "ok"),
            )

        assertEquals("real", resolveReplyTargetId(candidates, "ich hol dich um acht ab"))
    }

    @Test
    fun `snippet can select an older inbound message instead of newest fallback`() {
        val candidates =
            listOf(
                ReplyTargetCandidate("older-voice", "das ist der ältere Audio-Text"),
                ReplyTargetCandidate("newest-voice", "das ist der neue Audio-Text"),
            )

        assertEquals(
            "older-voice",
            resolveReplyTargetId(candidates, "ältere Audio-Text"),
        )
    }
}
