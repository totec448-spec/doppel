package de.totec.doppel.runtime

import de.totec.doppel.security.privacySafeErrorType
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import de.totec.doppel.DoppelApplication
import de.totec.doppel.app.BotAppGraph
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.engine.LinkPowerFeed
import de.totec.doppel.engine.LinkState
import de.totec.doppel.integration.RepositoryEngineSettingsProvider
import de.totec.doppel.integration.RuntimeBridgeControl
import de.totec.doppel.transport.EmbeddedNativeBridge
import de.totec.doppel.transport.BridgeActionException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single user-started Android runtime for the bridge-connected bot.
 *
 * Android constraints captured here:
 * - A persistent linked-device text bridge uses remoteMessaging, not dataSync.
 * - Foreground promotion happens before network or engine initialization.
 * - START_STICKY is honored only while the durable runRequested flag is true.
 * - The service still refuses to fight Android with polling, restart alarms, or
 *   a second process. It does hold a wake lock, because suspend is not a policy
 *   disagreement but the measured cause of a websocket that died every 60 s with
 *   the screen off; see [LinkKeepAlive] for the evidence.
 */
class BridgeForegroundService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO + CoroutineName("bot-runtime-service"),
    )

    /**
     * Lifecycle commands describe the latest desired state, so retaining a
     * backlog of repeated notification taps adds no value. Conflation keeps
     * memory constant; desiredRunning plus the persisted runRequested flag
     * resolve races while an older command is already being processed.
     */
    private val commands = Channel<RuntimeCommand>(capacity = Channel.CONFLATED)
    private val backoff = FullJitterBackoff()

    private lateinit var preferences: RuntimePreferences
    private lateinit var networkMonitor: ValidatedNetworkMonitor
    private lateinit var notificationController: RuntimeNotificationController
    private lateinit var keepAlive: LinkKeepAlive
    private var linkPower: LinkPowerFeed? = null

    private var runtimeJob: Job? = null
    private var linkPowerJob: Job? = null
    @Volatile
    private var lastStartId: Int = 0
    private var actorJob: Job? = null

    @Volatile
    private var foregroundStarted = false

    /**
     * Process-local mirror of the latest delivered command. Persistence is the
     * source for sticky recreation; this mirror makes a current Stop win even
     * if a SharedPreferences commit fails, while a later Start can still win
     * an in-flight orderly shutdown.
     */
    @Volatile
    private var desiredRunning = false

    @Volatile
    private var orderlyStopInProgress = false
    @Volatile
    private var serviceDestroyed = false
    /** Invalidates late reports from a host that outlived its bounded cancellation window. */
    private val sessionGeneration = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        preferences = RuntimePreferences(this)
        desiredRunning = preferences.runRequested
        networkMonitor = ValidatedNetworkMonitor(this)
        keepAlive = LinkKeepAlive.forService(this)
        notificationController = RuntimeNotificationController(this).also {
            it.createChannel()
        }
        actorJob = serviceScope.launch {
            for (command in commands) {
                when (command) {
                    RuntimeCommand.Start -> ensureRuntimeRunning()
                    RuntimeCommand.Reconnect -> restartRuntime()
                    RuntimeCommand.Stop -> stopOrderly()
                }
            }
        }
        startLinkPower()
    }

    /**
     * The link power loop runs for the whole service, not per bridge session. Its
     * entire job is to decide things while the socket is *down*, so tying it to a
     * session would switch it off exactly when it is needed. It only ever acts in low
     * power mode or during the quiet hours; in default daytime it evaluates once,
     * concludes AWAKE and then sits on a flow.
     */
    private fun startLinkPower() {
        if (linkPowerJob?.isActive == true) return
        val application = application as? DoppelApplication ?: return
        val graph = application.graph
        val feed = graph.linkPower
        val first = linkPower == null
        linkPower = feed
        val controller =
            LinkPowerController(
                feed = feed,
                settingsProvider =
                    RepositoryEngineSettingsProvider(graph.settings, graph.repository),
                link =
                    object : LinkSwitch {
                        override suspend fun sleep() = RuntimeBridgeControl.sleepLink()

                        override suspend fun wake() = RuntimeBridgeControl.wakeLink()

                        override suspend fun showOnlineNow() =
                            RuntimeBridgeControl.showOnlineNow()
                    },
                cpu = { held -> if (held) keepAlive.acquire() else keepAlive.release() },
                alarm = LinkWakeAlarm(this),
                settingsChanged = graph.settings.changes.drop(1).map { },
                linkConnected = RuntimeBridgeControl.linkConnected,
                onFailure = { stage, error ->
                    val code = (error as? BridgeActionException)?.code ?: error.javaClass.simpleName
                    Log.w(TAG, "Link power $stage failed: $code")
                },
                onDecision = { state, wakeAt -> recordLinkDecision(graph, state, wakeAt) },
            )
        linkPowerJob =
            serviceScope.launch(CoroutineName("link-power")) { controller.run() }
        // The loop is restartable — a stop ends it — but the notification line watches the feed,
        // not the loop, so it is subscribed once for the life of the service.
        if (!first) return
        serviceScope.launch(CoroutineName("link-power-notice")) {
            combine(feed.state, feed.wakeAtMs) { state, wakeAt ->
                sleepNotice(state, wakeAt)
            }.collect { note ->
                if (foregroundStarted) {
                    notificationController.setSleepNote(note, RuntimeStateStore.state.value)
                }
            }
        }
    }

    /**
     * One activity row per link transition: what it did, and how long it means to.
     *
     * Off the loop's thread, because a database write is not something the schedule may
     * wait on, and swallowed on failure for the same reason — a log line that can take
     * the link down with it is worse than no log line.
     */
    private fun recordLinkDecision(
        graph: BotAppGraph,
        state: LinkState,
        wakeAtMs: Long?,
    ) {
        serviceScope.launch(CoroutineName("link-power-log")) {
            runCatching {
                val until =
                    wakeAtMs?.let {
                        val clock =
                            java.time.Instant.ofEpochMilli(it)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(WAKE_CLOCK)
                        when (state) {
                            LinkState.AWAKE -> " · listening until $clock"
                            else -> " · back around $clock"
                        }
                    } ?: ""
                val what =
                    when (state) {
                        LinkState.AWAKE -> "Link up"
                        LinkState.DOZING -> "Link dozing between sessions"
                        LinkState.SLEEPING -> "Link down for the quiet hours"
                    }
                withContext(Dispatchers.IO) {
                    graph.repository.appendActivity(
                        ActivityLogRecord(
                            level = ActivityLevel.INFO,
                            category = "link_power",
                            action = state.name.lowercase(),
                            summary = what + until,
                        ),
                    )
                }
                graph.controls.notifyActivityChanged()
            }
        }
    }

    /**
     * The notification's one line while the link is down on purpose. Null while it is
     * up, which is what puts the ordinary phase line back.
     */
    private fun sleepNotice(state: LinkState, wakeAtMs: Long?): String? {
        if (state == LinkState.AWAKE) return null
        val label = if (state == LinkState.SLEEPING) "Sleeping" else "Dozing"
        val wake = wakeAtMs ?: return label
        val clock =
            java.time.Instant.ofEpochMilli(wake)
                .atZone(java.time.ZoneId.systemDefault())
                .format(WAKE_CLOCK)
        return "$label · back around $clock"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId

        when (intent?.action) {
            RuntimeServiceController.ACTION_STOP -> {
                desiredRunning = false
                if (!preferences.requestStop()) {
                    Log.e(TAG, "Stopped state could not be persisted")
                }
                commands.trySend(RuntimeCommand.Stop)
            }

            RuntimeServiceController.ACTION_LINK_WAKE -> {
                desiredRunning = preferences.runRequested
                if (desiredRunning && promoteToForeground()) {
                    // The alarm's own wake lock lasts only as long as this call, so the
                    // locks are taken back here rather than in the controller: by the
                    // time a coroutine is scheduled the device may be suspended again.
                    // The controller releases them if it decides to keep dozing.
                    keepAlive.acquire()
                    linkPower?.requestEvaluation()
                    // The alarm is also the recovery path. If the system killed the
                    // process while it was dozing — the one thing a released wake lock
                    // makes possible — this is the callback that recreates it, and the
                    // runtime loop has to come back with it. A no-op when it is running.
                    commands.trySend(RuntimeCommand.Start)
                } else {
                    stopSelfResult(startId)
                }
            }

            RuntimeServiceController.ACTION_RECONNECT -> {
                desiredRunning = preferences.runRequested
                if (desiredRunning && promoteToForeground()) {
                    commands.trySend(RuntimeCommand.Reconnect)
                } else {
                    stopSelfResult(startId)
                }
            }

            RuntimeServiceController.ACTION_START, null -> {
                desiredRunning = preferences.runRequested
                if (desiredRunning && promoteToForeground()) {
                    commands.trySend(RuntimeCommand.Start)
                } else {
                    stopSelfResult(startId)
                }
            }

            else -> {
                desiredRunning = preferences.runRequested
                if (desiredRunning && promoteToForeground()) {
                    commands.trySend(RuntimeCommand.Start)
                } else {
                    stopSelfResult(startId)
                }
            }
        }

        return if (desiredRunning) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceDestroyed = true
        sessionGeneration.incrementAndGet()
        linkPowerJob?.cancel()
        // A pending wake alarm outlives the process it was armed from, so an explicit
        // teardown has to take it with it — otherwise a stopped bot resurrects itself
        // hours later with no user having asked for it.
        LinkWakeAlarm(this).armAt(null)
        keepAlive.release()
        networkMonitor.stop()
        commands.close()
        actorJob?.cancel()
        serviceScope.cancel()
        // A sticky restart creates a new native owner. Never leave the old linked-device core or
        // loopback server alive after Android has destroyed this service instance.
        EmbeddedNativeBridge.shutdown()

        if (!desiredRunning && foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationController.cancel()
        }
        foregroundStarted = false

        RuntimeStateStore.dispatch(RuntimeStateEvent.Stopped)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // remoteMessaging currently has no platform duration timeout. Keep a
        // defensive implementation so future platform changes stop cleanly
        // instead of producing a RemoteServiceException.
        Log.e(TAG, "Foreground service timed out; type=$fgsType")
        desiredRunning = false
        if (!preferences.requestStop()) {
            Log.e(TAG, "Timeout stop state could not be persisted")
        }
        commands.trySend(RuntimeCommand.Stop)
    }

    private fun promoteToForeground(): Boolean {
        if (foregroundStarted) return true

        val state = publish(RuntimeStateEvent.StartRequested)
        return try {
            val notification = notificationController.buildForeground(state)
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                RuntimeNotificationController.NOTIFICATION_ID,
                notification,
                serviceType,
            )
            foregroundStarted = true
            // Paired with promotion, not with the socket: a link that is merely retrying still
            // needs the CPU awake to get its next attempt out on time.
            keepAlive.acquire()
            true
        } catch (error: RuntimeException) {
            Log.e(
                TAG,
                "Unable to promote bot runtime to foreground: ${privacySafeErrorType(error)}",
            )
            desiredRunning = false
            if (!preferences.requestStop()) {
                Log.e(TAG, "Failed-start stop state could not be persisted")
            }
            publish(
                RuntimeStateEvent.FatalError(
                    "The background service could not be started",
                ),
            )
            stopSelf()
            false
        }
    }

    private fun ensureRuntimeRunning() {
        if (
            !RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = desiredRunning,
                runRequested = preferences.runRequested,
                orderlyStopInProgress = orderlyStopInProgress,
                loopActive = runtimeJob?.isActive == true,
            )
        ) {
            return
        }

        if (!networkMonitor.start()) {
            publish(
                RuntimeStateEvent.FatalError(
                    "The network status could not be read",
                ),
            )
            return
        }
        // A stop ends the link power loop, and a service can survive its own stop when a Start
        // arrives while it is winding down. Starting the bot has to bring its schedule back with it.
        startLinkPower()
        runtimeJob = serviceScope.launch(CoroutineName("bot-runtime-loop")) {
            runRuntimeLoop()
        }
    }

    private suspend fun restartRuntime() {
        if (!RuntimeRestartPolicy.shouldRestart(desiredRunning, preferences.runRequested)) return
        // A user-triggered reconnect outranks an orderly stop that is still winding down; without
        // this the restart fell through `ensureRuntimeRunning`'s guard and did nothing at all.
        orderlyStopInProgress = false
        stopRuntimeJob()
        // Reconnect is the operator's escape hatch, so it rebuilds everything — including the
        // native core, which an ordinary session end deliberately leaves running. Anything less
        // and "Reconnect" would silently mean "reopen the loopback socket".
        EmbeddedNativeBridge.shutdown()
        publish(RuntimeStateEvent.StartRequested)
        ensureRuntimeRunning()
    }

    /** Cancels the runtime loop with a bounded wait; see [RuntimeRestartPolicy.cancelWithinDeadline]. */
    private suspend fun stopRuntimeJob(): Boolean {
        val job = runtimeJob ?: return true
        runtimeJob = null
        sessionGeneration.incrementAndGet()
        val joined = RuntimeRestartPolicy.cancelWithinDeadline(job, RUNTIME_JOIN_TIMEOUT_MS)
        if (!joined) {
            // A cancelled Kotlin host may be stuck in a blocking provider/native call. Stop the
            // process-global Go runtime directly before a replacement is allowed to bind or let a
            // second whatsmeow client auto-reconnect against the same account.
            EmbeddedNativeBridge.shutdown()
            Log.w(TAG, "Runtime loop exceeded ${RUNTIME_JOIN_TIMEOUT_MS} ms; native transport forced closed")
        }
        return joined
    }

    private suspend fun stopOrderly() {
        if (orderlyStopInProgress) return
        orderlyStopInProgress = true
        publish(RuntimeStateEvent.Stopping)

        stopRuntimeJob()
        networkMonitor.stop()

        // A newer explicit Start can arrive while cancellation is completing.
        // Its in-process and persisted true state wins, so an older Stop cannot
        // tear it down.
        if (desiredRunning && preferences.runRequested) {
            orderlyStopInProgress = false
            ensureRuntimeRunning()
            return
        }

        // The native core outlives a session but not a stop. This is the one place a user-visible
        // "stopped" has to mean the linked device is actually off the air.
        EmbeddedNativeBridge.shutdown()
        publish(RuntimeStateEvent.Stopped)
        // Stop means stop: no armed alarm, no schedule, and nothing left claiming the bot is
        // merely asleep. The loop is stopped *before* the feed is cleared — it is the only writer,
        // and one more plan applied after the reset would put "Sleeping · back 9:05" straight back
        // on a landing page whose bot is off. The feed lives on the app graph, so it survives this
        // service and would keep saying it for as long as the app is open.
        linkPowerJob?.cancelAndJoin()
        linkPowerJob = null
        LinkWakeAlarm(this).armAt(null)
        linkPower?.reset()
        keepAlive.release()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationController.cancel()
            foregroundStarted = false
        }
        stopSelfResult(lastStartId)
    }

    private suspend fun runRuntimeLoop() {
        var reconnectAttempt = 0
        // Sessions that failed without ever standing on their own feet. The native core survives an
        // ordinary session end on purpose, so this counter is what still distinguishes "the loopback
        // socket hiccuped" from "the native core is the thing that is broken".
        var unstableSessions = 0

        while (desiredRunning && preferences.runRequested) {
            publish(RuntimeStateEvent.WaitingForNetwork)
            networkMonitor.state.filter { state -> state.isValidated }.first()
            if (!desiredRunning || !preferences.runRequested) return

            publish(RuntimeStateEvent.Connecting())
            val onlineSinceMs = AtomicLong(0L)
            val session =
                RuntimeHostSessionImpl(
                    onlineSinceMs = onlineSinceMs,
                    generation = sessionGeneration.incrementAndGet(),
                )
            val initialNetworkHandle = networkMonitor.state.value.networkHandle
            val sessionEnd = try {
                val host = RuntimeDependencies.createHost(applicationContext)
                runHostUntilNetworkLoss(
                    host = host,
                    session = session,
                    initialNetworkHandle = initialNetworkHandle,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Keep exception messages and response bodies out of logs and
                // lock-screen notifications: they may contain provider data.
                Log.e(TAG, "Bot host setup failed: ${error.javaClass.simpleName}")
                HostSessionEnd.Finished(
                    RuntimeHostResult.RetryableFailure("Internal bot engine error"),
                )
            }

            when (val end = sessionEnd) {
                HostSessionEnd.NetworkLost -> {
                    // Wait for the next validated default network without a
                    // timer or retry increment.
                    reconnectAttempt = 0
                    continue
                }

                is HostSessionEnd.Finished -> {
                    if (!desiredRunning || !preferences.runRequested) return
                    // Reaching ONLINE is not evidence of a healthy session. A session that connects
                    // and dies again a second later used to reset the backoff every single time,
                    // which turned one reproducibly failing event into a sub-second relink loop
                    // against WhatsApp. Only a session that *stayed* up earns the reset.
                    val onlineSince = onlineSinceMs.get()
                    val stable =
                        onlineSince > 0L &&
                            System.currentTimeMillis() - onlineSince >= STABLE_SESSION_MS
                    if (stable) {
                        reconnectAttempt = 0
                        unstableSessions = 0
                    }

                    when (val result = end.result) {
                        is RuntimeHostResult.TerminalFailure -> {
                            // Nothing is going to reconnect, so the native core has no reason to
                            // hold a linked-device socket open while an operator is asleep.
                            EmbeddedNativeBridge.shutdown()
                            unstableSessions = 0
                            publish(RuntimeStateEvent.AttentionRequired(result.detail))
                            // No socket exists in this state and nothing will reconnect on
                            // its own, so holding CPU and Wi-Fi awake only drains the
                            // battery. The lock is restored on the way out because
                            // `promoteToForeground` short-circuits on an already-foreground
                            // service and would not re-acquire it for the next attempt.
                            keepAlive.release()
                            try {
                                // A user Reconnect or Stop cancels this zero-CPU wait.
                                kotlinx.coroutines.awaitCancellation()
                            } finally {
                                if (!serviceDestroyed) keepAlive.acquire()
                            }
                        }

                        RuntimeHostResult.Completed,
                        is RuntimeHostResult.RetryableFailure,
                        -> {
                            if (!stable) {
                                unstableSessions += 1
                                // Reusing the native core is the whole point, but it must not turn
                                // into "never restart it". A run of sessions that never stabilised
                                // is the one signal that the core itself, not the loopback socket,
                                // is what keeps failing.
                                if (unstableSessions >= NATIVE_RESTART_AFTER_UNSTABLE_SESSIONS) {
                                    Log.w(
                                        TAG,
                                        "Restarting the native transport after " +
                                            "$unstableSessions unstable sessions",
                                    )
                                    EmbeddedNativeBridge.shutdown()
                                    unstableSessions = 0
                                }
                            }
                            val delayMs = backoff.nextDelay(reconnectAttempt)
                            val detail = (result as? RuntimeHostResult.RetryableFailure)?.detail
                            publish(
                                RuntimeStateEvent.RetryScheduled(
                                    attempt = reconnectAttempt,
                                    delayMs = delayMs,
                                    detail = detail,
                                ),
                            )
                            if (reconnectAttempt < Int.MAX_VALUE) {
                                reconnectAttempt++
                            }
                            if (awaitRetryDelayOrNetworkChange(delayMs)) {
                                reconnectAttempt = 0
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Backoff is a single suspended timer, raced against callback-driven
     * default-network loss/handover. A network transition cancels the stale
     * timer so reconnection can resume as soon as Android validates a network.
     */
    private suspend fun awaitRetryDelayOrNetworkChange(delayMs: Long): Boolean = coroutineScope {
        val initialNetworkHandle = networkMonitor.state.value.networkHandle
        val retryDelay = async {
            delay(delayMs)
        }
        val networkChanged = async {
            networkMonitor.state.filter { state ->
                RuntimeNetworkPolicy.shouldRestartSession(
                    initialNetworkHandle = initialNetworkHandle,
                    isValidated = state.isValidated,
                    currentNetworkHandle = state.networkHandle,
                )
            }.first()
        }

        select {
            retryDelay.onAwait {
                networkChanged.cancel()
                false
            }
            networkChanged.onAwait {
                retryDelay.cancelAndJoin()
                true
            }
        }
    }

    private suspend fun runHostUntilNetworkLoss(
        host: BotRuntimeHost,
        session: RuntimeHostSession,
        initialNetworkHandle: Long?,
    ): HostSessionEnd = coroutineScope {
        val hostWork = async {
            try {
                host.run(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The concrete exception message can contain a remote URL,
                // response body, identifier, or token. Log only its type.
                Log.e(TAG, "Injected bot host failed: ${error.javaClass.simpleName}")
                RuntimeHostResult.RetryableFailure(
                    "Internal bot engine error",
                )
            }
        }
        val networkLost = async {
            // A validated Wi-Fi/cellular/VPN handover also restarts the
            // session, so a socket cannot remain pinned to the old default
            // network until a slow application heartbeat happens to fail.
            networkMonitor.state.filter { state ->
                RuntimeNetworkPolicy.shouldRestartSession(
                    initialNetworkHandle = initialNetworkHandle,
                    isValidated = state.isValidated,
                    currentNetworkHandle = state.networkHandle,
                )
            }.first()
        }

        select {
            hostWork.onAwait { result ->
                networkLost.cancel()
                HostSessionEnd.Finished(result)
            }
            networkLost.onAwait {
                sessionGeneration.incrementAndGet()
                val joined =
                    RuntimeRestartPolicy.cancelWithinDeadline(
                        hostWork,
                        RUNTIME_JOIN_TIMEOUT_MS,
                    )
                if (!joined) {
                    // Network handovers need the same bounded cancellation guarantee as an
                    // explicit Reconnect. Otherwise one blocking provider/native call can pin the
                    // runtime actor forever and prevent it from binding to the new default network.
                    EmbeddedNativeBridge.shutdown()
                    Log.w(
                        TAG,
                        "Host exceeded ${RUNTIME_JOIN_TIMEOUT_MS} ms after network loss; " +
                            "native transport forced closed",
                    )
                }
                HostSessionEnd.NetworkLost
            }
        }
    }

    private fun publish(event: RuntimeStateEvent): RuntimeState {
        val state = RuntimeStateStore.dispatch(event)
        if (foregroundStarted) {
            notificationController.update(state)
        }
        return state
    }

    private inner class RuntimeHostSessionImpl(
        private val onlineSinceMs: AtomicLong,
        private val generation: Long,
    ) : RuntimeHostSession {
        override val network = networkMonitor.state

        override fun report(report: RuntimeHostReport) {
            if (generation != sessionGeneration.get()) return
            when (report.phase) {
                RuntimeHostPhase.CONNECTING -> publish(
                    RuntimeStateEvent.Connecting(report.detail),
                )

                RuntimeHostPhase.ONLINE -> {
                    // First transition only: the runtime re-announces `connected` legitimately, and
                    // the caller is measuring how long this session has been standing, not when it
                    // last said so.
                    onlineSinceMs.compareAndSet(0L, System.currentTimeMillis())
                    publish(RuntimeStateEvent.Online(report.detail))
                }

                RuntimeHostPhase.DEGRADED -> {
                    // A degraded report can be the socket's initial
                    // DISCONNECTED state. Treating it as proof of a prior
                    // healthy WhatsApp session resets exponential backoff on
                    // every failed attempt and creates a battery-heavy retry
                    // loop. Only the explicit ONLINE phase earns that reset.
                    publish(
                        RuntimeStateEvent.Connecting(
                            report.detail ?: RuntimeText.CONNECTION_DEGRADED,
                        ),
                    )
                }

                RuntimeHostPhase.AUTHENTICATION_REQUIRED -> publish(
                    RuntimeStateEvent.AttentionRequired(
                        report.detail ?: "Bridge sign-in is required",
                    ),
                )

                RuntimeHostPhase.ENGINE_UNAVAILABLE -> publish(
                    RuntimeStateEvent.EngineUnavailable(
                        report.detail ?: RuntimeText.ENGINE_UNAVAILABLE,
                    ),
                )
            }
        }
    }

    private enum class RuntimeCommand {
        Start,
        Reconnect,
        Stop,
    }

    private sealed interface HostSessionEnd {
        data object NetworkLost : HostSessionEnd
        data class Finished(val result: RuntimeHostResult) : HostSessionEnd
    }

    private companion object {
        const val TAG = "BridgeRuntimeService"

        const val RUNTIME_JOIN_TIMEOUT_MS = RuntimeRestartPolicy.JOIN_TIMEOUT_MS

        /**
         * How long a session has to stay online before it counts as healthy.
         *
         * Long enough that a connect-and-die cycle cannot masquerade as progress, short enough that
         * a normally working bot is never held at a long backoff.
         */
        const val STABLE_SESSION_MS = 60_000L

        /** Consecutive sessions that never stabilised before the native core is restarted. */
        const val NATIVE_RESTART_AFTER_UNSTABLE_SESSIONS = 4

        /** Same 24-hour shape the chat list uses for a wake time. */
        val WAKE_CLOCK: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }
}
