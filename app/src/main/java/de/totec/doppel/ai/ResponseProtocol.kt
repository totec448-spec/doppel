package de.totec.doppel.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class QuoteDirective(val sourceSnippet: String?)

data class ParsedAssistantResponse(
    val noReply: Boolean,
    val reaction: String?,
    val quote: QuoteDirective?,
    val bubbles: List<String>,
    val fallbackToolCalls: List<AiToolCall>,
    /** Reply directive aligned by index with [bubbles]; null means a normal text bubble. */
    val bubbleReplies: List<QuoteDirective?> = emptyList(),
    /** True when a reasoning block had to be cut out of the completion before parsing. */
    val strippedReasoning: Boolean = false,
    val strippedReasoningCharacters: Int = 0,
)

/**
 * Parses the deliberately tiny text protocol. Unknown or malformed action syntax stays inert
 * text; it never becomes a side effect by best effort.
 */
class ResponseProtocolParser(
    private val knownToolNames: Set<String> = ToolRegistry.names,
) {
    fun parse(
        raw: String,
        output: OutputSettings = OutputSettings(),
    ): ParsedAssistantResponse {
        // Before anything else: a chain of thought that ended up in `content` is not part of the
        // protocol, and every rule below would otherwise be applied to it.
        val reasoning = ReasoningLeakGuard.strip(raw)
        val trimmed = reasoning.text.trim()
        // This is a reserved control token, never user-visible text. Whether the model should use
        // it is a prompt-policy decision; treating it as an ordinary bubble when a legacy flag is
        // false leaks internal protocol syntax straight into WhatsApp.
        if (trimmed == NO_REPLY || (trimmed.isEmpty() && reasoning.stripped)) {
            return ParsedAssistantResponse(
                noReply = true,
                reaction = null,
                quote = null,
                bubbles = emptyList(),
                fallbackToolCalls = emptyList(),
                strippedReasoning = reasoning.stripped,
                strippedReasoningCharacters = reasoning.strippedCharacters,
            )
        }

        val jsonFallbacks = parseJsonToolFallback(trimmed)
        if (jsonFallbacks != null) {
            return ParsedAssistantResponse(
                noReply = false,
                reaction = null,
                quote = null,
                bubbles = emptyList(),
                fallbackToolCalls = jsonFallbacks,
                strippedReasoning = reasoning.stripped,
                strippedReasoningCharacters = reasoning.strippedCharacters,
            )
        }

        val bracket = parseBracketToolFallbacks(trimmed)
        var clean = bracket.remainingText

        var reaction: String? = null
        clean = REACTION.replace(clean) { match ->
            if (output.allowReactions) {
                val candidate = match.groupValues[1].trim()
                if (reaction == null && isSafeReaction(candidate)) {
                    reaction = candidate
                }
            }
            ""
        }

        val parsed = parseBubbles(clean, output.allowQuoteReply)
        val parsedBubbles = parsed.bubbles
            // Never glue overflow back into one giant WhatsApp message. The prompt advertises the
            // same bound; truncation is the deterministic safety fallback if a model exceeds it.
            .take(output.maxBubbles)
        val bubbles = parsedBubbles.map(ParsedBubble::text)
        val bubbleReplies = parsedBubbles.map(ParsedBubble::reply)
        // A directive that never found a bubble still says which message was meant — a bare
        // `[reply]` next to a reaction is a valid, text-free response.
        val quote = bubbleReplies.firstOrNull { it != null } ?: parsed.unattachedReply

        return ParsedAssistantResponse(
            noReply = false,
            reaction = reaction,
            quote = quote,
            bubbles = bubbles,
            fallbackToolCalls = bracket.calls,
            bubbleReplies = bubbleReplies,
            strippedReasoning = reasoning.stripped,
            strippedReasoningCharacters = reasoning.strippedCharacters,
        )
    }

    /**
     * Splits the visible text into bubbles and attaches each Reply directive to the bubble it
     * belongs to.
     *
     * A marker that stands on its own line used to be discarded together with the empty chunk it
     * left behind, which is why every requested Reply arrived as a plain message: models overwhelmingly
     * write the directive on one line and the sentence on the next. A lone marker is therefore not a
     * bubble but a carried instruction, applied to the next line that does have text.
     */
    private fun parseBubbles(
        text: String,
        allowQuoteReply: Boolean,
    ): ParsedBubbles {
        val chunks = text
            .trim()
            .split(BUBBLE_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (!allowQuoteReply) {
            return ParsedBubbles(
                chunks.mapNotNull { chunk ->
                    sanitizeBubble(splitQuoteMarker(chunk).visible)
                        .takeIf(String::isNotEmpty)
                        ?.let { ParsedBubble(it, null) }
                },
                unattachedReply = null,
            )
        }

        val bubbles = mutableListOf<ParsedBubble>()
        var carried: QuoteDirective? = null
        chunks.forEach { chunk ->
            val parsed = splitQuoteMarker(chunk)
            val visible = sanitizeBubble(parsed.visible)
            if (visible.isEmpty()) {
                // Marker-only line: remember it instead of dropping it on the floor.
                carried = parsed.reply ?: carried
                return@forEach
            }
            bubbles += ParsedBubble(visible, parsed.reply ?: carried)
            carried = null
        }
        return ParsedBubbles(bubbles, carried)
    }

    /**
     * Last line of defence for text that is about to be sent verbatim to WhatsApp.
     *
     * Models copy both of these from the context they are shown: the `You sent: ...` history labels
     * and the TTS expression tags of an earlier voice note. Neither is ever legitimate visible text,
     * and a prompt rule alone did not stop it.
     */
    private fun sanitizeBubble(text: String): String =
        OutboundSurfaceSanitizer.sanitize(text)

    private fun splitQuoteMarker(chunk: String): MarkerSplit {
        var reply: QuoteDirective? = null
        val visible =
            QUOTE.replace(chunk) { match ->
                if (reply == null) {
                    reply = QuoteDirective(match.groupValues.getOrNull(1).cleanSnippet())
                }
                ""
            }.trim()
        return MarkerSplit(visible, reply)
    }

    /**
     * Normalises whatever the model put after `reply:`. Quotes are optional, because requiring them
     * only produced literal `[reply: ...]` text in the chat when a model left them out.
     */
    private fun String?.cleanSnippet(): String? =
        this.orEmpty()
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
            .takeIf(String::isNotEmpty)

    private fun parseJsonToolFallback(raw: String): List<AiToolCall>? {
        if (raw.isEmpty() || (raw.first() != '{' && raw.first() != '[')) return null
        return try {
            when (raw.first()) {
                '{' -> callsFromObject(JSONObject(raw)).takeIf { it.isNotEmpty() }
                '[' -> callsFromArray(JSONArray(raw)).takeIf { it.isNotEmpty() }
                else -> null
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun callsFromObject(root: JSONObject): List<AiToolCall> {
        val nested = root.optJSONArray("tool_calls") ?: root.optJSONArray("tools")
        if (nested != null) return callsFromArray(nested)
        return listOfNotNull(callFromObject(root))
    }

    private fun callsFromArray(array: JSONArray): List<AiToolCall> =
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                callFromObject(value)?.let(::add)
            }
        }

    private fun callFromObject(value: JSONObject): AiToolCall? {
        val function = value.optJSONObject("function")
        val name = (
            function?.optString("name").orEmpty().ifBlank {
                value.optString("tool").ifBlank { value.optString("name") }
            }
            ).trim().lowercase()
        if (name !in knownToolNames) return null
        val rawArguments: Any? = when {
            function?.has("arguments") == true -> function.opt("arguments")
            value.has("arguments") -> value.opt("arguments")
            value.has("args") -> value.opt("args")
            else -> JSONObject()
        }
        val arguments = when (rawArguments) {
            is JSONObject -> rawArguments.toString()
            is String -> try {
                JSONObject(rawArguments).toString()
            } catch (_: JSONException) {
                return null
            }
            null, JSONObject.NULL -> "{}"
            else -> return null
        }
        val id = value.optString("id").trim().ifEmpty(::nextFallbackId)
        return AiToolCall(id = id, name = name, argumentsJson = arguments)
    }

    private fun parseBracketToolFallbacks(raw: String): BracketParse {
        val calls = mutableListOf<AiToolCall>()
        val kept = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            val match = BRACKET_TOOL.matchEntire(line)
            if (match == null) {
                kept += line
                return@forEach
            }
            val rawName = match.groupValues[1].lowercase()
            val name = TOOL_ALIASES[rawName] ?: rawName
            if (name !in knownToolNames) {
                kept += line
                return@forEach
            }
            val arguments = shorthandArguments(name, match.groupValues[2])
            if (arguments == null) {
                kept += line
            } else {
                calls += AiToolCall(nextFallbackId(), name, arguments)
            }
        }
        return BracketParse(calls, kept.joinToString("\n"))
    }

    private fun shorthandArguments(name: String, rawPayload: String): String? {
        val payload = rawPayload.trim()
        if (payload.startsWith("{")) {
            return try {
                JSONObject(payload).toString()
            } catch (_: JSONException) {
                null
            }
        }
        val scalar = payload
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .trim()
        return when (name) {
            "request_chat_memory_refresh" -> "{}"
            "search_current_chat" -> JSONObject().put("query", scalar).toString()
            "scroll_current_chat" -> if (scalar.isEmpty()) "{}" else null
            "search_chat" -> null // Requires two named values; never guess.
            "list_chats" -> scalar.toIntOrNull()
                ?.takeIf { it in 1..50 }
                ?.let { JSONObject().put("limit", it).toString() }
                ?: if (scalar.isEmpty()) "{}" else null
            "list_sendable_images" ->
                if (scalar.isEmpty()) "{}" else JSONObject().put("query", scalar).toString()
            "send_image" -> scalar
                .takeIf(String::isNotEmpty)
                ?.let { JSONObject().put("asset_id", it).toString() }
            "send_voice_note" -> scalar
                .takeIf(String::isNotEmpty)
                ?.let { JSONObject().put("text", it).toString() }
            "block_contact" -> if (scalar.isEmpty()) "{}" else null
            else -> null
        }
    }

    private fun isSafeReaction(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 16 &&
            value.none { it.isWhitespace() || it == '[' || it == ']' }

    private data class BracketParse(
        val calls: List<AiToolCall>,
        val remainingText: String,
    )

    private data class ParsedBubble(
        val text: String,
        val reply: QuoteDirective?,
    )

    private data class MarkerSplit(
        val visible: String,
        val reply: QuoteDirective?,
    )

    private data class ParsedBubbles(
        val bubbles: List<ParsedBubble>,
        /** A directive with no bubble after it, e.g. a bare `[reply]` alongside a reaction. */
        val unattachedReply: QuoteDirective?,
    )

    private companion object {
        const val NO_REPLY = "[no reply]"
        val REACTION = Regex("""\[react:([^\]\r\n]{1,32})]""")
        // Deliberately forgiving: quotes, the colon, and the snippet itself are all optional, and
        // the common natural-language variants are accepted. Every spelling a model reaches for
        // must become a real WhatsApp quote instead of surviving as literal text in the bubble.
        val QUOTE = Regex(
            """\[\s*(?:reply|antwort|quote|zitat)\b(?:\s+(?:to|auf|on))?\s*(?:[:=]\s*)?([^\]\r\n]*)]""",
            RegexOption.IGNORE_CASE,
        )
        // One model output line maps to one WhatsApp bubble. A blank line is accepted too and is
        // collapsed by the empty-chunk filter. This is intentionally unambiguous: previously the
        // prompt asked for a blank line, but models commonly returned only one line break, causing
        // two requested messages to be delivered as two lines inside a single WhatsApp bubble.
        val BUBBLE_SEPARATOR = Regex("""\r?\n[ \t]*""")
        val BRACKET_TOOL = Regex(
            """^\s*\[(?:tool:)?([a-z][a-z0-9_]*)\s*:\s*(.*?)\]\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val TOOL_ALIASES = mapOf(
            "voice" to "send_voice_note",
            "voice_note" to "send_voice_note",
            "image" to "send_image",
            "refresh_memory" to "request_chat_memory_refresh",
        )
        val fallbackSequence = AtomicLong()

        fun nextFallbackId(): String =
            "fallback-${fallbackSequence.incrementAndGet()}"
    }
}
