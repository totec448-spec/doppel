package de.totec.doppel.engine

import de.totec.doppel.domain.IncomingEvent
import de.totec.doppel.domain.MediaKind
import java.time.ZoneId

/**
 * German on purpose, and one of the very few strings in this codebase that is: everything else here
 * configures the engine, while this one is delivered verbatim to a contact. It stays a single plain
 * sentence so it reads as a notice rather than as the persona's opening line.
 */
const val DEFAULT_AI_DISCLOSURE_TEXT =
    "Hinweis: Dieser Account nutzt einen KI-Assistenten, der Nachrichten automatisch beantwortet."

data class EngineSettingsSnapshot(
    val enabled: Boolean = true,
    val chatPaused: Boolean = false,
    val allowAll: Boolean = false,
    val batchWindowMs: Long = 2_000,
    val replyPreset: ReplyPreset = ReplyPreset.HUMAN,
    /** Typing speed in words per minute — see [HumanTimingPolicy.typingDelayMs]. */
    val typingWordsPerMinute: Int = HumanTimingPolicy.DEFAULT_TYPING_WPM,
    /** Reading speed for a short message, before the length ramp in [HumanTimingPolicy.readingFloorMs]. */
    val readingWordsPerSecond: Int = HumanTimingPolicy.DEFAULT_READING_WORDS_PER_SECOND,
    val sleepStartMinutes: Int = 30,
    val sleepEndMinutes: Int = 510,
    val timezone: ZoneId = ZoneId.of("Europe/Berlin"),
    /**
     * Whether the WhatsApp link may drop between online sessions — see [LinkPowerPolicy].
     * Affects the transport only; the behavioural timing model is identical in both modes.
     *
     * Read it through [effectivePowerMode] rather than directly: this is what the operator chose,
     * which is not the same as what is in force.
     */
    val powerMode: PowerMode = PowerMode.DEFAULT,
    /** Low mode only: minutes the link keeps listening after an answer. */
    val lowListenMinutes: Int = LinkPowerPolicy.DEFAULT_LISTEN_MINUTES,
    /** Messages that survive a memory write verbatim — see [ConversationMemoryPolicy]. */
    val historyLimit: Int = ConversationMemoryPolicy.DEFAULT_RETAINED_HISTORY_MESSAGES,
    /** New messages between two memory writes — see [ConversationMemoryPolicy]. */
    val memoryIntervalMessages: Int = ConversationMemoryPolicy.DEFAULT_MEMORY_INTERVAL_MESSAGES,
    val personality: String = "human",
    val reactions: Boolean = true,
    val quoteReplies: Boolean = true,
    /** Sends a one-off "this is an AI" notice before the first automated reply in a chat. */
    val aiDisclosureEnabled: Boolean = true,
    val aiDisclosureText: String = DEFAULT_AI_DISCLOSURE_TEXT,
    val markRead: Boolean = true,
    val selfEditEnabled: Boolean = false,
    val selfEditChance: Double = 0.03,
    /** Divides the typing time of a self-corrected bubble — see [HumanTimingPolicy.selfEditDelayMs]. */
    val selfEditDelayDivisor: Double = HumanTimingPolicy.DEFAULT_SELF_EDIT_DIVISOR,
    /** Upper bound of the pause before "schreibt…" drops after an interrupt. */
    val typingStopDelayMs: Long = HumanTimingPolicy.DEFAULT_TYPING_STOP_DELAY_MS,
    val proactiveLevel: Int = 0,
    val proactiveMode: ProactiveMode = ProactiveMode.FIXED,
    val groupTrigger: String = "",
    val maxSendsPerHour: Int = 25,
    val maxSendsPerDay: Int = 120,
    val autoblockEnabled: Boolean = true,
    val autoblockPerMinute: Int = 10,
    val autoblockPerFiveMinutes: Int = 30,
    val autoblockPerTenMinutes: Int = 50,
)

enum class ReplyPreset {
    HUMAN,
    INSTANT,
}

/**
 * The power mode actually in force, which [ReplyPreset.INSTANT] decides for itself.
 *
 * Instant means "she is at her phone, always" — it is the mode people switch to when they want an
 * answer *now*, and a bot that is dozing until half past two cannot give them one. So instant
 * outranks a stored [PowerMode.LOW] for as long as it is selected.
 *
 * An override rather than a write: the stored choice is what the operator picked, and switching a
 * conversation to instant for an afternoon must not silently spend it. Going back to human puts low
 * mode back with nothing to restore.
 */
val EngineSettingsSnapshot.effectivePowerMode: PowerMode
    get() = if (replyPreset == ReplyPreset.INSTANT) PowerMode.DEFAULT else powerMode

enum class ProactiveMode {
    FIXED,
    HOT_COLD,
}

data class StoredTurnMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestampMs: Long,
    val senderName: String? = null,
    /** True only for trusted context deliberately inserted by the local operator. */
    val operatorInjection: Boolean = false,
    /**
     * The words this message was a reply to. WhatsApp shows that quote above the bubble, so
     * without it the log reads as a non sequitur — "is that your serious?" with nothing to point
     * at — and the model answers the wrong thing or asks what is meant.
     */
    val quotedText: String? = null,
)

/**
 * Shared prompt-history and durable-memory cadence, built from two configured numbers that simply
 * add up:
 *
 * ```
 * retained overlap (history_limit)  +  refresh interval (memory_interval)  =  complete window
 *              10                   +                150                  =        160
 * ```
 *
 * A successful chat-memory write leaves the *retained overlap* verbatim in the prompt. That
 * anchored block then grows only at its newest end — which is what keeps the provider's prefix
 * cache matching — until *interval* new messages have arrived, at which point the next memory
 * write folds them in and the window snaps back to the overlap. Only a successful memory write
 * releases the anchor: if consolidation fails the old anchor stands and the prompt keeps growing
 * rather than silently dropping unsummarized messages.
 *
 * The first write of a fresh chat waits for the whole window, not just the interval — otherwise a
 * brand-new conversation would pay for a consolidation before it has an overlap to keep.
 *
 * Every third chat-memory write rebuilds persona-wide memory, unless that ratio is configured
 * otherwise.
 *
 * Turning chat memory off does not turn the cadence off. The window still snaps back at the
 * interval — the consolidation pointer is simply moved without a model call, and everything behind
 * it is forgotten instead of summarised. A window that stopped advancing would grow without limit
 * and re-bill the whole history on every turn, which is the opposite of what switching memory off
 * is for.
 */
object ConversationMemoryPolicy {
    const val DEFAULT_RETAINED_HISTORY_MESSAGES = 10
    const val MAX_RETAINED_HISTORY_MESSAGES = 100

    const val DEFAULT_MEMORY_INTERVAL_MESSAGES = 150
    const val MIN_MEMORY_INTERVAL_MESSAGES = 10
    const val MAX_MEMORY_INTERVAL_MESSAGES = 500

    /** The default `10 + 150`; the live value comes from [completeWindow]. */
    const val DEFAULT_COMPLETE_CHAT_WINDOW_MESSAGES =
        DEFAULT_RETAINED_HISTORY_MESSAGES + DEFAULT_MEMORY_INTERVAL_MESSAGES

    /** Largest window any configuration can produce; the summary fold must cover it. */
    const val MAX_COMPLETE_CHAT_WINDOW_MESSAGES =
        MAX_RETAINED_HISTORY_MESSAGES + MAX_MEMORY_INTERVAL_MESSAGES

    const val DEFAULT_PERSONA_MEMORY_EVERY_CHAT_REFRESHES = 3
    const val MIN_PERSONA_MEMORY_EVERY_CHAT_REFRESHES = 1
    const val MAX_PERSONA_MEMORY_EVERY_CHAT_REFRESHES = 10

    /**
     * Chat-memory writes between two persona syntheses, with an unset/out-of-range value repaired.
     *
     * The synthesis is a second full model call, so this is the main lever on what memory costs:
     * 1 rebuilds the persona view after every chat write, 10 after every tenth.
     */
    fun personaSynthesisInterval(configuredInterval: Int): Long =
        if (configuredInterval <= 0) {
            DEFAULT_PERSONA_MEMORY_EVERY_CHAT_REFRESHES.toLong()
        } else {
            configuredInterval
                .coerceIn(
                    MIN_PERSONA_MEMORY_EVERY_CHAT_REFRESHES,
                    MAX_PERSONA_MEMORY_EVERY_CHAT_REFRESHES,
                ).toLong()
        }

    /** New messages between two memory writes, with an unset/out-of-range value repaired. */
    fun refreshInterval(configuredInterval: Int): Int =
        if (configuredInterval <= 0) {
            DEFAULT_MEMORY_INTERVAL_MESSAGES
        } else {
            configuredInterval.coerceIn(MIN_MEMORY_INTERVAL_MESSAGES, MAX_MEMORY_INTERVAL_MESSAGES)
        }

    /** Retained overlap, with `0` kept as the explicit "keep everything stored" choice. */
    fun retainedMessages(configuredRetained: Int): Int =
        configuredRetained.coerceIn(0, MAX_RETAINED_HISTORY_MESSAGES)

    /**
     * The window both numbers add up to: how far the rendered history grows before the next memory
     * write pulls it back. With `historyLimit == 0` nothing is trimmed at all, so the window is
     * only ever the interval plus whatever the chat already had.
     */
    fun completeWindow(
        configuredRetained: Int,
        configuredInterval: Int,
    ): Int = retainedMessages(configuredRetained) + refreshInterval(configuredInterval)

    /**
     * New messages needed after a reset before the next memory write.
     *
     * With the additive model this is exactly the interval: the overlap is already in the window,
     * so `overlap + interval` is back at the full window.
     */
    fun newMessagesAfterReset(configuredInterval: Int): Int = refreshInterval(configuredInterval)
}

data class TurnInput(
    val turnId: String,
    val chatJid: String,
    val conversationKey: String,
    val isGroup: Boolean,
    val senderName: String?,
    val newestEvents: List<IncomingEvent>,
    val history: List<StoredTurnMessage>,
    val chatMemory: String?,
    val personaMemory: String?,
    val settings: EngineSettingsSnapshot,
    val mood: Mood,
    val proactive: Boolean,
    val isAdmin: Boolean = false,
    val proactiveTrailingDirective: String? = null,
)

data class TurnOutput(
    val text: String = "",
    val bubbles: List<String> = emptyList(),
    val reaction: String? = null,
    val reactionTargetMessageId: String? = null,
    val quoteMessageId: String? = null,
    /** Reply target aligned by index with [bubbles]; supports multiple WhatsApp Replies in one turn. */
    val bubbleQuoteMessageIds: List<String?> = emptyList(),
    val noReply: Boolean = false,
    val actions: List<PlannedSideEffect> = emptyList(),
)

sealed interface PlannedSideEffect {
    val idempotencyKey: String

    data class SendMedia(
        override val idempotencyKey: String,
        val uploadId: String,
        val mimeType: String,
        val voiceNote: Boolean,
        val durationSeconds: Int? = null,
        /**
         * Base64 of the 64-byte, 0..100 amplitude envelope for a voice note.
         *
         * Only this side ever holds the PCM, so if it is not measured here the bridge has nothing
         * to draw with and every voice note ships the same synthetic pattern — the most visible
         * bot fingerprint the bubble can carry.
         */
        val waveformBase64: String? = null,
        val caption: String? = null,
        val approvedAssetId: String? = null,
        val approvedPersonaKey: String? = null,
        /**
         * What she should remember having sent: the spoken text of a voice note,
         * the name and caption of an image. The upload itself is an opaque handle,
         * so without this the history keeps no trace of her own media and she
         * answers her own photo as if it had arrived from the contact.
         */
        val historyText: String? = null,
    ) : PlannedSideEffect {
        init {
            require((approvedAssetId == null) == (approvedPersonaKey == null)) {
                "Approved asset id and persona must be supplied together"
            }
        }
    }

    /** Verified but still inert; materialized only after the final outbound reservation. */
    data class SynthesizeVoiceNote(
        override val idempotencyKey: String,
        val text: String,
        val model: String,
        val voice: String,
        val instructions: String?,
        val quality: Int,
    ) : PlannedSideEffect

    /**
     * Last-resort visible reply when verified media could not be materialized —
     * voice synthesis failing, or an approved image that can no longer be sent.
     * This prevents a paid/reactive turn from ending silently.
     */
    data class VoiceTextFallback(
        override val idempotencyKey: String,
        val text: String,
        val reasonCode: String,
    ) : PlannedSideEffect

    /** Durable local promise; execution later still uses the normal globally serialized turn. */
    data class ScheduleFollowUp(
        override val idempotencyKey: String,
        val conversationKey: String,
        val personaKey: String,
        val scheduledAtMs: Long,
        val note: String,
    ) : PlannedSideEffect

    /** Verified app-private asset reference; no upload occurs before final reservation. */
    data class UploadApprovedImage(
        override val idempotencyKey: String,
        val assetId: String,
        val personaKey: String,
        val caption: String?,
        val blockRepeats: Boolean,
    ) : PlannedSideEffect

    /** Verified generation request; paid work starts only after the final outbound reservation. */
    data class GenerateImage(
        override val idempotencyKey: String,
        val prompt: String,
        val caption: String?,
        val model: String,
        val quality: String,
        val prefix: String,
        val personaKey: String,
        val includeCharacter: Boolean,
    ) : PlannedSideEffect

    data class BlockContact(
        override val idempotencyKey: String,
        val jid: String,
        val aliases: List<String> = emptyList(),
        val reason: String,
    ) : PlannedSideEffect

    data class RequestMemoryRefresh(
        override val idempotencyKey: String,
        val conversationKey: String,
    ) : PlannedSideEffect
}

sealed interface CommandHandling {
    data object NotACommand : CommandHandling
    data object FallThrough : CommandHandling
    data class Handled(val replies: List<String>) : CommandHandling
}

data class AccessDecision(
    val allowed: Boolean,
    val isAdmin: Boolean,
    val reason: String? = null,
)

data class InboundRateDecision(
    val limited: Boolean,
    /** True only for the dedicated 1/5/10-minute flood thresholds. */
    val autoblockThresholdExceeded: Boolean,
)

enum class TurnActivityLevel {
    /**
     * Internal step of a turn. Recorded for diagnosis, hidden from the feed unless
     * the details view is opened: the log should read as what the bot *did*, not as
     * every function it walked through on the way.
     */
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** Privacy-safe lifecycle event; summaries must never contain chat text. */
data class TurnActivity(
    val chatJid: String,
    val turnId: String,
    val stage: String,
    val summary: String,
    val level: TurnActivityLevel = TurnActivityLevel.INFO,
    val elapsedMs: Long? = null,
    val timestampMs: Long,
)

interface EngineStore {
    /** Claims only the transport event journal; false means duplicate delivery. */
    suspend fun claimInbound(event: IncomingEvent): Boolean

    /** Persists an access-approved conversational event after command/safety gates. */
    suspend fun persistConversation(event: IncomingEvent): Boolean

    /** Applies an access-approved edit/delete to its existing target message. */
    suspend fun applyInboundMutation(event: IncomingEvent): Boolean

    /** Completes a claimed event that intentionally does not enter AI history. */
    suspend fun completeInbound(
        event: IncomingEvent,
        disposition: String,
    )

    suspend fun accessDecision(event: IncomingEvent, allowAll: Boolean): AccessDecision

    suspend fun inboundRateDecision(
        event: IncomingEvent,
        perMinute: Int,
        perFiveMinutes: Int,
        perTenMinutes: Int,
    ): InboundRateDecision

    suspend fun blockSender(event: IncomingEvent, reason: String)

    suspend fun loadHistory(conversationKey: String, limit: Int): List<StoredTurnMessage>

    suspend fun loadChatMemory(conversationKey: String): String?

    suspend fun loadPersonaMemory(personaKey: String): String?

    /** True while this chat has never been told an AI answers here. */
    suspend fun needsAiDisclosure(chatJid: String): Boolean = false

    /**
     * Stamps the disclosure as delivered. Called only after the notice is actually on the wire, so
     * a failed send leaves the chat undisclosed and the next turn tries again — disclosing twice is
     * a far better failure than never disclosing at all.
     */
    suspend fun markAiDisclosureSent(chatJid: String, timestampMs: Long) = Unit

    suspend fun recordAssistant(
        conversationKey: String,
        chatJid: String,
        text: String,
        transportMessageIds: List<String>,
        timestampMs: Long,
    )

    /** Reconciles local history after WhatsApp confirms an edit of an already sent message. */
    suspend fun reviseAssistant(
        transportMessageId: String,
        text: String,
    ): Boolean = false

    suspend fun markDeferred(conversationKey: String, timestampMs: Long)

    suspend fun clearDeferred(conversationKey: String)

    suspend fun requestMemoryRefresh(conversationKey: String, timestampMs: Long)

    /**
     * Cheap read-only gate used before any model/media call. The final
     * reservation is still authoritative and must run after generation.
     */
    suspend fun preflightOutbound(intent: OutboundIntent): OutboundDecision

    suspend fun reserveOutbound(intent: OutboundIntent): OutboundDecision

    suspend fun completeOutbound(
        reservationId: String,
        transportMessageId: String?,
        success: Boolean,
        timestampMs: Long,
    )

    suspend fun lastOnlineAt(): Long?

    suspend fun setLastOnlineAt(timestampMs: Long)

    /** Best-effort local stage telemetry without message bodies or identities. */
    suspend fun recordTurnActivity(activity: TurnActivity) = Unit

    /**
     * The batched form of [recordTurnActivity]: one transaction and one feed notification for a
     * whole turn's worth of rows.
     *
     * A single reply used to produce around twenty separate inserts, each with its own dispatcher
     * hop and its own UI refresh, and most of them [TurnActivityLevel.DEBUG] steps nobody reads
     * while the bot is working. Rows carry their own timestamps, so writing them together does not
     * reorder the feed. The default delegates to the single-row call, which keeps stores that do
     * not care about batching (tests, alternative implementations) working unchanged.
     */
    suspend fun recordTurnActivities(activities: List<TurnActivity>) {
        activities.forEach { recordTurnActivity(it) }
    }

    /**
     * Best-effort durable observability for a failed visible turn. Implementors
     * must not rethrow: a diagnostics write can never terminate the worker.
     */
    suspend fun recordTurnFailure(
        chatJid: String,
        turnId: String,
        proactive: Boolean,
        error: Throwable,
        timestampMs: Long,
    )

    /** Re-enqueue persisted inbound work that has no committed assistant turn. */
    suspend fun recoverPending(): List<List<IncomingEvent>>
}

interface EngineSettingsProvider {
    /**
     * Resolves the settings view for [chatJid], or the global view when it is blank.
     *
     * A blank id is a legitimate call, not a bug: chat-independent work — the self-online
     * session, for instance — has no conversation to scope to and still needs the settings.
     * Implementors must therefore treat it as "no per-chat override applies" and must not
     * push it into stores that reject an empty identifier.
     */
    suspend fun resolve(chatJid: String): EngineSettingsSnapshot
}

interface AdminCommandGateway {
    suspend fun handle(event: IncomingEvent, isAdmin: Boolean): CommandHandling
}

interface AiTurnRunner {
    suspend fun run(input: TurnInput): TurnOutput

    /**
     * Continues the same model turn after WhatsApp confirmed visible assistant output. The
     * supplied messages must already be durable history; implementations keep the original
     * settings, system prompt, tools, inbound message and live tail unchanged.
     */
    suspend fun followUpAfterVisibleAssistant(
        input: TurnInput,
        confirmedAssistantMessages: List<StoredTurnMessage>,
        followUpIndex: Int,
    ): TurnOutput = TurnOutput(noReply = true)

    /**
     * Materializes expensive media actions only after the engine acquired the
     * authoritative outbound reservation.
     */
    suspend fun materializeActions(
        input: TurnInput,
        actions: List<PlannedSideEffect>,
    ): List<PlannedSideEffect> = actions

    /** Event-driven low-priority maintenance after a durably recorded send. */
    suspend fun afterSuccessfulSend(
        input: TurnInput,
        actions: List<PlannedSideEffect>,
    ) = Unit
}

interface WhatsAppActions {
    suspend fun markRead(chatJid: String, messageIds: List<String>)

    suspend fun markPlayed(chatJid: String, messageId: String)

    suspend fun setPresence(chatJid: String, presence: String)

    suspend fun sendText(
        chatJid: String,
        text: String,
        quoteMessageId: String?,
        idempotencyKey: String,
        /**
         * Text of the quoted message. WhatsApp draws the reply bubble from the
         * copy the sender attaches, so this keeps a quote readable even when the
         * transport no longer retains the original.
         */
        quotePreview: String? = null,
    ): String

    suspend fun sendReaction(
        chatJid: String,
        targetMessageId: String,
        emoji: String,
        idempotencyKey: String,
    ): String?

    suspend fun sendMedia(
        chatJid: String,
        action: PlannedSideEffect.SendMedia,
    ): String

    suspend fun editText(
        chatJid: String,
        messageId: String,
        text: String,
        idempotencyKey: String,
    )

    suspend fun blockContact(
        jid: String,
        reason: String,
        idempotencyKey: String,
        aliases: List<String> = emptyList(),
    )
}

/**
 * What one rotation attempt did.
 *
 * A picture that silently stays put looks exactly like a broken feature, which
 * is why "nothing happened" is not one answer but three: the reason is what
 * the activity log needs in order to tell a persona without pictures apart from
 * a bridge that was busy.
 */
enum class ProfilePictureOutcome {
    /** Not due, or the persona is already the one on the account. */
    UNCHANGED,

    /** The account is now wearing this persona's picture. */
    CHANGED,

    /** The persona ships no pictures at all, so the previous face stays up. */
    NO_PICTURES,

    /** The picture could not be applied; the switch stays pending and is retried. */
    PENDING,
}

/**
 * Swaps the account's own profile picture when enough weeks have passed. The
 * engine only says "she is online now"; how rare a change is, and which picture
 * comes next, is entirely the implementation's business.
 */
interface ProfilePictureRotation {
    /**
     * Rotates the picture when the persona is due for a new one.
     *
     * Also the place where a switch that could not be delivered earlier is
     * caught up: the account wearing the previous persona's face is worse than
     * a picture change arriving a session late.
     */
    suspend fun rotateIfDue(
        personaKey: String,
        nowMs: Long,
    ): ProfilePictureOutcome

    /**
     * Puts the new persona's own face up right after the persona was switched.
     *
     * A persona switch is the one moment where waiting weeks would be wrong: the
     * account is now a different person, and keeping the previous one's picture
     * is the contradiction, not the change.
     *
     * Called on every start as well, not only when a setting changes — that is
     * what records which persona the account is wearing in the first place, and
     * what catches a switch made while the runtime was down.
     */
    suspend fun applyPersonaSwitch(
        personaKey: String,
        nowMs: Long,
    ): ProfilePictureOutcome
}

interface EngineClock {
    fun wallTimeMillis(): Long

    suspend fun delay(millis: Long)
}

object SystemEngineClock : EngineClock {
    override fun wallTimeMillis(): Long = System.currentTimeMillis()

    override suspend fun delay(millis: Long) {
        kotlinx.coroutines.delay(millis)
    }
}

data class OutboundIntent(
    val reservationId: String,
    val chatJid: String,
    val proactive: Boolean,
    val admin: Boolean = false,
    /** False for a turn-level permit; actual WhatsApp sends reserve children. */
    val countsTowardBudget: Boolean = true,
    val textHash: String,
    /**
     * Length of the text this intent will send, or 0 when the payload is not text.
     *
     * Only the duplicate guard reads it. That guard exists to catch a resend of the same generated
     * message, but a hash cannot tell a resend apart from a human saying "ok" twice in ten minutes
     * — and the second "ok" was being dropped without a word. The length is what separates the two.
     */
    val payloadChars: Int = 0,
    val timestampMs: Long,
)

sealed interface OutboundDecision {
    data class Allowed(val reservationId: String) : OutboundDecision
    data class Deferred(val untilMs: Long, val reason: String) : OutboundDecision
    data class Blocked(val reason: String, val hard: Boolean) : OutboundDecision
}

fun IncomingEvent.conversationKey(personaKey: String): String = "$chatJid#$personaKey"

fun IncomingEvent.isVoiceMessage(): Boolean = media?.kind == MediaKind.AUDIO
