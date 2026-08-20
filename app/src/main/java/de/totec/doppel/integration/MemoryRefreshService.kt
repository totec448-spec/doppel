package de.totec.doppel.integration

import de.totec.doppel.ai.AiMessage
import de.totec.doppel.ai.ChatCompletionGateway
import de.totec.doppel.ai.ChatCompletionRequest
import de.totec.doppel.ai.ChatRole
import de.totec.doppel.ai.CacheControl
import de.totec.doppel.ai.MissingApiKeyException
import de.totec.doppel.ai.LiveTokenSink
import de.totec.doppel.ai.OpenRouterHttpException
import de.totec.doppel.ai.OpenRouterProtocolException
import de.totec.doppel.ai.OpenRouterStallException
import de.totec.doppel.ai.ReasoningEffort
import de.totec.doppel.ai.SamplingSettings
import de.totec.doppel.ai.TokenUsage
import de.totec.doppel.ai.ToolChoice
import de.totec.doppel.ai.ToolDefinition
import de.totec.doppel.ai.stripCodeFence
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.ChatMemoryRecord
import de.totec.doppel.data.db.ChatRecord
import de.totec.doppel.data.db.CHAT_INJECTION_MESSAGE_TYPE
import de.totec.doppel.data.db.SCHEDULED_FOLLOW_UP_MESSAGE_TYPE
import de.totec.doppel.data.db.MessageDirection
import de.totec.doppel.data.db.MessageRecord
import de.totec.doppel.data.db.PersonaMemoryRecord
import de.totec.doppel.engine.ConversationMemoryPolicy
import de.totec.doppel.engine.MemoryWork
import de.totec.doppel.engine.MemoryWorkFeed
import de.totec.doppel.engine.MemoryWorkScope
import de.totec.doppel.engine.MemoryWriteHold
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Event-driven memory consolidation. It deliberately owns no timer or queue scanner: a
 * memory tool call invokes [refresh] once, while a caller runs the cheap [refreshIfDue] gate as
 * post-send work.
 *
 * Two things about the shape of this are deliberate and were arrived at the hard way:
 *
 * The memory is German prose in fixed sections, not JSON. The strict nested-JSON contract this
 * used to demand failed on essentially every call — the model answered in the persona's voice or
 * with a fragment, the parse threw, and no memory was ever written. Prose has no parse to fail:
 * whatever comes back either is a summary or is too short to be one.
 *
 * The instruction is appended as a USER message at the very end of the reply turn's verbatim
 * prompt. It used to be a SYSTEM message, which providers hoist to the front of the request — that
 * moves the prefix, so the cache missed and the whole 12k-token history was re-billed for a
 * summary. Behind the history it costs only the instruction itself.
 */
internal class MemoryRefreshService(
    private val gateway: ChatCompletionGateway,
    private val persistence: MemoryRefreshPersistence,
    private val configurationProvider: MemoryRefreshConfigurationProvider? = null,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    /**
     * Where a running write announces itself so the UI can draw it. Absent in tests and in any
     * build without a screen attached, which is why every publish goes through [tracking] rather
     * than a branch at each call site.
     */
    private val work: MemoryWorkFeed? = null,
    /** Installed by the live engine; tests and headless tools may omit the global reply gate. */
    private val globalMemoryHold: ((String) -> MemoryWriteHold?)? = null,
) {
    // A single process-wide mutex made an unrelated slow model call for chat A block even the
    // cheap cadence check for chat B. Fixed stripes keep the no-double-billing guarantee for one
    // conversation without retaining an unbounded map of contact keys.
    private val refreshLocks = Array(REFRESH_LOCK_STRIPES) { Mutex() }

    suspend fun refresh(request: MemoryRefreshRequest): MemoryRefreshOutcome =
        refreshLock(request.conversationKey).withLock {
            refreshLocked(
                request,
                minimumNewMessages = 1,
                auditSkipped = true,
            )
        }

    /**
     * The cross-chat synthesis on its own, triggered by hand from the memory browser.
     *
     * It is the same [synthesizePersonaMemory] the automatic path runs — same prompt, same model,
     * same commit — only without a chat write in front of it and past the cadence gate. Its commit
     * still stores the current chat-write total, so the automatic every-third-write rule is re-armed
     * from here rather than left standing.
     *
     * The request carries a synthetic conversation key ([personaConversationKey]): nothing about
     * this call belongs to one chat, and the source load returns the persona's revision and marker
     * for an unknown chat id without touching any message.
     */
    suspend fun synthesizePersona(personaKey: String): MemoryRefreshOutcome {
        val request =
            configuredRequest(personaConversationKey(personaKey), personaKey)
                ?: return MemoryRefreshOutcome.Failed("memory_configuration_unavailable")
        return refreshLock(request.personaId).withLock {
            val hold = globalMemoryHold?.invoke(request.personaId)
            try {
                hold?.awaitReady()
            val source =
                try {
                    persistence.load(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withLock MemoryRefreshOutcome.Failed("memory_source_unavailable")
                }
            val updatedAt = wallTimeMillis().coerceAtLeast(0L)
            val persona =
                synthesizePersonaMemory(
                    request,
                    source,
                    updatedAt,
                    force = true,
                    existingHold = hold,
                )
            val outcome =
                if (persona.written) {
                    MemoryRefreshOutcome.Updated(
                        chatRevision = source.chatRevision,
                        personaRevision = source.personaRevision + 1,
                        personaSynthesized = true,
                        usage = persona.usage,
                    )
                } else {
                    MemoryRefreshOutcome.Failed("persona_synthesis_failed")
                }
            auditSafely(request, outcome, source, persona.usage)
            outcome
            } finally {
                hold?.close()
            }
        }
    }

    /**
     * Cheap post-send cadence gate. It performs bounded indexed reads, but no model request until
     * at least [threshold] not-yet-consolidated messages exist. The durable last-message marker,
     * revision and source count are reloaded under the conversation lock, so a forced refresh and an
     * automatic refresh cannot both bill the same progress in this runtime.
     *
     * [firstWriteThreshold] applies only to a chat that has never been consolidated: it waits for
     * the complete window (retained overlap + interval) instead of the bare interval, so a brand
     * new conversation does not pay for a summary before it has an overlap worth keeping.
     *
     * [demanded] is a person pressing "Create Memory". It is the one thing that overrules the chat
     * memory setting being off: a button that silently does nothing is worse than a memory the
     * operator asked for by hand and can delete again.
     */
    suspend fun refreshIfDue(
        conversationKey: String,
        personaKey: String,
        threshold: Int = DEFAULT_AUTOMATIC_THRESHOLD,
        firstWriteThreshold: Int = ConversationMemoryPolicy.DEFAULT_COMPLETE_CHAT_WINDOW_MESSAGES,
        cachedPrompt: CachedTurnPrompt? = null,
        demanded: Boolean = false,
        forcePersonaSynthesis: Boolean = false,
    ): MemoryRefreshOutcome {
        if (threshold !in 1..MAX_AUTOMATIC_THRESHOLD) {
            return MemoryRefreshOutcome.Failed("invalid_memory_threshold")
        }
        if (firstWriteThreshold !in 1..MAX_AUTOMATIC_THRESHOLD) {
            return MemoryRefreshOutcome.Failed("invalid_memory_threshold")
        }
        val request =
            configuredRequest(conversationKey, personaKey, cachedPrompt)
                ?: return MemoryRefreshOutcome.Failed("memory_configuration_unavailable")
        return refreshLock(conversationKey).withLock {
            refreshLocked(
                request,
                minimumNewMessages = threshold,
                firstWriteMinimum = firstWriteThreshold,
                auditSkipped = false,
                demanded = demanded,
                forcePersonaSynthesis = forcePersonaSynthesis,
            )
        }
    }

    /**
     * The live model, character limit, persona ground truth and timezone for one request. Every
     * caller that does not already hold a request goes through here, so a chat refresh and a persona
     * synthesis can never end up on different configurations.
     */
    private suspend fun configuredRequest(
        conversationKey: String,
        personaKey: String,
        cachedPrompt: CachedTurnPrompt? = null,
    ): MemoryRefreshRequest? {
        val provider = configurationProvider ?: return null
        val configuration =
            try {
                provider.configuration(conversationKey, personaKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
        return runCatching {
            MemoryRefreshRequest(
                conversationKey = conversationKey,
                personaId = personaKey,
                model = configuration.model,
                configuredCharacterLimit = configuration.characterLimit,
                personaGroundTruth = configuration.personaGroundTruth,
                timezone = configuration.timezone,
                reasoningEffort = configuration.reasoningEffort,
                cachedPrompt = cachedPrompt,
                chatMemoryEnabled = configuration.chatMemoryEnabled,
                globalMemoryEnabled = configuration.globalMemoryEnabled,
                personaSynthesisEvery = configuration.personaSynthesisEvery,
            )
        }.getOrNull()
    }

    private fun refreshLock(conversationKey: String): Mutex =
        refreshLocks[(conversationKey.hashCode() and Int.MAX_VALUE) % refreshLocks.size]

    /**
     * Announces a write for as long as it runs, and only for that long.
     *
     * Deliberately started at the model call rather than at the entry point: the cheap cadence gate
     * runs after every single send and decides against a write almost every time, and flashing the
     * indicator for each of those would make it meaningless. Paired with [endWork] in a `finally`,
     * never with a lambda, because both write paths return early from half a dozen failure branches.
     */
    private fun beginWork(
        scope: MemoryWorkScope,
        key: String,
        personaKey: String,
        chatJid: String?,
    ): MemoryWork? {
        val feed = work ?: return null
        val started =
            MemoryWork(
                scope = scope,
                key = key,
                personaKey = personaKey,
                chatJid = chatJid,
                startedAtMs = wallTimeMillis().coerceAtLeast(0L),
            )
        feed.started(started)
        return started
    }

    private fun endWork(started: MemoryWork?) {
        started?.let { work?.finished(it.scope, it.key) }
    }

    /**
     * The whole retry rule, and there is deliberately no more to it: a failed write is attempted a
     * second time straight away, and if that fails too the call is over.
     *
     * Nothing durable is consumed until the commit, so a give-up costs nothing but the cycle: the
     * marker does not move, the unconsolidated messages stay piled up behind it, and the next
     * message's cadence check picks the same pointer up again with that message appended. Two
     * failures in a row therefore turn into one attempt per following message all by themselves —
     * which is the intended behaviour, not a leak. There is no backoff floor: a memory that cannot
     * be written is a broken bot, and delaying the next attempt only hides it for longer.
     */
    private suspend fun refreshLocked(
        request: MemoryRefreshRequest,
        minimumNewMessages: Int,
        auditSkipped: Boolean,
        firstWriteMinimum: Int = ConversationMemoryPolicy.DEFAULT_COMPLETE_CHAT_WINDOW_MESSAGES,
        demanded: Boolean = true,
        forcePersonaSynthesis: Boolean = false,
    ): MemoryRefreshOutcome {
        val first =
            refreshAttempt(
                request,
                minimumNewMessages,
                auditSkipped,
                firstWriteMinimum,
                demanded,
                forcePersonaSynthesis,
            )
        if (first.succeeded) return first
        // Re-entered from the top rather than resumed: the second attempt has to reload the source,
        // or a lost revision race would be retried against exactly the revision that just lost it.
        return refreshAttempt(
            request,
            minimumNewMessages,
            auditSkipped,
            firstWriteMinimum,
            demanded,
            forcePersonaSynthesis,
        )
    }

    private suspend fun refreshAttempt(
        request: MemoryRefreshRequest,
        minimumNewMessages: Int,
        auditSkipped: Boolean,
        firstWriteMinimum: Int,
        demanded: Boolean,
        forcePersonaSynthesis: Boolean,
    ): MemoryRefreshOutcome {
        val source =
            try {
                persistence.loadIfDue(request, minimumNewMessages, firstWriteMinimum)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val outcome = MemoryRefreshOutcome.Failed("memory_source_unavailable")
                auditSafely(request, outcome, null, null)
                return outcome
            }

        val requiredNewMessages =
            if (source.chatRevision == 0L && minimumNewMessages > 1) {
                maxOf(minimumNewMessages, firstWriteMinimum)
            } else {
                minimumNewMessages
            }
        if (source.newMessageCount < requiredNewMessages || source.messages.isEmpty()) {
            val reason =
                if (source.newMessageCount == 0 || source.messages.isEmpty()) {
                    "no_new_messages"
                } else {
                    "cadence_not_due"
                }
            val outcome = MemoryRefreshOutcome.Skipped(reason)
            if (auditSkipped) {
                auditSafely(request, outcome, source, null)
            }
            return outcome
        }

        if (!request.chatMemoryEnabled && !demanded) {
            return advanceWindowWithoutWriting(request, source)
        }

        val outgoing = buildRequest(request, source)
        val announced =
            beginWork(
                scope = MemoryWorkScope.CHAT,
                key = request.conversationKey,
                personaKey = request.personaId,
                chatJid = source.chatId,
            )
        return try {
            refreshLockedAnnounced(request, source, outgoing, forcePersonaSynthesis, announced)
        } finally {
            // Still the safety net for every failure exit. The success path clears the row earlier,
            // and clearing twice is a no-op.
            endWork(announced)
        }
    }

    /**
     * The cadence with chat memory switched off: move the pointer, call no model.
     *
     * The interval is not only a spending rule, it is what makes the rendered history turn over —
     * the window is pinned until a new chat-memory revision releases it. Leaving the pointer where
     * it was would have made "off" mean "the prompt grows forever and the whole conversation is
     * re-billed on every turn", which is the opposite of switching memory off. So the revision and
     * the marker advance exactly as a real write advances them; the difference is that what falls
     * out of the window is forgotten rather than summarised.
     *
     * The persona synthesis is not run from here. It reads chat memories, and none of them moved.
     */
    private suspend fun advanceWindowWithoutWriting(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
    ): MemoryRefreshOutcome {
        val updatedAt = wallTimeMillis().coerceAtLeast(0L)
        val outcome =
            try {
                persistence.advanceChatWindow(request, source, updatedAt)
                MemoryRefreshOutcome.Skipped("chat_memory_disabled")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MemoryRevisionConflictException) {
                MemoryRefreshOutcome.Failed("memory_revision_conflict")
            } catch (_: Exception) {
                MemoryRefreshOutcome.Failed("memory_persistence_failed")
            }
        auditSafely(request, outcome, source, null)
        return outcome
    }

    /** The billed half of [refreshLocked], split out so the announcement has one exit to clear on. */
    private suspend fun refreshLockedAnnounced(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        outgoing: ChatCompletionRequest,
        forcePersonaSynthesis: Boolean,
        announced: MemoryWork?,
    ): MemoryRefreshOutcome {
        try {
            persistence.auditStarted(request, source, outgoing.model, wallTimeMillis().coerceAtLeast(0L))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics never block the consolidation itself.
        }

        val completion =
            try {
                completeWithDraft(
                    request = outgoing,
                    scope = MemoryWorkScope.CHAT,
                    key = request.conversationKey,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MissingApiKeyException) {
                val outcome = MemoryRefreshOutcome.Failed("api_key_missing")
                auditSafely(request, outcome, source, null)
                return outcome
            } catch (error: IOException) {
                // Memory uses the configured main chat model. Calling a transport/SSE failure a
                // missing "utility model" sent the operator looking for a model path that no
                // longer exists.
                val outcome = MemoryRefreshOutcome.Failed(memoryProviderFailureCode(error))
                auditSafely(request, outcome, source, null)
                return outcome
            } catch (_: Exception) {
                val outcome = MemoryRefreshOutcome.Failed("utility_model_failed")
                auditSafely(request, outcome, source, null)
                return outcome
            }

        val summary =
            try {
                chatSummaryOf(completion.content, request.outputCharacterLimit(), source.isGroup)
            } catch (_: InvalidMemoryResponse) {
                val outcome = MemoryRefreshOutcome.Failed("invalid_memory_response")
                auditSafely(request, outcome, source, completion.usage)
                return outcome
            }

        val updatedAt = wallTimeMillis().coerceAtLeast(0L)
        try {
            persistence.commitChat(request, source, summary, updatedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MemoryRevisionConflictException) {
            val outcome = MemoryRefreshOutcome.Failed("memory_revision_conflict")
            auditSafely(request, outcome, source, completion.usage)
            return outcome
        } catch (_: Exception) {
            val outcome = MemoryRefreshOutcome.Failed("memory_persistence_failed")
            auditSafely(request, outcome, source, completion.usage)
            return outcome
        }

        // This chat's write is done and durable, so its indicator comes down here rather than at the
        // end of the function. What follows is the persona synthesis — a second full model call that
        // can run for minutes — and leaving the chat row up across it told the operator their chat
        // memory was still being generated long after it had been written.
        endWork(announced)

        // The chat memory is already durable, so a failing synthesis costs the persona view one
        // cycle and nothing else. It runs after the commit on purpose: it must see the memory
        // that was just written, not the one it replaced. It announces itself separately, under the
        // persona it actually belongs to.
        val persona =
            synthesizePersonaMemory(
                request,
                source,
                updatedAt,
                force = forcePersonaSynthesis && request.globalMemoryEnabled,
            )
        val outcome =
            MemoryRefreshOutcome.Updated(
                chatRevision = source.chatRevision + 1,
                personaRevision = source.personaRevision + if (persona.written) 1 else 0,
                personaSynthesized = persona.written,
                usage = completion.usage.plus(persona.usage),
            )
        auditSafely(request, outcome, source, outcome.usage)
        return outcome
    }

    /**
     * The persona's cross-chat memory, re-synthesised every
     * [MemoryRefreshRequest.personaSynthesisEvery] chat refreshes.
     *
     * Doing it on every refresh means a second full call each time — the expensive half of the
     * whole feature. The chat memory it is built from only moves a little per cycle, so lagging
     * two cycles behind costs nothing noticeable and cuts the synthesis bill by two thirds at the
     * default of three. The
     * cadence is read from the durable sum of every chat-memory revision owned by the persona.
     * That makes three writes across three different chats equivalent to three writes in one chat,
     * and a restart cannot reset or re-arm the counter.
     *
     * [force] is the hand-triggered synthesis from the memory browser. It skips the cadence check
     * and the global memory setting, but not the commit, and the commit stores the current total —
     * so forcing one does not leave the automatic cadence permanently armed, it re-arms it a full
     * interval out.
     */
    private suspend fun synthesizePersonaMemory(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        updatedAt: Long,
        force: Boolean = false,
        existingHold: MemoryWriteHold? = null,
    ): PersonaSynthesis {
        if (!force && !request.globalMemoryEnabled) {
            return PersonaSynthesis(written = false, usage = null)
        }
        val memories =
            try {
                persistence.loadPersonaChatMemories(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return PersonaSynthesis(written = false, usage = null)
            }
        if (memories.isEmpty()) return PersonaSynthesis(written = false, usage = null)
        val personaChatWrites =
            try {
                persistence.loadPersonaChatWriteCount(request, memories)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return PersonaSynthesis(written = false, usage = null)
            }
        if (
            !force &&
            personaChatWrites - source.personaSynthesisMarker < request.personaSynthesisEvery
        ) {
            return PersonaSynthesis(written = false, usage = null)
        }
        // No retry of its own: the marker only moves on a successful commit, so a failed synthesis
        // is picked up again at the next chat write — three chat writes apart, which is already far
        // rarer than the per-message retry the chat memory gets.
        val hold = existingHold ?: globalMemoryHold?.invoke(request.personaId)
        return try {
            hold?.takeIf { it !== existingHold }?.awaitReady()
            val announced =
                beginWork(
                    scope = MemoryWorkScope.PERSONA,
                    key = request.personaId,
                    personaKey = request.personaId,
                    chatJid = null,
                )
            try {
            synthesizePersonaMemoryAnnounced(
                request,
                source,
                updatedAt,
                memories,
                personaChatWrites,
            )
            } finally {
                endWork(announced)
            }
        } finally {
            if (existingHold == null) hold?.close()
        }
    }

    /** The billed half of [synthesizePersonaMemory]; see [beginWork] for why it is split out. */
    private suspend fun synthesizePersonaMemoryAnnounced(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        updatedAt: Long,
        memories: List<PersonaChatMemory>,
        personaChatWrites: Long,
    ): PersonaSynthesis {
        val completion =
            try {
                completeWithDraft(
                    scope = MemoryWorkScope.PERSONA,
                    key = request.personaId,
                    request =
                    ChatCompletionRequest(
                        model = request.model,
                        messages =
                            listOf(
                                AiMessage.text(
                                    ChatRole.SYSTEM,
                                    personaSynthesisSystemPrompt(request.personaGroundTruth),
                                ),
                                AiMessage.text(
                                    ChatRole.USER,
                                    personaSynthesisPayload(memories),
                                    cacheControl = CacheControl.EPHEMERAL,
                                ),
                            ),
                        sampling = samplingSettings(request),
                        stream = true,
                        requestTag = "memory_persona_synthesis",
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never fall back to concatenating the chat memories: the last good synthesis is
                // a better persona view than a pile of per-chat text.
                return PersonaSynthesis(written = false, usage = null)
            }

        val summary =
            sanitizeSummary(completion.content, request.outputCharacterLimit())
                .takeIf(::isUsableSummary)
                ?: return PersonaSynthesis(written = false, usage = completion.usage)
        return try {
            persistence.commitPersona(
                request = request,
                revision = source.personaRevision + 1,
                summary = summary,
                updatedAt = updatedAt,
                chatWriteCount = personaChatWrites,
            )
            PersonaSynthesis(written = true, usage = completion.usage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PersonaSynthesis(written = false, usage = completion.usage)
        }
    }

    /**
     * Two shapes of the same job.
     *
     * The cheap one appends the instruction to the verbatim prompt of the reply turn that just
     * finished, as a USER message at the very end, so the provider's cache serves the persona,
     * memory and history block that request already paid for.
     *
     * The standalone one carries its own system prompt and is what a cold caller (forced tool
     * call, no preceding turn) still gets.
     */
    private fun buildRequest(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
    ): ChatCompletionRequest {
        val cached = request.cachedPrompt
        // The reused prompt only replaces the transcript when its history verifiably contains
        // every message still waiting for consolidation. Otherwise the older ones are inlined.
        val reuseHistory =
            cached != null &&
                source.messages.all { it.providerMessageId in cached.providerMessageIds }

        if (cached == null) {
            return ChatCompletionRequest(
                model = request.model,
                messages =
                    listOf(
                        AiMessage.text(
                            ChatRole.SYSTEM,
                            chatMemorySystemPrompt(request.personaGroundTruth, source.isGroup),
                        ),
                        AiMessage.text(
                            ChatRole.USER,
                            chatMemoryTask(request, source, includeTranscript = true),
                            cacheControl = CacheControl.EPHEMERAL,
                        ),
                    ),
                sampling = samplingSettings(request),
                stream = true,
                requestTag = "memory_refresh",
            )
        }

        val instruction =
            buildString {
                appendLine(CACHED_TASK_OVERRIDE)
                appendLine()
                appendLine(chatMemorySystemPrompt(request.personaGroundTruth, source.isGroup))
                appendLine()
                append(chatMemoryTask(request, source, includeTranscript = !reuseHistory))
            }
        return ChatCompletionRequest(
            model = cached.model,
            // USER, never SYSTEM: a system message is hoisted to the front of the request by the
            // provider, which moves the cached prefix and re-bills the entire history.
            messages = cached.messages + AiMessage.text(ChatRole.USER, instruction),
            // Byte-identical to the turn, including `tool_choice`, and that last part is not
            // cosmetic. This request only exists to ride the turn's prefix cache, and the provider
            // renders the prompt itself: measured on 2026-08-10 against DeepSeek V4 Flash, a turn
            // cached 10,386 of 10,783 tokens while the memory call that reused its exact messages
            // cached 3,072 of 16,456 — a break at the end of the stable system block, which is
            // where the tool definitions are rendered. `tool_choice: none` is the only field we
            // changed that reaches that rendering, so the previous comment here ("the definitions
            // stay in the prompt") was an assumption about the template, not an observation.
            //
            // Nothing forbids a call at the protocol level any more; CACHED_TASK_OVERRIDE does it
            // in words. A tool call instead of a summary therefore just yields no usable summary,
            // which is an ordinary failed attempt and rides the retry above.
            tools = cached.tools,
            toolChoice = ToolChoice.AUTO,
            sampling = cached.sampling,
            stream = true,
            requestTag = "memory_refresh_cached",
        )
    }

    /**
     * Streams only the final memory prose into the ephemeral UI feed. Reasoning is deliberately
     * consumed as a phase signal, never copied: it is neither operator data nor durable memory.
     * Cancelling the caller cancels the SSE reader and its OkHttp call through the gateway.
     */
    private suspend fun completeWithDraft(
        request: ChatCompletionRequest,
        scope: MemoryWorkScope,
        key: String,
    ) =
        if (work == null) {
            gateway.complete(request)
        } else {
            val sink =
                object : LiveTokenSink() {
                    override fun emitReasoning(text: String) = Unit

                    override fun emitContent(text: String) {
                        work.appendDraft(scope, key, text)
                    }
                }
            withContext(sink) { gateway.complete(request) }
        }

    /**
     * Memory is the one call in this app that is worth thinking about: it decides what the persona
     * still knows in a month, it runs once per seventy messages, and a weak summary silently
     * degrades every later reply.
     *
     * It nevertheless runs on the same model and the same reasoning effort as the reply turns. That
     * is the whole point: a request that differs in either cannot share the provider's prompt cache
     * with the turn it follows, and the cached-prompt path below reuses exactly that prefix. The
     * budget still leaves room for thinking on top of the summary itself, because reasoning is
     * billed as (hidden) output.
     */
    private fun samplingSettings(request: MemoryRefreshRequest) =
        SamplingSettings(
            temperature = 0.2,
            topP = 1.0,
            // A sectioned prose memory is thousands of characters. The old 1k cap truncated it
            // mid-sentence, which then failed the usability check.
            maxTokens = MAX_OUTPUT_TOKENS,
            reasoningEffort = request.reasoningEffort,
        )

    /** Privacy-safe but precise enough for the UI/activity row to name the actual failure class. */
    private fun memoryProviderFailureCode(error: IOException): String =
        when (error) {
            is OpenRouterHttpException -> "openrouter_http_${error.statusCode}"
            is OpenRouterStallException -> "openrouter_stream_stalled"
            is OpenRouterProtocolException -> "openrouter_${error.reasonCode}"
            else -> "openrouter_network_unavailable"
        }

    /** Persona ground truth plus the section layout — identical for both request shapes. */
    private fun chatMemorySystemPrompt(
        personaGroundTruth: String,
        isGroup: Boolean,
    ): String {
        val intro =
            listOf(
                "# Your persona (ground truth)",
                personaGroundTruth
                    .trim()
                    .take(MAX_PERSONA_GROUND_TRUTH_CHARACTERS)
                    .ifBlank { "(no persona set)" },
                "This IS you. Derive \"About me\" from this and from your own statements in the " +
                    "chat. Do NOT speculate about yourself and invent nothing on top.",
                "",
            )
        val sections =
            if (isGroup) {
                listOf(
                    "You keep a lasting memory for a WhatsApp GROUP CHAT, from the persona's " +
                        "point of view (\"I\"). Top goal: the persona stays CONSISTENT over " +
                        "weeks and never contradicts itself. Structure it into exactly these " +
                        "sections:",
                    "1) ABOUT ME (persona): everything I revealed about myself in the role and " +
                        "have to keep consistent (name, age, where I live, school/job, people in " +
                        "my life, concrete statements). Those facts must NEVER contradict each " +
                        "other.",
                    "2) ABOUT THE PARTICIPANTS: one entry per person — name/handle, how to " +
                        "recognise them, facts about them, their relationship to me, writing " +
                        "style.",
                    "3) GROUP CONTEXT: what the group is about, its purpose/topic, the dynamic, " +
                        "running gags and in-jokes.",
                    "4) DATES & ROUTINES: concrete appointments and events with a date — mine " +
                        "AND the participants'. Fixed routines too. Resolve relative wording " +
                        "(\"today\", \"tomorrow\") into a concrete date using the timestamps. " +
                        "Past one-off dates may be removed.",
                    "5) OPEN THREADS: what should be come back to.",
                    "6) STATE: mood and closeness most recently, what was left unresolved.",
                )
            } else {
                listOf(
                    "You keep a lasting memory for a WhatsApp chat, from the persona's point " +
                        "of view (\"I\"). Top goal: the persona stays CONSISTENT over weeks and " +
                        "never contradicts itself. Structure it into exactly these sections:",
                    "1) ABOUT ME (persona): everything I revealed about myself in the role and " +
                        "have to keep consistent (name, age, where I live, school/job, hobbies, " +
                        "people in my life, pets, concrete statements such as \"was out last " +
                        "night\"). Those facts must NEVER contradict each other.",
                    "2) ABOUT THE CHAT PARTNER: name, relationship to me, likes and dislikes, " +
                        "projects, important people, boundaries, in-jokes, writing style.",
                    "3) DATES & ROUTINES: concrete appointments and events with a date — mine " +
                        "AND the chat partner's. Fixed routines too. Resolve relative wording " +
                        "(\"today\", \"tomorrow\", \"next week\") into a concrete date using the " +
                        "timestamps. Past one-off dates may be removed.",
                    "4) OPEN THREADS: what should be come back to.",
                    "5) RELATIONSHIP/STATE: closeness, mood most recently, what was left " +
                        "unresolved.",
                )
            }
        return (
            intro + sections +
                listOf(
                    "No long quotations, no trivial or episodic detail — only stable, reusable " +
                        "facts. Do NOT lose dates, routines or newly mentioned events with a " +
                        "date. Update the old memory: carry it over as far as possible, add only " +
                        "what is new, condense, and do NOT contradict established persona facts.",
                    "This chat and the old memory are your ONLY sources. Never carry facts in " +
                        "about people from other chats: a separate global memory already holds " +
                        "those, and repeating them here only makes this file longer and staler.",
                    "NEVER remember passwords, API keys, credentials, tokens or complete payment " +
                        "details. Keep it compact, and write the memory in the language of the " +
                        "chat — German unless the chat itself is in another one.",
                )
        ).joinToString("\n")
    }

    private fun chatMemoryTask(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        includeTranscript: Boolean,
    ): String =
        buildString {
            appendLine("Old memory (carry it over as far as possible):")
            appendLine(
                source.previousChatSummary
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: "(no memory yet)",
            )
            appendLine()
            if (includeTranscript) {
                appendLine("New/current chat excerpts (work the small updates from them in):")
                appendLine(transcript(source, request.timezone))
                appendLine()
            } else {
                appendLine(
                    "The new material is the chat history above — the last " +
                        "${source.messages.size} messages of it are not condensed yet.",
                )
                appendLine()
            }
            append(
                "Now return the updated memory, in the sections named above, at most " +
                    "${request.outputCharacterLimit()} characters. Prioritise \"About me\" and " +
                    "leave out none of the statements I made about myself. Only the memory, no " +
                    "preamble, no JSON, no salutation.",
            )
            if (source.isGroup) {
                append(" Start the output with \"$GROUP_MARKER\".")
            }
        }

    private fun transcript(
        source: MemoryRefreshSource,
        timezone: String,
    ): String {
        val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.of("UTC") }
        return source.messages.joinToString("\n") { message ->
            val time =
                runCatching {
                    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(message.timestampMs).atZone(zone))
                }.getOrDefault("")
            val speaker =
                when {
                    message.role == "system" -> "operator note"
                    message.role == "assistant" -> "me (persona)"
                    source.isGroup -> "participant"
                    else -> "the other person"
                }
            "[$time] $speaker: ${message.text}"
        }
    }

    private fun personaSynthesisSystemPrompt(personaGroundTruth: String): String =
        listOf(
            "# Your persona (ground truth)",
            personaGroundTruth
                .trim()
                .take(MAX_PERSONA_GROUND_TRUTH_CHARACTERS)
                .ifBlank { "(no persona set)" },
            "This IS you. Anchor \"About me\" in it and speculate nothing on top.",
            "",
            "You condense several local chat memories into ONE global memory. IMPORTANT: a real " +
                "synthesis, NOT the chats stapled together. Deduplicate people who appear in " +
                "several chats and resolve contradictions in favour of the persona facts. " +
                "Compact, stable facts only, no long quotations. Structure it into EXACTLY these " +
                "sections:",
            "## About me — persona facts: name/age, where I live, family, school/job, fixed " +
                "statements I have to keep consistent.",
            "## Relationships — which people do I have which relationship with (kind/closeness).",
            "## People I know — one short entry per person: name/handle, where I know them from " +
                "(name the private chat or the group, using the headings below), how to " +
                "recognise them, the most important facts. If the same person writes to me " +
                "privately and in a group, that is ONE entry naming both.",
            "## Groups — which groups, who is in them, what they are about.",
            "## Dates & routines — concrete appointments/events with a date, plus fixed routines. " +
                "Keep the dates from the local memories.",
            "## Open threads / ongoing — what I should stay on across chats.",
            "## Other important things — anything else stable or relevant.",
            "NEVER remember passwords, API keys, credentials or tokens. Write the memory in the " +
                "language of the chats — German unless they are in another one.",
        ).joinToString("\n")

    private fun personaSynthesisPayload(memories: List<PersonaChatMemory>): String {
        val builder =
            StringBuilder(
                "My local chat memories. Every block is headed by whom it is with — that " +
                    "heading is the attribution: carry the name over into the entries you " +
                    "write, so a person stays recognisable across the chats they appear in.\n\n",
            )
        for (memory in memories) {
            val header =
                if (memory.isGroup) {
                    "### This is my memory of the GROUP \"${memory.label}\""
                } else {
                    "### This is my memory of the private chat with ${memory.label}"
                }
            val block = "$header\n${memory.summary.trim()}\n\n"
            if (builder.length + block.length > MAX_SYNTHESIS_SOURCE_CHARACTERS) break
            builder.append(block)
        }
        builder.append(
            "Return the global memory in the sections named above. Only the memory, no " +
                "preamble.",
        )
        return builder.toString()
    }

    private fun chatSummaryOf(
        raw: String,
        maximumCharacters: Int,
        isGroup: Boolean,
    ): String {
        if (raw.length > MAX_RAW_RESPONSE_CHARACTERS) throw InvalidMemoryResponse()
        val summary = sanitizeSummary(stripCodeFence(raw), maximumCharacters)
        if (!isUsableSummary(summary)) throw InvalidMemoryResponse()
        // A group memory is read back by the persona synthesis, which has to know what it is
        // looking at even when the model forgot the marker.
        return if (isGroup && !summary.startsWith(GROUP_MARKER)) {
            "$GROUP_MARKER\n$summary".take(maximumCharacters)
        } else {
            summary
        }
    }

    /**
     * A memory that short is a refusal, an "ok" or a truncated fragment — never a summary. Writing
     * it would overwrite a good memory with nothing, so it fails closed instead.
     */
    private fun isUsableSummary(value: String): Boolean = value.length >= MIN_USABLE_SUMMARY_CHARACTERS

    private fun sanitizeSummary(value: String, maximumCharacters: Int): String =
        SENSITIVE_ASSIGNMENT
            .replace(
                RAW_SECRET_PATTERN.replace(
                    value.replace(INVALID_CONTROL_CHARACTERS, " "),
                    "[redacted]",
                ),
            ) { match ->
                "${match.groupValues[1]}=[redacted]"
            }
            .trim()
            .replace(EXCESSIVE_BLANK_LINES, "\n\n")
            .take(maximumCharacters)
            .trim()

    private suspend fun auditSafely(
        request: MemoryRefreshRequest,
        outcome: MemoryRefreshOutcome,
        source: MemoryRefreshSource?,
        usage: TokenUsage?,
    ) {
        try {
            persistence.audit(request, outcome, source, usage, wallTimeMillis().coerceAtLeast(0L))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Memory persistence is authoritative. Diagnostics must never turn a completed
            // consolidation into a failed user turn.
        }
    }

    private class InvalidMemoryResponse : Exception()

    private data class PersonaSynthesis(
        val written: Boolean,
        val usage: TokenUsage?,
    )

    internal companion object {
        /**
         * Default new-message suffix between two memory writes. The live call derives it from the
         * configured interval; revision zero is additionally protected by the first-write threshold.
         */
        const val DEFAULT_AUTOMATIC_THRESHOLD =
            ConversationMemoryPolicy.DEFAULT_MEMORY_INTERVAL_MESSAGES
        const val MAX_AUTOMATIC_THRESHOLD = 5_000
        private const val REFRESH_LOCK_STRIPES = 64

        /**
         * Covers the summary *and* the hidden reasoning tokens, which most providers bill against
         * the same budget. At 4k the thinking alone could eat the whole allowance and return an
         * empty or half-written memory.
         */
        const val MAX_OUTPUT_TOKENS = 12_288
        const val MAX_RAW_RESPONSE_CHARACTERS = 64_000
        const val MAX_PERSONA_GROUND_TRUTH_CHARACTERS = 8_000
        const val MAX_SYNTHESIS_SOURCE_CHARACTERS = 60_000
        const val MIN_USABLE_SUMMARY_CHARACTERS = 80
        const val GROUP_MARKER = "[GROUP CHAT]"

        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
        val INVALID_CONTROL_CHARACTERS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        val EXCESSIVE_BLANK_LINES = Regex("\\n{3,}")
        val SENSITIVE_ASSIGNMENT =
            Regex(
                """(?i)\b(api[ _-]?key|password|passwort|secret|access[ _-]?token|bearer[ _-]?token)\b\s*[:=]\s*\S+""",
            )
        val RAW_SECRET_PATTERN =
            Regex("""(?i)\b(?:sk|key|token)[_-][a-z0-9_-]{16,}\b""")

        /**
         * Sits after the whole reused turn prompt, so its wording is free: it is past the cached
         * prefix either way and changing it costs nothing.
         *
         * The second paragraph is there because the reused prompt carries the persona's *global*
         * memory too, and the model kept mining it: a chat file about one person came back
         * carrying tidy little entries about everyone else she talks to. Nothing was confused —
         * it was simply copying the cross-chat file into a per-chat one, where it is duplicated,
         * goes stale, and costs a chunk of the character budget the actual chat needed.
         */
        val CACHED_TASK_OVERRIDE =
            """
            STOP. The chat above is context only — it was resent verbatim so the prompt cache
            matches. Do NOT answer as the persona, do NOT write a chat message and do NOT call a
            tool. Your only job now is the summary below.

            That prompt also carries a "Global Memory" block, which is what you know across ALL
            your chats. Read it only so you do not contradict yourself, and copy nothing out of
            it. This file is about THIS chat: it may only hold what is in the conversation above
            and in the old memory below. People who do not appear in this conversation do not
            belong in it, and neither does anything you know about them only from the global
            memory.
            """.trimIndent()
    }
}

/** Chat refresh plus persona synthesis are billed together, so the audit reports them together. */
private fun TokenUsage?.plus(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    fun add(left: Int?, right: Int?): Int? =
        if (left == null && right == null) null else (left ?: 0) + (right ?: 0)
    return TokenUsage(
        promptTokens = add(promptTokens, other.promptTokens),
        completionTokens = add(completionTokens, other.completionTokens),
        totalTokens = add(totalTokens, other.totalTokens),
        cachedPromptTokens = add(cachedPromptTokens, other.cachedPromptTokens),
        cacheWriteTokens = add(cacheWriteTokens, other.cacheWriteTokens),
    )
}

/**
 * The prompt a just-finished reply turn sent, handed to the memory refresh so it can append its
 * instruction instead of building a second prompt from scratch.
 *
 * The provider caches on an exact message prefix, so this must be the verbatim message list of
 * that turn — reassembling it would move the clock/mood block and miss the cache.
 * [providerMessageIds] proves which durable rows were rendered; the memory task may omit its own
 * transcript only when every row in the consolidation batch is already present by id.
 */
internal data class CachedTurnPrompt(
    val model: String,
    val messages: List<AiMessage>,
    val tools: List<ToolDefinition>,
    val sampling: SamplingSettings,
    val providerMessageIds: Set<String>,
) {
    init {
        require(model.isNotBlank())
        require(messages.isNotEmpty())
        require(providerMessageIds.none(String::isBlank))
    }
}

/**
 * Conversation key for a persona-only request. It deliberately names no real chat: the synthesis
 * reads the persona's own memory row and its per-chat memories, never a message of one chat.
 */
internal fun personaConversationKey(personaId: String): String = "persona-synthesis#$personaId"

internal data class MemoryRefreshRequest(
    val conversationKey: String,
    val personaId: String,
    /** The main chat model, so the memory call rides the same provider prefix cache. */
    val model: String,
    val configuredCharacterLimit: Int?,
    val personaGroundTruth: String = "",
    val timezone: String = "UTC",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MAX,
    val cachedPrompt: CachedTurnPrompt? = null,
    /**
     * False writes no chat memory at all. The cadence still runs — it advances the window pointer
     * instead of summarising, so the prompt keeps turning over without a model call.
     */
    val chatMemoryEnabled: Boolean = true,
    val globalMemoryEnabled: Boolean = true,
    /** Chat-memory writes between two persona syntheses. */
    val personaSynthesisEvery: Long =
        ConversationMemoryPolicy.DEFAULT_PERSONA_MEMORY_EVERY_CHAT_REFRESHES.toLong(),
    /** Normal refreshes stay at 120k; an explicit local text import may opt into 500k. */
    val sourceCharacterLimit: Int = RepositoryMemoryRefreshStore.MAX_SOURCE_CHARACTERS,
    val sourceMessageLimit: Int = RepositoryMemoryRefreshStore.MAX_SOURCE_MESSAGES,
) {
    init {
        require(conversationKey.isNotBlank())
        require(personaId.isNotBlank())
        require(model.isNotBlank())
        require(configuredCharacterLimit == null || configuredCharacterLimit > 0)
        require(timezone.isNotBlank())
        require(personaSynthesisEvery >= 1L)
        require(sourceCharacterLimit in 1..MAX_IMPORT_SOURCE_CHARACTERS)
        require(sourceMessageLimit in 1..RepositoryMemoryRefreshStore.MAX_BACKLOG_SCAN_ROWS)
        require(conversationKey.substringAfterLast('#', "") == personaId) {
            "conversationKey persona does not match personaId"
        }
    }

    fun outputCharacterLimit(): Int =
        configuredCharacterLimit?.coerceIn(MIN_OUTPUT_CHARACTERS, MAX_OUTPUT_CHARACTERS)
            ?: DEFAULT_OUTPUT_CHARACTERS

    private companion object {
        const val MIN_OUTPUT_CHARACTERS = 256
        const val DEFAULT_OUTPUT_CHARACTERS = 6_000
        const val MAX_OUTPUT_CHARACTERS = 12_000
        const val MAX_IMPORT_SOURCE_CHARACTERS = 500_000
    }
}

internal data class MemoryRefreshConfiguration(
    /** The main chat model and its reasoning effort — memory is not a separate model any more. */
    val model: String,
    val characterLimit: Int?,
    val personaGroundTruth: String = "",
    val timezone: String = "UTC",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MAX,
    val chatMemoryEnabled: Boolean = true,
    val globalMemoryEnabled: Boolean = true,
    val personaSynthesisEvery: Long =
        ConversationMemoryPolicy.DEFAULT_PERSONA_MEMORY_EVERY_CHAT_REFRESHES.toLong(),
) {
    init {
        require(model.isNotBlank())
        require(characterLimit == null || characterLimit > 0)
        require(timezone.isNotBlank())
        require(personaSynthesisEvery >= 1L)
    }
}

internal fun interface MemoryRefreshConfigurationProvider {
    suspend fun configuration(
        conversationKey: String,
        personaKey: String,
    ): MemoryRefreshConfiguration
}

internal sealed interface MemoryRefreshOutcome {
    val succeeded: Boolean

    data class Updated(
        val chatRevision: Long,
        val personaRevision: Long,
        val personaSynthesized: Boolean,
        val usage: TokenUsage?,
    ) : MemoryRefreshOutcome {
        override val succeeded = true
    }

    data class Skipped(val reasonCode: String) : MemoryRefreshOutcome {
        override val succeeded = true
    }

    data class Failed(val reasonCode: String) : MemoryRefreshOutcome {
        override val succeeded = false
    }
}

internal data class MemorySourceMessage(
    val providerMessageId: String,
    val role: String,
    val text: String,
    val timestampMs: Long,
)

internal data class MemoryRefreshSource(
    val chatId: String,
    val previousChatSummary: String?,
    val previousPersonaSummary: String?,
    val chatRevision: Long,
    val personaRevision: Long,
    /**
     * Chat-write total the persona memory was last synthesized at. The cadence gate compares the
     * current total against this instead of taking a modulo, so a forced synthesis moves the next
     * automatic one a full interval out.
     */
    val personaSynthesisMarker: Long = 0,
    val sourceMessageCount: Int,
    val newMessageCount: Int,
    val newestProviderMessageId: String?,
    val messages: List<MemorySourceMessage>,
) {
    val isGroup: Boolean get() = chatId.endsWith("@g.us")
}

/** One persona-owned chat memory, as input for the cross-chat synthesis. */
internal data class PersonaChatMemory(
    /** Never the raw JID: for a 1:1 chat that is the phone number, and this text is prompted. */
    val label: String,
    val isGroup: Boolean,
    val summary: String,
    /** Durable successful-write count for this chat. */
    val revision: Long = 1,
)

internal interface MemoryRefreshPersistence {
    suspend fun load(request: MemoryRefreshRequest): MemoryRefreshSource

    /** Optional cheap cadence gate; test/in-memory stores retain the full-load default. */
    suspend fun loadIfDue(
        request: MemoryRefreshRequest,
        minimumNewMessages: Int,
        firstWriteMinimum: Int,
    ): MemoryRefreshSource = load(request)

    /**
     * Announced right before the consolidation request goes out, so the activity log shows that
     * memory is being written while it happens instead of only once it succeeded or failed.
     */
    suspend fun auditStarted(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        model: String,
        occurredAt: Long,
    ) = Unit

    suspend fun commitChat(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        summary: String,
        updatedAt: Long,
    )

    /**
     * Moves the consolidation pointer to the newest read message without writing a memory, so the
     * rendered history window still turns over while chat memory is switched off. Throws
     * [MemoryRevisionConflictException] on a lost race, exactly like [commitChat].
     */
    suspend fun advanceChatWindow(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        updatedAt: Long,
    )

    /** Every chat memory belonging to this persona, newest write first. */
    suspend fun loadPersonaChatMemories(request: MemoryRefreshRequest): List<PersonaChatMemory>

    suspend fun loadPersonaChatWriteCount(
        request: MemoryRefreshRequest,
        loadedMemories: List<PersonaChatMemory>,
    ): Long = loadedMemories.sumOf(PersonaChatMemory::revision)

    suspend fun commitPersona(
        request: MemoryRefreshRequest,
        revision: Long,
        summary: String,
        updatedAt: Long,
        /** Chat-write total this synthesis saw; the cadence gate measures its distance from it. */
        chatWriteCount: Long,
    )

    suspend fun audit(
        request: MemoryRefreshRequest,
        outcome: MemoryRefreshOutcome,
        source: MemoryRefreshSource?,
        usage: TokenUsage?,
        occurredAt: Long,
    )
}

/**
 * Thin repository adapter. It reads one bounded recent page and relies on the previously
 * consolidated memory for older context, avoiding full-history scans and unbounded allocations.
 */
internal class RepositoryMemoryRefreshStore(
    private val repository: BotRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MemoryRefreshPersistence {
    override suspend fun loadIfDue(
        request: MemoryRefreshRequest,
        minimumNewMessages: Int,
        firstWriteMinimum: Int,
    ): MemoryRefreshSource =
        withContext(ioDispatcher) {
            val chatId = request.conversationKey.substringBeforeLast('#', request.conversationKey)
            val chatMemory = repository.getChatMemory(request.conversationKey)
            val required =
                if ((chatMemory?.revision ?: 0L) == 0L && minimumNewMessages > 1) {
                    maxOf(minimumNewMessages, firstWriteMinimum)
                } else {
                    minimumNewMessages
                }
            val upperBound =
                repository.countMessagesAfterMarkerAtMost(
                    chatId = chatId,
                    providerMessageId = chatMemory?.lastProviderMessageId,
                    limit = required,
                    conversationKey = request.conversationKey,
                )
            if (upperBound == null || upperBound >= required) {
                return@withContext load(request)
            }
            val personaMemory = repository.getPersonaMemory(request.personaId)
            MemoryRefreshSource(
                chatId = chatId,
                previousChatSummary = chatMemory?.summary?.take(MAX_PRIOR_SUMMARY_CHARACTERS),
                previousPersonaSummary =
                    personaMemory?.summary?.take(MAX_PRIOR_SUMMARY_CHARACTERS),
                chatRevision = chatMemory?.revision ?: 0,
                personaRevision = personaMemory?.revision ?: 0,
                personaSynthesisMarker = personaMemory?.lastChatWriteCount ?: 0,
                sourceMessageCount = chatMemory?.sourceMessageCount ?: 0,
                newMessageCount = upperBound,
                newestProviderMessageId = null,
                messages = emptyList(),
            )
        }

    override suspend fun load(request: MemoryRefreshRequest): MemoryRefreshSource =
        withContext(ioDispatcher) {
            val chatId = request.conversationKey.substringBeforeLast('#', request.conversationKey)
            val chatMemory = repository.getChatMemory(request.conversationKey)
            val personaMemory = repository.getPersonaMemory(request.personaId)
            val pendingNewestFirst = ArrayList<MessageRecord>()
            val durableMarker = chatMemory?.lastProviderMessageId
            var beforeOccurredAt: Long? = null
            var beforeDatabaseId: Long? = null
            var reachedMarker = false
            var scanned = 0
            while (scanned < MAX_BACKLOG_SCAN_ROWS) {
                val page =
                    repository.listMessages(
                        chatId = chatId,
                        beforeOccurredAt = beforeOccurredAt,
                        beforeDatabaseId = beforeDatabaseId,
                        limit = MESSAGE_SCAN_PAGE,
                    )
                if (page.isEmpty()) {
                    reachedMarker = true
                    break
                }
                for (record in page) {
                    scanned += 1
                    if (durableMarker != null && record.providerMessageId == durableMarker) {
                        reachedMarker = true
                        break
                    }
                    if (
                        record.belongsTo(request.conversationKey) &&
                        (
                            record.direction != MessageDirection.SYSTEM ||
                                record.messageType == CHAT_INJECTION_MESSAGE_TYPE ||
                                    record.messageType == SCHEDULED_FOLLOW_UP_MESSAGE_TYPE
                        )
                    ) {
                        pendingNewestFirst += record
                    }
                    if (scanned >= MAX_BACKLOG_SCAN_ROWS) break
                }
                if (reachedMarker) break
                if (page.size < MESSAGE_SCAN_PAGE) {
                    reachedMarker = true
                    break
                }
                val last = page.last()
                beforeOccurredAt = last.occurredAt
                beforeDatabaseId = last.databaseId
            }
            if (durableMarker == null && scanned >= MAX_BACKLOG_SCAN_ROWS) {
                // Per-chat retention is bounded to the same maximum.
                reachedMarker = true
            }
            if (!reachedMarker) {
                // Never jump a durable marker across unscanned history.
                throw IllegalStateException("memory marker is outside the bounded chat history")
            }
            val pendingOldestFirst =
                pendingNewestFirst
                    .asReversed()
                    .take(request.sourceMessageLimit)

            var remainingCharacters = request.sourceCharacterLimit
            val selectedOldestFirst =
                pendingOldestFirst.mapNotNull { record ->
                    if (remainingCharacters <= 0) return@mapNotNull null
                    val body =
                        record.body
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: "[${record.messageType.take(80)}]"
                    val text =
                        if (
                            chatId.endsWith("@g.us") &&
                            record.direction == MessageDirection.INBOUND
                        ) {
                            "${record.safeGroupSpeaker()}: $body"
                        } else {
                            body
                        }
                    val bounded = text.take(minOf(MAX_MESSAGE_CHARACTERS, remainingCharacters)).trim()
                    if (bounded.isEmpty()) return@mapNotNull null
                    remainingCharacters -= bounded.length
                    MemorySourceMessage(
                        providerMessageId = record.providerMessageId,
                        role =
                            when (record.direction) {
                                MessageDirection.INBOUND -> "user"
                                MessageDirection.OUTBOUND -> "assistant"
                                MessageDirection.SYSTEM -> "system"
                            },
                        text = bounded,
                        timestampMs = record.occurredAt,
                    )
                }
            val newestProviderMessageId =
                selectedOldestFirst.lastOrNull()?.providerMessageId

            MemoryRefreshSource(
                chatId = chatId,
                previousChatSummary =
                    chatMemory?.summary?.take(MAX_PRIOR_SUMMARY_CHARACTERS),
                previousPersonaSummary =
                    personaMemory?.summary?.take(MAX_PRIOR_SUMMARY_CHARACTERS),
                chatRevision = chatMemory?.revision ?: 0,
                personaRevision = personaMemory?.revision ?: 0,
                personaSynthesisMarker = personaMemory?.lastChatWriteCount ?: 0,
                sourceMessageCount =
                    (chatMemory?.sourceMessageCount ?: 0) +
                        selectedOldestFirst.count { it.role != "system" },
                newMessageCount =
                    pendingNewestFirst.count {
                        it.messageType != CHAT_INJECTION_MESSAGE_TYPE &&
                            it.messageType != SCHEDULED_FOLLOW_UP_MESSAGE_TYPE
                    },
                newestProviderMessageId = newestProviderMessageId,
                messages = selectedOldestFirst,
            )
        }

    override suspend fun commitChat(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        summary: String,
        updatedAt: Long,
    ) {
        withContext(ioDispatcher) {
            val committed =
                repository.compareAndSwapChatMemory(
                    expectedRevision = source.chatRevision,
                    memory = ChatMemoryRecord(
                    chatId = source.chatId,
                    conversationKey = request.conversationKey,
                    summary = summary,
                    lastProviderMessageId = source.newestProviderMessageId,
                    sourceMessageCount = source.sourceMessageCount,
                    revision = source.chatRevision + 1,
                    updatedAt = updatedAt,
                ),
                )
            if (!committed) throw MemoryRevisionConflictException()
        }
    }

    override suspend fun advanceChatWindow(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        updatedAt: Long,
    ) {
        withContext(ioDispatcher) {
            val advanced =
                repository.advanceChatMemoryWindow(
                    expectedRevision = source.chatRevision,
                    conversationKey = request.conversationKey,
                    chatId = source.chatId,
                    lastProviderMessageId = source.newestProviderMessageId,
                    updatedAt = updatedAt,
                )
            if (!advanced) throw MemoryRevisionConflictException()
        }
    }

    override suspend fun loadPersonaChatMemories(
        request: MemoryRefreshRequest,
    ): List<PersonaChatMemory> =
        withContext(ioDispatcher) {
            repository
                .listChatMemoriesForPersona(
                    personaId = request.personaId,
                    limit = MAX_SYNTHESIS_CHATS,
                    maxSummaryChars = MAX_SYNTHESIS_CHAT_CHARACTERS,
                )
                .map { row ->
                    PersonaChatMemory(
                        label = chatLabel(row.chatId, row.conversationKey),
                        isGroup = row.chatId.endsWith("@g.us"),
                        summary = row.summary,
                        revision = row.revision,
                    )
                }
        }

    /**
     * What the synthesis calls one chat.
     *
     * This used to be an opaque hash, on the reasoning that the raw JID of a private chat is a phone
     * number and this text ends up in a prompt injected into every chat of the persona. That traded
     * away the one thing the cross-chat memory exists for: without a name the model cannot tell that
     * the person writing in the group is the person from the private chat, so it can neither merge
     * them nor say where a fact came from. It gets the name the bot itself sees, and the number only
     * when there is no name — never both when the name already is the number.
     */
    private fun chatLabel(
        chatId: String,
        conversationKey: String,
    ): String {
        val chat = runCatching { repository.getChat(chatId) }.getOrNull()
        val name =
            listOfNotNull(chat?.subject, chat?.displayName)
                .firstNotNullOfOrNull { it.trim().takeIf(String::isNotEmpty) }
                ?.replace(SPEAKER_WHITESPACE, " ")
                ?.take(MAX_CHAT_LABEL_CHARACTERS)
        val number = phoneNumberOf(chatId, chat)
        return when {
            name == null && number == null ->
                "unknown ${Integer.toUnsignedString(conversationKey.hashCode(), 36).take(6)}"

            name == null -> number!!
            number == null || name.contains(number.trimStart('+')) -> name
            else -> "$name ($number)"
        }
    }

    /** E.164 from the chat's own address or from an alias it recorded; never an opaque LID. */
    private fun phoneNumberOf(
        chatId: String,
        chat: ChatRecord?,
    ): String? {
        val aliases =
            chat?.metadataJson
                ?.let { runCatching { JSONObject(it).optJSONArray("aliases") }.getOrNull() }
                ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it) } }
                .orEmpty()
        return (listOf(chatId) + aliases)
            .firstOrNull { it.endsWith(PHONE_JID_DOMAIN) }
            ?.substringBefore('@')
            ?.takeIf { it.length in 6..20 && it.all(Char::isDigit) }
            ?.let { "+$it" }
    }

    override suspend fun loadPersonaChatWriteCount(
        request: MemoryRefreshRequest,
        loadedMemories: List<PersonaChatMemory>,
    ): Long =
        withContext(ioDispatcher) {
            repository.sumChatMemoryRevisionsForPersona(request.personaId)
        }

    override suspend fun commitPersona(
        request: MemoryRefreshRequest,
        revision: Long,
        summary: String,
        updatedAt: Long,
        chatWriteCount: Long,
    ) {
        withContext(ioDispatcher) {
            val committed =
                repository.compareAndSwapPersonaMemory(
                    expectedRevision = revision - 1L,
                    memory =
                        PersonaMemoryRecord(
                            personaId = request.personaId,
                            summary = summary,
                            revision = revision,
                            lastChatWriteCount = chatWriteCount.coerceAtLeast(0L),
                            updatedAt = updatedAt,
                        ),
                )
            if (!committed) throw MemoryRevisionConflictException()
        }
    }

    override suspend fun auditStarted(
        request: MemoryRefreshRequest,
        source: MemoryRefreshSource,
        model: String,
        occurredAt: Long,
    ) {
        withContext(ioDispatcher) {
            repository.appendActivity(
                ActivityLogRecord(
                    occurredAt = occurredAt,
                    level = ActivityLevel.INFO,
                    category = "memory",
                    action = "refresh_started",
                    chatId = source.chatId,
                    correlationId =
                        Integer.toUnsignedString(request.conversationKey.hashCode(), 16),
                    summary =
                        "Writing memory · ${source.newMessageCount} new messages · " +
                            "condensing ${source.messages.size} · model $model" +
                            if (request.cachedPrompt != null) " · prompt reused" else "",
                    detailsJson =
                        JSONObject()
                            .put("v", 1)
                            .put("persona", request.personaId)
                            .put("newMessages", source.newMessageCount)
                            .put("includedMessages", source.messages.size)
                            .put("model", model)
                            .put("reusedTurnPrompt", request.cachedPrompt != null)
                            .put("chatRevision", source.chatRevision)
                            .put("personaRevision", source.personaRevision)
                            .toString(),
                ),
            )
        }
    }

    override suspend fun audit(
        request: MemoryRefreshRequest,
        outcome: MemoryRefreshOutcome,
        source: MemoryRefreshSource?,
        usage: TokenUsage?,
        occurredAt: Long,
    ) {
        withContext(ioDispatcher) {
            val reusedPrompt = request.cachedPrompt != null
            val cacheNote = if (reusedPrompt) " · prompt reused" else ""
            val (level, action, summary, reason) =
                when (outcome) {
                    is MemoryRefreshOutcome.Updated ->
                        AuditFields(
                            ActivityLevel.INFO,
                            "refresh_completed",
                            "Memory written · chat r${outcome.chatRevision} · " +
                                (
                                    if (outcome.personaSynthesized) {
                                        "character memory re-condensed (r${outcome.personaRevision})"
                                    } else {
                                        "character memory unchanged"
                                    }
                                ) +
                                " · ${source?.messages?.size ?: 0} messages condensed" +
                                cacheNote +
                                (usage?.cachedPromptTokens
                                    ?.takeIf { it > 0 }
                                    ?.let { " ($it tokens from cache)" }
                                    .orEmpty()),
                            "updated",
                        )

                    is MemoryRefreshOutcome.Skipped ->
                        AuditFields(
                            ActivityLevel.DEBUG,
                            "refresh_skipped",
                            if (outcome.reasonCode == "chat_memory_disabled") {
                                "History window moved on without a memory · " +
                                    "${source?.newMessageCount ?: 0} messages dropped out of " +
                                    "the window · chat memory is switched off"
                            } else {
                                "Memory unchanged · ${source?.newMessageCount ?: 0} new " +
                                    "messages are not enough yet (${outcome.reasonCode})"
                            },
                            outcome.reasonCode,
                        )

                    is MemoryRefreshOutcome.Failed ->
                        AuditFields(
                            ActivityLevel.WARN,
                            "refresh_failed",
                            "Memory refresh failed · ${outcome.reasonCode}",
                            outcome.reasonCode,
                        )
                }
            repository.appendActivity(
                ActivityLogRecord(
                    occurredAt = occurredAt,
                    level = level,
                    category = "memory",
                    action = action,
                    chatId = source?.chatId ?: request.conversationKey.substringBeforeLast('#'),
                    correlationId =
                        Integer.toUnsignedString(request.conversationKey.hashCode(), 16),
                    summary = summary,
                    detailsJson =
                        JSONObject()
                            .put("v", 1)
                            .put("persona", request.personaId)
                            .put("reason", reason)
                            .put("newMessages", source?.newMessageCount ?: 0)
                            .put("includedMessages", source?.messages?.size ?: 0)
                            .put("promptTokens", usage?.promptTokens ?: JSONObject.NULL)
                            .put("completionTokens", usage?.completionTokens ?: JSONObject.NULL)
                            .put("totalTokens", usage?.totalTokens ?: JSONObject.NULL)
                            .put("cachedPromptTokens", usage?.cachedPromptTokens ?: JSONObject.NULL)
                            .put("reusedTurnPrompt", reusedPrompt)
                            .put("model", request.cachedPrompt?.model ?: request.model)
                            .toString(),
                ),
            )
        }
    }

    private fun MessageRecord.belongsTo(conversationKey: String): Boolean {
        val metadata =
            metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return false
        val exact = metadata.stringOrNull("conversationKey")
        if (exact != null) return exact == conversationKey
        val persona = metadata.stringOrNull("persona") ?: return false
        return persona == conversationKey.substringAfterLast('#', "")
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).trim().takeIf(String::isNotEmpty)

    private fun MessageRecord.safeGroupSpeaker(): String {
        val candidate =
            metadataJson
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.stringOrNull("senderName")
                ?.replace(SPEAKER_WHITESPACE, " ")
                ?.trim()
                ?.take(MAX_SPEAKER_CHARACTERS)
                ?.takeIf {
                    '@' !in it &&
                        !PHONE_LIKE_SPEAKER.matches(it)
                }
        return candidate ?: "Teilnehmer"
    }

    private data class AuditFields(
        val level: ActivityLevel,
        val action: String,
        val summary: String,
        val reason: String,
    )

    internal companion object {
        const val MESSAGE_SCAN_PAGE = 300
        const val MAX_BACKLOG_SCAN_ROWS = 5_000

        /**
         * Sliding window the fresh summary is folded from. The previous memory always travels with
         * the request and is only added to, so this is the new window — not the whole history.
         *
         * Sized to [ConversationMemoryPolicy.MAX_COMPLETE_CHAT_WINDOW_MESSAGES] so even the largest
         * configurable window is folded whole; [MAX_SOURCE_CHARACTERS] remains the real cost cap.
         */
        const val MAX_SOURCE_MESSAGES = ConversationMemoryPolicy.MAX_COMPLETE_CHAT_WINDOW_MESSAGES
        const val MAX_SOURCE_CHARACTERS = 120_000
        const val MAX_MESSAGE_CHARACTERS = 2_000
        const val MAX_PRIOR_SUMMARY_CHARACTERS = 8_000
        const val MAX_SYNTHESIS_CHATS = 12
        const val MAX_SYNTHESIS_CHAT_CHARACTERS = 12_000
        const val MAX_SPEAKER_CHARACTERS = 80
        const val MAX_CHAT_LABEL_CHARACTERS = 60
        const val PHONE_JID_DOMAIN = "@s.whatsapp.net"
        val SPEAKER_WHITESPACE = Regex("\\s+")
        val PHONE_LIKE_SPEAKER = Regex("^\\+?[\\d .()/-]{6,}$")
    }
}

private class MemoryRevisionConflictException : IllegalStateException()
