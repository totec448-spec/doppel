package de.totec.doppel.media

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

enum class ApprovedMediaKind {
    IMAGE,
    CHARACTER_REFERENCE,

    /**
     * A face for the account itself. Never sendable and never a generation reference: these are
     * the pictures the profile-picture rotation puts up, and mixing them into either of the other
     * two kinds would mean the bot sends its own avatar to a contact.
     */
    PROFILE_PICTURE,
}

data class ApprovedMediaAsset(
    val assetId: String,
    val personaKey: String,
    val displayName: String,
    val mimeType: String,
    val kind: ApprovedMediaKind,
    val sizeBytes: Long,
    val sha256: String,
    val createdAtMs: Long,
)

data class ApprovedMediaHandle(
    val asset: ApprovedMediaAsset,
    val file: File,
)

data class ApprovedMediaImport(
    val asset: ApprovedMediaAsset,
    val created: Boolean,
)

/**
 * Private, flat and deliberately small catalogue for media which the owner has
 * explicitly approved through Android's document picker.
 *
 * The AI and admin layers only receive random [ApprovedMediaAsset.assetId]
 * values. Original document URIs and filesystem paths never cross this store.
 * Every resolving operation validates the opaque identifier, persona binding,
 * canonical path, persisted length and (for sending) SHA-256 digest.
 */
class ApprovedMediaAssetStore(
    rootDirectory: File,
    private val maximumAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
    private val random: SecureRandom = SecureRandom(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val root = rootDirectory.canonicalFile

    init {
        require(maximumAssetBytes in 1..ABSOLUTE_MAX_ASSET_BYTES) {
            "Invalid approved-media byte limit"
        }
        ensureRoot()
        cleanupIncompleteFiles()
    }

    @Synchronized
    fun importImage(
        personaKey: String,
        displayName: String?,
        declaredMimeType: String?,
        source: InputStream,
        kind: ApprovedMediaKind = ApprovedMediaKind.IMAGE,
    ): ApprovedMediaImport {
        val persona = requirePersonaKey(personaKey)
        ensureRoot()
        val staging = safeFile(".import-${randomHex(16)}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(16)
        var headerSize = 0
        var total = 0L

        try {
            BufferedInputStream(source, STREAM_BUFFER_BYTES).use { input ->
                BufferedOutputStream(staging.outputStream(), STREAM_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > maximumAssetBytes) {
                            throw IOException("Image exceeds the approval size limit")
                        }
                        if (headerSize < header.size) {
                            val copied = minOf(read, header.size - headerSize)
                            buffer.copyInto(header, headerSize, 0, copied)
                            headerSize += copied
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) throw IOException("An empty file cannot be approved")
            val detectedMime = detectImageMime(header, headerSize)
                ?: throw IOException("Only supported image files can be approved")
            verifyDeclaredMime(declaredMimeType, detectedMime)
            val sha256 = digest.digest().toHex()

            findDuplicate(persona, kind, sha256, total)?.let { existing ->
                staging.delete()
                return ApprovedMediaImport(existing, created = false)
            }
            val personaAssetCount =
                metadataFiles()
                    .asSequence()
                    .mapNotNull(::readMetadataSafely)
                    .count { it.personaKey == persona && it.kind == kind && hasValidDataFile(it) }
            val maximumCount = maximumCount(kind)
            if (personaAssetCount >= maximumCount) {
                throw IOException("Maximum number of ${kind.label()} images reached for this persona")
            }

            val assetId = newAssetId()
            val asset =
                ApprovedMediaAsset(
                    assetId = assetId,
                    personaKey = persona,
                    displayName = sanitizeDisplayName(displayName, detectedMime),
                    mimeType = detectedMime,
                    kind = kind,
                    sizeBytes = total,
                    sha256 = sha256,
                    createdAtMs = nowMs(),
                )
            val dataFile = dataFile(assetId)
            val metadataStaging = safeFile(".$assetId.meta.part")
            val metadataFile = metadataFile(assetId)
            try {
                moveAtomically(staging, dataFile)
                writeMetadata(metadataStaging, asset)
                moveAtomically(metadataStaging, metadataFile)
            } catch (failure: Throwable) {
                staging.delete()
                metadataStaging.delete()
                metadataFile.delete()
                dataFile.delete()
                throw failure
            }
            return ApprovedMediaImport(asset, created = true)
        } catch (failure: Throwable) {
            staging.delete()
            throw failure
        }
    }

    @Synchronized
    fun list(
        personaKey: String,
        query: String = "",
        limit: Int = 100,
        kind: ApprovedMediaKind = ApprovedMediaKind.IMAGE,
    ): List<ApprovedMediaAsset> {
        val persona = requirePersonaKey(personaKey)
        val normalizedQuery = query.trim().lowercase(Locale.ROOT).take(200)
        return metadataFiles()
            .asSequence()
            .mapNotNull(::readMetadataSafely)
            .filter { it.personaKey == persona }
            .filter { it.kind == kind }
            .filter {
                normalizedQuery.isEmpty() ||
                    it.displayName.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
            .filter(::hasValidDataFile)
            .sortedByDescending(ApprovedMediaAsset::createdAtMs)
            .take(limit.coerceIn(1, MAX_LIST_SIZE))
            .toList()
    }

    fun hasAssets(personaKey: String): Boolean =
        runCatching { list(personaKey, limit = 1).isNotEmpty() }.getOrDefault(false)

    fun hasReferences(personaKey: String): Boolean =
        runCatching {
            list(
                personaKey,
                limit = 1,
                kind = ApprovedMediaKind.CHARACTER_REFERENCE,
            ).isNotEmpty()
        }.getOrDefault(false)

    @Synchronized
    fun deleteAll(
        personaKey: String,
        kind: ApprovedMediaKind,
    ): Int {
        val persona = requirePersonaKey(personaKey)
        val assets = list(persona, limit = MAX_LIST_SIZE, kind = kind)
        var deleted = 0
        for (asset in assets) {
            if (delete(asset.assetId, persona, kind)) deleted++
        }
        return deleted
    }

    /**
     * Removes every asset whose bytes hash to one of [sha256Digests], across all personas.
     *
     * This exists for bundled files that were replaced by a smaller re-encode. The store keys
     * duplicates by digest, so the replacement is a new asset and the old copy would otherwise stay
     * next to it forever — for a character reference that means both are attached to every
     * generation request, which is the cost the re-encode was supposed to remove. A digest is
     * evidence about bytes, not about who imported them: an owner-picked file that happens to be
     * byte-identical to the retired bundled one *is* the retired bundled one.
     */
    @Synchronized
    fun deleteByDigest(
        sha256Digests: Set<String>,
        kind: ApprovedMediaKind,
    ): Int {
        val wanted = sha256Digests.filter(SHA256::matches).toSet()
        if (wanted.isEmpty()) return 0
        var deleted = 0
        metadataFiles()
            .asSequence()
            .mapNotNull(::readMetadataSafely)
            .filter { it.kind == kind && it.sha256 in wanted }
            .forEach { asset ->
                if (runCatching { delete(asset.assetId, asset.personaKey, kind) }.getOrDefault(false)) {
                    deleted++
                }
            }
        return deleted
    }

    @Synchronized
    fun listSendable(
        personaKey: String,
        chatId: String,
        blockRepeats: Boolean,
        query: String = "",
        limit: Int = 100,
    ): List<ApprovedMediaAsset> {
        val assets = list(personaKey, query, MAX_ASSETS_PER_PERSONA, ApprovedMediaKind.IMAGE)
        if (!blockRepeats) return assets.take(limit.coerceIn(1, MAX_LIST_SIZE))
        val markerName = chatMarkerName(chatId)
        return assets
            .asSequence()
            .filter { asset ->
                val directory = sentAssetDirectory(asset.assetId)
                val marker = File(directory, markerName).canonicalFile
                require(marker.parentFile == directory) { "Unsicherer Bildhistorienpfad" }
                !marker.isFile
            }
            .take(limit.coerceIn(1, MAX_LIST_SIZE))
            .toList()
    }

    fun hasSendableAsset(
        personaKey: String,
        chatId: String,
        blockRepeats: Boolean,
    ): Boolean =
        runCatching {
            listSendable(personaKey, chatId, blockRepeats, limit = 1).isNotEmpty()
        }.getOrDefault(false)

    /**
     * Full validation for a pending send. Hashing happens only when bytes are
     * about to leave the app, so idle/list operations remain cheap.
     */
    @Synchronized
    fun openForSend(
        assetId: String,
        personaKey: String,
    ): ApprovedMediaHandle =
        open(assetId, personaKey, ApprovedMediaKind.IMAGE)

    /** Full integrity validation immediately before a private reference enters an API request. */
    @Synchronized
    fun openForReference(
        assetId: String,
        personaKey: String,
    ): ApprovedMediaHandle =
        open(assetId, personaKey, ApprovedMediaKind.CHARACTER_REFERENCE)

    /** Full integrity validation immediately before a face goes up on the account itself. */
    @Synchronized
    fun openForProfilePicture(
        assetId: String,
        personaKey: String,
    ): ApprovedMediaHandle =
        open(assetId, personaKey, ApprovedMediaKind.PROFILE_PICTURE)

    /**
     * The same validation every other reader gets, for a copy that leaves the app.
     *
     * Export is the one operation where the destination is outside this store, so it goes through
     * [open] rather than around it: an asset that fails the digest check is not something to hand
     * to a file manager either.
     */
    @Synchronized
    fun openForExport(
        assetId: String,
        personaKey: String,
        kind: ApprovedMediaKind,
    ): ApprovedMediaHandle = open(assetId, personaKey, kind)

    private fun open(
        assetId: String,
        personaKey: String,
        expectedKind: ApprovedMediaKind,
    ): ApprovedMediaHandle {
        val id = requireAssetId(assetId)
        val persona = requirePersonaKey(personaKey)
        val asset = readMetadata(metadataFile(id))
        require(asset.assetId == id && asset.personaKey == persona && asset.kind == expectedKind) {
            "Image is not approved for this persona"
        }
        val file = dataFile(id)
        require(hasValidDataFile(asset)) { "Approved image is missing or damaged" }
        val actualHash = file.inputStream().buffered(STREAM_BUFFER_BYTES).use(::sha256)
        require(actualHash == asset.sha256) { "Approved image was modified" }
        return ApprovedMediaHandle(asset, file)
    }

    /**
     * Durable, O(1) repeat-image marker scoped by approved asset (therefore
     * persona) and conversation. Only a SHA-256 of the chat ID is stored.
     */
    @Synchronized
    fun wasSentTo(
        assetId: String,
        personaKey: String,
        chatId: String,
    ): Boolean {
        requireExistingAsset(assetId, personaKey)
        return sentMarker(assetId, chatId).isFile
    }

    @Synchronized
    fun markSentTo(
        assetId: String,
        personaKey: String,
        chatId: String,
    ) {
        requireExistingAsset(assetId, personaKey)
        val directory = sentAssetDirectory(assetId)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Image history could not be created")
        }
        require(directory.isDirectory && directory.canonicalFile.parentFile == sentRoot()) {
            "Unsicherer Bildhistorienpfad"
        }
        val marker = sentMarker(assetId, chatId)
        if (!marker.exists() && !marker.createNewFile()) {
            throw IOException("Image history could not be saved")
        }
    }

    /**
     * Used by reset/wipe admin actions. Cost is proportional only to the small
     * approved-asset catalogue and consumes no idle CPU.
     */
    @Synchronized
    fun clearSentHistory(
        chatId: String,
        personaKey: String? = null,
    ): Int {
        val persona = personaKey?.let(::requirePersonaKey)
        val markerName = chatMarkerName(chatId)
        var deleted = 0
        metadataFiles()
            .asSequence()
            .mapNotNull(::readMetadataSafely)
            .filter { persona == null || it.personaKey == persona }
            .forEach { asset ->
                val directory = sentAssetDirectory(asset.assetId)
                val marker =
                    File(directory, markerName).canonicalFile.also {
                        require(it.parentFile == directory) { "Unsicherer Bildhistorienpfad" }
                    }
                if (marker.isFile && marker.delete()) deleted++
                if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
            }
        return deleted
    }

    @Synchronized
    fun delete(
        assetId: String,
        personaKey: String,
        kind: ApprovedMediaKind = ApprovedMediaKind.IMAGE,
    ): Boolean {
        val id = requireAssetId(assetId)
        val persona = requirePersonaKey(personaKey)
        val metadata = metadataFile(id)
        if (!metadata.isFile) return false
        val asset = readMetadata(metadata)
        require(asset.assetId == id && asset.personaKey == persona && asset.kind == kind) {
            "Image is not approved for this persona"
        }
        // Removing the authority record first prevents a concurrent resolve
        // from starting after the owner has confirmed deletion.
        if (!metadata.delete()) throw IOException("Image approval could not be removed")
        val data = dataFile(id)
        if (data.exists() && !data.delete()) {
            throw IOException("Image file could not be removed")
        }
        deleteSentAssetDirectory(id)
        return true
    }

    private fun requireExistingAsset(
        assetId: String,
        personaKey: String,
    ): ApprovedMediaAsset {
        val id = requireAssetId(assetId)
        val persona = requirePersonaKey(personaKey)
        val asset = readMetadata(metadataFile(id))
        require(
            asset.assetId == id &&
                asset.personaKey == persona &&
                asset.kind == ApprovedMediaKind.IMAGE &&
                hasValidDataFile(asset),
        ) {
            "Image is not approved for this persona"
        }
        return asset
    }

    private fun findDuplicate(
        personaKey: String,
        kind: ApprovedMediaKind,
        sha256: String,
        sizeBytes: Long,
    ): ApprovedMediaAsset? =
        metadataFiles()
            .asSequence()
            .mapNotNull(::readMetadataSafely)
            .firstOrNull {
                it.personaKey == personaKey &&
                    it.kind == kind &&
                    it.sha256 == sha256 &&
                    it.sizeBytes == sizeBytes &&
                    hasValidDataFile(it)
            }

    private fun metadataFiles(): List<File> =
        root.listFiles { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX) }
            ?.toList()
            .orEmpty()

    private fun hasValidDataFile(asset: ApprovedMediaAsset): Boolean {
        if (!ASSET_ID.matches(asset.assetId)) return false
        val file = runCatching { dataFile(asset.assetId) }.getOrNull() ?: return false
        return file.isFile && file.length() == asset.sizeBytes && asset.sizeBytes in 1..maximumAssetBytes
    }

    private fun writeMetadata(
        file: File,
        asset: ApprovedMediaAsset,
    ) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
            output.writeInt(METADATA_MAGIC)
            output.writeInt(METADATA_VERSION)
            output.writeUTF(asset.assetId)
            output.writeUTF(asset.personaKey)
            output.writeUTF(asset.displayName)
            output.writeUTF(asset.mimeType)
            output.writeUTF(asset.kind.name)
            output.writeLong(asset.sizeBytes)
            output.writeUTF(asset.sha256)
            output.writeLong(asset.createdAtMs)
        }
    }

    private fun readMetadataSafely(file: File): ApprovedMediaAsset? =
        runCatching { readMetadata(file) }.getOrNull()

    private fun readMetadata(file: File): ApprovedMediaAsset {
        requireSafeChild(file)
        if (!file.isFile || file.length() !in 1..MAX_METADATA_BYTES) {
            throw IOException("Invalid image approval")
        }
        try {
            return DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readInt() == METADATA_MAGIC) { "Invalid image approval" }
                val version = input.readInt()
                require(version in LEGACY_METADATA_VERSION..METADATA_VERSION) {
                    "Unknown image approval"
                }
                val asset =
                    ApprovedMediaAsset(
                        assetId = requireAssetId(input.readUTF()),
                        personaKey = requirePersonaKey(input.readUTF()),
                        displayName = input.readUTF().also { require(it.length in 1..MAX_NAME_CHARS) },
                        mimeType = input.readUTF().also { require(it in SUPPORTED_IMAGE_MIMES) },
                        // Version 1 predates private character references; every existing row was
                        // therefore an ordinary approved/sendable image.
                        kind =
                            if (version >= 2) {
                                ApprovedMediaKind.valueOf(input.readUTF())
                            } else {
                                ApprovedMediaKind.IMAGE
                            },
                        sizeBytes = input.readLong(),
                        sha256 = input.readUTF().also { require(SHA256.matches(it)) },
                        createdAtMs = input.readLong(),
                    )
                require(file.name == "${asset.assetId}$METADATA_SUFFIX") {
                    "Image approval and ID do not match"
                }
                require(asset.sizeBytes in 1..maximumAssetBytes)
                asset
            }
        } catch (failure: EOFException) {
            throw IOException("Incomplete image approval", failure)
        }
    }

    private fun cleanupIncompleteFiles() {
        root.listFiles()?.forEach { file ->
            val orphanAssetId =
                file.name
                    .takeIf { it.endsWith(DATA_SUFFIX) }
                    ?.removeSuffix(DATA_SUFFIX)
                    ?.takeIf(ASSET_ID::matches)
            val removable =
                file.isFile &&
                    (
                        file.name.endsWith(".part") ||
                            (
                                orphanAssetId != null &&
                                    !metadataFile(orphanAssetId).isFile
                            )
                    )
            if (removable) file.delete()
        }
    }

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Private media folder could not be created")
        }
        require(root.isDirectory) { "Private media path is not a folder" }
    }

    private fun sentRoot(): File =
        File(root, SENT_DIRECTORY).canonicalFile.also {
            require(it.parentFile == root) { "Unsicherer Bildhistorienpfad" }
        }

    private fun sentAssetDirectory(assetId: String): File {
        val sentRoot = sentRoot()
        val directory = File(sentRoot, requireAssetId(assetId)).canonicalFile
        require(directory.parentFile == sentRoot) { "Unsicherer Bildhistorienpfad" }
        return directory
    }

    private fun sentMarker(
        assetId: String,
        chatId: String,
    ): File {
        val directory = sentAssetDirectory(assetId)
        val marker = File(directory, chatMarkerName(chatId)).canonicalFile
        require(marker.parentFile == directory) { "Unsicherer Bildhistorienpfad" }
        return marker
    }

    private fun chatMarkerName(chatId: String): String {
        val normalized = chatId.trim()
        require(normalized.length in 1..512 && !normalized.any(Char::isISOControl)) {
            "Invalid chat ID"
        }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun deleteSentAssetDirectory(assetId: String) {
        val directory = sentAssetDirectory(assetId)
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { marker ->
            val safe = marker.canonicalFile.parentFile == directory
            if (safe && marker.isFile && SHA256.matches(marker.name)) marker.delete()
        }
        if (directory.list().isNullOrEmpty()) directory.delete()
    }

    private fun dataFile(assetId: String): File =
        safeFile("${requireAssetId(assetId)}$DATA_SUFFIX")

    private fun metadataFile(assetId: String): File =
        safeFile("${requireAssetId(assetId)}$METADATA_SUFFIX")

    private fun safeFile(name: String): File =
        File(root, name).canonicalFile.also(::requireSafeChild)

    private fun requireSafeChild(file: File) {
        require(file.canonicalFile.parentFile == root) { "Unsicherer Medienpfad" }
    }

    private fun requireAssetId(value: String): String =
        value.trim().also { require(ASSET_ID.matches(it)) { "Invalid image ID" } }

    private fun requirePersonaKey(value: String): String =
        value.trim().lowercase(Locale.ROOT).also {
            require(PERSONA_KEY.matches(it)) { "Invalid persona key" }
        }

    private fun newAssetId(): String {
        repeat(8) {
            val candidate = "img_${randomHex(16)}"
            if (!dataFile(candidate).exists() && !metadataFile(candidate).exists()) return candidate
        }
        throw IOException("No unique image ID available")
    }

    private fun randomHex(byteCount: Int): String =
        ByteArray(byteCount).also(random::nextBytes).toHex()

    private fun sanitizeDisplayName(
        value: String?,
        mimeType: String,
    ): String {
        val extension =
            when (mimeType) {
                "image/jpeg" -> ".jpg"
                "image/png" -> ".png"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                else -> ".img"
            }
        val cleaned =
            value
                .orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot(Char::isISOControl)
                .trim()
                .take(MAX_NAME_CHARS)
        return cleaned.ifBlank { "Approved image$extension" }
    }

    private fun verifyDeclaredMime(
        declared: String?,
        detected: String,
    ) {
        val normalized = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (
            normalized.isNotEmpty() &&
            normalized != "application/octet-stream" &&
            normalized != "image/*" &&
            normalized in SUPPORTED_IMAGE_MIMES &&
            normalized != detected
        ) {
            throw IOException("File type and image content do not match")
        }
        if (normalized.isNotEmpty() && normalized !in GENERIC_MIMES && !normalized.startsWith("image/")) {
            throw IOException("Document is not an image")
        }
    }

    private fun detectImageMime(
        header: ByteArray,
        size: Int,
    ): String? =
        when {
            size >= 3 &&
                header[0].u() == 0xff &&
                header[1].u() == 0xd8 &&
                header[2].u() == 0xff -> "image/jpeg"

            size >= 8 &&
                header.sliceArray(0 until 8).contentEquals(PNG_SIGNATURE) -> "image/png"

            size >= 6 &&
                (
                    header.copyOfRange(0, 6).contentEquals(GIF87_SIGNATURE) ||
                        header.copyOfRange(0, 6).contentEquals(GIF89_SIGNATURE)
                ) -> "image/gif"

            size >= 12 &&
                header.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
                header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) -> "image/webp"

            else -> null
        }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun moveAtomically(
        source: File,
        destination: File,
    ) {
        requireSafeChild(source)
        requireSafeChild(destination)
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun Byte.u(): Int = toInt() and 0xff

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun maximumCount(kind: ApprovedMediaKind): Int =
        when (kind) {
            ApprovedMediaKind.IMAGE -> MAX_ASSETS_PER_PERSONA
            ApprovedMediaKind.CHARACTER_REFERENCE -> MAX_REFERENCES_PER_PERSONA
            ApprovedMediaKind.PROFILE_PICTURE -> MAX_PROFILE_PICTURES_PER_PERSONA
        }

    private fun ApprovedMediaKind.label(): String =
        when (this) {
            ApprovedMediaKind.IMAGE -> "approved"
            ApprovedMediaKind.CHARACTER_REFERENCE -> "character-reference"
            ApprovedMediaKind.PROFILE_PICTURE -> "profile"
        }

    companion object {
        const val DEFAULT_MAX_ASSET_BYTES = 16L * 1024 * 1024
        const val MAX_ASSETS_PER_PERSONA = 250
        const val MAX_REFERENCES_PER_PERSONA = 8

        /**
         * A person has a handful of pictures of themselves, not a gallery. The rotation walks the
         * whole list before it repeats, so at one change every few weeks a dozen is already years.
         */
        const val MAX_PROFILE_PICTURES_PER_PERSONA = 12
        private const val ABSOLUTE_MAX_ASSET_BYTES = 64L * 1024 * 1024
        private const val STREAM_BUFFER_BYTES = 16 * 1024
        private const val MAX_LIST_SIZE = 500
        private const val MAX_NAME_CHARS = 120
        private const val MAX_METADATA_BYTES = 4L * 1024
        private const val METADATA_MAGIC = 0x5741424d
        private const val LEGACY_METADATA_VERSION = 1
        private const val METADATA_VERSION = 2
        private const val DATA_SUFFIX = ".asset"
        private const val METADATA_SUFFIX = ".meta"
        private const val SENT_DIRECTORY = "sent"
        private val ASSET_ID = Regex("^img_[a-f0-9]{32}$")
        private val PERSONA_KEY = Regex("^[a-z0-9_-]{2,40}$")
        private val SHA256 = Regex("^[a-f0-9]{64}$")
        private val SUPPORTED_IMAGE_MIMES =
            setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        private val GENERIC_MIMES =
            SUPPORTED_IMAGE_MIMES + setOf("application/octet-stream", "image/*")
        private val PNG_SIGNATURE =
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
        private val GIF87_SIGNATURE = "GIF87a".toByteArray(Charsets.US_ASCII)
        private val GIF89_SIGNATURE = "GIF89a".toByteArray(Charsets.US_ASCII)
        private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.US_ASCII)
    }
}
