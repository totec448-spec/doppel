package de.totec.doppel.domain

/**
 * How media is written into the chat history the model reads back.
 *
 * A bare transcript or image description reads exactly like typed text: nothing
 * in the line says a voice note was involved, and nothing says who sent it. That
 * is why she answered her own photo as if she had received one, and why she
 * never referred back to a voice note she had just spoken. Both directions
 * therefore get an explicit lead-in, phrased the same way on both sides so the
 * two can never be confused.
 */
object MediaHistoryLabels {
    /** Kind-only marker used when there is nothing to say about the content yet. */
    fun marker(kind: MediaKind): String =
        when (kind) {
            MediaKind.IMAGE -> "[Image]"
            MediaKind.AUDIO -> "[Voice note]"
            MediaKind.VIDEO -> "[Video]"
            MediaKind.DOCUMENT -> "[Document]"
            MediaKind.STICKER -> "[Sticker]"
            MediaKind.UNKNOWN -> "[File]"
        }

    fun incoming(kind: MediaKind): String =
        when (kind) {
            MediaKind.IMAGE -> "User sent an image"
            MediaKind.AUDIO -> "User sent a voice note"
            MediaKind.VIDEO -> "User sent a video"
            MediaKind.DOCUMENT -> "User sent a document"
            MediaKind.STICKER -> "User sent a sticker"
            MediaKind.UNKNOWN -> "User sent a file"
        }

    fun outgoing(kind: MediaKind): String =
        when (kind) {
            MediaKind.IMAGE -> "You sent an image"
            MediaKind.AUDIO -> "You sent a voice note"
            MediaKind.VIDEO -> "You sent a video"
            MediaKind.DOCUMENT -> "You sent a document"
            MediaKind.STICKER -> "You sent a sticker"
            MediaKind.UNKNOWN -> "You sent a file"
        }

    fun incomingLine(
        kind: MediaKind,
        detail: String?,
    ): String = line(incoming(kind), kind, detail)

    fun outgoingLine(
        kind: MediaKind,
        detail: String?,
    ): String = line(outgoing(kind), kind, detail)

    /**
     * Media kind of an outbound attachment, which only carries a MIME type.
     * Voice notes are decided by the caller — a PTT and a music file share
     * `audio/…` but must not read the same way.
     */
    fun kindOfMime(mimeType: String): MediaKind =
        when (mimeType.substringBefore(';').trim().substringBefore('/').lowercase()) {
            "image" -> MediaKind.IMAGE
            "audio" -> MediaKind.AUDIO
            "video" -> MediaKind.VIDEO
            else -> MediaKind.UNKNOWN
        }

    private fun line(
        prefix: String,
        kind: MediaKind,
        detail: String?,
    ): String {
        // The prefix already names the kind, so a leading "[Image]" from an
        // unanalysed clip would only repeat it back at the model.
        val body =
            detail
                .orEmpty()
                .trim()
                .removePrefix(marker(kind))
                .trim()
        return if (body.isEmpty()) prefix else "$prefix: $body"
    }
}
