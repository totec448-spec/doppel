package de.totec.doppel.runtime

import de.totec.doppel.data.db.AccessEntryRecord
import de.totec.doppel.data.db.AccessListKind
import de.totec.doppel.data.db.AccessSubjectType
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.OutboundDecision
import de.totec.doppel.data.db.OutboundSafetyRecord
import de.totec.doppel.data.db.OutboundStatus
import de.totec.doppel.transport.BridgeFrame
import org.json.JSONArray
import org.json.JSONObject

/**
 * How one transport-safety frame should read in the operator log.
 *
 * The transport reports *state*, not *trouble*: a periodic timelock probe that comes back "no lock"
 * is the same frame kind as an actual lock. Logging every frame at one severity turned routine
 * health checks into warnings, so severity is derived from whether outbound sending is actually
 * restricted right now.
 */
internal data class SafetyNarration(
    val level: ActivityLevel,
    val summary: String,
)

/**
 * Removes contact-level values that the safety controller needs transiently but that must not be
 * copied into the long-lived activity log or safety snapshot. Confirmed blocked JIDs already have
 * one owner in the access table, so retaining the list in diagnostic JSON adds exposure without
 * adding recovery value.
 */
internal fun privacySafeSafetyDetail(kind: String, rawDetail: String): String {
    val detail = runCatching { JSONObject(rawDetail) }.getOrElse { JSONObject() }
    if (kind.startsWith("blocklist_")) detail.remove("jids")
    return detail.toString()
}

/**
 * The access-entry label that marks a block WhatsApp itself confirmed, so a later sync can tell it
 * apart from a block an admin set here.
 *
 * Persisted in the database and matched on read, which is why it stays German while the rest of the
 * operator text is English: rewording it would orphan every row already stored under the old text,
 * and those rows would then survive an unblock forever. It is shared with the admin actions instead
 * of being spelled out twice, because the two copies have to agree byte for byte.
 */
internal const val REMOTE_BLOCK_LABEL = "WhatsApp-Blockliste (bestätigt)"

/**
 * What has to happen before a transport lock is allowed to end.
 *
 * A lock without a stated way out is the failure mode this exists to prevent: one stream error used
 * to install a block with no expiry and no clearing frame, and the bot then stayed silent forever
 * while the link itself was long since healthy again. Every lock now names the event that releases
 * it, and carries a fallback expiry as a last resort, so "muted until someone reinstalls the app"
 * is not a reachable state.
 */
internal enum class LockRelease {
    /**
     * Only WhatsApp itself may lift this — a status frame saying the limit is gone, or the expiry
     * WhatsApp handed us. These are real account restrictions; sending into one is what turns a
     * strike into a ban, so the bot waits them out.
     */
    WHATSAPP_CONFIRMS,

    /**
     * The link coming back lifts this. The cause was the transport, not the account: a dropped
     * session, a `conflict` because another device took over, a "not logged in" during a relink.
     * Once we are online again the reason is gone by definition.
     */
    LINK_RECOVERED,
}

/**
 * The lifecycle of one lock reason: what it does to sending, what ends it, and how long it may
 * survive if the ending event never arrives.
 */
private data class LockPolicy(
    val decision: OutboundDecision,
    val release: LockRelease,
    val fallbackTtlMs: Long,
    val scope: String = "global",
)

/** Applies normalized WhatsApp account signals to the one shared outbound gate. */
internal class TransportSafetyController(
    private val repository: BotRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Returns false when a pushed snapshot is byte-semantically unchanged and needs no UI row. */
    fun apply(frame: BridgeFrame.Safety): Boolean {
        val detail = runCatching { JSONObject(frame.detail) }.getOrElse { JSONObject() }
        if (
            frame.kind in setOf("blocklist_set", "blocklist_update") &&
            !confirmedBlocklistChanged(detail)
        ) return false
        persistSnapshot(frame.kind, detail)
        when (frame.kind) {
            "blocklist_set", "blocklist_update" -> syncConfirmedBlocklist(detail)
            "temporary_ban" -> {
                val expiresAt = detail.optLong("expiresAtMs", 0L).takeIf { it > now() }
                if (expiresAt == null) {
                    release("temporary_ban", "WhatsApp reports the lock is over")
                } else {
                    lock(frame, detail, reason = "temporary_ban", expiresAt = expiresAt)
                }
            }
            "reachout_timelock" -> {
                val active = detail.optBoolean("isActive", false)
                val expiresAt = detail.optLong("expiresAtMs", 0L).takeIf { it > now() }
                if (!active || (detail.has("expiresAtMs") && expiresAt == null)) {
                    release("reachout_timelock", "WhatsApp reports no time lock")
                } else {
                    lock(frame, detail, reason = "reachout_timelock", expiresAt = expiresAt)
                }
                // This frame is the authoritative answer to the guess a rejected send made, so the
                // guess goes either way: confirmed into the real lock above, or dropped here.
                release("timelock", "WhatsApp answered the time-lock query")
            }
            "message_capping" -> lock(frame, detail, reason = "new_chat_message_cap")
            "message_capping_status" -> {
                if (detail.optBoolean("limited", false)) {
                    lock(frame, detail, reason = "new_chat_message_cap")
                } else {
                    release("new_chat_message_cap", "WhatsApp reports no chat cap")
                }
            }
            "outbound_timeout" -> hold(frame)
            "connection_hard_stop" -> {
                val status = detail.optInt("statusCode", 0).takeIf { it > 0 }
                lock(
                    frame,
                    detail,
                    reason = status?.let { "connection_$it" } ?: "connection_hard_stop",
                )
            }
            "restriction", "timelock" -> lock(frame, detail, reason = frame.kind)
        }
        return true
    }

    /**
     * The recovery condition for every [LockRelease.LINK_RECOVERED] lock, called on each transition
     * into "connected".
     *
     * This is the half that was missing: those locks are armed by transport trouble and there is no
     * WhatsApp frame that ever says "the transport is fine now" — the link simply works again. The
     * user logging back in has to be enough to make the bot speak again, and now it is.
     */
    fun onLinkRecovered() {
        activeLocks()
            .filter { lockReleaseOf(it.metadataJson) == LockRelease.LINK_RECOVERED }
            .forEach { clear(it, "the link is back") }
    }

    /**
     * Turns a frame into the row the operator should see, using the same reading of [frame] that
     * [apply] uses to gate sending. Anything that does not restrict outbound is informational; the
     * periodic probes the runtime fires on its own are demoted further so the log stays scannable.
     */
    fun describe(frame: BridgeFrame.Safety): SafetyNarration {
        val detail = runCatching { JSONObject(frame.detail) }.getOrElse { JSONObject() }
        val expiresAt = detail.optLong("expiresAtMs", 0L).takeIf { it > now() }
        return when (frame.kind) {
            "blocklist_set", "blocklist_update" -> {
                val count = detail.optJSONArray("jids")?.length() ?: detail.optInt("count", 0)
                SafetyNarration(
                    ActivityLevel.DEBUG,
                    "WhatsApp block list synced · $count contacts blocked",
                )
            }

            "temporary_ban" ->
                if (expiresAt == null) {
                    SafetyNarration(ActivityLevel.INFO, "The temporary WhatsApp lock has been lifted.")
                } else {
                    SafetyNarration(
                        ActivityLevel.WARN,
                        "Temporary WhatsApp lock active · ${formatRemaining(expiresAt - now())} left",
                    )
                }

            "reachout_timelock" -> {
                val active = detail.optBoolean("isActive", false)
                if (!active || (detail.has("expiresAtMs") && expiresAt == null)) {
                    SafetyNarration(
                        ActivityLevel.DEBUG,
                        "Time lock checked · no lock active, sending is free",
                    )
                } else {
                    SafetyNarration(
                        ActivityLevel.WARN,
                        "WhatsApp time lock active · first contacts blocked" +
                            (expiresAt?.let { ", ${formatRemaining(it - now())} left" } ?: ""),
                    )
                }
            }

            "message_capping" ->
                SafetyNarration(
                    ActivityLevel.WARN,
                    "WhatsApp is capping new chats · first contacts are held back",
                )

            "message_capping_status" ->
                if (detail.optBoolean("limited", false)) {
                    SafetyNarration(
                        ActivityLevel.WARN,
                        "WhatsApp is capping new chats · first contacts are held back",
                    )
                } else {
                    SafetyNarration(
                        ActivityLevel.DEBUG,
                        "Chat cap checked · no cap active, sending is free",
                    )
                }

            "outbound_timeout" ->
                SafetyNarration(
                    ActivityLevel.WARN,
                    "Send stayed unconfirmed · outbound paused for " +
                        formatRemaining(AMBIGUOUS_SEND_HOLD_MS),
                )

            "connection_hard_stop" ->
                SafetyNarration(
                    ActivityLevel.WARN,
                    "WhatsApp cut the connection hard · sending waits for a review",
                )

            "restriction" ->
                SafetyNarration(
                    ActivityLevel.WARN,
                    "WhatsApp reports an account restriction · sending waits for a review",
                )

            "timelock" ->
                SafetyNarration(
                    ActivityLevel.WARN,
                    "WhatsApp reports a time lock · sending waits for a review",
                )

            // Informational on purpose. A message we failed to carry inward says nothing about the
            // account, and blocking every outgoing message over it punished the wrong thing —
            // every other conversation went quiet because one event could not be journaled.
            "inbound_pipeline_failed" ->
                SafetyNarration(
                    ActivityLevel.WARN,
                    "An incoming message could not be processed and was dropped",
                )

            "runtime_worker_panic" ->
                SafetyNarration(
                    ActivityLevel.WARN,
                    "A background task in the WhatsApp core failed and was restarted",
                )

            // An unknown kind changes nothing about the gate, so it is news, not a warning.
            else -> SafetyNarration(ActivityLevel.INFO, "WhatsApp status: ${frame.kind.take(120)}")
        }
    }

    /**
     * SET. Records the reason, the decision, the release condition and an expiry, then supersedes
     * any older row for the same reason so a reason is only ever held by one row.
     *
     * [expiresAt] is WhatsApp's own answer where we have one; everything else falls back to the
     * policy TTL. The fallback is not an attempt to outsmart a restriction — a lock that lapses
     * while the account is still limited is re-armed by the very next rejected send, and that send
     * also triggers a fresh probe. It exists so a signal we never get a clearing frame for cannot
     * mute the bot permanently.
     */
    private fun lock(
        frame: BridgeFrame.Safety,
        detail: JSONObject,
        reason: String,
        expiresAt: Long? = null,
    ) {
        val policy = policyFor(reason)
        val setAt = now()
        val effectiveExpiry = expiresAt ?: (setAt + policy.fallbackTtlMs)
        // Supersede, do not "clear": the reason is not resolved, it is being restated.
        supersede(reason)
        repository.reserveOutbound(
            OutboundSafetyRecord(
                dedupeKey = "safety:transport:$reason:${frame.sequence}",
                outboundKind = "safety_lock",
                decision = policy.decision,
                reasonCode = reason,
                status = OutboundStatus.RESERVED,
                plannedAt = setAt,
                expiresAt = effectiveExpiry,
                metadataJson =
                    JSONObject(detail.toString())
                        .put("scope", policy.scope)
                        .put("lockReason", reason)
                        .put("lockRelease", policy.release.name)
                        .put("lockSetAtMs", setAt)
                        .put("lockExpiresAtMs", effectiveExpiry)
                        .put("lockExpirySource", if (expiresAt != null) "whatsapp" else "fallback")
                        .toString(),
            ),
        )
    }

    private fun hold(frame: BridgeFrame.Safety) {
        val startedAt = now()
        repository.reserveOutbound(
            OutboundSafetyRecord(
                dedupeKey = "safety:transport-timeout:${frame.sequence}",
                outboundKind = "safety_hold",
                decision = OutboundDecision.DENY,
                reasonCode = "outbound_timeout",
                status = OutboundStatus.RESERVED,
                plannedAt = startedAt,
                expiresAt = startedAt + AMBIGUOUS_SEND_HOLD_MS,
                metadataJson = frame.detail,
            ),
        )
    }

    private fun activeLocks(): List<OutboundSafetyRecord> =
        repository.listActiveSafetyControls().filter { it.outboundKind == "safety_lock" }

    /** Retires the previous row for a reason that is about to be restated. Not a release. */
    private fun supersede(reason: String) {
        repository.listActiveSafetyControls()
            .filter { it.reasonCode == reason }
            .forEach {
                repository.markOutboundStatus(
                    it.dedupeKey,
                    OutboundStatus.CANCELLED,
                    reasonCode = "${reason}_superseded".take(128),
                )
            }
    }

    /** CLEAR, by reason: the recovery condition for this reason was met. */
    private fun release(reason: String, cause: String) {
        repository.listActiveSafetyControls()
            .filter { it.reasonCode == reason }
            .forEach { clear(it, cause) }
    }

    /**
     * CLEAR, and say so. A lock that lifts silently is indistinguishable from a lock that is still
     * on, which is exactly the confusion that made the permanent block so hard to spot.
     */
    private fun clear(record: OutboundSafetyRecord, cause: String) {
        val reason = record.reasonCode
        repository.markOutboundStatus(
            record.dedupeKey,
            OutboundStatus.CANCELLED,
            reasonCode = "${reason}_cleared".take(128),
        )
        repository.appendActivity(
            ActivityLogRecord(
                occurredAt = now(),
                level = ActivityLevel.INFO,
                category = "transport_safety",
                action = "lock_cleared",
                summary = "Sending lock lifted · $reason · $cause",
                detailsJson =
                    JSONObject()
                        .put("reason", reason)
                        .put("cause", cause)
                        .put("heldForMs", now() - record.plannedAt)
                        .toString(),
            ),
        )
    }

    private fun syncConfirmedBlocklist(detail: JSONObject) {
        val array = detail.optJSONArray("jids") ?: return
        val confirmed = buildSet {
            for (index in 0 until array.length()) {
                val jid = array.optString(index).trim().lowercase()
                if (DIRECT_USER_JID.matches(jid)) add(jid)
            }
        }
        val previous =
            repository.listAccessEntries(AccessListKind.BLOCK, enabledOnly = false)
                .filter {
                    it.subjectType == AccessSubjectType.JID &&
                        it.label == REMOTE_BLOCK_LABEL
                }
        previous.filterNot { it.subjectId in confirmed }.forEach {
            repository.removeAccessEntry(
                AccessListKind.BLOCK,
                AccessSubjectType.JID,
                it.subjectId,
            )
        }
        val now = now()
        confirmed.forEach { jid ->
            repository.upsertAccessEntry(
                AccessEntryRecord(
                    listKind = AccessListKind.BLOCK,
                    subjectType = AccessSubjectType.JID,
                    subjectId = jid,
                    label = REMOTE_BLOCK_LABEL,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun confirmedBlocklistChanged(detail: JSONObject): Boolean {
        val array = detail.optJSONArray("jids") ?: return true
        val confirmed = buildSet {
            for (index in 0 until array.length()) {
                val jid = array.optString(index).trim().lowercase()
                if (DIRECT_USER_JID.matches(jid)) add(jid)
            }
        }
        val stored =
            repository.listAccessEntries(AccessListKind.BLOCK, enabledOnly = false)
                .asSequence()
                .filter {
                    it.subjectType == AccessSubjectType.JID && it.label == REMOTE_BLOCK_LABEL
                }
                .map(AccessEntryRecord::subjectId)
                .toSet()
        return confirmed != stored
    }

    private fun persistSnapshot(kind: String, detail: JSONObject) {
        val root =
            repository.transportSafetySnapshot()
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
        val storedDetail = JSONObject(privacySafeSafetyDetail(kind, detail.toString()))
        root.put(kind, storedDetail)
        root.put("lastKind", kind)
        root.put("updatedAtMs", now())
        repository.putTransportSafetySnapshot(root.toString())
    }

    private fun lockReleaseOf(metadataJson: String?): LockRelease {
        val stored =
            metadataJson
                ?.let { runCatching { JSONObject(it).optString("lockRelease") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
        // A row written before the lifecycle existed carries no release condition. Treating it as
        // link-recovered is the deliberate choice: those are exactly the rows that could never be
        // cleared, and any that describe a real restriction are re-armed by the next rejected send.
        return LockRelease.entries.firstOrNull { it.name == stored } ?: LockRelease.LINK_RECOVERED
    }

    companion object {
        private val DIRECT_USER_JID = Regex("^[^\\s@]{1,160}@(s\\.whatsapp\\.net|lid)$")

        private const val ONE_HOUR_MS = 60L * 60_000L

        /**
         * A confirmed account restriction may outlive almost anything — WhatsApp's reach-out timer
         * runs on the order of days — so the fallback sits past it and the real expiry does the
         * work whenever WhatsApp tells us one.
         */
        private const val ACCOUNT_LOCK_FALLBACK_MS = 8L * 24L * ONE_HOUR_MS

        /** The new-chat cap is re-read on every link-up, so a day is plenty of slack. */
        private const val CAP_FALLBACK_MS = 24L * ONE_HOUR_MS

        /**
         * A guess made from a single rejected send. The probe that same failure fires answers within
         * seconds, so this only has to bridge the gap — and must not become a week of silence
         * because one query happened to fail.
         */
        private const val PROVISIONAL_LOCK_FALLBACK_MS = 30L * 60_000L

        /** An unclassified 4xx: real enough to stop for, far too vague to stop for a day. */
        private const val RESTRICTION_FALLBACK_MS = 6L * ONE_HOUR_MS

        /**
         * Transport trouble. Normally cleared the moment the link is back; the TTL only covers a
         * process that dies before it ever reconnects.
         */
        private const val LINK_LOCK_FALLBACK_MS = 2L * ONE_HOUR_MS

        /**
         * The state machine, in one place.
         *
         * Reading it top to bottom is the whole delivery policy: what WhatsApp told us, how hard we
         * stop, and what has to happen before we start again.
         */
        private fun policyFor(reason: String): LockPolicy =
            when {
                reason == "temporary_ban" ->
                    LockPolicy(
                        OutboundDecision.DENY,
                        LockRelease.WHATSAPP_CONFIRMS,
                        ACCOUNT_LOCK_FALLBACK_MS,
                    )

                reason == "reachout_timelock" ->
                    LockPolicy(
                        OutboundDecision.DENY,
                        LockRelease.WHATSAPP_CONFIRMS,
                        ACCOUNT_LOCK_FALLBACK_MS,
                    )

                reason == "new_chat_message_cap" ->
                    LockPolicy(
                        OutboundDecision.DENY,
                        LockRelease.WHATSAPP_CONFIRMS,
                        CAP_FALLBACK_MS,
                        scope = "new_chat",
                    )

                // Derived from one rejected send, superseded by the probe it triggers.
                reason == "timelock" ->
                    LockPolicy(
                        OutboundDecision.REVIEW,
                        LockRelease.WHATSAPP_CONFIRMS,
                        PROVISIONAL_LOCK_FALLBACK_MS,
                    )

                reason == "restriction" ->
                    LockPolicy(
                        OutboundDecision.REVIEW,
                        LockRelease.WHATSAPP_CONFIRMS,
                        RESTRICTION_FALLBACK_MS,
                    )

                reason.startsWith("connection_") ->
                    LockPolicy(
                        OutboundDecision.REVIEW,
                        LockRelease.LINK_RECOVERED,
                        LINK_LOCK_FALLBACK_MS,
                    )

                // Unknown reasons stop sending, but only until the link proves itself: an
                // unrecognized signal is not licence to mute the bot indefinitely.
                else ->
                    LockPolicy(
                        OutboundDecision.REVIEW,
                        LockRelease.LINK_RECOVERED,
                        PROVISIONAL_LOCK_FALLBACK_MS,
                    )
            }

        /** Coarse on purpose: a lock that runs for days does not get more readable by the minute. */
        internal fun formatRemaining(remainingMs: Long): String {
            val seconds = (remainingMs / 1_000L).coerceAtLeast(0L)
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 -> "${days}d ${hours % 24}h"
                hours > 0 -> "${hours}h ${minutes % 60}m"
                minutes > 0 -> "${minutes}m"
                else -> "${seconds}s"
            }
        }
    }
}
