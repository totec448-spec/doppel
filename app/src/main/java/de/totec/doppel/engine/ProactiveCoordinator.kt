package de.totec.doppel.engine

import de.totec.doppel.domain.IncomingEvent
import java.io.Closeable
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue

/**
 * Minimal persisted view required by the proactive planner. The database owns
 * delivery counters and lease details; the engine owns only deterministic
 * scheduling decisions.
 */
data class ProactiveStateSnapshot(
    val chatJid: String,
    val enabled: Boolean,
    val nextDueAtMs: Long?,
    val cooldownUntilMs: Long?,
    val lastInboundAtMs: Long?,
    val lastOutboundAtMs: Long?,
    val dailyWindowStartedAtMs: Long?,
    val dailyOutboundCount: Int,
    val consecutiveFailures: Int,
    val leaseOwner: String?,
    val leaseUntilMs: Long?,
    val deferredAtMs: Long?,

    /**
     * Set when the user raised the per-contact level for a chat that never wrote in. It replaces
     * [lastInboundAtMs] as the silence anchor so a stranger can be addressed once, and stays
     * distinct from it so the cold-contact safety caps keep seeing a cold contact.
     */
    val coldOutreachAtMs: Long?,
    val updatedAtMs: Long,
    /** Ordinary personality-driven due time; stored separately from an explicit promise. */
    val baseNextDueAtMs: Long? = nextDueAtMs,
    val scheduledFollowUp: ScheduledFollowUp? = null,
) {
    /** Whatever the scheduler measures silence from; null means the chat must not be scheduled. */
    val anchorMs: Long? get() = lastInboundAtMs ?: coldOutreachAtMs

    /**
     * The moment that last granted permission for *one* proactive message: the silence anchor, or
     * the deliberate leave-on-read if that is newer.
     *
     * A deferral is an owed continuation, so it re-opens the door — but it opens it exactly once,
     * the same way an inbound message does. Comparing [lastOutboundAtMs] against this instead of
     * merely asking whether a deferral exists is what keeps the one-nudge-per-silence rule and the
     * daily cap in force after that continuation has been delivered: nothing clears `deferredAtMs`
     * on send, so a chat that stays deferred would otherwise be permanently exempt from both.
     */
    val nudgeAnchorMs: Long? get() = anchorMs?.let { maxOf(it, deferredAtMs ?: it) }

    /** True once a proactive message has gone out since [nudgeAnchorMs] re-opened the door. */
    val nudgeAlreadySpent: Boolean
        get() {
            val anchor = nudgeAnchorMs ?: return true
            return lastOutboundAtMs != null && lastOutboundAtMs >= anchor
        }
}

data class ScheduledFollowUp(
    val id: String,
    val conversationKey: String,
    val personaKey: String,
    val scheduledAtMs: Long,
    val nextAttemptAtMs: Long,
    val note: String,
    val createdAtMs: Long,
)

data class ScheduledFollowUpRequest(
    val id: String,
    val chatJid: String,
    val conversationKey: String,
    val personaKey: String,
    val scheduledAtMs: Long,
    val note: String,
    val createdAtMs: Long,
)

data class ProactiveSeed(
    /**
     * A real, already-persisted inbound event. It is never persisted again. For a cold chat this
     * is a synthetic stand-in carrying nothing but the addressing fields.
     */
    val lastInbound: IncomingEvent,
)

data class ProactiveTurnRequest(
    val reason: String,
    /** One-off instruction after mood/time; preserves the complete anchored chat prefix. */
    val trailingDirective: Boolean = false,
    /** Persona that owns this proactive decision; frozen into the synthetic turn. */
    val personaKey: String? = null,
    /** Exact durable promise, allowed to collect already-buffered inbound work before it runs. */
    val scheduledFollowUp: Boolean = false,
)

data class ProactiveSchedule(
    val chatJid: String,
    val enabled: Boolean,
    val nextDueAtMs: Long?,
    val deferredAtMs: Long?,
    val updatedAtMs: Long,
)

/**
 * Persistence contract kept separate from [EngineStore]: ordinary reply tests
 * and alternative stores do not have to pretend that proactive leases exist.
 */
interface ProactivePersistence : DueWorkStore {
    suspend fun listStates(limit: Int = MAX_PROACTIVE_STATES): List<ProactiveStateSnapshot>

    suspend fun getState(chatJid: String): ProactiveStateSnapshot?

    suspend fun saveSchedule(schedule: ProactiveSchedule)

    suspend fun loadSeed(chatJid: String): ProactiveSeed?

    /** Persisted account-wide cadence shared by every proactive chat. */
    suspend fun setGlobalNotBefore(timestampMs: Long)

    suspend fun scheduleFollowUp(request: ScheduledFollowUpRequest) {
        error("Scheduled follow-ups are not supported by this store")
    }

    suspend fun listScheduledFollowUps(
        personaKey: String,
        limit: Int = 50,
    ): List<Pair<ProactiveStateSnapshot, ScheduledFollowUp>> = emptyList()

    companion object {
        const val MAX_PROACTIVE_STATES = 1_000
    }
}

sealed interface ProactiveTurnOutcome {
    data object Sent : ProactiveTurnOutcome

    data object Silent : ProactiveTurnOutcome

    data class Deferred(val untilMs: Long, val reason: String? = null) : ProactiveTurnOutcome

    data class Blocked(
        val hard: Boolean,
        val retryAtMs: Long? = null,
        val reason: String? = null,
    ) : ProactiveTurnOutcome

    data object Cancelled : ProactiveTurnOutcome
}

/**
 * Event-driven proactive re-engagement.
 *
 * There is exactly one [DueWorkScheduler] timer for all chats. Incoming events
 * and settings changes recompute persisted deadlines and explicitly re-arm that
 * timer; idle runtime performs no scan. A due chat is generated and sent by the
 * same globally serialized BotEngine worker used for reactive replies.
 */
class ProactiveCoordinator(
    parentScope: kotlinx.coroutines.CoroutineScope,
    private val persistence: ProactivePersistence,
    private val settingsProvider: EngineSettingsProvider,
    private val canContact: suspend (ProactiveSeed, EngineSettingsSnapshot) -> Boolean,
    private val execute: suspend (ProactiveSeed, Int, ProactiveTurnRequest) -> ProactiveTurnOutcome,
    private val wakeForScheduled: () -> Unit = {},
    private val publishScheduledWake: (Long?) -> Unit = {},
    private val inOnlineSession: (Long) -> Boolean = { true },
    private val nextOnlineSessionAt: () -> Long? = { null },
    private val clock: EngineClock = SystemEngineClock,
) : Closeable {
    private val started = AtomicBoolean(false)
    private val scheduler =
        DueWorkScheduler(
            parentScope = parentScope,
            store = persistence,
            runner = DueWorkRunner(::runDue),
            clock = clock,
        )

    suspend fun start() {
        if (!started.compareAndSet(false, true)) return
        reconcileAll()
        publishEarliestScheduledWake()
        scheduler.reschedule()
    }

    /**
     * Called from the SettingsRepository StateFlow. It performs work only when
     * a real settings revision changes and never creates a periodic wakeup.
     */
    suspend fun refreshSchedules() {
        if (!started.get()) return
        reconcileAll()
        publishEarliestScheduledWake()
        scheduler.reschedule()
    }

    suspend fun onInbound(
        event: IncomingEvent,
        settings: EngineSettingsSnapshot,
    ) {
        if (event.fromMe) return
        val now = clock.wallTimeMillis()
        if (event.isGroup || !settings.enabled || settings.chatPaused || settings.proactiveLevel <= 0) {
            persistence.saveSchedule(
                ProactiveSchedule(
                    chatJid = event.chatJid,
                    enabled = false,
                    nextDueAtMs = null,
                    deferredAtMs = null,
                    updatedAtMs = now,
                ),
            )
        } else {
            val level = effectiveLevel(event.chatJid, settings, now)
            persistence.saveSchedule(
                ProactiveSchedule(
                    chatJid = event.chatJid,
                    enabled = true,
                    nextDueAtMs = safeAdd(now, minimumIdleMs(level)),
                    deferredAtMs = null,
                    updatedAtMs = now,
                ),
            )
        }
        if (started.get()) scheduler.reschedule()
    }

    /**
     * A deliberate leave-on-read is an owed continuation, not cold outreach.
     * It is therefore revisited earlier, but still only for an existing 1:1
     * inbound conversation.
     */
    suspend fun onDeferred(
        event: IncomingEvent,
        settings: EngineSettingsSnapshot,
    ) {
        if (event.isGroup || settings.proactiveLevel <= 0 || !settings.enabled || settings.chatPaused) return
        val now = clock.wallTimeMillis()
        val level = effectiveLevel(event.chatJid, settings, now)
        persistence.saveSchedule(
            ProactiveSchedule(
                chatJid = event.chatJid,
                enabled = true,
                nextDueAtMs = safeAdd(now, minOf(DEFERRED_REENGAGE_MS, minimumIdleMs(level))),
                deferredAtMs = now,
                updatedAtMs = now,
            ),
        )
        if (started.get()) scheduler.reschedule()
    }

    /**
     * Once the bot has answered, it must wait for a new inbound message before
     * initiating again. This is the one-nudge-per-silence invariant.
     */
    suspend fun onReactiveReply(
        chatJid: String,
        settings: EngineSettingsSnapshot,
    ) {
        val now = clock.wallTimeMillis()
        persistence.saveSchedule(
            ProactiveSchedule(
                chatJid = chatJid,
                enabled = settings.enabled && !settings.chatPaused && settings.proactiveLevel > 0,
                nextDueAtMs = null,
                deferredAtMs = null,
                updatedAtMs = now,
            ),
        )
        if (started.get()) scheduler.reschedule()
    }

    /** Called only after a durable tool commit; one existing timer is re-armed, never polled. */
    suspend fun onScheduledFollowUpChanged() {
        if (started.get()) {
            publishEarliestScheduledWake()
            scheduler.reschedule()
        }
    }

    /** Normal proactive candidates are only evaluated while the persona is already online. */
    fun onOnlineSessionStarted() {
        if (started.get()) scheduler.reschedule()
    }

    override fun close() {
        started.set(false)
        scheduler.close()
    }

    /**
     * Recomputes every chat's next outreach time after a settings change.
     *
     * Deliberately does *not* load each chat's seed. Building a seed scans that conversation's
     * message history, and doing it for every known chat turned one settings revision into up to a
     * thousand history scans — on a phone, on the settings flow, which fires far more often than
     * the schedule actually changes. The seed decides whether outreach may happen at all, and
     * [runDueWork] already re-checks it at the only moment that matters: just before sending, where
     * a missing or group seed still disables the schedule without a message going out. Reconciling
     * on the cheap state row alone therefore changes when the check runs, not what it decides.
     */
    private suspend fun reconcileAll() {
        val now = clock.wallTimeMillis()
        persistence.listStates().forEach { state ->
            val settings = settingsProvider.resolve(state.chatJid)
            val anchor = state.anchorMs
            val shouldDisable =
                anchor == null ||
                    !settings.enabled ||
                    settings.chatPaused ||
                    settings.proactiveLevel <= 0
            if (shouldDisable) {
                saveIfChanged(
                    state,
                    enabled = false,
                    nextDueAtMs = null,
                    deferredAtMs = null,
                    now = now,
                )
                return@forEach
            }

            val waitingForReply = state.nudgeAlreadySpent
            val level = effectiveLevel(state.chatJid, settings, now)
            val idleMs =
                if (state.lastInboundAtMs == null) {
                    firstContactIdleMs(level)
                } else {
                    minimumIdleMs(level)
                }
            val due =
                when {
                    waitingForReply -> null
                    state.deferredAtMs != null ->
                        safeAdd(state.deferredAtMs, minOf(DEFERRED_REENGAGE_MS, idleMs))
                    else -> safeAdd(anchor, idleMs)
                }
            saveIfChanged(
                state,
                enabled = true,
                nextDueAtMs = due,
                deferredAtMs = state.deferredAtMs,
                now = now,
            )
        }
    }

    private suspend fun saveIfChanged(
        state: ProactiveStateSnapshot,
        enabled: Boolean,
        nextDueAtMs: Long?,
        deferredAtMs: Long?,
        now: Long,
    ) {
        if (
            state.enabled == enabled &&
            state.nextDueAtMs == nextDueAtMs &&
            state.deferredAtMs == deferredAtMs
        ) {
            return
        }
        persistence.saveSchedule(
            ProactiveSchedule(
                chatJid = state.chatJid,
                enabled = enabled,
                nextDueAtMs = nextDueAtMs,
                deferredAtMs = deferredAtMs,
                updatedAtMs = now,
            ),
        )
    }

    private suspend fun runDue(work: DueWork): DueWorkResult {
        check(work.kind == DueWorkKind.PROACTIVE)
        val now = clock.wallTimeMillis()
        val state =
            persistence.getState(work.id)
                ?: return DueWorkResult(success = true, nextDueAtMs = null, disable = true)
        val seed =
            persistence.loadSeed(work.id)
                ?: return DueWorkResult(success = true, nextDueAtMs = null, disable = true)
        val settings = settingsProvider.resolve(work.id)
        val scheduled = state.scheduledFollowUp?.takeIf { it.nextAttemptAtMs <= now }
        if (scheduled != null) {
            return runScheduledFollowUp(state, scheduled, seed, settings, now)
        }
        val anchor = state.anchorMs
        if (!inOnlineSession(now)) {
            val nextSession = nextOnlineSessionAt()?.takeIf { it > now }
            return DueWorkResult(
                success = true,
                nextDueAtMs = nextSession ?: safeAdd(now, SESSION_ALIGNMENT_RECHECK_MS),
            )
        }
        if (
            seed.lastInbound.isGroup ||
            anchor == null ||
            !settings.enabled ||
            settings.chatPaused ||
            settings.proactiveLevel <= 0 ||
            !canContact(seed, settings)
        ) {
            return DueWorkResult(success = true, nextDueAtMs = null, disable = true)
        }

        // No second unanswered nudge. A real inbound write clears this relation
        // and explicitly re-arms the scheduler. A cold contact who never answers
        // the opener therefore hears from the bot exactly once — and so does a
        // contact whose owed continuation has already been delivered.
        if (state.nudgeAlreadySpent) {
            return DueWorkResult(success = true, nextDueAtMs = null)
        }

        val level = effectiveLevel(work.id, settings, now)
        val idleMsForLevel =
            if (state.lastInboundAtMs == null) firstContactIdleMs(level) else minimumIdleMs(level)
        val earliestByIdle =
            if (state.deferredAtMs != null) {
                safeAdd(
                    state.deferredAtMs,
                    minOf(DEFERRED_REENGAGE_MS, idleMsForLevel),
                )
            } else {
                safeAdd(anchor, idleMsForLevel)
            }
        if (earliestByIdle > now) {
            return DueWorkResult(success = true, nextDueAtMs = earliestByIdle)
        }

        nextAwakeAt(now, settings)?.let {
            return DueWorkResult(success = true, nextDueAtMs = it)
        }

        val cap = maximumPerDay(level)
        val windowStart = state.dailyWindowStartedAtMs
        // The cap counts every proactive message, including owed continuations. Exempting
        // deferred chats made the cap unreachable for exactly the chats that send the most.
        if (
            windowStart != null &&
            now - windowStart < ONE_DAY_MS &&
            state.dailyOutboundCount >= cap
        ) {
            return DueWorkResult(
                success = true,
                nextDueAtMs = safeAdd(windowStart, ONE_DAY_MS),
            )
        }

        val reason =
            if (state.lastInboundAtMs == null) {
                FIRST_CONTACT_REASON
            } else {
                idleReason(
                    (now - state.lastInboundAtMs).coerceAtLeast(0L),
                    state.deferredAtMs != null,
                )
            }
        return when (
            val outcome = execute(
                seed,
                level,
                ProactiveTurnRequest(reason = reason, personaKey = settings.personality),
            )
        ) {
            ProactiveTurnOutcome.Sent -> {
                persistence.setGlobalNotBefore(
                    safeAdd(now, globalAttemptGapMs(work.id, now, active = true)),
                )
                DueWorkResult(
                    success = true,
                    nextDueAtMs = null,
                    outboundSent = true,
                )
            }

            ProactiveTurnOutcome.Silent -> {
                persistence.setGlobalNotBefore(
                    safeAdd(now, globalAttemptGapMs(work.id, now, active = false)),
                )
                DueWorkResult(
                    success = true,
                    nextDueAtMs = safeAdd(now, nextSessionGapMs(work.id, level, now)),
                )
            }

            is ProactiveTurnOutcome.Deferred -> {
                persistence.setGlobalNotBefore(outcome.untilMs.coerceAtLeast(now + 1L))
                DueWorkResult(
                    success = true,
                    nextDueAtMs = outcome.untilMs.coerceAtLeast(now + 1L),
                )
            }

            is ProactiveTurnOutcome.Blocked -> {
                val retryAt =
                    outcome.retryAtMs?.coerceAtLeast(now + 1L)
                        ?: safeAdd(now, if (outcome.hard) HARD_BLOCK_RECHECK_MS else SOFT_BLOCK_RECHECK_MS)
                persistence.setGlobalNotBefore(retryAt)
                DueWorkResult(
                    success = true,
                    nextDueAtMs = retryAt,
                )
            }

            ProactiveTurnOutcome.Cancelled ->
                DueWorkResult(
                    success = true,
                    // A concurrent inbound/settings write is authoritative. The
                    // repository's optimistic finish preserves that new value.
                    nextDueAtMs = null,
                )
        }
    }

    private suspend fun runScheduledFollowUp(
        state: ProactiveStateSnapshot,
        followUp: ScheduledFollowUp,
        seed: ProactiveSeed,
        settings: EngineSettingsSnapshot,
        now: Long,
    ): DueWorkResult {
        fun retry(at: Long) = DueWorkResult(
            success = true,
            nextDueAtMs = state.baseNextDueAtMs,
            retryFollowUpAtMs = at.coerceAtLeast(now + 1L),
            retryFollowUpId = followUp.id,
        )
        if (
            seed.lastInbound.isGroup ||
            followUp.conversationKey.substringBeforeLast('#') != state.chatJid
        ) {
            publishEarliestScheduledWake(excludingId = followUp.id)
            return DueWorkResult(
                success = true,
                nextDueAtMs = state.baseNextDueAtMs,
                completedFollowUpId = followUp.id,
            )
        }
        if (!canContact(seed, settings)) {
            publishEarliestScheduledWake(excludingId = followUp.id)
            return DueWorkResult(
                success = true,
                nextDueAtMs = state.baseNextDueAtMs,
                completedFollowUpId = followUp.id,
            )
        }
        if (!settings.enabled || settings.chatPaused) {
            val retryAt = safeAdd(now, SOFT_BLOCK_RECHECK_MS)
            publishEarliestScheduledWake(overrideId = followUp.id, overrideAtMs = retryAt)
            return retry(retryAt)
        }

        wakeForScheduled()
        val reason =
            "You set a timer to write this person right now about the following: ${followUp.note}. " +
                "The plan is already visible in the chat log. Make a strong, genuine effort to " +
                "fulfil it naturally now with a " +
                "visible message. [no reply] remains available only if writing would truly be " +
                "incoherent or unsafe; do not schedule the same plan again."
        return when (
            val outcome = execute(
                seed,
                maxOf(1, settings.proactiveLevel),
                ProactiveTurnRequest(
                    reason = reason,
                    trailingDirective = true,
                    personaKey = followUp.personaKey,
                    scheduledFollowUp = true,
                ),
            )
        ) {
            ProactiveTurnOutcome.Sent,
            ProactiveTurnOutcome.Silent,
            -> {
                publishEarliestScheduledWake(excludingId = followUp.id)
                DueWorkResult(
                    success = true,
                    nextDueAtMs = state.baseNextDueAtMs,
                    outboundSent = outcome is ProactiveTurnOutcome.Sent,
                    completedFollowUpId = followUp.id,
                )
            }
            is ProactiveTurnOutcome.Deferred -> {
                publishEarliestScheduledWake(overrideId = followUp.id, overrideAtMs = outcome.untilMs)
                retry(outcome.untilMs)
            }
            is ProactiveTurnOutcome.Blocked -> {
                val retryAt = outcome.retryAtMs
                    ?: safeAdd(now, if (outcome.hard) HARD_BLOCK_RECHECK_MS else SOFT_BLOCK_RECHECK_MS)
                publishEarliestScheduledWake(overrideId = followUp.id, overrideAtMs = retryAt)
                retry(retryAt)
            }
            ProactiveTurnOutcome.Cancelled -> {
                val retryAt = safeAdd(now, CANCELLED_RETRY_MS)
                publishEarliestScheduledWake(overrideId = followUp.id, overrideAtMs = retryAt)
                retry(retryAt)
            }
        }
    }

    private suspend fun publishEarliestScheduledWake(
        excludingId: String? = null,
        overrideId: String? = null,
        overrideAtMs: Long? = null,
    ) {
        val earliest =
            persistence.listStates()
                .asSequence()
                .mapNotNull(ProactiveStateSnapshot::scheduledFollowUp)
                .filter { it.id != excludingId }
                .map { if (it.id == overrideId && overrideAtMs != null) overrideAtMs else it.nextAttemptAtMs }
                .minOrNull()
        publishScheduledWake(earliest)
    }

    companion object {
        const val LEASE_DURATION_MS = 15L * 60_000L
        private const val ONE_HOUR_MS = 60L * 60_000L
        private const val ONE_DAY_MS = 24L * ONE_HOUR_MS
        private const val DEFERRED_REENGAGE_MS = 25L * 60_000L
        private const val HARD_BLOCK_RECHECK_MS = 6L * ONE_HOUR_MS
        private const val SOFT_BLOCK_RECHECK_MS = ONE_HOUR_MS
        private const val CANCELLED_RETRY_MS = 5L * 60_000L
        private const val SESSION_ALIGNMENT_RECHECK_MS = 5L * 60_000L
        private const val SAME_CHAT_MIN_SPACING_MS = 90L * 60_000L

        /** Shortest first-contact wait after a cold contact was armed. */
        private const val COLD_FIRST_CONTACT_FLOOR_MS = 20L * 60_000L

        /**
         * How long a one-to-one chat has to be quiet before the bot may initiate, indexed by
         * proactivity level. Level 1 is a message every other day, level 10 roughly every two
         * hours; combined with [maximumPerDay] that lands on "one every two days" up to "three a
         * day". Level 0 is infinite and therefore never due.
         */
        private val IDLE_HOURS =
            doubleArrayOf(
                Double.POSITIVE_INFINITY,
                48.0,
                34.0,
                24.0,
                17.0,
                12.0,
                8.0,
                6.0,
                4.0,
                3.0,
                2.0,
            )

        /**
         * Retry attempts per day after the model decided to stay silent. Kept close to
         * [maximumPerDay] so a quiet contact does not cause a stream of billed turns.
         */
        private val SESSIONS_PER_DAY = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 5, 6, 6)
        private val HOT_COLD_OFFSETS = intArrayOf(-2, -1, 0, 1, 2, 1, 0, -1)

        private const val FIRST_CONTACT_REASON =
            "There is no history with this contact yet — this would be the very first message " +
                "in this chat. Decide freely whether a short, natural hello fits; otherwise stay quiet."

        internal fun effectiveLevel(
            chatJid: String,
            settings: EngineSettingsSnapshot,
            nowMs: Long,
        ): Int {
            val base = settings.proactiveLevel.coerceIn(0, 10)
            if (base == 0 || settings.proactiveMode != ProactiveMode.HOT_COLD) return base
            val day = Math.floorDiv(nowMs, ONE_DAY_MS)
            val offset =
                HOT_COLD_OFFSETS[
                    Math.floorMod(day + stableHash(chatJid), HOT_COLD_OFFSETS.size.toLong())
                        .toInt()
                ]
            return (base + offset).coerceIn(1, 10)
        }

        internal fun minimumIdleMs(level: Int): Long {
            val hours = IDLE_HOURS[level.coerceIn(0, 10)]
            return if (!hours.isFinite()) Long.MAX_VALUE else (hours * ONE_HOUR_MS).toLong()
        }

        /**
         * A stranger is approached sooner than a stale conversation is revived: the level still
         * orders the contacts, but sliding somebody up should visibly do something within the
         * same day rather than two days later. The floor keeps it off "instantly on slider
         * release", which would read as a bot and is the riskiest possible pattern.
         */
        internal fun firstContactIdleMs(level: Int): Long {
            val idle = minimumIdleMs(level)
            if (idle == Long.MAX_VALUE) return Long.MAX_VALUE
            return maxOf(COLD_FIRST_CONTACT_FLOOR_MS, idle / 4)
        }

        internal fun maximumPerDay(level: Int): Int =
            when {
                level <= 0 -> 0
                level <= 4 -> 1
                level <= 8 -> 2
                else -> 3
            }

        internal fun failureBackoffMs(consecutiveFailures: Int): Long {
            val shift = consecutiveFailures.coerceIn(0, 6)
            return (5L * 60_000L * (1L shl shift)).coerceAtMost(6L * ONE_HOUR_MS)
        }

        private fun nextSessionGapMs(
            chatJid: String,
            level: Int,
            nowMs: Long,
        ): Long {
            val sessions = SESSIONS_PER_DAY[level.coerceIn(0, 10)]
            if (sessions <= 0) return 12L * ONE_HOUR_MS
            val average = 16.0 * ONE_HOUR_MS / sessions
            val slot = Math.floorDiv(nowMs, ONE_HOUR_MS)
            val fraction =
                (stableHash("$chatJid:$slot").absoluteValue % 10_001L).toDouble() / 10_000.0
            val factor = 0.6 + (0.8 * fraction)
            return maxOf(SAME_CHAT_MIN_SPACING_MS, (average * factor).toLong())
        }

        private fun globalAttemptGapMs(
            chatJid: String,
            nowMs: Long,
            active: Boolean,
        ): Long {
            val minimum = if (active) 3L * 60_000L else 15L * 60_000L
            val maximum = if (active) 12L * 60_000L else 45L * 60_000L
            val slot = Math.floorDiv(nowMs, 60_000L)
            val unsigned = stableHash("global:$chatJid:$slot").ushr(1)
            val fraction = (unsigned % 10_001L).toDouble() / 10_000.0
            return minimum + ((maximum - minimum) * fraction).toLong()
        }

        private fun nextAwakeAt(
            nowMs: Long,
            settings: EngineSettingsSnapshot,
        ): Long? {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), settings.timezone)
            val minute = now.hour * 60 + now.minute
            val start = settings.sleepStartMinutes.coerceIn(0, 1439)
            val end = settings.sleepEndMinutes.coerceIn(0, 1439)
            val sleeping =
                when {
                    start == end -> false
                    start < end -> minute in start until end
                    else -> minute >= start || minute < end
                }
            if (!sleeping) return null
            val endHour = end / 60
            val endMinute = end % 60
            var wake =
                now.withHour(endHour)
                    .withMinute(endMinute)
                    .withSecond(0)
                    .withNano(0)
            if (!wake.isAfter(now)) wake = wake.plusDays(1)
            return wake.toInstant().toEpochMilli()
        }

        private fun idleReason(
            idleMs: Long,
            deferred: Boolean,
        ): String {
            val prefix = if (deferred) "You deliberately put a reply off until later. " else ""
            val hours = idleMs / ONE_HOUR_MS
            return if (hours >= 1) {
                "${prefix}The one-to-one chat has been quiet for about ${hours}h. Decide freely whether a short natural message fits; otherwise stay quiet."
            } else {
                val minutes = (idleMs / 60_000L).coerceAtLeast(1L)
                "${prefix}The one-to-one chat has been quiet for about ${minutes}min. Decide freely whether a short natural message fits; otherwise stay quiet."
            }
        }

        private fun stableHash(value: String): Long {
            var hash = 0xcbf29ce484222325UL
            value.encodeToByteArray().forEach { byte ->
                hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
            }
            return hash.toLong()
        }

        private fun safeAdd(
            left: Long,
            right: Long,
        ): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
