package de.totec.doppel.integration

import de.totec.doppel.ai.awaitParsed
import kotlin.math.min
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject

data class TranscriptionFallbackConfiguration(
    val endpointUrl: String,
    val model: String,
    val apiKey: String,
)

fun interface TranscriptionFallbackConfigurationProvider {
    fun current(): TranscriptionFallbackConfiguration?
}

fun interface TranscriptionFallbackClient {
    suspend fun transcribe(
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ): String
}

/**
 * One-shot OpenAI-compatible `/audio/transcriptions` fallback.
 *
 * This is the *direct* route, for a dedicated transcription model such as `gpt-transcribe` that
 * only exists behind its own endpoint and its own key. Which endpoint and credential it gets is
 * decided by the caller, not here: a bare model name means the operator-configured transcription
 * URL with the separate transcription key, while a slash-qualified id is sent to OpenRouter's own
 * audio/transcriptions path with the OpenRouter key. Either way the audio leaves the phone from
 * this client, so both routes are disclosed in `PRIVACY.md` and `docs/SECURITY.md`. Ordinary
 * multimodal audio is not this path; [IncomingMediaAnalyzer] sends that through the media gateway.
 *
 * It is deliberately not a second resident media pipeline: configuration is
 * resolved only after the normal multimodal request failed, the request is
 * cancellable, input and response are bounded, and neither credential nor
 * provider response is logged or persisted here.
 */
class OkHttpTranscriptionFallbackClient(
    private val httpClient: OkHttpClient,
    private val configurationProvider: TranscriptionFallbackConfigurationProvider,
) : TranscriptionFallbackClient {
    override suspend fun transcribe(
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ): String {
        require(bytes.isNotEmpty()) { "STT input is empty" }
        require(bytes.size <= MAX_INPUT_BYTES) { "STT input is too large" }
        val configuration =
            configurationProvider.current()
                ?: error("Transcription fallback is not configured")
        val endpoint =
            configuration.endpointUrl.toHttpUrlOrNull()
                ?.takeIf { it.isHttps }
                ?: error("Transcription endpoint must use HTTPS")
        val model = configuration.model.trim()
        require(model.length in 1..MAX_MODEL_CHARS) { "Invalid STT model" }
        val apiKey = configuration.apiKey.trim()
        require(
            apiKey.length in 1..MAX_API_KEY_CHARS &&
                '\r' !in apiKey &&
                '\n' !in apiKey
        ) {
            "Invalid STT credential"
        }
        val normalizedMime =
            mimeType.substringBefore(';')
                .trim()
                .lowercase()
                .takeIf { it.startsWith("audio/") && it.none(Char::isWhitespace) }
                ?: "application/octet-stream"
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart(
                    "file",
                    safeFileName(fileName, normalizedMime),
                    bytes.toRequestBody(normalizedMime.toMediaTypeOrNull()),
                )
                .build()
        val request =
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
        return httpClient.newCall(request).awaitParsed { response ->
            if (!response.isSuccessful) {
                throw TranscriptionFallbackException("stt_http_${response.code}")
            }
            val raw =
                response.body?.readBoundedUtf8()
                    ?: throw TranscriptionFallbackException("stt_empty_body")
            val text =
                runCatching { JSONObject(raw).optString("text") }
                    .getOrElse { throw TranscriptionFallbackException("stt_invalid_json") }
                    .trim()
                    .take(MAX_TRANSCRIPT_CHARS)
            text.ifBlank { throw TranscriptionFallbackException("stt_empty_transcript") }
        }
    }

    private fun safeFileName(
        supplied: String?,
        mimeType: String,
    ): String {
        val extension =
            when {
                mimeType.contains("ogg") -> "ogg"
                mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
                mimeType.contains("mp4") || mimeType.contains("m4a") -> "m4a"
                mimeType.contains("wav") -> "wav"
                mimeType.contains("webm") -> "webm"
                else -> "audio"
            }
        val stem =
            supplied
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.substringBeforeLast('.')
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.trim('.', '_', '-')
                ?.take(MAX_FILE_STEM_CHARS)
                ?.takeIf(String::isNotBlank)
                ?: "voice"
        return "$stem.$extension"
    }

    private fun ResponseBody.readBoundedUtf8(): String {
        val declared = contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw TranscriptionFallbackException("stt_response_too_large")
        }
        val source = source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val remainingProbe = MAX_RESPONSE_BYTES + 1L - total
            if (remainingProbe <= 0L) {
                throw TranscriptionFallbackException("stt_response_too_large")
            }
            val read = source.read(buffer, min(8_192L, remainingProbe))
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw TranscriptionFallbackException("stt_response_too_large")
            }
        }
        return buffer.readUtf8()
    }

    private companion object {
        const val MAX_INPUT_BYTES = 20 * 1_024 * 1_024
        const val MAX_RESPONSE_BYTES = 1L * 1_024 * 1_024
        const val MAX_TRANSCRIPT_CHARS = 12_000
        const val MAX_MODEL_CHARS = 256
        const val MAX_API_KEY_CHARS = 8_192
        const val MAX_FILE_STEM_CHARS = 80
    }
}

class TranscriptionFallbackException(
    reasonCode: String,
) : Exception(reasonCode)
