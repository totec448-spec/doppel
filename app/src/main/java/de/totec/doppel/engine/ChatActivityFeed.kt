package de.totec.doppel.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the bot is doing, per chat, right now.
 *
 * The engine has always known this and never said it. [BotEngine] holds exactly one `activeTurn`
 * at a time, a map of armed pickup windows and a channel of turns queued behind the active one —
 * three private structures that together answer "why is nothing happening" and that the UI could
 * only guess at from the activity log, after the fact.
 *
 * This is the read side of those three, and nothing more: the engine pushes stage changes as they
 * happen, the UI collects. Nothing here is persisted. A stage is true for as long as the process
 * that produced it lives, which is correct — a pickup window does not survive a restart either.
 *
 * The single-active-turn rule is the whole reason the chat list can be read at a glance: at most
 * one row is ever mid-operation, everything else is waiting with a time on it or idle.
 */
enum class ChatStage {
    /** Nothing scheduled. The row shows the last message instead of an operation. */
    IDLE,

    /** A pickup window is armed and [ChatActivity.dueAtMs] says when it fires. */
    WAITING,

    /** The window fired; the turn is behind another chat's turn and cannot start yet. */
    QUEUED,

    /** Loading history, marking read — everything before the first byte is spent. */
    READING,

    /** A photo or voice note is being described; the detail carries the model name. */
    ANALYZING,

    /** The main call is out. The detail carries the model name, the trace carries the reasoning. */
    THINKING,

    /** Writing a bubble at human speed; the detail counts them, e.g. `1/2`. */
    TYPING,

    /** A bubble is on the wire. */
    SENDING,

    /** The turn ended badly. The detail is the reason, and it stays until the retry starts. */
    FAILED,
}

/** True while the bot is mid-operation, as opposed to merely scheduled to be. */
val ChatStage.isWorking: Boolean
    get() = this == ChatStage.READING ||
        this == ChatStage.ANALYZING ||
        this == ChatStage.THINKING ||
        this == ChatStage.TYPING ||
        this == ChatStage.SENDING

data class ChatActivity(
    val chatJid: String,
    val stage: ChatStage,
    /** Wall-clock instant the armed window fires; only ever set for [ChatStage.WAITING]. */
    val dueAtMs: Long? = null,
    val detail: String? = null,
    /**
     * The last provider error this chat hit, in as few words as it takes — "OpenRouter 400".
     *
     * Separate from [ChatStage.FAILED] because the two are genuinely different: a failed attempt
     * that will be retried leaves the stage alone and the turn still running, and the countdown
     * that used to be all one could see said nothing about it. It is cleared by the next answer
     * that works, so a stale one cannot linger.
     */
    val problem: String? = null,
    val updatedAtMs: Long = 0L,
)

/** One line of the live trace. The kind decides how loud it is drawn, not what it says. */
enum class TraceKind {
    /** Engine progress: a download finished, a window closed. */
    STEP,

    /** Streamed model reasoning — dim, the bulk of the trace. */
    REASONING,

    /** A tool the model called, and its return. Drawn bright: these cost money and change state. */
    TOOL,

    /** Something went wrong. */
    PROBLEM,
}

data class TraceLine(
    val kind: TraceKind,
    val text: String,
    val atMs: Long,
)

/**
 * The turn being run right now, in full detail — trace, draft and failure.
 *
 * Separate from [ChatActivity] because the list only needs one word per chat, while an open chat
 * wants everything. Only one exists at a time, for the same reason only one turn runs at a time.
 */
data class LiveTurn(
    val chatJid: String,
    val turnId: String,
    val stage: ChatStage,
    val detail: String? = null,
    val trace: List<TraceLine> = emptyList(),
    /** What has been typed into the field so far; survives a failure on purpose. */
    val draft: String = "",
    val failure: String? = null,
    /** The last provider error inside this turn; see [ChatActivity.problem]. */
    val problem: String? = null,
    val startedAtMs: Long = 0L,
)

/**
 * The engine's write side and the UI's read side of the same two flows.
 *
 * Held on the application graph so the engine can publish without knowing a UI exists, and so the
 * UI can collect without reaching into the engine. Every method is safe to call from any thread.
 */
class ChatActivityFeed(private val traceLimit: Int = MAX_TRACE_LINES) {
    private val _chats = MutableStateFlow<Map<String, ChatActivity>>(emptyMap())

    /** Per chat, the one word the list row shows. Chats with nothing to say are absent. */
    val chats: StateFlow<Map<String, ChatActivity>> = _chats.asStateFlow()

    private val _live = MutableStateFlow<LiveTurn?>(null)

    /** The running turn in full, or `null` while the engine is idle. */
    val live: StateFlow<LiveTurn?> = _live.asStateFlow()

    /** A pickup window was armed: the chat is waiting, and [dueAtMs] says until when. */
    fun armed(chatJid: String, dueAtMs: Long, nowMs: Long) {
        clearFailedTurn(chatJid)
        put(ChatActivity(chatJid, ChatStage.WAITING, dueAtMs = dueAtMs, updatedAtMs = nowMs))
    }

    /** The window fired but another chat holds the engine. */
    fun queued(chatJid: String, nowMs: Long) {
        clearFailedTurn(chatJid)
        put(ChatActivity(chatJid, ChatStage.QUEUED, updatedAtMs = nowMs))
    }

    /** A previous failure belongs to its turn, never to a newly received message. */
    private fun clearFailedTurn(chatJid: String) {
        _live.update { current ->
            if (current?.chatJid == chatJid && current.stage == ChatStage.FAILED) null else current
        }
    }

    /** This chat now owns the engine. Replaces any previous live turn, which must have ended. */
    fun begin(chatJid: String, turnId: String, nowMs: Long) {
        _live.value = LiveTurn(chatJid, turnId, ChatStage.READING, startedAtMs = nowMs)
        // The previous turn's provider error does not belong to this one, and put() carries the
        // field forward on purpose, so the row has to go before the new one is written.
        _chats.update { it - chatJid }
        put(ChatActivity(chatJid, ChatStage.READING, updatedAtMs = nowMs))
    }

    /** Moves the running turn to a new stage; ignored if the turn already ended. */
    fun stage(chatJid: String, stage: ChatStage, detail: String? = null, nowMs: Long) {
        put(ChatActivity(chatJid, stage, detail = detail, updatedAtMs = nowMs))
        _live.update { current ->
            if (current?.chatJid != chatJid) current else current.copy(stage = stage, detail = detail)
        }
    }

    /**
     * Appends a trace line, dropping the oldest once [traceLimit] is reached.
     *
     * The cap is what keeps a long reasoning stream from turning into an unbounded list that the
     * UI has to diff on every token. Nothing is stored, so nothing is lost that mattered.
     */
    fun trace(chatJid: String, kind: TraceKind, text: String, nowMs: Long) {
        if (text.isBlank()) return
        _live.update { current ->
            if (current?.chatJid != chatJid) {
                current
            } else {
                val appended = current.trace + TraceLine(kind, text.trim(), nowMs)
                current.copy(
                    trace = if (appended.size <= traceLimit) appended else appended.takeLast(traceLimit),
                )
            }
        }
    }

    /**
     * Appends streamed reasoning to the line it is already writing, rather than starting a new one.
     *
     * A chain of thought arrives in fragments that are not sentences and not lines — cutting a new
     * trace entry per batch would both read as gibberish and empty the [traceLimit] window of
     * everything else within a second. So the batches grow one paragraph until it reaches
     * [MAX_REASONING_LINE_CHARACTERS], and only then does the next one start. A step, a tool call
     * or a problem in between ends the paragraph on its own, because it is no longer the last line.
     */
    fun reasoning(chatJid: String, chunk: String, nowMs: Long) {
        if (chunk.isEmpty()) return
        _live.update { current ->
            if (current?.chatJid != chatJid) return@update current
            val trace = current.trace.toMutableList()
            var offset = 0
            val last = trace.lastOrNull()
            if (last?.kind == TraceKind.REASONING) {
                val room = (MAX_REASONING_LINE_CHARACTERS - last.text.length).coerceAtLeast(0)
                if (room > 0) {
                    val count = minOf(room, chunk.length)
                    trace[trace.lastIndex] = last.copy(text = last.text + chunk.substring(0, count))
                    offset = count
                }
            }
            while (offset < chunk.length) {
                val end = minOf(offset + MAX_REASONING_LINE_CHARACTERS, chunk.length)
                val piece = chunk.substring(offset, end).let { if (offset == 0) it.trimStart() else it }
                if (piece.isNotEmpty()) trace += TraceLine(TraceKind.REASONING, piece, nowMs)
                offset = end
            }
            current.copy(
                trace = if (trace.size <= traceLimit) trace else trace.takeLast(traceLimit),
            )
        }
    }

    /** Replaces the text shown in the field as she types it, character by character. */
    fun draft(chatJid: String, text: String) {
        _live.update { current ->
            if (current?.chatJid != chatJid) current else current.copy(draft = text)
        }
    }

    /**
     * The turn failed.
     *
     * The draft is deliberately kept: a half-written line is the most informative thing on the
     * screen at that moment, because it says how far the call got before the money was spent.
     */
    fun failed(chatJid: String, reason: String, nowMs: Long) {
        put(ChatActivity(chatJid, ChatStage.FAILED, detail = reason, updatedAtMs = nowMs))
        _live.update { current ->
            if (current?.chatJid != chatJid) {
                current
            } else {
                current.copy(stage = ChatStage.FAILED, failure = reason)
            }
        }
    }

    /**
     * The turn ended. The chat drops out of the map rather than showing a stale word.
     *
     * A failed turn is left standing: the whole point of [failed] is that the reason outlives the
     * turn that produced it, and the turn's own `finally` runs immediately afterwards. It is
     * cleared by the next [begin] for that chat, or by [clearAll].
     */
    fun finish(chatJid: String) {
        if (_chats.value[chatJid]?.stage == ChatStage.FAILED) return
        _chats.update { it - chatJid }
        _live.update { if (it?.chatJid == chatJid) null else it }
    }

    /** Forgets every transient trace belonging to a chat that was explicitly deleted. */
    fun forget(chatJids: Collection<String>) {
        val removed = chatJids.toSet()
        if (removed.isEmpty()) return
        _chats.update { current -> current - removed }
        _live.update { current -> current?.takeUnless { it.chatJid in removed } }
    }

    /** Everything stops: the engine paused, or the bridge went down mid-turn. */
    fun clearAll() {
        _chats.value = emptyMap()
        _live.value = null
    }

    /**
     * A provider call for the running turn came back an error, and the turn goes on.
     *
     * Attached to whichever chat currently holds the engine, because exactly one turn runs at a
     * time — that is what makes an HTTP failure, which knows nothing about chats, placeable at all.
     */
    fun noteProblem(text: String, nowMs: Long) {
        val chatJid = _live.value?.chatJid ?: return
        _live.update { current ->
            if (current?.chatJid != chatJid) current else current.copy(problem = text)
        }
        _chats.update { current ->
            val existing = current[chatJid] ?: return@update current
            current + (chatJid to existing.copy(problem = text, updatedAtMs = nowMs))
        }
    }

    /** A provider call worked, so the last error is history and stops being shown. */
    fun clearProblem() {
        val chatJid = _live.value?.chatJid ?: return
        _live.update { current ->
            if (current?.chatJid != chatJid) current else current.copy(problem = null)
        }
        _chats.update { current ->
            val existing = current[chatJid] ?: return@update current
            current + (chatJid to existing.copy(problem = null))
        }
    }

    /**
     * Stage changes carry the problem forward: the retry moves the chat from THINKING to WAITING
     * and back, and rebuilding the row from scratch each time would erase the only visible sign
     * that anything went wrong. [clearProblem] and the next [begin] are what end it.
     */
    private fun put(activity: ChatActivity) {
        _chats.update { current ->
            val carried =
                activity.copy(problem = activity.problem ?: current[activity.chatJid]?.problem)
            current + (activity.chatJid to carried)
        }
    }

    private companion object {
        /**
         * Enough to hold a full reasoning stream plus its tool calls, and small enough that the
         * list is cheap to recompose on every appended token.
         */
        const val MAX_TRACE_LINES = 60

        /**
         * How long one paragraph of reasoning grows before the next begins. Long enough that a
         * thought is not chopped mid-argument, short enough that appending to it stays a cheap
         * string copy on every batch.
         */
        const val MAX_REASONING_LINE_CHARACTERS = 600
    }
}
