package de.totec.doppel.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface OpenRouterApiKeyProvider {
    suspend fun apiKey(): String?
}

data class OpenRouterAttribution(
    val referer: String? = null,
    val title: String? = null,
)

/**
 * Identity of one outgoing prompt, in a form that can be logged in production.
 *
 * Both values are truncated SHA-256 digests, so they correlate calls without exposing a single
 * character of prompt or user text. They are what makes a cache miss diagnosable:
 *
 * - [prefixHash] covers exactly the region marked cacheable (everything up to and including the
 *   last `cache_control: ephemeral` marker). If it changes between two calls, *we* broke the
 *   prefix — some volatile value leaked in front of the boundary.
 * - [promptHash] covers the whole message array. Changing while [prefixHash] holds is the normal,
 *   healthy case: only the tail moved.
 *
 * If [prefixHash] is stable and the provider still reports zero cached tokens, the break is on the
 * provider side, not in the prompt.
 */
data class PromptFingerprint(
    val promptHash: String,
    val prefixHash: String?,
    val prefixCharacters: Int,
    val totalCharacters: Int,
    val messageCount: Int,
)

sealed interface AiNetworkEvent {
    val operation: String
    val model: String?
    val elapsedMs: Long?

    data class Started(
        override val operation: String,
        val attempt: Int,
        override val model: String? = null,
        override val elapsedMs: Long? = 0L,
        val routingPolicy: String? = null,
        val requestTag: String? = null,
        val prompt: PromptFingerprint? = null,
    ) : AiNetworkEvent

    data class Succeeded(
        override val operation: String,
        val statusCode: Int,
        val attempt: Int,
        override val model: String? = null,
        override val elapsedMs: Long? = null,
        val responseContentType: String? = null,
        val providerRequestId: String? = null,
        val providerName: String? = null,
        val routingPolicy: String? = null,
        val requestTag: String? = null,
        val prompt: PromptFingerprint? = null,
        val usage: TokenUsage? = null,
        val finishReason: String? = null,
    ) : AiNetworkEvent

    data class Retrying(
        override val operation: String,
        val attempt: Int,
        val delayMs: Long,
        val reasonCode: String,
        override val model: String? = null,
        override val elapsedMs: Long? = null,
    ) : AiNetworkEvent

    /** The caller abandoned this exact request; the OkHttp call was cancelled with it. */
    data class Cancelled(
        override val operation: String,
        val attempt: Int,
        override val model: String? = null,
        override val elapsedMs: Long? = null,
        val requestTag: String? = null,
        val prompt: PromptFingerprint? = null,
    ) : AiNetworkEvent

    data class Failed(
        override val operation: String,
        val statusCode: Int?,
        val attempt: Int,
        val reasonCode: String,
        override val model: String? = null,
        override val elapsedMs: Long? = null,
        val endpointPath: String? = null,
        val responseContentType: String? = null,
        val providerRequestId: String? = null,
        val responseBodyKind: String? = null,
        val providerErrorType: String? = null,
        val providerErrorCode: String? = null,
        val providerName: String? = null,
        val errorMetadataKeys: List<String> = emptyList(),
        val errorFingerprint: String? = null,
        val responseContentLength: Long? = null,
        /** The request field OpenRouter could route to no endpoint, when it named one. */
        val unsupportedParameter: String? = null,
        val requestTag: String? = null,
        val prompt: PromptFingerprint? = null,
    ) : AiNetworkEvent
}

fun interface AiNetworkObserver {
    /**
     * Events intentionally contain no credentials, prompts, responses or message identifiers.
     */
    fun onEvent(event: AiNetworkEvent)

    companion object {
        val NONE = AiNetworkObserver { }
    }
}

class MissingApiKeyException : IllegalStateException("OpenRouter API key is not configured")

class OpenRouterHttpException(
    val statusCode: Int,
    val retryAfterMs: Long?,
    val providerRequestId: String?,
    val reasonCode: String = "http_error",
    val endpointPath: String? = null,
    val responseContentType: String? = null,
    val responseBodyKind: String? = null,
    val providerErrorType: String? = null,
    val providerErrorCode: String? = null,
    val providerName: String? = null,
    val errorMetadataKeys: List<String> = emptyList(),
    val errorFingerprint: String? = null,
    val responseContentLength: Long? = null,
    val unsupportedParameter: String? = null,
) : IOException("OpenRouter HTTP $statusCode")

class OpenRouterProtocolException(
    val reasonCode: String,
) : IOException("OpenRouter protocol error: $reasonCode")

/**
 * The attempt was abandoned because the provider never answered in time.
 *
 * This is not an OkHttp timeout. The HTTP timeouts are a user setting and can be switched off
 * (`OPENROUTER_TIMEOUT_MS = 0`), and a connection that is technically alive but silent — the slow
 * time-to-first-token case — otherwise keeps a whole turn hanging with nothing to show for it.
 * The deadline behind this exception is enforced in the coroutine instead, so there is always a
 * point at which the request is dropped and re-sent.
 *
 * It is an [IOException] on purpose: that puts it on the ordinary retry path, bounded by the same
 * attempt budget as any other transient network fault. It is re-issued only when the stalled
 * attempt was streaming and had not received a single byte — see [RetryClassifier], which is where
 * the difference between "nothing was started" and "something is being generated and billed" is
 * decided. A stall on a buffered request therefore ends the turn instead of paying for it twice.
 */
class OpenRouterStallException(
    val waitedMs: Long,
) : IOException("OpenRouter did not answer within ${waitedMs}ms")

object RetryClassifier {
    fun isRetryableStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode in 500..599

    /**
     * Whether this failure may be sent again, given what the attempt already got back.
     *
     * A retry is free only when the attempt cannot have left a completion running on the provider's
     * side. Once the answer exists it is billed whether or not this app was still listening, so a
     * second request for the same turn pays for the same work twice — and the second bill buys
     * nothing the first one did not already produce.
     *
     * [responseStarted] is true from the moment the provider's response headers were read; up to
     * that point nothing had come back at all. [streaming] says whether tokens were asked for as
     * they are produced, which is the only case where silence is evidence: a streaming request that
     * has sent nothing is a request nothing has started answering, while a buffered one is silent
     * for exactly as long as it takes to write the whole answer.
     *
     * That is what separates the two IO failures. An HTTP error status is retryable regardless: an
     * error is what the provider returned *instead* of a completion, so there is none to pay for.
     * A transient envelope fault is the one deliberate exception — the completion behind it is
     * already lost, so a single re-issue turns a certain loss into a probable answer, and the
     * one-shot guard in [OpenRouterClient] is what keeps it from becoming a habit.
     */
    fun isRetryableThrowable(
        throwable: Throwable,
        responseStarted: Boolean = false,
        streaming: Boolean = false,
    ): Boolean =
        when (throwable) {
            is OpenRouterProtocolException ->
                throwable.reasonCode in TRANSIENT_PROTOCOL_FAILURES &&
                    (throwable.reasonCode != "response_too_large" || !responseStarted)
            is OpenRouterHttpException -> isRetryableStatus(throwable.statusCode)
            is OpenRouterStallException -> streaming && !responseStarted
            is IOException -> !responseStarted
            else -> false
        }

    fun retryAfterMillis(
        value: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Long? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        normalized.toDoubleOrNull()?.let { seconds ->
            if (!seconds.isFinite() || seconds < 0) return null
            return (seconds * 1_000.0).toLong().coerceAtLeast(0)
        }
        return try {
            val instantMs = ZonedDateTime
                .parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            max(0, instantMs - nowEpochMs)
        } catch (_: Exception) {
            null
        }
    }

    /** Malformed/incomplete provider envelopes can succeed on one fresh request. A response-size
     * overflow also gets one bounded retry: reasoning envelopes and provider framing can vary even
     * when the requested answer budget is unchanged. Request/content/tool limits stay permanent. */
    private val TRANSIENT_PROTOCOL_FAILURES =
        setOf(
            "invalid_json",
            "invalid_sse_json",
            "missing_body",
            "missing_choice",
            "missing_message",
            "missing_stream_choice",
            "provider_error",
            "response_too_large",
        )
}

/**
 * Small OpenRouter client with no interceptor-based body logging. Streaming and JSON responses
 * share one request; a provider returning JSON to a streaming request is parsed in place and is
 * never billed through an automatic second request.
 */
class OpenRouterClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val apiKeyProvider: OpenRouterApiKeyProvider,
    private val attribution: OpenRouterAttribution = OpenRouterAttribution(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val observer: AiNetworkObserver = AiNetworkObserver.NONE,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Per-attempt deadline in milliseconds; `0` disables it. Deliberately a backstop rather than
     * the primary timeout — see [stallDeadlineFor] for how it relates to the configured HTTP
     * timeout, and [OpenRouterStallException] for why it exists at all.
     */
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    /**
     * Read per request, not captured once: the setting behind it is a live switch and the client
     * outlives a settings change.
     */
    private val firstPartyProviderOnly: () -> Boolean = { true },
) : ChatCompletionGateway {
    override suspend fun complete(request: ChatCompletionRequest): CompletionResult {
        val apiKey = apiKeyProvider.apiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) throw MissingApiKeyException()
        // Read once, here, where the call still has the caller's context: the parse itself happens
        // on an OkHttp thread that has none.
        val sink = coroutineContext[LiveTokenSink]
        val slug = firstPartyProviderFor(request.model).takeIf { firstPartyProviderOnly() }
        // No client-side fallback pass. A pinned model that the maker cannot serve right now fails
        // the turn instead of quietly landing somewhere else: a third-party host answers without
        // the prefix cache the prompt was built for, and a silent detour is exactly what made this
        // impossible to see in the first place. The escape hatch is the setting, not the code path.
        return dispatch(
            request,
            apiKey,
            pin = slug,
            routingPolicy = slug?.let { "original_${it}_only" },
            sink = sink,
        )
    }

    private suspend fun dispatch(
        request: ChatCompletionRequest,
        apiKey: String,
        pin: String?,
        routingPolicy: String?,
        sink: LiveTokenSink? = null,
    ): CompletionResult {
        val payload = encodeRequest(request, pin)
        val encoded = payload.toString()
        if (encoded.length > MAX_REQUEST_CHARACTERS) {
            throw OpenRouterProtocolException("request_too_large")
        }
        val fingerprint = fingerprintPrompt(request)
        val body = encoded
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(endpoint("chat", "completions"))
            .headers(authHeaders(apiKey, attribution))
            .post(body)
            .build()

        val startedAtMs = clock.millis()
        var protocolRetryUsed = false
        var activeAttempt = 0
        // Whether the attempt that just failed had anything back from the provider. It is reset at
        // the top of every attempt and raised the moment the response headers are in hand, which is
        // the line between a request nothing has answered yet and one whose completion exists and
        // is billed whether or not this app was still listening.
        var responseStarted = false
        return try {
            executeWithRetry(
            policy = retryPolicy,
            retryable = { failure ->
                RetryClassifier.isRetryableThrowable(
                    failure,
                    responseStarted = responseStarted,
                    streaming = request.stream,
                ) && (failure !is OpenRouterProtocolException || !protocolRetryUsed)
            },
            onFailure = { failure, attempt ->
                observer.emit(
                    failure.toNetworkFailure(
                        operation = OPERATION,
                        attempt = attempt,
                        reasonCode = failure.reasonCode(),
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                        requestTag = request.requestTag,
                        prompt = fingerprint,
                    ),
                )
            },
            onRetry = { failure, attempt, waitMs ->
                observer.emit(
                    AiNetworkEvent.Retrying(
                        operation = OPERATION,
                        attempt = attempt,
                        delayMs = waitMs,
                        reasonCode = failure.reasonCode(),
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                    ),
                )
                if (failure is OpenRouterProtocolException) protocolRetryUsed = true
            },
        ) { attempt ->
                activeAttempt = attempt
                responseStarted = false
                observer.emit(
                    AiNetworkEvent.Started(
                        operation = OPERATION,
                        attempt = attempt,
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                        routingPolicy = routingPolicy,
                        requestTag = request.requestTag,
                        prompt = fingerprint,
                    ),
                )
                val call = httpClient.newCall(httpRequest)
                // When this attempt last proved it was alive. The watchdog below measures silence
                // from here, so a long answer that keeps arriving is never abandoned for taking a
                // long time — only one that has stopped arriving.
                val lastProgressAtMs = AtomicLong(clock.millis())
                val parse = { response: Response ->
                    responseStarted = true
                    lastProgressAtMs.set(clock.millis())
                    response.requireOpenRouterSuccess(clock.millis())
                    val contentType = response.body?.contentType()?.toString().orEmpty()
                    val startsLikeEventStream = if (request.stream) {
                        response.peekBody(64)
                            .string()
                            .trimStart()
                            .startsWith("data:")
                    } else {
                        false
                    }
                    val result = if (
                        request.stream &&
                        (
                            contentType.contains("text/event-stream", ignoreCase = true) ||
                                startsLikeEventStream
                            )
                    ) {
                        parseEventStream(response, sink) {
                            lastProgressAtMs.set(clock.millis())
                        }
                    } else {
                        parseCompletionJson(readResponseText(response.body), sink)
                    }
                    observer.emit(
                        AiNetworkEvent.Succeeded(
                            operation = OPERATION,
                            statusCode = response.code,
                            attempt = attempt,
                            model = request.model,
                            elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                            responseContentType = contentType.takeIf(String::isNotBlank),
                            providerRequestId =
                                (
                                    response.header("X-Generation-Id")
                                        ?: response.header("x-request-id")
                                    )
                                    ?.takeIf(String::isNotBlank)
                                    ?.take(MAX_REQUEST_ID_CHARS),
                            providerName = result.provider,
                            routingPolicy = routingPolicy,
                            requestTag = request.requestTag,
                            prompt = fingerprint,
                            usage = result.usage,
                            finishReason = result.finishReason,
                        ),
                    )
                    result
                }
                // A stalled attempt is cancelled here, which cancels the OkHttp call with it, so
                // nothing is left reading a dead socket in the background. What it cannot undo is
                // the provider's side: a completion that is already being generated keeps going and
                // is still billed. So the stall is only re-issued while `responseStarted` is false
                // and the request was streaming — the one case where the silence proves there is no
                // half-finished answer to pay for. Otherwise it fails the turn, which costs one
                // reply rather than two bills for the same one.
                val result =
                    if (stallTimeoutMs > 0L) {
                        awaitWithSilenceDeadline(stallTimeoutMs, lastProgressAtMs) {
                            call.awaitParsed(parse)
                        }
                    } else {
                        call.awaitParsed(parse)
                    }
                result
            }
        } catch (cancelled: CancellationException) {
            observer.emit(
                AiNetworkEvent.Cancelled(
                    operation = OPERATION,
                    attempt = activeAttempt.coerceAtLeast(1),
                    model = request.model,
                    elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                    requestTag = request.requestTag,
                    prompt = fingerprint,
                ),
            )
            throw cancelled
        }
    }

    /**
     * Runs [body] under a deadline that measures *silence*, not duration.
     *
     * The distinction is the whole point. A single deadline over the entire read cannot tell a
     * dead socket apart from a model that is still writing, so it kills the longest answers —
     * exactly the ones that cost the most to produce and are most expensive to lose. Here the
     * clock is restarted by [lastProgressAtMs] every time bytes arrive, so an attempt is
     * abandoned only after [silenceTimeoutMs] with nothing at all coming back.
     *
     * The watchdog cancels the work rather than throwing past it, so the OkHttp call is closed on
     * the way out. [stalled] separates that cancellation from an ordinary one: a turn the user or
     * the engine aborted must keep propagating as cancellation, not be reported as a provider
     * stall.
     */
    private suspend fun <T> awaitWithSilenceDeadline(
        silenceTimeoutMs: Long,
        lastProgressAtMs: AtomicLong,
        body: suspend () -> T,
    ): T = coroutineScope {
        val stalled = AtomicBoolean(false)
        // Both children start on the calling thread rather than waiting for a dispatcher turn. The
        // request must leave as soon as the caller reaches this point — it used to run inline — and
        // a watchdog that is not armed until some later scheduling turn would be measuring silence
        // from the wrong instant.
        val work = async(start = CoroutineStart.UNDISPATCHED) { body() }
        val watchdog =
            launch(start = CoroutineStart.UNDISPATCHED) {
                while (true) {
                    val remaining =
                        silenceTimeoutMs - (clock.millis() - lastProgressAtMs.get())
                    if (remaining <= 0L) {
                        stalled.set(true)
                        work.cancel()
                        return@launch
                    }
                    delay(remaining)
                }
            }
        try {
            work.await()
        } catch (cancelled: CancellationException) {
            if (stalled.get()) throw OpenRouterStallException(silenceTimeoutMs)
            throw cancelled
        } finally {
            watchdog.cancel()
        }
    }

    internal fun encodeRequest(
        request: ChatCompletionRequest,
        pin: String? = firstPartyProviderFor(request.model).takeIf { firstPartyProviderOnly() },
    ): JSONObject =
        JSONObject().apply {
            put("model", request.model)
            put("messages", JSONArray().apply {
                request.messages.forEach { put(encodeMessage(it)) }
            })
            put("stream", request.stream)
            if (request.stream) {
                put("stream_options", JSONObject().put("include_usage", true))
            }
            // Without this OpenRouter omits prompt_tokens_details entirely, so every response looks
            // like a cache miss even when the provider served a cached prefix. Cache accounting is
            // unreadable without it — and it costs nothing.
            put("usage", JSONObject().put("include", true))
            put("temperature", request.sampling.temperature)
            if (request.sampling.topP < 1.0) put("top_p", request.sampling.topP)
            if (request.sampling.frequencyPenalty != 0.0) {
                put("frequency_penalty", request.sampling.frequencyPenalty)
            }
            if (request.sampling.presencePenalty != 0.0) {
                put("presence_penalty", request.sampling.presencePenalty)
            }
            request.sampling.maxTokens?.let { put("max_tokens", it) }
            val reasoningEffort = request.sampling.reasoningEffort.wireValue
            val reasoningBudget = request.sampling.reasoningMaxTokens
            if (reasoningEffort != null || reasoningBudget != null) {
                put(
                    "reasoning",
                    JSONObject().apply {
                        // OpenRouter's chat contract permits effort or max_tokens, never both.
                        // SamplingSettings enforces that invariant before a paid request is built.
                        reasoningEffort?.let { put("effort", it) }
                        reasoningBudget?.let { put("max_tokens", it) }
                        // Asked for, not excluded. These tokens are billed as output whether they
                        // come back or not, so excluding them bought nothing and cost the only
                        // account of why an answer turned out the way it did. [LiveTokenSink]
                        // carries them to the open chat's trace as they arrive.
                        put("exclude", false)
                    },
                )
            }
            // Routing, not sampling: keeping a model on its own maker's endpoint is what makes the
            // prompt cache, the tokenizer and the tool dialect predictable.
            //
            // All three fields say the same thing on purpose. `only` alone is a filter that
            // OpenRouter still leaves as soon as `allow_fallbacks` permits it — which is how the
            // large, tool-carrying turns ended up on whatever third party was cheapest that second
            // while only the small verifier calls stayed on the maker's endpoint. `order` is the
            // form the routing documentation uses for "these and nothing else", and
            // `allow_fallbacks: false` is what makes either of them binding. There is no
            // arrangement of these three under which another host may answer.
            pin?.let { slug ->
                put(
                    "provider",
                    JSONObject()
                        .put("order", JSONArray().put(slug))
                        .put("only", JSONArray().put(slug))
                        .put("allow_fallbacks", false),
                )
            }
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { put(encodeTool(it)) }
                })
                put("tool_choice", request.toolChoice.wireValue)
            }
        }

    /**
     * Hashes the prompt so a cache miss can be attributed without logging any prompt text.
     *
     * The prefix region ends at the last message carrying [CacheControl.EPHEMERAL] — that is
     * exactly what the request asks the provider to cache. Tool definitions are folded into the
     * prefix hash because they sit in front of the messages on the wire, so a changed tool set
     * breaks the cache just as surely as changed instructions do.
     */
    internal fun fingerprintPrompt(request: ChatCompletionRequest): PromptFingerprint {
        val lastCacheable = request.messages.indexOfLast {
            it.cacheControl == CacheControl.EPHEMERAL
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val prefixDigest = MessageDigest.getInstance("SHA-256")
        var prefixCharacters = 0
        var totalCharacters = 0

        fun feed(value: String, inPrefix: Boolean) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes)
            totalCharacters += value.length
            if (inPrefix) {
                prefixDigest.update(bytes)
                prefixCharacters += value.length
            }
        }

        feed(request.model, inPrefix = true)
        request.tools.forEach { tool ->
            feed(tool.name, inPrefix = true)
            feed(tool.description, inPrefix = true)
            tool.parameters.forEach { feed(it.name + it.type + it.description, inPrefix = true) }
        }
        request.messages.forEachIndexed { index, message ->
            feed(message.role.wireValue, inPrefix = index <= lastCacheable)
            feed(message.digestText(), inPrefix = index <= lastCacheable)
        }
        return PromptFingerprint(
            promptHash = digest.digest().toShortHex(),
            prefixHash = if (lastCacheable >= 0) prefixDigest.digest().toShortHex() else null,
            prefixCharacters = if (lastCacheable >= 0) prefixCharacters else 0,
            totalCharacters = totalCharacters,
            messageCount = request.messages.size,
        )
    }

    internal fun parseCompletionJson(
        raw: String,
        sink: LiveTokenSink? = null,
    ): CompletionResult {
        if (raw.length > MAX_RESPONSE_CHARACTERS) {
            throw OpenRouterProtocolException("response_too_large")
        }
        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw OpenRouterProtocolException("invalid_json")
        }
        if (root.has("error")) throw OpenRouterProtocolException("provider_error")
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw OpenRouterProtocolException("missing_choice")
        val message = choice.optJSONObject("message")
            ?: throw OpenRouterProtocolException("missing_message")
        val content = readContent(message.opt("content"))
        if (content.length > MAX_CONTENT_CHARACTERS) {
            throw OpenRouterProtocolException("content_too_large")
        }
        // A non-streamed answer arrives whole, so its reasoning is history by the time it is read.
        // It still goes to the sink: late is not the same as never, and a media description or a
        // verifier verdict is short enough that one line is the honest shape for it anyway.
        val reasoning = readReasoning(message).take(MAX_REASONING_CHARACTERS)
        if (reasoning.isNotEmpty()) {
            sink?.reasoning(reasoning, clock.millis())
        }
        if (content.isNotEmpty()) sink?.content(content, clock.millis())
        sink?.flush()
        return CompletionResult(
            content = content,
            toolCalls =
                readToolCalls(message.optJSONArray("tool_calls")).also(::validateToolCalls),
            usage = parseUsage(root.optJSONObject("usage")),
            model = root.optString("model").takeIf(String::isNotBlank),
            provider = root.optString("provider").takeIf(String::isNotBlank),
            finishReason = choice.optString("finish_reason").takeIf(String::isNotBlank),
            reasoning = reasoning.takeIf(String::isNotEmpty),
        )
    }

    /**
     * The model's own words, in either of the two shapes OpenRouter uses for them.
     *
     * `reasoning` is the flat string most providers send, in the same delta rhythm as the content.
     * `reasoning_details` is the structured form — signed or redacted blocks for the providers that
     * require the reasoning to be handed back verbatim on the next turn. Only the readable text of
     * it is of interest here; an encrypted block has nothing to show.
     */
    private fun readReasoning(node: JSONObject): String {
        val plain = node.opt("reasoning")
        if (plain is String && plain.isNotEmpty()) return plain
        val details = node.optJSONArray("reasoning_details") ?: return ""
        val text = StringBuilder()
        for (index in 0 until details.length()) {
            val entry = details.optJSONObject(index) ?: continue
            val piece = entry.optString("text").takeIf(String::isNotBlank)
                ?: entry.optString("summary").takeIf(String::isNotBlank)
                ?: continue
            text.append(piece)
        }
        return text.toString()
    }

    private fun parseEventStream(
        response: Response,
        sink: LiveTokenSink? = null,
        onProgress: (() -> Unit)? = null,
    ): CompletionResult {
        val body = response.body ?: throw OpenRouterProtocolException("missing_body")
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw OpenRouterProtocolException("response_too_large")
        }
        val source = body.source()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var sawFlatReasoning = false
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: TokenUsage? = null
        var model: String? = null
        var provider: String? = null
        var finishReason: String? = null
        var sawChoice = false
        var responseCharacters = 0
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            // Every line counts as life, including the blank separators and the `:` keepalive
            // comments a provider sends while a reasoning model is still thinking. Ticking only on
            // deltas would treat a deliberately idling-but-healthy stream as a stall.
            onProgress?.invoke()
            responseCharacters += line.length + 1
            if (responseCharacters > MAX_RESPONSE_CHARACTERS) {
                throw OpenRouterProtocolException("response_too_large")
            }
            if (!line.startsWith("data:")) continue
            val data = line.substringAfter("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val root = try {
                JSONObject(data)
            } catch (_: JSONException) {
                throw OpenRouterProtocolException("invalid_sse_json")
            }
            if (root.has("error")) throw OpenRouterProtocolException("provider_error")
            if (model == null) model = root.optString("model").takeIf(String::isNotBlank)
            if (provider == null) provider = root.optString("provider").takeIf(String::isNotBlank)
            parseUsage(root.optJSONObject("usage"))?.let { usage = it }
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: continue
            sawChoice = true
            choice.optString("finish_reason").takeIf(String::isNotBlank)?.let {
                finishReason = it
            }
            val delta = choice.optJSONObject("delta") ?: continue
            val answer = readContent(delta.opt("content"))
            content.append(answer)
            if (answer.isNotEmpty()) sink?.content(answer, clock.millis())
            if (content.length > MAX_CONTENT_CHARACTERS) {
                throw OpenRouterProtocolException("content_too_large")
            }
            // Providers that send the flat `reasoning` field also tend to repeat the whole thing in
            // `reasoning_details` at the end of the stream. Once the flat form has been seen, the
            // structured one is ignored, or the trace would read the same thought twice.
            val flat = delta.opt("reasoning")
            val thought = if (flat is String && flat.isNotEmpty()) {
                sawFlatReasoning = true
                flat
            } else if (sawFlatReasoning) {
                ""
            } else {
                readReasoning(delta)
            }
            if (thought.isNotEmpty()) {
                if (reasoning.length < MAX_REASONING_CHARACTERS) reasoning.append(thought)
                sink?.reasoning(thought, clock.millis())
            }
            val chunks = delta.optJSONArray("tool_calls") ?: continue
            for (chunkIndex in 0 until chunks.length()) {
                val chunk = chunks.optJSONObject(chunkIndex) ?: continue
                val index = chunk.optInt("index", chunkIndex)
                if (index !in 0 until MAX_TOOL_CALLS) {
                    throw OpenRouterProtocolException("too_many_tool_calls")
                }
                val aggregate = toolCalls.getOrPut(index) { StreamingToolCall() }
                chunk.optString("id").takeIf(String::isNotBlank)?.let { aggregate.id = it }
                val function = chunk.optJSONObject("function")
                function?.optString("name")?.takeIf(String::isNotBlank)?.let {
                    aggregate.name = it
                }
                function?.optString("arguments")
                    ?.takeIf(String::isNotEmpty)
                    ?.let {
                        aggregate.arguments.append(it)
                        if (aggregate.arguments.length > MAX_TOOL_ARGUMENT_CHARACTERS) {
                            throw OpenRouterProtocolException("tool_arguments_too_large")
                        }
                    }
            }
        }
        sink?.flush()
        if (!sawChoice) throw OpenRouterProtocolException("missing_stream_choice")
        return CompletionResult(
            content = content.toString(),
            toolCalls = toolCalls.entries.map { (index, call) ->
                val name = call.name.takeIf(String::isNotBlank)
                    ?: throw OpenRouterProtocolException("missing_tool_name")
                AiToolCall(
                    id = call.id.takeIf(String::isNotBlank) ?: "stream-tool-$index",
                    name = name,
                    argumentsJson = call.arguments.toString().ifBlank { "{}" },
                )
            },
            usage = usage,
            model = model,
            provider = provider,
            finishReason = finishReason,
            reasoning = reasoning.toString().takeIf(String::isNotEmpty),
        )
    }

    private fun encodeMessage(message: AiMessage): JSONObject = JSONObject().apply {
        put("role", message.role.wireValue)
        message.name?.let { put("name", it) }
        message.toolCallId?.let { put("tool_call_id", it) }
        when (val content = message.content) {
            is MessageContent.Text -> {
                if (message.cacheControl == CacheControl.EPHEMERAL) {
                    put("content", JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", content.value)
                            .put("cache_control", JSONObject().put("type", "ephemeral")),
                    ))
                } else {
                    put("content", content.value)
                }
            }
            is MessageContent.Parts -> put("content", JSONArray().apply {
                content.values.forEachIndexed { index, part ->
                    val encoded = encodeContentPart(part)
                    if (
                        message.cacheControl == CacheControl.EPHEMERAL &&
                        index == content.values.lastIndex
                    ) {
                        encoded.put("cache_control", JSONObject().put("type", "ephemeral"))
                    }
                    put(encoded)
                }
            })
            null -> put("content", JSONObject.NULL)
        }
        if (message.toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                message.toolCalls.forEach { call ->
                    put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", call.argumentsJson),
                            ),
                    )
                }
            })
        }
    }

    private fun encodeContentPart(part: ContentPart): JSONObject = when (part) {
        is ContentPart.Text -> JSONObject()
            .put("type", "text")
            .put("text", part.text)
        is ContentPart.ImageUrl -> JSONObject()
            .put("type", "image_url")
            .put(
                "image_url",
                JSONObject()
                    .put("url", part.url)
                    .apply { part.detail?.let { put("detail", it) } },
            )
        is ContentPart.InputAudio -> JSONObject()
            .put("type", "input_audio")
            .put(
                "input_audio",
                JSONObject()
                    .put("data", part.base64Data)
                    .put("format", part.format),
            )
        is ContentPart.VideoUrl -> JSONObject()
            .put("type", "video_url")
            .put("video_url", JSONObject().put("url", part.url))
    }

    private fun encodeTool(tool: ToolDefinition): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", tool.name)
                .put("description", tool.description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("additionalProperties", tool.additionalProperties)
                        .put("properties", JSONObject().apply {
                            tool.parameters.forEach { parameter ->
                                put(parameter.name, JSONObject().apply {
                                    put("type", parameter.type)
                                    put("description", parameter.description)
                                    if (parameter.enumValues.isNotEmpty()) {
                                        put("enum", JSONArray(parameter.enumValues))
                                    }
                                    parameter.minimum?.let { put("minimum", it) }
                                    parameter.maximum?.let { put("maximum", it) }
                                    parameter.maximumLength?.let { put("maxLength", it) }
                                })
                            }
                        })
                        .put(
                            "required",
                            JSONArray(
                                tool.parameters
                                    .filter(ToolParameter::required)
                                    .map(ToolParameter::name),
                            ),
                        ),
                ),
        )

    private fun readResponseText(body: ResponseBody?): String {
        body ?: throw OpenRouterProtocolException("missing_body")
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw OpenRouterProtocolException("response_too_large")
        }
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val remaining = MAX_RESPONSE_BYTES + 1L - total
            if (remaining <= 0L) throw OpenRouterProtocolException("response_too_large")
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) return buffer.readUtf8()
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw OpenRouterProtocolException("response_too_large")
            }
        }
    }

    private fun validateToolCalls(calls: List<AiToolCall>) {
        if (calls.size > MAX_TOOL_CALLS) {
            throw OpenRouterProtocolException("too_many_tool_calls")
        }
        if (calls.any { it.argumentsJson.length > MAX_TOOL_ARGUMENT_CHARACTERS }) {
            throw OpenRouterProtocolException("tool_arguments_too_large")
        }
    }

    private fun endpoint(vararg pathSegments: String): HttpUrl =
        baseUrl.newBuilder().apply {
            pathSegments.forEach(::addPathSegment)
        }.build()

    private data class StreamingToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    companion object {
        /**
         * How long one attempt may stay silent before it is abandoned and re-sent. Two minutes is
         * the point at which a pending answer has stopped being slow and started being stuck: it
         * is far outside normal time-to-first-token even for a reasoning model under load, and it
         * is short enough that a chat still recovers within the same conversation.
         */
        const val DEFAULT_STALL_TIMEOUT_MS = 120_000L

        /**
         * The per-attempt *silence* deadline for a client configured with [configuredTimeoutMs] as
         * its OkHttp read timeout (`OPENROUTER_TIMEOUT_MS`, where `0` means "no HTTP timeout").
         *
         * The watchdog never pre-empts an explicitly configured timeout — someone who raises the
         * setting for a slow reasoning model means it, and getting their calls killed at two
         * minutes anyway would make the setting a lie. It only guarantees that *some* deadline
         * exists: with the HTTP timeout switched off it is the only one left, and where the HTTP
         * timeout is shorter it stays out of the way and lets the more precise error win.
         *
         * Neither deadline bounds how long a whole answer may take, and that is deliberate. The
         * chat client is built without a total call timeout precisely because this watchdog and
         * OkHttp's read timeout both measure a gap between bytes: a memory write that condenses
         * thousands of messages is allowed to run for as long as it keeps producing.
         */
        fun stallDeadlineFor(configuredTimeoutMs: Long): Long =
            if (configuredTimeoutMs <= 0L) {
                DEFAULT_STALL_TIMEOUT_MS
            } else {
                max(configuredTimeoutMs + STALL_GRACE_MS, DEFAULT_STALL_TIMEOUT_MS)
            }

        /** Keeps the HTTP timeout, which knows *why* a call failed, ahead of the blunt deadline. */
        private const val STALL_GRACE_MS = 5_000L
        private const val OPERATION = "chat_completion"
        // Multimodal requests contain base64 audio/images, so the request ceiling must be
        // substantially larger than the text prompt ceiling while still bounding allocation.
        private const val MAX_REQUEST_CHARACTERS = 32 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L
        private const val MAX_RESPONSE_CHARACTERS = 4_000_000
        private const val MAX_CONTENT_CHARACTERS = 512_000

        /**
         * What is kept of the chain of thought, which can outrun the answer several times over.
         * The live trace has its own, much smaller cap; this one only stops a runaway reasoner
         * from holding a megabyte of text alive for the length of a turn.
         */
        private const val MAX_REASONING_CHARACTERS = 256_000
        private const val MAX_TOOL_CALLS = 32
        private const val MAX_TOOL_ARGUMENT_CHARACTERS = 64_000
    }
}

/**
 * OpenRouter slug of the provider that also *built* the model, keyed by the author prefix of the
 * model id.
 *
 * Deliberately incomplete. An author that is not listed here simply is not pinned, which is the
 * harmless outcome; a wrong slug is not, because OpenRouter rejects an unknown provider name. Only
 * makers that demonstrably serve their own models on OpenRouter under a stable slug are listed.
 */
private val FIRST_PARTY_PROVIDERS =
    linkedMapOf(
        "deepseek/" to "deepseek",
        "openai/" to "openai",
        "anthropic/" to "anthropic",
        "google/" to "google-ai-studio",
        "x-ai/" to "xai",
        "mistralai/" to "mistral",
        "cohere/" to "cohere",
        "perplexity/" to "perplexity",
        "moonshotai/" to "moonshotai",
        "z-ai/" to "z-ai",
    )

internal fun firstPartyProviderFor(model: String): String? =
    FIRST_PARTY_PROVIDERS.entries
        .firstOrNull { model.startsWith(it.key, ignoreCase = true) }
        ?.value

/**
 * The provider slug a routing policy pinned the request to, or null when nothing was pinned.
 *
 * Reading it back out of the policy string keeps the check where the answer is — an observer sees
 * the policy and the provider that actually answered, and nothing else has to be threaded through.
 */
internal fun pinnedProviderSlug(routingPolicy: String?): String? =
    routingPolicy?.removePrefix("original_")?.removeSuffix("_only")?.takeIf {
        routingPolicy.startsWith("original_") && routingPolicy.endsWith("_only") && it.isNotEmpty()
    }

/**
 * Whether a request that was pinned to one host came back answered by a different one.
 *
 * OpenRouter names the provider by display name ("DeepSeek", "GMICloud") while the pin is a slug
 * ("deepseek"), and an endpoint may carry a variant suffix ("deepseek/fp8"), so the comparison is
 * deliberately loose: only a name that cannot be the pinned provider at all counts as a mismatch.
 */
internal fun servedAgainstPin(
    routingPolicy: String?,
    providerName: String?,
): Boolean {
    val slug = pinnedProviderSlug(routingPolicy) ?: return false
    val served = providerName?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val normalized = served.lowercase().replace("-", "").replace(" ", "")
    return !normalized.startsWith(slug.lowercase().replace("-", ""))
}

internal fun authHeaders(
    apiKey: String,
    attribution: OpenRouterAttribution,
): Headers = Headers.Builder()
    .add("Authorization", "Bearer $apiKey")
    .add("Accept", "application/json, text/event-stream")
    .apply {
        attribution.referer?.trim()?.takeIf(String::isNotEmpty)?.let {
            add("HTTP-Referer", it)
        }
        attribution.title?.trim()?.takeIf(String::isNotEmpty)?.let {
            add("X-OpenRouter-Title", it)
        }
    }
    .build()

internal fun Response.requireOpenRouterSuccess(nowMs: Long) {
    if (!isSuccessful) throw toOpenRouterHttpException(nowMs)
}

/** One retry loop for chat, speech and model-catalog requests. */
internal suspend fun <T> executeWithRetry(
    policy: RetryPolicy,
    retryable: (Throwable) -> Boolean = RetryClassifier::isRetryableThrowable,
    onFailure: (failure: Throwable, attempt: Int) -> Unit,
    onRetry: (failure: Throwable, attempt: Int, delayMs: Long) -> Unit,
    request: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return request(attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!retryable(failure) || attempt >= policy.maxAttempts) {
                onFailure(failure, attempt)
                throw failure
            }
            val waitMs =
                (failure as? OpenRouterHttpException)?.retryAfterMs
                    ?: policy.delayForAttempt(attempt)
            onRetry(failure, attempt, waitMs)
            attempt += 1
            if (waitMs > 0) delay(waitMs)
        }
    }
}

/** Shared safe metadata projection for a final OpenRouter failure. */
internal fun Throwable.toNetworkFailure(
    operation: String,
    attempt: Int,
    reasonCode: String,
    elapsedMs: Long,
    model: String? = null,
    requestTag: String? = null,
    prompt: PromptFingerprint? = null,
): AiNetworkEvent.Failed {
    val http = this as? OpenRouterHttpException
    return AiNetworkEvent.Failed(
        operation = operation,
        statusCode = http?.statusCode,
        attempt = attempt,
        reasonCode = reasonCode,
        model = model,
        elapsedMs = elapsedMs,
        endpointPath = http?.endpointPath,
        responseContentType = http?.responseContentType,
        providerRequestId = http?.providerRequestId,
        responseBodyKind = http?.responseBodyKind,
        providerErrorType = http?.providerErrorType,
        providerErrorCode = http?.providerErrorCode,
        providerName = http?.providerName,
        errorMetadataKeys = http?.errorMetadataKeys.orEmpty(),
        errorFingerprint = http?.errorFingerprint,
        responseContentLength = http?.responseContentLength,
        unsupportedParameter = http?.unsupportedParameter,
        requestTag = requestTag,
        prompt = prompt,
    )
}

internal fun AiNetworkObserver.emit(event: AiNetworkEvent) {
    try {
        onEvent(event)
    } catch (_: Exception) {
        // Diagnostics must never retry, fail, or otherwise alter a paid provider request.
    }
}

internal suspend fun <T> Call.awaitParsed(parser: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    response.use { continuation.resume(parser(it)) }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

internal fun parseUsage(usage: JSONObject?): TokenUsage? {
    usage ?: return null
    val details = usage.optJSONObject("prompt_tokens_details")
    return TokenUsage(
        promptTokens = usage.optionalInt("prompt_tokens"),
        completionTokens = usage.optionalInt("completion_tokens"),
        totalTokens = usage.optionalInt("total_tokens"),
        cachedPromptTokens = details?.optionalInt("cached_tokens")
            ?: usage.optionalInt("cached_tokens"),
        cacheWriteTokens = details?.optionalInt("cache_write_tokens")
            ?: usage.optionalInt("cache_write_tokens"),
    )
}

internal fun readToolCalls(array: JSONArray?): List<AiToolCall> {
    array ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name").trim()
            if (name.isEmpty()) continue
            val rawArguments = function.opt("arguments")
            val arguments = when (rawArguments) {
                is JSONObject -> rawArguments.toString()
                is String -> rawArguments.ifBlank { "{}" }
                null, JSONObject.NULL -> "{}"
                else -> continue
            }
            add(
                AiToolCall(
                    id = item.optString("id").trim().ifEmpty { "tool-$index" },
                    name = name,
                    argumentsJson = arguments,
                ),
            )
        }
    }
}

internal fun readContent(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> buildString {
        for (index in 0 until value.length()) {
            when (val part = value.opt(index)) {
                is String -> append(part)
                is JSONObject -> {
                    val type = part.optString("type")
                    if (type == "text" || type == "output_text") {
                        append(part.optString("text"))
                    }
                }
            }
        }
    }
    else -> ""
}

private fun JSONObject.optionalInt(name: String): Int? =
    takeIf { has(name) && !isNull(name) }?.optInt(name)

private fun Throwable.reasonCode(): String = when (this) {
    is OpenRouterHttpException -> reasonCode
    is OpenRouterProtocolException -> reasonCode
    is OpenRouterStallException -> "stalled"
    is IOException -> "network_io"
    else -> "unexpected"
}

/**
 * Turns the bounded OpenRouter error envelope into a stable, privacy-safe code.
 * The provider message itself is deliberately never persisted because it can
 * occasionally repeat request data. [Response.peekBody] leaves the response
 * lifecycle untouched and caps memory used for a hostile error page.
 */
internal data class OpenRouterFailureDiagnostics(
    val reasonCode: String,
    val endpointPath: String,
    val responseContentType: String?,
    val providerRequestId: String?,
    val responseBodyKind: String,
    val providerErrorType: String?,
    val providerErrorCode: String?,
    val providerName: String?,
    val errorMetadataKeys: List<String>,
    val errorFingerprint: String?,
    val responseContentLength: Long?,
    val unsupportedParameter: String?,
)

/**
 * Extracts only allow-listed routing metadata from a bounded error envelope.
 * Provider text is classified and hashed for correlation, but never returned,
 * persisted, or shown because an upstream error can echo prompt content.
 */
internal fun diagnoseOpenRouterFailure(response: Response): OpenRouterFailureDiagnostics {
    val raw = runCatching { response.peekBody(MAX_ERROR_BODY_BYTES).string() }.getOrDefault("")
    val root = runCatching { JSONObject(raw) }.getOrNull()
    val errorObject = root?.optJSONObject("error")
    val message =
        when (val error = root?.opt("error")) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> ""
        }.lowercase()
    val metadata = errorObject?.optJSONObject("metadata")
    val metadataKeys = metadata?.keys()?.asSequence()
        ?.map(String::trim)
        ?.filter { it.matches(SAFE_METADATA_KEY) }
        ?.distinct()
        ?.sorted()
        ?.take(MAX_ERROR_METADATA_KEYS)
        ?.toList()
        .orEmpty()
    val providerErrorType = safeDiagnosticValue(metadata?.opt("error_type")?.toString().orEmpty())
    val providerErrorCode =
        listOfNotNull(
            metadata?.opt("provider_code")?.toString(),
            errorObject?.opt("code")?.toString(),
            root?.opt("code")?.toString(),
        ).firstNotNullOfOrNull(::safeDiagnosticValue)
    val providerName =
        listOfNotNull(
            metadata?.opt("provider_name")?.toString(),
            metadata?.opt("provider")?.toString(),
            errorObject?.opt("provider")?.toString(),
        ).firstNotNullOfOrNull(::safeDiagnosticValue)
    val canonicalErrorType = providerErrorType?.takeIf {
        it in setOf(
            "authentication",
            "permission_denied",
            "payment_required",
            "rate_limit_exceeded",
            "provider_overloaded",
            "provider_unavailable",
            "timeout",
            "invalid_request",
            "invalid_prompt",
            "not_found",
            "content_policy_violation",
            "refusal",
            "server",
            "unmapped",
        )
    }
    val reasonCode = canonicalErrorType ?: when {
        "no endpoints found" in message ||
            "no endpoint found" in message ||
            "no endpoints available" in message ||
            "no available endpoints" in message ||
            "no available provider" in message ||
            "no compatible provider" in message ||
            ("endpoint" in message && "not available" in message) ||
            ("endpoint" in message && "not found" in message) -> "no_compatible_endpoint"
        "model not found" in message ||
            "unknown model" in message ||
            "not a valid model" in message ||
            "model is not available" in message -> "model_not_found"
        "data policy" in message || "privacy policy" in message -> "data_policy"
        "unsupported parameter" in message ||
            "does not support" in message ||
            "not supported" in message -> "unsupported_parameter"
        // OpenRouter reserves the whole max_tokens budget up front, so a thin balance fails the
        // expensive calls first and leaves the cheap ones running. That looked exactly like "video
        // analysis is broken while chat works", and `http_402` in the log said nothing about money.
        // The wording is OpenRouter's own and carries no prompt content.
        "more credits" in message ||
            "insufficient credit" in message ||
            "can only afford" in message ||
            response.code == 402 -> "insufficient_credits"
        response.code == 401 || response.code == 403 -> "authentication_or_policy"
        response.code == 408 -> "provider_timeout"
        response.code == 429 -> "rate_limited"
        response.code in 500..599 -> "provider_unavailable"
        else -> "http_${response.code}"
    }
    return OpenRouterFailureDiagnostics(
        reasonCode = reasonCode,
        endpointPath = response.request.url.encodedPath,
        responseContentType = response.header("Content-Type")?.take(MAX_DIAGNOSTIC_VALUE_CHARS),
        providerRequestId =
            (response.header("X-Generation-Id") ?: response.header("x-request-id"))
                ?.take(MAX_REQUEST_ID_CHARS),
        responseBodyKind = responseBodyKind(raw, root),
        providerErrorType = providerErrorType,
        providerErrorCode = providerErrorCode,
        providerName = providerName,
        errorMetadataKeys = metadataKeys,
        errorFingerprint = raw.takeIf(String::isNotBlank)?.let(::diagnosticFingerprint),
        responseContentLength = response.body?.contentLength()?.takeIf { it >= 0L },
        unsupportedParameter = unsupportedParameterIn(message),
    )
}

/**
 * The request field OpenRouter says no endpoint supports, out of a "no endpoints found" message.
 *
 * The message itself stays unpersisted for the reason above, but the routing variant of it names
 * exactly one thing, and that name is the whole diagnosis: "no endpoints found that support the
 * provided 'output_format' value" is the difference between a model that refuses a picture and one
 * that was never asked for it. Only the quoted token is kept, and only when it is a bare identifier
 * — a message quoting anything else contributes nothing rather than risking prompt text.
 */
internal fun unsupportedParameterIn(message: String): String? =
    UNSUPPORTED_PARAMETER.find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()
        ?.takeIf { it.matches(SAFE_PARAMETER_NAME) }

private val UNSUPPORTED_PARAMETER =
    Regex(
        """no endpoints? found that supports? (?:the provided )?'([^']{1,40})'""",
        RegexOption.IGNORE_CASE,
    )

private val SAFE_PARAMETER_NAME = Regex("[a-z][a-z0-9_.]{0,39}")

internal fun Response.toOpenRouterHttpException(nowMs: Long): OpenRouterHttpException {
    val diagnostics = diagnoseOpenRouterFailure(this)
    return OpenRouterHttpException(
        statusCode = code,
        retryAfterMs = RetryClassifier.retryAfterMillis(header("Retry-After"), nowMs),
        providerRequestId = diagnostics.providerRequestId,
        reasonCode = diagnostics.reasonCode,
        endpointPath = diagnostics.endpointPath,
        responseContentType = diagnostics.responseContentType,
        responseBodyKind = diagnostics.responseBodyKind,
        providerErrorType = diagnostics.providerErrorType,
        providerErrorCode = diagnostics.providerErrorCode,
        providerName = diagnostics.providerName,
        errorMetadataKeys = diagnostics.errorMetadataKeys,
        errorFingerprint = diagnostics.errorFingerprint,
        responseContentLength = diagnostics.responseContentLength,
        unsupportedParameter = diagnostics.unsupportedParameter,
    )
}

private fun safeDiagnosticValue(value: String): String? =
    value.trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_DIAGNOSTIC_VALUE_CHARS }
        ?.takeIf { it.matches(SAFE_DIAGNOSTIC_VALUE) }

private fun responseBodyKind(raw: String, root: JSONObject?): String =
    when {
        raw.isBlank() -> "empty"
        root?.has("error") == true -> "json_error"
        root != null -> "json"
        raw.trimStart().startsWith("<") -> "html"
        else -> "text"
    }

private fun ByteArray.toShortHex(): String =
    take(8).joinToString("") { byte -> "%02x".format(byte) }

/**
 * Stable textual stand-in for a message's content, used only as digest input.
 * Binary parts contribute their length instead of their bytes: hashing a 16 MiB image would cost
 * far more than the diagnostic is worth, and the length already changes whenever the image does.
 */
private fun AiMessage.digestText(): String = buildString {
    name?.let { append(it) }
    toolCallId?.let { append(it) }
    toolCalls.forEach { append(it.name).append(it.argumentsJson) }
    when (val body = content) {
        null -> Unit
        is MessageContent.Text -> append(body.value)
        is MessageContent.Parts ->
            body.values.forEach { part ->
                when (part) {
                    is ContentPart.Text -> append(part.text)
                    is ContentPart.ImageUrl -> append("image:").append(part.url.length)
                    is ContentPart.InputAudio ->
                        append("audio:").append(part.format).append(part.base64Data.length)
                    is ContentPart.VideoUrl -> append("video:").append(part.url.length)
                }
            }
    }
}

private fun diagnosticFingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte) }

private const val MAX_ERROR_BODY_BYTES = 16L * 1024L
private const val MAX_ERROR_METADATA_KEYS = 12
private const val MAX_DIAGNOSTIC_VALUE_CHARS = 120
private const val MAX_REQUEST_ID_CHARS = 200
private val SAFE_METADATA_KEY = Regex("[A-Za-z0-9_.-]{1,80}")
private val SAFE_DIAGNOSTIC_VALUE = Regex("[A-Za-z0-9_./:@ -]+")

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
