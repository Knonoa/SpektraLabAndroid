package com.spektralab.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Darkroom palette: warm blacks, paper whites, one safelight amber. */
object Palette {
    val Canvas = Color(0xFF0B0A09)        // behind the photo
    val Background = Color(0xFF13110F)
    val Panel = Color(0xFF1A1714)
    val PanelRaised = Color(0xFF242019)
    val Hairline = Color(0xFF2E2923)
    val Text = Color(0xFFEDE6DA)
    val TextDim = Color(0xFFA49A8B)
    val TextFaint = Color(0xFF6F675C)
    val Amber = Color(0xFFE3A857)
    val AmberDim = Color(0xFF6B4F2C)
    val AmberInk = Color(0xFF241806)
    val Error = Color(0xFFE5826A)
}

private val scheme = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Palette.AmberInk,
    primaryContainer = Palette.AmberDim,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.TextDim,
    onSecondary = Palette.Background,
    secondaryContainer = Palette.PanelRaised,
    onSecondaryContainer = Palette.Text,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Panel,
    onSurface = Palette.Text,
    surfaceVariant = Palette.PanelRaised,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Palette.PanelRaised,
    surfaceContainerHighest = Palette.PanelRaised,
    surfaceContainerLow = Palette.Panel,
    outline = Palette.Hairline,
    outlineVariant = Palette.Hairline,
    error = Palette.Error,
    inverseSurface = Palette.Text,
    inverseOnSurface = Palette.Background,
)

private val typography = Typography().let { t ->
    t.copy(
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}

/** Numeric readouts: tabular figures so values do not jitter while dragging. */
val NumberStyle = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium)

@Composable
fun SpektraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
