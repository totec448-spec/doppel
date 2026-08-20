package de.totec.doppel.data

import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.data.db.ScopedSettingRecord
import de.totec.doppel.data.db.SettingsScopes
import de.totec.doppel.data.db.StoredSettingValueType
import de.totec.doppel.settings.BotSettingKeys

/**
 * What a single conversation is allowed to decide for itself.
 *
 * Everything the bot does is configured account-wide, which is right for almost all of it — one
 * safety cap, one set of keys, one sleep window. But a chat is also the only place where the
 * question "which model, how fast, how much memory" is actually asked, and until now the answer had
 * to be given globally and then applied to everyone.
 *
 * The list is short on purpose. A generic per-chat copy of all hundred settings would quietly
 * become a second configuration system, and the first time a global change had no effect nobody
 * would know which of the two was winning. These five keys are the ones the chat screen offers, and
 * an absent row always means "whatever the global value is right now" — not a copy of it taken at
 * the time the chat was opened.
 */
object ChatOverrides {
    /** Stops replies and proactive outreach for only this chat; admin controls remain reachable. */
    const val PAUSED = "chat_paused"

    /** `human` or `instant` — whether she takes human time to answer this person. */
    const val REPLY_PRESET = BotSettingKeys.REPLY_PRESET

    /**
     * The word that makes her answer in this group; empty means she answers everyone in it.
     *
     * Stored per chat under the same key as the global trigger, which is what makes "leave it
     * empty" mean the same thing in both places.
     */
    const val GROUP_TRIGGER = de.totec.doppel.settings.AppSettingKeys.GROUP_TRIGGER

    /** Every key a chat may override. Anything else stays global, and stays global everywhere. */
    val KEYS: Set<String> = setOf(PAUSED, REPLY_PRESET, GROUP_TRIGGER)
}

/**
 * Reads and writes the per-chat deviations.
 *
 * Thin by design: no caching, no snapshot, no revision. A turn resolves them once while it is
 * already loading that chat's settings, and the screen writes one row when a control is used —
 * neither path is hot enough to be worth a second layer that could go stale.
 */
class ChatOverrideStore(private val repository: BotRepository) {
    /** Every override this chat holds, empty when it follows the global settings in full. */
    fun all(chatId: String): Map<String, String> {
        if (chatId.isBlank()) return emptyMap()
        return runCatching {
            repository.listSettings(SettingsScopes.CHAT, chatId)
                .filter { it.key in ChatOverrides.KEYS }
                .associate { it.key to it.value }
        }.getOrDefault(emptyMap())
    }

    fun value(chatId: String, key: String): String? = all(chatId)[key]

    /**
     * Sets one override, or clears it when [value] is null.
     *
     * Clearing is not the same as writing the current global value: an override frozen at today's
     * global setting would stop following it tomorrow, which is exactly the surprise this whole
     * mechanism has to avoid.
     */
    fun put(chatId: String, key: String, value: String?) {
        require(key in ChatOverrides.KEYS) { "Not an overridable setting: $key" }
        if (chatId.isBlank()) return
        if (value == null) {
            repository.deleteSetting(SettingsScopes.CHAT, chatId, key)
            return
        }
        repository.putSetting(
            ScopedSettingRecord(
                scopeType = SettingsScopes.CHAT,
                scopeId = chatId,
                key = key,
                value = value,
                valueType = StoredSettingValueType.STRING,
            ),
        )
    }

    /** Drops every override, putting the chat back on the global settings in one step. */
    fun clear(chatId: String) {
        if (chatId.isBlank()) return
        ChatOverrides.KEYS.forEach { repository.deleteSetting(SettingsScopes.CHAT, chatId, it) }
    }
}
