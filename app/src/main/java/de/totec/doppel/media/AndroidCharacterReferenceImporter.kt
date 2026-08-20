package de.totec.doppel.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns an arbitrary SAF-selected picture into a bounded API reference.
 *
 * Decoding is authoritative: a forged extension or MIME cannot bypass it. ImageDecoder applies
 * EXIF orientation, animated formats become one still frame, drawing onto white removes alpha,
 * and JPEG re-encoding strips EXIF/location metadata. Every stored result is therefore an opaque,
 * sRGB-compatible JPEG with bounded dimensions and bytes before it can enter an API payload.
 */
class AndroidCharacterReferenceImporter(
    context: Context,
    private val store: ApprovedMediaAssetStore,
) {
    private val resolver = context.applicationContext.contentResolver

    fun importImage(
        personaKey: String,
        uri: Uri,
    ): ApprovedMediaImport {
        require(uri.scheme == "content") {
            "Only documents picked with the Android file chooser are allowed"
        }
        val sourceName = displayName(uri)
        val encoded =
            resolver.openInputStream(uri)?.use(::readBounded)
                ?: throw IOException("Reference image could not be opened")
        val normalized = normalize(encoded)
        return ByteArrayInputStream(normalized).use { source ->
            store.importImage(
                personaKey = personaKey,
                displayName = sourceName,
                declaredMimeType = "image/jpeg",
                source = source,
                kind = ApprovedMediaKind.CHARACTER_REFERENCE,
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
            if (total > MAX_SOURCE_BYTES) throw IOException("Reference image is too large")
            output.write(buffer, 0, read)
        }
        if (total == 0) throw IOException("Reference image is empty")
        return output.toByteArray()
    }

    private fun normalize(encoded: ByteArray): ByteArray {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(encoded))
        val decoded =
            try {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0) { "Reference image dimensions are invalid" }
                    val scale = (MAX_DIMENSION.toDouble() / max(width, height)).coerceAtMost(1.0)
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    decoder.setOnPartialImageListener { false }
                }
            } catch (failure: Exception) {
                throw IOException("Reference image could not be decoded", failure)
            }
        val opaque = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, 0f, 0f, null)
        }
        if (decoded !== opaque) decoded.recycle()
        return try {
            encodeBounded(opaque)
        } finally {
            opaque.recycle()
        }
    }

    private fun encodeBounded(source: Bitmap): ByteArray {
        var bitmap = source
        repeat(MAX_RESIZE_PASSES) { pass ->
            for (quality in JPEG_QUALITIES) {
                val bytes = ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw IOException("Reference image could not be encoded")
                    }
                    output.toByteArray()
                }
                if (bytes.size <= MAX_REFERENCE_BYTES) {
                    if (bitmap !== source) bitmap.recycle()
                    return bytes
                }
            }
            if (pass < MAX_RESIZE_PASSES - 1) {
                val resized =
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * RESIZE_FACTOR).roundToInt().coerceAtLeast(1),
                        (bitmap.height * RESIZE_FACTOR).roundToInt().coerceAtLeast(1),
                        true,
                    )
                if (bitmap !== source) bitmap.recycle()
                bitmap = resized
            }
        }
        if (bitmap !== source) bitmap.recycle()
        throw IOException("Reference image could not be reduced to the API size limit")
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 24 * 1_024 * 1_024

        /**
         * A reference is not a picture anybody looks at — it is sent base64-encoded inside every
         * image-generation request that carries this persona, up to eight at a time, on a phone
         * connection. The old ceiling of 1.5 MB let a single one weigh more than the rest of the
         * request together while adding nothing the model could use: the identity is carried by the
         * face at a couple of hundred pixels, not by the last JPEG quality step. The bundled
         * references sit at 85–200 KB after the same treatment, so this is the size an owner-picked
         * file is brought to as well.
         */
        const val MAX_REFERENCE_BYTES = 240 * 1_024

        /** Roughly the bundled references' own resolution; more is detail the model discards. */
        const val MAX_DIMENSION = 1_280
        const val MAX_RESIZE_PASSES = 4
        const val RESIZE_FACTOR = 0.82
        val JPEG_QUALITIES = intArrayOf(86, 78, 70, 62, 54, 46)
    }
}
