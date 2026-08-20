package de.totec.doppel.integration

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import de.totec.doppel.ai.ChatCompletionGateway
import de.totec.doppel.ai.ChatCompletionRequest
import de.totec.doppel.ai.MediaModelCapabilitySource
import de.totec.doppel.ai.MediaRequestBuilders
import de.totec.doppel.ai.MediaAnalysisPromptSettings
import de.totec.doppel.ai.ModelCapability
import de.totec.doppel.ai.ResolvedTurnSettings
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.MediaAnalysisRecord
import de.totec.doppel.domain.MediaHistoryLabels
import de.totec.doppel.domain.MediaKind
import de.totec.doppel.domain.MediaReference
import de.totec.doppel.transport.BridgeMediaClient
import de.totec.doppel.settings.DefaultMediaAnalysisPrompts
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What one media object contributed to the turn.
 *
 * [analyzed] is the honest answer to "did a model actually look at this?". Only
 * a successful analysis (fresh or from the cache) counts. A clip that was
 * downloaded but never analysed — the format is unsupported, the feature is
 * switched off, the request failed — carries a bare marker instead, and the
 * caller must not record it as processed content.
 */
data class MediaAnalysisResult(
    val text: String,
    val analyzed: Boolean,
    val reason: String,
)

/**
 * Streams one bridge media object into memory, analyzes it once and keeps only the bounded
 * textual result in SQLite. The received photo or voice note never touches storage: it lives
 * as a local byte array for the length of the analysis and is unreachable afterwards. No raw
 * media survives a completed turn and no background transcoder remains resident while idle.
 */
class IncomingMediaAnalyzer(
    private val bridgeMedia: BridgeMediaClient,
    private val repository: BotRepository,
    private val gateway: ChatCompletionGateway,
    private val transcriptionFallback: TranscriptionFallbackClient? = null,
    /**
     * The transcription model the operator picked, or blank for none.
     *
     * A slug with a slash is an OpenRouter model and runs through [gateway]; anything else is a
     * dedicated endpoint and runs through [transcriptionFallback].
     */
    private val transcriptionModel: () -> String = { "" },
    /**
     * What the configured media model says it can read. Unknown by default, which keeps the old
     * behaviour: try the request, and only learn from the failure.
     */
    private val mediaModelCapabilities: MediaModelCapabilitySource =
        MediaModelCapabilitySource.UNKNOWN,
    private val activityChanged: () -> Unit = {},
) {
    private val builders = MediaRequestBuilders(MAX_ANALYSIS_BYTES.toInt())

    suspend fun analyzeMedia(
        media: MediaReference,
        settings: ResolvedTurnSettings,
        imageEnabled: Boolean,
        audioEnabled: Boolean,
        videoEnabled: Boolean,
        maxVideoBytes: Long,
    ): MediaAnalysisResult {
        val enabled =
            when (media.kind) {
                MediaKind.IMAGE -> imageEnabled
                MediaKind.AUDIO -> audioEnabled
                MediaKind.VIDEO -> videoEnabled
                else -> false
            }
        // Video used to be pinned to one hard-coded Gemini build. That made the media-model setting
        // a dead knob for the one modality the owner kept re-configuring, so three rounds of
        // "changed the model, still broken" changed nothing at all. One media model, every kind.
        val analysisModel = settings.model(de.totec.doppel.ai.ModelRole.MEDIA)
        val instruction = MediaAnalysisInstructions.forKind(media.kind, settings.mediaAnalysisPrompts)
        // A prompt edit must never serve an older cached description. Keep the model visible in
        // logs while the cache identity also includes the exact effective instruction contract.
        val analyzerVersion =
            "$analysisModel:${media.kind.name.lowercase()}:${instructionFingerprint(instruction)}"
        val startedAtMs = System.currentTimeMillis()
        // "Not analyzable" and "switched off" produce the same bare marker downstream, so they must
        // not produce the same log line: one is a settings decision, the other is a hard format
        // limit the operator cannot configure away.
        if (media.kind !in ANALYZABLE_KINDS) {
            recordActivity(
                media = media,
                action = "unsupported_kind",
                // Nothing is broken — WhatsApp simply delivered something no model
                // reads. Worth saying, not worth alarming about.
                level = ActivityLevel.INFO,
                summary =
                    "${mediaLabel(media.kind)} received · this format is not analysed · " +
                        "supported are image, voice message and video",
                model = analysisModel,
                elapsedMs = 0L,
            )
            // A format no model can read will never become analysable; it counts
            // as handled so it is not retried forever.
            return MediaAnalysisResult(marker(media.kind), analyzed = true, reason = "unsupported_kind")
        }
        if (!enabled) {
            recordActivity(
                media = media,
                action = "skipped",
                // The operator turned this off on purpose; obeying a setting is not
                // a warning.
                level = ActivityLevel.INFO,
                summary = "${mediaLabel(media.kind)} received · analysis is switched off",
                model = analysisModel,
                elapsedMs = 0L,
            )
            return MediaAnalysisResult(marker(media.kind), analyzed = false, reason = "analysis_disabled")
        }
        media.sha256?.let { hash ->
            repository.getMediaAnalysis(hash, ANALYZER, analyzerVersion)?.let {
                recordActivity(
                    media = media,
                    action = "cache_hit",
                    // The same clip a second time costs nothing and changes nothing.
                    level = ActivityLevel.DEBUG,
                    summary = "${mediaLabel(media.kind)} loaded from the analysis cache · $analyzerVersion",
                    model = analysisModel,
                    elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                )
                val cached = JSONObject(it.resultJson).optString("text").trim()
                if (cached.isNotEmpty() && cached != marker(media.kind)) {
                    return MediaAnalysisResult(cached, analyzed = true, reason = "cache_hit")
                }
            }
        }

        // What the model declares it reads, or null when the catalogue cannot say. Asked after the
        // cache and before the download: a clip no model in this configuration can open is not
        // worth fetching, and a repeat of one already described costs nothing either way.
        val declared = mediaModelCapabilities.capabilitiesOf(analysisModel)
        val readsThisKind =
            declared == null || requiredInput(media.kind)?.let { it in declared } ?: true
        // Audio is the one kind with somewhere else to go. A picture or a video has no second
        // reader, so a media model that cannot see them is a dead end, and saying so beats paying
        // for a call whose failure is already known.
        if (!readsThisKind && media.kind != MediaKind.AUDIO) {
            recordActivity(
                media = media,
                action = "model_cannot_read",
                level = ActivityLevel.WARN,
                summary =
                    "${mediaLabel(media.kind)} received · $analysisModel does not take this kind " +
                        "of input · choose a media model that does",
                model = analysisModel,
                elapsedMs = 0L,
            )
            return MediaAnalysisResult(
                marker(media.kind),
                analyzed = false,
                reason = "media_model_lacks_${media.kind.name.lowercase()}_input",
            )
        }

        recordActivity(
            media = media,
            action = "started",
            level = ActivityLevel.INFO,
            summary = "${mediaLabel(media.kind)} received · analysis started with $analyzerVersion",
            model = analysisModel,
            elapsedMs = null,
        )

        val maximum =
            when (media.kind) {
                MediaKind.VIDEO -> maxVideoBytes.coerceIn(1, MAX_ANALYSIS_BYTES)
                else -> MAX_ANALYSIS_BYTES
            }
        return try {
            val downloaded =
                bridgeMedia.download(
                    mediaId = media.id,
                    expectedSha256 = media.sha256,
                    maxBytes = maximum,
                )
            val bytes = downloaded.bytes
            val downloadedHash = media.sha256 ?: downloaded.sha256
            repository.getMediaAnalysis(downloadedHash, ANALYZER, analyzerVersion)?.let { hit ->
                val cached = JSONObject(hit.resultJson).optString("text").trim()
                if (cached.isNotEmpty() && cached != marker(media.kind)) {
                    recordActivity(
                        media = media,
                        action = "cache_hit_after_download",
                        level = ActivityLevel.DEBUG,
                        summary =
                            "${mediaLabel(media.kind)} loaded from the analysis cache after download · " +
                                analyzerVersion,
                        model = analysisModel,
                        elapsedMs =
                            (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                    )
                    return MediaAnalysisResult(cached, analyzed = true, reason = "cache_hit")
                }
            }
            val text =
                when (media.kind) {
                    MediaKind.IMAGE ->
                        gateway.complete(
                            builders.image(
                                settings,
                                bytes,
                                analysisMime(media, "image/", "image/jpeg", analysisModel),
                                instruction,
                            ),
                        ).content

                    MediaKind.AUDIO ->
                        analyzeAudio(settings, media, bytes, instruction, readsThisKind)

                    MediaKind.VIDEO ->
                        gateway.complete(
                            builders.video(
                                settings,
                                bytes,
                                analysisMime(media, "video/", "video/mp4", analysisModel),
                                instruction,
                            ),
                        ).content

                    else ->
                        return MediaAnalysisResult(
                            marker(media.kind),
                            analyzed = false,
                            reason = "unsupported_kind",
                        )
                }.let { raw -> MediaReportMarkup.normalize(media.kind, raw) }
                    .take(MAX_RESULT_CHARACTERS)
                    .ifBlank {
                        throw de.totec.doppel.ai.OpenRouterProtocolException(
                            "empty_media_analysis",
                        )
                    }
            repository.putMediaAnalysis(
                MediaAnalysisRecord(
                    contentHash = downloadedHash,
                    analyzer = ANALYZER,
                    analyzerVersion = analyzerVersion,
                    mediaType = media.kind.name.lowercase(),
                    resultJson = JSONObject().put("text", text).toString(),
                    byteSize = downloaded.sizeBytes,
                    expiresAt = System.currentTimeMillis() + CACHE_TTL_MS,
                ),
            )
            recordActivity(
                media = media,
                action = "succeeded",
                // The analysis was already announced when it started; a second row
                // saying it worked is the noise the feed is being cleaned of. Only
                // the failure below needs to interrupt the reader.
                level = ActivityLevel.DEBUG,
                summary =
                    "${mediaLabel(media.kind)} analysed · $analyzerVersion · " +
                        formatDuration(System.currentTimeMillis() - startedAtMs),
                model = analysisModel,
                elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
            )
            MediaAnalysisResult(text, analyzed = true, reason = "analyzed")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val cause = safeFailure(failure)
            recordActivity(
                media = media,
                action = "failed",
                level = ActivityLevel.ERROR,
                summary =
                    "${mediaLabel(media.kind)} analysis failed · " +
                        "$cause · ${formatDuration(System.currentTimeMillis() - startedAtMs)}",
                model = analyzerVersion,
                elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
            )
            MediaAnalysisResult(
                "${marker(media.kind)} (analysis unavailable)",
                analyzed = false,
                // The bare code sent every diagnosis to the activity log, one screen away from the
                // trace that says the clip was not read. The cause travels with the verdict now.
                reason = "analysis_failed · $cause",
            )
        }
        // No `finally` clean-up left to get wrong: the clip only ever existed as a local byte
        // array, so it is gone when this frame is.
    }

    /**
     * Describes a picture the bot itself is about to send, straight from the local asset file.
     *
     * The chat history used to record an outgoing image as nothing but its file name, so the model
     * had no idea what it had just shown and answered the follow-up blind. Running it through the
     * same multimodal model an incoming picture gets closes that gap; the result is cached by
     * content hash, so each asset is described once no matter how often it is sent.
     *
     * Returns null when vision is off, the request fails, or the model says nothing useful — the
     * caller then keeps the plain file-name history row rather than blocking the send.
     */
    suspend fun describeOutgoingImage(
        file: File,
        mimeType: String,
        sha256: String?,
        settings: ResolvedTurnSettings,
        imageEnabled: Boolean,
    ): String? {
        if (!imageEnabled) return null
        val analyzerVersion = settings.model(de.totec.doppel.ai.ModelRole.MEDIA)
        sha256?.let { hash ->
            repository.getMediaAnalysis(hash, OUTGOING_IMAGE_ANALYZER, analyzerVersion)?.let { hit ->
                return JSONObject(hit.resultJson).optString("text").trim().takeIf(String::isNotEmpty)
            }
        }
        val startedAtMs = System.currentTimeMillis()
        return try {
            require(file.length() in 1..MAX_ANALYSIS_BYTES) { "Image is too large to analyse" }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            require(bytes.size <= MAX_ANALYSIS_BYTES) { "Image is too large to analyse" }
            val text =
                gateway.complete(
                    builders.image(
                        settings,
                        bytes,
                        normalizedMime(mimeType, "image/jpeg"),
                        OUTGOING_IMAGE_INSTRUCTION,
                    ),
                ).content
                    .let(MediaReportMarkup::strip)
                    .take(MAX_OUTGOING_DESCRIPTION_CHARACTERS)
                    .takeIf(String::isNotEmpty)
            if (text != null && sha256 != null) {
                repository.putMediaAnalysis(
                    MediaAnalysisRecord(
                        contentHash = sha256,
                        analyzer = OUTGOING_IMAGE_ANALYZER,
                        analyzerVersion = analyzerVersion,
                        mediaType = MediaKind.IMAGE.name.lowercase(),
                        resultJson = JSONObject().put("text", text).toString(),
                        byteSize = bytes.size.toLong(),
                        expiresAt = System.currentTimeMillis() + CACHE_TTL_MS,
                    ),
                )
            }
            recordOutgoingImageActivity(
                action = if (text == null) "outgoing_image_empty" else "outgoing_image_described",
                level = if (text == null) ActivityLevel.WARN else ActivityLevel.DEBUG,
                summary =
                    if (text == null) {
                        "Own image returned no description · $analyzerVersion"
                    } else {
                        "Own image described · $analyzerVersion · " +
                            formatDuration(System.currentTimeMillis() - startedAtMs)
                    },
                model = analyzerVersion,
                sha256 = sha256,
            )
            text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordOutgoingImageActivity(
                action = "outgoing_image_failed",
                level = ActivityLevel.WARN,
                summary = "Own image could not be described · ${safeFailure(failure)}",
                model = analyzerVersion,
                sha256 = sha256,
            )
            null
        }
    }

    private fun recordOutgoingImageActivity(
        action: String,
        level: ActivityLevel,
        summary: String,
        model: String,
        sha256: String?,
    ) {
        repository.appendActivity(
            ActivityLogRecord(
                occurredAt = System.currentTimeMillis(),
                level = level,
                category = "media_analysis",
                action = action.take(100),
                correlationId = sha256?.take(64),
                summary = summary.take(1_024),
                detailsJson =
                    JSONObject()
                        .put("kind", "outgoing_image")
                        .put("model", model.take(256))
                        .toString(),
            ),
        )
        activityChanged()
    }

    /**
     * A voice note, read by whoever can read it.
     *
     * [mediaModelReadsAudio] is false only when the catalogue explicitly says the configured media
     * model takes no audio — a picture-and-video model. There is no point calling it then: the
     * transcription model answers directly, and the turn saves a guaranteed failure plus its
     * retries. When the answer is unknown the media model is still tried first, exactly as before.
     */
    private suspend fun analyzeAudio(
        settings: ResolvedTurnSettings,
        media: MediaReference,
        originalBytes: ByteArray,
        instruction: String,
        mediaModelReadsAudio: Boolean,
    ): String {
        val prepared =
            if (
                media.mimeType.contains("mpeg", true) ||
                media.fileName?.endsWith(".mp3", true) == true
            ) {
                PreparedAudio(originalBytes, "mp3")
            } else if (
                media.mimeType.contains("wav", true) ||
                media.fileName?.endsWith(".wav", true) == true
            ) {
                PreparedAudio(originalBytes, "wav")
            } else {
                PreparedAudio(
                    AudioToWavDecoder.decode(originalBytes, MAX_ANALYSIS_BYTES.toInt()),
                    "wav",
                )
            }
        if (!mediaModelReadsAudio) {
            recordActivity(
                media = media,
                action = "transcription_route",
                level = ActivityLevel.INFO,
                summary =
                    "Voice message · the media model takes no audio · transcribing with " +
                        transcriptionModel().trim().ifBlank { "the configured fallback" },
                model = settings.model(de.totec.doppel.ai.ModelRole.MEDIA),
                elapsedMs = null,
            )
            return transcribeWithFallback(settings, media, originalBytes, prepared, instruction)
        }
        return try {
            gateway.complete(
                builders.audio(
                    settings,
                    prepared.bytes,
                    prepared.format,
                    instruction,
                ),
            ).content.ifBlank {
                throw de.totec.doppel.ai.OpenRouterProtocolException("empty_audio_analysis")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (primaryFailure: Exception) {
            try {
                transcribeWithFallback(settings, media, originalBytes, prepared, instruction)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The fallback's own reason would hide why the voice note was handed over in the
                // first place, and that is the one the operator can act on.
                throw primaryFailure
            }
        }
    }

    /**
     * The second reader for a voice note.
     *
     * An OpenRouter slug goes through the ordinary media gateway — the route serves no
     * `/audio/transcriptions` endpoint, so audio there is a chat completion like any other, and it
     * answers in the same `Tone:`/`Transcription:` shape. A bare model name is a dedicated
     * endpoint with its own key and returns nothing but words, so the tone line is supplied here.
     */
    private suspend fun transcribeWithFallback(
        settings: ResolvedTurnSettings,
        media: MediaReference,
        originalBytes: ByteArray,
        prepared: PreparedAudio,
        instruction: String,
    ): String {
        transcriptionModel().trim().ifBlank { error("No transcription model is configured") }
        val direct = transcriptionFallback ?: error("No transcription model is configured")
        return "Tone: unclear\nTranscription:\n" +
            direct.transcribe(
                bytes = originalBytes,
                mimeType = normalizedMime(media.mimeType, "application/octet-stream"),
                fileName = media.fileName,
            )
    }

    /** The input modality a kind needs, or null when nothing in particular is required. */
    private fun requiredInput(kind: MediaKind): ModelCapability? =
        when (kind) {
            MediaKind.IMAGE -> ModelCapability.IMAGE_INPUT
            MediaKind.AUDIO -> ModelCapability.AUDIO_INPUT
            MediaKind.VIDEO -> ModelCapability.VIDEO_INPUT
            else -> null
        }

    // Shared with the history labels: the turn builder strips exactly this marker
    // back off before the caller prepends the stable "User sent …" label.
    private fun marker(kind: MediaKind): String = MediaHistoryLabels.marker(kind)

    private fun normalizedMime(value: String, fallback: String): String =
        value.substringBefore(';').trim().lowercase().takeIf {
            it.isNotBlank() && it.none(Char::isWhitespace)
        } ?: fallback

    private fun instructionFingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Substituting a MIME type is a legitimate recovery — WhatsApp forwards empty or exotic types
     * and the provider still decodes the bytes — but silently doing so turned every later provider
     * rejection into an unexplainable failure. Log the substitution once, then proceed as before.
     */
    private fun analysisMime(
        media: MediaReference,
        expectedPrefix: String,
        fallback: String,
        model: String,
    ): String {
        val declared = normalizedMime(media.mimeType, "")
        if (declared.startsWith(expectedPrefix)) return declared
        recordActivity(
            media = media,
            action = "unsupported_format",
            // The analysis still runs, just with a guessed type. That is a note.
            level = ActivityLevel.INFO,
            summary =
                "${mediaLabel(media.kind)} without a matching media type · " +
                    (declared.takeIf { it.isNotBlank() }?.let { "reported $it" } ?: "no type reported") +
                    " · analysing it as $fallback",
            model = model,
            elapsedMs = null,
        )
        return fallback
    }

    private fun recordActivity(
        media: MediaReference,
        action: String,
        level: ActivityLevel,
        summary: String,
        model: String,
        elapsedMs: Long?,
    ) {
        repository.appendActivity(
            ActivityLogRecord(
                occurredAt = System.currentTimeMillis(),
                level = level,
                category = "media_analysis",
                action = "${media.kind.name.lowercase()}_$action".take(100),
                correlationId = media.sha256?.take(64),
                summary = summary.take(1_024),
                detailsJson =
                    JSONObject()
                        .put("kind", media.kind.name.lowercase())
                        .put("model", model.take(256))
                        .put("elapsedMs", elapsedMs ?: JSONObject.NULL)
                        .toString(),
            ),
        )
        activityChanged()
    }

    private fun safeFailure(failure: Throwable): String =
        when (failure) {
            is de.totec.doppel.ai.OpenRouterHttpException ->
                "OpenRouter HTTP ${failure.statusCode} (${failure.reasonCode.take(80)})"
            is de.totec.doppel.ai.OpenRouterProtocolException ->
                "OpenRouter protocol ${failure.reasonCode.take(80)}"
            is de.totec.doppel.ai.MissingApiKeyException ->
                "OpenRouter key missing"
            else -> failure.message
                ?.takeIf {
                    it.startsWith("Bridge-") ||
                        it.startsWith("Medium ") ||
                        it.startsWith("Media ")
                }
                ?.take(160)
                ?: failure.javaClass.simpleName.take(80)
        }

    private fun mediaLabel(kind: MediaKind): String =
        when (kind) {
            MediaKind.IMAGE -> "Image"
            MediaKind.AUDIO -> "Voice message"
            MediaKind.VIDEO -> "Video"
            MediaKind.DOCUMENT -> "Document"
            MediaKind.STICKER -> "Sticker"
            MediaKind.UNKNOWN -> "Media"
        }

    private fun formatDuration(value: Long): String =
        value.coerceAtLeast(0L).let {
            if (it < 1_000L) "$it ms" else "%.2f s".format(it / 1_000.0)
        }

    private data class PreparedAudio(
        val bytes: ByteArray,
        val format: String,
    )

    private companion object {
        val ANALYZABLE_KINDS = setOf(MediaKind.IMAGE, MediaKind.AUDIO, MediaKind.VIDEO)
        // Cache identity includes the instruction contract. The previous two-sentence visual
        // prompt must never be served after the richer image/video descriptions ship.
        const val ANALYZER = "openrouter_media_v4_structured_voice_video_flash"

        /**
         * Own namespace: the same bytes arriving from a contact and being sent by the bot need
         * different wording, so they must not share a cache entry.
         */
        const val OUTGOING_IMAGE_ANALYZER = "openrouter_outgoing_image"
        const val MAX_OUTGOING_DESCRIPTION_CHARACTERS = 600
        const val MAX_ANALYSIS_BYTES = 20L * 1024 * 1024
        const val MAX_RESULT_CHARACTERS = 12_000
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        /**
         * Written in the first person because the result is inserted into the chat log as something
         * the persona herself just sent, not as an observation about a stranger's picture.
         */
        const val OUTGOING_IMAGE_INSTRUCTION =
            "This is a picture you are sending yourself right now in a WhatsApp chat. Describe in one or two short sentences what can be seen on it, so that you know later what you sent. Plainly, no analysis report, no preamble."
    }
}

/**
 * One authoritative instruction per incoming media modality.
 *
 * Audio stays transcription-first because paraphrasing a voice note destroys the speaker's wording.
 * Images and videos need substantially more visual context: their result is the only representation
 * retained in history after the decrypted bytes leave memory, so a two-sentence caption permanently
 * hides details that later turns may depend on.
 */
internal object MediaAnalysisInstructions {
    /** Appended to every instruction: the result is chat context, never a document. */
    private const val PLAIN =
        " Answer in plain running text: no markdown, no bold or italics, no headings, no bullet " +
            "points and no labelled sections."

    val IMAGE: String = image(DefaultMediaAnalysisPrompts.IMAGE)
    val AUDIO: String = audio(DefaultMediaAnalysisPrompts.VOICE)
    val VIDEO: String = video(DefaultMediaAnalysisPrompts.VIDEO)

    fun forKind(kind: MediaKind, prompts: MediaAnalysisPromptSettings): String =
        when (kind) {
            MediaKind.IMAGE -> image(prompts.image)
            MediaKind.AUDIO -> audio(prompts.voice)
            MediaKind.VIDEO -> video(prompts.video)
            else -> ""
        }

    private fun image(custom: String): String =
        custom.trim().ifBlank { DefaultMediaAnalysisPrompts.IMAGE }.take(MAX_EDITABLE_CHARS) + PLAIN

    private fun video(custom: String): String =
        custom.trim().ifBlank { DefaultMediaAnalysisPrompts.VIDEO }.take(MAX_EDITABLE_CHARS) + PLAIN

    private fun audio(custom: String): String =
        custom.trim().ifBlank { DefaultMediaAnalysisPrompts.VOICE }.take(MAX_EDITABLE_CHARS) +
            " Return exactly two plain-text fields. First write `Tone:` followed by concise " +
            "audible speaker and delivery cues; say unclear rather than guessing age, gender or " +
            "emotion. Then write `Transcription:` on a new line followed by the word-for-word " +
            "transcription. No markdown, headings, bold, italics or bullet points; keep only the " +
            "two requested labels."

    private const val MAX_EDITABLE_CHARS = 16_000
}

/**
 * The report scaffolding a media model reaches for even when the instruction forbids it.
 *
 * Gemini answers a word-for-word transcription request with `**Tone:** cheerful, slightly rushed`
 * and headings above the actual words. That text is written into the chat history as what the other
 * person said, so every marker in it is a marker the writing model reads as part of the message —
 * and copies. The markers go; whatever they labelled stays, because the tone of a voice note is
 * worth knowing and only its packaging was the problem.
 */
internal object MediaReportMarkup {
    private val HEADING = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+""")
    private val EMPHASIS =
        Regex("""(\*{1,3}|_{2,3})(?=\S)(.+?)(?<=\S)\1""", RegexOption.DOT_MATCHES_ALL)
    private val BULLET = Regex("""(?m)^[ \t]{0,3}[*+•][ \t]+""")

    /**
     * Tone and transcription labels are semantic structure and deliberately survive. The colon is
     * optional because models emit both heading and field forms; redundant empty headings are
     * removed when a real labelled value follows later.
     */
    private val TONE_LINE = Regex("""(?im)^[ \t]*tone[ \t]*:?[ \t]*(.*)$""")
    private val TRANSCRIPTION_LINE =
        Regex("""(?im)^[ \t]*(?:transcription|transcript)[ \t]*:?[ \t]*(.*)$""")
    private val SCAFFOLD_LINE =
        Regex(
            """(?im)^[ \t]*(?:translation|summary|analysis|""" +
                """description|content|text)[ \t]*:?[ \t]*$""",
        )
    private val BLANK_RUN = Regex("""\n{3,}""")

    fun normalize(kind: MediaKind, text: String): String {
        val stripped = strip(text)
        if (kind != MediaKind.AUDIO || stripped.isBlank()) return stripped
        val hasTone = stripped.lineSequence().any { it.startsWith("Tone:") }
        val hasTranscription = stripped.lineSequence().any { it.startsWith("Transcription:") }
        return when {
            hasTone && hasTranscription -> stripped
            hasTranscription -> "Tone: unclear\n$stripped"
            hasTone -> "$stripped\nTranscription:"
            else -> "Tone: unclear\nTranscription:\n$stripped"
        }
    }

    fun strip(text: String): String {
        val lines =
            text
            .replace(HEADING, "")
            .replace(EMPHASIS) { it.groupValues[2] }
            .replace(TONE_LINE) { match ->
                "Tone:" + match.groupValues[1].trim().takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
            }
            .replace(TRANSCRIPTION_LINE) { match ->
                "Transcription:" +
                    match.groupValues[1].trim().takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
            }
            .replace(SCAFFOLD_LINE, "")
            .replace(BULLET, "")
            .replace(BLANK_RUN, "\n\n")
            .lineSequence()
            .map(String::trimEnd)
            .toList()
        return lines
            .filterIndexed { index, line ->
                val duplicateEmptyLabel =
                    (line == "Tone:" || line == "Transcription:") &&
                        lines.drop(index + 1).any { later ->
                            later.startsWith(line)
                        }
                !duplicateEmptyLabel
            }
            .joinToString("\n")
            .trim()
    }
}

/**
 * One-shot platform decoder for WhatsApp OGG/Opus and other device-supported
 * audio. It requests PCM16, collects at most [maximumBytes], then emits a normal
 * WAV container accepted by multimodal providers.
 */
private object AudioToWavDecoder {
    suspend fun decode(input: ByteArray, maximumBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            val source = ByteArrayMediaDataSource(input)
            try {
                extractor.setDataSource(source)
                val track =
                    (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index)
                            .getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    } ?: error("No audio track")
                extractor.selectTrack(track)
                val sourceFormat = extractor.getTrackFormat(track)
                val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("No audio MIME")
                sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                val activeCodec = MediaCodec.createDecoderByType(mime)
                codec = activeCodec
                activeCodec.configure(sourceFormat, null, null, 0)
                activeCodec.start()

                val pcm = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var sampleRate = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
                var channels = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer =
                                activeCodec.getInputBuffer(inputIndex)
                                    ?: error("Missing decoder input")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                activeCodec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                activeCodec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    extractor.sampleTime.coerceAtLeast(0),
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = activeCodec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val output = activeCodec.outputFormat
                            sampleRate = output.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = output.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            val encoding =
                                output.getIntegerOrDefault(
                                    MediaFormat.KEY_PCM_ENCODING,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                )
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                "Decoder did not produce PCM16"
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer =
                                activeCodec.getOutputBuffer(outputIndex)
                                    ?: error("Missing decoder output")
                            if (info.size > 0) {
                                require(pcm.size() + info.size + WAV_HEADER_BYTES <= maximumBytes) {
                                    "Decoded audio is too large"
                                }
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(data)
                                pcm.write(data)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            activeCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                wav(pcm.toByteArray(), sampleRate, channels)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
                // MediaExtractor.release() does not close a data source it did not open.
                source.close()
            }
        }

    private fun wav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        require(sampleRate > 0 && channels in 1..8)
        val result = ByteBuffer.allocate(WAV_HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        result.put("RIFF".toByteArray(Charsets.US_ASCII))
        result.putInt(36 + pcm.size)
        result.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        result.putInt(16)
        result.putShort(1.toShort())
        result.putShort(channels.toShort())
        result.putInt(sampleRate)
        result.putInt(sampleRate * channels * 2)
        result.putShort((channels * 2).toShort())
        result.putShort(16.toShort())
        result.put("data".toByteArray(Charsets.US_ASCII))
        result.putInt(pcm.size)
        result.put(pcm)
        return result.array()
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private const val TIMEOUT_US = 10_000L
    private const val WAV_HEADER_BYTES = 44
}

/**
 * Lets [MediaExtractor] read a clip that only exists in RAM.
 *
 * The platform decoder normally wants a path or a file descriptor, which is the one thing an
 * in-memory voice note must not be given: writing it out just to read it straight back would put
 * the decrypted audio on disk for the length of a turn. This adapter hands the same bytes to the
 * extractor directly — it seeks freely inside the array, which OGG container parsing needs.
 */
private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        // The contract is "-1 at or past the end", and the extractor does probe past the end.
        if (position < 0 || position >= data.size) return -1
        val available = (data.size - position).coerceAtMost(size.toLong()).toInt()
        if (available <= 0) return 0
        System.arraycopy(data, position.toInt(), buffer, offset, available)
        return available
    }

    override fun getSize(): Long = data.size.toLong()

    /** Nothing to release: the array is owned by the caller and dies with its frame. */
    override fun close() = Unit
}
