package de.totec.doppel.transport

import de.totec.doppel.domain.ChatEventKind
import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.domain.MediaKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    @Test
    fun decodesReplayReadyBoundary() {
        val frame =
            BridgeProtocol.decode(
                """
                {
                  "v":1,
                  "type":"ready",
                  "sequence":12,
                  "account":{"state":"connected","jid":"49123@s.whatsapp.net","name":"Bot"}
                }
                """.trimIndent(),
            ) as BridgeFrame.Ready
        assertEquals(12L, frame.sequence)
        assertEquals(BridgeConnectionState.CONNECTED, frame.accountState)
        assertEquals("49123@s.whatsapp.net", frame.accountJid)
    }

    @Test
    fun `hello advertises resume without secrets`() {
        val raw = BridgeProtocol.hello("device-1", 42)
        val json = JSONObject(raw)

        assertEquals(1, json.getInt("v"))
        assertEquals("hello", json.getString("type"))
        assertEquals(42, json.getLong("resumeAfter"))
        assertFalse(raw.contains("token", ignoreCase = true))
    }

    @Test
    fun `incoming media event is normalized`() {
        val raw =
            """
            {
              "v": 1,
              "type": "incoming",
              "sequence": 7,
              "event": {
                "eventId": "evt-7",
                "kind": "message",
                "messageId": "m-1",
                "chatJid": "123@s.whatsapp.net",
                "chatAliases": ["999@lid"],
                "isGroup": false,
                "senderJid": "123@s.whatsapp.net",
                "senderAliases": ["999@lid"],
                "senderName": "Alice",
                "fromMe": false,
                "timestampMs": 1234,
                "text": "hi",
                "quoted": {"messageId":"old","text":"before"},
                "media": {
                  "id":"0123456789abcdef0123456789abcdef",
                  "kind":"image",
                  "mimeType":"image/jpeg",
                  "sizeBytes":123,
                  "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
              }
            }
            """.trimIndent()

        val frame = BridgeProtocol.decode(raw) as BridgeFrame.Incoming

        assertEquals(7, frame.sequence)
        assertEquals(ChatEventKind.MESSAGE, frame.event.kind)
        assertEquals("before", frame.event.quoted?.text)
        assertEquals(MediaKind.IMAGE, frame.event.media?.kind)
        assertEquals(123L, frame.event.media?.sizeBytes)
        assertEquals(listOf("999@lid"), frame.event.chatAliases)
        assertEquals(listOf("999@lid"), frame.event.senderAliases)
    }

    @Test
    fun `welcome exposes negotiated replay and reset cursor`() {
        val frame =
            BridgeProtocol.decode(
                """
                {
                  "v": 1,
                  "type": "welcome",
                  "sequence": 4,
                  "capabilities": ["protocol.v1", "journal.durable"],
                  "account": {"state": "connected"},
                  "resume": {
                    "requested": 99,
                    "after": 0,
                    "oldest": 1,
                    "latest": 4,
                    "count": 4,
                    "gap": false,
                    "reset": true
                  }
                }
                """.trimIndent(),
            ) as BridgeFrame.Welcome

        assertEquals(0L, frame.resume.after)
        assertEquals(4, frame.resume.count)
        assertEquals(
            de.totec.doppel.domain.BridgeConnectionState.CONNECTED,
            frame.accountState,
        )
        assertTrue(frame.resume.reset)
    }

    @Test(expected = BridgeProtocolException::class)
    fun `welcome without resume state fails closed`() {
        BridgeProtocol.decode(
            """{"v":1,"type":"welcome","sequence":0,"capabilities":["protocol.v1"]}""",
        )
    }

    @Test
    fun `pairing availability event never carries persisted code`() {
        val frame =
            BridgeProtocol.decode(
                """{"v":1,"type":"pairing_code","sequence":9,"available":true,"expiresAtMs":99}""",
            ) as BridgeFrame.PairingCode

        assertTrue(frame.available)
        assertEquals(99L, frame.expiresAtMs)
    }

    @Test
    fun `sequenced safety telemetry stays transport-only`() {
        val frame =
            BridgeProtocol.decode(
                """
                {
                  "v": 1,
                  "type": "safety",
                  "sequence": 19,
                  "kind": "connection_hard_stop",
                  "state": "manual_review"
                }
                """.trimIndent(),
            ) as BridgeFrame.Safety

        assertEquals(19L, frame.sequence)
        assertEquals("connection_hard_stop", frame.kind)
        assertTrue(frame.detail.contains("manual_review"))
    }

    @Test(expected = BridgeProtocolException::class)
    fun `unknown versions fail closed`() {
        BridgeProtocol.decode("""{"v":2,"type":"welcome","sequence":0}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported actions cannot be serialized`() {
        BridgeProtocol.action("id", "run_shell")
    }

    @Test
    fun `action keeps request id as idempotency key`() {
        val json = JSONObject(BridgeProtocol.action("stable-id", "reconnect"))
        assertEquals("stable-id", json.getString("id"))
        assertEquals("reconnect", json.getString("action"))
    }

    @Test
    fun `safety refresh is an allowed bridge action`() {
        val json = JSONObject(BridgeProtocol.action("safety-id", "safety_refresh"))

        assertEquals("safety_refresh", json.getString("action"))
        assertEquals(0, json.getJSONObject("payload").length())
    }

    /**
     * The persona switch dresses the account: a picture and an info line. Both are dispatched by the
     * Go runtime, so leaving them out of this allowlist silently swallows every switch on the phone.
     */
    @Test
    fun `the profile actions the runtime sends are allowed`() {
        val picture =
            JSONObject(
                BridgeProtocol.action(
                    "picture-id",
                    "set_profile_picture",
                    JSONObject().put("mediaId", "abcdefgh"),
                ),
            )
        val about =
            JSONObject(
                BridgeProtocol.action(
                    "about-id",
                    "set_status_message",
                    JSONObject().put("text", "back later"),
                ),
            )

        assertEquals("set_profile_picture", picture.getString("action"))
        assertEquals("abcdefgh", picture.getJSONObject("payload").getString("mediaId"))
        assertEquals("set_status_message", about.getString("action"))
        assertEquals("back later", about.getJSONObject("payload").getString("text"))
    }

    /**
     * A status update carries an address this contract cannot express. It has to stay undecodable —
     * but it must be skippable, because the journal replays it on every single reconnect.
     */
    @Test
    fun `an event with an unroutable address is quarantined instead of fatal`() {
        val raw =
            """
            {
              "v":1,
              "type":"incoming",
              "sequence":211,
              "event":{
                "eventId":"message:status@broadcast:A1",
                "kind":"message",
                "messageId":"A1",
                "chatJid":"status@broadcast",
                "senderJid":"4915112345678@s.whatsapp.net",
                "text":"hi"
              }
            }
            """.trimIndent()

        val failure = runCatching { BridgeProtocol.decode(raw) }.exceptionOrNull()
        assertTrue(failure is BridgeProtocolException)

        val quarantined = BridgeProtocol.quarantine(raw, failure?.message.orEmpty())
        assertEquals(211L, quarantined?.sequence)
        assertEquals("incoming", quarantined?.frameType)
        assertTrue(quarantined?.reason.orEmpty().contains("chatJid"))
    }

    /** An older app meeting a newer bridge skips what it cannot read rather than disconnecting. */
    @Test
    fun `an unknown event type is quarantined`() {
        val quarantined =
            BridgeProtocol.quarantine(
                """{"v":1,"type":"presence_update","sequence":7}""",
                "Unknown bridge frame type presence_update",
            )

        assertEquals(7L, quarantined?.sequence)
        assertEquals("presence_update", quarantined?.frameType)
    }

    /** One receipt stanza can acknowledge a whole backlog; the frame carries all of its IDs. */
    @Test
    fun `a delivery receipt decodes every message it acknowledges`() {
        val frame =
            BridgeProtocol.decode(
                """
                {
                  "v":1,
                  "type":"delivery",
                  "sequence":31,
                  "messageId":"AAA",
                  "messageIds":["AAA","BBB","CCC"],
                  "status":"read",
                  "timestampMs":1700000000000
                }
                """.trimIndent(),
            ) as BridgeFrame.Delivery

        assertEquals(listOf("AAA", "BBB", "CCC"), frame.messageIds)
        assertEquals("AAA", frame.messageId)
    }

    /** The list is an extension; a bridge that only sends the single ID still decodes. */
    @Test
    fun `a delivery receipt without the id list still decodes`() {
        val frame =
            BridgeProtocol.decode(
                """{"v":1,"type":"delivery","sequence":31,"messageId":"AAA","status":"delivered"}""",
            ) as BridgeFrame.Delivery

        assertEquals(listOf("AAA"), frame.messageIds)
    }

    @Test
    fun `a missed call decodes as conversational input`() {
        val frame =
            BridgeProtocol.decode(
                """
                {
                  "v":1,
                  "type":"incoming",
                  "sequence":44,
                  "event":{
                    "eventId":"call_missed:4915100000001@s.whatsapp.net:C1",
                    "kind":"call_missed",
                    "messageId":"call:C1",
                    "chatJid":"4915100000001@s.whatsapp.net",
                    "senderJid":"4915100000001@s.whatsapp.net",
                    "fromMe":false,
                    "timestampMs":1700000000000,
                    "text":"",
                    "category":"call",
                    "callMedia":"video"
                  }
                }
                """.trimIndent(),
            ) as BridgeFrame.Incoming

        assertEquals(ChatEventKind.CALL_MISSED, frame.event.kind)
        assertEquals("call:C1", frame.event.messageId)
        assertEquals("video", frame.event.callMedia)
        assertFalse(frame.event.isGroup)
    }

    /** The call media is only meaningful on a call, and only in the two shapes WhatsApp has. */
    @Test
    fun `call media is ignored on anything but a call`() {
        val frame =
            BridgeProtocol.decode(
                """
                {
                  "v":1,
                  "type":"incoming",
                  "sequence":45,
                  "event":{
                    "eventId":"message:4915100000001@s.whatsapp.net:M1",
                    "kind":"message",
                    "messageId":"M1",
                    "chatJid":"4915100000001@s.whatsapp.net",
                    "senderJid":"4915100000001@s.whatsapp.net",
                    "timestampMs":1700000000000,
                    "text":"hi",
                    "callMedia":"video"
                  }
                }
                """.trimIndent(),
            ) as BridgeFrame.Incoming

        assertNull(frame.event.callMedia)
    }

    /** Handshake frames carry session state, not a stream position, so they cannot be skipped. */
    @Test
    fun `a broken handshake frame is not quarantined`() {
        assertNull(
            BridgeProtocol.quarantine(
                """{"v":1,"type":"welcome","sequence":3}""",
                "Welcome frame is missing resume state",
            ),
        )
        assertNull(BridgeProtocol.quarantine("""{"v":1,"type":"incoming"}""", "Missing sequence"))
        assertNull(BridgeProtocol.quarantine("not json", "Bridge sent malformed JSON"))
        assertNull(
            BridgeProtocol.quarantine("""{"v":2,"type":"incoming","sequence":3}""", "bad version"),
        )
    }
}
