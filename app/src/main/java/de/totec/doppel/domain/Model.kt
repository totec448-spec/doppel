package de.totec.doppel.domain

/**
 * Small immutable transport/domain objects. They intentionally do not depend on
 * Android, SQLite, Compose, Baileys, or OpenRouter so the bot engine can be
 * tested as a plain Kotlin state machine.
 */
enum class BridgeConnectionState {
    NOT_CONFIGURED,
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    PAIRING,
    CONNECTED,
    BACKING_OFF,
    ERROR,
}

enum class MediaKind {
    IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT,
    STICKER,
    UNKNOWN,
}

enum class ChatEventKind {
    MESSAGE,
    REACTION,
    EDIT,
    DELETE,

    /**
     * The contact called and the bridge declined after letting it ring.
     *
     * It is conversational input like any other message: a call that rang out into nothing, with
     * no reaction afterwards, is a conspicuous break for someone who otherwise answers within
     * minutes.
     */
    CALL_MISSED,
}

data class BridgeStatus(
    val connection: BridgeConnectionState = BridgeConnectionState.NOT_CONFIGURED,
    val detail: String = "",
    val accountJid: String? = null,
    val accountName: String? = null,
    val lastConnectedAt: Long? = null,
    val reconnectAttempt: Int = 0,
    val pairingCode: String? = null,
)

data class MediaReference(
    val id: String,
    val kind: MediaKind,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val fileName: String? = null,
    val caption: String? = null,
    /**
     * Play length of an audio or video message in seconds; null for everything
     * else. Drives how long she "listens" before the play receipt and the
     * recording indicator go out.
     */
    val durationSeconds: Int? = null,
)

data class QuotedMessage(
    val messageId: String,
    val senderJid: String? = null,
    val text: String = "",
)

data class IncomingEvent(
    val eventId: String,
    val sequence: Long,
    val kind: ChatEventKind,
    val messageId: String,
    val chatJid: String,
    val chatName: String? = null,
    val isGroup: Boolean,
    val senderJid: String,
    val senderName: String? = null,
    val fromMe: Boolean,
    val timestampMs: Long,
    val text: String = "",
    val quoted: QuotedMessage? = null,
    val reactionEmoji: String? = null,
    val targetMessageId: String? = null,
    val media: MediaReference? = null,
    /** `audio` or `video` for [ChatEventKind.CALL_MISSED], null everywhere else. */
    val callMedia: String? = null,
    /** Alternate PN/LID forms supplied by the transport, never display names. */
    val chatAliases: List<String> = emptyList(),
    val senderAliases: List<String> = emptyList(),
    /** Persona ownership frozen when the event is accepted; never re-resolved while queued. */
    val personaKey: String? = null,
)

data class Persona(
    val key: String,
    val displayName: String,
    val prompt: String,
    val voice: String,
    val builtIn: Boolean,
    val enabled: Boolean = true,
)
