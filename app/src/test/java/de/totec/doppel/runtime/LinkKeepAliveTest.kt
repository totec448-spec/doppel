package de.totec.doppel.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkKeepAliveTest {
    private class RecordingLock(private val failOnAcquire: Boolean = false) : HeldLock {
        var acquired = 0
        var released = 0

        override fun acquire() {
            acquired++
            if (failOnAcquire) throw IllegalStateException("device refused the lock")
        }

        override fun release() {
            released++
        }
    }

    @Test
    fun `repeated acquire does not stack locks`() {
        val lock = RecordingLock()
        val keepAlive = LinkKeepAlive(onLockFailure = { _, _ -> }, locks = listOf(lock))

        keepAlive.acquire()
        keepAlive.acquire()
        keepAlive.acquire()

        assertEquals(1, lock.acquired)
        assertTrue(keepAlive.isHeld)
    }

    @Test
    fun `release without acquire never touches the OS lock`() {
        val lock = RecordingLock()
        val keepAlive = LinkKeepAlive(onLockFailure = { _, _ -> }, locks = listOf(lock))

        keepAlive.release()

        // Releasing a wake lock that is not held throws on Android; the flag has to
        // gate the call, not the platform.
        assertEquals(0, lock.released)
        assertFalse(keepAlive.isHeld)
    }

    @Test
    fun `acquire and release can alternate across service restarts`() {
        val lock = RecordingLock()
        val keepAlive = LinkKeepAlive(onLockFailure = { _, _ -> }, locks = listOf(lock))

        repeat(3) {
            keepAlive.acquire()
            keepAlive.release()
        }

        assertEquals(3, lock.acquired)
        assertEquals(3, lock.released)
        assertFalse(keepAlive.isHeld)
    }

    /**
     * Mirrors `BridgeForegroundService.runRuntimeLoop`'s terminal-failure branch: no
     * socket exists, so the locks must be down for the whole idle wait, and back up
     * once a Reconnect cancels it — `promoteToForeground` short-circuits on an
     * already-foreground service and would not re-acquire them.
     */
    @Test
    fun `terminal failure parks without locks and restores them on cancellation`() = runBlocking {
        val lock = RecordingLock()
        val keepAlive = LinkKeepAlive(onLockFailure = { _, _ -> }, locks = listOf(lock))
        keepAlive.acquire()

        val parked = CompletableDeferred<Unit>()
        val wait = launch {
            keepAlive.release()
            try {
                parked.complete(Unit)
                awaitCancellation()
            } finally {
                keepAlive.acquire()
            }
        }

        parked.await()
        assertFalse(keepAlive.isHeld)
        assertEquals(1, lock.released)

        wait.cancelAndJoin()
        assertTrue(keepAlive.isHeld)
        assertEquals(2, lock.acquired)
    }

    @Test
    fun `one refusing lock does not stop the others`() {
        val refusing = RecordingLock(failOnAcquire = true)
        val working = RecordingLock()
        val keepAlive = LinkKeepAlive(onLockFailure = { _, _ -> }, locks = listOf(refusing, working))

        keepAlive.acquire()
        keepAlive.release()

        assertEquals(1, working.acquired)
        assertEquals(1, working.released)
    }
}
