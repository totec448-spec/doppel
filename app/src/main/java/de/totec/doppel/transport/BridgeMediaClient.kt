package de.totec.doppel.transport

import de.totec.doppel.ai.awaitParsed
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject

/**
 * A downloaded media item, held in memory and nowhere else.
 *
 * Deliberately not a data class: the payload is a [ByteArray], and generated equals/hashCode
 * would compare references while looking like they compare content.
 */
class DownloadedMedia(
    val bytes: ByteArray,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class UploadedMedia(
    val uploadId: String,
    val sha256: String?,
    val sizeBytes: Long,
)

/**
 * Streams media over authenticated HTTPS. Raw media never goes through the
 * WebSocket and never has to exist as a second in-memory Base64 copy.
 */
class BridgeMediaClient(
    private val httpClient: OkHttpClient,
    baseUrl: String,
    private val token: String,
) {
    private val base = baseUrl.toHttpUrl()

    init {
        require(
            base.isHttps ||
                (base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost")),
        ) { "Bridge media endpoint must use HTTPS, except for Android loopback" }
        require(BridgeControlToken.isValid(token)) { "Bridge token must encode at least 256 bits" }
    }

    /**
     * Streams a media item into memory, hashed and bounded.
     *
     * There is deliberately no file variant any more. Incoming media is read exactly once — it is
     * analysed and then never opened again — so writing it to the app's cache first only put a
     * decrypted photo or voice note on disk for the length of a turn, and paid for a second copy
     * of the bytes for the privilege. It also left the file behind whenever the process died
     * between download and cleanup.
     *
     * [maxBytes] is what makes holding it safe: the transfer is aborted the moment it would cross
     * the caller's ceiling, so the array cannot grow past it. The integrity check still runs
     * against the whole stream before a single byte is handed to a caller.
     */
    suspend fun download(
        mediaId: String,
        expectedSha256: String?,
        maxBytes: Long,
    ): DownloadedMedia =
        run {
            require(mediaId.matches(SAFE_ID)) { "Invalid media id" }
            require(maxBytes in 1..MAX_IN_MEMORY_BYTES) {
                "maxBytes must be positive and within the in-memory limit"
            }
            val url =
                base.newBuilder()
                    .addPathSegment("media")
                    .addPathSegment(mediaId)
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

            httpClient.newCall(request).awaitParsed { response ->
                requireSuccess(response)
                val body = response.body ?: throw IOException("Bridge returned no media body")
                val declared = body.contentLength()
                if (declared > maxBytes) throw IOException("Media exceeds configured limit")
                val digest = MessageDigest.getInstance("SHA-256")
                // Sized from Content-Length when the bridge sends one. Without it a video would
                // grow the buffer by repeated doubling, copying everything read so far each time.
                val collected =
                    ByteArrayOutputStream(
                        if (declared in 1..maxBytes) declared.toInt() else STREAM_BUFFER_BYTES,
                    )
                var total = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("Media exceeds configured limit")
                        digest.update(buffer, 0, read)
                        collected.write(buffer, 0, read)
                    }
                }
                val sha = digest.digest().toHex()
                if (expectedSha256 != null && !sha.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("Media integrity check failed")
                }
                DownloadedMedia(
                    bytes = collected.toByteArray(),
                    mimeType = body.contentType()?.toString() ?: "application/octet-stream",
                    sha256 = sha,
                    sizeBytes = total,
                )
            }
        }

    suspend fun upload(
        file: File,
        mimeType: String,
        maxBytes: Long,
    ): UploadedMedia =
        run {
            require(file.isFile) { "Media file is missing" }
            require(file.length() in 1..maxBytes) { "Media file exceeds configured limit" }
            val url = base.newBuilder().addPathSegment("media").build()
            val requestBody =
                object : RequestBody() {
                    override fun contentType() = mimeType.toMediaTypeOrNull()

                    override fun contentLength(): Long = file.length()

                    override fun writeTo(sink: BufferedSink) {
                        file.source().use { source -> sink.writeAll(source) }
                    }
                }
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("X-Media-Filename", sanitizeFileName(file.name))
                    .post(requestBody)
                    .build()
            httpClient.newCall(request).awaitParsed { response ->
                requireSuccess(response)
                val body = response.body?.string() ?: throw IOException("Bridge returned no upload result")
                val json = JSONObject(body)
                // Protocol v1 uses top-level fields. Reading the old nested
                // shape as a compatibility fallback makes rolling bridge
                // upgrades safe without keeping two upload implementations.
                val media = json.optJSONObject("media")
                val uploadId =
                    json.optString("uploadId")
                        .ifBlank { media?.optString("id").orEmpty() }
                        .takeIf(String::isNotBlank)
                        ?: throw IOException("Bridge returned no upload id")
                UploadedMedia(
                    uploadId = uploadId,
                    sha256 =
                        json.optString("sha256")
                            .ifBlank { media?.optString("sha256").orEmpty() }
                            .takeIf(String::isNotBlank),
                    sizeBytes =
                        when {
                            json.has("sizeBytes") -> json.optLong("sizeBytes", file.length())
                            media?.has("size") == true -> media.optLong("size", file.length())
                            else -> file.length()
                        },
                )
            }
        }

    private fun requireSuccess(response: Response) {
        if (!response.isSuccessful) {
            val code =
                when (response.code) {
                    401, 403 -> "Bridge-Token abgelehnt"
                    404 -> "The media is no longer available"
                    413 -> "The media is too large"
                    429 -> "Bridge-Medienlimit erreicht"
                    else -> "Bridge-Medienfehler ${response.code}"
                }
            throw IOException(code)
        }
    }

    private fun sanitizeFileName(value: String): String =
        value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "media.bin" }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{8,128}$")
        private const val STREAM_BUFFER_BYTES = 16 * 1024

        /**
         * Hard ceiling on what a caller may ask to be held in RAM at once. Above the largest
         * limit the analyser configures, and low enough that a hostile Content-Length cannot
         * talk this phone into an allocation it will be killed for.
         */
        const val MAX_IN_MEMORY_BYTES = 32L * 1024 * 1024
    }
}
