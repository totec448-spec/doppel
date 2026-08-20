package de.totec.doppel.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWindowAnchorTest {
    private val limit = 10

    /** Newest-first ids for a chat that has produced [total] messages so far. */
    private fun loaded(total: Int): List<String> =
        (0 until total).map { "m${total - it}" }

    @Test
    fun `a short conversation needs no anchor`() {
        val decision = HistoryWindowAnchor.resolve(null, loaded(8), limit)

        assertEquals(8, decision.size)
        assertNull(decision.anchorMessageId)
        assertFalse(decision.reanchored)
    }

    @Test
    fun `exact capacity pins immediately instead of deadlocking at null`() {
        val decision = HistoryWindowAnchor.resolve(null, loaded(10), limit)

        assertEquals(10, decision.size)
        assertEquals("m1", decision.anchorMessageId)
        assertTrue(decision.reanchored)
    }

    @Test
    fun `first overflow pins the oldest overlap message`() {
        val decision = HistoryWindowAnchor.resolve(null, loaded(11), limit)

        assertEquals(10, decision.size)
        assertEquals("m2", decision.anchorMessageId)
        assertTrue(decision.reanchored)
    }

    @Test
    fun `pointer stays fixed through seventy messages until memory revision`() {
        var anchor: String? = null
        val reanchoredAt = mutableListOf<Int>()

        for (total in 11..80) {
            val decision = HistoryWindowAnchor.resolve(anchor, loaded(total), limit)
            anchor = decision.anchorMessageId
            if (decision.reanchored) reanchoredAt += total
            assertEquals("m2", anchor)
            assertEquals(total - 1, decision.size)
        }

        assertEquals(listOf(11), reanchoredAt)
    }

    @Test
    fun `memory revision releases pointer and resets to newest ten`() {
        val decision = HistoryWindowAnchor.resolve(null, loaded(80), limit)

        assertEquals(10, decision.size)
        assertEquals("m71", decision.anchorMessageId)
        assertTrue(decision.reanchored)
    }

    @Test
    fun `a pruned pointer safely falls back to newest ten`() {
        val decision = HistoryWindowAnchor.resolve("long-gone", loaded(200), limit)

        assertEquals(10, decision.size)
        assertEquals("m191", decision.anchorMessageId)
        assertTrue(decision.reanchored)
    }

    @Test
    fun `a widened batch request holds an older pointer that still covers it`() {
        val ids = loaded(100)
        val anchor = ids[14]

        val decision = HistoryWindowAnchor.resolve(anchor, ids, requested = 12)

        assertEquals(15, decision.size)
        assertEquals(anchor, decision.anchorMessageId)
        assertFalse(decision.reanchored)
    }

    @Test
    fun `an empty or disabled window has no pointer`() {
        assertEquals(0, HistoryWindowAnchor.resolve(null, emptyList(), limit).size)
        assertEquals(0, HistoryWindowAnchor.resolve(null, loaded(50), 0).size)
    }
}
