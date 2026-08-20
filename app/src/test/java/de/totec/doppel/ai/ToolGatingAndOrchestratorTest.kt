package de.totec.doppel.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class ToolGatingAndOrchestratorTest {
    private val context = ToolExecutionContext(
        conversationKey = "conversation-1",
        currentSender = "current",
    )

    @Test
    fun `registry contains exactly twelve tools and gates sensitive capabilities`() {
        assertEquals(12, ToolRegistry.all.size)
        assertEquals(12, ToolRegistry.names.size)

        val minimal = ToolRegistry.allowed(ToolAccessSettings()).map(ToolDefinition::name)
        assertEquals(
            setOf(
                "search_current_chat",
                "scroll_current_chat",
                "request_chat_memory_refresh",
                "list_scheduled_followups",
                "schedule_followup",
            ),
            minimal.toSet(),
        )
        val all = ToolRegistry.allowed(
            ToolAccessSettings(
                crossChatSearch = true,
                imageSending = true,
                imageGeneration = true,
                voiceNotes = true,
                memoryRefresh = true,
                contactBlocking = true,
            ),
        )
        assertEquals(ToolRegistry.names, all.map(ToolDefinition::name).toSet())
        val currentChatTools = all.associateBy(ToolDefinition::name)
        assertTrue(currentChatTools.getValue("scroll_current_chat").parameters.isEmpty())
        assertEquals(
            listOf("query"),
            currentChatTools.getValue("search_current_chat").parameters.map(ToolParameter::name),
        )
    }

    @Test
    fun `image generation prompt and schema appear only when the dedicated gate is on`() {
        val off = ToolAccessSettings(imageGeneration = false)
        val on = off.copy(imageGeneration = true)

        assertFalse(ToolRegistry.allowed(off).any { it.name == "generate_image" })
        assertTrue(ToolRegistry.allowed(on).any { it.name == "generate_image" })
        assertFalse(PromptLibrary.toolInstructions(off, emptyList()).contains("GENERATED IMAGE"))
        val prompt =
            PromptLibrary.toolInstructions(
                on,
                ToolRegistry.allowed(on).map(ToolDefinition::name),
            )
        assertTrue(prompt.contains("GENERATED IMAGE - generate_image"))
        // The three things a model has to be told or it calls the tool wrong: when the persona is
        // in the picture, that the style is not its job, and that one call is the whole thing.
        assertTrue(prompt.contains("include_character=true"))
        assertTrue(prompt.contains("phone-gallery style"))
        assertTrue(prompt.contains("sends it to this chat by itself"))
    }

    @Test
    fun `follow-up scheduling requires a prior global list and a bounded future time`() {
        // Scheduling is current-chat state, not permission to read another transcript.
        val settings = ToolAccessSettings(crossChatSearch = false)
        val sessionContext =
            ToolExecutionContext(
                conversationKey = "chat#female",
                personaKey = "female",
                nowMs = 1_000L,
            )
        val call =
            AiToolCall(
                "schedule-1",
                "schedule_followup",
                """{"scheduled_at":"1970-01-01T01:01:01Z","note":"ask how it went"}""",
            )
        assertEquals(
            PreparedToolCall.Rejected("scheduled_followups_not_listed"),
            ToolRegistry.prepare(call, settings, sessionContext),
        )
        sessionContext.session.mark(ToolRegistry.LIST_SCHEDULED_FOLLOWUPS)
        val prepared = ToolRegistry.prepare(call, settings, sessionContext)
        assertTrue(prepared is PreparedToolCall.Action)
        val action = (prepared as PreparedToolCall.Action).value as PendingAction.ScheduleFollowUp
        assertEquals("chat#female", action.conversationKey)
        assertEquals("ask how it went", action.note)

        // A second time in the same turn is not a second reminder. Only the last one survives in
        // the store, so a model that hedges with three times gets one timer and two promises in
        // the chat text that nothing will ever keep. The duplicate-action fingerprint could never
        // catch this: three different times are three different fingerprints.
        assertEquals(
            PreparedToolCall.Rejected("followup_already_scheduled"),
            ToolRegistry.prepare(
                AiToolCall(
                    "schedule-2",
                    "schedule_followup",
                    """{"scheduled_at":"1970-01-01T02:02:02Z","note":"or maybe then"}""",
                ),
                settings,
                sessionContext,
            ),
        )
    }

    @Test
    fun `read-only calls and pending actions are classified before execution`() {
        val enabled = ToolAccessSettings(imageSending = true)
        val read = ToolRegistry.prepare(
            AiToolCall("r1", "search_current_chat", """{"query":"x"}"""),
            enabled,
            context,
        )
        assertTrue(read is PreparedToolCall.ReadOnly)
        assertTrue(
            ToolRegistry.prepare(
                AiToolCall("r-scroll", "scroll_current_chat", "{}"),
                enabled,
                context,
            ) is PreparedToolCall.ReadOnly,
        )

        val action = ToolRegistry.prepare(
            AiToolCall("a1", "send_image", """{"asset_id":"image-7"}"""),
            enabled,
            context,
        )
        assertTrue(action is PreparedToolCall.Action)
        assertEquals(
            "image-7",
            ((action as PreparedToolCall.Action).value as PendingAction.SendImage).assetId,
        )

        val disabled = ToolRegistry.prepare(
            AiToolCall("a2", "send_voice_note", """{"text":"hi"}"""),
            enabled,
            context,
        )
        assertEquals(
            "tool_disabled",
            (disabled as PreparedToolCall.Rejected).reasonCode,
        )
        val missing = ToolRegistry.prepare(
            AiToolCall("a3", "send_image", "{}"),
            enabled,
            context,
        )
        assertEquals(
            "missing_argument",
            (missing as PreparedToolCall.Rejected).reasonCode,
        )
        val tooLarge = ToolRegistry.prepare(
            AiToolCall(
                "r2",
                "search_current_chat",
                """{"query":"x","limit":51}""",
            ),
            enabled,
            context,
        )
        assertEquals(
            "invalid_arguments",
            (tooLarge as PreparedToolCall.Rejected).reasonCode,
        )
        val unknownField = ToolRegistry.prepare(
            AiToolCall(
                "r3",
                "search_current_chat",
                """{"query":"x","secret_override":true}""",
            ),
            enabled,
            context,
        )
        assertEquals(
            "invalid_arguments",
            (unknownField as PreparedToolCall.Rejected).reasonCode,
        )
    }

    @Test
    fun `verifier allows commit only after action stayed pending through tool loop`() = runBlocking {
        val events = mutableListOf<String>()
        val completions = ArrayDeque(
            listOf(
                CompletionResult(
                    content = "",
                    toolCalls = listOf(
                        AiToolCall(
                            id = "action-1",
                            name = "send_image",
                            argumentsJson = """{"asset_id":"img-1","caption":"Hi"}""",
                        ),
                    ),
                ),
                CompletionResult(content = "Hier ist es."),
            ),
        )
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway {
                events += "model"
                completions.removeFirst()
            },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                throw AssertionError("Side effects must not use read-only executor")
            },
            actionCommitter = PendingActionCommitter { _, actions ->
                events += "commit"
                actions.map { ActionCommitReceipt(it.toolCallId, committed = true) }
            },
            verifier = ReplyVerifier { candidate, _ ->
                events += "verify"
                assertEquals(1, candidate.pendingActions.size)
                VerificationDecision(true, "approved")
            },
        )

        val result = orchestrator.runTurn(
            settings = testSettings(
                tools = ToolAccessSettings(imageSending = true),
                verification = VerificationSettings(enabled = true),
            ),
            turn = testTurn(),
            toolContext = context,
        )

        assertTrue(result.commitAllowed)
        assertEquals(listOf("model", "model", "verify", "commit"), events)
        assertEquals("img-1", (result.pendingActions.single() as PendingAction.SendImage).assetId)
        assertTrue(result.commitReceipts.single().committed)
    }

    @Test
    fun `the same picture asked for twice in one turn goes out once`() = runBlocking {
        val completions = ArrayDeque(
            listOf(
                CompletionResult(
                    content = "",
                    toolCalls = listOf(
                        AiToolCall(
                            id = "action-1",
                            name = "send_image",
                            argumentsJson = """{"asset_id":"img-1","caption":"Schau mal"}""",
                        ),
                        // Same photo, second caption. This is the shape the duplicate guard used to
                        // miss, because the caption was part of the key it compared — and nothing
                        // downstream caught it either: the whole turn is materialized before its
                        // first send, so the once-per-chat marker that "Never send an image twice"
                        // reads does not exist yet when the second copy is prepared.
                        AiToolCall(
                            id = "action-2",
                            name = "send_image",
                            argumentsJson = """{"asset_id":"img-1","caption":"oder so"}""",
                        ),
                    ),
                ),
                CompletionResult(content = "Hier ist es."),
            ),
        )
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway { completions.removeFirst() },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                throw AssertionError("Side effects must not use read-only executor")
            },
            actionCommitter = PendingActionCommitter { _, actions ->
                actions.map { ActionCommitReceipt(it.toolCallId, committed = true) }
            },
            verifier = ReplyVerifier { _, _ -> VerificationDecision(true, "approved") },
        )

        val result = orchestrator.runTurn(
            settings = testSettings(
                tools = ToolAccessSettings(imageSending = true),
                verification = VerificationSettings(enabled = true),
            ),
            turn = testTurn(),
            toolContext = context,
        )

        val image = result.pendingActions.single() as PendingAction.SendImage
        assertEquals("img-1", image.assetId)
        assertEquals("Schau mal", image.caption)
    }

    @Test
    fun `visible media deferral stops before speculative model follow up`() = runBlocking {
        var modelCalls = 0
        var committed = false
        var verifierCalls = 0
        val orchestrator =
            AiOrchestrator(
                completionGateway =
                    ChatCompletionGateway {
                        modelCalls += 1
                        CompletionResult(
                            content = "",
                            toolCalls =
                                listOf(
                                    AiToolCall(
                                        id = "image-1",
                                        name = "send_image",
                                        argumentsJson = """{"asset_id":"img-1"}""",
                                    ),
                                ),
                        )
                    },
                readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                    throw AssertionError("Unexpected read-only call")
                },
                actionCommitter =
                    PendingActionCommitter { _, actions ->
                        committed = true
                        actions.map { ActionCommitReceipt(it.toolCallId, committed = true) }
                    },
                verifier =
                    ReplyVerifier { _, _ ->
                        verifierCalls += 1
                        VerificationDecision(true, "verified")
                    },
                deferVisibleActionFollowUp = true,
            )

        val result =
            orchestrator.runTurn(
                settings =
                    testSettings(
                        tools = ToolAccessSettings(imageSending = true),
                        verification = VerificationSettings(enabled = true),
                    ),
                turn = testTurn(),
                toolContext = context,
            )

        assertEquals(1, modelCalls)
        assertEquals(1, result.modelCalls)
        assertTrue(committed)
        assertTrue(result.commitAllowed)
        assertTrue(result.pendingActions.single() is PendingAction.SendImage)
        assertEquals("nothing_to_verify", result.verification.reasonCode)
        assertEquals(0, verifierCalls)
    }

    @Test
    fun `voice verifier receives spoken text and accepts protocol formatting`() = runBlocking {
        var captured: ChatCompletionRequest? = null
        val verifier =
            OpenRouterReplyVerifier(
                ChatCompletionGateway { request ->
                    captured = request
                    CompletionResult(content = """{"allow":true,"reason":"approved"}""")
                },
            )
        val candidate =
            VerificationCandidate(
                response =
                    ParsedAssistantResponse(
                        noReply = false,
                        reaction = null,
                        quote = null,
                        bubbles = emptyList(),
                        fallbackToolCalls = emptyList(),
                    ),
                pendingActions =
                    listOf(
                        PendingAction.SendVoiceNote(
                            toolCallId = "voice-1",
                            spokenText = "[angry] das reicht jetzt",
                            style = "direct",
                        ),
                    ),
            )

        val decision = verifier.verify(
            candidate = candidate,
            settings = testSettings(verification = VerificationSettings(enabled = true)),
        )

        assertTrue(decision.allowCommit)
        val request = requireNotNull(captured)
        val system = (request.messages.first().content as MessageContent.Text).value
        val message = (request.messages.last().content as MessageContent.Text).value
        assertFalse(system.contains("[react:"))
        assertFalse(system.contains("[reply"))
        assertTrue(system.contains("blank lines"))
        assertTrue(system.contains("[angry]"))
        assertEquals("[angry] das reicht jetzt", message)
    }

    /**
     * The check prompt promises the model "nothing but the text that was really sent" and lists
     * bracketed metadata as a reject reason. Labelling a caption on the way in therefore hands the
     * check a reason to block that this code wrote itself — and since the rejection regenerates,
     * the next candidate arrives with the same label in front of it. That is a paid loop with no
     * exit but the regeneration budget, and it is exactly what happened to generated images.
     */
    @Test
    fun `image captions reach the verifier as plain chat text without an added label`() = runBlocking {
        var captured: ChatCompletionRequest? = null
        val verifier =
            OpenRouterReplyVerifier(
                ChatCompletionGateway { request ->
                    captured = request
                    CompletionResult(content = """{"allow":true,"reason":"approved"}""")
                },
            )
        val candidate =
            VerificationCandidate(
                response =
                    ParsedAssistantResponse(
                        noReply = false,
                        reaction = null,
                        quote = null,
                        bubbles = emptyList(),
                        fallbackToolCalls = emptyList(),
                    ),
                pendingActions =
                    listOf(
                        PendingAction.GenerateImage(
                            toolCallId = "gen-1",
                            prompt = "a sleepy cat on a grey blanket",
                            caption = "grüße von meiner maus",
                            includeCharacter = false,
                        ),
                        PendingAction.BlockContact(toolCallId = "block-1"),
                    ),
            )

        val decision =
            verifier.verify(
                candidate = candidate,
                settings = testSettings(verification = VerificationSettings(enabled = true)),
            )

        assertTrue(decision.allowCommit)
        val message = ((requireNotNull(captured)).messages.last().content as MessageContent.Text).value
        assertEquals("grüße von meiner maus", message)
        assertFalse(message.contains("["))
        // The scene prompt is instruction to the image model, never something a contact reads.
        assertFalse(message.contains("sleepy cat"))
    }

    @Test
    fun `verifier receives only parsed bubble text without reaction or reply syntax`() = runBlocking {
        var captured: ChatCompletionRequest? = null
        val verifier =
            OpenRouterReplyVerifier(
                ChatCompletionGateway { request ->
                    captured = request
                    CompletionResult(content = """{"allow":true,"reason":"approved"}""")
                },
            )
        val raw =
            """
            [reply:"reagier – und reply das"][react:😂] hahaha
            ja safe
            """.trimIndent()
        val candidate =
            VerificationCandidate(
                response = ResponseProtocolParser().parse(raw),
                pendingActions = emptyList(),
            )

        val decision =
            verifier.verify(
                candidate = candidate,
                settings = testSettings(verification = VerificationSettings(enabled = true)),
            )

        assertTrue(decision.allowCommit)
        val request = requireNotNull(captured)
        val system = (request.messages.first().content as MessageContent.Text).value
        val message = (request.messages.last().content as MessageContent.Text).value
        assertEquals("hahaha\n\nja safe", message)
        assertFalse(message.contains("[react:"))
        assertFalse(message.contains("[reply"))
        assertFalse(message.contains("reagier – und reply das"))
        assertFalse(system.contains("[react:"))
        assertFalse(system.contains("[reply"))
        assertTrue(system.contains("When in doubt ALWAYS allow=true"))
    }

    /**
     * A generated picture is the most expensive thing this bot can do, and a model that cannot tell
     * whether its first call worked will happily pay for a second. The budget is one per turn and
     * it is counted in the session, so the continuation after a confirmed send shares it.
     */
    @Test
    fun `only one picture can be generated per turn and a bare prompt is enough`() {
        val settings = ToolAccessSettings(imageGeneration = true)
        val call = { id: String ->
            AiToolCall(id, "generate_image", """{"prompt":"my cat asleep on a grey blanket"}""")
        }

        val first = ToolRegistry.prepare(call("gen-1"), settings, context) as PreparedToolCall.Action
        val generate = first.value as PendingAction.GenerateImage
        // include_character is optional now; the model asking for a plain photo says nothing.
        assertFalse(generate.includeCharacter)
        assertEquals("my cat asleep on a grey blanket", generate.prompt)

        val second = ToolRegistry.prepare(call("gen-2"), settings, context)
        assertEquals(
            "image_already_generated",
            (second as PreparedToolCall.Rejected).reasonCode,
        )

        // A rejected candidate is thrown away whole, so its unspent picture comes back for the
        // regeneration that replaces it.
        context.session.clear(ToolRegistry.GENERATED_IMAGE)
        assertTrue(
            ToolRegistry.prepare(call("gen-3"), settings, context) is PreparedToolCall.Action,
        )
    }

    /**
     * A persona with no reference photos may still take a picture of herself.
     *
     * This used to be refused outright, which made generation impossible for every persona that
     * has no base images — and because the refusal happened before the request was built, the
     * attempt was invisible in the provider's log too. References sharpen the likeness when they
     * exist; their absence is handled in the generation prompt, not by a rejection.
     */
    @Test
    fun `a picture of the persona without a reference is still allowed`() {
        val settings = ToolAccessSettings(imageGeneration = true)
        val prepared =
            ToolRegistry.prepare(
                AiToolCall("gen-1", "generate_image", """{"prompt":"a selfie","include_character":true}"""),
                settings,
                ToolExecutionContext(
                    conversationKey = "conversation-1",
                    currentSender = "current",
                ),
            )

        val action = (prepared as PreparedToolCall.Action).value
        assertTrue((action as PendingAction.GenerateImage).includeCharacter)
    }

    @Test
    fun `failed action receipt fails the complete candidate closed`() = runBlocking {
        val completions =
            ArrayDeque(
                listOf(
                    CompletionResult(
                        content = "",
                        toolCalls =
                            listOf(
                                AiToolCall(
                                    id = "action-1",
                                    name = "send_image",
                                    argumentsJson = """{"asset_id":"img-1"}""",
                                ),
                            ),
                    ),
                    CompletionResult(content = "Hier ist es."),
                ),
            )
        val orchestrator =
            AiOrchestrator(
                completionGateway = ChatCompletionGateway { completions.removeFirst() },
                readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                    throw AssertionError("Unexpected read-only call")
                },
                actionCommitter = PendingActionCommitter { _, actions ->
                    actions.map {
                        ActionCommitReceipt(
                            toolCallId = it.toolCallId,
                            committed = false,
                            reasonCode = "asset_missing",
                        )
                    }
                },
                verifier = ReplyVerifier { _, _ ->
                    VerificationDecision(true, "approved")
                },
            )

        val result =
            orchestrator.runTurn(
                settings =
                    testSettings(
                        tools = ToolAccessSettings(imageSending = true),
                        verification = VerificationSettings(enabled = true),
                    ),
                turn = testTurn(),
                toolContext = context,
            )

        assertFalse(result.commitAllowed)
        assertEquals("action_commit_failed", result.verification.reasonCode)
        assertFalse(result.commitReceipts.single().committed)
    }

    @Test
    fun `contact block is direct-only protects admins and current sender`() {
        val enabled = ToolAccessSettings(contactBlocking = true)
        fun prepared(context: ToolExecutionContext) =
            ToolRegistry.prepare(
                AiToolCall(
                    id = "block-1",
                    name = "block_contact",
                    argumentsJson = "{}",
                ),
                enabled,
                context,
            )

        val action = prepared(context) as PreparedToolCall.Action
        assertEquals("block-1", action.value.toolCallId)
        assertTrue(action.value is PendingAction.BlockContact)
        assertTrue(
            ToolRegistry.allowed(enabled)
                .single { it.name == "block_contact" }
                .parameters.isEmpty(),
        )
        assertEquals(
            "block_not_allowed_in_group",
            (
                prepared(context.copy(isGroup = true))
                    as PreparedToolCall.Rejected
            ).reasonCode,
        )
        assertEquals(
            "admin_is_protected",
            (
                prepared(context.copy(isAdmin = true))
                    as PreparedToolCall.Rejected
            ).reasonCode,
        )
        assertEquals(
            "no_current_sender",
            (
                prepared(context.copy(currentSender = null))
                    as PreparedToolCall.Rejected
            ).reasonCode,
        )
    }

    @Test
    fun `rejected candidate never reaches committer`() = runBlocking {
        var committed = false
        var calls = 0
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway {
                calls += 1
                if (calls == 1) {
                    CompletionResult(content = "[voice:\"do it\"]")
                } else {
                    CompletionResult(content = "Voice note queued.")
                }
            },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                ToolExecutionResult("{}")
            },
            actionCommitter = PendingActionCommitter { _, _ ->
                committed = true
                emptyList()
            },
            verifier = ReplyVerifier { _, _ ->
                VerificationDecision(false, "unsafe")
            },
        )

        val result = orchestrator.runTurn(
            settings = testSettings(
                tools = ToolAccessSettings(voiceNotes = true),
                verification = VerificationSettings(enabled = true),
            ),
            turn = testTurn(),
            toolContext = context,
        )

        assertFalse(result.commitAllowed)
        assertFalse(committed)
        assertEquals("unsafe", result.verification.reasonCode)
        assertEquals(1, result.pendingActions.size)
    }

    // Running out of tool rounds used to throw, which discarded the finished turn along with every
    // lookup it had already been billed for, and the contact got silence. The budget now ends in an
    // answer: one final call with the tool list withheld, so the model has to speak.
    @Test
    fun `an exhausted tool budget ends in an answer instead of a discarded turn`() = runBlocking {
        val requests = mutableListOf<ChatCompletionRequest>()
        var calls = 0
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway { request ->
                requests += request
                calls += 1
                if (request.tools.isEmpty()) {
                    // Nothing left to call: this is the turn's last chance to say something.
                    CompletionResult(content = "hab ich alles gefunden")
                } else {
                    CompletionResult(
                        content = "",
                        toolCalls = listOf(
                            AiToolCall(
                                id = "read-$calls",
                                name = "search_current_chat",
                                argumentsJson = """{"query":"x"}""",
                            ),
                        ),
                    )
                }
            },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                ToolExecutionResult(JSONObject().put("matches", 0).toString())
            },
            actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
        )

        val result = orchestrator.runTurn(
            settings = testSettings(toolLoopLimit = 8),
            turn = testTurn(),
            toolContext = context,
        )

        // Eight rounds of tools, then exactly one tool-free call — never a ninth round of tools.
        assertEquals(9, calls)
        assertEquals(8, requests.count { it.tools.isNotEmpty() })
        assertTrue(requests.last().tools.isEmpty())
        assertEquals(listOf("hab ich alles gefunden"), result.response.bubbles)
    }

    // A provider that answers with nothing is a hiccup, not a decision by the model, so it must not
    // eat one of the rounds the model was given to do its work.
    @Test
    fun `an empty completion does not spend a tool round`() = runBlocking {
        var calls = 0
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway {
                calls += 1
                when (calls) {
                    // The whole budget is one round; the hiccup arrives before it is used.
                    1 -> CompletionResult(content = "")
                    2 -> CompletionResult(
                        content = "",
                        toolCalls = listOf(
                            AiToolCall(
                                id = "read-1",
                                name = "search_current_chat",
                                argumentsJson = """{"query":"x"}""",
                            ),
                        ),
                    )
                    else -> CompletionResult(content = "gefunden")
                }
            },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                ToolExecutionResult(JSONObject().put("matches", 0).toString())
            },
            actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
        )

        val result = orchestrator.runTurn(
            settings = testSettings(toolLoopLimit = 1),
            turn = testTurn(),
            toolContext = context,
        )

        assertEquals(3, calls)
        assertEquals(1, result.emptyCompletions)
        assertEquals(listOf("gefunden"), result.response.bubbles)
    }

    @Test
    fun `read-only result follows its tool call with matching id`() = runBlocking {
        val requests = mutableListOf<ChatCompletionRequest>()
        var calls = 0
        var verifierCalls = 0
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway { request ->
                requests += request
                calls += 1
                if (calls == 1) {
                    CompletionResult(
                        content = "",
                        toolCalls =
                            listOf(
                                AiToolCall(
                                    id = "scroll-42",
                                    name = "scroll_current_chat",
                                    argumentsJson = "{}",
                                ),
                            ),
                    )
                } else {
                    CompletionResult(content = "jetzt weiß ichs wieder")
                }
            },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                ToolExecutionResult(
                    """{"resultType":"older_messages","messages":[{"text":"damals"}]}""",
                )
            },
            actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
            verifier =
                ReplyVerifier { _, _ ->
                    verifierCalls += 1
                    VerificationDecision(true, "verified")
                },
        )

        val orchestration = orchestrator.runTurn(
            settings = testSettings(verification = VerificationSettings(enabled = true)),
            turn = testTurn(),
            toolContext = context,
        )

        val followUp = requests.first { request ->
            request.messages.any { it.role == ChatRole.TOOL }
        }
        val assistantCall = followUp.messages.first { it.toolCalls.isNotEmpty() }
        val result = followUp.messages.first { it.role == ChatRole.TOOL }
        assertEquals("scroll-42", assistantCall.toolCalls.single().id)
        assertEquals("scroll-42", result.toolCallId)
        assertEquals("scroll_current_chat", result.name)
        assertTrue((result.content as MessageContent.Text).value.contains("older_messages"))
        assertEquals(2, calls)
        assertEquals("verified", orchestration.verification.reasonCode)
        assertEquals(1, verifierCalls)
    }

    @Test
    fun `destructive action without a verifier fails closed and records why`() = runBlocking {
        var committed = false
        val completions =
            ArrayDeque(
                listOf(
                    CompletionResult(
                        content = "",
                        toolCalls = listOf(AiToolCall("block-1", "block_contact", "{}")),
                    ),
                    CompletionResult(content = "Erledigt."),
                ),
            )
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway {
                completions.removeFirst()
            },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                ToolExecutionResult("{}")
            },
            actionCommitter = PendingActionCommitter { _, actions ->
                committed = true
                actions.map { ActionCommitReceipt(it.toolCallId, committed = true) }
            },
            verifier = null,
        )

        val result = orchestrator.runTurn(
            testSettings(
                tools = ToolAccessSettings(contactBlocking = true),
                verification = VerificationSettings(enabled = true),
            ),
            testTurn(),
            context,
        )

        assertFalse(result.commitAllowed)
        assertFalse(committed)
        assertEquals("verifier_unavailable", result.verification.reasonCode)
        assertEquals(
            VerificationFallback.POLICY_FAIL_CLOSED,
            result.verification.fallback?.policy,
        )
        assertEquals("verifier_unavailable", result.verification.fallback?.cause)
    }

    @Test
    fun `fail closed verification without a verifier still blocks the commit`() = runBlocking {
        var committed = false
        var mainCalls = 0
        val completions =
            ArrayDeque(
                listOf(
                    CompletionResult(
                        content = "",
                        toolCalls = listOf(AiToolCall("block-1", "block_contact", "{}")),
                    ),
                    CompletionResult(content = "Erledigt."),
                ),
            )
        val orchestrator = AiOrchestrator(
            completionGateway = ChatCompletionGateway {
                mainCalls += 1
                completions.removeFirst()
            },
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ ->
                ToolExecutionResult("{}")
            },
            actionCommitter = PendingActionCommitter { _, _ ->
                committed = true
                emptyList()
            },
            verifier = null,
        )

        val result = orchestrator.runTurn(
            testSettings(
                tools = ToolAccessSettings(contactBlocking = true),
                verification = VerificationSettings(
                    enabled = true,
                    maxRegenerations = 3,
                    failClosed = true,
                ),
            ),
            testTurn(),
            context,
        )

        assertFalse(result.commitAllowed)
        assertFalse(committed)
        // One tool request plus its mandatory final assistant response; no regeneration cycle.
        assertEquals(2, mainCalls)
        assertEquals(2, result.modelCalls)
        assertEquals("verifier_unavailable", result.verification.reasonCode)
        assertEquals(
            VerificationFallback.POLICY_FAIL_CLOSED,
            result.verification.fallback?.policy,
        )
    }

    @Test
    fun `fail closed verifier failure never regenerates the main text candidate`() = runBlocking {
        var mainCalls = 0
        var verifierCalls = 0
        val orchestrator =
            AiOrchestrator(
                completionGateway = ChatCompletionGateway {
                    mainCalls += 1
                    CompletionResult(content = "Hey")
                },
                readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ -> ToolExecutionResult("{}") },
                actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
                verifier = ReplyVerifier { _, _ ->
                    verifierCalls += 1
                    error("HTTP 400")
                },
            )

        val result =
            orchestrator.runTurn(
                testSettings(
                    verification = VerificationSettings(
                        enabled = true,
                        maxRegenerations = 3,
                        failClosed = true,
                    ),
                ),
                testTurn(),
                context,
            )

        assertFalse(result.commitAllowed)
        assertEquals("verifier_failed", result.verification.reasonCode)
        assertEquals(VerificationFallback.POLICY_FAIL_CLOSED, result.verification.fallback?.policy)
        assertEquals(1, mainCalls)
        assertEquals(1, verifierCalls)
        assertEquals(1, result.modelCalls)
    }

    @Test
    fun `action free reaction and text still use the enabled verifier`() = runBlocking {
        var modelCalls = 0
        var verifierCalls = 0
        val orchestrator =
            AiOrchestrator(
                completionGateway = ChatCompletionGateway {
                    modelCalls += 1
                    CompletionResult(content = "[react:🔥] Genau.\nZweite Bubble.")
                },
                readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ -> ToolExecutionResult("{}") },
                actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
                verifier =
                    ReplyVerifier { _, _ ->
                        verifierCalls += 1
                        VerificationDecision(true, "verified")
                    },
            )

        val result =
            orchestrator.runTurn(
                settings = testSettings(verification = VerificationSettings(enabled = true)),
                turn = testTurn(),
                toolContext = context,
            )

        assertTrue(result.commitAllowed)
        assertEquals("verified", result.verification.reasonCode)
        assertEquals("🔥", result.response.reaction)
        assertEquals(listOf("Genau.", "Zweite Bubble."), result.response.bubbles)
        assertEquals(1, modelCalls)
        assertEquals(1, result.modelCalls)
        assertEquals(1, verifierCalls)
    }

    @Test
    fun `reply and reaction without message text never reach the verifier`() = runBlocking {
        var verifierCalls = 0
        val orchestrator =
            AiOrchestrator(
                completionGateway = ChatCompletionGateway {
                    CompletionResult(content = "[reply][react:👍]")
                },
                readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ -> ToolExecutionResult("{}") },
                actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
                verifier = ReplyVerifier { _, _ ->
                    verifierCalls += 1
                    VerificationDecision(true, "unexpected")
                },
            )

        val result =
            orchestrator.runTurn(
                settings = testSettings(verification = VerificationSettings(enabled = true)),
                turn = testTurn(),
                toolContext = context,
            )

        assertTrue(result.commitAllowed)
        assertEquals("nothing_to_verify", result.verification.reasonCode)
        assertEquals("👍", result.response.reaction)
        assertNotNull(result.response.quote)
        assertTrue(result.response.bubbles.isEmpty())
        assertEquals(0, verifierCalls)
    }

    @Test
    fun `no reply candidate can never commit a pending mutation`() = runBlocking {
        var committed = false
        val completions =
            ArrayDeque(
                listOf(
                    CompletionResult(
                        content = "",
                        toolCalls =
                            listOf(
                                AiToolCall(
                                    id = "image-1",
                                    name = "send_image",
                                    argumentsJson = """{"asset_id":"img-1"}""",
                                ),
                            ),
                    ),
                    CompletionResult(content = "[no reply]"),
                ),
            )
        val orchestrator =
            AiOrchestrator(
                completionGateway = ChatCompletionGateway { completions.removeFirst() },
                readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ -> ToolExecutionResult("{}") },
                actionCommitter =
                    PendingActionCommitter { _, _ ->
                        committed = true
                        emptyList()
                    },
            )

        val result =
            orchestrator.runTurn(
                settings =
                    testSettings(
                        tools = ToolAccessSettings(imageSending = true),
                        output = OutputSettings(allowNoReply = true),
                    ),
                turn = testTurn(),
                toolContext = context,
            )

        assertFalse(result.commitAllowed)
        assertFalse(committed)
        assertEquals("no_reply_with_actions", result.verification.reasonCode)
    }

    @Test
    fun `pure no reply bypasses the paid verifier`() = runBlocking {
        var verifierCalls = 0
        val orchestrator =
            AiOrchestrator(
                completionGateway = ChatCompletionGateway {
                    CompletionResult(content = "[no reply]")
                },
                readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ -> ToolExecutionResult("{}") },
                actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
                verifier =
                    ReplyVerifier { _, _ ->
                        verifierCalls += 1
                        VerificationDecision(true, "verified")
                    },
            )

        val result =
            orchestrator.runTurn(
                settings =
                    testSettings(
                        output = OutputSettings(allowNoReply = true),
                        verification = VerificationSettings(enabled = true),
                    ),
                turn = testTurn(),
                toolContext = context,
            )

        assertTrue(result.commitAllowed)
        assertTrue(result.response.noReply)
        assertEquals("no_reply", result.verification.reasonCode)
        assertEquals(0, verifierCalls)
    }
}
