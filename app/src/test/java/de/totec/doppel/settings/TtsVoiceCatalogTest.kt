package de.totec.doppel.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8: the picker and the send path used to consult two different voice tables, so a voice could
 * look selected in the UI and never reach the provider. Both now go through this object, which
 * makes "the picker offers it" and "the request accepts it" the same statement.
 */
class TtsVoiceCatalogTest {
    @Test
    fun `each provider offers only its own voices`() {
        assertEquals(
            TtsVoiceCatalog.GOOGLE_VOICES,
            TtsVoiceCatalog.knownVoices(TtsVoiceCatalog.DEFAULT_REMOTE_MODEL),
        )
        assertEquals(TtsVoiceCatalog.GROK_VOICES, TtsVoiceCatalog.knownVoices(TtsVoiceCatalog.GROK_MODEL))
        assertEquals(TtsVoiceCatalog.OPENAI_VOICES, TtsVoiceCatalog.knownVoices("openai/gpt-4o-mini-tts"))
    }

    /** An empty list means "do not constrain" — never "no voices available". */
    @Test
    fun `free-form providers are not constrained`() {
        assertTrue(TtsVoiceCatalog.knownVoices("qwen/qwen-audio-3.0-tts-plus").isEmpty())
        assertTrue(TtsVoiceCatalog.knownVoices(TtsVoiceCatalog.ANDROID_SYSTEM_MODEL).isEmpty())
    }

    @Test
    fun `provider reported voices win over the static table`() {
        val reported = listOf("Nova-1", "Nova-2")

        assertEquals(reported, TtsVoiceCatalog.knownVoices(TtsVoiceCatalog.DEFAULT_REMOTE_MODEL, reported))
        assertEquals("Nova-1", TtsVoiceCatalog.defaultVoice("some/new-tts", reported))
        assertEquals("Nova-2", TtsVoiceCatalog.resolve("some/new-tts", "nova-2", reported))
        assertEquals("Nova-1", TtsVoiceCatalog.resolve("some/new-tts", "Kore", reported))
    }

    @Test
    fun `a compatible voice is never rewritten and keeps the published casing`() {
        assertEquals("Kore", TtsVoiceCatalog.resolve(TtsVoiceCatalog.DEFAULT_REMOTE_MODEL, "kore"))
        assertEquals("alloy", TtsVoiceCatalog.resolve("openai/gpt-4o-mini-tts", "ALLOY"))
        assertEquals("Rex", TtsVoiceCatalog.resolve(TtsVoiceCatalog.GROK_MODEL, "rex"))
    }

    /** The bug this fixes: a Google voice left behind after switching to another provider. */
    @Test
    fun `a foreign voice is replaced by the new providers default`() {
        assertEquals("coral", TtsVoiceCatalog.resolve("openai/gpt-4o-mini-tts", "Kore"))
        assertEquals("Kore", TtsVoiceCatalog.resolve(TtsVoiceCatalog.DEFAULT_REMOTE_MODEL, "alloy"))
        assertEquals(
            TtsVoiceCatalog.QWEN_DEFAULT_VOICE,
            TtsVoiceCatalog.resolve("qwen/qwen-audio-3.0-tts-plus", "Kore"),
        )
    }

    @Test
    fun `switching to Grok keeps the perceived gender instead of resetting everyone`() {
        assertEquals("Rex", TtsVoiceCatalog.resolve(TtsVoiceCatalog.GROK_MODEL, "Orus"))
        assertEquals("Rex", TtsVoiceCatalog.resolve(TtsVoiceCatalog.GROK_MODEL, "Charon"))
        assertEquals("Eve", TtsVoiceCatalog.resolve(TtsVoiceCatalog.GROK_MODEL, "Leda"))
    }

    /** Qwen accepts cloned ids that no allowlist can enumerate; only foreign names are rejected. */
    @Test
    fun `a qwen clone id survives while another providers name does not`() {
        val model = "qwen/qwen-audio-3.0-tts-plus"

        assertEquals("my_cloned_voice_42", TtsVoiceCatalog.resolve(model, "my_cloned_voice_42"))
        assertTrue(TtsVoiceCatalog.isCompatible(model, "my_cloned_voice_42"))
        assertFalse(TtsVoiceCatalog.isCompatible(model, "Kore"))
    }

    @Test
    fun `a blank voice always resolves to a usable default`() {
        listOf(
            TtsVoiceCatalog.DEFAULT_REMOTE_MODEL,
            TtsVoiceCatalog.GROK_MODEL,
            "openai/gpt-4o-mini-tts",
            "qwen/qwen-audio-3.0-tts-plus",
            TtsVoiceCatalog.ANDROID_SYSTEM_MODEL,
        ).forEach { model ->
            val resolved = TtsVoiceCatalog.resolve(model, "")
            assertTrue("$model resolved to a blank voice", resolved.isNotBlank())
            assertTrue("$model default is not compatible", TtsVoiceCatalog.isCompatible(model, resolved))
        }
    }

    /** Whatever the picker offers has to survive the send path unchanged. */
    @Test
    fun `every offered voice resolves to itself`() {
        listOf(
            TtsVoiceCatalog.DEFAULT_REMOTE_MODEL,
            TtsVoiceCatalog.GROK_MODEL,
            "openai/gpt-4o-mini-tts",
        ).forEach { model ->
            TtsVoiceCatalog.knownVoices(model).forEach { voice ->
                assertEquals(voice, TtsVoiceCatalog.resolve(model, voice))
                assertTrue(TtsVoiceCatalog.isCompatible(model, voice))
            }
        }
    }

    @Test
    fun `the schema default model is the remote gemini path`() {
        assertEquals(
            TtsVoiceCatalog.DEFAULT_REMOTE_MODEL,
            (BotSettingsSchema.defaults.getValue(BotSettingKeys.TTS_MODEL) as SettingValue.Text).value,
        )
        val voice =
            (BotSettingsSchema.defaults.getValue(BotSettingKeys.TTS_VOICE) as SettingValue.Text).value
        assertTrue(
            "global default voice is not compatible with the default model",
            TtsVoiceCatalog.isCompatible(TtsVoiceCatalog.DEFAULT_REMOTE_MODEL, voice),
        )
    }
}
