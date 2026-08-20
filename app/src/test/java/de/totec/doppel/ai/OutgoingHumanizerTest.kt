package de.totec.doppel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingHumanizerTest {
    private fun clean(
        vararg bubbles: String,
        recent: List<String> = emptyList(),
        profile: OutgoingHumanizer.Profile = OutgoingHumanizer.Profile.FULL,
    ) = OutgoingHumanizer.humanize(
        bubbles = bubbles.toList(),
        recentAssistantTexts = recent,
        profile = profile,
        seed = "test-seed",
    )

    @Test
    fun `a line never ends on an emoji`() {
        // The loudest tell in the field screenshots: nearly every message captioned with one.
        assertEquals(listOf("total mein ding"), clean("total mein ding 😍").bubbles)
        assertEquals(listOf("langweilig irgendwie"), clean("langweilig irgendwie 🌙  ").bubbles)
        assertEquals(listOf("und du so?"), clean("und du so? 🎧").bubbles)
    }

    @Test
    fun `one emoji survives inside a line and the rest do not`() {
        val result = clean("hab 😍 das lied 🎧 dreimal gehört 🔥")
        assertEquals(listOf("hab 😍 das lied dreimal gehört"), result.bubbles)
    }

    @Test
    fun `the emoji budget is spent across the whole turn, not per bubble`() {
        val result = clean("erst 😍 das", "dann 🎧 das")
        assertEquals(listOf("erst 😍 das", "dann das"), result.bubbles)
    }

    @Test
    fun `a recent emoji drops the budget to zero`() {
        val result = clean("hab 😍 das gehört", recent = listOf("war schon ok 🙂"))
        assertEquals(listOf("hab das gehört"), result.bubbles)
    }

    @Test
    fun `an emoji-only bubble is a human message and stays whole`() {
        assertEquals(listOf("😂😂"), clean("😂😂").bubbles)
    }

    @Test
    fun `quotation marks go, apostrophes stay`() {
        val result = clean("\"oh mann, das tut mir leid\"", "sie meinte „echt jetzt“")
        assertEquals(listOf("oh mann, das tut mir leid", "sie meinte echt jetzt"), result.bubbles)
        // German contractions are apostrophes, not quotes: stripping them would corrupt the word.
        assertTrue(clean("wie geht's dir").bubbles.single().contains("geht"))
    }

    @Test
    fun `em dashes become the comma a thumb would type`() {
        assertEquals(
            listOf("war ok, also eigentlich"),
            clean("war ok — also eigentlich").bubbles,
        )
    }

    @Test
    fun `no rule may ever empty a bubble`() {
        // Quote-only content would otherwise vanish; sending the model's wording beats sending air.
        assertEquals(listOf("\"\""), clean("\"\"").bubbles)
    }

    @Test
    fun `urls and mentions are never roughened`() {
        val url = "guck mal https://open.spotify.com/track/abc"
        assertEquals(listOf(url), clean(url).bubbles)
    }

    @Test
    fun `the assistant persona is exempt`() {
        val text = "Die Antwort lautet \"42\" — laut Buch. 🙂"
        assertEquals(
            listOf(text),
            OutgoingHumanizer.humanize(
                bubbles = listOf(text),
                profile = OutgoingHumanizer.Profile.forPersona("assistant"),
                seed = "s",
            ).bubbles,
        )
    }

    @Test
    fun `a formal persona keeps its quotes but loses the decoration`() {
        val result =
            OutgoingHumanizer.humanize(
                bubbles = listOf("Der Termin steht: \"Montag, 9 Uhr\". 🙂"),
                profile = OutgoingHumanizer.Profile.forPersona("formell"),
                seed = "s",
            )
        assertEquals(listOf("Der Termin steht: \"Montag, 9 Uhr\"."), result.bubbles)
    }

    @Test
    fun `the same seed always produces the same text`() {
        val once = clean("Das war heute wirklich anstrengend.")
        val twice = clean("Das war heute wirklich anstrengend.")
        assertEquals(once.bubbles, twice.bubbles)
    }

    @Test
    fun `clean output is left alone`() {
        // Everything the shipping prompt already produces correctly must survive untouched, or the
        // humanizer is fighting the prompt instead of backing it up.
        val untouched =
            listOf(
                "hey, nichts los bei mir",
                "musik läuft halt so",
                "und bei dir?",
                "hä? was meinst du jetzt genau",
                "kein geld für was anderes",
            )
        untouched.forEach { line ->
            val result = clean(line)
            assertEquals(listOf(line), result.bubbles)
            assertFalse("$line was changed by ${result.rules}", result.changed)
        }
    }
}
