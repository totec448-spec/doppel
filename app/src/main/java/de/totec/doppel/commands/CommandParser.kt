package de.totec.doppel.commands

import java.util.Locale

class CommandParser(
    private val registry: CommandRegistry,
    private val prefixProvider: () -> String,
) {
    fun parse(text: String): CommandParseResult = parse(text, prefixProvider())

    fun parse(text: String, prefix: String): CommandParseResult {
        require(prefix.isNotEmpty()) { "Command prefix must not be empty" }

        val trimmed = text.trim()
        if (!trimmed.startsWith(prefix)) return CommandParseResult.NotACommand

        val body = trimmed.removePrefix(prefix)
        val tokenEnd = body.indexOfFirst(Char::isWhitespace).let { if (it < 0) body.length else it }
        val invoked = body.substring(0, tokenEnd)
        val rawArguments = body.substring(tokenEnd).trimStart()
        if (invoked.isEmpty()) return CommandParseResult.UnknownCommand("", rawArguments)

        val spec = registry.resolve(invoked)
            ?: return CommandParseResult.UnknownCommand(invoked.lowercase(Locale.ROOT), rawArguments)
        val arguments = rawArguments
            .takeIf(String::isNotBlank)
            ?.split(Regex("\\s+"))
            .orEmpty()
        return CommandParseResult.Parsed(
            CommandInvocation(
                spec = spec,
                invokedName = invoked.lowercase(Locale.ROOT),
                arguments = arguments,
                rawArguments = rawArguments,
            ),
        )
    }
}
