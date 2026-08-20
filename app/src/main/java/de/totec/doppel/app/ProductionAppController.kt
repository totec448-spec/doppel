// Onboarding completion must inspect the synchronous commit result.
@file:Suppress("UseKtx")

package de.totec.doppel.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.totec.doppel.commands.AccessList
import de.totec.doppel.commands.AccessOperation
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AdminContext
import de.totec.doppel.commands.AdminOrigin
import de.totec.doppel.commands.AdminPayload
import de.totec.doppel.commands.AdminRequest
import de.totec.doppel.commands.AdminResult
import de.totec.doppel.commands.normalizePhoneNumber
import de.totec.doppel.data.BotDataArchive
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.BotDatabaseLimits
import de.totec.doppel.engine.MoodEngine
import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.domain.BridgeStatus
import de.totec.doppel.media.ApprovedMediaKind
import de.totec.doppel.media.ProfilePictureRotator
import de.totec.doppel.integration.RuntimeBridgeControl
import de.totec.doppel.runtime.RuntimePhase
import de.totec.doppel.runtime.RuntimeServiceController
import de.totec.doppel.runtime.RuntimeState
import de.totec.doppel.runtime.RuntimeStateStore
import de.totec.doppel.security.SecretStore
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.AppSettingsSchema
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.BotSettingsSchema
import de.totec.doppel.settings.SettingValue
import de.totec.doppel.settings.SettingValueType
import de.totec.doppel.settings.SettingsRepository
import de.totec.doppel.settings.SettingsSnapshot
import de.totec.doppel.settings.SettingsValidationException
import de.totec.doppel.settings.TtsVoiceCatalog
import de.totec.doppel.settings.parseBooleanInput
import de.totec.doppel.settings.parseSettingInput
import de.totec.doppel.transport.BridgeActionException
import de.totec.doppel.ui.AppUiController
import de.totec.doppel.ui.AppUiState
import de.totec.doppel.ui.SetupDraft
import de.totec.doppel.ui.SetupState
import de.totec.doppel.ui.UiAccessList
import de.totec.doppel.ui.UiActivityEntry
import de.totec.doppel.ui.UiCatalogStatus
import java.io.Closeable
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thin Compose-facing controller over the canonical application services.
 *
 * All blocking SQLite/Keystore/provider work runs on Dispatchers.IO. The
 * controller has no polling loop: state changes are driven by settings,
 * runtime, bridge, activity invalidations and explicit user operations.
 */
class ProductionAppController(
    context: Context,
    private val graph: BotAppGraph,
) : AppUiController, Closeable {
    private val applicationContext = context.applicationContext
    private val onboardingPreferences =
        applicationContext.getSharedPreferences(
            ONBOARDING_PREFERENCES,
            Context.MODE_PRIVATE,
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeOperations = AtomicInteger(0)
    private val modelCatalogLoading = AtomicBoolean(false)
    private val pairingAttempt = PairingAttemptGate()
    private val overlay = MutableStateFlow(ControllerOverlay())
    private val activity = MutableStateFlow(ActivityFeed())

    /** How many log rows the user has asked for; grows only via [loadMoreActivity]. */
    private val activityWindow = AtomicInteger(ACTIVITY_PAGE_SIZE)
    private val activityLoad = Mutex()
    private val profilePictureStateFile =
        java.io.File(applicationContext.filesDir, ProfilePictureRotator.STATE_FILE_NAME)
    @Volatile private var profilePictureProjectionStamp = Long.MIN_VALUE
    @Volatile private var profilePictureProjection: String? = null
    private val mutableState = MutableStateFlow(buildState())
    private val mutableAdminResults = MutableSharedFlow<AdminResult>(extraBufferCapacity = 8)
    private val globalMemoryJobs = ConcurrentHashMap<String, Job>()
    private val outreachJobs = ConcurrentHashMap<String, Job>()

    override val state = mutableState.asStateFlow()
    override val adminResults: SharedFlow<AdminResult> = mutableAdminResults.asSharedFlow()

    init {
        reconcileAutoStart()

        // Projecting the settings schema and re-reading the activity log is by far the most
        // expensive thing this class does, and none of it is observable while the app is in the
        // background. Both pipelines therefore run only while Compose actually collects [state];
        // the bot itself keeps running untouched.
        scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (!observed) return@collectLatest
                    coroutineScope {
                        refreshActivity()
                        graph.controls.requestMetricsRefresh()
                        launch {
                            graph.controls.activityRevision.drop(1).collectLatest {
                                // Activity can emit several boundary records for one turn.
                                // `collectLatest` restarts this window on each of them, so a burst
                                // costs Compose one DB snapshot instead of one per log row —
                                // plain `collect` only conflated the revisions that arrived while
                                // a read was already running, and paid the full window plus a full
                                // re-read for every one that did not.
                                delay(ACTIVITY_REFRESH_BATCH_MS)
                                refreshActivity()
                            }
                        }
                        combine(
                            graph.settings.changes,
                            RuntimeStateStore.state,
                            combine(
                                overlay,
                                graph.controls.limitNotice,
                                graph.controls.alerts,
                            ) { value, limit, alerts ->
                                Triple(value, limit, alerts)
                            },
                            graph.controls.metrics,
                            activity,
                        ) { settings, runtime, presentation, metrics, activityFeed ->
                            buildState(
                                settings,
                                runtime,
                                presentation.first,
                                metrics,
                                activityFeed,
                                presentation.second,
                                presentation.third,
                            )
                        }.collect(mutableState::emit)
                    }
                }
        }
        scope.launch {
            RuntimeBridgeControl.pairingCode.collect { code ->
                overlay.update {
                    it.copy(
                        pairingCode = code,
                        pairingCodeExpiresAtMs =
                            it.pairingCodeExpiresAtMs.takeIf { _ -> code != null },
                    )
                }
            }
        }
        scope.launch {
            RuntimeBridgeControl.status.collect { status ->
                when (status.connection) {
                    BridgeConnectionState.CONNECTED -> {
                        onboardingPreferences.edit().putBoolean(KEY_WHATSAPP_LINK_OBSERVED, true).apply()
                        overlay.update { current ->
                            current.copy(
                                bridgeStatus = status,
                                pairingCode = null,
                                pairingCodeExpiresAtMs = null,
                                message =
                                    if (!onboardingCompleted()) {
                                        "WhatsApp connected. Add an OpenRouter key or finish setup."
                                    } else {
                                        current.message
                                    },
                            )
                        }
                    }

                    BridgeConnectionState.PAIRING -> {
                        // Pairing with no active code is the native source of truth for a logged-out
                        // device. Transient post-pair 515/backoff states deliberately preserve the
                        // successful observation so setup cannot fall back to Send code.
                        if (status.pairingCode == null && RuntimeBridgeControl.pairingCode.value == null) {
                            onboardingPreferences.edit().putBoolean(KEY_WHATSAPP_LINK_OBSERVED, false).apply()
                        }
                        overlay.update { it.copy(bridgeStatus = status) }
                    }

                    else -> overlay.update { it.copy(bridgeStatus = status) }
                }
            }
        }
        // The two standing conditions nothing else reports as a problem: without a key no answer
        // can be produced at all, and a link the runtime has given up on will not come back by
        // itself. Both are conditions rather than events, so they are watched rather than pushed —
        // and both withdraw themselves the moment the condition ends.
        scope.launch {
            combine(
                graph.settings.changes,
                RuntimeStateStore.state,
                overlay
                    .map { it.credentialRevision to it.bridgeStatus.connection }
                    .distinctUntilChanged(),
            ) { _, runtime, _ -> runtime }
                .collect { runtime -> publishStandingAlerts(runtime) }
        }
        // A limit notice has no X on purpose: a rule still in force must not be dismissible. That
        // only holds if it withdraws itself, and it did not. It is a record of the last refusal
        // rather than a live reading, so a settings change can lift the cap underneath it without
        // anything noticing — switching the warm-up off raises the hourly budget without touching
        // the hourly setting at all, which left "Limit reached" on screen for hours against a cap
        // that was no longer being hit. Dropping it on any settings change is safe because the next
        // refusal republishes it immediately; keeping it is not, because there is no way to remove
        // a stale one by hand.
        scope.launch {
            graph.settings.changes.collect { graph.controls.publishLimit(null) }
        }
    }

    private fun publishStandingAlerts(runtime: RuntimeState) {
        if (graph.secrets.isReadable(SecretStore.OPENROUTER_API_KEY)) {
            graph.controls.clearAlert(AlertKind.API_KEY)
        } else {
            graph.controls.publishAlert(
                RuntimeAlert(
                    kind = AlertKind.API_KEY,
                    title = "OpenRouter API key missing",
                    detail = "Nothing can be answered until a key is stored · open settings",
                    settingKey = SettingsTargets.OPENROUTER_API_KEY,
                    dismissible = false,
                ),
            )
        }
        val linkObserved =
            RuntimeBridgeControl.status.value.connection == BridgeConnectionState.CONNECTED ||
                onboardingPreferences.getBoolean(KEY_WHATSAPP_LINK_OBSERVED, false)
        // Title and destination together, because a red row the owner cannot act on is only half a
        // report: the three link states are all answered by the pairing panel, and an engine that is
        // not running is answered by the Start control on the settings overview a null target lands
        // on.
        val linkAlert: Pair<String, String?>? =
            if (!linkObserved) {
                "WhatsApp not connected" to SettingsTargets.LINK_WHATSAPP
            } else {
                when (runtime.phase) {
                    RuntimePhase.ATTENTION_REQUIRED ->
                        "WhatsApp needs to be linked again" to SettingsTargets.LINK_WHATSAPP
                    RuntimePhase.ERROR ->
                        "WhatsApp not connected" to SettingsTargets.LINK_WHATSAPP
                    RuntimePhase.ENGINE_UNAVAILABLE ->
                        "The bot engine is not running" to null
                    else -> null
                }
            }
        if (linkAlert == null) {
            graph.controls.clearAlert(AlertKind.LINK)
        } else {
            val (linkTitle, linkTarget) = linkAlert
            graph.controls.publishAlert(
                RuntimeAlert(
                    kind = AlertKind.LINK,
                    title = linkTitle,
                    settingKey = linkTarget,
                    // No timestamp on a standing condition: it is republished on every settings
                    // change, and a fresh clock reading each time would make an identical alert a
                    // different object and push a pointless state emission through the whole UI.
                    detail = runtime.detail?.take(160) ?: "Nothing is being received or sent",
                    dismissible = false,
                ),
            )
        }
    }

    override fun startService() {
        if (!currentSetup().configured) {
            showMessage("Finish setting up the bridge, credentials and owner first.")
            return
        }
        try {
            RuntimeServiceController.start(applicationContext)
            logControl("service_start", "Bot service started.")
        } catch (_: RuntimeException) {
            showMessage("Android refused the start. Open the app and try again.")
        }
    }

    override fun stopService() {
        try {
            RuntimeServiceController.stop(applicationContext)
            logControl("service_stop", "Bot service stopped.")
        } catch (_: RuntimeException) {
            showMessage("The service could not be stopped cleanly.")
        }
    }

    override fun reconnect() {
        if (!currentSetup().configured) {
            showMessage("The bridge is not fully set up yet.")
            return
        }
        try {
            RuntimeServiceController.reconnect(applicationContext)
            logControl("service_reconnect", "Bridge reconnect requested.")
        } catch (_: RuntimeException) {
            showMessage("The reconnect could not be started.")
        }
    }

    override fun setBotEnabled(enabled: Boolean) {
        launchOperation {
            val action = if (enabled) AdminAction.ResumeBot else AdminAction.PauseBot
            val sharedActions = graph.controls.currentAdminActions()
            if (sharedActions != null) {
                requireAdminSuccess(sharedActions.execute(adminRequest(action)))
                graph.settings.reload()
            } else {
                graph.settings.updateGlobal(
                    mapOf(BotSettingKeys.ENABLED to SettingValue.Bool(enabled)),
                )
            }
            appendActivity(
                action = if (enabled) "bot_resumed" else "bot_paused",
                summary =
                    if (enabled) {
                        "Automatic replies are on."
                    } else {
                        "Automatic replies are paused; admin control stays live."
                    },
            )
        }
    }

    override fun saveOpenRouterKey(apiKey: String) {
        launchOperation(errorMessage = "The OpenRouter key could not be saved.") {
            val normalized = apiKey.trim()
            require(normalized.isNotEmpty()) { "Enter an OpenRouter API key." }
            graph.secrets.put(SecretStore.OPENROUTER_API_KEY, normalized)
            overlay.update {
                it.copy(
                    credentialRevision = it.credentialRevision + 1,
                    message = "OpenRouter key saved.",
                )
            }
            appendActivity("openrouter_key_saved", "The protected OpenRouter key was replaced.")
        }
    }

    override fun clearOpenRouterKey() {
        launchOperation(errorMessage = "The OpenRouter key could not be removed.") {
            graph.secrets.remove(SecretStore.OPENROUTER_API_KEY)
            overlay.update {
                it.copy(
                    credentialRevision = it.credentialRevision + 1,
                    message = "OpenRouter key removed.",
                )
            }
            appendActivity("openrouter_key_cleared", "The protected OpenRouter key was removed.")
        }
    }

    override fun linkWhatsApp(phoneNumber: String) {
        if (!pairingAttempt.tryEnter()) {
            showMessage("A WhatsApp pairing request is already in progress.")
            return
        }
        val job = launchOperation(errorMessage = "The WhatsApp pairing code could not be fetched.") {
            requirePhoneNumber(phoneNumber, "Phone number")
            if (!graph.secrets.isReadable(SecretStore.BRIDGE_TOKEN)) {
                graph.secrets.put(SecretStore.BRIDGE_TOKEN, generateBridgeToken())
            }
            overlay.update { it.copy(credentialRevision = it.credentialRevision + 1) }
            // Pairing is an explicit foreground action. In LOW mode or quiet hours the
            // bridge may intentionally be asleep, so give it one bounded listening window
            // before rebuilding the runtime. The feed queues this request even when this
            // tap is what creates the service and its power controller does not exist yet.
            // When the window ends, the ordinary policy re-arms the alarm and returns to
            // LOW/quiet-hours sleep; this does not disable power saving.
            graph.linkPower.requestWake()
            // Pairing is an explicit recovery edge, not an ordinary idempotent Start. A failed
            // native bind leaves the runtime loop deliberately parked in ATTENTION_REQUIRED;
            // Start sees that still-active job and cannot replace it. Reconnect cancels the parked
            // loop and native core first, while still degrading to a normal foreground start on a
            // fresh install where no runtime has been requested yet.
            RuntimeServiceController.reconnect(applicationContext)
            requestPairingCodeNow(phoneNumber)
        }
        job.invokeOnCompletion { pairingAttempt.leave() }
    }

    private suspend fun persistSetup(draft: SetupDraft) {
            val admin =
                draft.adminNumber.trim().takeIf(String::isNotEmpty)?.let {
                    requirePhoneNumber(it, "Admin number")
                }
            val openRouterKey = draft.openRouterKey.trim()
            val sttApiKey = draft.sttApiKey.trim()

            // The bridge only ever listens on Android loopback now, so its token is an
            // internal secret rather than something the operator has to type in and keep in
            // sync with a second machine. It is minted once and then simply kept.
            if (!graph.secrets.isReadable(SecretStore.BRIDGE_TOKEN)) {
                graph.secrets.put(SecretStore.BRIDGE_TOKEN, generateBridgeToken())
            }
            if (openRouterKey.isNotEmpty()) {
                graph.secrets.put(SecretStore.OPENROUTER_API_KEY, openRouterKey)
            }
            if (sttApiKey.isNotEmpty()) {
                graph.secrets.put(SecretStore.STT_API_KEY, sttApiKey)
            }

            val snapshot = graph.settings.snapshot()
            val owners = snapshot.appStringList(AppSettingKeys.OWNER_NUMBERS)
            val admins =
                (snapshot.appStringList(AppSettingKeys.ADMIN_NUMBERS) + listOfNotNull(admin))
                    .distinct()
            val allowed =
                (snapshot.appStringList(AppSettingKeys.ALLOWLIST_NUMBERS) + listOfNotNull(admin))
                    .distinct()
            graph.settings.updateAppControls(
                mapOf(
                    AppSettingKeys.OWNER_NUMBERS to SettingValue.StringList.of(owners),
                    AppSettingKeys.ADMIN_NUMBERS to SettingValue.StringList.of(admins),
                    AppSettingKeys.ALLOWLIST_NUMBERS to SettingValue.StringList.of(allowed),
                ),
            )
            overlay.update {
                it.copy(
                    credentialRevision = it.credentialRevision + 1,
                    message = "Setup saved securely.",
                )
            }
            appendActivity(
                "setup_saved",
                "Phone transport and protected credentials are set up.",
            )
    }

    override fun completeOnboarding(draft: SetupDraft) {
        launchOperation(errorMessage = "Setup could not be completed.") {
            persistSetup(draft)
            markOnboardingCompleted()
            overlay.update { it.copy(credentialRevision = it.credentialRevision + 1) }
            logControl(
                action = "onboarding_completed",
                summary = "Setup guide completed.",
            )
        }
    }

    private suspend fun requestPairingCodeNow(phoneNumber: String) {
        val number = phoneNumber.filter(Char::isDigit)
        require(number.length in 6..15) { "WhatsApp number must have 6 to 15 digits." }
        val result = RuntimeBridgeControl.requestPairingCode(number)
        overlay.update {
            it.copy(
                pairingCode = result.code,
                pairingCodeExpiresAtMs = result.expiresAtMs,
                message = "Pairing code loaded.",
            )
        }
        result.expiresAtMs?.let { expiresAt ->
            scope.launch {
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
                overlay.update { current ->
                    if (
                        current.pairingCode == result.code &&
                            current.pairingCodeExpiresAtMs == expiresAt
                    ) {
                        current.copy(pairingCode = null, pairingCodeExpiresAtMs = null)
                    } else {
                        current
                    }
                }
            }
        }
        appendActivity("pairing_requested", "A WhatsApp pairing code was requested.")
    }

    override fun updateSetting(key: String, rawValue: String) {
        if (key == AppSettingKeys.AUTOSTART) {
            val value = parseBooleanInput(rawValue)
            if (value == null) {
                showMessage("Not a valid on/off value.")
            } else {
                setAutoStart(value)
            }
            return
        }
        launchOperation(
            errorMessage = "The setting could not be applied.",
        ) {
            when {
                key in BotSettingsSchema.byKey -> {
                    val spec = BotSettingsSchema.requireSpec(key)
                    // This screen is already the trusted local admin surface. Writing the typed
                    // value directly avoids serialising it into an AdminAction, parsing it a
                    // second time, then reloading the same repository. The durable repository is
                    // still the single mutation point and repairs TTS voice compatibility there.
                    val parsed = parseSettingInput(spec, rawValue)
                    val (updates, ttsCatalogVoices) =
                        if (key == BotSettingKeys.TTS_MODEL && parsed is SettingValue.Text) {
                            val currentVoice =
                                graph.settings.snapshot().text(BotSettingKeys.TTS_VOICE)
                            val reportedVoices =
                                overlay.value.models
                                    .firstOrNull { it.id == parsed.value }
                                    ?.supportedVoices
                            Pair(
                                mapOf(
                                    key to parsed,
                                    BotSettingKeys.TTS_VOICE to
                                        SettingValue.Text(
                                            TtsVoiceCatalog.resolve(
                                                model = parsed.value,
                                                requested = currentVoice,
                                                catalogVoices = reportedVoices,
                                            ),
                                        ),
                                ),
                                reportedVoices,
                            )
                        } else {
                            Pair(mapOf(key to parsed), null)
                        }
                    graph.settings.updateGlobal(updates, ttsCatalogVoices)
                }

                key in AppSettingsSchema.byKey -> {
                    val spec = AppSettingsSchema.requireSpec(key)
                    check(spec.valueType != SettingValueType.SECRET_REFERENCE) {
                        "Credentials are only changed through the secure setup flow."
                    }
                    check(spec.valueType != SettingValueType.STRING_LIST) {
                        "This list is edited on the Access screen."
                    }
                    graph.settings.updateAppControls(
                        mapOf(
                            key to
                                parseSettingInput(
                                    spec,
                                    MegabyteSettings.toStored(key, rawValue),
                                ),
                        ),
                    )
                }

                else -> error("Unknown setting.")
            }
            appendActivity("setting_changed", "Setting \"${safeLabel(key)}\" was changed.")
        }
    }

    override fun resetSetting(key: String) {
        launchOperation(
            errorMessage = "The setting could not be reset.",
        ) {
            when {
                key in BotSettingsSchema.byKey ->
                    graph.settings.resetGlobalToDefaults(setOf(key))
                key in AppSettingsSchema.byKey -> {
                    graph.settings.resetAppControlsToDefaults(setOf(key))
                    if (key == AppSettingKeys.AUTOSTART) {
                        syncRuntimeAutoStart(
                            graph.settings.snapshot().appBoolean(AppSettingKeys.AUTOSTART),
                        )
                    }
                }
                else -> error("Unknown setting.")
            }
            appendActivity("setting_reset", "Setting \"${safeLabel(key)}\" is back on its default.")
        }
    }

    override fun updateAccessList(key: String, entries: List<String>) {
        launchOperation(
            errorMessage = "The access list could not be saved.",
        ) {
            val snapshot = graph.settings.snapshot()
            val owners = snapshot.appStringList(AppSettingKeys.OWNER_NUMBERS)
            when (key) {
                AppSettingKeys.OWNER_NUMBERS -> {
                    val firstOwner =
                        owners.firstOrNull()
                            ?: error("No protected owner is set up yet.")
                    val normalized = normalizePhoneEntries(entries)
                    val protectedOwners = (listOf(firstOwner) + normalized).distinct()
                    val admins =
                        (protectedOwners + snapshot.appStringList(AppSettingKeys.ADMIN_NUMBERS))
                            .distinct()
                    val allowed =
                        (protectedOwners + snapshot.appStringList(AppSettingKeys.ALLOWLIST_NUMBERS))
                            .distinct()
                    graph.settings.updateAppControls(
                        mapOf(
                            AppSettingKeys.OWNER_NUMBERS to SettingValue.StringList.of(protectedOwners),
                            AppSettingKeys.ADMIN_NUMBERS to SettingValue.StringList.of(admins),
                            AppSettingKeys.ALLOWLIST_NUMBERS to SettingValue.StringList.of(allowed),
                        ),
                    )
                }

                AppSettingKeys.ADMIN_NUMBERS ->
                    replaceSharedAccess(
                        appKey = key,
                        list = AccessList.ADMIN,
                        entries = (owners + normalizePhoneEntries(entries)).distinct(),
                    )
                AppSettingKeys.ALLOWLIST_NUMBERS ->
                    replaceSharedAccess(
                        appKey = key,
                        list = AccessList.ALLOW,
                        entries = (owners + normalizePhoneEntries(entries)).distinct(),
                    )
                AppSettingKeys.GROUP_ALLOWLIST ->
                    replaceSharedAccess(
                        appKey = key,
                        list = AccessList.GROUP_ALLOW,
                        entries = normalizeGroupEntries(entries),
                    )
                else -> error("Unknown access list.")
            }
            appendActivity("access_changed", "Access list \"${safeLabel(key)}\" was changed.")
        }
    }

    override fun refreshModels() {
        // Picker expansion is an "ensure loaded" signal, not a force-refresh command. Once the
        // controller already exposes a catalog, opening another model/voice wheel must be a pure
        // UI operation. The atomic gate also collapses rapid taps while the first fetch is active.
        if (overlay.value.models.isNotEmpty() || !modelCatalogLoading.compareAndSet(false, true)) {
            return
        }
        val errorMessage = "Models could not be loaded. Check the API key and the connection."
        overlay.update {
            it.copy(catalogStatus = UiCatalogStatus.LOADING, catalogError = null)
        }
        launchOperation(errorMessage = errorMessage) {
            try {
                check(graph.secrets.has(SecretStore.OPENROUTER_API_KEY)) {
                    "The OpenRouter key is not set up yet."
                }
                val control =
                    graph.controls.currentModelCatalog()
                        ?: error("The model catalogue is still initialising.")
                val models =
                    control.refreshModels()
                        .asSequence()
                        .filter {
                            it.id.isNotBlank() &&
                                it.label.isNotBlank() &&
                                it.supportedRoles.isNotEmpty()
                        }
                        .distinctBy(UiModelOption::id)
                        .toList()
                check(models.isNotEmpty()) { "The provider returned no compatible models." }
                overlay.update {
                    it.copy(
                        models = models,
                        catalogStatus = UiCatalogStatus.READY,
                        catalogError = null,
                        message = "${models.size} compatible models loaded.",
                    )
                }
                appendActivity("models_refreshed", "The compatible model catalogue was refreshed.")
            } catch (cancellation: CancellationException) {
                // A cancelled fetch is a closed screen, not a broken provider. Leave the status
                // alone so a reopened picker starts from IDLE and simply asks again.
                throw cancellation
            } catch (error: Exception) {
                // The snackbar alone is not enough: it disappears while the picker keeps standing
                // there with an empty list. Record the reason on the screen that is missing rows,
                // then let launchOperation classify and surface it as before.
                overlay.update {
                    it.copy(
                        catalogStatus = UiCatalogStatus.ERROR,
                        catalogError = error.message.cleanForUi() ?: errorMessage,
                    )
                }
                throw error
            } finally {
                modelCatalogLoading.set(false)
            }
        }
    }

    override fun loadMoreActivity() {
        // Nothing older exists, or the probe row said so at the last refresh - either way the
        // window must not creep upwards on repeated taps at the bottom of the list.
        if (!activity.value.hasMore) return
        scope.launch {
            activityWindow.updateAndGet {
                (it + ACTIVITY_PAGE_SIZE).coerceAtMost(BotDatabaseLimits.MAX_ACTIVITY_ROWS)
            }
            refreshActivity()
        }
    }

    override fun setAutoStart(enabled: Boolean) {
        launchOperation(
            errorMessage = "Autostart could not be saved.",
        ) {
            val previous = graph.settings.snapshot().appBoolean(AppSettingKeys.AUTOSTART)
            graph.settings.updateAppControls(
                mapOf(AppSettingKeys.AUTOSTART to SettingValue.Bool(enabled)),
            )
            try {
                syncRuntimeAutoStart(enabled)
            } catch (failure: Exception) {
                graph.settings.updateAppControls(
                    mapOf(AppSettingKeys.AUTOSTART to SettingValue.Bool(previous)),
                )
                throw failure
            }
            appendActivity(
                action = "autostart_changed",
                summary =
                    if (enabled) {
                        "Autostart after a reboot is on."
                    } else {
                        "Autostart after a reboot is off."
                    },
            )
        }
    }

    override fun executeAdminAction(action: AdminAction) {
        if (action is AdminAction.CreateGlobalMemory) {
            val key = action.personaKey.trim()
            synchronized(globalMemoryJobs) {
                if (globalMemoryJobs[key]?.isActive == true) return
                val job = launchOperation(errorMessage = "Global memory could not be written.") {
                    executeAdminActionNow(action)
                }
                globalMemoryJobs[key] = job
                job.invokeOnCompletion { globalMemoryJobs.remove(key, job) }
            }
            return
        }
        if (action is AdminAction.WriteContactNow && action.confirmation != null) {
            val target = action.target.trim()
            synchronized(outreachJobs) {
                if (outreachJobs[target]?.isActive == true) return
                val job = launchOperation(errorMessage = "The outreach could not be run.") {
                    executeAdminActionNow(action)
                }
                outreachJobs[target] = job
                job.invokeOnCompletion { outreachJobs.remove(target, job) }
            }
            return
        }
        launchOperation(errorMessage = "The admin action could not be run.") {
            executeAdminActionNow(action)
        }
    }

    override fun disconnectWhatsApp() {
        launchOperation(errorMessage = "WhatsApp could not be disconnected safely.") {
            RuntimeBridgeControl.disconnectWhatsApp()
            onboardingPreferences.edit().putBoolean(KEY_WHATSAPP_LINK_OBSERVED, false).apply()
            overlay.update {
                it.copy(
                    pairingCode = null,
                    pairingCodeExpiresAtMs = null,
                    message = "WhatsApp disconnected. Enter the new phone number when ready.",
                )
            }
            graph.controls.publishMetrics(
                graph.controls.metrics.value.copy(accountName = null, accountJid = null),
            )
            appendActivity(
                "whatsapp_disconnected",
                "The linked device was logged out and a fresh local pairing client was prepared.",
            )
        }
    }

    override fun cancelWriteContact(chatId: String) {
        val target = chatId.trim()
        outreachJobs.remove(target)?.cancel(
            CancellationException("Manual outreach cancelled by the operator"),
        )
    }

    override fun cancelGlobalMemory(personaKey: String) {
        val key = personaKey.trim()
        globalMemoryJobs.remove(key)?.cancel(
            CancellationException("Global memory rewrite abandoned by the operator"),
        )
    }

    private suspend fun executeAdminActionNow(action: AdminAction) {
        val actions =
            graph.controls.currentAdminActions()
                ?: error("The shared admin control is still initialising.")
        val result = actions.execute(adminRequest(action))
        mutableAdminResults.emit(result)
        requireAdminSuccess(result)
        // A pure lookup changes nothing: a confirmation dialog would only cover the surface the
        // user just opened, and reloading settings plus the activity log would be wasted work.
        if (!action.isReadOnlyLookup()) {
            showMessage(successMessage(result))
            graph.settings.reload()
            graph.controls.notifyActivityChanged()
        }
    }

    /**
     * True for actions that only read state and already render into their own screen.
     *
     * A confirmation for these was worse than useless: it appeared on top of the answer the user
     * had just asked for, so reading a lookup took two taps, and it dragged a settings reload and
     * an activity refresh along behind a command that changed nothing.
     */
    private fun AdminAction.isReadOnlyLookup(): Boolean =
        this is AdminAction.ListMemories ||
            this is AdminAction.OpenMemory ||
            this is AdminAction.PersonaImages ||
            this is AdminAction.PersonaImageReferences ||
            this is AdminAction.PersonaProfilePictures ||
            this is AdminAction.Status ||
            this is AdminAction.MoodStatus ||
            this is AdminAction.Meta ||
            this is AdminAction.ListSettings ||
            this is AdminAction.GetSetting ||
            this is AdminAction.ListAccess ||
            this is AdminAction.ApiKeyStatus ||
            this is AdminAction.ListPersonas ||
            this is AdminAction.VoiceStatus ||
            this is AdminAction.ListVoices ||
            this is AdminAction.ListBlocks ||
            this is AdminAction.ProactiveStatus ||
            this is AdminAction.GetProactiveOverride ||
            this is AdminAction.ListProactiveContacts ||
            this is AdminAction.SafetyStatus ||
            this is AdminAction.SafetyEvents ||
            this is AdminAction.RefreshSafety ||
            this is AdminAction.Help

    override fun importApprovedImage(
        personaKey: String,
        displayName: String,
        uri: Uri,
    ) {
        launchOperation(
            errorMessage = "The image could not be imported safely.",
        ) {
            val actions =
                graph.controls.currentAdminActions()
                    ?: error("The shared admin control is still initialising.")
            // The shared admin adapter is the authority for known personas;
            // import never creates a hidden persona or a second admin path.
            requireAdminSuccess(
                actions.execute(adminRequest(AdminAction.PersonaImages(personaKey))),
            )
            val imported = graph.approvedMediaImporter.importImage(personaKey, uri, displayName)
            val updated =
                actions.execute(adminRequest(AdminAction.PersonaImages(imported.asset.personaKey)))
            mutableAdminResults.emit(updated)
            requireAdminSuccess(updated)
            showMessage(
                if (imported.created) {
                    "Image approved privately for ${imported.asset.personaKey}."
                } else {
                    "This image was already approved for ${imported.asset.personaKey}."
                },
            )
            appendActivity(
                action = if (imported.created) "approved_image_imported" else "approved_image_duplicate",
                summary =
                    "Approved image catalogue for ${imported.asset.personaKey} was refreshed.",
            )
        }
    }

    override fun exportPersonaImage(
        kind: ApprovedMediaKind,
        personaKey: String,
        assetId: String,
        destination: Uri,
    ) {
        launchOperation(errorMessage = "The image could not be saved.") {
            // Opened through the store rather than by path, so the export runs the same persona
            // binding and digest check as a send. A copy that leaves the app is still a copy of
            // something this install vouches for.
            val handle = graph.approvedMedia.openForExport(assetId, personaKey, kind)
            applicationContext.contentResolver
                .openOutputStream(destination)
                ?.use { output -> handle.file.inputStream().use { it.copyTo(output) } }
                ?: error("The selected location cannot be written to.")
            showMessage("Saved ${handle.asset.displayName}.")
        }
    }

    override fun exportBotData(uri: Uri) {
        launchOperation(errorMessage = "The backup could not be written.") {
            val summary =
                applicationContext.contentResolver
                    .openOutputStream(uri)
                    ?.use { BotDataArchive.export(applicationContext, graph.repository, it) }
                    ?: error("The selected location cannot be written to.")
            showMessage(
                "Backup written: ${summary.databaseBytes / 1024} kB of data and " +
                    "${summary.mediaFiles} approved image(s).",
            )
            appendActivity(
                action = "data_exported",
                summary = "Bot data was exported to a file chosen by the operator.",
            )
        }
    }

    override fun importBotData(uri: Uri) {
        launchOperation(errorMessage = "The backup could not be restored.") {
            // Staged and validated first; only once that succeeded is anything of the live install
            // touched, and from that point the process must not keep running on the old data.
            val staged =
                applicationContext.contentResolver
                    .openInputStream(uri)
                    ?.use { BotDataArchive.read(applicationContext, it) }
                    ?: error("The selected file cannot be read.")
            appendActivity(
                action = "data_import_started",
                summary = "A backup was accepted and is being restored; the app will restart.",
            )
            RuntimeServiceController.stop(applicationContext)
            graph.repository.close()
            BotDataArchive.commit(applicationContext, staged)
            restartProcess()
        }
    }

    /**
     * Relaunches the task and then ends the process.
     *
     * A restored database cannot be adopted in place: settings, the runtime host and every cached
     * repository handle were built from the file that has just been replaced.
     */
    private fun restartProcess() {
        applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let(applicationContext::startActivity)
        Runtime.getRuntime().exit(0)
    }

    override fun clearMessage() {
        overlay.update { it.copy(message = null) }
    }

    override fun dismissAlert(kind: AlertKind) {
        graph.controls.dismissAlert(kind)
    }

    override fun close() {
        scope.cancel()
    }

    private fun buildState(
        settings: SettingsSnapshot = graph.settings.snapshot(),
        runtime: RuntimeState = RuntimeStateStore.state.value,
        presentation: ControllerOverlay = overlay.value,
        metrics: AppRuntimeMetrics = graph.controls.metrics.value,
        activityFeed: ActivityFeed = activity.value,
        limitNotice: RuntimeLimitNotice? = graph.controls.limitNotice.value,
        alerts: List<RuntimeAlert> = graph.controls.alerts.value,
    ): AppUiState {
        val projection = UiSettingsMapper.project(settings, presentation.models)
        val setup =
            setupState(
                settings,
                presentation.pairingCode,
                presentation.pairingCodeExpiresAtMs,
                presentation.bridgeStatus,
                presentation.credentialRevision,
            )
        val maxHour = settings.integer(BotSettingKeys.MAX_SENDS_PER_HOUR)
        val maxDay = settings.integer(BotSettingKeys.MAX_SENDS_PER_DAY)
        return AppUiState(
            runtime = runtime,
            setup = setup,
            botEnabled = settings.boolean(BotSettingKeys.ENABLED),
            model = settings.text(BotSettingKeys.MODEL),
            persona = settings.text(BotSettingKeys.PERSONALITY),
            profilePicturePath = confirmedProfilePicture(),
            // Recomputed with the rest of the state rather than on a timer: the mood only turns
            // over every six hours, and every state rebuild is far more frequent than that.
            mood = currentMood(settings),
            proactiveLevel = settings.integer(BotSettingKeys.PROACTIVE_LEVEL),
            safetyLabel =
                when {
                    maxHour <= 15 && maxDay <= 60 -> "Very cautious"
                    maxHour <= 25 && maxDay <= 120 -> "Cautious"
                    else -> "Custom"
                },
            pendingChats = metrics.pendingChats,
            processedToday = metrics.processedToday,
            sentToday = metrics.sentToday,
            accountName =
                presentation.bridgeStatus.accountName
                    ?: metrics.accountName.takeIf {
                        presentation.bridgeStatus.connection == BridgeConnectionState.NOT_CONFIGURED
                    },
            accountJid =
                presentation.bridgeStatus.accountJid
                    ?: metrics.accountJid.takeIf {
                        presentation.bridgeStatus.connection == BridgeConnectionState.NOT_CONFIGURED
                    },
            hideSensitiveData = settings.appBoolean(AppSettingKeys.HIDE_SENSITIVE_DATA),
            whatsappConnection = presentation.bridgeStatus.connection,
            // The deadline the row itself prints is also the point at which it stops being true.
            limitNotice = limitNotice?.takeUnless { notice ->
                notice.untilMs?.let { it <= System.currentTimeMillis() } == true
            },
            alerts = alerts,
            basicSettings = projection.basic,
            expertSettings = projection.expert,
            accessLists = accessLists(settings),
            activity = activityFeed.entries,
            activityHasMore = activityFeed.hasMore,
            modelOptions =
                presentation.models
                    .distinctBy(UiModelOption::id)
                    .map { it.id to it.label },
            modelCatalogStatus = presentation.catalogStatus,
            modelCatalogError = presentation.catalogError,
            busy = presentation.busy,
            message = presentation.message,
        )
    }

    /** Local state-file projection with no WhatsApp read and no repeated catalogue walk. */
    private fun confirmedProfilePicture(): String? {
        val stamp =
            if (profilePictureStateFile.isFile) {
                profilePictureStateFile.lastModified() xor profilePictureStateFile.length()
            } else {
                0L
            }
        if (stamp == profilePictureProjectionStamp) return profilePictureProjection
        return synchronized(profilePictureStateFile) {
            if (stamp != profilePictureProjectionStamp) {
                profilePictureProjection =
                    ProfilePictureRotator.confirmedPicturePath(
                        stateFile = profilePictureStateFile,
                        library = graph.profilePictures,
                    )
                profilePictureProjectionStamp = stamp
            }
            profilePictureProjection
        }
    }

    override fun importCharacterReferences(
        personaKey: String,
        uris: List<Uri>,
    ) {
        launchOperation(errorMessage = "The character references could not be imported safely.") {
            require(uris.isNotEmpty()) { "Pick at least one image." }
            val actions =
                graph.controls.currentAdminActions()
                    ?: error("The shared admin control is still initialising.")
            requireAdminSuccess(
                actions.execute(adminRequest(AdminAction.PersonaImageReferences(personaKey))),
            )
            var created = 0
            var duplicates = 0
            var failed = 0
            uris.take(MAX_REFERENCE_PICK_COUNT).forEach { uri ->
                runCatching { graph.characterReferenceImporter.importImage(personaKey, uri) }
                    .onSuccess { imported ->
                        if (imported.created) created++ else duplicates++
                    }.onFailure { failed++ }
            }
            val updated =
                actions.execute(adminRequest(AdminAction.PersonaImageReferences(personaKey)))
            mutableAdminResults.emit(updated)
            requireAdminSuccess(updated)
            showMessage(
                buildString {
                    append(created)
                    append(if (created == 1) " reference imported" else " references imported")
                    if (duplicates > 0) append(" · $duplicates duplicate")
                    if (failed > 0) append(" · $failed rejected")
                },
            )
            appendActivity(
                action = "character_references_imported",
                summary =
                    "Character references for $personaKey were normalized and refreshed " +
                        "($created new, $duplicates duplicate, $failed rejected).",
            )
        }
    }

    override fun importProfilePictures(
        personaKey: String,
        uris: List<Uri>,
    ) {
        launchOperation(errorMessage = "The profile pictures could not be imported safely.") {
            require(uris.isNotEmpty()) { "Pick at least one image." }
            val actions =
                graph.controls.currentAdminActions()
                    ?: error("The shared admin control is still initialising.")
            requireAdminSuccess(
                actions.execute(adminRequest(AdminAction.PersonaProfilePictures(personaKey))),
            )
            var created = 0
            var duplicates = 0
            var failed = 0
            uris.take(MAX_REFERENCE_PICK_COUNT).forEach { uri ->
                runCatching { graph.profilePictureImporter.importPicture(personaKey, uri) }
                    .onSuccess { imported ->
                        if (imported.created) created++ else duplicates++
                    }.onFailure { failed++ }
            }
            val updated =
                actions.execute(adminRequest(AdminAction.PersonaProfilePictures(personaKey)))
            mutableAdminResults.emit(updated)
            requireAdminSuccess(updated)
            showMessage(
                buildString {
                    append(created)
                    append(if (created == 1) " picture imported" else " pictures imported")
                    if (duplicates > 0) append(" · $duplicates duplicate")
                    if (failed > 0) append(" · $failed rejected")
                    // Importing never touches the account: the rotation puts a new face up when
                    // the persona changes or the schedule comes round, never on an upload.
                    if (created > 0) append(" · used from the next change")
                },
            )
            appendActivity(
                action = "profile_pictures_imported",
                summary =
                    "Profile pictures for $personaKey were cropped and refreshed " +
                        "($created new, $duplicates duplicate, $failed rejected).",
            )
        }
    }

    /** Empty when the mood is switched off, which is also how the UI hides the label. */
    private fun currentMood(settings: SettingsSnapshot): String {
        if (!settings.boolean(BotSettingKeys.MOOD_ENABLED)) return ""
        val zone =
            runCatching { ZoneId.of(settings.text(BotSettingKeys.TIMEZONE)) }
                .getOrElse { ZoneId.systemDefault() }
        return MoodEngine.at(System.currentTimeMillis(), zone).key
    }

    private fun setupState(
        settings: SettingsSnapshot,
        pairingCode: String?,
        pairingCodeExpiresAtMs: Long?,
        bridgeStatus: BridgeStatus,
        credentialRevision: Long,
    ): SetupState {
        val owners = settings.appStringList(AppSettingKeys.OWNER_NUMBERS)
        val admins = settings.appStringList(AppSettingKeys.ADMIN_NUMBERS)
        val bridgeTokenConfigured = graph.secrets.isReadable(SecretStore.BRIDGE_TOKEN)
        val openRouterKeyConfigured = graph.secrets.isReadable(SecretStore.OPENROUTER_API_KEY)
        val sttApiKeyConfigured = graph.secrets.isReadable(SecretStore.STT_API_KEY)
        return SetupState(
            bridgeTokenConfigured = bridgeTokenConfigured,
            openRouterKeyConfigured = openRouterKeyConfigured,
            sttApiKeyConfigured = sttApiKeyConfigured,
            ownerNumbers = owners,
            adminNumbers = admins,
            pairingCode = pairingCode,
            pairingCodeExpiresAtMs = pairingCodeExpiresAtMs,
            whatsAppConnected =
                bridgeStatus.connection == BridgeConnectionState.CONNECTED ||
                    onboardingPreferences.getBoolean(KEY_WHATSAPP_LINK_OBSERVED, false),
            credentialRevision = credentialRevision,
            configured =
                bridgeTokenConfigured &&
                    openRouterKeyConfigured,
            onboardingCompleted =
                onboardingPreferences.getBoolean(KEY_ONBOARDING_COMPLETED, false) &&
                    onboardingPreferences.getInt(KEY_ONBOARDING_VERSION, 0) >=
                    CURRENT_ONBOARDING_VERSION,
        )
    }

    private fun currentSetup(): SetupState {
        val presentation = overlay.value
        return setupState(
            graph.settings.snapshot(),
            presentation.pairingCode,
            presentation.pairingCodeExpiresAtMs,
            presentation.bridgeStatus,
            presentation.credentialRevision,
        )
    }

    private fun onboardingCompleted(): Boolean =
        onboardingPreferences.getBoolean(KEY_ONBOARDING_COMPLETED, false) &&
            onboardingPreferences.getInt(KEY_ONBOARDING_VERSION, 0) >= CURRENT_ONBOARDING_VERSION

    private fun markOnboardingCompleted() {
        check(
            onboardingPreferences
                .edit()
                .putBoolean(KEY_ONBOARDING_COMPLETED, true)
                .putInt(KEY_ONBOARDING_VERSION, CURRENT_ONBOARDING_VERSION)
                .commit(),
        ) { "Completion of the setup guide could not be saved." }
    }

    private fun generateBridgeToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun accessLists(settings: SettingsSnapshot): List<UiAccessList> {
        val owners = settings.appStringList(AppSettingKeys.OWNER_NUMBERS)
        return listOf(
            UiAccessList(
                key = AppSettingKeys.OWNER_NUMBERS,
                title = "Owner",
                description = "Full access, and the recipient of critical alerts. The first owner is protected.",
                entries = owners,
                protectedFirstEntry = true,
            ),
            UiAccessList(
                key = AppSettingKeys.ADMIN_NUMBERS,
                title = "Admins",
                description = "May run admin commands from the WhatsApp chat.",
                entries = settings.appStringList(AppSettingKeys.ADMIN_NUMBERS),
                protectedEntries = owners.toSet(),
            ),
            UiAccessList(
                key = AppSettingKeys.ALLOWLIST_NUMBERS,
                title = "Allowed contacts",
                description = "Direct contacts whose messages the bot processes.",
                entries = settings.appStringList(AppSettingKeys.ALLOWLIST_NUMBERS),
                protectedEntries = owners.toSet(),
            ),
            UiAccessList(
                key = AppSettingKeys.GROUP_ALLOWLIST,
                title = "Allowed groups",
                description = "Group name, JID or group ID; groups stay locked out by default.",
                entries = settings.appStringList(AppSettingKeys.GROUP_ALLOWLIST),
                groupList = true,
            ),
        )
    }

    /**
     * Publishes the currently requested slice of the log.
     *
     * "Everything" is literal: DEBUG through ERROR are reachable. The window remains bounded, so
     * diagnostic rows do not turn this into an unbounded in-memory log.
     *
     * The whole log stays reachable, but only what the user scrolled to is held in memory: the
     * window starts at [ACTIVITY_PAGE_SIZE] and grows through [loadMoreActivity]. Once the window
     * is full, a refresh reads only the newest page and splices it in - the cost of an update
     * therefore stays flat no matter how far back the user scrolled.
     */
    private suspend fun refreshActivity() =
        activityLoad.withLock {
            val window = activityWindow.get()
            val current = activity.value
            if (current.entries.size < window) {
                // First load, or a window the user just widened: read it outright.
                publishActivity(loadActivityRecords(window + 1), window)
                return@withLock
            }
            // A full window only ever gains rows at the newest end, and the seam is the row we
            // already know. Anything above it is new; the rest of the window is untouched.
            val head = loadActivityRecords(ACTIVITY_PAGE_SIZE)
            val newestKnown = current.entries.first().id
            val fresh = head.takeWhile { it.databaseId != newestKnown }
            when {
                fresh.isEmpty() -> return@withLock
                // No seam: more than a page arrived (or old rows were pruned), so splicing could
                // leave a hole in the middle. Re-read the window instead.
                fresh.size == head.size -> publishActivity(loadActivityRecords(window + 1), window)
                else ->
                    activity.value =
                        ActivityFeed(
                            entries = (fresh.map(::formatActivity) + current.entries).take(window),
                            // The splice pushed the oldest loaded rows back out of the window.
                            hasMore = true,
                        )
            }
        }

    /** Replaces the whole window; rows still on screen keep their formatted instance. */
    private fun publishActivity(
        records: List<ActivityLogRecord>,
        window: Int,
    ) {
        val known = activity.value.entries.associateByTo(HashMap(), UiActivityEntry::id)
        activity.value =
            ActivityFeed(
                entries = records.take(window).map { known[it.databaseId] ?: formatActivity(it) },
                // One row past the window answers "is there older material?" without a COUNT(*).
                hasMore = records.size > window,
            )
    }

    private fun formatActivity(record: ActivityLogRecord): UiActivityEntry =
        UiActivityEntry(
            id = record.databaseId,
            timestampMs = record.occurredAt,
            level = record.level.databaseValue,
            category = record.category,
            action = record.action,
            message = record.summary,
            details = prettyActivityDetails(record.detailsJson),
        )

    /**
     * Reads up to [target] rows newest-first, page by page.
     *
     * A single query is capped at [BotDatabaseLimits.MAX_QUERY_LIMIT], so a larger window walks
     * backwards on the `(occurred_at, id)` keyset. Both columns are required: a busy millisecond can
     * contain more than one full page, and a timestamp-only cursor would skip the remainder.
     */
    private fun loadActivityRecords(target: Int): List<ActivityLogRecord> {
        val wanted = target.coerceIn(1, BotDatabaseLimits.MAX_ACTIVITY_ROWS)
        val collected = ArrayList<ActivityLogRecord>(minOf(wanted, ACTIVITY_PAGE_SIZE))
        var cursorAt: Long? = null
        var cursorId: Long? = null
        var pages = 0
        while (collected.size < wanted && pages < ACTIVITY_MAX_PAGES) {
            pages++
            val pageLimit = (wanted - collected.size).coerceAtMost(BotDatabaseLimits.MAX_QUERY_LIMIT)
            val page =
                graph.repository.listActivity(
                    minimumLevel = ActivityLevel.DEBUG,
                    beforeOccurredAt = cursorAt,
                    beforeId = cursorId,
                    limit = pageLimit,
                )
            if (page.isEmpty()) break
            collected += page
            if (page.size < pageLimit) break
            cursorAt = page.last().occurredAt
            cursorId = page.last().databaseId
        }
        return collected
    }

    private fun prettyActivityDetails(detailsJson: String?): String? =
        detailsJson
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw) }
            ?.take(MAX_ACTIVITY_DETAILS_CHARS)

    private suspend fun replaceSharedAccess(
        appKey: String,
        list: AccessList,
        entries: List<String>,
    ) {
        val actions = graph.controls.currentAdminActions()
        if (actions == null) {
            graph.settings.updateAppControls(
                mapOf(appKey to SettingValue.StringList.of(entries)),
            )
            return
        }
        requireAdminSuccess(
            actions.execute(
                adminRequest(
                    AdminAction.ChangeAccess(
                        list = list,
                        operation = AccessOperation.REPLACE,
                        entries = entries,
                    ),
                ),
            ),
        )
        graph.settings.reload()
    }

    private fun adminRequest(action: AdminAction): AdminRequest {
        val actorId =
            graph.settings.snapshot()
                .appStringList(AppSettingKeys.OWNER_NUMBERS)
                .firstOrNull()
                ?: LOCAL_APP_ACTOR
        return AdminRequest(
            context =
                AdminContext(
                    origin = AdminOrigin.APP,
                    actorId = actorId,
                    chatId = null,
                    isGroup = false,
                ),
            action = action,
        )
    }

    private fun requireAdminSuccess(result: AdminResult) {
        when (result) {
            is AdminResult.Success -> Unit
            is AdminResult.Invalid ->
                throw IllegalArgumentException(
                    listOfNotNull(result.field, result.reason).joinToString(": "),
                )
            is AdminResult.NotFound -> throw IllegalArgumentException("${result.subject} was not found.")
            is AdminResult.Denied -> throw IllegalStateException(result.reason)
            is AdminResult.Failure -> throw IllegalStateException(result.reason)
        }
    }

    private fun successMessage(result: AdminResult): String =
        when (val payload = (result as AdminResult.Success).payload) {
            is AdminPayload.Text -> payload.value.lineSequence().firstOrNull().orEmpty().take(240)
            is AdminPayload.SettingsChanged -> "${payload.normalizedValues.size} settings changed."
            is AdminPayload.AccessChanged -> "${payload.total} entries saved."
            is AdminPayload.ImageAssets ->
                "${payload.entries.size} approved images for ${payload.personaKey}."
            is AdminPayload.ImageReferences ->
                "${payload.entries.size} character references for ${payload.personaKey}."
            is AdminPayload.ProfilePictures ->
                "${payload.entries.size} profile pictures for ${payload.personaKey}."
            is AdminPayload.ImageSent -> "Approved image sent."
            is AdminPayload.BlockChanged ->
                if (payload.whatsappConfirmed) {
                    "WhatsApp confirmed the block."
                } else {
                    "Block saved locally."
                }
            is AdminPayload.WipeSummary -> "${payload.affectedThreads} threads cleared."
            is AdminPayload.MemoryWritten -> payload.detail
            else -> "Admin action done."
        }

    private fun launchOperation(
        errorMessage: String = "The action could not be completed.",
        block: suspend () -> Unit,
    ): Job =
        scope.launch {
            beginOperation()
            try {
                block()
            } catch (error: SettingsValidationException) {
                val reason = error.errors.values.firstOrNull()
                showMessage(reason?.take(200) ?: errorMessage)
            } catch (error: IllegalArgumentException) {
                showMessage(error.message.cleanForUi() ?: errorMessage)
            } catch (error: IllegalStateException) {
                showMessage(error.message.cleanForUi() ?: errorMessage)
            } catch (error: BridgeActionException) {
                showMessage(error.message.cleanForUi() ?: errorMessage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage(errorMessage)
            } finally {
                endOperation()
            }
        }

    private fun beginOperation() {
        if (activeOperations.getAndIncrement() == 0) {
            overlay.update { it.copy(busy = true) }
        }
    }

    private fun endOperation() {
        if (activeOperations.decrementAndGet() == 0) {
            overlay.update { it.copy(busy = false) }
        }
    }

    private fun showMessage(message: String) {
        overlay.update { it.copy(message = message.cleanForUi() ?: "Action completed.") }
    }

    private fun logControl(
        action: String,
        summary: String,
    ) {
        scope.launch { appendActivity(action, summary) }
    }

    private fun appendActivity(
        action: String,
        summary: String,
    ) {
        graph.repository.appendActivity(
            ActivityLogRecord(
                level = ActivityLevel.INFO,
                category = "app",
                action = action,
                summary = summary,
            ),
        )
        graph.controls.notifyActivityChanged()
    }

    private fun reconcileAutoStart() {
        val desired = graph.settings.snapshot().appBoolean(AppSettingKeys.AUTOSTART)
        if (RuntimeServiceController.isAutoStartEnabled(applicationContext) != desired) {
            if (!RuntimeServiceController.setAutoStartEnabled(applicationContext, desired)) {
                showMessage("Android could not store the autostart choice.")
            }
        }
    }

    private fun syncRuntimeAutoStart(enabled: Boolean) {
        check(RuntimeServiceController.setAutoStartEnabled(applicationContext, enabled)) {
            "Android could not store the autostart choice."
        }
        check(RuntimeServiceController.isAutoStartEnabled(applicationContext) == enabled) {
            "Android could not store the autostart choice."
        }
    }

    private fun normalizePhoneEntries(entries: List<String>): List<String> =
        entries.map { requirePhoneNumber(it, "Phone number") }.distinct()

    private fun normalizeGroupEntries(entries: List<String>): List<String> =
        entries
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .onEach {
                require(it.length <= MAX_ACCESS_ENTRY_LENGTH && it.none(Char::isISOControl)) {
                    "Not a valid group entry."
                }
            }
            .distinctBy { it.lowercase(Locale.ROOT) }

    private fun requirePhoneNumber(
        raw: String,
        label: String,
    ): String {
        return requireNotNull(normalizePhoneNumber(raw)) {
            "$label must be a valid number with 7 to 15 digits."
        }
    }

    private fun safeLabel(key: String): String =
        key.replace('_', ' ').take(MAX_ACTIVITY_LABEL_LENGTH)

    private fun String?.cleanForUi(): String? =
        this
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(MAX_UI_MESSAGE_LENGTH)
            ?.takeIf(String::isNotEmpty)

    /** The loaded log slice plus whether the database still holds older rows. */
    private data class ActivityFeed(
        val entries: List<UiActivityEntry> = emptyList(),
        val hasMore: Boolean = false,
    )

    private data class ControllerOverlay(
        val busy: Boolean = false,
        val message: String? = null,
        val pairingCode: String? = null,
        val pairingCodeExpiresAtMs: Long? = null,
        val models: List<UiModelOption> = emptyList(),
        val catalogStatus: UiCatalogStatus = UiCatalogStatus.IDLE,
        val catalogError: String? = null,
        val bridgeStatus: BridgeStatus = BridgeStatus(),
        @Suppress("unused")
        val credentialRevision: Long = 0,
    )

    private companion object {
        const val ONBOARDING_PREFERENCES = "whatsapp_bot_onboarding"
        const val KEY_ONBOARDING_COMPLETED = "completed"
        const val KEY_ONBOARDING_VERSION = "version"
        const val KEY_WHATSAPP_LINK_OBSERVED = "whatsapp_link_observed"
        const val CURRENT_ONBOARDING_VERSION = 2
        const val ACTIVITY_PAGE_SIZE = 100

        /** Bounded walk: 1000-row pages reach the whole retained log in a third of these. */
        const val ACTIVITY_MAX_PAGES = 64
        const val ACTIVITY_REFRESH_BATCH_MS = 500L
        const val LOCAL_APP_ACTOR = "local-app"
        const val MAX_ACCESS_ENTRY_LENGTH = 512
        const val MAX_ACTIVITY_LABEL_LENGTH = 128
        const val MAX_ACTIVITY_DETAILS_CHARS = 4_096
        const val MAX_UI_MESSAGE_LENGTH = 240
        const val MAX_REFERENCE_PICK_COUNT = 8
    }
}

/** Prevents impatient/repeated taps from queueing multiple destructive native reconnects. */
internal class PairingAttemptGate {
    private val active = AtomicBoolean(false)

    fun tryEnter(): Boolean = active.compareAndSet(false, true)

    fun leave() {
        active.set(false)
    }
}
