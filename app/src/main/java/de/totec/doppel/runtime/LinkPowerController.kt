package de.totec.doppel.runtime

import de.totec.doppel.engine.EngineSettingsProvider
import de.totec.doppel.engine.LinkPowerFeed
import de.totec.doppel.engine.LinkPowerPolicy
import de.totec.doppel.engine.LinkPowerRequest
import de.totec.doppel.engine.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The three things a plan can actually do to the device, behind an interface each so
 * the loop below stays a plain JVM unit test.
 */
internal interface LinkSwitch {
    /** Disconnect the WhatsApp socket without giving up the pairing. */
    suspend fun sleep()

    /** Reconnect it. */
    suspend fun wake()

    /** Run one ordinary self-session, so a hand-woken link also shows the dot. */
    suspend fun showOnlineNow()
}

/** The wake lock and Wi-Fi lock, as one on/off. */
internal fun interface CpuHold {
    fun hold(held: Boolean)
}

/**
 * The only clock that survives suspend.
 *
 * Everything else in this app waits with `delay`, which is fine while a wake lock
 * pins the CPU and useless the moment it does not: neither `nanoTime` nor a coroutine
 * timer advances through deep sleep, so a three-hour doze would end three hours of
 * *awake* time later — which, on a phone in a pocket, is never.
 */
internal fun interface WakeAlarm {
    /** Arm for a wall-clock instant, or cancel when null. */
    fun armAt(atMs: Long?): Boolean
}

/**
 * Applies [LinkPowerPolicy] to the real device.
 *
 * It owns no schedule of its own. The engine says what it is doing ([LinkPowerFeed]),
 * the policy says what that means for the link, and this turns the answer into three
 * calls: the socket, the locks, the alarm. The order those happen in is the whole
 * correctness argument — see [apply].
 *
 * Runs for the lifetime of the foreground service rather than of a bridge session,
 * because the point is to keep deciding while there is no session at all.
 */
internal class LinkPowerController(
    private val feed: LinkPowerFeed,
    private val settingsProvider: EngineSettingsProvider,
    private val link: LinkSwitch,
    private val cpu: CpuHold,
    private val alarm: WakeAlarm,
    /** Emits whenever the stored settings change, so a mode switch acts at once. */
    private val settingsChanged: Flow<Unit>,
    /**
     * True whenever WhatsApp is actually connected — the feedback half of this loop.
     *
     * Everything else here is open loop: the policy decides, [apply] issues a call, and
     * [appliedState] records what was *asked for*. That is not the same as what the socket
     * is doing, and this model does not own the socket. The native runtime connects on its
     * own as soon as it has a paired device, whatsmeow reconnects by itself, a session
     * restart builds a fresh client — none of them ask, and any one of them can raise the
     * link in the middle of a doze. With only its own intent to go on the controller would
     * then sit on a recorded DOZING forever while the bot was fully online underneath it,
     * which is exactly what made every doze so far a lie.
     *
     * So the rule is: **the link coming up is news, whoever brought it up.** Treat it as
     * "awake now" and re-apply the plan, which puts it straight back down if the plan still
     * says down. That covers the whole class at once — the startup race where a sleep is
     * issued before any client exists and evaporates, an auto-reconnect, a manual
     * `reconnect`, a rebuilt session — instead of one watcher per way in.
     */
    private val linkConnected: Flow<Boolean>,
    private val policy: LinkPowerPolicy = LinkPowerPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
    /**
     * Every change of state, for the activity log.
     *
     * This model decides things while the socket is down and the screen is off, which
     * made every question about it — did the tap land, why is it still up, when was the
     * doze planned — unanswerable after the fact. One line per transition is enough to
     * answer all three, and transitions are rare by construction.
     */
    private val onDecision: (LinkState, Long?) -> Unit = { _, _ -> },
) {
    /**
     * The state the device is actually in. Starts AWAKE because the service acquires
     * its locks the moment it is promoted to the foreground, and this loop begins
     * after that — so "no change needed" is the correct first verdict, and no wake
     * action is issued against a bridge that is still connecting.
     */
    private var appliedState: LinkState = LinkState.AWAKE

    /** Actual transport truth, kept separate from the state the policy requested. */
    @Volatile
    private var actualConnected: Boolean = false

    /** Set by a [LinkPowerRequest.WAKE]; lapses on its own, or is cleared by a SLEEP. */
    private var manualWakeUntilMs: Long = 0L

    /** Drawn once per quiet-hours window — see [LinkPowerPolicy.plan]. */
    private var sleepWakeAtMs: Long = 0L

    /**
     * Drawn once per doze, for the same reason [sleepWakeAtMs] is: the gap is a random
     * draw, so re-deriving it on every re-plan would walk the wake time forward each
     * time anything poked the loop, and the armed alarm would never match the instant
     * the status line is showing.
     */
    private var dozeWakeAtMs: Long = 0L

    /**
     * The end of the low-mode session that is running, drawn once when the doze came
     * due and carried until it lapses. See [LinkPlan.sessionUntilMs].
     */
    private var sessionUntilMs: Long = 0L

    /**
     * "Something changed, look again." Conflated because that is all it ever means, and
     * fed by watchers that live for the whole run rather than by a subscription opened
     * per iteration: a tap or an activity note that lands while a plan is being applied
     * has to survive until the loop comes back round, not fall into the gap between two
     * subscriptions.
     */
    private val poked = Channel<Unit>(Channel.CONFLATED)

    /**
     * The loop's own scope, for the one side effect that must not be waited on.
     *
     * [LinkSwitch.showOnlineNow] is a 25-35 second presence window that suspends for its
     * whole length. Awaiting it inside [apply] wedged the loop for half a minute after
     * every wake: the new state was not published until it returned, so the status line
     * still read "Dozing" and every tap and settings change in those thirty seconds
     * looked like it had been swallowed. It is a fire-and-forget by nature — the dot is
     * a behaviour, not a transport step.
     */
    private var scope: CoroutineScope? = null

    suspend fun run() = coroutineScope {
        scope = this
        val subscribed = List(2) { CompletableDeferred<Unit>() }
        val watchers =
            listOf(
                launch {
                    feed.requests
                        .onSubscription { subscribed[0].complete(Unit) }
                        .collect { request ->
                            remember(request)
                            poked.trySend(Unit)
                        }
                },
                launch {
                    // A StateFlow replays what it is already holding, so the value the
                    // watcher opens on is not news — everything after it is.
                    var seen = feed.activity.value
                    feed.activity
                        .onSubscription { subscribed[1].complete(Unit) }
                        .collect { activity ->
                            if (activity == seen) return@collect
                            seen = activity
                            poked.trySend(Unit)
                        }
                },
                // Ungated, unlike the two above: a settings change needs somebody to have
                // tapped something, which cannot happen in the microseconds before the
                // loop starts, and the flow arrives here already transformed and so has
                // no subscription hook to wait on.
                launch { settingsChanged.collect { poked.trySend(Unit) } },
                launch {
                    var wasConnected = false
                    linkConnected.collect { connected ->
                        actualConnected = connected
                        feed.publishCarrying(connected && appliedState == LinkState.AWAKE)
                        val cameUp = connected && !wasConnected
                        val wentDown = !connected && wasConnected
                        wasConnected = connected
                        when {
                            cameUp -> {
                                // Force a real sleep if the policy still says down.
                                appliedState = LinkState.AWAKE
                                poked.trySend(Unit)
                            }

                            wentDown && appliedState == LinkState.AWAKE -> {
                                // The requested awake state no longer matches transport truth.
                                appliedState = LinkState.DOZING
                                poked.trySend(Unit)
                            }
                        }
                    }
                },
            )
        // Nothing is planned before the two hot sources are live. [LinkPowerFeed.requests]
        // has no replay, so a wake alarm firing into an unsubscribed channel would not be
        // late — it would never have happened, and the bot would stay asleep for good.
        subscribed.awaitAll()
        try {
            while (currentCoroutineContext().isActive) {
                val wakeAt = evaluate()
                awaitChange(wakeAt)
            }
        } finally {
            watchers.forEach { it.cancel() }
        }
    }

    /** Re-plan and apply once. Returns the instant the decision has to be revisited. */
    private suspend fun evaluate(): Long? {
        val now = nowMs()
        val settings =
            runCatching { settingsProvider.resolve("") }
                .onFailure { onFailure("settings", it) }
                .getOrNull()
                // Settings are unreadable — an unusual, probably transient database
                // problem. Staying awake is the safe direction: the bot keeps working
                // and the battery pays for it, rather than the link going down with no
                // stored schedule to tell it when to come back.
                ?: return null
        val plan =
            policy.plan(
                nowMs = now,
                settings = settings,
                activity = feed.activity.value,
                manualWakeUntilMs = manualWakeUntilMs,
                sleepWakeAtMs = sleepWakeAtMs,
                dozeWakeAtMs = dozeWakeAtMs,
                sessionUntilMs = sessionUntilMs,
                linkIsUp = appliedState == LinkState.AWAKE,
            )
        sleepWakeAtMs = if (plan.state == LinkState.SLEEPING) plan.wakeAtMs ?: 0L else 0L
        dozeWakeAtMs = if (plan.state == LinkState.DOZING) plan.wakeAtMs ?: 0L else 0L
        sessionUntilMs = plan.sessionUntilMs
        val changed = plan.state != appliedState
        // Published before it is applied, not after. Applying means awaiting a socket
        // that takes seconds to come up, and the status line is the only feedback a tap
        // has: showing the new state only once the websocket had finished connecting
        // made every tap look ignored for five to ten seconds, which is exactly long
        // enough to tap again. The line shows the decision; the decision is made here.
        feed.publish(plan.state, plan.wakeAtMs)
        if (changed) runCatching { onDecision(plan.state, plan.wakeAtMs) }
        apply(plan.state, plan.wakeAtMs)
        return plan.wakeAtMs
    }

    private suspend fun apply(
        state: LinkState,
        wakeAtMs: Long?,
    ) {
        val previous = appliedState
        // Recorded before the calls below rather than after: a failed disconnect that
        // left the state at AWAKE would retry the whole transition on every tick, and
        // a socket that is already down answers a second sleep with an error either way.
        // The cases that genuinely have to be re-issued — a sleep that reached no client,
        // one that raced a connect still in flight — are caught by the [linkConnected]
        // watcher instead, the only thing that reports the socket coming back on its own.
        appliedState = state
        // Said before the socket is touched, and only ever narrowing: a turn must never
        // start against a link that is on its way down, while one already running keeps
        // the link up by [LinkActivity.busy] and so cannot be cut off by this.
        if (state != LinkState.AWAKE) feed.publishCarrying(false)
        try {
            if (state == LinkState.AWAKE) {
                // Lock first, connect second. The reverse order gives the device a
                // window in which a socket is being opened with nothing holding the
                // CPU up, which is exactly the 60 s-EOF failure LinkKeepAlive documents.
                cpu.hold(true)
                alarm.armAt(null)
                if (previous != LinkState.AWAKE) {
                    link.wake()
                    // The dot, not just the transport: waking is either a self-session
                    // coming due or the operator tapping the status line, and both are
                    // "she picked the phone up". Launched rather than awaited — see [scope].
                    scope?.launch {
                        runCatching { link.showOnlineNow() }
                            .onFailure { if (it !is CancellationException) onFailure("dot", it) }
                    }
                }
                // Connect() starts an attempt. Only the bridge's Connected event may open the
                // engine gate.
                feed.publishCarrying(actualConnected)
                return
            }
            if (previous == LinkState.AWAKE) link.sleep()
            // Alarm before lock release, always. Between dropping the wake lock and
            // arming the alarm the device may already be suspended, and an alarm that
            // was never armed is a bot that never comes back.
            if (!alarm.armAt(wakeAtMs)) {
                // Keep the CPU alive so the coroutine deadline remains a degraded wake path.
                cpu.hold(true)
                onFailure("alarm", IllegalStateException("wake_alarm_not_armed"))
                return
            }
            cpu.hold(false)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            onFailure("apply", error)
            // Whatever failed, the device must not be left dozing without an alarm — nor
            // parked forever on a wake that threw. Connectivity has its own retry loop in
            // the runtime host; a turn waiting on a link this model believes should be up
            // is waiting on the wrong thing.
            if (state == LinkState.AWAKE) {
                cpu.hold(true)
                feed.publishCarrying(actualConnected)
            } else {
                if (!alarm.armAt(wakeAtMs)) cpu.hold(true)
            }
        }
    }

    /**
     * Wait for the next thing that could change the verdict — the engine's activity, a
     * settings change, an out-of-band request, all three arriving as one poke — or for
     * the deadline itself.
     *
     * The deadline is the least trustworthy of the two: it does not advance while the
     * CPU is suspended, so it is only a backstop for the case where the device stayed
     * awake anyway, and [WakeAlarm] is what actually ends a doze.
     */
    private suspend fun awaitChange(wakeAtMs: Long?) {
        if (wakeAtMs == null) {
            poked.receive()
            return
        }
        // Floored rather than allowed to be zero. A deadline that is already past would
        // otherwise re-plan with no wait at all, and if the plan keeps naming the same
        // instant — a clock jump, a sleep window whose end rounds to now — the loop spins
        // at full speed inside the one feature whose entire job is not to burn the battery.
        val remaining = (wakeAtMs - nowMs()).coerceAtLeast(MIN_WAIT_MS)
        withTimeoutOrNull(remaining) { poked.receive() }
    }

    private suspend fun remember(request: LinkPowerRequest) {
        when (request) {
            // Every tap is "stay up a while longer", counted from the end of whatever is
            // already running rather than from now — a fresh draw measured from now can
            // land *before* the deadline it replaces, and a tap that visibly changes
            // nothing is the reason nobody could tell this line was a control at all.
            // Bounded, because the taps are free and the battery is not.
            LinkPowerRequest.WAKE -> {
                val now = nowMs()
                val minutes =
                    runCatching { settingsProvider.resolve("").lowListenMinutes }
                        .onFailure { onFailure("settings", it) }
                        .getOrDefault(LinkPowerPolicy.DEFAULT_LISTEN_MINUTES)
                val running = maxOf(manualWakeUntilMs, feed.activity.value.listenUntilMs, now)
                // The ceiling is a few windows, not a few hours, and it is the *setting*
                // that scales it. A flat two-hour cap measured from each tap let a morning
                // of idle tapping walk the bedtime hours into the future — the status line
                // read "offline 12:27" at half past nine, which is not what anyone means by
                // tapping a control whose label says ten minutes.
                val ceiling = now + MAX_MANUAL_WINDOWS * minutes.coerceAtLeast(1) * 60_000L
                manualWakeUntilMs = minOf(running + policy.listenWindowMs(minutes), ceiling)
            }

            // A long press takes back every tap, the conversation tail *and* the session
            // that is running, or the bot would stay up for the rest of whichever of the
            // three happens to reach furthest. A turn that is mid-sentence still finishes
            // — the link drops the moment it ends, not in the middle of it.
            LinkPowerRequest.SLEEP -> {
                manualWakeUntilMs = 0L
                sessionUntilMs = 0L
                feed.clearListening()
            }

            LinkPowerRequest.EVALUATE -> Unit
        }
    }

    private companion object {
        /** Shortest gap between two re-plans, see [awaitChange]. */
        const val MIN_WAIT_MS = 1_000L

        /** How many configured listening windows a stack of taps may add up to. */
        const val MAX_MANUAL_WINDOWS = 3
    }
}
