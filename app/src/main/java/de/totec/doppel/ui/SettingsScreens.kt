package de.totec.doppel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.totec.doppel.app.UiSettingsMapper
import de.totec.doppel.settings.BotSettingKeys
import kotlin.math.roundToInt

/**
 * Settings as one page that never leaves itself.
 *
 * Every value used to be opened somewhere else: an enum in a modal sheet, a prompt on a pushed page,
 * the model catalogue on a second pushed page with its own back arrow. Four ways to change four
 * kinds of value, and each one took the list off screen, so the answer to "what did I just set this
 * next to" was always one navigation away.
 *
 * Now there is one page and two behaviours, both defined in [Disclosure]: content **pushes** the
 * page down ([PushPanel]) and values **float** over it ([FloatAnchor]). A category opens in place, a
 * prompt is written in place, and a list of options covers the rows under it for exactly as long as
 * it takes to pick one.
 */

/**
 * Every setting this page shows, after the three things the page decides rather than the schema.
 *
 * The battery pair is filed elsewhere: it sits in the Battery panel at the bottom, next to the
 * Android permission that decides whether any of it works, and listing it twice would be two
 * places to set one value.
 *
 * Quiet hours arrive as two stored times and leave as one row. A night is a span — its two ends
 * are only ever set against each other — and two rows made you open one, remember it, and open
 * the other to find out how long the bot is actually away.
 *
 * The quiet hours and the zone they are measured in disappear entirely under the instant reply
 * preset, because instant answers the moment a message lands, at three in the morning as much as
 * at noon. Nothing there consults a night window, so showing one would be a promise the bot does
 * not keep. Both settings keep their stored values and come back with the human preset.
 */
internal fun visibleSettings(state: AppUiState): List<UiSetting> {
    val all = state.basicSettings + state.expertSettings
    val instant = all.any { it.key == BotSettingKeys.REPLY_PRESET && it.value == INSTANT_PRESET }
    return buildList {
        all.forEach { setting ->
            when (setting.key) {
                BotSettingKeys.POWER_MODE, BotSettingKeys.LOW_LISTEN_MINUTES -> Unit
                // Folded into the row its start time carries, below.
                BotSettingKeys.SLEEP_END -> Unit
                BotSettingKeys.SLEEP_START -> if (!instant) add(quietHoursRow(setting, all))
                BotSettingKeys.TIMEZONE -> if (!instant) add(setting)
                else -> add(setting)
            }
        }
    }
}

/** The two stored times as the one row that edits them; see [QuietHoursPanel]. */
private fun quietHoursRow(
    start: UiSetting,
    all: List<UiSetting>,
): UiSetting {
    val end = all.firstOrNull { it.key == BotSettingKeys.SLEEP_END }
    return start.copy(
        label = "Quiet hours",
        description =
            "The hours the bot is away: it answers nothing, and the link itself goes offline so " +
                "the phone can suspend. Drag either end.",
        value = quietHoursText(start.value, end?.value.orEmpty()),
        kind = UiSettingKind.TIME_RANGE,
        overridden = start.overridden || end?.overridden == true,
    )
}

/** One category as the list shows it: every setting that carries this group name. */
internal data class SettingGroupModel(
    val name: String,
    val settings: List<UiSetting>,
) {
    val changed: Int get() = settings.count(UiSetting::overridden)
}

/**
 * A non-setting destination indexed by the same global search field.
 *
 * A shortcut either pushes a [panel] open under its own row or runs [onClick]. The panel is how
 * Setup and the log stopped being pages: they are content, so they push, and the row that opened
 * them stays visible directly above.
 */
internal data class SettingsShortcut(
    val section: String,
    val title: String,
    val subtitle: String? = null,
    val icon: BotIcon,
    val keywords: String = "",
    val danger: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val panel: (@Composable () -> Unit)? = null,
    /** Live state at the end of the row. Ignored for a row that pushes a panel — that end is the chevron's. */
    val trailing: (@Composable () -> Unit)? = null,
    /**
     * The settings category this belongs *inside*, when it is about that category's own subject.
     * Per-contact proactivity is the proactivity setting applied one conversation at a time; as a
     * sibling of the Proactivity category it read as an unrelated destination filed nearby by
     * accident. Null keeps a shortcut where all the others are — beside the categories, not in one.
     *
     * A name no category carries falls back to that sibling position rather than disappearing.
     */
    val group: String? = null,
) {
    fun matches(query: String): Boolean =
        listOfNotNull(section, title, subtitle, keywords).any {
            it.contains(query, ignoreCase = true)
        }
}

/**
 * Groups are keyed by name across both tiers.
 *
 * A category holding one advanced switch next to four everyday ones is still one category; listing
 * it twice — once under "Everyday" and once under "Expert" — was a filing detail leaking into the
 * navigation. The basic/expert split is not what somebody looking for a setting has in mind, so it
 * no longer sorts anything on screen: [settingsSection] files categories by how often they are
 * opened, and every setting of a category is inside that one category.
 */
internal fun settingGroups(state: AppUiState): List<SettingGroupModel> {
    val all = visibleSettings(state)
    // The speech model is a model like any other, so it is listed with the models too — not only
    // under voice messages, where it is filed because that is the only thing it affects.
    val modelsGroup = all.firstOrNull { it.key == BotSettingKeys.MODEL }?.group
    val speechModel = all.firstOrNull { it.key == BotSettingKeys.TTS_MODEL }
    return all
        .groupBy(UiSetting::group)
        .map { (name, settings) ->
            val rows =
                if (speechModel != null && name == modelsGroup) {
                    settings + speechModel
                } else {
                    settings
                }
            SettingGroupModel(name, rows)
        }
        // Declaration order in the schema is a filing decision; the list is sorted by how often a
        // category is actually opened.
        .sortedBy { UiSettingsMapper.groupRank(it.name) }
}

@Composable
internal fun SettingsRootScreen(
    state: AppUiState,
    onChange: (String, String) -> Unit,
    onRefreshModels: () -> Unit,
    shortcuts: List<SettingsShortcut> = emptyList(),
    header: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    /**
     * One setting to reveal and open, named by another surface that sent the operator here — the
     * safety limits, for instance, say which number stopped a send. Without it the caller could only
     * put this page on screen and leave the search to whoever had just been told what to change.
     */
    focusSettingKey: String? = null,
    /** Called once the request above has been carried out, so the caller can forget it. */
    onFocusHandled: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val trimmed = query.trim()
    val groups = remember(state.basicSettings, state.expertSettings) { settingGroups(state) }
    val matches =
        remember(trimmed, groups) {
            if (trimmed.isBlank()) {
                emptyList()
            } else {
                // distinctBy: a setting listed in two groups is still one search result.
                groups
                    .flatMap(SettingGroupModel::settings)
                    .distinctBy(UiSetting::key)
                    .filter { it.matches(trimmed) }
            }
        }
    val shortcutMatches =
        remember(trimmed, shortcuts) {
            if (trimmed.isBlank()) emptyList() else shortcuts.filter { it.matches(trimmed) }
        }
    // Only one thing is ever open on this page: one category, or one shortcut panel. Opening a
    // second would push the first one's rows somewhere else while they were being read.
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedShortcut by rememberSaveable { mutableStateOf<String?>(null) }
    // Keyed by setting rather than by value object: a captured UiSetting is a snapshot, so after a
    // write the open control would keep painting the value the setting had when it was opened.
    var openSettingKey by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(
        enabled =
            openSettingKey != null || expandedShortcut != null || expandedGroup != null,
    ) {
        when {
            openSettingKey != null -> openSettingKey = null
            expandedShortcut != null -> expandedShortcut = null
            else -> expandedGroup = null
        }
    }
    val sections = remember(groups) { groups.groupBy { settingsSection(it.name) } }
    // Split once: a shortcut that names a category which is actually on the page is rendered inside
    // it, everything else keeps the sibling position it has always had.
    val presentGroups = remember(groups) { groups.mapTo(mutableSetOf(), SettingGroupModel::name) }
    val nestedShortcuts =
        remember(shortcuts, presentGroups) {
            shortcuts.filter { it.group != null && it.group in presentGroups }.groupBy { it.group!! }
        }
    val shortcutSections =
        remember(shortcuts, presentGroups) {
            shortcuts
                .filter { it.group == null || it.group !in presentGroups }
                .groupBy(SettingsShortcut::section)
        }
    val sectionNames =
        (sections.keys + shortcutSections.keys)
            .distinct()
            .sortedBy(::settingsSectionRank)
    val open: (UiSetting) -> Unit = { setting ->
        // Only a model list has a remote catalogue behind it — the voices are a local table, and
        // asking OpenRouter about them would be a request for nothing. Even here the catalogue
        // client answers from its own cache until that goes stale, so opening a picker costs a
        // provider call only sporadically rather than every single time.
        if (
            setting.kind == UiSettingKind.MODEL ||
                setting.key == BotSettingKeys.TTS_VOICE ||
                setting.key == BotSettingKeys.REASONING_EFFORT ||
                setting.key == BotSettingKeys.MEDIA_REASONING_EFFORT ||
                setting.key == BotSettingKeys.VERIFY_REASONING_EFFORT
        ) {
            onRefreshModels()
        }
        expandedShortcut = null
        openSettingKey = setting.key
    }
    // Searching for the setting rather than expanding its category: the search result is the one
    // row, already open, with nothing else on the page to look past. Which category it happens to
    // live in is not what the operator was told to change.
    LaunchedEffect(focusSettingKey, groups) {
        val requested = focusSettingKey ?: return@LaunchedEffect
        // A shortcut first, and without waiting for settings: the two conditions that send somebody
        // here most often — no API key, no WhatsApp link — are a secret and a pairing panel, and
        // neither has a settings row that could ever match. Asking for one by its schema key used to
        // resolve to nothing at all, which is exactly what the tap then did.
        val shortcut = shortcuts.firstOrNull { it.title.equals(requested, ignoreCase = true) }
        if (shortcut != null) {
            query = shortcut.title
            expandedGroup = null
            openSettingKey = null
            expandedShortcut = shortcut.title
            listState.scrollToItem(0)
            onFocusHandled()
            return@LaunchedEffect
        }
        // Settings arrive with the first state emission; without one there is nothing to reveal
        // yet, and the request is kept until they do.
        if (groups.isEmpty()) return@LaunchedEffect
        val setting =
            groups.flatMap(SettingGroupModel::settings).firstOrNull { it.key == requested }
        if (setting != null) {
            query = setting.label
            expandedGroup = null
            expandedShortcut = null
            openSettingKey = setting.key
            listState.scrollToItem(0)
        }
        onFocusHandled()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
        contentPadding = PaddingValues(ScreenPadding, 0.dp, ScreenPadding, 32.dp),
    ) {
        item(key = "search") {
            BotTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search",
                leading = {
                    BotLineIcon(
                        BotIcon.SEARCH,
                        Modifier.size(18.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailing =
                    if (query.isNotEmpty()) {
                        {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "Clear search" }
                                    .clickable { query = "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                BotLineIcon(
                                    BotIcon.CLOSE,
                                    Modifier.size(15.dp),
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        null
                    },
            )
        }
        if (header != null && trimmed.isBlank()) item(key = "overview") { header() }

        if (trimmed.isNotBlank()) {
            item(key = "results_label") {
                SectionLabel("${matches.size + shortcutMatches.size} matches")
            }
            if (matches.isEmpty() && shortcutMatches.isEmpty()) {
                item(key = "results_empty") {
                    EmptyHint("No setting matches “$trimmed”.")
                }
            } else {
                item(key = "results") {
                    ListGroup {
                        matches.forEachIndexed { index, setting ->
                            if (index > 0) RowSeparator()
                            SettingControlRow(
                                setting = setting,
                                open = openSettingKey == setting.key,
                                onOpen = { open(setting) },
                                onClose = { openSettingKey = null },
                                onChange = onChange,
                                catalogStatus = state.modelCatalogStatus,
                                catalogError = state.modelCatalogError,
                                showGroup = true,
                            )
                        }
                        shortcutMatches.forEachIndexed { index, shortcut ->
                            if (index > 0 || matches.isNotEmpty()) RowSeparator(inset = RowLabelInset)
                            ShortcutRow(
                                shortcut = shortcut,
                                expanded = expandedShortcut == shortcut.title,
                                onToggle = {
                                    expandedShortcut =
                                        if (expandedShortcut == shortcut.title) null else shortcut.title
                                    expandedGroup = null
                                    openSettingKey = null
                                },
                            )
                        }
                    }
                }
            }
            return@LazyColumn
        }

        // One card per section, not one per category. Every category used to be its own detached
        // slab with a gap under it, so a screen of five related categories read as five unrelated
        // buttons. They are rows of one surface now, separated by a hairline that starts where the
        // labels start — the same shape the rest of the app already uses for a list.
        sectionNames.forEach { section ->
            val sectionGroups = sections[section].orEmpty()
            val sectionShortcuts = shortcutSections[section].orEmpty()
            item(key = "section_$section") { SectionLabel(section) }
            item(key = "section_body_$section") {
                ListGroup {
                    sectionGroups.forEachIndexed { index, group ->
                        if (index > 0) RowSeparator(inset = RowLabelInset)
                        SettingsCategory(
                            group = group,
                            expanded = expandedGroup == group.name,
                            onToggle = {
                                expandedGroup =
                                    if (expandedGroup == group.name) null else group.name
                                expandedShortcut = null
                                openSettingKey = null
                            },
                            openSettingKey = openSettingKey,
                            onOpenSetting = open,
                            onCloseSetting = { openSettingKey = null },
                            onChange = onChange,
                            catalogStatus = state.modelCatalogStatus,
                            catalogError = state.modelCatalogError,
                            nested = nestedShortcuts[group.name].orEmpty(),
                            expandedShortcut = expandedShortcut,
                            // Deliberately not clearing expandedGroup: this row lives inside that
                            // group, and closing the group would take the row away with it.
                            onToggleShortcut = { title ->
                                expandedShortcut = if (expandedShortcut == title) null else title
                                openSettingKey = null
                            },
                        )
                    }
                    sectionShortcuts.forEachIndexed { index, shortcut ->
                        if (index > 0 || sectionGroups.isNotEmpty()) {
                            RowSeparator(inset = RowLabelInset)
                        }
                        ShortcutRow(
                            shortcut = shortcut,
                            expanded = expandedShortcut == shortcut.title,
                            onToggle = {
                                expandedShortcut =
                                    if (expandedShortcut == shortcut.title) null else shortcut.title
                                expandedGroup = null
                                openSettingKey = null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A shortcut row, and its content underneath when it carries one.
 *
 * Setup and the log are the reason this exists. Both were pages — Setup was a full-screen takeover
 * that appeared over everything, which is the one interaction the console had left that could not be
 * dismissed by scrolling. They are content, so they push, clipped to [PushClipHeight] and scrolling
 * inside themselves so that opening the log does not add four thousand pixels to the page.
 */
@Composable
private fun ShortcutRow(
    shortcut: SettingsShortcut,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (shortcut.panel == null) {
        ListRow(
            title = shortcut.title,
            subtitle = shortcut.subtitle,
            icon = shortcut.icon,
            danger = shortcut.danger,
            onClick = shortcut.onClick,
            trailing = shortcut.trailing,
        )
        return
    }
    val turn by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "shortcut-chevron",
    )
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            title = shortcut.title,
            subtitle = shortcut.subtitle,
            icon = shortcut.icon,
            danger = shortcut.danger,
            onClick = onToggle,
            trailing = {
                BotLineIcon(
                    BotIcon.CHEVRON_RIGHT,
                    modifier = Modifier.size(18.dp).rotate(turn),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
                )
            },
        )
        PushPanel(expanded = expanded, maxHeight = PushClipHeight) {
            shortcut.panel.invoke()
        }
    }
}

/**
 * One category of the settings list, and its settings when it is open.
 *
 * The header is a single line. It used to carry "8 settings" underneath, which doubled the height of
 * every row on the page to report a number nobody acts on; what is worth reporting is whether this
 * category has been touched, so that is what the right-hand side says and only when it is true.
 */
@Composable
private fun SettingsCategory(
    group: SettingGroupModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    openSettingKey: String?,
    onOpenSetting: (UiSetting) -> Unit,
    onCloseSetting: () -> Unit,
    onChange: (String, String) -> Unit,
    catalogStatus: UiCatalogStatus,
    catalogError: String?,
    /** Destinations that belong to this category's subject rather than beside it. */
    nested: List<SettingsShortcut> = emptyList(),
    expandedShortcut: String? = null,
    onToggleShortcut: (String) -> Unit = {},
) {
    val rows = remember(group) { group.settings.distinctBy(UiSetting::key) }
    val turn by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "settings-category-chevron",
    )
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            title = group.name,
            value = countLabel(group.changed, "change", "changes").takeIf { group.changed > 0 },
            icon = GROUP_ICONS[group.name],
            onClick = onToggle,
            trailing = {
                BotLineIcon(
                    BotIcon.CHEVRON_RIGHT,
                    modifier = Modifier.size(18.dp).rotate(turn),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
                )
            },
        )
        PushPanel(expanded = expanded) {
            rows.forEachIndexed { index, setting ->
                if (index > 0) RowSeparator()
                SettingControlRow(
                    setting = setting,
                    open = openSettingKey == setting.key,
                    onOpen = { onOpenSetting(setting) },
                    onClose = onCloseSetting,
                    onChange = onChange,
                    catalogStatus = catalogStatus,
                    catalogError = catalogError,
                )
            }
            nested.forEachIndexed { index, shortcut ->
                if (index > 0 || rows.isNotEmpty()) RowSeparator(inset = RowLabelInset)
                ShortcutRow(
                    shortcut = shortcut,
                    expanded = expandedShortcut == shortcut.title,
                    onToggle = { onToggleShortcut(shortcut.title) },
                )
            }
        }
    }
}

/**
 * Frequency-first filing for the one scrolling Settings page.
 *
 * The everyday section is the three categories a conversation can override — the ones with two
 * places to be set, so this is where people come to see what the global one actually is — plus the
 * three that are changed on their own. Everything else is filed by what it is about, and the
 * leftovers land in Advanced rather than in a category invented for them.
 *
 * Battery is in there too. It is not a settings category — it is a shortcut panel, because it also
 * carries the Android permission it depends on — but whether the link stays awake decides whether
 * anything answers at all, so it is filed by how often it is opened like everything else and not
 * parked at the bottom under Advanced.
 */
private fun settingsSection(group: String): String =
    when (group) {
        "Persona" -> "Persona"
        in PER_CHAT_GROUPS, "Models", "Voice messages" -> "Everyday"
        "Human behaviour", "Reply style", "Context & memory" -> "Behaviour & memory"
        "Media" -> "Media"
        "Access", "Privacy", "Safety" -> "Safety & access"
        else -> "Advanced"
    }

private fun settingsSectionRank(section: String): Int =
    when (section) {
        "Everyday" -> 0
        "Persona" -> 1
        "Media" -> 2
        "Behaviour & memory" -> 3
        "Safety & access" -> 4
        "Advanced" -> 5
        "Danger zone" -> 6
        else -> 7
    }

/**
 * The three global categories represented by per-conversation overrides, in the same order as the
 * conversation sheet.
 *
 * The per-chat sheet reports each of these as "Global · …" — this list is where that global is set,
 * so the settings screen leads with the same three under the same names instead of filing them among
 * a dozen equal-looking siblings. Everything not named here is global-only by definition.
 */
internal val PER_CHAT_GROUPS =
    listOf("Persona", "Timing", "Proactivity")

/** The stored value of [BotSettingKeys.REPLY_PRESET] that turns the human timing model off. */
internal const val INSTANT_PRESET = "instant"

/**
 * One glyph per category, so the list can be scanned by shape instead of read label by label.
 *
 * Keyed by the label because that is what [UiSetting.group] carries. The set is closed —
 * [UiSettingsMapper.groupLabels] lists all of them — and a test pins every entry, so adding a
 * category cannot silently fall through to the generic icon.
 */
internal val GROUP_ICONS =
    mapOf(
        "Battery" to BotIcon.BATTERY,
        "Models" to BotIcon.SPARK,
        "Persona" to BotIcon.PERSON,
        "Timing" to BotIcon.HOURGLASS,
        "Proactivity" to BotIcon.SEND,
        "Human behaviour" to BotIcon.PULSE,
        "Voice messages" to BotIcon.MIC,
        "Reply style" to BotIcon.BUBBLE,
        "Media" to BotIcon.IMAGE,
        "Context & memory" to BotIcon.MEMORY,
        "Access" to BotIcon.KEY,
        "Privacy" to BotIcon.LOCK,
        "Safety" to BotIcon.SHIELD,
        "Network" to BotIcon.GLOBE,
        "Log" to BotIcon.LIST,
        "Connection" to BotIcon.LINK,
        "Status" to BotIcon.POWER,
    )

// ─────────────────────────────────────────────────────────────────────────────
// One setting, and the control it opens
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One setting in a list, with its editor attached to it rather than somewhere else.
 *
 * Which of the two disclosures a setting gets is decided here, once, by what the value *is*:
 *
 *  * a switch is the whole control and opens nothing;
 *  * a prompt or a typed value **pushes**, because it is written into and needs the keyboard, the
 *    description and the row it belongs to all on screen at the same time;
 *  * a number, a choice and a model catalogue **float**, because picking one is a single tap and the
 *    panel is gone again straight afterwards.
 */
@Composable
internal fun SettingControlRow(
    setting: UiSetting,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onChange: (String, String) -> Unit,
    catalogStatus: UiCatalogStatus,
    catalogError: String?,
    showGroup: Boolean = false,
) {
    val toggle = { if (open) onClose() else onOpen() }
    when (setting.kind) {
        UiSettingKind.BOOLEAN -> {
            val checked = setting.value.equals("true", ignoreCase = true)
            ListRow(
                title = setting.label,
                subtitle = if (showGroup) setting.group else null,
                onClick = { onChange(setting.key, (!checked).toString()) },
                trailing = {
                    Switch(
                        checked = checked,
                        onCheckedChange = { onChange(setting.key, it.toString()) },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                    )
                },
            )
        }

        UiSettingKind.MULTILINE,
        UiSettingKind.TEXT,
        UiSettingKind.TIME,
        -> WrittenSetting(setting, showGroup, open, toggle, onChange, onClose)

        UiSettingKind.MODEL ->
            FloatAnchor(
                expanded = open,
                onDismiss = onClose,
                maxHeight = 340.dp,
                panel = {
                    ModelPanel(
                        setting = setting,
                        catalogStatus = catalogStatus,
                        catalogError = catalogError,
                        onPick = { value ->
                            onChange(setting.key, value)
                            onClose()
                        },
                    )
                },
            ) { ValueRow(setting, showGroup, open, toggle) }

        UiSettingKind.TIME_RANGE ->
            FloatAnchor(
                expanded = open,
                onDismiss = onClose,
                maxHeight = 190.dp,
                panel = {
                    QuietHoursPanel(
                        setting = setting,
                        onChange = onChange,
                        onFinished = onClose,
                    )
                },
            ) { ValueRow(setting, showGroup, open, toggle) }

        UiSettingKind.ENUM,
        UiSettingKind.TIMEZONE,
        ->
            FloatAnchor(
                expanded = open,
                onDismiss = onClose,
                panel = {
                    ChoicePanel(
                        setting = setting,
                        onPick = { value ->
                            onChange(setting.key, value)
                            onClose()
                        },
                    )
                },
            ) { ValueRow(setting, showGroup, open, toggle) }

        UiSettingKind.ENUM_SLIDER, UiSettingKind.INTEGER, UiSettingKind.DECIMAL ->
            if (setting.sliderable) {
                FloatAnchor(
                    expanded = open,
                    onDismiss = onClose,
                    maxHeight = 190.dp,
                    panel = {
                        SliderPanel(
                            setting = setting,
                            onChange = onChange,
                            onFinished = onClose,
                        )
                    },
                ) { ValueRow(setting, showGroup, open, toggle) }
            } else {
                // No draggable range, so this is a keyboard — and a keyboard belongs in a push.
                WrittenSetting(setting, showGroup, open, toggle, onChange, onClose)
            }
    }
}

/**
 * Whether a number can be dragged rather than typed.
 *
 * The mapper only publishes bounds for ranges narrow enough to cross with a fingertip; a character
 * budget of zero to a hundred thousand has no useful slider, so it stays a typed field. That single
 * fact decides both the control and how it opens, which is why it is asked once, here.
 */
private val UiSetting.sliderable: Boolean
    get() =
        when (kind) {
            UiSettingKind.ENUM_SLIDER -> options.isNotEmpty()
            UiSettingKind.INTEGER, UiSettingKind.DECIMAL ->
                minimum != null && maximum != null && maximum > minimum
            else -> false
        }

/** A row whose value is written, so the field arrives by pushing the page rather than covering it. */
@Composable
private fun WrittenSetting(
    setting: UiSetting,
    showGroup: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onChange: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ValueRow(setting, showGroup, open, onToggle)
        PushPanel(expanded = open) {
            WritingPanel(
                setting = setting,
                onApply = { value ->
                    onChange(setting.key, value)
                    onClose()
                },
            )
        }
    }
}

/**
 * The row itself: what it is on the left, what it is set to on the right.
 *
 * The description is gone from this line on purpose. Every row carrying two lines of explanation
 * turned a category of eight settings into a page and a half of prose that says the same thing the
 * label already says. Where an explanation genuinely helps it is inside the opened control, next to
 * what it explains.
 */
@Composable
private fun ValueRow(
    setting: UiSetting,
    showGroup: Boolean,
    open: Boolean,
    onClick: () -> Unit,
) {
    ListRow(
        title = setting.label,
        subtitle = if (showGroup) setting.group else null,
        value = settingDisplayValue(setting),
        onClick = onClick,
        trailing = {
            val turn by animateFloatAsState(
                targetValue = if (open) 90f else 0f,
                animationSpec = tween(durationMillis = 160),
                label = "setting-chevron",
            )
            BotLineIcon(
                BotIcon.CHEVRON_RIGHT,
                modifier = Modifier.size(16.dp).rotate(turn),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
            )
        },
    )
}

/**
 * The floating slider, with the value beside it.
 *
 * Writes on release rather than on every pixel of the drag: each write goes through the settings
 * repository and out to the running engine, and a continuous drag would send fifty of them.
 */
@Composable
private fun SliderPanel(
    setting: UiSetting,
    onChange: (String, String) -> Unit,
    onFinished: () -> Unit,
) {
    val options =
        remember(setting.key, setting.options) {
            if (setting.kind == UiSettingKind.ENUM_SLIDER) {
                setting.options.distinctBy { it.first }
            } else {
                emptyList()
            }
        }
    val stepped = options.isNotEmpty()
    val min = if (stepped) 0.0 else setting.minimum
    val max = if (stepped) options.lastIndex.toDouble() else setting.maximum

    // Guaranteed by UiSetting.sliderable at the call site; a setting without a range is a field.
    if (min == null || max == null || max <= min) return

    // Even top and bottom. It used to pad two above the track and eight below, and a track sitting
    // off-centre in its own box is exactly what made the control look broken rather than plain.
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        var position by remember(setting.key, setting.value, options) {
            mutableFloatStateOf(
                if (stepped) {
                    options.indexOfFirst { it.first == setting.value }.coerceAtLeast(0).toFloat()
                } else {
                    setting.value.toFloatOrNull()?.coerceIn(min.toFloat(), max.toFloat())
                        ?: min.toFloat()
                },
            )
        }
        val encoded = {
            if (stepped) {
                options.getOrNull(position.roundToInt())?.first
            } else if (setting.kind == UiSettingKind.INTEGER) {
                position.roundToInt().toString()
            } else {
                "%.2f".format(position)
            }
        }
        val shown =
            if (stepped) {
                options.getOrNull(position.roundToInt())?.second ?: setting.value
            } else if (setting.kind == UiSettingKind.INTEGER) {
                position.roundToInt().toString()
            } else {
                "%.2f".format(position)
            }
        Row(
            // Starts where the row's label starts and ends where the row's value ends — the row
            // inset plus its chevron column — so the number does not jump sideways when the slider
            // opens underneath the number it is editing.
            modifier = Modifier.fillMaxWidth().padding(start = RowTextInset, end = ValueColumnInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotSlider(
                value = position,
                onValueChange = { position = it },
                valueRange = min.toFloat()..max.toFloat(),
                steps = sliderSteps(setting.kind, stepped, options.size, min, max),
                modifier = Modifier.weight(1f),
                onValueChangeFinished = {
                    encoded()?.let { onChange(setting.key, it) }
                    onFinished()
                },
            )
            Text(
                shown,
                modifier = Modifier.padding(start = 14.dp).widthIn(min = 40.dp, max = 96.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The night, as one thing you drag the ends of.
 *
 * The axis starts at noon rather than at midnight, and that is the whole trick. Quiet hours are a
 * night, so on a 00:00–24:00 axis the common ones — 22:00 to 07:00 — wrap around the end and cannot
 * be drawn as a band at all, which is why this used to be two separate clock fields. Anchored at
 * noon, every window that contains midnight is contiguous, and the only spans this cannot express
 * are the ones that contain *noon*, which is not what quiet hours are for.
 *
 * Writes on release, and only the half that moved: each write goes through the settings repository
 * and out to the running engine.
 */
@Composable
private fun QuietHoursPanel(
    setting: UiSetting,
    onChange: (String, String) -> Unit,
    onFinished: () -> Unit,
) {
    val stored = remember(setting.value) { quietHoursAxis(setting.value) }
    var span by remember(setting.value) { mutableStateOf(stored) }
    val startClock = clockOfAxis(span.start.roundToInt())
    val endClock = clockOfAxis(span.endInclusive.roundToInt())
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = RowTextInset, end = ValueColumnInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${clockLabel(startClock)} – ${clockLabel(endClock)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                quietHoursLength(startClock, endClock),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        BotRangeSlider(
            value = span,
            onValueChange = { span = it },
            valueRange = 0f..DayMinutes.toFloat(),
            steps = DayMinutes / QuietHoursStepMinutes - 1,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = RowTextInset, end = ValueColumnInset, top = 2.dp),
            onValueChangeFinished = {
                val start = clockOfAxis(span.start.roundToInt())
                val end = clockOfAxis(span.endInclusive.roundToInt())
                val (storedStart, storedEnd) = quietHoursClocks(setting.value)
                if (clockLabel(start) != storedStart) {
                    onChange(BotSettingKeys.SLEEP_START, clockLabel(start))
                }
                if (clockLabel(end) != storedEnd) {
                    onChange(BotSettingKeys.SLEEP_END, clockLabel(end))
                }
                onFinished()
            },
        )
        // The ends of the axis and the middle of the night, so the band can be read against
        // something. Four labels rather than a tick per hour: this is a scale, not a timetable.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = RowTextInset, end = ValueColumnInset),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("12:00", "18:00", "00:00", "06:00", "12:00").forEach { mark ->
                Text(
                    mark,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                )
            }
        }
    }
}

/** Minutes in a day, and the resolution the quiet-hours slider snaps to. */
private const val DayMinutes = 24 * 60
private const val QuietHoursStepMinutes = 15
private const val NoonMinutes = 12 * 60

/** `"00:30"` → 30. Anything unreadable is left to the caller's default. */
private fun clockMinutes(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** 30 → `"00:30"`. */
private fun clockLabel(minutes: Int): String {
    val wrapped = ((minutes % DayMinutes) + DayMinutes) % DayMinutes
    return "%02d:%02d".format(wrapped / 60, wrapped % 60)
}

/** Both stored halves as they are written, for comparison against what the slider produced. */
private fun quietHoursClocks(value: String): Pair<String, String> {
    val halves = value.split('–')
    return halves.getOrNull(0).orEmpty().trim() to halves.getOrNull(1).orEmpty().trim()
}

/** The row's right-hand value: `00:30 – 08:30`. */
internal fun quietHoursText(
    start: String,
    end: String,
): String = "${start.trim()} – ${end.trim()}"

/** Clock minutes → the noon-anchored axis the slider is drawn on, and back. */
private fun axisOfClock(minutes: Int): Int = ((minutes - NoonMinutes) + DayMinutes) % DayMinutes

private fun clockOfAxis(axis: Int): Int = (axis + NoonMinutes) % DayMinutes

/**
 * The stored pair as a band on the axis.
 *
 * A window that contains noon cannot be one — only an import or an admin command can produce it —
 * so it is shown running to the end of the axis rather than refusing to open. One drag fixes it.
 */
private fun quietHoursAxis(value: String): ClosedFloatingPointRange<Float> {
    val (start, end) = quietHoursClocks(value)
    val from = axisOfClock(clockMinutes(start) ?: 30)
    val to = axisOfClock(clockMinutes(end) ?: 510)
    return from.toFloat()..(if (to > from) to else DayMinutes).toFloat()
}

/** `8 h` / `8 h 30` — how long the bot is actually away. */
private fun quietHoursLength(
    startClock: Int,
    endClock: Int,
): String {
    val minutes = ((endClock - startClock) + DayMinutes) % DayMinutes
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours h" else "$hours h $rest"
}

/**
 * Notches, but only where they mean something.
 *
 * A stepped list has one notch per option. A number gets them only over a range short enough that
 * every stop is reachable with a fingertip; over a wider one the track is continuous, because a
 * hundred invisible notches are just a rough slider.
 */
private fun sliderSteps(
    kind: UiSettingKind,
    stepped: Boolean,
    optionCount: Int,
    min: Double,
    max: Double,
): Int =
    when {
        stepped -> (optionCount - 2).coerceAtLeast(0)
        kind == UiSettingKind.INTEGER && max - min <= 20 -> (max - min).toInt().minus(1).coerceAtLeast(0)
        else -> 0
    }

/** The floating list of choices. Scrolls inside the panel once there are more than fit. */
@Composable
private fun ChoicePanel(
    setting: UiSetting,
    onPick: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        items(setting.options, key = { it.first }) { (value, label) ->
            ChoiceLine(label = label, active = value == setting.value) { onPick(value) }
        }
    }
}

@Composable
internal fun ChoiceLine(
    label: String,
    active: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 38.dp)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (active) {
            BotLineIcon(
                BotIcon.CHECK,
                Modifier.padding(start = 10.dp).size(16.dp),
                MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The model catalogue, floating under the row that opened it.
 *
 * Search is the only catalogue partition. Vendor tabs duplicated that job, consumed a complete row
 * and made a compact picker read like a lab browser. Results stay newest-first and page in twenties.
 */
@Composable
private fun ModelPanel(
    setting: UiSetting,
    catalogStatus: UiCatalogStatus,
    catalogError: String?,
    onPick: (String) -> Unit,
) {
    var query by remember(setting.key) { mutableStateOf("") }
    val matches = remember(setting.options, query) { ModelPickerPresentation.matches(setting.options, query) }
    var visibleCount by remember(setting.key, query, setting.options) {
        mutableIntStateOf(ModelPickerPresentation.firstVisibleCount(matches.size))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    LaunchedEffect(listState, matches.size, visibleCount) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (ModelPickerPresentation.shouldLoadMore(lastVisible, visibleCount, matches.size)) {
                    visibleCount = ModelPickerPresentation.nextVisibleCount(visibleCount, matches.size)
                }
            }
    }
    val rows =
        remember(matches, visibleCount, setting.value) {
            ModelPickerPresentation.page(matches, visibleCount, setting.value)
        }
    val placeholder =
        remember(catalogStatus, setting.options, matches.size) {
            val catalogSize =
                setting.options.count { (_, label) ->
                    !label.contains("current, not in the catalogue", ignoreCase = true)
                }
            ModelPickerPresentation.placeholder(
                catalogStatus,
                catalogSize,
                if (catalogSize == 0) 0 else matches.size,
            )
        }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)) {
            BotTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search or paste a slug",
                leading = {
                    BotLineIcon(
                        BotIcon.SEARCH,
                        Modifier.size(16.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        if (placeholder != ModelPickerPlaceholder.NONE) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(
                    when (placeholder) {
                        ModelPickerPlaceholder.LOADING -> "Loading the catalogue …"
                        ModelPickerPlaceholder.ERROR ->
                            catalogError ?: "The catalogue could not be loaded."
                        ModelPickerPlaceholder.EMPTY_CATALOG -> "No compatible models."
                        else -> "No match."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Typing a slug stays possible in every one of these states: a picker that cannot
                // reach the catalogue must not also block setting a model by hand.
                if (placeholder != ModelPickerPlaceholder.LOADING && query.isNotBlank()) {
                    ChoiceLine(label = "Use “${query.trim()}”", active = false) { onPick(query.trim()) }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = 4.dp),
        ) {
            items(rows, key = ModelPickerRow::value) { row ->
                ChoiceLine(
                    label = row.label.substringBefore('\n'),
                    active = row.selected,
                    subtitle = row.label.substringAfter('\n', "").takeIf(String::isNotBlank),
                ) { onPick(row.value) }
            }
            if (visibleCount < matches.size) {
                item(key = "more") {
                    Text(
                        "${ModelPickerPresentation.remaining(visibleCount, matches.size)} more …",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The writing window, pushed rather than floating.
 *
 * This is the one control that must not cover anything: it is the base prompt and the typed values,
 * the things that are composed against what is around them, with a keyboard open over the bottom
 * half of the screen. It grows the page, and the page scrolls to it.
 */
@Composable
private fun WritingPanel(
    setting: UiSetting,
    onApply: (String) -> Unit,
) {
    var value by remember(setting.key, setting.value) { mutableStateOf(setting.value) }
    val multiline = setting.kind == UiSettingKind.MULTILINE
    val shortValue = setting.kind == UiSettingKind.TIME || setting.kind == UiSettingKind.INTEGER ||
        setting.kind == UiSettingKind.DECIMAL
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (multiline) {
            BotTextField(
                value = value,
                onValueChange = { value = it },
                label = null,
                singleLine = false,
                minLines = 4,
                maxLines = 8,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${value.length} chars",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkButton("Save", enabled = value != setting.value) { onApply(value) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = null,
                    modifier = if (shortValue) Modifier.width(168.dp) else Modifier.weight(1f),
                    keyboardType =
                        if (setting.kind == UiSettingKind.INTEGER ||
                            setting.kind == UiSettingKind.DECIMAL
                        ) {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Text
                        },
                )
                if (shortValue) Spacer(Modifier.weight(1f))
                LinkButton("Save", enabled = value != setting.value) { onApply(value) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Short right-hand representation of a value for the collapsed row. */
internal fun settingDisplayValue(setting: UiSetting): String {
    val raw =
        when (setting.kind) {
            UiSettingKind.ENUM,
            UiSettingKind.ENUM_SLIDER,
            UiSettingKind.MODEL,
            // Only the name identifies the current choice here; the price/date second line belongs
            // to the picker rows, where two candidates are actually being compared.
            ->
                setting.options
                    .firstOrNull { it.first == setting.value }
                    ?.second
                    ?.substringBefore('\n')
                    ?: setting.value
            else -> setting.value
        }
    val single = raw.replace('\n', ' ').trim()
    return when {
        single.isEmpty() -> "empty"
        // The row gives this a capped right-hand column, so what is shown has to survive being cut
        // at a readable length rather than at the first ellipsis.
        single.length > 40 -> single.take(39) + "…"
        else -> single
    }
}

internal fun UiSetting.matches(query: String): Boolean =
    label.contains(query, ignoreCase = true) ||
        key.contains(query, ignoreCase = true) ||
        group.contains(query, ignoreCase = true) ||
        description.contains(query, ignoreCase = true)

internal fun countLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"
