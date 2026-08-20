package de.totec.doppel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.totec.doppel.app.digits
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AdminPayload
import de.totec.doppel.commands.BlockSource
import de.totec.doppel.settings.AppSettingKeys

@Composable
internal fun BlockedContactsPanel(
    blocks: AdminPayload.Blocks?,
    onAction: (AdminAction) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var confirmTarget by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onAction(AdminAction.ListBlocks) }
    CompactAccessColumn {
        val entries = blocks?.entries.orEmpty()
        when {
            blocks == null -> CompactEmpty("Loading…")
            entries.isEmpty() -> CompactEmpty("Nobody is blocked.")
            else -> entries.forEachIndexed { index, contact ->
                if (index > 0) RowSeparator()
                ListRow(
                    title = masked(contact.number),
                    value = if (contact.source == BlockSource.REMOTE) "WhatsApp" else "Local",
                    trailing = {
                        CircleIconButton(BotIcon.TRASH, onClick = {
                            onAction(AdminAction.UnblockContact(contact.number))
                        })
                    },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        EntryInput(input, { input = it }, "Phone number", KeyboardType.Phone) {
            input.trim().takeIf(String::isNotEmpty)?.let { confirmTarget = it }
        }
    }
    // Deliberately unmasked, here and in the admin confirmation below: this dialog exists so the
    // owner can check the number they typed a second ago before it takes effect, and a masked
    // number cannot be checked. Everything the app lists by itself is masked.
    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("Block contact?") },
            text = { Text(target, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                LinkButton("Block") {
                    onAction(AdminAction.BlockContact(target, ""))
                    input = ""
                    confirmTarget = null
                }
            },
            dismissButton = { LinkButton("Cancel") { confirmTarget = null } },
        )
    }
}

@Composable
internal fun AccessListPanel(
    list: UiAccessList,
    bindings: Map<String, String>,
    onChange: (String, List<String>) -> Unit,
) {
    var input by rememberSaveable(list.key) { mutableStateOf("") }
    var pendingAdmin by rememberSaveable(list.key) { mutableStateOf<String?>(null) }
    CompactAccessColumn {
        if (list.entries.isEmpty()) {
            CompactEmpty("Nobody on this list yet.")
        } else {
            list.entries.forEachIndexed { index, entry ->
                if (index > 0) RowSeparator()
                val locked = (list.protectedFirstEntry && index == 0) || entry in list.protectedEntries
                // The bound chat is a name. While numbers are hidden, printing it next to the
                // masked number would give back the identity the mask just took away, so the row
                // says only whether the number found its chat.
                val hidden = LocalHideNumbers.current
                val bound =
                    entry.boundChat(bindings)?.let { chat -> if (hidden) "Linked" else chat }
                ListRow(
                    title = masked(entry),
                    value = if (locked) "Protected" else bound ?: "Waiting",
                    trailing = if (locked) null else {
                        {
                            CircleIconButton(BotIcon.TRASH, onClick = {
                                onChange(list.key, list.entries.filterIndexed { item, _ -> item != index })
                            })
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        EntryInput(
            input,
            { input = it },
            if (list.groupList) "Group name" else "Number or JID",
            if (list.groupList) KeyboardType.Text else KeyboardType.Phone,
        ) {
            input.trim().takeIf(String::isNotEmpty)?.let { value ->
                if (list.key == AppSettingKeys.ADMIN_NUMBERS) {
                    pendingAdmin = value
                } else {
                    onChange(list.key, (list.entries + value).distinct())
                    input = ""
                }
            }
        }
    }
    pendingAdmin?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingAdmin = null },
            title = { Text("Make admin?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This number will be able to control the bot from WhatsApp.")
                    Text(target, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                LinkButton("Make admin") {
                    onChange(list.key, (list.entries + target).distinct())
                    input = ""
                    pendingAdmin = null
                }
            },
            dismissButton = { LinkButton("Cancel") { pendingAdmin = null } },
        )
    }
}

@Composable
private fun CompactAccessColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // The bottom inset matches the inset the last row above the input carries inside itself, so the
    // input sits in the middle of its own space. It used to be 2dp against that row's 13dp, which is
    // the lopsided gap under every access list.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 13.dp),
        content = content,
    )
}

@Composable
private fun CompactEmpty(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EntryInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onAdd: () -> Unit,
) {
    InlineAddField(
        value = value,
        onValueChange = onValueChange,
        placeholder = label,
        onAdd = onAdd,
        keyboardType = keyboardType,
    )
}

private fun String.boundChat(bindings: Map<String, String>): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    trimmed.digits()?.let { digits -> bindings[digits]?.let { return it } }
    return bindings[trimmed.lowercase()]
}
