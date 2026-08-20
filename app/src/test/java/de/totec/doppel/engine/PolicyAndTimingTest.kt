package de.totec.doppel.engine

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAndTimingTest {
    private val intent =
        OutboundIntent(
            reservationId = "r1",
            chatJid = "chat",
            proactive = false,
            textHash = "hash",
            timestampMs = 1_000_000,
        )

    @Test
    fun `hard lock also blocks admin`() {
        val result =
            OutboundPolicy.evaluate(
                intent.copy(admin = true),
                OutboundFacts(hardLockReason = "auth"),
                OutboundPolicySettings(),
            )
        assertEquals(OutboundDecision.Blocked("auth", true), result)
    }

    @Test
    fun `the daily limit retries when a slot actually frees up`() {
        // Three sends, cap of three: the reply is held exactly until the oldest
        // of them leaves the 24h window, not for a fixed hour that would find the
        // window just as full and hold it again, and again, for the rest of the day.
        val now = intent.timestampMs
        val day = OutboundPolicy.ONE_DAY_MS
        val oldest = now - day + 90 * 60_000
        val facts =
            OutboundFacts(
                sentLastDay = 3,
                recentSendTimestampsMs = listOf(now - 5_000, oldest, now - 60_000),
            )
        val decision =
            OutboundPolicy.evaluate(intent, facts, OutboundPolicySettings(maxPerDay = 3))
        assertEquals(
            OutboundDecision.Deferred(oldest + day + 1, "Daily send limit"),
            decision,
        )
    }

    @Test
    fun `a limit without timestamps keeps the fixed fallback`() {
        val decision =
            OutboundPolicy.evaluate(
                intent,
                OutboundFacts(sentLastDay = 200),
                OutboundPolicySettings(maxPerDay = 120),
            )
        assertEquals(
            OutboundDecision.Deferred(intent.timestampMs + 60 * 60_000, "Daily send limit"),
            decision,
        )
    }

    @Test
    fun `timestamps that cannot explain the count fall back instead of spinning`() {
        // The count and the timestamp list come from the same query, but an older
        // record without a commit time would leave fewer timestamps than sends.
        // Guessing a deadline from those would retry immediately, forever.
        val now = intent.timestampMs
        val decision =
            OutboundPolicy.evaluate(
                intent,
                OutboundFacts(
                    sentLastDay = 2,
                    recentSendTimestampsMs = listOf(now - OutboundPolicy.ONE_DAY_MS * 2),
                ),
                OutboundPolicySettings(maxPerDay = 2),
            )
        assertEquals(
            OutboundDecision.Deferred(now + 60 * 60_000, "Daily send limit"),
            decision,
        )
    }

    @Test
    fun `the hourly limit uses its own window`() {
        val now = intent.timestampMs
        val hour = OutboundPolicy.ONE_HOUR_MS
        val oldest = now - hour + 30_000
        val decision =
            OutboundPolicy.evaluate(
                intent,
                OutboundFacts(
                    sentLastHour = 2,
                    recentSendTimestampsMs = listOf(oldest, now - 10_000),
                ),
                OutboundPolicySettings(maxPerHour = 2),
            )
        assertEquals(
            OutboundDecision.Deferred(oldest + hour + 1, "Hourly send limit"),
            decision,
        )
    }

    @Test
    fun `hour limit defers normal send but not admin`() {
        val facts = OutboundFacts(sentLastHour = 25)
        assertTrue(
            OutboundPolicy.evaluate(intent, facts, OutboundPolicySettings()) is
                OutboundDecision.Deferred,
        )
        assertTrue(
            OutboundPolicy.evaluate(
                intent.copy(admin = true),
                facts,
                OutboundPolicySettings(),
            ) is OutboundDecision.Allowed,
        )
    }

    @Test
    fun `duplicate is blocked before soft budgets`() {
        val result =
            OutboundPolicy.evaluate(
                intent,
                OutboundFacts(samePayloadSentRecently = true),
                OutboundPolicySettings(),
            )
        assertTrue(result is OutboundDecision.Blocked)
    }

    @Test
    fun `instant preset has no pickup delay`() {
        val delay =
            HumanTimingPolicy(Random(1)).pickupPlan(
                nowMs = 10_000,
                offlineForMs = 8 * 60 * 60 * 1_000L,
                inSession = false,
                settings = EngineSettingsSnapshot(replyPreset = ReplyPreset.INSTANT),
            ).delayMs
        assertEquals(0, delay)
    }

    @Test
    fun `sleep window delays until morning`() {
        val zone = ZoneId.of("Europe/Berlin")
        val timestamp =
            ZonedDateTime.of(2026, 7, 30, 1, 0, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
        val delay =
            HumanTimingPolicy(Random(1)).pickupPlan(
                nowMs = timestamp,
                offlineForMs = 8 * 60 * 60 * 1_000L,
                inSession = false,
                settings =
                    EngineSettingsSnapshot(
                        timezone = zone,
                        sleepStartMinutes = 30,
                        sleepEndMinutes = 8 * 60 + 30,
                    ),
            ).delayMs
        // Wake-up plus the reference build's 5-120 min "not the second the alarm
        // goes off" jitter.
        assertTrue(delay >= 7.5 * 60 * 60 * 1_000 + 5 * 60 * 1_000)
        assertTrue(delay <= 7.5 * 60 * 60 * 1_000 + 120 * 60 * 1_000)
    }

    /**
     * A wait drawn before bedtime may not elapse after it. The away band reaches half an hour,
     * so a message at 00:20 against a 00:30 bedtime could otherwise have its answer scheduled
     * for the middle of the night — into a link that is down for those same quiet hours.
     *
     * Since the bands gained a ten-second floor, a draw from this position can also legitimately
     * land *before* 00:30, so the invariant is no longer "every draw is pushed to morning". It is
     * the one that always mattered: no answer is ever scheduled inside the window. Either it beats
     * bedtime or it waits out the whole night.
     */
    @Test
    fun `no pickup is ever scheduled inside the sleep window`() {
        val zone = ZoneId.of("Europe/Berlin")
        val settings =
            EngineSettingsSnapshot(
                timezone = zone,
                sleepStartMinutes = 30,
                sleepEndMinutes = 8 * 60 + 30,
            )
        val nowMs =
            ZonedDateTime.of(2026, 7, 30, 0, 20, 0, 0, zone).toInstant().toEpochMilli()
        val untilBedtimeMs = 10 * 60 * 1_000L
        val untilMorningMs = 8 * 60 * 60 * 1_000L + untilBedtimeMs
        var pushed = 0
        repeat(200) { seed ->
            val plan =
                HumanTimingPolicy(Random(seed.toLong())).pickupPlan(
                    nowMs = nowMs,
                    offlineForMs = 8 * 60 * 60 * 1_000L,
                    inSession = false,
                    settings = settings,
                )
            val beatsBedtime = plan.delayMs < untilBedtimeMs
            if (!beatsBedtime) {
                pushed++
                assertTrue(
                    "answered at ${(plan.delayMs / 60_000L)} minutes past 00:20",
                    plan.delayMs >= untilMorningMs,
                )
                // Fixed, like the sleeping case: a later message may not talk the night down.
                assertEquals(plan.delayMs, plan.minDelayMs)
                assertEquals(plan.delayMs, plan.maxDelayMs)
            }
        }
        // The band is centred twenty minutes out, so the overwhelming majority must be pushed —
        // otherwise this passed only because nothing ever crossed bedtime in the first place.
        assertTrue("only $pushed of 200 draws crossed bedtime", pushed > 150)
    }

    /** Before bedtime is still before bedtime — the ordinary band survives. */
    @Test
    fun `a pickup that lands well before bedtime is left alone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val nowMs =
            ZonedDateTime.of(2026, 7, 30, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val plan =
            HumanTimingPolicy(Random(1)).pickupPlan(
                nowMs = nowMs,
                offlineForMs = 8 * 60 * 60 * 1_000L,
                inSession = false,
                settings =
                    EngineSettingsSnapshot(
                        timezone = zone,
                        sleepStartMinutes = 30,
                        sleepEndMinutes = 8 * 60 + 30,
                    ),
            )
        assertTrue(plan.delayMs <= 30 * 60_000L)
    }

    @Test
    fun `mood is shared and stable within block`() {
        val zone = ZoneId.of("Europe/Berlin")
        val first = MoodEngine.at(1_785_000_000_000, zone)
        val second = MoodEngine.at(1_785_000_000_000 + 60_000, zone)
        assertEquals(first, second)
    }
}
