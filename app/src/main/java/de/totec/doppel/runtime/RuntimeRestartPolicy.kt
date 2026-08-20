package de.totec.doppel.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The two decisions behind "stuck on reconnecting while a manual restart works instantly".
 *
 * Both live on the single command actor of [BridgeForegroundService], which processes Start, Stop
 * and Reconnect strictly in order. That makes them easy to get wrong in the same way: anything that
 * blocks the actor, or any guard that quietly swallows a command, leaves the UI showing whatever
 * state was published last — and a process restart "fixes" it only because it rebuilds the actor.
 *
 * Extracted so both can be exercised without an Android service behind them.
 */
internal object RuntimeRestartPolicy {
    /**
     * Upper bound for an orderly runtime shutdown. Long enough for a healthy host to close its
     * socket, short enough that a wedged one cannot hold the command actor hostage.
     */
    const val JOIN_TIMEOUT_MS = 5_000L

    /**
     * A user-triggered reconnect outranks an orderly stop that is still winding down. Leaving
     * `orderlyStopInProgress` set made the subsequent [shouldStartLoop] check fail, so Reconnect
     * did nothing at all and the last published state stayed on screen.
     */
    fun shouldStartLoop(
        desiredRunning: Boolean,
        runRequested: Boolean,
        orderlyStopInProgress: Boolean,
        loopActive: Boolean,
    ): Boolean = desiredRunning && runRequested && !orderlyStopInProgress && !loopActive

    /** A restart only makes sense while the bot is supposed to be running at all. */
    fun shouldRestart(
        desiredRunning: Boolean,
        runRequested: Boolean,
    ): Boolean = desiredRunning && runRequested

    /**
     * Cancels [job] and waits at most [timeoutMs] for it to finish.
     *
     * An unbounded `cancelAndJoin` here was a whole-service deadlock: a host stuck in a blocking
     * call never completed the join, the actor never read another command, and Start/Stop/Reconnect
     * queued behind it forever. A cancelled job that overruns the deadline is abandoned instead —
     * it is already cancelled and unregisters itself once its blocking call returns. The service
     * force-closes the process-global native transport before any replacement starts. Returns false
     * when that watchdog path was needed.
     */
    suspend fun cancelWithinDeadline(
        job: Job?,
        timeoutMs: Long = JOIN_TIMEOUT_MS,
    ): Boolean {
        if (job == null) return true
        return withTimeoutOrNull(timeoutMs) { job.cancelAndJoin() } != null
    }
}
