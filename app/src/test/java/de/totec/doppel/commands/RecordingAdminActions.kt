package de.totec.doppel.commands

import kotlinx.coroutines.runBlocking

/**
 * Runs one command to completion from a plain JUnit test.
 *
 * [CommandExecutor.execute] suspends because the admin port does — several of its actions are
 * transport round-trips. Nothing in these tests is concurrent, so blocking on the result here is
 * the whole of the adaptation.
 */
internal fun CommandExecutor.executeBlocking(request: CommandRequest): CommandExecutionResult =
    runBlocking { execute(request) }

internal class RecordingAdminActions : AdminActions {
    val requests = mutableListOf<AdminRequest>()
    var responder: ((AdminRequest) -> AdminResult)? = null

    override suspend fun execute(request: AdminRequest): AdminResult {
        requests += request
        return responder?.invoke(request) ?: defaultResult(request.action)
    }

    fun defaultResult(action: AdminAction): AdminResult.Success = when (action) {
        AdminAction.Help -> AdminResult.Success(
            AdminPayload.HelpHeader(
                title = "WhatsApp-Bot – Admin-Befehle",
                introduction = "Native Administration",
            ),
        )

        AdminAction.MoodStatus -> AdminResult.Success(
            AdminPayload.Mood(enabled = true, name = "verspielt"),
        )

        is AdminAction.GetSetting -> AdminResult.Success(
            AdminPayload.Setting(
                SettingSnapshot(
                    key = action.key,
                    value = when (action.key) {
                        "personality" -> "human"
                        "history_retention" -> "120"
                        else -> "test-value"
                    },
                    defaultValue = "default-value",
                    description = "Testbeschreibung",
                    overridden = true,
                ),
            ),
        )

        AdminAction.ListSettings -> AdminResult.Success(
            AdminPayload.Settings(
                listOf(
                    SettingSnapshot(
                        key = "trait_flirt",
                        value = "0",
                        defaultValue = "0",
                        description = "Flirt",
                        overridden = false,
                    ),
                    SettingSnapshot(
                        key = "autoblock_enabled",
                        value = "true",
                        defaultValue = "true",
                        description = "Flood-Guard",
                        overridden = false,
                    ),
                ),
            ),
        )

        is AdminAction.ListAccess -> AdminResult.Success(
            AdminPayload.AccessEntries(action.list, emptyList()),
        )

        AdminAction.ApiKeyStatus -> AdminResult.Success(
            AdminPayload.SecretStatus(
                configured = true,
                lastFour = "test",
                source = "runtime",
                overrideActive = true,
            ),
        )

        is AdminAction.SetApiKey -> AdminResult.Success(
            AdminPayload.SecretStatus(
                configured = true,
                lastFour = "test",
                source = "runtime",
                overrideActive = true,
            ),
        )

        AdminAction.ListPersonas -> AdminResult.Success(
            AdminPayload.Personas(
                activeKey = "human",
                entries = listOf(
                    PersonaSummary("human", "Human", "Standard", builtIn = true),
                    PersonaSummary("alice", "Alice", "Test", builtIn = false),
                    PersonaSummary("bob", "Bob", "Test", builtIn = false),
                ),
            ),
        )

        AdminAction.ListPersonaData -> AdminResult.Success(AdminPayload.PersonaData(emptyList()))

        AdminAction.ListVoices -> AdminResult.Success(
            AdminPayload.Voices(listOf(VoiceOption("Nova", "Teststimme"))),
        )

        AdminAction.ListBlocks -> AdminResult.Success(AdminPayload.Blocks(emptyList()))

        is AdminAction.GetProactiveOverride -> AdminResult.Success(
            AdminPayload.ProactiveOverride(action.target, level = null, globalLevel = 4),
        )

        is AdminAction.Wipe -> AdminResult.Success(
            AdminPayload.WipeSummary(action.target, affectedThreads = 1),
        )

        else -> AdminResult.Success()
    }
}
