package de.totec.doppel.integration

import de.totec.doppel.data.db.ChatKind
import de.totec.doppel.data.db.ChatRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * A chat is keyed by the address the transport delivered on — for most contacts their LID, whose
 * digits are unrelated to their phone number. Everything the user types is a phone number, so
 * without the alias the chat records here, a months-old conversation looks like an unknown
 * stranger and outreach opens a second, empty chat.
 */
class ChatAliasReconciliationTest {
    private val lid = "123456789012345@lid"
    private val phone = "491234567890@s.whatsapp.net"

    @Test
    fun `a lid and its phone number share no identity on their own`() {
        assertFalse(sameIdentity(lid, phone))
    }

    @Test
    fun `a recorded alias links the phone number to the lid chat`() {
        val metadata = mergeChatAliases(null, listOf(phone))

        assertTrue(storedChatAliases(metadata).any { sameIdentity(it, phone) })
        assertTrue(storedChatAliases(metadata).any { sameIdentity(it, "491234567890") })
    }

    /**
     * Aliases only ever accumulate. An event that carries none — an older message, or a contact
     * the LID store cannot resolve at that moment — must not drop a link established earlier.
     */
    @Test
    fun `an event without aliases leaves the recorded ones alone`() {
        val first = mergeChatAliases(null, listOf(phone))

        assertEquals(listOf(phone), storedChatAliases(mergeChatAliases(first, emptyList())))
    }

    @Test
    fun `a second address is added rather than replacing the first`() {
        val first = mergeChatAliases(null, listOf(phone))
        val second = mergeChatAliases(first, listOf("491234567890:12@s.whatsapp.net", phone))

        assertEquals(
            listOf(phone, "491234567890:12@s.whatsapp.net"),
            storedChatAliases(second),
        )
    }

    @Test
    fun `unrelated chat metadata survives an alias write`() {
        val existing = JSONObject().put("reason", "kept").toString()

        val merged = mergeChatAliases(existing, listOf(phone))

        assertEquals("kept", JSONObject(merged!!).optString("reason"))
        assertEquals(listOf(phone), storedChatAliases(merged))
    }

    @Test
    fun `a chat that never carried an alias reads back empty instead of failing`() {
        assertEquals(emptyList<String>(), storedChatAliases(null))
        assertEquals(emptyList<String>(), storedChatAliases("not json at all"))
    }

    @Test
    fun `a typed phone number reaches the lid chat once the alias is recorded`() {
        val chat = chat(lid, aliases = listOf(phone))

        assertTrue(chatMatchesAddress(chat, phone))
        assertTrue(chatMatchesAddress(chat, "491234567890"))
        assertFalse(chatMatchesAddress(chat(lid), phone))
    }

    /**
     * Arming a cold contact writes a chat row under the address the request named. Done for a
     * contact whose conversation runs on a LID, that leaves an empty second row for one person —
     * the row that then claims there is no history.
     */
    @Test
    fun `the empty stub under a phone number is recognised as the lid chat`() {
        val conversation = chat(lid, aliases = listOf(phone), lastMessageAt = 1_000L)
        val stub = chat(phone)

        assertTrue(sameChatIdentity(conversation, stub))
        assertTrue(sameChatIdentity(stub, conversation))
    }

    /**
     * The per-contact proactivity slider files its level under the typed phone number while the
     * engine resolves settings by the chat's LID. Without the aliases the override is invisible and
     * the contact keeps the global level — the slider looks set but does nothing.
     */
    @Test
    fun `a per-contact override typed as a phone number reaches the lid chat`() {
        val overrides = mapOf("491234567890" to 7)

        assertEquals(7, chatOverride(overrides, lid, listOf(phone)))
        assertEquals(null, chatOverride(overrides, lid, emptyList()))
    }

    @Test
    fun `an override still resolves under the exact address it was filed under`() {
        assertEquals(4, chatOverride(mapOf(phone to 4), phone, emptyList()))
    }

    @Test
    fun `another contact's override never leaks into this chat`() {
        assertEquals(null, chatOverride(mapOf("499999999999" to 9), lid, listOf(phone)))
        assertEquals(null, chatOverride(emptyMap<String, Int>(), lid, listOf(phone)))
    }

    @Test
    fun `two genuinely different contacts are never merged`() {
        val first = chat(lid, aliases = listOf(phone), lastMessageAt = 1_000L)
        val second = chat("999888777@s.whatsapp.net", lastMessageAt = 2_000L)

        assertFalse(sameChatIdentity(first, second))
        assertFalse(sameChatIdentity(second, first))
    }

    private fun chat(
        chatId: String,
        aliases: List<String> = emptyList(),
        lastMessageAt: Long? = null,
    ) = ChatRecord(
        chatId = chatId,
        kind = ChatKind.DIRECT,
        metadataJson = aliases.takeIf(List<String>::isNotEmpty)?.let { mergeChatAliases(null, it) },
        lastMessageAt = lastMessageAt,
        createdAt = 1L,
    )
}
