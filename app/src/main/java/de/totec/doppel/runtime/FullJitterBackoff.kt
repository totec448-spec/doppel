package de.totec.doppel.runtime

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with full jitter.
 *
 * Full jitter deliberately permits a delay of zero. Across many clients this
 * spreads reconnects over the complete [0, exponential ceiling] window instead
 * of making every disconnected phone retry at the same instant.
 */
data class FullJitterBackoff(
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 5 * 60_000L,
) {
    init {
        require(baseDelayMs > 0L) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) {
            "maxDelayMs must be greater than or equal to baseDelayMs"
        }
    }

    /**
     * Attempt zero uses [baseDelayMs], attempt one doubles it, and so on until
     * [maxDelayMs]. The loop stops once capped, so even a corrupted huge
     * attempt count cannot cause expensive iteration or numeric overflow.
     */
    fun ceilingForAttempt(attempt: Int): Long {
        require(attempt >= 0) { "attempt must not be negative" }

        var ceiling = baseDelayMs
        var remainingDoublings = attempt
        while (remainingDoublings > 0 && ceiling < maxDelayMs) {
            ceiling = if (ceiling > maxDelayMs / 2L) {
                maxDelayMs
            } else {
                min(maxDelayMs, ceiling * 2L)
            }
            remainingDoublings--
        }
        return ceiling
    }

    /**
     * Deterministic sampling hook used by JVM tests. [unitSample] represents a
     * point in the inclusive unit interval.
     */
    fun delayForAttempt(attempt: Int, unitSample: Double): Long {
        require(unitSample.isFinite() && unitSample in 0.0..1.0) {
            "unitSample must be finite and between zero and one"
        }
        val ceiling = ceilingForAttempt(attempt)
        return if (unitSample == 1.0) ceiling else (ceiling * unitSample).toLong()
    }

    fun nextDelay(attempt: Int, random: Random = Random.Default): Long {
        val ceiling = ceilingForAttempt(attempt)
        return if (ceiling == Long.MAX_VALUE) {
            // Masking the sign bit maps all 64-bit values uniformly into the
            // inclusive non-negative Long range without overflowing max + 1.
            random.nextLong().and(Long.MAX_VALUE)
        } else {
            random.nextLong(until = ceiling + 1L)
        }
    }
}
