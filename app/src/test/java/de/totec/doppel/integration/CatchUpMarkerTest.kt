package de.totec.doppel.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7, second half: a message replayed after a reconnect must be recognizable as a catch-up rather
 * than as something that just arrived. Ordinary pickup delay is deliberately *not* lateness — the
 * online model holds replies for up to a quarter of an hour on purpose — so the threshold has to
 * clear that without swallowing a real multi-hour gap.
 */
class CatchUpMarkerTest {
    private val now = 1_785_672_180_000L

    @Test
    fun `a message answered right away is not marked`() {
        assertNull(CatchUpMarker.forMessage(now - 2_000L, now))
        assertNull(CatchUpMarker.forMessage(now, now))
    }

    /** The exact case that must not produce a false positive: a deliberate pickup delay. */
    @Test
    fun `a deliberate pickup delay is not lateness`() {
        assertNull(CatchUpMarker.forMessage(now - 15L * 60_000L, now))
        assertNull(CatchUpMarker.forMessage(now - 29L * 60_000L, now))
    }

    @Test
    fun `the threshold is the first marked age`() {
        assertNull(CatchUpMarker.forMessage(now - (CatchUpMarker.THRESHOLD_MS - 1L), now))
        assertEquals(
            "[Delivered late · 30 minutes ago]",
            CatchUpMarker.forMessage(now - CatchUpMarker.THRESHOLD_MS, now),
        )
    }

    @Test
    fun `the age is stated in a unit a reader can act on`() {
        assertEquals(
            "[Delivered late · 75 minutes ago]",
            CatchUpMarker.forMessage(now - 75L * 60_000L, now),
        )
        assertEquals(
            "[Delivered late · 6 hours ago]",
            CatchUpMarker.forMessage(now - 6L * 3_600_000L, now),
        )
        assertEquals(
            "[Delivered late · 3 days ago]",
            CatchUpMarker.forMessage(now - 3L * 86_400_000L, now),
        )
    }

    /** A clock that runs backwards must not turn every live message into a replay. */
    @Test
    fun `clock skew and missing timestamps are never marked`() {
        assertNull(CatchUpMarker.forMessage(now + 3_600_000L, now))
        assertNull(CatchUpMarker.forMessage(0L, now))
        assertNull(CatchUpMarker.forMessage(-1L, now))
    }

    /**
     * The marker is what separates a caught-up reply from a new one, so it has to be visible as its
     * own line and name the delay — not blend into the message text.
     */
    @Test
    fun `the marker reads as transport metadata and not as message text`() {
        val marker = CatchUpMarker.forMessage(now - 6L * 3_600_000L, now)!!

        assertTrue(marker.startsWith("["))
        assertTrue(marker.endsWith("]"))
        assertTrue(marker.contains("Delivered late"))
        assertEquals(1, marker.lines().size)
    }

    /** Every marked age must produce a bounded, non-empty description at any distance. */
    @Test
    fun `every age past the threshold is described`() {
        val ages =
            listOf(
                CatchUpMarker.THRESHOLD_MS,
                2L * 3_600_000L,
                47L * 3_600_000L,
                49L * 3_600_000L,
                400L * 86_400_000L,
            )

        ages.forEach { age ->
            val marker = CatchUpMarker.forMessage(now - age, now)
            assertTrue("age=$age", marker != null && marker.length in 20..64)
        }
    }
}
