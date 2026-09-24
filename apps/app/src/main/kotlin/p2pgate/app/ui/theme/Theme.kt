package p2pgate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Black & red identity. Red is the brand accent (primary); success/OK
// states use onSurface (bright) so red never means "all good".
private val LightColors = lightColorScheme(
    primary = Color(0xFFC01830),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF5A5A5A),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF111111),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5964),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF9A9A9A),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFF2F2F2),
)

@Composable
fun P2PGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
