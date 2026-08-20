package de.totec.doppel.ai

/**
 * Gemini TTS steering markup.
 *
 * Tags like `[laughs]` or `[whispers]` shape how a voice note is *spoken*; they are never audible.
 * They must therefore disappear from everything that is read as text: the chat-history row the model
 * sees on the next turn (`You sent a voice note: ...`), the text fallback when synthesis fails, and
 * any visible bubble where a model copied the pattern by mistake. Leaving them in produced real
 * WhatsApp messages reading `You sent a voice note: [moans] mhh...`.
 *
 * The shape is deliberately narrow — a short lowercase phrase in brackets — so ordinary chat text
 * survives untouched. The protocol markers (`[reply:...]`, `[react:...]`) are consumed earlier and
 * carry a colon, so they never reach this.
 */
object SpeechMarkup {
    /**
     * One to three short words in brackets: `[laughs]`, `[short pause]`, `[deep sigh]`.
     *
     * The protocol markers are excluded by name so this stays safe to run on text where a marker is
     * deliberately kept literal (a disabled feature must not be silently swallowed here).
     */
    private val EXPRESSION_TAG = Regex(
        """\[(?!(?:no[ \t]+reply|reply|react|quote|antwort|zitat)\b)""" +
            """[a-zA-Z]+(?:[ \t]+[a-zA-Z]+){0,2}]""",
        RegexOption.IGNORE_CASE,
    )

    private val REDUNDANT_SPACE = Regex("""[ \t]{2,}""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""[ \t]+([,.!?;:])""")

    /** Returns [text] without expression tags, with the whitespace they leave behind cleaned up. */
    fun stripExpressionTags(text: String): String =
        EXPRESSION_TAG
            .replace(text, " ")
            .replace(REDUNDANT_SPACE, " ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .lineSequence()
            .joinToString("\n") { it.trim() }
            .trim()
}

/**
 * The `You sent: ...` / `User sent: ...` prefixes the chat history is rendered with.
 *
 * They exist so the model can tell apart who sent what, but models regularly mirror the pattern back
 * into an actual message. Stripping the prefix deterministically is the only reliable guard; a
 * prompt rule and the verifier both catch it only sometimes.
 */
object HistoryLabelGuard {
    /**
     * Anchored to the start of a *line*, not of the bubble.
     *
     * A model that copies the transcript rarely copies one row: it writes the incoming line and its
     * own answer underneath, and only the first of the two was ever cleaned. A real WhatsApp message
     * went out reading `Was?` / `You sent: siehste zu = …` for exactly that reason. The width allows
     * for the `(quoting "…")` reference the labels now carry inline.
     */
    private val LEADING_LABEL = Regex(
        """^[ \t]*(?:you\s+(?:sent|replied\s+to|reacted(?:\s+\w+)?)|user\s+sent|in\s+reply\s+to)\b""" +
            """[^:\r\n]{0,80}:[ \t]*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /**
     * Removes history labels from a bubble the model is about to send, on every line that starts
     * with one. Returns the text unchanged when there is none, and an empty string when the labels
     * were the entire bubble.
     */
    fun stripLeadingLabel(text: String): String =
        if (LEADING_LABEL.containsMatchIn(text)) {
            LEADING_LABEL.replace(text, "")
                .lineSequence()
                .joinToString("\n") { it.trimEnd() }
                .trim()
        } else {
            text
        }
}

/**
 * A reasoning model's internal monologue, when it lands in the answer instead of beside it.
 *
 * Normally it does not: OpenRouter returns the chain of thought in `message.reasoning`, a field this
 * app never reads, so it cannot reach WhatsApp. But a field report showed an English monologue glued
 * to the front of a real German reply, which means some provider or some model wrote it straight into
 * `content` — and once it is in `content` nothing downstream can tell it apart from the message.
 *
 * Delimited blocks are always removed. A deliberately narrow set of leading English meta-sentences
 * is removed as well, but only when a real answer remains behind it. The verb allow-list is what
 * keeps normal messages such as "I need to sleep" untouched.
 */
object ReasoningLeakGuard {
    /**
     * Every delimiter a reasoning model is known to emit, closed or left open.
     *
     * An unclosed opener is the more dangerous case — the block then runs to the end of the output
     * and there is no reply behind it — so it is matched explicitly rather than ignored.
     */
    private val THINK_BLOCK = Regex(
        """<\s*(think|thinking|thought|reasoning|reflection|scratchpad|analysis)\s*>""" +
            """[\s\S]*?(?:<\s*/\s*\1\s*>|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** The bare closing tag of a block whose opener the provider already consumed. */
    private val ORPHAN_CLOSE = Regex(
        """^\s*<\s*/\s*(?:think|thinking|thought|reasoning|reflection|scratchpad|analysis)\s*>""",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_META_SENTENCE = Regex(
        """^\s*(?:(?:let\s+me|let's)\s+(?:think|analy[sz]e|reason|plan|craft|formulate|respond|answer)\b|""" +
            """(?:i|we)\s+(?:(?:need|must|should|will)(?:\s+to)?|have\s+to)\s+(?:answer|respond|reply|provide|avoid|consider|decide|follow|address|write|craft|ensure)\b|""" +
            """the\s+user\s+(?:asks|asked|wants|needs|provided|says|said|is\s+asking)\b)""" +
            """[^\r\n]{0,600}?(?:[.!?](?:[ \t]+|$)|\r?\n+)""",
        RegexOption.IGNORE_CASE,
    )

    data class Result(
        val text: String,
        val stripped: Boolean,
        val strippedCharacters: Int = 0,
    )

    fun strip(raw: String): Result {
        val rawTrimmed = raw.trim()
        val withoutBlocks =
            if (raw.contains('<')) THINK_BLOCK.replace(raw, " ") else raw
        var cleaned = ORPHAN_CLOSE.replace(withoutBlocks, " ").trim()
        val blocksWereStripped = cleaned != rawTrimmed

        // Strip a bounded run of high-confidence meta sentences. If the whole completion is only
        // plain prose, keep it: without a real answer behind it the heuristic cannot prove intent.
        var candidate = cleaned
        repeat(MAX_LEADING_META_SENTENCES) {
            val match = LEADING_META_SENTENCE.find(candidate) ?: return@repeat
            if (match.range.first != 0) return@repeat
            val remainder = candidate.substring(match.range.last + 1).trimStart()
            if (remainder.isBlank() && !blocksWereStripped) return@repeat
            candidate = remainder
        }
        cleaned = candidate.trim()
        val stripped = cleaned != raw.trim()
        // A leak that was the entire completion leaves nothing behind. Returning the empty string
        // would look like a silent model; the caller handles an empty answer explicitly, and it is
        // still better than sending the monologue.
        return Result(
            text = if (stripped) cleaned else raw,
            stripped = stripped,
            strippedCharacters = (rawTrimmed.length - cleaned.length).coerceAtLeast(0),
        )
    }

    private const val MAX_LEADING_META_SENTENCES = 8
}
