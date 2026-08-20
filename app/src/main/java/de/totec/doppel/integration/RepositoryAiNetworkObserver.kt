package de.totec.doppel.integration

import de.totec.doppel.ai.AiNetworkEvent
import de.totec.doppel.ai.AiNetworkObserver
import de.totec.doppel.ai.PromptFingerprint
import de.totec.doppel.ai.firstPartyProviderFor
import de.totec.doppel.ai.servedAgainstPin
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotRepository
import java.io.Closeable
import java.util.Collections
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * One process-wide I/O writer for provider diagnostics.
 *
 * OkHttp callbacks only enqueue an already-redacted record; SQLite work never occupies an OkHttp
 * callback thread. The process-local queue is unbounded because dropping provider failures makes
 * the activity screen least reliable exactly when it is needed most; one consumer still bounds
 * SQLite work to a single writer.
 */
class AsyncActivityWriter(
    private val repository: BotRepository,
    private val activityChanged: () -> Unit = {},
) : Closeable {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("activity-writer"))
    private val records = Channel<ActivityLogRecord>(capacity = Channel.UNLIMITED)
    private val writer =
        scope.launch {
            for (record in records) {
                runCatching { repository.appendActivity(record) }
                    .onSuccess { activityChanged() }
            }
        }

    fun enqueue(record: ActivityLogRecord) {
        records.trySend(record)
    }

    override fun close() {
        records.close()
        // The repository is closed immediately after this owner. Join the
        // bounded writer so its final SQLite append cannot race that close.
        runBlocking { writer.join() }
        scope.cancel()
    }
}

/**
 * Privacy-safe provider diagnostics. It records operation, selected model,
 * status and duration, but never prompts, responses, phone numbers or secrets.
 *
 * Prompts appear only as truncated digests, which is enough to answer the two questions a cache
 * miss raises — did *our* prefix change, and did the provider honour it — without putting a single
 * character of user text into the log.
 */
class RepositoryAiNetworkObserver(
    private val activityWriter: AsyncActivityWriter,
    /**
     * The short form of a provider failure, for whoever is watching the screen right now: the
     * title is what the row shows ("OpenRouter 400"), the detail is the sentence under it.
     *
     * The activity log keeps everything and is where a failure is diagnosed. This exists because
     * "nothing is happening" is the one thing the log cannot say while it is happening.
     */
    private val providerProblem: (title: String, detail: String) -> Unit = { _, _ -> },
    /** Called after a request that worked, so a stale failure stops being shown. */
    private val providerRecovered: () -> Unit = {},
) : AiNetworkObserver {
    private val cacheTracker = PromptCacheTracker()

    override fun onEvent(event: AiNetworkEvent) {
        reportProblem(event)
        val elapsed = event.elapsedMs?.let(::formatDuration)
        val model = event.model?.take(MAX_MODEL_CHARS)
        val (level, action, summary, details) =
            when (event) {
                is AiNetworkEvent.Started ->
                    Quadruple(
                        // One row per HTTP attempt is diagnosis, not narrative: a
                        // single answer can open a dozen of them through the tool
                        // loop. The turn's own "OpenRouter" row reports the loop as
                        // one action; these stay available underneath it.
                        ActivityLevel.DEBUG,
                        "${event.operation}_started",
                        listOfNotNull(
                            "${operationLabel(event.operation)} started",
                            model,
                            if (event.attempt > 1) "attempt ${event.attempt}" else null,
                        ).joinToString(" · "),
                        JSONObject()
                            .put("attempt", event.attempt)
                            .apply {
                                event.routingPolicy?.let { put("routingPolicy", it.take(MAX_REASON_CHARS)) }
                                event.requestTag?.let { put("purpose", it.take(MAX_REASON_CHARS)) }
                                putPrompt(event.prompt)
                            },
                    )
                is AiNetworkEvent.Succeeded -> {
                    val cache = classifyCache(event)
                    // A request that was pinned to one host and answered by another is the single
                    // most expensive thing that can happen quietly: the prefix cache is gone and
                    // nothing about the reply looks wrong. It does not belong at DEBUG next to the
                    // ordinary rows — if the pin is being ignored, the log has to say so.
                    val strayed = servedAgainstPin(event.routingPolicy, event.providerName)
                    // The other half of the same question: a model whose maker serves it here, sent
                    // without a pin at all, means the setting is off — not that the router strayed.
                    // Saying which of the two it is turns "why is this on the wrong host again" from
                    // a guess into a reading.
                    val pinOff =
                        event.routingPolicy == null &&
                            event.model?.let { firstPartyProviderFor(it) } != null
                    Quadruple(
                        // Speech is its own visible step — one call, one voice note,
                        // one row. Chat completions and catalogue refreshes are steps
                        // inside a larger action that reports itself.
                        when {
                            strayed || pinOff -> ActivityLevel.WARN
                            event.operation == "speech" -> ActivityLevel.INFO
                            else -> ActivityLevel.DEBUG
                        },
                        "${event.operation}_succeeded",
                        listOfNotNull(
                            operationLabel(event.operation),
                            event.requestTag?.let(::purposeLabel),
                            model,
                            "HTTP ${event.statusCode}",
                            event.providerName?.let { "Provider ${it.take(48)}" },
                            "PIN IGNORED".takeIf { strayed },
                            "provider pin off".takeIf { pinOff },
                            tokenSummary(event),
                            cacheLabel(cache),
                            elapsed,
                        ).joinToString(" · "),
                        JSONObject()
                            .put("status", event.statusCode)
                            .put("attempt", event.attempt)
                            .apply {
                                event.responseContentType
                                    ?.take(MAX_CONTENT_TYPE_CHARS)
                                    ?.let { put("responseContentType", it) }
                                event.providerRequestId
                                    ?.take(MAX_REQUEST_ID_CHARS)
                                    ?.let { put("providerRequestId", it) }
                                event.providerName
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("providerName", it) }
                                event.routingPolicy
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("routingPolicy", it) }
                                event.requestTag?.let { put("purpose", it.take(MAX_REASON_CHARS)) }
                                event.finishReason
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("finishReason", it) }
                                putPrompt(event.prompt)
                                event.usage?.let { usage ->
                                    usage.promptTokens?.let { put("inputTokens", it) }
                                    usage.completionTokens?.let { put("outputTokens", it) }
                                    usage.totalTokens?.let { put("totalTokens", it) }
                                    put("cacheReadTokens", usage.cachedPromptTokens ?: 0)
                                    put("cacheWriteTokens", usage.cacheWriteTokens ?: 0)
                                }
                                put("cache", cache)
                            },
                    )
                }
                is AiNetworkEvent.Retrying ->
                    Quadruple(
                        ActivityLevel.WARN,
                        "${event.operation}_retrying",
                        listOfNotNull(
                            // A stall is the one retry cause where nothing actually failed:
                            // the request is still open, the provider simply never started
                            // answering. "retrying" would hide that this is the watchdog
                            // dropping a dead request, not an error bouncing back.
                            if (event.reasonCode == STALLED_REASON) {
                                "${operationLabel(event.operation)} never arrived · asked again"
                            } else {
                                "${operationLabel(event.operation)} is being retried"
                            },
                            reasonLabel(event.reasonCode)
                                .takeIf { event.reasonCode != STALLED_REASON },
                            model,
                            elapsed,
                        ).joinToString(" · "),
                        JSONObject()
                            .put("attempt", event.attempt)
                            .put("delayMs", event.delayMs)
                            .put("reason", event.reasonCode.take(MAX_REASON_CHARS)),
                    )
                is AiNetworkEvent.Cancelled ->
                    Quadruple(
                        ActivityLevel.INFO,
                        "${event.operation}_cancelled",
                        listOfNotNull(
                            "${operationLabel(event.operation)} cancelled",
                            event.requestTag?.let(::purposeLabel),
                            model,
                            elapsed,
                        ).joinToString(" · "),
                        JSONObject()
                            .put("attempt", event.attempt)
                            .apply {
                                event.requestTag?.let { put("purpose", it.take(MAX_REASON_CHARS)) }
                                putPrompt(event.prompt)
                            },
                    )
                is AiNetworkEvent.Failed ->
                    Quadruple(
                        ActivityLevel.ERROR,
                        "${event.operation}_failed",
                        listOfNotNull(
                            "${operationLabel(event.operation)} failed",
                            model,
                            event.statusCode?.let { "HTTP $it" },
                            reasonLabel(event.reasonCode),
                            // Named right in the summary, because it is the whole repair
                            // instruction: no endpoint takes this field, so stop sending it.
                            event.unsupportedParameter?.let { "unsupported ${it.take(40)}" },
                            event.providerName?.let { "Provider ${it.take(48)}" },
                            event.responseBodyKind?.let { "response $it" },
                            elapsed,
                        ).joinToString(" · "),
                        JSONObject()
                            .put("status", event.statusCode ?: JSONObject.NULL)
                            .put("attempt", event.attempt)
                            .put("reason", event.reasonCode.take(MAX_REASON_CHARS))
                            .apply {
                                event.endpointPath
                                    ?.take(MAX_ENDPOINT_CHARS)
                                    ?.let { put("endpointPath", it) }
                                event.responseContentType
                                    ?.take(MAX_CONTENT_TYPE_CHARS)
                                    ?.let { put("responseContentType", it) }
                                event.providerRequestId
                                    ?.take(MAX_REQUEST_ID_CHARS)
                                    ?.let { put("providerRequestId", it) }
                                event.responseBodyKind
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("responseBodyKind", it) }
                                event.providerErrorType
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("providerErrorType", it) }
                                event.providerErrorCode
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("providerErrorCode", it) }
                                event.providerName
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("providerName", it) }
                                if (event.errorMetadataKeys.isNotEmpty()) {
                                    put("errorMetadataKeys", event.errorMetadataKeys.joinToString(","))
                                }
                                event.errorFingerprint
                                    ?.take(MAX_FINGERPRINT_CHARS)
                                    ?.let { put("errorFingerprint", it) }
                                event.unsupportedParameter
                                    ?.take(MAX_REASON_CHARS)
                                    ?.let { put("unsupportedParameter", it) }
                                event.responseContentLength
                                    ?.let { put("responseContentLength", it.coerceAtLeast(0L)) }
                                event.requestTag?.let { put("purpose", it.take(MAX_REASON_CHARS)) }
                                putPrompt(event.prompt)
                            },
                    )
            }
        model?.let { details.put("model", it) }
        event.elapsedMs?.let { details.put("elapsedMs", it.coerceAtLeast(0L)) }
        activityWriter.enqueue(
            ActivityLogRecord(
                occurredAt = System.currentTimeMillis(),
                level = level,
                category = "ai_network",
                action = action.take(100),
                summary = summary.take(1_024),
                detailsJson = details.toString(),
            ),
        )
    }

    /**
     * Turns one network event into the short live problem line, or into the all-clear.
     *
     * Only chat completions and speech count. A model-catalogue refresh failing means the picker
     * is empty, which the picker already says itself — it must not put a red row over the chats.
     */
    private fun reportProblem(event: AiNetworkEvent) {
        if (event.operation == "model_catalog") return
        when (event) {
            is AiNetworkEvent.Succeeded -> providerRecovered()
            is AiNetworkEvent.Failed ->
                providerProblem(
                    // The status is the part worth reading from across the room; when there is
                    // none, the reason code says what happened instead of a bare "failed".
                    event.statusCode?.let { "OpenRouter $it" }
                        ?: reasonLabel(event.reasonCode)?.replaceFirstChar(Char::uppercase)
                        ?: "OpenRouter failed",
                    listOfNotNull(
                        operationLabel(event.operation),
                        event.model?.take(MAX_MODEL_CHARS),
                        event.statusCode?.let { reasonLabel(event.reasonCode) },
                        event.unsupportedParameter?.let { "unsupported ${it.take(40)}" },
                        if (event.attempt > 1) "attempt ${event.attempt}" else null,
                    ).joinToString(" · "),
                )
            else -> Unit
        }
    }

    private fun JSONObject.putPrompt(prompt: PromptFingerprint?) {
        prompt ?: return
        put("promptHash", prompt.promptHash)
        prompt.prefixHash?.let { put("cachePrefixHash", it) }
        put("cachePrefixChars", prompt.prefixCharacters)
        put("promptChars", prompt.totalCharacters)
        put("promptMessages", prompt.messageCount)
    }

    private fun classifyCache(event: AiNetworkEvent.Succeeded): String = cacheTracker.classify(event)

    private fun cacheLabel(cache: String): String? =
        when (cache) {
            "hit" -> "cache hit"
            "cold" -> "cache cold"
            "prefix_changed" -> "cache broken: prefix changed"
            "provider_miss" -> "cache missed despite the same prefix"
            "not_reported" -> "cache not reported"
            "not_requested" -> null
            else -> null
        }

    private fun tokenSummary(event: AiNetworkEvent.Succeeded): String? {
        val usage = event.usage ?: return null
        val input = usage.promptTokens ?: return null
        val output = usage.completionTokens ?: 0
        val cached = usage.cachedPromptTokens ?: 0
        return buildString {
            append("in ").append(input)
            if (cached > 0) append(" (").append(cached).append(" cached)")
            append(" · out ").append(output)
        }
    }

    private fun purposeLabel(tag: String): String =
        when (tag) {
            "turn" -> "reply"
            "visible_follow_up" -> "follow-up check"
            "verify" -> "verification"
            "memory_refresh_cached" -> "memory"
            "media" -> "media"
            else -> tag.take(MAX_REASON_CHARS)
        }

    private fun operationLabel(operation: String): String =
        when (operation) {
            "chat_completion" -> "OpenRouter reply"
            "speech" -> "OpenRouter TTS"
            "model_catalog" -> "OpenRouter model catalog"
            else -> "OpenRouter ${operation.take(80)}"
        }

    private fun formatDuration(value: Long): String =
        if (value < 1_000L) "$value ms" else "%.2f s".format(value / 1_000.0)

    private fun reasonLabel(reason: String): String? =
        when (reason) {
            "no_compatible_endpoint" -> "no compatible provider endpoint"
            "model_not_found" -> "model not found"
            "unsupported_parameter" -> "parameter not supported"
            "data_policy" -> "blocked by data policy"
            "authentication_or_policy" -> "blocked by access or policy"
            "provider_timeout" -> "provider timeout"
            "rate_limited" -> "rate limited"
            "provider_unavailable" -> "provider unavailable"
            "provider_overloaded" -> "provider overloaded"
            "payment_required" -> "out of credit"
            "permission_denied" -> "access denied"
            "invalid_request", "invalid_prompt" -> "invalid request"
            "not_found" -> "model or resource not found"
            "server" -> "OpenRouter server error"
            "timeout" -> "provider timeout"
            STALLED_REASON -> "no response received"
            "network_io" -> "network error"
            "http_404" -> "route or resource not found"
            else -> null
        }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )

    private companion object {
        /** [de.totec.doppel.ai.OpenRouterStallException], as it reaches this observer. */
        const val STALLED_REASON = "stalled"
        const val MAX_MODEL_CHARS = 256
        const val MAX_REASON_CHARS = 120
        const val MAX_CONTENT_TYPE_CHARS = 120
        const val MAX_REQUEST_ID_CHARS = 200
        const val MAX_ENDPOINT_CHARS = 160
        const val MAX_FINGERPRINT_CHARS = 32
    }
}

/**
 * Names *where* a prompt cache broke, which token counts alone cannot say.
 *
 * Token counts only ever say "you paid full price". The interesting question is whose fault that
 * was: `prefix_changed` means the request itself differed — a volatile value slipped in front of
 * the cache boundary — while `provider_miss` means a byte-identical prefix went out and the
 * provider charged anyway, which is a routing or provider problem and not a prompt bug.
 *
 * Split out of the observer so the classification can be exercised without a database behind it.
 * It stores digests only, never prompt text, and is bounded to [MAX_TRACKED_PREFIXES] call sites.
 */
internal class PromptCacheTracker {
    /** Last cacheable-prefix digest per call site, in least-recently-used order. */
    private val lastPrefixHashes: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
                    size > MAX_TRACKED_PREFIXES
            },
        )

    fun classify(event: AiNetworkEvent.Succeeded): String {
        val prompt = event.prompt ?: return "unknown"
        val cached = event.usage?.cachedPromptTokens
        val prefixHash = prompt.prefixHash ?: return "not_requested"
        // Call sites are tracked separately: a verification and a turn share neither prompt nor
        // cache, so comparing their prefixes would report a break on every alternating call.
        val key = "${event.operation}/${event.requestTag.orEmpty()}/${event.model.orEmpty()}"
        val previous = lastPrefixHashes.put(key, prefixHash)
        return when {
            cached == null -> "not_reported"
            cached > 0 -> "hit"
            previous == null -> "cold"
            previous != prefixHash -> "prefix_changed"
            else -> "provider_miss"
        }
    }

    private companion object {
        const val MAX_TRACKED_PREFIXES = 64
    }
}
