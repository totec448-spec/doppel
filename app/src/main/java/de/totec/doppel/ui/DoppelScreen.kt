package de.totec.doppel.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.totec.doppel.commands.AdminAction
import de.totec.doppel.commands.AccessList
import de.totec.doppel.commands.AccessOperation
import de.totec.doppel.commands.AdminPayload
import de.totec.doppel.commands.AdminResult
import de.totec.doppel.commands.MemorySummary
import de.totec.doppel.commands.WipeTarget
import de.totec.doppel.engine.chatWork
import de.totec.doppel.engine.personaWork
import de.totec.doppel.domain.BridgeConnectionState
import de.totec.doppel.app.SettingsTargets
import de.totec.doppel.app.accent
import de.totec.doppel.app.initial
import de.totec.doppel.runtime.RuntimePhase
import de.totec.doppel.settings.BotSettingKeys
import de.totec.doppel.settings.AppSettingKeys
import de.totec.doppel.settings.SettingsCatalogs
import de.totec.doppel.ui.theme.Waiting
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val SAFETY_RESULT_ID = "safety"
private const val PROACTIVE_RESULT_ID = "proactive"
private const val PERSONAS_RESULT_ID = "personas"
private const val IMAGES_RESULT_ID = "images"
private const val REFERENCES_RESULT_ID = "references"
private const val PROFILE_PICTURES_RESULT_ID = "profile-pictures"

private data class PendingAdminChange(
    val number: String,
    val operation: AccessOperation,
)

/**
 * The app shell: a list of people, a conversation, and a console behind them.
 *
 * The app used to open on a dashboard of runtime phases and counters, with the conversations
 * nowhere. That had it backwards. What this thing does all day is talk to specific people, one at a
 * time, so the front of the app is now the list of those people and everything that was a tab is one
 * level down behind the gear — still all of it, settings, access, tools and the log, none of it
 * removed, just no longer the first thing in the way.
 *
 * Three destinations, and back always means the obvious thing: chat → list, console → list.
 */
@Composable
fun DoppelScreen(
    controller: AppUiController,
    chats: ChatsController,
    onStartService: () -> Unit,
) {
    val appContext = LocalContext.current
    val state by controller.state.collectAsState()
    val chatOperationError by chats.operationError.collectAsState()
    // Read once at the top: the same list answers "is this chat writing", "is this persona
    // writing" and "is anything writing", and those three questions are asked on three different
    // surfaces that must not disagree with each other.
    val memoryWork by chats.memoryWork.collectAsState()
    // The list and the console are two pages of one surface, not two screens that swap. A swap
    // could only play a canned slide after the finger had already left the glass; a pager tracks
    // the drag in both directions for as long as it lasts and settles wherever it was pointing.
    // Both pages stay composed, so coming back to the console lands on the row and scroll offset
    // it was left at instead of at the top.
    val shell = rememberPagerState(initialPage = 0) { 2 }
    val shellScope = rememberCoroutineScope()
    val consoleOpen by remember { derivedStateOf { shell.currentPage == 1 } }
    val openConsole = { shellScope.launch { shell.animateScrollToPage(1) }; Unit }
    val closeConsole = { shellScope.launch { shell.animateScrollToPage(0) }; Unit }
    var openChatId by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsListState = rememberLazyListState()
    // A setting another surface asked for by name, held until the settings page has revealed it.
    var focusSettingKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Sending the console page an operator who is standing in a chat means taking the chat off the
    // screen first: the chat is an overlay over the whole pager, so scrolling the pager underneath
    // it changed nothing anybody could see — the tap read as a dead button that ate the dialog.
    val openSettings: (String?) -> Unit = { settingKey ->
        focusSettingKey = settingKey
        openChatId = null
        openConsole()
    }
    // Secret input is intentionally not written into saved-instance state.
    var openRouterKeyDraft by remember { mutableStateOf("") }
    var lastSavedOpenRouterKey by remember { mutableStateOf("") }
    var linkPhone by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    var pendingToolResult by remember { mutableStateOf<String?>(null) }
    var pendingAdminChange by remember { mutableStateOf<PendingAdminChange?>(null) }
    var safetyState by remember { mutableStateOf<AdminPayload.SafetyState?>(null) }
    var approvedImages by remember { mutableStateOf<AdminPayload.ImageAssets?>(null) }
    var imageReferences by remember { mutableStateOf<AdminPayload.ImageReferences?>(null) }
    var profilePictures by remember { mutableStateOf<AdminPayload.ProfilePictures?>(null) }
    var personas by remember { mutableStateOf<AdminPayload.Personas?>(null) }
    var personaData by remember { mutableStateOf<AdminPayload.PersonaData?>(null) }
    var blocks by remember { mutableStateOf<AdminPayload.Blocks?>(null) }
    var proactiveContacts by remember { mutableStateOf<AdminPayload.ProactiveContacts?>(null) }
    var writeOutcome by remember { mutableStateOf<AdminPayload.WriteContactOutcome?>(null) }
    var memoryBrowserOpen by rememberSaveable { mutableStateOf(false) }
    var memoryEntries by remember { mutableStateOf<List<MemorySummary>?>(null) }
    var memoryDocument by remember { mutableStateOf<AdminPayload.MemoryDocument?>(null) }
    val openMemoryBrowser = {
        memoryEntries = null
        memoryDocument = null
        memoryBrowserOpen = true
    }
    LaunchedEffect(openChatId, chats) {
        openChatId?.let(chats::openChat)
    }
    LaunchedEffect(memoryBrowserOpen, controller) {
        if (memoryBrowserOpen && memoryEntries == null) {
            controller.executeAdminAction(AdminAction.ListMemories())
        }
    }
    LaunchedEffect(controller) {
        controller.adminResults.collect { result ->
            // Payloads with a place of their own are rendered there instead of degrading into a
            // wall of text in a sheet.
            when (val payload = (result as? AdminResult.Success)?.payload) {
                is AdminPayload.ImageAssets -> {
                    approvedImages = payload
                    pendingToolResult = null
                }
                is AdminPayload.ImageReferences -> {
                    imageReferences = payload
                    pendingToolResult = null
                }
                is AdminPayload.ProfilePictures -> {
                    profilePictures = payload
                    pendingToolResult = null
                }
                is AdminPayload.Personas -> personas = payload
                is AdminPayload.PersonaData -> personaData = payload
                is AdminPayload.Blocks -> blocks = payload
                is AdminPayload.ProactiveContacts -> proactiveContacts = payload
                is AdminPayload.SafetyState -> {
                    safetyState = payload
                    pendingToolResult = null
                }
                // Both halves of the two-step land here: the dry run that offers a confirmation
                // and the send that reports what happened. The dialog tells them apart.
                is AdminPayload.WriteContactOutcome -> writeOutcome = payload
                is AdminPayload.Memories -> memoryEntries = payload.entries
                is AdminPayload.MemoryDocument -> {
                    memoryDocument = payload
                    memoryEntries =
                        memoryEntries?.map { entry ->
                            if (
                                entry.scope == payload.summary.scope &&
                                entry.id == payload.summary.id
                            ) {
                                payload.summary
                            } else {
                                entry
                            }
                        }
                }
                // A hand-written memory file makes the catalogue on screen stale, so re-read it.
                is AdminPayload.MemoryWritten ->
                    if (memoryBrowserOpen) {
                        memoryEntries = null
                        controller.executeAdminAction(AdminAction.ListMemories())
                    }
                // Blocking wrote to the list the tools screen is showing, so re-read it.
                is AdminPayload.BlockChanged -> {
                    controller.executeAdminAction(AdminAction.ListBlocks)
                    shellScope.launch { snackbar.showSnackbar(renderAdminResult(result)) }
                }
                is AdminPayload.AccessChanged -> {
                    val pending = pendingAdminChange
                    if (pending != null) {
                        val message =
                            when {
                                pending.operation == AccessOperation.REMOVE && payload.changed > 0 ->
                                    "${pending.number} is no longer an admin"
                                pending.operation == AccessOperation.REMOVE ->
                                    "${pending.number} was not an admin"
                                payload.changed > 0 -> "${pending.number} is now an admin"
                                else -> "${pending.number} is already an admin"
                            }
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                        pendingAdminChange = null
                    } else {
                        shellScope.launch { snackbar.showSnackbar(renderAdminResult(result)) }
                    }
                }
                is AdminPayload.WipeSummary -> {
                    personaData = null
                    controller.executeAdminAction(AdminAction.ListPersonaData)
                    shellScope.launch { snackbar.showSnackbar(renderAdminResult(result)) }
                }
                else -> {
                    val toolId = pendingToolResult
                    if (toolId == SAFETY_RESULT_ID) {
                        controller.executeAdminAction(AdminAction.RefreshSafety)
                    } else if (toolId == PERSONAS_RESULT_ID) {
                        controller.executeAdminAction(AdminAction.ListPersonas)
                    } else if (toolId == IMAGES_RESULT_ID) {
                        approvedImages?.personaKey?.let {
                            controller.executeAdminAction(AdminAction.PersonaImages(it))
                        }
                    } else if (
                        toolId == PROACTIVE_RESULT_ID &&
                        result is AdminResult.Success
                    ) {
                        // Reload only after the mutation result arrives. Firing both operations
                        // from the slider raced the read against the write and could snap back.
                        controller.executeAdminAction(AdminAction.ListProactiveContacts)
                    } else {
                        shellScope.launch { snackbar.showSnackbar(renderAdminResult(result)) }
                    }
                    pendingAdminChange = null
                    pendingToolResult = null
                }
            }
        }
    }

    // Short confirmations are a snackbar, not a sheet. As a sheet they landed on top of the sheet
    // holding the answer the user actually asked for, so every query needed two taps to read.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        controller.clearMessage()
    }

    LaunchedEffect(chatOperationError) {
        val error = chatOperationError ?: return@LaunchedEffect
        snackbar.showSnackbar(error)
        chats.clearOperationError()
    }

    LaunchedEffect(consoleOpen) {
        if (consoleOpen) controller.executeAdminAction(AdminAction.ListPersonas)
    }

    val personaOptions =
        personas?.entries?.map { it.key to it.displayName }
            ?: SettingsCatalogs.personas.map { it.key to it.label }
    val settingsState =
        state.copy(
            basicSettings =
                state.basicSettings.map {
                    if (it.key == BotSettingKeys.PERSONALITY) it.copy(options = personaOptions) else it
                },
            expertSettings =
                state.expertSettings.map {
                    if (it.key == BotSettingKeys.PERSONALITY) it.copy(options = personaOptions) else it
                },
        )
    val allowEveryDirectContact =
        (settingsState.basicSettings + settingsState.expertSettings)
            .firstOrNull { it.key == AppSettingKeys.ALLOW_ALL }
            ?.value
            ?.toBooleanStrictOrNull()
            ?: true

    // The wizard is first-run only now. Re-opening setup afterwards pushes the same fields open in
    // the Settings list instead of throwing a full-screen takeover over whatever was on screen.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (!state.setup.onboardingCompleted) {
            SetupWizard(
                setup = state.setup,
                busy = state.busy,
                onPair = controller::linkWhatsApp,
                onSaveKey = controller::saveOpenRouterKey,
                onComplete = controller::completeOnboarding,
            )
        } else if (openChatId != null) {
            val rows by chats.rows.collectAsState()
            val openChat by chats.detail.collectAsState()
            val chatLoadError by chats.loadError.collectAsState()
            val leave = {
                openChatId = null
                writeOutcome = null
                chats.closeChat()
            }
            BackHandler(onBack = leave)
            val detail = openChat
            // The transcript is read from disk, so there is a frame or two where the chat that was
            // tapped is not the chat that is loaded. Drawing the previous one for that frame would
            // put someone else's conversation on screen, so nothing is drawn until they match.
            if (detail == null || detail.chatId != openChatId) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chatLoadError ?: "Opening…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            if (chatLoadError != null) {
                                Modifier.clickable { openChatId?.let(chats::openChat) }
                            } else {
                                Modifier
                            },
                    )
                }
            } else {
                val contactNumber = detail.contactNumber
                val ownerNumbers =
                    state.accessLists.firstOrNull { it.key == AppSettingKeys.OWNER_NUMBERS }?.entries
                        .orEmpty()
                val adminNumbers =
                    state.accessLists.firstOrNull { it.key == AppSettingKeys.ADMIN_NUMBERS }?.entries
                        .orEmpty()
                val contactIsOwner =
                    contactNumber?.let { number -> ownerNumbers.any { it.samePhoneNumber(number) } } == true
                val adminOperation =
                    contactNumber?.let { number ->
                        when {
                            contactIsOwner -> null
                            adminNumbers.any { it.samePhoneNumber(number) } -> AccessOperation.REMOVE
                            else -> AccessOperation.ADD
                        }
                    }
                ChatScreen(
                    detail = detail,
                    live = chats.live,
                    activity = rows.firstOrNull { it.chatId == detail.chatId }?.activity,
                    onBack = leave,
                    onInject = { text -> chats.inject(detail.chatId, text) },
                    onEditMemory = { id, text -> chats.editMemory(detail.chatId, id, text) },
                    onDeleteInjection = { id -> chats.deleteInjection(detail.chatId, id) },
                    onDeletePersonaChat = { chats.deletePersonaChat(detail.chatId) },
                    onSetOverride = { key, value ->
                        chats.setChatOverride(detail.chatId, key, value)
                    },
                    onImportChat = { uri ->
                        chats.importWhatsAppChat(detail.chatId, uri)
                    },
                    onCreateMemory = {
                        controller.executeAdminAction(
                            AdminAction.CreateChatMemory(detail.chatId),
                        )
                    },
                    writingMemory = memoryWork.chatWork(detail.chatId),
                    writingGlobalMemory =
                        detail.settings.effectivePersona
                            ?.let { persona -> memoryWork.personaWork(persona) },
                    onWritePerson =
                        if (detail.isGroup) null else {
                            { controller.executeAdminAction(AdminAction.WriteContactNow(detail.chatId)) }
                        },
                    onBlockPerson =
                        detail.contactNumber?.let { number ->
                            {
                                controller.executeAdminAction(
                                    AdminAction.BlockContact(number, "Blocked from chat actions"),
                                )
                            }
                        },
                    adminOperation = adminOperation,
                    contactIsOwner = contactIsOwner,
                    onChangeAdmin = { operation ->
                        contactNumber?.let { number ->
                            pendingAdminChange = PendingAdminChange(number, operation)
                                controller.executeAdminAction(
                                    AdminAction.ChangeAccess(
                                        AccessList.ADMIN,
                                        operation,
                                        listOf(number),
                                    ),
                                )
                        }
                    },
                    personaOptions = personaOptions,
                    modifier = Modifier.statusBarsPadding(),
                )
            }
        } else {
            // Registered out here, not inside the console page: the console page stays composed
            // while the list is on screen, so a handler declared in it would swallow back presses
            // meant for the list.
            BackHandler(enabled = consoleOpen, onBack = closeConsole)
            HorizontalPager(
                state = shell,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                if (page == 0) {
            val rows by chats.rows.collectAsState()
            val uptime by chats.uptimeMs.collectAsState()
            val linkPower by chats.linkPower.collectAsState()
            ChatListScreen(
                rows = rows,
                status = phaseHeadline(state.runtime.phase),
                tone = state.runtime.phase.tone(),
                uptimeMs = uptime,
                linkPower = linkPower,
                stopped = state.runtime.phase == RuntimePhase.STOPPED,
                limitNotice = state.limitNotice,
                alerts = state.alerts,
                onDismissAlert = controller::dismissAlert,
                onOpenChat = { chatId ->
                    openChatId = chatId
                },
                onDeleteChat = chats::deleteChat,
                allowEveryDirectContact = allowEveryDirectContact,
                onAllowEveryDirectContactChanged = { enabled ->
                    controller.updateSetting(AppSettingKeys.ALLOW_ALL, enabled.toString())
                },
                onAddContact = { number ->
                    chats.createPendingChat(number)
                    state.accessLists
                        .firstOrNull { it.key == AppSettingKeys.ALLOWLIST_NUMBERS }
                        ?.let { list ->
                            controller.updateAccessList(
                                list.key,
                                (list.entries + number.trim()).distinct(),
                            )
                        }
                },
                onOpenSettings = openConsole,
                onOpenLimitSetting = openSettings,
                onToggleConnection = {
                    // Wake and sleep, not start and stop. Tapping the status line used
                    // to stop the whole bot, which is a heavier thing than the line it
                    // sits on suggests — the real Stop is in the settings overview and
                    // stays there. While the bot is not running at all this is still
                    // the way to start it, because then there is no link to wake.
                    if (state.runtime.phase == RuntimePhase.STOPPED) {
                        onStartService()
                    } else {
                        chats.keepLinkAwake()
                    }
                },
                onSleepNow = { if (state.runtime.phase != RuntimePhase.STOPPED) chats.sleepLinkNow() },
                modifier = Modifier.statusBarsPadding(),
            )
                } else {
            val uptime by chats.uptimeMs.collectAsState()
            val bindings by chats.bindings.collectAsState()
            // The chats that exist, for the panels that have to name one. A chat is keyed by the
            // contact's @lid, so it can only be chosen from this list — never typed.
            val settingsRows by chats.rows.collectAsState()
            val whatsappLinked =
                state.accountJid != null ||
                    state.whatsappConnection == BridgeConnectionState.CONNECTED
            val shortcuts =
                buildList {
                    state.accessLists.forEach { list ->
                        add(
                            SettingsShortcut(
                                section = "Safety & access",
                                title = list.title,
                                subtitle = null,
                                icon = BotIcon.KEY,
                                keywords = "allow permissions contacts groups admin owner",
                                panel = {
                                    AccessListPanel(
                                        list = list,
                                        bindings = bindings,
                                        onChange = controller::updateAccessList,
                                    )
                                },
                            ),
                        )
                    }
                    add(
                        SettingsShortcut(
                            "Safety & access",
                            "Blocked contacts",
                            null,
                            BotIcon.SHIELD,
                            keywords = "block blocked blacklist",
                            panel = {
                                BlockedContactsPanel(
                                    blocks = blocks,
                                    onAction = controller::executeAdminAction,
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Safety & access",
                            "Safety limits",
                            null,
                            BotIcon.WARNING,
                            keywords = "recheck restriction lock timelock ban",
                            panel = {
                                SafetyInlinePanel(
                                    state = safetyState,
                                    onAction = { action ->
                                        pendingToolResult = SAFETY_RESULT_ID
                                        controller.executeAdminAction(action)
                                    },
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Everyday",
                            "Per-contact proactivity",
                            null,
                            BotIcon.PULSE,
                            keywords = "proactive outreach contact write first",
                            // Inside Proactivity, not beside it: this is the same decision as the
                            // level above, taken one conversation at a time.
                            group = "Proactivity",
                            panel = {
                                ProactiveContactsPanel(
                                    contacts = proactiveContacts,
                                    outcome = writeOutcome,
                                    onAction = { action ->
                                        pendingToolResult = PROACTIVE_RESULT_ID
                                        controller.executeAdminAction(action)
                                    },
                                    onClearOutcome = { writeOutcome = null },
                                    onCancelWrite = controller::cancelWriteContact,
                                    limitNotice = state.limitNotice,
                                    // Already on the settings page: only the named row has to be
                                    // revealed, no page has to be travelled to.
                                    onOpenLimitSettings = {
                                        focusSettingKey = state.limitNotice?.settingKey
                                    },
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Persona",
                            "Personas",
                            null,
                            BotIcon.PERSON,
                            panel = {
                                PersonasPanel(
                                    activePersona = state.persona,
                                    personas = personas,
                                    onAction = { action ->
                                        pendingToolResult = PERSONAS_RESULT_ID
                                        controller.executeAdminAction(action)
                                    },
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Persona",
                            "Approved images",
                            null,
                            BotIcon.IMAGE,
                            panel = {
                                ApprovedImagesPanel(
                                    activePersona = state.persona,
                                    personas = personas,
                                    assets = approvedImages,
                                    onAction = { action ->
                                        pendingToolResult = IMAGES_RESULT_ID
                                        controller.executeAdminAction(action)
                                    },
                                    onImportImage = controller::importApprovedImage,
                                    onExportImage = controller::exportPersonaImage,
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Persona",
                            "Character references",
                            "Up to 8 normalized API-ready images per persona",
                            BotIcon.PERSON,
                            keywords = "reference images upload character generated portrait",
                            panel = {
                                CharacterReferencesPanel(
                                    activePersona = state.persona,
                                    personas = personas,
                                    references = imageReferences,
                                    onAction = { action ->
                                        pendingToolResult = REFERENCES_RESULT_ID
                                        controller.executeAdminAction(action)
                                    },
                                    onImportReferences = controller::importCharacterReferences,
                                    onExportImage = controller::exportPersonaImage,
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Persona",
                            "Profile pictures",
                            "The faces on WhatsApp, and how often they change",
                            BotIcon.IMAGE,
                            keywords = "profile picture avatar face rotation upload",
                            panel = {
                                ProfilePicturesPanel(
                                    activePersona = state.persona,
                                    personas = personas,
                                    pictures = profilePictures,
                                    settings =
                                        settingsState.basicSettings + settingsState.expertSettings,
                                    onAction = { action ->
                                        pendingToolResult = PROFILE_PICTURES_RESULT_ID
                                        controller.executeAdminAction(action)
                                    },
                                    onChange = controller::updateSetting,
                                    onImportPictures = controller::importProfilePictures,
                                    onExportImage = controller::exportPersonaImage,
                                    catalogStatus = settingsState.modelCatalogStatus,
                                    catalogError = settingsState.modelCatalogError,
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Persona",
                            "View memory",
                            null,
                            BotIcon.MEMORY,
                            keywords = "memories remembered",
                            onClick = openMemoryBrowser,
                            // Writes mostly start on their own, mid-conversation. Saying so here is
                            // the difference between opening the browser to a file that is about to
                            // change and knowing to wait a moment for it.
                            trailing =
                                if (memoryWork.isEmpty()) {
                                    null
                                } else {
                                    { MemoryWritingDot() }
                                },
                        ),
                    )
                    // The log and setup are content, so they push open under their own row instead
                    // of replacing the page. Both are still reachable as pages — the log from
                    // Overview, setup on first run — this is only how they open from Settings.
                    add(
                        SettingsShortcut(
                            "Advanced",
                            SettingsTargets.ACTIVITY_LOG,
                            null,
                            BotIcon.ACTIVITY,
                            keywords = "log diagnostics history",
                            panel = {
                                ActivityScreen(
                                    entries = state.activity,
                                    hasMore = state.activityHasMore,
                                    onLoadMore = controller::loadMoreActivity,
                                    showTitle = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 16.dp),
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Advanced",
                            // Named by the alert rows that send the owner here; see SettingsTargets.
                            SettingsTargets.LINK_WHATSAPP,
                            if (whatsappLinked) "Linked" else "Not linked",
                            BotIcon.LINK,
                            keywords = "pair link phone number code device",
                            panel = {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .navigationBarsPadding()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    if (whatsappLinked) {
                                        ListGroup {
                                            ListRow(
                                                title =
                                                    if (
                                                        state.whatsappConnection ==
                                                        BridgeConnectionState.CONNECTED
                                                    ) {
                                                        "Connected"
                                                    } else {
                                                        "Linked device"
                                                    },
                                                subtitle =
                                                    state.accountJid
                                                        ?.substringBefore('@')
                                                        ?.let { masked("+$it") },
                                                value =
                                                    if (
                                                        state.whatsappConnection ==
                                                        BridgeConnectionState.CONNECTED
                                                    ) {
                                                        "Online"
                                                    } else {
                                                        "Sleeping or reconnecting"
                                                    },
                                                icon = BotIcon.LINK,
                                            )
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            "Disconnect removes this linked-device identity. Sleep and Low Battery " +
                                                "only close the socket and never unlink it.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        HoldToConfirm(
                                            text = "Hold to disconnect",
                                            onConfirm = controller::disconnectWhatsApp,
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !state.busy,
                                        )
                                    } else {
                                        BotTextField(
                                            value = linkPhone,
                                            onValueChange = { linkPhone = it },
                                            label = "Phone number",
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        PrimaryButton(
                                            text = "Send code",
                                            onClick = { controller.linkWhatsApp(linkPhone) },
                                            enabled = linkPhone.isNotBlank() && !state.busy,
                                        )
                                        state.setup.pairingCode?.let { code ->
                                            Spacer(Modifier.height(14.dp))
                                            PairingCodePanel(code)
                                            Text(
                                                "WhatsApp → three-dot menu → Linked devices → Link a device → " +
                                                    "Link with phone number.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 8.dp),
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Advanced",
                            SettingsTargets.OPENROUTER_API_KEY,
                            null,
                            BotIcon.KEY,
                            keywords = "openrouter api credential key replace",
                            panel = {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    // Stored keys stay write-only. This row reports the local
                                    // Keystore state without ever returning the secret to Compose.
                                    if (state.setup.openRouterKeyConfigured) {
                                        ListGroup {
                                            ListRow(
                                                title = "OpenRouter API key",
                                                subtitle = "Saved and in use",
                                                value = "••••••••••••",
                                                icon = BotIcon.KEY,
                                            )
                                            DangerRow(
                                                title = "Remove key",
                                                subtitle = "Hold to delete, then enter a new one",
                                                onConfirm = {
                                                    controller.clearOpenRouterKey()
                                                    openRouterKeyDraft = ""
                                                    lastSavedOpenRouterKey = ""
                                                },
                                            )
                                        }
                                    } else {
                                        BotTextField(
                                            value = openRouterKeyDraft,
                                            onValueChange = { openRouterKeyDraft = it },
                                            label = "OpenRouter API key",
                                            secret = true,
                                        )
                                        LaunchedEffect(openRouterKeyDraft) {
                                            val key = openRouterKeyDraft.trim()
                                            if (key.isBlank() || key == lastSavedOpenRouterKey) {
                                                return@LaunchedEffect
                                            }
                                            delay(900L)
                                            controller.saveOpenRouterKey(key)
                                            lastSavedOpenRouterKey = key
                                        }
                                    }
                                }
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Advanced",
                            "Third-party notices",
                            "Licenses, exact versions and source availability",
                            BotIcon.HELP,
                            keywords = "open source license notices whatsmeow libsignal mpl gpl",
                            panel = { ThirdPartyNoticesPanel() },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Everyday",
                            "Battery & background",
                            null,
                            BotIcon.BATTERY,
                            keywords = "battery power mode low sleep doze listen background",
                            panel = {
                                val allSettings =
                                    settingsState.basicSettings + settingsState.expertSettings
                                BatteryPanel(
                                    settings = allSettings,
                                    instant =
                                        allSettings.any {
                                            it.key == BotSettingKeys.REPLY_PRESET &&
                                                it.value == INSTANT_PRESET
                                        },
                                    onChange = controller::updateSetting,
                                    catalogStatus = settingsState.modelCatalogStatus,
                                    catalogError = settingsState.modelCatalogError,
                                )
                            },
                        ),
                    )
                    add(
                        SettingsShortcut(
                            "Danger zone",
                            "Delete data",
                            null,
                            BotIcon.TRASH,
                            danger = true,
                            panel = {
                                DataPanel(
                                    personaData = personaData,
                                    chats = settingsRows.map { it.chatId to it.title },
                                    onAction = controller::executeAdminAction,
                                    onExportData = controller::exportBotData,
                                    onImportData = controller::importBotData,
                                )
                            },
                        ),
                    )
                }
            Column(Modifier.fillMaxSize()) {
                ConsoleBar(onClose = closeConsole)
                // Only the settings root gets the console bar. Every pushed detail screen brings
                // its own DetailAppBar, and stacking the two produced the second back arrow, a
                // "Settings" title on a page that is not Settings, and — because both bars apply
                // statusBarsPadding() — a status-bar-high empty band in the middle of the screen.
                SettingsRootScreen(
                        state = settingsState,
                        onChange = controller::updateSetting,
                        onRefreshModels = controller::refreshModels,
                        shortcuts = shortcuts,
                        listState = settingsListState,
                        focusSettingKey = focusSettingKey,
                        onFocusHandled = { focusSettingKey = null },
                        header = {
                            UnifiedSettingsOverview(
                                state = state,
                                personaLabel =
                                    personaOptions
                                        .firstOrNull { it.first == state.persona }
                                        ?.second
                                        ?: state.persona.replaceFirstChar(Char::uppercase),
                                uptimeMs = uptime,
                                onStart = onStartService,
                                onStop = controller::stopService,
                            )
                        },
                    )
            }
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
        )
    }

    if (memoryBrowserOpen) {
        MemoryBrowserDialog(
            entries = memoryEntries,
            document = memoryDocument,
            work = memoryWork,
            onOpen = { controller.executeAdminAction(AdminAction.OpenMemory(it.scope, it.id)) },
            onCreateGlobalMemory = {
                controller.executeAdminAction(AdminAction.CreateGlobalMemory(it))
            },
            onCancelGlobalMemory = controller::cancelGlobalMemory,
            // One action, no follow-up list call: it answers with the refreshed catalogue, and a
            // second action fired from here would run concurrently with the delete/empty it follows.
            onDelete = { entry ->
                controller.executeAdminAction(AdminAction.DeleteMemory(entry.scope, entry.id))
            },
            onSaveSummary = { entry, summary ->
                controller.executeAdminAction(
                    AdminAction.UpdateMemory(
                        scope = entry.scope,
                        id = entry.id,
                        expectedRevision = entry.revision,
                        summary = summary,
                    ),
                )
            },
            onCloseDocument = { memoryDocument = null },
            onRefresh = {
                memoryEntries = null
                controller.executeAdminAction(AdminAction.ListMemories())
            },
            onDismiss = {
                memoryBrowserOpen = false
                memoryDocument = null
            },
        )
    }
    if (openChatId != null) {
        writeOutcome?.takeIf { it.target == openChatId }?.let { outcome ->
            WriteContactDialog(
                outcome = outcome,
                onConfirm = { note ->
                    controller.executeAdminAction(
                        AdminAction.WriteContactNow(outcome.target, outcome.confirmation, note),
                    )
                },
                onDismiss = { writeOutcome = null },
                onCancelRunning = {
                    controller.cancelWriteContact(outcome.target)
                    writeOutcome = null
                },
                limitNotice = state.limitNotice,
                onOpenLimitSettings = {
                    val notice = state.limitNotice
                    writeOutcome = null
                    openSettings(notice?.settingKey)
                },
            )
        }
    }
}

@Composable
private fun ThirdPartyNoticesPanel() {
    val context = LocalContext.current
    val notices by produceState("Loading notices…", context) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    listOf(
                        "THIRD_PARTY_NOTICES.md",
                        "licenses/Apache-2.0.txt",
                        "licenses/MPL-2.0.txt",
                        "licenses/GPL-3.0-only.txt",
                        "licenses/NATIVE_PERMISSIVE_LICENSES.txt",
                    ).joinToString("\n\n────────────────────────\n\n") { asset ->
                        context.assets.open(asset).bufferedReader().use { it.readText() }
                    }
                }.getOrElse { "Third-party notices could not be read from this build." }
            }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            notices,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        QuietButton(
            text = "Open source and licenses",
            onClick = {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/totec448-spec/doppel".toUri(),
                    ),
                )
            },
        )
    }
}

/**
 * The strip that says where you are and how to leave.
 *
 * It carries the connection state because this is the one screen where that question comes up, and
 * the number beside it is how long the link has been up — not how long the app has been open. There
 * is no second status line anywhere below: one place, one answer.
 */
@Composable
private fun ConsoleBar(
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClose)
                .padding(12.dp),
        ) {
            BotLineIcon(
                BotIcon.ARROW_LEFT,
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun UnifiedSettingsOverview(
    state: AppUiState,
    personaLabel: String,
    uptimeMs: Long?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val runtimeActive =
        state.runtime.phase in setOf(
            RuntimePhase.STARTING,
            RuntimePhase.WAITING_FOR_NETWORK,
            RuntimePhase.CONNECTING,
            RuntimePhase.ONLINE,
            RuntimePhase.BACKING_OFF,
            RuntimePhase.STOPPING,
        )
    val stopping = state.runtime.phase == RuntimePhase.STOPPING
    // The account's own WhatsApp name, which is what contacts actually see. It is kept on the
    // selected persona by the runtime rather than second-guessed here — see
    // [NativeRuntimeHost.syncPushName]. The persona label only stands in before the first
    // connection has reported a name.
    val profileLabel = state.accountName ?: personaLabel
    val status = phaseHeadline(state.runtime.phase)
    val statusColor =
        when (state.runtime.phase.tone()) {
            StatusTone.LIVE -> MaterialTheme.colorScheme.primary
            StatusTone.WAITING -> Waiting
            StatusTone.DOWN -> MaterialTheme.colorScheme.error
        }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonaAvatar(state.profilePicturePath, profileLabel)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profileLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        listOfNotNull(
                            state.accountJid?.substringBefore('@')?.let { masked("+$it") },
                            shortModel(state.model).takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ListGroup {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = buildString {
                        append(status)
                        if (uptimeMs != null) append(" · ${formatUptime(uptimeMs)}")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (runtimeActive) "Stop" else "Start",
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        when {
                            stopping -> MaterialTheme.colorScheme.onSurfaceVariant
                            runtimeActive -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                enabled = !stopping,
                                onClick = if (runtimeActive) onStop else onStart,
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        state.limitNotice?.let { notice ->
            Spacer(Modifier.height(10.dp))
            ListGroup {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BotLineIcon(BotIcon.WARNING, Modifier.size(20.dp), MaterialTheme.colorScheme.error)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(
                            "Limit reached · ${notice.reason}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            buildString {
                                append(notice.detail)
                                notice.settingLabel?.let { append("\nChange: ").append(it) }
                                notice.untilMs?.let { append(" · free ").append(formatClock(it)) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        StatStrip(
            listOf(
                "Received" to state.processedToday.toString(),
                "Sent" to state.sentToday.toString(),
                "Waiting" to state.pendingChats.toString(),
            ),
        )
    }
}

/** 640² stored, 52 dp painted: a quarter of each side is still more pixels than the circle has. */
private const val AVATAR_SAMPLE_SIZE = 4

/** The stored picture that is actually on the account: no WhatsApp read, no network request. */
@Composable
private fun PersonaAvatar(picturePath: String?, fallbackLabel: String) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, picturePath) {
        value =
            picturePath?.let { path ->
                withContext(Dispatchers.IO) {
                    // Downsampled on the way in: the stored file is a 640² avatar, the view is
                    // 52 dp, and decoding the full bitmap for it is pure heap.
                    runCatching {
                        BitmapFactory.decodeFile(
                            path,
                            BitmapFactory.Options().apply { inSampleSize = AVATAR_SAMPLE_SIZE },
                        )?.asImageBitmap()
                    }.getOrNull()
                }
            }
    }
    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = "$fallbackLabel profile picture",
            modifier = Modifier.size(52.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Avatar(
            initial = fallbackLabel.initial(),
            accent = fallbackLabel.accent(),
            isGroup = false,
            size = 52,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Navigation bar
// ─────────────────────────────────────────────────────────────────────────────
// Simple detail screens
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything about what the bot costs the phone, in one place.
 *
 * The two settings live here rather than in a category of their own because the third row is what
 * makes either of them work: Android's own battery permission. Deciding to run in low power mode
 * and then finding out the system had been killing the service anyway is one panel, not two.
 */
@Composable
private fun BatteryPanel(
    settings: List<UiSetting>,
    instant: Boolean,
    onChange: (String, String) -> Unit,
    catalogStatus: UiCatalogStatus,
    catalogError: String?,
) {
    val context = LocalContext.current
    val mode = settings.firstOrNull { it.key == BotSettingKeys.POWER_MODE }
    val listen = settings.firstOrNull { it.key == BotSettingKeys.LOW_LISTEN_MINUTES }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        ListGroup {
            if (instant) {
                // Instant keeps the link up around the clock, so whatever is stored here is not
                // what is running. Said out loud rather than by disabling the row, and said as the
                // one step that unlocks it, because "your choice returns with Human" left people
                // reading a status line when what they needed was an instruction.
                ListRow(
                    title = mode?.label ?: "Battery use",
                    subtitle = "Set reply timing to Human to change this",
                    value = "Default · instant",
                )
            } else if (mode != null) {
                SettingControlRow(
                    setting = mode,
                    open = openKey == mode.key,
                    onOpen = { openKey = mode.key },
                    onClose = { openKey = null },
                    onChange = onChange,
                    catalogStatus = catalogStatus,
                    catalogError = catalogError,
                )
                if (listen != null) {
                    RowSeparator()
                    SettingControlRow(
                        setting = listen,
                        open = openKey == listen.key,
                        onOpen = { openKey = listen.key },
                        onClose = { openKey = null },
                        onChange = onChange,
                        catalogStatus = catalogStatus,
                        catalogError = catalogError,
                    )
                }
            }
            RowSeparator()
            ListRow(
                title = "Allow battery use",
                subtitle = "Set it to “Unrestricted” in the app settings",
                icon = BotIcon.BATTERY,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared building blocks
// ─────────────────────────────────────────────────────────────────────────────

/** 40 dp circular icon button — the only icon-only control in the app. */
@Composable
internal fun CircleIconButton(
    icon: BotIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(48.dp)
                .semantics { contentDescription = icon.accessibleLabel() }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BotLineIcon(icon, Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A row action for lists that have more than one of them.
 *
 * [CircleIconButton] is 48 dp of filled surface, which is right when it is the only thing on the
 * right of a row and wrong the moment there are two: the pair took 96 dp off the title and turned
 * a list of file names into a list of buttons. This drops the fill and the padding, keeps a touch
 * target Android will still accept, and lets the name have the row back.
 */
@Composable
internal fun RowGlyphButton(
    icon: BotIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(36.dp)
                .semantics { contentDescription = icon.accessibleLabel() }
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BotLineIcon(icon, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun BotIcon.accessibleLabel(): String =
    name.lowercase(Locale.ENGLISH).replace('_', ' ')

/**
 * Where an admin command's answer lands.
 *
 * These replies are lists — every persona, every block, a whole safety report — and an
 * `AlertDialog` sized them to a paragraph and then clipped them. A sheet gets the height and the
 * scroll, and monospace keeps the ids and numbers in columns.
 */
// ─────────────────────────────────────────────────────────────────────────────
// Text helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Colour slot for a runtime phase: green only while it is genuinely up. */
internal fun RuntimePhase.tone(): StatusTone =
    when (this) {
        RuntimePhase.ONLINE -> StatusTone.LIVE
        RuntimePhase.STOPPED, RuntimePhase.ERROR, RuntimePhase.STOPPING -> StatusTone.DOWN
        else -> StatusTone.WAITING
    }

internal fun phaseHeadline(phase: RuntimePhase): String =
    when (phase) {
        RuntimePhase.STOPPED -> "Stopped"
        RuntimePhase.STARTING -> "Starting"
        RuntimePhase.WAITING_FOR_NETWORK -> "Waiting for network"
        RuntimePhase.CONNECTING -> "Connecting"
        RuntimePhase.ONLINE -> "Connected"
        RuntimePhase.BACKING_OFF -> "Retrying"
        RuntimePhase.ATTENTION_REQUIRED -> "Needs attention"
        RuntimePhase.ENGINE_UNAVAILABLE -> "Engine missing"
        RuntimePhase.STOPPING -> "Stopping"
        RuntimePhase.ERROR -> "Error"
    }

/** The provider prefix is the same on every model; the name is what identifies it in a row. */
internal fun shortModel(model: String): String =
    when {
        model.isBlank() -> "Not set"
        else -> model.substringAfterLast('/')
    }

private fun renderAdminResult(result: AdminResult): String =
    when (result) {
        is AdminResult.Invalid ->
            "Invalid${result.field?.let { " ($it)" }.orEmpty()}: ${result.reason}"
        is AdminResult.NotFound -> "Not found: ${result.subject}"
        is AdminResult.Denied -> "Denied: ${result.reason}"
        is AdminResult.Failure -> "Failed: ${result.reason}"
        is AdminResult.Success ->
            when (val payload = result.payload) {
                AdminPayload.Empty -> "Done."
                is AdminPayload.Text -> payload.value
                is AdminPayload.Mood ->
                    if (payload.enabled) {
                        "Mood active: ${payload.name ?: "neutral"}"
                    } else {
                        "Mood is switched off."
                    }
                is AdminPayload.HelpHeader -> payload.introduction.ifBlank { payload.title }
                is AdminPayload.Setting ->
                    "${payload.setting.key} = ${payload.setting.value}\n${payload.setting.description}"
                is AdminPayload.Settings ->
                    payload.settings.joinToString("\n") { "${it.key} = ${it.value}" }
                is AdminPayload.SettingsChanged ->
                    payload.normalizedValues.entries.joinToString("\n") {
                        "${it.key} = ${it.value}"
                    }
                is AdminPayload.AccessEntries ->
                    payload.entries.joinToString("\n").ifBlank { "The list is empty." }
                is AdminPayload.AccessChanged ->
                    "${payload.total} entries, ${payload.changed} changed."
                is AdminPayload.SecretStatus -> "API key: ${payload.maskedValue()}"
                is AdminPayload.Personas ->
                    payload.entries.joinToString("\n") {
                        "${if (it.key == payload.activeKey) "● " else "  "}${it.key} — ${it.displayName}"
                    }
                is AdminPayload.PersonaData ->
                    payload.entries.joinToString("\n") { "${it.displayName} (${it.key})" }
                        .ifBlank { "No personality has conversation data." }
                is AdminPayload.Voices ->
                    payload.entries.joinToString("\n") { "${it.name} — ${it.description}" }
                is AdminPayload.ImageLocation -> payload.location
                is AdminPayload.ImageAssets ->
                    payload.entries.joinToString("\n") {
                        "${it.displayName} (${formatBytes(it.sizeBytes)})"
                    }.ifBlank { "No images approved for ${payload.personaKey}." }
                is AdminPayload.ImageReferences ->
                    payload.entries.joinToString("\n") {
                        "${it.displayName} (${formatBytes(it.sizeBytes)})"
                    }.ifBlank { "No character references for ${payload.personaKey}." }
                is AdminPayload.ProfilePictures ->
                    payload.entries.joinToString("\n") {
                        "${if (it.assetId == payload.live) "● " else "  "}${it.displayName} " +
                            "(${formatBytes(it.sizeBytes)})"
                    }.ifBlank { "No profile pictures for ${payload.personaKey}." }
                is AdminPayload.ImageSent ->
                    "${payload.assetId} was sent to ${payload.targetChatId}."
                is AdminPayload.Blocks ->
                    payload.entries.joinToString("\n") {
                        "${it.number}${it.reason.takeIf(String::isNotBlank)?.let { reason -> " — $reason" }.orEmpty()}"
                    }.ifBlank { "The blocklist is empty." }
                is AdminPayload.BlockChanged ->
                    if (payload.whatsappConfirmed) {
                        "WhatsApp confirmed the block state for ${payload.number}."
                    } else {
                        "Local block state saved for ${payload.number}."
                    }
                is AdminPayload.SafetyState ->
                    payload.summary +
                        payload.activeLocks.joinToString(
                            prefix = if (payload.activeLocks.isEmpty()) "" else "\n",
                            separator = "\n",
                        ) { "${it.label} (#${it.id})" }
                is AdminPayload.ProactiveOverride ->
                    "${payload.target}: ${payload.level ?: "global ${payload.globalLevel}"}"
                // The proactivity screen renders both of these itself; this is the fallback for
                // the same actions arriving over a chat command.
                is AdminPayload.ProactiveContacts ->
                    payload.contacts.joinToString("\n") {
                        "${it.displayName ?: it.chatId}: " +
                            if (it.overridden) "${it.level}" else "global ${payload.globalLevel}"
                    }.ifBlank { "No contacts known yet." }
                is AdminPayload.WriteContactOutcome ->
                    buildString {
                        append(payload.displayName ?: payload.target)
                        append(": ")
                        append(
                            when {
                                payload.sent -> "message sent"
                                payload.confirmation != null ->
                                    "confirm with ${payload.confirmation}"
                                else -> payload.detail ?: "not sent"
                            },
                        )
                    }
                // Both memory payloads own a dedicated screen and never reach this sheet.
                is AdminPayload.Memories -> "${payload.entries.size} memory files"
                is AdminPayload.MemoryDocument -> payload.body
                is AdminPayload.MemoryWritten -> payload.detail
                is AdminPayload.WipeSummary ->
                    "${payload.affectedThreads} thread(s) cleared for ${wipeTargetLabel(payload.target)}."
            }
    }

private fun String.samePhoneNumber(other: String): Boolean {
    val left = filter(Char::isDigit)
    val right = other.filter(Char::isDigit)
    return left.isNotEmpty() && left == right
}

/** [WipeTarget.label] is the German wording the chat commands answer with; the app has its own. */
private fun wipeTargetLabel(target: WipeTarget): String =
    when (target) {
        is WipeTarget.Persona -> "persona ${target.key}"
        WipeTarget.All -> "all personas"
    }

internal fun formatBytes(value: Long): String =
    when {
        value >= 1024L * 1024 ->
            String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024L -> String.format(Locale.ROOT, "%.0f KB", value / 1024.0)
        else -> "$value B"
    }
