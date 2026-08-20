package de.totec.doppel.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestMessageBudgetTest {
    @Test
    fun `keeps newest events when a batch exceeds the prompt budget`() {
        val result =
            newestMessagesWithinBudget(
                messages = listOf("old".repeat(20), "middle", "newest request"),
                characterLimit = 30,
            )

        assertFalse(result.contains("oldold"))
        assertTrue(result.endsWith("newest request"))
        assertTrue(result.contains("middle"))
    }

    @Test
    fun `preserves chronological order for selected events`() {
        assertEquals(
            "second\n\nthird",
            newestMessagesWithinBudget(listOf("first", "second", "third"), 13),
        )
    }
}
