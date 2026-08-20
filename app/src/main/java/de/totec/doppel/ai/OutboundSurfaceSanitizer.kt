package de.totec.doppel.ai

/** One deterministic last-mile contract for text, media captions and voice fallbacks. */
object OutboundSurfaceSanitizer {
    fun sanitize(text: String): String {
        val withoutReasoning = ReasoningLeakGuard.strip(text).text
        return SpeechMarkup
            .stripExpressionTags(HistoryLabelGuard.stripLeadingLabel(withoutReasoning))
            .trim()
    }
}
