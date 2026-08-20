package de.totec.doppel.settings

import java.math.BigDecimal
import java.util.Collections

/**
 * Storage and UI types for the canonical settings contract.
 *
 * The types are deliberately Android-free. Compose, SQLite, a remote bridge, and
 * tests all consume the same metadata without duplicating validation rules.
 */
enum class SettingValueType {
    TEXT,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    ENUM,
    STRING_LIST,
    SECRET_REFERENCE,
}

enum class SettingTier {
    BASIC,
    EXPERT,
    HIDDEN,
}

enum class SettingGroup {
    STATUS,
    CONNECTION,
    /** How much battery the link is allowed to spend staying reachable. */
    BATTERY,
    ACCESS,
    MODELS,
    PERSONA,
    GENERATION,
    CONTEXT_MEMORY,
    TIMING,
    PROACTIVITY,
    REALISM,
    MEDIA,
    VOICE,
    PRIVACY,
    SAFETY,
    NETWORK,
    LOGGING,
}

enum class SettingControl {
    SWITCH,
    TEXT_FIELD,
    MULTILINE_TEXT,
    NUMBER_FIELD,
    SLIDER,
    STEPPED_SLIDER,
    DROPDOWN,
    MODEL_PICKER,
    PERSONA_PICKER,
    VOICE_PICKER,
    TIME_PICKER,
    TIMEZONE_PICKER,
    URL_FIELD,
    CHIP_LIST,
    SECRET_REFERENCE,
}

enum class ModelRole {
    MAIN,
    MEDIA,
    IMAGE,
    VERIFIER,
    TTS,

    /**
     * Reads a voice note the media model cannot. It used to be called LEGACY_STT because the only
     * thing it could hold was a dedicated `/audio/transcriptions` endpoint; the picker now also
     * offers every OpenRouter model that takes audio, so there is nothing legacy about it.
     */
    TRANSCRIBE,
}

sealed interface SettingValue {
    data class Text(
        val value: String,
    ) : SettingValue

    data class Bool(
        val value: Boolean,
    ) : SettingValue

    data class Integer(
        val value: Int,
    ) : SettingValue

    data class Decimal(
        val value: Double,
    ) : SettingValue {
        init {
            require(value.isFinite()) { "Decimal settings must be finite" }
        }
    }

    class StringList private constructor(
        val values: List<String>,
    ) : SettingValue {
        override fun equals(other: Any?): Boolean =
            other is StringList && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "StringList(values=$values)"

        companion object {
            fun of(values: Iterable<String>): StringList =
                StringList(
                    Collections.unmodifiableList(
                        values.map(String::trim).filter(String::isNotEmpty).distinct(),
                    ),
                )
        }
    }

    /**
     * A logical pointer into the Android Keystore-backed secret store.
     *
     * It is never a credential and deliberately cannot carry a secret value.
     */
    data class SecretReference(
        val name: String,
    ) : SettingValue {
        init {
            require(SECRET_REFERENCE_PATTERN.matches(name)) {
                "Secret references must be logical names, never credential values"
            }
        }
    }
}

data class NumericBounds(
    val min: Double? = null,
    val max: Double? = null,
) {
    init {
        require(min == null || min.isFinite())
        require(max == null || max.isFinite())
        require(min == null || max == null || min <= max)
    }

    fun contains(value: Double): Boolean =
        value.isFinite() &&
            (min == null || value >= min) &&
            (max == null || value <= max)
}

data class SettingOption(
    val value: String,
    val label: String,
    val description: String = "",
)

data class SettingSpec(
    val key: String,
    val valueType: SettingValueType,
    val defaultValue: SettingValue,
    val description: String,
    val tier: SettingTier,
    val group: SettingGroup,
    val control: SettingControl,
    val bounds: NumericBounds? = null,
    val options: List<SettingOption> = emptyList(),
    val modelRole: ModelRole? = null,
    val environmentKey: String? = null,
    val legacyAliases: Set<String> = emptySet(),
    val recommendedValuesOnly: Boolean = false,
    val sensitiveReference: Boolean = false,
) {
    init {
        require(SETTING_KEY_PATTERN.matches(key)) { "Invalid setting key: $key" }
        require(description.isNotBlank()) { "Every setting requires a description" }
        require(matchesType(defaultValue, valueType)) {
            "Default type mismatch for $key: ${typeOf(defaultValue)} != $valueType"
        }
        require(options.map(SettingOption::value).distinct().size == options.size) {
            "Duplicate option in $key"
        }
        require(valueType == SettingValueType.ENUM || !recommendedValuesOnly || options.isNotEmpty()) {
            "Recommended-only settings need options"
        }
        require(valueType != SettingValueType.ENUM || options.isNotEmpty()) {
            "Enum setting $key needs options"
        }
        require(!sensitiveReference || valueType == SettingValueType.SECRET_REFERENCE)
        validate(defaultValue).also { result ->
            require(result is SettingValidation.Valid) {
                "Invalid default for $key: ${(result as? SettingValidation.Invalid)?.reason}"
            }
        }
    }

    fun validate(value: SettingValue): SettingValidation {
        if (!matchesType(value, valueType)) {
            return SettingValidation.Invalid(
                "expected ${valueType.name.lowercase()}, got ${typeOf(value).name.lowercase()}",
            )
        }
        val numeric =
            when (value) {
                is SettingValue.Integer -> value.value.toDouble()
                is SettingValue.Decimal -> value.value
                else -> null
            }
        if (numeric != null && bounds?.contains(numeric) == false) {
            return SettingValidation.Invalid(
                "value $numeric is outside ${bounds.min ?: "-∞"}..${bounds.max ?: "∞"}",
            )
        }
        if (valueType == SettingValueType.ENUM) {
            val raw = (value as SettingValue.Text).value
            if (options.none { it.value == raw }) {
                return SettingValidation.Invalid(
                    "allowed values: ${options.joinToString { it.value }}",
                )
            }
        }
        if (value is SettingValue.SecretReference &&
            !SECRET_REFERENCE_PATTERN.matches(value.name)
        ) {
            return SettingValidation.Invalid("invalid secret reference")
        }
        return SettingValidation.Valid
    }
}

sealed interface SettingValidation {
    data object Valid : SettingValidation

    data class Invalid(
        val reason: String,
    ) : SettingValidation
}

data class PersonaCatalogEntry(
    val key: String,
    val label: String,
    val emoji: String,
    val customEditor: Boolean = false,
)

data class VoiceCatalogEntry(
    val name: String,
    val description: String,
)

internal fun typeOf(value: SettingValue): SettingValueType =
    when (value) {
        is SettingValue.Text -> SettingValueType.TEXT
        is SettingValue.Bool -> SettingValueType.BOOLEAN
        is SettingValue.Integer -> SettingValueType.INTEGER
        is SettingValue.Decimal -> SettingValueType.DECIMAL
        is SettingValue.StringList -> SettingValueType.STRING_LIST
        is SettingValue.SecretReference -> SettingValueType.SECRET_REFERENCE
    }

private fun matchesType(
    value: SettingValue,
    expected: SettingValueType,
): Boolean =
    when (expected) {
        SettingValueType.ENUM -> value is SettingValue.Text
        else -> typeOf(value) == expected
    }

internal fun SettingValue.encode(): String =
    when (this) {
        is SettingValue.Text -> value
        is SettingValue.Bool -> value.toString()
        is SettingValue.Integer -> value.toString()
        is SettingValue.Decimal ->
            BigDecimal.valueOf(value)
                .stripTrailingZeros()
                .toPlainString()
        is SettingValue.StringList -> values.joinToString("\n")
        is SettingValue.SecretReference -> name
    }

internal fun immutableSettingMap(
    source: Map<String, SettingValue>,
): Map<String, SettingValue> =
    Collections.unmodifiableMap(LinkedHashMap(source))

internal fun <V> immutableMap(source: Map<String, V>): Map<String, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))

internal fun immutableStringSet(source: Set<String>): Set<String> =
    Collections.unmodifiableSet(LinkedHashSet(source))

private val SETTING_KEY_PATTERN = Regex("[a-z][a-z0-9_]{0,127}")
private val SECRET_REFERENCE_PATTERN = Regex("[a-z][a-z0-9_.-]{0,127}")
