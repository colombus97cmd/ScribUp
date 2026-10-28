package com.scribup.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF4D8D)      // ad-libs, ligne en cours
val Accent2 = Color(0xFF7C5CFF)     // accords
val Good = Color(0xFF3DDC97)
val Warn = Color(0xFFFFB547)
val Bad = Color(0xFFFF5C5C)
val Dim = Color(0xFF8A8A99)
val Surface1 = Color(0xFF17171E)
val Surface2 = Color(0xFF22222C)
val Background = Color(0xFF0E0E12)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    onSecondary = Color.White,
    background = Background,
    onBackground = Color(0xFFEDEDF2),
    surface = Surface1,
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB9B9C6),
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF2B2B37),
    outline = Color(0xFF3A3A48),
    error = Bad,
)

@Composable
fun ScribUpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
