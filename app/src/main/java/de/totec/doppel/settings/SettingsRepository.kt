package de.totec.doppel.settings

import java.net.URI
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsSnapshot internal constructor(
    val revision: Long,
    val global: Map<String, SettingValue>,
    val app: Map<String, SettingValue>,
    val globalOverrideKeys: Set<String>,
    val appOverrideKeys: Set<String>,
    val personaContactOverrides: Map<String, String>,
    val proactiveContactOverrides: Map<String, Int>,
    val retainedLegacyKeys: Set<String>,
) {
    init {
        require(revision >= 0)
    }

    fun value(key: String): SettingValue =
        requireNotNull(global[key]) { "Unknown live setting: $key" }

    fun appValue(key: String): SettingValue =
        requireNotNull(app[key]) { "Unknown app setting: $key" }

    fun boolean(key: String): Boolean = (value(key) as SettingValue.Bool).value

    fun integer(key: String): Int = (value(key) as SettingValue.Integer).value

    fun decimal(key: String): Double = (value(key) as SettingValue.Decimal).value

    fun text(key: String): String = (value(key) as SettingValue.Text).value

    fun appBoolean(key: String): Boolean = (appValue(key) as SettingValue.Bool).value

    fun appInteger(key: String): Int = (appValue(key) as SettingValue.Integer).value

    fun appText(key: String): String = (appValue(key) as SettingValue.Text).value

    fun appStringList(key: String): List<String> =
        (appValue(key) as SettingValue.StringList).values

    fun secretReference(key: String): String =
        (appValue(key) as SettingValue.SecretReference).name

    fun effectivePersona(contactId: String): String =
        personaContactOverrides[normalizeContactId(contactId)] ?: text(BotSettingKeys.PERSONALITY)

    /**
     * A per-contact level is deliberately independent of the global one, which defaults to off:
     * arming a single contact is the normal way to use this at all. The account-wide stop is
     * therefore the bot switch, not this slider.
     */
    fun effectiveProactiveLevel(contactId: String): Int =
        proactiveContactOverrides[normalizeContactId(contactId)] ?: integer(BotSettingKeys.PROACTIVE_LEVEL)
}

enum class ImportNoticeKind {
    ALIAS_MIGRATED,
    ALIAS_SHADOWED,
    LEGACY_TRANSPORT_NORMALIZED,
    INVALID_RESET_TO_DEFAULT,
    UNKNOWN_DROPPED,
    LEGACY_HIDDEN,
    CONTACT_OVERRIDE_DROPPED,
    SECRET_VALUE_REJECTED,
}

data class ImportNotice(
    val kind: ImportNoticeKind,
    val sourceKey: String,
    val targetKey: String? = null,
    val reason: String,
)

data class ImportSanitizationReport(
    val originalRevision: Long,
    val finalRevision: Long,
    val notices: List<ImportNotice>,
) {
    val aliases: List<ImportNotice>
        get() = notices.filter { it.kind == ImportNoticeKind.ALIAS_MIGRATED }

    val hiddenLegacy: List<ImportNotice>
        get() = notices.filter { it.kind == ImportNoticeKind.LEGACY_HIDDEN }

    val changed: Boolean
        get() = finalRevision != originalRevision || notices.isNotEmpty()
}

data class SettingsImportResult(
    val snapshot: SettingsSnapshot,
    val report: ImportSanitizationReport,
)

class SettingsValidationException(
    val errors: Map<String, String>,
) : IllegalArgumentException(
        errors.entries.joinToString(
            prefix = "Invalid settings: ",
            separator = "; ",
        ) { "${it.key}: ${it.value}" },
    )

/**
 * Canonical typed settings service.
 *
 * Normal bot settings are global and authoritative. Per-contact variation is
 * deliberately limited to the two source-supported dimensions: persona and
 * proactive level. This prevents a second generic per-chat settings system from
 * silently shadowing the admin's global controls.
 */
class SettingsRepository(
    private val persistence: SettingsPersistence,
) {
    private val monitor = Any()
    private var persisted: PersistedSettings
    private val mutableChanges: MutableStateFlow<SettingsSnapshot>

    val changes: StateFlow<SettingsSnapshot>
        get() = mutableChanges.asStateFlow()

    val importReport: ImportSanitizationReport

    init {
        val loaded = persistence.load().frozen()
        val sanitized = sanitize(loaded)
        val committed =
            if (sanitized.mutation.isEmpty) {
                loaded
            } else {
                persistence.commit(loaded.revision, sanitized.mutation).frozen()
            }
        persisted = committed
        val initialSnapshot = snapshotOf(committed)
        mutableChanges = MutableStateFlow(initialSnapshot)
        importReport =
            ImportSanitizationReport(
                originalRevision = loaded.revision,
                finalRevision = committed.revision,
                notices = Collections.unmodifiableList(sanitized.notices.toList()),
            )
    }

    fun snapshot(): SettingsSnapshot = mutableChanges.value

    /**
     * Validates every value before opening the persistence transaction. A single
     * bad key leaves both durable state and the change Flow untouched.
     */
    fun updateGlobal(
        values: Map<String, SettingValue>,
        ttsCatalogVoices: List<String>? = null,
    ): SettingsSnapshot =
        synchronized(monitor) {
            val effective = withCompatibleVoices(values, ttsCatalogVoices)
            validateUpdates(effective, BotSettingsSchema.byKey)
            val (upserts, deletes) = compactOverrides(effective, BotSettingsSchema.defaults)
            commitIfChanged(
                SettingsMutation(
                    globalUpserts = upserts,
                    globalDeletes = deletes,
                ),
            )
        }

    /**
     * Switching the TTS model used to leave the previous provider's voice in place, so the next
     * voice note failed or came out in an unrelated voice. Repairing it at the single durable write
     * point covers the settings screen, admin commands, presets and env import in one move; a voice
     * the new model already accepts is never touched.
     */
    private fun withCompatibleVoices(
        values: Map<String, SettingValue>,
        catalogVoices: List<String>?,
    ): Map<String, SettingValue> {
        val model = (values[BotSettingKeys.TTS_MODEL] as? SettingValue.Text)?.value ?: return values
        val key = BotSettingKeys.TTS_VOICE
        val requested = (values[key] as? SettingValue.Text)?.value ?: mutableChanges.value.text(key)
        if (requested.isBlank()) return values
        val resolved = TtsVoiceCatalog.resolve(model, requested, catalogVoices)
        return if (resolved == requested) values else values + (key to SettingValue.Text(resolved))
    }

    fun updateAppControls(values: Map<String, SettingValue>): SettingsSnapshot =
        synchronized(monitor) {
            validateUpdates(values, AppSettingsSchema.byKey, appControls = true)
            val (upserts, deletes) = compactOverrides(values, AppSettingsSchema.defaults)
            commitIfChanged(
                SettingsMutation(
                    appUpserts = upserts,
                    appDeletes = deletes,
                ),
            )
        }

    fun resetGlobalToDefaults(
        keys: Set<String> = BotSettingsSchema.byKey.keys,
    ): SettingsSnapshot =
        synchronized(monitor) {
            validateKnownKeys(keys, BotSettingsSchema.byKey)
            commitIfChanged(SettingsMutation(globalDeletes = keys))
        }

    fun resetAppControlsToDefaults(
        keys: Set<String> = AppSettingsSchema.byKey.keys,
    ): SettingsSnapshot =
        synchronized(monitor) {
            validateKnownKeys(keys, AppSettingsSchema.byKey)
            commitIfChanged(SettingsMutation(appDeletes = keys))
        }

    fun setContactPersona(
        contactId: String,
        personaKey: String?,
    ): SettingsSnapshot =
        synchronized(monitor) {
            val contact = checkedContactId(contactId)
            if (personaKey == null) {
                commitIfChanged(SettingsMutation(personaDeletes = setOf(contact)))
            } else {
                val persona = personaKey.trim().lowercase()
                if (!PERSONA_KEY_PATTERN.matches(persona)) {
                    throw SettingsValidationException(
                        mapOf(contact to "invalid persona key"),
                    )
                }
                commitIfChanged(SettingsMutation(personaUpserts = mapOf(contact to persona)))
            }
        }

    fun setContactProactiveLevel(
        contactId: String,
        level: Int?,
    ): SettingsSnapshot =
        synchronized(monitor) {
            val contact = checkedContactId(contactId)
            if (level == null) {
                commitIfChanged(SettingsMutation(proactiveDeletes = setOf(contact)))
            } else {
                val spec = BotSettingsSchema.requireSpec(BotSettingKeys.PROACTIVE_LEVEL)
                val validation = spec.validate(SettingValue.Integer(level))
                if (validation is SettingValidation.Invalid) {
                    throw SettingsValidationException(mapOf(contact to validation.reason))
                }
                commitIfChanged(
                    SettingsMutation(proactiveUpserts = mapOf(contact to level.toString())),
                )
            }
        }

    /**
     * Pulls state written by another repository/process. Invalid external data is
     * sanitised with the same import contract before publication.
     */
    fun reload(): SettingsImportResult =
        synchronized(monitor) {
            val loaded = persistence.load().frozen()
            val sanitized = sanitize(loaded)
            val committed =
                if (sanitized.mutation.isEmpty) {
                    loaded
                } else {
                    persistence.commit(loaded.revision, sanitized.mutation).frozen()
                }
            persisted = committed
            val next = snapshotOf(committed)
            mutableChanges.value = next
            SettingsImportResult(
                next,
                ImportSanitizationReport(
                    loaded.revision,
                    committed.revision,
                    Collections.unmodifiableList(sanitized.notices.toList()),
                ),
            )
        }

    /**
     * Commits against the revision this repository last saw. Another writer — the engine, or the
     * settings reload an admin action triggers — can advance the durable revision in between, and
     * that used to surface as a raw "expected 134, actual 136" and silently drop the user's edit.
     * Their write and this one almost never touch the same key, so rebase onto the newer state and
     * retry rather than refusing. Only a mutation that still conflicts after [COMMIT_ATTEMPTS]
     * rebases is reported.
     */
    private fun commitIfChanged(mutation: SettingsMutation): SettingsSnapshot {
        var lastConflict: SettingsRevisionConflictException? = null
        repeat(COMMIT_ATTEMPTS) {
            val reduced = removeNoOps(persisted, mutation)
            if (reduced.isEmpty) return mutableChanges.value
            try {
                val committed = persistence.commit(persisted.revision, reduced).frozen()
                // A plain IllegalStateException from here is a persistence contract violation, not
                // a conflict, so it must escape instead of being retried.
                check(committed.revision > persisted.revision) {
                    "Persistence must increment the revision for a non-empty transaction"
                }
                persisted = committed
                val next = snapshotOf(committed)
                mutableChanges.value = next
                return next
            } catch (conflict: SettingsRevisionConflictException) {
                lastConflict = conflict
                persisted = persistence.load().frozen()
                mutableChanges.value = snapshotOf(persisted)
            }
        }
        throw lastConflict ?: IllegalStateException("Settings commit did not converge")
    }
}

private data class SanitizedState(
    val mutation: SettingsMutation,
    val notices: List<ImportNotice>,
)

private data class SanitizedScope(
    val values: Map<String, String>,
    val hiddenLegacy: Map<String, String>,
    val notices: List<ImportNotice>,
)

private fun sanitize(state: PersistedSettings): SanitizedState {
    val global =
        sanitizeScope(
            raw = state.globalOverrides,
            specs = BotSettingsSchema.byKey,
            aliases = BOT_ALIAS_INDEX,
            allowHiddenTtsScriptModel = true,
        )
    val app =
        sanitizeScope(
            raw = state.appOverrides,
            specs = AppSettingsSchema.byKey,
            aliases = APP_ALIAS_INDEX,
            allowHiddenTtsScriptModel = false,
            appControls = true,
        )
    val notices = mutableListOf<ImportNotice>()
    notices += global.notices
    notices += app.notices

    val personas = linkedMapOf<String, String>()
    for ((rawContact, rawPersona) in state.personaContactOverrides) {
        val contact = rawContact.trim()
        val persona = rawPersona.trim().lowercase()
        if (!isValidContactId(contact) || !PERSONA_KEY_PATTERN.matches(persona)) {
            notices +=
                ImportNotice(
                    ImportNoticeKind.CONTACT_OVERRIDE_DROPPED,
                    rawContact,
                    reason = "invalid contact or persona key",
                )
        } else {
            personas[contact] = persona
        }
    }

    val proactive = linkedMapOf<String, String>()
    for ((rawContact, rawLevel) in state.proactiveContactOverrides) {
        val contact = rawContact.trim()
        val level = rawLevel.trim().toIntOrNull()
        if (!isValidContactId(contact) || level == null || level !in 0..10) {
            notices +=
                ImportNotice(
                    ImportNoticeKind.CONTACT_OVERRIDE_DROPPED,
                    rawContact,
                    reason = "invalid contact or proactive level",
                )
        } else {
            proactive[contact] = level.toString()
        }
    }

    val retained =
        buildMap {
            state.retainedLegacy[BotSettingsSchema.LEGACY_HIDDEN_TTS_SCRIPT_MODEL]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(512)
                ?.let { put(BotSettingsSchema.LEGACY_HIDDEN_TTS_SCRIPT_MODEL, it) }
            putAll(global.hiddenLegacy)
        }

    val target =
        state.copy(
            globalOverrides = global.values,
            appOverrides = app.values,
            personaContactOverrides = personas,
            proactiveContactOverrides = proactive,
            retainedLegacy = retained,
        )
    return SanitizedState(
        mutation = diffPersisted(state, target),
        notices = notices,
    )
}

private fun sanitizeScope(
    raw: Map<String, String>,
    specs: Map<String, SettingSpec>,
    aliases: Map<String, String>,
    allowHiddenTtsScriptModel: Boolean,
    appControls: Boolean = false,
): SanitizedScope {
    val output = linkedMapOf<String, String>()
    val hidden = linkedMapOf<String, String>()
    val notices = mutableListOf<ImportNotice>()
    val canonicalKeysPresent = raw.keys.filterTo(linkedSetOf(), specs::containsKey)

    // Canonical keys always win over legacy aliases independent of map order.
    for ((key, rawValue) in raw) {
        val spec = specs[key] ?: continue
        val migrated = migrateLegacyAppValue(key, rawValue, appControls)
        val parsed = parseRaw(spec, migrated.value, appControls)
        if (parsed.value == null) {
            notices +=
                ImportNotice(
                    parsed.noticeKind ?: ImportNoticeKind.INVALID_RESET_TO_DEFAULT,
                    key,
                    key,
                    parsed.reason ?: "invalid value",
                )
        } else {
            if (parsed.value != spec.defaultValue) {
                output[key] = parsed.value.encode()
            }
            migrated.reason?.let { reason ->
                notices +=
                    ImportNotice(
                        ImportNoticeKind.LEGACY_TRANSPORT_NORMALIZED,
                        key,
                        key,
                        reason,
                    )
            }
        }
    }

    for ((rawKey, rawValue) in raw) {
        if (rawKey in specs) continue
        val normalizedKey = rawKey.trim().lowercase()
        if (
            appControls &&
            normalizedKey in setOf(
                AppSettingKeys.BRIDGE_MODE,
                AppSettingKeys.CONNECTION_MODE,
                AppSettingKeys.BRIDGE_URL,
                AppSettingKeys.TRANSPORT_MODE,
            )
        ) {
            notices +=
                ImportNotice(
                    ImportNoticeKind.LEGACY_TRANSPORT_NORMALIZED,
                    rawKey,
                    reason =
                        "obsolete transport settings removed: there is one transport, the linked-device core inside this app, and it binds to Android loopback",
                )
            continue
        }
        if (allowHiddenTtsScriptModel && normalizedKey in HIDDEN_TTS_SCRIPT_ALIASES) {
            val hiddenValue = rawValue.trim().take(512)
            if (hiddenValue.isNotEmpty()) {
                hidden[BotSettingsSchema.LEGACY_HIDDEN_TTS_SCRIPT_MODEL] = hiddenValue
            }
            notices +=
                ImportNotice(
                    ImportNoticeKind.LEGACY_HIDDEN,
                    rawKey,
                    BotSettingsSchema.LEGACY_HIDDEN_TTS_SCRIPT_MODEL,
                    "obsolete second TTS model retained but not exposed",
                )
            continue
        }
        if (
            allowHiddenTtsScriptModel &&
            normalizedKey == BotSettingsSchema.LEGACY_REMOVED_LEAVE_ON_READ
        ) {
            notices +=
                ImportNotice(
                    ImportNoticeKind.LEGACY_HIDDEN,
                    rawKey,
                    reason = "obsolete leave-on-read switch removed; deferred replies use the live engine policy",
                )
            continue
        }

        val canonical = aliases[normalizedKey]
        val spec = canonical?.let(specs::get)
        if (canonical == null || spec == null) {
            notices +=
                ImportNotice(
                    ImportNoticeKind.UNKNOWN_DROPPED,
                    rawKey,
                    reason = "unknown setting",
                )
            continue
        }
        if (canonical in canonicalKeysPresent || canonical in output) {
            notices +=
                ImportNotice(
                    ImportNoticeKind.ALIAS_SHADOWED,
                    rawKey,
                    canonical,
                    "canonical key already present",
                )
            continue
        }

        val migrated = migrateLegacyAppValue(canonical, rawValue, appControls)
        val parsed = parseRaw(spec, migrated.value, appControls)
        if (parsed.value == null) {
            notices +=
                ImportNotice(
                    parsed.noticeKind ?: ImportNoticeKind.INVALID_RESET_TO_DEFAULT,
                    rawKey,
                    canonical,
                    parsed.reason ?: "invalid value",
                )
            continue
        }
        if (parsed.value != spec.defaultValue) {
            output[canonical] = parsed.value.encode()
        }
        notices +=
            ImportNotice(
                ImportNoticeKind.ALIAS_MIGRATED,
                rawKey,
                canonical,
                "legacy alias migrated",
            )
        migrated.reason?.let { reason ->
            notices +=
                ImportNotice(
                    ImportNoticeKind.LEGACY_TRANSPORT_NORMALIZED,
                    rawKey,
                    canonical,
                    reason,
                )
        }
    }
    return SanitizedScope(output, hidden, notices)
}

private data class MigratedRawValue(
    val value: String,
    val reason: String? = null,
)

/**
 * The old adaptive mode described a transport which no longer exists. An early
 * phone-app release also persisted 9000 ms as the provider timeout before the
 * stable default became 90 seconds. Persisted/imported values safely converge to
 * their current executable contracts.
 *
 * This migration is deliberately confined to loading/import. A new UI or API
 * update which attempts to write "adaptive" still fails normal enum validation.
 */
private fun migrateLegacyAppValue(
    key: String,
    raw: String,
    appControls: Boolean,
): MigratedRawValue =
    when {
        !appControls -> MigratedRawValue(raw)

        key == AppSettingKeys.CONNECTION_MODE &&
            raw.trim().equals("adaptive", ignoreCase = true) ->
            MigratedRawValue(
                value = "persistent",
                reason = "adaptive/FCM mode is unsupported and was migrated to persistent WSS",
            )

        key == AppSettingKeys.OPENROUTER_TIMEOUT_MS && raw.trim() == "9000" ->
            MigratedRawValue(
                value = "90000",
                reason =
                    "the obsolete 9 second provider timeout was migrated to the current " +
                        "90 second default",
            )

        else -> MigratedRawValue(raw)
    }

private data class ParsedRaw(
    val value: SettingValue?,
    val reason: String? = null,
    val noticeKind: ImportNoticeKind? = null,
)

private fun parseRaw(
    spec: SettingSpec,
    raw: String,
    appControls: Boolean,
): ParsedRaw {
    val trimmed = raw.trim()
    val parsed =
        try {
            when (spec.valueType) {
                SettingValueType.TEXT -> SettingValue.Text(trimmed)
                SettingValueType.BOOLEAN ->
                    when {
                        TRUE_VALUES.matches(trimmed) -> SettingValue.Bool(true)
                        FALSE_VALUES.matches(trimmed) -> SettingValue.Bool(false)
                        else -> return ParsedRaw(null, "expected true/false")
                    }
                SettingValueType.INTEGER ->
                    SettingValue.Integer(
                        trimmed.toIntOrNull()
                            ?: return ParsedRaw(null, "expected integer"),
                    )
                SettingValueType.DECIMAL ->
                    SettingValue.Decimal(
                        trimmed.toDoubleOrNull()
                            ?: return ParsedRaw(null, "expected decimal"),
                    )
                SettingValueType.ENUM -> {
                    val match =
                        spec.options.firstOrNull { it.value == trimmed }
                            ?: spec.options.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }
                            ?: return ParsedRaw(null, "unknown enum option")
                    SettingValue.Text(match.value)
                }
                SettingValueType.STRING_LIST ->
                    SettingValue.StringList.of(
                        trimmed.split(LIST_SEPARATOR).map(String::trim),
                    )
                SettingValueType.SECRET_REFERENCE -> {
                    val expected = (spec.defaultValue as SettingValue.SecretReference).name
                    if (trimmed != expected) {
                        return ParsedRaw(
                            null,
                            "raw secret values are forbidden; import into SecretStore first",
                            ImportNoticeKind.SECRET_VALUE_REJECTED,
                        )
                    }
                    SettingValue.SecretReference(expected)
                }
            }
        } catch (_: IllegalArgumentException) {
            return ParsedRaw(null, "invalid value")
        }

    val validation = validateValue(spec, parsed, appControls)
    return if (validation == null) {
        ParsedRaw(parsed)
    } else {
        ParsedRaw(null, validation)
    }
}

private fun validateUpdates(
    updates: Map<String, SettingValue>,
    schema: Map<String, SettingSpec>,
    appControls: Boolean = false,
) {
    val errors = linkedMapOf<String, String>()
    for ((key, value) in updates) {
        val spec = schema[key]
        if (spec == null) {
            errors[key] = "unknown setting"
            continue
        }
        validateValue(spec, value, appControls)?.let { errors[key] = it }
    }
    if (errors.isNotEmpty()) throw SettingsValidationException(immutableMap(errors))
}

private fun validateValue(
    spec: SettingSpec,
    value: SettingValue,
    appControls: Boolean,
): String? {
    val validation = spec.validate(value)
    if (validation is SettingValidation.Invalid) return validation.reason
    if (spec.sensitiveReference) {
        val expected = (spec.defaultValue as SettingValue.SecretReference).name
        val actual = (value as SettingValue.SecretReference).name
        if (actual != expected) {
            return "secret values are forbidden; only the fixed Keystore reference is accepted"
        }
    }

    if (value is SettingValue.Text) {
        when (spec.key) {
            BotSettingKeys.SLEEP_START,
            BotSettingKeys.SLEEP_END,
            -> if (!validTime(value.value)) return "expected HH:mm"

            BotSettingKeys.TIMEZONE ->
                try {
                    ZoneId.of(value.value)
                } catch (_: DateTimeException) {
                    return "expected IANA timezone"
                }

            AppSettingKeys.OPENROUTER_BASE_URL,
            AppSettingKeys.STT_FALLBACK_URL,
            -> if (!validUrl(value.value, setOf("http", "https"))) {
                return "expected http/https URL"
            }

            AppSettingKeys.COMMAND_PREFIX ->
                if (value.value.isEmpty() ||
                    value.value.length > 4 ||
                    value.value.any(Char::isWhitespace)
                ) {
                    return "command prefix must be 1..4 non-whitespace characters"
                }
        }
    }

    if (appControls &&
        value is SettingValue.StringList &&
        spec.key in setOf(
            AppSettingKeys.OWNER_NUMBERS,
            AppSettingKeys.ADMIN_NUMBERS,
            AppSettingKeys.ALLOWLIST_NUMBERS,
        ) &&
        value.values.any { !PHONE_PATTERN.matches(it) }
    ) {
        return "phone lists require international-style numbers"
    }
    return null
}

private fun snapshotOf(state: PersistedSettings): SettingsSnapshot {
    val global =
        linkedMapOf<String, SettingValue>().apply {
            putAll(BotSettingsSchema.defaults)
            for ((key, raw) in state.globalOverrides) {
                val spec = BotSettingsSchema.byKey[key] ?: continue
                parseRaw(spec, raw, appControls = false).value?.let { put(key, it) }
            }
        }
    val app =
        linkedMapOf<String, SettingValue>().apply {
            putAll(AppSettingsSchema.defaults)
            for ((key, raw) in state.appOverrides) {
                val spec = AppSettingsSchema.byKey[key] ?: continue
                parseRaw(spec, raw, appControls = true).value?.let { put(key, it) }
            }
        }
    val proactive =
        state.proactiveContactOverrides.mapValues { (_, value) ->
            checkNotNull(value.toIntOrNull())
        }
    return SettingsSnapshot(
        revision = state.revision,
        global = immutableSettingMap(global),
        app = immutableSettingMap(app),
        globalOverrideKeys = immutableStringSet(state.globalOverrides.keys),
        appOverrideKeys = immutableStringSet(state.appOverrides.keys),
        personaContactOverrides = immutableMap(state.personaContactOverrides),
        proactiveContactOverrides = immutableMap(proactive),
        retainedLegacyKeys = immutableStringSet(state.retainedLegacy.keys),
    )
}

private fun aliasIndex(
    specs: List<SettingSpec>,
    explicit: Map<String, String> = emptyMap(),
): Map<String, String> =
    buildMap {
        for (spec in specs) {
            val aliases =
                buildSet {
                    addAll(spec.legacyAliases)
                    spec.environmentKey?.let(::add)
                }
            for (alias in aliases) put(alias.lowercase(), spec.key)
        }
        for ((alias, canonical) in explicit) put(alias.lowercase(), canonical)
    }

private fun diffPersisted(
    current: PersistedSettings,
    target: PersistedSettings,
): SettingsMutation =
    SettingsMutation(
        globalUpserts = changedValues(current.globalOverrides, target.globalOverrides),
        globalDeletes = current.globalOverrides.keys - target.globalOverrides.keys,
        appUpserts = changedValues(current.appOverrides, target.appOverrides),
        appDeletes = current.appOverrides.keys - target.appOverrides.keys,
        personaUpserts =
            changedValues(current.personaContactOverrides, target.personaContactOverrides),
        personaDeletes =
            current.personaContactOverrides.keys - target.personaContactOverrides.keys,
        proactiveUpserts =
            changedValues(current.proactiveContactOverrides, target.proactiveContactOverrides),
        proactiveDeletes =
            current.proactiveContactOverrides.keys - target.proactiveContactOverrides.keys,
        retainedLegacyUpserts = changedValues(current.retainedLegacy, target.retainedLegacy),
        retainedLegacyDeletes = current.retainedLegacy.keys - target.retainedLegacy.keys,
    )

private fun changedValues(
    current: Map<String, String>,
    target: Map<String, String>,
): Map<String, String> =
    target.filter { (key, value) -> current[key] != value }

/**
 * Defaults are represented by absence at the persistence boundary. Besides
 * reducing writes and export size, this keeps future default migrations
 * possible without having old defaults masquerade as intentional overrides.
 */
private fun compactOverrides(
    values: Map<String, SettingValue>,
    defaults: Map<String, SettingValue>,
): Pair<Map<String, String>, Set<String>> {
    val upserts = linkedMapOf<String, String>()
    val deletes = linkedSetOf<String>()
    for ((key, value) in values) {
        if (defaults[key] == value) {
            deletes += key
        } else {
            upserts[key] = value.encode()
        }
    }
    return upserts to deletes
}

/** Rebase attempts before a settings commit gives up and reports the conflict. */
private const val COMMIT_ATTEMPTS = 4

private fun removeNoOps(
    current: PersistedSettings,
    mutation: SettingsMutation,
): SettingsMutation =
    SettingsMutation(
        globalUpserts =
            mutation.globalUpserts.filter { (key, value) -> current.globalOverrides[key] != value },
        globalDeletes = mutation.globalDeletes.filterTo(linkedSetOf()) { it in current.globalOverrides },
        appUpserts = mutation.appUpserts.filter { (key, value) -> current.appOverrides[key] != value },
        appDeletes = mutation.appDeletes.filterTo(linkedSetOf()) { it in current.appOverrides },
        personaUpserts =
            mutation.personaUpserts.filter { (key, value) ->
                current.personaContactOverrides[key] != value
            },
        personaDeletes =
            mutation.personaDeletes.filterTo(linkedSetOf()) {
                it in current.personaContactOverrides
            },
        proactiveUpserts =
            mutation.proactiveUpserts.filter { (key, value) ->
                current.proactiveContactOverrides[key] != value
            },
        proactiveDeletes =
            mutation.proactiveDeletes.filterTo(linkedSetOf()) {
                it in current.proactiveContactOverrides
            },
        retainedLegacyUpserts =
            mutation.retainedLegacyUpserts.filter { (key, value) ->
                current.retainedLegacy[key] != value
            },
        retainedLegacyDeletes =
            mutation.retainedLegacyDeletes.filterTo(linkedSetOf()) {
                it in current.retainedLegacy
            },
    )

private fun validateKnownKeys(
    keys: Set<String>,
    schema: Map<String, SettingSpec>,
) {
    val unknown = keys.filterNot(schema::containsKey)
    if (unknown.isNotEmpty()) {
        throw SettingsValidationException(unknown.associateWith { "unknown setting" })
    }
}

private fun checkedContactId(raw: String): String {
    val normalized = normalizeContactId(raw)
    if (!isValidContactId(normalized)) {
        throw SettingsValidationException(mapOf(raw to "invalid contact id"))
    }
    return normalized
}

private fun normalizeContactId(raw: String): String = raw.trim()

private fun isValidContactId(value: String): Boolean =
    value.isNotEmpty() && value.length <= 512 && !value.any(Char::isISOControl)

private fun validTime(raw: String): Boolean {
    val match = TIME_PATTERN.matchEntire(raw) ?: return false
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    return hour in 0..23 && minute in 0..59
}

private fun validUrl(
    raw: String,
    allowedSchemes: Set<String>,
): Boolean =
    try {
        val uri = URI(raw)
        uri.scheme?.lowercase() in allowedSchemes && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

private val BOT_ALIAS_INDEX =
    aliasIndex(
        BotSettingsSchema.all,
        explicit =
            mapOf(
                "mediamodel" to BotSettingKeys.MEDIA_MODEL,
                "visionmodel" to BotSettingKeys.MEDIA_MODEL,
                "audiomodel" to BotSettingKeys.MEDIA_MODEL,
                "history" to BotSettingKeys.HISTORY_LIMIT,
                "proactive" to BotSettingKeys.PROACTIVE_LEVEL,
                "crosschat" to BotSettingKeys.CROSS_CHAT_SEARCH,
                "voice" to BotSettingKeys.TTS_ENABLED,
            ),
    )
private val APP_ALIAS_INDEX = aliasIndex(AppSettingsSchema.all)
private val HIDDEN_TTS_SCRIPT_ALIASES =
    setOf(
        BotSettingsSchema.LEGACY_HIDDEN_TTS_SCRIPT_MODEL,
        "ttsscriptmodel",
        "tts_script_model",
    )
private val TRUE_VALUES = Regex("^(1|true|yes|on)$", RegexOption.IGNORE_CASE)
private val FALSE_VALUES = Regex("^(0|false|no|off)$", RegexOption.IGNORE_CASE)
private val LIST_SEPARATOR = Regex("[,\\r\\n]+")
private val TIME_PATTERN = Regex("^(\\d{2}):(\\d{2})$")
private val PERSONA_KEY_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,127}")
private val PHONE_PATTERN = Regex("^\\+?[0-9][0-9 .()/-]{5,31}$")
