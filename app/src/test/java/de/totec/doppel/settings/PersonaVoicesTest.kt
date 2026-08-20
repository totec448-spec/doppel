package de.totec.doppel.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every persona sounds like itself. Before this, `voiceConfigJson` was written null everywhere and
 * one global setting spoke for all fifteen of them, so Lina and Sam sent the same voice in the
 * same voice.
 */
class PersonaVoicesTest {
    @Test
    fun `every built-in persona but the custom one has a voice from the catalogue`() {
        val offered = SettingsCatalogs.voices.map { it.name }.toSet()
        SettingsCatalogs.personas
            .filterNot { it.key == "custom" }
            .forEach { persona ->
                val voice = PersonaVoices.baseVoice(persona.key)
                assertTrue("${persona.key} has no voice", voice != null)
                assertTrue("${persona.key} uses $voice", voice in offered)
            }
        // The owner writes this one, so it inherits the global voice until they pick one.
        assertNull(PersonaVoices.baseVoice("custom"))
    }

    /** No two personas share a voice: that is the entire point of giving them their own. */
    @Test
    fun `built-in voices are distinct`() {
        val assigned =
            SettingsCatalogs.personas.mapNotNull { PersonaVoices.baseVoice(it.key) }
        assertEquals(assigned.size, assigned.toSet().size)
    }

    @Test
    fun `Lina speaks with Leda`() {
        assertEquals("Leda", PersonaVoices.baseVoice("female"))
    }

    @Test
    fun `a written voice reads back`() {
        val stored = PersonaVoices.write(null, "Leda")
        assertEquals("Leda", PersonaVoices.read(stored))
    }

    /** The column is an object so later voice settings can join it; a write must not flatten it. */
    @Test
    fun `writing a voice keeps the rest of the blob`() {
        val existing = JSONObject().put("speed", 1.2).toString()
        val stored = PersonaVoices.write(existing, "Puck")
        assertEquals("Puck", PersonaVoices.read(stored))
        assertEquals(1.2, JSONObject(stored!!).getDouble("speed"), 0.001)
    }

    @Test
    fun `clearing a voice leaves no voice behind`() {
        val stored = PersonaVoices.write(PersonaVoices.write(null, "Kore"), null)
        assertNull(PersonaVoices.read(stored))
    }

    /** Malformed JSON is missing data, never a crash: the persona falls back to the global voice. */
    @Test
    fun `unreadable configuration reads as no voice`() {
        assertNull(PersonaVoices.read("not json"))
        assertNull(PersonaVoices.read(""))
        assertNull(PersonaVoices.read(null))
    }

    @Test
    fun `a saved voice beats the bundled one and both beat the global setting`() {
        assertEquals(
            "Sulafat",
            PersonaVoices.effectiveVoice("female", PersonaVoices.write(null, "Sulafat"), "Kore"),
        )
        assertEquals("Leda", PersonaVoices.effectiveVoice("female", null, "Kore"))
        assertEquals("Kore", PersonaVoices.effectiveVoice("custom", null, "Kore"))
    }
}
