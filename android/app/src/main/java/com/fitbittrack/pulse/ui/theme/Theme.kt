package com.fitbittrack.pulse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** What the user picked in Settings → Appearance. */
enum class ThemeMode { System, Light, Dark }

/**
 * Pulse's own metric colours. One colour per metric, in both themes.
 * `x` is for fills, bars and lines; `xLabel` is for small text (passes 4.5:1 contrast).
 */
@Immutable
data class PulseColors(
    val move: Color,
    val moveLabel: Color,
    val heart: Color,
    val heartLabel: Color,
    val sleep: Color,
    val sleepLabel: Color,
    val sleepDeep: Color,
    val moveMuted: Color,
    val body: Color,
    val bodyLabel: Color,
    val good: Color,
    val goodContainer: Color,
    val track: Color,
)

private val DarkPulse = PulseColors(
    move = Color(0xFFF5A524), moveLabel = Color(0xFFF5B84A),
    heart = Color(0xFFFF5C74), heartLabel = Color(0xFFFF8A9B),
    sleep = Color(0xFF8E9BFF), sleepLabel = Color(0xFFAEB7FF), sleepDeep = Color(0xFF6E7BEA),
    moveMuted = Color(0xFF5A4A2A),
    body = Color(0xFF2FD3C0), bodyLabel = Color(0xFF5FE3D3),
    good = Color(0xFF5FE0A0), goodContainer = Color(0xFF10261F),
    track = Color(0xFF1E2735),
)

private val LightPulse = PulseColors(
    move = Color(0xFFE8950F), moveLabel = Color(0xFFA86200),
    heart = Color(0xFFE03A57), heartLabel = Color(0xFFC42D49),
    sleep = Color(0xFF5462E0), sleepLabel = Color(0xFF4350C9), sleepDeep = Color(0xFF3240B8),
    moveMuted = Color(0xFFF1D9AE),
    body = Color(0xFF0E9E8E), bodyLabel = Color(0xFF0B7F72),
    good = Color(0xFF157A45), goodContainer = Color(0xFFDDF5E8),
    track = Color(0xFFE3E7EE),
)

// "Night ink"
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFEEF2F8),
    onPrimary = Color(0xFF0B1018),
    secondary = Color(0xFF8E9BFF),
    onSecondary = Color(0xFF0B1018),
    background = Color(0xFF0B1018),
    onBackground = Color(0xFFEEF2F8),
    surface = Color(0xFF0B1018),
    onSurface = Color(0xFFEEF2F8),
    surfaceContainer = Color(0xFF141B26),
    surfaceContainerHigh = Color(0xFF1B2433),
    surfaceContainerLow = Color(0xFF0F1520),
    onSurfaceVariant = Color(0xFF8C97AA),
    outline = Color(0xFF2A3445),
    outlineVariant = Color(0xFF1E2735),
    error = Color(0xFFFF8A9B),
)

// "Daylight"
private val LightScheme = lightColorScheme(
    primary = Color(0xFF111827),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF5462E0),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF4F6FA),
    onSurface = Color(0xFF111827),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDF0F5),
    surfaceContainerLow = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5B6576),
    outline = Color(0xFFC9D0DB),
    outlineVariant = Color(0xFFDDE2EA),
    error = Color(0xFFC42D49),
)

val LocalPulseColors = staticCompositionLocalOf { DarkPulse }

/** Shortcut: `PulseTheme.colors.heart` anywhere inside [PulseTheme]. */
object PulseTheme {
    val colors: PulseColors
        @Composable get() = LocalPulseColors.current
}

@Composable
fun PulseTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalPulseColors provides if (dark) DarkPulse else LightPulse) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = PulseTypography,
            content = content,
        )
    }
}
