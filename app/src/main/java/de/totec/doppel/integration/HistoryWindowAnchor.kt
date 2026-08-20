package de.totec.doppel.integration

/**
 * Decides where a chat's rendered history window starts.
 *
 * A plain "newest N messages" window slides on every turn and destroys the provider's exact-prefix
 * cache. Instead, the store pins the oldest retained message. New chat messages only extend the
 * newest end. The store releases the pointer after a successful chat-memory revision, at which
 * point the verbatim overlap resets to the newest configured number of messages.
 */
internal object HistoryWindowAnchor {
    /**
     * @param anchorMessageId the currently pinned oldest message, if the window already has one.
     * @param newestFirstIds loaded candidates, newest first, through the held anchor.
     * @param requested lower bound requested by the caller (configured overlap plus current batch).
     */
    fun resolve(
        anchorMessageId: String?,
        newestFirstIds: List<String>,
        requested: Int,
    ): Decision {
        if (requested <= 0 || newestFirstIds.isEmpty()) {
            return Decision(size = 0, anchorMessageId = null, reanchored = false)
        }
        if (newestFirstIds.size < requested) {
            return Decision(
                size = newestFirstIds.size,
                anchorMessageId = null,
                reanchored = false,
            )
        }
        val held = anchorMessageId?.let(newestFirstIds::indexOf) ?: -1
        if (held >= requested - 1) {
            return Decision(
                size = held + 1,
                anchorMessageId = anchorMessageId,
                reanchored = false,
            )
        }
        return Decision(
            size = requested,
            anchorMessageId = newestFirstIds[requested - 1],
            reanchored = true,
        )
    }

    data class Decision(
        val size: Int,
        val anchorMessageId: String?,
        val reanchored: Boolean,
    )
}
