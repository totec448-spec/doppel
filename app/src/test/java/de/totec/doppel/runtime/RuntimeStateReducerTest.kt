package de.totec.doppel.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeStateReducerTest {
    @Test
    fun successfulLifecycleResetsRetryMetadata() {
        var state = RuntimeState()
        state = RuntimeStateReducer.reduce(state, RuntimeStateEvent.StartRequested)
        assertEquals(RuntimePhase.STARTING, state.phase)

        state = RuntimeStateReducer.reduce(state, RuntimeStateEvent.WaitingForNetwork)
        assertEquals(RuntimePhase.WAITING_FOR_NETWORK, state.phase)

        state = RuntimeStateReducer.reduce(
            state,
            RuntimeStateEvent.RetryScheduled(
                attempt = 4,
                delayMs = 30_000L,
                detail = "offline",
            ),
        )
        assertEquals(RuntimePhase.BACKING_OFF, state.phase)
        assertEquals(4, state.reconnectAttempt)

        state = RuntimeStateReducer.reduce(
            state,
            RuntimeStateEvent.Online("connected"),
        )
        assertEquals(RuntimePhase.ONLINE, state.phase)
        assertEquals(0, state.reconnectAttempt)
        assertEquals(0L, state.retryDelayMs)
    }

    @Test
    fun retryInputIsClampedAndNotificationDetailIsBounded() {
        val state = RuntimeStateReducer.reduce(
            RuntimeState(),
            RuntimeStateEvent.RetryScheduled(
                attempt = -7,
                delayMs = -100L,
                detail = "first line\nsecret second line",
            ),
        )

        assertEquals(RuntimePhase.BACKING_OFF, state.phase)
        assertEquals(0, state.reconnectAttempt)
        assertEquals(0L, state.retryDelayMs)
        assertEquals("first line", state.detail)
    }

    @Test
    fun stoppedAlwaysReturnsCleanInitialState() {
        val dirty = RuntimeState(
            phase = RuntimePhase.ERROR,
            detail = "failure",
            reconnectAttempt = 8,
            retryDelayMs = 99_000L,
        )

        val stopped = RuntimeStateReducer.reduce(dirty, RuntimeStateEvent.Stopped)
        assertEquals(RuntimePhase.STOPPED, stopped.phase)
        assertEquals(0, stopped.reconnectAttempt)
        assertEquals(0L, stopped.retryDelayMs)
        assertNull(stopped.detail)
    }
}
