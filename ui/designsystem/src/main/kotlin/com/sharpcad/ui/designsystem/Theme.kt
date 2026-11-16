package com.sharpcad.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colours of the dark theme: neutral blue-greys with one blue accent. */
object SharpCadColors {
    val Background = Color(0xFF1B1F26)
    val Panel = Color(0xFF232832)
    val PanelRaised = Color(0xFF2C323D)
    val Divider = Color(0xFF3A4150)
    val Content = Color(0xFFE6EAF0)
    val ContentMuted = Color(0xFF9AA4B2)
    val ContentDisabled = Color(0xFF5C6573)
    val Accent = Color(0xFF3E8BFF)
    val OnAccent = Color(0xFFFFFFFF)
    val Error = Color(0xFFE5484D)
}

/** Sizes shared by the workspace chrome. */
object SharpCadDimens {
    val TopBarHeight = 48.dp
    val RailWidth = 56.dp
    val RailButton = 44.dp
    val RailIcon = 22.dp
    val PanelRadius = 12.dp
    val ButtonRadius = 10.dp
    val Gutter = 8.dp
}

private val ColorScheme = darkColorScheme(
    primary = SharpCadColors.Accent,
    onPrimary = SharpCadColors.OnAccent,
    background = SharpCadColors.Background,
    onBackground = SharpCadColors.Content,
    surface = SharpCadColors.Panel,
    onSurface = SharpCadColors.Content,
    surfaceVariant = SharpCadColors.PanelRaised,
    onSurfaceVariant = SharpCadColors.ContentMuted,
    surfaceContainer = SharpCadColors.Panel,
    surfaceContainerHigh = SharpCadColors.PanelRaised,
    outline = SharpCadColors.Divider,
    outlineVariant = SharpCadColors.Divider,
    error = SharpCadColors.Error,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(SharpCadDimens.ButtonRadius),
    medium = RoundedCornerShape(SharpCadDimens.PanelRadius),
    large = RoundedCornerShape(16.dp),
)

/** App theme. Dark only for now; light theme and accent choice come in Phase 6. */
@Composable
fun SharpCadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, shapes = AppShapes, content = content)
}
