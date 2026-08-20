package de.totec.doppel.settings

internal class FakeSettingsPersistence(
    initial: PersistedSettings = PersistedSettings(),
) : SettingsPersistence {
    var state: PersistedSettings = initial.frozen()
        private set

    var commitCount: Int = 0
        private set

    var failNextCommit: Boolean = false

    /** Runs just before the revision is checked, so a test can play a competing writer. */
    var beforeCommit: (() -> Unit)? = null

    override fun load(): PersistedSettings = state

    override fun commit(
        expectedRevision: Long,
        mutation: SettingsMutation,
    ): PersistedSettings {
        beforeCommit?.invoke()
        if (expectedRevision != state.revision) {
            throw SettingsRevisionConflictException(expectedRevision, state.revision)
        }
        if (failNextCommit) {
            failNextCommit = false
            throw IllegalStateException("simulated persistence failure")
        }
        check(!mutation.isEmpty)
        state =
            PersistedSettings(
                revision = state.revision + 1,
                globalOverrides =
                    mutate(state.globalOverrides, mutation.globalUpserts, mutation.globalDeletes),
                appOverrides = mutate(state.appOverrides, mutation.appUpserts, mutation.appDeletes),
                personaContactOverrides =
                    mutate(
                        state.personaContactOverrides,
                        mutation.personaUpserts,
                        mutation.personaDeletes,
                    ),
                proactiveContactOverrides =
                    mutate(
                        state.proactiveContactOverrides,
                        mutation.proactiveUpserts,
                        mutation.proactiveDeletes,
                    ),
                retainedLegacy =
                    mutate(
                        state.retainedLegacy,
                        mutation.retainedLegacyUpserts,
                        mutation.retainedLegacyDeletes,
                    ),
            ).frozen()
        commitCount += 1
        return state
    }

    fun replaceExternally(newState: PersistedSettings) {
        state = newState.frozen()
    }

    private fun mutate(
        current: Map<String, String>,
        upserts: Map<String, String>,
        deletes: Set<String>,
    ): Map<String, String> =
        current.toMutableMap().apply {
            deletes.forEach(::remove)
            putAll(upserts)
        }
}
