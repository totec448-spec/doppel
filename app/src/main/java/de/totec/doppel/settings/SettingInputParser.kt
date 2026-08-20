package de.totec.doppel.settings

import java.util.Locale

/**
 * Canonical parser for operator-entered setting values.
 *
 * Compose and WhatsApp admin commands used to implement slightly different grammars. That made
 * the same visible value succeed through one surface and fail or change through the other. Both
 * now cross this boundary before [SettingsRepository] performs the final schema validation.
 */
fun parseSettingInput(
    spec: SettingSpec,
    raw: String,
): SettingValue {
    val value =
        when (spec.valueType) {
            SettingValueType.BOOLEAN ->
                SettingValue.Bool(
                    parseBooleanInput(raw)
                        ?: throw IllegalArgumentException("Expected on or off."),
                )

            SettingValueType.INTEGER ->
                SettingValue.Integer(
                    raw.trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Expected a whole number."),
                )

            SettingValueType.DECIMAL ->
                SettingValue.Decimal(
                    raw.trim().replace(',', '.').toDoubleOrNull()
                        ?: throw IllegalArgumentException("Expected a decimal number."),
                )

            SettingValueType.ENUM -> {
                val candidate = raw.trim()
                val canonical =
                    spec.options.firstOrNull { it.value == candidate }
                        ?: spec.options.firstOrNull { it.value.equals(candidate, ignoreCase = true) }
                        ?: throw IllegalArgumentException("Unknown option.")
                SettingValue.Text(canonical.value)
            }

            SettingValueType.TEXT -> {
                val candidate =
                    if (spec.control == SettingControl.MULTILINE_TEXT) raw else raw.trim()
                require(candidate.length <= MAX_SETTING_INPUT_CHARS) {
                    "The value is too long."
                }
                if (spec.control == SettingControl.MODEL_PICKER) {
                    require(candidate.isNotBlank()) { "The model must not be empty." }
                }
                SettingValue.Text(candidate)
            }

            SettingValueType.STRING_LIST ->
                SettingValue.StringList.of(raw.split(SETTING_LIST_SEPARATOR))

            SettingValueType.SECRET_REFERENCE ->
                throw IllegalArgumentException("Secrets are never handled as setting text.")
        }

    val validation = spec.validate(value)
    if (validation is SettingValidation.Invalid) {
        throw IllegalArgumentException(validation.reason)
    }
    return value
}

fun parseBooleanInput(raw: String): Boolean? =
    when (raw.trim().lowercase(Locale.ROOT)) {
        "true", "1", "on", "an", "ein", "yes", "ja" -> true
        "false", "0", "off", "aus", "no", "nein" -> false
        else -> null
    }

private const val MAX_SETTING_INPUT_CHARS = 100_000
private val SETTING_LIST_SEPARATOR = Regex("[,;\\n]+")
