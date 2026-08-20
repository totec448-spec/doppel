package de.totec.doppel.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingInputParserTest {
    @Test
    fun `localized booleans and decimal commas use one grammar`() {
        val enabled = BotSettingsSchema.requireSpec(BotSettingKeys.ENABLED)
        val temperature = BotSettingsSchema.requireSpec(BotSettingKeys.TEMPERATURE)

        assertEquals(SettingValue.Bool(true), parseSettingInput(enabled, " ein "))
        assertEquals(SettingValue.Bool(false), parseSettingInput(enabled, "NEIN"))
        assertEquals(SettingValue.Decimal(0.65), parseSettingInput(temperature, "0,65"))
    }

    @Test
    fun `enum values are canonicalized without changing multiline prompts`() {
        val replyPreset = BotSettingsSchema.requireSpec(BotSettingKeys.REPLY_PRESET)
        val systemPrompt = BotSettingsSchema.requireSpec(BotSettingKeys.SYSTEM_PROMPT)

        assertEquals(SettingValue.Text("human"), parseSettingInput(replyPreset, " HUMAN "))
        assertEquals(
            SettingValue.Text("  first line\nsecond line  "),
            parseSettingInput(systemPrompt, "  first line\nsecond line  "),
        )
    }

    @Test
    fun `lists accept every operator delimiter and invalid values fail before persistence`() {
        val owners = AppSettingsSchema.requireSpec(AppSettingKeys.OWNER_NUMBERS)
        val maxTokens = BotSettingsSchema.requireSpec(BotSettingKeys.MAX_TOKENS)

        assertEquals(
            SettingValue.StringList.of(listOf("1", "2", "3")),
            parseSettingInput(owners, "1, 2;\n3"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseSettingInput(maxTokens, "not-a-number")
        }
    }
}
