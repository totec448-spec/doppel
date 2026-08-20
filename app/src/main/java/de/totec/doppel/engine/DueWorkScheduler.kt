package de.totec.doppel.engine

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DueWorkKind {
    LIVE_REPLY,
    MEDIA,
    PROACTIVE,
    MEMORY,
    DELIVERY_CHECK,
    PRUNE,
}

data class DueWork(
    val id: String,
    val kind: DueWorkKind,
    val dueAtMs: Long,
)

interface DueWorkStore {
    suspend fun nextDue(): DueWork?

    /** Claim must be atomic and return false when another run already owns it. */
    suspend fun claim(id: String, nowMs: Long): Boolean

    suspend fun finish(id: String, result: DueWorkResult)
}

fun interface DueWorkRunner {
    suspend fun run(work: DueWork): DueWorkResult
}

/**
 * Result committed while the lease is still held.
 *
 * [outboundSent] is deliberately separate from [success]: a valid proactive
 * check can decide to stay silent. [disable] removes the item from the timer
 * without deleting its history, so a later inbound event can enable it again.
 */
data class DueWorkResult(
    val success: Boolean,
    val nextDueAtMs: Long?,
    val outboundSent: Boolean = false,
    val disable: Boolean = false,
    /** Clears this exact explicit follow-up after a sent/silent terminal attempt. */
    val completedFollowUpId: String? = null,
    /** Keeps the explicit follow-up and moves only its retry clock. */
    val retryFollowUpAtMs: Long? = null,
    val retryFollowUpId: String? = null,
)

/**
 * Exactly one cancellable timer is armed for the nearest persisted deadline.
 * Changes call [reschedule]; idle time performs no database scan or wakeup.
 *
 * A reschedule replaces only a waiting timer. Once work owns a durable lease,
 * it is allowed to finish; callers must invalidate stale work in their
 * persistence layer. This keeps unrelated inbound events from cancelling an
 * already-running visible turn.
 */
class DueWorkScheduler(
    parentScope: CoroutineScope,
    private val store: DueWorkStore,
    private val runner: DueWorkRunner,
    private val clock: EngineClock = SystemEngineClock,
) : Closeable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope =
        CoroutineScope(parentScope.coroutineContext + job + CoroutineName("due-work-scheduler"))
    private val mutex = Mutex()
    private val rescheduleDirty = AtomicBoolean(false)
    private val rescheduleWorkerActive = AtomicBoolean(false)
    private var timer: Job? = null
    private var runnerJob: Job? = null

    fun reschedule() {
        // One inbound batch can update several persisted schedules. Collapse those signals into
        // one worker; if another update lands while it is arming, the dirty flag causes exactly
        // one follow-up pass instead of launching another competing coroutine.
        rescheduleDirty.set(true)
        if (!rescheduleWorkerActive.compareAndSet(false, true)) return
        scope.launch { drainReschedules() }
    }

    private suspend fun drainReschedules() {
        while (true) {
            rescheduleDirty.set(false)
            mutex.withLock {
                if (runnerJob?.isActive != true) {
                    armLocked()
                }
            }
            if (!rescheduleDirty.get()) {
                rescheduleWorkerActive.set(false)
                // Close the small hand-off race: a caller may have marked dirty after the first
                // check but before active became false. Either this worker reclaims ownership or
                // that caller already did and launched its own worker.
                if (!rescheduleDirty.get() ||
                    !rescheduleWorkerActive.compareAndSet(false, true)
                ) {
                    return
                }
            }
        }
    }

    private fun armLocked() {
        timer?.cancel()
        timer =
            scope.launch(CoroutineName("next-due-work")) {
                val thisTimer = currentCoroutineContext()[Job]
                val work = store.nextDue()
                if (work == null) {
                    mutex.withLock {
                        if (timer === thisTimer) timer = null
                    }
                    return@launch
                }
                clock.delay((work.dueAtMs - clock.wallTimeMillis()).coerceAtLeast(0L))

                mutex.withLock {
                    if (timer !== thisTimer) return@withLock
                    if (!store.claim(work.id, clock.wallTimeMillis())) {
                        timer = null
                        armLocked()
                        return@withLock
                    }
                    timer = null
                    runnerJob =
                        scope.launch(CoroutineName("due-work-${work.kind.name.lowercase()}")) {
                            executeClaimed(work)
                        }
                }
            }
    }

    private suspend fun executeClaimed(work: DueWork) {
        var result =
            DueWorkResult(
                success = false,
                nextDueAtMs = null,
            )
        try {
            result = runner.run(work)
        } catch (_: Throwable) {
            // Failure is represented by [result] and persisted below. A
            // SupervisorJob does not itself consume launch exceptions, so
            // rethrowing here could still reach Android's uncaught handler.
        } finally {
            // Releasing an expiring lease must not depend on the parent still
            // being active. Process death is still safe because the lease has a
            // finite deadline in persistent storage.
            try {
                withContext(NonCancellable) {
                    store.finish(work.id, result)
                }
            } finally {
                mutex.withLock {
                    runnerJob = null
                    armLocked()
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
