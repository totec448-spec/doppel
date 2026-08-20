package de.totec.doppel.media

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/**
 * Starter media that ships inside the APK, so a built-in persona can send a picture or use an
 * identity reference before the owner has imported anything by hand.
 *
 * Bundled files take exactly the same route as a hand-picked document: they are
 * copied through [ApprovedMediaAssetStore.importImage], which sniffs the real
 * image type, hashes the bytes and files them under the persona. Sendable images expose only opaque
 * IDs and keep their once-per-chat ledger. Character references remain a separate kind and are
 * opened only as bounded private bytes for an explicitly character-bearing generation request.
 *
 * A ledger next to the store records every bundled file that was offered once.
 * That is what makes deletion stick: an image the owner removes is never quietly
 * restored on the next start. Sendable images and references use separate ledger files.
 */
class PreinstalledPersonaImages(
    private val store: ApprovedMediaAssetStore,
    private val ledger: File,
    private val open: (String) -> InputStream,
    private val assetRoot: String = ASSET_ROOT,
    private val kind: ApprovedMediaKind = ApprovedMediaKind.IMAGE,
    /**
     * SHA-256 digests of bundled files that a later build replaced with a smaller re-encode. They
     * are removed from the store before seeding, because the replacement arrives as a new asset and
     * both would otherwise be held — and, for references, sent — side by side.
     */
    private val superseded: Set<String> = emptySet(),
) {
    /**
     * Copies everything the manifest lists and the ledger has not seen yet.
     * Returns how many assets were newly approved; zero on every normal start.
     * Never throws: a broken manifest or a failed copy must not take the app down.
     */
    fun seed(): Outcome {
        val retired =
            runCatching { store.deleteByDigest(superseded, kind) }.getOrDefault(0)
        val entries =
            runCatching(::readManifest)
                .getOrElse { return Outcome(retired = retired, manifestError = it.javaClass.simpleName) }
        val stored = countStored(entries)
        // The ledger only ever describes what is in the store. When the store holds nothing at all
        // for these personas, every line in it is a claim about files that do not exist, and
        // honouring it means the bundled set stays invisible forever — which is exactly how an
        // install ends up reporting "imported 0 of 20 · 0 in store" on every single start. An empty
        // store is not a decision, so the stale ledger is dropped and the whole set is offered
        // again. Deleting individual pictures still sticks: as long as one bundled file survives,
        // the ledger is authoritative and the rest stay gone.
        if (stored == 0 && !isLedgerEmpty()) resetLedger()
        val done = readLedger()
        // The seal names the manifest it sealed. A bare "#complete" made the first successful start
        // the last one that ever read the manifest, so every file added to the APK afterwards was
        // invisible for the life of the install — which is what an empty picture list on a fresh
        // build looks like from the outside.
        val seal = "$COMPLETE_MARKER:${fingerprint(entries)}"
        if (seal in done) {
            return Outcome(retired = retired, sealed = true, offered = entries.size, stored = stored)
        }
        var imported = 0
        var failed = 0
        for (entry in entries) {
            if (entry.ledgerKey in done) continue
            val created =
                runCatching { importEntry(entry) }
                    .onFailure { failed++ }
                    .getOrNull() ?: continue
            appendToLedger(entry.ledgerKey)
            if (created) imported++
        }
        // Only a fully offered manifest may be sealed; a transient IO error has to
        // be retried on the next start instead of being remembered as done.
        if (failed == 0) appendToLedger(seal)
        return Outcome(
            imported = imported,
            failed = failed,
            retired = retired,
            offered = entries.size,
            stored = countStored(entries),
        )
    }

    /**
     * What the store actually holds for the personas this manifest covers. The ledger only records
     * what was *offered*; without this number a seeded install and an empty gallery write the same
     * log line, which is precisely the ambiguity that made the missing starter images unreadable.
     */
    private fun countStored(entries: List<Entry>): Int =
        entries.asSequence()
            .map(Entry::persona)
            .distinct()
            .sumOf { persona ->
                runCatching { store.list(persona, limit = 250, kind = kind).size }.getOrDefault(0)
            }

    /**
     * What one seeding pass did. It used to return a bare count that nobody read, so a manifest
     * that failed to parse, an asset missing from the APK and a store that was already full were
     * all the same silent zero.
     */
    data class Outcome(
        val imported: Int = 0,
        val failed: Int = 0,
        /** Copies of bundled files that a later build replaced, removed from the store. */
        val retired: Int = 0,
        val offered: Int = 0,
        val stored: Int = 0,
        val sealed: Boolean = false,
        val manifestError: String? = null,
    ) {
        /** Nothing is wrong only when the manifest read, nothing failed and the store has files. */
        val healthy: Boolean
            get() = manifestError == null && failed == 0 && stored > 0

        val summary: String
            get() = when {
                manifestError != null -> "manifest unreadable ($manifestError)"
                sealed -> "already seeded · $offered in manifest · $stored in store$retiredSuffix"
                else ->
                    "imported $imported of $offered · $stored in store" +
                        (if (failed > 0) " · $failed failed" else "") +
                        retiredSuffix
            }

        private val retiredSuffix: String
            get() = if (retired > 0) " · $retired replaced" else ""
    }

    /** Stable over the manifest's contents, so any edit to the bundled set breaks the seal. */
    private fun fingerprint(entries: List<Entry>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(entries.joinToString("\n") { it.ledgerKey }.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private fun importEntry(entry: Entry): Boolean =
        open("$assetRoot/${entry.persona}/${entry.file}").use { source ->
            store.importImage(
                personaKey = entry.persona,
                displayName = entry.displayName,
                declaredMimeType = null,
                source = source,
                kind = kind,
            ).created
        }

    private fun readManifest(): List<Entry> {
        val text = open("$assetRoot/$MANIFEST_FILE").use { it.readBytes().decodeToString() }
        require(text.length <= MAX_MANIFEST_CHARS) { "Image manifest is too large" }
        val root = JSONObject(text)
        require(root.optInt("version") == MANIFEST_VERSION) { "Unbekanntes Bildmanifest" }
        val personas = root.optJSONArray("personas") ?: return emptyList()
        val entries = mutableListOf<Entry>()
        for (personaIndex in 0 until personas.length()) {
            val group = personas.optJSONObject(personaIndex) ?: continue
            val persona = group.optString("persona").trim().lowercase(Locale.ROOT)
            if (!PERSONA_KEY.matches(persona)) continue
            val images = group.optJSONArray("images") ?: continue
            for (imageIndex in 0 until images.length()) {
                val image = images.optJSONObject(imageIndex) ?: continue
                val file = image.optString("file").trim()
                if (!ASSET_FILE.matches(file)) continue
                entries +=
                    Entry(
                        persona = persona,
                        file = file,
                        displayName = image.optString("name").trim().ifBlank { file },
                    )
                if (entries.size >= MAX_MANIFEST_ENTRIES) return entries
            }
        }
        return entries
    }

    private fun isLedgerEmpty(): Boolean = runCatching { !ledger.isFile }.getOrDefault(true)

    /** The store this ledger describes is gone, so the ledger describes nothing. */
    private fun resetLedger() {
        runCatching { ledger.delete() }
    }

    private fun readLedger(): Set<String> =
        runCatching {
            if (!ledger.isFile || ledger.length() > MAX_LEDGER_BYTES) return@runCatching emptySet()
            ledger.readLines().mapNotNullTo(mutableSetOf()) { it.trim().takeIf(String::isNotEmpty) }
        }.getOrDefault(emptySet())

    private fun appendToLedger(key: String) {
        runCatching {
            ledger.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException(it.path) }
            ledger.appendText("$key\n")
        }
    }

    private data class Entry(
        val persona: String,
        val file: String,
        val displayName: String,
    ) {
        val ledgerKey: String get() = "$persona/$file"
    }

    companion object {
        const val ASSET_ROOT = "persona-images"
        const val MANIFEST_FILE = "manifest.json"
        private const val MANIFEST_VERSION = 1
        private const val COMPLETE_MARKER = "#complete"
        private const val MAX_MANIFEST_CHARS = 256 * 1024
        private const val MAX_LEDGER_BYTES = 64L * 1024
        private const val MAX_MANIFEST_ENTRIES = 512
        private val PERSONA_KEY = Regex("^[a-z0-9_-]{2,40}$")
        private val ASSET_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
    }
}
