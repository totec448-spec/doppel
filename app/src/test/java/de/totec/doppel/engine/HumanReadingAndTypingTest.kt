package de.totec.doppel.engine

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rates the owner can turn: how fast she reads a message and how fast she
 * types the answer. Reading is a floor that a slow model call absorbs entirely;
 * typing is a real delay that runs while the indicator is on.
 */
class HumanReadingAndTypingTest {
    private val timing = HumanTimingPolicy(Random(11))
    private val human = EngineSettingsSnapshot()

    private fun words(count: Int) = (1..count).joinToString(" ") { "wort$it" }

    @Test
    fun `a short message is read in a glance`() {
        repeat(50) {
            val floor = timing.readingFloorMs(words(5), human)
            assertTrue("$floor", floor in 700L..2_000L)
        }
    }

    /** 10 w/s at the short end, ramping to 20 w/s for a wall of text. */
    @Test
    fun `the reading rate accelerates with length`() {
        val short = median { timing.readingFloorMs(words(20), human) }
        val long = median { timing.readingFloorMs(words(200), human) }

        val shortRate = 20_000.0 / short
        val longRate = 200_000.0 / long
        // The fixed "something arrived" beat is most of a short message's floor,
        // so the effective rate sits below the configured one.
        assertTrue("$shortRate w/s", shortRate in 7.0..12.0)
        assertTrue("$longRate w/s", longRate in 17.0..23.0)
        // Ten times the words must not cost ten times the time.
        assertTrue("$short -> $long", long < short * 8)
    }

    @Test
    fun `even a wall of text is read inside the cap`() {
        repeat(50) {
            val floor = timing.readingFloorMs(words(2_000), human)
            assertTrue("$floor", floor <= 20_000L)
        }
    }

    @Test
    fun `a faster reader has a shorter floor`() {
        val slow = median { timing.readingFloorMs(words(60), human) }
        val fast =
            median {
                timing.readingFloorMs(words(60), EngineSettingsSnapshot(readingWordsPerSecond = 20))
            }
        assertTrue("$fast vs $slow", fast < slow)
    }

    @Test
    fun `empty text and the instant preset have no floor at all`() {
        assertEquals(0L, timing.readingFloorMs("   ", human))
        assertEquals(
            0L,
            timing.readingFloorMs(words(50), EngineSettingsSnapshot(replyPreset = ReplyPreset.INSTANT)),
        )
    }

    /** 45 WPM is 225 characters a minute, so a 90-character bubble is about 24 s… of thumbs. */
    @Test
    fun `typing tracks the configured words per minute`() {
        val text = "a".repeat(90)
        val fortyFive =
            median {
                timing.typingDelayMs(text, EngineSettingsSnapshot(typingWordsPerMinute = 45))
            }
        val ninety =
            median {
                timing.typingDelayMs(text, EngineSettingsSnapshot(typingWordsPerMinute = 90))
            }

        // 90 characters at 45 WPM = 24 s of keystrokes plus the lead-in.
        assertTrue("$fortyFive", fortyFive in 22_000L..27_000L)
        // Twice the speed, half the keystroke time; the lead-in does not halve.
        assertTrue("$ninety vs $fortyFive", ninety < fortyFive * 0.65)
    }

    @Test
    fun `the shortest bubble still costs a beat and the longest is capped`() {
        repeat(50) {
            assertTrue(timing.typingDelayMs("ok", human) >= 1_400L)
            assertTrue(timing.typingDelayMs("a".repeat(5_000), human) <= 30_000L)
        }
        assertEquals(
            0L,
            timing.typingDelayMs("hallo", EngineSettingsSnapshot(replyPreset = ReplyPreset.INSTANT)),
        )
    }

    @Test
    fun `word count follows whitespace, not punctuation`() {
        assertEquals(0, HumanTimingPolicy.wordCount("  \n "))
        assertEquals(3, HumanTimingPolicy.wordCount(" hey,  wie   geht's? "))
        assertEquals(4, HumanTimingPolicy.wordCount("eins\nzwei\tdrei vier"))
    }

    @Test
    fun `words per minute convert at five characters a word`() {
        assertEquals(60_000.0 / 225.0, HumanTimingPolicy.msPerCharacter(45), 0.0001)
        // A nonsense rate must not divide by zero.
        assertTrue(HumanTimingPolicy.msPerCharacter(0) > 0.0)
    }

    private fun median(block: () -> Long): Long =
        (1..101).map { block() }.sorted()[50]
}
