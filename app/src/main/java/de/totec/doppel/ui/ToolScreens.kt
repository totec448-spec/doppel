package de.totec.doppel.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AdminPayload
import de.totec.doppel.commands.WipeTarget
import de.totec.doppel.data.BotDataArchive
import de.totec.doppel.media.ApprovedMediaKind
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.SettingsCatalogs
import java.util.Locale

/**
 * One image the operator has asked to keep, held between the tap and the picker coming back.
 *
 * The persona travels with it because the panel's selection can move while the system picker is
 * in front of it, and the export has to write out the file that was actually tapped.
 */
private data class PendingImageExport(
    val personaKey: String,
    val assetId: String,
)

private data class SaveDocumentRequest(
    val fileName: String,
    val mimeType: String,
)

/**
 * Android's create-document contract with the type read off the file being saved.
 *
 * [ActivityResultContracts.CreateDocument] fixes the MIME type when the launcher is remembered,
 * which is one launcher per possible type or a picker that offers `.jpg` for a PNG. The stored
 * images keep whatever type they were approved as, so the type belongs in the launch.
 */
private class SaveDocumentContract : ActivityResultContract<SaveDocumentRequest, Uri?>() {
    override fun createIntent(
        context: Context,
        input: SaveDocumentRequest,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/** A name a file manager will accept, carrying the extension the stored type actually is. */
private fun saveRequest(
    displayName: String,
    mimeType: String,
): SaveDocumentRequest {
    val extension =
        when (mimeType.lowercase(Locale.ROOT)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
    val base =
        displayName
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString(separator = "")
            .trim('_')
            .take(60)
            .ifEmpty { "image" }
    return SaveDocumentRequest("$base.$extension", mimeType)
}

/** Compact persona selector plus the two fields that actually define a persona. */
@Composable
internal fun PersonasPanel(
    activePersona: String,
    personas: AdminPayload.Personas?,
    onAction: (AdminAction) -> Unit,
) {
    val entries = personas?.entries.orEmpty()
    var selectedKey by rememberSaveable { mutableStateOf(activePersona) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var voice by rememberSaveable { mutableStateOf("") }
    var voicePickerOpen by rememberSaveable { mutableStateOf(false) }
    val selected = entries.firstOrNull { it.key == selectedKey }

    LaunchedEffect(Unit) { onAction(AdminAction.ListPersonas) }
    LaunchedEffect(
        selectedKey,
        selected?.displayName,
        selected?.description,
        selected?.voice,
        creating,
    ) {
        if (!creating && selected != null) {
            name = selected.displayName
            prompt = selected.description
            voice = selected.voice.orEmpty()
        }
    }
    val key = if (creating) personaKeyFromName(name) else selectedKey

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            entries.forEach { persona ->
                TextButton(
                    onClick = {
                        creating = false
                        selectedKey = persona.key
                    },
                ) {
                    Text(if (!creating && persona.key == selectedKey) "• ${persona.displayName}" else persona.displayName)
                }
            }
            TextButton(
                onClick = {
                    creating = true
                    name = ""
                    prompt = ""
                    voice = ""
                },
            ) {
                Text("+ New")
            }
        }
        Spacer(Modifier.height(10.dp))
        BotTextField(name, { name = it.take(80) }, "Name")
        Spacer(Modifier.height(10.dp))
        BotTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = "Instructions",
            singleLine = false,
            minLines = 5,
        )
        Spacer(Modifier.height(10.dp))
        // The voice belongs to the persona, not to the app: this is who the voice notes sound
        // like. Empty means the persona has never been given one and speaks in the global voice.
        FloatAnchor(
            expanded = voicePickerOpen,
            onDismiss = { voicePickerOpen = false },
            panel = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(SettingsCatalogs.voices, key = { it.name }) { option ->
                        ChoiceLine(
                            label = option.name,
                            subtitle = option.description,
                            active = option.name.equals(voice, ignoreCase = true),
                        ) {
                            voice = option.name
                            voicePickerOpen = false
                        }
                    }
                }
            },
            row = {
                ListRow(
                    title = "Voice",
                    value = voice.ifBlank { "Fallback voice" },
                    icon = BotIcon.MIC,
                    onClick = { voicePickerOpen = true },
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = if (creating) "Create persona" else "Save persona",
            onClick = {
                onAction(
                    AdminAction.UpsertPersona(key, prompt, name.trim(), voice.takeIf(String::isNotBlank)),
                )
            },
            enabled = key.length >= 2 && name.isNotBlank() && prompt.isNotBlank(),
        )
        if (!creating && selected?.builtIn == false) {
            Spacer(Modifier.height(8.dp))
            HoldToConfirm(
                text = "Hold to delete",
                onConfirm = { onAction(AdminAction.DeletePersona(selectedKey)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Inline approved-image manager. Manual one-off sending was removed from Settings. */
@Composable
internal fun ApprovedImagesPanel(
    activePersona: String,
    personas: AdminPayload.Personas?,
    assets: AdminPayload.ImageAssets?,
    onAction: (AdminAction) -> Unit,
    onImportImage: (String, String, Uri) -> Unit,
    onExportImage: (ApprovedMediaKind, String, String, Uri) -> Unit,
) {
    val entries = personas?.entries.orEmpty()
    // The persona that is live right now, until the operator picks another one in here.
    //
    // This used to be seeded from the persona payload, which is the answer to the *previous* fetch
    // and is still in hand when the panel is reopened — so after switching persona this panel, the
    // references panel and the profile pictures all showed the persona before the switch, and only
    // the switch after that corrected them. Delayed by exactly one, every time.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val personaKey = picked ?: activePersona
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var imageName by rememberSaveable { mutableStateOf("") }
    var pendingName by remember { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val name = pendingName
            pendingName = null
            if (uri != null && personaKey.isNotBlank() && !name.isNullOrBlank()) {
                onImportImage(personaKey, name, uri)
                imageName = ""
            }
        }
    var pendingExport by remember { mutableStateOf<PendingImageExport?>(null) }
    val saver =
        rememberLauncherForActivityResult(SaveDocumentContract()) { destination ->
            val target = pendingExport
            pendingExport = null
            if (destination != null && target != null) {
                onExportImage(
                    ApprovedMediaKind.IMAGE,
                    target.personaKey,
                    target.assetId,
                    destination,
                )
            }
        }

    LaunchedEffect(personaKey) {
        if (personaKey.isNotBlank()) onAction(AdminAction.PersonaImages(personaKey))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(10.dp),
    ) {
        FloatAnchor(
            expanded = pickerOpen,
            onDismiss = { pickerOpen = false },
            panel = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(entries, key = { it.key }) { persona ->
                        ChoiceLine(
                            label = persona.displayName,
                            subtitle = persona.key,
                            active = persona.key == personaKey,
                        ) {
                            picked = persona.key
                            pickerOpen = false
                        }
                    }
                }
            },
            row = {
                ListRow(
                    title = entries.firstOrNull { it.key == personaKey }?.displayName ?: "Persona",
                    value = personaKey.ifBlank { null },
                    icon = BotIcon.PERSON,
                    onClick = { pickerOpen = true },
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        // Same one-line add as every other list in the app: name it, press the green square.
        InlineAddField(
            value = imageName,
            onValueChange = { imageName = it.take(120) },
            placeholder = "Image name",
            onAdd = {
                imageName.trim().takeIf(String::isNotEmpty)?.let {
                    pendingName = it
                    picker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp"))
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        when {
            assets == null || assets.personaKey != personaKey -> EmptyHint("Loading images...")
            assets.entries.isEmpty() -> EmptyHint("No approved images.")
            else -> ListGroup {
                assets.entries.forEachIndexed { index, asset ->
                    if (index > 0) RowSeparator()
                    ListRow(
                        title = asset.displayName,
                        subtitle = "${asset.mimeType} · ${formatBytes(asset.sizeBytes)}",
                        icon = BotIcon.IMAGE,
                        trailing = {
                            Row {
                                RowGlyphButton(BotIcon.DOWNLOAD, onClick = {
                                    pendingExport = PendingImageExport(personaKey, asset.assetId)
                                    saver.launch(saveRequest(asset.displayName, asset.mimeType))
                                })
                                RowGlyphButton(BotIcon.TRASH, onClick = {
                                    onAction(
                                        AdminAction.DeletePersonaImage(personaKey, asset.assetId),
                                    )
                                })
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Multi-image character-reference manager shared by built-in and custom personas. */
@Composable
internal fun CharacterReferencesPanel(
    activePersona: String,
    personas: AdminPayload.Personas?,
    references: AdminPayload.ImageReferences?,
    onAction: (AdminAction) -> Unit,
    onImportReferences: (String, List<Uri>) -> Unit,
    onExportImage: (ApprovedMediaKind, String, String, Uri) -> Unit,
) {
    val entries = personas?.entries.orEmpty()
    // Live persona first, operator's pick second — see [ApprovedImagesPanel] for what seeding this
    // from the persona payload did.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val personaKey = picked ?: activePersona
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty() && personaKey.isNotBlank()) {
                onImportReferences(personaKey, uris)
            }
        }
    var pendingExport by remember { mutableStateOf<PendingImageExport?>(null) }
    val saver =
        rememberLauncherForActivityResult(SaveDocumentContract()) { destination ->
            val target = pendingExport
            pendingExport = null
            if (destination != null && target != null) {
                onExportImage(
                    ApprovedMediaKind.CHARACTER_REFERENCE,
                    target.personaKey,
                    target.assetId,
                    destination,
                )
            }
        }

    LaunchedEffect(personaKey) {
        if (personaKey.isNotBlank()) onAction(AdminAction.PersonaImageReferences(personaKey))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(10.dp),
    ) {
        FloatAnchor(
            expanded = pickerOpen,
            onDismiss = { pickerOpen = false },
            panel = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(entries, key = { it.key }) { persona ->
                        ChoiceLine(
                            label = persona.displayName,
                            subtitle = persona.key,
                            active = persona.key == personaKey,
                        ) {
                            picked = persona.key
                            pickerOpen = false
                        }
                    }
                }
            },
            row = {
                ListRow(
                    title = entries.firstOrNull { it.key == personaKey }?.displayName ?: "Persona",
                    value = personaKey.ifBlank { null },
                    icon = BotIcon.PERSON,
                    onClick = { pickerOpen = true },
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Selected files are decoded, oriented, resized, flattened onto white, stripped of " +
                "metadata and stored privately as bounded JPEG references.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = "Add reference images",
            onClick = {
                picker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp"))
            },
            enabled = personaKey.isNotBlank() && (references?.entries?.size ?: 0) < 8,
        )
        Spacer(Modifier.height(10.dp))
        when {
            references == null || references.personaKey != personaKey ->
                EmptyHint("Loading references...")
            references.entries.isEmpty() ->
                EmptyHint("No character references. Non-character images can still be generated.")
            else -> {
                ListGroup {
                    references.entries.forEachIndexed { index, asset ->
                        if (index > 0) RowSeparator()
                        ListRow(
                            title = asset.displayName,
                            subtitle =
                                "API-ready ${asset.mimeType.substringAfter('/').uppercase(java.util.Locale.ROOT)} · " +
                                    formatBytes(asset.sizeBytes),
                            icon = BotIcon.IMAGE,
                            trailing = {
                                Row {
                                    RowGlyphButton(
                                        BotIcon.DOWNLOAD,
                                        onClick = {
                                            pendingExport =
                                                PendingImageExport(personaKey, asset.assetId)
                                            saver.launch(
                                                saveRequest(asset.displayName, asset.mimeType),
                                            )
                                        },
                                    )
                                    RowGlyphButton(
                                        BotIcon.TRASH,
                                        onClick = {
                                            onAction(
                                                AdminAction.DeletePersonaImageReference(
                                                    personaKey,
                                                    asset.assetId,
                                                ),
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HoldToConfirm(
                    text = "Hold to delete all references",
                    onConfirm = {
                        onAction(AdminAction.DeleteAllPersonaImageReferences(personaKey))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The faces a persona wears on WhatsApp, and how often they change.
 *
 * The starter set ships inside the APK and is already in the list the first time it is opened, so
 * the empty state below is what a fully emptied library looks like, not what a fresh install does.
 * The two rotation settings live here rather than three screens away in Realism: how often the
 * picture changes is only meaningful next to the pictures it changes between.
 */
@Composable
internal fun ProfilePicturesPanel(
    activePersona: String,
    personas: AdminPayload.Personas?,
    pictures: AdminPayload.ProfilePictures?,
    settings: List<UiSetting>,
    onAction: (AdminAction) -> Unit,
    onChange: (String, String) -> Unit,
    onImportPictures: (String, List<Uri>) -> Unit,
    onExportImage: (ApprovedMediaKind, String, String, Uri) -> Unit,
    catalogStatus: UiCatalogStatus,
    catalogError: String?,
) {
    val entries = personas?.entries.orEmpty()
    // Live persona first, operator's pick second — see [ApprovedImagesPanel] for what seeding this
    // from the persona payload did.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val personaKey = picked ?: activePersona
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var openSettingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty() && personaKey.isNotBlank()) {
                onImportPictures(personaKey, uris)
            }
        }
    var pendingExport by remember { mutableStateOf<PendingImageExport?>(null) }
    val saver =
        rememberLauncherForActivityResult(SaveDocumentContract()) { destination ->
            val target = pendingExport
            pendingExport = null
            if (destination != null && target != null) {
                onExportImage(
                    ApprovedMediaKind.PROFILE_PICTURE,
                    target.personaKey,
                    target.assetId,
                    destination,
                )
            }
        }
    val rotation =
        listOfNotNull(
            settings.firstOrNull { it.key == BotSettingKeys.PROFILE_PICTURE_ENABLED },
            settings.firstOrNull { it.key == BotSettingKeys.PROFILE_PICTURE_INTERVAL_DAYS },
        )

    LaunchedEffect(personaKey) {
        if (personaKey.isNotBlank()) onAction(AdminAction.PersonaProfilePictures(personaKey))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(10.dp),
    ) {
        FloatAnchor(
            expanded = pickerOpen,
            onDismiss = { pickerOpen = false },
            panel = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(entries, key = { it.key }) { persona ->
                        ChoiceLine(
                            label = persona.displayName,
                            subtitle = persona.key,
                            active = persona.key == personaKey,
                        ) {
                            picked = persona.key
                            pickerOpen = false
                        }
                    }
                }
            },
            row = {
                ListRow(
                    title = entries.firstOrNull { it.key == personaKey }?.displayName ?: "Persona",
                    value = personaKey.ifBlank { null },
                    icon = BotIcon.PERSON,
                    onClick = { pickerOpen = true },
                )
            },
        )
        if (rotation.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ListGroup {
                rotation.forEachIndexed { index, setting ->
                    if (index > 0) RowSeparator()
                    SettingControlRow(
                        setting = setting,
                        open = openSettingKey == setting.key,
                        onOpen = { openSettingKey = setting.key },
                        onClose = { openSettingKey = null },
                        onChange = onChange,
                        catalogStatus = catalogStatus,
                        catalogError = catalogError,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Selected files are cropped to a centred square, scaled and stored as small opaque " +
                "JPEGs without their original metadata. The picture changes when the persona " +
                "changes, and otherwise on the schedule above — every change is broadcast to all " +
                "contacts, so it stays deliberately slow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = "Add profile pictures",
            onClick = {
                picker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp"))
            },
            enabled = personaKey.isNotBlank() && (pictures?.entries?.size ?: 0) < MAX_PROFILE_PICTURES,
        )
        Spacer(Modifier.height(10.dp))
        when {
            pictures == null || pictures.personaKey != personaKey ->
                EmptyHint("Loading profile pictures...")
            pictures.entries.isEmpty() ->
                EmptyHint("No profile pictures. The picture on the account stays as it is.")
            else -> ListGroup {
                pictures.entries.forEachIndexed { index, asset ->
                    if (index > 0) RowSeparator()
                    ListRow(
                        title = asset.displayName,
                        subtitle =
                            "${asset.mimeType.substringAfter('/').uppercase(java.util.Locale.ROOT)} · " +
                                formatBytes(asset.sizeBytes),
                        // Which one is up right now, rather than which one is next: only a
                        // confirmed WhatsApp change is a fact, the next one is a plan.
                        value = if (asset.assetId == pictures.live) "On WhatsApp" else null,
                        icon = BotIcon.IMAGE,
                        trailing = {
                            Row {
                                RowGlyphButton(
                                    BotIcon.DOWNLOAD,
                                    onClick = {
                                        pendingExport =
                                            PendingImageExport(personaKey, asset.assetId)
                                        saver.launch(
                                            saveRequest(asset.displayName, asset.mimeType),
                                        )
                                    },
                                )
                                RowGlyphButton(
                                    BotIcon.TRASH,
                                    onClick = {
                                        onAction(
                                            AdminAction.DeletePersonaProfilePicture(
                                                personaKey,
                                                asset.assetId,
                                            ),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Mirrors ApprovedMediaAssetStore.MAX_PROFILE_PICTURES_PER_PERSONA, which the store enforces. */
private const val MAX_PROFILE_PICTURES = 12

/** Bounded Settings disclosure; Safety has no duplicate detail page. */
@Composable
internal fun SafetyInlinePanel(
    onAction: (AdminAction) -> Unit,
    state: AdminPayload.SafetyState?,
) {
    LaunchedEffect(Unit) { onAction(AdminAction.RefreshSafety) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(10.dp),
    ) {
        Text(
            state?.summary ?: "Checking live limits...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val locks = state?.activeLocks.orEmpty()
        if (state != null && locks.isEmpty()) {
            EmptyHint("No safety limit is active.", dense = true)
        } else {
            ListGroup {
                locks.forEachIndexed { index, lock ->
                    if (index > 0) RowSeparator()
                    ListRow(
                        title = lock.label,
                        subtitle = lock.expiresAtMs?.let { "Expires automatically" } ?: "Active",
                        value = "Disable",
                        icon = BotIcon.WARNING,
                        danger = true,
                        onClick = { onAction(AdminAction.ClearSafetyLock(lock.id)) },
                    )
                }
            }
        }
    }
}

/**
 * Everything destructive, on one page that does not scroll.
 *
 * It used to be a scrolling stack of full-width rows: backup as two rows, a "View memory" row
 * duplicating the Manage shortcut that already opens the memory browser, and a free-text field for
 * the chat to clear. The field was the actual bug — chats are keyed by the contact's `@lid` and the
 * digits of a typed phone number never match one, so a correctly typed number cleared nothing. It is
 * a list of the chats that exist now, which is the only set of values that can work. Backup collapsed
 * into one control because export and import are one decision with two directions.
 */
@Composable
internal fun DataPanel(
    personaData: AdminPayload.PersonaData?,
    chats: List<Pair<String, String>>,
    onExportData: (android.net.Uri) -> Unit,
    onImportData: (android.net.Uri) -> Unit,
    onAction: (AdminAction) -> Unit,
) {
    var targetChat by rememberSaveable { mutableStateOf("") }
    var chatPickerOpen by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(BotDataArchive.MIME_TYPE),
        ) { uri -> uri?.let(onExportData) }
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            restoreUri = uri
        }
    val chosen = chats.firstOrNull { it.first == targetChat }?.second
    LaunchedEffect(Unit) { onAction(AdminAction.ListPersonaData) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(10.dp),
    ) {
        SectionLabel("Backup")
        SplitPill(
            leftLabel = "Export",
            onLeft = {
                exportPicker.launch(BotDataArchive.defaultFileName(System.currentTimeMillis()))
            },
            rightLabel = "Import",
            onRight = { importPicker.launch(arrayOf(BotDataArchive.MIME_TYPE, "*/*")) },
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("One chat")
        ListGroup {
            FloatAnchor(
                expanded = chatPickerOpen,
                onDismiss = { chatPickerOpen = false },
                maxHeight = 300.dp,
                panel = {
                    if (chats.isEmpty()) {
                        EmptyHint("No chats yet.", dense = true)
                    } else {
                        LazyColumn {
                            items(chats, key = { it.first }) { (id, title) ->
                                ChoiceLine(label = title, active = id == targetChat) {
                                    targetChat = id
                                    chatPickerOpen = false
                                }
                            }
                        }
                    }
                },
            ) {
                ListRow(
                    title = "Chat",
                    value = chosen ?: "Pick one",
                    onClick = { chatPickerOpen = !chatPickerOpen },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldToConfirm(
                "Hold: history",
                { onAction(AdminAction.ResetChat(targetChat, false)) },
                Modifier.weight(1f),
                targetChat.isNotBlank(),
            )
            HoldToConfirm(
                "Hold: memory",
                { onAction(AdminAction.ClearChatMemory(targetChat)) },
                Modifier.weight(1f),
                targetChat.isNotBlank(),
            )
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("By personality")
        ListGroup {
            val entries = personaData?.entries.orEmpty()
            if (personaData == null) {
                EmptyHint("Loading personalities with data…", dense = true)
            } else if (entries.isEmpty()) {
                EmptyHint("No personality has chat or memory data.", dense = true)
            } else {
                entries.forEachIndexed { index, entry ->
                    DangerRow(
                        "Delete ${entry.displayName} data",
                        "Every chat and memory owned by ${entry.key}",
                        onConfirm = {
                            onAction(AdminAction.Wipe(WipeTarget.Persona(entry.key)))
                        },
                    )
                    if (index != entries.lastIndex) RowSeparator()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("Everything")
        ListGroup {
            DangerRow("Delete all data", "Every chat and all memories", onConfirm = {
                onAction(AdminAction.Wipe(WipeTarget.All))
            })
        }
    }
    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Restore from backup?") },
            text = { Text("This replaces every current chat, memory, persona, setting and image.") },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
            confirmButton = {
                HoldToConfirm("Hold to replace", {
                    restoreUri = null
                    onImportData(uri)
                })
            },
        )
    }
}

private fun personaKeyFromName(name: String): String =
    name.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
