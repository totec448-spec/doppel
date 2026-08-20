package de.totec.doppel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import de.totec.doppel.ui.theme.OutlineSoft
import kotlin.math.min

/**
 * The two — and only two — ways anything in this app opens.
 *
 * There is no dialog, no modal sheet and no pushed page for a setting any more. What replaced them
 * is a single rule the whole console follows, so the shape of a control tells you what it will do
 * before you touch it:
 *
 *  * [PushPanel] — **content pushes.** Anything you read, write or work in: a category's settings, a
 *    prompt you type into, the setup form, the log. It slides the page down and never covers a
 *    thing, because covering the page is exactly what makes you lose your place in it. It is
 *    recognisable as belonging to the row above it by being inset from both edges — the panel sits
 *    *inside* the card the row is in, on a slightly lighter ground.
 *  * [FloatAnchor] — **values float.** A list of options, a slider, a model catalogue: one choice,
 *    then it is gone. It is allowed to cover what is under it precisely because it is temporary and
 *    small, it is exactly as wide as the row that opened it, and a tap anywhere else dismisses it.
 *
 * The reason for the split is what each one costs. Pushing a 400-row model catalogue would throw the
 * page you were reading half a screen down and leave you scrolling back; floating a prompt editor
 * would hide the conversation settings you are editing it against. Neither is a matter of taste —
 * each kind of content has one behaviour that is not annoying.
 */

/** How far a pushed panel is inset from its card, on both sides. This inset *is* the hierarchy. */
private val PushInset = 10.dp

/** How tall a pushed panel may grow before it scrolls inside itself rather than growing the page. */
val PushClipHeight = 460.dp

/**
 * A panel that pushes the page down, inset inside the row's own card.
 *
 * [maxHeight] turns it into the clipped kind: the panel stops growing and whatever is inside it
 * scrolls on its own. Give the content a scrollable of its own when passing this — the panel only
 * bounds the height, it does not scroll.
 */
@Composable
fun PushPanel(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    connectedToRecorder: Boolean = false,
    /** Something above already carries the rounded roof; this panel only continues its walls. */
    connectedAbove: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(animationSpec = tween(190)) + fadeIn(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(80)),
    ) {
        // Welded: round on top, square where it meets the field, so the two are one shape growing
        // upward out of the composer rather than a card parked above it.
        val roofRadius = if (connectedAbove) 0.dp else 24.dp
        val shape =
            if (connectedToRecorder) {
                RoundedCornerShape(topStart = roofRadius, topEnd = roofRadius)
            } else {
                MaterialTheme.shapes.small
            }
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (connectedToRecorder) 12.dp else PushInset,
                        end = if (connectedToRecorder) 12.dp else PushInset,
                        bottom = if (connectedToRecorder) 0.dp else PushInset,
                    )
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (connectedToRecorder) {
                            // Exact same visible hairline as the recorder/input field this panel
                            // is welded to; outlineVariant was too faint and read as no frame.
                            // Walls and roof only — a `border` would stroke the bottom edge too and
                            // draw a straight line across the seam with the field.
                            Modifier.drawBehind { drawPanelWalls(roofRadius) }
                        } else {
                            Modifier
                        },
                    )
                    .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier),
            content = content,
        )
    }
}

/**
 * A row and the value picker that floats under it.
 *
 * The panel is measured to the anchor's width and positioned directly under its bottom edge, so it
 * reads as the row having grown downwards rather than as a menu that appeared from nowhere. It is
 * focusable, which is what makes a tap outside — or the back gesture — close it.
 *
 * [maxHeight] is deliberately short. A floating panel that fills the screen is a dialog wearing a
 * different name, and the whole point of floating is that what is behind it stays visible.
 */
@Composable
fun FloatAnchor(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 330.dp,
    panel: @Composable ColumnScope.() -> Unit,
    row: @Composable () -> Unit,
) {
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val positionProvider =
        remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x =
                        anchorBounds.left.coerceIn(
                            0,
                            (windowSize.width - popupContentSize.width).coerceAtLeast(0),
                        )
                    val spaceBelow = windowSize.height - anchorBounds.bottom
                    val y =
                        if (
                            spaceBelow >= popupContentSize.height ||
                            spaceBelow >= anchorBounds.top
                        ) {
                            anchorBounds.bottom
                        } else {
                            (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
                        }
                    return IntOffset(x, y)
                }
            }
        }
    Box(
        modifier = modifier.fillMaxWidth().onSizeChanged { anchor = it },
        contentAlignment = Alignment.TopStart,
    ) {
        row()
        if (expanded && anchor.width > 0) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier =
                        Modifier
                            .width(with(density) { anchor.width.toDp() })
                            .shadow(18.dp, MaterialTheme.shapes.medium)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                MaterialTheme.shapes.medium,
                            )
                            .heightIn(max = maxHeight),
                    content = panel,
                )
            }
        }
    }
}

/**
 * The outline of a panel that stands on the input field: its two sides and its roof, and no floor.
 *
 * A `border` with a top-rounded shape strokes the bottom edge as well, and that edge sits exactly
 * where the panel meets the field — a straight hairline across the seam of two things that are meant
 * to read as one shape. Stroking an open path leaves that edge out, and the inset keeps the stroke a
 * full pixel wide inside the clip rather than half of one on the boundary.
 *
 * A [radius] of zero means this panel has no roof of its own: something above — the scheduled-reply
 * strip — already carries it. Then the top edge is left out for the same reason the bottom one is.
 * Drawing it put a straight hairline across that seam, and against the rounded shoulders directly
 * above it read as the panel having had its top corners cut off square.
 */
internal fun DrawScope.drawPanelWalls(radius: Dp) {
    val hairline = 1.dp.toPx()
    // A full hairline, not half of one. This draws inside a rounded clip, and a stroke centred on
    // the clip's own boundary loses its outer half to it: on the straight walls the surviving half
    // still reads as a line, but on the shoulders the arc's antialiasing and the clip's cancel each
    // other out and the corner disappears — the roof of the scheduled-reply strip had no visible
    // rounding at all, only two side walls that stopped short. Set in by the whole width, the line
    // lies entirely within the clip and the corners survive.
    val inset = hairline
    val left = inset
    val right = size.width - inset
    val top = inset
    val corner =
        min(radius.toPx() - inset, min(size.height, right - left) / 2f).coerceAtLeast(0f)
    if (corner <= 0f) {
        drawLine(OutlineSoft, Offset(left, 0f), Offset(left, size.height), hairline)
        drawLine(OutlineSoft, Offset(right, 0f), Offset(right, size.height), hairline)
        return
    }
    val path =
        Path().apply {
            moveTo(left, size.height)
            lineTo(left, top + corner)
            arcTo(Rect(left, top, left + 2 * corner, top + 2 * corner), 180f, 90f, false)
            lineTo(right - corner, top)
            arcTo(Rect(right - 2 * corner, top, right, top + 2 * corner), 270f, 90f, false)
            lineTo(right, size.height)
        }
    drawPath(path, OutlineSoft, style = Stroke(width = hairline))
}
