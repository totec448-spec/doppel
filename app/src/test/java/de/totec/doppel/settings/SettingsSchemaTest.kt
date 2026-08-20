package de.totec.doppel.settings

import de.totec.doppel.engine.DEFAULT_AI_DISCLOSURE_TEXT
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSchemaTest {
    @Test
    fun liveSchemaHasExactlyThe88SourceBackedSettings() {
        assertEquals(88, BotSettingsSchema.LIVE_SETTING_COUNT)
        assertEquals(88, BotSettingsSchema.all.size)
        assertEquals(EXPECTED_LIVE_KEYS, BotSettingsSchema.byKey.keys)
        assertEquals(EXPECTED_LIVE_KEYS, BotSettingsSchema.defaults.keys)
        assertFalse(BotSettingsSchema.byKey.containsKey("tts_script_model"))
        assertTrue(BotSettingsSchema.all.none { it.tier == SettingTier.HIDDEN })
    }

    @Test
    fun everyLiveDefaultIsExactAndValid() {
        val encodedDefaults =
            BotSettingsSchema.defaults
                .filterKeys { it != BotSettingKeys.BASE_PROMPT }
                .mapValues { (_, value) -> value.encode() }

        assertEquals(EXPECTED_ENCODED_DEFAULTS, encodedDefaults)
        BotSettingsSchema.all.forEach { spec ->
            assertEquals(
                "${spec.key} has an invalid default",
                SettingValidation.Valid,
                spec.validate(spec.defaultValue),
            )
        }

        val basePrompt = (BotSettingsSchema.defaults.getValue(BotSettingKeys.BASE_PROMPT) as SettingValue.Text).value
        assertTrue(basePrompt.startsWith("# What this is\n"))
        assertTrue(basePrompt.contains("never gets a reason to wonder whether a human is on this end"))
        assertTrue(basePrompt.endsWith("use it only where it falls naturally."))
        assertEquals(EXPECTED_BASE_PROMPT_SHA_256, sha256(basePrompt))
    }

    @Test
    fun numericBoundsAreCompleteAndExact() {
        val liveNumeric =
            BotSettingsSchema.all
                .filter { it.valueType == SettingValueType.INTEGER || it.valueType == SettingValueType.DECIMAL }
                .associate { it.key to it.bounds }
        assertEquals(EXPECTED_LIVE_BOUNDS, liveNumeric)

        val appNumeric =
            AppSettingsSchema.all
                .filter { it.valueType == SettingValueType.INTEGER || it.valueType == SettingValueType.DECIMAL }
                .associate { it.key to it.bounds }
        assertEquals(EXPECTED_APP_BOUNDS, appNumeric)

        (BotSettingsSchema.all + AppSettingsSchema.all)
            .filterNot {
                it.valueType == SettingValueType.INTEGER ||
                    it.valueType == SettingValueType.DECIMAL
            }.forEach { assertNull("${it.key} unexpectedly has numeric bounds", it.bounds) }
    }

    @Test
    fun enumAndCatalogOptionsAreExact() {
        val liveEnums =
            BotSettingsSchema.all
                .filter { it.valueType == SettingValueType.ENUM }
                .associate { spec -> spec.key to spec.options.map(SettingOption::value) }
        assertEquals(EXPECTED_LIVE_ENUMS, liveEnums)

        val appEnums =
            AppSettingsSchema.all
                .filter { it.valueType == SettingValueType.ENUM }
                .associate { spec -> spec.key to spec.options.map(SettingOption::value) }
        assertEquals(EXPECTED_APP_ENUMS, appEnums)

        assertEquals(EXPECTED_PERSONAS, SettingsCatalogs.personas.map(PersonaCatalogEntry::key))
        assertEquals(15, SettingsCatalogs.personas.size)
        assertEquals(14, SettingsCatalogs.personas.count { !it.customEditor })
        assertEquals(listOf("custom"), SettingsCatalogs.personas.filter(PersonaCatalogEntry::customEditor).map(PersonaCatalogEntry::key))

        assertEquals(EXPECTED_VOICES, SettingsCatalogs.voices.map(VoiceCatalogEntry::name))
        assertEquals(22, SettingsCatalogs.voices.size)
        assertEquals(22, SettingsCatalogs.voices.map(VoiceCatalogEntry::name).distinct().size)
        assertEquals(
            EXPECTED_VOICES,
            BotSettingsSchema.requireSpec(BotSettingKeys.TTS_VOICE).options.map(SettingOption::value),
        )
    }

    @Test
    fun uiMetadataCoversEverySettingWithoutAParallelHiddenControl() {
        assertEquals(
            BotSettingsSchema.all.toSet(),
            (BotSettingsSchema.basic + BotSettingsSchema.expert).toSet(),
        )
        assertTrue(BotSettingsSchema.basic.intersect(BotSettingsSchema.expert.toSet()).isEmpty())

        (BotSettingsSchema.all + AppSettingsSchema.all).forEach { spec ->
            assertTrue("${spec.key} description", spec.description.isNotBlank())
            assertTrue("${spec.key} has a hidden tier", spec.tier != SettingTier.HIDDEN)
            assertNotNull("${spec.key} group", spec.group)
            assertNotNull("${spec.key} control", spec.control)
            when (spec.valueType) {
                SettingValueType.BOOLEAN -> assertEquals(spec.key, SettingControl.SWITCH, spec.control)
                SettingValueType.ENUM -> {
                    assertTrue("${spec.key} enum options", spec.options.isNotEmpty())
                    assertTrue(
                        "${spec.key} enum control",
                        spec.control == SettingControl.DROPDOWN ||
                            spec.control == SettingControl.PERSONA_PICKER ||
                            spec.control == SettingControl.STEPPED_SLIDER,
                    )
                }
                SettingValueType.SECRET_REFERENCE -> {
                    assertTrue(spec.sensitiveReference)
                    assertEquals(spec.key, SettingControl.SECRET_REFERENCE, spec.control)
                }
                else -> Unit
            }
            if (spec.modelRole != null) {
                assertEquals(spec.key, SettingControl.MODEL_PICKER, spec.control)
            }
        }

        val modelRoles =
            (BotSettingsSchema.all + AppSettingsSchema.all)
                .filter { it.modelRole != null }
                .associate { it.key to it.modelRole }
        assertEquals(EXPECTED_MODEL_ROLES, modelRoles)
    }

    @Test
    fun appSchemaCoversAllAndroidOnlyControlsAndNeverStoresSecrets() {
        assertEquals(EXPECTED_APP_KEYS, AppSettingsSchema.byKey.keys)
        assertEquals(EXPECTED_APP_KEYS, AppSettingsSchema.defaults.keys)

        val secretReferences =
            AppSettingsSchema.all
                .filter(SettingSpec::sensitiveReference)
                .associate { spec ->
                    spec.key to (spec.defaultValue as SettingValue.SecretReference).name
                }
        assertEquals(
            mapOf(
                AppSettingKeys.OPENROUTER_API_KEY_REF to "openrouter_api_key",
                AppSettingKeys.BRIDGE_TOKEN_REF to "bridge_token",
                AppSettingKeys.STT_API_KEY_REF to "stt_api_key",
            ),
            secretReferences,
        )
        assertTrue(
            AppSettingsSchema.all.none { spec ->
                spec.key.contains("api_key") && spec.valueType != SettingValueType.SECRET_REFERENCE
            },
        )
    }

    @Test
    fun appTransportSchemaOnlyExposesImplementedModesAndKeepsAutostart() {
        assertFalse(AppSettingsSchema.byKey.containsKey(AppSettingKeys.BRIDGE_MODE))
        assertFalse(AppSettingsSchema.byKey.containsKey(AppSettingKeys.CONNECTION_MODE))
        // The off-device Baileys companion is gone: no transport may point outside this phone.
        assertFalse(AppSettingsSchema.byKey.containsKey(AppSettingKeys.BRIDGE_URL))
        // And there is no transport left to pick between: the notification-only mode was removed,
        // so the setting that chose one is a migration-only key with no live spec.
        assertFalse(AppSettingsSchema.byKey.containsKey(AppSettingKeys.TRANSPORT_MODE))

        val autostart = AppSettingsSchema.byKey.getValue(AppSettingKeys.AUTOSTART)
        assertEquals(SettingValue.Bool(true), autostart.defaultValue)
        assertEquals(SettingTier.BASIC, autostart.tier)
        assertEquals(SettingGroup.CONNECTION, autostart.group)
        assertEquals(SettingControl.SWITCH, autostart.control)
    }

    @Test
    fun newInstallsAllowEveryContactAndGroupWithoutARequiredTrigger() {
        val allowAll = AppSettingsSchema.byKey.getValue(AppSettingKeys.ALLOW_ALL)
        assertEquals(SettingValue.Bool(true), allowAll.defaultValue)
        assertEquals(SettingTier.BASIC, allowAll.tier)
        assertEquals(SettingGroup.ACCESS, allowAll.group)
        assertEquals(SettingControl.SWITCH, allowAll.control)
        assertEquals(
            SettingValue.Integer(20 * 1_024 * 1_024),
            AppSettingsSchema.byKey.getValue(AppSettingKeys.MAX_VIDEO_BYTES).defaultValue,
        )
        assertEquals(
            SettingValue.Text(""),
            AppSettingsSchema.byKey.getValue(AppSettingKeys.GROUP_TRIGGER).defaultValue,
        )
    }

    @Test
    fun allUserFacingMetadataIsUtf8AndContainsNoMojibake() {
        val text =
            buildList {
                (BotSettingsSchema.all + AppSettingsSchema.all).forEach { spec ->
                    add(spec.key)
                    add(spec.description)
                    spec.options.forEach { option ->
                        add(option.value)
                        add(option.label)
                        add(option.description)
                    }
                    val default = spec.defaultValue
                    if (default is SettingValue.Text) add(default.value)
                }
                SettingsCatalogs.personas.forEach {
                    add(it.key)
                    add(it.label)
                    add(it.emoji)
                }
                SettingsCatalogs.voices.forEach {
                    add(it.name)
                    add(it.description)
                }
                val invalid =
                    BotSettingsSchema.requireSpec(BotSettingKeys.TEMPERATURE)
                        .validate(SettingValue.Decimal(-1.0)) as SettingValidation.Invalid
                add(invalid.reason)
            }.joinToString("\n")

        listOf("Ã", "â", "ðŸ", "ï¿½", "\uFFFD").forEach { forbidden ->
            assertFalse("Mojibake fragment '$forbidden' found", text.contains(forbidden))
        }
        // One sample per UTF-8 width that still occurs: three-byte (setting descriptions) and
        // four-byte (persona emoji). The catalogue is English now and no longer contains a
        // two-byte character anywhere, so there is nothing left to sample for that width.
        assertTrue(text.contains("…"))
        assertTrue(text.contains("🧑"))
        assertTrue(text.contains("–") || text.contains("—"))
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXPECTED_BASE_PROMPT_SHA_256 =
            "6144a77e681d4f2a02f426f39a5bc8865b7770d9b4e5fd14f37d46a053d6fec5"

        val EXPECTED_LIVE_KEYS =
            linkedSetOf(
                BotSettingKeys.ENABLED,
                BotSettingKeys.MODEL,
                BotSettingKeys.MEDIA_MODEL,
                BotSettingKeys.IMAGE_ANALYSIS_PROMPT,
                BotSettingKeys.VIDEO_ANALYSIS_PROMPT,
                BotSettingKeys.VOICE_ANALYSIS_PROMPT,
                BotSettingKeys.MEDIA_REASONING_EFFORT,
                BotSettingKeys.IMAGE_GENERATION_ENABLED,
                BotSettingKeys.IMAGE_GENERATION_MODEL,
                BotSettingKeys.IMAGE_GENERATION_QUALITY,
                BotSettingKeys.IMAGE_GENERATION_PREFIX,
                BotSettingKeys.FIRST_PARTY_PROVIDER_ONLY,
                BotSettingKeys.VISION_ENABLED,
                BotSettingKeys.STT_ENABLED,
                BotSettingKeys.VIDEO_ENABLED,
                BotSettingKeys.PERSONALITY,
                BotSettingKeys.BASE_PROMPT,
                BotSettingKeys.SYSTEM_PROMPT,
                BotSettingKeys.TEMPERATURE,
                BotSettingKeys.MAX_TOKENS,
                BotSettingKeys.MEMORY_CHAR_LIMIT,
                BotSettingKeys.HISTORY_RETENTION,
                BotSettingKeys.REASONING_EFFORT,
                BotSettingKeys.TOP_P,
                BotSettingKeys.FREQUENCY_PENALTY,
                BotSettingKeys.PRESENCE_PENALTY,
                BotSettingKeys.HISTORY_LIMIT,
                BotSettingKeys.MEMORY_INTERVAL,
                BotSettingKeys.MEMORY_CHAT_ENABLED,
                BotSettingKeys.MEMORY_GLOBAL_ENABLED,
                BotSettingKeys.MEMORY_GLOBAL_INTERVAL,
                BotSettingKeys.HISTORY_TIMESTAMPS,
                BotSettingKeys.BATCH_WINDOW_MS,
                BotSettingKeys.REPLY_PRESET,
                BotSettingKeys.TYPING_SPEED_WPM,
                BotSettingKeys.READING_SPEED_WPS,
                BotSettingKeys.TYPING_STOP_DELAY_MS,
                BotSettingKeys.PROACTIVE_LEVEL,
                BotSettingKeys.PROACTIVE_MODE,
                BotSettingKeys.AUTO_VERIFY,
                BotSettingKeys.VERIFY_MODEL,
                BotSettingKeys.VERIFY_MAX_RETRIES,
                BotSettingKeys.VERIFY_MAX_TOKENS,
                BotSettingKeys.VERIFY_REASONING_EFFORT,
                BotSettingKeys.VERIFY_FAIL_CLOSED,
                BotSettingKeys.BLOCK_REPEAT_IMAGES,
                BotSettingKeys.CROSS_CHAT_SEARCH,
                BotSettingKeys.ENABLE_REACTIONS,
                BotSettingKeys.ENABLE_QUOTE_REPLY,
                BotSettingKeys.AI_DISCLOSURE_ENABLED,
                BotSettingKeys.AI_DISCLOSURE_TEXT,
                BotSettingKeys.MARK_READ,
                BotSettingKeys.SELF_EDIT_ENABLED,
                BotSettingKeys.SELF_EDIT_CHANCE,
                BotSettingKeys.SELF_EDIT_DELAY_DIVISOR,
                BotSettingKeys.MOOD_ENABLED,
                BotSettingKeys.PROFILE_PICTURE_ENABLED,
                BotSettingKeys.PROFILE_PICTURE_INTERVAL_DAYS,
                BotSettingKeys.ERROR_REPORTS,
                BotSettingKeys.SLEEP_START,
                BotSettingKeys.SLEEP_END,
                BotSettingKeys.TIMEZONE,
                BotSettingKeys.POWER_MODE,
                BotSettingKeys.LOW_LISTEN_MINUTES,
                BotSettingKeys.TTS_ENABLED,
                BotSettingKeys.TTS_MODEL,
                BotSettingKeys.TTS_VOICE,
                BotSettingKeys.TTS_QUALITY,
                BotSettingKeys.TTS_STYLE_PROMPT,
                BotSettingKeys.TRAIT_OBEDIENCE,
                BotSettingKeys.TRAIT_FLIRT,
                BotSettingKeys.TRAIT_LEWD,
                BotSettingKeys.TRAIT_MEANNESS,
                BotSettingKeys.TRAIT_INITIATIVE,
                BotSettingKeys.TRAIT_OPENNESS,
                BotSettingKeys.TRAIT_SUSPICION,
                BotSettingKeys.TRAIT_PLAYFULNESS,
                BotSettingKeys.TRAIT_CHAOS,
                BotSettingKeys.MAX_SENDS_PER_HOUR,
                BotSettingKeys.MAX_SENDS_PER_DAY,
                BotSettingKeys.PROACTIVE_DAILY_CAP,
                BotSettingKeys.PROACTIVE_REPLY_RATIO_MAX,
                BotSettingKeys.COLD_MONTHLY_CAP,
                BotSettingKeys.AUTOBLOCK_ENABLED,
                BotSettingKeys.AUTOBLOCK_PER_MIN,
                BotSettingKeys.AUTOBLOCK_PER_5MIN,
                BotSettingKeys.AUTOBLOCK_PER_10MIN,
                BotSettingKeys.BLOCK_TOOL_ENABLED,
            )

        val EXPECTED_ENCODED_DEFAULTS =
            linkedMapOf(
                BotSettingKeys.ENABLED to "true",
                BotSettingKeys.MODEL to "deepseek/deepseek-v4-pro-0813",
                BotSettingKeys.MEDIA_MODEL to "google/gemini-3.7-flash",
                BotSettingKeys.IMAGE_ANALYSIS_PROMPT to DefaultMediaAnalysisPrompts.IMAGE,
                BotSettingKeys.VIDEO_ANALYSIS_PROMPT to DefaultMediaAnalysisPrompts.VIDEO,
                BotSettingKeys.VOICE_ANALYSIS_PROMPT to DefaultMediaAnalysisPrompts.VOICE,
                BotSettingKeys.MEDIA_REASONING_EFFORT to "minimal",
                BotSettingKeys.IMAGE_GENERATION_ENABLED to "true",
                BotSettingKeys.IMAGE_GENERATION_MODEL to "openai/gpt-image-2",
                BotSettingKeys.IMAGE_GENERATION_QUALITY to "very_low",
                BotSettingKeys.IMAGE_GENERATION_PREFIX to
                    "Create an intentionally ordinary, unpolished phone photo. It should look straight from an " +
                        "iPhone gallery: casual framing, available light, slight motion blur or missed focus, " +
                        "visible grain and compression, imperfect exposure and white balance, natural skin " +
                        "texture, no studio polish, no cinematic grading, and no influencer aesthetic.",
                BotSettingKeys.FIRST_PARTY_PROVIDER_ONLY to "true",
                BotSettingKeys.VISION_ENABLED to "true",
                BotSettingKeys.STT_ENABLED to "true",
                BotSettingKeys.VIDEO_ENABLED to "true",
                BotSettingKeys.PERSONALITY to "human",
                BotSettingKeys.SYSTEM_PROMPT to "",
                BotSettingKeys.TEMPERATURE to "0.7",
                BotSettingKeys.MAX_TOKENS to "10000",
                BotSettingKeys.MEMORY_CHAR_LIMIT to "0",
                BotSettingKeys.HISTORY_RETENTION to "1000",
                BotSettingKeys.REASONING_EFFORT to "high",
                BotSettingKeys.TOP_P to "1",
                BotSettingKeys.FREQUENCY_PENALTY to "0",
                BotSettingKeys.PRESENCE_PENALTY to "0",
                BotSettingKeys.HISTORY_LIMIT to "10",
                BotSettingKeys.MEMORY_INTERVAL to "150",
                BotSettingKeys.MEMORY_CHAT_ENABLED to "true",
                BotSettingKeys.MEMORY_GLOBAL_ENABLED to "true",
                BotSettingKeys.MEMORY_GLOBAL_INTERVAL to "3",
                BotSettingKeys.HISTORY_TIMESTAMPS to "true",
                BotSettingKeys.BATCH_WINDOW_MS to "2000",
                BotSettingKeys.REPLY_PRESET to "instant",
                BotSettingKeys.TYPING_SPEED_WPM to "70",
                BotSettingKeys.READING_SPEED_WPS to "10",
                BotSettingKeys.TYPING_STOP_DELAY_MS to "2000",
                BotSettingKeys.PROACTIVE_LEVEL to "0",
                BotSettingKeys.PROACTIVE_MODE to "fixed",
                BotSettingKeys.AUTO_VERIFY to "true",
                BotSettingKeys.VERIFY_MODEL to "deepseek/deepseek-v4-flash-0731",
                BotSettingKeys.VERIFY_MAX_RETRIES to "3",
                BotSettingKeys.VERIFY_MAX_TOKENS to "2000",
                BotSettingKeys.VERIFY_REASONING_EFFORT to "low",
                BotSettingKeys.VERIFY_FAIL_CLOSED to "false",
                BotSettingKeys.BLOCK_REPEAT_IMAGES to "true",
                BotSettingKeys.CROSS_CHAT_SEARCH to "false",
                BotSettingKeys.ENABLE_REACTIONS to "true",
                BotSettingKeys.ENABLE_QUOTE_REPLY to "true",
                BotSettingKeys.AI_DISCLOSURE_ENABLED to "true",
                BotSettingKeys.AI_DISCLOSURE_TEXT to DEFAULT_AI_DISCLOSURE_TEXT,
                BotSettingKeys.MARK_READ to "true",
                BotSettingKeys.SELF_EDIT_ENABLED to "true",
                BotSettingKeys.SELF_EDIT_CHANCE to "0.03",
                BotSettingKeys.SELF_EDIT_DELAY_DIVISOR to "2",
                BotSettingKeys.MOOD_ENABLED to "true",
                BotSettingKeys.PROFILE_PICTURE_ENABLED to "true",
                BotSettingKeys.PROFILE_PICTURE_INTERVAL_DAYS to "30",
                BotSettingKeys.ERROR_REPORTS to "true",
                BotSettingKeys.SLEEP_START to "00:30",
                BotSettingKeys.SLEEP_END to "08:30",
                BotSettingKeys.TIMEZONE to "Europe/Berlin",
                BotSettingKeys.POWER_MODE to "default",
                BotSettingKeys.LOW_LISTEN_MINUTES to "10",
                BotSettingKeys.TTS_ENABLED to "true",
                BotSettingKeys.TTS_MODEL to "google/gemini-3.1-flash-tts-preview",
                BotSettingKeys.TTS_VOICE to "Kore",
                BotSettingKeys.TTS_QUALITY to "6",
                BotSettingKeys.TTS_STYLE_PROMPT to
                    "real casual voice note, really low recording quality, noisy, phone microphone, fast paced, uneven",
                BotSettingKeys.TRAIT_OBEDIENCE to "0",
                BotSettingKeys.TRAIT_FLIRT to "0",
                BotSettingKeys.TRAIT_LEWD to "0",
                BotSettingKeys.TRAIT_MEANNESS to "0",
                BotSettingKeys.TRAIT_INITIATIVE to "0",
                BotSettingKeys.TRAIT_OPENNESS to "0",
                BotSettingKeys.TRAIT_SUSPICION to "0",
                BotSettingKeys.TRAIT_PLAYFULNESS to "0",
                BotSettingKeys.TRAIT_CHAOS to "0",
                BotSettingKeys.MAX_SENDS_PER_HOUR to "25",
                BotSettingKeys.MAX_SENDS_PER_DAY to "120",
                BotSettingKeys.PROACTIVE_DAILY_CAP to "2",
                BotSettingKeys.PROACTIVE_REPLY_RATIO_MAX to "0.1",
                BotSettingKeys.COLD_MONTHLY_CAP to "30",
                BotSettingKeys.AUTOBLOCK_ENABLED to "true",
                BotSettingKeys.AUTOBLOCK_PER_MIN to "10",
                BotSettingKeys.AUTOBLOCK_PER_5MIN to "30",
                BotSettingKeys.AUTOBLOCK_PER_10MIN to "50",
                BotSettingKeys.BLOCK_TOOL_ENABLED to "true",
            )

        val EXPECTED_LIVE_BOUNDS =
            linkedMapOf(
                BotSettingKeys.TEMPERATURE to NumericBounds(0.0, 2.0),
                BotSettingKeys.MAX_TOKENS to NumericBounds(1_000.0, 100_000.0),
                BotSettingKeys.MEMORY_CHAR_LIMIT to NumericBounds(0.0, null),
                BotSettingKeys.HISTORY_RETENTION to NumericBounds(0.0, 5_000.0),
                BotSettingKeys.TOP_P to NumericBounds(0.0, 1.0),
                BotSettingKeys.FREQUENCY_PENALTY to NumericBounds(-2.0, 2.0),
                BotSettingKeys.PRESENCE_PENALTY to NumericBounds(-2.0, 2.0),
                BotSettingKeys.HISTORY_LIMIT to NumericBounds(0.0, 100.0),
                BotSettingKeys.MEMORY_INTERVAL to NumericBounds(10.0, 500.0),
                BotSettingKeys.MEMORY_GLOBAL_INTERVAL to NumericBounds(1.0, 10.0),
                BotSettingKeys.BATCH_WINDOW_MS to NumericBounds(0.0, 60_000.0),
                BotSettingKeys.TYPING_SPEED_WPM to NumericBounds(10.0, 200.0),
                BotSettingKeys.READING_SPEED_WPS to NumericBounds(1.0, 60.0),
                BotSettingKeys.TYPING_STOP_DELAY_MS to NumericBounds(0.0, 10_000.0),
                BotSettingKeys.PROACTIVE_LEVEL to NumericBounds(0.0, 10.0),
                BotSettingKeys.VERIFY_MAX_RETRIES to NumericBounds(0.0, 5.0),
                BotSettingKeys.VERIFY_MAX_TOKENS to NumericBounds(32.0, 4_000.0),
                BotSettingKeys.SELF_EDIT_CHANCE to NumericBounds(0.0, 1.0),
                BotSettingKeys.SELF_EDIT_DELAY_DIVISOR to NumericBounds(1.0, 10.0),
                BotSettingKeys.PROFILE_PICTURE_INTERVAL_DAYS to NumericBounds(1.0, 180.0),
                BotSettingKeys.LOW_LISTEN_MINUTES to NumericBounds(2.0, 30.0),
                BotSettingKeys.TTS_QUALITY to NumericBounds(1.0, 10.0),
                BotSettingKeys.TRAIT_OBEDIENCE to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_FLIRT to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_LEWD to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_MEANNESS to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_INITIATIVE to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_OPENNESS to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_SUSPICION to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_PLAYFULNESS to NumericBounds(-3.0, 3.0),
                BotSettingKeys.TRAIT_CHAOS to NumericBounds(-3.0, 3.0),
                BotSettingKeys.MAX_SENDS_PER_HOUR to NumericBounds(1.0, 1_000.0),
                BotSettingKeys.MAX_SENDS_PER_DAY to NumericBounds(1.0, 100_000.0),
                BotSettingKeys.PROACTIVE_DAILY_CAP to NumericBounds(0.0, 1_000.0),
                BotSettingKeys.PROACTIVE_REPLY_RATIO_MAX to NumericBounds(0.0, 1.0),
                BotSettingKeys.COLD_MONTHLY_CAP to NumericBounds(1.0, 10_000.0),
                BotSettingKeys.AUTOBLOCK_PER_MIN to NumericBounds(0.0, 1_000.0),
                BotSettingKeys.AUTOBLOCK_PER_5MIN to NumericBounds(0.0, 5_000.0),
                BotSettingKeys.AUTOBLOCK_PER_10MIN to NumericBounds(0.0, 10_000.0),
            )

        val EXPECTED_APP_BOUNDS =
            linkedMapOf(
                AppSettingKeys.OPENROUTER_TIMEOUT_MS to NumericBounds(0.0, 600_000.0),
                AppSettingKeys.RATE_LIMIT_MAX to NumericBounds(1.0, 1_000.0),
                AppSettingKeys.RATE_LIMIT_WINDOW_MS to NumericBounds(1_000.0, 3_600_000.0),
                AppSettingKeys.MAX_VIDEO_BYTES to NumericBounds(1_048_576.0, 20_971_520.0),
                AppSettingKeys.WARMUP_DAYS to NumericBounds(1.0, 30.0),
                AppSettingKeys.RECOVERY_WARMUP_DAYS to NumericBounds(1.0, 30.0),
            )

        val EXPECTED_PERSONAS =
            listOf(
                "human",
                "female",
                "male",
                "goth",
                "sam",
                "default",
                "assistant",
                "homie",
                "sarkastisch",
                "flirty",
                "coach",
                "nerd",
                "philosoph",
                "formell",
                "custom",
            )

        val EXPECTED_VOICES =
            listOf(
                "Zephyr",
                "Puck",
                "Charon",
                "Kore",
                "Fenrir",
                "Leda",
                "Orus",
                "Aoede",
                "Callirrhoe",
                "Autonoe",
                "Enceladus",
                "Iapetus",
                "Umbriel",
                "Algieba",
                "Despina",
                "Erinome",
                "Laomedeia",
                "Achernar",
                "Schedar",
                "Gacrux",
                "Sulafat",
                "Zubenelgenubi",
            )

        val EXPECTED_LIVE_ENUMS =
            linkedMapOf(
                BotSettingKeys.PERSONALITY to EXPECTED_PERSONAS,
                BotSettingKeys.REASONING_EFFORT to
                    listOf("off", "none", "minimal", "low", "medium", "high", "xhigh", "max"),
                BotSettingKeys.MEDIA_REASONING_EFFORT to
                    listOf("minimal", "low", "medium", "high", "xhigh", "max"),
                BotSettingKeys.IMAGE_GENERATION_QUALITY to
                    listOf("very_low", "low", "medium", "high"),
                BotSettingKeys.VERIFY_REASONING_EFFORT to
                    listOf("off", "none", "minimal", "low", "medium", "high", "xhigh", "max"),
                BotSettingKeys.REPLY_PRESET to listOf("human", "instant"),
                BotSettingKeys.PROACTIVE_MODE to listOf("fixed", "hot_cold"),
                BotSettingKeys.POWER_MODE to listOf("default", "low"),
            )

        /** There is one transport and no other app control is an enum, so this is empty on purpose. */
        val EXPECTED_APP_ENUMS = linkedMapOf<String, List<String>>()

        val EXPECTED_MODEL_ROLES =
            linkedMapOf(
                BotSettingKeys.MODEL to ModelRole.MAIN,
                BotSettingKeys.MEDIA_MODEL to ModelRole.MEDIA,
                BotSettingKeys.IMAGE_GENERATION_MODEL to ModelRole.IMAGE,
                BotSettingKeys.VERIFY_MODEL to ModelRole.VERIFIER,
                BotSettingKeys.TTS_MODEL to ModelRole.TTS,
                AppSettingKeys.STT_FALLBACK_MODEL to ModelRole.TRANSCRIBE,
            )

        val EXPECTED_APP_KEYS =
            linkedSetOf(
                AppSettingKeys.AUTOSTART,
                AppSettingKeys.OPENROUTER_BASE_URL,
                AppSettingKeys.OPENROUTER_REFERER,
                AppSettingKeys.OPENROUTER_TITLE,
                AppSettingKeys.OPENROUTER_TIMEOUT_MS,
                AppSettingKeys.OPENROUTER_API_KEY_REF,
                AppSettingKeys.BRIDGE_TOKEN_REF,
                AppSettingKeys.OWNER_NUMBERS,
                AppSettingKeys.ADMIN_NUMBERS,
                AppSettingKeys.ALLOWLIST_NUMBERS,
                AppSettingKeys.GROUP_ALLOWLIST,
                AppSettingKeys.ALLOW_ALL,
                AppSettingKeys.COMMAND_PREFIX,
                AppSettingKeys.GROUP_TRIGGER,
                AppSettingKeys.RATE_LIMIT_MAX,
                AppSettingKeys.RATE_LIMIT_WINDOW_MS,
                AppSettingKeys.STT_FALLBACK_URL,
                AppSettingKeys.STT_FALLBACK_MODEL,
                AppSettingKeys.STT_API_KEY_REF,
                AppSettingKeys.MAX_VIDEO_BYTES,
                AppSettingKeys.WARMUP_ENABLED,
                AppSettingKeys.WARMUP_DAYS,
                AppSettingKeys.RECOVERY_WARMUP_DAYS,
                AppSettingKeys.HIDE_SENSITIVE_DATA,
            )
    }
}
