package de.totec.doppel.ai

import kotlin.math.max

/**
 * Every model is selected for a job instead of being scattered across call sites.
 * A missing specialised role deliberately falls back to [MAIN].
 */
enum class ModelRole {
    MAIN,
    UTILITY,
    VERIFY,
    MEDIA,
    IMAGE,
    TTS,

    /**
     * Reads a voice note when the media model cannot. Anything that takes audio in and answers in
     * text qualifies, which on OpenRouter is a normal chat model rather than a dedicated
     * transcription endpoint — the route does not have one.
     */
    TRANSCRIBE,
}

enum class ReasoningEffort(val wireValue: String?) {
    PROVIDER_DEFAULT(null),
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    EXTRA_HIGH("xhigh"),
    MAX("max"),
}

enum class ChatRole(val wireValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

enum class CacheControl {
    NONE,
    EPHEMERAL,
}

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    data class ImageUrl(
        val url: String,
        val detail: String? = null,
    ) : ContentPart

    data class InputAudio(
        val base64Data: String,
        val format: String,
    ) : ContentPart

    data class VideoUrl(val url: String) : ContentPart
}

sealed interface MessageContent {
    data class Text(val value: String) : MessageContent

    data class Parts(val values: List<ContentPart>) : MessageContent {
        init {
            require(values.isNotEmpty()) { "A multipart message cannot be empty" }
        }
    }
}

data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AiMessage(
    val role: ChatRole,
    val content: MessageContent? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val cacheControl: CacheControl = CacheControl.NONE,
) {
    init {
        if (role == ChatRole.TOOL) {
            require(!toolCallId.isNullOrBlank()) { "Tool messages require toolCallId" }
        }
    }

    fun textOrNull(): String? = (content as? MessageContent.Text)?.value

    companion object {
        fun text(
            role: ChatRole,
            value: String,
            cacheControl: CacheControl = CacheControl.NONE,
        ): AiMessage = AiMessage(
            role = role,
            content = MessageContent.Text(value),
            cacheControl = cacheControl,
        )
    }
}

data class SamplingSettings(
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val maxTokens: Int? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.PROVIDER_DEFAULT,
    /**
     * Hard ceiling on hidden reasoning tokens, so reasoning cannot consume the whole [maxTokens]
     * budget and leave an empty visible answer. `null` leaves the split to the provider.
     */
    val reasoningMaxTokens: Int? = null,
) {
    init {
        require(temperature in 0.0..2.0)
        require(topP in 0.0..1.0)
        require(frequencyPenalty in -2.0..2.0)
        require(presencePenalty in -2.0..2.0)
        require(maxTokens == null || maxTokens > 0)
        require(reasoningMaxTokens == null || reasoningMaxTokens > 0)
        require(reasoningEffort.wireValue == null || reasoningMaxTokens == null) {
            "OpenRouter reasoning.effort and reasoning.max_tokens are mutually exclusive"
        }
    }
}

data class ContextSettings(
    // Zero is deliberate here: the persistence layer already supplies the anchored,
    // settings-bounded window. A second default trim in the assembler would slide it again.
    val historyLimit: Int = 0,
    val memoryCharacterLimit: Int? = null,
    /**
     * How many messages beyond [historyLimit] the assembler may keep.
     *
     * The store hands over an *anchored* window: it pins the oldest message and lets the block grow
     * at its newest end so the provider's prefix cache keeps matching. Trimming that back to exactly
     * [historyLimit] on every turn would undo the anchor and turn the block into a sliding window
     * again — one cache miss per reply. The slack is the same quantization step the store uses, so
     * it bounds memory without cutting into the anchored region.
     */
    val historyWindowSlack: Int = 0,
) {
    init {
        require(historyLimit >= 0)
        require(memoryCharacterLimit == null || memoryCharacterLimit > 0)
        require(historyWindowSlack >= 0)
    }

    /** Upper bound the prompt assembler may render, or `null` when the limit is "everything". */
    fun renderableHistoryLimit(): Int? =
        if (historyLimit == 0) null else historyLimit + historyWindowSlack
}

data class OutputSettings(
    val allowNoReply: Boolean = true,
    val allowReactions: Boolean = true,
    val allowQuoteReply: Boolean = true,
    val maxBubbles: Int = 8,
) {
    init {
        require(maxBubbles in 1..8)
    }
}

data class ToolAccessSettings(
    val crossChatSearch: Boolean = false,
    /** Current-chat timer state; deliberately independent of cross-chat transcript access. */
    val followUpScheduling: Boolean = true,
    val imageSending: Boolean = false,
    val imageGeneration: Boolean = false,
    val voiceNotes: Boolean = false,
    val memoryRefresh: Boolean = true,
    val contactBlocking: Boolean = false,
)

data class VerificationSettings(
    val enabled: Boolean = false,
    val maxRegenerations: Int = 0,
    /** Total output budget for one verification call, including hidden reasoning tokens. */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    /**
     * Whether an unusable verification (transport error, empty or malformed verdict) blocks the
     * reply. Default is fail-open: an infrastructure problem in the optional safety check must not
     * swallow an answer the user is waiting for. The decision is always logged with its reason.
     */
    val failClosed: Boolean = false,
) {
    init {
        require(maxRegenerations in 0..5)
        require(maxTokens >= MINIMUM_VERDICT_TOKENS) {
            "Verification needs at least $MINIMUM_VERDICT_TOKENS output tokens for the verdict"
        }
    }

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
        const val MINIMUM_VERDICT_TOKENS = 32
    }
}

data class MediaAnalysisPromptSettings(
    val image: String = "",
    val video: String = "",
    val voice: String = "",
) {
    init {
        require(image.length <= MAX_PROMPT_CHARACTERS)
        require(video.length <= MAX_PROMPT_CHARACTERS)
        require(voice.length <= MAX_PROMPT_CHARACTERS)
    }

    companion object {
        const val MAX_PROMPT_CHARACTERS = 16_000
    }
}

/**
 * Fully resolved settings for one turn. Callers merge global/persona/chat settings once,
 * then pass this immutable snapshot through the complete turn.
 */
data class ResolvedTurnSettings(
    val models: Map<ModelRole, String>,
    val baseInstructions: String,
    val customInstructions: String = "",
    val sampling: SamplingSettings = SamplingSettings(),
    val context: ContextSettings = ContextSettings(),
    val output: OutputSettings = OutputSettings(),
    val tools: ToolAccessSettings = ToolAccessSettings(),
    val verification: VerificationSettings = VerificationSettings(),
    val mediaAnalysisPrompts: MediaAnalysisPromptSettings = MediaAnalysisPromptSettings(),

    /**
     * Reasoning effort for media analysis. Separate from [sampling] because that profile belongs
     * to the main model: handing a media model an effort its provider does not accept makes
     * OpenRouter reject every route for it.
     */
    val mediaReasoningEffort: ReasoningEffort = ReasoningEffort.PROVIDER_DEFAULT,
    val preferStreaming: Boolean = true,
    /**
     * How many rounds the model may spend calling tools before it has to answer.
     *
     * Counted in rounds the model actually used a tool in — an empty provider response does not
     * spend one. Running out is no longer fatal either: the last call goes out with the tool list
     * removed and a note to answer from what it has, so a research-heavy turn ends in a reply
     * rather than in an exception that discards everything it already paid for. That is why this
     * can sit high without being expensive: nothing forces the model to use the budget.
     */
    val toolLoopLimit: Int = 10,
) {
    init {
        require(!models[ModelRole.MAIN].isNullOrBlank()) { "A MAIN model is required" }
        require(baseInstructions.isNotBlank()) { "Base instructions cannot be blank" }
        require(toolLoopLimit in 1..MAX_TOOL_LOOP_LIMIT) {
            "Tool loop limit must be between 1 and $MAX_TOOL_LOOP_LIMIT"
        }
        require(models.none { it.value.isBlank() }) { "Model identifiers cannot be blank" }
    }

    fun model(role: ModelRole): String = models[role] ?: requireNotNull(models[ModelRole.MAIN])

    companion object {
        /** A ceiling, not a target: the per-turn output budget is what actually caps the spend. */
        const val MAX_TOOL_LOOP_LIMIT = 16
    }
}

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val maximumLength: Int? = null,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val additionalProperties: Boolean = false,
)

enum class ToolChoice(val wireValue: String) {
    AUTO("auto"),

    /**
     * The model may not call a tool.
     *
     * Not for requests that piggyback on another turn's prompt to reuse its cache: the tool
     * definitions are rendered into the prompt by the provider, and asking for a different
     * `tool_choice` than the turn used appears to change that rendering — see the measurement in
     * [de.totec.doppel.integration.MemoryRefreshService]. Such a request has to stay
     * byte-identical to the turn and forbid the call in words instead.
     */
    NONE("none"),
}

data class ChatCompletionRequest(
    val model: String,
    val messages: List<AiMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.AUTO,
    val sampling: SamplingSettings = SamplingSettings(),
    val stream: Boolean = true,
    val requestTag: String? = null,
) {
    init {
        require(model.isNotBlank())
        require(messages.isNotEmpty())
    }
}

data class TokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedPromptTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
)

data class CompletionResult(
    val content: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val usage: TokenUsage? = null,
    val model: String? = null,
    val provider: String? = null,
    val finishReason: String? = null,
    /**
     * The chain of thought, when the provider returned one. Paid for as output either way, so it
     * is asked for rather than excluded; [LiveTokenSink] is what shows it while it is still
     * arriving, and this is the same text once the stream has closed.
     */
    val reasoning: String? = null,
)

fun interface ChatCompletionGateway {
    suspend fun complete(request: ChatCompletionRequest): CompletionResult
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val maximumDelayMs: Long = 8_000,
) {
    init {
        require(maxAttempts >= 1)
        require(initialDelayMs >= 0)
        require(maximumDelayMs >= initialDelayMs)
    }

    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 1 || initialDelayMs == 0L) return initialDelayMs
        var result = initialDelayMs
        repeat((attempt - 1).coerceAtMost(62)) {
            if (result >= maximumDelayMs || result > maximumDelayMs / 2) {
                return maximumDelayMs
            }
            result *= 2
        }
        return result.coerceAtMost(maximumDelayMs)
    }
}

internal fun String.limitedTo(maxCharacters: Int?): String {
    if (maxCharacters == null || length <= maxCharacters) return this
    if (maxCharacters <= 1) return take(max(0, maxCharacters))
    return take(maxCharacters - 1).trimEnd() + "…"
}
