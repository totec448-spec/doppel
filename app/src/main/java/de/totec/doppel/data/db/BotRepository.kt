package de.totec.doppel.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import org.json.JSONObject

/**
 * Blocking, allocation-conscious repository over [BotDatabase].
 *
 * Runtime callers should invoke these methods from their existing IO dispatcher. The repository
 * It owns one lazy maintenance executor, but no timer or polling loop: the thread is created only
 * after a write threshold and sleeps never. Multi-row writes and lease claims use non-exclusive WAL transactions.
 */
class BotRepository private constructor(
    private val helper: BotDatabase,
) : Closeable {
    constructor(context: Context) : this(BotDatabase(context))

    private val writesSinceMaintenance = AtomicInteger(0)
    private val maintenanceLock = Any()
    private val maintenanceScheduled = AtomicBoolean(false)
    private val maintenanceExecutor =
        Executors.newSingleThreadExecutor { work ->
            Thread(work, "bot-db-maintenance").apply { isDaemon = true }
        }

    fun getOrCreateInstallStartedAt(now: Long = System.currentTimeMillis()): Long {
        requireNonNegativeTimestamp(now, "now")
        return helper.writableDatabase.inNonExclusiveTransaction {
            readMetaLong(this, META_INSTALL_STARTED_AT)
                ?.coerceAtLeast(0L)
                ?: now.also {
                    putMeta(this, META_INSTALL_STARTED_AT, it.toString())
                }
        }
    }

    // region Chats

    fun upsertChat(chat: ChatRecord) {
        validateChat(chat)
        val db = helper.writableDatabase
        db.inNonExclusiveTransaction {
            upsertChat(db, chat)
        }
        afterWrite(chat.updatedAt)
    }

    fun getChat(chatId: String): ChatRecord? {
        requireExternalId(chatId, "chatId")
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_CHATS,
            columns = CHAT_COLUMNS,
            selection = "chat_id = ?",
            selectionArgs = arrayOf(chatId),
            mapper = ::readChat,
        )
    }

    /**
     * Stamps the AI disclosure as delivered, and reports whether this call is the one that did it.
     *
     * The `IS NULL` guard is what makes the disclosure at-most-once: it is a single conditional
     * UPDATE rather than a read-then-write, so two turns racing on the same chat cannot both see an
     * unstamped row and both disclose. A false return means someone else already sent it.
     */
    fun markAiDisclosureSent(chatId: String, sentAtMs: Long): Boolean {
        requireExternalId(chatId, "chatId")
        require(sentAtMs >= 0) { "Disclosure timestamp must not be negative" }
        val db = helper.writableDatabase
        var claimed = false
        db.inNonExclusiveTransaction {
            val values = ContentValues(2).apply {
                put("ai_disclosure_sent_at", sentAtMs)
                put("updated_at", sentAtMs)
            }
            claimed = db.update(
                BotDbSchema.TABLE_CHATS,
                values,
                "chat_id = ? AND ai_disclosure_sent_at IS NULL",
                arrayOf(chatId),
            ) > 0
        }
        if (claimed) afterWrite(sentAtMs)
        return claimed
    }

    /**
     * Removes complete local chat rows and everything owned by them through foreign-key cascades.
     *
     * Persona/global memory is intentionally not touched here. It is account-wide knowledge and
     * the operator-facing "delete chat" action promises to remove only the conversation, every
     * chat-memory revision, its schedule and its local overrides. Generic scoped settings are not
     * foreign-keyed, so their chat scopes are deleted explicitly in the same transaction.
     *
     * Multiple ids are accepted because one WhatsApp contact can have a phone-number row and a LID
     * row. Deleting only the row that happened to be visible would let its alias reappear as the
     * same apparent duplicate immediately afterwards.
     */
    fun deleteChats(chatIds: Collection<String>): Int {
        val ids = chatIds.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
        if (ids.isEmpty()) return 0
        ids.forEach { requireExternalId(it, "chatId") }
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.toTypedArray()
        val changed =
            helper.writableDatabase.inNonExclusiveTransaction {
                delete(
                    BotDbSchema.TABLE_SCOPED_SETTINGS,
                    "scope_type = ? AND scope_id IN ($placeholders)",
                    arrayOf(SettingsScopes.CHAT, *args),
                )
                delete(
                    BotDbSchema.TABLE_CHATS,
                    "chat_id IN ($placeholders)",
                    args,
                )
            }
        if (changed > 0) afterWrite(System.currentTimeMillis(), changed)
        return changed
    }

    fun listChats(
        includeArchived: Boolean = false,
        beforeLastMessageAt: Long? = null,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<ChatRecord> {
        val safeLimit = queryLimit(limit)
        val clauses = ArrayList<String>(2)
        val args = ArrayList<String>(1)
        if (!includeArchived) clauses += "archived = 0"
        if (beforeLastMessageAt != null) {
            clauses += "(last_message_at IS NULL OR last_message_at < ?)"
            args += beforeLastMessageAt.toString()
        }
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_CHATS,
            columns = CHAT_COLUMNS,
            selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs = args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            orderBy = "last_message_at DESC, updated_at DESC, chat_id",
            limit = safeLimit.toString(),
            mapper = ::readChat,
        )
    }

    /**
     * Stable identity-only scan for explicit destructive maintenance.
     *
     * Normal UI and model queries stay bounded by [BotDatabaseLimits.MAX_QUERY_LIMIT]. A wipe is
     * different: silently leaving the oldest chats behind is worse than reading one short column,
     * and the caller needs every id to clear its file-backed sent-media markers as well.
     */
    fun listAllChatIdsForMaintenance(): List<String> {
        helper.readableDatabase.rawQuery(
            "SELECT chat_id FROM ${BotDbSchema.TABLE_CHATS} ORDER BY chat_id",
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    /** Full local scan for an explicit operator-entered PN/LID identity resolution. */
    fun listAllChatsForIdentityResolution(): List<ChatRecord> =
        helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_CHATS,
            columns = CHAT_COLUMNS,
            selection = null,
            selectionArgs = null,
            orderBy = "last_message_at DESC, updated_at DESC, chat_id",
            limit = null,
            mapper = ::readChat,
        )

    /**
     * How many chats there are, up to [limit].
     *
     * The counting callers used to page full [ChatRecord]s in and take `.size`, which reads every
     * column of up to a thousand rows and builds a thousand objects to produce one number. The
     * cap is kept because it is part of what the caller shows: a bounded count.
     */
    fun countChats(
        includeArchived: Boolean = false,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): Int =
        countUpTo(
            table = BotDbSchema.TABLE_CHATS,
            selection = if (includeArchived) null else "archived = 0",
            selectionArgs = emptyArray(),
            limit = limit,
        )

    /** How many safety-ledger rows there are, up to [limit]. See [countChats]. */
    fun countOutboundSafety(limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT): Int =
        countUpTo(
            table = BotDbSchema.TABLE_OUTBOUND_SAFETY,
            selection = null,
            selectionArgs = emptyArray(),
            limit = limit,
        )

    /**
     * `COUNT(*)` over a bounded window, so the answer stays a number the caller can trust to be
     * cheap: the inner LIMIT stops the scan instead of counting a table of unknown size.
     */
    private fun countUpTo(
        table: String,
        selection: String?,
        selectionArgs: Array<String>,
        limit: Int,
    ): Int {
        val where = selection?.let { "WHERE $it" } ?: ""
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM (SELECT 1 FROM $table $where LIMIT ?)",
            selectionArgs + queryLimit(limit).toString(),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Persona-scoped chat discovery for AI tools. The query returns only chats
     * with persisted messages in that persona namespace and never exposes a
     * broad unfiltered chat list to the model.
     */
    fun listChatsForPersona(
        personaId: String,
        includeArchived: Boolean = true,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<ChatRecord> {
        requireShortId(personaId, "personaId")
        val safeLimit = queryLimit(limit)
        val personaMarker = "%\"persona\":\"${escapeLike(personaId)}\"%"
        val conversationMarker = "%#${escapeLike(personaId)}\"%"
        val archivedClause = if (includeArchived) "" else "AND c.archived = 0"
        val columns = CHAT_COLUMNS.joinToString(",") { "c.$it" }
        val sql =
            """
            SELECT $columns
            FROM ${BotDbSchema.TABLE_CHATS} c
            WHERE 1 = 1
              $archivedClause
              AND EXISTS (
                SELECT 1
                FROM ${BotDbSchema.TABLE_MESSAGES} m
                WHERE m.chat_id = c.chat_id
                  AND m.metadata_json IS NOT NULL
                  AND (
                    m.metadata_json LIKE ? ESCAPE '\'
                    OR m.metadata_json LIKE ? ESCAPE '\'
                  )
              )
            ORDER BY c.last_message_at DESC, c.chat_id
            LIMIT ?
            """.trimIndent()
        helper.readableDatabase.rawQuery(
            sql,
            arrayOf(personaMarker, conversationMarker, safeLimit.toString()),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(readChat(cursor))
            }
        }
    }

    // endregion

    // region Event dedupe and messages

    /**
     * Atomically claims an external event. False means another delivery already claimed it.
     */
    fun claimEvent(event: ProcessedEventRecord): Boolean {
        validateEvent(event)
        val changed = helper.writableDatabase.inNonExclusiveTransaction {
            insertProcessedEvent(this, event)
        }
        if (changed > 0) afterWrite(event.receivedAt)
        return changed > 0
    }

    fun getEventDisposition(eventId: String): String? {
        requireExternalId(eventId, "eventId")
        helper.readableDatabase.rawQuery(
            """
            SELECT disposition
            FROM ${BotDbSchema.TABLE_PROCESSED_EVENTS}
            WHERE event_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(eventId),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getString(0)
        }
    }

    fun completeEvent(
        eventId: String,
        disposition: String,
        completedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        requireExternalId(eventId, "eventId")
        requireLength(disposition, 1, 64, "disposition")
        requireNonNegativeTimestamp(completedAt, "completedAt")
        val changed =
            helper.writableDatabase.update(
                BotDbSchema.TABLE_PROCESSED_EVENTS,
                ContentValues(1).apply { put("disposition", disposition) },
                "event_id = ? AND disposition IS NULL",
                arrayOf(eventId),
            )
        if (changed > 0) afterWrite(completedAt)
        return changed > 0
    }

    /**
     * Atomically publishes an accepted conversation row while leaving its event pending until the
     * engine has either sent or deliberately stayed silent. A provider-ID alias owned by another
     * event is terminally completed as a duplicate.
     */
    fun storeAcceptedConversation(
        eventId: String,
        message: MessageRecord,
    ): Boolean {
        requireExternalId(eventId, "eventId")
        require(message.eventId == eventId) { "Message event does not match claimed event" }
        validateMessage(message)
        val result =
            helper.writableDatabase.inNonExclusiveTransaction {
                val inserted = insertMessages(this, listOf(message))
                if (inserted == 1) {
                    true
                } else {
                    val belongsToThisEvent =
                        rawQuery(
                            "SELECT event_id FROM ${BotDbSchema.TABLE_MESSAGES} " +
                                "WHERE provider_message_id = ? LIMIT 1",
                            arrayOf(message.providerMessageId),
                        ).use { cursor ->
                            cursor.moveToFirst() && cursor.stringOrNull(0) == eventId
                        }
                    if (belongsToThisEvent) {
                        // Re-entry after a crash between message persistence and turn completion.
                        true
                    } else {
                        update(
                            BotDbSchema.TABLE_PROCESSED_EVENTS,
                            ContentValues(1).apply { put("disposition", "duplicate_provider") },
                            "event_id = ? AND disposition IS NULL",
                            arrayOf(eventId),
                        )
                        false
                    }
                }
            }
        afterWrite(message.receivedAt)
        return result
    }

    /**
     * Commits event claims and their normalized messages in one WAL transaction. Both tables use
     * INSERT OR IGNORE, so redelivery after a crash is idempotent at event and message level.
     */
    fun storeMessages(messages: Collection<MessageRecord>): Int {
        if (messages.isEmpty()) return 0
        messages.forEach(::validateMessage)
        val db = helper.writableDatabase
        val inserted = db.inNonExclusiveTransaction {
            insertMessages(db, messages)
        }
        if (inserted > 0) {
            afterWrite(messages.maxOf { it.receivedAt }, inserted)
        }
        return inserted
    }

    fun getMessage(providerMessageId: String): MessageRecord? {
        requireExternalId(providerMessageId, "providerMessageId")
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_MESSAGES,
            columns = MESSAGE_COLUMNS,
            selection = "provider_message_id = ?",
            selectionArgs = arrayOf(providerMessageId),
            mapper = ::readMessage,
        )
    }

    /** Updates a durable local context marker without moving its chronological anchor. */
    fun updateLocalContextMarker(
        providerMessageId: String,
        body: String,
        metadataJson: String?,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        requireExternalId(providerMessageId, "providerMessageId")
        require(body.isNotBlank() && body.length <= MAX_MESSAGE_CHARS)
        val changed =
            helper.writableDatabase.update(
                BotDbSchema.TABLE_MESSAGES,
                ContentValues(3).apply {
                    put("body", body)
                    putNullable("metadata_json", metadataJson)
                    put("received_at", updatedAt)
                },
                "provider_message_id = ? AND direction = ? AND message_type = ?",
                arrayOf(
                    providerMessageId,
                    MessageDirection.SYSTEM.databaseValue,
                    SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                ),
            )
        if (changed > 0) afterWrite(updatedAt)
        return changed > 0
    }

    /**
     * Stable keyset paging; callers pass both fields from the final item of the prior page.
     */
    fun listMessages(
        chatId: String,
        beforeOccurredAt: Long? = null,
        beforeDatabaseId: Long? = null,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<MessageRecord> {
        requireExternalId(chatId, "chatId")
        val safeLimit = queryLimit(limit)
        val selection: String
        val args: Array<String>
        if (beforeOccurredAt == null) {
            selection = "chat_id = ?"
            args = arrayOf(chatId)
        } else {
            selection =
                "chat_id = ? AND (occurred_at < ? OR (occurred_at = ? AND id < ?))"
            args = arrayOf(
                chatId,
                beforeOccurredAt.toString(),
                beforeOccurredAt.toString(),
                (beforeDatabaseId ?: Long.MAX_VALUE).toString(),
            )
        }
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_MESSAGES,
            columns = MESSAGE_COLUMNS,
            selection = selection,
            selectionArgs = args,
            orderBy = "occurred_at DESC, id DESC",
            limit = safeLimit.toString(),
            mapper = ::readMessage,
        )
    }

    /** Indexed persona-scoped paging; avoids scanning and JSON-parsing the physical chat. */
    fun listConversationMessages(
        conversationKey: String,
        beforeOccurredAt: Long? = null,
        beforeDatabaseId: Long? = null,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<MessageRecord> {
        requireConversationKey(conversationKey)
        val safeLimit = queryLimit(limit)
        val selection: String
        val args: Array<String>
        if (beforeOccurredAt == null) {
            selection = "conversation_key = ?"
            args = arrayOf(conversationKey)
        } else {
            selection =
                "conversation_key = ? AND (occurred_at < ? OR (occurred_at = ? AND id < ?))"
            args =
                arrayOf(
                    conversationKey,
                    beforeOccurredAt.toString(),
                    beforeOccurredAt.toString(),
                    (beforeDatabaseId ?: Long.MAX_VALUE).toString(),
                )
        }
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_MESSAGES,
            columns = MESSAGE_COLUMNS,
            selection = selection,
            selectionArgs = args,
            orderBy = "occurred_at DESC, id DESC",
            limit = safeLimit.toString(),
            mapper = ::readMessage,
        )
    }

    fun listMessagesByType(
        chatId: String,
        messageType: String,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<MessageRecord> {
        requireExternalId(chatId, "chatId")
        requireLength(messageType, 1, 64, "messageType")
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_MESSAGES,
            columns = MESSAGE_COLUMNS,
            selection = "chat_id = ? AND message_type = ?",
            selectionArgs = arrayOf(chatId, messageType),
            orderBy = "occurred_at DESC, id DESC",
            limit = queryLimit(limit).toString(),
            mapper = ::readMessage,
        )
    }

    /**
     * Counts at most [limit] non-system rows newer than a durable message marker.
     *
     * This is an inexpensive cadence precheck for memory consolidation. A null
     * result means the marker is no longer in the bounded history and the caller
     * must perform its full defensive scan. The existing chat/time and unique
     * provider-message indexes cover both lookups.
     */
    fun countMessagesAfterMarkerAtMost(
        chatId: String,
        providerMessageId: String?,
        limit: Int,
        conversationKey: String? = null,
    ): Int? {
        requireExternalId(chatId, "chatId")
        conversationKey?.let { requireConversationKey(it) }
        val safeLimit = queryLimit(limit)
        val marker =
            providerMessageId?.let { markerId ->
                requireExternalId(markerId, "providerMessageId")
                helper.readableDatabase.rawQuery(
                    """
                    SELECT occurred_at, id
                    FROM ${BotDbSchema.TABLE_MESSAGES}
                    WHERE chat_id = ? AND provider_message_id = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(chatId, markerId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    cursor.getLong(0) to cursor.getLong(1)
                }
            }
        val markerClause =
            if (marker == null) {
                ""
            } else {
                "AND (occurred_at > ? OR (occurred_at = ? AND id > ?))"
            }
        // Another persona's messages in the same physical chat are not this conversation's
        // backlog. Without this the gate opened on them — most visibly right after a chat was
        // cleared, when the marker is gone and every row in the chat counts as new. A null key
        // still counts: those are the ambiguous legacy rows the defensive scan has to look at
        // anyway, and excluding them here could stall consolidation for good.
        val conversationClause =
            if (conversationKey == null) {
                ""
            } else {
                "AND (conversation_key = ? OR conversation_key IS NULL)"
            }
        val args =
            buildList {
                add(chatId)
                if (conversationKey != null) add(conversationKey)
                if (marker != null) {
                    add(marker.first.toString())
                    add(marker.first.toString())
                    add(marker.second.toString())
                }
                add(safeLimit.toString())
            }.toTypedArray()
        helper.readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM (
                SELECT 1
                FROM ${BotDbSchema.TABLE_MESSAGES}
                WHERE chat_id = ? AND direction != 'system' $conversationClause $markerClause
                ORDER BY occurred_at ASC, id ASC
                LIMIT ?
            )
            """.trimIndent(),
            args,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Returns the newest persisted message for every requested chat in one database round trip.
     *
     * The chat list used to call [listMessages] once per row, turning one refresh into as many as
     * 201 SQLite queries. The correlated lookup stays on the existing `(chat_id, occurred_at, id)`
     * index, while the outer query and cursor are opened only once.
     */
    fun listLatestMessagePreviews(
        chatIds: Collection<String>,
        includeSystem: Boolean = true,
    ): Map<String, LatestMessagePreview> {
        val ids = chatIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        require(ids.size <= BotDatabaseLimits.MAX_QUERY_LIMIT) { "Too many chat ids" }
        ids.forEach { requireExternalId(it, "chatId") }
        val placeholders = ids.joinToString(",") { "?" }
        val visiblePredicate = if (includeSystem) "" else "AND newest.direction != 'system'"
        val sql =
            """
            SELECT m.chat_id, m.direction, m.message_type,
                   substr(m.body, 1, $MAX_ROSTER_PREVIEW_CHARS), m.occurred_at
            FROM ${BotDbSchema.TABLE_MESSAGES} m
            WHERE m.chat_id IN ($placeholders)
              AND m.id = (
                SELECT newest.id
                FROM ${BotDbSchema.TABLE_MESSAGES} newest
                WHERE newest.chat_id = m.chat_id
                  $visiblePredicate
                ORDER BY newest.occurred_at DESC, newest.id DESC
                LIMIT 1
              )
            """.trimIndent()
        helper.readableDatabase.rawQuery(sql, ids.toTypedArray()).use { cursor ->
            return buildMap(ids.size) {
                while (cursor.moveToNext()) {
                    val preview =
                        LatestMessagePreview(
                            chatId = cursor.getString(0),
                            direction = MessageDirection.fromDatabase(cursor.getString(1)),
                            messageType = cursor.getString(2),
                            body = cursor.stringOrNull(3),
                            occurredAt = cursor.getLong(4),
                        )
                    put(preview.chatId, preview)
                }
            }
        }
    }

    /** Applies one WhatsApp receipt stanza with one SQL UPDATE and one change notification. */
    fun updateMessageDelivery(
        providerMessageIds: Collection<String>,
        deliveryState: MessageDeliveryState,
    ): Int {
        val ids = providerMessageIds.distinct()
        if (ids.isEmpty()) return 0
        require(ids.size <= 256) { "Too many message ids" }
        ids.forEach { requireExternalId(it, "providerMessageId") }
        val placeholders = ids.joinToString(",") { "?" }
        val replaceableStates = replaceableDeliveryStates(deliveryState)
        val statePlaceholders = replaceableStates.joinToString(",") { "?" }
        var changed = helper.writableDatabase.update(
            BotDbSchema.TABLE_MESSAGES,
            ContentValues(1).apply {
                put("delivery_state", deliveryState.databaseValue)
            },
            "provider_message_id IN ($placeholders) AND delivery_state IN ($statePlaceholders)",
            (ids + replaceableStates).toTypedArray(),
        )
        changed += applyDeliveryWatermark(ids, deliveryState, replaceableStates)
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed
    }

    /**
     * Carries a receipt back over the bubbles it silently covers.
     *
     * A WhatsApp receipt is a watermark, not a list: reading one message means everything sent
     * before it in that chat has been read too, and the server usually only names the newest id.
     * Applying it literally left the upper half of every multi-bubble reply sitting on "Unread"
     * while the operator was demonstrably looking at the chat.
     *
     * Bounded on purpose: the [MAX_WATERMARK_CASCADE] rows nearest the mark that are still behind
     * it, which is a multi-bubble burst and its follow-ups. Normally there are one or two, because
     * everything further back already carries its final state — the limit is there for the case
     * where it does not, so one receipt after a restore cannot walk back through a whole imported
     * history. No extra WhatsApp traffic is involved: this is the push we already received, read
     * the way a real client reads it.
     */
    private fun applyDeliveryWatermark(
        providerMessageIds: List<String>,
        deliveryState: MessageDeliveryState,
        replaceableStates: List<String>,
    ): Int {
        // Only the states that mean "the other side has seen this far" cascade. A send confirmation
        // or a failure says nothing at all about the bubble above it.
        if (deliveryState != MessageDeliveryState.READ && deliveryState != MessageDeliveryState.DELIVERED) {
            return 0
        }
        val marks = deliveryWatermarks(providerMessageIds)
        if (marks.isEmpty()) return 0
        // Strictly below the new state: a watermark only ever moves a row forward, and rewriting
        // rows that already hold the value would both cost writes and pad the returned count.
        val liftableStates = replaceableStates - deliveryState.databaseValue
        if (liftableStates.isEmpty()) return 0
        val statePlaceholders = liftableStates.joinToString(",") { "?" }
        val values =
            ContentValues(1).apply { put("delivery_state", deliveryState.databaseValue) }
        return marks.entries.sumOf { (chatId, highestRowId) ->
            helper.writableDatabase.update(
                BotDbSchema.TABLE_MESSAGES,
                values,
                """
                id IN (
                    SELECT id FROM ${BotDbSchema.TABLE_MESSAGES}
                    WHERE chat_id = ?
                      AND direction = ?
                      AND id < ?
                      AND delivery_state IN ($statePlaceholders)
                    ORDER BY id DESC
                    LIMIT $MAX_WATERMARK_CASCADE
                )
                """.trimIndent(),
                (
                    listOf(chatId, MessageDirection.OUTBOUND.databaseValue, highestRowId.toString()) +
                        liftableStates
                ).toTypedArray(),
            )
        }
    }

    /** Per chat, the newest outbound row the receipt actually named. */
    private fun deliveryWatermarks(providerMessageIds: List<String>): Map<String, Long> {
        val placeholders = providerMessageIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT chat_id, MAX(id)
            FROM ${BotDbSchema.TABLE_MESSAGES}
            WHERE provider_message_id IN ($placeholders) AND direction = ?
            GROUP BY chat_id
            """.trimIndent()
        val arguments =
            (providerMessageIds + MessageDirection.OUTBOUND.databaseValue).toTypedArray()
        helper.readableDatabase.rawQuery(sql, arguments).use { cursor ->
            return buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getLong(1))
                }
            }
        }
    }

    private fun replaceableDeliveryStates(deliveryState: MessageDeliveryState): List<String> =
        when (deliveryState) {
            MessageDeliveryState.SENT -> listOf("queued", "sent", "unknown")
            MessageDeliveryState.DELIVERED -> listOf("queued", "sent", "delivered", "unknown")
            MessageDeliveryState.READ -> listOf("queued", "sent", "delivered", "read", "unknown")
            MessageDeliveryState.FAILED -> listOf("queued", "sent", "failed", "unknown")
            else -> listOf(deliveryState.databaseValue)
        }

    /**
     * Replace only a message's body, keeping its type and metadata.
     *
     * Used to write a media analysis (voice transcript, image description) onto
     * the row it belongs to. Media arrives with no body of its own, and history
     * skips bodyless rows, so without this a voice note would be invisible to
     * every later turn — the analysis would have to be paid for again, or, worse,
     * the request inside it would silently never reach the model.
     */
    fun updateMessageBody(
        providerMessageId: String,
        body: String?,
    ): Boolean {
        requireExternalId(providerMessageId, "providerMessageId")
        requireOptionalLength(body, MAX_MESSAGE_CHARS, "body")
        val changed =
            helper.writableDatabase.update(
                BotDbSchema.TABLE_MESSAGES,
                ContentValues(1).apply { putNullable("body", body) },
                "provider_message_id = ?",
                arrayOf(providerMessageId),
            )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    fun deleteMessage(providerMessageId: String): Boolean {
        requireExternalId(providerMessageId, "providerMessageId")
        val changed = helper.writableDatabase.delete(
            BotDbSchema.TABLE_MESSAGES,
            "provider_message_id = ?",
            arrayOf(providerMessageId),
        )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    /** Deletes a bounded imported-history batch without one transaction and invalidation per row. */
    fun deleteMessages(providerMessageIds: Collection<String>): Int {
        if (providerMessageIds.isEmpty()) return 0
        providerMessageIds.forEach { requireExternalId(it, "providerMessageId") }
        val db = helper.writableDatabase
        val deleted =
            db.inNonExclusiveTransaction {
                providerMessageIds.chunked(MAX_SQL_VARIABLES).sumOf { chunk ->
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    delete(
                        BotDbSchema.TABLE_MESSAGES,
                        "provider_message_id IN ($placeholders)",
                        chunk.toTypedArray(),
                    )
                }
            }
        if (deleted > 0) afterWrite(System.currentTimeMillis())
        return deleted
    }

    /** Deletes only a locally authenticated operator-context row in its owning conversation. */
    fun deleteInjection(databaseId: Long, conversationKey: String): Boolean {
        require(databaseId > 0) { "databaseId must be positive" }
        requireConversationKey(conversationKey)
        val changed =
            helper.writableDatabase.delete(
                BotDbSchema.TABLE_MESSAGES,
                "id = ? AND conversation_key = ? AND message_type = ? AND from_admin = 1",
                arrayOf(
                    databaseId.toString(),
                    conversationKey,
                    CHAT_INJECTION_MESSAGE_TYPE,
                ),
            )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    fun trimConversationMessages(
        conversationKey: String,
        keepNewest: Int,
        now: Long = System.currentTimeMillis(),
    ): Int {
        requireConversationKey(conversationKey)
        require(keepNewest in 1..BotDatabaseLimits.MAX_MESSAGES_PER_CHAT) {
            "keepNewest is outside the bounded conversation retention range"
        }
        val deleted =
            helper.writableDatabase.executeChangedRows(
                """
                DELETE FROM ${BotDbSchema.TABLE_MESSAGES}
                WHERE conversation_key = ?
                  AND message_type != ?
                  AND id NOT IN (
                      SELECT id
                      FROM ${BotDbSchema.TABLE_MESSAGES}
                      WHERE conversation_key = ?
                        AND message_type != ?
                      ORDER BY occurred_at DESC, id DESC
                      LIMIT ?
                  )
                """.trimIndent(),
                listOf(
                    conversationKey,
                    SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                    conversationKey,
                    SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                    keepNewest,
                ),
            )
        if (deleted > 0) afterWrite(now, deleted)
        return deleted
    }

    /**
     * Keeps the newest [keepNewest] follow-up markers of a conversation and drops the rest.
     *
     * Markers are exempt from every other retention path on purpose: they are the only record of a
     * plan the model made about this chat, and they have to survive a chat that scrolls past them.
     * Exempt is not the same as unbounded, though, so the tail is cut here — far enough back that
     * the cut only ever touches rows the prompt window left behind long ago. Deleting a row
     * rewrites the prompt from that row onwards, and the whole point of a marker is that it never
     * moves.
     */
    fun trimFollowUpMarkers(
        conversationKey: String,
        keepNewest: Int,
        now: Long = System.currentTimeMillis(),
    ): Int {
        requireConversationKey(conversationKey)
        require(keepNewest in 1..BotDatabaseLimits.MAX_MESSAGES_PER_CHAT) {
            "keepNewest must keep at least the current plan and stay inside chat retention"
        }
        val deleted =
            helper.writableDatabase.executeChangedRows(
                """
                DELETE FROM ${BotDbSchema.TABLE_MESSAGES}
                WHERE conversation_key = ?
                  AND message_type = ?
                  AND id NOT IN (
                      SELECT id
                      FROM ${BotDbSchema.TABLE_MESSAGES}
                      WHERE conversation_key = ?
                        AND message_type = ?
                      ORDER BY occurred_at DESC, id DESC
                      LIMIT ?
                  )
                """.trimIndent(),
                listOf(
                    conversationKey,
                    SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                    conversationKey,
                    SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                    keepNewest,
                ),
            )
        if (deleted > 0) afterWrite(now, deleted)
        return deleted
    }

    // endregion

    // region Memory and personas

    /**
     * Writes a synthesized memory only if the source revision is still current.
     * This closes the model-latency window in which a manual edit could otherwise
     * be overwritten by an older automatic refresh.
     */
    fun compareAndSwapChatMemory(
        expectedRevision: Long,
        memory: ChatMemoryRecord,
    ): Boolean {
        require(expectedRevision >= 0L) { "expectedRevision must not be negative" }
        require(memory.revision == expectedRevision + 1L) { "memory revision is not monotonic" }
        requireExternalId(memory.chatId, "chatId")
        requireConversationKey(memory.conversationKey)
        require(memory.conversationKey.substringBeforeLast('#') == memory.chatId) {
            "conversationKey does not belong to chatId"
        }
        require(memory.summary.length <= MAX_MEMORY_CHARS) { "Chat memory summary is too large" }
        requireJsonSize(memory.factsJson, MAX_MEMORY_CHARS, "factsJson")
        val changed =
            helper.writableDatabase.inNonExclusiveTransaction {
                ensureChatStub(this, memory.chatId, memory.updatedAt)
                val values = chatMemoryValues(memory)
                val rowChanged = if (expectedRevision == 0L) {
                    insertWithOnConflict(
                        BotDbSchema.TABLE_CHAT_MEMORY,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ).let { if (it == -1L) 0 else 1 }
                } else {
                    update(
                        BotDbSchema.TABLE_CHAT_MEMORY,
                        values,
                        "conversation_key = ? AND revision = ?",
                        arrayOf(memory.conversationKey, expectedRevision.toString()),
                    )
                }
                if (rowChanged > 0) archiveChatMemory(this, memory)
                rowChanged
            }
        if (changed > 0) afterWrite(memory.updatedAt)
        return changed > 0
    }

    /**
     * Moves a conversation's consolidation pointer forward without writing a memory.
     *
     * This is what "chat memory off" does at the interval. The pointer is not the text — it is
     * where the rendered history window is allowed to start — and a window that never advanced
     * would grow without limit and re-bill the whole conversation on every turn. So the revision
     * and the marker move exactly as a real write moves them, while the summary that is already
     * stored is left untouched: switching memory off must not delete what was remembered before.
     *
     * Deliberately not archived. A revision that adds no text is not a new version of the document,
     * and archiving it would post a duplicate memory card in the transcript every interval — the
     * one thing the operator turned this off to stop seeing.
     */
    fun advanceChatMemoryWindow(
        expectedRevision: Long,
        conversationKey: String,
        chatId: String,
        lastProviderMessageId: String?,
        updatedAt: Long,
    ): Boolean {
        require(expectedRevision >= 0L) { "expectedRevision must not be negative" }
        requireExternalId(chatId, "chatId")
        requireConversationKey(conversationKey)
        require(conversationKey.substringBeforeLast('#') == chatId) {
            "conversationKey does not belong to chatId"
        }
        val changed =
            helper.writableDatabase.inNonExclusiveTransaction {
                ensureChatStub(this, chatId, updatedAt)
                if (expectedRevision == 0L) {
                    insertWithOnConflict(
                        BotDbSchema.TABLE_CHAT_MEMORY,
                        null,
                        chatMemoryValues(
                            ChatMemoryRecord(
                                chatId = chatId,
                                conversationKey = conversationKey,
                                summary = "",
                                lastProviderMessageId = lastProviderMessageId,
                                sourceMessageCount = 0,
                                revision = 1L,
                                updatedAt = updatedAt,
                            ),
                        ),
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ).let { if (it == -1L) 0 else 1 }
                } else {
                    update(
                        BotDbSchema.TABLE_CHAT_MEMORY,
                        ContentValues(3).apply {
                            putNullable("last_provider_message_id", lastProviderMessageId)
                            put("revision", expectedRevision + 1L)
                            put("updated_at", updatedAt)
                        },
                        "conversation_key = ? AND revision = ?",
                        arrayOf(conversationKey, expectedRevision.toString()),
                    )
                }
            }
        if (changed > 0) afterWrite(updatedAt)
        return changed > 0
    }

    fun getChatMemory(conversationKey: String): ChatMemoryRecord? {
        requireConversationKey(conversationKey)
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_CHAT_MEMORY,
            columns = CHAT_MEMORY_COLUMNS,
            selection = "conversation_key = ?",
            selectionArgs = arrayOf(conversationKey),
            mapper = ::readChatMemory,
        )
    }

    /** Recent revisions in timeline order; the current revision is included. */
    fun listChatMemoryHistory(
        conversationKey: String,
        limit: Int = MAX_MEMORY_HISTORY_REVISIONS,
    ): List<ChatMemoryRecord> {
        requireConversationKey(conversationKey)
        return helper.readableDatabase
            .queryList(
                table = BotDbSchema.TABLE_CHAT_MEMORY_HISTORY,
                columns = CHAT_MEMORY_COLUMNS,
                selection = "conversation_key = ?",
                selectionArgs = arrayOf(conversationKey),
                orderBy = "revision DESC",
                limit = limit.coerceIn(1, MAX_MEMORY_HISTORY_REVISIONS).toString(),
                mapper = ::readChatMemory,
            ).asReversed()
    }

    /**
     * Catalogue of every stored memory document, newest write first.
     *
     * The projection deliberately never selects the full summary: a document may hold up to a
     * megabyte and the browser only needs a headline. The caller loads the body separately, for the
     * one entry that was actually opened.
     *
     * A chat memory is only a document while there is something to read and a chat to read it
     * against. [clearChatMemorySummary] keeps the row on purpose — it is the consolidation pointer,
     * not the text — but an emptied memory listed as "0 characters" is an entry that opens onto
     * nothing, and a row whose chat has since been deleted names a conversation that is not in the
     * app any more. Both are storage bookkeeping, so both stay out of the browser rather than being
     * deleted for the sake of the view.
     */
    fun listMemoryDocuments(limit: Int = 200): List<MemoryDocumentSummary> {
        val bounded = limit.coerceIn(1, 500)
        helper.readableDatabase.rawQuery(
            """
            SELECT 'chat' AS scope,
                   conversation_key AS id,
                   chat_id AS owner,
                   length(summary) AS chars,
                   substr(summary, 1, 200) AS preview,
                   facts_json IS NOT NULL AS has_facts,
                   source_message_count,
                   revision,
                   updated_at
            FROM ${BotDbSchema.TABLE_CHAT_MEMORY} AS memory
            WHERE (length(memory.summary) > 0 OR memory.facts_json IS NOT NULL)
              AND EXISTS (
                  SELECT 1
                  FROM ${BotDbSchema.TABLE_CHATS} AS chat
                  WHERE chat.chat_id = memory.chat_id
              )
            UNION ALL
            SELECT 'persona' AS scope,
                   persona_id AS id,
                   persona_id AS owner,
                   length(summary) AS chars,
                   substr(summary, 1, 200) AS preview,
                   facts_json IS NOT NULL AS has_facts,
                   0 AS source_message_count,
                   revision,
                   updated_at
            FROM ${BotDbSchema.TABLE_PERSONA_MEMORY}
            ORDER BY updated_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(bounded.toString()),
        ).use { cursor ->
            return buildList(cursor.count.coerceAtLeast(0)) {
                while (cursor.moveToNext()) {
                    add(
                        MemoryDocumentSummary(
                            scope = cursor.getString(0),
                            id = cursor.getString(1),
                            owner = cursor.getString(2),
                            characters = cursor.getInt(3),
                            preview = cursor.getString(4),
                            hasFacts = cursor.getInt(5) != 0,
                            sourceMessageCount = cursor.getInt(6),
                            revision = cursor.getLong(7),
                            updatedAt = cursor.getLong(8),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Every chat memory owned by one persona, newest write first, with the summary bodies.
     *
     * This is what the persona's global memory is synthesised from, so it must select the text and
     * not just a headline. Both bounds are therefore mandatory: [limit] caps how many chats are
     * folded in and [maxSummaryChars] caps each one, so a single very long memory cannot blow up
     * the request. Ownership is decided by the `#persona` suffix of the conversation key, matched
     * with LIKE — the persona id is user-supplied, so its wildcards are escaped.
     *
     * Rows with no text are left out, and filtered in the query rather than afterwards so they do
     * not consume the [limit]. A chat memory row outlives its summary on purpose — it is also the
     * consolidation pointer, which is all that is left of it once the memory was cleared by hand or
     * while chat memory is switched off — but an empty one contributes nothing to a synthesis
     * except a chat heading with silence under it.
     */
    fun listChatMemoriesForPersona(
        personaId: String,
        limit: Int = 12,
        maxSummaryChars: Int = 12_000,
    ): List<PersonaChatMemoryRow> {
        requireShortId(personaId, "personaId")
        val boundedLimit = limit.coerceIn(1, 100)
        val boundedChars = maxSummaryChars.coerceIn(200, MAX_MEMORY_CHARS)
        helper.readableDatabase.rawQuery(
            """
            SELECT conversation_key, chat_id, substr(summary, 1, ?), revision, updated_at
            FROM ${BotDbSchema.TABLE_CHAT_MEMORY}
            WHERE persona_id = ? AND length(trim(summary)) > 0
            ORDER BY updated_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(boundedChars.toString(), personaId, boundedLimit.toString()),
        ).use { cursor ->
            return buildList(cursor.count.coerceAtLeast(0)) {
                while (cursor.moveToNext()) {
                    add(
                        PersonaChatMemoryRow(
                            conversationKey = cursor.getString(0),
                            chatId = cursor.getString(1),
                            summary = cursor.getString(2).orEmpty(),
                            revision = cursor.getLong(3),
                            updatedAt = cursor.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    /** Durable cadence count over every chat memory, independent of synthesis payload limits. */
    fun sumChatMemoryRevisionsForPersona(personaId: String): Long {
        requireShortId(personaId, "personaId")
        helper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(revision), 0) FROM ${BotDbSchema.TABLE_CHAT_MEMORY} " +
                "WHERE persona_id = ?",
            arrayOf(personaId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    fun deleteChatMemory(chatId: String): Boolean {
        requireExternalId(chatId, "chatId")
        val db = helper.writableDatabase
        val changed =
            db.inNonExclusiveTransaction {
                delete(
                    BotDbSchema.TABLE_CHAT_MEMORY_HISTORY,
                    "chat_id = ?",
                    arrayOf(chatId),
                ) + delete(
                    BotDbSchema.TABLE_CHAT_MEMORY,
                    "chat_id = ?",
                    arrayOf(chatId),
                )
            }
        if (changed > 0) afterWrite(System.currentTimeMillis(), changed)
        return changed > 0
    }

    /**
     * Erases the text of one chat memory and leaves the row — and with it the consolidation
     * pointer — exactly where it was.
     *
     * Deliberately not [deleteChatMemory]. `last_provider_message_id` is what records which
     * messages have already been condensed, and the anchored history window is pinned to
     * `revision`; dropping the row rewinds both, so "forget what you wrote down" would silently
     * also mean "read the whole chat again and pay for it again". The archived revisions do go,
     * or the text the user just deleted would still be sitting on disk.
     */
    fun clearChatMemorySummary(conversationKey: String): Boolean {
        requireConversationKey(conversationKey)
        val now = System.currentTimeMillis()
        val changed =
            helper.writableDatabase.inNonExclusiveTransaction {
                delete(
                    BotDbSchema.TABLE_CHAT_MEMORY_HISTORY,
                    "conversation_key = ?",
                    arrayOf(conversationKey),
                )
                update(
                    BotDbSchema.TABLE_CHAT_MEMORY,
                    ContentValues(4).apply {
                        put("summary", "")
                        putNull("facts_json")
                        // The count described the summary that just went; carrying it would leave
                        // an empty file claiming to have condensed four hundred messages.
                        put("source_message_count", 0)
                        put("updated_at", now)
                    },
                    "conversation_key = ?",
                    arrayOf(conversationKey),
                )
            }
        if (changed > 0) afterWrite(now)
        return changed > 0
    }

    /** Removes only visible transcript rows for one exact `chat#persona` owner. */
    fun deleteConversationHistory(conversationKey: String): Int {
        requireConversationKey(conversationKey)
        val changed =
            helper.writableDatabase.delete(
                BotDbSchema.TABLE_MESSAGES,
                "conversation_key = ?",
                arrayOf(conversationKey),
            )
        if (changed > 0) afterWrite(System.currentTimeMillis(), changed)
        return changed
    }

    /**
     * Removes only rows attributable to one `chat#persona` conversation plus its current and
     * archived memory. Rows without indexed ownership are deliberately preserved: guessing would
     * let deleting one persona erase the same contact's history for every other persona.
     *
     * One indexed delete replaces the former full-chat metadata scan.
     */
    fun deleteConversationData(conversationKey: String): Int {
        requireConversationKey(conversationKey)
        val db = helper.writableDatabase
        val changed =
            db.inNonExclusiveTransaction {
                var deleted =
                    delete(
                        BotDbSchema.TABLE_MESSAGES,
                        "conversation_key = ?",
                        arrayOf(conversationKey),
                    )
                deleted +=
                    delete(
                        BotDbSchema.TABLE_CHAT_MEMORY_HISTORY,
                        "conversation_key = ?",
                        arrayOf(conversationKey),
                    )
                deleted +=
                    delete(
                        BotDbSchema.TABLE_CHAT_MEMORY,
                        "conversation_key = ?",
                        arrayOf(conversationKey),
                    )
                deleted
            }
        if (changed > 0) afterWrite(System.currentTimeMillis(), changed)
        return changed
    }

    /**
     * Returns the bounded set of persona owners that still have transcript or memory data.
     * This is opened only from the local destructive-data panel; it performs no WhatsApp work.
     */
    fun listPersonaIdsWithConversationData(
        limit: Int = BotDatabaseLimits.MAX_QUERY_LIMIT,
    ): List<String> =
        helper.readableDatabase.rawQuery(
            """
            SELECT persona_id
            FROM (
                SELECT DISTINCT substr(conversation_key, instr(conversation_key, '#') + 1) AS persona_id
                FROM ${BotDbSchema.TABLE_MESSAGES}
                WHERE instr(conversation_key, '#') > 0
                UNION
                SELECT DISTINCT persona_id FROM ${BotDbSchema.TABLE_CHAT_MEMORY}
                UNION
                SELECT DISTINCT persona_id FROM ${BotDbSchema.TABLE_CHAT_MEMORY_HISTORY}
                UNION
                SELECT DISTINCT persona_id FROM ${BotDbSchema.TABLE_PERSONA_MEMORY}
            )
            WHERE persona_id IS NOT NULL AND length(trim(persona_id)) BETWEEN 1 AND 128
            ORDER BY persona_id COLLATE NOCASE
            LIMIT ?
            """.trimIndent(),
            arrayOf(queryLimit(limit).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    /** Deletes every persisted conversation/memory/proactive row without a hidden query cap. */
    fun deleteAllConversationData(): Int {
        val now = System.currentTimeMillis()
        val changed =
            helper.writableDatabase.inNonExclusiveTransaction {
                delete(BotDbSchema.TABLE_MESSAGES, null, null) +
                    delete(BotDbSchema.TABLE_CHAT_MEMORY_HISTORY, null, null) +
                    delete(BotDbSchema.TABLE_CHAT_MEMORY, null, null) +
                    delete(BotDbSchema.TABLE_PERSONA_MEMORY, null, null) +
                    delete(BotDbSchema.TABLE_PROACTIVE_STATE, null, null)
            }
        if (changed > 0) afterWrite(now, changed)
        return changed
    }

    /**
     * Deletes all rows owned by [personaId] and returns every affected chat id for file cleanup.
     */
    fun deletePersonaConversationData(personaId: String): Pair<List<String>, Int> {
        requireShortId(personaId, "personaId")
        val pattern = "%#${escapeLike(personaId)}"
        val db = helper.writableDatabase
        val result =
            db.inNonExclusiveTransaction {
                val chatIds =
                    rawQuery(
                        """
                        SELECT DISTINCT chat_id
                        FROM ${BotDbSchema.TABLE_MESSAGES}
                        WHERE conversation_key LIKE ? ESCAPE '\'
                        UNION
                        SELECT DISTINCT chat_id
                        FROM ${BotDbSchema.TABLE_CHAT_MEMORY}
                        WHERE persona_id = ?
                        ORDER BY chat_id
                        """.trimIndent(),
                        arrayOf(pattern, personaId),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.getString(0))
                        }
                    }
                var changed =
                    delete(
                        BotDbSchema.TABLE_MESSAGES,
                        "conversation_key LIKE ? ESCAPE '\\'",
                        arrayOf(pattern),
                    )
                changed +=
                    delete(
                        BotDbSchema.TABLE_CHAT_MEMORY_HISTORY,
                        "persona_id = ?",
                        arrayOf(personaId),
                    )
                changed +=
                    delete(
                        BotDbSchema.TABLE_CHAT_MEMORY,
                        "persona_id = ?",
                        arrayOf(personaId),
                    )
                changed +=
                    delete(
                        BotDbSchema.TABLE_PERSONA_MEMORY,
                        "persona_id = ?",
                        arrayOf(personaId),
                    )
                chatIds to changed
            }
        if (result.second > 0) afterWrite(System.currentTimeMillis(), result.second)
        return result
    }

    fun upsertPersona(persona: PersonaRecord) {
        validatePersona(persona)
        val db = helper.writableDatabase
        db.inNonExclusiveTransaction {
            val insertValues = personaValues(persona, includeCreatedAt = true)
            val updateValues = personaValues(persona, includeCreatedAt = false)
            upsertWithoutRowId(
                db = db,
                table = BotDbSchema.TABLE_PERSONAS,
                insertValues = insertValues,
                updateValues = updateValues,
                where = "persona_id = ?",
                whereArgs = arrayOf(persona.personaId),
            )
        }
        afterWrite(persona.updatedAt)
    }

    fun upsertPersonas(personas: Collection<PersonaRecord>): Int {
        if (personas.isEmpty()) return 0
        personas.forEach(::validatePersona)
        val db = helper.writableDatabase
        db.inNonExclusiveTransaction {
            personas.forEach { persona ->
                val insertValues = personaValues(persona, includeCreatedAt = true)
                val updateValues = personaValues(persona, includeCreatedAt = false)
                upsertWithoutRowId(
                    db = db,
                    table = BotDbSchema.TABLE_PERSONAS,
                    insertValues = insertValues,
                    updateValues = updateValues,
                    where = "persona_id = ?",
                    whereArgs = arrayOf(persona.personaId),
                )
            }
        }
        afterWrite(personas.maxOf(PersonaRecord::updatedAt), personas.size)
        return personas.size
    }

    fun getPersona(personaId: String): PersonaRecord? {
        requireShortId(personaId, "personaId")
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_PERSONAS,
            columns = PERSONA_COLUMNS,
            selection = "persona_id = ?",
            selectionArgs = arrayOf(personaId),
            mapper = ::readPersona,
        )
    }

    fun listPersonas(
        enabledOnly: Boolean = false,
        limit: Int = BotDatabaseLimits.MAX_QUERY_LIMIT,
    ): List<PersonaRecord> = helper.readableDatabase.queryList(
        table = BotDbSchema.TABLE_PERSONAS,
        columns = PERSONA_COLUMNS,
        selection = if (enabledOnly) "enabled = 1" else null,
        selectionArgs = null,
        orderBy = "name COLLATE NOCASE, persona_id",
        limit = queryLimit(limit).toString(),
        mapper = ::readPersona,
    )

    fun deletePersona(personaId: String): Boolean {
        requireShortId(personaId, "personaId")
        val changed = helper.writableDatabase.delete(
            BotDbSchema.TABLE_PERSONAS,
            "persona_id = ?",
            arrayOf(personaId),
        )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    fun compareAndSwapPersonaMemory(
        expectedRevision: Long,
        memory: PersonaMemoryRecord,
    ): Boolean {
        require(expectedRevision >= 0L) { "expectedRevision must not be negative" }
        require(memory.revision == expectedRevision + 1L) { "memory revision is not monotonic" }
        requireShortId(memory.personaId, "personaId")
        require(memory.summary.length <= MAX_MEMORY_CHARS) { "Persona memory summary is too large" }
        require(memory.lastChatWriteCount >= 0L) { "lastChatWriteCount must not be negative" }
        val values = ContentValues(5).apply {
            put("persona_id", memory.personaId)
            put("summary", memory.summary)
            put("revision", memory.revision)
            put("last_chat_write_count", memory.lastChatWriteCount)
            put("updated_at", memory.updatedAt)
        }
        val changed =
            if (expectedRevision == 0L) {
                helper.writableDatabase.insertWithOnConflict(
                    BotDbSchema.TABLE_PERSONA_MEMORY,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE,
                ).let { if (it == -1L) 0 else 1 }
            } else {
                helper.writableDatabase.update(
                    BotDbSchema.TABLE_PERSONA_MEMORY,
                    values,
                    "persona_id = ? AND revision = ?",
                    arrayOf(memory.personaId, expectedRevision.toString()),
                )
            }
        if (changed > 0) afterWrite(memory.updatedAt)
        return changed > 0
    }

    fun getPersonaMemory(personaId: String): PersonaMemoryRecord? {
        requireShortId(personaId, "personaId")
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_PERSONA_MEMORY,
            columns = PERSONA_MEMORY_COLUMNS,
            selection = "persona_id = ?",
            selectionArgs = arrayOf(personaId),
            mapper = ::readPersonaMemory,
        )
    }

    fun deletePersonaMemory(personaId: String): Boolean {
        requireShortId(personaId, "personaId")
        val changed =
            helper.writableDatabase.delete(
                BotDbSchema.TABLE_PERSONA_MEMORY,
                "persona_id = ?",
                arrayOf(personaId),
            )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    fun clearPersonaAssignment(chatId: String): Boolean {
        requireExternalId(chatId, "chatId")
        val changed = helper.writableDatabase.delete(
            BotDbSchema.TABLE_PERSONA_ASSIGNMENTS,
            "chat_id = ?",
            arrayOf(chatId),
        )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    // endregion

    // region Access lists and scoped settings

    fun upsertAccessEntry(entry: AccessEntryRecord) {
        requireExternalId(entry.subjectId, "subjectId")
        require(entry.label == null || entry.label.length <= 512) { "Access label is too large" }
        val insertValues = accessValues(entry, includeCreatedAt = true)
        val updateValues = accessValues(entry, includeCreatedAt = false)
        helper.writableDatabase.inNonExclusiveTransaction {
            upsertWithoutRowId(
                db = this,
                table = BotDbSchema.TABLE_ACCESS_ENTRIES,
                insertValues = insertValues,
                updateValues = updateValues,
                where = "list_kind = ? AND subject_type = ? AND subject_id = ?",
                whereArgs = arrayOf(
                    entry.listKind.databaseValue,
                    entry.subjectType.databaseValue,
                    entry.subjectId,
                ),
            )
        }
        afterWrite(entry.updatedAt)
    }

    fun listAccessEntries(
        listKind: AccessListKind? = null,
        enabledOnly: Boolean = false,
        limit: Int = BotDatabaseLimits.MAX_QUERY_LIMIT,
    ): List<AccessEntryRecord> {
        val clauses = ArrayList<String>(2)
        val args = ArrayList<String>(1)
        if (listKind != null) {
            clauses += "list_kind = ?"
            args += listKind.databaseValue
        }
        if (enabledOnly) clauses += "enabled = 1"
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_ACCESS_ENTRIES,
            columns = ACCESS_COLUMNS,
            selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs = args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            orderBy = "list_kind, label COLLATE NOCASE, subject_id",
            limit = queryLimit(limit).toString(),
            mapper = ::readAccess,
        )
    }

    fun isAccessListed(
        listKind: AccessListKind,
        subjectType: AccessSubjectType,
        subjectId: String,
    ): Boolean {
        requireExternalId(subjectId, "subjectId")
        helper.readableDatabase.rawQuery(
            """
            SELECT 1
            FROM ${BotDbSchema.TABLE_ACCESS_ENTRIES}
            WHERE list_kind = ? AND subject_type = ? AND subject_id = ? AND enabled = 1
            LIMIT 1
            """.trimIndent(),
            arrayOf(listKind.databaseValue, subjectType.databaseValue, subjectId),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun removeAccessEntry(
        listKind: AccessListKind,
        subjectType: AccessSubjectType,
        subjectId: String,
    ): Boolean {
        requireExternalId(subjectId, "subjectId")
        val changed = helper.writableDatabase.delete(
            BotDbSchema.TABLE_ACCESS_ENTRIES,
            "list_kind = ? AND subject_type = ? AND subject_id = ?",
            arrayOf(listKind.databaseValue, subjectType.databaseValue, subjectId),
        )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    /**
     * Stores non-secret runtime preferences. Sensitive-looking keys are rejected before SQLite so
     * an accidental API token cannot become part of backups, logs or a database export.
     */
    fun putSetting(setting: ScopedSettingRecord) {
        validateSetting(setting)
        helper.writableDatabase.inNonExclusiveTransaction {
            val values = settingValues(setting)
            upsertWithoutRowId(
                db = this,
                table = BotDbSchema.TABLE_SCOPED_SETTINGS,
                insertValues = values,
                updateValues = values,
                where = "scope_type = ? AND scope_id = ? AND setting_key = ?",
                whereArgs = arrayOf(setting.scopeType, setting.scopeId, setting.key),
            )
            ensureSettingsWithinLimit(this)
            incrementSettingsRevision(this)
        }
        afterWrite(setting.updatedAt)
    }

    fun getSetting(
        scopeType: String,
        scopeId: String = ScopedSettingRecord.GLOBAL_SCOPE_ID,
        key: String,
    ): ScopedSettingRecord? {
        validateSettingAddress(scopeType, scopeId, key)
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_SCOPED_SETTINGS,
            columns = SETTING_COLUMNS,
            selection = "scope_type = ? AND scope_id = ? AND setting_key = ?",
            selectionArgs = arrayOf(scopeType, scopeId, key),
            mapper = ::readSetting,
        )
    }

    /**
     * Resolves scopes in caller-supplied priority order without encoding UI policy in persistence.
     */
    fun listSettings(
        scopeType: String,
        scopeId: String = ScopedSettingRecord.GLOBAL_SCOPE_ID,
        limit: Int = BotDatabaseLimits.MAX_QUERY_LIMIT,
    ): List<ScopedSettingRecord> {
        validateSettingAddress(scopeType, scopeId, "placeholder")
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_SCOPED_SETTINGS,
            columns = SETTING_COLUMNS,
            selection = "scope_type = ? AND scope_id = ?",
            selectionArgs = arrayOf(scopeType, scopeId),
            orderBy = "setting_key",
            limit = queryLimit(limit).toString(),
            mapper = ::readSetting,
        )
    }

    /**
     * Loads every non-secret scoped setting and its revision from one SQLite snapshot.
     */
    fun loadSettingsSnapshot(): DatabaseSettingsSnapshot {
        helper.readableDatabase.rawQuery(
            """
            SELECT
                COALESCE(
                    (SELECT CAST(meta_value AS INTEGER)
                     FROM ${BotDbSchema.TABLE_DB_META}
                     WHERE meta_key = ?),
                    0
                ) AS settings_revision,
                s.scope_type,
                s.scope_id,
                s.setting_key,
                s.setting_value,
                s.value_type,
                s.updated_at
            FROM (SELECT 1) singleton
            LEFT JOIN ${BotDbSchema.TABLE_SCOPED_SETTINGS} s ON 1 = 1
            ORDER BY s.scope_type, s.scope_id, s.setting_key
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                META_SETTINGS_REVISION,
                (BotDatabaseLimits.MAX_SCOPED_SETTINGS + 1).toString(),
            ),
        ).use { cursor ->
            var revision = 0L
            val settings = buildList(cursor.count.coerceAtLeast(0)) {
                while (cursor.moveToNext()) {
                    revision = cursor.getLong(0)
                    if (!cursor.isNull(1)) {
                        add(
                            ScopedSettingRecord(
                                scopeType = cursor.getString(1),
                                scopeId = cursor.getString(2),
                                key = cursor.getString(3),
                                value = cursor.getString(4),
                                valueType = StoredSettingValueType.fromDatabase(cursor.getString(5)),
                                updatedAt = cursor.getLong(6),
                            ),
                        )
                    }
                }
            }
            check(settings.size <= BotDatabaseLimits.MAX_SCOPED_SETTINGS) {
                "Scoped settings table exceeds its hard limit"
            }
            return DatabaseSettingsSnapshot(revision = revision, settings = settings)
        }
    }

    /**
     * Compare-and-swap used by SettingsPersistence. A matching revision applies every deletion,
     * every upsert and the revision increment in one transaction. A mismatch writes nothing. Both
     * outcomes include the matching full snapshot, so the adapter never needs a racy second load.
     */
    fun compareAndSwapSettings(
        expectedRevision: Long,
        upserts: Collection<ScopedSettingRecord>,
        deletes: Collection<SettingAddress>,
    ): SettingsCompareAndSwapResult {
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
        require(upserts.isNotEmpty() || deletes.isNotEmpty()) {
            "A settings compare-and-swap batch must contain at least one mutation"
        }
        upserts.forEach(::validateSetting)
        deletes.forEach { validateSettingAddress(it) }
        val upsertAddresses = upserts.mapTo(HashSet(upserts.size)) {
            SettingAddress(it.scopeType, it.scopeId, it.key)
        }
        require(upsertAddresses.size == upserts.size) {
            "A settings compare-and-swap batch contains duplicate upsert addresses"
        }
        require(deletes.toSet().size == deletes.size) {
            "A settings compare-and-swap batch contains duplicate delete addresses"
        }
        require(deletes.none(upsertAddresses::contains)) {
            "The same setting cannot be upserted and deleted in one batch"
        }

        val result = helper.writableDatabase.inNonExclusiveTransaction {
            val currentRevision = readMetaLong(this, META_SETTINGS_REVISION) ?: 0L
            if (currentRevision != expectedRevision) {
                return@inNonExclusiveTransaction SettingsCompareAndSwapResult(
                    applied = false,
                    snapshot = DatabaseSettingsSnapshot(
                        revision = currentRevision,
                        settings = readAllSettingsRows(this),
                    ),
                )
            }
            deletes.forEach { address ->
                delete(
                    BotDbSchema.TABLE_SCOPED_SETTINGS,
                    "scope_type = ? AND scope_id = ? AND setting_key = ?",
                    arrayOf(address.scopeType, address.scopeId, address.key),
                )
            }
            upserts.forEach { setting ->
                val values = settingValues(setting)
                upsertWithoutRowId(
                    db = this,
                    table = BotDbSchema.TABLE_SCOPED_SETTINGS,
                    insertValues = values,
                    updateValues = values,
                    where = "scope_type = ? AND scope_id = ? AND setting_key = ?",
                    whereArgs = arrayOf(setting.scopeType, setting.scopeId, setting.key),
                )
            }
            ensureSettingsWithinLimit(this)
            val nextRevision = incrementSettingsRevision(this, currentRevision)
            SettingsCompareAndSwapResult(
                applied = true,
                snapshot = DatabaseSettingsSnapshot(
                    revision = nextRevision,
                    settings = readAllSettingsRows(this),
                ),
            )
        }
        if (result.applied) {
            afterWrite(System.currentTimeMillis(), upserts.size + deletes.size)
        }
        return result
    }

    fun deleteSetting(
        scopeType: String,
        scopeId: String = ScopedSettingRecord.GLOBAL_SCOPE_ID,
        key: String,
    ): Boolean {
        validateSettingAddress(scopeType, scopeId, key)
        val changed = helper.writableDatabase.inNonExclusiveTransaction {
            val deleted = delete(
                BotDbSchema.TABLE_SCOPED_SETTINGS,
                "scope_type = ? AND scope_id = ? AND setting_key = ?",
                arrayOf(scopeType, scopeId, key),
            )
            if (deleted > 0) incrementSettingsRevision(this)
            deleted
        }
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    // endregion

    // region Proactive scheduler state

    fun putProactiveState(state: ProactiveStateRecord) {
        validateProactiveState(state)
        val db = helper.writableDatabase
        db.inNonExclusiveTransaction {
            ensureChatStub(db, state.chatId, state.updatedAt)
            val values = proactiveValues(state)
            upsertWithoutRowId(
                db = db,
                table = BotDbSchema.TABLE_PROACTIVE_STATE,
                insertValues = values,
                updateValues = values,
                where = "chat_id = ?",
                whereArgs = arrayOf(state.chatId),
            )
        }
        afterWrite(state.updatedAt)
    }

    fun getProactiveState(chatId: String): ProactiveStateRecord? {
        requireExternalId(chatId, "chatId")
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_PROACTIVE_STATE,
            columns = PROACTIVE_COLUMNS,
            selection = "chat_id = ?",
            selectionArgs = arrayOf(chatId),
            mapper = ::readProactive,
        )
    }

    /**
     * CAS update for planner-owned fields only. A full-row upsert here can erase a concurrently
     * claimed lease or overwrite delivery counters, which makes the same due item runnable twice.
     */
    fun updateProactiveSchedule(
        chatId: String,
        enabled: Boolean,
        nextDueAt: Long?,
        stateJson: String,
        requestedUpdatedAt: Long,
        expectedUpdatedAt: Long,
    ): Boolean {
        requireExternalId(chatId, "chatId")
        requireJsonSize(stateJson, 262_144, "stateJson")
        val updatedAt = nextMonotonicTimestamp(expectedUpdatedAt, requestedUpdatedAt)
        val changed =
            helper.writableDatabase.update(
                BotDbSchema.TABLE_PROACTIVE_STATE,
                ContentValues(4).apply {
                    put("enabled", enabled.asDatabaseInt())
                    putNullable("next_due_at", nextDueAt)
                    put("state_json", stateJson)
                    put("updated_at", updatedAt)
                },
                "chat_id = ? AND updated_at = ?",
                arrayOf(chatId, expectedUpdatedAt.toString()),
            )
        if (changed > 0) afterWrite(updatedAt)
        return changed == 1
    }

    fun listProactiveStates(
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<ProactiveStateRecord> =
        helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_PROACTIVE_STATE,
            columns = PROACTIVE_COLUMNS,
            selection = null,
            selectionArgs = null,
            orderBy = "updated_at DESC, chat_id",
            limit = queryLimit(limit).toString(),
            mapper = ::readProactive,
        )

    /**
     * Returns the one nearest effective deadline. Cooldowns and unexpired
     * leases are folded into the ordering, preventing a tight claim-fail loop.
     * A row without any anchor is excluded by construction: proactive work can
     * only run for chats that either replied once or were explicitly armed for
     * cold outreach by the user.
     */
    fun nextProactiveDeadline(): ProactiveStateRecord? =
        helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_PROACTIVE_STATE,
            columns = PROACTIVE_COLUMNS,
            selection = """
                enabled = 1
                AND next_due_at IS NOT NULL
                AND (last_inbound_at IS NOT NULL OR cold_outreach_at IS NOT NULL)
            """.trimIndent(),
            selectionArgs = null,
            orderBy = """
                MAX(
                    next_due_at,
                    COALESCE(cooldown_until, 0),
                    COALESCE(lease_until, 0)
                ),
                chat_id
            """.trimIndent(),
            limit = "1",
            mapper = ::readProactive,
        ).firstOrNull()

    fun proactiveGlobalNotBefore(): Long? =
        readMetaLong(helper.readableDatabase, META_PROACTIVE_GLOBAL_NOT_BEFORE)

    /**
     * Monotonic account-wide cadence. A shorter stale writer cannot pull a
     * later safety deadline forward.
     */
    fun setProactiveGlobalNotBefore(timestamp: Long) {
        requireNonNegativeTimestamp(timestamp, "timestamp")
        val db = helper.writableDatabase
        db.inNonExclusiveTransaction {
            val current = readMetaLong(this, META_PROACTIVE_GLOBAL_NOT_BEFORE) ?: 0L
            putMeta(
                this,
                META_PROACTIVE_GLOBAL_NOT_BEFORE,
                max(current, timestamp).toString(),
            )
        }
        afterWrite(System.currentTimeMillis())
    }

    fun deleteProactiveState(chatId: String): Boolean {
        requireExternalId(chatId, "chatId")
        val changed =
            helper.writableDatabase.delete(
                BotDbSchema.TABLE_PROACTIVE_STATE,
                "chat_id = ?",
                arrayOf(chatId),
            )
        if (changed > 0) afterWrite(System.currentTimeMillis())
        return changed > 0
    }

    /**
     * Claims due chats with expiring leases. A process death merely delays work until leaseUntil;
     * no alarm, timer or permanently awake scheduler is required.
     */
    /**
     * Atomic single-chat claim used by the one-timer scheduler. Returning the
     * claimed snapshot lets the caller detect any newer inbound/settings write
     * before finishing the lease.
     */
    fun claimProactive(
        chatId: String,
        leaseOwner: String,
        now: Long = System.currentTimeMillis(),
        leaseDurationMs: Long,
        respectGlobalNotBefore: Boolean = true,
    ): ProactiveStateRecord? {
        requireExternalId(chatId, "chatId")
        requireShortId(leaseOwner, "leaseOwner")
        require(leaseDurationMs in 1_000L..MAX_LEASE_DURATION_MS) {
            "leaseDurationMs is outside the supported range"
        }
        val leaseUntil = now + leaseDurationMs
        val db = helper.writableDatabase
        val claimed =
            db.inNonExclusiveTransaction {
                val globalNotBefore =
                    readMetaLong(this, META_PROACTIVE_GLOBAL_NOT_BEFORE) ?: 0L
                if (respectGlobalNotBefore && globalNotBefore > now) {
                    return@inNonExclusiveTransaction null
                }
                val current =
                    queryOne(
                        table = BotDbSchema.TABLE_PROACTIVE_STATE,
                        columns = PROACTIVE_COLUMNS,
                        selection = """
                            chat_id = ?
                            AND enabled = 1
                            AND (last_inbound_at IS NOT NULL OR cold_outreach_at IS NOT NULL)
                            AND next_due_at IS NOT NULL
                            AND next_due_at <= ?
                            AND (cooldown_until IS NULL OR cooldown_until <= ?)
                            AND (lease_until IS NULL OR lease_until <= ?)
                        """.trimIndent(),
                        selectionArgs =
                            arrayOf(
                                chatId,
                                now.toString(),
                                now.toString(),
                                now.toString(),
                            ),
                        mapper = ::readProactive,
                    ) ?: return@inNonExclusiveTransaction null
                val updatedAt = nextMonotonicTimestamp(current.updatedAt, now)
                val changed =
                    update(
                        BotDbSchema.TABLE_PROACTIVE_STATE,
                        ContentValues(3).apply {
                            put("lease_owner", leaseOwner)
                            put("lease_until", leaseUntil)
                            put("updated_at", updatedAt)
                        },
                        """
                            chat_id = ?
                            AND updated_at = ?
                            AND (lease_until IS NULL OR lease_until <= ?)
                        """.trimIndent(),
                        arrayOf(chatId, current.updatedAt.toString(), now.toString()),
                    )
                if (changed == 1) {
                    current.copy(
                        leaseOwner = leaseOwner,
                        leaseUntil = leaseUntil,
                        updatedAt = updatedAt,
                    )
                } else {
                    null
                }
            }
        if (claimed != null) afterWrite(now)
        return claimed
    }

    fun finishProactiveLease(
        chatId: String,
        leaseOwner: String,
        nextDueAt: Long?,
        success: Boolean,
        outboundSent: Boolean,
        now: Long = System.currentTimeMillis(),
        enabled: Boolean = true,
        expectedUpdatedAt: Long? = null,
        stateJson: String? = null,
    ): Boolean {
        requireExternalId(chatId, "chatId")
        requireShortId(leaseOwner, "leaseOwner")
        val db = helper.writableDatabase
        val changed = db.inNonExclusiveTransaction {
            val current = queryOne(
                table = BotDbSchema.TABLE_PROACTIVE_STATE,
                columns = PROACTIVE_COLUMNS,
                selection = "chat_id = ? AND lease_owner = ?",
                selectionArgs = arrayOf(chatId, leaseOwner),
                mapper = ::readProactive,
            ) ?: return@inNonExclusiveTransaction 0

            val stateChanged =
                expectedUpdatedAt != null && current.updatedAt != expectedUpdatedAt
            val windowExpired =
                current.dailyWindowStartedAt == null ||
                    current.dailyWindowStartedAt <= now - ONE_DAY_MS
            val values = ContentValues(10).apply {
                putNull("lease_owner")
                putNull("lease_until")
                if (!stateChanged) {
                    put("enabled", enabled.asDatabaseInt())
                    putNullable("next_due_at", nextDueAt)
                    put(
                        "consecutive_failures",
                        if (success) 0 else current.consecutiveFailures + 1,
                    )
                    if (stateJson != null) put("state_json", stateJson)
                }
                if (outboundSent) {
                    put("last_outbound_at", now)
                    put(
                        "daily_window_started_at",
                        if (windowExpired) now else current.dailyWindowStartedAt,
                    )
                    put(
                        "daily_outbound_count",
                        if (windowExpired) 1 else current.dailyOutboundCount + 1,
                    )
                }
                put("updated_at", nextMonotonicTimestamp(current.updatedAt, now))
            }
            update(
                BotDbSchema.TABLE_PROACTIVE_STATE,
                values,
                "chat_id = ? AND lease_owner = ?",
                arrayOf(chatId, leaseOwner),
            )
        }
        if (changed > 0) afterWrite(now)
        return changed > 0
    }

    fun recordProactiveInbound(
        chatId: String,
        occurredAt: Long,
    ) {
        requireExternalId(chatId, "chatId")
        val db = helper.writableDatabase
        db.inNonExclusiveTransaction {
            ensureChatStub(db, chatId, occurredAt)
            val current =
                queryOne(
                    table = BotDbSchema.TABLE_PROACTIVE_STATE,
                    columns = PROACTIVE_COLUMNS,
                    selection = "chat_id = ?",
                    selectionArgs = arrayOf(chatId),
                    mapper = ::readProactive,
                )
            if (current == null) {
                val state = ProactiveStateRecord(
                    chatId = chatId,
                    lastInboundAt = occurredAt,
                    updatedAt = occurredAt,
                )
                insertOrThrow(
                    BotDbSchema.TABLE_PROACTIVE_STATE,
                    null,
                    proactiveValues(state),
                )
            } else {
                update(
                    BotDbSchema.TABLE_PROACTIVE_STATE,
                    ContentValues(2).apply {
                        put("last_inbound_at", max(current.lastInboundAt ?: 0L, occurredAt))
                        put("updated_at", nextMonotonicTimestamp(current.updatedAt, occurredAt))
                    },
                    "chat_id = ?",
                    arrayOf(chatId),
                )
            }
        }
        afterWrite(occurredAt)
    }

    /**
     * Arms a chat that never wrote in for proactive outreach, creating the row if the user raised
     * the level for a contact the bot has no history with. The timestamp is written once and then
     * left alone, so re-arming an already armed contact does not restart its silence clock.
     * Returns the row as it stands afterwards.
     */
    fun armColdProactive(
        chatId: String,
        armedAt: Long = System.currentTimeMillis(),
    ): ProactiveStateRecord {
        requireExternalId(chatId, "chatId")
        requireNonNegativeTimestamp(armedAt, "armedAt")
        val db = helper.writableDatabase
        val result = db.inNonExclusiveTransaction {
            ensureChatStub(db, chatId, armedAt)
            val current = queryOne(
                table = BotDbSchema.TABLE_PROACTIVE_STATE,
                columns = PROACTIVE_COLUMNS,
                selection = "chat_id = ?",
                selectionArgs = arrayOf(chatId),
                mapper = ::readProactive,
            )
            if (current == null) {
                val state = ProactiveStateRecord(
                    chatId = chatId,
                    coldOutreachAt = armedAt,
                    updatedAt = armedAt,
                )
                insertOrThrow(
                    BotDbSchema.TABLE_PROACTIVE_STATE,
                    null,
                    proactiveValues(state),
                )
                state
            } else if (current.coldOutreachAt != null || current.lastInboundAt != null) {
                current
            } else {
                val updatedAt = nextMonotonicTimestamp(current.updatedAt, armedAt)
                update(
                    BotDbSchema.TABLE_PROACTIVE_STATE,
                    ContentValues(2).apply {
                        put("cold_outreach_at", armedAt)
                        put("updated_at", updatedAt)
                    },
                    "chat_id = ?",
                    arrayOf(chatId),
                )
                current.copy(coldOutreachAt = armedAt, updatedAt = updatedAt)
            }
        }
        afterWrite(armedAt)
        return result
    }

    /**
     * Chats the bot may address proactively, newest activity first. Rows with neither an inbound
     * message nor a cold arming timestamp are excluded, matching [nextProactiveDeadline].
     */
    // endregion

    // region Media analysis cache

    fun putMediaAnalysis(analysis: MediaAnalysisRecord) {
        validateMediaAnalysis(analysis)
        val values = mediaAnalysisValues(analysis)
        helper.writableDatabase.inNonExclusiveTransaction {
            upsertWithoutRowId(
                db = this,
                table = BotDbSchema.TABLE_MEDIA_ANALYSIS_CACHE,
                insertValues = values,
                updateValues = values,
                where = "content_hash = ? AND analyzer = ? AND analyzer_version = ?",
                whereArgs = arrayOf(
                    analysis.contentHash,
                    analysis.analyzer,
                    analysis.analyzerVersion,
                ),
            )
        }
        afterWrite(analysis.createdAt)
    }

    /**
     * Cache reads normally remain read-only. last_accessed_at is touched at most every six hours,
     * preventing frequently reused media from creating a flash write for every message.
     */
    fun getMediaAnalysis(
        contentHash: String,
        analyzer: String,
        analyzerVersion: String,
        now: Long = System.currentTimeMillis(),
    ): MediaAnalysisRecord? {
        requireHash(contentHash, "contentHash")
        requireShortId(analyzer, "analyzer")
        requireShortId(analyzerVersion, "analyzerVersion")
        val db = helper.readableDatabase
        val result = db.queryOne(
            table = BotDbSchema.TABLE_MEDIA_ANALYSIS_CACHE,
            columns = MEDIA_COLUMNS,
            selection = """
                content_hash = ?
                AND analyzer = ?
                AND analyzer_version = ?
                AND (expires_at IS NULL OR expires_at > ?)
            """.trimIndent(),
            selectionArgs = arrayOf(
                contentHash,
                analyzer,
                analyzerVersion,
                now.toString(),
            ),
            mapper = ::readMediaAnalysis,
        ) ?: return null

        if (result.lastAccessedAt <= now - BotDatabaseLimits.MEDIA_TOUCH_MIN_INTERVAL_MS) {
            val changed = helper.writableDatabase.update(
                BotDbSchema.TABLE_MEDIA_ANALYSIS_CACHE,
                ContentValues(1).apply { put("last_accessed_at", now) },
                """
                content_hash = ? AND analyzer = ? AND analyzer_version = ?
                AND last_accessed_at <= ?
                """.trimIndent(),
                arrayOf(
                    contentHash,
                    analyzer,
                    analyzerVersion,
                    (now - BotDatabaseLimits.MEDIA_TOUCH_MIN_INTERVAL_MS).toString(),
                ),
            )
            if (changed > 0) afterWrite(now)
            return if (changed > 0) result.copy(lastAccessedAt = now) else result
        }
        return result
    }

    // endregion

    // region Outbound safety ledger

    /**
     * Atomically reserves a dedupe key. Existing rows are returned unchanged, allowing every send
     * path (reply, command, proactive and retry) to share the same final outbound gate.
     */
    fun reserveOutbound(record: OutboundSafetyRecord): OutboundReservation {
        validateOutbound(record)
        val db = helper.writableDatabase
        val reservation = db.inNonExclusiveTransaction {
            val insertedId = insertWithOnConflict(
                BotDbSchema.TABLE_OUTBOUND_SAFETY,
                null,
                outboundValues(record, includeId = false),
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (insertedId >= 0L) {
                OutboundReservation(
                    acquired = true,
                    record = record.copy(databaseId = insertedId),
                )
            } else {
                val existing = queryOne(
                    table = BotDbSchema.TABLE_OUTBOUND_SAFETY,
                    columns = OUTBOUND_COLUMNS,
                    selection = "dedupe_key = ?",
                    selectionArgs = arrayOf(record.dedupeKey),
                    mapper = ::readOutbound,
                ) ?: error("Outbound dedupe conflict without a persisted row")
                OutboundReservation(acquired = false, record = existing)
            }
        }
        if (reservation.acquired) afterWrite(record.plannedAt)
        return reservation
    }

    fun getOutboundReservation(dedupeKey: String): OutboundSafetyRecord? {
        requireExternalId(dedupeKey, "dedupeKey")
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_OUTBOUND_SAFETY,
            columns = OUTBOUND_COLUMNS,
            selection = "dedupe_key = ?",
            selectionArgs = arrayOf(dedupeKey),
            mapper = ::readOutbound,
        )
    }

    fun markOutboundStatus(
        dedupeKey: String,
        status: OutboundStatus,
        committedAt: Long? = System.currentTimeMillis(),
        reasonCode: String? = null,
    ): Boolean {
        requireExternalId(dedupeKey, "dedupeKey")
        if (reasonCode != null) requireShortId(reasonCode, "reasonCode")
        val values = ContentValues(3).apply {
            put("status", status.databaseValue)
            put("committed_at", committedAt)
            if (reasonCode != null) put("reason_code", reasonCode)
        }
        val changed = helper.writableDatabase.update(
            BotDbSchema.TABLE_OUTBOUND_SAFETY,
            values,
            "dedupe_key = ?",
            arrayOf(dedupeKey),
        )
        if (changed > 0) afterWrite(committedAt ?: System.currentTimeMillis())
        return changed > 0
    }

    /**
     * Chronological ledger page, newest first.
     *
     * [sincePlannedAt], [outboundKind] and [status] exist so the outbound safety policy can ask
     * for the rows it actually evaluates. It used to read a full page of the newest rows and drop
     * most of them in Kotlin, on every send decision — the filters belong in SQL, where the
     * chronological and kind indexes can serve them.
     */
    fun listOutboundSafety(
        chatId: String? = null,
        beforePlannedAt: Long? = null,
        beforeDatabaseId: Long? = null,
        sincePlannedAt: Long? = null,
        outboundKind: String? = null,
        status: OutboundStatus? = null,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<OutboundSafetyRecord> {
        if (chatId != null) requireExternalId(chatId, "chatId")
        if (outboundKind != null) requireShortId(outboundKind, "outboundKind")
        require(beforeDatabaseId == null || beforePlannedAt != null) {
            "beforeDatabaseId requires beforePlannedAt"
        }
        val clauses = ArrayList<String>(5)
        val args = ArrayList<String>(7)
        if (chatId != null) {
            clauses += "chat_id = ?"
            args += chatId
        }
        if (outboundKind != null) {
            clauses += "outbound_kind = ?"
            args += outboundKind
        }
        if (status != null) {
            clauses += "status = ?"
            args += status.databaseValue
        }
        if (sincePlannedAt != null) {
            clauses += "planned_at >= ?"
            args += sincePlannedAt.toString()
        }
        if (beforePlannedAt != null) {
            if (beforeDatabaseId == null) {
                clauses += "planned_at < ?"
                args += beforePlannedAt.toString()
            } else {
                clauses += "(planned_at < ? OR (planned_at = ? AND id < ?))"
                args += beforePlannedAt.toString()
                args += beforePlannedAt.toString()
                args += beforeDatabaseId.toString()
            }
        }
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_OUTBOUND_SAFETY,
            columns = OUTBOUND_COLUMNS,
            selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs = args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            orderBy = "planned_at DESC, id DESC",
            limit = queryLimit(limit).toString(),
            mapper = ::readOutbound,
        )
    }

    /**
     * The safety locks that are still holding, newest first.
     *
     * The caller used to read the newest thousand ledger rows and filter them down in Kotlin —
     * a thousand mapped records to show two or three locks, on every safety refresh. Worse, the
     * ledger is mostly ordinary send reservations, so a lock that had scrolled past the thousandth
     * row simply stopped being reported as active while it was still in force.
     */
    fun listActiveOutboundLocks(
        kinds: Collection<String>,
        now: Long,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<OutboundSafetyRecord> {
        if (kinds.isEmpty()) return emptyList()
        kinds.forEach { requireShortId(it, "outboundKind") }
        val placeholders = List(kinds.size) { "?" }.joinToString(",")
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_OUTBOUND_SAFETY,
            columns = OUTBOUND_COLUMNS,
            selection =
                "status = ? AND outbound_kind IN ($placeholders) " +
                    "AND (expires_at IS NULL OR expires_at > ?)",
            selectionArgs =
                (
                    listOf(OutboundStatus.RESERVED.databaseValue) + kinds + now.toString()
                ).toTypedArray(),
            orderBy = "planned_at DESC, id DESC",
            limit = queryLimit(limit).toString(),
            mapper = ::readOutbound,
        )
    }

    /**
     * One safety row by its primary key.
     *
     * The admin path used to find it by listing the newest thousand rows and filtering in Kotlin,
     * which is both a thousand-row read for one row and quietly wrong: a row older than that
     * window reported "not found" to an admin who was looking straight at its ID.
     */
    fun getOutboundSafety(databaseId: Long): OutboundSafetyRecord? {
        require(databaseId > 0) { "databaseId must be positive" }
        return helper.readableDatabase.queryOne(
            table = BotDbSchema.TABLE_OUTBOUND_SAFETY,
            columns = OUTBOUND_COLUMNS,
            selection = "id = ?",
            selectionArgs = arrayOf(databaseId.toString()),
            mapper = ::readOutbound,
        )
    }

    fun listActiveSafetyControls(
        limit: Int = BotDatabaseLimits.MAX_QUERY_LIMIT,
    ): List<OutboundSafetyRecord> =
        helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_OUTBOUND_SAFETY,
            columns = OUTBOUND_COLUMNS,
            selection = "status = ? AND outbound_kind IN (?, ?)",
            selectionArgs =
                arrayOf(
                    OutboundStatus.RESERVED.databaseValue,
                    "safety_hold",
                    "safety_lock",
                ),
            orderBy = "planned_at DESC, id DESC",
            limit = queryLimit(limit).toString(),
            mapper = ::readOutbound,
        )

    fun putTransportSafetySnapshot(value: String) {
        require(value.length <= 131_072) { "Transport safety snapshot is too large" }
        helper.writableDatabase.inNonExclusiveTransaction {
            putMeta(this, META_TRANSPORT_SAFETY_SNAPSHOT, value)
        }
        afterWrite(System.currentTimeMillis())
    }

    fun transportSafetySnapshot(): String? =
        helper.readableDatabase.query(
            BotDbSchema.TABLE_DB_META,
            arrayOf("meta_value"),
            "meta_key = ?",
            arrayOf(META_TRANSPORT_SAFETY_SNAPSHOT),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    // endregion

    // region Structured activity log

    fun appendActivity(record: ActivityLogRecord): Long {
        validateActivity(record)
        val id = helper.writableDatabase.insertOrThrow(
            BotDbSchema.TABLE_ACTIVITY_LOG,
            null,
            activityValues(record, includeId = false),
        )
        afterWrite(record.occurredAt)
        return id
    }

    fun appendActivities(records: Collection<ActivityLogRecord>): Int {
        if (records.isEmpty()) return 0
        records.forEach(::validateActivity)
        helper.writableDatabase.inNonExclusiveTransaction {
            records.forEach { record ->
                insertOrThrow(
                    BotDbSchema.TABLE_ACTIVITY_LOG,
                    null,
                    activityValues(record, includeId = false),
                )
            }
        }
        afterWrite(records.maxOf { it.occurredAt }, records.size)
        return records.size
    }

    fun listActivity(
        chatId: String? = null,
        category: String? = null,
        minimumLevel: ActivityLevel? = null,
        beforeOccurredAt: Long? = null,
        beforeId: Long? = null,
        limit: Int = BotDatabaseLimits.DEFAULT_QUERY_LIMIT,
    ): List<ActivityLogRecord> {
        if (chatId != null) requireExternalId(chatId, "chatId")
        if (category != null) requireShortId(category, "category")
        val clauses = ArrayList<String>(4)
        val args = ArrayList<String>(4)
        if (chatId != null) {
            clauses += "chat_id = ?"
            args += chatId
        }
        if (category != null) {
            clauses += "category = ?"
            args += category
        }
        if (minimumLevel != null) {
            clauses += when (minimumLevel) {
                ActivityLevel.DEBUG -> "level IN ('debug','info','warn','error')"
                ActivityLevel.INFO -> "level IN ('info','warn','error')"
                ActivityLevel.WARN -> "level IN ('warn','error')"
                ActivityLevel.ERROR -> "level = 'error'"
            }
        }
        if (beforeOccurredAt != null) {
            if (beforeId != null) {
                clauses += "(occurred_at < ? OR (occurred_at = ? AND id < ?))"
                args += beforeOccurredAt.toString()
                args += beforeOccurredAt.toString()
                args += beforeId.toString()
            } else {
                clauses += "occurred_at < ?"
                args += beforeOccurredAt.toString()
            }
        }
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_ACTIVITY_LOG,
            columns = ACTIVITY_COLUMNS,
            selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs = args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            orderBy = "occurred_at DESC, id DESC",
            limit = queryLimit(limit).toString(),
            mapper = ::readActivity,
        )
    }

    /**
     * Small indexed aggregate used only after committed events or when UI opens.
     *
     * The sent count skips [OUTBOUND_KIND_TURN_PERMIT] rows for the same reason the send budget
     * does: one reply reserves the turn once and then every visible bubble separately, so counting
     * both made the screen report roughly twice the messages that were actually sent — and made a
     * daily cap that was working look like it was being ignored.
     */
    fun runtimeCountsSince(sinceEpochMs: Long): RuntimeCountSnapshot {
        require(sinceEpochMs >= 0) { "sinceEpochMs must be non-negative" }
        helper.readableDatabase.rawQuery(
            """
            SELECT
              (SELECT COUNT(*) FROM ${BotDbSchema.TABLE_MESSAGES}
               WHERE direction = ? AND occurred_at >= ?),
              (SELECT COUNT(*) FROM ${BotDbSchema.TABLE_OUTBOUND_SAFETY}
               WHERE status = ? AND committed_at >= ? AND outbound_kind <> ?),
              (SELECT COUNT(DISTINCT chat_id) FROM ${BotDbSchema.TABLE_BRIDGE_OUTBOX}
               WHERE chat_id IS NOT NULL AND state IN (?, ?))
            """.trimIndent(),
            arrayOf(
                MessageDirection.INBOUND.databaseValue,
                sinceEpochMs.toString(),
                OutboundStatus.SENT.databaseValue,
                sinceEpochMs.toString(),
                OUTBOUND_KIND_TURN_PERMIT,
                BridgeOutboxState.PENDING.databaseValue,
                BridgeOutboxState.LEASED.databaseValue,
            ),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Runtime count query returned no row" }
            return RuntimeCountSnapshot(
                processedInbound = cursor.getLong(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                sentOutbound = cursor.getLong(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                pendingChats = cursor.getLong(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
    }

    // endregion

    // region Durable bridge outbox

    fun enqueueBridgeOperation(record: BridgeOutboxRecord): BridgeEnqueueResult {
        validateOutbox(record)
        val db = helper.writableDatabase
        val result = db.inNonExclusiveTransaction {
            queryOne(
                table = BotDbSchema.TABLE_BRIDGE_OUTBOX,
                columns = OUTBOX_COLUMNS,
                selection = "dedupe_key = ?",
                selectionArgs = arrayOf(record.dedupeKey),
                mapper = ::readOutbox,
            )?.let { existing ->
                return@inNonExclusiveTransaction BridgeEnqueueResult(
                    enqueued = false,
                    record = existing,
                )
            }
            val atCapacity =
                rawQuery(
                    """
                    SELECT 1
                    FROM ${BotDbSchema.TABLE_BRIDGE_OUTBOX}
                    WHERE state IN ('pending','leased')
                    LIMIT 1 OFFSET ?
                    """.trimIndent(),
                    arrayOf((BotDatabaseLimits.MAX_ACTIVE_OUTBOX_ROWS - 1).toString()),
                ).use(Cursor::moveToFirst)
            check(!atCapacity) { "local_outbox_capacity_exceeded" }
            val insertedId = insertWithOnConflict(
                BotDbSchema.TABLE_BRIDGE_OUTBOX,
                null,
                outboxValues(record, includeId = false),
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (insertedId >= 0L) {
                BridgeEnqueueResult(
                    enqueued = true,
                    record = record.copy(databaseId = insertedId),
                )
            } else {
                // Another thread committed the same dedupe key between the precheck and insert.
                val existing = queryOne(
                    table = BotDbSchema.TABLE_BRIDGE_OUTBOX,
                    columns = OUTBOX_COLUMNS,
                    selection = "dedupe_key = ?",
                    selectionArgs = arrayOf(record.dedupeKey),
                    mapper = ::readOutbox,
                ) ?: error("Outbox dedupe conflict without a persisted row")
                BridgeEnqueueResult(enqueued = false, record = existing)
            }
        }
        if (result.enqueued) afterWrite(record.createdAt)
        return result
    }

    /**
     * Earliest real wake-up for the durable dispatcher. A leased row is not
     * eligible before its lease expires, even when its original available_at
     * is older. This single indexed aggregate replaces any fixed outbox poll.
     */
    fun nextBridgeOperationDueAt(): Long? {
        helper.readableDatabase.rawQuery(
            """
            SELECT MIN(
                CASE
                    WHEN state = 'leased'
                        THEN MAX(available_at, COALESCE(lease_until, available_at))
                    ELSE available_at
                END
            )
            FROM ${BotDbSchema.TABLE_BRIDGE_OUTBOX}
            WHERE state IN ('pending','leased')
            """.trimIndent(),
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    fun claimBridgeOperations(
        leaseOwner: String,
        now: Long = System.currentTimeMillis(),
        leaseDurationMs: Long,
        limit: Int = 20,
    ): List<BridgeOutboxRecord> {
        requireShortId(leaseOwner, "leaseOwner")
        require(leaseDurationMs in 1_000L..MAX_LEASE_DURATION_MS) {
            "leaseDurationMs is outside the supported range"
        }
        val leaseUntil = now + leaseDurationMs
        val safeLimit = queryLimit(limit)
        val db = helper.writableDatabase
        val claimed = db.inNonExclusiveTransaction {
            val candidates = queryList(
                table = BotDbSchema.TABLE_BRIDGE_OUTBOX,
                columns = OUTBOX_COLUMNS,
                selection = """
                    available_at <= ?
                    AND (
                        state = 'pending'
                        OR (state = 'leased' AND lease_until IS NOT NULL AND lease_until <= ?)
                    )
                """.trimIndent(),
                selectionArgs = arrayOf(now.toString(), now.toString()),
                orderBy = "priority DESC, available_at, id",
                limit = safeLimit.toString(),
                mapper = ::readOutbox,
            )
            buildList(candidates.size) {
                candidates.forEach { candidate ->
                    val changed = update(
                        BotDbSchema.TABLE_BRIDGE_OUTBOX,
                        ContentValues(6).apply {
                            put("state", BridgeOutboxState.LEASED.databaseValue)
                            put("lease_owner", leaseOwner)
                            put("lease_until", leaseUntil)
                            put("attempt_count", candidate.attemptCount + 1)
                            putNull("last_error")
                            put("updated_at", now)
                        },
                        """
                        id = ?
                        AND available_at <= ?
                        AND (
                            state = 'pending'
                            OR (state = 'leased' AND lease_until IS NOT NULL AND lease_until <= ?)
                        )
                        """.trimIndent(),
                        arrayOf(
                            candidate.databaseId.toString(),
                            now.toString(),
                            now.toString(),
                        ),
                    )
                    if (changed == 1) {
                        add(
                            candidate.copy(
                                state = BridgeOutboxState.LEASED,
                                attemptCount = candidate.attemptCount + 1,
                                leaseOwner = leaseOwner,
                                leaseUntil = leaseUntil,
                                lastError = null,
                                updatedAt = now,
                            ),
                        )
                    }
                }
            }
        }
        if (claimed.isNotEmpty()) afterWrite(now, claimed.size)
        return claimed
    }

    fun completeBridgeOperation(
        databaseId: Long,
        leaseOwner: String,
        resultJson: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = transitionLeasedOutbox(
        databaseId = databaseId,
        leaseOwner = leaseOwner,
        state = BridgeOutboxState.COMPLETED,
        availableAt = null,
        lastError = null,
        resultJson = resultJson,
        now = now,
    )

    fun retryBridgeOperation(
        databaseId: Long,
        leaseOwner: String,
        availableAt: Long,
        error: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = transitionLeasedOutbox(
        databaseId = databaseId,
        leaseOwner = leaseOwner,
        state = BridgeOutboxState.PENDING,
        availableAt = availableAt,
        lastError = error,
        resultJson = null,
        now = now,
    )

    fun deadLetterBridgeOperation(
        databaseId: Long,
        leaseOwner: String,
        error: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = transitionLeasedOutbox(
        databaseId = databaseId,
        leaseOwner = leaseOwner,
        state = BridgeOutboxState.DEAD,
        availableAt = null,
        lastError = error,
        resultJson = null,
        now = now,
    )

    /** Returns this process's unfinished leases immediately on an orderly shutdown. */
    fun releaseBridgeOperations(
        leaseOwner: String,
        now: Long = System.currentTimeMillis(),
    ): Int {
        requireShortId(leaseOwner, "leaseOwner")
        val changed = helper.writableDatabase.update(
            BotDbSchema.TABLE_BRIDGE_OUTBOX,
            ContentValues(4).apply {
                put("state", BridgeOutboxState.PENDING.databaseValue)
                putNull("lease_owner")
                putNull("lease_until")
                put("updated_at", now)
            },
            "state = ? AND lease_owner = ?",
            arrayOf(BridgeOutboxState.LEASED.databaseValue, leaseOwner),
        )
        if (changed > 0) afterWrite(now, changed)
        return changed
    }

    /**
     * Completed outbox rows for [operations], oldest first, that settled at or after [since].
     *
     * The outbox is the only durable proof that a message left the phone; the chat log is written
     * by the turn that asked for it. Those are two different places, so a process that dies between
     * them — or a row the dispatcher finishes on recovery, with no turn left waiting for it — puts a
     * message on the contact's screen that the bot has no record of ever sending. This is the query
     * that finds those rows again.
     */
    fun listCompletedBridgeOperations(
        operations: Set<String>,
        since: Long,
        limit: Int = 200,
        afterUpdatedAt: Long? = null,
        afterId: Long? = null,
    ): List<BridgeOutboxRecord> {
        require((afterUpdatedAt == null) == (afterId == null)) {
            "Completed outbox cursor fields must be supplied together"
        }
        val wanted = operations.filter(String::isNotBlank).distinct()
        if (wanted.isEmpty()) return emptyList()
        val placeholders = wanted.joinToString(",") { "?" }
        val cursorSelection =
            if (afterUpdatedAt == null) {
                ""
            } else {
                "AND (updated_at > ? OR (updated_at = ? AND id > ?))"
            }
        val cursorArgs =
            if (afterUpdatedAt == null) {
                emptyList()
            } else {
                listOf(afterUpdatedAt.toString(), afterUpdatedAt.toString(), afterId.toString())
            }
        return helper.readableDatabase.queryList(
            table = BotDbSchema.TABLE_BRIDGE_OUTBOX,
            columns = OUTBOX_COLUMNS,
            selection =
                "state = ? AND updated_at >= ? AND operation IN ($placeholders) $cursorSelection",
            selectionArgs =
                (listOf(BridgeOutboxState.COMPLETED.databaseValue, since.toString()) +
                    wanted + cursorArgs)
                    .toTypedArray(),
            orderBy = "updated_at, id",
            limit = queryLimit(limit).toString(),
            mapper = ::readOutbox,
        )
    }

    // endregion

    // region Bounded maintenance

    /** Folds the WAL back into the database file so a backup cannot miss the most recent writes. */
    fun checkpointForBackup() {
        synchronized(maintenanceLock) {
            helper.writableDatabase
                .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { it.moveToFirst() }
        }
    }

    private fun afterWrite(
        @Suppress("UNUSED_PARAMETER") writeOccurredAt: Long,
        weight: Int = 1,
    ) {
        val now = System.currentTimeMillis()
        val accumulated = writesSinceMaintenance.addAndGet(max(1, weight))
        if (accumulated < BotDatabaseLimits.MAINTENANCE_EVERY_WRITES) return
        if (!maintenanceScheduled.compareAndSet(false, true)) return
        try {
            maintenanceExecutor.execute {
                try {
                    synchronized(maintenanceLock) {
                        if (writesSinceMaintenance.get() < BotDatabaseLimits.MAINTENANCE_EVERY_WRITES) {
                            return@synchronized
                        }
                        writesSinceMaintenance.set(0)
                        val db = helper.writableDatabase
                        val lastMaintenance = readMetaLong(db, META_LAST_MAINTENANCE_AT) ?: 0L
                        if (now - lastMaintenance >= BotDatabaseLimits.MAINTENANCE_MIN_INTERVAL_MS) {
                            performMaintenanceLocked(db, now)
                        }
                    }
                } finally {
                    maintenanceScheduled.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            maintenanceScheduled.set(false)
        }
    }

    private fun performMaintenanceLocked(
        db: SQLiteDatabase,
        now: Long,
    ): MaintenanceResult = db.inNonExclusiveTransaction {
        var processedEventsDeleted = executeChangedRows(
            "DELETE FROM ${BotDbSchema.TABLE_PROCESSED_EVENTS} WHERE expires_at <= ?",
            listOf(now),
        )
        processedEventsDeleted += executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_PROCESSED_EVENTS}
            WHERE event_id IN (
                SELECT event_id
                FROM ${BotDbSchema.TABLE_PROCESSED_EVENTS}
                ORDER BY received_at DESC, event_id DESC
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            listOf(BotDatabaseLimits.MAX_PROCESSED_EVENTS),
        )

        var messagesDeleted = 0
        val oversizedConversations = rawQuery(
            """
            SELECT conversation_key
            FROM ${BotDbSchema.TABLE_MESSAGES}
            WHERE conversation_key IS NOT NULL
            GROUP BY conversation_key
            HAVING COUNT(*) > ?
            LIMIT 128
            """.trimIndent(),
            arrayOf(BotDatabaseLimits.MAX_MESSAGES_PER_CHAT.toString()),
        ).use { cursor ->
            buildList(cursor.count.coerceAtLeast(0)) {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        oversizedConversations.forEach { conversationKey ->
            messagesDeleted += executeChangedRows(
                """
                DELETE FROM ${BotDbSchema.TABLE_MESSAGES}
                WHERE id IN (
                    SELECT id
                    FROM ${BotDbSchema.TABLE_MESSAGES}
                    WHERE conversation_key = ?
                      AND message_type != ?
                    ORDER BY occurred_at DESC, id DESC
                    LIMIT -1 OFFSET ?
                )
                """.trimIndent(),
                listOf(
                    conversationKey,
                    SCHEDULED_FOLLOW_UP_MESSAGE_TYPE,
                    BotDatabaseLimits.MAX_MESSAGES_PER_CHAT,
                ),
            )
        }
        messagesDeleted += executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_MESSAGES}
            WHERE id IN (
                SELECT id
                FROM ${BotDbSchema.TABLE_MESSAGES}
                WHERE message_type != ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            listOf(SCHEDULED_FOLLOW_UP_MESSAGE_TYPE, BotDatabaseLimits.MAX_MESSAGES_TOTAL),
        )

        var mediaDeleted = executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_MEDIA_ANALYSIS_CACHE}
            WHERE expires_at IS NOT NULL AND expires_at <= ?
            """.trimIndent(),
            listOf(now),
        )
        mediaDeleted += executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_MEDIA_ANALYSIS_CACHE}
            WHERE (content_hash, analyzer, analyzer_version) IN (
                SELECT content_hash, analyzer, analyzer_version
                FROM ${BotDbSchema.TABLE_MEDIA_ANALYSIS_CACHE}
                ORDER BY last_accessed_at DESC, created_at DESC
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            listOf(BotDatabaseLimits.MAX_MEDIA_ANALYSES),
        )

        var outboundDeleted = executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_OUTBOUND_SAFETY}
            WHERE expires_at IS NOT NULL AND expires_at <= ?
            """.trimIndent(),
            listOf(now),
        )
        outboundDeleted += executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_OUTBOUND_SAFETY}
            WHERE id IN (
                SELECT id
                FROM ${BotDbSchema.TABLE_OUTBOUND_SAFETY}
                ORDER BY planned_at DESC, id DESC
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            listOf(BotDatabaseLimits.MAX_OUTBOUND_LEDGER_ROWS),
        )

        val activityDeleted = executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_ACTIVITY_LOG}
            WHERE id IN (
                SELECT id
                FROM ${BotDbSchema.TABLE_ACTIVITY_LOG}
                ORDER BY occurred_at DESC, id DESC
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            listOf(BotDatabaseLimits.MAX_ACTIVITY_ROWS),
        )

        executeChangedRows(
            """
            UPDATE ${BotDbSchema.TABLE_BRIDGE_OUTBOX}
            SET state = 'dead',
                lease_owner = NULL,
                lease_until = NULL,
                last_error = 'local_outbox_capacity_exceeded',
                updated_at = ?
            WHERE id IN (
                SELECT id
                FROM ${BotDbSchema.TABLE_BRIDGE_OUTBOX}
                WHERE state = 'pending'
                ORDER BY priority DESC, created_at, id
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            listOf(now, BotDatabaseLimits.MAX_ACTIVE_OUTBOX_ROWS),
        )
        val outboxDeleted = executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_BRIDGE_OUTBOX}
            WHERE id IN (
                SELECT id
                FROM ${BotDbSchema.TABLE_BRIDGE_OUTBOX}
                WHERE state IN ('completed','dead')
                ORDER BY updated_at DESC, id DESC
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            listOf(BotDatabaseLimits.MAX_COMPLETED_OUTBOX_ROWS),
        )

        putMeta(this, META_LAST_MAINTENANCE_AT, now.toString())
        MaintenanceResult(
            processedEventsDeleted = processedEventsDeleted,
            messagesDeleted = messagesDeleted,
            mediaEntriesDeleted = mediaDeleted,
            outboundLedgerRowsDeleted = outboundDeleted,
            activityRowsDeleted = activityDeleted,
            outboxRowsDeleted = outboxDeleted,
        )
    }

    // endregion

    override fun close() {
        maintenanceExecutor.shutdown()
        if (!maintenanceExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            maintenanceExecutor.shutdownNow()
            maintenanceExecutor.awaitTermination(2, TimeUnit.SECONDS)
        }
        helper.close()
    }

    // region Internal writes

    private fun upsertChat(db: SQLiteDatabase, chat: ChatRecord) {
        val insertValues = chatValues(chat, includeCreatedAt = true)
        val updateValues = chatValues(chat, includeCreatedAt = false)
        upsertWithoutRowId(
            db = db,
            table = BotDbSchema.TABLE_CHATS,
            insertValues = insertValues,
            updateValues = updateValues,
            where = "chat_id = ?",
            whereArgs = arrayOf(chat.chatId),
        )
    }

    private fun ensureChatStub(
        db: SQLiteDatabase,
        chatId: String,
        now: Long,
    ) {
        db.executeChangedRows(
            """
            INSERT OR IGNORE INTO ${BotDbSchema.TABLE_CHATS}(
                chat_id, kind, unread_count, archived, created_at, updated_at
            ) VALUES(?, 'unknown', 0, 0, ?, ?)
            """.trimIndent(),
            listOf(chatId, now, now),
        )
    }

    private fun insertProcessedEvent(
        db: SQLiteDatabase,
        event: ProcessedEventRecord,
    ): Int {
        db.executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_PROCESSED_EVENTS}
            WHERE event_id = ? AND expires_at <= ?
            """.trimIndent(),
            listOf(event.eventId, event.receivedAt),
        )
        return db.executeChangedRows(
            """
            INSERT OR IGNORE INTO ${BotDbSchema.TABLE_PROCESSED_EVENTS}(
                event_id, source, event_type, chat_id, provider_message_id,
                payload_hash, received_at, expires_at
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                event.eventId,
                event.source,
                event.eventType,
                event.chatId,
                event.providerMessageId,
                event.payloadHash,
                event.receivedAt,
                event.expiresAt,
            ),
        )
    }

    private fun insertMessages(
        db: SQLiteDatabase,
        messages: Collection<MessageRecord>,
    ): Int {
        if (messages.isEmpty()) return 0
        val chatUpdates = LinkedHashMap<String, ChatWriteSummary>()
        val firstChatWrites = LinkedHashMap<String, Long>()
        messages.forEach { message ->
            firstChatWrites.putIfAbsent(message.chatId, message.receivedAt)
        }
        firstChatWrites.forEach { (chatId, firstTimestamp) ->
            ensureChatStub(db, chatId, firstTimestamp)
        }

        var inserted = 0
        messages.forEach { message ->
            val rowId = db.insertWithOnConflict(
                BotDbSchema.TABLE_MESSAGES,
                null,
                messageValues(message, includeId = false),
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (rowId >= 0L) {
                inserted++
                val current = chatUpdates[message.chatId]
                chatUpdates[message.chatId] = ChatWriteSummary(
                    latestOccurredAt = max(
                        current?.latestOccurredAt ?: Long.MIN_VALUE,
                        message.occurredAt,
                    ),
                    latestReceivedAt = max(
                        current?.latestReceivedAt ?: Long.MIN_VALUE,
                        message.receivedAt,
                    ),
                    inboundCount = (current?.inboundCount ?: 0) +
                        if (message.direction == MessageDirection.INBOUND) 1 else 0,
                )
            }
        }
        chatUpdates.forEach { (chatId, summary) ->
            db.execSQL(
                """
                UPDATE ${BotDbSchema.TABLE_CHATS}
                SET last_message_at = CASE
                        WHEN last_message_at IS NULL OR last_message_at < ?
                            THEN ?
                        ELSE last_message_at
                    END,
                    unread_count = unread_count + ?,
                    updated_at = CASE WHEN updated_at < ? THEN ? ELSE updated_at END
                WHERE chat_id = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    summary.latestOccurredAt,
                    summary.latestOccurredAt,
                    summary.inboundCount,
                    summary.latestReceivedAt,
                    summary.latestReceivedAt,
                    chatId,
                ),
            )
        }
        return inserted
    }

    private fun transitionLeasedOutbox(
        databaseId: Long,
        leaseOwner: String,
        state: BridgeOutboxState,
        availableAt: Long?,
        lastError: String?,
        resultJson: String?,
        now: Long,
    ): Boolean {
        require(databaseId > 0) { "databaseId must be positive" }
        requireShortId(leaseOwner, "leaseOwner")
        require(state != BridgeOutboxState.LEASED) { "Use claimBridgeOperations to lease work" }
        require(lastError == null || lastError.length <= MAX_ERROR_CHARS) {
            "lastError is too large"
        }
        require(resultJson == null || resultJson.length <= MAX_OUTBOX_RESULT_CHARS) {
            "resultJson is too large"
        }
        val values = ContentValues(7).apply {
            put("state", state.databaseValue)
            putNull("lease_owner")
            putNull("lease_until")
            if (availableAt != null) put("available_at", availableAt)
            put("last_error", lastError)
            put("result_json", resultJson)
            put("updated_at", now)
        }
        val changed = helper.writableDatabase.update(
            BotDbSchema.TABLE_BRIDGE_OUTBOX,
            values,
            "id = ? AND state = 'leased' AND lease_owner = ?",
            arrayOf(databaseId.toString(), leaseOwner),
        )
        if (changed > 0) afterWrite(now)
        return changed > 0
    }

    private fun upsertWithoutRowId(
        db: SQLiteDatabase,
        table: String,
        insertValues: ContentValues,
        updateValues: ContentValues,
        where: String,
        whereArgs: Array<String>,
    ) {
        if (db.update(table, updateValues, where, whereArgs) > 0) return
        try {
            db.insertOrThrow(table, null, insertValues)
        } catch (error: SQLiteConstraintException) {
            // A concurrent writer inserted the logical key between UPDATE and INSERT.
            if (db.update(table, updateValues, where, whereArgs) == 0) {
                throw error
            }
        }
    }

    private fun putMeta(
        db: SQLiteDatabase,
        key: String,
        value: String,
    ) {
        val values = ContentValues(2).apply {
            put("meta_key", key)
            put("meta_value", value)
        }
        upsertWithoutRowId(
            db = db,
            table = BotDbSchema.TABLE_DB_META,
            insertValues = values,
            updateValues = values,
            where = "meta_key = ?",
            whereArgs = arrayOf(key),
        )
    }

    private fun incrementSettingsRevision(
        db: SQLiteDatabase,
        knownCurrentRevision: Long? = null,
    ): Long {
        val current = knownCurrentRevision
            ?: readMetaLong(db, META_SETTINGS_REVISION)
            ?: 0L
        check(current < Long.MAX_VALUE) { "Settings revision is exhausted" }
        val next = current + 1L
        putMeta(db, META_SETTINGS_REVISION, next.toString())
        return next
    }

    private fun ensureSettingsWithinLimit(db: SQLiteDatabase) {
        db.rawQuery(
            """
            SELECT 1
            FROM ${BotDbSchema.TABLE_SCOPED_SETTINGS}
            LIMIT 1 OFFSET ?
            """.trimIndent(),
            arrayOf(BotDatabaseLimits.MAX_SCOPED_SETTINGS.toString()),
        ).use { cursor ->
            check(!cursor.moveToFirst()) {
                "Scoped settings exceed the ${BotDatabaseLimits.MAX_SCOPED_SETTINGS}-row limit"
            }
        }
    }

    private fun readAllSettingsRows(db: SQLiteDatabase): List<ScopedSettingRecord> {
        val settings = db.queryList(
            table = BotDbSchema.TABLE_SCOPED_SETTINGS,
            columns = SETTING_COLUMNS,
            selection = null,
            selectionArgs = null,
            orderBy = "scope_type, scope_id, setting_key",
            limit = (BotDatabaseLimits.MAX_SCOPED_SETTINGS + 1).toString(),
            mapper = ::readSetting,
        )
        check(settings.size <= BotDatabaseLimits.MAX_SCOPED_SETTINGS) {
            "Scoped settings table exceeds its hard limit"
        }
        return settings
    }

    private fun readMetaLong(
        db: SQLiteDatabase,
        key: String,
    ): Long? {
        db.query(
            BotDbSchema.TABLE_DB_META,
            arrayOf("meta_value"),
            "meta_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() else null
        }
    }

    // endregion

    // region ContentValues

    private fun chatValues(
        chat: ChatRecord,
        includeCreatedAt: Boolean,
    ) = ContentValues(11).apply {
        put("chat_id", chat.chatId)
        put("kind", chat.kind.databaseValue)
        putNullable("display_name", chat.displayName)
        putNullable("subject", chat.subject)
        putNullable("metadata_json", chat.metadataJson)
        putNullable("last_message_at", chat.lastMessageAt)
        put("unread_count", chat.unreadCount)
        put("archived", chat.archived.asDatabaseInt())
        putNullable("ai_disclosure_sent_at", chat.aiDisclosureSentAt)
        if (includeCreatedAt) put("created_at", chat.createdAt)
        put("updated_at", chat.updatedAt)
    }

    private fun messageValues(
        message: MessageRecord,
        includeId: Boolean,
    ) = ContentValues(18).apply {
        if (includeId && message.databaseId > 0) put("id", message.databaseId)
        put("provider_message_id", message.providerMessageId)
        putNullable("event_id", message.eventId)
        put("chat_id", message.chatId)
        putNullable("conversation_key", message.indexedConversationKey())
        putNullable("sender_id", message.senderId)
        put("direction", message.direction.databaseValue)
        put("message_type", message.messageType)
        putNullable("body", message.body)
        putNullable("quoted_provider_message_id", message.quotedProviderMessageId)
        putNullable("media_key", message.mediaKey)
        put("occurred_at", message.occurredAt)
        put("received_at", message.receivedAt)
        put("delivery_state", message.deliveryState.databaseValue)
        put("from_admin", message.fromAdmin.asDatabaseInt())
        putNullable("metadata_json", message.metadataJson)
    }

    private fun MessageRecord.indexedConversationKey(): String? {
        conversationKey?.trim()?.takeIf {
            it.length in 1..1_024 && it.substringBeforeLast('#') == chatId
        }?.let { return it }
        val metadata = metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val exact = metadata.optString("conversationKey").trim()
        if (exact.length in 1..1_024 && exact.substringBeforeLast('#') == chatId) return exact
        val persona = metadata.optString("persona").trim()
        return persona.takeIf { it.length in 1..128 }?.let { "$chatId#$it" }
    }

    private fun chatMemoryValues(memory: ChatMemoryRecord) = ContentValues(9).apply {
        put("conversation_key", memory.conversationKey)
        put("chat_id", memory.chatId)
        put("persona_id", memory.conversationKey.substringAfterLast('#'))
        put("summary", memory.summary)
        putNullable("facts_json", memory.factsJson)
        putNullable("last_provider_message_id", memory.lastProviderMessageId)
        put("source_message_count", memory.sourceMessageCount)
        put("revision", memory.revision)
        put("updated_at", memory.updatedAt)
    }

    private fun archiveChatMemory(db: SQLiteDatabase, memory: ChatMemoryRecord) {
        db.insertWithOnConflict(
            BotDbSchema.TABLE_CHAT_MEMORY_HISTORY,
            null,
            chatMemoryValues(memory),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        db.executeChangedRows(
            """
            DELETE FROM ${BotDbSchema.TABLE_CHAT_MEMORY_HISTORY}
            WHERE conversation_key = ?
              AND revision NOT IN (
                  SELECT revision
                  FROM ${BotDbSchema.TABLE_CHAT_MEMORY_HISTORY}
                  WHERE conversation_key = ?
                  ORDER BY revision DESC
                  LIMIT ?
              )
            """.trimIndent(),
            listOf(
                memory.conversationKey,
                memory.conversationKey,
                MAX_MEMORY_HISTORY_REVISIONS,
            ),
        )
    }

    private fun personaValues(
        persona: PersonaRecord,
        includeCreatedAt: Boolean,
    ) = ContentValues(9).apply {
        put("persona_id", persona.personaId)
        put("name", persona.name)
        putNullable("description", persona.description)
        put("system_prompt", persona.systemPrompt)
        putNullable("traits_json", persona.traitsJson)
        putNullable("voice_config_json", persona.voiceConfigJson)
        put("enabled", persona.enabled.asDatabaseInt())
        if (includeCreatedAt) put("created_at", persona.createdAt)
        put("updated_at", persona.updatedAt)
    }

    private fun accessValues(
        entry: AccessEntryRecord,
        includeCreatedAt: Boolean,
    ) = ContentValues(7).apply {
        put("list_kind", entry.listKind.databaseValue)
        put("subject_type", entry.subjectType.databaseValue)
        put("subject_id", entry.subjectId)
        putNullable("label", entry.label)
        put("enabled", entry.enabled.asDatabaseInt())
        if (includeCreatedAt) put("created_at", entry.createdAt)
        put("updated_at", entry.updatedAt)
    }

    private fun settingValues(setting: ScopedSettingRecord) = ContentValues(6).apply {
        put("scope_type", setting.scopeType)
        put("scope_id", setting.scopeId)
        put("setting_key", setting.key)
        put("setting_value", setting.value)
        put("value_type", setting.valueType.databaseValue)
        put("updated_at", setting.updatedAt)
    }

    private fun proactiveValues(state: ProactiveStateRecord) = ContentValues(14).apply {
        put("chat_id", state.chatId)
        put("enabled", state.enabled.asDatabaseInt())
        putNullable("next_due_at", state.nextDueAt)
        putNullable("cooldown_until", state.cooldownUntil)
        putNullable("last_inbound_at", state.lastInboundAt)
        putNullable("last_outbound_at", state.lastOutboundAt)
        putNullable("daily_window_started_at", state.dailyWindowStartedAt)
        put("daily_outbound_count", state.dailyOutboundCount)
        put("consecutive_failures", state.consecutiveFailures)
        putNullable("lease_owner", state.leaseOwner)
        putNullable("lease_until", state.leaseUntil)
        putNullable("state_json", state.stateJson)
        putNullable("cold_outreach_at", state.coldOutreachAt)
        put("updated_at", state.updatedAt)
    }

    private fun mediaAnalysisValues(analysis: MediaAnalysisRecord) = ContentValues(9).apply {
        put("content_hash", analysis.contentHash)
        put("analyzer", analysis.analyzer)
        put("analyzer_version", analysis.analyzerVersion)
        put("media_type", analysis.mediaType)
        put("result_json", analysis.resultJson)
        putNullable("byte_size", analysis.byteSize)
        put("created_at", analysis.createdAt)
        put("last_accessed_at", analysis.lastAccessedAt)
        putNullable("expires_at", analysis.expiresAt)
    }

    private fun outboundValues(
        record: OutboundSafetyRecord,
        includeId: Boolean,
    ) = ContentValues(12).apply {
        if (includeId && record.databaseId > 0) put("id", record.databaseId)
        put("dedupe_key", record.dedupeKey)
        putNullable("chat_id", record.chatId)
        put("outbound_kind", record.outboundKind)
        put("decision", record.decision.databaseValue)
        put("reason_code", record.reasonCode)
        put("status", record.status.databaseValue)
        putNullable("payload_hash", record.payloadHash)
        put("planned_at", record.plannedAt)
        putNullable("committed_at", record.committedAt)
        putNullable("expires_at", record.expiresAt)
        putNullable("metadata_json", record.metadataJson)
    }

    private fun activityValues(
        record: ActivityLogRecord,
        includeId: Boolean,
    ) = ContentValues(9).apply {
        if (includeId && record.databaseId > 0) put("id", record.databaseId)
        put("occurred_at", record.occurredAt)
        put("level", record.level.databaseValue)
        put("category", record.category)
        put("action", record.action)
        putNullable("chat_id", record.chatId)
        putNullable("correlation_id", record.correlationId)
        put("summary", record.summary)
        putNullable("details_json", record.detailsJson)
    }

    private fun outboxValues(
        record: BridgeOutboxRecord,
        includeId: Boolean,
    ) = ContentValues(15).apply {
        if (includeId && record.databaseId > 0) put("id", record.databaseId)
        put("dedupe_key", record.dedupeKey)
        put("operation", record.operation)
        putNullable("chat_id", record.chatId)
        put("payload_json", record.payloadJson)
        put("priority", record.priority)
        put("state", record.state.databaseValue)
        put("attempt_count", record.attemptCount)
        put("available_at", record.availableAt)
        putNullable("lease_owner", record.leaseOwner)
        putNullable("lease_until", record.leaseUntil)
        putNullable("last_error", record.lastError)
        putNullable("result_json", record.resultJson)
        put("created_at", record.createdAt)
        put("updated_at", record.updatedAt)
    }

    // endregion

    // region Cursor mapping

    private fun readChat(cursor: Cursor) = ChatRecord(
        chatId = cursor.getString(0),
        kind = ChatKind.fromDatabase(cursor.getString(1)),
        displayName = cursor.stringOrNull(2),
        subject = cursor.stringOrNull(3),
        metadataJson = cursor.stringOrNull(4),
        lastMessageAt = cursor.longOrNull(5),
        unreadCount = cursor.getInt(6),
        archived = cursor.getInt(7) != 0,
        aiDisclosureSentAt = cursor.longOrNull(8),
        createdAt = cursor.getLong(9),
        updatedAt = cursor.getLong(10),
    )

    private fun readMessage(cursor: Cursor) = MessageRecord(
        databaseId = cursor.getLong(0),
        providerMessageId = cursor.getString(1),
        eventId = cursor.stringOrNull(2),
        chatId = cursor.getString(3),
        conversationKey = cursor.stringOrNull(4),
        senderId = cursor.stringOrNull(5),
        direction = MessageDirection.fromDatabase(cursor.getString(6)),
        messageType = cursor.getString(7),
        body = cursor.stringOrNull(8),
        quotedProviderMessageId = cursor.stringOrNull(9),
        mediaKey = cursor.stringOrNull(10),
        occurredAt = cursor.getLong(11),
        receivedAt = cursor.getLong(12),
        deliveryState = MessageDeliveryState.fromDatabase(cursor.getString(13)),
        fromAdmin = cursor.getInt(14) != 0,
        metadataJson = cursor.stringOrNull(15),
    )

    private fun readChatMemory(cursor: Cursor) = ChatMemoryRecord(
        conversationKey = cursor.getString(0),
        chatId = cursor.getString(1),
        summary = cursor.getString(2),
        factsJson = cursor.stringOrNull(3),
        lastProviderMessageId = cursor.stringOrNull(4),
        sourceMessageCount = cursor.getInt(5),
        revision = cursor.getLong(6),
        updatedAt = cursor.getLong(7),
    )

    private fun readPersona(cursor: Cursor) = PersonaRecord(
        personaId = cursor.getString(0),
        name = cursor.getString(1),
        description = cursor.stringOrNull(2),
        systemPrompt = cursor.getString(3),
        traitsJson = cursor.stringOrNull(4),
        voiceConfigJson = cursor.stringOrNull(5),
        enabled = cursor.getInt(6) != 0,
        createdAt = cursor.getLong(7),
        updatedAt = cursor.getLong(8),
    )

    private fun readPersonaMemory(cursor: Cursor) = PersonaMemoryRecord(
        personaId = cursor.getString(0),
        summary = cursor.getString(1),
        factsJson = cursor.stringOrNull(2),
        revision = cursor.getLong(3),
        lastChatWriteCount = cursor.getLong(4),
        updatedAt = cursor.getLong(5),
    )

    private fun readAccess(cursor: Cursor) = AccessEntryRecord(
        listKind = AccessListKind.fromDatabase(cursor.getString(0)),
        subjectType = AccessSubjectType.fromDatabase(cursor.getString(1)),
        subjectId = cursor.getString(2),
        label = cursor.stringOrNull(3),
        enabled = cursor.getInt(4) != 0,
        createdAt = cursor.getLong(5),
        updatedAt = cursor.getLong(6),
    )

    private fun readSetting(cursor: Cursor) = ScopedSettingRecord(
        scopeType = cursor.getString(0),
        scopeId = cursor.getString(1),
        key = cursor.getString(2),
        value = cursor.getString(3),
        valueType = StoredSettingValueType.fromDatabase(cursor.getString(4)),
        updatedAt = cursor.getLong(5),
    )

    private fun readProactive(cursor: Cursor) = ProactiveStateRecord(
        chatId = cursor.getString(0),
        enabled = cursor.getInt(1) != 0,
        nextDueAt = cursor.longOrNull(2),
        cooldownUntil = cursor.longOrNull(3),
        lastInboundAt = cursor.longOrNull(4),
        lastOutboundAt = cursor.longOrNull(5),
        dailyWindowStartedAt = cursor.longOrNull(6),
        dailyOutboundCount = cursor.getInt(7),
        consecutiveFailures = cursor.getInt(8),
        leaseOwner = cursor.stringOrNull(9),
        leaseUntil = cursor.longOrNull(10),
        stateJson = cursor.stringOrNull(11),
        coldOutreachAt = cursor.longOrNull(12),
        updatedAt = cursor.getLong(13),
    )

    private fun readMediaAnalysis(cursor: Cursor) = MediaAnalysisRecord(
        contentHash = cursor.getString(0),
        analyzer = cursor.getString(1),
        analyzerVersion = cursor.getString(2),
        mediaType = cursor.getString(3),
        resultJson = cursor.getString(4),
        byteSize = cursor.longOrNull(5),
        createdAt = cursor.getLong(6),
        lastAccessedAt = cursor.getLong(7),
        expiresAt = cursor.longOrNull(8),
    )

    private fun readOutbound(cursor: Cursor) = OutboundSafetyRecord(
        databaseId = cursor.getLong(0),
        dedupeKey = cursor.getString(1),
        chatId = cursor.stringOrNull(2),
        outboundKind = cursor.getString(3),
        decision = OutboundDecision.fromDatabase(cursor.getString(4)),
        reasonCode = cursor.getString(5),
        status = OutboundStatus.fromDatabase(cursor.getString(6)),
        payloadHash = cursor.stringOrNull(7),
        plannedAt = cursor.getLong(8),
        committedAt = cursor.longOrNull(9),
        expiresAt = cursor.longOrNull(10),
        metadataJson = cursor.stringOrNull(11),
    )

    private fun readActivity(cursor: Cursor) = ActivityLogRecord(
        databaseId = cursor.getLong(0),
        occurredAt = cursor.getLong(1),
        level = ActivityLevel.fromDatabase(cursor.getString(2)),
        category = cursor.getString(3),
        action = cursor.getString(4),
        chatId = cursor.stringOrNull(5),
        correlationId = cursor.stringOrNull(6),
        summary = cursor.getString(7),
        detailsJson = cursor.stringOrNull(8),
    )

    private fun readOutbox(cursor: Cursor) = BridgeOutboxRecord(
        databaseId = cursor.getLong(0),
        dedupeKey = cursor.getString(1),
        operation = cursor.getString(2),
        chatId = cursor.stringOrNull(3),
        payloadJson = cursor.getString(4),
        priority = cursor.getInt(5),
        state = BridgeOutboxState.fromDatabase(cursor.getString(6)),
        attemptCount = cursor.getInt(7),
        availableAt = cursor.getLong(8),
        leaseOwner = cursor.stringOrNull(9),
        leaseUntil = cursor.longOrNull(10),
        lastError = cursor.stringOrNull(11),
        resultJson = cursor.stringOrNull(12),
        createdAt = cursor.getLong(13),
        updatedAt = cursor.getLong(14),
    )

    // endregion

    // region Validation

    private fun validateChat(chat: ChatRecord) {
        requireExternalId(chat.chatId, "chatId")
        requireOptionalLength(chat.displayName, 512, "displayName")
        requireOptionalLength(chat.subject, 2_048, "subject")
        requireJsonSize(chat.metadataJson, 131_072, "metadataJson")
        require(chat.unreadCount >= 0) { "unreadCount must not be negative" }
        requireOptionalNonNegativeTimestamp(chat.lastMessageAt, "lastMessageAt")
        requireNonNegativeTimestamp(chat.createdAt, "createdAt")
        requireNonNegativeTimestamp(chat.updatedAt, "updatedAt")
    }

    private fun validateMessage(message: MessageRecord) {
        require(message.databaseId >= 0) { "databaseId must not be negative" }
        requireExternalId(message.providerMessageId, "providerMessageId")
        requireOptionalLength(message.eventId, 512, "eventId")
        requireExternalId(message.chatId, "chatId")
        requireOptionalLength(message.conversationKey, 1_024, "conversationKey")
        requireOptionalLength(message.senderId, 512, "senderId")
        requireLength(message.messageType, 1, 64, "messageType")
        requireOptionalLength(message.body, MAX_MESSAGE_CHARS, "body")
        requireOptionalLength(
            message.quotedProviderMessageId,
            512,
            "quotedProviderMessageId",
        )
        requireOptionalLength(message.mediaKey, 512, "mediaKey")
        requireNonNegativeTimestamp(message.occurredAt, "occurredAt")
        requireNonNegativeTimestamp(message.receivedAt, "receivedAt")
        requireJsonSize(message.metadataJson, 262_144, "metadataJson")
    }

    private fun validateEvent(event: ProcessedEventRecord) {
        requireExternalId(event.eventId, "eventId")
        requireLength(event.source, 1, 64, "source")
        requireLength(event.eventType, 1, 128, "eventType")
        requireOptionalLength(event.chatId, 512, "chatId")
        requireOptionalLength(event.providerMessageId, 512, "providerMessageId")
        requireOptionalLength(event.payloadHash, 128, "payloadHash")
        requireNonNegativeTimestamp(event.receivedAt, "receivedAt")
        require(event.expiresAt > event.receivedAt) {
            "expiresAt must be later than receivedAt"
        }
    }

    private fun validatePersona(persona: PersonaRecord) {
        requireLength(persona.personaId, 1, 128, "personaId")
        requireLength(persona.name, 1, 256, "name")
        requireOptionalLength(persona.description, 4_096, "description")
        require(persona.systemPrompt.length <= MAX_MEMORY_CHARS) { "systemPrompt is too large" }
        requireJsonSize(persona.traitsJson, 262_144, "traitsJson")
        requireJsonSize(persona.voiceConfigJson, 131_072, "voiceConfigJson")
        requireNonNegativeTimestamp(persona.createdAt, "createdAt")
        requireNonNegativeTimestamp(persona.updatedAt, "updatedAt")
    }

    private fun validateSetting(setting: ScopedSettingRecord) {
        validateSettingAddress(setting.scopeType, setting.scopeId, setting.key)
        require(setting.value.length <= MAX_SETTING_VALUE_CHARS) { "Setting value is too large" }
        when (setting.valueType) {
            StoredSettingValueType.BOOLEAN -> require(
                setting.value.equals("true", ignoreCase = true) ||
                    setting.value.equals("false", ignoreCase = true),
            ) { "Boolean settings must be true or false" }

            StoredSettingValueType.INTEGER -> require(setting.value.toLongOrNull() != null) {
                "Integer setting is outside Long range or malformed"
            }

            StoredSettingValueType.DECIMAL -> require(
                setting.value.toDoubleOrNull()?.isFinite() == true,
            ) { "Decimal setting must be finite" }

            StoredSettingValueType.SECRET_REFERENCE -> require(
                LOGICAL_SECRET_REFERENCE.matches(setting.value),
            ) {
                "Secret references must be logical Keystore aliases, not credential material"
            }

            StoredSettingValueType.STRING,
            StoredSettingValueType.JSON,
            -> Unit
        }
        val credentialReferenceKey = isCredentialReferenceKey(setting.key)
        require(
            credentialReferenceKey == (setting.valueType == StoredSettingValueType.SECRET_REFERENCE),
        ) {
            "Credential-reference keys and secret_reference values must be used together"
        }
        requireNonNegativeTimestamp(setting.updatedAt, "updatedAt")
    }

    private fun validateSettingAddress(address: SettingAddress) {
        validateSettingAddress(address.scopeType, address.scopeId, address.key)
    }

    private fun validateSettingAddress(
        scopeType: String,
        scopeId: String,
        key: String,
    ) {
        requireLength(scopeType, 1, 64, "scopeType")
        requireLength(scopeId, 0, 512, "scopeId")
        requireLength(key, 1, 128, "key")
        requireSettingKeyIsNotSecret(key)
    }

    private fun requireSettingKeyIsNotSecret(key: String) {
        val normalized = normalizeSettingKey(key)
        val isReference = normalized.endsWith("_ref")
        val inspected = if (isReference) normalized.removeSuffix("_ref") else normalized
        val secretLike = FORBIDDEN_SETTING_SEGMENTS.any { forbidden ->
            inspected == forbidden ||
                inspected.startsWith("${forbidden}_") ||
                inspected.endsWith("_$forbidden") ||
                inspected.contains("_${forbidden}_")
        } ||
            inspected.endsWith("_api_key") ||
            inspected.endsWith("_token") ||
            inspected.endsWith("_password") ||
            inspected.endsWith("_private_key")
        require(!secretLike || isReference) {
            "Sensitive setting '$key' must use a logical *_ref Keystore reference"
        }
    }

    private fun isCredentialReferenceKey(key: String): Boolean {
        val normalized = normalizeSettingKey(key)
        if (!normalized.endsWith("_ref")) return false
        val referencedName = normalized.removeSuffix("_ref")
        return referencedName.endsWith("_api_key") ||
            referencedName.endsWith("_token") ||
            referencedName.endsWith("_secret") ||
            referencedName.endsWith("_password") ||
            referencedName.endsWith("_credential") ||
            referencedName.endsWith("_credentials") ||
            referencedName.endsWith("_private_key")
    }

    private fun normalizeSettingKey(key: String): String =
        key
            .replace(CAMEL_CASE_BOUNDARY, "$1_$2")
            .lowercase()
            .replace(NON_KEY_CHARACTER, "_")
            .trim('_')

    private fun escapeLike(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun validateProactiveState(state: ProactiveStateRecord) {
        requireExternalId(state.chatId, "chatId")
        require(state.dailyOutboundCount >= 0) { "dailyOutboundCount must not be negative" }
        require(state.consecutiveFailures >= 0) { "consecutiveFailures must not be negative" }
        requireOptionalLength(state.leaseOwner, 128, "leaseOwner")
        require((state.leaseOwner == null) == (state.leaseUntil == null)) {
            "leaseOwner and leaseUntil must either both be present or both be null"
        }
        requireOptionalNonNegativeTimestamp(state.nextDueAt, "nextDueAt")
        requireOptionalNonNegativeTimestamp(state.cooldownUntil, "cooldownUntil")
        requireOptionalNonNegativeTimestamp(state.lastInboundAt, "lastInboundAt")
        requireOptionalNonNegativeTimestamp(state.lastOutboundAt, "lastOutboundAt")
        requireOptionalNonNegativeTimestamp(
            state.dailyWindowStartedAt,
            "dailyWindowStartedAt",
        )
        requireOptionalNonNegativeTimestamp(state.leaseUntil, "leaseUntil")
        requireOptionalNonNegativeTimestamp(state.coldOutreachAt, "coldOutreachAt")
        requireJsonSize(state.stateJson, 131_072, "stateJson")
        requireNonNegativeTimestamp(state.updatedAt, "updatedAt")
    }

    private fun validateMediaAnalysis(analysis: MediaAnalysisRecord) {
        requireHash(analysis.contentHash, "contentHash")
        requireLength(analysis.analyzer, 1, 128, "analyzer")
        requireLength(analysis.analyzerVersion, 1, 64, "analyzerVersion")
        requireLength(analysis.mediaType, 1, 128, "mediaType")
        require(analysis.resultJson.length <= MAX_MEDIA_RESULT_CHARS) {
            "Media analysis result is too large"
        }
        require(analysis.byteSize == null || analysis.byteSize >= 0) {
            "byteSize must not be negative"
        }
        requireNonNegativeTimestamp(analysis.createdAt, "createdAt")
        requireNonNegativeTimestamp(analysis.lastAccessedAt, "lastAccessedAt")
        requireOptionalNonNegativeTimestamp(analysis.expiresAt, "expiresAt")
    }

    private fun validateOutbound(record: OutboundSafetyRecord) {
        require(record.databaseId >= 0) { "databaseId must not be negative" }
        requireExternalId(record.dedupeKey, "dedupeKey")
        requireOptionalLength(record.chatId, 512, "chatId")
        requireLength(record.outboundKind, 1, 64, "outboundKind")
        requireLength(record.reasonCode, 1, 128, "reasonCode")
        requireOptionalLength(record.payloadHash, 128, "payloadHash")
        require(record.status == OutboundStatus.RESERVED) {
            "A new outbound reservation must start in RESERVED state"
        }
        requireJsonSize(record.metadataJson, 131_072, "metadataJson")
        requireNonNegativeTimestamp(record.plannedAt, "plannedAt")
        requireOptionalNonNegativeTimestamp(record.committedAt, "committedAt")
        requireOptionalNonNegativeTimestamp(record.expiresAt, "expiresAt")
    }

    private fun validateActivity(record: ActivityLogRecord) {
        require(record.databaseId >= 0) { "databaseId must not be negative" }
        requireNonNegativeTimestamp(record.occurredAt, "occurredAt")
        requireLength(record.category, 1, 128, "category")
        requireLength(record.action, 1, 128, "action")
        requireOptionalLength(record.chatId, 512, "chatId")
        requireOptionalLength(record.correlationId, 512, "correlationId")
        require(record.summary.length <= MAX_ACTIVITY_SUMMARY_CHARS) { "summary is too large" }
        requireJsonSize(record.detailsJson, 262_144, "detailsJson")
    }

    private fun validateOutbox(record: BridgeOutboxRecord) {
        require(record.databaseId >= 0) { "databaseId must not be negative" }
        requireExternalId(record.dedupeKey, "dedupeKey")
        requireLength(record.operation, 1, 128, "operation")
        requireOptionalLength(record.chatId, 512, "chatId")
        require(record.payloadJson.length <= MAX_OUTBOX_PAYLOAD_CHARS) {
            "Outbox payload is too large"
        }
        require(record.priority in -1_000..1_000) { "priority is outside the supported range" }
        require(record.state == BridgeOutboxState.PENDING) {
            "New outbox operations must start in PENDING state"
        }
        require(record.attemptCount == 0) { "New outbox operations must start with zero attempts" }
        require(record.leaseOwner == null && record.leaseUntil == null) {
            "New outbox operations cannot start leased"
        }
        requireOptionalLength(record.lastError, MAX_ERROR_CHARS, "lastError")
        requireJsonSize(record.resultJson, MAX_OUTBOX_RESULT_CHARS, "resultJson")
        requireNonNegativeTimestamp(record.availableAt, "availableAt")
        requireOptionalNonNegativeTimestamp(record.leaseUntil, "leaseUntil")
        requireNonNegativeTimestamp(record.createdAt, "createdAt")
        requireNonNegativeTimestamp(record.updatedAt, "updatedAt")
    }

    private fun requireHash(value: String, name: String) {
        requireLength(value, 16, 128, name)
        require('\u0000' !in value) { "$name contains a NUL character" }
    }

    private fun requireExternalId(value: String, name: String) {
        requireLength(value, 1, 512, name)
        require('\u0000' !in value) { "$name contains a NUL character" }
    }

    private fun requireConversationKey(value: String) {
        requireLength(value, 1, 1_024, "conversationKey")
        require('\u0000' !in value) { "conversationKey contains a NUL character" }
    }

    private fun requireShortId(value: String, name: String) {
        requireLength(value, 1, 128, name)
        require('\u0000' !in value) { "$name contains a NUL character" }
    }

    private fun requireLength(
        value: String,
        minimum: Int,
        maximum: Int,
        name: String,
    ) {
        require(value.length in minimum..maximum) {
            "$name length must be between $minimum and $maximum characters"
        }
    }

    private fun requireOptionalLength(
        value: String?,
        maximum: Int,
        name: String,
    ) {
        require(value == null || value.length <= maximum) { "$name is too large" }
    }

    private fun requireJsonSize(
        value: String?,
        maximum: Int,
        name: String,
    ) {
        requireOptionalLength(value, maximum, name)
    }

    private fun requireNonNegativeTimestamp(value: Long, name: String) {
        require(value >= 0L) { "$name must not be negative" }
    }

    private fun requireOptionalNonNegativeTimestamp(value: Long?, name: String) {
        require(value == null || value >= 0L) { "$name must not be negative" }
    }

    private fun queryLimit(requested: Int): Int {
        require(requested > 0) { "limit must be positive" }
        return requested.coerceAtMost(BotDatabaseLimits.MAX_QUERY_LIMIT)
    }

    private fun nextMonotonicTimestamp(
        current: Long,
        requested: Long,
    ): Long =
        max(requested, if (current == Long.MAX_VALUE) current else current + 1L)

    // endregion

    private data class ChatWriteSummary(
        val latestOccurredAt: Long,
        val latestReceivedAt: Long,
        val inboundCount: Int,
    )

    companion object {
        private const val MAX_MESSAGE_CHARS = 1_048_576
        private const val MAX_SQL_VARIABLES = 500

        /**
         * How far back one receipt may carry its state. A reply is at most a handful of bubbles
         * plus a voice note or an image, so this covers a burst several times over while keeping a
         * single receipt from ever rewriting a whole chat.
         */
        private const val MAX_WATERMARK_CASCADE = 20
        private const val MAX_ROSTER_PREVIEW_CHARS = 1_024
        private const val MAX_MEMORY_CHARS = 1_048_576
        private const val MAX_MEMORY_HISTORY_REVISIONS = 24
        private const val MAX_SETTING_VALUE_CHARS = 65_536
        private const val MAX_MEDIA_RESULT_CHARS = 2_097_152
        private const val MAX_OUTBOX_PAYLOAD_CHARS = 1_048_576
        private const val MAX_OUTBOX_RESULT_CHARS = 262_144
        private const val MAX_ACTIVITY_SUMMARY_CHARS = 4_096
        private const val MAX_ERROR_CHARS = 4_096
        private const val MAX_LEASE_DURATION_MS = 60L * 60L * 1_000L
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1_000L
        private const val META_LAST_MAINTENANCE_AT = "last_maintenance_at"
        private const val META_SETTINGS_REVISION = "settings_revision"
        private const val META_PROACTIVE_GLOBAL_NOT_BEFORE = "proactive_global_not_before"
        private const val META_INSTALL_STARTED_AT = "install_started_at"
        private const val META_TRANSPORT_SAFETY_SNAPSHOT = "transport_safety_snapshot"

        private val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        private val NON_KEY_CHARACTER = Regex("[^a-z0-9]+")
        private val LOGICAL_SECRET_REFERENCE = Regex("[a-z][a-z0-9_.-]{0,127}")
        private val FORBIDDEN_SETTING_SEGMENTS = setOf(
            "api_key",
            "access_token",
            "refresh_token",
            "auth_token",
            "authorization_header",
            "client_secret",
            "private_key",
            "password",
            "credential",
            "credentials",
            "whatsapp_auth",
            "baileys_auth",
            "session_credentials",
        )

        private val CHAT_COLUMNS = arrayOf(
            "chat_id",
            "kind",
            "display_name",
            "subject",
            "metadata_json",
            "last_message_at",
            "unread_count",
            "archived",
            "ai_disclosure_sent_at",
            "created_at",
            "updated_at",
        )
        private val MESSAGE_COLUMNS = arrayOf(
            "id",
            "provider_message_id",
            "event_id",
            "chat_id",
            "conversation_key",
            "sender_id",
            "direction",
            "message_type",
            "body",
            "quoted_provider_message_id",
            "media_key",
            "occurred_at",
            "received_at",
            "delivery_state",
            "from_admin",
            "metadata_json",
        )
        private val CHAT_MEMORY_COLUMNS = arrayOf(
            "conversation_key",
            "chat_id",
            "summary",
            "facts_json",
            "last_provider_message_id",
            "source_message_count",
            "revision",
            "updated_at",
        )
        private val PERSONA_COLUMNS = arrayOf(
            "persona_id",
            "name",
            "description",
            "system_prompt",
            "traits_json",
            "voice_config_json",
            "enabled",
            "created_at",
            "updated_at",
        )
        private val PERSONA_MEMORY_COLUMNS = arrayOf(
            "persona_id",
            "summary",
            "facts_json",
            "revision",
            "last_chat_write_count",
            "updated_at",
        )
        private val ACCESS_COLUMNS = arrayOf(
            "list_kind",
            "subject_type",
            "subject_id",
            "label",
            "enabled",
            "created_at",
            "updated_at",
        )
        private val SETTING_COLUMNS = arrayOf(
            "scope_type",
            "scope_id",
            "setting_key",
            "setting_value",
            "value_type",
            "updated_at",
        )
        private val PROACTIVE_COLUMNS = arrayOf(
            "chat_id",
            "enabled",
            "next_due_at",
            "cooldown_until",
            "last_inbound_at",
            "last_outbound_at",
            "daily_window_started_at",
            "daily_outbound_count",
            "consecutive_failures",
            "lease_owner",
            "lease_until",
            "state_json",
            "cold_outreach_at",
            "updated_at",
        )
        private val MEDIA_COLUMNS = arrayOf(
            "content_hash",
            "analyzer",
            "analyzer_version",
            "media_type",
            "result_json",
            "byte_size",
            "created_at",
            "last_accessed_at",
            "expires_at",
        )
        private val OUTBOUND_COLUMNS = arrayOf(
            "id",
            "dedupe_key",
            "chat_id",
            "outbound_kind",
            "decision",
            "reason_code",
            "status",
            "payload_hash",
            "planned_at",
            "committed_at",
            "expires_at",
            "metadata_json",
        )
        private val ACTIVITY_COLUMNS = arrayOf(
            "id",
            "occurred_at",
            "level",
            "category",
            "action",
            "chat_id",
            "correlation_id",
            "summary",
            "details_json",
        )
        private val OUTBOX_COLUMNS = arrayOf(
            "id",
            "dedupe_key",
            "operation",
            "chat_id",
            "payload_json",
            "priority",
            "state",
            "attempt_count",
            "available_at",
            "lease_owner",
            "lease_until",
            "last_error",
            "result_json",
            "created_at",
            "updated_at",
        )
    }
}

private inline fun <T> SQLiteDatabase.inNonExclusiveTransaction(
    block: SQLiteDatabase.() -> T,
): T {
    beginTransactionNonExclusive()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

private fun <T> SQLiteDatabase.queryOne(
    table: String,
    columns: Array<String>,
    selection: String?,
    selectionArgs: Array<String>?,
    mapper: (Cursor) -> T,
): T? {
    query(
        table,
        columns,
        selection,
        selectionArgs,
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        return if (cursor.moveToFirst()) mapper(cursor) else null
    }
}

private fun <T> SQLiteDatabase.queryList(
    table: String,
    columns: Array<String>,
    selection: String?,
    selectionArgs: Array<String>?,
    orderBy: String,
    limit: String?,
    mapper: (Cursor) -> T,
): List<T> {
    query(
        table,
        columns,
        selection,
        selectionArgs,
        null,
        null,
        orderBy,
        limit,
    ).use { cursor ->
        return buildList(cursor.count.coerceAtLeast(0)) {
            while (cursor.moveToNext()) add(mapper(cursor))
        }
    }
}

private fun SQLiteDatabase.executeChangedRows(
    sql: String,
    arguments: List<Any?>,
): Int {
    val statement = compileStatement(sql)
    try {
        arguments.forEachIndexed { index, value ->
            statement.bindValue(index + 1, value)
        }
        return statement.executeUpdateDelete()
    } finally {
        statement.close()
    }
}

private fun SQLiteStatement.bindValue(index: Int, value: Any?) {
    when (value) {
        null -> bindNull(index)
        is ByteArray -> bindBlob(index, value)
        is Float -> bindDouble(index, value.toDouble())
        is Double -> bindDouble(index, value)
        is Number -> bindLong(index, value.toLong())
        is Boolean -> bindLong(index, if (value) 1L else 0L)
        else -> bindString(index, value.toString())
    }
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun Cursor.longOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun Boolean.asDatabaseInt(): Int = if (this) 1 else 0

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}
