package de.totec.doppel.engine

import de.totec.doppel.domain.ChatEventKind
import de.totec.doppel.domain.IncomingEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCoordinatorTest {
    @Test
    fun `direct inbound persists one level-derived deadline`() = runBlocking {
        val persistence = RecordingProactivePersistence()
        val clock = FixedEngineClock(1_000_000L)
        val coordinator = coordinator(this, persistence, clock)

        coordinator.onInbound(
            event(chat = "alice", group = false),
            EngineSettingsSnapshot(proactiveLevel = 10),
        )

        val saved = persistence.schedules.single()
        assertTrue(saved.enabled)
        assertNull(saved.deferredAtMs)
        assertEquals(
            clock.now + ProactiveCoordinator.minimumIdleMs(10),
            saved.nextDueAtMs,
        )
        coordinator.close()
    }

    @Test
    fun `group and level zero are disabled without deadline`() = runBlocking {
        val persistence = RecordingProactivePersistence()
        val clock = FixedEngineClock(2_000_000L)
        val coordinator = coordinator(this, persistence, clock)

        coordinator.onInbound(
            event(chat = "group", group = true),
            EngineSettingsSnapshot(proactiveLevel = 10),
        )
        coordinator.onInbound(
            event(chat = "off", group = false),
            EngineSettingsSnapshot(proactiveLevel = 0),
        )

        assertEquals(2, persistence.schedules.size)
        persistence.schedules.forEach {
            assertFalse(it.enabled)
            assertNull(it.nextDueAtMs)
        }
        coordinator.close()
    }

    @Test
    fun `leave on read is revisited earlier but remains persisted`() = runBlocking {
        val persistence = RecordingProactivePersistence()
        val clock = FixedEngineClock(3_000_000L)
        val coordinator = coordinator(this, persistence, clock)

        coordinator.onDeferred(
            event(chat = "alice", group = false),
            EngineSettingsSnapshot(proactiveLevel = 1),
        )

        val saved = persistence.schedules.single()
        assertEquals(clock.now, saved.deferredAtMs)
        assertEquals(clock.now + 25L * 60_000L, saved.nextDueAtMs)
        coordinator.close()
    }

    @Test
    fun `hot cold level is restart stable and remains in configured band`() {
        val settings =
            EngineSettingsSnapshot(
                proactiveLevel = 5,
                proactiveMode = ProactiveMode.HOT_COLD,
            )
        val day = 24L * 60L * 60L * 1_000L
        val values =
            (0L until 8L).map { offset ->
                ProactiveCoordinator.effectiveLevel("alice", settings, offset * day)
            }

        assertTrue(values.all { it in 3..7 })
        assertTrue(values.toSet().size > 1)
        assertEquals(
            values.first(),
            ProactiveCoordinator.effectiveLevel("alice", settings, day / 2),
        )
    }

    @Test
    fun `level caps and failure backoff stay tightly bounded`() {
        assertEquals(0, ProactiveCoordinator.maximumPerDay(0))
        assertEquals(1, ProactiveCoordinator.maximumPerDay(3))
        assertEquals(2, ProactiveCoordinator.maximumPerDay(7))
        assertEquals(3, ProactiveCoordinator.maximumPerDay(10))
        assertEquals(5L * 60_000L, ProactiveCoordinator.failureBackoffMs(0))
        assertTrue(ProactiveCoordinator.failureBackoffMs(100) <= 6L * 60L * 60_000L)
    }

    @Test
    fun `scheduled follow-up keeps the persona that created it after a persona switch`() = runBlocking {
        val clock = FixedEngineClock(2_000L)
        val store = ScheduledDueStore()
        val request = CompletableDeferred<ProactiveTurnRequest>()
        val coordinator =
            ProactiveCoordinator(
                parentScope = this,
                persistence = store,
                settingsProvider =
                    object : EngineSettingsProvider {
                        override suspend fun resolve(chatJid: String) =
                            EngineSettingsSnapshot(personality = "male", proactiveLevel = 5)
                    },
                canContact = { _, _ -> true },
                execute = { _, _, turn ->
                    request.complete(turn)
                    ProactiveTurnOutcome.Silent
                },
                clock = clock,
            )

        coordinator.start()
        val turn = withTimeout(3_000L) { request.await() }
        val result = withTimeout(3_000L) { store.finished.await() }

        assertEquals("female", turn.personaKey)
        assertTrue(turn.trailingDirective)
        assertTrue(turn.scheduledFollowUp)
        assertTrue(turn.reason.startsWith("You set a timer to write this person right now"))
        assertEquals(store.followUp.id, result.completedFollowUpId)
        coordinator.close()
    }

    @Test
    fun `reschedule replaces waiting timer but never duplicates claimed work`() = runBlocking {
        val clock = GateEngineClock()
        val store = SingleDueStore()
        val runs = AtomicInteger()
        val scheduler =
            DueWorkScheduler(
                parentScope = this,
                store = store,
                runner =
                    DueWorkRunner {
                        runs.incrementAndGet()
                        DueWorkResult(success = true, nextDueAtMs = null)
                    },
                clock = clock,
            )

        scheduler.reschedule()
        await { clock.delayCalls.get() == 1 }
        scheduler.reschedule()
        await { clock.delayCalls.get() == 2 }
        clock.release.complete(Unit)
        await { store.finished.get() }

        assertEquals(1, runs.get())
        assertEquals(1, store.claimCalls.get())
        scheduler.close()
    }

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        persistence: ProactivePersistence,
        clock: EngineClock,
    ) = ProactiveCoordinator(
        parentScope = scope,
        persistence = persistence,
        settingsProvider =
            object : EngineSettingsProvider {
                override suspend fun resolve(chatJid: String) =
                    EngineSettingsSnapshot(proactiveLevel = 5)
            },
        canContact = { _, _ -> true },
        execute = { _, _, _ -> ProactiveTurnOutcome.Silent },
        clock = clock,
    )

    private fun event(chat: String, group: Boolean) =
        IncomingEvent(
            eventId = "event-$chat",
            sequence = 1,
            kind = ChatEventKind.MESSAGE,
            messageId = "message-$chat",
            chatJid = chat,
            isGroup = group,
            senderJid = "$chat@s.whatsapp.net",
            fromMe = false,
            timestampMs = 100,
            text = "hello",
        )

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(3_000L) {
            while (!condition()) delay(5L)
        }
    }
}

private class FixedEngineClock(val now: Long) : EngineClock {
    override fun wallTimeMillis() = now

    override suspend fun delay(millis: Long) = Unit
}

private class RecordingProactivePersistence : ProactivePersistence {
    val schedules = mutableListOf<ProactiveSchedule>()

    override suspend fun listStates(limit: Int): List<ProactiveStateSnapshot> = emptyList()

    override suspend fun getState(chatJid: String): ProactiveStateSnapshot? = null

    override suspend fun saveSchedule(schedule: ProactiveSchedule) {
        schedules += schedule
    }

    override suspend fun loadSeed(chatJid: String): ProactiveSeed? = null

    override suspend fun setGlobalNotBefore(timestampMs: Long) = Unit

    override suspend fun nextDue(): DueWork? = null

    override suspend fun claim(id: String, nowMs: Long) = false

    override suspend fun finish(id: String, result: DueWorkResult) = Unit
}

private class ScheduledDueStore : ProactivePersistence {
    val followUp =
        ScheduledFollowUp(
            id = "follow-up-1",
            conversationKey = "alice#female",
            personaKey = "female",
            scheduledAtMs = 1_000L,
            nextAttemptAtMs = 1_000L,
            note = "ask how the appointment went",
            createdAtMs = 500L,
        )
    val finished = CompletableDeferred<DueWorkResult>()
    private val claimed = AtomicBoolean()

    private val state =
        ProactiveStateSnapshot(
            chatJid = "alice",
            enabled = true,
            nextDueAtMs = 1_000L,
            cooldownUntilMs = null,
            lastInboundAtMs = 500L,
            lastOutboundAtMs = null,
            dailyWindowStartedAtMs = null,
            dailyOutboundCount = 0,
            consecutiveFailures = 0,
            leaseOwner = null,
            leaseUntilMs = null,
            deferredAtMs = null,
            coldOutreachAtMs = null,
            updatedAtMs = 500L,
            baseNextDueAtMs = null,
            scheduledFollowUp = followUp,
        )

    override suspend fun listStates(limit: Int): List<ProactiveStateSnapshot> = emptyList()

    override suspend fun getState(chatJid: String): ProactiveStateSnapshot? =
        state.takeIf { chatJid == state.chatJid }

    override suspend fun saveSchedule(schedule: ProactiveSchedule) = Unit

    override suspend fun loadSeed(chatJid: String): ProactiveSeed? =
        ProactiveSeed(
            IncomingEvent(
                eventId = "seed",
                sequence = 1,
                kind = ChatEventKind.MESSAGE,
                messageId = "seed-message",
                chatJid = "alice",
                isGroup = false,
                senderJid = "alice@s.whatsapp.net",
                fromMe = false,
                timestampMs = 500L,
                text = "see you later",
                personaKey = "male",
            ),
        ).takeIf { chatJid == "alice" }

    override suspend fun setGlobalNotBefore(timestampMs: Long) = Unit

    override suspend fun nextDue(): DueWork? =
        if (finished.isCompleted) null else DueWork("alice", DueWorkKind.PROACTIVE, 1_000L)

    override suspend fun claim(id: String, nowMs: Long): Boolean = claimed.compareAndSet(false, true)

    override suspend fun finish(id: String, result: DueWorkResult) {
        finished.complete(result)
    }
}

private class GateEngineClock : EngineClock {
    val delayCalls = AtomicInteger()
    val release = CompletableDeferred<Unit>()

    override fun wallTimeMillis() = 0L

    override suspend fun delay(millis: Long) {
        delayCalls.incrementAndGet()
        release.await()
    }
}

private class SingleDueStore : DueWorkStore {
    private val claimed = AtomicBoolean()
    val claimCalls = AtomicInteger()
    val finished = AtomicBoolean()

    override suspend fun nextDue(): DueWork? =
        if (finished.get()) null else DueWork("chat", DueWorkKind.PROACTIVE, 10L)

    override suspend fun claim(id: String, nowMs: Long): Boolean {
        claimCalls.incrementAndGet()
        return claimed.compareAndSet(false, true)
    }

    override suspend fun finish(id: String, result: DueWorkResult) {
        finished.set(true)
    }
}
