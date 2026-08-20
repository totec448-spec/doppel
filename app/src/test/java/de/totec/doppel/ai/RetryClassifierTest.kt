package de.totec.doppel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RetryClassifierTest {
    @Test
    fun `only transient HTTP statuses retry`() {
        listOf(408, 429, 500, 502, 599).forEach {
            assertTrue("Expected $it to retry", RetryClassifier.isRetryableStatus(it))
        }
        listOf(200, 301, 400, 401, 403, 404, 409, 422, 600).forEach {
            assertFalse("Expected $it not to retry", RetryClassifier.isRetryableStatus(it))
        }
    }

    @Test
    fun `network IO and transient protocol envelopes retry but deterministic limits do not`() {
        assertTrue(RetryClassifier.isRetryableThrowable(IOException("offline")))
        assertTrue(
            RetryClassifier.isRetryableThrowable(
                OpenRouterHttpException(503, null, null),
            ),
        )
        assertFalse(
            RetryClassifier.isRetryableThrowable(
                OpenRouterHttpException(400, null, null),
            ),
        )
        assertTrue(
            RetryClassifier.isRetryableThrowable(
                OpenRouterProtocolException("invalid_json"),
            ),
        )
        assertTrue(
            RetryClassifier.isRetryableThrowable(
                OpenRouterProtocolException("response_too_large"),
            ),
        )
        assertFalse(
            RetryClassifier.isRetryableThrowable(
                OpenRouterProtocolException("response_too_large"),
                responseStarted = true,
            ),
        )
        listOf(
            "request_too_large",
            "content_too_large",
            "too_many_tool_calls",
            "tool_arguments_too_large",
            "missing_tool_name",
        ).forEach { reason ->
            assertFalse(
                "Expected $reason not to retry",
                RetryClassifier.isRetryableThrowable(OpenRouterProtocolException(reason)),
            )
        }
        assertFalse(RetryClassifier.isRetryableThrowable(IllegalStateException()))
    }

    /**
     * The line the retry budget is not allowed to cross: once the provider has answered, the answer
     * is billed, and asking again buys a second bill for work that was already paid for.
     */
    @Test
    fun `a transport failure after the response began is not retried`() {
        assertTrue(
            RetryClassifier.isRetryableThrowable(IOException("connect timed out"), responseStarted = false),
        )
        assertFalse(
            RetryClassifier.isRetryableThrowable(IOException("stream closed"), responseStarted = true),
        )
    }

    /**
     * A stall is silence, and silence only means "nothing has started" when tokens were supposed to
     * arrive as they are produced. A buffered request is silent for exactly as long as it takes to
     * generate the whole answer, so its stall can never be told apart from a completion in flight.
     */
    @Test
    fun `only a streaming stall that produced nothing is retried`() {
        assertTrue(
            RetryClassifier.isRetryableThrowable(
                OpenRouterStallException(30_000),
                responseStarted = false,
                streaming = true,
            ),
        )
        assertFalse(
            RetryClassifier.isRetryableThrowable(
                OpenRouterStallException(30_000),
                responseStarted = true,
                streaming = true,
            ),
        )
        assertFalse(
            RetryClassifier.isRetryableThrowable(
                OpenRouterStallException(30_000),
                responseStarted = false,
                streaming = false,
            ),
        )
    }

    /**
     * An HTTP error status is what came back *instead* of a completion, so there is nothing billed
     * to duplicate — it stays retryable even though the response had already started. Envelope
     * faults keep their single re-issue for the same reason; the completion behind them is lost.
     */
    @Test
    fun `an error response and a broken envelope stay retryable once the response started`() {
        assertTrue(
            RetryClassifier.isRetryableThrowable(
                OpenRouterHttpException(503, null, null),
                responseStarted = true,
            ),
        )
        assertTrue(
            RetryClassifier.isRetryableThrowable(
                OpenRouterProtocolException("invalid_sse_json"),
                responseStarted = true,
                streaming = true,
            ),
        )
        assertFalse(
            RetryClassifier.isRetryableThrowable(
                OpenRouterHttpException(400, null, null),
                responseStarted = true,
            ),
        )
    }

    /** The whole budget: one attempt, then at most two more. */
    @Test
    fun `the shipped policy allows exactly two additional attempts`() {
        assertEquals(3, RetryPolicy().maxAttempts)
    }

    @Test
    fun `retry after accepts seconds and RFC date`() {
        assertEquals(1_500L, RetryClassifier.retryAfterMillis("1.5"))
        assertNull(RetryClassifier.retryAfterMillis("-2"))
        assertNull(RetryClassifier.retryAfterMillis("not-a-date"))

        val now = Instant.parse("2026-07-30T10:00:00Z")
        val later = ZonedDateTime.ofInstant(now.plusSeconds(12), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(
            12_000L,
            RetryClassifier.retryAfterMillis(later, now.toEpochMilli()),
        )
    }
}
