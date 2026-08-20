package de.totec.doppel.engine

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pickup ladder: how long she takes to notice a message, as a step function of
 * how long the dot has been out. A running conversation stays instant, a chatty
 * contact can shorten a pending window but never below its floor, and the window is
 * never pushed back out.
 */
class OnlinePickupTimingTest {
    private val timing = HumanTimingPolicy(Random(7))
    private val human = EngineSettingsSnapshot()
    private val now = 1_785_000_000_000L // 2026-08-01, mid-afternoon in Berlin

    private fun plan(
        offlineForMs: Long,
        inSession: Boolean,
        chatOpen: Boolean = false,
    ) = timing.pickupPlan(
        nowMs = now,
        offlineForMs = offlineForMs,
        inSession = inSession,
        settings = human,
        chatOpen = chatOpen,
    )

    @Test
    fun `a chat she is still in is answered without any pickup wait`() {
        assertEquals(
            OnlineDelayPlan(0L, 0L, 0L),
            plan(offlineForMs = 0L, inSession = true, chatOpen = true),
        )
    }

    @Test
    fun `the first five minutes after the dot goes out cost up to two`() {
        repeat(50) {
            val plan = plan(offlineForMs = 90_000L, inSession = true)
            assertTrue("$plan", plan.delayMs in 10_000..120_000)
            assertEquals(minOf(plan.delayMs, 30_000L), plan.minDelayMs)
            assertEquals(120_000L, plan.maxDelayMs)
        }
    }

    /**
     * The reported bug: offline for a minute must not mean "read instantly". Ten seconds is the
     * floor now rather than thirty, but it is still a wait and it is still never zero.
     */
    @Test
    fun `going offline restores a real wait even seconds later`() {
        repeat(50) {
            val plan = plan(offlineForMs = 1_000L, inSession = true)
            assertTrue("$plan", plan.delayMs >= 10_000L)
        }
    }

    @Test
    fun `later in the session it is up to ten minutes`() {
        repeat(50) {
            val plan = plan(offlineForMs = 12 * 60_000L, inSession = true)
            assertTrue("$plan", plan.delayMs in 10_000..10 * 60_000)
            assertEquals(minOf(plan.delayMs, 2 * 60_000L), plan.minDelayMs)
        }
    }

    @Test
    fun `once the session is over it is never more than thirty minutes`() {
        repeat(50) {
            val plan = plan(offlineForMs = 6 * 60 * 60_000L, inSession = false)
            assertTrue("$plan", plan.delayMs in 10_000..30 * 60_000)
            assertEquals(minOf(plan.delayMs, 10 * 60_000L), plan.minDelayMs)
        }
    }

    /**
     * The bands overlap at the bottom by design and separate at the top.
     *
     * They used to be laid end to end, which made the floor of a band carry the meaning that now
     * belongs to its peak: every band can produce a ten-second answer because she might have been
     * looking, and what says how far away the phone is, is the ceiling and the shape underneath it.
     */
    @Test
    fun `the ceilings climb even though the floors are shared`() {
        val justOff = plan(offlineForMs = 60_000L, inSession = true)
        val inSession = plan(offlineForMs = 12 * 60_000L, inSession = true)
        val away = plan(offlineForMs = 3 * 60 * 60_000L, inSession = false)
        assertTrue(justOff.maxDelayMs < inSession.maxDelayMs)
        assertTrue(inSession.maxDelayMs < away.maxDelayMs)
    }

    @Test
    fun `instant preset and interruptions skip the pickup entirely`() {
        val instant =
            timing.pickupPlan(
                nowMs = now,
                offlineForMs = 8 * 60 * 60_000L,
                inSession = false,
                settings = EngineSettingsSnapshot(replyPreset = ReplyPreset.INSTANT),
            )
        assertEquals(OnlineDelayPlan(0L, 0L, 0L), instant)

        val interrupted =
            timing.pickupPlan(
                nowMs = now,
                offlineForMs = 8 * 60 * 60_000L,
                inSession = false,
                settings = human,
                immediate = true,
            )
        assertEquals(OnlineDelayPlan(0L, 0L, 0L), interrupted)
    }

    @Test
    fun `a follow-up message only shortens the pending wait`() {
        var rest = 30 * 60_000L
        val floor = 5 * 60_000L
        repeat(5) {
            val next = timing.reduceOnlineDelay(rest, floor)
            assertTrue("$next > $rest", next <= rest)
            rest = next
        }
        assertTrue("$rest", rest >= floor)
    }

    @Test
    fun `shortening stops at the human floor`() {
        // No cold floor: the wait may drop to seconds, but never to zero — she
        // still has to pick the phone up.
        var rest = 20_000L
        repeat(10) { rest = timing.reduceOnlineDelay(rest, 0L) }
        assertTrue("$rest", rest >= 3_000)
    }

    @Test
    fun `self sessions land hours apart and last well under a minute`() {
        repeat(50) {
            val gap = timing.selfSessionGapMs()
            assertTrue("$gap", gap in 3 * 60 * 60_000L..5 * 60 * 60_000L)
            val online = timing.selfSessionOnlineMs()
            assertTrue("$online", online in 25_000L..35_000L)
        }
    }

    @Test
    fun `session opens on reading and only sending advances the away clock`() {
        val clock = MutableClock(now)
        val session = OnlineSessionClock(clock)

        session.seed(now - 2 * 60 * 60_000)
        assertFalse(session.inSession(now))
        assertEquals(now - 2 * 60 * 60_000, session.lastOnlineAt())

        session.noteCameOnline(now)
        assertTrue(session.inSession(now))
        // Reading does not count as having sent something.
        assertEquals(now - 2 * 60 * 60_000, session.lastOnlineAt())

        session.noteOnline(now + 1_000)
        assertEquals(now + 1_000, session.lastOnlineAt())
        assertTrue(session.inSession(now + 29 * 60_000))
        assertFalse(session.inSession(now + 31 * 60_000))
    }

    @Test
    fun `a stale seed does not resurrect an ended session`() {
        val clock = MutableClock(now)
        val session = OnlineSessionClock(clock)
        session.seed(now - OnlineSessionClock.SESSION_LINGER_MS - 1)
        assertFalse(session.inSession(now))
    }

    /**
     * The two clocks that used to disagree. While the dot is on, the chat she is in
     * is open and nothing has been "offline" for any length of time; the moment it
     * goes out, the ladder starts counting from there.
     */
    @Test
    fun `the open chat follows the dot and nothing else`() {
        val clock = MutableClock(now)
        val session = OnlineSessionClock(clock)

        session.noteWentOnline("chat@s.whatsapp.net", now)
        assertTrue(session.isChatOpen("chat@s.whatsapp.net", now))
        assertFalse(session.isChatOpen("someone-else@s.whatsapp.net", now))
        assertEquals(0L, session.offlineForMs(now + 5 * 60_000))

        session.noteWentOffline(now + 20_000)
        assertFalse(session.isChatOpen("chat@s.whatsapp.net", now + 21_000))
        assertEquals(60_000L, session.offlineForMs(now + 80_000))
    }

    /** A self-initiated session has no chat, so every chat counts as in front of her. */
    @Test
    fun `an online window with no chat opens all of them`() {
        val session = OnlineSessionClock(MutableClock(now))
        session.noteWentOnline(null, now)
        assertTrue(session.isChatOpen("anyone@s.whatsapp.net", now))
        assertTrue(session.isChatOpen("someone-else@s.whatsapp.net", now))
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
