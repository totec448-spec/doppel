package de.totec.doppel.integration

import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.BridgeOutboxRecord
import de.totec.doppel.data.db.BridgeOutboxState
import de.totec.doppel.transport.BridgeActionException
import de.totec.doppel.transport.BridgeActionResult
import de.totec.doppel.transport.BridgeTransport
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Narrow execution port used by user-visible transport actions.
 *
 * Receipts and presence intentionally bypass this port: they are transient
 * protocol state. Text, reactions, media and contact blocks must pass through
 * this durable port before the companion can observe them.
 */
interface BridgeActionExecutor {
    suspend fun execute(
        action: String,
        payload: JSONObject,
        idempotencyKey: String,
        chatId: String?,
        priority: Int,
    ): BridgeActionResult
}

/**
 * Persistent, signal-driven dispatcher over [BotRepository]'s bridge outbox.
 *
 * There is no interval loop. The worker wakes only for a new row, a connection
 * transition, or the exact next retry/expired-lease deadline. The companion
 * receives the same stable request ID for every retry, so its own durable
 * idempotency barrier closes the crash window after the external effect.
 */
class DurableBridgeOutboxDispatcher(
    parentScope: CoroutineScope,
    private val repository: BotRepository,
    private val bridge: BridgeTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val activityChanged: () -> Unit = {},
    /**
     * Called after a delivery that no caller was waiting for any more.
     *
     * The turn that asked for the send is what writes the message into the chat log. When it is
     * gone — cancelled, or lost with the process — the row still goes out on a recovery pass and
     * nobody records it. This is the only moment at which that is knowable from here.
     */
    private val onUnclaimedDelivery: suspend (BridgeOutboxRecord) -> Unit = {},
) : BridgeActionExecutor, Closeable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope =
        CoroutineScope(parentScope.coroutineContext + job + CoroutineName("bridge-outbox"))
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val online = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val waiters = ConcurrentHashMap<String, OutboxWaiter>()
    private val leaseOwner = "android-${UUID.randomUUID()}"
    private val worker = scope.launch { runWorker() }

    /**
     * Starts recovery only after WhatsApp itself is ready, not merely after the
     * control WebSocket welcome frame.
     */
    fun setOnline(value: Boolean) {
        online.set(value)
        wake.trySend(Unit)
    }

    override suspend fun execute(
        action: String,
        payload: JSONObject,
        idempotencyKey: String,
        chatId: String?,
        priority: Int,
    ): BridgeActionResult {
        require(action in DURABLE_ACTIONS) { "Action is not durable" }
        val dedupeKey = idempotencyKey.toBridgeRequestId()
        val payloadJson = payload.toString()
        val createdAt = now().coerceAtLeast(0L)
        val waiter =
            synchronized(lifecycleLock) {
                check(!closed.get()) { "Bridge outbox is closed" }
                checkNotNull(
                    waiters.compute(dedupeKey) { _, existing ->
                        (existing ?: OutboxWaiter()).also { it.callers.incrementAndGet() }
                    },
                )
            }
        try {
            val enqueue =
                repository.enqueueBridgeOperation(
                    BridgeOutboxRecord(
                        dedupeKey = dedupeKey,
                        operation = action,
                        chatId = chatId,
                        payloadJson = payloadJson,
                        priority = priority.coerceIn(-1_000, 1_000),
                        availableAt = createdAt,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                    ),
                )
            check(
                enqueue.record.operation == action &&
                    enqueue.record.payloadJson == payloadJson &&
                    enqueue.record.chatId == chatId
            ) {
                "Outbox idempotency key was reused with a different operation"
            }
            // Enqueue is already represented durably by the outbox row. Recording the same expected
            // transition in Activity created an extra SQLite transaction and a full UI-log refresh for
            // every bubble. Only the exceptional idempotency-reuse case merits a diagnostic row.
            if (!enqueue.enqueued) {
                recordOutboxActivity(
                    record = enqueue.record,
                    level = ActivityLevel.DEBUG,
                    action = "deduplicated",
                    summary = "Reused an already queued WhatsApp action",
                )
            }
            when (enqueue.record.state) {
                BridgeOutboxState.DEAD ->
                    throw BridgeActionException(
                        "outbox_dead",
                        "Bridge action was permanently rejected",
                    )

                BridgeOutboxState.COMPLETED ->
                    return BridgeActionResult(
                        result = enqueue.record.resultJson?.let(::JSONObject),
                    )

                BridgeOutboxState.PENDING,
                BridgeOutboxState.LEASED,
                -> Unit
            }
            wake.trySend(Unit)
            return waiter.result.await()
        } finally {
            if (waiter.callers.decrementAndGet() == 0 && !waiter.result.isCompleted) {
                waiters.remove(dedupeKey, waiter)
            }
        }
    }

    override fun close() {
        val failure = BridgeActionException("dispatcher_closed", "Bridge outbox was shut down")
        val pending =
            synchronized(lifecycleLock) {
                if (!closed.compareAndSet(false, true)) return
                online.set(false)
                wake.close()
                waiters.values.toList().also { waiters.clear() }
            }
        pending.forEach { it.result.completeExceptionally(failure) }
        scope.cancel()
    }

    private suspend fun runWorker() {
        try {
            while (scope.isActive) {
                try {
                    if (!online.get()) {
                        if (wake.receiveCatching().isClosed) return
                        continue
                    }

                    val claimed =
                        repository.claimBridgeOperations(
                            leaseOwner = leaseOwner,
                            now = now(),
                            leaseDurationMs = LEASE_DURATION_MS,
                            limit = CLAIM_LIMIT,
                        )
                    if (claimed.isNotEmpty()) {
                        for (record in claimed) {
                            if (!online.get()) {
                                repository.releaseBridgeOperations(leaseOwner, now())
                                break
                            }
                            process(record)
                        }
                        continue
                    }

                    val dueAt = repository.nextBridgeOperationDueAt()
                    if (dueAt == null) {
                        if (wake.receiveCatching().isClosed) return
                    } else {
                        val waitMs = (dueAt - now()).coerceAtLeast(1L)
                        withTimeoutOrNull(waitMs) { wake.receiveCatching() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A transient repository failure must not kill the only outbox worker. Keep
                    // existing leases intact and retry after a short, signal-interruptible pause.
                    withTimeoutOrNull(WORKER_FAILURE_RETRY_MS) { wake.receiveCatching() }
                }
            }
        } finally {
            // A process crash keeps the lease until its deadline. Reaching finally proves an
            // orderly cancellation, so the next host can resume immediately instead of waiting
            // up to the full media-action lease.
            runCatching { repository.releaseBridgeOperations(leaseOwner, now()) }
        }
    }

    /** Callers check [online] first; a row that reaches here keeps its lease until it settles. */
    private suspend fun process(record: BridgeOutboxRecord) {
        val attemptStartedAt = now()
        try {
            val result =
                bridge.action(
                    action = record.operation,
                    payload = JSONObject(record.payloadJson),
                    timeoutMs = bridgeActionTimeoutMs(record.operation),
                    requestId = record.dedupeKey,
                )
            val completedAt = now()
            val resultJson = result.result?.toString()
            check(
                repository.completeBridgeOperation(
                    databaseId = record.databaseId,
                    leaseOwner = leaseOwner,
                    resultJson = resultJson,
                    now = completedAt,
                ),
            ) { "Bridge outbox completion lease was lost" }
            val waiter = waiters.remove(record.dedupeKey)
            waiter?.result?.complete(result)
            // Completion is already durable in the outbox and the turn records the visible send.
            // A third success log row added no information but did add a DB write and UI refresh.
            if (waiter == null) {
                // Shielded on purpose. The row is delivered and COMPLETED; a failure while
                // repairing the chat log must not fall through to the retry path below, where it
                // would put the same message on the wire a second time.
                try {
                    onUnclaimedDelivery(
                        record.copy(
                            state = BridgeOutboxState.COMPLETED,
                            resultJson = resultJson,
                            updatedAt = completedAt,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Best effort: the start-up sweep finds the row again.
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val code = (error as? BridgeActionException)?.code ?: error.javaClass.simpleName
            val permanent = isPermanent(error)
            if (permanent || record.attemptCount >= MAX_ATTEMPTS) {
                repository.deadLetterBridgeOperation(
                    databaseId = record.databaseId,
                    leaseOwner = leaseOwner,
                    error = code.take(MAX_ERROR_CHARS),
                    now = now(),
                )
                waiters.remove(record.dedupeKey)?.result?.completeExceptionally(
                    if (error is BridgeActionException) {
                        error
                    } else {
                        BridgeActionException("outbox_failed", "Bridge action failed")
                    },
                )
                recordDeadLetter(record, code)
            } else {
                val retryAt = safeAdd(now(), retryDelay(record.attemptCount))
                repository.retryBridgeOperation(
                    databaseId = record.databaseId,
                    leaseOwner = leaseOwner,
                    availableAt = retryAt,
                    error = code.take(MAX_ERROR_CHARS),
                    now = now(),
                )
                recordOutboxActivity(
                    record = record,
                    level = ActivityLevel.WARN,
                    action = "retry_scheduled",
                    summary = "Retrying the WhatsApp action safely",
                    reasonCode = code,
                    elapsedMs = (now() - attemptStartedAt).coerceAtLeast(0L),
                    retryAt = retryAt,
                )
            }
        }
    }

    private class OutboxWaiter(
        val result: CompletableDeferred<BridgeActionResult> = CompletableDeferred(),
        val callers: AtomicInteger = AtomicInteger(0),
    )

    private fun retryDelay(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 18)
        val cap = min(MAX_RETRY_MS, BASE_RETRY_MS * (1L shl exponent))
        return if (cap <= 1L) 1L else random.nextLong(1L, cap + 1L)
    }

    private fun isPermanent(error: Throwable): Boolean {
        val code = (error as? BridgeActionException)?.code ?: return false
        return code in PERMANENT_ERROR_CODES ||
            code.startsWith("invalid_") ||
            code.startsWith("unsupported_")
    }

    private fun recordDeadLetter(record: BridgeOutboxRecord, code: String) {
        recordOutboxActivity(
            record = record,
            level = ActivityLevel.ERROR,
            action = "dead_letter",
            summary = "Bridge action stopped after safe retries",
            reasonCode = code,
        )
    }

    private fun recordOutboxActivity(
        record: BridgeOutboxRecord,
        level: ActivityLevel,
        action: String,
        summary: String,
        reasonCode: String? = null,
        elapsedMs: Long? = null,
        retryAt: Long? = null,
    ) {
        runCatching {
            repository.appendActivity(
                ActivityLogRecord(
                    occurredAt = now(),
                    level = level,
                    category = "bridge_outbox",
                    action = action,
                    chatId = record.chatId,
                    correlationId = record.dedupeKey,
                    summary = "$summary · ${record.operation}",
                    detailsJson =
                        JSONObject()
                            .put("operation", record.operation)
                            .put("attempt", record.attemptCount.coerceAtLeast(1))
                            .put("state", record.state.databaseValue)
                            .put("reasonCode", reasonCode?.take(MAX_ERROR_CHARS) ?: JSONObject.NULL)
                            .put("elapsedMs", elapsedMs ?: JSONObject.NULL)
                            .put("retryAt", retryAt ?: JSONObject.NULL)
                            .toString(),
                ),
            )
            activityChanged()
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        /** The longest deadline [bridgeActionTimeoutMs] hands out (`send_media`). */
        const val MAX_ACTION_MS = 195_000L

        /**
         * How many rows one claim takes. Small on purpose: the batch is worked sequentially, so
         * the lease below has to cover the *whole* batch, and a lease long enough for twenty
         * media uploads would also be the delay before a crashed process's rows become
         * reclaimable.
         */
        const val CLAIM_LIMIT = 2

        /**
         * The lease has to outlive the work it protects. At forty-five seconds it expired while
         * a `send_text` (105 s) or `send_media` (195 s) was still in flight, so the very same
         * dispatcher could reclaim a row it was already sending: a second attempt on the wire,
         * and — because the request ID is stable — a decent chance of coming back as
         * `idempotency_in_doubt`, which is treated as permanent and dead-letters the message.
         */
        const val LEASE_DURATION_MS = CLAIM_LIMIT * MAX_ACTION_MS + 60_000L
        const val MAX_ATTEMPTS = 8
        const val BASE_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 5L * 60L * 1_000L
        const val MAX_ERROR_CHARS = 256
        const val WORKER_FAILURE_RETRY_MS = 1_000L

        val DURABLE_ACTIONS =
            setOf(
                "send_text",
                "send_media",
                "send_reaction",
                "edit_message",
                "block",
                "unblock",
            )
        val PERMANENT_ERROR_CODES =
            setOf(
                "unknown_action",
                "idempotency_conflict",
                "idempotency_in_doubt",
                "confirmation_required",
                "media_not_found",
                "media_expired",
                "broadcast_rejected",
                "target_rejected",
            )
    }
}

internal fun String.toBridgeRequestId(): String {
    val safe = replace(Regex("[^A-Za-z0-9._:-]"), "_")
    if (safe.length in 8..160) return safe
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    return "native:${digest.take(48)}"
}

/**
 * The Android deadline deliberately sits beyond the native WhatsApp deadline.
 * Otherwise Android can abandon a request that the native client is still
 * executing and schedule an avoidable duplicate attempt. The request ID still
 * remains stable across genuine retries.
 */
internal fun bridgeActionTimeoutMs(action: String): Long =
    when (action) {
        "send_media" -> 195_000L
        "send_text", "send_reply", "send_reaction", "edit_message", "delete_message",
        "block", "unblock",
        -> 105_000L
        else -> 45_000L
    }
