package de.totec.doppel.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Clock

enum class ModelCapability {
    TEXT_INPUT,
    IMAGE_INPUT,
    AUDIO_INPUT,
    VIDEO_INPUT,
    TEXT_OUTPUT,
    IMAGE_OUTPUT,
    AUDIO_OUTPUT,
    TRANSCRIPTION_OUTPUT,
    TOOLS,
    REASONING,
    STRUCTURED_OUTPUT,
}

data class ModelPricing(
    val promptPerToken: Double? = null,
    val completionPerToken: Double? = null,
    val imagePerUnit: Double? = null,
    val imageOutputPerToken: Double? = null,
    val audioPerUnit: Double? = null,
    val request: Double? = null,
    /** Duration-priced transcription normalized to one hour; null means token-priced or unknown. */
    val transcriptionPerHour: Double? = null,
)

/** Provider-declared reasoning controls for one concrete model. */
data class ModelReasoning(
    val supportedEfforts: List<String>?,
    val defaultEffort: String?,
    val defaultEnabled: Boolean?,
    val mandatory: Boolean,
    val supportsMaxTokens: Boolean,
)

data class OpenRouterModel(
    val id: String,
    val name: String,
    val description: String?,
    val createdAtEpochSeconds: Long? = null,
    val contextLength: Int?,
    val capabilities: Set<ModelCapability>,
    val supportedParameters: Set<String>,
    val reasoning: ModelReasoning? = null,
    val supportedVoices: List<String> = emptyList(),
    val pricing: ModelPricing,
) {
    init {
        require(id.isNotBlank())
    }

    fun supports(role: ModelRole): Boolean = when (role) {
        ModelRole.MAIN ->
            ModelCapability.TEXT_INPUT in capabilities &&
                ModelCapability.TEXT_OUTPUT in capabilities &&
                ModelCapability.TOOLS in capabilities
        ModelRole.UTILITY,
        ModelRole.VERIFY,
        -> ModelCapability.TEXT_INPUT in capabilities &&
            ModelCapability.TEXT_OUTPUT in capabilities
        ModelRole.MEDIA ->
            ModelCapability.TEXT_OUTPUT in capabilities &&
                capabilities.any {
                    it == ModelCapability.IMAGE_INPUT ||
                        it == ModelCapability.AUDIO_INPUT ||
                        it == ModelCapability.VIDEO_INPUT
                }
        ModelRole.IMAGE -> ModelCapability.IMAGE_OUTPUT in capabilities
        ModelRole.TTS -> ModelCapability.AUDIO_OUTPUT in capabilities
        // Narrower than MEDIA on purpose: a model that reads pictures but not sound is exactly the
        // one this role exists to stand in for.
        ModelRole.TRANSCRIBE ->
            ModelCapability.AUDIO_INPUT in capabilities &&
                ModelCapability.TRANSCRIPTION_OUTPUT in capabilities
    }
}

data class CachedModelCatalog(
    val models: List<OpenRouterModel>,
    val fetchedAtEpochMs: Long,
)

fun interface ModelCatalogReader {
    suspend fun read(): CachedModelCatalog?
}

interface ModelCatalogCache : ModelCatalogReader {
    suspend fun write(value: CachedModelCatalog)
}

class InMemoryModelCatalogCache : ModelCatalogCache {
    @Volatile
    private var value: CachedModelCatalog? = null

    override suspend fun read(): CachedModelCatalog? = value

    override suspend fun write(value: CachedModelCatalog) {
        this.value = value
    }
}

data class ModelCatalogResult(
    val models: List<OpenRouterModel>,
    val fetchedAtEpochMs: Long,
    val stale: Boolean,
)

/**
 * Fetches model objects, not a copied list of slugs. A stale cache remains usable when the phone
 * is offline, and the mutex prevents concurrent settings screens from duplicating a catalog call.
 */
class ModelCatalogClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val apiKeyProvider: OpenRouterApiKeyProvider,
    private val cache: ModelCatalogCache,
    private val attribution: OpenRouterAttribution = OpenRouterAttribution(),
    private val cacheTtlMs: Long = 6 * 60 * 60 * 1_000L,
    private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 2),
    private val observer: AiNetworkObserver = AiNetworkObserver.NONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val refreshMutex = Mutex()

    init {
        require(cacheTtlMs >= 0)
    }

    suspend fun getCatalog(forceRefresh: Boolean = false): ModelCatalogResult =
        refreshMutex.withLock {
            val cached = cache.read()
            val now = clock.millis()
            if (
                !forceRefresh &&
                cached != null &&
                now - cached.fetchedAtEpochMs in 0..cacheTtlMs
            ) {
                return@withLock cached.asResult(stale = false)
            }

            try {
                val models = fetchModels()
                val refreshed = CachedModelCatalog(
                    models = models,
                    fetchedAtEpochMs = clock.millis(),
                )
                cache.write(refreshed)
                refreshed.asResult(stale = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                cached?.asResult(stale = true) ?: throw failure
            }
        }

    internal fun parseModels(raw: String): List<OpenRouterModel> {
        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw OpenRouterProtocolException("invalid_model_catalog")
        }
        if (root.has("error")) throw OpenRouterProtocolException("provider_error")
        val data = root.optJSONArray("data")
            ?: throw OpenRouterProtocolException("missing_model_data")
        return buildList {
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                parseModel(model)?.let(::add)
            }
        }.distinctBy(OpenRouterModel::id)
    }

    private suspend fun fetchModels(): List<OpenRouterModel> {
        val apiKey = apiKeyProvider.apiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) throw MissingApiKeyException()
        val startedAtMs = clock.millis()
        // The documented discovery filter accepts a concrete modality such as
        // `speech`; `all` is not a documented sentinel. Merge the normal model
        // catalog with the dedicated speech catalog so main/media models and
        // TTS-only models are all available in their respective pickers.
        val requests =
            listOf(null, "speech", "image", "transcription").map { outputModality ->
                Request.Builder()
                    .url(
                        baseUrl.newBuilder()
                            .addPathSegment("models")
                            .apply {
                                outputModality?.let {
                                    addQueryParameter("output_modalities", it)
                                }
                            }
                            .addQueryParameter("sort", "newest")
                            .build(),
                    )
                    .headers(authHeaders(apiKey, attribution))
                    .get()
                    .build()
            }
        return executeWithRetry(
            policy = retryPolicy,
            onFailure = { failure, attempt ->
                observer.emit(
                    failure.toNetworkFailure(
                        operation = OPERATION,
                        attempt = attempt,
                        reasonCode =
                            (failure as? OpenRouterHttpException)?.reasonCode
                                ?: "model_catalog_failure",
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
                        reasonCode = "model_catalog_retry",
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                    ),
                )
            },
        ) { attempt ->
                val models =
                    requests
                        .flatMap { request ->
                            httpClient.newCall(request).awaitParsed { response ->
                                response.requireOpenRouterSuccess(clock.millis())
                                parseModels(response.body?.string().orEmpty())
                            }
                        }
                        .distinctBy(OpenRouterModel::id)
                observer.emit(
                    AiNetworkEvent.Succeeded(
                        operation = OPERATION,
                        statusCode = 200,
                        attempt = attempt,
                        elapsedMs = (clock.millis() - startedAtMs).coerceAtLeast(0L),
                    ),
                )
                models
        }
    }

    private fun parseModel(model: JSONObject): OpenRouterModel? {
        val id = model.optString("id").trim()
        if (id.isEmpty()) return null
        val architecture = model.optJSONObject("architecture")
        val supportedParameters = model
            .optJSONArray("supported_parameters")
            .asStringSet()
        val input = architecture
            ?.optJSONArray("input_modalities")
            .asStringSet()
            .toMutableSet()
        val output = architecture
            ?.optJSONArray("output_modalities")
            .asStringSet()
            .toMutableSet()
        parseModalityArrow(architecture?.optString("modality"), input, output)
        if (input.isEmpty()) input += "text"
        if (output.isEmpty()) output += "text"

        val capabilities = buildSet {
            if ("text" in input) add(ModelCapability.TEXT_INPUT)
            if ("image" in input) add(ModelCapability.IMAGE_INPUT)
            if ("audio" in input) add(ModelCapability.AUDIO_INPUT)
            if ("video" in input) add(ModelCapability.VIDEO_INPUT)
            if ("text" in output) add(ModelCapability.TEXT_OUTPUT)
            if ("image" in output) add(ModelCapability.IMAGE_OUTPUT)
            // OpenRouter calls dedicated TTS output `speech`; realtime models
            // may still advertise `audio`.
            if ("audio" in output || "speech" in output) add(ModelCapability.AUDIO_OUTPUT)
            if ("transcription" in output) add(ModelCapability.TRANSCRIPTION_OUTPUT)
            if (
                "tools" in supportedParameters ||
                "tool_choice" in supportedParameters
            ) {
                add(ModelCapability.TOOLS)
            }
            if (
                "reasoning" in supportedParameters ||
                "include_reasoning" in supportedParameters
            ) {
                add(ModelCapability.REASONING)
            }
            if (
                "response_format" in supportedParameters ||
                "structured_outputs" in supportedParameters
            ) {
                add(ModelCapability.STRUCTURED_OUTPUT)
            }
        }
        val pricing = model.optJSONObject("pricing")
        val promptPrice = pricing?.optionalDouble("prompt")
        val completionPrice = pricing?.optionalDouble("completion")
        val description = model.optString("description").trim().takeIf(String::isNotEmpty)
        val topProvider = model.optJSONObject("top_provider")
        val reasoning = model.optJSONObject("reasoning")
        return OpenRouterModel(
            id = id,
            name = model.optString("name").trim().ifEmpty { id },
            description = description,
            createdAtEpochSeconds = model.optionalPositiveLong("created"),
            contextLength = model.optionalPositiveInt("context_length")
                ?: topProvider?.optionalPositiveInt("context_length"),
            capabilities = capabilities,
            supportedParameters = supportedParameters,
            reasoning = reasoning?.let(::parseReasoning),
            supportedVoices = model.optJSONArray("supported_voices").asStringList(),
            pricing = ModelPricing(
                promptPerToken = promptPrice,
                completionPerToken = completionPrice,
                imagePerUnit = pricing?.optionalDouble("image"),
                imageOutputPerToken = pricing?.optionalDouble("image_output"),
                audioPerUnit = pricing?.optionalDouble("audio"),
                request = pricing?.optionalDouble("request"),
                transcriptionPerHour =
                    transcriptionPerHour(
                        capabilities = capabilities,
                        promptPrice = promptPrice,
                        completionPrice = completionPrice,
                        description = description,
                    ),
            ),
        )
    }

    /**
     * OpenRouter currently returns duration prices in `pricing.prompt`, but does not include the
     * duration unit in the Models API object. Its catalogue uses three bands: per-second rates at
     * or below $0.0001, per-minute rates between those and $0.02, and per-hour rates from $0.02.
     * Token-priced transcription models declare a non-zero completion price or explicitly say so
     * in their catalogue description and must retain the normal per-million-token display.
     *
     * Centralizing this interpretation here keeps the raw provider value out of the UI and turns
     * every duration model into the one comparable unit the operator asked for.
     */
    private fun transcriptionPerHour(
        capabilities: Set<ModelCapability>,
        promptPrice: Double?,
        completionPrice: Double?,
        description: String?,
    ): Double? {
        if (ModelCapability.TRANSCRIPTION_OUTPUT !in capabilities) return null
        val rate = promptPrice?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val tokenPriced =
            completionPrice?.let { it.isFinite() && it > 0.0 } == true ||
                description?.contains("priced per token", ignoreCase = true) == true
        if (tokenPriced) return null
        return when {
            rate <= TRANSCRIPTION_SECOND_RATE_MAX -> rate * 60.0 * 60.0
            rate >= TRANSCRIPTION_HOUR_RATE_MIN -> rate
            else -> rate * 60.0
        }
    }

    private fun parseModalityArrow(
        modality: String?,
        input: MutableSet<String>,
        output: MutableSet<String>,
    ) {
        val raw = modality?.lowercase()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val sides = raw.split("->", limit = 2)
        addModalityTokens(sides[0], input)
        if (sides.size == 2) addModalityTokens(sides[1], output)
    }

    private fun parseReasoning(value: JSONObject): ModelReasoning {
        val supportedEfforts =
            value.optJSONArray("supported_efforts")
                ?.asStringList()
                ?.map(String::lowercase)
        return ModelReasoning(
            supportedEfforts = supportedEfforts,
            defaultEffort = value.optionalString("default_effort")?.lowercase(),
            defaultEnabled = value.optionalBoolean("default_enabled"),
            mandatory = value.optBoolean("mandatory", false),
            supportsMaxTokens = value.optBoolean("supports_max_tokens", false),
        )
    }

    private fun addModalityTokens(raw: String, target: MutableSet<String>) {
        raw.split('+', ',', '/', ' ')
            .map(String::trim)
            .filter { it in KNOWN_MODALITIES }
            .forEach(target::add)
    }

    private fun CachedModelCatalog.asResult(stale: Boolean) = ModelCatalogResult(
        models = models,
        fetchedAtEpochMs = fetchedAtEpochMs,
        stale = stale,
    )

    private companion object {
        const val TRANSCRIPTION_SECOND_RATE_MAX = 0.0001
        const val TRANSCRIPTION_HOUR_RATE_MIN = 0.02
        const val OPERATION = "model_catalog"
        val KNOWN_MODALITIES =
            setOf("text", "image", "audio", "speech", "video", "transcription")
    }
}

private fun JSONArray?.asStringSet(): Set<String> {
    this ?: return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            optString(index).lowercase().trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }
}

private fun JSONArray?.asStringList(): List<String> {
    this ?: return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }.distinct()
}

private fun JSONObject.optionalPositiveInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name).takeIf { it > 0 } else null

private fun JSONObject.optionalPositiveLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name).takeIf { it > 0L } else null

private fun JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).trim().takeIf(String::isNotEmpty) else null

private fun JSONObject.optionalBoolean(name: String): Boolean? =
    if (has(name) && !isNull(name)) optBoolean(name) else null

private fun JSONObject.optionalDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = when (val raw = opt(name)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
    return value?.takeIf(Double::isFinite)
}
