package de.totec.doppel.integration

import de.totec.doppel.data.db.BotDatabaseLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionFloorTest {
    @Test
    fun `a missing marker leaves the ordinary window floor standing`() {
        assertEquals(160, RetentionFloor.resolve(backlog = null, retainedOverlap = 10, windowFloor = 160))
    }

    @Test
    fun `a backlog inside the window does not raise the floor above it`() {
        // 40 unconsolidated plus the overlap is still well inside the complete window, so the
        // window floor is what retention has to respect — the caller takes the larger of the two.
        val floor = RetentionFloor.resolve(backlog = 40, retainedOverlap = 10, windowFloor = 160)
        assertTrue("expected $floor to stay under the window floor", floor < 160)
    }

    @Test
    fun `a backlog past the window keeps every unconsolidated message plus the overlap`() {
        // This is the case the floor exists for: memory writes have been failing for 400 messages,
        // and retention set to 200 would otherwise delete the oldest 210 before the fold saw them.
        assertEquals(410, RetentionFloor.resolve(backlog = 400, retainedOverlap = 10, windowFloor = 160))
    }

    @Test
    fun `a saturated count is treated as unknown and keeps everything`() {
        // At the query bound the number is no longer a count but "at least this many", so trusting
        // it would prune exactly the messages it failed to see.
        assertEquals(
            BotDatabaseLimits.MAX_MESSAGES_PER_CHAT,
            RetentionFloor.resolve(
                backlog = BotDatabaseLimits.MAX_QUERY_LIMIT,
                retainedOverlap = 10,
                windowFloor = 160,
            ),
        )
    }

    @Test
    fun `an overlap of zero still protects the whole backlog`() {
        // Zero overlap is the explicit "keep everything stored" choice and must not read as a
        // negative adjustment on the backlog.
        assertEquals(400, RetentionFloor.resolve(backlog = 400, retainedOverlap = 0, windowFloor = 160))
    }
}
