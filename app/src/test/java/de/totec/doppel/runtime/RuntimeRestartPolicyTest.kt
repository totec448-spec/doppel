package de.totec.doppel.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6: "hängt dauerhaft bei verbindet erneut, ein manueller Neustart geht sofort".
 *
 * That combination is the signature of a blocked command actor, not of a slow network — a process
 * restart appears instant precisely because it rebuilds the actor. Two independent defects produced
 * it, and each is checked separately below: a stop that never completes takes the actor with it,
 * and a reconnect arriving during a stop is swallowed by the start guard.
 */
class RuntimeRestartPolicyTest {
    @Test
    fun `a healthy loop is joined and reported as stopped cleanly`() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }

        assertTrue(RuntimeRestartPolicy.cancelWithinDeadline(job, timeoutMs = 2_000L))
        assertTrue(job.isCompleted)
        stopped.await()
    }

    /**
     * The deadlock, reproduced: a host that ignores cancellation. Without the bound this call never
     * returns, so the assertion is that it returns at all — and says the job was abandoned.
     */
    @Test
    fun `a wedged loop is abandoned instead of holding the caller forever`() = runBlocking {
        val wedged = wedgedJob()

        val joined = withTimeout(3_000L) {
            RuntimeRestartPolicy.cancelWithinDeadline(wedged, timeoutMs = 150L)
        }

        assertFalse(joined)
        // Already cancelled, just not finished: it unregisters itself once its blocking call ends.
        assertTrue(wedged.isCancelled)
        assertFalse(wedged.isCompleted)
        wedged.cancel()
    }

    /**
     * The point of the bound is not the boolean but the actor: the next command must be processed
     * while the old loop is still winding down.
     */
    @Test
    fun `the next command is processed while a wedged loop is still winding down`() = runBlocking {
        val wedged = wedgedJob()
        val processed = mutableListOf<String>()

        withTimeout(3_000L) {
            RuntimeRestartPolicy.cancelWithinDeadline(wedged, timeoutMs = 150L)
            processed += "reconnect"
            RuntimeRestartPolicy.cancelWithinDeadline(null)
            processed += "start"
        }

        assertEquals(listOf("reconnect", "start"), processed)
        assertFalse(wedged.isCompleted)
        wedged.cancel()
    }

    @Test
    fun `having no loop at all counts as stopped`() = runBlocking {
        assertTrue(RuntimeRestartPolicy.cancelWithinDeadline(null))
        assertTrue(RuntimeRestartPolicy.cancelWithinDeadline(Job().apply { complete() }, 50L))
    }

    /** The swallowed reconnect: the guard that made the manual restart the only way out. */
    @Test
    fun `a loop never starts while an orderly stop is still in progress`() {
        assertFalse(
            RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = true,
                runRequested = true,
                orderlyStopInProgress = true,
                loopActive = false,
            ),
        )
        assertTrue(
            RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = true,
                runRequested = true,
                orderlyStopInProgress = false,
                loopActive = false,
            ),
        )
    }

    /**
     * Which is why the restart clears the flag first: with the same inputs the reconnect used to
     * fall through the guard and do nothing at all.
     */
    @Test
    fun `a reconnect during an orderly stop clears the flag and then starts`() {
        var orderlyStopInProgress = true

        assertTrue(RuntimeRestartPolicy.shouldRestart(desiredRunning = true, runRequested = true))
        orderlyStopInProgress = false

        assertTrue(
            RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = true,
                runRequested = true,
                orderlyStopInProgress = orderlyStopInProgress,
                loopActive = false,
            ),
        )
    }

    /** A reconnect must never resurrect a bot the user stopped, in either representation. */
    @Test
    fun `a stopped bot is not restarted by a queued reconnect`() {
        assertFalse(RuntimeRestartPolicy.shouldRestart(desiredRunning = false, runRequested = true))
        assertFalse(RuntimeRestartPolicy.shouldRestart(desiredRunning = true, runRequested = false))
        assertFalse(
            RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = false,
                runRequested = true,
                orderlyStopInProgress = false,
                loopActive = false,
            ),
        )
        assertFalse(
            RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = true,
                runRequested = false,
                orderlyStopInProgress = false,
                loopActive = false,
            ),
        )
    }

    /** Two sessions at once would duplicate every inbound message after a reconnect. */
    @Test
    fun `a running loop is never started a second time`() {
        assertFalse(
            RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = true,
                runRequested = true,
                orderlyStopInProgress = false,
                loopActive = true,
            ),
        )
    }

    /**
     * The whole sequence the operator performs: reconnect against a wedged host, then the fresh
     * session starts — with no second live loop and without waiting out the wedged one.
     */
    @Test
    fun `a reconnect against a wedged host yields exactly one fresh session`() = runBlocking {
        val wedged = wedgedJob()
        // The service state at the moment Reconnect is pressed: a stop is winding down and the old
        // loop is still registered.
        var orderlyStopInProgress = true
        var runtimeJob: Job? = wedged

        val mayStart = withTimeout(3_000L) {
            orderlyStopInProgress = false
            val abandoned = runtimeJob
            runtimeJob = null
            RuntimeRestartPolicy.cancelWithinDeadline(abandoned, timeoutMs = 150L)
            RuntimeRestartPolicy.shouldStartLoop(
                desiredRunning = true,
                runRequested = true,
                orderlyStopInProgress = orderlyStopInProgress,
                loopActive = runtimeJob?.isActive == true,
            )
        }

        assertTrue(mayStart)
        assertTrue(wedged.isCancelled)
        wedged.cancel()
    }

    @Test
    fun `the shipped deadline is bounded and not instantaneous`() {
        assertEquals(5_000L, RuntimeRestartPolicy.JOIN_TIMEOUT_MS)
    }

    /**
     * A job that is already stuck in an uncancellable call by the time it is handed back.
     *
     * [launch] only schedules. On a machine busy with the rest of the build the coroutine can
     * still be sitting in the dispatcher queue when the cancel arrives, and a coroutine that never
     * started cancels instantly — so the test would be measuring a wedge that never existed.
     * Waiting for the body to announce itself removes that race without changing what is tested.
     */
    private suspend fun CoroutineScope.wedgedJob(): Job {
        val wedging = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                wedging.complete(Unit)
                delay(WEDGE_MS)
            }
        }
        wedging.await()
        return job
    }

    private companion object {
        /**
         * Long enough to still be running when the 150 ms deadline expires, short enough that the
         * enclosing `runBlocking` — which waits for its children — does not stall the suite.
         */
        const val WEDGE_MS = 1_200L
    }
}
