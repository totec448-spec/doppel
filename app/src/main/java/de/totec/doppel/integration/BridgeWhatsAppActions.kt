package de.totec.doppel.integration

import de.totec.doppel.domain.IncomingEvent
import de.totec.doppel.engine.PlannedSideEffect
import de.totec.doppel.engine.WhatsAppActions
import de.totec.doppel.media.ApprovedMediaAssetStore
import de.totec.doppel.transport.BridgeTransport
import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only mapping from native use cases to transport actions. Payloads are
 * intentionally rebuilt instead of passing domain objects or Baileys state
 * across the process boundary.
 */
class BridgeWhatsAppActions(
    private val bridge: BridgeTransport,
    private val keys: MessageKeyCache,
    private val approvedMedia: ApprovedMediaAssetStore,
    // No default on purpose. A fallback executor here would look harmless and quietly send
    // every user-visible action straight down the socket, outside the durable outbox — no
    // lease, no retry, no dead-letter. Callers have to name the port they are wiring in.
    private val durableActions: BridgeActionExecutor,
) : WhatsAppActions {
    private val receiptMutex = Mutex()
    private val presenceMutex = Mutex()
    private val readReceipts = BoundedReceiptSet(MAX_TRACKED_RECEIPTS)
    private val playedReceipts = BoundedReceiptSet(MAX_TRACKED_RECEIPTS)
    private val presenceStates = LinkedHashMap<String, PresenceState>(MAX_TRACKED_PRESENCE, 0.75f, true)
    private var presenceSequence = 0L

    override suspend fun markRead(chatJid: String, messageIds: List<String>) {
        receiptMutex.withLock {
            val unsent =
                messageIds
                    .distinct()
                    .filterNot { readReceipts.contains("$chatJid\u0000$it") }
                    .take(MAX_READ_RECEIPTS)
            if (unsent.isEmpty()) return@withLock
            val messages = JSONArray()
            unsent.forEach { id ->
                messages.put(keys.json(chatJid, id, defaultFromMe = false))
            }
            bridge.action(
                action = "mark_read",
                payload = JSONObject().put("messages", messages),
                timeoutMs = RECEIPT_TIMEOUT_MS,
                requestId = actionId("read", chatJid, unsent.joinToString(",")),
            )
            unsent.forEach { readReceipts.add("$chatJid\u0000$it") }
        }
    }

    override suspend fun markPlayed(chatJid: String, messageId: String) {
        receiptMutex.withLock {
            val receiptKey = "$chatJid\u0000$messageId"
            if (playedReceipts.contains(receiptKey)) return@withLock
            bridge.action(
                action = "mark_played",
                payload = JSONObject().put("message", keys.json(chatJid, messageId, false)),
                timeoutMs = RECEIPT_TIMEOUT_MS,
                requestId = actionId("played", chatJid, messageId),
            )
            playedReceipts.add(receiptKey)
        }
    }

    override suspend fun setPresence(chatJid: String, presence: String) {
        presenceMutex.withLock {
            val now = System.currentTimeMillis()
            val previous = presenceStates[chatJid]
            if (previous?.value == presence && now - previous.sentAtMs < MIN_PRESENCE_REFRESH_MS) {
                return@withLock
            }
            bridge.action(
                action = "presence",
                payload =
                    JSONObject()
                        .put("chatId", chatJid)
                        .put("state", presence),
                timeoutMs = PRESENCE_TIMEOUT_MS,
                requestId = actionId("presence", chatJid, presence, (++presenceSequence).toString()),
            )
            presenceStates[chatJid] = PresenceState(presence, now)
            while (presenceStates.size > MAX_TRACKED_PRESENCE) {
                presenceStates.remove(presenceStates.entries.first().key)
            }
        }
    }

    override suspend fun sendText(
        chatJid: String,
        text: String,
        quoteMessageId: String?,
        idempotencyKey: String,
        quotePreview: String?,
    ): String {
        val payload =
            JSONObject()
                .put("chatId", chatJid)
                .put("text", text)
        quoteMessageId?.let {
            payload.put(
                "replyTo",
                keys.json(chatJid, it, defaultFromMe = false)
                    .apply {
                        quotePreview?.trim()?.takeIf(String::isNotEmpty)?.let { preview ->
                            put("preview", preview.take(MAX_QUOTE_PREVIEW_CHARACTERS))
                        }
                    },
            )
        }
        return durableActions.execute(
            action = "send_text",
            payload = payload,
            idempotencyKey = idempotencyKey,
            chatId = chatJid,
            priority = PRIORITY_TEXT,
        ).messageId()
    }

    override suspend fun sendReaction(
        chatJid: String,
        targetMessageId: String,
        emoji: String,
        idempotencyKey: String,
    ): String? =
        durableActions.execute(
            action = "send_reaction",
            payload =
                JSONObject()
                    .put("message", keys.json(chatJid, targetMessageId, defaultFromMe = false))
                    .put("emoji", emoji),
            idempotencyKey = idempotencyKey,
            chatId = chatJid,
            priority = PRIORITY_REACTION,
        ).result
            ?.optJSONObject("message")
            ?.optString("id")
            ?.takeIf(String::isNotBlank)

    override suspend fun sendMedia(
        chatJid: String,
        action: PlannedSideEffect.SendMedia,
    ): String {
        val kind =
            when {
                action.voiceNote -> "audio"
                action.mimeType.startsWith("image/") -> "image"
                action.mimeType.startsWith("video/") -> "video"
                action.mimeType.startsWith("audio/") -> "audio"
                else -> "document"
            }
        val transportMessageId =
            durableActions.execute(
                action = "send_media",
                payload =
                    JSONObject()
                        .put("chatId", chatJid)
                        .put("mediaId", action.uploadId)
                        .put("kind", kind)
                        .put("ptt", action.voiceNote)
                        .apply {
                            action.durationSeconds
                                ?.takeIf { action.voiceNote && it > 0 }
                                ?.let { put("durationSeconds", it) }
                            action.waveformBase64
                                ?.takeIf { action.voiceNote && it.isNotBlank() }
                                ?.let { put("waveform", it) }
                            action.caption?.takeIf(String::isNotBlank)?.let { put("caption", it) }
                        },
                idempotencyKey = action.idempotencyKey,
                chatId = chatJid,
                priority = PRIORITY_MEDIA,
            ).messageId()
        // The durable operation is now completed (or its previously completed
        // result was replayed). Only then consume the app-private repeat
        // marker. A failed pre-effect attempt therefore stays retryable.
        action.approvedAssetId?.let { assetId ->
            approvedMedia.markSentTo(
                assetId = assetId,
                personaKey = requireNotNull(action.approvedPersonaKey),
                chatId = chatJid,
            )
        }
        return transportMessageId
    }

    override suspend fun editText(
        chatJid: String,
        messageId: String,
        text: String,
        idempotencyKey: String,
    ) {
        durableActions.execute(
            action = "edit_message",
            payload =
                JSONObject()
                    .put("message", keys.json(chatJid, messageId, defaultFromMe = true))
                    .put("text", text),
            idempotencyKey = idempotencyKey,
            chatId = chatJid,
            priority = PRIORITY_EDIT,
        )
    }

    override suspend fun blockContact(
        jid: String,
        reason: String,
        idempotencyKey: String,
        aliases: List<String>,
    ) {
        durableActions.execute(
            action = "block",
            payload = blockContactPayload(jid, aliases),
            idempotencyKey = idempotencyKey,
            chatId = jid,
            priority = PRIORITY_BLOCK,
        )
    }

    private fun de.totec.doppel.transport.BridgeActionResult.messageId(): String =
        result
            ?.optJSONObject("message")
            ?.optString("id")
            ?.takeIf(String::isNotBlank)
            ?: error("The bridge returned no message ID")

    private fun actionId(vararg parts: String): String =
        parts.joinToString(":").toBridgeRequestId()

    private companion object {
        const val MAX_READ_RECEIPTS = 100
        const val RECEIPT_TIMEOUT_MS = 3_000L
        const val PRESENCE_TIMEOUT_MS = 1_500L
        const val MAX_TRACKED_RECEIPTS = 4_096
        const val MAX_TRACKED_PRESENCE = 64
        /**
         * How long the same presence value is suppressed for one chat.
         *
         * Must sit *below* the engine's presence refresh cadence
         * ([de.totec.doppel.engine.BotEngine] `PRESENCE_REFRESH_MS`, 9 s) so a deliberate
         * keep-alive always goes out, and far enough above zero to actually collapse the
         * repeats it exists for: a release/hold pair inside one turn, back-to-back bubbles,
         * a "paused" that arrives twice. At four seconds it sat under every cadence in the
         * engine and suppressed nothing at all.
         */
        const val MIN_PRESENCE_REFRESH_MS = 6_000L
        const val PRIORITY_TEXT = 100
        const val PRIORITY_MEDIA = 100
        const val PRIORITY_REACTION = 90
        const val PRIORITY_EDIT = 95
        const val PRIORITY_BLOCK = 200

        /** A quote preview is a bubble caption, never the full quoted message. */
        const val MAX_QUOTE_PREVIEW_CHARACTERS = 512
    }

    private data class PresenceState(val value: String, val sentAtMs: Long)
}

private class BoundedReceiptSet(private val capacity: Int) {
    private val entries = LinkedHashMap<String, Unit>(capacity, 0.75f, false)

    fun contains(value: String): Boolean = entries.containsKey(value)

    fun add(value: String) {
        entries[value] = Unit
        while (entries.size > capacity) {
            entries.remove(entries.entries.first().key)
        }
    }
}

internal fun blockContactPayload(
    jid: String,
    aliases: List<String>,
): JSONObject =
    JSONObject()
        .put("chatId", jid)
        .put(
            "aliases",
            JSONArray(
                aliases.asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(16)
                    .toList(),
            ),
        )

class MessageKeyCache(
    private val capacity: Int = 512,
) {
    private val values =
        object : LinkedHashMap<String, MessageKey>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MessageKey>?): Boolean =
                size > capacity
        }

    init {
        require(capacity in 32..4_096)
    }

    fun remember(event: IncomingEvent) {
        synchronized(values) {
            values[key(event.chatJid, event.messageId)] =
                MessageKey(
                    chatJid = event.chatJid,
                    id = event.messageId,
                    fromMe = event.fromMe,
                    participant = event.senderJid.takeIf { event.isGroup },
                )
        }
    }

    fun json(
        chatJid: String,
        messageId: String,
        defaultFromMe: Boolean,
    ): JSONObject {
        val cached = synchronized(values) { values[key(chatJid, messageId)] }
        return JSONObject()
            .put("chatId", chatJid)
            .put("id", messageId)
            .put("fromMe", cached?.fromMe ?: defaultFromMe)
            .apply { cached?.participant?.let { put("participant", it) } }
    }

    private fun key(chatJid: String, messageId: String): String = "$chatJid\u0000$messageId"

    private data class MessageKey(
        val chatJid: String,
        val id: String,
        val fromMe: Boolean,
        val participant: String?,
    )
}
