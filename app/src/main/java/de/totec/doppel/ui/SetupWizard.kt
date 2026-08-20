package de.totec.doppel.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Tap target of the copy button in [PairingCodePanel]. */
private val CopyButtonSize = 40.dp

/** How far that button sits in from the panel's right edge. */
private val CopyButtonInset = 8.dp

/** One first-run form; every field writes through the same repositories used by Settings. */
@Composable
fun SetupWizard(
    setup: SetupState,
    busy: Boolean,
    onPair: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onComplete: (SetupDraft) -> Unit,
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var admin by rememberSaveable(setup.adminNumbers) {
        mutableStateOf(setup.adminNumbers.firstOrNull().orEmpty())
    }
    var extendedOpen by rememberSaveable { mutableStateOf(false) }
    var keySaveState by remember {
        mutableStateOf(if (setup.openRouterKeyConfigured) KeySaveState.SAVED else KeySaveState.IDLE)
    }
    var keySaveRevision by remember { mutableStateOf<Long?>(null) }
    val draft =
        SetupDraft(
            openRouterKey = key.trim(),
            sttApiKey = "",
            adminNumber = admin.trim(),
        )

    // The secret itself never comes back to Compose. A revision emitted only after the Keystore
    // write is the acknowledgement behind "Saved securely", so the UI cannot claim success early.
    LaunchedEffect(key) {
        val normalized = key.trim()
        if (normalized.isEmpty()) {
            keySaveState = if (setup.openRouterKeyConfigured) KeySaveState.SAVED else KeySaveState.IDLE
            return@LaunchedEffect
        }
        keySaveState = KeySaveState.WAITING
        delay(KEY_SAVE_DEBOUNCE_MS)
        keySaveRevision = setup.credentialRevision
        keySaveState = KeySaveState.SAVING
        onSaveKey(normalized)
    }
    LaunchedEffect(setup.credentialRevision, setup.openRouterKeyConfigured) {
        val requestedAt = keySaveRevision
        if (
            requestedAt != null &&
            setup.credentialRevision > requestedAt &&
            setup.openRouterKeyConfigured
        ) {
            keySaveRevision = null
            keySaveState = KeySaveState.SAVED
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = ScreenPadding, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Setup", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inset),
            contentPadding = PaddingValues(ScreenPadding, 6.dp, ScreenPadding, 28.dp),
        ) {
            item("setup") {
                ListGroup {
                    Column(Modifier.padding(14.dp)) {
                        BotTextField(
                            phone,
                            { phone = it },
                            "WhatsApp number",
                            keyboardType = KeyboardType.Phone,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (setup.whatsAppConnected) {
                            ConnectedButton()
                        } else {
                            PrimaryButton(
                                "Send code",
                                { onPair(phone.trim()) },
                                enabled = !busy && phone.isNotBlank(),
                            )
                        }
                        setup.pairingCode?.takeIf { !setup.whatsAppConnected }?.let { code ->
                            Spacer(Modifier.height(12.dp))
                            PairingCodePanel(code)
                            Text(
                                "WhatsApp → three-dot menu → Linked devices → Link a device → " +
                                    "Link with phone number.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        BotTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = "OpenRouter API key",
                            supporting =
                                when (keySaveState) {
                                    KeySaveState.IDLE -> null
                                    KeySaveState.WAITING, KeySaveState.SAVING -> "Saving…"
                                    KeySaveState.SAVED -> "Saved securely"
                                },
                            secret = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        // This is also the deliberate escape hatch: incomplete setup opens the bot
                        // with non-dismissible notices instead of trapping the operator here. The
                        // label says which of the two it is about to be, because "Finish setup" on
                        // a form with nothing filled in claims the setup is finished when pressing
                        // it is in fact the decision to leave it undone.
                        val keyReady = setup.openRouterKeyConfigured || key.isNotBlank()
                        val setupComplete = setup.whatsAppConnected && keyReady
                        PrimaryButton(
                            if (setupComplete) "Finish setup" else "Skip setup",
                            { onComplete(draft) },
                            enabled = !busy,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ListGroup {
                    ListRow(
                        title = "Extended settings",
                        subtitle = "Optional admin and battery recommendations",
                        onClick = { extendedOpen = !extendedOpen },
                        trailing = {
                            BotLineIcon(
                                BotIcon.CHEVRON,
                                Modifier.size(16.dp).rotate(if (extendedOpen) 90f else 0f),
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (extendedOpen) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            BotTextField(
                                admin,
                                { admin = it },
                                "Admin number (optional)",
                                keyboardType = KeyboardType.Phone,
                            )
                            Spacer(Modifier.height(8.dp))
                            ListRow(
                                title = "Battery usage",
                                subtitle = "App info → Battery → Unrestricted for reliable background replies",
                                value = "Recommended",
                                icon = BotIcon.BATTERY,
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedButton() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotLineIcon(BotIcon.CHECK, Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Successfully connected",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun PairingCodePanel(code: String) {
    val context = LocalContext.current
    val copyableCode = remember(code) { code.filter(Char::isLetterOrDigit) }
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500L)
            copied = false
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PAIRING CODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = CopyButtonInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Balances the copy button on the right, plus the inset that button was moved in
                // by, so the code itself stays centred in the panel rather than drifting left.
                Spacer(Modifier.width(CopyButtonSize + CopyButtonInset))
                Text(
                    // The hyphen is how WhatsApp prints it on the linked-device screen, and reading
                    // a code off one screen to type into another is the entire job of this panel.
                    // It is presentation only: [copyableCode] is what the clipboard gets, because a
                    // pasted hyphen is rejected by the very field this is meant to be pasted into.
                    copyableCode.chunked(4).joinToString("-"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                androidx.compose.foundation.layout.Box(
                    modifier =
                        Modifier
                            .size(CopyButtonSize)
                            .semantics {
                                contentDescription =
                                    if (copied) "Pairing code copied" else "Copy pairing code"
                            }
                            .clip(CircleShape)
                            .clickable {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                // This deliberately remains a normal clipboard entry so it can be
                                // pasted directly into WhatsApp's linked-device screen.
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Pairing code", copyableCode),
                                )
                                copied = true
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    BotLineIcon(
                        if (copied) BotIcon.CHECK else BotIcon.COPY,
                        Modifier.size(18.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private enum class KeySaveState { IDLE, WAITING, SAVING, SAVED }

private const val KEY_SAVE_DEBOUNCE_MS = 500L
