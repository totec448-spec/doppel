package de.totec.doppel.settings

import org.json.JSONObject

/**
 * The voice a persona speaks with, stored on the persona itself.
 *
 * A voice note is the persona's own voice, so it belongs to the persona and not to the app: Lina
 * and Sam sending the same recording in the same voice is the single most obvious way the illusion
 * breaks. The value lives in `PersonaRecord.voiceConfigJson` — a column that existed but was always
 * written null — and the global `tts_voice` setting stays as the fallback for anything without one.
 *
 * The names are Google's Gemini TTS voices, which is what the picker offers. That is not a
 * restriction to one provider: [de.totec.doppel.ai.TtsVoiceCatalog] maps the stored name onto
 * whatever the configured TTS model actually offers just before the request goes out, so a persona
 * keeps its character across a model switch.
 */
object PersonaVoices {
    private const val FIELD = "voice"
    private const val MAX_VOICE_CHARS = 64

    /**
     * The voice each built-in persona starts with, chosen to match the character the prompt
     * describes — age and gender first, then delivery. Nothing here is locked: a saved choice
     * always wins, and this table is only consulted when a persona has never had one.
     *
     * `custom` is deliberately absent. Its whole point is that the owner writes it, so it inherits
     * the global voice until the owner picks one.
     */
    private val BASE_VOICES =
        mapOf(
            // 18, lively, emotional — the youngest voice in the catalogue.
            "female" to "Leda",
            // 20, easy and direct.
            "male" to "Puck",
            // 24, dry and distant; breathy reads as detached rather than cold.
            "goth" to "Enceladus",
            // 40, calm and self-assured.
            "sam" to "Orus",
            "human" to "Zephyr",
            "default" to "Kore",
            "assistant" to "Charon",
            "homie" to "Zubenelgenubi",
            "sarkastisch" to "Umbriel",
            "flirty" to "Laomedeia",
            "coach" to "Fenrir",
            "nerd" to "Iapetus",
            "philosoph" to "Schedar",
            "formell" to "Erinome",
        )

    /** The bundled voice for [personaKey], or null for a persona that was never given one. */
    fun baseVoice(personaKey: String): String? = BASE_VOICES[personaKey.trim().lowercase()]

    /** The saved voice inside a `voiceConfigJson` blob, or null if it holds none. */
    fun read(voiceConfigJson: String?): String? {
        val raw = voiceConfigJson?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching {
            JSONObject(raw).optString(FIELD).trim().takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    /**
     * [voiceConfigJson] with [voice] set, or with the voice removed when it is null or blank.
     *
     * Merges rather than replaces: the column is a JSON object so later voice settings (speed,
     * style) can join it, and clearing a voice must not silently drop them.
     */
    fun write(
        voiceConfigJson: String?,
        voice: String?,
    ): String? {
        val existing =
            voiceConfigJson?.trim()?.takeIf(String::isNotEmpty)?.let {
                runCatching { JSONObject(it) }.getOrNull()
            } ?: JSONObject()
        val normalized = voice?.trim()?.take(MAX_VOICE_CHARS)?.takeIf(String::isNotEmpty)
        if (normalized == null) existing.remove(FIELD) else existing.put(FIELD, normalized)
        return existing.toString().takeIf { existing.length() > 0 }
    }

    /**
     * The voice to speak [personaKey] with: its own if it has one, its bundled one if it does not,
     * and [fallback] — the global `tts_voice` — only when neither exists.
     */
    fun effectiveVoice(
        personaKey: String,
        voiceConfigJson: String?,
        fallback: String,
    ): String = read(voiceConfigJson) ?: baseVoice(personaKey) ?: fallback
}
