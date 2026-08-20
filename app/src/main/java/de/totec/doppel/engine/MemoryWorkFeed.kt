package de.totec.doppel.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which file a running memory write is going to land in. */
enum class MemoryWorkScope {
    /** One conversation's own memory, keyed by `<chatJid>#<persona>`. */
    CHAT,

    /** A persona's cross-chat synthesis, keyed by the persona id alone. */
    PERSONA,
}

/** The only two public phases: private reasoning stays private, final prose may be previewed. */
enum class MemoryWorkPhase {
    REASONING,
    WRITING,
}

/**
 * One memory write that is in flight right now.
 *
 * [key] is what the write is keyed by — the conversation key for [MemoryWorkScope.CHAT], the persona
 * id for [MemoryWorkScope.PERSONA]. [chatJid] is the physical chat, which the chat screen matches on
 * because it knows nothing about persona-suffixed conversation keys.
 */
data class MemoryWork(
    val scope: MemoryWorkScope,
    val key: String,
    val personaKey: String,
    val chatJid: String? = null,
    val startedAtMs: Long = 0L,
    val phase: MemoryWorkPhase = MemoryWorkPhase.REASONING,
    /** Bounded final model prose only. Chain-of-thought is never copied into UI state or storage. */
    val draft: String = "",
)

/**
 * Every memory write currently running, written by the memory service and read by the UI.
 *
 * It exists because a memory write is the one expensive thing in this app that produced no visible
 * sign of itself: a model call of up to a minute behind a menu entry that looked like it had done
 * nothing, so it got pressed again and billed twice. Every surface that can start one — the chat
 * menu, the persona card, the browser rows — reads this to draw its progress and to refuse a second
 * press while the first is still running.
 *
 * On the graph rather than in the engine for the same reason [ChatActivityFeed] is: the engine is
 * rebuilt on every reconnect, the screen watching it is not. In-memory by design — a write cannot
 * outlive the process running it, so neither should the row describing it.
 */
class MemoryWorkFeed {
    private val _inFlight = MutableStateFlow<List<MemoryWork>>(emptyList())

    val inFlight: StateFlow<List<MemoryWork>> = _inFlight.asStateFlow()

    fun started(work: MemoryWork) {
        _inFlight.update { current ->
            if (current.any { it.scope == work.scope && it.key == work.key }) {
                current
            } else {
                current + work
            }
        }
    }

    fun finished(scope: MemoryWorkScope, key: String) {
        _inFlight.update { current ->
            current.filterNot { it.scope == scope && it.key == key }
        }
    }

    /** Appends streamed final prose without ever publishing the model's private reasoning. */
    fun appendDraft(scope: MemoryWorkScope, key: String, text: String) {
        if (text.isEmpty()) return
        _inFlight.update { current ->
            current.map { item ->
                if (item.scope == scope && item.key == key) {
                    item.copy(
                        phase = MemoryWorkPhase.WRITING,
                        draft = (item.draft + text).take(MAX_DRAFT_CHARACTERS),
                    )
                } else {
                    item
                }
            }
        }
    }

    /** Runs [block] with the write announced, and takes the announcement back however it ends. */
    suspend fun <T> tracking(work: MemoryWork, block: suspend () -> T): T {
        started(work)
        return try {
            block()
        } finally {
            finished(work.scope, work.key)
        }
    }

    private companion object {
        /** UI preview bound; the durable sanitizer/character setting remains the commit authority. */
        const val MAX_DRAFT_CHARACTERS = 32_768
    }
}

/** True while this physical chat has a memory write running, whatever persona owns it. */
fun List<MemoryWork>.writingChat(chatJid: String): Boolean =
    any { it.scope == MemoryWorkScope.CHAT && it.chatJid == chatJid }

/** The exact running chat write, including its streamed final draft. */
fun List<MemoryWork>.chatWork(chatJid: String): MemoryWork? =
    firstOrNull { it.scope == MemoryWorkScope.CHAT && it.chatJid == chatJid }

/** True while this exact conversation memory is being rewritten. */
fun List<MemoryWork>.writingConversation(conversationKey: String): Boolean =
    any { it.scope == MemoryWorkScope.CHAT && it.key == conversationKey }

/** True while this persona's cross-chat synthesis is running. */
fun List<MemoryWork>.writingPersona(personaKey: String): Boolean =
    any { it.scope == MemoryWorkScope.PERSONA && it.key == personaKey }

/** The running synthesis for one persona, for surfaces that show more than that it is running. */
fun List<MemoryWork>.personaWork(personaKey: String): MemoryWork? =
    firstOrNull { it.scope == MemoryWorkScope.PERSONA && it.key == personaKey }
