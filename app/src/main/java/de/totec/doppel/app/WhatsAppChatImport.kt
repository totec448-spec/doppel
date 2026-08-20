package de.totec.doppel.app

import java.security.MessageDigest
import java.util.Locale

/**
 * Parsed representation of one line from WhatsApp's plain-text chat export.
 *
 * The export does not encode bubble colour or an account id. Import runs on the linked bot phone,
 * so the local WhatsApp account is the outbound side. The parser resolves that account from live
 * account/persona metadata and, for direct chats, from the participant named by the export file.
 */
internal data class ImportedWhatsAppMessage(
    val providerMessageId: String,
    val exportedTimestamp: String,
    val sender: String,
    val body: String,
    val sentByBot: Boolean,
)

internal object WhatsAppChatImportParser {
    const val MAX_SOURCE_CHARACTERS = 500_000
    const val MAX_MESSAGES = 5_000

    private val bracketedLine = Regex("^\\u200e?\\[([^]]+)]\\s*([^:]{1,160}):\\s?(.*)$")
    private val dashedLine = Regex("^\\u200e?(.{6,80}?)\\s+-\\s+([^:]{1,160}):\\s?(.*)$")
    private val bracketedSystemLine = Regex("^\\u200e?\\[([^]]+)]\\s*.+$")
    private val dashedSystemLine = Regex("^\\u200e?(.{6,80}?)\\s+-\\s+.+$")

    fun parse(source: String, botSender: String): List<ImportedWhatsAppMessage> =
        parseResolved(source) { senders ->
            val normalizedBotSender = normalizeSender(botSender)
            require(normalizedBotSender.isNotEmpty()) { "Bot account name is required" }
            senders.firstOrNull { normalizeSender(it) == normalizedBotSender }
                ?: error("The bot account name does not occur as a sender in this export")
        }

    fun parseAutomatically(
        source: String,
        botAccountCandidates: Collection<String>,
        remoteParticipantCandidates: Collection<String>,
    ): List<ImportedWhatsAppMessage> =
        parseResolved(source) { senders ->
            val byNormalized = senders.associateBy(::normalizeSender)
            botAccountCandidates
                .asSequence()
                .map(::normalizeSender)
                .mapNotNull(byNormalized::get)
                .firstOrNull()
                ?: run {
                    val remotes = remoteParticipantCandidates.mapTo(HashSet(), ::normalizeSender)
                    senders.filterNot { normalizeSender(it) in remotes }.singleOrNull()
                }
                // A one-sided export is still useful. Since the export was created on the bot
                // phone, its sole sender is the only possible local side.
                ?: senders.singleOrNull()
                ?: error(
                    "Could not identify the bot side of this export. Reconnect WhatsApp and retry.",
                )
        }

    private fun parseResolved(
        source: String,
        resolveBotSender: (List<String>) -> String,
    ): List<ImportedWhatsAppMessage> {
        val boundedSource =
            if (source.length <= MAX_SOURCE_CHARACTERS) {
                source
            } else {
                // A character cut can start in the middle of a physical message line. Drop that
                // fragment so it cannot be mistaken for a continuation of the first full row.
                source.takeLast(MAX_SOURCE_CHARACTERS).substringAfter('\n', missingDelimiterValue = "")
            }
        val draft = ArrayList<DraftMessage>()
        boundedSource.lineSequence().forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")
            val match = bracketedLine.matchEntire(line) ?: dashedLine.matchEntire(line)
            if (match != null) {
                draft +=
                    DraftMessage(
                        timestamp = match.groupValues[1].trim(),
                        sender = match.groupValues[2].trim(),
                        body = StringBuilder(match.groupValues[3]),
                    )
            } else if (
                draft.isNotEmpty() &&
                    !isTimestampedSystemLine(line)
            ) {
                // WhatsApp keeps line breaks inside a message as physical continuation lines.
                // Timestamped system events have the same prefix but no sender colon; they are
                // deliberately skipped instead of being glued to the previous human message.
                draft.last().body.append('\n').append(line)
            }
        }
        require(draft.isNotEmpty()) { "No WhatsApp messages were found in this text file" }

        val retained = draft.takeLast(MAX_MESSAGES)
        val botSender = resolveBotSender(retained.map(DraftMessage::sender).distinct())
        val normalizedBotSender = normalizeSender(botSender)

        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(boundedSource.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
                .take(20)
        var botMessages = 0
        val parsed =
            retained.mapIndexed { index, message ->
                val sentByBot = normalizeSender(message.sender) == normalizedBotSender
                if (sentByBot) botMessages += 1
                ImportedWhatsAppMessage(
                    providerMessageId = "import:$digest:$index",
                    exportedTimestamp = message.timestamp,
                    sender = message.sender,
                    body = message.body.toString().trimEnd(),
                    sentByBot = sentByBot,
                )
            }
        check(botMessages > 0) { "The resolved bot side has no messages in this export" }
        return parsed
    }

    private fun normalizeSender(value: String): String =
        value
            .filterNot { it == '\u200e' || it == '\u200f' || it == '\u202a' || it == '\u202c' }
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

    private fun isTimestampedSystemLine(line: String): Boolean {
        val timestamp =
            bracketedSystemLine.matchEntire(line)?.groupValues?.get(1)
                ?: dashedSystemLine.matchEntire(line)?.groupValues?.get(1)
                ?: return false
        return timestamp.any(Char::isDigit) &&
            ':' in timestamp &&
            timestamp.any { it == '/' || it == '.' || it == '-' }
    }

    private data class DraftMessage(
        val timestamp: String,
        val sender: String,
        val body: StringBuilder,
    )
}
