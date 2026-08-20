package de.totec.doppel.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullJitterBackoffTest {
    private val backoff = FullJitterBackoff(
        baseDelayMs = 1_000L,
        maxDelayMs = 5_000L,
    )

    @Test
    fun ceilingDoublesAndCapsWithoutOverflow() {
        assertEquals(1_000L, backoff.ceilingForAttempt(0))
        assertEquals(2_000L, backoff.ceilingForAttempt(1))
        assertEquals(4_000L, backoff.ceilingForAttempt(2))
        assertEquals(5_000L, backoff.ceilingForAttempt(3))
        assertEquals(5_000L, backoff.ceilingForAttempt(Int.MAX_VALUE))
    }

    @Test
    fun deterministicSampleCoversCompleteJitterWindow() {
        assertEquals(0L, backoff.delayForAttempt(2, 0.0))
        assertEquals(1_000L, backoff.delayForAttempt(2, 0.25))
        assertEquals(4_000L, backoff.delayForAttempt(2, 1.0))
    }

    @Test
    fun randomDelayAlwaysStaysInsideAttemptCeiling() {
        repeat(250) {
            val value = backoff.nextDelay(attempt = 2)
            assertTrue(value in 0L..4_000L)
        }
    }

    @Test
    fun maximumLongCeilingCanBeSampledWithoutOverflow() {
        val maximumBackoff = FullJitterBackoff(
            baseDelayMs = Long.MAX_VALUE,
            maxDelayMs = Long.MAX_VALUE,
        )

        repeat(250) {
            assertTrue(maximumBackoff.nextDelay(attempt = Int.MAX_VALUE) >= 0L)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeAttemptIsRejected() {
        backoff.ceilingForAttempt(-1)
    }
}
