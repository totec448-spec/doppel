package de.totec.doppel.ai

/**
 * Catches the persona answering with a message it has already sent.
 *
 * A small model on a long history falls into loops: the same line back, sometimes verbatim,
 * sometimes with one word moved. In a chat that reads worse than a wrong answer, because a human
 * never repeats themselves word for word two messages apart.
 *
 * Everything here is string comparison — no model call — so it is free to run on every turn. It only
 * decides *whether* a retry is worth paying for; the retry itself is the caller's business.
 */
object RepetitionGuard {
    /** How far back a repeat still counts. Beyond this, saying the same thing again is normal. */
    private const val WINDOW = 10

    /**
     * Short messages are exempt. "ja", "hmm", "lol", "keine ahnung" recur constantly in real chat
     * and flagging them would make the damper fire on nearly every turn.
     */
    private const val MIN_LENGTH = 12

    /** Above this share of shared words a rephrasing is still the same message. */
    private const val OVERLAP_LIMIT = 0.8

    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * True when any bubble repeats something recently said in this chat.
     *
     * The caller decides whose messages [recentTexts] holds. Its own produce the classic loop; the
     * contact's produce the other half of the same defect — the persona sending someone their own
     * message back — which reads even worse and has the same cure.
     *
     * @param recentTexts newest last, already free of history labels.
     */
    fun repeats(
        bubbles: List<String>,
        recentTexts: List<String>,
    ): Boolean {
        val recent =
            recentTexts
                .takeLast(WINDOW)
                .map(::normalize)
                .filter { it.length >= MIN_LENGTH }
        if (recent.isEmpty()) return false
        return bubbles.any { bubble ->
            val candidate = normalize(bubble)
            candidate.length >= MIN_LENGTH &&
                recent.any {
                    it == candidate ||
                        sameOpening(it, candidate) ||
                        overlap(it, candidate) >= OVERLAP_LIMIT
                }
        }
    }

    /** Lower case, letters and digits only, single spaces: punctuation and emoji cannot hide a repeat. */
    fun normalize(text: String): String =
        WHITESPACE.replace(NON_WORD.replace(text.lowercase(), " "), " ").trim()

    /**
     * Share of words the two have in common, relative to the longer one.
     *
     * Set-based on purpose: word order is exactly what a model changes when it "rephrases" a line it
     * has already sent, so ignoring order is what makes this catch the near-duplicates.
     */
    private fun overlap(
        first: String,
        second: String,
    ): Double {
        val a = first.split(' ').filter(String::isNotEmpty).toSet()
        val b = second.split(' ').filter(String::isNotEmpty).toSet()
        if (a.size < 4 || b.size < 4) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / maxOf(a.size, b.size).toDouble()
    }

    /** Five identical opening words are a loop even when the model changes the ending. */
    private fun sameOpening(first: String, second: String): Boolean {
        val a = first.split(' ').filter(String::isNotEmpty)
        val b = second.split(' ').filter(String::isNotEmpty)
        return a.size >= PREFIX_WORDS &&
            b.size >= PREFIX_WORDS &&
            a.take(PREFIX_WORDS) == b.take(PREFIX_WORDS)
    }

    private const val PREFIX_WORDS = 5
}
