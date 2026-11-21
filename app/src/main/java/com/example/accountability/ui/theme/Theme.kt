package com.example.accountability.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0F172A)
val SurfaceDark = Color(0xFF1E293B)
val PrimaryBlue = Color(0xFF3B82F6)
val PrimaryDarkBlue = Color(0xFF1D4ED8)
val AccentGreen = Color(0xFF10B981)
val DangerRed = Color(0xFFEF4444)
val WarningAmber = Color(0xFFF59E0B)
val TextLight = Color(0xFFF8FAFC)
val TextDim = Color(0xFF94A3B8)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    secondary = AccentGreen,
    background = DarkBackground,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextLight,
    onSurface = TextLight,
    error = DangerRed
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryDarkBlue,
    secondary = AccentGreen,
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    error = DangerRed
)

@Composable
fun AccountabilityTheme(
    darkTheme: Boolean = true, // Default to sleek dark mode for evening 9 PM check
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
