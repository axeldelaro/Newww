package com.kinetix.coach.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val KinetixDarkColorScheme = darkColorScheme(
    primary = SignalOrange,
    onPrimary = CrispWhite,
    primaryContainer = SignalOrangeDim,
    onPrimaryContainer = CrispWhite,
    secondary = SteelGray,
    onSecondary = ObsidianBg,
    secondaryContainer = ElevatedSurface,
    onSecondaryContainer = CrispWhite,
    tertiary = FormGood,
    onTertiary = CrispWhite,
    background = ObsidianBg,
    onBackground = CrispWhite,
    surface = CharcoalSurface,
    onSurface = CrispWhite,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = SteelGray,
    outline = BorderHairline,
    outlineVariant = BorderHairline,
    error = FormFault,
    onError = CrispWhite
)

private val KinetixShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp)
)

@Composable
fun KinetixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KinetixDarkColorScheme,
        typography = KinetixTypography,
        shapes = KinetixShapes,
        content = content
    )
}
