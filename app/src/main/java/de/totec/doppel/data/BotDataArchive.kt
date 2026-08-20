package de.totec.doppel.data

import android.content.Context
import de.totec.doppel.data.db.BotDbSchema
import de.totec.doppel.data.db.BotRepository
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * The whole bot in one zip: the SQLite file and the approved-image store.
 *
 * A logical dump (JSON per table) was the alternative and was rejected: it silently drops whatever
 * the exporter forgot, and every schema change makes it wrong again. Copying the database keeps the
 * backup exhaustive by construction — settings, personas, memory, history, access lists and the
 * outbox all live in that one file — at the cost of only restoring into a build whose schema is not
 * older than the one that wrote it, which [read] enforces.
 *
 * The import deliberately does not merge. Half a bot from one backup and half from the live install
 * is a state nobody can reason about, so a restore replaces both stores wholesale.
 */
object BotDataArchive {
    const val MIME_TYPE = "application/zip"

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATABASE_ENTRY = "database/${BotDbSchema.DATABASE_NAME}"
    private const val MEDIA_PREFIX = "approved-media/"
    private const val MEDIA_DIRECTORY = "approved-media/v1"
    private const val FORMAT = 1
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L

    /**
     * A per-entry cap says nothing about an archive as a whole: fifty entries just under the entry
     * limit, or a hundred thousand tiny ones, each pass the check and together fill the cache
     * partition before anything is committed. Extraction is therefore also bounded in total and in
     * count. Both sit far above a real backup — the database plus every approved media file — so
     * the only archives they reject are ones no export of this app produced.
     */
    internal const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
    internal const val MAX_ENTRIES = 10_000

    /** SQLite's file magic, so a wrong file is refused before anything is overwritten. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    data class Summary(
        val databaseBytes: Long,
        val mediaFiles: Int,
    )

    class UnreadableArchiveException(
        message: String,
    ) : Exception(message)

    fun defaultFileName(nowMillis: Long): String {
        val stamp =
            java.time.Instant
                .ofEpochMilli(nowMillis)
                .toString()
                .take(19)
                .replace(':', '-')
        return "whatsapp-bot-backup-$stamp.zip"
    }

    /**
     * Folds the write-ahead log into the main database file first: without the checkpoint the copy
     * is a snapshot from before the most recent writes, which is the classic way a backup silently
     * loses the last hour.
     */
    fun export(
        context: Context,
        repository: BotRepository,
        output: OutputStream,
    ): Summary {
        repository.checkpointForBackup()
        val database = context.getDatabasePath(BotDbSchema.DATABASE_NAME)
        val media = File(context.filesDir, MEDIA_DIRECTORY)
        var mediaFiles = 0

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(
                JSONObject()
                    .put("format", FORMAT)
                    .put("schemaVersion", BotDbSchema.VERSION)
                    .put("createdAt", System.currentTimeMillis())
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
            database.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            media
                .walkTopDown()
                .filter(File::isFile)
                .forEach { file ->
                    val relative = file.relativeTo(media).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(MEDIA_PREFIX + relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    mediaFiles += 1
                }
        }
        return Summary(databaseBytes = database.length(), mediaFiles = mediaFiles)
    }

    /**
     * Unpacks into a staging directory and validates it there. Nothing the live install owns is
     * touched until the archive is known to be complete, so a truncated download cannot leave the
     * app with half a database and no way back.
     */
    fun read(
        context: Context,
        input: InputStream,
    ): Staged {
        val staging = File(context.cacheDir, "restore-staging").also { it.deleteRecursively() }
        val mediaRoot = File(staging, "media")
        var database: File? = null
        var mediaFiles = 0
        var manifest: JSONObject? = null
        var manifestSeen = false
        var databaseSeen = false

        val budget = ExtractionBudget()

        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    // Counted before the name is examined, so an archive padded with entries this
                    // restore ignores is bounded too rather than only the ones it extracts.
                    budget.consumeEntry()
                    when {
                        entry.name == MANIFEST_ENTRY -> {
                            if (manifestSeen) {
                                throw UnreadableArchiveException("The archive contains two manifests.")
                            }
                            manifestSeen = true
                            manifest =
                                runCatching { JSONObject(zip.readBoundedText()) }
                                    .getOrElse { throw UnreadableArchiveException("The manifest is unreadable.") }
                        }

                        entry.name == DATABASE_ENTRY -> {
                            if (databaseSeen) {
                                throw UnreadableArchiveException("The archive contains two bot databases.")
                            }
                            databaseSeen = true
                            database = File(staging, "database").also { zip.copyBoundedTo(it, budget) }
                        }

                        entry.name.startsWith(MEDIA_PREFIX) -> {
                            val target = mediaRoot.resolveInside(entry.name.removePrefix(MEDIA_PREFIX))
                            zip.copyBoundedTo(target, budget)
                            mediaFiles += 1
                        }
                    }
                    zip.closeEntry()
                }
            }

            val databaseFile =
                database ?: throw UnreadableArchiveException("The archive contains no bot database.")
            val format = manifest?.optInt("format") ?: 0
            if (format != FORMAT) {
                throw UnreadableArchiveException("This is not a backup this version can read.")
            }
            val schemaVersion = manifest?.optInt("schemaVersion") ?: 0
            if (schemaVersion > BotDbSchema.VERSION) {
                throw UnreadableArchiveException(
                    "The backup was written by a newer app version. Update first, then import.",
                )
            }
            if (!databaseFile.startsWithSqliteMagic()) {
                throw UnreadableArchiveException("The bot database in the archive is damaged.")
            }
            return Staged(
                root = staging,
                database = databaseFile,
                mediaRoot = mediaRoot,
                summary = Summary(databaseBytes = databaseFile.length(), mediaFiles = mediaFiles),
            )
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    /**
     * The point of no return, kept as short as possible: the caller has already closed the database,
     * and the process is expected to end right afterwards so nothing keeps reading the old files.
     */
    fun commit(
        context: Context,
        staged: Staged,
    ): Summary {
        val target = context.getDatabasePath(BotDbSchema.DATABASE_NAME)
        val media = File(context.filesDir, MEDIA_DIRECTORY)
        val databaseParent = checkNotNull(target.parentFile)
        check(databaseParent.isDirectory || databaseParent.mkdirs()) {
            "The database directory could not be created."
        }
        val incomingDatabase = File(databaseParent, ".${target.name}.restore-new")
        val previousDatabase = File(databaseParent, ".${target.name}.restore-old")
        val mediaParent = checkNotNull(media.parentFile)
        check(mediaParent.isDirectory || mediaParent.mkdirs()) {
            "The media directory could not be created."
        }
        val incomingMedia = File(mediaParent, ".${media.name}.restore-new")
        val previousMedia = File(mediaParent, ".${media.name}.restore-old")

        var databaseMovedAside = false
        var databaseInstalled = false
        var mediaMovedAside = false
        var mediaInstalled = false
        try {
            incomingDatabase.delete()
            previousDatabase.delete()
            incomingMedia.deleteRecursively()
            previousMedia.deleteRecursively()
            staged.database.copyDurablyTo(incomingDatabase)
            if (staged.mediaRoot.exists()) {
                check(staged.mediaRoot.copyRecursively(incomingMedia, overwrite = true)) {
                    "The restored media could not be staged."
                }
            } else {
                check(incomingMedia.mkdirs()) { "The restored media directory could not be staged." }
            }

            if (target.exists()) {
                target.moveReplacing(previousDatabase)
                databaseMovedAside = true
            }
            incomingDatabase.moveReplacing(target)
            databaseInstalled = true

            if (media.exists()) {
                media.moveReplacing(previousMedia)
                mediaMovedAside = true
            }
            incomingMedia.moveReplacing(media)
            mediaInstalled = true

            // These journals belong to the database that was moved aside. Left at the canonical
            // path they would be replayed over the restored database on the next process start.
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            previousDatabase.delete()
            previousMedia.deleteRecursively()
        } catch (failure: Throwable) {
            if (mediaInstalled) media.deleteRecursively()
            if (mediaMovedAside && previousMedia.exists()) previousMedia.moveReplacing(media)
            if (databaseInstalled) target.delete()
            if (databaseMovedAside && previousDatabase.exists()) {
                previousDatabase.moveReplacing(target)
            }
            throw failure
        } finally {
            incomingDatabase.delete()
            incomingMedia.deleteRecursively()
            staged.root.deleteRecursively()
        }
        return staged.summary
    }

    data class Staged(
        val root: File,
        val database: File,
        val mediaRoot: File,
        val summary: Summary,
    )

    private fun File.startsWithSqliteMagic(): Boolean =
        inputStream().use { stream ->
            val header = ByteArray(SQLITE_MAGIC.size)
            stream.read(header) == header.size && header.contentEquals(SQLITE_MAGIC)
        }

    private fun File.copyDurablyTo(target: File) {
        inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun File.moveReplacing(target: File) {
        try {
            Files.move(
                toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Zip-slip guard: an entry named `../../databases/x` must not escape the staging directory. */
    private fun File.resolveInside(relative: String): File {
        val target = File(this, relative).canonicalFile
        val root = canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw UnreadableArchiveException("The archive contains an unsafe path.")
        }
        return target
    }

    /**
     * Spent down across the whole extraction rather than reset per entry, and spent while the bytes
     * are still being written, so an over-large archive is refused with the disk it has already
     * used rather than after it has finished using all of it. The declared sizes in the zip are
     * never consulted: they are attacker-controlled and a compressed bomb declares whatever it
     * likes.
     */
    // Internal rather than private so the limits can be tested directly. Extraction itself needs a
    // Context for the staging directory and this module's unit tests do not run under one, so the
    // guard would otherwise be reachable only through a path no test can take.
    internal class ExtractionBudget {
        internal var remainingBytes = MAX_TOTAL_BYTES
            private set
        internal var remainingEntries = MAX_ENTRIES
            private set

        fun consumeEntry() {
            remainingEntries -= 1
            if (remainingEntries < 0) {
                throw UnreadableArchiveException("The archive contains implausibly many files.")
            }
        }

        fun consumeBytes(count: Long) {
            remainingBytes -= count
            if (remainingBytes < 0) {
                throw UnreadableArchiveException("The archive is implausibly large.")
            }
        }
    }

    private fun ZipInputStream.copyBoundedTo(
        target: File,
        budget: ExtractionBudget,
    ) {
        target.parentFile?.mkdirs()
        var written = 0L
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = read(buffer)
                if (read <= 0) break
                written += read
                if (written > MAX_ENTRY_BYTES) {
                    throw UnreadableArchiveException("The archive is implausibly large.")
                }
                budget.consumeBytes(read.toLong())
                out.write(buffer, 0, read)
            }
        }
    }

    private fun ZipInputStream.readBoundedText(): String {
        val maximum = 64 * 1024
        val bytes = ByteArray(maximum + 1)
        var total = 0
        while (total < bytes.size) {
            val read = read(bytes, total, bytes.size - total)
            if (read < 0) break
            if (read == 0) continue
            total += read
        }
        if (total > maximum) {
            throw UnreadableArchiveException("The manifest is implausibly large.")
        }
        return bytes.copyOf(total).toString(Charsets.UTF_8)
    }
}
