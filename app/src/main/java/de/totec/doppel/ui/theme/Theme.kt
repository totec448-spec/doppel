package de.totec.doppel.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkScheme =
    darkColorScheme(
        primary = Live,
        onPrimary = LiveInk,
        primaryContainer = LiveMuted,
        onPrimaryContainer = Live,
        secondary = TextMid,
        onSecondary = Base,
        secondaryContainer = Layer2,
        onSecondaryContainer = TextHigh,
        tertiary = Info,
        onTertiary = Base,
        tertiaryContainer = Layer2,
        onTertiaryContainer = Info,
        background = Base,
        onBackground = TextHigh,
        surface = Layer1,
        onSurface = TextHigh,
        surfaceVariant = Layer2,
        onSurfaceVariant = TextMid,
        surfaceContainerLowest = Base,
        surfaceContainerLow = Layer1,
        surfaceContainer = Layer1,
        surfaceContainerHigh = Layer2,
        surfaceContainerHighest = Layer3,
        outline = OutlineSoft,
        outlineVariant = Hairline,
        error = Broken,
        onError = BrokenInk,
        errorContainer = BrokenMuted,
        onErrorContainer = Broken,
        scrim = Base,
    )

private val LightScheme =
    lightColorScheme(
        primary = LiveDark,
        onPrimary = PaperLayer1,
        primaryContainer = Live.copy(alpha = .22f),
        onPrimaryContainer = LiveDark,
        secondary = PaperTextMid,
        background = PaperBase,
        onBackground = PaperTextHigh,
        surface = PaperLayer1,
        onSurface = PaperTextHigh,
        surfaceVariant = PaperLayer2,
        onSurfaceVariant = PaperTextMid,
        surfaceContainer = PaperLayer1,
        surfaceContainerHigh = PaperLayer2,
        outline = PaperTextMid,
        outlineVariant = PaperHairline,
        error = Broken,
    )

/**
 * The conversation is soft; controls stay restrained.
 *
 * A single stepped radius scale keeps inputs, lists and sheets related without turning every row
 * into a pill. The former universal 2dp radius made the complete settings surface look accidental
 * and harsh even though the spacing and icons were already sound.
 */
private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

@Composable
fun DoppelTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
