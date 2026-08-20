package de.totec.doppel.transport

import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.domain.ChatEventKind
import de.totec.doppel.domain.IncomingEvent
import de.totec.doppel.domain.MediaKind
import de.totec.doppel.domain.MediaReference
import de.totec.doppel.domain.QuotedMessage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

const val BRIDGE_PROTOCOL_VERSION = 1

sealed interface BridgeFrame {
    data class Welcome(
        val sequence: Long,
        val capabilities: Set<String>,
        val accountJid: String?,
        val accountName: String?,
        val accountState: BridgeConnectionState,
        val resume: ResumeState,
    ) : BridgeFrame

    data class ResumeState(
        val requested: Long,
        val after: Long,
        val oldest: Long,
        val latest: Long,
        val count: Int,
        val gap: Boolean,
        val reset: Boolean,
    )

    data class Ready(
        val sequence: Long,
        val accountJid: String?,
        val accountName: String?,
        val accountState: BridgeConnectionState,
    ) : BridgeFrame

    data class Connection(
        val sequence: Long,
        val state: BridgeConnectionState,
        val detail: String,
        val accountJid: String?,
        val accountName: String?,
    ) : BridgeFrame

    data class PairingCode(
        val sequence: Long,
        val available: Boolean,
        val expiresAtMs: Long?,
    ) : BridgeFrame

    data class Incoming(
        val sequence: Long,
        val event: IncomingEvent,
    ) : BridgeFrame

    /**
     * One receipt stanza, which WhatsApp may issue for several messages at once.
     *
     * [messageIds] is never empty; [messageId] names the first of them and is what the wire
     * contract has always carried, so a bridge that does not know the list yet still decodes.
     */
    data class Delivery(
        val sequence: Long,
        val requestId: String?,
        val messageIds: List<String>,
        val status: String,
        val timestampMs: Long,
    ) : BridgeFrame {
        init {
            require(messageIds.isNotEmpty()) { "A delivery receipt needs at least one message" }
        }

        val messageId: String
            get() = messageIds.first()
    }

    /**
     * Coarse transport telemetry emitted by the companion. It is deliberately
     * separate from the native outbound policy: the bridge may report a hard
     * disconnect, block-list change, or WhatsApp message-capping state, but it
     * can never authorize an Android-side send.
     */
    data class Safety(
        val sequence: Long,
        val kind: String,
        val detail: String,
    ) : BridgeFrame

    /**
     * A journalled event this build cannot decode, forwarded instead of dropped.
     *
     * Killing the session over one such frame was self-defeating: the bridge journal is durable, so
     * the same frame is replayed on the next connect and rejected again, which turned a single
     * unreadable event into a permanently dead link that only re-pairing cleared. Ordering is what
     * the stream really needs, so the frame keeps its place in the sequence, gets acknowledged, and
     * is reported to the operator instead of decoded.
     */
    data class Undecodable(
        val sequence: Long,
        val frameType: String,
        val reason: String,
    ) : BridgeFrame

    data class ActionResult(
        val requestId: String,
        val ok: Boolean,
        val result: JSONObject?,
        val errorCode: String?,
        val errorMessage: String?,
    ) : BridgeFrame

    data class ProtocolError(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : BridgeFrame
}

class BridgeProtocolException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The wire format is deliberately tiny and versioned. All transport-specific
 * Baileys objects are normalized by the companion bridge before they cross this
 * boundary; the Android runtime never depends on Baileys field layouts.
 */
object BridgeProtocol {
    const val VERSION = BRIDGE_PROTOCOL_VERSION

    private const val MAX_FRAME_CHARS = 1_000_000
    private const val MAX_TEXT_CHARS = 100_000
    private const val MAX_DETAIL_CHARS = 2_000
    private const val MAX_SAFETY_DETAIL_CHARS = 131_072
    private const val MAX_EVENT_ID_CHARS = 512
    private const val MAX_MESSAGE_ID_CHARS = 192
    private const val MAX_JID_CHARS = 192
    private const val MAX_MEDIA_DURATION_SECONDS = 10 * 60
    private val JID = Regex("^[^\\s@]{1,160}@(s\\.whatsapp\\.net|lid|g\\.us)$")
    private val MEDIA_ID = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val MEDIA_HASH = Regex("^[a-f0-9]{64}$")

    fun hello(
        clientId: String,
        resumeAfter: Long,
    ): String =
        JSONObject()
            .put("v", BRIDGE_PROTOCOL_VERSION)
            .put("type", "hello")
            .put("clientId", clientId)
            .put("resumeAfter", resumeAfter.coerceAtLeast(0))
            .put("capabilities", JSONArray(listOf("media_http", "durable_ack", "pairing_code")))
            .toString()

    fun acknowledge(sequence: Long): String =
        JSONObject()
            .put("v", BRIDGE_PROTOCOL_VERSION)
            .put("type", "ack_event")
            .put("sequence", sequence.coerceAtLeast(0))
            .toString()

    fun action(
        requestId: String,
        action: String,
        payload: JSONObject = JSONObject(),
    ): String {
        require(requestId.isNotBlank()) { "requestId is required" }
        require(action in ACTIONS) { "Unsupported bridge action" }
        return JSONObject()
            .put("v", BRIDGE_PROTOCOL_VERSION)
            .put("type", "action")
            .put("id", requestId)
            .put("action", action)
            .put("payload", payload)
            .toString()
    }

    fun decode(raw: String): BridgeFrame {
        if (raw.length > MAX_FRAME_CHARS) {
            throw BridgeProtocolException("Bridge frame exceeds the configured limit")
        }
        val root =
            try {
                JSONObject(raw)
            } catch (error: JSONException) {
                throw BridgeProtocolException("Bridge sent malformed JSON", error)
            }

        val version = root.optInt("v", -1)
        if (version != BRIDGE_PROTOCOL_VERSION) {
            throw BridgeProtocolException("Unsupported bridge protocol version $version")
        }
        return when (val type = root.requiredString("type")) {
            "welcome" -> decodeWelcome(root)
            "ready" -> decodeReady(root)
            "connection" -> decodeConnection(root)
            "pairing_code" -> decodePairingCode(root)
            "incoming" -> decodeIncoming(root)
            "delivery" -> decodeDelivery(root)
            "safety" -> decodeSafety(root)
            "action_result" -> decodeActionResult(root)
            "error" ->
                BridgeFrame.ProtocolError(
                    code = root.optString("code", "bridge_error").bounded(MAX_DETAIL_CHARS),
                    message = root.optString("message", "Bridge error").bounded(MAX_DETAIL_CHARS),
                    retryable = root.optBoolean("retryable", false),
                )
            else -> throw BridgeProtocolException("Unknown bridge frame type $type")
        }
    }

    /**
     * Turns a frame that failed [decode] into a quarantined event, or returns null when the frame
     * cannot be quarantined and the session genuinely has to end.
     *
     * Quarantine needs exactly one thing: the sequence number, because that is what keeps the
     * ordered stream intact across the skip. A handshake frame carries session state rather than a
     * stream position, so a broken one stays fatal. An *unknown* type is quarantined on purpose —
     * an older app meeting a newer bridge should skip what it does not understand, not disconnect.
     */
    fun quarantine(raw: String, reason: String): BridgeFrame.Undecodable? {
        val root =
            try {
                JSONObject(raw)
            } catch (error: JSONException) {
                return null
            }
        if (root.optInt("v", -1) != BRIDGE_PROTOCOL_VERSION) return null
        val type = root.optString("type").trim().bounded(60)
        if (type.isEmpty() || type in HANDSHAKE_TYPES) return null
        val sequence = root.optLong("sequence", -1)
        if (sequence < 0) return null
        return BridgeFrame.Undecodable(
            sequence = sequence,
            frameType = type,
            reason = reason.bounded(MAX_DETAIL_CHARS),
        )
    }

    private fun decodeWelcome(root: JSONObject): BridgeFrame.Welcome {
        val account = root.optObject("account")
        val resume =
            root.optObject("resume")
                ?: throw BridgeProtocolException("Welcome frame is missing resume state")
        val requested = resume.requiredNonNegativeLong("requested")
        val after = resume.requiredNonNegativeLong("after")
        val oldest = resume.requiredNonNegativeLong("oldest")
        val latest = resume.requiredNonNegativeLong("latest")
        val count = resume.optInt("count", -1)
        if (count !in 0..MAX_REPLAY_EVENTS) {
            throw BridgeProtocolException("Welcome replay count is invalid")
        }
        if (after > latest && latest != 0L) {
            throw BridgeProtocolException("Welcome resume cursor is ahead of journal bounds")
        }
        return BridgeFrame.Welcome(
            sequence = root.requiredNonNegativeLong("sequence"),
            capabilities = root.optArray("capabilities").stringSet(),
            accountJid = account?.nullableJid("jid"),
            accountName = account?.nullableString("name")?.bounded(300),
            accountState = parseConnectionState(account?.optString("state").orEmpty()),
            resume =
                BridgeFrame.ResumeState(
                    requested = requested,
                    after = after,
                    oldest = oldest,
                    latest = latest,
                    count = count,
                    gap = resume.optBoolean("gap", false),
                    reset = resume.optBoolean("reset", false),
                ),
        )
    }

    private fun decodeReady(root: JSONObject): BridgeFrame.Ready {
        val account = root.optObject("account")
        return BridgeFrame.Ready(
            sequence = root.requiredNonNegativeLong("sequence"),
            accountJid = account?.nullableJid("jid"),
            accountName = account?.nullableString("name")?.bounded(300),
            accountState = parseConnectionState(account?.optString("state").orEmpty()),
        )
    }

    private fun decodeConnection(root: JSONObject): BridgeFrame.Connection {
        val state = parseConnectionState(root.optString("state"))
        val account = root.optObject("account")
        return BridgeFrame.Connection(
            sequence = root.requiredSequence(),
            state = state,
            detail = root.optString("detail").bounded(MAX_DETAIL_CHARS),
            accountJid = account?.nullableJid("jid"),
            accountName = account?.nullableString("name")?.bounded(300),
        )
    }

    private fun decodePairingCode(root: JSONObject): BridgeFrame.PairingCode {
        return BridgeFrame.PairingCode(
            sequence = root.requiredSequence(),
            available = root.optBoolean("available", true),
            expiresAtMs = root.nullableLong("expiresAtMs"),
        )
    }

    private fun decodeIncoming(root: JSONObject): BridgeFrame.Incoming {
        val event = root.optJSONObject("event")
            ?: throw BridgeProtocolException("Incoming frame is missing event")
        val sequence = root.requiredSequence()
        val kind =
            when (event.optString("kind", "message").lowercase()) {
                "message" -> ChatEventKind.MESSAGE
                "reaction" -> ChatEventKind.REACTION
                "edit" -> ChatEventKind.EDIT
                "delete" -> ChatEventKind.DELETE
                "call_missed" -> ChatEventKind.CALL_MISSED
                else -> throw BridgeProtocolException("Unsupported incoming event kind")
            }
        val mediaJson = event.optObject("media")
        val media =
            mediaJson?.let {
                val size = it.optLong("sizeBytes", -1)
                if (size < 0) throw BridgeProtocolException("Invalid media size")
                val mediaId = it.requiredBoundedString("id", 128)
                if (!MEDIA_ID.matches(mediaId)) {
                    throw BridgeProtocolException("Invalid media id")
                }
                MediaReference(
                    id = mediaId,
                    kind = parseMediaKind(it.optString("kind")),
                    mimeType = it.optString("mimeType", "application/octet-stream").bounded(200),
                    sizeBytes = size,
                    sha256 =
                        it.nullableString("sha256")
                            ?.lowercase()
                            ?.also { hash ->
                                if (!MEDIA_HASH.matches(hash)) {
                                    throw BridgeProtocolException("Invalid media digest")
                                }
                            },
                    fileName = it.nullableString("fileName")?.bounded(300),
                    caption = it.nullableString("caption")?.bounded(MAX_TEXT_CHARS),
                    // Peer-reported, so clamp again on this side instead of
                    // trusting the native clamp alone.
                    durationSeconds =
                        it.optInt("durationSeconds", 0)
                            .takeIf { seconds -> seconds > 0 }
                            ?.coerceAtMost(MAX_MEDIA_DURATION_SECONDS),
                )
            }
        val quotedJson = event.optObject("quoted")
        val quoted =
            quotedJson?.let {
                QuotedMessage(
                    messageId = it.requiredBoundedString("messageId", MAX_MESSAGE_ID_CHARS),
                    senderJid = it.nullableJid("senderJid"),
                    text = it.optString("text").bounded(MAX_TEXT_CHARS),
                )
            }
        val text =
            event.optString("text").bounded(MAX_TEXT_CHARS).ifBlank {
                when (event.optString("category").lowercase()) {
                    "contact" -> "[Contact shared]"
                    "location" -> "[Standort geteilt]"
                    "poll" -> "[Umfrage geteilt]"
                    // Stickers are deliberately never downloaded, so the media
                    // ingress drops them and the event arrives with no text and
                    // no attachment — the bot saw nothing at all and answered
                    // past it. The marker is all it needs to acknowledge one or
                    // react to it; the content stays unread on purpose.
                    "sticker" -> "[Sticker]"
                    "unknown" -> "[Unsupported WhatsApp message]"
                    else -> ""
                }
            }
        val chatJid = event.requiredJid("chatJid")

        return BridgeFrame.Incoming(
            sequence = sequence,
            event =
                IncomingEvent(
                    eventId = event.requiredBoundedString("eventId", MAX_EVENT_ID_CHARS),
                    sequence = sequence,
                    kind = kind,
                    messageId = event.requiredBoundedString("messageId", MAX_MESSAGE_ID_CHARS),
                    chatJid = chatJid,
                    chatName = event.nullableString("chatName")?.bounded(300),
                    // Never trust a redundant Boolean for an authorization
                    // boundary; the normalized transport JID is authoritative.
                    isGroup = chatJid.endsWith("@g.us"),
                    senderJid = event.requiredJid("senderJid"),
                    senderName = event.nullableString("senderName")?.bounded(300),
                    fromMe = event.optBoolean("fromMe", false),
                    timestampMs =
                        event.optLong("timestampMs", System.currentTimeMillis()).coerceAtLeast(0L),
                    text = text,
                    quoted = quoted,
                    reactionEmoji = event.nullableString("reactionEmoji")?.bounded(32),
                    targetMessageId =
                        event.nullableString("targetMessageId")?.also {
                            if (it.length > MAX_MESSAGE_ID_CHARS) {
                                throw BridgeProtocolException("Invalid targetMessageId")
                            }
                        },
                    media = media,
                    callMedia =
                        event
                            .nullableString("callMedia")
                            ?.lowercase()
                            ?.takeIf { kind == ChatEventKind.CALL_MISSED && it in CALL_MEDIA },
                    chatAliases = event.jidList("chatAliases"),
                    senderAliases = event.jidList("senderAliases"),
                ),
        )
    }

    private fun decodeDelivery(root: JSONObject): BridgeFrame.Delivery {
        // The single ID stays authoritative for the frame's own validity; the list is an
        // extension, so a bridge that only sends the old shape keeps working unchanged.
        val primary = root.requiredBoundedString("messageId", MAX_MESSAGE_ID_CHARS)
        val values = root.optArray("messageIds")
        if (values != null && values.length() > MAX_DELIVERY_MESSAGE_IDS) {
            throw BridgeProtocolException("Too many delivery message ids")
        }
        val messageIds =
            buildList {
                add(primary)
                for (index in 0 until (values?.length() ?: 0)) {
                    val value = values!!.optString(index).trim()
                    if (
                        value.isEmpty() ||
                        value.length > MAX_MESSAGE_ID_CHARS ||
                        value.any(Char::isISOControl)
                    ) {
                        throw BridgeProtocolException("Invalid delivery message id")
                    }
                    if (value !in this) add(value)
                }
            }
        return BridgeFrame.Delivery(
            sequence = root.requiredSequence(),
            requestId = root.nullableString("requestId"),
            messageIds = messageIds,
            status = root.requiredString("status").bounded(100),
            timestampMs = root.optLong("timestampMs", System.currentTimeMillis()),
        )
    }

    private fun decodeSafety(root: JSONObject): BridgeFrame.Safety =
        BridgeFrame.Safety(
            sequence = root.requiredSequence(),
            kind = root.optString("kind", "transport").bounded(100),
            // Keep the event useful for the local activity log without
            // inventing a second unbounded transport object model.
            detail = root.toString().bounded(MAX_SAFETY_DETAIL_CHARS),
        )

    private fun decodeActionResult(root: JSONObject): BridgeFrame.ActionResult {
        val error = root.optObject("error")
        return BridgeFrame.ActionResult(
            requestId = root.requiredString("id"),
            ok = root.optBoolean("ok", false),
            result = root.optObject("result"),
            errorCode = error?.nullableString("code")?.bounded(200),
            errorMessage = error?.nullableString("message")?.bounded(MAX_DETAIL_CHARS),
        )
    }

    private fun parseMediaKind(raw: String): MediaKind =
        when (raw.lowercase()) {
            "image" -> MediaKind.IMAGE
            "audio", "voice" -> MediaKind.AUDIO
            "video", "gif" -> MediaKind.VIDEO
            "document" -> MediaKind.DOCUMENT
            "sticker" -> MediaKind.STICKER
            else -> MediaKind.UNKNOWN
        }

    private fun parseConnectionState(raw: String): BridgeConnectionState =
        when (raw.lowercase()) {
            "connecting", "initializing" -> BridgeConnectionState.CONNECTING
            "authenticating" -> BridgeConnectionState.AUTHENTICATING
            "pairing", "pairing_required" -> BridgeConnectionState.PAIRING
            "connected", "ready" -> BridgeConnectionState.CONNECTED
            "backing_off", "reconnecting" -> BridgeConnectionState.BACKING_OFF
            "error", "logged_out", "bad_session", "manual_review" ->
                BridgeConnectionState.ERROR

            else -> BridgeConnectionState.DISCONNECTED
        }

    private fun JSONObject.requiredString(key: String): String {
        val value = nullableString(key)?.trim().orEmpty()
        if (value.isEmpty()) throw BridgeProtocolException("Missing $key")
        return value
    }

    private fun JSONObject.requiredBoundedString(key: String, maximum: Int): String {
        val value = requiredString(key)
        if (value.length > maximum || value.any(Char::isISOControl)) {
            throw BridgeProtocolException("Invalid $key")
        }
        return value
    }

    private fun JSONObject.requiredJid(key: String): String {
        val value = requiredBoundedString(key, MAX_JID_CHARS)
        if (!JID.matches(value)) throw BridgeProtocolException("Invalid $key")
        return value
    }

    private fun JSONObject.nullableJid(key: String): String? =
        nullableString(key)?.also {
            if (it.length > MAX_JID_CHARS || !JID.matches(it)) {
                throw BridgeProtocolException("Invalid $key")
            }
        }

    private fun JSONObject.requiredSequence(): Long {
        val sequence = optLong("sequence", -1)
        if (sequence < 0) throw BridgeProtocolException("Missing sequence")
        return sequence
    }

    private fun JSONObject.requiredNonNegativeLong(key: String): Long {
        if (!has(key) || isNull(key)) {
            throw BridgeProtocolException("Missing $key")
        }
        val value = optLong(key, -1L)
        if (value < 0L) throw BridgeProtocolException("Invalid $key")
        return value
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optObject(key: String): JSONObject? =
        if (!has(key) || isNull(key)) null else optJSONObject(key)

    private fun JSONObject.optArray(key: String): JSONArray? =
        if (!has(key) || isNull(key)) null else optJSONArray(key)

    private fun JSONArray?.stringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun JSONObject.jidList(key: String): List<String> {
        val values = optArray(key) ?: return emptyList()
        if (values.length() > MAX_IDENTITY_ALIASES) {
            throw BridgeProtocolException("Too many $key")
        }
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.optString(index).trim()
                if (value.isEmpty() || value.length > MAX_JID_CHARS || !JID.matches(value)) {
                    throw BridgeProtocolException("Invalid $key")
                }
                if (value !in this) add(value)
            }
        }
    }

    private fun String.bounded(max: Int): String =
        if (length <= max) this else substring(0, max)

    private const val MAX_REPLAY_EVENTS = 10_000
    private const val MAX_IDENTITY_ALIASES = 16

    /** Matches the bridge's per-frame receipt batch size. */
    private const val MAX_DELIVERY_MESSAGE_IDS = 256

    private val CALL_MEDIA = setOf("audio", "video")

    /** Frames that carry session state instead of a stream position. */
    private val HANDSHAKE_TYPES = setOf("welcome", "ready", "action_result", "error")

    val ACTIONS =
        setOf(
            "pair",
            "reconnect",
            "link_sleep",
            "link_wake",
            "logout",
            "send_text",
            "send_media",
            "send_reaction",
            "mark_read",
            "mark_played",
            "presence",
            "set_ingress_policy",
            "safety_refresh",
            "set_profile_picture",
            "set_status_message",
            "set_push_name",
            "edit_message",
            "delete_message",
            "block",
            "unblock",
        )
}
