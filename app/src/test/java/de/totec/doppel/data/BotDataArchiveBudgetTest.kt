package de.totec.doppel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The per-entry cap these limits sit next to is the one that looks sufficient and is not: an archive
 * made of many entries, each individually plausible, is the shape that gets past it. What is checked
 * here is that the budget is spent across the whole extraction rather than reset per entry.
 */
class BotDataArchiveBudgetTest {
    @Test
    fun `spends bytes across entries rather than resetting per entry`() {
        val budget = BotDataArchive.ExtractionBudget()
        val half = BotDataArchive.MAX_TOTAL_BYTES / 2

        budget.consumeBytes(half)
        budget.consumeBytes(half)

        assertEquals(0L, budget.remainingBytes)
        assertThrows(BotDataArchive.UnreadableArchiveException::class.java) {
            budget.consumeBytes(1L)
        }
    }

    @Test
    fun `allows an archive that exactly meets the byte limit`() {
        val budget = BotDataArchive.ExtractionBudget()

        budget.consumeBytes(BotDataArchive.MAX_TOTAL_BYTES)

        assertEquals(0L, budget.remainingBytes)
    }

    @Test
    fun `refuses more entries than the limit and allows exactly the limit`() {
        val budget = BotDataArchive.ExtractionBudget()

        repeat(BotDataArchive.MAX_ENTRIES) { budget.consumeEntry() }

        assertEquals(0, budget.remainingEntries)
        assertThrows(BotDataArchive.UnreadableArchiveException::class.java) {
            budget.consumeEntry()
        }
    }

    @Test
    fun `refuses a single entry that exceeds the whole budget on its own`() {
        val budget = BotDataArchive.ExtractionBudget()

        assertThrows(BotDataArchive.UnreadableArchiveException::class.java) {
            budget.consumeBytes(BotDataArchive.MAX_TOTAL_BYTES + 1L)
        }
    }
}
