package de.totec.doppel.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-visible lifecycle of the one Android bot runtime.
 *
 * This is intentionally separate from bridge/domain state. The Android
 * foreground service owns process lifecycle; the injected host owns the bot
 * and transport implementation.
 */
enum class RuntimePhase {
    STOPPED,
    STARTING,
    WAITING_FOR_NETWORK,
    CONNECTING,
    ONLINE,
    BACKING_OFF,
    ATTENTION_REQUIRED,
    ENGINE_UNAVAILABLE,
    STOPPING,
    ERROR,
}

data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.STOPPED,
    val detail: String? = null,
    val reconnectAttempt: Int = 0,
    val retryDelayMs: Long = 0L,
    /**
     * When the current unbroken online stretch began, or null whenever the phase is not
     * [RuntimePhase.ONLINE].
     *
     * Kept here rather than in the UI because the link is usually established long before anyone
     * opens the app: a controller that started its own stopwatch on first collect would report the
     * age of the screen, not the age of the connection.
     */
    val onlineSince: Long? = null,
)

sealed interface RuntimeStateEvent {
    data object StartRequested : RuntimeStateEvent
    data object WaitingForNetwork : RuntimeStateEvent
    data class Connecting(val detail: String? = null) : RuntimeStateEvent
    data class Online(val detail: String? = null) : RuntimeStateEvent
    data class RetryScheduled(
        val attempt: Int,
        val delayMs: Long,
        val detail: String? = null,
    ) : RuntimeStateEvent

    data class AttentionRequired(val detail: String) : RuntimeStateEvent
    data class EngineUnavailable(val detail: String) : RuntimeStateEvent
    data class FatalError(val detail: String) : RuntimeStateEvent
    data object Stopping : RuntimeStateEvent
    data object Stopped : RuntimeStateEvent
}

/** Pure reducer so lifecycle behavior can be covered by host-side JVM tests. */
object RuntimeStateReducer {
    fun reduce(
        current: RuntimeState,
        event: RuntimeStateEvent,
        now: Long = System.currentTimeMillis(),
    ): RuntimeState =
        when (event) {
            RuntimeStateEvent.StartRequested -> RuntimeState(
                phase = RuntimePhase.STARTING,
            )

            RuntimeStateEvent.WaitingForNetwork -> current.copy(
                phase = RuntimePhase.WAITING_FOR_NETWORK,
                detail = "Waiting for a validated internet connection",
                retryDelayMs = 0L,
                onlineSince = null,
            )

            is RuntimeStateEvent.Connecting -> current.copy(
                phase = RuntimePhase.CONNECTING,
                detail = cleanDetail(event.detail) ?: "Connecting the bridge",
                retryDelayMs = 0L,
                onlineSince = null,
            )

            // A second Online event on an already-online link is a status refresh, not a new
            // connection, so the stretch keeps the instant it actually started.
            is RuntimeStateEvent.Online -> RuntimeState(
                phase = RuntimePhase.ONLINE,
                detail = cleanDetail(event.detail) ?: "The bot is connected",
                onlineSince = current.onlineSince.takeIf { current.phase == RuntimePhase.ONLINE } ?: now,
            )

            is RuntimeStateEvent.RetryScheduled -> current.copy(
                phase = RuntimePhase.BACKING_OFF,
                detail = cleanDetail(event.detail) ?: "Retrying the connection",
                reconnectAttempt = event.attempt.coerceAtLeast(0),
                retryDelayMs = event.delayMs.coerceAtLeast(0L),
                onlineSince = null,
            )

            is RuntimeStateEvent.AttentionRequired -> current.copy(
                phase = RuntimePhase.ATTENTION_REQUIRED,
                detail = cleanDetail(event.detail),
                retryDelayMs = 0L,
                onlineSince = null,
            )

            is RuntimeStateEvent.EngineUnavailable -> current.copy(
                phase = RuntimePhase.ENGINE_UNAVAILABLE,
                detail = cleanDetail(event.detail),
                retryDelayMs = 0L,
                onlineSince = null,
            )

            is RuntimeStateEvent.FatalError -> current.copy(
                phase = RuntimePhase.ERROR,
                detail = cleanDetail(event.detail),
                retryDelayMs = 0L,
                onlineSince = null,
            )

            RuntimeStateEvent.Stopping -> current.copy(
                phase = RuntimePhase.STOPPING,
                detail = "Stopping the bot cleanly",
                retryDelayMs = 0L,
                onlineSince = null,
            )

            RuntimeStateEvent.Stopped -> RuntimeState()
        }

    /**
     * Status text is displayed in a notification. Keep it single-line and
     * bounded so an injected bridge cannot accidentally expose a response body,
     * token, stack trace, or unbounded server-controlled text.
     */
    private fun cleanDetail(value: String?): String? =
        value
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(160)
            ?.takeIf(String::isNotEmpty)
}

/**
 * Process-local observation point for UI and notifications. Durable desired
 * state lives in [RuntimePreferences], not in this object.
 */
object RuntimeStateStore {
    private val lock = Any()
    private val mutableState = MutableStateFlow(RuntimeState())

    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    internal fun dispatch(event: RuntimeStateEvent): RuntimeState =
        synchronized(lock) {
            RuntimeStateReducer.reduce(mutableState.value, event).also {
                mutableState.value = it
            }
        }
}
