package de.totec.doppel.integration

import de.totec.doppel.ai.AiMessage
import de.totec.doppel.ai.ChatCompletionGateway
import de.totec.doppel.ai.ChatCompletionRequest
import de.totec.doppel.ai.ChatRole
import de.totec.doppel.ai.CacheControl
import de.totec.doppel.ai.CompletionResult
import de.totec.doppel.ai.MessageContent
import de.totec.doppel.ai.SamplingSettings
import de.totec.doppel.ai.TokenUsage
import de.totec.doppel.ai.ToolChoice
import de.totec.doppel.ai.ToolDefinition
import de.totec.doppel.engine.ConversationMemoryPolicy
import de.totec.doppel.engine.MemoryWorkFeed
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryRefreshServiceTest {
    @Test
    fun `one bounded utility call rewrites the chat memory as prose`() = runBlocking {
        // Short of the persona cadence, so the one call under test is the only one.
        val persistence = FakePersistence(source().copy(chatRevision = 1))
        val calls = mutableListOf<ChatCompletionRequest>()
        val gateway =
            ChatCompletionGateway { request ->
                calls += request
                CompletionResult(
                    content = "$SUMMARY\nAPI key: should-not-survive",
                    usage = TokenUsage(promptTokens = 410, completionTokens = 90, totalTokens = 500),
                )
            }
        val service = MemoryRefreshService(gateway, persistence, wallTimeMillis = { 9_000L })

        val outcome = service.refresh(request(characterLimit = 1_500))

        assertTrue(outcome is MemoryRefreshOutcome.Updated)
        assertTrue(outcome.succeeded)
        assertEquals(1, calls.size)
        val request = calls.single()
        assertEquals("main/model", request.model)
        assertEquals(true, request.stream)
        assertEquals("memory_refresh", request.requestTag)
        assertEquals(2, request.messages.size)
        // The transcript travels in the user message; the sections live in the system prompt.
        val task = (request.messages.last().content as MessageContent.Text).value
        assertTrue(task.contains("Old memory (carry it over as far as possible):"))
        assertTrue(task.contains("1500 characters"))

        val committed = requireNotNull(persistence.committedChat)
        assertEquals(9_000L, committed.updatedAt)
        assertEquals("$SUMMARY\nAPI key=[redacted]", committed.summary)
        assertEquals(1, persistence.audits.size)
        assertTrue(persistence.audits.single().outcome is MemoryRefreshOutcome.Updated)
    }

    @Test
    fun `no new messages is a successful no-op without model cost`() = runBlocking {
        val persistence = FakePersistence(source(messages = emptyList()))
        var calls = 0
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        error("must not be called")
                    },
                persistence = persistence,
            )

        val outcome = service.refresh(request())

        assertEquals(MemoryRefreshOutcome.Skipped("no_new_messages"), outcome)
        assertTrue(outcome.succeeded)
        assertEquals(0, calls)
        assertNull(persistence.committedChat)
        assertTrue(persistence.audits.single().outcome is MemoryRefreshOutcome.Skipped)
    }

    @Test
    fun `a fragment too short to be a summary never overwrites the old memory`() = runBlocking {
        val persistence = FakePersistence(source())
        val service =
            MemoryRefreshService(
                gateway = ChatCompletionGateway { CompletionResult("Ok, mach ich!") },
                persistence = persistence,
            )

        val outcome = service.refresh(request())

        assertEquals(MemoryRefreshOutcome.Failed("invalid_memory_response"), outcome)
        assertFalse(outcome.succeeded)
        assertNull(persistence.committedChat)
        assertEquals(
            listOf("invalid_memory_response", "invalid_memory_response"),
            persistence.audits.map { (it.outcome as MemoryRefreshOutcome.Failed).reasonCode },
        )
    }

    @Test
    fun `a failed write is attempted a second time straight away and then gives up`() = runBlocking {
        // Short of the persona cadence, so the only calls counted are the two attempts.
        val persistence = FakePersistence(source().copy(chatRevision = 1))
        var calls = 0
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        if (calls == 1) throw IOException("offline") else CompletionResult(SUMMARY)
                    },
                persistence = persistence,
            )

        val outcome = service.refresh(request())

        // Two attempts, no third: past this the marker has not moved, so the next incoming
        // message re-enters the cadence gate on exactly the same batch.
        assertEquals(2, calls)
        assertTrue(outcome is MemoryRefreshOutcome.Updated)
        assertEquals(SUMMARY, requireNotNull(persistence.committedChat).summary)
    }

    @Test
    fun `OpenRouter stream failure is isolated and does not overwrite memory`() = runBlocking {
        val persistence = FakePersistence(source())
        var calls = 0
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        throw IOException("offline")
                    },
                persistence = persistence,
            )

        val outcome = service.refresh(request())

        assertEquals(MemoryRefreshOutcome.Failed("openrouter_network_unavailable"), outcome)
        assertEquals(2, calls)
        assertNull(persistence.committedChat)
        assertTrue(persistence.audits.all { it.outcome is MemoryRefreshOutcome.Failed })
    }

    @Test
    fun `persistence failure is reported without leaking generated content`() = runBlocking {
        val persistence = FakePersistence(source(), failCommit = true)
        val service =
            MemoryRefreshService(
                gateway = ChatCompletionGateway { CompletionResult(SUMMARY) },
                persistence = persistence,
            )

        val outcome = service.refresh(request())

        assertEquals(MemoryRefreshOutcome.Failed("memory_persistence_failed"), outcome)
        assertEquals(
            listOf("memory_persistence_failed", "memory_persistence_failed"),
            persistence.audits.map { (it.outcome as MemoryRefreshOutcome.Failed).reasonCode },
        )
    }

    @Test
    fun `cancellation is never converted to a memory failure`() = runBlocking {
        val persistence = FakePersistence(source())
        val service =
            MemoryRefreshService(
                gateway = ChatCompletionGateway { throw CancellationException("stopped") },
                persistence = persistence,
            )

        try {
            service.refresh(request())
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Expected: stopping a turn must cancel its network work immediately.
        }
        assertTrue(persistence.audits.isEmpty())
    }

    @Test
    fun `summary character budget is clamped to safe bounds`() {
        assertEquals(256, request(characterLimit = 1).outputCharacterLimit())
        assertEquals(12_000, request(characterLimit = 100_000).outputCharacterLimit())
        assertEquals(6_000, request(characterLimit = null).outputCharacterLimit())
    }

    @Test
    fun `automatic cadence performs no model call below persistent delta`() = runBlocking {
        val persistence = FakePersistence(source().copy(newMessageCount = 9))
        var calls = 0
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        error("must not be called below cadence")
                    },
                persistence = persistence,
                configurationProvider = configuration(),
            )

        val outcome =
            service.refreshIfDue(
                conversationKey = "49123@s.whatsapp.net#human",
                personaKey = "human",
            )

        assertEquals(MemoryRefreshOutcome.Skipped("cadence_not_due"), outcome)
        assertEquals(0, calls)
        assertNull(persistence.committedChat)
    }

    @Test
    fun `automatic cadence consolidates once the configured interval is new`() = runBlocking {
        val persistence =
            FakePersistence(
                source().copy(
                    chatRevision = 1,
                    newMessageCount = ConversationMemoryPolicy.DEFAULT_MEMORY_INTERVAL_MESSAGES,
                ),
            )
        var calls = 0
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
                configurationProvider =
                    MemoryRefreshConfigurationProvider { conversationKey, personaKey ->
                        assertEquals("49123@s.whatsapp.net#human", conversationKey)
                        assertEquals("human", personaKey)
                        MemoryRefreshConfiguration("main/model", 2_000)
                    },
            )

        val outcome =
            service.refreshIfDue(
                conversationKey = "49123@s.whatsapp.net#human",
                personaKey = "human",
            )

        assertTrue(outcome is MemoryRefreshOutcome.Updated)
        assertEquals(1, calls)
        assertNotNull(persistence.committedChat)
    }

    @Test
    fun `chat memory off still turns the window over, without a model call`() = runBlocking {
        val persistence =
            FakePersistence(
                source().copy(
                    chatRevision = 1,
                    newMessageCount = ConversationMemoryPolicy.DEFAULT_MEMORY_INTERVAL_MESSAGES,
                ),
            )
        var calls = 0
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        error("a disabled chat memory must never reach the model")
                    },
                persistence = persistence,
                configurationProvider = configuration(chatMemoryEnabled = false),
                wallTimeMillis = { 9_000L },
            )

        val outcome =
            service.refreshIfDue(
                conversationKey = "49123@s.whatsapp.net#human",
                personaKey = "human",
            )

        assertEquals(MemoryRefreshOutcome.Skipped("chat_memory_disabled"), outcome)
        assertTrue(outcome.succeeded)
        assertEquals(0, calls)
        assertNull(persistence.committedChat)
        assertNull(persistence.committedPersona)
        // The pointer moves to the newest message read, which is the whole point: the rendered
        // window is pinned until the revision changes, and a pinned window grows forever.
        assertEquals(WindowAdvance("m2", 9_000L), persistence.advancedWindow)
        assertEquals(
            listOf("chat_memory_disabled"),
            persistence.audits.map { (it.outcome as MemoryRefreshOutcome.Skipped).reasonCode },
        )
    }

    @Test
    fun `chat memory off below the interval leaves the pointer alone`() = runBlocking {
        val persistence = FakePersistence(source().copy(newMessageCount = 9))
        val service =
            MemoryRefreshService(
                gateway = ChatCompletionGateway { error("must not be called below cadence") },
                persistence = persistence,
                configurationProvider = configuration(chatMemoryEnabled = false),
            )

        val outcome =
            service.refreshIfDue(
                conversationKey = "49123@s.whatsapp.net#human",
                personaKey = "human",
            )

        assertEquals(MemoryRefreshOutcome.Skipped("cadence_not_due"), outcome)
        assertNull(persistence.advancedWindow)
    }

    @Test
    fun `pressing Create Memory writes one even with chat memory off`() = runBlocking {
        val persistence = FakePersistence(source().copy(chatRevision = 1))
        var calls = 0
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
                configurationProvider = configuration(chatMemoryEnabled = false),
            )

        val outcome =
            service.refreshIfDue(
                conversationKey = "49123@s.whatsapp.net#human",
                personaKey = "human",
                threshold = 1,
                firstWriteThreshold = 1,
                demanded = true,
            )

        assertTrue(outcome is MemoryRefreshOutcome.Updated)
        assertEquals(1, calls)
        assertNotNull(persistence.committedChat)
        assertNull(persistence.advancedWindow)
    }

    @Test
    fun `global memory off writes the chat memory and nothing else`() = runBlocking {
        // At the cadence: with global memory on this exact source synthesises as well.
        val persistence = FakePersistence(source())
        val calls = mutableListOf<String>()
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway { request ->
                        calls += request.requestTag.orEmpty()
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
                configurationProvider = configuration(globalMemoryEnabled = false),
            )

        val outcome =
            service.refreshIfDue(
                conversationKey = "49123@s.whatsapp.net#human",
                personaKey = "human",
                threshold = 1,
                firstWriteThreshold = 1,
                forcePersonaSynthesis = true,
            )

        assertTrue(outcome is MemoryRefreshOutcome.Updated)
        assertFalse((outcome as MemoryRefreshOutcome.Updated).personaSynthesized)
        assertEquals(listOf("memory_refresh"), calls)
        assertNotNull(persistence.committedChat)
        assertNull(persistence.committedPersona)
    }

    @Test
    fun `model-requested memory refresh writes chat then persona immediately`() = runBlocking {
        val persistence = FakePersistence(source().copy(personaSynthesisMarker = 10_000))
        val calls = mutableListOf<String>()
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway { request ->
                        calls += request.requestTag.orEmpty()
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
                configurationProvider = configuration(personaSynthesisEvery = 10),
            )

        val outcome =
            service.refreshIfDue(
                conversationKey = "49123@s.whatsapp.net#human",
                personaKey = "human",
                threshold = 1,
                firstWriteThreshold = 1,
                forcePersonaSynthesis = true,
            )

        assertTrue(outcome is MemoryRefreshOutcome.Updated)
        assertTrue((outcome as MemoryRefreshOutcome.Updated).personaSynthesized)
        assertEquals(listOf("memory_refresh", "memory_persona_synthesis"), calls)
        assertNotNull(persistence.committedChat)
        assertNotNull(persistence.committedPersona)
    }

    @Test
    fun `the configured ratio decides how often the persona view is rebuilt`() = runBlocking {
        // Four chat writes stand behind the marker, so a ratio of four is due and five is not.
        val due =
            FakePersistence(
                source().copy(personaSynthesisMarker = 0),
                personaMemories =
                    listOf(
                        PersonaChatMemory(
                            label = "a1b2c3",
                            isGroup = false,
                            summary = "Erinnerung aus einem anderen Chat",
                            revision = 4,
                        ),
                    ),
            )
        MemoryRefreshService(
            gateway = ChatCompletionGateway { CompletionResult(SUMMARY) },
            persistence = due,
            configurationProvider = configuration(personaSynthesisEvery = 4),
        ).refreshIfDue("49123@s.whatsapp.net#human", "human", threshold = 1, firstWriteThreshold = 1)
        assertNotNull(due.committedPersona)

        val notDue =
            FakePersistence(
                source().copy(personaSynthesisMarker = 0),
                personaMemories =
                    listOf(
                        PersonaChatMemory(
                            label = "a1b2c3",
                            isGroup = false,
                            summary = "Erinnerung aus einem anderen Chat",
                            revision = 4,
                        ),
                    ),
            )
        MemoryRefreshService(
            gateway = ChatCompletionGateway { CompletionResult(SUMMARY) },
            persistence = notDue,
            configurationProvider = configuration(personaSynthesisEvery = 5),
        ).refreshIfDue("49123@s.whatsapp.net#human", "human", threshold = 1, firstWriteThreshold = 1)
        assertNotNull(notDue.committedChat)
        assertNull(notDue.committedPersona)
    }

    @Test
    fun `first automatic memory waits for a complete window`() = runBlocking {
        // Nothing has been summarized yet, so the first write waits for retained + interval.
        val window = ConversationMemoryPolicy.DEFAULT_COMPLETE_CHAT_WINDOW_MESSAGES
        val below = FakePersistence(source().copy(chatRevision = 0, newMessageCount = window - 1))
        var belowCalls = 0
        val belowOutcome =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        belowCalls += 1
                        CompletionResult(SUMMARY)
                    },
                persistence = below,
                configurationProvider = configuration(),
            ).refreshIfDue("49123@s.whatsapp.net#human", "human")

        assertEquals(MemoryRefreshOutcome.Skipped("cadence_not_due"), belowOutcome)
        assertEquals(0, belowCalls)

        val due = FakePersistence(source().copy(chatRevision = 0, newMessageCount = window))
        var dueCalls = 0
        val dueOutcome =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        dueCalls += 1
                        CompletionResult(SUMMARY)
                    },
                persistence = due,
                configurationProvider = configuration(),
            ).refreshIfDue("49123@s.whatsapp.net#human", "human")

        assertTrue(dueOutcome is MemoryRefreshOutcome.Updated)
        assertEquals(1, dueCalls)
    }

    @Test
    fun `a handed-over turn prompt is reused verbatim so the provider cache serves it`() =
        runBlocking {
            // Short of the persona cadence, so the reused prompt is the only request sent.
            val persistence = FakePersistence(source().copy(chatRevision = 1))
            val calls = mutableListOf<ChatCompletionRequest>()
            val service =
                MemoryRefreshService(
                    gateway =
                        ChatCompletionGateway { request ->
                            calls += request
                            CompletionResult(SUMMARY)
                        },
                    persistence = persistence,
                )

            val outcome = service.refresh(request().copy(cachedPrompt = cachedPrompt()))

            assertTrue(outcome is MemoryRefreshOutcome.Updated)
            val sent = calls.single()
            // The model of the cached turn, not the configured one: only that prompt is in the
            // provider's cache.
            assertEquals("cached/model", sent.model)
            assertEquals("memory_refresh_cached", sent.requestTag)
            // The turn's messages must survive byte-identical, with the instruction appended.
            assertEquals(turnPromptMessages(), sent.messages.dropLast(1))
            // USER, never SYSTEM: providers hoist system messages to the front of the request,
            // which moves the cached prefix and re-bills the whole history.
            assertEquals(ChatRole.USER, sent.messages.last().role)
            // Byte-identical to the turn down to `tool_choice`, because the provider renders the
            // tool definitions into the prompt itself: asking for a different choice than the turn
            // used moved that rendering and cost the cache everything past the system block
            // (3,072 of 16,456 tokens on 2026-08-10). The call is forbidden in words instead, by
            // CACHED_TASK_OVERRIDE, which is asserted below.
            assertEquals(turnTools(), sent.tools)
            assertEquals(ToolChoice.AUTO, sent.toolChoice)
            assertEquals(cachedSampling(), sent.sampling)
            // The prompt's history covers all pending messages, so they are not sent twice.
            val instruction = (sent.messages.last().content as MessageContent.Text).value
            assertFalse(instruction.contains("Bitte antworte eher kurz."))
            assertTrue(instruction.contains("STOP."))
            // The reused prompt also carries the persona's cross-chat file, and the model was
            // copying entries about other people out of it into this chat's memory.
            assertTrue(instruction.contains("Global Memory"))
        }

    @Test
    fun `a backlog larger than the reused history is still inlined`() = runBlocking {
        // Short of the persona cadence, so the reused prompt is the only request sent.
        val persistence = FakePersistence(source().copy(chatRevision = 1, newMessageCount = 70))
        val calls = mutableListOf<ChatCompletionRequest>()
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway { request ->
                        calls += request
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
            )

        service.refresh(request().copy(cachedPrompt = cachedPrompt(providerMessageIds = emptySet())))

        val instruction =
            (calls.single().messages.last().content as MessageContent.Text).value
        assertTrue(instruction.contains("Bitte antworte eher kurz."))
    }

    @Test
    fun `the persona memory is synthesized on every third chat refresh`() = runBlocking {
        // chatRevision 1 becomes revision 2 — two writes past the last synthesis, so no second call.
        val quiet = FakePersistence(source().copy(chatRevision = 1))
        var quietCalls = 0
        MemoryRefreshService(
            gateway =
                ChatCompletionGateway {
                    quietCalls += 1
                    CompletionResult(SUMMARY)
                },
            persistence = quiet,
        ).refresh(request())

        assertEquals(1, quietCalls)
        assertNull(quiet.committedPersona)
        assertFalse((quiet.audits.single().outcome as MemoryRefreshOutcome.Updated).personaSynthesized)

        // chatRevision 2 becomes revision 3 — a full interval past the marker, so it is rebuilt.
        val due = FakePersistence(source().copy(chatRevision = 2))
        val calls = mutableListOf<ChatCompletionRequest>()
        val outcome =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway { request ->
                        calls += request
                        CompletionResult(SUMMARY)
                    },
                persistence = due,
                wallTimeMillis = { 5_000L },
            ).refresh(request())

        assertEquals(2, calls.size)
        assertEquals("memory_persona_synthesis", calls.last().requestTag)
        val payload = (calls.last().messages.last().content as MessageContent.Text).value
        // The chats are labelled, never named: this text is prompted into every chat.
        assertTrue(payload.contains("Erinnerung aus einem anderen Chat"))
        assertFalse(payload.contains("49123@s.whatsapp.net"))
        val persona = requireNotNull(due.committedPersona)
        assertEquals(8L, persona.revision)
        assertEquals(5_000L, persona.updatedAt)
        assertTrue((outcome as MemoryRefreshOutcome.Updated).personaSynthesized)
        assertEquals(8L, outcome.personaRevision)
    }

    @Test
    fun `three chat writes across different chats trigger persona memory`() = runBlocking {
        val persistence =
            FakePersistence(
                source = source().copy(chatRevision = 0),
                personaMemories =
                    listOf(
                        PersonaChatMemory("chat-a", false, "A", revision = 1),
                        PersonaChatMemory("chat-b", false, "B", revision = 1),
                        PersonaChatMemory("chat-c", true, "C", revision = 1),
                    ),
            )
        var calls = 0

        val outcome =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
            ).refresh(request())

        assertEquals(2, calls)
        assertNotNull(persistence.committedPersona)
        assertTrue((outcome as MemoryRefreshOutcome.Updated).personaSynthesized)
    }

    @Test
    fun `a forced persona synthesis re-arms the cadence instead of leaving it due`() = runBlocking {
        // One chat write in total: far short of the interval, so only forcing gets past the gate.
        val persistence = FakePersistence(source().copy(chatRevision = 0))
        val calls = mutableListOf<ChatCompletionRequest>()
        val outcome =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway { request ->
                        calls += request
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
                configurationProvider = configuration(),
                wallTimeMillis = { 9_000L },
            ).synthesizePersona("human")

        // The cross-chat call and nothing else — no chat memory is written in front of it.
        assertEquals(1, calls.size)
        assertNull(persistence.committedChat)
        assertTrue((outcome as MemoryRefreshOutcome.Updated).personaSynthesized)
        assertEquals(CacheControl.EPHEMERAL, calls.single().messages.last().cacheControl)
        assertEquals(8L, outcome.personaRevision)
        val persona = requireNotNull(persistence.committedPersona)
        assertEquals(9_000L, persona.updatedAt)
        // The stored total is what makes the next automatic synthesis a full interval away.
        assertEquals(1L, persona.chatWriteCount)
    }

    @Test
    fun `cancelling forced persona synthesis stops its call and clears visible work`() = runBlocking {
        val persistence = FakePersistence(source().copy(chatRevision = 0))
        val feed = MemoryWorkFeed()
        val entered = CompletableDeferred<Unit>()
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                persistence = persistence,
                configurationProvider = configuration(),
                work = feed,
            )

        val job = async { service.synthesizePersona("human") }
        entered.await()
        assertTrue(feed.inFlight.value.any { it.personaKey == "human" })

        job.cancel(CancellationException("operator abandoned global memory"))
        try {
            job.await()
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Expected: cancellation is control flow, never a failed/committed memory result.
        }

        assertTrue(feed.inFlight.value.isEmpty())
        assertNull(persistence.committedPersona)
    }

    @Test
    fun `a failed persona synthesis keeps the committed chat memory`() = runBlocking {
        val persistence = FakePersistence(source().copy(chatRevision = 2))
        var calls = 0
        val outcome =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        calls += 1
                        if (calls == 1) CompletionResult(SUMMARY) else throw IOException("offline")
                    },
                persistence = persistence,
            ).refresh(request())

        assertTrue(outcome is MemoryRefreshOutcome.Updated)
        assertNotNull(persistence.committedChat)
        assertNull(persistence.committedPersona)
        assertFalse((outcome as MemoryRefreshOutcome.Updated).personaSynthesized)
    }

    @Test
    fun `a group memory is marked so the persona synthesis can tell them apart`() = runBlocking {
        val persistence =
            FakePersistence(source().copy(chatId = "4912345-1616@g.us"))
        val service =
            MemoryRefreshService(
                gateway = ChatCompletionGateway { CompletionResult(SUMMARY) },
                persistence = persistence,
            )

        service.refresh(
            MemoryRefreshRequest(
                conversationKey = "4912345-1616@g.us#human",
                personaId = "human",
                model = "main/model",
                configuredCharacterLimit = 2_000,
            ),
        )

        assertTrue(requireNotNull(persistence.committedChat).summary.startsWith("[GROUP CHAT]"))
    }

    @Test
    fun `memory writes are announced before the model call, not only afterwards`() = runBlocking {
        val persistence = FakePersistence(source())
        val service =
            MemoryRefreshService(
                gateway =
                    ChatCompletionGateway {
                        // The start entry must already exist while the request is in flight.
                        assertEquals(1, persistence.started.size)
                        CompletionResult(SUMMARY)
                    },
                persistence = persistence,
            )

        service.refresh(request())

        assertEquals("main/model", persistence.started.single())
    }

    private fun turnPromptMessages() =
        listOf(
            AiMessage.text(ChatRole.SYSTEM, "persona instructions"),
            AiMessage.text(ChatRole.USER, "[12:00] hallo"),
            AiMessage.text(ChatRole.SYSTEM, "live block: 12:01"),
        )

    private fun turnTools() =
        listOf(ToolDefinition(name = "send_voice_note", description = "spricht"))

    private fun cachedSampling() = SamplingSettings(temperature = 0.42, topP = 0.81)

    private fun cachedPrompt(providerMessageIds: Set<String> = setOf("m1", "m2")) =
        CachedTurnPrompt(
            model = "cached/model",
            messages = turnPromptMessages(),
            tools = turnTools(),
            sampling = cachedSampling(),
            providerMessageIds = providerMessageIds,
        )

    private fun configuration(
        chatMemoryEnabled: Boolean = true,
        globalMemoryEnabled: Boolean = true,
        personaSynthesisEvery: Long =
            ConversationMemoryPolicy.DEFAULT_PERSONA_MEMORY_EVERY_CHAT_REFRESHES.toLong(),
    ) = MemoryRefreshConfigurationProvider { _, _ ->
        MemoryRefreshConfiguration(
            model = "main/model",
            characterLimit = 2_000,
            chatMemoryEnabled = chatMemoryEnabled,
            globalMemoryEnabled = globalMemoryEnabled,
            personaSynthesisEvery = personaSynthesisEvery,
        )
    }

    private fun request(characterLimit: Int? = 2_000) =
        MemoryRefreshRequest(
            conversationKey = "49123@s.whatsapp.net#human",
            personaId = "human",
            model = "main/model",
            configuredCharacterLimit = characterLimit,
        )

    private fun source(
        messages: List<MemorySourceMessage> =
            listOf(
                MemorySourceMessage("m1", "user", "Bitte antworte eher kurz.", 1_000L),
                MemorySourceMessage("m2", "assistant", "Klar.", 2_000L),
            ),
    ) = MemoryRefreshSource(
        chatId = "49123@s.whatsapp.net",
        previousChatSummary = "Vorherige Chat-Zusammenfassung",
        previousPersonaSummary = "Vorherige Persona-Zusammenfassung",
        chatRevision = 3,
        personaRevision = 7,
        sourceMessageCount = 12,
        newMessageCount = messages.size,
        newestProviderMessageId = "m2",
        messages = messages,
    )

    private class FakePersistence(
        private val source: MemoryRefreshSource,
        private val failCommit: Boolean = false,
        private val personaMemories: List<PersonaChatMemory>? = null,
    ) : MemoryRefreshPersistence {
        var committedChat: ChatCommit? = null
        var committedPersona: PersonaCommit? = null
        var advancedWindow: WindowAdvance? = null
        val audits = mutableListOf<AuditCall>()
        val started = mutableListOf<String>()

        override suspend fun load(request: MemoryRefreshRequest): MemoryRefreshSource = source

        override suspend fun auditStarted(
            request: MemoryRefreshRequest,
            source: MemoryRefreshSource,
            model: String,
            occurredAt: Long,
        ) {
            started += model
        }

        override suspend fun commitChat(
            request: MemoryRefreshRequest,
            source: MemoryRefreshSource,
            summary: String,
            updatedAt: Long,
        ) {
            if (failCommit) throw IllegalStateException("database unavailable")
            committedChat = ChatCommit(summary, updatedAt)
        }

        override suspend fun advanceChatWindow(
            request: MemoryRefreshRequest,
            source: MemoryRefreshSource,
            updatedAt: Long,
        ) {
            if (failCommit) throw IllegalStateException("database unavailable")
            advancedWindow = WindowAdvance(source.newestProviderMessageId, updatedAt)
        }

        override suspend fun loadPersonaChatMemories(
            request: MemoryRefreshRequest,
        ): List<PersonaChatMemory> =
            personaMemories
                ?: listOf(
                    PersonaChatMemory(
                        label = "a1b2c3",
                        isGroup = false,
                        summary = "Erinnerung aus einem anderen Chat",
                        revision = source.chatRevision + 1,
                    ),
                )

        override suspend fun commitPersona(
            request: MemoryRefreshRequest,
            revision: Long,
            summary: String,
            updatedAt: Long,
            chatWriteCount: Long,
        ) {
            committedPersona = PersonaCommit(revision, summary, updatedAt, chatWriteCount)
        }

        override suspend fun audit(
            request: MemoryRefreshRequest,
            outcome: MemoryRefreshOutcome,
            source: MemoryRefreshSource?,
            usage: TokenUsage?,
            occurredAt: Long,
        ) {
            audits += AuditCall(outcome, usage, occurredAt)
        }
    }

    private data class ChatCommit(val summary: String, val updatedAt: Long)

    private data class WindowAdvance(val marker: String?, val updatedAt: Long)

    private data class PersonaCommit(
        val revision: Long,
        val summary: String,
        val updatedAt: Long,
        val chatWriteCount: Long = 0,
    )

    private data class AuditCall(
        val outcome: MemoryRefreshOutcome,
        val usage: TokenUsage?,
        val occurredAt: Long,
    )

    private companion object {
        /** Long enough to pass the "this is not a fragment" gate the service applies. */
        const val SUMMARY =
            "ÜBER MICH: Ich heiße Mia, 24, wohne in Köln und arbeite im Café. " +
                "ÜBER DEN CHATPARTNER: Mag kurze Antworten, schreibt abends."
    }
}
