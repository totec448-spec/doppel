package de.totec.doppel.integration

import de.totec.doppel.commands.AccessList
import de.totec.doppel.commands.AccessOperation
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AdminActions
import de.totec.doppel.commands.AdminPayload
import de.totec.doppel.commands.AdminRequest
import de.totec.doppel.commands.AdminResult
import de.totec.doppel.commands.BlockSource
import de.totec.doppel.commands.BlockedContact
import de.totec.doppel.commands.MediaAssetSummary
import de.totec.doppel.commands.MemoryScope
import de.totec.doppel.commands.MemorySummary
import de.totec.doppel.commands.PersonaAssignment
import de.totec.doppel.commands.PersonaDataSummary
import de.totec.doppel.commands.PersonaSummary
import de.totec.doppel.commands.ProactiveContact
import de.totec.doppel.commands.SettingSnapshot
import de.totec.doppel.commands.VoiceOption
import de.totec.doppel.commands.WipeTarget
import de.totec.doppel.commands.normalizePhoneNumber
import de.totec.doppel.data.db.AccessEntryRecord
import de.totec.doppel.data.db.AccessListKind
import de.totec.doppel.data.db.AccessSubjectType
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.ChatKind
import de.totec.doppel.data.db.ChatRecord
import de.totec.doppel.data.db.MemoryDocumentSummary
import de.totec.doppel.data.db.OutboundDecision
import de.totec.doppel.data.db.OutboundSafetyRecord
import de.totec.doppel.data.db.OutboundStatus
import de.totec.doppel.data.db.PersonaRecord
import de.totec.doppel.data.resetConversationHistory
import de.totec.doppel.media.ApprovedMediaAsset
import de.totec.doppel.media.ApprovedMediaAssetStore
import de.totec.doppel.media.ApprovedMediaKind
import de.totec.doppel.runtime.REMOTE_BLOCK_LABEL
import de.totec.doppel.runtime.RuntimeStateStore
import de.totec.doppel.security.SecretStore
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.BotSettingsSchema
import de.totec.doppel.settings.PersonaVoices
import de.totec.doppel.settings.SettingValue
import de.totec.doppel.settings.SettingsCatalogs
import de.totec.doppel.settings.SettingsRepository
import de.totec.doppel.settings.SettingsSnapshot
import de.totec.doppel.settings.SettingsValidationException
import de.totec.doppel.settings.parseSettingInput
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Shared implementation behind WhatsApp admin commands and the Compose admin
 * UI. Every mutation goes through the same typed settings/DB/transport ports.
 */
class NativeAdminActions(
    private val repository: BotRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val approvedMedia: ApprovedMediaAssetStore,
    private val activityChanged: () -> Unit = {},
    /**
     * Which of a persona's pictures is on the account right now, read from the rotator's local
     * state. A lambda rather than the rotator itself: this is a marker in a list, and the admin
     * layer must not be able to change what WhatsApp is showing as a side effect of listing it.
     */
    private val liveProfilePicture: (String) -> String? = { null },
) : AdminActions {
    /**
     * Runs [block] and turns a failure into a [Result], without ever claiming that a cancellation
     * was one.
     *
     * Every transport call below used to sit inside `runCatching { runBlocking { … } }`. Now that
     * they suspend, plain `runCatching` would catch the [CancellationException] the runtime throws
     * on shutdown and answer the command with a tidy "the bridge failed" — while the coroutine
     * that was told to stop carries on. Cancellation is not an outcome of the action; it is the
     * end of the caller.
     */
    private suspend fun <T> bridgeCall(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    override suspend fun execute(request: AdminRequest): AdminResult =
        try {
            val result = dispatch(request)
            // A lookup that succeeded changed nothing, and the operator log keeps only the last
            // 100 rows: auditing every screen the user opens would push the records that matter
            // out of view, and the reload it triggers would re-read the log for no new content.
            // A failed lookup is still worth a row.
            val silent = result is AdminResult.Success && request.action.isPureRead()
            if (!silent) {
                if (request.action !is AdminAction.ApiKeyStatus &&
                    request.action !is AdminAction.SetApiKey
                ) {
                    log(request, result)
                }
                activityChanged()
            }
            result
        } catch (error: SettingsValidationException) {
            AdminResult.Invalid(
                field = error.errors.keys.firstOrNull(),
                reason = error.errors.values.firstOrNull() ?: "Invalid value",
            )
        } catch (_: IllegalArgumentException) {
            AdminResult.Invalid(null, "Invalid input")
        } catch (error: AdminTransportFailure) {
            AdminResult.Failure(error.safeMessage)
        } catch (cancelled: CancellationException) {
            // Dispatch suspends, so this catch is now in the runtime's shutdown path too.
            throw cancelled
        } catch (_: Exception) {
            AdminResult.Failure("The action could not be completed")
        }

    private suspend fun dispatch(request: AdminRequest): AdminResult =
        when (val action = request.action) {
            AdminAction.Help ->
                success(
                    AdminPayload.HelpHeader(
                        introduction =
                            "The same typed admin actions are available in the app and in the chat.",
                    ),
                )

            AdminAction.Status -> status()
            AdminAction.MoodStatus ->
                success(
                    AdminPayload.Mood(
                        enabled = settings.snapshot().boolean(BotSettingKeys.MOOD_ENABLED),
                        name = "time based",
                    ),
                )

            AdminAction.Meta -> meta()
            AdminAction.ListSettings -> success(AdminPayload.Settings(settingSnapshots()))
            is AdminAction.GetSetting -> getSetting(action.key)
            is AdminAction.SetSettings -> setSettings(action.changes)
            AdminAction.ResetAllGlobalSettings -> {
                settings.resetGlobalToDefaults()
                success(AdminPayload.Text("Global settings have been reset."))
            }

            AdminAction.PauseBot -> {
                settings.updateGlobal(mapOf(BotSettingKeys.ENABLED to SettingValue.Bool(false)))
                success()
            }

            AdminAction.ResumeBot -> {
                settings.updateGlobal(mapOf(BotSettingKeys.ENABLED to SettingValue.Bool(true)))
                success()
            }

            is AdminAction.ListAccess -> listAccess(action.list)
            is AdminAction.ChangeAccess -> changeAccess(action.list, action.operation, action.entries)
            AdminAction.ApiKeyStatus -> apiKeyStatus()
            is AdminAction.SetApiKey -> setApiKey(action)
            AdminAction.ClearApiKey -> {
                secrets.remove(SecretStore.OPENROUTER_API_KEY)
                success(AdminPayload.SecretStatus(false, null, "Android Keystore", false))
            }

            is AdminAction.ListMemories -> listMemories(action.limit)
            is AdminAction.OpenMemory -> openMemory(action.scope, action.id)
            is AdminAction.UpdateMemory -> updateMemory(action)
            is AdminAction.CreateChatMemory -> createChatMemory(action.chatId)
            is AdminAction.CreateGlobalMemory -> createGlobalMemory(action.personaKey)
            is AdminAction.DeleteMemory -> deleteMemory(action.scope, action.id)
            is AdminAction.ResetChat -> resetChat(action.chatId, action.resetGlobalSettings)
            is AdminAction.ClearChatMemory -> clearChatMemory(action.chatId)
            is AdminAction.Wipe -> wipe(action.target)
            AdminAction.ListPersonaData -> listPersonaData()
            AdminAction.ListPersonas -> listPersonas(request.context.chatId)
            is AdminAction.UpsertPersona -> upsertPersona(action)
            is AdminAction.DeletePersona -> deletePersona(action.key)
            is AdminAction.AssignPersona -> assignPersona(action.key, action.targetChatId)
            is AdminAction.UnassignPersona -> {
                settings.setContactPersona(action.targetChatId, null)
                repository.clearPersonaAssignment(action.targetChatId)
                success()
            }

            is AdminAction.PersonaImages -> listPersonaImages(action.key)
            is AdminAction.DeletePersonaImage ->
                deletePersonaImage(action.key, action.assetId)
            is AdminAction.PersonaImageReferences -> listPersonaImageReferences(action.key)
            is AdminAction.DeletePersonaImageReference ->
                deletePersonaImageReference(action.key, action.assetId)
            is AdminAction.DeleteAllPersonaImageReferences ->
                deleteAllPersonaImageReferences(action.key)
            is AdminAction.PersonaProfilePictures -> listPersonaProfilePictures(action.key)
            is AdminAction.DeletePersonaProfilePicture ->
                deletePersonaProfilePicture(action.key, action.assetId)
            is AdminAction.SendPersonaImage ->
                if (request.context.origin == de.totec.doppel.commands.AdminOrigin.APP) {
                    sendPersonaImage(action)
                } else {
                    AdminResult.Denied("Sending a picture by hand is only allowed from the local app")
                }
            is AdminAction.SendManualText ->
                if (request.context.origin == de.totec.doppel.commands.AdminOrigin.APP) {
                    sendManualText(action)
                } else {
                    AdminResult.Denied("Sending a test message by hand is only allowed from the local app")
                }

            AdminAction.VoiceStatus -> {
                val snapshot = settings.snapshot()
                success(
                    AdminPayload.Text(
                        "Voice: ${if (snapshot.boolean(BotSettingKeys.TTS_ENABLED)) "on" else "off"}, " +
                            "default ${snapshot.text(BotSettingKeys.TTS_VOICE)}",
                    ),
                )
            }

            AdminAction.ListVoices ->
                success(
                    AdminPayload.Voices(
                        SettingsCatalogs.voices.map { VoiceOption(it.name, it.description) },
                    ),
                )

            is AdminAction.SetDefaultVoice -> {
                requireVoice(action.voice)
                settings.updateGlobal(
                    mapOf(BotSettingKeys.TTS_VOICE to SettingValue.Text(action.voice)),
                )
                success()
            }

            AdminAction.ListBlocks -> listBlocks()
            is AdminAction.BlockContact -> block(action.number, action.reason)
            is AdminAction.UnblockContact -> unblock(action.number)
            AdminAction.ProactiveStatus ->
                success(
                    AdminPayload.Text(
                        "Globales Proaktiv-Level: ${settings.snapshot().integer(BotSettingKeys.PROACTIVE_LEVEL)}",
                    ),
                )

            AdminAction.ListProactiveContacts -> listProactiveContacts()

            is AdminAction.WriteContactNow -> writeContactNow(action)

            is AdminAction.GetProactiveOverride -> {
                val snapshot = settings.snapshot()
                success(
                    AdminPayload.ProactiveOverride(
                        target = action.target,
                        level = snapshot.proactiveContactOverrides[action.target],
                        globalLevel = snapshot.integer(BotSettingKeys.PROACTIVE_LEVEL),
                    ),
                )
            }

            is AdminAction.SetGlobalProactiveLevel -> {
                settings.updateGlobal(
                    mapOf(BotSettingKeys.PROACTIVE_LEVEL to SettingValue.Integer(action.level)),
                )
                success()
            }

            is AdminAction.SetProactiveOverride -> {
                settings.setContactProactiveLevel(action.target, action.level)
                // Raising the level for somebody the bot has never heard from is the explicit
                // consent to approach them; it creates the scheduler row that a purely reactive
                // history would never produce. Lowering it leaves the row alone — the coordinator
                // disables it on the next reconcile and re-enables it if the level comes back up.
                if (action.level > 0) {
                    // Arm the chat that exists rather than the address that was typed. Creating
                    // the stub under a phone number while the conversation runs on a LID is what
                    // splits one contact into two rows, the second of which claims no history.
                    repository.armColdProactive(outreachTarget(requireChatJid(action.target)))
                }
                success()
            }

            is AdminAction.ClearProactiveOverride -> {
                settings.setContactProactiveLevel(action.target, null)
                success()
            }

            AdminAction.SafetyStatus -> safetyStatus()
            is AdminAction.SafetyEvents -> safetyEvents(action.limit)
            AdminAction.RefreshSafety -> refreshSafety()
            is AdminAction.AcknowledgeSafetyLock -> markSafety(action.id, "acknowledged")
            is AdminAction.ClearSafetyLock -> markSafety(action.id, "cleared")
            is AdminAction.HoldSafety -> holdSafety(action.durationMs, action.reason)
            AdminAction.Reachout -> refreshSafety()
        }

    private fun status(): AdminResult {
        val runtime = RuntimeStateStore.state.value
        val snapshot = settings.snapshot()
        return success(
            AdminPayload.Text(
                buildString {
                    append("Runtime: ${runtime.phase.name.lowercase()}")
                    runtime.detail?.takeIf(String::isNotBlank)?.let { append(" ($it)") }
                    append("\nBot: ${if (snapshot.boolean(BotSettingKeys.ENABLED)) "running" else "paused"}")
                    append("\nModel: ${snapshot.text(BotSettingKeys.MODEL)}")
                    append("\nPersona: ${snapshot.text(BotSettingKeys.PERSONALITY)}")
                },
            ),
        )
    }

    private fun meta(): AdminResult =
        success(
            AdminPayload.Text(
                "Chats: ${repository.countChats(includeArchived = true, limit = 1_000)}; " +
                    "Safety entries: ${repository.countOutboundSafety(limit = 1_000)}; " +
                    "Settings-Revision: ${settings.snapshot().revision}",
            ),
        )

    private fun settingSnapshots(): List<SettingSnapshot> {
        val snapshot = settings.snapshot()
        return BotSettingsSchema.all.map { spec ->
            SettingSnapshot(
                key = spec.key,
                value = snapshot.value(spec.key).displayValue(),
                defaultValue = spec.defaultValue.displayValue(),
                description = spec.description,
                overridden = spec.key in snapshot.globalOverrideKeys,
            )
        }
    }

    private fun getSetting(key: String): AdminResult {
        val normalized = key.trim().lowercase(Locale.ROOT)
        val value = settingSnapshots().firstOrNull { it.key == normalized }
            ?: return AdminResult.NotFound(normalized)
        return success(AdminPayload.Setting(value))
    }

    private fun setSettings(changes: Map<String, String>): AdminResult {
        require(changes.isNotEmpty()) { "No changes" }
        val parsed = LinkedHashMap<String, SettingValue>(changes.size)
        changes.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim().lowercase(Locale.ROOT)
            val spec = BotSettingsSchema.byKey[key] ?: return AdminResult.NotFound(key)
            val value =
                try {
                    parseSettingInput(spec, rawValue)
                } catch (error: IllegalArgumentException) {
                    return AdminResult.Invalid(key, error.message ?: "The value has the wrong type")
                }
            parsed[key] = value
        }
        val updated = settings.updateGlobal(parsed)
        return success(
            AdminPayload.SettingsChanged(
                parsed.mapValues { (key, _) -> updated.value(key).displayValue() },
            ),
        )
    }

    private fun listAccess(list: AccessList): AdminResult {
        val values = accessValues(list)
        return success(AdminPayload.AccessEntries(list, values))
    }

    private fun changeAccess(
        list: AccessList,
        operation: AccessOperation,
        rawEntries: List<String>,
    ): AdminResult {
        val key =
            when (list) {
                AccessList.ALLOW -> AppSettingKeys.ALLOWLIST_NUMBERS
                AccessList.GROUP_ALLOW -> AppSettingKeys.GROUP_ALLOWLIST
                AccessList.ADMIN -> AppSettingKeys.ADMIN_NUMBERS
            }
        // Two different lists, and confusing them corrupted the stored one. [accessValues] answers
        // "who has this access", which for admin means owners *plus* admins — an owner is an admin
        // by definition. What this writes back is only the list it owns. Mutating the union and
        // storing the result copied every owner into the admin list on the first change and left
        // them there, so the two lists drifted into each other with every edit.
        val stored = settings.snapshot().appStringList(key)
        val current = accessValues(list)
        val normalized =
            rawEntries.mapNotNull { normalizeAccessEntry(list, it) }.distinct()
        val owners =
            if (list == AccessList.ADMIN) {
                settings.snapshot().appStringList(AppSettingKeys.OWNER_NUMBERS)
            } else {
                emptyList()
            }
        if (list == AccessList.ADMIN) {
            val ownersSurvive =
                when (operation) {
                    AccessOperation.REMOVE -> owners.none { it in normalized }
                    AccessOperation.REPLACE -> owners.all { it in normalized }
                    AccessOperation.ADD -> true
                }
            if (!ownersSurvive) {
                return AdminResult.Denied("Owners are always admins and cannot be removed")
            }
        }
        val next =
            when (operation) {
                AccessOperation.ADD -> stored + normalized
                AccessOperation.REMOVE -> stored.filterNot { it in normalized }
                AccessOperation.REPLACE -> normalized
            }.distinct().filterNot { it in owners }
        settings.updateAppControls(mapOf(key to SettingValue.StringList.of(next)))
        mirrorAccess(list, stored, next)
        // Counted against what has access rather than against what is stored, so adding a number
        // that was already an owner honestly reports "nothing changed".
        val effective = (owners + next).distinct()
        return success(
            AdminPayload.AccessChanged(
                changed = symmetricDifferenceSize(current, effective),
                total = effective.size,
            ),
        )
    }

    private fun accessValues(list: AccessList): List<String> {
        val snapshot = settings.snapshot()
        return when (list) {
            AccessList.ALLOW -> snapshot.appStringList(AppSettingKeys.ALLOWLIST_NUMBERS)
            AccessList.GROUP_ALLOW -> snapshot.appStringList(AppSettingKeys.GROUP_ALLOWLIST)
            AccessList.ADMIN ->
                (
                    snapshot.appStringList(AppSettingKeys.OWNER_NUMBERS) +
                        snapshot.appStringList(AppSettingKeys.ADMIN_NUMBERS)
                    ).distinct()
        }
    }

    private fun mirrorAccess(
        list: AccessList,
        before: List<String>,
        after: List<String>,
    ) {
        val kind = list.dbKind()
        val type =
            if (list == AccessList.GROUP_ALLOW) AccessSubjectType.GROUP else AccessSubjectType.PHONE
        (before - after.toSet()).forEach { repository.removeAccessEntry(kind, type, it) }
        after.forEach {
            repository.upsertAccessEntry(
                AccessEntryRecord(
                    listKind = kind,
                    subjectType = type,
                    subjectId = it,
                ),
            )
        }
    }

    private fun apiKeyStatus(): AdminResult {
        val configured = secrets.has(SecretStore.OPENROUTER_API_KEY)
        val suffix =
            if (configured) {
                secrets.get(SecretStore.OPENROUTER_API_KEY)?.takeLast(4)
            } else {
                null
            }
        return success(
            AdminPayload.SecretStatus(
                configured = configured,
                lastFour = suffix,
                source = "Android Keystore",
                overrideActive = configured,
            ),
        )
    }

    private fun setApiKey(action: AdminAction.SetApiKey): AdminResult {
        var suffix: String? = null
        action.secret.use { chars ->
            require(chars.size in 16..512) { "API key length" }
            val value = String(chars)
            secrets.put(SecretStore.OPENROUTER_API_KEY, value)
            suffix = value.takeLast(4)
        }
        return success(
            AdminPayload.SecretStatus(true, suffix, "Android Keystore", true),
        )
    }

    private fun resetChat(
        chatId: String,
        resetGlobalSettings: Boolean,
    ): AdminResult {
        val persona = settings.snapshot().effectivePersona(chatId)
        val affected = resetConversationHistory(repository, approvedMedia, "$chatId#$persona")
        if (resetGlobalSettings) settings.resetGlobalToDefaults()
        return success(AdminPayload.Text("$affected history records reset for $persona."))
    }

    private fun clearChatMemory(chatId: String): AdminResult {
        val persona = settings.snapshot().effectivePersona(chatId)
        val conversationKey = "$chatId#$persona"
        val changed = repository.clearChatMemorySummary(conversationKey)
        return success(
            AdminPayload.Text(
                if (changed) "Chat memory cleared for $persona." else "No chat memory existed for $persona.",
            ),
        )
    }

    /**
     * Deletes a chat's whole timeline, one bounded page per transaction.
     *
     * The page used to be deleted row by row, which is one transaction and one cache invalidation
     * per message — a chat with a few thousand messages paid thousands of both while the admin
     * waited. The batch delete does the same work per page.
     */
    private fun clearMessages(chatId: String): Int {
        var deleted = 0
        while (true) {
            val page = repository.listMessages(chatId, limit = CLEAR_PAGE_SIZE)
            if (page.isEmpty()) break
            val removed = repository.deleteMessages(page.map { it.providerMessageId })
            deleted += removed
            // The loop re-reads from the top, so a page that deletes nothing would be read
            // forever. It cannot happen while the rows are keyed by the ID they were just read
            // by — but "cannot happen" is not a reason for an unbounded loop to depend on it.
            if (removed == 0) break
        }
        return deleted
    }

    private fun wipe(target: WipeTarget): AdminResult {
        val affectedChats =
            when (target) {
                WipeTarget.All -> {
                    val chatIds = repository.listAllChatIdsForMaintenance()
                    repository.deleteAllConversationData()
                    chatIds.forEach(approvedMedia::clearSentHistory)
                    chatIds.size
                }

                is WipeTarget.Persona -> {
                    val (chatIds, _) = repository.deletePersonaConversationData(target.key)
                    chatIds.forEach { approvedMedia.clearSentHistory(it, target.key) }
                    chatIds.size
                }
            }
        return success(AdminPayload.WipeSummary(target, affectedChats))
    }

    private fun listPersonaData(): AdminResult {
        val custom = repository.listPersonas(limit = 1_000).associateBy { it.personaId }
        val builtInNames = SettingsCatalogs.personas.associate { it.key to it.label }
        val entries =
            repository.listPersonaIdsWithConversationData(limit = 1_000).map { key ->
                PersonaDataSummary(
                    key = key,
                    displayName =
                        builtInNames[key]
                            ?: custom[key]?.name?.takeIf(String::isNotBlank)
                            ?: key.replaceFirstChar { it.titlecase() },
                )
            }
        return success(AdminPayload.PersonaData(entries))
    }

    private fun listPersonas(currentChat: String?): AdminResult {
        val custom = repository.listPersonas(limit = 1_000).associateBy { it.personaId }
        val builtIns =
            SettingsCatalogs.personas.map {
                PersonaSummary(
                    key = it.key,
                    displayName = it.label,
                    description =
                        custom[it.key]?.systemPrompt?.takeIf(String::isNotBlank)
                            ?: PersonaBehavior.instructions(it.key),
                    builtIn = true,
                    // The bundled voice is shown for a persona the seeder has not reached yet, so
                    // the picker never claims a persona has no voice when it is about to get one.
                    voice =
                        PersonaVoices.read(custom[it.key]?.voiceConfigJson)
                            ?: PersonaVoices.baseVoice(it.key),
                )
            }
        val extra =
            custom.values
                .filter { record -> SettingsCatalogs.personas.none { it.key == record.personaId } }
                .map {
                    PersonaSummary(
                        key = it.personaId,
                        displayName = it.name,
                        description = it.systemPrompt,
                        builtIn = false,
                        voice = PersonaVoices.read(it.voiceConfigJson),
                    )
                }
        val snapshot = settings.snapshot()
        return success(
            AdminPayload.Personas(
                activeKey =
                    currentChat?.let(snapshot::effectivePersona)
                        ?: snapshot.text(BotSettingKeys.PERSONALITY),
                entries = builtIns + extra,
                assignments =
                    snapshot.personaContactOverrides.map {
                        PersonaAssignment(it.key, it.value)
                    },
            ),
        )
    }

    private fun upsertPersona(action: AdminAction.UpsertPersona): AdminResult {
        val key = action.key.trim().lowercase(Locale.ROOT)
        require(PERSONA_KEY.matches(key)) { "Persona-Key" }
        val voice = action.voice?.trim().orEmpty()
        require(voice.isEmpty() || SettingsCatalogs.voices.any { it.name.equals(voice, true) }) {
            "Unknown voice"
        }
        val existing = repository.getPersona(key)
        repository.upsertPersona(
            PersonaRecord(
                personaId = key,
                name =
                    action.name?.trim()?.take(80)?.takeIf(String::isNotEmpty)
                        ?: existing?.name
                        ?: key.replaceFirstChar(Char::uppercase),
                description = existing?.description ?: "Eigene Persona",
                systemPrompt = action.prompt.trim().take(MAX_PROMPT_CHARS),
                // Saving a prompt used to wipe the voice, because this wrote a flat null. A caller
                // that says nothing about the voice must leave the one already stored alone.
                voiceConfigJson =
                    if (voice.isEmpty()) {
                        existing?.voiceConfigJson
                    } else {
                        PersonaVoices.write(existing?.voiceConfigJson, voice)
                    },
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ),
        )
        return success()
    }

    private fun deletePersona(key: String): AdminResult {
        val normalized = key.trim().lowercase(Locale.ROOT)
        require(PERSONA_KEY.matches(normalized)) { "Persona-Key" }
        if (SettingsCatalogs.personas.any { it.key == normalized }) {
            return AdminResult.Denied("Built-in personas cannot be deleted")
        }
        if (approvedMedia.hasAssets(normalized) || approvedMedia.hasReferences(normalized)) {
            return AdminResult.Denied(
                "The persona's approved images and character references have to be deleted first",
            )
        }
        val assignedCount =
            settings.snapshot().personaContactOverrides.values.count { it == normalized }
        if (assignedCount > 0) {
            return AdminResult.Denied(
                "Persona is still assigned to $assignedCount chat(s); unassign it first",
            )
        }
        return if (repository.deletePersona(normalized)) success() else AdminResult.NotFound(normalized)
    }

    private fun assignPersona(
        key: String,
        chatId: String,
    ): AdminResult {
        val exists =
            SettingsCatalogs.personas.any { it.key == key } ||
                repository.getPersona(key) != null
        if (!exists) return AdminResult.NotFound(key)
        settings.setContactPersona(chatId, key)
        return success()
    }

    private fun listPersonaImages(key: String): AdminResult {
        val persona = requireKnownPersona(key)
        return success(
            AdminPayload.ImageAssets(
                personaKey = persona,
                entries =
                    approvedMedia
                        .list(
                            persona,
                            limit = ApprovedMediaAssetStore.MAX_ASSETS_PER_PERSONA,
                        )
                        .map {
                            MediaAssetSummary(
                                assetId = it.assetId,
                                displayName = it.displayName,
                                mimeType = it.mimeType,
                                sizeBytes = it.sizeBytes,
                                createdAtMs = it.createdAtMs,
                            )
                        },
            ),
        )
    }

    private fun deletePersonaImage(
        key: String,
        assetId: String,
    ): AdminResult {
        val persona = requireKnownPersona(key)
        if (!approvedMedia.delete(assetId, persona)) return AdminResult.NotFound(assetId)
        return listPersonaImages(persona)
    }

    private fun listPersonaImageReferences(key: String): AdminResult {
        val persona = requireKnownPersona(key)
        return success(
            AdminPayload.ImageReferences(
                personaKey = persona,
                entries =
                    approvedMedia
                        .list(
                            persona,
                            limit = ApprovedMediaAssetStore.MAX_REFERENCES_PER_PERSONA,
                            kind = ApprovedMediaKind.CHARACTER_REFERENCE,
                        )
                        .map {
                            MediaAssetSummary(
                                assetId = it.assetId,
                                displayName = it.displayName,
                                mimeType = it.mimeType,
                                sizeBytes = it.sizeBytes,
                                createdAtMs = it.createdAtMs,
                            )
                        },
            ),
        )
    }

    private fun deletePersonaImageReference(
        key: String,
        assetId: String,
    ): AdminResult {
        val persona = requireKnownPersona(key)
        if (
            !approvedMedia.delete(
                assetId,
                persona,
                ApprovedMediaKind.CHARACTER_REFERENCE,
            )
        ) {
            return AdminResult.NotFound(assetId)
        }
        return listPersonaImageReferences(persona)
    }

    private fun deleteAllPersonaImageReferences(key: String): AdminResult {
        val persona = requireKnownPersona(key)
        approvedMedia.deleteAll(persona, ApprovedMediaKind.CHARACTER_REFERENCE)
        return listPersonaImageReferences(persona)
    }

    /**
     * Oldest first, which is the order the rotation walks them in: what the list shows is what
     * will happen, not an arbitrary catalogue order.
     */
    private fun listPersonaProfilePictures(key: String): AdminResult {
        val persona = requireKnownPersona(key)
        return success(
            AdminPayload.ProfilePictures(
                personaKey = persona,
                entries =
                    approvedMedia
                        .list(
                            persona,
                            limit = ApprovedMediaAssetStore.MAX_PROFILE_PICTURES_PER_PERSONA,
                            kind = ApprovedMediaKind.PROFILE_PICTURE,
                        )
                        .sortedWith(
                            compareBy(ApprovedMediaAsset::createdAtMs, ApprovedMediaAsset::assetId),
                        )
                        .map {
                            MediaAssetSummary(
                                assetId = it.assetId,
                                displayName = it.displayName,
                                mimeType = it.mimeType,
                                sizeBytes = it.sizeBytes,
                                createdAtMs = it.createdAtMs,
                            )
                        },
                live = liveProfilePicture(persona),
            ),
        )
    }

    private fun deletePersonaProfilePicture(
        key: String,
        assetId: String,
    ): AdminResult {
        val persona = requireKnownPersona(key)
        if (
            !approvedMedia.delete(
                assetId,
                persona,
                ApprovedMediaKind.PROFILE_PICTURE,
            )
        ) {
            return AdminResult.NotFound(assetId)
        }
        // The picture on the account is deliberately left alone: WhatsApp broadcasts every avatar
        // change to every contact, and deleting a file from a list is not a reason to send one.
        // The next rotation picks a survivor, and the header falls back to the initial until then.
        return listPersonaProfilePictures(persona)
    }

    private suspend fun sendPersonaImage(action: AdminAction.SendPersonaImage): AdminResult {
        val persona = requireKnownPersona(action.key)
        val target = requireChatJid(action.targetChatId)
        require(ADMIN_REQUEST_ID.matches(action.requestId)) { "Invalid request ID" }
        // Resolve before crossing the runtime seam. The runtime resolves again
        // immediately before upload, closing deletion/tampering races.
        approvedMedia.openForSend(action.assetId, persona)
        val messageId =
            bridgeCall {
                RuntimeBridgeControl.sendApprovedImage(
                    personaKey = persona,
                    assetId = action.assetId,
                    targetChatId = target,
                    caption = action.caption?.trim()?.take(MAX_IMAGE_CAPTION_CHARS),
                    requestId = action.requestId,
                )
            }.getOrElse {
                throw AdminTransportFailure(
                    "Sending the picture over WhatsApp failed; check the activity log.",
                )
            }
        return success(
            AdminPayload.ImageSent(
                personaKey = persona,
                assetId = action.assetId,
                targetChatId = target,
                transportMessageId = messageId,
            ),
        )
    }

    private suspend fun sendManualText(action: AdminAction.SendManualText): AdminResult {
        val target = requireChatJid(action.targetChatId)
        val text = action.text.trim()
        require(text.isNotEmpty() && text.length <= MAX_MANUAL_TEXT_CHARS) {
            "Invalid test text"
        }
        require(ADMIN_REQUEST_ID.matches(action.requestId)) { "Invalid request ID" }
        val messageId =
            bridgeCall {
                RuntimeBridgeControl.sendManualText(
                    targetChatId = target,
                    text = text,
                    requestId = action.requestId,
                )
            }.getOrElse {
                throw AdminTransportFailure("The native test send failed; check the activity log.")
            }
        return success(AdminPayload.Text("Native test text sent ($messageId)."))
    }

    private fun requireKnownPersona(value: String): String {
        val key = value.trim().lowercase(Locale.ROOT)
        require(PERSONA_KEY.matches(key)) { "Persona-Key" }
        require(
            SettingsCatalogs.personas.any { it.key == key } ||
                repository.getPersona(key) != null,
        ) { "Unbekannte Persona" }
        return key
    }

    private fun requireChatJid(value: String): String {
        val normalized = value.trim()
        val phone =
            normalized
                .takeIf(PHONE_INPUT::matches)
                ?.filter(Char::isDigit)
                ?.takeIf { it.length in 7..15 }
        if (phone != null) return "$phone@s.whatsapp.net"
        require(normalized.length in 8..160 && CHAT_JID.matches(normalized)) {
            "Invalid chat JID"
        }
        return normalized
    }

    /**
     * The roster behind the proactivity screen: every 1:1 chat with history, plus everybody on
     * the allowlist even if the bot has never heard from them. Groups are left out — proactive
     * outreach is a one-to-one feature. Blocked contacts stay in the list, marked, so the reason
     * a slider does nothing is visible instead of the row silently disappearing.
     */
    private fun listProactiveContacts(): AdminResult {
        val snapshot = settings.snapshot()
        val globalLevel = snapshot.integer(BotSettingKeys.PROACTIVE_LEVEL)
        val allowed =
            repository.listAccessEntries(AccessListKind.ALLOW, enabledOnly = true)
                .mapNotNull { entry ->
                    runCatching { requireChatJid(entry.subjectId) }.getOrNull()
                        ?.let { it to entry.label }
                }
                .toMap()
        val blocked =
            repository.listAccessEntries(AccessListKind.BLOCK, enabledOnly = true)
                .mapNotNullTo(HashSet()) { entry ->
                    runCatching { requireChatJid(entry.subjectId) }.getOrNull()
                }
        // Ordered so that rows carrying messages come before empty ones, which makes the first
        // row of a duplicate pair the one worth keeping. The stubs are self-inflicted: arming a
        // contact for cold outreach writes a chat row under the address the request named, and
        // that address is a phone number while the conversation itself runs on a LID.
        val groups =
            repository.listChats(includeArchived = true, limit = MAX_PROACTIVE_ROSTER)
                .filter { it.kind != ChatKind.GROUP }
                .fold(ArrayList<MutableList<ChatRecord>>()) { groups, chat ->
                    groups.apply {
                        firstOrNull { group -> group.any { sameChatIdentity(it, chat) } }
                            ?.add(chat)
                            ?: add(mutableListOf(chat))
                    }
                }
        // Access lists are keyed by phone number, chats by whatever address the transport used —
        // for most contacts a LID, whose digits are not the phone number at all. Union them by the
        // raw key and the same person appears twice: once with the history, once as a stranger the
        // bot offers to greet from scratch. Only entries no chat accounts for stay on their own.
        val covered = HashSet<String>()
        val chatRows =
            groups.map { group ->
                // The member that holds the conversation represents the contact. Taking whichever
                // row happened to sort first would let the empty stub speak for a chat with months
                // of history and claim there is none.
                val chat =
                    group.maxByOrNull { it.lastMessageAt ?: Long.MIN_VALUE } ?: group.first()
                val allowEntry = allowed.entries.firstOrNull { entry ->
                    group.any { chatMatchesAddress(it, entry.key) }
                }
                allowEntry?.let { covered += it.key }
                proactiveRow(
                    chatId = chat.chatId,
                    chat = chat,
                    fallbackName = group.firstNotNullOfOrNull { candidate ->
                        candidate.displayName?.takeIf(String::isNotBlank)
                            ?: candidate.subject?.takeIf(String::isNotBlank)
                    },
                    label = allowEntry?.value,
                    allowlisted = allowEntry != null,
                    blocked = blocked.any { address -> group.any { chatMatchesAddress(it, address) } },
                    snapshot = snapshot,
                    // A level the user set is stored under the address they typed — a phone number,
                    // which is neither the LID this chat is keyed by nor the key of the stub that
                    // got merged away. Every address the group knows has to be offered, or the
                    // slider reads as silently reset.
                    identities =
                        group.flatMap { member ->
                            listOf(member.chatId) + storedChatAliases(member.metadataJson)
                        },
                )
            }
        val strangerRows =
            (allowed.keys - covered).map { jid ->
                proactiveRow(
                    chatId = jid,
                    chat = null,
                    label = allowed[jid],
                    allowlisted = true,
                    blocked = jid in blocked,
                    snapshot = snapshot,
                )
            }
        return success(
            AdminPayload.ProactiveContacts(
                globalLevel = globalLevel,
                contacts =
                    (chatRows + strangerRows).sortedWith(
                        compareByDescending<ProactiveContact> { it.lastMessageAtMs ?: 0L }
                            .thenBy { it.displayName ?: it.chatId },
                    ),
            ),
        )
    }

    private fun proactiveRow(
        chatId: String,
        chat: ChatRecord?,
        label: String?,
        allowlisted: Boolean,
        blocked: Boolean,
        snapshot: SettingsSnapshot,
        identities: List<String> = listOf(chatId),
        fallbackName: String? = null,
    ): ProactiveContact {
        val override = chatOverride(snapshot.proactiveContactOverrides, chatId, identities)
        return ProactiveContact(
            chatId = chatId,
            displayName =
                chat?.displayName
                    ?: chat?.subject
                    ?: fallbackName?.takeIf(String::isNotBlank)
                    ?: label?.takeIf(String::isNotBlank),
            level = override ?: snapshot.integer(BotSettingKeys.PROACTIVE_LEVEL),
            overridden = override != null,
            allowlisted = allowlisted,
            hasHistory = chat?.lastMessageAt != null,
            lastMessageAtMs = chat?.lastMessageAt,
            blocked = blocked,
        )
    }

    /**
     * The chat a request about [target] really means. Looking the address up verbatim is not
     * enough twice over: the conversation usually runs on the contact's LID, and the phone number
     * may already carry an empty stub that arming a cold contact left behind. A row that holds
     * messages therefore beats an exact key match that holds none — otherwise a months-old chat
     * gets greeted as a stranger.
     */
    private fun resolveChat(target: String): ChatRecord? {
        val exact = repository.getChat(target)
        if (exact?.lastMessageAt != null) return exact
        return repository.listAllChatsForIdentityResolution()
            .firstOrNull {
                it.kind != ChatKind.GROUP &&
                    it.lastMessageAt != null &&
                    chatMatchesAddress(it, target)
            }
            ?: exact
    }

    /** The address to write to, or to arm, for a request naming [target]. */
    private fun outreachTarget(target: String): String = resolveChat(target)?.chatId ?: target

    /**
     * Two-step by construction: the first call only reports what it would do and hands back a
     * confirmation token, and only a second call quoting that token actually sends. Writing to
     * somebody unprompted is the single riskiest thing this app can do, so it never happens on
     * one tap.
     */
    private suspend fun writeContactNow(action: AdminAction.WriteContactNow): AdminResult {
        val requested = requireChatJid(action.target)
        val chat = resolveChat(requested)
        // Send on the address the conversation actually lives on. Writing to the phone number of
        // a contact whose chat runs on a LID opens a second, empty chat and greets someone the
        // bot has been talking to for months as if they had never met.
        val target = chat?.chatId ?: requested
        val displayName = chat?.displayName ?: chat?.subject
        val expected = outreachConfirmation(target)
        if (action.confirmation?.trim() != expected) {
            return success(
                AdminPayload.WriteContactOutcome(
                    target = target,
                    displayName = displayName,
                    sent = false,
                    confirmation = expected,
                    detail =
                        if (chat?.lastMessageAt == null) {
                            "This contact has no history — the bot would open the chat with a first message."
                        } else {
                            "The bot would write to this contact now."
                        },
                ),
            )
        }
        // Explicit outreach is also explicit consent, so the contact is armed for the scheduler
        // even if the send itself fails: the user has said they want this conversation.
        repository.armColdProactive(target)
        val outcome = bridgeCall { RuntimeBridgeControl.writeContactNow(target, action.note) }
        val result =
            outcome.getOrElse {
                return success(
                    AdminPayload.WriteContactOutcome(
                        target = target,
                        displayName = displayName,
                        sent = false,
                        detail = it.message?.takeIf(String::isNotBlank) ?: "Sending failed",
                    ),
                )
            }
        return success(
            AdminPayload.WriteContactOutcome(
                target = target,
                displayName = displayName,
                sent = result.sent,
                detail = result.detail,
            ),
        )
    }

    /**
     * Bound to the contact and to a coarse time slot, so a token cannot be replayed a day later
     * and confirming one contact can never send to another.
     */
    private fun outreachConfirmation(chatJid: String): String {
        val slot = System.currentTimeMillis() / OUTREACH_CONFIRMATION_SLOT_MS
        var hash = -0x340d631b7bdddcdbL
        "$chatJid:$slot".encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toLong()) * 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash).takeLast(8)
    }

    private fun listBlocks(): AdminResult =
        success(
            AdminPayload.Blocks(
                repository.listAccessEntries(AccessListKind.BLOCK, enabledOnly = true)
                    .map {
                        val remote = it.label == REMOTE_BLOCK_LABEL
                        BlockedContact(
                            number = it.subjectId,
                            // The remote marker is bookkeeping, not a reason someone typed.
                            reason = if (remote) "" else it.label.orEmpty(),
                            source = if (remote) BlockSource.REMOTE else BlockSource.LOCAL,
                        )
                    },
            ),
        )

    private suspend fun block(number: String, reason: String): AdminResult {
        val phone = phone(number) ?: return AdminResult.Invalid("number", "Invalid number")
        val snapshot = settings.snapshot()
        val owners =
            snapshot.appStringList(AppSettingKeys.OWNER_NUMBERS) +
                snapshot.appStringList(AppSettingKeys.ADMIN_NUMBERS)
        if (phone in owners) return AdminResult.Denied("Owners and admins are protected")
        val existed =
            repository.isAccessListed(AccessListKind.BLOCK, AccessSubjectType.PHONE, phone)
        bridgeCall {
            RuntimeBridgeControl.setBlocked("$phone@s.whatsapp.net", blocked = true)
        }.getOrElse {
            throw AdminTransportFailure("WhatsApp did not confirm the block")
        }
        repository.upsertAccessEntry(
            AccessEntryRecord(
                listKind = AccessListKind.BLOCK,
                subjectType = AccessSubjectType.PHONE,
                subjectId = phone,
                label = reason.take(512),
            ),
        )
        return success(AdminPayload.BlockChanged(phone, true, existed))
    }

    /**
     * Lifting a block has to clear every row that could still be enforcing it.
     *
     * Three code paths write to the blocklist and none of them agree on the subject. The admin
     * command stores a bare phone number; the flood guard and the model's block tool store the
     * sender's JIDs, `@lid` alias included, whose digits are not a phone number at all; the
     * WhatsApp sync stores the confirmed JID. Deleting only the `PHONE` row therefore left every
     * automatically placed block permanently in force — the contact could be unblocked inside
     * WhatsApp and still have every message rejected, with a row in the app that no button removed.
     *
     * The local rows also go first and unconditionally. WhatsApp rejects `unblock` for a contact it
     * does not consider blocked, which is precisely the case someone runs this for, and letting
     * that failure abort the whole command is what made the state impossible to get out of. A block
     * left standing on WhatsApp's side while the app has released it is the harmless direction.
     */
    private suspend fun unblock(number: String): AdminResult {
        val requested = number.trim()
        val digits = phone(requested)
        if (requested.isEmpty() || (digits == null && !CHAT_JID.matches(requested))) {
            return AdminResult.Invalid("number", "Invalid number")
        }
        val matches =
            repository.listAccessEntries(AccessListKind.BLOCK, enabledOnly = false)
                .filter { entry ->
                    entry.subjectId.equals(requested, ignoreCase = true) ||
                        (digits != null && phone(entry.subjectId) == digits)
                }
        matches.forEach {
            repository.removeAccessEntry(AccessListKind.BLOCK, it.subjectType, it.subjectId)
        }
        val target = if (requested.contains('@')) requested else "$digits@s.whatsapp.net"
        val confirmed =
            bridgeCall { RuntimeBridgeControl.setBlocked(target, blocked = false) }.isSuccess
        return success(
            AdminPayload.BlockChanged(digits ?: requested, confirmed, matches.isNotEmpty()),
        )
    }

    private fun safetyStatus(): AdminResult {
        val now = System.currentTimeMillis()
        val rows = repository.listOutboundSafety(limit = 1_000)
        val activeHolds =
            rows.count {
                it.outboundKind == "safety_hold" &&
                    it.status == OutboundStatus.RESERVED &&
                    (it.expiresAt ?: 0) > now
            }
        val review = rows.count { it.decision == OutboundDecision.REVIEW && it.status == OutboundStatus.RESERVED }
        return success(
            AdminPayload.Text(
                buildString {
                    append("Safety: $activeHolds active pause(s), $review open review entries.")
                    repository.transportSafetySnapshot()
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        ?.let { append("\n").append(formatStoredSafety(it)) }
                },
            ),
        )
    }

    private suspend fun refreshSafety(): AdminResult {
        val result = bridgeCall { RuntimeBridgeControl.refreshSafety() }.getOrNull()
        val now = System.currentTimeMillis()
        val active =
            repository.listActiveOutboundLocks(kinds = SAFETY_LOCK_KINDS, now = now)
                .map {
                    de.totec.doppel.commands.SafetyLock(
                        id = it.databaseId,
                        label = it.reasonCode.replace('_', ' ').replaceFirstChar(Char::uppercase),
                        expiresAtMs = it.expiresAt,
                    )
                }
        return success(
            AdminPayload.SafetyState(
                summary = result?.let(::formatLiveSafety) ?: "Connect WhatsApp to refresh live limits.",
                activeLocks = active,
            ),
        )
    }

    private fun formatLiveSafety(result: JSONObject): String =
        buildString {
            append("WhatsApp safety ")
            append(if (result.optBoolean("complete", false)) "complete" else "partial")
            result.optJSONObject("blocklist")?.let {
                append("\nBlocked accounts: ${it.optInt("count", 0)}")
            }
            result.optJSONObject("reachoutTimelock")?.let {
                append("\nReachout time lock: ")
                append(if (it.optBoolean("isActive", false)) "active" else "inactive")
                it.optLong("expiresAtMs", 0L).takeIf { value -> value > 0L }?.let { value ->
                    append(", expires: $value")
                }
                it.optString("enforcementType").takeIf(String::isNotBlank)?.let { value ->
                    append(", type: ${value.take(80)}")
                }
            }
            result.optJSONObject("messageCapping")?.let { cap ->
                append("\nNew-chat cap: ")
                append(if (cap.optBoolean("limited", false)) "reached" else "free/no limit reported")
                cap.keys().asSequence()
                    .filterNot { it in setOf("available", "checkedAtMs", "limited") }
                    .take(12)
                    .forEach { key -> append("\n  ${key.takeLast(80)} = ${cap.opt(key)}") }
            }
            result.optJSONObject("errors")?.takeIf { it.length() > 0 }?.let { errors ->
                append("\nUnavailable: ")
                append(
                    errors.keys().asSequence()
                        .joinToString { key -> "$key=${errors.optString(key)}" },
                )
            }
        }

    private fun formatStoredSafety(root: JSONObject): String {
        val reachout = root.optJSONObject("reachout_timelock")
        val cap = root.optJSONObject("message_capping_status")
        val blocklist = root.optJSONObject("blocklist_set") ?: root.optJSONObject("blocklist_update")
        return buildString {
            append("Last signal: ${root.optString("lastKind", "unknown")}")
            blocklist?.let { append("; WhatsApp blocks: ${it.optInt("count", 0)}") }
            reachout?.let {
                append("; reachout: ${if (it.optBoolean("isActive", false)) "active" else "inactive"}")
            }
            cap?.let {
                append("; new-chat cap: ${if (it.optBoolean("limited", false)) "reached" else "free"}")
            }
        }
    }

    private fun safetyEvents(limit: Int): AdminResult {
        val text =
            repository.listOutboundSafety(limit = limit.coerceIn(1, 100))
                .joinToString("\n") {
                    "#${it.databaseId} ${it.outboundKind}: ${it.reasonCode} (${it.status.databaseValue})"
                }
                .ifBlank { "No safety events." }
        return success(AdminPayload.Text(text))
    }

    private fun markSafety(
        id: Long,
        reason: String,
    ): AdminResult {
        // A non-positive ID is a typo, not a lookup: the repository rejects it outright, and the
        // admin who mistyped one deserves "no such row", not a generic failure.
        val row = id.takeIf { it > 0 }?.let(repository::getOutboundSafety)
            ?: return AdminResult.NotFound("Safety #$id")
        repository.markOutboundStatus(
            row.dedupeKey,
            OutboundStatus.CANCELLED,
            reasonCode = reason,
        )
        return success()
    }

    private fun holdSafety(
        durationMs: Long,
        reason: String,
    ): AdminResult {
        require(durationMs in 1_000..MAX_HOLD_MS) { "Safety-Dauer" }
        val now = System.currentTimeMillis()
        repository.reserveOutbound(
            OutboundSafetyRecord(
                dedupeKey = "safety-hold:${UUID.randomUUID()}",
                outboundKind = "safety_hold",
                decision = OutboundDecision.DENY,
                reasonCode = "manual_hold",
                status = OutboundStatus.RESERVED,
                plannedAt = now,
                expiresAt = now + durationMs,
                metadataJson = JSONObject().put("reason", reason.take(500)).toString(),
            ),
        )
        return success()
    }

    /**
     * True for actions that only read state. They are the ones a screen fires on every open, and
     * a successful one leaves nothing behind that an audit row could explain later.
     */
    private fun AdminAction.isPureRead(): Boolean =
        when (this) {
            AdminAction.Help,
            AdminAction.Status,
            AdminAction.MoodStatus,
            AdminAction.Meta,
            AdminAction.ListSettings,
            AdminAction.ApiKeyStatus,
            AdminAction.ListPersonaData,
            AdminAction.ListPersonas,
            AdminAction.ListBlocks,
            AdminAction.ListVoices,
            AdminAction.VoiceStatus,
            AdminAction.ProactiveStatus,
            AdminAction.ListProactiveContacts,
            AdminAction.SafetyStatus,
            -> true

            is AdminAction.GetSetting,
            is AdminAction.ListAccess,
            is AdminAction.GetProactiveOverride,
            is AdminAction.PersonaImages,
            is AdminAction.PersonaProfilePictures,
            is AdminAction.SafetyEvents,
            is AdminAction.ListMemories,
            is AdminAction.OpenMemory,
            -> true

            else -> false
        }

    private fun log(
        request: AdminRequest,
        result: AdminResult,
    ) {
        runCatching {
            val name = request.action::class.simpleName.orEmpty().take(100)
            repository.appendActivity(
                ActivityLogRecord(
                    level = if (result is AdminResult.Success) ActivityLevel.INFO else ActivityLevel.WARN,
                    category = "admin",
                    action = name,
                    chatId = request.context.chatId,
                    // Naming the action is the whole point of the row: three identical
                    // "admin action executed" lines tell an operator nothing.
                    summary =
                        "${actionLabel(request.action, name)} " +
                            if (result is AdminResult.Success) "executed" else "refused",
                ),
            )
        }
    }

    /** German name for the operator log; [fallback] is the class name for rarely used actions. */
    private fun actionLabel(
        action: AdminAction,
        fallback: String,
    ): String =
        when (action) {
            is AdminAction.SetSettings -> "Setting changed:"
            AdminAction.ResetAllGlobalSettings -> "All settings reset:"
            AdminAction.PauseBot -> "Replies paused:"
            AdminAction.ResumeBot -> "Replies resumed:"
            is AdminAction.ChangeAccess -> "Access list changed:"
            AdminAction.ClearApiKey -> "API key deleted:"
            is AdminAction.ResetChat -> "Chat reset:"
            is AdminAction.ClearChatMemory -> "Chat memory deleted:"
            is AdminAction.Wipe -> "Memory wipe:"
            is AdminAction.UpsertPersona -> "Persona saved:"
            is AdminAction.DeletePersona -> "Persona deleted:"
            is AdminAction.AssignPersona -> "Persona assigned:"
            is AdminAction.UnassignPersona -> "Persona assignment removed:"
            is AdminAction.SetDefaultVoice -> "Default voice set:"
            is AdminAction.DeletePersonaImage -> "Persona image deleted:"
            is AdminAction.DeletePersonaProfilePicture -> "Profile picture deleted:"
            is AdminAction.SendPersonaImage -> "Image sent by hand:"
            is AdminAction.SendManualText -> "Message sent by hand:"
            is AdminAction.BlockContact -> "Contact blocked:"
            is AdminAction.UnblockContact -> "Contact unblocked:"
            is AdminAction.SetGlobalProactiveLevel -> "Proactive level set:"
            is AdminAction.SetProactiveOverride -> "Proactive override set:"
            is AdminAction.ClearProactiveOverride -> "Proactive override removed:"
            is AdminAction.AcknowledgeSafetyLock -> "Safety lock acknowledged:"
            is AdminAction.ClearSafetyLock -> "Safety lock lifted:"
            is AdminAction.HoldSafety -> "Sending locked:"
            is AdminAction.CreateChatMemory -> "Memory written by hand:"
            is AdminAction.CreateGlobalMemory -> "Global memory written by hand:"
            is AdminAction.UpdateMemory -> "Memory edited by hand:"
            is AdminAction.DeleteMemory -> "Memory emptied by hand:"
            else -> "$fallback:"
        }

    private fun normalizeAccessEntry(list: AccessList, value: String): String? =
        if (list == AccessList.GROUP_ALLOW) {
            value.trim().takeIf(String::isNotBlank)
        } else {
            phone(value)
        }

    private fun phone(value: String): String? =
        normalizePhoneNumber(value)

    private fun requireVoice(voice: String) {
        require(SettingsCatalogs.voices.any { it.name.equals(voice, true) }) {
            "Unknown voice"
        }
    }

    private fun AccessList.dbKind(): AccessListKind =
        when (this) {
            AccessList.ALLOW -> AccessListKind.ALLOW
            AccessList.GROUP_ALLOW -> AccessListKind.GROUP_ALLOW
            AccessList.ADMIN -> AccessListKind.ADMIN
        }

    private fun SettingValue.displayValue(): String =
        when (this) {
            is SettingValue.Bool -> value.toString()
            is SettingValue.Decimal -> value.toString()
            is SettingValue.Integer -> value.toString()
            is SettingValue.SecretReference -> "[Keystore:$name]"
            is SettingValue.StringList -> values.joinToString(",")
            is SettingValue.Text -> value
        }

    private fun symmetricDifferenceSize(a: List<String>, b: List<String>): Int =
        (a.toSet() - b.toSet()).size + (b.toSet() - a.toSet()).size

    private fun listMemories(limit: Int): AdminResult =
        success(
            AdminPayload.Memories(
                repository.listMemoryDocuments(limit).map(::toMemorySummary),
            ),
        )

    private fun openMemory(scope: MemoryScope, id: String): AdminResult {
        val summary: MemorySummary
        val body: String
        val factsJson: String?
        when (scope) {
            MemoryScope.CHAT -> {
                val record = repository.getChatMemory(id) ?: return AdminResult.NotFound(id)
                summary =
                    toMemorySummary(
                        MemoryDocumentSummary(
                            scope = "chat",
                            id = record.conversationKey,
                            owner = record.chatId,
                            characters = record.summary.length,
                            preview = record.summary.take(MEMORY_PREVIEW_CHARS),
                            hasFacts = record.factsJson != null,
                            sourceMessageCount = record.sourceMessageCount,
                            revision = record.revision,
                            updatedAt = record.updatedAt,
                        ),
                    )
                body = record.summary
                factsJson = record.factsJson
            }

            MemoryScope.PERSONA -> {
                val record = repository.getPersonaMemory(id) ?: return AdminResult.NotFound(id)
                summary =
                    toMemorySummary(
                        MemoryDocumentSummary(
                            scope = "persona",
                            id = record.personaId,
                            owner = record.personaId,
                            characters = record.summary.length,
                            preview = record.summary.take(MEMORY_PREVIEW_CHARS),
                            hasFacts = record.factsJson != null,
                            sourceMessageCount = 0,
                            revision = record.revision,
                            updatedAt = record.updatedAt,
                        ),
                    )
                body = record.summary
                factsJson = record.factsJson
            }
        }
        return success(AdminPayload.MemoryDocument(summary, body, parseFactList(factsJson)))
    }

    /**
     * A manual edit is a normal monotonic memory revision. The compare-and-swap closes the model
     * latency window: a refresh that started before this edit may not overwrite it afterwards.
     */
    private fun updateMemory(action: AdminAction.UpdateMemory): AdminResult {
        val key = action.id.trim()
        val summary = action.summary.trim()
        if (key.isEmpty()) return AdminResult.Invalid("id", "A memory id is required")
        if (action.expectedRevision < 1L) {
            return AdminResult.Invalid("revision", "The memory revision is invalid")
        }
        if (summary.isEmpty()) {
            return AdminResult.Invalid("summary", "Summary must not be empty; use Delete instead")
        }
        if (summary.length > MAX_EDITABLE_MEMORY_CHARS) {
            return AdminResult.Invalid(
                "summary",
                "Summary exceeds $MAX_EDITABLE_MEMORY_CHARS characters",
            )
        }
        val now = System.currentTimeMillis()
        val changed =
            runCatching {
                when (action.scope) {
                    MemoryScope.CHAT -> {
                        val current = repository.getChatMemory(key) ?: return AdminResult.NotFound(key)
                        if (current.revision != action.expectedRevision) return memoryChanged(key)
                        repository.compareAndSwapChatMemory(
                            expectedRevision = current.revision,
                            memory =
                                current.copy(
                                    summary = summary,
                                    revision = current.revision + 1L,
                                    updatedAt = now,
                                ),
                        )
                    }

                    MemoryScope.PERSONA -> {
                        val current = repository.getPersonaMemory(key) ?: return AdminResult.NotFound(key)
                        if (current.revision != action.expectedRevision) return memoryChanged(key)
                        repository.compareAndSwapPersonaMemory(
                            expectedRevision = current.revision,
                            memory =
                                current.copy(
                                    summary = summary,
                                    revision = current.revision + 1L,
                                    updatedAt = now,
                                ),
                        )
                    }
                }
            }.getOrElse { return AdminResult.Invalid("id", "Not a memory id") }
        if (!changed) return memoryChanged(key)
        return openMemory(action.scope, key)
    }

    private fun memoryChanged(id: String): AdminResult =
        AdminResult.Denied("$id changed while it was open. Reopen it before saving again")

    /**
     * The chat's own consolidation, run now. The persona is resolved the same way a turn resolves
     * it, so the write lands on the conversation the bot is actually holding rather than on a second
     * thread keyed by the globally selected persona.
     */
    private suspend fun createChatMemory(chatId: String): AdminResult {
        val requested = requireChatJid(chatId)
        val target = resolveChat(requested)?.chatId ?: requested
        val personaKey = settings.snapshot().effectivePersona(target)
        val result =
            bridgeCall { RuntimeBridgeControl.writeChatMemoryNow(target, personaKey) }.getOrElse {
                return AdminResult.Failure(
                    it.message?.takeIf(String::isNotBlank) ?: "Memory could not be written",
                    retryable = true,
                )
            }
        return success(AdminPayload.MemoryWritten(result.detail))
    }

    /** The persona's cross-chat synthesis, run now. Refused when it owns no chat memory yet. */
    private suspend fun createGlobalMemory(personaKey: String): AdminResult {
        val key = personaKey.trim()
        if (key.isEmpty()) return AdminResult.Invalid("persona", "Persona must not be empty")
        if (repository.listChatMemoriesForPersona(key, limit = 1).isEmpty()) {
            return AdminResult.NotFound(key)
        }
        val result =
            bridgeCall { RuntimeBridgeControl.writeGlobalMemoryNow(key) }.getOrElse {
                return AdminResult.Failure(
                    it.message?.takeIf(String::isNotBlank) ?: "Global memory could not be written",
                    retryable = true,
                )
            }
        return success(AdminPayload.MemoryWritten(result.detail))
    }

    /** Chat memory keeps its fold pointer; global memory is removed as though it never existed. */
    private fun deleteMemory(scope: MemoryScope, id: String): AdminResult {
        val key = id.trim()
        if (key.isEmpty()) return AdminResult.Invalid("id", "A memory id is required")
        val cleared =
            runCatching {
                when (scope) {
                    MemoryScope.CHAT -> repository.clearChatMemorySummary(key)
                    MemoryScope.PERSONA -> repository.deletePersonaMemory(key)
                }
            }.getOrElse { return AdminResult.Invalid("id", "Not a memory id") }
        if (!cleared) return AdminResult.NotFound(key)
        // Answers with the refreshed catalogue instead of an empty payload. The browser is holding
        // a snapshot this call just invalidated, and a re-list fired from the UI afterwards would
        // run concurrently with the delete it is meant to follow.
        return listMemories(AdminAction.ListMemories().limit)
    }

    private fun toMemorySummary(row: MemoryDocumentSummary): MemorySummary {
        val scope = if (row.scope == "persona") MemoryScope.PERSONA else MemoryScope.CHAT
        // Conversation keys are "<chatJid>#<persona>"; an empty tail means the legacy chat scope.
        val persona = row.id.substringAfterLast('#', "")
        return MemorySummary(
            scope = scope,
            id = row.id,
            title =
                when (scope) {
                    MemoryScope.PERSONA -> row.id
                    MemoryScope.CHAT -> row.owner.substringBefore('@')
                },
            subtitle =
                when {
                    scope == MemoryScope.PERSONA -> "Global persona memory"
                    persona.isBlank() -> "Chat memory"
                    else -> "Persona $persona"
                },
            characters = row.characters,
            sourceMessageCount = row.sourceMessageCount,
            revision = row.revision,
            updatedAtMs = row.updatedAt,
            hasFacts = row.hasFacts,
            preview = row.preview.replace('\n', ' ').trim(),
        )
    }

    /** Facts are stored as a JSON array of short strings; anything else degrades to no facts. */
    private fun parseFactList(factsJson: String?): List<String> {
        if (factsJson.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(factsJson)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun success(payload: AdminPayload = AdminPayload.Empty): AdminResult.Success =
        AdminResult.Success(payload)

    private companion object {
        val PERSONA_KEY = Regex("^[a-z0-9_-]{2,40}$")
        val CHAT_JID = Regex("^[0-9A-Za-z._:-]+@[0-9A-Za-z.-]+$")
        val PHONE_INPUT = Regex("^[+0-9 ()-]+$")
        val ADMIN_REQUEST_ID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        const val MAX_PROMPT_CHARS = 50_000
        const val MAX_IMAGE_CAPTION_CHARS = 1_024
        const val MAX_MANUAL_TEXT_CHARS = 4_096
        const val MEMORY_PREVIEW_CHARS = 200
        const val MAX_EDITABLE_MEMORY_CHARS = 1_048_576
        const val MAX_HOLD_MS = 30L * 24 * 60 * 60 * 1_000
        const val MAX_PROACTIVE_ROSTER = 500

        /** One page of a chat wipe: bounded so a huge chat never builds one giant statement. */
        const val CLEAR_PAGE_SIZE = 200

        /** The ledger kinds that represent a hold on sending, as opposed to a send reservation. */
        val SAFETY_LOCK_KINDS = setOf("safety_lock", "safety_hold")

        /** How long a "write this person" confirmation stays valid. */
        const val OUTREACH_CONFIRMATION_SLOT_MS = 5L * 60 * 1_000
    }

    private class AdminTransportFailure(
        val safeMessage: String,
    ) : RuntimeException()
}
