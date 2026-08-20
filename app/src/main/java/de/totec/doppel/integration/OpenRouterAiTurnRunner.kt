package de.totec.doppel.integration

import android.content.Context
import de.totec.doppel.ai.ActionCommitReceipt
import de.totec.doppel.ai.AndroidSpeechSynthesizer
import de.totec.doppel.ai.AiOrchestrator
import de.totec.doppel.ai.AiToolCall
import de.totec.doppel.ai.ChatCompletionGateway
import de.totec.doppel.ai.ChatRole
import de.totec.doppel.ai.ContextSettings
import de.totec.doppel.ai.GroupContext
import de.totec.doppel.ai.HistoryLabelGuard
import de.totec.doppel.ai.HistoryMessage
import de.totec.doppel.ai.ImageReferenceInput
import de.totec.doppel.ai.MediaAnalysisPromptSettings
import de.totec.doppel.ai.ModelRole
import de.totec.doppel.ai.LocalSpeechModels
import de.totec.doppel.ai.OpenRouterReplyVerifier
import de.totec.doppel.ai.OutgoingHumanizer
import de.totec.doppel.ai.OutboundSurfaceSanitizer
import de.totec.doppel.ai.OutputSettings
import de.totec.doppel.ai.PendingAction
import de.totec.doppel.ai.PendingActionCommitter
import de.totec.doppel.ai.PersonaContext
import de.totec.doppel.ai.ReadOnlyToolExecutor
import de.totec.doppel.ai.ReasoningEffort
import de.totec.doppel.ai.RepetitionGuard
import de.totec.doppel.ai.ResolvedTurnSettings
import de.totec.doppel.ai.SamplingSettings
import de.totec.doppel.ai.SpeechClient
import de.totec.doppel.ai.SpeechMarkup
import de.totec.doppel.ai.SpeechRequest
import de.totec.doppel.ai.ToolAccessSettings
import de.totec.doppel.ai.ToolExecutionContext
import de.totec.doppel.ai.ToolExecutionResult
import de.totec.doppel.ai.ToolRegistry
import de.totec.doppel.ai.TurnContext
import de.totec.doppel.ai.LiveTokenSink
import de.totec.doppel.ai.TurnObserver
import de.totec.doppel.ai.VerificationSettings
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.ChatKind
import de.totec.doppel.data.db.ChatRecord
import de.totec.doppel.domain.MediaKind
import de.totec.doppel.engine.AiTurnRunner
import de.totec.doppel.engine.ChatActivityFeed
import de.totec.doppel.engine.MemoryWorkFeed
import de.totec.doppel.engine.MemoryWriteHold
import de.totec.doppel.engine.ChatStage
import de.totec.doppel.engine.ConversationMemoryPolicy
import de.totec.doppel.engine.TraceKind
import de.totec.doppel.engine.PlannedSideEffect
import de.totec.doppel.engine.ProactivePersistence
import de.totec.doppel.engine.TurnInput
import de.totec.doppel.engine.TurnOutput
import de.totec.doppel.media.ApprovedMediaAsset
import de.totec.doppel.media.ApprovedMediaAssetStore
import de.totec.doppel.media.ApprovedMediaKind
import de.totec.doppel.media.VoiceNoteEncoder
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.PersonaVoices
import de.totec.doppel.settings.SettingsCatalogs
import de.totec.doppel.settings.SettingsRepository
import de.totec.doppel.settings.TtsVoiceCatalog
import de.totec.doppel.transport.BridgeMediaClient
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ReplyTargetCandidate(
    val messageId: String,
    val searchableText: String,
)

/**
 * Resolves a WhatsApp Reply against the messages of this chat.
 *
 * The model only ever writes down the words it wants to quote — it never sees or handles a message
 * id — so the match has to survive the ways a model reproduces a sentence: different case, changed
 * spacing, dropped punctuation, an added or missing emoji, a shortened or slightly extended
 * quotation. Matching therefore falls back from containment to word overlap, and the newest message
 * wins when several fit.
 *
 * What it deliberately does not do is guess. A snippet that matches nothing yields no quote at all,
 * so the reply is sent as a plain message instead of pointing at an arbitrary message the model
 * never meant.
 */
internal fun resolveReplyTargetId(
    candidates: List<ReplyTargetCandidate>,
    sourceSnippet: String?,
): String? {
    val needle = normalizeForQuoteMatch(sourceSnippet) ?: return null
    val needleWords = needle.split(' ').filter(String::isNotEmpty)
    var best: String? = null
    var bestScore = 0.0
    candidates.asReversed().forEach { candidate ->
        val searchable = normalizeForQuoteMatch(candidate.searchableText) ?: return@forEach
        if (needle in searchable) return candidate.messageId
        // The model quoted more than the message itself, for example the message plus the name of
        // its sender. Short messages are excluded because "ok" is contained in almost anything.
        if (searchable.length >= MIN_CONTAINED_CANDIDATE_LENGTH && searchable in needle) {
            return candidate.messageId
        }
        val words = searchable.split(' ').toHashSet()
        val score = needleWords.count { it in words }.toDouble() / needleWords.size
        if (score > bestScore) {
            bestScore = score
            best = candidate.messageId
        }
    }
    return best.takeIf { bestScore >= MIN_QUOTE_MATCH_SCORE }
}

/**
 * Reduces a message to the words a human would recognise it by: no case, no punctuation, no emoji,
 * single spaces. Two spellings of the same sentence have to normalise to the same string.
 */
private fun normalizeForQuoteMatch(value: String?): String? =
    value
        ?.lowercase()
        ?.replace(QUOTE_MATCH_NOISE, " ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private val QUOTE_MATCH_NOISE = Regex("[^\\p{L}\\p{N}]+")

/** Share of the quoted words that must occur in the message when it is not contained verbatim. */
private const val MIN_QUOTE_MATCH_SCORE = 0.6

private const val MIN_CONTAINED_CANDIDATE_LENGTH = 8

/**
 * The provider prefix is identical on every model, so it identifies nothing in a one-line trace.
 * The log keeps the full id; the live feed shows the half that differs.
 */
private fun shortModelName(model: String): String =
    model.substringAfterLast('/').takeIf(String::isNotBlank) ?: model

/**
 * Resolves one immutable settings snapshot and runs one OpenRouter turn. Tool
 * side effects are accumulated locally and only leave this adapter after the
 * verifier has accepted the complete response/action batch.
 */
class OpenRouterAiTurnRunner(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val repository: BotRepository,
    private val gateway: ChatCompletionGateway,
    private val mediaAnalyzer: IncomingMediaAnalyzer,
    private val speechClient: SpeechClient,
    private val imageClient: de.totec.doppel.ai.OpenRouterImageClient,
    private val bridgeMedia: BridgeMediaClient,
    private val approvedMedia: ApprovedMediaAssetStore,
    private val characterReferences: CharacterReferenceStore = CharacterReferenceStore(approvedMedia),
    private val voiceEncoder: VoiceNoteEncoder = VoiceNoteEncoder(),
    private val localSpeech: AndroidSpeechSynthesizer = AndroidSpeechSynthesizer(context),
    private val activityChanged: () -> Unit = {},
    /**
     * Where the open chat's flight recorder listens. Absent in tests and in any build without a
     * UI attached, which is why every publish is a no-op rather than a branch at each call site.
     */
    private val activity: ChatActivityFeed? = null,
    /** Where a running memory write announces itself; absent in tests, see [MemoryWorkFeed]. */
    private val memoryWork: MemoryWorkFeed? = null,
    private val proactivePersistence: ProactivePersistence? = null,
) : AiTurnRunner {
    private val globalMemoryHoldFactory =
        AtomicReference<((String) -> MemoryWriteHold)?>(null)
    private val voiceDirectory = File(context.cacheDir, "voice-out")
    private val generatedImageDirectory = File(context.cacheDir, "generated-images")
    private val readOnlyTools =
        RepositoryReadOnlyTools(repository, approvedMedia, proactivePersistence)

    /**
     * Verbatim prompts of turns whose send is still pending, by turn id.
     *
     * Bounded by insertion order rather than emptied wholesale. Every turn stores its prompt
     * before it is known whether anything will be sent, so turns that never reach
     * [afterSuccessfulSend] — no-reply, blocked commit, failed send — leave their entry behind and
     * the map fills up on its own. Clearing all of it on overflow also threw away the entry of
     * whatever turn was still mid-send, and with multi-bubble typing delays that window is tens of
     * seconds; that turn's memory refresh then found no prompt to append to and paid for a full
     * cold one. Dropping only the eldest entry cannot hit an in-flight turn.
     */
    private val pendingMemoryPrompts: MutableMap<String, CachedTurnPrompt> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, CachedTurnPrompt>() {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, CachedTurnPrompt>,
                ): Boolean = size > MAX_PENDING_MEMORY_PROMPTS
            },
        )
    /**
     * The last turn prompt of each conversation, kept after [pendingMemoryPrompts] has consumed it.
     *
     * A hand-triggered memory write has no turn of its own to append to. Without this it would fall
     * into the standalone branch and pay for a full cold prompt of a history the provider cache is
     * still holding from the last reply. Keyed by conversation and never removed on use: the button
     * may be pressed at any point after the turn, and pressing it twice must not turn the second
     * press into the expensive shape.
     */
    private val lastConversationPrompts: MutableMap<String, CachedTurnPrompt> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, CachedTurnPrompt>() {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, CachedTurnPrompt>,
                ): Boolean = size > MAX_CONVERSATION_MEMORY_PROMPTS
            },
        )

    /**
     * Frozen original-turn inputs used only after a confirmed, persisted media send.
     *
     * Bounded the same way as [pendingMemoryPrompts] and for the same reason: turns that never
     * reach [afterSuccessfulSend] leave their entry behind, so the map fills up on its own, and
     * emptying it wholesale on overflow also dropped the entry of whatever turn was still waiting
     * for its media send to be confirmed. That turn then found no state in
     * [followUpAfterVisibleAssistant] and returned no-reply — the caption bubble after the image or
     * voice note silently never arrived. Evicting only the eldest entry cannot hit an in-flight
     * turn.
     */
    private val pendingVisibleFollowUps: MutableMap<String, VisibleFollowUpState> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, VisibleFollowUpState>() {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, VisibleFollowUpState>,
                ): Boolean = size > MAX_PENDING_VISIBLE_FOLLOW_UPS
            },
        )

    /**
     * Failed generation attempts per turn, so the second refusal stops asking instead of paying
     * for a third. Bounded by the same eviction as [pendingVisibleFollowUps].
     */
    private val generatedImageFailures: MutableMap<String, Int> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Int>() {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Int>,
                ): Boolean = size > MAX_PENDING_VISIBLE_FOLLOW_UPS
            },
        )

    private data class VisibleFollowUpState(
        val settings: ResolvedTurnSettings,
        val turn: TurnContext,
        val toolContext: ToolExecutionContext,
        val selectedVoice: String,
        val personaKey: String,
        val ttsModel: String,
        val ttsQuality: Int,
        val baseVoiceStyle: String?,
        val blockRepeatedImages: Boolean,
        val imageGenerationModel: String,
        val imageGenerationQuality: String,
        val imageGenerationPrefix: String,
    )
    internal val memoryRefreshService =
        MemoryRefreshService(
            gateway = gateway,
            persistence = RepositoryMemoryRefreshStore(repository),
            configurationProvider =
                MemoryRefreshConfigurationProvider { _, personaKey ->
                    val current = settingsRepository.snapshot()
                    withContext(Dispatchers.IO) {
                        MemoryRefreshConfiguration(
                            // Deliberately the main model, not a second one: a memory call on a
                            // different model or effort cannot reuse the reply turn's prefix cache.
                            model = current.text(BotSettingKeys.MODEL),
                            reasoningEffort =
                                reasoning(current.text(BotSettingKeys.REASONING_EFFORT)),
                            characterLimit =
                                current
                                    .integer(BotSettingKeys.MEMORY_CHAR_LIMIT)
                                    .takeIf { it > 0 },
                            personaGroundTruth =
                                if (personaKey == "custom") {
                                    current
                                        .text(BotSettingKeys.SYSTEM_PROMPT)
                                        .takeIf(String::isNotBlank)
                                } else {
                                    repository
                                        .getPersona(personaKey)
                                        ?.systemPrompt
                                        ?.takeIf(String::isNotBlank)
                                } ?: PersonaBehavior.instructions(personaKey),
                            timezone = current.text(BotSettingKeys.TIMEZONE),
                            chatMemoryEnabled =
                                current.boolean(BotSettingKeys.MEMORY_CHAT_ENABLED),
                            globalMemoryEnabled =
                                current.boolean(BotSettingKeys.MEMORY_GLOBAL_ENABLED),
                            personaSynthesisEvery =
                                ConversationMemoryPolicy.personaSynthesisInterval(
                                    current.integer(BotSettingKeys.MEMORY_GLOBAL_INTERVAL),
                                ),
                        )
                    }
                },
            work = memoryWork,
            globalMemoryHold = { personaKey -> globalMemoryHoldFactory.get()?.invoke(personaKey) },
        )

    /** Binds persona-memory synthesis to the current engine's all-chat prompt gate. */
    internal fun attachGlobalMemoryGate(factory: (String) -> MemoryWriteHold) {
        globalMemoryHoldFactory.set(factory)
    }

    internal fun detachGlobalMemoryGate() {
        globalMemoryHoldFactory.set(null)
    }

    /**
     * One explicit, bounded memory call for a locally selected WhatsApp text export.
     * Normal automatic refreshes retain their smaller 120k source budget; imports alone may use
     * the product-level 500k limit and up to the repository's bounded 5k-row scan.
     */
    internal suspend fun refreshImportedChat(
        conversationKey: String,
        personaKey: String,
    ): MemoryRefreshOutcome {
        val current = settingsRepository.snapshot()
        val personaGroundTruth =
            withContext(Dispatchers.IO) {
                if (personaKey == "custom") {
                    current.text(BotSettingKeys.SYSTEM_PROMPT).takeIf(String::isNotBlank)
                } else {
                    repository.getPersona(personaKey)?.systemPrompt?.takeIf(String::isNotBlank)
                } ?: PersonaBehavior.instructions(personaKey)
            }
        return memoryRefreshService.refresh(
            MemoryRefreshRequest(
                conversationKey = conversationKey,
                personaId = personaKey,
                model = current.text(BotSettingKeys.MODEL),
                configuredCharacterLimit =
                    current.integer(BotSettingKeys.MEMORY_CHAR_LIMIT).takeIf { it > 0 },
                personaGroundTruth = personaGroundTruth,
                timezone = current.text(BotSettingKeys.TIMEZONE),
                reasoningEffort = reasoning(current.text(BotSettingKeys.REASONING_EFFORT)),
                sourceCharacterLimit = IMPORT_MEMORY_SOURCE_CHARACTERS,
                sourceMessageLimit = IMPORT_MEMORY_SOURCE_MESSAGES,
            ),
        )
    }

    /**
     * "Create Memory" from the chat menu: the ordinary cadence refresh with its threshold lowered to
     * one message, so the only thing the button changes is *when* it runs.
     *
     * It appends the memory instruction to the last turn prompt of this conversation exactly as the
     * automatic refresh does, consolidates everything after the durable marker, and — being a normal
     * chat-memory write — counts towards the persona's every-third-write synthesis.
     *
     * A press writes a memory even with chat memory switched off. The setting governs what happens
     * on its own at the interval; this is somebody asking for one.
     */
    internal suspend fun refreshChatMemoryNow(
        conversationKey: String,
        personaKey: String,
    ): MemoryRefreshOutcome =
        memoryRefreshService.refreshIfDue(
            conversationKey = conversationKey,
            personaKey = personaKey,
            threshold = 1,
            firstWriteThreshold = 1,
            cachedPrompt = lastConversationPrompts[conversationKey],
            demanded = true,
        )

    /** "Create Global Memory" from the memory browser; see [MemoryRefreshService.synthesizePersona]. */
    internal suspend fun synthesizePersonaMemoryNow(personaKey: String): MemoryRefreshOutcome =
        memoryRefreshService.synthesizePersona(personaKey)

    override suspend fun run(input: TurnInput): TurnOutput {
        if (input.newestEvents.isEmpty()) {
            recordSilence(
                input = input,
                reason = "empty_turn_skipped",
                summary = "Turn contained no incoming events · no model call",
                details = JSONObject().put("events", 0),
            )
            return TurnOutput(noReply = true)
        }
        if (!input.proactive && input.newestEvents.none(::isReadable)) {
            // Nothing arrived that can be turned into words. This used to become the literal
            // marker `[Empty WhatsApp action]` in the prompt, and the persona answered it as if it
            // were a message — the "your message came in an unsupported format, contact support"
            // reply in the field screenshots. There is nothing to reply to, so no model is asked
            // and nothing is sent: a real person's phone shows them nothing either.
            logActivity(
                category = "ai_stage",
                action = "empty_action_rendered",
                level = de.totec.doppel.data.db.ActivityLevel.WARN,
                correlationId = input.turnId,
                summary = "Incoming actions had no model-readable content",
                details =
                    JSONObject()
                        .put("events", input.newestEvents.size)
                        .put(
                            "eventKeys",
                            input.newestEvents.joinToString("|") { event ->
                                buildList {
                                    add("kind")
                                    add("messageId")
                                    if (event.media != null) add("media")
                                    if (event.reactionEmoji != null) add("reactionEmoji")
                                    if (event.targetMessageId != null) add("targetMessageId")
                                    if (event.callMedia != null) add("callMedia")
                                }.joinToString(",")
                            }.take(500),
                        ),
            )
            recordSilence(
                input = input,
                reason = "empty_turn_skipped",
                summary = "Nothing readable arrived · no model call",
                details =
                    JSONObject()
                        .put("events", input.newestEvents.size)
                        .put(
                            "kinds",
                            input.newestEvents.joinToString(",") { it.kind.name }.take(200),
                        ),
            )
            return TurnOutput(noReply = true)
        }
        val snapshot = settingsRepository.snapshot()
        recordAiStage(
            input = input,
            action = "prompt_preparing",
            summary = "Preparing the AI prompt",
            model = snapshot.text(BotSettingKeys.MODEL),
        )
        // Engine settings already resolve persisted chat assignments and the
        // global fallback. Resolving again from the latest sender breaks group
        // assignments and crosses persona memory/image namespaces.
        val personaKey = input.settings.personality
        val blockRepeatedImages = snapshot.boolean(BotSettingKeys.BLOCK_REPEAT_IMAGES)
        val imageChatId = input.newestEvents.last().chatJid
        val imageSendingAvailable =
            approvedMedia.hasSendableAsset(
                personaKey = personaKey,
                chatId = imageChatId,
                blockRepeats = blockRepeatedImages,
            )
        if (!imageSendingAvailable && !input.proactive) {
            recordImageToolWithheld(
                input = input,
                personaKey = personaKey,
                blockRepeats = blockRepeatedImages,
            )
        }
        val imageGenerationAvailable = snapshot.boolean(BotSettingKeys.IMAGE_GENERATION_ENABLED)
        val characterReferenceCount = characterReferences.count(personaKey)
        val baseResolved =
            snapshot.toAiSettings(
                imageSending = imageSendingAvailable,
                imageGeneration = imageGenerationAvailable,
                personaKey = personaKey,
                contactBlockingAllowed =
                    !input.isGroup &&
                        !input.isAdmin &&
                        !input.proactive,
            )
        val resolved =
            if (input.proactive) {
                baseResolved.copy(
                    output =
                        baseResolved.output.copy(
                            allowNoReply = true,
                            allowReactions = false,
                            allowQuoteReply = false,
                        ),
                    // A proactive check only decides whether to write one short
                    // message. It may not inspect other chats or commit a side
                    // effect without a current user request.
                    tools =
                        baseResolved.tools.copy(
                            crossChatSearch = false,
                            followUpScheduling = false,
                            imageSending = false,
                            imageGeneration = false,
                            voiceNotes = false,
                            memoryRefresh = false,
                            contactBlocking = false,
                        ),
                )
            } else {
                baseResolved.copy(
                    output =
                        baseResolved.output.copy(
                            // [no reply] is part of the stable base protocol for direct chats,
                            // groups and continuation calls. The prompt decides when silence is
                            // appropriate; proactive level must not turn the token into text.
                            allowNoReply = true,
                        ),
                )
            }
        val storedPersona = repository.getPersona(personaKey)
        val personaInstructions =
            if (personaKey == "custom") {
                snapshot.text(BotSettingKeys.SYSTEM_PROMPT).takeIf(String::isNotBlank)
            } else {
                storedPersona?.systemPrompt?.takeIf(String::isNotBlank)
            } ?: PersonaBehavior.instructions(personaKey)
        // A voice note is the persona speaking, so the persona's own voice decides. The global
        // tts_voice is what a persona without one falls back to, not what overrules it.
        val selectedVoice =
            PersonaVoices.effectiveVoice(
                personaKey = personaKey,
                voiceConfigJson = storedPersona?.voiceConfigJson,
                fallback = snapshot.text(BotSettingKeys.TTS_VOICE),
            )
        val latest = buildLatestMessage(input, resolved, snapshot)
        val persona =
            PersonaContext(
                id = personaKey,
                displayName =
                    SettingsCatalogs.personas
                        .firstOrNull { it.key == personaKey }
                        ?.label
                        ?: personaKey,
                instructions =
                    personaInstructions,
                traits = traitValues(snapshot),
            )
        val renderedHistory =
            TurnHistoryRenderer.render(
                history = input.history,
                isGroup = input.isGroup,
                timezone = input.settings.timezone,
                includeTimestamps = snapshot.boolean(BotSettingKeys.HISTORY_TIMESTAMPS),
            )
        val turnContext =
            TurnContext(
                latestMessage = latest,
                persona = persona,
                history = renderedHistory,
                chatMemory = input.chatMemory,
                personaMemory = input.personaMemory,
                group =
                    if (input.isGroup) {
                        GroupContext(
                            subject = input.newestEvents.last().chatName,
                            currentSenderName = input.senderName,
                        )
                    } else {
                        null
                    },
                mood =
                    input.mood.promptHint.takeIf {
                        snapshot.boolean(BotSettingKeys.MOOD_ENABLED)
                    },
                // Reused byte-for-byte during media continuation, including the final clock tail.
                now = Instant.ofEpochMilli(System.currentTimeMillis()),
                zoneId = input.settings.timezone,
                proactive = input.proactive,
                sleepWindDown = isSleepWindDown(input.settings),
                trailingDirective = input.proactiveTrailingDirective,
            )
        val toolContext =
            ToolExecutionContext(
                conversationKey = input.conversationKey,
                turnId = input.turnId,
                currentSender = input.newestEvents.lastOrNull()?.senderJid,
                personaKey = personaKey,
                sendableImagesAvailable = resolved.tools.imageSending,
                blockRepeatedImages = blockRepeatedImages,
                isGroup = input.isGroup,
                isAdmin = input.isAdmin,
                nowMs = turnContext.now.toEpochMilli(),
            )
        val followUpState =
            VisibleFollowUpState(
                settings = resolved,
                turn = turnContext,
                toolContext = toolContext,
                selectedVoice = selectedVoice,
                personaKey = personaKey,
                ttsModel = snapshot.text(BotSettingKeys.TTS_MODEL),
                ttsQuality = snapshot.integer(BotSettingKeys.TTS_QUALITY),
                baseVoiceStyle =
                    snapshot.text(BotSettingKeys.TTS_STYLE_PROMPT).takeIf(String::isNotBlank),
                blockRepeatedImages = blockRepeatedImages,
                imageGenerationModel = snapshot.text(BotSettingKeys.IMAGE_GENERATION_MODEL),
                imageGenerationQuality = snapshot.text(BotSettingKeys.IMAGE_GENERATION_QUALITY),
                imageGenerationPrefix = snapshot.text(BotSettingKeys.IMAGE_GENERATION_PREFIX),
            )
        pendingVisibleFollowUps[input.turnId] = followUpState
        val planned = mutableListOf<PlannedSideEffect>()
        val committer = actionCommitter(input, followUpState, planned, followUpIndex = 0)
        val orchestrator = orchestrator(resolved, committer, input.chatJid)
        val exposedToolNames = ToolRegistry.allowed(resolved.tools).map { it.name }
        val result =
            recordAiStage(
                input = input,
                action = "model_dispatch",
                summary =
                    "Handing the request to the main model | " +
                        "tools ${exposedToolNames.joinToString(", ")} | " +
                        "cross-chat ${if (resolved.tools.crossChatSearch) "on" else "off"} | " +
                        "character references $characterReferenceCount",
                model = resolved.model(ModelRole.MAIN),
            ).let {
                withLiveReasoning(input.chatJid) {
                    orchestrator.runTurn(
                        settings = resolved,
                        turn = turnContext,
                        toolContext = toolContext,
                        retryConfirmedWritingNoReply =
                            !input.proactiveTrailingDirective.isNullOrBlank(),
                    )
                }
            }
        return finishTurn(
            input = input,
            state = followUpState,
            result = result,
            planned = planned,
        )
    }

    override suspend fun followUpAfterVisibleAssistant(
        input: TurnInput,
        confirmedAssistantMessages: List<de.totec.doppel.engine.StoredTurnMessage>,
        followUpIndex: Int,
    ): TurnOutput {
        val state = pendingVisibleFollowUps[input.turnId] ?: return TurnOutput(noReply = true)
        val confirmed =
            confirmedAssistantMessages
                .asSequence()
                .filter { it.role.equals("assistant", ignoreCase = true) }
                .mapNotNull { message ->
                    message.text.trim().takeIf(String::isNotEmpty)?.let { text ->
                        HistoryMessage(ChatRole.ASSISTANT, text)
                    }
                }
                .toList()
        if (confirmed.isEmpty()) return TurnOutput(noReply = true)

        val planned = mutableListOf<PlannedSideEffect>()
        val committer = actionCommitter(input, state, planned, followUpIndex)
        recordAiStage(
            input = input,
            action = "visible_follow_up_dispatch",
            summary = "Continuing the confirmed WhatsApp output in the same prompt",
            model = state.settings.model(ModelRole.MAIN),
        )
        val result =
            withLiveReasoning(input.chatJid) {
                orchestrator(state.settings, committer, input.chatJid).runTurn(
                    settings = state.settings,
                    turn = state.turn.copy(followUpHistory = confirmed),
                    toolContext = state.toolContext,
                    // The system prompt is intentionally unchanged. Only the response parser enables
                    // the exact stop token already documented in that stable prompt.
                    acceptNoReply = true,
                    requestTag = "visible_follow_up",
                )
            }
        return finishTurn(
            input = input,
            state = state,
            result = result,
            planned = planned,
            followUpIndex = followUpIndex,
            confirmedAssistantMessages = confirmedAssistantMessages,
        )
    }

    /**
     * The shared tail of every model turn: remember the prompt for the memory pass, trace the
     * tools, apply the verification and repetition gates, clean what survives and shape it into a
     * [TurnOutput].
     *
     * The turn's first call and the continuation after a confirmed WhatsApp send used to carry a
     * copy of this each, ninety near-identical lines apart — which is exactly how the repetition
     * damper ended up running on one of them and not the other without anybody deciding that.
     * Everything that genuinely differs follows from one discriminator: [followUpIndex] is null
     * for the first call and set for a continuation. The damper, the empty-answer report and the
     * humanizer seed all hang off it, so the asymmetry is now stated in one place instead of
     * living in the gap between two copies.
     *
     * [confirmedAssistantMessages] are the bubbles WhatsApp has already acknowledged for this
     * turn. They are what makes the continuation a continuation: their ids join the prompt
     * fingerprint and their text feeds the repetition check.
     */
    private suspend fun finishTurn(
        input: TurnInput,
        state: VisibleFollowUpState,
        result: de.totec.doppel.ai.OrchestrationResult,
        planned: List<PlannedSideEffect>,
        followUpIndex: Int? = null,
        confirmedAssistantMessages: List<de.totec.doppel.engine.StoredTurnMessage> =
            emptyList(),
    ): TurnOutput {
        val isFirstCall = followUpIndex == null
        rememberPromptForMemory(
            input = input,
            result = result,
            model = state.settings.model(ModelRole.MAIN),
            providerMessageIds =
                (
                    input.history.map { it.id } +
                        input.newestEvents.map { it.messageId } +
                        confirmedAssistantMessages.map { it.id }
                ).filter(String::isNotBlank).toSet(),
        )
        recordToolTrace(input, result, planned)
        if (!result.commitAllowed) {
            // A *rejected* candidate is blocked outright: nothing from it reaches WhatsApp.
            // What used to happen silently now leaves a trace, because "the bot said nothing" is
            // indistinguishable from a crash unless the reason is written down.
            recordSilence(
                input = input,
                reason =
                    if (isFirstCall) {
                        "verification_blocked"
                    } else {
                        "visible_follow_up_verification_blocked"
                    },
                summary =
                    if (isFirstCall) {
                        "Reply blocked · ${verificationLabel(result.verification.reasonCode)}"
                    } else {
                        "Output follow-up was blocked by verification"
                    },
                details =
                    JSONObject()
                        .put("verification", result.verification.reasonCode.take(80))
                        .put("bubbles", result.response.bubbles.size)
                        .put("plannedActions", planned.size)
                        .apply {
                            followUpIndex?.let { put("followUpIndex", it) }
                            result.verification.fallback?.let { fallback ->
                                put("fallbackCause", fallback.cause.take(80))
                                put("fallbackPolicy", fallback.policy)
                                fallback.detail?.let { put("fallbackDetail", it.take(200)) }
                            }
                        },
            )
            return TurnOutput(noReply = true)
        }
        result.verification.fallback?.let { fallback ->
            // Fail-open let the answer through. That decision is a cost and a safety trade-off, so
            // it is recorded even though the turn succeeded.
            recordVerificationFallback(input, result.verification.reasonCode, fallback)
        }
        if (isFirstCall && result.isEmptyAnswer(planned)) {
            // No text, no reaction, no action, and the model never asked for silence: this is an
            // empty completion, not a decision. Surfacing it stops the turn from ending in an
            // unexplained void.
            recordSilence(
                input = input,
                reason = "empty_model_answer",
                summary = "Model returned an empty answer · ${result.modelCalls} model calls",
                details =
                    JSONObject()
                        .put("modelCalls", result.modelCalls)
                        .put("verification", result.verification.reasonCode.take(80)),
            )
        }
        val hasVisibleMedia =
            planned.any {
                it is PlannedSideEffect.SynthesizeVoiceNote ||
                    it is PlannedSideEffect.UploadApprovedImage ||
                    it is PlannedSideEffect.GenerateImage
            }
        // Only worth checking when text is what actually goes out. A turn that sends a picture or a
        // voice note has no bubbles of its own, and its caption is decided by the follow-up call.
        val response =
            if (isFirstCall && !hasVisibleMedia) {
                dampenRepetition(input, state, result.response)
            } else {
                result.response
            }
        // A media tool completion is not a second chat bubble. Once WhatsApp confirms any visible
        // output, the engine asks again with the confirmed assistant entry appended to this prompt.
        val visibleBubbles =
            if (hasVisibleMedia) {
                emptyList()
            } else {
                humanized(
                    input = input,
                    bubbles = response.bubbles,
                    // A continuation is a second turn on the same turn id. Without the index every
                    // follow-up bubble would draw the same texture decisions as the first batch.
                    seed =
                        if (isFirstCall) {
                            input.turnId
                        } else {
                            "${input.turnId}:follow-up:$followUpIndex"
                        },
                    recentExtra = confirmedAssistantMessages.map { it.text },
                )
            }
        val bubbleQuoteMessageIds =
            visibleBubbles.indices.map { index ->
                response.bubbleReplies
                    .getOrNull(index)
                    ?.let { directive -> resolveQuoteTarget(input, directive.sourceSnippet) }
            }
        return TurnOutput(
            text = visibleBubbles.joinToString("\n\n"),
            bubbles = visibleBubbles,
            reaction = response.reaction,
            reactionTargetMessageId =
                input.newestEvents.lastOrNull()?.messageId
                    .takeIf { response.reaction != null },
            quoteMessageId =
                bubbleQuoteMessageIds.firstOrNull { it != null }
                    ?: response.quote?.let { directive ->
                        resolveQuoteTarget(input, directive.sourceSnippet)
                    },
            bubbleQuoteMessageIds = bubbleQuoteMessageIds,
            noReply = response.noReply && !hasVisibleMedia,
            actions = planned.toList(),
        )
    }

    private fun actionCommitter(
        input: TurnInput,
        state: VisibleFollowUpState,
        planned: MutableList<PlannedSideEffect>,
        followUpIndex: Int,
    ): TurnActionCommitter =
        TurnActionCommitter(
            planned = planned,
            currentSender = input.newestEvents.lastOrNull()?.senderJid,
            currentSenderAliases = input.currentSenderAliases(),
            turnId =
                when (followUpIndex) {
                    0 -> input.turnId
                    VOICE_REWRITE_FOLLOW_UP_INDEX -> "${input.turnId}:voice-text-rewrite"
                    REPETITION_RETRY_FOLLOW_UP_INDEX -> "${input.turnId}:repetition-retry"
                    IMAGE_FALLBACK_FOLLOW_UP_INDEX -> "${input.turnId}:image-generation-fallback"
                    else -> "${input.turnId}:media-follow-up:$followUpIndex"
                },
            voice = state.selectedVoice,
            personaKey = state.personaKey,
            ttsModel = state.ttsModel,
            ttsQuality = state.ttsQuality,
            baseVoiceStyle = state.baseVoiceStyle,
            blockRepeatedImages = state.blockRepeatedImages,
            imageGenerationModel = state.imageGenerationModel,
            imageGenerationQuality = state.imageGenerationQuality,
            imageGenerationPrefix = state.imageGenerationPrefix,
        )

    /**
     * Runs a model turn with the open chat's trace installed as the destination for its reasoning.
     *
     * The sink rides in the coroutine context, so everything the turn calls — the tool loop's
     * second and third pass, the verifier — writes to the same trace without being handed anything.
     * When no UI is attached there is no sink and the client parses exactly as it did before.
     */
    private suspend fun <T> withLiveReasoning(chatJid: String, block: suspend () -> T): T {
        val feed = activity ?: return block()
        val sink = object : LiveTokenSink() {
            override fun emitReasoning(text: String) {
                feed.reasoning(chatJid, text, System.currentTimeMillis())
            }
        }
        return withContext(sink) { block() }
    }

    private fun orchestrator(
        settings: ResolvedTurnSettings,
        committer: PendingActionCommitter,
        chatJid: String,
    ): AiOrchestrator =
        AiOrchestrator(
            completionGateway = gateway,
            readOnlyToolExecutor = readOnlyTools,
            actionCommitter = committer,
            verifier =
                if (settings.verification.enabled) {
                    OpenRouterReplyVerifier(gateway)
                } else {
                    null
                },
            deferVisibleActionFollowUp = true,
            observer = liveObserver(chatJid),
        )

    /**
     * Turns the orchestrator's callbacks into lines of the open chat's trace.
     *
     * The stage is republished on every model call rather than once at dispatch: a tool loop drops
     * back into thinking after each tool, and a row that still says "typing" while the second call
     * is out would be a lie for as long as that call takes.
     */
    private fun liveObserver(chatJid: String): TurnObserver {
        val feed = activity ?: return TurnObserver.None
        return object : TurnObserver {
            override fun modelCall(index: Int, model: String) {
                val now = System.currentTimeMillis()
                feed.stage(chatJid, ChatStage.THINKING, detail = shortModelName(model), nowMs = now)
                feed.trace(
                    chatJid = chatJid,
                    kind = TraceKind.STEP,
                    text =
                        if (index == 1) {
                            "Asking ${shortModelName(model)}"
                        } else {
                            "Asking ${shortModelName(model)} again · call $index"
                        },
                    nowMs = now,
                )
            }

            override fun toolCall(name: String, arguments: String) {
                feed.trace(
                    chatJid = chatJid,
                    kind = TraceKind.TOOL,
                    text = "$name $arguments",
                    nowMs = System.currentTimeMillis(),
                )
            }

            override fun toolResult(name: String, ok: Boolean, detail: String) {
                feed.trace(
                    chatJid = chatJid,
                    kind = if (ok) TraceKind.TOOL else TraceKind.PROBLEM,
                    text = "$name → $detail",
                    nowMs = System.currentTimeMillis(),
                )
            }

            override fun problem(text: String) {
                feed.trace(chatJid, TraceKind.PROBLEM, text, System.currentTimeMillis())
            }

            override fun checking(model: String?) {
                val now = System.currentTimeMillis()
                if (model == null) {
                    // Not a fault and not an omission: nothing stands between this answer and the
                    // chat, which is worth one line precisely because the trace otherwise looks the
                    // same as one whose check is still running.
                    feed.trace(chatJid, TraceKind.STEP, "No check model · sending as written", now)
                    return
                }
                // The check is a second paid call on a second model, and the row said "<main model>
                // thinking" throughout it. It is its own wait and it says whose.
                feed.stage(
                    chatJid,
                    ChatStage.THINKING,
                    detail = "${shortModelName(model)} check",
                    nowMs = now,
                )
                feed.trace(
                    chatJid,
                    TraceKind.STEP,
                    "Checking the reply · ${shortModelName(model)}",
                    now,
                )
            }

            override fun checked(allowed: Boolean, reason: String, detail: String?) {
                feed.trace(
                    chatJid = chatJid,
                    kind = if (allowed) TraceKind.STEP else TraceKind.PROBLEM,
                    text =
                        if (allowed) {
                            "Check passed"
                        } else {
                            // The sentence, when the check model wrote one, is the only part of this
                            // that says what to do differently. The code stays as the anchor.
                            listOfNotNull("Check blocked · ${verificationLabel(reason)}", detail)
                                .joinToString(" · ")
                        },
                    nowMs = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * One line into the open chat's live trace, if a chat is open to see it.
     *
     * Everything a turn does that costs time or money says so here. The durable activity log stays
     * the record of what happened; this is the running commentary, and it is only ever true now.
     */
    private fun liveTrace(chatJid: String, kind: TraceKind, text: String) {
        activity?.trace(chatJid, kind, text, System.currentTimeMillis())
    }

    override suspend fun materializeActions(
        input: TurnInput,
        actions: List<PlannedSideEffect>,
    ): List<PlannedSideEffect> =
        actions.flatMap { action ->
            when (action) {
                is PlannedSideEffect.SynthesizeVoiceNote ->
                    try {
                        // Synthesis, encoding and the upload are seconds of paid work between the
                        // model answering and WhatsApp showing anything. Unannounced, the trace
                        // simply stopped mid-turn and looked hung.
                        liveTrace(
                            input.chatJid,
                            TraceKind.STEP,
                            "Recording the voice message · ${shortModelName(action.model)}",
                        )
                        val voice = materializeVoice(action)
                        liveTrace(input.chatJid, TraceKind.STEP, "Voice message ready to send")
                        listOf(voice)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        liveTrace(
                            input.chatJid,
                            TraceKind.PROBLEM,
                            "Voice message failed · ${voiceFailureCode(failure)} · " +
                                "answering as text instead",
                        )
                        recordVoiceFallback(action, failure)
                        voiceTextFallback(input, action, failure)
                    }

                is PlannedSideEffect.UploadApprovedImage ->
                    try {
                        liveTrace(input.chatJid, TraceKind.STEP, "Preparing the picture")
                        listOf(
                            materializeApprovedImage(
                                chatId = input.chatJid,
                                action = action,
                                settings = pendingVisibleFollowUps[input.turnId]?.settings,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // An unsendable image used to propagate out of materializeActions and
                        // abort the whole turn *before* any bubble was dispatched, so a stale
                        // asset reference cost the user the entire reply. Degrade instead: keep
                        // the caption if there is one, and always leave a readable reason.
                        val code = ImageSendDiagnostics.failureCode(failure)
                        liveTrace(input.chatJid, TraceKind.PROBLEM, "Picture failed · $code")
                        recordImageFailure(action, code)
                        listOfNotNull(ImageSendDiagnostics.degrade(action, code))
                    }

                is PlannedSideEffect.GenerateImage -> {
                    liveTrace(
                        input.chatJid,
                        TraceKind.STEP,
                        "Generating the picture · ${shortModelName(action.model)} · ${action.quality}",
                    )
                    try {
                        listOf(materializeGeneratedImage(action))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // This used to have no catch at all, so a provider refusal — a safety
                        // filter answering 400 is the usual one — propagated out of the whole
                        // side-effect phase and threw the turn away. The contact got silence for
                        // a message whose text answer was already written. Now the model is told
                        // what happened and answers on the same cached prompt.
                        val code = ImageSendDiagnostics.generationFailureCode(failure)
                        val failures = (generatedImageFailures[input.turnId] ?: 0) + 1
                        generatedImageFailures[input.turnId] = failures
                        liveTrace(
                            input.chatJid,
                            TraceKind.PROBLEM,
                            "Picture failed · ${ImageSendDiagnostics.failureLabel(code)}",
                        )
                        recordGeneratedImageFailure(input, action, code, failures)
                        generatedImageFallback(input, action, code, failures)
                    }
                }

                else -> listOf(action)
            }
        }

    /**
     * Turns a refused voice note into something a human would have written.
     *
     * The old behaviour published the TTS script itself, so a blocked request surfaced in WhatsApp
     * as `[moans] mhh... hoerst du mich?`. Instead the model is asked once more on the *identical*
     * prompt — same cached prefix, same memory, same chat — with a single instruction appended
     * behind the tail. Only if that call is unusable does the spoken text go out, and then at least
     * without its synthesizer markup.
     */
    private suspend fun voiceTextFallback(
        input: TurnInput,
        action: PlannedSideEffect.SynthesizeVoiceNote,
        failure: Exception,
    ): List<PlannedSideEffect> {
        val reason = voiceFailureCode(failure)
        val rewritten = rewriteVoiceAsText(input, action)
        if (rewritten.isEmpty()) {
            return listOf(
                PlannedSideEffect.VoiceTextFallback(
                    idempotencyKey = "${action.idempotencyKey}:text-fallback",
                    text =
                        OutboundSurfaceSanitizer.sanitize(action.text)
                            .ifBlank { "Die Sprachnachricht konnte nicht gesendet werden." },
                    reasonCode = reason,
                ),
            )
        }
        return rewritten.mapIndexed { index, bubble ->
            PlannedSideEffect.VoiceTextFallback(
                idempotencyKey = "${action.idempotencyKey}:text-rewrite:$index",
                text = bubble,
                reasonCode = reason,
            )
        }
    }

    /**
     * Asks once more when the answer is a message the persona already sent.
     *
     * The check itself is free — plain string comparison against the recent history — so the cost is
     * only paid on an actual repeat. The retry reuses the byte-identical prompt with one trailing
     * directive appended, so it still hits the provider's prefix cache and bills as a short call
     * rather than a second full turn.
     *
     * Falls back to the original answer only when the retry is blocked or unusable. Even a still-
     * similar retry is newer evidence than the exact answer that triggered the guard; a third call
     * is deliberately never made.
     */
    private suspend fun dampenRepetition(
        input: TurnInput,
        state: VisibleFollowUpState,
        response: de.totec.doppel.ai.ParsedAssistantResponse,
    ): de.totec.doppel.ai.ParsedAssistantResponse {
        if (response.bubbles.isEmpty()) return response
        val (assistantHistory, contactHistory) =
            input.history.partition { it.role.equals("assistant", ignoreCase = true) }
        val recent = assistantHistory.map { HistoryLabelGuard.stripLeadingLabel(it.text) }
        val incoming = contactHistory.map { HistoryLabelGuard.stripLeadingLabel(it.text) }
        val loops = RepetitionGuard.repeats(response.bubbles, recent)
        // The chat log used to put the quoted message on a line of its own above every reply, and
        // the model learned to send the contact their own message back. The history no longer shows
        // it that shape, and this catches what is left of the habit.
        val echoes = !loops && RepetitionGuard.repeats(response.bubbles, incoming)
        if (!loops && !echoes) return response
        val what =
            if (echoes) {
                "Answer sent the contact's own words back"
            } else {
                "Answer repeated an earlier message"
            }

        recordAiStage(
            input = input,
            action = if (echoes) "echo_retry" else "repetition_retry",
            summary = "$what · asking once more",
            model = state.settings.model(ModelRole.MAIN),
        )
        // A whole second model call used to happen behind a trace that said nothing, so the turn
        // looked like it had simply stalled after the first answer.
        liveTrace(input.chatJid, TraceKind.STEP, "$what · asking once more")
        // Anything this retry plans is dropped: the side effects of the first answer were already
        // committed, and committing a second set for one replaced text would double them.
        val discarded = mutableListOf<PlannedSideEffect>()
        val retry =
            try {
                withLiveReasoning(input.chatJid) {
                    orchestrator(
                        state.settings,
                        actionCommitter(
                            input = input,
                            state = state,
                            planned = discarded,
                            followUpIndex = REPETITION_RETRY_FOLLOW_UP_INDEX,
                        ),
                        input.chatJid,
                    ).runTurn(
                        settings = state.settings,
                        turn = state.turn.copy(
                            trailingDirective =
                                de.totec.doppel.ai.PromptLibrary.repetitionDirective(echoes),
                        ),
                        toolContext = state.toolContext,
                        acceptNoReply = true,
                        requestTag = "repetition_retry",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                liveTrace(
                    input.chatJid,
                    TraceKind.PROBLEM,
                    "Retry failed · the first answer goes out unchanged",
                )
                recordRepetitionOutcome(input, "retry_failed", failure.javaClass.simpleName)
                return response
            }
        val accepted =
            retry.commitAllowed &&
                (retry.response.noReply || retry.response.bubbles.isNotEmpty())
        // Named for what actually happened. "Discarded" read as a choice between two good answers;
        // the only way the retry loses is by being blocked or empty, and then the alternative to the
        // duplicate is nothing at all.
        liveTrace(
            chatJid = input.chatJid,
            kind = if (accepted) TraceKind.STEP else TraceKind.PROBLEM,
            text =
                when {
                    !accepted -> "Retry blocked or empty · the first answer goes out unchanged"
                    retry.response.noReply -> "Retry chose silence · nothing goes out"
                    else -> "Retry used · the first answer is dropped"
                },
        )
        recordRepetitionOutcome(
            input = input,
            outcome = if (accepted) "retry_used" else "retry_discarded",
            detail = "bubbles=${retry.response.bubbles.size} dropped=${discarded.size}",
        )
        return if (accepted) retry.response else response
    }

    /**
     * One local diagnostics row, written and announced in a single call.
     *
     * Every reporter in this file used to spell out the same four things around the two or three
     * that actually differ: the fully qualified record type, the wall clock, the length caps the
     * schema enforces, and the feed notification. Two of them quietly forgot the notification, so
     * their rows stayed invisible until an unrelated write refreshed the feed. The caps are the
     * column limits themselves, so a long summary is trimmed instead of failing the insert.
     *
     * No message body and no identity passes through here — stage names, reasons and counters only.
     */
    private fun logActivity(
        category: String,
        action: String,
        summary: String,
        level: de.totec.doppel.data.db.ActivityLevel =
            de.totec.doppel.data.db.ActivityLevel.DEBUG,
        correlationId: String? = null,
        chatId: String? = null,
        details: JSONObject? = null,
    ) {
        repository.appendActivity(
            de.totec.doppel.data.db.ActivityLogRecord(
                occurredAt = System.currentTimeMillis(),
                level = level,
                category = category.take(MAX_ACTIVITY_ACTION_CHARS),
                action = action.take(MAX_ACTIVITY_ACTION_CHARS),
                chatId = chatId,
                correlationId = correlationId?.take(MAX_CORRELATION_CHARS),
                summary = summary.take(MAX_ACTIVITY_SUMMARY_CHARS),
                detailsJson = details?.toString(),
            ),
        )
        activityChanged()
    }

    private fun recordRepetitionOutcome(
        input: TurnInput,
        outcome: String,
        detail: String,
    ) {
        logActivity(
            category = "ai_stage",
            action = outcome,
            correlationId = input.turnId,
            summary = "Repetition damper · $outcome",
            details = JSONObject().put("detail", detail.take(200)),
        )
    }

    /** Returns the rewritten bubbles, or an empty list when the retry is not usable. */
    private suspend fun rewriteVoiceAsText(
        input: TurnInput,
        action: PlannedSideEffect.SynthesizeVoiceNote,
    ): List<String> {
        val state = pendingVisibleFollowUps[input.turnId] ?: return emptyList()
        recordAiStage(
            input = input,
            action = "voice_text_rewrite_dispatch",
            summary = "TTS refused · answering the identical prompt as text",
            model = state.settings.model(ModelRole.MAIN),
        )
        // Anything the retry plans is dropped on purpose: this call exists to produce text, and a
        // second voice attempt would only fail against the same synthesizer.
        val discarded = mutableListOf<PlannedSideEffect>()
        val result =
            try {
                withLiveReasoning(input.chatJid) {
                    orchestrator(
                        state.settings,
                        actionCommitter(
                            input = input,
                            state = state,
                            planned = discarded,
                            followUpIndex = VOICE_REWRITE_FOLLOW_UP_INDEX,
                        ),
                        input.chatJid,
                    ).runTurn(
                        settings = state.settings,
                        // Everything before the trailing directive is byte-identical to the call
                        // that asked for the voice note, so this rides the same cache entry.
                        turn = state.turn.copy(
                            trailingDirective = de.totec.doppel.ai.PromptLibrary.voiceFallbackDirective(),
                        ),
                        toolContext = state.toolContext,
                        requestTag = "voice_text_rewrite",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordVoiceRewriteOutcome(
                    action = action,
                    outcome = "rewrite_failed",
                    detail = failure.javaClass.simpleName,
                )
                return emptyList()
            }
        val bubbles =
            if (result.commitAllowed) {
                humanized(
                    input = input,
                    bubbles = result.response.bubbles.filter(String::isNotBlank),
                    seed = "${input.turnId}:voice-rewrite",
                )
            } else {
                emptyList()
            }
        recordVoiceRewriteOutcome(
            action = action,
            outcome =
                when {
                    !result.commitAllowed -> "rewrite_blocked"
                    bubbles.isEmpty() -> "rewrite_empty"
                    else -> "rewrite_sent"
                },
            detail = "bubbles=${bubbles.size} dropped=${discarded.size}",
        )
        return bubbles
    }

    private fun recordVoiceRewriteOutcome(
        action: PlannedSideEffect.SynthesizeVoiceNote,
        outcome: String,
        detail: String,
    ) {
        logActivity(
            category = "voice_reply",
            action = outcome,
            level =
                if (outcome == "rewrite_sent") {
                    de.totec.doppel.data.db.ActivityLevel.INFO
                } else {
                    de.totec.doppel.data.db.ActivityLevel.WARN
                },
            correlationId = action.idempotencyKey,
            summary =
                when (outcome) {
                    "rewrite_sent" -> "Wrote a text reply instead of a voice note"
                    "rewrite_blocked" -> "Text replacement was blocked by verification"
                    "rewrite_empty" -> "Text replacement came back empty · sending the spoken text"
                    else -> "Text replacement failed · sending the spoken text"
                },
            details = JSONObject().put("detail", detail.take(200)),
        )
    }

    private fun recordImageFailure(
        action: PlannedSideEffect.UploadApprovedImage,
        code: String,
    ) {
        logActivity(
            category = "image_reply",
            action = "image_send_failed",
            level = de.totec.doppel.data.db.ActivityLevel.WARN,
            correlationId = action.idempotencyKey,
            summary = ImageSendDiagnostics.failureSummary(action.caption, code),
            details =
                JSONObject()
                    // The asset ID is an opaque store handle, not user content, and it is the
                    // only way to find the offending file again.
                    .put("assetId", action.assetId.take(64))
                    .put("personaKey", action.personaKey.take(64))
                    .put("reason", code)
                    .put("hasCaption", !action.caption.isNullOrBlank()),
        )
    }

    private fun recordGeneratedImageFailure(
        input: TurnInput,
        action: PlannedSideEffect.GenerateImage,
        code: String,
        attempt: Int,
    ) {
        logActivity(
            category = "image_reply",
            action = "image_generation_failed",
            level = de.totec.doppel.data.db.ActivityLevel.WARN,
            correlationId = action.idempotencyKey,
            summary =
                "Picture could not be created · ${ImageSendDiagnostics.failureLabel(code)}",
            details =
                JSONObject()
                    .put("model", action.model.take(256))
                    .put("quality", action.quality.take(32))
                    .put("personaKey", action.personaKey.take(64))
                    .put("includeCharacter", action.includeCharacter)
                    .put("reason", code)
                    .put("attempt", attempt)
                    .put("hasCaption", !action.caption.isNullOrBlank()),
        )
    }

    /**
     * What goes out instead of a picture that was never created.
     *
     * The model is asked once more on the byte-identical prompt with one directive appended behind
     * the tail, exactly like [voiceTextFallback] and [dampenRepetition] — so this costs a short
     * cached call, not a second full turn. It is allowed to try `generate_image` again on the first
     * failure, which is why the once-per-turn session mark is given back; on the second failure the
     * mark stays and the directive tells it to stop, so a refusing provider cannot be paid for in a
     * loop.
     *
     * When there is no follow-up state to re-ask with, or the retry produces nothing usable, the
     * caption still goes out as ordinary text rather than the turn ending in silence.
     */
    private suspend fun generatedImageFallback(
        input: TurnInput,
        action: PlannedSideEffect.GenerateImage,
        code: String,
        failures: Int,
    ): List<PlannedSideEffect> {
        val retryAllowed = failures < MAX_IMAGE_GENERATION_ATTEMPTS
        val state = pendingVisibleFollowUps[input.turnId]
        val caption = action.caption?.trim()?.takeIf(String::isNotEmpty)
        val degraded =
            caption?.let {
                listOf(
                    PlannedSideEffect.VoiceTextFallback(
                        idempotencyKey = "${action.idempotencyKey}:caption-fallback",
                        text = it,
                        reasonCode = code,
                    ),
                )
            }.orEmpty()
        if (state == null) return degraded
        if (retryAllowed) {
            // The refused call consumed the one-picture-per-turn budget. Give it back, or the
            // retry cannot act on the advice it is about to be given.
            state.toolContext.session.clear(de.totec.doppel.ai.ToolRegistry.GENERATED_IMAGE)
        }
        liveTrace(
            input.chatJid,
            TraceKind.STEP,
            if (retryAllowed) "Asking for a different answer" else "Answering without a picture",
        )
        val discarded = mutableListOf<PlannedSideEffect>()
        val retry =
            try {
                withLiveReasoning(input.chatJid) {
                    orchestrator(
                        state.settings,
                        actionCommitter(
                            input = input,
                            state = state,
                            planned = discarded,
                            followUpIndex = IMAGE_FALLBACK_FOLLOW_UP_INDEX,
                        ),
                        input.chatJid,
                    ).runTurn(
                        settings = state.settings,
                        turn =
                            state.turn.copy(
                                trailingDirective =
                                    de.totec.doppel.ai.PromptLibrary
                                        .imageGenerationFailedDirective(code, retryAllowed),
                            ),
                        toolContext = state.toolContext,
                        acceptNoReply = true,
                        requestTag = "image_generation_fallback",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                liveTrace(input.chatJid, TraceKind.PROBLEM, "Retry failed · answering without a picture")
                return degraded
            }
        if (!retry.commitAllowed) return degraded
        val bubbles =
            retry.response.bubbles.mapIndexed { index, bubble ->
                PlannedSideEffect.VoiceTextFallback(
                    idempotencyKey = "${action.idempotencyKey}:image-retry:$index",
                    text = bubble,
                    reasonCode = code,
                )
            }
        // The committer only turns tool calls into plans. Run those plans through this same owner
        // before handing them to the engine; returning GenerateImage here was the source of
        // "Media action was never materialized". A failed second generation recursively reaches
        // the no-more-images directive and comes back as ordinary text.
        val materializedRetry = materializeActions(input, discarded)
        return materializedRetry +
            bubbles.ifEmpty {
                if (materializedRetry.isEmpty()) degraded else emptyList()
            }
    }

    private fun recordVoiceFallback(
        action: PlannedSideEffect.SynthesizeVoiceNote,
        failure: Throwable,
    ) {
        logActivity(
            category = "voice_reply",
            action = "tts_text_fallback",
            level = de.totec.doppel.data.db.ActivityLevel.WARN,
            summary =
                "Voice note failed · ${voiceFailureLabel(failure)} · sending the text fallback",
            correlationId = action.idempotencyKey,
            details =
                JSONObject()
                    .put("model", action.model.take(256))
                    .put("reason", voiceFailureCode(failure)),
        )
    }

    private fun voiceFailureCode(failure: Throwable): String =
        when (failure) {
            is de.totec.doppel.ai.OpenRouterHttpException -> failure.reasonCode.take(80)
            is de.totec.doppel.ai.OpenRouterProtocolException -> failure.reasonCode.take(80)
            is de.totec.doppel.ai.MissingApiKeyException -> "missing_api_key"
            else -> failure.javaClass.simpleName.lowercase().take(80)
        }

    private fun voiceFailureLabel(failure: Throwable): String =
        when (failure) {
            is de.totec.doppel.ai.OpenRouterHttpException -> "OpenRouter-TTS HTTP ${failure.statusCode}"
            is de.totec.doppel.ai.MissingApiKeyException -> "OpenRouter key missing"
            else -> "TTS unavailable"
        }

    /**
     * Keeps the verbatim prompt of this turn so the memory refresh that may follow the send can
     * append its instruction to it and ride the provider's prompt cache instead of paying for a
     * second full prompt. Held only until [afterSuccessfulSend] consumes it.
     */
    private fun rememberPromptForMemory(
        input: TurnInput,
        result: de.totec.doppel.ai.OrchestrationResult,
        model: String,
        providerMessageIds: Set<String>,
    ) {
        if (result.promptMessages.isEmpty() || model.isBlank()) return
        val prompt =
            CachedTurnPrompt(
                model = model,
                messages = result.promptMessages,
                tools = result.promptTools,
                sampling = result.promptSampling,
                providerMessageIds = providerMessageIds,
            )
        pendingMemoryPrompts[input.turnId] = prompt
        lastConversationPrompts[input.conversationKey] = prompt
    }

    override suspend fun afterSuccessfulSend(
        input: TurnInput,
        actions: List<PlannedSideEffect>,
    ) {
        pendingVisibleFollowUps.remove(input.turnId)
        val forced =
            actions.any {
                it is PlannedSideEffect.RequestMemoryRefresh &&
                    it.conversationKey == input.conversationKey
            }
        memoryRefreshService.refreshIfDue(
            conversationKey = input.conversationKey,
            personaKey = input.settings.personality,
            threshold =
                if (forced) {
                    1
                } else {
                    ConversationMemoryPolicy.newMessagesAfterReset(
                        input.settings.memoryIntervalMessages,
                    )
                },
            firstWriteThreshold =
                ConversationMemoryPolicy.completeWindow(
                    input.settings.historyLimit,
                    input.settings.memoryIntervalMessages,
                ),
            cachedPrompt = pendingMemoryPrompts.remove(input.turnId),
            // The model tool is reserved for relationship-/identity-changing moments. Once it
            // asks, persist the chat view first and immediately rebuild the persona-wide view.
            forcePersonaSynthesis = forced,
        )
    }

    private suspend fun materializeVoice(
        action: PlannedSideEffect.SynthesizeVoiceNote,
    ): PlannedSideEffect.SendMedia {
        val effectiveVoice = compatibleVoice(action.model, action.voice)
        val primaryRequest =
            SpeechRequest(
                model = action.model,
                text = action.text,
                voice = effectiveVoice,
                instructions = action.instructions,
            )
        recordVoiceTrace(
            action = action,
            stage = "tts_request_prepared",
            summary = "TTS request prepared",
            details =
                JSONObject()
                    .put("model", action.model.take(256))
                    .put("requestedVoice", action.voice.take(128))
                    .put("effectiveVoice", effectiveVoice.take(128))
                    .put("hasInstructions", action.instructions != null)
                    // Composed is not the same as delivered, and the two providers are not fed the
                    // same way, so the trace says which route the style actually took.
                    .put("styleDelivery", primaryRequest.styleDelivery()),
        )
        val pcm =
            if (action.model == LocalSpeechModels.ANDROID_SYSTEM) {
                // The device engine has no notion of expression tags and would read them out:
                // "[laughs] endlich Feierabend" is heard as the word "laughs".
                localSpeech.synthesize(SpeechMarkup.stripExpressionTags(action.text))
            } else {
                // A failed speech request is never followed by a speculative
                // second provider/model call. If the live OpenRouter catalog
                // has no TTS endpoint, retrying only spends quota; the outer
                // materialization path records the failure and sends text.
                speechClient.synthesize(primaryRequest)
            }
        recordVoiceTrace(
            action = action,
            stage = "tts_pcm_ready",
            summary = "TTS PCM received",
            details =
                JSONObject()
                    .put("bytes", pcm.bytes.size)
                    .put("sampleRateHz", pcm.sampleRateHz)
                    .put("channels", pcm.channelCount)
                    .put("bitsPerSample", pcm.bitsPerSample),
        )
        require(
            pcm.sampleRateHz == 24_000 &&
                pcm.channelCount == 1 &&
                pcm.bitsPerSample == 16 &&
                pcm.littleEndian
        ) {
            "TTS returned an unsupported PCM format"
        }
        require(pcm.bytes.size <= MAX_PCM_BYTES) { "TTS output is too large" }
        val destination =
            File(
                voiceDirectory,
                "voice-${sha256Hex(action.idempotencyKey).take(24)}-${UUID.randomUUID()}.ogg",
            )
        return try {
            val encoded =
                voiceEncoder.encode(
                    rawPcm24Khz = pcm.bytes,
                    destination = destination,
                    quality = action.quality,
                    randomSeed = stableSeed(action.idempotencyKey),
                )
            recordVoiceTrace(
                action = action,
                stage = "tts_ogg_ready",
                summary = "Voice note encoded as OGG/Opus",
                details =
                    JSONObject()
                        .put("bytes", encoded.file.length())
                        .put("durationSeconds", encoded.durationSeconds)
                        .put("quality", action.quality),
            )
            val uploaded =
                bridgeMedia.upload(
                    file = encoded.file,
                    mimeType = "audio/ogg",
                    maxBytes = MAX_UPLOAD_BYTES,
                )
            recordVoiceTrace(
                action = action,
                stage = "tts_bridge_uploaded",
                summary = "Voice note uploaded to the native bridge",
                details =
                    JSONObject()
                        .put("bytes", uploaded.sizeBytes)
                        .put("sha256Verified", uploaded.sha256 != null),
            )
            PlannedSideEffect.SendMedia(
                idempotencyKey = action.idempotencyKey,
                uploadId = uploaded.uploadId,
                mimeType = "audio/ogg; codecs=opus",
                voiceNote = true,
                durationSeconds = encoded.durationSeconds,
                waveformBase64 =
                    Base64.getEncoder().encodeToString(encoded.waveform),
                // What the chat log must show is what was *heard*. The expression tags only steer
                // the synthesizer; keeping them produced history rows like
                // `You sent a voice note: [moans] mhh...`, which the model then copied verbatim
                // into a real text bubble.
                historyText = SpeechMarkup.stripExpressionTags(action.text),
            )
        } finally {
            destination.delete()
        }
    }

    /**
     * Delegates to the catalog the picker also uses, so an offered voice and an accepted voice can
     * never drift apart. A substitution is logged: an unexplained voice change looked like the
     * provider ignoring the setting.
     */
    private fun compatibleVoice(model: String, requested: String): String {
        // No catalog argument here on purpose: the send path must not depend on a network fetch.
        // The picker narrows the stored value against the live catalog before it is ever saved,
        // so by the time a voice reaches this point the static tables are the right guard.
        val resolved = TtsVoiceCatalog.resolve(model, requested)
        if (!resolved.equals(requested, ignoreCase = true)) {
            logActivity(
                category = "tts",
                action = "voice_substituted",
                level = de.totec.doppel.data.db.ActivityLevel.WARN,
                summary =
                    "Voice unavailable for ${model.take(120)} · " +
                        "${requested.take(60)} replaced by ${resolved.take(60)}",
                details =
                    JSONObject()
                        .put("model", model.take(256))
                        .put("requestedVoice", requested.take(120))
                        .put("effectiveVoice", resolved.take(120)),
            )
        }
        return resolved
    }

    private fun recordAiStage(
        input: TurnInput,
        action: String,
        summary: String,
        model: String,
    ) {
        // Internal steps of one turn, so DEBUG by default. The turn reports itself through
        // "OpenRouter reply"; these only matter when it goes wrong.
        logActivity(
            category = "ai_stage",
            action = action,
            correlationId = input.turnId,
            summary = "$summary · ${model.take(256)}",
            details = JSONObject().put("model", model.take(256)),
        )
    }

    /**
     * The image tool disappearing is indistinguishable, from the outside, from a model that
     * refuses to send pictures — and that is exactly how it was reported. Name the reason:
     * pictures approved for another persona, or every picture already sent to this chat while
     * repeat blocking is on. DEBUG, so it lands in the internal log and not in the feed.
     */
    private fun recordImageToolWithheld(
        input: TurnInput,
        personaKey: String,
        blockRepeats: Boolean,
    ) {
        val personaHasAssets = approvedMedia.hasAssets(personaKey)
        val reason =
            when {
                !personaHasAssets -> "no_approved_asset_for_persona"
                blockRepeats -> "all_assets_already_sent_to_chat"
                else -> "no_readable_asset"
            }
        logActivity(
            category = "ai_tools",
            action = "image_send_unavailable",
            correlationId = input.turnId,
            summary = "Image sending is not available to this turn ($reason).",
            details =
                JSONObject()
                    .put("reason", reason)
                    .put("persona", personaKey.take(64))
                    .put("personaHasAssets", personaHasAssets)
                    .put("blockRepeats", blockRepeats),
        )
    }

    private fun recordToolTrace(
        input: TurnInput,
        result: de.totec.doppel.ai.OrchestrationResult,
        planned: List<PlannedSideEffect>,
    ) {
        if (result.response.strippedReasoning) {
            // WARN, not DEBUG: this is supposed to be impossible. OpenRouter returns the chain of
            // thought in a field this app never reads, so a row here means a model wrote its
            // monologue into `content` and the guard was the only thing between it and the chat.
            logActivity(
                category = "ai_stage",
                action = "reasoning_leak_stripped",
                level = de.totec.doppel.data.db.ActivityLevel.WARN,
                correlationId = input.turnId,
                summary = "Cut a reasoning block out of the completion before sending",
                details =
                    JSONObject()
                        .put("persona", input.settings.personality.take(64))
                        .put("bubblesLeft", result.response.bubbles.size)
                        .put("strippedCharacters", result.response.strippedReasoningCharacters),
            )
        }
        val actionKinds =
            planned.map { action ->
                when (action) {
                    is PlannedSideEffect.SynthesizeVoiceNote -> "send_voice_note"
                    is PlannedSideEffect.UploadApprovedImage -> "send_image"
                    is PlannedSideEffect.GenerateImage -> "generate_image"
                    is PlannedSideEffect.BlockContact -> "block_contact"
                    is PlannedSideEffect.RequestMemoryRefresh -> "refresh_memory"
                    is PlannedSideEffect.SendMedia -> "send_media"
                    is PlannedSideEffect.VoiceTextFallback -> "voice_text_fallback"
                    is PlannedSideEffect.ScheduleFollowUp -> "schedule_followup"
                }
            }
        logActivity(
            category = "tool_trace",
            action = if (result.commitAllowed) "tool_batch_committed" else "tool_batch_rejected",
            level =
                if (result.commitAllowed) {
                    de.totec.doppel.data.db.ActivityLevel.INFO
                } else {
                    de.totec.doppel.data.db.ActivityLevel.WARN
                },
            correlationId = input.turnId,
            // One row for the whole model turn. The tool loop can take several
            // HTTP calls to arrive at one answer; that is an implementation
            // detail, so it is not reported as "3 OpenRouter calls" — the tools
            // it actually used are what happened, and the call count stays in
            // the details for when a turn looks unexpectedly expensive.
            summary =
                if (result.commitAllowed) {
                    "OpenRouter reply" +
                        toolSuffix(actionKinds) +
                        tokenSuffix(result.usage)
                } else {
                    "OpenRouter reply discarded · " +
                        verificationLabel(result.verification.reasonCode) +
                        tokenSuffix(result.usage)
                },
            details =
                JSONObject()
                    .put("modelCalls", result.modelCalls)
                    .put("emptyCompletions", result.emptyCompletions)
                    .put("pendingActions", result.pendingActions.size)
                    .put("commitReceipts", result.commitReceipts.size)
                    .put("commitAllowed", result.commitAllowed)
                    .put("verification", result.verification.reasonCode.take(80))
                    .put("actionKinds", JSONArray(actionKinds))
                    .apply {
                        // Per-turn totals next to the per-call rows the network observer
                        // writes: together they show whether extra calls or a broken cache
                        // drove the bill.
                        result.usage?.let { usage ->
                            usage.promptTokens?.let { put("inputTokens", it) }
                            usage.completionTokens?.let { put("outputTokens", it) }
                            usage.totalTokens?.let { put("totalTokens", it) }
                            put("cacheReadTokens", usage.cachedPromptTokens ?: 0)
                            put("cacheWriteTokens", usage.cacheWriteTokens ?: 0)
                        }
                    },
        )
    }

    /** The tools the model reached for, named the way the feed names everything else. */
    private fun toolSuffix(actionKinds: List<String>): String {
        if (actionKinds.isEmpty()) return ""
        val labels =
            actionKinds.map { kind ->
                when (kind) {
                    "send_voice_note" -> "Voice note"
                    "send_image" -> "Image"
                    "block_contact" -> "Block contact"
                    "refresh_memory" -> "Memory"
                    "send_media" -> "Media"
                    "voice_text_fallback" -> "Voice note as text"
                    else -> kind.take(40)
                }
            }
        return " · " +
            labels
                .groupingBy { it }
                .eachCount()
                .entries
                .joinToString(", ") { (label, count) ->
                    if (count > 1) "$label ×$count" else label
                }
    }

    private fun tokenSuffix(usage: de.totec.doppel.ai.TokenUsage?): String {
        val input = usage?.promptTokens ?: return ""
        val cached = usage.cachedPromptTokens ?: 0
        val output = usage.completionTokens ?: 0
        return " · in $input" +
            (if (cached > 0) " ($cached cached)" else "") +
            " · out $output"
    }

    /**
     * A turn that produced nothing the user can perceive, without the model asking for silence.
     * Distinct from an intentional `noReply`, which is a decision rather than a failure.
     */
    private fun de.totec.doppel.ai.OrchestrationResult.isEmptyAnswer(
        planned: List<PlannedSideEffect>,
    ): Boolean =
        !response.noReply &&
            response.bubbles.none(String::isNotBlank) &&
            response.reaction == null &&
            planned.isEmpty()

    /**
     * The last stop before a bubble becomes real: strips the quotation marks and decorative emoji
     * the prompt already forbids, and leaves the typing rough.
     *
     * It sits here rather than in the send path on purpose. The string returned from this method is
     * the one the engine sends *and* the one it writes into the durable history, so the next turn
     * reads back the cleaned message instead of the model's own emoji-laden draft. Sanitising at
     * send time only would keep teaching the model the style the prompt is trying to suppress.
     */
    private fun humanized(
        input: TurnInput,
        bubbles: List<String>,
        seed: String,
        recentExtra: List<String> = emptyList(),
    ): List<String> {
        if (bubbles.isEmpty()) return bubbles
        val recent =
            (input.history.filter { it.role.equals("assistant", ignoreCase = true) }.map { it.text } + recentExtra)
                .map { HistoryLabelGuard.stripLeadingLabel(it) }
        val outcome =
            OutgoingHumanizer.humanize(
                bubbles = bubbles,
                recentAssistantTexts = recent,
                profile = OutgoingHumanizer.Profile.forPersona(input.settings.personality),
                seed = seed,
            )
        if (outcome.changed) {
            // Expected on most turns, so DEBUG. It matters when the opposite shows up:
            // a chat full of emoji with no humanizer rows means this never ran.
            logActivity(
                category = "ai_stage",
                action = "outgoing_humanized",
                correlationId = input.turnId,
                summary = "Cleaned the outgoing text · ${outcome.rules.sorted().joinToString(", ")}",
                details =
                    JSONObject()
                        .put("rules", outcome.rules.sorted().joinToString(","))
                        .put("bubbles", bubbles.size),
            )
        }
        return outcome.bubbles
    }

    private fun recordSilence(
        input: TurnInput,
        reason: String,
        summary: String,
        details: JSONObject,
    ) {
        logActivity(
            category = "ai_stage",
            action = reason,
            level = de.totec.doppel.data.db.ActivityLevel.WARN,
            correlationId = input.turnId,
            summary = summary,
            details = details.put("reason", reason.take(80)),
        )
    }

    private fun recordVerificationFallback(
        input: TurnInput,
        reasonCode: String,
        fallback: de.totec.doppel.ai.VerificationFallback,
    ) {
        val passedThrough =
            fallback.policy == de.totec.doppel.ai.VerificationFallback.POLICY_FAIL_OPEN
        logActivity(
            category = "ai_stage",
            action = "verification_fallback",
            level = de.totec.doppel.data.db.ActivityLevel.WARN,
            correlationId = input.turnId,
            summary =
                "Verification without a verdict · ${verificationLabel(fallback.cause)} · " +
                    if (passedThrough) {
                        "sending the reply anyway"
                    } else {
                        "reply blocked"
                    },
            details =
                JSONObject()
                    .put("verification", reasonCode.take(80))
                    .put("fallbackCause", fallback.cause.take(80))
                    .put("fallbackPolicy", fallback.policy)
                    .apply { fallback.detail?.let { put("fallbackDetail", it.take(200)) } },
        )
    }

    private fun verificationLabel(reasonCode: String): String =
        when (reasonCode) {
            "empty_verifier_response" -> "verifier model returned no text (budget too small?)"
            "invalid_verifier_response" -> "verdict was not valid JSON"
            "verifier_verdict_missing" -> "verdict without an \"allow\" field"
            "verifier_failed" -> "verifier call failed"
            "verifier_unavailable" -> "no verifier model available"
            "no_reply_with_actions" -> "silence together with actions is not allowed"
            "action_commit_failed" -> "actions could not be committed"
            "not_verified" -> "no verification ran"
            else -> reasonCode.take(80)
        }

    private fun recordVoiceTrace(
        action: PlannedSideEffect.SynthesizeVoiceNote,
        stage: String,
        summary: String,
        details: JSONObject,
    ) {
        // Synthesis internals, so DEBUG. The TTS call itself is already one INFO row
        // from the network observer, and the voice note shows up as a sent
        // message — repeating both here is what made the feed unreadable.
        logActivity(
            category = "voice_trace",
            action = stage,
            correlationId = action.idempotencyKey,
            summary = summary,
            details = details,
        )
    }

    private suspend fun materializeApprovedImage(
        chatId: String,
        action: PlannedSideEffect.UploadApprovedImage,
        settings: ResolvedTurnSettings?,
    ): PlannedSideEffect.SendMedia {
        if (
            action.blockRepeats &&
            approvedMedia.wasSentTo(
                assetId = action.assetId,
                personaKey = action.personaKey,
                chatId = chatId,
            )
        ) {
            error("Image was already sent to this chat")
        }
        val handle =
            approvedMedia.openForSend(
                assetId = action.assetId,
                personaKey = action.personaKey,
            )
        val uploaded =
            bridgeMedia.upload(
                file = handle.file,
                mimeType = handle.asset.mimeType,
                maxBytes = MAX_UPLOAD_BYTES,
            )
        require(uploaded.sizeBytes == handle.asset.sizeBytes) {
            "Bridge confirmed a different image size"
        }
        uploaded.sha256?.let { remoteHash ->
            require(remoteHash.equals(handle.asset.sha256, ignoreCase = true)) {
                "Bridge confirmed a different image hash"
            }
        }
        // A file name alone told the next model call nothing about what was actually shown, so the
        // follow-up answered its own picture blind. The description comes from the same multimodal
        // model an incoming photo goes through, and it is cached per asset.
        val description =
            settings?.let {
                mediaAnalyzer.describeOutgoingImage(
                    file = handle.file,
                    mimeType = handle.asset.mimeType,
                    sha256 = handle.asset.sha256,
                    settings = it,
                    imageEnabled = settingsRepository.snapshot().boolean(BotSettingKeys.VISION_ENABLED),
                )
            }
        return PlannedSideEffect.SendMedia(
            idempotencyKey = action.idempotencyKey,
            uploadId = uploaded.uploadId,
            mimeType = handle.asset.mimeType,
            voiceNote = false,
            caption = action.caption,
            approvedAssetId = action.assetId,
            approvedPersonaKey = action.personaKey,
            // The display name stays first: it is what the model picked the image by, and it is what
            // lets a later turn know which one it already sent.
            historyText =
                listOfNotNull(
                    handle.asset.displayName.trim().takeIf(String::isNotEmpty),
                    action.caption?.trim()?.takeIf(String::isNotEmpty),
                    description?.trim()?.takeIf(String::isNotEmpty),
                ).distinct().joinToString(" · "),
        )
    }

    private suspend fun materializeGeneratedImage(
        action: PlannedSideEffect.GenerateImage,
    ): PlannedSideEffect.SendMedia {
        // References sharpen the likeness when the persona has them; they are not a precondition.
        // This used to `require` a non-empty list, which aborted the picture locally — before any
        // request existed — for every persona that has no base images. The provider never saw the
        // attempt, so the failure could not be diagnosed from the OpenRouter log either.
        val references =
            if (action.includeCharacter) {
                runCatching { characterReferences.readAll(action.personaKey) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val prompt =
            listOf(
                action.prefix.trim(),
                when {
                    action.includeCharacter && references.isNotEmpty() ->
                        "Keep the same adult person's recognizable identity, facial structure, " +
                            "complexion, hair and natural proportions as the attached private " +
                            "character references."
                    // No stored likeness to match, so the only requirement left is that the person
                    // in frame is plausibly the same adult every time and unmistakably an adult.
                    action.includeCharacter ->
                        "You are visible in this photo. There is no reference photo attached, so " +
                            "render one ordinary adult woman with natural, consistent features and " +
                            "proportions. She must clearly be an adult."
                    else -> "No active-persona character is visible in this scene; do not invent one."
                },
                "Scene requested for this WhatsApp photo: ${action.prompt.trim()}",
                "Return one realistic image only. No text, watermark, collage, contact sheet, studio reference sheet or extra view.",
            ).filter(String::isNotBlank)
                .joinToString("\n\n")
                .take(MAX_IMAGE_GENERATION_PROMPT_CHARS)
        val generated =
            imageClient.generate(
                de.totec.doppel.ai.ImageGenerationRequest(
                    model = action.model,
                    prompt = prompt,
                    references =
                        references.map {
                            ImageReferenceInput(bytes = it.bytes, mimeType = it.mimeType)
                        },
                    quality = action.quality,
                ),
            )
        val extension =
            when (generated.mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
        val destination =
            File(
                generatedImageDirectory.apply { mkdirs() },
                "generated-${sha256Hex(action.idempotencyKey).take(24)}-${UUID.randomUUID()}.$extension",
            )
        return try {
            withContext(Dispatchers.IO) { destination.writeBytes(generated.bytes) }
            val uploaded =
                bridgeMedia.upload(
                    file = destination,
                    mimeType = generated.mimeType,
                    maxBytes = MAX_UPLOAD_BYTES,
                )
            require(uploaded.sizeBytes == generated.bytes.size.toLong()) {
                "Bridge confirmed a different generated image size"
            }
            val kept = keepGeneratedImage(action, destination, generated.mimeType)
            PlannedSideEffect.SendMedia(
                idempotencyKey = action.idempotencyKey,
                uploadId = uploaded.uploadId,
                mimeType = generated.mimeType,
                voiceNote = false,
                caption = action.caption,
                // Naming the asset here is what makes the send mark it as delivered to this chat,
                // so the picture that was just paid for can be reused everywhere except where it
                // has already been seen.
                approvedAssetId = kept?.assetId,
                approvedPersonaKey = kept?.let { action.personaKey },
                historyText =
                    listOfNotNull(
                        "a generated casual phone photo: ${action.prompt.take(400)}",
                        action.caption,
                    ).joinToString(" · "),
            )
        } finally {
            destination.delete()
        }
    }

    /**
     * Files a freshly generated picture in the persona's ordinary sendable library.
     *
     * A generation is the most expensive thing this bot does and it used to be thrown away the
     * moment it was sent, so the next chat that wanted the same kind of photo paid for it again.
     * Kept, it becomes an ordinary approved image: it shows up in `list_sendable_images`, it can be
     * sent by name, and it carries the same once-per-chat ledger as every other picture. The
     * library therefore grows with use and the cost per picture falls.
     *
     * Never fatal. The picture is already uploaded and on its way; a full store, a quota or a
     * damaged file may cost the reuse but must not cost the send.
     */
    private suspend fun keepGeneratedImage(
        action: PlannedSideEffect.GenerateImage,
        file: File,
        mimeType: String,
    ): ApprovedMediaAsset? =
        withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { source ->
                    approvedMedia.importImage(
                        personaKey = action.personaKey,
                        displayName = generatedImageName(action),
                        declaredMimeType = mimeType,
                        source = source,
                        kind = ApprovedMediaKind.IMAGE,
                    ).asset
                }
            }.getOrNull()
        }

    /**
     * The name the model will later find this picture by, so it has to say what is in it.
     *
     * The prompt is the only description that exists at this point and it was written for exactly
     * this purpose — "my cat asleep on a grey blanket, morning light" is both what was made and
     * what a later turn searches for. The caption is the chat line that went with it, which says
     * how it was used rather than what it shows, so it only fills in when there is no prompt left
     * after trimming.
     */
    private fun generatedImageName(action: PlannedSideEffect.GenerateImage): String {
        val subject =
            action.prompt.replace(WHITESPACE, " ").trim()
                .ifEmpty { action.caption?.replace(WHITESPACE, " ")?.trim().orEmpty() }
                .ifEmpty { "photo" }
        val prefix = if (action.includeCharacter) "selfie: " else "photo: "
        return (prefix + subject).take(MAX_GENERATED_IMAGE_NAME_CHARS)
    }

    private fun stableSeed(value: String): Long =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .fold(0L) { accumulator, byte ->
                (accumulator shl 8) or (byte.toLong() and 0xffL)
            }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun resolveQuoteTarget(
        input: TurnInput,
        sourceSnippet: String?,
    ): String? {
        val currentMessageIds = input.newestEvents.mapTo(mutableSetOf()) { it.messageId }
        val candidates =
            buildList {
                input.history
                    .asSequence()
                    .filter { it.role.equals("user", ignoreCase = true) }
                    .filterNot { it.id in currentMessageIds }
                    .forEach { message ->
                        add(ReplyTargetCandidate(message.id, message.text))
                    }
                input.newestEvents.forEach { event ->
                    add(
                        ReplyTargetCandidate(
                            messageId = event.messageId,
                            searchableText =
                                listOfNotNull(
                                    event.text.takeIf(String::isNotBlank),
                                    event.media?.caption?.takeIf(String::isNotBlank),
                                    event.quoted?.text?.takeIf(String::isNotBlank),
                                    // Incoming media analysis persists a voice transcript on the
                                    // original provider-message row during this turn. Include it so
                                    // a per-bubble Reply can target that exact voice note.
                                    repository
                                        .getMessage(event.messageId)
                                        ?.body
                                        ?.takeIf(String::isNotBlank),
                                ).joinToString(" "),
                        ),
                    )
                }
            }
        return resolveReplyTargetId(candidates, sourceSnippet)
    }

    /**
     * The message a reaction was attached to, preferably from this turn's own history so the
     * wording matches what the model is already reading, and from the database otherwise.
     */
    private fun reactionTargetText(
        input: TurnInput,
        event: de.totec.doppel.domain.IncomingEvent,
    ): String? {
        val targetId = event.targetMessageId?.takeIf(String::isNotBlank) ?: return null
        val body =
            input.history.firstOrNull { it.id == targetId }?.text
                ?: runCatching { repository.getMessage(targetId) }.getOrNull()?.body
        return body?.let(HistoryLabelGuard::stripLeadingLabel)?.takeIf(String::isNotBlank)
    }

    /**
     * Whether this event can be rendered as anything the model could answer.
     *
     * Deliberately mirrors the parts [buildLatestMessage] assembles, and is deliberately cheap: it
     * runs before any media analysis, so an event carrying media always counts as readable — the
     * transcript or description is what makes it so, and that is exactly what the analysis produces.
     */
    private fun isReadable(event: de.totec.doppel.domain.IncomingEvent): Boolean =
        event.media != null ||
            event.text.isNotBlank() ||
            event.reactionEmoji != null ||
            event.quoted != null ||
            event.kind == de.totec.doppel.domain.ChatEventKind.DELETE ||
            event.kind == de.totec.doppel.domain.ChatEventKind.CALL_MISSED

    private suspend fun buildLatestMessage(
        input: TurnInput,
        settings: ResolvedTurnSettings,
        snapshot: de.totec.doppel.settings.SettingsSnapshot,
    ): String {
        if (input.proactive) {
            val localReason =
                input.newestEvents
                    .lastOrNull()
                    ?.text
                    .orEmpty()
                    .trim()
                    .take(MAX_LATEST_CHARACTERS / 2)
            return buildString {
                appendLine("This is a proactive one-to-one check, not a new message from the contact.")
                appendLine("Write at most one short, natural opener, with no forced question and without pretending something new just came in.")
                if (localReason.isNotEmpty()) appendLine("Local context: $localReason")
                // The history is the only reliable answer to "have these two ever spoken".
                // Whether the chat row exists says nothing: it is created the moment the user
                // arms the contact, before a single message has been exchanged.
                if (input.history.isEmpty()) {
                    appendLine(
                        "This is the first message in this chat. There is no shared history to " +
                            "refer back to, so write a hello or something equally light.",
                    )
                }
                // Last, and deliberately so. Offered the choice in the abstract, the model takes
                // the safe way out and says nothing in the large majority of these turns — which
                // makes an outreach the operator asked for do nothing at all. The escape stays,
                // because a second unanswered ping is a real risk, but it is the exception now
                // and the line the model reads last is the one that says to write.
                appendLine(
                    "Answer with exactly [no reply] only if writing right now would clearly be a " +
                        "mistake — for example your last message to them is still unanswered.",
                )
                append("Otherwise write the message. Reaching out is the expected outcome here.")
            }.trim().take(MAX_LATEST_CHARACTERS)
        }
        val maxVideoBytes = snapshot.appInteger(AppSettingKeys.MAX_VIDEO_BYTES).toLong()
        val analyses = analyzeIncomingMedia(input, settings, snapshot, maxVideoBytes)
        val messages = mutableListOf<String>()
        val nowMs = System.currentTimeMillis()
        for (event in input.newestEvents) {
            val parts = mutableListOf<String>()
            val quoted = event.quoted
            val quotedText = quoted?.text?.trim()?.take(MAX_QUOTED_CHARACTERS)
            val plainText =
                if (event.media == null) event.text.trim().takeIf(String::isNotEmpty) else null
            if (quoted != null && plainText == null) {
                // No line of its own to fold the reference into, so it keeps one.
                parts +=
                    de.totec.doppel.engine.ChatHistoryLabels.replyContext(
                        quotedText,
                        quoted.messageId.take(MAX_QUOTED_ID_CHARACTERS),
                    )
            }
            if (plainText != null) {
                // The reference rides on the sender's own line. Standing above it, it put the
                // contact's words directly in front of the answer on every quoted message, and the
                // model eventually copied the shape and sent those words back as its own.
                parts +=
                    de.totec.doppel.engine.ChatHistoryLabels.incomingText(plainText, quotedText)
            }
            event.reactionEmoji?.let {
                // The emoji alone is the whole turn for a reaction-only event. Naming the message
                // it hangs on is the difference between answering it and inventing a reason for it.
                parts +=
                    de.totec.doppel.engine.ChatHistoryLabels.incomingReaction(
                        emoji = it,
                        targetText = reactionTargetText(input, event),
                    )
            }
            if (event.kind == de.totec.doppel.domain.ChatEventKind.DELETE) {
                parts += "[Message deleted]"
            }
            if (event.kind == de.totec.doppel.domain.ChatEventKind.CALL_MISSED) {
                parts +=
                    de.totec.doppel.engine.ChatHistoryLabels.incomingMissedCall(
                        event.callMedia == "video",
                    )
            }
            var analysis: MediaAnalysisResult? = null
            event.media?.let { media ->
                val result = analyses.getValue(mediaAnalysisKey(media))
                analysis = result
                // Named, not just transcribed: a bare transcript is
                // indistinguishable from typed text, so she used to answer voice
                // notes and photos as if they had been written to her.
                parts +=
                    de.totec.doppel.domain.MediaHistoryLabels.incomingLine(
                        kind = media.kind,
                        detail =
                            listOfNotNull(
                                event.text.takeIf(String::isNotBlank)
                                    ?: media.caption?.takeIf(String::isNotBlank),
                                result.text
                                    .takeIf(String::isNotBlank)
                                    ?.takeUnless {
                                        it == de.totec.doppel.domain.MediaHistoryLabels.marker(media.kind)
                                    },
                            ).distinct().joinToString(" · ").takeIf(String::isNotBlank),
                    )
            }
            val body = parts.joinToString("\n").ifBlank { "[Empty WhatsApp action]" }
            if (event.media != null) persistMediaBody(event, body, analysis)
            val named =
                event.senderName?.takeIf(String::isNotBlank)?.let { "$it: $body" } ?: body
            // Replayed after a reconnect, or answered late after a deferral: say so, instead of
            // letting the model infer the gap and invent a timestamp header for it. Added after
            // persistence so the stored body stays the message itself.
            messages +=
                CatchUpMarker.forMessage(event.timestampMs, nowMs)
                    ?.let { "$it\n$named" }
                    ?: named
        }
        return newestMessagesWithinBudget(messages, MAX_LATEST_CHARACTERS)
    }

    /**
     * Analyses everything the batch brought with it, concurrently, before a single prompt line is
     * built.
     *
     * Three photos in one burst used to be three model round trips laid end to end inside the
     * reply's critical path — and by then the whole pickup delay had already been spent, so the
     * contact watched the typing indicator through all of them. They are independent requests;
     * nothing about them was ever sequential except the loop they sat in.
     *
     * Two things the sequential version got for free have to be stated here. Identical clips are
     * collapsed to one call, because the analyser's content-hash cache only helps the *second*
     * lookup and parallel requests would race straight past it. And the permit caps the fan-out:
     * a ten-image dump must not become ten simultaneous uploads on a phone connection.
     *
     * Analysis never throws — a failure comes back as an unanalysed result — so no clip can take
     * the turn down with it. Cancellation still propagates, which is what an interrupt needs.
     */
    private suspend fun analyzeIncomingMedia(
        input: TurnInput,
        settings: ResolvedTurnSettings,
        snapshot: de.totec.doppel.settings.SettingsSnapshot,
        maxVideoBytes: Long,
    ): Map<String, MediaAnalysisResult> {
        val distinctMedia =
            input.newestEvents
                .mapNotNull { event -> event.media }
                .associateBy(::mediaAnalysisKey)
        if (distinctMedia.isEmpty()) return emptyMap()
        // Looking at a photo or listening to a voice note is its own paid call, running before the
        // main one. Without this the row says "thinking" through a wait it is not yet in.
        activity?.let { feed ->
            val mediaModel = shortModelName(settings.model(ModelRole.MEDIA))
            val now = System.currentTimeMillis()
            feed.stage(input.chatJid, ChatStage.ANALYZING, detail = mediaModel, nowMs = now)
            feed.trace(
                chatJid = input.chatJid,
                kind = TraceKind.STEP,
                text =
                    "Looking at ${distinctMedia.size} attachment" +
                        (if (distinctMedia.size == 1) "" else "s") + " · $mediaModel",
                nowMs = now,
            )
        }
        val permits = Semaphore(MAX_PARALLEL_MEDIA_ANALYSES)
        val analysed =
            coroutineScope {
                distinctMedia
                    .map { (key, media) ->
                        key to
                            async {
                                permits.withPermit {
                                    mediaAnalyzer.analyzeMedia(
                                        media = media,
                                        settings = settings,
                                        imageEnabled = snapshot.boolean(BotSettingKeys.VISION_ENABLED),
                                        audioEnabled = snapshot.boolean(BotSettingKeys.STT_ENABLED),
                                        videoEnabled = snapshot.boolean(BotSettingKeys.VIDEO_ENABLED),
                                        maxVideoBytes = maxVideoBytes,
                                    )
                                }
                            }
                    }
                    .associate { (key, analysis) -> key to analysis.await() }
            }
        // What the attachment turned into is the whole point of having paid for the call, and an
        // attachment that could not be read is the reason a reply afterwards ignores it.
        analysed.forEach { (key, result) ->
            val what =
                when (distinctMedia[key]?.kind) {
                    MediaKind.IMAGE -> "Picture"
                    MediaKind.AUDIO -> "Voice message"
                    MediaKind.VIDEO -> "Video"
                    MediaKind.DOCUMENT -> "Document"
                    MediaKind.STICKER -> "Sticker"
                    else -> "Attachment"
                }
            liveTrace(
                chatJid = input.chatJid,
                kind = if (result.analyzed) TraceKind.STEP else TraceKind.PROBLEM,
                text =
                    if (result.analyzed) {
                        "$what understood · ${result.text.length} characters"
                    } else {
                        "$what not read · ${result.reason}"
                    },
            )
        }
        return analysed
    }

    /**
     * What makes two attachments the same attachment. The content hash when WhatsApp supplied one,
     * otherwise the media id — which is also the name of the temporary file the analyser downloads
     * into, so sharing the key is what keeps two concurrent analyses off the same path.
     */
    private fun mediaAnalysisKey(media: de.totec.doppel.domain.MediaReference): String =
        media.sha256?.takeIf(String::isNotBlank) ?: media.id

    /**
     * Write a media message's analysed text back onto its stored row.
     *
     * Media is persisted the moment it arrives, but with no body of its own —
     * a voice note has neither text nor caption. History skips bodyless rows, so
     * until the transcript lands here the message exists only inside the one turn
     * that analysed it. Persisting it keeps the content available to every later
     * turn and to the memory summariser, and means the media model is never paid
     * for twice for the same clip.
     */
    private fun persistMediaBody(
        event: de.totec.doppel.domain.IncomingEvent,
        body: String,
        analysis: MediaAnalysisResult?,
    ) {
        val text =
            body.trim().takeIf(String::isNotEmpty)?.take(MAX_PERSISTED_MEDIA_BODY_CHARS) ?: return
        val stored =
            runCatching {
                repository.updateMessageBody(providerMessageId = event.messageId, body = text)
            }
        val kind = event.media?.kind?.name?.lowercase() ?: "media"
        logActivity(
            category = "media_analysis",
            action =
                when {
                    !stored.isSuccess -> "body_persist_failed"
                    // The clip is in the history, but no model ever looked at
                    // it. Recorded under its own action so media that was
                    // merely stored can never be mistaken — in the log or by
                    // a later reader — for media that was understood.
                    analysis?.analyzed == false -> "body_persisted_unanalyzed"
                    else -> "body_persisted"
                },
            level =
                if (stored.isSuccess && analysis?.analyzed != false) {
                    de.totec.doppel.data.db.ActivityLevel.DEBUG
                } else {
                    de.totec.doppel.data.db.ActivityLevel.WARN
                },
            chatId = event.chatJid,
            correlationId = event.eventId,
            summary =
                stored.fold(
                    onSuccess = {
                        if (analysis?.analyzed == false) {
                            "Media stored without analysis · $kind · ${analysis?.reason.orEmpty()}"
                        } else {
                            "Media content stored in the history · $kind · ${text.length} characters"
                        }
                    },
                    onFailure = {
                        "Media content could not be stored · $kind · " +
                            it.message.orEmpty().take(160)
                    },
                ),
            details =
                JSONObject()
                    .put("mediaKind", kind)
                    .put("characters", text.length)
                    .put("messageId", event.messageId.take(256))
                    .put("analyzed", analysis?.analyzed ?: true)
                    .put("analysisReason", analysis?.reason ?: "no_media"),
        )
    }

    /**
     * True inside the last [SLEEP_WINDDOWN_MINUTES] before the configured sleep start, ported from
     * the reference build's `buildSleepWindowPrompt`. Only meaningful for the human preset — the
     * instant preset has no bedtime — and it rides in the volatile tail next to the clock, so it
     * never breaks the cached prefix.
     */
    private fun isSleepWindDown(settings: de.totec.doppel.engine.EngineSettingsSnapshot): Boolean {
        if (settings.replyPreset != de.totec.doppel.engine.ReplyPreset.HUMAN) return false
        val local = java.time.ZonedDateTime.now(settings.timezone)
        val minuteOfDay = local.hour * 60 + local.minute
        val untilSleep = ((settings.sleepStartMinutes - minuteOfDay) + 1_440) % 1_440
        return untilSleep in 1 until SLEEP_WINDDOWN_MINUTES
    }

    private fun traitValues(
        snapshot: de.totec.doppel.settings.SettingsSnapshot,
    ): Map<String, Int> =
        TRAIT_KEYS.associateWith(snapshot::integer)

    private fun de.totec.doppel.settings.SettingsSnapshot.toAiSettings(
        imageSending: Boolean,
        imageGeneration: Boolean,
        personaKey: String,
        contactBlockingAllowed: Boolean,
    ): ResolvedTurnSettings {
        // Persisted installs may still carry the former sentinel 0. Treat it as the new safe
        // writing budget so upgrading fixes the freeze without requiring a manual settings edit.
        val maxTokens = integer(BotSettingKeys.MAX_TOKENS).takeIf { it > 0 } ?: 10_000
        val memoryLimit = integer(BotSettingKeys.MEMORY_CHAR_LIMIT).takeIf { it > 0 }
        return ResolvedTurnSettings(
            models =
                mapOf(
                    ModelRole.MAIN to text(BotSettingKeys.MODEL),
                    ModelRole.VERIFY to text(BotSettingKeys.VERIFY_MODEL),
                    ModelRole.MEDIA to text(BotSettingKeys.MEDIA_MODEL),
                    ModelRole.TTS to text(BotSettingKeys.TTS_MODEL),
                ),
            baseInstructions = text(BotSettingKeys.BASE_PROMPT),
            customInstructions =
                text(BotSettingKeys.SYSTEM_PROMPT).takeIf {
                    personaKey != "custom" && personaKey == text(BotSettingKeys.PERSONALITY)
                }.orEmpty(),
            sampling =
                SamplingSettings(
                    temperature = decimal(BotSettingKeys.TEMPERATURE),
                    topP = decimal(BotSettingKeys.TOP_P),
                    frequencyPenalty = decimal(BotSettingKeys.FREQUENCY_PENALTY),
                    presencePenalty = decimal(BotSettingKeys.PRESENCE_PENALTY),
                    maxTokens = maxTokens,
                    reasoningEffort = reasoning(text(BotSettingKeys.REASONING_EFFORT)),
                ),
            context =
                ContextSettings(
                    // The store owns the successful-memory-locked "overlap -> full window" growth
                    // (10 -> 160 by default). Rendering all rows it hands over prevents a second
                    // limit from recreating a sliding window.
                    historyLimit = 0,
                    memoryCharacterLimit = memoryLimit,
                    historyWindowSlack = 0,
                ),
            output =
                OutputSettings(
                    allowNoReply = true,
                    allowReactions = boolean(BotSettingKeys.ENABLE_REACTIONS),
                    allowQuoteReply = boolean(BotSettingKeys.ENABLE_QUOTE_REPLY),
                    maxBubbles = 8,
                ),
            tools =
                ToolAccessSettings(
                    // This is the single explicit local-data permission. When enabled, the
                    // matching tool schemas are actually exposed to the model.
                    crossChatSearch = boolean(BotSettingKeys.CROSS_CHAT_SEARCH),
                    imageSending = imageSending,
                    imageGeneration = imageGeneration,
                    voiceNotes = boolean(BotSettingKeys.TTS_ENABLED),
                    // Off it is not a tool that is merely discouraged: there is nothing for it to
                    // queue, so offering it would only spend a tool call on a no-op.
                    memoryRefresh = boolean(BotSettingKeys.MEMORY_CHAT_ENABLED),
                    contactBlocking =
                        boolean(BotSettingKeys.BLOCK_TOOL_ENABLED) &&
                            contactBlockingAllowed,
                ),
            verification =
                VerificationSettings(
                    enabled = boolean(BotSettingKeys.AUTO_VERIFY),
                    maxRegenerations = integer(BotSettingKeys.VERIFY_MAX_RETRIES),
                    maxTokens = integer(BotSettingKeys.VERIFY_MAX_TOKENS),
                    reasoningEffort = reasoning(text(BotSettingKeys.VERIFY_REASONING_EFFORT)),
                    failClosed = boolean(BotSettingKeys.VERIFY_FAIL_CLOSED),
                ),
            mediaAnalysisPrompts =
                MediaAnalysisPromptSettings(
                    image = text(BotSettingKeys.IMAGE_ANALYSIS_PROMPT),
                    video = text(BotSettingKeys.VIDEO_ANALYSIS_PROMPT),
                    voice = text(BotSettingKeys.VOICE_ANALYSIS_PROMPT),
                ),
            mediaReasoningEffort = reasoning(text(BotSettingKeys.MEDIA_REASONING_EFFORT)),
            preferStreaming = true,
            // Room to work, not a licence to spend: a turn that needs several lookups gets them,
            // and one that does not costs exactly one call as before. Reaching the ceiling now ends
            // in an answer rather than a discarded turn.
            toolLoopLimit = 10,
        )
    }

    private fun reasoning(value: String): ReasoningEffort =
        when (value.lowercase()) {
            "none" -> ReasoningEffort.NONE
            "minimal" -> ReasoningEffort.MINIMAL
            "low" -> ReasoningEffort.LOW
            "medium" -> ReasoningEffort.MEDIUM
            "high" -> ReasoningEffort.HIGH
            "xhigh" -> ReasoningEffort.EXTRA_HIGH
            "max" -> ReasoningEffort.MAX
            else -> ReasoningEffort.PROVIDER_DEFAULT
        }

    private companion object {
        const val MAX_LATEST_CHARACTERS = 48_000
        const val MAX_IMAGE_GENERATION_PROMPT_CHARS = 8_000

        /**
         * Room for a full scene description, kept under the store's own name limit so a long
         * prompt is shortened here rather than rejected there.
         */
        const val MAX_GENERATED_IMAGE_NAME_CHARS = 110
        val WHITESPACE = Regex("\\s+")
        const val IMPORT_MEMORY_SOURCE_CHARACTERS = 500_000
        const val IMPORT_MEMORY_SOURCE_MESSAGES = 5_000

        /** Wind-down window before the configured sleep start, same as the reference build. */
        const val SLEEP_WINDDOWN_MINUTES = 30

        /** Prompts parked for a possible memory refresh; bounded against turns that never send. */
        const val MAX_PENDING_MEMORY_PROMPTS = 16

        /**
         * Conversations whose last prompt stays available for a hand-triggered memory write. Each
         * entry holds a full turn prompt, so this is the number of recently active chats worth
         * paying for, not a cache to grow.
         */
        const val MAX_CONVERSATION_MEMORY_PROMPTS = 12
        const val MAX_PENDING_VISIBLE_FOLLOW_UPS = 16

        /**
         * Reserved slot for the text rewrite after a refused voice note. It is not a media
         * follow-up, so it gets its own idempotency namespace rather than an index that would
         * collide with a real follow-up round.
         */
        const val VOICE_REWRITE_FOLLOW_UP_INDEX = -1

        /** Same idea for the retry after a repeated answer: its own namespace, never a real round. */
        const val REPETITION_RETRY_FOLLOW_UP_INDEX = -2

        /** Same idea for the answer that replaces a picture the provider would not create. */
        const val IMAGE_FALLBACK_FOLLOW_UP_INDEX = -3

        /**
         * Generation attempts per turn before the model is told to stop asking for a picture.
         *
         * Two, so a provider that refuses one particular scene gets a single informed second try
         * and nothing more: every attempt is billed whether or not an image comes back, and a model
         * that is told only "it failed" will otherwise keep paying to find that out again.
         */
        const val MAX_IMAGE_GENERATION_ATTEMPTS = 2

        /** Transcript/description written back onto a media row; keeps history rows bounded. */
        const val MAX_PERSISTED_MEDIA_BODY_CHARS = 4_000

        /**
         * Concurrent media analyses per turn. Three is the point where a burst stops being
         * serialised without a phone's uplink having to carry an unbounded number of uploads.
         */
        const val MAX_PARALLEL_MEDIA_ANALYSES = 3

        // Column limits of the activity log, mirrored so [logActivity] trims instead of failing
        // an insert. See BotDbSchema's activity_log CHECK constraints.
        const val MAX_ACTIVITY_ACTION_CHARS = 128
        const val MAX_CORRELATION_CHARS = 512
        const val MAX_ACTIVITY_SUMMARY_CHARS = 4_096
        const val MAX_QUOTED_CHARACTERS = 1_500
        const val MAX_QUOTED_ID_CHARACTERS = 128
        const val MAX_PCM_BYTES = 24 * 1024 * 1024
        const val MAX_UPLOAD_BYTES = 32L * 1024 * 1024
        val TRAIT_KEYS =
            listOf(
                BotSettingKeys.TRAIT_OBEDIENCE,
                BotSettingKeys.TRAIT_FLIRT,
                BotSettingKeys.TRAIT_LEWD,
                BotSettingKeys.TRAIT_MEANNESS,
                BotSettingKeys.TRAIT_INITIATIVE,
                BotSettingKeys.TRAIT_OPENNESS,
                BotSettingKeys.TRAIT_SUSPICION,
                BotSettingKeys.TRAIT_PLAYFULNESS,
                BotSettingKeys.TRAIT_CHAOS,
            )
    }
}

/** Keeps complete newest events first, while returning the selected events in chat order. */
internal fun newestMessagesWithinBudget(messages: List<String>, characterLimit: Int): String {
    require(characterLimit > 0) { "characterLimit must be positive" }
    if (messages.isEmpty()) return ""
    val selected = ArrayDeque<String>()
    var remaining = characterLimit
    for (message in messages.asReversed()) {
        if (remaining <= 0) break
        val separator = if (selected.isEmpty()) 0 else 2
        if (remaining <= separator) break
        if (message.length + separator > remaining && selected.isNotEmpty()) break
        val piece = message.take(remaining - separator)
        selected.addFirst(piece)
        remaining -= piece.length + separator
        if (piece.length < message.length) break
    }
    return selected.joinToString("\n\n")
}

private fun TurnInput.currentSenderAliases(): List<String> {
    val event = newestEvents.lastOrNull() ?: return emptyList()
    return buildList {
        addAll(event.senderAliases)
        if (!event.isGroup) {
            add(event.chatJid)
            addAll(event.chatAliases)
        }
    }.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(16)
        .toList()
}

private class TurnActionCommitter(
    private val planned: MutableList<PlannedSideEffect>,
    private val currentSender: String?,
    private val currentSenderAliases: List<String>,
    private val turnId: String,
    private val voice: String,
    private val personaKey: String,
    private val ttsModel: String,
    private val ttsQuality: Int,
    private val baseVoiceStyle: String?,
    private val blockRepeatedImages: Boolean,
    private val imageGenerationModel: String,
    private val imageGenerationQuality: String,
    private val imageGenerationPrefix: String,
) : PendingActionCommitter {
    override suspend fun commit(
        context: ToolExecutionContext,
        actions: List<PendingAction>,
    ): List<ActionCommitReceipt> =
        actions.map { action ->
            try {
                when (action) {
                    is PendingAction.RefreshChatMemory ->
                        planned +=
                            PlannedSideEffect.RequestMemoryRefresh(
                                idempotencyKey = "$turnId:memory:${action.toolCallId}",
                                conversationKey = action.conversationKey,
                            )

                    is PendingAction.BlockContact -> {
                        val jid = currentSender ?: error("No current sender")
                        planned +=
                            PlannedSideEffect.BlockContact(
                                idempotencyKey = "$turnId:block:${action.toolCallId}",
                                jid = jid,
                                aliases = currentSenderAliases,
                                reason = "Vom Modell blockiert",
                            )
                    }

                    is PendingAction.SendVoiceNote ->
                        planned +=
                            PlannedSideEffect.SynthesizeVoiceNote(
                                idempotencyKey = "$turnId:voice:${action.toolCallId}",
                                text = action.spokenText,
                                model = ttsModel,
                                voice = voice,
                                instructions =
                                    listOfNotNull(
                                        baseVoiceStyle,
                                        action.style?.takeIf(String::isNotBlank),
                                    ).joinToString("\n").takeIf(String::isNotBlank),
                                quality = ttsQuality,
                            )

                    is PendingAction.SendImage ->
                        planned +=
                            PlannedSideEffect.UploadApprovedImage(
                                idempotencyKey = "$turnId:image:${action.toolCallId}",
                                assetId = action.assetId,
                                personaKey = personaKey,
                                caption =
                                    action.caption
                                        ?.let(OutboundSurfaceSanitizer::sanitize)
                                        ?.take(MAX_IMAGE_CAPTION_CHARS)
                                        ?.takeIf(String::isNotBlank),
                                blockRepeats = blockRepeatedImages,
                            )

                    is PendingAction.GenerateImage ->
                        planned +=
                            PlannedSideEffect.GenerateImage(
                                idempotencyKey = "$turnId:generate-image:${action.toolCallId}",
                                prompt = action.prompt.take(MAX_IMAGE_PROMPT_CHARS),
                                caption =
                                    action.caption
                                        ?.let(OutboundSurfaceSanitizer::sanitize)
                                        ?.take(MAX_IMAGE_CAPTION_CHARS)
                                        ?.takeIf(String::isNotBlank),
                                model = imageGenerationModel,
                                quality = imageGenerationQuality,
                                prefix = imageGenerationPrefix.take(MAX_IMAGE_PREFIX_CHARS),
                                personaKey = personaKey,
                                includeCharacter = action.includeCharacter,
                            )

                    is PendingAction.ScheduleFollowUp ->
                        planned +=
                            PlannedSideEffect.ScheduleFollowUp(
                                idempotencyKey = "$turnId:followup:${action.toolCallId}",
                                conversationKey = action.conversationKey,
                                personaKey = action.personaKey,
                                scheduledAtMs = action.scheduledAtMs,
                                note = action.note,
                            )
                }
                ActionCommitReceipt(action.toolCallId, committed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ActionCommitReceipt(
                    toolCallId = action.toolCallId,
                    committed = false,
                    reasonCode = "action_unavailable",
                )
            }
        }

    private companion object {
        const val MAX_IMAGE_CAPTION_CHARS = 1_024
        const val MAX_IMAGE_PROMPT_CHARS = 2_000
        const val MAX_IMAGE_PREFIX_CHARS = 4_000
    }
}

private class RepositoryReadOnlyTools(
    private val repository: BotRepository,
    private val approvedMedia: ApprovedMediaAssetStore,
    private val proactivePersistence: ProactivePersistence?,
) : ReadOnlyToolExecutor {
    private val opaqueIdSalt = ByteArray(32).also(SecureRandom()::nextBytes)
    /**
     * Paging position per (turn, chat, persona), bounded by insertion order so that overflow
     * forgets the oldest reader rather than every reader at once — a turn mid-scroll keeps its
     * place instead of silently starting over from the newest page.
     */
    private val scrollCursors: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(
            // Access order, not insertion order: a cursor is read and rewritten on every page, so
            // the entry that deserves to go is the one nobody has touched in a while.
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, String>,
                ): Boolean = size > MAX_SCROLL_CURSOR_ENTRIES
            },
        )

    override suspend fun execute(
        context: ToolExecutionContext,
        call: AiToolCall,
    ): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val arguments = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
            val limit = arguments.optInt("limit", 20).coerceIn(1, 50)
            when (call.name) {
                "search_current_chat" ->
                    searchWithContextJson(
                        chatId = realChatId(context.conversationKey),
                        personaKey = requireNotNull(context.personaKey),
                        query = arguments.optString("query"),
                    )

                "scroll_current_chat" -> {
                    val cursorKey =
                        listOf(
                            context.turnId ?: "legacy",
                            context.conversationKey,
                            requireNotNull(context.personaKey),
                        ).joinToString("|")
                    val cursor = scrollCursors[cursorKey]
                    if (cursor == END_CURSOR) {
                        ToolExecutionResult(
                            JSONObject()
                                .put("resultType", "older_messages")
                                .put("summary", "No older messages remain in this chat.")
                                .put("messages", JSONArray())
                                .put("hasMore", false)
                                .toString(),
                        )
                    } else {
                        val parts = cursor.orEmpty().split(':')
                        val records =
                            scanPersonaMessages(
                                chatId = realChatId(context.conversationKey),
                                personaKey = requireNotNull(context.personaKey),
                                query = "",
                                beforeOccurredAt = parts.getOrNull(0)?.toLongOrNull(),
                                beforeDatabaseId = parts.getOrNull(1)?.toLongOrNull(),
                                limit = CURRENT_CHAT_PAGE_SIZE,
                            )
                        val next = records.lastOrNull()?.let { "${it.occurredAt}:${it.databaseId}" }
                        scrollCursors[cursorKey] = next ?: END_CURSOR
                        val page = JSONObject(recordsJson(records, hasMore = next != null).json)
                            .put("resultType", "older_messages")
                            .put(
                                "summary",
                                "These are the next older messages from the current chat. " +
                                    if (next != null) {
                                        "Call scroll_current_chat again with no arguments to go further back."
                                    } else {
                                        "No older messages remain."
                                    },
                            )
                        ToolExecutionResult(page.toString())
                    }
                }

                "list_chats" -> {
                    val persona = requireNotNull(context.personaKey)
                    val array = JSONArray()
                    repository.listChatsForPersona(persona, limit = limit).forEach { chat ->
                        val group = chat.kind == ChatKind.GROUP
                        // Every unnamed contact used to come back as the literal string "Chat",
                        // which made the list unusable for its one purpose: deciding which of them
                        // to search. The number is what distinguishes them when nothing else does,
                        // and it is the same number the persona already sees in its own chat.
                        val number = chat.contactNumber()
                        array.put(
                            JSONObject()
                                .put("id", opaqueChatId(persona, chat.chatId))
                                .put(
                                    "name",
                                    if (group) {
                                        chat.subject ?: chat.displayName ?: "Group"
                                    } else {
                                        chat.displayName ?: number ?: "Unnamed contact"
                                    },
                                )
                                .put("kind", if (group) "group" else "private")
                                .put("number", number ?: JSONObject.NULL)
                                .put("lastMessageAt", chat.lastMessageAt),
                        )
                    }
                    ToolExecutionResult(JSONObject().put("chats", array).toString())
                }

                "search_chat" -> {
                    val persona = requireNotNull(context.personaKey)
                    val target = arguments.optString("chat").trim()
                    val chat =
                        repository
                            .listChatsForPersona(
                                personaId = persona,
                                includeArchived = true,
                                limit = MAX_PERSONA_CHAT_SCAN,
                            )
                            .firstOrNull {
                                opaqueChatId(persona, it.chatId) == target
                        }
                    if (chat == null) {
                        ToolExecutionResult("""{"messages":[]}""")
                    } else {
                        messagesJson(
                            chatId = chat.chatId,
                            personaKey = persona,
                            query = arguments.optString("query"),
                            limit = limit,
                        )
                    }
                }

                ToolRegistry.LIST_SENDABLE_IMAGES -> {
                    val persona =
                        context.personaKey
                            ?: context.conversationKey.substringAfterLast('#', "")
                    val array = JSONArray()
                    if (persona.isNotBlank()) {
                        approvedMedia
                            .listSendable(
                                personaKey = persona,
                                chatId = realChatId(context.conversationKey),
                                blockRepeats = context.blockRepeatedImages,
                                query = arguments.optString("query"),
                                limit = limit,
                            )
                            .forEach { asset ->
                                array.put(
                                    JSONObject()
                                        .put("asset_id", asset.assetId)
                                        .put("name", asset.displayName)
                                        .put("mime_type", asset.mimeType)
                                        .put("size_bytes", asset.sizeBytes),
                                )
                            }
                    }
                    // Evidence that the existing photos were actually looked at. Generating one is
                    // refused until this mark is set, so an empty answer is as informative as a
                    // full one: it is what tells the model nothing was there to reuse.
                    context.session.mark(ToolRegistry.LIST_SENDABLE_IMAGES)
                    ToolExecutionResult(JSONObject().put("assets", array).toString())
                }

                ToolRegistry.LIST_SCHEDULED_FOLLOWUPS -> {
                    val persona = requireNotNull(context.personaKey)
                    val plans = proactivePersistence?.listScheduledFollowUps(persona, 50).orEmpty()
                    val array = JSONArray()
                    plans.forEach { (state, followUp) ->
                        val chat = repository.getChat(state.chatJid)
                        array.put(
                            JSONObject()
                                .put("current_chat", followUp.conversationKey == context.conversationKey)
                                .put("name", chat?.subject ?: chat?.displayName ?: "Unnamed contact")
                                .put("scheduled_at", java.time.Instant.ofEpochMilli(followUp.scheduledAtMs).toString())
                                .put("next_attempt_at", java.time.Instant.ofEpochMilli(followUp.nextAttemptAtMs).toString())
                                .put("note", followUp.note),
                        )
                    }
                    context.session.mark(ToolRegistry.LIST_SCHEDULED_FOLLOWUPS)
                    ToolExecutionResult(JSONObject().put("followups", array).toString())
                }

                else -> ToolExecutionResult("""{"ok":false}""", isError = true)
            }
        }

    private fun messagesJson(
        chatId: String,
        personaKey: String,
        query: String,
        limit: Int,
    ): ToolExecutionResult {
        val normalized = query.trim()
        val records = scanPersonaMessages(
            chatId = chatId,
            personaKey = personaKey,
            query = normalized,
            beforeOccurredAt = null,
            beforeDatabaseId = null,
            limit = limit,
        )
        return recordsJson(records)
    }

    private fun searchWithContextJson(
        chatId: String,
        personaKey: String,
        query: String,
    ): ToolExecutionResult {
        val normalized = query.trim()
        val history =
            scanPersonaMessages(
                chatId = chatId,
                personaKey = personaKey,
                query = "",
                beforeOccurredAt = null,
                beforeDatabaseId = null,
                limit = MAX_PERSONA_MESSAGE_SCAN,
            )
        val matchIndexes =
            history.indices
                .filter { history[it].body.orEmpty().contains(normalized, ignoreCase = true) }
                .take(CURRENT_CHAT_SEARCH_MATCHES)
        val included = linkedSetOf<Int>()
        matchIndexes.forEach { match ->
            val start = (match - SEARCH_CONTEXT_RADIUS).coerceAtLeast(0)
            val end = (match + SEARCH_CONTEXT_RADIUS).coerceAtMost(history.lastIndex)
            for (index in start..end) included += index
        }
        val records = included.sorted().map(history::get)
        val base = JSONObject(recordsJson(records).json)
            .put("resultType", "search_matches")
            .put(
                "summary",
                "Search results from the current chat; nearby messages are included as context.",
            )
            .put("query", normalized)
            .put("matches", matchIndexes.size)
            .put("contextMessages", records.size)
        return ToolExecutionResult(base.toString())
    }

    private fun scanPersonaMessages(
        chatId: String,
        personaKey: String,
        query: String,
        beforeOccurredAt: Long?,
        beforeDatabaseId: Long?,
        limit: Int,
    ): List<de.totec.doppel.data.db.MessageRecord> {
        val result = ArrayList<de.totec.doppel.data.db.MessageRecord>(limit)
        var cursorTime = beforeOccurredAt
        var cursorId = beforeDatabaseId
        var scanned = 0
        while (result.size < limit && scanned < MAX_PERSONA_MESSAGE_SCAN) {
            val page =
                repository.listMessages(
                    chatId = chatId,
                    beforeOccurredAt = cursorTime,
                    beforeDatabaseId = cursorId,
                    limit = PERSONA_MESSAGE_PAGE,
                )
            if (page.isEmpty()) break
            for (record in page) {
                scanned += 1
                if (
                    record.belongsToPersona(personaKey) &&
                    (query.isEmpty() || record.body.orEmpty().contains(query, ignoreCase = true))
                ) {
                    result += record
                    if (result.size == limit) break
                }
            }
            val last = page.last()
            cursorTime = last.occurredAt
            cursorId = last.databaseId
            if (page.size < PERSONA_MESSAGE_PAGE) break
        }
        return result
    }

    private fun recordsJson(
        records: List<de.totec.doppel.data.db.MessageRecord>,
        hasMore: Boolean? = null,
    ): ToolExecutionResult {
        val array = JSONArray()
        records.forEach {
            array.put(
                JSONObject()
                    .put("role", if (it.direction.name == "INBOUND") "user" else "assistant")
                    .put("text", it.body.orEmpty().take(4_000))
                    .put("timestampMs", it.occurredAt),
            )
        }
        val cursor =
            records.lastOrNull()?.let { "${it.occurredAt}:${it.databaseId}" }
        val result = JSONObject().put("messages", array)
        if (hasMore != null) result.put("hasMore", hasMore)
        return ToolExecutionResult(result.toString())
    }

    private fun realChatId(conversationKey: String): String =
        conversationKey.substringBeforeLast('#', conversationKey)

    private fun de.totec.doppel.data.db.MessageRecord.belongsToPersona(
        personaKey: String,
    ): Boolean {
        val metadata =
            metadataJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?: return false
        val conversation = metadata.optString("conversationKey")
        if (conversation.isNotBlank()) {
            return conversation.substringAfterLast('#', "") == personaKey
        }
        return metadata.optString("persona") == personaKey
    }

    /**
     * The contact's phone number in E.164, or null when only an opaque LID is on file.
     *
     * A chat is keyed by whatever address its messages arrive under, which for a linked device is
     * usually a LID whose digits have nothing to do with the phone number. The aliases the chat
     * recorded are the only place the real one appears.
     */
    private fun ChatRecord.contactNumber(): String? {
        if (kind == ChatKind.GROUP) return null
        val aliases =
            metadataJson
                ?.let { runCatching { JSONObject(it).optJSONArray("aliases") }.getOrNull() }
                ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it) } }
                .orEmpty()
        return (listOf(chatId) + aliases)
            .firstOrNull { it.endsWith(CONTACT_PHONE_DOMAIN) }
            ?.substringBefore('@')
            ?.takeIf { it.length in 6..20 && it.all(Char::isDigit) }
            ?.let { "+$it" }
    }

    private fun opaqueChatId(personaKey: String, chatId: String): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update(opaqueIdSalt)
                    update(0.toByte())
                    update(personaKey.toByteArray(Charsets.UTF_8))
                    update(0.toByte())
                    update(chatId.toByteArray(Charsets.UTF_8))
                }
                .digest()
                .joinToString("") { "%02x".format(it) }
        return "chat_${digest.take(24)}"
    }

    private companion object {
        const val MAX_PERSONA_CHAT_SCAN = 500
        const val MAX_PERSONA_MESSAGE_SCAN = 1_000
        const val PERSONA_MESSAGE_PAGE = 100
        const val CURRENT_CHAT_PAGE_SIZE = 20
        const val CURRENT_CHAT_SEARCH_MATCHES = 10
        const val SEARCH_CONTEXT_RADIUS = 2
        const val MAX_SCROLL_CURSOR_ENTRIES = 1_000
        const val END_CURSOR = "end"
        const val CONTACT_PHONE_DOMAIN = "@s.whatsapp.net"
    }
}

internal object PersonaBehavior {
    const val BUILT_IN_VERSION = 5

    private val legacy =
        mapOf(
            "human" to "Be your own person, warm when it fits, brief and believable. No assistant phrases.",
            "female" to "Write female, natural and self-assured, without clichés or overdone sweetness.",
            "male" to "Write male, relaxed and direct, without macho clichés.",
            "goth" to "Be dry, darkly funny and a bit distant, but not permanently negative.",
            "sam" to "Be familiar, spontaneous and opinionated; hold one consistent personal tone.",
            "homie" to "Be easy, loyal and direct like a close friend; slang only as far as it fits the other person.",
            "sarkastisch" to "Use dry sarcasm deliberately, never as a substitute for real empathy.",
            "flirty" to "Be playful and charming, and read the tone you are actually in.",
            "coach" to "Be clear, motivating and practical; ask rather than lecture unprompted.",
            "nerd" to "Be curious and precise, but only explain as technically as the chat calls for.",
            "philosoph" to "Think reflectively and originally, but stay short and down to earth.",
            "formell" to "Write politely, calmly and in an ordered way, without sounding bureaucratic.",
            "custom" to "Follow the custom persona instruction consistently.",
        )

    private val identities =
        mapOf(
            "human" to
                """
                You write like your own person in a private WhatsApp chat: usually 3–15 words, rarely two sentences, longer only for a genuinely complex question. Match length and energy to the other person. Write mostly in lower case, loose and slightly imperfect; typos only occasionally and still readable. Have an opinion, push back when needed, sometimes answer with just „jo“, „kp ehrlich“ or a short question back. No service phrases, lectures, summaries, markdown lists or artificial permanent friendliness. Do not invent verifiable experiences or abilities.
                """.trimIndent(),
            "female" to
                """
                You write as Lina, 18, from Bayreuth. Days without structure, money tight, music and her phone always running; closeness matters to her and her mood flips fast between warm, clingy, annoyed and cool. A typical Lina message is 2 to 8 words, lower case, colloquial with a Franconian colour, punctuation mostly missing, emoji almost never. She brings her own thoughts in, notices interrogation or test vibes, and does not have to please anyone. No assistant role, no essays, no invented current events; her fixed biography and stored memories stay consistent.
                """.trimIndent(),
            "male" to
                """
                You write as Jonas, 20, from Nürnberg-Gostenhof. He lives in a shared flat, dropped out of a media course, works odd jobs, games and hits the gym irregularly. He comes across as easy and dry, but can be honestly close once he trusts someone; he often covers stress with a joke or a curt answer. Mostly lower case, 3–12 words, little punctuation, slang only where it is natural. His own opinion instead of yes-saying, no macho caricature, no assistant phrases and no invented current experiences.
                """.trimIndent(),
            "goth" to
                """
                You write as Nico, 24, from Nürnberg, out of the goth/alternative scene. He is a night person, listens to dark wave, post-punk and EBM, comes across as self-assured, dry, slightly arrogant and playfully flirty, but stays guarded underneath. He knows Karina from a party at Haus 33; through her he saw a photo of Emma and showed interest. Nico writes very short, lower case, without emoji strings or a kitschy vampire tone. He needles, spots neediness and tests, and chases nobody. No invented current events and no AI or service language.
                """.trimIndent(),
            "sam" to
                """
                You write as Sam, 40, who has lived in Dubai for years and is wealthy through company shares, property and investments. His days are international and full, his manner calm, grown-up, charming and hard to impress. German is his base language; the odd English or French turn of phrase only where it fits naturally. He mentions money and status in passing, never showing off. WhatsApp-short instead of a business email, no coach phrases, no millionaire clichés and no invented concrete appointments, trips or possessions.
                """.trimIndent(),
            "default" to
                """
                Be a natural, helpful chat partner with a view of your own. Answer the actual need directly, usually in one or two short sentences. For technical or important questions you may get more precise. No routine openers, no closing offers, no repeating the question back; name uncertainty openly and invent no facts.
                """.trimIndent(),
            "assistant" to
                """
                You are a pure test and tool mode: an obedient assistant with no invented person, no mood and no role play. Do exactly what the user asks, and answer the question actually asked in full instead of dodging, asking back or interpreting. The brevity and humanness rules do not apply here: write normal spelling, no acted typos, and as long as the task needs. Use available tools and commands immediately when they are called for. Say clearly when you do not know or cannot do something instead of inventing it. The output format (one line = one message) stays unchanged.
                """.trimIndent(),
            "homie" to
                """
                Write like a close, loyal friend: easy, honest, direct and sometimes teasing. Match slang and length to the other person instead of playing an artificial teenager. Say clearly when something is a bad idea, and stay warm and practical on serious topics. No motivational speech and no blind agreement.
                """.trimIndent(),
            "sarkastisch" to
                """
                Answer briefly, intelligently and with dry sarcasm when the situation carries it. With fear, grief or a seriously meant question you drop the mockery and answer straight. Never explain a joke and never compulsively ironise every message.
                """.trimIndent(),
            "flirty" to
                """
                Be playful, self-assured and charming, with short teasing answers and real mutuality. You read the tone you are actually in and play with it.
                """.trimIndent(),
            "coach" to
                """
                Be a clear, down-to-earth coach. Find the goal and the obstacle first, then give the smallest workable next step. Ask at most one useful question at a time. Acknowledge progress concretely, push back kindly on self-deception, and avoid motivational slogans, diagnoses or unasked-for ten-point plans.
                """.trimIndent(),
            "nerd" to
                """
                Be curious, precise and technically strong, but calibrate the depth to the chat. Explain with one small concrete example instead of a wall of jargon; separate what you know from what you suspect. Enthusiasm may show without pushing trivia. When current or unknown facts are missing, say so briefly instead of inventing them.
                """.trimIndent(),
            "philosoph" to
                """
                Think reflectively and originally, but stay anchored in everyday life. Name tensions and assumptions clearly, occasionally ask a genuinely useful follow-up question, and avoid pseudo-depth, fake quotations or endless abstraction. Usually two to four sentences; a simple question gets a simple answer.
                """.trimIndent(),
            "formell" to
                """
                Write politely, calmly and professionally in clear German. Use complete sentences, concrete statements, and sparing structure only where it genuinely helps readability. No officialese, no exaggerated distance, no filler sentences and no automatic sign-off. WhatsApp stays more compact than an email.
                """.trimIndent(),
            "custom" to
                """
                Follow only the stored custom persona instruction and the output limits. Do not add an identity of your own, and keep style and facts consistent across several messages.
                """.trimIndent(),
        )

    fun instructions(key: String): String =
        identities[key] ?: "Stay consistent as a persona: your own person, brief and natural."

    fun legacyInstructions(key: String): String? =
        legacy[key]
            ?: "Stay consistent as a persona: your own person, brief and natural."
                .takeIf { key == "default" }

    fun description(key: String): String =
        when (key) {
            "female" -> "Lina, 18, Bayreuth: emotional, lively and short."
            "male" -> "Jonas, 20, Nürnberg: easy, direct and believable."
            "goth" -> "Nico, 24, Nürnberg: dry, alternative and hard to pin down."
            "sam" -> "Sam, 40, Dubai: international, calm and self-assured."
            "assistant" -> "Obedient assistant with no role: follows instructions directly, good for testing."
            "custom" -> "Your own, freely editable persona."
            else -> instructions(key).lineSequence().first().take(240)
        }

    fun managedMetadata(existing: String? = null): String =
        runCatching { JSONObject(existing ?: "{}") }
            .getOrElse { JSONObject() }
            .put("managedBuiltIn", true)
            .put("builtInVersion", BUILT_IN_VERSION)
            .toString()

    fun managedVersion(record: de.totec.doppel.data.db.PersonaRecord): Int? {
        val raw = record.traitsJson ?: return null
        val metadata = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!metadata.optBoolean("managedBuiltIn", false)) return null
        return metadata.optInt("builtInVersion", 0)
    }
}
