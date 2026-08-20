package de.totec.doppel.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.min
import de.totec.doppel.commands.AccessOperation
import de.totec.doppel.data.ChatOverrides
import de.totec.doppel.engine.ChatActivity
import de.totec.doppel.engine.ChatStage
import de.totec.doppel.engine.LiveTurn
import de.totec.doppel.engine.MemoryWork
import de.totec.doppel.engine.TraceKind
import de.totec.doppel.engine.TraceLine
import de.totec.doppel.engine.isWorking
import de.totec.doppel.ui.theme.Base
import de.totec.doppel.ui.theme.Broken
import de.totec.doppel.ui.theme.BubbleBot
import de.totec.doppel.ui.theme.BubbleBotInk
import de.totec.doppel.ui.theme.BubbleContact
import de.totec.doppel.ui.theme.BubbleContactInk
import de.totec.doppel.ui.theme.Info
import de.totec.doppel.ui.theme.Layer1
import de.totec.doppel.ui.theme.Layer2
import de.totec.doppel.ui.theme.Live
import de.totec.doppel.ui.theme.LiveDark
import de.totec.doppel.ui.theme.LiveMuted
import de.totec.doppel.ui.theme.Media
import de.totec.doppel.ui.theme.OutlineSoft
import de.totec.doppel.ui.theme.TextHigh
import de.totec.doppel.ui.theme.TextLow
import de.totec.doppel.ui.theme.TextMid
import de.totec.doppel.ui.theme.Waiting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One conversation, and what the bot is doing inside it.
 *
 * The screen is a normal transcript with one thing that is not: the input field at the bottom does
 * not accept typing. It is a flight recorder. It shows the stages of the running turn, the model's
 * reasoning and every tool it calls, and finally the answer being typed into it at the speed the bot
 * is really typing it — the same characters, at the same time, that the other person is watching
 * appear. When the bubble is sent, the field empties and the bubble lands in the log above.
 *
 * That is the whole reason this app exists. Everything else here — memory, injections, the settings
 * beside the field — is in service of being able to change what the next turn will do.
 */
@Composable
fun ChatScreen(
    detail: ChatDetail,
    live: StateFlow<LiveTurn?>,
    activity: ChatActivity?,
    onBack: () -> Unit,
    onInject: (String) -> Unit,
    onEditMemory: (memoryId: String, text: String) -> Unit,
    onDeleteInjection: (Long) -> Unit,
    onDeletePersonaChat: () -> Unit,
    onSetOverride: (key: String, value: String?) -> Unit,
    onImportChat: (uri: Uri) -> Unit,
    onCreateMemory: () -> Unit,
    /** A memory write for this chat is running — by hand or on the bot's own cadence. */
    writingMemory: MemoryWork?,
    /**
     * The cross-chat synthesis for this chat's persona is running.
     *
     * It is the second half of one operation and lands in a different file, so it gets no rule in
     * this transcript — but it is the same forty-second model call, and leaving the composer on
     * "Nothing running" through it is what made the whole write look finished when it was not.
     */
    writingGlobalMemory: MemoryWork? = null,
    onWritePerson: (() -> Unit)?,
    onBlockPerson: (() -> Unit)?,
    adminOperation: AccessOperation?,
    contactIsOwner: Boolean,
    onChangeAdmin: (AccessOperation) -> Unit,
    personaOptions: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val initialBottom = remember(detail.chatId) { detail.entries.lastIndex.coerceAtLeast(0) }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialBottom,
            initialFirstVisibleItemScrollOffset = Int.MAX_VALUE,
        )
    val scope = rememberCoroutineScope()
    val currentIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    var settingsOpen by remember { mutableStateOf(false) }
    var injectOpen by remember { mutableStateOf(false) }
    val dismissPanelsInteraction = remember { MutableInteractionSource() }
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onImportChat)
        }
    // The trace and the field float over the transcript, so the list has to reserve exactly their
    // height. Measured here and fed back into contentPadding below.
    var overlayHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    // This is intentionally driven only by an actual operator scroll. When a new row is appended,
    // canScrollForward becomes true before the effect below runs; using it directly would therefore
    // mistake a new bot message for the operator having scrolled away from the tail.
    var followTail by remember(detail.chatId) { mutableStateOf(true) }
    // Expansion is per entry and deliberately not remembered across chats: a memory left open in
    // one conversation should not be open in the next one, which is a different memory entirely.
    val expanded = remember(detail.chatId) { mutableStateMapOf<String, Boolean>() }

    BackHandler(enabled = settingsOpen || injectOpen) {
        settingsOpen = false
        injectOpen = false
    }
    val knownMemoryRevisions =
        remember(detail.chatId) {
            mutableStateMapOf<String, Long>().apply {
                detail.entries.filterIsInstance<ChatEntry.Memory>().forEach { put(it.id, it.revision) }
            }
        }

    val memoryIndices =
        remember(detail.entries) {
            detail.entries.mapIndexedNotNull { index, entry ->
                index.takeIf { entry is ChatEntry.Memory }
            }
        }

    val memoryRevisions =
        remember(detail.entries) {
            detail.entries.filterIsInstance<ChatEntry.Memory>().map { it.id to it.revision }
        }
    LaunchedEffect(memoryRevisions) {
        memoryRevisions.forEach { (id, revision) ->
            val previous = knownMemoryRevisions[id]
            if (previous == null || revision > previous) expanded[id] = true
            knownMemoryRevisions[id] = revision
        }
    }

    LaunchedEffect(detail.chatId, listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) followTail = !canScrollForward
            }
    }

    // One effect owns following the tail, for every reason the tail can move: a new row, a trace
    // panel opening under it and taking reserved space with it, or the writing rule appearing past
    // the last entry. It used to be three, and they fought each other — a panel that resized while
    // the operator was dragging relaunched one of them mid-scroll, `scrollToItem` cancelled the
    // drag, and the list snapped back down. That is the "it keeps pulling me to the bottom" during
    // a memory write, when the held turn is recording a trace line every few seconds.
    //
    // Opening is already positioned at the last row. `isScrollInProgress` is the whole guard: while
    // a finger is on the screen the transcript belongs to the operator, and the next change follows
    // the tail again anyway.
    val transcriptTail = detail.entries.lastOrNull()
    LaunchedEffect(
        detail.chatId,
        detail.entries.size,
        transcriptTail,
        overlayHeight,
        writingMemory,
    ) {
        if (detail.entries.isNotEmpty() && followTail && !listState.isScrollInProgress) {
            listState.scrollToItem(detail.entries.lastIndex, Int.MAX_VALUE)
        }
    }

    Column(modifier.fillMaxSize().background(Base)) {
        ChatTopBar(
            detail = detail,
            onBack = onBack,
            onTogglePause = {
                onSetOverride(
                    ChatOverrides.PAUSED,
                    if (detail.settings.paused) null else true.toString(),
                )
            },
            onImportChat = { importPicker.launch(arrayOf("text/plain", "text/*")) },
            onCreateMemory = onCreateMemory,
            writingMemory = writingMemory != null,
            onWritePerson = onWritePerson,
            onBlockPerson = onBlockPerson,
            adminOperation = adminOperation,
            contactIsOwner = contactIsOwner,
            onChangeAdmin = onChangeAdmin,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            enabled = settingsOpen || injectOpen,
                            interactionSource = dismissPanelsInteraction,
                            indication = null,
                        ) {
                            settingsOpen = false
                            injectOpen = false
                        },
                // The recorder floats over the transcript. This spacer lets the last bubble clear
                // it while the transparent space around the field still shows the conversation.
                // Measured rather than fixed: an expanded trace is several times the height of the
                // bare field, and a constant 84 dp let it cover the newest messages — the ones the
                // trace is about.
                contentPadding = PaddingValues(top = 8.dp, bottom = overlayHeight + 12.dp),
            ) {
            if (detail.entries.isEmpty()) {
                item(key = "empty-chat") {
                    EmptyHint("No messages in this chat yet.")
                }
            }
            items(
                count = detail.entries.size,
                key = { index -> detail.entries[index].stableKey() },
            ) { index ->
                when (val entry = detail.entries[index]) {
                    is ChatEntry.Bubble -> MessageBubble(entry)
                    is ChatEntry.DayBreak -> DayBreakRule(entry.label)
                    is ChatEntry.Memory ->
                        MemoryRule(
                            entry = entry,
                            budget = detail.memoryBudget,
                            expanded = expanded[entry.id] == true,
                            onToggle = { expanded[entry.id] = expanded[entry.id] != true },
                            onSave = { text -> onEditMemory(entry.conversationKey, text) },
                            onJumpUp =
                                memoryIndices.lastOrNull { it < index }?.let { target ->
                                    { scope.launch { listState.animateScrollToItem(target) }; Unit }
                                },
                            onJumpDown =
                                memoryIndices.firstOrNull { it > index }?.let { target ->
                                    { scope.launch { listState.animateScrollToItem(target) }; Unit }
                                },
                        )

                    is ChatEntry.Injection ->
                        InjectionRule(
                            entry = entry,
                            expanded = expanded["inject-${entry.id}"] == true,
                            onToggle = {
                                expanded["inject-${entry.id}"] =
                                    expanded["inject-${entry.id}"] != true
                            },
                            onDelete = { onDeleteInjection(entry.id) },
                        )

                    is ChatEntry.PersonaSwitch -> PersonaSwitchRule(entry)
                }
            }
            // Sits exactly where the finished rule will appear, so the write lands in the place you
            // were already watching instead of somewhere you have to go find.
            if (writingMemory != null) {
                item(key = "memory-writing") { MemoryWritingRule(work = writingMemory) }
            }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } },
            ) {
                ScheduledFollowUpStrip(detail.scheduledFollowUp)
                // Both panels live in the measured overlay, so opening one lifts the transcript by
                // exactly its height rather than covering the conversation it is about.
                ChatSettingsPanel(
                    expanded = settingsOpen,
                    detail = detail,
                    personaOptions = personaOptions,
                    memoryCount = memoryIndices.size,
                    onPreviousMemory =
                        memoryIndices.lastOrNull { it < currentIndex }?.let { target ->
                            {
                                settingsOpen = false
                                scope.launch { listState.animateScrollToItem(target) }
                            }
                        },
                    onNextMemory =
                        memoryIndices.firstOrNull { it > currentIndex }?.let { target ->
                            {
                                settingsOpen = false
                                scope.launch { listState.animateScrollToItem(target) }
                            }
                        },
                    onSet = onSetOverride,
                    onAddNote = { injectOpen = true },
                    onDeletePersonaChat = onDeletePersonaChat,
                    onDismiss = { settingsOpen = false },
                    connectedAbove = detail.scheduledFollowUp != null,
                    modifier = Modifier,
                )
                InjectPanel(
                    expanded = injectOpen,
                    onSend = { text ->
                        onInject(text)
                        injectOpen = false
                    },
                    onDismiss = { injectOpen = false },
                    connectedAbove = detail.scheduledFollowUp != null,
                    modifier = Modifier,
                )

                LiveRecorderOverlay(
                    live = live,
                    chatId = detail.chatId,
                    activity = activity?.takeIf { it.chatJid == detail.chatId },
                    writingMemory = writingMemory,
                    writingGlobalMemory = writingGlobalMemory,
                    paused = detail.settings.paused,
                    panelsOpen = settingsOpen || injectOpen,
                    scheduledFollowUpVisible = detail.scheduledFollowUp != null,
                    // A toggle now that the panel stays on screen: the same control that opened it
                    // is the obvious one to close it with.
                    onOpenSettings = {
                        settingsOpen = !settingsOpen
                        if (settingsOpen) injectOpen = false
                    },
                    overrideCount = detail.settings.overrideCount,
                    modifier = Modifier,
                )
            }
        }
    }

}

@Composable
private fun ScheduledFollowUpStrip(followUp: UiScheduledFollowUp?) {
    AnimatedVisibility(
        visible = followUp != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val value = followUp ?: return@AnimatedVisibility
        val delayed = value.nextAttemptAtMs > value.scheduledAtMs
        // The roof of the stack, not a badge floating over it. Whatever opens below — settings,
        // inject, the trace, or the composer itself — continues these walls with a square top, so
        // the strip and the panel read as one surface. The rounding lives here and only here: the
        // free-floating pill made the strip a second card on a screen that already has one, and a
        // square-shouldered strip made the stack look decapitated.
        val roof = RoundedCornerShape(topStart = PanelRadius, topEnd = PanelRadius)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Exactly the panels' inset, or the seam shows a step instead of a join.
                    .padding(horizontal = 12.dp)
                    .clip(roof)
                    .background(Layer2)
                    .drawBehind { drawPanelWalls(PanelRadius) }
                    // Enough headroom that the round shoulders sit above the text rather than
                    // through it: a 24 dp corner needs more than a label's own line height.
                    .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (delayed) Waiting else Live),
            )
            Text(
                text =
                    buildString {
                        append(if (delayed) "Writing this person after hold" else "Writing this person")
                        append("  ·  ")
                        append(formatClock(value.nextAttemptAtMs))
                        append("  ·  ")
                        append(value.note)
                    },
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The engine publishes draft prefixes at typing cadence. Reading that StateFlow in this small
 * subtree keeps each character from invalidating and remeasuring the complete transcript above.
 */
@Composable
private fun LiveRecorderOverlay(
    live: StateFlow<LiveTurn?>,
    chatId: String,
    activity: ChatActivity?,
    writingMemory: MemoryWork?,
    writingGlobalMemory: MemoryWork?,
    paused: Boolean,
    panelsOpen: Boolean,
    scheduledFollowUpVisible: Boolean,
    onOpenSettings: () -> Unit,
    overrideCount: Int,
    modifier: Modifier = Modifier,
) {
    val current by live.collectAsState()
    val liveHere = current?.takeIf { it.chatJid == chatId }
    var traceExpanded by remember(chatId) { mutableStateOf(true) }

    // A turn whose trace holds nothing but plumbing draws no panel, so it must not weld one to the
    // field either: the composer would wear a slab's square shoulders with nothing standing on them.
    val traceShowing = liveHere != null && !panelsOpen && liveHere.visibleTrace().isNotEmpty()
    if (traceShowing) {
        TracePanel(
            turn = liveHere!!,
            expanded = traceExpanded,
            onToggle = { traceExpanded = !traceExpanded },
            connectedAbove = scheduledFollowUpVisible,
            modifier = Modifier,
        )
    }
    FlightRecorder(
        turn = liveHere,
        activity = activity,
        writingMemory = writingMemory,
        writingGlobalMemory = writingGlobalMemory,
        paused = paused,
        onOpenSettings = onOpenSettings,
        overrideCount = overrideCount,
        // Anything sitting on top of the field closes the gap to it, whether that is a settings
        // panel, the trace, or the scheduled-reply strip standing alone. There is only one rule
        // here, not one per kind of panel.
        welded = panelsOpen || traceShowing || scheduledFollowUpVisible,
        modifier = modifier,
    )
}

@Composable
private fun ChatTopBar(
    detail: ChatDetail,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onImportChat: () -> Unit,
    onCreateMemory: () -> Unit,
    writingMemory: Boolean,
    onWritePerson: (() -> Unit)?,
    onBlockPerson: (() -> Unit)?,
    adminOperation: AccessOperation?,
    contactIsOwner: Boolean,
    onChangeAdmin: (AccessOperation) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<ChatMenuConfirmation?>(null) }
    // The engine only announces the write once the model call actually starts, which is a round trip
    // away. Without this the entry stays pressable for those first frames — exactly the window in
    // which it used to get pressed twice. The wait is bounded because the write may never start at
    // all (a refusal comes back as an error, not as work), and a menu wedged forever is worse.
    var requestedMemory by remember { mutableStateOf(false) }
    val writingNow by rememberUpdatedState(writingMemory)
    LaunchedEffect(requestedMemory) {
        if (!requestedMemory) return@LaunchedEffect
        withTimeoutOrNull(30_000) { snapshotFlow { writingNow }.first { it } }
        requestedMemory = false
    }
    val memoryBusy = writingMemory || requestedMemory
    Column(Modifier.fillMaxWidth().background(Layer1)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 5.dp, end = 12.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Back" }
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                BotLineIcon(BotIcon.ARROW_LEFT, color = TextHigh, modifier = Modifier.size(20.dp))
            }
            Avatar(detail.initial, detail.accent, detail.isGroup, size = 32)
            Spacer(Modifier.width(10.dp))
            Text(
                text = masked(detail.title),
                style = MaterialTheme.typography.titleMedium,
                color = TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                Box(
                    Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Chat actions" }
                        .clip(CircleShape)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    BotLineIcon(BotIcon.MORE, color = TextMid, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ChatActionItem("Import WhatsApp chat", BotIcon.MEMORY) {
                        menuOpen = false
                        onImportChat()
                    }
                    // The same consolidation the bot runs on its own, only now instead of at the
                    // next cadence point — so it also counts towards the global persona memory.
                    // While one is running the row says so and refuses: a second press does not
                    // queue anything, it starts a second billed call over the same conversation.
                    ChatActionItem(
                        label = if (memoryBusy) "Writing memory…" else "Create memory",
                        icon = BotIcon.MEMORY,
                        enabled = !memoryBusy,
                        busy = memoryBusy,
                    ) {
                        menuOpen = false
                        requestedMemory = true
                        onCreateMemory()
                    }
                    ChatActionItem(
                        label = if (detail.settings.paused) "Resume bot" else "Pause bot",
                        icon = BotIcon.POWER,
                    ) {
                        menuOpen = false
                        onTogglePause()
                    }
                    if (!detail.isGroup && onWritePerson != null) {
                        ChatActionItem("Write this person", BotIcon.SEND) {
                            menuOpen = false
                            onWritePerson()
                        }
                    }
                    if (!detail.isGroup && adminOperation != null) {
                        ChatActionItem(
                            if (adminOperation == AccessOperation.REMOVE) "Remove admin" else "Make admin",
                            BotIcon.PERSON,
                        ) {
                            menuOpen = false
                            confirmation = ChatMenuConfirmation.ADMIN
                        }
                    }
                    // An owner is an admin that cannot be demoted. Leaving the row out entirely made
                    // that look like a missing feature — most obviously in your own chat, where the
                    // one number that can never lose admin is the one you go looking for first.
                    if (!detail.isGroup && contactIsOwner) {
                        ChatActionItem("Owner · always admin", BotIcon.PERSON, enabled = false) {}
                    }
                    if (!detail.isGroup && onBlockPerson != null) {
                        ChatActionItem("Block contact", BotIcon.SHIELD, danger = true) {
                            menuOpen = false
                            confirmation = ChatMenuConfirmation.BLOCK
                        }
                    }
                }
            }
        }
    }

    confirmation?.let { pending ->
        val blocking = pending == ChatMenuConfirmation.BLOCK
        val removingAdmin = adminOperation == AccessOperation.REMOVE
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    when {
                        blocking -> "Block this contact?"
                        removingAdmin -> "Remove this admin?"
                        else -> "Make this contact an admin?"
                    },
                )
            },
            text = {
                Text(
                    when {
                        blocking -> "WhatsApp and the bot will both block this contact."
                        removingAdmin -> "This contact will no longer be allowed to use admin commands."
                        else -> "This contact will be allowed to control the bot with admin commands."
                    },
                )
            },
            confirmButton = {
                LinkButton(
                    when {
                        blocking -> "Block"
                        removingAdmin -> "Remove admin"
                        else -> "Make admin"
                    },
                ) {
                    confirmation = null
                    if (blocking) {
                        onBlockPerson?.invoke()
                    } else {
                        adminOperation?.let(onChangeAdmin)
                    }
                }
            },
            dismissButton = { LinkButton("Cancel") { confirmation = null } },
        )
    }
}

@Composable
private fun ChatActionItem(
    label: String,
    icon: BotIcon,
    danger: Boolean = false,
    enabled: Boolean = true,
    /** Draws the row as work in progress rather than as merely unavailable. */
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val color =
        when {
            busy -> Info
            !enabled -> TextLow
            danger -> MaterialTheme.colorScheme.error
            else -> TextHigh
        }
    DropdownMenuItem(
        enabled = enabled,
        text = {
            if (busy) {
                MemoryWritingText(label, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
            }
        },
        onClick = onClick,
        leadingIcon = { BotLineIcon(icon, color = color, modifier = Modifier.size(18.dp)) },
    )
}

private enum class ChatMenuConfirmation {
    BLOCK,
    ADMIN,
}

// ── Transcript ───────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(entry: ChatEntry.Bubble) {
    val botShape =
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomEnd = if (entry.continuesAfter) 16.dp else 4.dp,
            bottomStart = 16.dp,
        )
    val contactShape =
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomEnd = 16.dp,
            bottomStart = if (entry.continuesAfter) 16.dp else 4.dp,
        )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = if (entry.stacked) 2.dp else 8.dp,
                bottom = 0.dp,
            ),
        horizontalArrangement = if (entry.fromBot) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(if (entry.fromBot) botShape else contactShape)
                .background(if (entry.fromBot) BubbleBot else BubbleContact)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (entry.senderName != null && !entry.stacked) {
                Text(
                    text = entry.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Live,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (entry.media != null) {
                MediaBlock(entry.media, onBot = entry.fromBot)
            }
            val ink = if (entry.fromBot) BubbleBotInk else BubbleContactInk
            // The clock sits in the bubble's bottom-right corner. A text-only bubble is exactly as
            // wide as its text, so wrapping content puts it there by itself — but a media bubble is
            // stretched to full width by the media block above, and then a wrap-content Box parks
            // the clock at the right edge of a short caption instead, which is why it looked
            // stranded mid-bubble under voice notes.
            val metaReserve = if (entry.delivery != null) 92.dp else 52.dp
            if (entry.text.isNotBlank()) {
                Box(if (entry.media != null) Modifier.fillMaxWidth() else Modifier) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink,
                        modifier = Modifier.padding(end = metaReserve, bottom = 10.dp),
                    )
                    BubbleMetadata(
                        entry = entry,
                        ink = ink,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            } else {
                BubbleMetadata(entry = entry, ink = ink, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun BubbleMetadata(
    entry: ChatEntry.Bubble,
    ink: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatClock(entry.atMs),
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = .45f),
        )
        // A word, not tick marks. One tick against two ticks is a distinction nobody reads at 11sp
        // on a light bubble, and the only question actually being asked here is whether the other
        // person has seen it — so the state says so, and the colour says it from across the room.
        entry.delivery?.let { delivery ->
            val label =
                when (delivery) {
                    "read" -> "Read"
                    "delivered" -> "Unread"
                    "sent" -> "Sent"
                    "failed" -> "Failed"
                    else -> null
                }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        when (delivery) {
                            "read" -> LiveDark
                            "delivered" -> Broken.copy(alpha = .85f)
                            "failed" -> Broken
                            else -> ink.copy(alpha = .45f)
                        },
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * A photo, a voice note, a file — as much of it as still exists.
 *
 * Nothing is stored: media is analysed in memory and dropped, so there is no image to show and no
 * audio to play. The block says what arrived and prints the description the media model produced
 * underneath, in italics, because that description *is* what the bot saw. Drawing a play button here
 * would promise something the app cannot do.
 */
@Composable
private fun MediaBlock(
    media: MediaPlaceholder,
    onBot: Boolean,
) {
    val ink = if (onBot) BubbleBotInk else BubbleContactInk
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraSmall)
                .background(ink.copy(alpha = .07f))
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotLineIcon(
                icon =
                    when (media.kind) {
                        MediaPlaceholderKind.IMAGE -> BotIcon.IMAGE
                        MediaPlaceholderKind.VOICE -> BotIcon.MIC
                        MediaPlaceholderKind.VIDEO -> BotIcon.IMAGE
                        MediaPlaceholderKind.STICKER -> BotIcon.SPARK
                        else -> BotIcon.LIST
                    },
                color = ink.copy(alpha = .6f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text =
                    when {
                        media.kind == MediaPlaceholderKind.VOICE && onBot -> "You sent a voice message"
                        media.kind == MediaPlaceholderKind.VOICE -> "Voice message received"
                        onBot -> "You sent ${media.kind.word.lowercase()}"
                        else -> media.kind.word
                    },
                style = MaterialTheme.typography.labelMedium,
                color = ink.copy(alpha = .6f),
            )
        }
        if (media.description.isNotBlank()) {
            Text(
                text = media.description,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = ink.copy(alpha = .75f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DayBreakRule(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextLow,
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Layer1)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * A memory write, drawn as a rule straight through the conversation.
 *
 * The position is not decoration: everything above this line is inside the summary, everything below
 * it is still verbatim history. Collapsed it is one line, because a dozen expanded summaries would
 * bury the conversation they describe. Expanded it is editable, and an edit applies from the next
 * turn — never the one already running, whose copy was read before the edit existed.
 */
@Composable
private fun MemoryRule(
    entry: ChatEntry.Memory,
    budget: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (String) -> Unit,
    onJumpUp: (() -> Unit)?,
    onJumpDown: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(Info.copy(alpha = .35f)))
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotLineIcon(BotIcon.MEMORY, color = Info, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "MEMORY",
                    style = MaterialTheme.typography.labelSmall,
                    color = Info,
                )
            }
            Box(Modifier.weight(1f).height(1.dp).background(Info.copy(alpha = .35f)))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    buildString {
                        append("rev ${entry.revision}")
                        append(" · ${entry.characters.compact()}")
                        if (budget > 0) append("/${budget.compact()}")
                        if (entry.persona.isNotBlank()) append(" · ${entry.persona}")
                    },
                style = MaterialTheme.typography.labelSmall,
                color = TextLow,
            )
            if (onJumpUp != null || onJumpDown != null) {
                Spacer(Modifier.width(10.dp))
                JumpButton(BotIcon.CHEVRON, onJumpUp, flip = true)
                JumpButton(BotIcon.CHEVRON, onJumpDown, flip = false)
            }
        }
        AnimatedVisibility(visible = expanded) {
            MemoryEditor(entry = entry, onSave = onSave)
        }
    }
}

@Composable
private fun JumpButton(
    icon: BotIcon,
    onClick: (() -> Unit)?,
    flip: Boolean,
) {
    // A 48dp square around a 14dp glyph put most of the button in empty space, so the pair read as
    // two arrows loose on the divider rather than as a control beside its label. Round and tight.
    Box(
        Modifier
            .size(30.dp)
            .alpha(if (onClick == null) .25f else 1f)
            .semantics { contentDescription = if (flip) "Previous memory" else "Next memory" }
            .clip(CircleShape)
            .let { base -> onClick?.let { base.clickable(onClick = it) } ?: base },
        contentAlignment = Alignment.Center,
    ) {
        BotLineIcon(
            icon = icon,
            color = TextMid,
            modifier = Modifier.size(14.dp).rotate(if (flip) 180f else 0f),
        )
    }
}

@Composable
private fun MemoryEditor(
    entry: ChatEntry.Memory,
    onSave: (String) -> Unit,
) {
    var reveal by remember(entry.id, entry.revision) { mutableStateOf(false) }
    var editedDraft by remember(entry.id, entry.revision) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id, entry.revision) { reveal = true }
    val revealProgress by
        animateFloatAsState(
            targetValue = if (reveal) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = (650 + entry.text.length * 2).coerceAtMost(1_600),
                    easing = LinearOutSlowInEasing,
                ),
            label = "memoryReveal",
        )
    val revealedText =
        entry.text.take((entry.text.length * revealProgress).toInt().coerceIn(0, entry.text.length))
    val draft = editedDraft ?: entry.text
    val dirty = editedDraft?.let { it != entry.text } ?: false
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Layer1)
            .border(1.dp, Info.copy(alpha = .25f), MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        if (revealProgress < 1f && editedDraft == null) {
            // The animation is presentation-only. The editable value is always the complete
            // durable memory, so a fast tap can never save the currently revealed prefix.
            Text(
                text = revealedText,
                style = MaterialTheme.typography.bodyMedium,
                color = TextHigh,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            BasicTextField(
                value = draft,
                onValueChange = { editedDraft = it },
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = TextHigh,
                        fontFamily = FontFamily.Monospace,
                    ),
                cursorBrush = SolidColor(Info),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (dirty) "Applies from the next turn" else "Saved",
                style = MaterialTheme.typography.labelSmall,
                color = if (dirty) Waiting else TextLow,
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                Text(
                    text = "REVERT",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMid,
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { editedDraft = entry.text }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    text = "SAVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Info,
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { onSave(draft) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Private operator context added by hand at the exact point in the log where it was added.
 *
 * Collapsed to a single hairline by default — always, on every open. It is not a message: it does
 * not count towards real-message or memory-cadence totals. The model sees a normal user-history
 * turn explicitly identified as trusted operator context; newer messages only move the row upward. Memory compression can
 * absorb its guidance and ordinary retained-history pruning eventually removes the source row.
 */
@Composable
private fun PersonaSwitchRule(entry: ChatEntry.PersonaSwitch) {
    Row(
        // The surrounding bubbles already contribute their own top gap. Keeping this landmark
        // symmetric stops it looking attached to one persona instead of marking the boundary.
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotLineIcon(BotIcon.PERSON, color = Media, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${entry.from} → ${entry.to}",
            style = MaterialTheme.typography.labelSmall,
            color = TextMid,
        )
        Spacer(Modifier.width(8.dp))
        Text(formatClock(entry.atMs), style = MaterialTheme.typography.labelSmall, color = TextLow)
    }
}

@Composable
private fun InjectionRule(
    entry: ChatEntry.Injection,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onToggle)
                .padding(vertical = 5.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Media))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "CONTEXT",
                style = MaterialTheme.typography.labelSmall,
                color = Media,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatClock(entry.atMs),
                style = MaterialTheme.typography.labelSmall,
                color = TextLow,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Layer1)
                    .border(1.dp, Media.copy(alpha = .3f), MaterialTheme.shapes.small)
                    .padding(12.dp),
            ) {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextHigh,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Fixed here · compressed into memory · ages out with history",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextLow,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "REMOVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Broken,
                        modifier =
                            Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable(onClick = onDelete)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ── The recorder ─────────────────────────────────────────────────────────────

/** The breathing room around the field. The trace is welded to it through this same value. */
private val ComposerPadding = 10.dp

/** The radius of the field's foot. Whatever is welded to the field closes with the same curve. */
private val ComposerRadius = 24.dp

/** The radius of a panel's roof. The same curve as the field's foot, mirrored. */
private val PanelRadius = 24.dp

/**
 * The trace lines worth showing.
 *
 * Three engine steps narrate the plumbing rather than the turn, and a turn that has produced nothing
 * else yet has no panel to draw — which is also how the composer knows whether anything is standing
 * on its top edge.
 */
private fun LiveTurn.visibleTrace(): List<TraceLine> =
    trace.filterNot { line ->
        line.kind == TraceKind.STEP &&
            (
                line.text.startsWith("Loading reply context", ignoreCase = true) ||
                    line.text.startsWith("Reply context ready", ignoreCase = true) ||
                    line.text.startsWith("Starting AI processing", ignoreCase = true)
            )
    }

/**
 * The reasoning stream and every tool call, welded to the top of the field.
 *
 * Tool calls are drawn bright and everything else dim, because a tool call is the only line here
 * that costs money and changes state outside this process. The panel is capped and scrolls itself;
 * it disappears entirely when the turn ends, which is the point — this is what is happening, not a
 * log of what happened.
 *
 * It is deliberately not a card of its own. Two separate rounded slabs stacked with a gap between
 * them read as two unrelated things, one of which is covering the chat; the same shape pulled out
 * from behind the field reads as the field having more to say. Nothing about the trace ever crosses
 * into the field's own space: the bottom [TraceWeldClearance] of this card is empty by construction.
 */
@Composable
private fun TracePanel(
    turn: LiveTurn,
    expanded: Boolean,
    onToggle: () -> Unit,
    connectedAbove: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    var followTail by remember(turn.turnId) { mutableStateOf(true) }
    val visibleTrace = remember(turn.trace) { turn.visibleTrace() }
    // Streamed reasoning grows the last line instead of adding one, so the size alone stops moving
    // exactly when there is the most to follow. The tail's length is the other half of "something
    // new arrived".
    val lastIndex = visibleTrace.lastIndex
    val tailLength = visibleTrace.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(turn.turnId, state) {
        snapshotFlow { state.isScrollInProgress to state.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) followTail = !canScrollForward
            }
    }
    LaunchedEffect(lastIndex, tailLength, followTail) {
        if (lastIndex >= 0 && followTail) {
            // Clamp to the real bottom, including the tail of a long final paragraph. Placing the
            // final row at the top made its newest streamed text disappear below the viewport.
            state.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }
    AnimatedVisibility(
        modifier = modifier,
        visible = visibleTrace.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(100)),
    ) {
        // Same inset, same surface, same outline as the field, and square where the two meet, so it
        // is the field carried upwards rather than a second card hovering over it. It had the field's
        // full radius and a gap under it, which is exactly what made it read as a separate slab. The
        // whole card toggles, not just its header — the body is the biggest target on screen and
        // reaching for a 20 dp strip to close a panel that covers the chat is the wrong way round.
        // The strip above already carries the roof when it is showing; two rounded tops in a row
        // would put a bright shoulder in the middle of one surface.
        val roofRadius = if (connectedAbove) 0.dp else PanelRadius
        val shape = RoundedCornerShape(topStart = roofRadius, topEnd = roofRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(shape)
                .background(Layer2)
                .drawBehind { drawPanelWalls(roofRadius) }
                .clickable(onClick = onToggle),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TRACE", style = MaterialTheme.typography.labelSmall, color = TextMid)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${visibleTrace.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextLow,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "HIDE" else "SHOW",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMid,
                )
            }
            AnimatedVisibility(visible = expanded) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    items(
                        count = visibleTrace.size,
                        key = { index -> "${visibleTrace[index].atMs}:$index" },
                    ) { index ->
                        val line = visibleTrace[index]
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                text =
                                    when (line.kind) {
                                        TraceKind.TOOL -> "▸ "
                                        TraceKind.PROBLEM -> "! "
                                        TraceKind.STEP -> "· "
                                        TraceKind.REASONING -> "  "
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color =
                                    when (line.kind) {
                                        TraceKind.TOOL -> Live
                                        TraceKind.PROBLEM -> Broken
                                        else -> TextLow
                                    },
                            )
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color =
                                    when (line.kind) {
                                        TraceKind.TOOL -> Live
                                        TraceKind.PROBLEM -> Broken
                                        TraceKind.STEP -> TextMid
                                        TraceKind.REASONING -> TextLow
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The outline of the panel's surface where it runs down behind the field, at [inset] from the edges.
 *
 * Open at the top: the seam with the panel above is not an edge of anything, so it is not drawn.
 */
private fun DrawScope.weldSkirtPath(inset: Float): Path {
    val left = inset
    val right = size.width - inset
    val bottom = size.height - inset
    val radius =
        min(ComposerRadius.toPx() - inset, min(bottom, right - left) / 2f).coerceAtLeast(0f)
    return Path().apply {
        moveTo(left, 0f)
        lineTo(left, bottom - radius)
        arcTo(Rect(left, bottom - 2 * radius, left + 2 * radius, bottom), 180f, -90f, false)
        lineTo(right - radius, bottom)
        arcTo(Rect(right - 2 * radius, bottom - 2 * radius, right, bottom), 90f, -90f, false)
        lineTo(right, 0f)
    }
}

/** The panel's surface and its two walls, carried down past the pill to the field's own foot. */
private fun DrawScope.drawWeldSkirt() {
    val hairline = 1.dp.toPx()
    // Filling an open path closes it along the top, which is exactly the seam: the fill reaches the
    // panel above while the stroke stays off it.
    drawPath(weldSkirtPath(inset = 0f), Layer2)
    drawPath(weldSkirtPath(inset = hairline / 2f), OutlineSoft, style = Stroke(width = hairline))
}

/**
 * The field at the bottom, which is where you watch rather than type.
 *
 * It holds the same characters the other person is watching appear, at the same moment. When there
 * is no turn it holds the stage instead, or nothing at all — an empty field is the honest rendering
 * of an idle bot, and it is what the screen shows most of the time.
 */
@Composable
private fun FlightRecorder(
    turn: LiveTurn?,
    activity: ChatActivity?,
    writingMemory: MemoryWork?,
    writingGlobalMemory: MemoryWork?,
    paused: Boolean,
    onOpenSettings: () -> Unit,
    overrideCount: Int,
    welded: Boolean,
    modifier: Modifier = Modifier,
) {
    val stage = turn?.stage ?: activity?.stage
    val draftScroll = rememberScrollState()
    val showingDraft = turn?.draft?.isNotEmpty() == true
    LaunchedEffect(showingDraft) {
        if (!showingDraft) return@LaunchedEffect
        snapshotFlow { draftScroll.maxValue }.collect { bottom ->
            draftScroll.scrollTo(bottom)
        }
    }
    var nowMs by remember(activity?.dueAtMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stage, activity?.dueAtMs) {
        if (stage != ChatStage.WAITING || activity?.dueAtMs == null) return@LaunchedEffect
        while (activity.dueAtMs > nowMs) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val waitingText =
        activity?.dueAtMs?.let { dueAt ->
            val remainingSeconds = ((dueAt - nowMs) / 1_000L).coerceAtLeast(0L)
            "Replying ${formatClock(dueAt)} · ${remainingSeconds}s"
        } ?: "Waiting"
    // What the provider last said, when it said something bad. It replaces the countdown rather
    // than sitting beside it: "Replying 19:28 · 827s" while every call comes back 400 is the pill
    // reporting a plan it has no way of keeping.
    val problem = turn?.problem ?: activity?.problem
    val stageText =
        if (paused) {
            "Paused"
        } else if (problem != null && stage != ChatStage.FAILED) {
            problem
        } else when (stage) {
            null, ChatStage.IDLE -> null
            ChatStage.WAITING -> waitingText
            ChatStage.QUEUED -> "Queued"
            ChatStage.READING -> "Processing"
            ChatStage.ANALYZING -> "Processing media"
            ChatStage.THINKING -> (turn?.detail ?: activity?.detail)?.let { "$it · thinking" } ?: "Thinking"
            ChatStage.TYPING -> null
            ChatStage.SENDING ->
                (turn?.detail ?: activity?.detail)?.let { "Sending $it" } ?: "Sending"
            ChatStage.FAILED -> turn?.failure ?: activity?.detail ?: "failed"
        }
    // The chat's own memory first: it is the half that starts first and the half this screen shows
    // a rule for, so the two surfaces agree while it runs.
    val memoryText =
        when {
            writingMemory != null -> "Generating Chat Memory"
            writingGlobalMemory != null -> "Generating Global Memory"
            else -> null
        }
    val tone =
        when {
            paused -> Waiting
            problem != null -> Broken
            stage == null -> TextLow
            stage == ChatStage.FAILED -> Broken
            stage.isWorking -> Live
            else -> Waiting
        }
    // Round, because this is the conversation's own voice: what stands here is what she is about to
    // say. Every machine surface in this app is square. The pill stays a pill even with a panel on
    // top of it — squaring its top corners to weld the seam broke the one shape on the screen that
    // is supposed to be unbroken. What closes instead is the gap: whatever is above ends square and
    // lands flush on the field, so the two read as one surface without the pill giving up its shape.
    val fieldShape = RoundedCornerShape(ComposerRadius)
    Row(
        modifier
            .fillMaxWidth()
            // Pulled up by one pixel when welded: both edges carry the same hairline, and letting
            // them overlap leaves one line at the seam instead of two.
            .offset(y = if (welded) (-1).dp else 0.dp)
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = if (welded) 0.dp else ComposerPadding,
                bottom = ComposerPadding,
            )
            // The panel above does not stop where the field begins. Its walls carry on down past the
            // pill and close with the pill's own radius, so panel and field are one slab with the
            // pill set into its foot rather than a card parked on top of a separate field. Only the
            // surface reaches down here — the panel's own rows are laid out entirely above it, so
            // nothing readable or tappable ever lands in the field's space. Drawn rather than
            // clipped, because a border would put a hairline across the seam and hand the eye back
            // the separator this is here to remove.
            .then(if (welded) Modifier.drawBehind { drawWeldSkirt() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(fieldShape)
                .background(Layer2)
                .border(
                    width = 1.dp,
                    color = if (stage == ChatStage.FAILED) Broken.copy(alpha = .5f) else OutlineSoft,
                    shape = fieldShape,
                )
                .then(
                    if (showingDraft) {
                        // Status text never changes the field height. The actual answer is the one
                        // exception: it wraps while it is being typed, so long Human replies cannot
                        // run invisibly past the right edge. Extremely long drafts stop at a useful
                        // viewport and keep their newest line in view instead of covering the chat.
                        Modifier
                            .heightIn(min = 48.dp, max = 240.dp)
                            .animateContentSize(animationSpec = tween(140))
                    } else {
                        Modifier.height(48.dp)
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                turn != null && turn.draft.isNotEmpty() ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 56.dp, top = 12.dp, bottom = 12.dp)
                            .verticalScroll(draftScroll),
                    ) {
                    Text(
                        text = turn.draft + if (turn.stage == ChatStage.TYPING) "▌" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextHigh,
                    )
                    if (turn.stage == ChatStage.FAILED) {
                        Text(
                            text = turn.failure ?: "Reply failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = Broken,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    }

                stageText != null ->
                    Text(
                        text = stageText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tone,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 56.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                // Both halves of a memory write, in the one place that is already the answer to
                // "is anything happening". The transcript's own rule covers the chat memory and
                // nothing at all covered the synthesis after it, so the expensive half of the
                // operation ran under the words "Nothing running".
                memoryText != null ->
                    MemoryWritingText(
                        text = memoryText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 56.dp),
                    )

                else ->
                    Text(
                        text = "Nothing running",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextLow,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 56.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp)
                    .semantics { contentDescription = "Chat settings" }
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (overrideCount > 0) LiveMuted else Layer1),
                    contentAlignment = Alignment.Center,
                ) {
                    BotLineIcon(
                        BotIcon.SLIDERS,
                        color = if (overrideCount > 0) Live else TextMid,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** `2.1k` rather than `2148`: character counts are read as magnitudes, never as exact numbers. */
internal fun Int.compact(): String =
    when {
        this >= 10_000 -> "${this / 1_000}k"
        this >= 1_000 -> String.format(Locale.ENGLISH, "%.1fk", this / 1_000.0)
        else -> toString()
    }

private fun ChatEntry.stableKey(): String =
    when (this) {
        is ChatEntry.Bubble -> "message:$id"
        is ChatEntry.Memory -> "memory:$id"
        is ChatEntry.Injection -> "context:$id"
        is ChatEntry.PersonaSwitch -> "persona:$id"
        is ChatEntry.DayBreak -> "day:$atMs:$label"
    }
