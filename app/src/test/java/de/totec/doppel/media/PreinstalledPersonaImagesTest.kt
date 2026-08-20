package de.totec.doppel.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled starter gallery: it must land in the persona's approved catalogue
 * exactly once, survive restarts without duplicating, and never resurrect a
 * picture the owner deleted.
 */
class PreinstalledPersonaImagesTest {
    private val directory = Files.createTempDirectory("preinstalled-media-test").toFile()
    private val root = File(directory, "store")
    private val ledger = File(directory, "preinstalled.index")

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `bundled pictures are approved for their persona with the manifest name`() {
        val store = store()

        assertEquals(2, seeder(store).seed().imported)

        val assets = store.list("female").sortedBy(ApprovedMediaAsset::displayName)
        assertEquals(
            listOf("Selfie im Aufzug", "Uni-Gebäude"),
            assets.map(ApprovedMediaAsset::displayName),
        )
        assertTrue(assets.all { it.mimeType == "image/jpeg" })
        assertTrue(store.list("male").isEmpty())
    }

    @Test
    fun `a second start copies nothing and never opens a picture again`() {
        val store = store()
        seeder(store).seed()

        var picturesOpened = 0
        val second =
            PreinstalledPersonaImages(store, ledger, open = { path ->
                if (!path.endsWith(".json")) picturesOpened++
                asset(path)
            })
        val outcome = second.seed()
        assertEquals(0, outcome.imported)
        assertTrue(outcome.sealed)
        // The manifest itself is read on every start — that is what tells the seal whether the
        // bundled set still is the one it sealed. No picture is touched.
        assertEquals(0, picturesOpened)
        assertEquals(2, store.list("female").size)
    }

    /**
     * The seal used to be a bare marker, so the first successful start was the last one that ever
     * looked at the manifest and every picture added to a later build stayed invisible for the life
     * of the install.
     */
    @Test
    fun `a picture added to a later build is picked up on the next start`() {
        val store = store()
        assertEquals(2, seeder(store).seed().imported)

        val grown =
            PreinstalledPersonaImages(store, ledger, open = { path ->
                when (path) {
                    "persona-images/manifest.json" ->
                        ByteArrayInputStream(GROWN_MANIFEST.toByteArray())
                    "persona-images/female/library.jpg" -> ByteArrayInputStream(jpegBytes(3))
                    else -> asset(path)
                }
            })
        val outcome = grown.seed()

        assertEquals(1, outcome.imported)
        assertEquals(3, outcome.stored)
        assertEquals(3, store.list("female").size)
    }

    /** Deleting an approved picture is a decision, not a glitch to be repaired. */
    @Test
    fun `a deleted picture stays deleted`() {
        val store = store()
        seeder(store).seed()
        val victim = store.list("female").first()
        assertTrue(store.delete(victim.assetId, "female"))

        assertEquals(0, seeder(store).seed().imported)
        assertEquals(1, store.list("female").size)
    }

    /** A file that fails to copy has to be retried, so the manifest is not sealed. */
    @Test
    fun `a broken bundled file is retried on the next start`() {
        val store = store()
        val flaky =
            PreinstalledPersonaImages(store, ledger, open = { path ->
                if (path.endsWith("elevator.jpg")) throw FileNotFoundException(path) else asset(path)
            })
        assertEquals(1, flaky.seed().imported)
        assertEquals(1, store.list("female").size)

        assertEquals(1, seeder(store).seed().imported)
        assertEquals(2, store.list("female").size)
    }

    /**
     * A bundled file that a later build re-encodes smaller arrives as a *new* asset, because the
     * store keys duplicates by digest. Without naming the old digest the install keeps both, and
     * for references that means the retired one is attached to every generation request forever.
     */
    @Test
    fun `a bundled file replaced by a smaller re-encode does not leave its predecessor behind`() {
        val store = store()
        seeder(store).seed()
        val retiring = store.list("female").single { it.displayName == "Selfie im Aufzug" }

        val rebuilt =
            PreinstalledPersonaImages(
                store = store,
                ledger = File(directory, "rebuilt.index"),
                open = { path ->
                    when (path) {
                        "persona-images/manifest.json" ->
                            ByteArrayInputStream(REENCODED_MANIFEST.toByteArray())
                        "persona-images/female/elevator-small.jpg" ->
                            ByteArrayInputStream(jpegBytes(7))
                        else -> asset(path)
                    }
                },
                superseded = setOf(retiring.sha256),
            )
        val outcome = rebuilt.seed()

        assertEquals(1, outcome.retired)
        assertEquals(
            listOf("Selfie im Aufzug (klein)", "Uni-Gebäude"),
            store.list("female").map(ApprovedMediaAsset::displayName).sorted(),
        )
    }

    @Test
    fun `a manifest from another version is ignored instead of crashing`() {
        val store = store()
        val wrongVersion =
            PreinstalledPersonaImages(store, ledger, open = { path ->
                if (path.endsWith(".json")) {
                    ByteArrayInputStream("""{"version":99}""".toByteArray())
                } else {
                    asset(path)
                }
            })
        assertEquals(0, wrongVersion.seed().imported)
        assertTrue(store.list("female").isEmpty())
    }

    private fun seeder(store: ApprovedMediaAssetStore) =
        PreinstalledPersonaImages(store, ledger, ::asset)

    private fun asset(path: String): InputStream =
        when (path) {
            "persona-images/manifest.json" -> ByteArrayInputStream(MANIFEST.toByteArray())
            "persona-images/female/elevator.jpg" -> ByteArrayInputStream(jpegBytes(1))
            "persona-images/female/campus.jpg" -> ByteArrayInputStream(jpegBytes(2))
            else -> throw FileNotFoundException(path)
        }

    private fun store(): ApprovedMediaAssetStore =
        ApprovedMediaAssetStore(
            rootDirectory = root,
            maximumAssetBytes = 4_096,
            random = SecureRandom(byteArrayOf(9, 8, 7, 6)),
            nowMs = { 1234L },
        )

    private fun jpegBytes(seed: Int): ByteArray =
        ByteArray(256).also {
            JPEG.copyInto(it)
            for (index in JPEG.size until it.size) it[index] = ((index * seed) % 251).toByte()
        }

    companion object {
        private val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        private val MANIFEST =
            """
            {
              "version": 1,
              "personas": [
                {
                  "persona": "female",
                  "images": [
                    {"file": "elevator.jpg", "name": "Selfie im Aufzug"},
                    {"file": "campus.jpg", "name": "Uni-Gebäude"},
                    {"file": "../escape.jpg", "name": "Pfadflucht"}
                  ]
                }
              ]
            }
            """.trimIndent()

        /** The same set with one picture re-encoded under a new file name. */
        private val REENCODED_MANIFEST =
            """
            {
              "version": 1,
              "personas": [
                {
                  "persona": "female",
                  "images": [
                    {"file": "elevator-small.jpg", "name": "Selfie im Aufzug (klein)"},
                    {"file": "campus.jpg", "name": "Uni-Gebäude"}
                  ]
                }
              ]
            }
            """.trimIndent()

        /** The same set with one more picture, as a later build would ship it. */
        private val GROWN_MANIFEST =
            """
            {
              "version": 1,
              "personas": [
                {
                  "persona": "female",
                  "images": [
                    {"file": "elevator.jpg", "name": "Selfie im Aufzug"},
                    {"file": "campus.jpg", "name": "Uni-Gebäude"},
                    {"file": "library.jpg", "name": "Bibliothek"}
                  ]
                }
              ]
            }
            """.trimIndent()
    }
}
