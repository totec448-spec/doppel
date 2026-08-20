package de.totec.doppel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Small dependency-free line icon set matching the app's quiet monochrome UI.
 *
 * Keeping these in Canvas avoids adding a large icon artifact for seven tiny
 * symbols and makes their weight/color consistent in every navigation state.
 */
enum class BotIcon {
    HOME,
    SLIDERS,
    SHIELD,
    TOOLS,
    ACTIVITY,
    HELP,
    BOT,
    CHECK,
    LINK,
    KEY,
    PERSON,
    CHEVRON,
    CHEVRON_RIGHT,
    ARROW_LEFT,
    SEARCH,
    MEMORY,
    COPY,
    CLOSE,
    PLUS,
    TRASH,
    REFRESH,
    POWER,
    SEND,
    IMAGE,
    BATTERY,
    WARNING,
    FILTER,
    SPARK,
    HOURGLASS,
    PULSE,
    MIC,
    BUBBLE,
    LOCK,
    GLOBE,
    LIST,
    MORE,
    DOWNLOAD,
}

@Composable
fun BotLineIcon(
    icon: BotIcon,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val strokeWidth = (minOf(width, height) * 0.085f).coerceAtLeast(2f)
        val stroke =
            Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )

        fun line(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ) {
            drawLine(
                color = color,
                start = Offset(width * x1, height * y1),
                end = Offset(width * x2, height * y2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        when (icon) {
            BotIcon.HOME -> {
                val path =
                    Path().apply {
                        moveTo(width * .16f, height * .48f)
                        lineTo(width * .50f, height * .18f)
                        lineTo(width * .84f, height * .48f)
                        lineTo(width * .76f, height * .48f)
                        lineTo(width * .76f, height * .84f)
                        lineTo(width * .24f, height * .84f)
                        lineTo(width * .24f, height * .48f)
                        close()
                    }
                drawPath(path, color, style = stroke)
            }

            BotIcon.SLIDERS -> {
                line(.18f, .25f, .82f, .25f)
                line(.18f, .50f, .82f, .50f)
                line(.18f, .75f, .82f, .75f)
                drawCircle(color, strokeWidth * .95f, Offset(width * .36f, height * .25f))
                drawCircle(color, strokeWidth * .95f, Offset(width * .66f, height * .50f))
                drawCircle(color, strokeWidth * .95f, Offset(width * .45f, height * .75f))
            }

            BotIcon.SHIELD -> {
                val path =
                    Path().apply {
                        moveTo(width * .50f, height * .12f)
                        lineTo(width * .80f, height * .24f)
                        lineTo(width * .76f, height * .62f)
                        cubicTo(
                            width * .73f,
                            height * .76f,
                            width * .61f,
                            height * .84f,
                            width * .50f,
                            height * .90f,
                        )
                        cubicTo(
                            width * .39f,
                            height * .84f,
                            width * .27f,
                            height * .76f,
                            width * .24f,
                            height * .62f,
                        )
                        lineTo(width * .20f, height * .24f)
                        close()
                    }
                drawPath(path, color, style = stroke)
                line(.38f, .51f, .47f, .61f)
                line(.47f, .61f, .65f, .41f)
            }

            BotIcon.TOOLS -> {
                line(.24f, .78f, .68f, .34f)
                drawCircle(
                    color,
                    radius = width * .12f,
                    center = Offset(width * .25f, height * .77f),
                    style = stroke,
                )
                val path =
                    Path().apply {
                        moveTo(width * .56f, height * .28f)
                        cubicTo(
                            width * .65f,
                            height * .12f,
                            width * .83f,
                            height * .15f,
                            width * .87f,
                            height * .17f,
                        )
                        lineTo(width * .72f, height * .33f)
                        lineTo(width * .84f, height * .45f)
                        cubicTo(
                            width * .72f,
                            height * .54f,
                            width * .59f,
                            height * .45f,
                            width * .56f,
                            height * .28f,
                        )
                    }
                drawPath(path, color, style = stroke)
            }

            BotIcon.ACTIVITY -> {
                drawCircle(
                    color,
                    radius = width * .34f,
                    center = Offset(width * .50f, height * .50f),
                    style = stroke,
                )
                line(.50f, .50f, .50f, .29f)
                line(.50f, .50f, .68f, .61f)
            }

            BotIcon.HELP -> {
                drawCircle(
                    color,
                    radius = width * .36f,
                    center = Offset(width * .50f, height * .50f),
                    style = stroke,
                )
                val path =
                    Path().apply {
                        moveTo(width * .37f, height * .38f)
                        cubicTo(
                            width * .40f,
                            height * .24f,
                            width * .62f,
                            height * .24f,
                            width * .65f,
                            height * .38f,
                        )
                        cubicTo(
                            width * .67f,
                            height * .49f,
                            width * .51f,
                            height * .52f,
                            width * .50f,
                            height * .63f,
                        )
                    }
                drawPath(path, color, style = stroke)
                drawCircle(color, strokeWidth * .55f, Offset(width * .50f, height * .75f))
            }

            BotIcon.BOT -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .18f, height * .30f),
                    size = Size(width * .64f, height * .52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .14f),
                    style = stroke,
                )
                line(.50f, .30f, .50f, .18f)
                drawCircle(color, strokeWidth * .55f, Offset(width * .50f, height * .14f))
                drawCircle(color, strokeWidth * .70f, Offset(width * .37f, height * .53f))
                drawCircle(color, strokeWidth * .70f, Offset(width * .63f, height * .53f))
                line(.37f, .69f, .63f, .69f)
            }

            BotIcon.CHECK -> {
                line(.18f, .52f, .40f, .74f)
                line(.40f, .74f, .82f, .28f)
            }

            BotIcon.LINK -> {
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 190f,
                    useCenter = false,
                    topLeft = Offset(width * .10f, height * .33f),
                    size = Size(width * .48f, height * .40f),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = -45f,
                    sweepAngle = 190f,
                    useCenter = false,
                    topLeft = Offset(width * .42f, height * .27f),
                    size = Size(width * .48f, height * .40f),
                    style = stroke,
                )
                line(.36f, .55f, .64f, .45f)
            }

            BotIcon.KEY -> {
                drawCircle(
                    color,
                    radius = width * .18f,
                    center = Offset(width * .32f, height * .40f),
                    style = stroke,
                )
                line(.45f, .53f, .80f, .82f)
                line(.66f, .70f, .75f, .61f)
                line(.75f, .78f, .83f, .70f)
            }

            BotIcon.PERSON -> {
                drawCircle(
                    color,
                    radius = width * .17f,
                    center = Offset(width * .50f, height * .32f),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = 190f,
                    sweepAngle = 160f,
                    useCenter = false,
                    topLeft = Offset(width * .20f, height * .48f),
                    size = Size(width * .60f, height * .42f),
                    style = stroke,
                )
            }

            // Points down; callers rotate it 180° to signal an open section.
            BotIcon.CHEVRON -> {
                line(.26f, .40f, .50f, .63f)
                line(.50f, .63f, .74f, .40f)
            }

            // The "this row goes somewhere" affordance. Its own glyph rather than a rotated
            // CHEVRON so the stroke ends stay optically centred in the row.
            BotIcon.CHEVRON_RIGHT -> {
                line(.40f, .26f, .63f, .50f)
                line(.63f, .50f, .40f, .74f)
            }

            BotIcon.ARROW_LEFT -> {
                line(.20f, .50f, .82f, .50f)
                line(.20f, .50f, .45f, .26f)
                line(.20f, .50f, .45f, .74f)
            }

            BotIcon.PLUS -> {
                line(.50f, .22f, .50f, .78f)
                line(.22f, .50f, .78f, .50f)
            }

            BotIcon.TRASH -> {
                line(.18f, .28f, .82f, .28f)
                line(.40f, .28f, .42f, .18f)
                line(.60f, .28f, .58f, .18f)
                line(.42f, .18f, .58f, .18f)
                val path =
                    Path().apply {
                        moveTo(width * .26f, height * .28f)
                        lineTo(width * .31f, height * .84f)
                        lineTo(width * .69f, height * .84f)
                        lineTo(width * .74f, height * .28f)
                    }
                drawPath(path, color, style = stroke)
                line(.44f, .42f, .45f, .70f)
                line(.56f, .42f, .55f, .70f)
            }

            BotIcon.REFRESH -> {
                drawArc(
                    color = color,
                    startAngle = 60f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(width * .18f, height * .18f),
                    size = Size(width * .64f, height * .64f),
                    style = stroke,
                )
                line(.78f, .18f, .80f, .40f)
                line(.80f, .40f, .58f, .38f)
            }

            BotIcon.POWER -> {
                drawArc(
                    color = color,
                    startAngle = -60f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(width * .20f, height * .22f),
                    size = Size(width * .60f, height * .60f),
                    style = stroke,
                )
                line(.50f, .12f, .50f, .46f)
            }

            BotIcon.SEND -> {
                val path =
                    Path().apply {
                        moveTo(width * .16f, height * .50f)
                        lineTo(width * .84f, height * .20f)
                        lineTo(width * .58f, height * .84f)
                        lineTo(width * .47f, height * .55f)
                        close()
                    }
                drawPath(path, color, style = stroke)
                line(.47f, .55f, .84f, .20f)
            }

            BotIcon.IMAGE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .16f, height * .22f),
                    size = Size(width * .68f, height * .56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .10f),
                    style = stroke,
                )
                drawCircle(color, strokeWidth * .80f, Offset(width * .36f, height * .38f))
                val path =
                    Path().apply {
                        moveTo(width * .20f, height * .70f)
                        lineTo(width * .42f, height * .48f)
                        lineTo(width * .60f, height * .66f)
                        lineTo(width * .70f, height * .57f)
                        lineTo(width * .82f, height * .70f)
                    }
                drawPath(path, color, style = stroke)
            }

            BotIcon.BATTERY -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .14f, height * .32f),
                    size = Size(width * .62f, height * .36f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .07f),
                    style = stroke,
                )
                line(.82f, .43f, .82f, .57f)
                line(.24f, .50f, .40f, .50f)
            }

            BotIcon.WARNING -> {
                val path =
                    Path().apply {
                        moveTo(width * .50f, height * .16f)
                        lineTo(width * .88f, height * .82f)
                        lineTo(width * .12f, height * .82f)
                        close()
                    }
                drawPath(path, color, style = stroke)
                line(.50f, .40f, .50f, .58f)
                drawCircle(color, strokeWidth * .55f, Offset(width * .50f, height * .70f))
            }

            BotIcon.FILTER -> {
                line(.16f, .28f, .84f, .28f)
                line(.28f, .50f, .72f, .50f)
                line(.42f, .72f, .58f, .72f)
            }

            BotIcon.SPARK -> {
                val path =
                    Path().apply {
                        moveTo(width * .50f, height * .14f)
                        lineTo(width * .59f, height * .41f)
                        lineTo(width * .86f, height * .50f)
                        lineTo(width * .59f, height * .59f)
                        lineTo(width * .50f, height * .86f)
                        lineTo(width * .41f, height * .59f)
                        lineTo(width * .14f, height * .50f)
                        lineTo(width * .41f, height * .41f)
                        close()
                    }
                drawPath(path, color, style = stroke)
            }

            BotIcon.SEARCH -> {
                drawCircle(
                    color,
                    radius = width * .26f,
                    center = Offset(width * .44f, height * .44f),
                    style = stroke,
                )
                line(.64f, .64f, .84f, .84f)
            }

            // A stack of summary lines: the memory documents this app writes.
            BotIcon.MEMORY -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .20f, height * .14f),
                    size = Size(width * .60f, height * .72f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .12f),
                    style = stroke,
                )
                line(.33f, .34f, .67f, .34f)
                line(.33f, .50f, .67f, .50f)
                line(.33f, .66f, .55f, .66f)
            }

            BotIcon.COPY -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .30f, height * .16f),
                    size = Size(width * .52f, height * .58f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .08f),
                    style = stroke,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .18f, height * .28f),
                    size = Size(width * .52f, height * .58f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .08f),
                    style = stroke,
                )
            }

            BotIcon.CLOSE -> {
                line(.28f, .28f, .72f, .72f)
                line(.72f, .28f, .28f, .72f)
            }

            // Timing. An hourglass rather than a clock face, which would collide with ACTIVITY —
            // and delays are what this category is actually about, not the time of day.
            BotIcon.HOURGLASS -> {
                line(.26f, .16f, .74f, .16f)
                line(.26f, .84f, .74f, .84f)
                val top =
                    Path().apply {
                        moveTo(width * .32f, height * .18f)
                        lineTo(width * .68f, height * .18f)
                        lineTo(width * .50f, height * .50f)
                        close()
                    }
                val bottom =
                    Path().apply {
                        moveTo(width * .32f, height * .82f)
                        lineTo(width * .68f, height * .82f)
                        lineTo(width * .50f, height * .50f)
                        close()
                    }
                drawPath(top, color, style = stroke)
                drawPath(bottom, color, style = stroke)
            }

            // Human behaviour: a pulse line, the shortest way to draw "there is a person here".
            BotIcon.PULSE -> {
                val path =
                    Path().apply {
                        moveTo(width * .12f, height * .52f)
                        lineTo(width * .32f, height * .52f)
                        lineTo(width * .40f, height * .28f)
                        lineTo(width * .50f, height * .74f)
                        lineTo(width * .59f, height * .44f)
                        lineTo(width * .66f, height * .52f)
                        lineTo(width * .88f, height * .52f)
                    }
                drawPath(path, color, style = stroke)
            }

            BotIcon.MIC -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .38f, height * .12f),
                    size = Size(width * .24f, height * .44f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .12f),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(width * .24f, height * .36f),
                    size = Size(width * .52f, height * .40f),
                    style = stroke,
                )
                line(.50f, .76f, .50f, .88f)
            }

            BotIcon.BUBBLE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .14f, height * .18f),
                    size = Size(width * .72f, height * .50f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .14f),
                    style = stroke,
                )
                line(.34f, .68f, .28f, .86f)
                line(.28f, .86f, .50f, .68f)
            }

            BotIcon.LOCK -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(width * .22f, height * .46f),
                    size = Size(width * .56f, height * .38f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .10f),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(width * .32f, height * .18f),
                    size = Size(width * .36f, height * .36f),
                    style = stroke,
                )
                line(.32f, .36f, .32f, .46f)
                line(.68f, .36f, .68f, .46f)
            }

            BotIcon.GLOBE -> {
                drawCircle(
                    color,
                    radius = width * .34f,
                    center = Offset(width * .50f, height * .50f),
                    style = stroke,
                )
                line(.16f, .50f, .84f, .50f)
                drawOval(
                    color = color,
                    topLeft = Offset(width * .34f, height * .16f),
                    size = Size(width * .32f, height * .68f),
                    style = stroke,
                )
            }

            BotIcon.LIST -> {
                drawCircle(color, strokeWidth * .60f, Offset(width * .22f, height * .28f))
                drawCircle(color, strokeWidth * .60f, Offset(width * .22f, height * .50f))
                drawCircle(color, strokeWidth * .60f, Offset(width * .22f, height * .72f))
                line(.38f, .28f, .82f, .28f)
                line(.38f, .50f, .82f, .50f)
                line(.38f, .72f, .68f, .72f)
            }

            BotIcon.MORE -> {
                drawCircle(color, strokeWidth * .72f, Offset(width * .50f, height * .24f))
                drawCircle(color, strokeWidth * .72f, Offset(width * .50f, height * .50f))
                drawCircle(color, strokeWidth * .72f, Offset(width * .50f, height * .76f))
            }

            // Arrow down into an open tray. The tray is open at the top rather than a closed box
            // so it still reads as "out of here" at the 17 dp this is drawn at.
            BotIcon.DOWNLOAD -> {
                line(.50f, .14f, .50f, .60f)
                line(.30f, .42f, .50f, .62f)
                line(.70f, .42f, .50f, .62f)
                line(.18f, .66f, .18f, .86f)
                line(.18f, .86f, .82f, .86f)
                line(.82f, .66f, .82f, .86f)
            }
        }
    }
}

/**
 * The app mark: a neutral disc with the accent drawn *inside* it.
 *
 * It used to be a solid green disc, which made the loudest colour on every screen a decoration.
 * Green now only ever reports state, so the mark carries it as a line weight instead of a fill.
 */
