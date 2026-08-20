package de.totec.doppel.transport

import de.totec.doppel.domain.BridgeStatus
import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

interface BridgeTransport : Closeable {
    val status: StateFlow<BridgeStatus>
    /** True only after authentication and durable inbound replay have both completed. */
    val actionReady: StateFlow<Boolean>
    val frames: Flow<BridgeFrame>
    val terminations: Flow<BridgeTermination>

    fun connect(
        endpoint: BridgeEndpoint,
        resumeAfter: Long,
    )

    fun acknowledge(sequence: Long): Boolean

    suspend fun action(
        action: String,
        payload: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000L,
        requestId: String = java.util.UUID.randomUUID().toString(),
    ): BridgeActionResult

    fun disconnect(reason: String = "stopped")
}
