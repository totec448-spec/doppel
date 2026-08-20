package de.totec.doppel.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns an arbitrary SAF-selected picture into something WhatsApp will accept as an avatar.
 *
 * WhatsApp shows profile pictures in a circle and stores them square. A rectangular photo handed
 * over as-is comes back squashed or cropped by the server in whatever way it likes, so the crop
 * happens here, where it can be centred and predictable: the largest centred square of the
 * original, scaled to [SIDE] and re-encoded as a small opaque JPEG.
 *
 * Decoding is authoritative, as it is for character references: a forged extension cannot bypass
 * it, EXIF orientation is applied, an animated file becomes one still frame, alpha is flattened
 * onto white, and re-encoding drops every piece of metadata the original carried — including the
 * location the photo was taken at, which for a picture of a person is the one thing that must not
 * travel with it.
 */
class AndroidProfilePictureImporter(
    context: Context,
    private val store: ApprovedMediaAssetStore,
) {
    private val resolver = context.applicationContext.contentResolver

    fun importPicture(
        personaKey: String,
        uri: Uri,
    ): ApprovedMediaImport {
        require(uri.scheme == "content") {
            "Only documents picked with the Android file chooser are allowed"
        }
        val sourceName = displayName(uri)
        val encoded =
            resolver.openInputStream(uri)?.use(::readBounded)
                ?: throw IOException("Profile picture could not be opened")
        val square = normalize(encoded)
        return ByteArrayInputStream(square).use { source ->
            store.importImage(
                personaKey = personaKey,
                displayName = sourceName,
                declaredMimeType = "image/jpeg",
                source = source,
                kind = ApprovedMediaKind.PROFILE_PICTURE,
            )
        }
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1_024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_SOURCE_BYTES) throw IOException("Profile picture is too large")
            output.write(buffer, 0, read)
        }
        if (total == 0) throw IOException("Profile picture is empty")
        return output.toByteArray()
    }

    private fun normalize(encoded: ByteArray): ByteArray {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(encoded))
        val decoded =
            try {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0) { "Profile picture dimensions are invalid" }
                    // Decoded at twice the final side at most: the crop still has pixels to work
                    // with, and a 12-megapixel camera file never becomes a 12-megapixel bitmap.
                    val scale = (DECODE_SIDE.toDouble() / min(width, height)).coerceAtMost(1.0)
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    decoder.setOnPartialImageListener { false }
                }
            } catch (failure: Exception) {
                throw IOException("Profile picture could not be decoded", failure)
            }
        val square = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        try {
            Canvas(square).apply {
                // White first: a transparent PNG would otherwise arrive as a black avatar, which is
                // how a logo with a cut-out background looks after WhatsApp flattens it.
                drawColor(Color.WHITE)
                drawBitmap(decoded, centredSquare(decoded), Rect(0, 0, SIDE, SIDE), null)
            }
            return encodeBounded(square)
        } finally {
            decoded.recycle()
            square.recycle()
        }
    }

    /** The largest centred square of the source — the part of a portrait a face is actually in. */
    private fun centredSquare(bitmap: Bitmap): Rect {
        val side = min(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    private fun encodeBounded(bitmap: Bitmap): ByteArray {
        for (quality in JPEG_QUALITIES) {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IOException("Profile picture could not be encoded")
                }
                output.toByteArray()
            }
            if (bytes.size <= MAX_PICTURE_BYTES) return bytes
        }
        throw IOException("Profile picture could not be reduced to the upload size limit")
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 24 * 1_024 * 1_024

        /** What WhatsApp itself stores an avatar at; more is thrown away by the server. */
        const val SIDE = 640
        const val DECODE_SIDE = SIDE * 2

        /** An avatar this size is comfortably under every server limit at these qualities. */
        const val MAX_PICTURE_BYTES = 200 * 1_024
        val JPEG_QUALITIES = intArrayOf(90, 82, 74, 66, 58, 50)
    }
}
