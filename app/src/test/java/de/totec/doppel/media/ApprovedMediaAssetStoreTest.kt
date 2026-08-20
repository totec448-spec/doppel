package de.totec.doppel.media

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedMediaAssetStoreTest {
    private val directory = Files.createTempDirectory("approved-media-test").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `imports into flat private catalogue and resolves only for owning persona`() {
        val store = store()
        val imported =
            store.importImage(
                personaKey = "human",
                displayName = "../../Urlaub\u0000.png",
                declaredMimeType = "image/png",
                source = ByteArrayInputStream(pngBytes(128)),
            )

        assertTrue(imported.created)
        assertTrue(imported.asset.assetId.matches(Regex("^img_[a-f0-9]{32}$")))
        assertEquals("Urlaub.png", imported.asset.displayName)
        assertEquals("image/png", imported.asset.mimeType)
        assertEquals(listOf(imported.asset), store.list("human"))
        assertTrue(store.list("female").isEmpty())

        val handle = store.openForSend(imported.asset.assetId, "human")
        assertEquals(imported.asset, handle.asset)
        assertEquals(directory.canonicalFile, handle.file.canonicalFile.parentFile)
        expectFailure<IllegalArgumentException> {
            store.openForSend(imported.asset.assetId, "female")
        }
    }

    @Test
    fun `same image is deduplicated per persona but remains separately scoped`() {
        val store = store()
        val bytes = pngBytes(96)
        val first =
            store.importImage("human", "one.png", "image/png", ByteArrayInputStream(bytes))
        val duplicate =
            store.importImage("human", "two.png", "image/png", ByteArrayInputStream(bytes))
        val otherPersona =
            store.importImage("female", "one.png", "image/png", ByteArrayInputStream(bytes))

        assertTrue(first.created)
        assertFalse(duplicate.created)
        assertEquals(first.asset.assetId, duplicate.asset.assetId)
        assertTrue(otherPersona.created)
        assertNotEquals(first.asset.assetId, otherPersona.asset.assetId)
        assertEquals(1, store.list("human").size)
        assertEquals(1, store.list("female").size)
    }

    @Test
    fun `character references stay separate from sendable images and delete all is kind scoped`() {
        val store = store()
        val sameBytes = pngBytes(96)
        val sendable =
            store.importImage(
                "human",
                "sendable.png",
                "image/png",
                ByteArrayInputStream(sameBytes),
            )
        val firstReference =
            store.importImage(
                "human",
                "front.png",
                "image/png",
                ByteArrayInputStream(sameBytes),
                ApprovedMediaKind.CHARACTER_REFERENCE,
            )
        val secondReference =
            store.importImage(
                "human",
                "side.png",
                "image/png",
                ByteArrayInputStream(pngBytes(97)),
                ApprovedMediaKind.CHARACTER_REFERENCE,
            )

        assertNotEquals(sendable.asset.assetId, firstReference.asset.assetId)
        assertEquals(listOf(sendable.asset), store.list("human"))
        assertEquals(
            setOf(firstReference.asset.assetId, secondReference.asset.assetId),
            store.list(
                "human",
                kind = ApprovedMediaKind.CHARACTER_REFERENCE,
            ).mapTo(mutableSetOf(), ApprovedMediaAsset::assetId),
        )
        expectFailure<IllegalArgumentException> {
            store.openForSend(firstReference.asset.assetId, "human")
        }
        expectFailure<IllegalArgumentException> {
            store.openForReference(sendable.asset.assetId, "human")
        }

        assertEquals(
            2,
            store.deleteAll("human", ApprovedMediaKind.CHARACTER_REFERENCE),
        )
        assertTrue(
            store.list(
                "human",
                kind = ApprovedMediaKind.CHARACTER_REFERENCE,
            ).isEmpty(),
        )
        assertEquals(listOf(sendable.asset), store.list("human"))
    }

    @Test
    fun `character reference collection is capped at eight per persona`() {
        val store = store()
        repeat(ApprovedMediaAssetStore.MAX_REFERENCES_PER_PERSONA) { index ->
            assertTrue(
                store.importImage(
                    "human",
                    "reference-$index.png",
                    "image/png",
                    ByteArrayInputStream(pngBytes(64 + index)),
                    ApprovedMediaKind.CHARACTER_REFERENCE,
                ).created,
            )
        }

        expectFailure<IOException> {
            store.importImage(
                "human",
                "reference-nine.png",
                "image/png",
                ByteArrayInputStream(pngBytes(80)),
                ApprovedMediaKind.CHARACTER_REFERENCE,
            )
        }
        assertEquals(
            ApprovedMediaAssetStore.MAX_REFERENCES_PER_PERSONA,
            store.list(
                "human",
                kind = ApprovedMediaKind.CHARACTER_REFERENCE,
            ).size,
        )
    }

    @Test
    fun `version one approved images remain sendable after the kind migration`() {
        val current = store()
        val imported =
            current.importImage(
                "human",
                "legacy.png",
                "image/png",
                ByteArrayInputStream(pngBytes(72)),
            ).asset
        val metadata = File(directory, "${imported.assetId}.meta")
        val magic = DataInputStream(metadata.inputStream()).use { it.readInt() }
        DataOutputStream(metadata.outputStream()).use { output ->
            output.writeInt(magic)
            output.writeInt(1)
            output.writeUTF(imported.assetId)
            output.writeUTF(imported.personaKey)
            output.writeUTF(imported.displayName)
            output.writeUTF(imported.mimeType)
            output.writeLong(imported.sizeBytes)
            output.writeUTF(imported.sha256)
            output.writeLong(imported.createdAtMs)
        }

        val migrated = store()
        val legacy = migrated.list("human").single()

        assertEquals(ApprovedMediaKind.IMAGE, legacy.kind)
        assertEquals(imported.assetId, migrated.openForSend(imported.assetId, "human").asset.assetId)
        assertTrue(
            migrated.list("human", kind = ApprovedMediaKind.CHARACTER_REFERENCE).isEmpty(),
        )
    }

    @Test
    fun `rejects traversal identifiers and persona paths`() {
        val store = store()
        val imported =
            store.importImage("human", "ok.png", "image/png", ByteArrayInputStream(pngBytes(64)))

        listOf("../secret", imported.asset.assetId + "/x", "img_nothex").forEach { badId ->
            expectFailure<IllegalArgumentException> {
                store.openForSend(badId, "human")
            }
        }
        listOf("../human", "a/b", "x", "human#other").forEach { badPersona ->
            expectFailure<IllegalArgumentException> {
                store.list(badPersona)
            }
        }
        assertTrue(store.openForSend(imported.asset.assetId, "human").file.isFile)
    }

    @Test
    fun `magic bytes are authoritative and mime spoof is rejected`() {
        val store = store()
        expectFailure<IOException> {
            store.importImage(
                "human",
                "fake.png",
                "image/png",
                ByteArrayInputStream("not an image".toByteArray()),
            )
        }
        expectFailure<IOException> {
            store.importImage(
                "human",
                "actually.png",
                "image/jpeg",
                ByteArrayInputStream(pngBytes(64)),
            )
        }
        expectFailure<IOException> {
            store.importImage(
                "human",
                "document.png",
                "application/pdf",
                ByteArrayInputStream(pngBytes(64)),
            )
        }
        assertTrue(store.list("human").isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `streaming byte limit deletes partial import`() {
        val store = store(maximumBytes = 32)
        expectFailure<IOException> {
            store.importImage(
                "human",
                "large.png",
                "image/png",
                ByteArrayInputStream(pngBytes(64)),
            )
        }

        assertTrue(store.list("human").isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `tampering is detected before send and wrong persona cannot delete`() {
        val store = store()
        val imported =
            store.importImage("human", "safe.png", "image/png", ByteArrayInputStream(pngBytes(64)))

        expectFailure<IllegalArgumentException> {
            store.delete(imported.asset.assetId, "female")
        }
        val handle = store.openForSend(imported.asset.assetId, "human")
        handle.file.appendBytes(byteArrayOf(1))
        expectFailure<IllegalArgumentException> {
            store.openForSend(imported.asset.assetId, "human")
        }

        // Restore the expected length but alter content: digest validation must
        // still catch it before the bridge sees any bytes.
        val changed = pngBytes(64).also { it[it.lastIndex] = 42 }
        handle.file.writeBytes(changed)
        expectFailure<IllegalArgumentException> {
            store.openForSend(imported.asset.assetId, "human")
        }
        assertTrue(store.delete(imported.asset.assetId, "human"))
        assertFalse(store.delete(imported.asset.assetId, "human"))
    }

    @Test
    fun `sent history is durable persona scoped and resettable without storing chat id`() {
        val store = store()
        val human =
            store.importImage("human", "human.png", "image/png", ByteArrayInputStream(pngBytes(64)))
        val female =
            store.importImage("female", "female.png", "image/png", ByteArrayInputStream(pngBytes(65)))
        val chat = "49123456789@s.whatsapp.net"

        assertFalse(store.wasSentTo(human.asset.assetId, "human", chat))
        store.markSentTo(human.asset.assetId, "human", chat)
        store.markSentTo(female.asset.assetId, "female", chat)
        assertTrue(store.wasSentTo(human.asset.assetId, "human", chat))
        assertTrue(store().wasSentTo(human.asset.assetId, "human", chat))
        assertTrue(store.listSendable("human", chat, blockRepeats = true).isEmpty())
        assertEquals(
            listOf(human.asset),
            store.listSendable("human", chat, blockRepeats = false),
        )
        expectFailure<IllegalArgumentException> {
            store.wasSentTo(human.asset.assetId, "female", chat)
        }
        val markerFiles =
            File(directory, "sent").walkTopDown().filter(File::isFile).toList()
        assertEquals(2, markerFiles.size)
        assertTrue(markerFiles.none { it.readText().contains(chat) || it.name.contains("491234") })

        assertEquals(1, store.clearSentHistory(chat, "human"))
        assertFalse(store.wasSentTo(human.asset.assetId, "human", chat))
        assertTrue(store.wasSentTo(female.asset.assetId, "female", chat))
        assertEquals(1, store.clearSentHistory(chat))
        assertFalse(store.wasSentTo(female.asset.assetId, "female", chat))
    }

    @Test
    fun `corrupt metadata is ignored by list without exposing a path`() {
        val store = store()
        File(directory, "img_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.meta").writeText("broken")
        File(directory, "unrelated.asset").writeText("leave me alone")

        assertTrue(store.list("human").isEmpty())
        // Reopening performs bounded orphan cleanup but must not treat an
        // unrelated filename as a valid opaque ID.
        val reopened = store()
        assertTrue(reopened.list("human").isEmpty())
    }

    @Test
    fun `profile pictures are a third kind that neither sending nor generation can reach`() {
        val store = store()
        val sameBytes = pngBytes(96)
        val sendable = store.importImage("human", "holiday.png", "image/png", ByteArrayInputStream(sameBytes))
        val face =
            store.importImage(
                "human",
                "face.png",
                "image/png",
                ByteArrayInputStream(sameBytes),
                ApprovedMediaKind.PROFILE_PICTURE,
            )

        // Same bytes, three separate assets: an avatar must not become sendable because a picture
        // of the same person happens to be in the gallery.
        assertNotEquals(sendable.asset.assetId, face.asset.assetId)
        assertEquals(listOf(sendable.asset), store.list("human"))
        assertEquals(
            listOf(face.asset),
            store.list("human", kind = ApprovedMediaKind.PROFILE_PICTURE),
        )
        expectFailure<IllegalArgumentException> {
            store.openForSend(face.asset.assetId, "human")
        }
        expectFailure<IllegalArgumentException> {
            store.openForReference(face.asset.assetId, "human")
        }
        expectFailure<IllegalArgumentException> {
            store.openForProfilePicture(sendable.asset.assetId, "human")
        }
        assertEquals(
            face.asset.assetId,
            store.openForProfilePicture(face.asset.assetId, "human").asset.assetId,
        )
        assertTrue(store.delete(face.asset.assetId, "human", ApprovedMediaKind.PROFILE_PICTURE))
        assertEquals(listOf(sendable.asset), store.list("human"))
    }

    @Test
    fun `profile picture collection is capped per persona`() {
        val store = store()
        repeat(ApprovedMediaAssetStore.MAX_PROFILE_PICTURES_PER_PERSONA) { index ->
            assertTrue(
                store.importImage(
                    "human",
                    "face-$index.png",
                    "image/png",
                    ByteArrayInputStream(pngBytes(64 + index)),
                    ApprovedMediaKind.PROFILE_PICTURE,
                ).created,
            )
        }

        expectFailure<IOException> {
            store.importImage(
                "human",
                "one-too-many.png",
                "image/png",
                ByteArrayInputStream(pngBytes(200)),
                ApprovedMediaKind.PROFILE_PICTURE,
            )
        }
        assertEquals(
            ApprovedMediaAssetStore.MAX_PROFILE_PICTURES_PER_PERSONA,
            store.list("human", kind = ApprovedMediaKind.PROFILE_PICTURE).size,
        )
    }

    /**
     * The rotation records the picture it put up by id and steps to the next one in this order, so
     * the order has to be the same on every start — [ApprovedMediaAssetStore.list] itself answers
     * newest first, which would reshuffle the moment a picture is added.
     */
    @Test
    fun `the profile picture library lists oldest first and hides the other kinds`() {
        var clock = 1_000L
        val store =
            ApprovedMediaAssetStore(
                rootDirectory = directory,
                maximumAssetBytes = 1_024,
                random = SecureRandom(byteArrayOf(5, 6, 7, 8)),
                nowMs = { clock },
            )
        val library = ApprovedMediaProfilePictures(store)
        val first =
            store.importImage(
                "human",
                "first.png",
                "image/png",
                ByteArrayInputStream(pngBytes(64)),
                ApprovedMediaKind.PROFILE_PICTURE,
            ).asset
        clock = 2_000L
        val second =
            store.importImage(
                "human",
                "second.png",
                "image/png",
                ByteArrayInputStream(pngBytes(65)),
                ApprovedMediaKind.PROFILE_PICTURE,
            ).asset
        clock = 3_000L
        store.importImage("human", "gallery.png", "image/png", ByteArrayInputStream(pngBytes(66)))

        assertEquals(listOf(first.assetId, second.assetId), library.list("human"))
        assertTrue(library.list("female").isEmpty())

        val staged = File(directory, "staged/face.jpg")
        library.stage("human", second.assetId, staged)
        assertEquals(second.sizeBytes, staged.length())
        assertEquals(
            File(directory, "${first.assetId}.asset").canonicalPath,
            File(library.path("human", first.assetId)!!).canonicalPath,
        )
        // A picture of another persona is not this persona's picture, however valid its id is.
        assertNull(library.path("female", first.assetId))
    }

    private fun store(maximumBytes: Long = 1_024): ApprovedMediaAssetStore =
        ApprovedMediaAssetStore(
            rootDirectory = directory,
            maximumAssetBytes = maximumBytes,
            random = SecureRandom(byteArrayOf(1, 2, 3, 4)),
            nowMs = { 1234L },
        )

    private fun pngBytes(size: Int): ByteArray =
        ByteArray(size.coerceAtLeast(PNG.size)).also {
            PNG.copyInto(it)
            for (index in PNG.size until it.size) it[index] = (index % 251).toByte()
        }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(
            "Expected ${T::class.java.simpleName}, got ${failure?.javaClass?.simpleName}",
            failure is T,
        )
    }

    companion object {
        private val PNG =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
            )
    }
}
