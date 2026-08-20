package de.totec.doppel.integration

import de.totec.doppel.media.ApprovedMediaAssetStore
import de.totec.doppel.media.ApprovedMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CharacterReference(
    val bytes: ByteArray,
    val mimeType: String,
)

/** Dynamic, app-private reference collection shared by Settings and the generation tool. */
class CharacterReferenceStore(
    private val store: ApprovedMediaAssetStore,
) {
    fun count(personaKey: String): Int =
        runCatching {
            store.list(
                personaKey = personaKey,
                limit = ApprovedMediaAssetStore.MAX_REFERENCES_PER_PERSONA,
                kind = ApprovedMediaKind.CHARACTER_REFERENCE,
            ).size
        }.getOrDefault(0)

    fun hasReference(personaKey: String): Boolean = count(personaKey) > 0

    suspend fun readAll(personaKey: String): List<CharacterReference> =
        withContext(Dispatchers.IO) {
            val assets =
                store.list(
                    personaKey = personaKey,
                    limit = ApprovedMediaAssetStore.MAX_REFERENCES_PER_PERSONA,
                    kind = ApprovedMediaKind.CHARACTER_REFERENCE,
                )
            var total = 0L
            assets.map { asset ->
                val handle = store.openForReference(asset.assetId, personaKey)
                require(handle.asset.mimeType in ALLOWED_MIME_TYPES) {
                    "Character reference is not API compatible"
                }
                val bytes = handle.file.readBytes()
                require(bytes.isNotEmpty() && bytes.size <= MAX_REFERENCE_BYTES) {
                    "Character reference is invalid"
                }
                total += bytes.size
                require(total <= MAX_COMBINED_REFERENCE_BYTES) {
                    "Character references exceed the API payload limit"
                }
                CharacterReference(bytes, handle.asset.mimeType)
            }
        }

    private companion object {
        const val MAX_REFERENCE_BYTES = 4 * 1_024 * 1_024
        const val MAX_COMBINED_REFERENCE_BYTES = 16L * 1_024L * 1_024L
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
