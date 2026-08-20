package de.totec.doppel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.totec.doppel.app.accent
import de.totec.doppel.commands.AdminPayload
import de.totec.doppel.commands.MemoryScope
import de.totec.doppel.commands.MemorySummary
import de.totec.doppel.engine.MemoryWork
import de.totec.doppel.engine.writingConversation
import de.totec.doppel.engine.writingPersona
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Full-screen browser over everything the bot has written down.
 *
 * The list is metadata only — a document's body is fetched on demand, because a single summary may
 * be far larger than anything a list row could show. [entries] is null while the catalogue request
 * is still in flight, and [document] non-null while one entry is open.
 *
 * The two levels are now a normal push: opening a file replaces the list and the back arrow returns
 * to it, instead of a header that changed meaning depending on what was showing.
 *
 * Above the list sits one card per persona that already owns a chat memory; tapping it runs that
 * persona's global synthesis now instead of waiting for the third chat write to trigger it.
 */
@Composable
fun MemoryBrowserDialog(
    entries: List<MemorySummary>?,
    document: AdminPayload.MemoryDocument?,
    /** Every write running right now, so a file about to change says so before you open it. */
    work: List<MemoryWork>,
    onOpen: (MemorySummary) -> Unit,
    onCreateGlobalMemory: (String) -> Unit,
    onCancelGlobalMemory: (String) -> Unit,
    /** Empties chat prose or fully deletes a persona memory — see the confirm strip. */
    onDelete: (MemorySummary) -> Unit,
    /** Revision-aware prose edit; stable facts and memory pointers stay untouched. */
    onSaveSummary: (MemorySummary, String) -> Unit,
    onCloseDocument: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (document != null) onCloseDocument() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                if (document != null) {
                    DetailAppBar(
                        // A chat memory is titled by the chat's own address, which is a number.
                        title = masked(document.summary.title),
                        subtitle = document.summary.subtitle,
                        onBack = onCloseDocument,
                    )
                    MemoryDocumentView(document, onSaveSummary)
                } else {
                    DetailAppBar(
                        title = "Memory",
                        subtitle = memoryCountLabel(entries, work),
                        onBack = onDismiss,
                        actions = { CircleIconButton(BotIcon.REFRESH, onClick = onRefresh) },
                    )
                    when {
                        entries == null -> LoadingBody()
                        entries.isEmpty() ->
                            EmptyHint(
                                "No memory file written yet. The bot condenses a chat once " +
                                    "enough new messages have piled up.",
                            )

                        else ->
                            MemoryList(
                                entries,
                                work,
                                onOpen,
                                onCreateGlobalMemory,
                                onCancelGlobalMemory,
                                onDelete,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryList(
    entries: List<MemorySummary>,
    work: List<MemoryWork>,
    onOpen: (MemorySummary) -> Unit,
    onCreateGlobalMemory: (String) -> Unit,
    onCancelGlobalMemory: (String) -> Unit,
    onDelete: (MemorySummary) -> Unit,
) {
    val chats = entries.filter { it.scope == MemoryScope.CHAT }
    val personas = entries.filter { it.scope == MemoryScope.PERSONA }
    val targets = personaTargets(chats, personas)
    // The card reacts before the engine has announced anything, because starting the synthesis is a
    // round trip and the press that used to land in that gap was the one that billed twice. It is
    // handed back to the feed as soon as the feed knows, and released on a timeout if the write
    // never starts at all.
    var requested by remember { mutableStateOf<String?>(null) }
    val pending = requested
    val announced = pending != null && work.writingPersona(pending)
    LaunchedEffect(pending, announced) {
        if (pending == null) return@LaunchedEffect
        // Once the feed reports the write the optimistic flag is redundant, and holding it would
        // outlive the write it stands in for.
        if (announced) {
            requested = null
        } else {
            delay(GLOBAL_WRITE_HANDOVER_MS)
            if (requested == pending) requested = null
        }
    }

    // One open confirm strip at a time, held here rather than per row: the rows live in a
    // LazyColumn and would forget the state as soon as one scrolled out from under a finger.
    var confirming by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ScreenPadding, 4.dp, ScreenPadding, 32.dp),
    ) {
        if (targets.isNotEmpty()) {
            item(key = "persona_actions") {
                SectionLabel("Global memory")
                // Two per row rather than a horizontal scroll: with three personas the strip ran
                // off the edge of a screen that has plenty of vertical room, and a card wide enough
                // to read was always wider than half the width it was given.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    targets.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { target ->
                                PersonaMemoryCard(
                                    target = target,
                                    writing =
                                        work.writingPersona(target.personaKey) ||
                                            requested == target.personaKey,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        requested = target.personaKey
                                        onCreateGlobalMemory(target.personaKey)
                                    },
                                    onCancel = {
                                        requested = null
                                        onCancelGlobalMemory(target.personaKey)
                                    },
                                )
                            }
                            // Keeps a lone last card at half width instead of letting it stretch
                            // across the row and read as a different kind of thing.
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        item(key = "totals") {
            StatStrip(
                listOf(
                    "Chats" to chats.size.toString(),
                    "Personas" to personas.size.toString(),
                    "Characters" to formatChars(entries.sumOf { it.characters }),
                ),
            )
            Spacer(Modifier.height(6.dp))
        }
        memorySection("Per chat", chats, work, confirming, onOpen, { confirming = it }, onDelete)
        memorySection("Per persona", personas, work, confirming, onOpen, { confirming = it }, onDelete)
    }
}

/** Identifies a row across both sections; the two scopes may share an id in principle. */
private fun MemorySummary.rowKey(): String = "$scope:$id"

/** How long the card stands in for the feed before giving up on the write having started. */
private const val GLOBAL_WRITE_HANDOVER_MS = 30_000L

/** A persona the strip may offer, plus the two numbers its card shows. */
private data class PersonaMemoryTarget(
    val personaKey: String,
    val chatCount: Int,
    val globalUpdatedAtMs: Long?,
)

/**
 * Only personas that already own at least one chat memory can be synthesised — the global file is
 * nothing but those chat files read together, so a persona without any has nothing to condense.
 * Conversation keys are "<chatJid>#<persona>"; the legacy keys without a tail carry no persona and
 * therefore never appear here.
 */
private fun personaTargets(
    chats: List<MemorySummary>,
    personas: List<MemorySummary>,
): List<PersonaMemoryTarget> {
    val written = personas.associate { it.id to it.updatedAtMs }
    return chats
        .mapNotNull { it.id.substringAfterLast('#', "").takeIf(String::isNotBlank) }
        .groupingBy { it }
        .eachCount()
        .map { (persona, count) -> PersonaMemoryTarget(persona, count, written[persona]) }
        .sortedWith(compareByDescending<PersonaMemoryTarget> { it.chatCount }.thenBy { it.personaKey })
}

@Composable
private fun PersonaMemoryCard(
    target: PersonaMemoryTarget,
    writing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                // A synthesis reads every chat file of the persona and rewrites one document.
                // Pressing again while it runs starts a second one over the same input.
                .clickable(enabled = !writing, onClick = onClick)
                .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                initial = target.personaKey.take(1).uppercase(Locale.ROOT),
                accent = target.personaKey.accent(),
                isGroup = false,
                size = 32,
            )
            Text(
                target.personaKey,
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            buildString {
                append(target.chatCount)
                append(if (target.chatCount == 1) " chat file" else " chat files")
                append(" · ")
                append(
                    target.globalUpdatedAtMs
                        ?.let { "written ${relativeTime(it)}" }
                        ?: "never written",
                )
            },
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (writing) {
                MemoryWritingDot(size = 8.dp)
                MemoryWritingText(
                    text = "Writing…",
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                CircleIconButton(
                    icon = BotIcon.CLOSE,
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = "Abandon global memory" },
                )
            } else {
                BotLineIcon(BotIcon.SPARK, Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
                Text(
                    if (target.globalUpdatedAtMs == null) "Create global" else "Rewrite global",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.memorySection(
    label: String,
    entries: List<MemorySummary>,
    work: List<MemoryWork>,
    confirming: String?,
    onOpen: (MemorySummary) -> Unit,
    onConfirming: (String?) -> Unit,
    onDelete: (MemorySummary) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "label_$label") { SectionLabel(label) }
    item(key = "group_$label") {
        ListGroup {
            entries.forEachIndexed { index, entry ->
                if (index > 0) RowSeparator()
                MemoryRow(
                    entry = entry,
                    // A chat file is keyed by its conversation key and a persona file by the
                    // persona id — which is exactly how the two kinds of write are keyed too.
                    writing =
                        when (entry.scope) {
                            MemoryScope.CHAT -> work.writingConversation(entry.id)
                            MemoryScope.PERSONA -> work.writingPersona(entry.id)
                        },
                    confirming = confirming == entry.rowKey(),
                    onOpen = onOpen,
                    onConfirming = { open -> onConfirming(if (open) entry.rowKey() else null) },
                    onDelete = {
                        onConfirming(null)
                        onDelete(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryRow(
    entry: MemorySummary,
    writing: Boolean,
    confirming: Boolean,
    onOpen: (MemorySummary) -> Unit,
    onConfirming: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(entry) }
                    .padding(start = 16.dp, end = 4.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    masked(entry.title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (writing) {
                    MemoryWritingText(
                        text = "Being rewritten right now…",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        buildString {
                            append(entry.subtitle)
                            append(" · ")
                            append(relativeTime(entry.updatedAtMs))
                            append(" · ")
                            append(formatChars(entry.characters))
                            append(" characters")
                            if (entry.hasFacts) append(" · facts")
                        },
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.preview.isNotBlank()) {
                    Text(
                        entry.preview,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Withheld while the file is being rewritten: the write already holds the row and
            // would land on top of the deletion a second later, which looks like nothing happened.
            if (!writing) {
                EraseButton(active = confirming, onClick = { onConfirming(!confirming) })
            }
            BotLineIcon(
                BotIcon.CHEVRON_RIGHT,
                Modifier.padding(start = 2.dp, end = 8.dp).size(16.dp),
                MaterialTheme.colorScheme.outline,
            )
        }
        PushPanel(expanded = confirming) {
            Text(
                if (entry.scope == MemoryScope.PERSONA) {
                    "Delete this global memory? The entry, revision and update date disappear. " +
                        "A future synthesis can create a new memory from the chat files."
                } else {
                    "Empty this chat memory? What she wrote down is gone — where she has read " +
                        "up to is not, so the next memory starts where this one ended."
                },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuietButton("Keep", { onConfirming(false) }, Modifier.weight(1f))
                HoldToConfirm(
                    if (entry.scope == MemoryScope.PERSONA) "Hold to delete" else "Hold to empty",
                    onDelete,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The one destructive control in a list of documents, so it stays quiet until it is armed:
 * an outline that only fills in once the confirm strip below it is open.
 */
@Composable
private fun EraseButton(
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        if (active) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.outline
        }
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .semantics { contentDescription = "delete or empty this memory" }
                .clip(CircleShape)
                .background(
                    if (active) {
                        MaterialTheme.colorScheme.error.copy(alpha = .12f)
                    } else {
                        Color.Transparent
                    },
                )
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BotLineIcon(BotIcon.TRASH, Modifier.size(17.dp), tint)
    }
}

@Composable
private fun MemoryDocumentView(
    document: AdminPayload.MemoryDocument,
    onSaveSummary: (MemorySummary, String) -> Unit,
) {
    var editing by remember(document.summary.id, document.summary.revision) { mutableStateOf(false) }
    var draft by remember(document.summary.id, document.summary.revision) {
        mutableStateOf(document.body)
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
    ) {
        SectionLabel("File")
        ListGroup {
            ListRow(title = "Last updated", value = absoluteTime(document.summary.updatedAtMs))
            RowSeparator()
            ListRow(title = "Revision", value = document.summary.revision.toString())
            RowSeparator()
            ListRow(title = "Size", value = "${formatChars(document.summary.characters)} characters")
            if (document.summary.sourceMessageCount > 0) {
                RowSeparator()
                ListRow(
                    title = "Condensed messages",
                    value = document.summary.sourceMessageCount.toString(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            document.summary.id,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )

        if (document.facts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("Stable facts (${document.facts.size})")
            ListGroup {
                document.facts.forEachIndexed { index, fact ->
                    if (index > 0) RowSeparator()
                    Text(
                        fact,
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 13.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Summary")
        if (editing) {
            BotTextField(
                value = draft,
                onValueChange = { draft = it.take(MAX_EDITABLE_MEMORY_CHARS) },
                label = "Summary",
                singleLine = false,
                minLines = 8,
                maxLines = 24,
                supporting =
                    "${formatChars(draft.length)} / " +
                        "${formatChars(MAX_EDITABLE_MEMORY_CHARS)} characters",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(
                    text = "Cancel",
                    onClick = {
                        draft = document.body
                        editing = false
                    },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "Save summary",
                    onClick = {
                        onSaveSummary(document.summary, draft)
                        editing = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = draft.trim().isNotEmpty() && draft != document.body,
                )
            }
        } else {
            ListGroup {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Edit memory summary" }
                            .clickable { editing = true }
                            .padding(16.dp),
                ) {
                    Text(
                        document.body.ifBlank { "This file is empty." },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap to edit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private const val MAX_EDITABLE_MEMORY_CHARS = 1_048_576

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

private fun memoryCountLabel(
    entries: List<MemorySummary>?,
    work: List<MemoryWork>,
): String {
    val files =
        when {
            entries == null -> return "Loading…"
            entries.isEmpty() -> "No files"
            entries.size == 1 -> "1 file"
            else -> "${entries.size} files"
        }
    return when (work.size) {
        0 -> files
        1 -> "$files · 1 being written"
        else -> "$files · ${work.size} being written"
    }
}

private fun formatChars(value: Int): String =
    if (value >= 1_000) String.format(Locale.ROOT, "%.1fk", value / 1000.0) else value.toString()

private fun relativeTime(timestampMs: Long): String {
    val delta = System.currentTimeMillis() - timestampMs
    val minutes = delta / 60_000
    return when {
        delta < 0 -> absoluteTime(timestampMs)
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} d ago"
        else -> absoluteTime(timestampMs)
    }
}

private fun absoluteTime(timestampMs: Long): String =
    DateFormat
        .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(timestampMs))
