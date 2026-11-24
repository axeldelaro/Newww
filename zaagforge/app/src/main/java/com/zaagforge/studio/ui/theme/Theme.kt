package com.zaagforge.studio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Hardware Groovebox / Pioneer CDJ × Ableton Dark Industrial Palette (Zero AI Slop)
val RackObsidian = Color(0xFF090A0C)
val RackCharcoal = Color(0xFF13151A)
val RackMetal = Color(0xFF1C1F26)
val RackBorder = Color(0xFF2A2E38)
val RackBorderBright = Color(0xFF3D4352)

val HazardRed = Color(0xFFFF2E1F)
val HazardRedDim = Color(0x33FF2E1F)
val SignalOrange = Color(0xFFFF6B00)
val SignalOrangeDim = Color(0x33FF6B00)
val PeakAmber = Color(0xFFFFB800)
val MeterGreen = Color(0xFF1ED760)
val MeterGreenDim = Color(0x331ED760)
val CrispWhite = Color(0xFFF4F5F7)
val SteelGray = Color(0xFF8C94A6)
val SteelDim = Color(0xFF555D6E)

private val ZaagForgeColorScheme = darkColorScheme(
    primary = HazardRed,
    onPrimary = CrispWhite,
    secondary = SignalOrange,
    onSecondary = CrispWhite,
    tertiary = PeakAmber,
    background = RackObsidian,
    onBackground = CrispWhite,
    surface = RackCharcoal,
    onSurface = CrispWhite,
    surfaceVariant = RackMetal,
    onSurfaceVariant = SteelGray,
    outline = RackBorder
)

private val ZaagForgeTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun ZaagForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZaagForgeColorScheme,
        typography = ZaagForgeTypography,
        content = content
    )
}
