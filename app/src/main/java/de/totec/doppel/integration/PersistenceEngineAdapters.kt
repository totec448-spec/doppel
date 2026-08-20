package de.totec.doppel.integration

import de.totec.doppel.data.ChatOverrideStore
import de.totec.doppel.data.ChatOverrides
import de.totec.doppel.data.db.AccessEntryRecord
import de.totec.doppel.data.db.AccessListKind
import de.totec.doppel.data.db.AccessSubjectType
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotDatabaseLimits
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.CHAT_INJECTION_MESSAGE_TYPE
import de.totec.doppel.data.db.ChatKind
import de.totec.doppel.data.db.ChatRecord
import de.totec.doppel.data.db.MessageDeliveryState
import de.totec.doppel.data.db.MessageDirection
import de.totec.doppel.data.db.PERSONA_SWITCH_MESSAGE_TYPE
import de.totec.doppel.data.db.SCHEDULED_FOLLOW_UP_MESSAGE_TYPE
import de.totec.doppel.data.db.MessageRecord
import de.totec.doppel.data.db.OUTBOUND_KIND_TURN_PERMIT
import de.totec.doppel.data.db.OutboundDecision as StoredOutboundDecision
import de.totec.doppel.data.db.OutboundSafetyRecord
import de.totec.doppel.data.db.OutboundStatus
import de.totec.doppel.data.db.ProcessedEventRecord
import de.totec.doppel.data.db.ProactiveStateRecord
import de.totec.doppel.domain.ChatEventKind
import de.totec.doppel.domain.IncomingEvent
import de.totec.doppel.domain.MediaHistoryLabels
import de.totec.doppel.domain.MediaKind
import de.totec.doppel.domain.MediaReference
import de.totec.doppel.domain.QuotedMessage
import de.totec.doppel.ai.HistoryLabelGuard
import de.totec.doppel.engine.AccessDecision
import de.totec.doppel.engine.ChatHistoryLabels
import de.totec.doppel.engine.ConversationMemoryPolicy
import de.totec.doppel.engine.EngineSettingsProvider
import de.totec.doppel.engine.EngineSettingsSnapshot
import de.totec.doppel.engine.EngineStore
import de.totec.doppel.engine.InboundRateDecision
import de.totec.doppel.engine.OutboundDecision
import de.totec.doppel.engine.OutboundFacts
import de.totec.doppel.engine.OutboundIntent
import de.totec.doppel.engine.OutboundPolicy
import de.totec.doppel.engine.OutboundPolicySettings
import de.totec.doppel.engine.DueWork
import de.totec.doppel.engine.DueWorkKind
import de.totec.doppel.engine.DueWorkResult
import de.totec.doppel.engine.ProactiveCoordinator
import de.totec.doppel.engine.ProactivePersistence
import de.totec.doppel.engine.PowerMode
import de.totec.doppel.engine.ProactiveMode
import de.totec.doppel.engine.ProactiveSchedule
import de.totec.doppel.engine.ProactiveSeed
import de.totec.doppel.engine.ProactiveStateSnapshot
import de.totec.doppel.engine.ScheduledFollowUp
import de.totec.doppel.engine.ScheduledFollowUpRequest
import de.totec.doppel.engine.ReplyPreset
import de.totec.doppel.engine.StoredTurnMessage
import de.totec.doppel.engine.conversationKey
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.SettingsRepository
import de.totec.doppel.settings.SettingsSnapshot
import de.totec.doppel.app.RuntimeLimitNotice
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolves the small, immutable settings view consumed by [de.totec.doppel.engine.BotEngine].
 *
 * Settings stay authoritative in [SettingsRepository]. A persisted persona assignment has
 * precedence over the typed per-contact override because it represents an explicit chat binding.
 * The adapter owns no scope, observer, timer, or cache and therefore has zero idle work.
 */
class RepositoryEngineSettingsProvider(
    private val settings: SettingsRepository,
    private val repository: BotRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EngineSettingsProvider {
    private val chatOverrides = ChatOverrideStore(repository)

    override suspend fun resolve(chatJid: String): EngineSettingsSnapshot =
        withContext(ioDispatcher) {
            val snapshot = settings.snapshot()
            // A blank chatJid is the documented "no chat scope" call — chat-independent
            // callers such as the self-online session only want the global view. The
            // repository rejects an empty external id, so the assignment lookup has to be
            // skipped rather than issued and caught.
            // Per-contact overrides are keyed by the phone number the user typed, this chat by its
            // LID. Only the aliases the chat recorded connect the two, so they have to be read
            // before either override can be resolved — but only when there is an override at all.
            val overrideAliases =
                chatJid
                    .takeIf {
                        it.isNotBlank() &&
                            (
                                snapshot.proactiveContactOverrides.isNotEmpty() ||
                                    snapshot.personaContactOverrides.isNotEmpty()
                            )
                    }
                    ?.let { storedChatAliases(repository.getChat(it)?.metadataJson) }
                    .orEmpty()
            // What this one conversation decided for itself. Absent keys are not defaults copied
            // from somewhere — they simply are not here, and the global value below applies.
            val overrides = chatOverrides.all(chatJid)
            EngineSettingsSnapshot(
                enabled = snapshot.boolean(BotSettingKeys.ENABLED),
                chatPaused = overrides[ChatOverrides.PAUSED]?.toBooleanStrictOrNull() ?: false,
                    allowAll = snapshot.appBoolean(AppSettingKeys.ALLOW_ALL),
                batchWindowMs = snapshot.integer(BotSettingKeys.BATCH_WINDOW_MS).toLong(),
                replyPreset =
                    when (
                        overrides[ChatOverrides.REPLY_PRESET]
                            ?: snapshot.text(BotSettingKeys.REPLY_PRESET)
                    ) {
                        "instant" -> ReplyPreset.INSTANT
                        else -> ReplyPreset.HUMAN
                    },
                typingWordsPerMinute = snapshot.integer(BotSettingKeys.TYPING_SPEED_WPM),
                readingWordsPerSecond = snapshot.integer(BotSettingKeys.READING_SPEED_WPS),
                typingStopDelayMs = snapshot.integer(BotSettingKeys.TYPING_STOP_DELAY_MS).toLong(),
                sleepStartMinutes =
                    parseClockMinutes(
                        snapshot.text(BotSettingKeys.SLEEP_START),
                        DEFAULT_SLEEP_START_MINUTES,
                    ),
                sleepEndMinutes =
                    parseClockMinutes(
                        snapshot.text(BotSettingKeys.SLEEP_END),
                        DEFAULT_SLEEP_END_MINUTES,
                    ),
                timezone = parseZone(snapshot.text(BotSettingKeys.TIMEZONE)),
                powerMode =
                    when (snapshot.text(BotSettingKeys.POWER_MODE)) {
                        "low" -> PowerMode.LOW
                        else -> PowerMode.DEFAULT
                    },
                lowListenMinutes = snapshot.integer(BotSettingKeys.LOW_LISTEN_MINUTES),
                historyLimit = snapshot.integer(BotSettingKeys.HISTORY_LIMIT),
                memoryIntervalMessages = snapshot.integer(BotSettingKeys.MEMORY_INTERVAL),
                personality =
                    chatOverride(snapshot.personaContactOverrides, chatJid, overrideAliases)
                        ?: snapshot.effectivePersona(chatJid),
                reactions = snapshot.boolean(BotSettingKeys.ENABLE_REACTIONS),
                quoteReplies = snapshot.boolean(BotSettingKeys.ENABLE_QUOTE_REPLY),
                aiDisclosureEnabled = snapshot.boolean(BotSettingKeys.AI_DISCLOSURE_ENABLED),
                aiDisclosureText = snapshot.text(BotSettingKeys.AI_DISCLOSURE_TEXT),
                markRead = snapshot.boolean(BotSettingKeys.MARK_READ),
                selfEditEnabled = snapshot.boolean(BotSettingKeys.SELF_EDIT_ENABLED),
                selfEditChance = snapshot.decimal(BotSettingKeys.SELF_EDIT_CHANCE),
                selfEditDelayDivisor = snapshot.decimal(BotSettingKeys.SELF_EDIT_DELAY_DIVISOR),
                proactiveLevel =
                    chatOverride(snapshot.proactiveContactOverrides, chatJid, overrideAliases)
                        ?: snapshot.effectiveProactiveLevel(chatJid),
                proactiveMode =
                    when (snapshot.text(BotSettingKeys.PROACTIVE_MODE)) {
                        "hot_cold" -> ProactiveMode.HOT_COLD
                        else -> ProactiveMode.FIXED
                    },
                // A per-group trigger is the one override that is meaningful when it is empty:
                // "answer everyone in this group" has to be sayable for one group without
                // switching it on for every group at once.
                groupTrigger =
                    overrides[ChatOverrides.GROUP_TRIGGER]
                        ?: snapshot.appText(AppSettingKeys.GROUP_TRIGGER),
                maxSendsPerHour = snapshot.integer(BotSettingKeys.MAX_SENDS_PER_HOUR),
                maxSendsPerDay = snapshot.integer(BotSettingKeys.MAX_SENDS_PER_DAY),
                autoblockEnabled = snapshot.boolean(BotSettingKeys.AUTOBLOCK_ENABLED),
                autoblockPerMinute = snapshot.integer(BotSettingKeys.AUTOBLOCK_PER_MIN),
                autoblockPerFiveMinutes = snapshot.integer(BotSettingKeys.AUTOBLOCK_PER_5MIN),
                autoblockPerTenMinutes = snapshot.integer(BotSettingKeys.AUTOBLOCK_PER_10MIN),
            )
        }

    private fun parseClockMinutes(value: String, fallback: Int): Int {
        val pieces = value.split(':', limit = 2)
        val hour = pieces.getOrNull(0)?.toIntOrNull() ?: return fallback
        val minute = pieces.getOrNull(1)?.toIntOrNull() ?: return fallback
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else fallback
    }

    private fun parseZone(value: String): ZoneId =
        try {
            ZoneId.of(value)
        } catch (_: DateTimeException) {
            DEFAULT_ZONE
        }

    private companion object {
        const val DEFAULT_SLEEP_START_MINUTES = 30
        const val DEFAULT_SLEEP_END_MINUTES = 8 * 60 + 30
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

/**
 * Durable adapter between the pure engine and the native SQLite repository.
 *
 * Important repository boundaries are handled explicitly instead of pretending stronger
 * guarantees:
 *
 * 1. Inbound event claim and normalized message insert are one SQLite transaction. Optional chat
 *    display metadata is enriched afterwards and cannot invalidate the durable claim.
 * 2. The repository exposes no transaction that combines outbound policy counters and the final
 *    reservation. The engine serializes visible sends, and this adapter reserves immediately after
 *    evaluating facts, but cross-process policy evaluation remains best-effort.
 * 3. A failed/cancelled outbound dedupe row cannot be atomically reopened by the current repository
 *    API. Reusing that reservation ID is blocked rather than reported as a fake successful retry.
 * 4. There is no persisted "inbound turn consumed" relation. Recovery therefore only returns
 *    trailing inbound rows after the last stored outbound row, and suppresses ambiguous chats with
 *    a reserved/sent ledger entry or a durable deferred marker. It is deliberately at-most-once in
 *    ambiguous crash windows to avoid duplicate WhatsApp messages.
 * 5. Model-backed memory consolidation is executed event-driven by the AI adapter and committed
 *    before its action receipt succeeds. [requestMemoryRefresh] remains only as a generic durable
 *    audit hook for alternative runners; it is not a polling-work promise.
 * 6. Repository list APIs cap a page at 1,000 rows and chats are not keyset-pageable by a unique
 *    cursor. Flood/history scans page messages up to the 5,000-row per-chat retention limit;
 *    startup recovery can inspect at most the newest 1,000 chats. The proactive reply ratio uses
 *    retained inbound rows for the target chat because no global inbound-time-range query exists.
 */
class RepositoryEngineStore(
    private val repository: BotRepository,
    private val settings: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    private val activityChanged: () -> Unit = {},
    private val limitChanged: (RuntimeLimitNotice?) -> Unit = {},
) : EngineStore, ProactivePersistence {
    private val proactiveLeaseOwner = "android-${UUID.randomUUID()}"
    private val proactiveClaimVersions = ConcurrentHashMap<String, Long>()
    private val retentionWriteCounts = ConcurrentHashMap<String, Int>()

    /**
     * Oldest message of each conversation's cache-anchored history window plus the durable chat
     * memory revision that owns it. A revision change is the only normal event that releases the
     * pointer. If memory creation fails, the old anchor remains and the prompt keeps growing instead
     * of silently dropping unsummarized messages.
     */
    private val historyAnchorLock = Any()
    private val historyAnchors =
        object : LinkedHashMap<String, HistoryAnchorState>(MAX_TRACKED_HISTORY_ANCHORS, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, HistoryAnchorState>?,
            ): Boolean = size > MAX_TRACKED_HISTORY_ANCHORS
        }

    override suspend fun listStates(limit: Int): List<ProactiveStateSnapshot> =
        withContext(ioDispatcher) {
            repository.listProactiveStates(limit).map { it.toEngineProactiveState() }
        }

    override suspend fun getState(chatJid: String): ProactiveStateSnapshot? =
        withContext(ioDispatcher) {
            repository.getProactiveState(chatJid)?.toEngineProactiveState()
        }

    override suspend fun saveSchedule(schedule: ProactiveSchedule) {
        withContext(ioDispatcher) {
            // Never create a proactive row here. A row exists only after a real inbound write or
            // after the user explicitly armed the contact for cold outreach; both are the
            // consent boundary, and neither is something the scheduler may invent for itself.
            repeat(MAX_PROACTIVE_CAS_ATTEMPTS) {
                val current = repository.getProactiveState(schedule.chatJid) ?: return@withContext
                val metadata = parseProactiveMetadata(current)
                if (
                    repository.updateProactiveSchedule(
                        chatId = schedule.chatJid,
                        enabled = schedule.enabled || metadata.followUp != null,
                        nextDueAt = earliest(schedule.nextDueAtMs, metadata.followUp?.nextAttemptAtMs),
                        stateJson =
                            proactiveMetadata(
                                deferredAtMs = schedule.deferredAtMs,
                                baseNextDueAtMs = schedule.nextDueAtMs,
                                followUp = metadata.followUp,
                            ),
                        requestedUpdatedAt = schedule.updatedAtMs.coerceAtLeast(0L),
                        expectedUpdatedAt = current.updatedAt,
                    )
                ) return@withContext
            }
            error("Proactive schedule changed concurrently")
        }
    }

    override suspend fun scheduleFollowUp(request: ScheduledFollowUpRequest) {
        withContext(ioDispatcher) {
            val followUp =
                ScheduledFollowUp(
                    id = request.id,
                    conversationKey = request.conversationKey,
                    personaKey = request.personaKey,
                    scheduledAtMs = request.scheduledAtMs,
                    nextAttemptAtMs = request.scheduledAtMs,
                    note = request.note.take(ProactiveStateMetadataCodec.MAX_NOTE_CHARS),
                    createdAtMs = request.createdAtMs,
                )
            if (repository.getProactiveState(request.chatJid) == null) {
                repository.putProactiveState(
                    ProactiveStateRecord(chatId = request.chatJid, updatedAt = request.createdAtMs),
                )
            }
            var stored = false
            repeat(MAX_PROACTIVE_CAS_ATTEMPTS) {
                if (!stored) {
                    val current = repository.getProactiveState(request.chatJid) ?: return@repeat
                    val metadata = parseProactiveMetadata(current)
                    stored =
                        repository.updateProactiveSchedule(
                            chatId = request.chatJid,
                            enabled = true,
                            nextDueAt = earliest(metadata.baseNextDueAtMs, followUp.nextAttemptAtMs),
                            stateJson =
                                proactiveMetadata(
                                    deferredAtMs = metadata.deferredAtMs,
                                    baseNextDueAtMs = metadata.baseNextDueAtMs,
                                    followUp = followUp,
                                ),
                            requestedUpdatedAt = request.createdAtMs,
                            expectedUpdatedAt = current.updatedAt,
                        )
                }
            }
            check(stored) { "Scheduled follow-up changed concurrently" }
            val markerId = followUpMarkerId(request.conversationKey, followUp.id)
            val markerBody = followUpMarkerBody(followUp)
            val markerMetadata =
                JSONObject()
                    .put("v", 1)
                    .put("followUpId", followUp.id)
                    .put("persona", followUp.personaKey)
                    .put("scheduledAt", followUp.scheduledAtMs)
                    .toString()
            // The update only ever matches a re-run of this exact plan, and then it writes the
            // identical body back — the prompt does not change, so nothing cached is lost. A new
            // plan has a new id and therefore no row yet, and is appended at the point in the
            // conversation where the model actually decided it. Appending at the end is the one
            // edit that costs no cached prefix at all.
            if (!repository.updateLocalContextMarker(markerId, markerBody, markerMetadata, request.createdAtMs)) {
                repository.storeMessages(
                    listOf(
                        MessageRecord(
                            providerMessageId = markerId,
                            chatId = request.chatJid,
                            conversationKey = request.conversationKey,
                            direction = MessageDirection.SYSTEM,
                            messageType = SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                            body = markerBody,
                            occurredAt = request.createdAtMs,
                            receivedAt = request.createdAtMs,
                            deliveryState = MessageDeliveryState.READ,
                            metadataJson = markerMetadata,
                        ),
                    ),
                )
                repository.trimFollowUpMarkers(request.conversationKey, MAX_FOLLOW_UP_MARKERS)
            }
        }
    }

    override suspend fun listScheduledFollowUps(
        personaKey: String,
        limit: Int,
    ): List<Pair<ProactiveStateSnapshot, ScheduledFollowUp>> =
        withContext(ioDispatcher) {
            repository.listProactiveStates(limit = ProactivePersistence.MAX_PROACTIVE_STATES)
                .asSequence()
                .map { it.toEngineProactiveState() }
                .mapNotNull { state -> state.scheduledFollowUp?.let { state to it } }
                .filter { (_, followUp) -> followUp.personaKey == personaKey }
                .sortedBy { (_, followUp) -> followUp.nextAttemptAtMs }
                .take(limit.coerceIn(1, 50))
                .toList()
        }

    override suspend fun loadSeed(chatJid: String): ProactiveSeed? =
        withContext(ioDispatcher) {
            val chat = repository.getChat(chatJid) ?: return@withContext null
            val inbound =
                repository
                    .listMessages(chatId = chatJid, limit = PROACTIVE_SEED_SCAN_ROWS)
                    .firstNotNullOfOrNull { it.toIncomingEvent(chat) }
            if (inbound != null) return@withContext ProactiveSeed(inbound)
            // No inbound history at all: only a contact the user armed by hand may be seeded, and
            // the stand-in carries addressing fields only so nothing downstream reads it as a
            // message the contact actually sent.
            val state = repository.getProactiveState(chatJid) ?: return@withContext null
            if (state.coldOutreachAt == null) return@withContext null
            ProactiveSeed(coldSeedEvent(chat, state.coldOutreachAt))
        }

    private fun coldSeedEvent(
        chat: ChatRecord,
        armedAtMs: Long,
    ): IncomingEvent =
        IncomingEvent(
            eventId = "cold:${chat.chatId}:$armedAtMs",
            sequence = 0L,
            kind = ChatEventKind.MESSAGE,
            messageId = "cold:${chat.chatId}:$armedAtMs",
            chatJid = chat.chatId,
            chatName = chat.subject ?: chat.displayName,
            isGroup = chat.kind == ChatKind.GROUP,
            senderJid = chat.chatId,
            senderName = chat.displayName,
            fromMe = false,
            timestampMs = armedAtMs,
        )

    override suspend fun nextDue(): DueWork? =
        withContext(ioDispatcher) {
            val state = repository.nextProactiveDeadline() ?: return@withContext null
            val metadata = parseProactiveMetadata(state)
            val scheduledAt = metadata.followUp?.nextAttemptAtMs
            val scheduledWins = scheduledAt != null && scheduledAt <= (metadata.baseNextDueAtMs ?: Long.MAX_VALUE)
            val dueAt = maxOf(
                state.nextDueAt ?: return@withContext null,
                state.cooldownUntil ?: 0L,
                state.leaseUntil ?: 0L,
                if (scheduledWins) 0L else repository.proactiveGlobalNotBefore() ?: 0L,
            )
            DueWork(
                id = state.chatId,
                kind = DueWorkKind.PROACTIVE,
                dueAtMs = dueAt,
            )
        }

    override suspend fun setGlobalNotBefore(timestampMs: Long) {
        withContext(ioDispatcher) {
            repository.setProactiveGlobalNotBefore(timestampMs.coerceAtLeast(0L))
        }
    }

    override suspend fun claim(id: String, nowMs: Long): Boolean =
        withContext(ioDispatcher) {
            val current = repository.getProactiveState(id) ?: return@withContext false
            val metadata = parseProactiveMetadata(current)
            val scheduledDue = metadata.followUp?.nextAttemptAtMs?.let { it <= nowMs } == true
            val claimed =
                repository.claimProactive(
                    chatId = id,
                    leaseOwner = proactiveLeaseOwner,
                    now = nowMs.coerceAtLeast(0L),
                    leaseDurationMs = ProactiveCoordinator.LEASE_DURATION_MS,
                    respectGlobalNotBefore = !scheduledDue,
                ) ?: return@withContext false
            proactiveClaimVersions[id] = claimed.updatedAt
            true
        }

    override suspend fun finish(id: String, result: DueWorkResult) {
        withContext(ioDispatcher) {
            val now = wallTimeMillis().coerceAtLeast(0L)
            val current = repository.getProactiveState(id)
            val metadata = current?.let(::parseProactiveMetadata)
            val updatedFollowUp =
                metadata?.followUp?.let { followUp ->
                    when {
                        result.completedFollowUpId == followUp.id -> null
                        result.retryFollowUpAtMs != null && result.retryFollowUpId == followUp.id ->
                            followUp.copy(nextAttemptAtMs = result.retryFollowUpAtMs)
                        else -> followUp
                    }
                }
            val baseNextDue =
                if (result.completedFollowUpId != null || result.retryFollowUpAtMs != null) {
                    metadata?.baseNextDueAtMs
                } else {
                    result.nextDueAtMs
                }
            val failedDue =
                if (!result.success && !result.disable) {
                    val failures = current?.consecutiveFailures ?: 0
                    safeAdd(now, ProactiveCoordinator.failureBackoffMs(failures))
                } else {
                    null
                }
            val nextDue = failedDue ?: earliest(baseNextDue, updatedFollowUp?.nextAttemptAtMs)
            repository.finishProactiveLease(
                chatId = id,
                leaseOwner = proactiveLeaseOwner,
                nextDueAt = nextDue,
                success = result.success,
                outboundSent = result.outboundSent,
                now = now,
                enabled = (!result.disable && baseNextDue != null) || updatedFollowUp != null,
                expectedUpdatedAt = proactiveClaimVersions.remove(id),
                stateJson = proactiveMetadata(
                    deferredAtMs = metadata?.deferredAtMs,
                    baseNextDueAtMs = baseNextDue,
                    followUp = updatedFollowUp,
                ),
            )
            // Firing the follow-up used to rewrite its marker to past tense. That was one more
            // edit to a row the prompt had already cached, for a fact the conversation shows on
            // its own: the message the timer sent sits right after the note. The marker says what
            // was planned and points at list_scheduled_followups for whether it is still armed, so
            // it needs no correction here.
        }
    }

    override suspend fun claimInbound(event: IncomingEvent): Boolean =
        withContext(ioDispatcher) {
            val receivedAt = wallTimeMillis().coerceAtLeast(0L)
            val processed =
                ProcessedEventRecord(
                    eventId = event.eventId,
                    source = EVENT_SOURCE,
                    eventType = event.kind.name.lowercase(),
                    chatId = event.chatJid,
                    providerMessageId = event.messageId,
                    payloadHash = event.payloadHash(),
                    receivedAt = receivedAt,
                    expiresAt = safeAdd(receivedAt, EVENT_DEDUPE_RETENTION_MS),
                )
            if (repository.claimEvent(processed)) {
                true
            } else {
                // A crash can happen after the lightweight event claim but
                // before the access-approved message write. Re-evaluate that
                // unfinished event; command replies use stable outbox IDs and
                // mutations are idempotent, so this never duplicates a
                // visible operation. A present provider message proves the
                // conversation commit already finished.
                repository.getEventDisposition(event.eventId) == null
            }
        }

    override suspend fun persistConversation(event: IncomingEvent): Boolean =
        withContext(ioDispatcher) {
            require(!event.fromMe) { "Own messages are not inbound conversation input" }
            require(
                event.kind in
                    setOf(
                        ChatEventKind.MESSAGE,
                        ChatEventKind.REACTION,
                        ChatEventKind.CALL_MISSED,
                    ),
            ) {
                "Only conversational messages, reactions and calls can be persisted"
            }
            val receivedAt = wallTimeMillis().coerceAtLeast(0L)
            val snapshot = settings.snapshot()
            val fromAdmin = isConfiguredAdmin(event, snapshot) || isDatabaseAdmin(event)
            val configuredPersona =
                event.personaKey
                    ?: snapshot.effectivePersona(event.chatJid)
            // A reaction belongs beside the message it targets. If the operator switched persona
            // after that message, assigning the reaction to the newly active persona made the old
            // exchange appear in the wrong character's timeline.
            val targetConversationKey =
                event.takeIf { it.kind == ChatEventKind.REACTION }
                    ?.targetMessageId
                    ?.let(repository::getMessage)
                    ?.conversationKey
            val conversationKey = targetConversationKey ?: event.conversationKey(configuredPersona)
            val persona =
                targetConversationKey
                    ?.substringAfterLast('#', "")
                    ?.takeIf(String::isNotBlank)
                    ?: configuredPersona
            val message =
                MessageRecord(
                    providerMessageId = event.messageId,
                    eventId = event.eventId,
                    chatId = event.chatJid,
                    conversationKey = conversationKey,
                    senderId = event.senderJid,
                    direction = MessageDirection.INBOUND,
                    messageType = event.persistedMessageType(),
                    body = event.persistedBody(),
                    quotedProviderMessageId = event.quoted?.messageId,
                    mediaKey = event.media?.id,
                    occurredAt = event.timestampMs.coerceAtLeast(0L),
                    receivedAt = receivedAt,
                    deliveryState = MessageDeliveryState.RECEIVED,
                    fromAdmin = fromAdmin,
                    metadataJson = event.persistenceMetadata(persona),
                )
            if (!repository.storeAcceptedConversation(event.eventId, message)) {
                return@withContext false
            }
            val configuredRetention = snapshot.integer(BotSettingKeys.HISTORY_RETENTION)
            if (configuredRetention > 0) {
                val writes =
                    retentionWriteCounts.merge(conversationKey, 1) { old, added -> old + added }
                        ?: 1
                if (writes >= RETENTION_TRIM_EVERY_WRITES) {
                    retentionWriteCounts.remove(conversationKey)
                    // Never prune into the prompt window. Retention is a storage budget, but the
                    // anchored history window is what the model actually reads, and a retention
                    // below "retained overlap + refresh interval" would delete rows the current
                    // window still points at — the anchor would then re-anchor mid-conversation
                    // and the prefix cache would miss on every turn until the next memory write.
                    val retainedOverlap = snapshot.integer(BotSettingKeys.HISTORY_LIMIT)
                    val windowFloor =
                        ConversationMemoryPolicy.completeWindow(
                            retainedOverlap,
                            snapshot.integer(BotSettingKeys.MEMORY_INTERVAL),
                        )
                    // The complete window is only the floor while memory writes are succeeding.
                    // A failing write holds the marker still and lets the backlog grow past it,
                    // and retention keeps the *newest* rows — exactly the wrong end, because the
                    // fold consumes the oldest unconsolidated messages first. Pruning there would
                    // delete messages that were never summarized: gone from the window and gone
                    // from memory, silently and for good. So the backlog is a floor of its own.
                    val backlogFloor =
                        backlogRetentionFloor(
                            conversationKey = conversationKey,
                            retainedOverlap = retainedOverlap,
                            windowFloor = windowFloor,
                        )
                    repository.trimConversationMessages(
                        conversationKey = conversationKey,
                        keepNewest =
                            maxOf(configuredRetention, windowFloor, backlogFloor).coerceAtMost(
                                BotDatabaseLimits.MAX_MESSAGES_PER_CHAT,
                            ),
                        now = receivedAt,
                    )
                }
            } else {
                retentionWriteCounts.remove(conversationKey)
            }

            // The accepted message is authoritative. Enrichment is non-fatal:
            // cosmetic metadata must never reopen the event claim.
            runCatching { enrichChat(event, receivedAt) }
            // A call is the contact reaching out just as a message is, so it counts
            // towards the silence the proactive coordinator measures.
            if (event.kind == ChatEventKind.MESSAGE || event.kind == ChatEventKind.CALL_MISSED) {
                runCatching {
                    repository.recordProactiveInbound(
                        event.chatJid,
                        event.timestampMs.coerceAtLeast(0L),
                    )
                }
            }
            true
        }

    override suspend fun completeInbound(
        event: IncomingEvent,
        disposition: String,
    ) {
        withContext(ioDispatcher) {
            val normalizedDisposition =
                disposition
                    .lowercase()
                    .replace(Regex("[^a-z0-9_-]"), "_")
                    .take(MAX_EVENT_DISPOSITION_CHARS)
                    .ifBlank { "ignored" }
            val completedAt = wallTimeMillis().coerceAtLeast(0L)
            repository.completeEvent(
                eventId = event.eventId,
                disposition = normalizedDisposition,
                completedAt = completedAt,
            )
            inboundDispositionNarration(normalizedDisposition)?.let { (level, summary) ->
                repository.appendActivity(
                    ActivityLogRecord(
                        occurredAt = completedAt,
                        level = level,
                        category = "inbound",
                        action = normalizedDisposition,
                        summary = summary,
                        detailsJson =
                            JSONObject()
                                .put("fromMe", event.fromMe)
                                .put("group", event.isGroup)
                                .put("senderAliasCount", event.senderAliases.size)
                                .put("chatAliasCount", event.chatAliases.size)
                                .toString(),
                    ),
                )
            }
        }
    }

    override suspend fun applyInboundMutation(event: IncomingEvent): Boolean =
        withContext(ioDispatcher) {
            val target = event.targetMessageId?.takeIf(String::isNotBlank)
                ?: return@withContext false
            val current = repository.getMessage(target) ?: return@withContext false
            if (current.chatId != event.chatJid) return@withContext false
            val body =
                when (event.kind) {
                    ChatEventKind.DELETE -> ChatHistoryLabels.incomingDelete()
                    ChatEventKind.EDIT ->
                        event.text
                            .takeIf(String::isNotBlank)
                            ?.let(ChatHistoryLabels::incomingEdit)
                            ?: return@withContext false

                    ChatEventKind.MESSAGE,
                    ChatEventKind.REACTION,
                    ChatEventKind.CALL_MISSED,
                    -> return@withContext false
                }
            val snapshot = settings.snapshot()
            val configuredPersona =
                event.personaKey
                    ?: snapshot.effectivePersona(event.chatJid)
            // Edits and deletes mutate the original exchange even when another persona is active
            // by the time WhatsApp delivers the mutation.
            val conversationKey =
                current.conversationKey ?: event.conversationKey(configuredPersona)
            val persona =
                current.conversationKey
                    ?.substringAfterLast('#', "")
                    ?.takeIf(String::isNotBlank)
                    ?: configuredPersona
            val eventMessageId = "event:${event.eventId}"
            val appended =
                repository.storeMessages(
                    listOf(
                        MessageRecord(
                            providerMessageId = eventMessageId,
                            eventId = event.eventId,
                            chatId = event.chatJid,
                            conversationKey = conversationKey,
                            senderId = event.senderJid,
                            direction = MessageDirection.INBOUND,
                            messageType = event.persistedMessageType(),
                            body = body,
                            quotedProviderMessageId = target,
                            occurredAt = event.timestampMs.coerceAtLeast(0L),
                            receivedAt = wallTimeMillis().coerceAtLeast(0L),
                            deliveryState = MessageDeliveryState.RECEIVED,
                            metadataJson = event.persistenceMetadata(persona),
                        ),
                    ),
                )
            appended == 1 || repository.getMessage(eventMessageId) != null
        }

    override suspend fun accessDecision(
        event: IncomingEvent,
        allowAll: Boolean,
    ): AccessDecision =
        withContext(ioDispatcher) {
            accessDecisionInternal(event, allowAll, settings.snapshot())
        }

    override suspend fun inboundRateDecision(
        event: IncomingEvent,
        perMinute: Int,
        perFiveMinutes: Int,
        perTenMinutes: Int,
    ): InboundRateDecision =
        withContext(ioDispatcher) {
            val snapshot = settings.snapshot()
            val configuredRateLimit = snapshot.appInteger(AppSettingKeys.RATE_LIMIT_MAX)
            val configuredRateWindow =
                snapshot.appInteger(AppSettingKeys.RATE_LIMIT_WINDOW_MS).toLong()
            val floodWindows =
                listOf(
                    RateWindow(ONE_MINUTE_MS, perMinute),
                    RateWindow(FIVE_MINUTES_MS, perFiveMinutes),
                    RateWindow(TEN_MINUTES_MS, perTenMinutes),
                ).filter { it.limit > 0 && it.durationMs > 0 }
            val softWindow =
                RateWindow(configuredRateWindow, configuredRateLimit)
                    .takeIf { it.limit > 0 && it.durationMs > 0 }
            val windows = floodWindows + listOfNotNull(softWindow)
            if (windows.isEmpty()) {
                return@withContext InboundRateDecision(
                    limited = false,
                    autoblockThresholdExceeded = false,
                )
            }

            val now = wallTimeMillis().coerceAtLeast(0L)
            val oldestCutoff = now - windows.maxOf(RateWindow::durationMs)
            // The current event is claimed but intentionally not yet part of
            // conversation history, so count it explicitly for the gate.
            val counts = IntArray(windows.size) { 1 }
            scanMessages(
                chatId = event.chatJid,
                // Other group participants can sit between this sender's messages.
                maximumRows = BotDatabaseLimits.MAX_MESSAGES_PER_CHAT,
                stopBeforeOccurredAt = oldestCutoff,
                preferredPageSize =
                    (windows.maxOf(RateWindow::limit) + 1).coerceIn(
                        MIN_FLOOD_PAGE_SIZE,
                        MAX_FLOOD_PAGE_SIZE,
                    ),
            ) { record ->
                if (
                    record.direction == MessageDirection.INBOUND &&
                    eventIdentityCandidates(event).any {
                        candidate -> sameIdentity(record.senderId, candidate)
                    }
                ) {
                    windows.forEachIndexed { index, window ->
                        if (record.occurredAt >= now - window.durationMs) counts[index]++
                    }
                }
                counts.indices.any { counts[it] > windows[it].limit }
            }
            val floodExceeded =
                floodWindows.indices.any { index ->
                    counts[index] > floodWindows[index].limit
                }
            val softExceeded =
                softWindow?.let { window ->
                    counts[windows.lastIndex] > window.limit
                } == true
            InboundRateDecision(
                limited = floodExceeded || softExceeded,
                autoblockThresholdExceeded = floodExceeded,
            )
        }

    override suspend fun blockSender(event: IncomingEvent, reason: String) {
        withContext(ioDispatcher) {
            val now = wallTimeMillis().coerceAtLeast(0L)
            (listOf(event.senderJid) + event.senderAliases)
                .distinct()
                .take(MAX_IDENTITY_ALIASES)
                .forEach { identity ->
                    repository.upsertAccessEntry(
                        AccessEntryRecord(
                            listKind = AccessListKind.BLOCK,
                            subjectType = AccessSubjectType.JID,
                            subjectId = identity,
                            label = reason.take(MAX_ACCESS_LABEL_CHARS),
                            enabled = true,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            runCatching {
                repository.appendActivity(
                    ActivityLogRecord(
                        occurredAt = now,
                        level = ActivityLevel.WARN,
                        category = CATEGORY_ACCESS,
                        action = ACTION_AUTOBLOCK,
                        chatId = event.chatJid,
                        correlationId = event.eventId.take(MAX_CORRELATION_CHARS),
                        summary = "Absender lokal blockiert: ${reason.take(256)}",
                    ),
                )
            }
        }
    }

    override suspend fun loadHistory(
        conversationKey: String,
        limit: Int,
    ): List<StoredTurnMessage> =
        withContext(ioDispatcher) {
            val requested =
                if (limit <= 0) {
                    BotDatabaseLimits.MAX_MESSAGES_PER_CHAT
                } else {
                    limit.coerceAtMost(BotDatabaseLimits.MAX_MESSAGES_PER_CHAT)
                }
            if (requested == 0) return@withContext emptyList()
            // Deliberately not trimmed back to [requested]. Each candidate already returns its
            // *anchored* window, which is meant to outgrow the requested overlap: it stays pinned
            // between two memory writes and grows only at its newest end, so the prompt prefix the
            // provider cached keeps matching. A `take(requested)` here quietly undid exactly that
            // and left every reply looking at the last ten messages, whatever the interval said.
            val newestFirst =
                (
                    conversationCandidates(conversationKey)
                        .flatMap { loadConversationMessages(it, requested) } +
                        repository.listMessagesByType(
                            chatId = conversationKey.substringBeforeLast('#'),
                            messageType = SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                            limit = 1,
                        ).filter { it.conversationKey == conversationKey }
                )
                    .distinctBy(MessageRecord::providerMessageId)
                    .sortedByDescending(MessageRecord::occurredAt)
                    .take(BotDatabaseLimits.MAX_MESSAGES_PER_CHAT)
            // The quoted message is almost always inside the window already; only a reply to
            // something older costs a lookup, and those are rare enough to read one at a time.
            val bodiesInWindow =
                newestFirst.associate { it.providerMessageId to it.body.orEmpty() }
            fun quotedBody(quotedId: String?): String? {
                val id = quotedId?.takeIf(String::isNotBlank) ?: return null
                val body = bodiesInWindow[id] ?: repository.getMessage(id)?.body
                return body?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_QUOTED_HISTORY_CHARS)
            }
            newestFirst
                .asReversed()
                .asSequence()
                .filter { !it.body.isNullOrBlank() }
                .map { record ->
                    StoredTurnMessage(
                        quotedText = quotedBody(record.quotedProviderMessageId),
                        id = record.providerMessageId,
                        role =
                            when (record.direction) {
                                MessageDirection.INBOUND -> "user"
                                MessageDirection.OUTBOUND -> "assistant"
                                MessageDirection.SYSTEM -> "system"
                            },
                        text = record.body.orEmpty(),
                        timestampMs = record.occurredAt,
                        operatorInjection =
                            record.messageType == CHAT_INJECTION_MESSAGE_TYPE ||
                                record.messageType == SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                        senderName =
                            record.metadataJson
                                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                                ?.stringOrNull("senderName"),
                    )
                }
                .toList()
        }

    override suspend fun loadChatMemory(conversationKey: String): String? =
        withContext(ioDispatcher) {
            conversationCandidates(conversationKey)
                .firstNotNullOfOrNull { candidate ->
                    repository.getChatMemory(candidate)?.summary?.takeIf(String::isNotBlank)
                }
        }

    private fun conversationCandidates(conversationKey: String): List<String> {
        val separator = conversationKey.lastIndexOf('#')
        if (separator <= 0 || separator == conversationKey.lastIndex) return listOf(conversationKey)
        val chatId = conversationKey.substring(0, separator)
        val persona = conversationKey.substring(separator + 1)
        val aliases = storedChatAliases(repository.getChat(chatId)?.metadataJson)
        return (listOf(chatId) + aliases).distinct().map { "$it#$persona" }
    }

    override suspend fun loadPersonaMemory(personaKey: String): String? =
        withContext(ioDispatcher) {
            repository.getPersonaMemory(personaKey)?.summary?.takeIf(String::isNotBlank)
        }

    override suspend fun needsAiDisclosure(chatJid: String): Boolean =
        withContext(ioDispatcher) {
            // An unknown chat has definitionally never been disclosed to. Defaulting to "needs it"
            // keeps a missing row from silently suppressing the notice.
            repository.getChat(chatJid)?.aiDisclosureSentAt == null
        }

    override suspend fun markAiDisclosureSent(chatJid: String, timestampMs: Long) {
        withContext(ioDispatcher) {
            repository.markAiDisclosureSent(chatJid, timestampMs)
        }
    }

    override suspend fun recordAssistant(
        conversationKey: String,
        chatJid: String,
        text: String,
        transportMessageIds: List<String>,
        timestampMs: Long,
    ) {
        withContext(ioDispatcher) {
            val ids = transportMessageIds.filter(String::isNotBlank).distinct()
            if (ids.isEmpty()) {
                check(text.isBlank()) {
                    "Cannot record assistant text without a transport message ID"
                }
                return@withContext
            }
            val occurredAt = timestampMs.coerceAtLeast(0L)
            val receivedAt = wallTimeMillis().coerceAtLeast(0L)
            val messages =
                ids.mapIndexed { index, id ->
                    val semanticRecord = index == ids.lastIndex
                    val semanticType = if (semanticRecord) text.outboundHistoryMessageType() else null
                    MessageRecord(
                        providerMessageId = id,
                        chatId = chatJid,
                        conversationKey = conversationKey,
                        direction = MessageDirection.OUTBOUND,
                        messageType = semanticType ?: if (semanticRecord) "assistant" else "assistant_side_effect",
                        body = text.takeIf { semanticRecord && it.isNotBlank() },
                        occurredAt = occurredAt,
                        receivedAt = receivedAt,
                        deliveryState = MessageDeliveryState.SENT,
                        metadataJson =
                            JSONObject()
                                .put("v", METADATA_VERSION)
                                .put("conversationKey", conversationKey)
                                .put("semantic", semanticRecord)
                                .toString(),
                    )
                }
            val inserted = repository.storeMessages(messages)
            check(inserted == messages.size || ids.all { repository.getMessage(it) != null }) {
                "Assistant transport records were not durably stored"
            }
            // This is a visible WhatsApp commit, not diagnostic chatter. Notify immediately so a
            // multi-bubble reply lands one bubble at a time while the next one is still typing.
            // StateFlow conflation plus the UI debounce keeps a burst to one pending DB reload.
            activityChanged()
        }
    }

    override suspend fun reviseAssistant(
        transportMessageId: String,
        text: String,
    ): Boolean =
        withContext(ioDispatcher) {
            repository.updateMessageBody(transportMessageId, text)
        }

    /**
     * Outbound media used to be persisted as a generic assistant message. The model still knew
     * what it was from the history prefix, but the native chat could only draw a text bubble.
     * Keep the durable model-readable line and add the normal media type so every reader gets the
     * same semantic event without another table or attachment cache.
     */
    private fun String.outboundHistoryMessageType(): String? =
        when {
            startsWith("You sent a voice note") -> "audio"
            startsWith("You sent an image") -> "image"
            startsWith("You sent a video") -> "video"
            startsWith("You sent a document") -> "document"
            startsWith("You sent a sticker") -> "sticker"
            startsWith("You sent a file") -> "unknown"
            else -> null
        }

    override suspend fun markDeferred(conversationKey: String, timestampMs: Long) {
        withContext(ioDispatcher) {
            appendStateActivity(
                conversationKey = conversationKey,
                timestampMs = timestampMs,
                category = CATEGORY_DEFERRED,
                action = ACTION_DEFERRED,
                summary = "Reply deliberately put off",
            )
        }
    }

    override suspend fun clearDeferred(conversationKey: String) {
        withContext(ioDispatcher) {
            appendStateActivity(
                conversationKey = conversationKey,
                timestampMs = wallTimeMillis(),
                category = CATEGORY_DEFERRED,
                action = ACTION_DEFERRED_CLEARED,
                summary = "Deferral lifted",
            )
        }
    }

    override suspend fun requestMemoryRefresh(conversationKey: String, timestampMs: Long) {
        withContext(ioDispatcher) {
            appendStateActivity(
                conversationKey = conversationKey,
                timestampMs = timestampMs,
                category = CATEGORY_MEMORY,
                action = ACTION_MEMORY_REFRESH_REQUESTED,
                summary = "Memory-Aktualisierung angefordert",
            )
        }
    }

    override suspend fun preflightOutbound(intent: OutboundIntent): OutboundDecision =
        withContext(ioDispatcher) {
            val ledgerKey = intent.reservationId.outboundLedgerKey()
            repository.getOutboundReservation(ledgerKey)?.let {
                return@withContext existingReservationDecision(it)
            }
            val snapshot = settings.snapshot()
            val facts = loadOutboundFacts(intent, intent.timestampMs.coerceAtLeast(0L))
            val policy =
                outboundPolicySettings(
                    snapshot,
                    intent.timestampMs.coerceAtLeast(0L),
                )
            OutboundPolicy.evaluate(
                intent = intent,
                facts = facts,
                settings = policy.settings,
            ).also { publishLimit(it, facts, policy) }
        }

    override suspend fun reserveOutbound(intent: OutboundIntent): OutboundDecision =
        withContext(ioDispatcher) {
            val ledgerKey = intent.reservationId.outboundLedgerKey()
            repository.getOutboundReservation(ledgerKey)?.let {
                return@withContext existingReservationDecision(it)
            }

            val snapshot = settings.snapshot()
            val now = intent.timestampMs.coerceAtLeast(0L)
            val policySettings = outboundPolicySettings(snapshot, now)
            val facts = loadOutboundFacts(intent, now)
            val decision = OutboundPolicy.evaluate(intent, facts, policySettings.settings)
            publishLimit(decision, facts, policySettings)
            if (decision !is OutboundDecision.Allowed) return@withContext decision

            val reservation =
                repository.reserveOutbound(
                    OutboundSafetyRecord(
                        dedupeKey = ledgerKey,
                        chatId = intent.chatJid,
                        outboundKind =
                            when {
                                !intent.countsTowardBudget -> OUTBOUND_KIND_TURN_PERMIT
                                intent.admin -> "admin"
                                intent.proactive -> "proactive"
                                else -> "reply"
                            },
                        decision = StoredOutboundDecision.ALLOW,
                        reasonCode = "policy_allow",
                        status = OutboundStatus.RESERVED,
                        payloadHash = intent.textHash.takeIf(String::isNotBlank),
                        plannedAt = now,
                        expiresAt = safeAdd(now, OUTBOUND_DEDUPE_RETENTION_MS),
                        metadataJson =
                            JSONObject()
                                .put("v", METADATA_VERSION)
                                .put("proactive", intent.proactive)
                                .put("admin", intent.admin)
                                .put("countsTowardBudget", intent.countsTowardBudget)
                                .toString(),
                    ),
                )
            if (reservation.acquired) {
                OutboundDecision.Allowed(intent.reservationId)
            } else {
                existingReservationDecision(reservation.record)
            }
        }

    override suspend fun completeOutbound(
        reservationId: String,
        transportMessageId: String?,
        success: Boolean,
        timestampMs: Long,
    ) {
        withContext(ioDispatcher) {
            val completedAt = timestampMs.coerceAtLeast(0L)
            val ledgerKey = reservationId.outboundLedgerKey()
            val changed =
                repository.markOutboundStatus(
                    dedupeKey = ledgerKey,
                    status = if (success) OutboundStatus.SENT else OutboundStatus.FAILED,
                    committedAt = completedAt,
                    reasonCode = if (success) "transport_sent" else "transport_failed",
                )
            check(changed) { "Outbound reservation '$reservationId' does not exist" }
            // The safety-ledger transition above is authoritative. Diagnostics are useful but
            // must never turn a confirmed transport send into a reported failure. Successful
            // sends are already visible in durable message/outbox/turn state; only failure needs
            // the extra lookup and activity transaction.
            if (!success) {
                val reservation = repository.getOutboundReservation(ledgerKey)
                runCatching {
                    repository.appendActivity(
                        ActivityLogRecord(
                            occurredAt = completedAt,
                            level = ActivityLevel.WARN,
                            category = CATEGORY_OUTBOUND,
                            action = ACTION_OUTBOUND_FAILED,
                            chatId = reservation?.chatId,
                            correlationId = reservationId.take(MAX_CORRELATION_CHARS),
                            summary = "WhatsApp output failed",
                            detailsJson =
                                JSONObject()
                                    .put("v", METADATA_VERSION)
                                    .put(
                                        "transportMessageId",
                                        transportMessageId ?: JSONObject.NULL,
                                    ).toString(),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun lastOnlineAt(): Long? =
        withContext(ioDispatcher) {
            repository
                .listActivity(category = CATEGORY_ONLINE, limit = 1)
                .firstOrNull()
                ?.occurredAt
        }

    override suspend fun setLastOnlineAt(timestampMs: Long) {
        withContext(ioDispatcher) {
            repository.appendActivity(
                ActivityLogRecord(
                    occurredAt = timestampMs.coerceAtLeast(0L),
                    level = ActivityLevel.DEBUG,
                    category = CATEGORY_ONLINE,
                    action = ACTION_ONLINE,
                    summary = "Last successful bot activity",
                ),
            )
        }
    }

    override suspend fun recordTurnFailure(
        chatJid: String,
        turnId: String,
        proactive: Boolean,
        error: Throwable,
        timestampMs: Long,
    ) {
        withContext(ioDispatcher) {
            if (!settings.snapshot().boolean(BotSettingKeys.ERROR_REPORTS)) {
                return@withContext
            }
            runCatching {
                repository.appendActivity(
                    ActivityLogRecord(
                        occurredAt = timestampMs.coerceAtLeast(0L),
                        level = ActivityLevel.ERROR,
                        category = CATEGORY_TURN,
                        action = ACTION_TURN_FAILED,
                        chatId = chatJid,
                        correlationId = turnId.take(MAX_CORRELATION_CHARS),
                        summary = safeTurnFailureSummary(error),
                        detailsJson =
                            JSONObject()
                                .put("v", METADATA_VERSION)
                                .put("proactive", proactive)
                                .put("type", error::class.java.name.take(256))
                                .apply {
                                    (error as? de.totec.doppel.transport.BridgeActionException)
                                        ?.code
                                        ?.take(120)
                                        ?.let { put("reasonCode", it) }
                                }
                                .toString(),
                    ),
                )
                activityChanged()
            }
        }
    }

    private fun safeTurnFailureSummary(error: Throwable): String =
        when (error) {
            is de.totec.doppel.transport.BridgeActionException ->
                "WhatsApp-Bridge: ${error.code.take(120)}"
            is de.totec.doppel.ai.OpenRouterHttpException ->
                // The bare status was the whole message a failed generation left on screen, and
                // "OpenRouter HTTP 404" says nothing about whether the picture was refused or
                // never asked for. The classification and the field OpenRouter could not route
                // are both already in hand here, and neither can carry prompt text.
                listOfNotNull(
                    "OpenRouter HTTP ${error.statusCode}",
                    error.reasonCode.take(60).takeIf { it != "http_error" },
                    error.unsupportedParameter?.take(40),
                ).joinToString(" · ")
            is de.totec.doppel.ai.MissingApiKeyException ->
                "OpenRouter API key missing"
            is de.totec.doppel.ai.OpenRouterProtocolException ->
                "OpenRouter protocol error: ${error.reasonCode.take(120)}"
            is java.net.SocketTimeoutException ->
                "Provider-Timeout"
            is java.io.IOException ->
                "Network error: ${error::class.java.simpleName.take(80)}"
            else ->
                "Turn failed: ${error::class.java.simpleName.take(120)}"
        }.take(MAX_ACTIVITY_ERROR_SUMMARY_CHARS)

    override suspend fun recordTurnActivity(activity: de.totec.doppel.engine.TurnActivity) {
        withContext(ioDispatcher) {
            runCatching {
                repository.appendActivity(activity.toActivityRecord())
                activityChanged()
            }
        }
    }

    /**
     * One transaction and one feed notification for the whole batch.
     *
     * The engine buffers a turn's diagnostic rows and hands them over together, so the twenty-odd
     * inserts a reply used to make — each one a dispatcher hop and a UI refresh of its own — become
     * a couple of grouped writes. Ordering is unaffected: the rows keep the timestamps they were
     * recorded with and are inserted in the order they were buffered.
     */
    override suspend fun recordTurnActivities(
        activities: List<de.totec.doppel.engine.TurnActivity>,
    ) {
        if (activities.isEmpty()) return
        withContext(ioDispatcher) {
            runCatching {
                repository.appendActivities(activities.map { it.toActivityRecord() })
                activityChanged()
            }
        }
    }

    private fun de.totec.doppel.engine.TurnActivity.toActivityRecord(): ActivityLogRecord =
        ActivityLogRecord(
            occurredAt = timestampMs.coerceAtLeast(0L),
            level =
                when (level) {
                    de.totec.doppel.engine.TurnActivityLevel.DEBUG -> ActivityLevel.DEBUG
                    de.totec.doppel.engine.TurnActivityLevel.INFO -> ActivityLevel.INFO
                    de.totec.doppel.engine.TurnActivityLevel.WARN -> ActivityLevel.WARN
                    de.totec.doppel.engine.TurnActivityLevel.ERROR -> ActivityLevel.ERROR
                },
            category = CATEGORY_TURN,
            action = stage.take(100),
            chatId = chatJid,
            correlationId = turnId.take(MAX_CORRELATION_CHARS),
            summary = summary.take(MAX_ACTIVITY_ERROR_SUMMARY_CHARS),
            detailsJson =
                JSONObject()
                    .put("v", METADATA_VERSION)
                    .put("elapsedMs", elapsedMs ?: JSONObject.NULL)
                    .toString(),
        )

    override suspend fun recoverPending(): List<List<IncomingEvent>> =
        withContext(ioDispatcher) {
            val snapshot = settings.snapshot()
            val commandPrefix = snapshot.appText(AppSettingKeys.COMMAND_PREFIX)
            val chats =
                repository
                .listChats(
                    includeArchived = true,
                    limit = BotDatabaseLimits.MAX_QUERY_LIMIT,
                )
            // Most chats end in our own outbound and cannot contain an interrupted reply. One
            // bounded projection query per batch eliminates the old history query for every such
            // chat before recovery inspects the genuinely pending tail.
            val latestDirections =
                chats
                    .chunked(RECOVERY_CHAT_BATCH)
                    .flatMap { batch ->
                        repository.listLatestMessagePreviews(
                            batch.map(ChatRecord::chatId),
                            includeSystem = false,
                        ).values
                    }
                    .associateBy({ it.chatId }, { it.direction })
            chats
                .asSequence()
                .filter { latestDirections[it.chatId] == MessageDirection.INBOUND }
                .mapNotNull { chat ->
                    val newestFirst =
                        loadMessages(chat.chatId, RECOVERY_MESSAGES_PER_CHAT)
                    val trailingInbound =
                        newestFirst
                            .takeWhile { it.direction != MessageDirection.OUTBOUND }
                            .filter { it.direction == MessageDirection.INBOUND }
                    if (trailingInbound.isEmpty()) return@mapNotNull null

                    val newestInboundAt = trailingInbound.maxOf(MessageRecord::occurredAt)
                    if (isDurablyDeferred(chat.chatId, newestInboundAt)) {
                        return@mapNotNull null
                    }
                    if (hasAmbiguousOutbound(chat.chatId, trailingInbound.minOf(MessageRecord::occurredAt))) {
                        return@mapNotNull null
                    }

                    val recovered =
                        trailingInbound
                            .asReversed()
                            .mapNotNull { it.toIncomingEvent(chat) }
                            // A silent handled turn has no outbound message to terminate the
                            // trailing-inbound scan. Its terminal disposition is the durable proof
                            // that recovery must not resurrect it after a process restart.
                            .filter { event -> repository.getEventDisposition(event.eventId) == null }
                            .filter { event ->
                                val decision =
                                    accessDecisionInternal(
                                        event,
                                        snapshot.appBoolean(AppSettingKeys.ALLOW_ALL),
                                        snapshot,
                                    )
                                decision.allowed &&
                                    (
                                        commandPrefix.isEmpty() ||
                                            !event.text.trimStart().startsWith(commandPrefix)
                                    )
                            }
                    recovered.takeIf(List<IncomingEvent>::isNotEmpty)
                }
                .toList()
        }

    private fun enrichChat(event: IncomingEvent, receivedAt: Long) {
        val current = repository.getChat(event.chatJid)
        val kind = if (event.isGroup) ChatKind.GROUP else ChatKind.DIRECT
        repository.upsertChat(
            (current ?: ChatRecord(chatId = event.chatJid, createdAt = receivedAt)).copy(
                kind = kind,
                // A direct chat is keyed by whatever address the transport delivered on, which
                // today is usually the contact's LID. Their phone number shares no digits with it,
                // so unless the alternate address is written down here, nothing can ever tell that
                // "49151…" and "1234…@lid" are the same person.
                metadataJson =
                    if (event.isGroup) {
                        current?.metadataJson
                    } else {
                        mergeChatAliases(current?.metadataJson, event.chatAliases)
                    },
                displayName =
                    if (event.isGroup) {
                        current?.displayName
                    } else {
                        event.chatName ?: current?.displayName
                    },
                subject =
                    if (event.isGroup) {
                        event.chatName ?: current?.subject
                    } else {
                        current?.subject
                    },
                lastMessageAt = maxOf(current?.lastMessageAt ?: 0L, event.timestampMs.coerceAtLeast(0L)),
                updatedAt = maxOf(current?.updatedAt ?: 0L, receivedAt),
            ),
        )
    }

    private fun accessDecisionInternal(
        event: IncomingEvent,
        allowAll: Boolean,
        snapshot: SettingsSnapshot,
    ): AccessDecision {
        val isAdmin = isConfiguredAdmin(event, snapshot) || isDatabaseAdmin(event)
        if (isAdmin) return AccessDecision(allowed = true, isAdmin = true)

        if (isDatabaseBlocked(event)) {
            return AccessDecision(false, false, "Lokal blockiert")
        }

        if (event.isGroup) {
            val groupAllowed =
                allowAll ||
                    matchesAnyConfigured(
                    snapshot.appStringList(AppSettingKeys.GROUP_ALLOWLIST),
                    groupCandidates(event),
                ) ||
                    isListed(
                        AccessListKind.GROUP_ALLOW,
                        groupSubjectCandidates(event),
                    )
            if (!groupAllowed) {
                return AccessDecision(false, false, "Group is not allowed")
            }
            val trigger = snapshot.appText(AppSettingKeys.GROUP_TRIGGER)
            if (
                event.kind == ChatEventKind.MESSAGE &&
                trigger.isNotEmpty() &&
                !event.text.trimStart().startsWith(trigger)
            ) {
                return AccessDecision(false, false, "Group trigger is missing")
            }
            return AccessDecision(true, false)
        }

        val configured =
            matchesAnyConfigured(
                snapshot.appStringList(AppSettingKeys.ALLOWLIST_NUMBERS),
                eventIdentityCandidates(event) +
                    event.chatJid +
                    event.chatAliases,
            )
        val database =
            isListed(
                AccessListKind.ALLOW,
                eventIdentitySubjects(event) +
                    (listOf(event.chatJid) + event.chatAliases)
                        .map { AccessSubjectType.CHAT to it },
            )
        return if (allowAll || configured || database) {
            AccessDecision(true, false)
        } else {
            AccessDecision(false, false, "Contact is not allowed")
        }
    }

    private fun isConfiguredAdmin(event: IncomingEvent, snapshot: SettingsSnapshot): Boolean {
        val configured =
            snapshot.appStringList(AppSettingKeys.OWNER_NUMBERS) +
                snapshot.appStringList(AppSettingKeys.ADMIN_NUMBERS)
        return configuredAdminMatches(event, configured)
    }

    private fun isDatabaseAdmin(event: IncomingEvent): Boolean =
        isListed(AccessListKind.ADMIN, eventIdentitySubjects(event))

    private fun isDatabaseBlocked(event: IncomingEvent): Boolean {
        val subjects =
            eventIdentitySubjects(event) +
                (listOf(event.chatJid) + event.chatAliases)
                    .map { AccessSubjectType.CHAT to it } +
                if (event.isGroup) groupSubjectCandidates(event) else emptyList()
        return isListed(AccessListKind.BLOCK, subjects)
    }

    private fun eventIdentityCandidates(event: IncomingEvent): List<String> =
        (listOf(event.senderJid) + event.senderAliases)
            .asSequence()
            .flatMap { identityCandidates(it).asSequence() }
            .distinct()
            .take(MAX_EXPANDED_IDENTITY_CANDIDATES)
            .toList()

    private fun eventIdentitySubjects(
        event: IncomingEvent,
    ): List<Pair<AccessSubjectType, String>> =
        (listOf(event.senderJid) + event.senderAliases)
            .asSequence()
            .flatMap { identitySubjectCandidates(it).asSequence() }
            .distinct()
            .take(MAX_EXPANDED_IDENTITY_CANDIDATES)
            .toList()

    private fun isListed(
        kind: AccessListKind,
        subjects: List<Pair<AccessSubjectType, String>>,
    ): Boolean =
        subjects
            .asSequence()
            .filter { it.second.isNotBlank() }
            .distinct()
            .any { (type, id) -> repository.isAccessListed(kind, type, id) }

    private fun loadOutboundFacts(intent: OutboundIntent, now: Long): OutboundFacts {
        val cutoff =
            now -
                if (intent.proactive) {
                    THIRTY_DAYS_MS
                } else {
                    ONE_DAY_MS
                }
        val ledger = loadOutboundSince(cutoff)
        val budgetLedger = ledger.filter { it.outboundKind != OUTBOUND_KIND_TURN_PERMIT }
        val sent = budgetLedger.filter { it.status == OutboundStatus.SENT }
        val oneHourAgo = now - ONE_HOUR_MS
        val oneDayAgo = now - ONE_DAY_MS
        val emptyHash = EMPTY_TEXT_SHA256
        // A short reply repeating itself is how people actually talk — "ok", "haha", a single
        // emoji — so it is exempt. Anything longer repeating inside ten minutes in the same chat
        // is a resend, and that is what the guard is for. Non-text payloads (payloadChars == 0:
        // reactions, media) stay guarded, because there the hash really does identify one action.
        val shortNaturalReply = intent.payloadChars in 1..SHORT_REPLY_MAX_CHARS
        val samePayload =
            intent.textHash != emptyHash &&
                !shortNaturalReply &&
                budgetLedger.any { row ->
                    row.dedupeKey != intent.reservationId &&
                        row.chatId == intent.chatJid &&
                        row.payloadHash == intent.textHash &&
                        row.plannedAt >= now - DUPLICATE_PAYLOAD_WINDOW_MS &&
                        row.status in setOf(OutboundStatus.RESERVED, OutboundStatus.SENT)
                }
        val proactiveSentToday =
            sent.count {
                it.outboundKind == "proactive" &&
                    it.chatId == intent.chatJid &&
                    effectiveCommittedAt(it) >= oneDayAgo
            }
        val inboundToday =
            if (intent.proactive) {
                countInboundSince(intent.chatJid, oneDayAgo)
            } else {
                0
            }
        val proactiveChats =
            if (intent.proactive) {
                sent
                    .asSequence()
                    .filter {
                        it.outboundKind == "proactive" &&
                            effectiveCommittedAt(it) >= now - THIRTY_DAYS_MS
                    }
                    .mapNotNull(OutboundSafetyRecord::chatId)
                    .distinct()
                    .toList()
            } else {
                emptyList()
            }
        val coldContacts =
            proactiveChats.count { repository.getProactiveState(it)?.lastInboundAt == null }
        val hardBlocked =
            if (isOutboundChatBlocked(intent.chatJid)) "Recipient is blocked locally" else null
        val isColdContact =
            intent.proactive &&
                repository.getProactiveState(intent.chatJid)?.lastInboundAt == null
        val activeSafetyControls = repository.listActiveSafetyControls()
        // DENY and REVIEW used to collapse into the same absolute block, which is why a lock also
        // silenced the one conversation that could do something about it. They are different
        // statements: DENY is "WhatsApp is refusing this account", REVIEW is "something looks wrong,
        // a human should look" — and a human cannot look if the bot will not answer them.
        fun applicableLock(decision: StoredOutboundDecision): OutboundSafetyRecord? =
            activeSafetyControls.firstOrNull {
                it.outboundKind == "safety_lock" &&
                    it.decision == decision &&
                    (it.expiresAt == null || it.expiresAt > now) &&
                    safetyScopeApplies(it.metadataJson, isColdContact)
            }
        val denyLock = applicableLock(StoredOutboundDecision.DENY)
        val reviewLock = applicableLock(StoredOutboundDecision.REVIEW)
        val safetyHoldUntil =
            activeSafetyControls
                .asSequence()
                .filter {
                    it.outboundKind == "safety_hold" &&
                        it.status == OutboundStatus.RESERVED &&
                        (it.expiresAt ?: Long.MIN_VALUE) > now
                }
                .mapNotNull(OutboundSafetyRecord::expiresAt)
                .maxOrNull()
        return OutboundFacts(
            hardLockReason =
                hardBlocked
                    ?: denyLock?.let { "WhatsApp is limiting this account: ${it.reasonCode}" },
            reviewRequiredReason =
                reviewLock?.let { "Safety review required: ${it.reasonCode}" },
            softHoldUntilMs = safetyHoldUntil,
            sentLastHour = sent.count { effectiveCommittedAt(it) >= oneHourAgo },
            sentLastDay = sent.count { effectiveCommittedAt(it) >= oneDayAgo },
            proactiveSentToday = proactiveSentToday,
            inboundToday = inboundToday,
            samePayloadSentRecently = !intent.admin && samePayload,
            lastGlobalSendAtMs =
                if (intent.admin) null else sent.maxOfOrNull { effectiveCommittedAt(it) },
            coldContactsLastThirtyDays = coldContacts,
            isColdContact =
                isColdContact,
            recentSendTimestampsMs =
                sent.asSequence()
                    .map(::effectiveCommittedAt)
                    .filter { it >= oneDayAgo }
                    .sorted()
                    .toList(),
        )
    }

    private fun safetyScopeApplies(
        metadataJson: String?,
        isColdContact: Boolean,
    ): Boolean {
        val scope =
            metadataJson
                ?.let { runCatching { JSONObject(it).optString("scope", "global") }.getOrNull() }
                ?: "global"
        return scope != "new_chat" || isColdContact
    }

    private fun outboundPolicySettings(
        snapshot: SettingsSnapshot,
        now: Long,
    ): EffectiveOutboundPolicy {
        val configuredProactiveCap = snapshot.integer(BotSettingKeys.PROACTIVE_DAILY_CAP)
        val derivedProactiveCap =
            if (configuredProactiveCap > 0) {
                configuredProactiveCap
            } else {
                val level = snapshot.integer(BotSettingKeys.PROACTIVE_LEVEL)
                if (level <= 0) 0 else ((level + 1) / 2).coerceAtLeast(1)
            }
        val warmupFactor = outboundWarmupFactor(snapshot, now)
        fun scaled(value: Int): Int =
            if (value <= 0) {
                value
            } else {
                (value * warmupFactor).toInt().coerceIn(1, value)
            }
        val configuredMaxPerHour = snapshot.integer(BotSettingKeys.MAX_SENDS_PER_HOUR)
        val configuredMaxPerDay = snapshot.integer(BotSettingKeys.MAX_SENDS_PER_DAY)
        val configuredColdMonthlyCap = snapshot.integer(BotSettingKeys.COLD_MONTHLY_CAP)
        return EffectiveOutboundPolicy(
            settings =
                OutboundPolicySettings(
                    maxPerHour = scaled(configuredMaxPerHour),
                    maxPerDay = scaled(configuredMaxPerDay),
                    proactiveDailyCap = scaled(derivedProactiveCap),
                    proactiveReplyRatioMax =
                        snapshot.decimal(BotSettingKeys.PROACTIVE_REPLY_RATIO_MAX),
                    coldMonthlyCap = scaled(configuredColdMonthlyCap),
                    minimumGlobalGapMs =
                        (MINIMUM_GLOBAL_SEND_GAP_MS / warmupFactor)
                            .toLong()
                            .coerceIn(
                                MINIMUM_GLOBAL_SEND_GAP_MS,
                                MAXIMUM_WARMUP_GLOBAL_GAP_MS,
                            ),
                ),
            configuredMaxPerHour = configuredMaxPerHour,
            configuredMaxPerDay = configuredMaxPerDay,
            configuredProactiveDailyCap = derivedProactiveCap,
            configuredColdMonthlyCap = configuredColdMonthlyCap,
            warmupFactor = warmupFactor,
        )
    }

    /**
     * The velocity budgets actually in force, next to the configured numbers they came from.
     *
     * Warm-up scales every cap down, so the number the policy enforces and the number the settings
     * screen shows are routinely different. Reporting only the enforced one is what made a refusal
     * read as "6/6 sends in the rolling hour" while the setting it pointed at plainly said 25 —
     * two true numbers that together look like a bug, because nothing on screen named the third
     * thing standing between them.
     */
    private data class EffectiveOutboundPolicy(
        val settings: OutboundPolicySettings,
        val configuredMaxPerHour: Int,
        val configuredMaxPerDay: Int,
        val configuredProactiveDailyCap: Int,
        val configuredColdMonthlyCap: Int,
        val warmupFactor: Double,
    ) {
        val warmingUp: Boolean get() = warmupFactor < 1.0
    }

    /** Resolves a refusal into the exact setting/value shown by the operator UI. */
    private fun publishLimit(
        decision: OutboundDecision,
        facts: OutboundFacts,
        effective: EffectiveOutboundPolicy,
    ) {
        val reason =
            when (decision) {
                is OutboundDecision.Blocked -> decision.reason
                is OutboundDecision.Deferred -> decision.reason
                // A send that got through is the proof the rule is no longer in force, so the row
                // comes down here. Returning without clearing left the last refusal on screen until
                // some *other* refusal replaced it — a limit that had long since lifted still
                // reading as "Limit reached", with no X, because nothing ever withdrew it.
                is OutboundDecision.Allowed -> {
                    limitChanged(null)
                    return
                }
            }
        val until = (decision as? OutboundDecision.Deferred)?.untilMs
        val policy = effective.settings

        /**
         * Names the setting that would actually lift this cap.
         *
         * While the warm-up is holding a budget below its configured value, raising that value is
         * the one thing that changes nothing: the factor is applied to whatever it is set to. So
         * the row points at the warm-up instead, and the detail carries both numbers so the
         * difference from the settings screen is stated rather than left to be discovered.
         */
        fun capped(
            countText: String,
            enforced: Int,
            configured: Int,
            settingKey: String,
            settingLabel: String,
        ): RuntimeLimitNotice =
            if (effective.warmingUp && enforced < configured) {
                RuntimeLimitNotice(
                    reason,
                    "$countText · warm-up holds $settingLabel at $enforced of $configured",
                    AppSettingKeys.WARMUP_ENABLED,
                    "Warm-up",
                    until,
                )
            } else {
                RuntimeLimitNotice(reason, countText, settingKey, settingLabel, until)
            }

        val notice =
            when (reason) {
                "Hourly send limit" ->
                    capped(
                        "${facts.sentLastHour}/${policy.maxPerHour} sends in the rolling hour",
                        policy.maxPerHour,
                        effective.configuredMaxPerHour,
                        BotSettingKeys.MAX_SENDS_PER_HOUR,
                        "Maximum sends per hour",
                    )
                "Daily send limit" ->
                    capped(
                        "${facts.sentLastDay}/${policy.maxPerDay} sends in the rolling day",
                        policy.maxPerDay,
                        effective.configuredMaxPerDay,
                        BotSettingKeys.MAX_SENDS_PER_DAY,
                        "Maximum sends per day",
                    )
                "Proactive daily limit" ->
                    capped(
                        "${facts.proactiveSentToday}/${policy.proactiveDailyCap} proactive sends today",
                        policy.proactiveDailyCap,
                        effective.configuredProactiveDailyCap,
                        BotSettingKeys.PROACTIVE_DAILY_CAP,
                        "Proactive maximum per day",
                    )
                "Proactive-to-reply ratio" ->
                    RuntimeLimitNotice(
                        reason,
                        "${facts.proactiveSentToday} proactive sends for ${facts.inboundToday} inbound messages",
                        BotSettingKeys.PROACTIVE_REPLY_RATIO_MAX,
                        "Proactive-to-reply ratio",
                    )
                "Cold contact limit" ->
                    capped(
                        "${facts.coldContactsLastThirtyDays}/${policy.coldMonthlyCap} new contacts in 30 days",
                        policy.coldMonthlyCap,
                        effective.configuredColdMonthlyCap,
                        BotSettingKeys.COLD_MONTHLY_CAP,
                        "Cold contacts per month",
                    )
                "Minimum send gap", "Duplicate message prevented" -> return
                else -> RuntimeLimitNotice(reason, "Safety layer: $reason", untilMs = until)
            }
        limitChanged(notice)
    }

    private fun outboundWarmupFactor(
        snapshot: SettingsSnapshot,
        now: Long,
    ): Double {
        if (!snapshot.appBoolean(AppSettingKeys.WARMUP_ENABLED)) return 1.0
        val installStartedAt = repository.getOrCreateInstallStartedAt(now)
        val installDuration =
            snapshot.appInteger(AppSettingKeys.WARMUP_DAYS)
                .toLong()
                .coerceAtLeast(1L) * ONE_DAY_MS
        val installProgress =
            ((now - installStartedAt).coerceAtLeast(0L).toDouble() / installDuration)
                .coerceIn(0.0, 1.0)

        // Asked of the index, not of the newest thousand ledger rows: cancelled safety locks are
        // rare, so the filtered query returns a handful of rows where the old scan parsed a full
        // page of ordinary sends to find them. Reaching further back than that page is harmless —
        // a recovery older than the warmup window resolves to progress 1.0 either way.
        val lastRecovery =
            repository
                .listOutboundSafety(
                    outboundKind = "safety_lock",
                    status = OutboundStatus.CANCELLED,
                    limit = BotDatabaseLimits.MAX_QUERY_LIMIT,
                )
                .asSequence()
                .filter { it.reasonCode in RECOVERY_SAFETY_REASONS }
                .map { effectiveCommittedAt(it) }
                .maxOrNull()
        val recoveryProgress =
            lastRecovery?.let { recoveryAt ->
                val duration =
                    snapshot.appInteger(AppSettingKeys.RECOVERY_WARMUP_DAYS)
                        .toLong()
                        .coerceAtLeast(1L) * ONE_DAY_MS
                ((now - recoveryAt).coerceAtLeast(0L).toDouble() / duration)
                    .coerceIn(0.0, 1.0)
            } ?: 1.0
        val progress = minOf(installProgress, recoveryProgress)
        return WARMUP_START_FACTOR + (1.0 - WARMUP_START_FACTOR) * progress
    }

    private fun existingReservationDecision(record: OutboundSafetyRecord): OutboundDecision =
        when (record.status) {
            OutboundStatus.SENT ->
                OutboundDecision.Blocked("Output was already sent", hard = false)

            OutboundStatus.RESERVED ->
                OutboundDecision.Blocked("Output is already reserved", hard = false)

            OutboundStatus.FAILED,
            OutboundStatus.CANCELLED,
            ->
                OutboundDecision.Blocked(
                    "A failed reservation cannot be reopened atomically",
                    hard = false,
                )
        }

    private fun isOutboundChatBlocked(chatJid: String): Boolean {
        val subjects =
            listOf(
                AccessSubjectType.CHAT to chatJid,
                AccessSubjectType.JID to chatJid,
            ) +
                canonicalPhone(chatJid)?.let { listOf(AccessSubjectType.PHONE to it) }.orEmpty()
        return isListed(AccessListKind.BLOCK, subjects)
    }

    /**
     * The ledger window every send decision is evaluated against, newest first.
     *
     * The cutoff is a SQL predicate, not a Kotlin filter. This used to page in the newest thousand
     * rows regardless of age and throw most of them away, several times per reply — the rows are
     * read, parsed and allocated either way, so the window has to be narrowed in the query.
     */
    private fun loadOutboundSince(cutoff: Long): List<OutboundSafetyRecord> {
        val result = ArrayList<OutboundSafetyRecord>()
        var beforePlannedAt: Long? = null
        var beforeDatabaseId: Long? = null
        while (result.size < MAX_OUTBOUND_SCAN_ROWS) {
            val page =
                repository.listOutboundSafety(
                    beforePlannedAt = beforePlannedAt,
                    beforeDatabaseId = beforeDatabaseId,
                    sincePlannedAt = cutoff,
                    limit = BotDatabaseLimits.MAX_QUERY_LIMIT,
                )
            if (page.isEmpty()) break
            result += page
            val oldest = page.last()
            if (
                page.size < BotDatabaseLimits.MAX_QUERY_LIMIT ||
                (
                    oldest.plannedAt == beforePlannedAt &&
                        oldest.databaseId == beforeDatabaseId
                )
            ) {
                break
            }
            beforePlannedAt = oldest.plannedAt
            beforeDatabaseId = oldest.databaseId
        }
        return result
    }

    private fun countInboundSince(chatJid: String, cutoff: Long): Int {
        var count = 0
        scanMessages(
            chatId = chatJid,
            maximumRows = BotDatabaseLimits.MAX_MESSAGES_PER_CHAT,
            stopBeforeOccurredAt = cutoff,
        ) { record ->
            if (record.direction == MessageDirection.INBOUND && record.occurredAt >= cutoff) {
                count++
            }
            false
        }
        return count
    }

    private fun loadMessages(chatId: String, maximumRows: Int): List<MessageRecord> {
        val result = ArrayList<MessageRecord>(maximumRows.coerceAtMost(BotDatabaseLimits.MAX_QUERY_LIMIT))
        scanMessages(chatId, maximumRows, Long.MIN_VALUE) {
            result += it
            false
        }
        return result
    }

    /**
     * Newest-first history window for a conversation, locked to the last successful memory write.
     *
     * A fresh memory revision starts with [requested] rows. Later calls scan only until the pinned
     * oldest row and therefore append new messages without moving the prefix. The window is allowed
     * to grow past the configured interval when a memory write fails — the pointer is released by a
     * successful write and by nothing else, so nothing unsummarized is ever dropped silently.
     */
    private fun loadConversationMessages(
        conversationKey: String,
        requested: Int,
    ): List<MessageRecord> {
        val chatMemory = repository.getChatMemory(conversationKey)
        val memoryRevision = chatMemory?.revision ?: 0L
        val previous = synchronized(historyAnchorLock) { historyAnchors[conversationKey] }
        val resetForMemory = previous == null || previous.memoryRevision != memoryRevision
        val heldAnchor =
            previous?.anchorMessageId.takeUnless { resetForMemory }
            // Cold start: the durable memory marker *is* the pointer, so a restart resumes the
            // window instead of jumping it forward. Without this every process start — and this
            // app is reinstalled and restarted constantly — silently threw away the messages
            // written since the last memory and answered from the newest ten. It applies only when
            // nothing is pinned yet: a *new* revision must reset to the overlap, and its marker is
            // the newest message anyway.
                ?: chatMemory?.lastProviderMessageId?.takeIf { previous == null }
        val result = ArrayList<MessageRecord>(requested.coerceAtMost(MAX_HISTORY_PAGE_SIZE))
        var foundHeldAnchor = false
        scanConversationMessages(
            conversationKey = conversationKey,
            maximumRows = BotDatabaseLimits.MAX_MESSAGES_PER_CHAT,
            stopBeforeOccurredAt = Long.MIN_VALUE,
            preferredPageSize =
                maxOf(requested, MIN_HISTORY_PAGE_SIZE)
                    .coerceAtMost(MAX_HISTORY_PAGE_SIZE),
        ) { record ->
            if (
                record.messageType != PERSONA_SWITCH_MESSAGE_TYPE &&
                !record.body.isNullOrBlank()
            ) {
                result += record
                if (heldAnchor != null && record.providerMessageId == heldAnchor) {
                    foundHeldAnchor = true
                }
            }
            // [requested] is a floor under every window, pointer or not: a marker only a message or
            // two back must still leave the configured overlap in front of it.
            (heldAnchor == null || foundHeldAnchor) && result.size >= requested
        }
        return anchorHistoryWindow(
            conversationKey = conversationKey,
            newestFirst = result,
            requested = requested,
            memoryRevision = memoryRevision,
            heldAnchor = heldAnchor,
            resetForMemory = resetForMemory,
        )
    }

    /** Reads the unconsolidated backlog and hands it to [RetentionFloor] to decide on. */
    private fun backlogRetentionFloor(
        conversationKey: String,
        retainedOverlap: Int,
        windowFloor: Int,
    ): Int =
        RetentionFloor.resolve(
            backlog =
                repository.countMessagesAfterMarkerAtMost(
                    chatId = conversationKey.chatJid(),
                    providerMessageId =
                        repository.getChatMemory(conversationKey)?.lastProviderMessageId,
                    limit = BotDatabaseLimits.MAX_QUERY_LIMIT,
                    conversationKey = conversationKey,
                ),
            retainedOverlap = retainedOverlap,
            windowFloor = windowFloor,
        )

    /** @return the anchored prefix of [newestFirst], re-anchoring only for a memory revision. */
    private fun anchorHistoryWindow(
        conversationKey: String,
        newestFirst: List<MessageRecord>,
        requested: Int,
        memoryRevision: Long,
        heldAnchor: String?,
        resetForMemory: Boolean,
    ): List<MessageRecord> {
        val decision =
            HistoryWindowAnchor.resolve(
                anchorMessageId = heldAnchor,
                newestFirstIds = newestFirst.map(MessageRecord::providerMessageId),
                requested = requested,
            )
        synchronized(historyAnchorLock) {
            historyAnchors[conversationKey] =
                HistoryAnchorState(
                    memoryRevision = memoryRevision,
                    anchorMessageId = decision.anchorMessageId,
                )
        }
        if (resetForMemory || decision.reanchored) {
            repository.appendActivity(
                ActivityLogRecord(
                    occurredAt = wallTimeMillis(),
                    level = ActivityLevel.DEBUG,
                    category = CATEGORY_HISTORY,
                    action = ACTION_HISTORY_REANCHORED,
                    chatId = conversationKey.chatJid(),
                    summary =
                        "History window anchored to memory revision $memoryRevision · " +
                            "${decision.size} messages · the pointer holds until the next memory write",
                ),
            )
        }
        return if (decision.size == newestFirst.size) {
            newestFirst
        } else {
            newestFirst.subList(0, decision.size)
        }
    }

    private data class HistoryAnchorState(
        val memoryRevision: Long,
        val anchorMessageId: String?,
    )

    private fun scanMessages(
        chatId: String,
        maximumRows: Int,
        stopBeforeOccurredAt: Long,
        preferredPageSize: Int = BotDatabaseLimits.MAX_QUERY_LIMIT,
        visitor: (MessageRecord) -> Boolean,
    ) = scanMessagePages(
        maximumRows,
        stopBeforeOccurredAt,
        preferredPageSize,
        { beforeOccurredAt, beforeDatabaseId, pageSize ->
                repository.listMessages(
                    chatId = chatId,
                    beforeOccurredAt = beforeOccurredAt,
                    beforeDatabaseId = beforeDatabaseId,
                    limit = pageSize,
                )
        },
        visitor,
    )

    private fun scanConversationMessages(
        conversationKey: String,
        maximumRows: Int,
        stopBeforeOccurredAt: Long,
        preferredPageSize: Int = BotDatabaseLimits.MAX_QUERY_LIMIT,
        visitor: (MessageRecord) -> Boolean,
    ) = scanMessagePages(
        maximumRows,
        stopBeforeOccurredAt,
        preferredPageSize,
        { beforeOccurredAt, beforeDatabaseId, pageSize ->
            repository.listConversationMessages(
                conversationKey = conversationKey,
                beforeOccurredAt = beforeOccurredAt,
                beforeDatabaseId = beforeDatabaseId,
                limit = pageSize,
            )
        },
        visitor,
    )

    private fun scanMessagePages(
        maximumRows: Int,
        stopBeforeOccurredAt: Long,
        preferredPageSize: Int,
        loadPage: (Long?, Long?, Int) -> List<MessageRecord>,
        visitor: (MessageRecord) -> Boolean,
    ) {
        var beforeOccurredAt: Long? = null
        var beforeDatabaseId: Long? = null
        var scanned = 0
        while (scanned < maximumRows) {
            val pageSize =
                minOf(
                    BotDatabaseLimits.MAX_QUERY_LIMIT,
                    preferredPageSize.coerceAtLeast(1),
                    maximumRows - scanned,
                )
            val page = loadPage(beforeOccurredAt, beforeDatabaseId, pageSize)
            if (page.isEmpty()) return
            for (record in page) {
                if (record.occurredAt < stopBeforeOccurredAt) return
                scanned++
                if (visitor(record)) return
                if (scanned >= maximumRows) return
            }
            if (page.size < pageSize) return
            val last = page.last()
            beforeOccurredAt = last.occurredAt
            beforeDatabaseId = last.databaseId
        }
    }

    private fun appendStateActivity(
        conversationKey: String,
        timestampMs: Long,
        category: String,
        action: String,
        summary: String,
    ) {
        repository.appendActivity(
            ActivityLogRecord(
                occurredAt = timestampMs.coerceAtLeast(0L),
                // Scheduling markers the engine writes to and reads back — deferral,
                // memory refresh. They are state, not events, and the reads that
                // depend on them ([isDurablyDeferred]) query by category without a
                // level filter, so the feed can stop showing them.
                level = ActivityLevel.DEBUG,
                category = category,
                action = action,
                chatId = conversationKey.chatJid(),
                correlationId = conversationKey.sha256().take(MAX_CORRELATION_CHARS),
                summary = summary,
                detailsJson =
                    JSONObject()
                        .put("v", METADATA_VERSION)
                        .put("conversationKey", conversationKey)
                        .toString(),
            ),
        )
    }

    private fun isDurablyDeferred(chatJid: String, newestInboundAt: Long): Boolean {
        val marker =
            repository
                .listActivity(
                    chatId = chatJid,
                    category = CATEGORY_DEFERRED,
                    limit = 1,
                )
                .firstOrNull()
                ?: return false
        return marker.action == ACTION_DEFERRED && marker.occurredAt >= newestInboundAt
    }

    private fun hasAmbiguousOutbound(chatJid: String, oldestInboundAt: Long): Boolean =
        repository
            .listOutboundSafety(chatId = chatJid, limit = RECOVERY_LEDGER_ROWS_PER_CHAT)
            .any { it.blocksInboundRecoveryAfter(oldestInboundAt) }

    private fun MessageRecord.toIncomingEvent(chat: ChatRecord): IncomingEvent? {
        if (direction != MessageDirection.INBOUND) return null
        val metadata = metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val mediaObject = metadata?.optJSONObject("media")
        val media =
            mediaObject?.let {
                val id = it.stringOrNull("id") ?: mediaKey ?: return@let null
                MediaReference(
                    id = id,
                    kind = it.stringOrNull("kind").toMediaKind(),
                    mimeType = it.stringOrNull("mimeType") ?: "application/octet-stream",
                    sizeBytes = it.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                    sha256 = it.stringOrNull("sha256"),
                    fileName = it.stringOrNull("fileName"),
                    caption = it.stringOrNull("caption"),
                )
            }
        val quotedId = quotedProviderMessageId ?: metadata?.stringOrNull("quotedMessageId")
        return IncomingEvent(
            eventId = eventId ?: "recovered:$providerMessageId",
            sequence = metadata?.optLong("sequence", databaseId) ?: databaseId,
            kind =
                metadata
                    ?.stringOrNull("kind")
                    .toChatEventKind(messageType),
            messageId = providerMessageId,
            chatJid = chatId,
            chatName = metadata?.stringOrNull("chatName") ?: chat.subject ?: chat.displayName,
            isGroup = metadata?.optBoolean("isGroup", chat.kind == ChatKind.GROUP)
                ?: (chat.kind == ChatKind.GROUP),
            senderJid = senderId ?: return null,
            senderName = metadata?.stringOrNull("senderName"),
            fromMe = false,
            timestampMs = occurredAt,
            text = body.orEmpty(),
            quoted = quotedId?.let { QuotedMessage(messageId = it) },
            reactionEmoji = metadata?.stringOrNull("reactionEmoji"),
            targetMessageId = metadata?.stringOrNull("targetMessageId"),
            media = media,
            chatAliases = restoredIdentityAliases(metadata, "chatAliases"),
            senderAliases = restoredIdentityAliases(metadata, "senderAliases"),
            personaKey = metadata?.stringOrNull("persona"),
        )
    }

    private fun ProactiveStateRecord.toEngineProactiveState(): ProactiveStateSnapshot {
        val metadata = parseProactiveMetadata(this)
        return ProactiveStateSnapshot(
            chatJid = chatId,
            enabled = enabled,
            nextDueAtMs = nextDueAt,
            cooldownUntilMs = cooldownUntil,
            lastInboundAtMs = lastInboundAt,
            lastOutboundAtMs = lastOutboundAt,
            dailyWindowStartedAtMs = dailyWindowStartedAt,
            dailyOutboundCount = dailyOutboundCount,
            consecutiveFailures = consecutiveFailures,
            leaseOwner = leaseOwner,
            leaseUntilMs = leaseUntil,
            deferredAtMs =
                metadata.deferredAtMs,
            baseNextDueAtMs = metadata.baseNextDueAtMs,
            scheduledFollowUp = metadata.followUp,
            coldOutreachAtMs = coldOutreachAt,
            updatedAtMs = updatedAt,
        )
    }

    private fun parseProactiveMetadata(record: ProactiveStateRecord) =
        ProactiveStateMetadataCodec.decode(record)

    private fun proactiveMetadata(
        deferredAtMs: Long?,
        baseNextDueAtMs: Long?,
        followUp: ScheduledFollowUp?,
    ): String = ProactiveStateMetadataCodec.encode(deferredAtMs, baseNextDueAtMs, followUp)

    /**
     * One id per *plan*, not one per chat.
     *
     * A chat-wide id meant the second follow-up rewrote the first one's row — a plan made tonight
     * landing in the history a hundred messages back, and every cached prompt token after that
     * row thrown away with it. Each plan is its own line in the conversation now, written where it
     * was made and never touched again. The id is still derived, not random, so re-running the
     * very same side effect finds the very same row and writes the very same bytes into it.
     */
    private fun followUpMarkerId(conversationKey: String, followUpId: String): String =
        "local-followup:${"$conversationKey|$followUpId".sha256().take(48)}"

    /**
     * Worded so it stays true forever, because it is never edited again — not when the plan is
     * replaced, not when the timer fires. It is a record of a decision, and the live state of the
     * timer comes from the tool that owns it.
     *
     * First person and with no framing of its own: the renderer replays this row as an operator
     * injection, which already puts `[in your head]` above it, so a second label here would only
     * be a bracket inside a bracket.
     */
    private fun followUpMarkerBody(followUp: ScheduledFollowUp): String =
        "I set a follow-up for this chat: message them at ${localStamp(followUp.scheduledAtMs)}. " +
            "Reason: ${followUp.note}. This note stays in the chat for good, so it records the " +
            "plan rather than proving the timer is still armed — list_scheduled_followups is what " +
            "says that. Do not set the same plan twice."

    /** Local wall time, because a UTC instant for a 20:00 plan reads as 18:00 and confuses both of us. */
    private fun localStamp(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        return runCatching {
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault()))
        }.getOrElse { instant.toString() }
    }

    private fun earliest(first: Long?, second: Long?): Long? =
        ProactiveStateMetadataCodec.earliest(first, second)

    private fun IncomingEvent.persistenceMetadata(persona: String): String {
        val root =
            JSONObject()
                .put("v", METADATA_VERSION)
                .put("sequence", sequence)
                .put("kind", kind.name)
                .put("persona", persona)
                .put("conversationKey", conversationKey(persona))
                .put("chatName", chatName ?: JSONObject.NULL)
                .put("isGroup", isGroup)
                .put("senderName", senderName ?: JSONObject.NULL)
                .put("chatAliases", org.json.JSONArray(chatAliases))
                .put("senderAliases", org.json.JSONArray(senderAliases))
                .put("reactionEmoji", reactionEmoji ?: JSONObject.NULL)
                .put("targetMessageId", targetMessageId ?: JSONObject.NULL)
                .put("quotedMessageId", quoted?.messageId ?: JSONObject.NULL)
        media?.let { reference ->
            root.put(
                "media",
                JSONObject()
                    .put("id", reference.id)
                    .put("kind", reference.kind.name)
                    .put("mimeType", reference.mimeType)
                    .put("sizeBytes", reference.sizeBytes)
                    .put("sha256", reference.sha256 ?: JSONObject.NULL)
                    .put("fileName", reference.fileName ?: JSONObject.NULL)
                    .put("caption", reference.caption ?: JSONObject.NULL),
            )
        }
        return root.toString()
    }

    private fun IncomingEvent.persistedMessageType(): String =
        when (kind) {
            ChatEventKind.REACTION -> "reaction"
            ChatEventKind.EDIT -> "edit"
            ChatEventKind.DELETE -> "delete"
            ChatEventKind.CALL_MISSED -> "call_missed"
            ChatEventKind.MESSAGE -> media?.kind?.name?.lowercase() ?: "text"
        }

    /**
     * Media is always stored as a named line, even before it has been analysed.
     *
     * History drops bodyless rows, so a plain voice note or photo would otherwise
     * leave no trace at all: a later turn could not see that anything arrived. A
     * caption alone is no better — it reads like typed text and hides that a
     * picture came with it. The line is replaced by the analysed one (same
     * wording, real transcript/description) once the media model has seen it,
     * see `updateMessageBody`.
     */
    private fun IncomingEvent.persistedBody(): String? =
        if (kind == ChatEventKind.REACTION) {
            reactionEmoji
                ?.takeIf(String::isNotBlank)
                ?.let { ChatHistoryLabels.incomingReaction(it, reactionTargetBody()) }
        } else if (kind == ChatEventKind.CALL_MISSED) {
            // A call carries no text, and history drops bodyless rows, so without an
            // explicit line the call would leave no trace for any later turn to see.
            ChatHistoryLabels.incomingMissedCall(callMedia == "video")
        } else {
            val mediaLine = media?.kind?.let { kind ->
                MediaHistoryLabels.incomingLine(
                    kind = kind,
                    detail =
                        text.takeIf(String::isNotBlank)
                            ?: media?.caption?.takeIf(String::isNotBlank),
                )
            }
            if (mediaLine != null) {
                // A media line has no room for the reference, so the quote keeps its own line.
                quoted
                    ?.let { "${ChatHistoryLabels.replyContext(it.text, it.messageId)}\n$mediaLine" }
                    ?: mediaLine
            } else {
                // The reference rides on the sender's own line rather than repeating the quoted
                // message above it, see ChatHistoryLabels.
                text.takeIf(String::isNotBlank)
                    ?.let { ChatHistoryLabels.incomingText(it, quoted?.text) }
                    ?: quoted?.let { ChatHistoryLabels.replyContext(it.text, it.messageId) }
            }
        }

    /**
     * The text of the message a reaction was attached to, already stripped of its history label.
     *
     * The bridge has always sent `targetMessageId`; it was simply dropped here, so the history only
     * ever held `User reacted with: 😯` and a reaction-only turn gave the model no content at all
     * to react to. It answered by inventing a reason.
     *
     * Null whenever the target is not in the database — reacting to something older than the
     * retention window is normal, and a missing referent is better than a wrong one.
     */
    private fun IncomingEvent.reactionTargetBody(): String? =
        targetMessageId
            ?.takeIf(String::isNotBlank)
            ?.let { id -> runCatching { repository.getMessage(id) }.getOrNull() }
            ?.body
            ?.let(HistoryLabelGuard::stripLeadingLabel)
            ?.takeIf(String::isNotBlank)

    private fun IncomingEvent.payloadHash(): String =
        buildString {
            append(eventId)
            append('\u0000')
            append(messageId)
            append('\u0000')
            append(kind.name)
            append('\u0000')
            append(text)
            append('\u0000')
            append(reactionEmoji.orEmpty())
            append('\u0000')
            append(media?.sha256 ?: media?.id.orEmpty())
        }.sha256()

    private fun effectiveCommittedAt(record: OutboundSafetyRecord): Long =
        record.committedAt ?: record.plannedAt

    private fun String.chatJid(): String = substringBeforeLast('#', this)

    private fun String.outboundLedgerKey(): String {
        require(isNotBlank()) { "Outbound reservation ID must not be blank" }
        return if (length <= MAX_DATABASE_EXTERNAL_ID_CHARS) {
            this
        } else {
            "sha256:${sha256()}"
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun String?.toMediaKind(): MediaKind =
        runCatching { MediaKind.valueOf(this.orEmpty().uppercase()) }.getOrDefault(MediaKind.UNKNOWN)

    private fun String?.toChatEventKind(messageType: String): ChatEventKind =
        runCatching { ChatEventKind.valueOf(this.orEmpty().uppercase()) }
            .getOrElse {
                when (messageType) {
                    "reaction" -> ChatEventKind.REACTION
                    "edit" -> ChatEventKind.EDIT
                    "delete" -> ChatEventKind.DELETE
                    "call_missed" -> ChatEventKind.CALL_MISSED
                    else -> ChatEventKind.MESSAGE
                }
            }

    private data class RateWindow(
        val durationMs: Long,
        val limit: Int,
    )

    /**
     * Keeps early inbound rejections diagnosable without storing message text, phone numbers,
     * chat IDs, or event IDs in the user-visible activity feed.
     */
    /**
     * Only a limit that actually held a message back is a warning. Skipping the bot's own
     * echo or a media type it never handles is the design working, and at one row per
     * outgoing message the echo alone would bury every real warning in the log.
     */
    private fun inboundDispositionNarration(disposition: String): Pair<ActivityLevel, String>? =
        when (disposition) {
            "access_blocked" ->
                ActivityLevel.INFO to "Inbound dropped by the access list."
            "from_me" ->
                ActivityLevel.DEBUG to "Own WhatsApp message was ignored."
            "rate_limited" ->
                ActivityLevel.WARN to "Inbound dropped by the message limit."
            "flood_limited" ->
                ActivityLevel.WARN to "Inbound dropped as a message flood."
            "unsupported_media" ->
                ActivityLevel.INFO to "Unsupported media message was ignored."
            else -> null
        }

    private companion object {
        const val EVENT_SOURCE = "baileys_bridge"
        const val METADATA_VERSION = 1
        const val MAX_ACCESS_LABEL_CHARS = 512
        const val MAX_IDENTITY_ALIASES = 16
        const val MAX_EXPANDED_IDENTITY_CANDIDATES = 64
        const val MAX_EVENT_DISPOSITION_CHARS = 64
        const val RETENTION_TRIM_EVERY_WRITES = 64
        const val MAX_CORRELATION_CHARS = 512
        const val MAX_PROACTIVE_CAS_ATTEMPTS = 5

        /**
         * How many past plans a chat keeps. Deep enough that the cut only reaches rows the prompt
         * window left behind, so trimming never rewrites anything the provider still has cached.
         */
        const val MAX_FOLLOW_UP_MARKERS = 12
        const val MAX_DATABASE_EXTERNAL_ID_CHARS = 512
        const val RECOVERY_MESSAGES_PER_CHAT = 1_000
        const val RECOVERY_CHAT_BATCH = 400
        const val RECOVERY_LEDGER_ROWS_PER_CHAT = 100
        const val PROACTIVE_SEED_SCAN_ROWS = 64
        const val MAX_OUTBOUND_SCAN_ROWS = 50_000

        /** Enough to recognise which message a reply points at, short enough to stay cheap. */
        const val MAX_QUOTED_HISTORY_CHARS = 160
        const val MIN_FLOOD_PAGE_SIZE = 32
        const val MAX_FLOOD_PAGE_SIZE = 256
        const val MIN_HISTORY_PAGE_SIZE = 64
        const val MAX_HISTORY_PAGE_SIZE = 256

        const val MAX_TRACKED_HISTORY_ANCHORS = 512

        const val CATEGORY_ACCESS = "access"
        const val ACTION_AUTOBLOCK = "autoblock"
        const val CATEGORY_DEFERRED = "engine_deferred"
        const val ACTION_DEFERRED = "deferred"
        const val ACTION_DEFERRED_CLEARED = "deferred_cleared"
        const val CATEGORY_MEMORY = "memory"
        const val ACTION_MEMORY_REFRESH_REQUESTED = "refresh_requested"
        const val CATEGORY_HISTORY = "history_window"
        const val ACTION_HISTORY_REANCHORED = "reanchored"
        const val CATEGORY_OUTBOUND = "outbound"
        const val ACTION_OUTBOUND_FAILED = "failed"
        const val CATEGORY_ONLINE = "engine_online"
        const val ACTION_ONLINE = "online"
        const val CATEGORY_TURN = "engine_turn"
        const val ACTION_TURN_FAILED = "failed"
        const val MAX_ACTIVITY_ERROR_SUMMARY_CHARS = 1_024

        const val ONE_MINUTE_MS = 60_000L
        const val FIVE_MINUTES_MS = 5L * ONE_MINUTE_MS
        const val TEN_MINUTES_MS = 10L * ONE_MINUTE_MS
        const val ONE_HOUR_MS = 60L * ONE_MINUTE_MS
        const val ONE_DAY_MS = 24L * ONE_HOUR_MS
        const val THIRTY_DAYS_MS = 30L * ONE_DAY_MS
        const val EVENT_DEDUPE_RETENTION_MS = 30L * ONE_DAY_MS
        const val OUTBOUND_DEDUPE_RETENTION_MS = 30L * ONE_DAY_MS
        const val DUPLICATE_PAYLOAD_WINDOW_MS = 10L * ONE_MINUTE_MS

        /**
         * Up to this many characters, a repeated message is treated as ordinary conversation
         * rather than a resend. Long enough for the short acknowledgements that genuinely recur,
         * short enough that no real generated reply slips past the guard.
         */
        const val SHORT_REPLY_MAX_CHARS = 32
        const val MINIMUM_GLOBAL_SEND_GAP_MS = 1_200L
        const val MAXIMUM_WARMUP_GLOBAL_GAP_MS = 4_800L
        const val WARMUP_START_FACTOR = 0.25
        val RECOVERY_SAFETY_REASONS =
            setOf(
                "restriction",
                "timelock",
                "message_capping",
                "connection_hard_stop",
            )

        val EMPTY_TEXT_SHA256: String = "".sha256()
    }
}

/** A turn permit authorizes work but is not proof that any user-visible effect began. */
internal fun OutboundSafetyRecord.blocksInboundRecoveryAfter(oldestInboundAt: Long): Boolean =
    outboundKind != OUTBOUND_KIND_TURN_PERMIT &&
        plannedAt >= oldestInboundAt &&
        status in setOf(OutboundStatus.RESERVED, OutboundStatus.SENT)

private fun identityCandidates(value: String): List<String> =
    buildList {
        value.trim().takeIf(String::isNotEmpty)?.let(::add)
        value.trim().lowercase().takeIf(String::isNotEmpty)?.let(::add)
        canonicalPhone(value)?.let(::add)
    }.distinct()

/** Metadata key under which a chat row remembers the contact's alternate PN/LID addresses. */
private const val CHAT_ALIASES_METADATA_KEY = "aliases"

/**
 * Folds the addresses seen on one event into what the chat row already knows. Aliases only ever
 * accumulate: a message that arrives without them (an older event, or a contact the LID store
 * cannot resolve right now) must not erase a link that was established earlier.
 */
internal fun mergeChatAliases(currentMetadataJson: String?, aliases: List<String>): String? {
    val metadata =
        currentMetadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
    val known = LinkedHashSet(restoredIdentityAliases(metadata, CHAT_ALIASES_METADATA_KEY))
    aliases.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(MAX_STORED_CHAT_ALIASES)
        .forEach(known::add)
    if (known.isEmpty()) return currentMetadataJson
    return (metadata ?: JSONObject())
        .put(CHAT_ALIASES_METADATA_KEY, org.json.JSONArray(known.take(MAX_STORED_CHAT_ALIASES)))
        .toString()
}

/** The alternate addresses [mergeChatAliases] recorded for a chat, or nothing for an unseen one. */
internal fun storedChatAliases(metadataJson: String?): List<String> =
    restoredIdentityAliases(
        metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() },
        CHAT_ALIASES_METADATA_KEY,
    )

/**
 * Whether a stored chat is the contact [address] names. Every address a user types is a phone
 * number, while the chat is keyed by whatever the transport delivered on — usually a LID, whose
 * digits are not the phone number. The recorded aliases are the only thing that can bridge that.
 */
internal fun chatMatchesAddress(chat: ChatRecord, address: String): Boolean =
    sameIdentity(chat.chatId, address) ||
        storedChatAliases(chat.metadataJson).any { sameIdentity(it, address) }

/**
 * Whether two stored chats are one contact reached under two addresses. The pair that matters is
 * the conversation under a LID and the empty stub that arming the same contact by phone number
 * left behind: both are real rows, and only one of them holds the history.
 */
internal fun sameChatIdentity(first: ChatRecord, second: ChatRecord): Boolean =
    chatMatchesAddress(first, second.chatId) ||
        storedChatAliases(second.metadataJson).any { chatMatchesAddress(first, it) }

/**
 * The per-contact override belonging to a chat. Overrides are filed under the address a user typed
 * — always a phone number — while the chat runs under its LID, so the plain key lookup finds
 * nothing and the contact silently keeps the global value. Matching the chat's recorded aliases as
 * well is what makes a slider set on one contact actually reach their conversation.
 */
internal fun <T> chatOverride(
    overrides: Map<String, T>,
    chatJid: String,
    aliases: List<String>,
): T? {
    if (overrides.isEmpty() || chatJid.isBlank()) return null
    overrides[chatJid.trim()]?.let { return it }
    if (aliases.isEmpty()) return null
    val identities = listOf(chatJid) + aliases
    return overrides.entries
        .firstOrNull { entry -> identities.any { sameIdentity(it, entry.key) } }
        ?.value
}

private const val MAX_STORED_CHAT_ALIASES = 16

internal fun restoredIdentityAliases(
    metadata: JSONObject?,
    key: String,
): List<String> {
    val values = metadata?.optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until values.length()) {
            values.optString(index)
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let(::add)
            if (size >= 16) break
        }
    }.distinct()
}

/**
 * A WhatsApp direct chat identifies the remote contact even when the event sender is a newer LID.
 * Group chat IDs must never grant a participant administrator rights.
 */
internal fun configuredAdminMatches(
    event: IncomingEvent,
    configured: List<String>,
): Boolean {
    val rawCandidates =
        buildList {
            add(event.senderJid)
            addAll(event.senderAliases)
            if (!event.isGroup) {
                add(event.chatJid)
                addAll(event.chatAliases)
            }
        }
    return matchesAnyConfigured(configured, rawCandidates)
}

private fun identitySubjectCandidates(value: String): List<Pair<AccessSubjectType, String>> =
    buildList {
        identityCandidates(value).forEach { candidate ->
            add(AccessSubjectType.JID to candidate)
        }
        canonicalPhone(value)?.let { add(AccessSubjectType.PHONE to it) }
    }.distinct()

private fun groupCandidates(event: IncomingEvent): List<String> =
    buildList {
        add(event.chatJid)
        add(event.chatJid.lowercase())
        event.chatJid.substringBefore('@').takeIf(String::isNotBlank)?.let(::add)
        event.chatName?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        event.chatName?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct()

private fun groupSubjectCandidates(event: IncomingEvent): List<Pair<AccessSubjectType, String>> =
    buildList {
        groupCandidates(event).forEach { candidate ->
            add(AccessSubjectType.GROUP to candidate)
        }
        add(AccessSubjectType.CHAT to event.chatJid)
        add(AccessSubjectType.JID to event.chatJid)
    }.distinct()

private fun matchesAnyConfigured(configured: List<String>, candidates: List<String>): Boolean {
    if (configured.isEmpty() || candidates.isEmpty()) return false
    val normalizedCandidates =
        candidates
            .asSequence()
            .flatMap { identityCandidates(it).asSequence() }
            .map(String::lowercase)
            .toHashSet()
    return configured.any { configuredValue ->
        identityCandidates(configuredValue).any {
            it.lowercase() in normalizedCandidates
        }
    }
}

internal fun sameIdentity(first: String?, second: String): Boolean {
    if (first == null) return false
    val firstCandidates = identityCandidates(first).mapTo(HashSet(), String::lowercase)
    return identityCandidates(second).any { it.lowercase() in firstCandidates }
}

private fun canonicalPhone(value: String): String? {
    val localPart = value.trim().substringBefore('@').substringBefore(':')
    val digits = localPart.filter(Char::isDigit)
    return digits.takeIf { it.length >= 5 }
}

private fun safeAdd(value: Long, delta: Long): Long =
    if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
