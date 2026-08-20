package de.totec.doppel.settings

/**
 * Raw durable representation. Values remain strings at the port boundary so a
 * SQLite table, Proto DataStore, or encrypted export can implement the port
 * without depending on UI/domain classes.
 *
 * Secret-bearing app controls contain logical references only. Credential bytes
 * belong exclusively in the Keystore-backed SecretStore.
 */
data class PersistedSettings(
    val revision: Long = 0,
    val globalOverrides: Map<String, String> = emptyMap(),
    val appOverrides: Map<String, String> = emptyMap(),
    val personaContactOverrides: Map<String, String> = emptyMap(),
    val proactiveContactOverrides: Map<String, String> = emptyMap(),
    val retainedLegacy: Map<String, String> = emptyMap(),
) {
    init {
        require(revision >= 0)
    }

    fun frozen(): PersistedSettings =
        copy(
            globalOverrides = immutableMap(globalOverrides),
            appOverrides = immutableMap(appOverrides),
            personaContactOverrides = immutableMap(personaContactOverrides),
            proactiveContactOverrides = immutableMap(proactiveContactOverrides),
            retainedLegacy = immutableMap(retainedLegacy),
        )
}

data class SettingsMutation(
    val globalUpserts: Map<String, String> = emptyMap(),
    val globalDeletes: Set<String> = emptySet(),
    val appUpserts: Map<String, String> = emptyMap(),
    val appDeletes: Set<String> = emptySet(),
    val personaUpserts: Map<String, String> = emptyMap(),
    val personaDeletes: Set<String> = emptySet(),
    val proactiveUpserts: Map<String, String> = emptyMap(),
    val proactiveDeletes: Set<String> = emptySet(),
    val retainedLegacyUpserts: Map<String, String> = emptyMap(),
    val retainedLegacyDeletes: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() =
            globalUpserts.isEmpty() &&
                globalDeletes.isEmpty() &&
                appUpserts.isEmpty() &&
                appDeletes.isEmpty() &&
                personaUpserts.isEmpty() &&
                personaDeletes.isEmpty() &&
                proactiveUpserts.isEmpty() &&
                proactiveDeletes.isEmpty() &&
                retainedLegacyUpserts.isEmpty() &&
                retainedLegacyDeletes.isEmpty()
}

/**
 * Durable settings port.
 *
 * [commit] must apply the complete mutation in one transaction, verify
 * [expectedRevision], increment the revision exactly once, and return the full
 * post-commit state. A partial write is a contract violation.
 */
interface SettingsPersistence {
    fun load(): PersistedSettings

    @Throws(SettingsRevisionConflictException::class)
    fun commit(
        expectedRevision: Long,
        mutation: SettingsMutation,
    ): PersistedSettings
}

class SettingsRevisionConflictException(
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
        "Settings revision conflict: expected $expectedRevision, actual $actualRevision",
    )
