package de.totec.doppel.ui

import de.totec.doppel.settings.BotSettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings page shows is not what the schema declares, and the difference is decided in
 * one function. Every rule in it is invisible from the schema side — a setting that quietly stops
 * being reachable looks exactly like a setting that was never added.
 */
class SettingsVisibilityTest {
    private fun setting(
        key: String,
        value: String,
        kind: UiSettingKind = UiSettingKind.TEXT,
        group: String = "Timing",
    ) = UiSetting(
        key = key,
        label = key,
        description = "",
        value = value,
        kind = kind,
        group = group,
        basic = true,
    )

    private fun state(preset: String) =
        AppUiState(
            basicSettings =
                listOf(
                    setting(BotSettingKeys.REPLY_PRESET, preset, UiSettingKind.ENUM),
                    setting(BotSettingKeys.SLEEP_START, "22:00", UiSettingKind.TIME),
                    setting(BotSettingKeys.SLEEP_END, "06:30", UiSettingKind.TIME),
                    setting(BotSettingKeys.TIMEZONE, "Europe/Berlin", UiSettingKind.TIMEZONE),
                    setting(BotSettingKeys.POWER_MODE, "low", UiSettingKind.ENUM, "Battery"),
                    setting(BotSettingKeys.LOW_LISTEN_MINUTES, "10", UiSettingKind.INTEGER, "Battery"),
                    setting(BotSettingKeys.MARK_READ, "true", UiSettingKind.BOOLEAN, "Human behaviour"),
                ),
        )

    @Test
    fun theTwoQuietHourTimesArriveAsOneRow() {
        val rows = visibleSettings(state("human"))
        val quiet = rows.single { it.key == BotSettingKeys.SLEEP_START }

        assertEquals(UiSettingKind.TIME_RANGE, quiet.kind)
        assertEquals("22:00 – 06:30", quiet.value)
        assertTrue(
            "the end time is edited through the merged row, not beside it",
            rows.none { it.key == BotSettingKeys.SLEEP_END },
        )
    }

    /** Instant answers at three in the morning as much as at noon; a night window would be a lie. */
    @Test
    fun theInstantPresetHidesTheNightAndTheZoneItIsMeasuredIn() {
        val keys = visibleSettings(state(INSTANT_PRESET)).map(UiSetting::key)

        assertFalse(BotSettingKeys.SLEEP_START in keys)
        assertFalse(BotSettingKeys.SLEEP_END in keys)
        assertFalse(BotSettingKeys.TIMEZONE in keys)
        assertTrue("only the night is hidden, not the page", BotSettingKeys.MARK_READ in keys)
    }

    /** They are shown in the Battery panel at the bottom; twice would be two places to set one value. */
    @Test
    fun theBatterySettingsAreNeverInTheCategoryList() {
        listOf("human", INSTANT_PRESET).forEach { preset ->
            val keys = visibleSettings(state(preset)).map(UiSetting::key)
            assertFalse(BotSettingKeys.POWER_MODE in keys)
            assertFalse(BotSettingKeys.LOW_LISTEN_MINUTES in keys)
        }
    }
}
