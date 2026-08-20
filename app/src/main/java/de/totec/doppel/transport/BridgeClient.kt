package de.totec.doppel.transport

import android.util.Log
import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.domain.BridgeStatus
import de.totec.doppel.security.privacySafeErrorType
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

data class BridgeEndpoint(
    val socketUrl: String,
    val token: String,
    val clientId: String,
) {
    init {
        require(isSecureOrLoopbackSocket(socketUrl)) {
            "Bridge URL must use wss://, except for the embedded Android loopback bridge"
        }
        require(BridgeControlToken.isValid(token)) { "Bridge token must encode at least 256 bits" }
        require(clientId.isNotBlank()) { "clientId is required" }
    }
}

private fun isSecureOrLoopbackSocket(value: String): Boolean =
    runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("wss", ignoreCase = true) ||
            (
                uri.scheme.equals("ws", ignoreCase = true) &&
                    uri.host in setOf("127.0.0.1", "localhost") &&
                    uri.userInfo == null &&
                    uri.fragment == null
            )
    }.getOrDefault(false)

data class BridgeActionResult(
    val result: JSONObject?,
)

sealed interface BridgeTermination {
    data class Closed(
        val code: Int,
        val protocolFailure: Boolean,
    ) : BridgeTermination

    data class Failed(
        val authenticationRejected: Boolean,
    ) : BridgeTermination
}

class BridgeActionException(
    val code: String,
    message: String,
) : Exception(message)

/**
 * One bounded, replay-aware WebSocket connection. It deliberately does not
 * reconnect by itself; the foreground runtime owns connectivity and backoff so
 * there can never be two competing retry loops.
 *
 * If the inbound decode queue is full, the socket is closed. The bridge journal
 * then replays from the last *persisted and acknowledged* sequence on reconnect;
 * silently dropping a message is never allowed.
 */
class BridgeClient(
    private val httpClient: OkHttpClient,
    parentScope: CoroutineScope? = null,
) : BridgeTransport {
    private val ownJob = SupervisorJob()
    private val scope =
        parentScope ?: CoroutineScope(ownJob + Dispatchers.Default)
    private val rawFrames = Channel<RawFrame>(capacity = INBOUND_CAPACITY)
    private val decodedFrames = Channel<BridgeFrame>(capacity = INBOUND_CAPACITY)
    private val socketTerminations = Channel<BridgeTermination>(capacity = 1)
    private val pendingActions = ConcurrentHashMap<String, CompletableDeferred<BridgeFrame.ActionResult>>()
    private val _status = MutableStateFlow(BridgeStatus())
    private val _actionReady = MutableStateFlow(false)
    private var processorJob: Job? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var endpoint: BridgeEndpoint? = null

    @Volatile
    private var resumeAfter: Long = 0

    @Volatile
    private var connectionGeneration: Long = 0

    @Volatile
    private var sessionReady = false

    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()
    override val actionReady: StateFlow<Boolean> = _actionReady.asStateFlow()
    override val frames = decodedFrames.receiveAsFlow()
    override val terminations = socketTerminations.receiveAsFlow()

    init {
        processorJob =
            scope.launch {
                for (rawFrame in rawFrames) {
                    if (rawFrame.generation != connectionGeneration) continue
                    val frame =
                        try {
                            BridgeProtocol.decode(rawFrame.value)
                        } catch (error: BridgeProtocolException) {
                            if (rawFrame.generation != connectionGeneration) continue
                            val reason = error.message ?: "Bridge frame could not be decoded"
                            val quarantined = BridgeProtocol.quarantine(rawFrame.value, reason)
                            if (quarantined != null) {
                                // Skipping one event keeps the link alive; closing the socket
                                // would only replay the same undecodable frame from the durable
                                // journal on the next connect, forever.
                                Log.w(
                                    TAG,
                                    "Bridge frame quarantined: type=${quarantined.frameType} " +
                                        "sequence=${quarantined.sequence} reason=$reason",
                                )
                                decodedFrames.send(quarantined)
                                continue
                            }
                            Log.e(TAG, "Bridge handshake frame rejected: ${reason.take(240)}")
                            _status.update {
                                it.copy(
                                    connection = BridgeConnectionState.ERROR,
                                    detail = error.message ?: "Protocol error",
                                )
                            }
                            webSocket?.close(CLOSE_PROTOCOL_ERROR, "invalid protocol frame")
                            continue
                        }
                    if (rawFrame.generation != connectionGeneration) continue

                    when (frame) {
                        is BridgeFrame.ActionResult -> {
                            pendingActions.remove(frame.requestId)?.complete(frame)
                        }

                        is BridgeFrame.Welcome -> {
                            // The companion may know a newer persisted ack than
                            // this process, or it may have reset its journal.
                            // All later local ack comparisons must use the
                            // server-negotiated cursor, including a lower reset
                            // value, or a restarted companion can be skipped
                            // forever.
                            resumeAfter = frame.resume.after
                            setSessionReady(false)
                            _status.update {
                                it.copy(
                                    // A connected account is not actionable
                                    // until the durable replay has completed.
                                    connection = BridgeConnectionState.AUTHENTICATING,
                                    detail =
                                        "Syncing bridge events",
                                    accountJid = frame.accountJid,
                                    accountName = frame.accountName,
                                )
                            }
                        }

                        is BridgeFrame.Ready -> {
                            setSessionReady(true)
                            _status.update {
                                it.copy(
                                    connection = frame.accountState,
                                    detail =
                                        if (frame.accountState == BridgeConnectionState.CONNECTED) {
                                            "Bridge and WhatsApp connected"
                                        } else {
                                            "Bridge ready; WhatsApp not connected yet"
                                        },
                                    accountJid = frame.accountJid ?: it.accountJid,
                                    accountName = frame.accountName ?: it.accountName,
                                    lastConnectedAt = System.currentTimeMillis(),
                                    reconnectAttempt = 0,
                                )
                            }
                        }

                        is BridgeFrame.Connection -> {
                            _status.update {
                                it.copy(
                                    connection = frame.state,
                                    detail = frame.detail,
                                    accountJid = frame.accountJid ?: it.accountJid,
                                    accountName = frame.accountName ?: it.accountName,
                                )
                            }
                        }

                        is BridgeFrame.PairingCode -> {
                            _status.update {
                                it.copy(
                                    connection = BridgeConnectionState.PAIRING,
                                    detail = "Pairing required",
                                    pairingCode = null,
                                )
                            }
                        }

                        else -> Unit
                    }
                    decodedFrames.send(frame)
                }
            }
    }

    @Synchronized
    override fun connect(
        endpoint: BridgeEndpoint,
        resumeAfter: Long,
    ) {
        connectionGeneration += 1
        val generation = connectionGeneration
        val replaced = webSocket
        webSocket = null
        replaced?.close(CLOSE_NORMAL, "replaced")
        failPending("connection_replaced", "The bridge connection was replaced")
        this.endpoint = endpoint
        this.resumeAfter = resumeAfter.coerceAtLeast(0)
        setSessionReady(false)
        _status.update {
            it.copy(
                connection = BridgeConnectionState.CONNECTING,
                detail = "Connecting the bridge",
                pairingCode = null,
            )
        }
        val request =
            Request.Builder()
                .url(endpoint.socketUrl)
                .header("Authorization", "Bearer ${endpoint.token}")
                .header("X-Bridge-Protocol", BridgeProtocol.VERSION.toString())
                .build()
        webSocket = httpClient.newWebSocket(request, Listener(endpoint, generation))
    }

    override fun acknowledge(sequence: Long): Boolean {
        if (sequence <= resumeAfter) return true
        if (resumeAfter == Long.MAX_VALUE || sequence != resumeAfter + 1) return false
        val sent = webSocket?.send(BridgeProtocol.acknowledge(sequence)) == true
        if (sent) resumeAfter = sequence
        return sent
    }

    override suspend fun action(
        action: String,
        payload: JSONObject,
        timeoutMs: Long,
        requestId: String,
    ): BridgeActionResult {
        if (!sessionReady) {
            throw BridgeActionException("session_not_ready", "The bridge is still syncing events")
        }
        val socket = webSocket ?: throw BridgeActionException("disconnected", "The bridge is disconnected")
        val result = CompletableDeferred<BridgeFrame.ActionResult>()
        check(pendingActions.putIfAbsent(requestId, result) == null) {
            "Duplicate action request id"
        }
        if (!socket.send(BridgeProtocol.action(requestId, action, payload))) {
            pendingActions.remove(requestId)
            throw BridgeActionException("send_failed", "The bridge action could not be sent")
        }

        val response =
            try {
                withTimeout(timeoutMs) { result.await() }
            } catch (_: TimeoutCancellationException) {
                // A single slow bridge action must never cancel the durable outbox worker.
                // The stable request ID makes a later retry idempotent at the native journal.
                throw BridgeActionException(
                    "action_timeout",
                    "The bridge action did not finish within ${timeoutMs}ms",
                )
            } finally {
                pendingActions.remove(requestId)
            }
        if (!response.ok) {
            throw BridgeActionException(
                response.errorCode ?: "bridge_action_failed",
                response.errorMessage ?: "The bridge action failed",
            )
        }
        return BridgeActionResult(response.result)
    }

    @Synchronized
    override fun disconnect(reason: String) {
        connectionGeneration += 1
        val closed = webSocket
        webSocket = null
        setSessionReady(false)
        closed?.close(CLOSE_NORMAL, reason.take(100))
        failPending("disconnected", "The bridge was disconnected")
        _status.update {
            it.copy(
                connection = BridgeConnectionState.DISCONNECTED,
                detail = "Disconnected",
                pairingCode = null,
            )
        }
    }

    override fun close() {
        disconnect("closed")
        rawFrames.close()
        decodedFrames.close()
        socketTerminations.close()
        processorJob?.cancel()
        ownJob.cancel()
    }

    private fun failPending(code: String, message: String) {
        pendingActions.entries.forEach { (id, deferred) ->
            if (pendingActions.remove(id, deferred)) {
                deferred.completeExceptionally(BridgeActionException(code, message))
            }
        }
    }

    private inner class Listener(
        private val endpoint: BridgeEndpoint,
        private val generation: Long,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (generation != connectionGeneration) {
                webSocket.close(CLOSE_NORMAL, "stale connection")
                return
            }
            _status.update {
                it.copy(
                    connection = BridgeConnectionState.AUTHENTICATING,
                    detail = "Authenticating with the bridge",
                )
            }
            Log.i(TAG, "Bridge WebSocket opened (${endpointKind(endpoint.socketUrl)})")
            if (!webSocket.send(BridgeProtocol.hello(endpoint.clientId, resumeAfter))) {
                webSocket.close(CLOSE_INTERNAL_ERROR, "hello failed")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (generation != connectionGeneration) return
            if (!rawFrames.trySend(RawFrame(generation, text)).isSuccess) {
                webSocket.close(CLOSE_OVERLOADED, "client queue full")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (generation != connectionGeneration) return
            webSocket.close(CLOSE_UNSUPPORTED_DATA, "binary frames unsupported")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (generation != connectionGeneration) return
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (generation != connectionGeneration) return
            this@BridgeClient.webSocket = null
            setSessionReady(false)
            failPending("disconnected", "The bridge connection was closed")
            _status.update {
                it.copy(
                    connection = BridgeConnectionState.DISCONNECTED,
                    detail = "Connection ended",
                )
            }
            socketTerminations.trySend(
                BridgeTermination.Closed(
                    code = code,
                    protocolFailure = code == CLOSE_PROTOCOL_ERROR,
                ),
            )
            Log.w(TAG, "Bridge WebSocket closed: code=$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (generation != connectionGeneration) return
            this@BridgeClient.webSocket = null
            setSessionReady(false)
            failPending("connection_failed", "The bridge connection failed")
            val authenticationRejected = response?.code in setOf(401, 403)
            _status.update {
                it.copy(
                    connection = BridgeConnectionState.ERROR,
                    detail = when (response?.code) {
                        401, 403 -> "Bridge token rejected"
                        else -> "Bridge is unreachable"
                    },
                )
            }
            socketTerminations.trySend(
                BridgeTermination.Failed(authenticationRejected),
            )
            Log.e(
                TAG,
                "Bridge WebSocket failed (${endpointKind(endpoint.socketUrl)}), " +
                    "http=${response?.code ?: 0} error=${privacySafeErrorType(t)}",
            )
        }
    }

    private fun setSessionReady(ready: Boolean) {
        sessionReady = ready
        _actionReady.value = ready
    }

    private data class RawFrame(
        val generation: Long,
        val value: String,
    )

    companion object {
        private const val TAG = "DoppelBridge"
        private const val INBOUND_CAPACITY = 256
        private const val CLOSE_NORMAL = 1000
        private const val CLOSE_PROTOCOL_ERROR = 1002
        private const val CLOSE_UNSUPPORTED_DATA = 1003
        private const val CLOSE_OVERLOADED = 1013
        private const val CLOSE_INTERNAL_ERROR = 1011
    }
}

private fun endpointKind(value: String): String =
    runCatching {
        val uri = java.net.URI(value)
        if (uri.host in setOf("127.0.0.1", "localhost")) "loopback" else uri.scheme.orEmpty()
    }.getOrDefault("invalid")
