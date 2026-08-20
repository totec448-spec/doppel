package de.totec.doppel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the operator types into "Tell what to send" is the one piece of prompt text a user writes
 * themselves, and how it is framed decides whether the persona sounds like herself or like someone
 * reading out a note she was handed. That framing is a single string — easy to lose in an edit,
 * and invisible afterwards except in the messages that go to real people.
 */
class ManualOutreachReasonTest {
    @Test
    fun withoutANoteTheOpenerIsEntirelyHers() {
        val plain = BotEngine.manualOutreachReason(null)

        assertEquals("blank input is no input", plain, BotEngine.manualOutreachReason("   "))
        assertFalse("nothing to say means nothing is quoted at her", plain.contains(':'))
        assertTrue("a confirmed send must not become silence", plain.contains("[no reply]"))
    }

    @Test
    fun aNoteBecomesHerOwnIntention() {
        val reason = BotEngine.manualOutreachReason("  ask her about the trip  ")

        assertTrue(reason.contains("ask her about the trip"))
        assertTrue(
            "the note is what she wants, not an instruction she received",
            reason.contains("In your mind you already know what you want to say to them:"),
        )
        assertTrue("her words, not the operator's", reason.contains("in your own words"))
        // A hand-typed outreach that answers [no reply] is the operator being ignored, not
        // restraint: they have already said what they want sent.
        assertTrue(reason.contains("[no reply]"))
    }

    /** A note is an idea, not a script — and not an unbounded write into the prompt either. */
    @Test
    fun aNoteCannotGrowIntoAnEssay() {
        val reason = BotEngine.manualOutreachReason("x".repeat(5_000))

        assertFalse(reason.contains("x".repeat(401)))
        assertTrue(reason.contains("x".repeat(400)))
    }
}
