package ng.name.nsikak.libaccession.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette taken from the launcher icon: deep teal, cream, terracotta, amber.
private val Teal = Color(0xFF12303A)
private val Cream = Color(0xFFF5EBD6)
private val Terracotta = Color(0xFFC9743A)
private val Amber = Color(0xFFF2B134)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Cream,
    secondary = Terracotta,
    onSecondary = Color.White,
    tertiary = Amber,
    onTertiary = Teal,
    background = Color(0xFFFBF7EE),
    surface = Color(0xFFFBF7EE),
    primaryContainer = Color(0xFFD7E6EA),
    onPrimaryContainer = Teal,
)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Teal,
    secondary = Color(0xFFE59A62),
    onSecondary = Teal,
    tertiary = Cream,
    onTertiary = Teal,
    background = Color(0xFF0E1E24),
    surface = Color(0xFF0E1E24),
    primaryContainer = Color(0xFF1D4452),
    onPrimaryContainer = Cream,
)

@Composable
fun TallyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
