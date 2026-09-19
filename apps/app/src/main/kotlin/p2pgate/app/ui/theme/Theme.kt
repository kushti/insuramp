package p2pgate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    secondary = Color(0xFF4E6355),
    surface = Color(0xFFF7FBF4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ED49A),
    secondary = Color(0xFFB6CCB9),
    surface = Color(0xFF101511),
)

@Composable
fun P2PGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
