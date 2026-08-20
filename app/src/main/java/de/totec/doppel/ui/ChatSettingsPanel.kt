package de.totec.doppel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.totec.doppel.data.ChatOverrides
import de.totec.doppel.ui.theme.Layer1
import de.totec.doppel.ui.theme.Live
import de.totec.doppel.ui.theme.TextHigh
import de.totec.doppel.ui.theme.TextLow
import de.totec.doppel.ui.theme.TextMid
import kotlin.math.roundToInt

/**
 * What this one conversation does differently, reachable from beside the field.
 *
 * Only identity, reply mode, proactivity and a group trigger can differ per conversation. Models,
 * typing speed and memory cadence remain global so the app has one authoritative configuration.
 *
 * Every row opens to reveal its options and every row's first option is "Global", which is not a
 * copy of today's global value but a genuine absence: change the global setting tomorrow and this
 * chat follows it. A row that is overriding says so, in green, without being opened.
 *
 * It **pushes** rather than covering, like every other block of settings in this app — see
 * [PushPanel]. As a modal sheet it was the one surface left that hid the conversation it was
 * configuring, and the transcript above it now reserves its height instead of disappearing under it.
 */
@Composable
fun ChatSettingsPanel(
    expanded: Boolean,
    detail: ChatDetail,
    personaOptions: List<Pair<String, String>>,
    memoryCount: Int,
    onPreviousMemory: (() -> Unit)?,
    onNextMemory: (() -> Unit)?,
    onSet: (key: String, value: String?) -> Unit,
    onAddNote: () -> Unit,
    onDeletePersonaChat: () -> Unit,
    onDismiss: () -> Unit,
    connectedAbove: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val settings = detail.settings
    var open by remember { mutableStateOf<String?>(null) }
    // Rows left open in a panel that was closed would reappear open the next time it is opened,
    // which reads as the panel having remembered a decision nobody made.
    if (!expanded && open != null) open = null

    PushPanel(
        expanded = expanded,
        modifier = modifier,
        maxHeight = PushClipHeight,
        connectedToRecorder = true,
        connectedAbove = connectedAbove,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
        ) {
            // No "Settings" heading: the panel is already what it says it is, and the word only
            // repeated the app's own top-level label inside a per-chat surface. The gap above the
            // first row is the panel's own shoulder, not a heading's leftover space.
            Spacer(Modifier.height(14.dp))

            SettingRow(
                label = "Persona",
                value = settings.persona ?: settings.globalPersona,
                overridden = settings.persona != null,
                expanded = open == "persona",
                onToggle = { open = if (open == "persona") null else "persona" },
            ) {
                OptionList(
                    options =
                        listOf(null to "Global · ${settings.globalPersona}") +
                            personaOptions.map { (id, name) -> id to name },
                    selected = settings.persona,
                    onPick = { onSet("persona", it) },
                )
            }

            SettingRow(
                label = "Reply speed",
                value = (settings.replyPreset ?: settings.globalReplyPreset).replaceFirstChar(Char::uppercase),
                overridden = settings.replyPreset != null,
                expanded = open == "timing",
                onToggle = { open = if (open == "timing") null else "timing" },
            ) {
                OptionList(
                    options =
                        listOf(
                            null to "Global · ${settings.globalReplyPreset}",
                            "instant" to "Instant — answers immediately",
                            "human" to "Human — reads, thinks and types",
                        ),
                    selected = settings.replyPreset,
                    onPick = { onSet(ChatOverrides.REPLY_PRESET, it) },
                )
            }

            SettingRow(
                label = "Proactivity",
                value = proactiveWord(settings.proactiveLevel ?: settings.globalProactiveLevel),
                overridden = settings.proactiveLevel != null,
                expanded = open == "proactive",
                onToggle = { open = if (open == "proactive") null else "proactive" },
            ) {
                ProactiveSlider(
                    level = settings.proactiveLevel,
                    globalLevel = settings.globalProactiveLevel,
                    onSet = { onSet("proactive", it) },
                )
            }

            MemorySettingRow(
                count = memoryCount,
                onPrevious = onPreviousMemory,
                onNext = onNextMemory,
            )

            // A trigger is only a question in a group. In a one-to-one chat she always answers, and
            // offering a trigger word there would suggest otherwise.
            if (detail.isGroup) {
                SettingRow(
                    label = "Trigger",
                    value =
                        (settings.groupTrigger ?: settings.globalGroupTrigger)
                            .ifBlank { "answers everyone" },
                    overridden = settings.groupTrigger != null,
                    expanded = open == "trigger",
                    onToggle = { open = if (open == "trigger") null else "trigger" },
                ) {
                    TriggerField(
                        current = settings.groupTrigger,
                        globalTrigger = settings.globalGroupTrigger,
                        onSet = { onSet(ChatOverrides.GROUP_TRIGGER, it) },
                    )
                }
            }

            ActionSettingRow(
                label = "Inject context",
                value = "Add to this persona",
                onClick = {
                    onDismiss()
                    onAddNote()
                },
            )
            DeleteSettingRow(
                onConfirm = {
                    onDismiss()
                    onDeletePersonaChat()
                },
            )
        }
    }
}

@Composable
private fun MemorySettingRow(
    count: Int,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Memories",
            style = MaterialTheme.typography.titleMedium,
            color = TextHigh,
            modifier = Modifier.width(104.dp),
        )
        Text(
            text = if (count == 0) "None yet" else "$count created",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
            modifier = Modifier.weight(1f),
        )
        MemoryArrow(previous = true, onClick = onPrevious)
        MemoryArrow(previous = false, onClick = onNext)
    }
}

@Composable
private fun MemoryArrow(
    previous: Boolean,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .size(48.dp)
            .semantics {
                contentDescription = if (previous) "Previous memory" else "Next memory"
            }
            .clip(CircleShape)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        BotLineIcon(
            icon = BotIcon.CHEVRON,
            color = if (onClick == null) TextLow.copy(alpha = .35f) else TextMid,
            modifier = Modifier.size(16.dp).rotate(if (previous) 180f else 0f),
        )
    }
}

/**
 * One slider for the whole proactivity decision: leftmost stop follows the global level, then 0..10.
 *
 * This replaced a paragraph of warning text plus a seven-entry option list. The scale is ordered
 * and small, which is what a slider is for; the paragraph said what the numbers already say, in a
 * sheet the operator opens repeatedly. The value is written on release, not per pixel, so dragging
 * across the range is one setting write rather than eleven.
 *
 * The range mirrors [BotSettingKeys.PROACTIVE_LEVEL] exactly. It used to stop at 5 while the global
 * setting went to 10, so the per-chat override silently could not reach the upper half of the scale
 * it was overriding.
 */
@Composable
private fun ProactiveSlider(
    level: Int?,
    globalLevel: Int,
    onSet: (String?) -> Unit,
) {
    // Stop 0 is "follow global"; stops 1..11 are levels 0..10, so the null override has a real
    // position on the track instead of living in a separate control.
    val positionOf = { current: Int? -> current?.let { it + 1 }?.toFloat() ?: 0f }
    var position by remember(level) { mutableFloatStateOf(positionOf(level)) }
    val selected = position.roundToInt()
    val effective = if (selected == 0) globalLevel else selected - 1

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotSlider(
            value = position,
            onValueChange = { position = it },
            modifier = Modifier.weight(1f),
            valueRange = 0f..11f,
            steps = 10,
            onValueChangeFinished = {
                onSet(if (selected == 0) null else (selected - 1).toString())
            },
        )
        Text(
            text = if (selected == 0) "Global $globalLevel" else effective.toString(),
            modifier = Modifier.padding(start = 12.dp).width(64.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected == 0) TextMid else Live,
        )
    }
}

/**
 * The last row of the sheet, shaped exactly like every row above it and only coloured differently.
 *
 * It used to be an outlined button in its own inset box, which is why it read as something pasted
 * on rather than part of the list. Same label column, same paddings, same trailing icon slot — the
 * danger is carried by colour and by the three-second hold, not by a different silhouette.
 */
@Composable
private fun DeleteSettingRow(onConfirm: () -> Unit) {
    // No leading glyph and no instruction line. Nothing else in this panel carries either, so both
    // were the only place a symbol and a caption appeared, and a hint printed under one row of seven
    // reads as decoration rather than as meaning. Red text, the sweeping fill and the three-second
    // hold already say destructive; the row that survives is the shape of every row above it.
    DangerRow(
        title = "Delete persona chat",
        subtitle = null,
        onConfirm = onConfirm,
        modifier = Modifier.padding(horizontal = 4.dp),
        icon = null,
    )
}

/**
 * A row that goes somewhere instead of opening options.
 *
 * Built out of the same three columns as [SettingRow] — label, value, trailing glyph — rather than
 * out of the app's generic list row, whose value hugs the right edge. Every value in this panel
 * starts at the same x; one that did not read as belonging to a different list.
 */
@Composable
private fun ActionSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = TextHigh,
            modifier = Modifier.width(104.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BotLineIcon(
            icon = BotIcon.CHEVRON,
            color = TextLow,
            // Pointing the way it leads. The rows above turn theirs downwards to open in place.
            modifier = Modifier.size(15.dp).rotate(-90f),
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    overridden: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = TextHigh,
                modifier = Modifier.width(104.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (overridden) Live else TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (overridden) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Live))
                Spacer(Modifier.width(10.dp))
            }
            BotLineIcon(
                icon = BotIcon.CHEVRON,
                color = TextLow,
                modifier = Modifier.size(15.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().background(Layer1).padding(vertical = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun OptionList(
    options: List<Pair<String?, String>>,
    selected: String?,
    onPick: (String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        options.forEach { (value, label) ->
            ChoiceLine(label = label, active = value == selected) { onPick(value) }
        }
    }
}

@Composable
private fun TriggerField(
    current: String?,
    globalTrigger: String,
    onSet: (String?) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current.orEmpty()) }
    Column(Modifier.padding(12.dp)) {
        BotTextField(draft, { draft = it.take(40) }, "Trigger word")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (current != null) LinkButton("Global · ${globalTrigger.ifBlank { "empty" }}") { onSet(null) }
            LinkButton("Set") { onSet(draft.trim()) }
        }
    }
}

/**
 * Context dropped into the log at the point you are looking at.
 *
 * Not a WhatsApp message, and deliberately not sent anywhere: the model reads it as an explicit
 * trusted operator-context turn, it holds its position, participates in memory compression and eventually
 * ages out through ordinary retained-history pruning.
 *
 * Content you write in, so it pushes ([PushPanel]) — the conversation this note is about stays on
 * screen while it is being written.
 */
@Composable
fun InjectPanel(
    expanded: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
    connectedAbove: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    // A cancelled note is discarded, not parked: reopening the panel to yesterday's half-sentence
    // is the behaviour of a draft, and this was never one.
    if (!expanded && text.isNotEmpty()) text = ""
    PushPanel(
        expanded = expanded,
        modifier = modifier,
        connectedToRecorder = true,
        connectedAbove = connectedAbove,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BotTextField(
                value = text,
                onValueChange = { text = it.take(600) },
                label = "Context nobody receives · ${text.length}/600",
                singleLine = false,
                minLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Cancel", onDismiss, Modifier.weight(1f))
                PrimaryButton("Add", { onSend(text) }, Modifier.weight(1f), enabled = text.isNotBlank())
            }
        }
    }
}

/** Spread across the full 0..10 scale; the old five words stopped where the old slider did. */
private fun proactiveWord(level: Int): String =
    when (level) {
        0 -> "never writes first"
        1, 2 -> "rarely"
        3, 4 -> "occasionally"
        5, 6 -> "regularly"
        7, 8 -> "often"
        else -> "very often"
    }
