package de.totec.doppel.integration

import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.domain.BridgeStatus
import de.totec.doppel.transport.BridgeActionResult
import de.totec.doppel.transport.BridgeActionException
import de.totec.doppel.transport.BridgeEndpoint
import de.totec.doppel.transport.BridgeFrame
import de.totec.doppel.transport.BridgeTermination
import de.totec.doppel.transport.BridgeTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBridgeControlTest {
    @Test
    fun connectedStatusClearsStalePairingCode() {
        assertNull(
            pairingCodeForStatus(
                BridgeStatus(connection = BridgeConnectionState.CONNECTED),
                "ABCD-EFGH",
            ),
        )
    }

    @Test
    fun nonConnectedStatusPreservesActivePairingCode() {
        assertEquals(
            "ABCD-EFGH",
            pairingCodeForStatus(
                BridgeStatus(connection = BridgeConnectionState.PAIRING),
                "ABCD-EFGH",
            ),
        )
    }

    @Test
    fun disconnectUsesExplicitLogoutAndImmediatelyProjectsFreshPairingState() = runBlocking {
        val bridge = RecordingBridgeTransport()
        val executor =
            object : BridgeActionExecutor {
                override suspend fun execute(
                    action: String,
                    payload: JSONObject,
                    idempotencyKey: String,
                    chatId: String?,
                    priority: Int,
                ) = BridgeActionResult(JSONObject())
            }
        RuntimeBridgeControl.attach(bridge, executor)
        try {
            RuntimeBridgeControl.publishStatus(
                bridge,
                BridgeStatus(
                    connection = BridgeConnectionState.CONNECTED,
                    accountJid = "49123456789@s.whatsapp.net",
                ),
            )

            RuntimeBridgeControl.disconnectWhatsApp()

            assertEquals("logout", bridge.action)
            assertTrue(bridge.payload?.getBoolean("confirm") == true)
            assertEquals(45_000L, bridge.timeoutMs)
            assertEquals(BridgeConnectionState.PAIRING, RuntimeBridgeControl.status.value.connection)
            assertNull(RuntimeBridgeControl.status.value.accountJid)
            assertFalse(RuntimeBridgeControl.status.value.detail.isNullOrBlank())
        } finally {
            RuntimeBridgeControl.detach(bridge, executor)
        }
    }

    @Test
    fun pairingWaitsForReadyAndSendsExactlyOneAction() = runBlocking {
        val bridge = RecordingBridgeTransport()
        val executor =
            object : BridgeActionExecutor {
                override suspend fun execute(
                    action: String,
                    payload: JSONObject,
                    idempotencyKey: String,
                    chatId: String?,
                    priority: Int,
                ) = BridgeActionResult(JSONObject())
            }
        RuntimeBridgeControl.attach(bridge, executor)
        try {
            val pending = async { RuntimeBridgeControl.requestPairingCode("12025550123") }
            delay(25)
            assertNull(bridge.action)

            bridge.markReady()

            assertEquals("ABCDEFGH", pending.await().code)
            assertEquals("pair", bridge.action)
            assertEquals(1, bridge.actionCount)
            assertEquals("12025550123", bridge.payload?.getString("phoneNumber"))
        } finally {
            RuntimeBridgeControl.detach(bridge, executor)
        }
    }

    @Test
    fun wakeTreatsConnectedStatusAsAuthoritativeAfterLostPairingResponse() = runBlocking {
        val bridge = RecordingBridgeTransport()
        val executor =
            object : BridgeActionExecutor {
                override suspend fun execute(
                    action: String,
                    payload: JSONObject,
                    idempotencyKey: String,
                    chatId: String?,
                    priority: Int,
                ) = BridgeActionResult(JSONObject())
            }
        RuntimeBridgeControl.attach(bridge, executor)
        try {
            RuntimeBridgeControl.publishStatus(
                bridge,
                BridgeStatus(connection = BridgeConnectionState.PAIRING),
            )
            bridge.actionFailure = BridgeActionException("action_abandoned", "response was lost")

            val wake = async { RuntimeBridgeControl.wakeLink() }
            delay(25)
            RuntimeBridgeControl.publishStatus(
                bridge,
                BridgeStatus(connection = BridgeConnectionState.CONNECTED),
            )

            wake.await()
            assertEquals("link_wake", bridge.action)
            assertEquals(1, bridge.actionCount)
        } finally {
            RuntimeBridgeControl.detach(bridge, executor)
        }
    }

    private class RecordingBridgeTransport : BridgeTransport {
        private val mutableStatus = MutableStateFlow(BridgeStatus())
        override val status: StateFlow<BridgeStatus> = mutableStatus
        private val mutableActionReady = MutableStateFlow(false)
        override val actionReady: StateFlow<Boolean> = mutableActionReady
        override val frames: Flow<BridgeFrame> = emptyFlow()
        override val terminations: Flow<BridgeTermination> = emptyFlow()
        var action: String? = null
        var payload: JSONObject? = null
        var timeoutMs: Long? = null
        var actionCount: Int = 0
        var actionFailure: Exception? = null

        fun markReady() {
            mutableActionReady.value = true
        }

        override fun connect(endpoint: BridgeEndpoint, resumeAfter: Long) = Unit

        override fun acknowledge(sequence: Long): Boolean = true

        override suspend fun action(
            action: String,
            payload: JSONObject,
            timeoutMs: Long,
            requestId: String,
        ): BridgeActionResult {
            this.action = action
            actionCount += 1
            this.payload = JSONObject(payload.toString())
            this.timeoutMs = timeoutMs
            actionFailure?.let { throw it }
            return BridgeActionResult(JSONObject().put("state", "pairing").put("code", "ABCDEFGH"))
        }

        override fun disconnect(reason: String) = Unit

        override fun close() = Unit
    }
}
