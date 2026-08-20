package de.totec.doppel.integration

import de.totec.doppel.data.db.ProactiveStateRecord
import de.totec.doppel.engine.ScheduledFollowUp
import org.json.JSONObject

/** One versioned codec shared by scheduler, tools and UI; no surface re-parses state JSON. */
internal object ProactiveStateMetadataCodec {
    const val MAX_NOTE_CHARS = 500
    private const val VERSION = 2
    private const val DEFERRED_AT = "deferredAt"
    private const val BASE_DUE = "baseNextDueAt"
    private const val FOLLOW_UP = "scheduledFollowUp"

    data class Decoded(
        val deferredAtMs: Long?,
        val baseNextDueAtMs: Long?,
        val followUp: ScheduledFollowUp?,
    )

    fun decode(record: ProactiveStateRecord): Decoded {
        val root = record.stateJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun nullableLong(name: String): Long? =
            root?.takeIf { it.has(name) && !it.isNull(name) }?.optLong(name)
        val followUp = root?.optJSONObject(FOLLOW_UP)?.let { item ->
            runCatching {
                ScheduledFollowUp(
                    id = item.getString("id"),
                    conversationKey = item.getString("conversationKey"),
                    personaKey = item.getString("personaKey"),
                    scheduledAtMs = item.getLong("scheduledAt"),
                    nextAttemptAtMs = item.getLong("nextAttemptAt"),
                    note = item.getString("note").take(MAX_NOTE_CHARS),
                    createdAtMs = item.getLong("createdAt"),
                )
            }.getOrNull()
        }
        return Decoded(
            deferredAtMs = nullableLong(DEFERRED_AT),
            baseNextDueAtMs = nullableLong(BASE_DUE) ?: record.nextDueAt,
            followUp = followUp,
        )
    }

    fun encode(
        deferredAtMs: Long?,
        baseNextDueAtMs: Long?,
        followUp: ScheduledFollowUp?,
    ): String =
        JSONObject()
            .put("v", VERSION)
            .put(DEFERRED_AT, deferredAtMs ?: JSONObject.NULL)
            .put(BASE_DUE, baseNextDueAtMs ?: JSONObject.NULL)
            .put(
                FOLLOW_UP,
                followUp?.let {
                    JSONObject()
                        .put("id", it.id)
                        .put("conversationKey", it.conversationKey)
                        .put("personaKey", it.personaKey)
                        .put("scheduledAt", it.scheduledAtMs)
                        .put("nextAttemptAt", it.nextAttemptAtMs)
                        .put("note", it.note.take(MAX_NOTE_CHARS))
                        .put("createdAt", it.createdAtMs)
                } ?: JSONObject.NULL,
            )
            .toString()

    fun earliest(first: Long?, second: Long?): Long? =
        listOfNotNull(first, second).minOrNull()
}
