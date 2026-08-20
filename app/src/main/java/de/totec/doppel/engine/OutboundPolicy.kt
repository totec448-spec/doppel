package de.totec.doppel.engine

data class OutboundFacts(
    val hardLockReason: String? = null,
    val reviewRequiredReason: String? = null,
    val softHoldUntilMs: Long? = null,
    val sentLastHour: Int = 0,
    val sentLastDay: Int = 0,
    val proactiveSentToday: Int = 0,
    val inboundToday: Int = 0,
    val samePayloadSentRecently: Boolean = false,
    val lastGlobalSendAtMs: Long? = null,
    val coldContactsLastThirtyDays: Int = 0,
    val isColdContact: Boolean = false,
    /**
     * Commit times of every send inside the 24h window, ascending.
     *
     * Both velocity budgets are rolling windows, so the moment a slot frees up
     * is a fact about these timestamps, not a fixed cooldown. Without them the
     * limits could only guess, and a guess that lands too early simply re-defers
     * — which is how a reply ends up silently held for the rest of the day.
     */
    val recentSendTimestampsMs: List<Long> = emptyList(),
)

data class OutboundPolicySettings(
    val maxPerHour: Int = 25,
    val maxPerDay: Int = 120,
    val proactiveDailyCap: Int = 2,
    val proactiveReplyRatioMax: Double = 0.1,
    val coldMonthlyCap: Int = 30,
    val minimumGlobalGapMs: Long = 1_200,
)

/**
 * Pure pre-generation/final-commit policy. The repository must load facts and
 * reserve the allowed intent in one transaction to avoid a time-of-check /
 * time-of-send race.
 */
object OutboundPolicy {
    fun evaluate(
        intent: OutboundIntent,
        facts: OutboundFacts,
        settings: OutboundPolicySettings,
    ): OutboundDecision {
        facts.hardLockReason?.let { return OutboundDecision.Blocked(it, hard = true) }
        // A review lock says "a human should look at this". Blocking the admin too meant the one
        // person who could clear it was the only one guaranteed not to hear about it, and asking
        // the bot a question was answered with silence. Everyone else still stops.
        if (!intent.admin) {
            facts.reviewRequiredReason?.let { return OutboundDecision.Blocked(it, hard = true) }
        }
        if (facts.samePayloadSentRecently) {
            return OutboundDecision.Blocked("Duplicate message prevented", hard = false)
        }
        facts.softHoldUntilMs?.takeIf { it > intent.timestampMs }?.let {
            return OutboundDecision.Deferred(it, "Safety pause")
        }

        // Admin replies bypass velocity budgets and review locks. A hard lock —
        // WhatsApp itself refusing the account — and exact dedupe still apply to
        // everyone, because sending into those is what earns the next strike.
        if (!intent.admin) {
            if (facts.sentLastHour >= settings.maxPerHour) {
                return OutboundDecision.Deferred(
                    windowFreesAtMs(facts, intent.timestampMs, ONE_HOUR_MS, settings.maxPerHour)
                        ?: (intent.timestampMs + 10 * 60_000),
                    "Hourly send limit",
                )
            }
            if (facts.sentLastDay >= settings.maxPerDay) {
                return OutboundDecision.Deferred(
                    windowFreesAtMs(facts, intent.timestampMs, ONE_DAY_MS, settings.maxPerDay)
                        ?: (intent.timestampMs + 60 * 60_000),
                    "Daily send limit",
                )
            }
            if (intent.proactive) {
                if (
                    settings.proactiveDailyCap > 0 &&
                    facts.proactiveSentToday >= settings.proactiveDailyCap
                ) {
                    return OutboundDecision.Blocked("Proactive daily limit", hard = false)
                }
                val ratioLimit =
                    (facts.inboundToday * settings.proactiveReplyRatioMax)
                        .toInt()
                        .coerceAtLeast(if (facts.inboundToday > 0) 1 else 0)
                if (facts.proactiveSentToday >= ratioLimit) {
                    return OutboundDecision.Blocked("Proactive-to-reply ratio", hard = false)
                }
                if (
                    facts.isColdContact &&
                    facts.coldContactsLastThirtyDays >= settings.coldMonthlyCap
                ) {
                    return OutboundDecision.Blocked("Cold contact limit", hard = false)
                }
            }
        }

        facts.lastGlobalSendAtMs?.let { last ->
            val earliest = last + settings.minimumGlobalGapMs
            if (earliest > intent.timestampMs) {
                return OutboundDecision.Deferred(earliest, "Minimum send gap")
            }
        }
        return OutboundDecision.Allowed(intent.reservationId)
    }

    const val ONE_HOUR_MS = 60L * 60_000L
    const val ONE_DAY_MS = 24L * ONE_HOUR_MS

    /**
     * When the oldest send still occupying a slot drops out of [windowMs].
     *
     * Returns null when the timestamps cannot explain the count — an older
     * build's facts, or a window trimmed elsewhere — so the caller keeps its
     * fixed fallback rather than retrying in a tight loop.
     */
    private fun windowFreesAtMs(
        facts: OutboundFacts,
        nowMs: Long,
        windowMs: Long,
        limit: Int,
    ): Long? {
        if (limit <= 0) return null
        val inWindow = facts.recentSendTimestampsMs.filter { it > nowMs - windowMs }.sorted()
        if (inWindow.size < limit) return null
        // One slot is enough: the send that has to expire is the one sitting at
        // the limit boundary counted from the oldest end.
        val blocking = inWindow[inWindow.size - limit]
        return (blocking + windowMs + 1L).coerceAtLeast(nowMs + 1L)
    }
}
