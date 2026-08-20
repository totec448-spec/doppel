package de.totec.doppel.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class VerificationCandidate(
    val response: ParsedAssistantResponse,
    val pendingActions: List<PendingAction>,
)

data class VerificationDecision(
    val allowCommit: Boolean,
    val reasonCode: String,
    /**
     * One dense sentence from the check model naming what it objected to, when it wrote one.
     *
     * The reason code alone is a category — `markdown`, `ai_disclosure` — and a category is enough
     * to log but not always enough to repair. This is asked for only on a rejection, so the calls
     * that pass (nearly all of them) pay nothing for it.
     */
    val explanation: String? = null,
    /**
     * Present only when no usable verdict was produced and a fallback policy decided the outcome.
     * A failing safety check has to stay visible: silently dropping the reply is exactly the bug
     * this records.
     */
    val fallback: VerificationFallback? = null,
    /**
     * What the check itself cost, when it actually reached a provider.
     *
     * The verifier runs on nearly every reply and its tokens were simply dropped, so the turn's
     * recorded usage described only half of what was billed — and the half that was missing is the
     * one that scales with the number of regenerations. A decision that never reached a provider
     * (no verifier wired, verification disabled) leaves this null, which is the honest answer.
     */
    val usage: TokenUsage? = null,
)

/**
 * Why a verification produced no verdict and what was done about it.
 *
 * [detail] carries only provider metadata (finish reason, token counts) — never candidate or
 * prompt text, because this string reaches the production activity log.
 */
data class VerificationFallback(
    val cause: String,
    val policy: String,
    val detail: String? = null,
) {
    companion object {
        const val POLICY_FAIL_OPEN = "fail_open"
        const val POLICY_FAIL_CLOSED = "fail_closed"
    }
}

/**
 * What a rejected candidate can tell the next attempt, or `null` when that is nothing.
 *
 * Only a verdict the check model wrote itself carries information the writer can act on. Every
 * [VerificationFallback] code names a transport or parser fault instead — "the verifier timed out"
 * says nothing about the reply and would only invite it to apologise for a problem the other person
 * never saw. `rejected` is the placeholder used when a verdict came back without a reason at all,
 * so it is equally empty.
 */
fun VerificationDecision.regenerationVerdict(): String? = when {
    allowCommit || fallback != null -> null
    reasonCode == "no_reply_with_actions" -> null
    explanation != null -> explanation
    reasonCode == "rejected" || reasonCode == "not_verified" -> null
    else -> reasonCode.replace('_', ' ')
}

/**
 * Turns "the check could not run" into a decision.
 *
 * This is deliberately *not* the same thing as "the check said no": a rejected candidate is a
 * safety signal, while a timed-out, empty or malformed verdict is an infrastructure fault. Treating
 * the second like the first is what made an unreachable verifier silence every reply.
 */
fun VerificationSettings.onUnusableVerdict(
    cause: String,
    detail: String? = null,
): VerificationDecision =
    VerificationDecision(
        allowCommit = !failClosed,
        reasonCode = cause,
        fallback = VerificationFallback(
            cause = cause,
            policy = if (failClosed) {
                VerificationFallback.POLICY_FAIL_CLOSED
            } else {
                VerificationFallback.POLICY_FAIL_OPEN
            },
            detail = detail,
        ),
    )

fun interface ReplyVerifier {
    suspend fun verify(
        candidate: VerificationCandidate,
        settings: ResolvedTurnSettings,
    ): VerificationDecision
}

data class OrchestrationResult(
    val response: ParsedAssistantResponse,
    val pendingActions: List<PendingAction>,
    val commitReceipts: List<ActionCommitReceipt>,
    val verification: VerificationDecision,
    val commitAllowed: Boolean,
    val modelCalls: Int,
    /**
     * How many provider calls came back with neither text nor a tool call. Non-zero means the turn
     * paid for at least one wasted call and had to nudge the model — worth surfacing rather than
     * hiding behind the retry.
     */
    val emptyCompletions: Int = 0,
    val usage: TokenUsage?,
    /**
     * The exact prompt this turn sent, so a follow-up background request can
     * reuse it verbatim and land on the provider's prompt cache instead of
     * paying full price for a second copy of the same context.
     */
    val promptMessages: List<AiMessage> = emptyList(),
    val promptTools: List<ToolDefinition> = emptyList(),
    val promptSampling: SamplingSettings = SamplingSettings(),
)

class AiOrchestrationException(
    val reasonCode: String,
) : IllegalStateException("AI orchestration failed: $reasonCode")

/**
 * What the model is doing, reported while it is doing it.
 *
 * A turn is one HTTP call in the good case and a loop of them once tools are involved, and from
 * the outside all of it is a single silence of unknown length. The activity log gets one summary
 * row afterwards, which is the wrong tense for someone watching a chat right now.
 *
 * What this can honestly report is what the model *did*: which call it is on, which tool it
 * reached for, and what came back. It cannot report what the model thought — the chain of thought
 * is billed as output but never returned in a field this app reads, so a "reasoning" line here
 * would be invented. Nothing in here is persisted and no implementation may block: it is called
 * from the middle of the tool loop.
 */
interface TurnObserver {
    /** A provider call is going out. [index] is 1-based within this turn's tool loop. */
    fun modelCall(index: Int, model: String) = Unit

    /** The model asked for a tool. [arguments] is the raw JSON, already short. */
    fun toolCall(name: String, arguments: String) = Unit

    /** That tool came back. [detail] is a status code, never the payload. */
    fun toolResult(name: String, ok: Boolean, detail: String) = Unit

    /** Something went wrong inside the loop and the turn is going to feel it. */
    fun problem(text: String) = Unit

    /**
     * The safety check is going out to [model], or nothing is: `null` says no check model stands
     * between this answer and the chat. Silence about it was indistinguishable from a stalled call.
     */
    fun checking(model: String?) = Unit

    /** The check came back, with the verdict's own reason code and its sentence, if it wrote one. */
    fun checked(allowed: Boolean, reason: String, detail: String? = null) = Unit

    companion object {
        /** The default for every call site that has no UI attached — tests, background work. */
        val None: TurnObserver = object : TurnObserver {}
    }
}

/**
 * Runs only read-only tools immediately. Mutating tools are represented as pending values,
 * verified as a batch, and handed to the injected committer only after that gate succeeds.
 */
class AiOrchestrator(
    private val completionGateway: ChatCompletionGateway,
    private val readOnlyToolExecutor: ReadOnlyToolExecutor,
    private val actionCommitter: PendingActionCommitter,
    private val verifier: ReplyVerifier? = null,
    private val promptAssembler: PromptAssembler = PromptAssembler(),
    private val responseParser: ResponseProtocolParser = ResponseProtocolParser(),
    /** Stop after a visible media action so WhatsApp can send and persist it before continuation. */
    private val deferVisibleActionFollowUp: Boolean = false,
    private val observer: TurnObserver = TurnObserver.None,
) {
    suspend fun runTurn(
        settings: ResolvedTurnSettings,
        turn: TurnContext,
        toolContext: ToolExecutionContext,
        /** Parser-only override; it does not alter the assembled prompt or system message. */
        acceptNoReply: Boolean = false,
        /** One reconsideration is allowed only for an explicit scheduled/operator writing turn. */
        retryConfirmedWritingNoReply: Boolean = false,
        /** Diagnostics-only request purpose; it is not encoded into the model prompt. */
        requestTag: String = "turn",
    ): OrchestrationResult {
        val definitions = ToolRegistry.allowed(settings.tools)
        val prompt = promptAssembler.assemble(settings, turn, definitions)
        var totalCalls = 0
        var emptyCompletions = 0
        val usage = UsageAccumulator()
        var lastCandidate: GenerationCandidate? = null
        var lastDecision = VerificationDecision(
            allowCommit = false,
            reasonCode = "not_verified",
        )

        val generationAttempts = if (settings.verification.enabled) {
            settings.verification.maxRegenerations + 1
        } else {
            1
        }
        repeat(generationAttempts) { generationAttempt ->
            val messages = prompt.messages.toMutableList()
            if (generationAttempt > 0) {
                // The rejected candidate's tool calls were never committed, so the once-per-turn
                // picture it asked for was never taken. Without this the regeneration is refused
                // its own image and the reply goes out without the picture it was written around.
                toolContext.session.clear(ToolRegistry.GENERATED_IMAGE)
                // Same for the one armed time: nothing was armed, so the rewrite must still be
                // allowed to set it. Otherwise a rejected draft silently costs the turn its
                // follow-up and the reply promises a time nothing will keep.
                toolContext.session.clear(ToolRegistry.SCHEDULE_FOLLOWUP)
                // Strictly last and in the USER role, exactly like a trailing directive: a SYSTEM
                // message here would be hoisted to the front of the prompt by DeepSeek's chat
                // template and invalidate the entire cached prefix, so every regeneration would
                // re-bill the system blocks and the full history. Carrying the verdict costs a
                // handful of tokens inside that same trailing message and leaves the prefix alone.
                messages += AiMessage.text(
                    ChatRole.USER,
                    PromptLibrary.regenerateAfterRejectionDirective(lastDecision.regenerationVerdict()),
                )
            }
            val candidate = generateCandidate(
                settings = settings,
                initialMessages = messages,
                definitions = definitions,
                toolContext = toolContext,
                usage = usage,
                acceptNoReply = acceptNoReply,
                retryConfirmedWritingNoReply = retryConfirmedWritingNoReply,
                requestTag = requestTag,
            )
            totalCalls += candidate.modelCalls
            emptyCompletions += candidate.emptyCompletions
            lastCandidate = candidate
            if (candidate.response.noReply && candidate.actions.isNotEmpty()) {
                // Silence can never smuggle a mutating tool batch. Reject before
                // verification and, crucially, before the committer sees it.
                lastDecision =
                    VerificationDecision(
                        allowCommit = false,
                        reasonCode = "no_reply_with_actions",
                    )
                return@repeat
            }
            if (candidate.response.noReply) {
                // There is no outbound text, reaction or mutation to safety-check. Sending a
                // verifier request for a pure stop token wastes a paid model call on every
                // completed continuation chain.
                return OrchestrationResult(
                    response = candidate.response,
                    pendingActions = emptyList(),
                    commitReceipts = emptyList(),
                    verification = VerificationDecision(true, "no_reply"),
                    commitAllowed = true,
                    modelCalls = totalCalls,
                    emptyCompletions = emptyCompletions,
                    usage = usage.value(),
                    promptMessages = prompt.messages,
                    promptTools = definitions,
                    promptSampling = settings.sampling,
                )
            }
            lastDecision =
                if (candidate.requiresReplyVerification()) {
                    verify(candidate, settings, usage)
                } else {
                    VerificationDecision(
                        allowCommit = true,
                        reasonCode =
                            if (settings.verification.enabled) {
                                "nothing_to_verify"
                            } else {
                                "verification_disabled"
                            },
                    )
                }
            if (!lastDecision.allowCommit && lastDecision.fallback != null) {
                // A provider/transport/parser failure is not a judgement on this candidate.
                // Regenerating it cannot repair the verifier and used to multiply one incoming text
                // into maxRegenerations + 1 expensive main-model calls. Fail-closed still blocks,
                // but does so immediately after the single candidate and single failed check.
                return OrchestrationResult(
                    response = candidate.response,
                    pendingActions = candidate.actions,
                    commitReceipts = emptyList(),
                    verification = lastDecision,
                    commitAllowed = false,
                    modelCalls = totalCalls,
                    emptyCompletions = emptyCompletions,
                    usage = usage.value(),
                    promptMessages = prompt.messages,
                    promptTools = definitions,
                    promptSampling = settings.sampling,
                )
            }
            if (lastDecision.allowCommit) {
                val receipts = if (candidate.actions.isEmpty()) {
                    emptyList()
                } else {
                    actionCommitter.commit(toolContext, candidate.actions)
                }
                val actionsCommitted =
                    receipts.size == candidate.actions.size &&
                        receipts.all(ActionCommitReceipt::committed)
                val effectiveDecision =
                    if (actionsCommitted) {
                        lastDecision
                    } else {
                        VerificationDecision(
                            allowCommit = false,
                            reasonCode = "action_commit_failed",
                        )
                    }
                return OrchestrationResult(
                    response = candidate.response,
                    pendingActions = candidate.actions,
                    commitReceipts = receipts,
                    verification = effectiveDecision,
                    commitAllowed = actionsCommitted,
                    modelCalls = totalCalls,
                    emptyCompletions = emptyCompletions,
                    usage = usage.value(),
                    promptMessages = prompt.messages,
                    promptTools = definitions,
                    promptSampling = settings.sampling,
                )
            }
        }

        val rejected = requireNotNull(lastCandidate)
        return OrchestrationResult(
            response = rejected.response,
            pendingActions = rejected.actions,
            commitReceipts = emptyList(),
            verification = lastDecision,
            commitAllowed = false,
            modelCalls = totalCalls,
            emptyCompletions = emptyCompletions,
            usage = usage.value(),
            promptMessages = prompt.messages,
            promptTools = definitions,
            promptSampling = settings.sampling,
        )
    }

    private suspend fun generateCandidate(
        settings: ResolvedTurnSettings,
        initialMessages: MutableList<AiMessage>,
        definitions: List<ToolDefinition>,
        toolContext: ToolExecutionContext,
        usage: UsageAccumulator,
        acceptNoReply: Boolean,
        retryConfirmedWritingNoReply: Boolean,
        requestTag: String,
    ): GenerationCandidate {
        val messages = initialMessages
        val pendingActions = mutableListOf<PendingAction>()
        val seenActionIds = mutableSetOf<String>()
        val seenActionKeys = mutableSetOf<String>()
        var modelCalls = 0
        var emptyCompletions = 0
        var truncatedCompletions = 0
        var directiveNoReplies = 0
        // Rounds in which the model actually *used* a tool. A provider that answered with nothing
        // used to burn one of these, so two hiccups could end a turn that had not yet asked for a
        // single lookup — the budget is for the model's decisions, not the provider's failures.
        var toolRounds = 0
        var finalRoundAnnounced = false

        // Bounded by construction: every iteration either returns, spends one of the two finite
        // budgets, or is the final tool-free call. The ceiling only exists so a future edit to
        // those rules cannot turn this into a paid infinite loop.
        val maxIterations =
            settings.toolLoopLimit +
                MAX_EMPTY_COMPLETION_RETRIES +
                MAX_TRUNCATED_COMPLETION_RETRIES +
                MAX_DIRECTIVE_NO_REPLY_RETRIES +
                2
        repeat(maxIterations) {
            val toolsExhausted = toolRounds >= settings.toolLoopLimit
            if (toolsExhausted && !finalRoundAnnounced) {
                finalRoundAnnounced = true
                observer.problem("Tool budget used up · asking for the answer now")
                // USER, not SYSTEM, for the same cache reason as everywhere else in this loop.
                messages += AiMessage.text(
                    ChatRole.USER,
                    PromptLibrary.toolBudgetExhaustedDirective(),
                )
            }
            observer.modelCall(modelCalls + 1, settings.model(ModelRole.MAIN))
            val completion = completionGateway.complete(
                ChatCompletionRequest(
                    model = settings.model(ModelRole.MAIN),
                    messages = messages,
                    // Withholding the tool definitions is what makes the final call terminal. A
                    // directive alone is a request; an empty tool list leaves answering as the only
                    // thing the model can do — and it answers with what it already gathered instead
                    // of the turn being thrown away along with everything it cost.
                    tools = if (toolsExhausted) emptyList() else definitions,
                    sampling = settings.sampling,
                    stream = settings.preferStreaming,
                    requestTag = requestTag,
                ),
            )
            modelCalls += 1
            usage.add(completion.usage)

            if (completion.finishReason.equals("length", ignoreCase = true)) {
                if (truncatedCompletions >= MAX_TRUNCATED_COMPLETION_RETRIES) {
                    throw AiOrchestrationException("completion_truncated")
                }
                truncatedCompletions += 1
                observer.problem("Output limit reached · retrying once with the full 10k budget")
                messages +=
                    AiMessage.text(
                        ChatRole.USER,
                        PromptLibrary.truncatedCompletionRetryDirective(),
                    )
                return@repeat
            }

            val parserOutput =
                if (acceptNoReply) settings.output.copy(allowNoReply = true) else settings.output
            val parsed = responseParser.parse(completion.content, parserOutput)
            val calls =
                if (toolsExhausted) {
                    emptyList()
                } else {
                    completion.toolCalls.ifEmpty { parsed.fallbackToolCalls }
                }
            if (
                completion.content.isBlank() &&
                calls.isEmpty() &&
                emptyCompletions < MAX_EMPTY_COMPLETION_RETRIES
            ) {
                // An empty completion is a provider hiccup, not an answer. Retrying once with an
                // explicit nudge is far cheaper than ending the turn in silence, and the counter
                // keeps it from becoming an unbounded paid loop.
                emptyCompletions += 1
                observer.problem("Empty completion · asking once more")
                // USER, not SYSTEM: a SYSTEM nudge is hoisted in front of the cached prefix by
                // DeepSeek's chat template, so the retry would pay for the whole prompt again.
                messages += AiMessage.text(
                    ChatRole.USER,
                    PromptLibrary.emptyCompletionRetryDirective(),
                )
                return@repeat
            }
            if (calls.isEmpty()) {
                if (
                    retryConfirmedWritingNoReply &&
                    parsed.noReply &&
                    directiveNoReplies < MAX_DIRECTIVE_NO_REPLY_RETRIES
                ) {
                    directiveNoReplies += 1
                    observer.problem("Confirmed writing turn returned no reply · asking once more")
                    messages +=
                        AiMessage.text(
                            ChatRole.USER,
                            PromptLibrary.confirmedWritingNoReplyRetryDirective(),
                        )
                    return@repeat
                }
                return GenerationCandidate(
                    response = parsed,
                    actions = pendingActions.toList(),
                    modelCalls = modelCalls,
                    emptyCompletions = emptyCompletions,
                )
            }
            messages += AiMessage(
                role = ChatRole.ASSISTANT,
                content = completion.content
                    .takeIf(String::isNotEmpty)
                    ?.let(MessageContent::Text),
                toolCalls = calls,
            )
            for (call in calls) {
                observer.toolCall(call.name, call.argumentsJson.take(MAX_OBSERVED_ARGUMENT_CHARACTERS))
                val toolResult = when (
                    val prepared = ToolRegistry.prepare(call, settings.tools, toolContext)
                ) {
                    is PreparedToolCall.ReadOnly ->
                        executeReadOnly(toolContext, prepared.call).also {
                            observer.toolResult(call.name, ok = true, detail = "answered")
                        }
                    is PreparedToolCall.Action -> {
                        val actionKey = prepared.value.dedupeKey()
                        if (
                            seenActionIds.add(prepared.value.toolCallId) &&
                            seenActionKeys.add(actionKey)
                        ) {
                            pendingActions += prepared.value
                            observer.toolResult(call.name, ok = true, detail = "accepted")
                            statusJson(
                                ok = true,
                                code = "accepted",
                                whatToDo = queuedGuidance(call.name),
                            )
                        } else {
                            observer.toolResult(call.name, ok = false, detail = "duplicate, dropped")
                            statusJson(
                                ok = false,
                                code = "duplicate_action",
                                whatToDo =
                                    "You already asked for exactly this in this turn and it is " +
                                        "still going out. Do not call the tool again; write your " +
                                        "chat message.",
                            )
                        }
                    }
                    is PreparedToolCall.Rejected -> {
                        observer.toolResult(call.name, ok = false, detail = prepared.reasonCode)
                        statusJson(
                            ok = false,
                            code = prepared.reasonCode,
                            whatToDo = ToolRegistry.guidanceFor(prepared.reasonCode),
                        )
                    }
                }
                messages += AiMessage(
                    role = ChatRole.TOOL,
                    content = MessageContent.Text(toolResult),
                    name = call.name,
                    toolCallId = call.id,
                )
            }
            toolRounds += 1
            if (
                deferVisibleActionFollowUp &&
                pendingActions.any(PendingAction::isVisibleMediaAction)
            ) {
                // Do not manufacture a follow-up from a pending tool result. The engine first
                // sends the medium, persists the confirmed assistant entry, then starts a fresh
                // call from the same assembled prompt with that entry appended before the tail.
                return GenerationCandidate(
                    response = parsed,
                    actions = pendingActions.toList(),
                    modelCalls = modelCalls,
                    emptyCompletions = emptyCompletions,
                )
            }
        }
        // Unreachable while the budgets above hold, and deliberately still an error rather than a
        // silent empty reply if some future change breaks that.
        throw AiOrchestrationException("tool_loop_limit")
    }

    private suspend fun executeReadOnly(
        context: ToolExecutionContext,
        call: AiToolCall,
    ): String = try {
        val result = readOnlyToolExecutor.execute(context, call)
        if (result.isError) {
            statusJson(ok = false, code = "lookup_failed")
        } else if (result.json.length > MAX_TOOL_RESULT_CHARACTERS) {
            statusJson(ok = false, code = "lookup_result_too_large")
        } else {
            result.json
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        statusJson(ok = false, code = "lookup_unavailable")
    }

    private suspend fun verify(
        candidate: GenerationCandidate,
        settings: ResolvedTurnSettings,
        usage: UsageAccumulator,
    ): VerificationDecision {
        val destructive = candidate.actions.any { it is PendingAction.BlockContact }
        if (!settings.verification.enabled && !destructive) {
            observer.checking(null)
            return VerificationDecision(true, "verification_disabled")
        }
        observer.checking(settings.model(ModelRole.VERIFY))
        val decision = runVerification(candidate, settings, destructive)
        // Folded in here, before anything can branch on the verdict: the check was paid for
        // regardless of what it decided, and on the regeneration path it is paid for repeatedly.
        usage.add(decision.usage)
        observer.checked(decision.allowCommit, decision.reasonCode, decision.explanation)
        return decision
    }

    /** The check itself, so that every way it can end passes the verdict back through one place. */
    private suspend fun runVerification(
        candidate: GenerationCandidate,
        settings: ResolvedTurnSettings,
        destructive: Boolean,
    ): VerificationDecision {
        val activeVerifier = verifier
            ?: return if (destructive) {
                destructiveVerificationFailure("verifier_unavailable", "no verifier wired for role VERIFY")
            } else {
                settings.verification.onUnusableVerdict(
                    cause = "verifier_unavailable",
                    detail = "no verifier wired for role VERIFY",
                )
            }
        return try {
            val decision = activeVerifier.verify(
                VerificationCandidate(
                    response = candidate.response,
                    pendingActions = candidate.actions,
                ),
                settings,
            )
            if (destructive && decision.fallback != null) {
                // Re-decided, not re-run: the provider call already happened, so its cost travels on.
                destructiveVerificationFailure(decision.reasonCode, decision.fallback.detail)
                    .copy(usage = decision.usage)
            } else {
                decision
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (destructive) {
                destructiveVerificationFailure("verifier_failed", failure.javaClass.simpleName)
            } else {
                settings.verification.onUnusableVerdict(
                    cause = "verifier_failed",
                    detail = failure.javaClass.simpleName,
                )
            }
        }
    }

    private fun destructiveVerificationFailure(cause: String, detail: String?): VerificationDecision =
        VerificationDecision(
            allowCommit = false,
            reasonCode = cause,
            fallback =
                VerificationFallback(
                    cause = cause,
                    policy = VerificationFallback.POLICY_FAIL_CLOSED,
                    detail = detail,
                ),
        )

    private fun statusJson(ok: Boolean, code: String, whatToDo: String? = null): String =
        JSONObject()
            .put("ok", ok)
            .put("status", code)
            .apply { whatToDo?.let { put("what_to_do", it) } }
            .toString()

    /**
     * What a queued side effect means for the model, in the model's own terms.
     *
     * "queued for the safety check" was true and useless: it names an internal stage and says
     * nothing about whether the picture is coming, so the model kept calling the tool again to find
     * out. Every one of those calls is a full extra turn through a paid model, and for a generated
     * image it is a paid picture too. The answer now states the outcome and the single next step.
     */
    private fun queuedGuidance(toolName: String): String = when (toolName) {
        "generate_image" ->
            "Done. The picture is being created and sent to this chat automatically. " +
                "Do not call generate_image again in this turn. " +
                "Just write your normal chat message now — and do not describe the picture in it, " +
                "the contact can see it."
        "send_image" ->
            "Done. The picture is on its way to this chat. Do not send another one in this turn. " +
                "Just write your normal chat message now."
        "send_voice_note" ->
            "Done. The voice note is being spoken and sent. Do not call this tool again in this " +
                "turn and do not repeat the same words as text."
        else -> "Done. This is committed at the end of the turn. Do not call the tool again."
    }

    private data class GenerationCandidate(
        val response: ParsedAssistantResponse,
        val actions: List<PendingAction>,
        val modelCalls: Int,
        val emptyCompletions: Int = 0,
    ) {
        /**
         * The check judges the form of visible words. A block carries none, so a block-only turn
         * used to hand the verifier a line this code invented — and get judged on that invention.
         */
        fun requiresReplyVerification(): Boolean =
            response.bubbles.any(String::isNotBlank) ||
                actions.any {
                    it is PendingAction.SendVoiceNote ||
                        (it is PendingAction.SendImage && !it.caption.isNullOrBlank()) ||
                        (it is PendingAction.GenerateImage && !it.caption.isNullOrBlank())
                }
    }

    private companion object {
        const val MAX_TOOL_RESULT_CHARACTERS = 32_000

        /** One retry: enough to ride out a provider hiccup, few enough to stay cheap. */
        const val MAX_EMPTY_COMPLETION_RETRIES = 1

        /** One fresh completion for a provider answer cut off by its output boundary. */
        const val MAX_TRUNCATED_COMPLETION_RETRIES = 1

        /** A confirmed outreach may reconsider silence once, but safety still wins after that. */
        const val MAX_DIRECTIVE_NO_REPLY_RETRIES = 1

        /**
         * A tool call's arguments go to a one-line trace, not to a log. Enough to see which
         * picture or which number it asked for, short enough that a long caption cannot push the
         * rest of the trace off the screen.
         */
        const val MAX_OBSERVED_ARGUMENT_CHARACTERS = 160
    }
}

/**
 * Optional model-backed verifier. It receives only the parsed outbound message text (multiple
 * bubbles joined by a blank line, or spoken voice text) and must return a tiny JSON decision.
 *
 * A verdict of `allow:false` blocks the reply. Anything else — empty content, truncated output,
 * unparseable JSON — is an *unusable* verdict and goes through [onUnusableVerdict], which by
 * default lets the reply through and records why.
 */
class OpenRouterReplyVerifier(
    private val gateway: ChatCompletionGateway,
) : ReplyVerifier {
    override suspend fun verify(
        candidate: VerificationCandidate,
        settings: ResolvedTurnSettings,
    ): VerificationDecision {
        // Nothing but the words the contact will actually see or hear. The prompt below promises
        // exactly that and lists bracketed metadata as a reject reason, so a label added here is a
        // reject reason we wrote ourselves: "[Generated image caption]" in front of a caption made
        // the check model block the reply, the orchestrator regenerate, and the next candidate
        // arrive with the same label in front of it — a paid loop with no way out but the
        // regeneration budget. Captions are real chat text and go in plain; a block is an action
        // with no form to judge and stays out entirely.
        val message =
            buildList {
                addAll(candidate.response.bubbles.filter(String::isNotBlank))
                candidate.pendingActions
                    .filterIsInstance<PendingAction.SendVoiceNote>()
                    .mapTo(this) { it.spokenText }
                candidate.pendingActions
                    .filterIsInstance<PendingAction.SendImage>()
                    .mapNotNullTo(this) { image -> image.caption?.takeIf(String::isNotBlank) }
                candidate.pendingActions
                    .filterIsInstance<PendingAction.GenerateImage>()
                    .mapNotNullTo(this) { image -> image.caption?.takeIf(String::isNotBlank) }
            }.joinToString("\n\n")
        val verification = settings.verification
        val result = gateway.complete(
            ChatCompletionRequest(
                model = settings.model(ModelRole.VERIFY),
                messages = listOf(
                    AiMessage.text(ChatRole.SYSTEM, VERIFY_SYSTEM_PROMPT),
                    AiMessage.text(ChatRole.USER, message),
                ),
                sampling = SamplingSettings(
                    temperature = 0.0,
                    topP = 1.0,
                    maxTokens = verification.maxTokens,
                    reasoningEffort = verification.reasoningEffort,
                ),
                // Streamed for the trace, not for the verdict: the verdict is tiny JSON either way,
                // but a non-streamed call hands its reasoning over in one block once the answer is
                // already complete. The chat then sits on "checking" in silence and dumps the whole
                // thought at the end. The live sink is already installed around this call.
                stream = true,
                requestTag = "verify",
            ),
        )
        // The call is billed the moment it returns, whatever the verdict turns out to be — an
        // unusable one most of all, since a reasoning model that burned its whole budget without
        // emitting JSON is the expensive case, not the free one.
        val body = stripCodeFence(result.content)
        if (body.isBlank()) {
            // Typically a reasoning model that spent the whole budget before emitting the verdict.
            return verification.onUnusableVerdict(
                cause = "empty_verifier_response",
                detail = result.diagnosticSummary(verification.maxTokens),
            ).copy(usage = result.usage)
        }
        val root = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return verification.onUnusableVerdict(
                cause = "invalid_verifier_response",
                detail = result.diagnosticSummary(verification.maxTokens),
            ).copy(usage = result.usage)
        }
        if (!root.has("allow")) {
            return verification.onUnusableVerdict(
                cause = "verifier_verdict_missing",
                detail = result.diagnosticSummary(verification.maxTokens),
            ).copy(usage = result.usage)
        }
        val allow = root.optBoolean("allow", false)
        val reason = root.optString("reason")
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .take(48)
            .ifBlank { if (allow) "approved" else "rejected" }
        // Free-form text written by a model, and it is appended to the next prompt, so it is flattened
        // to a single short line first: a newline or a stray header in there would read as structure
        // in a place where only one sentence belongs.
        val detail = root.optString("detail")
            .replace(NEWLINES, " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(MAX_VERDICT_DETAIL_CHARS)
            .takeIf { it.isNotEmpty() && !allow }
        return VerificationDecision(allow, reason, explanation = detail, usage = result.usage)
    }

    /**
     * Provider metadata only — finish reason and token counts. Never the candidate or the verdict
     * body, because this ends up in the production activity log.
     */
    private fun CompletionResult.diagnosticSummary(budget: Int): String = buildString {
        append("finish=").append(finishReason ?: "unknown")
        append(" budget=").append(budget)
        usage?.completionTokens?.let { append(" completion=").append(it) }
        append(" chars=").append(content.length)
    }

    private companion object {
        private val NEWLINES = Regex("[\\r\\n]+")

        /** One sentence, and the prompt asks for twenty words. Anything past this is not one. */
        const val MAX_VERDICT_DETAIL_CHARS = 200

        /**
         * Ported from the reference build's `VERIFY_SYSTEM_PROMPT`, with its single-word verdict
         * swapped for this build's JSON contract.
         *
         * The previous prompt asked for "safe, relevant, and consistent", which the model reads as
         * a content-policy check — it passed exactly the replies the operator complained about
         * (quoted-back phrases, emoji clusters, essay-length answers to a two-word ping). The
         * criteria below judge form only, which is what a human-passing WhatsApp reply needs.
         */
        val VERIFY_SYSTEM_PROMPT =
            """
            You are a restrained final quality check for WhatsApp messages.

            Your job: decide whether the message reads as typed by a real person in a WhatsApp
            chat, or whether it gives itself away as AI.

            The messages are usually German. Judge them as they are; the language is never a
            reason to reject.

            OUTPUT RULE (the calling code depends on it):
            - Answer with nothing but JSON: {"allow":true,"reason":"shortcode"}
            - allow=true means clean and human, it may go out as it is.
            - allow=false is only for unmistakable, severe AI tells or broken visible output.
            - reason is a short code without spaces, e.g. metadata, markdown, ai_disclosure.
            - On allow=false add one more field: {"allow":false,"reason":"shortcode","detail":"..."}
            - detail is ONE dense sentence, max 20 words, naming the concrete tell and where it is.
              It is read by the writer, who then rewrites the message. No greeting, no hedging, no
              advice on style, no repeating the message back.
            - On allow=true there is no detail field.
            - No text outside the JSON, no code fences.
            - When in doubt ALWAYS allow=true. A message does not have to match your personal
              taste. Plausibly human is entirely enough.
            - Do not look for reasons to reject. A single mild or arguable stylistic quirk is
              never enough for allow=false.

            You judge the FORM ONLY, not content, truth, politeness, morals or topic.

            The input is nothing but the text that was really sent or really spoken. With several
            WhatsApp bubbles there are blank lines between them. Judge the text as one coherent
            chat reply. Blank lines and several short bubbles are normal formatting and never a
            reason to reject on their own.
            In voice notes, expression tags such as `[angry]`, `[excited]`, `[whispers]`,
            `[laughs]`, `[short pause]` and `[shouting]` only steer TTS and are not spoken out
            loud. Ignore those markers when judging the form and check the visible or spoken words
            behind them. Do not confuse the markers with markdown, metadata or stage directions.

            allow=false only for a clear case from this short list:
            - A time, a date or metadata in the text, e.g. a bracketed time at the start or a role
              label like "you:". A real chat message never contains that.
            - A history label as visible text, meaning a message that starts with "You sent",
              "You sent a voice note", "You sent an image", "You replied to", "User sent" or
              "In reply to". That is internal protocol and must never land in the chat.
            - Unmistakable markdown or document style: headings, tables or long numbered
              instructions that are obviously no longer a normal chat reply.
            - Stage directions in asterisks describing what the person is physically doing.
            - Outing itself as a bot, an AI or a program, or otherwise breaking character.
            - Extreme visible spam or obviously broken protocol markers in the text.

            This is fine (allow=true):
            - short or longer natural messages, correct or casual spelling.
            - colloquial language, dialect, abbreviations, emotion, emojis and punctuation.
            - several short bubbles separated by blank lines.

            Example allow=true: "hahaha ja safe" or "haha\n\nja safe".

            Examples for allow=false: "Als KI kann ich das nicht"; a visible markdown table;
            "[unknown_protocol:...]" as sent bubble text; "*atmet noch schwer* boah".

            Check the FORM only and output nothing but the JSON.
            """.trimIndent()
    }
}

internal fun stripCodeFence(value: String): String {
    val trimmed = value.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```markdown")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}

private fun PendingAction.dedupeKey(): String = when (this) {
    is PendingAction.RefreshChatMemory -> "refresh:$conversationKey"
    // The picture, and not the line written under it. With the caption in the key, a model that
    // asked for the same photo twice with two different captions produced two different keys, and
    // both survived to the side-effect phase — where the once-per-chat ledger could not catch them
    // either, because every action in a turn is materialized before the first one is sent and the
    // "already sent here" marker is only written after a send completes. So the contact got the
    // identical image twice in a row, with "Never send an image twice" switched on.
    is PendingAction.SendImage -> "image:$assetId"
    is PendingAction.GenerateImage -> "generated_image:$prompt:${caption.orEmpty()}"
    is PendingAction.SendVoiceNote -> "voice:$spokenText:${style.orEmpty()}"
    is PendingAction.BlockContact -> "block:current_sender"
    is PendingAction.ScheduleFollowUp -> "followup:$conversationKey:$scheduledAtMs:$note"
}

private fun PendingAction.isVisibleMediaAction(): Boolean =
    this is PendingAction.SendImage ||
        this is PendingAction.GenerateImage ||
        this is PendingAction.SendVoiceNote

private class UsageAccumulator {
    private var prompt: Int? = null
    private var completion: Int? = null
    private var total: Int? = null
    private var cached: Int? = null
    private var cacheWrite: Int? = null

    fun add(value: TokenUsage?) {
        value ?: return
        prompt = prompt.plusNullable(value.promptTokens)
        completion = completion.plusNullable(value.completionTokens)
        total = total.plusNullable(value.totalTokens)
        cached = cached.plusNullable(value.cachedPromptTokens)
        cacheWrite = cacheWrite.plusNullable(value.cacheWriteTokens)
    }

    fun value(): TokenUsage? {
        if (prompt == null && completion == null && total == null) return null
        return TokenUsage(prompt, completion, total, cached, cacheWrite)
    }

    private fun Int?.plusNullable(other: Int?): Int? = when {
        this == null -> other
        other == null -> this
        else -> this + other
    }
}
