package de.totec.doppel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The app's whole visual vocabulary: a grouped list, a row, a label, a button, a field.
 *
 * What this replaces is one component — an expand-in-place card — that every screen used for
 * everything, so a destructive action, a setting group, an explanation and a status readout were
 * all the same dark rounded rectangle with a chevron. Nothing could be more important than anything
 * else, and every tap grew the page instead of going somewhere. Here a row that navigates looks
 * different from a row that toggles, which looks different from a row that destroys data, and
 * grouping is done by *containers* rather than by repeating a card outline per item.
 */

/** Horizontal margin every screen aligns to. */
val ScreenPadding = 20.dp

/** Where a row's text starts, and therefore where its separator starts. */
internal val RowTextInset = 16.dp

/**
 * Where a row's value column ends: the row inset plus the trailing chevron and its gap.
 *
 * A control that opens under a row lines its own value up with this, so the number stays put
 * between reading it and changing it.
 */
internal val ValueColumnInset = RowTextInset + 12.dp + 16.dp

/**
 * Where the *label* of a row that carries an icon starts.
 *
 * A separator between two icon rows is aligned to this instead of to [RowTextInset], so it begins
 * under the words rather than cutting the icon column in half.
 */
val RowLabelInset = 49.dp

// ─────────────────────────────────────────────────────────────────────────────
// Grouped list
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A container of related rows sharing one rounded surface.
 *
 * Separators are drawn by [ListGroup] itself rather than by the caller, so a group can never end up
 * with a divider under its last row or a missing one in the middle.
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

/** Separator between two rows of a [ListGroup], inset to the row's text column. */
@Composable
fun RowSeparator(inset: Dp = RowTextInset) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = inset)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * One line of a [ListGroup].
 *
 * Exactly one trailing affordance is shown, and which one it is says what the row does: a chevron
 * navigates, a [trailing] control (a switch) changes something in place, a bare [value] is
 * read-only. [danger] tints the title, which is the only styling destructive rows get — they are
 * separated into their own group instead of being coloured in among normal ones.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: BotIcon? = null,
    iconTint: Color? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier,
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = RowTextInset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            BotLineIcon(
                icon = icon,
                modifier = Modifier.size(19.dp),
                color =
                    iconTint
                        ?: if (danger) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                // Capped, or one long model slug squeezes the title it belongs to down to an
                // ellipsis and the row stops saying what it is.
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 150.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(12.dp))
                trailing()
            }

            onClick != null -> {
                Spacer(Modifier.width(8.dp))
                BotLineIcon(
                    icon = BotIcon.CHEVRON_RIGHT,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Headings
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The inline title at the top of a root screen.
 *
 * Root screens carry no app bar: the previous build spent ~90 dp of every screen on a fixed header
 * that repeated the avatar and the words "Connected · replies on" five times over. The title now
 * scrolls away with the content and the status lives in one place, on Overview.
 */
@Composable
fun LargeTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.displaySmall,
        )
        trailing?.invoke()
    }
}

/** Small caps label above a [ListGroup], with an optional action on the right. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

/** Top bar of a pushed detail screen. The back arrow is the only way this differs from a root. */
@Composable
fun DetailAppBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Back" }
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                BotLineIcon(BotIcon.ARROW_LEFT, Modifier.size(21.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions?.invoke(this)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status
// ─────────────────────────────────────────────────────────────────────────────

/** What the runtime is doing, reduced to the three things the UI needs to colour. */
enum class StatusTone { LIVE, WAITING, DOWN }

/**
 * The status dot, with a slow halo while live.
 *
 * The halo is the app's one piece of motion. It exists because "is it actually running right now"
 * is the single question this screen has to answer from across a room, and a static dot cannot
 * distinguish a live service from a screenshot of one.
 */
/**
 * Three numbers on one surface, split by dividers.
 *
 * Previously three separate cards, which read as three unrelated things floating in a row. They
 * are one fact — today's traffic — so they get one container.
 */
@Composable
fun StatStrip(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stats.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(30.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    label,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Read-only pill for a current value. */
/**
 * A tinted block of prose — an explanation, a warning, a consequence.
 *
 * This is what the old collapsed "what is this?" cards become. Hiding a two-line explanation behind
 * a tap cost a full row on every screen to save two, and meant the answer was never there when the
 * question came up.
 */
// ─────────────────────────────────────────────────────────────────────────────
// Controls
// ─────────────────────────────────────────────────────────────────────────────

/** The one filled button on a screen. There is never a second. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = MaterialTheme.shapes.small,
        colors =
            ButtonDefaults.buttonColors(
                containerColor =
                    if (danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                contentColor =
                    if (danger) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
            ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary action: an outlined shape on the neutral ramp, never coloured. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val content =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f), MaterialTheme.shapes.small)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two equal-width [QuietButton]s — the recurring "do / undo" pair in the tool screens. */
// ─────────────────────────────────────────────────────────────────────────────
// Hold to confirm
// ─────────────────────────────────────────────────────────────────────────────

/** How long a finger has to stay down before a destructive action fires. */
private const val HoldMillis = 3_000

/**
 * The hold measured in real milliseconds, reported as 0f..1f.
 *
 * This used to be `Animatable.animateTo(tween(HoldMillis))`, which was a silent hole in the only
 * safeguard the app has. Every tween is multiplied by the system animator duration scale, and with
 * developer-options animations turned off that scale is 0 — so the three-second sweep finished in
 * the frame the press started and the destructive action fired on touch-down. On 2026-08-08 that
 * deleted a whole persona chat from a tap of about a tenth of a second, on a phone reporting
 * `animator_duration_scale = 0.0`. Frame times are wall-clock and ignore the scale, so the hold is
 * a real three seconds on every device regardless of the user's animation settings.
 *
 * Returns to 0f the moment the finger lifts; [onElapsed] runs once, only on a completed hold.
 */
@Composable
private fun holdProgress(
    held: Boolean,
    onElapsed: () -> Unit,
): Float {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(held) {
        if (!held) {
            progress = 0f
            return@LaunchedEffect
        }
        val startedAt = withFrameMillis { it }
        var fraction = 0f
        while (fraction < 1f) {
            fraction =
                withFrameMillis { frame ->
                    ((frame - startedAt).toFloat() / HoldMillis).coerceIn(0f, 1f)
                }
            progress = fraction
        }
        onElapsed()
        progress = 0f
    }
    return progress
}

@Composable
private fun HoldGesture(
    description: String,
    onConfirm: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.error,
    fillAlpha: Float = .18f,
    alignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(Float) -> Unit,
) {
    var held by remember { mutableStateOf(false) }
    var accessibilityArmed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(accessibilityArmed) {
        if (accessibilityArmed) {
            delay(10_000L)
            accessibilityArmed = false
        }
    }
    val progress = holdProgress(held) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onConfirm()
        held = false
        accessibilityArmed = false
    }
    Box(
        modifier =
            modifier
                .drawBehind {
                    drawRect(
                        color = accent.copy(alpha = fillAlpha),
                        size = size.copy(width = size.width * progress),
                    )
                }
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    if (enabled) {
                        stateDescription =
                            if (accessibilityArmed) {
                                "Destructive action armed. Long press once more to confirm."
                            } else {
                                "Long press twice with a screen reader to confirm."
                            }
                        onLongClick("Confirm destructive action") {
                            if (accessibilityArmed) {
                                onConfirm()
                                accessibilityArmed = false
                            } else {
                                accessibilityArmed = true
                            }
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                held = true
                                tryAwaitRelease()
                                held = false
                            })
                        }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = alignment,
    ) {
        content(progress)
    }
}

/**
 * The only way anything in this app deletes data: three seconds of a finger held down.
 *
 * A confirmation dialog asks the same question twice and gets the same reflex twice — the second tap
 * lands in the same place the first one did, half a second later. Holding cannot be done by reflex.
 * It also removes the modal: the button that destroys the thing *is* the confirmation, so the
 * wording stays attached to what it describes instead of being restated in a box over it.
 *
 * Releasing early rewinds. The bar drains rather than snapping, so an accidental brush is visibly
 * nothing rather than invisibly nothing.
 */
@Composable
fun HoldToConfirm(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.error
    val idle = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
    HoldGesture(
        description = text,
        onConfirm = onConfirm,
        enabled = enabled,
        accent = accent,
        fillAlpha = .22f,
        alignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, idle.copy(alpha = .55f), MaterialTheme.shapes.small),
    ) { progress ->
        // Padding must live inside the draw layer. When it was part of [modifier], Compose applied
        // [HoldGesture]'s drawBehind after that padding and the red progress bar was only as wide as
        // the text. The gesture box now owns the whole outlined button and its child owns the inset.
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (progress > 0f) "Keep holding…" else text,
                style = MaterialTheme.typography.labelLarge,
                color = idle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A [ListRow] whose action needs the same three seconds.
 *
 * Used where the destructive thing belongs in a list rather than under a form — deleting one
 * persona's data, deleting everything — so it keeps a row's shape and gains a row's subtitle to say
 * what goes.
 */
@Composable
fun DangerRow(
    title: String,
    subtitle: String?,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    icon: BotIcon? = BotIcon.TRASH,
) {
    HoldGesture(listOfNotNull(title, subtitle).joinToString(". "), onConfirm, modifier.fillMaxWidth()) {
        progress ->
        ListRow(
            title = title,
            // A row that carries no subtitle does not grow one under the finger: the sweep of the
            // fill already says the hold registered, and a second line would move the row it is on.
            subtitle = subtitle?.let { if (progress > 0f) "Keep holding…" else it },
            icon = icon,
            danger = true,
        )
    }
}

/**
 * The app's only text input, so every field on every screen has the same shape and height.
 *
 * A one-line field shows [label] as a placeholder, not as a floating label. Material reserves half a
 * line of height above the border for a label to float into, and that reserved band is why every
 * panel built around a single field had visibly more air above its content than below it. What the
 * field is for is already written on the row that opened the panel. A multi-line field keeps the
 * floating label: it is an editor rather than an answer, its content scrolls, and the label is also
 * where its character counter lives.
 */
@Composable
fun BotTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    supporting: String? = null,
    secret: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label =
            label?.takeIf { !singleLine }?.let {
                { Text(it, style = MaterialTheme.typography.bodyMedium) }
            },
        placeholder =
            label?.takeIf { singleLine }?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingIcon = leading,
        trailingIcon = trailing,
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = MaterialTheme.shapes.small,
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .55f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
    )
}

/** How thick a [BotSlider] track is drawn, and how big its handle is. */
private val SliderTrackHeight = 5.dp
private val SliderThumbSize = 17.dp

/**
 * The app's only slider, so a value that is dragged looks the same everywhere it is dragged.
 *
 * Material draws its own as a wide vertical handle wedged between two fat segments with notches
 * stamped through them. At the size these are used — inside a floating panel, one per settings row —
 * that reads as two unrelated blocks rather than as one scale, which is why every slider in the app
 * looked wrong at once. Same control underneath: same drag, same stepping, same write-on-release.
 * What changed is a thin continuous track, a round handle in the saturated accent against a softer
 * filled section so the handle is findable without a ring around it, and notches only on the part
 * still to go, where they show the remaining stops instead of speckling the value you already chose.
 */
// Handing Slider its own thumb and track is still an experimental slot API. Opted in here once, in
// the one place the app draws a slider, rather than at every call site.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val accent = MaterialTheme.colorScheme.primary
    val handle = if (enabled) accent else accent.copy(alpha = .38f)
    val filled = accent.copy(alpha = if (enabled) .45f else .18f)
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .16f else .08f)
    val notch = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .30f else .14f)
    // Notches only where they can be counted; over a long range they turn the track into a dotted
    // line that says nothing the number beside it does not already say.
    val stops = if (steps in 1..14) steps + 2 else 0
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(SliderThumbSize).clip(CircleShape).background(handle)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(SliderThumbSize)) {
                val mid = size.height / 2f
                val thickness = SliderTrackHeight.toPx()
                val stop = size.width * fraction
                drawLine(empty, Offset(0f, mid), Offset(size.width, mid), thickness, StrokeCap.Round)
                if (stop > 0f) {
                    drawLine(filled, Offset(0f, mid), Offset(stop, mid), thickness, StrokeCap.Round)
                }
                if (stops > 1) {
                    val radius = thickness * .28f
                    repeat(stops) { index ->
                        val x = size.width * index / (stops - 1)
                        if (x > stop) drawCircle(notch, radius, Offset(x, mid))
                    }
                }
            }
        },
    )
}

/**
 * The same slider with two handles, for a value that is a span rather than a point.
 *
 * Drawn from [BotSlider]'s parts on purpose: a range control that looked like Material's and a
 * single-value control that looks like this one would read as two unrelated widgets on the same
 * page. The only difference is which part of the track is filled — here it is the middle, because
 * what is selected is between the handles rather than behind one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val from = ((value.start - valueRange.start) / span).coerceIn(0f, 1f)
    val to = ((value.endInclusive - valueRange.start) / span).coerceIn(from, 1f)
    val accent = MaterialTheme.colorScheme.primary
    val handle = if (enabled) accent else accent.copy(alpha = .38f)
    val filled = accent.copy(alpha = if (enabled) .45f else .18f)
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .16f else .08f)
    val thumb: @Composable () -> Unit = {
        Box(Modifier.size(SliderThumbSize).clip(CircleShape).background(handle))
    }
    RangeSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        startThumb = { thumb() },
        endThumb = { thumb() },
        track = {
            Canvas(Modifier.fillMaxWidth().height(SliderThumbSize)) {
                val mid = size.height / 2f
                val thickness = SliderTrackHeight.toPx()
                drawLine(empty, Offset(0f, mid), Offset(size.width, mid), thickness, StrokeCap.Round)
                val left = size.width * from
                val right = size.width * to
                if (right > left) {
                    drawLine(filled, Offset(left, mid), Offset(right, mid), thickness, StrokeCap.Round)
                }
            }
        },
        steps = steps,
    )
}

/** Height of an [InlineAddField] — and therefore the side of the square button that closes it. */
private val InlineAddHeight = 50.dp

/**
 * Type one thing, press the plus. The whole "add an entry to this list" interaction, in one line.
 *
 * The pattern it replaces was a boxed field with a floating label and a separate button underneath.
 * The box carried its own fill, so the field read as a card dropped onto the panel rather than as
 * part of it; the floating label reserved a band of empty space above the text, which is the gap
 * that opened between the last entry and the input on every access list; and the button below meant
 * the confirming tap was nowhere near the thing being confirmed. Here the field is the panel — no
 * fill of its own, one hairline, nothing underlined — and the end of the line is the button: a
 * square of solid accent the full height of the field, clipped into its corner, with the plus cut
 * out of it. A tinted circle floating inside the field read as decoration; a filled block reads as
 * the thing you press, which is the only reason it is there.
 */
@Composable
fun InlineAddField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val shape = RoundedCornerShape(14.dp)
    val ready = value.isNotBlank()
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(InlineAddHeight)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                // No end inset: the button owns the last square of the field, right into the corner.
                .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                cursorBrush = SolidColor(accent),
                keyboardOptions =
                    KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ready) onAdd() }),
            )
        }
        Box(
            modifier =
                Modifier
                    .size(InlineAddHeight)
                    .background(if (ready) accent else accent.copy(alpha = .22f))
                    .clickable(enabled = ready, onClick = onAdd)
                    .semantics { contentDescription = "Add" },
            contentAlignment = Alignment.Center,
        ) {
            BotLineIcon(
                icon = BotIcon.PLUS,
                modifier = Modifier.size(22.dp),
                // Dark on full green, green on the dimmed block: the plus stays the brightest thing
                // in the square either way.
                color = if (ready) MaterialTheme.colorScheme.onPrimary else accent,
            )
        }
    }
}

/**
 * Two halves of one decision, in one pill divided down the middle.
 *
 * For a pair that is the same operation in opposite directions — export and import, in and out. Two
 * separate full-width rows made them look like two unrelated things that happened to sit next to
 * each other, and each needed a subtitle to say which direction it went.
 */
@Composable
fun SplitPill(
    leftLabel: String,
    onLeft: () -> Unit,
    rightLabel: String,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    val edge = MaterialTheme.colorScheme.outline
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, edge, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillHalf(leftLabel, Modifier.weight(1f), onLeft)
        Box(Modifier.width(1.dp).height(52.dp).background(edge))
        PillHalf(rightLabel, Modifier.weight(1f), onRight)
    }
}

@Composable
private fun PillHalf(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.height(52.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Right-aligned "Default" style link used inside sheets and detail screens. */
@Composable
fun LinkButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Centred placeholder so an empty list still explains itself. */
@Composable
fun EmptyHint(
    text: String,
    // 40dp of air on both sides is right on a page whose whole job is to be empty. Inside a panel
    // that is six rows tall it is a hole, which is what it looked like under "No safety limit is
    // active."
    dense: Boolean = false,
) {
    Box(
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (dense) 14.dp else 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
