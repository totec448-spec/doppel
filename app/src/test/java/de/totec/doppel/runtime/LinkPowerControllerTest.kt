package de.totec.doppel.runtime

import de.totec.doppel.engine.EngineSettingsProvider
import de.totec.doppel.engine.EngineSettingsSnapshot
import de.totec.doppel.engine.LinkPowerFeed
import de.totec.doppel.engine.LinkState
import de.totec.doppel.engine.PowerMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What the controller does to the device, in the order it does it.
 *
 * The ordering is not cosmetic. Connecting before the wake lock is held is the 60 s-EOF
 * failure [LinkKeepAlive] documents, and releasing the lock before the alarm is armed is
 * a bot that never comes back — both are silent in production and obvious here.
 */
class LinkPowerControllerTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(hour: Int): Long =
        ZonedDateTime.of(2026, 8, 1, hour, 0, 0, 0, berlin).toInstant().toEpochMilli()

    /** Every side effect, in one list, so the assertions can be about order. */
    private class Recorder {
        val calls = mutableListOf<String>()

        val link =
            object : LinkSwitch {
                override suspend fun sleep() {
                    calls += "socket:down"
                }

                override suspend fun wake() {
                    calls += "socket:up"
                }

                override suspend fun showOnlineNow() {
                    calls += "dot"
                }
            }

        val cpu = CpuHold { held -> calls += if (held) "lock:on" else "lock:off" }

        var armedAt: Long? = null
        val alarm =
            WakeAlarm { atMs ->
                armedAt = atMs
                calls += if (atMs == null) "alarm:off" else "alarm:on"
                true
            }
    }

    private class FixedSettings(private val settings: EngineSettingsSnapshot) :
        EngineSettingsProvider {
        override suspend fun resolve(chatJid: String): EngineSettingsSnapshot = settings
    }

    private suspend fun awaitCall(
        recorder: Recorder,
        call: String,
    ) {
        withTimeout(5_000) {
            while (call !in recorder.calls) yield()
        }
    }

    private fun controllerFor(
        recorder: Recorder,
        feed: LinkPowerFeed,
        settings: EngineSettingsSnapshot,
        nowMs: () -> Long,
        settingsChanged: MutableSharedFlow<Unit> = MutableSharedFlow(),
        linkConnected: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = LinkPowerController(
        feed = feed,
        settingsProvider = FixedSettings(settings),
        link = recorder.link,
        cpu = recorder.cpu,
        alarm = recorder.alarm,
        settingsChanged = settingsChanged,
        linkConnected = linkConnected,
        nowMs = nowMs,
    )

    /**
     * Going down: the socket first, then the alarm, and only then the lock. Between
     * dropping the lock and arming the alarm the device may already be suspended.
     */
    @Test
    fun `dozing arms the alarm before it lets the cpu go`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(15)
        val nextSession = at(17)
        feed.noteNextSessionAt(nextSession)

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW),
                nowMs = { now },
            )
        val job: Job = launch { controller.run() }
        awaitCall(recorder, "lock:off")

        assertEquals(listOf("socket:down", "alarm:on", "lock:off"), recorder.calls)
        assertEquals(nextSession, recorder.armedAt)
        assertEquals(LinkState.DOZING, feed.state.value)
        assertEquals(nextSession, feed.wakeAtMs.value)
        job.cancelAndJoin()
    }

    /**
     * Coming back: the lock first, then the socket — and the dot, because a link that
     * came up either for a session or because the operator tapped it is "she picked
     * the phone up", not a silent background reconnect.
     */
    @Test
    fun `a tap on a dozing link takes the lock before it reconnects`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(15)
        feed.noteNextSessionAt(at(17))

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW),
                nowMs = { now },
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:off")
        recorder.calls.clear()

        feed.requestWake()
        awaitCall(recorder, "dot")

        assertEquals(listOf("lock:on", "alarm:off", "socket:up", "dot"), recorder.calls)
        assertEquals(LinkState.AWAKE, feed.state.value)
        job.cancelAndJoin()
    }

    /**
     * Pairing can be the event that creates the foreground service. Its wake request is
     * therefore emitted before the power controller subscribes, and must still override
     * LOW mode and quiet hours for one bounded listening window.
     */
    @Test
    fun `a wake queued before service startup survives and opens a bounded window`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(3)
        feed.requestWake()

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW, lowListenMinutes = 10),
                nowMs = { now },
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "alarm:off")

        assertEquals(listOf("lock:on", "alarm:off"), recorder.calls.take(2))
        assertFalse("startup must not apply the scheduled sleep", "lock:off" in recorder.calls)
        assertFalse("startup must not drop the bridge", "socket:down" in recorder.calls)
        assertEquals(LinkState.AWAKE, feed.state.value)
        assertTrue("the manual window must have an end", feed.wakeAtMs.value!! > now)
        assertTrue(
            "the queued wake must remain bounded",
            feed.wakeAtMs.value!! - now <= 12 * 60_000L,
        )
        job.cancelAndJoin()
    }

    /** Holding it puts the link back down, and takes the conversation tail with it. */
    @Test
    fun `a long press sleeps the link again and drops the listening window`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(15)
        feed.noteNextSessionAt(at(17))
        feed.extendListening(now + 10 * 60_000L)

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW),
                nowMs = { now },
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:on")
        recorder.calls.clear()

        feed.requestSleep()
        awaitCall(recorder, "lock:off")

        assertEquals(0L, feed.activity.value.listenUntilMs)
        assertEquals(LinkState.DOZING, feed.state.value)
        job.cancelAndJoin()
    }

    /**
     * The quiet hours are the one case default mode also acts on: the bot answers
     * nothing between 00:30 and 08:30 either way, it simply used to burn a pinned CPU
     * doing it.
     */
    @Test
    fun `default mode still sleeps through the quiet hours`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(3)

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.DEFAULT),
                nowMs = { now },
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:off")

        assertEquals(LinkState.SLEEPING, feed.state.value)
        assertTrue("the morning has to be armed for", recorder.armedAt!! > now)
        job.cancelAndJoin()
    }

    /**
     * A turn must not be a way back up.
     *
     * `busy` protects a turn that is *already running* from having the socket pulled out
     * from under it — it is not a request. This used to be read as "busy → AWAKE" without
     * qualification, which handed the engine's scheduler a lever on the transport: a reply
     * whose human delay came due inside a doze set `busy`, the link came up hours before
     * the time the status line was showing, and the bot answered — including straight
     * through a long press that had just asked for the opposite. A turn that finds the
     * link down parks on [LinkPowerFeed.awaitCarrying] instead.
     */
    @Test
    fun `a turn does not raise a link that is down`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(15)
        feed.noteNextSessionAt(at(17))

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW),
                nowMs = { now },
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:off")
        recorder.calls.clear()

        feed.noteBusy(true)
        // The assertion is that something does *not* happen, so the loop is given a window
        // in which it would: if the old rule were still in force the link would be up well
        // inside it, and this returns early with a failure instead of a timeout.
        withTimeoutOrNull(500) {
            while (feed.state.value == LinkState.DOZING) yield()
        }

        assertEquals(LinkState.DOZING, feed.state.value)
        assertFalse("a due reply may not open the socket", "socket:up" in recorder.calls)
        assertFalse("nor start a turn against it", feed.carrying.value)
        job.cancelAndJoin()
    }

    /**
     * Every tap buys another window, measured from the end of the one already running and
     * drawn from the configured minutes.
     *
     * Both halves were wrong before. The window was hard-coded to the default ten minutes,
     * so the setting did not apply to the one control that names it; and it was measured
     * from now, so a tap could hand back *less* time than was left — which is why tapping
     * the line appeared to do nothing at all.
     */
    @Test
    fun `each tap buys another configured window on top of the one running`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(15)
        feed.noteNextSessionAt(at(17))

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW, lowListenMinutes = 30),
                nowMs = { now },
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:off")

        feed.requestWake()
        withTimeout(5_000) {
            while (feed.state.value != LinkState.AWAKE) yield()
        }
        val first = feed.wakeAtMs.value!!
        // 30 minutes ±20%, not the 10 minute default.
        assertTrue("drawn from the setting", first - now >= 24 * 60_000L)

        feed.requestWake()
        withTimeout(5_000) {
            while (feed.wakeAtMs.value == first) yield()
        }
        assertTrue("a second tap has to push it out", feed.wakeAtMs.value!! > first)
        job.cancelAndJoin()
    }

    /**
     * ...but only so far. Idle tapping used to buy two hours measured from the newest tap,
     * so a morning of it walked the deadline forward all day: the line read "offline 12:27"
     * at half past nine. The ceiling is now a few of the windows the setting names, which is
     * the only number the person tapping has been shown.
     */
    @Test
    fun `a stack of taps stops at a few configured windows`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val now = at(15)

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW, lowListenMinutes = 10),
                nowMs = { now },
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:off")

        // One at a time so each request is applied and advances the visible deadline;
        // a burst would only prove that the bounded queue accepted a burst.
        repeat(20) {
            val before = feed.wakeAtMs.value
            feed.requestWake()
            withTimeoutOrNull(1_000) {
                while (feed.wakeAtMs.value == before) yield()
            }
        }
        assertEquals(LinkState.AWAKE, feed.state.value)
        assertTrue(
            "twenty taps must not buy more than three windows",
            feed.wakeAtMs.value!! - now <= 30 * 60_000L,
        )
        job.cancelAndJoin()
    }

    /**
     * The bug that made the whole feature ornamental on the device.
     *
     * At startup the controller decides before any client exists, and a disconnect issued
     * then reaches nothing and does nothing at all. The state was recorded as DOZING
     * regardless, and since a sleep is only issued on the way *out* of AWAKE it was never
     * issued again — so the native runtime finished its own connect a second later and the
     * bot sat fully online for the whole doze, under a header promising it was away until
     * half past twelve. The link reporting itself up is the one fact needed to re-apply the
     * plan that was already made, and it does not matter who raised it.
     */
    @Test
    fun `a link that comes up under a doze is put back down`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val connected = MutableStateFlow(false)
        val now = at(15)
        feed.noteNextSessionAt(at(17))

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW),
                nowMs = { now },
                linkConnected = connected,
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "socket:down")
        assertEquals(1, recorder.calls.count { it == "socket:down" })
        assertFalse("a link that is down carries nothing", feed.carrying.value)

        // WhatsApp reports itself connected. Nothing in this controller asked for it —
        // the native runtime connects on its own as soon as it has a paired device.
        connected.value = true

        withTimeout(5_000) {
            while (recorder.calls.count { it == "socket:down" } < 2) yield()
        }
        assertEquals(LinkState.DOZING, feed.state.value)
        assertFalse(feed.carrying.value)
        job.cancelAndJoin()
    }

    /**
     * The same signal must not become a second way of *keeping* the link up. A link that
     * comes up while the plan says awake is simply the plan working, and re-applying it
     * there would reconnect an already connected socket on every reconnect the runtime
     * makes on its own.
     */
    @Test
    fun `a link that comes up while awake is left alone`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val connected = MutableStateFlow(false)
        val now = at(15)

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.DEFAULT),
                nowMs = { now },
                linkConnected = connected,
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:on")

        connected.value = true
        connected.value = false
        connected.value = true

        withTimeout(5_000) {
            while (feed.state.value != LinkState.AWAKE) yield()
        }
        assertEquals(0, recorder.calls.count { it == "socket:down" })
        // Never issued: the controller starts at AWAKE and stays there, so there is no
        // transition into AWAKE to wake a socket that was never asleep.
        assertEquals(0, recorder.calls.count { it == "socket:up" })
        job.cancelAndJoin()
    }

    /**
     * The other half of the same contract: a turn may not start against a link that is on
     * its way down, and may start the moment one is genuinely up. [LinkPowerFeed.carrying]
     * is the engine's gate, and it is deliberately not [LinkPowerFeed.state] — the state is
     * published the instant a decision is made so the status line can answer a tap, which
     * is well before the websocket has finished connecting.
     */
    @Test
    fun `carrying follows the socket rather than the decision`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val connected = MutableStateFlow(false)
        val now = at(15)
        feed.noteNextSessionAt(at(17))

        val controller =
            controllerFor(
                recorder,
                feed,
                EngineSettingsSnapshot(powerMode = PowerMode.LOW),
                nowMs = { now },
                linkConnected = connected,
            )
        val job = launch { controller.run() }
        awaitCall(recorder, "lock:off")
        assertFalse(feed.carrying.value)

        feed.requestWake()
        awaitCall(recorder, "socket:up")
        assertFalse("Connect is only an attempt, not transport proof", feed.carrying.value)
        connected.value = true
        withTimeout(5_000) {
            while (!feed.carrying.value) yield()
        }
        assertEquals(LinkState.AWAKE, feed.state.value)
        // Not before the socket call: the gate exists to stop a send racing the connect.
        assertTrue("socket:up" in recorder.calls)
        job.cancelAndJoin()
    }

    /**
     * Settings that cannot be read are a transient database problem, not an instruction
     * to disconnect: there would be no stored schedule to come back on.
     */
    @Test
    fun `unreadable settings keep the link up`() = runBlocking {
        val recorder = Recorder()
        val feed = LinkPowerFeed()
        val failures = mutableListOf<String>()
        val controller =
            LinkPowerController(
                feed = feed,
                settingsProvider =
                    object : EngineSettingsProvider {
                        override suspend fun resolve(chatJid: String): EngineSettingsSnapshot =
                            throw IllegalStateException("database is busy")
                    },
                link = recorder.link,
                cpu = recorder.cpu,
                alarm = recorder.alarm,
                settingsChanged = MutableSharedFlow(),
                linkConnected = MutableStateFlow(false),
                nowMs = { at(3) },
                onFailure = { stage, _ -> failures += stage },
            )
        val job = launch { controller.run() }
        withTimeout(5_000) {
            while (failures.isEmpty()) yield()
        }

        assertEquals(listOf("settings"), failures)
        assertTrue("nothing may be done to the link", recorder.calls.isEmpty())
        job.cancelAndJoin()
    }
}
