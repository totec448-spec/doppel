package de.totec.doppel.ai

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Where the model's own words go while they are still arriving.
 *
 * The chain of thought is billed as output tokens whether it is asked for or not, so
 * `reasoning.exclude` never saved a cent — it only threw away the one part of the turn that
 * explains why the answer looks the way it does. It is asked for now, and this is the one-way
 * street it runs down: the client pushes each piece off the SSE reader as it is parsed, and
 * whoever installed the sink decides where it lands.
 *
 * It travels in the coroutine context rather than in a parameter on purpose. [ChatCompletionGateway]
 * is a `fun interface`, SAM-converted by roughly forty test lambdas; a parameter — even a defaulted
 * one — would break every one of them for a concern none of them have. Ambient per-call state is
 * exactly what a context element is for, and a caller that installs nothing gets today's behaviour
 * unchanged.
 *
 * Chunks arrive a token at a time. Publishing every one straight through would mean a UI diff per
 * token for the length of a reasoning stream, so the sink batches: text waits until there is enough
 * of it to be worth reading or until the pause is long enough to be noticed, whichever comes first.
 */
abstract class LiveTokenSink : AbstractCoroutineContextElement(LiveTokenSink) {
    companion object Key : CoroutineContext.Key<LiveTokenSink> {
        /** About a line of text: below this a batch is not worth a recomposition. */
        private const val MIN_BATCH_CHARACTERS = 48

        /** …unless the model has gone quiet for this long, in which case show what there is. */
        private const val MIN_BATCH_MILLIS = 120L
    }

    private val pendingReasoning = StringBuilder()
    private val pendingContent = StringBuilder()
    private var lastReasoningEmitMs = 0L
    private var lastContentEmitMs = 0L

    /**
     * A piece of reasoning, exactly as it came off the wire.
     *
     * The first chunk of a stream always goes straight through: the whole value of the field is
     * that it says "something is happening" before the answer exists.
     */
    @Synchronized
    fun reasoning(chunk: String, nowMs: Long) {
        if (chunk.isEmpty()) return
        pendingReasoning.append(chunk)
        if (
            pendingReasoning.length < MIN_BATCH_CHARACTERS &&
            nowMs - lastReasoningEmitMs < MIN_BATCH_MILLIS
        ) return
        lastReasoningEmitMs = nowMs
        drainReasoning()
    }

    /** A piece of the final model answer, separate from private reasoning. */
    @Synchronized
    fun content(chunk: String, nowMs: Long) {
        if (chunk.isEmpty()) return
        pendingContent.append(chunk)
        if (
            pendingContent.length < MIN_BATCH_CHARACTERS &&
            nowMs - lastContentEmitMs < MIN_BATCH_MILLIS
        ) return
        lastContentEmitMs = nowMs
        drainContent()
    }

    /** The stream ended — nothing may be left sitting in the buffer. */
    @Synchronized
    fun flush() {
        drainReasoning()
        drainContent()
    }

    /** Publish private reasoning. Called from the reader thread, never concurrently with itself. */
    protected abstract fun emitReasoning(text: String)

    /** Publish final response text. Most callers do not need it, so the default is intentionally quiet. */
    protected open fun emitContent(text: String) = Unit

    private fun drainReasoning() {
        if (pendingReasoning.isEmpty()) return
        val text = pendingReasoning.toString()
        pendingReasoning.setLength(0)
        emitReasoning(text)
    }

    private fun drainContent() {
        if (pendingContent.isEmpty()) return
        val text = pendingContent.toString()
        pendingContent.setLength(0)
        emitContent(text)
    }
}
