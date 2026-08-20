package de.totec.doppel.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the two failure modes that made the bot go quiet without a trace:
 * a verification that could not produce a verdict, and a provider call that returned nothing.
 *
 * Both used to end the turn in silence. The contract now is: the reply survives an *infrastructure*
 * failure, a real rejection still blocks, and either way the reason is recorded.
 */
class VerificationBudgetAndEmptyReplyTest {
    // -- P1: budget ------------------------------------------------------------------------------

    @Test
    fun `a budget below the verdict floor is rejected at construction`() {
        val failure = runCatching {
            VerificationSettings(maxTokens = VerificationSettings.MINIMUM_VERDICT_TOKENS - 1)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    /** The request carries one valid OpenRouter reasoning control plus the configured output cap. */
    @Test
    fun `the verifier request carries the configured budget and reasoning effort`() = runBlocking {
        var seen: ChatCompletionRequest? = null
        val verifier = OpenRouterReplyVerifier(
            gateway = ChatCompletionGateway { request ->
                seen = request
                CompletionResult(content = """{"allow":true,"reason":"ok"}""")
            },
        )

        val decision = verifier.verify(
            candidate(),
            testSettings(
                verification = VerificationSettings(
                    enabled = true,
                    maxTokens = 800,
                    reasoningEffort = ReasoningEffort.LOW,
                ),
            ),
        )

        assertTrue(decision.allowCommit)
        assertEquals("ok", decision.reasonCode)
        assertEquals(800, seen?.sampling?.maxTokens)
        assertEquals(ReasoningEffort.LOW, seen?.sampling?.reasoningEffort)
        assertNull(seen?.sampling?.reasoningMaxTokens)
        assertEquals("verify", seen?.requestTag)
    }

    // -- P1: unusable verdicts -------------------------------------------------------------------

    @Test
    fun `an empty verdict lets the reply through and names the cause`() = runBlocking {
        val decision = verdictFor(CompletionResult(content = "   ", finishReason = "length"))

        assertTrue(decision.allowCommit)
        assertEquals("empty_verifier_response", decision.reasonCode)
        assertEquals(VerificationFallback.POLICY_FAIL_OPEN, decision.fallback?.policy)
        assertNotNull(decision.fallback?.detail)
    }

    @Test
    fun `an unparseable verdict lets the reply through and names the cause`() = runBlocking {
        val decision = verdictFor(CompletionResult(content = "definitely not json"))

        assertTrue(decision.allowCommit)
        assertEquals("invalid_verifier_response", decision.reasonCode)
    }

    @Test
    fun `a verdict without the allow field lets the reply through and names the cause`() = runBlocking {
        val decision = verdictFor(CompletionResult(content = """{"reason":"unsure"}"""))

        assertTrue(decision.allowCommit)
        assertEquals("verifier_verdict_missing", decision.reasonCode)
    }

    @Test
    fun `fail closed turns an unusable verdict back into a block`() = runBlocking {
        val decision = verdictFor(
            CompletionResult(content = ""),
            VerificationSettings(enabled = true, failClosed = true),
        )

        assertFalse(decision.allowCommit)
        assertEquals(VerificationFallback.POLICY_FAIL_CLOSED, decision.fallback?.policy)
    }

    /** Fail-open must not extend to a verifier that actually looked and said no. */
    @Test
    fun `a real rejection still blocks and carries no fallback`() = runBlocking {
        val decision = verdictFor(CompletionResult(content = """{"allow":false,"reason":"unsafe"}"""))

        assertFalse(decision.allowCommit)
        assertEquals("unsafe", decision.reasonCode)
        assertNull(decision.fallback)
    }

    @Test
    fun `the fallback detail carries provider metadata only`() = runBlocking {
        val decision = verdictFor(
            CompletionResult(
                content = "",
                usage = TokenUsage(completionTokens = 512),
                finishReason = "length",
            ),
        )

        val detail = requireNotNull(decision.fallback?.detail)
        assertEquals("finish=length budget=512 completion=512 chars=0", detail)
        // The candidate text is the one thing that must never reach the operator log.
        assertFalse(detail.contains(CANDIDATE_TEXT))
    }

    // -- P1: what a blocked candidate tells the next one -----------------------------------------

    /**
     * A retry that is told only "no" pays a full generation plus a full check to walk into the same
     * wall. The verdict has to travel — and it travels inside the trailing USER nudge, so the cached
     * prefix in front of it is untouched.
     */
    @Test
    fun `the regeneration nudge names the verdict that blocked the previous try`() = runBlocking {
        val sent = mutableListOf<List<AiMessage>>()
        var verdicts = 0
        val orchestrator = AiOrchestrator(
            completionGateway = { request ->
                sent += request.messages.toList()
                CompletionResult(content = "Alles gut.")
            },
            readOnlyToolExecutor = { _, _ -> ToolExecutionResult("{}") },
            actionCommitter = { _, _ -> emptyList() },
            verifier = { _, _ ->
                verdicts += 1
                VerificationDecision(allowCommit = verdicts > 1, reasonCode = "ai_disclosure")
            },
        )

        orchestrator.runTurn(
            settings = testSettings(
                verification = VerificationSettings(enabled = true, maxRegenerations = 1),
            ),
            turn = testTurn(),
            toolContext = context,
        )

        assertEquals(2, sent.size)
        val nudge = requireNotNull(sent[1].last().textOrNull())
        assertTrue(nudge.contains("ai disclosure"))
        assertEquals(ChatRole.USER, sent[1].last().role)
    }

    /** Only a verdict the checker wrote itself carries information; a broken checker carries none. */
    @Test
    fun `an infrastructure failure is never quoted back to the writer`() {
        val broken = VerificationSettings(enabled = true, failClosed = true)
            .onUnusableVerdict("verifier_failed")

        assertNull(broken.regenerationVerdict())
        assertNull(VerificationDecision(false, "rejected").regenerationVerdict())
        assertEquals("sexual content", VerificationDecision(false, "sexual_content").regenerationVerdict())
    }

    // -- P4: empty model answers -----------------------------------------------------------------

    @Test
    fun `an empty completion is retried once and the retry answers`() = runBlocking {
        val completions = ArrayDeque(
            listOf(
                CompletionResult(content = ""),
                CompletionResult(content = "Da bin ich wieder."),
            ),
        )
        var calls = 0
        val orchestrator = orchestrator { completions.removeFirst().also { calls += 1 } }

        val result = orchestrator.runTurn(testSettings(), testTurn(), context)

        assertEquals(2, calls)
        assertEquals(1, result.emptyCompletions)
        assertEquals(listOf("Da bin ich wieder."), result.response.bubbles)
        assertTrue(result.commitAllowed)
    }

    @Test
    fun `the empty retry nudges the model instead of resending the same prompt`() = runBlocking {
        val sent = mutableListOf<List<AiMessage>>()
        val completions = ArrayDeque(
            listOf(CompletionResult(content = ""), CompletionResult(content = "ok")),
        )
        val orchestrator = orchestrator { request ->
            sent += request.messages.toList()
            completions.removeFirst()
        }

        orchestrator.runTurn(testSettings(), testTurn(), context)

        assertEquals(2, sent.size)
        assertEquals(sent[0].size + 1, sent[1].size)
        val nudge = requireNotNull(sent[1].last().textOrNull())
        assertTrue(nudge.contains("no output at all"))
    }

    @Test
    fun `a length-truncated completion is discarded and retried once`() = runBlocking {
        val sent = mutableListOf<List<AiMessage>>()
        val completions =
            ArrayDeque(
                listOf(
                    CompletionResult(content = "cut off partial", finishReason = "length"),
                    CompletionResult(content = "fertig"),
                ),
            )
        val orchestrator = orchestrator { request ->
            sent += request.messages.toList()
            completions.removeFirst()
        }

        val result = orchestrator.runTurn(testSettings(), testTurn(), context)

        assertEquals(listOf("fertig"), result.response.bubbles)
        assertEquals(2, sent.size)
        assertTrue(sent.last().last().textOrNull().orEmpty().contains("hit the output limit"))
    }

    @Test
    fun `confirmed writing retries no reply once with a cache-safe trailing nudge`() = runBlocking {
        val sent = mutableListOf<List<AiMessage>>()
        val completions =
            ArrayDeque(
                listOf(
                    CompletionResult(content = "[no reply]"),
                    CompletionResult(content = "ich wollt noch fragen wie es lief"),
                ),
            )
        val orchestrator = orchestrator { request ->
            sent += request.messages.toList()
            completions.removeFirst()
        }

        val result =
            orchestrator.runTurn(
                testSettings(),
                testTurn().copy(trailingDirective = "You decided to write now."),
                context,
                retryConfirmedWritingNoReply = true,
            )

        assertEquals(listOf("ich wollt noch fragen wie es lief"), result.response.bubbles)
        assertEquals(2, sent.size)
        assertTrue(sent.last().last().textOrNull().orEmpty().contains("confirmed writing turn"))
    }

    @Test
    fun `ordinary trailing retries still accept no reply without another paid call`() = runBlocking {
        var calls = 0
        val orchestrator = orchestrator {
            calls += 1
            CompletionResult(content = "[no reply]")
        }

        val result =
            orchestrator.runTurn(
                testSettings(),
                testTurn().copy(trailingDirective = "Rewrite a failed voice note as text."),
                context,
                acceptNoReply = true,
            )

        assertTrue(result.response.noReply)
        assertEquals(1, calls)
    }

    /** One retry, not a paid loop: a provider that only ever returns nothing must stop costing. */
    @Test
    fun `a permanently empty provider retries exactly once`() = runBlocking {
        var calls = 0
        val orchestrator = orchestrator {
            calls += 1
            CompletionResult(content = "")
        }

        val result = orchestrator.runTurn(testSettings(), testTurn(), context)

        assertEquals(2, calls)
        assertEquals(1, result.emptyCompletions)
        assertTrue(result.response.bubbles.all(String::isBlank))
    }

    private suspend fun verdictFor(
        result: CompletionResult,
        verification: VerificationSettings = VerificationSettings(enabled = true),
    ): VerificationDecision =
        OpenRouterReplyVerifier(gateway = ChatCompletionGateway { result })
            .verify(candidate(), testSettings(verification = verification))

    private fun candidate(): VerificationCandidate =
        VerificationCandidate(
            response = ParsedAssistantResponse(
                noReply = false,
                reaction = null,
                quote = null,
                bubbles = listOf(CANDIDATE_TEXT),
                fallbackToolCalls = emptyList(),
            ),
            pendingActions = emptyList(),
        )

    private fun orchestrator(gateway: ChatCompletionGateway) =
        AiOrchestrator(
            completionGateway = gateway,
            readOnlyToolExecutor = ReadOnlyToolExecutor { _, _ -> ToolExecutionResult("{}") },
            actionCommitter = PendingActionCommitter { _, _ -> emptyList() },
        )

    private val context = ToolExecutionContext(
        conversationKey = "chat-1",
        currentSender = "sender-1",
    )

    private companion object {
        const val CANDIDATE_TEXT = "geheime Antwort an den Nutzer"
    }
}
