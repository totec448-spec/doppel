package de.totec.doppel.ai

import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * What the images route accepts for one model.
 *
 * Every value is the route's own declaration, so an absent parameter means "this model does not
 * take it", never "we could not tell" — that case is a null [ImageModelParameters] instead.
 */
class ImageModelParameters internal constructor(
    private val enums: Map<String, Set<String>>,
    private val ranges: Map<String, IntRange>,
    private val flags: Set<String>,
) {
    /**
     * The first of [preferences] this model allows, or null when it allows none of them — including
     * when it does not take the parameter at all, which is the answer that keeps it out of the
     * request.
     */
    fun firstAllowed(parameter: String, preferences: List<String>): String? {
        val allowed = enums[parameter] ?: return null
        return preferences.firstOrNull { it in allowed }
    }

    /** [value] moved inside the declared range, or null when the model does not take [parameter]. */
    fun clamp(parameter: String, value: Int): Int? =
        ranges[parameter]?.let { value.coerceIn(it.first, it.last) }

    /** The most this model takes of a counted parameter, or null when it does not take it. */
    fun maxOf(parameter: String): Int? = ranges[parameter]?.last

    internal companion object {
        /**
         * Reads one entry of the route's `supported_parameters` object, which is keyed by parameter
         * name and describes each as an enum, a numeric range or a plain flag.
         */
        fun parse(supported: JSONObject?): ImageModelParameters {
            val enums = mutableMapOf<String, Set<String>>()
            val ranges = mutableMapOf<String, IntRange>()
            val flags = mutableSetOf<String>()
            supported?.keys()?.forEach { key ->
                val entry = supported.optJSONObject(key) ?: return@forEach
                when (entry.optString("type")) {
                    "enum" -> {
                        val values = entry.optJSONArray("values") ?: return@forEach
                        val parsed = buildSet {
                            for (index in 0 until values.length()) {
                                values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                            }
                        }
                        if (parsed.isNotEmpty()) enums[key] = parsed
                    }

                    "range" -> {
                        val min = entry.optInt("min", 0)
                        val max = entry.optInt("max", min)
                        if (max >= min) ranges[key] = min..max
                    }

                    else -> flags += key
                }
            }
            return ImageModelParameters(enums, ranges, flags)
        }
    }
}

fun interface ImageModelParameterSource {
    /**
     * The contract for [model], or null when it is currently unknown — the catalog could not be
     * read, or the route does not list this model. Null is not "accepts nothing": a caller that
     * cannot tell must fall back to the plainest request it can make, not to no request at all.
     */
    suspend fun parametersFor(model: String): ImageModelParameters?

    companion object {
        /** For call sites that deliberately send whatever they were given. */
        val UNKNOWN = ImageModelParameterSource { null }
    }
}

/**
 * Reads `GET /api/v1/images/models`, the images route's own model list.
 *
 * `GET /api/v1/models` answers a different question. There an image model advertises the parameters
 * it takes on chat/completions — `temperature`, `max_tokens`, `stop` — and says nothing about the
 * dedicated images route, whose list is far narrower: of the 42 models it serves, all 42 take
 * `input_references`, but only 8 take `background`, 7 `quality`, and 6 each `output_format` and
 * `output_compression`.
 *
 * That distinction is not cosmetic. A parameter the chosen model does not declare is not ignored:
 * the request passes schema validation, then OpenRouter looks for an endpoint satisfying every
 * parameter in it, finds none, and answers **404** before the request reaches the provider. Nothing
 * is billed and nothing appears in the OpenRouter activity log, so from the outside the generation
 * simply never happened.
 */
class OpenRouterImageModelCatalog(
    private val httpClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val apiKeyProvider: OpenRouterApiKeyProvider,
    private val attribution: OpenRouterAttribution = OpenRouterAttribution(),
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtlMs: Long = 6 * 60 * 60 * 1_000L,
    private val failureBackoffMs: Long = 5 * 60 * 1_000L,
) : ImageModelParameterSource {
    private val mutex = Mutex()

    @Volatile
    private var cached: Map<String, ImageModelParameters>? = null

    @Volatile
    private var cachedAtMs = 0L

    @Volatile
    private var failedAtMs = 0L

    override suspend fun parametersFor(model: String): ImageModelParameters? =
        catalog()?.get(model.trim())

    private suspend fun catalog(): Map<String, ImageModelParameters>? =
        mutex.withLock {
            val now = clock.millis()
            cached?.takeIf { now - cachedAtMs in 0..cacheTtlMs }?.let { return@withLock it }
            // A catalog that cannot be read must not turn every generation into a second failing
            // request. Until the backoff expires the caller keeps the answer it already has, or
            // "unknown" when there is none.
            if (failedAtMs != 0L && now - failedAtMs in 0..failureBackoffMs) {
                return@withLock cached
            }
            try {
                val fetched = fetch()
                cached = fetched
                cachedAtMs = clock.millis()
                failedAtMs = 0L
                fetched
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedAtMs = clock.millis()
                cached
            }
        }

    private suspend fun fetch(): Map<String, ImageModelParameters> {
        val request =
            Request.Builder()
                .url(
                    baseUrl.newBuilder()
                        .addPathSegment("images")
                        .addPathSegment("models")
                        .build(),
                )
                .headers(authHeaders(apiKeyProvider.apiKey()?.trim().orEmpty(), attribution))
                .get()
                .build()
        return httpClient.newCall(request).awaitParsed { response ->
            response.requireOpenRouterSuccess(clock.millis())
            parseImageModelCatalog(response.body?.string().orEmpty())
        }
    }
}

internal fun parseImageModelCatalog(raw: String): Map<String, ImageModelParameters> {
    val root = runCatching { JSONObject(raw) }
        .getOrElse { throw OpenRouterProtocolException("invalid_image_model_catalog") }
    val data = root.optJSONArray("data")
        ?: throw OpenRouterProtocolException("missing_image_model_data")
    return buildMap {
        for (index in 0 until data.length()) {
            val model = data.optJSONObject(index) ?: continue
            val id = model.optString("id").trim().takeIf(String::isNotEmpty) ?: continue
            put(id, ImageModelParameters.parse(model.optJSONObject("supported_parameters")))
        }
    }
}
