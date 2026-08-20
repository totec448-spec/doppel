package de.totec.doppel.engine

import de.totec.doppel.ai.HistoryLabelGuard

/** Stable, model-readable labels for non-plain events in the durable chat stream. */
internal object ChatHistoryLabels {
    fun incomingText(
        text: String,
        quotedText: String? = null,
    ): String = "User sent${quoteReference(quotedText)}: ${text.trim()}"

    /**
     * @param openedConversation this message went out with no incoming message in front of it — an
     *   outreach or a due promise. Unmarked, it is indistinguishable in the transcript from an
     *   answer, and the persona then met the reply to its *own* opener with "why are you writing
     *   me". The marker sits inside the label's own parentheses, so [HistoryLabelGuard] still
     *   strips the whole label if a model ever copies the shape into a real message.
     */
    fun outgoingText(
        text: String,
        replyTo: String? = null,
        openedConversation: Boolean = false,
    ): String =
        if (openedConversation) {
            // An opener answers nothing, so it never carries a quote reference either — which also
            // keeps the label inside the width the guard strips.
            "You sent (you wrote first, they had not messaged): ${text.trim()}"
        } else {
            "You sent${quoteReference(replyTo)}: ${text.trim()}"
        }

    /**
     * Which message a reply points at, folded into the sender's own line.
     *
     * This used to be a line of its own — `You replied to: <the full quoted message>` directly above
     * `You sent: <the answer>`. Every quoted reply therefore showed the model the same shape: the
     * other person's words repeated verbatim, then the answer underneath. It copied it, and started
     * sending the contact's own message back before replying. A quote is a reference, so it now
     * reads as one, on the line it belongs to and cut to [MAX_QUOTE_SNIPPET_CHARS] — which message
     * is meant is the only thing it has to carry, and the message itself is already in the history.
     */
    private fun quoteReference(text: String?): String {
        // The snippet is a reference, not content, so it gives up its own quotes and colons: both
        // would break the label back open for everything downstream that reads up to the first
        // colon — the outbound guard above all.
        // The preview is read back out of the stored history, so it arrives carrying that row's own
        // label. `(quoting "User sent earlier")` names a message nobody sent.
        val snippet =
            text?.let(HistoryLabelGuard::stripLeadingLabel)
                ?.replace(LABEL_BREAKERS, " ")
                ?.replace(WHITESPACE, " ")
                ?.trim()
                ?.takeIf(String::isNotEmpty) ?: return ""
        val cut =
            if (snippet.length <= MAX_QUOTE_SNIPPET_CHARS) {
                snippet
            } else {
                snippet.take(MAX_QUOTE_SNIPPET_CHARS).trimEnd() + "…"
            }
        return " (quoting \"$cut\")"
    }

    fun replyContext(text: String?, messageId: String): String =
        text?.trim()?.takeIf(String::isNotEmpty)?.let { "In reply to: $it" }
            ?: "In reply to message: ${messageId.trim()}"

    /**
     * A reaction, and what it was aimed at.
     *
     * Without [targetText] this is the whole turn the model gets — a bare `User reacted with: 😯`
     * with no referent — and it answers by inventing one. WhatsApp reactions are attached to a
     * specific message, so the history has to say which.
     */
    fun incomingReaction(
        emoji: String,
        targetText: String? = null,
    ): String {
        val target = targetText?.trim()?.takeIf(String::isNotEmpty)
        return if (target == null) {
            "User reacted with: ${emoji.trim()}"
        } else {
            "User reacted with ${emoji.trim()} to: ${target.take(MAX_REACTION_TARGET_CHARS)}"
        }
    }

    fun outgoingReaction(emoji: String): String = "You reacted with: ${emoji.trim()}"

    fun incomingEdit(text: String): String = "User edited a message: ${text.trim()}"

    fun incomingDelete(): String = "User deleted a message."

    /** A call that rang and was not picked up; [video] tells a video call from a voice call. */
    fun incomingMissedCall(video: Boolean): String =
        if (video) {
            "User tried to video call you. The call was not picked up."
        } else {
            "User tried to call you. The call was not picked up."
        }

    /** Enough to recognise which message was meant, short enough to stay cheap in every prompt. */
    const val MAX_REACTION_TARGET_CHARS = 80

    /**
     * Enough to identify the quoted message, short enough that repeating it is not worth imitating
     * and that the whole label stays inside the width `HistoryLabelGuard` strips.
     */
    const val MAX_QUOTE_SNIPPET_CHARS = 48

    private val WHITESPACE = Regex("""\s+""")

    /** Characters that would end the label early for anything parsing it. */
    private val LABEL_BREAKERS = Regex("""["“”:\r\n]""")
}
