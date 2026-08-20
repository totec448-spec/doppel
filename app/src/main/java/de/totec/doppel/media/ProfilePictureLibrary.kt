package de.totec.doppel.media

import java.io.File

/**
 * The pictures one persona can wear on the account, in the order the rotation walks them.
 *
 * The rotator used to read the bundled manifest directly, which made the set of faces a property
 * of the APK: an owner could not add one, and a picture they were tired of could not be taken out.
 * Behind this interface the same rotation now walks a catalogue the owner edits, while the rotator
 * itself keeps knowing nothing about where the bytes live.
 */
interface ProfilePictureLibrary {
    /**
     * Stable ids in a stable order. Adding a picture must not renumber the ones already there —
     * the rotation records which picture is up by id, and a list that reshuffles would put the
     * same face up twice in a row.
     */
    fun list(personaKey: String): List<String>

    /** Copies one picture to [destination] so it can be uploaded and then thrown away. */
    fun stage(
        personaKey: String,
        pictureId: String,
        destination: File,
    )

    /** Where the picture's bytes are, for showing the face the account is actually wearing. */
    fun path(
        personaKey: String,
        pictureId: String,
    ): String?
}

/**
 * The owner's approved profile pictures, kept in the same private catalogue as every other
 * approved image and validated by it on the way out.
 *
 * Order is oldest first, so the bundled starter pictures keep the order they were seeded in and an
 * upload lands at the end instead of jumping the queue. Ties are broken by id because two files
 * seeded in the same millisecond would otherwise be free to swap places between two starts.
 */
class ApprovedMediaProfilePictures(
    private val store: ApprovedMediaAssetStore,
) : ProfilePictureLibrary {
    override fun list(personaKey: String): List<String> =
        runCatching {
            store
                .list(
                    personaKey,
                    limit = ApprovedMediaAssetStore.MAX_PROFILE_PICTURES_PER_PERSONA,
                    kind = ApprovedMediaKind.PROFILE_PICTURE,
                )
                .sortedWith(
                    compareBy(ApprovedMediaAsset::createdAtMs, ApprovedMediaAsset::assetId),
                )
                .map(ApprovedMediaAsset::assetId)
        }.getOrDefault(emptyList())

    override fun stage(
        personaKey: String,
        pictureId: String,
        destination: File,
    ) {
        val handle = store.openForProfilePicture(pictureId, personaKey)
        destination.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        handle.file.copyTo(destination, overwrite = true)
    }

    override fun path(
        personaKey: String,
        pictureId: String,
    ): String? =
        runCatching { store.openForProfilePicture(pictureId, personaKey).file.path }.getOrNull()
}
