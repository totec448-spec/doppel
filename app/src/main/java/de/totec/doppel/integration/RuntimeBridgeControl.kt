package de.totec.doppel.integration

import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.domain.BridgeStatus
import de.totec.doppel.transport.BridgeTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Process-local control seam used by the setup UI. The socket remains owned by
 * [de.totec.doppel.runtime.NativeRuntimeHost]; this object only exposes
 * bounded, user-triggered operations and never starts a retry or polling loop.
 */
object RuntimeBridgeControl {
    private val client = AtomicReference<BridgeTransport?>(null)
    private val durableActions = AtomicReference<BridgeActionExecutor?>(null)
    private val approvedImageSender = AtomicReference<ApprovedImageSender?>(null)
    private val manualTextSender = AtomicReference<ManualTextSender?>(null)
    private val outreachTrigger = AtomicReference<OutreachTrigger?>(null)
    private val chatSettingsChangeHandler = AtomicReference<ChatSettingsChangeHandler?>(null)
    private val importedMemorySessions = AtomicReference<ImportedMemorySessionFactory?>(null)
    private val memoryMaintenance = AtomicReference<MemoryMaintenance?>(null)
    private val onlineSession = AtomicReference<OnlineSessionTrigger?>(null)
    private val linkPowerMutex = Mutex()
    private val _status = MutableStateFlow(BridgeStatus())
    private val _pairingCode = MutableStateFlow<String?>(null)
    private val activeTransport = MutableStateFlow<BridgeTransport?>(null)
    private val pairingMutex = Mutex()
    private val safetyRefreshMutex = Mutex()
    private val safetyCache = AtomicReference<CachedSafety?>(null)
    private val blockMutationMutex = Mutex()
    private val blockIntents = ConcurrentHashMap<String, BlockIntent>()
    private val blockGeneration = AtomicLong(0L)
    private val profileMutationMutex = Mutex()
    private val lastPushName = AtomicReference<String?>(null)

    val status: StateFlow<BridgeStatus> = _status.asStateFlow()
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    internal fun attach(
        value: BridgeTransport,
        actions: BridgeActionExecutor,
    ) {
        client.set(value)
        durableActions.set(actions)
        safetyCache.set(null)
        blockIntents.clear()
        lastPushName.set(null)
        _pairingCode.value = null
        activeTransport.value = value
    }

    internal fun publishStatus(owner: BridgeTransport, value: BridgeStatus) {
        // A host that exceeded its cancellation deadline may still unwind after a replacement has
        // attached. It must not overwrite the replacement's status or pairing state.
        if (client.get() !== owner) return
        _pairingCode.value = pairingCodeForStatus(value, _pairingCode.value)
        _status.value = value.copy(pairingCode = _pairingCode.value)
    }

    internal fun detach(
        value: BridgeTransport,
        actions: BridgeActionExecutor,
    ) {
        val detachedCurrent = client.compareAndSet(value, null)
        if (detachedCurrent) safetyCache.set(null)
        durableActions.compareAndSet(actions, null)
        if (detachedCurrent) {
            _pairingCode.value = null
            activeTransport.compareAndSet(value, null)
        }
    }

    internal fun attachApprovedImageSender(value: ApprovedImageSender) {
        approvedImageSender.set(value)
    }

    internal fun detachApprovedImageSender(value: ApprovedImageSender) {
        approvedImageSender.compareAndSet(value, null)
    }

    internal fun attachManualTextSender(value: ManualTextSender) {
        manualTextSender.set(value)
    }

    internal fun detachManualTextSender(value: ManualTextSender) {
        manualTextSender.compareAndSet(value, null)
    }

    internal fun attachOutreachTrigger(value: OutreachTrigger) {
        outreachTrigger.set(value)
    }

    internal fun detachOutreachTrigger(value: OutreachTrigger) {
        outreachTrigger.compareAndSet(value, null)
    }

    internal fun attachChatSettingsChangeHandler(value: ChatSettingsChangeHandler) {
        chatSettingsChangeHandler.set(value)
    }

    internal fun detachChatSettingsChangeHandler(value: ChatSettingsChangeHandler) {
        chatSettingsChangeHandler.compareAndSet(value, null)
    }

    internal fun attachImportedMemorySessions(value: ImportedMemorySessionFactory) {
        importedMemorySessions.set(value)
    }

    internal fun detachImportedMemorySessions(value: ImportedMemorySessionFactory) {
        importedMemorySessions.compareAndSet(value, null)
    }

    fun beginImportedMemory(chatJid: String, personaKey: String): ImportedMemorySession {
        val sessions = importedMemorySessions.get() ?: error("Bot is not running")
        return sessions.open(chatJid, personaKey)
    }

    internal fun attachOnlineSession(value: OnlineSessionTrigger) {
        onlineSession.set(value)
    }

    internal fun detachOnlineSession(value: OnlineSessionTrigger) {
        onlineSession.compareAndSet(value, null)
    }

    /**
     * True whenever WhatsApp itself is connected, as the native runtime reports it.
     *
     * This is the link power model's feedback signal, and it is deliberately the socket's
     * own account of itself rather than anyone's record of what they last commanded. The
     * model does not own the transport: the native runtime connects on its own the moment
     * it has a paired device ([de.totec.doppel.runtime.NativeRuntimeHost] starts it,
     * `server.go` launches `connectWhatsApp`), whatsmeow reconnects by itself, and a
     * session restart builds a fresh client. Every one of those raises the socket without
     * asking, and a controller that only remembers its own intent then believes a sleep it
     * issued is still in force while the bot is fully online underneath it.
     *
     * That is precisely how the first dozes were a lie: a sleep issued before a client
     * existed reached nothing at all ([sleepLink] is a no-op then), the state was recorded
     * as asleep anyway, and the connect that landed a moment later stood for hours while
     * the status line promised the bot was away until half past twelve.
     */
    val linkConnected: Flow<Boolean> =
        _status
            .map { it.connection == BridgeConnectionState.CONNECTED }
            .distinctUntilChanged()

    /**
     * Take the WhatsApp link down without giving up the pairing.
     *
     * The native side disconnects the socket and leaves the device store, the Signal
     * sessions and the app-state keys exactly where they are, so [wakeLink] is a
     * reconnect and not a relink. Serialized against [wakeLink] because a sleep and a
     * wake crossing in flight would decide the link's state by whichever answer came
     * back last.
     */
    suspend fun sleepLink() = linkPowerMutex.withLock {
        val active = client.get() ?: return@withLock
        active.action("link_sleep")
        Unit
    }

    /**
     * Bring the socket back after [sleepLink].
     *
     * Pairing deliberately causes WhatsApp to restart the new companion stream. A link-wake action
     * can therefore lose its action response while the same client is already reconnecting. In
     * that one ambiguous window, an observed CONNECTED state is stronger evidence than the lost
     * action response and turns the wake into success instead of surfacing "operation abandoned".
     */
    suspend fun wakeLink() = linkPowerMutex.withLock {
        val active = client.get() ?: return@withLock
        try {
            active.action("link_wake")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val recovered =
                _status.value.connection == BridgeConnectionState.CONNECTED ||
                    withTimeoutOrNull(LINK_WAKE_RECOVERY_MS) {
                        _status.filter { it.connection == BridgeConnectionState.CONNECTED }.first()
                    } != null
            if (!recovered) throw error
        }
        Unit
    }

    /**
     * Run one self-initiated online window now — the same 25–35 s look the bot takes
     * on its own, not a new kind of presence. Used when the operator taps a sleeping
     * status line, so a manual wake looks like her picking the phone up.
     */
    suspend fun showOnlineNow() {
        onlineSession.get()?.showOnlineNow()
    }

    internal fun attachMemoryMaintenance(value: MemoryMaintenance) {
        memoryMaintenance.set(value)
    }

    internal fun detachMemoryMaintenance(value: MemoryMaintenance) {
        memoryMaintenance.compareAndSet(value, null)
    }

    /** Writes this conversation's chat memory now, on the cadence path but without its threshold. */
    suspend fun writeChatMemoryNow(chatJid: String, personaKey: String): MemoryWriteResult {
        val maintenance = memoryMaintenance.get() ?: error("Bot is not running")
        return maintenance.writeChatMemory(chatJid, personaKey)
    }

    /** Rebuilds one persona's cross-chat memory now, past the every-third-write cadence. */
    suspend fun writeGlobalMemoryNow(personaKey: String): MemoryWriteResult {
        val maintenance = memoryMaintenance.get() ?: error("Bot is not running")
        return maintenance.writeGlobalMemory(personaKey)
    }

    /** Applies a per-chat timing change to a batch that may already be waiting. */
    suspend fun chatSettingsChanged(chatJid: String) {
        chatSettingsChangeHandler.get()?.changed(chatJid)
    }

    /**
     * Runs one generated outreach turn for [chatJid] now. The engine still applies the access,
     * block and safety gates, so a refusal here is a real refusal and not a scheduling delay.
     */
    suspend fun writeContactNow(
        chatJid: String,
        note: String? = null,
    ): OutreachResult {
        val trigger = outreachTrigger.get() ?: error("Bot is not running")
        return trigger.write(chatJid, note)
    }

    suspend fun requestPairingCode(phoneNumber: String): PairingCodeResult = pairingMutex.withLock {
        val normalized = phoneNumber.filter(Char::isDigit)
        require(normalized.length in 6..15) { "Invalid phone number" }
        // Attaching the process is not enough: the bridge rejects actions until authentication and
        // durable event replay have emitted Ready. Waiting here makes every pairing caller share
        // the same one-tap contract instead of teaching individual screens to guess bridge state.
        val active = awaitActionReady()
        val payload = JSONObject().put("phoneNumber", normalized)
        // Native pairing may legitimately wait for WhatsApp for three minutes. The generic bridge
        // action timeout is only 30 seconds and used to abandon a still-live native attempt.
        val result = active.action("pair", payload, timeoutMs = PAIRING_TIMEOUT_MS).result
            ?: error("Bridge returned no pairing code")
        val code = result.optString("code").trim()
        require(code.isNotEmpty()) { "Bridge returned no pairing code" }
        val expiresAtMs =
            result.optLong("expiresAtMs", 0L)
                .takeIf { it > 0L }
        _pairingCode.value = code
        _status.value = _status.value.copy(pairingCode = code)
        PairingCodeResult(code, expiresAtMs)
    }

    /**
     * Explicitly unlinks the current companion and leaves the native runtime ready to pair a new
     * number. Sleep and Low Battery never call this; they only disconnect the socket and retain the
     * linked-device identity.
     */
    suspend fun disconnectWhatsApp() = pairingMutex.withLock {
        linkPowerMutex.withLock {
            val active = client.get() ?: error("Bridge is not connected")
            active.action(
                "logout",
                JSONObject().put("confirm", true),
                timeoutMs = LOGOUT_TIMEOUT_MS,
            )
            _pairingCode.value = null
            _status.value =
                BridgeStatus(
                    connection = BridgeConnectionState.PAIRING,
                    detail = "WhatsApp is ready for a new phone number",
                )
            safetyCache.set(null)
            blockIntents.clear()
            lastPushName.set(null)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitActionReady(timeoutMs: Long = BRIDGE_READY_TIMEOUT_MS): BridgeTransport {
        return checkNotNull(
            withTimeoutOrNull(timeoutMs) {
                activeTransport
                    .filterNotNull()
                    .flatMapLatest { transport ->
                        transport.actionReady.filter { it }.map { transport }
                    }
                    .first { transport -> client.get() === transport }
            },
        ) { "The local WhatsApp bridge did not become ready in time" }
    }

    suspend fun refreshSafety(): JSONObject {
        return safetyRefreshMutex.withLock {
            val active = client.get() ?: error("Bridge is not connected")
            val now = System.currentTimeMillis()
            safetyCache
                .get()
                ?.takeIf { it.client === active && now - it.loadedAtMs < SAFETY_CACHE_MS }
                ?.let { return@withLock JSONObject(it.resultJson) }
            val result =
                active.action("safety_refresh").result
                    ?: error("Bridge returned no safety status")
            val serialized = result.toString()
            safetyCache.set(CachedSafety(active, now, serialized))
            JSONObject(serialized)
        }
    }

    suspend fun setPushName(name: String) {
        val normalized = name.trim().takeCodePoints(25)
        require(normalized.isNotEmpty()) { "Profile name must not be empty" }
        profileMutationMutex.withLock {
            if (lastPushName.get() == normalized || _status.value.accountName == normalized) return
            val active = client.get() ?: error("Bridge is not connected")
            active.action(
                action = "set_push_name",
                payload = JSONObject().put("name", normalized),
            )
            lastPushName.set(normalized)
            _status.value = _status.value.copy(accountName = normalized)
        }
    }

    suspend fun setBlocked(
        jid: String,
        blocked: Boolean,
    ) {
        val target = jid.trim().lowercase()
        require(target.isNotEmpty()) { "Contact must not be empty" }
        blockMutationMutex.withLock {
            val previous = blockIntents[target]
            if (previous?.blocked == blocked && previous.completed) return
            check(previous == null || previous.blocked == blocked || previous.completed) {
                "The previous block update for this contact is still pending"
            }
            val intent =
                previous?.takeIf { it.blocked == blocked }
                    ?: BlockIntent(
                        blocked = blocked,
                        key =
                            "block-state:$target:${blockGeneration.incrementAndGet()}:" +
                                if (blocked) "blocked" else "allowed",
                    ).also { blockIntents[target] = it }
            val actions = durableActions.get() ?: error("Bridge is not connected")
            try {
                actions.execute(
                    action = if (blocked) "block" else "unblock",
                    payload = JSONObject().put("chatId", target),
                    idempotencyKey = intent.key,
                    chatId = target,
                    priority = PRIORITY_ADMIN_BLOCK,
                )
                blockIntents[target] = intent.copy(completed = true)
            } catch (cancelled: CancellationException) {
                // The durable outbox may still finish. Retain the same key so a repeated tap joins
                // that operation instead of enqueueing another WhatsApp blocklist mutation.
                throw cancelled
            } catch (error: Throwable) {
                blockIntents.remove(target, intent)
                throw error
            }
        }
    }

    suspend fun sendApprovedImage(
        personaKey: String,
        assetId: String,
        targetChatId: String,
        caption: String?,
        requestId: String,
    ): String {
        val sender = approvedImageSender.get() ?: error("Bridge is not connected")
        return sender.send(
            personaKey = personaKey,
            assetId = assetId,
            targetChatId = targetChatId,
            caption = caption,
            requestId = requestId,
        )
    }

    suspend fun sendManualText(
        targetChatId: String,
        text: String,
        requestId: String,
    ): String {
        val sender = manualTextSender.get() ?: error("Bridge is not connected")
        return sender.send(targetChatId, text, requestId)
    }

    private data class CachedSafety(
        val client: BridgeTransport,
        val loadedAtMs: Long,
        val resultJson: String,
    )

    private data class BlockIntent(
        val blocked: Boolean,
        val key: String,
        val completed: Boolean = false,
    )

    private fun String.takeCodePoints(maximum: Int): String {
        val points = codePoints().limit(maximum.toLong()).toArray()
        return String(points, 0, points.size)
    }

    private const val PRIORITY_ADMIN_BLOCK = 100
    private const val SAFETY_CACHE_MS = 30_000L
    private const val PAIRING_TIMEOUT_MS = 185_000L
    private const val LOGOUT_TIMEOUT_MS = 45_000L
    private const val BRIDGE_READY_TIMEOUT_MS = 30_000L
    private const val LINK_WAKE_RECOVERY_MS = 5_000L
}

internal fun pairingCodeForStatus(
    status: BridgeStatus,
    current: String?,
): String? =
    if (status.connection == de.totec.doppel.domain.BridgeConnectionState.CONNECTED) {
        null
    } else {
        current
    }

fun interface ApprovedImageSender {
    suspend fun send(
        personaKey: String,
        assetId: String,
        targetChatId: String,
        caption: String?,
        requestId: String,
    ): String
}

fun interface ManualTextSender {
    suspend fun send(
        targetChatId: String,
        text: String,
        requestId: String,
    ): String
}

fun interface OutreachTrigger {
    /** [note] is what the operator wants said, or null to leave the opener entirely to the model. */
    suspend fun write(
        chatJid: String,
        note: String?,
    ): OutreachResult
}

fun interface ChatSettingsChangeHandler {
    suspend fun changed(chatJid: String)
}

/** The engine's own self-session, triggered from outside it. */
fun interface OnlineSessionTrigger {
    suspend fun showOnlineNow()
}

/**
 * Hand-triggered memory writes. Both run the same consolidation the bot runs on its own — the port
 * exists so the UI can ask for it now instead of waiting for the cadence, not to add a second way of
 * writing a memory.
 */
interface MemoryMaintenance {
    suspend fun writeChatMemory(chatJid: String, personaKey: String): MemoryWriteResult

    suspend fun writeGlobalMemory(personaKey: String): MemoryWriteResult
}

/**
 * [written] separates "nothing to consolidate" from "it ran": a refused write is not a failure, and
 * a message that claims a memory was created when none was is worse than no message. [detail] is
 * already user-facing.
 */
data class MemoryWriteResult(
    val written: Boolean,
    val detail: String,
)

fun interface ImportedMemorySessionFactory {
    fun open(chatJid: String, personaKey: String): ImportedMemorySession
}

interface ImportedMemorySession : AutoCloseable {
    /** Wait for an older cadence refresh before temporary import rows are written. */
    suspend fun awaitReady()

    suspend fun refresh(): String
}

/**
 * Why a hand-triggered outreach turn ended the way it did. [detail] is already user-facing;
 * WhatsApp's reach-out time lock in particular has to stay visible rather than look like the
 * model choosing to stay quiet.
 */
data class OutreachResult(
    val sent: Boolean,
    val detail: String,
)

data class PairingCodeResult(
    val code: String,
    val expiresAtMs: Long?,
)
