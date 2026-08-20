package de.totec.doppel.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * Android Storage Access Framework adapter. The selected document is copied
 * once into app-private storage; no long-lived URI grant is retained.
 */
class AndroidApprovedMediaImporter(
    context: Context,
    private val store: ApprovedMediaAssetStore,
) {
    private val resolver = context.applicationContext.contentResolver

    fun importImage(
        personaKey: String,
        uri: Uri,
        displayNameOverride: String? = null,
    ): ApprovedMediaImport {
        require(uri.scheme == "content") { "Only documents picked with the Android file chooser are allowed" }
        val sourceDisplayName =
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        val input = resolver.openInputStream(uri) ?: throw IOException("Image could not be opened")
        return input.use {
            store.importImage(
                personaKey = personaKey,
                displayName = displayNameOverride?.trim()?.takeIf(String::isNotEmpty) ?: sourceDisplayName,
                declaredMimeType = resolver.getType(uri),
                source = it,
            )
        }
    }
}
