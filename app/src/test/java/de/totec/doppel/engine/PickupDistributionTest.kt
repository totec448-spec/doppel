package de.totec.doppel.engine

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The *shape* of the pickup, which is the part the bounds cannot check.
 *
 * A band is no longer described by its two edges — every one of them now starts at the same ten
 * seconds — so "is the draw inside the range" says almost nothing about whether a cold contact
 * still waits twenty minutes. What separates the bands is where the mass sits, and that only shows
 * up over a few thousand draws. Each case below rolls the real policy and measures the result
 * rather than re-deriving the formula, so a change to the constants that ruins the feel fails here
 * instead of on someone's phone.
 */
class PickupDistributionTest {
    private val human = EngineSettingsSnapshot()

    /** Mid-afternoon in Berlin: no sleep window anywhere near the draws. */
    private val now = 1_785_000_000_000L

    private val second = 1_000L
    private val minute = 60 * second

    private fun rolls(
        count: Int,
        offlineForMs: Long,
        inSession: Boolean,
        heat: Double = 0.0,
        cooling: Boolean = false,
        seed: Long = 20260813L,
    ): List<Long> {
        val timing = HumanTimingPolicy(Random(seed))
        return List(count) {
            timing.pickupPlan(
                nowMs = now,
                offlineForMs = offlineForMs,
                inSession = inSession,
                settings = human,
                tempo = ExchangeTempo(heat = heat, cooling = cooling),
            ).delayMs
        }
    }

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private fun List<Long>.share(predicate: (Long) -> Boolean): Double =
        count(predicate).toDouble() / size

    /**
     * A readable picture of one band, printed so a failure says what the distribution actually
     * looked like instead of only which assertion tripped.
     */
    private fun report(
        label: String,
        draws: List<Long>,
        bucketMs: Long,
    ) {
        val buckets = draws.groupingBy { it / bucketMs }.eachCount()
        val peak = buckets.values.max()
        println("--- $label (n=${draws.size}, median ${draws.median() / second}s) ---")
        buckets.toSortedMap().forEach { (bucket, hits) ->
            val bar = "#".repeat((hits * 40 / peak).coerceAtLeast(if (hits > 0) 1 else 0))
            println(String.format("%6ds %-40s %d", bucket * bucketMs / second, bar, hits))
        }
    }

    @Test
    fun `the just-offline band peaks near a minute and never exceeds two`() {
        val draws = rolls(count = 3_000, offlineForMs = 90 * second, inSession = true)
        report("just offline", draws, bucketMs = 10 * second)

        assertTrue("min ${draws.min()}", draws.min() >= 10 * second)
        assertTrue("max ${draws.max()}", draws.max() <= 2 * minute)
        // Peak at 75 s puts the median just under 70 s.
        assertTrue("median ${draws.median()}", draws.median() in (55 * second)..(85 * second))
    }

    @Test
    fun `the in-session band still typically costs minutes`() {
        val draws = rolls(count = 3_000, offlineForMs = 12 * minute, inSession = true)
        report("in session", draws, bucketMs = minute)

        assertTrue("max ${draws.max()}", draws.max() <= 10 * minute)
        assertTrue("median ${draws.median()}", draws.median() in (4 * minute)..(7 * minute))
        // The fast tail is present but is not what usually happens.
        assertTrue("under a minute: ${draws.share { it < minute }}", draws.share { it < minute } < 0.06)
    }

    /**
     * The band that matters most for how the account reads from outside: a contact it has not
     * spoken to in hours still waits about twenty minutes, and the occasional near-instant answer
     * has to stay occasional.
     */
    @Test
    fun `the away band keeps its twenty-minute centre and only rarely answers fast`() {
        val draws = rolls(count = 5_000, offlineForMs = 6 * 60 * minute, inSession = false)
        report("away", draws, bucketMs = 2 * minute)

        assertTrue("max ${draws.max()}", draws.max() <= 30 * minute)
        assertTrue("median ${draws.median()}", draws.median() in (15 * minute)..(20 * minute))

        val fast = draws.share { it < 2 * minute }
        assertTrue("fast share $fast", fast > 0.0)
        assertTrue("fast share $fast", fast < 0.02)
    }

    /**
     * Heat is the whole point of the rework: the same band, the same ceiling, a peak that has moved
     * because the last reply went out seconds ago.
     */
    @Test
    fun `heat moves the peak down without touching the ceiling`() {
        val cold = rolls(count = 3_000, offlineForMs = 12 * minute, inSession = true, heat = 0.0)
        val hot = rolls(count = 3_000, offlineForMs = 12 * minute, inSession = true, heat = 1.0)
        report("in session, hot", hot, bucketMs = minute)

        assertTrue("hot max ${hot.max()}", hot.max() <= 10 * minute)
        assertTrue(
            "cold ${cold.median()} vs hot ${hot.median()}",
            hot.median() < cold.median() / 2,
        )
        assertTrue("hot median ${hot.median()}", hot.median() < 3 * minute)
    }

    /** Half heat lands between the two, so the peak slides rather than switching. */
    @Test
    fun `the peak slides continuously with heat`() {
        val medians =
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { heat ->
                heat to rolls(count = 2_000, offlineForMs = 12 * minute, inSession = true, heat = heat).median()
            }
        println("heat -> median: " + medians.joinToString { "${it.first}=${it.second / second}s" })
        medians.zipWithNext().forEach { (lower, higher) ->
            assertTrue("$lower then $higher", higher.second < lower.second)
        }
    }

    @Test
    fun `cooling refuses the fast floor entirely`() {
        val draws = rolls(count = 2_000, offlineForMs = 0L, inSession = true, cooling = true)
        report("cooling", draws, bucketMs = 2 * minute)

        assertTrue("min ${draws.min()}", draws.min() >= 5 * minute)
        assertTrue("max ${draws.max()}", draws.max() <= 30 * minute)
        assertTrue("median ${draws.median()}", draws.median() >= 15 * minute)
    }

    /**
     * Twenty draws per level, which is the sample an operator would actually eyeball, checked for
     * the one property that has to survive at that size: the levels stay ordered.
     */
    @Test
    fun `a twenty-draw sample already separates the levels`() {
        repeat(20) { seed ->
            val justOff = rolls(20, 90 * second, inSession = true, seed = seed.toLong()).median()
            val inSession = rolls(20, 12 * minute, inSession = true, seed = seed.toLong()).median()
            val away = rolls(20, 6 * 60 * minute, inSession = false, seed = seed.toLong()).median()
            assertTrue("seed $seed: $justOff / $inSession / $away", justOff < inSession)
            assertTrue("seed $seed: $justOff / $inSession / $away", inSession < away)
        }
    }

    /**
     * The runaway the whole cooldown exists for.
     *
     * Two accounts on this policy answering each other as fast as it allows: each reply leaves the
     * other in the just-offline band, which keeps its heat at one, which keeps both peaks at the
     * fast end. Nothing in the delay model breaks that loop, because nothing in it is supposed to —
     * the exchange ends when the stamina behind it runs out, and this is the test that it does.
     */
    @Test
    fun `two accounts answering each other are forced apart within the hour`() {
        val session = OnlineSessionClock(MutableClock(now), Random(3))
        val timing = HumanTimingPolicy(Random(4))

        var t = now
        var lastSend = now
        var cooledAt: Long? = null
        session.noteOnline(t)

        repeat(2_000) {
            val tempo = session.exchangeTempo(t)
            if (tempo.cooling && cooledAt == null) cooledAt = t
            val plan =
                timing.pickupPlan(
                    nowMs = t,
                    offlineForMs = t - lastSend,
                    inSession = session.inSession(t),
                    settings = human,
                    tempo = tempo,
                )
            t += plan.delayMs
            session.noteOnline(t)
            lastSend = t
        }

        val cooled = cooledAt
        assertTrue("the exchange never cooled off", cooled != null)
        val ranFor = cooled!! - now
        println("burst ran for ${ranFor / minute} minutes before the phone went down")
        assertTrue("ran for ${ranFor / minute} min", ranFor <= OnlineSessionClock.BURST_STAMINA_MAX_MS + 5 * minute)
    }

    /** Once the break is over the account is allowed to be fast again. */
    @Test
    fun `the cooldown expires instead of latching`() {
        val clock = MutableClock(now)
        val session = OnlineSessionClock(clock, Random(9))

        // Fifty minutes of steady back-and-forth: past the longest stamina the burst can draw
        // (35 min), and still short of the shortest cooldown it can draw afterwards (45 min), so
        // the account is provably inside the break at the end of the loop rather than by luck.
        var t = now
        repeat(100) {
            session.noteOnline(t)
            t += 30 * second
        }
        assertTrue("never cooled", session.exchangeTempo(t).cooling)

        val past = t + OnlineSessionClock.COOLDOWN_MAX_MS + minute
        assertTrue("still cooling", !session.exchangeTempo(past).cooling)
    }

    private class MutableClock(
        var nowMs: Long,
    ) : EngineClock {
        override fun wallTimeMillis(): Long = nowMs

        override suspend fun delay(millis: Long) {
            nowMs += millis
        }
    }
}
