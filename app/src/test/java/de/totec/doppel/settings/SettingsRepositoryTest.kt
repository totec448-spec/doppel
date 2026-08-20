package de.totec.doppel.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun emptyPersistencePublishesImmutableCanonicalDefaults() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        val snapshot = repository.snapshot()

        assertEquals(0L, snapshot.revision)
        assertEquals(BotSettingsSchema.defaults, snapshot.global)
        assertEquals(AppSettingsSchema.defaults, snapshot.app)
        assertTrue(snapshot.globalOverrideKeys.isEmpty())
        assertTrue(snapshot.appOverrideKeys.isEmpty())
        assertTrue(snapshot.personaContactOverrides.isEmpty())
        assertTrue(snapshot.proactiveContactOverrides.isEmpty())
        assertSame(snapshot, repository.changes.value)
        assertEquals(0, persistence.commitCount)
        assertTrue(SettingsSnapshot::class.java.methods.none { it.name == "copy" })
        assertTrue(SettingValue.StringList::class.java.methods.none { it.name == "copy" })

        expectThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.global as MutableMap<String, SettingValue>)[BotSettingKeys.ENABLED] =
                SettingValue.Bool(false)
        }
        expectThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.globalOverrideKeys as MutableSet<String>).add("forbidden")
        }
        expectThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.appStringList(AppSettingKeys.OWNER_NUMBERS) as MutableList<String>)
                .add("+491234567")
        }
    }

    @Test
    fun multiKeyValidationIsAtomicBeforePersistence() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        val before = repository.snapshot()

        val error =
            expectThrows<SettingsValidationException> {
                repository.updateGlobal(
                    linkedMapOf(
                        BotSettingKeys.TEMPERATURE to SettingValue.Decimal(0.9),
                        BotSettingKeys.MAX_TOKENS to SettingValue.Integer(-1),
                        "not_a_setting" to SettingValue.Bool(true),
                    ),
                )
            }

        assertEquals(
            setOf(BotSettingKeys.MAX_TOKENS, "not_a_setting"),
            error.errors.keys,
        )
        assertEquals(0, persistence.commitCount)
        assertEquals(PersistedSettings(), persistence.state)
        assertSame(before, repository.snapshot())
        assertSame(before, repository.changes.value)
    }

    @Test
    fun oneValidBatchProducesOneRevisionAndOneFlowPublication() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)

        val after =
            repository.updateGlobal(
                linkedMapOf(
                    BotSettingKeys.TEMPERATURE to SettingValue.Decimal(1.1),
                    BotSettingKeys.HISTORY_LIMIT to SettingValue.Integer(60),
                    BotSettingKeys.CROSS_CHAT_SEARCH to SettingValue.Bool(true),
                ),
            )

        assertEquals(1, persistence.commitCount)
        assertEquals(1L, after.revision)
        assertEquals(1.1, after.decimal(BotSettingKeys.TEMPERATURE), 0.0)
        assertEquals(60, after.integer(BotSettingKeys.HISTORY_LIMIT))
        assertTrue(after.boolean(BotSettingKeys.CROSS_CHAT_SEARCH))
        assertEquals(
            setOf(
                BotSettingKeys.TEMPERATURE,
                BotSettingKeys.HISTORY_LIMIT,
                BotSettingKeys.CROSS_CHAT_SEARCH,
            ),
            after.globalOverrideKeys,
        )
        assertSame(after, repository.changes.value)
    }

    @Test
    fun persistenceFailureCannotLeakPartiallyUpdatedState() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        val before = repository.snapshot()
        persistence.failNextCommit = true

        expectThrows<IllegalStateException> {
            repository.updateGlobal(
                mapOf(BotSettingKeys.TEMPERATURE to SettingValue.Decimal(1.2)),
            )
        }

        assertEquals(0, persistence.commitCount)
        assertEquals(PersistedSettings(), persistence.state)
        assertSame(before, repository.snapshot())
        assertSame(before, repository.changes.value)
    }

    @Test
    fun defaultsAreAbsenceAndNeverCauseRedundantWrites() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        val initial = repository.snapshot()

        assertSame(
            initial,
            repository.updateGlobal(
                mapOf(BotSettingKeys.TEMPERATURE to SettingValue.Decimal(0.7)),
            ),
        )
        assertSame(
            initial,
            repository.updateAppControls(
                mapOf(AppSettingKeys.AUTOSTART to SettingValue.Bool(true)),
            ),
        )
        assertEquals(0, persistence.commitCount)

        repository.updateGlobal(
            mapOf(BotSettingKeys.TEMPERATURE to SettingValue.Decimal(1.3)),
        )
        val resetByWritingDefault =
            repository.updateGlobal(
                mapOf(BotSettingKeys.TEMPERATURE to SettingValue.Decimal(0.7)),
            )
        assertEquals(2, persistence.commitCount)
        assertEquals(0.7, resetByWritingDefault.decimal(BotSettingKeys.TEMPERATURE), 0.0)
        assertFalse(BotSettingKeys.TEMPERATURE in resetByWritingDefault.globalOverrideKeys)
        assertFalse(BotSettingKeys.TEMPERATURE in persistence.state.globalOverrides)
    }

    @Test
    fun globalValuesRemainAuthoritativeExceptExplicitPersonaAndProactiveOverrides() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        repository.updateGlobal(
            mapOf(
                BotSettingKeys.PERSONALITY to SettingValue.Text("female"),
                BotSettingKeys.PROACTIVE_LEVEL to SettingValue.Integer(8),
            ),
        )

        assertEquals("female", repository.snapshot().effectivePersona("alice"))
        assertEquals(8, repository.snapshot().effectiveProactiveLevel("alice"))

        repository.setContactPersona(" alice ", "goth")
        repository.setContactProactiveLevel("alice", 2)
        val overridden = repository.snapshot()
        assertEquals("goth", overridden.effectivePersona("alice"))
        assertEquals(2, overridden.effectiveProactiveLevel(" alice "))
        assertEquals("female", overridden.effectivePersona("bob"))
        assertEquals(8, overridden.effectiveProactiveLevel("bob"))
        assertEquals(mapOf("alice" to "goth"), overridden.personaContactOverrides)
        assertEquals(mapOf("alice" to 2), overridden.proactiveContactOverrides)

        repository.setContactPersona("alice", null)
        repository.setContactProactiveLevel("alice", null)
        assertEquals("female", repository.snapshot().effectivePersona("alice"))
        assertEquals(8, repository.snapshot().effectiveProactiveLevel("alice"))
    }

    /**
     * Arming a single contact while the global level stays at its default of off is the normal way
     * to use proactivity at all, so the override has to survive a global zero. Stopping that
     * contact means moving their own slider to zero, which must be distinguishable from having no
     * override at all.
     */
    @Test
    fun aPerContactLevelSurvivesAGlobalZeroAndZeroItselfSilencesTheContact() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        repository.setContactProactiveLevel("alice", 9)
        repository.updateGlobal(
            mapOf(BotSettingKeys.PROACTIVE_LEVEL to SettingValue.Integer(0)),
        )

        assertEquals(9, repository.snapshot().effectiveProactiveLevel("alice"))
        assertEquals(0, repository.snapshot().effectiveProactiveLevel("bob"))

        repository.setContactProactiveLevel("alice", 0)
        assertEquals(0, repository.snapshot().effectiveProactiveLevel("alice"))
        assertEquals(mapOf("alice" to 0), repository.snapshot().proactiveContactOverrides)

        repository.updateGlobal(
            mapOf(BotSettingKeys.PROACTIVE_LEVEL to SettingValue.Integer(6)),
        )
        assertEquals(0, repository.snapshot().effectiveProactiveLevel("alice"))
        assertEquals(6, repository.snapshot().effectiveProactiveLevel("bob"))
    }

    @Test
    fun resetSelectedAndResetAllRestoreDefaults() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        repository.updateGlobal(
            mapOf(
                BotSettingKeys.TEMPERATURE to SettingValue.Decimal(1.2),
                BotSettingKeys.HISTORY_LIMIT to SettingValue.Integer(60),
            ),
        )
        repository.updateAppControls(
            mapOf(
                AppSettingKeys.AUTOSTART to SettingValue.Bool(true),
                AppSettingKeys.COMMAND_PREFIX to SettingValue.Text("!"),
            ),
        )

        val partlyReset =
            repository.resetGlobalToDefaults(setOf(BotSettingKeys.TEMPERATURE))
        assertEquals(0.7, partlyReset.decimal(BotSettingKeys.TEMPERATURE), 0.0)
        assertEquals(60, partlyReset.integer(BotSettingKeys.HISTORY_LIMIT))
        assertEquals(setOf(BotSettingKeys.HISTORY_LIMIT), partlyReset.globalOverrideKeys)

        repository.resetGlobalToDefaults()
        val reset = repository.resetAppControlsToDefaults()
        assertEquals(BotSettingsSchema.defaults, reset.global)
        assertEquals(AppSettingsSchema.defaults, reset.app)
        assertTrue(reset.globalOverrideKeys.isEmpty())
        assertTrue(reset.appOverrideKeys.isEmpty())

        val commits = persistence.commitCount
        assertSame(reset, repository.resetGlobalToDefaults())
        assertSame(reset, repository.resetAppControlsToDefaults())
        assertEquals(commits, persistence.commitCount)
    }

    @Test
    fun sourceAliasesInvalidValuesAndDeadTtsOptionMigrateInOneTransaction() {
        val persistence =
            FakeSettingsPersistence(
                PersistedSettings(
                    revision = 7,
                    globalOverrides =
                        linkedMapOf(
                            "MODEL" to "anthropic/claude-sonnet",
                            "HISTORY" to "44",
                            "BOT_ENABLED" to "off",
                            BotSettingKeys.TEMPERATURE to "9",
                            "tts_script_model" to "obsolete/script-model",
                            "unknown_option" to "drop-me",
                        ),
                    appOverrides =
                        linkedMapOf(
                            "openrouterBaseUrl" to "https://example.invalid/v1",
                            "adminNumbers" to "+491234567,+498765432",
                            AppSettingKeys.OPENROUTER_API_KEY_REF to "sk-raw-secret",
                        ),
                    personaContactOverrides =
                        linkedMapOf(
                            " alice " to "GOTH",
                            "" to "human",
                        ),
                    proactiveContactOverrides =
                        linkedMapOf(
                            "alice" to "9",
                            "bob" to "99",
                        ),
                    retainedLegacy = mapOf("unrelated_old_key" to "drop-me"),
                ),
            )

        val repository = SettingsRepository(persistence)
        val snapshot = repository.snapshot()
        val report = repository.importReport

        assertEquals(1, persistence.commitCount)
        assertEquals(7L, report.originalRevision)
        assertEquals(8L, report.finalRevision)
        assertEquals(8L, snapshot.revision)
        assertEquals("anthropic/claude-sonnet", snapshot.text(BotSettingKeys.MODEL))
        assertEquals(44, snapshot.integer(BotSettingKeys.HISTORY_LIMIT))
        assertFalse(snapshot.boolean(BotSettingKeys.ENABLED))
        assertEquals(0.7, snapshot.decimal(BotSettingKeys.TEMPERATURE), 0.0)
        assertEquals(
            "https://example.invalid/v1",
            snapshot.appText(AppSettingKeys.OPENROUTER_BASE_URL),
        )
        assertEquals(
            listOf("+491234567", "+498765432"),
            snapshot.appStringList(AppSettingKeys.ADMIN_NUMBERS),
        )
        assertEquals(
            "openrouter_api_key",
            snapshot.secretReference(AppSettingKeys.OPENROUTER_API_KEY_REF),
        )
        assertEquals("goth", snapshot.effectivePersona("alice"))
        assertEquals(9, snapshot.effectiveProactiveLevel("alice"))
        assertEquals(setOf("tts_script_model"), snapshot.retainedLegacyKeys)
        assertFalse(snapshot.global.containsKey("tts_script_model"))
        assertFalse(BotSettingsSchema.byKey.containsKey("tts_script_model"))

        assertTrue(report.notices.any { it.kind == ImportNoticeKind.ALIAS_MIGRATED })
        assertTrue(report.notices.any { it.kind == ImportNoticeKind.INVALID_RESET_TO_DEFAULT })
        assertTrue(report.notices.any { it.kind == ImportNoticeKind.UNKNOWN_DROPPED })
        assertTrue(report.notices.any { it.kind == ImportNoticeKind.LEGACY_HIDDEN })
        assertTrue(report.notices.any { it.kind == ImportNoticeKind.CONTACT_OVERRIDE_DROPPED })
        assertTrue(report.notices.any { it.kind == ImportNoticeKind.SECRET_VALUE_REJECTED })

        assertEquals(
            setOf(BotSettingKeys.MODEL, BotSettingKeys.HISTORY_LIMIT, BotSettingKeys.ENABLED),
            persistence.state.globalOverrides.keys,
        )
        assertEquals(
            setOf(AppSettingKeys.OPENROUTER_BASE_URL, AppSettingKeys.ADMIN_NUMBERS),
            persistence.state.appOverrides.keys,
        )
        assertFalse(
            persistence.state.toString().contains("sk-raw-secret"),
        )
    }

    @Test
    fun canonicalKeyAlwaysShadowsAliasEvenWhenCanonicalIsInvalid() {
        val persistence =
            FakeSettingsPersistence(
                PersistedSettings(
                    globalOverrides =
                        linkedMapOf(
                            BotSettingKeys.MODEL to "canonical/model",
                            "MODEL" to "alias/model",
                            BotSettingKeys.TEMPERATURE to "9",
                            "TEMPERATURE" to "0.2",
                        ),
                ),
            )
        val repository = SettingsRepository(persistence)

        assertEquals("canonical/model", repository.snapshot().text(BotSettingKeys.MODEL))
        assertEquals(0.7, repository.snapshot().decimal(BotSettingKeys.TEMPERATURE), 0.0)
        assertTrue(
            repository.importReport.notices.count {
                it.kind == ImportNoticeKind.ALIAS_SHADOWED
            } >= 2,
        )
        assertEquals(
            mapOf(BotSettingKeys.MODEL to "canonical/model"),
            persistence.state.globalOverrides,
        )
    }

    @Test
    fun obsoleteTransportChoicesConvergeToTheSupportedRemotePersistentRuntime() {
        val persistence =
            FakeSettingsPersistence(
                PersistedSettings(
                    revision = 4,
                    appOverrides =
                        linkedMapOf(
                            AppSettingKeys.BRIDGE_MODE to "on_device",
                            AppSettingKeys.CONNECTION_MODE to "adaptive",
                            AppSettingKeys.AUTOSTART to "true",
                        ),
                ),
            )

        val repository = SettingsRepository(persistence)
        val snapshot = repository.snapshot()

        assertEquals(5L, snapshot.revision)
        assertFalse(snapshot.app.containsKey(AppSettingKeys.BRIDGE_MODE))
        assertFalse(snapshot.app.containsKey(AppSettingKeys.CONNECTION_MODE))
        assertTrue(snapshot.appBoolean(AppSettingKeys.AUTOSTART))
        assertEquals(
            emptyMap<String, String>(),
            persistence.state.appOverrides,
        )

        val transportNotices =
            repository.importReport.notices.filter {
                it.kind == ImportNoticeKind.LEGACY_TRANSPORT_NORMALIZED
            }
        assertEquals(2, transportNotices.size)
        assertEquals(
            setOf(AppSettingKeys.BRIDGE_MODE, AppSettingKeys.CONNECTION_MODE),
            transportNotices.mapTo(linkedSetOf(), ImportNotice::sourceKey),
        )
    }

    @Test
    fun obsoleteNineSecondProviderTimeoutMigratesWithoutOverwritingCustomTimeouts() {
        val legacyPersistence =
            FakeSettingsPersistence(
                PersistedSettings(
                    revision = 2,
                    appOverrides =
                        linkedMapOf(
                            AppSettingKeys.OPENROUTER_TIMEOUT_MS to "9000",
                        ),
                ),
            )

        val migrated = SettingsRepository(legacyPersistence)

        assertEquals(90_000, migrated.snapshot().appInteger(AppSettingKeys.OPENROUTER_TIMEOUT_MS))
        assertTrue(legacyPersistence.state.appOverrides.isEmpty())
        assertTrue(
            migrated.importReport.notices.any {
                it.sourceKey == AppSettingKeys.OPENROUTER_TIMEOUT_MS &&
                    it.kind == ImportNoticeKind.LEGACY_TRANSPORT_NORMALIZED
            },
        )

        val customPersistence =
            FakeSettingsPersistence(
                PersistedSettings(
                    appOverrides =
                        linkedMapOf(
                            AppSettingKeys.OPENROUTER_TIMEOUT_MS to "15000",
                        ),
                ),
            )
        val custom = SettingsRepository(customPersistence)

        assertEquals(15_000, custom.snapshot().appInteger(AppSettingKeys.OPENROUTER_TIMEOUT_MS))
        assertEquals(0, customPersistence.commitCount)
    }

    @Test
    fun rawSecretsAndAlternativeSecretReferencesAreRejected() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)

        expectThrows<SettingsValidationException> {
            repository.updateAppControls(
                mapOf(
                    AppSettingKeys.OPENROUTER_API_KEY_REF to
                        SettingValue.SecretReference("another_reference"),
                ),
            )
        }
        assertEquals(0, persistence.commitCount)

    }

    @Test
    fun specializedAndroidValuesAreValidatedAsOneBatch() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)

        val error =
            expectThrows<SettingsValidationException> {
                repository.updateAppControls(
                    linkedMapOf(
                        AppSettingKeys.OPENROUTER_BASE_URL to SettingValue.Text("not a URL"),
                        AppSettingKeys.COMMAND_PREFIX to SettingValue.Text("too long"),
                        AppSettingKeys.ADMIN_NUMBERS to
                            SettingValue.StringList.of(listOf("not-a-phone")),
                    ),
                )
            }
        assertEquals(
            setOf(
                AppSettingKeys.OPENROUTER_BASE_URL,
                AppSettingKeys.COMMAND_PREFIX,
                AppSettingKeys.ADMIN_NUMBERS,
            ),
            error.errors.keys,
        )
        assertEquals(0, persistence.commitCount)

        expectThrows<SettingsValidationException> {
            repository.updateAppControls(
                mapOf(AppSettingKeys.BRIDGE_MODE to SettingValue.Text("remote")),
            )
        }
        expectThrows<SettingsValidationException> {
            repository.updateAppControls(
                mapOf(AppSettingKeys.CONNECTION_MODE to SettingValue.Text("adaptive")),
            )
        }
        // bridge_url is migration-only now: writing it at all must be refused.
        expectThrows<SettingsValidationException> {
            repository.updateAppControls(
                mapOf(AppSettingKeys.BRIDGE_URL to SettingValue.Text("wss://bridge.example")),
            )
        }
        assertEquals(0, persistence.commitCount)

        expectThrows<SettingsValidationException> {
            repository.updateGlobal(
                mapOf(
                    BotSettingKeys.SLEEP_START to SettingValue.Text("25:61"),
                    BotSettingKeys.TIMEZONE to SettingValue.Text("Mars/Olympus"),
                ),
            )
        }
        assertEquals(0, persistence.commitCount)
    }

    /**
     * Another writer can advance the durable revision between this repository's last read and its
     * commit — the engine, or the per-chat override path, which bumps the revision without going
     * through here. That used to surface as a raw "expected 134, actual 136" and drop the edit,
     * which locked the settings screen: once the cached revision was stale, every later change
     * failed the same way. The two writes almost never touch the same key, so rebase and retry.
     */
    @Test
    fun anOutOfBandWriteIsRebasedOntoRatherThanRefused() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        persistence.replaceExternally(
            PersistedSettings(
                revision = 4,
                globalOverrides = mapOf(BotSettingKeys.HISTORY_LIMIT to "55"),
            ),
        )

        val updated =
            repository.updateGlobal(
                mapOf(BotSettingKeys.TEMPERATURE to SettingValue.Decimal(1.0)),
            )

        assertEquals(5L, updated.revision)
        assertEquals(1.0, updated.decimal(BotSettingKeys.TEMPERATURE), 1e-9)
        // The other writer's value survived: this is a rebase, not a last-writer-wins overwrite.
        assertEquals(55, updated.integer(BotSettingKeys.HISTORY_LIMIT))
        assertSame(updated, repository.snapshot())
        assertEquals(1, persistence.commitCount)
    }

    /** Rebasing is bounded: a revision that never settles is reported instead of retried forever. */
    @Test
    fun aRevisionThatKeepsMovingIsStillReported() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)
        persistence.beforeCommit = {
            persistence.replaceExternally(
                persistence.state.copy(revision = persistence.state.revision + 1),
            )
        }

        val conflict =
            expectThrows<SettingsRevisionConflictException> {
                repository.updateGlobal(
                    mapOf(BotSettingKeys.TEMPERATURE to SettingValue.Decimal(1.0)),
                )
            }

        assertTrue(conflict.actualRevision > conflict.expectedRevision)
        assertEquals(0, persistence.commitCount)
    }

    /**
     * P8: switching the TTS model used to leave the previous provider's voice behind, so the next
     * voice note failed or came out in an unrelated voice. The repair sits at the single durable
     * write point, which covers the settings screen, admin commands, presets and env import at once.
     */
    @Test
    fun switchingTheTtsModelRepairsIncompatibleVoices() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)

        val snapshot =
            repository.updateGlobal(
                mapOf(BotSettingKeys.TTS_MODEL to SettingValue.Text("openai/gpt-4o-mini-tts")),
            )

        assertEquals("openai/gpt-4o-mini-tts", snapshot.text(BotSettingKeys.TTS_MODEL))
        assertEquals("coral", snapshot.text(BotSettingKeys.TTS_VOICE))
    }

    @Test
    fun aVoiceTheNewModelAcceptsIsLeftAlone() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)

        val snapshot =
            repository.updateGlobal(
                mapOf(BotSettingKeys.TTS_MODEL to SettingValue.Text(TtsVoiceCatalog.DEFAULT_REMOTE_MODEL)),
            )

        assertEquals("Kore", snapshot.text(BotSettingKeys.TTS_VOICE))
    }

    /** A voice sent together with the model in one write is repaired too, not just the stored one. */
    @Test
    fun aVoiceWrittenAlongsideAnIncompatibleModelIsRepaired() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)

        val snapshot =
            repository.updateGlobal(
                linkedMapOf(
                    BotSettingKeys.TTS_MODEL to SettingValue.Text(TtsVoiceCatalog.GROK_MODEL),
                    BotSettingKeys.TTS_VOICE to SettingValue.Text("Orus"),
                ),
            )

        // Grok has no "Orus"; the mapping keeps the perceived gender instead of resetting to Eve.
        assertEquals("Rex", snapshot.text(BotSettingKeys.TTS_VOICE))
    }

    @Test
    fun providerReportedVoicesOverrideTheOfflineFallback() {
        val repository = SettingsRepository(FakeSettingsPersistence())
        val snapshot =
            repository.updateGlobal(
                mapOf(BotSettingKeys.TTS_MODEL to SettingValue.Text("provider/surfer-tts")),
                ttsCatalogVoices = listOf("Wave", "Breeze"),
            )

        assertEquals("Wave", snapshot.text(BotSettingKeys.TTS_VOICE))
    }

    @Test
    fun aWriteWithoutTheTtsModelNeverTouchesVoices() {
        val persistence = FakeSettingsPersistence()
        val repository = SettingsRepository(persistence)

        val snapshot =
            repository.updateGlobal(
                mapOf(BotSettingKeys.TTS_VOICE to SettingValue.Text("Puck")),
            )

        assertEquals("Puck", snapshot.text(BotSettingKeys.TTS_VOICE))
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    }
}
