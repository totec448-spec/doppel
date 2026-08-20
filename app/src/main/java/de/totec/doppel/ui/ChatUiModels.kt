package de.totec.doppel.ui

import android.net.Uri

import androidx.compose.runtime.Immutable
import de.totec.doppel.engine.ChatActivity
import de.totec.doppel.engine.LinkState
import de.totec.doppel.engine.LiveTurn
import de.totec.doppel.engine.MemoryWork
import kotlinx.coroutines.flow.StateFlow

/**
 * What the redesigned app shows, as data.
 *
 * The old UI was five tabs of machine state: runtime phase, settings groups, access lists, tools,
 * a log. None of it answered the only question actually being asked while the bot runs — *what is
 * it doing with this person, right now* — because that lived in the engine's head and in a log you
 * read afterwards. These models exist to answer it, and the screens are thin over them.
 */

/** One row of the landing list: a person or group the bot has ever written in. */
data class ChatRow(
    val chatId: String,
    /** Phone number for a person, subject for a group. Never a raw JID. */
    val title: String,
    val isGroup: Boolean,
    /** Single character for the avatar; the avatar is a colour and a letter, never a photo. */
    val initial: String,
    /** Stable index into the avatar palette, derived from [chatId] so it never moves. */
    val accent: Int,
    /** The last thing said, by either side, already prefixed with who said it in a group. */
    val preview: String,
    val previewFromBot: Boolean,
    val previewAtMs: Long,
    /** The live operation, or `null` when the bot has nothing scheduled for this chat. */
    val activity: ChatActivity? = null,
    val scheduledFollowUpAtMs: Long? = null,
    val scheduledFollowUpNote: String? = null,
    /** True once the chat has been silent long enough to sit below the quiet rule. */
    val quiet: Boolean = false,
)

/** Everything a chat screen draws, in the order it is drawn. */
sealed interface ChatEntry {
    val atMs: Long

    /** A message from either side. Media is a [Bubble] too — with [media] set. */
    data class Bubble(
        val id: Long,
        override val atMs: Long,
        val fromBot: Boolean,
        val text: String,
        /** Group sender, shown only when the speaker changes. Null in one-to-one chats. */
        val senderName: String? = null,
        val delivery: String? = null,
        val media: MediaPlaceholder? = null,
        /** True when the previous bubble came from the same side inside one send. */
        val stacked: Boolean = false,
        /** True when the next visual row is another bubble from this exact speaker. */
        val continuesAfter: Boolean = false,
        /** Rendering-only identity used to group different members of one group correctly. */
        val speakerKey: String = if (fromBot) "bot" else "contact",
    ) : ChatEntry

    /**
     * A memory write, at the point in the conversation where it happened.
     *
     * Collapsed by default: expanded, twelve of these would bury the conversation they describe.
     */
    data class Memory(
        val id: String,
        val conversationKey: String,
        override val atMs: Long,
        val persona: String,
        val revision: Long,
        val characters: Int,
        val budget: Int,
        val text: String,
    ) : ChatEntry

    /**
     * A line you added by hand, sitting where you added it.
     *
     * It behaves like a message — it holds its position and is pushed back as the conversation
     * moves on — but it is not one: it does not count towards the message total and the model
     * receives it marked as important operator context. It participates in memory compression and is removed
     * only when ordinary retained-history pruning ages the source row out.
     */
    data class Injection(
        val id: Long,
        override val atMs: Long,
        val text: String,
    ) : ChatEntry

    /** A future-only local landmark showing where the active persona changed. */
    data class PersonaSwitch(
        val id: Long,
        override val atMs: Long,
        val from: String,
        val to: String,
    ) : ChatEntry

    /** A date separator. Cheap, and the only thing that makes a long log navigable. */
    data class DayBreak(
        override val atMs: Long,
        val label: String,
    ) : ChatEntry
}

/**
 * Received media, as it can honestly be shown.
 *
 * The bytes are gone: analysis runs in RAM and nothing is kept but the description, so there is
 * nothing to play or open. The placeholder says what kind of thing arrived and the description
 * sits underneath in italics — which is exactly what the bot itself got to work with.
 */
data class MediaPlaceholder(
    val kind: MediaPlaceholderKind,
    /** The model's description or transcript. Empty while the analysis has not run yet. */
    val description: String,
)

enum class MediaPlaceholderKind(
    /** What the list row says when a media message is the last thing in a chat. */
    val word: String,
) {
    IMAGE("Photo"),
    VOICE("Voice message"),
    VIDEO("Video"),
    DOCUMENT("Document"),
    STICKER("Sticker"),
    FILE("File"),
}

/**
 * What one conversation decides for itself, next to what it would get otherwise.
 *
 * Every row is a pair: the override, which is null whenever the chat has none, and the global value
 * it falls back to. The screen needs both — a control that only knew the effective value could not
 * tell "set to gpt-4" from "following global, which happens to be gpt-4", and clearing it would be
 * indistinguishable from leaving it alone.
 */
data class ChatSettings(
    val paused: Boolean = false,
    val persona: String? = null,
    val globalPersona: String = "",
    val replyPreset: String? = null,
    val globalReplyPreset: String = "",
    val proactiveLevel: Int? = null,
    val globalProactiveLevel: Int = 0,
    /** Group trigger; empty means she answers everyone, which is the engine's own rule. */
    val groupTrigger: String? = null,
    val globalGroupTrigger: String = "",
) {
    val overrideCount: Int
        get() =
            listOf(
                paused.takeIf { it },
                persona,
                replyPreset,
                proactiveLevel,
                groupTrigger,
            ).count { it != null }

    /** Who is actually answering here: this chat's own choice, or the one every chat inherits. */
    val effectivePersona: String?
        get() =
            persona?.takeIf(String::isNotBlank)
                ?: globalPersona.takeIf(String::isNotBlank)
}

/** One chat, fully loaded. */
data class ChatDetail(
    val chatId: String,
    val title: String,
    val isGroup: Boolean,
    val initial: String,
    val accent: Int,
    /** E.164 display number when WhatsApp exposed one; unlike a LID this is safe for admin actions. */
    val contactNumber: String? = null,
    val entries: List<ChatEntry> = emptyList(),
    val settings: ChatSettings = ChatSettings(),
    val scheduledFollowUp: UiScheduledFollowUp? = null,
    val memoryCharacters: Int = 0,
    val memoryBudget: Int = 0,
    val loading: Boolean = true,
)

data class UiScheduledFollowUp(
    val scheduledAtMs: Long,
    val nextAttemptAtMs: Long,
    val note: String,
)

/** The read side of the chat surfaces, kept apart from the settings-shaped [AppUiController]. */
interface ChatsController {
    /** The landing list, newest first, with the quiet ones already marked. */
    val rows: StateFlow<List<ChatRow>>

    /** The open chat, or `null` when the list is showing. */
    val detail: StateFlow<ChatDetail?>

    /** A real load failure, kept separate from the normal short opening state. */
    val loadError: StateFlow<String?>

    /** Last failed per-chat mutation; never disguised as a successful reload. */
    val operationError: StateFlow<String?>

    /** The running turn in full — trace, draft, failure. Null while the engine is idle. */
    val live: StateFlow<LiveTurn?>

    /**
     * Memory writes running right now, hand-triggered and automatic alike.
     *
     * Every surface that can start one reads this both to show that it is happening and to refuse a
     * second press: a memory write is a model call of up to a minute behind a control that used to
     * give no sign of itself at all.
     */
    val memoryWork: StateFlow<List<MemoryWork>>

    /** Milliseconds the bridge has been continuously up, or `null` when it is not. */
    val uptimeMs: StateFlow<Long?>

    /**
     * Whether the WhatsApp link is deliberately down, and when it is due back.
     *
     * Separate from [uptimeMs] and from the runtime phase on purpose: a link that is
     * asleep is not a link that is broken, and the two used to be indistinguishable on
     * screen — both read "Reconnecting".
     */
    val linkPower: StateFlow<LinkPowerStatus>

    /**
     * Every address that has actually reached a conversation, mapped to what that chat is called.
     *
     * Keyed by digits alone, because the two sides never match as written: a number is typed as
     * `+49 151 …` and the chat it belongs to runs under a LID whose digits are unrelated, joined
     * only by the aliases the chat recorded from the addresses its messages arrived under. This is
     * what lets an access list say "armed, nobody has written yet" instead of showing every entry
     * as if it were already live.
     */
    val bindings: StateFlow<Map<String, String>>

    fun openChat(chatId: String)

    fun closeChat()

    fun clearOperationError()

    /** Creates the empty phone-addressed contact that can receive context before its first message. */
    fun createPendingChat(phoneNumber: String)

    /** Deletes the visible identity group: chat/history/chat memory, never persona/global memory. */
    fun deleteChat(chatId: String)

    /** Adds an Injection at the current end of the log; normal history retention owns its expiry. */
    fun inject(chatId: String, text: String)

    /** Imports a WhatsApp export from the bot phone and resolves its outbound side automatically. */
    fun importWhatsAppChat(chatId: String, uri: Uri)

    /** Rewrites a memory. Takes effect on the next turn, never the one already running. */
    fun editMemory(chatId: String, memoryId: String, text: String)

    fun deleteInjection(chatId: String, injectionId: Long)

    /** Deletes only the currently selected persona's attributable log and memory for this chat. */
    fun deletePersonaChat(chatId: String)

    /**
     * Sets one of this chat's deviations, or clears it when [value] is null.
     *
     * Clearing puts the row back on the global setting *as it will be* — not on a copy of what it
     * is today, which would stop following the global change it was supposed to inherit.
     */
    fun setChatOverride(chatId: String, key: String, value: String?)

    /**
     * Keep the link up: wake a sleeping one, or push an awake one's bedtime out by
     * another listening window.
     *
     * One direction only, unlike the toggle this used to be. A tap that sometimes meant
     * "stay" and sometimes "go" depended on a state the line was not showing clearly
     * enough to act on, and the going direction is the one nobody wants by accident —
     * it is [sleepLinkNow], on a press long enough to be deliberate.
     *
     * Deliberately NOT the same control as Stop. This only moves the socket; the bot
     * stays started, the service stays up and the schedule takes over again on its own.
     * Stop lives in the settings overview and is the one that means stopped.
     */
    fun keepLinkAwake()

    /** Drop the link now, giving up the current listening window with it. */
    fun sleepLinkNow()
}

/** The link power state, as the status line needs it. */
@Immutable
data class LinkPowerStatus(
    val state: LinkState = LinkState.AWAKE,
    /**
     * Wall clock instant the link's current state runs out.
     *
     * While it is down, when it is due back. While it is up on a deadline — a listening
     * window, a hand-started one — when it is due to drop. Null means "no deadline":
     * in default mode the link simply stays up, and there is nothing to count down to.
     */
    val wakeAtMs: Long? = null,
)
