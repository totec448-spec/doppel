package de.totec.doppel.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaHistoryLabelsTest {
    @Test
    fun `incoming media is named before its content`() {
        assertEquals(
            "User sent a voice note: hey, wo bist du",
            MediaHistoryLabels.incomingLine(MediaKind.AUDIO, "hey, wo bist du"),
        )
        assertEquals(
            "User sent an image: ein Hund am Strand",
            MediaHistoryLabels.incomingLine(MediaKind.IMAGE, "ein Hund am Strand"),
        )
    }

    @Test
    fun `outgoing media is named as her own`() {
        assertEquals(
            "You sent a voice note: bin gleich da",
            MediaHistoryLabels.outgoingLine(MediaKind.AUDIO, "bin gleich da"),
        )
        assertEquals(
            "You sent a video",
            MediaHistoryLabels.outgoingLine(MediaKind.VIDEO, "   "),
        )
    }

    @Test
    fun `the kind marker is not repeated behind the prefix`() {
        assertEquals(
            "User sent an image",
            MediaHistoryLabels.incomingLine(MediaKind.IMAGE, MediaHistoryLabels.marker(MediaKind.IMAGE)),
        )
        assertEquals(
            "User sent a video: (Analyse nicht verfügbar)",
            MediaHistoryLabels.incomingLine(MediaKind.VIDEO, "[Video] (Analyse nicht verfügbar)"),
        )
    }

    @Test
    fun `outbound attachments are classified by mime type`() {
        assertEquals(MediaKind.IMAGE, MediaHistoryLabels.kindOfMime("image/jpeg"))
        assertEquals(MediaKind.AUDIO, MediaHistoryLabels.kindOfMime("audio/ogg; codecs=opus"))
        assertEquals(MediaKind.UNKNOWN, MediaHistoryLabels.kindOfMime("application/pdf"))
    }
}
