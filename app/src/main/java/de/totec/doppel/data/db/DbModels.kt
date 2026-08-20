package de.totec.doppel.data.db

const val CHAT_INJECTION_MESSAGE_TYPE = "injection"
const val PERSONA_SWITCH_MESSAGE_TYPE = "persona_switch"
const val SCHEDULED_FOLLOW_UP_MESSAGE_TYPE = "scheduled_follow_up"

/**
 * Persisted values are deliberately limited to bot state, message history and operational
 * metadata. Credentials, API keys, WhatsApp authentication material and encryption keys must
 * never be represented by one of these models; those belong in Android Keystore-backed storage.
 *
 * JSON fields are opaque, versioned payloads owned by the runtime layer. Keeping JSON parsing out
 * of the database layer avoids object allocation during simple list/status queries.
 */

enum class ChatKind(val databaseValue: String) {
    DIRECT("direct"),
    GROUP("group"),
    BROADCAST("broadcast"),
    STATUS("status"),
    UNKNOWN("unknown");

    companion object {
        fun fromDatabase(value: String): ChatKind =
            entries.firstOrNull { it.databaseValue == value } ?: UNKNOWN
    }
}

enum class MessageDirection(val databaseValue: String) {
    INBOUND("inbound"),
    OUTBOUND("outbound"),
    SYSTEM("system");

    companion object {
        fun fromDatabase(value: String): MessageDirection =
            entries.firstOrNull { it.databaseValue == value } ?: SYSTEM
    }
}

enum class MessageDeliveryState(val databaseValue: String) {
    RECEIVED("received"),
    QUEUED("queued"),
    SENT("sent"),
    DELIVERED("delivered"),
    READ("read"),
    FAILED("failed"),
    UNKNOWN("unknown");

    companion object {
        fun fromDatabase(value: String): MessageDeliveryState =
            entries.firstOrNull { it.databaseValue == value } ?: UNKNOWN
    }
}

enum class AccessListKind(val databaseValue: String) {
    ALLOW("allow"),
    GROUP_ALLOW("group_allow"),
    BLOCK("block"),
    ADMIN("admin");

    companion object {
        fun fromDatabase(value: String): AccessListKind =
            entries.firstOrNull { it.databaseValue == value } ?: ALLOW
    }
}

enum class AccessSubjectType(val databaseValue: String) {
    JID("jid"),
    PHONE("phone"),
    CHAT("chat"),
    GROUP("group");

    companion object {
        fun fromDatabase(value: String): AccessSubjectType =
            entries.firstOrNull { it.databaseValue == value } ?: JID
    }
}

enum class StoredSettingValueType(val databaseValue: String) {
    STRING("string"),
    BOOLEAN("boolean"),
    INTEGER("integer"),
    DECIMAL("decimal"),
    JSON("json"),
    SECRET_REFERENCE("secret_reference");

    companion object {
        fun fromDatabase(value: String): StoredSettingValueType =
            entries.firstOrNull { it.databaseValue == value } ?: STRING
    }
}

enum class OutboundDecision(val databaseValue: String) {
    ALLOW("allow"),
    DENY("deny"),
    REVIEW("review");

    companion object {
        fun fromDatabase(value: String): OutboundDecision =
            entries.firstOrNull { it.databaseValue == value } ?: DENY
    }
}

enum class OutboundStatus(val databaseValue: String) {
    RESERVED("reserved"),
    SENT("sent"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromDatabase(value: String): OutboundStatus =
            entries.firstOrNull { it.databaseValue == value } ?: FAILED
    }
}

enum class ActivityLevel(val databaseValue: String) {
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error");

    companion object {
        fun fromDatabase(value: String): ActivityLevel =
            entries.firstOrNull { it.databaseValue == value } ?: INFO
    }
}

enum class BridgeOutboxState(val databaseValue: String) {
    PENDING("pending"),
    LEASED("leased"),
    COMPLETED("completed"),
    DEAD("dead");

    companion object {
        fun fromDatabase(value: String): BridgeOutboxState =
            entries.firstOrNull { it.databaseValue == value } ?: DEAD
    }
}

data class ChatRecord(
    val chatId: String,
    val kind: ChatKind = ChatKind.UNKNOWN,
    val displayName: String? = null,
    val subject: String? = null,
    val metadataJson: String? = null,
    val lastMessageAt: Long? = null,
    val unreadCount: Int = 0,
    val archived: Boolean = false,
    /**
     * When this chat was told it is talking to an AI. Null means it never was, which is what makes
     * the disclosure fire exactly once per contact rather than on every turn.
     */
    val aiDisclosureSentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class MessageRecord(
    val databaseId: Long = 0,
    val providerMessageId: String,
    val eventId: String? = null,
    val chatId: String,
    /** Indexed ownership for one physical-chat/persona conversation. Null means legacy/ambiguous. */
    val conversationKey: String? = null,
    val senderId: String? = null,
    val direction: MessageDirection,
    val messageType: String,
    val body: String? = null,
    val quotedProviderMessageId: String? = null,
    val mediaKey: String? = null,
    val occurredAt: Long,
    val receivedAt: Long = System.currentTimeMillis(),
    val deliveryState: MessageDeliveryState = MessageDeliveryState.UNKNOWN,
    val fromAdmin: Boolean = false,
    val metadataJson: String? = null,
)

/** Small bounded projection used by the chat roster instead of full message rows. */
data class LatestMessagePreview(
    val chatId: String,
    val direction: MessageDirection,
    val messageType: String,
    val body: String?,
    val occurredAt: Long,
)

data class ProcessedEventRecord(
    val eventId: String,
    val source: String,
    val eventType: String,
    val chatId: String? = null,
    val providerMessageId: String? = null,
    val payloadHash: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
)

data class ChatMemoryRecord(
    val chatId: String,
    val conversationKey: String = chatId,
    val summary: String,
    val factsJson: String? = null,
    val lastProviderMessageId: String? = null,
    val sourceMessageCount: Int = 0,
    val revision: Long = 1,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class PersonaRecord(
    val personaId: String,
    val name: String,
    val description: String? = null,
    val systemPrompt: String,
    val traitsJson: String? = null,
    val voiceConfigJson: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

/**
 * Headline of one stored memory document, as shown in the app's memory browser.
 *
 * [scope] is either `chat` or `persona`; [id] is the conversation key respectively persona id that
 * identifies the document. [preview] is the first ~200 characters of the summary — the full body is
 * only read when a document is opened.
 */
data class MemoryDocumentSummary(
    val scope: String,
    val id: String,
    val owner: String,
    val characters: Int,
    val preview: String,
    val hasFacts: Boolean,
    val sourceMessageCount: Int,
    val revision: Long,
    val updatedAt: Long,
)

/**
 * One persona-owned chat memory, as read for the cross-chat synthesis.
 *
 * Unlike [MemoryDocumentSummary] this carries the actual summary text — the synthesis has to read
 * the memories, not list them — which is why it is capped by the caller instead of at 200 chars.
 */
data class PersonaChatMemoryRow(
    val conversationKey: String,
    val chatId: String,
    val summary: String,
    /** Durable number of successful chat-memory writes for this conversation. */
    val revision: Long,
    val updatedAt: Long,
)

data class PersonaMemoryRecord(
    val personaId: String,
    val summary: String,
    val factsJson: String? = null,
    val revision: Long = 1,
    /**
     * Sum of this persona's chat-memory revisions at the moment of this synthesis. The cadence gate
     * measures its distance from the current total, so a forced synthesis moves the next automatic
     * one a full interval away instead of leaving it due again.
     */
    val lastChatWriteCount: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class AccessEntryRecord(
    val listKind: AccessListKind,
    val subjectType: AccessSubjectType,
    val subjectId: String,
    val label: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class ScopedSettingRecord(
    val scopeType: String,
    val scopeId: String = GLOBAL_SCOPE_ID,
    val key: String,
    val value: String,
    val valueType: StoredSettingValueType = StoredSettingValueType.STRING,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val GLOBAL_SCOPE_ID = ""
    }
}

data class SettingAddress(
    val scopeType: String,
    val scopeId: String = ScopedSettingRecord.GLOBAL_SCOPE_ID,
    val key: String,
)

data class DatabaseSettingsSnapshot(
    val revision: Long,
    val settings: List<ScopedSettingRecord>,
)

data class SettingsCompareAndSwapResult(
    val applied: Boolean,
    val snapshot: DatabaseSettingsSnapshot,
) {
    val revision: Long
        get() = snapshot.revision
}

/**
 * Canonical scope mapping for the SettingsPersistence adapter.
 *
 * Global/app/legacy maps store their map key in setting_key with an empty scope_id. Contact maps
 * store the contact ID in scope_id and use [CONTACT_VALUE_KEY] as the fixed setting_key.
 */
object SettingsScopes {
    const val GLOBAL = "global"
    const val APP = "app"
    const val PERSONA_CONTACT = "persona_contact"
    const val PROACTIVE_CONTACT = "proactive_contact"
    const val RETAINED_LEGACY = "retained_legacy"
    const val CONTACT_VALUE_KEY = "value"

    /**
     * One chat's deviations from the global settings, keyed by chat id and setting key.
     *
     * Deliberately outside [SettingsRepository]'s snapshot: that snapshot is the account-wide
     * truth every screen and every chat command reads, and folding per-chat rows into it would let
     * one conversation silently redefine what "the model" or "the memory interval" means
     * everywhere. These rows are resolved once, against the chat a turn belongs to, and only for
     * the handful of keys listed in `ChatOverrides`.
     */
    const val CHAT = "chat"
}

data class ProactiveStateRecord(
    val chatId: String,
    val enabled: Boolean = false,
    val nextDueAt: Long? = null,
    val cooldownUntil: Long? = null,
    val lastInboundAt: Long? = null,
    val lastOutboundAt: Long? = null,
    val dailyWindowStartedAt: Long? = null,
    val dailyOutboundCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val leaseOwner: String? = null,
    val leaseUntil: Long? = null,
    val stateJson: String? = null,

    /**
     * When the chat was armed for proactive outreach without any inbound message, i.e. the
     * moment the user raised the per-contact proactivity level for a stranger. Stays null for
     * chats that started with a real inbound message, so cold-contact caps keep working.
     */
    val coldOutreachAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class MediaAnalysisRecord(
    val contentHash: String,
    val analyzer: String,
    val analyzerVersion: String,
    val mediaType: String,
    val resultJson: String,
    val byteSize: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = createdAt,
    val expiresAt: Long? = null,
)

/**
 * [OutboundSafetyRecord.outboundKind] of the bookkeeping row that reserves a whole turn.
 *
 * One reply reserves the turn once and then reserves every visible bubble separately, so the
 * permit row is not a message anybody received. Both the send budget and the "sent today" figure
 * have to skip it; when only one of them did, the screen reported roughly twice the messages that
 * had actually gone out and a daily cap that was working looked as if it were being ignored.
 */
const val OUTBOUND_KIND_TURN_PERMIT = "turn_permit"

data class OutboundSafetyRecord(
    val databaseId: Long = 0,
    val dedupeKey: String,
    val chatId: String? = null,
    val outboundKind: String,
    val decision: OutboundDecision,
    val reasonCode: String,
    val status: OutboundStatus = OutboundStatus.RESERVED,
    val payloadHash: String? = null,
    val plannedAt: Long = System.currentTimeMillis(),
    val committedAt: Long? = null,
    val expiresAt: Long? = null,
    val metadataJson: String? = null,
)

data class OutboundReservation(
    val acquired: Boolean,
    val record: OutboundSafetyRecord,
)

data class ActivityLogRecord(
    val databaseId: Long = 0,
    val occurredAt: Long = System.currentTimeMillis(),
    val level: ActivityLevel,
    val category: String,
    val action: String,
    val chatId: String? = null,
    val correlationId: String? = null,
    val summary: String,
    val detailsJson: String? = null,
)

data class BridgeOutboxRecord(
    val databaseId: Long = 0,
    val dedupeKey: String,
    val operation: String,
    val chatId: String? = null,
    val payloadJson: String,
    val priority: Int = 0,
    val state: BridgeOutboxState = BridgeOutboxState.PENDING,
    val attemptCount: Int = 0,
    val availableAt: Long = System.currentTimeMillis(),
    val leaseOwner: String? = null,
    val leaseUntil: Long? = null,
    val lastError: String? = null,
    val resultJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class BridgeEnqueueResult(
    val enqueued: Boolean,
    val record: BridgeOutboxRecord,
)

data class MaintenanceResult(
    val processedEventsDeleted: Int,
    val messagesDeleted: Int,
    val mediaEntriesDeleted: Int,
    val outboundLedgerRowsDeleted: Int,
    val activityRowsDeleted: Int,
    val outboxRowsDeleted: Int,
) {
    val totalRowsDeleted: Int
        get() = processedEventsDeleted +
            messagesDeleted +
            mediaEntriesDeleted +
            outboundLedgerRowsDeleted +
            activityRowsDeleted +
            outboxRowsDeleted
}

data class RuntimeCountSnapshot(
    val processedInbound: Int,
    val sentOutbound: Int,
    val pendingChats: Int,
)
