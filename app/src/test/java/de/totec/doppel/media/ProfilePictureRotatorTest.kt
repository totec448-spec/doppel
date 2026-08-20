package de.totec.doppel.media

import de.totec.doppel.engine.ProfilePictureOutcome.CHANGED
import de.totec.doppel.engine.ProfilePictureOutcome.NO_PICTURES
import de.totec.doppel.engine.ProfilePictureOutcome.PENDING
import de.totec.doppel.engine.ProfilePictureOutcome.UNCHANGED
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A profile picture change is broadcast to every contact, so the interesting
 * property here is restraint: it happens weeks apart, never on a fresh start,
 * and a bridge that is momentarily unavailable must not cost the account weeks
 * of waiting.
 *
 * The faces come from a catalogue the owner edits, so the second property is that
 * editing it mid-rotation never repeats or skips a face.
 */
class ProfilePictureRotatorTest {
    private val directory = Files.createTempDirectory("profile-picture-test").toFile()
    private val state = File(directory, "rotation.json")
    private val work = File(directory, "work")
    private val applied = mutableListOf<ByteArray>()
    private val about = mutableListOf<String>()
    private var failNextApply = false

    /** Stands in for the approved-media catalogue: ids in a stable order, bytes behind them. */
    private class FakeLibrary(
        val pictures: MutableMap<String, MutableList<Pair<String, String>>>,
    ) : ProfilePictureLibrary {
        override fun list(personaKey: String): List<String> =
            pictures[personaKey].orEmpty().map { it.first }

        override fun stage(
            personaKey: String,
            pictureId: String,
            destination: File,
        ) {
            val content =
                pictures[personaKey].orEmpty().firstOrNull { it.first == pictureId }?.second
                    ?: throw FileNotFoundException(pictureId)
            destination.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            destination.writeText(content)
        }

        override fun path(
            personaKey: String,
            pictureId: String,
        ): String? =
            pictures[personaKey]
                .orEmpty()
                .firstOrNull { it.first == pictureId }
                ?.let { "/catalogue/$personaKey/${it.first}" }
    }

    private val library =
        FakeLibrary(
            mutableMapOf(
                "female" to
                    mutableListOf("img_a" to "picture-a", "img_b" to "picture-b"),
                "male" to mutableListOf("img_m" to "picture-m"),
            ),
        )

    /** Only the info lines still come from the bundled manifest. */
    private val assets =
        mapOf(
            "persona-profile/manifest.json" to
                """
                {"version":1,"personas":[{"persona":"female","about":["info-one","info-two"]},
                  {"persona":"male","about":["info-male"]}]}
                """.trimIndent(),
        )

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun open(path: String): InputStream =
        ByteArrayInputStream(
            assets[path]?.toByteArray() ?: throw FileNotFoundException(path),
        )

    private fun rotator(
        fraction: Double = 0.0,
        enabled: () -> Boolean = { true },
        intervalProvider: (() -> LongRange)? = null,
    ) =
        ProfilePictureRotator(
            stateFile = state,
            workDir = work,
            openAsset = ::open,
            library = library,
            applyPicture = { picture ->
                if (failNextApply) {
                    failNextApply = false
                    throw IllegalStateException("bridge is reconnecting")
                }
                applied += picture.readBytes()
            },
            applyAbout = { text -> about += text },
            enabled = enabled,
            intervalMsProvider = intervalProvider,
            randomFraction = { fraction },
        )

    private val interval = ProfilePictureRotator.DEFAULT_INTERVAL.first

    @Test
    fun `the first start only schedules and never changes the picture`() =
        runBlocking {
            assertEquals(UNCHANGED, rotator().rotateIfDue("female", nowMs = 1_000))
            assertTrue(applied.isEmpty())
        }

    @Test
    fun `nothing happens again until the interval has passed`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)

            assertEquals(UNCHANGED, rotator.rotateIfDue("female", nowMs = interval - 1))
            assertEquals(CHANGED, rotator.rotateIfDue("female", nowMs = interval))
            assertEquals(listOf("picture-a"), applied.map { it.decodeToString() })
        }

    @Test
    fun `disabled setting performs no profile mutation and writes no schedule`() =
        runBlocking {
            val disabled = rotator(enabled = { false })

            assertEquals(UNCHANGED, disabled.applyPersonaSwitch("female", nowMs = 0))
            assertEquals(UNCHANGED, disabled.rotateIfDue("female", nowMs = Long.MAX_VALUE))
            assertTrue(applied.isEmpty())
            assertFalse(state.exists())
        }

    @Test
    fun `configured interval controls the next same-persona change`() =
        runBlocking {
            val sevenDays = 7L * 24L * 60L * 60L * 1_000L
            val configured = rotator(intervalProvider = { sevenDays..sevenDays })
            configured.rotateIfDue("female", nowMs = 0)

            assertEquals(UNCHANGED, configured.rotateIfDue("female", nowMs = sevenDays - 1L))
            assertEquals(CHANGED, configured.rotateIfDue("female", nowMs = sevenDays))
        }

    @Test
    fun `settings projects only the picture that WhatsApp accepted`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            assertNull(ProfilePictureRotator.confirmedPicturePath(state, library))

            failNextApply = true
            assertEquals(PENDING, rotator.rotateIfDue("female", nowMs = interval))
            assertNull(ProfilePictureRotator.confirmedPicturePath(state, library))

            assertEquals(
                CHANGED,
                rotator.rotateIfDue("female", nowMs = interval + ProfilePictureRotator.RETRY_BACKOFF_MS),
            )
            assertEquals(
                "/catalogue/female/img_a",
                ProfilePictureRotator.confirmedPicturePath(state, library),
            )
        }

    @Test
    fun `a deleted live picture stops being projected instead of being invented`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            rotator.rotateIfDue("female", nowMs = interval)
            assertEquals(
                "/catalogue/female/img_a",
                ProfilePictureRotator.confirmedPicturePath(state, library),
            )

            // The owner removes the picture that is currently on the account. WhatsApp still shows
            // it — but nothing local can point at it any more, and guessing another one would name
            // a face the account is not wearing.
            library.pictures.getValue("female").removeAt(0)
            assertNull(ProfilePictureRotator.confirmedPicturePath(state, library))
        }

    @Test
    fun `the same face never comes up twice in a row`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            rotator.rotateIfDue("female", nowMs = interval)
            rotator.rotateIfDue("female", nowMs = 2 * interval)
            // Two pictures in the set, so the third change wraps back to the first.
            rotator.rotateIfDue("female", nowMs = 3 * interval)

            assertEquals(
                listOf("picture-a", "picture-b", "picture-a"),
                applied.map { it.decodeToString() },
            )
        }

    @Test
    fun `a picture added ahead of the live one neither repeats nor skips a face`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            rotator.rotateIfDue("female", nowMs = interval)
            assertEquals(listOf("picture-a"), applied.map { it.decodeToString() })

            // An upload that sorts before the live picture used to shift every position by one,
            // which an index-based rotation would have read as "the next one is the one already up".
            library.pictures.getValue("female").add(0, "img_new" to "picture-new")
            rotator.rotateIfDue("female", nowMs = 2 * interval)

            assertEquals(
                listOf("picture-a", "picture-b"),
                applied.map { it.decodeToString() },
            )
        }

    @Test
    fun `deleting the live picture makes the next change start over, not fail`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            rotator.rotateIfDue("female", nowMs = interval)

            library.pictures.getValue("female").removeAt(0)
            assertEquals(CHANGED, rotator.rotateIfDue("female", nowMs = 2 * interval))
            assertEquals(
                listOf("picture-a", "picture-b"),
                applied.map { it.decodeToString() },
            )
        }

    @Test
    fun `an emptied library leaves the account alone`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            rotator.rotateIfDue("female", nowMs = interval)

            library.pictures.getValue("female").clear()
            assertEquals(UNCHANGED, rotator.rotateIfDue("female", nowMs = 2 * interval))
            assertEquals(listOf("picture-a"), applied.map { it.decodeToString() })
        }

    @Test
    fun `a restart keeps the schedule instead of changing the picture again`() =
        runBlocking {
            rotator().rotateIfDue("female", nowMs = 0)
            rotator().rotateIfDue("female", nowMs = interval)
            // A new instance reads the same state file: still not due.
            assertEquals(UNCHANGED, rotator().rotateIfDue("female", nowMs = interval + 1))
            assertEquals(1, applied.size)
        }

    @Test
    fun `a bridge that is busy is retried after a bounded cooldown, not every reconnect`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            failNextApply = true

            assertEquals(PENDING, rotator.rotateIfDue("female", nowMs = interval))
            assertTrue(applied.isEmpty())
            assertEquals(PENDING, rotator.rotateIfDue("female", nowMs = interval + 1))
            assertEquals(
                CHANGED,
                rotator.rotateIfDue(
                    "female",
                    nowMs = interval + ProfilePictureRotator.RETRY_BACKOFF_MS,
                ),
            )
            assertEquals(listOf("picture-a"), applied.map { it.decodeToString() })
        }

    @Test
    fun `the info line changes with every second picture, not every one`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            // Picture index 0 — a person updating their profile writes something new.
            rotator.rotateIfDue("female", nowMs = interval)
            assertEquals(listOf("info-one"), about)
            // Index 1 — the picture moves on, the info line stays put.
            rotator.rotateIfDue("female", nowMs = 2 * interval)
            assertEquals(listOf("info-one"), about)
            rotator.rotateIfDue("female", nowMs = 3 * interval)
            assertEquals(listOf("info-one", "info-two"), about)
        }

    @Test
    fun `the persona already on the account is only recorded, never re-applied`() =
        runBlocking {
            val rotator = rotator()
            // Nothing is known yet: this is an install, not a decision.
            assertEquals(UNCHANGED, rotator.applyPersonaSwitch("female", nowMs = 0))
            assertTrue(applied.isEmpty())
            // And the same persona again is not a switch at all.
            assertEquals(UNCHANGED, rotator.applyPersonaSwitch("female", nowMs = 1))
            assertTrue(applied.isEmpty())
        }

    @Test
    fun `the first switch after a start is a switch, not an install`() =
        runBlocking {
            // What the runtime does on every start: report the persona that is
            // configured. Nothing is known yet, so this is the install.
            assertEquals(UNCHANGED, rotator().applyPersonaSwitch("female", nowMs = 0))

            // The owner now picks a different persona — the very first switch
            // they ever make, and the one that used to be swallowed.
            assertEquals(CHANGED, rotator().applyPersonaSwitch("male", nowMs = 1))
            assertEquals(listOf("picture-m"), applied.map { it.decodeToString() })
        }

    @Test
    fun `switching the persona changes the face and the info line at once`() =
        runBlocking {
            val rotator = rotator()
            rotator.applyPersonaSwitch("female", nowMs = 0)

            assertEquals(CHANGED, rotator.applyPersonaSwitch("male", nowMs = 1))
            assertEquals(listOf("picture-m"), applied.map { it.decodeToString() })
            // The old info line described someone else, so it goes with the face
            // rather than waiting for the every-other-change rhythm.
            assertEquals(listOf("info-male"), about)
        }

    @Test
    fun `a persona switch restarts the weeks-long clock instead of stacking on it`() =
        runBlocking {
            val rotator = rotator()
            rotator.applyPersonaSwitch("female", nowMs = 0)
            rotator.applyPersonaSwitch("male", nowMs = 1)

            assertEquals(UNCHANGED, rotator.rotateIfDue("male", nowMs = interval - 1))
            assertEquals(CHANGED, rotator.rotateIfDue("male", nowMs = interval + 1))
        }

    @Test
    fun `a switch the bridge refuses is applied on the next session`() =
        runBlocking {
            val rotator = rotator()
            rotator.applyPersonaSwitch("female", nowMs = 0)
            failNextApply = true

            // The picture never went up, so the switch is still owed — recording
            // it as done here is what used to leave the old face on the account
            // until the next rotation, weeks later.
            assertEquals(PENDING, rotator.applyPersonaSwitch("male", nowMs = 1))
            assertTrue(applied.isEmpty())

            // Not due for a rotation at all, and it still catches up.
            assertEquals(PENDING, rotator.rotateIfDue("male", nowMs = 2))
            assertEquals(
                CHANGED,
                rotator.rotateIfDue("male", nowMs = 1 + ProfilePictureRotator.RETRY_BACKOFF_MS),
            )
            assertEquals(listOf("picture-m"), applied.map { it.decodeToString() })
            assertEquals(UNCHANGED, rotator.rotateIfDue("male", nowMs = 3))
        }

    @Test
    fun `a refused switch is not retried by every unrelated settings change`() =
        runBlocking {
            val rotator = rotator()
            rotator.applyPersonaSwitch("female", nowMs = 0)
            failNextApply = true
            assertEquals(PENDING, rotator.applyPersonaSwitch("male", nowMs = 1))
            assertTrue(applied.isEmpty())

            // This path runs from `onSettingsChanged`, which fires for every setting in the app.
            // Without the same cooldown the rotation pass honours, flipping any unrelated switch
            // re-uploaded the picture to WhatsApp — a broadcast to every contact, per toggle.
            repeat(5) { attempt ->
                assertEquals(PENDING, rotator.applyPersonaSwitch("male", nowMs = 2L + attempt))
            }
            assertTrue(applied.isEmpty())

            assertEquals(
                CHANGED,
                rotator.applyPersonaSwitch("male", nowMs = 1 + ProfilePictureRotator.RETRY_BACKOFF_MS),
            )
            assertEquals(listOf("picture-m"), applied.map { it.decodeToString() })
        }

    @Test
    fun `a switch made while the app was down is caught up on the next start`() =
        runBlocking {
            rotator().applyPersonaSwitch("female", nowMs = 0)

            // The persona setting moved while nothing was running; the start-up
            // report is the first time the rotator hears about it.
            assertEquals(CHANGED, rotator().applyPersonaSwitch("male", nowMs = 1))
            assertEquals(listOf("picture-m"), applied.map { it.decodeToString() })
        }

    @Test
    fun `a switch with no picture keeps the previously confirmed face authoritative`() =
        runBlocking {
            val rotator = rotator()
            rotator.applyPersonaSwitch("female", nowMs = 0)

            assertEquals(NO_PICTURES, rotator.applyPersonaSwitch("goth", nowMs = 1))
            // WhatsApp never changed, so the local projection must keep saying female. The small
            // catalogue may be checked again later, but no remote profile action is attempted.
            assertEquals(NO_PICTURES, rotator.applyPersonaSwitch("goth", nowMs = 2))
            assertEquals(NO_PICTURES, rotator.rotateIfDue("goth", nowMs = 3))
            assertEquals(UNCHANGED, rotator.applyPersonaSwitch("female", nowMs = 4))
            assertTrue(applied.isEmpty())
        }

    @Test
    fun `a persona without pictures is left alone`() =
        runBlocking {
            assertEquals(UNCHANGED, rotator().rotateIfDue("goth", nowMs = 0))
            assertFalse(state.isFile)
        }

    @Test
    fun `the staged file does not linger in the cache`() =
        runBlocking {
            val rotator = rotator()
            rotator.rotateIfDue("female", nowMs = 0)
            rotator.rotateIfDue("female", nowMs = interval)

            val leftOver: List<String> = work.listFiles()?.map(File::getName).orEmpty()
            assertEquals(emptyList<String>(), leftOver)
        }
}
