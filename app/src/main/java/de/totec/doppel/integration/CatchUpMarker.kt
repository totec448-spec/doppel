package de.totec.doppel.integration

/**
 * Marks an inbound message that is being answered long after it arrived.
 *
 * After a reconnect the engine replays every unanswered inbound message, and those replays used to
 * reach the model indistinguishable from a message that had just come in — so it answered a
 * six-hour-old "bist du da?" as if it were live, and reached for WhatsApp-style timestamp headers to
 * carry the age it had no other way to express. Naming the delay explicitly gives it that way, and
 * separates a caught-up reply from a new one for the reader too.
 *
 * The age is derived from the message timestamp alone, so no flag has to be threaded through the
 * engine and a deferred re-engagement is described just as accurately as a reconnect replay.
 */
internal object CatchUpMarker {
    /**
     * Ordinary pickup delay is not lateness. The online model can hold a reply for up to a quarter
     * of an hour on purpose, so the threshold sits well clear of it: below this, nothing is marked.
     */
    const val THRESHOLD_MS = 30L * 60_000L

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS

    fun forMessage(
        timestampMs: Long,
        nowMs: Long,
    ): String? {
        if (timestampMs <= 0L) return null
        val age = nowMs - timestampMs
        // A clock skew that puts the message in the future is not lateness either.
        if (age < THRESHOLD_MS) return null
        return "[Delivered late · ${describeAge(age)}]"
    }

    private fun describeAge(ageMs: Long): String =
        when {
            ageMs < 90L * MINUTE_MS -> "${ageMs / MINUTE_MS} minutes ago"
            ageMs < 2L * DAY_MS -> "${ageMs / HOUR_MS} hours ago"
            else -> "${ageMs / DAY_MS} days ago"
        }
}
