package de.totec.doppel.media

import de.totec.doppel.engine.ProfilePictureOutcome
import de.totec.doppel.engine.ProfilePictureRotation
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.json.JSONObject

/**
 * Changes the account's own profile picture every few weeks.
 *
 * An account whose picture never changes for years is not what a young person's
 * WhatsApp looks like; one that changes it daily is not either. The interval is
 * therefore measured in weeks and rolled once, so a restart cannot turn it into
 * a burst — a picture change is broadcast to every contact, which makes it the
 * one piece of realism that must stay boring.
 *
 * The pictures come from the persona's own [ProfilePictureLibrary] — a starter
 * set ships inside the APK and the owner adds or removes whatever they like —
 * and are handed out strictly in order so the same face does not reappear two
 * changes in a row. The info lines still come from the bundled manifest: they
 * are written for the persona, not chosen per install.
 *
 * Nothing here throws: a missing asset, a broken state file or a bridge that is
 * mid-reconnect must never take a chat turn down with it.
 */
class ProfilePictureRotator(
    private val stateFile: File,
    private val workDir: File,
    private val openAsset: (String) -> InputStream,
    private val library: ProfilePictureLibrary,
    /** Uploads the picture and points the account's avatar at it. */
    private val applyPicture: suspend (File) -> Unit,
    /** Rewrites the "info" line under the name. */
    private val applyAbout: suspend (String) -> Unit,
    private val enabled: () -> Boolean = { true },
    private val intervalMs: LongRange = DEFAULT_INTERVAL,
    private val intervalMsProvider: (() -> LongRange)? = null,
    private val randomFraction: () -> Double = Math::random,
) : ProfilePictureRotation {
    /**
     * Rotates the picture when the persona is due, and catches up a switch that
     * never made it onto the account.
     *
     * The very first call never changes anything: it only schedules the first
     * rotation. Swapping the picture the moment a new build starts would tie the
     * change to an install rather than to a person having a new photo.
     */
    override suspend fun rotateIfDue(
        personaKey: String,
        nowMs: Long,
    ): ProfilePictureOutcome {
        if (!enabled()) return ProfilePictureOutcome.UNCHANGED
        val persona = personaKey.trim().lowercase(Locale.ROOT)
        if (!PERSONA_KEY.matches(persona)) return ProfilePictureOutcome.UNCHANGED

        val state = readState()
        val live = state.optString(ACTIVE_KEY, "")
        // The account is still wearing somebody else's face: a switch the bridge
        // could not take when it was made. Catching up in the next session is
        // what keeps a momentary failure from lasting until the next rotation.
        if (live.isNotEmpty() && live != persona) {
            val retryAt = state.optJSONObject(persona)?.optLong("retryAt", 0L) ?: 0L
            if (nowMs < retryAt) return ProfilePictureOutcome.PENDING
            return switchTo(state, persona, nowMs)
        }

        val pictures = picturesFor(persona)
        if (pictures.isEmpty()) return ProfilePictureOutcome.UNCHANGED

        val entry = state.optJSONObject(persona)
        if (entry == null) {
            writeEntry(state, persona, dueAtMs = nowMs + rollInterval(), picture = "", aboutIndex = -1)
            return ProfilePictureOutcome.UNCHANGED
        }
        if (nowMs < entry.optLong("dueAt", Long.MAX_VALUE)) return ProfilePictureOutcome.UNCHANGED
        if (nowMs < entry.optLong("retryAt", 0L)) return ProfilePictureOutcome.PENDING

        return if (change(state, persona, pictures, entry, nowMs, alwaysWriteAbout = false)) {
            ProfilePictureOutcome.CHANGED
        } else {
            writePendingRetry(state, persona, entry, nowMs)
            ProfilePictureOutcome.PENDING
        }
    }

    /**
     * The persona changed, so the face has to change with it — now, not in weeks.
     *
     * The very first observation only records which persona is live. Applying a
     * picture there would tie the change to a fresh install rather than to a
     * decision, and it would fire on a build that has been running the same
     * persona all along. That recording is why the runtime calls this on every
     * start too: without it the first real switch has nothing to switch *from*
     * and would be swallowed as an install.
     */
    override suspend fun applyPersonaSwitch(
        personaKey: String,
        nowMs: Long,
    ): ProfilePictureOutcome {
        if (!enabled()) return ProfilePictureOutcome.UNCHANGED
        val persona = personaKey.trim().lowercase(Locale.ROOT)
        if (!PERSONA_KEY.matches(persona)) return ProfilePictureOutcome.UNCHANGED

        val state = readState()
        val live = state.optString(ACTIVE_KEY, "")
        if (live == persona) return ProfilePictureOutcome.UNCHANGED
        if (live.isEmpty()) {
            rememberActive(state, persona)
            return ProfilePictureOutcome.UNCHANGED
        }
        // A switch WhatsApp would not take is retried on a timer, exactly like the one in
        // [rotateIfDue] — this used to be the only path without that guard, and it is the one
        // called from `BotEngine.onSettingsChanged`, which fires for *every* setting in the app.
        // So a single refused switch turned every later toggle of any unrelated switch into
        // another full picture upload, for as long as the refusal lasted.
        val retryAt = state.optJSONObject(persona)?.optLong("retryAt", 0L) ?: 0L
        if (nowMs < retryAt) return ProfilePictureOutcome.PENDING
        return switchTo(state, persona, nowMs)
    }

    /** Puts [persona]'s face up now, whatever the weeks-long schedule says. */
    private suspend fun switchTo(
        state: JSONObject,
        persona: String,
        nowMs: Long,
    ): ProfilePictureOutcome {
        val pictures = picturesFor(persona)
        if (pictures.isEmpty()) {
            // Nothing can be put up, so the account keeps the previous face. `#active` is the
            // confirmed WhatsApp picture, not merely the selected persona; changing it here made
            // Settings claim a picture transition that never happened. Re-reading the small local
            // manifest in a later session is preferable to that false state and costs no stanza.
            return ProfilePictureOutcome.NO_PICTURES
        }
        val entry = state.optJSONObject(persona) ?: JSONObject()
        // A switched persona also gets its info line, regardless of where the
        // every-other-change rhythm happened to stand: the old text belonged to
        // someone else.
        return if (change(state, persona, pictures, entry, nowMs, alwaysWriteAbout = true)) {
            ProfilePictureOutcome.CHANGED
        } else {
            // The persona on the account is deliberately left as it was, so the
            // next session or the next start tries this switch again rather than
            // recording a picture that never went up.
            writePendingRetry(state, persona, entry, nowMs)
            ProfilePictureOutcome.PENDING
        }
    }

    /** Applies the next picture of [persona] and records the new schedule. */
    private suspend fun change(
        state: JSONObject,
        persona: String,
        pictures: List<String>,
        entry: JSONObject,
        nowMs: Long,
        alwaysWriteAbout: Boolean,
    ): Boolean {
        // By id, not by position: a deleted or newly added picture renumbers the list, and an index
        // into it would then either repeat the face that is already up or skip one at random.
        val next = (pictures.indexOf(entry.optString("picture", "")) + 1).mod(pictures.size)
        val staged = runCatching { stage(persona, pictures[next]) }.getOrNull() ?: return false
        try {
            applyPicture(staged)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // The bridge may simply be reconnecting. Leave the due date where it
            // is so the next self-session tries again instead of skipping weeks.
            return false
        } finally {
            staged.delete()
        }
        // Only every other change touches the info line as well. People rarely
        // rewrite both at once, and a text that moves in lockstep with the
        // picture is its own pattern.
        var aboutIndex = entry.optInt("aboutIndex", -1)
        if (alwaysWriteAbout || next % 2 == 0) {
            val texts = runCatching { aboutFor(persona) }.getOrDefault(emptyList())
            if (texts.isNotEmpty()) {
                val nextAbout = (aboutIndex + 1).mod(texts.size)
                // The picture is already live; a failing info line must not undo it.
                if (runCatching { applyAbout(texts[nextAbout]) }.isSuccess) aboutIndex = nextAbout
            }
        }
        // Whatever moved the picture, this persona is the one now on the account.
        state.put(ACTIVE_KEY, persona)
        writeEntry(
            state,
            persona,
            dueAtMs = nowMs + rollInterval(),
            picture = pictures[next],
            aboutIndex = aboutIndex,
        )
        return true
    }

    private fun rememberActive(
        state: JSONObject,
        persona: String,
    ) {
        runCatching {
            state.put(ACTIVE_KEY, persona)
            stateFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            stateFile.writeText(state.toString())
        }
    }

    /** Copies one approved picture out of the catalogue so it can be uploaded as a file. */
    private fun stage(
        persona: String,
        pictureId: String,
    ): File {
        if (!workDir.isDirectory) workDir.mkdirs()
        val destination = File(workDir, "profile-$persona.jpg")
        library.stage(persona, pictureId, destination)
        return destination
    }

    /** Never throws: an unreadable catalogue reads as "no pictures". */
    private fun picturesFor(persona: String): List<String> =
        runCatching { library.list(persona) }.getOrDefault(emptyList())

    private fun aboutFor(persona: String): List<String> {
        val texts = groupFor(persona)?.optJSONArray("about") ?: return emptyList()
        return buildList {
            for (index in 0 until texts.length()) {
                val text = texts.optString(index).trim()
                if (text.isNotEmpty() && text.length <= MAX_ABOUT_CHARS) add(text)
            }
        }
    }

    private fun groupFor(persona: String): JSONObject? {
        return manifestGroup(openAsset, persona)
    }

    private fun rollInterval(): Long =
        (intervalMsProvider?.invoke() ?: intervalMs).let { range ->
            require(range.first > 0L && range.last >= range.first) { "Invalid profile-picture interval" }
            range.first +
                ((range.last - range.first) * randomFraction().coerceIn(0.0, 1.0)).toLong()
        }

    private fun readState(): JSONObject =
        runCatching {
            if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) return@runCatching JSONObject()
            JSONObject(stateFile.readText())
        }.getOrDefault(JSONObject())

    private fun writeEntry(
        state: JSONObject,
        persona: String,
        dueAtMs: Long,
        picture: String,
        aboutIndex: Int,
        retryAtMs: Long? = null,
    ) {
        state.put(
            persona,
            JSONObject()
                .put("dueAt", dueAtMs)
                .put("picture", picture)
                .put("aboutIndex", aboutIndex)
                .apply { retryAtMs?.let { put("retryAt", it) } },
        )
        val parent = stateFile.parentFile ?: error("Profile state has no parent directory")
        check(parent.isDirectory || parent.mkdirs()) { "Profile state directory could not be created" }
        val staging = File(parent, ".${stateFile.name}.new")
        try {
            FileOutputStream(staging).use { output ->
                output.write(state.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    staging.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staging.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            staging.delete()
        }
    }

    private fun writePendingRetry(
        state: JSONObject,
        persona: String,
        entry: JSONObject,
        nowMs: Long,
    ) {
        writeEntry(
            state = state,
            persona = persona,
            dueAtMs = entry.optLong("dueAt", nowMs),
            picture = entry.optString("picture", ""),
            aboutIndex = entry.optInt("aboutIndex", -1),
            retryAtMs = nowMs + RETRY_BACKOFF_MS,
        )
    }

    companion object {
        const val STATE_FILE_NAME = "profile-picture-rotation.json"
        const val RETRY_BACKOFF_MS = 15L * 60L * 1_000L
        const val ASSET_ROOT = "persona-profile"
        const val MANIFEST_FILE = "manifest.json"
        private const val MANIFEST_VERSION = 1
        private const val MAX_MANIFEST_CHARS = 64 * 1024
        private const val MAX_STATE_BYTES = 64L * 1024

        /** WhatsApp's own limit for the info line. */
        private const val MAX_ABOUT_CHARS = 139

        /**
         * Which persona the picture on the account currently belongs to. The '#'
         * keeps it out of reach of [PERSONA_KEY], so no persona can ever occupy
         * this slot in the state file.
         */
        private const val ACTIVE_KEY = "#active"
        private val PERSONA_KEY = Regex("^[a-z0-9_-]{2,40}$")

        /** Three to six weeks. Deliberately slow; see the class comment. */
        val DEFAULT_INTERVAL: LongRange = 21L * 24 * 60 * 60 * 1000..45L * 24 * 60 * 60 * 1000

        /**
         * The persona and picture whose WhatsApp mutation succeeded. No network read is ever
         * used; an uninitialised or pending switch deliberately returns null.
         */
        fun confirmedPicture(stateFile: File): Pair<String, String>? = runCatching {
            if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) return@runCatching null
            val state = JSONObject(stateFile.readText())
            val active = state.optString(ACTIVE_KEY).trim().lowercase(Locale.ROOT)
            if (!PERSONA_KEY.matches(active)) return@runCatching null
            val picture = state.optJSONObject(active)?.optString("picture").orEmpty()
            if (picture.isEmpty()) return@runCatching null
            active to picture
        }.getOrNull()

        /** Where that picture lives on disk, or null once it has been deleted from the catalogue. */
        fun confirmedPicturePath(
            stateFile: File,
            library: ProfilePictureLibrary,
        ): String? =
            confirmedPicture(stateFile)?.let { (persona, picture) -> library.path(persona, picture) }

        private fun manifestGroup(
            openAsset: (String) -> InputStream,
            persona: String,
        ): JSONObject? {
            val text = openAsset("$ASSET_ROOT/$MANIFEST_FILE").use { it.readBytes().decodeToString() }
            require(text.length <= MAX_MANIFEST_CHARS) { "Profile picture manifest is too large" }
            val root = JSONObject(text)
            require(root.optInt("version") == MANIFEST_VERSION) { "Unknown profile picture manifest" }
            val personas = root.optJSONArray("personas") ?: return null
            for (index in 0 until personas.length()) {
                val group = personas.optJSONObject(index) ?: continue
                if (group.optString("persona").trim().lowercase(Locale.ROOT) == persona) return group
            }
            return null
        }
    }
}
