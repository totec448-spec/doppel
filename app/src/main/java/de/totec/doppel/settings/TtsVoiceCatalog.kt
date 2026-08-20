package de.totec.doppel.settings

/**
 * Single source of truth for "which voice may be sent to which TTS model".
 *
 * The rule used to live twice: the picker offered one list while the send path silently rewrote
 * the value with a different one, so a voice could look selected in the UI and never reach the
 * provider. Both callers now resolve through here, which makes "the picker shows it" and "the
 * request accepts it" the same statement.
 *
 * Voices reported by OpenRouter for the concrete model always win; the static tables are the
 * offline fallback for a catalog that has not been fetched yet.
 */
object TtsVoiceCatalog {
    /** Local Android engine; it owns its own voice handling and constrains nothing here. */
    const val ANDROID_SYSTEM_MODEL = "android/system-tts"

    /** Preferred remote default; mirrors the Node bridge's `TTS_MODEL`. */
    const val DEFAULT_REMOTE_MODEL = "google/gemini-3.1-flash-tts-preview"

    const val GROK_MODEL = "x-ai/grok-voice-tts-1.0"

    val GROK_VOICES = listOf("Eve", "Ara", "Rex", "Sal", "Leo")

    val GOOGLE_VOICES =
        listOf(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
            "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
            "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
            "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
        )

    val OPENAI_VOICES =
        listOf(
            "alloy", "ash", "ballad", "coral", "echo", "fable",
            "nova", "onyx", "sage", "shimmer", "verse",
        )

    const val QWEN_DEFAULT_VOICE = "longanlingxin"

    /**
     * Voices this model is known to accept, or an empty list when the model takes free-form
     * identifiers (Qwen clones) and no catalog entry narrows it down. An empty result means
     * "do not constrain", never "no voices available".
     */
    fun knownVoices(model: String, catalogVoices: List<String>? = null): List<String> {
        catalogVoices?.takeIf { it.isNotEmpty() }?.let { return it }
        return when {
            model.equals(ANDROID_SYSTEM_MODEL, ignoreCase = true) -> emptyList()
            model.equals(GROK_MODEL, ignoreCase = true) -> GROK_VOICES
            model.isGoogleTts() -> GOOGLE_VOICES
            model.startsWith("openai/", ignoreCase = true) -> OPENAI_VOICES
            model.startsWith("mistralai/voxtral", ignoreCase = true) -> OPENAI_VOICES
            else -> emptyList()
        }
    }

    /** The voice used when the configured one cannot be honoured. */
    fun defaultVoice(model: String, catalogVoices: List<String>? = null): String {
        catalogVoices?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { preferred ->
            // Prefer a familiar name when the provider happens to offer one, so switching models
            // does not also silently change how the persona sounds.
            return catalogVoices.firstOrNull { it.equals("Kore", ignoreCase = true) } ?: preferred
        }
        return when {
            model.equals(GROK_MODEL, ignoreCase = true) -> "Eve"
            model.isGoogleTts() -> "Kore"
            model.startsWith("openai/", ignoreCase = true) -> "coral"
            model.startsWith("mistralai/voxtral", ignoreCase = true) -> "coral"
            model.isQwenTts() -> QWEN_DEFAULT_VOICE
            else -> "Kore"
        }
    }

    fun isCompatible(
        model: String,
        voice: String,
        catalogVoices: List<String>? = null,
    ): Boolean {
        if (voice.isBlank()) return false
        val known = knownVoices(model, catalogVoices)
        if (known.isEmpty()) {
            // Free-form provider: anything except another provider's obvious name is plausible.
            return !model.isQwenTts() || !isForeignVoice(voice)
        }
        return known.any { it.equals(voice, ignoreCase = true) }
    }

    /**
     * Maps a requested voice onto one the model will accept, preserving the exact casing the
     * provider publishes so the request matches the catalog byte for byte.
     */
    fun resolve(
        model: String,
        requested: String,
        catalogVoices: List<String>? = null,
    ): String {
        val known = knownVoices(model, catalogVoices)
        known.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
        if (known.isEmpty()) {
            if (model.isQwenTts()) {
                // Qwen also accepts hundreds of base and model-bound cloned voice IDs, so preserve
                // non-foreign IDs instead of using a closed allowlist that would reject legitimate
                // custom voices.
                return if (requested.isBlank() || isForeignVoice(requested)) {
                    QWEN_DEFAULT_VOICE
                } else {
                    requested
                }
            }
            return requested.ifBlank { defaultVoice(model, catalogVoices) }
        }
        if (model.equals(GROK_MODEL, ignoreCase = true)) {
            // Keep the perceived gender across the switch rather than resetting everyone to Eve.
            if (requested.equals("Orus", true) || requested.equals("Charon", true)) return "Rex"
        }
        return defaultVoice(model, catalogVoices)
    }

    private fun isForeignVoice(voice: String): Boolean =
        (GROK_VOICES + GOOGLE_VOICES + OPENAI_VOICES).any { it.equals(voice, ignoreCase = true) }

    private fun String.isGoogleTts(): Boolean =
        startsWith("google/", ignoreCase = true) && contains("tts", ignoreCase = true)

    private fun String.isQwenTts(): Boolean =
        startsWith("qwen/", ignoreCase = true) && contains("tts", ignoreCase = true)
}
