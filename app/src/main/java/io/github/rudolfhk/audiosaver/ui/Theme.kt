package io.github.rudolfhk.audiosaver.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Brand palette: orange (action), purple (accents, the icon's background), near-black (dark
 * surfaces). The same on every phone - deliberately no wallpaper ("dynamic") colours, so the
 * app is recognisable. Every text/background pair below is at least WCAG AA (4.5:1).
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFB84A0B),             // burnt orange: buttons, links, selection
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC9),
    onPrimaryContainer = Color(0xFF3A1300),
    secondary = Color(0xFF6B3FA0),           // royal purple: section titles, chips
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDDCFF),
    onSecondaryContainer = Color(0xFF270055),
    tertiary = Color(0xFF8A3FB0),            // orchid: hints ("already saved")
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8D8FF),
    onTertiaryContainer = Color(0xFF330046),
    background = Color(0xFFFFF8F5),          // warm off-white
    onBackground = Color(0xFF211A17),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF211A17),
    surfaceVariant = Color(0xFFF4DED4),
    onSurfaceVariant = Color(0xFF52443C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEBE3),
    surfaceContainerHigh = Color(0xFFF6E5DD),
    surfaceContainerHighest = Color(0xFFF0DFD7),
    outline = Color(0xFF85736A),
    outlineVariant = Color(0xFFD8C2B8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68C),             // soft orange glows on black without glaring
    onPrimary = Color(0xFF552000),
    primaryContainer = Color(0xFF7A3100),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFD6BBFF),
    onSecondary = Color(0xFF3D0F72),
    secondaryContainer = Color(0xFF54278A),
    onSecondaryContainer = Color(0xFFEDDCFF),
    tertiary = Color(0xFFEBB2FF),
    onTertiary = Color(0xFF51146B),
    tertiaryContainer = Color(0xFF6C2E86),
    onTertiaryContainer = Color(0xFFF8D8FF),
    // Near-black with a hint of purple; matches values-night/colors.xml window_background.
    background = Color(0xFF141118),
    onBackground = Color(0xFFE9E0E6),
    surface = Color(0xFF141118),
    onSurface = Color(0xFFE9E0E6),
    surfaceVariant = Color(0xFF3A3340),
    onSurfaceVariant = Color(0xFFCFC4CF),
    surfaceContainerLowest = Color(0xFF0E0C11),
    surfaceContainerLow = Color(0xFF1B1720),
    surfaceContainer = Color(0xFF201B25),
    surfaceContainerHigh = Color(0xFF2A242F),
    surfaceContainerHighest = Color(0xFF352E3A),
    outline = Color(0xFF988E98),
    outlineVariant = Color(0xFF4B434F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AudioSaverTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AppShapes, content = content)
}
