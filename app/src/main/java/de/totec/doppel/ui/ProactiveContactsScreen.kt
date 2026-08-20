package de.totec.doppel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AdminPayload
import de.totec.doppel.commands.ProactiveContact
import kotlin.math.roundToInt

/** Compact per-contact overrides; Settings already supplies the surrounding navigation. */
@Composable
internal fun ProactiveContactsPanel(
    contacts: AdminPayload.ProactiveContacts?,
    outcome: AdminPayload.WriteContactOutcome?,
    onAction: (AdminAction) -> Unit,
    onClearOutcome: () -> Unit,
    onCancelWrite: (String) -> Unit,
    limitNotice: de.totec.doppel.app.RuntimeLimitNotice? = null,
    onOpenLimitSettings: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { onAction(AdminAction.ListProactiveContacts) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = PushClipHeight),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (contacts == null) item(key = "loading") { EmptyHint("Loading contacts…") }
        if (contacts?.contacts?.isEmpty() == true) item(key = "empty") { EmptyHint("No contacts yet.") }
        items(contacts?.contacts.orEmpty(), key = { it.chatId }) { contact ->
            ProactiveContactRow(
                contact = contact,
                onLevel = { onAction(AdminAction.SetProactiveOverride(contact.chatId, it)) },
                onClearOverride = { onAction(AdminAction.ClearProactiveOverride(contact.chatId)) },
                onWrite = { onAction(AdminAction.WriteContactNow(contact.chatId)) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    outcome?.let { result ->
        WriteContactDialog(
            outcome = result,
            onConfirm = { note ->
                onAction(AdminAction.WriteContactNow(result.target, result.confirmation, note))
            },
            onDismiss = onClearOutcome,
            onCancelRunning = {
                onCancelWrite(result.target)
                onClearOutcome()
            },
            limitNotice = limitNotice,
            onOpenLimitSettings =
                onOpenLimitSettings?.let {
                    {
                        onClearOutcome()
                        it()
                    }
                },
        )
    }
}

@Composable
private fun ProactiveContactRow(
    contact: ProactiveContact,
    onLevel: (Int) -> Unit,
    onClearOverride: () -> Unit,
    onWrite: () -> Unit,
) {
    var level by remember(contact.chatId, contact.level) { mutableFloatStateOf(contact.level.toFloat()) }
    val name = contactLabel(contact.displayName, contact.chatId)
    val value = when {
        contact.blocked -> "Blocked"
        !contact.hasHistory -> "No history"
        else -> level.roundToInt().toString()
    }
    // Two lines, not five columns. A name, a track and two buttons crammed into one row left the
    // slider about a thumb wide, and neither the name nor the number had room to be read.
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                // Stated, not inherited: nothing here provides a content colour, so an unset one
                // came out black on a black list.
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
                color =
                    if (contact.blocked || !contact.hasHistory) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotSlider(
                value = level,
                onValueChange = { level = it },
                modifier = Modifier.weight(1f),
                valueRange = 0f..10f,
                steps = 9,
                enabled = !contact.blocked,
                onValueChangeFinished = { onLevel(level.roundToInt()) },
            )
            if (contact.overridden) {
                TextButton(onClick = onClearOverride, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Global", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(
                onClick = onWrite,
                enabled = !contact.blocked,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Write", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * What to call a contact in this list.
 *
 * This is the app's own contact log, so while numbers are hidden the name is not a fallback: it
 * names the same person the masked number does. The masked number is shown instead, which is what
 * the switch promises — a dialling prefix and nothing that identifies anybody.
 */
@Composable
private fun contactLabel(displayName: String?, chatId: String): String =
    if (LocalHideNumbers.current) {
        maskNumber(chatId.substringBefore('@'))
    } else {
        displayName?.takeIf(String::isNotBlank) ?: chatId.substringBefore('@')
    }

/** How much of a dictated opener is still an idea rather than a script to read out. */
private const val MaxOutreachNoteCharacters = 400

/**
 * Unsolicited outreach still needs an explicit final confirmation.
 *
 * Deliberately not masked: this is the last thing between a tap and a real message to a real
 * person, and a confirmation that cannot be checked is not a confirmation. Masking applies to what
 * the app lists on its own, not to the one line that has to be read before saying yes.
 */
@Composable
internal fun WriteContactDialog(
    outcome: AdminPayload.WriteContactOutcome,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    onCancelRunning: (() -> Unit)? = null,
    limitNotice: de.totec.doppel.app.RuntimeLimitNotice? = null,
    onOpenLimitSettings: (() -> Unit)? = null,
) {
    val name = outcome.displayName?.takeIf(String::isNotBlank) ?: outcome.target.substringBefore('@')
    var sending by remember(outcome.target, outcome.confirmation) { mutableStateOf(false) }
    // Open, not behind a link. Leaving it empty is the whole old behaviour, so the box costs a
    // glance and nothing else — and a field you have to find first is a field nobody uses.
    var note by rememberSaveable(outcome.target) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(if (outcome.confirmation != null) "Write $name?" else "Outreach") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                outcome.detail?.let { Text(it) }
                if (outcome.confirmation != null) {
                    Text(
                        outcome.target,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BotTextField(
                        value = note,
                        onValueChange = { note = it.take(MaxOutreachNoteCharacters) },
                        label = "What to write about (optional)",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        supporting =
                            if (note.isBlank()) {
                                "Leave empty and the opener is entirely theirs."
                            } else {
                                "Your idea, their words — it is rephrased, not read out."
                            },
                    )
                }
            }
        },
        confirmButton = {
            if (outcome.confirmation != null) {
                LinkButton(if (sending) "Sending…" else "Send now", enabled = !sending) {
                    sending = true
                    onConfirm(note.trim().takeIf(String::isNotEmpty))
                }
            } else {
                if (limitNotice != null && onOpenLimitSettings != null) {
                    LinkButton(
                        "Open ${limitNotice.settingLabel ?: "Safety limits"}",
                        onClick = onOpenLimitSettings,
                    )
                } else {
                    LinkButton("Close", onClick = onDismiss)
                }
            }
        },
        dismissButton = {
            if (outcome.confirmation != null) {
                LinkButton(
                    "Cancel",
                    onClick = if (sending && onCancelRunning != null) onCancelRunning else onDismiss,
                )
            } else if (limitNotice != null && onOpenLimitSettings != null) {
                LinkButton("Close", onClick = onDismiss)
            }
        },
    )
}
