package io.github.darkryh.yukitoki.sample.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CyberBg = Color(0xFF070B19)          // Very dark slate/blue background
val CyberCard = Color(0xFF0F172A)        // Slate dark card background
val CyberBorder = Color(0xFF1E293B)      // Card border
val NeonCyan = Color(0xFF00E5FF)         // Primary Accent
val NeonPink = Color(0xFFF43F5E)         // Error or High Priority
val NeonPurple = Color(0xFFD946EF)       // Alternative high priority / highlight
val EmeraldGreen = Color(0xFF10B981)     // Success / Complete
val AmberYellow = Color(0xFFF59E0B)      // Warning / Paused
val TextWhite = Color(0xFFF8FAFC)        // Active Text
val TextMuted = Color(0xFF64748B)        // Muted labels/details

private val ColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF020617),
    secondary = NeonPurple,
    onSecondary = Color.White,
    tertiary = NeonPink,
    background = CyberBg,
    onBackground = TextWhite,
    surface = CyberCard,
    onSurface = TextWhite,
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = TextMuted,
    error = NeonPink,
    onError = Color.White
)

@Composable
fun YukitokiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content
    )
}
