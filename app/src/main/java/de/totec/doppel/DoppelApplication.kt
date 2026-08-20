package de.totec.doppel

import android.app.Application
import de.totec.doppel.ai.ModelCatalogClient
import de.totec.doppel.ai.LocalSpeechModels
import de.totec.doppel.ai.ModelRole as AiModelRole
import de.totec.doppel.ai.OpenRouterApiKeyProvider
import de.totec.doppel.ai.OpenRouterAttribution
import de.totec.doppel.app.ModelCatalogControl
import de.totec.doppel.app.BotAppGraph
import de.totec.doppel.app.ProductionAppController
import de.totec.doppel.app.ProductionChatsController
import de.totec.doppel.app.UiModelOption
import de.totec.doppel.data.db.PersonaRecord
import de.totec.doppel.integration.NativeAdminActions
import de.totec.doppel.integration.PersonaBehavior
import de.totec.doppel.integration.RepositoryAiNetworkObserver
import de.totec.doppel.media.ProfilePictureRotator
import de.totec.doppel.runtime.BotRuntimeHostFactory
import de.totec.doppel.runtime.NativeRuntimeHost
import de.totec.doppel.runtime.RuntimeDependencies
import de.totec.doppel.security.SecretStore
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.ModelRole
import de.totec.doppel.settings.PersonaVoices
import de.totec.doppel.settings.SettingsCatalogs
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * One process-wide composition root for app UI and foreground runtime.
 *
 * NativeRuntimeHost installs its factory from this Application and consumes
 * [graph]; it must not construct a second database, settings repository or
 * credential store.
 */
class DoppelApplication : Application() {
    lateinit var graph: BotAppGraph
        private set

    private val controllerDelegate =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ProductionAppController(this, graph)
        }

    /**
     * Lazy on purpose: a boot-created foreground-service process needs the
     * graph, but pays no Compose/controller/activity-list cost until UI opens.
     */
    val appController: ProductionAppController
        get() = controllerDelegate.value

    private val chatsControllerDelegate =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ProductionChatsController(graph) }

    /** The chat surfaces. Lazy for the same reason, and idle until a screen collects from it. */
    val chatsController: ProductionChatsController
        get() = chatsControllerDelegate.value

    override fun onCreate() {
        super.onCreate()
        graph = BotAppGraph(this)
        seedApplicationData()
        val adminActions =
            NativeAdminActions(
                repository = graph.repository,
                settings = graph.settings,
                secrets = graph.secrets,
                approvedMedia = graph.approvedMedia,
                activityChanged = graph.controls::notifyActivityChanged,
                liveProfilePicture = { persona ->
                    ProfilePictureRotator
                        .confirmedPicture(
                            java.io.File(filesDir, ProfilePictureRotator.STATE_FILE_NAME),
                        )
                        ?.takeIf { it.first == persona }
                        ?.second
                },
            )
        graph.controls.bindAdminActions(adminActions)

        val modelCache = graph.modelCatalogCache
        graph.controls.bindModelCatalog(
            ModelCatalogControl {
                val snapshot = graph.settings.snapshot()
                val client =
                    ModelCatalogClient(
                        httpClient = graph.httpClient,
                        baseUrl =
                            snapshot.appText(AppSettingKeys.OPENROUTER_BASE_URL).toHttpUrl(),
                        apiKeyProvider =
                            OpenRouterApiKeyProvider {
                                graph.secrets.get(SecretStore.OPENROUTER_API_KEY)
                            },
                        cache = modelCache,
                        attribution =
                            OpenRouterAttribution(
                                referer =
                                    snapshot.appText(AppSettingKeys.OPENROUTER_REFERER)
                                        .takeIf(String::isNotBlank),
                                title =
                                    snapshot.appText(AppSettingKeys.OPENROUTER_TITLE)
                                    .takeIf(String::isNotBlank),
                            ),
                        observer =
                            RepositoryAiNetworkObserver(
                                activityWriter = graph.activityWriter,
                            ),
                    )
                (client.getCatalog(forceRefresh = false).models
                    .mapNotNull { model ->
                        val roles =
                            buildSet {
                                if (model.supports(AiModelRole.MAIN)) add(ModelRole.MAIN)
                                if (model.supports(AiModelRole.VERIFY)) add(ModelRole.VERIFIER)
                                if (model.supports(AiModelRole.MEDIA)) add(ModelRole.MEDIA)
                                if (model.supports(AiModelRole.IMAGE)) add(ModelRole.IMAGE)
                                if (model.supports(AiModelRole.TTS)) add(ModelRole.TTS)
                                if (model.supports(AiModelRole.TRANSCRIBE)) {
                                    add(ModelRole.TRANSCRIBE)
                                }
                            }
                        roles.takeIf { it.isNotEmpty() }?.let {
                            UiModelOption(
                                id = model.id,
                                label = model.name.ifBlank { model.id },
                                supportedRoles = it,
                                createdAtEpochSeconds = model.createdAtEpochSeconds,
                                reasoningEfforts = model.reasoning?.supportedEfforts,
                                reasoningDefaultEffort = model.reasoning?.defaultEffort,
                                reasoningMandatory = model.reasoning?.mandatory == true,
                                supportedVoices = model.supportedVoices,
                                promptPricePerToken = model.pricing.promptPerToken,
                                completionPricePerToken = model.pricing.completionPerToken,
                                imagePricePerUnit = model.pricing.imagePerUnit,
                                imageOutputPricePerToken = model.pricing.imageOutputPerToken,
                                requestPrice = model.pricing.request,
                                transcriptionPricePerHour = model.pricing.transcriptionPerHour,
                            )
                        }
                    } +
                    UiModelOption(
                        id = LocalSpeechModels.ANDROID_SYSTEM,
                        label = "Android System-TTS (lokal)",
                        supportedRoles = setOf(ModelRole.TTS),
                    ))
                    .sortedWith(
                        compareByDescending<UiModelOption> {
                            it.createdAtEpochSeconds ?: Long.MIN_VALUE
                        }.thenBy { it.label.lowercase() },
                    )
            },
        )
        RuntimeDependencies.install(
            BotRuntimeHostFactory { context -> NativeRuntimeHost(context, graph) },
        )
    }

    /**
     * Persona memory has an intentional foreign key to the persona catalog. Built-ins live in
     * typed settings rather than a second hard-coded database list. Managed built-ins carry a
     * schema marker so prompt improvements can upgrade old bundled text without ever overwriting
     * a custom/user-modified record. Normal starts perform one bounded read and zero writes.
     */
    private fun seedBuiltInPersonas() {
        val existing =
            graph.repository
                .listPersonas(limit = 1_000)
                .asSequence()
                .associateBy(PersonaRecord::personaId)
        val now = System.currentTimeMillis()
        val updates =
            SettingsCatalogs.personas
                .asSequence()
                .mapNotNull { catalog ->
                    val current = existing[catalog.key]
                    if (catalog.key == "custom") {
                        return@mapNotNull current
                            ?: PersonaRecord(
                                personaId = catalog.key,
                                name = catalog.label,
                                description = PersonaBehavior.description(catalog.key),
                                systemPrompt = PersonaBehavior.instructions(catalog.key),
                                createdAt = now,
                                updatedAt = now,
                            )
                    }
                    // Each built-in speaks in a voice that matches the character its prompt
                    // describes. A voice the owner picked is never overwritten — only a persona
                    // that has none is given its bundled one.
                    val voiceConfigJson =
                        PersonaVoices.write(
                            current?.voiceConfigJson,
                            PersonaVoices.read(current?.voiceConfigJson)
                                ?: PersonaVoices.baseVoice(catalog.key),
                        )
                    val shouldUpgrade =
                        current == null ||
                            (PersonaBehavior.managedVersion(current) ?: 0) <
                                PersonaBehavior.BUILT_IN_VERSION &&
                            (
                                PersonaBehavior.managedVersion(current) != null ||
                                    current.systemPrompt ==
                                        PersonaBehavior.legacyInstructions(catalog.key)
                            )
                    if (!shouldUpgrade) {
                        // The prompt is already current, but the voice column was written null for
                        // every persona until now, so an existing install still needs filling in.
                        // Nothing but the voice is touched here: a prompt the owner edited stays.
                        return@mapNotNull current
                            ?.takeIf { it.voiceConfigJson != voiceConfigJson }
                            ?.copy(voiceConfigJson = voiceConfigJson, updatedAt = now)
                    }
                    PersonaRecord(
                        personaId = catalog.key,
                        name = catalog.label,
                        description = PersonaBehavior.description(catalog.key),
                        systemPrompt = PersonaBehavior.instructions(catalog.key),
                        traitsJson = PersonaBehavior.managedMetadata(current?.traitsJson),
                        voiceConfigJson = voiceConfigJson,
                        enabled = current?.enabled ?: true,
                        createdAt = current?.createdAt ?: now,
                        updatedAt = now,
                    )
                }
                .toList()
        graph.repository.upsertPersonas(updates)
    }

    /**
     * One activity row per bundled-media pass. A failure here never blocks the start, so the log is
     * the only place it can be noticed at all — and an owner staring at an empty picture list needs
     * to be able to tell "nothing shipped" from "the import threw".
     */
    private fun recordSeedOutcome(
        what: String,
        outcome: de.totec.doppel.media.PreinstalledPersonaImages.Outcome,
    ) {
        // A quiet start stays quiet: a sealed manifest whose files are all in the store has nothing
        // to report. Everything else does, and an empty store most of all.
        if (outcome.sealed && outcome.healthy) return
        runCatching {
            graph.repository.appendActivity(
                de.totec.doppel.data.db.ActivityLogRecord(
                    occurredAt = System.currentTimeMillis(),
                    level =
                        if (!outcome.healthy) {
                            de.totec.doppel.data.db.ActivityLevel.ERROR
                        } else {
                            de.totec.doppel.data.db.ActivityLevel.INFO
                        },
                    category = "media",
                    action = "bundled_$what",
                    summary = "Bundled $what · ${outcome.summary}",
                ),
            )
        }
    }

    /**
     * Built-in persona reconciliation and the starter gallery are startup I/O, not
     * prerequisites for the first frame. One low-priority worker performs both and
     * repository revisions refresh any already-open UI when it finishes.
     */
    private fun seedApplicationData() {
        Thread(
            {
                seedBuiltInPersonas()
                // Both passes used to swallow their own result, so "no pictures in settings" and
                // "twenty pictures imported" looked identical from here: silence.
                recordSeedOutcome("starter_images", graph.preinstalledMedia.seed())
                recordSeedOutcome(
                    "character_references",
                    graph.preinstalledCharacterReferences.seed(),
                )
                recordSeedOutcome(
                    "profile_pictures",
                    graph.preinstalledProfilePictures.seed(),
                )
            },
            "application-seed",
        )
            .apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
    }

    /**
     * Called only by emulated processes; Android normally kills an app process
     * without this callback. Explicit close remains useful for instrumentation.
     */
    override fun onTerminate() {
        if (controllerDelegate.isInitialized()) {
            controllerDelegate.value.close()
        }
        if (chatsControllerDelegate.isInitialized()) {
            chatsControllerDelegate.value.close()
        }
        graph.close()
        super.onTerminate()
    }
}
