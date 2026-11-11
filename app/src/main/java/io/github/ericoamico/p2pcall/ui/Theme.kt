package io.github.ericoamico.p2pcall.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Mesmas cores do desktop; texto com contraste acima de 4,5:1 sobre o fundo.
private val Colors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B1B33),
    background = Color(0xFF16181D),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF16181D),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF22252C),
    onSurfaceVariant = Color(0xFFB4BAC4),
    surfaceContainer = Color(0xFF22252C),
    surfaceContainerHigh = Color(0xFF2A2E36),
    outline = Color(0xFF7A8292),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun P2PTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
