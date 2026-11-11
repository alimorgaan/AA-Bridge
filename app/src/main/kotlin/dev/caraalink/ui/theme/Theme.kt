package dev.caraalink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** O21 "dashboard" palette: always dark, teal when things are fine, amber when something needs you. */
object Dash {
    val Background = Color(0xFF0A0E12)
    val Tile = Color(0xFF121A20)
    val TileBorder = Color(0xFF1D2A31)
    val Track = Color(0xFF16252B)
    val Teal = Color(0xFF5EEAD4)
    val Cyan = Color(0xFF22D3EE)
    val Amber = Color(0xFFFBBF24)
    val Orange = Color(0xFFF97316)
    val Text = Color(0xFFE8F1F2)
    val Muted = Color(0xFF8AA3A8)
    val GlowTeal = Color(0xFF0F2A2E)
    val GlowAmber = Color(0xFF2A1D0A)
    val GlowIdle = Color(0xFF0E1D22)
}

private val Scheme = darkColorScheme(
    primary = Dash.Teal, onPrimary = Color(0xFF032724),
    primaryContainer = Dash.GlowTeal, onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Dash.Cyan, onSecondary = Color(0xFF00222A),
    tertiary = Dash.Amber, onTertiary = Color(0xFF1A0F02),
    tertiaryContainer = Dash.GlowAmber, onTertiaryContainer = Color(0xFFFDE68A),
    error = Dash.Amber, onError = Color(0xFF1A0F02),
    background = Dash.Background, onBackground = Dash.Text,
    surface = Dash.Background, onSurface = Dash.Text,
    surfaceVariant = Dash.Track, onSurfaceVariant = Dash.Muted,
    surfaceContainerLowest = Color(0xFF0C1116), surfaceContainerLow = Color(0xFF10171C),
    surfaceContainer = Dash.Tile, surfaceContainerHigh = Color(0xFF16222A), surfaceContainerHighest = Color(0xFF1A2830),
    outline = Dash.TileBorder, outlineVariant = Dash.Track,
)

private val Base = Typography()
private val Type = Base.copy(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Bold),
)

@Composable
fun AaBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
