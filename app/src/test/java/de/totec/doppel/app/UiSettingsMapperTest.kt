package de.totec.doppel.app

import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.AppSettingsSchema
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.BotSettingsSchema
import de.totec.doppel.settings.ModelRole
import de.totec.doppel.settings.SettingsSnapshot
import de.totec.doppel.settings.SettingValue
import de.totec.doppel.ui.UiSettingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UiSettingsMapperTest {
    @Test
    fun dedicatedSecretAndAccessValuesAreNotExposedAsEditors() {
        val projection = UiSettingsMapper.project(defaultSnapshot(), emptyList())
        val keys = (projection.basic + projection.expert).map { it.key }.toSet()

        assertFalse(AppSettingKeys.OPENROUTER_API_KEY_REF in keys)
        assertFalse(AppSettingKeys.BRIDGE_TOKEN_REF in keys)
        assertFalse(AppSettingKeys.OWNER_NUMBERS in keys)
        assertFalse(AppSettingKeys.ADMIN_NUMBERS in keys)
        assertFalse(AppSettingKeys.ALLOWLIST_NUMBERS in keys)
        assertFalse(AppSettingKeys.GROUP_ALLOWLIST in keys)
        assertFalse(BotSettingsSchema.LEGACY_REMOVED_LEAVE_ON_READ in keys)
        // Connected/start-stop is the only global runtime control.
        assertFalse(BotSettingKeys.ENABLED in keys)
        assertTrue(AppSettingKeys.AUTOSTART in keys)
    }

    @Test
    fun modelOptionsAreFilteredByDeclaredRole() {
        val projection =
            UiSettingsMapper.project(
                defaultSnapshot(),
                listOf(
                    UiModelOption("text/model", "Text", setOf(ModelRole.MAIN)),
                    UiModelOption("media/model", "Media", setOf(ModelRole.MEDIA)),
                ),
            )
        val all = projection.basic + projection.expert
        val main = all.single { it.key == BotSettingKeys.MODEL }
        val media = all.single { it.key == BotSettingKeys.MEDIA_MODEL }

        assertTrue(main.options.any { it.first == "text/model" })
        assertFalse(main.options.any { it.first == "media/model" })
        assertTrue(media.options.any { it.first == "media/model" })
        assertFalse(media.options.any { it.first == "text/model" })
        assertEquals(UiSettingKind.MODEL, main.kind)
    }

    @Test
    fun veryLargeNumericRangesUseExactNumberFieldInsteadOfFloatSlider() {
        val projection = UiSettingsMapper.project(defaultSnapshot(), emptyList())
        val timeout =
            (projection.basic + projection.expert)
                .single { it.key == AppSettingKeys.OPENROUTER_TIMEOUT_MS }

        assertEquals(UiSettingKind.INTEGER, timeout.kind)
        assertNull(timeout.minimum)
        assertNull(timeout.maximum)
    }

    @Test
    fun dailySendLimitUsesReachableSliderStepsDespiteItsLargeSafetyRange() {
        val projection = UiSettingsMapper.project(defaultSnapshot(), emptyList())
        val daily =
            (projection.basic + projection.expert)
                .single { it.key == BotSettingKeys.MAX_SENDS_PER_DAY }

        assertEquals(UiSettingKind.ENUM_SLIDER, daily.kind)
        assertTrue(daily.options.any { it.first == "120" })
        assertTrue(daily.options.any { it.first == "100000" })
    }

    @Test
    fun modelsAreNewestFirstAndReleaseDateIsVisible() {
        val projection =
            UiSettingsMapper.project(
                defaultSnapshot(),
                listOf(
                    UiModelOption(
                        id = "vendor/old",
                        label = "Old",
                        supportedRoles = setOf(ModelRole.MAIN),
                        createdAtEpochSeconds = 1_704_067_200L,
                    ),
                    UiModelOption(
                        id = "vendor/new",
                        label = "New",
                        supportedRoles = setOf(ModelRole.MAIN),
                        createdAtEpochSeconds = 1_767_225_600L,
                    ),
                ),
            )
        val main = (projection.basic + projection.expert).single { it.key == BotSettingKeys.MODEL }
        val catalogOptions = main.options.filter { it.first.startsWith("vendor/") }

        assertEquals(listOf("vendor/new", "vendor/old"), catalogOptions.map { it.first })
        assertTrue(catalogOptions.first().second.contains("2026-01-01"))
    }

    @Test
    fun reasoningSliderOnlyShowsSelectedModelEfforts() {
        val snapshot = snapshotWith(BotSettingKeys.MODEL, "deepseek/current")
        val projection =
            UiSettingsMapper.project(
                snapshot,
                listOf(
                    UiModelOption(
                        id = "deepseek/current",
                        label = "DeepSeek",
                        supportedRoles = setOf(ModelRole.MAIN),
                        reasoningEfforts = listOf("xhigh", "high"),
                    ),
                ),
            )
        val reasoning =
            (projection.basic + projection.expert)
                .single { it.key == BotSettingKeys.REASONING_EFFORT }

        assertEquals(UiSettingKind.ENUM_SLIDER, reasoning.kind)
        assertEquals(listOf("off", "high", "xhigh"), reasoning.options.map { it.first })
    }

    @Test
    fun dedicatedSpeechModelAndItsVoicesAreSelectable() {
        val snapshot = snapshotWith(BotSettingKeys.TTS_MODEL, "google/tts")
        val projection =
            UiSettingsMapper.project(
                snapshot,
                listOf(
                    UiModelOption(
                        id = "google/tts",
                        label = "Gemini TTS",
                        supportedRoles = setOf(ModelRole.TTS),
                        createdAtEpochSeconds = 1_785_513_600L,
                        supportedVoices = listOf("Kore", "Puck"),
                    ),
                ),
            )
        val all = projection.basic + projection.expert
        val tts = all.single { it.key == BotSettingKeys.TTS_MODEL }
        val voice = all.single { it.key == BotSettingKeys.TTS_VOICE }

        assertEquals(listOf("google/tts"), tts.options.map { it.first })
        assertEquals("Voice messages", tts.group)
        assertEquals(voice.group, tts.group)
        assertEquals(listOf("Kore", "Puck"), voice.options.map { it.first })
    }

    @Test
    fun transcriptionAndImagePickersUseTheirRealBillingUnits() {
        val projection =
            UiSettingsMapper.project(
                defaultSnapshot(),
                listOf(
                    UiModelOption(
                        id = "vendor/stt",
                        label = "STT",
                        supportedRoles = setOf(ModelRole.TRANSCRIBE),
                        promptPricePerToken = 0.006,
                        completionPricePerToken = 0.0,
                        transcriptionPricePerHour = 0.36,
                    ),
                    UiModelOption(
                        id = "vendor/image",
                        label = "Image",
                        supportedRoles = setOf(ModelRole.IMAGE),
                        promptPricePerToken = 0.0,
                        completionPricePerToken = 0.0,
                        imagePricePerUnit = 0.01,
                    ),
                    UiModelOption(
                        id = "vendor/image-token",
                        label = "Image Token",
                        supportedRoles = setOf(ModelRole.IMAGE),
                        promptPricePerToken = 0.000008,
                        imageOutputPricePerToken = 0.00003,
                    ),
                ),
            )
        val all = projection.basic + projection.expert
        val transcription = all.single { it.key == AppSettingKeys.STT_FALLBACK_MODEL }
        val image = all.single { it.key == BotSettingKeys.IMAGE_GENERATION_MODEL }

        assertTrue(transcription.options.single { it.first == "vendor/stt" }.second.contains("\$0.36/hour"))
        assertTrue(image.options.single { it.first == "vendor/image" }.second.contains("\$0.01/input image"))
        assertTrue(
            image.options.single { it.first == "vendor/image-token" }.second
                .contains("\$30.00/M generated image"),
        )
        assertFalse(image.options.single { it.first == "vendor/image" }.second.contains("\$0.00"))
    }

    @Test
    fun timezoneIsAnIanaDropdownWithTheCurrentValueFirst() {
        val projection =
            UiSettingsMapper.project(
                snapshotWith(BotSettingKeys.TIMEZONE, "Europe/Berlin"),
                emptyList(),
            )
        val timezone =
            (projection.basic + projection.expert).single { it.key == BotSettingKeys.TIMEZONE }

        assertEquals(UiSettingKind.TIMEZONE, timezone.kind)
        assertEquals("Europe/Berlin", timezone.options.first().first)
        assertTrue(timezone.options.any { it.first == "UTC" })
        assertTrue(timezone.options.any { it.first == "Asia/Tokyo" })
    }

    /**
     * The whole tzdb is around six hundred entries, which is a scroll through Africa to reach
     * Berlin. One zone per offset, each named by a city and stamped with the offset in force, is
     * the entire point of the curated list — so both halves are asserted, not just the length.
     */
    @Test
    fun timezoneOptionsStayShortAndSayWhereAndWhenTheyAre() {
        val projection =
            UiSettingsMapper.project(
                snapshotWith(BotSettingKeys.TIMEZONE, "Europe/Berlin"),
                emptyList(),
            )
        val timezone =
            (projection.basic + projection.expert).single { it.key == BotSettingKeys.TIMEZONE }

        assertTrue("${timezone.options.size} zones is a scroll, not a list", timezone.options.size <= 40)
        assertEquals(timezone.options.size, timezone.options.map { it.first }.distinct().size)
        // Every id is a real zone with a city, never a fixed offset: daylight saving has to keep
        // working or quiet hours drift by an hour every summer.
        assertTrue(timezone.options.none { it.first.startsWith("Etc/") })
        val berlin = timezone.options.first { it.first == "Europe/Berlin" }.second
        assertTrue(berlin, berlin.startsWith("UTC+0") && berlin.contains("Berlin"))
        // Below the pinned current value the list reads west to east, so scrolling it is a map.
        val offsets =
            timezone.options
                .drop(1)
                .map { ZoneId.of(it.first).rules.getOffset(Instant.now()).totalSeconds }
        assertEquals(offsets.sorted(), offsets)
    }

    /**
     * A model the catalog does not know must stay selectable, or an operator on a preview slug
     * loses their configuration the moment the picker opens.
     */
    @Test
    fun aSelectedModelMissingFromTheCatalogIsPinnedToTheTop() {
        val projection =
            UiSettingsMapper.project(
                snapshotWith(BotSettingKeys.MODEL, "vendor/preview-only"),
                listOf(
                    UiModelOption(
                        id = "vendor/known",
                        label = "Known",
                        supportedRoles = setOf(ModelRole.MAIN),
                        createdAtEpochSeconds = 1_767_225_600L,
                    ),
                ),
            )
        val main = (projection.basic + projection.expert).single { it.key == BotSettingKeys.MODEL }

        assertEquals("vendor/preview-only", main.options.first().first)
        assertTrue(main.options.first().second.contains("not in the catalogue"))
        assertEquals(listOf("vendor/preview-only", "vendor/known"), main.options.map { it.first })
    }

    @Test
    fun aSelectedModelPresentInTheCatalogIsNotDuplicated() {
        val projection =
            UiSettingsMapper.project(
                snapshotWith(BotSettingKeys.MODEL, "vendor/known"),
                listOf(
                    UiModelOption(
                        id = "vendor/known",
                        label = "Known",
                        supportedRoles = setOf(ModelRole.MAIN),
                        createdAtEpochSeconds = 1_767_225_600L,
                    ),
                ),
            )
        val main = (projection.basic + projection.expert).single { it.key == BotSettingKeys.MODEL }

        assertEquals(listOf("vendor/known"), main.options.map { it.first })
        assertFalse(main.options.single().second.contains("not in the catalogue"))
    }

    private fun snapshotWith(key: String, value: String): SettingsSnapshot {
        val defaults = defaultSnapshot()
        return SettingsSnapshot(
            revision = defaults.revision,
            global = defaults.global + (key to SettingValue.Text(value)),
            app = defaults.app,
            globalOverrideKeys = defaults.globalOverrideKeys,
            appOverrideKeys = defaults.appOverrideKeys,
            personaContactOverrides = defaults.personaContactOverrides,
            proactiveContactOverrides = defaults.proactiveContactOverrides,
            retainedLegacyKeys = defaults.retainedLegacyKeys,
        )
    }

    private fun defaultSnapshot(): SettingsSnapshot =
        SettingsSnapshot(
            revision = 0,
            global = BotSettingsSchema.defaults,
            app = AppSettingsSchema.defaults,
            globalOverrideKeys = emptySet(),
            appOverrideKeys = emptySet(),
            personaContactOverrides = emptyMap(),
            proactiveContactOverrides = emptyMap(),
            retainedLegacyKeys = emptySet(),
        )
}
