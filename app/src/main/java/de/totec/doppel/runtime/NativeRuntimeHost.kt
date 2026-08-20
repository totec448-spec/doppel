// Crash-safe cursor writes intentionally inspect SharedPreferences.commit().
@file:Suppress("UseKtx")

package de.totec.doppel.runtime

import de.totec.doppel.security.privacySafeErrorType
import android.content.Context
import android.util.Log
import de.totec.doppel.app.AlertKind
import de.totec.doppel.app.AppRuntimeMetrics
import de.totec.doppel.app.BotAppGraph
import de.totec.doppel.app.RuntimeAlert
import de.totec.doppel.app.SettingsTargets
import de.totec.doppel.data.db.ActivityLevel
import de.totec.doppel.data.db.ActivityLogRecord
import de.totec.doppel.data.db.AccessListKind
import de.totec.doppel.data.db.MessageDeliveryState
import de.totec.doppel.data.db.OutboundDecision as DbOutboundDecision
import de.totec.doppel.data.db.OutboundSafetyRecord
import de.totec.doppel.data.db.OutboundStatus
import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.engine.BotEngine
import de.totec.doppel.engine.OutboundDecision
import de.totec.doppel.engine.ProfilePictureOutcome
import de.totec.doppel.engine.ProfilePictureRotation
import de.totec.doppel.engine.OutboundIntent
import de.totec.doppel.engine.PlannedSideEffect
import de.totec.doppel.engine.ProactiveTurnOutcome
import de.totec.doppel.integration.ApprovedImageSender
import de.totec.doppel.integration.ManualTextSender
import de.totec.doppel.integration.BridgeWhatsAppActions
import de.totec.doppel.integration.CommandGatewayAdapter
import de.totec.doppel.integration.DurableBridgeOutboxDispatcher
import de.totec.doppel.integration.IncomingMediaAnalyzer
import de.totec.doppel.integration.ImportedMemorySession
import de.totec.doppel.integration.ImportedMemorySessionFactory
import de.totec.doppel.integration.MessageKeyCache
import de.totec.doppel.integration.TranscriptionFallbackConfiguration
import de.totec.doppel.integration.OpenRouterAiTurnRunner
import de.totec.doppel.ai.CatalogMediaModelCapabilities
import de.totec.doppel.ai.ModelCatalogClient
import de.totec.doppel.ai.OpenRouterImageClient
import de.totec.doppel.integration.MemoryMaintenance
import de.totec.doppel.integration.MemoryRefreshOutcome
import de.totec.doppel.integration.MemoryWriteResult
import de.totec.doppel.integration.OutboxHistoryReconciler
import de.totec.doppel.integration.OutreachResult
import de.totec.doppel.integration.OutreachTrigger
import de.totec.doppel.integration.OkHttpTranscriptionFallbackClient
import de.totec.doppel.integration.RepositoryEngineSettingsProvider
import de.totec.doppel.integration.RepositoryAiNetworkObserver
import de.totec.doppel.integration.RepositoryEngineStore
import de.totec.doppel.integration.RuntimeBridgeControl
import de.totec.doppel.media.ApprovedMediaAssetStore
import de.totec.doppel.media.ProfilePictureRotator
import de.totec.doppel.security.SecretStore
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.SettingsCatalogs
import de.totec.doppel.settings.SettingsSnapshot
import de.totec.doppel.transport.BridgeClient
import de.totec.doppel.transport.BridgeControlToken
import de.totec.doppel.transport.BridgeEndpoint
import de.totec.doppel.transport.BridgeFrame
import de.totec.doppel.transport.BridgeMediaClient
import de.totec.doppel.transport.BridgeTermination
import de.totec.doppel.transport.BridgeTransport
import de.totec.doppel.transport.EmbeddedNativeBridge
import de.totec.doppel.ai.OpenRouterApiKeyProvider
import de.totec.doppel.ai.OpenRouterAttribution
import de.totec.doppel.ai.OpenRouterClient
import de.totec.doppel.ai.SpeechClient
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Dispatcher
import org.json.JSONObject
import org.json.JSONArray

internal const val AMBIGUOUS_SEND_HOLD_MS = 10 * 60_000L
private const val MAX_INGRESS_JIDS = 1_000
private const val SAFETY_REFRESH_DEBOUNCE_MS = 5 * 60_000L
private const val HISTORY_SWEEP_DEBOUNCE_MS = 5 * 60_000L
private const val METRICS_REFRESH_MIN_INTERVAL_MS = 1_000L
private const val TAG = "NativeRuntimeHost"

/**
 * The namespace an installed device's linked-device identity is filed under.
 *
 * It was the persisted name of a transport back when there were two of them. There is one now, so
 * nothing chooses it any more — but it is still the key existing installs stored their identity
 * against, and renaming it would ask every one of them to link the phone again.
 */
private const val NATIVE_IDENTITY_NAMESPACE = "native"

/** Which profile picture is current and when the next change is due. */

/** Scratch space for a picture on its way from the APK to the bridge. */
private const val PROFILE_PICTURE_WORK_DIR = "profile-picture"

/** Recorded when a session ended before a resume cursor was ever read. */
private const val UNKNOWN_RESUME_CURSOR = -1L
private val INGRESS_JID = Regex("^[^\\s@]{1,160}@(s\\.whatsapp\\.net|lid|g\\.us)$")

/**
 * How often one bridge event may fail before it is quarantined.
 *
 * Enough to ride out a locked database or a momentarily unavailable dependency, few enough that a
 * genuinely undigestible event stops holding the stream within a couple of seconds.
 */
private const val MAX_FRAME_ATTEMPTS = 3
private const val FRAME_RETRY_DELAY_MS = 250L

/**
 * The sequence of an event frame, or null for the frames that carry the session itself.
 *
 * Only an event may be skipped. A handshake or protocol frame that cannot be processed is not a
 * poison pill to step over — it is a session that has no business continuing.
 */
private fun BridgeFrame.quarantinableSequence(): Long? =
    when (this) {
        is BridgeFrame.Connection -> sequence
        is BridgeFrame.PairingCode -> sequence
        is BridgeFrame.Incoming -> sequence
        is BridgeFrame.Delivery -> sequence
        is BridgeFrame.Safety -> sequence
        is BridgeFrame.Undecodable -> sequence
        is BridgeFrame.Welcome,
        is BridgeFrame.Ready,
        is BridgeFrame.ActionResult,
        is BridgeFrame.ProtocolError,
        -> null
    }

/**
 * Owns exactly one Android-to-companion socket and one native bot engine for a
 * foreground-service session. Reconnect policy remains exclusively in the
 * service; this host closes every child on cancellation and performs no idle
 * polling, wake lock, or watchdog work.
 */
class NativeRuntimeHost(
    context: Context,
    private val graph: BotAppGraph,
) : BotRuntimeHost {
    private val applicationContext = context.applicationContext
    private val accountName = AtomicReference<String?>(null)
    private val accountJid = AtomicReference<String?>(null)
    private val lastMetricsRefreshAt = AtomicLong(0L)

    override suspend fun run(session: RuntimeHostSession): RuntimeHostResult =
        coroutineScope {
            val snapshot = graph.settings.snapshot()
            // "native" was one of two transport values and is now the only one there has ever
            // really been. It stays as the identity namespace rather than becoming a cleaner
            // literal: the string is what an installed device's stored linked-device identity is
            // filed under, and renaming it would ask every existing install to link again.
            val identity = BridgeIdentityStore(applicationContext, NATIVE_IDENTITY_NAMESPACE)
            val token =
                runCatching { graph.secrets.get(SecretStore.BRIDGE_TOKEN) }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: return@coroutineScope terminal(
                        session,
                        "Bridge access is missing",
                        RuntimeHostPhase.AUTHENTICATION_REQUIRED,
                    )
            // Acquired, not created: the native core is process-global and survives this session on
            // purpose, so a local failure reconnects the loopback socket instead of relinking the
            // device. Only [EmbeddedNativeBridge.shutdown] — driven by the foreground service —
            // ends it.
            val embeddedBridge =
                runCatching { EmbeddedNativeBridge.acquire(applicationContext, token) }
                    .getOrElse {
                        return@coroutineScope terminal(
                            session,
                            "The native WhatsApp transport could not be started",
                            RuntimeHostPhase.ENGINE_UNAVAILABLE,
                        )
                    }
            val configuredBridgeUrl = embeddedBridge.baseUrl
            val urls =
                runCatching { resolveBridgeUrls(configuredBridgeUrl) }
                    .getOrElse {
                        return@coroutineScope terminal(
                            session,
                            "Bridge address is invalid",
                            RuntimeHostPhase.AUTHENTICATION_REQUIRED,
                        )
                    }
            if (!BridgeControlToken.isValid(token)) {
                return@coroutineScope terminal(
                    session,
                    "Bridge access is invalid",
                    RuntimeHostPhase.AUTHENTICATION_REQUIRED,
                )
            }

            val socketHttp =
                graph.httpClient.newBuilder()
                    // OkHttpClient.newBuilder() otherwise shares the process-wide dispatcher.
                    // Session teardown calls cancelAll(), so the WebSocket needs its own dispatcher
                    // or it would also cancel unrelated AI, speech, catalog and media requests.
                    .dispatcher(Dispatcher())
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            val aiTimeout = snapshot.appInteger(AppSettingKeys.OPENROUTER_TIMEOUT_MS).toLong()
            // Zero means "no *additional* app timeout", which is not the same as no timeout at
            // all: writing it into the builder would override the shared client's own deadlines
            // with "wait forever", and speech and transcription — unlike chat completions — have
            // no watchdog of their own to fall back on. Leaving the shared client untouched keeps
            // them bounded, which is what the setting says it does.
            val aiHttp =
                if (aiTimeout <= 0L) {
                    graph.httpClient
                } else {
                    graph.httpClient.newBuilder()
                        .callTimeout(aiTimeout, TimeUnit.MILLISECONDS)
                        .readTimeout(aiTimeout, TimeUnit.MILLISECONDS)
                        .build()
                }
            // Chat completions are the one route that streams, so they are the one route where a
            // total call timeout is the wrong instrument: it cannot tell a dead socket from a model
            // that is still writing, and it fires soonest on the longest answers. A memory write
            // that condenses thousands of messages is exactly that answer, and killing it after the
            // provider has already generated it pays for the tokens and keeps nothing. This client
            // keeps the read (between-bytes) timeout and drops the total one; the gateway's own
            // silence watchdog is the outer guard.
            val chatHttp =
                if (aiTimeout <= 0L) {
                    graph.httpClient
                } else {
                    graph.httpClient.newBuilder()
                        .callTimeout(0, TimeUnit.MILLISECONDS)
                        .readTimeout(aiTimeout, TimeUnit.MILLISECONDS)
                        .build()
                }
            val openRouterBase = snapshot.appText(AppSettingKeys.OPENROUTER_BASE_URL).toHttpUrl()
            // Read for every provider request so /apikey set|clear takes effect
            // immediately while the admin/bridge path stays online.
            val apiKeyProvider =
                OpenRouterApiKeyProvider {
                    graph.secrets.get(SecretStore.OPENROUTER_API_KEY)
                }
            val attribution =
                OpenRouterAttribution(
                    referer =
                        snapshot.appText(AppSettingKeys.OPENROUTER_REFERER)
                            .takeIf(String::isNotBlank),
                    title =
                        snapshot.appText(AppSettingKeys.OPENROUTER_TITLE)
                            .takeIf(String::isNotBlank),
                )
            val aiObserver =
                RepositoryAiNetworkObserver(
                    activityWriter = graph.activityWriter,
                    // Two surfaces, one event: the chat gets the short red line where the
                    // countdown normally stands, and the chat list gets a notification the owner
                    // can close. Neither replaces the activity log, which keeps the full record.
                    providerProblem = { title, detail ->
                        graph.chatActivity.noteProblem(title, System.currentTimeMillis())
                        // A rejected credential is fixed in one place, so the row goes there. Any
                        // other status is not: the key is fine and there is nothing to change, so
                        // the row opens the full record of the call instead of a setting that would
                        // suggest the wrong repair.
                        val rejectedCredential = title.endsWith(" 401") || title.endsWith(" 403")
                        graph.controls.publishAlert(
                            RuntimeAlert(
                                kind =
                                    if (rejectedCredential) {
                                        AlertKind.API_KEY
                                    } else {
                                        AlertKind.PROVIDER
                                    },
                                title = title,
                                detail = detail,
                                settingKey =
                                    if (rejectedCredential) {
                                        SettingsTargets.OPENROUTER_API_KEY
                                    } else {
                                        SettingsTargets.ACTIVITY_LOG
                                    },
                                atMs = System.currentTimeMillis(),
                            ),
                        )
                    },
                    providerRecovered = {
                        graph.chatActivity.clearProblem()
                        graph.controls.clearAlert(AlertKind.PROVIDER)
                        graph.controls.clearAlert(AlertKind.API_KEY)
                    },
                )
            val gateway =
                OpenRouterClient(
                    httpClient = chatHttp,
                    baseUrl = openRouterBase,
                    apiKeyProvider = apiKeyProvider,
                    attribution = attribution,
                    observer = aiObserver,
                    stallTimeoutMs = OpenRouterClient.stallDeadlineFor(aiTimeout),
                    firstPartyProviderOnly = {
                        graph.settings
                            .snapshot()
                            .boolean(BotSettingKeys.FIRST_PARTY_PROVIDER_ONLY)
                    },
                )
            val bridge: BridgeTransport = BridgeClient(socketHttp, this)
            val media = BridgeMediaClient(graph.httpClient, urls.mediaBaseUrl, token)
            val keys = MessageKeyCache()
            val store =
                RepositoryEngineStore(
                    repository = graph.repository,
                    settings = graph.settings,
                    activityChanged = graph.controls::notifyActivityChanged,
                    limitChanged = graph.controls::publishLimit,
                )
            // Built before the dispatcher because the dispatcher hands it every delivery no turn is
            // waiting for any more — the case where a message reaches the contact and nothing on
            // this side writes it down.
            val historyReconciler =
                OutboxHistoryReconciler(
                    repository = graph.repository,
                    settings = graph.settings,
                    store = store,
                    activityChanged = graph.controls::notifyActivityChanged,
                )
            val durableActions =
                DurableBridgeOutboxDispatcher(
                    parentScope = this,
                    repository = graph.repository,
                    bridge = bridge,
                    activityChanged = graph.controls::notifyActivityChanged,
                    onUnclaimedDelivery = { historyReconciler.recover(it) },
                )
            val whatsapp =
                BridgeWhatsAppActions(
                    bridge = bridge,
                    keys = keys,
                    approvedMedia = graph.approvedMedia,
                    durableActions = durableActions,
                )
            val approvedImageSender =
                ApprovedImageSender { personaKey, assetId, targetChatId, caption, requestId ->
                    val handle = graph.approvedMedia.openForSend(assetId, personaKey)
                    val reservationId = "admin-image:$requestId"
                    val now = System.currentTimeMillis()
                    when (
                        val decision =
                            store.reserveOutbound(
                                OutboundIntent(
                                    reservationId = reservationId,
                                    chatJid = targetChatId,
                                    proactive = false,
                                    admin = true,
                                    textHash =
                                        manualImagePayloadHash(
                                            handle.asset.sha256,
                                            caption,
                                        ),
                                    timestampMs = now,
                                ),
                            )
                    ) {
                        is OutboundDecision.Allowed -> Unit
                        is OutboundDecision.Deferred ->
                            error("Sending paused for safety: ${decision.reason}")
                        is OutboundDecision.Blocked ->
                            error("Sending blocked: ${decision.reason}")
                    }
                    try {
                        val uploaded =
                            media.upload(
                                file = handle.file,
                                mimeType = handle.asset.mimeType,
                                maxBytes = ApprovedMediaAssetStore.DEFAULT_MAX_ASSET_BYTES,
                            )
                        check(uploaded.sizeBytes == handle.asset.sizeBytes) {
                            "Bridge upload size mismatch"
                        }
                        uploaded.sha256?.let {
                            check(it.equals(handle.asset.sha256, ignoreCase = true)) {
                                "Bridge upload integrity mismatch"
                            }
                        }
                        val transportMessageId =
                            whatsapp.sendMedia(
                                targetChatId,
                                PlannedSideEffect.SendMedia(
                                    idempotencyKey = reservationId,
                                    uploadId = uploaded.uploadId,
                                    mimeType = handle.asset.mimeType,
                                    voiceNote = false,
                                    caption = caption,
                                    approvedAssetId = handle.asset.assetId,
                                    approvedPersonaKey = handle.asset.personaKey,
                                ),
                            )
                        store.completeOutbound(
                            reservationId = reservationId,
                            transportMessageId = transportMessageId,
                            success = true,
                            timestampMs = System.currentTimeMillis(),
                        )
                        transportMessageId
                    } catch (cancelled: CancellationException) {
                        // Outbox/transport completion is ambiguous; leave the
                        // reservation and repeat marker conservative.
                        throw cancelled
                    } catch (failure: Throwable) {
                        store.completeOutbound(
                            reservationId = reservationId,
                            transportMessageId = null,
                            success = false,
                            timestampMs = System.currentTimeMillis(),
                        )
                        throw failure
                    }
                }
            val manualTextSender =
                ManualTextSender { targetChatId, text, requestId ->
                    val reservationId = "admin-text:$requestId"
                    when (
                        val decision =
                            store.reserveOutbound(
                                OutboundIntent(
                                    reservationId = reservationId,
                                    chatJid = targetChatId,
                                    proactive = false,
                                    admin = true,
                                    textHash = sha256Text(text),
                                    payloadChars = text.length,
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            )
                    ) {
                        is OutboundDecision.Allowed -> Unit
                        is OutboundDecision.Deferred ->
                            error("Sending paused for safety: ${decision.reason}")
                        is OutboundDecision.Blocked ->
                            error("Sending blocked: ${decision.reason}")
                    }
                    try {
                        val transportMessageId =
                            whatsapp.sendText(
                                targetChatId,
                                text,
                                quoteMessageId = null,
                                idempotencyKey = reservationId,
                            )
                        store.completeOutbound(
                            reservationId = reservationId,
                            transportMessageId = transportMessageId,
                            success = true,
                            timestampMs = System.currentTimeMillis(),
                        )
                        transportMessageId
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        store.completeOutbound(
                            reservationId = reservationId,
                            transportMessageId = null,
                            success = false,
                            timestampMs = System.currentTimeMillis(),
                        )
                        throw failure
                    }
                }
            val admin =
                graph.controls.currentAdminActions()
                    ?: run {
                        return@coroutineScope terminal(
                            session,
                            "The admin engine is not initialised",
                            RuntimeHostPhase.ENGINE_UNAVAILABLE,
                        )
                    }
            val settingsProvider =
                RepositoryEngineSettingsProvider(graph.settings, graph.repository)
            val mediaAnalyzer =
                IncomingMediaAnalyzer(
                    bridgeMedia = media,
                    repository = graph.repository,
                    gateway = gateway,
                    activityChanged = graph.controls::notifyActivityChanged,
                    transcriptionModel = {
                        graph.settings.snapshot().appText(AppSettingKeys.STT_FALLBACK_MODEL)
                    },
                    // The catalogue the settings screen already fetched, so asking whether the
                    // media model takes audio costs nothing on the hot path.
                    mediaModelCapabilities =
                        CatalogMediaModelCapabilities(
                            ModelCatalogClient(
                                httpClient = aiHttp,
                                baseUrl = openRouterBase,
                                apiKeyProvider = apiKeyProvider,
                                cache = graph.modelCatalogCache,
                                attribution = attribution,
                                observer = aiObserver,
                            ),
                        ),
                    transcriptionFallback =
                        OkHttpTranscriptionFallbackClient(aiHttp) {
                            val current = graph.settings.snapshot()
                            val model = current.appText(AppSettingKeys.STT_FALLBACK_MODEL).trim()
                            val usesOpenRouter = model.contains('/')
                            val key =
                                if (usesOpenRouter) {
                                    graph.secrets.get(SecretStore.OPENROUTER_API_KEY)
                                } else {
                                    graph.secrets.get(
                                        current.secretReference(AppSettingKeys.STT_API_KEY_REF),
                                    )
                                }
                            if (key.isNullOrBlank()) {
                                null
                            } else {
                                TranscriptionFallbackConfiguration(
                                    endpointUrl =
                                        if (usesOpenRouter) {
                                            openRouterBase.newBuilder()
                                                .addPathSegment("audio")
                                                .addPathSegment("transcriptions")
                                                .build()
                                                .toString()
                                        } else {
                                            current.appText(AppSettingKeys.STT_FALLBACK_URL)
                                        },
                                    model = model,
                                    apiKey = key,
                                )
                            }
                        },
                )
            val ai =
                OpenRouterAiTurnRunner(
                    context = applicationContext,
                    settingsRepository = graph.settings,
                    repository = graph.repository,
                    gateway = gateway,
                    mediaAnalyzer = mediaAnalyzer,
                    speechClient =
                        SpeechClient(
                            httpClient = aiHttp,
                            baseUrl = openRouterBase,
                            apiKeyProvider = apiKeyProvider,
                            attribution = attribution,
                            observer = aiObserver,
                            firstPartyProviderOnly = {
                                graph.settings
                                    .snapshot()
                                    .boolean(BotSettingKeys.FIRST_PARTY_PROVIDER_ONLY)
                            },
                    ),
                    imageClient =
                        OpenRouterImageClient(
                            httpClient = aiHttp,
                            baseUrl = openRouterBase,
                            apiKeyProvider = apiKeyProvider,
                            attribution = attribution,
                            observer = aiObserver,
                            firstPartyProviderOnly = {
                                graph.settings
                                    .snapshot()
                                    .boolean(BotSettingKeys.FIRST_PARTY_PROVIDER_ONLY)
                            },
                        ),
                    bridgeMedia = media,
                    approvedMedia = graph.approvedMedia,
                    activityChanged = graph.controls::notifyActivityChanged,
                    activity = graph.chatActivity,
                    memoryWork = graph.memoryWork,
                    proactivePersistence = store,
                )
            val engine =
                BotEngine(
                    parentScope = this,
                    store = store,
                    settingsProvider = settingsProvider,
                    commands = CommandGatewayAdapter(admin, graph.settings),
                    ai = ai,
                    whatsapp = whatsapp,
                    proactivePersistence = store,
                    // Lives on the graph, not the engine: the engine is rebuilt on every
                    // reconnect, the screen watching it is not.
                    activity = graph.chatActivity,
                    // Same reason, one step stronger: in low power mode the link is
                    // dropped and the engine torn down between sessions, so the thing
                    // that decides when to come back cannot live inside the engine.
                    linkPower = graph.linkPower,
                    // One shared human online-session clock owns both sparse presence and when
                    // ordinary proactive candidates may be evaluated. Low Battery uses the same
                    // published deadline as its wake alarm; Default keeps the socket up but still
                    // does not let proactive work escape those sessions.
                    selfOnlineSessions = true,
                    profilePictures =
                        // Traced to logcat because this is the one feature whose
                        // failure is invisible from both sides: the account simply
                        // keeps the picture it had, and neither WhatsApp nor the app
                        // says why. The persona key is the only payload — no chat
                        // content — and a normal day produces a handful of lines.
                        LoggedProfilePictures(
                            ProfilePictureRotator(
                                stateFile = File(applicationContext.filesDir, ProfilePictureRotator.STATE_FILE_NAME),
                                workDir = File(applicationContext.cacheDir, PROFILE_PICTURE_WORK_DIR),
                                openAsset = applicationContext.assets::open,
                                library = graph.profilePictures,
                                enabled = {
                                    graph.settings.snapshot().boolean(BotSettingKeys.PROFILE_PICTURE_ENABLED)
                                },
                                intervalMsProvider = {
                                    val days =
                                        graph.settings.snapshot()
                                            .integer(BotSettingKeys.PROFILE_PICTURE_INTERVAL_DAYS)
                                            .toLong()
                                    val interval = days * 24L * 60L * 60L * 1_000L
                                    interval..interval
                                },
                                applyPicture = { picture ->
                                    try {
                                        val uploaded =
                                            media.upload(
                                                file = picture,
                                                mimeType = "image/jpeg",
                                                maxBytes = ApprovedMediaAssetStore.DEFAULT_MAX_ASSET_BYTES,
                                            )
                                        Log.i(TAG, "profile picture: staged ${picture.name} as ${uploaded.uploadId}")
                                        bridge.action(
                                            action = "set_profile_picture",
                                            payload = JSONObject().put("mediaId", uploaded.uploadId),
                                        )
                                        Log.i(TAG, "profile picture: WhatsApp accepted it")
                                    } catch (error: Throwable) {
                                        if (error !is CancellationException) {
                                            Log.w(
                                                TAG,
                                                "profile picture: refused " +
                                                    "error=${privacySafeErrorType(error)}",
                                            )
                                        }
                                        throw error
                                    }
                                },
                                applyAbout = { text ->
                                    try {
                                        bridge.action(
                                            action = "set_status_message",
                                            payload = JSONObject().put("text", text),
                                        )
                                    } catch (error: Throwable) {
                                        if (error !is CancellationException) {
                                            Log.w(
                                                TAG,
                                                "info line: refused " +
                                                    "error=${privacySafeErrorType(error)}",
                                            )
                                        }
                                        throw error
                                    }
                                },
                            ),
                        ),
                )
            ai.attachGlobalMemoryGate { engine.holdAllForMemoryWrite() }
            val importedMemorySessions =
                ImportedMemorySessionFactory { chatJid, personaKey ->
                    val conversationKey = "$chatJid#$personaKey"
                    val hold = engine.holdForMemoryWrite(conversationKey)
                    object : ImportedMemorySession {
                        override suspend fun awaitReady() = hold.awaitReady()

                        override suspend fun refresh(): String =
                            when (
                                val refresh =
                                    ai.refreshImportedChat(
                                        conversationKey = conversationKey,
                                        personaKey = personaKey,
                                    )
                            ) {
                                is MemoryRefreshOutcome.Updated ->
                                    "Memory created (revision ${refresh.chatRevision})"

                                is MemoryRefreshOutcome.Skipped ->
                                    error("Memory creation skipped: ${refresh.reasonCode}")

                                is MemoryRefreshOutcome.Failed ->
                                    error("Memory creation failed: ${refresh.reasonCode}")
                            }

                        override fun close() = hold.close()
                    }
                }
            val memoryMaintenance =
                object : MemoryMaintenance {
                    /**
                     * The same gate the automatic cadence gets: while this runs, the chat's next
                     * turn waits instead of answering from the memory this write is replacing.
                     * Without it, "Create Memory" was the one write the engine could not see, and
                     * the bot went on replying straight through it.
                     *
                     * Deliberately no `awaitReady()`: that also joins the turn currently running,
                     * which may itself still be about to wait on this very gate. Two writers for
                     * one conversation are already impossible — the refresh service holds a
                     * per-conversation lock across the whole call.
                     */
                    override suspend fun writeChatMemory(
                        chatJid: String,
                        personaKey: String,
                    ): MemoryWriteResult {
                        val conversationKey = "$chatJid#$personaKey"
                        return engine
                            .holdForMemoryWrite(conversationKey, mustComplete = false)
                            .use {
                                describe(
                                    ai.refreshChatMemoryNow(
                                        conversationKey = conversationKey,
                                        personaKey = personaKey,
                                    ),
                                )
                            }
                    }

                    override suspend fun writeGlobalMemory(personaKey: String): MemoryWriteResult =
                        describe(ai.synthesizePersonaMemoryNow(personaKey))

                    private fun describe(outcome: MemoryRefreshOutcome): MemoryWriteResult =
                        when (outcome) {
                            is MemoryRefreshOutcome.Updated ->
                                MemoryWriteResult(
                                    written = true,
                                    detail =
                                        if (outcome.personaSynthesized) {
                                            "Memory written · global memory rebuilt"
                                        } else {
                                            "Memory written"
                                        },
                                )

                            // Nothing new since the last write. Not a fault, and saying so beats a
                            // success message for a memory that was never touched.
                            is MemoryRefreshOutcome.Skipped ->
                                MemoryWriteResult(
                                    written = false,
                                    detail = "Nothing new to remember (${outcome.reasonCode})",
                                )

                            is MemoryRefreshOutcome.Failed ->
                                MemoryWriteResult(
                                    written = false,
                                    detail = "Memory failed: ${outcome.reasonCode}",
                                )
                        }
                }
            // Every other reader pulls settings when it needs them. Two switches
            // cannot wait that long — see [BotEngine.onSettingsChanged] — so the
            // change Flow is followed here. `drop(1)` skips the StateFlow's
            // current value: starting the runtime is not a settings change. The
            // state as it stands at boot is not ignored, it is reconciled from
            // `BotEngine.startRecovery()` instead, where the bridge is actually
            // connected and a picture change can be delivered.
            launch {
                var activePushPersona = snapshot.text(BotSettingKeys.PERSONALITY)
                graph.settings.changes.drop(1).collect { changed ->
                    val selectedPersona = changed.text(BotSettingKeys.PERSONALITY)
                    if (selectedPersona != activePushPersona) {
                        activePushPersona = selectedPersona
                        syncPushName(bridge, selectedPersona)
                    }
                    runCatching { engine.onSettingsChanged() }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            Log.w(
                                TAG,
                                "settings change follow-up failed " +
                                    "error=${privacySafeErrorType(error)}",
                            )
                        }
                }
            }
            // The proactivity screen's "write this person" button. It runs the same generated
            // turn the scheduler would, so nothing about the message or its gates is special —
            // only its timing is, and the refusal reason has to reach the user verbatim.
            val outreachTrigger =
                OutreachTrigger { chatJid, note ->
                    when (val result = engine.writeContactNow(chatJid, note)) {
                        ProactiveTurnOutcome.Sent ->
                            OutreachResult(sent = true, detail = "Message sent")

                        ProactiveTurnOutcome.Silent ->
                            OutreachResult(
                                sent = false,
                                detail = "She decided not to write anything right now",
                            )

                        is ProactiveTurnOutcome.Deferred -> {
                            val minutes =
                                ((result.untilMs - System.currentTimeMillis()) / 60_000L)
                                    .coerceAtLeast(1L)
                            OutreachResult(
                                sent = false,
                                detail =
                                    "${result.reason ?: "Safety hold"} · " +
                                        "available again in about ${minutes}min",
                            )
                        }

                        is ProactiveTurnOutcome.Blocked ->
                            OutreachResult(
                                sent = false,
                                detail =
                                    result.reason
                                        ?: if (result.hard) {
                                            "Sending to this contact is blocked"
                                        } else {
                                            "Sending is paused right now, try again later"
                                        },
                            )

                        ProactiveTurnOutcome.Cancelled ->
                            OutreachResult(
                                sent = false,
                                detail = "The bot is paused or does not know this contact",
                            )
                    }
                }
            val chatSettingsChangeHandler =
                de.totec.doppel.integration.ChatSettingsChangeHandler { chatJid ->
                    engine.onChatSettingsChanged(chatJid)
                }
            // A hand-woken link should look like her picking the phone up, not like a
            // socket reconnecting: same self-session window, same listening tail.
            val onlineSession =
                de.totec.doppel.integration.OnlineSessionTrigger { engine.showOnlineNow() }
            val outcome = CompletableDeferred<RuntimeHostResult>()
            val recoveryStarted = AtomicBoolean(false)
            val replayReady = AtomicBoolean(false)
            // Edge detector for "the link just came back". Recovery used to be a
            // one-shot latch, so the very first connect drained the pending queue and
            // every reconnect after that left waiting turns and outbox rows sitting
            // there until the process was restarted. Recovery has to run on each
            // transition *into* connected — and only on the transition, because the
            // native runtime now legitimately re-announces `connected` (e.g. after a
            // keepalive is restored) while the link never actually went down.
            val linkOnline = AtomicBoolean(false)
            // Seeded from disk, not from zero. A per-session counter made the debounce below
            // meaningless: every reconnect started at "never refreshed" and paid the full safety
            // IQ round again — and the native cache was cold for the same reason.
            val lastSafetyRefreshAt = AtomicLong(identity.lastSafetyRefreshAt())
            // Set by the start-up sweep below, so a link that comes up moments later does not
            // immediately repeat it. A dropped link is exactly when a send completes with no turn
            // left to write it down, so the sweep belongs on every recovery — but a flapping link
            // would otherwise rescan a month of outbox rows per flap.
            val lastHistorySweepAt = AtomicLong(0L)
            val sequenceGuard = BridgeSequenceGuard()
            val safetyController = TransportSafetyController(graph.repository)

            val settingsJob =
                launch {
                    var observedRevision = snapshot.revision
                    graph.settings.changes.collect { changed ->
                        if (changed.revision == observedRevision) return@collect
                        observedRevision = changed.revision
                        if (replayReady.get()) {
                            pushIngressPolicy(bridge, changed)
                        }
                        if (recoveryStarted.get()) {
                            // Also reconciles every proactive deadline, which is what makes a
                            // per-contact level slider act at once instead of at the next event.
                            engine.applyEnabledSetting(
                                changed.boolean(BotSettingKeys.ENABLED),
                            )
                        }
                    }
                }
            val statusJob =
                launch {
                    bridge.status.collect { status ->
                        RuntimeBridgeControl.publishStatus(bridge, status)
                        if (status.connection != BridgeConnectionState.CONNECTED) {
                            durableActions.setOnline(false)
                        }
                        when (status.connection) {
                            BridgeConnectionState.CONNECTED -> {
                                session.report(
                                    RuntimeHostReport(
                                        RuntimeHostPhase.ONLINE,
                                        "Connected",
                                    ),
                                )
                            }

                            BridgeConnectionState.PAIRING ->
                                session.report(
                                    RuntimeHostReport(
                                        RuntimeHostPhase.AUTHENTICATION_REQUIRED,
                                        "WhatsApp pairing required",
                                    ),
                                )

                            BridgeConnectionState.BACKING_OFF,
                            BridgeConnectionState.DISCONNECTED,
                            ->
                                session.report(
                                    RuntimeHostReport(
                                        RuntimeHostPhase.DEGRADED,
                                        "Reconnecting to WhatsApp",
                                    ),
                                )

                            BridgeConnectionState.ERROR ->
                                session.report(
                                    RuntimeHostReport(
                                        RuntimeHostPhase.DEGRADED,
                                        "Connection disturbed",
                                    ),
                                )

                            else ->
                                session.report(
                                    RuntimeHostReport(
                                        RuntimeHostPhase.CONNECTING,
                                        "Connecting the bridge",
                                    ),
                                )
                        }
                    }
                }
            val terminationJob =
                launch {
                    bridge.terminations.collect { termination ->
                        val result =
                            when (termination) {
                                is BridgeTermination.Failed ->
                                    if (termination.authenticationRejected) {
                                        RuntimeHostResult.TerminalFailure("Bridge access was refused")
                                    } else {
                                        RuntimeHostResult.RetryableFailure("Bridge is unreachable")
                                    }

                                is BridgeTermination.Closed ->
                                    if (termination.protocolFailure) {
                                        // Recoverable on purpose. The bridge journal is durable,
                                        // so the events this session missed are replayed on the
                                        // next connect — while a terminal verdict left the bot
                                        // dead until somebody noticed and re-paired it.
                                        RuntimeHostResult.RetryableFailure(
                                            "The bridge rejected a frame and the session ended",
                                        )
                                    } else {
                                        RuntimeHostResult.RetryableFailure("Bridge connection ended")
                                    }
                            }
                        outcome.complete(result)
                    }
                }
            // One frame's handling, lifted out of the collector so a failure can be retried
            // instead of taking the whole session — and with it the WhatsApp link — down with it.
            val frameJob =
                launch {
                    suspend fun isDuplicate(sequence: Long): Boolean {
                        if (sequenceGuard.classify(sequence) != BridgeSequenceDecision.DUPLICATE) {
                            return false
                        }
                        acknowledge(bridge, identity, sequence)
                        return true
                    }

                    suspend fun dispatchFrame(frame: BridgeFrame) {
                        when (frame) {
                            is BridgeFrame.Welcome -> {
                                rememberAccount(frame.accountJid, frame.accountName)
                                sequenceGuard.begin(frame)
                                identity.reconcileServerCursor(frame.resume.after)
                                if (frame.resume.gap || frame.resume.reset) {
                                    recordReplayDiscontinuity(frame)
                                }
                                durableActions.setOnline(false)
                            }
                            is BridgeFrame.Ready -> {
                                rememberAccount(frame.accountJid, frame.accountName)
                                sequenceGuard.markReady(frame.sequence)
                                pushIngressPolicy(bridge, graph.settings.snapshot())
                                replayReady.set(true)
                                val connected =
                                    frame.accountState == BridgeConnectionState.CONNECTED
                                durableActions.setOnline(connected)
                                if (!connected) linkOnline.set(false)
                                if (connected && linkOnline.compareAndSet(false, true)) {
                                    recoveryStarted.set(true)
                                    // The recovery condition for every lock that was armed by
                                    // transport trouble. Without it a single dropped session could
                                    // leave the bot silent for good.
                                    safetyController.onLinkRecovered()
                                    engine.startRecovery()
                                    launch {
                                        syncPushName(
                                            bridge,
                                            graph.settings.snapshot().text(BotSettingKeys.PERSONALITY),
                                        )
                                    }
                                    launch {
                                        sweepRecoveredHistory(
                                            historyReconciler,
                                            lastHistorySweepAt,
                                        )
                                    }
                                    launch {
                                        refreshTransportSafety(
                                            bridge,
                                            lastSafetyRefreshAt,
                                            identity,
                                        )
                                    }
                                }
                                refreshMetrics()
                            }
                            is BridgeFrame.Connection -> {
                                rememberAccount(frame.accountJid, frame.accountName)
                                if (isDuplicate(frame.sequence)) return
                                recordConnection(frame)
                                acknowledge(bridge, identity, frame.sequence)
                                sequenceGuard.commit(frame.sequence)
                                durableActions.setOnline(
                                    replayReady.get() &&
                                        frame.state == BridgeConnectionState.CONNECTED,
                                )
                                if (frame.state != BridgeConnectionState.CONNECTED) {
                                    linkOnline.set(false)
                                }
                                when (frame.state) {
                                    BridgeConnectionState.CONNECTED -> {
                                        // Resumes anything the drop left in flight:
                                        // pending inbound batches and the proactive
                                        // scheduler. Safe to repeat — recoverPending
                                        // only returns work that is still pending.
                                        if (
                                            replayReady.get() &&
                                            linkOnline.compareAndSet(false, true)
                                        ) {
                                            recoveryStarted.set(true)
                                            safetyController.onLinkRecovered()
                                            engine.startRecovery()
                                            launch {
                                                syncPushName(
                                                    bridge,
                                                    graph.settings
                                                        .snapshot()
                                                        .text(BotSettingKeys.PERSONALITY),
                                                )
                                            }
                                            launch {
                                                sweepRecoveredHistory(
                                                    historyReconciler,
                                                    lastHistorySweepAt,
                                                )
                                            }
                                            launch {
                                                refreshTransportSafety(
                                                    bridge,
                                                    lastSafetyRefreshAt,
                                                    identity,
                                                )
                                            }
                                        }
                                        refreshMetrics()
                                    }

                                    BridgeConnectionState.ERROR ->
                                        outcome.complete(
                                            RuntimeHostResult.TerminalFailure(
                                                "WhatsApp is asking for a manual review",
                                            ),
                                        )

                                    else -> Unit
                                }
                            }

                            is BridgeFrame.PairingCode -> {
                                if (isDuplicate(frame.sequence)) return
                                session.report(
                                    RuntimeHostReport(
                                        RuntimeHostPhase.AUTHENTICATION_REQUIRED,
                                        "Request a pairing code in the app",
                                    ),
                                )
                                acknowledge(bridge, identity, frame.sequence)
                                sequenceGuard.commit(frame.sequence)
                            }

                            is BridgeFrame.Incoming -> {
                                if (isDuplicate(frame.sequence)) return
                                keys.remember(frame.event)
                                engine.accept(frame.event)
                                acknowledge(bridge, identity, frame.sequence)
                                sequenceGuard.commit(frame.sequence)
                                refreshMetrics()
                                graph.controls.notifyActivityChanged()
                            }

                            is BridgeFrame.Delivery -> {
                                if (isDuplicate(frame.sequence)) return
                                val state = frame.status.deliveryState()
                                if (state != MessageDeliveryState.UNKNOWN) {
                                    val changed =
                                        graph.repository.updateMessageDelivery(frame.messageIds, state)
                                    // The row moved to delivered/read but nothing told the UI, so an
                                    // open conversation kept drawing the receipt it had when it was
                                    // opened — the last message stayed "sent" while the other phone
                                    // was already reading it. Costs no WhatsApp traffic: receipts are
                                    // pushed to us, this only reloads what is already on disk, and
                                    // only when a row actually changed.
                                    if (changed > 0) graph.controls.notifyActivityChanged()
                                }
                                acknowledge(bridge, identity, frame.sequence)
                                sequenceGuard.commit(frame.sequence)
                            }

                            is BridgeFrame.Safety -> {
                                if (isDuplicate(frame.sequence)) return
                                val changed = safetyController.apply(frame)
                                if (changed) {
                                    val narration = safetyController.describe(frame)
                                    graph.repository.appendActivity(
                                        ActivityLogRecord(
                                            occurredAt = System.currentTimeMillis(),
                                            level = narration.level,
                                            category = "transport_safety",
                                            action = frame.kind.take(100),
                                            summary = narration.summary,
                                            detailsJson =
                                                privacySafeSafetyDetail(frame.kind, frame.detail),
                                        ),
                                    )
                                }
                                acknowledge(bridge, identity, frame.sequence)
                                sequenceGuard.commit(frame.sequence)
                                if (changed) graph.controls.notifyActivityChanged()
                            }

                            is BridgeFrame.Undecodable -> {
                                if (isDuplicate(frame.sequence)) return
                                // Committed like any other event so the stream stays
                                // contiguous, and surfaced in the operator log because a
                                // skipped frame may have been a real message.
                                Log.w(
                                    TAG,
                                    "Bridge event skipped: type=${frame.frameType} " +
                                        "sequence=${frame.sequence}",
                                )
                                graph.repository.appendActivity(
                                    ActivityLogRecord(
                                        occurredAt = System.currentTimeMillis(),
                                        level = ActivityLevel.WARN,
                                        category = "bridge_protocol",
                                        action = "frame_skipped",
                                        summary =
                                            "A bridge event could not be read and was skipped",
                                        detailsJson =
                                            JSONObject()
                                                .put("frameType", frame.frameType)
                                                .put("sequence", frame.sequence)
                                                .put("reason", frame.reason)
                                                .toString(),
                                    ),
                                )
                                acknowledge(bridge, identity, frame.sequence)
                                sequenceGuard.commit(frame.sequence)
                                graph.controls.notifyActivityChanged()
                            }

                            is BridgeFrame.ProtocolError -> {
                                if (!frame.retryable) {
                                    outcome.complete(
                                        RuntimeHostResult.TerminalFailure(
                                            "The bridge refused the session",
                                        ),
                                    )
                                }
                            }

                            is BridgeFrame.ActionResult -> Unit
                        }
                    }

                    // A frame whose Android-side effect fails is retried in place, and only a frame
                    // that keeps failing is quarantined: logged, acknowledged and committed so the
                    // stream stays contiguous.
                    //
                    // Ending the session here was the real defect. Every retryable end tore the
                    // native core down, the journal replayed the same frame on reconnect, and it
                    // failed the same way — a permanent relink loop against WhatsApp driven by one
                    // bad event. Handshake frames are exempt: a Welcome or Ready that cannot be
                    // processed is not a poison event, it is a session that must not continue.
                    suspend fun quarantineFrame(
                        frame: BridgeFrame,
                        sequence: Long,
                        failure: Throwable,
                    ) {
                        val errorType = privacySafeErrorType(failure)
                        Log.e(
                            TAG,
                            "Bridge frame quarantined: sequence=$sequence " +
                                "type=${frame.javaClass.simpleName} error=$errorType",
                        )
                        runCatching {
                            graph.repository.appendActivity(
                                ActivityLogRecord(
                                    occurredAt = System.currentTimeMillis(),
                                    level = ActivityLevel.ERROR,
                                    category = "bridge_protocol",
                                    action = "frame_quarantined",
                                    summary =
                                        "A bridge event could not be processed and was skipped",
                                    detailsJson =
                                        JSONObject()
                                            .put("frameType", frame.javaClass.simpleName)
                                            .put("sequence", sequence)
                                            .put("attempts", MAX_FRAME_ATTEMPTS)
                                            .put("error", errorType)
                                            .toString(),
                                ),
                            )
                            graph.controls.notifyActivityChanged()
                        }
                        sequenceGuard.quarantine(sequence)
                        runCatching { acknowledge(bridge, identity, sequence) }
                    }

                    try {
                        bridge.frames.collect { frame ->
                            val quarantinable = frame.quarantinableSequence()
                            var attempt = 1
                            while (true) {
                                val failure =
                                    try {
                                        dispatchFrame(frame)
                                        null
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        error
                                    }
                                if (failure == null) break
                                if (quarantinable == null) throw failure
                                if (attempt >= MAX_FRAME_ATTEMPTS) {
                                    quarantineFrame(frame, quarantinable, failure)
                                    break
                                }
                                // Releases the guard's pending slot, otherwise the retry trips over
                                // its own half-processed predecessor instead of the real fault.
                                sequenceGuard.abandon(quarantinable)
                                Log.w(
                                    TAG,
                                    "Bridge frame failed, retrying: sequence=$quarantinable " +
                                        "attempt=$attempt " +
                                        "error=${privacySafeErrorType(failure)}",
                                )
                                delay(FRAME_RETRY_DELAY_MS * attempt)
                                attempt += 1
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val errorType = privacySafeErrorType(error)
                        Log.e(TAG, "Bridge frame processing failed: error=$errorType")
                        // Written to the operator log as well: this path used to be visible only
                        // over adb, which is why a stream that kept failing here looked like a
                        // bot that simply refused to start.
                        graph.repository.appendActivity(
                            ActivityLogRecord(
                                occurredAt = System.currentTimeMillis(),
                                level = ActivityLevel.ERROR,
                                category = "bridge_protocol",
                                action = "event_failed",
                                summary = "A bridge event could not be processed",
                                detailsJson =
                                    JSONObject()
                                        .put("error", errorType)
                                        .toString(),
                            ),
                        )
                        graph.controls.notifyActivityChanged()
                        outcome.complete(
                            RuntimeHostResult.RetryableFailure("A bridge event could not be processed"),
                        )
                    }
                }

            // Lets the UI pull one fresh aggregate the moment it attaches, which is what makes
            // skipping the per-frame recomputation above safe.
            val metricsRefresh = { refreshMetrics(force = true) }

            try {
                // Every process-global registration happens inside the try, so a throw on the way
                // to `connect` cannot leave the static senders, the trigger and the metrics hook
                // bound to a session that is already unwinding. The jobs above need no such care:
                // they are children of this scope and die with it.
                RuntimeBridgeControl.attach(bridge, durableActions)
                RuntimeBridgeControl.attachApprovedImageSender(approvedImageSender)
                RuntimeBridgeControl.attachManualTextSender(manualTextSender)
                RuntimeBridgeControl.attachOutreachTrigger(outreachTrigger)
                RuntimeBridgeControl.attachChatSettingsChangeHandler(chatSettingsChangeHandler)
                RuntimeBridgeControl.attachImportedMemorySessions(importedMemorySessions)
                RuntimeBridgeControl.attachMemoryMaintenance(memoryMaintenance)
                RuntimeBridgeControl.attachOnlineSession(onlineSession)
                graph.controls.bindMetricsRefresh(metricsRefresh)
                // Anything the outbox delivered while nothing was left to write it down — including
                // whatever earlier versions lost this way — goes back into the chat log now. It is
                // deliberately not gated on the bridge being up: it only reads rows that already
                // completed, and the history it repairs is what the first turn after start reads.
                launch { sweepRecoveredHistory(historyReconciler, lastHistorySweepAt) }
                reportDevicePowerState()
                session.report(
                    RuntimeHostReport(
                        RuntimeHostPhase.CONNECTING,
                        "Opening a secure bridge connection",
                    ),
                )
                bridge.connect(
                    BridgeEndpoint(
                        socketUrl = urls.socketUrl,
                        token = token,
                        clientId = identity.clientId(),
                    ),
                    resumeAfter = identity.lastAcknowledgedSequence(),
                )
                outcome.await().also { result ->
                    recordSessionEnd(
                        result,
                        cursor =
                            runCatching { identity.lastAcknowledgedSequence() }
                                .getOrDefault(UNKNOWN_RESUME_CURSOR),
                    )
                }
            } finally {
                ai.detachGlobalMemoryGate()
                graph.controls.unbindMetricsRefresh(metricsRefresh)
                frameJob.cancel()
                terminationJob.cancel()
                settingsJob.cancel()
                statusJob.cancel()
                durableActions.setOnline(false)
                engine.close()
                RuntimeBridgeControl.detachApprovedImageSender(approvedImageSender)
                RuntimeBridgeControl.detachManualTextSender(manualTextSender)
                RuntimeBridgeControl.detachOutreachTrigger(outreachTrigger)
                RuntimeBridgeControl.detachChatSettingsChangeHandler(chatSettingsChangeHandler)
                RuntimeBridgeControl.detachImportedMemorySessions(importedMemorySessions)
                RuntimeBridgeControl.detachMemoryMaintenance(memoryMaintenance)
                RuntimeBridgeControl.detachOnlineSession(onlineSession)
                RuntimeBridgeControl.detach(bridge, durableActions)
                durableActions.close()
                bridge.close()
                // The native core is deliberately not closed here — see [EmbeddedNativeBridge].
                socketHttp.dispatcher.cancelAll()
            }
        }

    /**
     * Every way this host can end a session funnels through here, so the reason is written once
     * and in plain text.
     *
     * Before this, the reason existed only as a `Log.e` line and as transient UI status: the
     * activity log showed nothing but "Bot service started"/"Bot service stopped" pairs, and a
     * session that died in seconds looked identical to a clean stop. The failure that actually
     * killed the link was readable only over adb, and only until MIUI rotated the buffer.
     *
     * The resume cursor is recorded alongside it. It is the one number that says whether a
     * restarting session will attempt the same replay again.
     */
    private fun recordSessionEnd(
        result: RuntimeHostResult,
        cursor: Long,
    ) {
        val (level, detail) =
            when (result) {
                is RuntimeHostResult.TerminalFailure -> ActivityLevel.ERROR to result.detail
                // A retryable end carries no detail when the socket simply went away. Saying so
                // beats an empty row: the timestamp is still the evidence of when it happened.
                is RuntimeHostResult.RetryableFailure ->
                    ActivityLevel.WARN to
                        (result.detail?.takeIf(String::isNotBlank) ?: "The bridge session ended")
                RuntimeHostResult.Completed -> return
            }
        Log.w(TAG, "Bridge session ended: $detail (resumeAfter=$cursor)")
        graph.repository.appendActivity(
            ActivityLogRecord(
                occurredAt = System.currentTimeMillis(),
                level = level,
                category = "bridge_session",
                action =
                    if (level == ActivityLevel.ERROR) "session_terminal" else "session_retryable",
                summary = detail,
                detailsJson =
                    JSONObject()
                        .put("detail", detail)
                        .put("resumeAfter", cursor)
                        .put("terminal", level == ActivityLevel.ERROR)
                        .toString(),
            ),
        )
        graph.controls.notifyActivityChanged()
    }

    /**
     * Puts delivered-but-unlogged messages back into the history, at most once per
     * [HISTORY_SWEEP_DEBOUNCE_MS].
     *
     * A link that drops is the situation the reconciler is for: the dispatcher can complete a send
     * while the turn that asked for it is already gone, and then nothing writes "You sent: …". So
     * this runs on start *and* on every recovery. The debounce is what keeps that affordable — a
     * flapping link announces `connected` repeatedly, and each sweep is a month-wide scan of the
     * outbox.
     */
    private suspend fun sweepRecoveredHistory(
        reconciler: OutboxHistoryReconciler,
        lastSweepAt: AtomicLong,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastSweepAt.get()
        if (previous != 0L && now - previous < HISTORY_SWEEP_DEBOUNCE_MS) return
        // Claim the slot before the scan, not after: two recovery edges can land together, and the
        // loser must skip rather than run the same scan alongside the winner.
        if (!lastSweepAt.compareAndSet(previous, now)) return
        reconciler.sweep()
    }

    private suspend fun refreshTransportSafety(
        bridge: BridgeTransport,
        lastRefreshAt: AtomicLong,
        identity: BridgeIdentityStore,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastRefreshAt.get()
        if (!force && now - previous < SAFETY_REFRESH_DEBOUNCE_MS) return
        if (!lastRefreshAt.compareAndSet(previous, now)) return
        identity.recordSafetyRefreshAt(now)
        runCatching { bridge.action("safety_refresh") }
            .onFailure { failure ->
                graph.repository.appendActivity(
                    ActivityLogRecord(
                        level = ActivityLevel.WARN,
                        category = "transport_safety",
                        action = "automatic_refresh_failed",
                        summary = "The automatic WhatsApp safety check failed",
                        detailsJson =
                            JSONObject()
                                .put("error", failure.javaClass.simpleName.take(120))
                                .apply {
                                    (failure as? de.totec.doppel.transport.BridgeActionException)
                                        ?.code
                                        ?.take(120)
                                        ?.let { put("errorCode", it) }
                                }
                                .toString(),
                    ),
                )
                graph.controls.notifyActivityChanged()
            }
    }

    private suspend fun pushIngressPolicy(
        bridge: BridgeTransport,
        snapshot: SettingsSnapshot,
    ) {
        val configured =
            snapshot.appStringList(AppSettingKeys.ALLOWLIST_NUMBERS) +
                snapshot.appStringList(AppSettingKeys.GROUP_ALLOWLIST) +
                snapshot.appStringList(AppSettingKeys.OWNER_NUMBERS) +
                snapshot.appStringList(AppSettingKeys.ADMIN_NUMBERS)
        val databaseSubjects =
            graph.repository
                .listAccessEntries(enabledOnly = true)
                .asSequence()
                .filter {
                    it.listKind in
                        setOf(
                            AccessListKind.ALLOW,
                            AccessListKind.GROUP_ALLOW,
                            AccessListKind.ADMIN,
                        )
                }
                .map { it.subjectId }
                .toList()
        val allowedJids =
            (configured + databaseSubjects)
                .asSequence()
                .mapNotNull(::canonicalIngressJid)
                .distinct()
                .take(MAX_INGRESS_JIDS)
                .toList()
        val mediaKinds =
            buildList {
                if (snapshot.boolean(BotSettingKeys.VISION_ENABLED)) add("image")
                if (snapshot.boolean(BotSettingKeys.STT_ENABLED)) add("audio")
                if (snapshot.boolean(BotSettingKeys.VIDEO_ENABLED)) add("video")
            }
        bridge.action(
            action = "set_ingress_policy",
            payload =
                JSONObject()
                    .put(
                        "allowAll",
                        snapshot.appBoolean(AppSettingKeys.ALLOW_ALL),
                    )
                    .put("allowedJids", JSONArray(allowedJids))
                    .put("mediaKinds", JSONArray(mediaKinds)),
        )
    }

    private fun canonicalIngressJid(raw: String): String? {
        val value = raw.trim().lowercase()
        if (INGRESS_JID.matches(value)) return value
        val digits = value.substringBefore('@').filter(Char::isDigit)
        return digits
            .takeIf { it.length in 6..15 }
            ?.let { "$it@s.whatsapp.net" }
    }

    private fun acknowledge(
        bridge: BridgeTransport,
        identity: BridgeIdentityStore,
        sequence: Long,
    ) {
        check(bridge.acknowledge(sequence)) { "Bridge acknowledgement failed" }
        identity.acknowledge(sequence)
    }

    private fun recordReplayDiscontinuity(frame: BridgeFrame.Welcome) {
        val resume = frame.resume
        graph.repository.reserveOutbound(
            OutboundSafetyRecord(
                dedupeKey =
                    "safety:bridge-replay:${resume.after}:${resume.oldest}:${resume.latest}",
                outboundKind = "safety_lock",
                decision = DbOutboundDecision.REVIEW,
                reasonCode = if (resume.gap) "bridge_replay_gap" else "bridge_journal_reset",
                status = OutboundStatus.RESERVED,
                plannedAt = System.currentTimeMillis(),
                metadataJson =
                    JSONObject()
                        .put("requested", resume.requested)
                        .put("after", resume.after)
                        .put("oldest", resume.oldest)
                        .put("latest", resume.latest)
                        .put("gap", resume.gap)
                        .put("reset", resume.reset)
                        .toString(),
            ),
        )
        graph.repository.appendActivity(
            ActivityLogRecord(
                level = ActivityLevel.WARN,
                category = "transport_safety",
                action = "replay_discontinuity",
                summary =
                    "The bridge history had a gap; sending stays locked until an admin reviews it.",
            ),
        )
        graph.controls.notifyActivityChanged()
    }

    /**
     * A link that keeps dropping while the screen is off is almost never the bridge — it is the
     * device suspending. The runtime holds a wake lock against exactly that, but battery saver
     * and a missing battery-optimization exemption can still tear the socket down, so the state
     * is named once per session instead of leaving the user with an unexplained flapping link.
     */
    private fun reportDevicePowerState() {
        val warning = DevicePowerState.warningOrNull(applicationContext) ?: return
        runCatching {
            graph.repository.appendActivity(
                ActivityLogRecord(
                    level = ActivityLevel.WARN,
                    category = "bridge",
                    action = "device_power_restricted",
                    summary = warning,
                ),
            )
            graph.controls.notifyActivityChanged()
        }
    }

    private fun recordConnection(frame: BridgeFrame.Connection) {
        runCatching {
            graph.repository.appendActivity(
                ActivityLogRecord(
                    level =
                        if (frame.state == BridgeConnectionState.ERROR) {
                            ActivityLevel.WARN
                        } else {
                            ActivityLevel.INFO
                        },
                    category = "bridge",
                    action = frame.state.name.lowercase(),
                    summary =
                        when (frame.state) {
                            BridgeConnectionState.CONNECTED -> "WhatsApp is connected."
                            BridgeConnectionState.PAIRING -> "WhatsApp needs to be paired."
                            BridgeConnectionState.ERROR -> "WhatsApp needs attention."
                            else -> "The WhatsApp connection status was updated."
                        },
                ),
            )
            graph.controls.notifyActivityChanged()
        }
    }

    /**
     * [force] is set only by the UI attaching. Every other caller is a frame handler that fires
     * many times per conversation, and the aggregate below is pointless with no screen to show it.
     */
    private fun refreshMetrics(force: Boolean = false) {
        if (!force && !graph.controls.metricsObserved) return
        val now = System.currentTimeMillis()
        if (!force) {
            while (true) {
                val previous = lastMetricsRefreshAt.get()
                if (now - previous < METRICS_REFRESH_MIN_INTERVAL_MS) return
                if (lastMetricsRefreshAt.compareAndSet(previous, now)) break
            }
        } else {
            lastMetricsRefreshAt.set(now)
        }
        val dayStart =
            Instant.ofEpochMilli(now)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val counts = graph.repository.runtimeCountsSince(dayStart)
        graph.controls.publishMetrics(
            AppRuntimeMetrics(
                pendingChats = counts.pendingChats,
                processedToday = counts.processedInbound,
                sentToday = counts.sentOutbound,
                accountName = accountName.get(),
                accountJid = accountJid.get(),
            ),
        )
    }

    /** The name the account should be wearing for [persona], catalogue label or the raw key. */
    private fun pushNameFor(persona: String): String =
        (
            SettingsCatalogs.personas.firstOrNull { it.key == persona }?.label
                ?: persona.replaceFirstChar(Char::titlecase)
        ).take(25)

    /**
     * Puts the profile name back on the selected persona.
     *
     * The switch itself only pushes a name while the runtime is up. Switching personas with the
     * bot stopped therefore left WhatsApp answering with the previous persona's name for good,
     * which is what contacts saw and what the settings header read back. This runs once per
     * carried connection and stays quiet when the two already agree, so a run that changes
     * nothing costs no profile write at all.
     */
    private suspend fun syncPushName(
        bridge: BridgeTransport,
        persona: String,
    ) {
        val desired = pushNameFor(persona)
        if (accountName.get() == desired) return
        runCatching {
            bridge.action(
                action = "set_push_name",
                payload = JSONObject().put("name", desired),
            )
            rememberAccount(accountJid.get(), desired)
            refreshMetrics()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(
                TAG,
                "push name: reconcile refused error=${privacySafeErrorType(error)}",
            )
        }
    }

    private fun rememberAccount(jid: String?, name: String?) {
        jid?.takeIf(String::isNotBlank)?.let(accountJid::set)
        name?.takeIf(String::isNotBlank)?.let(accountName::set)
    }

    private fun String.deliveryState(): MessageDeliveryState =
        when (lowercase()) {
            "pending" -> MessageDeliveryState.QUEUED
            "server_ack", "sent", "updated" -> MessageDeliveryState.SENT
            "delivered" -> MessageDeliveryState.DELIVERED
            "read", "played" -> MessageDeliveryState.READ
            "error", "failed" -> MessageDeliveryState.FAILED
            else -> MessageDeliveryState.UNKNOWN
        }

    private fun manualImagePayloadHash(
        assetSha256: String,
        caption: String?,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                "$assetSha256\u0000${caption.orEmpty()}"
                    .toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    private fun sha256Text(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun terminal(
        session: RuntimeHostSession,
        detail: String,
        phase: RuntimeHostPhase,
    ): RuntimeHostResult.TerminalFailure {
        session.report(RuntimeHostReport(phase, detail))
        val result = RuntimeHostResult.TerminalFailure(detail)
        // A startup refusal never reaches the bridge, so it never reaches the session-end
        // funnel either — and it is exactly the failure that leaves an operator staring at a
        // bot that reports nothing at all.
        recordSessionEnd(result, cursor = UNKNOWN_RESUME_CURSOR)
        return result
    }

    private data class BridgeUrls(
        val socketUrl: String,
        val mediaBaseUrl: String,
    )

    /**
     * The transport runs inside the phone: the native Go core publishes the bridge contract on
     * Android loopback. Off-device addresses are therefore rejected outright rather than merely
     * discouraged.
     */
    private fun resolveBridgeUrls(raw: String): BridgeUrls {
        val input = URI(raw.trim())
        val loopbackHost = input.host in setOf("127.0.0.1", "localhost")
        require(loopbackHost && (input.scheme.equals("ws", true) || input.scheme.equals("http", true)))
        require(input.userInfo == null && input.fragment == null)
        var path = input.path.orEmpty().trimEnd('/')
        path =
            when {
                path.endsWith("/v1/socket") -> path
                path.endsWith("/v1") -> "$path/socket"
                path.isBlank() -> "/v1/socket"
                else -> "$path/v1/socket"
            }
        val socket =
            URI(
                "ws",
                null,
                input.host,
                input.port,
                path,
                input.query,
                null,
            ).toString()
        val mediaPath = path.removeSuffix("/socket")
        val mediaBase =
            URI(
                "http",
                null,
                input.host,
                input.port,
                mediaPath,
                input.query,
                null,
            ).toString()
        return BridgeUrls(socket, mediaBase)
    }
}

private class BridgeIdentityStore(
    context: Context,
    mode: String,
) {
    private val preferences =
        context.getSharedPreferences("$PREFERENCES_NAME-$mode", Context.MODE_PRIVATE)

    @Synchronized
    fun clientId(): String {
        preferences.getString(KEY_CLIENT_ID, null)?.takeIf(String::isNotBlank)?.let {
            return it
        }
        val created = "android-${UUID.randomUUID()}"
        check(preferences.edit().putString(KEY_CLIENT_ID, created).commit()) {
            "Could not persist bridge client identity"
        }
        return created
    }

    fun lastAcknowledgedSequence(): Long =
        preferences.getLong(KEY_ACKNOWLEDGED_SEQUENCE, 0L).coerceAtLeast(0L)

    /**
     * When the account last answered the safety IQ round, across sessions.
     *
     * Kept next to the resume cursor because it answers the same kind of question: what this
     * install already knows, so a reconnect does not ask WhatsApp for it again.
     */
    fun lastSafetyRefreshAt(): Long =
        preferences.getLong(KEY_SAFETY_REFRESH_AT, 0L).coerceAtLeast(0L)

    fun recordSafetyRefreshAt(nowMs: Long) {
        // Best effort and asynchronous on purpose: losing this timestamp costs one extra refresh,
        // never correctness, and it must not block the frame that triggered it.
        preferences.edit().putLong(KEY_SAFETY_REFRESH_AT, nowMs.coerceAtLeast(0L)).apply()
    }

    @Synchronized
    fun reconcileServerCursor(sequence: Long) {
        val safe = sequence.coerceAtLeast(0L)
        if (safe == lastAcknowledgedSequence()) return
        check(preferences.edit().putLong(KEY_ACKNOWLEDGED_SEQUENCE, safe).commit()) {
            "Could not reconcile bridge resume cursor"
        }
    }

    @Synchronized
    fun acknowledge(sequence: Long) {
        if (sequence <= lastAcknowledgedSequence()) return
        check(preferences.edit().putLong(KEY_ACKNOWLEDGED_SEQUENCE, sequence).commit()) {
            "Could not persist bridge resume cursor"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "bridge_identity_v1"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_ACKNOWLEDGED_SEQUENCE = "acknowledged_sequence"
        const val KEY_SAFETY_REFRESH_AT = "safety_refresh_at"
    }
}

/**
 * Writes every rotation verdict to logcat, then hands it on unchanged.
 *
 * A profile picture that does not change looks identical whether the switch
 * never arrived, the persona has no pictures, or WhatsApp refused the stanza.
 * The in-app activity log distinguishes them, but it cannot be read over adb,
 * and this is the one feature whose whole result lives on a server we cannot
 * query. Persona keys only — nothing from a chat goes in here.
 */
private class LoggedProfilePictures(
    private val delegate: ProfilePictureRotation,
) : ProfilePictureRotation {
    override suspend fun rotateIfDue(
        personaKey: String,
        nowMs: Long,
    ): ProfilePictureOutcome =
        delegate.rotateIfDue(personaKey, nowMs).also { outcome ->
            // The session pass runs constantly and is almost always UNCHANGED;
            // only a verdict worth reading gets a line.
            if (outcome != ProfilePictureOutcome.UNCHANGED) {
                Log.i(TAG, "profile picture: session pass for '$personaKey' -> $outcome")
            }
        }

    override suspend fun applyPersonaSwitch(
        personaKey: String,
        nowMs: Long,
    ): ProfilePictureOutcome =
        delegate.applyPersonaSwitch(personaKey, nowMs).also { outcome ->
            Log.i(TAG, "profile picture: persona '$personaKey' -> $outcome")
        }
}
