package de.totec.doppel.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Date

/**
 * P10: scrolling a long log dropped frames. The list was already lazy and paged — the cost was
 * per row, inside the composition: every visible row built its own [DateFormat] (a locale lookup
 * plus a pattern compile) and its own [Date], on every single frame.
 *
 * Formatting now happens once per entry, outside the scroll path. [renderCostBeforeAndAfter]
 * measures exactly that difference on a realistically large log.
 */
class ActivityLogRenderCostTest {
    @Test
    fun `rows carry everything the row composable needs`() {
        val rows = ActivityLogPresentation.rows(listOf(entry(id = 7, level = "warn")))
        val row = rows.single()

        assertEquals(7L, row.id)
        assertEquals("WhatsApp link", row.category)
        assertEquals("WARNING", row.level)
        assertEquals("bridge/connect", row.technical)
        assertTrue(row.warning)
        assertFalse(row.error)
        assertTrue(row.glyph.isNotBlank())
        assertEquals(ActivityAccent.YELLOW, row.accent)
        assertTrue(row.time.matches(Regex("""\d{2}:\d{2}:\d{2}""")))
    }

    /**
     * The gutter is 46dp wide and `HH:mm:ss` does not fit it — it rendered as `09:34:`, a clock
     * with a dangling colon. The collapsed row therefore carries the minute, and only the expanded
     * row spends width on the second.
     */
    @Test
    fun `the collapsed clock is a minute, not a truncated second`() {
        val row = ActivityLogPresentation.rows(listOf(entry(id = 1))).single()

        assertTrue(row.clock.matches(Regex("""\d{2}:\d{2}""")))
        assertEquals(row.time.take(5), row.clock)
    }

    @Test
    fun `level flags are derived once instead of per frame`() {
        val rows = ActivityLogPresentation.rows(
            listOf(
                entry(id = 1, level = "ERROR"),
                entry(id = 2, level = "Warning"),
                entry(id = 3, level = "info"),
            ),
        )

        assertTrue(rows[0].error)
        assertFalse(rows[0].warning)
        assertTrue(rows[1].warning)
        assertFalse(rows[2].error)
        assertFalse(rows[2].warning)
    }

    /** One oversized payload must not be able to stall a frame when its row is expanded. */
    @Test
    fun `details are bounded and absent details stay absent`() {
        val huge = "x".repeat(ActivityLogPresentation.MAX_DETAIL_CHARACTERS * 3)
        val rows = ActivityLogPresentation.rows(
            listOf(entry(id = 1, details = huge), entry(id = 2, details = null)),
        )

        assertEquals(ActivityLogPresentation.MAX_DETAIL_CHARACTERS, rows[0].details?.length)
        assertNull(rows[1].details)
    }

    /**
     * A terminal log needs seconds — two events inside the same minute are usually the interesting
     * pair — so the timestamp cache buckets per second, not per minute.
     */
    @Test
    fun `entries in the same second share one formatted timestamp`() {
        val base = 1_760_000_000_000L
        val rows = ActivityLogPresentation.rows(
            listOf(
                entry(id = 1, timestampMs = base),
                entry(id = 2, timestampMs = base + 400L),
                entry(id = 3, timestampMs = base + 30_000L),
            ),
        )

        assertEquals(rows[0].time, rows[1].time)
        assertTrue(rows[0].time != rows[2].time)
    }

    /**
     * The before/after measurement the fix is judged by.
     *
     * It models a scroll rather than a single pass, because that is where the cost actually sat:
     * the old code formatted inside the row composable, so every frame re-paid for every visible
     * row. The new code formats once when the log changes and the scroll only reads strings.
     *
     * "Before" = [FRAMES] frames x [VISIBLE_ROWS] rows of per-row formatting.
     * "After" = the same frames reading already-formatted rows; the one-off
     * [ActivityLogPresentation.rows] pass is measured separately, because it happens once per log
     * change rather than once per frame. Both are warmed up first so JIT compilation is not part of
     * the number.
     */
    @Test
    fun renderCostBeforeAndAfter() {
        val log = (1..LOG_SIZE).map { entry(id = it.toLong(), timestampMs = 1_760_000_000_000L + it * 7_000L) }

        repeat(3) {
            scrollFormattingPerFrame(log)
            scrollOverPrecomputedRows(ActivityLogPresentation.rows(log))
        }

        val before = measureNanos { scrollFormattingPerFrame(log) }
        val rows = ActivityLogPresentation.rows(log)
        val after = measureNanos { scrollOverPrecomputedRows(rows) }
        val precompute = measureNanos { ActivityLogPresentation.rows(log) }

        println(
            "activity log scroll cost, $LOG_SIZE entries, $FRAMES frames x $VISIBLE_ROWS visible rows: " +
                "before=${"%.2f".format(before / 1_000_000.0)} ms " +
                "(${"%.4f".format(before / 1_000_000.0 / FRAMES)} ms/frame), " +
                "after=${"%.2f".format(after / 1_000_000.0)} ms " +
                "(${"%.4f".format(after / 1_000_000.0 / FRAMES)} ms/frame), " +
                "factor=${"%.1f".format(before.toDouble() / after)}x; " +
                "one-off precompute for the whole log=${"%.2f".format(precompute / 1_000_000.0)} ms",
        )

        // The precompute runs once per log change, the scroll runs every frame — so the frame cost
        // is the one that has to collapse.
        assertTrue(
            "Expected the precomputed path to be at least 5x cheaper per frame, " +
                "was before=${before}ns after=${after}ns",
            after * 5 < before,
        )
        // And the one-off pass must not simply move the stall to the moment the log arrives.
        assertTrue(
            "Precompute for $LOG_SIZE entries took ${precompute / 1_000_000.0} ms",
            precompute < 50_000_000L,
        )
    }

    /** The pre-fix scroll: each frame formats its own visible rows from scratch. */
    private fun scrollFormattingPerFrame(entries: List<UiActivityEntry>): Int {
        var sink = 0
        repeat(FRAMES) { frame ->
            val first = (frame * 3) % (entries.size - VISIBLE_ROWS)
            sink += formatPerRow(entries.subList(first, first + VISIBLE_ROWS)).sumOf(String::length)
        }
        return sink
    }

    /** The current scroll: the frame only reads strings that were formatted once, up front. */
    private fun scrollOverPrecomputedRows(rows: List<ActivityRowModel>): Int {
        var sink = 0
        repeat(FRAMES) { frame ->
            val first = (frame * 3) % (rows.size - VISIBLE_ROWS)
            for (index in first until first + VISIBLE_ROWS) {
                val row = rows[index]
                sink += row.time.length + row.category.length + row.level.length + row.technical.length
            }
        }
        return sink
    }

    private inline fun measureNanos(block: () -> Unit): Long {
        val best = (1..REPEATS).minOf {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }
        return best
    }

    /** The pre-fix formatting, reproduced verbatim: a fresh formatter and Date for every row. */
    private fun formatPerRow(entries: List<UiActivityEntry>): List<String> =
        entries.map { entry ->
            val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
            formatter.format(Date(entry.timestampMs)) +
                ActivityLogPresentation.categoryLabel(entry.category) +
                ActivityLogPresentation.levelLabel(entry.level) +
                "${entry.category}/${entry.action}"
        }

    private fun entry(
        id: Long,
        timestampMs: Long = 1_760_000_000_000L,
        level: String = "info",
        details: String? = null,
    ) = UiActivityEntry(
        id = id,
        timestampMs = timestampMs,
        level = level,
        category = "bridge",
        action = "connect",
        message = "Verbindung hergestellt",
        details = details,
    )

    private companion object {
        /** A log the operator would actually be scrolling through after a day of traffic. */
        const val LOG_SIZE = 2_000

        /** Roughly two seconds of scrolling at 60 Hz. */
        const val FRAMES = 120

        /** How many log rows fit on a phone screen at once. */
        const val VISIBLE_ROWS = 12
        const val REPEATS = 5
    }
}
