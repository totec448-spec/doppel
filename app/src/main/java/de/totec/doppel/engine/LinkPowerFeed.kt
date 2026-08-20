package de.totec.doppel.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The seam between "what the bot is doing" and "may the socket drop".
 *
 * The engine writes; the foreground service and the status line read. It lives on
 * the app graph for the same reason [ChatActivityFeed] does — the engine is rebuilt
 * on every reconnect and the thing watching it is not — and that matters more here
 * than anywhere else: the whole feature is about surviving a process that has been
 * asleep, so the record of when the link is next needed cannot live inside the
 * component most likely to have been replaced in the meantime.
 *
 * Deliberately dumb. Every decision is in [LinkPowerPolicy], which is pure; this
 * only holds the last thing the engine said.
 */
class LinkPowerFeed {
    private val mutableActivity = MutableStateFlow(LinkActivity())
    private val mutableState = MutableStateFlow(LinkState.AWAKE)
    private val mutableWakeAt = MutableStateFlow<Long?>(null)

    /**
     * Starts up, and stays up wherever nothing owns the link.
     *
     * The engine must never stall waiting for a controller that does not exist — in the
     * tests, and in every configuration where the power model is not running at all.
     */
    private val mutableCarrying = MutableStateFlow(true)

    /**
     * Not conflated and not a state: "wake" followed by "sleep" is two decisions and
     * collapsing them would silently drop one. Buffered so a tap is never lost while
     * the controller is mid-plan, and oldest-first because the newest tap is the one
     * the operator is still looking at.
     */
    private val mutableRequests =
        MutableSharedFlow<LinkPowerRequest>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** What the engine knows, as input to the policy. */
    val activity: StateFlow<LinkActivity> = mutableActivity.asStateFlow()

    /** What the runtime actually did about it, for the status line. */
    val state: StateFlow<LinkState> = mutableState.asStateFlow()

    /** When the link is due back, while it is down. */
    val wakeAtMs: StateFlow<Long?> = mutableWakeAt.asStateFlow()

    /** Out-of-band nudges: the status line was tapped, or the wake alarm fired. */
    val requests: SharedFlow<LinkPowerRequest> = mutableRequests.asSharedFlow()

    /**
     * Whether the socket is actually carrying traffic — as opposed to [state], which is
     * what the model has just *decided* and publishes immediately so the status line can
     * answer a tap.
     *
     * The two are deliberately separate. A decision is instant; bringing a websocket up
     * is not, and a turn that starts on the decision would send into a socket that is
     * still connecting.
     */
    val carrying: StateFlow<Boolean> = mutableCarrying.asStateFlow()

    /**
     * Park until the link can actually carry a message.
     *
     * This is the join between the two halves of the feature, and its absence was the
     * whole bug: the engine scheduled a reply, the clock ran it out, and the turn went
     * ahead against a link the power model had deliberately taken down — which either
     * dragged the socket back up hours early or sent into nothing. A message that lands
     * while the bot is down is answered when it next comes up, and the drawn human delay
     * runs inside that session rather than through the sleep.
     */
    suspend fun awaitCarrying() {
        carrying.first { it }
    }

    /**
     * Bring the link up now and keep it up for one listening window, quiet hours
     * included. The operator asked; nothing about the schedule outranks that.
     */
    fun requestWake() {
        mutableRequests.tryEmit(LinkPowerRequest.WAKE)
    }

    /** Drop a manual wake again — the status line was tapped a second time. */
    fun requestSleep() {
        mutableRequests.tryEmit(LinkPowerRequest.SLEEP)
    }

    /**
     * Re-evaluate, without wanting anything in particular. This is what the wake
     * alarm sends: coroutine timers do not advance while the CPU is suspended, so an
     * external poke is the only thing that can end a doze on time.
     */
    fun requestEvaluation() {
        mutableRequests.tryEmit(LinkPowerRequest.EVALUATE)
    }

    /**
     * Push the listening deadline out. Only ever forward: two chats answering in the
     * same window must not let the earlier one's shorter deadline cut the later one
     * off, and a late-arriving note from a cancelled turn must not shorten a live one.
     */
    fun extendListening(untilMs: Long) {
        mutableActivity.update {
            if (untilMs <= it.listenUntilMs) it else it.copy(listenUntilMs = untilMs)
        }
    }

    /** Drop the listening deadline — the bot went quiet on purpose (paused, stopped, blocked). */
    fun clearListening() {
        mutableActivity.update { it.copy(listenUntilMs = 0L) }
    }

    /** When the engine next intends to come online by itself. */
    fun noteNextSessionAt(atMs: Long) {
        mutableActivity.update { it.copy(nextSessionAtMs = atMs) }
    }

    /** Earliest durable model-planned follow-up; null removes the exact wake override. */
    fun noteScheduledFollowUpAt(atMs: Long?) {
        mutableActivity.update { it.copy(scheduledFollowUpAtMs = atMs ?: 0L) }
    }

    /**
     * A turn started or finished. Counted rather than flagged would be wrong here:
     * the engine runs at most one visible turn globally, and a count that leaked on
     * a cancelled turn would pin the link awake forever.
     */
    fun noteBusy(busy: Boolean) {
        mutableActivity.update { it.copy(busy = busy) }
    }

    /**
     * Forget everything: there is no schedule any more.
     *
     * This feed outlives the service on purpose, which is exactly why a stop has to say so. The
     * state is the last thing the *controller* published, and with the controller gone nothing
     * would ever contradict it — so the status line went on announcing "Sleeping · back 9:05"
     * about a bot the operator had switched off by hand.
     */
    fun reset() {
        mutableActivity.value = LinkActivity()
        mutableState.value = LinkState.AWAKE
        mutableWakeAt.value = null
        // Nothing owns the link any more, so nothing may be left parked on it.
        mutableCarrying.value = true
    }

    /** What the runtime has just decided, for the status line. Published before it is applied. */
    fun publish(
        state: LinkState,
        wakeAtMs: Long?,
    ) {
        mutableState.value = state
        mutableWakeAt.value = wakeAtMs
    }

    /** What the runtime has actually done to the socket. Published after it is applied. */
    fun publishCarrying(carrying: Boolean) {
        mutableCarrying.value = carrying
    }
}

/** What someone outside the schedule is asking the link to do. */
enum class LinkPowerRequest {
    WAKE,
    SLEEP,
    EVALUATE,
}
