package de.totec.doppel.ai

import de.totec.doppel.integration.HistoryWindowAnchor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * P3: the cacheable prefix must stay byte-identical across the things that happen every day —
 * the same message twice, a clock that ticks, a mood that shifts, a chat that grows, a reconnect
 * that rebuilds the turn from scratch. Anything that moves the prefix hash costs a full re-bill
 * of the whole context on the next call.
 *
 * The prefix hash is exactly what the provider caches: model, tool definitions and every message up
 * to the last `cache_control: ephemeral` marker.
 */
class PromptCacheStabilityTest {
    private val assembler = PromptAssembler()
    private val client = OpenRouterClient(
        httpClient = OkHttpClient(),
        baseUrl = "https://openrouter.invalid/api/v1/".toHttpUrl(),
        apiKeyProvider = OpenRouterApiKeyProvider { "test-key" },
    )

    private val settings = testSettings(
        tools = ToolAccessSettings(
            crossChatSearch = true,
            imageSending = true,
            voiceNotes = true,
        ),
    )

    @Test
    fun `an identical turn produces an identical prompt`() {
        val first = fingerprint(turn())
        val second = fingerprint(turn())

        assertEquals(first, second)
        assertNotNull(first.prefixHash)
        assertTrue(first.prefixCharacters > 0)
    }

    @Test
    fun `a ticking clock never moves the prefix`() {
        val first = fingerprint(turn(now = Instant.parse("2026-08-02T09:00:00Z")))
        val second = fingerprint(turn(now = Instant.parse("2026-08-02T23:59:00Z")))

        assertEquals(first.prefixHash, second.prefixHash)
        assertEquals(first.prefixCharacters, second.prefixCharacters)
        // The clock still reaches the model — it just lives behind the cache boundary.
        assertNotEquals(first.promptHash, second.promptHash)
    }

    @Test
    fun `mood and memory changes never move the prefix`() {
        val first = fingerprint(turn(mood = "ruhig", chatMemory = "Mag kurze Antworten."))
        val second = fingerprint(turn(mood = "aufgekratzt", chatMemory = "Plant eine Reise."))

        assertEquals(first.prefixHash, second.prefixHash)
        assertNotEquals(first.promptHash, second.promptHash)
    }

    @Test
    fun `a growing chat never moves the prefix`() {
        val hashes = (0..25).map { extra ->
            fingerprint(turn(history = history(20 + extra))).prefixHash
        }

        assertEquals(1, hashes.distinct().size)
    }

    /**
     * A reconnect rebuilds the turn from persisted state. As long as that state is the same, the
     * rebuilt prompt has to hash the same — otherwise every reconnect silently pays full price.
     */
    @Test
    fun `a rebuilt turn after a reconnect hits the same prefix`() {
        val before = fingerprint(turn(history = history(30)))
        val rebuilt = PromptAssembler().let { fresh ->
            client.fingerprintPrompt(
                request(
                    fresh.assemble(settings, turn(history = history(30)), ToolRegistry.allowed(settings.tools)),
                ),
            )
        }

        assertEquals(before, rebuilt)
    }

    /**
     * The rule the prefix hash cannot see. DeepSeek's chat template hoists every system message to
     * the front of the rendered prompt, so a system message holding per-turn content lands directly
     * behind the stable prefix and invalidates everything after it — while [PromptFingerprint] still
     * reports a perfectly stable prefix, because the app's own `cache_control` boundary never moved.
     *
     * Measured against deepseek/deepseek-v4-flash-0731, five-turn conversation, ~1.9k prompt tokens,
     * identical in every respect except the role of the trailing mood/clock messages:
     *
     *     system tail -> 896 cached of 1926 (46%), frozen at the end of the stable prefix
     *     user   tail -> 1792 cached of 1926 (93%)
     *
     * So: exactly one system message, and it is the cacheable prefix.
     */
    @Test
    fun `the prompt carries no system message behind the cacheable prefix`() {
        val bundle = assembler.assemble(
            settings,
            turn(
                history = history(20),
                chatMemory = "Mag kurze Antworten.",
                mood = "ruhig",
            ),
            ToolRegistry.allowed(settings.tools),
        )

        assertEquals(listOf(ChatRole.SYSTEM), bundle.cacheablePrefix.map(AiMessage::role))
        assertEquals(emptyList<ChatRole>(), bundle.volatileTail.map(AiMessage::role).filter { it == ChatRole.SYSTEM })
    }

    /**
     * What the provider actually matches is the longest common message prefix between two calls.
     * A turn that grows the chat by one exchange must therefore leave every message in front of the
     * live block untouched — otherwise the whole history is re-billed on every reply.
     */
    @Test
    fun `a following turn shares every message in front of the live block`() {
        val before = assembler.assemble(
            settings,
            turn(history = history(20), chatMemory = "Mag kurze Antworten."),
            ToolRegistry.allowed(settings.tools),
        ).messages
        val after = assembler.assemble(
            settings,
            turn(
                history = history(20) + listOf(
                    HistoryMessage(ChatRole.USER, "Was denkst du?"),
                    HistoryMessage(ChatRole.ASSISTANT, "Nicht viel."),
                ),
                chatMemory = "Mag kurze Antworten.",
                now = Instant.parse("2026-08-02T12:07:00Z"),
            ),
            ToolRegistry.allowed(settings.tools),
        ).messages

        val shared = before.zip(after).takeWhile { (a, b) -> a == b }.size
        // The prior latest message is now part of the growing chat prefix. Only its old live tail
        // differs because the confirmed assistant response is inserted directly in front of it.
        assertEquals(before.size - 1, shared)
    }

    /**
     * One inbound message can now cost more than one model call: a voice note whose TTS was refused
     * is answered again as text, and a visible send (voice note, image) is continued in the same
     * turn. Those extra calls are only affordable if they ride the first call's cache entry, so what
     * they may do is strictly bounded — append, never edit.
     *
     * The retry after a refused TTS is the strictest case: same settings, same clock, same chat, one
     * extra message at the very end.
     */
    @Test
    fun `a voice-to-text retry appends exactly one message and touches nothing else`() {
        val original = turn(history = history(20), chatMemory = "Mag kurze Antworten.")
        val first = assembler.assemble(settings, original, ToolRegistry.allowed(settings.tools))
        val retry = assembler.assemble(
            settings,
            original.copy(trailingDirective = PromptLibrary.voiceFallbackDirective()),
            ToolRegistry.allowed(settings.tools),
        )

        assertEquals(first.messages, retry.messages.dropLast(1))
        assertEquals(
            PromptLibrary.voiceFallbackDirective(),
            retry.messages.last().textOrNull(),
        )
        assertEquals(ChatRole.USER, retry.messages.last().role)
        assertEquals(
            client.fingerprintPrompt(request(first)).prefixHash,
            client.fingerprintPrompt(request(retry)).prefixHash,
        )
    }

    /**
     * A confirmed visible send is inserted in front of the live block, not behind it, so the
     * follow-up shares everything from the system prompt through the inbound message. Only the small
     * live block is re-billed.
     */
    @Test
    fun `a confirmed visible send continues the same prompt in front of the live block`() {
        val original = turn(history = history(20), chatMemory = "Mag kurze Antworten.")
        val first = assembler.assemble(settings, original, ToolRegistry.allowed(settings.tools))
        val followUp = assembler.assemble(
            settings,
            original.copy(
                followUpHistory = listOf(
                    HistoryMessage(ChatRole.ASSISTANT, "You sent a voice note: alles gut bei dir?"),
                ),
            ),
            ToolRegistry.allowed(settings.tools),
        )

        val shared = first.messages.zip(followUp.messages).takeWhile { (a, b) -> a == b }.size
        // Everything except the live block, which the appended assistant message pushes back by one.
        assertEquals(first.messages.size - 1, shared)
        assertEquals(first.messages.dropLast(1), followUp.messages.dropLast(2))
        assertEquals(
            client.fingerprintPrompt(request(first)).prefixHash,
            client.fingerprintPrompt(request(followUp)).prefixHash,
        )
    }

    /**
     * The full sequence the user actually hits: voice note, its TTS refused, a text retry, and then
     * the visible-send follow-up on top. Whatever the model does inside one turn, the system prompt
     * and the whole chat in front of it must not move once.
     */
    @Test
    fun `every call inside one turn keeps the same prompt up to the inbound message`() {
        val original = turn(history = history(20), chatMemory = "Mag kurze Antworten.")
        val calls = listOf(
            original,
            original.copy(trailingDirective = PromptLibrary.voiceFallbackDirective()),
            original.copy(
                followUpHistory = listOf(HistoryMessage(ChatRole.ASSISTANT, "hab dir was geschickt")),
            ),
            original.copy(
                followUpHistory = listOf(
                    HistoryMessage(ChatRole.ASSISTANT, "You sent an image: strand.jpg · Sonnenuntergang"),
                ),
                trailingDirective = PromptLibrary.voiceFallbackDirective(),
            ),
        ).map { assembler.assemble(settings, it, ToolRegistry.allowed(settings.tools)) }

        // The chat prefix ends at the inbound message; the live block and anything behind it is the
        // only part any of these calls is allowed to differ in.
        val chatPrefix = calls.first().messages.dropLast(1)
        assertTrue(chatPrefix.size > 20)
        calls.forEach { assertEquals(chatPrefix, it.messages.take(chatPrefix.size)) }
        assertEquals(1, calls.map { client.fingerprintPrompt(request(it)).prefixHash }.distinct().size)
        // And the directive is genuinely last — a section order that put it anywhere else would
        // silently move the cache boundary.
        assertEquals(
            PromptLayer.TRAILING_DIRECTIVE,
            calls.last().sections.last().layer,
        )
    }

    /**
     * The retries the orchestrator drives itself. A rejected candidate and an empty completion are
     * both steered with a nudge, and both used to send it as a SYSTEM message — which DeepSeek's
     * template hoists to the very front, so the retry re-bills the system blocks *and* the whole
     * history. They are the most expensive calls in the turn: a regeneration only ever happens after
     * a paid generation plus a paid verification.
     */
    @Test
    fun `every retry the orchestrator drives appends behind the prompt instead of in front of it`() =
        kotlinx.coroutines.runBlocking {
            val sent = mutableListOf<List<AiMessage>>()
            val completions = ArrayDeque(
                listOf(
                    // Rejected, then an empty answer on the regeneration, then a usable one.
                    CompletionResult(content = "unsicher"),
                    CompletionResult(content = ""),
                    CompletionResult(content = "so besser?"),
                ),
            )
            var verdicts = 0
            val orchestrator = AiOrchestrator(
                completionGateway = { request ->
                    sent += request.messages.toList()
                    completions.removeFirst()
                },
                readOnlyToolExecutor = { _, _ -> ToolExecutionResult("{}") },
                actionCommitter = { _, _ -> emptyList() },
                verifier = { _, _ ->
                    verdicts += 1
                    VerificationDecision(allowCommit = verdicts > 1, reasonCode = "test")
                },
            )

            val retrySettings = settings.copy(
                verification = VerificationSettings(enabled = true, maxRegenerations = 1),
            )
            orchestrator.runTurn(
                settings = retrySettings,
                turn = turn(history = history(20), chatMemory = "Mag kurze Antworten."),
                toolContext = ToolExecutionContext(conversationKey = "chat-1"),
            )

            assertEquals(3, sent.size)
            sent.forEach { messages ->
                // Exactly one system message, and it is the first: anything else means a nudge
                // landed in front of the cached prefix.
                assertEquals(listOf(0), messages.indices.filter { messages[it].role == ChatRole.SYSTEM })
            }
            // Append-only: each call is the previous one plus its nudge, nothing shifted.
            assertEquals(sent[0], sent[1].dropLast(1))
            assertEquals(sent[1], sent[2].dropLast(1))
            assertEquals(
                listOf(
                    PromptLibrary.regenerateAfterRejectionDirective("test"),
                    PromptLibrary.emptyCompletionRetryDirective(),
                ),
                listOf(sent[1], sent[2]).map { it.last().textOrNull() },
            )
            assertEquals(
                1,
                sent.map { client.fingerprintPrompt(request(it, retrySettings)).prefixHash }
                    .distinct()
                    .size,
            )
        }

    @Test
    fun `a changed tool set is expected to break the prefix`() {
        val narrow = testSettings(tools = ToolAccessSettings(crossChatSearch = false))
        val wide = testSettings(tools = ToolAccessSettings(crossChatSearch = true, imageSending = true))

        val first = client.fingerprintPrompt(
            request(assembler.assemble(narrow, turn(), ToolRegistry.allowed(narrow.tools)), narrow),
        )
        val second = client.fingerprintPrompt(
            request(assembler.assemble(wide, turn(), ToolRegistry.allowed(wide.tools)), wide),
        )

        // Tools sit in front of the messages on the wire, so this *must* be visible as a break —
        // a fingerprint that hid it would report a cache hit the provider never gives.
        assertNotEquals(first.prefixHash, second.prefixHash)
    }

    /**
     * The end-to-end version of the anchoring rule: as the chat grows message by message, the
     * rendered history block must stay stable for a whole quantization step instead of dropping its
     * oldest line every turn.
     */
    @Test
    fun `the anchored window keeps the rendered history stable while the chat grows`() {
        val limit = 30
        val step = 40
        val anchored = settings.copy(
            context = ContextSettings(historyLimit = limit, historyWindowSlack = step),
        )

        var anchor: String? = null
        val renderedBlocks = mutableListOf<List<String>>()
        for (total in 31..60) {
            val ids = (0 until minOf(total, limit + step)).map { "m${total - it}" }
            val decision = HistoryWindowAnchor.resolve(anchor, ids, limit)
            anchor = decision.anchorMessageId
            val window = (1..total).map { "msg-$it" }.takeLast(decision.size)
            val bundle = assembler.assemble(
                anchored,
                turn(history = window.map { HistoryMessage(ChatRole.USER, it) }),
                emptyList(),
            )
            renderedBlocks += bundle.messages.mapNotNull(AiMessage::textOrNull).filter { it.startsWith("msg-") }
        }

        // Same oldest line for the whole run: the anchor was pinned once and never re-cut.
        assertEquals(1, renderedBlocks.map { it.first() }.distinct().size)
        // The block still grows at the newest end, so nothing is lost from the conversation.
        assertEquals(renderedBlocks.first().size + renderedBlocks.size - 1, renderedBlocks.last().size)
    }

    /** A sliding window is the failure this replaces — kept as an explicit contrast. */
    @Test
    fun `a hard sliding window would re-cut the history on every turn`() {
        val sliding = settings.copy(context = ContextSettings(historyLimit = 30, historyWindowSlack = 0))

        val firsts = (31..60).map { total ->
            val bundle = assembler.assemble(
                sliding,
                turn(history = (1..total).map { HistoryMessage(ChatRole.USER, "msg-$it") }),
                emptyList(),
            )
            bundle.messages.mapNotNull(AiMessage::textOrNull).first { it.startsWith("msg-") }
        }

        assertEquals(30, firsts.distinct().size)
    }

    private fun fingerprint(context: TurnContext): PromptFingerprint =
        client.fingerprintPrompt(
            request(assembler.assemble(settings, context, ToolRegistry.allowed(settings.tools))),
        )

    private fun request(
        bundle: PromptBundle,
        used: ResolvedTurnSettings = settings,
    ) = request(bundle.messages, used)

    private fun request(
        messages: List<AiMessage>,
        used: ResolvedTurnSettings = settings,
    ) = ChatCompletionRequest(
        model = used.model(ModelRole.MAIN),
        messages = messages,
        tools = ToolRegistry.allowed(used.tools),
        stream = false,
    )

    private fun history(count: Int): List<HistoryMessage> =
        (1..count).map { HistoryMessage(if (it % 2 == 0) ChatRole.ASSISTANT else ChatRole.USER, "msg-$it") }

    private fun turn(
        message: String = "Was denkst du?",
        history: List<HistoryMessage> = emptyList(),
        mood: String? = "ruhig",
        chatMemory: String? = null,
        now: Instant = Instant.parse("2026-08-02T12:00:00Z"),
    ) = TurnContext(
        latestMessage = message,
        persona = testTurn().persona,
        history = history,
        chatMemory = chatMemory,
        mood = mood,
        now = now,
        zoneId = ZoneId.of("Europe/Berlin"),
    )
}
