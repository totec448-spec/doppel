package de.totec.doppel.ai

import org.json.JSONException
import org.json.JSONObject
import java.time.OffsetDateTime

enum class ToolEffect {
    READ_ONLY,
    PENDING_ACTION,
}

enum class ToolGate {
    ALWAYS,
    MEMORY_REFRESH,
    CROSS_CHAT,
    FOLLOW_UP,
    IMAGE_SEND,
    IMAGE_GENERATION,
    VOICE_NOTE,
    CONTACT_BLOCK,
}

data class RegisteredTool(
    val definition: ToolDefinition,
    val effect: ToolEffect,
    private val gate: ToolGate,
) {
    fun isAllowed(settings: ToolAccessSettings): Boolean = when (gate) {
        ToolGate.ALWAYS -> true
        ToolGate.MEMORY_REFRESH -> settings.memoryRefresh
        ToolGate.CROSS_CHAT -> settings.crossChatSearch
        ToolGate.FOLLOW_UP -> settings.followUpScheduling
        ToolGate.IMAGE_SEND -> settings.imageSending
        ToolGate.IMAGE_GENERATION -> settings.imageGeneration
        ToolGate.VOICE_NOTE -> settings.voiceNotes
        ToolGate.CONTACT_BLOCK -> settings.contactBlocking
    }
}

data class ToolExecutionContext(
    val conversationKey: String,
    val turnId: String? = null,
    val currentSender: String? = null,
    val personaKey: String? = null,
    /**
     * Whether `list_sendable_images` is exposed this turn *and* has something to show. It is the
     * precondition for making a new picture: a generation is paid for, an existing photo is not.
     */
    val sendableImagesAvailable: Boolean = false,
    val blockRepeatedImages: Boolean = false,
    val isGroup: Boolean = false,
    val isAdmin: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
    val session: ToolSessionState = ToolSessionState(),
) {
    init {
        require(conversationKey.isNotBlank())
        require(personaKey == null || personaKey.matches(Regex("^[a-z0-9_-]{2,40}$")))
    }
}

/** Bounded per-turn evidence that a prerequisite read-only tool actually completed. */
class ToolSessionState {
    private val completed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun mark(name: String) {
        if (completed.size < 16) completed += name
    }

    /**
     * Forgets one mark. A rejected candidate's tool calls never happen, so the once-per-turn
     * budget it consumed has to come back before the regeneration asks for the same thing.
     */
    fun clear(name: String) {
        completed -= name
    }

    fun has(name: String): Boolean = name in completed
}

data class ToolExecutionResult(
    val json: String,
    val isError: Boolean = false,
)

fun interface ReadOnlyToolExecutor {
    suspend fun execute(
        context: ToolExecutionContext,
        call: AiToolCall,
    ): ToolExecutionResult
}

sealed interface PendingAction {
    val toolCallId: String

    data class RefreshChatMemory(
        override val toolCallId: String,
        val conversationKey: String,
    ) : PendingAction

    data class SendImage(
        override val toolCallId: String,
        val assetId: String,
        val caption: String?,
    ) : PendingAction

    data class GenerateImage(
        override val toolCallId: String,
        val prompt: String,
        val caption: String?,
        val includeCharacter: Boolean,
    ) : PendingAction

    data class SendVoiceNote(
        override val toolCallId: String,
        val spokenText: String,
        val style: String?,
    ) : PendingAction

    data class BlockContact(
        override val toolCallId: String,
    ) : PendingAction

    data class ScheduleFollowUp(
        override val toolCallId: String,
        val conversationKey: String,
        val personaKey: String,
        val scheduledAtMs: Long,
        val note: String,
    ) : PendingAction
}

data class ActionCommitReceipt(
    val toolCallId: String,
    val committed: Boolean,
    val reasonCode: String? = null,
)

fun interface PendingActionCommitter {
    /**
     * Implementations own all transport/storage mutations. They must remain idempotent by
     * [PendingAction.toolCallId], because an interrupted process can retry a verified batch.
     */
    suspend fun commit(
        context: ToolExecutionContext,
        actions: List<PendingAction>,
    ): List<ActionCommitReceipt>
}

sealed interface PreparedToolCall {
    data class ReadOnly(val call: AiToolCall) : PreparedToolCall

    data class Action(val value: PendingAction) : PreparedToolCall

    data class Rejected(val reasonCode: String) : PreparedToolCall
}

/**
 * Single source of truth for tool visibility and side-effect classification.
 */
object ToolRegistry {
    private val query = ToolParameter(
        name = "query",
        type = "string",
        description = "One word or a short phrase to find.",
        required = true,
        maximumLength = 100,
    )
    private val limit = ToolParameter(
        name = "limit",
        type = "integer",
        description = "Maximum result count.",
        minimum = 1.0,
        maximum = 50.0,
    )

    val all: List<RegisteredTool> = listOf(
        registered(
            name = "search_current_chat",
            description =
                "Find a word or short phrase in the current chat. Returns matching messages " +
                    "with nearby messages for context. Pass only query.",
            effect = ToolEffect.READ_ONLY,
            gate = ToolGate.ALWAYS,
            parameters = listOf(query),
        ),
        registered(
            name = "scroll_current_chat",
            description =
                "Read the next older messages in the current chat. Call with no arguments; " +
                    "repeated calls automatically continue further back.",
            effect = ToolEffect.READ_ONLY,
            gate = ToolGate.ALWAYS,
            parameters = emptyList(),
        ),
        registered(
            name = "request_chat_memory_refresh",
            description =
                "Immediately update this chat memory and then the persona-wide global memory. " +
                    "Use only for rare, status-changing emotional, social, identity, home or " +
                    "relationship developments; never for ordinary facts, plans or small talk.",
            effect = ToolEffect.PENDING_ACTION,
            gate = ToolGate.MEMORY_REFRESH,
        ),
        registered(
            name = "list_chats",
            description =
                "List recent conversations without exposing message bodies. Each entry has an " +
                    "id for search_chat, a name, kind (private or group) and the contact's " +
                    "phone number where one is known.",
            effect = ToolEffect.READ_ONLY,
            gate = ToolGate.CROSS_CHAT,
            parameters = emptyList(),
        ),
        registered(
            name = "search_chat",
            description = "Search message text in another permitted conversation.",
            effect = ToolEffect.READ_ONLY,
            gate = ToolGate.CROSS_CHAT,
            parameters = listOf(
                ToolParameter(
                    "chat",
                    "string",
                    "Opaque conversation id returned by list_chats.",
                    required = true,
                    maximumLength = 256,
                ),
                query,
                limit,
            ),
        ),
        registered(
            name = LIST_SCHEDULED_FOLLOWUPS,
            description =
                "List this persona's already planned follow-ups across all chats, including " +
                    "contact names, due times and reminders. You must inspect this result before " +
                    "creating or replacing a follow-up.",
            effect = ToolEffect.READ_ONLY,
            // This returns only the active persona's own opaque plans, not another chat's
            // transcript. Requiring cross-chat search made the model lose its timer tool when
            // cross-chat search was disabled, even though scheduling needs no cross-chat read.
            gate = ToolGate.FOLLOW_UP,
            parameters = listOf(limit),
        ),
        registered(
            name = SCHEDULE_FOLLOWUP,
            description =
                "Create or replace the one durable later follow-up for the current direct chat. " +
                    "Call list_scheduled_followups in an earlier tool round first.",
            effect = ToolEffect.PENDING_ACTION,
            // The action can target only the current direct conversation and is committed through
            // the normal pending-action boundary, so it is independent of cross-chat search.
            gate = ToolGate.FOLLOW_UP,
            parameters = listOf(
                ToolParameter(
                    "scheduled_at",
                    "string",
                    "Exact future ISO-8601 date-time with UTC offset, for example 2026-08-11T17:00:00+02:00.",
                    required = true,
                    maximumLength = 64,
                ),
                ToolParameter(
                    "note",
                    "string",
                    "Short private reminder of what you committed to follow up about.",
                    required = true,
                    maximumLength = 500,
                ),
            ),
        ),
        registered(
            name = LIST_SENDABLE_IMAGES,
            description =
                "List the photos you already have and may still send in this chat. Every picture " +
                    "you ever made is in here, so look before making a new one.",
            effect = ToolEffect.READ_ONLY,
            gate = ToolGate.IMAGE_SEND,
            parameters = listOf(query.copy(required = false), limit),
        ),
        registered(
            name = "send_image",
            description = "Queue one approved image asset for delivery after verification.",
            effect = ToolEffect.PENDING_ACTION,
            gate = ToolGate.IMAGE_SEND,
            parameters = listOf(
                ToolParameter(
                    "asset_id",
                    "string",
                    "Opaque identifier returned by list_sendable_images.",
                    required = true,
                    maximumLength = 256,
                ),
                ToolParameter(
                    "caption",
                    "string",
                    "Optional short caption.",
                    maximumLength = 1_024,
                ),
            ),
        ),
        registered(
            name = "send_voice_note",
            description = "Queue spoken text as a voice note after verification.",
            effect = ToolEffect.PENDING_ACTION,
            gate = ToolGate.VOICE_NOTE,
            parameters = listOf(
                ToolParameter(
                    "text",
                    "string",
                    "Final words to speak. Gemini expression tags such as [angry], [excited], " +
                        "[whispers], [laughs], [short pause], or [shouting] may be inserted inline " +
                        "to control the following delivery; use them sparingly and never explain them.",
                    required = true,
                    maximumLength = 4_000,
                ),
                ToolParameter(
                    "style",
                    "string",
                    "Optional short whole-note vocal direction; inline expression tags belong in text.",
                    maximumLength = 500,
                ),
            ),
        ),
        registered(
            name = "generate_image",
            description =
                "Take a NEW photo and send it to this chat. One call does everything: the picture " +
                    "is created from your prompt and sent automatically — you never upload, " +
                    "confirm or send it yourself. Call this at most ONCE per turn, and only after " +
                    "list_sendable_images showed nothing that fits: an existing photo is free, a " +
                    "new one is not.",
            effect = ToolEffect.PENDING_ACTION,
            gate = ToolGate.IMAGE_GENERATION,
            parameters = listOf(
                ToolParameter(
                    "prompt",
                    "string",
                    "What is in the photo, in plain English: subject, setting, action, light, mood. " +
                        "Your own looks and the casual phone-photo style are added for you, so do " +
                        "not describe them. Example: \"my cat asleep on a grey blanket, morning light\".",
                    required = true,
                    maximumLength = 2_000,
                ),
                ToolParameter(
                    "include_character",
                    "boolean",
                    "Set true only for a photo you are visible in, such as a selfie. Leave it out " +
                        "or set false for everything else: food, pets, objects, a view. Defaults to false.",
                ),
                ToolParameter(
                    "caption",
                    "string",
                    "Optional: the short chat line that goes with the photo, written the way you " +
                        "normally text. Leave it out and send your text as a normal message instead.",
                    maximumLength = 1_024,
                ),
            ),
        ),
        registered(
            name = "block_contact",
            description =
                "Block the current direct-message sender on WhatsApp. " +
                    "Call this tool without arguments; the app resolves the verified sender ID.",
            effect = ToolEffect.PENDING_ACTION,
            gate = ToolGate.CONTACT_BLOCK,
            parameters = emptyList(),
        ),
    )

    private val byName: Map<String, RegisteredTool> = all.associateBy { it.definition.name }

    val names: Set<String> = byName.keys

    fun allowed(settings: ToolAccessSettings): List<ToolDefinition> =
        all.asSequence()
            .filter { it.isAllowed(settings) }
            .map { it.definition }
            .toList()

    fun prepare(
        call: AiToolCall,
        settings: ToolAccessSettings,
        context: ToolExecutionContext,
    ): PreparedToolCall {
        val registered = byName[call.name]
            ?: return PreparedToolCall.Rejected("unknown_tool")
        if (!registered.isAllowed(settings)) {
            return PreparedToolCall.Rejected("tool_disabled")
        }
        val arguments = try {
            if (call.argumentsJson.isBlank()) JSONObject() else JSONObject(call.argumentsJson)
        } catch (_: JSONException) {
            return PreparedToolCall.Rejected("invalid_arguments")
        }
        when (validateArguments(registered.definition, arguments)) {
            ArgumentValidation.VALID -> Unit
            ArgumentValidation.MISSING ->
                return PreparedToolCall.Rejected("missing_argument")
            ArgumentValidation.INVALID ->
                return PreparedToolCall.Rejected("invalid_arguments")
        }
        if (registered.effect == ToolEffect.READ_ONLY) {
            return PreparedToolCall.ReadOnly(call)
        }
        if (call.name == "block_contact") {
            context.currentSender
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return PreparedToolCall.Rejected("no_current_sender")
            if (context.isGroup) return PreparedToolCall.Rejected("block_not_allowed_in_group")
            if (context.isAdmin) return PreparedToolCall.Rejected("admin_is_protected")
        }
        if (call.name == SCHEDULE_FOLLOWUP) {
            if (context.isGroup) return PreparedToolCall.Rejected("followup_not_allowed_in_group")
            if (!context.session.has(LIST_SCHEDULED_FOLLOWUPS)) {
                return PreparedToolCall.Rejected("scheduled_followups_not_listed")
            }
            // One armed time per turn. Only the last call survives in the store anyway, so a model
            // that hedges with three times does not get three reminders — it gets one, plus two
            // promises in the chat text that nothing will ever keep. The duplicate-action guard
            // never caught this: three different times are three different fingerprints.
            if (context.session.has(SCHEDULE_FOLLOWUP)) {
                return PreparedToolCall.Rejected("followup_already_scheduled")
            }
        }
        if (call.name == "generate_image") {
            // One picture per turn, counted across the continuation that follows a confirmed send:
            // both calls share this session. A generated image is the most expensive thing this
            // bot can do, and a model that is unsure whether the first call worked will happily
            // pay for a second one. It cannot any more.
            if (context.session.has(GENERATED_IMAGE)) {
                return PreparedToolCall.Rejected("image_already_generated")
            }
            // Look before you buy. Every generated picture is kept and stays sendable, so the
            // library grows with every one that was paid for — and the cheapest photo is the one
            // that already exists. This only applies while there is a library to look at: when the
            // list is empty or the tool is not exposed, there is nothing to prefer over generating.
            if (context.sendableImagesAvailable && !context.session.has(LIST_SENDABLE_IMAGES)) {
                return PreparedToolCall.Rejected("sendable_images_not_listed")
            }
            // A persona without reference photos used to be refused here, which made every
            // generation impossible for personas that have no base images at all — the call never
            // reached the provider, so the failure was invisible in the OpenRouter log too.
            // References improve likeness when they exist; they are not a precondition for taking
            // a picture. The absence is handled where the prompt is written, not by a rejection.
        }

        val action = when (call.name) {
            "request_chat_memory_refresh" -> PendingAction.RefreshChatMemory(
                toolCallId = call.id,
                conversationKey = context.conversationKey,
            )
            "send_image" -> PendingAction.SendImage(
                toolCallId = call.id,
                assetId = arguments.requiredString("asset_id"),
                caption = arguments.optionalString("caption"),
            )
            "send_voice_note" -> PendingAction.SendVoiceNote(
                toolCallId = call.id,
                spokenText = arguments.requiredString("text"),
                style = arguments.optionalString("style"),
            )
            "generate_image" -> PendingAction.GenerateImage(
                toolCallId = call.id,
                prompt = arguments.requiredString("prompt"),
                caption = arguments.optionalString("caption"),
                includeCharacter = arguments.optBoolean("include_character", false),
            ).also { context.session.mark(GENERATED_IMAGE) }
            "block_contact" -> PendingAction.BlockContact(
                toolCallId = call.id,
            )
            SCHEDULE_FOLLOWUP -> {
                val dueAt =
                    runCatching {
                        OffsetDateTime.parse(arguments.requiredString("scheduled_at"))
                            .toInstant()
                            .toEpochMilli()
                    }.getOrElse {
                        return PreparedToolCall.Rejected("invalid_scheduled_time")
                    }
                if (dueAt < context.nowMs + MIN_FOLLOWUP_DELAY_MS) {
                    return PreparedToolCall.Rejected("scheduled_time_not_future")
                }
                if (dueAt > context.nowMs + MAX_FOLLOWUP_HORIZON_MS) {
                    return PreparedToolCall.Rejected("scheduled_time_too_far")
                }
                PendingAction.ScheduleFollowUp(
                    toolCallId = call.id,
                    conversationKey = context.conversationKey,
                    personaKey = context.personaKey
                        ?: return PreparedToolCall.Rejected("persona_unavailable"),
                    scheduledAtMs = dueAt,
                    note = arguments.requiredString("note"),
                ).also { context.session.mark(SCHEDULE_FOLLOWUP) }
            }
            else -> return PreparedToolCall.Rejected("action_mapping_missing")
        }
        return PreparedToolCall.Action(action)
    }

    private fun validateArguments(
        definition: ToolDefinition,
        arguments: JSONObject,
    ): ArgumentValidation {
        val known = definition.parameters.associateBy(ToolParameter::name)
        if (!definition.additionalProperties && arguments.keys().asSequence().any { it !in known }) {
            return ArgumentValidation.INVALID
        }
        definition.parameters.forEach { parameter ->
            if (!arguments.has(parameter.name) || arguments.isNull(parameter.name)) {
                if (parameter.required) return ArgumentValidation.MISSING
                return@forEach
            }
            val raw = arguments.opt(parameter.name)
            val validType = when (parameter.type) {
                "string" -> raw is String && (!parameter.required || raw.isNotBlank())
                "integer" -> raw is Number && raw.toDouble().let {
                    it.isFinite() && it % 1.0 == 0.0
                }
                "number" -> raw is Number && raw.toDouble().isFinite()
                "boolean" -> raw is Boolean
                "object" -> raw is JSONObject
                "array" -> raw is org.json.JSONArray
                else -> false
            }
            if (!validType) return ArgumentValidation.INVALID
            if (
                raw is String &&
                parameter.maximumLength != null &&
                raw.length > parameter.maximumLength
            ) {
                return ArgumentValidation.INVALID
            }
            if (raw is Number) {
                val numeric = raw.toDouble()
                if (parameter.minimum != null && numeric < parameter.minimum) {
                    return ArgumentValidation.INVALID
                }
                if (parameter.maximum != null && numeric > parameter.maximum) {
                    return ArgumentValidation.INVALID
                }
            }
            if (
                parameter.enumValues.isNotEmpty() &&
                raw !in parameter.enumValues
            ) {
                return ArgumentValidation.INVALID
            }
        }
        return ArgumentValidation.VALID
    }

    private fun registered(
        name: String,
        description: String,
        effect: ToolEffect,
        gate: ToolGate,
        parameters: List<ToolParameter> = emptyList(),
    ) = RegisteredTool(
        definition = ToolDefinition(
            name = name,
            description = description,
            parameters = parameters,
        ),
        effect = effect,
        gate = gate,
    )

    private fun JSONObject.requiredString(name: String): String =
        getString(name).trim()

    private fun JSONObject.optionalString(name: String): String? =
        optString(name).trim().takeIf { it.isNotEmpty() }

    private enum class ArgumentValidation {
        VALID,
        MISSING,
        INVALID,
    }

    const val LIST_SCHEDULED_FOLLOWUPS = "list_scheduled_followups"
    const val SCHEDULE_FOLLOWUP = "schedule_followup"
    const val LIST_SENDABLE_IMAGES = "list_sendable_images"

    /** Session mark: one picture has already been paid for in this turn. */
    const val GENERATED_IMAGE = "generated_image"

    private const val MIN_FOLLOWUP_DELAY_MS = 60_000L
    private const val MAX_FOLLOWUP_HORIZON_MS = 30L * 24L * 60L * 60L * 1_000L

    /**
     * What a refusal means and what to do instead, in one sentence, for the model that has to act
     * on it. A bare code like `character_reference_required` reads as a fault the model can retry
     * its way out of, so it retried — the same call, the same refusal, until the tool budget ran
     * out. Every line here therefore ends in a concrete alternative, including "give up and say so".
     */
    fun guidanceFor(reasonCode: String): String? = when (reasonCode) {
        "sendable_images_not_listed" ->
            "You have photos already and have not looked at them. Call list_sendable_images " +
                "first, send one with send_image if anything fits what you want to show, and " +
                "only generate a new picture when nothing does."
        "image_already_generated" ->
            "You already sent a picture in this turn and it is on its way. Do not generate " +
                "another one. Write your normal chat message."
        "followup_already_scheduled" ->
            "You already set a time in this turn and it is armed. There is only one, so do not " +
                "set another. Write your normal chat message."
        "no_approved_asset_for_persona", "tool_disabled" ->
            "This is switched off for this chat. Do not try it again in this turn; answer with " +
                "text instead."
        "scheduled_followups_not_listed" ->
            "Call list_scheduled_followups first, then schedule_followup."
        "scheduled_time_not_future" ->
            "The time must be at least a minute from now. Pick a later time and call again."
        "scheduled_time_too_far" ->
            "The time is more than 30 days away. Pick a closer time and call again."
        "followup_not_allowed_in_group", "block_not_allowed_in_group" ->
            "Not possible in a group chat. Answer with text instead."
        "admin_is_protected" ->
            "This contact cannot be blocked. Answer with text instead."
        "duplicate_action" ->
            "You already asked for exactly this in this turn. Write your chat message."
        "invalid_arguments", "missing_argument" ->
            "The arguments did not match the tool's schema. Read the parameter list and call it " +
                "once more, correctly."
        else -> null
    }
}
