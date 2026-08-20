package de.totec.doppel.integration

import de.totec.doppel.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAnalysisInstructionsTest {
    @Test
    fun imageAndVideoAskForMateriallyRicherContextThanAudio() {
        assertTrue(MediaAnalysisInstructions.IMAGE.contains("four to six"))
        assertTrue(MediaAnalysisInstructions.VIDEO.contains("six to ten"))
        assertTrue(MediaAnalysisInstructions.AUDIO.contains("exactly two plain-text fields"))
    }

    @Test
    fun audioRemainsTranscriptionFirst() {
        assertTrue(MediaAnalysisInstructions.AUDIO.contains("word-for-word"))
        assertTrue(MediaAnalysisInstructions.AUDIO.contains("pronunciation"))
        assertFalse(MediaAnalysisInstructions.AUDIO.contains("six to ten"))
    }

    @Test
    fun everyModalityIsToldToAnswerInPlainProse() {
        listOf(
            MediaAnalysisInstructions.IMAGE,
            MediaAnalysisInstructions.AUDIO,
            MediaAnalysisInstructions.VIDEO,
        ).forEach { assertTrue(it.contains("no markdown", ignoreCase = true)) }
        assertTrue(MediaAnalysisInstructions.AUDIO.contains("`Tone:`"))
        assertTrue(MediaAnalysisInstructions.AUDIO.contains("`Transcription:`"))
    }

    /**
     * Asking is not enough — Gemini answers a transcription request with `**Tone:**` regardless, and
     * the result is stored as what the other person said, so the writing model reads and copies it.
     */
    @Test
    fun reportScaffoldingIsRemovedButWhatItLabelledSurvives() {
        val report =
            """
            ## Transcription
            **Tone:** cheerful, slightly rushed

            Transcript:
            * hey bist du noch wach

            *bit of laughter at the end*
            """.trimIndent()

        assertEquals(
            "Tone: cheerful, slightly rushed\n\nTranscription:\nhey bist du noch wach\n\n" +
                "bit of laughter at the end",
            MediaReportMarkup.strip(report),
        )
    }

    @Test
    fun ordinaryProseAndLoneAsterisksAreLeftAlone() {
        val spoken = "sie meinte 2 * 3 und dann nix mehr"

        assertEquals(spoken, MediaReportMarkup.strip(spoken))
    }

    @Test
    fun rawVoiceTranscriptIsAlwaysWrappedInSemanticFields() {
        assertEquals(
            "Tone: unclear\nTranscription:\nhello there",
            MediaReportMarkup.normalize(MediaKind.AUDIO, "hello there"),
        )
        assertEquals(
            "Tone: unclear\nTranscription: hello there",
            MediaReportMarkup.normalize(MediaKind.AUDIO, "Transcript: hello there"),
        )
        assertEquals(
            "hello there",
            MediaReportMarkup.normalize(MediaKind.VIDEO, "hello there"),
        )
    }

}
