package de.totec.doppel.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeActionTimeoutTest {
    @Test
    fun `android deadlines outlive native WhatsApp operations`() {
        assertEquals(195_000L, bridgeActionTimeoutMs("send_media"))
        assertEquals(105_000L, bridgeActionTimeoutMs("send_text"))
        assertEquals(105_000L, bridgeActionTimeoutMs("send_reaction"))
        assertEquals(105_000L, bridgeActionTimeoutMs("edit_message"))
        assertEquals(105_000L, bridgeActionTimeoutMs("block"))
        assertEquals(45_000L, bridgeActionTimeoutMs("safety_refresh"))
    }
}
