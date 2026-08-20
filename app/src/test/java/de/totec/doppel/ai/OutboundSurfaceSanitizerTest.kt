package de.totec.doppel.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboundSurfaceSanitizerTest {
    @Test
    fun `caption cannot leak history labels protocol markup or reasoning`() {
        assertEquals(
            "echte caption",
            OutboundSurfaceSanitizer.sanitize(
                "<think>internal</think> You sent: [whispers] echte caption",
            ),
        )
    }

    @Test
    fun `markup only surface becomes empty instead of restoring markup`() {
        assertEquals("", OutboundSurfaceSanitizer.sanitize("[moans] [short pause]"))
    }

    /**
     * A quoted reply carries its label inline now — `You sent (quoting "…"): text` — and a stored
     * history row can hold several labelled lines. Stripping only the very first character position
     * would let every later line through onto the wire.
     */
    @Test
    fun `every labelled line loses its label, not just the first`() {
        assertEquals(
            "answer",
            HistoryLabelGuard.stripLeadingLabel("""You sent (quoting "was ist los"): answer"""),
        )
        assertEquals(
            "earlier\nfirst",
            HistoryLabelGuard.stripLeadingLabel("In reply to: earlier\nYou sent: first"),
        )
    }
}
