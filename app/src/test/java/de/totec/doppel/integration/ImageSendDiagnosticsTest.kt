package de.totec.doppel.integration

import de.totec.doppel.engine.PlannedSideEffect
import de.totec.doppel.ai.OpenRouterHttpException
import de.totec.doppel.media.ApprovedMediaAssetStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5: an image the model asked for but that could not be sent used to abort the whole turn before
 * a single bubble went out, and the log carried nothing an operator could act on.
 *
 * The failure codes are matched against exception *messages*, because the asset store reports every
 * validation failure as an `IllegalArgumentException`. That makes the mapping fragile by nature, so
 * the cases below drive the real store to produce the real exceptions rather than hand-writing the
 * strings — if a store message is reworded, these fail instead of the diagnostics silently
 * degrading to "unknown error".
 */
class ImageSendDiagnosticsTest {
    @Test
    fun `image generation prefers the canonical routing reason over a numeric provider code`() {
        val failure =
            OpenRouterHttpException(
                statusCode = 404,
                retryAfterMs = null,
                providerRequestId = null,
                reasonCode = "no_compatible_endpoint",
                providerErrorCode = "404",
            )

        val code = ImageSendDiagnostics.generationFailureCode(failure)

        assertEquals("no_compatible_endpoint", code)
        assertEquals(
            "OpenRouter found no compatible image provider endpoint",
            ImageSendDiagnostics.failureLabel(code),
        )
    }

    @Test
    fun `image generation HTTP 400 is actionable instead of unknown`() {
        val failure =
            OpenRouterHttpException(
                statusCode = 400,
                retryAfterMs = null,
                providerRequestId = null,
                reasonCode = "http_error",
            )

        val code = ImageSendDiagnostics.generationFailureCode(failure)

        assertEquals("http_400", code)
        assertEquals(
            "OpenRouter rejected the image request (HTTP 400)",
            ImageSendDiagnostics.failureLabel(code),
        )
    }

    private val directory = Files.createTempDirectory("image-diagnostics-test").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a reference that is not an asset id at all is named as such`() {
        val store = store()

        assertEquals("invalid_asset_id", codeOf { store.openForSend("../../etc/passwd", "human") })
        assertEquals("invalid_asset_id", codeOf { store.openForSend("", "human") })
    }

    @Test
    fun `an asset approved for another persona is not reported as a missing file`() {
        val store = store()
        val asset = store.importImage("human", "urlaub.png", "image/png", png(64)).asset

        assertEquals("asset_not_approved", codeOf { store.openForSend(asset.assetId, "female") })
    }

    @Test
    fun `a deleted or truncated file is reported as missing`() {
        val store = store()
        val asset = store.importImage("human", "urlaub.png", "image/png", png(64)).asset
        val file = store.openForSend(asset.assetId, "human").file

        file.writeBytes(ByteArray(3))
        assertEquals("asset_file_missing", codeOf { store.openForSend(asset.assetId, "human") })

        file.delete()
        assertEquals("asset_file_missing", codeOf { store.openForSend(asset.assetId, "human") })
    }

    /** Same length, different bytes: only the digest catches it, and it means something different. */
    @Test
    fun `a file changed after approval is distinguished from a missing one`() {
        val store = store()
        val asset = store.importImage("human", "urlaub.png", "image/png", png(64)).asset
        val file = store.openForSend(asset.assetId, "human").file

        file.writeBytes(pngBytes(64).also { it[it.lastIndex] = 42 })

        assertEquals("asset_hash_mismatch", codeOf { store.openForSend(asset.assetId, "human") })
    }

    @Test
    fun `unreadable approval metadata is its own reason`() {
        val store = store()
        val asset = store.importImage("human", "urlaub.png", "image/png", png(64)).asset

        File(directory, "${asset.assetId}.meta").writeBytes(ByteArray(64))

        assertEquals("asset_metadata_invalid", codeOf { store.openForSend(asset.assetId, "human") })
    }

    /** The repeat guard is a policy decision, not a fault, so it must not read as a broken file. */
    @Test
    fun `an image already sent to this chat is its own reason`() {
        assertEquals(
            "already_sent_here",
            ImageSendDiagnostics.failureCode(
                IllegalStateException("Image was already sent to this chat"),
            ),
        )
    }

    @Test
    fun `bridge mismatches after upload are separated from the upload failing`() {
        assertEquals(
            "upload_size_mismatch",
            ImageSendDiagnostics.failureCode(
                IllegalArgumentException("Bridge confirmed a different image size"),
            ),
        )
        assertEquals(
            "upload_hash_mismatch",
            ImageSendDiagnostics.failureCode(
                IllegalArgumentException("Bridge confirmed a different image hash"),
            ),
        )
        assertEquals("upload_failed", ImageSendDiagnostics.failureCode(IOException("connection reset")))
        assertEquals("asset_file_missing", ImageSendDiagnostics.failureCode(FileNotFoundException("gone")))
    }

    /** An unrecognized failure still has to say *something* specific enough to search for. */
    @Test
    fun `an unknown failure falls back to its type instead of a blank reason`() {
        val code = ImageSendDiagnostics.failureCode(IllegalStateException("völlig neuer Fehler"))

        assertEquals("illegalstateexception", code)
        assertTrue(ImageSendDiagnostics.failureLabel(code).contains("illegalstateexception"))
    }

    /** No message must ever leak the failing text back to the operator log as an "unknown error". */
    @Test
    fun `every named code has a readable label`() {
        val codes =
            listOf(
                "asset_file_missing",
                "asset_hash_mismatch",
                "asset_not_approved",
                "invalid_asset_id",
                "already_sent_here",
                "asset_metadata_invalid",
                "upload_size_mismatch",
                "upload_hash_mismatch",
                "upload_failed",
            )

        codes.forEach { code ->
            assertFalse(code, ImageSendDiagnostics.failureLabel(code).contains("unknown error"))
        }
        assertEquals(codes.size, codes.map(ImageSendDiagnostics::failureLabel).distinct().size)
    }

    /**
     * The core of the fix: a failed image degrades to its caption instead of taking the turn with
     * it, and the reason travels with the degraded action.
     */
    @Test
    fun `a captioned image degrades to the caption instead of vanishing`() {
        val degraded = ImageSendDiagnostics.degrade(action(caption = "  Schau mal  "), "asset_file_missing")

        assertEquals("Schau mal", degraded?.text)
        assertEquals("asset_file_missing", degraded?.reasonCode)
        assertEquals("key-1:caption-fallback", degraded?.idempotencyKey)
    }

    @Test
    fun `an uncaptioned image drops only itself`() {
        assertNull(ImageSendDiagnostics.degrade(action(caption = null), "upload_failed"))
        assertNull(ImageSendDiagnostics.degrade(action(caption = "   "), "upload_failed"))
    }

    @Test
    fun `the log line says what failed and what was sent instead`() {
        val withCaption = ImageSendDiagnostics.failureSummary("Schau mal", "asset_file_missing")
        val without = ImageSendDiagnostics.failureSummary(null, "asset_not_approved")

        assertTrue(withCaption.contains("image file is missing or damaged"))
        assertTrue(withCaption.contains("sending the caption as text"))
        assertTrue(without.contains("not approved"))
        assertFalse(without.contains("caption"))
        // The caption is user content and belongs in the message, never in the operator summary.
        assertFalse(withCaption.contains("Schau mal"))
    }

    private fun action(caption: String?) =
        PlannedSideEffect.UploadApprovedImage(
            idempotencyKey = "key-1",
            assetId = "img_00000000000000000000000000000001",
            personaKey = "human",
            caption = caption,
            blockRepeats = true,
        )

    private fun codeOf(block: () -> Unit): String {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected a failure", failure != null)
        return ImageSendDiagnostics.failureCode(failure!!)
    }

    private fun store(): ApprovedMediaAssetStore =
        ApprovedMediaAssetStore(
            rootDirectory = directory,
            maximumAssetBytes = 1_024,
            random = SecureRandom(byteArrayOf(1, 2, 3, 4)),
            nowMs = { 1234L },
        )

    private fun png(size: Int) = ByteArrayInputStream(pngBytes(size))

    private fun pngBytes(size: Int): ByteArray =
        ByteArray(size.coerceAtLeast(PNG.size)).also {
            PNG.copyInto(it)
            for (index in PNG.size until it.size) it[index] = (index % 251).toByte()
        }

    private companion object {
        val PNG =
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            )
    }
}
