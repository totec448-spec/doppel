package de.totec.doppel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The link power model, from the outside: given a clock and what the engine is doing,
 * is the socket allowed to drop and when does it have to be back.
 *
 * Every case here is one the device cannot be asked about — 03:00 with a conversation
 * still running, a phone that woke up ninety minutes late — which is exactly why the
 * decision was kept pure.
 */
class LinkPowerPolicyTest {
    private val policy = LinkPowerPolicy()
    private val berlin = ZoneId.of("Europe/Berlin")
    private val low = EngineSettingsSnapshot(powerMode = PowerMode.LOW)
    private val default = EngineSettingsSnapshot(powerMode = PowerMode.DEFAULT)

    private fun at(
        hour: Int,
        minute: Int = 0,
    ): Long =
        ZonedDateTime.of(2026, 8, 1, hour, minute, 0, 0, berlin).toInstant().toEpochMilli()

    /**
     * The one rule that outranks every schedule. A socket dropped mid-turn saves no
     * measurable battery and costs half a reply.
     */
    @Test
    fun aRunningTurnIsNeverCutOff() {
        val nightMidTurn =
            policy.plan(
                nowMs = at(3),
                settings = low,
                activity = LinkActivity(busy = true),
            )
        assertEquals(LinkState.AWAKE, nightMidTurn.state)
        assertNull(nightMidTurn.wakeAtMs)
    }

    /**
     * ...but a turn is not a reason to *raise* a link, only a reason not to drop one.
     *
     * Unqualified, this rule handed the engine's scheduler a veto over the power model:
     * `busy` goes true the moment a turn starts, so a reply whose drawn delay came due
     * inside a doze pulled the socket up and sent — hours before the time on the status
     * line, and straight through a long press that had just asked for the opposite. The
     * engine waits for the link now, so a turn can no longer be running while it is down.
     */
    @Test
    fun aTurnDoesNotRaiseALinkThatIsAlreadyDown() {
        val plan =
            policy.plan(
                nowMs = at(15),
                settings = low,
                activity = LinkActivity(busy = true, nextSessionAtMs = at(17)),
                linkIsUp = false,
            )
        assertEquals(LinkState.DOZING, plan.state)
        assertEquals(at(17), plan.wakeAtMs)
    }

    /** The quiet hours are behaviour, not a power setting: default mode sleeps too. */
    @Test
    fun bothModesGoOfflineDuringTheQuietHours() {
        listOf(default, low).forEach { settings ->
            val plan = policy.plan(at(3), settings, LinkActivity())
            assertEquals(LinkState.SLEEPING, plan.state)
            assertTrue("a sleeping link must name its own way back", plan.wakeAtMs!! > at(3))
        }
    }

    @Test
    fun anExactFollowUpArmsAndOpensOneSessionEvenDuringSleep() {
        val now = at(3)
        val due = now + 20 * 60_000L
        val waiting =
            policy.plan(
                nowMs = now,
                settings = low,
                activity = LinkActivity(scheduledFollowUpAtMs = due),
            )
        assertEquals(LinkState.SLEEPING, waiting.state)
        assertEquals(due, waiting.wakeAtMs)

        val opened =
            policy.plan(
                nowMs = due,
                settings = low,
                activity = LinkActivity(scheduledFollowUpAtMs = due),
                linkIsUp = false,
            )
        assertEquals(LinkState.AWAKE, opened.state)
        assertTrue(opened.sessionUntilMs > due)
    }

    /**
     * Instant is a promise about latency, and a link that is down at three in the
     * morning cannot keep it. It outranks both the quiet hours and a stored low mode.
     */
    @Test
    fun theInstantPresetNeverSleepsOrDozes() {
        val instantLow = low.copy(replyPreset = ReplyPreset.INSTANT)
        val night = policy.plan(at(3), instantLow, LinkActivity())
        assertEquals(LinkState.AWAKE, night.state)
        assertNull(night.wakeAtMs)

        val betweenSessions =
            policy.plan(
                nowMs = at(14),
                settings = instantLow,
                activity = LinkActivity(nextSessionAtMs = at(17)),
            )
        assertEquals(LinkState.AWAKE, betweenSessions.state)
    }

    /** The stored choice is kept; instant only outranks it while it is selected. */
    @Test
    fun instantOverridesLowModeWithoutErasingIt() {
        val instantLow = low.copy(replyPreset = ReplyPreset.INSTANT)
        assertEquals(PowerMode.DEFAULT, instantLow.effectivePowerMode)
        assertEquals(PowerMode.LOW, instantLow.powerMode)
        assertEquals(PowerMode.LOW, instantLow.copy(replyPreset = ReplyPreset.HUMAN).effectivePowerMode)
    }

    /**
     * The morning is drawn once and carried, or the armed alarm and the time on the
     * status line would disagree by up to two hours — see [HumanTimingPolicy.sleepWakeInMs].
     */
    @Test
    fun theWakeInstantSurvivesRepeatedPlanning() {
        val now = at(3)
        val drawn = policy.plan(now, low, LinkActivity()).wakeAtMs
        assertNotNull(drawn)

        repeat(20) {
            val again = policy.plan(now + 1, low, LinkActivity(), sleepWakeAtMs = drawn!!)
            assertEquals(drawn, again.wakeAtMs)
        }
    }

    /** A carried instant that has passed is stale, and drawing a fresh one is the point. */
    @Test
    fun aWakeInstantInThePastIsRedrawn() {
        val now = at(3)
        val plan = policy.plan(now, low, LinkActivity(), sleepWakeAtMs = now - 60_000L)
        assertTrue(plan.wakeAtMs!! > now)
    }

    /** Tapping the status line beats the quiet hours — the operator asked. */
    @Test
    fun aManualWakeOutranksTheQuietHours() {
        val now = at(3)
        val until = now + 10 * 60_000L
        val plan = policy.plan(now, low, LinkActivity(), manualWakeUntilMs = until)
        assertEquals(LinkState.AWAKE, plan.state)
        assertEquals(until, plan.wakeAtMs)

        // And it lapses on its own rather than pinning the link for the rest of the night.
        assertEquals(
            LinkState.SLEEPING,
            policy.plan(until, low, LinkActivity(), manualWakeUntilMs = until).state,
        )
    }

    /** Default mode is the old behaviour exactly: up all day, no schedule of its own. */
    @Test
    fun defaultModeStaysConnectedOutsideTheQuietHours() {
        val plan = policy.plan(at(15), default, LinkActivity(nextSessionAtMs = at(18)))
        assertEquals(LinkState.AWAKE, plan.state)
        assertNull(plan.wakeAtMs)
    }

    /**
     * The tail of a conversation. The contact reads, types and sends, and none of that
     * reaches a disconnected client — so the link outlives the answer by minutes.
     */
    @Test
    fun lowModeStaysUpWhileSomeoneIsStillTalking() {
        val now = at(15)
        val listenUntil = now + 5 * 60_000L
        val plan =
            policy.plan(
                now,
                low,
                LinkActivity(listenUntilMs = listenUntil, nextSessionAtMs = at(18)),
            )
        assertEquals(LinkState.AWAKE, plan.state)
        assertEquals(listenUntil, plan.wakeAtMs)
    }

    @Test
    fun lowModeDozesBetweenSessionsAndNamesTheNextOne() {
        val now = at(15)
        val next = at(17)
        val plan = policy.plan(now, low, LinkActivity(nextSessionAtMs = next))
        assertEquals(LinkState.DOZING, plan.state)
        assertEquals(next, plan.wakeAtMs)
    }

    /**
     * A device that slept through its alarm comes back late. The session is not skipped —
     * it is simply due now, and the engine's own loop decides what to do with the link.
     */
    @Test
    fun anOverdueSessionBringsTheLinkStraightBack() {
        val next = at(15)
        val plan = policy.plan(next + 90 * 60_000L, low, LinkActivity(nextSessionAtMs = next))
        assertEquals(LinkState.AWAKE, plan.state)
    }

    /**
     * The regression that made low mode do nothing at all: the engine only publishes a
     * session from a loop production does not run, so `nextSessionAtMs` stayed 0 forever
     * and this used to answer "stay awake". The link was up around the clock in the one
     * mode whose entire job is to take it down. It draws its own gap now.
     */
    @Test
    fun lowModeDozesOnItsOwnGapWhenNoSessionWasPublished() {
        val now = at(15)
        val plan = policy.plan(now, low, LinkActivity(nextSessionAtMs = 0L))
        assertEquals(LinkState.DOZING, plan.state)
        assertTrue("a doze with no alarm never comes back", plan.wakeAtMs!! > now)
    }

    /** The drawn gap is carried, not re-rolled: an alarm that moves is an alarm that lies. */
    @Test
    fun aCarriedDozeInstantIsKept() {
        val now = at(15)
        val carried = now + 42 * 60_000L
        val plan = policy.plan(now, low, LinkActivity(), dozeWakeAtMs = carried)
        assertEquals(LinkState.DOZING, plan.state)
        assertEquals(carried, plan.wakeAtMs)

        // And one that has come due is *spent*, not redrawn. Redrawing here is what made
        // low mode look possessed on the device: the alarm fired, the policy found its own
        // instant in the past, drew a fresh 90-180 minute gap and dozed again — so the link
        // never once came up on its own and the status line announced a different offline
        // time every time anything poked the loop.
        val due = policy.plan(now, low, LinkActivity(), dozeWakeAtMs = now - 1L)
        assertEquals(LinkState.AWAKE, due.state)
        assertTrue("a session has to name its own end", due.sessionUntilMs > now)
        assertEquals(due.sessionUntilMs, due.wakeAtMs)
    }

    /**
     * The full low-mode cycle, which is the thing that was actually broken: doze on a drawn
     * gap, come up for one listening window when it expires, doze again on a fresh gap when
     * that window runs out. Nothing else in the app expresses "a session is running", so the
     * window has to be carried — a session that lasts exactly one call is a flapping link.
     */
    @Test
    fun aDozeThatComesDueOpensASessionAndThenDozesAgain() {
        val start = at(15)

        val dozing = policy.plan(start, low, LinkActivity())
        assertEquals(LinkState.DOZING, dozing.state)
        val alarm = dozing.wakeAtMs!!

        val session = policy.plan(alarm, low, LinkActivity(), dozeWakeAtMs = alarm)
        assertEquals(LinkState.AWAKE, session.state)
        val until = session.sessionUntilMs
        assertTrue("the session is one listening window", until - alarm in (8 * 60_000L)..(12 * 60_000L))

        // Held for its whole length, at a stable instant — 20 re-plans, one answer.
        repeat(20) {
            val again = policy.plan(alarm + 60_000L, low, LinkActivity(), sessionUntilMs = until)
            assertEquals(LinkState.AWAKE, again.state)
            assertEquals(until, again.wakeAtMs)
            assertEquals(until, again.sessionUntilMs)
        }

        val after = policy.plan(until, low, LinkActivity(), sessionUntilMs = until)
        assertEquals(LinkState.DOZING, after.state)
        assertTrue("and the next gap is a fresh draw", after.wakeAtMs!! > until)
        assertEquals("a doze is not in a session", 0L, after.sessionUntilMs)
    }

    /** A long press has to be able to end a session early, so nothing may re-open it. */
    @Test
    fun aClearedSessionDoesNotComeBack() {
        val now = at(15)
        val plan = policy.plan(now, low, LinkActivity(), sessionUntilMs = 0L)
        assertEquals(LinkState.DOZING, plan.state)
    }

    /**
     * A message landing during a hand-started window pushes the bedtime out past it. The
     * status line reads this instant out loud, so naming the earlier of the two would have
     * it announce an offline time the link is not going to keep.
     */
    @Test
    fun aManualWakeReportsTheLaterOfTheTwoDeadlines() {
        val now = at(15)
        val manual = now + 5 * 60_000L
        val listen = now + 20 * 60_000L
        val plan =
            policy.plan(
                now,
                low,
                LinkActivity(listenUntilMs = listen),
                manualWakeUntilMs = manual,
            )
        assertEquals(LinkState.AWAKE, plan.state)
        assertEquals(listen, plan.wakeAtMs)
    }

    /** Nothing to count down to in a mode that never drops the link: no number beats a wrong one. */
    @Test
    fun aManualWakeNamesNoDeadlineWhenTheLinkWouldBeUpAnyway() {
        val now = at(15)
        val plan = policy.plan(now, default, LinkActivity(), manualWakeUntilMs = now + 60_000L)
        assertEquals(LinkState.AWAKE, plan.state)
        assertNull(plan.wakeAtMs)
    }

    /** The slider names a target, not a stopwatch: ±20%, and never outside its own range. */
    @Test
    fun theListenWindowIsJitteredAroundTheConfiguredMinutes() {
        val minute = 60_000L
        val drawn = List(200) { policy.listenWindowMs(10) }
        assertTrue(drawn.all { it in (8 * minute)..(12 * minute) })
        assertTrue("±20% that never varies is not jitter", drawn.distinct().size > 1)

        assertTrue(policy.listenWindowMs(0) >= (LinkPowerPolicy.MIN_LISTEN_MINUTES - 1) * minute)
        assertTrue(policy.listenWindowMs(9_000) <= (LinkPowerPolicy.MAX_LISTEN_MINUTES + 6) * minute)
    }

    /**
     * Low mode's whole promise: she looks at her phone *more often*, so an unanswered
     * message is seen sooner than the behavioural 3–5 h gap would allow.
     */
    @Test
    fun theLowModeSessionGapIsShorterThanTheBehaviouralOne() {
        val drawn = List(200) { policy.lowSessionGapMs() }
        assertTrue(drawn.all { it in (90 * 60_000L)..(180 * 60_000L) })
        assertTrue(drawn.distinct().size > 1)
        // Never longer than the behavioural floor of 3 h, so low mode can only ever make her
        // check more often than the untouched self-session gap already does.
        assertTrue(drawn.max() <= 3 * 60 * 60_000L)
        assertTrue(List(200) { HumanTimingPolicy().selfSessionGapMs() }.min() >= drawn.max())
    }
}
