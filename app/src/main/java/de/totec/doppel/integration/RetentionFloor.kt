package de.totec.doppel.integration

import de.totec.doppel.data.db.BotDatabaseLimits

/**
 * Decides how many of the newest messages retention is not allowed to delete.
 *
 * Retention is a storage budget and keeps the *newest* rows. The memory fold consumes the *oldest*
 * unconsolidated ones first. Those are opposite ends, and they only stayed compatible while the
 * rendered window could never outgrow "retained overlap + refresh interval": a memory write that
 * keeps failing holds its marker still and lets the backlog grow past that, and pruning would then
 * delete messages that were never summarized — gone from the window and gone from memory, silently
 * and for good. So the backlog is a floor in its own right.
 */
internal object RetentionFloor {
    /**
     * @param backlog unconsolidated messages behind the durable marker, or `null` when the marker
     *   row itself is already gone — the window re-anchors on its own and nothing is left to save.
     * @param retainedOverlap the verbatim overlap a successful write leaves behind, kept on top of
     *   the backlog so the next window still opens with its configured context in front of it.
     * @param windowFloor the ordinary "complete window" floor, which applies when there is no
     *   backlog worth protecting.
     */
    fun resolve(
        backlog: Int?,
        retainedOverlap: Int,
        windowFloor: Int,
    ): Int =
        when {
            backlog == null -> windowFloor
            // Counted under a query bound: at the bound the number has stopped being a count and
            // become "at least this many", so the only honest answer is the per-chat ceiling —
            // which is also where MemoryRefreshService gives up looking for the marker.
            backlog >= BotDatabaseLimits.MAX_QUERY_LIMIT -> BotDatabaseLimits.MAX_MESSAGES_PER_CHAT
            else -> backlog + retainedOverlap.coerceAtLeast(0)
        }
}
