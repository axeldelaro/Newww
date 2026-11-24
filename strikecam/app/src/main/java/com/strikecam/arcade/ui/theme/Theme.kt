package com.strikecam.arcade.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StrikeCamColorScheme = darkColorScheme(
    primary = LeftCrimson,
    onPrimary = CrispWhite,
    secondary = RightCyan,
    onSecondary = OledBlack,
    tertiary = PowerGold,
    onTertiary = OledBlack,
    background = OledBlack,
    onBackground = CrispWhite,
    surface = ArcadeSurface,
    onSurface = CrispWhite,
    surfaceVariant = ArcadeSurfaceElevated,
    onSurfaceVariant = SteelMuted,
    outline = ArcadeBorder,
    error = LeftCrimson,
    onError = CrispWhite
)

@Composable
fun StrikeCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StrikeCamColorScheme,
        typography = ArcadeTypography,
        content = content
    )
}
