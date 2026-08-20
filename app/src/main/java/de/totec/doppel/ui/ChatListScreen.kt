package de.totec.doppel.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.totec.doppel.engine.ChatActivity
import de.totec.doppel.engine.ChatStage
import de.totec.doppel.engine.LinkState
import de.totec.doppel.engine.isWorking
import de.totec.doppel.ui.theme.Asleep
import de.totec.doppel.ui.theme.AvatarPalette
import de.totec.doppel.ui.theme.Base
import de.totec.doppel.ui.theme.Broken
import de.totec.doppel.ui.theme.Hairline
import de.totec.doppel.ui.theme.Live
import de.totec.doppel.ui.theme.Layer2
import de.totec.doppel.ui.theme.TextHigh
import de.totec.doppel.ui.theme.TextLow
import de.totec.doppel.ui.theme.TextMid
import de.totec.doppel.ui.theme.Waiting
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The landing screen: everyone the bot has ever written to, most recent first.
 *
 * This replaces a dashboard of runtime phases and counters. The reason is that the bot runs exactly
 * one turn at a time, which makes this list a complete status display on its own — at most one row
 * is ever mid-operation, every other row is either waiting with a time on it or has nothing to say.
 * A separate status page could only repeat, less precisely, what one row here already shows.
 */
@Composable
fun ChatListScreen(
    rows: List<ChatRow>,
    /** The runtime's own word for where the link stands — "Connected", "Retrying", "Stopped". */
    status: String,
    tone: StatusTone,
    uptimeMs: Long?,
    /** Whether the link is deliberately down, and when it is due back. */
    linkPower: LinkPowerStatus,
    /** The bot is switched off entirely, so there is no schedule to report. */
    stopped: Boolean,
    limitNotice: de.totec.doppel.app.RuntimeLimitNotice?,
    /** Problems the owner can read and close; see [de.totec.doppel.app.RuntimeAlert]. */
    alerts: List<de.totec.doppel.app.RuntimeAlert> = emptyList(),
    onDismissAlert: (de.totec.doppel.app.AlertKind) -> Unit = {},
    onOpenChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onAddContact: (String) -> Unit,
    allowEveryDirectContact: Boolean,
    onAllowEveryDirectContactChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    /** The limit banner names a number; tapping it opens that number, not the settings root. */
    onOpenLimitSetting: (String?) -> Unit = { onOpenSettings() },
    /** Tap: start the bot if it is off, otherwise keep the link up a while longer. */
    onToggleConnection: () -> Unit,
    /** Long press: drop the link now. */
    onSleepNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the roster. Per-row clocks live inside the few visible rows that actually display a
    // duration, so one countdown no longer recomposes the header, notices and whole lazy list.
    val sections =
        remember(rows) {
            val live = rows.filter { it.activity != null }
            ChatListSections(
                active = live + rows.filter { it.activity == null && !it.quiet },
                quiet = rows.filter { it.activity == null && it.quiet },
            )
        }
    // One instant for every waiting row, because while the link is down that is genuinely
    // the only thing any of them are waiting for.
    val linkBackAtMs =
        linkPower.wakeAtMs.takeIf { !stopped && linkPower.state != LinkState.AWAKE }
    // No horizontal gesture of its own any more. This list is a page of the shell's pager, and a
    // drag handler here would consume the swipe before the pager ever saw it — the console would
    // jump open at the end of the gesture instead of following the finger across.
    Column(
        modifier
            .fillMaxSize()
            .background(Base),
    ) {
        ChatListHeader(
            status = status,
            tone = tone,
            uptimeMs = uptimeMs,
            linkPower = linkPower,
            stopped = stopped,
            chatCount = rows.size,
            onOpenSettings = onOpenSettings,
            onAddContact = onAddContact,
            allowEveryDirectContact = allowEveryDirectContact,
            onAllowEveryDirectContactChanged = onAllowEveryDirectContactChanged,
            onToggleConnection = onToggleConnection,
            onSleepNow = onSleepNow,
        )
        // A limit is a rule that is still in force, so it has no X: it is lifted in the setting
        // behind it and nowhere else, and the row says so instead of offering a close button that
        // would only hide the reason the bot is quiet.
        limitNotice?.let { notice ->
            NoticeRow(
                title = "Limit reached · ${notice.reason}",
                detail =
                    buildString {
                        append(notice.detail)
                        notice.untilMs?.let { append(" · free ").append(formatClock(it)) }
                        append(" · Open settings to lift it")
                        notice.settingLabel?.let { append(": ").append(it) }
                    },
                onClick = { onOpenLimitSetting(notice.settingKey) },
            )
        }
        // Event alerts can be acknowledged. Standing setup conditions deliberately have no X and
        // withdraw themselves only after the missing connection or credential is fixed.
        alerts.forEach { alert ->
            NoticeRow(
                title = alert.title,
                detail = alert.detail,
                // Always tappable, whether or not the alert names a destination. The row draws a
                // chevron either way, and one that led nowhere read as a dead app; an alert without
                // an exact target still belongs in settings, which is where the bot is started,
                // stopped and relinked.
                onClick = { onOpenLimitSetting(alert.settingKey) },
                onDismiss = if (alert.dismissible) ({ onDismissAlert(alert.kind) }) else null,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(sections.active, key = { it.chatId }) { row ->
                ChatListRow(
                    row = row,
                    linkBackAtMs = linkBackAtMs,
                    onOpen = { onOpenChat(row.chatId) },
                    onDelete = { onDeleteChat(row.chatId) },
                )
            }
            if (sections.quiet.isNotEmpty()) {
                item(key = "quiet-rule") { QuietRule(count = sections.quiet.size) }
                items(sections.quiet, key = { it.chatId }) { row ->
                    ChatListRow(
                        row = row,
                        linkBackAtMs = linkBackAtMs,
                        onOpen = { onOpenChat(row.chatId) },
                        onDelete = { onDeleteChat(row.chatId) },
                    )
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyRoster(tone == StatusTone.LIVE) }
            }
        }
    }
}

/**
 * The roster split into what the list draws: the rows above the rule (working first, then merely
 * recent) and the quiet tail below it.
 */
@Immutable
private data class ChatListSections(
    val active: List<ChatRow>,
    val quiet: List<ChatRow>,
)

@Composable
private fun ChatListHeader(
    status: String,
    tone: StatusTone,
    uptimeMs: Long?,
    linkPower: LinkPowerStatus,
    stopped: Boolean,
    chatCount: Int,
    onAddContact: (String) -> Unit,
    allowEveryDirectContact: Boolean,
    onAllowEveryDirectContactChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleConnection: () -> Unit,
    onSleepNow: () -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    // A switched-off bot is not asleep, whatever the last plan said before it went. The runtime
    // phase is the authority here: the power feed outlives the service that writes it.
    val asleep = !stopped && linkPower.state != LinkState.AWAKE
    // Sending the link to bed by hand only means something while it is up and the bot is on.
    val canSleep = !stopped && !asleep
    val haptics = LocalHapticFeedback.current
    // How far into the hold the finger is, 0 while nothing is pressed. Drawn as a fill
    // sweeping across the chip, which is the whole reason the gesture is discoverable:
    // a long press nobody can see is a long press nobody finds.
    val hold = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    // Set the moment the hold completes, so the release that follows is not also read as
    // a tap — the link would be put to sleep and woken again in the same gesture.
    var slept by remember { mutableStateOf(false) }
    LaunchedEffect(holding, canSleep) {
        if (holding && canSleep) {
            slept = false
            hold.animateTo(1f, tween(SLEEP_HOLD_MS, easing = LinearEasing))
            slept = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onSleepNow()
        } else {
            hold.animateTo(0f, tween(180, easing = LinearEasing))
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One status line for the whole app, so it has to carry the whole answer: "Disconnected" in
        // red while the bridge is in fact three seconds into reconnecting is the kind of half-truth
        // that gets a working link restarted by hand.
        // A link that is asleep on purpose outranks the runtime phase, which can only
        // see a socket that is down and calls it "Retrying". Blue rather than red for
        // the same reason: nothing is wrong, and a red dot every night would train the
        // eye to ignore the one that means something. Not grey either — grey is what
        // this app uses for "nothing to say", and a bot that has gone to bed until 09:05
        // is saying something.
        val statusColour =
            when {
                asleep -> Asleep
                tone == StatusTone.LIVE -> Live
                tone == StatusTone.WAITING -> Waiting
                else -> Broken
            }
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .drawBehind {
                    if (hold.value <= 0f) return@drawBehind
                    drawRect(
                        color = Asleep.copy(alpha = 0.20f),
                        size = size.copy(width = size.width * hold.value),
                    )
                }
                .pointerInput(canSleep) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val released = tryAwaitRelease()
                            holding = false
                            // A finger that left the chip is a cancelled gesture, not a tap.
                            if (released && !slept) onToggleConnection()
                        },
                    )
                }
                // The gesture above is raw pointer input, which carries no accessibility
                // action of its own — spelled out here so both halves stay reachable.
                .semantics {
                    role = Role.Button
                    onClick(label = "Keep the link up") {
                        onToggleConnection()
                        true
                    }
                    onLongClick(label = "Put the link to sleep") {
                        if (canSleep) onSleepNow()
                        true
                    }
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColour),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (asleep) sleepHeadline(linkPower.state) else status,
                style = MaterialTheme.typography.labelMedium,
                color = statusColour,
            )
            // Asleep, the useful number is when she is back — an uptime of a link that
            // is deliberately down is not a number anyone wants. Awake on a deadline,
            // it is when she goes: low mode keeps the link up for one listening window
            // past the last thing that happened, and until this said so out loud there
            // was no way to tell a window that had just been pushed out by an incoming
            // message from one that was about to lapse. It moves on its own whenever
            // the engine extends the window, which is the point. Otherwise the link is
            // usually up long before this screen is opened, so the number beside it is
            // the age of the connection, not the age of the app.
            val sleepAtMs = linkPower.wakeAtMs.takeIf { !asleep && !stopped }
            if (asleep) {
                linkPower.wakeAtMs?.let { wakeAt ->
                    Text(
                        text = "  ·  back ${formatClock(wakeAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMid,
                    )
                }
            } else if (sleepAtMs != null) {
                Text(
                    text = "  ·  offline ${formatClock(sleepAtMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMid,
                )
            } else if (uptimeMs != null) {
                Text(
                    text = "  ·  ${formatUptime(uptimeMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMid,
                )
            }
            Text(
                text = "  ·  $chatCount ${if (chatCount == 1) "chat" else "chats"}",
                style = MaterialTheme.typography.labelMedium,
                color = TextLow,
            )
        }
        Box {
            Box(
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Add contact" }
                    .clip(CircleShape)
                    .clickable { addOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                BotLineIcon(BotIcon.PLUS, color = TextMid, modifier = Modifier.size(21.dp))
            }
            DropdownMenu(
                expanded = addOpen,
                onDismissRequest = { addOpen = false },
                modifier = Modifier.width(252.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = Layer2,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, Hairline),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "Allow every person",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextHigh,
                            )
                            Text(
                                if (allowEveryDirectContact) {
                                    "Any contact or group may write"
                                } else {
                                    "Only allow-listed people and groups"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMid,
                            )
                        }
                        Switch(
                            checked = allowEveryDirectContact,
                            onCheckedChange = onAllowEveryDirectContactChanged,
                            colors =
                                SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                        )
                    }
                    if (!allowEveryDirectContact) {
                        Spacer(Modifier.height(10.dp))
                        // The allow-list input is relevant only while global direct-contact access
                        // is off; both controls still write the one shared access repository.
                        InlineAddField(
                            value = number,
                            onValueChange = { number = it },
                            placeholder = "Allow phone number",
                            keyboardType = KeyboardType.Phone,
                            onAdd = {
                                onAddContact(number)
                                number = ""
                                addOpen = false
                            },
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = "Settings" }
                .clip(CircleShape)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            BotLineIcon(BotIcon.SLIDERS, color = TextMid, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun ChatListRow(
    row: ChatRow,
    /** When the link is due back, or null while it is up. See [ActivityLabel]. */
    linkBackAtMs: Long?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val ticking = row.activity != null || row.scheduledFollowUpAtMs != null
    var nowMs by remember(ticking) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        while (ticking) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(IntOffset.Zero) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { rowSize = it }
            .pointerInput(row.chatId) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { position ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // DropdownMenu already positions itself relative to its parent anchor.
                        // Feeding the finger coordinates to its additional offset therefore added
                        // the row anchor twice and made the menu appear at an apparently random
                        // place. Move a one-pixel anchor to the finger instead.
                        menuAnchor =
                            IntOffset(
                                position.x.toInt().coerceIn(0, rowSize.width.coerceAtLeast(0)),
                                position.y.toInt().coerceIn(0, rowSize.height.coerceAtLeast(0)),
                            )
                        menuOpen = true
                    },
                )
            }
            .semantics {
                role = Role.Button
                onClick(label = "Open chat") {
                    onOpen()
                    true
                }
                onLongClick(label = "Chat actions") {
                    menuAnchor = IntOffset(rowSize.width / 2, rowSize.height / 2)
                    menuOpen = true
                    true
                }
            },
    ) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(initial = row.initial, accent = row.accent, isGroup = row.isGroup)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // Only a title that *is* a number is masked. A contact who has a name keeps
                        // it: this is the list you navigate by, and a column of identical prefixes
                        // would be a roster nobody can use.
                        text = masked(row.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.activity != null) {
                        Spacer(Modifier.width(10.dp))
                        ActivityLabel(row.activity, nowMs, linkBackAtMs)
                    } else if (row.scheduledFollowUpAtMs != null) {
                        Spacer(Modifier.width(10.dp))
                        ScheduledFollowUpLabel(row.scheduledFollowUpAtMs, nowMs)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text =
                            row.scheduledFollowUpNote
                                ?.takeIf(String::isNotBlank)
                                ?.let { "Writing this person · $it" }
                                ?: row.preview
                                    .takeIf(String::isNotBlank)
                                    ?.let { if (row.previewFromBot) "You: $it" else it }
                                ?: "No messages yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.scheduledFollowUpAtMs != null) Waiting else if (row.preview.isBlank()) TextLow else TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.scheduledFollowUpAtMs != null || row.preview.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatStamp(row.scheduledFollowUpAtMs ?: row.previewAtMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextLow,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(Modifier.padding(start = 78.dp).fillMaxWidth().height(1.dp).background(Hairline))
    }
        Box(
            Modifier
                .offset { menuAnchor }
                .size(1.dp),
        ) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = Layer2,
                border = BorderStroke(1.dp, Hairline),
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BotLineIcon(BotIcon.TRASH, color = Broken, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete chat", color = Broken)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    onClick = {
                        menuOpen = false
                        confirmingDelete = true
                    },
                )
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${masked(row.title)}?") },
            text = {
                Text(
                    "Deletes this chat, its complete local history and every chat memory. " +
                        "Global persona memory stays intact.",
                )
            },
            confirmButton = {
                HoldToConfirm(
                    text = "Hold to delete",
                    onConfirm = {
                        confirmingDelete = false
                        onDelete()
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep chat") }
            },
            containerColor = Layer2,
        )
    }
}

@Composable
private fun ScheduledFollowUpLabel(
    dueAtMs: Long,
    nowMs: Long,
) {
    val remaining = (dueAtMs - nowMs).coerceAtLeast(0L)
    val label =
        when {
            remaining < 60L * 60_000L -> "in ${((remaining + 59_999L) / 60_000L).coerceAtLeast(1L)} min"
            remaining < 24L * 60L * 60_000L -> {
                val hours = remaining / (60L * 60_000L)
                val minutes = (remaining / 60_000L) % 60L
                if (minutes == 0L) "in ${hours}h" else "in ${hours}h ${minutes}m"
            }
            else -> formatClock(dueAtMs)
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Waiting))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "writing $label",
            style = MaterialTheme.typography.labelSmall,
            color = Waiting,
            maxLines = 1,
        )
    }
}

/**
 * One red row above the chats, for a problem that is worth interrupting the list for.
 *
 * The trailing control is the whole distinction the owner has to be able to read at a glance: an X
 * means "you have seen it, it is gone", and a chevron means "this is still in force and lives in
 * settings". A row therefore takes either [onDismiss] or [onClick], and drawing both would put the
 * two meanings on the same row.
 */
@Composable
private fun NoticeRow(
    title: String,
    detail: String,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Broken.copy(alpha = .12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = if (onDismiss != null) 4.dp else 12.dp)
            .padding(vertical = if (onDismiss != null) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotLineIcon(BotIcon.WARNING, Modifier.size(18.dp), Broken)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Broken,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
        }
        if (onDismiss != null) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .semantics { contentDescription = "Dismiss notification" }
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                BotLineIcon(
                    BotIcon.CLOSE,
                    Modifier.size(13.dp),
                    MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                )
            }
        } else {
            BotLineIcon(BotIcon.CHEVRON_RIGHT, Modifier.size(16.dp), Broken)
        }
    }
}

/**
 * The one word that says what the bot intends with this person.
 *
 * A waiting row is the common case and carries the time the reply is due, because "waiting" without
 * a time is indistinguishable from "stuck". A working row pulses instead: there is nothing useful to
 * count towards, and the pulse is what separates "thinking" from a label that has frozen.
 *
 * While the link is down, every waiting row names the same instant: the one the link comes back on.
 * Nothing can be sent before then, so a per-chat due time from before the link dropped would be a
 * promise the transport cannot keep — and once the link is up the queue is worked one chat at a
 * time anyway, which is why the rows sharing one time is the honest picture rather than a shortcut.
 */
@Composable
private fun ActivityLabel(
    activity: ChatActivity,
    nowMs: Long,
    linkBackAtMs: Long?,
) {
    // A provider error outranks the countdown. "replying 19:28" next to a call that came back 400
    // is the row lying about what it is doing, and it is the case the owner most needs to see.
    val text =
        activity.problem?.take(28)
            ?: when (activity.stage) {
            // The later of the two, not the link's instant flat: a delay drawn for half an
            // hour is not shortened by the link happening to come back in ten minutes.
            ChatStage.WAITING ->
                listOfNotNull(activity.dueAtMs, linkBackAtMs).maxOrNull()
                    ?.let { "replying ${formatClock(it)}" }
                    ?: "waiting"

            ChatStage.QUEUED -> linkBackAtMs?.let { "replying ${formatClock(it)}" } ?: "queued"
            ChatStage.READING -> "reading"
            ChatStage.ANALYZING -> activity.detail?.let { "looking · $it" } ?: "looking"
            ChatStage.THINKING -> "thinking"
            ChatStage.TYPING -> activity.detail?.let { "typing $it" } ?: "typing"
            ChatStage.SENDING -> "sending"
            ChatStage.FAILED -> activity.detail?.take(28) ?: "failed"
            ChatStage.IDLE -> return
        }
    val tone =
        when {
            activity.problem != null -> Broken
            activity.stage == ChatStage.FAILED -> Broken
            activity.stage.isWorking -> Live
            else -> Waiting
        }
    val pulse =
        if (activity.stage.isWorking) {
            val transition = rememberInfiniteTransition(label = "working")
            transition.animateFloat(
                initialValue = 1f,
                targetValue = .45f,
                animationSpec =
                    infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
                label = "workingAlpha",
            ).value
        } else {
            1f
        }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(pulse)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            maxLines = 1,
        )
        if (activity.problem == null && activity.stage == ChatStage.WAITING && activity.dueAtMs != null) {
            val remaining = ((activity.dueAtMs - nowMs) / 1_000L).coerceAtLeast(0L)
            if (remaining in 1..600) {
                Text(
                    text = " (${remaining}s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextLow,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun Avatar(
    initial: String,
    accent: Int,
    isGroup: Boolean,
    size: Int = 46,
) {
    val colour = AvatarPalette[accent.mod(AvatarPalette.size)]
    Box(
        Modifier
            .size(size.dp)
            // A group is square, a person is round — the same rule the whole app runs on, applied
            // to the one place where telling the two apart matters before reading a single word.
            .clip(if (isGroup) MaterialTheme.shapes.small else CircleShape)
            .background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = .92f),
        )
    }
}

@Composable
private fun QuietRule(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Hairline))
        Text(
            text = "  QUIET · $count  ",
            style = MaterialTheme.typography.labelSmall,
            color = TextLow,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Hairline))
    }
}

@Composable
private fun EmptyRoster(connected: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.titleLarge,
            color = TextMid,
        )
        Text(
            text =
                if (connected) {
                    "A chat appears here the first time someone writes."
                } else {
                    "Connect the bridge in settings, then wait for the first message."
                },
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
        )
    }
}

/**
 * How long the status line has to be held to send the link to bed.
 *
 * Long enough that it cannot be a mis-tap on a control whose ordinary tap does the
 * opposite, short enough to be worth waiting out — and the whole of it is drawn as a
 * fill sweeping across the chip, so the wait is also the explanation.
 */
private const val SLEEP_HOLD_MS = 650

// ── Time formatting ──────────────────────────────────────────────────────────

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * What a link that is down on purpose is called.
 *
 * Two words for two different reasons, because the answer to "why is it not answering"
 * is different: dozing is the low power schedule between two online sessions, sleeping
 * is the quiet hours and happens in both modes.
 */
private fun sleepHeadline(state: LinkState): String =
    when (state) {
        LinkState.DOZING -> "Dozing"
        LinkState.SLEEPING -> "Sleeping"
        LinkState.AWAKE -> "Connected"
    }

/** `19:52` — the wall clock, which is what "when will it answer" is actually asking. */
internal fun formatClock(atMs: Long): String =
    Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(CLOCK)

/** Time for today, a date for anything older: a list of `14:03` over a week tells you nothing. */
internal fun formatStamp(atMs: Long): String {
    val zoned = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (zoned.toLocalDate()) {
        today -> zoned.format(CLOCK)
        today.minusDays(1) -> "Yesterday"
        else -> zoned.format(DAY)
    }
}

/** `4h 12m`, `3d 5h`, `41s` — the largest two units that are non-zero, never more. */
internal fun formatUptime(ms: Long): String {
    val seconds = ms / 1_000L
    val days = seconds / 86_400L
    val hours = (seconds % 86_400L) / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
