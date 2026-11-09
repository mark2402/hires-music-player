package com.hiresplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0C0D11)
private val InkRaised = Color(0xFF17191F)
private val InkHigh = Color(0xFF22252E)
private val Gold = Color(0xFFE4C08A)
private val GoldDeep = Color(0xFF3D2E14)
private val Sage = Color(0xFFA9C9C0)
private val Mist = Color(0xFFE8E6E3)
private val MistDim = Color(0xFFB7B3AC)

private val HiresColors = darkColorScheme(
    primary = Gold,
    onPrimary = GoldDeep,
    primaryContainer = Color(0xFF5A4524),
    onPrimaryContainer = Color(0xFFFFE8C2),
    secondary = Sage,
    onSecondary = Color(0xFF12302B),
    secondaryContainer = Color(0xFF244842),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFD7B4F8),
    onTertiary = Color(0xFF3B2558),
    background = Ink,
    onBackground = Mist,
    surface = Ink,
    onSurface = Mist,
    surfaceVariant = InkHigh,
    onSurfaceVariant = MistDim,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkHigh,
    surfaceContainerHighest = Color(0xFF2C303A),
    outline = Color(0xFF46443E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun HiresTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HiresColors, content = content)
}
