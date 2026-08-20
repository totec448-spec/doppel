package de.totec.doppel.commands

import java.util.Arrays
import java.util.UUID

enum class AdminOrigin {
    APP,
    CHAT,
    NOTIFICATION,
}

data class AdminContext(
    val origin: AdminOrigin,
    val actorId: String,
    val chatId: String?,
    val isGroup: Boolean,
)

data class AdminRequest(
    val context: AdminContext,
    val action: AdminAction,
)

/**
 * One narrow application port shared by chat commands, Compose and notification
 * controls. Implementations own persistence, transport and runtime coordination;
 * this command package only maps text to typed actions and renders results.
 *
 * [execute] suspends because several actions are transport round-trips — a memory write, a
 * block, an outreach turn. It used to be a plain function, so the adapter bridged the gap with
 * `runBlocking`, and the caller that pays for that is the inbound frame loop: a WhatsApp admin
 * command held its thread for the whole duration of the call, which for a memory write is a
 * model round-trip. Suspending hands the thread back and makes the work cancellable with the
 * runtime that owns it.
 */
fun interface AdminActions {
    suspend fun execute(request: AdminRequest): AdminResult
}

enum class AccessList {
    ALLOW,
    GROUP_ALLOW,
    ADMIN,
}

enum class AccessOperation {
    ADD,
    REMOVE,
    REPLACE,
}

sealed interface WipeTarget {
    data class Persona(
        val key: String,
    ) : WipeTarget

    data object All : WipeTarget

    fun label(): String = when (this) {
        is Persona -> "Persona $key"
        All -> "alle Personas"
    }
}

/**
 * Mutable secret carrier whose [toString] is permanently redacted. Command code
 * closes it immediately after the synchronous port call and never includes it in
 * a result or exception message.
 */
class SecretInput private constructor(
    chars: CharArray,
) : AutoCloseable {
    private var value: CharArray? = chars

    /**
     * Gives the synchronous adapter a short-lived working buffer. The exact
     * buffer passed to [block] is zeroed before this method returns, even when
     * the adapter throws.
     */
    fun <T> use(block: (CharArray) -> T): T {
        val workingCopy = synchronized(this) {
            checkNotNull(value) { "Secret input was already cleared" }.copyOf()
        }
        return try {
            block(workingCopy)
        } finally {
            Arrays.fill(workingCopy, '\u0000')
        }
    }

    override fun close() {
        synchronized(this) {
            value?.let { Arrays.fill(it, '\u0000') }
            value = null
        }
    }

    override fun toString(): String = "[REDACTED]"

    companion object {
        fun from(value: String): SecretInput = SecretInput(value.toCharArray())
    }
}

sealed interface AdminAction {
    data object Help : AdminAction
    data object Status : AdminAction
    data object MoodStatus : AdminAction
    data object Meta : AdminAction

    data object ListSettings : AdminAction

    data class GetSetting(
        val key: String,
    ) : AdminAction

    /**
     * The adapter must validate and commit the complete map atomically. An
     * invalid member must leave every setting unchanged.
     */
    data class SetSettings(
        val changes: Map<String, String>,
    ) : AdminAction

    data object ResetAllGlobalSettings : AdminAction

    /**
     * Pauses ordinary AI replies and proactive work only. The adapter must keep
     * the foreground runtime, WhatsApp connection and admin path alive.
     */
    data object PauseBot : AdminAction

    data object ResumeBot : AdminAction

    data class ListAccess(
        val list: AccessList,
    ) : AdminAction

    /** REPLACE explicitly permits an empty [entries] list. */
    data class ChangeAccess(
        val list: AccessList,
        val operation: AccessOperation,
        val entries: List<String>,
    ) : AdminAction

    data object ApiKeyStatus : AdminAction

    class SetApiKey(
        val secret: SecretInput,
    ) : AdminAction {
        override fun toString(): String = "SetApiKey(secret=[REDACTED])"
    }

    data object ClearApiKey : AdminAction

    /**
     * Resets history, proactive state and sent-image deduplication for
     * [chatId], while retaining memory. When [resetGlobalSettings] is true, the
     * adapter must additionally reset the real global settings scope, not a
     * legacy chat-local scope, in the same transaction.
     */
    data class ResetChat(
        val chatId: String,
        val resetGlobalSettings: Boolean,
    ) : AdminAction

    /** Clears only the stored chat-memory prose for the current persona thread. */
    data class ClearChatMemory(
        val chatId: String,
    ) : AdminAction

    /**
     * Clears history, per-chat/global persona memory, proactive state and image
     * dedup for the immutable target. Definitions, assignments, settings and
     * access lists survive.
     */
    data class Wipe(
        val target: WipeTarget,
    ) : AdminAction

    /** Lists only personas that currently own conversation history or memory. */
    data object ListPersonaData : AdminAction

    data object ListPersonas : AdminAction

    /**
     * [voice] null means "leave the persona's voice alone", which is what the chat command and
     * every other caller that has no opinion about it want. Only the settings picker sends one.
     */
    data class UpsertPersona(
        val key: String,
        val prompt: String,
        val name: String? = null,
        val voice: String? = null,
    ) : AdminAction

    data class DeletePersona(
        val key: String,
    ) : AdminAction

    data class AssignPersona(
        val key: String,
        val targetChatId: String,
    ) : AdminAction

    data class UnassignPersona(
        val targetChatId: String,
    ) : AdminAction

    data class PersonaImages(
        val key: String,
    ) : AdminAction

    data class DeletePersonaImage(
        val key: String,
        val assetId: String,
    ) : AdminAction

    data class PersonaImageReferences(
        val key: String,
    ) : AdminAction

    data class DeletePersonaImageReference(
        val key: String,
        val assetId: String,
    ) : AdminAction

    data class DeleteAllPersonaImageReferences(
        val key: String,
    ) : AdminAction

    data class PersonaProfilePictures(
        val key: String,
    ) : AdminAction

    data class DeletePersonaProfilePicture(
        val key: String,
        val assetId: String,
    ) : AdminAction

    /**
     * Explicit owner-triggered delivery of an already approved asset. [assetId]
     * is an opaque catalogue identifier, never a URI or filesystem path.
     */
    data class SendPersonaImage(
        val key: String,
        val assetId: String,
        val targetChatId: String,
        val caption: String? = null,
        val requestId: String = UUID.randomUUID().toString(),
    ) : AdminAction

    /** Local-only, safety-checked end-to-end transport diagnostic. */
    data class SendManualText(
        val targetChatId: String,
        val text: String,
        val requestId: String = UUID.randomUUID().toString(),
    ) : AdminAction

    data object VoiceStatus : AdminAction
    data object ListVoices : AdminAction

    data class SetDefaultVoice(
        val voice: String,
    ) : AdminAction

    data object ListBlocks : AdminAction

    data class BlockContact(
        val number: String,
        val reason: String,
    ) : AdminAction

    data class UnblockContact(
        val number: String,
    ) : AdminAction

    data object ProactiveStatus : AdminAction

    /** Every contact the bot may be told to approach: past 1:1 chats plus the allowlist. */
    data object ListProactiveContacts : AdminAction

    /**
     * Sends one proactive message to [target] right now, bypassing the schedule but not the
     * safety layer. [confirmation] must repeat the token from the preceding dry run.
     *
     * [note] is what the operator wants said, in their words. It is handed to the model as her
     * own intention rather than as a dictated text — she still writes the message herself, in
     * her voice, which is the whole reason the turn is generated instead of simply sent.
     */
    data class WriteContactNow(
        val target: String,
        val confirmation: String? = null,
        val note: String? = null,
    ) : AdminAction

    data class GetProactiveOverride(
        val target: String,
    ) : AdminAction

    data class SetGlobalProactiveLevel(
        val level: Int,
    ) : AdminAction

    data class SetProactiveOverride(
        val target: String,
        val level: Int,
    ) : AdminAction

    data class ClearProactiveOverride(
        val target: String,
    ) : AdminAction

    data object SafetyStatus : AdminAction

    data class SafetyEvents(
        val limit: Int = 12,
    ) : AdminAction

    data object RefreshSafety : AdminAction

    data class AcknowledgeSafetyLock(
        val id: Long,
    ) : AdminAction

    data class ClearSafetyLock(
        val id: Long,
    ) : AdminAction

    data class HoldSafety(
        val durationMs: Long,
        val reason: String,
    ) : AdminAction

    data object Reachout : AdminAction

    /** Catalogue of stored memory documents, newest write first. Never carries a full body. */
    data class ListMemories(
        val limit: Int = 120,
    ) : AdminAction

    /** Loads the complete text of exactly one memory document for local display. */
    data class OpenMemory(
        val scope: MemoryScope,
        val id: String,
    ) : AdminAction

    /** Replaces only the editable prose while preserving facts, fold pointers and cadence state. */
    data class UpdateMemory(
        val scope: MemoryScope,
        val id: String,
        val expectedRevision: Long,
        val summary: String,
    ) : AdminAction

    /**
     * Consolidates this chat's memory now instead of at the next cadence point. Everything else is
     * the ordinary refresh, so the write also counts towards the persona's cross-chat synthesis.
     */
    data class CreateChatMemory(
        val chatId: String,
    ) : AdminAction

    /**
     * Rebuilds one persona's cross-chat memory now, past the every-third-chat-write cadence. The
     * persona must already own at least one chat memory — there is nothing to synthesize otherwise.
     */
    data class CreateGlobalMemory(
        val personaKey: String,
    ) : AdminAction

    /**
     * Empties one memory document without touching where the bot has read up to.
     *
     * Only the text goes. The consolidation pointer and the revision the anchored history window
     * hangs off both stay, so the next memory write starts at exactly the message it would have
     * started at anyway: this forgets what she wrote down, it does not rewind the chat.
     */
    data class DeleteMemory(
        val scope: MemoryScope,
        val id: String,
    ) : AdminAction
}

enum class MemoryScope {
    /** One persona thread inside one chat. */
    CHAT,

    /** The synthesized cross-chat memory of one persona. */
    PERSONA,
}

sealed interface AdminResult {
    data class Success(
        val payload: AdminPayload = AdminPayload.Empty,
    ) : AdminResult

    data class Invalid(
        val field: String?,
        val reason: String,
    ) : AdminResult

    data class NotFound(
        val subject: String,
    ) : AdminResult

    data class Denied(
        val reason: String,
    ) : AdminResult

    data class Failure(
        val reason: String,
        val retryable: Boolean = false,
    ) : AdminResult
}

sealed interface AdminPayload {
    data object Empty : AdminPayload

    data class Text(
        val value: String,
    ) : AdminPayload

    data class Mood(
        val enabled: Boolean,
        val name: String?,
    ) : AdminPayload

    data class HelpHeader(
        val title: String = "WhatsApp-Bot – Admin-Befehle",
        val introduction: String = "",
    ) : AdminPayload

    data class Setting(
        val setting: SettingSnapshot,
    ) : AdminPayload

    data class Settings(
        val settings: List<SettingSnapshot>,
    ) : AdminPayload

    data class SettingsChanged(
        val normalizedValues: Map<String, String>,
    ) : AdminPayload

    data class AccessEntries(
        val list: AccessList,
        val entries: List<String>,
    ) : AdminPayload

    data class AccessChanged(
        val changed: Int,
        val total: Int,
    ) : AdminPayload

    data class SecretStatus(
        val configured: Boolean,
        val lastFour: String?,
        val source: String,
        val overrideActive: Boolean,
    ) : AdminPayload {
        init {
            require(lastFour == null || (lastFour.length <= 4 && lastFour.none(Char::isWhitespace))) {
                "Only a four-character secret suffix may cross the admin port"
            }
        }

        fun maskedValue(): String {
            if (!configured) return "nicht konfiguriert"
            return "sk-or-••••${lastFour.orEmpty()}"
        }
    }

    data class Personas(
        val activeKey: String,
        val entries: List<PersonaSummary>,
        val assignments: List<PersonaAssignment> = emptyList(),
    ) : AdminPayload

    data class Voices(
        val entries: List<VoiceOption>,
    ) : AdminPayload

    data class ImageLocation(
        val personaKey: String,
        val location: String,
    ) : AdminPayload

    data class ImageAssets(
        val personaKey: String,
        val entries: List<MediaAssetSummary>,
    ) : AdminPayload

    data class ImageReferences(
        val personaKey: String,
        val entries: List<MediaAssetSummary>,
    ) : AdminPayload

    /**
     * The persona's own faces, in the order the rotation walks them. [live] is the one the last
     * successful WhatsApp mutation put on the account, so a list without it is a list whose
     * current picture was deleted.
     */
    data class ProfilePictures(
        val personaKey: String,
        val entries: List<MediaAssetSummary>,
        val live: String? = null,
    ) : AdminPayload

    data class ImageSent(
        val personaKey: String,
        val assetId: String,
        val targetChatId: String,
        val transportMessageId: String,
    ) : AdminPayload

    data class Blocks(
        val entries: List<BlockedContact>,
    ) : AdminPayload

    data class BlockChanged(
        val number: String,
        val whatsappConfirmed: Boolean,
        val existed: Boolean,
    ) : AdminPayload

    data class SafetyState(
        val summary: String,
        val activeLocks: List<SafetyLock>,
    ) : AdminPayload

    data class ProactiveOverride(
        val target: String,
        val level: Int?,
        val globalLevel: Int,
    ) : AdminPayload

    data class ProactiveContacts(
        val globalLevel: Int,
        val contacts: List<ProactiveContact>,
    ) : AdminPayload

    /**
     * Result of [AdminAction.WriteContactNow]. Without a matching confirmation the action only
     * reports what it would do, and [confirmation] carries the token needed to actually send.
     */
    data class WriteContactOutcome(
        val target: String,
        val displayName: String?,
        val sent: Boolean,
        val confirmation: String? = null,
        val detail: String? = null,
    ) : AdminPayload

    data class WipeSummary(
        val target: WipeTarget,
        val affectedThreads: Int,
    ) : AdminPayload

    data class PersonaData(
        val entries: List<PersonaDataSummary>,
    ) : AdminPayload

    data class Memories(
        val entries: List<MemorySummary>,
    ) : AdminPayload

    data class MemoryDocument(
        val summary: MemorySummary,
        val body: String,
        val facts: List<String>,
    ) : AdminPayload

    /**
     * A memory file was just written by hand rather than at the next cadence point. Its own type
     * because the catalogue the user is looking at is now stale, exactly like [BlockChanged].
     */
    data class MemoryWritten(
        val detail: String,
    ) : AdminPayload
}

/**
 * One memory document as the UI sees it. [title] is already human-readable; [preview] is a short
 * excerpt so the list can be rendered without loading any body.
 */
data class MemorySummary(
    val scope: MemoryScope,
    val id: String,
    val title: String,
    val subtitle: String,
    val characters: Int,
    val sourceMessageCount: Int,
    val revision: Long,
    val updatedAtMs: Long,
    val hasFacts: Boolean,
    val preview: String,
)

data class SettingSnapshot(
    val key: String,
    val value: String,
    val defaultValue: String,
    val description: String,
    val overridden: Boolean,
)

/** [voice] is the voice this persona speaks with, or null when it inherits the global one. */
data class PersonaSummary(
    val key: String,
    val displayName: String,
    val description: String,
    val builtIn: Boolean,
    val voice: String? = null,
)

data class PersonaDataSummary(
    val key: String,
    val displayName: String,
)

data class PersonaAssignment(
    val chatId: String,
    val personaKey: String,
)

/**
 * One row of the proactivity roster. [level] is what actually applies to the contact, while
 * [overridden] distinguishes a per-contact value from the inherited global one.
 */
data class ProactiveContact(
    val chatId: String,
    val displayName: String?,
    val level: Int,
    val overridden: Boolean,
    val allowlisted: Boolean,
    val hasHistory: Boolean,
    val lastMessageAtMs: Long?,
    val blocked: Boolean,
)

data class SafetyLock(
    val id: Long,
    val label: String,
    val expiresAtMs: Long?,
)

data class VoiceOption(
    val name: String,
    val description: String,
)

data class MediaAssetSummary(
    val assetId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAtMs: Long,
)

/**
 * [source] is a code, not a sentence: the same list is read out in the app (English) and in the chat
 * commands (German), so the wording belongs to each surface rather than to the payload.
 */
data class BlockedContact(
    val number: String,
    val reason: String,
    val source: BlockSource,
)

enum class BlockSource {
    /** WhatsApp itself reports this contact as blocked. */
    REMOTE,

    /** Set here — in the app or by an admin command. */
    LOCAL,
}
