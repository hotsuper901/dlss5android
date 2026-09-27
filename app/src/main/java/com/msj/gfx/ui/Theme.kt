package com.msj.gfx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonCyan = Color(0xFF00E5FF)
val DeepBg = Color(0xFF070A14)
val Panel = Color(0xFF111726)
val PanelHi = Color(0xFF1A2334)
val HotAmber = Color(0xFFFF7A45)
val WarnYellow = Color(0xFFFFC24B)
val OkGreen = Color(0xFF3DDC97)
val Muted = Color(0xFF7A879C)
val Body = Color(0xFFB9C6D8)
val Ink = Color(0xFFE6EDF7)

private val MsjScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DeepBg,
    primaryContainer = PanelHi,
    onPrimaryContainer = Ink,
    secondary = OkGreen,
    onSecondary = DeepBg,
    background = DeepBg,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = PanelHi,
    onSurfaceVariant = Body,
    outline = Color(0xFF2A3548),
    error = HotAmber,
    onError = DeepBg
)

@Composable
fun MsjTheme(content: @Composable () -> Unit) {
    // Deliberately dark-only: a light variant of a game HUD is never the right call.
    MaterialTheme(colorScheme = MsjScheme, content = content)
}
