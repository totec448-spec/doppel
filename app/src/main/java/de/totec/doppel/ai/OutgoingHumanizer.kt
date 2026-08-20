package de.totec.doppel.ai

import java.security.MessageDigest

/**
 * The deterministic de-AI pass every outgoing text bubble goes through.
 *
 * The prompt already forbids quotation marks, decorative emoji and tidy prose. A small model
 * ignores those rules often enough that the chat still reads as generated: an emoji closing nearly
 * every line, quotes around every joke, four evenly built sentences in a row. This runs after the
 * model and enforces the three loudest tells mechanically.
 *
 * Crucially it runs *before* the bubble is handed to the transport, so the text that goes to
 * WhatsApp is byte-identical to the text written into the durable history. The model therefore
 * never sees its own emoji-laden draft when it reads the chat back on the next turn — it sees the
 * cleaned message and keeps writing in that direction. Sanitising at send time only would leave
 * the unedited draft in the history and teach the model the opposite of what the prompt asks for.
 *
 * Everything here is pure and seeded: the same bubble with the same seed always yields the same
 * text, so a retried or replayed send can never produce a second, slightly different message.
 *
 * This is not [BotEngine]'s self-edit. That one sends a typo and *corrects* it a moment later, and
 * the history keeps the clean version; it models a visible slip. The typing texture here is
 * permanent and deliberately lands in the history, because it is what the persona's own writing is
 * supposed to look like.
 */
object OutgoingHumanizer {
    /**
     * How much of the pass a persona wants.
     *
     * Not every persona is a teenager on a phone: the `assistant` preset exists for testing and
     * explicitly promises normal spelling and no acted typos, and a formal persona may legitimately
     * need quotation marks. Roughening those would be a bug, not realism.
     */
    data class Profile(
        val stripQuotes: Boolean,
        val capEmoji: Boolean,
        val roughenTyping: Boolean,
    ) {
        companion object {
            val FULL = Profile(stripQuotes = true, capEmoji = true, roughenTyping = true)
            val TIDY = Profile(stripQuotes = false, capEmoji = true, roughenTyping = false)
            val OFF = Profile(stripQuotes = false, capEmoji = false, roughenTyping = false)

            fun forPersona(personaKey: String): Profile =
                when (personaKey.lowercase()) {
                    // A pure tool mode. It may have to reproduce a string verbatim, quotes and all.
                    "assistant" -> OFF
                    "formell", "coach" -> TIDY
                    else -> FULL
                }
        }
    }

    data class Outcome(
        val bubbles: List<String>,
        /** Short slugs of the rules that changed something, for the activity log. */
        val rules: Set<String>,
    ) {
        val changed: Boolean get() = rules.isNotEmpty()
    }

    const val RULE_QUOTES = "quotes"
    const val RULE_EMOJI = "emoji"
    const val RULE_DASH = "dash"
    const val RULE_TEXTURE = "texture"

    /**
     * Messages looked at when deciding whether an emoji is still affordable. Five is the frequency
     * the prompt states, so the code enforces exactly what the model was asked for.
     */
    private const val EMOJI_WINDOW = 5

    /**
     * @param recentAssistantTexts the persona's own last messages, newest last, already free of
     *   history labels. Only used to decide the emoji budget.
     * @param seed stable per turn (the turn id); the bubble index is mixed in, so two bubbles of
     *   one turn get independent decisions while a replay of the same turn repeats them exactly.
     */
    fun humanize(
        bubbles: List<String>,
        recentAssistantTexts: List<String> = emptyList(),
        profile: Profile = Profile.FULL,
        seed: String = "",
    ): Outcome {
        if (bubbles.isEmpty() || profile == Profile.OFF) return Outcome(bubbles, emptySet())
        val rules = mutableSetOf<String>()
        // One budget for the whole turn: a four-bubble reply must not be allowed four emoji just
        // because each bubble looked at the same history in isolation.
        var emojiBudget =
            if (!profile.capEmoji) {
                Int.MAX_VALUE
            } else if (recentAssistantTexts.takeLast(EMOJI_WINDOW).any(::containsEmoji)) {
                0
            } else {
                1
            }

        val output =
            bubbles.mapIndexed { index, original ->
                var text = original
                if (profile.stripQuotes) {
                    text = text.withoutQuotationMarks().alsoMark(text, rules, RULE_QUOTES)
                }
                text = text.withoutLongDashes().alsoMark(text, rules, RULE_DASH)
                if (profile.capEmoji && !isEmojiOnly(text)) {
                    val capped = text.withEmojiBudget(emojiBudget)
                    if (capped != text) rules += RULE_EMOJI
                    emojiBudget = (emojiBudget - countEmoji(capped)).coerceAtLeast(0)
                    text = capped
                } else if (profile.capEmoji) {
                    // An emoji-only bubble is a normal human message and stays untouched, but it
                    // still spends the budget for the rest of the turn.
                    emojiBudget = 0
                }
                if (profile.roughenTyping) {
                    text = text.withTypingTexture("$seed:$index").alsoMark(text, rules, RULE_TEXTURE)
                }
                // No rule may ever delete a message. An emptied bubble means a rule overreached,
                // and sending nothing is worse than sending the model's own wording.
                text.trim().ifEmpty { original }
            }
        return Outcome(output, rules)
    }

    private fun String.alsoMark(
        before: String,
        rules: MutableSet<String>,
        rule: String,
    ): String {
        if (this != before) rules += rule
        return this
    }

    // ---------------------------------------------------------------- quotation marks

    /**
     * Every double-quote form, straight and typographic, German and French.
     *
     * Single quotes are deliberately absent: `'` and `’` are apostrophes far more often than
     * quotes in German chat ("geht's", "hab's"), and stripping those would corrupt ordinary words.
     */
    private val DOUBLE_QUOTES =
        charArrayOf(
            '"', '“', '”', '„', '‟',
            '«', '»', '‹', '›',
            '″', '‶', '〝', '〞',
        ).toSet()

    private val REDUNDANT_SPACE = Regex("""[ \t]{2,}""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""[ \t]+([,.!?;:])""")

    private fun String.withoutQuotationMarks(): String {
        if (none { it in DOUBLE_QUOTES }) return this
        return filterNot { it in DOUBLE_QUOTES }.tidySpacing()
    }

    private fun String.tidySpacing(): String =
        replace(REDUNDANT_SPACE, " ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .lineSequence()
            .joinToString("\n") { it.trim() }
            .trim()

    // ---------------------------------------------------------------- dashes

    private val SPACED_LONG_DASH = Regex("""\s+[—–]\s+""")

    /** Nobody types an em dash on a phone keyboard; it is pure model punctuation. */
    private fun String.withoutLongDashes(): String {
        if (none { it == '—' || it == '–' }) return this
        return replace(SPACED_LONG_DASH, ", ")
            .replace('—', '-')
            .replace('–', '-')
            .tidySpacing()
    }

    // ---------------------------------------------------------------- emoji

    /**
     * Deliberately range-based rather than a full Unicode emoji property lookup, which the Android
     * runtime does not offer. The blocks below cover everything a chat model reaches for; anything
     * outside them is treated as ordinary text, which is the safe direction to be wrong in.
     */
    private fun isEmojiCodePoint(codePoint: Int): Boolean =
        when (codePoint) {
            in 0x1F000..0x1FAFF -> true
            in 0x2600..0x27BF -> true
            in 0x2B00..0x2BFF -> true
            0x203C, 0x2049, 0x2122, 0x2139, 0x3030, 0x303D, 0x3297, 0x3299 -> true
            else -> false
        }

    /** Joiners, variation selectors, skin tones and the keycap mark: never emoji on their own. */
    private fun isEmojiModifier(codePoint: Int): Boolean =
        codePoint == 0x200D ||
            codePoint == 0xFE0E ||
            codePoint == 0xFE0F ||
            codePoint == 0x20E3 ||
            codePoint in 0x1F3FB..0x1F3FF

    private fun containsEmoji(text: String): Boolean = countEmoji(text) > 0

    private fun countEmoji(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (isEmojiCodePoint(codePoint)) count += 1
            index += Character.charCount(codePoint)
        }
        return count
    }

    private fun isEmojiOnly(text: String): Boolean {
        val stripped = text.filterCodePoints { !isEmojiCodePoint(it) && !isEmojiModifier(it) }
        return countEmoji(text) > 0 && stripped.isBlank()
    }

    private inline fun String.filterCodePoints(keep: (Int) -> Boolean): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            if (keep(codePoint)) builder.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    /**
     * Applies the frequency rule: a line never ends on an emoji, and at most [budget] survive.
     *
     * The trailing strip runs unconditionally, before the budget is spent. A line-final emoji is
     * the single loudest tell in the field screenshots — it is what makes every message look
     * captioned — so it never survives, not even when the budget would have allowed one.
     */
    private fun String.withEmojiBudget(budget: Int): String {
        if (countEmoji(this) == 0) return this
        var text = withoutTrailingEmoji()
        val remaining = countEmoji(text)
        if (remaining > budget) {
            var allowance = budget
            text = text.filterCodePoints { codePoint ->
                when {
                    isEmojiCodePoint(codePoint) -> (allowance-- > 0)
                    // A modifier without its emoji renders as a stray box.
                    isEmojiModifier(codePoint) -> false
                    else -> true
                }
            }
        }
        return text.tidySpacing()
    }

    private fun String.withoutTrailingEmoji(): String {
        var end = length
        var removed = false
        while (end > 0) {
            while (end > 0 && this[end - 1].isWhitespace()) end -= 1
            if (end == 0) break
            val codePoint = codePointBefore(end)
            if (!isEmojiCodePoint(codePoint) && !isEmojiModifier(codePoint)) break
            if (isEmojiCodePoint(codePoint)) removed = true
            end -= Character.charCount(codePoint)
        }
        return if (removed) substring(0, end).trimEnd() else this
    }

    // ---------------------------------------------------------------- typing texture

    private val TEXTURE_SAFE = Regex("""https?://|www\.|@\w""", RegexOption.IGNORE_CASE)
    private val CONTRACTION_APOSTROPHE = Regex("""(\p{L})['’](\p{L})""")
    private val LONG_WORD = Regex("""\p{L}{6,}""")

    /**
     * The small imperfections a thumb leaves: no full stop at the end, a lower-case start, the
     * apostrophe nobody reaches for, and now and then a genuine slip.
     *
     * Probabilities are low on purpose. This has to read as someone typing fast, not as someone
     * who cannot spell, and it is permanent — every one of these lands in the durable history.
     */
    private fun String.withTypingTexture(seed: String): String {
        if (length < 3 || TEXTURE_SAFE.containsMatchIn(this)) return this
        var text = this

        // A closing full stop on a chat message is the most formal thing in it. Question and
        // exclamation marks carry tone and stay; an ellipsis is a deliberate pause and stays too.
        if (text.endsWith(".") && !text.endsWith("..") && stableFraction("$seed:stop") < 0.8) {
            text = text.dropLast(1).trimEnd()
        }
        if (stableFraction("$seed:apostrophe") < 0.55) {
            text = CONTRACTION_APOSTROPHE.replace(text, "$1$2")
        }
        text.firstOrNull()?.let { first ->
            if (first.isUpperCase() && stableFraction("$seed:lower") < 0.55) {
                text = first.lowercaseChar() + text.substring(1)
            }
        }
        if (stableFraction("$seed:slip") < 0.12) {
            text = text.withSwappedLetters("$seed:slip-word")
        }
        return text
    }

    /** Swaps two adjacent inner letters of one long word: `nicht` stays readable as `nihct`. */
    private fun String.withSwappedLetters(seed: String): String {
        val words = LONG_WORD.findAll(this).toList()
        if (words.isEmpty()) return this
        val word = words[pick("$seed:word", words.size)]
        val value = word.value
        // Never the first or last letter: those are the ones a reader uses to recognise the word.
        val at = 1 + pick("$seed:offset", value.length - 3)
        val swapped =
            value.substring(0, at) + value[at + 1] + value[at] + value.substring(at + 2)
        return replaceRange(word.range, swapped)
    }

    // ---------------------------------------------------------------- deterministic randomness

    private fun pick(seed: String, count: Int): Int =
        if (count <= 1) 0 else (stableFraction(seed) * count).toInt().coerceIn(0, count - 1)

    /** Stable 0.0..1.0 derived from [seed]; the same seed always yields the same value. */
    private fun stableFraction(seed: String): Double {
        val bytes =
            MessageDigest
                .getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
        var accumulator = 0L
        for (index in 0 until 6) {
            accumulator = (accumulator shl 8) or (bytes[index].toLong() and 0xffL)
        }
        return accumulator.toDouble() / (1L shl 48).toDouble()
    }
}
