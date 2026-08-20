package de.totec.doppel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExecutorTest {
    @Test
    fun everyCanonicalAndAliasReachesTheDispatcher() {
        val fake = RecordingAdminActions()
        val registry = CommandRegistry()
        val executor = CommandExecutor(fake, registry = registry)

        registry.specs.flatMap(CommandSpec::tokens).forEach { token ->
            val result = executor.executeBlocking(request("/$token"))
            assertTrue("$token did not dispatch: $result", result is CommandExecutionResult.Replied)
        }
    }

    @Test
    fun all102RecognizedTokensAreAdminOnlyAndFallThroughSilentlyForOthers() {
        val fake = RecordingAdminActions()
        val registry = CommandRegistry()
        val executor = CommandExecutor(fake, registry = registry)

        registry.specs.flatMap(CommandSpec::tokens).forEach { token ->
            fake.requests.clear()
            val result = executor.executeBlocking(request("/${token.uppercase()}", isAdmin = false))
            assertTrue(
                "$token did not use non-admin fallthrough: $result",
                result is CommandExecutionResult.NotAuthorizedFallThrough,
            )
            assertTrue("port was called for non-admin $token", fake.requests.isEmpty())
        }

        val unknown = executor.executeBlocking(request("/unknown", isAdmin = true))
        assertTrue(unknown is CommandExecutionResult.UnknownCommandFallThrough)
        assertTrue(fake.requests.isEmpty())
        assertTrue(executor.executeBlocking(request("hello")) is CommandExecutionResult.NotACommand)
    }

    @Test
    fun apiKeySetUsesRedactedTypedActionOnlyInDirectAdminChat() {
        val fake = RecordingAdminActions()
        val rawKey = "example-openrouter-key-for-redaction-test"
        val executor = CommandExecutor(fake)

        val reply = executor.executeBlocking(request("/apikey set $rawKey")).replyText()
        assertFalse(reply.contains(rawKey))
        assertTrue(reply.contains("API-Key"))
        assertTrue(reply.contains("test"))
        assertTrue(fake.requests.single().action is AdminAction.SetApiKey)

        fake.requests.clear()
        val denied =
            executor.executeBlocking(request("/apikey set $rawKey", isGroup = true)).replyText()
        assertFalse(denied.contains(rawKey))
        assertTrue(denied.contains("Direktchat"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun multiSettingCommandsSubmitOneAtomicTypedUpdate() {
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake)

        executor.executeBlocking(request("/system Bleib knapp und freundlich"))
        assertEquals(
            mapOf(
                "system_prompt" to "Bleib knapp und freundlich",
                "personality" to "custom",
            ),
            fake.onlySetSettings().changes,
        )

        fake.requests.clear()
        executor.executeBlocking(request("/autoblock limits 5 15 25"))
        assertEquals(
            mapOf(
                "autoblock_per_min" to "5",
                "autoblock_per_5min" to "15",
                "autoblock_per_10min" to "25",
            ),
            fake.onlySetSettings().changes,
        )

        fake.requests.clear()
        executor.executeBlocking(request("/traits reset"))
        val traits = fake.onlySetSettings().changes
        assertEquals(9, traits.size)
        assertTrue(traits.keys.all { it.startsWith("trait_") })
        assertTrue(traits.values.all { it == "0" })

        fake.requests.clear()
        fake.responder = { adminRequest ->
            if (adminRequest.action == AdminAction.GetSetting("history_retention")) {
                AdminResult.Success(
                    AdminPayload.Setting(
                        SettingSnapshot(
                            "history_retention",
                            "5",
                            "120",
                            "Retention",
                            overridden = true,
                        ),
                    ),
                )
            } else {
                fake.defaultResult(adminRequest.action)
            }
        }
        executor.executeBlocking(request("/history 30"))
        assertEquals(
            mapOf("history_limit" to "30", "history_retention" to "30"),
            fake.requests.map(AdminRequest::action)
                .filterIsInstance<AdminAction.SetSettings>()
                .single()
                .changes,
        )
    }

    @Test
    fun invalidAtomicInputsNeverReachThePort() {
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake)

        executor.executeBlocking(request("/autoblock limits 1 2"))
        assertTrue(fake.requests.isEmpty())
        executor.executeBlocking(request("/autoblock limits 1 2 3 4"))
        assertTrue(fake.requests.isEmpty())
        executor.executeBlocking(request("/autoblock limits 1 2 1.5"))
        assertTrue(fake.requests.isEmpty())
        executor.executeBlocking(request("/traits flirt 1.5"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun proactiveLevelsRequireConcreteIntegers() {
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake)

        executor.executeBlocking(request("/proactive global 1.5"))
        assertTrue(fake.requests.isEmpty())
        executor.executeBlocking(request("/proactive 49123456789 2.5"))
        assertTrue(fake.requests.isEmpty())

        executor.executeBlocking(request("/proactive global 6"))
        assertEquals(AdminAction.SetGlobalProactiveLevel(6), fake.requests.single().action)
        fake.requests.clear()
        executor.executeBlocking(request("/proactive +49 123456789 7"))
        assertTrue(
            "space-formatted numbers need an explicit unambiguous token",
            fake.requests.isEmpty(),
        )
        executor.executeBlocking(request("/proactive 49123456789 7"))
        assertEquals(
            AdminAction.SetProactiveOverride("49123456789", 7),
            fake.requests.single().action,
        )
    }

    @Test
    fun emptyReplacementIsAllowedButGroupMutationRequiresExplicitTarget() {
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake)

        executor.executeBlocking(request("/allowlist set"))
        assertEquals(
            AdminAction.ChangeAccess(AccessList.ALLOW, AccessOperation.REPLACE, emptyList()),
            fake.requests.single().action,
        )
        fake.requests.clear()

        executor.executeBlocking(request("/groupallowlist set"))
        assertEquals(
            AdminAction.ChangeAccess(AccessList.GROUP_ALLOW, AccessOperation.REPLACE, emptyList()),
            fake.requests.single().action,
        )
        fake.requests.clear()

        executor.executeBlocking(request("/groupallowlist add"))
        assertTrue(fake.requests.isEmpty())
        fake.requests.clear()

        executor.executeBlocking(request("/allowlist add 49123456789 49123456780"))
        assertEquals(
            listOf("49123456789", "49123456780"),
            (fake.requests.single().action as AdminAction.ChangeAccess).entries,
        )
        fake.requests.clear()

        executor.executeBlocking(request("/groupallowlist add Familie, Projekt Alpha"))
        assertEquals(
            listOf("Familie", "Projekt Alpha"),
            (fake.requests.single().action as AdminAction.ChangeAccess).entries,
        )
    }

    @Test
    fun globalResetIsExplicitAndUnknownResetModesDoNothing() {
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake)

        executor.executeBlocking(request("/reset all", chatId = "target@s.whatsapp.net"))
        assertEquals(
            AdminAction.ResetChat("target@s.whatsapp.net", resetGlobalSettings = true),
            fake.requests.single().action,
        )
        fake.requests.clear()

        executor.executeBlocking(request("/reset"))
        assertEquals(
            AdminAction.ResetChat("chat@s.whatsapp.net", resetGlobalSettings = false),
            fake.requests.single().action,
        )
        fake.requests.clear()

        executor.executeBlocking(request("/reset everything"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun wipeConfirmationIsTargetActorChatAndExpiryBound() {
        var now = 1_000L
        var nextCode = 111_111
        val manager = WipeChallengeManager(
            clock = EpochMillisClock { now },
            codeGenerator = ConfirmationCodeGenerator {
                (nextCode++).toString().padStart(6, '0')
            },
            ttlMs = 100L,
        )
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake, wipeChallenges = manager)

        val issued = executor.executeBlocking(
            request("/clearall alice", senderId = "admin-a", chatId = "chat-a"),
        ).replyText()
        assertTrue(issued.contains("111111"))
        assertTrue(fake.requests.none { it.action is AdminAction.Wipe })

        executor.executeBlocking(
            request("/clearall 111111", senderId = "admin-b", chatId = "chat-a"),
        )
        executor.executeBlocking(
            request("/clearall 111111", senderId = "admin-a", chatId = "chat-b"),
        )
        assertTrue(fake.requests.none { it.action is AdminAction.Wipe })

        executor.executeBlocking(
            request("/clearall 111111", senderId = "admin-a", chatId = "chat-a"),
        )
        val wipe = fake.requests.map(AdminRequest::action)
            .filterIsInstance<AdminAction.Wipe>()
            .single()
        assertEquals(WipeTarget.Persona("alice"), wipe.target)

        executor.executeBlocking(
            request("/clearall 111111", senderId = "admin-a", chatId = "chat-a"),
        )
        assertEquals(
            1,
            fake.requests.map(AdminRequest::action).filterIsInstance<AdminAction.Wipe>().size,
        )

        val second = executor.executeBlocking(
            request("/clearall all", senderId = "admin-a", chatId = "chat-a"),
        ).replyText()
        assertTrue(second.contains("111112"))
        now += 101L
        executor.executeBlocking(
            request("/clearall 111112", senderId = "admin-a", chatId = "chat-a"),
        )
        assertEquals(
            1,
            fake.requests.map(AdminRequest::action).filterIsInstance<AdminAction.Wipe>().size,
        )
        assertEquals(0, manager.pendingCount())
    }

    @Test
    fun configuredPrefixAppearsInEveryInstructionAndSlashNoLongerTriggers() {
        var code = 444_444
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(
            actions = fake,
            prefixProvider = { "!" },
            wipeChallenges = WipeChallengeManager(
                codeGenerator = ConfirmationCodeGenerator {
                    (code++).toString().padStart(6, '0')
                },
            ),
        )

        assertTrue(executor.executeBlocking(request("/off")) is CommandExecutionResult.NotACommand)
        val off = executor.executeBlocking(request("!off")).replyText()
        assertTrue(off.contains("!on"))
        assertFalse(off.contains("/on"))
        val wipe = executor.executeBlocking(request("!clearall all")).replyText()
        assertTrue(wipe.contains("!clearall 444444"))
        assertFalse(wipe.contains("/clearall"))
        val validation = executor.executeBlocking(request("!mood vielleicht")).replyText()
        assertTrue(validation.contains("!mood"))
        assertFalse(validation.contains("/mood"))
    }

    @Test
    fun adminCommandsBypassPausedRuntimeAndOffDoesNotStopAdministration() {
        val fake = RecordingAdminActions()
        var paused = false
        fake.responder = { adminRequest ->
            when (adminRequest.action) {
                AdminAction.PauseBot -> {
                    paused = true
                    AdminResult.Success()
                }
                AdminAction.ResumeBot -> {
                    paused = false
                    AdminResult.Success()
                }
                else -> fake.defaultResult(adminRequest.action)
            }
        }
        val executor = CommandExecutor(fake)

        val off = executor.executeBlocking(request("/off")).replyText()
        assertTrue(paused)
        assertTrue(off.contains("Verbindung und Admin-Befehle bleiben aktiv"))
        assertTrue(executor.executeBlocking(request("/status")) is CommandExecutionResult.Replied)
        assertTrue(executor.executeBlocking(request("/help")) is CommandExecutionResult.Replied)
        assertTrue(paused)
        assertTrue(executor.executeBlocking(request("/on")) is CommandExecutionResult.Replied)
        assertFalse(paused)
        assertTrue(fake.requests.any { it.action == AdminAction.Status })
        assertTrue(fake.requests.any { it.action == AdminAction.Help })
    }

    @Test
    fun explicitPersonaTargetNeverFallsBackWhenMalformed() {
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake)

        executor.executeBlocking(request("/persona assign alice malformed@target"))
        assertTrue(fake.requests.isEmpty())
        executor.executeBlocking(request("/persona unassign malformed@target"))
        assertTrue(fake.requests.isEmpty())

        executor.executeBlocking(request("/persona assign alice 49123456789"))
        assertEquals(
            AdminAction.AssignPersona("alice", "49123456789@s.whatsapp.net"),
            fake.requests.single().action,
        )
    }

    @Test
    fun moodAndGlobalVoiceCommandsKeepTheirNativeStatusFeatures() {
        val fake = RecordingAdminActions()
        val executor = CommandExecutor(fake)

        val mood = executor.executeBlocking(request("/mood")).replyText()
        assertTrue(mood.contains("verspielt"))
        assertEquals(AdminAction.MoodStatus, fake.requests.single().action)

        fake.requests.clear()
        val voice = executor.executeBlocking(request("/voice Alice")).replyText()
        assertTrue(voice.contains("Alice"))
        assertEquals(AdminAction.SetDefaultVoice("Alice"), fake.requests.single().action)

        fake.requests.clear()
        executor.executeBlocking(request("/traits trait_flirt 2"))
        assertEquals(
            mapOf("trait_flirt" to "2"),
            fake.onlySetSettings().changes,
        )
    }

    @Test
    fun blockCommandConsultsTheAdminListAndCannotBlockAnAdmin() {
        val fake = RecordingAdminActions()
        fake.responder = { adminRequest ->
            if (adminRequest.action == AdminAction.ListAccess(AccessList.ADMIN)) {
                AdminResult.Success(
                    AdminPayload.AccessEntries(
                        AccessList.ADMIN,
                        listOf("49123456789"),
                    ),
                )
            } else {
                fake.defaultResult(adminRequest.action)
            }
        }
        val executor = CommandExecutor(fake)

        val protectedReply = executor.executeBlocking(
            request("/block 49123456789 Test"),
        ).replyText()
        assertTrue(protectedReply.contains("Admin-Nummer"))
        assertTrue(fake.requests.none { it.action is AdminAction.BlockContact })

        fake.requests.clear()
        executor.executeBlocking(request("/block 49123456780 Flood"))
        assertEquals(
            AdminAction.BlockContact("49123456780", "Flood"),
            fake.requests.map(AdminRequest::action)
                .filterIsInstance<AdminAction.BlockContact>()
                .single(),
        )
    }

    @Test
    fun longAdministrativeOutputIsSplitAtReadableBoundaries() {
        val fake = RecordingAdminActions()
        fake.responder = { adminRequest ->
            if (adminRequest.action == AdminAction.ListSettings) {
                AdminResult.Success(
                    AdminPayload.Settings(
                        (0 until 100).map { index ->
                            SettingSnapshot(
                                key = "setting_${index.toString().padStart(3, '0')}",
                                value = "x".repeat(24),
                                defaultValue = "default",
                                description = "Ausführliche Beschreibung für die native Administration",
                                overridden = index % 2 == 0,
                            )
                        },
                    ),
                )
            } else {
                fake.defaultResult(adminRequest.action)
            }
        }
        val result = CommandExecutor(fake).executeBlocking(request("/settings"))
            as CommandExecutionResult.Replied

        assertTrue(result.messages.size > 1)
        assertTrue(result.messages.all { it.length <= 3_500 })
        assertTrue(result.messages.joinToString("\n").contains("setting_099"))
    }

    private fun request(
        text: String,
        senderId: String = "admin",
        chatId: String = "chat@s.whatsapp.net",
        isGroup: Boolean = false,
        isAdmin: Boolean = true,
    ): CommandRequest = CommandRequest(
        text = text,
        senderId = senderId,
        chatId = chatId,
        isGroup = isGroup,
        isAdmin = isAdmin,
    )

    private fun CommandExecutionResult.replyText(): String {
        return (this as CommandExecutionResult.Replied).messages.joinToString("\n")
    }

    private fun RecordingAdminActions.onlySetSettings(): AdminAction.SetSettings {
        val actions = requests.map(AdminRequest::action)
        assertEquals("unexpected port calls: $actions", 1, actions.size)
        return actions.single() as AdminAction.SetSettings
    }
}
