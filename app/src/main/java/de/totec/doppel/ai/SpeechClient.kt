package de.totec.doppel.ai

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock

data class SpeechRequest(
    val model: String,
    val text: String,
    val voice: String,
    val instructions: String? = null,
    val speed: Double? = null,
) {
    init {
        require(model.isNotBlank() && model.length <= 256)
        require(text.isNotBlank() && text.length <= 20_000)
        require(voice.isNotBlank() && voice.length <= 128)
        require(instructions == null || instructions.length <= 2_000)
        require(speed == null || speed in 0.25..4.0)
    }

    /**
     * The speaking style as an `instructions` parameter — OpenAI only.
     *
     * `instructions` is not a portable top-level parameter of the OpenRouter speech endpoint.
     * Sending it to Gemini makes the router filter out that model's only provider and answer with
     * HTTP 404, so it only ever goes to OpenAI, inside its provider namespace.
     */
    fun providerInstructions(): String? = styleDirection()?.takeIf { takesInstructionsParameter() }

    /**
     * The words as the provider will really receive them.
     *
     * Every other provider reads its direction from the input itself: Gemini takes a bracketed
     * instruction at the start of the text — the same `[angry]` / `[whispers]` markup the model
     * already writes inline — and never speaks it. So the style rides along as that prefix instead
     * of being dropped, which is what used to happen: the fixed style block reached OpenAI and
     * silently reached nothing else.
     */
    fun providerText(): String {
        val direction = styleDirection()?.takeUnless { takesInstructionsParameter() } ?: return text
        // One bracket, one line: a newline or a stray `]` inside would end the tag early and leave
        // the rest of the style to be read out loud as if it were the message.
        val inlineDirection =
            direction
                .replace(BRACKET_BREAKERS, " ")
                .replace(REPEATED_SPACE, " ")
                .trim()
        return if (inlineDirection.isEmpty()) text else "[$inlineDirection] $text"
    }

    /** How the composed style actually travels, so a trace can never overstate it. */
    fun styleDelivery(): String =
        when {
            styleDirection() == null -> "none"
            takesInstructionsParameter() -> "instructions"
            else -> "text_prefix"
        }

    private fun styleDirection(): String? = instructions?.trim()?.takeIf(String::isNotEmpty)

    private fun takesInstructionsParameter(): Boolean =
        model.startsWith("openai/", ignoreCase = true)

    private companion object {
        val BRACKET_BREAKERS = Regex("""[\[\]\r\n]+""")
        val REPEATED_SPACE = Regex("""\s{2,}""")
    }
}

data class PcmAudio(
    val bytes: ByteArray,
    val sampleRateHz: Int = 24_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
    val littleEndian: Boolean = true,
) {
    init {
        require(bytes.isNotEmpty())
        require(sampleRateHz > 0)
        require(channelCount > 0)
        require(bitsPerSample in setOf(8, 16, 24, 32))
        require(bytes.size % (bitsPerSample / 8 * channelCount) == 0) {
            "PCM payload must contain complete samples"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PcmAudio &&
            bytes.contentEquals(other.bytes) &&
            sampleRateHz == other.sampleRateHz &&
            channelCount == other.channelCount &&
            bitsPerSample == other.bitsPerSample &&
            littleEndian == other.littleEndian

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channelCount
        result = 31 * result + bitsPerSample
        result = 31 * result + littleEndian.hashCode()
        return result
    }
}

class SpeechClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val apiKeyProvider: OpenRouterApiKeyProvider,
    private val attribution: OpenRouterAttribution = OpenRouterAttribution(),
    // TTS is a secondary output path. Never spend a second paid request after
    // an ambiguous provider failure; the engine can fall back to text safely.
    private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
    private val maximumResponseBytes: Long = 24L * 1024 * 1024,
    private val observer: AiNetworkObserver = AiNetworkObserver.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val firstPartyProviderOnly: () -> Boolean = { true },
) {
    init {
        require(maximumResponseBytes > 0)
    }

    suspend fun synthesize(request: SpeechRequest): PcmAudio {
        val apiKey = apiKeyProvider.apiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) throw MissingApiKeyException()
        val providerSlug =
            firstPartyProviderFor(request.model).takeIf { firstPartyProviderOnly() }
        val provider =
            JSONObject().apply {
                request.providerInstructions()?.let { instructions ->
                    put(
                        "options",
                        JSONObject().put(
                            "openai",
                            JSONObject().put("instructions", instructions),
                        ),
                    )
                }
                providerSlug?.let { slug ->
                    put("order", JSONArray().put(slug))
                    put("only", JSONArray().put(slug))
                    put("allow_fallbacks", false)
                }
            }
        val json = JSONObject()
            .put("model", request.model)
            .put("input", request.providerText())
            .put("voice", request.voice)
            .apply {
                // OpenRouter's speech endpoint defaults to raw 24 kHz PCM. Do not send
                // `response_format=pcm` explicitly: provider adapters are allowed to
                // expose PCM as their default while omitting `response_format` from
                // their supported-parameter list. Explicitly adding it can therefore
                // make the router reject an otherwise valid TTS endpoint with 404.
                // See SpeechRequest.providerInstructions: the speaking style only survives for
                // OpenAI models, and it travels inside their provider namespace.
                if (provider.length() > 0) put("provider", provider)
                request.speed?.let { put("speed", it) }
            }
        val httpRequest = Request.Builder()
            .url(
                baseUrl.newBuilder()
                    .addPathSegment("audio")
                    .addPathSegment("speech")
                    .build(),
            )
            // The documented raw REST example sends no Accept header. Remove
            // the chat client's JSON/SSE Accept value instead of asking the
            // router to content-negotiate a specific provider representation.
            .headers(
                authHeaders(apiKey, attribution)
                    .newBuilder()
                    .removeAll("Accept")
                    .build(),
            )
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()

        val startedAtMs = clock.millis()
        return executeWithRetry(
            policy = retryPolicy,
            onFailure = { failure, attempt ->
                observer.emit(
                    failure.toNetworkFailure(
                        operation = OPERATION,
                        attempt = attempt,
                        reasonCode =
                            (failure as? OpenRouterHttpException)?.reasonCode
                                ?: (failure as? OpenRouterProtocolException)?.reasonCode
                                ?: "speech_failure",
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                    ),
                )
            },
            onRetry = { _, attempt, waitMs ->
                observer.emit(
                    AiNetworkEvent.Retrying(
                        operation = OPERATION,
                        attempt = attempt,
                        delayMs = waitMs,
                        reasonCode = "speech_retry",
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                    ),
                )
            },
        ) { attempt ->
                observer.emit(
                    AiNetworkEvent.Started(
                        operation = OPERATION,
                        attempt = attempt,
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                    ),
                )
                httpClient.newCall(httpRequest).awaitParsed { response ->
                    response.requireOpenRouterSuccess(clock.millis())
                    val body = response.body
                        ?: throw OpenRouterProtocolException("missing_speech_body")
                    if (
                        body.contentType()?.subtype?.contains("json", ignoreCase = true) == true
                    ) {
                        throw OpenRouterProtocolException("unexpected_speech_json")
                    }
                    val declaredLength = body.contentLength()
                    if (declaredLength > maximumResponseBytes) {
                        throw OpenRouterProtocolException("speech_too_large")
                    }
                    val bytes = readLimited(body.source())
                    observer.emit(
                        AiNetworkEvent.Succeeded(
                            operation = OPERATION,
                            statusCode = response.code,
                            attempt = attempt,
                            model = request.model,
                            elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                            responseContentType = body.contentType()?.toString(),
                            providerRequestId =
                                response.header("X-Generation-Id")
                                    ?: response.header("x-request-id"),
                        ),
                    )
                    PcmAudio(bytes)
                }
        }
    }

    private fun readLimited(source: BufferedSource): ByteArray {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val remainingProbe = maximumResponseBytes + 1 - total
            if (remainingProbe <= 0) {
                throw OpenRouterProtocolException("speech_too_large")
            }
            val read = source.read(buffer, minOf(8_192L, remainingProbe))
            if (read == -1L) return buffer.readByteArray()
            total += read
            if (total > maximumResponseBytes) {
                throw OpenRouterProtocolException("speech_too_large")
            }
        }
    }

    private companion object {
        const val OPERATION = "speech"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
