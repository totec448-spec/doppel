package de.totec.doppel.commands

/**
 * The complete native command vocabulary. Keeping the identifiers finite makes
 * exhaustive dispatch possible and prevents a command from being registered
 * without an implementation.
 */
enum class CommandId {
    HELP,
    STATUS,
    MOOD,
    META,
    ALLOWLIST,
    GROUP_ALLOWLIST,
    API_KEY,
    RESET,
    CLEAR_MEMORY,
    CLEAR_ALL,
    SETTINGS,
    SET,
    GET,
    MODEL,
    MEDIA_MODEL,
    HISTORY,
    SYSTEM,
    PERSONALITY,
    OFF,
    ON,
    CROSS_CHAT,
    VOICE,
    TRAITS,
    PERSONA,
    BLOCK,
    UNBLOCK,
    AUTOBLOCK,
    BLOCK_TOOL,
    PROACTIVE,
    REACHOUT,
    SAFETY,
}

data class CommandSpec(
    val id: CommandId,
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    val usage: String? = null,
) {
    val tokens: List<String>
        get() = listOf(name) + aliases
}

data class CommandInvocation(
    val spec: CommandSpec,
    val invokedName: String,
    val arguments: List<String>,
    val rawArguments: String,
)

sealed interface CommandParseResult {
    data object NotACommand : CommandParseResult

    data class UnknownCommand(
        val invokedName: String,
        val rawArguments: String,
    ) : CommandParseResult

    data class Parsed(
        val invocation: CommandInvocation,
    ) : CommandParseResult
}

/**
 * Command authorization is intentionally explicit input. The transport/router
 * is responsible for computing [isAdmin] from every known PN/LID identity.
 */
data class CommandRequest(
    val text: String,
    val senderId: String,
    val chatId: String,
    val isGroup: Boolean,
    val isAdmin: Boolean,
)

sealed interface CommandExecutionResult {
    /** Text does not start with the configured prefix. */
    data object NotACommand : CommandExecutionResult

    /** Prefixed text is unknown and must remain available to the normal engine. */
    data class UnknownCommandFallThrough(
        val invokedName: String,
    ) : CommandExecutionResult

    /**
     * A real command was recognized, but the sender is not an admin. This is not
     * a visible denial: the engine must treat the original text like a normal
     * chat message so the persona does not break character.
     */
    data class NotAuthorizedFallThrough(
        val commandName: String,
    ) : CommandExecutionResult

    data class Replied(
        val messages: List<String>,
    ) : CommandExecutionResult {
        init {
            require(messages.isNotEmpty())
            require(messages.none(String::isBlank))
        }

        constructor(message: String) : this(listOf(message))
    }
}
