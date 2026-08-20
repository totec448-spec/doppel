package de.totec.doppel.app

import de.totec.doppel.engine.ConversationMemoryPolicy
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.AppSettingsSchema
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.BotSettingsSchema
import de.totec.doppel.settings.ModelRole
import de.totec.doppel.settings.NumericBounds
import de.totec.doppel.settings.SettingControl
import de.totec.doppel.settings.SettingGroup
import de.totec.doppel.settings.SettingSpec
import de.totec.doppel.settings.SettingTier
import de.totec.doppel.settings.SettingValue
import de.totec.doppel.settings.SettingValueType
import de.totec.doppel.settings.SettingsCatalogs
import de.totec.doppel.settings.SettingsSnapshot
import de.totec.doppel.settings.TtsVoiceCatalog
import de.totec.doppel.ui.UiSetting
import de.totec.doppel.ui.UiSettingKind
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal data class UiSettingsProjection(
    val basic: List<UiSetting>,
    val expert: List<UiSetting>,
)

/**
 * The settings whose stored unit is not the unit anybody thinks in.
 *
 * `max_video_bytes` is a byte count, and has to stay one: the same name and the same number live in
 * the shared `.env` as `MEDIA_MAX_VIDEO_BYTES`, so re-basing the stored value would silently change
 * what an imported bridge configuration means. On a phone screen the byte count was unusable — it
 * arrived as "8388608" in a field with a number keypad, which nobody can read at a glance and
 * nobody can type a sensible new value into. The megabytes therefore exist only in the UI: they are
 * projected out here, and multiplied back to bytes on the way in.
 */
internal object MegabyteSettings {
    private const val BYTES_PER_MEGABYTE = 1_024L * 1_024L

    fun applies(key: String): Boolean = key == AppSettingKeys.MAX_VIDEO_BYTES

    /** Bytes as they are stored → whole megabytes as they are shown. */
    fun toDisplay(
        key: String,
        storedValue: String,
    ): String {
        if (!applies(key)) return storedValue
        val bytes = storedValue.trim().toLongOrNull() ?: return storedValue
        return Math.round(bytes.toDouble() / BYTES_PER_MEGABYTE).toString()
    }

    fun toDisplay(
        key: String,
        bounds: NumericBounds?,
    ): NumericBounds? {
        if (!applies(key) || bounds == null) return bounds
        return NumericBounds(
            min = bounds.min?.div(BYTES_PER_MEGABYTE),
            max = bounds.max?.div(BYTES_PER_MEGABYTE),
        )
    }

    /** Megabytes as they were entered → bytes as they are stored. */
    fun toStored(
        key: String,
        enteredValue: String,
    ): String {
        if (!applies(key)) return enteredValue
        val megabytes = enteredValue.trim().toLongOrNull() ?: return enteredValue
        return (megabytes * BYTES_PER_MEGABYTE).toString()
    }
}

/**
 * Pure projection from the canonical settings schema into UI metadata.
 * Validation and defaults remain owned by SettingsRepository.
 */
internal object UiSettingsMapper {
    fun project(
        snapshot: SettingsSnapshot,
        catalog: List<UiModelOption>,
    ): UiSettingsProjection {
        val selectedModelIds =
            mapOf(
                ModelRole.MAIN to snapshot.text(BotSettingKeys.MODEL),
                ModelRole.MEDIA to snapshot.text(BotSettingKeys.MEDIA_MODEL),
                ModelRole.IMAGE to snapshot.text(BotSettingKeys.IMAGE_GENERATION_MODEL),
                ModelRole.VERIFIER to snapshot.text(BotSettingKeys.VERIFY_MODEL),
                ModelRole.TTS to snapshot.text(BotSettingKeys.TTS_MODEL),
                ModelRole.TRANSCRIBE to snapshot.appText(AppSettingKeys.STT_FALLBACK_MODEL),
            )
        val memoryWindowNote = memoryWindowNote(snapshot)
        val settings =
            buildList {
                BotSettingsSchema.all
                    .filterNot(::hasItsOwnControl)
                    .forEach { spec ->
                        add(
                            spec.toUi(
                                value = snapshot.value(spec.key),
                                overridden = spec.key in snapshot.globalOverrideKeys,
                                catalog = catalog,
                                selectedModelIds = selectedModelIds,
                                memoryWindowNote = memoryWindowNote,
                            ),
                        )
                    }
                AppSettingsSchema.all
                    .asSequence()
                    .filterNot(::belongsOnDedicatedScreen)
                    .forEach { spec ->
                        add(
                            spec.toUi(
                                value = snapshot.appValue(spec.key),
                                overridden = spec.key in snapshot.appOverrideKeys,
                                catalog = catalog,
                                selectedModelIds = selectedModelIds,
                            ),
                        )
                    }
            }
        return UiSettingsProjection(
            basic = settings.filter(UiSetting::basic),
            expert = settings.filterNot(UiSetting::basic),
        )
    }

    /**
     * The one line that makes the two memory numbers legible: they add up.
     *
     * Computed from the live snapshot rather than written into the schema, because the whole point
     * is showing the sum the *current* pair produces.
     */
    private fun memoryWindowNote(snapshot: SettingsSnapshot): String {
        val retained =
            ConversationMemoryPolicy.retainedMessages(
                snapshot.integer(BotSettingKeys.HISTORY_LIMIT),
            )
        val interval =
            ConversationMemoryPolicy.refreshInterval(
                snapshot.integer(BotSettingKeys.MEMORY_INTERVAL),
            )
        val window = retained + interval
        val written = snapshot.boolean(BotSettingKeys.MEMORY_CHAT_ENABLED)
        val base =
            if (retained == 0) {
                "Right now: every stored message stays in the window, the window turns over " +
                    "every $interval new messages. Without trimming the prompt keeps growing " +
                    "forever."
            } else {
                "Right now: $retained kept + $interval new = $window messages in the window. " +
                    "Every $interval messages it snaps back to the $retained kept ones and " +
                    "grows to $window again."
            }
        return if (written) {
            base
        } else {
            "$base Chat memory is off, so what drops out of the window is forgotten rather " +
                "than summarised."
        }
    }

    private fun SettingSpec.toUi(
        value: SettingValue,
        overridden: Boolean,
        catalog: List<UiModelOption>,
        selectedModelIds: Map<ModelRole, String>,
        memoryWindowNote: String = "",
    ): UiSetting {
        val selectedTtsModel = catalog.firstOrNull { it.id == selectedModelIds[ModelRole.TTS] }
        val reasoningModelRole = reasoningModelRole(key)
        val selectedReasoningModel =
            reasoningModelRole?.let { role ->
                catalog.firstOrNull { it.id == selectedModelIds[role] }
            }
        val kind =
            if (
                reasoningModelRole != null ||
                key == BotSettingKeys.MAX_SENDS_PER_DAY ||
                key == BotSettingKeys.IMAGE_GENERATION_QUALITY
            ) {
                UiSettingKind.ENUM_SLIDER
            } else {
                uiKind()
            }
        val valueText = MegabyteSettings.toDisplay(key, value.asUiText())
        val availableOptions =
            when {
                control == SettingControl.MODEL_PICKER -> modelOptions(valueText, catalog)
                control == SettingControl.PERSONA_PICKER ->
                    SettingsCatalogs.personas.map { it.key to "${it.emoji} ${it.label}" }
                control == SettingControl.VOICE_PICKER ->
                    voiceOptions(selectedModelIds[ModelRole.TTS].orEmpty(), selectedTtsModel, valueText)
                control == SettingControl.TIMEZONE_PICKER -> timezoneOptions(valueText)
                key in REASONING_SETTING_KEYS ->
                    reasoningOptions(selectedReasoningModel, valueText)
                // Any spec that carries options offers them, not just enums: a numeric dropdown
                // used to project an empty menu, which made the setting unchangeable in the UI.
                // Numeric options are suggested steps rather than a whitelist, so a value between
                // two of them — from an import or an admin command — stays visible and selected.
                options.isNotEmpty() -> {
                    val offered = options.map { it.value to it.label }
                    val withCurrent =
                        if (offered.any { it.first == valueText }) {
                            offered
                        } else {
                            offered + (valueText to "$valueText · current")
                        }
                    if (key == BotSettingKeys.MAX_SENDS_PER_DAY) {
                        withCurrent.sortedBy { it.first.toDoubleOrNull() ?: Double.MAX_VALUE }
                    } else {
                        withCurrent
                    }
                }
                else -> emptyList()
            }
        val numericBounds =
            MegabyteSettings.toDisplay(key, bounds)?.takeIf { range ->
                val min = range.min
                val max = range.max
                min != null &&
                    max != null &&
                    max - min <= MAX_SLIDER_RANGE
            }
        return UiSetting(
            key = key,
            label = settingLabel(key),
            description =
                when {
                    key in REASONING_SETTING_KEYS ->
                        reasoningDescription(selectedReasoningModel, reasoningModelRole)
                    // Both halves of the window carry the same computed sum, so it is right there
                    // whichever of the two the user opened.
                    key == BotSettingKeys.HISTORY_LIMIT ||
                        key == BotSettingKeys.MEMORY_INTERVAL ||
                        key == BotSettingKeys.MEMORY_CHAT_ENABLED ->
                        listOf(description, memoryWindowNote)
                            .filter(String::isNotBlank)
                            .joinToString("\n\n")
                    else -> description
                },
            value = valueText,
            kind = kind,
            group = groupLabel(group),
            basic = tier == SettingTier.BASIC,
            options = availableOptions,
            minimum = numericBounds?.min,
            maximum = numericBounds?.max,
            overridden = overridden,
        )
    }

    private fun SettingSpec.modelOptions(
        selected: String,
        catalog: List<UiModelOption>,
    ): List<Pair<String, String>> {
        val requiredRole = modelRole
        val compatible =
            catalog
                .asSequence()
                .filter { requiredRole == null || requiredRole in it.supportedRoles }
                .distinctBy(UiModelOption::id)
                .sortedWith(
                    compareByDescending<UiModelOption> {
                        it.createdAtEpochSeconds ?: Long.MIN_VALUE
                    }.thenBy { it.label.lowercase(Locale.ROOT) },
                )
                .map { option ->
                    // The name alone is the row; price and release date are what actually decide
                    // between two models, so they go on a second line in that order. The raw slug
                    // used to be repeated under every row and told the reader nothing the name did
                    // not already say — search still matches it.
                    val meta =
                        listOfNotNull(
                            option.pricingLabel(requiredRole),
                            option.createdAtEpochSeconds?.let {
                                java.time.Instant.ofEpochSecond(it)
                                    .atZone(java.time.ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                            },
                        ).joinToString(" · ")
                    option.id to if (meta.isEmpty()) option.label else "${option.label}\n$meta"
                }
                .toList()
        return if (compatible.any { it.first == selected }) {
            compatible
        } else {
            listOf(selected to "$selected · current, not in the catalogue") + compatible
        }
    }

    /**
     * Only voices the selected TTS path can actually accept.
     *
     * The old fallback listed the Gemini voice catalog for every model, so choosing an OpenAI or
     * Grok model still offered "Kore" — which the send path then silently rewrote. Provider-declared
     * voices win; otherwise the same static tables the send path uses answer the question.
     */
    private fun voiceOptions(
        modelId: String,
        model: UiModelOption?,
        selected: String,
    ): List<Pair<String, String>> {
        val available = TtsVoiceCatalog.knownVoices(modelId, model?.supportedVoices)
        if (available.isEmpty()) {
            // Free-form provider (or the local engine): keep the descriptive catalog and let the
            // current value stand rather than pretending to know the allowed set.
            val catalog = SettingsCatalogs.voices.map { it.name to "${it.name} · ${it.description}" }
            return if (selected.isBlank() || catalog.any { it.first == selected }) {
                catalog
            } else {
                listOf(selected to "$selected · current") + catalog
            }
        }
        val descriptions = SettingsCatalogs.voices.associate { it.name to it.description }
        val options = available.map { voice ->
            voice to (descriptions[voice]?.let { "$voice · $it" } ?: voice)
        }
        return if (
            selected.isBlank() || options.any { it.first.equals(selected, ignoreCase = true) }
        ) {
            options
        } else {
            listOf(selected to "$selected · current, no longer reported") + options
        }
    }

    /** Region IDs stay valid IANA values while legacy aliases are kept out of the picker. */
    private fun timezoneOptions(selected: String): List<Pair<String, String>> =
        buildList {
            if (selected.isNotBlank()) add(selected to "${timezoneLabel(selected)} · current")
            TIMEZONE_IDS.filterNot { it == selected }.forEach { add(it to timezoneLabel(it)) }
        }

    /**
     * `UTC+02:00 · Berlin` — the offset that is in force right now, not the standard one, because
     * half the year the standard offset is a lie and the point of the label is to be checkable
     * against a clock.
     */
    private fun timezoneLabel(zoneId: String): String {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return zoneId
        val offset = zone.rules.getOffset(Instant.now()).id.let { if (it == "Z") "+00:00" else it }
        val city = zoneId.substringAfterLast('/').replace('_', ' ')
        return if (zoneId == "UTC") "UTC±00:00" else "UTC$offset · $city"
    }

    private fun reasoningOptions(
        model: UiModelOption?,
        selected: String,
    ): List<Pair<String, String>> {
        val providerEfforts = model?.reasoningEfforts
        val efforts =
            when {
                providerEfforts != null ->
                    providerEfforts.sortedBy { ALL_REASONING_EFFORTS.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                model == null -> ALL_REASONING_EFFORTS
                else -> emptyList()
            }
        val values = buildList {
            if (model?.reasoningMandatory != true) {
                add("off" to "Provider default")
                if ("none" in efforts || providerEfforts == null) add("none" to "Off")
            }
            efforts.forEach { effort -> add(effort to reasoningLabel(effort)) }
        }.distinctBy { it.first }
        val available = values.ifEmpty { listOf("off" to "Provider default") }
        return if (
            selected.isBlank() || available.any { it.first.equals(selected, ignoreCase = true) }
        ) {
            available
        } else {
            listOf(selected to "${reasoningLabel(selected)} · current, unsupported") + available
        }
    }

    private fun reasoningDescription(
        model: UiModelOption?,
        role: ModelRole?,
    ): String =
        when {
            model == null ->
                "Reasoning level of the ${role?.displayName() ?: "selected"} model. Open its model picker to load the catalogue."
            model.reasoningEfforts == null ->
                "${model.label} publishes no selectable reasoning levels; the provider default stays active."
            model.reasoningMandatory ->
                "${model.label} requires reasoning. Only the levels OpenRouter reports are shown."
            else ->
                "Only the reasoning levels OpenRouter reports for ${model.label}."
        }

    private fun reasoningModelRole(key: String): ModelRole? =
        when (key) {
            BotSettingKeys.REASONING_EFFORT -> ModelRole.MAIN
            BotSettingKeys.MEDIA_REASONING_EFFORT -> ModelRole.MEDIA
            BotSettingKeys.VERIFY_REASONING_EFFORT -> ModelRole.VERIFIER
            else -> null
        }

    private fun ModelRole.displayName(): String =
        when (this) {
            ModelRole.MAIN -> "main"
            ModelRole.MEDIA -> "media"
            ModelRole.IMAGE -> "image generation"
            ModelRole.VERIFIER -> "check"
            ModelRole.TTS -> "speech"
            ModelRole.TRANSCRIBE -> "transcription"
        }

    private fun UiModelOption.pricingLabel(role: ModelRole?): String? {
        val input = promptPricePerToken.validPrice()
        val output = completionPricePerToken.validPrice()
        return when (role) {
            ModelRole.TRANSCRIBE ->
                transcriptionPricePerHour.validPrice()?.let { "${usd(it)}/hour" }
                    ?: tokenPricing(input, output)
            ModelRole.IMAGE ->
                buildList {
                    imagePricePerUnit.validPrice()?.let { add("${usd(it)}/input image") }
                    input?.let { add("${perMillion(it)}/M input") }
                    imageOutputPricePerToken.validPrice()
                        ?.let { add("${perMillion(it)}/M generated image") }
                    if (isEmpty()) {
                        requestPrice.validPrice()?.let { add("${usd(it)}/request") }
                    }
                }.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
            else -> tokenPricing(input, output)
        }
    }

    private fun tokenPricing(input: Double?, output: Double?): String? =
        buildList {
            input?.let { add("${perMillion(it)}/M in") }
            output?.let { add("${perMillion(it)}/M out") }
        }.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")

    private fun Double?.validPrice(): Double? =
        this?.takeIf { it.isFinite() && it > 0.0 }

    private fun perMillion(perToken: Double): String =
        "$" + String.format(Locale.US, "%.2f", perToken * 1_000_000.0)

    private fun usd(value: Double): String {
        val raw = String.format(Locale.US, "%.4f", value)
        val compact = raw.trimEnd('0').trimEnd('.')
        val decimalPlaces = compact.substringAfter('.', "").length
        return "$" + if (decimalPlaces >= 2) compact else compact + "." + "0".repeat(2 - decimalPlaces)
    }

    private fun reasoningLabel(value: String): String =
        when (value) {
            "off" -> "Provider default"
            "none" -> "Off"
            "minimal" -> "Minimal"
            "low" -> "Low"
            "medium" -> "Medium"
            "high" -> "High"
            "xhigh" -> "Very high"
            "max" -> "Maximum"
            else -> value
        }

    private fun SettingSpec.uiKind(): UiSettingKind =
        when {
            control == SettingControl.MODEL_PICKER -> UiSettingKind.MODEL
            control in
                setOf(
                    SettingControl.DROPDOWN,
                    SettingControl.PERSONA_PICKER,
                    SettingControl.VOICE_PICKER,
                ) -> UiSettingKind.ENUM
            control == SettingControl.MULTILINE_TEXT -> UiSettingKind.MULTILINE
            control == SettingControl.TIME_PICKER -> UiSettingKind.TIME
            control == SettingControl.TIMEZONE_PICKER -> UiSettingKind.TIMEZONE
            valueType == SettingValueType.BOOLEAN -> UiSettingKind.BOOLEAN
            valueType == SettingValueType.INTEGER -> UiSettingKind.INTEGER
            valueType == SettingValueType.DECIMAL -> UiSettingKind.DECIMAL
            else -> UiSettingKind.TEXT
        }

    private fun SettingValue.asUiText(): String =
        when (this) {
            is SettingValue.Bool -> value.toString()
            is SettingValue.Decimal -> value.toString()
            is SettingValue.Integer -> value.toString()
            is SettingValue.SecretReference -> ""
            is SettingValue.StringList -> values.joinToString("\n")
            is SettingValue.Text -> value
        }

    /**
     * Settings the app already exposes as a purpose-built control, so listing them again as a
     * generic row would be two switches for one value: the connected state already owns the
     * global runtime switch.
     */
    private fun hasItsOwnControl(spec: SettingSpec): Boolean =
        spec.key == BotSettingKeys.ENABLED

    private fun belongsOnDedicatedScreen(spec: SettingSpec): Boolean =
        spec.valueType == SettingValueType.SECRET_REFERENCE ||
            spec.valueType == SettingValueType.STRING_LIST

    private fun groupLabel(group: SettingGroup): String =
        when (group) {
            SettingGroup.STATUS -> "Status"
            SettingGroup.CONNECTION -> "Connection"
            SettingGroup.BATTERY -> "Battery"
            SettingGroup.ACCESS -> "Access"
            SettingGroup.MODELS -> "Models"
            SettingGroup.PERSONA -> "Persona"
            SettingGroup.GENERATION -> "Reply style"
            SettingGroup.CONTEXT_MEMORY -> "Context & memory"
            SettingGroup.TIMING -> "Timing"
            SettingGroup.PROACTIVITY -> "Proactivity"
            SettingGroup.REALISM -> "Human behaviour"
            SettingGroup.MEDIA -> "Media"
            SettingGroup.VOICE -> "Voice messages"
            SettingGroup.PRIVACY -> "Privacy"
            SettingGroup.SAFETY -> "Safety"
            SettingGroup.NETWORK -> "Network"
            SettingGroup.LOGGING -> "Log"
        }

    /**
     * How often a category actually gets opened, which is not the order the schema declares them
     * in. Models, persona and timing are touched constantly; connection is essential and never
     * looked at once it works, so it sits at the very bottom rather than near the top.
     *
     * Battery is in the list for completeness only — its two settings are shown in the Battery
     * panel at the bottom of the page rather than as a category, next to the Android permission
     * that decides whether either of them can work.
     *
     * An unlisted group sorts to the end instead of failing, so adding a [SettingGroup] cannot
     * break the settings list.
     */
    private val GROUP_ORDER =
        listOf(
            SettingGroup.MODELS,
            SettingGroup.PERSONA,
            SettingGroup.TIMING,
            SettingGroup.PROACTIVITY,
            SettingGroup.REALISM,
            SettingGroup.VOICE,
            SettingGroup.GENERATION,
            SettingGroup.MEDIA,
            SettingGroup.CONTEXT_MEMORY,
            SettingGroup.ACCESS,
            SettingGroup.PRIVACY,
            SettingGroup.SAFETY,
            SettingGroup.NETWORK,
            SettingGroup.LOGGING,
            SettingGroup.CONNECTION,
            SettingGroup.BATTERY,
            SettingGroup.STATUS,
        ).map(::groupLabel)

    /** Sort key for a group label as [UiSetting.group] carries it. */
    fun groupRank(label: String): Int =
        GROUP_ORDER.indexOf(label).takeIf { it >= 0 } ?: GROUP_ORDER.size

    /** Every category label there is, in display order. The UI pins an icon to each of them. */
    val groupLabels: List<String> get() = GROUP_ORDER

    private fun settingLabel(key: String): String =
        LABELS[key]
            ?: key
                .split('_')
                .joinToString(" ") { token -> TOKEN_LABELS[token] ?: token }
                .replaceFirstChar { it.titlecase(Locale.ROOT) }

    private const val MAX_SLIDER_RANGE = 1_000.0

    private val REASONING_SETTING_KEYS =
        setOf(
            BotSettingKeys.REASONING_EFFORT,
            BotSettingKeys.MEDIA_REASONING_EFFORT,
            BotSettingKeys.VERIFY_REASONING_EFFORT,
        )

    private val ALL_REASONING_EFFORTS =
        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    private val LABELS =
        mapOf(
            BotSettingKeys.ENABLED to "Replies enabled",
            BotSettingKeys.MODEL to "Main model",
            BotSettingKeys.MEDIA_MODEL to "Media model (vision, STT & video)",
            BotSettingKeys.FIRST_PARTY_PROVIDER_ONLY to "Prefer the model maker",
            BotSettingKeys.VISION_ENABLED to "Understand images",
            BotSettingKeys.STT_ENABLED to "Understand voice messages",
            BotSettingKeys.VIDEO_ENABLED to "Understand videos",
            BotSettingKeys.BLOCK_REPEAT_IMAGES to "Never send an image twice",
            BotSettingKeys.VERIFY_MODEL to "Check model",
            BotSettingKeys.PERSONALITY to "Persona",
            BotSettingKeys.BASE_PROMPT to "Base prompt",
            BotSettingKeys.SYSTEM_PROMPT to "System prompt",
            BotSettingKeys.TEMPERATURE to "Creativity",
            BotSettingKeys.REASONING_EFFORT to "Main model: reasoning effort",
            BotSettingKeys.MEDIA_REASONING_EFFORT to "Media model: reasoning effort",
            BotSettingKeys.IMAGE_ANALYSIS_PROMPT to "Image analysis prompt",
            BotSettingKeys.VIDEO_ANALYSIS_PROMPT to "Video analysis prompt",
            BotSettingKeys.VOICE_ANALYSIS_PROMPT to "Voice analysis prompt",
            BotSettingKeys.HISTORY_LIMIT to "Messages kept",
            BotSettingKeys.MEMORY_INTERVAL to "Memory interval",
            BotSettingKeys.MEMORY_CHAT_ENABLED to "Chat memory",
            BotSettingKeys.MEMORY_GLOBAL_ENABLED to "Global memory",
            BotSettingKeys.MEMORY_GLOBAL_INTERVAL to "Chat memories per global memory",
            BotSettingKeys.HISTORY_TIMESTAMPS to "Timestamps in the history",
            BotSettingKeys.BATCH_WINDOW_MS to "Bundling window",
            BotSettingKeys.REPLY_PRESET to "Reply timing",
            BotSettingKeys.TYPING_SPEED_WPM to "Typing speed (words/minute)",
            BotSettingKeys.READING_SPEED_WPS to "Reading speed (words/second)",
            BotSettingKeys.TYPING_STOP_DELAY_MS to "Typing-stop delay (ms)",
            BotSettingKeys.PROACTIVE_LEVEL to "Proactivity",
            BotSettingKeys.TTS_ENABLED to "Voice messages",
            BotSettingKeys.TTS_MODEL to "Speech model",
            BotSettingKeys.TTS_VOICE to "Fallback voice",
            BotSettingKeys.TTS_QUALITY to "Speech quality",
            BotSettingKeys.MOOD_ENABLED to "Mood",
            BotSettingKeys.PROFILE_PICTURE_ENABLED to "Change profile picture with persona",
            BotSettingKeys.PROFILE_PICTURE_INTERVAL_DAYS to "Profile picture interval (days)",
            BotSettingKeys.MARK_READ to "Mark as read",
            BotSettingKeys.ENABLE_REACTIONS to "Reactions",
            BotSettingKeys.ENABLE_QUOTE_REPLY to "Quote when replying",
            BotSettingKeys.AI_DISCLOSURE_ENABLED to "Tell new chats it is an AI",
            BotSettingKeys.AI_DISCLOSURE_TEXT to "AI notice text",
            BotSettingKeys.SLEEP_START to "Quiet hours from",
            BotSettingKeys.SLEEP_END to "Quiet hours until",
            BotSettingKeys.POWER_MODE to "Battery use",
            BotSettingKeys.LOW_LISTEN_MINUTES to "Keep listening for (minutes)",
            BotSettingKeys.TIMEZONE to "Time zone",
            BotSettingKeys.AUTOBLOCK_ENABLED to "Flood auto-block",
            AppSettingKeys.AUTOSTART to "Start after reboot",
            AppSettingKeys.OPENROUTER_BASE_URL to "API address",
            AppSettingKeys.OPENROUTER_REFERER to "API attribution URL",
            AppSettingKeys.OPENROUTER_TITLE to "API app name",
            AppSettingKeys.OPENROUTER_TIMEOUT_MS to "Provider timeout",
            AppSettingKeys.COMMAND_PREFIX to "Admin command prefix",
            AppSettingKeys.GROUP_TRIGGER to "Group trigger",
            AppSettingKeys.ALLOW_ALL to "Allow every person and group",
            // Everything below would otherwise fall back to the token splitter and surface as
            // half-readable text like "Stt fallback url" or "Trait chaos".
            BotSettingKeys.MAX_TOKENS to "Maximum reply length",
            BotSettingKeys.TOP_P to "Top-p (word choice)",
            BotSettingKeys.FREQUENCY_PENALTY to "Repetition brake",
            BotSettingKeys.PRESENCE_PENALTY to "Topic-change bonus",
            BotSettingKeys.HISTORY_RETENTION to "Keep history",
            BotSettingKeys.MEMORY_CHAR_LIMIT to "Memory character limit",
            BotSettingKeys.CROSS_CHAT_SEARCH to "Cross-chat search",
            BotSettingKeys.SELF_EDIT_ENABLED to "Correct her own messages",
            BotSettingKeys.SELF_EDIT_CHANCE to "Correction probability",
            BotSettingKeys.SELF_EDIT_DELAY_DIVISOR to "Correction speed (divisor)",
            BotSettingKeys.PROACTIVE_MODE to "Proactive mode",
            BotSettingKeys.PROACTIVE_DAILY_CAP to "Proactive: maximum per day",
            BotSettingKeys.PROACTIVE_REPLY_RATIO_MAX to "Proactive: reply-ratio limit",
            BotSettingKeys.MAX_SENDS_PER_HOUR to "Maximum sends per hour",
            BotSettingKeys.MAX_SENDS_PER_DAY to "Maximum sends per day",
            AppSettingKeys.RATE_LIMIT_MAX to "Rate limit: messages",
            AppSettingKeys.RATE_LIMIT_WINDOW_MS to "Rate limit: time window",
            AppSettingKeys.WARMUP_ENABLED to "Warm-up phase",
            AppSettingKeys.WARMUP_DAYS to "Warm-up phase (days)",
            AppSettingKeys.RECOVERY_WARMUP_DAYS to "Recovery phase (days)",
            BotSettingKeys.COLD_MONTHLY_CAP to "New contacts per month",
            BotSettingKeys.AUTOBLOCK_PER_MIN to "Auto-block from messages/minute",
            BotSettingKeys.AUTOBLOCK_PER_5MIN to "Auto-block from messages/5 min",
            BotSettingKeys.AUTOBLOCK_PER_10MIN to "Auto-block from messages/10 min",
            BotSettingKeys.BLOCK_TOOL_ENABLED to "Bot may block on its own",
            BotSettingKeys.AUTO_VERIFY to "Check replies automatically",
            BotSettingKeys.VERIFY_MAX_RETRIES to "Check: maximum attempts",
            BotSettingKeys.VERIFY_MAX_TOKENS to "Check: output budget",
            BotSettingKeys.VERIFY_REASONING_EFFORT to "Check: reasoning effort",
            BotSettingKeys.VERIFY_FAIL_CLOSED to "Check: block on failure",
            AppSettingKeys.MAX_VIDEO_BYTES to "Maximum video size (MB)",
            AppSettingKeys.STT_FALLBACK_MODEL to "Transcription model",
            AppSettingKeys.STT_FALLBACK_URL to "Transcription endpoint",
            BotSettingKeys.TTS_STYLE_PROMPT to "Speaking-style prompt",
            BotSettingKeys.ERROR_REPORTS to "Error reports to admin",
            BotSettingKeys.TRAIT_FLIRT to "Flirtiness 😏",
            BotSettingKeys.TRAIT_LEWD to "Raunchiness 😈",
            BotSettingKeys.TRAIT_MEANNESS to "Bite 🦷",
            BotSettingKeys.TRAIT_CHAOS to "Chaos 🌀",
            BotSettingKeys.TRAIT_OBEDIENCE to "Compliance 🫡",
            BotSettingKeys.TRAIT_OPENNESS to "Openness 🫠",
            BotSettingKeys.TRAIT_PLAYFULNESS to "Playfulness 🤡",
            BotSettingKeys.TRAIT_SUSPICION to "Suspicion 🤨",
            BotSettingKeys.TRAIT_INITIATIVE to "Initiative 🚀",
        )

    private val TOKEN_LABELS =
        mapOf(
            "ms" to "(ms)",
            "url" to "URL",
            "tts" to "TTS",
            "stt" to "STT",
            "ai" to "AI",
            "id" to "ID",
        )

    /**
     * One inhabited zone per offset the world actually uses, named by a city.
     *
     * The full tzdb is roughly six hundred ids, which is a scroll through Africa to reach Berlin
     * for a setting that has at most a couple of dozen distinct answers. These are real zones, not
     * fixed offsets, so daylight saving keeps working — quiet hours would drift by an hour every
     * summer if this were a list of `UTC+01:00`-style offsets.
     *
     * Sorted by the offset in force right now, so the list reads west to east.
     */
    private val TIMEZONE_IDS: List<String> by lazy {
        listOf(
            "Pacific/Pago_Pago",
            "Pacific/Honolulu",
            "America/Anchorage",
            "America/Los_Angeles",
            "America/Denver",
            "America/Chicago",
            "America/New_York",
            "America/Halifax",
            "America/Sao_Paulo",
            "Atlantic/South_Georgia",
            "Atlantic/Azores",
            "UTC",
            "Europe/London",
            "Europe/Berlin",
            "Europe/Athens",
            "Europe/Moscow",
            "Asia/Tehran",
            "Asia/Dubai",
            "Asia/Kabul",
            "Asia/Karachi",
            "Asia/Kolkata",
            "Asia/Kathmandu",
            "Asia/Dhaka",
            "Asia/Yangon",
            "Asia/Bangkok",
            "Asia/Singapore",
            "Asia/Tokyo",
            "Australia/Adelaide",
            "Australia/Sydney",
            "Pacific/Noumea",
            "Pacific/Auckland",
            "Pacific/Apia",
            "Pacific/Kiritimati",
        ).mapNotNull { id -> runCatching { ZoneId.of(id) }.getOrNull()?.let { id to it } }
            .sortedBy { (_, zone) -> zone.rules.getOffset(Instant.now()).totalSeconds }
            .map { (id, _) -> id }
    }
}
