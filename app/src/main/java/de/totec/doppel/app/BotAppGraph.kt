package de.totec.doppel.app

import android.content.Context
import de.totec.doppel.ai.InMemoryModelCatalogCache
import de.totec.doppel.data.SqlSettingsPersistence
import de.totec.doppel.data.db.BotRepository
import de.totec.doppel.engine.ChatActivityFeed
import de.totec.doppel.engine.LinkPowerFeed
import de.totec.doppel.engine.MemoryWorkFeed
import de.totec.doppel.integration.AsyncActivityWriter
import de.totec.doppel.media.AndroidApprovedMediaImporter
import de.totec.doppel.media.AndroidCharacterReferenceImporter
import de.totec.doppel.media.AndroidProfilePictureImporter
import de.totec.doppel.media.ApprovedMediaKind
import de.totec.doppel.media.ApprovedMediaAssetStore
import de.totec.doppel.media.ApprovedMediaProfilePictures
import de.totec.doppel.media.PreinstalledPersonaImages
import de.totec.doppel.media.ProfilePictureRotator
import de.totec.doppel.security.SecretStore
import de.totec.doppel.settings.SettingsRepository
import java.io.File
import java.io.Closeable
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Process-wide, low-idle application dependencies.
 *
 * Every component receives these same repository/settings/secret instances.
 * There is no UI-only persistence path and no background polling owner.
 */
class BotAppGraph(context: Context) : Closeable {
    val applicationContext: Context = context.applicationContext
    val repository = BotRepository(applicationContext)
    val settings = SettingsRepository(SqlSettingsPersistence(repository))
    val secrets = SecretStore(applicationContext)
    val approvedMedia =
        ApprovedMediaAssetStore(
            File(applicationContext.filesDir, "approved-media/v1"),
        )
    val approvedMediaImporter =
        AndroidApprovedMediaImporter(applicationContext, approvedMedia)
    val characterReferenceImporter =
        AndroidCharacterReferenceImporter(applicationContext, approvedMedia)
    val preinstalledMedia =
        PreinstalledPersonaImages(
            store = approvedMedia,
            ledger = File(applicationContext.filesDir, "approved-media/preinstalled.index"),
            open = applicationContext.assets::open,
        )
    val preinstalledCharacterReferences =
        PreinstalledPersonaImages(
            store = approvedMedia,
            ledger = File(applicationContext.filesDir, "approved-media/character-reference.index"),
            open = applicationContext.assets::open,
            assetRoot = "character-reference",
            kind = ApprovedMediaKind.CHARACTER_REFERENCE,
            superseded = RETIRED_REFERENCE_DIGESTS,
        )

    /**
     * The starter faces. Seeded through the same catalogue as everything else, which is what makes
     * them show up in the profile-picture list the first time it is opened — and what makes
     * deleting one stick instead of it reappearing on the next start.
     */
    val preinstalledProfilePictures =
        PreinstalledPersonaImages(
            store = approvedMedia,
            ledger = File(applicationContext.filesDir, "approved-media/profile-picture.index"),
            open = applicationContext.assets::open,
            assetRoot = ProfilePictureRotator.ASSET_ROOT,
            kind = ApprovedMediaKind.PROFILE_PICTURE,
        )
    val profilePictures = ApprovedMediaProfilePictures(approvedMedia)
    val profilePictureImporter =
        AndroidProfilePictureImporter(applicationContext, approvedMedia)
    val controls = AppControlRegistry()
    val activityWriter =
        AsyncActivityWriter(
            repository = repository,
            activityChanged = controls::notifyActivityChanged,
        )

    /**
     * Live per-chat stages, written by the engine and read by the UI.
     *
     * On the graph rather than inside the engine because the engine is torn down and rebuilt on
     * every reconnect, while the screen watching it stays. In-memory by design: an armed pickup
     * window does not outlive the process that armed it, so neither should the row describing it.
     */
    val chatActivity = ChatActivityFeed()

    /**
     * Memory writes in flight, so every surface that can start one can show it and refuse a second.
     * On the graph for the same reason [chatActivity] is.
     */
    val memoryWork = MemoryWorkFeed()

    /**
     * Whether the WhatsApp link may currently drop, and when it is due back.
     *
     * On the graph for a stronger version of [chatActivity]'s reason: in low power mode
     * the process spends most of its life with the engine gone and the CPU suspended,
     * and the alarm that brings it back is armed from here.
     */
    val linkPower = LinkPowerFeed()

    /**
     * One model catalogue for the whole process.
     *
     * The settings screen fills its model pickers from it and the media pipeline asks it what the
     * media model can read. Both used to be answered by a fetch of their own; sharing the cache
     * means `GET /models` runs once per TTL no matter how many callers there are, which is the
     * entire cost of knowing whether a model takes audio.
     */
    val modelCatalogCache = InMemoryModelCatalogCache()
    val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    override fun close() {
        activityWriter.close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
        repository.close()
    }

    private companion object {
        /**
         * The four bundled character references as they shipped before they were re-encoded.
         *
         * They were ~2 MB PNGs, and every image generation carried the whole set base64-encoded
         * into the request. The replacements are bounded JPEGs of the same pictures at a tenth of
         * the size. An install that already imported the PNGs keeps them unless they are named
         * here: the store files by digest, so the smaller file is a new asset rather than an
         * update, and both would be attached to every request from then on.
         */
        val RETIRED_REFERENCE_DIGESTS =
            setOf(
                "bf63f64a30c1c66faf3ee6020ff8513c3c44e10278e0fce71e9cd6d3d1c22352",
                "5b2554d0dbf7e016cc26441aa327523a38658beb02b55ee65824854895485ea6",
                "2b9028b65247f48cf29a363d666e9fa36b245b9676fcb9fe5e13f5bfd2ab9e89",
                "2f2d4c1a693c9958a44bdb4724d74bd096658fa7f1d28436aa458f3d7b47dbd1",
            )
    }
}
