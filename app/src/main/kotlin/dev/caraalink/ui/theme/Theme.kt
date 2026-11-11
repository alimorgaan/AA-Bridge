package dev.caraalink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightTeal = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF042F2E),
)

private val DarkTeal = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF115E59), onPrimaryContainer = Color(0xFFCCFBF1),
)

/** Material You colors on Android 12+, the logo's teal before that; light/dark follows the system. */
@Composable
fun AaBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkTeal
        else -> LightTeal
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
