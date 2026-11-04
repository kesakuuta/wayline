package app.wayline.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = WaylinePrimary,
    secondary = WaylinePrimaryGlow,
    background = WaylineBackground,
    surface = WaylineSurface,
    surfaceVariant = WaylineSurfaceElevated,
    onPrimary = WaylineTextPrimary,
    onBackground = WaylineTextPrimary,
    onSurface = WaylineTextPrimary,
    outline = WaylineBorder
)

@Composable
fun WaylineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
