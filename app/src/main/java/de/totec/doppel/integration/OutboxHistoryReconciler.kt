package de.totec.doppel.integration

import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.BridgeOutboxRecord
import de.totec.doppel.domain.MediaKind
import de.totec.doppel.domain.MediaHistoryLabels
import de.totec.doppel.engine.ChatHistoryLabels
import de.totec.doppel.engine.EngineStore
import de.totec.doppel.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Puts messages that WhatsApp delivered but the chat log never heard about back into the history.
 *
 * Sending is durable and the history write is not part of it: [DurableBridgeOutboxDispatcher] owns
 * the delivery, and the turn that asked for the send is what writes "You sent: …" afterwards. When
 * that turn is no longer there — the process died between the two, the turn was cancelled while
 * waiting and the row went out on a later recovery pass, or the dispatcher was closed under it —
 * the message reaches the contact and the bot has no memory of it. The next model call then answers
 * as if it had never written, and the chat screen shows a gap the contact does not see.
 *
 * The completed outbox row still holds everything needed to repair that: which chat, what was sent,
 * and the transport message ID the server handed back. Recording is keyed on that transport ID, the
 * same key the turn uses, so replaying a row that *was* recorded changes nothing.
 */
class OutboxHistoryReconciler(
    private val repository: BotRepository,
    private val settings: SettingsRepository,
    private val store: EngineStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val activityChanged: () -> Unit = {},
) {
    /**
     * Repairs everything the outbox delivered within the retention window.
     *
     * Runs once per start, which is also what recovers messages lost by earlier versions: the rows
     * are still there, they were just never read this way.
     */
    suspend fun sweep(): Int =
        withContext(ioDispatcher) {
            val since = (now() - SWEEP_LOOKBACK_MS).coerceAtLeast(0L)
            var afterUpdatedAt: Long? = null
            var afterId: Long? = null
            var recovered = 0
            do {
                val rows =
                    runCatching {
                        repository.listCompletedBridgeOperations(
                            operations = RECOVERABLE_OPERATIONS,
                            since = since,
                            limit = SWEEP_PAGE_ROWS,
                            afterUpdatedAt = afterUpdatedAt,
                            afterId = afterId,
                        )
                    }.getOrElse { return@withContext recovered }
                recovered += rows.count { recover(it) }
                rows.lastOrNull()?.let {
                    afterUpdatedAt = it.updatedAt
                    afterId = it.databaseId
                }
            } while (rows.size == SWEEP_PAGE_ROWS)
            recovered
        }

    /**
     * Repairs one completed row. Safe to call for every completion: a row that is already in the
     * history, carries no message ID, or is not a visible send returns false without writing.
     *
     * Never throws. It runs inside the dispatcher's completion path, where a failure here must not
     * turn a delivered message into a retry.
     */
    suspend fun recover(record: BridgeOutboxRecord): Boolean =
        try {
            withContext(ioDispatcher) { recoverOrThrow(record) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun recoverOrThrow(record: BridgeOutboxRecord): Boolean {
        if (record.operation !in RECOVERABLE_OPERATIONS) return false
        if (isAdminOutboxKey(record.dedupeKey)) return false
        val transportId = outboxTransportMessageId(record.resultJson) ?: return false
        if (repository.getMessage(transportId) != null) return false
        val payload = JSONObject(record.payloadJson)
        val chatJid =
            record.chatId?.takeIf(String::isNotBlank)
                ?: payload.optString("chatId").takeIf(String::isNotBlank)
                ?: return false
        val text = outboxHistoryLine(record.operation, payload) ?: return false
        val chat = repository.getChat(chatJid) ?: return false
        if (!belongsToCurrentChat(sentAt = record.updatedAt, chatCreatedAt = chat.createdAt)) {
            return false
        }
        store.recordAssistant(
            conversationKey = "$chatJid#${personaKeyAt(chatJid, record.updatedAt)}",
            chatJid = chatJid,
            text = text,
            transportMessageIds = listOf(transportId),
            // When the row settled is the closest thing to when the contact saw it; the turn's own
            // clock reading is gone with the turn.
            timestampMs = record.updatedAt,
        )
        recordRecovery(record, chatJid)
        return true
    }

    /**
     * The persona the chat was talking to when the message went out.
     *
     * The obvious reading — the persona the chat is assigned *now* — is wrong for exactly the rows
     * this class exists to repair. A sweep reaches back over a month, and a persona switch in the
     * meantime would file a month-old message into a thread that never sent it: the new persona
     * reads "You sent: …" for words it never wrote, and the persona that did write them still has
     * the gap. The conversation key is the one thing the history itself records per message, so the
     * newest message the chat logged at or before the send answers the question without any new
     * bookkeeping on the send path.
     *
     * Falls back to the current assignment when nothing was logged that far back — a first message
     * in a fresh chat has no neighbour, and there the current persona is also the right one.
     */
    private fun personaKeyAt(chatJid: String, sentAt: Long): String {
        val logged =
            runCatching {
                repository.listMessages(
                    chatId = chatJid,
                    // The column is exclusive, and a row stamped exactly at the send is the
                    // strongest evidence there is — most likely the turn's own inbound message.
                    beforeOccurredAt = sentAt + 1,
                    limit = PERSONA_LOOKBACK_ROWS,
                )
            }.getOrDefault(emptyList())
                .map { it.conversationKey }
        return personaKeyFromLoggedConversations(logged)
            ?: settings.snapshot().effectivePersona(chatJid)
    }

    private fun recordRecovery(record: BridgeOutboxRecord, chatJid: String) {
        runCatching {
            repository.appendActivity(
                ActivityLogRecord(
                    occurredAt = now(),
                    level = ActivityLevel.WARN,
                    category = "bridge_outbox",
                    action = "history_recovered",
                    chatId = chatJid,
                    correlationId = record.dedupeKey,
                    summary =
                        "Recovered a delivered message the chat log had missed · ${record.operation}",
                    detailsJson =
                        JSONObject()
                            .put("operation", record.operation)
                            .put("sentAt", record.updatedAt)
                            .toString(),
                ),
            )
            activityChanged()
        }
    }

    private companion object {
        /**
         * How far back a start-up sweep looks. Long enough to cover a phone that was off for a
         * weekend, short enough that the scan stays a single indexed page.
         */
        const val SWEEP_LOOKBACK_MS = 30L * 24L * 60L * 60L * 1_000L
        const val SWEEP_PAGE_ROWS = 500

        /**
         * How many logged messages to look back through for a conversation key. More than one
         * because the rows nearest the send can be legacy rows without a key; small because a chat
         * that logged ten keyless messages in a row has nothing useful further back either.
         */
        const val PERSONA_LOOKBACK_ROWS = 10
    }
}

/**
 * The persona named by the newest conversation key the chat actually logged, or null when none of
 * them names one.
 *
 * [keys] arrives newest first. Rows written before the column existed carry no key at all, and a
 * key without a `#` names no persona — both are skipped rather than answered with, because "this
 * row cannot tell us" is not the same as "there is no persona".
 */
internal fun personaKeyFromLoggedConversations(keys: List<String?>): String? =
    keys.firstNotNullOfOrNull { key ->
        key?.substringAfter('#', missingDelimiterValue = "")?.takeIf(String::isNotBlank)
    }

/**
 * Whether a delivered message still belongs to the chat that stands there now.
 *
 * Deleting a chat cascades its messages away but leaves the outbox ledger untouched, so every send
 * of the last month reads as "delivered but missing from the history" afterwards — and the next
 * sweep faithfully put all of them back, filed under whichever persona was selected by then, while
 * the operator's own messages stayed deleted. That is the whole bug: a chat deleted under one
 * persona reappeared, half of it, in another.
 *
 * A chat deleted and later reopened by a new message is a new row with a new `ChatRecord.createdAt`,
 * and that timestamp is written on insert only. Anything sent before it belonged to the conversation
 * that was thrown away.
 */
internal fun belongsToCurrentChat(sentAt: Long, chatCreatedAt: Long): Boolean =
    sentAt >= chatCreatedAt

/** Only sends leave a message behind. Reactions, edits and blocks revise or touch nothing. */
internal val RECOVERABLE_OPERATIONS = setOf("send_text", "send_media")

/**
 * True for a command answer, which is deliberately kept out of the AI history — the bot did not say
 * it, the admin console did.
 *
 * Those reservation IDs (`admin:<eventId>:<index>`, `admin-image:<requestId>`) stay well inside the
 * length at which [toBridgeRequestId] keeps the key verbatim, so the prefix survives into the
 * dedupe key. A key long enough to have been hashed cannot be one of them.
 */
internal fun isAdminOutboxKey(dedupeKey: String): Boolean =
    dedupeKey.startsWith("admin:") || dedupeKey.startsWith("admin-image:")

/** The transport message ID the companion returned, or null when the row carries no send result. */
internal fun outboxTransportMessageId(resultJson: String?): String? =
    resultJson
        ?.takeIf(String::isNotBlank)
        ?.let(::JSONObject)
        ?.optJSONObject("message")
        ?.optString("id")
        ?.takeIf(String::isNotBlank)

/**
 * The history line the turn would have written, rebuilt from the payload it actually sent.
 *
 * It has to read exactly like the live path's line, because the model sees no difference between a
 * recovered message and one that was recorded the moment it went out.
 */
internal fun outboxHistoryLine(operation: String, payload: JSONObject): String? =
    when (operation) {
        "send_text" ->
            payload.optString("text").takeIf(String::isNotBlank)?.let { text ->
                ChatHistoryLabels.outgoingText(
                    text,
                    payload.optJSONObject("replyTo")
                        ?.optString("preview")
                        ?.takeIf(String::isNotBlank),
                )
            }

        "send_media" ->
            MediaHistoryLabels.outgoingLine(
                kind =
                    if (payload.optBoolean("ptt")) {
                        MediaKind.AUDIO
                    } else {
                        when (payload.optString("kind")) {
                            "image" -> MediaKind.IMAGE
                            "video" -> MediaKind.VIDEO
                            "audio" -> MediaKind.AUDIO
                            else -> MediaKind.DOCUMENT
                        }
                    },
                detail = payload.optString("caption").takeIf(String::isNotBlank),
            )

        else -> null
    }
