package de.totec.doppel.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeBlockPayloadTest {
    @Test
    fun `block payload retains bounded PN and LID aliases`() {
        val payload =
            blockContactPayload(
                jid = "123456789012345@lid",
                aliases =
                    listOf(" 49123456789@s.whatsapp.net ", "123456789012345@lid") +
                        (0..20).map { "alias-$it@s.whatsapp.net" },
            )

        assertEquals("123456789012345@lid", payload.getString("chatId"))
        val aliases = payload.getJSONArray("aliases")
        assertEquals(16, aliases.length())
        assertEquals("49123456789@s.whatsapp.net", aliases.getString(0))
        assertEquals("123456789012345@lid", aliases.getString(1))
    }
}
