package de.totec.doppel.ai

import java.time.Clock
import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
)

data class ImageReferenceInput(
    val bytes: ByteArray,
    val mimeType: String,
) {
    init {
        require(bytes.isNotEmpty() && bytes.size <= ImageGenerationRequest.MAX_REFERENCE_BYTES)
        require(mimeType in ImageGenerationRequest.ALLOWED_REFERENCE_MIME_TYPES)
    }
}

data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val references: List<ImageReferenceInput> = emptyList(),
    val quality: String,
) {
    init {
        require(model.isNotBlank() && model.length <= 256)
        require(prompt.isNotBlank() && prompt.length <= 8_000)
        require(references.size <= MAX_REFERENCES)
        require(references.sumOf { it.bytes.size.toLong() } <= MAX_COMBINED_REFERENCE_BYTES)
        require(quality in setOf("very_low", "low", "medium", "high"))
    }

    companion object {
        const val MAX_REFERENCE_BYTES = 4 * 1024 * 1024
        const val MAX_REFERENCES = 8
        const val MAX_COMBINED_REFERENCE_BYTES = 16L * 1024L * 1024L
        val ALLOWED_REFERENCE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Dedicated OpenRouter Images API client. One request produces exactly one bounded JPEG. */
class OpenRouterImageClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val apiKeyProvider: OpenRouterApiKeyProvider,
    private val attribution: OpenRouterAttribution = OpenRouterAttribution(),
    private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 2),
    private val observer: AiNetworkObserver = AiNetworkObserver.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val firstPartyProviderOnly: () -> Boolean = { true },
    private val imageModels: ImageModelParameterSource =
        OpenRouterImageModelCatalog(
            httpClient = httpClient,
            baseUrl = baseUrl,
            apiKeyProvider = apiKeyProvider,
            attribution = attribution,
            clock = clock,
        ),
) {
    suspend fun generate(request: ImageGenerationRequest): GeneratedImage {
        val apiKey = apiKeyProvider.apiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) throw MissingApiKeyException()
        val startedAt = clock.millis()
        val provider = firstPartyProviderFor(request.model).takeIf { firstPartyProviderOnly() }
        // Only what this model declares. Every generation used to carry `output_format`,
        // `output_compression` and `background` unconditionally; three quarters of the image
        // catalog — Grok Imagine among them — takes none of the three, and OpenRouter answers a
        // request naming a parameter its endpoints do not support with 404, before any provider
        // sees it. That is why every generation failed while nothing at all showed up in the
        // OpenRouter activity log.
        val supported = imageModels.parametersFor(request.model)
        val payload =
            JSONObject()
                .put("model", request.model)
                .put("prompt", request.prompt)
                .apply {
                    supported?.clamp("n", 1)?.let { put("n", it) }
                    supported?.firstAllowed("quality", qualityPreference(request.quality))
                        ?.let { put("quality", it) }
                    val outputFormat =
                        supported?.firstAllowed("output_format", OUTPUT_FORMAT_PREFERENCE)
                    outputFormat?.let { put("output_format", it) }
                    // Compression is a JPEG/WebP option. GPT Image 2 currently declares
                    // output_compression but no output_format through OpenRouter, which otherwise
                    // combines compression with the provider's PNG default and can be rejected as
                    // HTTP 400. Omit the meaningless pair instead of guessing a hidden format.
                    if (outputFormat in COMPRESSIBLE_OUTPUT_FORMATS) {
                        supported?.clamp("output_compression", compression(request.quality))
                            ?.let { put("output_compression", it) }
                    }
                    supported?.firstAllowed("background", BACKGROUND_PREFERENCE)
                        ?.let { put("background", it) }
                    // Every image model takes references, but not the same number of them: three
                    // for Grok Imagine, sixteen for GPT Image 1. One too many is the same 404 as an
                    // unsupported parameter, so the extras are dropped rather than the request.
                    val references =
                        supported?.maxOf("input_references")
                            ?.let(request.references::take)
                            ?: request.references
                    if (references.isNotEmpty()) {
                        put(
                            "input_references",
                            JSONArray().apply {
                                references.forEach { reference ->
                                    put(
                                        JSONObject()
                                            .put("type", "image_url")
                                            .put(
                                                "image_url",
                                                JSONObject().put(
                                                    "url",
                                                    "data:${reference.mimeType};base64," +
                                                        Base64.getEncoder().encodeToString(reference.bytes),
                                                ),
                                            ),
                                    )
                                }
                            },
                        )
                    }
                    provider?.let { slug ->
                        put(
                            "provider",
                            JSONObject()
                                .put("only", JSONArray().put(slug))
                                .put("order", JSONArray().put(slug))
                                .put("allow_fallbacks", false)
                                .apply {
                                    // OpenRouter exposes OpenAI moderation as a provider
                                    // passthrough. Sending it at the image payload's top level made
                                    // GPT Image 2 reject every request with HTTP 400.
                                    if (slug == "openai") {
                                        put(
                                            "options",
                                            JSONObject().put(
                                                slug,
                                                JSONObject().put("moderation", "low"),
                                            ),
                                        )
                                    }
                                },
                        )
                    }
                }
        val httpRequest =
            Request.Builder()
                .url(baseUrl.newBuilder().addPathSegment("images").build())
                .headers(authHeaders(apiKey, attribution))
                .post(payload.toString().toRequestBody(JSON))
                .build()
        // An image is the most expensive thing this app can ask for, so the second attempt is only
        // allowed while the first one is known to have produced nothing: `responseStarted` goes up
        // when the response headers are in hand, and from that point the picture is being rendered
        // and billed no matter what the socket does afterwards.
        var responseStarted = false
        return executeWithRetry(
            policy = retryPolicy,
            retryable = { failure ->
                RetryClassifier.isRetryableThrowable(failure, responseStarted = responseStarted)
            },
            onFailure = { failure, attempt ->
                observer.emit(
                    failure.toNetworkFailure(
                        operation = OPERATION,
                        attempt = attempt,
                        // What OpenRouter said, when it said anything. The flat
                        // `image_generation_failed` used to overwrite the diagnosis, so a routing
                        // 404 and a content refusal reached the log as the same sentence and the
                        // trace could only show "OpenRouter HTTP 404".
                        reasonCode =
                            (failure as? OpenRouterHttpException)?.reasonCode
                                ?: "image_generation_failed",
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAt).coerceAtLeast(0L),
                    ),
                )
            },
            onRetry = { _, attempt, waitMs ->
                observer.emit(
                    AiNetworkEvent.Retrying(
                        operation = OPERATION,
                        attempt = attempt,
                        delayMs = waitMs,
                        reasonCode = "image_generation_retry",
                        model = request.model,
                        elapsedMs = (clock.millis() - startedAt).coerceAtLeast(0L),
                    ),
                )
            },
        ) { attempt ->
            responseStarted = false
            observer.emit(
                AiNetworkEvent.Started(
                    operation = OPERATION,
                    attempt = attempt,
                    model = request.model,
                    routingPolicy = provider?.let { "original_${it}_only" },
                ),
            )
            val generated =
                httpClient.newCall(httpRequest).awaitParsed { response ->
                    responseStarted = true
                    response.requireOpenRouterSuccess(clock.millis())
                    parse(response.body?.string().orEmpty())
                }
            observer.emit(
                AiNetworkEvent.Succeeded(
                    operation = OPERATION,
                    statusCode = 200,
                    attempt = attempt,
                    model = request.model,
                    elapsedMs = (clock.millis() - startedAt).coerceAtLeast(0L),
                    responseContentType = generated.mimeType,
                    routingPolicy = provider?.let { "original_${it}_only" },
                ),
            )
            generated
        }
    }

    internal fun parse(raw: String): GeneratedImage {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw OpenRouterProtocolException("invalid_image_json") }
        val item = root.optJSONArray("data")?.optJSONObject(0)
            ?: throw OpenRouterProtocolException("missing_generated_image")
        val encoded = item.optString("b64_json").trim()
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARACTERS) {
            throw OpenRouterProtocolException("generated_image_too_large")
        }
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw OpenRouterProtocolException("invalid_generated_image") }
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
            throw OpenRouterProtocolException("generated_image_too_large")
        }
        val mime = item.optString("media_type").substringBefore(';').lowercase()
        val effectiveMime = mime.takeIf { it in ALLOWED_MIME_TYPES } ?: sniffMime(bytes)
        return GeneratedImage(bytes, effectiveMime)
    }

    private fun sniffMime(bytes: ByteArray): String =
        when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> throw OpenRouterProtocolException("unsupported_generated_image")
        }

    private fun compression(quality: String): Int =
        when (quality) {
            "very_low" -> 42
            "low" -> 58
            "medium" -> 76
            else -> 90
        }

    /**
     * The requested tier first, then the nearest one the model does offer.
     *
     * `very_low` is this app's own tier and not one of the route's four, so it asks for the cheapest
     * real one. `auto` is last everywhere: it is the provider's default rather than a price, and
     * falling into it silently is how a cheap generation turns expensive.
     */
    private fun qualityPreference(quality: String): List<String> =
        when (quality) {
            "very_low", "low" -> listOf("low", "medium", "high", "auto")
            "medium" -> listOf("medium", "low", "high", "auto")
            else -> listOf("high", "medium", "low", "auto")
        }

    private companion object {
        /** JPEG for the size, WebP next; PNG only because a model offering neither still works. */
        val OUTPUT_FORMAT_PREFERENCE = listOf("jpeg", "webp", "png")
        val COMPRESSIBLE_OUTPUT_FORMATS = setOf("jpeg", "webp")

        /** A transparent background reaches WhatsApp as black, so it is never the second choice. */
        val BACKGROUND_PREFERENCE = listOf("opaque", "auto")

        val JSON = "application/json; charset=utf-8".toMediaType()
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        const val OPERATION = "image_generation"
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        const val MAX_ENCODED_CHARACTERS = 34 * 1024 * 1024
    }
}
