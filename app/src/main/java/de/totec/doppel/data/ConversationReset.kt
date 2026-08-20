package de.totec.doppel.data

import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.media.ApprovedMediaAssetStore

/**
 * Puts one persona's conversation back to the state a never-before-seen contact is in.
 *
 * Deleting the conversation rows alone was not enough. Three things outlived it and made the next
 * turn behave like a continuation:
 *
 *  * **Persona memory.** It is synthesised purely from this persona's chat memories, and nothing
 *    deleted it. The summary of the wiped conversation kept being injected into every following
 *    prompt, which is why a freshly cleared chat could produce a memory write describing messages
 *    that no longer existed.
 *  * **Proactive state.** The per-chat outreach counters decide whether and when she pings first.
 *    Keeping them meant a "new" chat started mid-schedule.
 *  * **Sent-image history.** The bot still believed it had already sent those pictures.
 *
 * Persona memory is only dropped once this persona has no chat memory left at all. While another
 * conversation of hers survives, the summary still describes it, and the next synthesis rebuilds it
 * without the deleted chat anyway — the cadence reads the durable sum of the remaining revisions.
 * Deleting it eagerly would throw away what she remembers about contacts that were never cleared.
 *
 * Nothing here talks to WhatsApp. The transport has no history-sync handler at all, so cleared
 * messages are never re-downloaded and a reset costs no traffic.
 *
 * Proactive state and image history are keyed by chat, not by persona, so they reset for the whole
 * conversation. That matches what clearing is for: one chat has one effective persona at a time.
 *
 * @return the number of deleted conversation records, for the operator-facing confirmation.
 */
fun resetConversation(
    repository: BotRepository,
    approvedMedia: ApprovedMediaAssetStore,
    conversationKey: String,
): Int {
    val affected = repository.deleteConversationData(conversationKey)
    val chatId = conversationKey.substringBeforeLast('#', "")
    if (chatId.isEmpty()) return affected

    repository.deleteProactiveState(chatId)
    runCatching { approvedMedia.clearSentHistory(chatId) }

    val personaId = conversationKey.substringAfterLast('#', "")
    if (personaId.isEmpty()) return affected
    val personaHasOtherChats =
        runCatching {
            repository
                .listChatMemoriesForPersona(personaId, limit = 1, maxSummaryChars = 200)
                .isNotEmpty()
        }.getOrDefault(true)
    if (!personaHasOtherChats) repository.deletePersonaMemory(personaId)
    return affected
}

/**
 * Removes only the visible transcript while retaining chat and persona memory. The behavioral
 * state tied to that transcript is reset through the same shared owner as a full conversation
 * reset, so a cleared chat does not retain an old proactive schedule or image-dedup history.
 */
fun resetConversationHistory(
    repository: BotRepository,
    approvedMedia: ApprovedMediaAssetStore,
    conversationKey: String,
): Int {
    val affected = repository.deleteConversationHistory(conversationKey)
    val chatId = conversationKey.substringBeforeLast('#', "")
    if (chatId.isNotEmpty()) {
        repository.deleteProactiveState(chatId)
        runCatching { approvedMedia.clearSentHistory(chatId) }
    }
    return affected
}
