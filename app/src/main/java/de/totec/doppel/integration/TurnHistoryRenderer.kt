package de.totec.doppel.integration

import de.totec.doppel.ai.ChatRole
import de.totec.doppel.ai.HistoryMessage
import de.totec.doppel.data.operatorContextForModel
import de.totec.doppel.engine.StoredTurnMessage
import java.time.Instant
import java.time.ZoneId

/**
 * Turns stored chat rows into the history block the model sees.
 *
 * The shape matters more than it looks: assistant turns are replayed *in the assistant role*, so
 * whatever they look like is, to the model, the house style. Stamping `[2026-08-02T14:03] ` onto
 * its own past replies taught it to open every new reply the same way — the WhatsApp-looking
 * timestamp blocks in the output. Incoming messages keep the timestamp, because that context is
 * genuinely useful, and the output contract names it as transport metadata.
 *
 * Kept out of the turn runner so this can be exercised without an Android context behind it.
 */
internal object TurnHistoryRenderer {
    private const val MAX_SPEAKER_CHARACTERS = 100

    /** Length of `yyyy-MM-ddTHH:mm` — minute precision, no seconds. */
    private const val LOCAL_TIME_CHARACTERS = 16

    fun render(
        history: List<StoredTurnMessage>,
        isGroup: Boolean,
        timezone: ZoneId,
        includeTimestamps: Boolean,
    ): List<HistoryMessage> =
        history.mapNotNull { message ->
            val role =
                when {
                    // An operator note is replayed as ordinary history at its original position.
                    // Making it another system message would let DeepSeek hoist it ahead of the
                    // cache-stable prefix; its marker carries the meaning where it stands.
                    message.operatorInjection -> ChatRole.USER
                    message.role.equals("user", ignoreCase = true) -> ChatRole.USER
                    message.role.equals("assistant", ignoreCase = true) -> ChatRole.ASSISTANT
                    else -> null
                }
            if (role == null || message.text.isBlank()) return@mapNotNull null
            if (message.operatorInjection) {
                // Rows written by older builds carry an older wording; they are re-rendered in the
                // current one, so a chat never shows the model two different framings of the same
                // thing.
                return@mapNotNull HistoryMessage(role, operatorContextForModel(message.text))
            }
            // Never prefix the model's own turns: that is the format it copies.
            if (role == ChatRole.ASSISTANT) return@mapNotNull HistoryMessage(role, message.text)

            val speaker =
                message.senderName
                    ?.takeIf { isGroup }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(MAX_SPEAKER_CHARACTERS)
            val prefix =
                when {
                    speaker != null && includeTimestamps ->
                        "[${localTime(message.timestampMs, timezone)}] $speaker: "
                    speaker != null -> "$speaker: "
                    includeTimestamps -> "[${localTime(message.timestampMs, timezone)}] "
                    else -> ""
                }
            // WhatsApp draws the quote above the bubble, so a reply that only carries its own
            // words reads as a non sequitur once the referenced message has scrolled away.
            val quote =
                message.quotedText
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { "[replying to: $it] " }
                    .orEmpty()
            HistoryMessage(role, prefix + quote + message.text)
        }

    private fun localTime(
        timestampMs: Long,
        timezone: ZoneId,
    ): String =
        Instant
            .ofEpochMilli(timestampMs)
            .atZone(timezone)
            .toLocalDateTime()
            .toString()
            .take(LOCAL_TIME_CHARACTERS)
}
