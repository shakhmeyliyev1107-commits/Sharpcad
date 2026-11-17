package com.sharpcad.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colours of the dark theme: neutral blue-greys with one blue accent. */
object SharpCadColors {
    // Backed by Compose state: switching theme or accent recomposes everything that reads them.
    var Background by mutableStateOf(Color(0xFF1B1F26)); private set
    var Panel by mutableStateOf(Color(0xFF232832)); private set
    var PanelRaised by mutableStateOf(Color(0xFF2C323D)); private set
    var Divider by mutableStateOf(Color(0xFF3A4150)); private set
    var Content by mutableStateOf(Color(0xFFE6EAF0)); private set
    var ContentMuted by mutableStateOf(Color(0xFF9AA4B2)); private set
    var ContentDisabled by mutableStateOf(Color(0xFF5C6573)); private set
    var Accent by mutableStateOf(Color(0xFF3E8BFF)); private set
    val OnAccent = Color(0xFFFFFFFF)
    val Error = Color(0xFFE5484D)

    var isDark by mutableStateOf(true); private set

    /** Accent choices offered in settings, as name to 0xRRGGBB. */
    val accents = listOf("Blue" to 0x3E8BFF, "Teal" to 0x14A3A3, "Green" to 0x3FA34D, "Orange" to 0xF07A1A, "Purple" to 0x8A5CF6, "Pink" to 0xE0457B)

    fun apply(dark: Boolean, accent: Int) {
        isDark = dark
        Accent = Color(0xFF000000.toInt() or accent)
        if (dark) {
            Background = Color(0xFF1B1F26); Panel = Color(0xFF232832); PanelRaised = Color(0xFF2C323D)
            Divider = Color(0xFF3A4150); Content = Color(0xFFE6EAF0); ContentMuted = Color(0xFF9AA4B2)
            ContentDisabled = Color(0xFF5C6573)
        } else {
            Background = Color(0xFFE9ECF1); Panel = Color(0xFFFFFFFF); PanelRaised = Color(0xFFEEF1F5)
            Divider = Color(0xFFD3D9E1); Content = Color(0xFF1B1F26); ContentMuted = Color(0xFF5B6575)
            ContentDisabled = Color(0xFFA9B1BD)
        }
    }
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

private fun scheme() = (if (SharpCadColors.isDark) darkColorScheme() else lightColorScheme()).copy(
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

/** App theme; follows [SharpCadColors.apply]. */
@Composable
fun SharpCadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(), shapes = AppShapes, content = content)
}
