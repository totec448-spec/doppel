package de.totec.doppel.ui

import android.net.Uri
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AdminResult
import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.media.ApprovedMediaKind
import de.totec.doppel.runtime.RuntimeState
import de.totec.doppel.app.AlertKind
import de.totec.doppel.app.RuntimeAlert
import de.totec.doppel.app.RuntimeLimitNotice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class UiSettingKind {
    BOOLEAN,
    ENUM,
    ENUM_SLIDER,
    INTEGER,
    DECIMAL,
    TEXT,
    MULTILINE,
    MODEL,
    TIME,

    /**
     * Two clock values that are really one span — the quiet hours. Not produced by the schema:
     * the two stored settings are merged into a single row by the settings page, because a night
     * is something you drag the ends of, not two times you set one after the other.
     */
    TIME_RANGE,
    TIMEZONE,
}

data class UiSetting(
    val key: String,
    val label: String,
    val description: String,
    val value: String,
    val kind: UiSettingKind,
    val group: String,
    val basic: Boolean,
    val options: List<Pair<String, String>> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val overridden: Boolean = false,
)

data class UiAccessList(
    val key: String,
    val title: String,
    val description: String,
    val entries: List<String>,
    val protectedFirstEntry: Boolean = false,
    /** Entries inherited from a stronger role; deleting them here would be misleading/no-op. */
    val protectedEntries: Set<String> = emptySet(),
    /** Groups are added by name rather than by number, which changes the field and the wording. */
    val groupList: Boolean = false,
)

data class UiActivityEntry(
    val id: Long,
    val timestampMs: Long,
    val level: String,
    val category: String,
    val action: String,
    val message: String,
    val details: String? = null,
)

data class SetupState(
    val bridgeTokenConfigured: Boolean = false,
    val openRouterKeyConfigured: Boolean = false,
    val sttApiKeyConfigured: Boolean = false,
    val ownerNumbers: List<String> = emptyList(),
    val adminNumbers: List<String> = emptyList(),
    val pairingCode: String? = null,
    val pairingCodeExpiresAtMs: Long? = null,
    val whatsAppConnected: Boolean = false,
    /** Monotonic local acknowledgement for write-only credential saves. */
    val credentialRevision: Long = 0,
    val configured: Boolean = false,
    val onboardingCompleted: Boolean = false,
)

data class AppUiState(
    val runtime: RuntimeState = RuntimeState(),
    val setup: SetupState = SetupState(),
    val botEnabled: Boolean = true,
    val model: String = "",
    val persona: String = "human",
    /**
     * Absolute path of the stored picture confirmed as live by the last successful WhatsApp
     * profile mutation. Null while none has been confirmed, or once it has been deleted.
     */
    val profilePicturePath: String? = null,

    /** Current shared mood key, empty when the mood engine is switched off. */
    val mood: String = "",
    val proactiveLevel: Int = 0,
    val safetyLabel: String = "Normal",
    val pendingChats: Int = 0,
    val processedToday: Int = 0,
    val sentToday: Int = 0,
    val accountName: String? = null,
    val accountJid: String? = null,

    /** Privacy switch: phone numbers are painted with their dialling prefix only. */
    val hideSensitiveData: Boolean = false,
    val whatsappConnection: BridgeConnectionState = BridgeConnectionState.NOT_CONFIGURED,
    val limitNotice: RuntimeLimitNotice? = null,

    /** Problems the owner can read and close, newest state of each kind; see [RuntimeAlert]. */
    val alerts: List<RuntimeAlert> = emptyList(),
    val basicSettings: List<UiSetting> = emptyList(),
    val expertSettings: List<UiSetting> = emptyList(),
    val accessLists: List<UiAccessList> = emptyList(),
    val activity: List<UiActivityEntry> = emptyList(),

    /** The controller holds only the requested slice; true while older rows remain in the log. */
    val activityHasMore: Boolean = false,
    val modelOptions: List<Pair<String, String>> = emptyList(),

    /** Why the catalogue is empty, so the picker can tell "still fetching" from "it failed". */
    val modelCatalogStatus: UiCatalogStatus = UiCatalogStatus.IDLE,

    /** The provider/setup reason for [UiCatalogStatus.ERROR]; null in every other state. */
    val modelCatalogError: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * An empty model list has three completely different meanings, and the picker used to render all
 * of them as "No catalogue match" — the same text a mistyped search produces. An operator whose
 * key was missing saw a working search box that simply never found anything.
 */
enum class UiCatalogStatus {
    /** Nothing requested yet; the picker asks on open. */
    IDLE,

    /** A fetch is in flight. */
    LOADING,

    /** The provider answered; the catalogue holds whatever it returned. */
    READY,

    /** The fetch failed. The reason is in [AppUiState.modelCatalogError] and retrying is allowed. */
    ERROR,
}

data class SetupDraft(
    val openRouterKey: String,
    val sttApiKey: String,
    val adminNumber: String,
)

interface AppUiController {
    val state: StateFlow<AppUiState>
    val adminResults: SharedFlow<AdminResult>

    fun startService()

    fun stopService()

    fun reconnect()

    fun setBotEnabled(enabled: Boolean)

    /** Replaces only the OpenRouter credential; WhatsApp linking and access roles stay untouched. */
    fun saveOpenRouterKey(apiKey: String)

    /**
     * Forgets the stored OpenRouter credential.
     *
     * A stored key can never be shown again — it is write-only by design — so replacing one means
     * clearing it first and typing the next one into an empty field.
     */
    fun clearOpenRouterKey()

    /** Starts/reuses the native runtime and requests a code without changing the AI credential. */
    fun linkWhatsApp(phoneNumber: String)

    /** Three-second UI hold only: logs out the linked companion and prepares a fresh pairing store. */
    fun disconnectWhatsApp()

    /** Saves the first-run fields and persists completion of the explanatory setup guide. */
    fun completeOnboarding(draft: SetupDraft)

    fun updateSetting(key: String, rawValue: String)

    fun resetSetting(key: String)

    fun updateAccessList(key: String, entries: List<String>)

    fun refreshModels()

    /** Extends the loaded log window by one database page; a no-op once the log is exhausted. */
    fun loadMoreActivity()

    fun setAutoStart(enabled: Boolean)

    /**
     * Extension point for persona, block, safety and data-management screens.
     * Results contain typed/redacted payloads and are never persisted by UI.
     */
    fun executeAdminAction(action: AdminAction)

    /** Cancels only the confirmed manual outreach currently running for this chat. */
    fun cancelWriteContact(chatId: String)

    /** Cancels only an operator-started global rewrite; chat-memory writes are integrity work. */
    fun cancelGlobalMemory(personaKey: String)

    /** Copies one SAF-selected image into the private approved catalogue. */
    fun importApprovedImage(personaKey: String, displayName: String, uri: Uri)

    /** Normalizes and privately stores several SAF-selected API character references. */
    fun importCharacterReferences(personaKey: String, uris: List<Uri>)

    /** Crops several SAF-selected images to square avatars and stores them for the rotation. */
    fun importProfilePictures(personaKey: String, uris: List<Uri>)

    /**
     * Writes one approved image out to a SAF-selected file so it can be kept, re-used as a
     * reference, or just looked at outside the app.
     */
    fun exportPersonaImage(
        kind: ApprovedMediaKind,
        personaKey: String,
        assetId: String,
        destination: Uri,
    )

    /** Writes database and approved images into one SAF-selected archive. */
    fun exportBotData(uri: Uri)

    /**
     * Replaces database and approved images with the contents of an archive, then ends the process
     * so nothing keeps serving the data that was just swapped out.
     */
    fun importBotData(uri: Uri)

    fun clearMessage()

    /**
     * Closes one alert. It stays closed until the problem says something different, so pressing X
     * on a provider error the retry is about to repeat does not put the same row straight back.
     */
    fun dismissAlert(kind: AlertKind)
}
