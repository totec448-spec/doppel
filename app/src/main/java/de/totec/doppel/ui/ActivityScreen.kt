package de.totec.doppel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.totec.doppel.ui.theme.Broken
import de.totec.doppel.ui.theme.Inbound
import de.totec.doppel.ui.theme.Info
import de.totec.doppel.ui.theme.Live
import de.totec.doppel.ui.theme.Media
import de.totec.doppel.ui.theme.Waiting

/** Severity filter for the log. Debugging almost always starts by hiding the routine rows. */
private enum class LogFilter(val label: String) {
    ALL("Everything"),
    IMPORTANT("Important"),
    ;

    fun accepts(row: ActivityRowModel): Boolean =
        when (this) {
            ALL -> true
            IMPORTANT -> row.error || row.warning
        }
}

/**
 * The operator log.
 *
 * It used to imitate a terminal: 10sp monospace for everything and the technical `category/action`
 * as the headline, with the actual message in grey underneath. That is unreadable on a phone and it
 * buries the one line you came for. Now the message is the row, in normal text at normal size, on a
 * single line: glyph, clock, message. Everything else — subsystem, severity word, protocol name,
 * payload — is one tap away, because on a list this long the point is scanning, not reading. The
 * severity is a coloured rail down the left edge, so a screenful can be judged by colour alone, and
 * monospace is kept only for what is genuinely a machine string.
 */
@Composable
internal fun ActivityScreen(
    entries: List<UiActivityEntry>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(ScreenPadding, 0.dp, ScreenPadding, 32.dp),
) {
    val rows = remember(entries) { ActivityLogPresentation.rows(entries) }
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val visible = remember(rows, filter) { rows.filter(filter::accepts) }
    val expanded = rememberSaveable(saver = LongSetSaver) { mutableStateListOf<Long>() }

    // The log is a page when it is a page and a clipped panel when it is pushed open in Settings;
    // the difference is only ever how tall it is allowed to be, so it is a parameter, not a copy.
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        if (showTitle) {
            item(key = "title") {
                LargeTitle("Log")
            }
        }
        item(key = "filter") {
            Row(
                modifier = Modifier.padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LogFilter.entries.forEach { option ->
                    FilterChip(
                        text = option.label,
                        selected = filter == option,
                        onClick = { filter = option },
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyHint(
                    if (rows.isEmpty()) {
                        "Nothing has happened yet. Once the bot runs, every action shows up here."
                    } else {
                        "No entry matches this filter."
                    },
                )
            }
        } else {
            items(visible, key = ActivityRowModel::id) { row ->
                Column {
                    ListGroup {
                        LogLine(
                            row = row,
                            expanded = row.id in expanded,
                            onToggle = { if (!expanded.remove(row.id)) expanded.add(row.id) },
                            dense = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (hasMore) {
            item(key = "more") {
                // The outline is the same quiet button it always was; what grew is the region that
                // accepts the tap. This is the last thing on a long log and it used to be a 48 dp
                // strip that a thumb coming off a scroll gesture kept missing, so the gap above and
                // below it is part of the target rather than dead margin.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onLoadMore,
                            )
                            .padding(top = 16.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QuietButton(
                        text = "Load older",
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    )
                }
            }
        }
    }
}

/**
 * One log row, expandable to its full payload.
 *
 * The row used to be a single line — glyph, clock, message — with the glyph and the clock at body
 * size, side by side with the text. Between them they took roughly a third of the width and the
 * message, the only part anyone reads, was cut off mid-sentence on almost every entry. Both are
 * still needed (the glyph is how a screenful is sorted by shape, the clock is the whole point of a
 * log), so they moved into a narrow gutter at reduced size and stacked, which buys the message the
 * full remaining width over two lines instead of a third of one.
 *
 * Shared with the overview, where the rows appear inside a group — hence the rail sitting flush at
 * the left edge and the separators being drawn by the caller. [dense] only decides whether the
 * subsystem line is worth its height; the overview has room for it, the log tab does not.
 */
@Composable
internal fun LogLine(
    row: ActivityRowModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    dense: Boolean = false,
) {
    val accent = row.accent.color()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
        )
        Gutter(glyph = row.glyph, time = row.clock)
        Column(
            modifier = Modifier.padding(end = 14.dp, top = 9.dp, bottom = 9.dp),
        ) {
            if (!dense || expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.error || row.warning) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = row.level,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = row.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                // The seconds live here rather than in the gutter: this is the line you read when
                // you are lining one event up against another.
                Text(
                    text = "${row.time}  ${row.technical}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.details?.takeIf(String::isNotBlank)?.let { details ->
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                )
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The row's metadata column: what happened, and when, on the smallest size either survives at.
 *
 * The width is fixed rather than intrinsic for two reasons. Emoji are not all the same advance
 * width — 🎙️ is narrower than 🚫 — so a wrapping column would leave the messages starting at a
 * different x on every row, and a fixed column means the clock needs no monospace metrics of its
 * own to stay in line with the one above it.
 */
@Composable
private fun Gutter(
    glyph: String,
    time: String,
) {
    Column(
        modifier = Modifier.width(GutterWidth).padding(start = 9.dp, top = 10.dp, end = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Text(
            text = time,
            style = ClockStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private val GutterWidth = 46.dp

/**
 * Smaller than anything in the type scale, on purpose: the clock has to stay readable but it must
 * not compete with the sentence next to it. It shows `HH:mm` — the eight digits of `HH:mm:ss` do
 * not fit this column at any size the row can afford, and a clipped `09:34:` is worse than a
 * minute, so the seconds moved to the expanded row.
 */
private val ClockStyle =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 13.sp,
        letterSpacing = (-0.4).sp,
    )

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(100),
                )
                .clickable(onClick = onClick)
                .heightIn(min = 30.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** The log's colour slots resolved against the app palette. */
@Composable
internal fun ActivityAccent.color(): Color =
    when (this) {
        ActivityAccent.GRAY -> MaterialTheme.colorScheme.outline
        ActivityAccent.CYAN -> Inbound
        ActivityAccent.BLUE -> Info
        ActivityAccent.GREEN -> Live
        ActivityAccent.MAGENTA -> Media
        ActivityAccent.YELLOW -> Waiting
        ActivityAccent.RED -> Broken
    }

/** Which rows are expanded, kept across rotation and process death. */
internal val LongSetSaver =
    listSaver<SnapshotStateList<Long>, Long>(
        save = { it.toList() },
        restore = { restored -> mutableStateListOf<Long>().apply { addAll(restored) } },
    )
