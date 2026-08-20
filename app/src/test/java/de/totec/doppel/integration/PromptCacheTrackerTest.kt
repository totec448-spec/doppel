package de.totec.doppel.integration

import de.totec.doppel.ai.AiNetworkEvent
import de.totec.doppel.ai.PromptFingerprint
import de.totec.doppel.ai.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2: the log has to say *why* a call paid full price. Token counts alone cannot distinguish
 * "we sent a different prompt" from "the provider ignored an identical one", and that difference
 * decides whether the fix is in this code base or in the routing.
 */
class PromptCacheTrackerTest {
    private val tracker = PromptCacheTracker()

    @Test
    fun `a reported cache read is a hit`() {
        assertEquals("hit", tracker.classify(succeeded(prefix = "aaa", cached = 1_280)))
    }

    @Test
    fun `the first call of a call site is cold rather than a break`() {
        assertEquals("cold", tracker.classify(succeeded(prefix = "aaa", cached = 0)))
    }

    /** The bug the fingerprint exists for: something volatile slipped in front of the boundary. */
    @Test
    fun `a changed prefix is named as our own break`() {
        tracker.classify(succeeded(prefix = "aaa", cached = 0))

        assertEquals("prefix_changed", tracker.classify(succeeded(prefix = "bbb", cached = 0)))
    }

    /** Byte-identical prefix, still charged: not a prompt bug, so it must not be reported as one. */
    @Test
    fun `an identical prefix that still misses is blamed on the provider`() {
        tracker.classify(succeeded(prefix = "aaa", cached = 0))

        assertEquals("provider_miss", tracker.classify(succeeded(prefix = "aaa", cached = 0)))
    }

    @Test
    fun `missing information is never guessed`() {
        assertEquals("unknown", tracker.classify(succeeded(prompt = null)))
        assertEquals("not_requested", tracker.classify(succeeded(prefix = null, cached = 0)))
        assertEquals("not_reported", tracker.classify(succeeded(prefix = "aaa", usage = TokenUsage())))
        assertEquals("not_reported", tracker.classify(succeeded(prefix = "aaa", usage = null)))
    }

    /**
     * A turn and a verification interleave constantly and share no prompt. Tracking them under one
     * key would report a break on literally every call — exactly the noise that makes a diagnostic
     * useless.
     */
    @Test
    fun `interleaved call sites do not report each other as breaks`() {
        tracker.classify(succeeded(prefix = "turn-1", tag = "turn", cached = 0))
        tracker.classify(succeeded(prefix = "verify-1", tag = "verify", cached = 0))

        assertEquals(
            "provider_miss",
            tracker.classify(succeeded(prefix = "turn-1", tag = "turn", cached = 0)),
        )
        assertEquals(
            "provider_miss",
            tracker.classify(succeeded(prefix = "verify-1", tag = "verify", cached = 0)),
        )
    }

    @Test
    fun `a switched model is its own call site`() {
        tracker.classify(succeeded(prefix = "aaa", model = "deepseek/deepseek-v4-pro", cached = 0))

        assertEquals(
            "cold",
            tracker.classify(succeeded(prefix = "aaa", model = "google/gemini-3.5-flash-lite", cached = 0)),
        )
    }

    private fun succeeded(
        prefix: String? = "aaa",
        cached: Int? = null,
        usage: TokenUsage? = TokenUsage(promptTokens = 2_672, completionTokens = 48, cachedPromptTokens = cached),
        prompt: PromptFingerprint? = PromptFingerprint(
            promptHash = "prompt-hash",
            prefixHash = prefix,
            prefixCharacters = if (prefix == null) 0 else 4_096,
            totalCharacters = 8_192,
            messageCount = 12,
        ),
        tag: String? = "turn",
        model: String? = "deepseek/deepseek-v4-pro",
    ) = AiNetworkEvent.Succeeded(
        operation = "chat_completion",
        statusCode = 200,
        attempt = 1,
        model = model,
        elapsedMs = 900L,
        requestTag = tag,
        prompt = prompt,
        usage = usage,
        finishReason = "stop",
    )
}
