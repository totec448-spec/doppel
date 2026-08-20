package de.totec.doppel.engine

import de.totec.doppel.ai.HistoryLabelGuard
import de.totec.doppel.domain.IncomingEvent
import de.totec.doppel.domain.ChatEventKind
import de.totec.doppel.domain.MediaHistoryLabels
import de.totec.doppel.domain.MediaKind
import java.io.Closeable
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Event-driven runtime: no heartbeat, polling loop, or permanent wake lock.
 *
 * The bridge event is durably committed before any gate or acknowledgement.
 * At most one visible AI/send turn runs globally. New input in that same chat
 * cancels the incomplete turn and replaces it with a combined batch; input in
 * another chat waits without interrupting.
 */
class BotEngine(
    parentScope: CoroutineScope,
    private val store: EngineStore,
    private val settingsProvider: EngineSettingsProvider,
    private val commands: AdminCommandGateway,
    private val ai: AiTurnRunner,
    private val whatsapp: WhatsAppActions,
    private val proactivePersistence: ProactivePersistence? = null,
    /**
     * Where the live per-chat stage is published, so a screen can show what this engine is doing
     * while it does it. Defaults to a private instance nobody reads, which is what tests want.
     */
    private val activity: ChatActivityFeed = ChatActivityFeed(),
    /**
     * Where the engine tells the transport whether the link may drop. Defaults to a
     * private instance nobody reads, which is what tests and the default-power path
     * want — in default mode the link is up regardless of anything written here.
     */
    private val linkPower: LinkPowerFeed = LinkPowerFeed(),
    private val clock: EngineClock = SystemEngineClock,
    private val timing: HumanTimingPolicy = HumanTimingPolicy(),
    private val linkPolicy: LinkPowerPolicy = LinkPowerPolicy(),
    /**
     * Whether she picks the phone up on her own every few hours ([runSelfSessions]).
     *
     * On in production. Off in tests that drive a fast-forwarding clock: a scheduler
     * loop against a clock whose delay costs nothing has no natural end, and it would
     * starve the very turns those tests are about.
     */
    // Off by default: autonomous online windows generate remote presence traffic without a user
    // or a pending send. Real reply presence remains unchanged and profile/persona changes still
    // run exactly when the user changes them.
    private val selfOnlineSessions: Boolean = false,
    /**
     * Swaps the account's own profile picture every few weeks, during a session
     * she is online anyway — which is when a person would do it. Absent in tests
     * and whenever the host has no picture set bundled.
     */
    private val profilePictures: ProfilePictureRotation? = null,
) : Closeable {
    private val engineJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope =
        CoroutineScope(parentScope.coroutineContext + engineJob + CoroutineName("native-bot-engine"))
    private val readyTurns = Channel<QueuedTurn>(capacity = READY_CAPACITY)

    /** Chats waiting for the next shared pickup window, in arrival order. */
    private val pendingPickups = LinkedHashMap<String, PendingPickup>()
    private val chatGenerations = HashMap<String, Long>()
    /**
     * Accepted reactive inputs that have not reached a terminal engine disposition yet.
     *
     * A newer burst invalidates an older queued generation. Keeping the actual inputs here lets the
     * replacement generation absorb them instead of merely relying on history text and leaving
     * their durable event claims unfinished until a process restart.
     */
    private val outstandingReactiveEvents =
        HashMap<String, LinkedHashMap<String, IncomingEvent>>()
    /**
     * Reactive inputs already completed by this engine instance.
     *
     * Reconnect recovery can race a live delivery and enqueue the same durable input twice before
     * the first answer reaches the chat mirror. The global worker is the final serialization
     * boundary, so it suppresses a later queued copy here. Cancelled, failed and deferred turns
     * are never added and therefore remain recoverable.
     */
    private val completedReactiveEventIds = LinkedHashSet<String>()

    /**
     * Messages already acknowledged by the immediate read receipt in [accept].
     *
     * The receipt is sent the moment a message lands in the chat she is still
     * sitting in, long before the turn that answers it starts. The turn must not
     * send the same receipt a second time — it is a wasted round trip, and on a
     * slow link it can land after the reply itself.
     */
    private val earlyReadMessageIds = LinkedHashSet<String>()
    private val batchLock = Any()

    /** The one global online window; `null` while nothing is waiting. */
    private var onlinePickupAtMs: Long? = null
    private var onlinePickupFloorAtMs: Long = 0L
    private var pickupReductions: Int = 0
    private var pickupWindowGeneration: Long = 0L
    private var pickupTimer: Job? = null
    private val session = OnlineSessionClock(clock)

    /**
     * The visible dot drives the behavioural clock, rather than the two keeping
     * separate windows that were only configured to look alike. They drifted
     * before: the dot went out ten to thirty seconds after the last bubble while
     * the engine kept treating the chat as open for two minutes, so a message sent
     * to a bot that was visibly offline still got read the instant it arrived.
     */
    private val presence =
        PresenceCoordinator(
            scope = scope,
            whatsapp = whatsapp,
            clock = clock,
            listener =
                object : PresenceCoordinator.Listener {
                    override fun onWentOnline(chatJid: String?) {
                        session.noteWentOnline(chatJid, clock.wallTimeMillis())
                    }

                    override fun onWentOffline() {
                        session.noteWentOffline(clock.wallTimeMillis())
                    }
                },
        )

    /** The self-initiated online sessions; `null` until [startRecovery]. */
    private var selfSessionJob: Job? = null
    private val activeLock = Any()
    private val paused = AtomicBoolean(false)

    /**
     * Memory consolidations still running, by conversation key.
     *
     * Consolidation is launched detached so it cannot delay the reply that triggered it, but the
     * *next* turn of the same conversation must not start generating while it runs: it would load
     * the memory the refresh is about to replace and answer from a stale one.
     */
    private val memoryRefreshJobs =
        java.util.concurrent.ConcurrentHashMap<String, MemoryRefreshJob>()

    /** A persona/global synthesis replaces context shared by every chat, so every chat waits. */
    private val globalMemoryRefresh = AtomicReference<MemoryRefreshJob?>(null)

    /**
     * Opens a per-conversation gate for work that replaces memory outside the normal post-send
     * cadence: a chat import, or "Create Memory" pressed by hand.
     *
     * Both used to run entirely outside the engine's view, which is why the bot kept answering
     * through a memory write it had not started itself — it read the memory the write was about to
     * replace. Import holds with [mustComplete] because a turn must never see its temporary staged
     * rows; a by-hand write does not, so a hung provider costs the chat the bounded
     * [MEMORY_REFRESH_WAIT_MS] wait and not the rest of the day.
     */
    internal fun holdForMemoryWrite(
        conversationKey: String,
        mustComplete: Boolean = true,
    ): MemoryWriteHold {
        val gate = CompletableDeferred<Unit>()
        var previousMemory: Job? = null
        val held = MemoryRefreshJob(job = gate, mustComplete = mustComplete)
        memoryRefreshJobs.compute(conversationKey) { _, existing ->
            previousMemory = existing?.job?.takeUnless { it.isCompleted }
            held
        }
        val active =
            synchronized(activeLock) {
                activeTurn
                    ?.takeIf { it.events.first().queueKey() == conversationKey && it.job.isActive }
                    ?.job
            }
        gate.invokeOnCompletion { memoryRefreshJobs.remove(conversationKey, held) }
        return MemoryWriteHold(
            previous = listOfNotNull(previousMemory, active).distinct(),
            release = { gate.complete(Unit) },
        )
    }

    /**
     * Closes a process-wide conversation gate before a global/persona memory call starts.
     * The owner waits for the visible turn already in progress, then rewrites and releases. New
     * turns cannot slip between those two steps and read the old persona memory.
     */
    internal fun holdAllForMemoryWrite(): MemoryWriteHold {
        val gate = CompletableDeferred<Unit>()
        val held = MemoryRefreshJob(job = gate, mustComplete = true)
        val previous = globalMemoryRefresh.getAndSet(held)?.job?.takeUnless { it.isCompleted }
        val active =
            synchronized(activeLock) {
                activeTurn?.job?.takeIf { it.isActive }
            }
        gate.invokeOnCompletion { globalMemoryRefresh.compareAndSet(held, null) }
        return MemoryWriteHold(
            previous = listOfNotNull(previous, active).distinct(),
            release = { gate.complete(Unit) },
        )
    }

    /**
     * Per chat: a low-frequency composing hand-off after an interrupt.
     *
     * The replacement turn cancels and owns it. Without this hand-off, cancelling the old
     * generation makes WhatsApp drop composing exactly when the new message arrives.
     */
    private val pendingTypingStops = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val proactive =
        proactivePersistence?.let { persistence ->
            ProactiveCoordinator(
                parentScope = scope,
                persistence = persistence,
                settingsProvider = settingsProvider,
                canContact = { seed, settings ->
                    store.accessDecision(seed.lastInbound, settings.allowAll).allowed
                },
                execute = ::enqueueProactive,
                wakeForScheduled = linkPower::requestWake,
                publishScheduledWake = linkPower::noteScheduledFollowUpAt,
                inOnlineSession = session::inSession,
                nextOnlineSessionAt = {
                    linkPower.activity.value.nextSessionAtMs.takeIf { it > 0L }
                },
                clock = clock,
            )
        }
    private val worker: Job

    /**
     * The visible turn currently running, or `null` while idle.
     *
     * Held as one object so the intake path can decide, under a single lock,
     * both *whether* to interrupt and *what the interrupted turn still owed the
     * user* — the two must not be observed separately.
     */
    private class ActiveTurn(
        val chatJid: String,
        val turnId: String,
        val events: List<IncomingEvent>,
        val proactive: Boolean,
        val job: Job,
    ) {
        /**
         * Set once this turn has produced something the contact can see. From
         * that moment its inputs are answered, so an interrupt must not replay
         * them into the follow-up batch.
         */
        @Volatile
        var answered: Boolean = false
    }

    @Volatile
    private var activeTurn: ActiveTurn? = null

    init {
        worker = scope.launch(CoroutineName("visible-turn-worker")) { runWorker() }
    }

    suspend fun startRecovery() {
        paused.set(false)
        // Seed the session from the persisted last send so a restart neither
        // thinks the bot has been away forever (long cold pickup for the first
        // message) nor resurrects a session that ended hours ago.
        session.seed(store.lastOnlineAt())
        // A reply that was already waiting when the link dropped is picked up
        // where it left off. Answering everything at once on reconnect was the
        // opposite of what the human delay is for: a burst of instant replies to
        // messages that are minutes old is exactly the pattern the pacing exists
        // to avoid.
        val recoveredAtMs = clock.wallTimeMillis()
        store.recoverPending().forEach { events ->
            if (events.isEmpty()) return@forEach
            val arrivedAtMs = events.minOf(IncomingEvent::timestampMs)
            scheduleBatch(
                events = events,
                immediate = false,
                waitedMs = (recoveredAtMs - arrivedAtMs).coerceAtLeast(0L),
            )
        }
        // A persona switched while the app was down, or a picture the bridge
        // could not take when it was switched, is put right here — this is the
        // first moment in a run where there is something to apply it with.
        syncProfilePersona()
        proactive?.start()
        if (selfOnlineSessions && selfSessionJob?.isActive != true) {
            selfSessionJob = scope.launch(CoroutineName("self-online-session")) { runSelfSessions() }
        }
    }

    /**
     * Picks the phone up every few hours for no reason at all.
     *
     * Without this the online dot is a perfect function of the contact's own
     * messages — she is never online except in the seconds around an answer, which
     * no real account looks like. It costs nothing: a presence stanza, no model
     * call, no message.
     *
     * The gap is measured from the end of the *last* online window, whatever
     * opened it, so the two can never overlap. An answer at 14:00 pushes the next
     * self-session out to 17:00-19:00 instead of leaving one scheduled for 14:20
     * that would put her online again minutes after she was last there.
     */
    private suspend fun runSelfSessions() {
        var anchorMs = session.offlineSince()
        var nextAtMs = planSelfSession(anchorMs + sessionGapMs(settingsProvider.resolve("")))
        while (currentCoroutineContext().isActive) {
            val now = clock.wallTimeMillis()
            val settings = settingsProvider.resolve("")
            // Any online window — a reply, a proactive message, an earlier session of
            // this kind — re-rolls the next one.
            val offlineSince = session.offlineSince()
            if (offlineSince != anchorMs) {
                anchorMs = offlineSince
                nextAtMs = planSelfSession(anchorMs + sessionGapMs(settings))
            }
            val restMs = nextAtMs - now
            if (restMs > 0L || paused.get() || settings.replyPreset == ReplyPreset.INSTANT) {
                clock.delay(restMs.coerceIn(SELF_SESSION_TICK_MS, SELF_SESSION_TICK_MS * 4))
                continue
            }
            if (timing.isSleeping(now, settings)) {
                // Asleep: not "skip this one" but "it happens after waking up", or the
                // account would come online at 04:00 with nothing to say.
                nextAtMs = planSelfSession(now + SELF_SESSION_TICK_MS)
                clock.delay(SELF_SESSION_TICK_MS)
                continue
            }
            val onlineMs = timing.selfSessionOnlineMs()
            session.noteCameOnline(now)
            proactive?.onOnlineSessionStarted()
            store.recordTurnActivity(
                TurnActivity(
                    chatJid = "",
                    turnId = "self-session:$now",
                    stage = "self_session",
                    summary =
                        "Bot goes online briefly on its own · ${formatWait(onlineMs)} · " +
                            "no message, presence only",
                    level = TurnActivityLevel.INFO,
                    timestampMs = now,
                ),
            )
            runCatching { presence.showOnlineBriefly(onlineMs) }
            // She is online right now anyway, which is the only moment a person
            // ever changes their picture. The rotator decides on its own whether
            // weeks have passed; almost every session it does nothing.
            profilePictures?.let { rotator ->
                runCatching { rotator.rotateIfDue(settings.personality, now) }
                    .onSuccess { outcome ->
                        // Housekeeping, not a decision: a persona without pictures
                        // and a retry that is still waiting would repeat in every
                        // session, so only the change itself is worth a line.
                        recordPictureOutcome(settings.personality, now, outcome, announceSkip = false)
                    }
            }
            // The window that just closed becomes the next anchor on the following
            // pass, so the gap is re-rolled from here.
        }
    }

    /**
     * How long until she picks the phone up on her own again.
     *
     * The only number low power mode changes in the behavioural model, and it changes
     * it in one direction: more often. That is not a cosmetic difference there — in low
     * mode the link is down between sessions, so this gap is also the longest an
     * unanswered message can sit before it is even seen.
     */
    private fun sessionGapMs(settings: EngineSettingsSnapshot): Long =
        when (settings.effectivePowerMode) {
            PowerMode.LOW -> linkPolicy.lowSessionGapMs()
            PowerMode.DEFAULT -> timing.selfSessionGapMs()
        }

    /** Records the planned session so the transport can sleep until it, and returns it unchanged. */
    private fun planSelfSession(atMs: Long): Long {
        linkPower.noteNextSessionAt(atMs)
        return atMs
    }

    /**
     * Writes what the rotator did into the activity log.
     *
     * A picture that does not change is invisible from the outside, so the two
     * ways it can fail to change are logged as well — but only where a person
     * just asked for it ([announceSkip]), never from the background pass that
     * would repeat the same line in every session.
     */
    private suspend fun recordPictureOutcome(
        personaKey: String,
        nowMs: Long,
        outcome: ProfilePictureOutcome,
        announceSkip: Boolean,
    ) {
        val level: TurnActivityLevel
        val summary: String
        when (outcome) {
            ProfilePictureOutcome.CHANGED -> {
                level = TurnActivityLevel.INFO
                summary = "New profile picture set (persona $personaKey)"
            }

            ProfilePictureOutcome.NO_PICTURES -> {
                if (!announceSkip) return
                level = TurnActivityLevel.WARN
                summary =
                    "Persona $personaKey has no profile pictures in the app · " +
                        "the previous picture stays up"
            }

            ProfilePictureOutcome.PENDING -> {
                if (!announceSkip) return
                level = TurnActivityLevel.WARN
                summary =
                    "New profile picture for persona $personaKey could not be set · " +
                        "retried on the next connection"
            }

            ProfilePictureOutcome.UNCHANGED -> return
        }
        store.recordTurnActivity(
            TurnActivity(
                chatJid = "",
                turnId = "profile-picture:$nowMs",
                stage = "profile_picture",
                summary = summary,
                level = level,
                timestampMs = nowMs,
            ),
        )
    }

    /**
     * Reconciles the picture on the account with the persona that is configured.
     *
     * Runs on every recovery, which is the only place that sees both a live
     * connection and a settings state that may have moved while the app was
     * down. It is a no-op whenever the two already agree — which is almost
     * always — and it is what makes the *first* switch after an install work at
     * all: the rotator has to have seen one persona before it can call the next
     * one a switch.
     */
    private suspend fun syncProfilePersona() {
        val rotator = profilePictures ?: return
        val persona = settingsProvider.resolve("").personality
        val now = clock.wallTimeMillis()
        runCatching { rotator.applyPersonaSwitch(persona, now) }
            .onSuccess { outcome -> recordPictureOutcome(persona, now, outcome, announceSkip = false) }
    }

    /**
     * Acts on a settings change that must not wait for the next inbound message.
     *
     * Everything else in the engine pulls its settings at the moment it needs
     * them, which is enough for anything decided per message. Two switches are
     * not: turning on the instant preset while a human-delay window is already
     * armed used to keep the contact waiting out a delay the setting had just
     * abolished — the ceiling that collapses the window is only applied when a
     * message arrives, so the fix was to send someone another message. And a
     * persona switch left the account wearing the previous persona's face until
     * the picture happened to be due, weeks later.
     */
    suspend fun onSettingsChanged() {
        val settings = settingsProvider.resolve("")
        if (settings.replyPreset == ReplyPreset.INSTANT) collapsePickupWindow()
        // A sleeping link cannot apply a profile mutation. Calling the rotator anyway records a
        // transport failure and arms its backoff, which then suppresses the recovery-time retry
        // after the socket actually wakes. The persisted persona is already the durable queue:
        // startRecovery reconciles it on the first carrying connection.
        if (!linkPower.carrying.value) return
        profilePictures?.let { rotator ->
            val now = clock.wallTimeMillis()
            runCatching { rotator.applyPersonaSwitch(settings.personality, now) }
                .onSuccess { outcome ->
                    // Somebody just switched the persona and is looking at the
                    // account, so "nothing happened" needs a reason here.
                    recordPictureOutcome(settings.personality, now, outcome, announceSkip = true)
                }
        }
    }

    /**
     * Applies a chat override to work that was scheduled before the operator changed it.
     *
     * The global settings flow cannot observe [ChatOverrideStore], so a per-chat Human -> Instant
     * switch needs this explicit edge. The existing shared pickup is collapsed deliberately: the
     * bot has already decided to come online for this waiting conversation, and answering the few
     * other batches that share that same online window avoids a second artificial reconnect cycle.
     */
    suspend fun onChatSettingsChanged(chatJid: String) {
        val settings = settingsProvider.resolve(chatJid)
        if (settings.chatPaused) {
            cancelChatWork(chatJid)
        } else {
            val stranded =
                synchronized(batchLock) {
                    outstandingReactiveEvents
                        .filter { (key, events) ->
                            key !in chatGenerations &&
                                events.values.firstOrNull()?.chatJid == chatJid
                        }.values
                        .map { it.values.toList() }
                }
            stranded.forEach { events ->
                scheduleBatch(
                    events = events,
                    immediate = settings.replyPreset == ReplyPreset.INSTANT,
                    resolvedSettings = settings,
                )
            }
            if (settings.replyPreset == ReplyPreset.INSTANT) collapsePickupWindow()
        }
        proactive?.refreshSchedules()
    }

    /** Drops only this chat's queued/active work when its per-chat pause is switched on. */
    private fun cancelChatWork(chatJid: String) {
        synchronized(batchLock) {
            val keys =
                buildSet {
                    pendingPickups.entries
                        .filter { (_, pickup) -> pickup.events.firstOrNull()?.chatJid == chatJid }
                        .mapTo(this, Map.Entry<String, PendingPickup>::key)
                    outstandingReactiveEvents.entries
                        .filter { (_, events) -> events.values.firstOrNull()?.chatJid == chatJid }
                        .mapTo(this, Map.Entry<String, LinkedHashMap<String, IncomingEvent>>::key)
                }
            keys.forEach { key ->
                pendingPickups.remove(key)
                chatGenerations.remove(key)
            }
            if (pendingPickups.isEmpty()) {
                pickupTimer?.cancel()
                pickupTimer = null
                onlinePickupAtMs = null
                onlinePickupFloorAtMs = 0L
                pickupReductions = 0
                pickupWindowGeneration += 1
            }
        }
        synchronized(activeLock) {
            activeTurn
                ?.takeIf { it.chatJid == chatJid }
                ?.job
                ?.cancel(CancellationException("chat paused"))
        }
        cancelPendingTypingStop(chatJid)
        activity.finish(chatJid)
    }

    /**
     * Pulls an armed pickup window forward to right now, keeping the batch.
     *
     * Nothing is dropped: the waiting messages stay in their pending batches and
     * are answered together, exactly as they would have been when the window
     * elapsed on its own.
     */
    private suspend fun collapsePickupWindow() {
        val now = clock.wallTimeMillis()
        val waiting =
            synchronized(batchLock) {
                if (onlinePickupAtMs == null) return
                val count = pendingPickups.values.sumOf { it.events.size }
                if (count == 0) return
                openPickupWindow(now, now)
                count
            }
        store.recordTurnActivity(
            TurnActivity(
                chatJid = "",
                turnId = "instant-switch:$now",
                stage = "pickup_scheduled",
                summary =
                    "Switched to instant · answering the $waiting waiting " +
                        (if (waiting == 1) "message" else "messages") + " now",
                level = TurnActivityLevel.INFO,
                timestampMs = now,
            ),
        )
    }

    suspend fun accept(event: IncomingEvent): AcceptResult {
        if (!store.claimInbound(event)) return AcceptResult.DUPLICATE
        suspend fun finish(
            disposition: String,
            result: AcceptResult,
        ): AcceptResult {
            store.completeInbound(event, disposition)
            return result
        }
        if (event.fromMe) return finish("from_me", AcceptResult.IGNORED)
        val settings = settingsProvider.resolve(event.chatJid)
        val access = store.accessDecision(event, settings.allowAll)
        if (!access.allowed) return finish("access_blocked", AcceptResult.BLOCKED)
        // Someone we do talk to just wrote: hold the link up past the answer, so the
        // rest of the exchange lands in this connection instead of waiting for the
        // next session. Placed after the access gate on purpose — a blocked number
        // must not be able to keep the phone awake by typing at it.
        linkPower.extendListening(
            clock.wallTimeMillis() + linkPolicy.listenWindowMs(settings.lowListenMinutes),
        )

        if (event.kind in setOf(ChatEventKind.EDIT, ChatEventKind.DELETE)) {
            val applied = store.applyInboundMutation(event)
            return finish(
                if (applied) {
                    "${event.kind.name.lowercase()}_applied"
                } else {
                    "${event.kind.name.lowercase()}_target_missing"
                },
                AcceptResult.IGNORED,
            )
        }
        if (event.kind == ChatEventKind.REACTION && event.reactionEmoji.isNullOrBlank()) {
            return finish("reaction_ignored", AcceptResult.IGNORED)
        }

        val processingEvent =
            event.withoutGroupTrigger(settings.groupTrigger).copy(personaKey = settings.personality)

        val rateDecision =
            if (access.isAdmin) {
                InboundRateDecision(
                    limited = false,
                    autoblockThresholdExceeded = false,
                )
            } else {
                store.inboundRateDecision(
                    event,
                    settings.autoblockPerMinute,
                    settings.autoblockPerFiveMinutes,
                    settings.autoblockPerTenMinutes,
                )
            }
        if (rateDecision.limited) {
            var remoteBlockConfirmed = true
            if (settings.autoblockEnabled && rateDecision.autoblockThresholdExceeded) {
                store.blockSender(event, "message flood")
                try {
                    whatsapp.blockContact(
                        jid = event.senderJid,
                        reason = "message flood",
                        idempotencyKey = "flood:${event.eventId}:block",
                        aliases =
                            buildList {
                                addAll(event.senderAliases)
                                if (!event.isGroup) {
                                    add(event.chatJid)
                                    addAll(event.chatAliases)
                                }
                            }.distinct().take(16),
                    )
                } catch (error: Throwable) {
                    remoteBlockConfirmed = false
                    // The local deny entry already protects the engine, but WhatsApp itself did
                    // not confirm the block. Persist that distinction instead of silently claiming
                    // the whole operation succeeded.
                    runCatching {
                        store.recordTurnFailure(
                            chatJid = event.chatJid,
                            turnId = "flood:${event.eventId}:block",
                            proactive = false,
                            error = error,
                            timestampMs = clock.wallTimeMillis(),
                        )
                    }
                }
            }
            return finish(
                if (rateDecision.autoblockThresholdExceeded && !remoteBlockConfirmed) {
                    "flood_limited_remote_block_failed"
                } else if (rateDecision.autoblockThresholdExceeded) {
                    "flood_limited"
                } else {
                    "rate_limited"
                },
                AcceptResult.BLOCKED,
            )
        }

        if (processingEvent.kind == ChatEventKind.MESSAGE) {
            when (val handling = commands.handle(processingEvent, access.isAdmin)) {
                is CommandHandling.Handled -> {
                    sendAdminReplies(processingEvent, handling.replies)
                    return finish("admin_command", AcceptResult.COMMAND)
                }

                CommandHandling.FallThrough,
                CommandHandling.NotACommand,
                -> Unit
            }
        }

        // The conversation write says "already here", so this event is done — and it has to be
        // *recorded* as done. Returning without a disposition left the claim open, and an open
        // claim is exactly what [claimInbound] treats as reclaimable: every journal replay of
        // this event walked the whole accept path again, commands included.
        if (!store.persistConversation(processingEvent)) {
            return finish("conversation_duplicate", AcceptResult.DUPLICATE)
        }
        store.recordTurnActivity(
            TurnActivity(
                chatJid = processingEvent.chatJid,
                turnId = processingEvent.eventId,
                stage = "received",
                summary =
                    when {
                        processingEvent.kind == ChatEventKind.CALL_MISSED ->
                            "Incoming call declined"
                        else ->
                            when (processingEvent.media?.kind) {
                                MediaKind.IMAGE -> "Image message received"
                                MediaKind.AUDIO -> "Voice message received"
                                MediaKind.VIDEO -> "Video message received"
                                null -> "Text message received"
                                else -> "Media message received"
                            }
                    },
                timestampMs = clock.wallTimeMillis(),
            ),
        )
        if (
            processingEvent.kind == ChatEventKind.MESSAGE ||
            processingEvent.kind == ChatEventKind.CALL_MISSED
        ) {
            proactive?.onInbound(processingEvent, settings)
        }
        if (processingEvent.kind == ChatEventKind.REACTION) {
            // A reaction is durable chat context — it was written to the conversation
            // above, as "User reacted with: …", and every later turn reads it there.
            // What it is not is a request for an answer. Nobody expects a message
            // back for tapping an emoji, and answering one turns a throwaway gesture
            // into a model call plus an unprompted message: the reply-ratio signal
            // this account can least afford. This used to apply to groups only, so a
            // one-to-one reaction pulled her online for a reply nobody asked for.
            return finish("reaction_logged", AcceptResult.IGNORED)
        }
        // She is still sitting in this chat, so the message arrives in front of her
        // eyes: it is read now, not after a pickup delay that models walking back to
        // the phone she never put down. Everything else about the turn is unchanged —
        // only the receipt moves forward, and only for a chat that is demonstrably open.
        val chatOpen =
            settings.markRead &&
                processingEvent.kind == ChatEventKind.MESSAGE &&
                session.isChatOpen(processingEvent.chatJid, clock.wallTimeMillis())
        if (chatOpen) {
            val acknowledged =
                runCatching {
                    whatsapp.markRead(processingEvent.chatJid, listOf(processingEvent.messageId))
                }.isSuccess
            if (acknowledged) rememberEarlyRead(processingEvent.messageId)
        }
        // A message for the chat we are mid-reply to cancels that turn, so the
        // contact gets one combined answer instead of a stale reply followed by
        // a late, out-of-context second one. The cancelled turn's inputs are
        // carried into the replacement batch: they were never answered, and the
        // prompt is built from the batch, so dropping them would silently lose
        // the request (an interrupted voice note or image would vanish).
        var answeredBeforeInterrupt = emptyList<IncomingEvent>()
        val (interrupted, carried) =
            synchronized(activeLock) {
                val active = activeTurn
                if (
                    active == null ||
                    active.chatJid != event.chatJid ||
                    active.events.first().queueKey() != processingEvent.queueKey() ||
                    !active.job.isActive
                ) {
                    false to emptyList<IncomingEvent>()
                } else {
                    // A proactive turn carries a synthetic seed event, not a real
                    // inbound one — replaying it would inject a fabricated user
                    // message into the batch.
                    val pending =
                        if (active.answered || active.proactive) {
                            if (active.answered && !active.proactive) {
                                answeredBeforeInterrupt = active.events
                            }
                            emptyList()
                        } else {
                            active.events
                        }
                    activeTurn = null
                    active.job.cancel(CancellationException("new message in active chat"))
                    true to pending
                }
            }
        // A visible action already reached WhatsApp. Cancelling the rest of that turn must not
        // leave its inbound claims open or let the replacement generation answer them again.
        answeredBeforeInterrupt.forEach { store.completeInbound(it, "answered") }
        if (answeredBeforeInterrupt.isNotEmpty()) markReactiveEventsCompleted(answeredBeforeInterrupt)
        if (interrupted) {
            // Stop typing. She was mid-answer, the new message pulled her attention
            // back, and a human does not keep the indicator running while re-reading.
            // It comes back on in [holdComposingPresence] once the replacement answer
            // is actually being typed — but not in the same millisecond the message
            // landed, see [pauseComposingSoon].
            pauseComposingSoon(processingEvent.chatJid, settings)
            store.recordTurnActivity(
                TurnActivity(
                    chatJid = processingEvent.chatJid,
                    turnId = processingEvent.eventId,
                    stage = "interrupted",
                    summary =
                        if (carried.isEmpty()) {
                            "New message while typing · the old reply was already out, " +
                                "carrying straight on"
                        } else {
                            "New message while typing · discarded the half-written reply, " +
                                "rebuilding it from ${carried.size + 1} inbound messages"
                        },
                    // Normal behaviour, not a fault: this is what makes the bot answer
                    // the whole conversation instead of a stale half of it.
                    level = TurnActivityLevel.INFO,
                    timestampMs = clock.wallTimeMillis(),
                ),
            )
        }
        if (!settings.enabled || settings.chatPaused || paused.get()) {
            return AcceptResult.PERSISTED_WHILE_PAUSED
        }

        // Only messages and missed calls reach this point; reactions were logged
        // and dropped above. Nothing bypasses the pickup window, because an event
        // that pulled the bot online within seconds while the away model claimed
        // she had been gone for hours was a deterministic tell.
        scheduleBatch(
            carried + processingEvent,
            immediate = interrupted,
            chatOpen = chatOpen,
            resolvedSettings = settings,
        )
        return AcceptResult.QUEUED
    }

    private fun rememberEarlyRead(messageId: String) {
        synchronized(batchLock) {
            earlyReadMessageIds += messageId
            while (earlyReadMessageIds.size > MAX_EARLY_READ_MESSAGE_IDS) {
                val oldest = earlyReadMessageIds.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
    }

    fun pause() {
        if (!paused.compareAndSet(false, true)) return
        val timer =
            synchronized(batchLock) {
                pendingPickups.clear()
                chatGenerations.clear()
                onlinePickupAtMs = null
                onlinePickupFloorAtMs = 0L
                pickupReductions = 0
                pickupWindowGeneration += 1
                pickupTimer.also { pickupTimer = null }
            }
        timer?.cancel()
        selfSessionJob?.cancel()
        selfSessionJob = null
        // Every armed window was just dropped, so every row promising a time is now lying.
        activity.clearAll()
        // She put the phone down: the dot goes out and the away clock starts, so the
        // next message gets a real pickup delay instead of the open-chat fast path.
        session.closeChat(clock.wallTimeMillis())
        scope.launch(CoroutineName("pause-offline")) { presence.forceOffline() }
        synchronized(activeLock) {
            activeTurn?.job?.cancel(CancellationException("bot paused"))
        }
    }

    suspend fun resume() {
        if (!paused.compareAndSet(true, false)) return
        startRecovery()
    }

    suspend fun applyEnabledSetting(enabled: Boolean) {
        if (enabled) {
            resume()
        } else {
            pause()
        }
        proactive?.refreshSchedules()
    }

    /**
     * Runs one proactive turn for [chatJid] immediately, on the user's explicit instruction.
     *
     * This skips only the schedule. Access lists, the block list, the outbound safety layer and
     * the sleep window all still apply, and the model may still decide to stay quiet — the
     * outcome says which of those happened.
     *
     * It does take the link off its schedule, though. Every turn parks on [LinkPowerFeed.carrying]
     * before it runs, so asking for a message while the link is dozing or inside the quiet hours
     * used to hand back nothing at all: the request sat in the queue for the hours until the next
     * session, with a spinner on screen the whole time. Somebody who confirmed a send has said
     * they want it now, and that is the same thing tapping the status line says — so this raises
     * the link exactly the way a tap does, and it lapses on its own the same way.
     */
    suspend fun writeContactNow(
        chatJid: String,
        note: String? = null,
    ): ProactiveTurnOutcome {
        val persistence = proactivePersistence ?: return ProactiveTurnOutcome.Cancelled
        val settings = settingsProvider.resolve(chatJid)
        if (!settings.enabled || settings.chatPaused) return ProactiveTurnOutcome.Cancelled
        val seed = persistence.loadSeed(chatJid) ?: return ProactiveTurnOutcome.Cancelled
        if (seed.lastInbound.isGroup) return ProactiveTurnOutcome.Blocked(hard = true)
        if (!store.accessDecision(seed.lastInbound, settings.allowAll).allowed) {
            return ProactiveTurnOutcome.Blocked(hard = true)
        }
        // After the gates, never before: a refusal must not be able to wake the phone up.
        linkPower.requestWake()
        return enqueueProactive(
            seed,
            settings.proactiveLevel,
            ProactiveTurnRequest(
                reason = manualOutreachReason(note),
                trailingDirective = true,
                personaKey = settings.personality,
            ),
        )
    }

    override fun close() {
        pause()
        proactive?.close()
        readyTurns.close()
        scope.cancel()
    }

    private suspend fun sendAdminReplies(event: IncomingEvent, replies: List<String>) {
        replies.filter(String::isNotBlank).forEachIndexed { index, reply ->
            val id = "admin:${event.eventId}:$index"
            when (
                val decision =
                    store.reserveOutbound(
                        OutboundIntent(
                            reservationId = id,
                            chatJid = event.chatJid,
                            proactive = false,
                            admin = true,
                            textHash = reply.sha256(),
                            payloadChars = reply.length,
                            timestampMs = clock.wallTimeMillis(),
                        ),
                    )
            ) {
                is OutboundDecision.Allowed -> {
                    try {
                        val messageId =
                            whatsapp.sendText(
                                chatJid = event.chatJid,
                                text = reply,
                                quoteMessageId = event.messageId,
                                idempotencyKey = id,
                            )
                        store.completeOutbound(id, messageId, true, clock.wallTimeMillis())
                    } catch (error: Throwable) {
                        store.completeOutbound(id, null, false, clock.wallTimeMillis())
                        throw error
                    }
                }

                is OutboundDecision.Deferred,
                is OutboundDecision.Blocked,
                -> Unit
            }
        }
    }

    /**
     * Fold a chat's new input into the ONE global online window.
     *
     * Ported from the reference build's `runtime/onlineScheduler.ts`: there is a
     * single pickup window for the whole bot, not a timer per chat. The first
     * waiting chat decides when she next comes online; everything that arrives
     * before that may only *shorten* the window (bounded by the floor the plan
     * chose and by [MAX_PICKUP_REDUCTIONS]), never push it out. That is what
     * keeps a running conversation from sliding back into a long human-delay
     * phase every time the contact types again — the old per-chat timer rolled a
     * brand-new random delay on every message.
     *
     * Because the events stay in [pendingPickups] until the window actually
     * fires, anything arriving in between — a photo and a video in two separate
     * messages, say — is answered in one combined turn instead of racing a
     * half-drained batch.
     */
    private suspend fun scheduleBatch(
        events: List<IncomingEvent>,
        immediate: Boolean,
        chatOpen: Boolean = false,
        waitedMs: Long = 0L,
        resolvedSettings: EngineSettingsSnapshot? = null,
    ) {
        val chatJid = events.first().chatJid
        val now = clock.wallTimeMillis()
        val plan =
            if (immediate) {
                OnlineDelayPlan(0L, 0L)
            } else {
                // [accept] resolved the same view microseconds ago; re-resolving it here
                // was a second persona-assignment lookup for a value that cannot have
                // changed in between. Recovery has no such view and still resolves.
                val settings = resolvedSettings ?: settingsProvider.resolve(chatJid)
                val pickup =
                    timing.pickupPlan(
                        nowMs = now,
                        offlineForMs = session.offlineForMs(now),
                        inSession = session.inSession(now),
                        settings = settings,
                        chatOpen = chatOpen,
                        tempo = session.exchangeTempo(now),
                    )
                // The collect window groups a burst that is still being typed. It
                // is a floor on the wait, never something added on top of it.
                //
                // For an open chat it is the *only* wait left, and that is on
                // purpose: it is the waiting floor that keeps an instant answer
                // from looking like an auto-responder, and it still lets three
                // messages typed in one breath become one answer instead of three
                // paid model calls.
                val grouping =
                    if (settings.replyPreset == ReplyPreset.INSTANT) {
                        0L
                    } else {
                        settings.batchWindowMs.coerceIn(0, 60_000)
                    }
                // Time the message already spent waiting counts towards the delay
                // instead of being thrown away. A reconnect therefore neither
                // restarts the wait nor collapses it: a delay that ran out while
                // the link was down is answered at once, and one that is still
                // running keeps only its remainder.
                OnlineDelayPlan(
                    delayMs = (maxOf(pickup.delayMs, grouping) - waitedMs).coerceAtLeast(0L),
                    minDelayMs = (maxOf(pickup.minDelayMs, grouping) - waitedMs).coerceAtLeast(0L),
                )
            }
        val waitMs =
            synchronized(batchLock) {
                scheduleBatchLocked(chatJid, events, now, plan, immediate, chatOpen)
            }
        // The link has to outlive the delay that was just drawn. [accept] already opened a
        // listening window when the message landed, but the pickup ladder can draw a longer
        // wait than that window is wide — 10-30 minutes in the away band against a 10 minute
        // default — and nothing marks the engine busy until the turn actually starts. So in
        // low mode the socket would have dropped with the answer still pending, and the timer
        // waiting it out is an ordinary coroutine delay that does not advance through a
        // suspended CPU. Cover the wait, plus a full window on the far side of it for the
        // reply itself and whatever the contact says back.
        if (waitMs > 0L) extendLinkListening(afterMs = waitMs)
        // The activity feed has to answer "why is nothing happening?" — so the
        // armed human-delay timer is written out the moment it is set, together
        // with how many inputs the window now holds.
        val pendingCount =
            synchronized(batchLock) {
                pendingPickups[events.first().queueKey()]?.events?.size ?: 0
            }
        store.recordTurnActivity(
            TurnActivity(
                chatJid = chatJid,
                turnId = events.last().eventId,
                stage = "pickup_scheduled",
                // Two different things used to be called "wait" here, which is why
                // it was never clear which one the row meant. This one is the wait
                // until the bot is online — nothing is asked of the model before it
                // elapses, and that is exactly what lets a burst of messages become
                // one call. The minimum before typing is the reading floor, and it
                // has its own row at the other end of the turn.
                summary =
                    if (waitMs <= 0L) {
                        "Message received · bot is online · request goes out immediately" +
                            if (pendingCount > 1) " · $pendingCount messages bundled" else ""
                    } else {
                        "Message received · going online in ${formatWait(waitMs)}" +
                            if (pendingCount > 1) " · $pendingCount messages bundled" else ""
                    },
                timestampMs = now,
            ),
        )
        // The same fact, live: the list row can now say when she answers this chat instead of
        // going quiet for the length of the delay. A window that is already up counts as queued —
        // the turn is real and merely waiting for whichever chat currently holds the engine.
        if (waitMs > 0L) {
            activity.armed(chatJid, dueAtMs = safeAdd(now, waitMs), nowMs = now)
        } else {
            activity.queued(chatJid, nowMs = now)
        }
    }

    /** Must be called while holding [batchLock]; returns the resulting wait. */
    private fun scheduleBatchLocked(
        chatJid: String,
        events: List<IncomingEvent>,
        now: Long,
        plan: OnlineDelayPlan,
        immediate: Boolean,
        chatOpen: Boolean = false,
    ): Long {
        run {
            val queueKey = events.first().queueKey()
            val outstanding = outstandingReactiveEvents.getOrPut(queueKey, ::LinkedHashMap)
            events.forEach { outstanding[it.eventId] = it }
            val completeBatch = outstanding.values.sortedBy(IncomingEvent::timestampMs)
            val pending = pendingPickups.getOrPut(queueKey) { PendingPickup(enqueuedAtMs = now) }
            // Carried-over events from an interrupted turn can overlap with what
            // is already buffered, so keep the batch keyed by event id.
            val known = pending.events.mapTo(HashSet(), IncomingEvent::eventId)
            pending.events += completeBatch.filter { known.add(it.eventId) }
            pending.events.sortBy(IncomingEvent::timestampMs)
            val generation = (chatGenerations[queueKey] ?: 0L) + 1L
            chatGenerations[queueKey] = generation
            pending.generation = generation
            // The chat she is already in goes before chats that merely happened to
            // share this window — she does not leave an open conversation to work
            // through a backlog first. An interrupt outranks it, and once raised the
            // priority stays: the chat does not become less current.
            if (chatOpen) {
                pending.priority = maxOf(pending.priority, PRIORITY_OPEN_CHAT)
            }
            when {
                immediate -> {
                    // She is already looking at this chat, so it goes first and
                    // the window is now — which also pulls forward whatever else
                    // she owes, exactly like the reference build does.
                    pending.immediate = true
                    pending.priority = PRIORITY_INTERRUPT
                    pending.enqueuedAtMs = 0L
                    openPickupWindow(now, now)
                }

                onlinePickupAtMs == null ->
                    openPickupWindow(safeAdd(now, plan.delayMs), safeAdd(now, plan.minDelayMs))

                // Every new message re-states the longest wait the CURRENT settings
                // still allow, and the armed window is clamped to it. A window that
                // was rolled under the human preset therefore cannot outlive a
                // switch to instant (ceiling 0 → she picks up now and answers the
                // whole batch at once) — no special case, just the ceiling applying
                // to a window that is already running.
                safeAdd(now, plan.maxDelayMs) < (onlinePickupAtMs ?: Long.MAX_VALUE) -> {
                    val ceiling = safeAdd(now, plan.maxDelayMs)
                    openPickupWindow(ceiling, minOf(safeAdd(now, plan.minDelayMs), ceiling))
                }

                pickupReductions < MAX_PICKUP_REDUCTIONS -> {
                    val rest =
                        timing.reduceOnlineDelay(
                            restMs = ((onlinePickupAtMs ?: now) - now).coerceAtLeast(0L),
                            floorMs = (onlinePickupFloorAtMs - now).coerceAtLeast(0L),
                        )
                    onlinePickupAtMs = safeAdd(now, rest)
                    pickupReductions += 1
                    armPickupTimer()
                }
            }
        }
        return ((onlinePickupAtMs ?: now) - now).coerceAtLeast(0L)
    }

    /** Must be called while holding [batchLock]. */
    private fun openPickupWindow(
        atMs: Long,
        floorAtMs: Long,
    ) {
        onlinePickupAtMs = atMs
        onlinePickupFloorAtMs = floorAtMs
        pickupReductions = 0
        armPickupTimer()
    }

    /** Must be called while holding [batchLock]. */
    private fun armPickupTimer() {
        val target = onlinePickupAtMs ?: return
        val generation = ++pickupWindowGeneration
        pickupTimer?.cancel()
        pickupTimer =
            scope.launch(CoroutineName("online-pickup")) {
                try {
                    clock.delay((target - clock.wallTimeMillis()).coerceAtLeast(0L))
                    firePickupWindow(generation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    runCatching {
                        store.recordTurnFailure(
                            chatJid = "",
                            turnId = "pickup:$generation",
                            proactive = false,
                            error = error,
                            timestampMs = clock.wallTimeMillis(),
                        )
                    }
                }
            }
    }

    /**
     * The window elapsed: she comes online and works through every chat that was
     * waiting, oldest first, one at a time behind the single visible-turn worker.
     */
    private suspend fun firePickupWindow(generation: Long) {
        val due =
            synchronized(batchLock) {
                // A window armed after this timer had already elapsed wins; this
                // run is stale and its events belong to the newer window.
                if (generation != pickupWindowGeneration) return
                onlinePickupAtMs = null
                onlinePickupFloorAtMs = 0L
                pickupReductions = 0
                pickupTimer = null
                pendingPickups.values.toList().also { pendingPickups.clear() }
            }
        if (due.isEmpty() || paused.get()) return
        // Opening WhatsApp starts (or extends) the online session for everything
        // that follows, the reply timing and the proactive layer alike.
        val now = clock.wallTimeMillis()
        val wasInSession = session.inSession(now)
        session.noteCameOnline(now)
        // "Bot came online" is the one line that explains the gap between the
        // message and the answer, so it is written when the bot actually goes
        // online — not on every window, because a window that fires inside a
        // running session is her still being online, not coming online again.
        if (!wasInSession) {
            store.recordTurnActivity(
                TurnActivity(
                    chatJid = due.first().events.first().chatJid,
                    turnId = due.first().events.last().eventId,
                    stage = "went_online",
                    summary =
                        "Bot came online" +
                            if (due.size > 1) " · ${due.size} chats waiting" else "",
                    timestampMs = now,
                ),
            )
        }
        due
            .sortedWith(
                compareByDescending<PendingPickup> { it.priority }
                    .thenBy { it.enqueuedAtMs }
                    .thenBy { it.events.first().sequence },
            ).forEach { entry ->
                readyTurns.send(
                    QueuedTurn(
                        events = entry.events.toList(),
                        priority = entry.priority,
                        enqueuedAtMs = entry.enqueuedAtMs,
                        generation = entry.generation,
                    ),
                )
            }
    }

    private suspend fun runWorker() {
        val priorityQueue =
            PriorityQueue<QueuedTurn>(
                compareByDescending<QueuedTurn> { it.priority }
                    .thenBy { it.enqueuedAtMs }
                    .thenBy { it.events.first().sequence },
            )
        while (scope.isActive) {
            if (priorityQueue.isEmpty()) {
                val first = readyTurns.receiveCatching().getOrNull() ?: break
                priorityQueue += first
            }
            while (true) {
                val next = readyTurns.tryReceive().getOrNull() ?: break
                priorityQueue += next
            }
            val queuedTurn = priorityQueue.remove()
            // Nothing starts while the link is deliberately down. The drawn human delay
            // decides *when within a session* she answers; it has never had the authority
            // to decide that the phone is switched on. Parking here rather than in the
            // scheduler is what keeps that true for every kind of turn at once — reactive,
            // proactive, retried — and it is checked after the wait as well, because a turn
            // can be cancelled or superseded during a doze that lasts hours.
            linkPower.awaitCarrying()
            if (
                paused.get() ||
                queuedTurn.completion?.isCancelled == true ||
                !isCurrentGeneration(queuedTurn)
            ) {
                queuedTurn.completion?.complete(ProactiveTurnOutcome.Cancelled)
                continue
            }
            val turn = withoutCompletedReactiveEvents(queuedTurn)
            if (turn == null) {
                queuedTurn.completion?.complete(ProactiveTurnOutcome.Cancelled)
                continue
            }

            val job =
                scope.launch(
                    context = CoroutineName("turn-${turn.events.first().eventId}"),
                    start = CoroutineStart.LAZY,
                ) {
                    try {
                        val result = processTurn(turn)
                        val completedInbound =
                            if (turn.reactiveEventsToComplete.isNotEmpty()) {
                                turn.reactiveEventsToComplete
                            } else if (!turn.proactive) {
                                turn.events
                            } else {
                                emptyList()
                            }
                        if (completedInbound.isNotEmpty() && result.isCompletedReactiveOutcome()) {
                            val disposition =
                                if (result == ProactiveTurnOutcome.Sent) "answered" else "handled_silently"
                            completedInbound.forEach { event ->
                                store.completeInbound(event, disposition)
                            }
                            // Memory may only claim completion after the durable disposition. If
                            // persistence fails, recovery must still see unfinished work instead
                            // of silently suppressing it for the rest of this process.
                            markReactiveEventsCompleted(completedInbound)
                        }
                        turn.completion?.complete(result)
                    } catch (cancelled: CancellationException) {
                        turn.completion?.complete(ProactiveTurnOutcome.Cancelled)
                        throw cancelled
                    } catch (error: Throwable) {
                        runCatching {
                            store.recordTurnFailure(
                                chatJid = turn.events.first().chatJid,
                                turnId = turn.turnId,
                                proactive = turn.proactive,
                                error = error,
                                timestampMs = clock.wallTimeMillis(),
                            )
                        }
                        // Marked failed rather than finished: the field keeps the half-written
                        // draft and states the reason, instead of snapping back to idle as if
                        // the turn had never run. The next turn's begin() clears it.
                        activity.failed(
                            chatJid = turn.events.first().chatJid,
                            reason = error.message?.take(160) ?: (error::class.simpleName ?: "unknown error"),
                            nowMs = clock.wallTimeMillis(),
                        )
                        turn.completion?.completeExceptionally(error)
                    }
                }
            synchronized(activeLock) {
                activeTurn =
                    ActiveTurn(
                        chatJid = turn.events.first().chatJid,
                        turnId = turn.turnId,
                        events = turn.events,
                        proactive = turn.proactive,
                        job = job,
                    )
            }
            job.start()
            // The engine runs at most one visible turn globally, so this single pair is
            // the whole "the bot is mid-sentence" signal the link power model needs — no
            // counter, and nothing to leak if the turn is cancelled.
            linkPower.noteBusy(true)
            try {
                job.join()
            } finally {
                synchronized(activeLock) {
                    if (activeTurn?.job === job) activeTurn = null
                }
                synchronized(batchLock) {
                    val queueKey = turn.events.first().queueKey()
                    if (chatGenerations[queueKey] == turn.generation) {
                        chatGenerations.remove(queueKey)
                    }
                }
                // Keeps a failure standing; clears anything else back to idle.
                activity.finish(turn.events.first().chatJid)
                linkPower.noteBusy(false)
                // The listening window starts when she stops talking, not when the message
                // arrived: a turn that spent four minutes reading, generating and typing
                // would otherwise have eaten most of the window before the contact could
                // even see the answer.
                extendLinkListening()
            }
        }
    }

    /**
     * Hold the link up a while longer because something just happened.
     *
     * Only meaningful in [PowerMode.LOW]; in default mode the link never drops and
     * the deadline is simply ignored. Kept unconditional anyway so the feed always
     * describes reality — switching the mode at runtime then needs no catch-up.
     *
     * [afterMs] shifts the whole window later, for a caller that knows the interesting
     * part has not happened yet: an armed human delay wants the link up for the wait
     * *and* the window after it, not for a window that starts counting down now.
     */
    private suspend fun extendLinkListening(afterMs: Long = 0L) {
        val minutes = runCatching { settingsProvider.resolve("").lowListenMinutes }
            .getOrDefault(LinkPowerPolicy.DEFAULT_LISTEN_MINUTES)
        // A wait longer than the ladder's own widest band is not a conversation tail —
        // it is a message that landed at the edge of the quiet hours and is being held
        // for the morning. Pinning the link awake for those hours is the opposite of
        // what the window is for, and it also put an "offline" time three hours out on
        // the status line. The sleep schedule owns that stretch; the alarm brings the
        // link back, and the waiting turn goes out then.
        val covered = afterMs.coerceIn(0L, MAX_COVERED_PICKUP_WAIT_MS)
        linkPower.extendListening(
            safeAdd(clock.wallTimeMillis() + covered, linkPolicy.listenWindowMs(minutes)),
        )
    }

    /**
     * One self-initiated online window, right now, on the operator's request.
     *
     * Shares [runSelfSessions]' body deliberately: tapping the status line should
     * produce the same thing the bot does on its own every few hours, not a second
     * kind of online window with its own rules. The scheduled loop re-anchors itself
     * off [OnlineSessionClock.offlineSince] on its next pass, so this also postpones
     * the automatic one exactly as a real session would.
     */
    suspend fun showOnlineNow() {
        if (paused.get()) return
        session.noteCameOnline(clock.wallTimeMillis())
        proactive?.onOnlineSessionStarted()
        runCatching { presence.showOnlineBriefly(timing.selfSessionOnlineMs()) }
        extendLinkListening()
    }

    private fun isCurrentGeneration(turn: QueuedTurn): Boolean =
        synchronized(batchLock) {
            chatGenerations[turn.events.first().queueKey()] == turn.generation
        }

    private fun withoutCompletedReactiveEvents(turn: QueuedTurn): QueuedTurn? {
        if (turn.proactive) return turn
        val remaining =
            synchronized(batchLock) {
                turn.events.filterNot { it.eventId in completedReactiveEventIds }
            }
        return remaining.takeIf(List<IncomingEvent>::isNotEmpty)?.let { turn.copy(events = it) }
    }

    private fun markReactiveEventsCompleted(events: List<IncomingEvent>) {
        synchronized(batchLock) {
            events.forEach { event ->
                completedReactiveEventIds += event.eventId
                outstandingReactiveEvents[event.queueKey()]?.let { outstanding ->
                    outstanding.remove(event.eventId)
                    if (outstanding.isEmpty()) outstandingReactiveEvents.remove(event.queueKey())
                }
            }
            while (completedReactiveEventIds.size > MAX_COMPLETED_REACTIVE_EVENT_IDS) {
                val oldest = completedReactiveEventIds.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
    }

    private fun ProactiveTurnOutcome.isCompletedReactiveOutcome(): Boolean =
        this == ProactiveTurnOutcome.Sent || this == ProactiveTurnOutcome.Silent

    internal suspend fun enqueueProactive(
        seed: ProactiveSeed,
        @Suppress("UNUSED_PARAMETER") level: Int,
        request: ProactiveTurnRequest,
    ): ProactiveTurnOutcome {
        if (paused.get()) return ProactiveTurnOutcome.Cancelled
        val chatJid = seed.lastInbound.chatJid
        val synthetic =
            seed.lastInbound.copy(
                eventId = "proactive:${seed.lastInbound.eventId}:${UUID.randomUUID()}",
                kind = ChatEventKind.MESSAGE,
                timestampMs = clock.wallTimeMillis(),
                text = request.reason.takeUnless { request.trailingDirective }.orEmpty(),
                quoted = null,
                reactionEmoji = null,
                targetMessageId = null,
                media = null,
                // Scheduled work belongs to the persona that made the promise. Ordinary proactive
                // and manual outreach freeze the currently resolved persona for the same reason.
                personaKey = request.personaKey ?: seed.lastInbound.personaKey,
            )
        val queueKey = synthetic.queueKey()
        var mergedReactiveEvents: List<IncomingEvent> = emptyList()
        var waitingBehind: List<PendingPickup> = emptyList()
        val generation =
            synchronized(batchLock) {
                val matching =
                    if (request.scheduledFollowUp) pendingPickups.remove(queueKey) else null
                if (matching != null) {
                    // The scheduled promise owns the already-buffered messages for the same
                    // persona. They become one reconstructed turn with the timer directive at
                    // the volatile end instead of one reactive call plus a second proactive call.
                    mergedReactiveEvents = matching.events.toList()
                    matching.generation
                } else {
                    // A queued or active turn cannot safely be rewritten after it left the pickup
                    // buffer. Keep the durable promise and retry it rather than racing two calls.
                    if (queueKey in chatGenerations) {
                        return ProactiveTurnOutcome.Deferred(
                            safeAdd(clock.wallTimeMillis(), PROACTIVE_CONTENTION_RETRY_MS),
                        )
                    }
                    val next = (chatGenerations[queueKey] ?: 0L) + 1L
                    chatGenerations[queueKey] = next
                    next
                }.also {
                    if (request.scheduledFollowUp) {
                        // A due exact promise is the wake-up event for the whole phone. Drain the
                        // shared pickup window now, then put every other persona/chat immediately
                        // behind the promise in the same global worker.
                        waitingBehind = pendingPickups.values.toList()
                        pendingPickups.clear()
                        pickupTimer?.cancel()
                        pickupTimer = null
                        onlinePickupAtMs = null
                        onlinePickupFloorAtMs = 0L
                        pickupReductions = 0
                        pickupWindowGeneration += 1L
                    }
                }
            }
        val completion = CompletableDeferred<ProactiveTurnOutcome>()
        val queued =
            QueuedTurn(
                events = mergedReactiveEvents.ifEmpty { listOf(synthetic) },
                priority = if (request.scheduledFollowUp) PRIORITY_SCHEDULED else PRIORITY_PROACTIVE,
                enqueuedAtMs = clock.wallTimeMillis(),
                generation = generation,
                proactive = true,
                proactiveTrailingDirective = request.reason.takeIf { request.trailingDirective },
                reactiveEventsToComplete = mergedReactiveEvents,
                completion = completion,
            )
        try {
            readyTurns.send(queued)
            waitingBehind
                .sortedWith(
                    compareByDescending<PendingPickup> { it.priority }
                        .thenBy { it.enqueuedAtMs }
                        .thenBy { it.events.first().sequence },
                ).forEach { entry ->
                    readyTurns.send(
                        QueuedTurn(
                            events = entry.events.toList(),
                            priority = entry.priority,
                            enqueuedAtMs = entry.enqueuedAtMs,
                            generation = entry.generation,
                        ),
                    )
                }
            return completion.await()
        } catch (cancelled: CancellationException) {
            completion.cancel(cancelled)
            synchronized(activeLock) {
                activeTurn
                    ?.takeIf { it.turnId == queued.turnId }
                    ?.job
                    ?.cancel(cancelled)
            }
            synchronized(batchLock) {
                if (chatGenerations[queueKey] == generation) {
                    chatGenerations.remove(queueKey)
                }
            }
            throw cancelled
        }
    }

    /**
     * Holds the turn while this conversation's memory is being written.
     *
     * The consolidation replaces the very memory this turn is about to read, so answering through
     * it means answering from the version that is one refresh out of date — exactly the facts the
     * refresh was triggered to capture. It is bounded: a consolidation that hangs must not take the
     * bot down with it, so past the timeout the turn continues on the previous memory.
     */
    private suspend fun awaitMemoryRefresh(
        conversationKey: String,
        trace: TurnTrace,
    ) {
        val running = memoryRefreshJobs[conversationKey] ?: return
        if (running.job.isCompleted) {
            memoryRefreshJobs.remove(conversationKey, running)
            return
        }
        val waitStartedAtMs = clock.wallTimeMillis()
        trace.record(
            stage = "memory_wait",
            summary =
                "Writing memory · reply held until it is through · " +
                    "the model call follows, with fresh memory and a fresh history cursor",
            // Not background work: this is the reason the chat is quiet right now,
            // so it belongs in the log at the level the user actually reads.
            level = TurnActivityLevel.INFO,
            timestampMs = waitStartedAtMs,
        )
        val completed =
            if (running.mustComplete) {
                running.job.join()
                true
            } else {
                // Only the join is cancelled on timeout; the consolidation itself keeps running and
                // its result is simply picked up by a later turn.
                kotlinx.coroutines.withTimeoutOrNull(MEMORY_REFRESH_WAIT_MS) {
                    running.job.join()
                } != null
            }
        if (completed) memoryRefreshJobs.remove(conversationKey, running)
        val waitedMs = (clock.wallTimeMillis() - waitStartedAtMs).coerceAtLeast(0L)
        trace.record(
            stage = if (completed) "memory_wait_finished" else "memory_wait_timeout",
            summary =
                if (completed) {
                    "Memory done · reply continues with fresh memory " +
                        "(waited ${formatDuration(waitedMs)})"
                } else {
                    "Memory is taking too long · reply continues with the old memory"
                },
            level = if (completed) TurnActivityLevel.INFO else TurnActivityLevel.WARN,
            elapsedMs = waitedMs,
        )
    }

    /** Global/persona memory has no safe stale fallback: all prompts depend on it. */
    private suspend fun awaitGlobalMemoryRefresh(trace: TurnTrace) {
        while (true) {
            val running = globalMemoryRefresh.get() ?: return
            if (running.job.isCompleted) {
                globalMemoryRefresh.compareAndSet(running, null)
                continue
            }
            val startedAt = clock.wallTimeMillis()
            trace.record(
                stage = "global_memory_wait",
                summary = "Global memory is being rebuilt · all chats are paused",
                level = TurnActivityLevel.INFO,
                timestampMs = startedAt,
            )
            running.job.join()
            globalMemoryRefresh.compareAndSet(running, null)
            trace.record(
                stage = "global_memory_wait_finished",
                summary =
                    "Global memory finished · reply continues with the rebuilt prompt " +
                        "(waited ${formatDuration(clock.wallTimeMillis() - startedAt)})",
                level = TurnActivityLevel.INFO,
            )
        }
    }

    /** Starts at most one memory refresh per conversation, even after a waiter times out. */
    private fun launchMemoryRefresh(
        conversationKey: String,
        newest: IncomingEvent,
        turnInput: TurnInput,
        committedActions: List<PlannedSideEffect>,
    ) {
        var created: MemoryRefreshJob? = null
        memoryRefreshJobs.compute(conversationKey) { _, existing ->
            if (existing != null && !existing.job.isCompleted) {
                existing
            } else {
                scope.launch(
                    context = CoroutineName("memory-cadence-${newest.chatJid.hashCode()}"),
                    start = kotlinx.coroutines.CoroutineStart.LAZY,
                ) {
                    runCatching {
                        ai.afterSuccessfulSend(
                            input = turnInput,
                            actions = committedActions,
                        )
                    }
                }.let { MemoryRefreshJob(job = it, mustComplete = false) }
                    .also { created = it }
            }
        }
        created?.let { refresh ->
            refresh.job.invokeOnCompletion { memoryRefreshJobs.remove(conversationKey, refresh) }
            refresh.job.start()
        }
    }

    private suspend fun processTurn(queued: QueuedTurn): ProactiveTurnOutcome {
        val turnStartedAtMs = clock.wallTimeMillis()
        val events = queued.events
        val newest = events.last()
        val newestMessageIds = events.mapTo(HashSet(events.size), IncomingEvent::messageId)
        val currentSettings = settingsProvider.resolve(newest.chatJid)
        // Persona is message ownership, not a presentation preference. A switch while this turn
        // waited may change every later event, but can never move this event into another prompt.
        val settings =
            newest.personaKey?.let { frozen -> currentSettings.copy(personality = frozen) }
                ?: currentSettings
        if (!settings.enabled || settings.chatPaused || paused.get()) {
            return ProactiveTurnOutcome.Cancelled
        }
        val currentAccess = store.accessDecision(newest, settings.allowAll)
        if (!currentAccess.allowed) return ProactiveTurnOutcome.Blocked(hard = true)
        val conversationKey = newest.queueKey()
        val trace = TurnTrace(newest.chatJid, queued.turnId, turnStartedAtMs)
        // Claims the live feed for this chat. Only one turn runs at a time, so this also ends
        // whatever the previous turn left showing.
        activity.begin(newest.chatJid, queued.turnId, turnStartedAtMs)
        trace.record(
            stage = "started",
            summary = "Reply processing started · ${events.size} inbound message${if (events.size == 1) "" else "s"}",
            timestampMs = turnStartedAtMs,
        )

        // Memory is part of opening the conversation, not merely loading the model prompt. Hold
        // before safety reservation, online presence and read receipts so a long import cannot make
        // the chat look read/active while its canonical context is still being replaced.
        trace.record(stage = "context_loading", summary = "Loading the reply context")
        awaitGlobalMemoryRefresh(trace)
        awaitMemoryRefresh(conversationKey, trace)

        val preflightIntent =
            OutboundIntent(
                reservationId = queued.turnId,
                chatJid = newest.chatJid,
                proactive = queued.proactive,
                admin = currentAccess.isAdmin,
                countsTowardBudget = false,
                textHash = EMPTY_TEXT_SHA256,
                timestampMs = clock.wallTimeMillis(),
            )
        // Deliberately not a loop: every outcome either lets the turn through or ends it. It used
        // to be wrapped in `while (true)`, which read like a retry that was never there.
        when (val decision = store.preflightOutbound(preflightIntent)) {
            is OutboundDecision.Allowed -> Unit
            is OutboundDecision.Blocked -> {
                trace.recordSafetyDecision("safety_blocked", decision.reason)
                return ProactiveTurnOutcome.Blocked(decision.hard, reason = decision.reason)
            }
            is OutboundDecision.Deferred -> {
                trace.recordSafetyDecision(
                    stage = "safety_deferred",
                    // The bare limit name left the log dead-ending on "Daily send limit" with no
                    // way to tell a short hold from a silence lasting the rest of the day. The
                    // retry time is the only part that answers "is it coming back?".
                    reason =
                        decision.reason +
                            " · retrying in " +
                            formatWait(
                                (decision.untilMs - clock.wallTimeMillis()).coerceAtLeast(0L),
                            ),
                )
                if (!queued.proactive) deferReactiveTurn(queued, decision.untilMs)
                return ProactiveTurnOutcome.Deferred(decision.untilMs, decision.reason)
            }
        }

        // She opens the chat now: the dot goes on before the receipts and stays
        // on through generation and typing. The finally block hands it back to
        // the idle tail, so everything from here on must be inside the try.
        presence.hold(newest.chatJid)

        // Runs concurrently with the model call below; joined before anything the
        // contact can see. Child of the turn's own job, so an interrupt kills it.
        var listening: Job? = null

        try {
            if (settings.markRead && !queued.proactive) {
                // Anything already acknowledged by the open-chat receipt in [accept]
                // is not receipted twice.
                val unread =
                    synchronized(batchLock) {
                        events.map(IncomingEvent::messageId)
                            .filterNot { it in earlyReadMessageIds }
                    }
                val readReceipt =
                    runCatching {
                        if (unread.isNotEmpty()) whatsapp.markRead(newest.chatJid, unread)
                        listening =
                            CoroutineScope(currentCoroutineContext()).launch {
                                playVoiceNotesSequentially(
                                    chatJid = newest.chatJid,
                                    events = events,
                                    settings = settings,
                                    trace = trace,
                                )
                            }
                    }
                if (readReceipt.isFailure) {
                    // Receipts improve WhatsApp realism, but they are not allowed
                    // to suppress the reply itself when the linked-device API is
                    // slow or temporarily rejects a receipt.
                    trace.record(
                        stage = "receipt_failed",
                        summary = "Read receipt failed · reply continues",
                        level = TurnActivityLevel.WARN,
                    )
                }
            }

            val turnInput =
                TurnInput(
                    turnId = queued.turnId,
                    chatJid = newest.chatJid,
                    conversationKey = conversationKey,
                    isGroup = newest.isGroup,
                    senderName = newest.senderName,
                    newestEvents = events,
                    history =
                        store
                            .loadHistory(
                                conversationKey,
                                if (settings.historyLimit <= 0) {
                                    0
                                } else {
                                    settings.historyLimit + events.size
                                },
                            )
                            .filterNot { it.id in newestMessageIds },
                    chatMemory = store.loadChatMemory(conversationKey),
                    personaMemory = store.loadPersonaMemory(settings.personality),
                    settings = settings,
                    mood = MoodEngine.at(clock.wallTimeMillis(), settings.timezone),
                    proactive = queued.proactive,
                    isAdmin = currentAccess.isAdmin,
                    proactiveTrailingDirective = queued.proactiveTrailingDirective,
                )
            trace.record(
                stage = "ai_dispatched",
                summary = "Reply context ready · starting AI processing",
            )
            val output =
                ai.run(turnInput)
            trace.record(
                stage = "model_completed",
                summary = "Reply planned · ${formatDuration(clock.wallTimeMillis() - turnStartedAtMs)}",
            )
            if (output.noReply) {
                // Silence is a base response outcome. Only direct chats with proactive follow-up
                // enabled need a deferred record; groups and non-proactive chats simply stay
                // silent. Never turn a valid control outcome into a turn failure.
                if (!queued.proactive && settings.proactiveLevel > 0 && !newest.isGroup) {
                    store.markDeferred(conversationKey, clock.wallTimeMillis())
                    proactive?.onDeferred(newest, settings)
                }
                trace.record(
                    stage = "no_reply",
                    summary = "No reply intended · ${formatDuration(clock.wallTimeMillis() - turnStartedAtMs)}",
                    // A decision, not a fault: staying silent is a valid outcome
                    // and must not look like something went wrong.
                    level = TurnActivityLevel.INFO,
                )
                return ProactiveTurnOutcome.Silent
            }

            val initialTextBubbles = output.pendingBubbles()
            val bubbles = initialTextBubbles.map(PendingTextBubble::text)
            if (bubbles.isEmpty() && output.reaction == null && output.actions.isEmpty()) {
                // The turn ran to completion and produced nothing sendable, without the model
                // having asked for silence — that is a defect, not a decision, so it gets a record
                // instead of an unexplained return.
                trace.record(
                    stage = "empty_output",
                    summary =
                        "Reply was lost · no bubble, reaction or action · " +
                            formatDuration(clock.wallTimeMillis() - turnStartedAtMs),
                    level = TurnActivityLevel.WARN,
                )
                return ProactiveTurnOutcome.Silent
            }

            val reservationId = queued.turnId
            val wholeReplyChars = bubbles.sumOf { it.length }
            val intent =
                OutboundIntent(
                    reservationId = reservationId,
                    chatJid = newest.chatJid,
                    proactive = queued.proactive,
                    admin = currentAccess.isAdmin,
                    countsTowardBudget = false,
                    payloadChars = wholeReplyChars,
                    textHash = bubbles.joinToString("\u0000").sha256(),
                    timestampMs = clock.wallTimeMillis(),
                )
            // As in the preflight above: no branch here ever comes back round.
            when (val decision = store.reserveOutbound(intent)) {
                is OutboundDecision.Allowed -> Unit
                is OutboundDecision.Blocked ->
                    return ProactiveTurnOutcome.Blocked(hard = decision.hard, reason = decision.reason)

                is OutboundDecision.Deferred -> {
                    if (!queued.proactive) deferReactiveTurn(queued, decision.untilMs)
                    return ProactiveTurnOutcome.Deferred(decision.untilMs, decision.reason)
                }
            }

            val transportIds = ArrayList<String>()
            val sentTextBubbles = ArrayList<String>()
            val pendingTextBubbles =
                ArrayDeque<PendingTextBubble>().apply { addAll(initialTextBubbles) }
            val confirmedAssistantMessages = ArrayList<StoredTurnMessage>()
            var reservationCommitted = false
            var visibleSendCommitted = false
            var midTurnStop: MidTurnOutboundStop? = null
            var committedActions: List<PlannedSideEffect> = emptyList()

            suspend fun recordVisibleTransport(transportId: String?) {
                transportId?.let(transportIds::add)
                visibleSendCommitted = true
                // The contact can see something now, so these inputs count as
                // answered: a later interrupt must not replay them.
                synchronized(activeLock) {
                    activeTurn?.takeIf { it.turnId == queued.turnId }?.answered = true
                }
                if (!reservationCommitted) {
                    // From the first externally visible action onward this turn
                    // is conservatively SENT. A later bubble/media failure must
                    // never reopen the whole turn for recovery.
                    store.completeOutbound(
                        reservationId,
                        transportId,
                        true,
                        clock.wallTimeMillis(),
                    )
                    reservationCommitted = true
                }
            }

            suspend fun recordAssistantEvent(
                text: String,
                transportId: String,
            ): StoredTurnMessage {
                val timestampMs = clock.wallTimeMillis()
                store.recordAssistant(
                    conversationKey = conversationKey,
                    chatJid = newest.chatJid,
                    text = text,
                    transportMessageIds = listOf(transportId),
                    timestampMs = timestampMs,
                )
                return StoredTurnMessage(
                    id = transportId,
                    role = "assistant",
                    text = text,
                    timestampMs = timestampMs,
                )
            }

            /**
             * The words of the quoted message, as WhatsApp shows them in the reply
             * bubble. The durable history keeps its own labels ("You sent: …"), which
             * are for the model and must not leak into a chat bubble.
             */
            fun quotedText(targetMessageId: String?): String? {
                val target = targetMessageId?.takeIf(String::isNotBlank) ?: return null
                input@ for (event in queued.events) {
                    if (event.messageId != target) continue@input
                    return event.text.takeIf(String::isNotBlank)
                        ?: event.media?.caption?.takeIf(String::isNotBlank)
                        ?: event.reactionEmoji?.takeIf(String::isNotBlank)
                }
                return turnInput.history.firstOrNull { it.id == target }
                    ?.text
                    ?.withoutHistoryLabel()
                    ?.takeIf(String::isNotEmpty)
            }

            fun replySnippet(targetMessageId: String?): String? {
                val target = targetMessageId?.takeIf(String::isNotBlank) ?: return null
                return quotedText(target) ?: "message $target"
            }

            suspend fun dispatchVisible(
                childReservationId: String,
                payloadHash: String,
                // Left at 0 for reactions and media: there the hash identifies one action, and a
                // second identical one really is a duplicate.
                payloadChars: Int = 0,
                operation: suspend () -> String?,
            ): String? {
                val childIntent =
                    OutboundIntent(
                        reservationId = childReservationId,
                        chatJid = newest.chatJid,
                        proactive = queued.proactive,
                        admin = currentAccess.isAdmin,
                        countsTowardBudget = true,
                        textHash = payloadHash,
                        payloadChars = payloadChars,
                        timestampMs = clock.wallTimeMillis(),
                    )
                // How long this bubble may wait inline before the turn gives up on it. The
                // worker that runs it is the *only* one in the process, and the presence hold
                // is open the whole time, so sleeping here does not delay one bubble — it
                // freezes every chat and keeps her typing indicator lit while nothing is being
                // written. The common case is the 1.2 s inter-message gap and stays well inside
                // the bound; an hourly or daily cap does not, and that one has to unwind.
                val inlineDeadlineMs = clock.wallTimeMillis() + MAX_INLINE_DEFER_MS
                while (true) {
                    when (
                        val decision =
                            store.reserveOutbound(
                                childIntent.copy(timestampMs = clock.wallTimeMillis()),
                            )
                    ) {
                        is OutboundDecision.Allowed -> break
                        is OutboundDecision.Blocked ->
                            throw MidTurnOutboundStop(
                                untilMs = null,
                                hard = decision.hard,
                                reason = decision.reason,
                            )

                        is OutboundDecision.Deferred -> {
                            if (queued.proactive || decision.untilMs > inlineDeadlineMs) {
                                throw MidTurnOutboundStop(
                                    untilMs = decision.untilMs,
                                    hard = false,
                                    reason = decision.reason,
                                )
                            }
                            clock.delay(
                                (decision.untilMs - clock.wallTimeMillis()).coerceAtLeast(1L),
                            )
                        }
                    }
                }
                val transportId =
                    try {
                        operation()
                    } catch (error: Throwable) {
                        runCatching {
                            store.completeOutbound(
                                childReservationId,
                                null,
                                false,
                                clock.wallTimeMillis(),
                            )
                        }
                        throw error
                    }
                // The message is out on the wire. An interrupt arriving in this
                // instant must not lose that fact: cancellation between the send and
                // [recordVisibleTransport] would leave the batch marked unanswered,
                // and the replacement turn would say the very same thing again.
                withContext(NonCancellable) {
                    store.completeOutbound(
                        childReservationId,
                        transportId,
                        true,
                        clock.wallTimeMillis(),
                    )
                    recordVisibleTransport(transportId)
                }
                return transportId
            }

            // Checked once per turn rather than per bubble: the notice precedes the turn's first
            // visible action, whatever kind it is, and later bubbles must not re-ask the database.
            var disclosureChecked = false

            /**
             * Sends the one-off "an AI answers here" notice ahead of the first visible output of
             * this turn — reaction, media, voice or text alike, which is why it gates
             * [dispatchVisible] instead of the text loop.
             *
             * It goes through the same dispatch as any other message, so it takes a reservation,
             * counts against the outbound budget and lands in the durable outbox. It is stamped
             * only after the send returns.
             */
            suspend fun discloseAiOnce() {
                if (!settings.aiDisclosureEnabled) return
                val notice = settings.aiDisclosureText.trim().ifEmpty { return }
                if (!store.needsAiDisclosure(newest.chatJid)) return
                val disclosureId = "$reservationId:ai-disclosure"
                dispatchVisible(
                    childReservationId = disclosureId,
                    payloadHash = notice.sha256(),
                    payloadChars = notice.length,
                ) {
                    whatsapp.sendText(
                        chatJid = newest.chatJid,
                        text = notice,
                        quoteMessageId = null,
                        idempotencyKey = disclosureId,
                        quotePreview = null,
                    )
                }
                withContext(NonCancellable) {
                    store.markAiDisclosureSent(newest.chatJid, clock.wallTimeMillis())
                }
                trace.record(
                    stage = "ai_disclosure_sent",
                    summary = "Told this chat once that an AI answers here",
                )
            }

            suspend fun executeVisible(
                childReservationId: String,
                payloadHash: String,
                payloadChars: Int = 0,
                operation: suspend () -> String?,
            ): String? {
                if (!disclosureChecked) {
                    // Set before the call, not after: discloseAiOnce dispatches through the same
                    // path, and the flag is what stops that from recursing back into here.
                    disclosureChecked = true
                    discloseAiOnce()
                }
                return dispatchVisible(
                    childReservationId = childReservationId,
                    payloadHash = payloadHash,
                    payloadChars = payloadChars,
                    operation = operation,
                )
            }

            try {
                // A verified contact block is terminal for this turn. Do not
                // synthesize/upload media or send a final text to a contact we
                // are about to block.
                var terminalBlock =
                    output.actions
                        .filterIsInstance<PlannedSideEffect.BlockContact>()
                        .firstOrNull()
                val actionsToCommit =
                    terminalBlock?.let(::listOf)
                        ?: output.actions
                trace.record(
                    stage = "actions_materializing",
                    summary = "Materialising side effects · ${actionsToCommit.size} planned",
                )
                committedActions =
                    ai.materializeActions(
                        input = turnInput,
                        actions = actionsToCommit,
                    )
                trace.record(
                    stage = "actions_materialized",
                    summary = "Side effects ready · ${committedActions.size} executable",
                )
                // Everything below is visible to the contact, so she must be done
                // listening first: generation and voice synthesis ran while the
                // clips played, they are not added on top of that time.
                listening?.join()
                // The reading floor. The model call is the reading time — that is
                // exactly why the request goes out immediately instead of sitting
                // behind an artificial delay — but a fast model must not out-run a
                // human eye. Whatever the turn has already spent counts against the
                // floor, so this is normally zero.
                if (!queued.proactive) {
                    val floorMs = timing.readingFloorMs(newest.text, settings)
                    val spentMs = (clock.wallTimeMillis() - turnStartedAtMs).coerceAtLeast(0L)
                    val remainingMs = floorMs - spentMs
                    if (remainingMs > 0L) {
                        // Logged only when it actually binds. A row on every turn would
                        // say nothing — the normal case is that the model call already
                        // took longer than a person needs to read the message.
                        trace.record(
                            stage = "reading_floor",
                            summary =
                                "Reply was ready before the reading time · " +
                                    "${formatWait(remainingMs)} left before typing " +
                                    "(${HumanTimingPolicy.wordCount(newest.text)} words read)",
                            level = TurnActivityLevel.INFO,
                            elapsedMs = spentMs,
                        )
                        clock.delay(remainingMs)
                    }
                }
                // A missed call has no message behind it, so there is nothing in the chat a
                // reaction could stick to. Aim at the newest real message instead, and drop
                // the reaction rather than sending one onto an ID that does not exist.
                val reactionTarget =
                    output.reactionTargetMessageId
                        ?: queued.events
                            .lastOrNull { it.kind != ChatEventKind.CALL_MISSED }
                            ?.messageId
                if (
                    terminalBlock == null &&
                    settings.reactions &&
                    !queued.proactive &&
                    output.reaction != null &&
                    reactionTarget != null
                ) {
                    val target = reactionTarget
                    val actionId = "$reservationId:reaction"
                    val reactionTransportId = executeVisible(
                        childReservationId = actionId,
                        payloadHash = "reaction:$target:${output.reaction}".sha256(),
                    ) {
                        whatsapp.sendReaction(
                            newest.chatJid,
                            target,
                            output.reaction,
                            actionId,
                        )
                    }
                    confirmedAssistantMessages +=
                        recordAssistantEvent(
                            text = ChatHistoryLabels.outgoingReaction(output.reaction),
                            transportId = reactionTransportId ?: "local:$actionId",
                        )
                }
                val actionQueue = ArrayDeque<PlannedSideEffect>().apply { addAll(committedActions) }
                var followUpIndex = 0
                var nextTextSendIndex = 0
                /** Bubbles typed so far; the first one gets the "is writing" row. */
                var typedBubbleCount = 0
                var lastVisibleWasVoice = false

                suspend fun enqueueVisibleFollowUp() {
                    if (
                        terminalBlock != null ||
                        confirmedAssistantMessages.isEmpty() ||
                        followUpIndex >= MAX_VISIBLE_ASSISTANT_FOLLOW_UPS
                    ) {
                        return
                    }
                    followUpIndex += 1
                    val followUp =
                        ai.followUpAfterVisibleAssistant(
                            input = turnInput,
                            confirmedAssistantMessages = confirmedAssistantMessages.toList(),
                            followUpIndex = followUpIndex,
                        )
                    val followUpBlock =
                        followUp.actions
                            .filterIsInstance<PlannedSideEffect.BlockContact>()
                            .firstOrNull()
                    val followUpActions = followUpBlock?.let(::listOf) ?: followUp.actions
                    if (followUpBlock == null) {
                        pendingTextBubbles.addAll(followUp.pendingBubbles())
                    } else {
                        // A server-confirmed block is terminal even when the model decides on it
                        // only after seeing the first delivered output. Discard sibling media and
                        // text from that follow-up so nothing is sent after the contact is blocked.
                        terminalBlock = followUpBlock
                        pendingTextBubbles.clear()
                        actionQueue.clear()
                    }
                    val materializedFollowUpActions =
                        ai.materializeActions(
                            input = turnInput,
                            actions = followUpActions,
                        )
                    committedActions = committedActions + materializedFollowUpActions
                    actionQueue.addAll(materializedFollowUpActions)
                }

                do {
                // One model result can contain several already-planned media outputs. Send and
                // persist that whole batch before asking the model whether anything is still
                // missing. Text uses the normal multi-bubble response and never recursively
                // starts another model call; otherwise a model can keep adding afterthoughts.
                var visibleMediaSentThisBatch = false
                while (actionQueue.isNotEmpty()) {
                    val action = actionQueue.removeFirst()
                    when (action) {
                        is PlannedSideEffect.SendMedia -> {
                            trace.record(
                                stage = "media_send_started",
                                summary =
                                    "Sending WhatsApp media · " +
                                        if (action.voiceNote) {
                                            "PTT · ${action.durationSeconds ?: 0}s · ${action.mimeType.take(80)}"
                                        } else {
                                            action.mimeType.take(100)
                                        },
                            )
                            if (action.voiceNote) {
                                // The clip already exists at this point, so the
                                // visible "recording audio…" stretch matches the
                                // note's own length instead of the TTS latency.
                                holdRecordingPresence(
                                    chatJid = newest.chatJid,
                                    seconds = action.durationSeconds,
                                    settings = settings,
                                )
                            }
                            val mediaTransportId = executeVisible(
                                childReservationId = action.idempotencyKey,
                                payloadHash =
                                    "media:${action.approvedAssetId ?: action.idempotencyKey}".sha256(),
                            ) {
                                whatsapp.sendMedia(newest.chatJid, action)
                            }
                            checkNotNull(mediaTransportId) {
                                "Media send returned no message ID"
                            }
                            // Only after the server confirmed it: the history must
                            // say she sent a voice note or a picture, otherwise the
                            // next turn sees nothing at all where her own media was.
                            val historyLine =
                                MediaHistoryLabels.outgoingLine(
                                    kind =
                                        if (action.voiceNote) {
                                            MediaKind.AUDIO
                                        } else {
                                            MediaHistoryLabels.kindOfMime(action.mimeType)
                                        },
                                    detail = action.historyText ?: action.caption,
                                )
                            val confirmedAt = clock.wallTimeMillis()
                            try {
                                // The continuation is forbidden from running until this exact
                                // assistant message is durable. This is the history the next model
                                // call sees, not a speculative "pending_verification" tool result.
                                store.recordAssistant(
                                    conversationKey = conversationKey,
                                    chatJid = newest.chatJid,
                                    text = historyLine,
                                    transportMessageIds = listOf(mediaTransportId),
                                    timestampMs = confirmedAt,
                                )
                            } catch (persistenceFailure: Throwable) {
                                sentTextBubbles += historyLine
                                throw persistenceFailure
                            }
                            confirmedAssistantMessages +=
                                StoredTurnMessage(
                                    id = mediaTransportId,
                                    role = "assistant",
                                    text = historyLine,
                                    timestampMs = confirmedAt,
                                )
                            trace.record(
                                stage = "media_send_succeeded",
                                summary =
                                    if (action.voiceNote) {
                                        "WhatsApp PTT confirmed by the server"
                                    } else {
                                        "WhatsApp media confirmed by the server"
                                    },
                            )
                            visibleMediaSentThisBatch = true
                            lastVisibleWasVoice = action.voiceNote
                        }

                        is PlannedSideEffect.VoiceTextFallback -> {
                            if (
                                pendingTextBubbles.none { it.text == action.text } &&
                                sentTextBubbles.none { it == action.text }
                            ) {
                                holdComposingPresence(
                                    chatJid = newest.chatJid,
                                    text = action.text,
                                    settings = settings,
                                    trace = trace,
                                    first = typedBubbleCount++ == 0,
                                )
                                val transportId =
                                    executeVisible(
                                        childReservationId = action.idempotencyKey,
                                        payloadHash = action.text.sha256(),
                                        payloadChars = action.text.length,
                                    ) {
                                        val quoted =
                                            output.quoteMessageId.takeIf {
                                                settings.quoteReplies && !queued.proactive
                                            }
                                        whatsapp.sendText(
                                            chatJid = newest.chatJid,
                                            text = action.text,
                                            quoteMessageId = quoted,
                                            idempotencyKey = action.idempotencyKey,
                                            quotePreview = quotedText(quoted),
                                        )
                                    }
                                checkNotNull(transportId) {
                                    "Voice text fallback returned no message ID"
                                }
                                activity.draft(newest.chatJid, "")
                                confirmedAssistantMessages +=
                                    recordAssistantEvent(
                                        text =
                                            ChatHistoryLabels.outgoingText(
                                                action.text,
                                                replySnippet(
                                                    output.quoteMessageId.takeIf {
                                                        settings.quoteReplies && !queued.proactive
                                                    },
                                                ),
                                            ),
                                        transportId = transportId,
                                    )
                                sentTextBubbles += action.text
                            }
                        }

                        is PlannedSideEffect.BlockContact -> {
                            whatsapp.blockContact(
                                jid = action.jid,
                                reason = action.reason,
                                idempotencyKey = action.idempotencyKey,
                                aliases = action.aliases,
                            )
                            // A tool commit is successful only after WhatsApp has
                            // confirmed its server-side blocklist. The local deny
                            // entry follows as the app-side defense in depth.
                            store.blockSender(
                                newest.copy(senderJid = action.jid),
                                action.reason.ifBlank { "Vom Modell blockiert" },
                            )
                        }

                        is PlannedSideEffect.RequestMemoryRefresh ->
                            store.requestMemoryRefresh(
                                action.conversationKey,
                                clock.wallTimeMillis(),
                            )

                        is PlannedSideEffect.ScheduleFollowUp -> {
                            val persistence = proactivePersistence
                                ?: error("Scheduled follow-ups are unavailable")
                            check(action.conversationKey == conversationKey) {
                                "A follow-up may target only the current conversation"
                            }
                            persistence.scheduleFollowUp(
                                ScheduledFollowUpRequest(
                                    id = action.idempotencyKey,
                                    chatJid = newest.chatJid,
                                    conversationKey = action.conversationKey,
                                    personaKey = action.personaKey,
                                    scheduledAtMs = action.scheduledAtMs,
                                    note = action.note,
                                    createdAtMs = clock.wallTimeMillis(),
                                ),
                            )
                            proactive?.onScheduledFollowUpChanged()
                            trace.record(
                                stage = "followup_scheduled",
                                summary = "Later follow-up saved for this chat",
                            )
                        }

                        is PlannedSideEffect.SynthesizeVoiceNote,
                        is PlannedSideEffect.UploadApprovedImage,
                        is PlannedSideEffect.GenerateImage,
                        -> error("Media action was never materialised")
                    }
                }
                val currentTextBatch = buildList {
                    while (pendingTextBubbles.isNotEmpty()) add(pendingTextBubbles.removeFirst())
                }
                currentTextBatch
                    .takeIf { terminalBlock == null }
                    .orEmpty()
                    .forEachIndexed { batchIndex, pendingBubble ->
                        val bubble = pendingBubble.text
                        if (lastVisibleWasVoice) {
                            // Recording and typing are different physical actions, so this mode
                            // switch keeps the short break that text bubbles no longer use.
                            runCatching { whatsapp.setPresence(newest.chatJid, "paused") }
                            clock.delay(timing.interBubbleGapMs(settings))
                            lastVisibleWasVoice = false
                        }
                        holdComposingPresence(
                            chatJid = newest.chatJid,
                            text = bubble,
                            settings = settings,
                            trace = trace,
                            first = typedBubbleCount++ == 0,
                            position = batchIndex + 1,
                            total = currentTextBatch.size,
                        )
                        val textSendIndex = nextTextSendIndex++
                        val actionId = "$reservationId:text:$textSendIndex"
                        val typo =
                            bubble.minorTypoOrNull(actionId)
                                ?.takeIf {
                                    settings.selfEditEnabled &&
                                        stableFraction("$actionId:self-edit") <
                                        settings.selfEditChance.coerceIn(0.0, 1.0)
                                }
                        val transportId =
                            executeVisible(
                                childReservationId = actionId,
                                payloadHash = bubble.sha256(),
                                payloadChars = bubble.length,
                            ) {
                                val quoted =
                                    pendingBubble.quoteMessageId.takeIf {
                                        settings.quoteReplies && !queued.proactive
                                    }
                                whatsapp.sendText(
                                    chatJid = newest.chatJid,
                                    text = typo ?: bubble,
                                    quoteMessageId = quoted,
                                    idempotencyKey = actionId,
                                    quotePreview = quotedText(quoted),
                                )
                            }
                        val sentMessageId = checkNotNull(transportId) {
                            "Text send returned no message ID"
                        }
                        // It left the field. Emptying it is what makes the next bubble read as a
                        // second message rather than as the first one growing.
                        activity.draft(newest.chatJid, "")
                        // Recorded here, before the self-edit pause and before the next
                        // bubble is typed — not at the end of the turn. A message that
                        // is on the contact's screen belongs in the chat log from that
                        // moment on, so an interrupt regenerates against what was
                        // actually said instead of repeating it.
                        val historyIndex = confirmedAssistantMessages.size
                        val actualSentBubble = typo ?: bubble
                        confirmedAssistantMessages +=
                            withContext(NonCancellable) {
                                recordAssistantEvent(
                                    text =
                                        ChatHistoryLabels.outgoingText(
                                            actualSentBubble,
                                            replySnippet(
                                                pendingBubble.quoteMessageId.takeIf {
                                                    settings.quoteReplies && !queued.proactive
                                                },
                                            ),
                                            // The one fact this turn knows and the transcript
                                            // otherwise loses: nobody wrote to her, she wrote to
                                            // them. Without it the next turn reads its own outreach
                                            // as an answer and greets the reply to it with "why are
                                            // you writing me".
                                            openedConversation = queued.proactive,
                                        ),
                                    transportId = sentMessageId,
                                )
                            }
                        var finalVisibleBubble = actualSentBubble
                        if (typo != null) {
                            // Correcting a message is typing, so it takes time proportional to the
                            // message — a flat pause corrected a two-hundred-character bubble as
                            // fast as "ok". The divisor is what makes it a correction rather than a
                            // retype, and the indicator runs while it happens, exactly as WhatsApp
                            // shows it for a real edit.
                            holdEditingPresence(
                                chatJid = newest.chatJid,
                                text = bubble,
                                settings = settings,
                            )
                            // The original send is already durably SENT at this
                            // point. An optional realism edit must never reopen
                            // the turn or cause a duplicate reply when WhatsApp
                            // rejects/loses the edit operation.
                            try {
                                whatsapp.editText(
                                    chatJid = newest.chatJid,
                                    messageId = sentMessageId,
                                    text = bubble,
                                    idempotencyKey = "$actionId:edit",
                                )
                                val finalHistoryText =
                                    ChatHistoryLabels.outgoingText(
                                        bubble,
                                        replySnippet(
                                            pendingBubble.quoteMessageId.takeIf {
                                                settings.quoteReplies && !queued.proactive
                                            },
                                        ),
                                    )
                                check(store.reviseAssistant(sentMessageId, finalHistoryText)) {
                                    "Edited assistant message was not reconciled in history"
                                }
                                confirmedAssistantMessages[historyIndex] =
                                    confirmedAssistantMessages[historyIndex].copy(text = finalHistoryText)
                                finalVisibleBubble = bubble
                            } catch (editFailure: Throwable) {
                                runCatching {
                                    store.recordTurnFailure(
                                        chatJid = newest.chatJid,
                                        turnId = "$actionId:edit",
                                        proactive = queued.proactive,
                                        error = editFailure,
                                        timestampMs = clock.wallTimeMillis(),
                                    )
                                }
                            }
                        }
                        sentTextBubbles += finalVisibleBubble
                    }
                if (
                    terminalBlock == null &&
                    visibleMediaSentThisBatch
                ) {
                    enqueueVisibleFollowUp()
                }
                } while (actionQueue.isNotEmpty() || pendingTextBubbles.isNotEmpty())
                if (!reservationCommitted) {
                    // A verified action-only turn (for example memory/block)
                    // still consumes its idempotency key even without a chat
                    // message.
                    store.completeOutbound(
                        reservationId,
                        null,
                        true,
                        clock.wallTimeMillis(),
                    )
                    reservationCommitted = true
                }
            } catch (stop: MidTurnOutboundStop) {
                // Not a fault: the outbound policy said "not now" for a bubble this turn had
                // already started. The turn unwinds instead of sleeping in the shared worker,
                // and the reservation is closed the same way the failure path closes it.
                midTurnStop = stop
                if (!visibleSendCommitted) {
                    runCatching {
                        store.completeOutbound(
                            reservationId,
                            null,
                            false,
                            clock.wallTimeMillis(),
                        )
                    }
                } else if (!reservationCommitted) {
                    runCatching {
                        store.completeOutbound(
                            reservationId,
                            transportIds.firstOrNull(),
                            true,
                            clock.wallTimeMillis(),
                        )
                    }
                    reservationCommitted = true
                }
            } catch (error: Throwable) {
                if (!visibleSendCommitted) {
                    runCatching {
                        store.completeOutbound(
                            reservationId,
                            null,
                            false,
                            clock.wallTimeMillis(),
                        )
                    }
                } else if (!reservationCommitted) {
                    runCatching {
                        store.completeOutbound(
                            reservationId,
                            transportIds.firstOrNull(),
                            true,
                            clock.wallTimeMillis(),
                        )
                    }
                }
                throw error
            }

            if (midTurnStop != null && !visibleSendCommitted) {
                // Nothing reached the contact, so the whole turn can be replayed later without
                // repeating itself.
                val untilMs = midTurnStop.untilMs
                if (untilMs == null) {
                    trace.record(
                        stage = "blocked",
                        summary = "Send blocked before the first message · ${midTurnStop.reason}",
                        level = TurnActivityLevel.WARN,
                    )
                    return ProactiveTurnOutcome.Blocked(
                        hard = midTurnStop.hard,
                        reason = midTurnStop.reason,
                    )
                }
                trace.record(
                    stage = "deferred",
                    summary =
                        "Send deferred before the first message · ${midTurnStop.reason} · " +
                            "retry in ${formatWait(untilMs - clock.wallTimeMillis())}",
                    level = TurnActivityLevel.INFO,
                )
                if (!queued.proactive) deferReactiveTurn(queued, untilMs)
                return ProactiveTurnOutcome.Deferred(untilMs, midTurnStop.reason)
            }
            if (midTurnStop != null) {
                // Bubbles are already on the wire. Rescheduling would say the first half a
                // second time, so the rest is dropped and the turn closes as sent.
                trace.record(
                    stage = "truncated",
                    summary =
                        "Reply cut short after ${transportIds.size} message(s) · ${midTurnStop.reason}",
                    level = TurnActivityLevel.WARN,
                )
            }

            val bookkeepingFailure =
                runCatching {
                    store.clearDeferred(conversationKey)
                    store.setLastOnlineAt(clock.wallTimeMillis())
                    session.noteOnline(clock.wallTimeMillis())
                    // Nothing to note about the open chat here any more: the presence
                    // hold around this turn already has her online in it, and the dot's
                    // own idle tail is what decides when she leaves.
                    if (!queued.proactive) {
                        proactive?.onReactiveReply(newest.chatJid, settings)
                    }
                }.exceptionOrNull()
            if (bookkeepingFailure != null) {
                runCatching {
                    store.recordTurnFailure(
                        chatJid = newest.chatJid,
                        turnId = queued.turnId,
                        proactive = queued.proactive,
                        error = bookkeepingFailure,
                        timestampMs = clock.wallTimeMillis(),
                    )
                }
            } else if (
                visibleSendCommitted ||
                committedActions.any { it is PlannedSideEffect.RequestMemoryRefresh }
            ) {
                // Detached so the reply is never held up by consolidation, but tracked: the next
                // turn of this conversation waits for it, see [awaitMemoryRefresh].
                launchMemoryRefresh(
                    conversationKey = conversationKey,
                    newest = newest,
                    turnInput = turnInput,
                    committedActions = committedActions,
                )
            }
            return if (!visibleSendCommitted) {
                trace.record(
                    stage = "silent",
                    summary = "Turn ended without a visible message · ${formatDuration(clock.wallTimeMillis() - turnStartedAtMs)}",
                    // Nothing failed — the turn simply had nothing to say out loud.
                    level = TurnActivityLevel.INFO,
                )
                ProactiveTurnOutcome.Silent
            } else {
                trace.record(
                    stage = "sent",
                    summary = "Reply sent · ${formatDuration(clock.wallTimeMillis() - turnStartedAtMs)}",
                    level = TurnActivityLevel.INFO,
                )
                ProactiveTurnOutcome.Sent
            }
        } finally {
            // A turn that ends early (no reply, block, failure) must not leave the
            // listening phase running as an orphan child of this job.
            listening?.cancel()
            // Whatever this turn still had buffered. Written even when it was cancelled
            // mid-reply — an interrupt is exactly the case the log gets read for, and every one
            // of these rows used to reach the database the moment it was recorded.
            withContext(NonCancellable) { runCatching { trace.flush() } }
            // Whatever ended this turn — sent, silent, blocked — the typing indicator
            // must not outlive it. WhatsApp does expire it on its own, but seconds
            // later, and a "typing…" that hangs after the answer already arrived is
            // the most visible tell there is. On the cancelled path this call cannot
            // run, and does not need to: [accept] already sent `paused` before it
            // pulled the trigger on the interrupt.
            if (currentCoroutineContext().isActive) {
                cancelPendingTypingStop(newest.chatJid)
                runCatching { whatsapp.setPresence(newest.chatJid, "paused") }
            }
            // NonCancellable is load-bearing, not defensive. release() takes a mutex, and taking a
            // mutex from an already-cancelled coroutine throws before the hold is given back — so
            // an interrupted turn leaked its hold, the counter never reached zero, and the green
            // dot stayed lit for as long as the app ran. Permanently online is the single most
            // obvious bot tell there is.
            withContext(NonCancellable) { presence.release(newest.chatJid) }
        }
    }

    /**
     * She actually LISTENS to the incoming voice notes, ported from the reference
     * build's `playVoiceNotesSequentially`: after the read receipt she takes a beat
     * to hit play, then plays one clip at a time — a played receipt (blue mic) per
     * note, spaced by that note's own length, because clip 2 cannot be heard before
     * clip 1 has finished.
     *
     * This runs as its own phase IN PARALLEL with the model call, so the request
     * still goes out at second 0 and only the first visible signal waits until the
     * notes could plausibly have been heard. Receipt failures are swallowed: a
     * missing blue mic must never suppress the reply itself.
     */
    private suspend fun playVoiceNotesSequentially(
        chatJid: String,
        events: List<IncomingEvent>,
        settings: EngineSettingsSnapshot,
        trace: TurnTrace,
    ) {
        val voiceNotes = events.filter(IncomingEvent::isVoiceMessage)
        if (voiceNotes.isEmpty()) return

        suspend fun firePlayed(note: IncomingEvent) {
            runCatching { whatsapp.markPlayed(note.chatJid, note.messageId) }
        }

        // Instant preset: no human listening delay — just flip every clip to played.
        if (settings.replyPreset == ReplyPreset.INSTANT) {
            voiceNotes.forEach { firePlayed(it) }
            return
        }

        val totalMs = voiceNotes.sumOf(::listeningTimeMs)
        trace.record(
            stage = "listening",
            summary =
                "Listening to ${voiceNotes.size} voice message${if (voiceNotes.size == 1) "" else "s"} · " +
                    formatWait(totalMs),
            level = TurnActivityLevel.INFO,
        )

        // "She's in the chat, but needs a second to hit play."
        clock.delay(LISTEN_PRESS_PLAY_MS)
        voiceNotes.forEach { note ->
            firePlayed(note)
            // She listens to the whole clip before tapping play on the next one.
            clock.delay(listeningTimeMs(note))
        }
    }

    /**
     * Hands an interrupted "typing…" state to the replacement turn without a visible blink.
     *
     * Detached on purpose: this sits on the intake path, and holding the new message's processing
     * would delay its read receipt. The old two-second delayed `paused` made interruption visible;
     * now that delay becomes the first refresh and composing continues at WhatsApp's minimum
     * validity cadence until the replacement owns or ends it.
     */
    private fun pauseComposingSoon(
        chatJid: String,
        settings: EngineSettingsSnapshot,
    ) {
        val delayMs = timing.typingStopDelayMs(settings)
        if (delayMs <= 0L) {
            cancelPendingTypingStop(chatJid)
            scope.launch(CoroutineName("typing-stop")) {
                runCatching { whatsapp.setPresence(chatJid, "paused") }
            }
            return
        }
        val job =
            scope.launch(CoroutineName("typing-stop")) {
                clock.delay(delayMs)
                while (currentCoroutineContext().isActive) {
                    runCatching { whatsapp.setPresence(chatJid, "composing") }
                    clock.delay(COMPOSING_REFRESH_MS)
                }
            }
        pendingTypingStops.put(chatJid, job)?.cancel()
        job.invokeOnCompletion { pendingTypingStops.remove(chatJid, job) }
    }

    private fun cancelPendingTypingStop(chatJid: String) {
        pendingTypingStops.remove(chatJid)?.cancel()
    }

    /**
     * Holds one visible WhatsApp state for [targetMs], keeping it alive on the way.
     *
     * WhatsApp expires a chat presence a few seconds after it is set, so a one-shot state is
     * invisible for anything but the shortest message — that is why the writing signal used to
     * flash and vanish no matter how long the delay was. The state is therefore set once up front
     * and re-sent every [refreshMs] until the wait is over, but never on the last leg, where the
     * send follows immediately anyway.
     *
     * Typing, correcting and recording were three copies of this loop that differed only in the
     * state name and the cadence.
     */
    private suspend fun holdPresence(
        chatJid: String,
        state: String,
        targetMs: Long,
        refreshMs: Long,
    ) {
        runCatching { whatsapp.setPresence(chatJid, state) }
        var elapsed = 0L
        while (elapsed < targetMs) {
            val step = (targetMs - elapsed).coerceAtMost(refreshMs)
            clock.delay(step)
            elapsed += step
            if (elapsed < targetMs) runCatching { whatsapp.setPresence(chatJid, state) }
        }
    }

    /**
     * Types one bubble: holds WhatsApp's "typing…" state for as long as writing it would actually
     * take, then returns so the caller can send it.
     *
     * A text batch is one keyboard session. The human inter-bubble pause stays, but composing does
     * not blink; recording-to-text is the deliberate exception because it is a physical mode
     * switch rather than another line on the same keyboard.
     */
    private suspend fun holdComposingPresence(
        chatJid: String,
        text: String,
        settings: EngineSettingsSnapshot,
        trace: TurnTrace,
        first: Boolean,
        position: Int = 1,
        total: Int = 1,
    ) {
        // She is typing again; whatever the last interrupt still owed the indicator is moot.
        cancelPendingTypingStop(chatJid)
        val target = timing.typingDelayMs(text, settings)
        if (!first) {
            // Send, glance at it, start the next one — with the indicator off.
            holdPresence(
                chatJid,
                "composing",
                timing.interBubbleGapMs(settings),
                COMPOSING_REFRESH_MS,
            )
        } else {
            trace.record(
                stage = "typing",
                summary = "Bot is typing · ${formatWait(target)}",
                level = TurnActivityLevel.INFO,
            )
        }
        // Republished per bubble, because the trace record above only fires for the first one and
        // the count is the whole point: "typing 2/3" says a second message is still coming.
        activity.stage(
            chatJid = chatJid,
            stage = ChatStage.TYPING,
            detail = if (total > 1) "$position/$total" else null,
            nowMs = clock.wallTimeMillis(),
        )
        // Switched on even when there is nothing to wait through. The instant preset
        // still sends a message, and a chat that goes from "online" straight to a
        // finished message without ever showing "schreibt…" is a tell of its own.
        holdComposingWithDraft(chatJid, text, target)
    }

    /**
     * Holds "composing" for [targetMs] and fills the live field as it goes.
     *
     * The presence refresh and the draft are one loop rather than two coroutines on purpose: they
     * share a clock, and a second coroutine ticking against the same virtual clock is a race the
     * engine does not need. The draft grows by elapsed fraction rather than per character, so a
     * four-hundred-character bubble costs the same number of published updates as a four-word one.
     *
     * What the field shows is exactly what is about to be sent — this is a recorder, not a
     * simulation, so no typos are staged here. The self-edit path already sends the typo for real.
     */
    private suspend fun holdComposingWithDraft(
        chatJid: String,
        text: String,
        targetMs: Long,
    ) {
        runCatching { whatsapp.setPresence(chatJid, "composing") }
        if (targetMs <= 0L || text.isEmpty()) {
            activity.draft(chatJid, text)
            return
        }
        var elapsed = 0L
        var sinceRefresh = 0L
        var publishedLength = 0
        while (elapsed < targetMs) {
            val step = (targetMs - elapsed).coerceAtMost(DRAFT_TICK_MS)
            clock.delay(step)
            elapsed += step
            sinceRefresh += step
            // A slow bubble advances by less than one character per tick, and republishing the
            // identical prefix still costs a state emission and a recomposition in every open
            // chat screen. Only a field that actually grew is worth publishing.
            val length = ((text.length * elapsed) / targetMs).toInt()
            if (length > publishedLength) {
                publishedLength = length
                activity.draft(chatJid, text.take(length))
            }
            if (elapsed < targetMs && sinceRefresh >= COMPOSING_REFRESH_MS) {
                sinceRefresh = 0L
                runCatching { whatsapp.setPresence(chatJid, "composing") }
            }
        }
        activity.draft(chatJid, text)
    }

    /**
     * Holds "schreibt…" while the just-sent bubble is being corrected.
     *
     * Same per-character model as [holdComposingPresence], only shortened by
     * [EngineSettingsSnapshot.selfEditDelayDivisor], because fixing one slip is not retyping the
     * message.
     */
    private suspend fun holdEditingPresence(
        chatJid: String,
        text: String,
        settings: EngineSettingsSnapshot,
    ) {
        val target = timing.selfEditDelayMs(text, settings)
        if (target <= 0L) return
        holdPresence(chatJid, "composing", target, COMPOSING_REFRESH_MS)
    }

    /**
     * The counterpart to typing for a voice note: WhatsApp's "recording audio…"
     * state, held for roughly the finished clip's length before it goes out. A
     * two-minute note appearing out of nowhere is an obvious tell — the indicator
     * has to run for as long as she would have been talking.
     *
     * Skipped in the instant preset like every other human-timing delay.
     */
    private suspend fun holdRecordingPresence(
        chatJid: String,
        seconds: Int?,
        settings: EngineSettingsSnapshot,
    ) {
        if (settings.replyPreset == ReplyPreset.INSTANT) return
        val target =
            ((seconds ?: DEFAULT_VOICE_SECONDS).coerceAtLeast(1) * 1_000L)
                .coerceAtMost(RECORDING_MAX_MS)
        holdPresence(chatJid, "recording", target, RECORDING_REFRESH_MS)
    }

    /**
     * How long she needs to hear one voice note out. Real time, bounded on both
     * ends: even a two-second memo costs a moment of attention, and a very long
     * (or mis-reported) clip must not stall the whole queue.
     */
    private fun listeningTimeMs(event: IncomingEvent): Long {
        val seconds = event.media?.durationSeconds?.takeIf { it > 0 } ?: DEFAULT_VOICE_SECONDS
        return (seconds * 1_000L).coerceIn(LISTEN_MIN_MS, LISTEN_MAX_MS)
    }

    /**
     * The sendable bubbles of one model result: trimmed, bounded, and each carrying the message it
     * is meant to quote.
     *
     * Used for the turn's first result and for every visible follow-up, which had a verbatim copy
     * of this block. A single bubble quote wins over the turn-wide one; the turn-wide quote only
     * applies when the model named no per-bubble targets at all, and then only to the first bubble.
     */
    private fun TurnOutput.pendingBubbles(): List<PendingTextBubble> {
        if (noReply) return emptyList()
        val raw =
            when {
                bubbles.isNotEmpty() -> bubbles
                text.isNotBlank() -> listOf(text)
                else -> emptyList()
            }
        return raw.take(MAX_BUBBLES).mapIndexedNotNull { index, rawBubble ->
            rawBubble.trim().takeIf(String::isNotEmpty)?.let { bubble ->
                PendingTextBubble(
                    text = bubble,
                    quoteMessageId =
                        bubbleQuoteMessageIds.getOrNull(index)
                            ?: quoteMessageId.takeIf {
                                bubbleQuoteMessageIds.isEmpty() && index == 0
                            },
                )
            }
        }
    }

    /**
     * One turn's activity rows, written as a batch instead of one insert at a time.
     *
     * Every row used to be its own dispatcher hop, its own SQLite insert and its own refresh of
     * the activity feed — around twenty of them for a single reply, most of them
     * [TurnActivityLevel.DEBUG] steps nobody reads while the bot is working. They are collected
     * here and handed to the store together.
     *
     * Anything at [TurnActivityLevel.INFO] or above flushes at once, buffer included. Those are
     * the rows that explain a silence *while it lasts* ("going online in 12m", "bot is typing"),
     * so holding them back until the turn ends would leave the feed dark exactly when someone is
     * watching it. The buffer is a noise filter, not a write-behind cache.
     *
     * Rows keep the timestamp they were recorded with and are written in the order they were
     * buffered, so a late flush cannot reorder the feed. The buffer is guarded because the
     * listening phase traces from its own coroutine while the turn is generating.
     */
    private inner class TurnTrace(
        private val chatJid: String,
        private val turnId: String,
        private val startedAtMs: Long,
    ) {
        private val buffered = ArrayList<TurnActivity>()

        suspend fun record(
            stage: String,
            summary: String,
            level: TurnActivityLevel = TurnActivityLevel.DEBUG,
            timestampMs: Long = clock.wallTimeMillis(),
            elapsedMs: Long = (timestampMs - startedAtMs).coerceAtLeast(0L),
        ) {
            val flushNow =
                synchronized(buffered) {
                    buffered +=
                        TurnActivity(
                            chatJid = chatJid,
                            turnId = turnId,
                            stage = stage,
                            summary = summary,
                            level = level,
                            elapsedMs = elapsedMs,
                            timestampMs = timestampMs,
                        )
                    level != TurnActivityLevel.DEBUG
                }
            publishLive(stage, summary, level, timestampMs)
            if (flushNow) flush()
        }

        /**
         * Mirrors the same record into the live feed the UI watches.
         *
         * Every stage of a turn already passes through here on its way to the activity log, so one
         * call covers all of them instead of forty publish sites that would drift apart. The log
         * write is durable and batched; this is neither, and deliberately so — it is only ever
         * true while the turn is running.
         */
        private fun publishLive(
            stage: String,
            summary: String,
            level: TurnActivityLevel,
            timestampMs: Long,
        ) {
            liveStageFor(stage)?.let { live ->
                val detail =
                    when (stage) {
                        "media_send_started", "media_send_succeeded" ->
                            if (summary.contains("PTT", ignoreCase = true)) {
                                "voice message"
                            } else {
                                "media"
                            }
                        else -> null
                    }
                activity.stage(chatJid, live, detail = detail, nowMs = timestampMs)
            }
            activity.trace(
                chatJid = chatJid,
                kind = if (level == TurnActivityLevel.WARN) TraceKind.PROBLEM else TraceKind.STEP,
                text = summary,
                nowMs = timestampMs,
            )
        }

        /** A blocked or deferred turn, in the words of the layer that stopped it. */
        suspend fun recordSafetyDecision(
            stage: String,
            reason: String,
        ) = record(
            stage = stage,
            summary = "Safety decision · ${reason.take(160)}",
            level = TurnActivityLevel.WARN,
        )

        suspend fun flush() {
            val pending =
                synchronized(buffered) {
                    if (buffered.isEmpty()) return
                    buffered.toList().also { buffered.clear() }
                }
            store.recordTurnActivities(pending)
        }
    }

    private fun deferReactiveTurn(
        queued: QueuedTurn,
        untilMs: Long,
    ) {
        check(!queued.proactive)
        val chatJid = queued.events.first().chatJid
        val queueKey = queued.events.first().queueKey()
        val deferredGeneration =
            synchronized(batchLock) {
                if (chatGenerations[queueKey] != queued.generation) return
                val next = queued.generation + 1L
                chatGenerations[queueKey] = next
                next
            }
        scope.launch(CoroutineName("policy-defer-${chatJid.hashCode()}")) {
            try {
                // Deliberately not one sleep to `untilMs`. That deadline is a prediction made under
                // the caps as they were, and the operator can lift them at any moment — turning the
                // warm-up off doubles the hourly budget on the spot. Sleeping through that left the
                // bot silent with room to spare until a deadline that no longer applied, and the
                // only way to get an answer was to send another message. Waking in bounded slices
                // re-runs the real preflight instead of trusting the old prediction: if the limit
                // still holds the turn simply defers again, and if it has lifted the reply goes out
                // within a slice. The re-check also refreshes the limit notice for free.
                clock.delay(
                    (untilMs - clock.wallTimeMillis())
                        .coerceAtLeast(1L)
                        .coerceAtMost(MAX_DEFER_SLICE_MS),
                )
                if (paused.get()) return@launch
                val stillCurrent =
                    synchronized(batchLock) {
                        chatGenerations[queueKey] == deferredGeneration &&
                            queueKey !in pendingPickups
                    }
                if (stillCurrent) {
                    readyTurns.send(
                        queued.copy(
                            generation = deferredGeneration,
                            enqueuedAtMs = clock.wallTimeMillis(),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching {
                    store.recordTurnFailure(
                        chatJid = chatJid,
                        turnId = queued.turnId,
                        proactive = false,
                        error = error,
                        timestampMs = clock.wallTimeMillis(),
                    )
                }
            }
        }
    }

    /**
     * Translates the activity log's stage vocabulary into the four words a person needs.
     *
     * The log is deliberately fine-grained — `context_loading`, `actions_materializing` — because
     * it is read after something went wrong. The field on the screen is read while it happens, so
     * everything that is "she has not called the model yet" collapses into one word. A stage with
     * no mapping leaves the current one standing rather than inventing a transition.
     */
    private fun liveStageFor(stage: String): ChatStage? =
        when (stage) {
            "started", "memory_wait", "memory_wait_finished", "memory_wait_timeout",
            "context_loading", "reading_floor",
            -> ChatStage.READING

            "ai_dispatched", "model_completed", "actions_materializing", "actions_materialized",
            -> ChatStage.THINKING

            "typing" -> ChatStage.TYPING
            "media_send_started", "media_send_succeeded" -> ChatStage.SENDING
            else -> null
        }

    /** One chat's input waiting for the next shared pickup window. */
    private data class PendingPickup(
        val events: MutableList<IncomingEvent> = ArrayList(),
        var generation: Long = 0L,
        var enqueuedAtMs: Long = 0L,
        var priority: Int = PRIORITY_REPLY,
        var immediate: Boolean = false,
    )

    private data class QueuedTurn(
        val events: List<IncomingEvent>,
        val priority: Int,
        val enqueuedAtMs: Long,
        val generation: Long,
        val proactive: Boolean = false,
        val proactiveTrailingDirective: String? = null,
        /** Real buffered inbound work absorbed by an exact scheduled promise. */
        val reactiveEventsToComplete: List<IncomingEvent> = emptyList(),
        val completion: CompletableDeferred<ProactiveTurnOutcome>? = null,
        val turnId: String = "turn:${events.first().eventId}:${UUID.randomUUID()}",
    )

    /** Reactive queues are isolated by persona; legacy synthetic events fall back to the chat. */
    private fun IncomingEvent.queueKey(): String =
        personaKey?.takeIf(String::isNotBlank)?.let(::conversationKey) ?: chatJid

    enum class AcceptResult {
        DUPLICATE,
        IGNORED,
        BLOCKED,
        COMMAND,
        PERSISTED_WHILE_PAUSED,
        QUEUED,
    }

    companion object {
        private const val READY_CAPACITY = 128
        private const val PRIORITY_REPLY = 100

        /**
         * How long a turn waits for a running memory consolidation before answering anyway.
         *
         * Waiting is what keeps her from replying out of a memory that is one refresh out of date.
         * Consolidation is a foreground step for the chat it belongs to, not background work: no
         * answer goes out while it runs, and the model call that follows is made against the
         * rewritten memory and the shortened history pointer, never against the old pair.
         *
         * The bound is what keeps a hung consolidation from freezing the bot outright: past it the
         * turn proceeds with the previous memory, which is merely stale, not wrong. It covers a
         * failed write *and* the immediate second attempt the refresh service makes — giving up at
         * one call would end the wait exactly when the retry that is going to succeed starts.
         */
        private const val MEMORY_REFRESH_WAIT_MS = 240_000L

        /**
         * How often the self-session loop looks up. Coarse on purpose: it is deciding something
         * that happens every few hours, and a phone should not be woken to find that out.
         */
        private const val SELF_SESSION_TICK_MS = 15L * 60_000L

        /**
         * A chat she is already reading (an interrupted turn) is served before
         * chats that merely happened to share the same pickup window.
         */
        private const val PRIORITY_INTERRUPT = 200

        /**
         * A chat she has not left yet outranks chats that merely share the window,
         * but not an interrupt, which is the same chat one step more urgent.
         */
        private const val PRIORITY_OPEN_CHAT = 150

        /** A due exact promise wakes the phone and is fulfilled before the backlog it woke. */
        private const val PRIORITY_SCHEDULED = 300

        /** Bound on how far later messages may talk one window forward. */
        private const val MAX_PICKUP_REDUCTIONS = 5

        /**
         * The longest armed human delay the link is held awake for, a little above the
         * widest band the pickup ladder can draw (30 min away + a 60 s collect window).
         * Anything past it is a wait for the morning, not a conversation.
         */
        private const val MAX_COVERED_PICKUP_WAIT_MS = 45L * 60_000L
        private const val PRIORITY_PROACTIVE = -10
        private const val PROACTIVE_CONTENTION_RETRY_MS = 5L * 60_000L

        /** Local context for a turn the user triggered by hand from the proactivity screen. */
        private const val MANUAL_OUTREACH_REASON =
            "You have decided to write to this contact now. Keep it short and natural, and do " +
                "not mention that anything prompted you. Make a strong, genuine effort to send a " +
                "visible message in this confirmed turn. [no reply] remains available only if a " +
                "message would truly be incoherent or unsafe after considering the actual chat."

        /** A dictated opener is still hers to phrase, so the cap is a sentence or two, not an essay. */
        private const val MAX_OUTREACH_NOTE_CHARACTERS = 400

        /**
         * The reason line for a hand-triggered outreach turn, with what the operator typed folded in.
         *
         * The note is framed as her own thought rather than as an order she received, for the same
         * reason the rest of the prompt is: a persona that is told what to send starts sounding
         * like someone relaying a message. What she wants to say is hers; the wording still is too.
         */
        internal fun manualOutreachReason(note: String?): String {
            val wish = note?.trim()?.take(MAX_OUTREACH_NOTE_CHARACTERS).orEmpty()
            if (wish.isEmpty()) return MANUAL_OUTREACH_REASON
            return "$MANUAL_OUTREACH_REASON In your mind you already know what you want to say " +
                "to them: $wish. Say exactly that, in your own words and in your own voice, as " +
                "a message from you. Do not answer with [no reply] here — you want to send this."
        }
        /**
         * How long a bubble that is already part of a running turn may wait inline for the
         * outbound policy.
         *
         * Generous enough for the pacing gap between two messages of one reply, far short of an
         * hourly or daily cap: those unwind the turn instead, because the visible-turn worker is
         * global and everything else queues behind whatever sleeps in it.
         */
        private const val MAX_INLINE_DEFER_MS = 10_000L

        /**
         * Longest a policy-deferred turn sleeps before re-checking the caps it was deferred by.
         *
         * A minute is short enough that lifting a limit by hand feels immediate and long enough
         * that a turn held by a daily cap costs a preflight read a minute rather than a busy loop.
         */
        private const val MAX_DEFER_SLICE_MS = 60_000L
        private const val MAX_BUBBLES = 8
        private const val MAX_COMPLETED_REACTIVE_EVENT_IDS = 4_096
        private const val MAX_EARLY_READ_MESSAGE_IDS = 1_024
        /** Paid-loop safety bound; every confirmed medium still precedes its continuation. */
        private const val MAX_VISIBLE_ASSISTANT_FOLLOW_UPS = 4

        /**
         * Voice-note listening realism, same numbers as the reference build:
         * a beat between the read receipt and the first played receipt, a floor
         * so even a two-second memo costs attention, and a ceiling so a very long
         * clip cannot stall the queue.
         */
        private const val LISTEN_PRESS_PLAY_MS = 1_000L
        private const val LISTEN_MIN_MS = 1_500L
        private const val LISTEN_MAX_MS = 90_000L

        /** Clip with no reported length: assume a short-ish note. */
        private const val DEFAULT_VOICE_SECONDS = 6

        /**
         * How long the "recording audio…" state is held before a voice note goes
         * out, and how often it is refreshed (WhatsApp expires presence quickly).
         */
        private const val RECORDING_MAX_MS = 90_000L

        /**
         * How often a held chat presence ("typing…", "recording audio…") is re-sent.
         *
         * WhatsApp drops a chat presence roughly ten seconds after it was set, so a one-shot
         * `composing` is invisible for all but the shortest messages — but every refresh is a
         * stanza on a link that is judged by how much it talks. Sitting just under the expiry
         * keeps the indicator continuous at the lowest rate that does, and it stays above
         * [de.totec.doppel.integration.BridgeWhatsAppActions] duplicate-suppression window,
         * so a genuine refresh is never swallowed while a repeated one still is.
         *
         * Typing and recording share the value: they cost the same and expire the same, and the
         * separate six/eight second pair was below the suppression window on both counts, which
         * made that guard dead code.
         */
        private const val PRESENCE_REFRESH_MS = 9_000L
        private const val COMPOSING_REFRESH_MS = PRESENCE_REFRESH_MS
        private const val RECORDING_REFRESH_MS = PRESENCE_REFRESH_MS

        /**
         * How often the live field grows while a bubble is being written.
         *
         * Fast enough to read as typing rather than as three jumps, slow enough that a long
         * message publishes tens of updates instead of hundreds. Nothing outside the open chat
         * screen consumes it, and the feed is not persisted, so the only cost is recomposition.
         */
        private const val DRAFT_TICK_MS = 110L
        private val EMPTY_TEXT_SHA256 = "".sha256()
    }
}

private class MemoryRefreshJob(
    val job: Job,
    /** Imported history must never fall through to stale memory after the normal wait timeout. */
    val mustComplete: Boolean,
)

/** Handle owned by the importer for the lifetime of one staged-memory transaction. */
internal class MemoryWriteHold internal constructor(
    private val previous: List<Job>,
    private val release: () -> Unit,
) : Closeable {
    /** Prevents two memory writers for the same conversation from overlapping. */
    suspend fun awaitReady() {
        previous.forEach { it.join() }
    }

    override fun close() {
        release()
    }
}

private data class PendingTextBubble(
    val text: String,
    val quoteMessageId: String?,
)

/**
 * The outbound policy refused a message the turn had already begun to send.
 *
 * Carried as a control-flow signal rather than a failure: the turn unwinds to a point where it
 * can either be rescheduled (nothing visible sent yet) or closed as a shortened reply (bubbles
 * already delivered). It used to be an `error(…)`, which recorded a policy decision as a crash
 * and skipped the outbound bookkeeping on the way out.
 */
private class MidTurnOutboundStop(
    /** Wall-clock time the policy would allow the send, or null when it is a hard block. */
    val untilMs: Long?,
    val hard: Boolean,
    val reason: String,
) : RuntimeException(reason)

private fun safeAdd(
    left: Long,
    right: Long,
): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Human-delay timers run for minutes, not milliseconds, so they get their own
 * coarse formatting: "1800.00 s" tells the user nothing, "30m" does.
 */
private fun formatWait(value: Long): String {
    val seconds = (value.coerceAtLeast(0L) + 500L) / 1_000L
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3_600L -> {
            val minutes = seconds / 60L
            val rest = seconds % 60L
            if (rest == 0L || minutes >= 10L) "${minutes}m" else "${minutes}m ${rest}s"
        }
        else -> {
            val hours = seconds / 3_600L
            val minutes = (seconds % 3_600L) / 60L
            if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
        }
    }
}

private fun formatDuration(value: Long): String =
    value.coerceAtLeast(0L).let {
        if (it < 1_000L) "$it ms" else "%.2f s".format(it / 1_000.0)
    }

/**
 * One plausible typing slip in one longer word, corrected a moment later by the
 * self-edit.
 *
 * Seeded from the send's action id rather than random, so a retried turn repeats
 * the same slip instead of inventing a new one. The word class is `\p{L}` and not
 * `[A-Za-z]`, because the persona writes German: with the ASCII class every word
 * carrying ä/ö/ü/ß was skipped, which is a large share of the candidates. A run of
 * letters bounded by non-letters is the word — `\b` is ASCII-only in Java regex
 * unless the Unicode flag is set, so it is deliberately not used here.
 */
private fun String.minorTypoOrNull(seed: String): String? {
    val words = Regex("\\p{L}{6,}").findAll(this).toList()
    if (words.isEmpty()) return null
    val word = words[pick("$seed:typo-word", words.size)]
    val length = word.value.length
    // Never the first or last letter: a wrong capital reads as a different kind of
    // mistake, and the final letter is where a swap has no partner.
    val at = word.range.first + 1 + pick("$seed:typo-at", length - 2)
    return when (pick("$seed:typo-kind", 3)) {
        // Always the same "drop the second-to-last letter" was itself a tell.
        0 -> removeRange(at, at + 1)
        1 -> substring(0, at) + this[at] + substring(at)
        else -> substring(0, at) + this[at + 1] + this[at] + substring(at + 2)
    }.takeIf { it != this }
}

/** Deterministic index in `0 until count` for [seed]. */
private fun pick(seed: String, count: Int): Int =
    if (count <= 1) 0 else (stableFraction(seed) * count).toInt().coerceIn(0, count - 1)

private fun stableFraction(seed: String): Double {
    val bytes =
        MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
    var value = 0L
    repeat(4) { index ->
        value = (value shl 8) or (bytes[index].toLong() and 0xffL)
    }
    return value.toDouble() / 4_294_967_296.0
}

/**
 * Strips the durable-history label from a stored line. The history is written for the model
 * ("User sent: …"), a WhatsApp quote bubble shows the words of the message alone.
 */
private fun String.withoutHistoryLabel(): String {
    // The same regex the outbound guard uses, rather than a list of exact prefixes: the labels
    // carry a parenthetical now — `You sent (quoting "…"): ` and `You sent (you wrote first, …): `
    // — and a fixed-prefix match silently let those through into the quote bubble.
    val lastLine = HistoryLabelGuard.stripLeadingLabel(substringAfterLast('\n').trim())
    HISTORY_LABEL_PREFIXES.forEach { prefix ->
        if (lastLine.startsWith(prefix, ignoreCase = true)) {
            return lastLine.removePrefix(prefix).trim()
        }
    }
    return lastLine
}

/** What [HistoryLabelGuard] does not cover, because no model has ever copied this one. */
private val HISTORY_LABEL_PREFIXES = listOf("User edited a message: ")

private fun IncomingEvent.withoutGroupTrigger(trigger: String): IncomingEvent {
    if (!isGroup || trigger.isEmpty()) return this
    val trimmed = text.trimStart()
    if (!trimmed.startsWith(trigger)) return this
    return copy(text = trimmed.removePrefix(trigger).trimStart())
}
