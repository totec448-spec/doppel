package de.totec.doppel.data

import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.DatabaseSettingsSnapshot
import de.totec.doppel.data.db.ScopedSettingRecord
import de.totec.doppel.data.db.SettingAddress
import de.totec.doppel.data.db.SettingsScopes
import de.totec.doppel.data.db.StoredSettingValueType
import de.totec.doppel.settings.PersistedSettings
import de.totec.doppel.settings.SettingsMutation
import de.totec.doppel.settings.SettingsPersistence
import de.totec.doppel.settings.SettingsRevisionConflictException

/** One canonical SQLite path for UI, chat commands, notification and engine. */
class SqlSettingsPersistence(
    private val repository: BotRepository,
) : SettingsPersistence {
    override fun load(): PersistedSettings = repository.loadSettingsSnapshot().toDomain()

    override fun commit(
        expectedRevision: Long,
        mutation: SettingsMutation,
    ): PersistedSettings {
        if (mutation.isEmpty) {
            val current = load()
            if (current.revision != expectedRevision) {
                throw SettingsRevisionConflictException(expectedRevision, current.revision)
            }
            return current
        }

        val now = System.currentTimeMillis()
        val upserts =
            buildList {
                addMap(SettingsScopes.GLOBAL, mutation.globalUpserts, now)
                addMap(SettingsScopes.APP, mutation.appUpserts, now)
                mutation.personaUpserts.forEach { (contactId, value) ->
                    add(contactRecord(SettingsScopes.PERSONA_CONTACT, contactId, value, now))
                }
                mutation.proactiveUpserts.forEach { (contactId, value) ->
                    add(contactRecord(SettingsScopes.PROACTIVE_CONTACT, contactId, value, now))
                }
                addMap(SettingsScopes.RETAINED_LEGACY, mutation.retainedLegacyUpserts, now)
            }
        val deletes =
            buildList {
                addAddresses(SettingsScopes.GLOBAL, mutation.globalDeletes)
                addAddresses(SettingsScopes.APP, mutation.appDeletes)
                mutation.personaDeletes.forEach {
                    add(SettingAddress(SettingsScopes.PERSONA_CONTACT, it, SettingsScopes.CONTACT_VALUE_KEY))
                }
                mutation.proactiveDeletes.forEach {
                    add(SettingAddress(SettingsScopes.PROACTIVE_CONTACT, it, SettingsScopes.CONTACT_VALUE_KEY))
                }
                addAddresses(SettingsScopes.RETAINED_LEGACY, mutation.retainedLegacyDeletes)
            }
        val result =
            repository.compareAndSwapSettings(
                expectedRevision = expectedRevision,
                upserts = upserts,
                deletes = deletes,
            )
        if (!result.applied) {
            throw SettingsRevisionConflictException(expectedRevision, result.revision)
        }
        return result.snapshot.toDomain()
    }

    private fun MutableList<ScopedSettingRecord>.addMap(
        scope: String,
        values: Map<String, String>,
        now: Long,
    ) {
        values.forEach { (key, value) ->
            add(
                ScopedSettingRecord(
                    scopeType = scope,
                    key = key,
                    value = value,
                    valueType =
                        if (key.endsWith("_ref")) {
                            StoredSettingValueType.SECRET_REFERENCE
                        } else {
                            StoredSettingValueType.STRING
                        },
                    updatedAt = now,
                ),
            )
        }
    }

    private fun contactRecord(
        scope: String,
        contactId: String,
        value: String,
        now: Long,
    ): ScopedSettingRecord =
        ScopedSettingRecord(
            scopeType = scope,
            scopeId = contactId,
            key = SettingsScopes.CONTACT_VALUE_KEY,
            value = value,
            valueType = StoredSettingValueType.STRING,
            updatedAt = now,
        )

    private fun MutableList<SettingAddress>.addAddresses(
        scope: String,
        keys: Set<String>,
    ) {
        keys.forEach { add(SettingAddress(scopeType = scope, key = it)) }
    }

    private fun DatabaseSettingsSnapshot.toDomain(): PersistedSettings {
        val global = linkedMapOf<String, String>()
        val app = linkedMapOf<String, String>()
        val personas = linkedMapOf<String, String>()
        val proactive = linkedMapOf<String, String>()
        val legacy = linkedMapOf<String, String>()
        settings.forEach { row ->
            when (row.scopeType) {
                SettingsScopes.GLOBAL -> global[row.key] = row.value
                SettingsScopes.APP -> app[row.key] = row.value
                SettingsScopes.PERSONA_CONTACT ->
                    if (row.key == SettingsScopes.CONTACT_VALUE_KEY) personas[row.scopeId] = row.value
                SettingsScopes.PROACTIVE_CONTACT ->
                    if (row.key == SettingsScopes.CONTACT_VALUE_KEY) proactive[row.scopeId] = row.value
                SettingsScopes.RETAINED_LEGACY -> legacy[row.key] = row.value
            }
        }
        return PersistedSettings(
            revision = revision,
            globalOverrides = global,
            appOverrides = app,
            personaContactOverrides = personas,
            proactiveContactOverrides = proactive,
            retainedLegacy = legacy,
        ).frozen()
    }
}
