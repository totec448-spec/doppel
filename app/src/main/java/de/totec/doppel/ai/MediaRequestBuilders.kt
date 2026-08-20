package de.totec.doppel.ai

import java.util.Base64

/**
 * Pure request builders only. Audio capture, transcoding and playback stay in Android-specific
 * layers; this file merely encodes already prepared media.
 */
class MediaRequestBuilders(
    private val maximumBytes: Int = 20 * 1024 * 1024,
) {
    init {
        require(maximumBytes > 0)
    }

    fun image(
        settings: ResolvedTurnSettings,
        bytes: ByteArray,
        mimeType: String,
        instruction: String,
        detail: String? = null,
    ): ChatCompletionRequest {
        requireMime(mimeType, "image/")
        return mediaRequest(
            settings = settings,
            instruction = instruction,
            part = ContentPart.ImageUrl(
                url = dataUrl(mimeType, bytes),
                detail = detail,
            ),
            tag = "image_analysis",
        )
    }

    /**
     * [model] overrides the media model. Only the transcription fallback passes it: when the media
     * model does not read audio, the very same request goes to a model that does.
     */
    fun audio(
        settings: ResolvedTurnSettings,
        bytes: ByteArray,
        format: String,
        instruction: String,
        model: String? = null,
    ): ChatCompletionRequest {
        require(format.lowercase() in AUDIO_FORMATS) {
            "Unsupported audio input format"
        }
        checkSize(bytes)
        return mediaRequest(
            settings = settings,
            instruction = instruction,
            part = ContentPart.InputAudio(
                base64Data = Base64.getEncoder().encodeToString(bytes),
                format = format.lowercase(),
            ),
            tag = if (model == null) "audio_analysis" else "audio_transcription",
            model = model?.trim()?.takeIf(String::isNotEmpty) ?: settings.model(ModelRole.MEDIA),
        )
    }

    fun video(
        settings: ResolvedTurnSettings,
        bytes: ByteArray,
        mimeType: String,
        instruction: String,
    ): ChatCompletionRequest {
        requireMime(mimeType, "video/")
        return mediaRequest(
            settings = settings,
            instruction = instruction,
            part = ContentPart.VideoUrl(dataUrl(mimeType, bytes)),
            tag = "video_analysis",
            reasoningEffort = settings.mediaReasoningEffort.forGeminiVideo(),
            maxTokens = VIDEO_ANSWER_TOKENS,
        )
    }

    private fun mediaRequest(
        settings: ResolvedTurnSettings,
        instruction: String,
        part: ContentPart,
        tag: String,
        model: String = settings.model(ModelRole.MEDIA),
        reasoningEffort: ReasoningEffort = settings.mediaReasoningEffort,
        maxTokens: Int = ANSWER_TOKENS,
    ): ChatCompletionRequest {
        require(instruction.isNotBlank() && instruction.length <= 20_000)
        return ChatCompletionRequest(
            model = model,
            messages = listOf(
                AiMessage(
                    role = ChatRole.USER,
                    content = MessageContent.Parts(
                        listOf(
                            ContentPart.Text(instruction.trim()),
                            part,
                        ),
                    ),
                ),
            ),
            // Media analysis is a separate model job. Reusing the main
            // model's effort/penalty profile can make OpenRouter reject every
            // provider for the media model (for example DeepSeek `max`
            // reasoning is not valid for Gemini Flash Lite). Keep this request
            // deliberately portable and bounded; the effort is the one knob the
            // user can raise, and it defaults to the provider's own choice.
            sampling =
                SamplingSettings(
                    temperature = 0.2,
                    topP = 1.0,
                    frequencyPenalty = 0.0,
                    presencePenalty = 0.0,
                    maxTokens = maxTokens,
                    reasoningEffort = reasoningEffort,
                ),
            stream = false,
            requestTag = tag,
        )
    }

    private fun dataUrl(mimeType: String, bytes: ByteArray): String {
        checkSize(bytes)
        return "data:${mimeType.lowercase()};base64," +
            Base64.getEncoder().encodeToString(bytes)
    }

    private fun requireMime(mimeType: String, prefix: String) {
        require(mimeType.lowercase().startsWith(prefix)) { "Unexpected media MIME type" }
        require(mimeType.none(Char::isWhitespace)) { "Invalid media MIME type" }
    }

    private fun checkSize(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Media cannot be empty" }
        require(bytes.size <= maximumBytes) { "Media exceeds the configured limit" }
    }

    companion object {
        val AUDIO_FORMATS = setOf("wav", "mp3")

        /**
         * The answer is one description or one transcript of a phone-sized clip, never an essay.
         * The old 10,000 was not a cap that ever bound the answer, it was a bill: OpenRouter holds
         * the full budget as credit before the call runs, so this number alone decided whether a
         * media analysis could start at all on a thin balance while cheap chat turns sailed past.
         */
        const val ANSWER_TOKENS = 2_500

        /** Video reasons about motion across frames, and that reasoning is billed as output. */
        const val VIDEO_ANSWER_TOKENS = 4_000
    }
}

/** Gemini Flash accepts minimal..high; legacy off/default values become minimal on video. */
internal fun ReasoningEffort.forGeminiVideo(): ReasoningEffort =
    when (this) {
        ReasoningEffort.PROVIDER_DEFAULT,
        ReasoningEffort.NONE,
        -> ReasoningEffort.MINIMAL
        ReasoningEffort.EXTRA_HIGH,
        ReasoningEffort.MAX,
        -> ReasoningEffort.HIGH
        else -> this
    }
