package de.totec.doppel.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPrivacyTest {
    @Test
    fun blocklistActivityKeepsCountsButDropsContactJids() {
        val raw =
            JSONObject()
                .put("count", 2)
                .put("jids", listOf("first@s.whatsapp.net", "second@lid"))
                .put("source", "blocklist_event")
                .toString()

        val stored = JSONObject(privacySafeSafetyDetail("blocklist_set", raw))

        assertEquals(2, stored.getInt("count"))
        assertEquals("blocklist_event", stored.getString("source"))
        assertFalse(stored.has("jids"))
    }

    @Test
    fun nonBlocklistSafetyDetailsRemainAvailable() {
        val raw = JSONObject().put("isActive", true).put("expiresAtMs", 1234L).toString()
        val stored = JSONObject(privacySafeSafetyDetail("reachout_timelock", raw))

        assertTrue(stored.getBoolean("isActive"))
        assertEquals(1234L, stored.getLong("expiresAtMs"))
    }

    @Test
    fun malformedDetailBecomesEmptyDiagnosticJson() {
        assertEquals(0, JSONObject(privacySafeSafetyDetail("blocklist_set", "not-json")).length())
    }
}
