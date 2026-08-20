package de.totec.doppel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionGuardTest {

    @Test
    fun `same five word opening is treated as a loop`() {
        assertTrue(
            RepetitionGuard.repeats(
                bubbles = listOf("ich glaube wir sollten das heute wirklich anders machen"),
                recentTexts = listOf("Ich glaube wir sollten das morgen zusammen klären"),
            ),
        )
    }

    @Test
    fun `guard checks ten recent assistant messages`() {
        val history =
            listOf("this old line is outside the ten message window") +
                listOf("target repeated assistant sentence lives at boundary") +
                List(9) { index -> "unique recent assistant sentence number $index here" }
        assertTrue(
            RepetitionGuard.repeats(
                bubbles = listOf("target repeated assistant sentence lives at boundary"),
                recentTexts = history,
            ),
        )
    }
    @Test
    fun `normalization collapses punctuation emoji and repeated whitespace`() {
        assertEquals("das ist echt gleich", RepetitionGuard.normalize("Das...  ist 😅   echt gleich"))
    }

    @Test
    fun `punctuation cannot hide an exact recent repeat`() {
        assertTrue(
            RepetitionGuard.repeats(
                bubbles = listOf("das ist wirklich genau derselbe satz"),
                recentTexts = listOf("Das ist wirklich, genau derselbe Satz! 😅"),
            ),
        )
    }
}
