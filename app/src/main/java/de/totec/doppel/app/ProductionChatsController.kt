package de.totec.doppel.app

import android.net.Uri
import android.provider.OpenableColumns
import de.totec.doppel.ai.HistoryLabelGuard
import de.totec.doppel.data.ChatOverrideStore
import de.totec.doppel.data.resetConversation
import de.totec.doppel.data.ChatOverrides
import de.totec.doppel.data.operatorContextBody
import de.totec.doppel.data.operatorContextForModel
import de.totec.doppel.data.db.ChatKind
import de.totec.doppel.data.db.CHAT_INJECTION_MESSAGE_TYPE
import de.totec.doppel.data.db.ChatMemoryRecord
import de.totec.doppel.data.db.ChatRecord
import de.totec.doppel.data.db.MessageDeliveryState
import de.totec.doppel.data.db.MessageDirection
import de.totec.doppel.data.db.MessageRecord
import de.totec.doppel.data.db.LatestMessagePreview
import de.totec.doppel.data.db.PERSONA_SWITCH_MESSAGE_TYPE
import de.totec.doppel.data.db.SCHEDULED_FOLLOW_UP_MESSAGE_TYPE
import de.totec.doppel.engine.ChatActivity
import de.totec.doppel.engine.LiveTurn
import de.totec.doppel.engine.MemoryWork
import de.totec.doppel.integration.RuntimeBridgeControl
import de.totec.doppel.integration.ProactiveStateMetadataCodec
import de.totec.doppel.integration.chatOverride
import de.totec.doppel.integration.sameChatIdentity
import de.totec.doppel.integration.storedChatAliases
import de.totec.doppel.runtime.RuntimePhase
import de.totec.doppel.runtime.RuntimeStateStore
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.SettingsCatalogs
import de.totec.doppel.ui.ChatDetail
import de.totec.doppel.ui.ChatEntry
import de.totec.doppel.ui.ChatRow
import de.totec.doppel.ui.ChatSettings
import de.totec.doppel.ui.ChatsController
import de.totec.doppel.ui.LinkPowerStatus
import de.totec.doppel.ui.MediaPlaceholder
import de.totec.doppel.ui.MediaPlaceholderKind
import de.totec.doppel.ui.UiScheduledFollowUp
import java.io.Closeable
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The chat surfaces, read from the same database the bot writes to.
 *
 * Deliberately a second controller rather than more fields on [ProductionAppController]: that one
 * projects a hundred settings and an activity log, and it rebuilds all of it on every change. A
 * conversation is read far more often and has to stay cheap, so it has its own reload path and its
 * own idle rules — nothing here runs while no screen is collecting.
 *
 * There is no change feed on the database, so freshness comes from two signals that already exist:
 * the activity revision the runtime bumps after every committed log row (which every turn produces
 * several of) and the live stage feed. Between them, anything the bot does reaches the screen
 * within one debounce window; nothing polls on a timer.
 */
class ProductionChatsController(
    private val graph: BotAppGraph,
) : ChatsController, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = graph.repository
    private val overrides = ChatOverrideStore(repository)
    private val roster = MutableStateFlow<List<RosterEntry>>(emptyList())
    private val openChatId = MutableStateFlow<String?>(null)
    private val mutableDetail = MutableStateFlow<ChatDetail?>(null)
    private val mutableLoadError = MutableStateFlow<String?>(null)
    private val mutableOperationError = MutableStateFlow<String?>(null)
    private val mutableRows = MutableStateFlow<List<ChatRow>>(emptyList())
    private val mutableUptime = MutableStateFlow<Long?>(null)
    private val mutableBindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val reload = Mutex()
    private val detailLoadGeneration = AtomicLong(0L)

    /**
     * Highest generation whose result actually reached the screen.
     *
     * Freshness used to be decided by "am I still the newest load that *started*", which quietly
     * dropped a result whenever a newer load had begun — including when that newer load was then
     * cancelled, which the activity-revision collector does on every new revision with
     * `collectLatest`. The screen then held whatever it had until something else happened to
     * reload it, which after a long import is "until the app is restarted". Publishing against the
     * newest load that *finished* keeps the same last-writer-wins ordering without a cancelled load
     * being able to take a live one down with it.
     */
    private val detailPublishedGeneration = AtomicLong(0L)

    override val rows: StateFlow<List<ChatRow>> = mutableRows.asStateFlow()
    override val detail: StateFlow<ChatDetail?> = mutableDetail.asStateFlow()
    override val loadError: StateFlow<String?> = mutableLoadError.asStateFlow()
    override val operationError: StateFlow<String?> = mutableOperationError.asStateFlow()
    override val live: StateFlow<LiveTurn?> = graph.chatActivity.live
    override val memoryWork: StateFlow<List<MemoryWork>> = graph.memoryWork.inFlight
    override val uptimeMs: StateFlow<Long?> = mutableUptime.asStateFlow()
    override val bindings: StateFlow<Map<String, String>> = mutableBindings.asStateFlow()

    /**
     * Straight off the graph feed, combined rather than stored: the runtime is the only
     * writer, and a copy here could only be a staler version of what it already says.
     */
    override val linkPower: StateFlow<LinkPowerStatus> =
        combine(graph.linkPower.state, graph.linkPower.wakeAtMs) { state, wakeAt ->
            LinkPowerStatus(state, wakeAt)
        }.stateIn(scope, SharingStarted.Eagerly, LinkPowerStatus())

    init {
        scope.launch {
            combine(
                mutableRows.subscriptionCount,
                mutableBindings.subscriptionCount,
            ) { rowCollectors, bindingCollectors ->
                rowCollectors > 0 || bindingCollectors > 0
            }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (!observed) return@collectLatest
                    coroutineScope {
                        loadRoster()
                        // This collector is a child of the observed subscription. Settings only
                        // collects identity bindings; the chat list additionally collects rows.
                        // Either view gets fresh aliases without keeping roster DB work alive once
                        // both disappear.
                        graph.controls.activityRevision.drop(1).collectLatest {
                            delay(REFRESH_DEBOUNCE_MS)
                            loadRoster()
                            openChatId.value?.let { chatId -> loadDetail(chatId) }
                        }
                    }
                }
        }
        scope.launch {
            mutableRows.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (!observed) return@collectLatest
                    combine(roster, graph.chatActivity.chats) { entries, activity ->
                        entries.map { it.toRow(activity[it.chatId]) }
                    }.collect(mutableRows::emit)
                }
        }
        scope.launch {
            combine(
                RuntimeStateStore.state,
                mutableUptime.subscriptionCount,
            ) { runtime, collectors ->
                runtime.onlineSince.takeIf {
                    runtime.phase == RuntimePhase.ONLINE && collectors > 0
                }
            }.distinctUntilChanged()
                .collectLatest { since ->
                    if (since == null) {
                        mutableUptime.value = null
                    } else {
                        // A clock, not a stopwatch: it advances only while a visible screen asks
                        // for it. Settings/list disposal cancels the loop immediately.
                        while (isActive) {
                            mutableUptime.value =
                                (System.currentTimeMillis() - since).coerceAtLeast(0L)
                            delay(UPTIME_TICK_MS)
                        }
                    }
                }
        }
    }

    override fun openChat(chatId: String) {
        openChatId.value = chatId
        mutableDetail.value = null
        mutableLoadError.value = null
        scope.launch { loadDetail(chatId) }
    }

    override fun closeChat() {
        openChatId.value = null
        detailLoadGeneration.incrementAndGet()
        mutableDetail.value = null
        mutableLoadError.value = null
    }

    override fun clearOperationError() {
        mutableOperationError.value = null
    }

    override fun createPendingChat(phoneNumber: String) {
        val digits = phoneNumber.digits()
        if (digits == null || digits.length !in 6..15) {
            mutableOperationError.value = "Enter a valid international phone number."
            return
        }
        val chatId = "$digits$PHONE_DOMAIN"
        scope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                repository.upsertChat(
                    (repository.getChat(chatId) ?: ChatRecord(chatId = chatId, createdAt = now)).copy(
                        kind = ChatKind.DIRECT,
                        displayName = "+$digits",
                        updatedAt = now,
                    ),
                )
                loadRoster()
            }.onFailure {
                mutableOperationError.value = "The contact could not be added."
            }
        }
    }

    override fun deleteChat(chatId: String) {
        scope.launch {
            val deleted =
                runCatching {
                    val chats = repository.listChats(includeArchived = true, limit = ROSTER_LIMIT)
                    val seed = chats.firstOrNull { it.chatId == chatId } ?: return@runCatching 0
                    val identities = connectedChatIdentityGroup(seed, chats)
                    val ids = identities.map(ChatRecord::chatId)

                    // Stop already-armed work before removing its durable rows. A genuinely new
                    // inbound message arriving after this point may recreate the chat, which is the
                    // honest behaviour; old queued work must not recreate it by itself.
                    ids.forEach { id ->
                        overrides.put(id, ChatOverrides.PAUSED, "true")
                        runCatching { RuntimeBridgeControl.chatSettingsChanged(id) }
                    }
                    graph.chatActivity.forget(ids)
                    ids.forEach { id ->
                        graph.settings.setContactPersona(id, null)
                        graph.settings.setContactProactiveLevel(id, null)
                        runCatching { graph.approvedMedia.clearSentHistory(id) }
                    }
                    repository.deleteChats(ids)
                }.getOrElse {
                    mutableOperationError.value = "The chat could not be deleted."
                    return@launch
                }
            if (deleted <= 0) {
                mutableOperationError.value = "The chat no longer exists."
                loadRoster()
                return@launch
            }
            if (openChatId.value == chatId) closeChat()
            mutableOperationError.value = null
            loadRoster()
        }
    }

    override fun inject(chatId: String, text: String) {
        val note = text.trim().takeIf(String::isNotEmpty) ?: return
        val persona = graph.settings.snapshot().effectivePersona(chatId)
        val conversationKey = "$chatId#$persona"
        launchChatMutation(chatId, "The context note could not be added.") {
                    repository.storeMessages(
                        listOf(
                            MessageRecord(
                                providerMessageId = "$INJECTION_ID_PREFIX${UUID.randomUUID()}",
                                chatId = chatId,
                                conversationKey = conversationKey,
                                direction = MessageDirection.SYSTEM,
                                messageType = CHAT_INJECTION_MESSAGE_TYPE,
                                // Never named "injection" towards the model: see OperatorContext.
                                body = operatorContextForModel(note),
                                occurredAt = System.currentTimeMillis(),
                                deliveryState = MessageDeliveryState.UNKNOWN,
                                fromAdmin = true,
                                metadataJson =
                                    JSONObject()
                                        .put("conversationKey", conversationKey)
                                        .put("persona", persona)
                                        .toString(),
                            ),
                        ),
                    ) > 0
        }
    }

    override fun importWhatsAppChat(chatId: String, uri: Uri) {
        launchChatMutation(
            chatId = chatId,
            failureMessage = "The WhatsApp chat could not be imported.",
            includeFailureReason = true,
        ) {
            val chat = repository.getChat(chatId) ?: error("Chat no longer exists")
            val persona = graph.settings.snapshot().effectivePersona(chatId)
            val conversationKey = "$chatId#$persona"
            val memorySession = RuntimeBridgeControl.beginImportedMemory(chatId, persona)
            try {
                // The mandatory engine gate is installed by beginImportedMemory. Waiting here also
                // drains an active turn and older cadence refresh before temporary rows are staged.
                memorySession.awaitReady()
                val export = readWhatsAppExportTail(uri)
                val personaName =
                    SettingsCatalogs.personas.firstOrNull { it.key == persona }?.label
                val imported =
                    WhatsAppChatImportParser.parseAutomatically(
                        source = export.text,
                        botAccountCandidates =
                            listOfNotNull(
                                graph.controls.metrics.value.accountName,
                                personaName,
                            ),
                        remoteParticipantCandidates =
                            listOfNotNull(
                                chat.title().takeUnless { chat.kind == ChatKind.GROUP },
                                export.remoteParticipant,
                            ),
                    )
                val batchId = UUID.randomUUID().toString()
                val firstOccurredAt = System.currentTimeMillis() - imported.size
                val records =
                    imported.mapIndexed { index, message ->
                        MessageRecord(
                            providerMessageId = "import:$batchId:$index",
                            chatId = chatId,
                            conversationKey = conversationKey,
                            senderId = message.sender,
                            direction =
                                if (message.sentByBot) {
                                    MessageDirection.OUTBOUND
                                } else {
                                    MessageDirection.INBOUND
                                },
                            messageType = "text",
                            body = message.body,
                            occurredAt = firstOccurredAt + index,
                            deliveryState =
                                if (message.sentByBot) {
                                    MessageDeliveryState.SENT
                                } else {
                                    MessageDeliveryState.RECEIVED
                                },
                            metadataJson =
                                JSONObject()
                                    .put("imported", true)
                                    .put("exportedTimestamp", message.exportedTimestamp)
                                    .put("exportedSender", message.sender)
                                    .put("conversationKey", conversationKey)
                                    .put("persona", persona)
                                    .toString(),
                        )
                    }
                var memoryCreated = false
                try {
                    check(repository.storeMessages(records) == records.size) {
                        "The imported history could not be stored completely"
                    }
                    memorySession.refresh()
                    memoryCreated = true
                } finally {
                    // Once memory owns the export, the timeline retains only enough recent context
                    // to keep the imported relationship legible. A failed memory call rolls the
                    // entire staging batch back instead of leaving a partial import.
                    repository.deleteMessages(
                        if (memoryCreated) {
                            records.dropLast(IMPORTED_TIMELINE_MESSAGES).map { it.providerMessageId }
                        } else {
                            records.map { it.providerMessageId }
                        },
                    )
                }
                memoryCreated
            } finally {
                memorySession.close()
            }
        }
    }

    private fun readWhatsAppExportTail(uri: Uri): SelectedWhatsAppExport {
        val resolver = graph.applicationContext.contentResolver
        val displayName =
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }?.getString(0)
            }
        val stream =
            resolver.openInputStream(uri)
                ?: error("The selected text file could not be opened")
        return InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            val capacity = WhatsAppChatImportParser.MAX_SOURCE_CHARACTERS
            val tail = CharArray(capacity)
            val buffer = CharArray(8_192)
            var writeIndex = 0
            var retained = 0
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                for (index in 0 until read) {
                    tail[writeIndex] = buffer[index]
                    writeIndex = (writeIndex + 1) % capacity
                    retained = (retained + 1).coerceAtMost(capacity)
                }
            }
            val ordered = CharArray(retained)
            val start = (writeIndex - retained + capacity) % capacity
            for (index in 0 until retained) {
                ordered[index] = tail[(start + index) % capacity]
            }
            SelectedWhatsAppExport(
                text = String(ordered),
                remoteParticipant = displayName?.exportedRemoteParticipant(),
            )
        }
    }

    override fun editMemory(chatId: String, memoryId: String, text: String) {
        launchChatMutation(chatId, "The memory edit could not be saved.") {
                var saved = false
                for (attempt in 0 until MANUAL_MEMORY_CAS_ATTEMPTS) {
                    val current = repository.getChatMemory(memoryId) ?: break
                    val updated =
                        current.copy(
                            summary = text.trim(),
                            revision = current.revision + 1,
                            updatedAt = System.currentTimeMillis(),
                        )
                    if (repository.compareAndSwapChatMemory(current.revision, updated)) {
                        saved = true
                        break
                    }
                }
                saved
        }
    }

    override fun deleteInjection(chatId: String, injectionId: Long) {
        val persona = graph.settings.snapshot().effectivePersona(chatId)
        val conversationKey = "$chatId#$persona"
        launchChatMutation(chatId, "The context note could not be deleted.") {
            repository.deleteInjection(injectionId, conversationKey)
        }
    }

    override fun deletePersonaChat(chatId: String) {
        val persona = graph.settings.snapshot().effectivePersona(chatId)
        val conversationKey = "$chatId#$persona"
        launchChatMutation(chatId, "The persona chat could not be deleted.") {
            // Not a bare row delete: proactive counters, sent-image history and a persona memory
            // with nothing left to describe all used to survive this and made the next turn read
            // as a continuation of the conversation that was just wiped.
            resetConversation(repository, graph.approvedMedia, conversationKey)
            true
        }
    }

    override fun setChatOverride(chatId: String, key: String, value: String?) {
        launchChatMutation(chatId, "The chat setting could not be saved.") {
                    // Persona and proactivity are not scoped settings — they each had their own
                    // per-contact store long before this screen existed, and writing a second copy
                    // into the chat scope would give every one of them two answers.
                    when (key) {
                        PERSONA_KEY ->
                            updatePersonaWithTimelineMarker(chatId, value)

                        PROACTIVE_KEY ->
                            graph.settings.setContactProactiveLevel(chatId, value?.toIntOrNull())

                        else -> {
                            overrides.put(chatId, key, value)
                            if (
                                key == ChatOverrides.REPLY_PRESET ||
                                    key == ChatOverrides.PAUSED
                            ) {
                                // A live Human -> Instant change is a scheduling command, not just
                                // a value for the next message. Wake this chat's existing batch now.
                                RuntimeBridgeControl.chatSettingsChanged(chatId)
                            }
                        }
                    }
            true
        }
    }

    /**
     * The status line was tapped. Nothing more than a note to the link power loop —
     * the socket, the locks and the alarm are all its business, and a UI that reached
     * past it would be a second scheduler racing the first.
     */
    override fun keepLinkAwake() {
        graph.linkPower.requestWake()
    }

    override fun sleepLinkNow() {
        graph.linkPower.requestSleep()
    }

    private fun launchChatMutation(
        chatId: String,
        failureMessage: String,
        includeFailureReason: Boolean = false,
        operation: suspend () -> Boolean,
    ) {
        scope.launch {
            var failureReason: String? = null
            val succeeded =
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failureReason = failure.message?.trim()?.take(240)
                    false
                }
            if (!succeeded) {
                mutableOperationError.value =
                    if (includeFailureReason && !failureReason.isNullOrBlank()) {
                        "$failureMessage $failureReason"
                    } else {
                        failureMessage
                    }
                return@launch
            }
            mutableOperationError.value = null
            loadDetail(chatId)
        }
    }

    private data class SelectedWhatsAppExport(
        val text: String,
        val remoteParticipant: String?,
    )

    private fun String.exportedRemoteParticipant(): String? {
        val stem = substringBeforeLast('.', this).trim()
        val prefixes = listOf("WhatsApp Chat with ", "WhatsApp Chat mit ")
        return prefixes.firstNotNullOfOrNull { prefix ->
            stem.takeIf { it.startsWith(prefix, ignoreCase = true) }
                ?.drop(prefix.length)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }
    }

    override fun close() {
        scope.cancel()
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private suspend fun loadRoster() {
        withContext(Dispatchers.IO) {
            reload.withLock {
                val now = System.currentTimeMillis()
                val chats =
                    try {
                        repository.listChats(limit = ROSTER_LIMIT)
                    } catch (_: Exception) {
                        mutableOperationError.value = "The chat list could not be refreshed."
                        return@withLock
                    }
                val latestMessages =
                    try {
                        repository.listLatestMessagePreviews(chats.map(ChatRecord::chatId))
                    } catch (_: Exception) {
                        mutableOperationError.value = "The chat previews could not be refreshed."
                        return@withLock
                    }
                val followUps =
                    repository.listProactiveStates(limit = ROSTER_LIMIT)
                        .associate { state ->
                            state.chatId to ProactiveStateMetadataCodec.decode(state).followUp
                        }
                val visibleChatGroups =
                    chats.fold(ArrayList<MutableList<ChatRecord>>()) { groups, chat ->
                        groups.apply {
                            if (chat.kind == ChatKind.GROUP) {
                                add(mutableListOf(chat))
                            } else {
                                firstOrNull { group -> group.any { sameChatIdentity(it, chat) } }
                                    ?.add(chat)
                                    ?: add(mutableListOf(chat))
                            }
                        }
                    }
                roster.value =
                    visibleChatGroups.map { group ->
                        val chat =
                            group.maxByOrNull { it.lastMessageAt ?: Long.MIN_VALUE } ?: group.first()
                        val last = latestMessages[chat.chatId]
                        val followUp =
                            group.asSequence()
                                .mapNotNull { candidate -> followUps[candidate.chatId] }
                                .minByOrNull { it.nextAttemptAtMs }
                        RosterEntry(
                            chatId = chat.chatId,
                            title = chat.title(),
                            isGroup = chat.kind == ChatKind.GROUP,
                            preview = last?.previewText().orEmpty(),
                            previewFromBot = last?.direction == MessageDirection.OUTBOUND,
                            previewAtMs = last?.occurredAt ?: chat.lastMessageAt ?: chat.createdAt,
                            quiet = now - (chat.lastMessageAt ?: chat.createdAt) > QUIET_AFTER_MS,
                            scheduledFollowUpAtMs = followUp?.nextAttemptAtMs,
                            scheduledFollowUpNote = followUp?.note,
                        )
                    }
                mutableBindings.value =
                    buildMap {
                        chats.forEach { chat ->
                            val title = chat.title()
                            (listOf(chat.chatId) + chat.aliases()).forEach { identity ->
                                identity.digits()?.let { put(it, title) }
                            }
                            // A group is joined by its subject, not by a number, so that is the key
                            // an entry typed on the groups list has to be found under.
                            if (chat.kind == ChatKind.GROUP) {
                                chat.subject?.trim()?.lowercase()
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { put(it, title) }
                            }
                        }
                    }
            }
        }
    }

    private suspend fun loadDetail(chatId: String) {
        val generation = detailLoadGeneration.incrementAndGet()
        val loaded =
            try {
                withContext(Dispatchers.IO) {
                val chat = repository.getChat(chatId) ?: error("chat_not_found")
                val settings = graph.settings.snapshot()
                // Every key on this screen has to be the one the engine actually writes under, and
                // deriving it takes the chat's aliases twice over. A per-contact persona is filed
                // under the phone number the user typed while the chat runs under its LID, so the
                // raw map lookup this used to do found nothing and silently fell back to the global
                // persona — building a conversation key no reply had ever been stored against, and
                // leaving the screen showing a memory that "disappeared" moments after it was
                // written. `chatOverride` is the same resolution the engine uses.
                val aliases = storedChatAliases(chat.metadataJson)
                val persona = chatOverride(settings.personaContactOverrides, chatId, aliases)
                val globalPersona = settings.effectivePersona(chatId)
                val stored = overrides.all(chatId)
                val conversationKey = "$chatId#${persona ?: globalPersona}"
                val messages =
                    (
                            repository.listConversationMessages(
                                conversationKey = conversationKey,
                                limit = MESSAGE_WINDOW,
                            ) +
                                repository.listMessagesByType(
                                    chatId = chatId,
                                    messageType = PERSONA_SWITCH_MESSAGE_TYPE,
                                    limit = MAX_PERSONA_SWITCH_MARKERS,
                                ).filter { it.belongsToConversation(conversationKey) }
                        ).distinctBy(MessageRecord::databaseId)
                            .sortedWith(
                                compareByDescending<MessageRecord> { it.occurredAt }
                                    .thenByDescending { it.databaseId },
                            )
                // The same candidate set the engine reads memory under. A chat that changed address
                // keeps writing under whichever key was current, so looking only under today's key
                // can find an empty row for a conversation that has a summary sitting one alias
                // over. Candidates stay in engine order — the chat's own id first — so the primary
                // key still wins when both hold something.
                val memoryKeys = (listOf(chatId) + aliases).distinct().map {
                    "$it#${persona ?: globalPersona}"
                }
                val memories =
                    memoryKeys
                        .firstNotNullOfOrNull { key ->
                            repository.listChatMemoryHistory(key).takeIf(List<*>::isNotEmpty)
                        }
                        .orEmpty()
                val memory =
                    memories.lastOrNull()
                        ?: memoryKeys.firstNotNullOfOrNull(repository::getChatMemory)
                val budget =
                    settings.integer(BotSettingKeys.MEMORY_CHAR_LIMIT).takeIf { it > 0 } ?: 0
                val title = chat.title()
                val scheduledFollowUp =
                    repository.getProactiveState(chatId)
                        ?.let(ProactiveStateMetadataCodec::decode)
                        ?.followUp
                        ?.takeIf { it.conversationKey == conversationKey }
                ChatDetail(
                    chatId = chatId,
                    title = title,
                    contactNumber = chat.phoneNumber(),
                    isGroup = chat.kind == ChatKind.GROUP,
                    initial = title.initial(),
                    accent = chatId.accent(),
                    entries =
                        entries(
                            messages,
                            if (memories.isEmpty()) listOfNotNull(memory) else memories,
                            chat.kind == ChatKind.GROUP,
                            conversationKey,
                        ),
                    settings =
                        ChatSettings(
                            paused = stored[ChatOverrides.PAUSED]?.toBooleanStrictOrNull() ?: false,
                            persona = persona,
                            globalPersona = globalPersona,
                            replyPreset = stored[ChatOverrides.REPLY_PRESET],
                            globalReplyPreset =
                                settings.text(BotSettingKeys.REPLY_PRESET),
                            proactiveLevel = settings.proactiveContactOverrides[chatId],
                            globalProactiveLevel =
                                settings.integer(BotSettingKeys.PROACTIVE_LEVEL),
                            groupTrigger = stored[ChatOverrides.GROUP_TRIGGER],
                            globalGroupTrigger =
                                settings.appText(
                                    de.totec.doppel.settings.AppSettingKeys.GROUP_TRIGGER,
                                ),
                        ),
                    scheduledFollowUp =
                        scheduledFollowUp?.let {
                            UiScheduledFollowUp(
                                scheduledAtMs = it.scheduledAtMs,
                                nextAttemptAtMs = it.nextAttemptAtMs,
                                note = it.note,
                            )
                        },
                    memoryCharacters = memory?.summary?.length ?: 0,
                    memoryBudget = budget,
                    loading = false,
                )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (openChatId.value == chatId && claimPublish(generation)) {
                    mutableDetail.value = null
                    mutableLoadError.value = "Couldn’t open this chat. Tap to retry."
                }
                return
            }
        // Guard against a slow read landing after the user has already left: the screen it was
        // loaded for is gone, and publishing it would reopen a chat nobody is looking at.
        if (openChatId.value == chatId && claimPublish(generation)) {
            mutableDetail.value = loaded
            mutableLoadError.value = null
        }
    }

    /** True when [generation] is newer than anything published so far, and claims the slot if so. */
    private fun claimPublish(generation: Long): Boolean {
        while (true) {
            val published = detailPublishedGeneration.get()
            if (generation <= published) return false
            if (detailPublishedGeneration.compareAndSet(published, generation)) return true
        }
    }

    /**
     * Turns stored rows into what the chat screen draws.
     *
     * The database holds a flat, newest-first list of messages, all of them labelled for the model
     * ("User sent: …"). Screens want the opposite: oldest first, labels off, day breaks in between,
     * and the memory that was written for this conversation sitting where it belongs — after the
     * message it last read.
     */
    private fun entries(
        newestFirst: List<MessageRecord>,
        memories: List<ChatMemoryRecord>,
        isGroup: Boolean,
        conversationKey: String,
    ): List<ChatEntry> {
        val ordered =
            newestFirst.asReversed().filter { message ->
                message.messageType != SCHEDULED_FOLLOW_UP_MESSAGE_TYPE &&
                    message.belongsToConversation(conversationKey)
            }
        // A chat can retain many memory revisions. Index their anchors once instead of scanning
        // the entire revision list again for every visible message.
        val memoriesByAnchor = memories.groupBy(ChatMemoryRecord::lastProviderMessageId)
        val result = ArrayList<ChatEntry>(ordered.size + 8)
        var previousDay: LocalDate? = null
        ordered.forEach { message ->
            val day = message.occurredAt.toLocalDate()
            if (day != previousDay) {
                result += ChatEntry.DayBreak(message.occurredAt, day.label())
                previousDay = day
            }
            if (message.messageType == CHAT_INJECTION_MESSAGE_TYPE) {
                result +=
                    ChatEntry.Injection(
                        id = message.databaseId,
                        atMs = message.occurredAt,
                        text = operatorContextBody(message.body.orEmpty()),
                    )
                memoriesByAnchor[message.providerMessageId]
                    .orEmpty()
                    .forEach { result += it.toEntry() }
                return@forEach
            }
            if (message.messageType == PERSONA_SWITCH_MESSAGE_TYPE) {
                val metadata = message.metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                result +=
                    ChatEntry.PersonaSwitch(
                        id = message.databaseId,
                        atMs = message.occurredAt,
                        from = personaLabel(metadata?.optString("fromPersona").orEmpty()),
                        to = personaLabel(metadata?.optString("toPersona").orEmpty()),
                    )
                return@forEach
            }
            val fromBot = message.direction == MessageDirection.OUTBOUND
            val speaker = if (fromBot) BOT_SPEAKER else message.senderId.orEmpty()
            val sender = message.senderName().takeIf { isGroup && !fromBot }
            result +=
                ChatEntry.Bubble(
                    id = message.databaseId,
                    atMs = message.occurredAt,
                    fromBot = fromBot,
                    text = message.bubbleText(),
                    senderName = sender,
                    delivery = message.deliveryLabel(),
                    media = message.media(),
                    speakerKey = speaker,
                )
            // A memory write is anchored to the last message it read, which is the only honest
            // place to draw it: everything above is in the summary, everything below is not.
            memoriesByAnchor[message.providerMessageId]
                .orEmpty()
                .forEach { result += it.toEntry() }
        }
        // A memory whose anchor has already been pruned still describes this conversation, so it
        // goes at the top rather than vanishing with the message it happened to point at.
        val visibleMemoryIds = result.filterIsInstance<ChatEntry.Memory>().mapTo(HashSet()) { it.id }
        memories
            .filterNot { "${it.conversationKey}@${it.revision}" in visibleMemoryIds }
            .asReversed()
            .forEach { result.add(0, it.toEntry()) }
        // Shape tails belong on the final bubble in a run, while compact top spacing belongs on
        // every bubble after the first. A memory/injection/day marker intentionally breaks a run.
        return result.mapIndexed { index, entry ->
            if (entry !is ChatEntry.Bubble) return@mapIndexed entry
            val previous = result.getOrNull(index - 1) as? ChatEntry.Bubble
            val next = result.getOrNull(index + 1) as? ChatEntry.Bubble
            entry.copy(
                stacked = previous?.speakerKey == entry.speakerKey,
                continuesAfter = next?.speakerKey == entry.speakerKey,
            )
        }
    }

    private fun MessageRecord.belongsToConversation(conversationKey: String): Boolean {
        this.conversationKey?.let { return it == conversationKey }
        val metadata = metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
        metadata.optString("conversationKey").takeIf(String::isNotBlank)?.let {
            return it == conversationKey
        }
        return metadata.optString("persona").takeIf(String::isNotBlank) ==
            conversationKey.substringAfterLast('#', "")
    }

    private fun ChatMemoryRecord.toEntry() =
        ChatEntry.Memory(
            id = "$conversationKey@$revision",
            conversationKey = conversationKey,
            atMs = updatedAt,
            persona = conversationKey.substringAfterLast('#', ""),
            revision = revision,
            characters = summary.length,
            budget = 0,
            text = summary,
        )

    // ── Presentation helpers ─────────────────────────────────────────────────

    private data class RosterEntry(
        val chatId: String,
        val title: String,
        val isGroup: Boolean,
        val preview: String,
        val previewFromBot: Boolean,
        val previewAtMs: Long,
        val quiet: Boolean,
        val scheduledFollowUpAtMs: Long?,
        val scheduledFollowUpNote: String?,
    ) {
        fun toRow(activity: ChatActivity?) =
            ChatRow(
                chatId = chatId,
                title = title,
                isGroup = isGroup,
                initial = title.initial(),
                accent = chatId.accent(),
                preview = preview,
                previewFromBot = previewFromBot,
                previewAtMs = previewAtMs,
                activity = activity,
                scheduledFollowUpAtMs = scheduledFollowUpAtMs,
                scheduledFollowUpNote = scheduledFollowUpNote,
                quiet = quiet,
            )
    }

    /**
     * Finds the full transitive PN/LID identity component for a visible chat.
     *
     * A contact can briefly have more than two stored rows while aliases arrive over several
     * events. Expanding until no row joins avoids making the result depend on database order.
     */
    private fun connectedChatIdentityGroup(
        seed: ChatRecord,
        chats: List<ChatRecord>,
    ): List<ChatRecord> {
        if (seed.kind == ChatKind.GROUP) return listOf(seed)
        val connected = linkedSetOf(seed)
        var changed: Boolean
        do {
            changed = false
            chats.forEach { candidate ->
                if (
                    candidate.kind != ChatKind.GROUP &&
                    candidate !in connected &&
                    connected.any { sameChatIdentity(it, candidate) }
                ) {
                    connected += candidate
                    changed = true
                }
            }
        } while (changed)
        return connected.toList()
    }

    /**
     * What to call this chat.
     *
     * A group has a subject. A person has a phone number — which is *not* the chat id: the chat is
     * usually keyed by a LID whose digits belong to no phone anywhere. The number, when it is
     * known at all, is in the aliases the chat recorded from the addresses its messages arrived
     * under, so that is where it is read from.
     */
    private fun ChatRecord.title(): String {
        if (kind == ChatKind.GROUP) {
            subject?.takeIf(String::isNotBlank)?.let { return it }
            displayName?.takeIf(String::isNotBlank)?.let { return it }
            return "Group ${chatId.substringBefore('@').takeLast(6)}"
        }
        displayName?.takeIf(String::isNotBlank)?.let { return it }
        phoneNumber()?.let { return it }
        return chatId.substringBefore('@')
    }

    /** The contact's phone number, preferring a `@s.whatsapp.net` address over an opaque LID. */
    private fun ChatRecord.phoneNumber(): String? {
        val candidates =
            buildList {
                add(chatId)
                addAll(aliases())
            }
        return candidates.firstOrNull { it.endsWith(PHONE_DOMAIN) }
            ?.substringBefore('@')
            ?.takeIf { it.length in 6..20 && it.all(Char::isDigit) }
            ?.let { "+$it" }
    }

    private fun ChatRecord.aliases(): List<String> {
        val metadata = metadataJson ?: return emptyList()
        val array =
            runCatching { JSONObject(metadata).optJSONArray("aliases") }.getOrNull()
                ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private suspend fun updatePersonaWithTimelineMarker(chatId: String, personaId: String?) {
        val global = graph.settings.snapshot().text(BotSettingKeys.PERSONALITY)
        val before = graph.settings.snapshot().effectivePersona(chatId)
        graph.settings.setContactPersona(chatId, personaId)
        val after = personaId ?: global
        if (before == after) return
        val occurredAt = System.currentTimeMillis()
        repository.storeMessages(
            listOf(
                MessageRecord(
                    providerMessageId = "persona-switch:${UUID.randomUUID()}",
                    chatId = chatId,
                    conversationKey = "$chatId#$after",
                    direction = MessageDirection.SYSTEM,
                    messageType = PERSONA_SWITCH_MESSAGE_TYPE,
                    body = "${personaLabel(before)} → ${personaLabel(after)}",
                    occurredAt = occurredAt,
                    deliveryState = MessageDeliveryState.UNKNOWN,
                    fromAdmin = true,
                    metadataJson =
                        JSONObject()
                            .put("conversationKey", "$chatId#$after")
                            .put("fromPersona", before)
                            .put("toPersona", after)
                            .toString(),
                ),
            ),
        )
        // A persona switch is an explicit operator action, so one matching profile-name update is
        // both useful and unsurprising to WhatsApp. No polling or contact lookup is involved.
        runCatching { RuntimeBridgeControl.setPushName(personaLabel(after)) }
    }

    private fun personaLabel(id: String): String =
        SettingsCatalogs.personas.firstOrNull { it.key == id }?.label ?: id.ifBlank { "Unknown" }

    private fun MessageRecord.senderName(): String? =
        metadataJson
            ?.let { runCatching { JSONObject(it).optString("senderName") }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: senderId?.substringBefore('@')?.takeLast(4)?.let { "…$it" }

    /** The bubble as a person would read it: the model's history label is not part of the message. */
    private fun MessageRecord.bubbleText(): String {
        val raw = body.orEmpty()
        val stripped = HistoryLabelGuard.stripLeadingLabel(raw.withoutQuoteLine().ifBlank { raw })
        return if (media() != null) "" else stripped
    }

    /**
     * Drops a standalone quote line, which is the whole first line when there is one.
     *
     * Rows written before the reference moved onto the sender's own line still carry the quoted
     * message above the text. Without this the app shows the contact their own words back — the
     * very thing that reading looked like a bug and was one.
     */
    private fun String.withoutQuoteLine(): String =
        if (startsWith("In reply to") || startsWith("You replied to")) {
            substringAfter('\n', "")
        } else {
            this
        }

    private fun LatestMessagePreview.previewText(): String {
        val mediaWord =
            when (messageType) {
                "image" -> MediaPlaceholderKind.IMAGE.word
                "audio" -> MediaPlaceholderKind.VOICE.word
                "video" -> MediaPlaceholderKind.VIDEO.word
                "document" -> MediaPlaceholderKind.DOCUMENT.word
                "sticker" -> MediaPlaceholderKind.STICKER.word
                "unknown" -> MediaPlaceholderKind.FILE.word
                else -> null
            }
        if (mediaWord != null) return mediaWord
        val raw = body.orEmpty()
        return HistoryLabelGuard
            .stripLeadingLabel(raw.withoutQuoteLine().ifBlank { raw })
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
    }

    /**
     * Received media, as far as it can honestly be shown.
     *
     * Nothing was kept but the description the media model produced, so the bubble is a labelled
     * placeholder with that description underneath — which is also exactly what the bot had.
     */
    private fun MessageRecord.media(): MediaPlaceholder? {
        val kind =
            when (messageType) {
                "image" -> MediaPlaceholderKind.IMAGE
                "audio" -> MediaPlaceholderKind.VOICE
                "video" -> MediaPlaceholderKind.VIDEO
                "document" -> MediaPlaceholderKind.DOCUMENT
                "sticker" -> MediaPlaceholderKind.STICKER
                "unknown" -> MediaPlaceholderKind.FILE
                else ->
                    when {
                        direction != MessageDirection.OUTBOUND -> return null
                        body.orEmpty().startsWith("You sent a voice note") -> MediaPlaceholderKind.VOICE
                        body.orEmpty().startsWith("You sent an image") -> MediaPlaceholderKind.IMAGE
                        body.orEmpty().startsWith("You sent a video") -> MediaPlaceholderKind.VIDEO
                        body.orEmpty().startsWith("You sent a document") -> MediaPlaceholderKind.DOCUMENT
                        body.orEmpty().startsWith("You sent a sticker") -> MediaPlaceholderKind.STICKER
                        body.orEmpty().startsWith("You sent a file") -> MediaPlaceholderKind.FILE
                        else -> return null
                    }
            }
        // Stored as "<prefix>: <description>" — the prefix repeats the kind the placeholder
        // already shows, so only what comes after it is worth drawing.
        val description = body.orEmpty().substringAfter(": ", "").trim()
        return MediaPlaceholder(kind = kind, description = description)
    }

    private fun MessageRecord.deliveryLabel(): String? =
        when {
            direction != MessageDirection.OUTBOUND -> null
            deliveryState == MessageDeliveryState.FAILED -> "failed"
            deliveryState == MessageDeliveryState.READ -> "read"
            deliveryState == MessageDeliveryState.DELIVERED -> "delivered"
            deliveryState == MessageDeliveryState.SENT -> "sent"
            else -> null
        }

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun LocalDate.label(): String {
        val today = LocalDate.now()
        return when (this) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> format(DAY_FORMAT)
        }
    }

    private companion object {
        const val ROSTER_LIMIT = 200
        const val MESSAGE_WINDOW = 400
        const val IMPORTED_TIMELINE_MESSAGES = 10
        const val MAX_PERSONA_SWITCH_MARKERS = 100
        const val MANUAL_MEMORY_CAS_ATTEMPTS = 3
        const val REFRESH_DEBOUNCE_MS = 300L
        const val UPTIME_TICK_MS = 1_000L

        /** After this much silence a chat drops below the rule at the bottom of the list. */
        const val QUIET_AFTER_MS = 30L * 24 * 60 * 60 * 1_000

        const val PHONE_DOMAIN = "@s.whatsapp.net"
        const val BOT_SPEAKER = "bot@self"

        /** Marks the rows this app wrote by hand, so they can be told from real traffic. */
        const val INJECTION_ID_PREFIX = "inject:"

        /** Persona is stored as a chat assignment, not as a scoped setting. */
        const val PERSONA_KEY = "persona"

        /** Proactivity has its own per-contact override map in the settings repository. */
        const val PROACTIVE_KEY = "proactive"

        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    }
}

/**
 * An address reduced to the digits that identify it, or null when it holds none.
 *
 * `+1 202-555-0147`, `12025550147@s.whatsapp.net` and `001-202-555-0147` are the same person
 * written three ways; nothing but the digits survives all three. Leading zeros are dropped so a
 * locally written number matches the internationally written one.
 */
internal fun String.digits(): String? =
    substringBefore('@')
        .filter(Char::isDigit)
        .trimStart('0')
        .takeIf { it.length >= 6 }

/**
 * The one or two characters that stand for a chat in its avatar.
 *
 * A first letter is only worth anything when the name is a name. Almost none of them are: every
 * German number begins `+49`, so a roster of them was a column of identical `4`s, and an unnamed
 * group is titled `Group 704810`, so those were a column of identical `G`s. Where a long run of
 * digits exists it is the only distinguishing part, so the last two of it are used instead — the
 * same two digits a person recognises a number by.
 */
internal fun String.initial(): String {
    val trimmed = trim()
    DIGIT_RUN.findAll(trimmed).lastOrNull()?.value?.let { return it.takeLast(2) }
    return trimmed.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
}

/** Four digits or more: a phone number or a generated group id, never part of a real name. */
private val DIGIT_RUN = Regex("\\d{4,}")

/**
 * A stable colour index for a chat.
 *
 * Derived from the id rather than from the position in the list, because the list reorders on every
 * message and an avatar that changes colour when someone writes is worse than no colour at all.
 */
internal fun String.accent(): Int = (hashCode().toLong() and 0xFFFFFFFFL).rem(AVATAR_COLOURS).toInt()

internal const val AVATAR_COLOURS = 8
