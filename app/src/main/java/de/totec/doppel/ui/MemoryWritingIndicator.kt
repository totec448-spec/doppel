package de.totec.doppel.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.totec.doppel.ui.theme.Info
import de.totec.doppel.engine.MemoryWork
import de.totec.doppel.engine.MemoryWorkPhase
import de.totec.doppel.ui.theme.Layer2
import de.totec.doppel.ui.theme.TextMid

/**
 * The one visible sign that a memory is being written.
 *
 * A memory write is a model call that can run for the better part of a minute, and until now every
 * control that started one — the chat menu, the persona card, the automatic write mid-conversation —
 * looked exactly the same during it as before it. So it got pressed again, and billed again. These
 * are the shared parts: one light that travels across the word, so the surface reads as *working*
 * rather than as *stuck*, and no surface has to invent its own idea of what that looks like.
 *
 * The sweep is deliberately slow. A fast shimmer reads as an error state; this one is closer to
 * breathing, which is the honest description of a call that will take another forty seconds.
 */
private const val SWEEP_MILLIS = 1900

/**
 * [text], with a bright band travelling left to right through it, over and over.
 *
 * The gradient is measured against the laid-out width rather than a guessed constant, so the light
 * crosses the whole word once per cycle whether the word is "Memory" or "Memory is being written".
 * Before the first measurement it falls back to flat dim text, which is what one frame of it looks
 * like anyway.
 */
@Composable
fun MemoryWritingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Info,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "memory-writing")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SWEEP_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "sweep",
    )
    val dim = color.copy(alpha = .38f)
    val brush =
        if (widthPx <= 0) {
            SolidColor(dim)
        } else {
            // The band is over half the word wide, so the transition is a glow passing through and
            // not a hard edge sliding past. It starts fully off the left and ends fully off the
            // right, which is why the travel is width + 2 * band.
            val band = widthPx * 0.6f
            val centre = sweep * (widthPx + 2f * band) - band
            Brush.linearGradient(
                0f to dim,
                0.5f to color,
                1f to dim,
                start = Offset(centre - band / 2f, 0f),
                end = Offset(centre + band / 2f, 0f),
            )
        }
    Text(
        text = text,
        modifier = modifier.onSizeChanged { widthPx = it.width },
        style = style.copy(brush = brush),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The same signal with no room for words: a dot that fades in and out.
 *
 * For rows that are already full — a settings entry, a list row whose title is the chat's name —
 * where the alternative would be truncating something the user actually came to read.
 */
@Composable
fun MemoryWritingDot(
    modifier: Modifier = Modifier,
    color: Color = Info,
    size: Dp = 7.dp,
) {
    val transition = rememberInfiniteTransition(label = "memory-writing-dot")
    val alpha by transition.animateFloat(
        initialValue = .25f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SWEEP_MILLIS / 2, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse",
    )
    Box(modifier.size(size).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/**
 * The in-chat divider, drawn while the write for that conversation is still running.
 *
 * It sits at the tail of the transcript in exactly the place the finished [ChatEntry.Memory] rule
 * will appear, and looks like it on purpose: when the call returns, the animated line is replaced by
 * the real one at the same spot, so the write reads as having landed where you were already looking.
 */
@Composable
fun MemoryWritingRule(
    work: MemoryWork,
    modifier: Modifier = Modifier,
) {
    // A running memory was previously collapsed by default, so the operator had to find and tap
    // the one-pixel rule after already tapping Create Memory. Open it immediately: this surface's
    // purpose is to show the stream.
    var expanded by remember(work.key) { mutableStateOf(true) }
    val draftScroll = rememberScrollState()
    LaunchedEffect(work.draft.length, expanded) {
        if (expanded) draftScroll.scrollTo(draftScroll.maxValue)
    }
    val transition = rememberInfiniteTransition(label = "memory-writing-rule")
    val glow by transition.animateFloat(
        initialValue = .12f,
        targetValue = .42f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SWEEP_MILLIS / 2, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "line",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(Info.copy(alpha = glow)))
            Spacer(Modifier.width(10.dp))
            MemoryWritingDot(size = 6.dp)
            Spacer(Modifier.width(6.dp))
            MemoryWritingText(
                text =
                    if (work.phase == MemoryWorkPhase.WRITING) {
                        "WRITING MEMORY"
                    } else {
                        "CREATING MEMORY"
                    },
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Info.copy(alpha = glow)))
        }
        if (expanded) {
            Text(
                text = if (work.draft.isBlank()) "Reasoning…" else work.draft,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(draftScroll)
                        .padding(top = 12.dp)
                        .background(Layer2, MaterialTheme.shapes.small)
                        .padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
        }
    }
}
